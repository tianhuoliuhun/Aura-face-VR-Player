package com.example.vr

import android.graphics.Bitmap

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

    /** 目标宽度；高度按屏幕比例算。96 已经足够 —— 反正后面还要糊掉 */
    const val TARGET_W = 96

    @Volatile
    private var front: Bitmap? = null

    private var back: Bitmap? = null
    private val lock = Any()

    /** 成功提交的次数（诊断用） */
    @Volatile
    var version: Long = 0
        private set

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
    }

    /** Compose 线程调用：当前可作为背景绘制的帧；不需要时返回 null */
    fun current(): Bitmap? = if (enabled) front else null

    /** 关闭时释放，避免常驻两张 bitmap */
    fun release() {
        enabled = false
        synchronized(lock) {
            front = null
            back = null
        }
    }
}
