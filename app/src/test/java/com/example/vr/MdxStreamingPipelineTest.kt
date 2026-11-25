package com.example.vr

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MdxStreamingPipelineTest {

    @Test
    fun applyWindow_matchesFullInlineApplyAcrossWindowBoundaries() {
        val totalSamples = MdxDsp.SAMPLE_RATE * 20 // 20 秒立体声，跨越多块窗口边界
        val mixL = FloatArray(totalSamples) { 0.8f }
        val mixR = FloatArray(totalSamples) { 0.6f }
        val vocalL = FloatArray(totalSamples) { 0.5f }
        val vocalR = FloatArray(totalSamples) { 0.3f }

        // 构造跨越 14 秒边界（即 ~617400 样本位置）的男声片段，测试 crossfade 连续性
        val segments = listOf(
            AudioGenderAnalyzer.SpeakerSegment(
                startMs = 2000L,
                endMs = 17500L,
                speakerId = 1,
                f0Hz = 120f,
                gender = AudioGenderAnalyzer.Gender.MALE,
            )
        )
        val selected = setOf(1)

        val inlineOut = MdxReprocessor.apply(
            mixL = mixL.copyOf(),
            mixR = mixR.copyOf(),
            vocalL = vocalL,
            vocalR = vocalR,
            segments = segments,
            selected = selected,
        )

        // 按流式分窗方式逐块处理（每块 100000 样本）
        val streamL = mixL.copyOf()
        val streamR = mixR.copyOf()
        val chunkSize = 100_000
        var offset = 0
        while (offset < totalSamples) {
            val len = minOf(chunkSize, totalSamples - offset)
            val subMixL = streamL.copyOfRange(offset, offset + len)
            val subMixR = streamR.copyOfRange(offset, offset + len)
            val subVocL = vocalL.copyOfRange(offset, offset + len)
            val subVocR = vocalR.copyOfRange(offset, offset + len)
            MdxReprocessor.applyWindow(
                mixL = subMixL,
                mixR = subMixR,
                vocalL = subVocL,
                vocalR = subVocR,
                validFrom = 0,
                validTo = len,
                windowGlobalStartSample = offset.toLong(),
                segments = segments,
                selected = selected,
            )
            System.arraycopy(subMixL, 0, streamL, offset, len)
            System.arraycopy(subMixR, 0, streamR, offset, len)
            offset += len
        }

        assertArrayEquals(inlineOut.first, streamL, 1e-5f)
        assertArrayEquals(inlineOut.second, streamR, 1e-5f)
    }

    @Test
    fun cacheKey_isDeterministicAndDistinct() {
        val k1 = MdxStreamingPipeline.cacheKey("content://media/external/video/100")
        val k2 = MdxStreamingPipeline.cacheKey("content://media/external/video/100")
        val k3 = MdxStreamingPipeline.cacheKey("content://media/external/video/101")
        assertEquals(16, k1.length)
        assertEquals(k1, k2)
        assertNotEquals(k1, k3)
    }

    @Test
    fun patchWavHeader_writesValidPcmHeader() {
        val tmp = File.createTempFile("wav_header_test", ".wav")
        try {
            val dataBytes = 44100L * 4L * 5L // 5s stereo
            tmp.outputStream().use { it.write(ByteArray(44 + dataBytes.toInt())) }
            MdxStreamingPipeline.patchWavHeader(tmp, dataBytes, 44100, 2)
            val hdr = tmp.inputStream().use {
                val b = ByteArray(44)
                it.read(b)
                b
            }
            assertEquals("RIFF", String(hdr, 0, 4))
            assertEquals("WAVEfmt ", String(hdr, 8, 8))
            assertEquals("data", String(hdr, 36, 4))
            val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals((36L + dataBytes).toInt(), bb.getInt(4))
            assertEquals(44100, bb.getInt(24))
            assertEquals(dataBytes.toInt(), bb.getInt(40))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun genderFilterAudioProcessor_passesThroughWhenDisabled() {
        val proc = GenderFilterAudioProcessor()
        val fmt = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        proc.configure(fmt)
        proc.flush()
        proc.isFilterEnabled = false

        val srcBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val input = ByteBuffer.wrap(srcBytes)
        proc.queueInput(input)
        val out = proc.output
        val outBytes = ByteArray(out.remaining())
        out.get(outBytes)
        assertArrayEquals(srcBytes, outBytes)
    }
}
