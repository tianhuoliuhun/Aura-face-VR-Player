package com.example.vr

import android.content.Context
import android.os.Build
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

    /** init 成功且 ABI 受支持时为 true；否则上层必须回退 GLSL 并提示 */
    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    private var initTried = false

    private var pipeline: Pipeline? = null

    /** 当前设备 ABI 是否在官方预编译包的覆盖范围内 */
    fun isAbiSupported(): Boolean =
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "armeabi-v7a" }

    /**
     * 初始化（幂等、绝不抛异常）。失败时 [available] 保持 false，
     * 上层据此弹「GPUPixel 不可用，已切回 GLSL」并自动切换。
     */
    @Synchronized
    fun init(context: Context): Boolean {
        if (available) return true
        if (initTried) return false
        initTried = true
        if (!isAbiSupported()) {
            Log.w(TAG, "GPUPixel unavailable: ABIs=${Build.SUPPORTED_ABIS.contentToString()}")
            return false
        }
        return try {
            // GPUPixel.Init 会把 AAR assets 里的 Mars-Face 模型拷到应用目录（copyResource 同理）
            GPUPixel.Init(context.applicationContext)
            available = true
            Log.i(TAG, "GPUPixel initialized (arm64-v8a/armeabi-v7a)")
            true
        } catch (e: Throwable) {
            // UnsatisfiedLinkError（x86_64 / so 缺失）等一律归为「不可用」
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
                // 独立检测：Mars-Face（不复用 MediaPipe）
                val det = detector ?: FaceDetector.Create().also { detector = it }
                val landmarks = det.detect(
                    rgba, w, h, stride,
                    FaceDetector.GPUPIXEL_FRAME_TYPE_RGBA,
                    FaceDetector.GPUPIXEL_MODE_FMT_VIDEO
                )
                reshape?.SetProperty("face_landmarks", landmarks ?: FloatArray(0))
                reshape?.SetProperty("thin_face_delta", slim)
                reshape?.SetProperty("big_eye_delta", eyeZoom)
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
