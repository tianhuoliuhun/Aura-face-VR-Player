package com.example.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.8.3：HLS 字幕「合并多少片」的边界。
 *
 * 回归目标是这条 bug：旧实现固定取前 **240 片**，而片长在 2~10 秒之间浮动 ——
 * 2 秒/片的清单只有 8 分钟字幕，10 秒/片的有 40 分钟，覆盖时长完全不可控。
 * 用户导出的 SRT 在后半段直接断掉，且没有任何提示。
 */
class WebSubtitleSnifferCoverageTest {

    private fun segs(count: Int, durationMs: Long) =
        (0 until count).map { HlsPlaylist.Segment("seg$it.vtt", durationMs) }

    /** 旧 bug 场景：2 秒/片的 1 小时片子 = 1800 片。旧实现只合 240 片 = 8 分钟。 */
    @Test
    fun shortSegmentsNowCoverWholeFilm() {
        val c = WebSubtitleSniffer.planCoverage(segs(1800, 2_000L), isVod = true)
        assertEquals(1800, c.take)
        assertEquals(1800 * 2_000L, c.coveredMs)
        assertFalse("1 小时的片子不该被截断", c.truncated)
    }

    /** 常见 6 秒/片、2 小时片子：必须全合（旧实现只到 24 分钟）。 */
    @Test
    fun typicalTwoHourFilmIsFullyCovered() {
        val c = WebSubtitleSniffer.planCoverage(segs(1200, 6_000L), isVod = true)
        assertEquals(1200, c.take)
        assertEquals(7_200_000L, c.coveredMs)
        assertFalse(c.truncated)
    }

    /** 超过 6 小时预算：截断，且 coveredMs 要停在预算内而不是超出去。 */
    @Test
    fun overBudgetIsTruncatedAtSixHours() {
        val c = WebSubtitleSniffer.planCoverage(segs(4000, 10_000L), isVod = true)
        assertTrue(c.truncated)
        assertEquals(2160, c.take)              // 21600s / 10s
        assertEquals(6 * 3600 * 1000L, c.coveredMs)
    }

    /** 片数硬上限先于时长上限触发（极小分片 + 超长清单）。 */
    @Test
    fun segmentCapTriggersForTinySegments() {
        val c = WebSubtitleSniffer.planCoverage(segs(5000, 2_000L), isVod = true)
        assertTrue(c.truncated)
        assertEquals(4000, c.take)              // 8000s，未到 6 小时，卡在片数上限
        assertEquals(8_000_000L, c.coveredMs)
    }

    /** 直播（非 VOD）只取一片：没有完整时间轴。 */
    @Test
    fun liveTakesSingleSegment() {
        val c = WebSubtitleSniffer.planCoverage(segs(500, 6_000L), isVod = false)
        assertEquals(1, c.take)
        assertEquals(6_000L, c.coveredMs)
        assertFalse("直播不是「被截断」，是本来就没有全片", c.truncated)
    }

    @Test
    fun emptyPlaylistIsSafe() {
        val c = WebSubtitleSniffer.planCoverage(emptyList(), isVod = true)
        assertEquals(0, c.take)
        assertEquals(0L, c.coveredMs)
        assertFalse(c.truncated)
    }

    /** 负数/坏时长不能把预算"吃回来"（否则畸形清单能合出负覆盖）。 */
    @Test
    fun negativeDurationsDoNotConsumeBudget() {
        val bad = listOf(
            HlsPlaylist.Segment("a.vtt", -5_000L),
            HlsPlaylist.Segment("b.vtt", 6_000L),
        )
        val c = WebSubtitleSniffer.planCoverage(bad, isVod = true)
        assertEquals(2, c.take)
        assertEquals(6_000L, c.coveredMs)
    }
}
