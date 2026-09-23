package com.example.vr

import android.content.Context
import android.util.Log
import com.pixpark.gpupixel.FaceDetector
import com.pixpark.gpupixel.GPUPixel
import com.pixpark.gpupixel.GPUPixelFilter
import com.pixpark.gpupixel.GPUPixelSinkRawData
import com.pixpark.gpupixel.GPUPixelSourceRawData

/** 美颜引擎标识（prefs 里存 Int，避免跨文件枚举依赖） */
const val BEAUTY_ENGINE_GLSL = 0
const val BEAUTY_ENGINE_GPUPIXEL = 1

/**
 * v2.0.160：GPUPixel 美颜引擎封装（双引擎方案之 GPUPixel 侧）。
 *
 * 与 GLSL 引擎的关系 —— **完全独立**（按需求不复用）：
 *  - 人脸检测用 GPUPixel 自带的 Mars-Face（[FaceDetector]），不共享 MediaPipe 的任何结果；
 *  - 参数走独立的 `beauty_gp_*` prefs（见 VRPlayerScreen）；
 *  - 渲染走独立的「人脸区域回读 → 处理 → glTexSubImage2D 贴回」链路，GLSL 的美颜 uniform 侧关闭。
 *
 * 链路（AAR 提供的唯一 Java 通道是 raw-data 模式，进出都是 byte[]）：
 * ```
 * RGBA bytes → GPUPixelSourceRawData.ProcessData
 *            → BeautyFaceFilter（磨皮 blur_alpha / 美白 white / 锐化 sharpen）
 *            → FaceReshapeFilter（瘦脸 / 大眼，吃 Mars-Face 的 landmarks）
 *            → GPUPixelSinkRawData.GetRgbaBuffer()
 * ```
 * GPUPixel 内部自建 GL 上下文 —— 正好**绕开了**与 VRGLRenderer 的 EGL 上下文共享问题
 * （这是原规划里风险最高的 P0 项，raw-data 模式让它不再是问题）。
 *
 * ABI 说明：官方预编译 AAR（v1.3.1）只含 arm64-v8a / armeabi-v7a，**没有 x86_64**，
 * 模拟器上 native 库缺失 → [available] 为 false → 上层弹提示并自动回退 GLSL。
 *
 * ⚠️ property 名（blur_alpha / white / …）按 C++ 端字段名推断
 * （`thin_face_delta_` / `big_eye_delta_` 可从头文件确认），真机 POC 时需对照官方 demo 核对。
 */
object GpuPixelBeauty {
    private const val TAG = "GpuPixelBeauty"

    /** init 成功时为 true；失败由上层提示并保持 GLSL（v2.0.163 起不再做 ABI 门槛） */
    @Volatile
    var available: Boolean = false
        private set

    /** 上次 init 失败的时刻（自动重试的 60s 冷却；UI 主动点击可跳过） */
    @Volatile
    private var lastFailedMs = 0L

    private var pipeline: Pipeline? = null

    /**
     * 初始化（幂等、绝不抛异常）。
     *
     * v2.0.163：按用户决策**取消 ABI 门槛** —— 任何设备都允许尝试 GPUPixel
     * （MuMu 的 houdini 转译环境实测也能运行 mars 库，之前的闪退根因是
     * 错误的 property key + 空 landmarks，已修复）。初始化失败时 [available] 保持
     * false，上层 Toast 提示并保持 GLSL 引擎。
     *
     * 失败重试：UI 主动点击传 [force]=true 立即重试；onDrawFrame 自动兜底走 60s 冷却
     * （避免每帧重试 loadLibrary）。
     *
     * @param force 用户主动点击引擎按钮时传 true
     */
    @Synchronized
    fun init(context: Context, force: Boolean = false): Boolean {
        if (available) return true
        if (!force && lastFailedMs != 0L &&
            android.os.SystemClock.uptimeMillis() - lastFailedMs < 60_000L
        ) {
            return false
        }
        return try {
            // GPUPixel.Init 会把 AAR assets 里的 Mars-Face 模型拷到应用外部目录
            GPUPixel.Init(context.applicationContext)
            available = true
            Log.i(TAG, "GPUPixel initialized")
            true
        } catch (e: Throwable) {
            lastFailedMs = android.os.SystemClock.uptimeMillis()
            Log.e(TAG, "GPUPixel init failed", e)
            false
        }
    }

    @Synchronized
    fun getOrCreatePipeline(): Pipeline {
        if (pipeline == null) pipeline = Pipeline()
        return pipeline!!
    }

    @Synchronized
    fun release() {
        pipeline?.release()
        pipeline = null
    }

    /** 一条处理链：输入 RGBA → 磨皮/美白 → 美型（吃 landmarks）→ 输出 RGBA */
    class Pipeline {
        private var source: GPUPixelSourceRawData? = null
        private var beauty: GPUPixelFilter? = null
        private var reshape: GPUPixelFilter? = null
        private var sink: GPUPixelSinkRawData? = null
        private var detector: FaceDetector? = null

        @Synchronized
        private fun ensure() {
            if (source != null && sink != null) return
            source = GPUPixelSourceRawData.Create()
            beauty = GPUPixelFilter.Create(GPUPixelFilter.BEAUTY_FACE_FILTER)
            reshape = GPUPixelFilter.Create(GPUPixelFilter.FACE_RESHAPE_FILTER)
            sink = GPUPixelSinkRawData.Create()
            // 串联滤镜链：source → beauty → reshape → sink
            source?.AddSink(beauty)
            beauty?.AddSink(reshape)
            reshape?.AddSink(sink)
        }

        /**
         * 对一帧 RGBA 做独立检测 + 美颜。
         *
         * @param stride 每行字节数（= w * 4）
         * @return 处理后的 RGBA（同尺寸）；任何失败返回 null（上层保持原帧不变）
         */
        @Synchronized
        fun process(
            rgba: ByteArray,
            w: Int,
            h: Int,
            stride: Int,
            smooth: Float,
            white: Float,
            sharpen: Float,
            slim: Float,
            eyeZoom: Float
        ): ByteArray? {
            if (!GpuPixelBeauty.available) return null
            return try {
                ensure()
                beauty?.SetProperty("blur_alpha", smooth)
                beauty?.SetProperty("white", white)
                beauty?.SetProperty("sharpen", sharpen)
                // 独立检测：Mars-Face（不复用 MediaPipe）。
                // ⚠️ 参数顺序：Java 签名 detect(data, w, h, stride, format(MODE_FMT), frameType(FRAME_TYPE))
                // —— v2.0.160 曾把两者传反（侥幸两个常量都是 0 没出错），这里摆正。
                val det = detector ?: FaceDetector.Create().also { detector = it }
                val landmarks = det.detect(
                    rgba, w, h, stride,
                    FaceDetector.GPUPIXEL_MODE_FMT_VIDEO,
                    FaceDetector.GPUPIXEL_FRAME_TYPE_RGBA
                )
                // 官方 demo 的 key 是 **face_landmark**（单数）—— 之前写成 face_landmarks 属于未知 key；
                // 且 detect 返回空时**不喂** native（空 FloatArray 对未验证的 native 路径有风险）
                if (landmarks != null && landmarks.isNotEmpty()) {
                    reshape?.SetProperty("face_landmark", landmarks)
                    reshape?.SetProperty("thin_face_delta", slim)
                    reshape?.SetProperty("big_eye_delta", eyeZoom)
                }
                source?.ProcessData(
                    rgba, w, h, stride,
                    GPUPixelSourceRawData.FRAME_TYPE_RGBA
                )
                sink?.GetRgbaBuffer()
            } catch (e: Throwable) {
                Log.e(TAG, "GPUPixel process failed", e)
                null
            }
        }

        @Synchronized
        fun release() {
            try {
                source?.Destroy()
                beauty?.Destroy()
                reshape?.Destroy()
                sink?.Destroy()
                detector?.destroy()
            } catch (_: Throwable) {
            }
            source = null
            beauty = null
            reshape = null
            sink = null
            detector = null
        }
    }
}
