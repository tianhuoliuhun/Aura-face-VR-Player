package com.example.vr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.File

/**
 * v2.6.7：从 `RealtimeSubtitleEngine`（原 1557 行）拆出的**音频解码层**。
 * 逻辑**原样搬迁**，只调整了可见性与常量引用。
 *
 * 内容：
 *  - [ASR_SAMPLE_RATE]：ASR 统一采样率。引擎与本文件**共用这一个来源** ——
 *    两处各写一份 16000 是隐患，一旦不一致字幕时间轴会整体偏移。
 *  - [DecodedAudio]：一次窗口解码的结果（PCM + 起始 PTS）。
 *  - [SmbMediaDataSource]：把 smb:// 暴露成 framework 的 MediaDataSource。
 *  - [AudioTee]：另开一套 MediaExtractor + MediaCodec 的独立音频解码器
 *    （与播放器解耦，可超前解码；http/smb 各有带请求头的打开方式）。
 */

internal const val ASR_SAMPLE_RATE = 16000

/**
 * 日志 TAG。
 *
 * 保持 "RealtimeSubtitle" 不变：拆分前 AudioTee 用的就是引擎的 TAG，
 * 沿用可以让既有的抓日志习惯（logcat -s RealtimeSubtitle）继续有效。
 */
private const val TAG = "RealtimeSubtitle"

internal class DecodedAudio(val samples: FloatArray, val ptsBaseMs: Long)


/**
 * 独立解码同文件的音频轨（文档 8.1）。
 *
 * 与整片转写（v127 已移除）的差别：那个是「整片一次性解码」，本类需要
 * **按范围反复解码**（预读窗口滚动 + seek），因此保持 extractor/codec 常驻，
 * 复用 codec 实例（创建开销约 50–100ms，不能每段重建）。
 */
/**
 * v2.0.142：把 smb:// 暴露成 framework 的 [MediaDataSource]，让 [MediaExtractor]
 * 能真随机访问（per-window seek）。底层用 jcifs [SmbRandomAccessFile]，与
 * [SmbDataSource] 的 seek 实现一致（真随机访问，非 skip 顺序读）。
 */
internal class SmbMediaDataSource(private val smbUri: String) : MediaDataSource() {
    private val raf = SmbRandomAccessFile(SmbFile(smbUri), "r")

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= raf.length()) return -1
        raf.seek(position)
        val n = raf.read(buffer, offset, size)
        return if (n < 0) -1 else n
    }

    override fun getSize(): Long = raf.length()

    override fun close() {
        try {
            raf.close()
        } catch (_: Exception) {
        }
    }
}

internal class AudioTee(private val context: Context, private val uri: Uri) {

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var outRate = ASR_SAMPLE_RATE
    private var outChannels = 1

    /** v127b：媒体时长（毫秒，0 表示未知），用于 UI 显示实时字幕生成进度 */
    var durationMs: Long = 0L
        private set

    /**
     * 打开音频轨。返回 false 表示没有可用音轨。
     * 用文件描述符打开：`setDataSource(context, uri, null)` 对相册（photo picker）
     * 的临时 URI 会抛 "Failed to instantiate extractor"（v123 实测）。
     */
    /** open() 的结果（审查 #4）：区分「真无音轨」与「编码/IO/转封装失败」，
     *  避免所有失败都被统一误报成「没有可用的音轨」。 */
    internal sealed class AudioTeeOpenResult {
        object Success : AudioTeeOpenResult()
        object NoTrack : AudioTeeOpenResult()            // 真无音轨
        data class UnsupportedCodec(val mime: String) : AudioTeeOpenResult()
        object IoFailed : AudioTeeOpenResult()
        data class RemuxFailed(val code: Int) : AudioTeeOpenResult()
    }

    fun open(): AudioTeeOpenResult {
        val scheme = uri.scheme?.lowercase()
        if (scheme == "smb") {
            // v2.0.142：smb://（应用内 SMB 浏览器）走 jcifs 真随机访问，
            // openFileDescriptor 对 smb:// 必然抛异常（与 http 同源的「无音轨」误报）。
            val ex = MediaExtractor()
            try {
                ex.setDataSource(SmbMediaDataSource(uri.toString()))
                extractor = ex
                return openCodec(ex)
            } catch (e: Exception) {
                Log.w(TAG, "AudioTee smb 直连失败（${e.message}），回退整文件下载")
                try { ex.release() } catch (_: Exception) {}
                val tmp = downloadSmbToTemp(uri) ?: return AudioTeeOpenResult.IoFailed
                val ex2 = MediaExtractor()
                try {
                    ex2.setDataSource(tmp.absolutePath)
                } catch (e2: Exception) {
                    Log.w(TAG, "AudioTee smb 临时文件打开失败：${e2.message}")
                    try { ex2.release() } catch (_: Exception) {}
                    return AudioTeeOpenResult.IoFailed
                }
                extractor = ex2
                return openCodec(ex2)
            }
        }
        if (scheme == "http" || scheme == "https") {
            // v2.6.7：**优先用带请求头的 HttpMediaDataSource**。
            // 框架自带 HTTP 栈（setDataSource(context, uri, null)）不带 UA / Range，
            // 对校验这两个头的 CDN 一律 403 —— 这正是「音频读取失败（IO）」的成因。
            val ex = MediaExtractor()
            try {
                ex.setDataSource(HttpMediaDataSource(uri.toString()))
                extractor = ex
                return openCodec(ex)
            } catch (e: Exception) {
                Log.w(TAG, "AudioTee http 带头直连失败（${e.message}），回退框架栈")
                try { ex.release() } catch (_: Exception) {}
            }
        
            // 次选：框架栈直连 —— 本地回环代理（http://127.0.0.1:port）之类不需要头，仍然有效
            val ex2 = MediaExtractor()
            try {
                ex2.setDataSource(context, uri, null)
                extractor = ex2
                return openCodec(ex2)
            } catch (e: Exception) {
                Log.w(TAG, "AudioTee http 框架栈也失败（${e.message}），回退整文件临时下载")
                try { ex2.release() } catch (_: Exception) {}
            }
        
            // 最后兜底：分块下载到临时文件（下载内部已带 UA/Referer 并循环补齐）
            val tmp = downloadToTemp(uri) ?: return AudioTeeOpenResult.IoFailed
            val ex3 = MediaExtractor()
            try {
                ex3.setDataSource(tmp.absolutePath)
            } catch (e2: Exception) {
                Log.w(TAG, "AudioTee 临时文件打开失败：${e2.message}")
                try { ex3.release() } catch (_: Exception) {}
                return AudioTeeOpenResult.IoFailed
            }
            extractor = ex3
            return openCodec(ex3)
        }
        val ex = MediaExtractor()
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                try {
                    ex.setDataSource(pfd.fileDescriptor)
                } catch (e: Exception) {
                    // fd 不可 seek 时回退到临时文件（与 v123 同策略）
                    Log.w(TAG, "AudioTee fd 方式失败（${e.message}），回退临时文件")
                    pfd.close()
                    val tmp = copyToTemp(uri) ?: return AudioTeeOpenResult.IoFailed
                    val ex2 = MediaExtractor()
                    // v2.4.21：临时文件也解不开时（实测：多段 OpenDML AVI —— 框架
                    // `Failed to instantiate extractor`，复制 1.19GB 纯属白费）不再
                    // 把异常抛给外层（那样只会得到一句 `open failed`），而是转入
                    // FFmpeg 转封装兜底。
                    val ok2 = try {
                        ex2.setDataSource(tmp.absolutePath); true
                    } catch (e2: Exception) {
                        Log.w(TAG, "AudioTee 临时文件也解不了（${e2.message}）→ 走 FFmpeg 转封装兜底")
                        false
                    }
                    if (ok2) {
                        extractor = ex2
                        return openCodec(ex2)
                    }
                    tmp.delete()   // 既然解不开，1.19GB 的副本没有留下的意义
                    return openViaFfmpegRemux()
                }
                pfd.close()
            } else {
                return AudioTeeOpenResult.IoFailed
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioTee open failed: ${e.message}")
            return AudioTeeOpenResult.IoFailed
        }
        extractor = ex
        return openCodec(ex)
    }

    private fun openCodec(ex: MediaExtractor): AudioTeeOpenResult {
        var audioTrack = -1
        var mime = ""
        var durationUs = 0L
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val m = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith("audio/")) {
                audioTrack = i; mime = m
                durationUs = if (f.containsKey(MediaFormat.KEY_DURATION)) {
                    f.getLong(MediaFormat.KEY_DURATION)
                } else 0L
                break
            }
        }
        if (audioTrack < 0) return AudioTeeOpenResult.NoTrack
        durationMs = durationUs / 1000L
        ex.selectTrack(audioTrack)
        return try {
            val c = MediaCodec.createDecoderByType(mime)
            c.configure(ex.getTrackFormat(audioTrack), null, null, 0)
            c.start()
            codec = c
            AudioTeeOpenResult.Success
        } catch (e: Exception) {
            Log.w(TAG, "AudioTee codec 创建失败：${e.message}（暂不支持该音频格式）")
            AudioTeeOpenResult.UnsupportedCodec(mime)
        }
    }

    private fun copyToTemp(uri: Uri): File? = try {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        val tmp = File(context.cacheDir, "rt_asr_src.tmp")
        pfd.use { p ->
            java.io.FileInputStream(p.fileDescriptor).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
        }
        tmp
    } catch (e: Exception) {
        null
    }

    /**
     * v2.4.21：**FFmpeg 转封装兜底** —— 框架 MediaExtractor 彻底解不开的容器
     * （实测：多段 OpenDML AVI，连复制成临时文件也一样），
     * 先用 libavformat 把它转封装成 MKV（**不解码**、纯容器转换，1.19GB 约 30~60s），
     * 再由框架 extractor 解 MKV 抽音频。
     *
     * 产物按 URI 缓存（`asr_remuxed_<hash>.mkv`）—— 同一媒体不重复转。
     * ⚠️ 同步、耗时 —— 调用方（open 的协程）本就在后台线程，可接受。
     */
    private fun openViaFfmpegRemux(): AudioTeeOpenResult {
        val outFile = java.io.File(context.cacheDir, "asr_remuxed_${uri.hashCode()}.mkv")
        if (!outFile.exists() || outFile.length() < 1024L) {
            Log.i(TAG, "AudioTee 走 FFmpeg 转封装兜底（框架解不开该容器）→ ${outFile.name}")
            val r = FfmpegRemuxer.remuxToMkv(context, uri, outFile)
            if (!r.success) {
                Log.w(TAG, "FFmpeg 转封装失败 code=${r.code}: ${r.message}")
                outFile.delete()
                return AudioTeeOpenResult.RemuxFailed(r.code)
            }
        } else {
            Log.i(TAG, "AudioTee 复用已有转封装产物：${outFile.name}")
        }
        val ex3 = MediaExtractor()
        val ok = try {
            ex3.setDataSource(outFile.absolutePath); true
        } catch (e: Exception) {
            Log.w(TAG, "AudioTee 转封装产物仍解不开：${e.message}")
            false
        }
        if (!ok) {
            outFile.delete()
            return AudioTeeOpenResult.IoFailed
        }
        extractor = ex3
        return openCodec(ex3)
    }

    /** v2.0.140：http(s) 直连失败时的兜底——经回环代理整文件下载到缓存再打开 */
    // v2.6.7：兜底下载也要带 UA/Referer，并且**必须分块循环** ——
    // 该类 CDN 的单次 Range 响应最多推进到 5 MiB 边界，一次 copyTo 会得到残缺文件。
    private fun downloadToTemp(uri: Uri): File? = try {
        val tmp = File(context.cacheDir, "rt_asr_src.tmp")
        val headers = HttpPlaybackHeaders.asRequestProperties() ?: emptyMap()
        val block = 5L * 1024L * 1024L
        var start = 0L
        var total = -1L
        var keep = true
        tmp.outputStream().use { out ->
            while (keep) {
                val conn = (java.net.URL(uri.toString()).openConnection()
                        as java.net.HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    headers.forEach { (k, v) -> setRequestProperty(k, v) }
                    setRequestProperty("Range", "bytes=$start-${start + block - 1}")
                }
                try {
                    val code = conn.responseCode
                    if (code != 200 && code != 206) {
                        Log.w(TAG, "临时下载 HTTP $code（start=$start）")
                        keep = false
                    } else {
                        if (total < 0) {
                            total = conn.getHeaderField("Content-Range")
                                ?.substringAfterLast('/', "")?.trim()?.toLongOrNull() ?: -1L
                        }
                        var got = 0L
                        val buf = ByteArray(1 shl 20)
                        conn.inputStream.use { input ->
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                got += n
                            }
                        }
                        start += got
                        if (got <= 0L) keep = false
                        if (total > 0 && start >= total) keep = false
                    }
                } finally {
                    try { conn.disconnect() } catch (_: Exception) {}
                }
            }
        }
        if (start <= 0L) null else tmp
    } catch (e: Exception) {
        Log.w(TAG, "AudioTee http 临时下载失败：${e.message}")
        null
    }

    /** v2.0.142：smb:// 直连失败时的兜底——经 jcifs 整文件下载到缓存再打开
     * （copyToTemp/downloadToTemp 都依赖 contentResolver.openFileDescriptor，对 smb:// 无效） */
    private fun downloadSmbToTemp(uri: Uri): File? = try {
        val smb = SmbFile(uri.toString())
        val tmp = File(context.cacheDir, "rt_asr_src_smb.tmp")
        smb.inputStream.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
        }
        tmp
    } catch (e: Exception) {
        Log.w(TAG, "AudioTee smb 临时下载失败：${e.message}")
        null
    }

    /** 解码 [fromMs, toMs) 区间，返回 16k 单声道样本与首样本 PTS（毫秒） */
    fun decodeWindow(fromMs: Long, toMs: Long): DecodedAudio? {
        val ex = extractor ?: return null
        val c = codec ?: return null

        // seek 到最近关键帧（可能早于 fromMs，因此必须用首样本 PTS 校正）
        ex.seekTo(fromMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        try { c.flush() } catch (_: Exception) {}

        val info = MediaCodec.BufferInfo()
        val acc = ArrayList<Float>(ASR_SAMPLE_RATE * 32)
        var ptsBaseUs = -1L
        var inputDone = false
        var outputDone = false
        val toUs = toMs * 1000L
        var pcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT
        var mono = FloatArray(0)

        while (!outputDone) {
            if (!inputDone) {
                val inIdx = c.dequeueInputBuffer(5_000)
                if (inIdx >= 0) {
                    val inBuf = c.getInputBuffer(inIdx)
                    val sz = if (inBuf != null) ex.readSampleData(inBuf, 0) else -1
                    if (sz < 0) {
                        c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        c.queueInputBuffer(inIdx, 0, sz, ex.sampleTime, 0)
                        ex.advance()
                    }
                }
            }
            val outIdx = c.dequeueOutputBuffer(info, 5_000)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = c.outputFormat
                if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) outRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) outChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                // 审查 #6：记录 PCM 编码，否则浮点/24 位/32 位输出会被当成 16 位整数 → 波形全错
                pcmEncoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    android.media.AudioFormat.ENCODING_PCM_16BIT
                }
                continue
            }
            if (outIdx < 0) {
                if (inputDone && outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // 输入已结束仍拿不到输出：避免死循环
                    if (info.size == 0 && info.presentationTimeUs == 0L) {
                        outputDone = true
                    }
                }
                continue
            }

            val buf = c.getOutputBuffer(outIdx)
            if (buf != null && info.size > 0) {
                if (ptsBaseUs < 0) ptsBaseUs = info.presentationTimeUs
                val bytes = ByteArray(info.size)
                buf.get(bytes)
                val ch = outChannels.coerceAtLeast(1)
                // 审查 #6：按解码输出的 PCM 编码解析，而非一律当 16 位。
                val bytesPerSample = when (pcmEncoding) {
                    android.media.AudioFormat.ENCODING_PCM_8BIT -> 1
                    android.media.AudioFormat.ENCODING_PCM_16BIT -> 2
                    android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                    android.media.AudioFormat.ENCODING_PCM_32BIT -> 4
                    android.media.AudioFormat.ENCODING_PCM_FLOAT -> 4
                    else -> 2
                }
                val frames = bytes.size / (bytesPerSample * ch).coerceAtLeast(1)
                val part = FloatArray(frames)
                for (f in 0 until frames) {
                    var sum = 0f
                    for (k in 0 until ch) {
                        sum += decodePcmSample(bytes, (f * ch + k) * bytesPerSample, pcmEncoding)
                    }
                    part[f] = sum / ch
                }
                mono = appendFloat(mono, part)
            }
            val ptsUs = info.presentationTimeUs
            c.releaseOutputBuffer(outIdx, false)

            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            if (ptsUs >= toUs) outputDone = true

            // 边解边降采样重采样，避免长窗口累积过多内存
            if (mono.size >= outRate) {
                val resampled = resampleLinear(mono, outRate, ASR_SAMPLE_RATE)
                for (v in resampled) acc.add(v)
                mono = FloatArray(0)
            }
        }
        if (mono.isNotEmpty()) {
            for (v in resampleLinear(mono, outRate, ASR_SAMPLE_RATE)) acc.add(v)
        }
        if (acc.isEmpty()) return null

        val baseMs = if (ptsBaseUs >= 0) ptsBaseUs / 1000L else fromMs
        val samples = FloatArray(acc.size)
        for (i in acc.indices) samples[i] = acc[i]
        return DecodedAudio(samples, baseMs)
    }

    fun close() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        try { extractor?.release() } catch (_: Exception) {}
        codec = null
        extractor = null
    }

    private fun appendFloat(a: FloatArray, b: FloatArray): FloatArray {
        if (a.isEmpty()) return b
        val out = FloatArray(a.size + b.size)
        System.arraycopy(a, 0, out, 0, a.size)
        System.arraycopy(b, 0, out, a.size, b.size)
        return out
    }

    /** 审查 #6：按 PCM 编码把一个采样点的字节解成归一化浮点（[-1,1]）。 */
    private fun decodePcmSample(bytes: ByteArray, off: Int, enc: Int): Float {
        return when (enc) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT ->
                java.nio.ByteBuffer.wrap(bytes, off, 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).float
            android.media.AudioFormat.ENCODING_PCM_8BIT ->
                ((bytes[off].toInt() and 0xff) - 128) / 128f
            android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val v = (bytes[off].toInt() and 0xff) or
                        ((bytes[off + 1].toInt() and 0xff) shl 8) or
                        ((bytes[off + 2].toInt() and 0xff) shl 16)
                val signed = if (v >= 0x800000) v - 0x1000000 else v
                signed / 8388608f
            }
            android.media.AudioFormat.ENCODING_PCM_32BIT -> {
                val v = (bytes[off].toInt() and 0xff) or
                        ((bytes[off + 1].toInt() and 0xff) shl 8) or
                        ((bytes[off + 2].toInt() and 0xff) shl 16) or
                        (bytes[off + 3].toInt() shl 24)
                v / 2147483648f
            }
            else -> { // ENCODING_PCM_16BIT 及未知 → 按 16 位有符号
                val lo = bytes[off].toInt() and 0xff
                val hi = bytes[off + 1].toInt() shl 8
                ((lo or hi)).toShort().toInt() / 32768f
            }
        }
    }

    private fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate || input.isEmpty()) return input
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val outSize = (input.size / ratio).toInt().coerceAtLeast(0)
        val out = FloatArray(outSize)
        for (i in 0 until outSize) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceAtMost(input.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = input[i0] + (input[(i0 + 1).coerceAtMost(input.size - 1)] - input[i0]) * frac
        }
        return out
    }
}

