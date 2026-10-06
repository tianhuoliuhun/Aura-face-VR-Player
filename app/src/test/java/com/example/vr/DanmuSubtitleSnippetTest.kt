package com.example.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DanmuSubtitleSnippet 单元测试（v2.4.1）
 *
 * 这段逻辑负责「按当前播放位置裁一段台词给模型」，最容易出错的正是边界，
 * 所以这里把**窗口边界**作为重点：
 * - 恰好跨越窗口起点的长句必须被保留（否则模型看到的上下文会断层）
 * - 恰好落在窗口外的必须被排除（尤其「后视」窗口，过大会让模型**剧透**）
 * - 无序输入也必须给出正确结果（本函数不假设调用方排好序）
 *
 * 纯 JVM 测试，不需要 Android 运行时。
 */
class DanmuSubtitleSnippetTest {

    private fun cue(startMs: Long, endMs: Long, text: String) =
        SubtitleCue(id = 0, startTimeMs = startMs, endTimeMs = endMs, text = text)

    // ------------------------------------------------------------ 基本窗口

    @Test
    fun `窗口内的台词会被保留、窗口外会被排除`() {
        // 基准：position = 100_000，窗口 = [85_000, 105_000]
        val cues = listOf(
            cue(10_000, 12_000, "太早的台词"),
            cue(90_000, 92_000, "窗口内的台词"),
            cue(200_000, 202_000, "太晚的台词")
        )
        val out = DanmuSubtitleSnippet.extract(cues, 100_000L)
        assertEquals("窗口内的台词", out)
    }

    @Test
    fun `跨越窗口起点的长句必须被保留`() {
        // cue 从 80_000 一直到 100_000，横跨整个窗口 —— 只判 start 会漏掉它
        val cues = listOf(cue(80_000, 100_000, "很长的一句话"))
        val out = DanmuSubtitleSnippet.extract(cues, 100_000L)
        assertEquals("很长的一句话", out)
    }

    @Test
    fun `后视窗口用于感知未说完的话`() {
        // position = 100_000，后视 5_000 → to = 105_000
        val cues = listOf(cue(104_000, 106_000, "话还没说完"))
        assertEquals("话还没说完", DanmuSubtitleSnippet.extract(cues, 100_000L))
    }

    @Test
    fun `超出后视窗口的台词不会剧透`() {
        // 起点 106_000 > to=105_000 → 必须排除，否则模型会把没播的剧情写进弹幕
        val cues = listOf(cue(106_000, 108_000, "还没播的剧情"))
        assertEquals("", DanmuSubtitleSnippet.extract(cues, 100_000L))
    }

    @Test
    fun `恰好落在窗口边界的台词会被保留`() {
        // 闭合区间 [85_000, 105_000]：end == from 与 start == to 都算命中
        val head = listOf(cue(83_000, 85_000, "右边界贴着窗口头"))
        val tail = listOf(cue(105_000, 107_000, "左边界贴着窗口尾"))
        assertEquals("右边界贴着窗口头", DanmuSubtitleSnippet.extract(head, 100_000L))
        assertEquals("左边界贴着窗口尾", DanmuSubtitleSnippet.extract(tail, 100_000L))
    }

    // ------------------------------------------------------------ 排序无关性

    @Test
    fun `乱序输入也能取全窗口内的台词`() {
        val cues = listOf(
            cue(95_000, 97_000, "第三句"),
            cue(88_000, 90_000, "第一句"),
            cue(200_000, 201_000, "窗口外"),
            cue(92_000, 94_000, "第二句")
        )
        val out = DanmuSubtitleSnippet.extract(cues, 100_000L)
        // 结果按时间升序拼接（sortedBy 后遍历的顺序）
        assertEquals("第一句\n第二句\n第三句", out)
    }

    // ------------------------------------------------------------ 截断

    @Test
    fun `超长时保留更靠近当前位置的台词`() {
        // 三条各 100 字符，maxChars 只够两条 + 冒号 → 应保留**靠后**的两条
        val a = "A".repeat(100)
        val b = "B".repeat(100)
        val c = "C".repeat(100)
        val cues = listOf(
            cue(86_000, 87_000, a),
            cue(88_000, 89_000, b),
            cue(90_000, 91_000, c)
        )
        val out = DanmuSubtitleSnippet.extract(cues, 100_000L, maxChars = 201)
        assertTrue("应丢弃最早的 A", !out.contains("A"))
        assertTrue("应保留 B", out.contains("B"))
        assertTrue("应保留 C", out.contains("C"))
    }

    @Test
    fun `单条就超长时退化为截断而不是返回空`() {
        val long = "长".repeat(1000)
        val cues = listOf(cue(95_000, 97_000, long))
        val out = DanmuSubtitleSnippet.extract(cues, 100_000L, maxChars = 50)
        assertEquals(50, out.length)
    }

    // ------------------------------------------------------------ 空输入

    @Test
    fun `没有台词时返回空串`() {
        assertEquals("", DanmuSubtitleSnippet.extract(emptyList(), 100_000L))
    }

    @Test
    fun `全是空白文本时返回空串`() {
        val cues = listOf(cue(95_000, 97_000, "   "), cue(98_000, 99_000, "\n\t"))
        assertEquals("", DanmuSubtitleSnippet.extract(cues, 100_000L))
    }

    @Test
    fun `maxChars 为 0 时直接返回空串`() {
        val cues = listOf(cue(95_000, 97_000, "内容"))
        assertEquals("", DanmuSubtitleSnippet.extract(cues, 100_000L, maxChars = 0))
    }

    @Test
    fun `多行台词会被压成单行再拼接`() {
        // cue 内部换行会破坏「一行一条」的弹幕格式，必须先压平
        val cues = listOf(cue(95_000, 97_000, "第一行\n第二行"))
        val out = DanmuSubtitleSnippet.extract(cues, 100_000L)
        assertEquals("第一行 第二行", out)
    }
}
