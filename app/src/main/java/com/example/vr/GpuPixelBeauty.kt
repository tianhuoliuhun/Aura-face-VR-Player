package com.example.vr

import android.content.Context
import android.util.Log
import com.pixpark.gpupixel.FaceDetector
import com.pixpark.gpupixel.GPUPixel
import com.pixpark.gpupixel.GPUPixelFilter
import com.pixpark.gpupixel.GPUPixelSinkRawData
import com.pixpark.gpupixel.GPUPixelSinkTexture
import com.pixpark.gpupixel.GPUPixelSourceTexture
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

    /**
     * v2.0.187：是否使用 **texture 零拷贝通道**（输入 + 输出都走 GPU 纹理）。
     *
     * ⚠️ 2026-09-29 临时回退：MuMu 上实测「CPU 侧开销极低（gpu 0ms/rb 2ms）、帧率正常，
     * 但体感卡顿」—— 判断问题在 **GPU 层面**（主渲染采样跨 context 的结果纹理时的隐式
     * 同步，在 MuMu 的 houdini 转译层被放大），故先置 false 回到稳定的 raw-data 通道，
     * 待定位后再开。置 true 即可恢复零拷贝实验通道。
     *
     * 依赖「GPUPixel 与主渲染共享 EGLContext」—— 应用侧需在 GL 线程先调用
     * [com.pixpark.gpupixel.GPUPixel.captureSharedEglContext]。共享未生效时，
     * 主渲染 context 里 `glIsTexture` 会返回 false，应用侧自动调用
     * `Pipeline.fallbackToRawDataSink()` 切回 CPU 回读，功能不受影响。
     */
    @Volatile
    var useTextureSink = false

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

    /** 一条处理链：输入 RGBA → 磨皮/美白 → 美型（吃 landmarks）→ 输出 RGBA / texture */
    class Pipeline {
        /** v2.0.187 阶段2：输入侧 texture 通道（替代 CPU 上传） */
        private var sourceTexture: GPUPixelSourceTexture? = null
        /** 回退路径：CPU 上传 + 回读（v2.0.187 之前的方式） */
        private var source: GPUPixelSourceRawData? = null
        private var beauty: GPUPixelFilter? = null
        private var reshape: GPUPixelFilter? = null
        /** 回退路径：CPU 回读（v2.0.187 之前的唯一方式） */
        private var sink: GPUPixelSinkRawData? = null
        /** v2.0.187：texture 输出通道 —— 结果留在 GPU 上，供共享 context 的主渲染直接采样 */
        private var sinkTexture: GPUPixelSinkTexture? = null
        private var detector: FaceDetector? = null

        /** 本次链是否走 texture 通道（false = raw-data 通道） */
        @Volatile private var textureMode = false

        /**
         * texture 通道的结果（faceExecutor 写、GL 线程读）。
         *
         * 与 [gpRegionPending] 的语义对应：`resultSerial` 变化即表示「产生了新结果」，
         * 正是 v2.0.185 保底帧机制所需的那个判据。
         */
        @Volatile var resultTextureId = 0
        @Volatile var resultSerial = 0L

        /**
         * v2.0.188：GPUPixel **锐化**级别（0~1）。
         *
         * 由上层在参数同步处赋值（而不是给每个 process 方法加参数，避免签名四处扩散）。
         *
         * ⚠️ 依赖 fork 给 `BeautyFaceFilter` **注册**的 `sharpen` 属性：上游只在 Init() 里
         * 注册了 `whiteness` / `skin_smoothing`，虽然 `SetSharpen()` 方法一直存在，
         * 但未注册时 `SetProperty("sharpen", ...)` 会被 `Filter::SetProperty` **静默忽略**
         * （只打一条 LOG_WARN）—— 这正是「GPUPixel 锐化」滑块此前完全无效的原因。
         * fork 侧已补注册（`beauty_face_filter.cc`）。
         */
        @Volatile var sharpenLevel = 0f

        /** 锐化值缓存：仅在变化时 SetProperty（与其它参数同策略） */
        private var lastSharpen = Float.NaN

        // ===== v2.0.186（P0-B）：检测与 GL 处理分离，支持两线程并行 =====
        /**
         * GL 管线锁：保护 source / beauty / reshape / sink 这一串 GPUPixel 调用。
         *
         * ⚠️ 为什么不用 `@Synchronized`（= 锁 Pipeline 实例）：检测（Mars-Face，纯 CPU）
         * 与 GL 处理（走 GPUPixel 独立 EGL context）使用的是**完全不同的底层资源**，
         * 仅共享只读的输入像素数组 —— 若共用一把实例锁，两段就会被强制串行，
         * 并行的意义（T_proc ≈ max 而非 sum）就没了。故拆成两把独立锁。
         */
        private val glLock = Any()

        /** 检测锁：Mars-Face 检测器非线程安全，但其调用者只有检测线程，锁用于兜底 */
        private val detectLock = Any()

        // ===== v2.0.186（P2-E2）：属性值缓存，仅在变化时 SetProperty =====
        // 原先每帧无条件 SetProperty（每次 = JNI 往返 + std::string 构造 + map 查找），
        // 而这些值只在用户拖动滑条时才变 —— 稳态下 100% 是重复劳动。
        private var lastSmooth = Float.NaN
        private var lastWhite = Float.NaN
        private var lastSlim = Float.NaN
        private var lastEye = Float.NaN
        private var lastHasFace: Boolean? = null
        /** 上次喂给 reshape 的 landmarks 引用；引用未变则无需重复 SetProperty */
        private var lastLandmarks: FloatArray? = null

        // ===== v2.0.186（P1-D）：landmarks 时序平滑（alpha-beta 预测-校正） =====
        // 比简单 lerp 更适合「检测降频」场景：降频后 landmark 呈阶梯状更新，
        // 预测项 v 能在两次真检之间把位置推着走，观感比纯保持/纯插值都平滑。
        private val smPos = FloatArray(1024)
        private val smVel = FloatArray(1024)
        private var smCount = 0
        private var smInit = false

        @Synchronized
        private fun ensure() {
            if ((source != null || sourceTexture != null) &&
                (sink != null || sinkTexture != null)
            ) {
                return
            }
            beauty = GPUPixelFilter.Create(GPUPixelFilter.BEAUTY_FACE_FILTER)
            reshape = GPUPixelFilter.Create(GPUPixelFilter.FACE_RESHAPE_FILTER)

            // v2.0.187：优先用 texture 通道（输入 + 输出都零拷贝，需与主渲染共享 EGLContext）。
            // 是否真的生效由应用侧在主渲染 context 里用 glIsTexture 校验 —— 校验失败时
            // 由 GL 线程调用 fallbackToRawDataSink() 把整条链切回 CPU 回读 + 上传。
            textureMode = GpuPixelBeauty.useTextureSink
            if (textureMode) {
                // ---- texture 通道：source → reshape → beauty → sink（全程不落 CPU）----
                sourceTexture = GPUPixelSourceTexture.Create()
                sourceTexture?.AddSink(reshape)
                reshape?.AddSink(beauty)
                sinkTexture = GPUPixelSinkTexture.Create()
                beauty?.AddSink(sinkTexture)
                Log.i(TAG, "pipeline -> [texture] SourceTexture -> ... -> SinkTexture (zero-copy)")
            } else {
                // ---- raw-data 通道：与 v2.0.186 完全一致 ----
                // v2.0.164：按官方文档的链顺序 —— source → reshape → beauty → sink
                // （官方示例里美型在美颜之前，之前接反了）
                source = GPUPixelSourceRawData.Create()
                source?.AddSink(reshape)
                reshape?.AddSink(beauty)
                sink = GPUPixelSinkRawData.Create()
                beauty?.AddSink(sink)
                Log.i(TAG, "pipeline -> [raw-data] SourceRawData -> ... -> SinkRawData")
            }
        }

        /**
         * v2.0.187：切回 raw-data 通道。
         *
         * 触发条件：GL 线程检测到 `glIsTexture(resultTextureId)` 为 false —— 说明
         * GPUPixel 并未真正与主渲染共享 context（共享创建失败已回退私有 context，
         * 或驱动拒绝），此时 texture id 对外部不可见，必须切回 CPU 回读。
         */
        @Synchronized
        fun fallbackToRawDataSink() {
            if (!textureMode) return
            // 拆掉 texture 通道的**输出** sink
            val st = sinkTexture
            if (st != null) {
                try {
                    beauty?.RemoveSink(st)
                    st.Destroy()
                } catch (e: Throwable) {
                    Log.w(TAG, "destroy SinkTexture failed", e)
                }
                sinkTexture = null
            }
            // 拆掉 texture 通道的**输入** source
            val srcT = sourceTexture
            if (srcT != null) {
                try {
                    srcT.RemoveAllSinks()
                    srcT.Destroy()
                } catch (e: Throwable) {
                    Log.w(TAG, "destroy SourceTexture failed", e)
                }
                sourceTexture = null
            }
            // 换成 raw-data 的 source + sink（保留已有的 reshape/beauty 滤镜实例）
            source = GPUPixelSourceRawData.Create()
            source?.AddSink(reshape)
            sink = GPUPixelSinkRawData.Create()
            beauty?.AddSink(sink)
            textureMode = false
            resultTextureId = 0
            resultSerial = 0L
            Log.w(TAG, "fallback: pipeline -> [raw-data] SourceRawData/SinkRawData")
        }

        /** 当前是否走 texture 通道（GL 线程据此决定贴回方式） */
        fun isTextureMode(): Boolean = textureMode

        /** 是否已产出过可用的结果 texture */
        fun hasTextureResult(): Boolean = resultTextureId != 0

        /**
         * v2.0.187：把「外部已消费完当前结果」的 fence 回传给 SinkTexture，
         * 使其在下一次写入前等待 —— 防止覆盖主渲染正在采样的那张。
         *
         * @param fence 由**主渲染 context** 创建的 GLsync 句柄；0 表示清除
         */
        fun setConsumerFence(fence: Long) {
            sinkTexture?.SetConsumerFence(fence)
        }

        /** fence 等待失败次数（诊断） */
        fun getFenceWaitFailures(): Int = sinkTexture?.GetFenceWaitFailures() ?: 0

        /**
         * 下发美颜/美型参数（**必须先持有 [glLock]**）。
         *
         * 抽成独立方法是为了让 [processFrame]（raw-data）与 [processFrameTexture]
         * 共用同一套参数语义，避免两处实现漂移。
         */
        private fun applyParamsLocked(
            smooth: Float,
            white: Float,
            slim: Float,
            eyeZoom: Float,
            landmarks: FloatArray?
        ) {
            // v2.0.186（P2-E2）：值变了才设 —— 稳态下（滑条不动）这几行全部跳过
            val b = beauty
            if (smooth != lastSmooth) {
                b?.SetProperty("skin_smoothing", smooth); lastSmooth = smooth
            }
            if (white != lastWhite) {
                b?.SetProperty("whiteness", white); lastWhite = white
            }
            // v2.0.188：锐化（依赖 fork 注册的 `sharpen` 属性；未注册时会被静默忽略）
            if (sharpenLevel != lastSharpen) {
                b?.SetProperty("sharpen", sharpenLevel); lastSharpen = sharpenLevel
            }
            // 美型：landmark 引用未变则不重复下发（检测降频时会连续几帧同引用）
            val hasFace = landmarks != null && landmarks.isNotEmpty()
            val r = reshape
            if (hasFace) {
                val lm = landmarks!!
                if (lm !== lastLandmarks) {
                    r?.SetProperty("face_landmark", lm)
                    lastLandmarks = lm
                }
                if (slim != lastSlim) {
                    r?.SetProperty("thin_face", slim); lastSlim = slim
                }
                if (eyeZoom != lastEye) {
                    r?.SetProperty("big_eye", eyeZoom); lastEye = eyeZoom
                }
            } else if (lastHasFace != false) {
                // v2.0.182：检测失败必须显式归零，否则 reshape 会沿用上一帧的
                // landmarks 对已不存在的脸做形变（画面出现诡异局部扭曲）
                r?.SetProperty("thin_face", 0f)
                r?.SetProperty("big_eye", 0f)
                lastSlim = 0f; lastEye = 0f
                lastLandmarks = null
            }
            lastHasFace = hasFace
        }

        /**
         * v2.0.187：**texture 输出通道**的处理 —— 与 [processFrame] 完全同构，
         * 只是结果不落 CPU，而是取 SinkTexture 暴露的 texture id。
         *
         * @return 是否产出了新结果（true 时 [resultTextureId] / [resultSerial] 已更新）
         */
        fun processFrameTexture(
            rgba: ByteArray,
            w: Int,
            h: Int,
            stride: Int,
            smooth: Float,
            white: Float,
            slim: Float,
            eyeZoom: Float,
            landmarks: FloatArray?
        ): Boolean {
            if (!GpuPixelBeauty.available) return false
            return try {
                ensure()
                val st = sinkTexture ?: return false
                synchronized(glLock) {
                    applyParamsLocked(smooth, white, slim, eyeZoom, landmarks)
                    source?.ProcessData(
                        rgba, w, h, stride,
                        GPUPixelSourceRawData.FRAME_TYPE_RGBA
                    )
                    val tex = st.GetTextureId()
                    if (tex == 0) {
                        return false
                    }
                    resultTextureId = tex
                    resultSerial = st.GetResultSerial()
                    true
                }
            } catch (e: Throwable) {
                Log.e(TAG, "GPUPixel processFrameTexture failed", e)
                false
            }
        }

        /**
         * v2.0.187 阶段2：**全零拷贝**处理 —— 输入直接用外部纹理（免 `glTexImage2D` 上传），
         * 输出取 SinkTexture 的纹理 id（免 `glReadPixels` 回读）。
         *
         * 相比 [processFrameTexture]（输入仍需 CPU 上传），本方法把输入侧 8.29 MB/帧的
         * 上传也一并省掉。
         *
         * ⚠️ 调用方须保证：`inputTexture` 在本次调用期间**不被改写**（用多缓冲轮转输入帧）。
         *
         * @param inputTexture 主渲染本帧的输入帧缓冲纹理（共享 context 下可见）
         * @return 是否产出了新结果（true 时 [resultTextureId] / [resultSerial] 已更新）
         */
        fun processFrameFromTexture(
            inputTexture: Int,
            w: Int,
            h: Int,
            smooth: Float,
            white: Float,
            slim: Float,
            eyeZoom: Float,
            landmarks: FloatArray?
        ): Boolean {
            if (!GpuPixelBeauty.available) return false
            return try {
                ensure()
                val srcTex = sourceTexture ?: return false
                val st = sinkTexture ?: return false
                synchronized(glLock) {
                    applyParamsLocked(smooth, white, slim, eyeZoom, landmarks)
                    if (!srcTex.PushTexture(inputTexture, w, h)) {
                        return false
                    }
                    val tex = st.GetTextureId()
                    if (tex == 0) {
                        return false
                    }
                    resultTextureId = tex
                    resultSerial = st.GetResultSerial()
                    true
                }
            } catch (e: Throwable) {
                Log.e(TAG, "GPUPixel processFrameFromTexture failed", e)
                false
            }
        }

        /**
         * **只做检测**（v2.0.186 从 [process] 拆出）：Mars-Face 推理 + alpha-beta 平滑。
         *
         * 返回值每次都是**新数组**（平滑结果被拷贝一份）—— 因为调用方（GL 处理线程）
         * 会在下一帧检测期间继续读它，必须与平滑内部缓冲解耦。
         *
         * @return 平滑后的 landmarks（归一化，2 float/点）；未检出返回 null
         */
        fun detect(rgba: ByteArray, w: Int, h: Int, stride: Int): FloatArray? {
            if (!GpuPixelBeauty.available) return null
            return try {
                ensure()
                val det = detector ?: FaceDetector.Create().also { detector = it }
                val raw = synchronized(detectLock) {
                    det.detect(
                        rgba, w, h, stride,
                        FaceDetector.GPUPIXEL_MODE_FMT_VIDEO,
                        FaceDetector.GPUPIXEL_FRAME_TYPE_RGBA
                    )
                }
                if (raw == null || raw.isEmpty()) {
                    // 丢失 → 复位平滑器，避免再次出现时用旧速度"拽"一下
                    smInit = false
                    null
                } else {
                    smoothLandmarks(raw)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "GPUPixel detect failed", e)
                null
            }
        }

        /** alpha-beta 滤波：pred = pos + vel → 用测量值校正，同时更新速度估计 */
        private fun smoothLandmarks(raw: FloatArray): FloatArray {
            val n = minOf(raw.size, smPos.size)
            if (!smInit || smCount != n) {
                for (i in 0 until n) {
                    smPos[i] = raw[i]
                    smVel[i] = 0f
                }
                smCount = n
                smInit = true
                return raw.copyOf()
            }
            val alpha = 0.55f
            val beta = 0.22f
            for (i in 0 until n) {
                val pred = smPos[i] + smVel[i]
                val err = raw[i] - pred
                smPos[i] = pred + alpha * err
                smVel[i] += beta * err
            }
            val out = raw.copyOf()
            for (i in 0 until n) out[i] = smPos[i]
            return out
        }

        /**
         * **只做 GL 处理**（v2.0.186 从 [process] 拆出）：磨皮/美白 + 美型 + 回读结果。
         *
         * 与 [detect] 分别在两个线程上运行，故不共享实例锁（见 [glLock] 说明）。
         *
         * ⚠️⚠️ property 名必须是 C++ 端 `RegisterProperty` **注册的名字**，
         * 不是内部字段名 —— 传错 key 时 native 静默忽略（只打 LOG_WARN），表现就是
         * 「美颜完全不生效」（v2.0.160~163 踩过：blur_alpha / white / thin_face_delta /
         * big_eye_delta 全是错的）。
         *
         * | 目标 | 正确 key | 对应 C++ setter |
         * |---|---|---|
         * | 磨皮 | `skin_smoothing` | `BeautyFaceFilter::SetBlurAlpha` |
         * | 美白 | `whiteness` | `BeautyFaceFilter::SetWhite` |
         * | 瘦脸 | `thin_face` | `FaceReshapeFilter::SetFaceSlimLevel` |
         * | 大眼 | `big_eye` | `FaceReshapeFilter::SetEyeZoomLevel` |
         * | 关键点 | `face_landmark` | `FaceReshapeFilter::SetFaceLandmarks` |
         *
         * 取值范围（官方文档）：磨皮 / 美白 / 瘦脸 / 大眼 均 0~1（0 = 不生效）。
         * 注：`BeautyFaceFilter` 未注册 sharpen / radius，故不提供锐化。
         *
         * @param landmarks 由 [detect] 产出（可为 null / 空 → 美型级别归零）
         * @return 处理后的 RGBA（同尺寸）；任何失败返回 null（上层保持原帧不变）
         */
        fun processFrame(
            rgba: ByteArray,
            w: Int,
            h: Int,
            stride: Int,
            smooth: Float,
            white: Float,
            slim: Float,
            eyeZoom: Float,
            landmarks: FloatArray?
        ): ByteArray? {
            if (!GpuPixelBeauty.available) return null
            return try {
                ensure()
                synchronized(glLock) {
                    // 参数下发与 texture 通道共用（避免两处实现漂移）
                    applyParamsLocked(smooth, white, slim, eyeZoom, landmarks)
                    source?.ProcessData(
                        rgba, w, h, stride,
                        GPUPixelSourceRawData.FRAME_TYPE_RGBA
                    )
                    sink?.GetRgbaBuffer()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "GPUPixel processFrame failed", e)
                null
            }
        }

        /**
         * 一体式入口（检测 + 处理，串行）—— 保留给「不需要并行」或回退场景使用。
         * 正常路径见 [detect] / [processFrame] 的双线程并行。
         */
        fun process(
            rgba: ByteArray,
            w: Int,
            h: Int,
            stride: Int,
            smooth: Float,
            white: Float,
            slim: Float,
            eyeZoom: Float
        ): ByteArray? {
            val lm = detect(rgba, w, h, stride)
            return processFrame(rgba, w, h, stride, smooth, white, slim, eyeZoom, lm)
        }

        @Synchronized
        fun release() {
            try {
                source?.Destroy()
                sourceTexture?.Destroy()
                beauty?.Destroy()
                reshape?.Destroy()
                sink?.Destroy()
                sinkTexture?.Destroy()
                detector?.destroy()
            } catch (_: Throwable) {
            }
            source = null
            sourceTexture = null
            beauty = null
            reshape = null
            sink = null
            sinkTexture = null
            textureMode = false
            resultTextureId = 0
            resultSerial = 0L
            detector = null
            // v2.0.186：清空属性/平滑缓存，避免下次创建时沿用旧值导致「跳过首次下发」
            lastSmooth = Float.NaN; lastWhite = Float.NaN
            lastSlim = Float.NaN; lastEye = Float.NaN
            lastHasFace = null; lastLandmarks = null
            smInit = false; smCount = 0
        }
    }
}
