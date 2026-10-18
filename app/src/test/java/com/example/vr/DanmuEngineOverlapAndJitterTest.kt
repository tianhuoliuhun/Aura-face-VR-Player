package com.example.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.2：修「弹幕重叠 / 从屏幕中间冒出 / 抖动看不出效果」的回归测试。
 *
 * 三组断言分别对应三个真实缺陷：
 * 1. **宽度**：入队时就要有保守宽度，否则同轨道避让按 `widthPx = 0` 算 → 重叠；
 * 2. **出生时刻下限**：调用方传的内容基准时刻可能早于渲染时刻 → `floorMs` 兜底；
 * 3. **抖动可观测**：σ=400 时出生时刻必须不全相等且单调不减，σ=0 时全等。
 *
 * 全部纯 JVM、随机数注入确定性 LCG —— 不依赖 Android 运行时。
 */
class DanmuEngineOverlapAndJitterTest {

    /** 确定性线性同余（只要求可复现，不追求随机质量）。 */
    private class Lcg(seed: Long = 20261018L) {
        private var state: Long = seed and 0xFFFFFFFFL
        fun next(): Float {
            state = (1664525L * state + 1013904223L) and 0xFFFFFFFFL
            return state.toFloat() / 4294967296f
        }
        fun asRoll(): () -> Float = { next() }
    }

    private fun engine(
        maxTracks: Int = 8,
        fontSizePx: Float = 54f,
        now: Long = 100_000L,
        seed: Long = 1L
    ): DanmuEngine {
        val lcg = Lcg(seed)
        return DanmuEngine().apply {
            nowProvider = { now }
            randomProvider = lcg.asRoll()
            screenWidthPx = 1000f
            speedPxPerSec = 200
            trackHeightPx = fontSizePx * DanmuEngine.TRACK_HEIGHT_FONT_FACTOR
            this.fontSizePx = fontSizePx
            this.maxTracks = maxTracks
            timeJitterMs = 0f
        }
    }

    // ------------------------------------------------------------ 宽度估计

    @Test
    fun `宽度估计_中文按一字号_ASCII按半字号左右_空文本为 0`() {
        val font = 54f
        val cjk = DanmuEngine.estimateTextWidthPx("中文", font)
        val ascii = DanmuEngine.estimateTextWidthPx("ab", font)
        val other = DanmuEngine.estimateTextWidthPx("★☆", font)

        assertEquals("两个汉字 ≈ 2 × 字号 × 1.08", 2 * font * 1.08f, cjk, 0.01f)
        assertEquals("两个 ASCII ≈ 2 × 0.55 × 字号 × 1.08", 2 * font * 0.55f * 1.08f, ascii, 0.01f)
        assertTrue("中文应比 ASCII 宽", cjk > ascii)
        assertTrue("其它字符按 0.8 倍估计", other > ascii && other < cjk)

        assertEquals(0f, DanmuEngine.estimateTextWidthPx("", font), 0.001f)
        assertEquals(0f, DanmuEngine.estimateTextWidthPx("中文", 0f), 0.001f)
        assertEquals(0f, DanmuEngine.estimateTextWidthPx("中文", Float.NaN), 0.001f)
    }

    @Test
    fun `入队后立即有保守宽度_不再等渲染层回填`() {
        val e = engine()
        e.enqueue(listOf("十个汉字测试弹幕宽度"), 100_000L)
        val item = e.snapshot().single()
        assertEquals("十个汉字 ≈ 10 × 54 × 1.08", 10 * 54f * 1.08f, item.widthPx, 0.01f)
    }

    // ------------------------------------------------------------ 同轨道避让

    @Test
    fun `同轨道上一条已进屏时_按估计宽度判定为太近而拒绝复用`() {
        // 这条是「弹幕重叠」的直接回归：
        // 若 widthPx 仍是 0，t=1500 时 lastX=700 → gap=300 ≥ minGap(90) → 允许复用；
        // 实际这条 10 字弹幕宽 ≈583px，右边缘还在 x=1283（屏外右侧），
        // 新弹幕一进来就会压上去 → 必须拒绝。
        val e = engine(maxTracks = 1)
        assertEquals(1, e.enqueue(listOf("十个汉字测试弹幕宽度"), 100_000L))
        assertEquals(
            "上一条的尾巴还没进屏，不应复用同轨道",
            0,
            e.enqueue(listOf("另一条完全不同的内容"), 101_500L)
        )
        assertEquals(1, e.size)
    }

    @Test
    fun `上一条真正走远后_同轨道仍可复用`() {
        val e = engine(maxTracks = 1)
        assertEquals(1, e.enqueue(listOf("十个汉字测试弹幕宽度"), 100_000L))
        // 再走 4 秒：lastX = 200，右边缘 ≈ 783，间距 = 1000 - 783 = 217 ≥ minGap
        assertEquals(1, e.enqueue(listOf("另一条完全不同的内容"), 104_000L))
        assertEquals(2, e.size)
    }

    // ------------------------------------------------------------ 出生时刻下限

    @Test
    fun `floorMs 保证出生时刻不早于渲染时钟`() {
        // 模拟调用方把「内容基准时刻」倒推了 3 秒（旧实现导致从屏幕中间冒出）
        val e = engine()
        e.timeJitterMs = 400f
        val contentBase = 97_000L      // 早于真实渲染时刻
        val renderNow = 100_000L
        val added = e.enqueue(
            texts = listOf("一号", "二号", "三号", "四号", "五号"),
            nowMs = contentBase,
            floorMs = renderNow
        )
        assertEquals(5, added)
        val born = e.snapshot().map { it.bornMs }
        assertTrue("所有 born 都应 ≥ floorMs，实际 $born", born.all { it >= renderNow })
        for (i in 1 until born.size) {
            assertTrue("born 应单调不减，实际 $born", born[i] >= born[i - 1])
        }
    }

    @Test
    fun `新参数默认值不改变旧调用方行为`() {
        val e = engine()
        e.timeJitterMs = 400f
        val now = 100_000L
        e.enqueue(listOf("甲", "乙", "丙"), now)
        assertTrue(e.snapshot().all { it.bornMs >= now })
    }

    // ------------------------------------------------------------ 抖动可观测

    @Test
    fun `σ=400 时一批 5 条不全相等且单调不减`() {
        val e = engine()
        e.timeJitterMs = 400f
        val now = 100_000L
        assertEquals(5, e.enqueue(listOf("一号", "二号", "三号", "四号", "五号"), now))

        val born = e.snapshot().map { it.bornMs }
        assertEquals("应保留 5 条", 5, born.size)
        assertTrue("抖动应让出生时刻不全相等：$born", born.distinct().size >= 2)
        for (i in 1 until born.size) {
            assertTrue("born 应单调不减：$born", born[i] >= born[i - 1])
        }
        // 统计值也要能反映抖动（供面板/日志观测）
        val delays = e.lastBatchDelaysMs
        assertEquals(5, delays.size)
        assertTrue("延迟应至少有一条 > 0：${delays.toList()}", delays.any { it > 0 })
        assertEquals("延迟应升序", delays.toList(), delays.sorted())
    }

    @Test
    fun `σ=0 时出生时刻全部等于 nowMs`() {
        val e = engine()
        e.timeJitterMs = 0f
        val now = 100_000L
        assertEquals(5, e.enqueue(listOf("一号", "二号", "三号", "四号", "五号"), now))
        val born = e.snapshot().map { it.bornMs }
        assertTrue("σ=0 应全部同一时刻：$born", born.all { it == now })
        assertTrue("延迟应全为 0", e.lastBatchDelaysMs.all { it == 0L })
    }

    @Test
    fun `σ 很大时出生延迟仍被 MAX_BIRTH_DELAY_MS 封顶`() {
        val e = engine()
        e.timeJitterMs = 2_000f
        val now = 100_000L
        e.enqueue(listOf("一号", "二号", "三号", "四号", "五号"), now)
        val delays = e.lastBatchDelaysMs
        assertTrue(
            "最大延迟 ${delays.maxOrNull()} 不应超过 ${DanmuEngine.MAX_BIRTH_DELAY_MS}",
            delays.all { it <= DanmuEngine.MAX_BIRTH_DELAY_MS }
        )
        val born = e.snapshot().map { it.bornMs }
        assertTrue(
            "极差不应超过上限",
            born.maxOrNull()!! - born.minOrNull()!! <= DanmuEngine.MAX_BIRTH_DELAY_MS
        )
    }

    @Test
    fun `debugSink 能观察到抖动链路`() {
        val e = engine()
        e.timeJitterMs = 400f
        val logs = mutableListOf<String>()
        e.debugSink = { logs.add(it) }
        e.enqueue(listOf("一号", "二号", "三号"), 100_000L)
        assertEquals(1, logs.size)
        assertTrue("日志应包含 σ 与 delays：${logs[0]}", logs[0].contains("σ=400"))
        assertTrue(logs[0].contains("delays="))
    }
}
