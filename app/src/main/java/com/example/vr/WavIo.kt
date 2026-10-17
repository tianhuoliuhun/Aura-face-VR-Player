package com.example.vr

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * v2.4.68：极简 **WAV（PCM 16-bit）读写**工具，只服务于「源分离的自验证」。
 *
 * 为什么自己写：只需要 mono/stereo + 16-bit，用 `MediaExtractor` 太重、又没有现成工具，
 * 而 WAV 头解析就几十行。**只支持 PCM16**（不含 float/24bit/压缩 WAV），够用即可。
 *
 * 说明：输出固定写 **44.1kHz / 立体声 / 16-bit**，因为 MDX 管线就是 44.1k 立体声。
 */
object WavIo {

    private const val TAG = "WavIo"

    /** 解出的 PCM（归一化到 [-1,1]）；单声道时 right 与 left 相同 */
    class Pcm(val left: FloatArray, val right: FloatArray, val sampleRate: Int, val channels: Int)

    /**
     * 读 16-bit PCM WAV。失败（非 WAV / 非 16bit / 采样率不支持）返回 null。
     */
    fun readPcm16(file: File): Pcm? {
        if (!file.isFile) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(minOf(4096L, raf.length()).toInt())
                raf.readFully(head)
                if (head.size < 12 ||
                    String(head, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                    String(head, 8, 4, Charsets.US_ASCII) != "WAVE"
                ) {
                    Log.w(TAG, "不是 WAV: ${file.name}")
                    return null
                }
                var i = 12
                var channels = 0
                var sampleRate = 0
                var bits = 0
                var dataOffset = -1L
                var dataLen = 0L
                while (i + 8 <= head.size) {
                    val cid = String(head, i, 4, Charsets.US_ASCII)
                    val sz = ByteBuffer.wrap(head, i + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                    if (cid == "fmt ") {
                        val bb = ByteBuffer.wrap(head, i + 8, 16).order(ByteOrder.LITTLE_ENDIAN)
                        val fmtTag = bb.short.toInt()
                        channels = bb.short.toInt()
                        sampleRate = bb.int
                        bb.int // byteRate
                        bb.short // blockAlign
                        bits = bb.short.toInt()
                        if (fmtTag != 1) {
                            Log.w(TAG, "非 PCM（fmtTag=$fmtTag）")
                            return null
                        }
                    } else if (cid == "data") {
                        dataOffset = (i + 8).toLong()
                        dataLen = sz
                        break
                    }
                    i += 8 + sz.toInt() + (sz.toInt() and 1)
                }
                if (dataOffset < 0 || channels !in 1..2 || bits != 16 || sampleRate <= 0) {
                    Log.w(TAG, "不支持的 WAV：ch=$channels bits=$bits sr=$sampleRate")
                    return null
                }
                val frameBytes = 2 * channels
                val frames = (minOf(dataLen, raf.length() - dataOffset) / frameBytes).toInt()
                if (frames <= 0) return null

                raf.seek(dataOffset)
                val raw = ByteArray(frames * frameBytes)
                raf.readFully(raw)
                val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                val l = FloatArray(frames)
                val r = FloatArray(frames)
                for (k in 0 until frames) {
                    val a = bb.short / 32768f
                    if (channels == 2) {
                        l[k] = a
                        r[k] = bb.short / 32768f
                    } else {
                        l[k] = a
                        r[k] = a
                    }
                }
                Pcm(l, r, sampleRate, channels)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "读 WAV 失败: ${t.message}", t)
            null
        }
    }

    /** 写 16-bit 立体声 WAV（44.1k 由调用方保证语义一致）。 */
    fun writeStereo16(file: File, left: FloatArray, right: FloatArray, sampleRate: Int): Boolean {
        return try {
            val n = minOf(left.size, right.size)
            val dataLen = n * 4
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                header.put("RIFF".toByteArray(Charsets.US_ASCII))
                header.putInt(36 + dataLen)
                header.put("WAVE".toByteArray(Charsets.US_ASCII))
                header.put("fmt ".toByteArray(Charsets.US_ASCII))
                header.putInt(16)                    // PCM chunk size
                header.putShort(1)                   // PCM
                header.putShort(2)                   // stereo
                header.putInt(sampleRate)
                header.putInt(sampleRate * 4)        // byte rate
                header.putShort(4)                   // block align
                header.putShort(16)                  // bits
                header.put("data".toByteArray(Charsets.US_ASCII))
                header.putInt(dataLen)
                raf.write(header.array())

                val chunk = ByteArray(minOf(dataLen, 1 shl 16) / 4 * 4)
                val bb = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
                var idx = 0
                while (idx < n) {
                    bb.clear()
                    var wrote = 0
                    while (idx < n && bb.remaining() >= 4) {
                        bb.putShort((left[idx].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                        bb.putShort((right[idx].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                        idx++
                        wrote += 4
                    }
                    raf.write(chunk, 0, wrote)
                }
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "写 WAV 失败: ${t.message}", t)
            false
        }
    }

    /** RMS（0..1），用于快速比较各轨能量 */
    fun rms(a: FloatArray): Float {
        if (a.isEmpty()) return 0f
        var s = 0.0
        for (v in a) s += v.toDouble() * v
        return kotlin.math.sqrt(s / a.size).toFloat()
    }
}
