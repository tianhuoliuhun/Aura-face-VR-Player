package com.example.vr

import android.util.Log
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 直播流与网络流实时音频旁路抓取器（基于 Media3 [TeeAudioProcessor.AudioBufferSink]）。
 *
 * ## 架构与目标
 * 1. **旁路取音，与播放完全零干扰**：挂载在 ExoPlayer 的 [DefaultAudioSink] 之前，
 *    复制即将输出到声卡的已解码 PCM 数据；
 * 2. **全协议通吃**：对于 HLS / DASH / RTSP / FLV 等无终点的直播流以及各类网络流，
 *    彻底摆脱依赖 [android.media.MediaExtractor] 的局限性（解决 LiveUnsupported 卡点）；
 * 3. **实时高质量重采样至 16kHz 单声道**：输入支持常见 44.1kHz / 48kHz 单声道/立体声/环绕声，
 *    在线执行双声道降采样与线性插值重采样，按 512 样本（Silero VAD 步进窗口）封装输出；
 * 4. **内存严格安全**：采用容量受限的 [Channel]（容量 64，约 2 秒音频缓存，DROP_OLDEST），
 *    当 ASR 处于冷启动或识别排队时绝不无上限积压内存。
 */
class LiveAudioFeed : TeeAudioProcessor.AudioBufferSink {

    companion object {
        private const val TAG = "LiveAudioFeed"
        const val TARGET_SAMPLE_RATE = 16000
        const val VAD_CHUNK_SIZE = 512
    }

    private val isRunning = AtomicBoolean(false)
    val isActive: Boolean get() = isRunning.get()

    @Volatile
    private var inputSampleRate: Int = 48000

    @Volatile
    private var inputChannelCount: Int = 2

    @Volatile
    private var inputEncoding: Int = C.ENCODING_PCM_16BIT

    /** 队列：存放 16kHz 单声道 512 维 float 数组（供 Silero VAD 与 Sherpa 直接取用） */
    private val chunkChannel = Channel<FloatArray>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // 内部连续流累积缓冲区（用于重采样与分块）
    private val lock = Any()
    private val chunkBuffer = FloatArray(VAD_CHUNK_SIZE)
    private var chunkPos = 0
    private var inPos = 0.0
    private var lastSample = 0.0f
    private var hasLastSample = false

    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            synchronized(lock) {
                chunkPos = 0
                inPos = 0.0
                lastSample = 0.0f
                hasLastSample = false
            }
            // 清理历史残留
            while (chunkChannel.tryReceive().isSuccess) {}
            Log.i(TAG, "LiveAudioFeed 已启动（监听 AudioSink 实时 PCM 输出）")
        }
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            while (chunkChannel.tryReceive().isSuccess) {}
            Log.i(TAG, "LiveAudioFeed 已停止")
        }
    }

    /**
     * 接收下一块 512 样本的 16kHz float 音频数据。
     * 若未就绪则挂起，若已停止则返回 null。
     */
    suspend fun receiveChunk(): FloatArray? {
        if (!isActive) return null
        return try {
            chunkChannel.receive()
        } catch (_: Exception) {
            null
        }
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        inputSampleRate = if (sampleRateHz > 0) sampleRateHz else 48000
        inputChannelCount = if (channelCount > 0) channelCount else 2
        inputEncoding = encoding
        synchronized(lock) {
            chunkPos = 0
            inPos = 0.0
            lastSample = 0.0f
            hasLastSample = false
        }
        Log.i(TAG, "AudioSink flush: inSampleRate=$sampleRateHz, channels=$channelCount, encoding=$encoding")
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!isActive) return
        val remaining = buffer.remaining()
        if (remaining <= 0) return

        val sampleRate = inputSampleRate
        val channels = inputChannelCount
        val encoding = inputEncoding

        // 仅处理 PCM 16-bit（Android 最通用音频管道输出格式）
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_INVALID) {
            return
        }

        val byteBuffer = buffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        val shortCount = remaining / 2
        if (shortCount < channels) return

        val totalFrames = shortCount / channels
        val ratio = sampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()

        synchronized(lock) {
            // 1. 将当前缓冲区的多声道采样点下混合为单声道 float 数组
            val mono = FloatArray(totalFrames)
            for (f in 0 until totalFrames) {
                val base = f * channels * 2
                if (base + 1 >= byteBuffer.limit()) break
                val left = byteBuffer.getShort(base).toFloat() / 32768.0f
                val right = if (channels >= 2 && base + 3 < byteBuffer.limit()) {
                    byteBuffer.getShort(base + 2).toFloat() / 32768.0f
                } else left
                mono[f] = (left + right) * 0.5f
            }

            if (!hasLastSample) {
                lastSample = mono[0]
                hasLastSample = true
            }

            // 2. 跨缓冲区严格连续线性插值重采样至 16000Hz
            while (inPos < totalFrames) {
                val idx = inPos.toInt()
                val frac = (inPos - idx).toFloat()
                val s0 = if (idx == 0) lastSample else mono[idx - 1]
                val s1 = mono[idx.coerceAtMost(totalFrames - 1)]
                val interpolated = s0 + (s1 - s0) * frac

                chunkBuffer[chunkPos++] = interpolated.coerceIn(-1.0f, 1.0f)
                if (chunkPos >= VAD_CHUNK_SIZE) {
                    val out = FloatArray(VAD_CHUNK_SIZE)
                    System.arraycopy(chunkBuffer, 0, out, 0, VAD_CHUNK_SIZE)
                    chunkChannel.trySend(out)
                    chunkPos = 0
                }
                inPos += ratio
            }

            // 结转至下一个缓冲区
            inPos -= totalFrames
            lastSample = mono[totalFrames - 1]
        }
    }
}
