package com.example.vr

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/**
 * v2.1.233：**给 Compose 的玻璃效果提供「真实的背景内容」**。
 *
 * ## 为什么需要它
 * 播放页的视频画在 `VRGLSurfaceView`（SurfaceView）上，而 SurfaceView 在 Android
 * 合成器里是**独立窗口、打洞**的 —— Compose 的 `rememberLayerBackdrop` 采的是
 * Compose 自己的绘制层，**采不到 SurfaceView 的内容**。
 *
 * 于是 backdrop 里实际只有 `drawRect(ThemeBgColor)` 那一层纯色，
 * 而**对纯色做高斯模糊，结果还是同一片纯色** ——
 * 这就是「磨砂看不出模糊」「高斯模糊没生效」的根因（不是参数没调对）。
 *
 * ## 做法
 * 在 GL 线程把当前画面**降采样**到 96×N 再读回 CPU（一次约 5 千像素，开销近乎为零），
 * 交给 Compose 画进 backdrop —— 这样 backdrop 里就有了真实的视频内容（虽然很糊），
 * 再叠系统 RenderEffect 高斯模糊，**糊的就是真画面了**。
 *
 * 降采样本身就是一次盒式模糊，与后续的高斯模糊叠加后视觉上是连贯的。
 *
 * ## 线程模型
 * GL 线程写 `back`、写完后与 `front` 交换；Compose 只读 `front`。
 * 两块 bitmap 轮换复用，避免了每帧分配，也保证了 UI 读到的永远不是 GL 正在写的那块。
 */
object VideoBackdrop {

    /** 只有玻璃/模糊档位开启时才需要采样（由 UI 侧设置） */
    @Volatile
    var enabled: Boolean = false

    /**
     * 目标宽度；高度按屏幕比例算。
     *
     * v2.6.18：**96 → 320 → 640**。
     * v2.6.18：**640 → 960** —— 液态玻璃去掉全局模糊后，降采样不再被高斯模糊掩盖：
     * 玻璃内部现在是**原清晰度**的 backdrop 位图直接放大绘制，640 宽放大到 1080/1220 宽
     * 的屏幕约 1.9 倍，肉眼看得出"发软"。960（≈1.25 倍）在观感上足够清晰。
     *
     * 96 是"反正后面要糊掉"时代的取值；但真液态玻璃的 `lens()` 要把背景"掰弯"，
     * 位图会**按面板尺寸放大后**参与折射 —— 96px 放大到 600dp 宽的面板上是 6 倍，
     * 折射出来仍是色块，而且面板后的画面明显糊。
     *
     * 回读量：960×432×4 ≈ 1.6MB/帧（30ms 一次 ≈ 54MB/s）。`glReadPixels` 是同步的，
     * 再往上调（≥1080 全宽）会在 GL 线程引入可见阻塞，**必须真机验帧率**再决定。
     * 高度上限见 [com.example.vr.VRGLRenderer] 的 `coerceIn(1, 1280)`。
     */
    const val TARGET_W = 960

    @Volatile
    private var front: Bitmap? = null

    private var back: Bitmap? = null
    private val lock = Any()

    /** 成功提交的次数（诊断用） */
    @Volatile
    var version: Long = 0
        private set

    /**
     * v2.4.46（模糊 UI 审查 #2）：**驱动 Compose 重绘的 tick**。
     *
     * ## 为什么必须要有它
     * `front` 是普通字段、`version` 也不在 Compose 的观察范围内 —— 所以 GL 线程提交了新帧，
     * **背景层 lambda 不会被重新执行**，`rememberLayerBackdrop` 记录下来的还是旧内容
     * → 面板背后的画面「卡在第一次采样的那一帧」。
     * 把它做成 `mutableLongStateOf`，并在背景层 lambda 里读一下，Compose 才会在它变化时
     * 重新记录背景层。
     *
     * ⚠️ GL 线程写 Snapshot State 是允许的（会被标脏、由主线程统一重组）。
     */
    var tick by mutableLongStateOf(0L)
        private set

    /**
     * v2.4.46（审查 #2 附带）：面板隐藏/暂停时置 true —— 不再驱动重绘，
     * 省掉 80dp 高斯模糊的反复重算。
     */
    @Volatile
    var paused: Boolean = false

    /**
     * v2.4.46（审查 #1）：最近一次采样失败的原因（供设置页「玻璃诊断」显示）。
     * `null` = 没失败过。
     */
    @Volatile
    var lastError: String? = null

    /** 最近一次成功提交的墙钟（ms），供诊断显示「距上次提交多久」 */
    @Volatile
    var lastCommitAtMs: Long = 0L
        private set

    /**
     * v2.4.47：GL 侧当前的**采样间隔（ms）**，由 [com.example.vr.VRGLRenderer] 写入，
     * 仅供设置页「玻璃诊断」显示（用户要求「30ms 一帧」后可直接核对）。
     */
    @Volatile
    var captureIntervalMs: Long = 0L

    /**
     * GL 线程调用：把一帧（已按 Bitmap 的**自上而下**顺序排好）提交为新的背景。
     *
     * @param pixels ARGB packed 且**行序已翻转**为自上而下（与 Bitmap 一致）
     */
    fun commit(pixels: IntArray, w: Int, h: Int) {
        if (!enabled) return
        var b = back
        if (b == null || b.width != w || b.height != h) {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            back = b
        }
        b.setPixels(pixels, 0, w, 0, 0, w, h)
        synchronized(lock) {
            val old = front
            front = b
            // 复用上一张当作下一次的写入目标（双缓冲轮换）
            back = old ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        version++
        lastCommitAtMs = android.os.SystemClock.elapsedRealtime()
        lastError = null
        // v2.4.47（用户要求「30ms 一帧」）：GL 侧已按时间节流到 30ms，这里**每次提交都推进 tick**，
        // 于是 backdrop 层正好以 30ms 的节奏重录。
        // （v2.4.46 曾额外「每 2 次才推进一次」，与 30ms 节流叠加会变成 60ms，偏慢。）
        // 但仍必须**推进** —— 不推进的话 GL 提交了新帧，背景层也永不重绘（审查 #2）。
        if (!paused) tick = version
    }

    /** Compose 线程调用：当前可作为背景绘制的帧；不需要时返回 null */
    fun current(): Bitmap? = if (enabled) front else null

    /** 供设置页「玻璃诊断」：当前 front 位图尺寸（`null` = 还没有帧） */
    fun currentSize(): String? = front?.let { "${it.width}x${it.height}" }

    /** 关闭时释放，避免常驻两张 bitmap */
    fun release() {
        enabled = false
        synchronized(lock) {
            front = null
            back = null
        }
    }
}
