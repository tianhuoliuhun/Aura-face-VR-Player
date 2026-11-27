package com.example.vr

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.net.Uri
import android.util.Log
import android.os.Build
import android.view.Surface
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.common.Player
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ProgressHolder
import androidx.media3.common.Effect
import androidx.media3.transformer.Effects
import androidx.media3.effect.Presentation
import com.google.common.collect.ImmutableList
import android.widget.Toast
import java.io.File
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.vr.vrinput.VrGamepadAction
import com.example.vr.vrinput.VrGamepadBus
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import com.example.vr.cast.CastCommand
import com.example.vr.cast.CastMediaBus
import com.example.vr.cast.CastReceiverService
import java.io.InputStream
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VRPlayerScreen(
    modifier: Modifier = Modifier,
    initialVideoUri: String? = null,
    onExternalUriConsumed: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Load local SharedPreferences & memory mode flag
    val prefs = remember { context.getSharedPreferences("vr_player_prefs", android.content.Context.MODE_PRIVATE) }
    // v2.6.7：**记忆模式改为强制开启**（用户要求去掉开关 UI）。
    // 保留这个常量而不是删掉变量：下游有 100+ 处 `if (isMemoryModeEnabled)` 门控
    // （读 pref 与否、写回与否），恒真即「始终读写 prefs」，与旧版默认值一致，
    // 因此无需逐个改判断。写回处仍会 putBoolean(true)，可自动纠正旧设备上的 false。
    val isMemoryModeEnabled = true
    // v2.4.58：投屏接收（DLNA/UPnP-AV MediaRenderer）开关 —— 开启后本机在局域网内
    // 作为一台「可投屏设备」出现，接收其他 App 推送的视频 URL 并播放。
    var castReceiverEnabled by remember {
        mutableStateOf(if (isMemoryModeEnabled) prefs.getBoolean("cast_receiver_enabled", false) else false)
    }

    // Screen Layout orientation states (Lock to Landscape manually as requested)
    var isLandscape by remember { mutableStateOf(true) }
    var isUserTouching by remember { mutableStateOf(false) }

    // Media and projection states
    // v2.0.181：内置演示图恢复为 v2.0.179 的第一张 —— `demo_360_beauty`「360° 等距圆柱全景
    // 画展廊」(2:1)。这张**必须**配 VR_360 球面投影才是一幅可环视的画廊；用 STANDARD 平面
    // 看会被压成屏幕中间一条窄带。故默认投影随之改回 **VR_360**。
    var selectedMediaItem by remember { mutableStateOf(DemoMediaProvider.demoMediaList[0]) }
    var projectionMode by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) {
                val modeId = prefs.getInt("projection_mode", ProjectionMode.VR_360.id)
                ProjectionMode.values().firstOrNull { it.id == modeId } ?: ProjectionMode.VR_360
            } else {
                ProjectionMode.VR_360
            }
        )
    }
    // 智能投影自动识别（8/1 功能）：False until the user explicitly picks a projection mode.
    // Smart auto-detection (2D video -> STANDARD, 2:1 panorama -> VR_360) only applies
    // while this is false, so a manual choice is never overridden.
    var projectionModeUserAdjusted by remember { mutableStateOf(false) }
    // v2.1.211：**鱼眼视场角**（度）—— 仅 FISHEYE 模式生效，180 为基准档。
    // 不同鱼眼镜头片源的视角不同（常见 180/190/200/220），选错会出现
    // 「画面鼓成球」或「中心挤成一团」，所以做成可选。
    var fisheyeFovDeg by remember {
        mutableStateOf(prefs.getInt("fisheye_fov_deg", 180))
    }
    // Master switch for the smart projection detection (settings panel)
    var isSmartProjectionEnabled by remember {
        mutableStateOf(prefs.getBoolean("smart_projection_enabled", true))
    }
    // 强制视频类型判断：0=自动检测，1=强制2D平面，2=强制360全景，3=强制180穹幕，4=强制3D左右，5=强制3D上下
    var forceVideoType by remember {
        mutableIntStateOf(prefs.getInt("force_video_type", 0))
    }
    var stereoMode by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) {
                val modeId = prefs.getInt("stereo_mode", StereoMode.MONO.id)
                StereoMode.values().firstOrNull { it.id == modeId } ?: StereoMode.MONO
            } else {
                StereoMode.MONO
            }
        )
    }

    // Beauty and picture adjustments
    // 预设：自然/淡妆/浓妆/自定义。
    // v2.0.156：内存状态改用**稳定 id**（BEAUTY_PRESET_*）—— 此前存本地化名，切界面语言就会
    // 高亮失配；各滑块回调里硬编码的中文 "自定义" 在非中文界面同样匹配不上。
    // 落盘仍是 0/1/2/3，映射集中在「这里」与「写回」两处。
    var beautyPreset by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) {
                when (prefs.getInt("beauty_preset_id", 3)) {
                    0 -> BEAUTY_PRESET_NATURAL
                    1 -> BEAUTY_PRESET_LIGHT
                    2 -> BEAUTY_PRESET_HEAVY
                    else -> BEAUTY_PRESET_CUSTOM
                }
            } else BEAUTY_PRESET_CUSTOM
        )
    }
    // v2.0.160：原「对比原图」改为「美颜总开关」；v2.0.172：默认改为**关**。
    // v2.4.35（#13）：美颜参数与记忆模式解耦 —— 开关状态始终落盘，不再跟随 isMemoryModeEnabled。
    var beautyMasterEnabled by remember {
        mutableStateOf(prefs.getBoolean("beauty_master_enabled", false))
    }
    // v2.0.160：美颜引擎（0 = GLSL 内置，1 = GPUPixel）。
    // v2.6.22（美颜审查 P1-4）：**参数已统一** —— 原先这里另有 beautyGpSmooth / beautyGpWhite /
    // beautyGpSlim / beautyGpEyeZoom 四个独立 state（prefs 前缀 beauty_gp_*），
    // 与下方的 beautyLevel / beautyWhitening / beautyFaceSlimming / beautyBigEyes 各调一套，
    // 切引擎时四项效果直接跳变。现在两个引擎共用同一套参数（GPUPixel 只支持其中四项，
    // 其余在面板上标注「该引擎不支持」）。
    var beautyEngineType by remember {
        mutableIntStateOf(prefs.getInt("beauty_engine_type", BEAUTY_ENGINE_GLSL))
    }
    // v2.0.160（P3）：GPUPixel 方案下把人脸美颜也应用到 VR/全景视频（屏幕空间后处理）。
    // v2.0.184：恢复为**真开关**（默认开）—— v2.0.182 曾把它从激活条件里拿掉（当时 VR 无条件生效），
    // 于是它退化成 UI 占位；现重新纳入 isGpuPixelActive()，让用户能真正控制「VR 下是否启用 GPUPixel 美颜」。
    var gpuPixelVrFaceBeauty by remember {
        mutableStateOf(prefs.getBoolean("beauty_gp_vr_face", true))
    }
    // v2.0.187：GPUPixel 美颜半分辨率处理（默认开）。
    // v2.4.35（#13）：不再跟随记忆模式 —— 美颜参数始终落盘，故此处始终读取 prefs。
    var gpuPixelHalfResBeauty by remember {
        mutableStateOf(prefs.getBoolean("beauty_gp_half_res", true))
    }
    var beautyLevel by remember {
        mutableFloatStateOf(
            prefs.getFloat("beauty_level", 0.65f)
        )
    }
    // v2.0.159：磨皮「皮肤质感」= 频域分离的高频保留度（0.5~1.3；> 1 相当于 USM 锐化）
    var beautyTextureDetail by remember {
        mutableFloatStateOf(
            prefs.getFloat("beauty_texture_detail", 0.88f)
        )
    }
    var brightnessLevel by remember {
        mutableFloatStateOf(
            if (isMemoryModeEnabled) prefs.getFloat("brightness_level", 0.0f) else 0.0f
        )
    }
    var contrastLevel by remember {
        mutableFloatStateOf(
            if (isMemoryModeEnabled) prefs.getFloat("contrast_level", 1.05f) else 1.05f
        )
    }

    // 12 Fine-grained Beauty cosmetics states
    var beautyWhitening by remember {
        mutableFloatStateOf(
            prefs.getFloat("beauty_whitening", 0.5f)
        )
    }
    var beautyFaceSlimming by remember {
        mutableFloatStateOf(
            prefs.getFloat("beauty_face_slimming", 0.4f)
        )
    }
    var beautyBigEyes by remember {
        mutableFloatStateOf(
            prefs.getFloat("beauty_big_eyes", 0.3f)
        )
    }
    var beautyDarkCircles by remember {
        mutableFloatStateOf(
            prefs.getFloat("beauty_dark_circles", 0.3f)
        )
    }
    var beautyNoseSlimming by remember {
        mutableFloatStateOf(
            prefs.getFloat("beauty_nose_slimming", 0.2f)
        )
    }
    var beautyMouth by remember {
        mutableFloatStateOf(
            prefs.getFloat("beauty_mouth", 0.2f)
        )
    }
    var beautyTeethWhitening by remember {
        mutableFloatStateOf(
            prefs.getFloat("beauty_teeth_whitening", 0.3f)
        )
    }
    var beautyLipstick by remember {
        mutableFloatStateOf(
            prefs.getFloat("beauty_lipstick", 0.3f)
        )
    }
    var beautyBlush by remember {
        mutableFloatStateOf(
            prefs.getFloat("beauty_blush", 0.3f)
        )
    }
    var beautyEyebrows by remember {
        mutableFloatStateOf(
            prefs.getFloat("beauty_eyebrows", 0.4f)
        )
    }
    var beautyLongLegs by remember {
        mutableFloatStateOf(
            prefs.getFloat("beauty_long_legs", 0.4f)
        )
    }
    var beautySmallHead by remember {
        mutableFloatStateOf(
            prefs.getFloat("beauty_small_head", 0.3f)
        )
    }

    // VR head track states
    var isSplitScreenVR by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("is_split_screen_vr", false) else false
        )
    }
    var isGyroEnabled by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("is_gyro_enabled", false) else false
        )
    }
    // v125：陀螺仪转向反转（个别机型/VR 眼镜模式下上下左右仍相反时打开）
    var gyroInverted by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("gyro_inverted", false) else false
        )
    }
    var isSettingsDialogOpen by remember { mutableStateOf(false) }
    // v106：开源许可对话框开关（设置面板 → 关于与开源许可）
    var showLicensesDialog by remember { mutableStateOf(false) }
    var fovDeg by remember {
        mutableFloatStateOf(
            if (isMemoryModeEnabled) prefs.getFloat("fov_deg", 75f) else 75f
        )
    }


    // Playback state
    var isVideoPlaying by remember { mutableStateOf(false) }
    /**
     * v2.6.20：**当前是否处于「已播完」态**。
     *
     * 播完（`onCompletion` / Exo `STATE_ENDED`）之后，各内核都不会因为 `seekTo` 自动开始播放：
     *   · EXO：仅当 `playWhenReady == true` 时 seek 后才会续播；
     *   · IJK / MPV：`seekTo` 只把播放头挪过去，完成态下仍需一次 `start`；
     *   · 系统 MediaPlayer：`PlaybackCompleted` 状态下 `seekTo` 本来就无效。
     * 于是「播完 → 拖进度条 → 停在目标画面不动」。
     * 这个标志让进度条松手时知道"这不是普通暂停，而是播完了"，从而补一次 play()。
     * 任何一次真正的开播（播放按钮 / 新播放器就绪 / 换片）都要把它清掉。
     */
    var playbackEnded by remember { mutableStateOf(false) }
    var videoPlaybackProgress by remember { mutableFloatStateOf(0f) }
    var videoDurationText by remember { mutableStateOf("00:00 / 00:00") }

    // New states for seek drag previews, video mirroring, and 180° Dome eye preference
    var isVideoMirrored by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("is_video_mirrored", false) else false
        )
    }
    var domeHalfSelect by remember {
        mutableIntStateOf(
            if (isMemoryModeEnabled) prefs.getInt("dome_half_select", 1) else 1
        )
    }
    var isHoverActive by remember { mutableStateOf(false) }
    var hoverTimeMs by remember { mutableLongStateOf(0L) }
    var hoverPreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // v2.0.180：上次抓取拖动预览帧的时刻（用于节流，避免拖动期间每帧都解码）
    var lastSeekThumbAt by remember { mutableLongStateOf(0L) }

    // Trigger state to notify Renderer to refresh its static photo texture
    var photoReloadTrigger by remember { mutableIntStateOf(0) }
    // Store custom loaded URI Bitmap
    var customBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val audioProcessor = remember { StereoChannelSwappingAudioProcessor() }
    val genderFilterProcessor = remember { GenderFilterAudioProcessor() }
    var isGenderFilterEnabled by remember { mutableStateOf(false) }
    val sliderInteractionSource = remember { MutableInteractionSource() }
    val isSliderDragged by sliderInteractionSource.collectIsDraggedAsState()

    // New states for independent controls
    var isViewLocked by remember { mutableStateOf(false) }
    var isAudioMirrored by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("is_audio_mirrored", false) else false
        )
    }
    var warpMode by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) {
                val savedId = prefs.getInt("warp_mode", -1)
                if (savedId != -1) {
                    WarpMode.values().find { it.id == savedId } ?: WarpMode.NONE
                } else if (prefs.getBoolean("is_cylinder_enabled", false)) {
                    WarpMode.CYLINDER_RECT
                } else {
                    WarpMode.NONE
                }
            } else {
                WarpMode.NONE
            }
        )
    }
    var videoCurvature by remember {
        mutableFloatStateOf(
            if (isMemoryModeEnabled) prefs.getFloat("video_curvature", 0.3f) else 0.3f
        )
    }
    // v2.6.9 P1：Cardboard 透镜反畸变自动开关（默认开启）
    var isCardboardDistortionEnabled by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("cardboard_anti_distortion", true) else true
        )
    }
    // v2.6.9 P2：适眼视距巨幕缩放（默认 0.82f，范围 0.60f ~ 1.0f）
    var vrCinemaScale by remember {
        mutableFloatStateOf(
            if (isMemoryModeEnabled) prefs.getFloat("vr_cinema_scale", 0.82f) else 0.82f
        )
    }
    // v2.6.9 P0：Cardboard 视线凝视免触控交互开关（默认开启）
    var isGazePointerEnabled by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("gaze_pointer_enabled", true) else true
        )
    }
    var gazeYawDeg by remember { mutableFloatStateOf(0f) }
    var gazePitchDeg by remember { mutableFloatStateOf(0f) }
    // 参考 vrplayer-gemini：左右/上下眼序对调（适配交叉眼与反序 3D 片源）
    var isEyesSwapped by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("is_eyes_swapped", false) else false
        )
    }
    var maxResolution by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) {
                val savedId = prefs.getInt("max_resolution_id", -1)
                if (savedId != -1) {
                    MaxResolution.values().find { it.id == savedId } ?: MaxResolution.UNRESTRICTED
                } else {
                    MaxResolution.UNRESTRICTED
                }
            } else {
                MaxResolution.UNRESTRICTED
            }
        )
    }

    // Floating ball and playback speed states
    var isFloatingBallEnabled by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("is_floating_ball_enabled", true) else true
        )
    }
    // v2.0.165：快进 / 后退悬浮球 —— 各自独立开关，默认均为**关**（不影响现有交互）。
    // v2.0.172：开关与步长状态的记忆已修复（此前这 4 个状态不在写回 LaunchedEffect 的
    // key 列表里，切换后 prefs 从未落盘，重启即丢）→ 现为真正的记忆开关。
    var isSeekForwardBallEnabled by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("is_seek_forward_ball_enabled", false) else false
        )
    }
    var isSeekBackwardBallEnabled by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("is_seek_backward_ball_enabled", false) else false
        )
    }
    // v2.1.208：**时间标记球开关**（单击跳回标记 / 双击打标记 / 拖动移位）
    // 与快进、后退两球同属「悬浮球」设置组，默认同样为**关**，保持一致的心智模型
    var isMarkerBallEnabled by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("is_marker_ball_enabled", false) else false
        )
    }
    // v2.4.67：**信息悬浮球（时钟 / 电量）** —— 与标记球同属「悬浮球」设置组，默认**关**。
    // 数据源与更新方式见 InfoFloatingBall 的注释（时钟定时器 / 电量系统广播）。
    var isClockBallEnabled by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("is_clock_ball_enabled", false) else false
        )
    }
    var isBatteryBallEnabled by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("is_battery_ball_enabled", false) else false
        )
    }
    // v2.6.4：播放控制球 / 性能球（与信息球同属「悬浮球」组，默认关）
    var isPlayBallEnabled by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("is_play_ball_enabled", false) else false
        )
    }
    var isPerfBallEnabled by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("is_perf_ball_enabled", false) else false
        )
    }
    // v2.6.4：URL 直接播放对话框
    var showUrlPlayDialog by remember { mutableStateOf(false) }
    // v2.9.0：网页直链播放（v2.6.5 曾按用户要求移除入口，仅保留 WebSnifferScreen 实现）。
    // 现在重新接回「选择文件来源」—— 页面地址 → WebView 旁听 → 抓出真实视频流。
    var showWebSniffer by remember { mutableStateOf(false) }
    // 步长（秒），双击循环 5 → 10 → 15 → 30
    var seekForwardStep by remember {
        mutableIntStateOf(if (isMemoryModeEnabled) prefs.getInt("seek_forward_step", 5) else 5)
    }
    var seekBackwardStep by remember {
        mutableIntStateOf(if (isMemoryModeEnabled) prefs.getInt("seek_backward_step", 5) else 5)
    }
    // 快进/后退/步长切换/加速球双击切档的提示文案（null = 不显示）
    var seekHudText by remember { mutableStateOf<String?>(null) }
    var floatingBallSpeed by remember {
        mutableFloatStateOf(
            if (isMemoryModeEnabled) prefs.getFloat("floating_ball_speed", 2.0f) else 2.0f
        )
    }
    var basePlaybackSpeed by remember {
        mutableFloatStateOf(
            if (isMemoryModeEnabled) prefs.getFloat("base_playback_speed", 1.0f) else 1.0f
        )
    }

    var maxFps by remember {
        mutableIntStateOf(
            if (isMemoryModeEnabled) prefs.getInt("max_fps", 0) else 0
        )
    }

    // ===== v2.0.206：画质增强（MEMC 插帧 / FSR 超分）=====
    // 规则语义与档位定义见 VideoEnhanceConfig.kt；这里只保存用户的**选择**，
    // 「当前实际会不会生效」一律由 VideoEnhanceRules.resolveFsr 现算，
    // 避免出现「UI 显示 1440p、渲染却在用 1080p」这种两套逻辑对不上的情况。
    var isMemcEnabled by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("memc_enabled", false) else false
        )
    }
    var memcTargetFps by remember {
        mutableIntStateOf(
            if (isMemoryModeEnabled) {
                // ⚠️ 读回时必须收敛到 [48,120] —— 老版本可能存过非法值，
                //    直接信 prefs 会让渲染侧拿到 0 或 9999。
                VideoEnhanceRules.clampMemcTargetFps(
                    prefs.getInt("memc_target_fps", VideoEnhanceRules.MEMC_TARGET_FPS_DEFAULT)
                )
            } else VideoEnhanceRules.MEMC_TARGET_FPS_DEFAULT
        )
    }
    var isFsrEnabled by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("fsr_enabled", false) else false
        )
    }
    var fsrRuleMode by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) FsrRuleMode.fromId(prefs.getString("fsr_rule_mode", null))
            else FsrRuleMode.DEFAULT
        )
    }
    var fsrCustomTarget by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) FsrTargetResolution.fromId(prefs.getString("fsr_target_resolution", null))
            else FsrTargetResolution.P1080
        )
    }
    // 视频源分辨率：FSR 默认规则的判定基准。
    // ⚠️ 必须是 state —— 它由 onVideoSizeChanged 回调写入，若只存在 renderer 里，
    //    尺寸变化不会触发重组，UI 上的判定结果会永远停在初始值（0）上。
    var videoSourceWidth by remember { mutableIntStateOf(0) }
    var videoSourceHeight by remember { mutableIntStateOf(0) }

    var isSoftwareDecoding by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("is_software_decoding", false) else false
        )
    }
    // 8K 超高清编码头适配（SPS level patch）：默认关闭，实验性功能（8/2-8/3）
    var levelPatchEnabled by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("level_patch_enabled", false) else false
        )
    }
    var level51Enabled by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("level51_enabled", false) else false
        )
    }
    var forceHwDecoderEnabled by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("force_hw_decoder_enabled", false) else false
        )
    }
    var spoofResolutionEnabled by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("spoof_resolution_enabled", false) else false
        )
    }
    var downscaleOutputEnabled by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("downscale_output_enabled", false) else false
        )
    }
    var addCodecParamsEnabled by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("add_codec_params_enabled", false) else false
        )
    }
    var autoFallbackSoftEnabled by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("auto_fallback_soft_enabled", false) else false
        )
    }
    var decoderEngine by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) {
                // v2.1.232：**未接通的内核一律回落 EXO**。
                // 这里刻意从 `isImplemented` 取值而不是硬编码 `id == MPV.id` ——
                // 否则每加一个预留内核（这次是 IJK）都要记得回来补一句，忘了就会出现
                // 「存档里是未实现的内核 → 播放链路拿不到 ExoPlayer」的隐蔽崩溃。
                val id = prefs.getInt("decoder_engine_id", DecoderEngine.EXO.id)
                DecoderEngine.values().find { it.id == id && it.isImplemented }
                    ?: DecoderEngine.EXO
            } else DecoderEngine.EXO
        )
    }

    /**
     * v2.4.32：**「Exo 打不开 → 本次改用 MPV 重试」的 URI**。
     *
     * 背景：有些 MP4/fMP4 的编解码配置畸形（实测：HEVC 的 `hvcC` 参数集数组为空），
     *   Media3 的 `HevcConfig`/`BoxParser` 会**直接抛异常** → `ExoPlaybackException(Source error)`
     *   → Exo 完全打不开。这类**解析层面**的失败，切软/硬解都救不了，只有换 FFmpeg 内核（MPV）能解。
     *
     * 语义：**只对「这一个文件」生效、只重试一次**，而且**不回写**用户的解码器设置
     *   （与上方容器路由「刻意不写回 prefs」同一原则）——避免「开了个坏文件就把引擎永久改掉」。
     * 置位点见 Exo 的 `onPlayerError`；消费点见 `setupVideoPlayer` 的 `effectiveEngine`。
     */
    var mpvRetryUri by remember { mutableStateOf<String?>(null) }

    /**
     * v2.6.13：**该文件超出本机硬件解码上限** → 本次改用软件解码内核（IJK / MPV）。
     *
     * 为什么需要它：`MediaCodec` **没有**任何「按低分辨率解码」的开关（`KEY_MAX_WIDTH`
     * 只对编码器有效），所以 8192×4096 这类超出 VPU block-count 的尺寸，硬解必然
     * configure 失败。能播的唯一途径是换 FFmpeg 软解内核（IJK 内置，MPV 需下载）。
     *
     * 判据与置位见 Exo 的 `onPlayerError`（先用 MediaMetadataRetriever 取真实尺寸，
     * 再用 [DecoderCapabilities.findHardwareFit] 确认「真的没有硬解能吃」才切）；
     * 消费点见 `setupVideoPlayer` 的 `effectiveEngine`。
     *
     * ⚠️ 与 [mpvRetryUri] 一样只记「这一个文件」，且**不写回 prefs** —— 用户设置的内核不变。
     */
    var softRetryUri by remember { mutableStateOf<String?>(null) }

    /**
     * v2.8.3：**该文件在 MPV / IJK / 系统解码上播失败过 → 本次改用 EXO**。
     *
     * 语义与 [mpvRetryUri] / [softRetryUri] 完全对齐：只对「这一个文件」生效、
     * **绝不写回 prefs** —— 用户设置的内核保持不变。
     *
     * 🔴 为什么必须这么做（原来直接 `decoderEngine = DecoderEngine.EXO`）：
     *   `decoderEngine` 是**持久化状态** —— `LaunchedEffect(decoderEngine, …)` 会
     *   `putInt("decoder_engine_id", decoderEngine.id)`。于是**一次播放失败就把用户的
     *   内核设置永久改掉**：用户下次打开完全不同的片子也变成 EXO，表现为「设置自己变了」。
     *   这类「为了救一个坏文件而改全局设置」的做法，与容器路由「刻意不写回 prefs」的原则冲突。
     *
     * 置位点：MPV `onError` / IJK `onError`（非容器路由场景）/ 系统解码 `onError`。
     * 消费点：`setupVideoPlayer` 的 `effectiveEngine` —— **必须放在所有自动路由之后**，
     *   否则会被后面的容器路由（AVI→MPV 等）再改回去。
     *
     * 不会死循环：EXO 失败置 [mpvRetryUri] → 转 MPV；MPV 失败置本标记 → 转回 EXO；
     * EXO 再失败时 `uriNow != mpvRetryUri` 已不成立，不再置位 → 停在 EXO 的报错上。
     */
    var exoRetryUri by remember { mutableStateOf<String?>(null) }

    /**
     * v2.8.4（P1）：**网页直链 403/410 鉴权失败 → 动态刷新 Cookie 并重试**。
     *
     * 针对 HLS 长播放中途 Cookie 过期导致的 403/410 卡死，重新从 CookieManager 读出最新 Cookie 并触发一次重试。
     * 每个 URI 仅允许自动重试一次（记录于 [cookieRetryAttemptedUri]），避免凭据彻底失效时死循环。
     */
    var cookieRetryAttemptedUri by remember { mutableStateOf<String?>(null) }
    var cookieRetryTrigger by remember { mutableIntStateOf(0) }

    // v2.1.233：IJK 内核的可调参数（对应设置面板「解码器参数 · IJK」那几项）。
    // 只有选中 IJK 时才会被读取；切回 EXO 时这些值不参与播放，但仍保留在 prefs 里，
    // 用户下次切回 IJK 时不用重设。
    var ijkOptions by remember {
        mutableStateOf(if (isMemoryModeEnabled) IjkOptions.load(prefs) else IjkOptions())
    }
    // v2.1.234：MPV 内核参数。
    // ⚠️ 新增持久化状态必须**同时改四处**（本项目头号坑）：
    //    ① state 定义（本行）② 写回 LaunchedEffect 的 key 列表 ③ put* 写回块
    //    ④ else 分支的 remove(...)。漏任意一处 = 永不落盘 / 关不掉记忆。
    var mpvOptions by remember {
        mutableStateOf(if (isMemoryModeEnabled) MpvOptions.load(prefs) else MpvOptions())
    }
    // v2.1.244：MPV 视频输出模式 —— 由**容器预判**决定（见 setupVideoPlayer 的「预判式选 vo」）。
    // ⚠️ **故意不持久化**：它只反映「本片要不要走兼容渲染」，不该跨片记住
    //    （否则播过一次 WMV 后，下次播 MP4 也走 GPU 拷贝，白掉性能）。
    // ⚠️ **也不要放进重建 effect 的 key 列表**：vo 在创建播放器前就已定好，
    //    若把它当 key，改它会触发「重建 MPV」→ 撞上全局单例的 `!mpctx->initialized`
    //    断言 → native 崩溃（第一版方案实测踩过）。
    var mpvVoMode by remember { mutableStateOf(MpvVoMode.EMBED) }

    // Subtitle System States
    var isSubtitleEnabled by remember {
        mutableStateOf(if (isMemoryModeEnabled) prefs.getBoolean("is_subtitle_enabled", true) else true)
    }
    // v2.0.154：显示与导出字幕时是否去除标点（默认开启；只影响显示层与导出，不改内部原文）
    var isStripSubtitlePunctuation by remember {
        mutableStateOf(if (isMemoryModeEnabled) prefs.getBoolean("subtitle_strip_punct", true) else true)
    }
    // v2.6.24：字幕文本切换的 160ms 淡入（**默认关闭** —— 直播/密集换行场景不必拖影）
    var subtitleFadeIn by remember {
        mutableStateOf(if (isMemoryModeEnabled) prefs.getBoolean("subtitle_fade_in", false) else false)
    }
    // v2.6.24（直播 P0）：直播态（时长未知 / 不可任意 seek）。
    // 判定需**连续 3 次采样**确认 —— 点播元数据解析期 duration 会短暂为 0，单次判定会误伤。
    var isLiveStream by remember { mutableStateOf(false) }
    val liveStreak = remember { intArrayOf(0) }
    // v2.6.24（直播 P1）：**直播强制 EXO** 的运行时兜底 —— URL 启发式（
    // MediaFormats.isLikelyLiveStream）漏判时（无扩展名且路径无特征词的流），
    // 等播放器自己确认是直播后，把当前 URI 记在这里触发**一次**重建切到 EXO。
    // ⚠️ 只记 URI 不记 boolean：重建后 isLiveStream 会因新播放器重新采样而短暂归 false，
    //    用 boolean 做 key 会来回抖；URI 一旦相等就恒定命中，不会反复重建。
    var liveForceExoUri by remember { mutableStateOf<String?>(null) }
    // v2.6.24（直播 P1）：直播字幕需要实时识别 —— 每次进入直播只提示一次，不反复骚扰
    var liveAsrHinted by remember { mutableStateOf(false) }
    // v2.6.24（直播 P3）：断流/缓冲提示的采样计数（避免一次瞬时 buffering 就弹提示）
    val liveBufferStreak = remember { intArrayOf(0) }
    // v2.8.6（直播主线 A 尾项）：断流指数退避自动重连状态
    var liveReconnectAttempt by remember { mutableIntStateOf(0) }
    var liveReconnectJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // v2.6.24（直播 P1）：流内嵌字幕轨（HLS 的 CC/TTML）自动选中 —— 只尝试一次，
    // 避免每秒覆盖用户后来手动选的轨
    var liveTextTrackPicked by remember { mutableStateOf(false) }
    var loadedSubtitleFileName by remember { mutableStateOf("") }
    var loadedSubtitleCues by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    /**
     * v2.8.3：网页嗅探面板选中的外挂字幕（**待挂载**）。
     *
     * 不能直接写 [loadedSubtitleCues]：切换媒体会触发
     * `LaunchedEffect(selectedMediaItem.uri)`，那里开头就清空字幕（防上一个视频残留），
     * 于是面板刚设好的字幕会被立刻清掉。改为先暂存，由那个 Effect 消费并 return。
     */
    var pendingWebSubtitle by remember { mutableStateOf<WebSubtitle?>(null) }
    var subtitleFont by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) {
                val id = prefs.getInt("subtitle_font_id", SubtitleFont.OPPO_SANS.id)
                SubtitleFont.values().find { it.id == id } ?: SubtitleFont.OPPO_SANS
            } else SubtitleFont.OPPO_SANS
        )
    }
    var subtitleFontSizeSp by remember {
        mutableIntStateOf(if (isMemoryModeEnabled) prefs.getInt("subtitle_font_size", 22) else 22)
    }
    var subtitleFontWeightVal by remember {
        mutableIntStateOf(if (isMemoryModeEnabled) prefs.getInt("subtitle_font_weight", 400) else 400)
    }
    var isSubtitleItalic by remember {
        mutableStateOf(if (isMemoryModeEnabled) prefs.getBoolean("is_subtitle_italic", false) else false)
    }
    var subtitleColorOpt by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) {
                val id = prefs.getInt("subtitle_color_id", 0)
                SubtitleColorOption.values().find { it.id == id } ?: SubtitleColorOption.WHITE
            } else SubtitleColorOption.WHITE
        )
    }
    var subtitleTextAlpha by remember {
        mutableFloatStateOf(if (isMemoryModeEnabled) prefs.getFloat("subtitle_text_alpha", 1.0f) else 1.0f)
    }
    var subtitleStrokeOpt by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) {
                val id = prefs.getInt("subtitle_stroke_id", 2)
                SubtitleStrokeOption.values().find { it.id == id } ?: SubtitleStrokeOption.MEDIUM_BLACK
            } else SubtitleStrokeOption.MEDIUM_BLACK
        )
    }
    var subtitleBgOpt by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) {
                val id = prefs.getInt("subtitle_bg_id", 1)
                SubtitleBgOption.values().find { it.id == id } ?: SubtitleBgOption.SEMI_BLACK
            } else SubtitleBgOption.SEMI_BLACK
        )
    }
    var subtitleOffsetYRatio by remember {
        mutableFloatStateOf(if (isMemoryModeEnabled) prefs.getFloat("subtitle_offset_y", 0.12f) else 0.12f)
    }
    var subtitleOffsetXRatio by remember {
        mutableFloatStateOf(if (isMemoryModeEnabled) prefs.getFloat("subtitle_offset_x", 0.0f) else 0.0f)
    }
    var subtitleDelayMs by remember {
        mutableLongStateOf(if (isMemoryModeEnabled) prefs.getLong("subtitle_delay_ms", 0L) else 0L)
    }
    // v2.6.24：**直播延迟**（秒，0 = 贴实时边缘）。
    // 与 subtitleDelayMs 是两个概念：那个只挪字幕显示的参考时间点，这个真的让
    // 播放器落后实时边缘 N 秒 —— 用实时性换"字幕来得及生成"。
    var liveDelaySec by remember {
        mutableIntStateOf(if (isMemoryModeEnabled) prefs.getInt("live_delay_sec", 0) else 0)
    }
    // 在线字幕搜索 API Key（8/2 功能）
    var subtitleSearchApiKey by remember {
        mutableStateOf(prefs.getString("subtitle_search_api_key", "") ?: "")
    }
    var subtitleTextAlignOpt by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) {
                val id = prefs.getInt("subtitle_align_id", 0)
                SubtitleAlignOption.values().find { it.id == id } ?: SubtitleAlignOption.CENTER
            } else SubtitleAlignOption.CENTER
        )
    }
    var vrIpdOffsetRatio by remember {
        mutableFloatStateOf(if (isMemoryModeEnabled) prefs.getFloat("vr_ipd_offset", 0.0f) else 0.0f)
    }
    var subtitleMaxLines by remember {
        mutableIntStateOf(if (isMemoryModeEnabled) prefs.getInt("subtitle_max_lines", 2) else 2)
    }
    val subtitleTranslator = remember { SubtitleTranslator(context) }
    // v2.6.24（直播 P2）：直播态同步给翻译器 —— 一个开关切两处行为
    // （不预读 / 不落盘，见 SubtitleTranslator.liveMode 的表格）。
    // ⚠️ v2.7.1：**不再有"迟到译文丢弃"**（用户明确要求），所以这里也不写任何过期时刻。
    LaunchedEffect(isLiveStream) {
        subtitleTranslator.liveMode = isLiveStream
    }
    // v2.0.127：恢复上次的「字幕翻译」开关。
    // v2.0.144：翻译相关设置**全部**固化——此前只有 translation_enabled 落盘，
    // 引擎（必应/MyMemory/…）与「双语/仅译文」显示模式每次重启都回到默认，
    // 用户会以为"选了没用"。这里连同目标语言、API Key、Base URL、模型名一并读回。
    // 另加「已恢复」门控：确保写回 effect 在恢复完成前不会用默认值覆盖已存设置。
    var translatorSettingsRestored by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (isMemoryModeEnabled) {
            val c = subtitleTranslator.config
            val engine = TranslationEngine.values()
                .find { it.id == prefs.getInt("translate_engine_id", c.engine.id) } ?: c.engine
            val displayMode = TranslationDisplayMode.values()
                .find { it.id == prefs.getInt("translate_display_mode_id", c.displayMode.id) } ?: c.displayMode
            val targetLang = TranslationTargetLanguage.values()
                .find { it.id == prefs.getInt("translate_target_lang_id", c.targetLanguage.id) } ?: c.targetLanguage
            subtitleTranslator.config = c.copy(
                isEnabled = prefs.getBoolean("translation_enabled", false),
                engine = engine,
                displayMode = displayMode,
                targetLanguage = targetLang,
                apiKey = prefs.getString("translate_api_key", "") ?: "",
                baseUrl = prefs.getString("translate_base_url", "") ?: "",
                modelName = prefs.getString("translate_model_name", "") ?: "",
                glossary = prefs.getString("translate_glossary", "") ?: ""
            )
        }
        translatorSettingsRestored = true
    }
    // v2.0.144：翻译设置固化写回。key 变化即落盘（记忆模式关闭时清除）。
    LaunchedEffect(
        translatorSettingsRestored,
        isMemoryModeEnabled,
        subtitleTranslator.config.engine,
        subtitleTranslator.config.displayMode,
        subtitleTranslator.config.targetLanguage,
        subtitleTranslator.config.apiKey,
        subtitleTranslator.config.baseUrl,
        subtitleTranslator.config.modelName,
        subtitleTranslator.config.glossary
    ) {
        if (!translatorSettingsRestored) return@LaunchedEffect
        prefs.edit().apply {
            if (isMemoryModeEnabled) {
                putInt("translate_engine_id", subtitleTranslator.config.engine.id)
                putInt("translate_display_mode_id", subtitleTranslator.config.displayMode.id)
                putInt("translate_target_lang_id", subtitleTranslator.config.targetLanguage.id)
                putString("translate_api_key", subtitleTranslator.config.apiKey)
                putString("translate_base_url", subtitleTranslator.config.baseUrl)
                putString("translate_model_name", subtitleTranslator.config.modelName)
                putString("translate_glossary", subtitleTranslator.config.glossary)
            } else {
                remove("translate_engine_id")
                remove("translate_display_mode_id")
                remove("translate_target_lang_id")
                remove("translate_api_key")
                remove("translate_base_url")
                remove("translate_model_name")
                remove("translate_glossary")
            }
            apply()
        }
    }

    // ===== v2.2.0（P1）：AI 弹幕配置 =====
    // 与翻译配置**有意分离**：翻译用文本模型、弹幕用视觉模型，复用同一份会互相污染
    // （方案文档 D1 决策）。默认端点/模型见 `DanmuConfig.DEFAULT_BASE_URL` / `DEFAULT_MODEL`
    // —— v2.4.4 起为 **AMD Radeon 开发者平台 + `MiMo-V2.6-Flash`**（OpenAI 兼容）。
    var danmuSettingsRestored by remember { mutableStateOf(false) }
    var danmuConfig by remember { mutableStateOf(DanmuConfig()) }
    LaunchedEffect(Unit) {
        if (isMemoryModeEnabled) {
            danmuConfig = DanmuConfig(
                isEnabled = prefs.getBoolean("danmu_enabled", false),
                // v2.4.41（弹幕审查 #14）：Key 落盘加密（历史明文值原样返回，平滑迁移）
            apiKey = DanmuKeyStore.decrypt(prefs.getString("danmu_api_key", "") ?: ""),
                baseUrl = prefs.getString("danmu_base_url", DanmuConfig.DEFAULT_BASE_URL)
                    ?: DanmuConfig.DEFAULT_BASE_URL,
                modelName = prefs.getString("danmu_model_name", DanmuConfig.DEFAULT_MODEL)
                    ?: DanmuConfig.DEFAULT_MODEL,
                personaPrompt = prefs.getString("danmu_persona", DanmuConfig.DEFAULT_PERSONA)
                    ?: DanmuConfig.DEFAULT_PERSONA,
                intervalSec = prefs.getInt("danmu_interval_sec", DanmuConfig.DEFAULT_INTERVAL_SEC),
                batchSize = prefs.getInt("danmu_batch_size", DanmuConfig.DEFAULT_BATCH_SIZE),
                speedPxPerSec = prefs.getInt("danmu_speed", DanmuConfig.DEFAULT_SPEED_PX_PER_SEC),
                maxTracks = prefs.getInt("danmu_max_tracks", DanmuConfig.DEFAULT_MAX_TRACKS),
                // v2.4.10：行数自适应（默认开启）
                autoTracks = prefs.getBoolean("danmu_auto_tracks", DanmuConfig.DEFAULT_AUTO_TRACKS),
                // v2.4.11：传给模型的图长边（默认 256）
                imageMaxLongSide = prefs.getInt(
                    "danmu_image_long_side", DanmuConfig.DEFAULT_IMAGE_LONG_SIDE
                ),
                // v2.4.12：本地 LLM 生成弹幕
                useLocalLlm = prefs.getBoolean("danmu_use_local_llm", false),
                opacityPercent = prefs.getInt("danmu_opacity", DanmuConfig.DEFAULT_OPACITY),
                fontSizeSp = prefs.getInt("danmu_font_size", DanmuConfig.DEFAULT_FONT_SIZE_SP),
                // v2.3.0：全局颜色（存 id，不存 ordinal）
                textColorId = prefs.getInt("danmu_text_color", DanmuConfig.DEFAULT_TEXT_COLOR_ID),
                strokeId = prefs.getInt("danmu_stroke", DanmuConfig.DEFAULT_STROKE_ID),
                bgId = prefs.getInt("danmu_bg", DanmuConfig.DEFAULT_BG_ID),
                // v2.4.6：颜色模式（存 id，不存 ordinal）
                colorModeId = prefs.getInt(
                    "danmu_color_mode", DanmuConfig.DEFAULT_COLOR_MODE_ID
                ),
                // v2.4.1：素材来源（存枚举 id，不存 ordinal）
                sourceModeId = prefs.getInt(
                    "danmu_source_mode", DanmuConfig.DEFAULT_SOURCE_MODE_ID
                ),
                // v2.4.7：内容来源（AI / 导入）
                sourceTypeId = prefs.getInt(
                    "danmu_source_type", DanmuConfig.DEFAULT_SOURCE_TYPE_ID
                ),
                // v2.4.7：导入文件（只存 URI 与显示名，**不存内容** —— 文件可达数 MB）
                importedUri = prefs.getString("danmu_import_uri", "").orEmpty(),
                importedName = prefs.getString("danmu_import_name", "").orEmpty(),
                // v2.4.7：批次时间抖动强度（ms）
                timeJitterMs = prefs.getInt(
                    "danmu_time_jitter", DanmuConfig.DEFAULT_TIME_JITTER_MS_INT
                )
            )
        }
        danmuSettingsRestored = true
    }
    // ⚠️ 三处同步之「写回」与「remove」——详见 docs/DANMUAI_PLAN_C_IMPLEMENTATION §P1
    LaunchedEffect(
        danmuSettingsRestored,
        isMemoryModeEnabled,
        danmuConfig
    ) {
        if (!danmuSettingsRestored) return@LaunchedEffect
        prefs.edit().apply {
            if (isMemoryModeEnabled) {
                putBoolean("danmu_enabled", danmuConfig.isEnabled)
                // v2.4.41（弹幕审查 #14）：加密后再落盘（密文带 `enc1:` 前缀）
            putString("danmu_api_key", DanmuKeyStore.encrypt(danmuConfig.apiKey))
                putString("danmu_base_url", danmuConfig.baseUrl)
                putString("danmu_model_name", danmuConfig.modelName)
                putString("danmu_persona", danmuConfig.personaPrompt)
                putInt("danmu_interval_sec", danmuConfig.intervalSec)
                putInt("danmu_batch_size", danmuConfig.batchSize)
                putInt("danmu_speed", danmuConfig.speedPxPerSec)
                putInt("danmu_max_tracks", danmuConfig.maxTracks)
                putBoolean("danmu_auto_tracks", danmuConfig.autoTracks)
                putInt("danmu_image_long_side", danmuConfig.imageMaxLongSide)
                putBoolean("danmu_use_local_llm", danmuConfig.useLocalLlm)
                putInt("danmu_opacity", danmuConfig.opacityPercent)
                putInt("danmu_font_size", danmuConfig.fontSizeSp)
                putInt("danmu_text_color", danmuConfig.textColorId)
                putInt("danmu_stroke", danmuConfig.strokeId)
                putInt("danmu_bg", danmuConfig.bgId)
                putInt("danmu_color_mode", danmuConfig.colorModeId)
                putInt("danmu_source_mode", danmuConfig.sourceModeId)
                // v2.4.7：内容来源 + 导入文件 + 抖动强度
                putInt("danmu_source_type", danmuConfig.sourceTypeId)
                putString("danmu_import_uri", danmuConfig.importedUri)
                putString("danmu_import_name", danmuConfig.importedName)
                putInt("danmu_time_jitter", danmuConfig.timeJitterMs)
            } else {
                remove("danmu_enabled")
                remove("danmu_api_key")
                remove("danmu_base_url")
                remove("danmu_model_name")
                remove("danmu_persona")
                remove("danmu_interval_sec")
                remove("danmu_batch_size")
                remove("danmu_speed")
                remove("danmu_max_tracks")
                remove("danmu_auto_tracks")
                remove("danmu_image_long_side")
                remove("danmu_use_local_llm")
                remove("danmu_opacity")
                remove("danmu_font_size")
                remove("danmu_text_color")
                remove("danmu_stroke")
                remove("danmu_bg")
                remove("danmu_color_mode")
                remove("danmu_source_mode")
                // v2.4.7
                remove("danmu_source_type")
                remove("danmu_import_uri")
                remove("danmu_import_name")
                remove("danmu_time_jitter")
            }
            apply()
        }
    }

    // v126：实时 AI 字幕引擎（方案文档「边播边生成」，不写 SRT 文件）
    val realtimeSubtitleEngine = remember { RealtimeSubtitleEngine(context) }
    val teeAudioProcessor = remember(realtimeSubtitleEngine) {
        androidx.media3.exoplayer.audio.TeeAudioProcessor(realtimeSubtitleEngine.liveAudioFeed)
    }
    var isRealtimeSubtitleEnabled by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("realtime_subtitle_enabled", false) else false
        )
    }
    /**
     * v2.6.24：**后台执行 AI 字幕**（不显示字幕，只给 AI 弹幕供数据）。
     *
     * 为什么需要它：AI 弹幕的素材就是"当前播放位置附近的台词"，而台词来自实时 ASR。
     * 但很多用户并不想在这种场景下看字幕（VR 沉浸、字幕挡画面），于是出现
     * 「想看 AI 弹幕就不得不开着字幕」的矛盾。这个开关把「跑识别」与「显示字幕」
     * 拆开：识别照跑、`realtimeCues` 照常填充（弹幕链路直接消费），只是渲染层不取它。
     */
    var bgAsrEnabled by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("bg_asr_enabled", false) else false
        )
    }
    /**
     * v2.6.24：**识别引擎的开关判据**（单一来源）。
     *
     * 凡是「要不要跑 ASR / 要不要推进游标 / 台词算不算可用」都读它；
     * 只有**渲染**相关的地方才继续读 [isRealtimeSubtitleEnabled]。
     * ⚠️ 不要再在各处写 `isRealtimeSubtitleEnabled || bgAsrEnabled` —— 那是同一判据两处登记，
     *    以后再加第三种模式必然漏改。
     */
    val isRealtimeAsrActive = isRealtimeSubtitleEnabled || bgAsrEnabled
    var realtimeCues by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    var realtimeSubtitleStatus by remember { mutableStateOf("") }
    // v127b：实时字幕生成进度（供进度条与"完成"提示）
    var realtimeTotalMs by remember { mutableLongStateOf(0L) }
    var realtimeGeneratedMs by remember { mutableLongStateOf(0L) }
    var realtimeDone by remember { mutableStateOf(false) }
    // v127：退出页面时停止实时字幕引擎（native 资源由引擎协程自行释放）
    DisposableEffect(Unit) {
        onDispose { realtimeSubtitleEngine.stop() }
    }
    // v127：只剩 SenseVoice 一条路线（Vosk / Qwen3 / QNN 已移除）
    val asrEngineType = AsrEngineType.SENSEVOICE
    // v111：sherpa 引擎语言选择（中/英/日/韩/自动）
    // v127f：识别语言持久化。此前只存内存状态，重启应用就回到「自动」，
    // 用户会觉得"语言选了没用"。
    var sherpaLangCode by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getString("sherpa_lang_code", "auto") ?: "auto" else "auto"
        )
    }
    // v2.0.208：模型选择版本号在 SherpaAsrManager.modelChoiceVersion（object 级状态）——
    // 语言 chip 有两处渲染（设置面板 + 字幕快捷面板），版本号必须全局共享才能两处联动重建。
    // （本地 remember 版本号在快捷面板触发时不生效，已废弃。）
    // v127e：SenseVoice 推理线程数（1~10，推荐 4~6）
    var asrThreads by remember {
        mutableIntStateOf(
            if (isMemoryModeEnabled) prefs.getInt("asr_threads", SherpaAsrManager.DEFAULT_THREADS)
            else SherpaAsrManager.DEFAULT_THREADS
        )
    }
    var isBatchTranscribing by remember { mutableStateOf(false) }
    var batchTranscribeProgress by remember { mutableFloatStateOf(0f) }
    var batchTranscribeStatus by remember { mutableStateOf("") }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var exoCueText by remember { mutableStateOf<String?>(null) }

    // Launcher for selecting external .srt / .vtt subtitle file
    val subtitleFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val inputStream = context.contentResolver.openInputStream(uri)
                    // ⚠️ v2.1.248：**必须读 ByteArray 再自己解码**，不能用
                    //    `bufferedReader()` —— 那走平台默认字符集（UTF-8），
                    //    GBK/Big5 的老字幕会静默变成乱码。见 SubtitleParser.decodeBytes。
                    val bytes = inputStream?.use { it.readBytes() } ?: ByteArray(0)
                    val content = SubtitleParser.decodeBytes(bytes)
                    val cues = SubtitleParser.parse(content)
                    withContext(Dispatchers.Main) {
                        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "外部字幕"
                        loadedSubtitleFileName = name
                        // ⚠️ v2.1.248：**解析出 0 条时必须明确告知**，不能静默。
                        //    此前 `.ass` 走到这里返回空列表、却照样弹「已加载 0 句」，
                        //    用户不知道是格式不支持还是文件坏了。
                        if (cues.isEmpty()) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_subtitle_empty_format, name),
                                Toast.LENGTH_LONG
                            ).show()
                            return@withContext
                        }
                        loadedSubtitleCues = cues
                        Toast.makeText(context, context.getString(R.string.toast_subtitle_loaded, cues.size), Toast.LENGTH_SHORT).show()
                        if (subtitleTranslator.config.isEnabled) {
                            subtitleTranslator.translateCuesBatch(cues)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VRPlayerScreen", "Error reading subtitle file", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.toast_subtitle_load_failed, (e.message ?: "")), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * v127f：切换识别语言。
     *
     * 语言是创建识别器时的参数，改了必须重建引擎才生效，而重建要重新加载
     * 238MB 模型（约 5 秒）。这里统一处理：写 prefs → 给出明确反馈 →
     * 由 LaunchedEffect(sherpaLangCode) 负责重启引擎。
     */
    fun changeAsrLanguage(code: String) {
        if (code == sherpaLangCode) return
        sherpaLangCode = code
        if (isMemoryModeEnabled) prefs.edit().putString("sherpa_lang_code", code).apply()
        val label = SherpaAsrManager.sherpaLanguages.firstOrNull { it.code == code }?.let { context.getString(it.labelResId) } ?: code
        Toast.makeText(
            context,
            if (isRealtimeAsrActive) context.getString(R.string.asr_lang_switch_hint, label) else context.getString(R.string.asr_lang_label, label),
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * v127e：导出当前字幕为 SRT。
     *
     * 此前 SubtitleSettingsPanel 的 onExportSubtitle 从未接线（点了没反应），
     * 且原实现依赖整片转写生成的临时文件——该链路已随实时字幕方案移除。
     * 现在直接从内存字幕缓存导出：实时字幕与手动加载的字幕都能导出。
     */
    fun exportSubtitleSrt() = VRPlayerTrackAndAsrOps.exportSubtitleSrt(
        context = context,
        isRealtimeAsrActive = isRealtimeAsrActive,
        realtimeCues = realtimeCues,
        loadedSubtitleCues = loadedSubtitleCues,
        subtitleTranslator = subtitleTranslator,
        selectedMediaItem = selectedMediaItem,
        isStripSubtitlePunctuation = isStripSubtitlePunctuation,
    )

    fun regenerateRealtimeSubtitle() = VRPlayerTrackAndAsrOps.regenerateRealtimeSubtitle(
        context = context,
        isRealtimeAsrActive = isRealtimeAsrActive,
        selectedMediaItem = selectedMediaItem,
        realtimeSubtitleEngine = realtimeSubtitleEngine,
        onResetRealtime = {
            realtimeCues = emptyList()
            realtimeDone = false
        },
    )

    fun loadSavedSubtitleFile(f: File) = VRPlayerTrackAndAsrOps.loadSavedSubtitleFile(
        context = context,
        prefs = prefs,
        scope = scope,
        f = f,
        isMemoryModeEnabled = isMemoryModeEnabled,
        onLoaded = { cues, name ->
            loadedSubtitleCues = cues
            loadedSubtitleFileName = name
            isSubtitleEnabled = true
            isRealtimeSubtitleEnabled = false
        },
    )

    fun startBatchTranscribe() {
        // v127：整片转写已停用，改为边播边生成的实时字幕（RealtimeSubtitleEngine）。
    }



    var isFloatingBallPressed by remember { mutableStateOf(false) }
    // v104：LUT 视频滤镜状态
    // ⚠️ v2.6.16：这两个状态此前只是 `remember`，没有持久化 —— 每次进播放器都回落到
    //    「无滤镜 + 0.8」，用户上次选的滤镜直接丢失。现在与美颜参数同组始终落盘。
    //    `lut_name` 存的是**内置款的 fileName**（不是本地化名，避免切语言后对不上），
    //    无滤镜 / 自定义款一律存空串：自定义款只记文件名、原始 Uri 权限会失效，
    //    恢复时必然「名字在、纹理没了」，索性不落盘。
    var lutName by remember {
        mutableStateOf(prefs.getString("lut_name", "").orEmpty())
    }
    var lutMix by remember {
        mutableFloatStateOf(prefs.getFloat("lut_mix", LutUtils.DEFAULT_MIX))
    }
    var isLutLoading by remember { mutableStateOf(false) }     // 解析中
    // v91：主界面字幕快捷面板
    var isSubtitleQuickPanelOpen by remember { mutableStateOf(false) }
    // v91：设置面板左列折叠分组展开状态（需在外层供快捷面板引用）
    var expandedSettings by remember { mutableStateOf(setOf("theme")) }
    // v2.6.7：从设置面板区前置到这里 —— 局部函数不能前向引用，
    // 而 SettingsGroup 的调用点在它原本的定义之前。
    fun toggleSettings(key: String) {
    expandedSettings = if (key in expandedSettings) expandedSettings - key else expandedSettings + key
    }

    // v2.1.231：关闭「设置记忆」时清掉悬浮球位置存档（与项目「关闭即 remove」的约定一致）。
    // 位置是组件自己在拖动抬手时直接 commit 的，不走下面那个大写回 effect，所以这里单独清。
    LaunchedEffect(isMemoryModeEnabled) {
        if (!isMemoryModeEnabled) FloatingBallPositions.clearAll(prefs)
    }

    // Dynamic Reactive Settings Memory Auto-Persistence Task
    // v2.0.172：补齐此前缺失的 key —— 快进/后退悬浮球开关与步长、美颜总开关/引擎/GPUPixel
    // 参数、磨皮质感、ASR 线程数、字幕去标点。这些状态此前**不在 key 列表里**，只改它们
    // 不触发写回 effect → prefs 从未落盘，重启后丢失（表现为"开关不记忆"）。
    LaunchedEffect(
        isMemoryModeEnabled,
        projectionMode,
        fisheyeFovDeg,
        stereoMode,
        beautyLevel,
        beautyTextureDetail,
        beautyMasterEnabled,
        beautyEngineType,
        gpuPixelVrFaceBeauty,
        gpuPixelHalfResBeauty,
        // v2.0.188 审计修复：beautyPreset 此前不在 key 列表 → 切换美颜预设不会触发写回
        // effect，导致 beauty_preset_id 永不落盘（重启后预设丢失）。
        beautyPreset,
        brightnessLevel,
        contrastLevel,
        beautyWhitening,
        beautyFaceSlimming,
        beautyBigEyes,
        beautyDarkCircles,
        beautyNoseSlimming,
        beautyMouth,
        beautyTeethWhitening,
        beautyLipstick,
        beautyBlush,
        beautyEyebrows,
        beautyLongLegs,
        beautySmallHead,
        isSplitScreenVR,
        isGyroEnabled,
        asrThreads,
        gyroInverted,
        fovDeg,
        isVideoMirrored,
        domeHalfSelect,
        warpMode,
        isAudioMirrored,
        videoCurvature,
        isCardboardDistortionEnabled,
        vrCinemaScale,
        isGazePointerEnabled,
        isEyesSwapped,
        maxResolution,
        isFloatingBallEnabled,
        isSeekForwardBallEnabled,
        isSeekBackwardBallEnabled,
        isMarkerBallEnabled,
        // v2.4.67：信息球开关 ⚠️ 必须进 key 列表，否则切换后 prefs 永不落盘（本项目头号坑）
        isClockBallEnabled,
        isBatteryBallEnabled,
        // v2.6.4：播放控制球 / 性能球 ⚠️ 同样必须进 key 列表，否则切换后不落盘
        isPlayBallEnabled,
        isPerfBallEnabled,
        seekForwardStep,
        seekBackwardStep,
        floatingBallSpeed,
        basePlaybackSpeed,
        maxFps,
        // v2.0.206：画质增强（MEMC / FSR）—— ⚠️ 漏加进 key 列表 = 改这些状态不会触发
        // 写回 effect，prefs 永不落盘（用户看到的就是「改了但重启就丢」）。
        isMemcEnabled,
        memcTargetFps,
        isFsrEnabled,
        fsrRuleMode,
        fsrCustomTarget,
        isSoftwareDecoding,
        decoderEngine,
        // v2.1.233：IJK 参数 ⚠️ 必须进 key 列表，否则改了不落盘（同上注释）
        ijkOptions,
        // v2.1.234：MPV 参数（同上）
        mpvOptions,
        isSubtitleEnabled,
        isStripSubtitlePunctuation,
        subtitleFont,
        subtitleFontSizeSp,
        subtitleFontWeightVal,
        isSubtitleItalic,
        subtitleColorOpt,
        subtitleTextAlpha,
        subtitleStrokeOpt,
        subtitleBgOpt,
        subtitleOffsetYRatio,
        subtitleOffsetXRatio,
        subtitleDelayMs,
        subtitleTextAlignOpt,
        vrIpdOffsetRatio,
        subtitleMaxLines,
        forceVideoType,
        levelPatchEnabled,
        level51Enabled,
        forceHwDecoderEnabled,
        spoofResolutionEnabled,
        downscaleOutputEnabled,
        addCodecParamsEnabled,
        autoFallbackSoftEnabled,
        beautyPreset,
        // v2.6.16：LUT 滤镜（漏加进 key 列表 = 改了永不落盘，本项目头号坑）
        lutName,
        lutMix,
        // v2.4.58：投屏接收开关（漏加进 key 列表 → 改了不落盘，重启即丢）
        castReceiverEnabled,
        // v2.6.24：直播延迟（秒）
        liveDelaySec
    ) {
        // v2.0.144：美颜预设按稳定 id 落盘（本地化名只用于显示与匹配）。
        // 名称无法识别（如切语言后残留旧语言名）时返回 -1，此时**不改动已存值**，
        // 避免把预设误写成「自定义」。
        val beautyPresetId = when (beautyPreset) {
            BEAUTY_PRESET_NATURAL -> 0
            BEAUTY_PRESET_LIGHT -> 1
            BEAUTY_PRESET_HEAVY -> 2
            BEAUTY_PRESET_CUSTOM -> 3
            else -> -1
        }
        prefs.edit().apply {
            // v2.6.7：记忆模式强制开启，这里显式写 true（顺手纠正旧设备上的 false 值）
            putBoolean("is_memory_mode_enabled", true)
            // v2.4.35（#13）：美颜参数与记忆模式解耦 —— 以下美颜 key 始终落盘，不受 isMemoryModeEnabled 影响
            putInt("beauty_engine_type", beautyEngineType)
            putBoolean("beauty_master_enabled", beautyMasterEnabled)
            putFloat("beauty_level", beautyLevel)
            putFloat("beauty_texture_detail", beautyTextureDetail)
            putBoolean("beauty_gp_vr_face", gpuPixelVrFaceBeauty)
            putBoolean("beauty_gp_half_res", gpuPixelHalfResBeauty)
            putFloat("beauty_whitening", beautyWhitening)
            putFloat("beauty_face_slimming", beautyFaceSlimming)
            putFloat("beauty_big_eyes", beautyBigEyes)
            putFloat("beauty_dark_circles", beautyDarkCircles)
            putFloat("beauty_nose_slimming", beautyNoseSlimming)
            putFloat("beauty_mouth", beautyMouth)
            putFloat("beauty_teeth_whitening", beautyTeethWhitening)
            putFloat("beauty_lipstick", beautyLipstick)
            putFloat("beauty_blush", beautyBlush)
            putFloat("beauty_eyebrows", beautyEyebrows)
            putFloat("beauty_long_legs", beautyLongLegs)
            putFloat("beauty_small_head", beautySmallHead)
            // v2.6.16：LUT 滤镜。与美颜参数同组「始终落盘」。
            // 只存内置款的 fileName；无滤镜 / 自定义款存空串 —— 自定义款只记文件名、
            // 原始 Uri 读权限会失效，恢复时必然「名字在、纹理没了」，不如不存。
            putString("lut_name", if (LutUtils.findBuiltin(lutName) != null) lutName else "")
            putFloat("lut_mix", lutMix)
            if (beautyPresetId >= 0) putInt("beauty_preset_id", beautyPresetId)
            if (isMemoryModeEnabled) {
                putBoolean("cast_receiver_enabled", castReceiverEnabled)
                putInt("projection_mode", projectionMode.id)
                // v2.1.211：鱼眼视场角（与投影模式同组持久化）
                putInt("fisheye_fov_deg", fisheyeFovDeg)
                putInt("stereo_mode", stereoMode.id)
                putFloat("brightness_level", brightnessLevel)
                putFloat("contrast_level", contrastLevel)
                putBoolean("is_split_screen_vr", isSplitScreenVR)
                putBoolean("is_gyro_enabled", isGyroEnabled)
                putInt("asr_threads", asrThreads)
                putBoolean("gyro_inverted", gyroInverted)
                putFloat("fov_deg", fovDeg)
                putBoolean("is_video_mirrored", isVideoMirrored)
                putInt("dome_half_select", domeHalfSelect)
                putInt("warp_mode", warpMode.id)
                putBoolean("is_audio_mirrored", isAudioMirrored)
                putFloat("video_curvature", videoCurvature)
                putBoolean("cardboard_anti_distortion", isCardboardDistortionEnabled)
                putFloat("vr_cinema_scale", vrCinemaScale)
                putBoolean("gaze_pointer_enabled", isGazePointerEnabled)
                putBoolean("is_eyes_swapped", isEyesSwapped)
                putInt("max_resolution_id", maxResolution.id)
                putBoolean("is_floating_ball_enabled", isFloatingBallEnabled)
                putBoolean("is_seek_forward_ball_enabled", isSeekForwardBallEnabled)
                putBoolean("is_seek_backward_ball_enabled", isSeekBackwardBallEnabled)
                // ⚠️ 新增持久化项必须**同时**改三处：key 列表 + 这里的 put + else 分支的 remove。
                //    漏了 key 会导致永不落盘（本项目头号坑）。
                putBoolean("is_marker_ball_enabled", isMarkerBallEnabled)
                putBoolean("is_clock_ball_enabled", isClockBallEnabled)
                putBoolean("is_battery_ball_enabled", isBatteryBallEnabled)
                putBoolean("is_play_ball_enabled", isPlayBallEnabled)
                putBoolean("is_perf_ball_enabled", isPerfBallEnabled)
                putInt("seek_forward_step", seekForwardStep)
                putInt("seek_backward_step", seekBackwardStep)
                putFloat("floating_ball_speed", floatingBallSpeed)
                putFloat("base_playback_speed", basePlaybackSpeed)
                putInt("max_fps", maxFps)
                // v2.0.206：画质增强（MEMC / FSR）
                putBoolean("memc_enabled", isMemcEnabled)
                putInt("memc_target_fps", memcTargetFps)
                putBoolean("fsr_enabled", isFsrEnabled)
                // 枚举一律存 id 字符串，绝不存 ordinal（增删枚举会错位）
                putString("fsr_rule_mode", fsrRuleMode.id)
                putString("fsr_target_resolution", fsrCustomTarget.id)
                putBoolean("is_software_decoding", isSoftwareDecoding)
                putInt("decoder_engine_id", decoderEngine.id)
                // v2.1.233：IJK 内核参数（与 decoderEngine 一起落盘）
                putBoolean("ijk_mediacodec", ijkOptions.mediaCodec)
                putBoolean("ijk_framedrop", ijkOptions.frameDrop)
                putBoolean("ijk_accurate_seek", ijkOptions.accurateSeek)
                putBoolean("ijk_soundtouch", ijkOptions.soundTouch)
                putLong("ijk_max_buffer", ijkOptions.maxBufferBytes)
                putLong("ijk_probe_size", ijkOptions.probeSizeBytes)
                putLong("ijk_skip_loop_filter", ijkOptions.skipLoopFilter)
                putLong("ijk_threads", ijkOptions.threads)
                putBoolean("ijk_fast_decode", ijkOptions.fastDecode)
                // v2.1.234：MPV 内核参数
                putBoolean("mpv_hwdec", mpvOptions.hwdec)
                putBoolean("mpv_framedrop", mpvOptions.frameDrop)
                putInt("mpv_cache_mb", mpvOptions.cacheMb)
                putInt("mpv_threads", mpvOptions.threads)
                putBoolean("mpv_fast_decode", mpvOptions.fastDecode)
                putBoolean("is_subtitle_enabled", isSubtitleEnabled)
                putBoolean("subtitle_strip_punct", isStripSubtitlePunctuation)
                putBoolean("subtitle_fade_in", subtitleFadeIn)
                putInt("subtitle_font_id", subtitleFont.id)
                putInt("subtitle_font_size", subtitleFontSizeSp)
                putInt("subtitle_font_weight", subtitleFontWeightVal)
                putBoolean("is_subtitle_italic", isSubtitleItalic)
                putInt("subtitle_color_id", subtitleColorOpt.id)
                putFloat("subtitle_text_alpha", subtitleTextAlpha)
                putInt("subtitle_stroke_id", subtitleStrokeOpt.id)
                putInt("subtitle_bg_id", subtitleBgOpt.id)
                putFloat("subtitle_offset_y", subtitleOffsetYRatio)
                putFloat("subtitle_offset_x", subtitleOffsetXRatio)
                putLong("subtitle_delay_ms", subtitleDelayMs)
                putInt("live_delay_sec", liveDelaySec)
                putInt("subtitle_align_id", subtitleTextAlignOpt.id)
                putFloat("vr_ipd_offset", vrIpdOffsetRatio)
                putInt("subtitle_max_lines", subtitleMaxLines)
                putInt("force_video_type", forceVideoType)
                putBoolean("level_patch_enabled", levelPatchEnabled)
                putBoolean("level51_enabled", level51Enabled)
                putBoolean("force_hw_decoder_enabled", forceHwDecoderEnabled)
                putBoolean("spoof_resolution_enabled", spoofResolutionEnabled)
                putBoolean("downscale_output_enabled", downscaleOutputEnabled)
                putBoolean("add_codec_params_enabled", addCodecParamsEnabled)
                putBoolean("auto_fallback_soft_enabled", autoFallbackSoftEnabled)
            } else {
                // v2.0.206：画质增强的 key —— 必须与上面的写回成对出现，
                // 否则关闭记忆模式后旧值会残留在 prefs，下次开启被「恢复」成过期状态。
                remove("memc_enabled")
                remove("memc_target_fps")
                remove("fsr_enabled")
                remove("fsr_rule_mode")
                remove("fsr_target_resolution")
                remove("projection_mode")
                // ⚠️ 三处必须同步：读取(prefs.getInt) + 写回(putInt) + 这里的 remove。
                //    漏掉本行会在「关闭记忆模式」后残留旧值（本项目头号坑）。
                remove("fisheye_fov_deg")
                remove("stereo_mode")
                remove("brightness_level")
                remove("contrast_level")
                remove("is_split_screen_vr")
                remove("is_gyro_enabled")
                remove("fov_deg")
                remove("is_video_mirrored")
                remove("dome_half_select")
                remove("warp_mode")
                remove("is_cylinder_enabled")
                remove("is_audio_mirrored")
                remove("video_curvature")
                remove("cardboard_anti_distortion")
                remove("vr_cinema_scale")
                remove("gaze_pointer_enabled")
                remove("is_eyes_swapped")
                remove("max_resolution_id")
                remove("is_floating_ball_enabled")
                remove("floating_ball_speed")
                remove("base_playback_speed")
                // v2.6.4：补齐各悬浮球开关的 remove —— 这三个 key（marker/clock/battery）
                // 自 v2.1.208 / v2.4.67 加入起就只有「读取 + 写回」两处，漏了本分支的 remove，
                // 导致「关闭记忆模式」后旧值残留在 prefs，下次开启被恢复成过期状态。
                remove("is_marker_ball_enabled")
                remove("is_clock_ball_enabled")
                remove("is_battery_ball_enabled")
                remove("is_play_ball_enabled")
                remove("is_perf_ball_enabled")
                // v2.6.7：补齐快进/后退球开关与步长（与上面同批漏掉的另外 4 个 key）
                remove("is_seek_forward_ball_enabled")
                remove("is_seek_backward_ball_enabled")
                remove("seek_forward_step")
                remove("seek_backward_step")
                remove("max_fps")
                remove("is_software_decoding")
                remove("decoder_engine_id")
                // v2.1.233：IJK 参数 key —— 必须与上面的 put 成对，否则关闭记忆模式后
                // 旧值残留，下次开启被「恢复」成过期设置（本项目头号坑）。
                remove("ijk_mediacodec")
                remove("ijk_framedrop")
                remove("ijk_accurate_seek")
                remove("ijk_soundtouch")
                remove("ijk_max_buffer")
                remove("ijk_probe_size")
                remove("ijk_skip_loop_filter")
                remove("ijk_threads")
                remove("ijk_fast_decode")
                // v2.1.234：MPV 参数 key（同上，必须与 put 成对）
                remove("mpv_hwdec")
                remove("mpv_framedrop")
                remove("mpv_cache_mb")
                remove("mpv_threads")
                remove("mpv_fast_decode")
                remove("is_subtitle_enabled")
                remove("subtitle_strip_punct")
                remove("subtitle_font_id")
                remove("subtitle_font_size")
                remove("subtitle_font_weight")
                remove("is_subtitle_italic")
                remove("subtitle_color_id")
                remove("subtitle_text_alpha")
                remove("subtitle_stroke_id")
                remove("subtitle_bg_id")
                remove("subtitle_offset_y")
                remove("subtitle_offset_x")
                remove("subtitle_delay_ms")
                remove("live_delay_sec")
                remove("subtitle_align_id")
                remove("vr_ipd_offset")
                remove("subtitle_max_lines")
                remove("force_video_type")
                remove("level_patch_enabled")
                remove("level51_enabled")
                remove("force_hw_decoder_enabled")
                remove("spoof_resolution_enabled")
                remove("downscale_output_enabled")
                remove("add_codec_params_enabled")
                remove("auto_fallback_soft_enabled")
                remove("asr_language_id")
                remove("asr_model_size")
            }
            apply()
        }
    }

    // Dynamically sync audio processor with independent state
    LaunchedEffect(isAudioMirrored) {
        audioProcessor.isSwappingEnabled = isAudioMirrored
    }

    // Automatically synchronize audio mirror with video mirror changes
    LaunchedEffect(isVideoMirrored) {
        isAudioMirrored = isVideoMirrored
    }

    // UI Auto-Hide Timeout tracking (hides controls after 2 seconds of inactivity)
    var isUiVisible by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isUiLocked by remember { mutableStateOf(false) }
    var isSeekingActive by remember { mutableStateOf(false) }
    var seekStartProgress by remember { mutableFloatStateOf(0f) }
    var seekStartValue by remember { mutableFloatStateOf(0f) }

    // 主题系统（8/2 功能；v2.9.2：16 套静态 MD 配色 + 1 套 Monet 动态取色 + 玻璃模式）
    var uiThemeId by remember { mutableStateOf(UiThemes.loadThemeId(prefs)) }
    var glassMode by remember { mutableStateOf(UiThemes.loadGlassMode(prefs)) }
    // v2.9.2：统一走 resolve —— Monet 模式从系统壁纸动态取色，其余走静态预设。
    // 刻意不用 remember 缓存：每次重组重算，壁纸变化才能自动跟随；monetPalette 内部有
    // 值缓存，值不变时复用同一对象引用，不会因「每帧 new 一个对象」误触发下游重组。
    val uiTheme = UiThemes.resolve(context, uiThemeId)
    val ThemeBgColor = uiTheme.bg
    val ThemePanelBgColor = when (glassMode) {
        1 -> uiTheme.panelBg.copy(alpha = 0.70f)
        else -> uiTheme.panelBg
    }
    val AccentColor = uiTheme.accent // Theme accent active color
    val AccentOnColor = uiTheme.accentOn // Contrast text color
    val TranslucentWhite10 = uiTheme.translucentWhite10
    val TranslucentWhite20 = uiTheme.translucentWhite20
    val TextLightColor = uiTheme.textLight
    val TextSoftColor = uiTheme.textSoft
    val glassPanelBorder = if (glassMode != 0) Color.White.copy(alpha = 0.28f) else Color(0x11FFFFFF)

    // Reset interaction clock to keep UI visible for another 2 seconds
    fun keepUiAlight() {
        lastInteractionTime = System.currentTimeMillis()
        if (!isUiVisible) {
            isUiVisible = true
        }
    }

    // 切换 UI 可见性（经典播放器行为：点击视频区域切换控制栏显示/隐藏）
    // v91: 鍚庡彴杞啓鍖哄潡锛堣缃潰鏉夸笌涓荤晫闈㈠瓧骞曞揩鎹烽潰鏉垮叡鐢級
    fun toggleUiVisibility() {
        isUiVisible = !isUiVisible
        if (isUiVisible) {
            keepUiAlight()
        }
    }

    // 获取当前媒体的实际文件名（占位标题如"【导入视频】…"时从 uri 提取真实文件名）
    fun getMediaDisplayName(): String {
        val title = selectedMediaItem.title
        // 非占位标题（demo 列表名等）直接返回
        if (!title.startsWith("【")) return title
        val uriStr = selectedMediaItem.uri ?: return title
        return try {
            val u = Uri.parse(uriStr)
            when (u.scheme?.lowercase()) {
                "content" -> {
                    var name: String? = null
                    context.contentResolver.query(
                        u,
                        arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
                        null, null, null
                    )?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
                            if (idx >= 0) name = c.getString(idx)
                        }
                    }
                    name ?: u.lastPathSegment?.substringAfterLast('/') ?: title
                }
                "file" -> u.lastPathSegment?.substringAfterLast('/') ?: title
                else -> u.lastPathSegment?.substringAfterLast('/') ?: title
            }
        } catch (e: Exception) {
            title
        }
    }

    // Lock orientation programmatically and allow ONLY manual switches
    val activity = context as? android.app.Activity
    LaunchedEffect(isLandscape) {
        activity?.requestedOrientation = if (isLandscape) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    // Automatically hide status bar and navigation bar (the white bar) for immersive playback
    LaunchedEffect(activity) {
        val window = activity?.window
        if (window != null) {
            val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // Monitoring idle timer: if user is touching/sliding, never hide the UI menu!
    // 注意：拖动中（isUserTouching=true）必须保持当前状态（隐藏），
    // 不能 keepUiAlight（它会把隐藏的 UI 重新显示，且更新 lastInteractionTime 导致本效应无限重启）。
    LaunchedEffect(lastInteractionTime, isUserTouching) {
        if (isUserTouching) {
            return@LaunchedEffect
        }
        delay(2500L) // 2.5 seconds
        if (!isUserTouching && System.currentTimeMillis() - lastInteractionTime >= 2500L) {
            isUiVisible = false
        }
    }

    // Monitor external video intent loads from third-party applications
    LaunchedEffect(initialVideoUri) {
        if (!initialVideoUri.isNullOrEmpty()) {
            val realName = Uri.parse(initialVideoUri).lastPathSegment
                ?.substringAfterLast('/')
                ?.substringBeforeLast('.')
                ?.takeIf { it.isNotBlank() } ?: context.getString(R.string.media_external_video)
            val customItem = MediaItem(
                id = "imported_" + System.currentTimeMillis(),
                title = realName,
                uri = initialVideoUri,
                isVideo = true,
                isDemo = false,
                description = context.getString(R.string.media_external_desc)
            )
            selectedMediaItem = customItem
            projectionMode = ProjectionMode.STANDARD
            stereoMode = StereoMode.MONO
            photoReloadTrigger++
            onExternalUriConsumed?.invoke()
        }
    }

    // ==================================================================================
    // v2.4.58：**投屏接收（DLNA/UPnP-AV MediaRenderer）** 与应用播放链路的对接
    // ----------------------------------------------------------------------------------
    // 三条链路（全部走 CastMediaBus，见 com.example.vr.cast.CastMediaBus）：
    //  1) 收到投屏 URL → 复用「外部视频意图」同一条链路（构造 MediaItem 赋给 selectedMediaItem）
    //  2) DMC 下发的 Play/Pause/Stop/Seek → 作用于现有播放器门面 playerInstance
    //  3) 播放进度回写 → 供 DMC 轮询 GetPositionInfo 显示进度
    // 好处：投影模式、字幕、美颜、VR 渲染等全部**自动继承**，无需为投屏单独写播放逻辑。
    // ==================================================================================
    LaunchedEffect(Unit) {
        CastMediaBus.requests.collect { req ->
            android.util.Log.i("CastReceiver", "收到投屏请求：${req.uri}")
            val name = req.title?.takeIf { it.isNotBlank() }
                ?: Uri.parse(req.uri).lastPathSegment
                    ?.substringAfterLast('/')
                    ?.substringBeforeLast('.')
                    ?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.media_cast_video)
            selectedMediaItem = MediaItem(
                id = "cast_" + System.currentTimeMillis(),
                title = name,
                uri = req.uri,
                isVideo = true,
                isDemo = false,
                description = context.getString(R.string.media_cast_desc),
            )
            // 与「外部视频意图」一致：先按普通 2D 平面播（投过来的绝大多数是普通视频）
            projectionMode = ProjectionMode.STANDARD
            stereoMode = StereoMode.MONO
            photoReloadTrigger++
            keepUiAlight()
        }
    }

    // ⚠️ 「DMC 指令 → 播放器」与「进度回写」两个 effect **必须放在 playerInstance 声明之后**
    //    （Kotlin 局部变量先声明后使用），因此移到下方 `var playerInstance` 附近，见 v2.4.58 段。

    // v2.4.58：随开关状态启停投屏接收前台服务（应用启动时若开关已开，自动恢复服务）
    LaunchedEffect(castReceiverEnabled) {
        if (castReceiverEnabled) CastReceiverService.start(context) else CastReceiverService.stop(context)
    }

    // ===== v2.0.180：拖动预览缩略图（性能优化版）=====
    //
    // 优化前的问题：
    //  ① 每次拖动，`hoverTimeMs` 一变就重启 effect（每帧都触发），每次都 `new MediaMetadataRetriever()`
    //     + `setDataSource` + `getFrameAtTime` + `createScaledBitmap` + `release()`；
    //  ② 无缓存 —— 来回拖动到同一位置要重新解码；
    //  ③ 160×90 分辨率在 xxhdpi 屏上被放大到 160dp → 模糊。
    //
    // 现在的做法：
    //  · **节流**：拖动期间每 [SEEK_THUMB_THROTTLE_MS] 才真正抓一次；
    //  · **时间量化**：按 [SEEK_THUMB_QUANTUM_MS] 对齐时间戳，配合内存缓存大幅提高命中率
    //    （同一关键帧区间内来回拖动直接命中，不再解码）；
    //  · **LruCache**：缓存"（视频标识 + 量化时间戳）→ Bitmap"，用内存预算控制（约 24 张 @320×180）。
    //    ⚠️ key 必须带视频标识：只按时间戳缓存会在切换视频后命中**上一部视频**的帧（跨片串味）。
    //  · **分辨率提升**：320×180（匹配高密度屏的 160dp 显示尺寸，观感明显更清晰）。
    //
    // ⚠️ retriever 仍按需创建并即时释放：`MediaMetadataRetriever` 不是线程安全的，
    //    且持有的 native 资源必须显式 release；复用单个实例在快速拖动时反而会互相打架
    //    （并发 setDataSource）。这里靠"节流 + 缓存"把创建次数压下来，而不是靠复用实例。

    val seekThumbCache = remember { object : android.util.LruCache<String, Bitmap>(24) {} }

    // 节流 + 抓帧
    LaunchedEffect(hoverTimeMs, selectedMediaItem.uri, isHoverActive) {
        if (!isHoverActive || !selectedMediaItem.isVideo || selectedMediaItem.uri == null) return@LaunchedEffect
        val uriStr = selectedMediaItem.uri ?: return@LaunchedEffect

        // ① 节流：距上次抓帧不足阈值则跳过（仅更新文字时间，不重新解码）
        val now = System.currentTimeMillis()
        if (now - lastSeekThumbAt < SEEK_THUMB_THROTTLE_MS) return@LaunchedEffect
        lastSeekThumbAt = now

        // ② 时间量化：对齐到固定步长，提高缓存命中率
        val quantizedMs = (hoverTimeMs / SEEK_THUMB_QUANTUM_MS) * SEEK_THUMB_QUANTUM_MS
        // ③ key 带视频标识，避免换片后命中上一部视频的帧
        val cacheKey = "${uriStr.hashCode()}:$quantizedMs"

        // ④ 缓存命中直接返回
        seekThumbCache.get(cacheKey)?.let { cached ->
            if (!cached.isRecycled) {
                hoverPreviewBitmap = cached
                return@LaunchedEffect
            }
            seekThumbCache.remove(cacheKey)
        }

        withContext(Dispatchers.IO) {
            var retriever: android.media.MediaMetadataRetriever? = null
            try {
                retriever = android.media.MediaMetadataRetriever().apply {
                    if (uriStr.startsWith("content://") || uriStr.startsWith("file://") || uriStr.startsWith("android.resource://")) {
                        setDataSource(context, Uri.parse(uriStr))
                    } else {
                        setDataSource(uriStr, java.util.HashMap<String, String>())
                    }
                }
                // OPTION_CLOSEST_SYNC：只取关键帧，解码代价最低（拖动预览无需精确帧）
                val bmp = retriever.getFrameAtTime(quantizedMs * 1000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bmp != null) {
                    // 目标 320×180（16:9）；若源不是 16:9 则等比缩放后居中裁切，避免变形
                    val target = scaleSeekThumb(bmp)
                    if (target !== bmp) bmp.recycle()
                    seekThumbCache.put(cacheKey, target)
                    withContext(Dispatchers.Main) {
                        hoverPreviewBitmap = target
                    }
                }
            } catch (e: Exception) {
                Log.e("VRPlayerScreen", "Error of hover frame retrieval", e)
            } finally {
                try { retriever?.release() } catch (e: Exception) {}
            }
        }
    }

    // v2.0.180：切换视频时清掉拖动预览缓存与当前缩略图，避免跨片串味 / 残影
    LaunchedEffect(selectedMediaItem.uri) {
        seekThumbCache.evictAll()
        hoverPreviewBitmap = null
        // v2.6.9：**换视频时复位「用户手动调过」的锁**。
        //   各选片入口（相册 / 文档 / 外部意图 / 投屏）都会把 projectionMode 复位成
        //   STANDARD，但 `projectionModeUserAdjusted` 以前**从不复位** →
        //   只要手动调过一次，此后所有视频都不再跑智能检测。
        //   典型症状：为了看上下拼接片手选过一次「上下3D」，之后打开普通 2:1 全景
        //   也不会再自动识别，只能看压扁的画面，且必须重启 App 才恢复。
        // 同时复位立体模式：上一个片源的 TAB/SBS 残留到新视频会只显示上半/左半
        // （虽然后续尺寸回调会纠正，但那个中间态很刺眼）。
        projectionModeUserAdjusted = false
        stereoMode = StereoMode.MONO
    }

    /**
     * v2.1.241：把「用户选中的 URI」变成当前播放项 —— **相册选择器与文档选择器的唯一汇合点**。
     *
     * 抽出这个函数的原因：两个 launcher（`PickVisualMedia` / `OpenDocument`）的回调
     * 逻辑必须完全一致，各写一份必然漏改其中一处（本项目踩过 6 次的「两份 UI」问题）。
     *
     * 与旧实现的区别 —— **视频判定改用 [MediaFormats.looksLikeVideoUri]**：
     * 旧代码是 `mimeType.startsWith("video") || uri.contains(".mp4")`，
     * 一个只认 mp4 扩展名的判据。选 `.iso` 或 MIME 缺失的 `.wmv` 时会判成**图片**，
     * 然后走 `BitmapFactory.decodeStream` —— 对一个几百 MB 的 ISO 解码，
     * 轻则 OOM、重则直接把主线程卡死。
     */
    fun applyPickedToMedia(uri: Uri) {
        // v2.6.5：本地文件播放不再需要网页请求头，先清掉（否则会残留到下一次播放）
        HttpPlaybackHeaders.clear()
        val resolver = context.contentResolver
        val mimeType = runCatching { resolver.getType(uri) }.getOrNull()
        val isVideo = MediaFormats.looksLikeVideoUri(uri, mimeType)

        val realName = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
            ?: if (isVideo) context.getString(R.string.action_import_video) else context.getString(R.string.action_import_image)
        val customItem = MediaItem(
            id = "custom_" + System.currentTimeMillis(),
            title = realName,
            uri = uri.toString(),
            isVideo = isVideo,
            isDemo = false,
            description = context.getString(R.string.media_imported_desc, uri.lastPathSegment),
            // v2.4.19：存下 MIME —— 相册的 content:// 无扩展名，内核路由要靠它兜底
            mimeType = mimeType
        )

        if (isVideo) {
            projectionMode = ProjectionMode.STANDARD // default to standard 2D view for Video
            selectedMediaItem = customItem
            photoReloadTrigger++

            // ⚠️ ISO 的「能不能播」判定必须放在**选中之后、播放之前**，且**必须在 IO 线程**：
            //    它要读镜像头部（最多 33KB）。放在这里而不是 setupVideoPlayer 里，
            //    是因为那是渲染路径，多一次随机读会拖慢首帧。
            if (MediaFormats.needsSpecialHandling(uri)) {
                scope.launch(Dispatchers.IO) {
                    val kind = MediaFormats.inspectIso {
                        runCatching { resolver.openInputStream(uri) }.getOrNull()
                    }
                    val msgRes = when (kind) {
                        MediaFormats.IsoKind.DATA_IMAGE -> null // 数据镜像，正常播，不用提示
                        MediaFormats.IsoKind.DVD_VIDEO -> R.string.toast_iso_dvd_video
                        MediaFormats.IsoKind.BLU_RAY -> R.string.toast_iso_bluray
                        MediaFormats.IsoKind.UNKNOWN -> R.string.toast_iso_unknown
                    }
                    if (msgRes != null) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, context.getString(msgRes), Toast.LENGTH_LONG).show()
                        }
                    } else {
                        // 数据镜像：确认走 IJK（EXO 读不了 UDF 挂载点）
                        withContext(Dispatchers.Main) {
                            if (decoderEngine != DecoderEngine.IJK) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_auto_switch_ijk, "ISO"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
        } else {
            // 图片路径保持不变（含 2:1 全景自动切 VR_360 的行为）
            scope.launch {
                try {
                    val bmp = withContext(Dispatchers.IO) {
                        resolver.openInputStream(uri)?.use { stream ->
                            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options())
                        }
                    }
                    if (bmp != null) {
                        customBitmap = bmp
                        val r = bmp.width.toFloat() / bmp.height.toFloat()
                        projectionMode = if (r in 1.8f..2.2f) ProjectionMode.VR_360 else ProjectionMode.STANDARD
                        selectedMediaItem = customItem
                        photoReloadTrigger++
                    }
                } catch (e: Exception) {
                    Log.e("VRPlayerScreen", "Error loading custom picked bitmap", e)
                }
            }
        }
    }

    // Modern android system photo picker launcher to load custom panoramic/flat files
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            keepUiAlight()
            applyPickedToMedia(uri)
        }
    }

    // v2.1.241：**通用文件**选择器（与上面的相册选择器并存）。
    //
    // 为什么必须再加一个：`PickVisualMedia` 是**相册**选择器，底层按 `video/*` 过滤 ——
    //   · `.wmv` 的 MIME 常被 provider 报成 `video/x-ms-wmv`，部分机型**不列出**；
    //   · `.iso` 根本不是视频 MIME，**永远不列出**。
    // 于是「能播但选不到文件」就成了一道看不见的墙。`OpenDocument` 直接走
    // SAF 文档树，配 `*/*` 可以选到任意文件。
    //
    // ⚠️ 两个 launcher 是**同一个 effect 的两种入口**，都调 [applyPickedToMedia] ——
    //    绝不要各写一份解析逻辑（本项目头号事故源「同一功能两份 UI」）。
    var documentPickerLauncher by remember {
        mutableStateOf<androidx.activity.result.ActivityResultLauncher<Array<String>>?>(null)
    }
    documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            keepUiAlight()
            // SAF 给的 content:// 往往**不带持久读权限**（进程重启后失效），
            // 但对「选完立刻播」的场景够用。这里不做 takePersistableUriPermission，
            // 因为一旦持久化，用户删了文件我们这边会留一堆失效记录。
            applyPickedToMedia(uri)
        }
    }

    // Handle modern ExoPlayer lifecycle and Surface Texture streaming in Compose
    // v2.1.233：类型由 ExoPlayer 改为统一门面 VrPlayerBackend（接入 ijk 后两种内核共用）。
    // 门面刻意复刻了 ExoPlayer 的方法名与签名（play/pause/seekTo/currentPosition/
    // duration/isPlaying/setPlaybackSpeed/release），因此下面 20 多处调用点无需改动。
    var playerInstance by remember { mutableStateOf<VrPlayerBackend?>(null) }

    // v2.6.24：**直播延迟**下发 —— 让播放位置稳定落后实时边缘 N 秒。
    // 直播的字幕要靠后台识别/翻译生成（比实时慢 1~3 秒），把播放压低就能让字幕追上画面。
    // ⚠️ key 里带 playerInstance：内核重建（如强制 EXO、切换解码器）后新播放器
    //    不知道这个偏移，必须重新下发一次，否则用户会看到"设置还在、位置又贴回实时了"。
    // ⚠️ 只在直播下下发：点播没有"实时边缘"概念，EXO 对非直播源设 target offset 无效。
    LaunchedEffect(isLiveStream, liveDelaySec, playerInstance) {
        if (isLiveStream) {
            val p = playerInstance
            Log.i(
                "VRPlayerScreen",
                "直播延迟 ${liveDelaySec}s 已下发 → engine=${p?.engine} cls=${p?.javaClass?.simpleName}"
            )
            p?.setLiveDelayMs(liveDelaySec * 1000L)
        }
    }

    // ==================================================================================
    // v2.4.58：投屏接收 —— DMC 遥控与进度回写（**必须放在 playerInstance 声明之后**）
    //   1) DMC 下发的 Play/Pause/Stop/Seek → 作用于现有播放器门面
    //   2) 播放进度回写 → 供 DMC 轮询 GetPositionInfo 显示进度
    // ==================================================================================
    LaunchedEffect(Unit) {
        CastMediaBus.commands.collect { cmd ->
            when (cmd.type) {
                CastCommand.Type.PLAY -> playerInstance?.play()
                CastCommand.Type.PAUSE -> playerInstance?.pause()
                CastCommand.Type.STOP -> {
                    playerInstance?.pause()
                    playerInstance?.seekTo(0L)
                }
                CastCommand.Type.SEEK -> playerInstance?.seekTo(cmd.positionMs)
            }
        }
    }

    // ==================================================================================
    // v2.6.20（用户要求「应用切换到后台时暂停播放」）：**切后台暂停，回前台自动续播**
    //
    //   · 触发点是 `ON_STOP` 而不是 `ON_PAUSE`：设置面板、隐私弹窗、权限弹窗、分屏拖动
    //     都会走 `ON_PAUSE`，但用户并没有离开应用，误伤概率高；`ON_STOP` 才是
    //     "界面已经看不到"（Home / 最近任务 / 锁屏 / 被全屏应用覆盖）。
    //   · 只在**确实在播**时才记暂停：用户手动暂停后再切后台，回来不会自动开播。
    //   · 回前台（`ON_START`）自动续播 —— 视频 App 的常规行为；若要"回来仍保持暂停"，
    //     删掉下面 `ON_START` 分支即可。
    //   · 与投屏遥控共用 [playerInstance]：后台暂停后 DMC 仍可下发 Play 唤醒播放。
    // ==================================================================================
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var pausedByBackground by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner, playerInstance) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    val p = playerInstance
                    if (p != null && p.isPlaying) {
                        p.pause()
                        isVideoPlaying = false
                        pausedByBackground = true
                    }
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    if (pausedByBackground) {
                        pausedByBackground = false
                        playerInstance?.play()
                        isVideoPlaying = true
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ==================================================================================
    // v2.6.20（P0，用户选定）：**音频焦点 + 拔耳机 / 断蓝牙自动暂停**（`PlaybackInterruptions`）
    //
    //   · 来电 / 导航语音 / 其它 App 抢焦点 → 自动暂停；临时失去（transient）在焦点归还时
    //     自动续播，永久失去（如用户去别处点了播放）不恢复 —— 规则详见该类注释。
    //   · 拔耳机 / 断蓝牙（BECOMING_NOISY）→ 暂停且不恢复。
    //   · ⚠️ 焦点在 [playerInstance] 就绪后一直持有到其释放，**不跟随播放状态启停**：
    //     若"暂停即 abandon"，来电（transient loss → 我们 abandon）后系统不会把
    //     GAIN 再发给我们，"挂电话续播"就断了 —— 宁可在选片期间多占一会儿焦点。
    //   · 回调里同步 `isVideoPlaying`，让球面图标 / 弹幕冻结 / 控制条跟着走。
    // ==================================================================================
    val playbackInterruptions = remember {
        PlaybackInterruptions(
            context = context,
            isPlaying = { playerInstance?.isPlaying == true },
            pause = {
                playerInstance?.pause()
                isVideoPlaying = false
            },
            resume = {
                playerInstance?.play()
                isVideoPlaying = true
            },
        )
    }
    DisposableEffect(playbackInterruptions, playerInstance) {
        if (playerInstance != null) playbackInterruptions.start()
        onDispose { playbackInterruptions.stop() }
    }

    LaunchedEffect(playerInstance) {
        while (true) {
            val p = playerInstance
            CastMediaBus.reportProgress(
                positionMs = p?.currentPosition ?: 0L,
                durationMs = (p?.duration ?: 0L).coerceAtLeast(0L),
                currentUri = p?.currentUri?.toString() ?: "",
            )
            delay(1000L)
        }
    }
    // 审查 #13：每次 setupVideoPlayer 都会 new 一个 Surface，旧的不释放会累积泄漏。
    // 用 AtomicReference（remember 一次，跨重组保留）记录当前绑定到播放器的 Surface，
    // 新建前先 release 旧的；不用 State 以免触发重组或后台线程写快照。
    val surfaceHolder = remember { java.util.concurrent.atomic.AtomicReference<android.view.Surface?>(null) }
    // v2.1.234：当前正在播放的 URI。
    // 用途：给「视频信息」面板猜容器格式（Exo 没有直接给出封装的 API，只能按扩展名推）。
    // 不能直接用 `decodedUri` —— 那是 setupVideoPlayer 里的**局部变量**，
    // 设置面板所在的 Compose 作用域看不到它。
    var currentVideoUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // Synchronize player speed with basePlaybackSpeed and floating ball long-press boost
    LaunchedEffect(basePlaybackSpeed, floatingBallSpeed, isFloatingBallPressed, playerInstance) {
        val speedToApply = if (isFloatingBallPressed) floatingBallSpeed else basePlaybackSpeed
        playerInstance?.setPlaybackSpeed(speedToApply)
    }
    var currentGlSurfaceView by remember { mutableStateOf<VRGLSurfaceView?>(null) }

    // ===== v2.3.1（P2 + P4）：AI 弹幕编排（已拆至 VRPlayerDanmuOrchestrator.kt）=====
    val danmuState = rememberDanmuOrchestratorEffects(
        context = context,
        prefs = prefs,
        isMemoryModeEnabled = isMemoryModeEnabled,
        selectedMediaItem = selectedMediaItem,
        danmuConfig = danmuConfig,
        onDanmuConfigChange = { danmuConfig = it },
        subtitleTranslator = subtitleTranslator,
        getCurrentPositionMs = { currentPositionMs },
        getIsVideoPlaying = { isVideoPlaying },
        getCurrentGlSurfaceView = { currentGlSurfaceView },
        getIsRealtimeAsrActive = { isRealtimeAsrActive },
        getRealtimeCues = { realtimeCues },
        getLoadedSubtitleCues = { loadedSubtitleCues },
        onKeepUiAlight = { keepUiAlight() },
    )

    // v104：手机自选 LUT 文件（.cube）选择器
    val lutPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    isLutLoading = true
                    val rgba = context.contentResolver.openInputStream(uri)?.use {
                        LutUtils.parseCubeToRgba(it)
                    }
                    withContext(Dispatchers.Main) {
                        if (rgba != null) {
                            // v2.6.16：与内置款同一口径 —— 强度为 0 时先恢复默认，
                            // 否则「刚刚点了无滤镜 / 拖到 0」再选自选文件同样毫无效果。
                            if (lutMix <= LutUtils.MIX_EPSILON) lutMix = LutUtils.DEFAULT_MIX
                            currentGlSurfaceView?.renderer?.setLutTexture(rgba)
                            currentGlSurfaceView?.renderer?.lutMix = lutMix
                            lutName = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
                                ?: context.getString(R.string.lut_custom)
                            Toast.makeText(context, context.getString(R.string.toast_lut_applied, lutName), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, context.getString(R.string.toast_lut_parse_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VRPlayerScreen", "LUT load failed", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.toast_lut_load_failed, (e.message ?: "")), Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    withContext(Dispatchers.Main) { isLutLoading = false }
                }
            }
        }
    }

    LaunchedEffect(maxFps, currentGlSurfaceView) {
        currentGlSurfaceView?.renderer?.maxFps = maxFps
    }

    // v2.6.16：恢复上次选中的内置 LUT —— 光把名字读回来**不够**，纹理必须重新解析上传，
    // 否则就是「名字亮着、画面没效果」的幽灵选中态（和强度被回写成 0 是同一类症状）。
    // key 只放 `currentGlSurfaceView`：渲染器实例一换（首次就绪 / AndroidView 重建）就重放一次，
    // 顺带补上「AndroidView.update 从不同步 LUT 纹理」这个漏洞 —— 之前 renderer 换了，
    // 新 renderer 的 `lutMix` 是 0、纹理是 -1，LUT 静默消失而 UI 状态还在。
    // ⚠️ 不要把这个 effect 的 key 加上 `lutName`/`lutMix`：那样每拖一次强度就会重新
    //    解析一遍 .cube（33³→64³ 重采样），白费 CPU 还可能闪一下。
    LaunchedEffect(currentGlSurfaceView) {
        val view = currentGlSurfaceView ?: return@LaunchedEffect
        val builtin = LutUtils.findBuiltin(lutName) ?: return@LaunchedEffect
        val rgba = withContext(Dispatchers.IO) {
            try {
                LutUtils.loadBuiltinLut(context, builtin.fileName)
            } catch (e: Exception) {
                Log.e("VRPlayerScreen", "恢复 LUT 失败：${builtin.fileName}", e)
                null
            }
        } ?: return@LaunchedEffect
        // 强度为 0 而滤镜名非空 → 是「拖到底」留下的死状态，恢复成默认强度才会有效果
        if (lutMix <= LutUtils.MIX_EPSILON) lutMix = LutUtils.DEFAULT_MIX
        view.renderer.setLutTexture(rgba)
        view.renderer.lutMix = lutMix
    }

    // v2.0.206：画质增强（MEMC / FSR）参数实时同步到渲染器。
    // ⚠️ FSR 的「是否真正启用 + 目标尺寸」在这里用 VideoEnhanceRules 现算后写入，
    //    渲染侧只消费结果、不再自己判一次 —— 这是保证「UI 显示 == 实际行为」的关键。
    //    例如源是 1440p 时判定为「目标不高于源 → 不启用」，此时 fsrEnabled 写 false，
    //    即便用户把总开关打开了也不会白跑一遍超分。
    LaunchedEffect(
        isMemcEnabled, memcTargetFps, isFsrEnabled, fsrRuleMode, fsrCustomTarget,
        videoSourceWidth, videoSourceHeight, currentGlSurfaceView
    ) {
        val r = currentGlSurfaceView?.renderer ?: return@LaunchedEffect
        r.memcEnabled = isMemcEnabled
        r.memcTargetFps = memcTargetFps
        val decision = VideoEnhanceRules.resolveFsr(
            videoSourceWidth, videoSourceHeight, fsrRuleMode, fsrCustomTarget
        )
        val active = isFsrEnabled && decision.enabled
        r.fsrEnabled = active
        r.fsrTargetWidth = if (active) decision.targetWidth else 0
        r.fsrTargetHeight = if (active) decision.targetHeight else 0
    }
    var showResolutionTip by remember { mutableStateOf(false) }
    var resolutionTipText by remember { mutableStateOf("") }

    var isTranscoding by remember { mutableStateOf(false) }
    var transcodingProgress by remember { mutableIntStateOf(0) }
    var transcodingStatusText by remember { mutableStateOf("") }
    // Seek-failure auto-fix state: some mp4 containers reset position to 0 on seek. (8/1 功能)
    var isRemuxing by remember { mutableStateOf(false) }
    var seekUnsupported by remember { mutableStateOf(false) }

    // ⚠️ v2.1.247：**切换媒体时必须复位 seekUnsupported**。
    //    它原来只在 `startRemuxFix()` 开头重置 → 一旦某个 AVI 触发了
    //    「不支持拖动定位」，标志位就永久为 true，切到**别的**（本可自动修复的）
    //    视频时也不会再触发重封装校验 —— 表现为「换个视频还是拖不动」。
    //    （必须放在 `seekUnsupported` 声明之后：Kotlin 局部 var 先声明后使用。）
    //
    // ======================================================================
    // v2.4.9：**打开时探测 AVI 是否带 idx1 索引**
    // ----------------------------------------------------------------------
    // 要解决的**唯一**症状（用户报告，且只出现在 AVI）：
    //   > 时间轴能跳过去，但**画面只停在那一帧，不再继续播放**。
    //
    // 根因不是 seek 参数 —— 本项目早已设 `SeekParameters`，也早已改成
    // 「拖动中不 seek、松手只 seek 一次」。真正的原因是**容器缺 `idx1` 索引**：
    // 无索引时 `AviExtractor` 只能从头线性扫描 chunk，而扫到的位置**未必是关键帧**，
    // 解码器于是拿不到 I 帧参考 → 那一帧显示出来后没有后续帧可解 → 画面冻结。
    //
    // ⚠️ 关键：这不是「seek 没生效」，所以旧的校验（看 `currentPosition` 有没有
    //    跳到目标）**恰好检测不到** —— 位置其实已经跳到目标了，卡住的是解码推进。
    // → 必须在打开时就把「有没有索引」探明，让 seek 那一段据此决定是否重封装
    //   （[VideoRemuxer] 不重编码、只重建索引）。
    //
    // ⚠️ 只对 `file:` / `content:` 探测：SMB / HTTP 上读 1MB 有实际代价，
    //    而那些路径本来就走各自的数据源封装。
    // ⚠️ 只对 AVI 家族的扩展名探测（见 [MediaFormats.isAviFamily]）。
    // ======================================================================
    var aviProbe by remember { mutableStateOf<AviRiffProbe.Probe?>(null) }
    LaunchedEffect(selectedMediaItem.uri) {
        seekUnsupported = false
        isRemuxing = false
        aviProbe = null

        val uriStr = selectedMediaItem.uri
        if (uriStr == null || !selectedMediaItem.isVideo) return@LaunchedEffect
        val parsed = Uri.parse(uriStr)
        val scheme = parsed.scheme?.lowercase()
        if (scheme != null && scheme != "file" && scheme != "content") return@LaunchedEffect
        if (!MediaFormats.isAviFamily(MediaFormats.extensionOf(parsed))) return@LaunchedEffect

        val probed = withContext(Dispatchers.IO) {
            try {
                val cr = context.contentResolver
                // ---- 头部：RIFF 头 + hdrl 一定在最前面，64KB 足够 ----
                val headBuf = ByteArray(AviRiffProbe.HEAD_BYTES)
                var headLen = 0
                cr.openInputStream(parsed)?.use { ins ->
                    while (headLen < headBuf.size) {
                        val n = ins.read(headBuf, headLen, headBuf.size - headLen)
                        if (n <= 0) break
                        headLen += n
                    }
                }
                if (headLen <= 0) return@withContext null
                val headProbe = AviRiffProbe.parseHeader(headBuf.copyOf(headLen))
                if (!headProbe.isAvi) return@withContext headProbe

                // ---- 尾部：idx1 索引与 AVIX 多段容器都在 movi 之后，取末尾一段扫 ----
                var hasIdx = false
                var hasMulti = false
                // ⚠️ v2.4.19 二轮：默认 true = 「判不出来就当没问题」，绝不因此误触发重封装
                var coversToEnd = true
                cr.openFileDescriptor(parsed, "r")?.use { pfd ->
                    val size = pfd.statSize
                    if (size > 0) {
                        val tailLen = minOf(AviRiffProbe.TAIL_BYTES.toLong(), size).toInt()
                        val tail = ByteArray(tailLen)
                        // ⚠️ 不 close 这个流：fd 由 pfd 持有并负责关闭
                        //    （本项目在 IJK 的 content:// 路径上踩过「提前关 fd」的坑）
                        val fis = java.io.FileInputStream(pfd.fileDescriptor)
                        fis.channel.position(size - tailLen)
                        var off = 0
                        while (off < tailLen) {
                            val n = fis.read(tail, off, tailLen - off)
                            if (n <= 0) break
                            off += n
                        }
                        // ⚠️ v2.4.19：同一份 tail 缓冲同时判三件事（idx1 存在性 / 多段 AVIX /
                        //    **idx1 实际覆盖范围**），不重复读文件。
                        val view = tail.copyOf(off)
                        hasIdx = AviRiffProbe.containsIndexMarker(view)
                        hasMulti = AviRiffProbe.containsMultiSegmentMarker(view)
                        // 🔴 事实判据：直接把「idx1 最后一条目指到的位置」与文件大小比 ——
                        //    这是唯一不依赖结构标记、能抓到「idx1 只覆盖第一段」的办法。
                        coversToEnd = AviRiffProbe.idx1CoversToEnd(
                            tail = view,
                            tailFileOffset = size - off.toLong(),
                            moviStart = headProbe.moviStart,
                            fileSize = size
                        )
                    }
                }
                headProbe.copy(
                    hasIndexChunk = hasIdx,
                    hasMultiSegment = hasMulti,
                    indexCoversToEnd = coversToEnd
                )
            } catch (e: Exception) {
                Log.w("VRPlayerScreen", "AVI 容器探测失败（按未知处理）：${e.message}")
                null
            }
        }
        aviProbe = probed
        probed?.let {
            Log.i(
                "VRPlayerScreen",
                "AVI 探测：容器=${if (it.isAvi) "AVI" else "非 AVI"}，" +
                    "idx1=${if (it.hasIndexChunk) "有" else "无"}" +
                    "，索引覆盖全片=${if (it.indexCoversToEnd) "是" else "否"}" +
                    "，OpenDML索引=${if (it.hasOpenDmlIndex) "有" else "无"}" +
                    "，多段movi=${if (it.hasMultiSegment) "是" else "否"}" +
                    "，movi起点=${it.moviStart}" +
                    if (it.needsIndexRebuild) " → 索引不足以覆盖全片，将按需重建" else ""
            )
        }
    }

    // LAN (SMB) browser state (8/2 功能)
    var smbDialogOpen by remember { mutableStateOf(false) }
    // v2.1.241：点「+」时先选从哪个入口挑文件（相册 / 任意文件）。
    var pickerSourceDialogOpen by remember { mutableStateOf(false) }
    var smbHost by remember { mutableStateOf("") }
    var smbUser by remember { mutableStateOf("") }
    var smbPass by remember { mutableStateOf("") }
    var smbPath by remember { mutableStateOf("") }
    var smbEntries by remember { mutableStateOf<List<jcifs.smb.SmbFile>>(emptyList()) }
    var smbError by remember { mutableStateOf("") }
    // Video info dialog + track selection (8/2 功能)
    var videoInfoDialogText by remember { mutableStateOf<String?>(null) }
    var trackDialogOpen by remember { mutableStateOf(false) }
    var selectedVideoTrack by remember { mutableIntStateOf(-1) }
    var selectedAudioTrack by remember { mutableIntStateOf(-1) }
    var selectedTextTrack by remember { mutableIntStateOf(-1) }
    // 播放位置恢复：v119 起改由 PlaybackPositions 按媒体 URI 持久化，
    // 不再使用跨媒体共享的 restorePositionMs 变量（详见该 object 注释 #7）

    // Media3 Transformer downscaling function
    fun startDownscalingTranscode() = VRPlayerMediaOps.startDownscalingTranscode(
        context = context, scope = scope, selectedMediaItem = selectedMediaItem, maxResolution = maxResolution,
        onSelectMedia = { selectedMediaItem = it }, onShowResolutionTipChange = { showResolutionTip = it },
        onTranscodingChange = { isTranscoding = it }, onTranscodeProgressChange = { transcodingProgress = it },
        onTranscodeStatusChange = { transcodingStatusText = it }, onPhotoReload = { photoReloadTrigger++ },
        onKeepUiAlight = { keepUiAlight() },
    )

    // Dynamic track selection parameters update when maxResolution limit is changed
    LaunchedEffect(maxResolution) {
        // v2.1.233：轨道选择是 ExoPlayer 专有能力 → 走门面的 exo 逃生舱口；
        // ijk 下为 null，此处自然跳过（不会崩，只是不生效）。
        playerInstance?.exo?.let { player ->
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(maxResolution.width, maxResolution.height)
                .build()
        }
    }

    fun encodeSmb(url: String): String = VRPlayerMediaOps.encodeSmb(url)
    fun browseSmb(path: String) = VRPlayerMediaOps.browseSmb(
        context = context, scope = scope, path = path,
        onSmbErrorChange = { smbError = it }, onSmbPathChange = { smbPath = it }, onSmbEntriesChange = { smbEntries = it },
    )
    fun connectSmb() = VRPlayerMediaOps.connectSmb(
        context = context, scope = scope, smbHost = smbHost, smbUser = smbUser, smbPass = smbPass,
        onSmbErrorChange = { smbError = it }, onSmbPathChange = { smbPath = it }, onSmbEntriesChange = { smbEntries = it },
    )
    fun playSmbFile(entry: jcifs.smb.SmbFile) = VRPlayerMediaOps.playSmbFile(
        context = context, entry = entry, onSelectMedia = { selectedMediaItem = it },
        onSmbDialogOpenChange = { smbDialogOpen = it }, onPhotoReload = { photoReloadTrigger++ },
    )

    /**
     * Auto-fixes a video whose container does not support seeking (position resets
     * to 0 after seekTo) by re-muxing it into a fresh MP4 via FFmpeg. No re-encoding. (8/1 功能)
     *
     * ⚠️ v2.4.26：从「MediaExtractor+MediaMuxer → MP4」改为「FFmpeg → MP4(MKV 兜底)」。
     *    MediaMuxer 要求时间戳单调，含 B 帧的源会被「退帧跳过」砍掉 90%+ 帧 → 卡顿。
     */
    fun startRemuxFix() = VRPlayerMediaOps.startRemuxFix(
        context = context, scope = scope, selectedMediaItem = selectedMediaItem, isRemuxing = isRemuxing,
        onRemuxingChange = { isRemuxing = it }, onSelectMedia = { selectedMediaItem = it },
        onSeekUnsupportedChange = { seekUnsupported = it }, onPhotoReload = { photoReloadTrigger++ },
    )
    fun effectiveOutputSize(w: Int, h: Int): Pair<Int, Int> = VRPlayerMediaOps.effectiveOutputSize(w, h, downscaleOutputEnabled)
    fun startLevelPatchFix() = VRPlayerMediaOps.startLevelPatchFix(
        context = context, scope = scope, selectedMediaItem = selectedMediaItem, isRemuxing = isRemuxing,
        level51Enabled = level51Enabled, spoofResolutionEnabled = spoofResolutionEnabled, levelPatchEnabled = levelPatchEnabled,
        onRemuxingChange = { isRemuxing = it }, onSelectMedia = { selectedMediaItem = it },
        onPhotoReload = { photoReloadTrigger++ },
    )

    fun selectMediaTrack(groupType: Int, trackIndex: Int) = VRPlayerTrackAndAsrOps.selectMediaTrack(
        playerInstance = playerInstance,
        groupType = groupType,
        trackIndex = trackIndex,
        onVideoTrackSelected = { selectedVideoTrack = it },
        onAudioTrackSelected = { selectedAudioTrack = it },
        onTextTrackSelected = { selectedTextTrack = it },
    )

    fun showVideoInfo() = VRPlayerTrackAndAsrOps.showVideoInfo(
        context = context,
        scope = scope,
        selectedMediaItem = selectedMediaItem,
        displayName = getMediaDisplayName(),
        playerInstance = playerInstance,
        isSoftwareDecoding = isSoftwareDecoding,
        onInfoReady = { videoInfoDialogText = it },
    )

    /**
     * v2.1.241：在主线程弹一个 Toast。
     *
     * 为什么需要它：`setupVideoPlayer` **可能在非主线程被调用**（例如从 IO 协程
     * 里切换片源后再起播），而 `Toast.makeText(...).show()` 必须在有 Looper 的
     * 线程调用 —— 直接调在极少数路径下会抛 `RuntimeException: Can't create handler
     * inside thread that has not called Looper.prepare()`。
     *
     * 注意：`Activity.runOnUiThread` 在**已是主线程**时也直接执行，不会多绕一圈消息，
     * 所以这里不需要先判 `Looper.myLooper()`。
     */
    fun postToast(text: CharSequence) {
        (context as? android.app.Activity)?.runOnUiThread {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        } ?: Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    /** v2.6.24（直播 P3）：实时字幕保留条数上限 / 时间窗口 */
    val LIVE_CUE_MAX_ENTRIES = 500
    val LIVE_CUE_KEEP_WINDOW_MS = 10 * 60 * 1000L

    /**
     * v2.6.24（直播 P3）：裁剪实时字幕快照 —— 只留「最近 10 分钟」且「不超过 500 条」。
     *
     * 引擎给的是**完整快照**（每次新增都推全量）。点播无所谓（一部片几千条封顶），
     * 但直播是无限流：挂一下午能攒出几万条，而真正的消费者只有两个，窗口都很窄
     * （字幕叠加层按当前播放位置取 1 条、AI 弹幕取词 ±15s）。
     *
     * ⚠️ 以最后一条 cue 的**结束时间**为基准往前算，而不是当前播放位置：
     *    直播下识别前沿略超前于播放头，用播放位置算会把刚生成的前沿句裁掉。
     * ⚠️ 点播**同样裁剪**：一部 3 小时的片也就 3000 条左右，裁到 10 分钟会让
     *    「往回拖到 20 分钟前」没有字幕可显示。因此只对直播生效。
     */
    fun trimRealtimeCues(cues: List<SubtitleCue>): List<SubtitleCue> {
        if (!isLiveStream || cues.size <= LIVE_CUE_MAX_ENTRIES) return cues
        val lastEnd = cues.lastOrNull()?.endTimeMs ?: return cues
        val keepFrom = lastEnd - LIVE_CUE_KEEP_WINDOW_MS
        val trimmed = cues.asSequence()
            .filter { it.endTimeMs >= keepFrom }
            .toList()
            .takeLast(LIVE_CUE_MAX_ENTRIES)
        return trimmed
    }

    // Helper inside Compose to rebuild/re-bind Android ExoPlayer to GLES
    fun setupVideoPlayer(surfaceTexture: SurfaceTexture, videoUriStr: String) {
        val host = KernelSetupHost(
            context = context,
            prefs = prefs,
            scope = scope,
            audioProcessor = audioProcessor,
            teeAudioProcessor = teeAudioProcessor,
            genderFilterProcessor = genderFilterProcessor,
            ijkOptions = ijkOptions,
            mpvOptions = mpvOptions,
            getDecoderEngine = { decoderEngine },
            setDecoderEngine = { decoderEngine = it },
            isSmartProjectionEnabled = isSmartProjectionEnabled,
            setIsSplitScreenVR = { isSplitScreenVR = it },
            addCodecParamsEnabled = addCodecParamsEnabled,
            forceHwDecoderEnabled = forceHwDecoderEnabled,
            autoFallbackSoftEnabled = autoFallbackSoftEnabled,
            levelPatchEnabled = levelPatchEnabled,
            spoofResolutionEnabled = spoofResolutionEnabled,
            maxResolution = maxResolution,
            floatingBallSpeed = floatingBallSpeed,
            basePlaybackSpeed = basePlaybackSpeed,
            isFloatingBallPressed = isFloatingBallPressed,
            selectedMediaItem = selectedMediaItem,
            currentGlSurfaceView = currentGlSurfaceView,
            isLiveStream = isLiveStream,
            isRemuxing = isRemuxing,
            isTranscoding = isTranscoding,
            effectiveOutputSize = ::effectiveOutputSize,
            postToast = ::postToast,
            startLevelPatchFix = ::startLevelPatchFix,
            getIsSoftwareDecoding = { isSoftwareDecoding },
            setIsSoftwareDecoding = { isSoftwareDecoding = it },
            getStereoMode = { stereoMode },
            setStereoMode = { stereoMode = it },
            getProjectionMode = { projectionMode },
            setProjectionMode = { projectionMode = it },
            getProjectionModeUserAdjusted = { projectionModeUserAdjusted },
            setProjectionModeUserAdjusted = { projectionModeUserAdjusted = it },
            getPlayerInstance = { playerInstance },
            setPlayerInstance = { playerInstance = it },
            setIsVideoPlaying = { isVideoPlaying = it },
            setPlaybackEnded = { playbackEnded = it },
            setVideoSourceWidth = { videoSourceWidth = it },
            setVideoSourceHeight = { videoSourceHeight = it },
            setVideoDurationText = { videoDurationText = it },
            setExoCueText = { exoCueText = it },
            getMpvRetryUri = { mpvRetryUri },
            setMpvRetryUri = { mpvRetryUri = it },
            getExoRetryUri = { exoRetryUri },
            setExoRetryUri = { exoRetryUri = it },
            getSoftRetryUri = { softRetryUri },
            setSoftRetryUri = { softRetryUri = it },
            setLiveForceExoUri = { liveForceExoUri = it },
            getCookieRetryAttemptedUri = { cookieRetryAttemptedUri },
            setCookieRetryAttemptedUri = { cookieRetryAttemptedUri = it },
            getCookieRetryTrigger = { cookieRetryTrigger },
            setCookieRetryTrigger = { cookieRetryTrigger = it },
            getLiveReconnectAttempt = { liveReconnectAttempt },
            setLiveReconnectAttempt = { liveReconnectAttempt = it },
            getLiveReconnectJob = { liveReconnectJob },
            setLiveReconnectJob = { liveReconnectJob = it },
            setShowResolutionTip = { showResolutionTip = it },
            setResolutionTipText = { resolutionTipText = it },
            setCurrentVideoUri = { currentVideoUri = it },
            surfaceHolder = surfaceHolder,
            getForceVideoType = { forceVideoType },
            setForceVideoType = { forceVideoType = it },
            getMpvVoMode = { mpvVoMode },
            setMpvVoMode = { mpvVoMode = it },
        )
        VRPlayerKernelSetup.setupVideoPlayer(host, surfaceTexture, videoUriStr)
    }

    // Continuously sync playback position for subtitle timing + 记录播放位置用于恢复（8/1 功能）
    // v119 修复(#7)：位置按**当前媒体 URI** 持久化（每 2s 落盘一次以免频繁 IO），
    // 不再写一个跨媒体共享、语义混乱的 restorePositionMs 镜像变量。
    LaunchedEffect(playerInstance, selectedMediaItem.uri, isVideoPlaying) {
        var lastSavedAt = 0L
        var lastPosForRealtime = 0L
        // v2.0.134：effect 因暂停/播放切换或播放器重建而重启时，局部变量会重置为 0；
        // 若首帧直接拿当前播放位置与 0 比较，会误判成一次 >2s 的大跳 seek，
        // 触发 onSeek 清空后续字幕缓存（"字幕放一会儿就没了"）。首帧只用来初始化基准。
        var realtimeSeekInit = false
        while (true) {
            playerInstance?.let { player ->
                currentPositionMs = player.currentPosition
                // v126：实时字幕跟随播放头。
                // 位置突跳（>2 秒）视为 seek —— 这样不必逐个改各 seek 调用点，
                // 进度条拖动、章节跳转、双击重置等入口都能被统一捕获。
                // v2.6.24：判据换成 isRealtimeAsrActive —— 后台执行模式下**不显示字幕，
                // 但引擎仍要跟着播放头走**（否则 ASR 不知道播到哪儿，弹幕素材就断了）。
                if (isRealtimeAsrActive) {
                    val pos = player.currentPosition
                    // v2.6.24（直播 P3）：直播的跳变阈值放大到 **30s**。
                    // 直播正常播放也会有秒级位置抖动（重连、切码率、追赶实时边缘），
                    // 沿用点播的 2s 阈值会被频繁误判成 seek；而 >30s 的跳变必然是重连。
                    val jumpThreshold = if (isLiveStream) 30_000L else 2_000L
                    if (kotlin.math.abs(pos - lastPosForRealtime) > jumpThreshold) {
                        realtimeSubtitleEngine.onSeek(pos)
                        if (isLiveStream) {
                            // v2.6.24（直播 P3）：直播跳变 = 换了内容段落 → 旧字幕/旧译文全部作废
                            realtimeCues = emptyList()
                            subtitleTranslator.clearLiveMemoryCache()
                            realtimeSubtitleEngine.dropAllCues()
                        } else if (subtitleTranslator.config.isEnabled && realtimeCues.isNotEmpty()) {
                            // v127：跳转后重新预读翻译新位置前方的字幕
                            // （直播下 pretranslateAhead 内部已直接 return，见其 liveMode 短路）
                            subtitleTranslator.pretranslateAhead(realtimeCues, pos)
                        }
                    } else {
                        realtimeSubtitleEngine.updateCursor(pos)
                    }
                    lastPosForRealtime = pos
                    // v127b：同步生成进度，供进度条与"完成"状态显示
                    realtimeTotalMs = realtimeSubtitleEngine.durationMs
                    realtimeGeneratedMs = realtimeSubtitleEngine.generatedMs
                    realtimeDone = realtimeSubtitleEngine.isFullyGenerated
                }
                if (player.isPlaying && player.currentPosition > 0L) {
                    // 审查 #10：切片瞬间 selectedMediaItem.uri 已指向新片，但 playerInstance
                    // 仍是旧播放器 → 会把「旧片位置」误存到新片 URI 下。仅当播放器绑定的
                    // URI 与当前片一致时才保存。
                    // v2.6.24（直播 P0）：直播不写断点 —— 位置对下次播放无意义，还会污染断点表
                    if (player.currentUri?.toString() == selectedMediaItem.uri && !isLiveStream) {
                        val now = System.currentTimeMillis()
                        if (now - lastSavedAt >= 2_000L) {
                            lastSavedAt = now
                            PlaybackPositions.save(prefs, selectedMediaItem.uri, player.currentPosition)
                        }
                    }
                }
            }
            delay(150L)
        }
    }

    // v117 修复(#6)：把“当前媒体 → 渲染器/播放器”的绑定抽成局部函数。
    // 原先这段绑定逻辑与“打开视频时的默认视角初始化”挤在同一个 effect 里，而该 effect 的
    // key 混入了 isSoftwareDecoding / decoderEngine / photoReloadTrigger —— 于是用户只要切换
    // “硬解/软解”或更换解码器引擎，正在看的投影模式（180°/360°/平面）、手动选的立体模式、
    // 已开启的陀螺仪都会被无声重置。现在绑定与视角初始化彻底分离。
    suspend fun rebindCurrentMediaToRenderer() {
        val view = currentGlSurfaceView ?: return
        if (selectedMediaItem.isVideo) {
            // Video active
            view.renderer.isVideoActive = true
            // If it is a video, VRGLRenderer onVideoSurfaceCreated callback will trigger video player binding!
            selectedMediaItem.uri?.let { uriStr ->
                val existingST = view.renderer.videoSurfaceTexture
                if (existingST != null) {
                    setupVideoPlayer(existingST, uriStr)
                }
                view.renderer.onVideoSurfaceCreated = { surfaceTexture ->
                    // 🔴 v2.6.20 修：**这个回调来自 GL 线程**（`VRGLRenderer.onSurfaceCreated`
                    // 里直接 `onVideoSurfaceCreated?.invoke(...)`），而 ExoPlayer 只允许在
                    // **创建它的主线程**访问 —— 以前这里直接调 setupVideoPlayer，结果是：
                    //   `IllegalStateException: Player is accessed on the wrong thread`
                    //   抛在 `apply {}` 的最前面（setSeekParameters），于是后面的
                    //   setVideoSurface / setMediaItem / addListener / prepare **全都没执行** →
                    //   播放器是个空壳（duration=0、黑屏、进度条 00:00、拖不动也播不了）。
                    // 现在改成 post 到主线程执行；表面纹理对象本身跨线程传递是安全的。
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        setupVideoPlayer(surfaceTexture, uriStr)
                    }
                }
            }
        } else {
            // Photo active
            playerInstance?.release()
            playerInstance = null
            isVideoPlaying = false
            view.renderer.isVideoActive = false

            // v2.0.180：位图获取与缓存
            //  · 内置演示图走 DemoMediaProvider 的 LruCache（同一 id 不再重复 Canvas 绘制）；
            //    loadDemoBitmap 返回的是**母本副本**，交给渲染器上传纹理。
            //  · 导入图直接用已有的 customBitmap（渲染器自 v2.0.180 起不再 recycle 传入位图，
            //    因此可以安全复用，无需重新解码）。
            val bmp: Bitmap = withContext(Dispatchers.IO) {
                if (selectedMediaItem.isDemo) {
                    DemoMediaProvider.loadDemoBitmap(selectedMediaItem.id)
                } else {
                    // 导入图：优先复用；仅在缺失时才回退到内置测试卡
                    customBitmap ?: DemoMediaProvider.loadDemoBitmap(DemoMediaProvider.primaryDemoId)
                }
            }
            // v2.0.181：先把尺寸告诉渲染器（决定是否走分块上传），再投递位图
            view.renderer.setImageSizeHint(bmp.width, bmp.height)
            view.updateImage(bmp)
        }
    }

    // Effect A：只在“换媒体”时触发 —— 应用一次打开视频的默认视角，再绑定新媒体。
    LaunchedEffect(selectedMediaItem) {
        keepUiAlight()
        // v2.6.24（直播 P1/P3）：换媒体必须复位直播态。
        // 否则上一场的 LIVE 判定、强制 EXO 标记、提示计数会被带到下一部片：
        // 典型后果是「从直播切回点播」后进度条仍显示 LIVE（要等 3 次采样才恢复）。
        isLiveStream = false
        liveStreak[0] = 0
        liveBufferStreak[0] = 0
        liveAsrHinted = false
        liveForceExoUri = null
        liveTextTrackPicked = false
        liveReconnectJob?.cancel()
        liveReconnectJob = null
        liveReconnectAttempt = 0
        selectedVideoTrack = -1
        cookieRetryAttemptedUri = null
        val cachedGenderWav = MdxStreamingPipeline.cachedFileIfReady(context, selectedMediaItem.uri)
        genderFilterProcessor.setFilteredWav(cachedGenderWav)
        if (cachedGenderWav == null) {
            isGenderFilterEnabled = false
            genderFilterProcessor.isFilterEnabled = false
        }
        if (currentGlSurfaceView == null) return@LaunchedEffect

        if (selectedMediaItem.isVideo) {
            // Default to 180° Dome, Left visual eye perspective, SBS 3D, and disable gyroscope when video opened
            projectionMode = ProjectionMode.VR_180
            domeHalfSelect = 1
            isGyroEnabled = false
            stereoMode = StereoMode.SBS
        }
        rebindCurrentMediaToRenderer()
    }

    // Effect B：解码设置 / 容器修复变更时触发 —— 只重建播放器，**不再改动视角设置**，
    // 并接着原播放位置继续，避免切换解码方式后从头播放。
    var decoderRebindSeen by remember { mutableStateOf(false) }
    // v2.1.233：ijkOptions 也作为 key —— 改 IJK 的任一参数都会重建播放器并续播，
    // 省掉一个「应用/重启播放」按钮（否则用户改完看不到效果，会以为参数没接上）。
    // ⚠️ v2.1.244：**mpvVoMode 刻意不放进来** —— MPV 是全局单例，重建会撞
    //    `!mpctx->initialized` 断言并 native 崩溃（实测）。vo 改为创建前预判。
    // ⚠️ v2.4.32：`mpvRetryUri` 也作为 key —— Exo 打开失败时置位它（见 onPlayerError），
    //    借此**触发一次重建**并用 MPV 重试；它只在失败时变一次，不会引起意外重建。
    // ⚠️ v2.6.13：`softRetryUri` 同理（尺寸超硬解上限 → 切软解内核），同样只会变一次。
    // ⚠️ v2.6.24：`liveForceExoUri` 也作为 key —— 运行时确认是直播但内核不是 EXO 时置位它，
    //    借此触发**一次**重建切到 EXO（见进度轮询里的直播判定）。同样只会变一次。
    // ⚠️ v2.8.3：`exoRetryUri` 同理 —— MPV/IJK/系统解码失败时置位它，触发一次重建回退 EXO
    //    （只本次生效，不写回 prefs；见 state 注释）。
    // ⚠️ v2.8.4（P1）：`cookieRetryTrigger` 也作为 key —— 网页流 403/410 刷新 Cookie 后触发重建续播。
    LaunchedEffect(
        isSoftwareDecoding,
        decoderEngine,
        ijkOptions,
        mpvOptions,
        photoReloadTrigger,
        mpvRetryUri,
        softRetryUri,
        exoRetryUri,
        liveForceExoUri,
        cookieRetryTrigger
    ) {
        if (!decoderRebindSeen) {
            // 首次组合时上面的 Effect A 已经完成绑定，这里跳过，避免重复创建播放器
            decoderRebindSeen = true
            return@LaunchedEffect
        }
        keepUiAlight()
        // v2.6.24（直播 P3）：直播**不做位置续播** —— 旧播放器的位置属于"刚才那一段"，
        // 重建后 seek 回去等于让用户退回十几秒，还要再等追直播边缘；
        // 直播重建后应当直接从实时边缘开播（EXO 默认行为）。
        val resumeAt = if (isLiveStream) 0L else {
            val pPos = playerInstance?.currentPosition ?: 0L
            if (pPos > 0L) pPos else currentPositionMs
        }
        rebindCurrentMediaToRenderer()
        if (resumeAt > 0L) {
            playerInstance?.seekTo(resumeAt)
        }
    }

    // v93：切换视频时自动加载已生成的字幕（应用目录下的 _asr.srt）
    // v95 修复：先清空上一个视频的字幕，确保每个视频字幕独立
    // v96 修复：用更宽松的文件名匹配（扫描应用目录找匹配的 _asr.srt）
    LaunchedEffect(selectedMediaItem.uri) {
        // 先清空旧字幕，避免上一个视频的字幕残留
        loadedSubtitleCues = emptyList()
        loadedSubtitleFileName = ""

        // v2.8.3：**网页嗅探面板带来的字幕优先**。
        // 位置有讲究：必须在清空之后、下面 `candidateNames` 的早退之前 ——
        // 网页直链的 title 是「网页视频」、URI 是一串 URL，本地存档匹配必然为空，
        // 放在早退之后这条字幕就永远挂不上（静默失效）。
        // 用户是主动选的字幕，所以直接开字幕、并关掉实时生成（内容相同，省电省 CPU）。
        pendingWebSubtitle?.let { ws ->
            pendingWebSubtitle = null
            loadedSubtitleCues = ws.cues
            loadedSubtitleFileName = ws.name
            isSubtitleEnabled = true
            isRealtimeSubtitleEnabled = false
            if (isMemoryModeEnabled) {
                prefs.edit().putBoolean("realtime_subtitle_enabled", false).apply()
            }
            exoCueText = null
            return@LaunchedEffect
        }

        // v119 修复(#8)：内嵌字幕（ExoPlayer Cue）同样要清。
        // exoCueText 只在 onCues 回调里赋值/清空，切到图片时播放器被 release()
        // 不会再触发 onCues(empty)；切到无内嵌字幕轨的新视频同理。
        // 于是上一媒体最后一句内嵌字幕会一直贴在屏幕上
        // （SubtitleOverlay 在 loadedSubtitleCues 为空时用 exoCueText 兜底显示）。
        exoCueText = null

        val uri = selectedMediaItem.uri ?: return@LaunchedEffect
        // 多重匹配：优先用 title，再用 URI 文件名
        val candidateNames = mutableSetOf<String>()
        selectedMediaItem.title?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }?.let { candidateNames.add(it) }
        Uri.parse(uri).lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }?.let { candidateNames.add(it) }
        if (candidateNames.isEmpty()) return@LaunchedEffect

        val filesDir = context.getExternalFilesDir(null) ?: return@LaunchedEffect
        val srtFiles = filesDir.listFiles { f -> f.name.endsWith("_asr.srt") } ?: emptyArray()
        // v2.0.136：优先加载应用 data 目录 subtitles/ 下自动保存的历史字幕
        // （<视频名>_<时间戳>.srt，listSavedSubtitles 已按时间倒序），取最新一份；
        // 没有再回退到旧版整片转写的 <视频名>_asr.srt。
        val savedFiles = candidateNames.flatMap { cand ->
            SubtitleExporter.listSavedSubtitles(context, cand)
        }.distinctBy { it.absolutePath }
        // v2.0.153：优先加载与当前目标语言匹配的译文文件（带语言后缀）；
        // 没有再退回「最新的任意一份」—— 避免同一视频导出了多语言时加载到别的语言。
        val preferSuffix = SubtitleExporter.langSuffix(subtitleTranslator)
        val preferred = if (preferSuffix.isNotBlank())
            savedFiles.firstOrNull { it.name.contains(preferSuffix) && it.length() > 0 } else null
        val matchedFile: File? = preferred
            ?: savedFiles.firstOrNull { it.length() > 0 }
            ?: srtFiles.firstOrNull { srt ->
                val srtBase = srt.name.removeSuffix("_asr.srt").lowercase()
                candidateNames.any { cand -> cand.lowercase() == srtBase }
            }

        if (matchedFile != null && matchedFile.length() > 0) {
            try {
                // v2.1.248：读字节自行解码 + 统一嗅探入口
                val content = withContext(Dispatchers.IO) { SubtitleParser.decodeBytes(matchedFile.readBytes()) }
                val cues = SubtitleParser.parse(content)
                if (cues.isNotEmpty()) {
                                    loadedSubtitleCues = cues
                                    loadedSubtitleFileName = matchedFile.name
                                    // v2.0.127：只有用户从未显式关闭过字幕时才自动开启。
                                    // 原先这里无条件置 true，于是「用户关掉字幕 → 切视频/重新进入」
                                    // 又会被自动打开，表现就是字幕开关不记忆。
                                    if (!prefs.getBoolean("subtitle_user_disabled", false)) {
                                        isSubtitleEnabled = true
                                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, context.getString(R.string.toast_subtitle_autoloaded, matchedFile.name, cues.size), Toast.LENGTH_SHORT).show()
                    }
                    // v2.0.155：自动加载的历史字幕也走批量翻译。此前只有「导入文件 / 在线搜索 /
                    // 点翻译按钮」三处会触发 batch，自动加载路径只能等显示层逐条翻，
                    // 表现为字幕先显示原文、过一会儿才变译文（观感是闪烁跳动）。
                    if (subtitleTranslator.config.isEnabled) {
                        subtitleTranslator.translateCuesBatch(cues)
                    }
                }
            } catch (e: Exception) {
                Log.w("VRPlayerScreen", "Auto-load generated subtitle failed: ${e.message}")
            }
        }
    }

    // v126：实时 AI 字幕（方案文档「边播边生成」）
    // 开启后后台滚动预读：独立解码音频 → VAD 分段 → ASR → 内存缓存；
    // 播放头只需查缓存即可显示，不再等整片转写完成。
    // v2.6.24：**"要不要跑引擎"与"要不要显示字幕"是两件事** —— 后台执行模式下
    // 只跑引擎不显示（台词给 AI 弹幕用），所以这里读 isRealtimeAsrActive。
    LaunchedEffect(isRealtimeAsrActive, selectedMediaItem.uri, asrEngineType, sherpaLangCode, SherpaAsrManager.modelChoiceVersion, isLiveStream) {
        // v127f：切语言/切媒体/开关都会重跑本 effect，必须把**全部**相关状态清干净，
        // 否则屏上会残留上一轮的字幕或进度（会让用户以为"改了语言没反应"）。
        realtimeCues = emptyList()
        realtimeDone = false
        realtimeGeneratedMs = 0L
        realtimeTotalMs = 0L
        realtimeSubtitleStatus = ""
        if (!isRealtimeAsrActive) {
            realtimeSubtitleEngine.stop()
            realtimeSubtitleStatus = ""
            return@LaunchedEffect
        }
        if (!selectedMediaItem.isVideo) {
            realtimeSubtitleStatus = context.getString(R.string.toast_image_no_audio_realtime)
            return@LaunchedEffect
        }
        val uriStr = selectedMediaItem.uri ?: return@LaunchedEffect
        realtimeSubtitleEngine.refreshLookahead()
        realtimeSubtitleEngine.start(
            mediaUri = Uri.parse(uriStr),
            factory = {
                // v127：只剩 SenseVoice 一条路线；线程数取用户设置（1~10）
                SherpaAsrManager
                    .createRecognizer(context, sherpaLangCode, asrThreads)
                    ?.let { SherpaSegmentRecognizer(it) }
            },
            listener = object : RealtimeSubtitleEngine.Listener {
                override fun onCuesUpdated(cues: List<SubtitleCue>) {
                    // v2.6.24（直播 P3）：**只保留最近 10 分钟 / 500 条**。
                    // 引擎回调的是「完整快照」，点播片长有限（一部电影几千条）无所谓；
                    // 直播是无限流，挂一下午就是几万条，而字幕叠加层只需要当前 ±15s、
                    // AI 弹幕取词也只看 ±15s —— 留全量纯粹是让内存线性增长。
                    scope.launch { realtimeCues = trimRealtimeCues(cues) }
                    // v127：字幕一有新增就预读翻译游标前方的部分，
                    // 显示时直接命中缓存，译文与原文同时出现（而不是等显示才开始翻）
                    // （直播下 pretranslateAhead 内部直接 return）
                    if (subtitleTranslator.config.isEnabled) {
                        subtitleTranslator.pretranslateAhead(realtimeCues, currentPositionMs)
                    }
                }
                override fun onStatus(message: String) {
                    scope.launch { realtimeSubtitleStatus = message }
                }
            },
            isLiveStream = isLiveStream
        )
    }

    // v2.0.136：实时字幕**全片生成完成**后，自动把本次生成的字幕保存到
    // 应用 data 目录：subtitles/<视频名>_<yyyyMMdd-HHmmss>.srt。
    // 每个视频本次会话只保存一次；下次打开同一视频时按名匹配自动加载。
    var realtimeAutoSaved by remember(selectedMediaItem.uri) { mutableStateOf(false) }
    LaunchedEffect(realtimeDone, selectedMediaItem.uri) {
        if (!realtimeDone || realtimeAutoSaved) return@LaunchedEffect
        if (!selectedMediaItem.isVideo) return@LaunchedEffect
        val cues = realtimeSubtitleEngine.cache.snapshot()
        if (cues.isEmpty()) return@LaunchedEffect
        realtimeAutoSaved = true
        val saved = withContext(Dispatchers.IO) {
            // v2.0.155：自动保存同样走译文映射 —— 否则文件名带 _zh 但内容是原文，
            // 与手动导出行为不一致
            SubtitleExporter.saveTimestamped(
                context,
                selectedMediaItem.title,
                cues,
                SubtitleExporter.langSuffix(subtitleTranslator),
                isStripSubtitlePunctuation,
                { subtitleTranslator.exportTextFor(it) }
            )
        }
        if (saved != null) {
            Toast.makeText(
                context,
                context.getString(R.string.toast_subtitle_autosaved, saved.name),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Progress updates tracking
    LaunchedEffect(isVideoPlaying) {
        while (isVideoPlaying) {
            playerInstance?.let { mp ->
                try {
                    val current = mp.currentPosition
                    val duration = mp.duration
                    // v2.6.24（直播 P0）：直播判定（连续 3 次采样确认，见 state 注释）
                    if (mp.isLive || duration <= 0) {
                        liveStreak[0]++
                        if (liveStreak[0] >= 3) {
                            if (!isLiveStream) {
                                isLiveStream = true
                                // 直播没有总时长：进度条禁用，时间区显示 LIVE
                                videoDurationText = "\u25CF LIVE"
                                // v2.6.24（直播 P1）：**直播强制 EXO** 的运行时兜底。
                                // URL 启发式（MediaFormats.isLikelyLiveStream）只能看扩展名/路径，
                                // 像 `https://cdn.x.com/abc123?token=…` 这种纯随机路径判不出来；
                                // 播放器自己采样确认是直播后才在这里补一次重建。
                                // 只置 URI（幂等）：重建后新播放器要重新累计 3 次才会再次进入本分支，
                                // 而 URI 相等时 setupVideoPlayer 里恒走 EXO，不会来回切。
                                // ⚠️ 两个 retry 标记必须排除：若 EXO 已经打不开这条流、内核是被
                                //    `mpvRetryUri`/`softRetryUri` 指过去的，这里再要求"切回 EXO"
                                //    就会形成「EXO 失败 → MPV → 又要 EXO」的重建抖动
                                //    （每次重建都要 release/destroy MPV 全局单例，代价极高）。
                                //    实测：静态 HLS 清单会被 EXO 判 PlaylistStuck 并回退 MPV。
                                if (mp.engine != DecoderEngine.EXO &&
                                    mpvRetryUri != selectedMediaItem.uri &&
                                    softRetryUri != selectedMediaItem.uri
                                ) {
                                    Log.i(
                                        "VRPlayerScreen",
                                        "运行时确认直播但内核是 ${mp.engine} → 重建切到 EXO"
                                    )
                                    liveForceExoUri = selectedMediaItem.uri
                                }
                                // v2.6.24（直播 P1）：直播字幕只能来自「实时识别」或「流内嵌 CC」。
                                // 外挂 SRT 在直播下毫无意义，用户开了翻译却看不到字幕时会以为坏了 → 提示一次。
                                if (!liveAsrHinted && isSubtitleEnabled && !isRealtimeSubtitleEnabled) {
                                    liveAsrHinted = true
                                    postToast(context.getString(R.string.toast_live_need_realtime_asr))
                                }
                            }
                        }
                    } else {
                        liveStreak[0] = 0
                        if (isLiveStream) {
                            isLiveStream = false
                            liveAsrHinted = false
                            liveBufferStreak[0] = 0
                        }
                    }
                    // v2.6.24（直播 P1）：流内嵌字幕轨（CC/TTML）**自动选中一次**。
                    // 直播没有外挂字幕可加载，而「媒体轨道」面板要用户自己翻菜单才会发现
                    // 有 CC 轨 —— 不自动选的话，用户开了字幕后画面依旧空白，会以为字幕坏了。
                    // ⚠️ 只尝试一次（liveTextTrackPicked）：HLS 的 CC 轨常常要等第一个媒体段
                    //    加载完才出现在 currentTracks 里，所以放在每秒轮询里等，而不是打开时判一次。
                    if (isLiveStream && !liveTextTrackPicked) {
                        if (!isSubtitleEnabled || selectedTextTrack >= 0) {
                            liveTextTrackPicked = true      // 用户没开字幕 / 已手动选过轨 → 不插手
                        } else {
                            val textGroup = mp.exo?.currentTracks?.groups
                                ?.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
                            if (textGroup != null && textGroup.mediaTrackGroup.length > 0) {
                                liveTextTrackPicked = true
                                selectMediaTrack(androidx.media3.common.C.TRACK_TYPE_TEXT, 0)
                                Log.i("VRPlayerScreen", "直播内嵌字幕轨 ${textGroup.mediaTrackGroup.length} 条 → 自动选中第 1 条")
                            }
                        }
                    }
                    // v2.6.24（直播 P3）：断流/卡顿提示 —— 连续 5 次采样（≈5s）仍在缓冲才提示，
                    // 瞬时缓冲（切码率、扔帧）不打扰。EXO 专有：另两个内核拿不到 playbackState。
                    if (isLiveStream) {
                        val buffering = mp.exo?.playbackState == androidx.media3.common.Player.STATE_BUFFERING
                        if (buffering) {
                            liveBufferStreak[0]++
                            if (liveBufferStreak[0] == 5) videoDurationText = "\u25CF LIVE \u00B7 缓冲中"
                        } else {
                            if (liveBufferStreak[0] >= 5) videoDurationText = "\u25CF LIVE"
                            liveBufferStreak[0] = 0
                        }
                    }
                    // v2.6.20：**拖动中绝不覆盖**。轮询每秒把"真实位置"写进进度 state，
                    // 用户正在拖的 thumb 会被立即拉回去 —— 播完后位置恒等于 duration，
                    // 覆盖幅度最大（从拖到的位置直接弹回片尾），表象就是"进度条拖不动"。
                    // v2.6.24（直播 P0）：直播**不写进度与时间文本** —— 直播的 duration 可能是
                    // 「滑动窗口时长」（实测 12~15s）而非 0，若这里照写会把上面刚设置的
                    // 「● LIVE」覆盖回 00:08 / 00:15（冒烟实测踩到）。
                    if (isGenderFilterEnabled) {
                        genderFilterProcessor.updatePlaybackPositionMs(current)
                    }
                    if (!isSeekingActive && duration > 0 && !isLiveStream) {
                        videoPlaybackProgress = current.toFloat() / duration.toFloat()
                        val curSec = (current / 1000).toInt()
                        val durSec = (duration / 1000).toInt()
                        videoDurationText = String.format("%02d:%02d / %02d:%02d", curSec / 60, curSec % 60, durSec / 60, durSec % 60)
                    }
                } catch (e: Exception) {
                    // ignore transient state errors
                }
            }
            delay(1000L)
        }
    }

    // Gyroscope tracking service registration matching user toggle
    val sensorManager = remember(currentGlSurfaceView) {
        currentGlSurfaceView?.let { view -> VRSensorManager(context, view.renderer) }
    }

    // 陀螺仪朝向模式：手持横屏举着看 / 放进 VR 眼镜平放看。
    // 两种握持下"屏幕上方"对应的设备轴完全不同，用错会导致低头时画面左右转等错乱。
    var gyroOrientationMode by remember { mutableStateOf(VRSensorManager.OrientationMode.HANDHELD) }

    LaunchedEffect(gyroOrientationMode, sensorManager, isSplitScreenVR) {
        if (isSplitScreenVR) {
            sensorManager?.setOrientationMode(VRSensorManager.OrientationMode.VR_BOX)
        } else {
            sensorManager?.setOrientationMode(gyroOrientationMode)
        }
    }

    // v2.6.9 P1 & P0：Cardboard 分屏模式联动
    LaunchedEffect(isSplitScreenVR) {
        if (isSplitScreenVR) {
            // 自动对齐传感器基准视线
            sensorManager?.recenter()
            // 若开启了透镜反畸变且当前无变形，自动应用反向球面 0.35f 抵消凸透镜枕形拉伸
            if (isCardboardDistortionEnabled && warpMode == WarpMode.NONE) {
                warpMode = WarpMode.ANTI_SPHERE
                videoCurvature = 0.35f
            }
        } else {
            // 退出分屏时，若当前是反向球面，恢复为 NONE
            if (warpMode == WarpMode.ANTI_SPHERE) {
                warpMode = WarpMode.NONE
            }
        }
    }

    // v2.6.9 P0：传感器 Gaze 姿态角回调
    LaunchedEffect(sensorManager, isSplitScreenVR, isGazePointerEnabled) {
        if (isSplitScreenVR && isGazePointerEnabled) {
            sensorManager?.onGazePoseChanged = { yaw, pitch ->
                gazeYawDeg = yaw
                gazePitchDeg = pitch
            }
        } else {
            sensorManager?.onGazePoseChanged = null
        }
    }

    // 双击重置视角用的信号量。
    // 不直接在 onDoubleTap 里调 sensorManager.recenter() 的原因：AndroidView 的 factory
    // 只在首次组合时执行一次，其内部闭包会永久捕获那一刻的 sensorManager（此时还是 null，
    // 因为 currentGlSurfaceView 尚未被赋值），调用会静默失效。
    // 而 MutableState 是 remember 出来的同一实例，闭包读取永远拿到最新值。
    var recenterViewSignal by remember { mutableIntStateOf(0) }

    // ===== v2.1.210：VR 手柄消费（奇遇一体机等）=====
    // ⚠️ 用 SideEffect 而不是 DisposableEffect(Unit) 注册 handler：
    //    DisposableEffect 只在 key 变化时重建，其闭包会**永久捕获**首次组合时的
    //    isUiVisible / isSubtitleEnabled 等状态（本项目已在此栽过：
    //    时间标记球的双击读到旧 currentPositionMs）。SideEffect 每次重组都跑，
    //    handler 始终拿着**本次组合的最新闭包**。
    // ⚠️ 清理放在独立的 DisposableEffect(Unit)：退出本界面必须置空，
    //    否则按键会被已销毁的界面吃掉。
    SideEffect {
        VrGamepadBus.handler = { action ->
            val p = playerInstance
            when (action) {
                VrGamepadAction.PLAY_PAUSE -> {
                    if (p != null) {
                        if (p.isPlaying) {
                            p.pause(); isVideoPlaying = false
                        } else {
                            val dur = p.duration
                            val cur = p.currentPosition
                            if (playbackEnded || (dur > 0L && cur >= dur - 500L)) {
                                p.seekTo(0L)
                            }
                            p.play(); isVideoPlaying = true; playbackEnded = false
                        }
                    }
                    true
                }
                // 陀螺仪/VR 方案：把画面重新摆到正前方
                VrGamepadAction.RECENTER -> { recenterViewSignal++; true }
                VrGamepadAction.TOGGLE_SUBTITLE -> { isSubtitleEnabled = !isSubtitleEnabled; true }
                VrGamepadAction.TOGGLE_UI -> { isUiVisible = !isUiVisible; true }
                // BACK：优先收起控制栏；已经收起时**不消费**，交回系统（触发返回）
                VrGamepadAction.BACK -> {
                    if (isUiVisible) { isUiVisible = false; true } else false
                }
                VrGamepadAction.SEEK_FORWARD -> {
                    // v2.6.24（直播 P0 补齐）：直播不可任意 seek —— 快进等于追实时边缘、
                    // 快退会被 EXO clamp 回窗口内（外加一次重缓冲），两个动作在直播下都不成立
                    if (isLiveStream) {
                        val label = context.getString(R.string.live_seek_disabled)
                        seekHudText = label
                    } else {
                        p?.let {
                            val dur = if (it.duration > 0) it.duration else Long.MAX_VALUE
                            it.seekTo((it.currentPosition + seekForwardStep * 1000L).coerceIn(0L, dur))
                        }
                    }
                    true
                }
                VrGamepadAction.SEEK_BACKWARD -> {
                    if (isLiveStream) {
                        seekHudText = context.getString(R.string.live_seek_disabled)
                    } else {
                        p?.let {
                            it.seekTo((it.currentPosition - seekBackwardStep * 1000L).coerceAtLeast(0L))
                            if (playbackEnded) {
                                playbackEnded = false
                                it.play()
                                isVideoPlaying = true
                            }
                        }
                    }
                    true
                }
                // 设置面板的开关状态变量较多，暂不接管（返回 false 交回系统）
                VrGamepadAction.MENU -> false
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { VrGamepadBus.handler = null }
    }

    LaunchedEffect(recenterViewSignal) {
        if (recenterViewSignal > 0) sensorManager?.recenter()
    }

    LaunchedEffect(isGyroEnabled, sensorManager) {
        if (isGyroEnabled) {
            sensorManager?.start()
        } else {
            sensorManager?.stop()
        }
    }

    // 传感器注销：key 必须带上 sensorManager。
    // 若写成 DisposableEffect(Unit)，onDispose 会一直持有首次组合时的闭包快照
    // （那时 currentGlSurfaceView 还是 null、sensorManager 也是 null），stop() 永不会执行。
    DisposableEffect(sensorManager) {
        onDispose {
            // registerListener 会让 SensorEventListener 长期持有 Activity 与 renderer 的
            // 强引用，页面销毁时不注销会持续后台耗电并泄漏整个页面。
            sensorManager?.stop()
        }
    }

    // Release ExoPlayer when screen disappears
    DisposableEffect(Unit) {
        onDispose {
            playerInstance?.release()
            playerInstance = null
            currentGlSurfaceView?.release()
        }
    }

    // Main layout
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1C1B1F)) // High Density Theme deep background color
            // v2.0.168：此处**不再做 Compose 图层旋转** —— 旋转 180° 已改为系统级
            // 反向屏幕方向（见上方 LaunchedEffect 的 SCREEN_ORIENTATION_REVERSE_*）。
            // 原因：graphicsLayer 的变换只在绘制阶段生效、**不参与命中测试**，
            // 用它旋转会让所有控件都点不中（v2.0.166/167 的「旋转后不能触摸」）。
            .testTag("player_root_container")
    ) {
        // Liquid glass backdrop：捕获视频层 + 主题底色，供玻璃面板绘制（Backdrop 库，Android 12+）
        val isLiquidGlass = glassMode > 0 && Build.VERSION.SDK_INT >= 31
        // v2.4.68：0=纯色(无 backdrop) / 1=Liquid(真半透明，透出全分辨率视频) / 2=磨砂(采样+模糊)
        // （原 3=高斯模糊 已按用户要求删除，见 UiThemes.MAX_GLASS_MODE）
        val glassStyle: GlassStyle = when (glassMode) {
            2 -> GlassStyle.Frosted
            else -> GlassStyle.Liquid
        }
        // 🔴 v2.4.68（用户要求「移除那张 96px 小模糊图」）：
        // **只有磨砂档才需要 VideoBackdrop 采样**。
        //   · 液态档改成真半透明后，透出的是 SurfaceView 的全分辨率视频 —— 不再需要那张降采样帧；
        //   · 磨砂档要用它做"被漫射掉的背后内容"（模糊层作用于它）。
        // 副作用：液态档不再有 GL 线程每 30ms 的 blit + glReadPixels，省一份持续开销。
        // v2.6.18：**液态档（=1）也要采样** —— 真液态玻璃的 lens 折射/模糊必须作用于真实视频帧，
        // 否则 backdrop 里只有 ThemeBgColor 纯色，折射会退化成一圈灰边（与磨砂档"看不出模糊"同源）。
        // v2.6.18：门控跟着"该档位真正要用的特效"走 ——
        //   · 液态（1）：去掉全局模糊后只剩 lens（AGSL），33+ 才可用 → 用 supportsLens；
        //     31/32 会走纯 Canvas 降级分支、根本不读 backdrop，再采样就是纯浪费。
        //   · 磨砂（2）：模糊层走 RenderEffect，31+ 即可。
        val needsBackdrop = when (glassMode) {
            1 -> GlassCapability.supportsLens
            2 -> Build.VERSION.SDK_INT >= 31
            else -> false
        }
        // v2.1.233：背景采样只在需要的档位开着时才做（纯色/液态档不需要，白白费电）
        LaunchedEffect(needsBackdrop) {
            VideoBackdrop.enabled = needsBackdrop
            if (!needsBackdrop) VideoBackdrop.release()
        }
        DisposableEffect(Unit) {
            onDispose { VideoBackdrop.release() }
        }
        val liquidBackdrop = rememberLayerBackdrop {
            // 🔴 v2.4.46（模糊 UI 审查 #2）：**必须先读一下 `VideoBackdrop.tick`**。
            //    `front` 与 `version` 都不在 Compose 的观察范围内 —— 不读它，GL 线程提交了新帧
            //    也**不会**让本 lambda 重新执行，`rememberLayerBackdrop` 记录的还是旧内容
            //    → 面板背后的画面永远停在「第一次采样」那一帧（模糊看起来「不生效」）。
            VideoBackdrop.tick
            // v2.4.57（记录层减负）：视频帧就绪后**跳过全屏纯色填充** ——
            // 位图 dst 铺满整屏且不透明，此前每帧先画的 drawRect(ThemeBgColor) 会被完全盖住，
            // 纯属过度绘制；仅帧未就绪时用它兜底。
            val frame = VideoBackdrop.current()
            if (frame == null) {
                drawRect(ThemeBgColor)
            }
            // v2.1.233：**把视频画面画进 backdrop** ——
            // 视频画在 SurfaceView 上，Compose 采样不到它（独立窗口、打洞），
            // 于是 backdrop 里只有纯色，而「模糊纯色」= 什么都没发生。
            // 现在由 VRGLRenderer 降采样一帧送过来，玻璃里透出的就是真实画面。
            frame?.let { bmp ->
                runCatching {
                    drawImage(
                        image = bmp.asImageBitmap(),
                        srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                        srcSize = androidx.compose.ui.unit.IntSize(bmp.width, bmp.height),
                        dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                        dstSize = androidx.compose.ui.unit.IntSize(
                            size.width.roundToInt(),
                            size.height.roundToInt()
                        ),
                        // v2.4.46（审查 #2）：96px 位图要放大到整屏，用双线性（Low）即可。
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.Low
                    )
                }.onFailure {
                    android.util.Log.w(
                        "GlassBackdrop",
                        "视频帧 drawImage 失败，玻璃回退到主题底（若仍惨白则非此因）: ${it.message}"
                    )
                }
            }
            // v2.4.57：挂载点是空 Box，本 drawContent() 为空操作；保留以维持 record 契约
            drawContent()
        }

        // 🔴 v2.4.48（用户反馈「模糊生效但层级不对」）：backdrop **不能挂在 AndroidView 自己身上**。
        //    AndroidView 是 **SurfaceView 宿主**，Compose 根本不往它的图层里画东西（SurfaceView
        //    在合成器里是独立窗口、打洞）→ 被记录下来的图层是**空的** → 玻璃面板取样拿到空层
        //    → 模糊「不生效」（这正是用户说的"层级不对"）。
        //    现在改为**第一个全屏子 Box** 承载 layerBackdrop：它是真实 Compose 图层，
        //    record 块里的 `ThemeBgColor` 底色 + `VideoBackdrop` 位图才会真的被记进去。
        //    v2.4.57 注：本 Box 是**空容器**——backdrop 内容全部来自 record 块、不含其余 UI
        //    （`.layerBackdrop()` 记录的是「本节点子树 + record 块」，故 drawContent() 为空操作）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(liquidBackdrop)
                .testTag("liquid_backdrop_host")
        )

        // 🔴 v2.6.20（P2）：Liquid Merge —— 两颗液态球靠近时的"拉丝融合膜"。
        // 层级在 backdrop 之上、所有球之下（先写先画，球在后面的兄弟节点里）；
        // 只绘制不消费触摸，球的手势不受影响。节点由各球的 `liquidPressFx` 注册。
        if (isLiquidGlass) {
            LiquidMergeOverlay(modifier = Modifier.fillMaxSize())
        }

        // 🔴 v2.4.68（用户要求「球的材质要跟面板一致」）：
        // 球**直接复用面板的那一份实现**（`glassBall` 就是 `glassPanel` + CircleShape），
        // 不再为球单独定义任何配色/颗粒/透明度。
        // 于是：
        //   · 液态档 → 与面板同一套「真半透明 + 体感层」；
        //   · 磨砂档 → 与面板同一套「backdrop 采样 + 模糊层 + 12% 白 + 同一份颗粒 + 体感层」；
        //   · 纯色档 → 与面板一致（平色，不加额外材质）。
        // 这样面板改什么、球就跟着变什么，不会出现"面板磨砂、球光滑"这类不一致。
        // 6 处调用点收敛到这里（此前各写一遍，是"多副本不同步"的老来源）。
        val ballMaterialModifier: Modifier = if (isLiquidGlass) {
            Modifier.glassBall(
                backdrop = liquidBackdrop,
                style = glassStyle,
                blurRadius = null,
                // v2.6.19：0.33 → 0.22，与面板同步（球体本身还有 62% 的 LiquidGlassBallTint，
                // 透出度由那一层主导，这里只做轻微压暗）。
                onDrawSurface = { drawCircle(ThemePanelBgColor.copy(alpha = 0.22f)) },
                // v2.6.20（P2）：悬浮球参与 Liquid Press —— 按压时边缘折射增强
                // （球体的弹性放大由各球组件里的 `liquidPressFx` 负责）。
                enableLiquidPress = true
            )
        } else {
            Modifier
        }

        // 1. OpenGL standard and VR view (Always on full bleed)
        AndroidView(
            factory = { ctx ->
                VRGLSurfaceView(ctx).apply {
                    // Connect callbacks
                    onInteractionTriggered = {
                        // Keep FOV adjusted on pinch, but do not light up the entire UI on drag
                        fovDeg = renderer.fovDeg
                    }
                    onTouchEventState = { touching ->
                        isUserTouching = touching
                        if (touching) {
                            // Immersive dragging: hide the UI when the user touches/drags the video surface
                            isUiVisible = false
                        }
                    }
                    onSingleTap = {
                        if (isUiLocked) {
                            // If UI is locked, single tap simply wakes up / shows the padlock unlock button
                            keepUiAlight()
                        } else {
                            // 经典播放器行为：点击视频区域切换 UI 可见性
                            // （UI 隐藏时点击 → 显示；UI 显示时点击 → 隐藏）
                            toggleUiVisibility()
                        }
                    }
                    onDoubleTap = {
                        if (!isUiLocked && !isViewLocked) {
                            // Double tap resets position yaw/pitch to center perspective
                            renderer.run {
                                manualYaw = 0f
                                manualPitch = 0f
                            }
                            // 陀螺仪开启时，重置视角还必须重新对齐姿态基准：
                            // 传感器给的是绝对姿态，只清 manualYaw/Pitch 无法消除朝向偏移。
                            if (isGyroEnabled) recenterViewSignal++
                        }
                    }
                    currentGlSurfaceView = this
                }
            },
            update = { view ->
                // Sync continuous configuration properties across streams safely
                view.isUiLocked = isUiLocked
                view.isViewLocked = isViewLocked
                view.renderer.projectionMode = projectionMode
                // v2.1.211：鱼眼视场角（FISHEYE 模式读取，其它模式忽略）
                view.renderer.fisheyeFov = fisheyeFovDeg.toFloat()
                view.renderer.stereoMode = stereoMode
                view.renderer.beautyLevel = beautyLevel
                view.renderer.beautyTextureDetail = beautyTextureDetail
                view.renderer.beautyMasterEnabled = beautyMasterEnabled
                view.renderer.beautyEngineType = beautyEngineType
                view.renderer.gpuPixelVrFaceBeauty = gpuPixelVrFaceBeauty
                view.renderer.gpuPixelHalfResBeauty = gpuPixelHalfResBeauty
                // v2.0.182：GPUPixel 覆盖范围 —— 2D 全视频 / VR 全屏（用户要求）
                view.renderer.gpCoverageFull = true
                view.renderer.brightnessLevel = brightnessLevel
                view.renderer.contrastLevel = contrastLevel
                view.renderer.beautyWhitening = beautyWhitening
                view.renderer.beautyFaceSlimming = beautyFaceSlimming
                view.renderer.beautyBigEyes = beautyBigEyes
                view.renderer.beautyDarkCircles = beautyDarkCircles
                view.renderer.beautyNoseSlimming = beautyNoseSlimming
                view.renderer.beautyMouth = beautyMouth
                view.renderer.beautyTeethWhitening = beautyTeethWhitening
                view.renderer.beautyLipstick = beautyLipstick
                view.renderer.beautyBlush = beautyBlush
                view.renderer.beautyEyebrows = beautyEyebrows
                view.renderer.beautyLongLegs = beautyLongLegs
                view.renderer.beautySmallHead = beautySmallHead
                view.renderer.isSplitScreenVR = isSplitScreenVR
                view.renderer.vrIpdOffsetRatio = vrIpdOffsetRatio
                view.renderer.vrCinemaScale = vrCinemaScale
                view.renderer.isEyesSwapped = isEyesSwapped
                view.renderer.gyroEnabled = isGyroEnabled && !isViewLocked
                view.renderer.gyroInverted = gyroInverted
                view.renderer.isMirrored = isVideoMirrored
                view.renderer.warpMode = warpMode
                view.renderer.cylinderCurvature = videoCurvature
                view.renderer.monoEyePreference = domeHalfSelect
                view.renderer.fovDeg = fovDeg
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("opengl_vr_player_view")
        )

        // 2. VR Central Stereoscopic guidelines (Only displays under Cardboard / view split mode)
        if (isSplitScreenVR) {
            // Thin elegant neat lavender divider line guiding VR alignment in goggles
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0x80D0BCFF), Color(0x809095A6), Color.Transparent)
                        )
                    )
                    .align(Alignment.Center)
            )
            // Left & Right screen visual icons - only show when UI is awake to avoid blocking video content
            AnimatedVisibility(
                visible = isUiVisible && !isUiLocked,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = 90.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Text("L", color = Color(0x60FFFFFF), fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text("R", color = Color(0x60FFFFFF), fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // 2.4 弹幕层（v2.4.0 / P5）：位于字幕层**之下**（弹幕在顶部区域，字幕通常在下半部）
        //     ⚠️ 必须放在 SubtitleOverlay 之前，让字幕能盖在弹幕上（万一位置撞上，字幕可读性优先）
        DanmuOverlay(
            engine = danmuState.engine,
            config = danmuConfig,
            isSplitScreenVR = isSplitScreenVR,
            vrIpdOffsetRatio = vrIpdOffsetRatio,
            // v2.4.40（弹幕审查 #1）：暂停时让渲染层冻结弹幕（虚拟时钟），
            // 不再靠上层平移 bornMs —— 那样会出现「暂停还在动、恢复向右跳」。
            isPaused = !isVideoPlaying,
            modifier = Modifier.fillMaxSize()
        )

        // 2.5 Dual-Eye & Flat Mode Universal Subtitle Overlay

                                // v120：字幕层直接调用 SubtitleOverlay（已独立为 SubtitleOverlay.kt）。
                                // 原先这里多包了一层内嵌 SubtitleLayer()，并无额外重组隔离收益。
                                SubtitleOverlay(
                                    currentPositionMs = currentPositionMs,
                                    // v126：实时字幕开启时用实时缓存，否则用已加载的整片字幕
                                    subtitleCues = if (isRealtimeSubtitleEnabled) realtimeCues else loadedSubtitleCues,
                                    translator = subtitleTranslator,
                                    isSubtitleEnabled = isSubtitleEnabled,
                                    subtitleFont = subtitleFont,
                                    fontSizeSp = subtitleFontSizeSp,
                                    fontWeightVal = subtitleFontWeightVal,
                                    isItalic = isSubtitleItalic,
                                    textColor = subtitleColorOpt.color,
                                    textAlpha = subtitleTextAlpha,
                                    strokeOption = subtitleStrokeOpt,
                                    bgOption = subtitleBgOpt,
                                    offsetYRatio = subtitleOffsetYRatio,
                                    offsetXRatio = subtitleOffsetXRatio,
                                    subtitleDelayMs = subtitleDelayMs,
                                    textAlign = subtitleTextAlignOpt.textAlign,
                                    maxLines = subtitleMaxLines,
                                    isSplitScreenVR = isSplitScreenVR,
                                    vrIpdOffsetRatio = vrIpdOffsetRatio,
                                    exoCueText = exoCueText,
                                    stripPunctuation = isStripSubtitlePunctuation,
                                    enableFadeIn = subtitleFadeIn,
                                    modifier = Modifier.fillMaxSize()
                                )
        AnimatedVisibility(
            visible = showResolutionTip,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 110.dp)
                .padding(horizontal = 24.dp)
                .testTag("resolution_warning_overlay")
        ) {
            Box(
                modifier = Modifier
                    .background(Color(0xE61C1B1F), shape = RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFFFF9800), shape = RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = stringResource(R.string.resolution_hint_title),
                        tint = Color(0xFFFF9800),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = resolutionTipText,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    if (selectedMediaItem.uri != null && maxResolution != MaxResolution.UNRESTRICTED) {
                        Text(
                            text = stringResource(R.string.action_downscale),
                            color = AccentColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable {
                                    startDownscalingTranscode()
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = stringResource(R.string.action_got_it),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable { showResolutionTip = false }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // 2.9 Video Transcoding Progress Overlay
        if (isTranscoding) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xD9000000)) // Semi-transparent dark background
                    .clickable(enabled = true, onClick = {}) // Block clicks underneath
                    .testTag("transcoding_progress_overlay"),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2C30)),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, AccentColor.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .width(300.dp)
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(
                            color = AccentColor,
                            strokeWidth = 4.dp,
                            modifier = Modifier.size(48.dp)
                        )
                        
                        Text(
                            text = stringResource(R.string.downscaling_title),
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Text(
                            text = stringResource(R.string.downscaling_desc),
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                        
                        LinearProgressIndicator(
                            progress = transcodingProgress / 100f,
                            color = AccentColor,
                            trackColor = AccentColor.copy(alpha = 0.2f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                        
                        Text(
                            text = transcodingStatusText,
                            color = AccentColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // 3 & 3.5 Floating Lock Button + Top Header Panel
        PlayerTopAndLockOverlay(
            isUiVisible = isUiVisible,
            isUiLocked = isUiLocked,
            onToggleUiLocked = { isUiLocked = !isUiLocked },
            isSettingsDialogOpen = isSettingsDialogOpen,
            onToggleSettingsDialog = { isSettingsDialogOpen = !isSettingsDialogOpen },
            isSplitScreenVR = isSplitScreenVR,
            onExitSplitScreenVR = { isSplitScreenVR = false },
            onToggleSplitScreenVR = {
                val nextSplit = !isSplitScreenVR
                isSplitScreenVR = nextSplit
                if (nextSplit && stereoMode == StereoMode.MONO) {
                    val curTitle = selectedMediaItem.title ?: selectedMediaItem.uri ?: ""
                    val aspect = if (videoSourceHeight > 0) videoSourceWidth.toFloat() / videoSourceHeight.toFloat() else 0f
                    if (ProjectionAutoDetector.isSbsPlanar(aspect) || ProjectionAutoDetector.isSbsName(curTitle)) {
                        stereoMode = StereoMode.SBS
                    }
                }
            },
            themePanelBgColor = ThemePanelBgColor,
            accentColor = AccentColor,
            textLightColor = TextLightColor,
            translucentWhite10 = TranslucentWhite10,
            mediaDisplayName = getMediaDisplayName(),
            onOpenPickerSource = { pickerSourceDialogOpen = true },
            onKeepUiAlight = { keepUiAlight() },
        )

        // 3.6 Bottom Controls Panel (Modern slide/fade non-linear easing animation)
        AnimatedVisibility(
            visible = isUiVisible && !isSettingsDialogOpen && !isUiLocked,
            enter = slideInVertically(animationSpec = tween(600, easing = EaseOutQuart)) { it } + 
                    fadeIn(animationSpec = tween(400, easing = EaseInOutCubic)),
            exit = slideOutVertically(animationSpec = tween(450, easing = EaseInOutCubic)) { it } + 
                   fadeOut(animationSpec = tween(350, easing = EaseInOutCubic)),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
                    .clickable(enabled = true, onClick = { keepUiAlight() }),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {


                    // Floating Main Controls Bar（液态玻璃效果，glassMode=1 时启用）
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isLiquidGlass) Color.Transparent else ThemePanelBgColor
                        ),
                        border = BorderStroke(1.dp, Color(0x11FFFFFF)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .then(
                                if (isLiquidGlass) Modifier.glassPanel(
                                    backdrop = liquidBackdrop,
                                    shape = { RoundedCornerShape(24.dp) },
                                    style = glassStyle,
                                    // v2.1.218：不传 blurRadius —— 显式传值会**覆盖风格的默认值**，
                                    // 导致磨砂虽配了 40dp 模糊、实际仍用旧值，与液态玻璃看不出差别。
                                    // null = 由 glassStyle 决定（面板 Liquid 22/Frosted 40，球体 Liquid 11/Frosted 20）。
                                    blurRadius = null,
                                onDrawSurface = { drawRect(ThemePanelBgColor.copy(alpha = 0.22f)) }
                                ) else Modifier
                            )
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Seek timeline or Image status Row
                            if (selectedMediaItem.isVideo) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    AnimatedVisibility(
                                        visible = isHoverActive && hoverPreviewBitmap != null,
                                        enter = fadeIn(),
                                        exit = fadeOut()
                                    ) {
                                        Card(
                                            shape = RoundedCornerShape(12.dp),
                                            border = BorderStroke(2.dp, Color.White.copy(alpha = 0.3f)),
                                            colors = CardDefaults.cardColors(containerColor = Color(0xE6101015)),
                                            modifier = Modifier.padding(bottom = 8.dp)
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(6.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                hoverPreviewBitmap?.let { bmp ->
                                                    androidx.compose.foundation.Image(
                                                        bitmap = bmp.asImageBitmap(),
                                                        contentDescription = stringResource(R.string.cd_drag_thumbnail),
                                                        modifier = Modifier
                                                            .size(160.dp, 90.dp)
                                                            .clip(RoundedCornerShape(8.dp))
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))
                                                val totalSec = hoverTimeMs / 1000
                                                val minutes = totalSec / 60
                                                val seconds = totalSec % 60
                                                Text(
                                                    text = String.format("%02d:%02d", minutes, seconds),
                                                    color = AccentColor,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        }
                                    }


                                // ===== v84 UI recomposition isolation: progress bar =====
                                @Composable
                                fun PlayerProgressBar() {
                                    PlayerProgressBarControl(
                                        isLiveStream = isLiveStream,
                                        videoDurationText = videoDurationText,
                                        accentColor = AccentColor,
                                        videoPlaybackProgress = videoPlaybackProgress,
                                        onVideoPlaybackProgressChange = { videoPlaybackProgress = it },
                                        isSliderDragged = isSliderDragged,
                                        isSeekingActive = isSeekingActive,
                                        onIsSeekingActiveChange = { isSeekingActive = it },
                                        seekStartProgress = seekStartProgress,
                                        onSeekStartProgressChange = { seekStartProgress = it },
                                        seekStartValue = seekStartValue,
                                        onSeekStartValueChange = { seekStartValue = it },
                                        onIsHoverActiveChange = { isHoverActive = it },
                                        onKeepUiAlight = { keepUiAlight() },
                                        playerInstance = playerInstance,
                                        hoverTimeMs = hoverTimeMs,
                                        onHoverTimeMsChange = { hoverTimeMs = it },
                                        playbackEnded = playbackEnded,
                                        onPlaybackEndedChange = { playbackEnded = it },
                                        onIsVideoPlayingChange = { isVideoPlaying = it },
                                        aviNeedsRebuild = aviProbe?.needsIndexRebuild == true,
                                        scope = scope,
                                        seekUnsupported = seekUnsupported,
                                        isRemuxing = isRemuxing,
                                        selectedMediaItem = selectedMediaItem,
                                        onStartRemuxFix = { startRemuxFix() },
                                        sliderInteractionSource = sliderInteractionSource,
                                    )
                                }

                                PlayerProgressBar()
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = stringResource(R.string.cd_image_state),
                                            tint = Color(0xFFFFD700),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.image_static_panorama), color = AccentColor, fontSize = 11.sp)
                                    }
                                    Text(stringResource(R.string.image_pinch_zoom), color = TextSoftColor, fontSize = 11.sp)
                                }
                            }

                            // v120 拆分：播放控制栏（左/中/右三组 + 自适应布局）→ PlayerControlBar.kt
                            PlayerControlButtons(
                                accentColor = AccentColor,
                                accentOnColor = AccentOnColor,
                                isVideo = selectedMediaItem.isVideo,
                                isVideoPlaying = isVideoPlaying,
                                isGyroEnabled = isGyroEnabled,
                                onToggleGyro = { isGyroEnabled = !isGyroEnabled },
                                isViewLocked = isViewLocked,
                                onToggleViewLock = { isViewLocked = !isViewLocked },
                                isLandscape = isLandscape,
                                onToggleOrientation = { isLandscape = !isLandscape },
                                isSplitScreenVR = isSplitScreenVR,
                                onToggleSplitScreen = {
                                    val nextSplit = !isSplitScreenVR
                                    isSplitScreenVR = nextSplit
                                    if (nextSplit && stereoMode == StereoMode.MONO) {
                                        val curTitle = selectedMediaItem.title ?: selectedMediaItem.uri ?: ""
                                        val aspect = if (videoSourceHeight > 0) videoSourceWidth.toFloat() / videoSourceHeight.toFloat() else 0f
                                        if (ProjectionAutoDetector.isSbsPlanar(aspect) || ProjectionAutoDetector.isSbsName(curTitle)) {
                                            stereoMode = StereoMode.SBS
                                        }
                                    }
                                },
                                isSubtitlePanelOpen = isSubtitleQuickPanelOpen,
                                onToggleSubtitlePanel = { isSubtitleQuickPanelOpen = !isSubtitleQuickPanelOpen },
                                isSettingsOpen = isSettingsDialogOpen,
                                onToggleSettings = { isSettingsDialogOpen = !isSettingsDialogOpen },
                                onPrev = {
                                    val list = DemoMediaProvider.demoMediaList
                                    // v2.0.181：内置演示图只剩 1 张 —— 单元素时不做任何事
                                    //（原先回绕到自身会白白 recycle/重建一次高清位图，很浪费）
                                    if (list.size > 1) {
                                        val currentIndex = list.indexOfFirst { it.id == selectedMediaItem.id }
                                        if (currentIndex >= 0) {
                                            val prevIndex = if (currentIndex > 0) currentIndex - 1 else list.size - 1
                                            // v2.0.180：释放导入图占用的内存（渲染器已不再回收传入位图）
                                            customBitmap?.takeIf { !it.isRecycled }?.recycle()
                                            customBitmap = null
                                            selectedMediaItem = list[prevIndex]
                                        }
                                    }
                                },
                                onNext = {
                                    val list = DemoMediaProvider.demoMediaList
                                    if (list.size > 1) {
                                        val currentIndex = list.indexOfFirst { it.id == selectedMediaItem.id }
                                        if (currentIndex >= 0) {
                                            val nextIndex = if (currentIndex < list.size - 1) currentIndex + 1 else 0
                                            // v2.0.180：释放导入图占用的内存（渲染器已不再回收传入位图）
                                            customBitmap?.takeIf { !it.isRecycled }?.recycle()
                                            customBitmap = null
                                            selectedMediaItem = list[nextIndex]
                                        }
                                    }
                                },
                                onTogglePlayPause = {
                                    if (selectedMediaItem.isVideo) {
                                        playerInstance?.let { mp ->
                                            if (mp.isPlaying) {
                                                mp.pause()
                                                isVideoPlaying = false
                                            } else {
                                                mp.play()
                                                isVideoPlaying = true
                                                playbackEnded = false
                                            }
                                        }
                                    }
                                },
                                onResetViewCenter = {
                                    currentGlSurfaceView?.renderer?.run { manualYaw = 0f; manualPitch = 0f }
                                },
                                onUserInteraction = { keepUiAlight() }
                            )


                            // 悬浮球所在作用域：需要 BoxWithConstraints 提供容器尺寸，
                            // 并用 BoxScope.align 定位（与控制栏原本共享同一个作用域）
                            BoxWithConstraints(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                        }
                    }
                }
            }
        }

        // v2.6.9 P0：Google Cardboard 免触控视线凝视交互层
        GazePointerOverlay(
            isSplitScreenVR = isSplitScreenVR,
            isGazeEnabled = isGazePointerEnabled,
            isUiVisible = isUiVisible && !isUiLocked,
            isVideoPlaying = isVideoPlaying,
            vrIpdOffsetRatio = vrIpdOffsetRatio,
            yawDeg = gazeYawDeg,
            pitchDeg = gazePitchDeg,
            onPlayPauseToggle = {
                playerInstance?.let { mp ->
                    if (isVideoPlaying) {
                        mp.pause()
                        isVideoPlaying = false
                    } else {
                        val dur = mp.duration
                        val cur = mp.currentPosition
                        // v2.8.4：若视频已播放到片尾（playbackEnded 或已接近末帧），点击播放自动重头开始播（seekTo 0）
                        if (playbackEnded || (dur > 0L && cur >= dur - 500L)) {
                            mp.seekTo(0L)
                        }
                        mp.play()
                        isVideoPlaying = true
                        playbackEnded = false
                    }
                }
            },
            onSeekRelative = { deltaSec ->
                playerInstance?.let { mp ->
                    val cur = mp.currentPosition
                    val dur = mp.duration
                    val target = (cur + deltaSec * 1000L).coerceIn(0L, dur.coerceAtLeast(0L))
                    mp.seekTo(target)
                    // v2.8.4：片尾快退时，自动解除播完锁定并继续起播
                    if (playbackEnded) {
                        playbackEnded = false
                        mp.play()
                        isVideoPlaying = true
                    }
                }
            },
            onExitVR = {
                isSplitScreenVR = false
            },
            onRecenter = {
                sensorManager?.recenter()
                currentGlSurfaceView?.renderer?.run { manualYaw = 0f; manualPitch = 0f }
            },
            onSwapEyesToggle = {
                isEyesSwapped = !isEyesSwapped
                keepUiAlight()
            },
            onKeepUiAlight = { keepUiAlight() }
        )

        // 3.5 字幕快捷面板（已拆至 VRPlayerQuickPanels.kt）
        PlayerSubtitleQuickPanel(
            context = context,
            prefs = prefs,
            isSubtitleQuickPanelOpen = isSubtitleQuickPanelOpen,
            onClosePanel = { isSubtitleQuickPanelOpen = false },
            themePanelBgColor = ThemePanelBgColor,
            accentColor = AccentColor,
            accentOnColor = AccentOnColor,
            isMemoryModeEnabled = isMemoryModeEnabled,
            isSubtitleEnabled = isSubtitleEnabled,
            onSubtitleEnabledChange = { isSubtitleEnabled = it },
            subtitleTranslator = subtitleTranslator,
            castReceiverEnabled = castReceiverEnabled,
            onCastReceiverEnabledChange = { castReceiverEnabled = it },
            sherpaLangCode = sherpaLangCode,
            onChangeAsrLanguage = { changeAsrLanguage(it) },
            asrThreads = asrThreads,
            onAsrThreadsChange = { asrThreads = it },
            isRealtimeAsrActive = isRealtimeAsrActive,
            realtimeSubtitleEngine = realtimeSubtitleEngine,
            isRealtimeSubtitleEnabled = isRealtimeSubtitleEnabled,
            onRealtimeSubtitleEnabledChange = { isRealtimeSubtitleEnabled = it },
            bgAsrEnabled = bgAsrEnabled,
            onBgAsrEnabledChange = { bgAsrEnabled = it },
            realtimeSubtitleStatus = realtimeSubtitleStatus,
            realtimeDone = realtimeDone,
            realtimeTotalMs = realtimeTotalMs,
            realtimeGeneratedMs = realtimeGeneratedMs,
            onRegenerateRealtimeSubtitle = { regenerateRealtimeSubtitle() },
            onExportSubtitleSrt = { exportSubtitleSrt() },
            selectedMediaItem = selectedMediaItem,
            loadedSubtitleFileName = loadedSubtitleFileName,
            onLoadSavedSubtitleFile = { loadSavedSubtitleFile(it) },
            subtitleFont = subtitleFont,
            onFontChange = { subtitleFont = it },
            subtitleFontSizeSp = subtitleFontSizeSp,
            onFontSizeChange = { subtitleFontSizeSp = it },
            subtitleFontWeightVal = subtitleFontWeightVal,
            onFontWeightChange = { subtitleFontWeightVal = it },
            isSubtitleItalic = isSubtitleItalic,
            onItalicChange = { isSubtitleItalic = it },
            subtitleColorOpt = subtitleColorOpt,
            onColorOptionChange = { subtitleColorOpt = it },
            subtitleTextAlpha = subtitleTextAlpha,
            onTextAlphaChange = { subtitleTextAlpha = it },
            subtitleStrokeOpt = subtitleStrokeOpt,
            onStrokeOptionChange = { subtitleStrokeOpt = it },
            subtitleBgOpt = subtitleBgOpt,
            onBgOptionChange = { subtitleBgOpt = it },
            subtitleOffsetYRatio = subtitleOffsetYRatio,
            onOffsetYRatioChange = { subtitleOffsetYRatio = it },
            subtitleOffsetXRatio = subtitleOffsetXRatio,
            onOffsetXRatioChange = { subtitleOffsetXRatio = it },
            subtitleDelayMs = subtitleDelayMs,
            onDelayMsChange = { subtitleDelayMs = it },
            liveDelaySec = liveDelaySec,
            onLiveDelaySecChange = { liveDelaySec = it },
            isLiveStream = isLiveStream,
            subtitleTextAlignOpt = subtitleTextAlignOpt,
            onTextAlignChange = { subtitleTextAlignOpt = it },
            subtitleMaxLines = subtitleMaxLines,
            onMaxLinesChange = { subtitleMaxLines = it },
            vrIpdOffsetRatio = vrIpdOffsetRatio,
            onVrIpdOffsetRatioChange = { vrIpdOffsetRatio = it },
            onOpenFullSubtitleSettings = {
                isSubtitleQuickPanelOpen = false
                isSettingsDialogOpen = true
                expandedSettings = expandedSettings + "sub"
            },
            onKeepUiAlight = { keepUiAlight() },
        )

        // 4. Secondary Settings Dialog Panel（外框与双列骨架已拆至 VRPlayerSettingsDialog.kt）
        PlayerSettingsDialogOverlay(
            isSettingsDialogOpen = isSettingsDialogOpen,
            onDismiss = { isSettingsDialogOpen = false },
            accentColor = AccentColor,
            expandedSettings = expandedSettings,
            onToggleSettings = { toggleSettings(it) },
            onShowLicensesDialog = { showLicensesDialog = true },
            onKeepUiAlight = { keepUiAlight() },
            headerContent = {
                PlayerSettingsHeader(
                    accentColor = AccentColor,
                    onOpenSmb = { smbDialogOpen = true; keepUiAlight() },
                    onShowVideoInfo = { showVideoInfo(); keepUiAlight() },
                    onOpenTrackDialog = { trackDialogOpen = true; keepUiAlight() },
                    onClose = { isSettingsDialogOpen = false },
                )
            },
            sectionTheme = {
                PlayerSettingsThemeSection(
                    context = context,
                    prefs = prefs,
                    accentColor = AccentColor,
                    accentOnColor = AccentOnColor,
                    translucentWhite10 = TranslucentWhite10,
                    uiThemeId = uiThemeId,
                    onUiThemeIdChange = { uiThemeId = it },
                    uiTheme = uiTheme,
                    glassMode = glassMode,
                    onGlassModeChange = { glassMode = it },
                    onKeepUiAlight = { keepUiAlight() },
                )
            },
            sectionProjection = {
                PlayerSettingsProjectionSection(
                    prefs = prefs,
                    accentColor = AccentColor,
                    accentOnColor = AccentOnColor,
                    renderer = currentGlSurfaceView?.renderer,
                    isSmartProjectionEnabled = isSmartProjectionEnabled,
                    onSmartProjectionEnabledChange = { isSmartProjectionEnabled = it },
                    projectionMode = projectionMode,
                    onProjectionModeChange = { projectionMode = it },
                    onProjectionModeUserAdjustedChange = { projectionModeUserAdjusted = it },
                    domeHalfSelect = domeHalfSelect,
                    onDomeHalfSelectChange = { domeHalfSelect = it },
                    stereoMode = stereoMode,
                    onStereoModeChange = { stereoMode = it },
                    fisheyeFovDeg = fisheyeFovDeg,
                    onFisheyeFovDegChange = { fisheyeFovDeg = it },
                    fovDeg = fovDeg,
                    onFovDegChange = { fovDeg = it },
                    videoCurvature = videoCurvature,
                    onVideoCurvatureChange = { videoCurvature = it },
                    warpMode = warpMode,
                    onWarpModeChange = { warpMode = it },
                    isVideoMirrored = isVideoMirrored,
                    onVideoMirroredChange = { isVideoMirrored = it },
                    isAudioMirrored = isAudioMirrored,
                    onAudioMirroredChange = { isAudioMirrored = it },
                    gyroOrientationMode = gyroOrientationMode,
                    onGyroOrientationModeChange = { gyroOrientationMode = it },
                    gyroInverted = gyroInverted,
                    onGyroInvertedChange = { gyroInverted = it },
                    isEyesSwapped = isEyesSwapped,
                    onEyesSwappedChange = { isEyesSwapped = it },
                    isCardboardDistortionEnabled = isCardboardDistortionEnabled,
                    onCardboardDistortionEnabledChange = { isCardboardDistortionEnabled = it },
                    vrCinemaScale = vrCinemaScale,
                    onVrCinemaScaleChange = { vrCinemaScale = it },
                    vrIpdOffsetRatio = vrIpdOffsetRatio,
                    onVrIpdOffsetRatioChange = { vrIpdOffsetRatio = it },
                    isGazePointerEnabled = isGazePointerEnabled,
                    onGazePointerEnabledChange = { isGazePointerEnabled = it },
                    forceVideoType = forceVideoType,
                    onForceVideoTypeChange = { forceVideoType = it },
                    maxResolution = maxResolution,
                    onMaxResolutionChange = { maxResolution = it },
                    onKeepUiAlight = { keepUiAlight() },
                )
            },
            sectionSubtitle = {
                PlayerSettingsSubtitleSection(
                    context = context,
                    prefs = prefs,
                    scope = scope,
                    selectedMediaItem = selectedMediaItem,
                    isMemoryModeEnabled = isMemoryModeEnabled,
                    isSubtitleEnabled = isSubtitleEnabled,
                    onSubtitleEnabledChange = { isSubtitleEnabled = it },
                    isStripSubtitlePunctuation = isStripSubtitlePunctuation,
                    onStripSubtitlePunctuationChange = { isStripSubtitlePunctuation = it },
                    subtitleFadeIn = subtitleFadeIn,
                    onFadeInChange = { subtitleFadeIn = it },
                    loadedSubtitleFileName = loadedSubtitleFileName,
                    loadedSubtitleCues = loadedSubtitleCues,
                    onSubtitleCuesLoaded = { cues, name ->
                        loadedSubtitleCues = cues
                        loadedSubtitleFileName = name
                    },
                    onPickSubtitleFile = { subtitleFilePickerLauncher.launch("*/*") },
                    subtitleFont = subtitleFont,
                    onFontChange = { subtitleFont = it },
                    subtitleFontSizeSp = subtitleFontSizeSp,
                    onFontSizeChange = { subtitleFontSizeSp = it },
                    subtitleFontWeightVal = subtitleFontWeightVal,
                    onFontWeightChange = { subtitleFontWeightVal = it },
                    isSubtitleItalic = isSubtitleItalic,
                    onItalicChange = { isSubtitleItalic = it },
                    subtitleColorOpt = subtitleColorOpt,
                    onColorOptionChange = { subtitleColorOpt = it },
                    subtitleTextAlpha = subtitleTextAlpha,
                    onTextAlphaChange = { subtitleTextAlpha = it },
                    subtitleStrokeOpt = subtitleStrokeOpt,
                    onStrokeOptionChange = { subtitleStrokeOpt = it },
                    subtitleBgOpt = subtitleBgOpt,
                    onBgOptionChange = { subtitleBgOpt = it },
                    subtitleOffsetYRatio = subtitleOffsetYRatio,
                    onOffsetYRatioChange = { subtitleOffsetYRatio = it },
                    subtitleOffsetXRatio = subtitleOffsetXRatio,
                    onOffsetXRatioChange = { subtitleOffsetXRatio = it },
                    subtitleDelayMs = subtitleDelayMs,
                    onDelayMsChange = { subtitleDelayMs = it },
                    subtitleTextAlignOpt = subtitleTextAlignOpt,
                    onTextAlignChange = { subtitleTextAlignOpt = it },
                    subtitleMaxLines = subtitleMaxLines,
                    onMaxLinesChange = { subtitleMaxLines = it },
                    vrIpdOffsetRatio = vrIpdOffsetRatio,
                    onVrIpdOffsetRatioChange = { vrIpdOffsetRatio = it },
                    subtitleSearchApiKey = subtitleSearchApiKey,
                    onSubtitleSearchApiKeyChange = { subtitleSearchApiKey = it },
                    isGenderFilterEnabled = isGenderFilterEnabled,
                    onGenderFilterEnabledChange = { isGenderFilterEnabled = it },
                    genderFilterProcessor = genderFilterProcessor,
                    playerInstance = playerInstance,
                    subtitleTranslator = subtitleTranslator,
                    onExportSubtitle = { exportSubtitleSrt() },
                    accentColor = AccentColor,
                    accentOnColor = AccentOnColor,
                    onKeepUiAlight = { keepUiAlight() },
                )
            },
            sectionAiSubtitle = {
                PlayerSettingsAiSubtitleSection(
                    context = context,
                    accentColor = AccentColor,
                    accentOnColor = AccentOnColor,
                    sherpaLangCode = sherpaLangCode,
                    onChangeAsrLanguage = { changeAsrLanguage(it) },
                    onKeepUiAlight = { keepUiAlight() },
                )
            },
            sectionDanmu = {
                PlayerSettingsDanmuSection(
                    danmuConfig = danmuConfig,
                    onConfigChange = { danmuConfig = it },
                    canPersistSecrets = isMemoryModeEnabled,
                    generatedCount = danmuState.generatedCount,
                    lastError = danmuState.lastError,
                    accentColor = AccentColor,
                    importedCount = danmuState.importCount,
                    importedName = danmuState.importName,
                    importStatus = danmuState.importStatus,
                    onPickImportFile = { danmuState.launchImportPicker() },
                )
            },
            sectionFloatingBall = {
                PlayerSettingsFloatingBallSection(
                    accentColor = AccentColor,
                    accentOnColor = AccentOnColor,
                    isFloatingBallEnabled = isFloatingBallEnabled,
                    onFloatingBallEnabledChange = { isFloatingBallEnabled = it },
                    isSeekForwardBallEnabled = isSeekForwardBallEnabled,
                    onSeekForwardBallEnabledChange = { isSeekForwardBallEnabled = it },
                    isSeekBackwardBallEnabled = isSeekBackwardBallEnabled,
                    onSeekBackwardBallEnabledChange = { isSeekBackwardBallEnabled = it },
                    isMarkerBallEnabled = isMarkerBallEnabled,
                    onMarkerBallEnabledChange = { isMarkerBallEnabled = it },
                    isClockBallEnabled = isClockBallEnabled,
                    onClockBallEnabledChange = { isClockBallEnabled = it },
                    isBatteryBallEnabled = isBatteryBallEnabled,
                    onBatteryBallEnabledChange = { isBatteryBallEnabled = it },
                    isPlayBallEnabled = isPlayBallEnabled,
                    onPlayBallEnabledChange = { isPlayBallEnabled = it },
                    isPerfBallEnabled = isPerfBallEnabled,
                    onPerfBallEnabledChange = { isPerfBallEnabled = it },
                    floatingBallSpeed = floatingBallSpeed,
                    onFloatingBallSpeedChange = { floatingBallSpeed = it },
                    basePlaybackSpeed = basePlaybackSpeed,
                    onBasePlaybackSpeedChange = { basePlaybackSpeed = it },
                    onKeepUiAlight = { keepUiAlight() },
                )
            },
            sectionDecoder = {
                PlayerSettingsDecoderSection(
                    context = context,
                    accentColor = AccentColor,
                    accentOnColor = AccentOnColor,
                    decoderEngine = decoderEngine,
                    onDecoderEngineChange = { decoderEngine = it },
                    isSoftwareDecoding = isSoftwareDecoding,
                    onSoftwareDecodingChange = { isSoftwareDecoding = it },
                    maxFps = maxFps,
                    onMaxFpsChange = { maxFps = it },
                    ijkOptions = ijkOptions,
                    onIjkOptionsChange = { ijkOptions = it },
                    mpvOptions = mpvOptions,
                    onMpvOptionsChange = { mpvOptions = it },
                    currentVideoUri = currentVideoUri,
                    playerInstance = playerInstance,
                    renderer = currentGlSurfaceView?.renderer,
                    onKeepUiAlight = { keepUiAlight() },
                )
            },
            sectionEnhance = {
                PlayerSettingsEnhanceSection(
                    accentColor = AccentColor,
                    accentOnColor = AccentOnColor,
                    isMemcEnabled = isMemcEnabled,
                    onMemcEnabledChange = { isMemcEnabled = it },
                    memcTargetFps = memcTargetFps,
                    onMemcTargetFpsChange = { memcTargetFps = it },
                    isFsrEnabled = isFsrEnabled,
                    onFsrEnabledChange = { isFsrEnabled = it },
                    fsrRuleMode = fsrRuleMode,
                    onFsrRuleModeChange = { fsrRuleMode = it },
                    fsrCustomTarget = fsrCustomTarget,
                    onFsrCustomTargetChange = { fsrCustomTarget = it },
                    videoSourceWidth = videoSourceWidth,
                    videoSourceHeight = videoSourceHeight,
                    renderer = currentGlSurfaceView?.renderer,
                    onKeepUiAlight = { keepUiAlight() },
                )
            },
            section8K = {
                PlayerSettings8KSection(
                    accentColor = AccentColor,
                    accentOnColor = AccentOnColor,
                    decoderEngine = decoderEngine,
                    levelPatchEnabled = levelPatchEnabled,
                    onLevelPatchChange = { levelPatchEnabled = it },
                    level51Enabled = level51Enabled,
                    onLevel51Change = { level51Enabled = it },
                    forceHwDecoderEnabled = forceHwDecoderEnabled,
                    onForceHwDecoderChange = { forceHwDecoderEnabled = it },
                    spoofResolutionEnabled = spoofResolutionEnabled,
                    onSpoofResolutionChange = { spoofResolutionEnabled = it },
                    downscaleOutputEnabled = downscaleOutputEnabled,
                    onDownscaleOutputChange = { downscaleOutputEnabled = it },
                    addCodecParamsEnabled = addCodecParamsEnabled,
                    onAddCodecParamsChange = { addCodecParamsEnabled = it },
                    autoFallbackSoftEnabled = autoFallbackSoftEnabled,
                    onAutoFallbackSoftChange = { autoFallbackSoftEnabled = it },
                    onKeepUiAlight = { keepUiAlight() },
                )
            },
            beautyColumnContent = {
                PlayerSettingsBeautySection(
                    accentColor = AccentColor,
                    accentOnColor = AccentOnColor,
                    projectionMode = projectionMode,
                    beautyMasterEnabled = beautyMasterEnabled,
                    onBeautyMasterEnabledChange = { beautyMasterEnabled = it },
                    beautyEngineType = beautyEngineType,
                    onBeautyEngineTypeChange = { beautyEngineType = it },
                    gpuPixelVrFaceBeauty = gpuPixelVrFaceBeauty,
                    onGpuPixelVrFaceBeautyChange = { gpuPixelVrFaceBeauty = it },
                    gpuPixelHalfResBeauty = gpuPixelHalfResBeauty,
                    onGpuPixelHalfResBeautyChange = { gpuPixelHalfResBeauty = it },
                    beautyPreset = beautyPreset,
                    onBeautyPresetChange = { beautyPreset = it },
                    beautyLevel = beautyLevel,
                    onBeautyLevelChange = { beautyLevel = it },
                    beautyTextureDetail = beautyTextureDetail,
                    onBeautyTextureDetailChange = { beautyTextureDetail = it },
                    brightnessLevel = brightnessLevel,
                    onBrightnessLevelChange = { brightnessLevel = it },
                    contrastLevel = contrastLevel,
                    onContrastLevelChange = { contrastLevel = it },
                    beautyWhitening = beautyWhitening,
                    onBeautyWhiteningChange = { beautyWhitening = it },
                    lutName = lutName,
                    onLutNameChange = { lutName = it },
                    lutMix = lutMix,
                    onLutMixChange = { lutMix = it },
                    isLutLoading = isLutLoading,
                    onLutLoadingChange = { isLutLoading = it },
                    currentGlSurfaceView = currentGlSurfaceView,
                    onPickCustomLut = { lutPickerLauncher.launch("application/octet-stream") },
                    beautyFaceSlimming = beautyFaceSlimming,
                    onBeautyFaceSlimmingChange = { beautyFaceSlimming = it },
                    beautyBigEyes = beautyBigEyes,
                    onBeautyBigEyesChange = { beautyBigEyes = it },
                    beautyDarkCircles = beautyDarkCircles,
                    onBeautyDarkCirclesChange = { beautyDarkCircles = it },
                    beautyNoseSlimming = beautyNoseSlimming,
                    onBeautyNoseSlimmingChange = { beautyNoseSlimming = it },
                    beautyMouth = beautyMouth,
                    onBeautyMouthChange = { beautyMouth = it },
                    beautyTeethWhitening = beautyTeethWhitening,
                    onBeautyTeethWhiteningChange = { beautyTeethWhitening = it },
                    beautyLipstick = beautyLipstick,
                    onBeautyLipstickChange = { beautyLipstick = it },
                    beautyBlush = beautyBlush,
                    onBeautyBlushChange = { beautyBlush = it },
                    beautyEyebrows = beautyEyebrows,
                    onBeautyEyebrowsChange = { beautyEyebrows = it },
                    beautyLongLegs = beautyLongLegs,
                    onBeautyLongLegsChange = { beautyLongLegs = it },
                    beautySmallHead = beautySmallHead,
                    onBeautySmallHeadChange = { beautySmallHead = it },
                    onKeepUiAlight = { keepUiAlight() },
                )
            },
        )

        // 根级对话框（已拆至 VRPlayerRootDialogs.kt）
        PlayerRootDialogsHost(
            context = context,
            accentColor = AccentColor,
            showLicensesDialog = showLicensesDialog,
            onDismissLicensesDialog = { showLicensesDialog = false },
            showWebSniffer = showWebSniffer,
            onCloseWebSniffer = { showWebSniffer = false },
            onPlayWebChoice = { url, pageUrl, cookie, subtitleName, subtitleCues ->
                showWebSniffer = false
                HttpPlaybackHeaders.forWebPlayback(pageUrl, url, cookie)
                pendingWebSubtitle = subtitleCues.takeIf { it.isNotEmpty() }?.let {
                    WebSubtitle(name = subtitleName ?: "web", cues = it)
                }
                selectedMediaItem = MediaItem(
                    id = "web_${System.currentTimeMillis()}",
                    title = context.getString(R.string.web_sniffer_playing_title),
                    uri = url,
                    isVideo = true
                )
            },
            showUrlPlayDialog = showUrlPlayDialog,
            onDismissUrlPlayDialog = { showUrlPlayDialog = false },
            onPlayDirectUrl = { u, referer ->
                showUrlPlayDialog = false
                HttpPlaybackHeaders.forWebPlayback(referer, u)
                selectedMediaItem = MediaItem(
                    id = "url_${System.currentTimeMillis()}",
                    title = context.getString(R.string.url_play_playing_title),
                    uri = u,
                    isVideo = true
                )
            },
            videoInfoDialogText = videoInfoDialogText,
            onDismissVideoInfoDialog = { videoInfoDialogText = null },
            trackDialogOpen = trackDialogOpen,
            onDismissTrackDialog = { trackDialogOpen = false },
            playerInstance = playerInstance,
            selectedVideoTrack = selectedVideoTrack,
            selectedAudioTrack = selectedAudioTrack,
            selectedTextTrack = selectedTextTrack,
            onSelectMediaTrack = { type, idx -> selectMediaTrack(type, idx) },
            pickerSourceDialogOpen = pickerSourceDialogOpen,
            onDismissPickerSourceDialog = { pickerSourceDialogOpen = false },
            onPickGallery = {
                pickerSourceDialogOpen = false
                filePickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            },
            onPickAnyFile = {
                pickerSourceDialogOpen = false
                documentPickerLauncher?.launch(arrayOf("*/*"))
            },
            onOpenUrlPlay = {
                pickerSourceDialogOpen = false
                showUrlPlayDialog = true
            },
            onOpenWebSniffer = {
                pickerSourceDialogOpen = false
                showWebSniffer = true
            },
            smbDialogOpen = smbDialogOpen,
            onDismissSmbDialog = { smbDialogOpen = false },
            smbHost = smbHost,
            onSmbHostChange = { smbHost = it },
            smbUser = smbUser,
            onSmbUserChange = { smbUser = it },
            smbPass = smbPass,
            onSmbPassChange = { smbPass = it },
            onConnectSmb = { connectSmb() },
            smbPath = smbPath,
            onBrowseSmbParent = { parent -> browseSmb(parent) },
            smbError = smbError,
            smbEntries = smbEntries,
            onBrowseSmbDir = { next -> browseSmb(encodeSmb(next)) },
            onPlaySmbFile = { entry -> playSmbFile(entry) },
        )

        PlayerFloatingBallsOverlay(
            context = context,
            prefs = prefs,
            maxWidthPx = constraints.maxWidth.toFloat(),
            maxHeightPx = constraints.maxHeight.toFloat(),
            ballsAllowed = !showWebSniffer && !isSplitScreenVR,
            isMemoryModeEnabled = isMemoryModeEnabled,
            isLiquidGlass = isLiquidGlass,
            ballMaterialModifier = ballMaterialModifier,
            accentColor = AccentColor,
            accentOnColor = AccentOnColor,
            playerInstance = playerInstance,
            isLiveStream = isLiveStream,
            currentPositionMs = currentPositionMs,
            isFloatingBallEnabled = isFloatingBallEnabled,
            floatingBallSpeed = floatingBallSpeed,
            basePlaybackSpeed = basePlaybackSpeed,
            isFloatingBallPressed = isFloatingBallPressed,
            onFloatingBallPressedChange = { isFloatingBallPressed = it },
            onFloatingBallSpeedChange = { floatingBallSpeed = it },
            isMarkerBallEnabled = isMarkerBallEnabled,
            isClockBallEnabled = isClockBallEnabled,
            isBatteryBallEnabled = isBatteryBallEnabled,
            isPlayBallEnabled = isPlayBallEnabled,
            isPerfBallEnabled = isPerfBallEnabled,
            isSeekForwardBallEnabled = isSeekForwardBallEnabled,
            seekForwardStep = seekForwardStep,
            onSeekForwardStepChange = { seekForwardStep = it },
            isSeekBackwardBallEnabled = isSeekBackwardBallEnabled,
            seekBackwardStep = seekBackwardStep,
            onSeekBackwardStepChange = { seekBackwardStep = it },
            seekHudText = seekHudText,
            onSeekHudTextChange = { seekHudText = it },
        )
    }
}
