package com.example.vr

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4.6：引擎的**逐条颜色**测试。
 *
 * ## 关键设计约束（本测试的核心目的）
 * 颜色必须在**入队时**掷定并固定在 [DanmuItem] 上 ——
 * ⚠️ 若改成在渲染时（每帧）现掷，颜色会以帧率级频率闪烁（本项目已踩过同类坑）。
 * 因此这里专门验证「同一条弹幕的颜色在多次读取后不变」。
 *
 * 另外验证：同一批内**逐条独立**掷色（不是每批统一一个色），
 * 否则视觉上仍等同于「单一颜色」，随机模式形同虚设。
 */
class DanmuEngineColorTest {

    private class Clock {
        var t: Long = 0L
    }

    private fun newEngine(rolls: List<Float>): Pair<DanmuEngine, Clock> {
        val clock = Clock()
        val engine = DanmuEngine()
        engine.nowProvider = { clock.t }
        engine.screenWidthPx = 1000f
        engine.speedPxPerSec = 200
        engine.maxTracks = 10
        // 用确定性序列提供随机数（循环使用）
        var i = 0
        engine.randomProvider = {
            val v = rolls[i % rolls.size]
            i++
            v
        }
        return engine to clock
    }

    // ------------------------------------------------------- 入队时定色

    @Test
    fun `单一模式下所有弹幕同色`() {
        val (engine, _) = newEngine(listOf(0.1f, 0.5f, 0.9f))
        engine.colorMode = DanmuColorMode.SINGLE
        engine.singleColor = Color(0xFF336699)
        engine.enqueue(listOf("一", "二", "三"), 0L)
        assertEquals(3, engine.size)
        engine.snapshot().forEach {
            assertEquals(Color(0xFF336699), it.color)
        }
    }

    @Test
    fun `同批弹幕逐条独立取色`() {
        // 关键：不能是"每批一个色"。用不同 roll 让每条取到不同色板项。
        val n = DanmuConfig.DANMU_PALETTE.size
        val rolls = (0 until n).map { (it + 0.5f) / n }
        val (engine, _) = newEngine(rolls)
        engine.colorMode = DanmuColorMode.RANDOM
        engine.enqueue(listOf("一", "二", "三", "四", "五", "六"), 0L)

        val colors = engine.snapshot().map { it.color }
        assertTrue("同批应出现多种颜色，实际 ${colors.toSet()}", colors.toSet().size > 1)
    }

    @Test
    fun `入队后颜色固定不再变化`() {
        // 防"渲染时现掷 → 每帧变色"
        val (engine, _) = newEngine(listOf(0.42f, 0.42f, 0.42f))
        engine.colorMode = DanmuColorMode.RANDOM
        engine.enqueue(listOf("固定色"), 0L)
        val first = engine.snapshot().first().color
        // 多次读取（模拟多帧渲染）
        repeat(5) {
            assertEquals("颜色不应在入队后改变", first, engine.snapshot().first().color)
        }
    }

    @Test
    fun `随机模式忽略单一颜色`() {
        val (engine, _) = newEngine(listOf(0.9f))
        engine.colorMode = DanmuColorMode.RANDOM
        engine.singleColor = Color(0xFF010203)
        engine.enqueue(listOf("测试"), 0L)
        val c = engine.snapshot().first().color
        assertNotEquals(Color(0xFF010203), c)
        assertTrue(DanmuConfig.DANMU_PALETTE.contains(c))
    }

    // ------------------------------------------------------- 模式切换

    @Test
    fun `切换颜色模式后新弹幕用新模式`() {
        val (engine, clock) = newEngine(listOf(0.9f, 0.9f, 0.9f, 0.9f))
        engine.colorMode = DanmuColorMode.SINGLE
        engine.singleColor = Color(0xFF445566)
        engine.enqueue(listOf("旧"), 0L)
        val oldColor = engine.snapshot().first().color
        assertEquals(Color(0xFF445566), oldColor)

        // 切成随机后再入队
        engine.colorMode = DanmuColorMode.RANDOM
        clock.t = 10_000L
        engine.enqueue(listOf("新"), clock.t)
        val newItem = engine.snapshot().first { it.text == "新" }
        // 新弹幕应走随机色板（roll=0.9 → 末位色）
        assertTrue(DanmuConfig.DANMU_PALETTE.contains(newItem.color))
        // 已有弹幕颜色**不应被改写**（改模式不追溯历史条目）
        assertEquals(Color(0xFF445566), engine.snapshot().first { it.text == "旧" }.color)
    }

    // ------------------------------------------------------- 默认值

    @Test
    fun `引擎默认颜色模式为单一且颜色为白`() {
        val engine = DanmuEngine()
        assertEquals(DanmuColorMode.SINGLE, engine.colorMode)
        assertEquals(Color.White, engine.singleColor)
    }

    @Test
    fun `默认随机源可用且落在 0 到 1`() {
        // 不注入 randomProvider，验证默认实现能跑通（不会 NPE）
        val engine = DanmuEngine()
        engine.nowProvider = { 0L }
        engine.screenWidthPx = 1000f
        engine.colorMode = DanmuColorMode.RANDOM
        engine.enqueue(listOf("默认随机源"), 0L)
        assertEquals(1, engine.size)
        assertTrue(DanmuConfig.DANMU_PALETTE.contains(engine.snapshot().first().color))
    }

    // ------------------------------------------------------- 八成白分布

    @Test
    fun `八成白模式下引擎产出的白色占多数`() {
        val rnd = java.util.Random(42L)
        val engine = DanmuEngine()
        engine.nowProvider = { 0L }
        engine.screenWidthPx = 1000f
        engine.maxTracks = 20
        engine.colorMode = DanmuColorMode.MOSTLY_WHITE
        engine.randomProvider = { rnd.nextFloat() }

        val texts = (1..200).map { "弹幕$it" }
        engine.enqueue(texts, 0L)
        val whites = engine.snapshot().count { it.color == Color.White }
        val total = engine.size
        assertTrue("应有弹幕入队", total > 0)
        val pct = whites * 100.0 / total
        assertTrue("白色占比 $pct% 应过半", pct > 50.0)
    }
}
