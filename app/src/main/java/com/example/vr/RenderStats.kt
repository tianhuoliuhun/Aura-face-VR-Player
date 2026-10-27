package com.example.vr

/**
 * 全局渲染帧度量（v2.6.16，P0）。
 *
 * ## 为什么需要它
 * 在这之前项目里唯一的「帧率」来自 [PerfFloatingBall] 的 `Choreographer` 回调 —— 那是
 * **主线程 UI 帧**的频率，不是 GL 渲染帧。两者会严重背离：渲染侧因为解码慢 / 填充率 /
 * 回读卡住而掉到 20 fps 时，只要主线程还有动画在跑，`Choreographer` 照样报 60。
 * 结果就是「看着 60 fps，画面明明在卡」，任何优化都无从验证。
 *
 * 所以这里直接量两件事，且口径分得很清楚：
 * - **帧间隔**（`intervalAvgMs` / `fps`）：两次 `onDrawFrame` 之间的实际间隔，
 *   **包含**被 `maxFps` 限帧 sleep 的时间 → 反映用户真正看到的帧率。
 * - **CPU 耗时**（`cpuAvgMs` / `cpuMaxMs`）：`onDrawFrame` 内部的执行时间，**不含**限帧
 *   sleep。它是 GL 命令提交 + 解码 surface 取帧 + 本帧所有 CPU 侧工作的成本。
 *
 * ⚠️ 口径限制（别误读）：GL 是异步的，`onDrawFrame` 返回时 GPU 往往还没画完。
 * 所以 `cpuMs` 不是 GPU 时间；「CPU 很低但 fps 也低」基本就是 GPU / 带宽瓶颈，
 * 而「CPU 很高」才是本帧的 Java 侧太重（如人脸检测回读、GPUPixel 贴回）。
 *
 * ## 线程模型
 * `onFrameBegin` / `onFrameEnd` **只允许渲染线程调用**（内部是普通字段，无锁）。
 * 对外只暴露一个 [@Volatile] 的不可变 [Snapshot]，任意线程读都是安全的 ——
 * 每秒最多构造一个对象，读侧不会看到半更新的数据。
 *
 * 纯 Kotlin（只用 `System.nanoTime`），可在 JVM 单测里直接驱动。
 */
object RenderStats {

    /** 没拿到 maxFps 时的判抖动基准 */
    const val DEFAULT_TARGET_FPS = 60

    /** 统计窗口：1 秒。太短会有明显跳动，太长反应迟钝 */
    private const val WINDOW_NS = 1_000_000_000L

    /** 一帧的度量快照（不可变，可安全跨线程） */
    data class Snapshot(
        /** 窗口内实际完成帧数换算出的帧率 */
        val fps: Int = 0,
        /** onDrawFrame 内部平均耗时（ms），不含限帧 sleep */
        val cpuAvgMs: Float = 0f,
        /** 窗口内最慢的一帧（ms） */
        val cpuMaxMs: Float = 0f,
        /** 平均帧间隔（ms），含限帧 sleep */
        val intervalAvgMs: Float = 0f,
        /** 帧间隔超过「目标间隔 × 1.5」的帧数 */
        val jankFrames: Int = 0,
        /** 窗口内总帧数 */
        val frames: Int = 0,
        /** 本窗口采用的目标帧率（抖动判据的基准） */
        val targetFps: Int = DEFAULT_TARGET_FPS,
        /** 帧间隔分布：`[<16.7ms, <33.3ms, <50ms, ≥50ms]` */
        val buckets: List<Int> = emptyList()
    ) {
        /** 是否已有有效数据（渲染器还没跑过时全 0，UI 应回退其它来源） */
        val hasData: Boolean get() = frames > 0
    }

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    // ------------------------- 渲染线程独占状态 -------------------------
    /** 上一帧结算过的「帧开始」时刻，用于算帧间隔（0 = 还没有上一帧） */
    private var lastBeginNs = 0L
    /** 当前窗口的起点（第一帧的 begin） */
    private var windowStartNs = 0L
    private var frames = 0
    private var cpuSumMs = 0.0
    private var cpuMaxMs = 0f
    private var intervalCount = 0
    private var intervalSumMs = 0.0
    private var jank = 0
    private val buckets = IntArray(4)

    /**
     * 标记一帧开始，返回该时刻。调用方须把返回值原样传给 [onFrameEnd]。
     *
     * ⚠️ 调用位置很关键：必须放在 `maxFps` 限帧 sleep **之后**。
     * 若放在 sleep 之前，限帧等待会被算进 `cpuMs`，30 fps 限帧时会凭空多出 16ms「CPU 耗时」。
     */
    fun onFrameBegin(nowNs: Long = System.nanoTime()): Long = nowNs

    /**
     * 结算一帧。[beginNs] 是 [onFrameBegin] 的返回值。
     * [targetFps] 传当前 `maxFps`（0 或负数表示不限帧，按 [DEFAULT_TARGET_FPS] 判抖动）。
     */
    fun onFrameEnd(beginNs: Long, targetFps: Int = 0, nowNs: Long = System.nanoTime()) {
        if (beginNs <= 0L) return

        val prevBeginNs = lastBeginNs
        lastBeginNs = beginNs

        val cpuMs = ((nowNs - beginNs) / 1_000_000.0).toFloat().coerceAtLeast(0f)
        frames++
        cpuSumMs += cpuMs
        if (cpuMs > cpuMaxMs) cpuMaxMs = cpuMs

        val effTarget = if (targetFps > 0) targetFps else DEFAULT_TARGET_FPS
        // prevBeginNs 为 0 表示这是首帧（或刚 reset），没有间隔可算；
        // 间隔用两次「帧开始」之差 —— sleep 在 begin 之前，所以它天然包含限帧等待。
        if (prevBeginNs > 0L && beginNs > prevBeginNs) {
            val intervalMs = ((beginNs - prevBeginNs) / 1_000_000.0).toFloat()
            intervalCount++
            intervalSumMs += intervalMs
            val targetMs = 1000f / effTarget
            if (intervalMs > targetMs * 1.5f) jank++
            buckets[bucketOf(intervalMs)]++
        }

        if (windowStartNs == 0L) windowStartNs = beginNs

        if (nowNs - windowStartNs >= WINDOW_NS) {
            publish(nowNs - windowStartNs, effTarget)
        }
    }

    /** 帧间隔 → 分档下标 */
    private fun bucketOf(intervalMs: Float): Int = when {
        intervalMs < 16.7f -> 0
        intervalMs < 33.3f -> 1
        intervalMs < 50f -> 2
        else -> 3
    }

    private fun publish(elapsedNs: Long, targetFps: Int) {
        val n = frames
        if (n <= 0 || elapsedNs <= 0L) {
            resetWindow()
            return
        }
        // fps = 窗口内帧数 / 窗口时长。用整数毫秒级误差不敏感，直接 round
        val fps = Math.round(n * 1_000_000_000.0 / elapsedNs).toInt()
        snapshot = Snapshot(
            fps = fps,
            cpuAvgMs = (cpuSumMs / n).toFloat(),
            cpuMaxMs = cpuMaxMs,
            intervalAvgMs = if (intervalCount > 0) (intervalSumMs / intervalCount).toFloat() else 0f,
            jankFrames = jank,
            frames = n,
            targetFps = targetFps,
            buckets = buckets.toList()
        )
        resetWindow()
    }

    private fun resetWindow() {
        frames = 0
        cpuSumMs = 0.0
        cpuMaxMs = 0f
        intervalCount = 0
        intervalSumMs = 0.0
        jank = 0
        buckets.fill(0)
        windowStartNs = 0L
    }

    /** 彻底重置（切换媒体 / 重建渲染器时调用，避免把上一段的统计带过来） */
    fun reset() {
        resetWindow()
        lastBeginNs = 0L
        snapshot = Snapshot()
    }
}
