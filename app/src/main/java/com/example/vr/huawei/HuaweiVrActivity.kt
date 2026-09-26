package com.example.vr.huawei

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.TextView
import android.view.Gravity
import android.graphics.Color
import android.util.TypedValue

/**
 * 华为 VR Glass 播放 Activity（P0 骨架）。
 *
 * === 它为什么存在 ===
 * 华为官方的「2D 应用 → VR」通道是：应用自己声明一个响应
 * `action = com.huawei.android.vr.PROMPT` 的 Activity，
 * 外部用 `Intent().setPackage(pkg).setAction(ACTION)` 拉起它。
 * 插入眼镜后，系统会跳过 VrLauncher 直接进入本 Activity 的 VR 会话。
 *
 * 参考项目 `LHT02/HuaweiVRGlass-ALVR` 的 `HuaweiVrActivity.java` 结构极薄：
 *   Activity + SurfaceView + `new LibUpdateClient(this).runUpdate()` + System.loadLibrary
 * 本类按同样思路，但渲染交给 native OpenXR 会话（路径 A′）。
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
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView

    private var initialized = false

    /** 是否由 Kotlin 画（见 [EXTRA_EXTERNAL_RENDERER]） */
    private var externalRenderer = true

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

        // ---- 极简 UI：SurfaceView（承载 GL）+ 状态文字（调试期可见）----
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        surfaceView = SurfaceView(this)
        root.addView(
            surfaceView,
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

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
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
            // SDK 未接入（桩实现）——P0 阶段会走到这里，给出明确提示而不是黑屏
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
        showStatus(
            "华为 VR 已启动（每眼 ${eye.getOrNull(0)}×${eye.getOrNull(1)}）\n" +
            if (externalRenderer) "渲染：Kotlin 3D 贴片" else "渲染：仅姿态跟随"
        )
    }

    private fun stopVrSession() {
        if (!initialized) return
        // ⚠️ 先落盘设置，再关会话（killProcess 在 onStop 里，顺序不能反）
        runCatching { onBeforeKill?.invoke() }
            .onFailure { Log.e(TAG, "onBeforeKill 回调失败", it) }

        HuaweiVrNative.shutdown()
        initialized = false
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
