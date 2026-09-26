package com.example.vr.huawei

import android.app.Activity
import android.content.Intent
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Color
import android.util.TypedValue
import com.example.vr.VRGLRenderer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 华为 VR Glass 播放 Activity（P1 完成版）。
 *
 * === 它为什么存在 ===
 * 华为官方的「2D 应用 → VR」通道是：应用自己声明一个响应
 * `action = com.huawei.android.vr.PROMPT` 的 Activity，
 * 外部用 `Intent().setPackage(pkg).setAction(ACTION)` 拉起它。
 * 插入眼镜后，系统会跳过 VrLauncher 直接进入本 Activity 的 VR 会话。
 *
 * === 渲染架构（P1 完成的闭环）===
 * ```
 * HuaweiVrActivity                      VRGLRenderer（GL 线程）
 *   │                                        │
 *   ├─ native.acquireEyeTargets()  ──获取→   │  （本帧双眼 swapchain 就绪）
 *   ├─ glSurfaceView.queueEvent {            │
 *   │      renderer.updateHuaweiEyeTargets() │
 *   │      renderer.drawHuaweiVrFrame()      │  逐眼 bindEyeFramebuffer → 画 → unbind
 *   │   }                                    │
 *   └─ native.submitFrame()  ──────────→     │  release + xrEndFrame（上屏）
 * ```
 * ⚠️ **native 的 acquire / submit 与 GL 绘制必须严格配对**：
 *    acquire 之后立刻 queueEvent 让 GL 线程画，画完再 submit。
 *    顺序颠倒（先 submit 再画）会画到已经被释放的 image 上，眼镜内表现为画面撕裂或全黑。
 *
 * === 生命周期铁律（参考项目实证，见报告 5.6 节）===
 * `onStop` 即 `finishAndRemoveTask()` + `killProcess`。
 * 原因：华为 VR Runtime 会认为「持有 session 的进程」还在用眼镜，
 * 不干净退出会导致下次插入眼镜黑屏 / 状态残留。
 * ⚠️ 因此**退出前必须先把设置落盘**（本类在 stop 前调用 onBeforeKill 回调）。
 */
class HuaweiVrActivity : Activity() {

    companion object {
        private const val TAG = "HuaweiVrActivity"

        /** 官方 2D→VR 通道的 action（与 `buildHuaweiVrPromptIntent` 一致） */
        const val ACTION_VR_PROMPT = "com.huawei.android.vr.PROMPT"

        /** 渲染分辨率档位（相对 Runtime 推荐值） */
        const val EXTRA_RENDER_SCALE = "render_scale_percent"

        /**
         * 是否由 Kotlin 负责把画面画进 swapchain texture（真机 3D 贴片通路）。
         * 默认 true；若无眼镜/无 Runtime 想安全自测，可传 false 只走姿态跟随。
         */
        const val EXTRA_EXTERNAL_RENDERER = "external_renderer"

        /**
         * 在进程被杀之前执行的「保存设置」回调。
         *
         * ⚠️ 为什么需要它：本 Activity 退出会 `killProcess`，如果设置还没落盘就全丢了。
         * 由 `MainActivity` / `VRPlayerScreen` 在启动本 Activity 时注入。
         * 用可空静态引用是刻意的：进程重启后引用为空，不会泄漏 Activity。
         */
        @Volatile
        var onBeforeKill: (() -> Unit)? = null

        /**
         * 视频源接线回调（P1 闭环的关键）。
         *
         * 调用方（`VRPlayerScreen`）在这里做两件事：
         * 1. 把 ExoPlayer 的输出指向传入的 [SurfaceTexture]（`player.setVideoSurface(Surface(st))`）
         * 2. 把视频宽高告知 [VRGLRenderer]（`renderer.videoWidth/Height` + `isVideoActive = true`）
         *
         * 用静态回调是为了跨进程内 Activity 边界传递，且避免持有 Activity 引用泄漏。
         */
        @Volatile
        var onVideoSurfaceNeeded: ((SurfaceTexture) -> Unit)? = null
    }

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: VRGLRenderer
    private lateinit var statusText: TextView

    private var initialized = false
    private var sessionRunning = false

    /** 是否由 Kotlin 画（见 [EXTRA_EXTERNAL_RENDERER]） */
    private var externalRenderer = true

    /** 帧泵线程：acquire → 让 GL 线程画 → submit */
    private var pumpThread: Thread? = null

    @Volatile private var pumping = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate, intent.action=${intent?.action}")

        val renderScale = intent?.getIntExtra(EXTRA_RENDER_SCALE, 100) ?: 100
        externalRenderer = intent?.getBooleanExtra(EXTRA_EXTERNAL_RENDERER, true) ?: true

        // 官方示例在 Activity 创建时先调一次 LibUpdateClient.runUpdate()，
        // 用于向系统登记/刷新本应用的 VR 能力（hvrbridge.jar 提供）。
        // ⚠️ 失败不是致命错误（部分系统版本不需要），只记录日志。
        runCatching {
            val cls = Class.forName("com.huawei.hvr.LibUpdateClient")
            val ctor = cls.getConstructor(Activity::class.java)
            val client = ctor.newInstance(this)
            cls.getMethod("runUpdate").invoke(client)
            Log.i(TAG, "LibUpdateClient.runUpdate() 已调用")
        }.onFailure { Log.i(TAG, "LibUpdateClient 不可用（可忽略）: ${it.message}") }

        // ---- 极简 UI：GLSurfaceView（承载我们的渲染器）+ 状态文字（调试期可见）----
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // ⚠️ GLES 版本必须 ≥3，与 native 侧 createEglContext 的 ES3 优先策略一致；
        //    用 ES2 会在部分机型上拿不到 OES 视频纹理的完整能力。
        renderer = VRGLRenderer(this)
        renderer.huaweiVrMode = externalRenderer

        // ⚠️ 视频源接线回调必须在 setRenderer() **之前**挂上。
        //    原因：GL 线程的 Renderer.onSurfaceCreated（内部会触发 onVideoSurfaceCreated）
        //    可能在 setRenderer() 之后的任意时刻就跑完了；若等到 startFramePump() 才赋值，
        //    回调早就错过了 → ExoPlayer 永远不会被切到华为侧的 SurfaceTexture → 眼镜里恒定黑屏。
        renderer.onVideoSurfaceCreated = { st ->
            Log.i(TAG, "渲染器 SurfaceTexture 就绪，交给播放器")
            renderer.isVideoActive = true
            if (externalRenderer) {
                runOnUiThread {
                    runCatching { onVideoSurfaceNeeded?.invoke(st) }
                        .onFailure { Log.e(TAG, "视频源接线失败", it) }
                }
            } else {
                // 非 external 模式：视频帧只用于姿态跟随期的预览，不接线
                Log.i(TAG, "非 external 渲染模式，跳过视频源接线")
            }
        }

        glSurfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            // ⚠️ 华为模式下帧由 pumpThread 驱动（RENDERMODE_WHEN_DIRTY），
            //    连续模式会与 pumpThread 抢 framebuffer，造成画面抖动。
            renderMode = if (externalRenderer) GLSurfaceView.RENDERMODE_WHEN_DIRTY
                         else GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        root.addView(
            glSurfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            text = "华为 VR 初始化中…"
        }
        root.addView(
            statusText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.TOP
            ).apply { setMargins(24, 24, 24, 24) }
        )

        setContentView(root)

        glSurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "surfaceCreated")
                // ⚠️ 顺序不可颠倒：先把 Surface 交给 native（EGL window surface 的来源），
                //    再初始化 OpenXR 会话。华为的 EGL 需要 window surface 才能上屏。
                HuaweiVrNative.setSurface(holder.surface)
                startVrSession(renderScale)
            }

            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {
                Log.i(TAG, "surfaceChanged ${w}x$ht")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "surfaceDestroyed")
                stopVrSession()
                // Surface 已销毁，通知 native 释放原生窗口引用
                HuaweiVrNative.setSurface(null)
            }
        })
    }

    private fun startVrSession(renderScalePercent: Int) {
        if (initialized) return

        if (!HuaweiVrNative.isLibraryLoaded) {
            showStatus("native 库未加载，无法启动华为 VR")
            return
        }

        if (!HuaweiVrNative.isSdkBuiltIn) {
            // SDK 未接入（桩实现）——给出明确提示而不是黑屏
            showStatus(
                "华为 OpenXR SDK 未接入\n" +
                "请在 local.properties 配置 huawei.vr.sdk.dir 后重新构建"
            )
            return
        }

        val ok = HuaweiVrNative.initialize(this, renderScalePercent)
        if (!ok) {
            val reason = HuaweiVrNative.lastError()
            Log.e(TAG, "initialize 失败: $reason")
            showStatus("华为 VR 初始化失败：\n$reason")
            return
        }

        val eye = HuaweiVrNative.recommendEyeSize()
        Log.i(TAG, "推荐每眼尺寸: ${eye.getOrNull(0)}x${eye.getOrNull(1)}")

        // ⚠️ 必须在 start() 之前决定渲染归属：帧循环线程一启动就会按该标志走不同分支
        HuaweiVrNative.setExternalRendererEnabled(externalRenderer)

        if (!HuaweiVrNative.start()) {
            showStatus("帧循环启动失败：\n${HuaweiVrNative.lastError()}")
            return
        }

        initialized = true
        sessionRunning = true

        // 每眼尺寸告知渲染器（用于 aspect 计算）
        renderer.updateHuaweiEyeSize(
            eye.getOrNull(0) ?: 0,
            eye.getOrNull(1) ?: 0
        )

        if (externalRenderer) startFramePump()

        showStatus(
            "华为 VR 已启动（每眼 ${eye.getOrNull(0)}×${eye.getOrNull(1)}）\n" +
            if (externalRenderer) "渲染：Kotlin 双眼直渲" else "渲染：仅姿态跟随"
        )
    }

    /**
     * 帧泵：把 native 的「acquire」与 GL 线程的「画」串起来。
     *
     * ⚠️ 为什么必须有它：swapchain image 的 acquire/release 必须成对且在同一帧内，
     * 而 GL 绘制只能在 GL 线程做。所以顺序固定为：
     *   native.acquire（本线程）→ queueEvent 让 GL 线程画 → native.submit（本线程）
     * 若把三步都塞进 GL 线程，acquire 的阻塞等待会卡住 GL 上下文；
     * 若先 submit 再画，则画到已释放的 image 上 → 眼镜内撕裂/全黑。
     */
    private fun startFramePump() {
        if (pumping) return
        pumping = true

        // ⚠️ 视频源接线回调已在 onCreate 中（setRenderer 之前）挂好，此处不再重复赋值。
        pumpThread = Thread({
            Log.i(TAG, "帧泵线程进入")
            while (pumping) {
                try {
                    // 1) 等本帧可用（native 内部含 xrWaitFrame，会随显示刷新自然节流到 ~70Hz）
                    if (!HuaweiVrNative.hasPendingFrame()) {
                        // 还没有待提交帧 → 让 native 先 acquire 一轮
                        val eyes = HuaweiVrNative.eyeTargetsEx()
                        if (eyes == null) {
                            Thread.sleep(2)
                            continue
                        }
                    }

                    val targets = HuaweiVrNative.eyeTargetsEx() ?: run {
                        Thread.sleep(2)
                        continue
                    }

                    if (!HuaweiVrNative.hasPendingFrame()) {
                        Thread.sleep(2)
                        continue
                    }

                    // 2) 交给 GL 线程画（queueEvent 是异步的 → 用 CountDownLatch 等它画完）
                    val done = java.util.concurrent.CountDownLatch(1)
                    glSurfaceView.queueEvent {
                        try {
                            renderer.updateHuaweiEyeTargets(targets)
                            val drew = renderer.drawHuaweiVrFrame()
                            if (!drew) Log.w(TAG, "本帧未画出任何一眼")
                        } catch (t: Throwable) {
                            Log.e(TAG, "GL 线程绘制异常", t)
                        } finally {
                            done.countDown()
                        }
                    }
                    // 最多等 100ms（约 7 帧 @70Hz），超时说明 GL 卡住，跳过本帧避免死等
                    if (!done.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        Log.w(TAG, "GL 绘制等待超时，跳过本帧")
                        continue
                    }

                    // 3) 画完了才提交（release + xrEndFrame）
                    HuaweiVrNative.submitFrame()

                } catch (_: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    Log.e(TAG, "帧泵异常", t)
                    try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                }
            }
            Log.i(TAG, "帧泵线程退出")
        }, "AuraHuaweiVrPump").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopFramePump() {
        pumping = false
        pumpThread?.let {
            it.interrupt()
            runCatching { it.join(500) }
        }
        pumpThread = null
    }

    private fun stopVrSession() {
        if (!initialized) return
        stopFramePump()
        // ⚠️ 先落盘设置，再关会话（killProcess 在 onStop 里，顺序不能反）
        runCatching { onBeforeKill?.invoke() }
            .onFailure { Log.e(TAG, "onBeforeKill 回调失败", it) }

        HuaweiVrNative.shutdown()
        initialized = false
        sessionRunning = false
    }

    private fun showStatus(text: String) {
        runOnUiThread { statusText.text = text }
    }

    /**
     * ⚠️ 华为铁律：onStop 立即结束任务并杀进程。
     * 参考项目 LHT02/HuaweiVRGlass-ALVR 实证——不这样做会导致下次进 VR 状态异常。
     */
    override fun onStop() {
        Log.i(TAG, "onStop → finishAndRemoveTask + killProcess")
        stopVrSession()
        super.onStop()

        finishAndRemoveTask()
        Process.killProcess(Process.myPid())
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        stopVrSession()
        super.onDestroy()
    }

    /** 供外部（如 MainActivity）构造启动 Intent，保持 action 字符串单点定义 */
    fun newIntent(
        renderScalePercent: Int = 100,
        externalRenderer: Boolean = true
    ): Intent =
        Intent(ACTION_VR_PROMPT).apply {
            setClass(this@HuaweiVrActivity, HuaweiVrActivity::class.java)
            putExtra(EXTRA_RENDER_SCALE, renderScalePercent)
            putExtra(EXTRA_EXTERNAL_RENDERER, externalRenderer)
        }
}
