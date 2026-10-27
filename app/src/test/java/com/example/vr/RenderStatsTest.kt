package com.example.vr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [RenderStats] 的纯逻辑单测（v2.6.16，P0）。
 *
 * 这些断言防的是**度量说谎** —— 度量一旦不准，之后所有性能优化都建立在错误结论上：
 * - 分档阈值写反 → 卡顿帧被算成流畅帧；
 * - 抖动判据用错基准（该用 maxFps 却硬编码 60）→ 30 fps 限帧时全帧误判为抖动；
 * - 窗口结算漏算首帧间隔 / 不重置 → fps 越跑越偏。
 */
class RenderStatsTest {

    /** 每次用例前清干净 —— RenderStats 是全局单例，用例之间会串状态 */
    @Before
    fun setUp() = RenderStats.reset()

    @After
    fun tearDown() = RenderStats.reset()

    /**
     * 按固定节奏喂帧。
     *
     * @param frames 帧数
     * @param intervalMs 帧开始之间的间隔（含限帧等待）
     * @param cpuMs 每帧 onDrawFrame 内部耗时
     * @param targetFps 传给 onFrameEnd 的 maxFps
     * @return 最后一帧结束时的时间戳（纳秒）
     */
    private fun feed(
        frames: Int,
        intervalMs: Double,
        cpuMs: Double = 2.0,
        targetFps: Int = 0,
        startNs: Long = 1_000_000_000L
    ): Long {
        var begin = startNs
        var lastNow = startNs
        repeat(frames) {
            val now = begin + (cpuMs * 1_000_000.0).toLong()
            RenderStats.onFrameEnd(begin, targetFps, now)
            lastNow = now
            begin += (intervalMs * 1_000_000.0).toLong()
        }
        return lastNow
    }

    // ===================== 帧率与帧间隔 =====================

    @Test
    fun `stable 60fps reports about 60`() {
        // 61 帧 × 16.667ms ≈ 1017ms → 跨过一个 1 秒窗口
        feed(frames = 61, intervalMs = 1000.0 / 60.0)
        val s = RenderStats.snapshot
        assertTrue("应有数据", s.hasData)
        assertTrue("fps 应接近 60，实际 ${s.fps}", s.fps in 58..62)
        assertEquals("稳定 60fps 不该有抖动帧", 0, s.jankFrames)
        // 首帧没有间隔可算，所以间隔数 = frames - 1
        assertEquals(s.frames - 1, s.buckets.sum())
    }

    @Test
    fun `throttled 30fps is not counted as jank`() {
        // 关键回归：抖动基准必须取 maxFps，不能硬编码 60。
        // 30 fps 限帧下每帧 33.3ms，若按 60 判（阈值 25ms）会被全判成抖动。
        feed(frames = 31, intervalMs = 1000.0 / 30.0, targetFps = 30)
        val s = RenderStats.snapshot
        assertTrue("fps 应接近 30，实际 ${s.fps}", s.fps in 28..32)
        assertEquals("限帧到 30 不算抖动", 0, s.jankFrames)
        assertEquals(30, s.targetFps)
    }

    @Test
    fun `slow frames land in the last bucket and count as jank`() {
        // 100ms 一帧 ≈ 10fps：远超 60fps 基准的 1.5 倍（25ms）
        feed(frames = 12, intervalMs = 100.0)
        val s = RenderStats.snapshot
        assertEquals("全部间隔都应落在 ≥50ms 档", s.frames - 1, s.buckets[3])
        assertEquals("全部间隔都算抖动", s.frames - 1, s.jankFrames)
        assertTrue("fps 应接近 10，实际 ${s.fps}", s.fps in 8..12)
    }

    @Test
    fun `bucket boundaries follow the documented thresholds`() {
        // 每档各喂满一个 1 秒窗口，验证分档边界没写反（<16.7 / <33.3 / <50 / ≥50）。
        // ⚠️ 帧数按「跑满 1 秒」反推：窗口不满时不会发布，见下一条用例。
        feed(frames = 101, intervalMs = 10.0)
        assertEquals("10ms 应进第一档", 100, RenderStats.snapshot.buckets[0])

        RenderStats.reset()
        feed(frames = 51, intervalMs = 20.0)
        assertEquals("20ms 应进第二档", 50, RenderStats.snapshot.buckets[1])

        RenderStats.reset()
        feed(frames = 26, intervalMs = 40.0)
        assertEquals("40ms 应进第三档", 25, RenderStats.snapshot.buckets[2])

        RenderStats.reset()
        feed(frames = 14, intervalMs = 80.0)
        assertEquals("80ms 应进第四档", 13, RenderStats.snapshot.buckets[3])
    }

    /**
     * 上面几档的间隔都小于 1 秒，窗口不会结算 —— 所以 snapshot 仍是上一次 publish 的值。
     * 这条用例专门覆盖「窗口未满时不发布」，防止 UI 拿到半窗口的 fps（会剧烈跳动）。
     */
    @Test
    fun `window is not published before one second elapses`() {
        feed(frames = 5, intervalMs = 10.0)   // 约 40ms，远不到 1 秒
        assertEquals("未满窗口不应发布", 0, RenderStats.snapshot.frames)
    }

    // ===================== CPU 耗时 =====================

    @Test
    fun `cpu average and peak are tracked separately`() {
        var begin = 1_000_000_000L
        // 交替 2ms / 40ms，跑够 1 秒窗口
        val cpus = List(61) { if (it % 2 == 0) 2.0 else 40.0 }
        cpus.forEach { cpu ->
            RenderStats.onFrameEnd(begin, 0, begin + (cpu * 1_000_000.0).toLong())
            begin += (1000.0 / 60.0 * 1_000_000.0).toLong()
        }
        val s = RenderStats.snapshot
        assertTrue("峰值应记录到 40ms 左右，实际 ${s.cpuMaxMs}", s.cpuMaxMs >= 39f)
        assertTrue("均值应在 2~40 之间，实际 ${s.cpuAvgMs}", s.cpuAvgMs in 15f..30f)
        assertTrue("峰值必须大于均值", s.cpuMaxMs > s.cpuAvgMs)
    }

    @Test
    fun `interval average excludes the first frame`() {
        feed(frames = 61, intervalMs = 1000.0 / 60.0)
        val s = RenderStats.snapshot
        // 首帧没有间隔，均值就该贴近 16.67，不该被一个 0 拉低
        assertTrue("平均间隔应接近 16.7ms，实际 ${s.intervalAvgMs}", s.intervalAvgMs in 16f..18f)
    }

    // ===================== 重置 =====================

    @Test
    fun `reset clears snapshot and restarts interval tracking`() {
        feed(frames = 61, intervalMs = 1000.0 / 60.0)
        assertTrue(RenderStats.snapshot.hasData)

        RenderStats.reset()
        assertEquals(0, RenderStats.snapshot.frames)
        assertEquals(0, RenderStats.snapshot.fps)

        // reset 后第一帧同样没有「上一帧」，所以间隔统计应从 0 开始
        RenderStats.onFrameEnd(5_000_000_000L, 0, 5_002_000_000L)
        assertEquals(0f, RenderStats.snapshot.intervalAvgMs, 0.001f)
    }

    @Test
    fun `invalid begin timestamp is ignored`() {
        // 华为 VR 模式那条路径不埋点，若漏了判空就会出现 0 起点导致 cpuMs 巨大
        RenderStats.onFrameEnd(0L, 0, 1_000_000L)
        assertEquals("begin<=0 应被忽略", 0, RenderStats.snapshot.frames)
    }
}
