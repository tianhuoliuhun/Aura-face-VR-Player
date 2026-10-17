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

/**
 * v2.4.68（档 2）：**44.1kHz 立体声流式解码**。
 *
 * ## 为什么需要另开一条路径
 * `AudioPcmExtractor` 产出的是 **16kHz 单声道**（给 sherpa 的 VAD / 说话人 / 性别模型用），
 * 而 MDX-Net 要求 **44.1kHz 立体声**。两者采样率、声道、用途都不同，无法共用；
 * 合在一起只会让「一边要降采样、一边要保立体声」互相牵制。
 *
 * ## 为什么必须流式
 * 44.1k 立体声 float 的体量：**353 KB/秒** → 15 分钟 ≈ **317 MB**，全量解码必然 OOM。
 * 因此这里**边解码边产出**：每凑满一个窗就回调，调用方处理完即可丢弃。
 *
 * ## 产出的窗口与 MDX 的外层分块**严格对齐**
 * 每次回调一个 **15 秒窗口**，相邻窗口**重叠 2 秒**（即步进 13 秒），
 * 参数直接取自 [MdxDsp.OUTER_CHUNK] / [MdxDsp.OUTER_STEP]（单一来源）。
 * 这样 `MdxSeparator` 可以直接把每窗当作它的一个外层块处理，不需要再做窗口管理。
 *
 * ## 重采样策略
 * 源采样率不是 44.1k 时，**每累积 1 秒源样本做一次线性重采样**再交给窗口切分。
 * 逐缓冲重采样会有跨缓冲相位不连续（每 ~1 千样本一次咔哒），累积 1 秒则把误差压到
 * 「每秒 ≤1 个样本」（<0.03 ms 位移），听不出来。
 */
object AudioPcmExtractor44k {

    private const val TAG = "AudioPcm44k"

    /** 目标采样率（= MDX 要求） */
    const val TARGET_RATE = MdxDsp.SAMPLE_RATE

    private const val DEQUEUE_TIMEOUT_US = 10_000L

    /** 重采样累积窗口（秒） */
    private const val RESAMPLE_ACCUM_SEC = 1

    /**
     * 流式解码为 44.1kHz 立体声，按 15 秒窗口（重叠 2 秒）回调。
     *
     * @param onChunk `(left, right, count)`；窗口长度通常 = `15*44100`，
     *                末尾不足一窗时会更短。返回 **false** 表示中止（调用方取消）。
     * @return true = 正常走完；false = 打不开/无音轨/被中止/异常
     */
    suspend fun stream(
        context: Context,
        uri: Uri,
        onChunk: (left: FloatArray, right: FloatArray, count: Int) -> Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        try {
            // 网络来源明确拒绝（与 16k 抽取器一致的做法）
            when (uri.scheme?.lowercase()) {
                "smb", "http", "https", "rtsp" -> {
                    Log.w(TAG, "来源 ${uri.scheme}:// 暂不支持流式解码")
                    return@withContext false
                }
            }
            extractor = try {
                MediaExtractor().apply { setDataSource(context, uri, null) }
            } catch (t: Throwable) {
                Log.w(TAG, "打不开媒体: ${t.message}")
                return@withContext false
            }

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
                Log.w(TAG, "没有音轨")
                return@withContext false
            }
            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val srcCh = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            Log.i(TAG, "音轨: mime=$mime rate=$srcRate ch=$srcCh → 目标 ${TARGET_RATE}Hz 立体声")

            codec = try {
                MediaCodec.createDecoderByType(mime).apply {
                    configure(format, null, null, 0)
                    start()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "解码器初始化失败: ${t.message}")
                return@withContext false
            }

            val needsResample = srcRate != TARGET_RATE
            val accumCap = if (needsResample) srcRate * RESAMPLE_ACCUM_SEC else 0
            val accL = FloatArray(accumCap)
            val accR = FloatArray(accumCap)
            var accFill = 0

            val chunker = StereoChunker(MdxDsp.OUTER_CHUNK, MdxDsp.OUTER_STEP)
            var chunkCount = 0
            var aborted = false

            // 44.1k 直通时的临时缓冲（解码块 → 声道分离）
            val tmpL = FloatArray(1 shl 16)
            val tmpR = FloatArray(1 shl 16)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone && !aborted) {
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
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val frames = decodeToStereo(buf, format, srcCh, tmpL, tmpR)
                        if (frames > 0) {
                            if (needsResample) {
                                var off = 0
                                while (off < frames) {
                                    val take = minOf(accumCap - accFill, frames - off)
                                    System.arraycopy(tmpL, off, accL, accFill, take)
                                    System.arraycopy(tmpR, off, accR, accFill, take)
                                    accFill += take
                                    off += take
                                    if (accFill == accumCap) {
                                        val rl = AudioPcmExtractor.resampleLinear(accL, srcRate, TARGET_RATE)
                                        val rr = AudioPcmExtractor.resampleLinear(accR, srcRate, TARGET_RATE)
                                        accFill = 0
                                        chunkCount += rl.size
                                        if (!chunker.feed(rl, rr, rl.size, onChunk)) { aborted = true; break }
                                    }
                                }
                            } else {
                                chunkCount += frames
                                if (!chunker.feed(tmpL, tmpR, frames, onChunk)) aborted = true
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }

            // 残余（重采样累积的尾巴 + 窗口尾巴）
            if (!aborted) {
                if (needsResample && accFill > 0) {
                    val rl = AudioPcmExtractor.resampleLinear(accL.copyOf(accFill), srcRate, TARGET_RATE)
                    val rr = AudioPcmExtractor.resampleLinear(accR.copyOf(accFill), srcRate, TARGET_RATE)
                    chunkCount += rl.size
                    if (!chunker.feed(rl, rr, rl.size, onChunk)) aborted = true
                }
                if (!aborted) chunker.flush(onChunk)
            }

            Log.i(TAG, "流式解码结束: 共 $chunkCount 样本 / %.1f 秒".format(chunkCount.toFloat() / TARGET_RATE))
            !aborted
        } catch (t: Throwable) {
            Log.e(TAG, "流式解码失败: ${t.javaClass.simpleName}: ${t.message}", t)
            false
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }

    /** 把解码输出缓冲解成**立体声**（单声道则复制到两声道；>2 声道取前两路）。返回帧数。 */
    private fun decodeToStereo(
        buf: ByteBuffer,
        format: MediaFormat,
        channels: Int,
        outL: FloatArray,
        outR: FloatArray,
    ): Int {
        buf.order(ByteOrder.nativeOrder())
        val pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        val ch = channels.coerceAtLeast(1)
        val cap = minOf(outL.size, outR.size)
        var frames = 0
        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val fb = buf.asFloatBuffer()
            val n = fb.remaining() / ch
            while (frames < n && frames < cap) {
                val base = frames * ch
                outL[frames] = fb.get(base)
                outR[frames] = if (ch >= 2) fb.get(base + 1) else fb.get(base)
                frames++
            }
        } else {
            val sb = buf.asShortBuffer()
            val n = sb.remaining() / ch
            while (frames < n && frames < cap) {
                val base = frames * ch
                outL[frames] = sb.get(base) / 32768f
                outR[frames] = if (ch >= 2) sb.get(base + 1) / 32768f else outL[frames]
                frames++
            }
        }
        return frames
    }
}

/**
 * v2.4.68：把不定长立体声输入切成「固定窗口 + 固定重叠」的产出器。
 *
 * **纯逻辑**（不碰 Android / 不碰 IO）→ 可以直接单测窗口边界是否正确，
 * 而这正是流式处理最容易出错、又最难在设备上看出来的地方（错位一窗 = 整段音频偏移）。
 *
 * @param winSamples 窗口长度（44.1k × 15s = 661500）
 * @param hopSamples 步进（44.1k × 13s = 573300）；`winSamples - hopSamples` 即重叠长度
 */
internal class StereoChunker(
    private val winSamples: Int,
    private val hopSamples: Int,
) {
    init {
        require(winSamples > 0 && hopSamples > 0 && hopSamples <= winSamples) {
            "非法窗口参数: win=$winSamples hop=$hopSamples"
        }
    }

    /** 缓冲留出 (win + hop) 的余量，便于「保留重叠 + 续写」而不用频繁扩容 */
    private val bufL = FloatArray(winSamples + hopSamples)
    private val bufR = FloatArray(winSamples + hopSamples)
    private var fill = 0

    /** 已产出的窗口数（诊断用） */
    var emitted = 0
        private set

    /**
     * 追加数据并尽可能产出窗口。
     * @return false = 回调返回 false（调用方要求中止）
     */
    fun feed(
        l: FloatArray,
        r: FloatArray,
        n: Int,
        emit: (FloatArray, FloatArray, Int) -> Boolean,
    ): Boolean {
        var off = 0
        while (off < n) {
            val need = winSamples - fill
            val take = minOf(need, n - off)
            System.arraycopy(l, off, bufL, fill, take)
            System.arraycopy(r, off, bufR, fill, take)
            fill += take
            off += take
            if (fill == winSamples) {
                // 交给调用方的是**拷贝**：避免其在后续 feed 中被覆写
                if (!emit(bufL.copyOf(winSamples), bufR.copyOf(winSamples), winSamples)) return false
                emitted++
                val keep = winSamples - hopSamples
                System.arraycopy(bufL, hopSamples, bufL, 0, keep)
                System.arraycopy(bufR, hopSamples, bufR, 0, keep)
                fill = keep
            }
        }
        return true
    }

    /** 收尾：把不足一窗的剩余也产出（末尾块）。 */
    fun flush(emit: (FloatArray, FloatArray, Int) -> Boolean): Boolean {
        if (fill <= 0) return true
        val n = fill
        fill = 0
        emitted++
        return emit(bufL.copyOf(n), bufR.copyOf(n), n)
    }
}
