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
import com.example.vr.huawei.HuaweiVrNative
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VRGLRenderer(private val context: Context) : GLSurfaceView.Renderer {

    companion object {
        const val TAG = "VRGLRenderer"

        /** 面积 ≥ 此值（≈ 2048×2048）时改用分块上传，单张 ≥ 16 MB */
        private const val CHUNK_UPLOAD_AREA_THRESHOLD = 2048L * 2048L
    }

    // Volatile settings accessible from Compose UI
    @Volatile var projectionMode = ProjectionMode.STANDARD
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

    // ======================= 华为 VR Glass（OpenXR）=======================
    /**
     * v2.0.175：华为 OpenXR 渲染模式。
     *
     * 开启后 onDrawFrame 走**双眼 swapchain 直渲**通路：
     *   native acquire → 本渲染器把第 i 眼画面画进 eye[i] 的 FBO → native release + endFrame
     *
     * 与 [isSplitScreenVR] 的区别：
     * - 分屏 VR：自己把屏幕切成左右两半，用估算的 IPD 偏移，**没有真实头姿**
     * - 华为 VR：每眼一块独立 swapchain，投影矩阵/FOV/头姿全部来自 OpenXR Runtime
     *
     * ⚠️ 开启时必须由 `HuaweiVrActivity` 驱动帧循环（native 侧 external 模式）。
     */
    @Volatile var huaweiVrMode = false

    /**
     * 上一帧从 native 取回的双眼渲染参数（长度 40）：
     * 眼 0 占 `[0..19]`，眼 1 占 `[20..39]`；
     * 每眼前 16 = 视图矩阵（列主序），后 4 = FOV `{left, right, up, down}`（弧度）。
     *
     * 由 `HuaweiVrActivity` 在每帧渲染前通过 [updateHuaweiEyeTargets] 写入。
     */
    @Volatile private var huaweiEyeTargets: FloatArray? = null

    /** 每眼 swapchain 尺寸（由 [updateHuaweiEyeSize] 写入，用于 aspect 计算） */
    @Volatile private var huaweiEyeWidth = 0
    @Volatile private var huaweiEyeHeight = 0

    /** 由 HuaweiVrActivity 每帧写入 OpenXR 双眼参数；传 null 表示本帧无新数据（保留上一帧） */
    fun updateHuaweiEyeTargets(targets: FloatArray?) {
        if (targets != null) huaweiEyeTargets = targets
    }

    /** 由 HuaweiVrActivity 告知每眼 swapchain 尺寸 */
    fun updateHuaweiEyeSize(width: Int, height: Int) {
        huaweiEyeWidth = width
        huaweiEyeHeight = height
    }

    /**
     * 华为 VR 模式下的单帧绘制：逐眼绑 FBO → 用 OpenXR 矩阵画 → 解绑。
     *
     * 由 `HuaweiVrActivity` 在 native `acquireEyeTargets` 成功后调用；
     * 返回 true 表示至少画了一只眼（调用方随后应调 `HuaweiVrNative.submitFrame()`）。
     */
    fun drawHuaweiVrFrame(): Boolean {
        val targets = huaweiEyeTargets ?: return false
        if (targets.size < 40) return false

        // ⚠️ 必须先在 GL 线程消费 SurfaceTexture 的新帧，否则画的是上一次的内容
        //    （内置路径在 onDrawFrame 里做，华为路径由本函数自己负责）
        synchronized(this) {
            if (isVideoFrameAvailable) {
                videoSurfaceTexture?.updateTexImage()
                isVideoFrameAvailable = false
            }
        }

        var drewAny = false
        for (eye in 0 until 2) {
            val fbo = HuaweiVrNative.bindEyeFramebuffer(eye)
            if (fbo == 0) {
                // 该眼未 acquire 到 image → 跳过（保留上一帧，不要清屏）
                continue
            }
            try {
                val w = if (huaweiEyeWidth > 0) huaweiEyeWidth else displayWidth
                val h = if (huaweiEyeHeight > 0) huaweiEyeHeight else displayHeight
                GLES20.glViewport(0, 0, w, h)
                drawEyeWithOpenXrMatrices(eye, w, h, targets)
                drewAny = true
            } finally {
                HuaweiVrNative.unbindEyeFramebuffer()
            }
        }
        return drewAny
    }

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
    private var mediaPipeManager: MediaPipeFaceManager? = null

    // Real-time Face Detection parameters updated smoothly via background thread
    @Volatile var faceDetectedUniform = 0
    @Volatile var faceCenterXUniform = 0.5f
    @Volatile var faceCenterYUniform = 0.45f
    @Volatile var eyeDistanceUniform = 0.14f
    // Precise MediaPipe 468-point features (normalized UV space)
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

    // Face detection sampling resolution. The GL thread reads a larger center
    // region (512) so faces off-center are still captured, then it is downscaled
    // to 256x256 for the MediaPipe landmarker.
    private val faceSampleSize = 256
    private val faceSampleArea = 256 * 256
    private val faceReadSize = 512

    // ===== v2.0.156：美颜链路的坐标与开销修复 =====

    /** 采样间隔（帧）：每 N 帧回读一次屏幕内容做人脸检测 */
    private val faceSampleInterval = 8

    /** v2.0.173：人脸检测连续失败计数 —— 连续 3 次失败才把 faceDetectedUniform 置 0（防妆容闪烁） */
    private var faceMissStreak = 0

    /**
     * 采样窗口相对**单个视口**的缩放比例（`rw/vpW`、`rh/vpH`）。
     *
     * 用途：把 MediaPipe 在裁剪图上算出的归一化坐标换算回视口 UV —— 见 [mapCropXToViewport]。
     * 采样区只占视口中央一小块，不做这层换算就会「脸越靠边、妆容越跑偏」。
     */
    @Volatile
    private var faceCropScaleX = 1f

    @Volatile
    private var faceCropScaleY = 1f

    /** GL 线程私有的回读缓冲（复用，避免每 8 帧新建 1MB DirectByteBuffer） */
    private var faceReadBuffer: java.nio.ByteBuffer? = null

    /** 待检测帧的数组池：GL 线程生产、faceExecutor 消费，用完归还（避免每帧 1MB 垃圾） */
    private var faceFramePool: ByteArray? = null

    private val faceExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** v2.0.156：MediaPipe 实例改为后台创建（原先在 GL 线程同步创建，会造成首帧明显卡顿） */
    private val faceInitExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val isDetectingFace = java.util.concurrent.atomic.AtomicBoolean(false)
    private var faceFrameCounter = 0

    // v84：检测线程缓冲复用（避免每帧创建 Bitmap/数组）
    private var faceArgBuffer: IntArray? = null
    private var faceBmp: Bitmap? = null
    private var faceScaledBmp: Bitmap? = null

    // Frame transfer between the GL thread and the face-detection executor.
    // The GL thread publishes a freshly allocated frame, and the executor consumes it,
    // so there is no shared mutable array being written and read concurrently.
    private val faceFrameLock = Any()
    private var pendingFaceFrame: ByteArray? = null
    private var pendingFaceFrameW = 0
    private var pendingFaceFrameH = 0

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
    private var sphere360VertexCount = 0

    private var sphere180Positions: FloatBuffer? = null
    private var sphere180TexCoords: FloatBuffer? = null
    private var sphere180VertexCount = 0

    // 盒子模式（Box Mode）：六面体细分网格 + 逆向射线 UV
    private var boxPositions: FloatBuffer? = null
    private var boxTexCoords: FloatBuffer? = null
    private var boxVertexCount = 0

    // GL SL shaders variables
    private class GLProgram(
        val programId: Int,
        val hMVPMatrix: Int,
        val hPosition: Int,
        val hTextureCoord: Int,
        val hIsVideo: Int,
        val hSamplerImage: Int,
        val hSamplerVideo: Int,
        val hProjectionMode: Int,
        val hStereoMode: Int,
        val hLeftEye: Int,
        val hBeautyStrength: Int,
        val hTextureDetail: Int,
        val hBrightness: Int,
        val hContrast: Int,
        val hTexelSize: Int,
        val hIsMirrored: Int,
        val hWarpMode: Int,
        val hCurvature: Int,
        val hWhitening: Int,
        val hFaceSlimming: Int,
        val hBigEyes: Int,
        val hDarkCircles: Int,
        val hNoseSlimming: Int,
        val hMouth: Int,
        val hTeethWhitening: Int,
        val hLipstick: Int,
        val hBlush: Int,
        val hEyebrows: Int,
        val hLongLegs: Int,
        val hSmallHead: Int,
        val hFaceDetected: Int,
        val hFaceCenter: Int,
        val hEyeDistance: Int,
        val hHasDetailed: Int,
        val hEyeLeft: Int,
        val hEyeRight: Int,
        val hMouthPos: Int,
        val hMouthHalfW: Int,
        val hMouthHalfH: Int,
        val hMouthAngle: Int,
        val hChin: Int,
        val hWarpDualCenter: Int,
        val hLutTexture: Int,
        val hLutMix: Int
    )

    private var videoProgram: GLProgram? = null
    private var imageProgram: GLProgram? = null
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
    // 三个 pass：
    //   down  : 主渲染结果(全分辨率纹理) → 降采样 1/2
    //   blur  : 半分辨率上做 8 邻域保边均值（频域分离的低频层）
    //   blend : 全分辨率原图 + 半分辨率低频，按皮肤判定与 uBeautyStrength 混合上屏
    //
    // 收益：磨皮 pass 的片元数降到 1/4（该 pass 开销 ↓75%）。
    private var pDownProgram = 0
    private var pBlurProgram = 0
    private var pBlendProgram = 0
    private var downTexId = 0        // 主渲染结果的**全分辨率**拷贝
    private var downFboId = 0
    private var halfLowTexId = 0     // 半分辨率低频层
    private var halfLowFboId = 0
    private var halfTexId = 0        // 半分辨率原图（供 blur pass 采样）
    private var halfFboId = 0
    private var halfW = 0
    private var halfH = 0
    private var halfFboW = 0
    private var halfFboH = 0
    private var downFboW = 0
    private var downFboH = 0
    private val halfQuad: java.nio.FloatBuffer = java.nio.ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f
            ))
            position(0)
        }
    /** 半分辨率 pass 是否为本次 onDrawFrame 铺好了数据（决定上屏走合成还是直绘） */
    private var halfPassReady = false

    // ===== 美颜对比模式（v102：GPUPixel 已移除，纯 shader 美颜）=====
    /** v2.0.160：美颜总开关（由原「对比原图」改造而来；false = 直通原图）。v2.0.172：默认改为关 */
    @Volatile var beautyMasterEnabled = false
    /** v2.0.160：美颜引擎。0 = GLSL（内置），1 = GPUPixel（独立 Mars-Face 检测 + 人脸区域处理） */
    @Volatile var beautyEngineType = BEAUTY_ENGINE_GLSL
    // ---- GPUPixel 引擎参数（与 GLSL 参数独立，prefs 前缀 beauty_gp_*）----
    @Volatile var beautyGpSmooth = 0.7f
    @Volatile var beautyGpWhite = 0.4f
    @Volatile var beautyGpSharpen = 0.3f
    @Volatile var beautyGpSlim = 0.4f
    @Volatile var beautyGpEyeZoom = 0.3f
    /**
     * v2.0.160（P3）：GPUPixel 是否在 VR / 全景（非平面投影）下也生效。
     * v2.0.184：恢复为**真开关**且**默认开** —— v2.0.182 曾把它从 [isGpuPixelActive] 里拿掉
     * （当时 VR 被改成无条件生效），使它退化成 UI 占位；现重新纳入激活条件。
     */
    @Volatile var gpuPixelVrFaceBeauty = true
    // GPUPixel 处理结果（faceExecutor 产出 → GL 线程贴回 FBO）
    @Volatile private var gpRegionPending: ByteArray? = null
    // v2.0.183：后台线程写入、GL 线程读取 —— 加 @Volatile 保证可见性（配合 gpRegionPending 的 volatile 写）
    @Volatile private var gpRegionW = 0
    @Volatile private var gpRegionH = 0
    /** v2.0.182 性能：GPUPixel 输出拷贝复用池（单块，avoid 每帧分配） */
    private var gpRegionPool: ByteArray? = null
    private val gpRegionPoolLock = Any()
    private var lastSampleX0 = 0
    private var lastSampleY0 = 0

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
    private val gpReadDownscale = 2

    /** 降采样后回读区的最小边（太小则人脸检测失效，宁可放大） */
    private val gpReadMinSide = 128

    /** 本帧是否为「全覆盖」模式（2D 全视频 或 VR 全屏）；false = 旧的人脸 ROI 模式 */
    private var gpFullCoverageThisFrame = false

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
    private var gpReadUvX0 = 0f
    private var gpReadUvY0 = 0f
    private var gpReadUvW = 1f
    private var gpReadUvH = 1f
    /** 上一帧实际生效的回读区（像素，窗口坐标）—— 贴回时必须与回读一致 */
    private var gpLastReadX0 = 0
    private var gpLastReadY0 = 0
    private var gpLastReadW = 0
    private var gpLastReadH = 0

    // v2.0.182：降采样结果的中转纹理（上传 → GPU 上采样拉伸贴回 FBO）
    private var gpUploadTexId = 0
    private var gpUploadTexW = 0
    private var gpUploadTexH = 0
    /** 带缩放 blit 的临时顶点缓冲（pos.xy + uv.xy × 4 顶点） */
    private var gpScaledBlitQuad: java.nio.FloatBuffer? = null

    // GPUPixel 模式的离屏渲染目标（画到 FBO → 区域处理贴回 → blit 上屏）
    private var gpFboId = 0
    private var gpFboTexId = 0
    private var gpFboW = 0
    private var gpFboH = 0

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
     * 本项目 EGL 上下文是 ES3（`VRGLSurfaceView` / `HuaweiVrActivity` 均
     * `setEGLContextClientVersion(3)`），故 `glBlitFramebuffer` 可用。
     */
    private var gpHalfFboId = 0
    private var gpHalfFboTexId = 0
    private var gpHalfFboW = 0
    private var gpHalfFboH = 0
    private var gpBlitProgram = 0
    private var gpBlitPosLoc = 0
    private var gpBlitTexLoc = 0
    private var gpBlitQuad: java.nio.FloatBuffer? = null

    // ===== v104 LUT 视频滤镜 =====
    @Volatile var lutMix = 0f                  // 0=关闭 ~ 1=完全应用（VRPlayerScreen 控制）
    @Volatile var lutName = ""                 // 当前 LUT 显示名（仅记录，UI 用）
    private var lutTextureId = -1              // LUT 纹理（GL_TEXTURE_2D, 512x512 RGBA）
    @Volatile private var pendingLutRgba: ByteArray? = null // 待上传（UI 线程写入，GL 线程消费）

    // Viewport and matrices
    private var displayWidth = 1080
    private var displayHeight = 1920
    private var lastScaleX = 0f
    private var lastScaleY = 0f
    private var lastSrcW = 0

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Shaders source code
    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 aPosition;
        attribute vec2 aTextureCoord;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTextureCoord = aTextureCoord;
        }
    """.trimIndent()

    // Minimal fallback shader used when the main shader fails to compile on a
    // strict device driver. Keeps photos/panoramas visible (plain textured quad).
    private val fallbackVertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTextureCoord;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = aPosition;
            vTextureCoord = aTextureCoord;
        }
    """.trimIndent()

    private val fallbackFragmentShaderCode = """
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform sampler2D uSampler;
        void main() {
            gl_FragColor = texture2D(uSampler, vTextureCoord);
        }
    """.trimIndent()

    // Latest shader compile error, surfaced to the UI so black-screen causes are visible
    @Volatile var shaderError: String? = null

    // Uniform setters that skip locations optimized out by strict device drivers
    // (Adreno frequently removes unused uniforms, leaving location = -1)
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
    private val fragmentShaderCode = """
        #ifdef VIDEO_OES
        #extension GL_OES_EGL_image_external : enable
        #endif
        precision highp float;
        
        varying vec2 vTextureCoord;
        
        uniform int uIsVideo;
        uniform sampler2D uSamplerImage;
        #ifdef VIDEO_OES
        uniform samplerExternalOES uSamplerVideo;
        #else
        uniform sampler2D uSamplerVideo;
        #endif
        
        // Settings
        uniform int uProjectionMode; // 0 = Standard, 1 = FishEye, 2 = 360 Sphere, 3 = 180 Dome
        uniform int uStereoMode;     // 0 = Mono, 1 = SBS, 2 = TAB
        uniform int uLeftEye;        // 1 = Left, 0 = Right
        uniform int uIsMirrored;
        uniform int uWarpMode;
        uniform float uCurvature;
        
        uniform float uBeautyStrength; 
        uniform float uBrightness;
        uniform float uContrast;
        uniform vec2 uTexelSize;
        
        // Detailed cosmetics uniforms
        // v2.0.159：磨皮「高频保留度」—— 频域分离后的纹理叠回系数（<1 更平滑，>1 相当于 USM 锐化）
        uniform float uTextureDetail;
        uniform float uWhitening;
        uniform float uFaceSlimming;
        uniform float uBigEyes;
        uniform float uDarkCircles;
        uniform float uNoseSlimming;
        uniform float uMouth;
        uniform float uTeethWhitening;
        uniform float uLipstick;
        uniform float uBlush;
        uniform float uEyebrows;
        uniform float uLongLegs;
        uniform float uSmallHead;
        
        // Face recognition tracking uniforms
        uniform int uFaceDetected;
        uniform vec2 uFaceCenter;
        uniform float uEyeDistance;
        
        // Precise MediaPipe 468-point features (normalized UV space)
        uniform int uHasDetailed;
        uniform vec2 uEyeLeft;
        uniform vec2 uEyeRight;
        uniform vec2 uMouthPos;
        // v2.0.159：嘴部形状参数（由 MediaPipe 唇部关键点算出）—— 供椭圆 mask 使用，
        // 取代原先「以嘴中心为圆心的正圆」。值为 0 表示未取到（shader 内回退到 fUnit 比例）。
        uniform float uMouthHalfW;
        uniform float uMouthHalfH;
        uniform float uMouthAngle;
        uniform vec2 uChin;
        uniform int uWarpDualCenter;
        
        // v104 LUT 视频滤镜（512x512 RGBA 打包：8x8 网格，每格 64x64）
        uniform sampler2D uLutTexture;
        uniform float uLutMix;   // 0 = 关闭，1 = 完全应用
        
        // 3D LUT 查找：层 z 位于 row=z/8, col=z%8，格内 x=r, y=g；
        // 像素中心偏移避免格间泄漏，GL_LINEAR 提供格内双线性 + 层间 mix
        vec3 applyLut(vec3 color) {
            const float SIZE = 64.0;
            const float GRID = 8.0;
            float scale = (SIZE - 1.0) / SIZE;
            float offset = 0.5 / SIZE;
            vec3 c = clamp(color, 0.0, 1.0) * scale + offset;
            float slice = floor(c.b * (SIZE - 1.0));  // v104 修复：用 SIZE-1 (63) 而非 GRID (8)，覆盖 64 个 slice
            float frac = c.b * (SIZE - 1.0) - slice;
            float row = floor(slice / GRID);
            float col = mod(slice, GRID);
            vec2 uv = vec2((col + c.r) / GRID, (row + c.g) / GRID);
            float slice2 = min(slice + 1.0, SIZE - 1.0);
            float row2 = floor(slice2 / GRID);
            float col2 = mod(slice2, GRID);
            vec2 uv2 = vec2((col2 + c.r) / GRID, (row2 + c.g) / GRID);
            vec3 lut0 = texture2D(uLutTexture, uv).rgb;
            vec3 lut1 = texture2D(uLutTexture, uv2).rgb;
            return mix(lut0, lut1, frac);
        }
        
        // Simple fast skin tone thresholding in RGB
        bool isSkin(vec3 rgb) {
            float r = rgb.r;
            float g = rgb.g;
            float b = rgb.b;
            return (r > 0.35 && g > 0.15 && b > 0.08 && r > g && r > b && (r - g) > 0.05);
        }

        // v2.0.159：soft-light 混合 —— 用于腮红 / 口红等妆容。
        // 相比原来的「纯加法」，它能保留底层皮肤的明暗层次，不会把脸颊糊成一片纯色，
        // 也不会把高光区顶到溢出。
        vec3 softLight(vec3 base, vec3 blend) {
            vec3 lo = 2.0 * base * blend + base * base * (1.0 - 2.0 * blend);
            vec3 hi = sqrt(max(base, vec3(0.0))) * (2.0 * blend - 1.0) + 2.0 * base * (1.0 - blend);
            return clamp(mix(lo, hi, step(vec3(0.5), blend)), 0.0, 1.0);
        }

        // v2.0.159：带倾角的椭圆软遮罩，返回 0~1 权重（1 = 中心，0 = 边界外）。
        // 取代原来「以关键点为圆心的正圆」判定 —— 嘴唇是横向长条、腮红是椭圆、眉是斜长条，
        // 正圆必然「圆小涂不到嘴角、圆大溢出到下巴」。softness 控制边界羽化宽度（0.2~0.4 效果自然）。
        // 参数名刻意避开 center / scale —— main() 里有同名局部变量，免得遮蔽后看代码犯迷糊
        float ellipseMask(vec2 p, vec2 ctr, vec2 rad, float angle, float softness) {
            float ca = cos(angle);
            float sa = sin(angle);
            vec2 d = p - ctr;
            vec2 q = vec2(ca * d.x - sa * d.y, sa * d.x + ca * d.y);
            float rr = length(q / max(rad, vec2(1e-4)));
            return 1.0 - smoothstep(1.0 - softness, 1.0, rr);
        }

        void main() {
            vec2 tc = vTextureCoord;
            
            // 1. Calculate active sub-frame center and scale for 3D stereoscopic offset mappings
            vec2 center = vec2(0.5, 0.5);
            vec2 scale = vec2(1.0, 1.0);
            
            if (uStereoMode == 1) { // Side-by-Side (SBS)
                scale.x = 0.5;
                if (uLeftEye == 1) {
                    center.x = 0.25;
                } else {
                    center.x = 0.75;
                }
            } else if (uStereoMode == 2) { // Top-Bottom (TAB)
                scale.y = 0.5;
                if (uLeftEye == 1) {
                    center.y = 0.25;
                } else {
                    center.y = 0.75;
                }
            }
            
            // Map texture coordinates to active eye segment first
            if (uStereoMode == 1) { // Side-by-Side (SBS)
                if (uLeftEye == 1) {
                    tc.x = tc.x * 0.5;
                } else {
                    tc.x = 0.5 + tc.x * 0.5;
                }
            } else if (uStereoMode == 2) { // Top-Bottom (TAB)
                if (uLeftEye == 1) {
                    tc.y = tc.y * 0.5;
                } else {
                    tc.y = 0.5 + tc.y * 0.5;
                }
            }
            
            // 双中心变形：2:1 全景（VR_360 + MONO）左右半区各自以 25%/75% 为变形中心，
            // 使左右两侧的变形/透视效果对称自然（如 360° 球面观看时正前/正后不变形）。
            if (uWarpDualCenter == 1 && uStereoMode == 0) {
                center.x = (tc.x < 0.5) ? 0.25 : 0.75;
            }
            
            // 2. Lens imaging distortion: cylindrical / spherical / curved viewing-lens
            //    warps applied in the active eye segment coordinate space. This only
            //    distorts the rendered image (the "lens" you look through) and never
            //    modifies the video source itself.
            //    优化：所有变形因子用 clamp 防止高曲率下变负导致纹理翻转/崩溃，
            //    并使用平滑二次曲线使变形过渡自然。
            float curv = clamp(uCurvature, 0.0, 1.0);
            if (uWarpMode == 1) { // Original Cylinder (等距矩形柱面)
                vec2 coord = (tc - center) / (0.5 * scale);
                float k = curv * (coord.x * coord.x);
                coord.y = coord.y * clamp(1.0 + k * 1.2, 0.1, 3.0);
                coord.x = coord.x * clamp(1.0 + k * 0.5, 0.1, 2.0);
                tc = center + coord * (0.5 * scale);
            }
            else if (uWarpMode == 2) { // Cylinder (等距圆柱)
                vec2 coord = (tc - center) / (0.5 * scale);
                float k = curv * (coord.x * coord.x);
                coord.y = coord.y * clamp(1.0 + k * 2.5, 0.1, 3.0);
                coord.x = coord.x * clamp(1.0 + k * 1.0, 0.1, 2.0);
                tc = center + coord * (0.5 * scale);
            }
            else if (uWarpMode == 3) { // Sphere (立体球面)
                vec2 coord = (tc - center) / (0.5 * scale);
                float r = length(coord);
                if (r > 0.001) {
                    // 平滑枕形：边缘按 r² 平滑拉伸，clamp 防爆
                    float rf = r * clamp(1.0 + curv * r * r * 2.0, 0.1, 2.5);
                    tc = center + (coord / r) * rf * (0.5 * scale);
                }
            }
            else if (uWarpMode == 4) { // Curve (环幕曲面)
                vec2 coord = (tc - center) / (0.5 * scale);
                float k = curv * abs(coord.x);
                coord.y = coord.y * clamp(1.0 + k * 1.5, 0.1, 2.5);
                tc = center + coord * (0.5 * scale);
            }
            else if (uWarpMode == 5) { // Anti-Sphere (反向球面/桶形)
                vec2 coord = (tc - center) / (0.5 * scale);
                float r = length(coord);
                if (r > 0.001) {
                    // 桶形畸变：边缘向内收缩，clamp 保证非负（防纹理翻转）
                    float rf = r * clamp(1.0 - curv * r * r * 1.2, 0.1, 1.5);
                    tc = center + (coord / r) * rf * (0.5 * scale);
                }
            }
            else if (uWarpMode == 6) { // Anti-Curve (反向曲面)
                vec2 coord = (tc - center) / (0.5 * scale);
                float k = curv * abs(coord.x);
                coord.y = coord.y * clamp(1.0 - k * 1.0, 0.1, 1.5);
                tc = center + coord * (0.5 * scale);
            }
            
            // Mirror horizontally correctly within each eye segment center:
            // VR_360 (2) and VR_180 (3) spheres are inherently flipped on the inside.
            if (uProjectionMode == 2 || uProjectionMode == 3) {
                if (uIsMirrored == 1) {
                    // Naturally mirrored is already mirrored, so keep it as is
                } else {
                    // Normal state: un-reverse the naturally mirrored sphere mapping
                    tc.x = center.x - (tc.x - center.x);
                }
            } else {
                if (uIsMirrored == 1) {
                    tc.x = center.x - (tc.x - center.x);
                }
            }
            
            // Fish-eye local polar radial warp coordinate simulation, centered inside active video view segment
            // 优化：全域连续处理（r 最大 ~0.707），边缘平滑过渡无突变
            if (uProjectionMode == 1) {
                vec2 d = (tc - center) / scale;
                float r = length(d);
                float rMax = 0.7071;
                if (r > 0.0001) {
                    float theta = atan(d.y, d.x);
                    // 标准穹顶压缩：r 归一化后平方压缩，全域平滑
                    float rn = clamp(r / rMax, 0.0, 1.0);
                    float rf = rn * rn * rMax;
                    // 中心区域保持线性（小 r 时不变形过多），边缘平滑压缩
                    float blend = smoothstep(0.0, 1.0, rn);
                    rf = mix(r * 0.5, rf, blend);
                    tc = center + vec2(cos(theta), sin(theta)) * rf * scale;
                }
            }
            
            // Dynamic Geometry Squeezes / Deformations for beauty properties.
            // IMPORTANT: geometry deformations (thin face / big eyes / nose / mouth /
            // long legs / small head) only apply when a face is actually tracked,
            // otherwise a flat 2D picture gets squeezed/stretched for no reason.
            if (uProjectionMode == 0 && uFaceDetected == 1) {
                // Initialize landmarks
                vec2 fCenter = vec2(0.5, 0.45);
                float fUnit = 0.14;
                if (uFaceDetected == 1) {
                    fCenter = uFaceCenter;
                    // v2.0.156：uEyeDistance 现在是**视口 UV 空间**的尺度（此前误用采样裁剪图空间，
                    // 数值偏大约 3.7 倍、总被下面的 clamp 顶到上限），故上下限整体下移。
                    fUnit = clamp(uEyeDistance, 0.02, 0.25);
                }

                // Derive facial feature anchors. When MediaPipe 468-point landmarks are
                // available, use them for pixel-accurate cosmetic placement.
                vec2 eyeL = fCenter + vec2(-0.5 * fUnit, 0.0);
                vec2 eyeR = fCenter + vec2(0.5 * fUnit, 0.0);
                vec2 mouthC = fCenter + vec2(0.0, 0.75 * fUnit);
                float chinY = fCenter.y + fUnit;
                if (uHasDetailed == 1) {
                    eyeL = uEyeLeft;
                    eyeR = uEyeRight;
                    mouthC = uMouthPos;
                    chinY = uChin.y;
                }

                float normY = (tc.y - center.y) / scale.y;
                
                // 1. Long Legs vertical stretch (bottom part of standard flat video projection)
                if (normY > 0.05) {
                    normY = normY - (normY - 0.05) * (uLongLegs * 0.08);
                    tc.y = center.y + normY * scale.y;
                }
                
                // 2. Face Slimming (centered around the chin/jawline relative to tracked face center)
                float jawY = uHasDetailed == 1 ? chinY - 0.15 * fUnit : fCenter.y + 0.5 * fUnit;
                float distYFace = abs(tc.y - jawY);
                if (distYFace < 1.1 * fUnit) {
                    float factor = (1.0 - distYFace / (1.1 * fUnit)) * uFaceSlimming * 0.08;
                    tc.x = mix(tc.x, fCenter.x, factor);
                }
                
                // Small Head (squeezing forehead region horizontally)
                float headY = uHasDetailed == 1 ? (eyeL.y + eyeR.y) * 0.5 - 0.55 * fUnit : fCenter.y - 0.7 * fUnit;
                float distYHead = abs(tc.y - headY);
                if (distYHead < 1.1 * fUnit) {
                    float factor = (1.0 - distYHead / (1.1 * fUnit)) * uSmallHead * 0.08;
                    tc.x = mix(tc.x, fCenter.x, factor);
                }
                
                // 3. Big Eyes radial expansion around tracked pupils
                float distEyeL = distance(tc, eyeL);
                if (distEyeL < 0.5 * fUnit) {
                    float f = (1.0 - distEyeL / (0.5 * fUnit)) * uBigEyes * 0.15;
                    tc = mix(tc, eyeL, -f);
                }
                float distEyeR = distance(tc, eyeR);
                if (distEyeR < 0.5 * fUnit) {
                    float f = (1.0 - distEyeR / (0.5 * fUnit)) * uBigEyes * 0.15;
                    tc = mix(tc, eyeR, -f);
                }
                
                // 4. Nose Slimming inside tracked nasal bridge
                vec2 noseCenter = uHasDetailed == 1
                    ? vec2((eyeL.x + eyeR.x) * 0.5, mix((eyeL.y + eyeR.y) * 0.5, chinY, 0.35))
                    : fCenter + vec2(0.0, 0.25 * fUnit);
                float distNoseWidth = abs(tc.x - noseCenter.x);
                float distNoseHeight = abs(tc.y - noseCenter.y);
                if (distNoseWidth < 0.4 * fUnit && distNoseHeight < 0.4 * fUnit) {
                    float f = (1.0 - distNoseWidth / (0.4 * fUnit)) * uNoseSlimming * 0.12;
                    tc.x = mix(tc.x, noseCenter.x, f);
                }
                
                // 5. Mouth resizing (squeeze slightly around mouth lip centroid)
                float distMouthArea = distance(tc, mouthC);
                if (distMouthArea < 0.45 * fUnit) {
                    float f = (1.0 - distMouthArea / (0.45 * fUnit)) * uMouth * 0.10;
                    tc = mix(tc, mouthC, f);
                }
            }
            
            // Read source frame pixel
            vec4 color;
            if (tc.x < 0.0 || tc.x > 1.0 || tc.y < 0.0 || tc.y > 1.0) {
                color = vec4(0.0, 0.0, 0.0, 1.0);
            } else {
                if (uIsVideo == 1) {
                    color = texture2D(uSamplerVideo, tc);
                } else {
                    color = texture2D(uSamplerImage, tc);
                }
            }
            
            // Realtime Skin-Smoothing Bilateral bilateral filter 3x3
            // ===== v2.0.177：皮肤判定前置，非皮肤像素**跳过 8 次邻域采样** =====
            // 旧实现：先把 8 个远邻域全采样完，最后才按 isSkin 决定混合强度
            // （皮肤 1.0 / 非皮肤 0.25）—— 而非皮肤区（背景、头发、衣物）占画面 60~80%，
            // 这些像素白付 8 次纹理采样，最后只换来一个乘 0.25 的轻微降噪（视频场景肉眼不可见）。
            // 移动 GPU 上纹理采样是 fragment 的主要瓶颈：1080p60 下 8 次/像素 ≈ 每秒 10 亿次采样，
            // 其中约 6.5 亿次是纯浪费。前置判定后，非皮肤区采样数 9 → 1。
            //
            // ⚠️ 这是一个「失败即退化」的优化：isSkin 是 RGB 阈值快速判定（见其定义处），
            //    对暖光墙 / 木地板 / 肤色家具会**误判成皮肤** → 只是退化回全采样路径，
            //    只损失收益、绝不会画错。这正是该优化风险极低的根本原因。
            // 注：GPU 以 2x2 quad 为单位执行分支，quad 内皮肤/非皮肤混合时两条分支都会跑
            //    （无收益但无害）；因 isSkin 在局部区域高度一致，quad 分歧概率低，收益基本保留。
            if (uBeautyStrength > 0.01) {
                // ===== v2.0.159：磨皮改为「频域分离」 =====
                // 把画面拆成两层：
                //   低频 = 大半径**保边**均值 → 色块与光影（痘印 / 色斑就住在这层）
                //   高频 = 原图 − 低频        → 毛孔、发丝、五官边缘
                // 只平滑低频、再按 uTextureDetail 把高频叠回去 —— 于是「磨皮强度」与「纹理保留度」
                // 解耦：既能抹平色块，又不会变成塑料脸。
                // （旧实现是单尺度双边 + 0.90 强混合：半径不足只能靠混合硬拉，观感是"糊"不是"净"。）
                if (isSkin(color.rgb)) {   // ← v2.0.177：纯标量比较，零采样成本
                vec2 stepF = vec2(uTexelSize.x * 6.0, uTexelSize.y * 6.0);

                // 8 个远邻域（±6 像素）
                vec4 n1 = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc + stepF) : texture2D(uSamplerImage, tc + stepF);
                vec4 n2 = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc - stepF) : texture2D(uSamplerImage, tc - stepF);
                vec4 n3 = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc + vec2(stepF.x, 0.0)) : texture2D(uSamplerImage, tc + vec2(stepF.x, 0.0));
                vec4 n4 = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc - vec2(stepF.x, 0.0)) : texture2D(uSamplerImage, tc - vec2(stepF.x, 0.0));
                vec4 n5 = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc + vec2(0.0, stepF.y)) : texture2D(uSamplerImage, tc + vec2(0.0, stepF.y));
                vec4 n6 = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc - vec2(0.0, stepF.y)) : texture2D(uSamplerImage, tc - vec2(0.0, stepF.y));
                vec4 n7 = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc + vec2(stepF.x, -stepF.y)) : texture2D(uSamplerImage, tc + vec2(stepF.x, -stepF.y));
                vec4 n8 = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc - vec2(stepF.x, -stepF.y)) : texture2D(uSamplerImage, tc - vec2(stepF.x, -stepF.y));

                // 保边权重：邻域色差越大权重越低。大半径下阈值放宽到 0.30 ——
                // 否则远邻域会被整片判成"边缘"，权重全 0，低频层退化成原图、磨皮等于没做。
                float deltaThres = 0.30;
                float w1 = max(0.0, 1.0 - distance(color.rgb, n1.rgb) / deltaThres);
                float w2 = max(0.0, 1.0 - distance(color.rgb, n2.rgb) / deltaThres);
                float w3 = max(0.0, 1.0 - distance(color.rgb, n3.rgb) / deltaThres);
                float w4 = max(0.0, 1.0 - distance(color.rgb, n4.rgb) / deltaThres);
                float w5 = max(0.0, 1.0 - distance(color.rgb, n5.rgb) / deltaThres);
                float w6 = max(0.0, 1.0 - distance(color.rgb, n6.rgb) / deltaThres);
                float w7 = max(0.0, 1.0 - distance(color.rgb, n7.rgb) / deltaThres);
                float w8 = max(0.0, 1.0 - distance(color.rgb, n8.rgb) / deltaThres);

                vec4 low = (color + n1 * w1 + n2 * w2 + n3 * w3 + n4 * w4
                    + n5 * w5 + n6 * w6 + n7 * w7 + n8 * w8)
                    / (1.0 + w1 + w2 + w3 + w4 + w5 + w6 + w7 + w8);

                // 高频层：纹理 + 边缘
                vec3 detail = color.rgb - low.rgb;

                // 低频 + 高频×保留度：uTextureDetail < 1 更平滑，> 1 相当于 USM 锐化（找回通透感）
                vec3 smoothed = low.rgb + detail * uTextureDetail;

                color.rgb = mix(color.rgb, smoothed, uBeautyStrength);
                } else {
                    // ===== v2.0.178 修复「美颜只显示一小块区域」 =====
                    // v2.0.177 让非皮肤像素**完全不做**磨皮（省 8 次采样）。但 isSkin 是
                    // RGB 阈值判定，实测一段真实视频（1920x1080，人物占中下 3/4）：
                    //   上 1/4 的粉/紫背景 → 通过率 0%，中下人物区 → 20~93%，总通过率仅 35%。
                    // 于是皮肤区被磨皮、背景完全没动，**看上去就是「美颜只在小块区域生效」**，
                    // 且皮肤/背景交界处出现可见的一圈分界。
                    //
                    // 这里恢复「非皮肤也轻度处理」，但**只采 2 个垂直邻域**（不是旧的 8 个）：
                    //   · 采样数 9 → 3（仅比 v2.0.177 多 2 次，远低于旧的 9 次）
                    //   · 背景获得极轻降噪 → 分界线消失，观感统一
                    // 权重固定 0.25（与 v2.0.176 的非皮肤分支一致），保持「背景不被过度处理」。
                    vec2 stepV = vec2(0.0, uTexelSize.y * 6.0);
                    vec3 vUp = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc + stepV).rgb
                                               : texture2D(uSamplerImage, tc + stepV).rgb;
                    vec3 vDn = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc - stepV).rgb
                                               : texture2D(uSamplerImage, tc - stepV).rgb;
                    vec3 vAvg = (color.rgb + vUp + vDn) / 3.0;
                    color.rgb = mix(color.rgb, vAvg, uBeautyStrength * 0.25);
                }
            }
            
            // Apply advanced fine cosmetics
            // v2.0.173：妆容/局部效果 gate 到 uFaceDetected == 1 —— 检测丢失时完全不画
            // （否则会按默认中心 (0.5,0.45) 把口红/腮红画到画面中央，产生跳变）。
            // 与 Kotlin 侧的「连续 3 次失败才置 0」防抖配合，短暂检测失败不再闪。
            if (uProjectionMode == 0 && uFaceDetected == 1) {
                // Initialize landmarks
                vec2 fCenter = vec2(0.5, 0.45);
                float fUnit = 0.14;
                if (uFaceDetected == 1) {
                    fCenter = uFaceCenter;
                    // v2.0.156：uEyeDistance 现在是**视口 UV 空间**的尺度（此前误用采样裁剪图空间，
                    // 数值偏大约 3.7 倍、总被下面的 clamp 顶到上限），故上下限整体下移。
                    fUnit = clamp(uEyeDistance, 0.02, 0.25);
                }

                // ===== v2.0.177：妆容 gate 到人脸包围盒 =====
                // 下方整段妆容（美白/黑眼圈/眉毛/口红/腮红/白牙）要跑 10+ 次 ellipseMask，
                // 每次含 cos/sin/length/smoothstep —— 这些是**纯算术**开销（无纹理采样）。
                // 但人脸通常只占画面一小部分，画面上 80%+ 的像素算完 mask 发现权重为 0 就丢弃。
                // 这里先做一次包围盒剔除：最远的腮红中心距 fCenter 约 0.72*fUnit、半径约 0.46*fUnit，
                // 再加上椭圆边界羽化，1.5*fUnit / 1.8*fUnit 的包围盒足以包住全部妆容区域且有余量。
                // ⚠️ 注意：这只是算术优化的「快路径」，盒外像素直接跳过整段妆容计算。
                bool inFaceBox =
                    abs(tc.x - fCenter.x) < 1.5 * fUnit &&
                    abs(tc.y - fCenter.y) < 1.8 * fUnit;

                if (inFaceBox) {
                // Derive facial feature anchors (MediaPipe 468-point aware)
                vec2 eyeL = fCenter + vec2(-0.5 * fUnit, 0.0);
                vec2 eyeR = fCenter + vec2(0.5 * fUnit, 0.0);
                vec2 mouthC = fCenter + vec2(0.0, 0.75 * fUnit);
                if (uHasDetailed == 1) {
                    eyeL = uEyeLeft;
                    eyeR = uEyeRight;
                    mouthC = uMouthPos;
                }

                // 1. Skin Whitening (美白)
                // v2.0.159：纯加法 → 「保高光提亮」：越接近白色的通道加得越少，
                // 避免鼻尖/额头等高光区直接溢出成白块（系数上调以补偿整体减弱）。
                if (isSkin(color.rgb)) {
                    color.rgb += vec3(uWhitening * 0.14) * (1.0 - color.rgb);
                }
                
                // 2. Dark Circles removal (黑眼圈) just below the tracked eyes
                vec2 bagL = eyeL + vec2(0.0, 0.3 * fUnit);
                vec2 bagR = eyeR + vec2(0.0, 0.3 * fUnit);
                float distBagL = distance(tc, bagL);
                float distBagR = distance(tc, bagR);
                // v2.0.159：正圆 → 横向椭圆（眼袋是横卧在眼下的月牙形），并保留「保高光提亮」
                vec2 bagRadius = vec2(0.28 * fUnit, 0.13 * fUnit);
                float bagMask = max(
                    ellipseMask(tc, bagL, bagRadius, 0.0, 0.55),
                    ellipseMask(tc, bagR, bagRadius, 0.0, 0.55)
                );
                if (bagMask > 0.001 && isSkin(color.rgb)) {
                    color.rgb += vec3(uDarkCircles * 0.15) * (1.0 - color.rgb) * bagMask;
                }
                
                // 3. Eyebrows definitions (眉毛) darkening directly above the eyes
                vec2 browL = eyeL + vec2(0.0, -0.5 * fUnit);
                vec2 browR = eyeR + vec2(0.0, -0.5 * fUnit);
                float distBrowL = distance(tc, browL);
                float distBrowR = distance(tc, browR);
                // v2.0.159：正圆 → **横向椭圆**（眉是斜长条，正圆只会涂成一团）。
                // 倾角用固定 ±0.14 rad（≈8°）近似眉毛走向。
                vec2 browRadius = vec2(0.30 * fUnit, 0.115 * fUnit);
                float browMaskL = ellipseMask(tc, browL, browRadius, -0.14, 0.45);
                if (browMaskL > 0.001) {
                    color.rgb = mix(color.rgb, color.rgb * vec3(0.52, 0.50, 0.53), browMaskL * uEyebrows * 0.70);
                }
                float browMaskR = ellipseMask(tc, browR, browRadius, 0.14, 0.45);
                if (browMaskR > 0.001) {
                    color.rgb = mix(color.rgb, color.rgb * vec3(0.52, 0.50, 0.53), browMaskR * uEyebrows * 0.70);
                }
                
                // 4. Lipstick coloring (口红) around the tracked mouth
                float distLip = distance(tc, mouthC);
                // v2.0.159：正圆 → **贴合唇形的椭圆**。宽/高/倾角来自 MediaPipe 唇部关键点
                // （uMouthHalfW 为 0 表示未取到，回退按 fUnit 估算，兼容 FaceDetector 兜底路径）。
                // 采样图 y 与视口 UV 的 y 方向相反，所以倾角取负。
                vec2 mouthRadius = (uMouthHalfW > 0.0001)
                    ? vec2(uMouthHalfW * 1.18, max(uMouthHalfH, uMouthHalfW * 0.30) * 1.60)
                    : vec2(0.34 * fUnit, 0.13 * fUnit);
                float lipMask = ellipseMask(tc, mouthC, mouthRadius, -uMouthAngle, 0.35);
                if (lipMask > 0.001) {
                    // soft-light 上色：保留唇部原有明暗与高光（不再三通道硬拉）
                    color.rgb = mix(
                        color.rgb,
                        softLight(color.rgb, vec3(0.74, 0.16, 0.26)),
                        lipMask * uLipstick * 0.70
                    );
                }
                
                // 5. Cheek Blush coloring (腮红) on the cheeks outside the eyes
                vec2 blushL = eyeL + vec2(-0.55 * fUnit, 0.55 * fUnit);
                vec2 blushR = eyeR + vec2(0.55 * fUnit, 0.55 * fUnit);
                float distBlushL = distance(tc, blushL);
                float distBlushR = distance(tc, blushR);
                // v2.0.159：正圆 → 椭圆（腮红是沿脸颊斜向下的大椭圆），并保留 soft-light 上色
                vec2 blushRadius = vec2(0.46 * fUnit, 0.30 * fUnit);
                float blushMaskL = ellipseMask(tc, blushL, blushRadius, -0.28, 0.50);
                if (blushMaskL > 0.001 && isSkin(color.rgb)) {
                    color.rgb = mix(color.rgb, softLight(color.rgb, vec3(0.93, 0.62, 0.66)), blushMaskL * uBlush * 0.75);
                }
                float blushMaskR = ellipseMask(tc, blushR, blushRadius, 0.28, 0.50);
                if (blushMaskR > 0.001 && isSkin(color.rgb)) {
                    color.rgb = mix(color.rgb, softLight(color.rgb, vec3(0.93, 0.62, 0.66)), blushMaskR * uBlush * 0.75);
                }
                
                // 6. Teeth Whitening (白牙) inside the lip cavity
                float distTeethSpace = distance(tc, mouthC);
                // v2.0.159：正圆 → 细长椭圆（牙齿在嘴腔里是横条），并跟随嘴的倾角
                vec2 teethRadius = (uMouthHalfW > 0.0001)
                    ? vec2(uMouthHalfW * 0.72, max(uMouthHalfH, uMouthHalfW * 0.30) * 0.55)
                    : vec2(0.20 * fUnit, 0.075 * fUnit);
                float teethMask = ellipseMask(tc, mouthC, teethRadius, -uMouthAngle, 0.40);
                if (teethMask > 0.001) {
                    float luma = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                    if (luma > 0.45) {
                        color.rgb = mix(color.rgb, vec3(luma + 0.12), teethMask * uTeethWhitening * 0.75);
                    }
                }
                }   // v2.0.177: end if (inFaceBox)
            }
            
            // Brightness
            color.rgb += uBrightness;
            
            // Contrast Adjustment
            color.rgb = (color.rgb - vec3(0.5)) * uContrast + vec3(0.5);
            
            // v104 LUT 视频滤镜（在调色之后、输出之前应用，可混合强度）
            if (uLutMix > 0.001) {
                color.rgb = mix(color.rgb, applyLut(color.rgb), uLutMix);
            }
            
            color.rgb = clamp(color.rgb, 0.0, 1.0);
            gl_FragColor = color;
        }
    """.trimIndent()

    private fun buildMainProgram(videoVariant: Boolean): GLProgram? {
        return try {
            val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            val fCode = if (videoVariant) "#define VIDEO_OES 1\n" + fragmentShaderCode else fragmentShaderCode
            val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fCode)
            val progId = GLES20.glCreateProgram().apply {
                GLES20.glAttachShader(this, vShader)
                GLES20.glAttachShader(this, fShader)
                GLES20.glLinkProgram(this)
                val linkStatus = IntArray(1)
                GLES20.glGetProgramiv(this, GLES20.GL_LINK_STATUS, linkStatus, 0)
                if (linkStatus[0] == 0) {
                    val log = GLES20.glGetProgramInfoLog(this)
                    throw RuntimeException("GL Program link error: $log")
                }
            }
            GLProgram(
                programId = progId,
                hMVPMatrix = GLES20.glGetUniformLocation(progId, "uMVPMatrix"),
                hPosition = GLES20.glGetAttribLocation(progId, "aPosition"),
                hTextureCoord = GLES20.glGetAttribLocation(progId, "aTextureCoord"),
                hIsVideo = GLES20.glGetUniformLocation(progId, "uIsVideo"),
                hSamplerImage = GLES20.glGetUniformLocation(progId, "uSamplerImage"),
                hSamplerVideo = GLES20.glGetUniformLocation(progId, "uSamplerVideo"),
                hProjectionMode = GLES20.glGetUniformLocation(progId, "uProjectionMode"),
                hStereoMode = GLES20.glGetUniformLocation(progId, "uStereoMode"),
                hLeftEye = GLES20.glGetUniformLocation(progId, "uLeftEye"),
                hBeautyStrength = GLES20.glGetUniformLocation(progId, "uBeautyStrength"),
                hTextureDetail = GLES20.glGetUniformLocation(progId, "uTextureDetail"),
                hBrightness = GLES20.glGetUniformLocation(progId, "uBrightness"),
                hContrast = GLES20.glGetUniformLocation(progId, "uContrast"),
                hTexelSize = GLES20.glGetUniformLocation(progId, "uTexelSize"),
                hIsMirrored = GLES20.glGetUniformLocation(progId, "uIsMirrored"),
                hWarpMode = GLES20.glGetUniformLocation(progId, "uWarpMode"),
                hCurvature = GLES20.glGetUniformLocation(progId, "uCurvature"),
                hWhitening = GLES20.glGetUniformLocation(progId, "uWhitening"),
                hFaceSlimming = GLES20.glGetUniformLocation(progId, "uFaceSlimming"),
                hBigEyes = GLES20.glGetUniformLocation(progId, "uBigEyes"),
                hDarkCircles = GLES20.glGetUniformLocation(progId, "uDarkCircles"),
                hNoseSlimming = GLES20.glGetUniformLocation(progId, "uNoseSlimming"),
                hMouth = GLES20.glGetUniformLocation(progId, "uMouth"),
                hTeethWhitening = GLES20.glGetUniformLocation(progId, "uTeethWhitening"),
                hLipstick = GLES20.glGetUniformLocation(progId, "uLipstick"),
                hBlush = GLES20.glGetUniformLocation(progId, "uBlush"),
                hEyebrows = GLES20.glGetUniformLocation(progId, "uEyebrows"),
                hLongLegs = GLES20.glGetUniformLocation(progId, "uLongLegs"),
                hSmallHead = GLES20.glGetUniformLocation(progId, "uSmallHead"),
                hFaceDetected = GLES20.glGetUniformLocation(progId, "uFaceDetected"),
                hFaceCenter = GLES20.glGetUniformLocation(progId, "uFaceCenter"),
                hEyeDistance = GLES20.glGetUniformLocation(progId, "uEyeDistance"),
                hHasDetailed = GLES20.glGetUniformLocation(progId, "uHasDetailed"),
                hEyeLeft = GLES20.glGetUniformLocation(progId, "uEyeLeft"),
                hEyeRight = GLES20.glGetUniformLocation(progId, "uEyeRight"),
                hMouthPos = GLES20.glGetUniformLocation(progId, "uMouthPos"),
                hMouthHalfW = GLES20.glGetUniformLocation(progId, "uMouthHalfW"),
                hMouthHalfH = GLES20.glGetUniformLocation(progId, "uMouthHalfH"),
                hMouthAngle = GLES20.glGetUniformLocation(progId, "uMouthAngle"),
                hChin = GLES20.glGetUniformLocation(progId, "uChin"),
                hWarpDualCenter = GLES20.glGetUniformLocation(progId, "uWarpDualCenter"),
                hLutTexture = GLES20.glGetUniformLocation(progId, "uLutTexture"),
                hLutMix = GLES20.glGetUniformLocation(progId, "uLutMix")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compile/link ${if (videoVariant) "video" else "image"} GLES program", e)
            shaderError = e.message ?: "shader compile failed"
            null
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.04f, 0.05f, 0.07f, 1.0f) // Dark night canvas background Hex #0a0c12
        GLES20.glDisable(GLES20.GL_DEPTH_TEST) // 2D or spherically projected mapping, depth test is unneeded

        // 记录实际拿到的 GL 版本：上下文虽然按 ES3 请求，但真机上驱动可能仍回退到 ES2
        // （VRGLSurfaceView / HuaweiVrActivity 均 setEGLContextClientVersion(3)）。
        // 排查「shader 在 A 设备能编译、B 设备黑屏」这类问题时，第一手证据就是这行日志。
        // GL_MAJOR_VERSION / GL_MINOR_VERSION 在 ES2 上下文下查询无效，故只用 GL_VERSION 字符串。
        Log.i(TAG, "GL_VERSION = ${GLES20.glGetString(GLES20.GL_VERSION)}"
                + " | VENDOR = ${GLES20.glGetString(GLES20.GL_VENDOR)}")

        // Build the two main programs: video variant samples an OES external texture,
        // image variant uses plain sampler2D so strict GPU drivers (Adreno etc.) never
        // hit an empty OES slot while displaying photos/panoramas.
        videoProgram = buildMainProgram(videoVariant = true)
        imageProgram = buildMainProgram(videoVariant = false)

        // Fallback program: keep images/panoramas visible even if the main shader
        // is rejected by a strict device driver.
        try {
            val vShader = compileShader(GLES20.GL_VERTEX_SHADER, fallbackVertexShaderCode)
            val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fallbackFragmentShaderCode)
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

        // 华为 VR 模式下帧循环由 HuaweiVrActivity 驱动（每帧要先 acquire swapchain 才能画），
        // GLSurfaceView 自己的 onDrawFrame 只负责把默认 framebuffer 清成黑底，
        // 否则会与双眼 FBO 渲染互抢 framebuffer 绑定与视频帧消费。
        if (huaweiVrMode) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }

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
        synchronized(this) {
            if (isVideoFrameAvailable) {
                videoSurfaceTexture?.updateTexImage()
                isVideoFrameAvailable = false
            }
        }

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
        if (beautyEngineType == BEAUTY_ENGINE_GPUPIXEL && !GpuPixelBeauty.available) {
            GpuPixelBeauty.init(context)
        }
        val gpActive = isGpuPixelActive()
        // v2.0.177：磨皮半分辨率 pass 激活时，主渲染写到 downFbo（全分辨率纹理）而非屏幕，
        // 之后跑 down→blur 两个 pass 再合成上屏。
        val halfActive = isHalfPassActive()
        // 主 shader 的磨皮段是否要让位给离屏 pass（见下方 hBeautyStrength 同步处）
        val halfPassThisFrame = halfActive
        if (gpActive) {
            ensureGpFbo(displayWidth, displayHeight)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, gpFboId)
        } else if (halfActive) {
            ensureHalfPassFbos(displayWidth, displayHeight)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, downFboId)
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // v102：视频/图片统一走主程序 shader（美颜在片元着色器内实时计算）
        val prog = if (isVideoActive) videoProgram else imageProgram
        if (prog == null) {
            // Neither main program is available on this driver: use the plain 2D fallback.
            drawFallbackFrame()
            return
        }

        // Use shader
        GLES20.glUseProgram(prog.programId)

        // Bind active textures
        bindActiveTextures(prog)

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
        val bc = if (beautyMasterEnabled && beautyEngineType == BEAUTY_ENGINE_GLSL) 1f else 0f
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
        uniform2f(prog.hFaceCenter, faceCenterXUniform, faceCenterYUniform)
        uniform1f(prog.hEyeDistance, eyeDistanceUniform)

        // Precise MediaPipe 468-point landmarks (if the real model is active)
        uniform1i(prog.hHasDetailed, hasDetailedLandmarks)
        uniform2f(prog.hEyeLeft, eyeLeftXUniform, eyeLeftYUniform)
        uniform2f(prog.hEyeRight, eyeRightXUniform, eyeRightYUniform)
        uniform2f(prog.hMouthPos, mouthXUniform, mouthYUniform)
        // v2.0.159：嘴形参数（0 表示尚未取到，shader 会回退到按 fUnit 估算）
        uniform1f(prog.hMouthHalfW, mouthHalfWidthUniform)
        uniform1f(prog.hMouthHalfH, mouthHalfHeightUniform)
        uniform1f(prog.hMouthAngle, mouthAngleUniform)
        uniform2f(prog.hChin, chinXUniform, chinYUniform)
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

            // Left Eye Viewport
            GLES20.glViewport(0, 0, halfWidth, displayHeight)
            calculateAndApplyMatrices(isLeft = true, aspect = halfWidth.toFloat() / displayHeight.toFloat(), mvpLoc = prog.hMVPMatrix)
            uniform1i(prog.hLeftEye, 1)
            drawActiveGeometry(prog)

            // Right Eye Viewport
            GLES20.glViewport(halfWidth, 0, halfWidth, displayHeight)
            calculateAndApplyMatrices(isLeft = false, aspect = halfWidth.toFloat() / displayHeight.toFloat(), mvpLoc = prog.hMVPMatrix)
            uniform1i(prog.hLeftEye, 0)
            drawActiveGeometry(prog)
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
        if (isPlanarFaceMode || (gpActive && gpuPixelVrFaceBeauty)) maybeScheduleFaceSampling()

        // v2.0.160：GPUPixel 链路收尾 —— 把后台处理完的人脸区域写回 FBO，再将整帧 blit 到屏幕
        if (gpActive) {
            uploadPendingGpRegion()
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            blitToScreen()
        } else if (halfActive) {
            // v2.0.177：跑 down(降采样) → blur(半分辨率低频)，再合成上屏。
            // ⚠️ 主渲染的磨皮段必须已被 uBeautyStrength = 0 关掉（见 uniform 同步处），
            //    否则会「主 shader 糊一次 + 半分辨率再糊一次」双重磨皮。
            runHalfSmoothingPasses()
            blendHalfPassToScreen()
        }
    }

    // ======================= 华为 VR（OpenXR）单眼绘制 =======================

    /**
     * 用 OpenXR Runtime 给出的 FOV / 头姿矩阵绘制第 [eye] 眼。
     *
     * OpenXR 的投影矩阵**不能**用 `Matrix.frustumM` 直接构造：
     * `frustumM` 假设视锥关于光轴对称（left = -right），而 VR 眼镜的每眼视锥
     * 是**倾斜**的（左右/上下不对称），必须用通用形式。
     *
     * 推导：设近裁面 `n`，则
     *   l = n·tan(angleLeft)，r = n·tan(angleRight)
     *   b = n·tan(angleDown)，t = n·tan(angleUp)
     * 投影矩阵（列主序，与 GLSL mat4 一致）：
     *   [ 2n/(r-l)      0           (r+l)/(r-l)      0        ]
     *   [ 0             2n/(t-b)    (t+b)/(t-b)      0        ]
     *   [ 0             0          -(f+n)/(f-n)     -2fn/(f-n) ]
     *   [ 0             0          -1                0        ]
     *
     * 视图矩阵直接用 native 算好的（`R(q)^T · T(-p)`），已是列主序。
     * model 矩阵保持与内置分屏 VR 相同的语义（几何/缩放/手动旋转仍生效），
     * 这样球面/穹顶/盒面等投影模式在眼镜里表现一致。
     */
    private fun drawEyeWithOpenXrMatrices(eye: Int, w: Int, h: Int, targets: FloatArray) {
        // 与内置路径一致：视频走视频变体、图片走图片变体
        val prog = if (isVideoActive) videoProgram else imageProgram
        if (prog == null) { drawFallbackFrame(); return }

        GLES20.glUseProgram(prog.programId)

        val base = eye * 20

        // --- 1) 用 OpenXR FOV 构造倾斜视锥投影矩阵 ---
        val fovL = targets[base + 16]
        val fovR = targets[base + 17]
        val fovU = targets[base + 18]
        val fovD = targets[base + 19]
        val near = 0.05f
        val far = 100.0f
        val tanL = StrictMath.tan(fovL.toDouble()).toFloat()
        val tanR = StrictMath.tan(fovR.toDouble()).toFloat()
        val tanU = StrictMath.tan(fovU.toDouble()).toFloat()
        val tanD = StrictMath.tan(fovD.toDouble()).toFloat()

        val l = near * tanL
        val r = near * tanR
        val b = near * tanD
        val t = near * tanU

        // 退化保护：极端 FOV 会让分母趋零 → 直接退出该眼（保留上一帧，别画花屏）
        if (kotlin.math.abs(r - l) < 1e-6f || kotlin.math.abs(t - b) < 1e-6f) {
            Log.w(TAG, "eye$eye FOV 退化，跳过（l=$l r=$r b=$b t=$t）")
            return
        }

        val pm = projectionMatrix
        java.util.Arrays.fill(pm, 0f)
        pm[0] = 2f * near / (r - l)
        pm[5] = 2f * near / (t - b)
        pm[8] = (r + l) / (r - l)
        pm[9] = (t + b) / (t - b)
        pm[10] = -(far + near) / (far - near)
        pm[11] = -1f
        pm[14] = -2f * far * near / (far - near)

        // --- 2) 视图矩阵：直接用 native 的 OpenXR 结果 ---
        System.arraycopy(targets, base, viewMatrix, 0, 16)

        // --- 3) model 矩阵：沿用内置逻辑（几何缩放 + 手动旋转），但不叠加陀螺仪 ---
        //     ⚠️ 头姿已由 OpenXR 的 viewMatrix 提供，若再叠陀螺仪会「转两次」。
        Matrix.setIdentityM(modelMatrix, 0)

        // 2D 平面投影时按源宽高比做 letterbox / pillarbox
        val isPlanarMode = projectionMode == ProjectionMode.STANDARD ||
            projectionMode == ProjectionMode.FISHEYE
        if (isPlanarMode) {
            val srcW = if (isVideoActive) videoWidth else imageWidth
            val srcH = if (isVideoActive) videoHeight else imageHeight
            if (srcW > 0 && srcH > 0) {
                val aScreen = if (h > 0) w.toFloat() / h.toFloat() else 1f
                val aSrc = srcW.toFloat() / srcH.toFloat()
                var scaleX = 1.0f
                var scaleY = 1.0f
                if (aSrc > aScreen) scaleY = aScreen / aSrc else scaleX = aSrc / aScreen
                Matrix.scaleM(modelMatrix, 0, scaleX, scaleY, 1.0f)
            }
        }

        // 手动拖拽视角（VR_360/180/BOX 下作为朝向微调；STANDARD 不旋转，与内置一致）
        if (projectionMode != ProjectionMode.STANDARD) {
            Matrix.rotateM(modelMatrix, 0, manualPitch, 1.0f, 0.0f, 0.0f)
            Matrix.rotateM(modelMatrix, 0, manualYaw, 0.0f, 1.0f, 0.0f)
        }

        // --- 4) 合成 MVP ---
        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)
        uniformMatrix4fv(prog.hMVPMatrix, 1, false, mvpMatrix, 0)

        // --- 5) 同步美颜 / 投影 / 立体等 uniform（与内置路径共用同一套着色器） ---
        uniform1i(prog.hProjectionMode, projectionMode.id)
        uniform1i(prog.hStereoMode, stereoMode.id)

        val bc = if (beautyMasterEnabled && beautyEngineType == BEAUTY_ENGINE_GLSL) 1f else 0f
        uniform1f(prog.hBeautyStrength, beautyLevel * bc)
        uniform1f(prog.hTextureDetail, beautyTextureDetail)
        uniform1f(prog.hBrightness, brightnessLevel)
        uniform1f(prog.hContrast, contrastLevel)
        uniform1i(prog.hIsMirrored, if (isMirrored) 1 else 0)
        uniform1i(prog.hWarpMode, warpMode.id)
        uniform1f(prog.hCurvature, cylinderCurvature)
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
        uniform2f(prog.hFaceCenter, faceCenterXUniform, faceCenterYUniform)
        uniform1f(prog.hEyeDistance, eyeDistanceUniform)
        uniform1i(prog.hHasDetailed, hasDetailedLandmarks)
        uniform2f(prog.hEyeLeft, eyeLeftXUniform, eyeLeftYUniform)
        uniform2f(prog.hEyeRight, eyeRightXUniform, eyeRightYUniform)
        uniform2f(prog.hMouthPos, mouthXUniform, mouthYUniform)
        uniform1f(prog.hMouthHalfW, mouthHalfWidthUniform)
        uniform1f(prog.hMouthHalfH, mouthHalfHeightUniform)
        uniform1f(prog.hMouthAngle, mouthAngleUniform)
        uniform2f(prog.hChin, chinXUniform, chinYUniform)
        uniform1i(prog.hWarpDualCenter, if (warpDualCenter) 1 else 0)
        // v2.0.181：同内置路径 —— 图片模式用图片自身 texel，视频用视口 texel
        uniform2f(
            prog.hTexelSize,
            if (!isVideoActive && imageTexelW > 0f) imageTexelW else 1.0f / w.coerceAtLeast(1),
            if (!isVideoActive && imageTexelH > 0f) imageTexelH else 1.0f / h.coerceAtLeast(1)
        )

        // uLeftEye：华为模式下每眼是独立渲染目标，填 1（左侧语义）——
        // 部分几何（如半宽面片）会据此取纹理左半，但双眼各自全屏绘制时不受影响。
        uniform1i(prog.hLeftEye, 1)

        // --- 6) 绑纹理并绘制 ---
        bindActiveTextures(prog)
        drawActiveGeometry(prog)
    }

    /**
     * 绑定视频/图片源纹理与 LUT（含占位回退）。
     *
     * 抽成独立函数是因为**华为 VR 双眼通路与内置分屏通路都要用**，
     * 且必须保证两条路径的纹理单元分配完全一致（unit1 = OES 视频、unit2 = LUT）。
     */
    private fun bindActiveTextures(prog: GLProgram) {
        if (isVideoActive) {
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
            projectionMode == ProjectionMode.BOX
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
            val offset = if (isLeft) -0.04f else 0.04f
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
                val aSrc = srcW.toFloat() / srcH.toFloat()
                var scaleX = 1.0f
                var scaleY = 1.0f
                if (aSrc > aScreen) {
                    // Source is wider than the display: match width, letterbox top/bottom
                    scaleY = aScreen / aSrc
                } else {
                    // Source is taller: match height, pillarbox left/right
                    scaleX = aSrc / aScreen
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
            projectionMode == ProjectionMode.BOX
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

    private fun drawActiveGeometry(prog: GLProgram) {
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
    private fun compileShader(type: Int, code: String): Int {
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
            releaseHalfPassFbos()
            if (pDownProgram != 0) { GLES20.glDeleteProgram(pDownProgram); pDownProgram = 0 }
            if (pBlurProgram != 0) { GLES20.glDeleteProgram(pBlurProgram); pBlurProgram = 0 }
            if (pBlendProgram != 0) { GLES20.glDeleteProgram(pBlendProgram); pBlendProgram = 0 }
            halfPassReady = false
        } catch (e: Throwable) {
            // ignore
        }
        try {
            faceExecutor.shutdownNow()
            faceInitExecutor.shutdownNow()
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
    private fun isBeautyActive(): Boolean {
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
    private fun mapCropXToViewport(cx: Float): Float = 0.5f + (cx - 0.5f) * faceCropScaleX

    /**
     * 同上，y 方向**额外翻转一次**：
     * `glReadPixels` 的行序是自下而上，却被按行直接填进 Bitmap（自上而下），
     * 于是 Bitmap 相对屏幕上下颠倒 —— MediaPipe 报上来的 y 与屏幕方向相反。
     * 采样区垂直居中，故翻转与缩放合并为 `0.5 - (cy - 0.5) * scaleY`。
     */
    private fun mapCropYToViewport(cy: Float): Float = 0.5f - (cy - 0.5f) * faceCropScaleY

    /** 长度量（如眼距）按水平比例缩放：眼距主要是水平距离，见 [mapCropXToViewport] */
    private fun mapCropLenToViewport(len: Float): Float = len * faceCropScaleX

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
    private fun isGpuPixelActive(): Boolean {
        if (!beautyMasterEnabled || beautyEngineType != BEAUTY_ENGINE_GPUPIXEL) return false
        if (!GpuPixelBeauty.available) return false
        return isPlanarProjection() || gpuPixelVrFaceBeauty
    }

    private fun isPlanarProjection(): Boolean =
        projectionMode == ProjectionMode.STANDARD || projectionMode == ProjectionMode.FISHEYE

    /** 创建（或按尺寸重建）GPUPixel 模式的离屏 FBO */
    private fun ensureGpFbo(w: Int, h: Int) {
        if (gpFboId != 0 && gpFboW == w && gpFboH == h) return
        releaseGpFbo()
        val tex = IntArray(1)
        val fbo = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[0], 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        gpFboTexId = tex[0]
        gpFboId = fbo[0]
        gpFboW = w
        gpFboH = h
        ensureBlitProgram()
    }

    private fun releaseGpFbo() {
        if (gpFboTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(gpFboTexId), 0)
        if (gpFboId != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(gpFboId), 0)
        gpFboTexId = 0; gpFboId = 0; gpFboW = 0; gpFboH = 0
        releaseGpHalfFbo()    // v2.0.183：降采样 FBO 随主 FBO 一起释放
        releaseGpUploadTex()  // v2.0.182：中转纹理随 FBO 一起释放
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
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
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

    /**
     * 把主 FBO 的 [srcUvX, srcUvY, srcUvW, srcUvH] 区域**真正缩放**渲染到 [dstW]×[dstH]
     * 的降采样 FBO（GPU 线性滤波），随后可从降采样 FBO 读像素。
     *
     * 这是「降采样回读」唯一正确的实现方式 —— `glReadPixels` 只裁剪不缩放。
     *
     * 返回是否成功（FBO 不完整 / 尺寸非法则返回 false，调用方退回全分辨率回读）。
     */
    private fun blitFboToHalf(srcUvX: Float, srcUvY: Float, srcUvW: Float, srcUvH: Float,
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

        GLES20.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, gpFboId)
        GLES20.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, gpHalfFboId)
        GLES30.glBlitFramebuffer(
            sx0, sy0, sx1, sy1,
            0, 0, dstW, dstH,
            GLES20.GL_COLOR_BUFFER_BIT, GLES20.GL_LINEAR
        )
        val err = GLES20.glGetError()
        GLES20.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, 0)
        GLES20.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        if (err != GLES20.GL_NO_ERROR) {
            Log.w(TAG, "glBlitFramebuffer downscale failed err=$err, fallback to full-res read")
            return false
        }
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

    // ===== v2.0.177：磨皮半分辨率 pass 的三个 program =====

    /** 全屏 quad 的顶点着色器：pos(-1..1) + tex(0..1)，直接透传，不做任何变换 */
    private val halffsVs = "attribute vec2 aPos;attribute vec2 aTex;varying vec2 vTex;" +
        "void main(){gl_Position=vec4(aPos,0.0,1.0);vTex=aTex;}"

    /** down pass：把全分辨率纹理降采样到 1/2（GL_LINEAR 自带 2x2 平均，已足够） */
    private val halfDownFs = "precision mediump float;varying vec2 vTex;uniform sampler2D uTex;" +
        "void main(){gl_FragColor=texture2D(uTex,vTex);}"

    /**
     * blur pass：半分辨率上做 8 邻域**保边**均值（频域分离的低频层）。
     *
     * 与主 shader 内联版算法的唯一区别是采样半径：主 shader 用 ±6 个**全分辨率**像素，
     * 这里纹理已是半分辨率，故半径取 ±6 个半分辨率像素 —— 视觉上等效于全分辨率的 ±12，
     * 只让低频更平滑一点（磨皮本就只要低频），并不会改变观感量级。
     * `uTexelSize` 由 Kotlin 侧按**半分辨率纹理**传入（绝不能复用全分辨率那份，
     * 否则半径会翻倍成 ±12 半分辨率像素 ≈ 全分辨率 ±24，磨皮会糊成一片）。
     */
    private val halfBlurFs = """
        precision mediump float;
        varying vec2 vTex;
        uniform sampler2D uTex;
        uniform vec2 uTexelSize;
        void main() {
            vec4 color = texture2D(uTex, vTex);
            vec2 stepF = vec2(uTexelSize.x * 6.0, uTexelSize.y * 6.0);
            vec4 n1 = texture2D(uTex, vTex + stepF);
            vec4 n2 = texture2D(uTex, vTex - stepF);
            vec4 n3 = texture2D(uTex, vTex + vec2(stepF.x, 0.0));
            vec4 n4 = texture2D(uTex, vTex - vec2(stepF.x, 0.0));
            vec4 n5 = texture2D(uTex, vTex + vec2(0.0, stepF.y));
            vec4 n6 = texture2D(uTex, vTex - vec2(0.0, stepF.y));
            vec4 n7 = texture2D(uTex, vTex + vec2(stepF.x, -stepF.y));
            vec4 n8 = texture2D(uTex, vTex - vec2(stepF.x, -stepF.y));
            float deltaThres = 0.30;
            float w1 = max(0.0, 1.0 - distance(color.rgb, n1.rgb) / deltaThres);
            float w2 = max(0.0, 1.0 - distance(color.rgb, n2.rgb) / deltaThres);
            float w3 = max(0.0, 1.0 - distance(color.rgb, n3.rgb) / deltaThres);
            float w4 = max(0.0, 1.0 - distance(color.rgb, n4.rgb) / deltaThres);
            float w5 = max(0.0, 1.0 - distance(color.rgb, n5.rgb) / deltaThres);
            float w6 = max(0.0, 1.0 - distance(color.rgb, n6.rgb) / deltaThres);
            float w7 = max(0.0, 1.0 - distance(color.rgb, n7.rgb) / deltaThres);
            float w8 = max(0.0, 1.0 - distance(color.rgb, n8.rgb) / deltaThres);
            gl_FragColor = (color + n1 * w1 + n2 * w2 + n3 * w3 + n4 * w4
                + n5 * w5 + n6 * w6 + n7 * w7 + n8 * w8)
                / (1.0 + w1 + w2 + w3 + w4 + w5 + w6 + w7 + w8);
        }
    """.trimIndent()

    /**
     * blend pass：全分辨率原图 + 半分辨率低频 → 上屏。
     *
     * 频域分离在这里完成：
     *   低频 = texture2D(uLowTex, vTex)        （半分辨率，双线性上采样）
     *   高频 = 原图 - 低频                      （全分辨率，含毛孔/发丝/五官边缘）
     *   结果 = 低频 + 高频 × uTextureDetail
     * 再按 isSkin 与 uBeautyStrength 决定混合强度 —— 与主 shader 内联版语义完全一致，
     * 只是**皮肤判定也从主 shader 搬到了这里**（主 shader 不再做磨皮）。
     */
    private val halfBlendFs = """
        precision highp float;
        varying vec2 vTex;
        uniform sampler2D uFullTex;   // 全分辨率原图（主渲染结果）
        uniform sampler2D uLowTex;    // 半分辨率低频层
        uniform float uBeautyStrength;
        uniform float uTextureDetail;
        bool isSkin(vec3 rgb) {
            float r = rgb.r;
            float g = rgb.g;
            float b = rgb.b;
            return (r > 0.35 && g > 0.15 && b > 0.08 && r > g && r > b && (r - g) > 0.05);
        }
        void main() {
            vec4 full = texture2D(uFullTex, vTex);
            if (uBeautyStrength <= 0.01) {
                gl_FragColor = full;
                return;
            }
            vec3 low = texture2D(uLowTex, vTex).rgb;
            vec3 detail = full.rgb - low;
            vec3 smoothed = low + detail * uTextureDetail;
            if (isSkin(full.rgb)) {
                gl_FragColor = vec4(mix(full.rgb, smoothed, uBeautyStrength), full.a);
            } else {
                // v2.0.178 修复：与主 shader 一致 —— 非皮肤像素不再「完全跳过」。
                // 实测真实视频 isSkin 总通过率仅 35%（粉/紫背景 0%），若背景完全不动，
                // 皮肤与背景之间会出现可见的一圈分界，观感即「美颜只在一小块生效」。
                // 这里用半分辨率低频层也做一次 0.25 权重的轻度混合（零额外采样）。
                gl_FragColor = vec4(mix(full.rgb, smoothed, uBeautyStrength * 0.25), full.a);
            }
        }
    """.trimIndent()

    /** 编译并链接一个「aPos + aTex」全屏 pass program；失败返回 0 */
    private fun buildHalfPassProgram(fs: String): Int {
        return try {
            val vsId = compileGpuShader(GLES20.GL_VERTEX_SHADER, halffsVs)
            val fsId = compileGpuShader(GLES20.GL_FRAGMENT_SHADER, fs)
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vsId)
            GLES20.glAttachShader(prog, fsId)
            GLES20.glLinkProgram(prog)
            val st = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, st, 0)
            if (st[0] == 0) {
                Log.e(TAG, "半分辨率磨皮 pass 链接失败: ${GLES20.glGetProgramInfoLog(prog)}")
                0
            } else {
                prog
            }
        } catch (e: Throwable) {
            Log.e(TAG, "半分辨率磨皮 pass 编译失败", e)
            0
        }
    }

    private fun ensureHalfPassPrograms() {
        if (pDownProgram == 0) pDownProgram = buildHalfPassProgram(halfDownFs)
        if (pBlurProgram == 0) pBlurProgram = buildHalfPassProgram(halfBlurFs)
        if (pBlendProgram == 0) pBlendProgram = buildHalfPassProgram(halfBlendFs)
    }

    /** 创建一个可作 FBO attachment 的 RGBA 纹理（LINEAR + CLAMP_TO_EDGE） */
    private fun createFboTexture(w: Int, h: Int): Int {
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
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return tex[0]
    }

    private fun createFboFor(texId: Int): Int {
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, texId, 0
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return fbo[0]
    }

    /** 按需（重）建半分辨率 pass 的 FBO 组。尺寸变化时才重建 */
    private fun ensureHalfPassFbos(w: Int, h: Int) {
        if (downTexId != 0 && downFboW == w && downFboH == h) return
        releaseHalfPassFbos()
        downTexId = createFboTexture(w, h)
        downFboId = createFboFor(downTexId)
        halfW = (w / 2).coerceAtLeast(1)
        halfH = (h / 2).coerceAtLeast(1)
        halfTexId = createFboTexture(halfW, halfH)
        halfFboId = createFboFor(halfTexId)
        halfLowTexId = createFboTexture(halfW, halfH)
        halfLowFboId = createFboFor(halfLowTexId)
        downFboW = w
        downFboH = h
        halfFboW = halfW
        halfFboH = halfH
    }

    private fun releaseHalfPassFbos() {
        if (downTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(downTexId), 0)
        if (downFboId != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(downFboId), 0)
        if (halfTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(halfTexId), 0)
        if (halfFboId != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(halfFboId), 0)
        if (halfLowTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(halfLowTexId), 0)
        if (halfLowFboId != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(halfLowFboId), 0)
        downTexId = 0; downFboId = 0
        halfTexId = 0; halfFboId = 0
        halfLowTexId = 0; halfLowFboId = 0
        downFboW = 0; downFboH = 0; halfFboW = 0; halfFboH = 0
    }

    /**
     * 是否启用「磨皮半分辨率 pass」。
     *
     * 仅在**内置分屏/全屏通路**下启用（[huaweiVrMode] 与 GPUPixel 通路各走自己的渲染目标，
     * 混进来会把 FBO 绑定搞乱）。且必须磨皮真的在跑（GLSL 引擎 + 总开关 + 强度 > 0）。
     */
    private fun isHalfPassActive(): Boolean =
        !huaweiVrMode &&
            !isGpuPixelActive() &&
            beautyMasterEnabled &&
            beautyEngineType == BEAUTY_ENGINE_GLSL &&
            beautyLevel > 0.01f &&
            displayWidth > 0 && displayHeight > 0

    /**
     * 绘制一个全屏 quad 到当前绑定的 framebuffer（供三个 pass 复用）。
     * [texUnit0] / [texUnit1] 为 -1 表示不绑定该单元。
     */
    private fun drawHalfQuad(prog: Int, texUnit0: Int, texUnit1: Int, texelW: Float, texelH: Float) {
        val posLoc = GLES20.glGetAttribLocation(prog, "aPos")
        val texLoc = GLES20.glGetAttribLocation(prog, "aTex")
        GLES20.glUseProgram(prog)
        if (texUnit0 != -1) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texUnit0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTex"), 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uFullTex"), 0)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uTexelSize"), texelW, texelH)
        }
        if (texUnit1 != -1) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texUnit1)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uLowTex"), 1)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        }
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

    /**
     * 跑三个 pass，把「低频层」准备好。
     *
     * ⚠️ 副作用：结束时**不恢复 framebuffer 绑定**（停在 0 = 屏幕）。
     *    调用方需自行绑定到 [downFboId] 做正式绘制。
     */
    private fun runHalfSmoothingPasses() {
        ensureHalfPassPrograms()
        if (pDownProgram == 0 || pBlurProgram == 0 || pBlendProgram == 0) {
            halfPassReady = false
            return
        }
        ensureHalfPassFbos(displayWidth, displayHeight)

        // pass 1：全分辨率 → 半分辨率
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, halfFboId)
        GLES20.glViewport(0, 0, halfW, halfH)
        GLES20.glDisable(GLES20.GL_BLEND)
        drawHalfQuad(pDownProgram, downTexId, -1, 0f, 0f)

        // pass 2：半分辨率 8 邻域保边均值 → 半分辨率低频层
        // ⚠️ texel 尺寸必须按**半分辨率纹理**算（1/halfW），不能用 1/displayWidth
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, halfLowFboId)
        GLES20.glViewport(0, 0, halfW, halfH)
        drawHalfQuad(pBlurProgram, halfTexId, -1, 1.0f / halfW, 1.0f / halfH)

        halfPassReady = true
        // 交给调用方绑定 downFbo 做正式绘制（不在此恢复 framebuffer，避免多一次切换）
    }

    /**
     * 磨皮半分辨率 pass 的合成上屏：全分辨率原图 + 半分辨率低频 → 屏幕。
     *
     * 全程 `glDisable(GL_BLEND)`：主渲染用的是直写（不混合），而 FBO 预乘/混合语义易踩坑。
     */
    private fun blendHalfPassToScreen() {
        if (!halfPassReady || pBlendProgram == 0 || downTexId == 0 || halfLowTexId == 0) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, displayWidth, displayHeight)
        GLES20.glDisable(GLES20.GL_BLEND)
        val prog = pBlendProgram
        GLES20.glUseProgram(prog)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uBeautyStrength"), beautyLevel)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uTextureDetail"), beautyTextureDetail)
        drawHalfQuad(prog, downTexId, halfLowTexId, 0f, 0f)
    }

    private fun blitToScreen() {
        if (gpBlitProgram == 0 || gpBlitQuad == null) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, gpFboW, gpFboH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(gpBlitProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gpFboTexId)
        GLES20.glUniform1i(gpBlitTexLoc, 0)
        val q = gpBlitQuad!!
        q.position(0)
        GLES20.glVertexAttribPointer(gpBlitPosLoc, 2, GLES20.GL_FLOAT, false, 16, q)
        GLES20.glEnableVertexAttribArray(gpBlitPosLoc)
        val texAttr = GLES20.glGetAttribLocation(gpBlitProgram, "aTex")
        q.position(2)
        GLES20.glVertexAttribPointer(texAttr, 2, GLES20.GL_FLOAT, false, 16, q)
        GLES20.glEnableVertexAttribArray(texAttr)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(gpBlitPosLoc)
        GLES20.glDisableVertexAttribArray(texAttr)
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
    private fun uploadPendingGpRegion() {
        val bytes = gpRegionPending ?: return
        gpRegionPending = null
        try {
            if (gpFboTexId != 0 && gpRegionW > 0 && gpRegionH > 0) {
                doUploadGpRegion(bytes)
            }
        } finally {
            // v2.0.182 性能：用完把缓冲还池，供下一帧拷贝复用（省掉每帧 2.1 MB 分配）
            synchronized(gpRegionPoolLock) { gpRegionPool = bytes }
        }
    }

    private fun doUploadGpRegion(bytes: ByteArray) {
        // 需不需要缩放贴回：回读尺寸 < 目标矩形尺寸（即降采样了）就必须走 blit 拉伸。
        // ⚠️ 不能用「gpRegionW == gpLastReadW」判断 —— 二者恒等（回读就是该尺寸），
        //    那样会永远走直通，把降采样结果只填在视口左上角。
        val dstW = (gpReadUvW * gpFboW + 0.5f).toInt()
        val dstH = (gpReadUvH * gpFboH + 0.5f).toInt()
        val needsScale = dstW > 0 && dstH > 0 &&
            (gpRegionW != dstW || gpRegionH != dstH)

        if (!needsScale) {
            // 1:1 直通：直接把处理结果写回 FBO 的同一区域（无人脸 ROI 缩放的旧路径）
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, gpFboTexId)
            GLES20.glTexSubImage2D(
                GLES20.GL_TEXTURE_2D, 0, gpLastReadX0, gpLastReadY0, gpRegionW, gpRegionH,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, java.nio.ByteBuffer.wrap(bytes)
            )
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            return
        }

        // 缩放路径：先传到临时纹理，再拉伸贴回 FBO 的对应区域（GPU 线性上采样）
        ensureGpUploadTex(gpRegionW, gpRegionH)
        val uploadTex = gpUploadTexId
        if (uploadTex == 0) return
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uploadTex)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0, 0, 0, gpRegionW, gpRegionH,
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
        verts.position(2)
        val texAttr = GLES20.glGetAttribLocation(gpBlitProgram, "aTex")
        GLES20.glVertexAttribPointer(texAttr, 2, GLES20.GL_FLOAT, false, 16, verts)
        GLES20.glEnableVertexAttribArray(texAttr)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(gpBlitPosLoc)
        GLES20.glDisableVertexAttribArray(texAttr)
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
    private fun computeGpCoverRegion(w: Int, h: Int): IntArray {
        // VR / 3D 投影下屏幕内容更「高频」（球面网格线、几何边缘），降采样过猛会看出软化，
        // 故平面模式用 gpReadDownscale，非平面（VR）模式降一档（更保守）。
        val base = gpReadDownscale.coerceAtLeast(1)
        val d = if (isPlanarProjection()) base else (base).coerceAtMost(2)
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
    private fun gpCoverScale(): Float = 1.0f

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
    private fun computeGpRoi(w: Int, h: Int): IntArray {
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

    /**
     * 视情况采一帧屏幕内容交给后台做人脸检测 / GPUPixel 处理（v2.0.156 重构，v2.0.177 收窄 ROI）。
     */
    private fun maybeScheduleFaceSampling() {
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
        if (gpFullFrame && isDetectingFace.get()) return

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
                val cov = computeGpCoverRegion(w, h)
                rw = cov[2]; rh = cov[3]; x0 = cov[0]; y0 = cov[1]
                gpFullCoverageThisFrame = true
                // 全覆盖：回读（可能降采样）覆盖整个视口 → UV 范围就是全域
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
            rw = minOf(faceReadSize, w)
            rh = minOf(faceReadSize, h)
            x0 = (w - rw) / 2
            y0 = (h - rh) / 2
            gpFullCoverageThisFrame = false
            gpReadUvX0 = x0.toFloat() / w
            gpReadUvY0 = y0.toFloat() / h
            gpReadUvW = rw.toFloat() / w
            gpReadUvH = rh.toFloat() / h
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

        try {
            val need = rw * rh * 4
            val buf = faceReadBuffer?.takeIf { it.capacity() >= need }
                ?: java.nio.ByteBuffer.allocateDirect(need)
                    .order(java.nio.ByteOrder.nativeOrder())
                    .also { faceReadBuffer = it }
            buf.clear()

            // v2.0.183：降采样回读必须先「真正缩放」再读 —— glReadPixels 只裁剪不缩放。
            // 用 glBlitFramebuffer 把主 FBO 的 [gpReadUv*] 区域缩小渲染到降采样 FBO，
            // 再从降采样 FBO 读（此时读到的才是整屏缩略图，坐标与视口 UV 一一对应）。
            var readFromHalf = false
            if (gpFullCoverageThisFrame && (rw < w || rh < h)) {
                readFromHalf = blitFboToHalf(
                    gpReadUvX0, gpReadUvY0, gpReadUvW, gpReadUvH, rw, rh
                )
            }
            val readFbo = if (readFromHalf) gpHalfFboId else gpFboId
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, readFbo)
            GLES20.glReadPixels(
                if (readFromHalf) 0 else x0,
                if (readFromHalf) 0 else y0,
                rw, rh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
            )
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            buf.rewind()

            // 从池里取一块装这一帧（所有权转移给后台线程，用完后归还）
            val recycled = synchronized(faceFrameLock) {
                faceFramePool?.takeIf { it.size >= need }?.also { faceFramePool = null }
            }
            val frame = recycled ?: ByteArray(need)
            buf.get(frame, 0, need)

            synchronized(faceFrameLock) {
                pendingFaceFrame = frame
                pendingFaceFrameW = rw
                pendingFaceFrameH = rh
            }
            triggerBackgroundFaceDetection()
        } catch (e: Exception) {
            // Ignore transient surface resizing safety errors
        }
    }

    private fun triggerBackgroundFaceDetection() {
        if (isDetectingFace.get()) return // Processing previous face detection

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

        isDetectingFace.set(true)
        faceExecutor.execute {
            try {
                // v2.0.160：GPUPixel 引擎分支 —— 独立检测（Mars-Face）+ 局部处理，不碰 MediaPipe。
                // 一次回读的像素可以同时作为两套引擎的输入（共享的是像素，不是检测结果）。
                if (isGpuPixelActive()) {
                    val out = GpuPixelBeauty.getOrCreatePipeline().process(
                        rgbaData, fw, fh, fw * 4,
                        beautyGpSmooth, beautyGpWhite, beautyGpSlim, beautyGpEyeZoom
                    )
                    if (out != null) {
                        gpRegionW = fw
                        gpRegionH = fh
                        // v2.0.182 性能：GPUPixel 内部会复用它的输出 buffer，必须拷一份再交 GL 线程。
                        // 原先用 out.copyOf() 每次都分配新数组（全覆盖下 2.1 MB/帧 → GC 压力大），
                        // 改为「双缓冲交换」：从池里取一块可复用缓冲，拷完交出去，用过的还回池。
                        val need = out.size
                        val dst = synchronized(gpRegionPoolLock) {
                            gpRegionPool?.takeIf { it.size == need }?.also { gpRegionPool = null }
                        } ?: ByteArray(need)
                        System.arraycopy(out, 0, dst, 0, need)
                        gpRegionPending = dst
                    }
                    return@execute
                }
                // v84 性能优化：复用 Bitmap/数组缓冲（faceExecutor 单线程，安全）
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
                if (faceBmp == null || faceBmp!!.width != fw || faceBmp!!.height != fh) {
                    faceBmp?.recycle()
                    faceBmp = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
                }
                val srcBmp = faceBmp!!
                srcBmp.setPixels(argb, 0, fw, 0, 0, fw, fh)
                // Downscale the larger sampled region to the landmarker input size
                if (faceScaledBmp == null) {
                    faceScaledBmp = Bitmap.createBitmap(faceSampleSize, faceSampleSize, Bitmap.Config.ARGB_8888)
                }
                val scaledBmp = faceScaledBmp!!
                val canvas = android.graphics.Canvas(scaledBmp)
                canvas.drawBitmap(
                    srcBmp,
                    android.graphics.Rect(0, 0, fw, fh),
                    android.graphics.Rect(0, 0, faceSampleSize, faceSampleSize),
                    null
                )

                // Track face landmarks using Google MediaPipe (with legacy fallback)
                val manager = mediaPipeManager
                if (manager != null) {
                    val result = manager.detectFace(scaledBmp)
                    if (result.detected) {
                        // v2.0.156：MediaPipe 给的是「采样裁剪图」空间的坐标，必须换算成视口 UV
                        // 再喂给 shader —— 否则人脸一偏离画面中心，妆容与变形就整体错位
                        // （采样区只覆盖视口中央一小块，不换算相当于把局部坐标当成全屏坐标用）。
                        val cxUv = mapCropXToViewport(result.centerX)
                        val cyUv = mapCropYToViewport(result.centerY)
                        val eyeDistUv = mapCropLenToViewport(result.eyeDistance)
                        // Smooth tracking updates using low-pass lerp filter to eliminate jitter
                        // v2.0.173：检测成功 → 立即置 1 并清零失败计数（见下方失败分支的防抖说明）
                        faceMissStreak = 0
                        faceDetectedUniform = 1
                        faceCenterXUniform = faceCenterXUniform * 0.75f + cxUv * 0.25f
                        faceCenterYUniform = faceCenterYUniform * 0.75f + cyUv * 0.25f
                        eyeDistanceUniform = eyeDistanceUniform * 0.75f + eyeDistUv * 0.25f

                        // Sync the precise MediaPipe 468-point features when available
                        if (result.hasDetailedLandmarks) {
                            hasDetailedLandmarks = 1
                            eyeLeftXUniform = eyeLeftXUniform * 0.75f + mapCropXToViewport(result.eyeLeftX) * 0.25f
                            eyeLeftYUniform = eyeLeftYUniform * 0.75f + mapCropYToViewport(result.eyeLeftY) * 0.25f
                            eyeRightXUniform = eyeRightXUniform * 0.75f + mapCropXToViewport(result.eyeRightX) * 0.25f
                            eyeRightYUniform = eyeRightYUniform * 0.75f + mapCropYToViewport(result.eyeRightY) * 0.25f
                            mouthXUniform = mouthXUniform * 0.75f + mapCropXToViewport(result.mouthX) * 0.25f
                            mouthYUniform = mouthYUniform * 0.75f + mapCropYToViewport(result.mouthY) * 0.25f
                            chinXUniform = chinXUniform * 0.75f + mapCropXToViewport(result.chinX) * 0.25f
                            chinYUniform = chinYUniform * 0.75f + mapCropYToViewport(result.chinY) * 0.25f
                            // v2.0.159：嘴形（宽按水平比例、高按垂直比例缩放；角度不平滑，
                            // 避免在 ±π 附近来回抖动）
                            mouthHalfWidthUniform = mouthHalfWidthUniform * 0.75f +
                                mapCropLenToViewport(result.mouthHalfWidth) * 0.25f
                            mouthHalfHeightUniform = mouthHalfHeightUniform * 0.75f +
                                (result.mouthHalfHeight * faceCropScaleY) * 0.25f
                            mouthAngleUniform = result.mouthAngle
                        } else {
                            // Fallback tracker: derive rough feature positions from center/eye distance
                            hasDetailedLandmarks = 0
                        }
                    } else {
                        // v2.0.173：防抖 —— 检测按 8 帧节奏进行，单次失败（遮挡/侧脸/运动模糊）
                        // 就硬切 0 会让妆容与变形在「出现/消失」间高频跳变（表现为闪烁）。
                        // 连续 3 次失败（≈半秒）才判定真正丢失；期间保持最后位置不漂移。
                        faceMissStreak++
                        if (faceMissStreak >= 3) {
                            faceDetectedUniform = 0
                            hasDetailedLandmarks = 0
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
                // v2.0.156：把这一帧的数组还给池，下一次采样即可复用（省掉每 8 帧 1MB 的分配）
                synchronized(faceFrameLock) { faceFramePool = rgbaData }
                isDetectingFace.set(false)
            }
        }
    }
}
