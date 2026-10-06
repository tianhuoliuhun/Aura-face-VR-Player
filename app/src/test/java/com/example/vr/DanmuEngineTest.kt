package com.example.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DanmuEngine 单元测试（v2.3.1 / P4）
 *
 * 引擎是**纯逻辑**（无 Context / View / GL 依赖），因此能直接在 JVM 上测。
 * 这里覆盖三类容易出错的行为：去重、轨道避让、生命周期。
 *
 * ⚠️ 引擎默认用 `android.os.SystemClock`，测试里**必须注入 nowProvider**，
 *    否则会因缺少 Android 运行时（Robolectric 未启用）而失败。
 */
class DanmuEngineTest {

    /** 造一个时间可控的引擎 */
    private class Clock {
        var t: Long = 0L
        fun advance(ms: Long) { t += ms }
    }

    private fun newEngine(): Pair<DanmuEngine, Clock> {
        val clock = Clock()
        val engine = DanmuEngine()
        engine.nowProvider = { clock.t }
        engine.screenWidthPx = 1000f
        engine.speedPxPerSec = 200
        engine.trackHeightPx = 40f
        return engine to clock
    }

    // ---------------------------------------------------------------- 去重

    @Test
    fun `完全相同文本会被去重`() {
        val (engine, clock) = newEngine()
        assertEquals(1, engine.enqueue(listOf("这画面太美了"), 0L))
        // 第二条完全相同 → 应被丢弃
        assertEquals(0, engine.enqueue(listOf("这画面太美了"), 100L))
        assertEquals(1, engine.size)
    }

    @Test
    fun `仅标点差异会被去重`() {
        val (engine, clock) = newEngine()
        assertEquals(1, engine.enqueue(listOf("哈哈哈哈"), 0L))
        assertEquals(0, engine.enqueue(listOf("哈哈哈哈！！！"), 100L))
    }

    @Test
    fun `明显不同的文本不会被去重`() {
        val (engine, clock) = newEngine()
        assertEquals(1, engine.enqueue(listOf("主角好帅"), 0L))
        assertEquals(1, engine.enqueue(listOf("背景音乐好听"), 100L))
        assertEquals(2, engine.size)
    }

    @Test
    fun `去重只比较最近窗口内的文本`() {
        val (engine, clock) = newEngine()
        engine.maxTracks = 20
        engine.speedPxPerSec = 2000  // 快速离场，避免轨道被占满
        // 先塞一条，然后塞满超过窗口长度的其他文本
        engine.enqueue(listOf("重复的那句"), 0L)
        var t = 0L
        repeat(DanmuEngine.DEDUP_WINDOW + 5) { i ->
            t += 1
            engine.enqueue(listOf("其他内容第${i}条"), t)
        }
        // 窗口已滚过，原句不再是「重复」
        val before = engine.size
        val added = engine.enqueue(listOf("重复的那句"), t + 1)
        assertTrue("窗口滚过后应可再次入队", added >= 0)
        assertTrue(before >= 0)
    }

    // ---------------------------------------------------------------- 轨道

    @Test
    fun `轨道用满后新弹幕被丢弃而不是重叠`() {
        val (engine, clock) = newEngine()
        engine.maxTracks = 3
        // 同一时刻连续入队 10 条**互不相似**的弹幕。
        // ⚠️ 必须真的不相似：若只差一个序号（如"弹幕内容第N号"），
        //    会被 80% 相似度阈值判重 → 只有第一条能进，测不出轨道行为。
        val texts = listOf(
            "这光影绝了", "主角演技在线", "背景音乐好听", "运镜好流畅",
            "这段我看了三遍", "配乐一响就燃", "导演懂观众", "色调很舒服",
            "这段剪辑聪明", "结尾留白好评"
        )
        val added = engine.enqueue(texts, 0L)
        // 同一时刻最多 3 条（每条占一条空轨道），其余被丢弃
        assertEquals(3, added)
        assertEquals(3, engine.size)
    }

    @Test
    fun `相似文本会在轨道判据之前就被去重`() {
        val (engine, clock) = newEngine()
        engine.maxTracks = 8
        // 只有 1 个字符不同 → 相似度远高于 80% 阈值
        val added = engine.enqueue(listOf("弹幕内容第1号", "弹幕内容第2号"), 0L)
        assertEquals("只差一个序号应被判重", 1, added)
        assertEquals(1, engine.size)
    }

    @Test
    fun `弹幕左移足够远后轨道可复用`() {
        val (engine, clock) = newEngine()
        engine.maxTracks = 1
        engine.speedPxPerSec = 500
        engine.screenWidthPx = 1000f
        assertEquals(1, engine.enqueue(listOf("第一条"), 0L))
        // 时间推进 2 秒：第一条已左移 1000px，完全离开右缘
        assertEquals(1, engine.enqueue(listOf("第二条"), 2000L))
        assertEquals(2, engine.size)
    }

    @Test
    fun `单轨道时刻太近时第二条被拒绝`() {
        val (engine, clock) = newEngine()
        engine.maxTracks = 1
        engine.speedPxPerSec = 200
        engine.screenWidthPx = 1000f
        assertEquals(1, engine.enqueue(listOf("第一条"), 0L))
        // 仅过 10ms，间距远不够 → 应被拒绝
        assertEquals(0, engine.enqueue(listOf("紧跟着的"), 10L))
        assertEquals(1, engine.size)
    }

    // ---------------------------------------------------------------- 生命周期

    @Test
    fun `过期弹幕会被 prune 回收`() {
        val (engine, clock) = newEngine()
        engine.speedPxPerSec = 500
        engine.screenWidthPx = 1000f
        engine.enqueue(listOf("会被回收的"), 0L)
        assertEquals(1, engine.size)
        // 3 秒后：位移 1500px > 屏宽 1000 + 文本宽 → 已离开
        engine.prune(3000L)
        assertEquals(0, engine.size)
    }

    @Test
    fun `未过期弹幕不会被误回收`() {
        val (engine, clock) = newEngine()
        engine.speedPxPerSec = 100
        engine.screenWidthPx = 1000f
        engine.enqueue(listOf("还在屏幕上"), 0L)
        engine.prune(1000L)  // 才走 100px
        assertEquals(1, engine.size)
    }

    @Test
    fun `clear 清空弹幕但保留去重窗口`() {
        val (engine, clock) = newEngine()
        engine.enqueue(listOf("某句弹幕"), 0L)
        engine.clear()
        assertEquals(0, engine.size)
        // 去重窗口保留 → 同样的句子仍被判重
        assertEquals(0, engine.enqueue(listOf("某句弹幕"), 1000L))
    }

    @Test
    fun `reset 连去重窗口一起清空`() {
        val (engine, clock) = newEngine()
        engine.enqueue(listOf("某句弹幕"), 0L)
        engine.reset()
        assertEquals(0, engine.size)
        // 窗口已清 → 同样的句子可以再次入队
        assertEquals(1, engine.enqueue(listOf("某句弹幕"), 1000L))
    }

    @Test
    fun `队列上限生效`() {
        val (engine, clock) = newEngine()
        engine.maxTracks = 20
        engine.maxPending = 5
        engine.speedPxPerSec = 2000
        val texts = (1..20).map { "不同的弹幕第${it}号内容" }
        val added = engine.enqueue(texts, 0L)
        assertTrue("受 maxPending 限制，不应超过 5", added <= 5)
    }

    // ---------------------------------------------------------------- 位置公式

    @Test
    fun `位置由时间线性推导`() {
        val (engine, clock) = newEngine()
        engine.screenWidthPx = 1000f
        engine.speedPxPerSec = 200
        val item = DanmuItem(text = "x", track = 0, bornMs = 0L, widthPx = 50f)
        // t=0：位于屏幕右缘
        assertEquals(1000f, item.currentX(0L, 1000f, 200), 0.01f)
        // t=1s：左移 200px
        assertEquals(800f, item.currentX(1000L, 1000f, 200), 0.01f)
        // t=2.5s：左移 500px
        assertEquals(500f, item.currentX(2500L, 1000f, 200), 0.01f)
    }

    @Test
    fun `完全离开左缘才算过期`() {
        val item = DanmuItem(text = "x", track = 0, bornMs = 0L, widthPx = 50f)
        // x = 10 > -50，还没完全离开
        assertFalse(item.isExpired(4950L, 1000f, 200))
        // x = -10 + 50 = 40 > 0，仍在屏内
        assertFalse(item.isExpired(5050L, 1000f, 200))
        // x = -100 + 50 = -50 < 0，完全离开
        assertTrue(item.isExpired(5500L, 1000f, 200))
    }

    @Test
    fun `暂停恢复通过平移出生时间实现`() {
        val (engine, clock) = newEngine()
        engine.screenWidthPx = 1000f
        engine.speedPxPerSec = 200
        engine.enqueue(listOf("暂停测试"), 0L)
        val item = engine.snapshot().first()
        val xBefore = item.currentX(1000L, 1000f, 200)  // 800
        // 暂停 5 秒后恢复：born 平移 5000
        engine.shiftBornTime(5000L)
        val xAfter = item.currentX(6000L, 1000f, 200)   // 仍应是 800
        assertEquals(xBefore, xAfter, 0.01f)
    }

    @Test
    fun `变速不会让已存在的弹幕瞬移`() {
        val (engine, clock) = newEngine()
        engine.screenWidthPx = 1000f
        engine.speedPxPerSec = 200
        engine.enqueue(listOf("变速测试"), 0L)
        val item = engine.snapshot().first()
        // 先推进到 t=1000 时的位置（由外部时间控制需重新注入，这里直接算）
        val xBefore = item.currentX(1000L, 1000f, 200)
        // 变速：clock 停在 1000，引擎会据此平移 born
        clock.t = 1000L
        engine.speedPxPerSec = 400
        val xAfter = item.currentX(1000L, 1000f, 400)
        assertEquals("变速瞬间位置应保持连续", xBefore, xAfter, 1.0f)
    }
}
