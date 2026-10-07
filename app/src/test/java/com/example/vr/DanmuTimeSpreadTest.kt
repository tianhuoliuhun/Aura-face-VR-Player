package com.example.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * v2.4.7：批次时间戳「正态分布抖动」的单测。
 *
 * 覆盖三件事：
 * 1. **[spreadBornTimes] 的统计形态** —— 均值、标准差、长尾截断；
 * 2. **顺序保持** —— 抖动后必须单调不减（用户明确要求「按顺序」）；
 * 3. **[DanmuEngine.enqueue] 的集成行为** —— 真正用上抖动、且不破坏既有约束。
 *
 * ⚠️ 所有随机都通过 `roll` 注入**确定性序列**，不使用真随机 ——
 *    否则断言会随机失败（本项目全部单测均为纯 JVM 可重复执行）。
 */
class DanmuTimeSpreadTest {

    // ---------------------------------------------------------------
    // 测试替身：确定性伪随机（线性同余），形态近似均匀
    // ---------------------------------------------------------------

    /**
     * 线性同余发生器（LCG）：给 [DanmuEngine.spreadBornTimes] 提供可控的 0..1 序列。
     *
     * 只要求**统计上近似均匀**且**完全可复现** —— 不追求密码学质量。
     * 常量取自 Numerical Recipes（`a=1664525, c=1013904223, m=2^32`）。
     */
    private class Lcg(seed: Long = 20261006L) {
        private var state: Long = seed and 0xFFFFFFFFL
        fun next(): Float {
            state = (1664525L * state + 1013904223L) and 0xFFFFFFFFL
            return state.toFloat() / 4294967296f  // / 2^32
        }
        fun asRoll(): () -> Float = { next() }
    }

    // ---------------------------------------------------------------
    // 一、spreadBornTimes —— 基本形态
    // ---------------------------------------------------------------

    @Test
    fun `count 为 0 或负数时返回空数组`() {
        val r = Lcg().asRoll()
        assertEquals(0, DanmuEngine.spreadBornTimes(0, 1_000L, 400f, r).size)
        assertEquals(0, DanmuEngine.spreadBornTimes(-5, 1_000L, 400f, r).size)
    }

    @Test
    fun `返回数组长度等于 count`() {
        val r = Lcg().asRoll()
        assertEquals(8, DanmuEngine.spreadBornTimes(8, 1_000L, 400f, r).size)
        assertEquals(30, DanmuEngine.spreadBornTimes(30, 1_000L, 400f, r).size)
    }

    @Test
    fun `sigma 为 0 时全部等于中心时刻（等价于不抖动）`() {
        val r = Lcg().asRoll()
        val out = DanmuEngine.spreadBornTimes(8, 50_000L, 0f, r)
        for (t in out) assertEquals(50_000L, t)
    }

    @Test
    fun `sigma 为负或非有限时全部等于中心时刻`() {
        val r = Lcg().asRoll()
        for (t in DanmuEngine.spreadBornTimes(5, 777L, -100f, r)) assertEquals(777L, t)
        for (t in DanmuEngine.spreadBornTimes(5, 777L, Float.NaN, r)) assertEquals(777L, t)
        for (t in DanmuEngine.spreadBornTimes(5, 777L, Float.POSITIVE_INFINITY, r)) assertEquals(777L, t)
    }

    // ---------------------------------------------------------------
    // 二、顺序保持 —— 「按顺序」的硬约束
    // ---------------------------------------------------------------

    @Test
    fun `抖动后必须单调不减（保持 AI 返回顺序）`() {
        // 多个种子都要满足，避免"某个种子恰好有序"的假阳性
        for (seed in 1L..20L) {
            val r = Lcg(seed).asRoll()
            val out = DanmuEngine.spreadBornTimes(12, 10_000L, 400f, r)
            for (i in 1 until out.size) {
                assertTrue(
                    "seed=$seed 第 ${i - 1}→$i 条不满足单调不减: ${out.toList()}",
                    out[i] >= out[i - 1]
                )
            }
        }
    }

    @Test
    fun `严格单调递减的均匀随机序列也不会让顺序倒挂`() {
        // 注入一个「每次都返回极小值」的退化序列：Box-Muller 会给出极端抖动，
        // 但排序 + 按下标取用后仍必须有序
        var n = 0
        val decreasing: () -> Float = { (0.5f / (n++ + 1)) }  // 0.5, 0.25, 0.166...
        val out = DanmuEngine.spreadBornTimes(10, 5_000L, 400f, decreasing)
        for (i in 1 until out.size) assertTrue(out[i] >= out[i - 1])
    }

    @Test
    fun `相同抖动值并列时顺序仍保持`() {
        // 全部返回同一个值 → 所有抖动完全相同 → 排序后全等 → 输出全等（合法）
        val same: () -> Float = { 0.5f }
        val out = DanmuEngine.spreadBornTimes(6, 1_234L, 400f, same)
        for (i in 1 until out.size) assertTrue(out[i] >= out[i - 1])
    }

    // ---------------------------------------------------------------
    // 三、统计形态 —— 正态分布的均值/标准差/截断
    // ---------------------------------------------------------------

    @Test
    fun `大样本下均值贴近中心时刻`() {
        val r = Lcg(42L).asRoll()
        val center = 1_000_000L
        val n = 4000
        val out = DanmuEngine.spreadBornTimes(n, center, 400f, r)
        val mean = out.map { it - center }.average()
        // 单侧抖动均值应接近 0；允许 ±40ms（= 0.1σ）的抽样误差
        assertTrue("均值偏移过大: $mean", abs(mean) < 40.0)
    }

    @Test
    fun `大样本下标准差贴近 sigma`() {
        val r = Lcg(1234L).asRoll()
        val n = 4000
        val sigma = 400f
        // ⚠️ 用真实量级的 center（elapsedRealtime 级别的毫秒数）。
        //    传 0 会让负抖动越过时间原点 → 若函数内部做了非负钳位就会压平左半边分布
        //    （曾踩：sd 从 400 掉到 228）。这里用大 center 保证测的是分布本身。
        val center = 1_000_000L
        val out = DanmuEngine.spreadBornTimes(n, center, sigma, r)
        val mean = out.average()
        // 用**样本**标准差（除以 n-1）
        val varSum = out.map { (it - mean) * (it - mean) }.sum()
        val sd = sqrt(varSum / (n - 1))
        // 允许 ±10% 的抽样误差
        assertTrue("标准差偏离过大: $sd (期望约 $sigma)", sd > sigma * 0.90 && sd < sigma * 1.10)
    }

    @Test
    fun `抖动量被硬截断在 ±SPREAD_MAX_ABS_MS 之内`() {
        val r = Lcg(7L).asRoll()
        val center = 100_000L
        val out = DanmuEngine.spreadBornTimes(2000, center, 400f, r)
        for (t in out) {
            val d = t - center
            assertTrue(
                "抖动量 $d 超出截断上限 ${DanmuEngine.SPREAD_MAX_ABS_MS}",
                d >= -DanmuEngine.SPREAD_MAX_ABS_MS && d <= DanmuEngine.SPREAD_MAX_ABS_MS
            )
        }
    }

    @Test
    fun `spreadBornTimes 是纯数学函数不做非负钳位（分布形态优先）`() {
        // center 为 0 时负抖动应当**保留为负数**，否则左半边分布被压平
        // （这是刻意设计：非负兜底放在 enqueue 里，见其注释）
        val r = Lcg(9L).asRoll()
        val out = DanmuEngine.spreadBornTimes(50, 0L, 400f, r)
        assertTrue("应当存在负值（说明未做钳位）", out.any { it < 0L })
    }

    @Test
    fun `中心时刻附近最密（正态的核心特征）`() {
        val r = Lcg(2026L).asRoll()
        val n = 3000
        val sigma = 400f
        val center = 1_000_000L
        val out = DanmuEngine.spreadBornTimes(n, center, sigma, r)
        // |d| < 1σ 的占比应接近 68%（允许 ±5pp）
        val within1 = out.count { abs(it - center) < sigma }.toFloat() / n
        assertTrue("|d|<1σ 占比异常: $within1", within1 > 0.62f && within1 < 0.74f)
        // |d| < 2σ 的占比应接近 95%（允许 ±4pp）
        val within2 = out.count { abs(it - center) < 2 * sigma }.toFloat() / n
        assertTrue("|d|<2σ 占比异常: $within2", within2 > 0.90f && within2 < 0.99f)
    }

    // ---------------------------------------------------------------
    // 四、standardNormal 本身
    // ---------------------------------------------------------------

    @Test
    fun `standardNormal 的 u1 为 0 时不产生无穷大`() {
        // 第一次 roll 返回 0（ln(0) = -∞ 的陷阱） → 必须被兜住
        var n = 0
        val roll: () -> Float = { if (n++ % 2 == 0) 0f else 0.5f }
        val z = DanmuEngine.standardNormal(roll)
        assertTrue("产生了非有限值: $z", z.isFinite())
    }

    @Test
    fun `standardNormal 大样本下均值约 0 标准差约 1`() {
        val r = Lcg(555L).asRoll()
        val n = 4000
        val zs = FloatArray(n) { DanmuEngine.standardNormal(r) }
        val mean = zs.average()
        val varSum = zs.map { (it - mean) * (it - mean) }.sum()
        val sd = sqrt(varSum / (n - 1))
        assertTrue("Z 均值偏移: $mean", abs(mean) < 0.10)
        assertTrue("Z 标准差偏离: $sd", sd > 0.90 && sd < 1.10)
    }

    // ---------------------------------------------------------------
    // 五、DanmuEngine 集成：抖动真的生效了
    // ---------------------------------------------------------------

    /** 造一个已注入时钟与随机的引擎 */
    private fun engine(seed: Long = 1L): Pair<DanmuEngine, Lcg> {
        val lcg = Lcg(seed)
        val e = DanmuEngine()
        e.nowProvider = { 100_000L }
        e.randomProvider = lcg.asRoll()
        e.screenWidthPx = 1920f
        e.maxTracks = 12
        return e to lcg
    }

    @Test
    fun `入队后各条出生时刻不完全相同（抖动生效）`() {
        val (e, _) = engine()
        val texts = (1..8).map { "弹幕$it" }
        val added = e.enqueue(texts, 100_000L)
        assertTrue("期望入队若干条，实际 $added", added >= 2)
        val times = e.snapshot().map { it.bornMs }.distinct()
        assertTrue("各条出生时刻完全相同 → 抖动未生效", times.size >= 2)
    }

    @Test
    fun `入队后按出生时刻排序的顺序与文本顺序一致`() {
        val (e, _) = engine(3L)
        val texts = (1..8).map { "序号$it" }
        e.enqueue(texts, 100_000L)
        // 引擎内部按入队顺序保存；出生时刻应单调不减
        val born = e.snapshot().map { it.bornMs }
        for (i in 1 until born.size) {
            assertTrue("第 $i 条出生时刻倒挂: $born", born[i] >= born[i - 1])
        }
    }

    @Test
    fun `timeJitterMs 为 0 时同批出生时刻完全相同`() {
        val (e, _) = engine()
        e.timeJitterMs = 0f
        val texts = (1..8).map { "同刻$it" }
        // ⚠️ 不要期望"只有 1 条能进"：本批用 usedTracks **禁止复用轨道**，
        //    而 maxTracks=12 足够容纳 8 条 → 8 条各占一条轨道全部入队，
        //    只是它们的 bornMs 完全相同（同一瞬间一起出场）。
        //    这正是"关闭抖动"该有的语义：同时出场、但分散在不同轨道。
        val added = e.enqueue(texts, 100_000L)
        assertEquals(8, added)
        assertEquals("出生时刻应完全相同", 1, e.snapshot().map { it.bornMs }.distinct().size)
        // 且轨道互不相同
        assertEquals("轨道应互不重复", 8, e.snapshot().map { it.track }.distinct().size)
    }

    @Test
    fun `关闭抖动且轨道数不足时多余的条目被丢弃`() {
        // 关闭抖动 + 轨道只有 3 条 → 一批 8 条只能进 3 条（每轨一条，且同刻无法复轨）
        val lcg = Lcg(1L)
        val e = DanmuEngine()
        e.nowProvider = { 100_000L }
        e.randomProvider = lcg.asRoll()
        e.screenWidthPx = 1920f
        e.maxTracks = 3
        e.timeJitterMs = 0f
        val added = e.enqueue((1..8).map { "同刻$it" }, 100_000L)
        assertEquals(3, added)
    }

    @Test
    fun `baseTimesMs 指定的时刻被原样采用（导入弹幕不打散）`() {
        val (e, _) = engine()
        e.timeJitterMs = 400f
        val texts = listOf("A", "B", "C")
        // 显式给三个相距很远、且**逆序**的时刻
        val explicit = longArrayOf(100_000L, 130_000L, 160_000L)
        val added = e.enqueue(texts, 0L, explicit)
        assertEquals(3, added)
        val born = e.snapshot().map { it.bornMs }.sorted()
        assertEquals(listOf(100_000L, 130_000L, 160_000L), born)
    }

    @Test
    fun `baseTimesMs 长度不足时余下条目走抖动`() {
        val (e, _) = engine()
        e.timeJitterMs = 400f
        val texts = listOf("A", "B", "C", "D")
        val explicit = longArrayOf(100_000L)  // 只有第一条指定
        val added = e.enqueue(texts, 200_000L, explicit)
        assertTrue("期望至少入队 2 条", added >= 2)
        val born = e.snapshot().map { it.bornMs }
        assertTrue("指定的时刻未被采用", born.contains(100_000L))
        // 其余条目应在中心 200_000 附近 ±1000ms 内
        val rest = born.filter { it != 100_000L }
        for (t in rest) assertTrue("抖动条目越界: $t", abs(t - 200_000L) <= DanmuEngine.SPREAD_MAX_ABS_MS)
    }

    @Test
    fun `入队条数不超过 maxPending`() {
        val (e, _) = engine()
        e.maxPending = 3
        val texts = (1..20).map { "限流$it" }
        val added = e.enqueue(texts, 100_000L)
        assertTrue("入队 $added 条，超过上限 3", added <= 3)
    }

    @Test
    fun `空文本与纯空白被跳过`() {
        val (e, _) = engine()
        val added = e.enqueue(listOf("", "  ", "\n", "真弹幕"), 100_000L)
        assertEquals(1, added)
        assertEquals("真弹幕", e.snapshot()[0].text)
    }

    @Test
    fun `抖动不会破坏相似度去重`() {
        val (e, _) = engine()
        val texts = listOf("重复的话", "重复的话", "重复的话", "另一句")
        val added = e.enqueue(texts, 100_000L)
        // 「重复的话」只应进 1 条
        assertEquals(2, added)
    }

}
