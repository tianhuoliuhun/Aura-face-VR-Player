package com.example.vr.poc

import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

/**
 * PoC：共享 EGLContext 下 texture 互通 + 跨 context fence 同步 —— 共享状态。
 *
 * ## 要验证的问题（这是 GPUPixel texture 通道方案唯一的不确定点）
 *
 * 1. 用同一个 `share_context`（root）派生出的两个 EGLContext，能否**互相看到对方创建的
 *    texture / FBO / GLsync**？
 * 2. `glFenceSync` 产生的 sync 对象能否**跨 context** 被 `glClientWaitSync` 等待？
 * 3. 长期运行是否会出现**撕裂 / 错帧**（同步失效的典型症状）？
 *
 * ## 判定方法（可量化，不靠肉眼）
 *
 * - 主 context 把 `texA` 清成灰度 `((frame * 8) and 0xFF) / 255`（**帧号步长 8**）
 * - 第二 context 等 `fenceA` → 直通复制 `texA → texB` → 插 `fenceB`
 * - 主 context 等 `fenceB` → **读回 texB 中心像素**，与期望值比对
 *
 * 若 fence 失效（读到旧帧 / 未写完的 texture），像素会差偶数个 8 —— 与浮点精度无关，
 * 因此**任何一次失配都是真实的同步错误**。判据：跑满 N 帧，`mismatches == 0`、
 * `glErrors == 0`、`waitTimeouts == 0`。
 *
 * ⚠️ 注意 GL 对象的**共享规则**：texture / FBO / renderbuffer / buffer / sync 是共享对象，
 * 但 **program / shader / VAO 不是** —— 所以第二 context 必须自己编译自己的 program。
 */
object PocShared {
    const val TAG = "PocSharedCtx"

    const val TEX_W = 512
    const val TEX_H = 512

    /** fence 等待超时（正常应在一个帧间隔内 signal，给足余量便于诊断） */
    const val TIMEOUT_NS = 300_000_000L

    /** 主 context 每帧写入 texA 的灰度步长（失配时会差偶数个 8，易与浮点误差区分） */
    const val FRAME_STEP = 8

    /** 保护 fence 交接（避免"主线程写新 fence / 第二线程置 0"互相覆盖） */
    val lock = Any()

    // ===== EGL（主线程创建，第二线程复用）=====
    @Volatile var egl: EGL10? = null
    @Volatile var display: EGLDisplay? = null
    @Volatile var config: EGLConfig? = null

    /** 共享根 context：两个 context 都以它为 share_context */
    @Volatile var rootContext: EGLContext? = null

    /** 第二 context 是否已就绪（就绪后主线程才允许开始往返） */
    @Volatile var secondaryReady = false

    @Volatile var secondaryError = ""

    // ===== 共享 GL 对象（必须由主 context 创建）=====
    @Volatile var texA = 0
    @Volatile var texB = 0
    @Volatile var fboA = 0
    @Volatile var fboB = 0
    @Volatile var glReady = false

    // ===== fence（GLsync，跨 context 共享）=====
    /** 主 context 已把 texA 写完 */
    @Volatile var fenceA = 0L
    /** 第二 context 已把 texB 写完 */
    @Volatile var fenceB = 0L

    // ===== 帧协调 =====
    val mainFrames = AtomicInteger(0)
    /** 当前已写入 texA、等待第二 context 取走的帧号 */
    @Volatile var pendingFrame = -1
    /** 第二 context 最后复制到 texB 的帧号 */
    @Volatile var consumedFrame = -1
    @Volatile var hasResult = false

    // ===== 统计 =====
    val roundTrips = AtomicInteger(0)
    val mismatches = AtomicInteger(0)
    val glErrors = AtomicInteger(0)
    val waitTimeouts = AtomicInteger(0)
    val waitFailed = AtomicInteger(0)
    /** 重试 3 次仍失败的次数（这些帧会被丢弃，但有保底帧兜底） */
    val waitGiveUp = AtomicInteger(0)
    val roundTripNanos = AtomicLong(0)
    @Volatile var lastMismatch = ""
    @Volatile var lastGlError = ""
    @Volatile var secondaryFrames = 0

    /** 第二 context **绘制成功**的帧数：与 secondaryFrames 对比可区分「同步失败」与「绘制失败」 */
    @Volatile var secondaryDrawOk = 0

    fun countGlError(where: String) {
        val e = GLES20.glGetError()
        if (e != GLES20.GL_NO_ERROR) {
            glErrors.incrementAndGet()
            lastGlError = "$where: 0x${Integer.toHexString(e)}"
            Log.w(TAG, "GL error at $where: 0x${Integer.toHexString(e)}")
        }
    }

    fun reset() {
        mainFrames.set(0)
        roundTrips.set(0)
        mismatches.set(0)
        glErrors.set(0)
        waitTimeouts.set(0)
        waitFailed.set(0)
        waitGiveUp.set(0)
        roundTripNanos.set(0)
        lastMismatch = ""
        lastGlError = ""
        secondaryFrames = 0
        secondaryDrawOk = 0
        primaryFrames = 0
    }

    /** 主 context 实际渲染并插 fence 的帧数（可能少于 mainFrames，因为会跳过等空位的帧） */
    @Volatile var primaryFrames = 0

    fun summary(): String {
        val rt = roundTrips.get()
        val avgMs = if (rt > 0) roundTripNanos.get() / rt / 1_000_000 else 0
        return buildString {
            append("主帧=").append(mainFrames.get())
            append("  往返=").append(rt)
            append("  第二ctx帧=").append(secondaryFrames)
            append("  绘制成功=").append(secondaryDrawOk)
            append('\n')
            append("平均往返=").append(avgMs).append("ms")
            append("  纹理=").append(TEX_W).append('x').append(TEX_H)
            append('\n')
            append("失配=").append(mismatches.get())
            append("  GL错误=").append(glErrors.get())
            append("  超时=").append(waitTimeouts.get())
            append("  等待失败=").append(waitFailed.get())
            append("  放弃=").append(waitGiveUp.get())
            if (lastMismatch.isNotEmpty()) append('\n').append("最近失配: ").append(lastMismatch)
            if (lastGlError.isNotEmpty()) append('\n').append("最近GL错: ").append(lastGlError)
            if (secondaryError.isNotEmpty()) append('\n').append("第二ctx错误: ").append(secondaryError)
        }
    }
}

/**
 * 第二 EGLContext 线程。
 *
 * - 用 [PocShared.rootContext] 作为 `share_context` 创建自己的 context（**这是 PoC 的核心动作**）
 * - 配 1x1 pbuffer 作为 surface（离屏渲染不需要 window surface）
 * - 自己编译直通 program（program 不跨 context 共享）
 * - 循环：等 fenceA → 复制 texA→texB → 插 fenceB
 */
class PocSecondaryContext : Thread("poc-secondary-ctx") {

    private var egl: EGL10? = null
    private var display: EGLDisplay? = null
    private var surface: EGLSurface? = null
    private var context: EGLContext? = null
    private var program = 0
    private var uTexLoc = 0
    private var aPosLoc = 0
    private var aTexLoc = 0
    private var quad: FloatBuffer? = null

    @Volatile private var running = true

    fun shutdown() {
        running = false
    }

    override fun run() {
        try {
            setup()
            PocShared.secondaryReady = true
            Log.i(PocShared.TAG, "secondary context READY (share_context = root)")
            loop()
        } catch (t: Throwable) {
            PocShared.secondaryError = t.message ?: t.toString()
            Log.e(PocShared.TAG, "secondary context thread failed", t)
        } finally {
            teardown()
        }
    }

    private fun setup() {
        val e = PocShared.egl ?: throw IllegalStateException("EGL10 not published yet")
        val d = PocShared.display ?: throw IllegalStateException("EGLDisplay not published")
        val c = PocShared.config ?: throw IllegalStateException("EGLConfig not published")
        val root = PocShared.rootContext ?: throw IllegalStateException("root context not published")
        egl = e
        display = d

        // ⚠️ EGL10 接口**没有** EGL_CONTEXT_CLIENT_VERSION 常量 —— 它在 android.opengl.EGL14 里
        val attribs = intArrayOf(android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL10.EGL_NONE)
        // ⚠️⚠️ PoC 要点 1：以 root 作为 share_context
        val ctx = e.eglCreateContext(d, c, root, attribs)
        if (ctx == null || ctx == EGL10.EGL_NO_CONTEXT) {
            throw IllegalStateException("eglCreateContext(shared) failed")
        }
        context = ctx

        val pb = intArrayOf(EGL10.EGL_WIDTH, 1, EGL10.EGL_HEIGHT, 1, EGL10.EGL_NONE)
        val s = e.eglCreatePbufferSurface(d, c, pb)
        if (s == null || s == EGL10.EGL_NO_SURFACE) {
            throw IllegalStateException("eglCreatePbufferSurface failed")
        }
        surface = s

        if (!e.eglMakeCurrent(d, s, s, ctx)) {
            throw IllegalStateException("eglMakeCurrent(secondary) failed")
        }
        Log.i(PocShared.TAG, "secondary EGL current: ver=${GLES20.glGetString(GLES20.GL_VERSION)}")

        buildProgram()

        // ===== 诊断 1：共享可见性 —— 必须用 GL 查询，打印 Kotlin 变量毫无意义 =====
        val isTexA = GLES20.glIsTexture(PocShared.texA)
        val isTexB = GLES20.glIsTexture(PocShared.texB)
        val isFboA = GLES30.glIsFramebuffer(PocShared.fboA)
        val isFboB = GLES30.glIsFramebuffer(PocShared.fboB)
        Log.i(
            PocShared.TAG,
            "shared visibility (from secondary ctx): " +
                "isTexture(texA)=$isTexA isTexture(texB)=$isTexB " +
                "isFramebuffer(fboA)=$isFboA isFramebuffer(fboB)=$isFboB " +
                "[ids texA=${PocShared.texA} texB=${PocShared.texB} fboA=${PocShared.fboA} fboB=${PocShared.fboB}]"
        )

        // ===== 诊断 2：在本 context 里重新 attach texB→fboB 并查完整性 =====
        // 跨 context 使用 FBO 时，完整性状态可能需要在「使用方 context」里重新建立
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, PocShared.fboB)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, PocShared.texB, 0
        )
        val attachErr = GLES20.glGetError()
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        Log.i(
            PocShared.TAG,
            "secondary re-attach fboB: status=0x${Integer.toHexString(status)} " +
                (if (status == GLES20.GL_FRAMEBUFFER_COMPLETE) "COMPLETE" else "NOT-COMPLETE") +
                " attachErr=0x${Integer.toHexString(attachErr)}"
        )

        // ===== 诊断 3：texB 的尺寸在第二 context 里是否可见 =====
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, PocShared.texB)
        val lvl = IntArray(1)
        GLES20.glGetTexParameteriv(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, lvl, 0)
        val sizeErr = GLES20.glGetError()
        Log.i(PocShared.TAG, "secondary texB bind: minFilter=0x${Integer.toHexString(lvl[0])} err=0x${Integer.toHexString(sizeErr)}")

        PocShared.countGlError("secondary setup")
    }

    /** program / shader **不跨 context 共享**，第二 context 必须自己编译 */
    private fun buildProgram() {
        val vs = "attribute vec2 aPos;attribute vec2 aTex;varying vec2 vTex;" +
            "void main(){gl_Position=vec4(aPos,0.0,1.0);vTex=aTex;}"
        val fs = "precision mediump float;varying vec2 vTex;uniform sampler2D uTex;" +
            "void main(){gl_FragColor=texture2D(uTex,vTex);}"
        val v = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
        GLES20.glShaderSource(v, vs)
        GLES20.glCompileShader(v)
        val f = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
        GLES20.glShaderSource(f, fs)
        GLES20.glCompileShader(f)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, v)
        GLES20.glAttachShader(program, f)
        GLES20.glLinkProgram(program)
        val st = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, st, 0)
        if (st[0] == 0) {
            throw IllegalStateException("secondary program link failed: " + GLES20.glGetProgramInfoLog(program))
        }
        uTexLoc = GLES20.glGetUniformLocation(program, "uTex")
        aPosLoc = GLES20.glGetAttribLocation(program, "aPos")
        aTexLoc = GLES20.glGetAttribLocation(program, "aTex")
        quad = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(
                floatArrayOf(
                    -1f, -1f, 0f, 0f,
                    1f, -1f, 1f, 0f,
                    -1f, 1f, 0f, 1f,
                    1f, 1f, 1f, 1f
                )
            )
            position(0)
        }
    }

    private fun loop() {
        while (running) {
            // 用锁交接 fenceA：主线程只在 fenceA == 0 时写入，这里取走并清零
            val fa = synchronized(PocShared.lock) {
                val x = PocShared.fenceA
                PocShared.fenceA = 0L
                x
            }
            if (fa == 0L) {
                Thread.sleep(1)
                continue
            }

            // ⚠️ 跨 context 等待时**不要**带 GL_SYNC_FLUSH_COMMANDS_BIT：
            //    该位的作用是「等待前 flush 本 context 的命令队列」，而这里等的是
            //    **另一个 context 创建的 sync**，flush 自己毫无帮助，实测还会偶发 WAIT_FAILED。
            // 带重试的等待：WAIT_FAILED 在跨 context 场景偶发，重试通常即可成功
            var ok = false
            var attempts = 0
            var lastSt = 0
            while (attempts < 3 && !ok) {
                val st = GLES30.glClientWaitSync(fa, 0, PocShared.TIMEOUT_NS)
                lastSt = st
                when (st) {
                    GLES30.GL_ALREADY_SIGNALED, GLES30.GL_CONDITION_SATISFIED -> ok = true
                    GLES30.GL_WAIT_FAILED -> {
                        attempts++
                        PocShared.waitFailed.incrementAndGet()
                    }
                    else -> {
                        attempts++
                        PocShared.waitTimeouts.incrementAndGet()
                    }
                }
            }
            if (!ok) {
                PocShared.waitGiveUp.incrementAndGet()
                if (PocShared.waitGiveUp.get() <= 3) {
                    Log.w(
                        PocShared.TAG,
                        "fenceA give up after $attempts tries, lastSt=0x${Integer.toHexString(lastSt)}"
                    )
                }
                GLES30.glDeleteSync(fa)
                continue
            }
            GLES30.glDeleteSync(fa)

            val srcFrame = PocShared.pendingFrame

            // 直通复制 texA → texB（texB / fboB 都是主 context 创建的共享对象）
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, PocShared.fboB)
            if (PocShared.secondaryFrames < 3) {
                val st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
                Log.i(
                    PocShared.TAG,
                    "copy#${PocShared.secondaryFrames}: fboB status=0x${Integer.toHexString(st)}" +
                        (if (st == GLES20.GL_FRAMEBUFFER_COMPLETE) " COMPLETE" else " NOT-COMPLETE")
                )
            }
            GLES20.glViewport(0, 0, PocShared.TEX_W, PocShared.TEX_H)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, PocShared.texA)
            GLES20.glUniform1i(uTexLoc, 0)
            val q = quad!!
            q.position(0)
            GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 16, q)
            GLES20.glEnableVertexAttribArray(aPosLoc)
            q.position(2)
            GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 16, q)
            GLES20.glEnableVertexAttribArray(aTexLoc)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            // 每次绘制后单独取错误（glGetError 只返回并清空第一个错误）
            val drawErr = GLES20.glGetError()
            GLES20.glDisableVertexAttribArray(aPosLoc)
            GLES20.glDisableVertexAttribArray(aTexLoc)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            if (drawErr != GLES20.GL_NO_ERROR) {
                val n = PocShared.glErrors.incrementAndGet()
                PocShared.lastGlError = "secondary draw: 0x${Integer.toHexString(drawErr)}"
                if (n <= 3) {
                    Log.w(
                        PocShared.TAG,
                        "secondary draw GL error 0x${Integer.toHexString(drawErr)} (seen=$n)"
                    )
                }
            } else {
                // 单独统计"成功绘制"的帧数，用于区分「同步失败」与「绘制失败」
                PocShared.secondaryDrawOk++
            }

            // 插 fenceB 通知主 context：texB 已就绪
            val fb = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            PocShared.secondaryFrames++
            synchronized(PocShared.lock) {
                PocShared.fenceB = fb
                PocShared.consumedFrame = srcFrame
            }
        }
    }

    private fun teardown() {
        try {
            val e = egl
            val d = display
            if (e != null && d != null) {
                e.eglMakeCurrent(d, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
                surface?.let { e.eglDestroySurface(d, it) }
                context?.let { e.eglDestroyContext(d, it) }
            }
        } catch (t: Throwable) {
            Log.w(PocShared.TAG, "teardown error", t)
        }
    }
}
