package com.example.vr

import android.opengl.GLES20
import android.opengl.GLES30
import android.os.SystemClock
import android.util.Log

/**
 * 玻璃界面 Backdrop 背景采样（同步 + PBO 异步双缓冲）与 AI 弹幕帧采样器，
 * 从 VRGLRenderer 拆出以实现独立维护。
 */
internal class VRGLBackdropAndDanmu(
    private val getDisplayWidth: () -> Int,
    private val getDisplayHeight: () -> Int,
    private val isSplitScreen: () -> Boolean,
) {
    companion object {
        private const val TAG = "VRGLBackdropAndDanmu"
        private const val BD_PBO_COUNT = 2
        private const val DANMU_MIN_CAPTURE_INTERVAL_MS = 1500L
    }

    private val displayWidth: Int get() = getDisplayWidth()
    private val displayHeight: Int get() = getDisplayHeight()
    private val isSplitScreenVR: Boolean get() = isSplitScreen()

    fun onSurfaceCreatedReset() {
        bdFboId = 0
        bdTexId = 0
        bdW = 0
        bdH = 0
        bdBuffer = null
        bdPixels = null
        bdRow = null
        bdPboIds[0] = 0
        bdPboIds[1] = 0
        bdPboFence[0] = 0L
        bdPboFence[1] = 0L
        bdPboCapBytes = 0
        bdPboIdx = 0
        resetBackdropCapture()
        VideoBackdrop.captureIntervalMs = bdCaptureIntervalMs
    }

    // v2.1.233：backdrop 背景采样
    //
    // 目的：把当前画面降采样成一张极小的图交给 Compose，让玻璃/模糊效果**真的有内容可糊**。
    // 背景（详见 VideoBackdrop 的注释）：SurfaceView 是打洞的独立窗口，Compose 的
    // layer backdrop 采不到它 —— backdrop 里只有一层纯色，模糊纯色等于没模糊。
    //
    // ⚠️ 必须在「本帧绘制完成、eglSwapBuffers 之前」读取，否则拿到的是已被换走的缓冲。
    // ⚠️ 尺寸刻意极小（96×N）：glReadPixels 是同步调用，全分辨率回读会卡死帧率
    //    （本项目 v2.0.182 就踩过 8MB 回读的坑），而这里只有约 5 千像素。
    // =======================================================================
    private var bdFboId = 0
    private var bdTexId = 0
    private var bdW = 0
    private var bdH = 0
    private var bdBuffer: java.nio.IntBuffer? = null
    private var bdPixels: IntArray? = null
    private var bdRow: IntArray? = null
    private var bdFrame = 0
    /**
     * v2.4.46（模糊 UI 审查 #1）：失败**不再永久停用**。
     *
     * 旧实现任何一次 GL 错误就把 `bdDisabled` 永久置 true —— 一次偶发失败（切后台、
     * 上下文短暂丢失、驱动抖动）之后，**本次会话的模糊就再也不更新**。
     * 现改为：记失败次数 + 下次重试时刻，最多重试 [BD_MAX_RETRIES] 次、间隔 [BD_RETRY_DELAY_MS]。
     */
    private var bdDisabled = false
    private var bdFailCount = 0
    private var bdRetryAtMs = 0L

    /** 上一帧的采样开关状态（捕捉 false→true 跳变，用于重建采样资源） */
    private var bdWasEnabled = false

    // =======================================================================
    // v2.2.0（P3）：AI 弹幕取帧
    //
    // 用途：给「AI 弹幕」业务层提供「用户当前看到的画面」的低频快照。
    //
    // ⚠️ 与 backdrop 采样的三点关键差异：
    //   1) **独立 FBO** —— 刻意不复用 `bdFboId` / `gpHalfFboId`。
    //      本项目已有多条回读链路（美颜 GPUPixel、背景采样），共用 FBO 会出现
    //      「谁最后绑定谁赢了」的竞态，且尺寸需求不同（弹幕要 1024 宽，backdrop 只要 96）。
    //   2) **由业务层按需触发**（`requestDanmuFrame()`），不是每帧自动跑。
    //   3) **分屏时只取左眼** —— 送模型只需一张单眼图，送整屏（并排双画面）
    //      会让模型看到两张一样的图并生成重复弹幕（方案文档 D5 决策）。
    //
    // ⚠️ glReadPixels 是**同步阻塞**调用（会让 GL 线程 flush 整条 GPU 管线），
    //    因此本功能有两重节流：业务层按 intervalSec 触发 + 此处的最小帧间隔守卫。
    //    反面判例见 v2.0.182（8.29MB 全分辨率回读拖垮帧率）。
    // =======================================================================
    private var danmuFboId = 0
    private var danmuTexId = 0
    private var danmuW = 0
    private var danmuH = 0
    private var danmuBuffer: java.nio.IntBuffer? = null
    private var danmuPixels: IntArray? = null
    private var danmuRow: IntArray? = null

    /** 待取帧请求：非 null 表示本帧结束后要回读一次。值为目标最大宽度。 */
    @Volatile private var danmuPendingMaxWidth: Int = 0

    /** 最近一次成功回读的结果（RGBA_8888 ARGB 数组 + 尺寸）。业务层取走即清空。 */
    @Volatile private var danmuResultPixels: IntArray? = null
    @Volatile private var danmuResultW: Int = 0
    @Volatile private var danmuResultH: Int = 0

    /** 失败计数：连续失败超过阈值就停用，避免每帧刷日志 */
    private var danmuFailCount = 0
    private var danmuDisabled = false

    /** 上次实际回读的时间戳（ms）。用于最小间隔守卫。 */
    private var danmuLastCaptureMs = 0L

    /**
     * 请求在**本帧结束后**回读一张画面快照（低频、按需）。
     *
     * @param maxWidth 目标最大宽度（会被裁剪到 [256, 1920]）。业务层应传 1024 左右。
     * @return true 表示请求已受理；false 表示当前不可用（已被停用/尺寸未就绪/节流中）。
     *
     * 线程安全：只写一个 `@Volatile Int`，实际回读发生在 GL 线程的 [onDrawFrame]。
     * 结果通过 [takeDanmuFrame] 取走。
     */
    fun requestDanmuFrame(maxWidth: Int = 1024): Boolean {
        if (danmuDisabled) return false
        if (displayWidth <= 0 || displayHeight <= 0) return false
        // 最小间隔守卫：即便业务层调用过密，也不会连续多帧阻塞 GL 线程
        val now = System.currentTimeMillis()
        if (now - danmuLastCaptureMs < DANMU_MIN_CAPTURE_INTERVAL_MS) return false
        danmuPendingMaxWidth = maxWidth.coerceIn(256, 1920)
        return true
    }

    /**
     * 取走最近一次回读结果（业务层在任意线程调用）。
     *
     * @return (pixels, width, height)；无新结果时返回 null。
     *         pixels 为 ARGB_8888 可直接填进 `Bitmap.setPixels` 的 IntArray，
     *         行序**已翻正**（自上而下），业务层无需再处理。
     *
     * ⚠️ 取出后即清空（一次性消费），避免业务层意外重复使用同一块内存。
     */
    fun takeDanmuFrame(): Triple<IntArray, Int, Int>? {
        val px = danmuResultPixels ?: return null
        val w = danmuResultW
        val h = danmuResultH
        danmuResultPixels = null
        danmuResultW = 0
        danmuResultH = 0
        if (w <= 0 || h <= 0 || px.size < w * h) return null
        return Triple(px, w, h)
    }

    fun releaseDanmuTarget() {
        if (danmuFboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(danmuFboId), 0)
            danmuFboId = 0
        }
        if (danmuTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(danmuTexId), 0)
            danmuTexId = 0
        }
        danmuW = 0
        danmuH = 0
        danmuBuffer = null
        danmuPixels = null
        danmuRow = null
    }

    private fun ensureDanmuTarget(w: Int, h: Int) {
        if (danmuFboId != 0 && danmuW == w && danmuH == h) return
        releaseDanmuTarget()
        val tex = IntArray(1)
        val fbo = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, tex[0], 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "danmu FBO incomplete: 0x${Integer.toHexString(status)}")
            GLES20.glDeleteTextures(1, tex, 0)
            GLES20.glDeleteFramebuffers(1, fbo, 0)
            return
        }
        danmuTexId = tex[0]
        danmuFboId = fbo[0]
        danmuW = w
        danmuH = h
        danmuBuffer = java.nio.IntBuffer.allocate(w * h)
        danmuPixels = IntArray(w * h)
        danmuRow = IntArray(w)
    }

    /**
     * 在本帧绘制完成后调用（见 [onDrawFrame] 末尾，与 [captureBackdropIfNeeded] 同级）。
     *
     * 实现要点（四条都是本项目踩过的坑）：
     *  ① `glReadPixels` **只裁剪、不缩放** → 必须先用 `glBlitFramebuffer` 真缩放到小 FBO
     *     （v2.0.182 判例：误以为减小宽高就是缩略图，实际只拿到左下角 1/4）
     *  ② `glReadPixels` 行序**自下而上** → 填进 Bitmap 前必须翻转，否则画面颠倒
     *  ③ `glGetError()` 要在 `glBindFramebuffer` **之前**取（bind 会改写错误状态）
     *  ④ 🔴 **颜色通道序**：`GL_RGBA` + `GL_UNSIGNED_BYTE` 写出的是**字节序 `R,G,B,A`**；
     *     而按 `IntBuffer` 逐元素取出时（小端设备）会组装成 **ABGR**，
     *     但 `Bitmap.createBitmap(int[], w, h, ARGB_8888)` 期望 **ARGB**
     *     → **R 与 B 互换 → 红蓝偏色**。
     *     ⚠️ 这是用户报的「传给模型的图偏色」的**真正根因**，
     *        且**与压缩无关** —— 在 GL 回读这一刻就已经错了，
     *        后面的缩放/JPEG 只是把错的颜色原样带给模型。v2.4.11 修。
     */
    fun captureDanmuFrameIfNeeded() {
        if (danmuDisabled) return
        val reqW = danmuPendingMaxWidth
        if (reqW <= 0) return
        danmuPendingMaxWidth = 0

        val dw = displayWidth
        val dh = displayHeight
        if (dw <= 0 || dh <= 0) return

        // 分屏时屏幕是「并排双画面」：只取左眼那一半，避免模型看到两张一样的图。
        // 非分屏（单眼铺满）时整屏即单眼画面，无需裁剪。
        val srcX = 0
        val srcW = if (isSplitScreenVR) dw / 2 else dw
        val srcY = 0
        val srcH = dh
        if (srcW <= 0 || srcH <= 0) return

        // ⚠️ v2.4.11：改用**长边**等比缩放（见 ImageScale），且与 DanmuVisionClient 用**同一套**。
        //    旧代码 `tw = reqW.coerceAtMost(srcW)` 是「按宽」——
        //    竖屏片源下会得到长边远超设定值的图（设 256 却得到 256×455，白耗 token）。
        val fitted = ImageScale.fitLongSide(srcW, srcH, reqW)
        var tw = fitted[0]
        var th = fitted[1]
        // ⚠️ 内存保护：回读要分配两份堆数组（每像素 4 字节 × 2），
        //    长边 4320 的竖屏视频会要 ~84MB 堆 → 必 OOM。
        //    超限就等比缩到上限以内（仍给出合法图，只是小于所设档位）。
        val totalPx = tw.toLong() * th
        if (totalPx > ImageScale.READBACK_MAX_PIXELS) {
            val k = kotlin.math.sqrt(ImageScale.READBACK_MAX_PIXELS.toDouble() / totalPx)
            tw = (tw * k).toInt().coerceAtLeast(1)
            th = (th * k).toInt().coerceAtLeast(1)
            Log.w(TAG, "danmu 取帧受内存上限约束：$totalPx px → ${tw}x$th")
        }

        try {
            ensureDanmuTarget(tw, th)
            if (danmuFboId == 0) {
                danmuFailCount++
                if (danmuFailCount >= 3) {
                    danmuDisabled = true
                    Log.w(TAG, "danmu 取帧目标创建失败 3 次，已停用")
                }
                return
            }

            // ① 先真缩放（只读源区域 = 左眼）
            GLES20.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
            GLES20.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, danmuFboId)
            GLES30.glBlitFramebuffer(
                srcX, srcY, srcX + srcW, srcY + srcH,
                0, 0, tw, th,
                GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_LINEAR
            )
            val err1 = GLES20.glGetError()
            if (err1 != GLES20.GL_NO_ERROR) {
                Log.w(TAG, "danmu blit failed err=$err1")
                danmuFailCount++
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                if (danmuFailCount >= 3) danmuDisabled = true
                return
            }

            // ② 再回读
            GLES20.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, danmuFboId)
            val buf = danmuBuffer ?: return
            buf.position(0)
            GLES20.glReadPixels(0, 0, tw, th, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            val err2 = GLES20.glGetError()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            if (err2 != GLES20.GL_NO_ERROR) {
                Log.w(TAG, "danmu readPixels failed err=$err2")
                danmuFailCount++
                if (danmuFailCount >= 3) danmuDisabled = true
                return
            }

            // ③ 行序翻转（GL 自下而上 → Bitmap 自上而下）
            buf.position(0)
            val px = danmuPixels ?: return
            buf.get(px)
            val row = danmuRow ?: return
            var y = 0
            while (y < th / 2) {
                val a = y * tw
                val b = (th - 1 - y) * tw
                System.arraycopy(px, a, row, 0, tw)
                System.arraycopy(px, b, px, a, tw)
                System.arraycopy(row, 0, px, b, tw)
                y++
            }

            // ④ 🔴 v2.4.11：**修正红蓝通道互换** —— 「传给模型的图偏色」的根因
            //     glReadPixels 写入字节序 R,G,B,A；按 IntBuffer 取出（小端）得到 ABGR；
            //     而 Bitmap 期望 ARGB → R/B 被交换 → 画面红蓝偏色。
            //     ⚠️ 必须在**行翻转之后**做（两者互不干扰，但集中在一处便于阅读）。
            ImageScale.swapRedBlueInPlace(px, tw * th)

            danmuFailCount = 0
            danmuLastCaptureMs = System.currentTimeMillis()
            danmuResultPixels = px.copyOf(tw * th)  // 复制一份交给业务层，避免被下帧覆盖
            danmuResultW = tw
            danmuResultH = th
        } catch (t: Throwable) {
            Log.w(TAG, "danmu capture 异常：${t.message}")
            danmuFailCount++
            if (danmuFailCount >= 3) danmuDisabled = true
        }
    }

    // ===== v2.6.20（P1，用户选定）：backdrop 回读 PBO 双缓冲 =====
    // 与美颜链路的 gpPbo* 同一套思路（fence + 双槽轮转），但状态独立：
    // 美颜的回读尺寸/节奏与玻璃采样完全不同，共用状态机只会互相绊倒。
    // 玻璃采样 40ms 一次，每次收割的是"上一帧提交"的 PBO —— 40ms 足够 GPU 完成
    // blit+copy，glClientWaitSync 几乎必然立即返回，GL 线程不再被管线 flush 阻塞。
    private val bdPboIds = IntArray(2)
    private val bdPboFence = LongArray(2)
    private var bdPboCapBytes = 0
    /** 下一个要写入的 PBO 槽（收割 1 - bdPboIdx） */
    private var bdPboIdx = 0
    /** PBO 异步回读总开关：任何一步 GL 异常 / fence 异常 → 永久回退同步读（护旧驱动） */
    @Volatile private var bdPboEnabled = true

    private fun ensureBdPbo(needBytes: Int) {
        if (!bdPboEnabled) return
        if (bdPboIds[0] == 0) GLES30.glGenBuffers(2, bdPboIds, 0)
        if (bdPboCapBytes < needBytes) {
            for (id in bdPboIds) {
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, id)
                GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, needBytes, null, GLES30.GL_STREAM_READ)
            }
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            bdPboCapBytes = needBytes
            bdPboIdx = 0
            bdPboFence[0] = 0L
            bdPboFence[1] = 0L
        }
    }

    private fun releaseBdPbo() {
        for (f in bdPboFence) if (f != 0L) GLES30.glDeleteSync(f)
        bdPboFence[0] = 0L
        bdPboFence[1] = 0L
        if (bdPboIds[0] != 0) {
            GLES30.glDeleteBuffers(2, bdPboIds, 0)
            bdPboIds[0] = 0
            bdPboIds[1] = 0
        }
        bdPboCapBytes = 0
        bdPboIdx = 0
    }

    fun releaseBackdropTarget() {
        if (bdFboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(bdFboId), 0)
            bdFboId = 0
        }
        if (bdTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(bdTexId), 0)
            bdTexId = 0
        }
        bdW = 0
        bdH = 0
        bdBuffer = null
        bdPixels = null
        bdRow = null
        releaseBdPbo()
    }

    private fun ensureBackdropTarget(w: Int, h: Int) {
        if (bdFboId != 0 && bdW == w && bdH == h) return
        releaseBackdropTarget()
        val tex = IntArray(1)
        val fbo = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, tex[0], 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "backdrop FBO incomplete: 0x${Integer.toHexString(status)}")
            releaseBackdropTarget()
            return
        }
        bdTexId = tex[0]
        bdFboId = fbo[0]
        bdW = w
        bdH = h
        bdBuffer = java.nio.IntBuffer.allocate(w * h)
        bdPixels = IntArray(w * h)
        bdRow = IntArray(w)
        ensureBdPbo(w * h * 4)
    }

    /** v2.4.46（审查 #1）：采样失败最多重试次数 */
    private val bdMaxRetries = 3

    /** v2.4.46（审查 #1）：采样失败后的重试间隔（ms） */
    private val bdRetryDelayMs = 30_000L

    /**
     * v2.4.47（用户要求）：**背景采样间隔**（≈33fps）。
     *
     * 旧实现是「每 3 帧采一次」—— 节奏随屏幕刷新率漂移（120Hz 屏只有 25ms、60Hz 屏 50ms），
     * 而且帧时间过短会让 backdrop 层频繁重录。改成固定节流后节奏稳定、开销可控。
     *
     * 🔴 v2.6.19：**30ms → 50ms**（33fps → 20fps），是「显化效果 + 显著降耗」的一半。
     * 🔴 v2.6.20（用户指定）：**50ms → 40ms**（25fps）。
     * 这个间隔同时是 Compose 侧的重录节奏（[VideoBackdrop.commit] 每提交一帧推进一次 tick），
     * 所以它直接决定：整屏 backdrop layer 的录制次数、以及每个玻璃元素重跑 lens pass 的次数。
     *
     * v2.6.20 说明：分辨率已经从 960 砍到 320（回读量 1.66MB → 0.18MB），
     * 单帧成本大幅下降，所以间隔可以回到 40ms 让边缘折射更跟手；
     * 而玻璃**本体已全透明**（透出真实视频），本体不依赖这张位图，滞后完全不可见。
     */
    private val bdCaptureIntervalMs = 40L

    /** 上次采样时刻（elapsedRealtime）—— 与 [bdCaptureIntervalMs] 配合做时间节流 */
    private var bdLastCaptureMs = 0L

    /**
     * v2.4.46（审查 #1）：记录一次采样失败 —— 写入诊断原因（设置页「玻璃诊断」会显示），
     * 按退避策略安排重试；**超过 [bdMaxRetries] 次才真正停用**（旧实现一次失败就永久停用）。
     */
    private fun bdFail(reason: String) {
        bdFailCount++
        bdDisabled = true
        bdRetryAtMs = android.os.SystemClock.elapsedRealtime() + bdRetryDelayMs
        VideoBackdrop.lastError = reason
        Log.w(TAG, "backdrop 采样失败(${bdFailCount}/${bdMaxRetries})：$reason")
    }

    /** 在本帧绘制完成后调用（见 [onDrawFrame] 末尾） */
    fun captureBackdropIfNeeded() {
        // v2.4.46（审查 #1）：采样开关 false→true 时重建资源（用户切到玻璃档 / 从后台回来）
        if (VideoBackdrop.enabled != bdWasEnabled) {
            bdWasEnabled = VideoBackdrop.enabled
            if (VideoBackdrop.enabled) {
                bdFailCount = 0
                bdRetryAtMs = 0L
                resetBackdropCapture()
                releaseBackdropTarget()
            }
        }
        if (!VideoBackdrop.enabled) return
        if (bdDisabled) {
            // 次数用尽 → 本会话不再采样；否则等到重试时刻再清掉失效资源重来
            if (bdFailCount >= bdMaxRetries) return
            if (android.os.SystemClock.elapsedRealtime() < bdRetryAtMs) return
            releaseBackdropTarget()
            bdDisabled = false
            Log.i(TAG, "backdrop 采样重试（第 ${bdFailCount + 1}/${bdMaxRetries} 次）")
        }
        val dw = displayWidth
        val dh = displayHeight
        if (dw <= 0 || dh <= 0) return

        // v2.4.47（用户要求）：**按时间节流 —— 30ms 一帧**（原来「每 3 帧」会随刷新率漂移：
        //   120Hz 屏 25ms、60Hz 屏 50ms；帧时间过短还会让 backdrop 层频繁重录）。
        //   糊掉的背景不需要更高新鲜度，30ms（≈33fps）足够。
        bdFrame++
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (nowMs - bdLastCaptureMs < bdCaptureIntervalMs) return
        bdLastCaptureMs = nowMs

        val tw = VideoBackdrop.TARGET_W
        // v2.6.18：上限 256 → 1280 —— TARGET_W 提高到 640 后，竖屏/极端比例的高度会超过旧上限
        //（截断会让 backdrop 变形 → 折射方向错乱）。1280 覆盖 640 宽下的所有常见长宽比。
        val th = (tw * dh / dw).coerceIn(1, 1280)
        try {
            ensureBackdropTarget(tw, th)
            if (bdFboId == 0) {
                bdFail("离屏 FBO 创建失败")
                return
            }
            // ⚠️ glReadPixels 只裁剪不缩放 —— 必须先用 glBlitFramebuffer 缩到小 FBO
            GLES20.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
            GLES20.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, bdFboId)
            GLES30.glBlitFramebuffer(
                0, 0, dw, dh,
                0, 0, tw, th,
                GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_LINEAR
            )
            val err1 = GLES20.glGetError()
            if (err1 != GLES20.GL_NO_ERROR) {
                bdFail("blit 失败 err=$err1")
                return
            }
            GLES20.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, bdFboId)
            val px = bdPixels!!
            // v2.6.20（P1）：优先走 PBO 异步回读；链路异常时本帧立即用同步路径兜底并永久回退
            var committed = false
            if (bdPboEnabled) {
                committed = captureBackdropPbo(tw, th, px)
                if (!bdPboEnabled) committed = captureBackdropSync(tw, th, px)
            } else {
                committed = captureBackdropSync(tw, th, px)
            }
            // v2.1.233：低频统计日志（约每 120 次采样一行 = 每 360 帧一次）。
            // 用途：确认「给玻璃/模糊效果喂画面」这条链路真的在跑 —— 高斯模糊
            // 「看不出效果」的根因就是这条链路没内容可糊（backdrop 只有一层纯色）。
            // 保留它是为了让下次排查能一眼分辨「没采样」与「采样了但糊得不对」。
            if (committed && bdFrame % 360 == 0) {
                // v2.4.53 诊断：顺带统计采样帧的平均亮度，用于区分「玻璃惨白」的两类根因——
                // 若平均RGB 接近 255 说明喂给玻璃的视频帧本身就是白的（需靠面板薄遮罩压住）；
                // 若平均RGB 偏暗但玻璃仍白，则问题在合成层而非视频帧。
                //
                // 🔴 v2.4.54：修「数组越界」—— px 是 IntArray(tw*th)，每个 Int 已经是一个
                //    打包好的像素（与 VideoBackdrop.commit() 直接喂给 Bitmap.setPixels 的格式一致）。
                //    ⚠️ v2.4.62 更正：这里原写「即 ARGB：R 在第 16~23 位…」是**错的** ——
                //    在补上上方 `swapRedBlueInPlace` 之前，px 实际是 GL 回读的 **ABGR**，
                //    所以当时打印出来的「平均RGB」把 B 当成了 R。补齐交换后，下面的
                //    `ushr 16 / ushr 8 / and 0xFF` 才真正对应 R / G / B。
                //    旧代码按「每像素 4 个数组元素」访问 px[bi*4]/[bi*4+1]/[bi*4+2]，而循环条件
                //    只写了 bi<n（n=tw*th，没有再除以 4）—— bi 超过约 n/4 时 bi*4+2 必然越界，
                //    每次执行这段代码都 100% 抛 ArrayIndexOutOfBoundsException，异常被下面的
                //    catch 吞掉后当成一次采样失败计入 bdFail()，连续 3 次就把整条采样链路
                //    永久停用。这段诊断代码本身从未成功打印过一行「平均RGB=」日志，
                //    也从未真正验证过「视频帧偏白」这个判断（本段注释上面那句话落空了）。
                //    包裹一层独立 try/catch：这段纯诊断代码出错不该拖垮采样本身。
                try {
                    var sr = 0L; var sg = 0L; var sb = 0L
                    val n = tw * th
                    var bi = 0
                    while (bi < n) {
                        val p = px[bi]
                        sr += (p ushr 16) and 0xFF
                        sg += (p ushr 8) and 0xFF
                        sb += p and 0xFF
                        bi++
                    }
                    val ar = (sr / n).toInt(); val ag = (sg / n).toInt(); val ab = (sb / n).toInt()
                    val whiteFlag = if (ar > 200 && ag > 200 && ab > 200) " ⚠️帧偏白" else ""
                    Log.d(TAG, "backdrop 采样正常: " + tw + "x" + th + " <- 屏幕 " + dw + "x" + dh + " (version=" + VideoBackdrop.version + ") 平均RGB=" + ar + "," + ag + "," + ab + whiteFlag)
                } catch (t: Throwable) {
                    Log.w(TAG, "backdrop 诊断统计出错（不影响采样本身）：${t.message}")
                }
            }
        } catch (t: Throwable) {
            bdFail("异常：${t.message}")
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
    }

    /** 采样开关变化时重建资源（尺寸变化由 ensureBackdropTarget 自行处理） */
    fun resetBackdropCapture() {
        bdDisabled = false
        bdFrame = 0
    }

    /**
     * 同步回读（兜底路径）：`glReadPixels` 直达 heap IntBuffer → 统一处理 → commit。
     * PBO 链路异常时 [captureBackdropIfNeeded] 会退到这里。
     */
    private fun captureBackdropSync(tw: Int, th: Int, px: IntArray): Boolean {
        val buf = bdBuffer!!
        buf.position(0)
        GLES20.glReadPixels(0, 0, tw, th, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) {
            bdFail("readPixels 失败 err=$err")
            return false
        }
        buf.position(0)
        buf.get(px)
        processAndCommitBackdrop(px, tw, th)
        return true
    }

    /**
     * v2.6.20（P1，用户选定）：**PBO 双缓冲异步回读**。
     *
     * 每次采样（40ms）做两件事：
     *   1. **收割上一帧**：`glClientWaitSync`（40ms 间隔下 GPU 早已完成，几乎必立即返回）
     *      → `glMapBufferRange` 拷出像素 → 走统一后处理 → commit；
     *   2. **提交本帧**：`glReadPixels(offset=0)` 写进另一个 PBO（异步，不阻塞管线）+ 挂 fence。
     *
     * 任何一步 GL 出错 / fence 8ms 内仍未就绪 → 关闭 [bdPboEnabled] 永久回退同步路径
     * （调用方本帧立即用 [captureBackdropSync] 兜底，采样不中断）。
     *
     * ⚠️ 代价：commit 的内容比同步路径旧一个提交间隔（40ms → 视觉滞后 ≈80ms）。
     * 折射带只有 2dp，环带内容滞后不可感知；本体又是全透明的，无影响。
     *
     * @return 是否 commit 了像素（首帧只提交、无上一帧可收割 → false 但不算失败）
     */
    private fun captureBackdropPbo(tw: Int, th: Int, px: IntArray): Boolean {
        var harvested = false
        val readSlot = 1 - bdPboIdx
        val fence = bdPboFence[readSlot]
        if (fence != 0L) {
            val status = GLES30.glClientWaitSync(fence, 0, 8_000_000L)
            GLES30.glDeleteSync(fence)
            bdPboFence[readSlot] = 0L
            if (status == GLES30.GL_ALREADY_SIGNALED || status == GLES30.GL_CONDITION_SATISFIED) {
                val need = tw * th * 4
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, bdPboIds[readSlot])
                val mapped = GLES30.glMapBufferRange(
                    GLES30.GL_PIXEL_PACK_BUFFER, 0, need, GLES30.GL_MAP_READ_BIT
                )
                if (mapped != null) {
                    val ib = (mapped as java.nio.ByteBuffer)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .asIntBuffer()
                    ib.position(0)
                    ib.get(px, 0, tw * th)
                    GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
                    harvested = true
                }
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
                if (GLES20.glGetError() != GLES20.GL_NO_ERROR) harvested = false
            } else {
                Log.w(TAG, "backdrop PBO fence 未就绪(status=$status) → 回退同步回读")
            }
        } else {
            // 首帧：没有上一帧可收割，只做提交
            harvested = false
        }
        if (!harvested && bdPboFence[readSlot] != 0L) {
            // 走到这说明收割失败且 fence 已删 —— 保守起见整体回退同步
            bdPboEnabled = false
            return false
        }
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
            bdPboEnabled = false
            return false
        }

        // 本帧异步提交到另一个槽
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, bdPboIds[bdPboIdx])
        GLES30.glReadPixels(0, 0, tw, th, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0)
        val err = GLES20.glGetError()
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        if (err != GLES20.GL_NO_ERROR) {
            Log.w(TAG, "backdrop PBO glReadPixels 失败 err=$err → 回退同步回读")
            bdPboEnabled = false
            return false
        }
        bdPboFence[bdPboIdx] = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        if (bdPboFence[bdPboIdx] == 0L) {
            bdPboEnabled = false
            return false
        }
        bdPboIdx = 1 - bdPboIdx

        if (harvested) processAndCommitBackdrop(px, tw, th)
        return harvested
    }

    /**
     * 回读像素的统一后处理：红蓝交换 → 垂直翻转 → 提交 [VideoBackdrop.commit]。
     * 同步与 PBO 两条路径共用（红蓝/翻转的完整机理说明见原内联版本的 v2.4.62 注释，
     * 已随代码移到此处）。
     */
    private fun processAndCommitBackdrop(px: IntArray, tw: Int, th: Int) {
        // 🔴 v2.4.62：**补红蓝交换** —— `glReadPixels` 按 R,G,B,A 字节序写入，IntBuffer（小端）
        //    逐元素取出后是 ABGR；而 `VideoBackdrop.commit` 的契约是 ARGB（直接 setPixels 进
        //    ARGB_8888 位图）。漏掉这步，玻璃背后的画面红蓝颠倒（高饱和画面明显偏蓝）。
        ImageScale.swapRedBlueInPlace(px, tw * th)
        // GL 的行序是自下而上，Bitmap 是自上而下 —— 必须翻转，否则背景是倒的
        val row = bdRow!!
        var y = 0
        while (y < th / 2) {
            val a = y * tw
            val b = (th - 1 - y) * tw
            System.arraycopy(px, a, row, 0, tw)
            System.arraycopy(px, b, px, a, tw)
            System.arraycopy(row, 0, px, b, tw)
            y++
        }
        VideoBackdrop.commit(px, tw, th)
    }

}
