package com.example.vr.poc

import android.app.Activity
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

/**
 * PoC 入口：验证「共享 EGLContext 下 texture 互通 + fence 跨 context 同步」是否可靠。
 *
 * 判定看屏幕上的统计行：跑够帧数后 **失配 = 0、GL错误 = 0、超时 = 0** 即通过。
 *
 * 启动（adb）：
 * ```
 * am start -n com.aistudio.vrplayer.vrmjpy/com.example.vr.poc.PocSharedContextActivity
 * ```
 */
class PocSharedContextActivity : Activity() {

    private lateinit var tv: TextView
    private var secondary: PocSecondaryContext? = null
    private val handler = Handler(Looper.getMainLooper())
    private var uiTicks = 0

    private val uiTick = object : Runnable {
        override fun run() {
            val s = PocShared.summary()
            tv.text = s
            // 每 3 秒把统计打进 logcat，便于自动化取证（UI 无法被脚本读取）
            uiTicks++
            if (uiTicks % 6 == 0) {
                Log.i(PocShared.TAG, "STAT " + s.replace('\n', ' '))
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PocShared.reset()

        tv = TextView(this).apply {
            setPadding(24, 24, 24, 24)
            textSize = 12f
            setTextColor(0xFF102010.toInt())
            setBackgroundColor(0xFFE8F2E8.toInt())
        }

        val glView = GLSurfaceView(this).apply {
            // ⚠️ 顺序很重要：setEGLContextFactory 必须在 setRenderer 之前，
            //    且它会覆盖 setEGLContextClientVersion 设置的默认 factory ——
            //    故 ES3 版本号在我们的 factory 内部自行声明。
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setEGLContextFactory(PocContextFactory())
            setRenderer(
                PocRenderer {
                    if (secondary == null) {
                        secondary = PocSecondaryContext().also { it.start() }
                    }
                }
            )
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(tv, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(glView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.post(uiTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(uiTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        secondary?.shutdown()
        secondary = null
    }

    /**
     * 关键：创建「共享根 context」，渲染 context 以它为 share_context。
     *
     * ```
     *   root (无 parent)
     *     ├── render context（GLSurfaceView 渲染用）
     *     └── secondary context（第二线程用，见 PocSecondaryContext）
     * ```
     * 两个 child 共享同一 parent ⇒ **互相可见对方创建的 texture / FBO / GLsync**。
     */
    private class PocContextFactory : GLSurfaceView.EGLContextFactory {
        override fun createContext(egl: EGL10, display: EGLDisplay, config: EGLConfig): EGLContext {
            // ⚠️ EGL10 接口没有 EGL_CONTEXT_CLIENT_VERSION（在 android.opengl.EGL14 里）
            val attribs = intArrayOf(android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL10.EGL_NONE)
            val root = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, attribs)
            if (root == null || root == EGL10.EGL_NO_CONTEXT) {
                Log.e(PocShared.TAG, "root context creation FAILED")
            }
            val render = egl.eglCreateContext(display, config, root, attribs)
            if (render == null || render == EGL10.EGL_NO_CONTEXT) {
                Log.e(PocShared.TAG, "render context creation FAILED")
            }
            // 发布给第二线程 —— rootContext **最后**设置，作为「信息齐全」的信号
            PocShared.egl = egl
            PocShared.display = display
            PocShared.config = config
            PocShared.rootContext = root
            Log.i(PocShared.TAG, "contexts created: root=$root render=$render (share_context=root)")
            return render
        }

        override fun destroyContext(egl: EGL10, display: EGLDisplay, context: EGLContext) {
            egl.eglDestroyContext(display, context)
        }
    }

    private class PocRenderer(private val onGlReady: () -> Unit) : GLSurfaceView.Renderer {

        private var inited = false
        private var viewW = 1
        private var viewH = 1
        private var program = 0
        private var aPosLoc = 0
        private var aTexLoc = 0
        private var uTexLoc = 0
        private var quad: FloatBuffer? = null
        private var pxBuf: ByteBuffer? = null
        private var hasResult = false
        private var rtStartNanos = 0L
        private var readySignalled = false

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            createSharedObjects()
            buildScreenProgram()
            pxBuf = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
            inited = true
            PocShared.glReady = true
            if (!readySignalled) {
                readySignalled = true
                // 共享对象已就绪 → 现在可以安全启动第二 context 线程
                onGlReady()
            }
            Log.i(
                PocShared.TAG,
                "main context ready: ver=${GLES20.glGetString(GLES20.GL_VERSION)}"
            )
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewW = width.coerceAtLeast(1)
            viewH = height.coerceAtLeast(1)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (!inited) return
            val frame = PocShared.mainFrames.incrementAndGet()

            // ---- 1) 收割 fenceB（第二 context 是否已把 texB 写好）----
            var gotNew = false
            val fb = synchronized(PocShared.lock) {
                val x = PocShared.fenceB
                PocShared.fenceB = 0L
                x
            }
            if (fb != 0L) {
                // 跨 context 等待：不带 GL_SYNC_FLUSH_COMMANDS_BIT（见 PocSecondaryContext 的说明）
                val st = GLES30.glClientWaitSync(fb, 0, PocShared.TIMEOUT_NS)
                if (st == GLES30.GL_ALREADY_SIGNALED || st == GLES30.GL_CONDITION_SATISFIED) {
                    gotNew = true
                } else if (st == GLES30.GL_WAIT_FAILED) {
                    PocShared.waitFailed.incrementAndGet()
                    Log.w(PocShared.TAG, "fenceB WAIT_FAILED")
                } else {
                    PocShared.waitTimeouts.incrementAndGet()
                    Log.w(PocShared.TAG, "fenceB wait timeout st=0x${Integer.toHexString(st)}")
                }
                GLES30.glDeleteSync(fb)
            }

            // ---- 2) 校验（像素级）+ 上屏 ----
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, viewW, viewH)
            if (gotNew) {
                verify(PocShared.consumedFrame)
                PocShared.roundTrips.incrementAndGet()
                if (rtStartNanos != 0L) {
                    PocShared.roundTripNanos.addAndGet(System.nanoTime() - rtStartNanos)
                    rtStartNanos = 0L
                }
                drawTex(PocShared.texB)
                hasResult = true
            } else if (hasResult) {
                drawTex(PocShared.texB)
            } else {
                GLES20.glClearColor(0.05f, 0.05f, 0.1f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            }

            // ---- 3) 上一轮已被取走（fenceA == 0）才渲染新输入，避免覆盖未消费的 fence ----
            synchronized(PocShared.lock) {
                if (PocShared.fenceA == 0L && PocShared.secondaryReady) {
                    val v = ((frame * PocShared.FRAME_STEP) and 0xFF) / 255f
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, PocShared.fboA)
                    GLES20.glViewport(0, 0, PocShared.TEX_W, PocShared.TEX_H)
                    GLES20.glClearColor(v, v, v, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

                    PocShared.pendingFrame = frame          // 先写帧号
                    rtStartNanos = System.nanoTime()
                    PocShared.fenceA = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
                    PocShared.primaryFrames++
                }
            }

            PocShared.countGlError("main frame")
        }

        /** 创建 texA / texB / fboA / fboB —— 均由主 context 创建，第二 context 通过共享可见 */
        private fun createSharedObjects() {
            val tex = IntArray(2)
            GLES20.glGenTextures(2, tex, 0)
            PocShared.texA = tex[0]
            PocShared.texB = tex[1]
            for (t in tex) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    PocShared.TEX_W, PocShared.TEX_H, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
                )
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            val fbo = IntArray(2)
            GLES20.glGenFramebuffers(2, fbo, 0)
            PocShared.fboA = fbo[0]
            PocShared.fboB = fbo[1]
            attach(fbo[0], tex[0], "A")
            attach(fbo[1], tex[1], "B")
            PocShared.countGlError("createSharedObjects")
        }

        private fun attach(f: Int, t: Int, tag: String) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t, 0
            )
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.e(PocShared.TAG, "FBO $tag incomplete: 0x${Integer.toHexString(status)}")
            }
        }

        /**
         * 像素级校验：读回 texB 中心像素，与「该帧应写入的值」比对。
         *
         * 帧号步长 8 → 若 fence 失效读到旧帧，差异必为 8 的倍数，与浮点误差（±1）区分明显。
         */
        private fun verify(expectedFrame: Int) {
            if (expectedFrame < 0) return
            val buf = pxBuf ?: return
            buf.clear()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, PocShared.fboB)
            GLES20.glReadPixels(
                PocShared.TEX_W / 2, PocShared.TEX_H / 2, 1, 1,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
            )
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            val got = buf.get(0).toInt() and 0xFF
            val expected = (expectedFrame * PocShared.FRAME_STEP) and 0xFF
            if (kotlin.math.abs(got - expected) > 2) {
                val n = PocShared.mismatches.incrementAndGet()
                PocShared.lastMismatch = "frame=$expectedFrame got=$got exp=$expected"
                if (n <= 5) {
                    Log.w(PocShared.TAG, "MISMATCH frame=$expectedFrame got=$got expected=$expected")
                }
            }
        }

        private fun buildScreenProgram() {
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
                Log.e(PocShared.TAG, "screen program link failed: " + GLES20.glGetProgramInfoLog(program))
            }
            aPosLoc = GLES20.glGetAttribLocation(program, "aPos")
            aTexLoc = GLES20.glGetAttribLocation(program, "aTex")
            uTexLoc = GLES20.glGetUniformLocation(program, "uTex")
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

        private fun drawTex(tex: Int) {
            if (program == 0 || tex == 0) return
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glUniform1i(uTexLoc, 0)
            val q = quad ?: return
            q.position(0)
            GLES20.glVertexAttribPointer(aPosLoc, 2, GLES20.GL_FLOAT, false, 16, q)
            GLES20.glEnableVertexAttribArray(aPosLoc)
            q.position(2)
            GLES20.glVertexAttribPointer(aTexLoc, 2, GLES20.GL_FLOAT, false, 16, q)
            GLES20.glEnableVertexAttribArray(aTexLoc)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(aPosLoc)
            GLES20.glDisableVertexAttribArray(aTexLoc)
        }
    }
}
