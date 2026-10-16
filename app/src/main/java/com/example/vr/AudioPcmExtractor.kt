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
 * v2.4.64 / v2.4.65：**从媒体（视频/音频）抽出 16kHz 单声道 PCM**，供离线音频分析使用。
 *
 * ## 为什么自己写（不复用 [RealtimeSubtitleEngine] 里的 `AudioTee`）
 * `AudioTee` 是 `RealtimeSubtitleEngine` 内的 **private** 类，且面向「边播边抽」的实时场景。
 * 离线分析要的是「一次性把整条音轨解出来」，逻辑更简单。
 *
 * ## 🔴 v2.4.65 修 OOM（v2.4.64 的实测事故）
 * v2.4.64 用 `ArrayList<Float>` 累积 PCM —— **每个样本都会装箱成一个 `Float` 对象**。
 * 实测（10.6 分钟、48kHz 立体声）3000 万个元素 → 数百 MB，扩容时 `Arrays.copyOf` 抛
 * `OutOfMemoryError: Failed to allocate a 39366016 byte allocation`，用户看到的就是
 * 「抽取音轨失败」。**只防「时长」是不够的**，容器本身的装箱/扩容开销才是主因。
 *
 * 现在三条一起上：
 * 1. **无装箱**：用 [FloatAccum]（裸 `FloatArray` + 手动翻倍扩容，每样本固定 4 字节）；
 * 2. **解码时就降载**：混声道 → 每 [CHUNK_SECONDS] 秒重采样到 16kHz 再入结果
 *    —— 48k→16k 让最终内存**降到 1/3**；
 * 3. **堆内存预检**：按 `上限 × 16000 × 4B` 估算并**动态下调**，宁可少分析一段，也不要 OOM。
 *
 * ## 其它处理要点
 * - 多声道**求平均**降为单声道；样本格式兼容 16-bit 与 `ENCODING_PCM_FLOAT`；
 * - **失败必带原因**（[ExtractResult.Fail.reason]），不塌缩成一句笼统提示；
 * - `smb://` / `http(s)://` 等来源**明确报「暂不支持」**（`MediaExtractor` 打不开），
 *   而不是让它掉进异常被误报成「没有音轨」。
 */
object AudioPcmExtractor {

    private const val TAG = "AudioPcmExtractor"

    /** sherpa-onnx 的 VAD / 说话人模型统一要求 16kHz */
    const val TARGET_SAMPLE_RATE = 16000

    /** 单次 dequeue 的等待上限（微秒） */
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /**
     * 默认分析上限（秒）。按「16kHz × 4B」核算：15 分钟 ≈ **57MB**
     * （+ 输入分块缓冲约 6MB），在 192MB 堆上有余量；超长片会被截断（结果里如实标注）。
     */
    const val DEFAULT_MAX_SECONDS = 15 * 60

    /** 解码缓冲分块长度（秒）—— 每满一块就重采样并落进结果，避免攒大数组 */
    private const val CHUNK_SECONDS = 30

    /** 抽取结果：成功给采样（含是否被截断），失败给**具体原因** */
    sealed class ExtractResult {
        data class Ok(val samples: FloatArray, val truncated: Boolean) : ExtractResult()
        data class Fail(val reason: String) : ExtractResult()
    }

    /**
     * @param maxSeconds 解码时长上限（秒）；<=0 表示用 [DEFAULT_MAX_SECONDS]
     * @param onProgress 0..100（按「已解码时长 / 总时长」估算）
     */
    suspend fun extract(
        context: Context,
        uri: Uri,
        maxSeconds: Int = DEFAULT_MAX_SECONDS,
        onProgress: (Int) -> Unit = {},
    ): ExtractResult = withContext(Dispatchers.IO) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            // ---- 内存预检：按可用堆把上限压到安全值 ----
            val requested = if (maxSeconds > 0) maxSeconds else DEFAULT_MAX_SECONDS
            val safeSeconds = safeMaxSeconds(requested)

            // ---- 来源检查（MediaExtractor 打不开网络来源，明确报出而非误报「无音轨」）----
            when (uri.scheme?.lowercase()) {
                "smb", "http", "https", "rtsp" -> return@withContext ExtractResult.Fail(
                    "该来源（${uri.scheme}://）暂不支持离线音频分析，请改用本机文件"
                )
            }

            extractor = try {
                MediaExtractor().apply { setDataSource(context, uri, null) }
            } catch (t: Throwable) {
                Log.w(TAG, "setDataSource 失败: ${t.message}")
                return@withContext ExtractResult.Fail("打不开该媒体：${t.message ?: t.javaClass.simpleName}")
            }

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
                return@withContext ExtractResult.Fail("该媒体没有音轨")
            }
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            Log.i(TAG, "音轨: mime=$mime rate=$srcRate ch=$srcChannels dur=${durationUs / 1000}ms 上限=${safeSeconds}s")

            codec = try {
                MediaCodec.createDecoderByType(mime).apply {
                    configure(format, null, null, 0)
                    start()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "解码器初始化失败: ${t.message}")
                return@withContext ExtractResult.Fail("解码器不支持该音频编码（$mime）")
            }

            // 输入分块：只装「源采样率的单声道」，写满就重采样掉进结果
            val chunk = FloatArray((srcRate * CHUNK_SECONDS).coerceAtLeast(srcRate))
            var chunkFill = 0
            // 结果累加器：按 16k 预分配（最多按 10 分钟铺底，超出再翻倍）
            val out = FloatAccum(
                (safeSeconds.coerceAtMost(600) * TARGET_SAMPLE_RATE).coerceAtLeast(TARGET_SAMPLE_RATE)
            )

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var truncated = false
            val lastUs = safeSeconds.toLong() * 1_000_000L

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
                            chunkFill = appendMono(buf, format, srcChannels, chunk, chunkFill)
                            if (chunkFill >= chunk.size) {
                                flushChunk(chunk, chunkFill, srcRate, out)
                                chunkFill = 0
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (durationUs > 0 && info.presentationTimeUs > 0) {
                            onProgress(((info.presentationTimeUs * 100) / durationUs).coerceIn(0, 100).toInt())
                        }
                        if (lastUs > 0 && info.presentationTimeUs > lastUs) {
                            Log.i(TAG, "已达分析上限 ${safeSeconds}s，停止解码")
                            truncated = true
                            outputDone = true
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "解码输出格式: ${codec.outputFormat}")
                    }
                }
            }

            codec.stop()
            codec.release()
            codec = null
            extractor.release()
            extractor = null

            // 残余一块
            if (chunkFill > 0) {
                flushChunk(chunk, chunkFill, srcRate, out)
            }
            if (out.size == 0) {
                return@withContext ExtractResult.Fail("解码未得到任何音频数据（编码可能不受支持）")
            }

            onProgress(100)
            Log.i(
                TAG,
                "PCM 抽取完成: ${out.size} 采样 / %.1f 秒（源 ${srcRate}Hz → 16kHz，截断=$truncated）"
                    .format(out.size.toFloat() / TARGET_SAMPLE_RATE)
            )
            ExtractResult.Ok(out.toArray(), truncated)
        } catch (t: Throwable) {
            val reason = if (t is OutOfMemoryError) {
                "内存不足（音频过长），可换更短的片子再试"
            } else {
                t.message ?: t.javaClass.simpleName
            }
            Log.e(TAG, "抽取音轨失败: $reason", t)
            ExtractResult.Fail(reason)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }

    /**
     * 按可用堆把分析上限压到安全值。
     *
     * 结果采样按 16kHz × 4B 计；只用**可用堆的一半**做预算（解码器本身还要缓冲）。
     * 例：堆上限 192MB → 预算 96MB → 约 25 分钟；用户请求 15 分钟则维持 15 分钟。
     */
    internal fun safeMaxSeconds(requested: Int): Int {
        val heap = Runtime.getRuntime().maxMemory()
        val budgetBytes = (heap * 0.5).toLong()
        val maxByHeap = (budgetBytes / (TARGET_SAMPLE_RATE * 4L)).toInt()
        return requested.coerceAtMost(maxByHeap.coerceAtLeast(60))
    }

    /** 把输入分块（源采样率、单声道）重采样到 16kHz 并追加到结果。 */
    private fun flushChunk(chunk: FloatArray, fill: Int, srcRate: Int, out: FloatAccum) {
        if (fill <= 0) return
        if (srcRate == TARGET_SAMPLE_RATE) {
            for (i in 0 until fill) out.add(chunk[i])
        } else {
            val resampled = resampleLinear(chunk.copyOf(fill), srcRate, TARGET_SAMPLE_RATE)
            for (v in resampled) out.add(v)
        }
    }

    /**
     * 把解码输出缓冲按样本格式解成 float、**多声道混合**后写入 [chunk]，返回新的写入位置。
     * ⚠️ 这里**不做重采样**（由 [flushChunk] 按块统一处理），避免跨缓冲插值的相位问题。
     */
    private fun appendMono(
        buf: ByteBuffer,
        format: MediaFormat,
        channels: Int,
        chunk: FloatArray,
        startFill: Int,
    ): Int {
        buf.order(ByteOrder.nativeOrder())
        val pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        val ch = channels.coerceAtLeast(1)
        var fill = startFill
        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val fb = buf.asFloatBuffer()
            val n = fb.remaining() / ch
            var i = 0
            while (i < n && fill < chunk.size) {
                var sum = 0f
                for (c in 0 until ch) sum += fb.get(i * ch + c)
                chunk[fill++] = sum / ch
                i++
            }
        } else {
            val sb = buf.asShortBuffer()
            val n = sb.remaining() / ch
            var i = 0
            while (i < n && fill < chunk.size) {
                var sum = 0f
                for (c in 0 until ch) sum += sb.get(i * ch + c) / 32768f
                chunk[fill++] = sum / ch
                i++
            }
        }
        return fill
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

    /**
     * **无装箱**的可增长 float 数组。
     *
     * 🔴 v2.4.64 的 OOM 就是 `ArrayList<Float>` 造成的：泛型 `ArrayList<Float>` 会把每个元素
     * **装箱**成 `Float` 对象（对象头 + 引用，约 20B/元素），3000 万样本即数百 MB，
     * 且扩容时 `Arrays.copyOf` 需要双份临时空间。这里用裸 `FloatArray` + 翻倍扩容，
     * 每样本固定 4 字节。
     */
    private class FloatAccum(initialCapacity: Int) {
        var data = FloatArray(initialCapacity.coerceAtLeast(1024))
            private set
        var size = 0
            private set

        fun add(v: Float) {
            if (size == data.size) {
                data = data.copyOf((data.size * 2).coerceAtLeast(1024))
            }
            data[size++] = v
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }
}
