package com.example.vr

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import java.nio.FloatBuffer

/**
 * 画质增强管线（MEMC 运动补偿插帧 + FSR 超分 EASU/RCAS），从 VRGLRenderer 拆出以保持渲染器职责单一。
 */
internal class VRGLVideoEnhance(
    private val halfQuad: FloatBuffer,
    private val buildQuadProgram: (String) -> Int,
) {
    companion object {
        private const val TAG = "VRGLVideoEnhance"
    }

    @Volatile var memcEnabled = false
    @Volatile var memcTargetFps = VideoEnhanceRules.MEMC_TARGET_FPS_DEFAULT
    @Volatile var fsrEnabled = false
    @Volatile var fsrTargetWidth = 0
    @Volatile var fsrTargetHeight = 0

    @Volatile var isVideoActive = false
    @Volatile var videoTextureId = 0
    @Volatile var imageTextureId = 0
    @Volatile var videoWidth = 0
    @Volatile var videoHeight = 0
    @Volatile var imageWidth = 0
    @Volatile var imageHeight = 0
    @Volatile var displayWidth = 1080
    @Volatile var displayHeight = 1920

    // ===== v2.0.206：MEMC / FSR 的 program 与 FBO 资源 =====
    // FSR：EASU（两级输入变体）→ RCAS，ping-pong 两张目标分辨率纹理
    private var pFsrEasuOes = 0      // EASU，输入为 ExternalOES（视频源）
    private var pFsrEasu2d = 0       // EASU，输入为普通 2D（图片源，或 MEMC 的输出）
    private var pFsrRcas = 0
    private var fsrTexA = 0
    private var fsrFboA = 0
    private var fsrTexB = 0
    private var fsrFboB = 0
    private var fsrW = 0
    private var fsrH = 0

    // MEMC：拷贝 → 双帧持有 → ME → MC，另加一张 1x1 的场景切换检测
    private var pMemcCopyOes = 0
    private var pMemcCopy2d = 0
    private var pMemcMe = 0
    private var pMemcMc = 0
    private var pMemcDiff = 0
    private var memcPrevTex = 0
    private var memcPrevFbo = 0
    private var memcCurrTex = 0
    private var memcCurrFbo = 0
    private var memcOutTex = 0
    private var memcOutFbo = 0
    private var memcMvFTex = 0
    private var memcMvFFbo = 0
    private var memcMvBTex = 0
    private var memcMvBFbo = 0
    private var memcDiffTex = 0
    private var memcDiffFbo = 0
    private var memcW = 0
    private var memcH = 0
    private var memcMvW = 0
    private var memcMvH = 0

    /** 是否已攒够「上一帧」。首帧没有 prev 可插，必须先直通一次 */
    private var memcHasPrev = false

    /** 记录源帧到达时刻（uptimeMillis），用于按真实时间算插帧相位 */
    private var memcPrevTimeMs = 0L
    private var memcCurrTimeMs = 0L

    /** 场景切换标记：为 true 时本帧不插帧（直接输出 curr），由 1x1 帧差检测回读得到 */
    private var memcSceneCut = false

    /** 本帧实际使用的插帧相位，仅用于日志诊断 */
    private var memcLastPhase = 0f

    /** MEMC 目标帧率的节流计时（毫秒） */
    private var memcThrottleMs = 0L

    // ===== v2.0.206 诊断：低频日志，用于实机验证增强链是否**真的在跑** =====
    // 设计原则：状态变化时打一行 + 每 120 帧一行统计。
    // 这样既能证明「开关真的传到了渲染层、ME/MC 真的在算」，又不会刷屏淹没其他日志。
    private var lastEnhanceStateKey = ""
    private var memcFrameCount = 0L
    private var fsrFrameCount = 0L
    private var memcSceneCutCount = 0L

    /** 相位落在 (0.05, 0.95) 的帧数 —— 「插帧真的发生了」的直接证据 */
    private var memcInterpCount = 0L

    /** 上一次诊断打印时用的增强纹理 id，变化时才重新打印（避免刷屏） */
    private var fsrSrcW = 0
    private var fsrSrcH = 0


    /** 缓存 GL_MAX_TEXTURE_SIZE（进程内不变，查一次即可） */
    private var cachedMaxTexSize = 0

    /** 上一帧输出的增强纹理，用于「目标帧率节流」时复用 */
    private var lastEnhanceTexId = 0

    /** 上一次输出增强结果的时刻，配合 memcTargetFps 做节流 */
    private var memcLastOutputMs = 0L

    private val memcBlockSize = 16f
    private val memcSearchRadius = 16f
    private val memcSadThreshold = 0.55f
    private val memcOcclusionThresh = 0.12f
    private val memcSceneCutDiff = 0.12f
    private val fsrRcasSharpness = 0.2f

    /** 是否请求了画质增强（任一开关打开即可能生效） */
    fun isEnhanceRequested(): Boolean = memcEnabled || fsrEnabled

    /**
     * 查询 GL_MAX_TEXTURE_SIZE。
     *
     * ⚠️ 必须查：自定义规则里用户可以把目标选到 4320p（7680x4320），
     * 而多数移动 GPU 的上限是 4096 或 8192。超限时 glTexImage2D 会静默失败，
     * 之后绑到 0 号纹理 → 画面全黑，且没有任何报错。
     * 宁可降级成「不超分」也绝不能黑屏。
     */
    private fun maxTexSize(): Int {
        if (cachedMaxTexSize > 0) return cachedMaxTexSize
        val v = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, v, 0)
        cachedMaxTexSize = if (v[0] > 0) v[0] else 4096
        return cachedMaxTexSize
    }

    /** 把一张纹理绑到指定 unit 并设置 sampler uniform（location 为 -1 时静默跳过） */
    private fun bindEnhanceTex(prog: Int, unit: Int, texId: Int, uniformName: String) {
        if (texId == 0) return
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        val loc = GLES20.glGetUniformLocation(prog, uniformName)
        if (loc != -1) GLES20.glUniform1i(loc, unit)
    }

    /** 设置一个 float uniform（location 为 -1 时静默跳过） */
    private fun setEnhanceFloat(prog: Int, name: String, v: Float) {
        val loc = GLES20.glGetUniformLocation(prog, name)
        if (loc != -1) GLES20.glUniform1f(loc, v)
    }

    /** 设置一个 vec2 uniform */
    private fun setEnhanceVec2(prog: Int, name: String, x: Float, y: Float) {
        val loc = GLES20.glGetUniformLocation(prog, name)
        if (loc != -1) GLES20.glUniform2f(loc, x, y)
    }

    /** 绘制全屏 quad（复用磨皮 pass 的 halfQuad，顶点布局一致） */
    private fun drawEnhanceQuad(prog: Int) {
        val posLoc = GLES20.glGetAttribLocation(prog, "aPos")
        val texLoc = GLES20.glGetAttribLocation(prog, "aTex")
        halfQuad.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, halfQuad)
        GLES20.glEnableVertexAttribArray(posLoc)
        halfQuad.position(2)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, halfQuad)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
    }

    /** 编译并链接一个全屏增强 pass（复用磨皮 pass 的编译路径） */
    private fun buildEnhanceProgram(fs: String): Int = buildQuadProgram(fs)

    /** 懒加载 MEMC 的全部 program */
    private fun ensureMemcPrograms() {
        if (pMemcCopyOes == 0) pMemcCopyOes = buildEnhanceProgram(VideoEnhanceShaders.memcCopyFragment(true))
        if (pMemcCopy2d == 0) pMemcCopy2d = buildEnhanceProgram(VideoEnhanceShaders.memcCopyFragment(false))
        if (pMemcMe == 0) pMemcMe = buildEnhanceProgram(VideoEnhanceShaders.MEMC_ME_FS)
        if (pMemcMc == 0) pMemcMc = buildEnhanceProgram(VideoEnhanceShaders.MEMC_MC_FS)
        if (pMemcDiff == 0) pMemcDiff = buildEnhanceProgram(VideoEnhanceShaders.MEMC_FRAME_DIFF_FS)
    }

    /** 懒加载 FSR 的全部 program */
    private fun ensureFsrPrograms() {
        if (pFsrEasuOes == 0) pFsrEasuOes = buildEnhanceProgram(VideoEnhanceShaders.fsrEasuFragment(true))
        if (pFsrEasu2d == 0) pFsrEasu2d = buildEnhanceProgram(VideoEnhanceShaders.fsrEasuFragment(false))
        if (pFsrRcas == 0) pFsrRcas = buildEnhanceProgram(VideoEnhanceShaders.FSR_RCAS_FS)
    }

    /** 重建 MEMC 的 FBO 组（工作尺寸或块尺寸变化时调用） */
    private fun releaseMemcFbos() {
        for (t in intArrayOf(memcPrevTex, memcCurrTex, memcOutTex, memcMvFTex, memcMvBTex, memcDiffTex)) {
            if (t != 0) GLES20.glDeleteTextures(1, intArrayOf(t), 0)
        }
        for (f in intArrayOf(memcPrevFbo, memcCurrFbo, memcOutFbo, memcMvFFbo, memcMvBFbo, memcDiffFbo)) {
            if (f != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(f), 0)
        }
        memcPrevTex = 0; memcPrevFbo = 0
        memcCurrTex = 0; memcCurrFbo = 0
        memcOutTex = 0; memcOutFbo = 0
        memcMvFTex = 0; memcMvFFbo = 0
        memcMvBTex = 0; memcMvBFbo = 0
        memcDiffTex = 0; memcDiffFbo = 0
        memcW = 0; memcH = 0; memcMvW = 0; memcMvH = 0
        memcHasPrev = false
    }

    private fun ensureMemcFbos(w: Int, h: Int) {
        if (memcPrevTex != 0 && memcW == w && memcH == h) return
        releaseMemcFbos()
        memcW = w.coerceAtLeast(2)
        memcH = h.coerceAtLeast(2)
        memcPrevTex = VRGLShaders.createFboTexture(memcW, memcH); memcPrevFbo = VRGLShaders.createFboFor(memcPrevTex)
        memcCurrTex = VRGLShaders.createFboTexture(memcW, memcH); memcCurrFbo = VRGLShaders.createFboFor(memcCurrTex)
        memcOutTex = VRGLShaders.createFboTexture(memcW, memcH); memcOutFbo = VRGLShaders.createFboFor(memcOutTex)
        // MV 场按块网格降采样：每个块只存一个 MV，MC 采样时用 LINEAR 自动插值回全分辨率。
        // 这样 ME 的成本是「块数 × 搜索步数 × 16」，而不是「全分辨率像素数 × ...」。
        memcMvW = ((memcW + memcBlockSize.toInt() - 1) / memcBlockSize.toInt()).coerceAtLeast(1)
        memcMvH = ((memcH + memcBlockSize.toInt() - 1) / memcBlockSize.toInt()).coerceAtLeast(1)
        memcMvFTex = VRGLShaders.createFboTexture(memcMvW, memcMvH); memcMvFFbo = VRGLShaders.createFboFor(memcMvFTex)
        memcMvBTex = VRGLShaders.createFboTexture(memcMvW, memcMvH); memcMvBFbo = VRGLShaders.createFboFor(memcMvBTex)
        memcDiffTex = VRGLShaders.createFboTexture(1, 1); memcDiffFbo = VRGLShaders.createFboFor(memcDiffTex)
        memcHasPrev = false
    }

    private fun releaseFsrFbos() {
        if (fsrTexA != 0) GLES20.glDeleteTextures(1, intArrayOf(fsrTexA), 0)
        if (fsrFboA != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fsrFboA), 0)
        if (fsrTexB != 0) GLES20.glDeleteTextures(1, intArrayOf(fsrTexB), 0)
        if (fsrFboB != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fsrFboB), 0)
        fsrTexA = 0; fsrFboA = 0; fsrTexB = 0; fsrFboB = 0
        fsrW = 0; fsrH = 0
    }

    /** @return 是否成功分配了目标尺寸的 FBO（超限时为 false，调用方需降级） */
    private fun ensureFsrFbos(w: Int, h: Int): Boolean {
        if (fsrTexA != 0 && fsrW == w && fsrH == h) return true
        val limit = maxTexSize()
        if (w > limit || h > limit) {
            Log.w(TAG, "FSR 目标 ${w}x${h} 超出 GL_MAX_TEXTURE_SIZE=$limit，本帧降级为不超分")
            return false
        }
        releaseFsrFbos()
        fsrW = w
        fsrH = h
        fsrTexA = VRGLShaders.createFboTexture(w, h); fsrFboA = VRGLShaders.createFboFor(fsrTexA)
        fsrTexB = VRGLShaders.createFboTexture(w, h); fsrFboB = VRGLShaders.createFboFor(fsrTexB)
        Log.i(TAG, "FSR FBO 已建立：${w}x${h}")
        return true
    }

    /**
     * 跑完整条增强链，返回供主 shader 采样的纹理 id。
     *
     * ⚠️ 副作用：结束时会把 framebuffer 绑回 0 并恢复 viewport —— 调用方
     *    可以当作「什么都没发生过」，随后自行绑定主渲染目标。
     *
     * @param newVideoFrame 本帧是否刚消费到一个新的视频帧（updateTexImage 被调用）
     * @return 增强后的纹理 id；0 表示「不启用增强，走原始通路」
     */
    fun run(newVideoFrame: Boolean): Int {
        if (!isEnhanceRequested()) {
            // 关掉开关时清一次跨帧状态，避免下次打开时拿到陈旧的 prev
            if (memcHasPrev) memcHasPrev = false
            lastEnhanceTexId = 0
            return 0
        }

        // ---- MEMC 目标帧率的节流 ----
        // 「目标帧率」是用户直接调的输出节奏，必须真的限制输出帧率，而不只是
        // 影响插帧相位。未到下一帧的时间点就复用上一帧的增强结果。
        //
        // ⚠️ 这里只复用纹理、**不跳过 onDrawFrame 的绘制** —— 画面仍按屏幕刷新率
        //    重绘（投影矩阵、陀螺仪姿态照常更新），所以 VR 下转头依旧跟手，
        //    只是视频内容停留在上一张。若直接 return 掉整个 onDrawFrame，
        //    转头的卡顿会非常明显（这是本设计与「限帧即跳帧」的关键区别）。
        if (memcEnabled && memcTargetFps > 0 && lastEnhanceTexId != 0) {
            val budgetMs = 1000L / memcTargetFps
            val nowMs = android.os.SystemClock.uptimeMillis()
            if (nowMs - memcLastOutputMs < budgetMs) {
                return lastEnhanceTexId
            }
            memcLastOutputMs = nowMs
        }

        // 状态变化时打一行 —— 这是「开关有没有真的传到渲染层」最直接的证据。
        // 若用户拨了开关但这里没打印，说明 LaunchedEffect 没触发（例如 videoSource 未更新）。
        val stateKey = "MEMC=$memcEnabled@${memcTargetFps}fps FSR=$fsrEnabled@${fsrTargetWidth}x${fsrTargetHeight}"
        if (stateKey != lastEnhanceStateKey) {
            lastEnhanceStateKey = stateKey
            Log.i(TAG, "画质增强状态变更: $stateKey")
        }

        var tex = 0

        // ---------- 第一段：MEMC ----------
        if (memcEnabled && isVideoActive) {
            tex = runMemc(newVideoFrame)
        }

        // ---------- 第二段：FSR ----------
        if (fsrEnabled && fsrTargetWidth > 0 && fsrTargetHeight > 0) {
            val out = runFsr(tex)
            if (out != 0) tex = out
        }

        // 恢复 GL 状态，交给调用方（默认 framebuffer + 满屏 viewport）
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, displayWidth, displayHeight)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        lastEnhanceTexId = tex
        return tex
    }

    /**
     * MEMC：把当前视频帧插值成「更密的帧」。
     *
     * 【为什么必须先把 OES 拷成 2D】
     * ME/MC 要**同时**持有 prev 与 curr 两张纹理，而 SurfaceTexture 只提供唯一一张
     * ExternalOES（且 updateTexImage 原地更新）。所以每来一帧都要先落到自己的
     * 2D 纹理里，才能构成「双帧」。
     *
     * 【时间驱动相位，而不是计数驱动】
     * phase 由「当前渲染时刻落在 [prev, curr] 区间的比例」决定，而不是简单地
     * 每次 +1/N。这样渲染循环与源帧率不同步（30fps 源 + 90Hz 屏）时也不会出现
     * 相位跳变/抖动。
     *
     * @return 插帧结果纹理；0 表示本帧不具备插帧条件（已退化为直接给 curr）
     */
    private fun runMemc(newVideoFrame: Boolean): Int {
        ensureMemcPrograms()
        if (pMemcCopyOes == 0 || pMemcMe == 0 || pMemcMc == 0) return 0

        val workW = if (videoWidth > 0) videoWidth else displayWidth
        val workH = if (videoHeight > 0) videoHeight else displayHeight
        if (workW <= 0 || workH <= 0) return 0
        ensureMemcFbos(workW, workH)
        if (memcPrevTex == 0 || memcOutTex == 0) return 0

        val now = android.os.SystemClock.uptimeMillis()

        if (newVideoFrame || !memcHasPrev) {
            // 新帧到达：curr 降级为 prev，新帧写入 curr
            if (memcHasPrev) {
                // prev ← curr（整帧拷贝）
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, memcPrevFbo)
                GLES20.glViewport(0, 0, memcW, memcH)
                GLES20.glDisable(GLES20.GL_BLEND)
                GLES20.glUseProgram(pMemcCopy2d)
                bindEnhanceTex(pMemcCopy2d, 0, memcCurrTex, "uSrc")
                drawEnhanceQuad(pMemcCopy2d)
                memcPrevTimeMs = memcCurrTimeMs
            } else {
                memcPrevTimeMs = now
            }

            // 新视频帧 → curr（OES → 2D）
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, memcCurrFbo)
            GLES20.glViewport(0, 0, memcW, memcH)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glUseProgram(pMemcCopyOes)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
            val copyLoc = GLES20.glGetUniformLocation(pMemcCopyOes, "uSrc")
            if (copyLoc != -1) GLES20.glUniform1i(copyLoc, 0)
            drawEnhanceQuad(pMemcCopyOes)
            // ⚠️ 必须在此更新 curr 的时间戳：相位 = (now - prevTime) / (currTime - prevTime)，
            //    漏了这一行 currTime 永远是 0，span 恒为负 → 相位恒为 1 → 插帧永不生效。
            memcCurrTimeMs = now

            // 首帧时 prev 与 curr 还不是同一张，直接复制一份，避免第一帧就插出鬼影
            if (!memcHasPrev) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, memcPrevFbo)
                GLES20.glViewport(0, 0, memcW, memcH)
                GLES20.glUseProgram(pMemcCopy2d)
                bindEnhanceTex(pMemcCopy2d, 0, memcCurrTex, "uSrc")
                drawEnhanceQuad(pMemcCopy2d)
                memcHasPrev = true
            }

            // 场景切换检测：只在新帧时做一次（prev/curr 只在这时变化）
            memcSceneCut = detectSceneCut()
            if (memcSceneCut) memcSceneCutCount++
        }

        // ---- 相位 ----
        // ⚠️ 必须用「显示时刻 = now − 一个源帧间隔」来算，**不能直接用 now**。
        //    判例（v2.0.206 装机实测，2026-10-01）：此前写的是
        //        phase = (now - prevTime) / (currTime - prevTime)
        //    而 currTime 就是「本帧刚取到的 now」（新帧分支里 memcCurrTimeMs = now），
        //    于是新帧到达那一帧恒得 1.0；渲染循环帧率 ≥ 源帧率时每一帧都踩中该分支
        //    → **相位永远钉在 1，插帧从头到尾没发生过**
        //    （logcat 连续 15 次打印 `phase=1.00` 证实）。
        //    减去一个源帧间隔后，相位才会真正在 [0,1] 之间推进：
        //        新帧刚到达      → phase = 0（显示 prev）
        //        下一帧到来之前  → phase → 1（显示 curr）
        //        中间时刻        → phase ∈ (0,1)  ← 插帧真正发生的地方
        //    代价是输出延迟一个源帧间隔（MEMC 的固有代价；30fps 源约 33ms，肉眼不可察）。
        val span = memcCurrTimeMs - memcPrevTimeMs
        var phase = if (span <= 0L) {
            // 首帧 / 时间戳未推进（还没攒够两帧）→ 直接显示 curr
            1f
        } else {
            ((now - span - memcPrevTimeMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
        }
        // 场景切换 → 不插帧，直接显示 curr（否则会插出两个场景叠加的鬼影）
        if (memcSceneCut) phase = 1f
        memcLastPhase = phase
        // 相位落在中间区间才算「插帧真的发生了」—— 比 phase 的瞬时值更硬的证据
        if (phase > 0.05f && phase < 0.95f) memcInterpCount++

        // ---- ME 两次（前向 / 反向）----
        runMemcMe(memcPrevTex, memcCurrTex, memcMvFFbo)
        runMemcMe(memcCurrTex, memcPrevTex, memcMvBFbo)

        // ---- MC 插帧 ----
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, memcOutFbo)
        GLES20.glViewport(0, 0, memcW, memcH)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(pMemcMc)
        bindEnhanceTex(pMemcMc, 0, memcPrevTex, "uTexPrev")
        bindEnhanceTex(pMemcMc, 1, memcCurrTex, "uTexCurr")
        bindEnhanceTex(pMemcMc, 2, memcMvFTex, "uTexMvF")
        bindEnhanceTex(pMemcMc, 3, memcMvBTex, "uTexMvB")
        setEnhanceVec2(pMemcMc, "uTexelSize", 1.0f / memcW, 1.0f / memcH)
        setEnhanceFloat(pMemcMc, "uSearchRadius", memcSearchRadius)
        setEnhanceFloat(pMemcMc, "uPhase", phase)
        setEnhanceFloat(pMemcMc, "uOcclusionThresh", memcOcclusionThresh)
        drawEnhanceQuad(pMemcMc)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        // 每 120 帧一行统计。
        // ⚠️ 判读要点：phase 必须**在 0~1 之间变化**；若恒为 1.00，说明插帧没有生效
        //    （最常见原因：currTime 没更新 → span 恒负 → 被 clamp 成 1）。
        //    「场景切换累计」在正常播放时应缓慢增长；若疯长说明阈值偏低、频繁误判。
        memcFrameCount++
        if (memcFrameCount == 1L || memcFrameCount % 120L == 0L) {
            Log.i(
                TAG,
                "MEMC 运行中 #$memcFrameCount phase=${"%.2f".format(memcLastPhase)} " +
                    "插帧帧数=$memcInterpCount 场景切换累计=$memcSceneCutCount " +
                    "工作=${memcW}x${memcH} MV=${memcMvW}x${memcMvH}"
            )
        }

        return memcOutTex
    }

    /** 跑一次 ME：把 (prev, curr) 的 MV 场渲染到 dstFbo */
    private fun runMemcMe(prevTex: Int, currTex: Int, dstFbo: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dstFbo)
        GLES20.glViewport(0, 0, memcMvW, memcMvH)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(pMemcMe)
        bindEnhanceTex(pMemcMe, 0, prevTex, "uTexPrev")
        bindEnhanceTex(pMemcMe, 1, currTex, "uTexCurr")
        setEnhanceVec2(pMemcMe, "uTexelSize", 1.0f / memcW, 1.0f / memcH)
        setEnhanceFloat(pMemcMe, "uSearchRadius", memcSearchRadius)
        setEnhanceFloat(pMemcMe, "uBlockSize", memcBlockSize)
        setEnhanceFloat(pMemcMe, "uSadThreshold", memcSadThreshold)
        drawEnhanceQuad(pMemcMe)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    /**
     * 场景切换检测：跑一次 1x1 的帧差 pass 并回读单个像素。
     *
     * ⚠️ 这是整条增强链里**唯一**的回读。1 个像素 4 字节，代价可忽略；
     *    但必须读 —— 场景切换时任何 MV 都是噪声，不跳过插帧就会出鬼影。
     */
    private fun detectSceneCut(): Boolean {
        if (pMemcDiff == 0 || memcDiffFbo == 0) return false
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, memcDiffFbo)
        GLES20.glViewport(0, 0, 1, 1)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(pMemcDiff)
        bindEnhanceTex(pMemcDiff, 0, memcPrevTex, "uTexPrev")
        bindEnhanceTex(pMemcDiff, 1, memcCurrTex, "uTexCurr")
        drawEnhanceQuad(pMemcDiff)
        val buf = java.nio.ByteBuffer.allocateDirect(4).order(java.nio.ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        buf.position(0)
        val diff = (buf.get(0).toInt() and 0xFF) / 255f
        return diff > memcSceneCutDiff
    }

    /**
     * FSR：把 srcTex（0 表示直接从视频源/图片源取）超分到目标分辨率。
     *
     * 两个 pass：EASU 上采样 → RCAS 锐化。
     * ⚠️ 为什么不做成单 pass：RCAS 需要 EASU 输出结果的**邻域**，
     *    而单 pass 里拿不到「别的输出像素」的值，只能退化成对 4 个源邻域
     *    各跑一次 EASU（采样数 ×4）。两 pass 反而更省。
     *
     * @return 超分结果纹理；0 表示失败（已降级，调用方应使用原始通路）
     */
    private fun runFsr(srcTex: Int): Int {
        ensureFsrPrograms()
        if (pFsrRcas == 0) return 0
        if (!ensureFsrFbos(fsrTargetWidth, fsrTargetHeight)) return 0
        if (fsrTexA == 0 || fsrTexB == 0) return 0

        // 选择 EASU 程序与输入尺寸：MEMC 输出 / 图片 = 2D；视频源 = OES
        val fromMemcOrImage = srcTex != 0 || !isVideoActive
        val easuProg = if (fromMemcOrImage) pFsrEasu2d else pFsrEasuOes
        if (easuProg == 0) return 0

        val inputTex = when {
            srcTex != 0 -> srcTex
            isVideoActive -> videoTextureId
            else -> imageTextureId
        }
        if (inputTex == 0) return 0

        val srcW = when {
            srcTex != 0 -> memcW
            isVideoActive -> if (videoWidth > 0) videoWidth else displayWidth
            else -> if (imageWidth > 0) imageWidth else displayWidth
        }
        val srcH = when {
            srcTex != 0 -> memcH
            isVideoActive -> if (videoHeight > 0) videoHeight else displayHeight
            else -> if (imageHeight > 0) imageHeight else displayHeight
        }
        if (srcW <= 0 || srcH <= 0) return 0

        // ---- pass 1：EASU 上采样 → fsrFboA ----
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fsrFboA)
        GLES20.glViewport(0, 0, fsrW, fsrH)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(easuProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        val isOesInput = (srcTex == 0 && isVideoActive)
        if (isOesInput) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTex)
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTex)
        }
        val easuLoc = GLES20.glGetUniformLocation(easuProg, "uSrc")
        if (easuLoc != -1) GLES20.glUniform1i(easuLoc, 0)
        setEnhanceVec2(easuProg, "uSrcTexel", 1.0f / srcW, 1.0f / srcH)
        drawEnhanceQuad(easuProg)

        // ---- pass 2：RCAS 锐化 → fsrFboB ----
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fsrFboB)
        GLES20.glViewport(0, 0, fsrW, fsrH)
        GLES20.glUseProgram(pFsrRcas)
        bindEnhanceTex(pFsrRcas, 0, fsrTexA, "uTex")
        setEnhanceVec2(pFsrRcas, "uTexel", 1.0f / fsrW, 1.0f / fsrH)
        setEnhanceFloat(pFsrRcas, "uSharpness", fsrRcasSharpness)
        drawEnhanceQuad(pFsrRcas)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        fsrSrcW = srcW
        fsrSrcH = srcH
        fsrFrameCount++
        if (fsrFrameCount == 1L || fsrFrameCount % 120L == 0L) {
            Log.i(TAG, "FSR 运行中 #$fsrFrameCount ${srcW}x${srcH} -> ${fsrW}x${fsrH}")
        }

        return fsrTexB
    }

    /** 释放画质增强的全部 GL 资源 */
    fun release() {
        try {
            releaseMemcFbos()
            releaseFsrFbos()
            if (pMemcCopyOes != 0) { GLES20.glDeleteProgram(pMemcCopyOes); pMemcCopyOes = 0 }
            if (pMemcCopy2d != 0) { GLES20.glDeleteProgram(pMemcCopy2d); pMemcCopy2d = 0 }
            if (pMemcMe != 0) { GLES20.glDeleteProgram(pMemcMe); pMemcMe = 0 }
            if (pMemcMc != 0) { GLES20.glDeleteProgram(pMemcMc); pMemcMc = 0 }
            if (pMemcDiff != 0) { GLES20.glDeleteProgram(pMemcDiff); pMemcDiff = 0 }
            if (pFsrEasuOes != 0) { GLES20.glDeleteProgram(pFsrEasuOes); pFsrEasuOes = 0 }
            if (pFsrEasu2d != 0) { GLES20.glDeleteProgram(pFsrEasu2d); pFsrEasu2d = 0 }
            if (pFsrRcas != 0) { GLES20.glDeleteProgram(pFsrRcas); pFsrRcas = 0 }
            lastEnhanceTexId = 0
        } catch (e: Throwable) {
            Log.w(TAG, "releaseVideoEnhance 失败", e)
        }
    }

}
