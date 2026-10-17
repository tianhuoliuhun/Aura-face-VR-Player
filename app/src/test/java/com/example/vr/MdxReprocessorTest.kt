package com.example.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4.68（档 2）：**按说话人重组**的单测。
 *
 * 用「mix = 1.0、vocal = 0.3 ⇒ 伴奏 = 0.7」这种可手算的构造，
 * 精确断言「选中区间被换成 0.7、未选中区间仍是 1.0、边界有过渡」。
 * 这类逻辑如果错，听感上是「去人声去早了/晚了」或「边界咔哒」，极难定位。
 */
class MdxReprocessorTest {

    private val sr = MdxDsp.SAMPLE_RATE
    private fun ms(v: Int) = v * sr / 1000

    private fun mix(v: Float = 1f) = FloatArray(sr) { v }
    private fun voc(v: Float = 0.3f) = FloatArray(sr) { v }

    private fun seg(startMs: Long, endMs: Long, id: Int) =
        AudioGenderAnalyzer.SpeakerSegment(
            startMs = startMs,
            endMs = endMs,
            speakerId = id,
            f0Hz = 120f,
            gender = AudioGenderAnalyzer.Gender.MALE,
        )

    @Test
    fun `selected segment is replaced by accompaniment`() {
        val (l, r) = MdxReprocessor.apply(
            mix(), mix(), voc(), voc(),
            listOf(seg(200, 800, 0)),
            setOf(0),
            crossfadeMs = 60,
        )
        // 区间中部：彻底换成伴奏 0.7
        assertEquals("区间中心应为伴奏", 0.7f, l[ms(500)], 0.01f)
        assertEquals(0.7f, r[ms(500)], 0.01f)
        // 区间外：保持原音 1.0
        assertEquals("区间前应保持原音", 1.0f, l[ms(100)], 0.001f)
        assertEquals("区间后应保持原音", 1.0f, l[ms(900)], 0.001f)
    }

    @Test
    fun `unselected speaker leaves audio untouched`() {
        val (l, r) = MdxReprocessor.apply(
            mix(), mix(), voc(), voc(),
            listOf(seg(200, 800, 0)),
            setOf(1),      // 只处理 1 号，0 号不动
            crossfadeMs = 60,
        )
        for (i in intArrayOf(ms(100), ms(500), ms(900))) {
            assertEquals(1.0f, l[i], 0.001f)
            assertEquals(1.0f, r[i], 0.001f)
        }
    }

    @Test
    fun `boundary is crossfaded rather than hard cut`() {
        val (l, _) = MdxReprocessor.apply(
            mix(), mix(), voc(), voc(),
            listOf(seg(200, 800, 0)),
            setOf(0),
            crossfadeMs = 60,
        )
        // 段起点本身权重≈0 → 仍是原音；之后线性过渡到伴奏
        val atStart = l[ms(200)]
        val midFade = l[ms(230)]     // 30ms 处 ≈ 半程
        val atMid = l[ms(500)]
        assertTrue("起点应接近原音（$atStart）", atStart > 0.95f)
        assertTrue("过渡区应介于两者之间（$midFade）", midFade < atStart && midFade > atMid)
        assertEquals(0.7f, atMid, 0.01f)
    }

    @Test
    fun `overlapping segments do not double-apply`() {
        // 两段重叠且都被选中 → 输出仍是伴奏水平，不会变成 0.7*2 之类
        val (l, _) = MdxReprocessor.apply(
            mix(), mix(), voc(), voc(),
            listOf(seg(200, 600, 0), seg(400, 800, 0)),
            setOf(0),
            crossfadeMs = 60,
        )
        assertEquals(0.7f, l[ms(500)], 0.02f)
        assertTrue("不应超出伴奏幅度", l[ms(500)] <= 0.75f)
    }

    @Test
    fun `empty selection returns copy of mix`() {
        val (l, _) = MdxReprocessor.apply(
            mix(), mix(), voc(), voc(),
            listOf(seg(200, 800, 0)),
            emptySet(),
        )
        assertEquals(1.0f, l[ms(500)], 0.001f)
    }

    @Test
    fun `speaker rows are sorted by speech duration desc`() {
        val result = AudioGenderAnalyzer.Result(
            sampleRate = 16000,
            totalMs = 60_000,
            speakers = listOf(
                AudioGenderAnalyzer.SpeakerInfo(0, AudioGenderAnalyzer.Gender.MALE, 120f, 5_000, 3),
                AudioGenderAnalyzer.SpeakerInfo(1, AudioGenderAnalyzer.Gender.FEMALE, 210f, 30_000, 9),
            ),
            segments = emptyList(),
            maleMs = 5_000,
            femaleMs = 30_000,
            unknownMs = 0,
        )
        val rows = MdxReprocessor.speakerRows(result)
        assertEquals("主要说话人应排前面", 1, rows[0].id)
        assertEquals(2, rows.size)
    }

    @Test
    fun `male speakers are selected by default`() {
        val male = MdxReprocessor.Row(0, AudioGenderAnalyzer.Gender.MALE, 120f, 1000, 1)
        val female = MdxReprocessor.Row(1, AudioGenderAnalyzer.Gender.FEMALE, 210f, 1000, 1)
        val unknown = MdxReprocessor.Row(2, AudioGenderAnalyzer.Gender.UNKNOWN, 0f, 1000, 1)
        assertTrue("男声默认勾选", male.defaultSelected())
        assertTrue("女声默认不勾", !female.defaultSelected())
        assertTrue("不确定默认不勾", !unknown.defaultSelected())
    }
}
