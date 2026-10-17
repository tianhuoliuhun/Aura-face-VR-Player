package com.example.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * v2.4.64：**音频性别分析的算法单测**（纯 JVM，不需要设备也不需要模型）。
 *
 * 覆盖两处「最易出错、且无法靠肉眼验证」的核心算法：
 * 1. [AudioGenderAnalyzer.estimateF0Median] —— 自相关法估基频（用合成正弦波做判据）；
 * 2. [AudioPcmExtractor.resampleLinear] —— 线性重采样（长度 + 频率保持）。
 *
 * 用合成信号的好处：**已知真值**（120Hz / 220Hz），算法偏了会立刻暴露 ——
 * 这比拿真实语音「听起来差不多」可靠得多。
 */
class AudioGenderCoreTest {

    private val sr = 16000

    private fun sine(freqHz: Float, seconds: Float, amplitude: Float = 0.5f, sampleRate: Int = sr): FloatArray {
        val n = (seconds * sampleRate).toInt()
        return FloatArray(n) { (amplitude * sin(2.0 * PI * freqHz * it / sampleRate)).toFloat() }
    }

    // ------------------------------------------------------------ F0 估计

    @Test
    fun `120Hz sine is detected and classified as MALE`() {
        val f0 = AudioGenderAnalyzer.estimateF0Median(sine(120f, 1.0f), sr)
        assertTrue("120Hz 应被估出（容差 ±12），实际 $f0", abs(f0 - 120f) < 12f)
        assertEquals(AudioGenderAnalyzer.Gender.MALE, AudioGenderAnalyzer.genderOf(f0))
    }

    @Test
    fun `220Hz sine is detected and classified as FEMALE`() {
        val f0 = AudioGenderAnalyzer.estimateF0Median(sine(220f, 1.0f), sr)
        assertTrue("220Hz 应被估出（容差 ±20），实际 $f0", abs(f0 - 220f) < 20f)
        assertEquals(AudioGenderAnalyzer.Gender.FEMALE, AudioGenderAnalyzer.genderOf(f0))
    }

    @Test
    fun `silence yields zero F0 and UNKNOWN`() {
        val f0 = AudioGenderAnalyzer.estimateF0Median(FloatArray(sr) { 0f }, sr)
        assertEquals(0f, f0, 0.0001f)
        assertEquals(AudioGenderAnalyzer.Gender.UNKNOWN, AudioGenderAnalyzer.genderOf(f0))
    }

    @Test
    fun `very short input is rejected`() {
        // 少于两帧（2×40ms）视为不可估
        assertEquals(0f, AudioGenderAnalyzer.estimateF0Median(sine(150f, 0.03f), sr), 0.0001f)
    }

    @Test
    fun `gender thresholds behave as documented`() {
        assertEquals(AudioGenderAnalyzer.Gender.MALE, AudioGenderAnalyzer.genderOf(100f))
        assertEquals(AudioGenderAnalyzer.Gender.MALE, AudioGenderAnalyzer.genderOf(158f))
        // 160~175 之间是刻意留下的「不确定」带（童声/变声/噪声）
        assertEquals(AudioGenderAnalyzer.Gender.UNKNOWN, AudioGenderAnalyzer.genderOf(168f))
        assertEquals(AudioGenderAnalyzer.Gender.FEMALE, AudioGenderAnalyzer.genderOf(200f))
        assertEquals(AudioGenderAnalyzer.Gender.UNKNOWN, AudioGenderAnalyzer.genderOf(0f))
    }

    @Test
    fun `noise with low RMS does not produce a pitch`() {
        // 极低幅度的白噪声：能量低于 RMS 门限 → 不应产生 F0
        val rnd = java.util.Random(42)
        val weak = FloatArray(sr) { (rnd.nextFloat() - 0.5f) * 0.002f }
        assertEquals(0f, AudioGenderAnalyzer.estimateF0Median(weak, sr), 0.0001f)
    }

    // ------------------------------------------------------------ 重采样

    @Test
    fun `resample 48k to 16k yields one third of samples`() {
        val input = sine(440f, 1.0f, sampleRate = 48000)
        val out = AudioPcmExtractor.resampleLinear(input, 48000, 16000)
        assertTrue("期望 ~16000 采样，实际 ${out.size}", abs(out.size - 16000) <= 1)
    }

    @Test
    fun `resample keeps frequency (48k to 16k sine still ~440Hz)`() {
        val input = sine(440f, 1.0f, sampleRate = 48000)
        val out = AudioPcmExtractor.resampleLinear(input, 48000, 16000)
        // 用同样的自相关估计验证频率没被改变（440Hz 超出性别范围，只验数值）
        var bestLag = 0
        var best = 0f
        val minLag = 16000 / 600
        val maxLag = 16000 / 300
        for (lag in minLag..maxLag) {
            var sum = 0f
            for (i in 0 until out.size - lag) sum += out[i] * out[i + lag]
            if (sum > best) { best = sum; bestLag = lag }
        }
        val f0 = 16000f / bestLag
        assertTrue("重采样后仍应约 440Hz，实际 $f0", abs(f0 - 440f) < 30f)
    }

    @Test
    fun `resample with equal rates returns identity`() {
        val input = sine(200f, 0.2f)
        val out = AudioPcmExtractor.resampleLinear(input, sr, sr)
        assertTrue("同采样率应原样返回", input === out)
    }

    // ------------------------------------------------------------ 切片

    @Test
    fun `slicePcm clamps out-of-range seconds`() {
        val pcm = FloatArray(sr * 2) // 2 秒
        val a = AudioGenderAnalyzer.slicePcm(pcm, sr, 0.5f, 1.0f)
        assertEquals(sr / 2, a.size)
        // 越界结束 → 裁剪到末尾；起点越界 → 空
        assertEquals(sr, AudioGenderAnalyzer.slicePcm(pcm, sr, 1.0f, 99f).size)
        assertEquals(0, AudioGenderAnalyzer.slicePcm(pcm, sr, 5f, 6f).size)
        assertEquals(0, AudioGenderAnalyzer.slicePcm(pcm, sr, 1.5f, 1.0f).size)
    }

    // ------------------------------------------------------------ 模型登记

    @Test
    fun `model urls are pinned and unique`() {
        val all = AudioGenderModels.ALL
        // v2.4.67 档1 起多了「性别分类」（ECAPA-TDNN q8）→ 共 4 个
        assertEquals("登记了 4 个模型（VAD / 分割 / 嵌入 / 性别分类）", 4, all.size)
        assertEquals("URL 不应重复", all.size, all.map { it.url }.toSet().size)
        // 来源必须是**可信、可复现**的地址：sherpa 官方 release 或 Hugging Face
        // （v2.4.67 新增的性别分类模型托管在 HF，所以不能只断言 sherpa release）
        assertTrue(
            "模型 URL 应全部来自 sherpa 官方 release 或 Hugging Face，实际=${all.map { it.url }}",
            all.all {
                it.url.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/") ||
                    it.url.startsWith("https://huggingface.co/")
            },
        )
        // ⚠️ 嵌入模型所在 tag 是上游的**拼写笔误**（recongition）—— 写错就 404，这里锁住
        assertTrue("嵌入模型 URL 必须使用上游的 recongition 拼写",
            all.any { it.url.contains("speaker-recongition-models") })
        // 分割模型是 tar.bz2（需要解压路径）
        assertTrue("分割模型应标记为压缩包",
            all.single { it.id == "segmentation" }.archive)
        assertTrue("VAD 与嵌入不应走解压路径",
            all.filter { it.id != "segmentation" }.none { it.archive })
    }

    // ------------------------------------------------------------ 内存预检（v2.4.65 防 OOM）

    @Test
    fun `safeMaxSeconds caps an enormous request within heap budget`() {
        // v2.4.65：v2.4.64 的「抽取音轨失败」实为 OOM（ArrayList<Float> 装箱）。
        // 现在上限必须被**可用堆的一半**封顶（结果按 16kHz × 4B 计）。
        val huge = Int.MAX_VALUE / 4
        val sec = AudioPcmExtractor.safeMaxSeconds(huge)
        val bytes = sec.toLong() * AudioPcmExtractor.TARGET_SAMPLE_RATE * 4L
        val budget = Runtime.getRuntime().maxMemory() / 2
        assertTrue(
            "上限 $sec 秒（$bytes 字节）应 ≤ 堆预算 $budget 字节",
            bytes <= budget + AudioPcmExtractor.TARGET_SAMPLE_RATE * 4L,
        )
        assertTrue("至少应给出 60 秒", sec >= 60)
    }

    @Test
    fun `safeMaxSeconds keeps a modest request unchanged`() {
        // 10 分钟远小于任何设备的堆预算 → 不应被下调
        assertEquals(600, AudioPcmExtractor.safeMaxSeconds(600))
    }

    @Test
    fun `default max seconds is 15 minutes`() {
        assertEquals(15 * 60, AudioPcmExtractor.DEFAULT_MAX_SECONDS)
    }

    // ------------------------------------------------------------ 档1：ML 性别分类的概率后处理

    @Test
    fun `softmax2 returns a normalized probability pair`() {
        val (m, f) = GenderClassifier.softmax2(2f, 0f)
        assertTrue("男声 logit 更大 → 男声概率应更高（m=$m f=$f）", m > f)
        assertTrue("两类概率之和应为 1（误差 1e-4）", abs(m + f - 1f) < 1e-4f)
    }

    @Test
    fun `softmax2 of equal logits is a coin flip`() {
        val (m, f) = GenderClassifier.softmax2(0f, 0f)
        assertEquals(0.5f, m, 1e-6f)
        assertEquals(0.5f, f, 1e-6f)
    }

    @Test
    fun `softmax2 is numerically stable for extreme logits`() {
        // 大 logit 直接 exp 会溢出 → 必须做减最大值的稳定化
        val (m, f) = GenderClassifier.softmax2(1000f, -1000f)
        assertTrue("不应出现 NaN/Inf（m=$m f=$f）", m.isFinite() && f.isFinite())
        assertTrue("应为接近 (1,0)", m > 0.999f && f < 0.001f)
    }

    @Test
    fun `genderOf maps probability to gender with a margin`() {
        assertEquals(AudioGenderAnalyzer.Gender.MALE, GenderClassifier.genderOf(0.9f to 0.1f))
        assertEquals(AudioGenderAnalyzer.Gender.FEMALE, GenderClassifier.genderOf(0.1f to 0.9f))
        // 概率接近 → 刻意留 UNKNOWN（不硬猜）
        assertEquals(AudioGenderAnalyzer.Gender.UNKNOWN, GenderClassifier.genderOf(0.52f to 0.48f))
        // 模型不可用（null）→ UNKNOWN，由调用方回退 F0
        assertEquals(AudioGenderAnalyzer.Gender.UNKNOWN, GenderClassifier.genderOf(null))
    }

    @Test
    fun `gender model spec points at hugging face and is registered`() {
        val g = AudioGenderModels.GENDER
        assertTrue("性别模型应登记在 ALL 里（否则 UI 不会提示下载）",
            AudioGenderModels.ALL.any { it.id == "gender" })
        assertTrue("URL 应指向 Hugging Face", g.url.startsWith("https://huggingface.co/"))
        assertTrue("该模型不是压缩包（直接 .onnx）", !g.archive)
        assertTrue("minBytes 应能挡住半截下载", g.minBytes > 1_000_000L)
    }
}
