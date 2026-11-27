package com.example.vr

import android.graphics.Bitmap
import android.opengl.GLES20
import android.util.Log
import com.example.vr.VRGLRenderer.Companion.TAG

/**
 * 人脸检测采样、PBO 异步回读与 MediaPipe/GPUPixel 后台流水线（从 VRGLRenderer.kt 拆出）。
 */
internal class VRGLFacePipeline(private val r: VRGLRenderer) {
    /**
     * v2.6.17 一次性探测：`Config.ARGB_8888` 的内存字节序是否等于 R,G,B,A。
     *
     * 依据：Android 的 N32 在小端机器上按 R,G,B,A 字节落盘，与 `glReadPixels` 的
     * `GL_RGBA` + `GL_UNSIGNED_BYTE` 输出一致 —— 因此「回读字节 → 位图」本可零转换。
     * 但这属平台实现细节，故用 1×1 位图实测：写入**纯红**的 RGBA 字节后读回像素，
     * 得到 `0xFFFF0000` 才算成立；若读到 `0xFF0000FF`（红蓝互换）则回退逐像素重排。
     */
    private fun probeFaceDirectCopy(): Boolean = with(r) {
        try {
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bmp.setHasAlpha(false)
        val probe = java.nio.ByteBuffer.allocateDirect(4)
            .order(java.nio.ByteOrder.nativeOrder())
        probe.put(0, 0xFF.toByte())  // R
        probe.put(1, 0x00.toByte())  // G
        probe.put(2, 0x00.toByte())  // B
        probe.put(3, 0xFF.toByte())  // A
        probe.position(0)
        bmp.copyPixelsFromBuffer(probe)
        val px = bmp.getPixel(0, 0)
        bmp.recycle()
        val ok = px == 0xFFFF0000.toInt()
        Log.i(
            TAG,
            "face direct-copy probe: pixel=0x${Integer.toHexString(px)} → " +
                if (ok) "direct (零转换)" else "convert (回退逐像素重排)"
        )
        ok
    } catch (e: Throwable) {
        Log.w(TAG, "face direct-copy probe failed", e)
        false
    }

    }

    /**
     * 视情况采一帧屏幕内容交给后台做人脸检测 / GPUPixel 处理（v2.0.156 重构，v2.0.177 收窄 ROI）。
     */
    fun maybeScheduleFaceSampling(): Unit = with(r) {
        // v2.6.22（P0-1）：渲染器已释放（线程池 shutdown）后不再回读 —— 白耗一次
        // glReadPixels 与一次 1MB 拷贝，且提交必然被拒。
        if (executorsClosed) return
        if (!isBeautyActive()) {
            faceFrameCounter = 0
            return
        }
        // v2.0.173：GPUPixel 路径**每帧**采样处理 —— 贴回间隔 > 1 帧时，显示内容在
        // 「美颜帧 / 原始帧」之间交替，肉眼可见闪烁；每帧处理则屏幕恒为「上一帧的美颜帧」，
        // 只有整体 1~2 帧延迟（不可感知）。v2.0.177 收窄到人脸 ROI 后**同样必须保持 1**。
        // GLSL 检测路径维持 8 帧节奏（它只消费人脸坐标，磨皮/美白是 shader 实时全帧）。
        val gpFullFrame = isGpuPixelActive()
        val interval = if (gpFullFrame) 1 else faceSampleInterval
        faceFrameCounter++
        if (faceFrameCounter < interval) return
        faceFrameCounter = 0

        // v2.0.173：后台仍在处理上一帧时跳过本次回读（白回读只制造 GC 压力），
        // 贴回间隔自适应 = max(1 帧, 实际处理耗时)
        //
        // ⚠️⚠️ v2.0.185：**这里就是「全屏闪烁」的源头**。
        // 跳过后本帧不会产生新的贴回，gpFbo 里只有**未经 GPUPixel 处理的原始渲染结果**；
        // 若照常上屏，画面便在「美颜帧 ↔ 原始帧」之间交替（处理耗时 > 帧时长时必然发生，
        // 模拟器/低端机尤其明显）。故此处**必须记账**，让上屏阶段改用保底帧复现上一张美颜画面。
        //
        // v2.0.186：判据由 isDetectingFace 改为 gpInFlight —— 检测与 GL 处理现在跑在两条
        // 线程上，必须两者都做完才算「不忙」（见 gpInFlight 的说明）。
        if (gpFullFrame && gpInFlight.get() > 0) {
            gpSkipReadThisFrame = true
            return
        }
        gpSkipReadThisFrame = false

        // v2.0.182：全覆盖模式下回读区就是**整个物理屏幕**（VR 分屏也整屏，不取单眼）——
        // 因为 GPUPixel 在此是屏幕空间后处理，且用户要求「VR 覆盖全屏」。
        // 注意：只在 GPUPixel 全帧路径（gpFullFrame）下才用整屏；GLSL 检测路径仍用单眼
        // （跨两眼取样会得到两张脸拼一起，MediaPipe 无法检测）。
        val vpW = if (isSplitScreenVR && !gpFullFrame) (displayWidth / 2) else displayWidth
        val w = vpW.coerceAtLeast(1)
        val h = displayHeight.coerceAtLeast(1)
        // v2.0.172：GPUPixel 引擎把美颜作用范围从「视口中央 512×512 人脸区域」扩到
        // **整个播放视口**（用户要求全屏生效）—— 回读整帧交 GPUPixel 全帧处理：
        // 磨皮/美白作用于全部画面，瘦脸/大眼由其内部 Mars-Face 检测定位人脸。
        // GLSL 引擎仍用中央 512×512 小图做人脸检测（它只消费人脸坐标，
        // 磨皮/美白在 shader 里本就是全屏皮肤色判定，无需大图）。
        //
        // ===== v2.0.177：GPUPixel 回读从「全屏」收窄到「人脸 ROI」 =====
        // 旧实现每帧回读 1920×1080×4 ≈ 8.29 MB，且 glReadPixels 是**同步阻塞**
        // （GL 线程停下等 GPU 回传），之后还要再拷一份 → ≈16 MB/帧内存流量。
        // 收窄到人脸 ROI 后典型 640×640 ≈ 1.6 MB（↓80%），远景小脸可降到 384×384（↓97%）。
        // 计算复用本项目已有的人脸坐标（uFaceCenter / uEyeDistance，均为**视口 UV** 空间）
        // 与已有基础设施（faceCropScaleX/Y + mapCrop*ToViewport + uploadPendingGpRegion 局部贴回）。
        //
        // ===== v2.0.182：恢复「全覆盖」，但用降采样把代价压回去 =====
        // 用户明确要求：**2D 覆盖全视频范围、3D/VR 覆盖全屏**。
        // 直接回读整帧会回到 v2.0.177 之前的 8.3 MB/帧 → 故按 gpReadDownscale(=2) 降采样回读，
        // 处理完再上采样贴回，内存流量 = 整帧 / 4（1920×1080 → 960×540 ≈ 2.1 MB）。
        // 磨皮/美白是低频效果，降采样几乎无感；瘦脸/大眼在 1/2 分辨率下人脸仍 ≥100 px，定位可靠。
        //
        // ⚠️ 3D/VR 模式下**不再**按人脸 ROI 收窄：VR 是屏幕空间后处理，收窄会出现
        //    「只有中间一块被美颜」的矩形分界（用户反馈的原始问题）。改为覆盖整个物理屏幕。
        val rw: Int
        val rh: Int
        val x0: Int
        val y0: Int
        if (gpFullFrame) {
            if (gpCoverageFull) {
                // v2.0.182（默认）：全覆盖 —— 整个视口（2D 即全视频；VR 即整屏，见上方 vpW 注释）
                //
                // ⚠️ v2.0.187 阶段2：这里的回读**只用于人脸检测**了 ——
                //    处理输入改走 texture 通道（SourceTexture 直接吃 gpFbo 的纹理，
                //    免去每帧 8.29 MB 的 glTexImage2D 上传）。因此回读尺寸按「检测够用」
                //    来定，用固定的 1/2 尺寸（数据量 ↓75%，Mars-Face 在 1/2 图上检测稳定）。
                val cov = if (gpuPixelHalfResBeauty) {
                    // 半分辨率处理（默认）：开销低，细节略软化
                    computeGpDetectRegion(w, h)
                } else {
                    // 全分辨率处理：画质最佳，开销更大（自适应档位决定降采样比）
                    computeGpCoverRegion(w, h)
                }
                rw = cov[2]; rh = cov[3]; x0 = cov[0]; y0 = cov[1]
                gpFullCoverageThisFrame = true
                // 检测小图覆盖整个视口 → UV 范围就是全域
                gpReadUvX0 = 0f; gpReadUvY0 = 0f; gpReadUvW = 1f; gpReadUvH = 1f
            } else {
                // 降级路径：v2.0.177 的「仅人脸 ROI」省电模式
                val roi = computeGpRoi(w, h)
                rw = roi[2]; rh = roi[3]; x0 = roi[0]; y0 = roi[1]
                gpFullCoverageThisFrame = false
                // 人脸 ROI：UV 范围 = 该矩形在视口里的实际位置与占比
                gpReadUvX0 = x0.toFloat() / w
                gpReadUvY0 = y0.toFloat() / h
                gpReadUvW = rw.toFloat() / w
                gpReadUvH = rh.toFloat() / h
            }
        } else {
            // ==========================================================================
            // 🔴 v2.6.22（用户要求「采样参考已修复的 AI 弹幕取帧链路」）：
            //    **从「视口中央 faceReadSize×faceReadSize 裁剪」改成「整帧（分屏取单眼）等比缩放」**。
            //
            // 旧实现的问题（实测体感：美颜"只有磨皮，瘦脸大眼没反应"）：
            //   · 采样区只有屏幕正中的 512×512 —— **竖屏视频**（1080×2400）里人脸通常在
            //     画面中上部，正中那一块经常**完全不含脸**；远景点的人脸、VR 里视线偏到
            //     边缘时的脸、多人场景里非中心的那张脸，同理全部采不到；
            //   · 每 8 帧采一次，一旦每次都落空 → faceMissStreak 持续增长 →
            //     形变/妆容永远是 0（磨皮/美白不依赖检测，所以看起来"只有磨皮生效"）。
            //
            // 弹幕链路（captureDanmuFrameIfNeeded）早已改成「整帧 + 长边等比缩放 + blit 真缩放」，
            // 所以任何位置的内容都能看到。这里对齐同一套做法：
            //   · 采样区 = 整个视口（分屏时 w 已是半屏宽，见上方 vpW 的推导）；
            //   · 由 blitFboToHalf 真缩放到长边 ≤ faceSampleSize（glReadPixels 只裁剪不缩放）；
            //   · faceCropScale 自然退化为 1.0（rw/w），mapCrop*ToViewport 变成恒等映射，
            //     坐标换算链路无需改动。
            // ==========================================================================
            rw = w
            rh = h
            x0 = 0
            y0 = 0
            gpFullCoverageThisFrame = false
            gpReadUvX0 = 0f
            gpReadUvY0 = 0f
            gpReadUvW = 1f
            gpReadUvH = 1f
        }

        // ===== v2.6.17：GLSL 检测路径改用「GPU 预缩放 + 零拷贝直拷」 =====
        // v2.6.22：采样**区域**已改为整帧（见上方 !gpFullFrame 分支），这里只负责把整帧
        // 由 GPU 缩到检测输入尺寸（长边 ≤ faceSampleSize，等比）：
        //   ① glReadPixels 是 GL 线程上的同步阻塞，数据量 1 MB → 约 0.25 MB（↓75%）；
        //   ② 后台线程不必再做 512→256 的 Skia 缩放（原先每 8 帧一次 Canvas.drawBitmap）。
        // blit 失败时保持原全尺寸回读，正确性不受影响（只是慢回旧路径）。
        var readW = rw
        var readH = rh
        var readFromHalfFbo = false
        if (!gpFullFrame) {
            // 等比缩到长边 ≤ faceSampleSize（复用 ImageScale.fitLongSide，已有单测覆盖）；
            // 采样区本就比检测尺寸还小时不放大（放大只会徒增回读字节，letterbox 会补回尺寸）。
            val fit = ImageScale.fitLongSide(rw, rh, faceSampleSize)
            val wantW = minOf(fit[0], rw)
            val wantH = minOf(fit[1], rh)
            if (wantW != rw || wantH != rh) {
                if (blitFboToHalf(gpReadUvX0, gpReadUvY0, gpReadUvW, gpReadUvH, wantW, wantH)) {
                    readW = wantW
                    readH = wantH
                    readFromHalfFbo = true
                }
            }
        }

        // v2.0.182：scale 的语义分两种（这是全覆盖与人脸 ROI 的关键差异）
        //   - 全覆盖：回读区（降采样）覆盖整个视口 → 裁剪图坐标 = 视口坐标，scale = 1
        //   - 人脸 ROI：回读区只覆盖视口一小块 → scale = rw/w，需放大回全域
        if (gpFullCoverageThisFrame) {
            faceCropScaleX = gpCoverScale()
            faceCropScaleY = gpCoverScale()
        } else {
            faceCropScaleX = rw.toFloat() / w
            faceCropScaleY = rh.toFloat() / h
        }
        // v2.0.160：记录回读区域（窗口坐标）—— GPUPixel 的处理结果要贴回同一区域
        lastSampleX0 = x0
        lastSampleY0 = y0
        // v2.0.182：全覆盖模式还要记住回读区尺寸，贴回时按同一区域 glTexSubImage2D
        gpLastReadX0 = x0
        gpLastReadY0 = y0
        gpLastReadW = rw
        gpLastReadH = rh

        // v2.0.187 阶段2：记录本帧要交给 GPUPixel 的**输入纹理**（= 当前 gpFbo）。
        // 后台线程用它走零拷贝输入通道，替代「回读的像素再上传」。
        // 仅在 texture 通道可用时才有意义；raw-data 路径会忽略它。
        if (gpFullFrame && !gpTextureFallbackRequested) {
            pendingInputTexId = gpFboTexId
            pendingInputTexW = gpFboW
            pendingInputTexH = gpFboH
        }

        try {
            // v2.6.17：按**实际回读尺寸**（readW×readH）算字节数 —— GLSL 路径下它已是
            // GPU 预缩放后的检测尺寸，比采样区 rw×rh 小得多。
            val need = readW * readH * 4

            // v2.0.183：降采样回读必须先「真正缩放」再读 —— glReadPixels 只裁剪不缩放。
            // 用 glBlitFramebuffer 把主 FBO 的 [gpReadUv*] 区域缩小渲染到降采样 FBO，
            // 再从降采样 FBO 读（此时读到的才是整屏缩略图，坐标与视口 UV 一一对应）。
            //
            // v2.0.185 修复：blit 失败时**不再**退回「左上角裁剪 + 拉伸铺满」的错误回退
            // （原实现只改了 FBO 绑定，却仍按 rw×rh 在左上角裁剪，随后被 needsScale 判定为
            // 需要缩放、把这一小块拉伸到全屏 → 该帧画面异常放大）。
            // 现改为：blit 失败即视为「本帧不回读」，交给保底帧显示上一张美颜画面 ——
            // 既不放大也不闪烁，下一帧会自动重试。
            val needsDownscale = gpFullCoverageThisFrame && (rw < w || rh < h)

            // ===== v2.0.186（P1-C）：先试 PBO 异步回读（消除 GL 线程硬阻塞） =====
            // 节奏：本帧「收割上一帧（非阻塞）→ blit → 提交本帧」，数据下一帧到位。
            var pboHandled = false
            if (gpAsyncReadback && gpPboEnabled && ensureGpPbo(need)) {
                // 回读尺寸变化（降采样档位切换 / 分辨率变化）→ 在途数据尺寸不匹配，作废
                if (gpPboActive && gpPboPendingBytes != need) discardGpPboPending()

                val tw0 = System.nanoTime()
                val collected = collectGpPbo(need)
                gpProfLogReadback(System.nanoTime() - tw0, gpPboActive)
                if (gpPboEnabled) {
                    if (gpPboActive) {
                        // 在途 fence 未 signal（GPU 还没拷完）→ 本帧没有新输入可投递，
                        // 交给保底帧复现上一张美颜画面（不阻塞、不闪烁）
                        gpSkipReadThisFrame = true
                        return
                    }
                    if (collected != null) {
                        synchronized(faceFrameLock) {
                            pendingFaceFrame = collected
                            pendingFaceFrameW = gpPboPendingW
                            pendingFaceFrameH = gpPboPendingH
                        }
                        triggerBackgroundFaceDetection()
                    }
                    // 本帧的降采样 blit 必须在提交回读之前完成
                    // （v2.6.17：GLSL 路径已在函数开头预缩放过，readFromHalfFbo 时跳过）
                    if (!readFromHalfFbo && needsDownscale && !blitFboToHalf(
                            gpReadUvX0, gpReadUvY0, gpReadUvW, gpReadUvH, rw, rh
                        )
                    ) {
                        gpSkipReadThisFrame = true
                        return
                    }
                    pboHandled = submitGpPboRead(
                        readFromHalfFbo || needsDownscale,
                        if (readFromHalfFbo) 0 else x0,
                        if (readFromHalfFbo) 0 else y0,
                        readW, readH, need
                    )
                    if (!pboHandled) {
                        Log.w(TAG, "PBO submit failed, fallback to sync readback")
                        gpPboEnabled = false
                        releaseGpPbo()
                    }
                }
            }

            if (!pboHandled) {
                // ===== 同步回读（原路径 / PBO 不可用或失败时的回退）=====
                val tr0 = System.nanoTime()
                val buf = faceReadBuffer?.takeIf { it.capacity() >= need }
                    ?: java.nio.ByteBuffer.allocateDirect(need)
                        .order(java.nio.ByteOrder.nativeOrder())
                        .also { faceReadBuffer = it }
                buf.clear()

                if (!readFromHalfFbo && needsDownscale && !blitFboToHalf(
                        gpReadUvX0, gpReadUvY0, gpReadUvW, gpReadUvH, rw, rh
                    )
                ) {
                    gpSkipReadThisFrame = true
                    return
                }
                // 降采样成功 → 从降采样 FBO 全图读；否则（本就 1:1）从 gpFbo 的 (x0,y0) 读同尺寸区域
                val srcIsHalf = readFromHalfFbo || needsDownscale
                GLES20.glBindFramebuffer(
                    GLES20.GL_FRAMEBUFFER,
                    if (srcIsHalf) gpHalfFboId else gpFboId
                )
                GLES20.glReadPixels(
                    if (srcIsHalf) 0 else x0,
                    if (srcIsHalf) 0 else y0,
                    readW, readH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
                )
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                buf.rewind()

                // 从池里取一块装这一帧（所有权转移给后台线程，用完后归还）
                val recycled = synchronized(faceFrameLock) {
                    faceFramePool?.takeIf { it.size >= need }?.also { faceFramePool = null }
                }
                val frame = recycled ?: ByteArray(need)
                buf.get(frame, 0, need)
                gpProfLogReadback(System.nanoTime() - tr0, false)

                synchronized(faceFrameLock) {
                    pendingFaceFrame = frame
                    pendingFaceFrameW = readW
                    pendingFaceFrameH = readH
                }
                triggerBackgroundFaceDetection()
            }
        } catch (e: Exception) {
            // Ignore transient surface resizing safety errors
        }
    }

    private fun triggerBackgroundFaceDetection(): Unit = with(r) {
        // v2.0.186：两条路径的「忙」判据不同
        //   - GPUPixel：gpInFlight（检测 + GL 处理跑在两条线程，都做完才算空闲）
        //   - MediaPipe：isDetectingFace（单线程，原语义）
        if (isGpuPixelActive()) {
            if (gpInFlight.get() > 0) return
        } else if (isDetectingFace.get()) {
            return
        }

        var frame: ByteArray? = null
        var fw = 0
        var fh = 0
        synchronized(faceFrameLock) {
            frame = pendingFaceFrame
            pendingFaceFrame = null
            fw = pendingFaceFrameW
            fh = pendingFaceFrameH
        }
        val rgbaData = frame ?: return
        if (fw <= 0 || fh <= 0) return

        // ===== v2.0.186（P0-B）：GPUPixel 走「检测 ∥ GL 处理」双线程流水线 =====
        // 一次回读的像素同时作为两套资源的输入（共享的是像素，不是检测结果）：
        //   · gpDetectExecutor → Mars-Face（纯 CPU）
        //   · faceExecutor     → GPUPixel GL 链（独立 EGL context）
        // 两者读同一块 rgbaData（只读），由 gpInFlight 保证读完前不被复用。
        if (isGpuPixelActive()) {
            val stride = fw * 4
            // 检测降频：landmark 是「形变」的唯一输入，滞后 ≤ gpDetectInterval 帧
            // （叠加回读/贴回本身的 1~2 帧延迟，同量级，视觉不可感知）；
            // 磨皮/美白完全不依赖检测，降频对它们零影响。
            val interval = gpDetectInterval.coerceAtLeast(1)
            // v2.4.34（#5）：无脸场景也要降频。此前 `gpCachedLandmarks == null` 一旦成立
            // （连续 3 次未检出脸后被清空），`== null` 分支会让检测**每帧**都跑 →
            // 风景/无脸画面 CPU 满载、VR 设备发热明显。改为：无脸时放大检测间隔（退避），
            // 有脸时仍按 gpDetectInterval 正常降频。首帧 gpFrameTick=0 仍会立即检测一次。
            val noFaceInterval = 8
            val detectInterval = if (gpCachedLandmarks == null) noFaceInterval else interval
            val doDetect = gpFrameTick % detectInterval == 0
            gpFrameTick++

            gpInFlight.addAndGet(if (doDetect) 2 else 1)

            if (doDetect) {
                // v2.6.22（P0-1）：提交失败要回滚刚预支的那 1 个在途计数，
                // 否则 gpInFlight 永不归零 → 「忙」判据恒真 → 检测永久停摆
                submitSafely(
                    gpDetectExecutor, "GPUPixel 人脸检测",
                    rollback = { finishGpTask(null) },
                ) {
                    try {
                        // v2.0.186：分段计时 —— 这一段是纯 Mars-Face 检测（含首次模型加载）
                        val td0 = System.nanoTime()
                        val lm = GpuPixelBeauty.getOrCreatePipeline().detect(rgbaData, fw, fh, stride)
                        gpProfLogDetect(System.nanoTime() - td0)
                        // v2.6.23：低频诊断 —— Mars 一条日志都不打，出问题时只能靠这行判断
                        // 「是没检出脸」还是「检出了但形变没下发」。
                        gpDiagCount++
                        if (gpDiagCount >= 60) {
                            gpDiagCount = 0
                            val n = (lm?.size ?: 0) / GpuPixelBeauty.FLOATS_PER_FACE
                            Log.i(TAG, "GPUPixel 人脸检测：$n 张（输入 ${fw}x$fh）")
                        }
                        if (lm != null && lm.isNotEmpty()) {
                            gpDetectMissStreak = 0
                            // v2.6.23：缓存的是**语义锚点**（每脸 6 个点），不是 Mars 原始 111 点。
                            // FaceReshapeFilter 的 shader 只认这 6 个解剖锚点，绕开了
                            // 「GPUPixel 106 点索引表 vs Mars 111 点」的点序不匹配问题。
                            gpCachedLandmarks = GpuPixelBeauty.anchorsFromLandmarks(lm)
                        } else {
                            gpDetectMissStreak++
                            // v2.0.186 防抖：连续 3 次拿不到脸才清空缓存。
                            // 单次失败（遮挡/侧脸/运动模糊）不再瞬间把美型归零 ——
                            // 这既是稳定性提升，也避免了「形变忽有忽无」的抖动。
                            if (gpDetectMissStreak >= 3) gpCachedLandmarks = null
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "GPUPixel background detect failed", e)
                    } finally {
                        finishGpTask(null)
                    }
                }
            }

            // v2.6.22（P0-1）：同检测侧 —— 提交失败回滚在途计数（此时计数已是 1 或 2）
            submitSafely(
                faceExecutor, "GPUPixel 图像处理",
                rollback = { finishGpTask(rgbaData) },
            ) {
                try {
                    // v2.0.185：实测处理耗时供自适应降采样使用。
                    // v2.0.186：这里只剩 **GL 段**（检测已移到 gpDetectExecutor），
                    // 故 EMA 反映的是纯 GPU 开销，降采样决策比之前更准确。
                    val t0 = System.nanoTime()
                    val pipeline = GpuPixelBeauty.getOrCreatePipeline()

                    // v2.0.187：GL 线程判定 texture 通道不可用时，在这里把链切回 raw-data。
                    // 必须在这里（faceExecutor 上）做 —— 它持有 GPUPixel context 的线程语义，
                    // 且此刻没有在途处理（gpInFlight 保证了串行）。
                    if (gpTextureFallbackRequested && pipeline.isTextureMode()) {
                        pipeline.fallbackToRawDataSink()
                    }

                    val useTexChannel =
                        GpuPixelBeauty.useTextureSink && !gpTextureFallbackRequested
                    if (useTexChannel) {
                        // ---- 全零拷贝通道 ----
                        // 输入：直接吃 gpFbo 的纹理（免去每帧 8.29 MB 的 glTexImage2D 上传）
                        // 输出：SinkTexture 暴露的纹理 id（免去 glReadPixels 回读 + JNI 拷贝）
                        val inTex = pendingInputTexId
                        val ok = if (inTex != 0) {
                            pipeline.processFrameFromTexture(
                                inTex, pendingInputTexW, pendingInputTexH,
                                beautyLevel, beautyWhitening, beautyFaceSlimming, beautyBigEyes,
                                gpCachedLandmarks
                            )
                        } else {
                            // 罕见：本帧没有可用的输入纹理（启动瞬间 / 尺寸未就绪）
                            // → 退回 CPU 上传路径，保证仍有结果产出
                            pipeline.processFrameTexture(
                                rgbaData, fw, fh, stride,
                                beautyLevel, beautyWhitening, beautyFaceSlimming, beautyBigEyes,
                                gpCachedLandmarks
                            )
                        }
                        if (ok) {
                            gpResultTexIdPending = pipeline.resultTextureId
                        }
                    } else {
                        // ---- raw-data 通道（回退）：CPU 回读字节 ----
                        val out = pipeline.processFrame(
                            rgbaData, fw, fh, stride,
                            beautyLevel, beautyWhitening, beautyFaceSlimming, beautyBigEyes,
                            gpCachedLandmarks
                        )
                        if (out != null) {
                            // v2.0.186（P0-A）：直接移交所有权，**不再 arraycopy**。
                            // 依据：jni_sink_raw_data.cc nativeGetRgbaBuffer 每次都
                            // env->NewByteArray(size) + SetByteArrayRegion 拷进新数组，
                            // 故 out 是本次调用独有的新数组（native 侧 rgba_buffer_ 的复用
                            // 被 JNI 边界隔离），GL 线程下一帧消费它不存在竞争。
                            // v2.4.34（#8）：数据与尺寸打包成同一对象原子写入，
                            // 避免分辨率变化时「旧 bytes 配新 W/H」的尺寸错配
                            gpRegionPending.set(VRGLRenderer.GpRegion(out, fw, fh))
                        }
                    }

                    val glNanos = System.nanoTime() - t0
                    updateGpAdaptiveDownscale(glNanos)
                    gpProfLogGpu(glNanos)
                } catch (e: Throwable) {
                    Log.e(TAG, "GPUPixel background process failed", e)
                } finally {
                    finishGpTask(rgbaData)
                }
            }
            return
        }

        // ===== MediaPipe（GLSL 引擎）路径：单线程，原逻辑 =====
        isDetectingFace.set(true)
        // v2.6.22（P0-1）：提交被拒时必须把标志与帧缓冲都还回去 ——
        // 否则 isDetectingFace 恒 true，「忙」判据永真 → 检测永久停摆
        submitSafely(
            faceExecutor, "MediaPipe 人脸检测",
            rollback = {
                synchronized(faceFrameLock) { faceFramePool = rgbaData }
                isDetectingFace.set(false)
            },
        ) {
            // v2.6.17：前置段（回读字节 → Bitmap）起点
            val facePrepStartNs = System.nanoTime()
            try {
                // ===== v2.6.17：回读数据**零转换**直拷进 Bitmap =====
                // 旧实现逐像素做 RGBA→ARGB 重排（512×512 = 262144 次字节读取 + 移位），
                // 再 setPixels 二次遍历（又一次 262144 次写）—— 检测前置的纯 CPU 段里最贵的一环。
                // 事实依据：`Bitmap.Config.ARGB_8888` 的**内存字节序就是 R,G,B,A**
                // （小端 N32 == RGBA8888），与 `glReadPixels(GL_RGBA, GL_UNSIGNED_BYTE)` 的
                // 输出完全一致 → `copyPixelsFromBuffer` 一次 memcpy 即可，循环整个消失。
                // 首次使用前用 1×1 位图探测一次（见 probeFaceDirectCopy），探测失败自动回退旧循环。
                if (faceDirectCopy == 0) faceDirectCopy = if (probeFaceDirectCopy()) 1 else 2
                if (faceBmp == null || faceBmp!!.width != fw || faceBmp!!.height != fh) {
                    faceBmp?.recycle()
                    faceBmp = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
                    // glReadPixels 的 alpha 通道不可靠（未开混合时常为 0）→ 按不透明处理。
                    // 否则直拷出来的位图全透明，drawBitmap 后 MediaPipe 看到的是一张全黑图。
                    faceBmp!!.setHasAlpha(false)
                }
                val srcBmp = faceBmp!!
                if (faceDirectCopy == 1) {
                    srcBmp.copyPixelsFromBuffer(
                        java.nio.ByteBuffer.wrap(rgbaData, 0, fw * fh * 4)
                            .order(java.nio.ByteOrder.nativeOrder())
                    )
                } else {
                    // ===== 回退路径（探测失败）：逐像素重排 + setPixels =====
                    val area = fw * fh
                    if (faceArgBuffer == null || faceArgBuffer!!.size < area) {
                        faceArgBuffer = IntArray(area)
                    }
                    val argb = faceArgBuffer!!
                    for (i in 0 until area) {
                        val r = rgbaData[i * 4].toInt() and 0xff
                        val g = rgbaData[i * 4 + 1].toInt() and 0xff
                        val b = rgbaData[i * 4 + 2].toInt() and 0xff
                        argb[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    srcBmp.setPixels(argb, 0, fw, 0, 0, fw, fh)
                }

                // ===== v2.6.23 🔴 修复「美颜完全没效果」：送检前必须把图**上下翻正** =====
                // `glReadPixels` 的行序是自下而上（GL 惯例），直接塞进 Bitmap 后整幅图是**倒立**的。
                // 此前这条链路一直没翻 —— MediaPipe 的 BlazeFace 对 180° 倒置人脸检出率≈0，
                // 于是每次采样都"未检出" → faceMissStreak 一路涨到 3 → uFaceCount/uFaceDetected=0
                // → 瘦脸/大眼/妆容/口红恒为 0，只剩不依赖检测的磨皮与美白。
                // 用户体感就是「没看出美颜效果」。实测（MuMu + 5 人同框测试视频）：送检图确认倒立。
                //
                // 翻转放在这唯一一次绘制里（canvas.scale(1, -1)），零额外内存、零额外拷贝。
                // 代价：合并了原来的「方形直通」分支 —— 现在方形也要画一次，可忽略（512×512 一次 blit）。
                if (faceScaledBmp == null) {
                    faceScaledBmp = Bitmap.createBitmap(faceSampleSize, faceSampleSize, Bitmap.Config.ARGB_8888)
                }
                val canvasBmp = faceScaledBmp!!
                val scaledSizeF = faceSampleSize.toFloat()
                val fitScale = minOf(scaledSizeF / fw, scaledSizeF / fh)
                val drawW = (fw * fitScale).toInt().coerceAtLeast(1)
                val drawH = (fh * fitScale).toInt().coerceAtLeast(1)
                val lbOffX = (faceSampleSize - drawW) / 2
                val lbOffY = (faceSampleSize - drawH) / 2
                // 黑边区域先擦除，否则复用位图会残留上一帧内容
                canvasBmp.eraseColor(android.graphics.Color.BLACK)
                run {
                    val canvas = android.graphics.Canvas(canvasBmp)
                    canvas.save()
                    // 垂直翻转：把目标矩形的原点移到内容区**底边**，再按 y 轴 -1 缩放
                    canvas.translate(0f, (lbOffY + drawH).toFloat())
                    canvas.scale(1f, -1f)
                    canvas.drawBitmap(
                        srcBmp,
                        android.graphics.Rect(0, 0, fw, fh),
                        android.graphics.Rect(lbOffX, 0, lbOffX + drawW, drawH),
                        null
                    )
                    canvas.restore()
                }
                val scaledBmp: Bitmap = canvasBmp
                // MediaPipe 返回的是「整张 512×512 画布（含黑边）」的归一化坐标，
                // 须先换算回内容子矩形再套 mapCrop*ToViewport 得到视口 UV。
                val lbX0: Float = lbOffX / scaledSizeF
                val lbY0: Float = lbOffY / scaledSizeF
                val lbW: Float = drawW / scaledSizeF
                val lbH: Float = drawH / scaledSizeF
                val mpX: (Float) -> Float = { px -> mapCropXToViewport((px - lbX0) / lbW) }
                // ⚠️ 图已翻正 → MediaPipe 给的 y 是「自上而下」；而 mapCropYToViewport 的公式
                // 是按「倒立图（自下而上）」推导的（它内部自带一次 0.5-(cy-0.5)*scaleY 的翻转）。
                // 这里先换回内容内、自下而上的等价坐标，公式本身不动 —— 改动面最小，也保证
                // 与历史映射行为完全一致。
                val mpY: (Float) -> Float = { py -> mapCropYToViewport(1f - (py - lbY0) / lbH) }
                val mpLenX: (Float) -> Float = { len -> mapCropLenToViewport(len / lbW) }

                // Track face landmarks using Google MediaPipe (with legacy fallback)
                val manager = mediaPipeManager
                val faceDetectStartNs = System.nanoTime()
                faceProfPrepNanos += faceDetectStartNs - facePrepStartNs
                if (manager != null) {
                    // v2.6.22（多脸）：一次拿回最多 3 张脸（按两眼距离降序，第 0 张是主脸）
                    val faces = manager.detectFaces(scaledBmp)
                    faceProfDetectNanos += System.nanoTime() - faceDetectStartNs
                    if (faces.isNotEmpty()) {
                        // v2.0.156：MediaPipe 给的是「采样裁剪图」空间的坐标，必须换算成视口 UV
                        // 再喂给 shader —— 否则人脸一偏离画面中心，妆容与变形就整体错位。
                        faceMissStreak = 0
                        faceDetectedUniform = 1

                        val n = minOf(faces.size, MediaPipeFaceManager.MAX_FACES)
                        faceCountUniform = n
                        // v2.6.23：按检出脸数动态降频 —— landmark 回归是逐脸做的，
                        // 3 张脸的成本约是 1 张的 2 倍多（实测 100ms+ vs 40~60ms）。
                        faceSampleInterval = when {
                            n >= 3 -> 12
                            n == 2 -> 10
                            else -> 8
                        }
                        // v2.6.22（多脸）：低频诊断 —— 每 60 次成功检测打一行，
                        // 用来确认「到底检出了几张脸」（多脸效果的唯一可观测指标）。
                        faceDiagCount++
                        if (faceDiagCount >= 60) {
                            faceDiagCount = 0
                            Log.i(
                                TAG,
                                "多脸检测：$n 张（主脸 eyeDist=${"%.4f".format(eyeDistancesArr[0])}，" +
                                    "精细点位=${if (faces[0].hasDetailedLandmarks) "有" else "无"}）"
                            )
                        }
                        for (i in 0 until n) {
                            val f = faces[i]
                            val o = i * 2
                            // 每张脸各自做低通（0.75/0.25），抑制检测抖动。
                            // ⚠️ 跨帧是按**索引**对应的 —— 返回顺序已按面积降序稳定化，
                            //   但两人大小接近时仍可能交换，表现为两张脸的妆容短暂互换位置。
                            //   这是「无 ID 跟踪」的固有局限（换检测器也一样），实测可接受。
                            faceCentersXY[o] = faceCentersXY[o] * 0.75f + mpX(f.centerX) * 0.25f
                            faceCentersXY[o + 1] = faceCentersXY[o + 1] * 0.75f + mpY(f.centerY) * 0.25f
                            eyeDistancesArr[i] = eyeDistancesArr[i] * 0.75f + mpLenX(f.eyeDistance) * 0.25f

                            if (f.hasDetailedLandmarks) {
                                hasDetailedsArr[i] = 1
                                eyeLeftsXY[o] = eyeLeftsXY[o] * 0.75f + mpX(f.eyeLeftX) * 0.25f
                                eyeLeftsXY[o + 1] = eyeLeftsXY[o + 1] * 0.75f + mpY(f.eyeLeftY) * 0.25f
                                eyeRightsXY[o] = eyeRightsXY[o] * 0.75f + mpX(f.eyeRightX) * 0.25f
                                eyeRightsXY[o + 1] = eyeRightsXY[o + 1] * 0.75f + mpY(f.eyeRightY) * 0.25f
                                mouthPossXY[o] = mouthPossXY[o] * 0.75f + mpX(f.mouthX) * 0.25f
                                mouthPossXY[o + 1] = mouthPossXY[o + 1] * 0.75f + mpY(f.mouthY) * 0.25f
                                chinsXY[o] = chinsXY[o] * 0.75f + mpX(f.chinX) * 0.25f
                                chinsXY[o + 1] = chinsXY[o + 1] * 0.75f + mpY(f.chinY) * 0.25f
                                mouthHalfWsArr[i] = mouthHalfWsArr[i] * 0.75f +
                                    mpLenX(f.mouthHalfWidth) * 0.25f
                                mouthHalfHsArr[i] = mouthHalfHsArr[i] * 0.75f +
                                    ((f.mouthHalfHeight / lbH) * faceCropScaleY) * 0.25f
                                // v2.6.23：图已翻正 → 嘴角连线的 y 方向与「倒立图」相反，
                                // 角度取负还原（shader 里用的是 -uMouthAngles[fi]，符号错会让口红椭圆拧反）
                                mouthAnglesArr[i] = -f.mouthAngle
                            } else {
                                hasDetailedsArr[i] = 0
                            }
                        }

                        // ---- 主脸镜像：GPUPixel 的 ROI / 日志仍按「一张脸」工作 ----
                        val main = faces[0]
                        faceCenterXUniform = faceCentersXY[0]
                        faceCenterYUniform = faceCentersXY[1]
                        eyeDistanceUniform = eyeDistancesArr[0]
                        if (main.hasDetailedLandmarks) {
                            hasDetailedLandmarks = 1
                            eyeLeftXUniform = eyeLeftsXY[0]
                            eyeLeftYUniform = eyeLeftsXY[1]
                            eyeRightXUniform = eyeRightsXY[0]
                            eyeRightYUniform = eyeRightsXY[1]
                            mouthXUniform = mouthPossXY[0]
                            mouthYUniform = mouthPossXY[1]
                            chinXUniform = chinsXY[0]
                            chinYUniform = chinsXY[1]
                            mouthHalfWidthUniform = mouthHalfWsArr[0]
                            mouthHalfHeightUniform = mouthHalfHsArr[0]
                            mouthAngleUniform = mouthAnglesArr[0]
                        } else {
                            hasDetailedLandmarks = 0
                        }
                    } else {
                        // v2.0.173：防抖 —— 检测按 8 帧节奏进行，单次失败（遮挡/侧脸/运动模糊）
                        // 就硬切 0 会让妆容与变形在「出现/消失」间高频跳变（表现为闪烁）。
                        // 连续 3 次失败（≈半秒）才判定真正丢失；期间保持最后位置不漂移。
                        faceMissStreak++
                        if (faceMissStreak >= 3) {
                            // v2.6.22（整帧采样改造的配套诊断）：只在"刚判定丢失"那一次打一行，
                            // 便于真机上区分「画面里真的没有人脸」与「整帧缩放后脸太小、检出率不够」
                            // —— 后者把 faceSampleSize 调大（512 → 640/768）即可，不必回退中央块裁剪。
                            if (faceMissStreak == 3) {
                                Log.w(
                                    TAG,
                                    "人脸检测连续 3 次未检出：输入 ${fw}x$fh（整帧等比缩放，长边上限 $faceSampleSize）；" +
                                        "若画面确实有人脸，考虑调大 faceSampleSize"
                                )
                            }
                            faceDetectedUniform = 0
                            hasDetailedLandmarks = 0
                            // v2.6.22（多脸）：张数归零 —— shader 的逐脸循环第一轮就 break，
                            // 否则会拿「最后一张脸的残留坐标」继续画（表现为妆容停在原地）。
                            faceCountUniform = 0
                            // v2.6.23：丢失后回到 8 帧（尽快重新抓到脸），不要停在多脸时的长间隔
                            faceSampleInterval = 8
                            // Decay smoothly back to default screen center coordinates
                            faceCenterXUniform = faceCenterXUniform * 0.92f + 0.50f * 0.08f
                            faceCenterYUniform = faceCenterYUniform * 0.92f + 0.45f * 0.08f
                            eyeDistanceUniform = eyeDistanceUniform * 0.92f + 0.14f * 0.08f
                        }
                    }
                }
                // v84：复用缓冲，不 recycle（仅尺寸变化时才重建）
            } catch (e: Throwable) {
                Log.e(TAG, "Background Google MediaPipe face tracking failed", e)
            } finally {
                // v2.6.17：分段统计（每 60 次一行）。prep = 回读字节 → Bitmap 的纯 CPU 前置，
                // detect = MediaPipe 推理。改动的目标就是让 prep 相对 detect 可忽略。
                faceProfCount++
                if (faceProfCount >= 60) {
                    val n = faceProfCount
                    Log.i(
                        TAG,
                        "face profile(${n}x): prep=${faceProfPrepNanos / n / 1_000_000} ms, " +
                            "detect=${faceProfDetectNanos / n / 1_000_000} ms, " +
                            "read=${fw}x${fh}, copy=${if (faceDirectCopy == 1) "direct" else "convert"}"
                    )
                    faceProfCount = 0
                    faceProfPrepNanos = 0
                    faceProfDetectNanos = 0
                }
                // v2.0.156：把这一帧的数组还给池，下一次采样即可复用（省掉每 8 帧 1MB 的分配）
                synchronized(faceFrameLock) { faceFramePool = rgbaData }
                isDetectingFace.set(false)
            }
        }
    }

    /**
     * v2.6.22（美颜审查 P0-1）：**带保护的线程池提交**。
     *
     * 之前所有提交点都是裸 `executor.execute {}`：
     *  - [release] 里 `shutdownNow` 之后若还有提交 → `RejectedExecutionException`
     *    冒到 GL 线程的 `onDrawFrame`；
     *  - 更隐蔽的是**计数泄漏** —— 调用方在提交**前**已经把 `gpInFlight` 加了 1
     *    （MediaPipe 路径是 `isDetectingFace.set(true)`），任务没提交成功就永远回不来，
     *    于是「忙」判据恒真，**本渲染器生命周期内人脸检测彻底停摆**。
     *
     * @param rollback 提交失败时的回滚动作（把提交前预支的计数/标志还回去、归还帧缓冲）
     * @return 是否真的提交成功
     */
    internal fun submitSafely(
        executor: java.util.concurrent.ExecutorService,
        tag: String,
        rollback: () -> Unit,
        task: () -> Unit,
    ): Boolean = with(r) {
        if (executorsClosed) {
            runCatching { rollback() }
            return false
        }
        return try {
            executor.execute { task() }
            true
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            Log.w(TAG, "$tag 提交被拒绝（线程池已关闭），回滚计数", e)
            runCatching { rollback() }
            false
        } catch (e: Throwable) {
            Log.e(TAG, "$tag 提交失败，回滚计数", e)
            runCatching { rollback() }
            false
        }
    }

    /**
     * v2.0.186（P0-B）：GPUPixel 双线程流水线的任务收尾。
     *
     * 计数归零意味着**所有读取者都已读完**本帧像素，此时才能：
     *  ① 把帧归还池，供下一帧回读复用；
     *  ② 让 GL 线程重新开始回读（[maybeScheduleFaceSampling] 的节流判据）。
     *
     * 传入 `null` 的任务（检测）不归还帧 —— 帧只由「处理」侧归还，
     * 避免两个任务同时写池。
     */
    private fun finishGpTask(rgbaData: ByteArray?): Unit = with(r) {
        if (gpInFlight.decrementAndGet() <= 0 && rgbaData != null) {
            synchronized(faceFrameLock) { faceFramePool = rgbaData }
        }
    }
}
