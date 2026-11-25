package com.example.vr

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * 实时去男声 / 说话人过滤音频处理器（挂在 ExoPlayer AudioSink 管线中）。
 *
 * - 关闭时（`isFilterEnabled == false`）或当前片源无已处理的过滤 WAV 缓存时：**零修改原样透传**。
 * - 开启时：按当前音频时间戳从流式生成的 44.1kHz 立体声 WAV 缓存中读取过滤后 PCM，
 *   自动适配当前播放器输出采样率（线性插值重采样）与声道数，支持实时开关切换与 Seek 精确跟随。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class GenderFilterAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var isFilterEnabled: Boolean = false
        set(value) {
            val changed = field != value
            field = value
            if (changed && value) {
                needResyncFromExternal = true
            }
        }

    private val lock = Any()
    private var wavFile: File? = null
    private var raf: RandomAccessFile? = null
    private var wavTotalSamples: Long = 0L

    // 64KB 读缓存（= 16384 帧 44.1k 立体声，约 0.37 秒）
    private val cacheBytes = ByteArray(65536)
    private var cacheStartSample: Long = -1L
    private var cacheValidSamples: Int = 0

    // 音频时钟追踪（44.1kHz 空间下的连续位置）
    @Volatile
    private var externalPosMs: Long = 0L

    @Volatile
    private var needResyncFromExternal: Boolean = true
    private var cursorPos44k: Double = 0.0

    private var inSampleRate: Int = MdxDsp.SAMPLE_RATE
    private var inChannels: Int = 2

    /** 绑定当前媒体的过滤 WAV 文件（传入 null 表示解除绑定并关闭文件句柄）。 */
    fun setFilteredWav(file: File?) {
        synchronized(lock) {
            if (wavFile?.absolutePath == file?.absolutePath && raf != null) return
            closeRafLocked()
            wavFile = file
            if (file != null && file.exists() && file.length() > 44L) {
                try {
                    val r = RandomAccessFile(file, "r")
                    raf = r
                    wavTotalSamples = (file.length() - 44L) / 4L
                } catch (_: Throwable) {
                    closeRafLocked()
                }
            }
            needResyncFromExternal = true
        }
    }

    /** 由外部播放进度或 Seek 回调调用，保持音频滤镜与画面时间对齐。 */
    fun updatePlaybackPositionMs(posMs: Long, forceResync: Boolean = false) {
        val safePos = posMs.coerceAtLeast(0L)
        externalPosMs = safePos
        if (forceResync) {
            needResyncFromExternal = true
        } else if (isFilterEnabled) {
            val estimatedMs = (cursorPos44k * 1000.0 / MdxDsp.SAMPLE_RATE).toLong()
            if (kotlin.math.abs(estimatedMs - safePos) > 600L) {
                needResyncFromExternal = true
            }
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount <= 0 || inputAudioFormat.sampleRate <= 0) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        inSampleRate = inputAudioFormat.sampleRate
        inChannels = inputAudioFormat.channelCount
        return inputAudioFormat
    }

    override fun onFlush() {
        needResyncFromExternal = true
    }

    override fun onReset() {
        synchronized(lock) {
            closeRafLocked()
            wavFile = null
        }
        needResyncFromExternal = true
        cursorPos44k = 0.0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val outputBuffer = replaceOutputBuffer(remaining)
        val bytesPerFrame = inChannels * 2

        if (!isFilterEnabled || remaining < bytesPerFrame) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val replaced = synchronized(lock) {
            val r = raf
            val totalSamples = wavTotalSamples
            if (r == null || totalSamples <= 0L) {
                false
            } else {
                if (needResyncFromExternal) {
                    cursorPos44k = externalPosMs.toDouble() * MdxDsp.SAMPLE_RATE / 1000.0
                    needResyncFromExternal = false
                }
                val frameCount = remaining / bytesPerFrame
                val step44k = MdxDsp.SAMPLE_RATE.toDouble() / inSampleRate
                val endCursor = cursorPos44k + frameCount * step44k

                if (cursorPos44k < 0.0 || endCursor >= totalSamples - 1) {
                    // 超过缓存 WAV 时长范围时安全回退原音
                    false
                } else {
                    inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                    outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                    var cur = cursorPos44k
                    for (f in 0 until frameCount) {
                        val idx0 = cur.toLong()
                        val frac = (cur - idx0).toFloat()
                        val s0 = readStereoSampleLocked(r, idx0)
                        val s1 = readStereoSampleLocked(r, idx0 + 1)
                        val outL = (s0.first + (s1.first - s0.first) * frac).roundToInt().coerceIn(-32768, 32767)
                        val outR = (s0.second + (s1.second - s0.second) * frac).roundToInt().coerceIn(-32768, 32767)

                        if (inChannels == 1) {
                            inputBuffer.short // consume
                            val mono = ((outL + outR) / 2).toShort()
                            outputBuffer.putShort(mono)
                        } else {
                            inputBuffer.short // consume L
                            inputBuffer.short // consume R
                            outputBuffer.putShort(outL.toShort())
                            outputBuffer.putShort(outR.toShort())
                            for (c in 2 until inChannels) {
                                outputBuffer.putShort(inputBuffer.short)
                            }
                        }
                        cur += step44k
                    }
                    cursorPos44k = cur
                    while (inputBuffer.hasRemaining()) {
                        outputBuffer.put(inputBuffer.get())
                    }
                    true
                }
            }
        }

        if (!replaced) {
            outputBuffer.put(inputBuffer)
        }
        outputBuffer.flip()
    }

    private fun readStereoSampleLocked(r: RandomAccessFile, sampleIdx: Long): Pair<Int, Int> {
        if (sampleIdx < 0L || sampleIdx >= wavTotalSamples) return 0 to 0
        if (cacheStartSample < 0L ||
            sampleIdx < cacheStartSample ||
            sampleIdx >= cacheStartSample + cacheValidSamples
        ) {
            val maxSamplesInCache = cacheBytes.size / 4
            val toReadSamples = minOf(maxSamplesInCache.toLong(), wavTotalSamples - sampleIdx).toInt()
            if (toReadSamples <= 0) return 0 to 0
            r.seek(44L + sampleIdx * 4L)
            val bytesNeeded = toReadSamples * 4
            var readTotal = 0
            while (readTotal < bytesNeeded) {
                val n = r.read(cacheBytes, readTotal, bytesNeeded - readTotal)
                if (n <= 0) break
                readTotal += n
            }
            cacheStartSample = sampleIdx
            cacheValidSamples = readTotal / 4
        }
        val local = (sampleIdx - cacheStartSample).toInt()
        if (local < 0 || local >= cacheValidSamples) return 0 to 0
        val base = local * 4
        val l = (cacheBytes[base].toInt() and 0xFF) or (cacheBytes[base + 1].toInt() shl 8)
        val rVal = (cacheBytes[base + 2].toInt() and 0xFF) or (cacheBytes[base + 3].toInt() shl 8)
        return l to rVal
    }

    private fun closeRafLocked() {
        runCatching { raf?.close() }
        raf = null
        wavTotalSamples = 0L
        cacheStartSample = -1L
        cacheValidSamples = 0
    }
}
