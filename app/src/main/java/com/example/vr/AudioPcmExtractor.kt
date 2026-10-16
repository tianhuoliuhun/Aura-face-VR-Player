package com.example.vr

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * v2.4.64：**从媒体（视频/音频）抽出 16kHz 单声道 PCM**，供离线音频分析使用。
 *
 * ## 为什么自己写（不复用 [RealtimeSubtitleEngine] 里的 `AudioTee`）
 * `AudioTee` 是 `RealtimeSubtitleEngine` 内的 **private** 类，且面向「边播边抽」的实时场景
 * （跟着播放位置走、有丢弃策略）。离线分析要的是「**一次性把整条音轨解出来**」，
 * 逻辑更简单：`MediaExtractor` 取音轨 → `MediaCodec` 解码 → 转 float → 重采样到 16kHz。
 *
 * ## 处理要点
 * 1. **采样率**：源可能是 44.1k/48k，统一线性重采样到 **16kHz**（sherpa 的 VAD 与说话人模型都吃 16k）；
 * 2. **声道**：多声道**求平均**降为单声道；
 * 3. **样本格式**：解码器一般输出 16-bit PCM；也兼容 `ENCODING_PCM_FLOAT`（部分设备）；
 * 4. 失败（无音轨 / 解码错误）返回 `null`，由调用方给出提示 —— **不抛异常**。
 */
object AudioPcmExtractor {

    private const val TAG = "AudioPcmExtractor"
    /** sherpa-onnx 的 VAD / 说话人模型统一要求 16kHz */
    const val TARGET_SAMPLE_RATE = 16000
    /** 单次解码超时保护（毫秒），防止个别文件把后台任务挂死 */
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /**
     * @param onProgress 0..100（按「已解码时长 / 总时长」估算）
     * @param maxSeconds **解码时长上限**（默认 20 分钟）。多声道 float PCM 的内存占用约
     *        16kHz×4B×2（双声道）≈ 128KB/秒，1 小时就是 ~460MB —— 远超移动端余量。
     *        离线分析不需要整片，故截断（说话人分离本身也是「全局聚类」，截断会让
     *        说话人编号只覆盖被分析的那一段，这一点会在结果里如实标注）。
     * @return 归一化到 [-1,1] 的单声道 16kHz 采样；失败返回 null
     */
    suspend fun extract(
        context: Context,
        uri: Uri,
        maxSeconds: Int = 20 * 60,
        onProgress: (Int) -> Unit = {},
    ): FloatArray? = withContext(Dispatchers.IO) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            extractor = MediaExtractor().apply { setDataSource(context, uri, null) }

            // ---- 找音轨 ----
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                Log.w(TAG, "该媒体没有音轨: $uri")
                return@withContext null
            }
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            Log.i(TAG, "音轨: mime=$mime rate=$srcRate ch=$srcChannels dur=${durationUs / 1000}ms")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val out = ArrayList<Float>(srcRate * 8) // 预分配约 8 秒的量，避免频繁扩容
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val buf = codec.getOutputBuffer(outIdx)!!
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            appendPcm(buf, format, srcChannels, out)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (durationUs > 0 && info.presentationTimeUs > 0) {
                            onProgress(((info.presentationTimeUs * 100) / durationUs).coerceIn(0, 100).toInt())
                        }
                        // v2.4.64：达到时长上限即停（内存保护，见方法注释）
                        if (maxSeconds > 0 && info.presentationTimeUs > maxSeconds * 1_000_000L) {
                            Log.i(TAG, "已达分析上限 ${maxSeconds}s，停止解码")
                            outputDone = true
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // 部分解码器在此才给出真实格式（如样本格式），已按 format 兜底处理
                        Log.d(TAG, "输出格式变更: ${codec.outputFormat}")
                    }
                    // INFO_TRY_AGAIN_LATER：继续循环
                }
            }
            codec.stop()
            codec.release()
            codec = null
            extractor.release()
            extractor = null

            if (out.isEmpty()) {
                Log.w(TAG, "解码结果为空: $uri")
                return@withContext null
            }

            val resampled = if (srcRate != TARGET_SAMPLE_RATE) {
                Log.i(TAG, "重采样 ${srcRate}Hz -> ${TARGET_SAMPLE_RATE}Hz（${out.size} 采样）")
                resampleLinear(out.toFloatArray(), srcRate, TARGET_SAMPLE_RATE)
            } else {
                out.toFloatArray()
            }
            onProgress(100)
            Log.i(TAG, "PCM 抽取完成: ${resampled.size} 采样 / %.1f 秒".format(resampled.size.toFloat() / TARGET_SAMPLE_RATE))
            resampled
        } catch (t: Throwable) {
            Log.e(TAG, "抽取音轨失败: ${t.message}", t)
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }

    /** 把解码输出缓冲按样本格式解成 float 并（多声道）混合进 [out]。 */
    private fun appendPcm(buf: ByteBuffer, format: MediaFormat, channels: Int, out: ArrayList<Float>) {
        buf.order(ByteOrder.nativeOrder())
        val pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        val ch = channels.coerceAtLeast(1)
        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val fb = buf.asFloatBuffer()
            var i = 0
            while (i + ch <= fb.remaining()) {
                var sum = 0f
                for (c in 0 until ch) sum += fb.get(i + c)
                out.add(sum / ch)
                i += ch
            }
        } else {
            // 默认按 16-bit little-endian
            val sb = buf.asShortBuffer()
            var i = 0
            while (i + ch <= sb.remaining()) {
                var sum = 0f
                for (c in 0 until ch) sum += sb.get(i + c) / 32768f
                out.add(sum / ch)
                i += ch
            }
        }
    }

    /** 线性插值重采样（离线一次，质量足够；不引入额外依赖）。 */
    internal fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate || input.isEmpty()) return input
        val ratio = srcRate.toDouble() / dstRate
        val outLen = (input.size / ratio).roundToInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i * ratio
            val i0 = pos.toInt()
            val i1 = (i0 + 1).coerceAtMost(input.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = input[i0] * (1f - frac) + input[i1] * frac
        }
        return out
    }
}
