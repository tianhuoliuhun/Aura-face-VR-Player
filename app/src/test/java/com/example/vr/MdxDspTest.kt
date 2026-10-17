package com.example.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * v2.4.68（档 2）：**MDX DSP 原语的正确性单测**（纯 JVM，不需要设备/模型）。
 *
 * 为什么这一层必须有单测：STFT/ISTFT 错一点（窗函数、center 处理、WOLA 归一化、FFT 缩放），
 * 分离出来的音频就会**整体变调/变响/发闷**，而且很难从"听起来不对"反推到具体是哪个环节。
 * 用「合成双音 → STFT → ISTFT → 比较」这个已知真值的测试，可以把 DSP 的正确性与
 * 模型推理的正确性**彻底分开**验证。
 */
class MdxDspTest {

    private val sr = MdxDsp.SAMPLE_RATE

    @Test
    fun `constants match sherpa-onnx formulas`() {
        // 这些数值直接对应 sherpa 的 offline-source-separation-uvr-impl.h
        assertEquals(2048, MdxDsp.TRIM)              // n_fft/2
        assertEquals(261120, MdxDsp.CHUNK_SIZE)      // hop*(dim_t-1) = 1024*255
        assertEquals(257024, MdxDsp.GEN_SIZE)        // chunk - 2*trim
        assertEquals(265216, MdxDsp.ISTFT_FULL)      // (dim_t-1)*hop + n_fft
        assertEquals(4096, MdxDsp.N_FFT)
        assertEquals(1024, MdxDsp.HOP)
        assertEquals(2048, MdxDsp.DIM_F)
        assertEquals(256, MdxDsp.DIM_T)
    }

    @Test
    fun `fft forward then inverse roundtrips`() {
        val n = MdxDsp.N_FFT
        val fft = MdxDsp.Fft(n)
        val re = FloatArray(n) { i -> (0.5 * sin(2.0 * PI * 37 * i / n)).toFloat() }
        val im = FloatArray(n)
        val re0 = re.copyOf()
        fft.forward(re, im)
        fft.inverse(re, im)
        var maxErr = 0f
        for (i in re.indices) maxErr = max(maxErr, abs(re[i] - re0[i]))
        assertTrue("FFT 正逆变换应还原（最大误差 $maxErr）", maxErr < 1e-4f)
    }

    @Test
    fun `stft then istft reconstructs the waveform`() {
        // 双音（440Hz + 1kHz），幅度 0.4 / 0.2
        val x = FloatArray(MdxDsp.CHUNK_SIZE) { i ->
            (0.4 * sin(2.0 * PI * 440.0 * i / sr) + 0.2 * sin(2.0 * PI * 1000.0 * i / sr)).toFloat()
        }
        val re = FloatArray(MdxDsp.DIM_F * MdxDsp.DIM_T)
        val im = FloatArray(MdxDsp.DIM_F * MdxDsp.DIM_T)

        MdxDsp.stft(x, re, im)
        val y = MdxDsp.istft(re, im)

        // ISTFT 输出比 CHUNK_SIZE 长两端各 TRIM → 裁掉
        val yc = y.copyOfRange(MdxDsp.TRIM, MdxDsp.TRIM + MdxDsp.CHUNK_SIZE)

        // 跳过最两端（center 补齐与段边界的瞬态），比较中段
        var maxErr = 0f
        var energy = 0f
        for (i in 2000 until MdxDsp.CHUNK_SIZE - 2000) {
            maxErr = max(maxErr, abs(yc[i] - x[i]))
            energy += x[i] * x[i]
        }
        assertTrue(
            "STFT→ISTFT 应还原波形（最大误差 $maxErr，信号能量 $energy）",
            maxErr < 0.01f,
        )
    }

    @Test
    fun `istft amplitude is not scaled by overlap count`() {
        // 单音、已知幅度 → 还原后幅度应基本一致（验证 WOLA 的 sum(w^2) 归一化写对了）
        val amp = 0.5f
        val x = FloatArray(MdxDsp.CHUNK_SIZE) { i -> (amp * sin(2.0 * PI * 440.0 * i / sr)).toFloat() }
        val re = FloatArray(MdxDsp.DIM_F * MdxDsp.DIM_T)
        val im = FloatArray(MdxDsp.DIM_F * MdxDsp.DIM_T)
        MdxDsp.stft(x, re, im)
        val y = MdxDsp.istft(re, im)

        // 取中段峰值，应接近 0.5
        var peak = 0f
        for (i in 20000 until 40000) peak = max(peak, abs(y[MdxDsp.TRIM + i]))
        assertEquals("还原后的峰值应接近原始幅度 0.5", amp, peak, 0.02f)
    }

    @Test
    fun `computePad aligns to gen size as in sherpa`() {
        // sherpa 的公式：pad = gen_size - (num_samples % gen_size)
        // ⚠️ 余数为 1 时得到的是 **gen_size - 1**，不是 1 —— 这里最初写错过，靠单测纠正。
        assertEquals(MdxDsp.GEN_SIZE, MdxDsp.computePad(MdxDsp.GEN_SIZE))
        assertEquals(MdxDsp.GEN_SIZE - 1, MdxDsp.computePad(MdxDsp.GEN_SIZE + 1))
        assertEquals(MdxDsp.GEN_SIZE - 10, MdxDsp.computePad(10))
    }

    @Test
    fun `safeInlineSeconds scales with heap and clamps to bounds`() {
        val mb = 1024L * 1024L

        // 192MB 的堆（MuMu 实测值）：预算 25% ≈ 48MB，每样本 28B → 约 40s
        // ⚠️ 原本用 45% 预算算出 85s，实测会把整个模拟器拖死 → 已调低（见 MdxSeparator 注释）
        assertEquals(40, MdxSeparator.safeInlineSeconds(192L * mb))

        // 512MB 的堆：算出来超过上限 → 夹到 90（真机常见情形）
        assertEquals(MdxSeparator.MAX_INLINE_SECONDS, MdxSeparator.safeInlineSeconds(512L * mb))

        // 极小堆：夹到下限，避免"0 秒"这种无意义结果
        assertEquals(MdxSeparator.MIN_INLINE_SECONDS, MdxSeparator.safeInlineSeconds(32L * mb))

        // 真机/模拟器上都应落在合法区间（防止估算常数被改坏）
        val real = MdxSeparator.safeInlineSeconds()
        assertTrue(
            "safeInlineSeconds() 应落在 [${MdxSeparator.MIN_INLINE_SECONDS}, ${MdxSeparator.MAX_INLINE_SECONDS}]，实际 $real",
            real in MdxSeparator.MIN_INLINE_SECONDS..MdxSeparator.MAX_INLINE_SECONDS,
        )
    }
}
