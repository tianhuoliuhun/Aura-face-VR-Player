package com.example.vr

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * 长片流式人声分离与按说话人重组管线（消除 90 秒内存上限）。
 *
 * 工作流：
 * `AudioPcmExtractor44k.stream`（15s 滑窗、2s 重叠）
 *   → 单步前瞻确定 `isFirst` / `isLast`
 *   → `MdxSeparator.separateSingleWindow` 推理
 *   → `MdxReprocessor.applyWindow` 按全局样本偏移无缝淡化替换
 *   → 流式追加写入 `.wav.tmp`，结束后回填 WAV 头并原子重命名为 `.wav` 缓存文件。
 *
 * 峰值内存恒定在 ~20MB，支持任意长度长片，且支持协程取消与断点清理。
 */
object MdxStreamingPipeline {

    private const val TAG = "MdxStreamingPipeline"
    private const val DIR_NAME = "gender_filter"

    data class StreamResult(
        val success: Boolean,
        val outputFile: File? = null,
        val processedSeconds: Int = 0,
        val cancelled: Boolean = false,
        val errorMessage: String? = null,
    )

    fun cacheDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /** 根据媒体 URI 生成稳定的缓存文件名键（SHA-256 前 16 位）。 */
    fun cacheKey(uriString: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uriString.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /** 若该媒体已完成过滤 WAV 缓存且文件有效，返回该文件，否则 null。 */
    fun cachedFileIfReady(context: Context, uriString: String?): File? {
        if (uriString.isNullOrBlank()) return null
        val f = File(cacheDir(context), "${cacheKey(uriString)}.wav")
        return if (f.exists() && f.length() > 44L) f else null
    }

    fun deleteCached(context: Context, uriString: String?) {
        if (uriString.isNullOrBlank()) return
        val f = File(cacheDir(context), "${cacheKey(uriString)}.wav")
        if (f.exists()) f.delete()
    }

    /**
     * 对整部媒体执行流式人声分离与指定说话人过滤，输出至持久化缓存 WAV。
     */
    suspend fun processToCache(
        context: Context,
        uri: Uri,
        segments: List<AudioGenderAnalyzer.SpeakerSegment>,
        selectedSpeakers: Set<Int>,
        onProgress: (pct: Int, processedSec: Int) -> Unit = { _, _ -> },
    ): StreamResult = withContext(Dispatchers.IO) {
        val uriStr = uri.toString()
        val finalFile = File(cacheDir(context), "${cacheKey(uriStr)}.wav")
        val tmpFile = File(cacheDir(context), "${cacheKey(uriStr)}.wav.tmp")
        if (tmpFile.exists()) tmpFile.delete()

        val totalMs = queryDurationMs(context, uri)
        val totalExpectedSamples = if (totalMs > 0) (totalMs * MdxDsp.SAMPLE_RATE / 1000L) else 0L

        val vocalL = FloatArray(MdxDsp.OUTER_CHUNK)
        val vocalR = FloatArray(MdxDsp.OUTER_CHUNK)
        val weightsScratch = FloatArray(MdxDsp.OUTER_CHUNK)
        val pcmBytes = ByteArray(MdxDsp.OUTER_CHUNK * 4)

        var bos: BufferedOutputStream? = null
        var totalWrittenSamples = 0L
        var chunkIdx = 0
        var pendingL: FloatArray? = null
        var pendingR: FloatArray? = null
        var pendingLen = 0
        var failed = false
        var abortedByCancel = false

        fun handleWindow(
            winL: FloatArray,
            winR: FloatArray,
            len: Int,
            isFirst: Boolean,
            isLast: Boolean,
            globalStartSample: Long,
        ): Boolean {
            val range = MdxSeparator.separateSingleWindow(
                context = context,
                winL = winL,
                winR = winR,
                length = len,
                isFirst = isFirst,
                isLast = isLast,
                outVocalL = vocalL,
                outVocalR = vocalR,
            ) ?: return false

            val validFrom = range.first
            val validTo = range.last + 1
            if (validTo <= validFrom) return true

            MdxReprocessor.applyWindow(
                mixL = winL,
                mixR = winR,
                vocalL = vocalL,
                vocalR = vocalR,
                validFrom = validFrom,
                validTo = validTo,
                windowGlobalStartSample = globalStartSample,
                segments = segments,
                selected = selectedSpeakers,
                weightsScratch = weightsScratch,
            )

            var outByteCount = 0
            for (i in validFrom until validTo) {
                val sl = (winL[i].coerceIn(-1f, 1f) * 32767f).toInt()
                val sr = (winR[i].coerceIn(-1f, 1f) * 32767f).toInt()
                pcmBytes[outByteCount++] = (sl and 0xFF).toByte()
                pcmBytes[outByteCount++] = ((sl ushr 8) and 0xFF).toByte()
                pcmBytes[outByteCount++] = (sr and 0xFF).toByte()
                pcmBytes[outByteCount++] = ((sr ushr 8) and 0xFF).toByte()
            }
            bos?.write(pcmBytes, 0, outByteCount)
            totalWrittenSamples += (validTo - validFrom)
            val sec = (totalWrittenSamples / MdxDsp.SAMPLE_RATE).toInt()
            val pct = if (totalExpectedSamples > 0) {
                (totalWrittenSamples * 100 / totalExpectedSamples).toInt().coerceIn(0, 99)
            } else {
                // 未知总长时用平滑渐进占位
                (sec.coerceAtMost(95))
            }
            onProgress(pct, sec)
            return true
        }

        try {
            bos = BufferedOutputStream(FileOutputStream(tmpFile), 65536)
            // 先写 44 字节 WAV 占位头
            bos.write(ByteArray(44))

            val streamOk = AudioPcmExtractor44k.stream(context, uri) { l, r, n ->
                try {
                    coroutineContext.ensureActive()
                } catch (_: kotlinx.coroutines.CancellationException) {
                    abortedByCancel = true
                    return@stream false
                }
                if (pendingL != null) {
                    val globalStart = chunkIdx.toLong() * MdxDsp.OUTER_STEP
                    val ok = handleWindow(
                        winL = pendingL!!,
                        winR = pendingR!!,
                        len = pendingLen,
                        isFirst = (chunkIdx == 0),
                        isLast = false,
                        globalStartSample = globalStart,
                    )
                    if (!ok) {
                        failed = true
                        return@stream false
                    }
                    chunkIdx++
                }
                pendingL = l
                pendingR = r
                pendingLen = n
                true
            }

            if (abortedByCancel) {
                return@withContext StreamResult(success = false, cancelled = true)
            }
            if (failed || !streamOk) {
                return@withContext StreamResult(
                    success = false,
                    errorMessage = "流式解码或分离中断",
                )
            }

            // 处理最后一个挂起窗口（isLast = true）
            if (pendingL != null && pendingLen > 0) {
                coroutineContext.ensureActive()
                val globalStart = chunkIdx.toLong() * MdxDsp.OUTER_STEP
                val ok = handleWindow(
                    winL = pendingL!!,
                    winR = pendingR!!,
                    len = pendingLen,
                    isFirst = (chunkIdx == 0),
                    isLast = true,
                    globalStartSample = globalStart,
                )
                if (!ok) {
                    return@withContext StreamResult(
                        success = false,
                        errorMessage = "最后窗口分离失败",
                    )
                }
            }

            bos.flush()
            bos.close()
            bos = null

            if (totalWrittenSamples <= 0L) {
                return@withContext StreamResult(success = false, errorMessage = "有效输出样本为空")
            }

            // 回填标准 44.1kHz 16-bit 立体声 WAV 文件头
            patchWavHeader(tmpFile, totalWrittenSamples * 4L, MdxDsp.SAMPLE_RATE, 2)
            if (finalFile.exists()) finalFile.delete()
            if (!tmpFile.renameTo(finalFile)) {
                return@withContext StreamResult(success = false, errorMessage = "重命名缓存文件失败")
            }

            val totalSec = (totalWrittenSamples / MdxDsp.SAMPLE_RATE).toInt()
            onProgress(100, totalSec)
            Log.i(TAG, "长片流式分离完成: ${totalSec}s -> ${finalFile.name} (${finalFile.length() / 1024}KB)")
            StreamResult(
                success = true,
                outputFile = finalFile,
                processedSeconds = totalSec,
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            abortedByCancel = true
            StreamResult(success = false, cancelled = true)
        } catch (t: Throwable) {
            Log.e(TAG, "流式分离异常: ${t.message}", t)
            StreamResult(success = false, errorMessage = t.message)
        } finally {
            runCatching { bos?.close() }
            if (abortedByCancel || failed || !finalFile.exists()) {
                runCatching { if (tmpFile.exists()) tmpFile.delete() }
            }
        }
    }

    private fun queryDurationMs(context: Context, uri: Uri): Long {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Throwable) {
            0L
        } finally {
            runCatching { mmr.release() }
        }
    }

    /** 向 WAV 文件头 0..43 字节写入标准 16-bit PCM 头。 */
    internal fun patchWavHeader(file: File, dataBytes: Long, sampleRate: Int, channels: Int) {
        val byteRate = sampleRate.toLong() * channels * 2
        val blockAlign = (channels * 2).toShort()
        val riffSize = (36L + dataBytes).coerceAtMost(0xFFFFFFFFL)
        val clampedData = dataBytes.coerceAtMost(0xFFFFFFFFL)

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.writeBytes("RIFF")
            raf.writeIntLE(riffSize.toInt())
            raf.writeBytes("WAVEfmt ")
            raf.writeIntLE(16)
            raf.writeShortLE(1)
            raf.writeShortLE(channels.toShort())
            raf.writeIntLE(sampleRate)
            raf.writeIntLE(byteRate.toInt())
            raf.writeShortLE(blockAlign)
            raf.writeShortLE(16)
            raf.writeBytes("data")
            raf.writeIntLE(clampedData.toInt())
        }
    }

    private fun RandomAccessFile.writeIntLE(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
        write((v ushr 16) and 0xFF)
        write((v ushr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeShortLE(v: Short) {
        val iv = v.toInt()
        write(iv and 0xFF)
        write((iv ushr 8) and 0xFF)
    }
}
