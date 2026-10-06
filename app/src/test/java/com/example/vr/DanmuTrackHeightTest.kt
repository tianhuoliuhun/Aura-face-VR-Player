package com.example.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4.6：行距自适应（弹幕区高度 + 轨道高度）测试。
 *
 * ## 背景
 * 用户反馈「优化多条弹幕排布」，明确要**行距自适应**、且**以字号为基准**。
 *
 * v2.4.5 及之前：`轨道高度 = 弹幕区高度(固定30%) / 轨道数`，导致：
 * 1. **字号调大时行距不变** → 大字号下上下两行贴在一起（用户看到的就是这个）；
 * 2. **轨道数调多时行距被压小** → 8 轨改 20 轨，行距只剩 40%。
 *
 * v2.4.6 改为**区域随内容长**：
 * ```
 * 弹幕区高度 = min(轨道数 × 字号 × 1.9, 屏高 × 0.45)
 * 轨道高度   = min(字号 × 1.9, 弹幕区高度 / 轨道数)
 * ```
 *
 * ## ⚠️ 本测试抓到的两个真实问题（都值得记住）
 * 1. **公式 max/min 写反**：初版行距写成 `max(natural, fit)`，语义完全反了 ——
 *    区域一大就永远取均分值，字号从 20 调到 50 行距都不变，**行距自适应彻底失效**。
 * 2. **只改行距公式还不够**：即使公式正确，固定 30% 区域在**默认参数**下
 *    （1080×2400 / 密度 3x / 8 轨 / 18sp）算出均分 90px，而理想行距 102.6px **已超出** →
 *    被上限压回 90px，**默认配置下完全感受不到变化**。这才促成本版把区域也改为自适应。
 *    → 教训：**"参数变了但观感没变"要回到真实数值上验算**，不要只盯着公式对错。
 */
class DanmuTrackHeightTest {

    // 默认参数（与生产一致）的常量，供多组断言复用
    private val defaultFontPx = 54f          // 18sp @ 3x 密度
    private val defaultScreenH = 2400f
    private val defaultTracks = 8

    // ============================================================ 轨道高度

    @Test
    fun `区域充足时行距完全由字号决定`() {
        val h = DanmuEngine.computeTrackHeightPx(
            fontSizePx = 50f, areaHeightPx = 1_000_000f, trackCount = 4
        )
        assertEquals(50f * DanmuEngine.TRACK_HEIGHT_FONT_FACTOR, h, 0.01f)
    }

    @Test
    fun `行距等于字号乘以系数`() {
        val h = DanmuEngine.computeTrackHeightPx(
            fontSizePx = 30f, areaHeightPx = 100_000f, trackCount = 2
        )
        assertEquals(30f * 1.9f, h, 0.01f)
    }

    @Test
    fun `区域充足时轨道数不影响行距`() {
        // 「以字号为基准」的关键含义：轨道数只决定能排几行，不该改变行距
        val area = 1_000_000f
        val font = 24f
        val h2 = DanmuEngine.computeTrackHeightPx(font, area, 2)
        val h8 = DanmuEngine.computeTrackHeightPx(font, area, 8)
        val h20 = DanmuEngine.computeTrackHeightPx(font, area, 20)
        assertEquals(h2, h8, 0.01f)
        assertEquals(h8, h20, 0.01f)
    }

    @Test
    fun `区域不足时行距被压缩`() {
        // 20 轨 × 字号 40（自然行距 76）→ 总高 1520 远超区域 600
        val area = 600f
        val tracks = 20
        val h = DanmuEngine.computeTrackHeightPx(40f, area, tracks)
        assertEquals("应压缩到区域均分", area / tracks, h, 0.01f)
        assertTrue("轨道总高不应超出弹幕区", h * tracks <= area + 0.01f)
    }

    @Test
    fun `任何参数下行距都不超过理想值`() {
        // 不变式：h <= natural（区域只会"压缩"，不会"拉大"行距）
        val areas = listOf(50f, 300f, 600f, 2000f, 100_000f)
        val fonts = listOf(10f, 18f, 24f, 40f, 60f)
        val trackCounts = listOf(1, 2, 4, 8, 12, 20)
        for (a in areas) for (f in fonts) for (t in trackCounts) {
            val h = DanmuEngine.computeTrackHeightPx(f, a, t)
            val natural = f * DanmuEngine.TRACK_HEIGHT_FONT_FACTOR
            assertTrue(
                "行距不应超过理想值 (area=$a font=$f tracks=$t h=$h natural=$natural)",
                h <= natural + 0.01f
            )
        }
    }

    @Test
    fun `任何参数下总高都不超出弹幕区`() {
        val areas = listOf(100f, 300f, 600f, 1200f, 2000f)
        val fonts = listOf(10f, 18f, 24f, 40f, 60f)
        val trackCounts = listOf(1, 2, 4, 8, 12, 20)
        for (a in areas) for (f in fonts) for (t in trackCounts) {
            val h = DanmuEngine.computeTrackHeightPx(f, a, t)
            assertTrue(
                "总高超出弹幕区 (area=$a font=$f tracks=$t h=$h total=${h * t})",
                h * t <= a + 0.01f
            )
        }
    }

    @Test
    fun `轨道数为 0 或负数时按 1 处理不崩`() {
        val area = 600f
        val font = 20f
        val a = DanmuEngine.computeTrackHeightPx(font, area, 0)
        val b = DanmuEngine.computeTrackHeightPx(font, area, -5)
        assertEquals(font * DanmuEngine.TRACK_HEIGHT_FONT_FACTOR, a, 0.01f)
        assertEquals(a, b, 0.01f)
    }

    @Test
    fun `区域高度为 0 时只用字号基准`() {
        // 布局尚未完成（maxHeight=0）。⚠️ 不能返回 0：轨道高 0 会让所有弹幕叠在第 0 行。
        val h = DanmuEngine.computeTrackHeightPx(20f, 0f, 8)
        assertEquals(20f * DanmuEngine.TRACK_HEIGHT_FONT_FACTOR, h, 0.01f)
        assertTrue(h > 0f)
    }

    @Test
    fun `字号为 0 时返回 0 而不是负值`() {
        assertEquals(0f, DanmuEngine.computeTrackHeightPx(0f, 600f, 8), 0.01f)
        assertEquals(0f, DanmuEngine.computeTrackHeightPx(0f, 0f, 8), 0.01f)
    }

    @Test
    fun `系数为 1_9`() {
        assertEquals(1.9f, DanmuEngine.TRACK_HEIGHT_FONT_FACTOR, 0.001f)
    }

    // ============================================================ 弹幕区高度（本版核心）

    @Test
    fun `区域高度随字号增长`() {
        val t = 8
        val screenH = 5000f
        val small = DanmuEngine.computeAreaHeightPx(20f, screenH, t)
        val large = DanmuEngine.computeAreaHeightPx(40f, screenH, t)
        assertTrue("字号变大区域应变高（$small → $large）", large > small)
    }

    @Test
    fun `区域高度随轨道数增长`() {
        val font = 20f
        val screenH = 5000f
        val few = DanmuEngine.computeAreaHeightPx(font, screenH, 4)
        val many = DanmuEngine.computeAreaHeightPx(font, screenH, 12)
        assertTrue("轨道变多区域应变高（$few → $many）", many > few)
    }

    @Test
    fun `区域高度等于轨道数乘理想行距`() {
        val font = 20f
        val tracks = 6
        val screenH = 100_000f  // 屏幕足够大，不触发上限
        val h = DanmuEngine.computeAreaHeightPx(font, screenH, tracks)
        assertEquals(tracks * font * 1.9f, h, 0.01f)
    }

    @Test
    fun `区域高度受屏高上限约束`() {
        // 20 轨 × 60px 字号 → 期望 2280px，但屏高 1000 × 0.45 = 450 → 取 450
        val screenH = 1000f
        val capped = DanmuEngine.computeAreaHeightPx(60f, screenH, 20)
        assertEquals(screenH * DanmuEngine.AREA_HEIGHT_RATIO_MAX, capped, 0.01f)
    }

    @Test
    fun `任何参数下区域高度不超过上限`() {
        val screens = listOf(500f, 1080f, 2400f, 5000f)
        val fonts = listOf(10f, 18f, 24f, 40f, 60f)
        val trackCounts = listOf(1, 4, 8, 12, 20)
        for (s in screens) for (f in fonts) for (t in trackCounts) {
            val area = DanmuEngine.computeAreaHeightPx(f, s, t)
            val cap = s * DanmuEngine.AREA_HEIGHT_RATIO_MAX
            assertTrue(
                "区域超出上限 (screen=$s font=$f tracks=$t area=$area cap=$cap)",
                area <= cap + 0.01f
            )
        }
    }

    @Test
    fun `屏高为 0 时不做上限约束`() {
        val h = DanmuEngine.computeAreaHeightPx(20f, 0f, 4)
        assertEquals(4 * 20f * 1.9f, h, 0.01f)
    }

    @Test
    fun `区域上限比例为 0_45`() {
        assertEquals(0.45f, DanmuEngine.AREA_HEIGHT_RATIO_MAX, 0.001f)
    }

    // ============================================================ 默认参数观感（回归）

    @Test
    fun `默认参数下行距现在由字号决定而非被区域压回`() {
        // ⚠️ 这是本版**最关键的回归断言**（对应真实场景 1080×2400 / 3x / 8 轨 / 18sp）
        // 旧：区域固定 2400×0.30 = 720 → 均分 90（行距被压死，且不随字号变）
        // 新：区域 = min(8 × 54 × 1.9, 2400 × 0.45) = min(820.8, 1080) = 820.8
        //     行距 = min(102.6, 820.8/8=102.6) = 102.6 ✅ 理想行距生效
        val screenH = defaultScreenH
        val area = DanmuEngine.computeAreaHeightPx(defaultFontPx, screenH, defaultTracks)
        val trackH = DanmuEngine.computeTrackHeightPx(defaultFontPx, area, defaultTracks)

        val ideal = defaultFontPx * 1.9f
        assertEquals("默认参数下应达到理想行距", ideal, trackH, 0.01f)
        assertTrue("应大于旧算法的 90px", trackH > 90f)
        assertEquals(820.8f, area, 0.5f)
    }

    @Test
    fun `默认参数下区域不再固定为屏高的三成`() {
        val screenH = defaultScreenH
        val area = DanmuEngine.computeAreaHeightPx(defaultFontPx, screenH, defaultTracks)
        val oldFixed = screenH * 0.30f  // 720
        assertTrue("新区域($area)应大于旧固定值($oldFixed)", area > oldFixed)
    }

    @Test
    fun `大字号且轨道多时会顶到上限但仍不溢出屏幕`() {
        // 极端：20 轨 × 32sp@3x(=96px) → 内容 3648px，屏高 2400 → 上限 1080
        val screenH = 2400f
        val area = DanmuEngine.computeAreaHeightPx(96f, screenH, 20)
        assertEquals(screenH * DanmuEngine.AREA_HEIGHT_RATIO_MAX, area, 0.01f)
        val trackH = DanmuEngine.computeTrackHeightPx(96f, area, 20)
        // 行距被压到 54px（= area/tracks），比理想 182.4 小，但保证 20 轨都在区内
        assertEquals(54f, trackH, 0.01f)
        assertTrue("总高不应超出区域", trackH * 20 <= area + 0.01f)
    }
}
