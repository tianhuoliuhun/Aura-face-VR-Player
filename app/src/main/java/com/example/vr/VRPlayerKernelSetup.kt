package com.example.vr

import android.content.Context
import android.content.SharedPreferences
import android.graphics.SurfaceTexture
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.example.R
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 四播放内核（ExoPlayer / IJK / MPV / 系统 MediaPlayer）初始化与异常恢复宿主状态桥接。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class KernelSetupHost(
    val context: Context,
    val prefs: SharedPreferences,
    val scope: CoroutineScope,
    val audioProcessor: StereoChannelSwappingAudioProcessor,
    val teeAudioProcessor: androidx.media3.exoplayer.audio.TeeAudioProcessor,
    val genderFilterProcessor: GenderFilterAudioProcessor,
    val ijkOptions: IjkOptions,
    val mpvOptions: MpvOptions,
    val getDecoderEngine: () -> DecoderEngine,
    val setDecoderEngine: (DecoderEngine) -> Unit,
    val isSmartProjectionEnabled: Boolean,
    val setIsSplitScreenVR: (Boolean) -> Unit,
    val addCodecParamsEnabled: Boolean,
    val forceHwDecoderEnabled: Boolean,
    val autoFallbackSoftEnabled: Boolean,
    val levelPatchEnabled: Boolean,
    val spoofResolutionEnabled: Boolean,
    val maxResolution: MaxResolution,
    val floatingBallSpeed: Float,
    val basePlaybackSpeed: Float,
    val isFloatingBallPressed: Boolean,
    val selectedMediaItem: MediaItem,
    val currentGlSurfaceView: VRGLSurfaceView?,
    val isLiveStream: Boolean,
    val isRemuxing: Boolean,
    val isTranscoding: Boolean,
    val effectiveOutputSize: (Int, Int) -> Pair<Int, Int>,
    val postToast: (CharSequence) -> Unit,
    val startLevelPatchFix: () -> Unit,
    val getIsSoftwareDecoding: () -> Boolean,
    val setIsSoftwareDecoding: (Boolean) -> Unit,
    val getStereoMode: () -> StereoMode,
    val setStereoMode: (StereoMode) -> Unit,
    val getProjectionMode: () -> ProjectionMode,
    val setProjectionMode: (ProjectionMode) -> Unit,
    val getProjectionModeUserAdjusted: () -> Boolean,
    val setProjectionModeUserAdjusted: (Boolean) -> Unit,
    val getPlayerInstance: () -> VrPlayerBackend?,
    val setPlayerInstance: (VrPlayerBackend?) -> Unit,
    val setIsVideoPlaying: (Boolean) -> Unit,
    val setPlaybackEnded: (Boolean) -> Unit,
    val setVideoSourceWidth: (Int) -> Unit,
    val setVideoSourceHeight: (Int) -> Unit,
    val setVideoDurationText: (String) -> Unit,
    val setExoCueText: (String?) -> Unit,
    val getMpvRetryUri: () -> String?,
    val setMpvRetryUri: (String?) -> Unit,
    val getExoRetryUri: () -> String?,
    val setExoRetryUri: (String?) -> Unit,
    val getSoftRetryUri: () -> String?,
    val setSoftRetryUri: (String?) -> Unit,
    val setLiveForceExoUri: (String?) -> Unit,
    val getCookieRetryAttemptedUri: () -> String?,
    val setCookieRetryAttemptedUri: (String?) -> Unit,
    val getCookieRetryTrigger: () -> Int,
    val setCookieRetryTrigger: (Int) -> Unit,
    val getLiveReconnectAttempt: () -> Int,
    val setLiveReconnectAttempt: (Int) -> Unit,
    val getLiveReconnectJob: () -> Job?,
    val setLiveReconnectJob: (Job?) -> Unit,
    val setShowResolutionTip: (Boolean) -> Unit,
    val setResolutionTipText: (String) -> Unit,
    val setCurrentVideoUri: (Uri?) -> Unit,
    val surfaceHolder: AtomicReference<Surface?>,
    val getForceVideoType: () -> Int,
    val setForceVideoType: (Int) -> Unit,
    val getMpvVoMode: () -> MpvVoMode,
    val setMpvVoMode: (MpvVoMode) -> Unit,
) {
    var decoderEngine: DecoderEngine
        get() = getDecoderEngine()
        set(v) = setDecoderEngine(v)
    var isSplitScreenVR: Boolean
        get() = false
        set(v) = setIsSplitScreenVR(v)
    var currentVideoUri: Uri?
        get() = null
        set(v) = setCurrentVideoUri(v)
    var forceVideoType: Int
        get() = getForceVideoType()
        set(v) = setForceVideoType(v)
    var mpvVoMode: MpvVoMode
        get() = getMpvVoMode()
        set(v) = setMpvVoMode(v)
    var isSoftwareDecoding: Boolean
        get() = getIsSoftwareDecoding()
        set(v) = setIsSoftwareDecoding(v)
    var stereoMode: StereoMode
        get() = getStereoMode()
        set(v) = setStereoMode(v)
    var projectionMode: ProjectionMode
        get() = getProjectionMode()
        set(v) = setProjectionMode(v)
    var projectionModeUserAdjusted: Boolean
        get() = getProjectionModeUserAdjusted()
        set(v) = setProjectionModeUserAdjusted(v)
    var playerInstance: VrPlayerBackend?
        get() = getPlayerInstance()
        set(v) = setPlayerInstance(v)
    var isVideoPlaying: Boolean
        get() = false
        set(v) = setIsVideoPlaying(v)
    var playbackEnded: Boolean
        get() = false
        set(v) = setPlaybackEnded(v)
    var videoSourceWidth: Int
        get() = 0
        set(v) = setVideoSourceWidth(v)
    var videoSourceHeight: Int
        get() = 0
        set(v) = setVideoSourceHeight(v)
    var videoDurationText: String
        get() = ""
        set(v) = setVideoDurationText(v)
    var exoCueText: String?
        get() = null
        set(v) = setExoCueText(v)
    var mpvRetryUri: String?
        get() = getMpvRetryUri()
        set(v) = setMpvRetryUri(v)
    var exoRetryUri: String?
        get() = getExoRetryUri()
        set(v) = setExoRetryUri(v)
    var softRetryUri: String?
        get() = getSoftRetryUri()
        set(v) = setSoftRetryUri(v)
    var liveForceExoUri: String?
        get() = null
        set(v) = setLiveForceExoUri(v)
    var cookieRetryAttemptedUri: String?
        get() = getCookieRetryAttemptedUri()
        set(v) = setCookieRetryAttemptedUri(v)
    var cookieRetryTrigger: Int
        get() = getCookieRetryTrigger()
        set(v) = setCookieRetryTrigger(v)
    var liveReconnectAttempt: Int
        get() = getLiveReconnectAttempt()
        set(v) = setLiveReconnectAttempt(v)
    var liveReconnectJob: Job?
        get() = getLiveReconnectJob()
        set(v) = setLiveReconnectJob(v)
    var showResolutionTip: Boolean
        get() = false
        set(v) = setShowResolutionTip(v)
    var resolutionTipText: String
        get() = ""
        set(v) = setResolutionTipText(v)

    fun effectiveOutputSize(w: Int, h: Int): Pair<Int, Int> = effectiveOutputSize.invoke(w, h)
    fun postToast(msg: CharSequence) = postToast.invoke(msg)
    fun startLevelPatchFix() = startLevelPatchFix.invoke()
}

/**
 * 四内核播放器装配器（从 VRPlayerScreen.kt 拆出）。
 */
internal object VRPlayerKernelSetup {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun setupVideoPlayer(
        host: KernelSetupHost,
        surfaceTexture: SurfaceTexture,
        videoUriStr: String,
    ): Unit = with(host) {
    try {
        playerInstance?.release()
        isVideoPlaying = false

        val decodedUri = Uri.parse(videoUriStr)
        // v2.1.234：记录下来供「视频信息」面板显示（见 currentVideoUri 的声明处）
        currentVideoUri = decodedUri

        // v119 修复(#7)：按当前媒体 URI 读取上次播放位置（不再跨媒体共享同一个变量）
        // v2.6.24（直播 P1）：**直播不恢复断点** —— 断点表里存的是上一场的播放位置，
        // 对实时流是失效位置：轻则被 clamp 到窗口边缘（画面顿一下才追到实时），
        // 重则触发一次重连。P0 只挡住了"写"，这里补上"读"。
        var resumeMs =
            if (MediaFormats.isLikelyLiveStream(decodedUri)) 0L
            else PlaybackPositions.load(prefs, videoUriStr)
        var resumeApplied = resumeMs <= 0L
        
        // Default dimensions prior to ExoPlayer onVideoSizeChanged callback
        var videoWidth = 1920
        var videoHeight = 1080

        // Bind measurements directly to active GLES renderer standard viewport calculations
        currentGlSurfaceView?.renderer?.let { r ->
            r.videoWidth = videoWidth
            r.videoHeight = videoHeight
        }

        surfaceTexture.setDefaultBufferSize(videoWidth, videoHeight)
        // 审查 #13：释放上一次绑定的 Surface，避免每次切片累积泄漏
        surfaceHolder.getAndSet(null)?.let { runCatching { it.release() } }
        val nativeSurface = Surface(surfaceTexture)
        surfaceHolder.set(nativeSurface)
    // ===================================================================
    // v2.1.233：视频尺寸处理 —— **Exo 与 ijk 共用同一份**
    // 原先这段逻辑整个写在 Exo 的 onVideoSizeChanged 里；接入 ijk 后如果
    // 照抄一份，就会变成「同一个功能两份实现」—— 本项目反复出事的根源。
    // 抽成局部函数后，两个内核都只调它，行为天然一致。
    // ===================================================================
        fun applyVideoSize(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
        try {
            surfaceTexture.setDefaultBufferSize(
                effectiveOutputSize(width, height).first,
                effectiveOutputSize(width, height).second
            )
            currentGlSurfaceView?.renderer?.let { r ->
                r.videoWidth = width
                r.videoHeight = height
            }
            // v2.0.206：同步进 state 供 FSR 默认规则判定与 UI 显示。
            // ⚠️ 只写 renderer 是不会触发重组的 —— 那样 FSR 的判定结果
            //    会永远停在初始的 0x0（「等待视频信息」），开关看起来失灵。
            videoSourceWidth = width
            videoSourceHeight = height
            // v2.1.233：这条日志原本写死 "ExoPlayer onVideoSizeChanged"，但抽成共用函数后
        // **ijk 也会走到这里** —— 实测日志里出现「ExoPlayer onVideoSizeChanged」而实际
        // 跑的是 ijk，会直接把排查带偏。改成带上当前内核名。
        Log.d(
            "VRPlayerScreen",
            "视频尺寸回调[" + decoderEngine.displayName + "]: 更新 SurfaceTexture 缓冲为 " + width + "x" + height
        )
        
            // Smart projection detection: only while the user has not
            // manually chosen a mode AND the feature switch is on. (8/1 功能)
            // 强制视频类型判断（优先于自动检测）：用户手动指定视频类型
            if (forceVideoType != 0) {
                val targetMode: ProjectionMode
                val targetStereo: StereoMode
                // v2.6.9：强制「3D 左右 / 3D 上下」时**不要再把投影锁死成平面**。
                //   用户选这两项表达的是"这是拼接 3D 片源"，与投影是否球面无关；
                //   原来固定 STANDARD，导致上下拼接的全景只能被压扁着看，
                //   想正确观看必须先选 3D 上下、再手动改投影（而且会被自动检测覆盖）。
                //   现在：当前已经是全景（360/180）就保留，否则仍按平面处理（与旧行为一致）。
                val keepPano = projectionMode == ProjectionMode.VR_360 ||
                    projectionMode == ProjectionMode.VR_180
                when (forceVideoType) {
                    1 -> { targetMode = ProjectionMode.STANDARD; targetStereo = StereoMode.MONO }
                    2 -> { targetMode = ProjectionMode.VR_360; targetStereo = StereoMode.MONO }
                    3 -> { targetMode = ProjectionMode.VR_180; targetStereo = StereoMode.MONO }
                    4 -> {
                        targetMode = if (keepPano) projectionMode else ProjectionMode.STANDARD
                        targetStereo = StereoMode.SBS
                    }
                    5 -> {
                        targetMode = if (keepPano) projectionMode else ProjectionMode.STANDARD
                        targetStereo = StereoMode.TAB
                    }
                    else -> { targetMode = ProjectionMode.STANDARD; targetStereo = StereoMode.MONO }
                }
                if (projectionMode != targetMode || stereoMode != targetStereo) {
                    projectionMode = targetMode
                    stereoMode = targetStereo
                    currentGlSurfaceView?.renderer?.warpDualCenter =
                        forceVideoType == 2 && (targetMode == ProjectionMode.VR_360)
                    projectionModeUserAdjusted = true // 强制锁定，防止自动检测覆盖
                    Toast.makeText(
                        context,
                        "已强制切换为 ${context.getString(targetMode.labelRes)}${if (targetStereo != StereoMode.MONO) " + ${context.getString(targetStereo.labelRes)}" else ""}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else if (isSmartProjectionEnabled && !projectionModeUserAdjusted) {
                // v2.6.8：判定规则抽到 ProjectionAutoDetector（可单测、阈值单一来源）。
                //   ① 360 窗口由 1.80~2.20 收窄到 1.95~2.05 —— 原窗口把 1.85:1 与 2.2:1
                //      的**普通电影**也判成全景，画面被塞进球面投影严重变形；
                //   ② 新增 4:1 → 360 + SBS（左右立体 3D 全景），此前这类片源会被判成
                //      平面并清掉立体模式，等于完全不可用；
                //   ③ 尺寸无效（解码器初始回调 0x0）返回 null，不套用任何结论；
                //   ④ v2.6.9：扩充平面 3D 左右 (Full-SBS/HSBS) 与上下 (TAB) 识别，自动联动分屏 VR。
                val mediaTitle = selectedMediaItem.title ?: selectedMediaItem.uri ?: ""
                val suggestion = ProjectionAutoDetector.detect(width, height, mediaTitle)
                val needsApply = suggestion != null && when (suggestion.reason) {
                    // 平面：只在投影模式不对时动手；立体复位交给下面那段独立的逻辑，
                    // 避免同一判断在两处各写一遍（那样很容易只改一处）。
                    ProjectionAutoDetector.Reason.FLAT ->
                        projectionMode != ProjectionMode.STANDARD

                    else -> projectionMode != suggestion.mode || stereoMode != suggestion.stereo
                }
                if (suggestion != null && needsApply) {
                    projectionMode = suggestion.mode
                    stereoMode = suggestion.stereo
                    currentGlSurfaceView?.renderer?.warpDualCenter = suggestion.warpDualCenter
                    // 识别到左右立体片源时，自动联动打开左右分屏 VR
                    if (suggestion.reason == ProjectionAutoDetector.Reason.PLANAR_3D_SBS ||
                        suggestion.reason == ProjectionAutoDetector.Reason.EQUIRECT_360_SBS
                    ) {
                        isSplitScreenVR = true
                    }
                    val toastRes = when (suggestion.reason) {
                        ProjectionAutoDetector.Reason.EQUIRECT_360 -> R.string.toast_detected_360
                        ProjectionAutoDetector.Reason.EQUIRECT_360_SBS -> R.string.toast_detected_360_sbs
                        // v2.6.9：上下拼接全景（1:1，上下各一张 2:1）
                        ProjectionAutoDetector.Reason.EQUIRECT_360_TAB -> R.string.toast_detected_360_tab
                        // v2.6.9：平面 3D 左右与上下
                        ProjectionAutoDetector.Reason.PLANAR_3D_SBS -> R.string.toast_detected_planar_sbs
                        ProjectionAutoDetector.Reason.PLANAR_3D_TAB -> R.string.toast_detected_planar_tab
                        ProjectionAutoDetector.Reason.FLAT -> R.string.toast_detected_2d
                    }
                    Toast.makeText(context, context.getString(toastRes), Toast.LENGTH_SHORT).show()
                }
            }

            // Independent stereo reset: a leftover SBS/TAB mode on a plain
            // 2D video renders only half the frame stretched full-screen.
            // 3D framing only makes sense on 3D sources, so we always fall
            // back to mono for ordinary aspect videos in planar projection.
            // v2.6.8：阈值改用 ProjectionAutoDetector.isEquirect 单一来源 ——
            //   原先这里和上面的检测各写一份 `1.80f..2.20f`，收窄窗口时极容易出现
            //   「检测按 1.95~2.05 判 2D、这里却按 1.80~2.20 保留 SBS」的自相矛盾。
            // v2.6.9：当片源为平面 3D（Full-SBS 或文件名带 SBS/TAB）或用户手动调整过立体模式时，禁止误重置为单目！
            val vidAspect = width.toFloat() / height.toFloat()
            val isPlanar = projectionMode == ProjectionMode.STANDARD ||
                projectionMode == ProjectionMode.FISHEYE
            val curTitle = selectedMediaItem.title ?: selectedMediaItem.uri ?: ""
            val isSbsSource = ProjectionAutoDetector.isSbsPlanar(vidAspect) || ProjectionAutoDetector.isSbsName(curTitle)
            val isTabSource = ProjectionAutoDetector.isTabName(curTitle)
            if (isSmartProjectionEnabled && isPlanar &&
                !projectionModeUserAdjusted &&
                !isSbsSource && !isTabSource &&
                !ProjectionAutoDetector.isEquirect(vidAspect) && stereoMode != StereoMode.MONO
            ) {
                stereoMode = StereoMode.MONO
                Toast.makeText(context, context.getString(R.string.toast_2d_mono), Toast.LENGTH_SHORT).show()
            }
        
            // Detect ultra high resolution (like 8K or exceeds user set resolution limit)
            if (width > maxResolution.width || height > maxResolution.height) {
                resolutionTipText = context.getString(R.string.toast_res_exceeds, width, height)
                showResolutionTip = true
            } else if (width >= 7680 || height >= 4320) {
                resolutionTipText = context.getString(R.string.toast_8k_hint, width, height)
                showResolutionTip = true
            } else {
                showResolutionTip = false
            }

            // 8K 硬解：视频超出硬件解码标称上限时，重封装并改写 SPS level
            // （实验性，默认关闭，levelPatchEnabled / spoofResolutionEnabled）
            if ((levelPatchEnabled || spoofResolutionEnabled) &&
                !isTranscoding && !isRemuxing && (width >= 7680 || height >= 4320)
            ) {
                val cap = DecoderCapabilities.getBestHardwareDecoderMax()
                if (cap != null && (width > cap.width || height > cap.height)) {
                    resolutionTipText = context.getString(R.string.toast_8k_patching, width, height)
                    showResolutionTip = true
                    startLevelPatchFix()
                }
            }
        } catch (e: Exception) {
            Log.e("VRPlayerScreen", "Error setting SurfaceTexture buffer size to ${width}x${height}", e)
        }
    }


    // ======================================================================
    // v2.1.241：容器感知的**内核自动路由**（WMV / ASF / ISO）
    //
    // 背景：WMV(ASF) 与 ISO 镜像 EXO 与系统解码都接不住（EXO 的
    // DefaultExtractorsFactory 没有 ASF 解析器；MediaPlayer 同理；
    // 两者也都不认 UDF 文件系统），只有 IJK 的 FFmpeg 能解 ——
    // 已在 libijkplayer.so 里实测到 wmv3/vc1/wmav2/wmapro/ff_asf_demuxer/udf。
    //
    // 若不自动路由，用户选了 EXO 打开一个 .wmv，会得到 ExoPlaybackException
    // 然后**被现有的错误处理静默吞掉** —— 表现就是「点了没反应/黑屏」，
    // 完全无从判断是片源问题还是应用问题。
    //
    // ⚠️ 刻意**不写回 prefs**：只改这一次播放用的 `effectiveEngine`。
    //    用户的「解码器」设置保持原样 —— 否则打开一个 WMV 就永久改成 IJK，
    //    下次播普通 MP4 也走 FFmpeg 软解，白掉性能（用户会以为是 bug）。
    // ======================================================================
    // ⚠️ v2.4.19：相册返回的 `content://media/...` URI **没有扩展名** → 下面的内核
    //    路由（AVI/WMV→MPV）会整体失效（用户表现为「改了路由没生效」）。
    //    用选择时记下的 MIME 兜底；两条都没有才返回空串。
    val containerExt = MediaFormats.extensionOf(decodedUri)
        .ifEmpty { MediaFormats.extFromMime(selectedMediaItem.mimeType) }
    var effectiveEngine = decoderEngine
    // ======================================================================
    // v2.6.13：**该文件超出硬件解码上限 → 本次改用软解内核**
    // ----------------------------------------------------------------------
    // 判据与置位都在 Exo 的 onPlayerError（解码器初始化/解码失败 → 探测真实尺寸 →
    //   DecoderCapabilities.findHardwareFit 确认没有硬解能吃），这里只负责消费：
    //   ① IJK 优先：内置 FFmpeg 软解，不需要额外下载，8K 也能出画面；
    //   ② IJK 不可用（native 库缺失）→ MPV（若已安装）；
    //   ③ 两者都没有 → 保持原内核（外层原有错误提示仍在，不会静默）。
    // ⚠️ 与 mpvRetryUri 同理：只改本次 `effectiveEngine`，**不写回 prefs**。
    // ======================================================================
    if (softRetryUri != null && softRetryUri == selectedMediaItem.uri &&
        decoderEngine != DecoderEngine.IJK && decoderEngine != DecoderEngine.MPV
    ) {
        effectiveEngine = when {
            // supports(uri) 也要判：SMB 等 FFmpeg 协议集外的源切过去只会静默回退 Exo
            IjkPlayerFactory.supports(decodedUri) && IjkPlayerFactory.ensureLibraries() ->
                DecoderEngine.IJK
            MpvPlayerFactory.isAvailable(context) -> DecoderEngine.MPV
            else -> decoderEngine
        }
        Log.i(
            "VRPlayerScreen",
            "该片超出本机硬件解码上限 → 本次改用软解内核 $effectiveEngine"
        )
    }
    // ======================================================================
    // v2.4.32：**该文件 Exo 打开失败过 → 本次直接用 MPV**（只对这一个文件、只重试一次）
    // ----------------------------------------------------------------------
    // 与下方容器路由同为「只改 effectiveEngine、不写回 prefs」：用户的解码器设置不变。
    // 置位见 Exo 的 `onPlayerError`（换软/硬解救不了的**解析类**失败）。
    if (mpvRetryUri != null && mpvRetryUri == selectedMediaItem.uri &&
        decoderEngine != DecoderEngine.MPV
    ) {
        effectiveEngine = DecoderEngine.MPV
        Log.i("VRPlayerScreen", "该文件 Exo 打开失败过 → 本次改用 MPV（顺带确认 vo 预判）")
    }
    // ======================================================================
    // v2.1.244：**预判式选 vo** —— MPV 的视频输出模式
    // ----------------------------------------------------------------------
    // 背景：EMBED（mediacodec_embed）只吃硬件帧格式（mpv 源码 query_format 只认
    //   IMGFMT_MEDIACODEC），而 WMV/ASF/RM/RMVB 这类老容器**几乎必然没有硬解器**
    //   → mpv 退回软解输出 yuv420p → vo 拒收 → END_FILE 失败。
    // 做法：**在创建播放器之前**就按容器选好 vo，而不是等失败后重建。
    //
    // ⚠️⚠️ 为什么不能「失败后重建」（第一版方案，实测直接崩）
    //   MPV 是**全局单例**（`is.xyz.mpv.MPVLib` 是 Kotlin object，所有调用打到同一份
    //   native context）。失败后重建会再次调 `MPVLib.create()` + `init()`，
    //   mpv 断言 `../player/main.c:347: assertion "!mpctx->initialized" failed`
    //   → **SIGABRT 直接崩在 libmpv.so**（实测 tombstone 已确认）。
    //   因此本方案**只改一次、不重建**。
    // ======================================================================
    // 判据：**可能没有硬解器**的容器 → 直接给 GPU 模式（软硬解都能出画）。
    //   ⚠️ v2.4.19：改用 `prefersMpvGpuVo` 而**不是** `shouldRouteToMpv` —— 后者
    //   现在含 AVI（路由判据），而这里是 **vo 判据**：AVI 里常见的 Xvid/DivX
    //   （MPEG-4 Part 2）在多数新机已无硬解器，用 `mediacodec_embed` 会因
    //   「只吃硬件帧」而拒收软解帧 → 黑屏。两个概念必须分开，见方法注释。
    //   代价：硬解路径多一次 GPU 拷贝；但这些容器本来就走软解，没有损失。
    if (MediaFormats.prefersMpvGpuVo(containerExt)) {
        if (mpvVoMode != MpvVoMode.GPU) mpvVoMode = MpvVoMode.GPU
        Log.i("VRPlayerScreen", "容器 .$containerExt 可能无硬解器，MPV 采用 vo=gpu（软硬解均可出画）")
    } else if (mpvVoMode != MpvVoMode.EMBED) {
        // 其它格式（含用户手动选 MPV 播 mp4 等）用 EMBED —— 零拷贝，真机最优
        mpvVoMode = MpvVoMode.EMBED
    }
    // ⚠️ v2.1.243：先判 MPV，再判 IJK。顺序不能反 ——
    //    wmv/rm 这类格式**两边都可能被 shouldXxx 命中**（requiresMpv 与
    //    shouldAutoRouteToIjk 的集合都是 IJK_ONLY 的子集），但只有 MPV 能真正解，
    //    所以必须先给 MPV 优先权。
    if (MediaFormats.shouldRouteToMpv(containerExt) && decoderEngine != DecoderEngine.MPV) {
        // SMB 例外：MPV 有没有 smb 协议取决于 libmpv 的 protocol 白名单，
        // 本项目未验证 → 与 IJK 一样不做自动切换，直接提示。
        val scheme = decodedUri.scheme?.lowercase()
        if (scheme == "smb") {
            Log.w("VRPlayerScreen", "SMB 上的 $containerExt 无内核可稳定解（MPV 的 smb 未验证），按原内核尝试")
        } else if (MpvPlayerFactory.isAvailable(context)) {
            effectiveEngine = DecoderEngine.MPV
            // ⚠️ v2.4.19：**只有「严格需 MPV」的容器才 Toast 告知**
            //    （WMV/ASF/RM/RMVB/ivf —— EXO/IJK 根本解不了，用户需要知道换了核）。
            //    AVI 属于「默认走 MPV」而非「必须」：EXO 也能开，只是无 `idx1` 时 seek
            //    要靠重封装补救。每次都弹「需完整 FFmpeg」既不准确又烦人 → **静默切换**。
            if (MediaFormats.requiresMpv(containerExt)) {
                Log.i("VRPlayerScreen", "容器 .$containerExt 需完整 FFmpeg，本次自动改用 MPV 内核")
                postToast(context.getString(R.string.toast_auto_switch_mpv, containerExt.uppercase()))
            } else {
                Log.i("VRPlayerScreen", "容器 .$containerExt 默认走 MPV（无索引亦可 seek），本次自动切换（静默）")
            }
        } else {
            // MPV 未装（native 库需按需下载）→ 明确提示去哪装，而不是静默走 EXO（必然也失败）
            Log.w("VRPlayerScreen", "容器 .$containerExt 需 MPV 但 native 库未安装，提示用户")
            postToast(context.getString(R.string.toast_mpv_need_download))
        }
    }
    // 用 shouldAutoRouteToIjk 而非 requiresIjk：后者是「只有 IJK 能解」，
    // 前者额外排除 EXO_ONLY（avi/ogv 这类 IJK 反而解不了的）——
    // 防止将来有人往 IJK_ONLY 里误加 avi 时把能播的格式路由成不能播。
    if (effectiveEngine != DecoderEngine.MPV &&
        MediaFormats.shouldAutoRouteToIjk(containerExt) && decoderEngine != DecoderEngine.IJK
    ) {
        // 唯一的例外：SMB 上的这类片源**无解**（IJK 没有 smb 协议，
        // 回退 EXO 依然不认 ASF）→ 不做无谓的自动切换，直接提示。
        val scheme = decodedUri.scheme?.lowercase()
        if (scheme == "smb") {
            Log.w("VRPlayerScreen", "SMB 上的 $containerExt 无内核可解（IJK 无 smb 协议），按原内核尝试")
        } else if (IjkPlayerFactory.ensureLibraries() && IjkPlayerFactory.supports(decodedUri)) {
            effectiveEngine = DecoderEngine.IJK
            Log.i("VRPlayerScreen", "容器 .$containerExt 需 FFmpeg，本次自动改用 IJK 内核")
            postToast(context.getString(R.string.toast_auto_switch_ijk, containerExt.uppercase()))
        } else {
            Log.w(
                "VRPlayerScreen",
                "容器 .$containerExt 需 FFmpeg 但 IJK 不可用（${IjkPlayerFactory.lastError}），按原内核尝试"
            )
            postToast(context.getString(R.string.toast_need_ffmpeg_engine, containerExt.uppercase()))
        }
    }

    // ======================================================================
    // v2.1.233：IJK（FFmpeg 内核）分支
    //
    // 放在 Exo 的 renderersFactory 之前：选中 IJK 时**根本不创建 ExoPlayer**，
    // 省掉一整套 MediaCodec 资源的无谓开销。
    //
    // 四类「接不住」的情况全部**回退 Exo**，绝不黑屏：
    //   ① native 库没加载上（ABI 不匹配 / 分包丢 .so）
    //   ② 片源协议 ijk 吃不下（smb:// 等不在 FFmpeg 协议集里）
    //   ③ create 内部抛异常（数据源打不开）
    //   ④ prepare 之后才暴露的错误（编码不支持等）→ 走 onError 回调
    // ①②③ 在这里静默回退（只 Log，不打扰用户）；
    // ④ 会 Toast 并把内核切回 EXO（用户知情，且下次不再踩同一个坑）。
    // ======================================================================
    // ======================================================================
    // v2.6.24（直播 P1）：**直播流强制 EXO**
    // ----------------------------------------------------------------------
    // 为什么直播必须走 EXO：
    //   ① HLS/DASH 的多分片清单只有 EXO 的 HlsMediaSource/DashMediaSource 能正确
    //      跟踪滑动窗口（IJK 的裁剪版 FFmpeg 根本没编 hls demuxer，MPV 虽有 FFmpeg
    //      但 vo=mediacodec_embed 下切码率/重连会黑屏）；
    //   ② 权重的直播判定（`VrPlayerBackend.isLive`）本身就依赖 EXO 的
    //      `Timeline.Window.isLive` —— 换内核后 UI 的 LIVE 态也会失准。
    // 判据有两条，命中任一即强制：
    //   ① 打开前的 URL 启发式（MediaFormats.isLikelyLiveStream）；
    //   ② 运行时兜底（liveForceExoUri）—— 播放器已自证是直播，但 URL 看不出来。
    // ⚠️ 两个 retry 例外必须绕开：Exo 打不开该流时，`mpvRetryUri` / `softRetryUri`
    //    已经把内核指到 MPV/IJK，这里再强制回 EXO 就成了"打开失败 → 切 MPV →
    //    又切回 EXO"的死循环（每轮都要重建播放器）。
    // ======================================================================
    val liveUrlGuess = MediaFormats.isLikelyLiveStream(decodedUri)
    val liveRuntimeForce = liveForceExoUri != null && liveForceExoUri == selectedMediaItem.uri
    if ((liveUrlGuess || liveRuntimeForce) && effectiveEngine != DecoderEngine.EXO &&
        mpvRetryUri != selectedMediaItem.uri && softRetryUri != selectedMediaItem.uri
    ) {
        Log.i(
            "VRPlayerScreen",
            "直播流（${if (liveUrlGuess) "URL 判据" else "运行时确认"}）→ 强制使用 EXO 内核（原 $effectiveEngine）"
        )
        effectiveEngine = DecoderEngine.EXO
    }

    // ======================================================================
    // v2.8.3：**该文件在当前内核上播失败过 → 本次强制 EXO**
    // ----------------------------------------------------------------------
    // 放在**全部自动路由之后**：容器路由（AVI/WMV→MPV、超硬件上限→IJK 等）在上面已经
    // 执行完毕，这里最后覆盖才真的回退成功；否则会「设成 EXO 又被路由回 MPV」。
    // 只认「本次这一个文件」，且**不改 `decoderEngine`、不写 prefs**（见 state 注释）。
    // 与直播强制 EXO 不冲突：两者目标都是 EXO，先到先得。
    // ======================================================================
    if (exoRetryUri != null && exoRetryUri == selectedMediaItem.uri &&
        effectiveEngine != DecoderEngine.EXO
    ) {
        Log.i(
            "VRPlayerScreen",
            "该文件在 $effectiveEngine 上播放失败过 → 本次回退 EXO（用户设置不变）"
        )
        effectiveEngine = DecoderEngine.EXO
    }

    if (effectiveEngine == DecoderEngine.IJK) {
        val ijkUri = decodedUri
        val ijkUsable = IjkPlayerFactory.supports(ijkUri) && IjkPlayerFactory.ensureLibraries()
        if (ijkUsable) {
            // v2.8.4：若因超出硬件硬解上限（如 8K 全景）走软解回退，强制关闭 MediaCodec 硬解，开启快速解码与主动丢帧
            val isOversizeSoft = softRetryUri != null && softRetryUri == selectedMediaItem.uri
            val effectiveIjkOptions = if (isOversizeSoft) {
                ijkOptions.copy(mediaCodec = false, frameDrop = true, fastDecode = true)
            } else {
                ijkOptions
            }
            val backend = IjkPlayerFactory.create(
                context = context,
                uri = ijkUri,
                surface = nativeSurface,
                options = effectiveIjkOptions,
                callbacks = object : IjkPlayerFactory.Callbacks {
                    override fun onVideoSizeChanged(width: Int, height: Int) {
                        // 与 Exo 侧共用同一份尺寸处理（智能投影检测 / 8K 提示 / 缓冲尺寸）
                        applyVideoSize(width, height)
                    }

                    override fun onPrepared(backend: VrPlayerBackend) {
                        // 倍速要在 start 之前设好 —— 与 Exo 侧「prepare 后、播放前」同一时机
                        backend.setPlaybackSpeed(
                            if (isFloatingBallPressed) floatingBallSpeed else basePlaybackSpeed
                        )
                        // 位置恢复：同 Exo 侧，duration 到这一步才真正就绪
                        if (!resumeApplied) {
                            resumeApplied = true
                            val dur = backend.duration
                            if (PlaybackPositions.shouldResume(resumeMs, dur)) {
                                backend.seekTo(resumeMs)
                                Log.i("VRPlayerScreen", "IJK 恢复上次播放位置 " + resumeMs + "ms / 总长 " + dur + "ms")
                            }
                        }
                        backend.play()
                        isVideoPlaying = true
                        playbackEnded = false
                    }

                    override fun onCompletion() {
                        // v2.6.20：播完把播放态归位。否则 isVideoPlaying 恒为 true，
                        // 1 秒进度轮询每秒把进度写回 current/duration = 1.0，
                        // 用户拖动进度条会被轮询立即弹回片尾 ——
                        // 「播完后进度条拖不动」的直接根因就是它。
                        isVideoPlaying = false
                        playbackEnded = true
                        PlaybackPositions.clear(prefs, videoUriStr)
                        resumeMs = 0L
                    }

                    override fun onError(what: Int, extra: Int) {
                        // ⚠️ v2.1.243：降级目标不再硬编码 EXO。
                        //    对 wmv/rm 这类「只有 MPV 能解」的格式，回退 EXO 是**必然再失败**
                        //    一次（EXO 无 ASF/RealMedia 解析器），用户会看到"转一圈还是黑屏"。
                        //    改为：需要 MPV 且 MPV 可用 → 降级 MPV；否则才回退 EXO。
                        val ext = MediaFormats.extensionOf(decodedUri)
                        if (MediaFormats.requiresMpv(ext) && MpvPlayerFactory.isAvailable(context)) {
                            Log.e(
                                "VRPlayerScreen",
                                "IJK 播放错误 what=$what extra=$extra -> .$ext 需完整 FFmpeg，降级 MPV"
                            )
                            // ⚠️ 不能在这里直接调 setupVideoPlayer（会递归）。
                            //    改 decoderEngine 会触发 Effect B 重建播放器；
                            //    这里**写回 prefs** 是刻意的 —— wmv/rm 在本项目就该走 MPV，
                            //    下次打开不必再"IJK 失败一次再降级"。
                            decoderEngine = DecoderEngine.MPV
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_ijk_fallback_mpv),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else if (MediaFormats.requiresMpv(ext)) {
                            // 需要 MPV 但库没装 → 提示去装，不回退 EXO（EXO 必然也失败）
                            Log.e(
                                "VRPlayerScreen",
                                "IJK 播放错误 what=$what extra=$extra；.$ext 需 MPV 但 native 库未装"
                            )
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_mpv_need_download),
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Log.e("VRPlayerScreen", "IJK 播放错误 what=$what extra=$extra -> 本次回退 EXO")
                            // v2.8.3：只标「本次这一个文件」，**不再写回 prefs**。
                            // 原来改 decoderEngine 会经持久化把用户的内核永久改成 EXO ——
                            // 播一个 IJK 解不了的片子，就让之后所有片子都变 EXO。
                            exoRetryUri = selectedMediaItem.uri
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_ijk_fallback),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }

                    override fun onFirstFrame() {
                        isVideoPlaying = true
                        playbackEnded = false
                    }
                }
            )
            if (backend != null) {
                playerInstance = backend
                Log.i("VRPlayerScreen", "已使用 IJK 内核播放")
                return
            }
            Log.w("VRPlayerScreen", "IJK 创建失败，回退 EXO")
        } else {
            Log.w(
                "VRPlayerScreen",
                "IJK 不可用（协议不支持或 native 库缺失: " + IjkPlayerFactory.lastError + "），回退 EXO"
            )
        }
    }

    // ======================================================================
    // v2.1.234：MPV 内核（libmpv / FFmpeg）
    // ----------------------------------------------------------------------
    // 与 IJK 分支同样的"绝不黑屏"策略，两类失败静默回退 Exo：
    //   ① libmpv native 库不可用（ABI 不匹配 / 分包丢 .so）
    //   ② create 抛异常（数据源打不开）
    // ③ prepare 后暴露的错误（解码失败、END_FILE 非正常结束）
    //    → 走 onError 回调，Toast 并把内核切回 EXO（不在这里递归调用 setupVideoPlayer）
    // ======================================================================
    // ⚠️ v2.1.241：用 effectiveEngine（不是 decoderEngine）—— MPV_ENABLED=false 时
    //    这段本来就走不到；但一旦恢复 MPV 开关，必须让「容器自动路由」也能把
    //    WMV/ISO 派给 MPV 而不是被 decoderEngine 挡住。
    if (effectiveEngine == DecoderEngine.MPV) {
        if (MpvPlayerFactory.isAvailable(context)) {
            // v2.8.4：若因超出硬件硬解上限走软解回退，强制关硬解、切 GPU vo 渲染、开启快速解码
            val isOversizeSoft = softRetryUri != null && softRetryUri == selectedMediaItem.uri
            val effectiveVoMode = if (isOversizeSoft) MpvVoMode.GPU else mpvVoMode
            val backend = MpvPlayerFactory.create(
                context = context,
                uri = decodedUri,
                surface = nativeSurface,
                // v2.1.244：把兜底选定的 voMode 合并进 options（EMBED / GPU）
                options = mpvOptions.copy(
                    hwdec = if (isOversizeSoft) false else mpvOptions.hwdec,
                    voMode = effectiveVoMode,
                    frameDrop = if (isOversizeSoft) true else mpvOptions.frameDrop,
                    fastDecode = if (isOversizeSoft) true else mpvOptions.fastDecode,
                    // 🔴 v2.9.1：网页/URL 播放时，把 Exo 用的**同一份** UA/Referer 也交给 mpv。
                    //   不给的话，Exo 失败回退到 MPV 就是裸请求（mpv 默认 UA = libmpv）→ CDN 403
                    //   → 用户看到"换内核还是打不开"。本地文件播放时这里是空列表，行为不变。
                    //   ⚠️ 只给 UA/Referer，**不给 Range**（会破坏 mpv 的 seek），见方法注释。
                    httpHeaders = HttpPlaybackHeaders.asMpvHeaderFields()
                ),
                callbacks = object : MpvPlayerFactory.Callbacks {
                    override fun onVideoSizeChanged(width: Int, height: Int) {
                        // 与 Exo / IJK 共用同一份尺寸处理（智能投影检测 / 8K 提示 / 缓冲尺寸）
                        applyVideoSize(width, height)
                    }

                    override fun onPrepared(backend: VrPlayerBackend) {
                        // 倍速要在 play 之前设好 —— 与 Exo / IJK 同一时机
                        backend.setPlaybackSpeed(
                            if (isFloatingBallPressed) floatingBallSpeed else basePlaybackSpeed
                        )
                        if (!resumeApplied) {
                            resumeApplied = true
                            val dur = backend.duration
                            if (PlaybackPositions.shouldResume(resumeMs, dur)) {
                                backend.seekTo(resumeMs)
                                Log.i("VRPlayerScreen", "MPV 恢复上次播放位置 " + resumeMs + "ms / 总长 " + dur + "ms")
                            }
                        }
                        backend.play()
                        isVideoPlaying = true
                        playbackEnded = false
                    }

                    override fun onCompletion() {
                        // v2.6.20：播完把播放态归位。否则 isVideoPlaying 恒为 true，
                        // 1 秒进度轮询每秒把进度写回 current/duration = 1.0，
                        // 用户拖动进度条会被轮询立即弹回片尾 ——
                        // 「播完后进度条拖不动」的直接根因就是它。
                        isVideoPlaying = false
                        playbackEnded = true
                        PlaybackPositions.clear(prefs, videoUriStr)
                        resumeMs = 0L
                    }

                    override fun onError(reason: String) {
                        val uriNow = selectedMediaItem.uri
                        val isAuthFailure = reason.contains("403") || reason.contains("410") ||
                            reason.contains("forbidden", ignoreCase = true) ||
                            (HttpPlaybackHeaders.isActive && reason.contains("loading failed", ignoreCase = true))

                        if (isAuthFailure && HttpPlaybackHeaders.isActive &&
                            uriNow != null && cookieRetryAttemptedUri != uriNow
                        ) {
                            cookieRetryAttemptedUri = uriNow
                            val refreshed = HttpPlaybackHeaders.refreshCookie(uriNow)
                            Log.w(
                                "VRPlayerScreen",
                                "MPV 网页流鉴权失败: $reason（疑似 Cookie 过期）→ 重新获取Cookie(成功=$refreshed)，尝试重试续播"
                            )
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_cookie_refreshed_retry),
                                Toast.LENGTH_SHORT
                            ).show()
                            cookieRetryTrigger++
                            return
                        }

                        // v2.1.244：**这里不能重建播放器**（第一版方案，实测直接崩）。
                        //   MPV 是全局单例，重建会再次调 MPVLib.create()+init()，而旧
                        //   context 尚未销毁 → mpv 断言 `!mpctx->initialized` 失败 →
                        //   SIGABRT 崩在 libmpv.so。vo 模式改为**创建前预判**
                        //   （见 setupVideoPlayer 里的「预判式选 vo」），失败即回退。
                        Log.e("VRPlayerScreen", "MPV 播放错误: $reason -> 本次回退 EXO")
                        // ⚠️ 不能在这里直接调 setupVideoPlayer（会递归）。
                        //    v2.8.3：只置「本次这一个文件」的标记 → 同样触发重建，
                        //    但**不再写回 prefs**：原来改 decoderEngine 会把用户的内核
                        //    永久改成 EXO（一次失败污染之后所有播放）。
                        exoRetryUri = selectedMediaItem.uri
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_mpv_fallback),
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    override fun onFirstFrame() {
                        isVideoPlaying = true
                        playbackEnded = false
                    }
                }
            )
            if (backend != null) {
                playerInstance = backend
                Log.i("VRPlayerScreen", "已使用 MPV 内核播放")
                return
            }
            Log.w("VRPlayerScreen", "MPV 创建失败，回退 EXO")
        } else {
            Log.w("VRPlayerScreen", "libmpv 不可用（解码库未安装或加载失败），回退 EXO")
            // v2.1.235：MPV 的 native 库改为后下载 —— 未安装时要明确告诉用户
            // 去哪里装（否则表现为"选了 MPV 却还是 Exo，没有任何提示"，很像 bug）
            Toast.makeText(
                context,
                context.getString(R.string.toast_mpv_need_download),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // ======================================================================
    // v2.1.240：**系统解码**（Android Framework 的 MediaPlayer）
    // ----------------------------------------------------------------------
    // 与 Exo/IJK/MPV 的本质区别：那三个都是"应用自带一套解码栈"，
    // 而系统解码走的是**厂商 ROM 自己的解码管线** —— 国产 ROM 常在其中集成
    // 私有增强解码器（自研格式、更激进的功耗控制）。所以它是一条**独立兜底路径**：
    // 同一片源在 Exo/IJK 下异常时，值得切过来试一次。
    //
    // 三层回退全部指向 EXO（与 IJK 同一套机制，绝不黑屏）：
    //   ① 协议不支持（smb://）→ supports() 返回 false，根本不进这里；
    //   ② create() 返回 null（MediaPlayer 建不起来/设数据源抛异常）→ 回退；
    //   ③ prepare 或播放中出错 → onError 回调 → 切回 EXO + Toast 提示。
    // ======================================================================
    if (effectiveEngine == DecoderEngine.SYSTEM) {
        if (SystemPlayerFactory.supports(decodedUri)) {
            val backend = SystemPlayerFactory.create(
                context = context,
                uri = decodedUri,
                surface = nativeSurface,
                callbacks = object : SystemPlayerFactory.Callbacks {
                    override fun onVideoSizeChanged(width: Int, height: Int) {
                        // 与 Exo / IJK / MPV 共用同一份尺寸处理（智能投影检测 / 8K 提示 / 缓冲尺寸）
                        applyVideoSize(width, height)
                    }

                    override fun onPrepared(backend: VrPlayerBackend) {
                        // 倍速要在 play 之前设好 —— 与其余内核同一时机
                        // （SystemBackend 内部会把 prepared 之前设过的倍速补应用一次）
                        backend.setPlaybackSpeed(
                            if (isFloatingBallPressed) floatingBallSpeed else basePlaybackSpeed
                        )
                        if (!resumeApplied) {
                            resumeApplied = true
                            val dur = backend.duration
                            if (PlaybackPositions.shouldResume(resumeMs, dur)) {
                                backend.seekTo(resumeMs)
                                Log.i("VRPlayerScreen", "系统解码 恢复上次播放位置 " + resumeMs + "ms / 总长 " + dur + "ms")
                            }
                        }
                        backend.play()
                        isVideoPlaying = true
                        playbackEnded = false
                    }

                    override fun onCompletion() {
                        // v2.6.20：播完把播放态归位。否则 isVideoPlaying 恒为 true，
                        // 1 秒进度轮询每秒把进度写回 current/duration = 1.0，
                        // 用户拖动进度条会被轮询立即弹回片尾 ——
                        // 「播完后进度条拖不动」的直接根因就是它。
                        isVideoPlaying = false
                        playbackEnded = true
                        PlaybackPositions.clear(prefs, videoUriStr)
                        resumeMs = 0L
                    }

                    override fun onError(what: Int, extra: Int) {
                        Log.e("VRPlayerScreen", "系统解码播放错误 what=" + what + " extra=" + extra + " -> 本次回退 EXO")
                        // ⚠️ 不能在这里直接调 setupVideoPlayer（会递归）。
                        //    v2.8.3：同样只标本次，**不写回 prefs**（理由见 exoRetryUri 注释）。
                        exoRetryUri = selectedMediaItem.uri
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_system_fallback),
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    override fun onFirstFrame() {
                        isVideoPlaying = true
                        playbackEnded = false
                    }
                }
            )
            if (backend != null) {
                playerInstance = backend
                Log.i("VRPlayerScreen", "已使用系统解码播放")
                return
            }
            Log.w("VRPlayerScreen", "系统解码创建失败，回退 EXO")
        } else {
            Log.w("VRPlayerScreen", "系统解码不支持该协议（scheme=${decodedUri.scheme}），回退 EXO")
        }
    }

        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink? {
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(audioProcessor, teeAudioProcessor, genderFilterProcessor))
                    .build()
            }

            // E. Inject extra MediaFormat keys (e.g. larger input buffer) into
            // every video decoder configuration. (8/2-8/3 8K 硬解)
            override fun getCodecAdapterFactory(): androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Factory {
                val inner = androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory()
                return if (addCodecParamsEnabled) {
                    ExperimentalDecode.ParamsAddingMediaCodecAdapterFactory(inner)
                } else {
                    inner
                }
            }
        }.apply {
            setEnableDecoderFallback(true)
            // A. Forced hardware decoder selection: skip platform capability
            // filtering and let every hardware driver try the stream. (8/2-8/3)
            if (forceHwDecoderEnabled && !isSoftwareDecoding) {
                setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                setMediaCodecSelector(ExperimentalDecode.ForcedHardwareMediaCodecSelector)
            } else if (isSoftwareDecoding) {
                setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                    val decoders = androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
                        .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                    val swDecoders = decoders.filter { 
                        it.softwareOnly || it.name.contains("google", ignoreCase = true) || it.name.contains("c2.android", ignoreCase = true)
                    }
                    if (swDecoders.isNotEmpty()) swDecoders else decoders
                }
            } else {
                setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                    val decoders = androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
                        .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                    val hwDecoders = decoders.filter { 
                        !it.softwareOnly && !it.name.startsWith("OMX.google.", ignoreCase = true)
                    }
                    if (hwDecoders.isNotEmpty()) hwDecoders else decoders
                }
            }
        }
        // v2.0.139: DefaultDataSource 只处理 file/asset/content，其余 scheme 落到 base。
        // MT 等文件管理器把 FTP/SMB 远程文件经本地回环 HTTP 代理（http://127.0.0.1:port/...）
        // 交给播放器，故 base 按 scheme 分流：smb:// → jcifs，其余 → DefaultHttpDataSource。
        //
        // 🔴 v2.6.5（关键修正）：**不再用 DefaultDataSource 包一层**。
        //    那层对 http(s) 会自建 `DefaultHttpDataSource`，而本 Media3 版本的双参构造
        //    **不给它设 UA**（实测：无 UA → CDN 直接 403；带任意 UA → 206），
        //    且该版本的 `DefaultDataSource.Factory` 没有 `setHttpDataSourceFactory` 可注入。
        //    故改为由 `SchemeRoutingDataSource` 全权分流：rtmp / smb / 本地资源 / http
        //    （http 分支按 HttpPlaybackHeaders 带 UA + Referer）。
        val smbAwareFactory = SchemeRoutingDataSource.Factory(context)
        val exo = ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(
                // ⚠️ v2.4.14：传入 `AviExtractorsFactory` —— 用**改进版 AVI extractor**
                //    **替换**官方那个（`DefaultMediaSourceFactory` 的第二个参数就是
                //    `ExtractorsFactory`）。
                //
                // 为什么必须换（源码实证 → `docs/AVI_EXO_COMPAT_STUDY.md`）：
                //   · 官方 `AviExtractor` **只认 `idx1` 一种索引**：状态机里只有
                //     `STATE_FINDING_IDX1_HEADER` / `STATE_READING_IDX1_BODY`，
                //     常量表里**没有 `INDX` / `AVIX` / `DMLH`**（即不支持 OpenDML）。
                //   · **没有 `idx1` 时它直接**：
                //       `extractorOutput.seekMap(new SeekMap.Unseekable(durationUs))`
                //     → 整个视频被判为**不可 seek**，用户观感就是「拖了没反应」。
                //   · 只支持**单个 `movi` 段** → >1GB 的 OpenDML AVI 只能读第一段。
                //
                // 换用后（本库 MIT、AAR 仅 55KB、纯 Java 无 native）：
                //   OpenDML（`indx`/`ix##`）与多 `movi` 均可解析、无索引时也有兜底，
                //   → **不再需要靠「把整个文件重封装成 MP4」来换取 seek 能力**。
                //
                // ⚠️ 下游的 `AviRiffProbe` + `VideoRemuxer` 自修复链路**保留** ——
                //    它是更低一层的兜底（extractor 也救不了的文件仍走重封装）。
                // ⚠️ 本库内部只会**替换 AVI 那一个 extractor**，其余原样委托
                //    `DefaultExtractorsFactory` → 对 mp4/mkv/ts 等零回归。
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    context,
                    com.homesoft.exo.extractor.AviExtractorsFactory()
                )
                    .setDataSourceFactory(smbAwareFactory)
                    // ⚠️ v2.1.248：**补上内嵌 ASS/SSA 字幕支持**。
                    //    Media3 自带 `ssa/SsaParser`，但默认的
                    //    `DefaultSubtitleParserFactory` **没登记 `text/x-ssa`**
                    //    （只有 dvbsubs/pgs/ttml/mp4-vtt/tx3g/subrip 六个 MIME），
                    //    于是 MKV 内挂的 ASS 轨会走
                    //    `Unsupported MIME type: text/x-ssa` → 字幕静默不显示。
                    //    SsaAwareSubtitleParserFactory 把 text/x-ssa 路由给 SsaParser，
                    //    其余 MIME 原样委托默认工厂（零回归）。
                    .setSubtitleParserFactory(SsaAwareSubtitleParserFactory())
                    // ⚠️ **必须同时开这个开关**：agent 只有在「抽取阶段」就解析字幕，
                    //    内嵌字幕才会经 Player.Listener.onCues 回调出来（本文件的
                    //    exoCueText 兜底显示就挂在那上面）。只挂 parserFactory 不开它，
                    //    内嵌 ASS 仍然不会显示。
                    .experimentalParseSubtitlesDuringExtraction(true)
            )
            .build()
            .apply {
            // ⚠️ v2.1.247：显式设定 seek 精度（此前**从未设过**，走 Media3 默认）。
            //
            // 为什么必须有这一行（AVI 相关）：
            //   · EXO 默认 `SeekParameters.DEFAULT` == EXACT（精确到帧）——
            //     它会「跳到前一个关键帧，再解码并丢弃中间所有帧」。
            //   · 对 **AVI**：`AviExtractor` 在**没有 `idx1` 索引**时，每次 seek 都要
            //     从头**线性扫描 chunk** 才能找到目标点；叠加 EXACT 的「解码丢弃」，
            //     单次 seek 可能耗时几百毫秒~数秒 → 用户观感就是「拖不动 / 拖了弹回去」。
            //   · 对 **AV1**：GOP 通常比 H.264 长（8~16s），EXACT 的「解码丢弃」代价
            //     进一步放大，4K AV1 上尤为明显。
            //
            // 选 `CLOSEST_SYNC`（关键帧级）的理由：
            //   · 只跳到**最近的关键帧**，不额外解码丢弃 → AVI 的线性扫描代价降一个量级；
            //   · 精度损失是「最多差一个 GOP」，对**拖动定位**场景完全够用
            //     （用户拖的是大致位置，不是要逐帧对齐）；
            //   · 与另三个后端（IJK/MPV/SYSTEM）的行为更一致 —— 它们默认都偏关键帧级。
            //
            // ⚠️ 仍保留 `enable-accurate-seek` 那类「需要逐帧精确」的场景：
            //     IJK 参数面板里的 `accurateSeek` 不受此行影响（那是 IJK 自己的选项）。
            setSeekParameters(SeekParameters.CLOSEST_SYNC)
            setVideoSurface(nativeSurface)
            setMediaItem(ExoMediaItem.fromUri(decodedUri))
            repeatMode = Player.REPEAT_MODE_ALL
            
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setMaxVideoSize(maxResolution.width, maxResolution.height)
                .build()
            
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    applyVideoSize(videoSize.width, videoSize.height)
                }

                override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                    if (cueGroup.cues.isNotEmpty()) {
                        exoCueText = cueGroup.cues.joinToString("\n") { it.text ?: "" }
                    } else {
                        exoCueText = null
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        isVideoPlaying = playWhenReady
                        if (liveReconnectAttempt > 0) {
                            liveReconnectAttempt = 0
                            if (isLiveStream) videoDurationText = "\u25CF LIVE"
                        }
                    }
                }

                // v119 修复(#7) 补充：位置恢复改在 onEvents 中执行 —— 该回调会把 player
                // 实例作为参数传入，避免在 object 表达式里捕获尚未初始化完成的 exo 变量。
                override fun onEvents(player: Player, events: Player.Events) {
                    if (!events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) return
                    when (player.playbackState) {
                        Player.STATE_READY -> {
                            if (!resumeApplied) {
                                resumeApplied = true
                                val dur = player.duration
                                if (PlaybackPositions.shouldResume(resumeMs, dur)) {
                                    player.seekTo(resumeMs)
                                    Log.i("VRPlayerScreen", "恢复上次播放位置 ${resumeMs}ms / 总长 ${dur}ms")
                                }
                            }
                        }
                        Player.STATE_ENDED -> {
                            // v2.6.20：同 onCompletion —— 归位播放态，停掉进度轮询，
                            // 否则轮询每秒把进度写回 1.0、进度条拖不动。
                            isVideoPlaying = false
                            playbackEnded = true
                            PlaybackPositions.clear(prefs, videoUriStr)
                            resumeMs = 0L
                        }
                    }
                }
                
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.e("VRPlayerScreen", "ExoPlayer playback error", error)
                    val uriNow = selectedMediaItem.uri
                    // v2.8.4（P1）：**网页直链 403/410 鉴权失败 → 动态刷新 Cookie 并重试**
                    if (VRPlayerMediaHelpers.isHttpAuthOrForbiddenError(error) && HttpPlaybackHeaders.isActive &&
                        uriNow != null && cookieRetryAttemptedUri != uriNow
                    ) {
                        cookieRetryAttemptedUri = uriNow
                        val refreshed = HttpPlaybackHeaders.refreshCookie(uriNow)
                        Log.w(
                            "VRPlayerScreen",
                            "Exo 网页直链 403/410 鉴权失败（Cookie可能过期）→ 重新获取Cookie(成功=$refreshed)，尝试重试续播"
                        )
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_cookie_refreshed_retry),
                            Toast.LENGTH_SHORT
                        ).show()
                        cookieRetryTrigger++
                        return
                    }
                    val exoType = (error as? androidx.media3.exoplayer.ExoPlaybackException)?.type ?: -1
                    // v2.8.6（直播主线 A 尾项）：直播断流指数退避自动重连
                    val isLiveNow = isLiveStream || (uriNow != null && MediaFormats.isLikelyLiveStream(Uri.parse(uriNow)))
                    if (isLiveNow && uriNow != null &&
                        LiveReconnectPolicy.isRetryableLiveError(error.errorCode, exoType) &&
                        liveReconnectAttempt < LiveReconnectPolicy.MAX_ATTEMPTS
                    ) {
                        val delayMs = LiveReconnectPolicy.nextDelayMs(liveReconnectAttempt)
                        val attemptNum = liveReconnectAttempt + 1
                        liveReconnectAttempt = attemptNum
                        videoDurationText = "\u25CF LIVE \u00B7 " + context.getString(
                            R.string.live_reconnecting,
                            attemptNum,
                            LiveReconnectPolicy.MAX_ATTEMPTS
                        )
                        Log.w(
                            "VRPlayerScreen",
                            "直播流异常断开（code=${error.errorCode}, type=$exoType），${delayMs}ms 后自动重连（$attemptNum/${LiveReconnectPolicy.MAX_ATTEMPTS}）"
                        )
                        liveReconnectJob?.cancel()
                        liveReconnectJob = scope.launch {
                            delay(delayMs)
                            if (selectedMediaItem.uri == uriNow) {
                                playerInstance?.exo?.let { liveExo ->
                                    liveExo.seekToDefaultPosition()
                                    liveExo.prepare()
                                    liveExo.playWhenReady = true
                                }
                            }
                        }
                        return
                    }
                    // ==========================================================
                    // v2.6.13：解码类失败 → 先排除「尺寸超出硬件上限」
                    // ----------------------------------------------------------
                    // 典型场景：8192×4096 的 8K 全景。MediaCodec 的软解（c2.android.*）
                    //   也走同一套 size/block 校验，所以 Exo 把候选解码器试一遍后会全部
                    //   DECODER_INIT_FAILED —— 此时再把 `isSoftwareDecoding` 置 true 毫无
                    //   意义（还是同一个 4096 上限）。唯一出路是换 FFmpeg 软解内核。
                    // ⚠️ 必须先探测尺寸再决定：解码失败的原因很多（profile 不支持、
                    //   secure 缺失、DRM…），不能一律当超限处理，否则会把普通片源
                    //   无谓地推去软解。
                    // ⚠️ 只在 EXO 生效：IJK/MPV 本身就是软解，`decoderEngine` 为它们时
                    //   这条分支不会走到（onPlayerError 只挂在 Exo 上）。
                    // ==========================================================
                    val decError =
                        error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED
                    val softCandidate = selectedMediaItem.uri
                    if (decError && softCandidate != null && softCandidate != softRetryUri) {
                        scope.launch(Dispatchers.IO) {
                            val oversize = VRPlayerMediaHelpers.probeOversizedVideo(context, Uri.parse(softCandidate))
                            if (oversize != null) {
                                Log.w(
                                    "VRPlayerScreen",
                                    "解码失败且尺寸 $oversize 超出本机硬件上限 → 改用软解内核重试"
                                )
                                withContext(Dispatchers.Main) {
                                    softRetryUri = softCandidate
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.toast_oversize_soft_switch, oversize),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }
                    // F. Auto fallback to software decoding when the hardware
                    // decoder fails to initialize or decode (opt-in, off by default). (8/3 功能)
                    if (autoFallbackSoftEnabled && !isSoftwareDecoding &&
                        (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
                    ) {
                        Toast.makeText(context, context.getString(R.string.toast_hw_failed_sw), Toast.LENGTH_SHORT).show()
                        isSoftwareDecoding = true
                    }
                    // v2.4.32：**Exo 打不开该文件 → 本次自动改用 MPV 重试一次**。
                    //   典型：某些 MP4/fMP4 的编解码配置畸形（实测 HEVC 的 hvcC 参数集数组为空）
                    //   → Media3 的 HevcConfig/BoxParser 直接抛异常（Source error，日志里是
                    //   `Unexpected IndexOutOfBoundsException`）。这类**解析层面**的失败
                    //   切软/硬解都救不了，只有换 MPV（FFmpeg）能解。
                    //   ⚠️ 只对「这一个文件」重试**一次**（`mpvRetryUri` 去重），
                    //      并且**不改**用户的解码器设置；MPV 不可用则不尝试（避免空转）。
                    // 判据用 **ExoPlaybackException 的 `type`**（`PlaybackException` 本身没有 type）：
                    //   TYPE_SOURCE     = 读取/解析容器失败（本实例即这类）；
                    //   TYPE_UNEXPECTED = 其它非解码类异常（Loader 把解析器抛的
                    //                     IndexOutOfBounds 包装后的那种）。
                    //   TYPE_DECODER 交给上面的软解回退，不在这里抢。
                    val sourceLikeFailure =
                        exoType == androidx.media3.exoplayer.ExoPlaybackException.TYPE_SOURCE ||
                            exoType == androidx.media3.exoplayer.ExoPlaybackException.TYPE_UNEXPECTED
                    if (sourceLikeFailure && uriNow != null && uriNow != mpvRetryUri &&
                        MpvPlayerFactory.isAvailable(context)
                    ) {
                        Log.w("VRPlayerScreen", "Exo 无法打开（type=$exoType）→ 改用 MPV 重试一次")
                        mpvRetryUri = uriNow
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_exo_fallback_mpv),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            })
            
            prepare()
            // 注：位置恢复已移到 onPlaybackStateChanged(STATE_READY)，
            // 那里 duration 才真正就绪（见 #7 修复说明）
            playWhenReady = true
            isVideoPlaying = true
        }
        // v2.1.233：包一层门面，使 playerInstance 与 IJK 分支同类型
        playerInstance = ExoBackend(exo, Uri.parse(videoUriStr))
    } catch (e: Exception) {
        Log.e("VRPlayerScreen", "Error preparing video content", e)
    }
    }
}
