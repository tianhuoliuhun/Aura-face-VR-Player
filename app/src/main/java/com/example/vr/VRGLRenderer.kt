package com.example.vr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VRGLRenderer(private val context: Context) : GLSurfaceView.Renderer {

    companion object {
        const val TAG = "VRGLRenderer"

        /** 面积 ≥ 此值（≈ 2048×2048）时改用分块上传，单张 ≥ 16 MB */
        private const val CHUNK_UPLOAD_AREA_THRESHOLD = 2048L * 2048L

        /**
         * v2.2.0（P3）：AI 弹幕取帧的最小间隔（ms）。
         *
         * `glReadPixels` 是同步阻塞调用，会让 GL 线程 flush 整条 GPU 管线。
         * 即便业务层或未来代码误以高频调用 [requestDanmuFrame]，也不会把帧率打垮。
         * 业务层正常间隔是 5s，本守卫只是兜底。
         */
        private const val DANMU_MIN_CAPTURE_INTERVAL_MS = 1500L
    }

    // Volatile settings accessible from Compose UI
    @Volatile var projectionMode = ProjectionMode.STANDARD

    /**
     * v2.1.211：**鱼眼视场角**（度）——仅 [ProjectionMode.FISHEYE] 生效。
     *
     * 180 = 基准档（压缩指数 2.0）；数值越大，边缘压缩越强、观感越「广」。
     * 不同鱼眼镜头片源的实际视角不同（常见 180/190/200/220），选错会
     * 出现「画面鼓成球」或「中心挤成一团」，故做成可调。
     */
    @Volatile var fisheyeFov: Float = 180f
    @Volatile var stereoMode = StereoMode.MONO
    @Volatile var beautyLevel = 0.5f // 0.0f (off) to 1.0f (max smoothing)
    @Volatile var brightnessLevel = 0.0f // -0.5f to 0.5f
    @Volatile var contrastLevel = 1.0f // 0.5f to 1.5f
    @Volatile var beautyWhitening = 0.5f
    @Volatile var beautyFaceSlimming = 0.4f
    @Volatile var beautyBigEyes = 0.3f
    @Volatile var beautyDarkCircles = 0.3f
    @Volatile var beautyNoseSlimming = 0.2f
    @Volatile var beautyMouth = 0.2f
    @Volatile var beautyTeethWhitening = 0.3f
    @Volatile var beautyLipstick = 0.3f
    @Volatile var beautyBlush = 0.3f
    @Volatile var beautyEyebrows = 0.4f
    @Volatile var beautyLongLegs = 0.4f
    @Volatile var beautySmallHead = 0.3f
    /**
     * v2.0.159：磨皮「高频保留度」。频域分离后，高频层（毛孔 / 纹理）按此系数叠回：
     * < 1.0 更平滑，> 1.0 相当于 USM 锐化（找回通透感）。默认 0.88 略偏平滑。
     */
    @Volatile var beautyTextureDetail = 0.88f
    @Volatile var isSplitScreenVR = false // Cardboard mode
    @Volatile var vrIpdOffsetRatio = 0.0f // VR 瞳距偏移调节（-0.1f ~ 0.1f）
    @Volatile var vrCinemaScale = 0.82f // v2.6.9 P2：Cardboard 分屏适眼巨幕缩放（0.6f ~ 1.0f）
    @Volatile var isEyesSwapped = false // 3D 眼序对调（左右眼/上下眼对调，适配交叉眼 3D）

    @Volatile var gyroEnabled = true
    @Volatile var isVideoActive = false
    @Volatile var isMirrored = false
    @Volatile var monoEyePreference = 1 // 1 for left half, 0 for right half
    // 双中心变形：2:1 全景视频左右半区各自以 25%/75% 为变形中心（VR_360 + MONO）
    @Volatile var warpDualCenter = false
    @Volatile var warpMode = WarpMode.NONE
    @Volatile var cylinderCurvature = 0.3f
    @Volatile var videoWidth = 0
    @Volatile var videoHeight = 0

    // ===== v2.0.206：画质增强（MEMC 插帧 / FSR 超分）=====
    // 开关与档位由 VRPlayerScreen 写入；规则判定（默认/自定义）已在 VideoEnhanceRules 完成，
    // 这里只消费结果 —— 渲染层不再自己判一次，避免两套逻辑对不上。

    /** MEMC 插帧总开关 */
    @Volatile var memcEnabled = false

    /** MEMC 目标帧率（48~120）。既用于决定插帧相位密度，也用作渲染输出的节流上限 */
    @Volatile var memcTargetFps = VideoEnhanceRules.MEMC_TARGET_FPS_DEFAULT

    /** FSR 超分总开关 */
    @Volatile var fsrEnabled = false

    /** FSR 超分目标宽（0 = 无有效目标，等同于关闭）。由 VideoEnhanceRules.resolveFsr 给出 */
    @Volatile var fsrTargetWidth = 0

    /** FSR 超分目标高（0 = 无有效目标，等同于关闭） */
    @Volatile var fsrTargetHeight = 0
    // Dimensions of the currently displayed image (used for 2D aspect fitting)
    @Volatile var imageWidth = 0
    @Volatile var imageHeight = 0

    // ===== v2.0.181：图片纹理「分块上传」 =====
    /**
     * true = 使用 `glTexSubImage2D` 按行条带逐块上传，而非一次性 `GLUtils.texImage2D`。
     *
     * 动因：内置测试卡提到了 4096×2048。`GLUtils.texImage2D(..., bitmap, 0)` 内部会
     * 在**上传前**再 `copyPixelsToBuffer` 出一份等大的像素缓冲 —— 4096×2048 ARGB_8888
     * 即 32 MB，于是上传瞬间会出现「Java 堆 32 MB（调用方副本）+ 32 MB（GLUtils 临时
     * 缓冲）+ 32 MB（GL 纹理）」约 96 MB 的峰值。低端机 / MuMu 上极易 OOM 或长时间卡顿。
     *
     * 改为分块后：先以 `glTexImage2D(..., null)` 开辟显存（不产生 Java 侧大对象），
     * 再按 [uploadChunkRows] 行一条把 `Bitmap.getPixels` 出来的 int[]（每块约 1 MB）
     * 升级为 RGBA 后上传。峰值只与条带高度有关，与整图尺寸解耦。
     *
     * 由 [setImageSizeHint] 在收到大图时自动开启。
     */
    private var useChunkedImageUpload = false

    /** 分块上传时每条带的行数（4096 宽 × 64 行 ≈ 1 MB 的 ARGB 像素） */
    private val uploadChunkRows = 64

    /**
     * 在 [updateImage] 之前告诉渲染器即将到来的图片尺寸，用于决定是否需要分块上传。
     * 不调用也能工作（[updateImage] 内部会自行判断）。
     */
    fun setImageSizeHint(width: Int, height: Int) {
        val w = if (width > 0) width else imageWidth
        val area = if (w > 0 && height > 0) w.toLong() * height.toLong() else 0L
        useChunkedImageUpload = area >= CHUNK_UPLOAD_AREA_THRESHOLD
    }

    /** 复用的条带缓冲：每块 int[] + byte[]，避免逐块 new */
    private var chunkPixelBuf: IntArray? = null
    private var chunkByteBuf: ByteArray? = null
    @Volatile var maxFps = 0 // Frame rate limit: 0 (unlimited), 12, 18, 24, 30, 48, 60, 90, 120
    private var lastFrameTimeMs = 0L

    // Zoom/FOV
    @Volatile var fovDeg = 75f // Field of View (Pinch to Zoom)

    // Manual panning state (if gyro is off or combined)
    @Volatile var manualYaw = 0f
    @Volatile var manualPitch = 0f

    // Feel/touch sensitivity (drag panning and fling inertia)
    @Volatile var panSensitivity = 0.18f
    @Volatile var flingSensitivity = 0.18f

    // Inertia fling velocities
    @Volatile var flingVelocityYaw = 0f
    @Volatile var flingVelocityPitch = 0f

    // Gyroscope rotation matrix passed from SensorEventListener
    private val gyroRotationMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val gyroSyncLock = Any()

    /**
     * v125：陀螺仪转向反转开关，供个别机型/VR 眼镜模式兜底。
     *
     * 默认 false —— 即直接使用传感器给出的相对姿态矩阵。旧实现固定做一次转置，
     * 实测导致上下左右全部反向；若换到某台设备后仍相反，把这里打开即可（改回转置）。
     */
    @Volatile
    var gyroInverted: Boolean = false

    fun updateGyroRotationMatrix(matrix: FloatArray) {
        synchronized(gyroSyncLock) {
            System.arraycopy(matrix, 0, gyroRotationMatrix, 0, 16)
        }
    }

    // Texture management
    private var imageTextureId = -1
    private var videoTextureId = -1
    var videoSurfaceTexture: SurfaceTexture? = null
    @Volatile var onVideoSurfaceCreated: ((SurfaceTexture) -> Unit)? = null
    
    // Google MediaPipe Face Landmarking & Smart Detection Manager
    internal var mediaPipeManager: MediaPipeFaceManager? = null

    // Real-time Face Detection parameters updated smoothly via background thread
    @Volatile var faceDetectedUniform = 0
    // ---- v2.6.22（多脸）：主脸（第 0 张）的单值镜像 ----
    // 这两个不只是「历史遗留」：GPUPixel 的 ROI 计算（gpRoiPx / 待检测区域）就靠它们，
    // 在多脸模式下应该以**主脸**为准（离镜头最近、面积最大的一张）。
    @Volatile var faceCenterXUniform = 0.5f
    @Volatile var faceCenterYUniform = 0.45f
    @Volatile var eyeDistanceUniform = 0.14f
    // Precise MediaPipe 468-point features (normalized UV space) —— 同样只是主脸镜像
    @Volatile var hasDetailedLandmarks = 0
    @Volatile var eyeLeftXUniform = 0f
    @Volatile var eyeLeftYUniform = 0f
    @Volatile var eyeRightXUniform = 0f
    @Volatile var eyeRightYUniform = 0f
    @Volatile var mouthXUniform = 0f
    @Volatile var mouthYUniform = 0f
    @Volatile var chinXUniform = 0f
    @Volatile var chinYUniform = 0f
    // v2.0.159：嘴部形状参数（供椭圆 mask 取代正圆）—— 由 MediaPipe 唇部关键点算出，0 = 未取到
    @Volatile var mouthHalfWidthUniform = 0f
    @Volatile var mouthHalfHeightUniform = 0f
    @Volatile var mouthAngleUniform = 0f

    // ===== v2.6.22（多脸）：shader 直接消费的数组 uniform（最多 MAX_FACES 张）=====
    // 每张脸一组，索引 0 是主脸。用「交错数组」而非 List<数组>：glUniform2fv 一次传一整组，
    // 每帧零分配（这些数组长期复用）。
    @Volatile internal var faceCountUniform = 0
    /** 各脸中心 (x,y) 交错 */
    internal val faceCentersXY = FloatArray(MediaPipeFaceManager.MAX_FACES * 2)
    /** 各脸两眼距离（视口 UV 尺度） */
    internal val eyeDistancesArr = FloatArray(MediaPipeFaceManager.MAX_FACES) { 0.14f }
    /** 各脸是否有精细点位（0/1） */
    internal val hasDetailedsArr = IntArray(MediaPipeFaceManager.MAX_FACES)
    internal val eyeLeftsXY = FloatArray(MediaPipeFaceManager.MAX_FACES * 2)
    internal val eyeRightsXY = FloatArray(MediaPipeFaceManager.MAX_FACES * 2)
    internal val mouthPossXY = FloatArray(MediaPipeFaceManager.MAX_FACES * 2)
    internal val chinsXY = FloatArray(MediaPipeFaceManager.MAX_FACES * 2)
    internal val mouthHalfWsArr = FloatArray(MediaPipeFaceManager.MAX_FACES)
    internal val mouthHalfHsArr = FloatArray(MediaPipeFaceManager.MAX_FACES)
    internal val mouthAnglesArr = FloatArray(MediaPipeFaceManager.MAX_FACES)

    // 人脸检测的**回读长边**（检测输入尺寸）。
    // v2.6.22（用户要求对齐 AI 弹幕链路）：采样区从「视口中央 512×512 裁剪」改成
    // **整帧**，所以这里不能再是 256 —— 整帧缩到 256 长边时，人脸只占画面 1/10 的话
    // 只剩 ~25px，MediaPipe 检出率骤降（那是"改完反而更差"的陷阱）。
    // 取 512：人脸占画面 1/10 时仍有 ~51px，与旧的"中央块"方案精度相当；
    // 而回读字节数反而更少（1920×1080 → 512×288 = 590KB，旧的 512×512 = 1MB）。
    internal val faceSampleSize = 512

    // 历史：faceReadSize（中央块边长 512）已随 v2.6.22 的整帧采样一并废弃，
    // 保留此说明避免后来者再去"恢复"中央块裁剪 —— 那会重新引入「脸不在中心就采不到」。

    // ===== v2.0.156：美颜链路的坐标与开销修复 =====

    /**
     * 采样间隔（帧）：每 N 帧回读一次屏幕内容做人脸检测。
     *
     * v2.6.23：从固定 8 帧改成**按检出脸数动态调整** —— MediaPipe 的 478 点 landmark 回归
     * 是**逐脸**做的，实测（模拟器 1920×1080）：
     *   · 0 张脸：detect ≈ 40~60 ms（只有检测器前向）
     *   · 3 张脸：detect ≈ **102~112 ms**（检测器 + 3 次 landmark 回归）
     * 每 8 帧一次 100ms+ 的后台推理会跟渲染抢 CPU（尤其 VR 分屏时每眼都要跑）。
     *
     * 折中：脸越多间隔越长（1 张 8 帧 / 2 张 10 帧 / 3 张 12 帧）。代价是妆容跟随略滞后
     * （12 帧 ≈ 0.2~0.4 s @30~60fps），但多脸场景下三张脸同时快速移动本身就少见，
     * 观感上主要是「转头时妆容跟得稍慢」。
     */
    internal var faceSampleInterval = 8

    /** v2.0.173：人脸检测连续失败计数 —— 连续 3 次失败才把 faceDetectedUniform 置 0（防妆容闪烁） */
    internal var faceMissStreak = 0

    /** v2.6.22（多脸）：多脸检测成功计数，每 60 次打一行诊断（确认检出张数） */
    internal var faceDiagCount = 0

    /**
     * 采样窗口相对**单个视口**的缩放比例（`rw/vpW`、`rh/vpH`）。
     *
     * 用途：把 MediaPipe 在裁剪图上算出的归一化坐标换算回视口 UV —— 见 [mapCropXToViewport]。
     * 采样区只占视口中央一小块，不做这层换算就会「脸越靠边、妆容越跑偏」。
     */
    @Volatile
    internal var faceCropScaleX = 1f

    @Volatile
    internal var faceCropScaleY = 1f

    /** GL 线程私有的回读缓冲（复用，避免每 8 帧新建 1MB DirectByteBuffer） */
    internal var faceReadBuffer: java.nio.ByteBuffer? = null

    /** 待检测帧的数组池：GL 线程生产、faceExecutor 消费，用完归还（避免每帧 1MB 垃圾） */
    internal var faceFramePool: ByteArray? = null

    internal val faceExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * v2.4.38（美颜审查 #10）：GPUPixel **后台初始化**线程。
     *
     * 初始化要做「assets 拷贝到外部目录 + `loadLibrary`」等 IO，旧实现直接在 GL 线程
     * （[onDrawFrame]）调用，失败后还会每 60s 重跑 → 阻塞渲染。现改为在此线程一次性执行。
     */
    private val gpInitExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** 自动初始化是否已派发（只派发一次；失败不自动重试，重试由 UI 点击触发） */
    private val gpInitDispatched = java.util.concurrent.atomic.AtomicBoolean(false)

    // ===== v2.0.186（P0-B）：GPUPixel 检测 / GL 处理 双线程流水线 =====
    /**
     * 独立的检测线程：跑 Mars-Face（纯 CPU 推理）。
     *
     * 背景：原先 [GpuPixelBeauty.Pipeline.process] 内部「检测 → GPU 链 → 回读」三段串行，
     * 检测期间 GPU 空转、GPU 处理期间 CPU 空转（实测检测段占 T_proc 的 40~60%）。
     * 拆成两条线程后，T_proc ≈ max(检测, GPU) 而非二者之和。
     */
    internal val gpDetectExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * v2.6.22（美颜审查 P0-1）：线程池是否已 `shutdownNow()`。
     *
     * 背景 —— 之前所有提交点都是裸 `executor.execute {}`，没有捕获
     * `RejectedExecutionException`：
     *  - 退出播放页时 [release] 调 `shutdownNow`，若 GL 线程恰好在 [maybeScheduleFaceSampling]
     *    里提交任务，异常会冒到 `onDrawFrame`；
     *  - 更严重的是**计数泄漏**：`gpInFlight` 在提交前已 `addAndGet`，任务没提交成功就永远不减；
     *    `isDetectingFace` 同理（在 finally 里才复位）。计数再也不归零 →
     *    [maybeScheduleFaceSampling] 的「忙」判据恒真 → **本渲染器生命周期内检测彻底停摆**
     *    （表现：切回来美颜形变再也不生效，必须退出播放页重进）。
     *
     * 修法：置位后所有提交点提前返回；提交本身也统一走 [submitSafely] 捕获拒绝异常并回滚计数。
     */
    @Volatile internal var executorsClosed = false

    /**
     * GPUPixel 路径的在途任务计数（检测 + GL 处理各算 1）。
     *
     * ⚠️ 这就是 GPUPixel 路径的「忙」判据（替代原先的 [isDetectingFace]）：
     * 只要 > 0 就说明还有线程在**读取本帧的像素数组**，此时 GL 线程既不该回读
     * （回读会取走帧池里那块正被读的数组 → 数据被覆盖），也不会有新结果可贴回。
     * 归零时才允许下一帧回读，并把帧归还池 —— 顺序上保证「读完之后才复用」。
     */
    internal val gpInFlight = java.util.concurrent.atomic.AtomicInteger(0)

    /** 检测线程产出、GL 处理线程消费（volatile 发布；数组本身不可变，引用换代即"新值"） */
    @Volatile internal var gpCachedLandmarks: FloatArray? = null

    /** 连续检测失败次数：达阈值才清缓存（对齐 GLSL 路径 faceMissStreak 的防抖思想） */
    internal var gpDetectMissStreak = 0

    /** v2.6.23：GPUPixel 检测低频诊断计数（每 60 次打一行检出张数） */
    internal var gpDiagCount = 0

    /** 检测降频计数器与间隔（每 N 帧真检一次；其余帧复用上次 landmarks） */
    internal var gpFrameTick = 0
    @Volatile internal var gpDetectInterval = 2

    // ===== v2.0.186：分段性能诊断（每 120 帧一行）=====
    // 用于验证「检测与 GL 处理并行」的实际收益：detect 与 gpu 两段应互不叠加，
    // 二者最大值 ≈ 单帧处理周期。rb（read-back）是 **GL 线程**上花在回读上的时间，
    // 同步与 PBO 两条路径都记，便于 A/B 对比哪种更省。
    private var gpProfFrames = 0
    private var gpProfDetectNanos = 0L
    private var gpProfGpuNanos = 0L
    private var gpProfReadbackNanos = 0L
    private var gpProfPboWaitFrames = 0

    internal fun gpProfLogDetect(nanos: Long) {
        gpProfDetectNanos += nanos
    }

    internal fun gpProfLogGpu(nanos: Long) {
        gpProfGpuNanos += nanos
        gpProfFrames++
        if (gpProfFrames >= 120) {
            val n = gpProfFrames.toLong()
            Log.i(
                TAG,
                "GP profile(120f): detect=${gpProfDetectNanos / n / 1_000_000} ms, " +
                    "gpu=${gpProfGpuNanos / n / 1_000_000} ms, " +
                    "rb=${gpProfReadbackNanos / n / 1_000_000} ms, " +
                    "pboWait=$gpProfPboWaitFrames/$gpProfFrames, " +
                    "downscale=${if (gpDownscaleNow > 0) gpDownscaleNow else gpReadDownscale}, " +
                    // ⚠️ 必须同时看开关与运行时探测标志，只看 gpPboEnabled 会在
                    //    开关关闭时误报 "on"（gpPboEnabled 默认就是 true）
                    "pbo=${if (gpAsyncReadback && gpPboEnabled) "on" else "off"}"
            )
            gpProfFrames = 0
            gpProfDetectNanos = 0
            gpProfGpuNanos = 0
            gpProfReadbackNanos = 0
            gpProfPboWaitFrames = 0
        }
    }

    /** @param waited 本帧是否因 fence 未就绪而放弃（仅 PBO 路径可能为 true） */
    internal fun gpProfLogReadback(nanos: Long, waited: Boolean) {
        gpProfReadbackNanos += nanos
        if (waited) gpProfPboWaitFrames++
    }

    /** v2.0.156：MediaPipe 实例改为后台创建（原先在 GL 线程同步创建，会造成首帧明显卡顿） */
    private val faceInitExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    internal val isDetectingFace = java.util.concurrent.atomic.AtomicBoolean(false)
    internal var faceFrameCounter = 0

    // v84：检测线程缓冲复用（避免每帧创建 Bitmap/数组）
    internal var faceArgBuffer: IntArray? = null
    internal var faceBmp: Bitmap? = null
    internal var faceScaledBmp: Bitmap? = null

    /**
     * v2.6.17：`glReadPixels(GL_RGBA)` 的输出能否**直接** `copyPixelsFromBuffer` 进
     * ARGB_8888 位图（省掉 262144 次逐像素重排）。
     *
     * 取值：0 = 未探测，1 = 可直拷，2 = 需回退逐像素重排。探测只做一次（1×1 位图）。
     */
    internal var faceDirectCopy = 0

    // v2.6.17：GLSL 检测段的分段计时（每 60 次输出一行）—— 用于真机确认
    // 「前置 CPU 段（回读字节 → Bitmap）已压到底」，即 prep 应远小于 detect。
    internal var faceProfCount = 0
    internal var faceProfPrepNanos = 0L
    internal var faceProfDetectNanos = 0L

    // Frame transfer between the GL thread and the face-detection executor.
    // The GL thread publishes a freshly allocated frame, and the executor consumes it,
    // so there is no shared mutable array being written and read concurrently.
    internal val faceFrameLock = Any()
    internal var pendingFaceFrame: ByteArray? = null
    internal var pendingFaceFrameW = 0
    internal var pendingFaceFrameH = 0

    // ===== v2.0.187 阶段2：texture 输入通道的待处理纹理 =====
    /**
     * 本帧要交给 GPUPixel 的**输入纹理**（= 当前 gpFbo 的纹理）。
     *
     * 阶段 2 下回读只用于人脸检测（小图），**处理**直接吃这张纹理 —— 免去每帧
     * 8.29 MB 的 `glTexImage2D` 上传。
     */
    @Volatile internal var pendingInputTexId = 0
    @Volatile internal var pendingInputTexW = 0
    @Volatile internal var pendingInputTexH = 0
    /** 最近一次成功美颜结果的纹理（供「本帧无新结果」时复用，避免回落到原始帧） */
    @Volatile private var gpLastResultTex = 0

    // Video frame sync flag
    private var isVideoFrameAvailable = false

    // Queue for loading Bitmap securely on the GL thread
    private var pendingBitmap: Bitmap? = null

    /** 与 [pendingBitmap] 配套：这一张是否走分块上传（由 updateImage 决定） */
    private var pendingBitmapChunked = false
    private val bitmapLock = Any()

    fun updateImage(bitmap: Bitmap) {
        imageWidth = bitmap.width
        imageHeight = bitmap.height
        // v2.0.181：大图自动走分块上传（见 useChunkedImageUpload 注释）
        setImageSizeHint(bitmap.width, bitmap.height)
        // 记录随图变化的 texelSize（每像素 UV 步长），供 shader 做邻域采样
        imageTexelW = 1.0f / bitmap.width.coerceAtLeast(1)
        imageTexelH = 1.0f / bitmap.height.coerceAtLeast(1)
        synchronized(bitmapLock) {
            pendingBitmap = bitmap
            pendingBitmapChunked = useChunkedImageUpload
        }
    }

    /** 当前图片的每像素 UV 步长（视频时回退为 0，由 shader 用视口 texel 兜底） */
    @Volatile private var imageTexelW = 0f
    @Volatile private var imageTexelH = 0f

    // Geometry caches
    private var quadPositionBuffer: FloatBuffer = GeometryHelper.createFloatBuffer(GeometryHelper.quadPositions)
    private var quadTexCoordBuffer: FloatBuffer = GeometryHelper.createFloatBuffer(GeometryHelper.quadTexCoords)

    private var sphere360Positions: FloatBuffer? = null
    private var sphere360TexCoords: FloatBuffer? = null
    // v2.1.210：EAC（等角立方体贴图）——**位置与 sphere360 相同**，只有 UV 不同
    private var eacPositions: FloatBuffer? = null
    private var eacTexCoords: FloatBuffer? = null
    private var eacVertexCount = 0
    // v2.1.212：Dome Master（球幕）—— 上半球网格
    private var domePositions: FloatBuffer? = null
    private var domeTexCoords: FloatBuffer? = null
    private var domeVertexCount = 0
    private var sphere360VertexCount = 0

    private var sphere180Positions: FloatBuffer? = null
    private var sphere180TexCoords: FloatBuffer? = null
    private var sphere180VertexCount = 0

    // 盒子模式（Box Mode）：六面体细分网格 + 逆向射线 UV
    private var boxPositions: FloatBuffer? = null
    private var boxTexCoords: FloatBuffer? = null
    private var boxVertexCount = 0

    // GL SL shaders variables

    private var videoProgram: VRMainGLProgram? = null
    private var imageProgram: VRMainGLProgram? = null
    private var fallbackProgramId = -1
    private var hFallbackPosition = -1
    private var hFallbackTexCoord = -1
    private var hFallbackSampler = -1
    private var placeholderTextureId = -1

    // ===== v2.0.177：磨皮半分辨率离屏 pass =====
    //
    // 为什么值得拆：磨皮处理的是**低频视觉信息**（色块、光影过渡、痘印），半分辨率下的
    // 双线性放大几乎不可辨；而高频细节（毛孔、发丝）本来就靠 detail * uTextureDetail
    // 从全分辨率原图叠回去 —— 拆分后这个「叠回」在合成 pass 里做，纹理保留度不受影响。
    //
    private var lastDiagEnhanceTex = -1
    private var lastDiagProgKind = ""

    // ===== 美颜对比模式（v102：GPUPixel 已移除，纯 shader 美颜）=====
    /** v2.0.160：美颜总开关（由原「对比原图」改造而来；false = 直通原图）。v2.0.172：默认改为关 */
    @Volatile var beautyMasterEnabled = false
    /** v2.0.160：美颜引擎。0 = GLSL（内置），1 = GPUPixel（独立 Mars-Face 检测 + 人脸区域处理） */
    @Volatile var beautyEngineType = BEAUTY_ENGINE_GLSL

    /**
     * v2.4.33（审查 #1）：**有效美颜引擎** —— 消除「设置里选了 GPUPixel、但 GPUPixel 不可用
     * → 美颜彻底没有输出」的三重否定。
     *
     * ## 病根（三个判断点各自为政）
     * 选 GPUPixel 而 `GpuPixelBeauty.available == false`（init 失败）时：
     *  - [isGpuPixelActive] 因 `!available` 返回 false → 不走离屏 FBO；
     *  - 主 shader 的 `bc` 与 [isHalfPassActive] 都**硬要求** `beautyEngineType == GLSL` → 也不跑。
     * 三者同时为假 ⇒ 用户看到「开了美颜却完全没效果」，而设置里明明选的是 GPUPixel。
     *
     * 修法：所有判断统一读**本属性**（GPUPixel 不可用时**一次性**折回 GLSL），
     * 行为与注释里那句「失败即回退 GLSL」一致 —— 只是此前没有对应代码。
     */
    private val effectiveBeautyEngine: Int
        get() = if (beautyEngineType == BEAUTY_ENGINE_GPUPIXEL && !GpuPixelBeauty.available) {
            BEAUTY_ENGINE_GLSL
        } else {
            beautyEngineType
        }
    // ---- v2.6.22（美颜审查 P1-4）：GPUPixel 参数**并入通用参数** ----
    // 旧实现在这里另有 beautyGpSmooth / beautyGpWhite / beautyGpSlim / beautyGpEyeZoom 四个字段
    // （默认 0.7 / 0.4 / 0.4 / 0.3，prefs 前缀 beauty_gp_*），与 GLSL 的
    // beautyLevel / beautyWhitening / beautyFaceSlimming / beautyBigEyes 完全独立。
    // 后果：同一用户切引擎，四项效果直接跳变（两套默认值、两套滑条），
    // 面板上还会出现「两处都在调磨皮」的困惑。
    //
    // 现在只有**一套参数**：GPUPixel 路径直接读 beautyLevel（磨皮）/ beautyWhitening（美白）/
    // beautyFaceSlimming（瘦脸）/ beautyBigEyes（大眼），量纲本来就一致（都是 0~1），
    // 无需换算。GPUPixel 不支持的项（妆容 / 去黑眼圈 / 瘦鼻 / 嘴型 / 牙齿 / 长腿 / 小头）
    // 由 UI 明确标注，不再静默失效。
    /**
     * v2.0.160（P3）：GPUPixel 是否在 VR / 全景（非平面投影）下也生效。
     * v2.0.184：恢复为**真开关**且**默认开** —— v2.0.182 曾把它从 [isGpuPixelActive] 里拿掉
     * （当时 VR 被改成无条件生效），使它退化成 UI 占位；现重新纳入激活条件。
     */
    @Volatile var gpuPixelVrFaceBeauty = true

    /**
     * v2.0.187：GPUPixel 美颜是否按**半分辨率**处理（默认开）。
     *
     * ## 影响
     * - **开启**（默认）：回读区固定为 1/2 尺寸，GPUPixel 的磨皮/美白/美型都在 1/2
     *   分辨率上处理，再上采样贴回。1080p 下每帧数据量 2.07 MB（全分辨率 8.29 MB 的
     *   1/4），实测 `gpu` 段 19~20 ms → 约 5 ms。代价是画面细节（毛孔/发丝/边缘）
     *   略有软化 —— 磨皮美白属低频效果，观感差异通常不明显。
     * - **关闭**：按自适应档位 [gpDownscaleNow] 回读（当前稳定在 1 档 = 全分辨率），
     *   画质最佳，但 `gpu` 段回到 19~20 ms。
     *
     * ## 生效范围
     * - 仅 **GPUPixel 引擎**（`isGpuPixelActive()`）的**全覆盖**路径。
     * - GLSL 引擎不受影响：它的磨皮/美白本就在主 shader 里按全分辨率执行。
     * - 若将来启用 texture 零拷贝通道（[GpuPixelBeauty.useTextureSink] = true），
     *   处理输入由主渲染的帧缓冲直接提供，**本开关不生效**。
     *
     * ## 与其他参数的关系
     * - 与自适应降采样 [updateGpAdaptiveDownscale]：本开关**开启时覆盖自适应结果**
     *   （固定 1/2，不让检测/处理分辨率随性能档位波动）；关闭时交回自适应档位决定。
     * - 与 [gpuPixelVrFaceBeauty]：后者决定 VR/全景下是否跑 GPUPixel，是本开关的前提。
     */
    @Volatile var gpuPixelHalfResBeauty = true
    // GPUPixel 处理结果（faceExecutor 产出 → GL 线程贴回 FBO）
    // v2.4.34（#8）：数据与尺寸封装进同一对象、用 AtomicReference 原子取走，
    // 避免「先读 bytes 再置 null」两步之间被 faceExecutor 的新写入覆盖（丢帧），
    // 以及分辨率变化时「旧 bytes 配新 W/H」导致的 glTexSubImage2D 尺寸错配。
    internal data class GpRegion(val bytes: ByteArray, val w: Int, val h: Int)
    internal val gpRegionPending = java.util.concurrent.atomic.AtomicReference<GpRegion?>(null)
    // v2.0.186：删除 gpRegionPool 复用池 —— JNI 侧 nativeGetRgbaBuffer 每次都
    // NewByteArray + SetByteArrayRegion（jni_sink_raw_data.cc:97-104），Java 拿到的
    // 必然是全新数组，"native 复用输出 buffer" 对 Java 层不成立。原先再 arraycopy
    // 一份纯属多余（每帧 2.07~8.3 MB 白拷），现直接把 sink 返回值移交 GL 线程。

    // ===== v2.0.185：修复「全屏闪烁」—— 保底帧（stable result）机制 =====
    /**
     * 本帧是否因「后台仍在处理上一帧」而**跳过回读**（见 [maybeScheduleFaceSampling] 首行）。
     *
     * 跳过的帧里 gpFbo 只含**原始渲染结果**（未经 GPUPixel 处理），若直接上屏，
     * 画面就在「美颜帧 ↔ 原始帧」之间交替 —— 这就是用户看到的**整个画面全屏闪烁**。
     * 上屏处据此改写为「重播上一张美颜结果」。
     *
     * 注：上屏的**决策判据**是 `gpRegionPending`（本帧是否真的贴回了新结果），
     * 因为「未跳读但后台未完成」的帧同样没有新结果、同样会闪；
     * 本字段保留用于诊断打点（区分「后台忙」与「后台空闲但未出结果」两种来源）。
     */
    internal var gpSkipReadThisFrame = false
    /** 最近一次成功的「美颜结果 FBO 快照」纹理（含全部 GPUPixel 处理），跳过回读时用它上屏 */
    private var gpResultTexId = 0

    // ===== v2.0.187：GPUPixel texture 输出通道（需与主渲染共享 EGLContext）=====
    /**
     * 待贴回的结果纹理（faceExecutor 产出 → GL 线程消费）。0 = 本帧无新结果。
     *
     * 与 [gpRegionPending] 语义对应：二者都表示「后台产出了一个可用的新结果」，
     * 只是载体不同（GPU texture vs CPU 字节数组）。
     */
    @Volatile internal var gpResultTexIdPending = 0
    /**
     * texture 通道是否已确认可用：`null` = 尚未校验。
     *
     * GL 线程首次拿到结果纹理后用 `glIsTexture` 在**主渲染 context** 里校验 ——
     * 不可见即说明共享未生效（GPUPixel 回退到了私有 context，或驱动拒绝），
     * 此时置 [gpTextureFallbackRequested] 让 Pipeline 切回 raw-data 通道。
     */
    private var gpTexturePathOk: Boolean? = null
    /** 已请求回退到 raw-data 通道（Pipeline 下次 ensure 时重建为 SinkRawData） */
    @Volatile internal var gpTextureFallbackRequested = false
    /** texture 通道成功贴回的帧数（诊断） */
    private var gpTextureAppliedFrames = 0
    private var gpResultFboId = 0
    private var gpResultW = 0
    private var gpResultH = 0
    /** 是否已有可用的保底帧（首次成功前不启用，避免上屏一张空白） */
    private var gpHasStableResult = false

    // ===== v2.0.185：自适应降采样（降低 T_proc，从源头减少「跳帧」频率）=====
    /**
     * 最近一次 GPUPixel 后台处理的实测耗时（纳秒，指数滑动平均）。
     *
     * 用途：T_proc 越大，GL 线程跳帧越频繁（闪烁虽已被保底帧消掉，但画面更新率会下降、
     * 观感变「卡」）。故按实测耗时**自动加大降采样档位**，把 T_proc 压回帧时长附近。
     */
    @Volatile private var gpProcNanosAvg = 0L
    /** 当前生效的降采样档位（1 = 不降采样）。随 [gpProcNanosAvg] 自适应调整 */
    @Volatile private var gpDownscaleNow = 0   // 0 = 尚未确定，用 gpReadDownscale 初值
    /**
     * 预热期样本数 —— 前 [gpAdaptiveWarmup] 次处理耗时**不计入**统计。
     *
     * 实测首帧耗时可达 1326 ms（Mars-Face 模型加载 + GPUPixel 管线首建 + shader 编译），
     * 是稳态（~10 ms）的上百倍。若计入平均，档位会瞬间冲到上限再逐级回落，
     * 表现为「启动时画面先糊一下再变清晰」。
     */
    private var gpAdaptiveSamples = 0
    private val gpAdaptiveWarmup = 3
    /** 连续同向调整计数（≥2 才真正换档，抑制横跳）；[gpAdaptiveLastDir] 记录上次方向 */
    private var gpAdaptiveDirStreak = 0
    private var gpAdaptiveLastDir = 0
    internal var lastSampleX0 = 0
    internal var lastSampleY0 = 0

    // ===== v2.0.177：GPUPixel 回读收窄到人脸 ROI =====
    /**
     * 人脸 ROI 的**低通跟随**状态（边长比例 + 中心，均为「单眼视口」UV 空间）。
     *
     * 为什么必须低通：人脸轻微晃动会让 ROI 每帧变化，贴回矩形的边缘就会出现
     * 「已美颜 / 未美颜」的分界跳动，肉眼表现为一圈会呼吸的矩形。
     * 用跟随系数把 ROI 变化压慢（只在人脸大幅移动时才有明显位移）。
     */
    private var gpRoiSide = 0f      // ROI 边长（UV，相对于视口宽）
    private var gpRoiCx = 0.5f      // ROI 中心 x（UV）
    private var gpRoiCy = 0.5f      // ROI 中心 y（UV）
    /** 上一帧实际生效的 ROI（像素，窗口坐标）—— 检测短暂失败时沿用，避免画面跳变 */
    private var gpRoiPx: IntArray? = null

    /** ROI 边长档位（像素）。量化到固定档位，避免尺寸连续变化导致贴回边缘持续抖动 */
    private val gpRoiLadder = intArrayOf(384, 512, 640, 768, 896, 1024, 1280)

    /**
     * ROI 相对人脸尺度的余量系数。
     *
     * 必须 ≥ 1.3：GPUPixel 内部会做**瘦脸/大眼的几何形变**，形变后的像素可能取自
     * ROI 之外（脸被拉瘦时边缘像素来自更外侧）——余量不足会在脸颊/下巴出现硬切割线。
     */
    private val gpRoiMargin = 3.2f

    /** 检测失败时保持上次 ROI 的帧数上限（配合 faceMissStreak 的 3 次防抖） */
    private val gpRoiHoldFrames = 24
    private var gpRoiHoldCounter = 0

    // ===== v2.0.182：GPUPixel 覆盖范围改为「全视频范围 / 全屏幕」=====
    /**
     * 回读降采样系数（v2.0.182）。
     *
     * 全覆盖后每帧回读量从「人脸 ROI 640×640 ≈ 1.6 MB」涨到「整屏 1920×1080 ≈ 8.3 MB」，
     * 而 `glReadPixels` 是**同步阻塞**的（GL 线程停下等 GPU 回传），量级不可接受。
     *
     * 磨皮 / 美白本质是**低频效果**，在 1/2 分辨率上处理再线性上采样贴回几乎无感损失；
     * 瘦脸 / 大眼依赖 Mars-Face 关键点定位，1/2 分辨率下人脸仍有 100+ 像素宽，精度足够。
     * 故统一按 0.5 倍回读 —— 内存流量降到 1/4（1920×1080 → 960×540 ≈ 2.1 MB）。
     */
    internal val gpReadDownscale = 2

    /**
     * v2.0.185：自适应降采样的**上限**档位。
     *
     * 处理耗时超过帧时长时会被调到更高的降采样档位（1 → 2 → 3 → 4），
     * 目的是把 T_proc 压回帧时长附近，让画面更新率维持在可用水平。
     * 上限取 4（即 1/4 分辨率，1920×1080 → 480×270）：再低人脸关键点就不可靠了
     * （Mars-Face 在 100px 以下检出率骤降），磨皮/美白的软化也开始可察觉。
     */
    private val gpReadDownscaleMax = 4

    /** 降采样后回读区的最小边（太小则人脸检测失效，宁可放大） */
    private val gpReadMinSide = 128

    /** 本帧是否为「全覆盖」模式（2D 全视频 或 VR 全屏）；false = 旧的人脸 ROI 模式 */
    internal var gpFullCoverageThisFrame = false

    /**
     * 是否启用「全覆盖」（v2.0.182 起默认为 true）。
     *
     * 用户要求：2D 覆盖全视频范围、3D/VR 覆盖全屏。保留此开关是为了在低端设备上
     * 一键退回 v2.0.177 的「仅人脸 ROI」省电模式（未来可挂到设置项）。
     */
    @Volatile var gpCoverageFull = true

    /**
     * 回读区在**全分辨率视口**里覆盖的范围（UV，0~1），贴回时按它拉伸。
     *
     * ⚠️ 必须与 [gpLastReadW]/[gpLastReadH]（回读像素尺寸，可能已降采样）区分：
     *   - 全覆盖：UV 覆盖 = (0,0)~(1,1)，即整个视口 → 降采样结果要拉伸回整屏；
     *   - 人脸 ROI：UV 覆盖 = 该矩形在视口里的实际位置与占比。
     * 若误用回读像素尺寸当目标矩形，降采样后只能填住视口左上角一小块。
     */
    internal var gpReadUvX0 = 0f
    internal var gpReadUvY0 = 0f
    internal var gpReadUvW = 1f
    internal var gpReadUvH = 1f
    /** 上一帧实际生效的回读区（像素，窗口坐标）—— 贴回时必须与回读一致 */
    internal var gpLastReadX0 = 0
    internal var gpLastReadY0 = 0
    internal var gpLastReadW = 0
    internal var gpLastReadH = 0

    // v2.0.182：降采样结果的中转纹理（上传 → GPU 上采样拉伸贴回 FBO）
    private var gpUploadTexId = 0
    private var gpUploadTexW = 0
    private var gpUploadTexH = 0
    /** 带缩放 blit 的临时顶点缓冲（pos.xy + uv.xy × 4 顶点） */
    private var gpScaledBlitQuad: java.nio.FloatBuffer? = null

    // GPUPixel 模式的离屏渲染目标（画到 FBO → 区域处理贴回 → blit 上屏）
    internal var gpFboId = 0
    internal var gpFboTexId = 0
    internal var gpFboW = 0
    internal var gpFboH = 0

    // ===== v2.0.187 阶段2：输入帧缓冲改**双缓冲轮转** =====
    // 引入 texture 输入通道后，GPUPixel 后台线程会直接采样 gpFbo 的纹理；若只有一张，
    // 主渲染重绘本帧时就会覆盖它正在读的内容（撕裂）。两张轮转后：GPUPixel 读第 N 帧
    // 那张时，主渲染已在写第 N+1 帧的另一张。
    // 为让上层代码零改动，[gpFboTexId] / [gpFboId] 始终指向「当前帧」那张（见 switchGpFboForFrame）。
    private var gpFboTexIdArr = IntArray(2)
    private var gpFboIdArr = IntArray(2)
    private var gpFboIdx = 0

    /**
     * v2.0.183：GPUPixel 回读用的**降采样 FBO**（尺寸 = 回读尺寸）。
     *
     * ⚠️ 为什么必须有它 —— ⚠️ 这是 v2.0.182 的错位根因：
     *   `glReadPixels(x0, y0, rw, rh)` **只做区域裁剪，不做缩放**。
     *   想在 1920×1080 的 FBO 上"降采样读取"，写 `glReadPixels(0, 0, 960, 540)`
     *   读到的其实是**屏幕左下角那 960×540 区域**（原尺寸裁剪），不是整屏缩略图。
     *   于是：美颜只作用在左下角 1/4 区域，贴回时又把它拉伸到全屏 →
     *   画面被放大 2 倍且效果与实际内容错位。
     *
     *   正确做法：先用 `GLES30.glBlitFramebuffer` 把主 FBO **真正缩放**渲染到这张
     *   降采样 FBO（GPU 线性滤波完成缩小），再从降采样 FBO 读像素 —— 这时读到的
     *   才是「整屏的缩略图」，坐标与视口 UV 一一对应。
     *
     * 本项目 EGL 上下文是 ES3（`VRGLSurfaceView` 用 `setEGLContextClientVersion(3)`），
     * 故 `glBlitFramebuffer` 可用。
     */
    internal var gpHalfFboId = 0
    private var gpHalfFboTexId = 0
    private var gpHalfFboW = 0
    private var gpHalfFboH = 0
    private var gpBlitProgram = 0
    private var gpBlitPosLoc = 0
    private var gpBlitTexLoc = 0
    /** v2.0.186（P2-E1）：aTex 的 attrib location 缓存（原先每帧 glGetAttribLocation） */
    private var gpBlitTexAttrLoc = 0
    private var gpBlitQuad: java.nio.FloatBuffer? = null

    // ===== v2.0.186（P1-C）：PBO + fence 异步回读 =====
    // glReadPixels 同步读会让 GL 线程 flush 整条 GPU 管线并等像素落内存（Adreno 上
    // 960×540 典型 0.5~2 ms）。改为「本帧提交到 PBO、下帧用 fence 收割」，GL 线程只
    // 做一次非阻塞的 glClientWaitSync(0) —— 代价是回读结果晚 1 帧到位（本就存在
    // 1~2 帧管线延迟，不可感知），换掉每帧的硬阻塞。
    private val gpPboIds = IntArray(2)
    private val gpPboFence = LongArray(2)
    private var gpPboCapBytes = 0
    /** 下一个要写入的 PBO 槽（收割的是 1 - gpPboIdx） */
    private var gpPboIdx = 0
    /** 是否有已提交、尚未收割的回读 */
    internal var gpPboActive = false
    /** 上一次提交的回读字节数与尺寸（用于检测"尺寸变化 → 在途数据作废"） */
    internal var gpPboPendingBytes = 0
    internal var gpPboPendingW = 0
    internal var gpPboPendingH = 0
    /** 运行时探测：一旦某步 GL 调用出错就永久回退同步读（护旧驱动） */
    @Volatile internal var gpPboEnabled = true
    /** fence 连续未就绪的帧数：达阈值判定驱动异常，回退同步读（正常应在一帧内 signal） */
    private var gpPboStall = 0
    /**
     * PBO 异步回读总开关（默认开）。
     *
     * ⚠️ 代价说明：异步化让回读结果**再晚 1 帧**到位（原本同步路径已延迟 1 帧，
     * 现在共 2 帧 ≈ 33 ms @60fps）。磨皮/美白是低频效果、不可感知；但**瘦脸/大眼的
     * 形变**在快速运动/快速摇镜时可能出现极轻微滞后。若实机观感有异，把此开关
     * 置 false 即可瞬时回到同步路径，无需改代码。
     *
     * 实测（MuMu，1080p / downscale=1，8.29 MB 回读，各 1200+ 帧）：
     *   PBO 开 → rb=8~9 ms / gpu=19~20 ms；PBO 关（同步）→ rb=9 ms / gpu=18~19 ms。
     *   即在本模拟器上两者**基本持平**（rb 的主体是 8.29 MB 的 memcpy，两条路径都要付），
     *   PBO 省下的只是 glReadPixels 的管线同步等待，而该等待在模拟器 GPU 直通下本就很小。
     *   真机（管线更深、等待更长）预期收益为正，故默认保持开启；随时可关。
     */
    @Volatile var gpAsyncReadback = true

    // ===== v104 LUT 视频滤镜 =====
    @Volatile var lutMix = 0f                  // 0=关闭 ~ 1=完全应用（VRPlayerScreen 控制）
    @Volatile var lutName = ""                 // 当前 LUT 显示名（仅记录，UI 用）
    private var lutTextureId = -1              // LUT 纹理（GL_TEXTURE_2D, 512x512 RGBA）
    @Volatile private var pendingLutRgba: ByteArray? = null // 待上传（UI 线程写入，GL 线程消费）

    // Viewport and matrices
    internal var displayWidth = 1080
    internal var displayHeight = 1920
    private var lastScaleX = 0f
    private var lastScaleY = 0f
    private var lastSrcW = 0

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Shaders source code

    // Latest shader compile error, surfaced to the UI so black-screen causes are visible
    @Volatile var shaderError: String? = null

    // Uniform setters that skip locations optimized out by strict device drivers
    // (Adreno frequently removes unused uniforms, leaving location = -1)
    /**
     * v2.6.22（多脸）：查询**数组 uniform** 的位置。
     *
     * 驱动差异：部分实现只认 "name[0]"，也有只认不带下标的 "name"（取数组首元素位置）。
     * 两种都试一遍，命中即用 —— 读不到就是 -1，调用侧会跳过下发（不会抛 GL 错误）。
     */
    private fun uniformArrayLocation(progId: Int, name: String): Int {
        val withIndex = GLES20.glGetUniformLocation(progId, "$name[0]")
        return if (withIndex != -1) withIndex else GLES20.glGetUniformLocation(progId, name)
    }

    private fun uniform1i(loc: Int, v: Int) {
        if (loc != -1) GLES20.glUniform1i(loc, v)
    }

    private fun uniform1f(loc: Int, v: Float) {
        if (loc != -1) GLES20.glUniform1f(loc, v)
    }

    private fun uniform2f(loc: Int, x: Float, y: Float) {
        if (loc != -1) GLES20.glUniform2f(loc, x, y)
    }

    private fun uniformMatrix4fv(loc: Int, count: Int, transpose: Boolean, value: FloatArray, offset: Int) {
        if (loc != -1) GLES20.glUniformMatrix4fv(loc, count, transpose, value, offset)
    }

    // Professional edge-preserving bilateral skin smoothing algorithm with customized facial cosmetics.
    // Two variants are compiled from this source:
    //   VIDEO_OES defined  -> uSamplerVideo is samplerExternalOES (video playback)
    //   VIDEO_OES undefined -> uSamplerVideo is sampler2D (images/panoramas, keeps
    //                          strict GPU drivers from touching an empty OES slot)

    private fun buildMainProgram(videoVariant: Boolean): VRMainGLProgram? = VRGLShaders.buildMainProgram(videoVariant) { shaderError = it }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // 🔴 v2.4.46（模糊 UI 审查 #1）：**GL 上下文重建后，旧的 FBO/纹理句柄全部失效**。
        //    此前只把 `bdDisabled` 之外的东西留着 → 退后台再回来（上下文丢失）后，
        //    `ensureBackdropTarget` 会因为 `bdFboId != 0 && 尺寸相同` 而**直接 return**，
        //    于是继续拿一个已失效的 FBO 去 blit → 必然报错 → 采样被永久停用
        //    → 用户看到「模糊再也不跟着画面动」。
        //    ⚠️ 只清 id、**不要 delete**：上下文已经丢了，对那些 id 调 glDelete* 无意义，
        //       还可能污染错误状态。
        backdropAndDanmu.onSurfaceCreatedReset()

        GLES20.glClearColor(0.04f, 0.05f, 0.07f, 1.0f) // Dark night canvas background Hex #0a0c12
        GLES20.glDisable(GLES20.GL_DEPTH_TEST) // 2D or spherically projected mapping, depth test is unneeded

        // 记录实际拿到的 GL 版本：上下文虽然按 ES3 请求，但真机上驱动可能仍回退到 ES2
        // （VRGLSurfaceView 按 setEGLContextClientVersion(3) 请求）。
        // 排查「shader 在 A 设备能编译、B 设备黑屏」这类问题时，第一手证据就是这行日志。
        // GL_MAJOR_VERSION / GL_MINOR_VERSION 在 ES2 上下文下查询无效，故只用 GL_VERSION 字符串。
        Log.i(TAG, "GL_VERSION = ${GLES20.glGetString(GLES20.GL_VERSION)}"
                + " | VENDOR = ${GLES20.glGetString(GLES20.GL_VENDOR)}")

        // ===== v2.0.187：把本 GL 线程的 EGL context 登记为 GPUPixel 的共享源 =====
        // 共享后 GPUPixel 的结果 texture 可被本渲染器直接采样，省掉每帧「回读 8.29MB
        // + JNI 拷贝 8.29MB + GL 线程再 memcpy 8.29MB」的 CPU 往返。
        //
        // ⚠️ 两个时机约束：
        //   1. 必须在**有 current context 的线程**调用 —— 这里是 GLSurfaceView 的渲染线程 ✓
        //   2. 必须在 GPUPixel 的 EGLContext 被创建**之前** —— 它是懒创建的，只要早于
        //      任何会触发 GPU 的 GPUPixel 调用（SourceRawData.Create 等）即可；本处最早 ✓
        // 失败不影响功能：GPUPixel 会用私有 context，texture 通道由 GL 线程的
        // glIsTexture 校验发现不可用后自动回退到 CPU 回读。
        try {
            val shared = com.pixpark.gpupixel.GPUPixel.captureSharedEglContext()
            Log.i(TAG, "GPUPixel shared EGL context registered: $shared")
        } catch (t: Throwable) {
            Log.w(TAG, "captureSharedEglContext failed (will use private context)", t)
        }

        // Build the two main programs: video variant samples an OES external texture,
        // image variant uses plain sampler2D so strict GPU drivers (Adreno etc.) never
        // hit an empty OES slot while displaying photos/panoramas.
        videoProgram = buildMainProgram(videoVariant = true)
        imageProgram = buildMainProgram(videoVariant = false)

        // Fallback program: keep images/panoramas visible even if the main shader
        // is rejected by a strict device driver.
        try {
            val vShader = VRGLShaders.compileShader(GLES20.GL_VERTEX_SHADER, VRGLShaders.fallbackVertexShaderCode)
            val fShader = VRGLShaders.compileShader(GLES20.GL_FRAGMENT_SHADER, VRGLShaders.fallbackFragmentShaderCode)
            fallbackProgramId = GLES20.glCreateProgram().apply {
                GLES20.glAttachShader(this, vShader)
                GLES20.glAttachShader(this, fShader)
                GLES20.glLinkProgram(this)
            }
            hFallbackPosition = GLES20.glGetAttribLocation(fallbackProgramId, "aPosition")
            hFallbackTexCoord = GLES20.glGetAttribLocation(fallbackProgramId, "aTextureCoord")
            hFallbackSampler = GLES20.glGetUniformLocation(fallbackProgramId, "uSampler")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compile fallback shader", e)
            fallbackProgramId = -1
        }

        // Initialize textures
        videoTextureId = createOESTexture()
        imageTextureId = createStandardTexture()
        placeholderTextureId = createStandardTexture()

        // Generate sphere geometries
        val sphere360 = GeometryHelper.generateSphere(1.0f, 40, 40, false)
        sphere360Positions = sphere360.first
        sphere360TexCoords = sphere360.second
        sphere360VertexCount = sphere360Positions!!.capacity() / 3

        // v2.1.210：EAC 网格 —— 同样的球面几何，UV 换成等角立方体 atlas 映射。
        // 细分度取 64×64（高于 360 模式的 40×40）：EAC 的 atan 映射在面边界附近
        // 变化最快，顶点太稀会把直线段插值成可见折线（接缝处出现细密锯齿）。
        val eac = GeometryHelper.generateEacSphere(1.0f, 64, 64)
        eacPositions = eac.first
        eacTexCoords = eac.second
        eacVertexCount = eacPositions!!.capacity() / 3

        // v2.1.212：Dome 网格（天顶 -> 赤道的上半球）
        val dome = GeometryHelper.generateDomeSphere(1.0f, 32, 64)
        domePositions = dome.first
        domeTexCoords = dome.second
        domeVertexCount = domePositions!!.capacity() / 3

        val sphere180 = GeometryHelper.generateSphere(1.0f, 40, 40, true)
        sphere180Positions = sphere180.first
        sphere180TexCoords = sphere180.second
        sphere180VertexCount = sphere180Positions!!.capacity() / 3

        // 盒子模式网格：六面体细分（16×16）+ 逆向射线 UV 映射
        val box = GeometryHelper.generateBox(size = 1.0f, subdivisions = 16)
        boxPositions = box.first
        boxTexCoords = box.second
        boxVertexCount = boxPositions!!.capacity() / 3

        // Setup OES SurfaceTexture for Media player playback
        if (videoTextureId != -1) {
            videoSurfaceTexture = SurfaceTexture(videoTextureId).apply {
                setOnFrameAvailableListener {
                    synchronized(this@VRGLRenderer) {
                        isVideoFrameAvailable = true
                    }
                }
            }
            // Fire callback to outside to setup Android MediaPlayer
            onVideoSurfaceCreated?.invoke(videoSurfaceTexture!!)
        }

        // Initialize/Configure Google MediaPipe Face Landmarker context on active GL thread.
        // Note: catch Throwable — MediaPipe ships no x86_64 native library, so on
        // x86_64 emulators loading it throws UnsatisfiedLinkError (an Error), which
        // must not kill the renderer thread.
        // v2.0.156：MediaPipe 的创建挪到后台线程 —— 它要打开 assets 里的 478 点模型并初始化
        // native 会话，在 GL 线程同步做会让首帧明显卡顿。创建完成前 mediaPipeManager 为 null，
        // 人脸检测会自动走 FaceDetector 兜底，功能不会因此不可用。
        val previousManager = mediaPipeManager
        mediaPipeManager = null
        try {
            previousManager?.release()
        } catch (e: Throwable) {
            Log.w(TAG, "释放上一个美颜人脸管理器失败：${e.message}")
        }
        faceInitExecutor.execute {
            try {
                mediaPipeManager = MediaPipeFaceManager(context)
                Log.i(TAG, "Successfully initialized Google MediaPipe face landmarking engine.")
            } catch (e: Throwable) {
                // MediaPipe 不含 x86_64 native 库，x86_64 模拟器上会抛 UnsatisfiedLinkError（Error），
                // 不能让它把初始化线程整个带走
                Log.e(TAG, "Failed to instantiate Google MediaPipe beauty engine", e)
            }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        displayWidth = width
        displayHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {

        // Enforce frame rate limit if maxFps > 0
        if (maxFps > 0) {
            val targetMs = 1000L / maxFps
            val now = android.os.SystemClock.uptimeMillis()
            val elapsed = now - lastFrameTimeMs
            if (elapsed in 1 until targetMs) {
                try {
                    Thread.sleep(targetMs - elapsed)
                } catch (_: Exception) {}
            }
            lastFrameTimeMs = android.os.SystemClock.uptimeMillis()
        }

        // v2.6.16（P0）：渲染帧度量。**必须放在限帧 sleep 之后** —— 放在前面会把等待时间
        // 算进 CPU 耗时（30 fps 限帧时凭空多 16ms）。帧间隔由两次 begin 之差得出，
        // 天然包含限帧等待，因此 fps 反映的是用户真正看到的帧率。
        val frameBeginNs = RenderStats.onFrameBegin()

        // Apply inertia decay for smooth drag/fling
        if (flingVelocityYaw != 0f || flingVelocityPitch != 0f) {
            manualYaw = (manualYaw + flingVelocityYaw) % 360f
            manualPitch = (manualPitch + flingVelocityPitch).coerceIn(-85f, 85f)
            
            val decay = 0.92f // smooth friction decay coefficient
            flingVelocityYaw *= decay
            flingVelocityPitch *= decay
            
            if (Math.abs(flingVelocityYaw) < 0.01f) flingVelocityYaw = 0f
            if (Math.abs(flingVelocityPitch) < 0.01f) flingVelocityPitch = 0f
        }

        // 1. Process any pending Bitmap from synchronization lock
        var bToLoad: Bitmap? = null
        var bChunked = false
        synchronized(bitmapLock) {
            if (pendingBitmap != null) {
                bToLoad = pendingBitmap
                bChunked = pendingBitmapChunked
                pendingBitmap = null
            }
        }
        bToLoad?.let {
            if (bChunked) {
                // v2.0.181：大图分块上传 —— 避免 GLUtils 内部再复制一份等大像素缓冲
                uploadImageChunked(it)
            } else {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, it, 0)
            }
            // v2.0.180：不再在此回收传入的 Bitmap。
            // 原先这里 `it.recycle()` 有两个问题：
            //  ① 与「位图缓存」冲突 —— 同一实例被缓存后再传来会抛
            //     IllegalStateException（texImage2D on a recycled bitmap）；
            //  ② 既有隐患：导入的图片 `customBitmap` 在切走再切回时会复用同一实例，
            //     第二次上传必然命中已回收的 bitmap → 崩溃。
            // 现改为由**提供方**管理生命周期：内置演示图由 DemoMediaProvider 缓存母本、
            // 每次返回副本（副本交给 GL 上传后即成为垃圾，由 GC 回收）；
            // 导入图由 VRPlayerScreen 持有并复用。渲染器只读取，不回收。
        }

        // 2. Fetch new video stream frames from SurfaceTexture on the GL thread
        // v2.0.206：额外记录「本帧是否真的消费到一个新的视频帧」——
        // MEMC 的 prev/curr 双帧推进与场景切换检测都依赖这个事实，
        // 不能靠「onDrawFrame 被调用」推断（渲染循环是 CONTINUOUSLY，帧率远高于源帧率）。
        var newVideoFrameThisFrame = false
        synchronized(this) {
            if (isVideoFrameAvailable) {
                videoSurfaceTexture?.updateTexImage()
                isVideoFrameAvailable = false
                newVideoFrameThisFrame = true
            }
        }

        // v2.0.206：画质增强（MEMC 插帧 → FSR 超分）。
        // 必须在主渲染之前跑完：它返回一张可供主 shader 直接采样的纹理
        // （0 表示未启用增强，走原始通路）。内部已把 framebuffer / viewport
        // 恢复原状，并持有上一帧结果的引用。
        val enhanceTexId = runVideoEnhance(newVideoFrameThisFrame)

        // v102：GPUPixel 原生美颜已移除，美颜效果全部由 shader 实时处理（下方 uniform 同步）

        // v104 LUT：消费待上传纹理数据（UI 线程写入，GL 线程在此上传）
        val pendingLut = pendingLutRgba
        if (pendingLut != null) {
            pendingLutRgba = null
            uploadLutTexture(pendingLut)
        }

        // Clear screen
        // v2.0.160：GPUPixel 引擎激活时先画到离屏 FBO（绘制完做「人脸区域处理 + 贴回」再上屏）。
        // 惰性初始化 GPUPixel：失败一次即标记不可用，UI 据此弹提示并自动回退 GLSL。
        // v2.4.38（美颜审查 #10）：GPUPixel 初始化（assets 拷贝 IO + loadLibrary）**移到后台线程**，
        // 且自动路径**只派发一次**（[gpInitDispatched]）—— 失败后不再自动重试，重试只能由用户在
        // 设置里重新点击 GPUPixel 引擎（UI 侧 `GpuPixelBeauty.init(force = true)`）触发。
        // 旧实现在这里每帧调用、失败后每 60s 在 GL 线程重跑一次 Init，直接阻塞渲染线程。
        if (beautyEngineType == BEAUTY_ENGINE_GPUPIXEL && !GpuPixelBeauty.available &&
            gpInitDispatched.compareAndSet(false, true)
        ) {
            val appCtx = context
            // v2.6.22（P0-1）：提交失败时把「已派发」标记复位，否则本渲染器再也不会尝试初始化
            submitSafely(
                gpInitExecutor, "GPUPixel 初始化",
                rollback = { gpInitDispatched.set(false) },
            ) { runCatching { GpuPixelBeauty.init(appCtx) } }
        }
        val gpActive = isGpuPixelActive()
        // v2.0.177：磨皮半分辨率 pass 激活时，主渲染写到 downFbo（全分辨率纹理）而非屏幕，
        // 之后跑 down→blur 两个 pass 再合成上屏。
        val halfActive = isHalfPassActive()
        // 主 shader 的磨皮段是否要让位给离屏 pass（见下方 hBeautyStrength 同步处）
        val halfPassThisFrame = halfActive
        if (gpActive) {
            ensureGpFbo(displayWidth, displayHeight)
            // v2.0.187 阶段2：切到本帧的输入帧缓冲（双缓冲轮转）。
            // 必须在任何绘制之前 —— 之后的绘制代码都用 gpFboId，无需改动即作用于本帧那张。
            switchGpFboForFrame()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, gpFboId)
        } else if (halfActive) {
            ensureHalfPassFbos(displayWidth, displayHeight)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, downFboId)
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // v102：视频/图片统一走主程序 shader（美颜在片元着色器内实时计算）
        // v2.0.206：有增强纹理时必须走 imageProgram —— 它的 uSamplerVideo 是普通
        //   sampler2D 槽位，正好用来接收 MEMC/FSR 的输出；而 videoProgram（OES 变体）
        //   的那个槽位声明成 samplerExternalOES，根本绑不了 2D 纹理。
        val hasEnhanceTex = enhanceTexId != 0
        val prog = if (isVideoActive && !hasEnhanceTex) videoProgram else imageProgram
        if (prog == null) {
            // Neither main program is available on this driver: use the plain 2D fallback.
            drawFallbackFrame()
            return
        }

        // Use shader
        GLES20.glUseProgram(prog.programId)

        // Bind active textures
        bindActiveTextures(prog, enhanceTexId)

        // v2.0.206 诊断：验证增强纹理到底有没有走到主 shader。
        // ⚠️ 判据：
        //   · prog 必须是 **image(2D)** —— 只有它那个槽位被声明成 sampler2D，能接 2D 纹理；
        //     video(OES) 的槽位是 samplerExternalOES，绑 2D 纹理属未定义行为。
        //   · `uSamplerVideo` 的 location 必须是 **非 -1**。若是 -1，说明该 sampler 被驱动
        //     优化掉了，采样器退回默认 unit 0（= 原图），表现就是
        //     「FSR/MEMC 的 pass 明明在跑、画面却毫无变化」。
        val progKind = if (prog === videoProgram) "video(OES)" else "image(2D)"
        if (hasEnhanceTex && (enhanceTexId != lastDiagEnhanceTex || progKind != lastDiagProgKind)) {
            lastDiagEnhanceTex = enhanceTexId
            lastDiagProgKind = progKind
            Log.i(
                TAG,
                "增强纹理接入: texId=$enhanceTexId prog=$progKind " +
                    "loc[uSamplerVideo=${prog.hSamplerVideo} uIsVideo=${prog.hIsVideo} " +
                    "uSamplerImage=${prog.hSamplerImage}]"
            )
        }

        // v104 LUT 视频滤镜：绑定 512x512 LUT 纹理到 TEXTURE2 并同步强度
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        if (lutTextureId != -1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
            uniform1i(prog.hLutTexture, 2)
            uniform1f(prog.hLutMix, lutMix)
        } else {
            // 无 LUT 时绑定占位纹理，强度置 0（shader 分支跳过）
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, placeholderTextureId)
            uniform1i(prog.hLutTexture, 2)
            uniform1f(prog.hLutMix, 0f)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        // Load uniforms settings
        uniform1i(prog.hProjectionMode, projectionMode.id)
        uniform1i(prog.hStereoMode, stereoMode.id)
        
        // Sync beauty filter state with GL pipeline
        // 对比模式：所有美颜 uniform 归零，仅保留亮度/对比度等调色
        // v2.0.160：原「对比原图」改为「美颜总开关」；且 GLSL 美颜只在 GLSL 引擎下生效
        // （GPUPixel 引擎下由其独立滤镜负责，避免双重磨皮/美白叠加）
        val bc = if (beautyMasterEnabled && effectiveBeautyEngine == BEAUTY_ENGINE_GLSL) 1f else 0f
        // v2.0.177：磨皮半分辨率 pass 激活时，主 shader 的磨皮段必须关闭（传 0）——
        // 磨皮的「低频层 + 频域分离合成」已搬到离屏 pass，主 shader 只负责其余美颜效果。
        // 若此处仍传 beautyLevel，会变成「主 shader 糊一次 + 合成 pass 再糊一次」的双重磨皮。
        uniform1f(prog.hBeautyStrength, if (halfPassThisFrame) 0f else beautyLevel * bc)
        // v2.0.159：磨皮的高频保留度。刻意不随对比模式归零 —— 对比模式下磨皮本身不执行（
        // uBeautyStrength = 0），这个值不会被用到。半分辨率合成 pass 仍消费它。
        uniform1f(prog.hTextureDetail, beautyTextureDetail)
        
        uniform1f(prog.hBrightness, brightnessLevel)
        uniform1f(prog.hContrast, contrastLevel)
        uniform1i(prog.hIsMirrored, if (isMirrored) 1 else 0)
        uniform1i(prog.hWarpMode, warpMode.id)
        uniform1f(prog.hCurvature, cylinderCurvature)
        uniform1f(prog.hFisheyeFov, fisheyeFov)

        uniform1f(prog.hWhitening, beautyWhitening * bc)
        uniform1f(prog.hFaceSlimming, beautyFaceSlimming * bc)
        uniform1f(prog.hBigEyes, beautyBigEyes * bc)
        uniform1f(prog.hDarkCircles, beautyDarkCircles * bc)
        uniform1f(prog.hNoseSlimming, beautyNoseSlimming * bc)
        uniform1f(prog.hMouth, beautyMouth * bc)
        uniform1f(prog.hTeethWhitening, beautyTeethWhitening * bc)
        uniform1f(prog.hLipstick, beautyLipstick * bc)
        uniform1f(prog.hBlush, beautyBlush * bc)
        uniform1f(prog.hEyebrows, beautyEyebrows * bc)
        uniform1f(prog.hLongLegs, beautyLongLegs * bc)
        uniform1f(prog.hSmallHead, beautySmallHead * bc)
        uniform1i(prog.hFaceDetected, if (bc > 0f) faceDetectedUniform else 0)

        // ===== v2.6.22（多脸）：数组 uniform 一次下发 =====
        // 与旧的「单脸 8 个 uniform」相比只多了 glUniform*v 的 count 参数；
        // 数组元素在 shader 里按 [fi] 索引，由 uFaceCount 控制循环轮数。
        // 关闭美颜（bc == 0）时把张数置 0 —— shader 的 for 循环第一轮就 break。
        val faceN = MediaPipeFaceManager.MAX_FACES
        val drawFaces = if (bc > 0f) faceCountUniform else 0
        uniform1i(prog.hFaceCount, drawFaces)
        if (prog.hFaceCenters != -1) GLES20.glUniform2fv(prog.hFaceCenters, faceN, faceCentersXY, 0)
        if (prog.hEyeDistances != -1) GLES20.glUniform1fv(prog.hEyeDistances, faceN, eyeDistancesArr, 0)
        if (prog.hHasDetaileds != -1) GLES20.glUniform1iv(prog.hHasDetaileds, faceN, hasDetailedsArr, 0)
        if (prog.hEyeLefts != -1) GLES20.glUniform2fv(prog.hEyeLefts, faceN, eyeLeftsXY, 0)
        if (prog.hEyeRights != -1) GLES20.glUniform2fv(prog.hEyeRights, faceN, eyeRightsXY, 0)
        if (prog.hMouthPoss != -1) GLES20.glUniform2fv(prog.hMouthPoss, faceN, mouthPossXY, 0)
        if (prog.hChins != -1) GLES20.glUniform2fv(prog.hChins, faceN, chinsXY, 0)
        // 嘴形参数（0 表示尚未取到，shader 会回退到按 fUnit 估算）
        if (prog.hMouthHalfWs != -1) GLES20.glUniform1fv(prog.hMouthHalfWs, faceN, mouthHalfWsArr, 0)
        if (prog.hMouthHalfHs != -1) GLES20.glUniform1fv(prog.hMouthHalfHs, faceN, mouthHalfHsArr, 0)
        if (prog.hMouthAngles != -1) GLES20.glUniform1fv(prog.hMouthAngles, faceN, mouthAnglesArr, 0)
        uniform1i(prog.hWarpDualCenter, if (warpDualCenter) 1 else 0)

        // Pass texel dimensions (size of 1 pixel) to fragment shaders
        // v2.0.181：图片模式下改用**图片自身**的 texel 步长。原先固定传视口的 1/W、1/H，
        // 当图片分辨率（现在 4096×2048）与视口（如 1920×1080）不同量级时，shader 里所有
        // 以 uTexelSize 为步长的邻域采样（磨皮模糊核、边缘检测等）会取错范围 ——
        // 4096 宽的图上按 1/1920 步长采样会跨到 2 个像素外，糊成一团。
        val texelW = if (!isVideoActive && imageTexelW > 0f) imageTexelW else 1.0f / displayWidth.coerceAtLeast(1)
        val texelH = if (!isVideoActive && imageTexelH > 0f) imageTexelH else 1.0f / displayHeight.coerceAtLeast(1)
        uniform2f(prog.hTexelSize, texelW, texelH)

        // Check if Dual Viewport/VR Box split mode is requested
        if (isSplitScreenVR) {
            val halfWidth = displayWidth / 2
            val leftEyeParam = if (isEyesSwapped) 0 else 1
            val rightEyeParam = if (isEyesSwapped) 1 else 0

            // 开启硬件视口裁剪，杜绝左右眼画面边缘渗漏
            GLES20.glEnable(GLES20.GL_SCISSOR_TEST)

            // Left Eye Viewport
            GLES20.glScissor(0, 0, halfWidth, displayHeight)
            GLES20.glViewport(0, 0, halfWidth, displayHeight)
            calculateAndApplyMatrices(isLeft = true, aspect = halfWidth.toFloat() / displayHeight.toFloat(), mvpLoc = prog.hMVPMatrix)
            uniform1i(prog.hLeftEye, leftEyeParam)
            drawActiveGeometry(prog)

            // Right Eye Viewport
            GLES20.glScissor(halfWidth, 0, halfWidth, displayHeight)
            GLES20.glViewport(halfWidth, 0, halfWidth, displayHeight)
            calculateAndApplyMatrices(isLeft = false, aspect = halfWidth.toFloat() / displayHeight.toFloat(), mvpLoc = prog.hMVPMatrix)
            uniform1i(prog.hLeftEye, rightEyeParam)
            drawActiveGeometry(prog)

            GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        } else {
            // Standard full width screen mode
            GLES20.glViewport(0, 0, displayWidth, displayHeight)
            calculateAndApplyMatrices(isLeft = true, aspect = displayWidth.toFloat() / displayHeight.toFloat(), mvpLoc = prog.hMVPMatrix)
            uniform1i(prog.hLeftEye, monoEyePreference) // default side center or user segment choose for dome
            drawActiveGeometry(prog)

        }

        // v2.0.156：人脸采样统一在绘制完成后调度，**分屏与全屏都要**（旧实现只在全屏分支里做，
        // 于是分屏 VR 下的人脸跟踪会冻在最后一次结果上）。仅平面投影需要 —— shader 里的
        // 面部效果（瘦脸/大眼/妆容等）只在 `uProjectionMode == 0` 时生效。
        val isPlanarFaceMode = projectionMode == ProjectionMode.STANDARD ||
            projectionMode == ProjectionMode.FISHEYE
        // v2.0.160：GPUPixel 的「VR 视频人脸美颜」开启时，非平面模式也采样（屏幕空间后处理）
        // v2.0.184：恢复开关条件 —— gpActive 已把该开关纳入（VR 下关开关时 gpActive=false），
        // 故此处条件写回「平面 或 gpActive」，语义等价于 v2.0.160 的原始设计。
        if (isPlanarFaceMode || (gpActive && gpuPixelVrFaceBeauty)) {
            maybeScheduleFaceSampling()
        } else {
            // v2.0.185：没走采样路径就无所谓「跳过回读」，清掉记账避免残留到下一帧误判
            gpSkipReadThisFrame = false
        }

        // v2.0.160：GPUPixel 链路收尾 —— 把后台处理完的人脸区域写回 FBO，再将整帧 blit 到屏幕
        if (gpActive) {
            // v2.0.185：修复「全屏闪烁」——
            // 本帧若因「后台仍在处理上一帧」而跳过回读（见 maybeScheduleFaceSampling 首行），
            // gpFbo 里是**未经 GPUPixel 处理的原始渲染结果**；若直接上屏，画面就会在
            // 「美颜帧 ↔ 原始帧」之间交替 = 肉眼看到的整个画面全屏闪烁。
            // 正确做法：跳过回读的帧**不呈现本帧原始内容**，而是把最近一次成功的美颜结果
            // 原样再上屏一次，屏幕因此恒定停留在一张美颜画面上（仅时间上略旧 1~2 帧）。
            //
            // ⚠️ 判据只看「本帧是否真的贴回了新结果」——这是**唯一充分**的条件：
            //   全覆盖模式下贴回覆盖整个视口，故「贴回过」⇒ gpFbo 整张 = 美颜画面；
            //   反之 gpFbo 里是本帧原始渲染（未美颜）。若拿 `!gpSkipReadThisFrame` 当有效判据
            //   就会漏掉「本帧没跳读、但后台还没处理完 ⇒ 也没有 pending」这一类帧 ——
            //   它们同样是未美颜的原始帧，正是闪烁的另一半来源。
            // ===== v2.0.187：两条输出通道 =====
            // · texture 通道（优先）：GPUPixel 结果留在 GPU 上，直接采样贴回 —— 零拷贝，
            //   省掉「glReadPixels 回读 8.29MB + JNI 拷贝 8.29MB + GL 线程再 memcpy 8.29MB」
            // · raw-data 通道（回退）：上面的 CPU 回读路径，行为与 v2.0.186 完全一致
            if (!applyGpTextureResult(gpResultTexIdPending)) {
                val hadPending = gpRegionPending.get() != null
                uploadPendingGpRegion()
                var fromStable = false
                if (!hadPending && gpHasStableResult && gpResultTexId != 0) {
                    fromStable = true   // 本帧无新美颜结果 → 复现上一张，杜绝「原始帧」闪现
                }
                if (hadPending) snapshotGpResult()   // 有新结果才刷新保底帧
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                if (fromStable) {
                    blitToScreen(gpResultTexId)   // 复现上一张已美颜画面（内容恒定）
                } else {
                    blitToScreen()                // 正常：呈现本帧的美颜结果
                }
            }
        } else if (halfActive) {
            // v2.0.177：跑 down(降采样) → blur(半分辨率低频)，再合成上屏。
            // ⚠️ 主渲染的磨皮段必须已被 uBeautyStrength = 0 关掉（见 uniform 同步处），
            //    否则会「主 shader 糊一次 + 半分辨率再糊一次」双重磨皮。
            runHalfSmoothingPasses()
            blendHalfPassToScreen()
        }

        // v2.1.233：本帧已经画完、尚未 swap —— 此刻把画面降采样一份给 Compose 的
        // backdrop，让玻璃/模糊效果有真实内容可糊（详见 VideoBackdrop 的注释）。
        captureBackdropIfNeeded()
        // v2.2.0（P3）：AI 弹幕取帧（低频、按需触发；未请求时零开销）
        captureDanmuFrameIfNeeded()

        // v2.6.16（P0）：本帧结算。这是 onDrawFrame 的**唯一出口**（上面无提前 return）。
        RenderStats.onFrameEnd(frameBeginNs, maxFps)
    }

    /**
     * 绑定视频/图片源纹理与 LUT（含占位回退）。
     *
     * 抽成独立函数是因为**华为 VR 双眼通路与内置分屏通路都要用**，
     * 且必须保证两条路径的纹理单元分配完全一致（unit1 = OES 视频、unit2 = LUT）。
     */
    private fun bindActiveTextures(prog: VRMainGLProgram, enhanceTexId: Int = 0) {
        if (enhanceTexId != 0) {
            // v2.0.206：增强纹理（MEMC / FSR 的输出）走 2D sampler 槽位。
            // ⚠️ 此时 prog 必然是 imageProgram（调用方已保证尺寸选择），其
            //    uSamplerVideo 声明为 sampler2D，绑 GL_TEXTURE_2D 才合法；
            //    OES 变体那个槽位是 samplerExternalOES，绑 2D 纹理属于未定义行为。
            // ⚠️ uIsVideo 必须置 1 —— 主 shader 里它唯一的用途就是「采样哪张纹理」，
            //    置 1 才会去读 uSamplerVideo（= 我们的增强纹理）。
            val base2d = if (imageTextureId != 0) imageTextureId else placeholderTextureId
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, base2d)
            uniform1i(prog.hSamplerImage, 0)
            uniform1i(prog.hIsVideo, 1)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, enhanceTexId)
            uniform1i(prog.hSamplerVideo, 1)
        } else if (isVideoActive) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
            uniform1i(prog.hSamplerVideo, 1)
            uniform1i(prog.hIsVideo, 1)
        } else {
            // Image/panorama: unit 0 gets the photo texture; unit 1 gets the plain
            // 2D placeholder so the image program's sampler2D slot stays consistent.
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId)
            uniform1i(prog.hSamplerImage, 0)
            uniform1i(prog.hIsVideo, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, placeholderTextureId)
            uniform1i(prog.hSamplerVideo, 1)
        }

        // LUT 视频滤镜：绑定到 TEXTURE2 并同步强度（绑定后统一回到 unit0）
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        if (lutTextureId != -1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
            uniform1i(prog.hLutTexture, 2)
            uniform1f(prog.hLutMix, lutMix)
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, placeholderTextureId)
            uniform1i(prog.hLutTexture, 2)
            uniform1f(prog.hLutMix, 0f)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun calculateAndApplyMatrices(isLeft: Boolean, aspect: Float, mvpLoc: Int) {
        // Flat projection (STANDARD / FISHEYE): use an orthographic projection so the
        // video/image quad fills the entire screen. The FOV slider becomes a zoom:
        // 75° = 1:1 full screen, larger FOV = zoom out, smaller = zoom in.
        val isPlanarMode = projectionMode == ProjectionMode.STANDARD ||
            projectionMode == ProjectionMode.FISHEYE
        if (isPlanarMode) {
            val zoom = (fovDeg / 75f).coerceIn(0.2f, 5f)
            Matrix.orthoM(projectionMatrix, 0, -zoom, zoom, -zoom, zoom, -5f, 5f)
        } else {
            // Perspective viewport configurations: Adjust FOV dynamically via Pinch-to-zoom
            Matrix.perspectiveM(projectionMatrix, 0, fovDeg, aspect, 0.1f, 100.0f)
        }

        // Setup standard eye look matrix looking inside the 3D dome / box
        if (projectionMode == ProjectionMode.VR_360 || projectionMode == ProjectionMode.VR_180 ||
            projectionMode == ProjectionMode.BOX || projectionMode == ProjectionMode.EAC ||
            projectionMode == ProjectionMode.DOME
        ) {
            // Looking inside a virtual sphere / box, eye is exactly at center origin (0, 0, 0)
            Matrix.setLookAtM(viewMatrix, 0, 
                0.0f, 0.0f, 0.0f, 
                0.0f, 0.0f, -1.0f, 
                0.0f, 1.0f, 0.0f
            )
        } else {
            // standard direct camera positioning
            Matrix.setLookAtM(viewMatrix, 0, 
                0.0f, 0.0f, 2.5f, 
                0.0f, 0.0f, 0.0f, 
                0.0f, 1.0f, 0.0f
            )
        }

        // Apply physical eye pupillary deviation offset for immersive VR stereoscopic spacing
        if (isSplitScreenVR) {
            val baseIpd = 0.04f
            // vrIpdOffsetRatio 范围 -0.1f ~ 0.1f，动态换算到基准瞳距偏移
            val ipd = (baseIpd * (1.0f + vrIpdOffsetRatio * 2.0f)).coerceIn(0.01f, 0.10f)
            val offset = if (isLeft) -ipd else ipd
            Matrix.translateM(viewMatrix, 0, offset, 0.0f, 0.0f)
        }

        // Base model transform
        Matrix.setIdentityM(modelMatrix, 0)

        // For standard 2D planar projection of video, scale to fit according to original aspect ratio.
        // Applies to both videos (16:9, 4:3, ...) and images so nothing is stretched.
        if (isPlanarMode) {
            val srcW = if (isVideoActive) videoWidth else imageWidth
            val srcH = if (isVideoActive) videoHeight else imageHeight
            if (srcW > 0 && srcH > 0) {
                val aScreen = aspect
                val rawAspect = srcW.toFloat() / srcH.toFloat()
                // v2.6.9：3D 平面片源单眼画幅比例修正
                // 当处于左右立体 (SBS) 模式时：
                //   - Full-SBS (32:9，rawAspect > 2.8)：单眼实际长宽比是 (srcW / 2) / srcH (约为 16:9)
                //   - Half-SBS (16:9，单眼已被压扁)：shader 里横向采样 0..0.5 展在 Quad 上，
                //     拉伸后单眼应当恢复为原本标准的长宽比 (即 rawAspect，16:9)
                // 同理 TAB (上下立体)：
                //   - Full-TAB (8:9，rawAspect < 1.0)：单眼实际长宽比是 srcW / (srcH / 2) (约为 16:9)
                //   - Half-TAB (16:9，上下被压扁)：拉伸后单眼恢复为 rawAspect (16:9)
                val aSrc = when (stereoMode) {
                    StereoMode.SBS -> if (rawAspect > 2.8f) (srcW.toFloat() / 2f) / srcH.toFloat() else rawAspect
                    StereoMode.TAB -> if (rawAspect < 1.0f) srcW.toFloat() / (srcH.toFloat() / 2f) else rawAspect
                    else -> rawAspect
                }
                var scaleX = 1.0f
                var scaleY = 1.0f
                if (aSrc > aScreen) {
                    // Source is wider than the display: match width, letterbox top/bottom
                    scaleY = aScreen / aSrc
                } else {
                    // Source is taller: match height, pillarbox left/right
                    scaleX = aSrc / aScreen
                }
                // v2.6.9 P2：Cardboard 分屏 VR 适眼视距缩放（Cinema Scale）
                // 默认 0.82f，留出舒适暗场边缘，防止画面边缘被凸透镜色散模糊或字幕裁切
                if (isSplitScreenVR) {
                    val factor = vrCinemaScale.coerceIn(0.5f, 1.0f)
                    scaleX *= factor
                    scaleY *= factor
                }
                Matrix.scaleM(modelMatrix, 0, scaleX, scaleY, 1.0f)

                // Diagnostic: log once whenever the fit parameters change
                if (scaleX != lastScaleX || scaleY != lastScaleY || srcW != lastSrcW) {
                    Log.d(TAG, "2D fit: src=${srcW}x${srcH} aspect=$aSrc screenAspect=$aScreen scale=$scaleX,$scaleY")
                    lastScaleX = scaleX
                    lastScaleY = scaleY
                    lastSrcW = srcW
                }
            }
        }

        // 3D sensor camera rotations tracking.
        // When the gyro is enabled the sensor drives the view, and the manual
        // swipe yaw/pitch is layered on top as a persistent viewing offset, so the
        // user can still swipe to adjust the viewpoint while looking around with
        // the gyroscope (offset is applied to the world first, then the head pose).
        val isPanorama = projectionMode == ProjectionMode.VR_360 || projectionMode == ProjectionMode.VR_180 ||
            projectionMode == ProjectionMode.BOX || projectionMode == ProjectionMode.EAC ||
            projectionMode == ProjectionMode.DOME
        val gyroActive = isPanorama && gyroEnabled


        if (gyroActive) {
            val currentGyro = FloatArray(16)
            synchronized(gyroSyncLock) {
                System.arraycopy(gyroRotationMatrix, 0, currentGyro, 0, 16)
            }

            // v125：这里旋转的是**全景球模型**，不是相机，因此直接用传感器给出的
            // 相对姿态矩阵（R_当前 × R_基准ᵀ）即可。
            //
            // 旧实现多做了一次转置（用逆矩阵），实测在多数机型上表现为
            // 上下左右全部反向——头往上抬画面往下跑、头往左转画面往右跑。
            // 现在默认直接使用；少数机型若仍相反，可用 [gyroInverted] 开关切回转置。
            val gyroMat = if (gyroInverted) {
                val inv = FloatArray(16)
                Matrix.transposeM(inv, 0, currentGyro, 0)
                inv
            } else {
                currentGyro
            }


            // model = gyro * manualOffset * scale
            val manualMat = FloatArray(16)
            Matrix.setIdentityM(manualMat, 0)
            Matrix.rotateM(manualMat, 0, manualPitch, 1.0f, 0.0f, 0.0f)
            Matrix.rotateM(manualMat, 0, manualYaw, 0.0f, 1.0f, 0.0f)
            Matrix.multiplyMM(modelMatrix, 0, manualMat, 0, modelMatrix, 0)
            Matrix.multiplyMM(modelMatrix, 0, gyroMat, 0, modelMatrix, 0)
        } else if (projectionMode != ProjectionMode.STANDARD) {
            // Apply touch swipes manual rotational overrides for all projection modes except standard 2D
            Matrix.rotateM(modelMatrix, 0, manualPitch, 1.0f, 0.0f, 0.0f)
            Matrix.rotateM(modelMatrix, 0, manualYaw, 0.0f, 1.0f, 0.0f)
        }

        // Combine MVP matrix
        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)

        // Pass MVP matrix uniform to shaders
        uniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0)
    }

    /**
     * Minimal fallback rendering when the main shader failed to compile:
     * draws the current image texture as a full-screen quad (video requires
     * the OES extension and is left black on such devices).
     */
    private fun drawFallbackFrame() {
        if (fallbackProgramId == -1 || isVideoActive) return

        GLES20.glUseProgram(fallbackProgramId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId)
        GLES20.glUniform1i(hFallbackSampler, 0)

        GLES20.glEnableVertexAttribArray(hFallbackPosition)
        GLES20.glVertexAttribPointer(hFallbackPosition, 3, GLES20.GL_FLOAT, false, 3 * 4, quadPositionBuffer)
        GLES20.glEnableVertexAttribArray(hFallbackTexCoord)
        GLES20.glVertexAttribPointer(hFallbackTexCoord, 2, GLES20.GL_FLOAT, false, 2 * 4, quadTexCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, GeometryHelper.quadVertexCount)
        GLES20.glDisableVertexAttribArray(hFallbackPosition)
        GLES20.glDisableVertexAttribArray(hFallbackTexCoord)
    }

    private fun drawActiveGeometry(prog: VRMainGLProgram) {
        var posBuf: FloatBuffer = quadPositionBuffer
        var texBuf: FloatBuffer = quadTexCoordBuffer
        var totalVertices = GeometryHelper.quadVertexCount
        var drawMethod = GLES20.GL_TRIANGLE_STRIP

        // Choose mesh depending on projection
        when (projectionMode) {
            ProjectionMode.STANDARD, ProjectionMode.FISHEYE -> {
                posBuf = quadPositionBuffer
                texBuf = quadTexCoordBuffer
                totalVertices = GeometryHelper.quadVertexCount
                drawMethod = GLES20.GL_TRIANGLE_STRIP
            }
            ProjectionMode.VR_360 -> {
                sphere360Positions?.let { pos ->
                    sphere360TexCoords?.let { tex ->
                        posBuf = pos
                        texBuf = tex
                        totalVertices = sphere360VertexCount
                        drawMethod = GLES20.GL_TRIANGLES
                    }
                }
            }
            ProjectionMode.VR_180 -> {
                sphere180Positions?.let { pos ->
                    sphere180TexCoords?.let { tex ->
                        posBuf = pos
                        texBuf = tex
                        totalVertices = sphere180VertexCount
                        drawMethod = GLES20.GL_TRIANGLES
                    }
                }
            }
            ProjectionMode.BOX -> {
                boxPositions?.let { pos ->
                    boxTexCoords?.let { tex ->
                        posBuf = pos
                        texBuf = tex
                        totalVertices = boxVertexCount
                        drawMethod = GLES20.GL_TRIANGLES
                    }
                }
            }
            // v2.1.212：Dome —— 上半球网格
            ProjectionMode.DOME -> {
                domePositions?.let { pos ->
                    domeTexCoords?.let { tex ->
                        posBuf = pos
                        texBuf = tex
                        totalVertices = domeVertexCount
                        drawMethod = GLES20.GL_TRIANGLES
                    }
                }
            }
            // v2.1.210：EAC —— 与 VR_360 同一套球面几何，仅 UV 不同
            ProjectionMode.EAC -> {
                eacPositions?.let { pos ->
                    eacTexCoords?.let { tex ->
                        posBuf = pos
                        texBuf = tex
                        totalVertices = eacVertexCount
                        drawMethod = GLES20.GL_TRIANGLES
                    }
                }
            }
        }

        // Pass positions
        GLES20.glEnableVertexAttribArray(prog.hPosition)
        GLES20.glVertexAttribPointer(prog.hPosition, 3, GLES20.GL_FLOAT, false, 3 * 4, posBuf)

        // Pass texture coordinates
        GLES20.glEnableVertexAttribArray(prog.hTextureCoord)
        GLES20.glVertexAttribPointer(prog.hTextureCoord, 2, GLES20.GL_FLOAT, false, 2 * 4, texBuf)

        // Draw arrays
        GLES20.glDrawArrays(drawMethod, 0, totalVertices)

        // Disable attrib arrays
        GLES20.glDisableVertexAttribArray(prog.hPosition)
        GLES20.glDisableVertexAttribArray(prog.hTextureCoord)
    }

    // Helper functions
    private fun VRGLShaders.compileShader(type: Int, code: String): Int {
        val shaderId = GLES20.glCreateShader(type)
        if (shaderId == 0) {
            throw RuntimeException("Could not create shader descriptor!")
        }
        GLES20.glShaderSource(shaderId, code)
        GLES20.glCompileShader(shaderId)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shaderId, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shaderId)
            GLES20.glDeleteShader(shaderId)
            throw RuntimeException("Shader compilation failed: $log")
        }
        return shaderId
    }

    private fun createOESTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val texId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE)
        return texId
    }

    private fun createStandardTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val texId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE)
        return texId
    }

    /**
     * 分块上传图片纹理（v2.0.181）。
     *
     * 为什么不用 `GLUtils.texImage2D`：它在上传前会 `copyPixelsToBuffer` 出一份**等大**的
     * 像素缓冲。对 4096×2048 来说就是额外 32 MB 的 Java 侧大对象，与调用方持有的 `Bitmap`
     * 叠加后峰值极高。本函数改为：
     *  1. `glTexImage2D(..., null)` 先在显存里开辟 [w]×[h]（**不**产生 Java 大对象）；
     *  2. 按 [uploadChunkRows] 行一条，`Bitmap.getPixels` 取出的 int[] 转 RGBA byte[]（GLES2
     *     没有 `GL_UNSIGNED_INT_8_8_8_8` 这类像素类型，必须自己拆包）；
     *  3. `glTexSubImage2D` 逐条覆盖。
     *
     * 于是 Java 侧峰值从「整图 ×2」降到「条带 ×2」（1 MB 级），且与整图尺寸解耦。
     * 复用 [chunkPixelBuf] / [chunkByteBuf]，稳态下几乎零分配。
     */
    private fun uploadImageChunked(bitmap: Bitmap) {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId)
        // 1) 开辟显存（pixels = null，不上传数据）
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        val glErr = GLES20.glGetError()
        if (glErr != GLES20.GL_NO_ERROR) {
            // 显存不够（4096×2048 RGBA = 32 MB）→ 退回一次性上传，至少保证有画面
            Log.w(TAG, "uploadImageChunked: glTexImage2D failed err=$glErr, fallback to GLUtils")
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            return
        }

        val rows = uploadChunkRows.coerceAtMost(h)
        val pxSize = w * rows
        val needPx = pxSize
        val needByte = pxSize * 4
        if (chunkPixelBuf == null || chunkPixelBuf!!.size < needPx) chunkPixelBuf = IntArray(needPx)
        if (chunkByteBuf == null || chunkByteBuf!!.size < needByte) chunkByteBuf = ByteArray(needByte)
        val px = chunkPixelBuf!!
        val bytes = chunkByteBuf!!

        var y = 0
        while (y < h) {
            val band = (h - y).coerceAtMost(rows)
            val n = w * band
            // 2) 取一条带的 ARGB（Bitmap 是 ARGB_8888 → premultiplied）
            bitmap.getPixels(px, 0, w, 0, y, w, band)
            // 3) 拆包成 RGBA，并做 un-premultiply
            var i = 0
            var j = 0
            while (i < n) {
                val c = px[i]
                val a = (c ushr 24) and 0xFF
                var r = (c ushr 16) and 0xFF
                var g = (c ushr 8) and 0xFF
                var b = c and 0xFF
                if (a != 0 && a != 255) {
                    // Bitmap 像素是预乘的；GL 上传后还会再乘一次 alpha，需还原
                    r = (r * 255 / a).coerceAtMost(255)
                    g = (g * 255 / a).coerceAtMost(255)
                    b = (b * 255 / a).coerceAtMost(255)
                }
                bytes[j] = r.toByte(); j++
                bytes[j] = g.toByte(); j++
                bytes[j] = b.toByte(); j++
                bytes[j] = a.toByte(); j++
                i++
            }
            val buf = java.nio.ByteBuffer.wrap(bytes, 0, n * 4)
                .order(java.nio.ByteOrder.nativeOrder())
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, 0, y, w, band,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
            )
            y += band
        }
        Log.i(TAG, "chunked image upload ${w}x$h in bands of $rows rows (peak Java ${needByte / 1024} KB)")
    }

    // ===== v104 LUT 视频滤镜：纹理上传与公开接口 =====

    /**
     * 设置 LUT 滤镜（任意线程调用，纹理在 GL 线程上传）。
     * @param rgba 512x512 RGBA 数据（LutUtils.parseCubeToRgba 产物）；null 表示关闭
     */
    fun setLutTexture(rgba: ByteArray?) {
        if (rgba == null) {
            pendingLutRgba = ByteArray(0) // 空数组标记清除
        } else {
            pendingLutRgba = rgba
        }
    }

    /** GL 线程：上传/更新 LUT 纹理；空数据则删除纹理（关闭滤镜） */
    private fun uploadLutTexture(rgba: ByteArray) {
        try {
            if (rgba.isEmpty()) {
                if (lutTextureId != -1) {
                    GLES20.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
                    lutTextureId = -1
                }
                return
            }
            if (lutTextureId == -1) {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                lutTextureId = ids[0]
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val buf = java.nio.ByteBuffer.wrap(rgba)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                LutUtils.TEX_SIZE, LutUtils.TEX_SIZE, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
            )
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            Log.i(TAG, "LUT texture uploaded (${rgba.size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "LUT texture upload failed", e)
        }
    }

    fun release() {
        videoSurfaceTexture?.setOnFrameAvailableListener(null)
        videoSurfaceTexture?.release()
        videoSurfaceTexture = null
        try {
            // v2.0.177：半分辨率磨皮 pass 的 FBO / program 清理
            halfPassBeauty.releasePrograms()
            // v2.0.206：画质增强（MEMC / FSR）的 program 与 FBO 清理
            releaseVideoEnhance()
        } catch (e: Throwable) {
            // ignore
        }
        try {
            // v2.6.22（P0-1）：**先置位**再 shutdown —— 之后所有提交点直接放弃，
            // 不再出现「刚 shutdown 就被提交 → RejectedExecutionException 冒到 onDrawFrame +
            // gpInFlight / isDetectingFace 计数泄漏导致检测永久停摆」。
            executorsClosed = true
            faceExecutor.shutdownNow()
            faceInitExecutor.shutdownNow()
            // v2.4.38（美颜审查 #10）：后台初始化线程一并关闭
            gpInitExecutor.shutdownNow()
            // v2.0.186（P0-B）：GPUPixel 检测线程也要一并关闭
            gpDetectExecutor.shutdownNow()
            // 🔴 v2.4.33：**必须等检测线程真正结束**，否则它可能正在 `Pipeline.detect()`
            //   里跑 native 检测，而紧接着的 `GpuPixelBeauty.release()` 会把 GL 资源与
            //   FaceDetector 销毁掉 → use-after-free / SIGSEGV。
            //   `shutdownNow` 只是「请求中断」，不等待；这里显式 awaitTermination。
            runCatching { gpDetectExecutor.awaitTermination(300, java.util.concurrent.TimeUnit.MILLISECONDS) }
            runCatching { faceExecutor.awaitTermination(300, java.util.concurrent.TimeUnit.MILLISECONDS) }
            // v2.4.33（审查 #3）：**GPUPixel 的原生资源此前从未释放** ——
            //   `GpuPixelBeauty.release()` 全工程无调用点，Pipeline / FaceDetector /
            //   Source/Sink 滤镜在退出视频后仍存活；GpuPixelBeauty 是单例，会跨视频残留状态。
            //   本方法运行在**创建 GPUPixel 上下文的 GL 线程**，正是销毁 GL 资源要求的线程。
            runCatching { GpuPixelBeauty.release() }
                .onFailure { Log.w(TAG, "GpuPixelBeauty.release 失败", it) }
            mediaPipeManager?.release()
            mediaPipeManager = null
            if (lutTextureId != -1) {
                GLES20.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
                lutTextureId = -1
            }
        } catch (e: Throwable) {
            // ignore
        }
    }

    /**
     * 是否真的需要人脸检测（v2.0.156）。
     *
     * 只有**依赖面部坐标**的效果才需要它：瘦脸 / 大眼 / 鼻 / 嘴 / 牙 / 口红 / 腮红 /
     * 眉毛 / 黑眼圈 / 长腿 / 小头。磨皮、美白、亮度、对比度都与人脸位置无关，
     * 因此不开这些效果的用户**完全不必付出**每 8 帧回读 1MB 的代价。
     * 「对比原图」模式下 shader 会强制 `uFaceDetected = 0`，同样无需检测。
     */
    internal fun isBeautyActive(): Boolean {
        if (!beautyMasterEnabled) return false
        // v2.0.160：GPUPixel 引擎激活时（含 VR 人脸美颜），区域处理本身也需要采样
        if (isGpuPixelActive()) return true
        return beautyFaceSlimming > 0.001f || beautyBigEyes > 0.001f ||
            beautyDarkCircles > 0.001f || beautyNoseSlimming > 0.001f ||
            beautyMouth > 0.001f || beautyTeethWhitening > 0.001f ||
            beautyLipstick > 0.001f || beautyBlush > 0.001f ||
            beautyEyebrows > 0.001f || beautyLongLegs > 0.001f ||
            beautySmallHead > 0.001f
    }

    /**
     * 把 MediaPipe 在「采样裁剪图」上的归一化 x 换算成**视口 UV**。
     *
     * 采样区是视口中央的 `rw×rh` 像素，而 shader 的 `tc` 覆盖整个视口 ——
     * 此前直接把裁剪图坐标当视口 UV 用，人脸偏离画面中心时妆容/变形就整体错位
     * （1080p 下最大偏差约 ±0.27 屏宽）。因为采样区**居中且对称**，换算就是
     * 「以 0.5 为中心按比例缩放」。
     */
    internal fun mapCropXToViewport(cx: Float): Float = 0.5f + (cx - 0.5f) * faceCropScaleX

    /**
     * 同上，y 方向**额外翻转一次**：
     * `glReadPixels` 的行序是自下而上，却被按行直接填进 Bitmap（自上而下），
     * 于是 Bitmap 相对屏幕上下颠倒 —— MediaPipe 报上来的 y 与屏幕方向相反。
     * 采样区垂直居中，故翻转与缩放合并为 `0.5 - (cy - 0.5) * scaleY`。
     */
    internal fun mapCropYToViewport(cy: Float): Float = 0.5f - (cy - 0.5f) * faceCropScaleY

    /** 长度量（如眼距）按水平比例缩放：眼距主要是水平距离，见 [mapCropXToViewport] */
    internal fun mapCropLenToViewport(len: Float): Float = len * faceCropScaleX

    /**
     * 视情况采一帧屏幕内容交给后台做人脸检测（v2.0.156 重构）。
     *
     * 与旧实现的区别：
     *  1. **仅在有面部效果时采样** —— 不再让「只用磨皮 / 不开美颜」的用户白付回读开销；
     *  2. **分屏 VR 模式也采样**，但只在**单眼视口**内取中心区域
     *     （跨两眼取样会得到两张脸拼在一起，无法检测）；
     *  3. 采样窗口相对视口的比例记进 [faceCropScaleX] / [faceCropScaleY] 供坐标换算；
     *  4. 回读缓冲与帧数组**复用**，不再每 8 帧产生 2MB 垃圾。
     *
     * 注：`glReadPixels` 是同步回读，**未**使用 PBO 异步化（PBO 需 GLES3 + 驱动支持
     * `GL_PIXEL_PACK_BUFFER`，且本项目主 shader 仍为 ESSL 100、未启用 ES3 专属路径），
     * 故通过「减少无谓采样 + 复用缓冲」控制代价。
     */
    // ===== v2.0.160：GPUPixel 双引擎支持 =====

    /**
     * GPUPixel 链路是否激活：总开关 + 引擎选择 + 初始化成功 +（平面投影 或 VR 人脸美颜开关）。
     *
     * v2.0.182：一度去掉了最后一项（当时 VR 被改成无条件全覆盖），使 [gpuPixelVrFaceBeauty]
     * 退化成 UI 占位。
     * v2.0.184：**恢复为真开关（默认开）** —— 用户的意图是「这个开关要真的能控制 VR 下的美颜」，
     * 而不是让它永远生效。故：
     *   - 平面投影（STANDARD / FISHEYE）：始终生效（开关与它无关）
     *   - 3D / VR（VR_360 / VR_180 / VR_BOX 等）：由 [gpuPixelVrFaceBeauty] 控制
     * 开关关闭时 VR 下退回**纯 GLSL 美颜**（GLSL 在 VR 下的面部效果本就受限，见下方注释）。
     */
    internal fun isGpuPixelActive(): Boolean {
        if (!beautyMasterEnabled || effectiveBeautyEngine != BEAUTY_ENGINE_GPUPIXEL) return false
        if (!GpuPixelBeauty.available) return false
        return isPlanarProjection() || gpuPixelVrFaceBeauty
    }

    private fun isPlanarProjection(): Boolean =
        projectionMode == ProjectionMode.STANDARD || projectionMode == ProjectionMode.FISHEYE

    /** 创建（或按尺寸重建）GPUPixel 模式的离屏 FBO */
    private fun ensureGpFbo(w: Int, h: Int) {
        if (gpFboId != 0 && gpFboW == w && gpFboH == h) return
        releaseGpFbo()
        // v2.0.187 阶段2：创建**两张**并轮转（texture 输入通道下防撕裂，见字段注释）
        val tex = IntArray(2)
        val fbo = IntArray(2)
        GLES20.glGenTextures(2, tex, 0)
        GLES20.glGenFramebuffers(2, fbo, 0)
        for (i in 0..1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[i])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
            )
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, tex[i], 0
            )
            // v2.0.185：校验完整性 —— 不完整时后续绘制/回读/上屏会静默失败（黑屏或残留内容）
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                Log.w(TAG, "gpFbo[$i] incomplete: 0x${Integer.toHexString(status)}")
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glDeleteTextures(2, tex, 0)
                GLES20.glDeleteFramebuffers(2, fbo, 0)
                return
            }
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        gpFboTexIdArr = tex
        gpFboIdArr = fbo
        gpFboIdx = 0
        gpFboTexId = tex[0]
        gpFboId = fbo[0]
        gpFboW = w
        gpFboH = h
        ensureBlitProgram()
    }

    /**
     * v2.0.187 阶段2：切到本帧要用的输入帧缓冲（双缓冲轮转）。
     *
     * 让 [gpFboTexId] / [gpFboId] 始终指向「当前帧」那张，从而让所有既有绘制/上屏
     * 代码**无需改动**即可受益。
     */
    private fun switchGpFboForFrame() {
        if (gpFboTexIdArr[0] == 0 || gpFboTexIdArr[1] == 0) return
        gpFboIdx = 1 - gpFboIdx
        gpFboTexId = gpFboTexIdArr[gpFboIdx]
        gpFboId = gpFboIdArr[gpFboIdx]
    }

    /** 本帧输入帧缓冲是否已交出去（在途）—— 在途时禁止再次切换，避免刚投递就被覆盖 */
    @Volatile private var gpInputTextureInFlight = 0

    private fun releaseGpFbo() {
        for (i in 0..1) {
            if (gpFboTexIdArr[i] != 0) GLES20.glDeleteTextures(1, intArrayOf(gpFboTexIdArr[i]), 0)
            if (gpFboIdArr[i] != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(gpFboIdArr[i]), 0)
            gpFboTexIdArr[i] = 0
            gpFboIdArr[i] = 0
        }
        gpFboIdx = 0
        gpFboTexId = 0; gpFboId = 0; gpFboW = 0; gpFboH = 0
        releaseGpHalfFbo()    // v2.0.183：降采样 FBO 随主 FBO 一起释放
        releaseGpUploadTex()  // v2.0.182：中转纹理随 FBO 一起释放
        releaseGpResultFbo()  // v2.0.185：保底帧随 FBO 一起释放
        releaseGpPbo()        // v2.0.186：PBO + 在途 fence 随 FBO 一起释放
    }

    // ===== v2.0.183：降采样 FBO（glBlitFramebuffer 真缩放，修 v2.0.182 错位） =====

    /** 创建（或按尺寸重建）GPUPixel 回读用的降采样 FBO */
    private fun ensureGpHalfFbo(w: Int, h: Int) {
        if (gpHalfFboId != 0 && gpHalfFboW == w && gpHalfFboH == h) return
        releaseGpHalfFbo()
        val tex = IntArray(1)
        val fbo = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        // LINEAR 让 glBlitFramebuffer 的缩小走线性滤波（比 NEAREST 少锯齿）
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
        // v2.0.185：校验完整性（原实现未查，导致 glBlitFramebuffer 失败难以定位）
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "gpHalfFbo incomplete: 0x${Integer.toHexString(status)}")
            GLES20.glDeleteTextures(1, tex, 0)
            GLES20.glDeleteFramebuffers(1, fbo, 0)
            return
        }
        gpHalfFboTexId = tex[0]
        gpHalfFboId = fbo[0]
        gpHalfFboW = w
        gpHalfFboH = h
    }

    private fun releaseGpHalfFbo() {
        if (gpHalfFboTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(gpHalfFboTexId), 0)
        if (gpHalfFboId != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(gpHalfFboId), 0)
        gpHalfFboTexId = 0; gpHalfFboId = 0; gpHalfFboW = 0; gpHalfFboH = 0
    }

    // ===== v2.0.185：保底帧（最近一次成功的美颜结果快照）=====

    /**
     * 创建（或按尺寸重建）「保底帧」FBO —— 用来存最近一次**成功贴回后**的完整画面。
     *
     * 为什么需要它：GPUPixel 的处理是异步的，后台忙时 GL 线程会跳帧（不回读、不贴回），
     * 这时 gpFbo 里只有原始渲染。若直接上屏就会出现「美颜帧 ↔ 原始帧」交替闪烁。
     * 有了这张快照，跳帧时改成重播它，屏幕内容恒定 → 闪烁消失（代价：画面略旧 1~2 帧）。
     */
    private fun ensureGpResultFbo(w: Int, h: Int) {
        if (gpResultFboId != 0 && gpResultW == w && gpResultH == h) return
        releaseGpResultFbo()
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
        // v2.0.185：校验 FBO 完整性 —— 不完整时后续 blit/上屏会静默失败（黑屏或花屏）
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "stable-result FBO incomplete: 0x${Integer.toHexString(status)}")
            GLES20.glDeleteTextures(1, tex, 0)
            GLES20.glDeleteFramebuffers(1, fbo, 0)
            gpHasStableResult = false
            return
        }
        gpResultTexId = tex[0]
        gpResultFboId = fbo[0]
        gpResultW = w
        gpResultH = h
        gpHasStableResult = false   // 新尺寸下旧内容作废
    }

    private fun releaseGpResultFbo() {
        if (gpResultTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(gpResultTexId), 0)
        if (gpResultFboId != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(gpResultFboId), 0)
        gpResultTexId = 0; gpResultFboId = 0; gpResultW = 0; gpResultH = 0
        gpHasStableResult = false
    }

    /**
     * 把当前 gpFbo（已贴回 GPUPixel 结果）整帧快照到保底帧纹理。
     *
     * 用 `glBlitFramebuffer` 让 GPU 直接搬（同尺寸 1:1，`GL_NEAREST` 无滤波开销），
     * 比「回读像素再上传」便宜一个数量级（1920×1080 回读 ≈ 8 MB 且同步阻塞）。
     */
    /**
     * v2.0.187：**texture 输出通道**的贴回 + 上屏。
     *
     * 与 raw-data 路径做的事完全对应（贴回回读区 → 刷新保底帧 → 上屏），但省掉了
     * CPU 往返：GPUPixel 的结果本就留在 GPU 上，这里直接把它按回读区 UV 拉伸贴回
     * `gpFbo` 即可（复用 raw-data 路径同一个 [blitScaledIntoFbo]）。
     *
     * ## 首次校验（关键）
     * 结果纹理由 GPUPixel 在自己的 EGLContext 里创建。**只有共享生效时**，主渲染
     * context 才能看到它 —— 故这里用 `glIsTexture` 做一次权威校验：
     *  - `true`  → 共享成立，后续帧直接用该通道
     *  - `false` → 共享未生效（GPUPixel 已回退私有 context／驱动拒绝共享），
     *             置 [gpTextureFallbackRequested] 让 Pipeline 切回 raw-data，
     *             本帧及后续都走 CPU 回读，功能不受影响
     *
     * @param tex 待贴回的结果纹理；0 表示本帧没有新结果
     * @return true = 已由本方法完成贴回与上屏；false = 调用方应走 raw-data 路径
     */
    private fun applyGpTextureResult(tex: Int): Boolean {
        if (gpTextureFallbackRequested) {
            gpResultTexIdPending = 0
            return false
        }

        if (gpTexturePathOk == null && tex != 0) {
            val visible = GLES30.glIsTexture(tex)
            gpTexturePathOk = visible
            Log.i(TAG, "GPUPixel texture path: glIsTexture($tex)=$visible (main context visibility)")
            if (!visible) {
                gpTextureFallbackRequested = true
                gpResultTexIdPending = 0
                Log.w(
                    TAG,
                    "GPUPixel texture path NOT available (shared EGL context ineffective); " +
                        "falling back to raw-data readback"
                )
                return false
            }
        }
        if (gpTexturePathOk != true) {
            gpResultTexIdPending = 0
            return false
        }

        if (tex != 0) {
            // 有新结果 → 记住它（SinkTexture 是双缓冲，本帧这张不会被下一帧覆盖）
            gpLastResultTex = tex
            gpResultTexIdPending = 0
            gpTextureAppliedFrames++

            // 回传消费端 fence：让 GPUPixel 在下一次写入前等待我们对这张的采样完成
            val sync = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            if (sync != 0L) {
                GpuPixelBeauty.getOrCreatePipeline().setConsumerFence(sync)
            }
        }

        // v2.0.187 阶段2：结果纹理本身就是「整屏美颜后的画面」（输入即整张 gpFbo），
        // 因此**直接上屏** —— 不再需要「贴回 gpFbo」那一步（那是 raw-data 通道为
        // 「输入帧缓冲与输出目标复用同一张」而做的妥协）。
        // 无新结果的帧复用上一张结果纹理，避免回落到未美颜的原始画面。
        val src = if (gpLastResultTex != 0) gpLastResultTex else gpFboTexId
        blitToScreen(src)
        return true
    }

    // =======================================================================
    private val backdropAndDanmu: VRGLBackdropAndDanmu by lazy {
        VRGLBackdropAndDanmu(
            getDisplayWidth = { displayWidth },
            getDisplayHeight = { displayHeight },
            isSplitScreen = { isSplitScreenVR },
        )
    }

    fun requestDanmuFrame(maxWidth: Int = 1024): Boolean = backdropAndDanmu.requestDanmuFrame(maxWidth)
    fun takeDanmuFrame(): Triple<IntArray, Int, Int>? = backdropAndDanmu.takeDanmuFrame()
    private fun releaseDanmuTarget() = backdropAndDanmu.releaseDanmuTarget()
    private fun releaseBackdropTarget() = backdropAndDanmu.releaseBackdropTarget()
    internal fun captureDanmuFrameIfNeeded() = backdropAndDanmu.captureDanmuFrameIfNeeded()
    private fun captureBackdropIfNeeded() = backdropAndDanmu.captureBackdropIfNeeded()

    private fun snapshotGpResult() {
        if (gpFboTexId == 0 || gpFboId == 0) return
        ensureGpResultFbo(gpFboW, gpFboH)
        if (gpResultFboId == 0) return
        GLES20.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, gpFboId)
        GLES20.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, gpResultFboId)
        GLES30.glBlitFramebuffer(
            0, 0, gpFboW, gpFboH,
            0, 0, gpResultW, gpResultH,
            GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_NEAREST
        )
        val err = GLES20.glGetError()
        GLES20.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        GLES20.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        if (err != GLES20.GL_NO_ERROR) {
            Log.w(TAG, "snapshotGpResult blit failed err=$err")
            gpHasStableResult = false
            return
        }
        gpHasStableResult = true
    }

    /**
     * 把主 FBO 的 [srcUvX, srcUvY, srcUvW, srcUvH] 区域**真正缩放**渲染到 [dstW]×[dstH]
     * 的降采样 FBO（GPU 线性滤波），随后可从降采样 FBO 读像素。
     *
     * 这是「降采样回读」唯一正确的实现方式 —— `glReadPixels` 只裁剪不缩放。
     *
     * 返回是否成功（FBO 不完整 / 尺寸非法则返回 false，调用方退回全分辨率回读）。
     */
    internal fun blitFboToHalf(srcUvX: Float, srcUvY: Float, srcUvW: Float, srcUvH: Float,
                              dstW: Int, dstH: Int): Boolean {
        if (gpFboId == 0 || gpFboTexId == 0) return false
        if (dstW <= 0 || dstH <= 0) return false
        ensureGpHalfFbo(dstW, dstH)
        if (gpHalfFboId == 0) return false

        // 源矩形（主 FBO 像素坐标，GL 左下原点）—— 用 UV × 主 FBO 尺寸，需夹紧
        val sx0 = (srcUvX * gpFboW).toInt().coerceIn(0, gpFboW)
        val sy0 = (srcUvY * gpFboH).toInt().coerceIn(0, gpFboH)
        val sx1 = ((srcUvX + srcUvW) * gpFboW).toInt().coerceIn(sx0, gpFboW)
        val sy1 = ((srcUvY + srcUvH) * gpFboH).toInt().coerceIn(sy0, gpFboH)
        val sw = sx1 - sx0
        val sh = sy1 - sy0
        if (sw <= 0 || sh <= 0) return false

        // v2.0.185：先清一遍历史错误，避免把上一次调用遗留的错误误判成本次失败
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* drain */ }

        GLES20.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, gpFboId)
        GLES20.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, gpHalfFboId)
        GLES30.glBlitFramebuffer(
            sx0, sy0, sx1, sy1,
            0, 0, dstW, dstH,
            GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_LINEAR
        )
        // ⚠️ 必须先取错误再解绑 —— glBindFramebuffer 本身也会改写错误状态，
        //    若放在解绑之后读取，blit 的真实错误可能已被后续调用清掉（漏判）。
        val err = GLES20.glGetError()
        GLES20.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        GLES20.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        if (err != GLES20.GL_NO_ERROR) {
            Log.w(TAG, "glBlitFramebuffer downscale failed err=$err, fallback to full-res read")
            return false
        }
        return true
    }

    // ===== v2.0.186（P1-C）：PBO 异步回读的三个辅助函数 =====

    /**
     * 按需创建/重建两个 STREAM_READ 类型的 PBO（双缓冲轮转）。
     *
     * 创建后立刻检查 `glGetError`：部分老驱动（尤其 Mali 的早期 ES3 实现）对
     * `GL_PIXEL_PACK_BUFFER` 支持不完整，出错就永久回退到同步读。
     *
     * @return 是否可继续使用 PBO 路径
     */
    internal fun ensureGpPbo(bytes: Int): Boolean {
        if (!gpPboEnabled) return false
        if (gpPboIds[0] != 0 && gpPboIds[1] != 0 && gpPboCapBytes >= bytes) return true
        releaseGpPbo()
        // 先 drain 历史错误，避免把上一次调用遗留的错误误判成本次失败
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* drain */ }
        val ids = IntArray(2)
        GLES30.glGenBuffers(2, ids, 0)
        if (ids[0] == 0 || ids[1] == 0) {
            Log.w(TAG, "PBO glGenBuffers failed, fallback to sync readback")
            gpPboEnabled = false
            return false
        }
        for (id in ids) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, id)
            // ⚠️ Android 的 glBufferData(int target, int size, Buffer, int usage) 的 size 是 **Int**
            GLES30.glBufferData(
                GLES30.GL_PIXEL_PACK_BUFFER, bytes, null, GLES30.GL_STREAM_READ
            )
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) {
            Log.w(TAG, "PBO alloc failed err=$err, fallback to sync readback")
            for (id in ids) GLES30.glDeleteBuffers(1, intArrayOf(id), 0)
            gpPboEnabled = false
            return false
        }
        gpPboIds[0] = ids[0]
        gpPboIds[1] = ids[1]
        gpPboCapBytes = bytes
        gpPboIdx = 0
        gpPboActive = false
        Log.i(TAG, "GPUPixel async readback (PBO+fence) enabled, ${bytes} bytes/eye")
        return true
    }

    /** 释放 PBO 与在途 fence（也用于「尺寸变化 → 在途作废」的重置） */
    internal fun releaseGpPbo() {
        discardGpPboPending()
        for (i in 0..1) {
            if (gpPboIds[i] != 0) {
                GLES30.glDeleteBuffers(1, intArrayOf(gpPboIds[i]), 0)
                gpPboIds[i] = 0
            }
        }
        gpPboCapBytes = 0
        gpPboIdx = 0
    }

    /** 丢弃在途 fence 与尺寸记账（PBO 本体保留，可继续复用） */
    internal fun discardGpPboPending() {
        for (i in 0..1) {
            if (gpPboFence[i] != 0L) {
                GLES30.glDeleteSync(gpPboFence[i])
                gpPboFence[i] = 0L
            }
        }
        gpPboActive = false
        gpPboPendingBytes = 0
        gpPboPendingW = 0
        gpPboPendingH = 0
    }

    /**
     * 收割上一帧提交的回读（**非阻塞**）。
     *
     * 返回语义（调用方必须配合 [gpPboActive] 判断）：
     *  - 返回非 null：已就绪并拷出数据，[gpPboActive] 置 false
     *  - 返回 null 且 [gpPboActive] 仍为 true：**GPU 还没拷完** → 本帧没有新输入，
     *    调用方应把本帧交给保底帧（不阻塞、不丢帧）
     *  - 返回 null 且 [gpPboActive] 为 false：本来就没有在途任务（首帧 / 刚重置）
     */
    internal fun collectGpPbo(need: Int): ByteArray? {
        if (!gpPboActive) return null
        val prev = 1 - gpPboIdx
        val st = GLES30.glClientWaitSync(
            gpPboFence[prev], GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, 0L
        )
        if (st == GLES30.GL_WAIT_FAILED) {
            Log.w(TAG, "glClientWaitSync failed, fallback to sync readback")
            gpPboEnabled = false
            releaseGpPbo()
            return null
        }
        if (st != GLES30.GL_ALREADY_SIGNALED && st != GLES30.GL_CONDITION_SATISFIED) {
            // 正常情况 GPU 应在一个帧间隔内拷完；连续多帧不就绪说明驱动/环境异常，
            // 回退同步读以保证「总会有新数据」，而不是长期停在保底帧上。
            gpPboStall++
            if (gpPboStall >= 60) {
                Log.w(TAG, "PBO fence stalled $gpPboStall frames, fallback to sync readback")
                gpPboEnabled = false
                releaseGpPbo()
            }
            return null  // 仍在途：gpPboActive 保持 true
        }
        gpPboStall = 0
        GLES30.glDeleteSync(gpPboFence[prev])
        gpPboFence[prev] = 0L

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, gpPboIds[prev])
        val mapped = GLES30.glMapBufferRange(
            GLES30.GL_PIXEL_PACK_BUFFER, 0, need, GLES30.GL_MAP_READ_BIT
        )
        if (mapped == null) {
            Log.w(TAG, "glMapBufferRange returned null, fallback to sync readback")
            glUnbindPackBufferAndDisable()
            return null
        }
        val mb = mapped as java.nio.ByteBuffer
        mb.position(0)
        mb.limit(need)
        val recycled = synchronized(faceFrameLock) {
            faceFramePool?.takeIf { it.size >= need }?.also { faceFramePool = null }
        }
        val f = recycled ?: ByteArray(need)
        mb.get(f, 0, need)
        GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
            Log.w(TAG, "PBO map/unmap error, fallback to sync readback")
            glUnbindPackBufferAndDisable()
            return null
        }
        gpPboActive = false
        return f
    }

    private fun glUnbindPackBufferAndDisable() {
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        gpPboEnabled = false
        releaseGpPbo()
    }

    /**
     * 提交本帧回读到 PBO（不等待 GPU），只挂一个 fence 供下一帧收割。
     *
     * ⚠️ `glReadPixels(..., offset=0L)` 的 offset 形式**只在绑定了
     * `GL_PIXEL_PACK_BUFFER` 时有效**（ES3 语义），故绑定/解绑必须成对。
     *
     * @return 是否提交成功（失败则由调用方回退同步路径）
     */
    internal fun submitGpPboRead(
        srcIsHalf: Boolean, x0: Int, y0: Int, rw: Int, rh: Int, need: Int
    ): Boolean {
        val pbo = gpPboIds[gpPboIdx]
        if (pbo == 0) return false
        while (GLES20.glGetError() != GLES20.GL_NO_ERROR) { /* drain */ }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo)
        GLES20.glBindFramebuffer(
            GLES20.GL_FRAMEBUFFER,
            if (srcIsHalf) gpHalfFboId else gpFboId
        )
        GLES30.glReadPixels(
            if (srcIsHalf) 0 else x0,
            if (srcIsHalf) 0 else y0,
            rw, rh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
            // ⚠️ Android 的重载是 glReadPixels(..., int offset)（偏移量用 Int 表达），
            //    不是 GL 规范里的 void*；写成 0L 会因无匹配重载而编译失败。
            0
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) {
            Log.w(TAG, "glReadPixels(PBO) failed err=$err")
            return false
        }
        // ⚠️ fence 的 condition 常量是 GL_SYNC_GPU_COMMANDS_COMPLETE（没有 ..._BIT 这个名字）
        val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        if (fence == 0L) {
            Log.w(TAG, "glFenceSync failed")
            return false
        }
        gpPboFence[gpPboIdx] = fence
        gpPboIdx = 1 - gpPboIdx
        gpPboActive = true
        gpPboPendingBytes = need
        gpPboPendingW = rw
        gpPboPendingH = rh
        return true
    }

    /** 极简上屏 pass：把 FBO 纹理 1:1 画回屏幕（FBO 与屏幕同为 GL 左下原点约定，直接对应即可） */
    private fun ensureBlitProgram() {
        if (gpBlitProgram != 0) return
        val vs = "attribute vec2 aPos;attribute vec2 aTex;varying vec2 vTex;" +
            "void main(){gl_Position=vec4(aPos,0.0,1.0);vTex=aTex;}"
        val fs = "precision mediump float;varying vec2 vTex;uniform sampler2D uTex;" +
            "void main(){gl_FragColor=texture2D(uTex,vTex);}"
        val vsId = compileGpuShader(GLES20.GL_VERTEX_SHADER, vs)
        val fsId = compileGpuShader(GLES20.GL_FRAGMENT_SHADER, fs)
        if (vsId == 0 || fsId == 0) return
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vsId)
        GLES20.glAttachShader(prog, fsId)
        GLES20.glLinkProgram(prog)
        gpBlitProgram = prog
        gpBlitPosLoc = GLES20.glGetAttribLocation(prog, "aPos")
        gpBlitTexLoc = GLES20.glGetUniformLocation(prog, "uTex")
        // v2.0.186（P2-E1）：aTex 的 attrib location 也一并缓存 —— 原先 blitToScreen /
        // blitScaledIntoFbo 每帧各调一次 glGetAttribLocation（JNI 往返 + 字符串查表），
        // program 在进程内不变，location 恒定，无需逐帧查询。
        gpBlitTexAttrLoc = GLES20.glGetAttribLocation(prog, "aTex")
        if (gpBlitQuad == null) {
            // TRIANGLE_STRIP：左下、右下、左上、右上（pos x,y + tex u,v）
            gpBlitQuad = java.nio.ByteBuffer.allocateDirect(4 * 4 * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply {
                    put(floatArrayOf(
                        -1f, -1f, 0f, 0f,
                        1f, -1f, 1f, 0f,
                        -1f, 1f, 0f, 1f,
                        1f, 1f, 1f, 1f
                    ))
                    position(0)
                }
        }
    }

    private fun compileGpuShader(type: Int, src: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, src)
        GLES20.glCompileShader(id)
        return id
    }

    private val halfPassBeauty: VRGLHalfPassBeauty by lazy {
        VRGLHalfPassBeauty(
            getDisplayWidth = { displayWidth },
            getDisplayHeight = { displayHeight },
            isBeautyEnabled = { beautyMasterEnabled },
            getBeautyLevel = { beautyLevel },
            getTextureDetail = { beautyTextureDetail },
            getEffectiveEngine = { effectiveBeautyEngine },
            isGpuPixelActiveFn = { isGpuPixelActive() },
        )
    }
    private val halfQuad: java.nio.FloatBuffer get() = halfPassBeauty.halfQuad
    private val downFboId: Int get() = halfPassBeauty.downFboId
    private fun buildHalfPassProgram(fs: String): Int = halfPassBeauty.buildQuadProgram(fs)
    private fun ensureHalfPassPrograms() = halfPassBeauty.ensurePrograms()
    private fun ensureHalfPassFbos(w: Int, h: Int) = halfPassBeauty.ensureFbos(w, h)
    private fun releaseHalfPassFbos() = halfPassBeauty.releaseFbos()
    private fun isHalfPassActive(): Boolean = halfPassBeauty.isActive()
    private fun runHalfSmoothingPasses() = halfPassBeauty.runSmoothingPasses()
    private fun blendHalfPassToScreen() = halfPassBeauty.blendToScreen()
    //
    // ⚠️ 【一律不在此链路里做截图/回读】，只有 scene-cut 那一处 1x1 回读是必需的。
    //    全屏 glReadPixels 的教训见项目记忆（v2.0.182/183 的错位事故）。

    private val videoEnhance: VRGLVideoEnhance by lazy {
        VRGLVideoEnhance(halfQuad) { fs -> buildHalfPassProgram(fs) }
    }
    private fun syncVideoEnhanceState() {
        videoEnhance.memcEnabled = memcEnabled
        videoEnhance.memcTargetFps = memcTargetFps
        videoEnhance.fsrEnabled = fsrEnabled
        videoEnhance.fsrTargetWidth = fsrTargetWidth
        videoEnhance.fsrTargetHeight = fsrTargetHeight
        videoEnhance.isVideoActive = isVideoActive
        videoEnhance.videoTextureId = videoTextureId
        videoEnhance.imageTextureId = imageTextureId
        videoEnhance.videoWidth = videoWidth
        videoEnhance.videoHeight = videoHeight
        videoEnhance.imageWidth = imageWidth
        videoEnhance.imageHeight = imageHeight
        videoEnhance.displayWidth = displayWidth
        videoEnhance.displayHeight = displayHeight
    }
    private fun runVideoEnhance(newVideoFrame: Boolean): Int {
        syncVideoEnhanceState()
        return videoEnhance.run(newVideoFrame)
    }
    private fun releaseVideoEnhance() = videoEnhance.release()

    /**
     * 把 GPUPixel 结果 FBO 整帧上屏。
     *
     * @param srcTex 要上屏的纹理，默认 [gpFboTexId]（本帧 gpFbo）。
     *   v2.0.185 新增该参数：跳过回读的帧传入 [gpResultTexId]（保底帧），
     *   从而复现上一张已美颜画面，避免「美颜帧 ↔ 原始帧」交替闪烁。
     */
    private fun blitToScreen(srcTex: Int = gpFboTexId) {
        if (gpBlitProgram == 0 || gpBlitQuad == null) return
        if (srcTex == 0) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, gpFboW, gpFboH)
        // v2.0.186（P2-E3）：全屏 quad 完整铺满 viewport 时无需 glClear —— 这是每帧一次
        // 的全屏写带宽（1080p ≈ 8.3 MB）纯浪费。仅当 viewport 与 surface 尺寸不一致
        // （quad 无法铺满，四周会残留上一帧像素）才 clear 兜底黑边。
        // 注：glClear 不受 viewport 限制，清的是整个 framebuffer，故兜底语义正确。
        if (gpFboW != displayWidth || gpFboH != displayHeight) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }
        GLES20.glUseProgram(gpBlitProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
        GLES20.glUniform1i(gpBlitTexLoc, 0)
        val q = gpBlitQuad!!
        q.position(0)
        GLES20.glVertexAttribPointer(gpBlitPosLoc, 2, GLES20.GL_FLOAT, false, 16, q)
        GLES20.glEnableVertexAttribArray(gpBlitPosLoc)
        // v2.0.186（P2-E1）：用缓存的 location，不再每帧 glGetAttribLocation
        val texAttr = gpBlitTexAttrLoc
        if (texAttr >= 0) {
            q.position(2)
            GLES20.glVertexAttribPointer(texAttr, 2, GLES20.GL_FLOAT, false, 16, q)
            GLES20.glEnableVertexAttribArray(texAttr)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(gpBlitPosLoc)
        if (texAttr >= 0) GLES20.glDisableVertexAttribArray(texAttr)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /**
     * 把后台 GPUPixel 处理完的像素写回 FBO 纹理（区域与回读时一致）。
     *
     * v2.0.182：全覆盖模式下回读是**降采样**的（如 1080p → 960×540），
     * 而 FBO 是全分辨率（1920×1080）—— 不能再用 `glTexSubImage2D` 按同尺寸覆盖，
     * 否则只会填住左上角一块。改为：
     * ① 先把处理结果上传到一张**临时纹理**（尺寸 = 回读尺寸）；
     * ② 再用 blit shader 带缩放的把这张临时纹理**拉伸铺满**回读区对应的
     *    全分辨率区域（全覆盖时就是整个视口）。
     *
     * ⚠️ 这里用 `glTexSubImage2D` 到临时纹理仍然是零拷贝上传（一次 `ByteBuffer.wrap`），
     *    真正的缩放交给 GPU 的线性采样完成，比 CPU 上采样便宜得多。
     */
    internal fun uploadPendingGpRegion() {
        // v2.4.34（#8）：原子「读 + 置 null」一步完成，避免与 faceExecutor 的新写入竞争丢帧
        val region = gpRegionPending.getAndSet(null) ?: return
        // v2.0.186（P0-A）：不再有输出复用池 —— 每帧的 bytes 是 sink 新建的独立数组，
        // 上传后交由 GC 回收（原"还池"逻辑已随 gpRegionPool 一并删除）。
        if (gpFboTexId != 0 && region.w > 0 && region.h > 0) {
            doUploadGpRegion(region)
        }
    }

    private fun doUploadGpRegion(region: GpRegion) {
        val bytes = region.bytes
        val srcW = region.w
        val srcH = region.h
        // 需不需要缩放贴回：回读尺寸 < 目标矩形尺寸（即降采样了）就必须走 blit 拉伸。
        // ⚠️ 不能用「srcW == gpLastReadW」判断 —— 二者恒等（回读就是该尺寸），
        //    那样会永远走直通，把降采样结果只填在视口左上角。
        val dstW = (gpReadUvW * gpFboW + 0.5f).toInt()
        val dstH = (gpReadUvH * gpFboH + 0.5f).toInt()
        val needsScale = dstW > 0 && dstH > 0 &&
            (srcW != dstW || srcH != dstH)

        if (!needsScale) {
            // 1:1 直通：直接把处理结果写回 FBO 的同一区域（无人脸 ROI 缩放的旧路径）
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gpFboTexId)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, gpLastReadX0, gpLastReadY0, srcW, srcH,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, java.nio.ByteBuffer.wrap(bytes)
            )
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            return
        }

        // 缩放路径：先传到临时纹理，再拉伸贴回 FBO 的对应区域（GPU 线性上采样）
        ensureGpUploadTex(srcW, srcH)
        val uploadTex = gpUploadTexId
        if (uploadTex == 0) return
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uploadTex)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, srcW, srcH,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, java.nio.ByteBuffer.wrap(bytes)
        )

        // 用 blit shader 把 uploadTex 拉伸画进 FBO 的回读区（全覆盖时为整张 FBO）
        blitScaledIntoFbo(uploadTex)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /** 降采样结果的中转纹理（尺寸 = 回读尺寸），随回读尺寸变化重建 */
    private fun ensureGpUploadTex(w: Int, h: Int) {
        if (gpUploadTexId != 0 && gpUploadTexW == w && gpUploadTexH == h) return
        releaseGpUploadTex()
        val tex = IntArray(1)
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
        gpUploadTexId = tex[0]
        gpUploadTexW = w
        gpUploadTexH = h
    }

    private fun releaseGpUploadTex() {
        if (gpUploadTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(gpUploadTexId), 0)
        gpUploadTexId = 0; gpUploadTexW = 0; gpUploadTexH = 0
    }

    /**
     * 用 blit shader 把 [srcTex] 拉伸画进 gpFbo 的「回读区对应的全分辨率矩形」。
     *
     * 目标矩形来自 [gpReadUvX0]/[gpReadUvY0]/[gpReadUvW]/[gpReadUvH]（UV 语义）——
     * **不能**用回读像素尺寸 [gpLastReadW]/[gpLastReadH]，因为那是降采样后的尺寸，
     * 当目标矩形用只会填住视口左上角一小块。
     *
     * 顶点：铺满目标矩形的 NDC；纹理：整张 [srcTex]（0..1）。
     * 线性过滤让 GPU 做上采样，比 CPU 快且质量足够（磨皮/美白是低频效果）。
     */
    private fun blitScaledIntoFbo(srcTex: Int) {
        if (gpBlitProgram == 0) return
        val fw = gpFboW; val fh = gpFboH
        if (fw <= 0 || fh <= 0) return
        // 目标矩形：UV → 全分辨率像素 → NDC（GL 左下原点，与 UV 的 y 向上一致）
        val x0 = gpReadUvX0 * fw
        val y0 = gpReadUvY0 * fh
        val x1 = (gpReadUvX0 + gpReadUvW) * fw
        val y1 = (gpReadUvY0 + gpReadUvH) * fh
        if (x1 <= x0 || y1 <= y0) return
        val nx0 = x0 / fw * 2f - 1f
        val ny0 = y0 / fh * 2f - 1f
        val nx1 = x1 / fw * 2f - 1f
        val ny1 = y1 / fh * 2f - 1f

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, gpFboId)
        GLES20.glViewport(0, 0, fw, fh)
        GLES20.glUseProgram(gpBlitProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex)
        GLES20.glUniform1i(gpBlitTexLoc, 0)

        // 临时顶点缓冲：pos.xy + uv.xy（TRIANGLE_STRIP: bl,br,tl,tr）
        val verts = gpScaledBlitQuad
            ?: java.nio.ByteBuffer.allocateDirect(4 * 4 * 4)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
                .also { gpScaledBlitQuad = it }
        verts.clear()
        verts.put(nx0); verts.put(ny0); verts.put(0f); verts.put(0f)
        verts.put(nx1); verts.put(ny0); verts.put(1f); verts.put(0f)
        verts.put(nx0); verts.put(ny1); verts.put(0f); verts.put(1f)
        verts.put(nx1); verts.put(ny1); verts.put(1f); verts.put(1f)
        verts.position(0)

        GLES20.glVertexAttribPointer(gpBlitPosLoc, 2, GLES20.GL_FLOAT, false, 16, verts)
        GLES20.glEnableVertexAttribArray(gpBlitPosLoc)
        // v2.0.186（P2-E1）：用缓存的 location，不再每帧 glGetAttribLocation
        val texAttr = gpBlitTexAttrLoc
        if (texAttr >= 0) {
            verts.position(2)
            GLES20.glVertexAttribPointer(texAttr, 2, GLES20.GL_FLOAT, false, 16, verts)
            GLES20.glEnableVertexAttribArray(texAttr)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(gpBlitPosLoc)
        if (texAttr >= 0) GLES20.glDisableVertexAttribArray(texAttr)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    // ===== v2.0.182：GPUPixel 全覆盖回读区计算 =====

    /**
     * 计算 GPUPixel「全覆盖」模式下的回读区（窗口坐标），返回 `[x0, y0, w, h]`。
     *
     * 语义：**2D 覆盖全视频范围、3D/VR 覆盖全屏幕** —— 即整个 [w]×[h] 视口，
     * 再按 [gpReadDownscale] 降采样回读。不做任何按人脸 ROI 的收窄，
     * 因此屏幕上不会出现「只有中间一块被美颜」的矩形分界。
     *
     * 为什么必须 16 像素对齐：`glReadPixels` / `glTexSubImage2D` 在部分驱动上
     * 对非对齐矩形会走慢路径（逐行拷贝），对齐后更接近整块 DMA。
     * 同时 16 对齐也让降采样后的边界是整数，避免上采样贴回时边缘出现半像素错位。
     */
    /**
     * v2.0.187 阶段2：**检测专用**回读区 —— 固定 1/2 尺寸。
     *
     * 阶段 2 起回读不再服务于「处理输入」（那走 texture 零拷贝），只给人脸检测用
     * （Mars-Face 需要 CPU 像素）。1/2 尺寸下 1080p 每帧 2.07 MB（原 8.29 MB，↓75%），
     * 且 Mars-Face 在 960×540 上检测稳定。
     *
     * 与 [computeGpCoverRegion] 的区别：后者按自适应档位决定清晰度；这里固定 1/2，
     * **不受自适应影响** —— 检测精度不应随性能档位波动。
     */
    internal fun computeGpDetectRegion(w: Int, h: Int): IntArray {
        val d = 2
        var rw = (w / d).coerceAtLeast(gpReadMinSide).coerceAtMost(w)
        var rh = (h / d).coerceAtLeast(gpReadMinSide).coerceAtMost(h)
        rw = ((rw / 16) * 16).coerceIn(16, w)
        rh = ((rh / 16) * 16).coerceIn(16, h)
        return intArrayOf(0, 0, rw, rh)
    }

    internal fun computeGpCoverRegion(w: Int, h: Int): IntArray {
        // VR / 3D 投影下屏幕内容更「高频」（球面网格线、几何边缘），降采样过猛会看出软化，
        // 故平面模式用 gpReadDownscale，非平面（VR）模式降一档（更保守）。
        //
        // v2.0.185：档位改为**自适应** —— 以实测处理耗时为准，在 [1, gpReadDownscaleMax]
        // 之间调节（见 updateGpAdaptiveDownscale）。首帧尚未有实测值时用初始值 gpReadDownscale。
        val base = if (gpDownscaleNow > 0) gpDownscaleNow
                   else gpReadDownscale.coerceAtLeast(1)
        val d = if (isPlanarProjection()) base else base.coerceAtMost(2)
        // 降采样后的目标尺寸（至少 gpReadMinSide，保证人脸检测可用）
        var rw = (w / d).coerceAtLeast(gpReadMinSide).coerceAtMost(w)
        var rh = (h / d).coerceAtLeast(gpReadMinSide).coerceAtMost(h)
        // 16 像素对齐（向下取整，再夹回合法范围）
        rw = ((rw / 16) * 16).coerceIn(16, w)
        rh = ((rh / 16) * 16).coerceIn(16, h)
        // 覆盖整屏 → 起点为 0；若因对齐导致尺寸不足，补到能覆盖的整块
        val x0 = 0
        val y0 = 0
        return intArrayOf(x0, y0, rw, rh)
    }

    /**
     * 把「降采样回读区」的坐标换算成**全分辨率视口 UV**。
     *
     * 全覆盖模式下回读区虽然是降采样的，但它在视口里覆盖的范围仍是**整个视口**
     * （即 [0,1]×[0,1] 的 UV 全域），所以 [faceCropScaleX] 必须记「1.0」而不是
     * `rw/w` —— 否则后续把 MediaPipe 的裁剪图坐标换算回视口时会整体缩水。
     *
     * ⚠️ 这与 v2.0.177 的人脸 ROI 语义**相反**，是本函数存在的唯一理由：
     *   - 人脸 ROI：回读区只覆盖视口一小块 → scale = rw/w（< 1），必须放大回全域
     *   - 全覆盖：回读区覆盖整个视口（只是分辨率低）→ scale = 1，坐标无需换算
     */
    internal fun gpCoverScale(): Float = 1.0f

    // ===== v2.0.185：自适应降采样 =====

    /**
     * 根据实测处理耗时 [elapsedNanos] 调整降采样档位，把 T_proc 压向帧时长。
     *
     * 背景：GL 线程在「后台仍在处理上一帧」时会跳过回读（见 [maybeScheduleFaceSampling]），
     * 于是画面更新率被 T_proc 限死。保底帧机制已消除「美颜/原始交替」的闪烁，
     * 但 T_proc 过大仍会让画面显得卡顿、且有明显延迟感 —— 故这里**主动加码降采样**。
     *
     * 策略（指数滑动平均 + 多重收敛保护，避免档位横跳导致画面清晰度抖动）：
     *  - **预热跳过**：前 [gpAdaptiveWarmup] 次样本直接丢弃。首次处理包含 Mars-Face 模型
     *    加载、GPUPixel 管线首建、shader 编译等一次性开销（实测可达 **1.3 s**，是稳态的
     *    上百倍），若计入平均会让档位瞬间冲到上限、随后又逐级回落 —— 启动时画面先糊再变清。
     *  - 平滑：`avg = avg·0.7 + sample·0.3`
     *  - 升档：`avg > 1.4×帧预算` → +1；降档：`avg < 0.5×帧预算` → −1
     *  - **连续 2 次同向才换档**：单次抖动不足以改变档位，消除「2→3→4→3→2→1」这类连跳。
     *  - 帧预算取 30fps（33 ms）而非 60fps：GPUPixel 是异步管线，允许它慢于渲染帧率，
     *    只要画面更新率 ≥ ~30fps 观感就已流畅，不必为此把分辨率压到最低。
     */
    internal fun updateGpAdaptiveDownscale(elapsedNanos: Long) {
        if (elapsedNanos <= 0) return
        // v2.0.185：预热期样本直接丢弃（含模型加载 / 管线首建 / shader 编译的一次性开销）
        if (gpAdaptiveSamples < gpAdaptiveWarmup) {
            gpAdaptiveSamples++
            return
        }
        gpProcNanosAvg = if (gpProcNanosAvg == 0L) elapsedNanos
                         else (gpProcNanosAvg * 7 + elapsedNanos * 3) / 10

        val frameBudget = 33_000_000L          // 33 ms ≈ 30fps
        val cur = if (gpDownscaleNow > 0) gpDownscaleNow else gpReadDownscale
        val dir = when {
            gpProcNanosAvg > frameBudget * 14 / 10 -> +1
            gpProcNanosAvg < frameBudget * 5 / 10  -> -1
            else -> 0
        }
        if (dir == 0) {
            gpAdaptiveDirStreak = 0
            return
        }
        // 同向累积；方向改变则重新计数（滞回，避免档位来回横跳）
        gpAdaptiveDirStreak = if (dir == gpAdaptiveLastDir) gpAdaptiveDirStreak + 1 else 1
        gpAdaptiveLastDir = dir
        if (gpAdaptiveDirStreak < 2) return

        val next = (cur + dir).coerceIn(1, gpReadDownscaleMax)
        gpAdaptiveDirStreak = 0
        if (next != cur) {
            Log.i(TAG, "GPUPixel adaptive downscale: $cur -> $next " +
                "(avg ${gpProcNanosAvg / 1_000_000} ms, budget ${frameBudget / 1_000_000} ms)")
            gpDownscaleNow = next
        }
    }

    // ===== v2.0.177：GPUPixel 人脸 ROI 计算 =====

    /**
     * 计算 GPUPixel 本帧的回读 ROI（窗口坐标），返回 `[x0, y0, w, h]`。
     *
     * 输入是**视口 UV 空间**的人脸参数（[faceCenterXUniform] / [faceCenterYUniform] /
     * [eyeDistanceUniform]），与 shader 里妆容用的是同一套坐标，因此不需要额外换算。
     *
     * 四道防抖处理（缺一不可，否则 ROI 边缘会出现会呼吸的矩形）：
     *  1. **尺寸量化档位** —— ROI 边长吸附到 [gpRoiLadder]，避免尺寸连续变化；
     *  2. **中心低通跟随** —— 只取 25% 的新位置，大幅移动时才明显位移；
     *  3. **向外对齐 16 像素** —— GPU 回读/贴回对齐更友好，也减少档位切换频率；
     *  4. **检测失败保持** —— [faceDetectedUniform] == 0 时沿用上一帧 ROI（[gpRoiPx]），
     *     最多保持 [gpRoiHoldFrames] 帧，避免检测抖一下 ROI 就跳。
     *
     * ⚠️ 绝不改变「每帧处理」的节奏（interval 恒为 1）：间隔 > 1 帧时 ROI 内会出现
     *    「美颜帧 / 原始帧」交替的肉眼可见闪烁 —— 这是 v2.0.173 已验证过的教训。
     *
     * ⚠️ VR 分屏：调用方传进来的 w 已是**单眼视口宽度**（`displayWidth / 2`），
     *    左右眼各自渲染时 faceCenterXUniform 也在各自视口 UV 空间内，故每只眼独立成 ROI。
     */
    internal fun computeGpRoi(w: Int, h: Int): IntArray {
        val detected = faceDetectedUniform == 1
        if (!detected) {
            // 检测失败：沿用上一帧 ROI（若还没有过，退回一个居中的保守尺寸）
            gpRoiHoldCounter++
            val held = gpRoiPx
            if (held != null && gpRoiHoldCounter <= gpRoiHoldFrames) {
                return clampGpRoi(held[0], held[1], held[2], held[3], w, h)
            }
            val side = minOf(640, minOf(w, h)).coerceAtLeast(16)
            return clampGpRoi((w - side) / 2, (h - side) / 2, side, side, w, h)
        }
        gpRoiHoldCounter = 0

        // --- 1) 由人脸尺度推 ROI 边长 ---
        // fUnit = 视口 UV 的眼距尺度；人脸整体宽约 2.2*fUnit、高约 3.0*fUnit，
        // 再乘 gpRoiMargin 留出瘦脸/大眼形变的溢出余量。
        val fUnit = eyeDistanceUniform.coerceIn(0.02f, 0.25f)
        val wantSidePx = gpRoiMargin * fUnit * w

        // --- 2) 量化到固定档位（取 >= wantSide 的最小档位）---
        var side = gpRoiLadder.last()
        for (v in gpRoiLadder) {
            if (wantSidePx <= v) { side = v; break }
        }
        // 档位上限也受视口约束：不能超过视口短边（ROI 是正方形，按短边夹）
        val maxSide = minOf(w, h)
        side = side.coerceAtMost(maxSide).coerceAtLeast(16)

        // --- 3) 中心低通跟随 ---
        // faceCenterYUniform 是 shader 的 UV（y 向上），而窗口坐标 y 向上也是从下往上，
        // 二者方向一致；但读回的像素行序是自下而上、贴回按行填，故贴回 y 直接用同一套即可
        // （既有实现就是这么用的，保持一致，避免引入新的翻转错误）。
        val targetCx = faceCenterXUniform.coerceIn(0f, 1f)
        val targetCy = faceCenterYUniform.coerceIn(0f, 1f)
        if (gpRoiSide <= 0f) {
            // 首次：直接吸附，不做低通（否则开头几帧 ROI 会从中心慢慢爬过去）
            gpRoiCx = targetCx
            gpRoiCy = targetCy
        } else {
            gpRoiCx += (targetCx - gpRoiCx) * 0.25f
            gpRoiCy += (targetCy - gpRoiCy) * 0.25f
        }
        gpRoiSide = side.toFloat()

        // 窗口坐标：ROI 中心像素 = UV * 视口尺寸；左上角 = 中心 - 半边长
        val cxPx = gpRoiCx * w
        val cyPx = gpRoiCy * h
        val rx0 = (cxPx - side * 0.5f).toInt()
        val ry0 = (cyPx - side * 0.5f).toInt()

        // --- 4) 向外对齐 16 像素 + 夹进视口 ---
        val ax0 = (Math.floor(rx0 / 16.0) * 16).toInt()
        val ay0 = (Math.floor(ry0 / 16.0) * 16).toInt()
        val out = clampGpRoi(ax0, ay0, side, side, w, h)
        gpRoiPx = out
        return out
    }

    /** 把 ROI 夹进视口并保证尺寸不超界（返回 `[x0, y0, w, h]`） */
    private fun clampGpRoi(x0: Int, y0: Int, w0: Int, h0: Int, w: Int, h: Int): IntArray {
        val rw = w0.coerceIn(16, w)
        val rh = h0.coerceIn(16, h)
        val rx = x0.coerceIn(0, (w - rw).coerceAtLeast(0))
        val ry = y0.coerceIn(0, (h - rh).coerceAtLeast(0))
        return intArrayOf(rx, ry, rw, rh)
    }

    private val facePipeline = VRGLFacePipeline(this)

    internal fun maybeScheduleFaceSampling() = facePipeline.maybeScheduleFaceSampling()

    private fun submitSafely(
        executor: java.util.concurrent.ExecutorService,
        tag: String,
        rollback: () -> Unit,
        task: () -> Unit,
    ): Boolean = facePipeline.submitSafely(executor, tag, rollback, task)
}
