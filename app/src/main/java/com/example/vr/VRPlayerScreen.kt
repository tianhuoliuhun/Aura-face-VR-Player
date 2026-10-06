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
import com.example.vr.huawei.HuaweiVrActivity
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.InputStream
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

private val CustomEaseOutBack = Easing { fraction ->
    val t = fraction - 1.0f
    val c1 = 1.70158f
    val c3 = c1 + 1.0f
    1.0f + c3 * t * t * t + c1 * t * t
}

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
    var isMemoryModeEnabled by remember { mutableStateOf(prefs.getBoolean("is_memory_mode_enabled", true)) }

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
    // v2.0.160：原「对比原图」改为「美颜总开关」；v2.0.172：默认改为**关**，开关状态记忆
    // （记忆模式下重启恢复上次状态；未设置时默认关）
    var beautyMasterEnabled by remember {
        mutableStateOf(if (isMemoryModeEnabled) prefs.getBoolean("beauty_master_enabled", false) else false)
    }
    // v2.0.160：美颜引擎（0 = GLSL 内置，1 = GPUPixel）。两套引擎的检测与参数完全独立
    var beautyEngineType by remember {
        mutableIntStateOf(if (isMemoryModeEnabled) prefs.getInt("beauty_engine_type", BEAUTY_ENGINE_GLSL) else BEAUTY_ENGINE_GLSL)
    }
    var beautyGpSmooth by remember {
        mutableFloatStateOf(if (isMemoryModeEnabled) prefs.getFloat("beauty_gp_smooth", 0.7f) else 0.7f)
    }
    var beautyGpWhite by remember {
        mutableFloatStateOf(if (isMemoryModeEnabled) prefs.getFloat("beauty_gp_white", 0.4f) else 0.4f)
    }
    var beautyGpSharpen by remember {
        mutableFloatStateOf(if (isMemoryModeEnabled) prefs.getFloat("beauty_gp_sharpen", 0.3f) else 0.3f)
    }
    var beautyGpSlim by remember {
        mutableFloatStateOf(if (isMemoryModeEnabled) prefs.getFloat("beauty_gp_slim", 0.4f) else 0.4f)
    }
    var beautyGpEyeZoom by remember {
        mutableFloatStateOf(if (isMemoryModeEnabled) prefs.getFloat("beauty_gp_eye_zoom", 0.3f) else 0.3f)
    }
    // v2.0.160（P3）：GPUPixel 方案下把人脸美颜也应用到 VR/全景视频（屏幕空间后处理）。
    // v2.0.184：恢复为**真开关**（默认开）—— v2.0.182 曾把它从激活条件里拿掉（当时 VR 无条件生效），
    // 于是它退化成 UI 占位；现重新纳入 isGpuPixelActive()，让用户能真正控制「VR 下是否启用 GPUPixel 美颜」。
    var gpuPixelVrFaceBeauty by remember {
        mutableStateOf(if (isMemoryModeEnabled) prefs.getBoolean("beauty_gp_vr_face", true) else true)
    }
    // v2.0.187：GPUPixel 美颜半分辨率处理（默认开，跟随记忆模式）
    var gpuPixelHalfResBeauty by remember {
        mutableStateOf(if (isMemoryModeEnabled) prefs.getBoolean("beauty_gp_half_res", true) else true)
    }
    var beautyLevel by remember {
        mutableFloatStateOf(
            if (isMemoryModeEnabled) prefs.getFloat("beauty_level", 0.65f) else 0.65f
        )
    }
    // v2.0.159：磨皮「皮肤质感」= 频域分离的高频保留度（0.5~1.3；> 1 相当于 USM 锐化）
    var beautyTextureDetail by remember {
        mutableFloatStateOf(
            if (isMemoryModeEnabled) prefs.getFloat("beauty_texture_detail", 0.88f) else 0.88f
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
            if (isMemoryModeEnabled) prefs.getFloat("beauty_whitening", 0.5f) else 0.5f
        )
    }
    var beautyFaceSlimming by remember {
        mutableFloatStateOf(
            if (isMemoryModeEnabled) prefs.getFloat("beauty_face_slimming", 0.4f) else 0.4f
        )
    }
    var beautyBigEyes by remember {
        mutableFloatStateOf(
            if (isMemoryModeEnabled) prefs.getFloat("beauty_big_eyes", 0.3f) else 0.3f
        )
    }
    var beautyDarkCircles by remember {
        mutableFloatStateOf(
            if (isMemoryModeEnabled) prefs.getFloat("beauty_dark_circles", 0.3f) else 0.3f
        )
    }
    var beautyNoseSlimming by remember {
        mutableFloatStateOf(
            if (isMemoryModeEnabled) prefs.getFloat("beauty_nose_slimming", 0.2f) else 0.2f
        )
    }
    var beautyMouth by remember {
        mutableFloatStateOf(
            if (isMemoryModeEnabled) prefs.getFloat("beauty_mouth", 0.2f) else 0.2f
        )
    }
    var beautyTeethWhitening by remember {
        mutableFloatStateOf(
            if (isMemoryModeEnabled) prefs.getFloat("beauty_teeth_whitening", 0.3f) else 0.3f
        )
    }
    var beautyLipstick by remember {
        mutableFloatStateOf(
            if (isMemoryModeEnabled) prefs.getFloat("beauty_lipstick", 0.3f) else 0.3f
        )
    }
    var beautyBlush by remember {
        mutableFloatStateOf(
            if (isMemoryModeEnabled) prefs.getFloat("beauty_blush", 0.3f) else 0.3f
        )
    }
    var beautyEyebrows by remember {
        mutableFloatStateOf(
            if (isMemoryModeEnabled) prefs.getFloat("beauty_eyebrows", 0.4f) else 0.4f
        )
    }
    var beautyLongLegs by remember {
        mutableFloatStateOf(
            if (isMemoryModeEnabled) prefs.getFloat("beauty_long_legs", 0.4f) else 0.4f
        )
    }
    var beautySmallHead by remember {
        mutableFloatStateOf(
            if (isMemoryModeEnabled) prefs.getFloat("beauty_small_head", 0.3f) else 0.3f
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

    // v2.0.174：华为 VR Glass（VR Engine）接入开关。
    // 默认关（华为设备占比低，避免误入）；受「记忆模式」门控，与其它设置一致。
    // 该开关仅决定「默认后端与入口是否显示」，真正的 VR 会话由华为 Runtime 接管：
    //   - 每眼 swapchain / FOV / IPD / 头姿 全部由 OpenXR Runtime 提供，
    //     因此华为模式下 fovDeg、vrIpdOffsetRatio、isGyroEnabled、isSplitScreenVR 均不生效。
    //   - ProjectionMode（平面/球面/穹顶几何）仍然生效，用于决定内容如何映射。
    // 详见 HUAWEI_VR_ENGINE_PLAN_2026-09-26.md 第 6 节。
    var huaweiVrEnabled by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("huawei_vr_enabled", false) else false
        )
    }
    // 每眼分辨率相对 Runtime 推荐值（1552×1552/眼）的比例，预留性能调档：1.0 / 0.75 / 0.5
    var huaweiVrRenderScale by remember {
        mutableFloatStateOf(
            if (isMemoryModeEnabled) prefs.getFloat("huawei_vr_render_scale", 1.0f) else 1.0f
        )
    }
    // 手柄 6DoF 模式（P5 才实际使用，先落状态与开关）
    var huaweiVrPrefer6dof by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("huawei_vr_prefer_6dof", false) else false
        )
    }
    // 运行时可用性缓存在状态里：开关行与路由都要读，避免每帧查 PackageManager
    val huaweiVrRuntimeAvailable = remember { isHuaweiVrRuntimeAvailable(context) }

    // v2.0.205：机型是否为华为/荣耀 —— 用户要求「机型非华为、荣耀不显示 VR Glass 开关」。
    // ⚠️ 用 manufacturer + brand 双字段（部分华为设备 brand 会是第三方渠道名），
    //    忽略大小写；缓存进 remember，避免每次重组都读 Build。
    val isHuaweiOrHonorDevice = remember {
        val mfr = android.os.Build.MANUFACTURER ?: ""
        val brand = android.os.Build.BRAND ?: ""
        listOf("huawei", "honor").any {
            mfr.contains(it, ignoreCase = true) || brand.contains(it, ignoreCase = true)
        }
    }

    // Playback state
    var isVideoPlaying by remember { mutableStateOf(false) }
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
    var loadedSubtitleFileName by remember { mutableStateOf("") }
    var loadedSubtitleCues by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }
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
                modelName = prefs.getString("translate_model_name", "") ?: ""
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
        subtitleTranslator.config.modelName
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
            } else {
                remove("translate_engine_id")
                remove("translate_display_mode_id")
                remove("translate_target_lang_id")
                remove("translate_api_key")
                remove("translate_base_url")
                remove("translate_model_name")
            }
            apply()
        }
    }

    // ===== v2.2.0（P1）：AI 弹幕配置 =====
    // 与翻译配置**有意分离**：翻译用文本模型、弹幕用视觉模型，复用同一份会互相污染
    // （方案文档 D1 决策）。默认端点/模型为小米 MiMo V2.6 Flash（OpenAI 兼容）。
    var danmuSettingsRestored by remember { mutableStateOf(false) }
    var danmuConfig by remember { mutableStateOf(DanmuConfig()) }
    LaunchedEffect(Unit) {
        if (isMemoryModeEnabled) {
            danmuConfig = DanmuConfig(
                isEnabled = prefs.getBoolean("danmu_enabled", false),
                apiKey = prefs.getString("danmu_api_key", "") ?: "",
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
                opacityPercent = prefs.getInt("danmu_opacity", DanmuConfig.DEFAULT_OPACITY),
                fontSizeSp = prefs.getInt("danmu_font_size", DanmuConfig.DEFAULT_FONT_SIZE_SP),
                // v2.3.0：全局颜色（存 id，不存 ordinal）
                textColorId = prefs.getInt("danmu_text_color", DanmuConfig.DEFAULT_TEXT_COLOR_ID),
                strokeId = prefs.getInt("danmu_stroke", DanmuConfig.DEFAULT_STROKE_ID),
                bgId = prefs.getInt("danmu_bg", DanmuConfig.DEFAULT_BG_ID),
                // v2.4.1：素材来源（存枚举 id，不存 ordinal）
                sourceModeId = prefs.getInt(
                    "danmu_source_mode", DanmuConfig.DEFAULT_SOURCE_MODE_ID
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
                putString("danmu_api_key", danmuConfig.apiKey)
                putString("danmu_base_url", danmuConfig.baseUrl)
                putString("danmu_model_name", danmuConfig.modelName)
                putString("danmu_persona", danmuConfig.personaPrompt)
                putInt("danmu_interval_sec", danmuConfig.intervalSec)
                putInt("danmu_batch_size", danmuConfig.batchSize)
                putInt("danmu_speed", danmuConfig.speedPxPerSec)
                putInt("danmu_max_tracks", danmuConfig.maxTracks)
                putInt("danmu_opacity", danmuConfig.opacityPercent)
                putInt("danmu_font_size", danmuConfig.fontSizeSp)
                putInt("danmu_text_color", danmuConfig.textColorId)
                putInt("danmu_stroke", danmuConfig.strokeId)
                putInt("danmu_bg", danmuConfig.bgId)
                putInt("danmu_source_mode", danmuConfig.sourceModeId)
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
                remove("danmu_opacity")
                remove("danmu_font_size")
                remove("danmu_text_color")
                remove("danmu_stroke")
                remove("danmu_bg")
                remove("danmu_source_mode")
            }
            apply()
        }
    }

    // v126：实时 AI 字幕引擎（方案文档「边播边生成」，不写 SRT 文件）
    val realtimeSubtitleEngine = remember { RealtimeSubtitleEngine(context) }
    var isRealtimeSubtitleEnabled by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) prefs.getBoolean("realtime_subtitle_enabled", false) else false
        )
    }
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
            if (isRealtimeSubtitleEnabled) context.getString(R.string.asr_lang_switch_hint, label) else context.getString(R.string.asr_lang_label, label),
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
    fun exportSubtitleSrt() {
        val cues = if (isRealtimeSubtitleEnabled) realtimeCues else loadedSubtitleCues
        if (cues.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.toast_no_subtitle_export), Toast.LENGTH_SHORT).show()
            return
        }
        // v2.0.155：先统计有多少条还没译文 —— 导出**只吃缓存、不发起请求**，
        // 未翻译的条目会以原文写入，必须如实告知，否则用户会以为导出坏了。
        val untranslated =
            if (subtitleTranslator.config.isEnabled) {
                cues.count { subtitleTranslator.cachedTranslationOf(it.text) == null }
            } else 0
        val f = SubtitleExporter.exportSrt(
            context,
            selectedMediaItem.title,
            cues,
            SubtitleExporter.langSuffix(subtitleTranslator),
            isStripSubtitlePunctuation,
            { subtitleTranslator.exportTextFor(it) }
        )
        Toast.makeText(
            context,
            when {
                f == null -> context.getString(R.string.toast_subtitle_export_failed)
                untranslated > 0 -> context.getString(
                    R.string.toast_subtitle_exported_untranslated,
                    cues.size,
                    untranslated,
                    f.absolutePath
                )
                else -> context.getString(R.string.toast_subtitle_exported, cues.size, f.absolutePath)
            },
            Toast.LENGTH_LONG
        ).show()
    }

    /** v127e：重新生成实时字幕（清空缓存后按当前播放点重新走优先级调度） */
    fun regenerateRealtimeSubtitle() {
        if (!isRealtimeSubtitleEnabled) {
            Toast.makeText(context, context.getString(R.string.toast_enable_realtime_first), Toast.LENGTH_SHORT).show()
            return
        }
        if (!selectedMediaItem.isVideo) {
            Toast.makeText(context, context.getString(R.string.toast_image_no_audio), Toast.LENGTH_SHORT).show()
            return
        }
        realtimeCues = emptyList()
        realtimeDone = false
        realtimeSubtitleEngine.restart()
        Toast.makeText(context, context.getString(R.string.toast_subtitle_restarted), Toast.LENGTH_SHORT).show()
    }

    /**
     * v2.0.136：加载应用 data 目录 subtitles/ 下的历史字幕文件，
     * 并把字幕源切换到本地（关闭实时生成——内容相同，省电省 CPU）。
     */
    fun loadSavedSubtitleFile(f: File) {
        // ⚠️ 读文件 + 解析字幕**必须**放 IO 线程：字幕文件从几十 KB 到几 MB 不等，
        //    而这个函数是从 `.clickable { }` 里直接调的（也就是**主线程**）——
        //    主线程做这件事会在用户点下去之后冻住 UI 一段时间（文件越大越明显）。
        //    ⚠️ 同一功能的另一处「设置面板 → 已下载字幕加载」早就用了
        //       `scope.launch(Dispatchers.IO)`，只有这里漏了 —— 典型的「同功能两处实现、
        //       改一处漏一处」。两处现在都走 IO 线程。
        scope.launch(Dispatchers.IO) {
            try {
                // ⚠️ v2.1.248：读字节自行解码（GBK/Big5 老字幕）+ 走统一嗅探入口
                //    （自动识别 ASS/SSA）。见 SubtitleParser.decodeBytes / parse。
                val content = SubtitleParser.decodeBytes(f.readBytes())
                val cues = SubtitleParser.parse(content)
                withContext(Dispatchers.Main) {
                    if (cues.isEmpty()) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_subtitle_empty_format, f.name),
                            Toast.LENGTH_LONG
                        ).show()
                        return@withContext
                    }
                    loadedSubtitleCues = cues
                    loadedSubtitleFileName = f.name
                    isSubtitleEnabled = true
                    isRealtimeSubtitleEnabled = false
                    if (isMemoryModeEnabled) {
                        prefs.edit().putBoolean("realtime_subtitle_enabled", false).apply()
                    }
                    Toast.makeText(context, context.getString(R.string.toast_subtitle_autoloaded, f.name, cues.size), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.w("VRPlayerScreen", "load saved subtitle failed: ${e.message}")
            }
        }
    }

    // 后台生成全片 SRT 字幕（v110）：支持双引擎 Vosk / Qwen3-ASR
    fun startBatchTranscribe() {
        // v127：整片转写（生成 _asr.srt）已停用，改为边播边生成的实时字幕
        // （RealtimeSubtitleEngine）。原实现保留于此以便回退，不再参与交互。
        /*
        val uriStr = selectedMediaItem.uri ?: return
        if (isBatchTranscribing) return
        // v123：图片没有音轨，此前直接开跑会在解码阶段才失败（提示"音轨解码失败"，
        // 用户无从判断）。这里提前拦下并给明确说明。
        if (!selectedMediaItem.isVideo) {
            batchTranscribeStatus = stringResource(R.string.toast_image_no_audio_gen)
            Toast.makeText(context, batchTranscribeStatus, Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            isBatchTranscribing = true
            batchTranscribeProgress = 0f
            batchTranscribeStatus = if (asrEngineType != AsrEngineType.VOSK) "准备 $asrEngineType 引擎..." else "准备 Vosk 识别模型..."
            val file = run {
                batchTranscriber.transcribeToSrtSherpa(
                    mediaUri = Uri.parse(uriStr),
                    videoTitle = selectedMediaItem.title,
                    engine = asrEngineType,
                    language = sherpaLangCode,
                    onStatus = { batchTranscribeStatus = it },
                    onProgress = { batchTranscribeProgress = it }
                )
            } else {
                val modelOption = asrManager.config.modelOption
                batchTranscriber.transcribeToSrt(
                    mediaUri = Uri.parse(uriStr),
                    videoTitle = selectedMediaItem.title,
                    modelOption = modelOption,
                    modelProvider = { opt -> asrManager.ensureModelForBatch(opt) },
                    onStatus = { batchTranscribeStatus = it },
                    onProgress = { batchTranscribeProgress = it }
                )
            }
            isBatchTranscribing = false
            if (file != null) {
                // v89：生成后自动加载并显示字幕
                // v2.1.248：改走解码 + 嗅探统一入口（与其余字幕加载点保持一致）
                val content = withContext(Dispatchers.IO) { SubtitleParser.decodeBytes(file.readBytes()) }
                val cues = SubtitleParser.parse(content)
                loadedSubtitleCues = cues
                loadedSubtitleFileName = file.name
                isSubtitleEnabled = true
                Toast.makeText(context, "字幕已生成并加载：${file.name}（${cues.size} 句）", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, context.getString(R.string.toast_subtitle_gen_failed, batchTranscriber.statusMessage), Toast.LENGTH_LONG).show()
            }
        }
        */
    }



    var isFloatingBallPressed by remember { mutableStateOf(false) }
    // v103：悬浮球按下时的速度提示条（仅显示 1 秒后自动隐藏）
    var showSpeedHud by remember { mutableStateOf(false) }
    // v104：LUT 视频滤镜状态
    var lutName by remember { mutableStateOf(context.getString(R.string.lut_none)) }       // 当前滤镜名
    var lutMix by remember { mutableFloatStateOf(0.8f) }       // 滤镜强度 0~1
    var isLutLoading by remember { mutableStateOf(false) }     // 解析中
    // v91：主界面字幕快捷面板
    var isSubtitleQuickPanelOpen by remember { mutableStateOf(false) }
    // v91：设置面板左列折叠分组展开状态（需在外层供快捷面板引用）
    var expandedSettings by remember { mutableStateOf(setOf("theme")) }
    var ballOffsetX by remember { mutableFloatStateOf(0f) }
    var ballOffsetY by remember { mutableFloatStateOf(0f) }
    var isBallPositionInitialized by remember { mutableStateOf(false) }

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
        beautyGpSmooth,
        beautyGpWhite,
        beautyGpSharpen,
        beautyGpSlim,
        beautyGpEyeZoom,
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
        huaweiVrEnabled,
        huaweiVrRenderScale,
        huaweiVrPrefer6dof,
        asrThreads,
        gyroInverted,
        fovDeg,
        isVideoMirrored,
        domeHalfSelect,
        warpMode,
        isAudioMirrored,
        videoCurvature,
        maxResolution,
        isFloatingBallEnabled,
        isSeekForwardBallEnabled,
        isSeekBackwardBallEnabled,
        isMarkerBallEnabled,
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
        beautyPreset
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
            putBoolean("is_memory_mode_enabled", isMemoryModeEnabled)
            if (isMemoryModeEnabled) {
                putInt("projection_mode", projectionMode.id)
                // v2.1.211：鱼眼视场角（与投影模式同组持久化）
                putInt("fisheye_fov_deg", fisheyeFovDeg)
                putInt("stereo_mode", stereoMode.id)
                putFloat("beauty_level", beautyLevel)
                putFloat("beauty_texture_detail", beautyTextureDetail)
                // v2.0.160：双引擎相关
                putBoolean("beauty_master_enabled", beautyMasterEnabled)
                putInt("beauty_engine_type", beautyEngineType)
                putFloat("beauty_gp_smooth", beautyGpSmooth)
                putFloat("beauty_gp_white", beautyGpWhite)
                putFloat("beauty_gp_sharpen", beautyGpSharpen)
                putFloat("beauty_gp_slim", beautyGpSlim)
                putFloat("beauty_gp_eye_zoom", beautyGpEyeZoom)
                putBoolean("beauty_gp_vr_face", gpuPixelVrFaceBeauty)
                putBoolean("beauty_gp_half_res", gpuPixelHalfResBeauty)
                putFloat("brightness_level", brightnessLevel)
                putFloat("contrast_level", contrastLevel)
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
                if (beautyPresetId >= 0) putInt("beauty_preset_id", beautyPresetId)
                putBoolean("is_split_screen_vr", isSplitScreenVR)
                putBoolean("is_gyro_enabled", isGyroEnabled)
                putBoolean("huawei_vr_enabled", huaweiVrEnabled)
                putFloat("huawei_vr_render_scale", huaweiVrRenderScale)
                putBoolean("huawei_vr_prefer_6dof", huaweiVrPrefer6dof)
                putInt("asr_threads", asrThreads)
                putBoolean("gyro_inverted", gyroInverted)
                putFloat("fov_deg", fovDeg)
                putBoolean("is_video_mirrored", isVideoMirrored)
                putInt("dome_half_select", domeHalfSelect)
                putInt("warp_mode", warpMode.id)
                putBoolean("is_audio_mirrored", isAudioMirrored)
                putFloat("video_curvature", videoCurvature)
                putInt("max_resolution_id", maxResolution.id)
                putBoolean("is_floating_ball_enabled", isFloatingBallEnabled)
                putBoolean("is_seek_forward_ball_enabled", isSeekForwardBallEnabled)
                putBoolean("is_seek_backward_ball_enabled", isSeekBackwardBallEnabled)
                // ⚠️ 新增持久化项必须**同时**改三处：key 列表 + 这里的 put + else 分支的 remove。
                //    漏了 key 会导致永不落盘（本项目头号坑）。
                putBoolean("is_marker_ball_enabled", isMarkerBallEnabled)
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
                // v2.1.234：MPV 内核参数
                putBoolean("mpv_hwdec", mpvOptions.hwdec)
                putBoolean("mpv_framedrop", mpvOptions.frameDrop)
                putInt("mpv_cache_mb", mpvOptions.cacheMb)
                putBoolean("is_subtitle_enabled", isSubtitleEnabled)
                putBoolean("subtitle_strip_punct", isStripSubtitlePunctuation)
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
                remove("beauty_level")
                // v2.0.184：补齐此前遗漏的美颜 key（关闭记忆模式时应一并清除，
                // 否则残留旧值会在下次开启记忆模式时被"恢复"成过期状态）
                remove("beauty_texture_detail")
                remove("beauty_master_enabled")
                remove("beauty_engine_type")
                remove("beauty_gp_smooth")
                remove("beauty_gp_white")
                remove("beauty_gp_sharpen")
                remove("beauty_gp_slim")
                remove("beauty_gp_eye_zoom")
                remove("beauty_gp_vr_face")
                remove("beauty_gp_half_res")
                remove("brightness_level")
                remove("contrast_level")
                remove("beauty_whitening")
                remove("beauty_face_slimming")
                remove("beauty_big_eyes")
                remove("beauty_dark_circles")
                remove("beauty_nose_slimming")
                remove("beauty_mouth")
                remove("beauty_teeth_whitening")
                remove("beauty_lipstick")
                remove("beauty_blush")
                remove("beauty_eyebrows")
                remove("beauty_long_legs")
                remove("beauty_small_head")
                remove("beauty_preset_id")
                remove("is_split_screen_vr")
                remove("is_gyro_enabled")
                remove("huawei_vr_enabled")
                remove("huawei_vr_render_scale")
                remove("huawei_vr_prefer_6dof")
                remove("fov_deg")
                remove("is_video_mirrored")
                remove("dome_half_select")
                remove("warp_mode")
                remove("is_cylinder_enabled")
                remove("is_audio_mirrored")
                remove("video_curvature")
                remove("max_resolution_id")
                remove("is_floating_ball_enabled")
                remove("floating_ball_speed")
                remove("base_playback_speed")
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
                // v2.1.234：MPV 参数 key（同上，必须与 put 成对）
                remove("mpv_hwdec")
                remove("mpv_framedrop")
                remove("mpv_cache_mb")
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

    // 主题系统（8/2 功能：6 套主题色 + 玻璃模式）
    var uiThemeId by remember { mutableStateOf(UiThemes.loadThemeId(prefs)) }
    var glassMode by remember { mutableStateOf(UiThemes.loadGlassMode(prefs)) }
    val uiTheme = UiThemes.byId(uiThemeId)
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
            description = context.getString(R.string.media_imported_desc, uri.lastPathSegment)
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

    // ===== v2.3.1（P2 + P4）：AI 弹幕编排 =====
    // 链路：定时触发 → GL 取帧（P3）→ 视觉模型（P2）→ 引擎入队/去重/分轨（P4）→ 渲染（P5，未做）
    //
    // ⚠️ 本版**只做「取帧→请求→入队」**，弹幕**尚未上屏**（渲染层 P5 在下一版）。
    //    因此这里产生的结果会存进 danmuEngine，供下一版渲染层直接消费。
    // ⚠️ 本块**必须位于 `currentGlSurfaceView` 声明之后**（依赖它取 renderer）。
    //
    // 设计要点（对应方案文档 P2/P4）：
    // - 视觉客户端与引擎都 remember 一次，不随重组重建
    // - 定时器用 LaunchedEffect + while(true) + delay，**仅在开启时运行**（关闭即取消协程）
    // - 请求在 IO 线程、串行（一次未回来不再发起下一次），天然限流
    // - 帧异常/请求失败一律静默跳过，绝不影响播放
    val danmuVisionClient = remember { DanmuVisionClient() }
    val danmuEngine = remember { DanmuEngine() }
    // 供 UI 显示「已生成」条数（渲染层未接前，用于确认链路已通）
    var danmuGeneratedCount by remember { mutableIntStateOf(0) }
    var danmuLastError by remember { mutableStateOf("") }

    // 运行参数跟随配置变化
    LaunchedEffect(danmuConfig.speedPxPerSec, danmuConfig.maxTracks, danmuConfig.dedupThresholdPercent) {
        danmuEngine.speedPxPerSec = danmuConfig.speedPxPerSec
        danmuEngine.maxTracks = danmuConfig.maxTracks
        danmuEngine.dedupThresholdPercent = danmuConfig.dedupThresholdPercent
    }

    // 关闭弹幕时清空（避免重新打开后立刻涌出旧弹幕）
    LaunchedEffect(danmuConfig.isEnabled) {
        if (!danmuConfig.isEnabled) {
            danmuEngine.reset()
            danmuGeneratedCount = 0
            danmuLastError = ""
        }
    }

    // 主循环：按 intervalSec 周期执行一次「取素材 → 请求 → 入队」
    //
    // v2.4.1：素材不再只有截图 —— 由 `danmuConfig.sourceMode` 决定：
    //   · 需要画面 → 走 GL 取帧（P3）
    //   · 需要台词 → 从当前播放位置附近的 AI 字幕里裁一段（DanmuSubtitleSnippet）
    // 仅台词模式**完全不碰 GL**（不请求、不轮询），因此更快、也不占用渲染线程。
    LaunchedEffect(danmuConfig.isEnabled, danmuConfig.intervalSec) {
        if (!danmuConfig.isEnabled) return@LaunchedEffect
        if (!danmuConfig.isReadyToRequest()) {
            danmuLastError = context.getString(R.string.danmu_err_not_configured)
            return@LaunchedEffect
        }
        // v2.4.2：URL 拼错（如 `hhttps://`）要**明确指出**，不能混进「意外错误」。
        // 这是最容易犯、也最容易被误判成「网络/密钥问题」的一类配置错误。
        val urlErr = when (danmuConfig.baseUrlProblem()) {
            DanmuConfig.BaseUrlProblem.EMPTY -> context.getString(R.string.danmu_err_url_empty)
            DanmuConfig.BaseUrlProblem.MISSING_SCHEME -> context.getString(R.string.danmu_err_url_no_scheme)
            DanmuConfig.BaseUrlProblem.BAD_SCHEME -> context.getString(R.string.danmu_err_url_bad_scheme)
            DanmuConfig.BaseUrlProblem.MISSING_HOST -> context.getString(R.string.danmu_err_url_no_host)
            null -> ""
        }
        if (urlErr.isNotEmpty()) {
            danmuLastError = urlErr
            return@LaunchedEffect
        }
        val periodMs = danmuConfig.intervalSec.coerceIn(
            DanmuConfig.MIN_INTERVAL_SEC, DanmuConfig.MAX_INTERVAL_SEC
        ) * 1000L
        // 首次稍作等待，避免刚进页面就抓一帧（可能还是黑帧/加载图）
        delay(FIRST_CAPTURE_DELAY_MS)
        while (true) {
            try {
                // ① 取画面（仅当本模式需要画面时）
                var frame: android.graphics.Bitmap? = null
                if (danmuConfig.needsImage) {
                    val renderer = currentGlSurfaceView?.renderer
                    if (renderer == null) {
                        danmuLastError = context.getString(R.string.danmu_err_no_renderer)
                        delay(periodMs)
                        continue
                    }
                    // 请求一帧（GL 线程在下一帧末尾回读；false 表示间隔未到/已禁用）
                    val requested = renderer.requestDanmuFrame(danmuConfig.imageMaxWidth)
                    if (!requested) {
                        // 节流中（距上次太近）—— 等下一周期，不算错误
                        delay(periodMs)
                        continue
                    }
                    // 轮询取出结果（GL 回读发生在下一帧，给它若干次机会）
                    var tries = 0
                    while (tries < FRAME_POLL_MAX_TRIES && frame == null) {
                        delay(FRAME_POLL_INTERVAL_MS)
                        val taken = renderer.takeDanmuFrame()
                        if (taken != null) {
                            val (px, w, h) = taken
                            // 渲染层交给我们的 pix 行序已翻正，可直接构 Bitmap
                            frame = android.graphics.Bitmap.createBitmap(
                                px, w, h, android.graphics.Bitmap.Config.ARGB_8888
                            )
                        }
                        tries++
                    }
                    if (frame == null) {
                        danmuLastError = context.getString(R.string.danmu_err_capture_timeout)
                        delay(periodMs)
                        continue
                    }
                }

                // ② 取台词（仅当本模式需要台词时）
                //    只取**当前播放位置附近**的一段：前 15s / 后 5s（DanmuConfig 常量）。
                //    ⚠️ 字幕可能尚未生成到当前位置（ASR 是 1x 速度），此时 substring 为空串 →
                //       在「仅台词」模式下就变成"没有素材"，用专门的错误文案提示，便于用户理解。
                val subtitleText = if (danmuConfig.needsSubtitle) {
                    val cues = if (isRealtimeSubtitleEnabled) realtimeCues else loadedSubtitleCues
                    DanmuSubtitleSnippet.extract(cues, currentPositionMs)
                } else {
                    ""
                }
                if (danmuConfig.needsSubtitle && subtitleText.isBlank() && !danmuConfig.needsImage) {
                    danmuLastError = context.getString(R.string.danmu_err_no_subtitle)
                    delay(periodMs)
                    continue
                }

                // ③ 交给视觉模型（内部已切 IO；失败返回空列表，不抛）
                val lines = danmuVisionClient.requestDanmu(frame, danmuConfig, subtitleText)
                // ⚠️ 用完立刻回收：1024×576 ARGB ≈ 2.3 MB，long-running 页面不能泄漏
                if (frame != null && !frame.isRecycled) frame.recycle()

                if (lines.isEmpty()) {
                    danmuLastError = context.getString(R.string.danmu_err_empty_response)
                } else {
                    danmuLastError = ""
                    val now = android.os.SystemClock.elapsedRealtime()
                    // 同步屏幕宽度（引擎按它算位置与过期）
                    // ⚠️ 分屏 VR 下引擎坐标系是**单眼宽**，由渲染层接管（DanmuOverlay 内同步），
                    //    这里只在拿到非 0 值时兜底设置，避免覆盖渲染层已设好的单眼宽。
                    val glW = (currentGlSurfaceView?.width ?: 0).toFloat()
                    if (glW > 0f && danmuEngine.screenWidthPx <= 0f) {
                        danmuEngine.screenWidthPx = glW
                    }
                    val added = danmuEngine.enqueue(lines, now)
                    danmuGeneratedCount += added
                    // 顺手回收过期项（正常情况下应由渲染层每帧调；此处兜底）
                    danmuEngine.prune(now)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // 协程取消必须原样抛出，否则 LaunchedEffect 无法正确结束
            } catch (e: Exception) {
                // 任何意外都不能让弹幕拖垮播放页
                android.util.Log.e("VRPlayerScreen", "弹幕编排循环异常: ${e.message}", e)
                danmuLastError = context.getString(R.string.danmu_err_unexpected)
            }
            delay(periodMs)
        }
    }

    // 退出页面时清理（引擎是纯内存对象，reset 即释放）
    DisposableEffect(Unit) {
        onDispose {
            danmuEngine.reset()
        }
    }

    // ===== v2.4.0（P6）：弹幕与播放状态的联动 =====

    /** 进入暂停的时刻（0 = 未暂停）。暂停/恢复与 seek 都要用它，故先声明。 */
    var danmuPausedAtMs by remember { mutableLongStateOf(0L) }

    /** 上一次观察到的播放位置，用于识别 seek 突跳。 */
    var danmuLastPosMs by remember { mutableLongStateOf(0L) }

    // ① 暂停/恢复：暂停时弹幕必须**冻结**，否则视频停了弹幕还在飘。
    //
    // 做法：记录进入暂停的时刻；恢复时把已存活弹幕的出生时间整体后移「暂停时长」，
    // 位置公式 `x = W - (now - born) * speed` 于是自然保持连续（引擎的 shiftBornTime 正是为此设计）。
    // ⚠️ 只在**暂停→播放**这一次跳变时平移；播放中反复重组不能重复平移（会越推越远）。
    LaunchedEffect(isVideoPlaying, danmuConfig.isEnabled) {
        if (!danmuConfig.isEnabled) return@LaunchedEffect
        val now = android.os.SystemClock.elapsedRealtime()
        if (!isVideoPlaying) {
            // 刚进入暂停：记下时刻（若已在暂停则不覆盖，避免跳变基准漂移）
            if (danmuPausedAtMs == 0L) danmuPausedAtMs = now
        } else {
            // 恢复播放：把暂停时长补给所有弹幕
            if (danmuPausedAtMs != 0L) {
                danmuEngine.shiftBornTime(now - danmuPausedAtMs)
                danmuPausedAtMs = 0L
            }
        }
    }

    // ② seek 后清空弹幕。
    //
    // 理由：跳转后旧弹幕的「出生时间」与新画面毫无关系，会以一堆陈旧文本糊在屏幕上；
    // 且它们的 x 多已越界，视觉上表现为「跳转后突然闪一下错位弹幕」。
    // 判据复用实时字幕的「位置突跳 > 2 秒」—— 这样不必逐个改各 seek 调用点，
    // 进度条拖动 / 章节跳转 / 双击重置都能被统一捕获。
    //
    // ⚠️ **不能把 currentPositionMs 当 LaunchedEffect 的 key**：它是高频写入的 state
    //    （上游每 ~100ms 写一次），用作 key 会让协程不断重启 → 每帧新建协程。
    //    改为在长驻协程内轮询比较（与实时字幕同款做法），开销可控且语义清晰。
    LaunchedEffect(danmuConfig.isEnabled) {
        if (!danmuConfig.isEnabled) return@LaunchedEffect
        while (true) {
            val pos = currentPositionMs
            val jumped = kotlin.math.abs(pos - danmuLastPosMs) > 2_000L
            danmuLastPosMs = pos
            if (jumped) {
                // clear 保留去重窗口：跳转后模型很可能给出相似内容，继续压制能避免刷屏
                danmuEngine.clear()
                // 跳转同时重置暂停基准，否则恢复播放时会补一段错误时长
                danmuPausedAtMs = 0L
            }
            kotlinx.coroutines.delay(DANMU_SEEK_POLL_MS)
        }
    }

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
    LaunchedEffect(selectedMediaItem.uri) {
        seekUnsupported = false
        isRemuxing = false
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
    var selectedAudioTrack by remember { mutableIntStateOf(-1) }
    var selectedTextTrack by remember { mutableIntStateOf(-1) }
    // 播放位置恢复：v119 起改由 PlaybackPositions 按媒体 URI 持久化，
    // 不再使用跨媒体共享的 restorePositionMs 变量（详见该 object 注释 #7）

    // Media3 Transformer downscaling function
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun startDownscalingTranscode() {
        val uriStr = selectedMediaItem.uri ?: return
        if (maxResolution == MaxResolution.UNRESTRICTED) {
            Toast.makeText(context, context.getString(R.string.toast_select_resolution_first), Toast.LENGTH_SHORT).show()
            return
        }

        val targetHeight = maxResolution.height
        val targetWidth = maxResolution.width
        
        // Uniquely identify transcoded file by item id and target height to avoid collision
        val cacheFile = File(context.cacheDir, "transcoded_${selectedMediaItem.id}_${targetHeight}.mp4")

        // If cached file already exists, load and play it immediately
        if (cacheFile.exists() && cacheFile.length() > 1024) {
            Toast.makeText(context, context.getString(R.string.toast_cached_downscale, context.getString(maxResolution.labelRes)), Toast.LENGTH_SHORT).show()
            val transcodedMediaItem = selectedMediaItem.copy(
                title = selectedMediaItem.title + context.getString(R.string.media_downscale_suffix, context.getString(maxResolution.labelRes)),
                uri = cacheFile.absolutePath,
                isDemo = false
            )
            selectedMediaItem = transcodedMediaItem
            showResolutionTip = false
            photoReloadTrigger++
            return
        }

        isTranscoding = true
        transcodingProgress = 0
        transcodingStatusText = context.getString(R.string.toast_transcode_init)

        scope.launch(Dispatchers.Main) {
            var tempOutFile: File? = null
            try {
                val inputUri = Uri.parse(uriStr)
                
                // Create Media3 effects list with presentation resizing
                val presentation = Presentation.createForHeight(targetHeight)
                val videoEffects = ImmutableList.of<Effect>(presentation)
                
                val editedMediaItem = EditedMediaItem.Builder(ExoMediaItem.fromUri(inputUri))
                    .setEffects(Effects(ImmutableList.of(), videoEffects))
                    .build()

                tempOutFile = File(context.cacheDir, "transcoding_${System.currentTimeMillis()}.mp4")
                withContext(Dispatchers.IO) {
                    if (tempOutFile.exists()) tempOutFile.delete()
                }

                val transformer = Transformer.Builder(context)
                    .build()

                var completed = false
                var errorException: Exception? = null

                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        completed = true
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exception: ExportException
                    ) {
                        errorException = exception
                    }
                })

                // Start the export process on the main thread
                transformer.start(editedMediaItem, tempOutFile.absolutePath)

                // Poll progress
                val progressHolder = ProgressHolder()
                while (!completed && errorException == null) {
                    val progressState = transformer.getProgress(progressHolder)
                    if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                        transcodingProgress = progressHolder.progress
                        transcodingStatusText = context.getString(R.string.toast_transcoding_progress, context.getString(maxResolution.labelRes), transcodingProgress)
                        keepUiAlight()
                    }
                    delay(500)
                }

                if (errorException != null) {
                    throw errorException!!
                }

                // Copy temp file to cache file
                withContext(Dispatchers.IO) {
                    if (tempOutFile.exists()) {
                        if (cacheFile.exists()) cacheFile.delete()
                        tempOutFile.renameTo(cacheFile)
                    }
                }

                isTranscoding = false
                Toast.makeText(context, context.getString(R.string.toast_transcode_ok, context.getString(maxResolution.labelRes)), Toast.LENGTH_LONG).show()
                val transcodedMediaItem = selectedMediaItem.copy(
                    title = selectedMediaItem.title + context.getString(R.string.media_downscale_suffix, context.getString(maxResolution.labelRes)),
                    uri = cacheFile.absolutePath,
                    isDemo = false
                )
                selectedMediaItem = transcodedMediaItem
                showResolutionTip = false
                photoReloadTrigger++
            } catch (e: Exception) {
                Log.e("VRPlayerScreen", "Transformer transcoding failed", e)
                isTranscoding = false
                withContext(Dispatchers.IO) {
                    tempOutFile?.let { if (it.exists()) it.delete() }
                }
                Toast.makeText(context, context.getString(R.string.toast_transcode_failed, e.localizedMessage), Toast.LENGTH_LONG).show()
            }
        }
    }

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

    fun encodeSmb(url: String): String {
        val m = Regex("""smb://([^@/]+@)?([^/]+)(/.*)?""").find(url) ?: return url
        val creds = m.groupValues[1]
        val host = m.groupValues[2]
        val path = m.groupValues[3] ?: "/"
        val encodedPath = path.split("/").joinToString("/") { seg ->
            if (seg.isEmpty()) "" else java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
        return "smb://$creds$host$encodedPath"
    }

    fun browseSmb(path: String) {
        smbError = ""
        scope.launch(Dispatchers.IO) {
            try {
                val dir = jcifs.smb.SmbFile(path)
                if (!dir.exists() || !dir.isDirectory) {
                    withContext(Dispatchers.Main) { smbError = context.getString(R.string.toast_path_not_exist, path) }
                    return@launch
                }
                val entries = dir.listFiles()?.toList() ?: emptyList()
                withContext(Dispatchers.Main) {
                    smbPath = path
                    smbEntries = entries
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { smbError = context.getString(R.string.toast_connect_failed, (e.message ?: "")) }
            }
        }
    }

    fun connectSmb() {
        val host = smbHost.trim()
        if (host.isEmpty()) {
            smbError = context.getString(R.string.toast_enter_server_addr)
            return
        }
        val creds = if (smbUser.isNotBlank()) "${smbUser}:${smbPass}@" else ""
        browseSmb("smb://$creds$host/")
    }

    fun playSmbFile(entry: jcifs.smb.SmbFile) {
        val smbUri = encodeSmb(entry.path)
        selectedMediaItem = MediaItem(
            id = "smb_" + System.currentTimeMillis(),
            title = entry.name,
            uri = smbUri,
            isVideo = true
        )
        smbDialogOpen = false
        photoReloadTrigger++

        // v2.1.241：SMB 上的 WMV / ASF 是**当前架构下唯一无解的组合**，提前告知而不是让它黑屏。
        //
        // 为什么无解：
        //  · WMV/ASF 必须走 IJK（EXO 不解析 ASF 容器）；
        //  · 但 IJK 的 FFmpeg **没有 smb 协议**（见 IjkPlayerBackend 的类注释第 3 条），
        //    它的数据源也拿不到本项目 jcifs 的 SmbDataSource → 只会回退 EXO；
        //  · 回退到 EXO 后依然不认 ASF → 最终失败。
        // 所以这里直接在**点开时**给提示，用户就不用等它转一圈再报错。
        // 本地文件 / HTTP 上的 WMV 不受此限（走得到 IJK）。
        val ext = MediaFormats.extensionOfName(entry.name)
        if (MediaFormats.requiresIjk(ext)) {
            Toast.makeText(
                context,
                context.getString(R.string.toast_smb_ffmpeg_only, ext.uppercase()),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Auto-fixes a video whose container does not support seeking (position resets
     * to 0 after seekTo) by re-muxing it into a fresh MP4. No re-encoding. (8/1 功能)
     */
    fun startRemuxFix() {
        val uriStr = selectedMediaItem.uri ?: return
        if (isRemuxing) return
        isRemuxing = true
        seekUnsupported = false
        Toast.makeText(
            context,
            context.getString(R.string.toast_fixing_container),
            Toast.LENGTH_LONG
        ).show()

        scope.launch(Dispatchers.IO) {
            val out = File(context.cacheDir, "remuxed_${selectedMediaItem.id}.mp4")
            val result = VideoRemuxer.remux(context, Uri.parse(uriStr), out)
            withContext(Dispatchers.Main) {
                isRemuxing = false
                if (result.success && out.length() > 1024) {
                    Toast.makeText(
                        context,
                        if (result.audioIncluded) context.getString(R.string.toast_container_fixed) else context.getString(R.string.toast_container_fixed_no_audio),
                        Toast.LENGTH_LONG
                    ).show()
                    selectedMediaItem = selectedMediaItem.copy(
                        uri = Uri.fromFile(out).toString(),
                        title = selectedMediaItem.title + context.getString(R.string.suffix_fixed)
                    )
                    photoReloadTrigger++
                } else if (result.videoTrackMissing) {
                    // ⚠️ v2.1.247：源文件有音频但**系统认不出视频轨** ——
                    //    典型就是 AVI 容器里的 AV1（EXO 的 AviExtractor 只认 14 个
                    //    fourcc，不含 `AV01`）。重封装救不了，唯一出路是换 **MPV** 内核
                    //    （它有完整 FFmpeg，认 `V_AV1`）。这里给**针对性**提示，
                    //    而不是笼统的「该文件不支持跳转」。
                    seekUnsupported = true
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_avi_no_video_track),
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    seekUnsupported = true
                    Toast.makeText(context, context.getString(R.string.toast_seek_unsupported), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Helper inside Compose to rebuild/re-bind Android ExoPlayer to GLES
    // D. When downscale output is enabled, cap the surface texture buffer to
    // 1920px wide (aspect preserved) to lower bandwidth/OOM during 8K hard decode. (8/3 功能)
    fun effectiveOutputSize(w: Int, h: Int): Pair<Int, Int> {
        if (!downscaleOutputEnabled || w <= 0 || h <= 0) return w to h
        val maxWidth = 1920
        if (w <= maxWidth) return w to h
        val nh = (h.toLong() * maxWidth / w).toInt().coerceAtLeast(1)
        return maxWidth to nh
    }

    /**
     * Rewrites the SPS (level_idc and/or width/height) so the hardware
     * decoder accepts the stream. No re-encoding. (8/2-8/3 功能)
     */
    fun startLevelPatchFix() {
        val uriStr = selectedMediaItem.uri ?: return
        if (isRemuxing) return
        isRemuxing = true
        Toast.makeText(
            context,
            context.getString(R.string.toast_patching_header),
            Toast.LENGTH_LONG
        ).show()

        scope.launch(Dispatchers.IO) {
            val out = File(context.cacheDir, "levelpatched_${selectedMediaItem.id}.mp4")
            val levelIdc = if (level51Enabled) 0x33 else 0x3D // 5.1 or 6.1
            val result = if (spoofResolutionEnabled) {
                VideoRemuxer.remuxWithSpsSpoof(
                    context,
                    Uri.parse(uriStr),
                    out,
                    targetWidth = 3840,
                    targetHeight = 2160,
                    levelIdc = if (levelPatchEnabled) levelIdc else null
                )
            } else {
                VideoRemuxer.remuxWithLevelPatch(
                    context,
                    Uri.parse(uriStr),
                    out,
                    levelIdc = levelIdc
                )
            }
            withContext(Dispatchers.Main) {
                isRemuxing = false
                if (result.success && out.length() > 1024) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_hw_patch_ok),
                        Toast.LENGTH_LONG
                    ).show()
                    selectedMediaItem = selectedMediaItem.copy(
                        uri = Uri.fromFile(out).toString(),
                        title = selectedMediaItem.title + context.getString(R.string.suffix_hw_patched)
                    )
                    photoReloadTrigger++
                } else {
                    Toast.makeText(
                        context,
                        "修改编码头失败：${result.message ?: "未知错误"}，可尝试手动降级转码",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // 音轨/字幕轨选择（8/2 功能）
    fun selectMediaTrack(groupType: Int, trackIndex: Int) {
        // v2.1.233：轨道切换仅 Exo 支持（ijk 无等效 API）→ 用 exo 逃生舱口
        playerInstance?.exo?.let { p ->
            try {
                val groups = p.currentTracks?.groups ?: return@let
                val group = groups.firstOrNull { it.type == groupType } ?: return@let
                val params = p.trackSelectionParameters.buildUpon()
                if (trackIndex >= 0 && trackIndex < group.mediaTrackGroup.length) {
                    params.setTrackTypeDisabled(groupType, false)
                    params.addOverride(
                        androidx.media3.common.TrackSelectionOverride(
                            group.mediaTrackGroup,
                            com.google.common.collect.ImmutableList.of(trackIndex)
                        )
                    )
                } else {
                    // Disable this track type (e.g. turn embedded subtitles off)
                    params.clearOverridesOfType(groupType)
                    params.setTrackTypeDisabled(groupType, true)
                }
                p.trackSelectionParameters = params.build()
                if (groupType == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
                    selectedAudioTrack = trackIndex
                } else if (groupType == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                    selectedTextTrack = trackIndex
                }
            } catch (e: Exception) {
                Log.e("VRPlayerScreen", "selectMediaTrack failed", e)
            }
        }
    }

    // 视频信息收集（8/2 功能）
    fun showVideoInfo() {
        val uriStr = selectedMediaItem.uri ?: return
        val title = selectedMediaItem.title
        val displayName = getMediaDisplayName()
        val sb = StringBuilder()
        sb.append(context.getString(R.string.info_file, displayName))
        if (title != null && title != displayName) {
            sb.append(context.getString(R.string.info_title, title))
        }
        var gotAny = false
        // v2.1.233：轨道信息仅 Exo 提供（ijk 无 Tracks API）→ 用 exo 逃生舱口
        playerInstance?.exo?.let { p ->
            try {
                val dur = p.duration
                if (dur > 0) {
                    gotAny = true
                    sb.append(context.getString(R.string.info_duration, dur / 1000 / 60, (dur / 1000) % 60))
                }
                val groups = p.currentTracks?.groups
                if (groups != null && groups.isNotEmpty()) {
                    gotAny = true
                    for (g in groups) {
                        val f = g.mediaTrackGroup.getFormat(0)
                        val mime = f.sampleMimeType ?: context.getString(R.string.unknown)
                        if (mime.startsWith("video/")) {
                            if (f.width > 0 && f.height > 0) {
                                sb.append(context.getString(R.string.info_resolution, f.width, f.height))
                            }
                            sb.append(context.getString(R.string.info_video_codec, mime))
                            if (f.frameRate > 0f) sb.append(context.getString(R.string.info_fps, f.frameRate))
                            if (f.bitrate > 0) sb.append(context.getString(R.string.info_bitrate, f.bitrate / 1000))
                        } else if (mime.startsWith("audio/")) {
                            sb.append("音轨: $mime ${f.language ?: ""}\n")
                        } else {
                            sb.append("轨道: $mime ${f.language ?: ""}\n")
                        }
                    }
                }
                sb.append("解码: ${if (isSoftwareDecoding) "软件" else context.getString(R.string.info_hw)}\n")
            } catch (e: Exception) {
                Log.e("VRPlayerScreen", "video info player read failed", e)
            }
        }

        scope.launch(Dispatchers.IO) {
            var retriever: android.media.MediaMetadataRetriever? = null
            try {
                retriever = android.media.MediaMetadataRetriever()
                val configured = try {
                    if (uriStr.startsWith("content://") || uriStr.startsWith("file://")) {
                        retriever.setDataSource(context, Uri.parse(uriStr))
                    } else {
                        retriever.setDataSource(uriStr, java.util.HashMap<String, String>())
                    }
                    true
                } catch (e: Exception) {
                    try {
                        val pfd = context.contentResolver.openFileDescriptor(Uri.parse(uriStr), "r")
                        pfd?.use { retriever.setDataSource(it.fileDescriptor) }
                        true
                    } catch (e2: Exception) {
                        false
                    }
                }
                if (configured) {
                    gotAny = true
                    val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    if (w != null && h != null && !sb.contains(context.getString(R.string.label_resolution))) {
                        sb.append(context.getString(R.string.info_resolution2, w, h))
                    }
                    val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    if (rotation != null && rotation != "0") sb.append(context.getString(R.string.info_rotation, rotation))
                    val fps = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    if (fps != null && fps != "-1" && !sb.contains(context.getString(R.string.label_fps))) sb.append(context.getString(R.string.info_fps2, fps))
                    val bitrate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    if (bitrate != null && !sb.contains(context.getString(R.string.label_bitrate))) sb.append(context.getString(R.string.info_bitrate2, bitrate.toLong() / 1000))
                    val mime = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    if (mime != null && !sb.contains(context.getString(R.string.label_codec))) sb.append(context.getString(R.string.info_codec, mime))
                }
            } catch (e: Exception) {
                Log.e("VRPlayerScreen", "video info retriever failed", e)
            } finally {
                try { retriever?.release() } catch (_: Exception) {}
            }

            val info = if (gotAny) sb.toString() else context.getString(R.string.info_unavailable)
            withContext(Dispatchers.Main) {
                videoInfoDialogText = info
            }
        }
    }

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

    // Helper inside Compose to rebuild/re-bind Android ExoPlayer to GLES
    fun setupVideoPlayer(surfaceTexture: SurfaceTexture, videoUriStr: String) {
        try {
            playerInstance?.release()
            isVideoPlaying = false

            val decodedUri = Uri.parse(videoUriStr)
            // v2.1.234：记录下来供「视频信息」面板显示（见 currentVideoUri 的声明处）
            currentVideoUri = decodedUri

            // v119 修复(#7)：按当前媒体 URI 读取上次播放位置（不再跨媒体共享同一个变量）
            var resumeMs = PlaybackPositions.load(prefs, videoUriStr)
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
            val nativeSurface = Surface(surfaceTexture)
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
                    when (forceVideoType) {
                        1 -> { targetMode = ProjectionMode.STANDARD; targetStereo = StereoMode.MONO }
                        2 -> { targetMode = ProjectionMode.VR_360; targetStereo = StereoMode.MONO }
                        3 -> { targetMode = ProjectionMode.VR_180; targetStereo = StereoMode.MONO }
                        4 -> { targetMode = ProjectionMode.STANDARD; targetStereo = StereoMode.SBS }
                        5 -> { targetMode = ProjectionMode.STANDARD; targetStereo = StereoMode.TAB }
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
                    val aspect = width.toFloat() / height.toFloat()
                    when {
                        aspect in 1.80f..2.20f -> {
                            // Equirectangular panorama (2:1): 切 VR_360 全景 + 单目（平面 2D 立体模式）
                            // + 双中心变形（左右半区各以 25%/75% 为变形中心）
                            if (projectionMode != ProjectionMode.VR_360 || stereoMode != StereoMode.MONO) {
                                projectionMode = ProjectionMode.VR_360
                                stereoMode = StereoMode.MONO
                                currentGlSurfaceView?.renderer?.warpDualCenter = true
                                Toast.makeText(context, context.getString(R.string.toast_detected_360), Toast.LENGTH_SHORT).show()
                            }
                        }
                        else -> {
                            // Ordinary flat video: don't let the 180° dome distort it
                            if (projectionMode != ProjectionMode.STANDARD) {
                                projectionMode = ProjectionMode.STANDARD
                                stereoMode = StereoMode.MONO
                                currentGlSurfaceView?.renderer?.warpDualCenter = false
                                Toast.makeText(context, context.getString(R.string.toast_detected_2d), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                // Independent stereo reset: a leftover SBS/TAB mode on a plain
                // 2D video renders only half the frame stretched full-screen.
                // 3D framing only makes sense on 3D sources, so we always fall
                // back to mono for ordinary aspect videos in planar projection.
                val vidAspect = width.toFloat() / height.toFloat()
                val isPlanar = projectionMode == ProjectionMode.STANDARD ||
                    projectionMode == ProjectionMode.FISHEYE
                if (isSmartProjectionEnabled && isPlanar &&
                    vidAspect !in 1.80f..2.20f && stereoMode != StereoMode.MONO
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
        val containerExt = MediaFormats.extensionOf(decodedUri)
        var effectiveEngine = decoderEngine
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
        // 判据：需 MPV 的容器 → 直接给 GPU 模式（软硬解都能出画）。
        //   代价：硬解路径多一次 GPU 拷贝；但这些容器本来就走软解，没有损失。
        if (MediaFormats.shouldRouteToMpv(containerExt)) {
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
                Log.i("VRPlayerScreen", "容器 .$containerExt 需完整 FFmpeg，本次自动改用 MPV 内核")
                postToast(context.getString(R.string.toast_auto_switch_mpv, containerExt.uppercase()))
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
        if (effectiveEngine == DecoderEngine.IJK) {
            val ijkUri = decodedUri
            val ijkUsable = IjkPlayerFactory.supports(ijkUri) && IjkPlayerFactory.ensureLibraries()
            if (ijkUsable) {
                val backend = IjkPlayerFactory.create(
                    context = context,
                    uri = ijkUri,
                    surface = nativeSurface,
                    options = ijkOptions,
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
                        }

                        override fun onCompletion() {
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
                                Log.e("VRPlayerScreen", "IJK 播放错误 what=$what extra=$extra -> 回退 EXO")
                                decoderEngine = DecoderEngine.EXO
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_ijk_fallback),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                        override fun onFirstFrame() {
                            isVideoPlaying = true
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
                val backend = MpvPlayerFactory.create(
                    context = context,
                    uri = decodedUri,
                    surface = nativeSurface,
                    // v2.1.244：把兜底选定的 voMode 合并进 options（EMBED / GPU）
                    options = mpvOptions.copy(voMode = mpvVoMode),
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
                        }

                        override fun onCompletion() {
                            PlaybackPositions.clear(prefs, videoUriStr)
                            resumeMs = 0L
                        }

                        override fun onError(reason: String) {
                            // v2.1.244：**这里不能重建播放器**（第一版方案，实测直接崩）。
                            //   MPV 是全局单例，重建会再次调 MPVLib.create()+init()，而旧
                            //   context 尚未销毁 → mpv 断言 `!mpctx->initialized` 失败 →
                            //   SIGABRT 崩在 libmpv.so。vo 模式改为**创建前预判**
                            //   （见 setupVideoPlayer 里的「预判式选 vo」），失败即回退。
                            Log.e("VRPlayerScreen", "MPV 播放错误: $reason -> 回退 EXO")
                            // ⚠️ 不能在这里直接调 setupVideoPlayer（会递归）。
                            //    改 decoderEngine 会触发重建 effect；同时写回 prefs，
                            //    下次打开不再踩同一个坑（与 IJK 的回退一致）。
                            decoderEngine = DecoderEngine.EXO
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_mpv_fallback),
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        override fun onFirstFrame() {
                            isVideoPlaying = true
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
                        }

                        override fun onCompletion() {
                            PlaybackPositions.clear(prefs, videoUriStr)
                            resumeMs = 0L
                        }

                        override fun onError(what: Int, extra: Int) {
                            Log.e("VRPlayerScreen", "系统解码播放错误 what=" + what + " extra=" + extra + " -> 回退 EXO")
                            // ⚠️ 不能在这里直接调 setupVideoPlayer（会递归）。
                            //    改 decoderEngine 触发重建；同时写回 prefs，下次不再踩同一个坑。
                            decoderEngine = DecoderEngine.EXO
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_system_fallback),
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        override fun onFirstFrame() {
                            isVideoPlaying = true
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
                        .setAudioProcessors(arrayOf(audioProcessor))
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
            val smbAwareFactory = androidx.media3.datasource.DefaultDataSource.Factory(
                context,
                SchemeRoutingDataSource.Factory(context)
            )
            val exo = ExoPlayer.Builder(context, renderersFactory)
                .setMediaSourceFactory(
                    androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
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
                                PlaybackPositions.clear(prefs, videoUriStr)
                                resumeMs = 0L
                            }
                        }
                    }
                    
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e("VRPlayerScreen", "ExoPlayer playback error", error)
                        // F. Auto fallback to software decoding when the hardware
                        // decoder fails to initialize or decode (opt-in, off by default). (8/3 功能)
                        if (autoFallbackSoftEnabled && !isSoftwareDecoding &&
                            (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
                        ) {
                            Toast.makeText(context, context.getString(R.string.toast_hw_failed_sw), Toast.LENGTH_SHORT).show()
                            isSoftwareDecoding = true
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
            playerInstance = ExoBackend(exo)
        } catch (e: Exception) {
            Log.e("VRPlayerScreen", "Error preparing video content", e)
        }
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
                if (isRealtimeSubtitleEnabled) {
                    val pos = player.currentPosition
                    if (kotlin.math.abs(pos - lastPosForRealtime) > 2_000L) {
                        realtimeSubtitleEngine.onSeek(pos)
                        // v127：跳转后重新预读翻译新位置前方的字幕
                        if (subtitleTranslator.config.isEnabled && realtimeCues.isNotEmpty()) {
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
                    val now = System.currentTimeMillis()
                    if (now - lastSavedAt >= 2_000L) {
                        lastSavedAt = now
                        PlaybackPositions.save(prefs, selectedMediaItem.uri, player.currentPosition)
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
                    setupVideoPlayer(surfaceTexture, uriStr)
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
    LaunchedEffect(isSoftwareDecoding, decoderEngine, ijkOptions, photoReloadTrigger) {
        if (!decoderRebindSeen) {
            // 首次组合时上面的 Effect A 已经完成绑定，这里跳过，避免重复创建播放器
            decoderRebindSeen = true
            return@LaunchedEffect
        }
        keepUiAlight()
        val resumeAt = playerInstance?.currentPosition ?: 0L
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
    LaunchedEffect(isRealtimeSubtitleEnabled, selectedMediaItem.uri, asrEngineType, sherpaLangCode, SherpaAsrManager.modelChoiceVersion) {
        // v127f：切语言/切媒体/开关都会重跑本 effect，必须把**全部**相关状态清干净，
        // 否则屏上会残留上一轮的字幕或进度（会让用户以为"改了语言没反应"）。
        realtimeCues = emptyList()
        realtimeDone = false
        realtimeGeneratedMs = 0L
        realtimeTotalMs = 0L
        realtimeSubtitleStatus = ""
        if (!isRealtimeSubtitleEnabled) {
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
                    scope.launch { realtimeCues = cues }
                    // v127：字幕一有新增就预读翻译游标前方的部分，
                    // 显示时直接命中缓存，译文与原文同时出现（而不是等显示才开始翻）
                    if (subtitleTranslator.config.isEnabled) {
                        subtitleTranslator.pretranslateAhead(cues, currentPositionMs)
                    }
                }
                override fun onStatus(message: String) {
                    scope.launch { realtimeSubtitleStatus = message }
                }
            }
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
                    if (duration > 0) {
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

    LaunchedEffect(gyroOrientationMode, sensorManager) {
        sensorManager?.setOrientationMode(gyroOrientationMode)
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
                            p.play(); isVideoPlaying = true
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
                    p?.let {
                        val dur = if (it.duration > 0) it.duration else Long.MAX_VALUE
                        it.seekTo((it.currentPosition + seekForwardStep * 1000L).coerceIn(0L, dur))
                    }
                    true
                }
                VrGamepadAction.SEEK_BACKWARD -> {
                    p?.let {
                        it.seekTo((it.currentPosition - seekBackwardStep * 1000L).coerceAtLeast(0L))
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
        // v2.1.232：0=纯色(无 backdrop) / 1=Liquid(iOS26) / 2=磨砂 / 3=高斯模糊
        val glassStyle: GlassStyle = when (glassMode) {
            2 -> GlassStyle.Frosted
            3 -> GlassStyle.Gaussian
            else -> GlassStyle.Liquid
        }
        // v2.1.233：背景采样只在「玻璃/模糊」档位开着时才做（纯色档不需要，白白费电）
        LaunchedEffect(isLiquidGlass) {
            VideoBackdrop.enabled = isLiquidGlass
            if (!isLiquidGlass) VideoBackdrop.release()
        }
        DisposableEffect(Unit) {
            onDispose { VideoBackdrop.release() }
        }
        val liquidBackdrop = rememberLayerBackdrop {
            drawRect(ThemeBgColor)
            // v2.1.233：**把视频画面画进 backdrop** ——
            // 视频画在 SurfaceView 上，Compose 采样不到它（独立窗口、打洞），
            // 于是此前 backdrop 里只有上面那层纯色，而「模糊纯色」= 什么都没发生，
            // 这就是磨砂/高斯模糊一直「看不出效果」的根因。
            // 现在由 VRGLRenderer 每 3 帧降采样一帧送过来，糊的就是真画面。
            VideoBackdrop.current()?.let { bmp ->
                runCatching {
                    drawImage(
                        image = bmp.asImageBitmap(),
                        srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                        srcSize = androidx.compose.ui.unit.IntSize(bmp.width, bmp.height),
                        dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
                        dstSize = androidx.compose.ui.unit.IntSize(
                            size.width.roundToInt(),
                            size.height.roundToInt()
                        )
                    )
                }
            }
            drawContent()
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
                view.renderer.beautyGpSmooth = beautyGpSmooth
                view.renderer.beautyGpWhite = beautyGpWhite
                view.renderer.beautyGpSharpen = beautyGpSharpen
                view.renderer.beautyGpSlim = beautyGpSlim
                view.renderer.beautyGpEyeZoom = beautyGpEyeZoom
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
                .layerBackdrop(liquidBackdrop)
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
            // Left & Right screen visual icons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = 90.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Text("L", color = Color(0x40FFFFFF), fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text("R", color = Color(0x40FFFFFF), fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }

        // 2.4 弹幕层（v2.4.0 / P5）：位于字幕层**之下**（弹幕在顶部区域，字幕通常在下半部）
        //     ⚠️ 必须放在 SubtitleOverlay 之前，让字幕能盖在弹幕上（万一位置撞上，字幕可读性优先）
        DanmuOverlay(
            engine = danmuEngine,
            config = danmuConfig,
            isSplitScreenVR = isSplitScreenVR,
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

        // 3. Floating Lock / Unlock Icon Button Layer
        // Shown when UI controls are visible, allowing user to toggle screen lock securely
        AnimatedVisibility(
            visible = isUiVisible,
            enter = slideInHorizontally(animationSpec = tween(500, easing = CustomEaseOutBack)) { -it } + 
                    fadeIn(animationSpec = tween(350, easing = EaseInOutCubic)),
            exit = slideOutHorizontally(animationSpec = tween(350, easing = EaseInOutCubic)) { -it } + 
                   fadeOut(animationSpec = tween(250, easing = EaseInOutCubic)),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 24.dp)
        ) {
            FilledIconButton(
                onClick = {
                    isUiLocked = !isUiLocked
                    keepUiAlight()
                },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = ThemePanelBgColor,
                    contentColor = if (isUiLocked) Color(0xFFFF5252) else AccentColor
                ),
                modifier = Modifier
                    .size(54.dp)
                    .testTag("ui_lock_button")
            ) {
                Icon(
                    imageVector = if (isUiLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = if (isUiLocked) stringResource(R.string.unlock_ui) else stringResource(R.string.lock_ui),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // 3.5 Top Header Panel (Modern slide/fade non-linear easing animation)
        AnimatedVisibility(
            visible = isUiVisible && !isSettingsDialogOpen && !isUiLocked,
            enter = slideInVertically(animationSpec = tween(550, easing = EaseOutQuart)) { -it } + 
                    fadeIn(animationSpec = tween(400, easing = EaseInOutCubic)),
            exit = slideOutVertically(animationSpec = tween(400, easing = EaseInOutCubic)) { -it } + 
                   fadeOut(animationSpec = tween(300, easing = EaseInOutCubic)),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xE01C1B1F), Color(0xA01C1B1F), Color.Transparent)
                        )
                    )
                    .clickable(enabled = true, onClick = { keepUiAlight() })
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Face, // Face / Beauty Icon
                                contentDescription = stringResource(R.string.cd_beauty_icon),
                                tint = AccentColor,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.app_desc),
                                color = TextLightColor,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("app_title_text")
                            )
                        }
                        Text(
                            text = stringResource(R.string.current_media, getMediaDisplayName()),
                            color = AccentColor.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 240.dp)
                        )
                    }

                    // Direct toggle controls at Top Right: Exactly 3 buttons: (1) local picker, (2) VR mode, (3) settings
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        IconButton(
                            onClick = {
                                keepUiAlight()
                                // v2.1.241：原先是直接拉相册选择器，导致 .wmv / .iso
                                // **永远列不出来**（相册只按 video/* 过滤）。
                                // 现在改为先弹一个「从哪选」的选择框，两条入口并存。
                                pickerSourceDialogOpen = true
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = Color(0x2BD0BCFF),
                                contentColor = AccentColor
                            ),
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("local_file_picker_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.import_local_media)
                            )
                        }

                        IconButton(
                            onClick = {
                                isSplitScreenVR = !isSplitScreenVR
                                keepUiAlight()
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isSplitScreenVR) Color(0x33D0BCFF) else TranslucentWhite10,
                                contentColor = if (isSplitScreenVR) AccentColor else Color.White
                            ),
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("vr_splitscreen_toggle_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ViewInAr,
                                contentDescription = stringResource(R.string.cd_vr_split_mode),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // v2.0.174：华为 VR Glass 显式入口。
                        // 仅在「华为 VR 开关已开」或「本机检测到华为 VR 运行时」时出现，
                        // 避免在普通机型上多出一个点不动的按钮。
                        // v2.0.205：机型非华为/荣耀 → 整个 VR Glass 入口不显示（用户要求）
                        if ((huaweiVrEnabled || huaweiVrRuntimeAvailable) && isHuaweiOrHonorDevice) {
                            IconButton(
                                onClick = {
                                    keepUiAlight()
                                    if (huaweiVrRuntimeAvailable) {
                                        val ok = try {
                                            context.startActivity(
                                                buildHuaweiVrPromptIntent(
                                                    context,
                                                    huaweiVrRenderScale,
                                                    // ⚠️ P2 退出落盘链路：
                                                    // HuaweiVrActivity 退出会 killProcess，
                                                    // 必须在此之前把设置写进 prefs，否则全丢。
                                                    onBeforeKill = {
                                                        runCatching {
                                                            prefs.edit()
                                                                .putBoolean("huawei_vr_enabled", huaweiVrEnabled)
                                                                .putFloat("huawei_vr_render_scale", huaweiVrRenderScale)
                                                                .putBoolean("huawei_vr_prefer_6dof", huaweiVrPrefer6dof)
                                                                .putBoolean("is_split_screen_vr", isSplitScreenVR)
                                                                .apply()
                                                        }
                                                    },
                                                    // ⚠️ P1 视频源接线（关键）：
                                                    // 华为侧 Activity 起来后会创建自己的 SurfaceTexture，
                                                    // 这里把**当前正在播放的 ExoPlayer 输出切过去**，
                                                    // 让它画的每一帧都来自真实视频流，而不是空画面。
                                                    onVideoSurfaceNeeded = { st ->
                                                        runCatching {
                                                            val uriStr = selectedMediaItem.uri
                                                            if (uriStr != null && selectedMediaItem.isVideo) {
                                                                // 尺寸沿用当前渲染器已知的视频宽高（VRGLRenderer.videoWidth/Height，
                                                                // 由 onVideoSizeChanged 持续更新），避免切换瞬间被当成 0。
                                                                val r = currentGlSurfaceView?.renderer
                                                                val w = r?.videoWidth?.takeIf { it > 0 } ?: 1920
                                                                val h = r?.videoHeight?.takeIf { it > 0 } ?: 1080
                                                                st.setDefaultBufferSize(w, h)
                                                                val newSurface = Surface(st)
                                                                // ⚠️ setVideoSurface 内部会做一次 flush + 重配解码器输出，
                                                                //    不会丢播放位置，也不会重启解码器。
                                                                playerInstance?.setSurface(newSurface)
                                                                Log.i(
                                                                    "HuaweiVR",
                                                                    "视频源已切到华为 VR（${w}x$h, uri=$uriStr）"
                                                                )
                                                            } else {
                                                                Log.i("HuaweiVR", "当前非视频媒体，华为侧保持空画面")
                                                            }
                                                        }.onFailure {
                                                            Log.e("HuaweiVR", "视频源接线失败", it)
                                                        }
                                                    }
                                                )
                                            )
                                            true
                                        } catch (e: Exception) {
                                            // 未集成 hvrprompt.aar 或 Activity 未注册时走到这里：
                                            // 记录原因并降级到内置分屏 VR（用户要求：绝不黑屏）
                                            Log.w("HuaweiVR", "启动华为 VR 失败，回退内置分屏 VR", e)
                                            false
                                        }
                                        if (!ok) {
                                            isSplitScreenVR = true
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.huawei_vr_enter_failed),
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    } else {
                                        // 无运行时：直接提示并回退
                                        isSplitScreenVR = true
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.huawei_vr_runtime_missing),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = if (huaweiVrEnabled) Color(0x33D0BCFF) else TranslucentWhite10,
                                    contentColor = if (huaweiVrEnabled) AccentColor else Color.White
                                ),
                                modifier = Modifier
                                    .size(44.dp)
                                    .testTag("huawei_vr_enter_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Vrpano,
                                    contentDescription = stringResource(R.string.huawei_vr_enter),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                isSettingsDialogOpen = !isSettingsDialogOpen
                                keepUiAlight()
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isSettingsDialogOpen) Color(0x33D0BCFF) else TranslucentWhite10,
                                contentColor = if (isSettingsDialogOpen) AccentColor else Color.White
                            ),
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("top_settings_orchestra_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.beauty_projection_menu),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

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
                                    onDrawSurface = { drawRect(ThemePanelBgColor.copy(alpha = 0.55f)) }
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
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Text(
                                            text = if (videoDurationText.contains("/")) videoDurationText.split("/")[0].trim() else "00:00",
                                            color = Color.White.copy(alpha = 0.6f),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )

                                        Slider(
                                            value = videoPlaybackProgress,
                                            onValueChange = { seeker ->
                                                val adjustedProgress = if (isSliderDragged) {
                                                    if (!isSeekingActive) {
                                                        isSeekingActive = true
                                                        seekStartProgress = videoPlaybackProgress
                                                        seekStartValue = seeker
                                                    }
                                                    val delta = seeker - seekStartValue
                                                    (seekStartProgress + delta * 0.25f).coerceIn(0f, 1f)
                                                } else {
                                                    isSeekingActive = false
                                                    seeker
                                                }
                                                videoPlaybackProgress = adjustedProgress
                                                isHoverActive = true
                                                keepUiAlight()
                                                // ⚠️⚠️ v2.1.247：**拖动过程中绝不逐帧 seek**。
                                                //
                                                // 旧实现在这里每帧调 `mp.seekTo(targetTime)`，对 mp4
                                                // 尚可，但对 **AVI 是灾难**：EXO 的 `AviExtractor` 若没有
                                                // `idx1` 索引，每次 seek 都要**从头线性扫描 chunk**；逐帧 seek
                                                // 会在几十毫秒内堆几十个未完成的 seek 请求 → 解码器被反复
                                                // flush → 画面冻结、进度条回弹、最终卡死（用户报的「AVI 拖不动」）。
                                                //
                                                // 正确做法（所有成熟播放器的标准姿势）：
                                                //   · 拖动中：只更新 UI 进度 + `hoverTimeMs`（驱动缩略图），**不 seek**；
                                                //   · 松手时（onValueChangeFinished）：**只 seek 一次**到最终位置。
                                                // 这样对 AVI 只有一次线性扫描，拖动全程跟手。
                                                playerInstance?.let { mp ->
                                                    try {
                                                        val duration = mp.duration
                                                        // ⚠️ v2.1.247：duration 在 buffering 阶段可能是
                                                        //    `C.TIME_UNSET`（负数）或 0（尤其 AVI 刚开始解析时）
                                                        //    → 若直接相乘会得到**负数**的 hoverTimeMs，
                                                        //    松手 seek 到负数 = 位置不动（用户看到的「拖了没反应」）。
                                                        //    这里只在 duration 有效时才更新，否则保留上一次有效值。
                                                        if (duration > 0) {
                                                            hoverTimeMs = (adjustedProgress * duration).toLong()
                                                        }
                                                    } catch (e: Exception) {}
                                                }
                                            },
                                            onValueChangeFinished = {
                                                isSeekingActive = false
                                                // ⚠️ v2.1.247：拖动结束才真正 seek（只此一次）。
                                                //
                                                // 这里**重新用当下的 duration × 进度**算目标位置，
                                                // 而不是复用 `hoverTimeMs` —— 后者可能是拖动早期
                                                // 用陈旧 duration 算出的值（见上），直接用会 seek 到错位置。
                                                val finalProgress = videoPlaybackProgress
                                                var seekTarget = hoverTimeMs
                                                var seekIssued = false
                                                playerInstance?.let { mp ->
                                                    try {
                                                        val duration = mp.duration
                                                        if (duration > 0) {
                                                            seekTarget = (finalProgress * duration).toLong()
                                                                .coerceIn(0L, duration)
                                                            hoverTimeMs = seekTarget
                                                            mp.seekTo(seekTarget)
                                                            seekIssued = true
                                                        } else {
                                                            // duration 不可用 → 退而求其次，用 hoverTimeMs
                                                            if (seekTarget > 0) {
                                                                mp.seekTo(seekTarget)
                                                                seekIssued = true
                                                            }
                                                        }
                                                    } catch (e: Exception) {}
                                                }
                                                // 拖动结束立即把 UI 进度对齐到目标（不等 1 秒轮询），
                                                // 否则会出现「松手后进度条先弹回旧位置、1 秒后才跳过去」的错觉。
                                                if (seekIssued) {
                                                    videoPlaybackProgress = finalProgress
                                                }
                                                // Verify the seek took effect: some containers
                                                // (moov-at-end / fragmented / AVI without idx1) reset the
                                                // position to 0, in which case we offer an auto container fix.
                                                //
                                                // ⚠️ v2.1.247 收紧判据（AVI 相关）：
                                                //   ① 只有在**确实发出过 seek** 时才校验（否则误判）；
                                                //   ② 判定用「位置几乎没动」而不是「< 2s」—— 后者在
                                                //      短片（< 10s）上会把正常位置也判成失败；
                                                //   ③ 加长等待到 1.5s：AVI 无索引时线性扫描 1s 内可能还没到位，
                                                //      过早判定会触发不必要的重封装。
                                                val seekIssuedFlag = seekIssued
                                                scope.launch {
                                                    delay(1500L)
                                                    if (seekIssuedFlag && seekTarget > 3000L) {
                                                        val pos = playerInstance?.currentPosition ?: -1L
                                                        val moved = kotlin.math.abs(pos - seekTarget)
                                                        val stuckAtStart = pos < 2000L && seekTarget > 5000L
                                                        if ((stuckAtStart || moved > 5000L) &&
                                                            !seekUnsupported && !isRemuxing &&
                                                            selectedMediaItem.isVideo
                                                        ) {
                                                            startRemuxFix()
                                                        }
                                                    }
                                                }
                                                scope.launch {
                                                    delay(2500L)
                                                    isHoverActive = false
                                                }
                                            },
                                            interactionSource = sliderInteractionSource,
                                            colors = SliderDefaults.colors(
                                                thumbColor = AccentColor,
                                                activeTrackColor = AccentColor,
                                                inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(16.dp)
                                        )

                                        Text(
                                            text = if (videoDurationText.contains("/")) videoDurationText.split("/")[1].trim() else "00:00",
                                            color = Color.White.copy(alpha = 0.6f),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
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
                                onToggleSplitScreen = { isSplitScreenVR = !isSplitScreenVR },
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

        // 3.5 字幕快捷面板（v91：字幕模块主界面提级入口）
        // v94：点击面板外部区域自动关闭
        if (isSubtitleQuickPanelOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { isSubtitleQuickPanelOpen = false; keepUiAlight() },
                contentAlignment = Alignment.TopEnd
            ) {
                Surface(
                    color = ThemePanelBgColor,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .padding(top = 24.dp, end = 16.dp)
                        .width(320.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { /* 消费点击，不穿透到背景层 */ }
                        .testTag("subtitle_quick_panel")
                ) {
                    // v2.0.128：面板内容较长（字幕开关 / 翻译 / ASR 引擎 / 推理线程 /
                    // 实时字幕 / 生成进度与操作 / 完整设置入口…），在竖屏或低分辨率下
                    // 会超出屏幕且无法滚动。改为「限高 + 竖向滚动」，
                    // 并在右侧显示滚动条（内容未超出时不显示）。
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val quickPanelScroll = rememberScrollState()
                        val density = LocalDensity.current
                        Column(
                            modifier = Modifier
                                .heightIn(max = 480.dp)
                                .verticalScroll(quickPanelScroll)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                        // 字幕开关
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.subtitle_quick_title),
                                color = AccentColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    if (isSubtitleEnabled) stringResource(R.string.subtitle_state_on)
                                    else stringResource(R.string.subtitle_state_off),
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 10.sp
                                )
                                Switch(
                                    checked = isSubtitleEnabled,
                                    onCheckedChange = {
                                        isSubtitleEnabled = it
                                        // v2.0.128：与完整字幕设置面板保持一致——记下用户的
                                        // 显式选择，否则切视频时自动加载的字幕会把它重新打开
                                        if (isMemoryModeEnabled) {
                                            prefs.edit().putBoolean("subtitle_user_disabled", !it).apply()
                                        }
                                        keepUiAlight()
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = AccentOnColor,
                                        checkedTrackColor = AccentColor,
                                        uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                                        uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                                    ),
                                    modifier = Modifier.height(24.dp)
                                )
                            }
                        }

                        // v94：翻译开关（字幕翻译 / 双语对照）
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    stringResource(R.string.subtitle_translate_title),
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (subtitleTranslator.config.isEnabled)
                                        "${stringResource(subtitleTranslator.config.engine.displayNameResId)} → ${stringResource(subtitleTranslator.config.targetLanguage.nameResId)}"
                                    else stringResource(R.string.subtitle_translate_off),
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 9.sp
                                )
                            }
                            Switch(
                                checked = subtitleTranslator.config.isEnabled,
                                onCheckedChange = {
                                    subtitleTranslator.config = subtitleTranslator.config.copy(isEnabled = it)
                                    if (isMemoryModeEnabled) prefs.edit().putBoolean("translation_enabled", it).apply()
                                    keepUiAlight()
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = AccentOnColor,
                                    checkedTrackColor = AccentColor,
                                    uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                                    uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                                ),
                                modifier = Modifier.height(24.dp)
                            )
                        }

                        // v127：实时字幕的引擎与模型（引擎已固定为 SenseVoice）
                        BatchTranscribeSection(
                            accentColor = AccentColor,
                            accentOnColor = AccentOnColor,
                            sherpaLangCode = sherpaLangCode,
                            onSherpaLangCodeChange = { changeAsrLanguage(it) },
                            onUserInteraction = { keepUiAlight() }
                        )

                        // v127e：推理线程数（1~10，推荐 4~6）
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(R.string.asr_threads),
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 10.sp
                                )
                                Text(
                                    stringResource(R.string.asr_threads_value, asrThreads) +
                                        if (asrThreads in SherpaAsrManager.RECOMMENDED_THREADS)
                                            stringResource(R.string.asr_threads_recommended) else "",
                                    color = if (asrThreads in SherpaAsrManager.RECOMMENDED_THREADS) AccentColor
                                    else Color(0xFFFFB74D),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Slider(
                                value = asrThreads.toFloat(),
                                onValueChange = { v ->
                                    asrThreads = v.toInt().coerceIn(SherpaAsrManager.MIN_THREADS, SherpaAsrManager.MAX_THREADS)
                                },
                                onValueChangeFinished = {
                                    if (isMemoryModeEnabled) {
                                        prefs.edit().putInt("asr_threads", asrThreads).apply()
                                    }
                                    // 识别器已按旧线程数创建，改动需重建引擎才生效
                                    if (isRealtimeSubtitleEnabled) realtimeSubtitleEngine.restart()
                                    keepUiAlight()
                                },
                                valueRange = SherpaAsrManager.MIN_THREADS.toFloat()..SherpaAsrManager.MAX_THREADS.toFloat(),
                                steps = SherpaAsrManager.MAX_THREADS - SherpaAsrManager.MIN_THREADS - 1,
                                colors = SliderDefaults.colors(
                                    thumbColor = AccentColor,
                                    activeTrackColor = AccentColor,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                                ),
                                modifier = Modifier.fillMaxWidth().height(24.dp)
                            )
                            Text(
                                stringResource(R.string.asr_threads_hint),
                                color = Color.White.copy(alpha = 0.4f),
                                fontSize = 8.sp
                            )
                        }

                        // v126：实时 AI 字幕开关（边播边生成，不写 SRT、不改动原视频）
                        ExperimentalSwitchRow(
                            title = stringResource(R.string.realtime_subtitle_title),
                            desc = stringResource(R.string.realtime_subtitle_desc),
                            checked = isRealtimeSubtitleEnabled,
                            onChanged = {
                                isRealtimeSubtitleEnabled = it
                                if (isMemoryModeEnabled) {
                                    prefs.edit().putBoolean("realtime_subtitle_enabled", it).apply()
                                }
                                keepUiAlight()
                            },
                            accentColor = AccentColor,
                            accentOnColor = AccentOnColor
                        )
                        if (isRealtimeSubtitleEnabled && realtimeSubtitleStatus.isNotBlank()) {
                            Text(
                                text = realtimeSubtitleStatus,
                                color = if (realtimeDone) AccentColor else Color.White.copy(alpha = 0.6f),
                                fontSize = 9.sp,
                                lineHeight = 12.sp,
                                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                            )
                        }
                        // v127b：实时字幕生成进度条（全片生成完则不再显示）
                        if (isRealtimeSubtitleEnabled && !realtimeDone && realtimeTotalMs > 0L) {
                            LinearProgressIndicator(
                                // v127f 修复闪退：progress 的 lambda 是**延迟求值**的，
                                // 外层 if 判断通过后，total 仍可能在求值前被引擎 restart()
                                // 重置为 0（切语言/切媒体/重新生成都会），此时 x/0 得到 NaN，
                                // 而 coerceIn 对 NaN 无效 → Compose 抛
                                // "IllegalArgumentException: current must not be NaN" 崩溃。
                                // 因此必须在 lambda 内部再判一次分母。
                                progress = {
                                    val total = realtimeTotalMs
                                    if (total <= 0L) 0f
                                    else (realtimeGeneratedMs.toFloat() / total).coerceIn(0f, 1f)
                                },
                                color = AccentColor,
                                trackColor = Color.White.copy(alpha = 0.12f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .padding(start = 4.dp, end = 4.dp)
                            )
                        }

                        // v127e：重新生成 / 导出（字幕悬浮窗操作）
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AccentColor.copy(alpha = 0.85f))
                                    .clickable {
                                        keepUiAlight()
                                        regenerateRealtimeSubtitle()
                                    }
                                    .padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stringResource(R.string.subtitle_regenerate),
                                    color = AccentOnColor,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White.copy(alpha = 0.12f))
                                    .clickable {
                                        keepUiAlight()
                                        exportSubtitleSrt()
                                    }
                                    .padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stringResource(R.string.subtitle_export_srt),
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        // v2.0.136：字幕源选择器 —— 实时 AI 生成 / 本地已保存的历史字幕
                        var savedSubtitleFiles by remember { mutableStateOf<List<File>>(emptyList()) }
                        LaunchedEffect(isSubtitleQuickPanelOpen, selectedMediaItem.uri) {
                            if (!isSubtitleQuickPanelOpen) return@LaunchedEffect
                            val base = SubtitleExporter.safeBaseName(selectedMediaItem.title)
                            savedSubtitleFiles = withContext(Dispatchers.IO) {
                                // 只展示最新 5 份，避免面板过长
                                SubtitleExporter.listSavedSubtitles(context, base).take(5)
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                stringResource(R.string.subtitle_source_title),
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            // 字幕源 1：实时 AI 生成
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isRealtimeSubtitleEnabled) AccentColor.copy(alpha = 0.30f)
                                        else Color.White.copy(alpha = 0.08f)
                                    )
                                    .clickable {
                                        keepUiAlight()
                                        isRealtimeSubtitleEnabled = true
                                        if (isMemoryModeEnabled) {
                                            prefs.edit().putBoolean("realtime_subtitle_enabled", true).apply()
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    stringResource(R.string.subtitle_source_realtime),
                                    color = if (isRealtimeSubtitleEnabled) AccentColor else Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = if (isRealtimeSubtitleEnabled) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            // 字幕源 2..n：data 目录 subtitles/ 下的历史字幕（最新在前）
                            savedSubtitleFiles.forEach { f ->
                                val selected = !isRealtimeSubtitleEnabled && loadedSubtitleFileName == f.name
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (selected) AccentColor.copy(alpha = 0.30f)
                                            else Color.White.copy(alpha = 0.08f)
                                        )
                                        .clickable {
                                            keepUiAlight()
                                            loadSavedSubtitleFile(f)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        f.name,
                                        color = if (selected) AccentColor else Color.White.copy(alpha = 0.85f),
                                        fontSize = 10.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        // v2.1.232：字幕外观 / 布局 / 时间 —— 与完整设置面板**同一份实现**
                        //（此前悬浮窗完全缺这块，想调字号颜色必须先进完整设置）
                        var styleSecExpanded by remember { mutableStateOf(false) }
                        var layoutSecExpanded by remember { mutableStateOf(false) }
                        SubtitleStyleSettings(
                            subtitleFont = subtitleFont,
                            onFontChange = { subtitleFont = it },
                            fontSizeSp = subtitleFontSizeSp,
                            onFontSizeChange = { subtitleFontSizeSp = it },
                            fontWeightVal = subtitleFontWeightVal,
                            onFontWeightChange = { subtitleFontWeightVal = it },
                            isItalic = isSubtitleItalic,
                            onItalicChange = { isSubtitleItalic = it },
                            selectedColorOption = subtitleColorOpt,
                            onColorOptionChange = { subtitleColorOpt = it },
                            textAlpha = subtitleTextAlpha,
                            onTextAlphaChange = { subtitleTextAlpha = it },
                            selectedStrokeOption = subtitleStrokeOpt,
                            onStrokeOptionChange = { subtitleStrokeOpt = it },
                            selectedBgOption = subtitleBgOpt,
                            onBgOptionChange = { subtitleBgOpt = it },
                            offsetYRatio = subtitleOffsetYRatio,
                            onOffsetYRatioChange = { subtitleOffsetYRatio = it },
                            offsetXRatio = subtitleOffsetXRatio,
                            onOffsetXRatioChange = { subtitleOffsetXRatio = it },
                            delayMs = subtitleDelayMs,
                            onDelayMsChange = { subtitleDelayMs = it },
                            textAlign = subtitleTextAlignOpt,
                            onTextAlignChange = { subtitleTextAlignOpt = it },
                            maxLines = subtitleMaxLines,
                            onMaxLinesChange = { subtitleMaxLines = it },
                            vrIpdOffsetRatio = vrIpdOffsetRatio,
                            onVrIpdOffsetRatioChange = { vrIpdOffsetRatio = it },
                            accentColor = AccentColor,
                            accentOnColor = AccentOnColor,
                            onUserActivity = { keepUiAlight() },
                            styleExpanded = styleSecExpanded,
                            onStyleExpandedChange = { styleSecExpanded = it },
                            layoutExpanded = layoutSecExpanded,
                            onLayoutExpandedChange = { layoutSecExpanded = it }
                        )

                        // 打开完整字幕设置（设置面板并展开字幕分组）
                        TextButton(
                            onClick = {
                                isSubtitleQuickPanelOpen = false
                                isSettingsDialogOpen = true
                                expandedSettings = expandedSettings + "sub"
                                keepUiAlight()
                            },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                              Text(
                                  stringResource(R.string.subtitle_open_full_settings),
                                  color = AccentColor,
                                  fontSize = 10.sp
                              )
                          }
                      }

                        // 右侧滚动条：仅当内容超出限高时才出现
                        if (quickPanelScroll.maxValue > 0) {
                            // 可视高度 = 上面的限高（480.dp），内容超过它才会走到这里
                            val viewH = with(density) { 480.dp.toPx() }
                            val totalH = quickPanelScroll.maxValue + viewH
                            val thumbRatio = (viewH / totalH).coerceIn(0.15f, 1f)
                            val progress = quickPanelScroll.value.toFloat() /
                                quickPanelScroll.maxValue.toFloat().coerceAtLeast(1f)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    // v2.0.137 修"面板底部一大块空白"：原先 fillMaxHeight()
                                    // 在 Box 里会填满**父级传入的最大约束**（整屏高），
                                    // 把 BoxWithConstraints/面板从内容高度撑到屏幕高——
                                    // 实测面板 593dp 而内容视口仅 480dp，底部多出 ~113dp 空白。
                                    // 改为与内容视口同高的固定值（matchParentSize 在本版本
                                    // Compose 无法 import，见 v2.0.128 记录）。
                                    .height(480.dp)
                                    .padding(vertical = 6.dp, horizontal = 2.dp)
                                    .width(7.dp),
                                contentAlignment = Alignment.TopEnd
                            ) {
                                // 轨道
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Color.White.copy(alpha = 0.10f))
                                )
                                // 滑块
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .fillMaxHeight(thumbRatio)
                                        .offset {
                                            IntOffset(
                                                0,
                                                ((1f - thumbRatio) * progress * viewH).roundToInt()
                                            )
                                        }
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(AccentColor.copy(alpha = 0.75f))
                                )
                            }
                        }
                    }
                  }
              }
          }

        // 4. Secondary Settings Dialog Panel (Hides other UI, so shown independently at root level when open!)
        AnimatedVisibility(
            visible = isSettingsDialogOpen,
            enter = fadeIn(animationSpec = tween(300, easing = EaseInOutCubic)) + 
                    scaleIn(initialScale = 0.90f, animationSpec = tween(400, easing = CustomEaseOutBack)),
            exit = fadeOut(animationSpec = tween(250, easing = EaseInOutCubic)) + 
                   scaleOut(targetScale = 0.95f, animationSpec = tween(250, easing = EaseInOutCubic)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable { isSettingsDialogOpen = false }
                    .padding(horizontal = 40.dp, vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFC18171C)
                    ),
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .clickable(enabled = false) {} // Prevent click-through closing
                        .testTag("secondary_settings_dialog_card")
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {

                                // ============================================================
                                // 设置面板拆分（v79 JIT 超限修复）
                                // 原因：整个设置面板 Column 编译为单个方法达 36264 指令，
                                //   超过 ART JIT 编译上限（~28000）后被降级为解释执行，
                                //   导致设置面板打开/更新时极卡（字幕 5 句后停更、翻译不刷新）。
                                // 方案：按功能块拆成 9 个局部 @Composable 函数（各自独立编译），
                                //   每个函数指令数远低于 JIT 上限。注意：局部函数必须标注
                                //   @Composable（否则不能调用 Compose API）；如需强制不内联
                                //   可加 @NonInline（androidx.compose.runtime.NonInline）。
                                // ============================================================

                                /** 设置面板头部：标题 + 工具按钮 + 关闭按钮 */
                                @Composable
                                fun SettingsHeader() {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = AccentColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.settings_dialog_title),
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                // v92: 从控制栏移入的工具按钮（局域网/视频信息/音轨选择）
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(
                                    onClick = { smbDialogOpen = true; keepUiAlight() },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.FolderOpen,
                                        contentDescription = stringResource(R.string.action_lan_play),
                                        tint = Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { showVideoInfo(); keepUiAlight() },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = stringResource(R.string.action_media_info),
                                        tint = Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (selectedAudioTrack == -1) selectedAudioTrack = 0
                                        if (selectedTextTrack == -1) selectedTextTrack = 0
                                        trackDialogOpen = true; keepUiAlight()
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.QueueMusic,
                                        contentDescription = stringResource(R.string.action_track_select),
                                        tint = Color.White.copy(alpha = 0.7f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            IconButton(
                                onClick = { isSettingsDialogOpen = false },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.action_close),
                                    tint = Color.White.copy(alpha = 0.6f)
                                )
                            }
                        }
                                }
                                /** 区块 0：UI 主题与玻璃效果（8/2 功能） */
                                @Composable
                                fun SettingsSection0() {
                                Text(
                                    text = stringResource(R.string.settings_group_ui_theme),
                                    color = AccentColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    UiThemes.list.forEach { t ->
                                        val selected = uiThemeId == t.id
                                        Box(
                                            modifier = Modifier
                                                .size(if (selected) 30.dp else 24.dp)
                                                .clip(CircleShape)
                                                .background(t.accent)
                                                .border(if (selected) 2.dp else 1.dp, if (selected) Color.White else Color.White.copy(alpha = 0.3f), CircleShape)
                                                .clickable {
                                                    uiThemeId = t.id
                                                    prefs.edit().putInt("ui_theme_id", t.id).apply()
                                                    keepUiAlight()
                                                }
                                                .testTag("theme_dot_${t.id}")
                                        )
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(UiThemes.byId(uiThemeId).nameRes),
                                        color = AccentColor,
                                        fontSize = 12.sp
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf(
                                            0 to stringResource(R.string.theme_solid),
                                            1 to stringResource(R.string.theme_liquid_glass),
                                            // v2.1.218：v2.1.217 的 4 档并回 3 档 ——
                                            // Liquid 与 Liquid26 观感接近、选择意义不大，
                                            // 已合并为一档并采用 iOS 26 的增强参数。
                                            // v2.1.232：再加一档「高斯模糊」—— 与「磨砂」的区别是
                                            // 只有纯模糊 + 轻微提亮（无噪点颗粒），且半径更大。
                                            2 to stringResource(R.string.theme_frosted_glass),
                                            3 to stringResource(R.string.theme_gaussian_blur),
                                        ).forEach { (m, label) ->
                                            val sel = glassMode == m
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(50))
                                                    .background(if (sel) AccentColor else TranslucentWhite10)
                                                    .clickable {
                                                        glassMode = m
                                                        prefs.edit().putInt("ui_glass_mode", m).apply()
                                                        keepUiAlight()
                                                    }
                                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                                    .testTag("glass_mode_$m")
                                            ) {
                                                Text(
                                                    text = label,
                                                    color = if (sel) AccentOnColor else Color.White,
                                                    fontSize = 10.sp
                                                )
                                            }
                                        }
                                    }
                                }
                                // v2.0.137：语言选择独立成块——原先塞在「主题名 … 玻璃模式」
                                // 同一行的中间（内部还有 fillMaxWidth 的两行按钮），垂直居中后
                                // 与左右内容互相叠压，表现为"语言选择叠在主题和玻璃效果上面"。
                                val currentLangTag = remember(context) { LanguageManager.getTag(context) }
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        stringResource(R.string.ui_language),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    // 6 种语言分两行排（一列太挤）
                                    LanguageManager.options.chunked(3).forEach { rowTags ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            rowTags.forEach { tag ->
                                                val sel = currentLangTag == tag
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(
                                                            if (sel) AccentColor
                                                            else Color.White.copy(alpha = 0.08f)
                                                        )
                                                        .clickable {
                                                            keepUiAlight()
                                                            LanguageManager.apply(context, tag)
                                                        }
                                                        .padding(vertical = 6.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        LanguageManager.displayName(tag),
                                                        color = if (sel) AccentOnColor
                                                        else Color.White.copy(alpha = 0.75f),
                                                        fontSize = 9.sp,
                                                        fontWeight = if (sel) FontWeight.Bold
                                                        else FontWeight.Normal,
                                                        textAlign = TextAlign.Center
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Text(
                                        stringResource(R.string.ui_language_hint),
                                        color = Color.White.copy(alpha = 0.4f),
                                        fontSize = 8.sp
                                    )
                                }
                                }
                                /** 区块 1：镜头投影与视角模式（2D/鱼眼/360/180/盒子 + 变形） */
                                @Composable
                                fun SettingsSection1() {
                                Text(
                                    text = stringResource(R.string.settings_group_projection),
                                    color = AccentColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    // Smart projection detection master switch (8/1 功能)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(stringResource(R.string.projection_auto_detect), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(stringResource(R.string.projection_auto_detect_desc), color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Switch(
                                            checked = isSmartProjectionEnabled,
                                            onCheckedChange = {
                                                isSmartProjectionEnabled = it
                                                prefs.edit().putBoolean("smart_projection_enabled", it).apply()
                                                keepUiAlight()
                                            },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = AccentOnColor,
                                                checkedTrackColor = AccentColor,
                                                uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                                                uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                                            )
                                        )
                                    }

                                    // 强制视频类型判断（自动/2D/360°/180°/3D 左右/3D 上下）
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(stringResource(R.string.force_video_type), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                        val forceOptions = listOf(
                                            0 to stringResource(R.string.auto),
                                            1 to "2D",
                                            2 to "360°",
                                            3 to "180°",
                                            4 to stringResource(R.string.video_type_3d_sbs),
                                            5 to stringResource(R.string.video_type_3d_tab)
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            forceOptions.forEach { (type, label) ->
                                                val isSel = forceVideoType == type
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(if (isSel) AccentColor else Color.White.copy(alpha = 0.08f))
                                                        .clickable {
                                                            forceVideoType = type
                                                            prefs.edit().putInt("force_video_type", type).apply()
                                                            keepUiAlight()
                                                        }
                                                        .padding(vertical = 6.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = label,
                                                        color = if (isSel) AccentOnColor else Color.White,
                                                        fontSize = 9.sp,
                                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = stringResource(R.string.force_video_type_desc),
                                            color = Color.White.copy(alpha = 0.4f),
                                            fontSize = 8.sp
                                        )
                                    }

                                    Text(stringResource(R.string.view_format), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                    ProjectionMode.values().toList().chunked(3).forEach { rowModes ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            rowModes.forEach { mode ->
                                                val isSelected = projectionMode == mode
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(32.dp)
                                                        .background(
                                                            if (isSelected) AccentColor else Color.White.copy(alpha = 0.05f),
                                                            shape = RoundedCornerShape(8.dp)
                                                        )
                                                        .clickable {
                                                            projectionMode = mode
                                                            projectionModeUserAdjusted = true
                                                            keepUiAlight()
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = stringResource(mode.labelRes),
                                                        color = if (isSelected) AccentOnColor else Color.White,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                            // Fill empty spaces if a row is incomplete
                                            repeat(3 - rowModes.size) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }

                                // v2.1.211：**鱼眼视场角** —— 仅在 FISHEYE 模式下显示
                                // （其它模式读不到这个值，显示出来只会让人困惑）。
                                // 不同鱼眼镜头片源的实际视角不同（常见 180/190/200/220），
                                // 选错会出现「画面鼓成球」或「中心挤成一团」。
                                if (projectionMode == ProjectionMode.FISHEYE) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(stringResource(R.string.fisheye_fov), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            listOf(180, 190, 200, 220).forEach { fov ->
                                                val isSel = fisheyeFovDeg == fov
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(32.dp)
                                                        .background(
                                                            if (isSel) AccentColor else Color.White.copy(alpha = 0.05f),
                                                            shape = RoundedCornerShape(8.dp)
                                                        )
                                                        .clickable {
                                                            fisheyeFovDeg = fov
                                                            projectionModeUserAdjusted = true
                                                            keepUiAlight()
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = "${fov}°",
                                                        color = if (isSel) AccentOnColor else Color.White.copy(alpha = 0.8f),
                                                        fontSize = 10.sp,
                                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // ⚠️ v2.1.210 曾在此处新增「投影模式」选择器 —— **已删除**。
                                // 原因：项目在下方「视角格式」(`R.string.view_format`) 处**早就有**
                                // 同一个 `ProjectionMode` 的选择器（带 projectionModeUserAdjusted 标记）。
                                // 我当初 grep `R.string.proj_*` 没命中就误判为「没有入口」，
                                // 但那里是用 `mode.labelRes` **动态取标签**，静态 grep 搜不到 ——
                                // 教训：查「某枚举有没有 UI 入口」要用**枚举类型名**搜，不要搜资源 key。
                                // 新增的 EAC 会自动出现在原有选择器里（它遍历 ProjectionMode.values()），
                                // 无需额外 UI。
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(stringResource(R.string.stereo_format), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        StereoMode.values().forEach { mode ->
                                            val isSelected = stereoMode == mode
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(32.dp)
                                                    .background(
                                                        if (isSelected) AccentColor else Color.White.copy(alpha = 0.05f),
                                                        shape = RoundedCornerShape(8.dp)
                                                    )
                                                    .clickable {
                                                        stereoMode = mode
                                                        keepUiAlight()
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = when (mode) {
                                                        StereoMode.MONO -> stringResource(R.string.stereo_2d)
                                                        StereoMode.SBS -> stringResource(R.string.stereo_sbs)
                                                        StereoMode.TAB -> stringResource(R.string.stereo_tab)
                                                    },
                                                    color = if (isSelected) AccentOnColor else Color.White,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                }

                                // v2.0.174：华为 VR Glass（VR Engine）接入开关。
                                // 放在「立体格式」之后，与投影/分屏/FOV/IPD 同组，语义上属同一类「输出后端」配置。
                                // 该开关默认关；非华为设备开启只作记录并提示，不会阻断使用。
                                ExperimentalSwitchRow(
                                    title = stringResource(R.string.huawei_vr_enable),
                                    desc = stringResource(R.string.huawei_vr_enable_desc),
                                    checked = huaweiVrEnabled,
                                    onChanged = {
                                        huaweiVrEnabled = it
                                        keepUiAlight()
                                        if (it && !huaweiVrRuntimeAvailable) {
                                            // 不阻断用户：仅提示会回退到内置分屏 VR
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.huawei_vr_runtime_missing),
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    },
                                    accentColor = AccentColor,
                                    accentOnColor = AccentOnColor
                                )
                                if (huaweiVrEnabled) {
                                    // 状态文字与颜色预先算好（Compose 里 @Composable 调用不嵌在实参三元/if 表达式中更稳）
                                    val hwStatusText = if (huaweiVrRuntimeAvailable)
                                        stringResource(R.string.huawei_vr_ready)
                                    else
                                        stringResource(R.string.huawei_vr_runtime_absent)
                                    val hwStatusColor = if (huaweiVrRuntimeAvailable) AccentColor else Color(0xFFE0A030)
                                    val hwNoteText = stringResource(R.string.huawei_vr_note)
                                    val hwScaleLabel = "${(huaweiVrRenderScale * 100).toInt()}%"
                                    val hwScaleTitle = stringResource(R.string.huawei_vr_render_scale)
                                    val hwScaleDesc = stringResource(R.string.huawei_vr_render_scale_desc)
                                    Text(
                                        text = hwStatusText,
                                        color = hwStatusColor,
                                        fontSize = 9.sp
                                    )
                                    Text(
                                        text = hwNoteText,
                                        color = Color.White.copy(alpha = 0.45f),
                                        fontSize = 8.sp,
                                        lineHeight = 11.sp
                                    )
                                    ExperimentalSwitchRow(
                                        title = stringResource(R.string.huawei_vr_prefer_6dof),
                                        desc = stringResource(R.string.huawei_vr_prefer_6dof_desc),
                                        checked = huaweiVrPrefer6dof,
                                        onChanged = { huaweiVrPrefer6dof = it; keepUiAlight() },
                                        accentColor = AccentColor,
                                        accentOnColor = AccentOnColor
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                hwScaleTitle,
                                                color = Color.White.copy(alpha = 0.5f),
                                                fontSize = 10.sp
                                            )
                                            Text(
                                                hwScaleLabel,
                                                color = AccentColor,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Slider(
                                            value = huaweiVrRenderScale,
                                            onValueChange = {
                                                huaweiVrRenderScale = it
                                                keepUiAlight()
                                            },
                                            valueRange = 0.5f..1.0f,
                                            steps = 1, // 0.5 / 0.75 / 1.0
                                            colors = SliderDefaults.colors(
                                                thumbColor = AccentColor,
                                                activeTrackColor = AccentColor,
                                                inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                                            ),
                                            modifier = Modifier.height(26.dp)
                                        )
                                        Text(
                                            text = hwScaleDesc,
                                            color = Color.White.copy(alpha = 0.4f),
                                            fontSize = 8.sp,
                                            lineHeight = 11.sp
                                        )
                                    }
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(stringResource(R.string.video_mirror), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(false to stringResource(R.string.mirror_normal), true to stringResource(R.string.mirror_hflip)).forEach { (mirrored, label) ->
                                            val isSelected = isVideoMirrored == mirrored
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(32.dp)
                                                    .background(
                                                        if (isSelected) AccentColor else Color.White.copy(alpha = 0.05f),
                                                        shape = RoundedCornerShape(8.dp)
                                                    )
                                                    .clickable {
                                                        isVideoMirrored = mirrored
                                                        keepUiAlight()
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = label,
                                                    color = if (isSelected) AccentOnColor else Color.White,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                 )
                                            }
                                        }
                                    }
                                }

                                // 陀螺仪朝向模式：决定"头部转动"如何映射为画面视角。
                                // 手持横屏与 VR 眼镜平放时正确的轴向完全不同，选错会导致方向错乱。
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(stringResource(R.string.gyro_orientation_mode), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(
                                            VRSensorManager.OrientationMode.HANDHELD to stringResource(R.string.gyro_handheld),
                                            VRSensorManager.OrientationMode.VR_BOX to stringResource(R.string.gyro_vr_flat)
                                        ).forEach { (mode, label) ->
                                            val isSelected = gyroOrientationMode == mode
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(32.dp)
                                                    .background(
                                                        if (isSelected) AccentColor else Color.White.copy(alpha = 0.05f),
                                                        shape = RoundedCornerShape(8.dp)
                                                    )
                                                    .clickable {
                                                        gyroOrientationMode = mode
                                                        keepUiAlight()
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = label,
                                                    color = if (isSelected) AccentOnColor else Color.White,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = stringResource(R.string.gyro_orientation_desc),
                                        color = Color.White.copy(alpha = 0.4f),
                                        fontSize = 8.sp
                                    )
                                }

                                // v125：陀螺仪转向反转。默认关闭（v125 起已修正为正确方向），
                                // 个别机型或 VR 眼镜模式下若仍上下/左右相反，打开此项即可。
                                ExperimentalSwitchRow(
                                    title = stringResource(R.string.gyro_invert),
                                    desc = stringResource(R.string.gyro_invert_desc),
                                    checked = gyroInverted,
                                    onChanged = { gyroInverted = it; keepUiAlight() },
                                    accentColor = AccentColor,
                                    accentOnColor = AccentOnColor
                                )

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(stringResource(R.string.audio_channel_mirror), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(false to stringResource(R.string.audio_normal), true to stringResource(R.string.audio_swapped)).forEach { (mirrored, label) ->
                                            val isSelected = isAudioMirrored == mirrored
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(32.dp)
                                                    .background(
                                                        if (isSelected) AccentColor else Color.White.copy(alpha = 0.05f),
                                                        shape = RoundedCornerShape(8.dp)
                                                    )
                                                    .clickable {
                                                        isAudioMirrored = mirrored
                                                        keepUiAlight()
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = label,
                                                    color = if (isSelected) AccentOnColor else Color.White,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                 )
                                            }
                                        }
                                    }
                                }

                                if (projectionMode == ProjectionMode.VR_180) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(stringResource(R.string.dome_half_crop), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            listOf(1 to stringResource(R.string.dome_half_left), 0 to stringResource(R.string.dome_half_right)).forEach { (half, label) ->
                                                val isSelected = domeHalfSelect == half
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(32.dp)
                                                        .background(
                                                            if (isSelected) AccentColor else Color.White.copy(alpha = 0.05f),
                                                            shape = RoundedCornerShape(8.dp)
                                                        )
                                                        .clickable {
                                                            domeHalfSelect = half
                                                            keepUiAlight()
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = label,
                                                        color = if (isSelected) AccentOnColor else Color.White,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                // v2.0.174：华为 VR 模式下的「接管提示」。
                                // 华为 Runtime 用自己的 FOV(95°×95°) / IPD(63mm) / OpenXR pose，
                                // 因此下面这些手动设置不再生效——显式告知，避免用户反复调却看不到变化。
                                if (huaweiVrEnabled) {
                                    Surface(
                                        color = Color(0x1AE0A030),
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, Color(0x66E0A030)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = stringResource(R.string.huawei_vr_handover),
                                            color = Color(0xFFE0A030),
                                            fontSize = 9.sp,
                                            lineHeight = 13.sp,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                        )
                                    }
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(stringResource(R.string.fov_title), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                        Text("${fovDeg.toInt()}°", color = AccentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Slider(
                                        value = fovDeg,
                                        onValueChange = {
                                            fovDeg = it
                                            keepUiAlight()
                                        },
                                        valueRange = 25f..125f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = AccentColor,
                                            activeTrackColor = AccentColor,
                                            inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                                        ),
                                        modifier = Modifier
                                            .height(26.dp)
                                            .testTag("fov_slider")
                                    )
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(stringResource(R.string.warp_title), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                    WarpMode.values().toList().chunked(3).forEach { rowModes ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            rowModes.forEach { mode ->
                                                val isSelected = warpMode == mode
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(30.dp)
                                                        .background(
                                                            if (isSelected) AccentColor else Color.White.copy(alpha = 0.05f),
                                                            shape = RoundedCornerShape(8.dp)
                                                        )
                                                        .clickable {
                                                            warpMode = mode
                                                            keepUiAlight()
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = stringResource(mode.labelRes),
                                                        color = if (isSelected) AccentOnColor else Color.White,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                            // Fill empty spaces if a row is incomplete
                                            repeat(3 - rowModes.size) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                    
                                    if (warpMode != WarpMode.NONE) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            val label = when (warpMode) {
                                                WarpMode.CYLINDER_RECT -> stringResource(R.string.warp_equirect_cylinder)
                                                WarpMode.CYLINDER -> stringResource(R.string.warp_equirect_column)
                                                WarpMode.SPHERE -> stringResource(R.string.warp_sphere_expand)
                                                WarpMode.CURVE -> stringResource(R.string.warp_ring_curve)
                                                WarpMode.ANTI_SPHERE -> stringResource(R.string.warp_sphere_shrink)
                                                WarpMode.ANTI_CURVE -> stringResource(R.string.warp_curve_shrink)
                                                else -> stringResource(R.string.warp_zoom_curve)
                                            }
                                            Text(label, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                            Text(String.format("%.2f", videoCurvature), color = AccentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Slider(
                                            value = videoCurvature,
                                            onValueChange = {
                                                videoCurvature = it
                                                keepUiAlight()
                                            },
                                            valueRange = 0.0f..0.8f,
                                            colors = SliderDefaults.colors(
                                                thumbColor = AccentColor,
                                                activeTrackColor = AccentColor,
                                                inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                                            ),
                                            modifier = Modifier.height(26.dp)
                                        )
                                    }
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(stringResource(R.string.max_resolution_title), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                    MaxResolution.values().toList().chunked(3).forEach { rowResolutions ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            rowResolutions.forEach { res ->
                                                val isSelected = maxResolution == res
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(30.dp)
                                                        .background(
                                                            if (isSelected) AccentColor else Color.White.copy(alpha = 0.05f),
                                                            shape = RoundedCornerShape(8.dp)
                                                        )
                                                        .clickable {
                                                            maxResolution = res
                                                            keepUiAlight()
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = stringResource(res.labelRes),
                                                        color = if (isSelected) AccentOnColor else Color.White,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                            // Fill empty spaces if a row is incomplete
                                            repeat(3 - rowResolutions.size) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                                }
                                /** 区块 2：8K 硬解实验开关（SPS level 适配/强制硬解/软解回退） */
                                @Composable
                                fun SettingsSection8K() {
                                Text(
                                    text = stringResource(R.string.group_8k_hw),
                                    color = AccentColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                ExperimentalSwitchRow(
                                    title = stringResource(R.string.level_patch),
                                    desc = stringResource(R.string.level_patch_desc),
                                    checked = levelPatchEnabled,
                                    onChanged = { levelPatchEnabled = it },
                                    accentColor = AccentColor,
                                    accentOnColor = AccentOnColor
                                )
                                ExperimentalSwitchRow(
                                    title = stringResource(R.string.level51),
                                    desc = stringResource(R.string.level51_desc),
                                    checked = level51Enabled,
                                    onChanged = { level51Enabled = it },
                                    accentColor = AccentColor,
                                    accentOnColor = AccentOnColor
                                )
                                ExperimentalSwitchRow(
                                    title = stringResource(R.string.force_hw_decoder),
                                    desc = stringResource(R.string.force_hw_decoder_desc),
                                    checked = forceHwDecoderEnabled,
                                    onChanged = { forceHwDecoderEnabled = it },
                                    accentColor = AccentColor,
                                    accentOnColor = AccentOnColor
                                )
                                ExperimentalSwitchRow(
                                    title = stringResource(R.string.spoof_resolution),
                                    desc = stringResource(R.string.spoof_resolution_desc),
                                    checked = spoofResolutionEnabled,
                                    onChanged = { spoofResolutionEnabled = it },
                                    accentColor = AccentColor,
                                    accentOnColor = AccentOnColor
                                )
                                ExperimentalSwitchRow(
                                    title = stringResource(R.string.downscale_output),
                                    desc = stringResource(R.string.downscale_output_desc),
                                    checked = downscaleOutputEnabled,
                                    onChanged = { downscaleOutputEnabled = it },
                                    accentColor = AccentColor,
                                    accentOnColor = AccentOnColor
                                )
                                ExperimentalSwitchRow(
                                    title = stringResource(R.string.add_codec_params),
                                    desc = stringResource(R.string.add_codec_params_desc),
                                    checked = addCodecParamsEnabled,
                                    onChanged = { addCodecParamsEnabled = it },
                                    accentColor = AccentColor,
                                    accentOnColor = AccentOnColor
                                )
                                ExperimentalSwitchRow(
                                    title = stringResource(R.string.auto_fallback_soft),
                                    desc = stringResource(R.string.auto_fallback_soft_desc),
                                    checked = autoFallbackSoftEnabled,
                                    onChanged = { autoFallbackSoftEnabled = it },
                                    accentColor = AccentColor,
                                    accentOnColor = AccentOnColor
                                )

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0x0CFFFFFF), shape = RoundedCornerShape(10.dp))
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.panorama_note),
                                        color = Color.White.copy(alpha = 0.5f),
                                        fontSize = 9.sp,
                                        lineHeight = 12.sp
                                    )
                                }
                                }
                                /** 区块 3：悬浮球控速与播放倍速 */
                                @Composable
                                fun SettingsSection3() {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    /** v2.0.165：悬浮球开关行（加速球 / 快进球 / 后退球共用同一布局样式） */
                                    @Composable
                                    fun BallSwitchRow(
                                        title: String,
                                        desc: String?,
                                        checked: Boolean,
                                        onChange: (Boolean) -> Unit,
                                        tag: String
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(Color.White.copy(alpha = 0.05f), shape = RoundedCornerShape(10.dp))
                                                .clickable { onChange(!checked); keepUiAlight() }
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    title,
                                                    color = Color.White,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    // v2.1.213：标题限 1 行 —— 某些语言的标题较长
                                                    //（德/俄语系），不限行会把 Switch 挤出可视区
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                // ⚠️ 不用 desc?.let{} —— @Composable 调用嵌进普通 lambda 会丢作用域
                                                if (desc != null) {
                                                    Text(
                                                        desc,
                                                        color = Color.White.copy(alpha = 0.5f),
                                                        fontSize = 9.sp,
                                                        // v2.1.213：**描述必须限行**。
                                                        // 原先不限行，描述一长（如时间标记球那句 33 字）
                                                        // 就会换行成 4~5 行、把整行撑得极高，
                                                        // 视觉上像「开关这块 UI 溢出/撑爆了」。
                                                        // 2 行 + 省略号，溢出内容仍可读到开头。
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                            Switch(
                                                checked = checked,
                                                onCheckedChange = { onChange(it); keepUiAlight() },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = AccentOnColor,
                                                    checkedTrackColor = AccentColor,
                                                    uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                                                    uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                                                ),
                                                modifier = Modifier
                                                    // v2.1.213：**scale() 不改变布局占位**，
                                                    // Switch 仍按标准宽度（约 52dp）占位、在窄屏上
                                                    // 会把左侧文字挤到很窄。给固定宽度把空间让给文字。
                                                    .width(46.dp)
                                                    .scale(0.8f)
                                                    .testTag(tag)
                                            )
                                        }
                                    }

                                    Text(
                                        text = stringResource(R.string.settings_group_floating_ball),
                                        color = AccentColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color.White.copy(alpha = 0.05f), shape = RoundedCornerShape(10.dp))
                                            .clickable {
                                                isFloatingBallEnabled = !isFloatingBallEnabled
                                                keepUiAlight()
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(stringResource(R.string.floating_ball_enable), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(stringResource(R.string.floating_ball_desc), color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Switch(
                                            checked = isFloatingBallEnabled,
                                            onCheckedChange = {
                                                isFloatingBallEnabled = it
                                                keepUiAlight()
                                            },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = AccentOnColor,
                                                checkedTrackColor = AccentColor,
                                                uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                                                uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                                            ),
                                            modifier = Modifier.scale(0.8f).testTag("floating_ball_switch")
                                        )
                                    }

                                    // v2.0.165：快进 / 后退悬浮球 —— 各自独立开关，默认均为**关**
                                    BallSwitchRow(
                                        title = stringResource(R.string.seek_ball_forward_enable),
                                        desc = stringResource(R.string.seek_ball_desc),
                                        checked = isSeekForwardBallEnabled,
                                        onChange = { isSeekForwardBallEnabled = it },
                                        tag = "seek_forward_ball_switch"
                                    )
                                    BallSwitchRow(
                                        title = stringResource(R.string.seek_ball_backward_enable),
                                        desc = stringResource(R.string.seek_ball_desc),
                                        checked = isSeekBackwardBallEnabled,
                                        onChange = { isSeekBackwardBallEnabled = it },
                                        tag = "seek_backward_ball_switch"
                                    )
                                    // v2.1.208：时间标记球开关（与上面两个同组、同一样式组件）
                                    BallSwitchRow(
                                        title = stringResource(R.string.marker_ball_enable),
                                        desc = stringResource(R.string.marker_ball_desc),
                                        checked = isMarkerBallEnabled,
                                        onChange = { isMarkerBallEnabled = it },
                                        tag = "marker_ball_switch"
                                    )

                                    if (isFloatingBallEnabled) {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(stringResource(R.string.floating_ball_speed), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                listOf(1.5f to "1.5X", 2.0f to "2.0X", 3.0f to "3.0X").forEach { (speed, label) ->
                                                    val isSelected = floatingBallSpeed == speed
                                                    Box(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .height(30.dp)
                                                            .background(
                                                                if (isSelected) AccentColor else Color.White.copy(alpha = 0.05f),
                                                                shape = RoundedCornerShape(8.dp)
                                                            )
                                                            .clickable {
                                                                floatingBallSpeed = speed
                                                                keepUiAlight()
                                                            },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = label,
                                                            color = if (isSelected) AccentOnColor else Color.White,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(stringResource(R.string.base_playback_speed), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            listOf(0.75f to "0.75X", 1.0f to "1.0X", 1.25f to "1.25X", 1.5f to "1.5X", 2.0f to "2.0X").forEach { (speed, label) ->
                                                val isSelected = basePlaybackSpeed == speed
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(30.dp)
                                                        .background(
                                                            if (isSelected) AccentColor else Color.White.copy(alpha = 0.05f),
                                                            shape = RoundedCornerShape(8.dp)
                                                        )
                                                        .clickable {
                                                            basePlaybackSpeed = speed
                                                            keepUiAlight()
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = label,
                                                        color = if (isSelected) AccentOnColor else Color.White,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                }
                                /** 区块 4：解码内核与帧率限制 */
                                @Composable
                                fun SettingsSection4() {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = stringResource(R.string.settings_group_decoder),
                                        color = AccentColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )

                                    // 解码器切换 EXO/MPV
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(stringResource(R.string.decoder_engine), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                        // v2.1.232：IJK 加入「预留位」。是否置灰**由枚举自己说了算**
                                        //（DecoderEngine.isImplemented）—— 将来接好了只需改那一处，UI 不用动。
                                        // v2.1.236：改成 filter 只展示**已启用**的内核 ——
                                        // 停用的（如 MPV，见 MPV_ENABLED）会**直接不出现**，
                                        // 而不是显示成置灰的「开发中」（那会让人以为功能坏了）。
                                        // ⚠️ 过滤依据仍是 isImplemented，所以启用/停用内核
                                        //    依然不需要动这里。
                                        DecoderEngine.values().filter { it.isImplemented }.forEach { engine ->
                                            // 过滤后恒为 false，保留是为了兼容下面依赖它的样式分支
                                            val isPlaceholder = !engine.isImplemented
                                            val isSelected = !isPlaceholder && decoderEngine == engine
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(30.dp)
                                                        .background(
                                                            if (isSelected) AccentColor else Color.White.copy(alpha = 0.05f),
                                                            shape = RoundedCornerShape(8.dp)
                                                        )
                                                        .clickable(enabled = !isPlaceholder) {
                                                            decoderEngine = engine
                                                            Toast.makeText(context, context.getString(R.string.toast_decoder_switched, context.getString(engine.labelRes)), Toast.LENGTH_SHORT).show()
                                                            keepUiAlight()
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = if (isPlaceholder) {
                                                            stringResource(engine.labelRes) + " · " + stringResource(R.string.decoder_coming_soon)
                                                        } else stringResource(engine.labelRes),
                                                        color = when {
                                                            isPlaceholder -> Color.White.copy(alpha = 0.35f)
                                                            isSelected -> AccentOnColor
                                                            else -> Color.White
                                                        },
                                                        fontSize = if (isPlaceholder) 9.sp else 10.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // v2.1.232：**解码器参数（预留）** ——
                                    // 用户要求「设置里预留地方修改」。这里按当前选中的内核留出一块
                                    // 参数区：每个内核将来可以挂自己的调参项（缓冲/探测尺寸/硬解策略…）。
                                    // 未接通的内核显示说明文案，接好后把分支内容替换成真实控件即可。
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.White.copy(alpha = 0.04f))
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.decoder_params_section) +
                                                " · " + stringResource(decoderEngine.labelRes),
                                            color = Color.White.copy(alpha = 0.55f),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        if (!decoderEngine.isImplemented) {
                                            Text(
                                                text = stringResource(R.string.decoder_params_placeholder),
                                                color = Color.White.copy(alpha = 0.4f),
                                                fontSize = 8.sp,
                                                lineHeight = 11.sp
                                            )
                                        } else if (decoderEngine == DecoderEngine.IJK) {
                                            // v2.1.233：IJK 已接通 → 挂上真实调参项。
                                            // 改任何一项都会更新 ijkOptions state，而它是重建 effect 的 key，
                                            // 因此改完自动重建播放器并从原位置续播，无需「应用」按钮。
                                            IjkOptionsPanel(
                                                options = ijkOptions,
                                                onOptionsChange = { ijkOptions = it },
                                                accentColor = AccentColor,
                                                accentOnColor = AccentOnColor,
                                                defaultBufferLabel = stringResource(R.string.ijk_buffer_default)
                                            )
                                        } else if (decoderEngine == DecoderEngine.MPV) {
                                            // v2.1.235：MPV 的 native 库不进 APK，
                                            // 所以先给一个"解码库"区块（未装时是下载入口）
                                            MpvLibPanel(
                                                context = context,
                                                accentColor = AccentColor,
                                                accentOnColor = AccentOnColor
                                            )
                                            // v2.1.234：MPV 参数（同样的机制：改完自动重建 + 续播）
                                            MpvOptionsPanel(
                                                options = mpvOptions,
                                                onOptionsChange = { mpvOptions = it },
                                                accentColor = AccentColor,
                                                accentOnColor = AccentOnColor,
                                                defaultLabel = stringResource(R.string.ijk_buffer_default)
                                            )
                                        } else {
                                            // EXO 目前可调项已在下面「软件/硬件解码」等开关里，
                                            // 这里保留给将来与内核绑定的参数。
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.decoder_coming_soon),
                                                    color = Color.White.copy(alpha = 0.35f),
                                                    fontSize = 8.sp
                                                )
                                                Text(
                                                    text = stringResource(decoderEngine.labelRes),
                                                    color = AccentColor,
                                                    fontSize = 8.sp
                                                )
                                            }
                                        }
                                    }

                                    // v2.1.234：**视频信息** ——
                                    // 显示当前片源的容器/编码/分辨率/帧率/码率/音轨/实际解码方式。
                                    // 三个内核（Exo / IJK / MPV）各自能给的字段不同，统一由
                                    // VideoInfoPanel 内部走 currentVideoInfo() 分发，这里不需要判断内核。
                                    VideoInfoPanel(
                                        player = playerInstance,
                                        uri = currentVideoUri,
                                        isSoftwareDecoding = isSoftwareDecoding,
                                        accentColor = AccentColor
                                    )

                                    // 软硬解码切换
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(stringResource(R.string.decode_mode), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            listOf(false to stringResource(R.string.decode_hw), true to stringResource(R.string.decode_sw)).forEach { (isSw, label) ->
                                                val isSelected = isSoftwareDecoding == isSw
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(30.dp)
                                                        .background(
                                                            if (isSelected) AccentColor else Color.White.copy(alpha = 0.05f),
                                                            shape = RoundedCornerShape(8.dp)
                                                        )
                                                        .clickable {
                                                            isSoftwareDecoding = isSw
                                                            Toast.makeText(context, "已切换为: ${if (isSw) "软件解码" else context.getString(R.string.info_hw_decode)}", Toast.LENGTH_SHORT).show()
                                                            keepUiAlight()
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = label,
                                                        color = if (isSelected) AccentOnColor else Color.White,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // 帧率限制 12/18/24/30/48/60/90/120
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(stringResource(R.string.fps_limit), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                        val fpsList = listOf(0 to stringResource(R.string.no_limit), 12 to "12", 18 to "18", 24 to "24", 30 to "30", 48 to "48", 60 to "60", 90 to "90", 120 to "120")
                                        fpsList.chunked(5).forEach { rowFps ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                rowFps.forEach { (fpsVal, label) ->
                                                    val isSelected = maxFps == fpsVal
                                                    Box(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .height(28.dp)
                                                            .background(
                                                                if (isSelected) AccentColor else Color.White.copy(alpha = 0.05f),
                                                                shape = RoundedCornerShape(6.dp)
                                                            )
                                                            .clickable {
                                                                maxFps = fpsVal
                                                                currentGlSurfaceView?.renderer?.maxFps = fpsVal
                                                                keepUiAlight()
                                                            },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = label,
                                                            color = if (isSelected) AccentOnColor else Color.White,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    }
                                                }
                                                repeat(5 - rowFps.size) {
                                                    Spacer(modifier = Modifier.weight(1f))
                                                }
                                            }
                                        }
                                    }
                                }
                                }
                                /** 区块 5：画质增强（MEMC 插帧 / FSR 超分） */
                                @Composable
                                fun SettingsSectionEnhance() {

                                // FSR 的「当前判定结果」—— 与渲染侧用**同一个纯函数**算出来，
                                // 这样 UI 上显示的原因文案与 renderer 的实际行为永远不会打架。
                                val fsrDecision = remember(
                                    videoSourceWidth, videoSourceHeight, fsrRuleMode, fsrCustomTarget
                                ) {
                                    VideoEnhanceRules.resolveFsr(
                                        videoSourceWidth, videoSourceHeight, fsrRuleMode, fsrCustomTarget
                                    )
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = stringResource(R.string.settings_group_enhance),
                                        color = AccentColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )

                                    // ===== MEMC 插帧 =====
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color.White.copy(alpha = 0.05f), shape = RoundedCornerShape(10.dp))
                                            .clickable { isMemcEnabled = !isMemcEnabled; keepUiAlight() }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(stringResource(R.string.memc_enabled), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(stringResource(R.string.memc_desc), color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Switch(
                                            checked = isMemcEnabled,
                                            onCheckedChange = { isMemcEnabled = it; keepUiAlight() },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = AccentOnColor,
                                                checkedTrackColor = AccentColor,
                                                uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                                                uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                                            ),
                                            modifier = Modifier.scale(0.8f)
                                        )
                                    }

                                    // 目标帧率：仅插帧打开时才显示（关闭时它没有任何作用对象）
                                    if (isMemcEnabled) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(stringResource(R.string.memc_target_fps), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                VideoEnhanceRules.MEMC_TARGET_FPS_OPTIONS.forEach { fps ->
                                                    val isSelected = memcTargetFps == fps
                                                    Box(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .height(28.dp)
                                                            .background(
                                                                if (isSelected) AccentColor else Color.White.copy(alpha = 0.05f),
                                                                shape = RoundedCornerShape(6.dp)
                                                            )
                                                            .clickable { memcTargetFps = fps; keepUiAlight() },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = "$fps",
                                                            color = if (isSelected) AccentOnColor else Color.White,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // ===== FSR 超分 =====
                                    // 副标题直接显示「当前会怎样」，用户不用猜规则有没有生效
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color.White.copy(alpha = 0.05f), shape = RoundedCornerShape(10.dp))
                                            .clickable { isFsrEnabled = !isFsrEnabled; keepUiAlight() }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(stringResource(R.string.fsr_enabled), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            // 副标题 = 判定原因 +（启用时）**实际生效的目标尺寸**。
                                            // ⚠️ 实际尺寸按「档位高度 × 源宽高比」推出，非 16:9 片源
                                            //    会与档位的 16:9 参考值不同，必须显示真实值 ——
                                            //    否则用户（或下次排查的我）会以为设置没生效。
                                            //    ⚠️ stringResource 先算成 val 再用 if —— 别把它写进 if/else 分支里。
                                            val baseReason = stringResource(fsrDecision.reason.labelRes)
                                            val reasonLine = if (fsrDecision.enabled) {
                                                "$baseReason · ${fsrDecision.targetWidth}×${fsrDecision.targetHeight}"
                                            } else baseReason
                                            Text(reasonLine, color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Switch(
                                            checked = isFsrEnabled,
                                            onCheckedChange = { isFsrEnabled = it; keepUiAlight() },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = AccentOnColor,
                                                checkedTrackColor = AccentColor,
                                                uncheckedThumbColor = Color.White.copy(alpha = 0.6f),
                                                uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                                            ),
                                            modifier = Modifier.scale(0.8f)
                                        )
                                    }

                                    if (isFsrEnabled) {
                                        // 规则模式：默认规则 / 自定义（自定义一旦选中即完全接管）
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(stringResource(R.string.fsr_rule_mode), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                FsrRuleMode.values().forEach { mode ->
                                                    val isSelected = fsrRuleMode == mode
                                                    Box(
                                                        modifier = Modifier
                                                            .weight(1f)
                                                            .height(30.dp)
                                                            .background(
                                                                if (isSelected) AccentColor else Color.White.copy(alpha = 0.05f),
                                                                shape = RoundedCornerShape(8.dp)
                                                            )
                                                            .clickable { fsrRuleMode = mode; keepUiAlight() },
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = stringResource(mode.labelRes),
                                                            color = if (isSelected) AccentOnColor else Color.White,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        // 自定义目标分辨率：仅在自定义规则下出现。
                                        // 档位按 3 列排布，label 里带上实际宽高（如「2160p (3840×2160)」），
                                        // 避免 3840p 这类非标准命名产生歧义。
                                        if (fsrRuleMode == FsrRuleMode.CUSTOM) {
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(stringResource(R.string.fsr_target_resolution), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                                val allRes = FsrTargetResolution.values().toList()
                                                allRes.chunked(3).forEach { rowRes ->
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        rowRes.forEach { res ->
                                                            val isSelected = fsrCustomTarget == res
                                                            Box(
                                                                modifier = Modifier
                                                                    .weight(1f)
                                                                    .height(28.dp)
                                                                    .background(
                                                                        if (isSelected) AccentColor else Color.White.copy(alpha = 0.05f),
                                                                        shape = RoundedCornerShape(6.dp)
                                                                    )
                                                                    .clickable { fsrCustomTarget = res; keepUiAlight() },
                                                                contentAlignment = Alignment.Center
                                                            ) {
                                                                Text(
                                                                    text = res.label,
                                                                    color = if (isSelected) AccentOnColor else Color.White,
                                                                    fontSize = 8.sp,
                                                                    fontWeight = FontWeight.SemiBold
                                                                )
                                                            }
                                                        }
                                                        repeat(3 - rowRes.size) {
                                                            Spacer(modifier = Modifier.weight(1f))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Text(
                                        text = stringResource(R.string.enhance_perf_hint),
                                        color = Color.White.copy(alpha = 0.35f),
                                        fontSize = 9.sp
                                    )
                                }
                                }

                                /** 区块 6：字幕功能设置（SubtitleSettingsPanel：字体/位置/ASR/翻译入口） */
                                @Composable
                                fun SettingsSectionSubtitle() {

                                // ===== ASR 引擎选择（v111：Vosk / Qwen3-ASR / SenseVoice QNN）=====
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White.copy(alpha = 0.04f))
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.asr_engine_title),
                                        color = AccentColor,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        // v127：引擎固定为 SenseVoice，不再提供切换按钮
                                    }
                                    // 引擎说明
                                    Text(
                                        text = stringResource(R.string.asr_engine_sensevoice_desc),
                                        color = Color.White.copy(alpha = 0.45f),
                                        fontSize = 8.sp,
                                        lineHeight = 11.sp
                                    )
                                    // sherpa-onnx 引擎语言选择（v111；v127 含 SenseVoice CPU）
                                    // v2.0.208：多级收纳 —— 支持「按语言 / 按模型」两种分组视角，
                                    //           语区可折叠，同语言多模型时支持选择模型。
                                    // 当前生效模型统一解析一次（chips 选中判定要用；此处仅供本处使用）
                                    val activeModelId =
                                        SherpaAsrManager.resolveExtModel(context, sherpaLangCode)?.dirName
                                            ?: "builtin"
                                    // v2.1.232：语言选择的**唯一实现**在 AsrLanguageChips ——
                                    // 此前设置面板与字幕悬浮窗各有一份副本，改一处漏一处已发生两次
                                    //（v2.1.226 内置候选选不中、v2.1.231 四项分类）。现在共用同一份。
                                    AsrLanguageChips(
                                        context = context,
                                        sherpaLangCode = sherpaLangCode,
                                        activeModelId = activeModelId,
                                        accentColor = AccentColor,
                                        accentOnColor = AccentOnColor,
                                        onPick = { code, modelId ->
                                            // ⚠️ 顺序不能反：先切语言、再写模型选择。
                                            //    同语言换模型时 changeAsrLanguage 会直接 return，
                                            //    全靠 setModelChoice 触发识别器重建。
                                            changeAsrLanguage(code)
                                            SherpaAsrManager.setModelChoice(context, code, modelId)
                                        },
                                        onUserInteraction = { keepUiAlight() }
                                    )
                                    // sherpa-onnx 引擎：模型状态 + 下载
                                    run {
                                        // v2.0.145：模型信息与就绪状态随所选语言变化
                                        // （扩展语言如越南语是独立模型，需单独下载，不能沿用 SenseVoice 的判断）
                                        val langKey = sherpaLangCode
                                        val modelInfo = SherpaAsrManager.modelInfoFor(context, langKey)
                                        val sherpaReady = remember(langKey) { mutableStateOf(SherpaAsrManager.isModelReadyFor(context, langKey)) }
                                        LaunchedEffect(langKey, SherpaAsrManager.isModelDownloading, SherpaAsrManager.modelDownloadProgress) {
                                            sherpaReady.value = SherpaAsrManager.isModelReadyFor(context, langKey)
                                        }
                                        val modelName = modelInfo.first
                                        val modelDesc = if (modelInfo.third) stringResource(R.string.asr_ext_model_tag) else stringResource(R.string.asr_sensevoice_tag)
                                        val modelSizeMB = modelInfo.second
                                        // ⚠️ v2.0.208：多模型的选择已上移到语言 chips（英语1/英语2/英语3
                                        //    点谁用谁，见上方按语区分组处），这里不再重复显示候选单选。
                                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                            // 模型信息
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = modelName,
                                                        color = Color.White.copy(alpha = 0.85f),
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text(
                                                        text = modelDesc,
                                                        color = Color.White.copy(alpha = 0.4f),
                                                        fontSize = 8.sp
                                                    )
                                                }
                                                Text(
                                                    text = when {
                                                        SherpaAsrManager.isModelDownloading -> stringResource(R.string.asr_downloading)
                                                        sherpaReady.value -> stringResource(R.string.asr_ready)
                                                        else -> stringResource(R.string.asr_not_downloaded)
                                                    },
                                                    color = when {
                                                        SherpaAsrManager.isModelDownloading -> Color(0xFF4FC3F7)
                                                        sherpaReady.value -> Color(0xFF81C784)
                                                        else -> Color.White
                                                    },
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            // 下载进度
                                            if (SherpaAsrManager.isModelDownloading) {
                                                LinearProgressIndicator(
                                                    progress = SherpaAsrManager.modelDownloadProgress,
                                                    color = AccentColor,
                                                    trackColor = Color.White.copy(alpha = 0.15f),
                                                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))
                                                )
                                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                    Text(stringResource(R.string.asr_download_percent, (SherpaAsrManager.modelDownloadProgress * 100).toInt()), color = Color(0xFF4FC3F7), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                                    Text(if (SherpaAsrManager.downloadStatus.isEmpty()) stringResource(R.string.asr_status_builtin_ready) else SherpaAsrManager.downloadStatus, color = Color.White.copy(alpha = 0.5f), fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                Text(
                                                    text = stringResource(R.string.asr_cancel_download),
                                                    color = Color(0xFFEF5350),
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.align(Alignment.End)
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(Color(0xFFEF5350).copy(alpha = 0.15f))
                                                        .clickable { SherpaAsrManager.cancelDownload(context) }
                                                        .padding(horizontal = 10.dp, vertical = 3.dp)
                                                )
                                            }
                                            // 下载按钮
                                            if (!sherpaReady.value && !SherpaAsrManager.isModelDownloading) {
                                                Text(
                                                    text = stringResource(R.string.asr_click_to_download_prefix) + modelName + "（" + modelSizeMB + "MB）",
                                                    color = Color.White,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.fillMaxWidth()
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(AccentColor.copy(alpha = 0.85f))
                                                        .clickable { SherpaAsrManager.startDownloadFor(context, sherpaLangCode) }
                                                        .padding(vertical = 7.dp)
                                                )
                                            }
                                            if (sherpaReady.value) {
                                                Text(
                                                    text = stringResource(R.string.asr_model_ready_hint, modelName),
                                                    color = Color(0xFF81C784),
                                                    fontSize = 8.sp
                                                )
                                            }
                                        }
                                    }
                                }

                                // ===== v2.3.0：AI 弹幕设置已**独立成组**（见 SettingsSectionDanmu）
                                // 此前 v2.2.0 曾把弹幕面板放在本分组内，导致「弹幕」与「字幕」混在一起。
                                // 现已在左列以顶级分组 `SettingsGroup(group_title_danmu)` 与字幕并列。

                                SubtitleSettingsPanel(
                                    isSubtitleEnabled = isSubtitleEnabled,
                                    onSubtitleEnabledChange = {
                                        isSubtitleEnabled = it
                                        // v2.0.127：记下用户的显式选择——关掉之后，
                                        // 切视频时自动加载的字幕不会再把它重新打开
                                        if (isMemoryModeEnabled) {
                                            prefs.edit().putBoolean("subtitle_user_disabled", !it).apply()
                                        }
                                    },
                                    stripPunctuation = isStripSubtitlePunctuation,
                                    onStripPunctuationChange = { isStripSubtitlePunctuation = it },
                                    loadedSubtitleFileName = loadedSubtitleFileName,
                                    loadedCueCount = loadedSubtitleCues.size,
                                    onPickSubtitleFile = {
                                        subtitleFilePickerLauncher.launch("*/*")
                                    },
                                    subtitleFont = subtitleFont,
                                    onFontChange = { subtitleFont = it },
                                    fontSizeSp = subtitleFontSizeSp,
                                    onFontSizeChange = { subtitleFontSizeSp = it },
                                    fontWeightVal = subtitleFontWeightVal,
                                    onFontWeightChange = { subtitleFontWeightVal = it },
                                    isItalic = isSubtitleItalic,
                                    onItalicChange = { isSubtitleItalic = it },
                                    selectedColorOption = subtitleColorOpt,
                                    onColorOptionChange = { subtitleColorOpt = it },
                                    textAlpha = subtitleTextAlpha,
                                    onTextAlphaChange = { subtitleTextAlpha = it },
                                    selectedStrokeOption = subtitleStrokeOpt,
                                    onStrokeOptionChange = { subtitleStrokeOpt = it },
                                    selectedBgOption = subtitleBgOpt,
                                    onBgOptionChange = { subtitleBgOpt = it },
                                    offsetYRatio = subtitleOffsetYRatio,
                                    onOffsetYRatioChange = { subtitleOffsetYRatio = it },
                                    offsetXRatio = subtitleOffsetXRatio,
                                    onOffsetXRatioChange = { subtitleOffsetXRatio = it },
                                    delayMs = subtitleDelayMs,
                                    onDelayMsChange = { subtitleDelayMs = it },
                                    textAlign = subtitleTextAlignOpt,
                                    onTextAlignChange = { subtitleTextAlignOpt = it },
                                    // v2.1.232：此前这里**没传** maxLines（用 SubtitleSettingsPanel 的
                                    // 默认值 2），导致 subtitleMaxLines 虽然有持久化却**没有任何 UI 能改它**
                                    // —— 现在与 SubtitleStyleSettings 打通，悬浮窗也可调。
                                    maxLines = subtitleMaxLines,
                                    onMaxLinesChange = { subtitleMaxLines = it },
                                    vrIpdOffsetRatio = vrIpdOffsetRatio,
                                    onVrIpdOffsetRatioChange = { vrIpdOffsetRatio = it },
                                    subtitleSearchApiKey = subtitleSearchApiKey,
                                    onSubtitleSearchApiKeyChange = {
                                        subtitleSearchApiKey = it
                                        prefs.edit().putString("subtitle_search_api_key", it).apply()
                                    },
                                    defaultSearchQuery = selectedMediaItem.title,
                                    // v117 修复：此前未接线，落到 SubtitleSettingsPanel 里的空实现默认值，
                                    // 于是"已下载并加载"只是文案 —— 文件从未被解析，字幕永远不显示。
                                    onSubtitleFileLoaded = { file ->
                                        scope.launch(Dispatchers.IO) {
                                            try {
                                                // v2.1.248：读字节自行解码 + 统一嗅探入口
                                                val content = SubtitleParser.decodeBytes(file.readBytes())
                                                val cues = SubtitleParser.parse(content)
                                                withContext(Dispatchers.Main) {
                                                    if (cues.isEmpty()) {
                                                        Toast.makeText(
                                                            context,
                                                            context.getString(R.string.toast_subtitle_empty_format, file.name),
                                                            Toast.LENGTH_LONG
                                                        ).show()
                                                        return@withContext
                                                    }
                                                    loadedSubtitleCues = cues
                                                    loadedSubtitleFileName = file.name
                                                    isSubtitleEnabled = true
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.toast_online_subtitle_loaded, cues.size),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                    if (subtitleTranslator.config.isEnabled) {
                                                        subtitleTranslator.translateCuesBatch(cues)
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                Log.e("VRPlayerScreen", "在线字幕解析失败", e)
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.toast_subtitle_parse_failed, (e.message ?: "")),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        }
                                    },
                                    translator = subtitleTranslator,
                                    onTranslateEnabledChange = { enabled ->
                                        // v2.0.127：字幕面板里的翻译开关也要落盘，
                                        // 否则重启后翻译总是回到关闭
                                        if (isMemoryModeEnabled) {
                                            prefs.edit().putBoolean("translation_enabled", enabled).apply()
                                        }
                                    },
                                    onTranslateFileRequested = { subtitleTranslator.translateCuesBatch(loadedSubtitleCues) },
                                    onExportSubtitle = { exportSubtitleSrt() },
                                    accentColor = AccentColor,
                                    accentOnColor = AccentOnColor,
                                    onUserActivity = { keepUiAlight() }
                                )

                                // ===== 鍚庡彴鐢熸垚鍏ㄧ墖瀛楀箷锛坴91 鎻愬彇澶嶇敤锛?====
BatchTranscribeSection(
                                                            accentColor = AccentColor,
                                                            accentOnColor = AccentOnColor,
                                                            sherpaLangCode = sherpaLangCode,
                                                            onSherpaLangCodeChange = { changeAsrLanguage(it) },
                                                            onUserInteraction = { keepUiAlight() }
                                                        )
                                }
                                /**
                                 * 区块 6b：AI 弹幕设置（v2.3.0 从「字幕」分组中独立出来）
                                 *
                                 * 独立理由：弹幕与字幕是**两条独立链路** —— 字幕来自文件/ASR，
                                 * 弹幕来自视觉模型实时生成。混在同一分组里，用户改弹幕要去字幕里找，
                                 * 且以后两边各自长大必然互相干扰。
                                 *
                                 * 本分组只承载**配置 UI**；渲染与取帧分别由
                                 * `DanmuOverlay`（P5）/ `VRGLRenderer.captureDanmuFrameIfNeeded`（P3）负责。
                                 */
                                @Composable
                                fun SettingsSectionDanmu() {
                                    DanmuSettingsPanel(
                                        config = danmuConfig,
                                        onConfigChange = { danmuConfig = it },
                                        canPersistSecrets = isMemoryModeEnabled,
                                        generatedCount = danmuGeneratedCount,
                                        lastError = danmuLastError,
                                        // v2.4.2：与字幕面板用同一强调色
                                        accentColor = AccentColor
                                    )
                                }
                                /** 区块 6：美颜设置（Shader 实时磨皮美白 + 预设方案 + 对比原图 + 2D 人像精修） */
                                @Composable
                                fun SettingsColumn2() {
                                // 应用美颜预设（13 项参数，顺序与下方滑块一致）
                                fun applyBeautyPreset(presetId: String) {
                                    beautyPreset = presetId
                                    val p = when (presetId) {
                                        BEAUTY_PRESET_NATURAL -> floatArrayOf(0.4f, 0.3f, 0.2f, 0.15f, 0.15f, 0.1f, 0.1f, 0.2f, 0.15f, 0.15f, 0.3f, 0.1f, 0.1f)
                                        BEAUTY_PRESET_LIGHT -> floatArrayOf(0.6f, 0.5f, 0.4f, 0.3f, 0.3f, 0.2f, 0.2f, 0.3f, 0.35f, 0.35f, 0.45f, 0.2f, 0.2f)
                                        BEAUTY_PRESET_HEAVY -> floatArrayOf(0.9f, 0.8f, 0.7f, 0.6f, 0.5f, 0.35f, 0.35f, 0.5f, 0.6f, 0.6f, 0.7f, 0.4f, 0.35f)
                                        else -> return
                                    }
                                    beautyLevel = p[0]; beautyWhitening = p[1]; beautyFaceSlimming = p[2]; beautyBigEyes = p[3]
                                    beautyDarkCircles = p[4]; beautyNoseSlimming = p[5]; beautyMouth = p[6]; beautyTeethWhitening = p[7]
                                    beautyLipstick = p[8]; beautyBlush = p[9]; beautyEyebrows = p[10]; beautyLongLegs = p[11]; beautySmallHead = p[12]
                                    keepUiAlight()
                                }

                                Text(
                                    text = stringResource(R.string.settings_group_beauty),
                                    color = AccentColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                // 2D 模式判定（人像精修仅在 2D 生效，多处复用）
                                val is2DBeautyMode = projectionMode == ProjectionMode.STANDARD ||
                                    projectionMode == ProjectionMode.FISHEYE

                                // v120 拆分：模式提示条 / 对比原图 / 美颜预设 → BeautySettingsSections.kt
                                BeautyModeHintBar(is2DMode = is2DBeautyMode, modeName = stringResource(projectionMode.labelRes))

                                // v2.0.160：对比原图 → 美颜总开关（默认开；关闭 = 直通原图）
                                BeautyCompareSwitch(
                                    checked = beautyMasterEnabled,
                                    onCheckedChange = { beautyMasterEnabled = it; keepUiAlight() },
                                    accentColor = AccentColor,
                                    accentOnColor = AccentOnColor
                                )

                                // v2.0.160：美颜方案选择（GLSL / GPUPixel 双引擎）。
                                // GPUPixel 不可用（ABI 不支持 / init 失败）时弹提示并自动保持 GLSL。
                                val engineContext = LocalContext.current
                                BeautyEngineSection(
                                    accentColor = AccentColor,
                                    engineType = beautyEngineType,
                                    onEngineChange = { type ->
                                        // 具名参数 lambda 没有 return@ 标签，这里用 if/else 分支代替提前 return
                                        // 用户主动点击：force=true 跳过失败冷却，立即重试
                                        if (type == BEAUTY_ENGINE_GPUPIXEL && !GpuPixelBeauty.available && !GpuPixelBeauty.init(engineContext, force = true)) {
                                            Toast.makeText(
                                                engineContext,
                                                engineContext.getString(R.string.beauty_engine_unavailable),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            beautyEngineType = type
                                            keepUiAlight()
                                        }
                                    },
                                    gpSmooth = beautyGpSmooth,
                                    onGpSmoothChange = { beautyGpSmooth = it; keepUiAlight() },
                                    gpWhite = beautyGpWhite,
                                    onGpWhiteChange = { beautyGpWhite = it; keepUiAlight() },
                                    gpSharpen = beautyGpSharpen,
                                    onGpSharpenChange = { beautyGpSharpen = it; keepUiAlight() },
                                    gpSlim = beautyGpSlim,
                                    onGpSlimChange = { beautyGpSlim = it; keepUiAlight() },
                                    gpEyeZoom = beautyGpEyeZoom,
                                    onGpEyeZoomChange = { beautyGpEyeZoom = it; keepUiAlight() },
                                    vrFace = gpuPixelVrFaceBeauty,
                                    onVrFaceChange = { gpuPixelVrFaceBeauty = it; keepUiAlight() },
                                    halfRes = gpuPixelHalfResBeauty,
                                    onHalfResChange = { gpuPixelHalfResBeauty = it; keepUiAlight() }
                                )

                                // v2.0.160：GLSL 专属参数区（GPUPixel 模式下显示其自带参数区，避免混淆）
                                if (beautyEngineType == BEAUTY_ENGINE_GLSL) {

                                BeautyPresetRow(
                                    presetId = beautyPreset,
                                    onPresetChange = { applyBeautyPreset(it) },
                                    accentColor = AccentColor
                                )

                                // v119 拆分：美颜设置三大区块已抽到 BeautySettingsSections.kt
                                GeneralBeautySection(
                                    accentColor = AccentColor,
                                    beautyLevel = beautyLevel,
                                    onBeautyLevelChange = { beautyLevel = it; beautyPreset = BEAUTY_PRESET_CUSTOM; keepUiAlight() },
                                    textureDetail = beautyTextureDetail,
                                    onTextureDetailChange = { beautyTextureDetail = it; beautyPreset = BEAUTY_PRESET_CUSTOM; keepUiAlight() },
                                    brightnessLevel = brightnessLevel,
                                    onBrightnessLevelChange = { brightnessLevel = it; beautyPreset = BEAUTY_PRESET_CUSTOM; keepUiAlight() },
                                    contrastLevel = contrastLevel,
                                    onContrastLevelChange = { contrastLevel = it; beautyPreset = BEAUTY_PRESET_CUSTOM; keepUiAlight() },
                                    whiteningLevel = beautyWhitening,
                                    onWhiteningLevelChange = { beautyWhitening = it; beautyPreset = BEAUTY_PRESET_CUSTOM; keepUiAlight() }
                                )
                                } // v2.0.160：end if GLSL（通用美颜参数区，LUT 调色不受引擎影响）

                                LutFilterSection(
                                    accentColor = AccentColor,
                                    lutName = lutName,
                                    onLutNameChange = { lutName = it },
                                    lutMix = lutMix,
                                    onLutMixChange = { lutMix = it; currentGlSurfaceView?.renderer?.lutMix = it; keepUiAlight() },
                                    isLutLoading = isLutLoading,
                                    onLutLoadingChange = { isLutLoading = it },
                                    onApplyLutRgba = { currentGlSurfaceView?.renderer?.setLutTexture(it) },
                                    onPickCustomLut = { lutPickerLauncher.launch("application/octet-stream") },
                                    onUserInteraction = { keepUiAlight() }
                                )

                                if (beautyEngineType == BEAUTY_ENGINE_GLSL) {
                                PortraitRetouchSection(
                                    accentColor = AccentColor,
                                    enabled = is2DBeautyMode,
                                    params = listOf(
                                        PortraitParam(stringResource(R.string.beauty_face_slim), beautyFaceSlimming) { beautyFaceSlimming = it; beautyPreset = BEAUTY_PRESET_CUSTOM; keepUiAlight() },
                                        PortraitParam(stringResource(R.string.beauty_big_eyes), beautyBigEyes) { beautyBigEyes = it; beautyPreset = BEAUTY_PRESET_CUSTOM; keepUiAlight() },
                                        PortraitParam(stringResource(R.string.beauty_dark_circles), beautyDarkCircles) { beautyDarkCircles = it; beautyPreset = BEAUTY_PRESET_CUSTOM; keepUiAlight() },
                                        PortraitParam(stringResource(R.string.beauty_nose_slim), beautyNoseSlimming) { beautyNoseSlimming = it; beautyPreset = BEAUTY_PRESET_CUSTOM; keepUiAlight() },
                                        PortraitParam(stringResource(R.string.beauty_mouth), beautyMouth) { beautyMouth = it; beautyPreset = BEAUTY_PRESET_CUSTOM; keepUiAlight() },
                                        PortraitParam(stringResource(R.string.beauty_teeth), beautyTeethWhitening) { beautyTeethWhitening = it; beautyPreset = BEAUTY_PRESET_CUSTOM; keepUiAlight() },
                                        PortraitParam(stringResource(R.string.beauty_lipstick), beautyLipstick) { beautyLipstick = it; beautyPreset = BEAUTY_PRESET_CUSTOM; keepUiAlight() },
                                        PortraitParam(stringResource(R.string.beauty_blush), beautyBlush) { beautyBlush = it; beautyPreset = BEAUTY_PRESET_CUSTOM; keepUiAlight() },
                                        PortraitParam(stringResource(R.string.beauty_eyebrows), beautyEyebrows) { beautyEyebrows = it; beautyPreset = BEAUTY_PRESET_CUSTOM; keepUiAlight() },
                                        PortraitParam(stringResource(R.string.beauty_long_legs), beautyLongLegs) { beautyLongLegs = it; beautyPreset = BEAUTY_PRESET_CUSTOM; keepUiAlight() },
                                        PortraitParam(stringResource(R.string.beauty_small_head), beautySmallHead) { beautySmallHead = it; beautyPreset = BEAUTY_PRESET_CUSTOM; keepUiAlight() }
                                    )
                                )
                                } // v2.0.160：end if GLSL（人像精修区，瘦脸/大眼等由 GPUPixel 滑块替代）
                                }
                                /** 设置面板底部：记忆模式开关 + 确认并应用按钮 */
                                @Composable
                                fun SettingsFooter() {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { isMemoryModeEnabled = !isMemoryModeEnabled }
                                    .padding(vertical = 4.dp, horizontal = 8.dp)
                            ) {
                                Checkbox(
                                    checked = isMemoryModeEnabled,
                                    onCheckedChange = { isMemoryModeEnabled = it },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = AccentColor,
                                        uncheckedColor = Color.White.copy(alpha = 0.4f),
                                        checkmarkColor = AccentOnColor
                                    ),
                                    modifier = Modifier.size(32.dp).testTag("memory_mode_checkbox")
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Column {
                                    Text(
                                        text = stringResource(R.string.memory_mode),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = stringResource(R.string.memory_mode_desc),
                                        color = Color.White.copy(alpha = 0.5f),
                                        fontSize = 9.sp
                                    )
                                }
                            }

                            Button(
                                onClick = { isSettingsDialogOpen = false },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(stringResource(R.string.action_confirm_apply), color = AccentOnColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                                }

                                SettingsHeader()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            // Column 1: Lens / 3D Projection Style
                            Column(
                                modifier = Modifier
                                    .weight(1.3f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                    // ===== v90 二级菜单：可折叠分组（expandedSettings 定义在外层供快捷面板共用）=====
                                    fun toggleSettings(key: String) {
                                        expandedSettings = if (key in expandedSettings) expandedSettings - key else expandedSettings + key
                                    }

                                    @Composable
                                    fun SettingsGroup(
                                        title: String,
                                        key: String,
                                        content: @Composable () -> Unit
                                    ) {
                                        val expanded = key in expandedSettings
                                        val arrowRotation by animateFloatAsState(
                                            targetValue = if (expanded) 180f else 0f,
                                            label = "settingsArrow"
                                        )
                                        Column {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(
                                                        if (expanded) Color.White.copy(alpha = 0.07f)
                                                        else Color.White.copy(alpha = 0.03f)
                                                    )
                                                    .clickable { toggleSettings(key); keepUiAlight() }
                                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = title,
                                                    color = if (expanded) AccentColor else Color.White.copy(alpha = 0.85f),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Spacer(modifier = Modifier.weight(1f))
                                                Icon(
                                                    imageVector = Icons.Default.KeyboardArrowDown,
                                                    contentDescription = if (expanded) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand),
                                                    tint = Color.White.copy(alpha = 0.5f),
                                                    modifier = Modifier
                                                        .size(16.dp)
                                                        .graphicsLayer { rotationZ = arrowRotation }
                                                )
                                            }
                                            AnimatedVisibility(
                                                visible = expanded,
                                                enter = expandVertically() + fadeIn(),
                                                exit = shrinkVertically() + fadeOut()
                                            ) {
                                                Column(modifier = Modifier.padding(top = 8.dp)) { content() }
                                            }
                                        }
                                    }

                                    // 左列分组（二级菜单）：主题 → 投影 → 8K → 倍速 → 解码 → 画质增强 → 字幕 → 弹幕
                                    SettingsGroup(stringResource(R.string.group_title_ui_theme), "theme") { SettingsSection0() }
                                    SettingsGroup(stringResource(R.string.group_title_projection), "proj") { SettingsSection1() }
                                    SettingsGroup(stringResource(R.string.group_title_8k_hw), "8k") { SettingsSection8K() }
                                    SettingsGroup(stringResource(R.string.group_title_floating_ball), "ball") { SettingsSection3() }
                                    SettingsGroup(stringResource(R.string.group_title_decoder), "decode") { SettingsSection4() }
                                    // v2.0.206：MEMC 插帧 / FSR 超分
                                    SettingsGroup(stringResource(R.string.group_title_enhance), "enhance") { SettingsSectionEnhance() }
                                    SettingsGroup(stringResource(R.string.group_title_subtitle), "sub") { SettingsSectionSubtitle() }
                                    // v2.3.0：AI 弹幕独立成组（此前挂在「字幕」分组内，与字幕混在一起）
                                    SettingsGroup(stringResource(R.string.group_title_danmu), "danmu") { SettingsSectionDanmu() }
                                    // v106：关于与开源许可（合规署名入口）
                                    SettingsGroup(stringResource(R.string.group_title_about), "about") {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color.White.copy(alpha = 0.04f))
                                                .clickable { showLicensesDialog = true; keepUiAlight() }
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = stringResource(R.string.licenses_title),
                                                    color = Color.White.copy(alpha = 0.85f),
                                                    fontSize = 11.sp
                                                )
                                                Text(
                                                    text = stringResource(R.string.licenses_desc),
                                                    color = Color.White.copy(alpha = 0.45f),
                                                    fontSize = 9.sp
                                                )
                                            }
                                            Text(
                                                text = stringResource(R.string.action_view),
                                                color = AccentColor,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                            }
                            // Column 2: Advanced Portrait Beauty Controls (Scrollable)
                            Column(
                                modifier = Modifier
                                    .weight(1.3f)
                                    .fillMaxHeight()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                    // 右列：美颜设置（预设/对比/磨皮美白/2D 人像精修）
                                    SettingsColumn2()
                            }
                        }
                                SettingsFooter()
                    }
                }
            }
        }

        // v106：开源许可对话框（设置面板 → 关于与开源许可）
        if (showLicensesDialog) {
            OpenSourceLicensesDialog(onDismiss = { showLicensesDialog = false })
        }

        // LAN (SMB) browser dialog (8/2 功能)
        // Video information dialog (8/2 功能)
        videoInfoDialogText?.let { info ->
            AlertDialog(
                onDismissRequest = { videoInfoDialogText = null },
                containerColor = Color(0xFC18171C),
                title = { Text(stringResource(R.string.action_media_info), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        text = info,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = { videoInfoDialogText = null }) {
                        Text(stringResource(R.string.action_close), color = AccentColor)
                    }
                }
            )
        }

        // Audio / subtitle track selection dialog (8/2 功能)
        if (trackDialogOpen) {
            // v2.1.233：轨道列表仅 Exo 有；ijk 下得到空列表 → 对话框显示「无轨道」
            val groups = playerInstance?.exo?.currentTracks?.groups ?: emptyList()
            val audioGroup = groups.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
            val textGroup = groups.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
            AlertDialog(
                onDismissRequest = { trackDialogOpen = false },
                containerColor = Color(0xFC18171C),
                title = { Text(stringResource(R.string.track_dialog_title), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (audioGroup == null && textGroup == null) {
                            Text(stringResource(R.string.track_none), color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                        }

                        audioGroup?.let { g ->
                            Text(stringResource(R.string.track_audio), color = AccentColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            for (i in 0 until g.mediaTrackGroup.length) {
                                val f = g.mediaTrackGroup.getFormat(i)
                                val isSel = selectedAudioTrack == i
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSel) Color(0x33D0BCFF) else Color.White.copy(alpha = 0.05f))
                                        .clickable { selectMediaTrack(androidx.media3.common.C.TRACK_TYPE_AUDIO, i) }
                                        .padding(horizontal = 8.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isSel) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (isSel) AccentColor else Color.White.copy(alpha = 0.4f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "音轨 ${i + 1}${f.language?.let { " ($it)" } ?: ""}",
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        if (textGroup != null) {
                            Text(stringResource(R.string.track_subtitle_embedded), color = AccentColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            // Disable subtitles option
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selectedTextTrack == -1) Color(0x33D0BCFF) else Color.White.copy(alpha = 0.05f))
                                    .clickable { selectMediaTrack(androidx.media3.common.C.TRACK_TYPE_TEXT, -1) }
                                    .padding(horizontal = 8.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (selectedTextTrack == -1) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (selectedTextTrack == -1) AccentColor else Color.White.copy(alpha = 0.4f),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.track_subtitle_off), color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp)
                            }
                            for (i in 0 until textGroup.mediaTrackGroup.length) {
                                val f = textGroup.mediaTrackGroup.getFormat(i)
                                val isSel = selectedTextTrack == i
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSel) Color(0x33D0BCFF) else Color.White.copy(alpha = 0.05f))
                                        .clickable { selectMediaTrack(androidx.media3.common.C.TRACK_TYPE_TEXT, i) }
                                        .padding(horizontal = 8.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isSel) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (isSel) AccentColor else Color.White.copy(alpha = 0.4f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "字幕 ${i + 1}${f.language?.let { " ($it)" } ?: ""}",
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Text(
                            text = stringResource(R.string.track_subtitle_note),
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 9.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { trackDialogOpen = false }) {
                        Text(stringResource(R.string.action_close), color = AccentColor)
                    }
                }
            )
        }

        // v2.1.241：文件来源选择框。
        // 两条入口必须都留着：相册选择器体验好（有缩略图、可按相册/时间浏览），
        // 但它按 video/* 过滤，选不到 .wmv / .iso；文档选择器能选任意文件，
        // 但界面是 SAF 的文件树，找图片反而绕。所以由用户按场景自己选。
        if (pickerSourceDialogOpen) {
            AlertDialog(
                onDismissRequest = { pickerSourceDialogOpen = false },
                containerColor = Color(0xFC18171C),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = null,
                            tint = AccentColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.picker_source_title), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    // ⚠️ v2.1.248：**优化视频选择 UI**。
                    //    此前是两个等权重的纯文字按钮（无图标、无副标题）+ 一行小字提示，
                    //    「相册」和「任意文件」的差别只能靠用户读完那行提示才明白，
                    //    而这两条入口的实际差异（能不能看到缩略图、能不能选到 WMV/ISO）
                    //    正是最需要一眼看出的信息。
                    //    现在改为「图标 + 主标题 + 副标题」两行式卡片，并按「日常用 / 兜底用」
                    //    做视觉分层：相册卡为强调色描边（推荐），任意文件卡为中性面。
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

                        // ── 入口 1：相册（有缩略图，日常主路径）──
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(AccentColor.copy(alpha = 0.12f))
                                .border(1.dp, AccentColor.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                .clickable {
                                    pickerSourceDialogOpen = false
                                    filePickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                    )
                                }
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = null,
                                tint = AccentColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.picker_source_gallery),
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    stringResource(R.string.picker_source_gallery_desc),
                                    color = Color.White.copy(alpha = 0.55f),
                                    fontSize = 10.sp
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = AccentColor.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // ── 入口 2：任意文件（兜底，可覆盖 WMV / ISO / AVI）──
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                                .clickable {
                                    pickerSourceDialogOpen = false
                                    // ⚠️ 必须先传 MIME 数组再 launch；`OpenDocument` 要求 Array<String>。
                                    //    用 "*/*" 而不是 "video/*"：WMV 的 MIME 常不是 video/*，
                                    //    而 ISO 完全不是 —— 用 video/* 等于把这次新增的意义抹掉。
                                    documentPickerLauncher?.launch(arrayOf("*/*"))
                                }
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.picker_source_any_file),
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    stringResource(R.string.picker_source_any_file_desc),
                                    color = Color.White.copy(alpha = 0.55f),
                                    fontSize = 10.sp
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.45f),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // ── 支持的格式一览（让用户事先知道能放什么）──
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.04f))
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(
                                stringResource(R.string.picker_source_formats_title),
                                color = AccentColor.copy(alpha = 0.9f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.picker_source_formats_list),
                                color = Color.White.copy(alpha = 0.55f),
                                fontSize = 9.5.sp,
                                lineHeight = 14.sp
                            )
                        }

                        Text(
                            stringResource(R.string.picker_source_hint),
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 10.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { pickerSourceDialogOpen = false }) {
                        Text(stringResource(R.string.action_close), color = Color.White.copy(alpha = 0.7f))
                    }
                }
            )
        }

        if (smbDialogOpen) {
            AlertDialog(
                onDismissRequest = { smbDialogOpen = false },
                containerColor = Color(0xFC18171C),
                title = { Text(stringResource(R.string.smb_title), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = smbHost,
                            onValueChange = { smbHost = it },
                            label = { Text(stringResource(R.string.smb_server_addr), fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentColor,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedTextField(
                                value = smbUser,
                                onValueChange = { smbUser = it },
                                label = { Text(stringResource(R.string.smb_username), fontSize = 9.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentColor,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                            OutlinedTextField(
                                value = smbPass,
                                onValueChange = { smbPass = it },
                                label = { Text(stringResource(R.string.smb_password), fontSize = 9.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AccentColor,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                            Button(
                                onClick = { connectSmb() },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(stringResource(R.string.smb_connect), fontSize = 11.sp)
                            }
                        }

                        if (smbPath.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = smbPath,
                                    color = AccentColor,
                                    fontSize = 9.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    val parent = smbPath.trimEnd('/')
                                    val idx = parent.lastIndexOf('/')
                                    if (idx > 6) {
                                        browseSmb(parent.substring(0, idx + 1))
                                    }
                                }) {
                                    Text(stringResource(R.string.smb_parent), fontSize = 10.sp, color = AccentColor)
                                }
                            }
                        }

                        if (smbError.isNotEmpty()) {
                            Text(smbError, color = Color(0xFFFF5252), fontSize = 9.sp)
                        }

                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .heightIn(max = 260.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(smbEntries.size) { i ->
                                val entry = smbEntries[i]
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color.White.copy(alpha = 0.05f))
                                        .clickable {
                                            if (entry.isDirectory) {
                                                val base = smbPath.trimEnd('/')
                                                val next = "$base/${entry.name}/"
                                                browseSmb(encodeSmb(next))
                                            } else if (MediaFormats.isSupportedVideoExtension(
                                                    MediaFormats.extensionOfName(entry.name)
                                                )
                                            ) {
                                                // v2.1.241：扩展名判定收敛到 MediaFormats（原先 5 个
                                                // endsWith 手写白名单，新增一种容器要再来改一遍）。
                                                playSmbFile(entry)
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = if (entry.isDirectory) Color(0xFFFFD700) else AccentColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = entry.name,
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { smbDialogOpen = false }) {
                        Text(stringResource(R.string.action_close), color = AccentColor)
                    }
                }
            )
        }

        // 12. Floating Speed Ball Overlay & Long-Press Fast Forward HUD
        if (isFloatingBallEnabled) {
            val density = LocalDensity.current
            val ballDp = 54.dp
            val ballSizePx = with(density) { ballDp.toPx() }
            val maxXPx = constraints.maxWidth.toFloat() - ballSizePx
            val maxYPx = constraints.maxHeight.toFloat() - ballSizePx

            LaunchedEffect(constraints.maxWidth, constraints.maxHeight) {
                if (!isBallPositionInitialized && maxXPx > 0f && maxYPx > 0f) {
                    // v2.1.231：优先恢复到上次拖动到的位置（存档是 0~1 比例 × 当次范围），
                    // 没有存档才回落到「贴右侧边缘、纵向居中」的默认值
                    val sx = if (isMemoryModeEnabled) FloatingBallPositions.loadX(prefs, FloatingBallPositions.SPEED) else Float.NaN
                    val sy = if (isMemoryModeEnabled) FloatingBallPositions.loadY(prefs, FloatingBallPositions.SPEED) else Float.NaN
                    if (!sx.isNaN() && !sy.isNaN()) {
                        ballOffsetX = (sx * maxXPx).coerceIn(0f, maxXPx)
                        ballOffsetY = (sy * maxYPx).coerceIn(0f, maxYPx)
                    } else {
                        ballOffsetX = maxXPx - with(density) { 20.dp.toPx() }
                        ballOffsetY = maxYPx / 2f
                    }
                    isBallPositionInitialized = true
                }
            }

            val speedText = if (floatingBallSpeed == floatingBallSpeed.toInt().toFloat()) {
                "${floatingBallSpeed.toInt()}X"
            } else {
                "${floatingBallSpeed}X"
            }

            // v2.0.172：双击循环切换加速档位的提示文案（下一档倍速；@Composable 调用须在语句位置取好）
            val nextSpeedVal = nextBallSpeed(floatingBallSpeed)
            val nextSpeedLabel = if (nextSpeedVal == nextSpeedVal.toInt().toFloat()) {
                "${nextSpeedVal.toInt()}X"
            } else {
                "${nextSpeedVal}X"
            }
            val ballSpeedSwitchedLabel = stringResource(R.string.ball_speed_switched, nextSpeedLabel)

            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            x = ballOffsetX.coerceIn(0f, maxXPx.coerceAtLeast(0f)).roundToInt(),
                            y = ballOffsetY.coerceIn(0f, maxYPx.coerceAtLeast(0f)).roundToInt()
                        )
                    }
                    .size(ballDp)
                    .shadow(
                        elevation = if (isFloatingBallPressed) 12.dp else 6.dp,
                        shape = CircleShape,
                        spotColor = if (isFloatingBallPressed) AccentColor else Color.Black
                    )
                    .background(
                        brush = if (isLiquidGlass) {
                            // 玻璃主题：半透明白底，配合下方 drawBackdrop 呈现液态玻璃
                            Brush.radialGradient(
                                colors = listOf(Color(0x66FFFFFF), Color(0x33FFFFFF))
                            )
                        } else if (isFloatingBallPressed) {
                            Brush.radialGradient(
                                colors = listOf(AccentColor, Color(0xFF9A82DB))
                            )
                        } else {
                            Brush.radialGradient(
                                colors = listOf(Color(0xEE2A2733), Color(0xDD18171C))
                            )
                        },
                        shape = CircleShape
                    )
                    .then(
                        // v88：玻璃主题（glassMode=1 且 Android 12+）下悬浮球使用液态玻璃材质
                        if (isLiquidGlass) Modifier.glassBall(
                            backdrop = liquidBackdrop,
                            style = glassStyle,
                            // v2.1.218：不传 blurRadius —— 显式传值会**覆盖风格的默认值**，
                            // 导致磨砂虽配了 40dp 模糊、实际仍用旧值，与液态玻璃看不出差别。
                            // null = 由 glassStyle 决定（面板 Liquid 22/Frosted 40，球体 Liquid 11/Frosted 20）。
                            blurRadius = null,
                            onDrawSurface = { drawCircle(ThemePanelBgColor.copy(alpha = 0.45f)) }
                        ) else Modifier
                    )
                    .border(
                        width = if (isFloatingBallPressed) 2.dp else 1.5.dp,
                        brush = if (isFloatingBallPressed) {
                            SolidColor(Color.White)
                        } else {
                            Brush.linearGradient(
                                colors = listOf(Color(0x99D0BCFF), Color(0x33FFFFFF))
                            )
                        },
                        shape = CircleShape
                    )
                    .pointerInput(floatingBallSpeed, basePlaybackSpeed) {
                        val touchSlop = viewConfiguration.touchSlop
                        // v2.0.172：双击在 1.5X / 2.0X / 3.0X 间循环切换（280ms 窗口，与快进/后退球一致）。
                        // lastTapUpTime 持有在 pointerInput 块内：key（含 floatingBallSpeed）变化时块重启、归零，
                        // 单击无独立动作（按住即加速），故双击无需延迟判定。
                        var lastTapUpTime = 0L
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // v103：点按/拖动悬浮球不点亮其他 UI（不再调用 keepUiAlight）
                            isFloatingBallPressed = true
                            showSpeedHud = true

                            var pointer = down.id
                            var dragDetected = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointer }
                                if (change == null || !change.pressed) {
                                    isFloatingBallPressed = false
                                    showSpeedHud = false
                                    break
                                }
                                val dragAmount = change.positionChange()
                                // 位移超过 touchSlop 才算拖动（微抖不算，否则双击永远无法成立）
                                if (!dragDetected && dragAmount.getDistance() > touchSlop) {
                                    dragDetected = true
                                }
                                if (dragDetected && dragAmount != Offset.Zero) {
                                    ballOffsetX = (ballOffsetX + dragAmount.x).coerceIn(0f, maxXPx.coerceAtLeast(0f))
                                    ballOffsetY = (ballOffsetY + dragAmount.y).coerceIn(0f, maxYPx.coerceAtLeast(0f))
                                    change.consume()
                                }
                            }

                            // 未拖动的快速连击 = 双击 → 循环切换加速档位（按住加速照常生效）
                            if (!dragDetected) {
                                val now = System.currentTimeMillis()
                                if (now - lastTapUpTime <= DOUBLE_TAP_WINDOW_MS) {
                                    lastTapUpTime = 0L
                                    floatingBallSpeed = nextBallSpeed(floatingBallSpeed)
                                    seekHudText = ballSpeedSwitchedLabel
                                } else {
                                    lastTapUpTime = now
                                }
                            } else {
                                lastTapUpTime = 0L
                                // v2.1.231：拖动结束 → 把落点按「占可移动范围的比例」存盘
                                if (isMemoryModeEnabled && maxXPx > 0f && maxYPx > 0f) {
                                    FloatingBallPositions.save(
                                        prefs,
                                        FloatingBallPositions.SPEED,
                                        ballOffsetX / maxXPx,
                                        ballOffsetY / maxYPx
                                    )
                                }
                            }
                        }
                    }
                    .testTag("floating_speed_ball"),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FastForward,
                        contentDescription = stringResource(R.string.speed_control),
                        tint = if (isFloatingBallPressed) AccentOnColor else AccentColor,
                        modifier = Modifier.size(if (isFloatingBallPressed) 20.dp else 16.dp)
                    )
                    Text(
                        text = speedText,
                        color = if (isFloatingBallPressed) AccentOnColor else Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // v103：速度提示条只显示 1 秒（即使继续长按/拖动也自动隐藏，不再常驻）
            LaunchedEffect(showSpeedHud) {
                if (showSpeedHud) {
                    delay(1000L)
                    showSpeedHud = false
                }
            }

            // Fast Forward Speed HUD Toast Overlay
            AnimatedVisibility(
                visible = showSpeedHud,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 28.dp)
                    .testTag("speed_fast_forward_hud")
            ) {
                Surface(
                    color = Color(0xEE18171C),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, AccentColor.copy(alpha = 0.6f)),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FastForward,
                            contentDescription = null,
                            tint = AccentColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.fast_forwarding, speedText),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 13. v2.0.165：快进 / 后退悬浮球 + 提示条
        // 层级说明：与加速球同在**最外层 Box**（绘制顺序在播控组件之后）→ 恒位于播控组件之上，
        // 拖到播控栏区域时小球仍可点击/拖动，不会被播控栏抢走事件。
        val ballPx = with(LocalDensity.current) { 54.dp.toPx() }
        val seekMaxX = constraints.maxWidth.toFloat() - ballPx
        val seekMaxY = constraints.maxHeight.toFloat() - ballPx
        // 提示文案在语句位置取好（@Composable 调用不能塞进普通 lambda）
        val fwdHudLabel = stringResource(R.string.seek_forward_hud, seekForwardStep)
        val bwdHudLabel = stringResource(R.string.seek_backward_hud, seekBackwardStep)
        val fwdStepSwitched = stringResource(R.string.seek_step_switched, nextSeekStep(seekForwardStep))
        val bwdStepSwitched = stringResource(R.string.seek_step_switched, nextSeekStep(seekBackwardStep))

        // 13b. v2.1.208：**时间标记球**（左侧边缘）
        // 与右侧的快进/后退球共用同一套手势（FloatingBallGesture），但语义不同：
        // 单击 = 跳回标记点，双击 = 把标记点设为当前播放位置，球面实时显示「当前−标记」差值。
        // 横向取 initialXRatio = 0 → 贴左边缘，与右侧两个球天然错开，不抢位置也不误触。
        // 差值实时性：currentPositionMs 是上游播放器循环写入的 state，写一次就重组一次
        //（见 MarkerFloatingBall 的注释），无需额外定时器。
        if (isMarkerBallEnabled) {
            MarkerFloatingBall(
                currentPositionMs = currentPositionMs,
                maxX = seekMaxX,
                maxY = seekMaxY,
                onSeekTo = { ms -> playerInstance?.seekTo(ms) },
                accentColor = AccentColor,
                accentOnColor = AccentOnColor,
                onFeedback = { seekHudText = it },
                initialXRatio = 0f,
                initialYRatio = 0.5f,
                isLiquidGlass = isLiquidGlass,
                // v2.1.231：与快进/后退球一致的玻璃材质（此前漏传，玻璃主题下标记球没有 backdrop）
                glassModifier = if (isLiquidGlass) Modifier.glassBall(
                    backdrop = liquidBackdrop,
                    style = glassStyle,
                    blurRadius = null,
                    onDrawSurface = { drawCircle(ThemePanelBgColor.copy(alpha = 0.45f)) }
                ) else Modifier,
                // v2.1.231：记忆上次拖动到的位置
                prefs = prefs,
                rememberPosition = isMemoryModeEnabled
            )
        }

        if (isSeekForwardBallEnabled) {
            SeekFloatingBall(
                forward = true,
                stepSeconds = seekForwardStep,
                maxX = seekMaxX,
                maxY = seekMaxY,
                accentColor = AccentColor,
                accentOnColor = AccentOnColor,
                initialYRatio = 0.28f,
                onStepCycle = {
                    seekForwardStep = nextSeekStep(seekForwardStep)
                    seekHudText = fwdStepSwitched
                },
                onTrigger = { step ->
                    val p = playerInstance
                    if (p != null) {
                        val dur = if (p.duration > 0) p.duration else Long.MAX_VALUE
                        p.seekTo((p.currentPosition + step * 1000L).coerceIn(0L, dur))
                    }
                    seekHudText = fwdHudLabel
                },
                onFeedback = { seekHudText = it },
                isLiquidGlass = isLiquidGlass,
                glassModifier = if (isLiquidGlass) Modifier.glassBall(
                    backdrop = liquidBackdrop,
                    style = glassStyle,
                    // v2.1.218：不传 blurRadius —— 显式传值会**覆盖风格的默认值**，
                    // 导致磨砂虽配了 40dp 模糊、实际仍用旧值，与液态玻璃看不出差别。
                    // null = 由 glassStyle 决定（面板 Liquid 22/Frosted 32）。
                    blurRadius = null,
                    onDrawSurface = { drawCircle(ThemePanelBgColor.copy(alpha = 0.45f)) }
                ) else Modifier,
                // v2.1.231：记忆上次拖动到的位置
                prefs = prefs,
                ballId = FloatingBallPositions.SEEK_FWD,
                rememberPosition = isMemoryModeEnabled
            )
        }

        if (isSeekBackwardBallEnabled) {
            SeekFloatingBall(
                forward = false,
                stepSeconds = seekBackwardStep,
                maxX = seekMaxX,
                maxY = seekMaxY,
                accentColor = AccentColor,
                accentOnColor = AccentOnColor,
                initialYRatio = 0.68f,
                onStepCycle = {
                    seekBackwardStep = nextSeekStep(seekBackwardStep)
                    seekHudText = bwdStepSwitched
                },
                onTrigger = { step ->
                    val p = playerInstance
                    if (p != null) {
                        p.seekTo((p.currentPosition - step * 1000L).coerceAtLeast(0L))
                    }
                    seekHudText = bwdHudLabel
                },
                onFeedback = { seekHudText = it },
                isLiquidGlass = isLiquidGlass,
                glassModifier = if (isLiquidGlass) Modifier.glassBall(
                    backdrop = liquidBackdrop,
                    style = glassStyle,
                    // v2.1.218：不传 blurRadius —— 显式传值会**覆盖风格的默认值**，
                    // 导致磨砂虽配了 40dp 模糊、实际仍用旧值，与液态玻璃看不出差别。
                    // null = 由 glassStyle 决定（面板 Liquid 22/Frosted 32）。
                    blurRadius = null,
                    onDrawSurface = { drawCircle(ThemePanelBgColor.copy(alpha = 0.45f)) }
                ) else Modifier,
                // v2.1.231：记忆上次拖动到的位置
                prefs = prefs,
                ballId = FloatingBallPositions.SEEK_BWD,
                rememberPosition = isMemoryModeEnabled
            )
        }

        // 快进 / 后退 / 步长切换提示条（复用速度提示条的样式，1.2 秒后自动隐藏）
        LaunchedEffect(seekHudText) {
            if (seekHudText != null) {
                delay(1200L)
                seekHudText = null
            }
        }
        AnimatedVisibility(
            visible = seekHudText != null,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 28.dp)
                .testTag("seek_ball_hud")
        ) {
            Surface(
                color = Color(0xEE18171C),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, AccentColor.copy(alpha = 0.6f)),
                shadowElevation = 8.dp
            ) {
                Text(
                    text = seekHudText ?: "",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

    }
}

// ============================================================================
// 华为 VR Glass（VR Engine）接入支持
// ----------------------------------------------------------------------------
// 设计见 HUAWEI_VR_ENGINE_PLAN_2026-09-26.md 第 6 节。本段只做「用户可见的一层」：
// 运行时探测 + 进入路由 + 降级提示。真正的 OpenXR 会话层（lib_loader.so /
// HuaweiVrActivity / GLES3.2 上下文）属于 P0~P2，需要华为真机才能实施与验证。
// ============================================================================
object HuaweiVrRuntime {
    /** 华为 VR SDK Service（插眼镜后自动安装的运行时服务） */
    const val PKG_SDK_SERVICE = "com.huawei.hvrsdkserverapp"

    /** 华为 VR 设备管理/手柄服务 */
    const val PKG_VRHANDLE = "com.huawei.vrhandle"

    /** 官方「2D 应用 → VR」提示页 Action（由 hvrprompt.aar 的 HVRModeActivity 响应） */
    const val ACTION_VR_PROMPT = "com.huawei.android.vr.PROMPT"

    /** 眼镜推荐 swapchain 尺寸（每眼），仅作注释用途 */
    const val RECOMMENDED_EYE_SIZE = 1552

    /** 眼镜刷新率 */
    const val DISPLAY_REFRESH_RATE_HZ = 70
}

/**
 * 探测华为 VR 运行时是否可用。
 *
 * 注意：`PackageManager.getPackageInfo` 在包不存在时抛 `NameNotFoundException`，
 * 某些定制 ROM 还可能抛其它 SecurityException，因此统一按「查得到即可用」处理，
 * 任何异常都视为不可用（安全降级）。
 */
fun isHuaweiVrRuntimeAvailable(context: Context): Boolean {
    val pm = context.packageManager ?: return false
    for (pkg in listOf(HuaweiVrRuntime.PKG_SDK_SERVICE, HuaweiVrRuntime.PKG_VRHANDLE)) {
        try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
            return true
        } catch (_: Exception) {
            // 该包不存在，继续探测下一个
        }
    }
    return false
}

/**
 * 构建并「预备」进入华为 VR 的 Intent。
 *
 * 两条路一起给出，按可靠性排序：
 *
 * ① **显式指向自身的 `HuaweiVrActivity`**（主路径，本函数实际使用）
 *    该 Activity 在 Manifest 中声明了 `action=com.huawei.android.vr.PROMPT` 的
 *    `<intent-filter>`，同时又是显式组件。显式 `setClass` 启动最稳，
 *    不依赖 `hvrprompt.aar` 是否已集成，也不受各 ROM 对隐式 Intent 的限制。
 *
 * ② `action=PROMPT` + `setPackage(自身包名)`（备用，见 [HuaweiVrRuntime.ACTION_VR_PROMPT]）
 *    集成 `hvrprompt.aar` 后由 `com.huawei.vrlab.HVRModeActivity` 响应，
 *    插眼镜可跳过 VrLauncher。P1 再启用。
 *
 * ⚠️ 本函数有副作用：会写入 `HuaweiVrActivity.onBeforeKill` 与
 * `HuaweiVrActivity.onVideoSurfaceNeeded`（静态回调）。
 * 这是刻意的——Activity 退出时 `killProcess`，设置必须在同一进程内先落盘；
 * 视频源也必须跨 Activity 边界交给华为侧的渲染器。
 * 回调用可空静态引用 + `@Volatile`，进程重启后自动失效，不会泄漏 Activity/Context。
 *
 * @param renderScale 渲染分辨率倍率（0.5f~1.0f），转成百分比传给 Activity
 * @param onBeforeKill 退出前落盘动作；传 null 则不覆盖既有回调
 * @param onVideoSurfaceNeeded P1 视频源接线；见 [HuaweiVrActivity.onVideoSurfaceNeeded]
 */
fun buildHuaweiVrPromptIntent(
    context: Context,
    renderScale: Float = 1.0f,
    onBeforeKill: (() -> Unit)? = null,
    onVideoSurfaceNeeded: ((SurfaceTexture) -> Unit)? = null
): Intent {
    if (onBeforeKill != null) {
        HuaweiVrActivity.onBeforeKill = onBeforeKill
    }
    // ⚠️ 视频接线回调也必须每次覆盖（哪怕是 null）：上一次会话的 lambda 可能捕获了
    //    已失效的 player/view 引用，留着会在华为侧触发时静默失败或崩。
    HuaweiVrActivity.onVideoSurfaceNeeded = onVideoSurfaceNeeded

    val percent = (renderScale.coerceIn(0.5f, 1.0f) * 100f).roundToInt()
    return Intent(context, HuaweiVrActivity::class.java).apply {
        action = HuaweiVrRuntime.ACTION_VR_PROMPT
        putExtra(HuaweiVrActivity.EXTRA_RENDER_SCALE, percent)
        putExtra(HuaweiVrActivity.EXTRA_EXTERNAL_RENDERER, true)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

// ===================== v2.0.180：拖动预览缩略图参数与工具 =====================

/** 拖动预览抓帧节流间隔（ms）：拖动期间最快每 120ms 抓一次，避免每帧都解码。 */
private const val SEEK_THUMB_THROTTLE_MS = 120L

/**
 * 拖动预览时间量化步长（ms）：把请求时间对齐到该步长后再抓帧 / 查缓存。
 * 1 秒 ≈ 关键帧量级，配合 OPTION_CLOSEST_SYNC 能显著提高缓存命中率（同一秒内拖动不再重复解码）。
 */
private const val SEEK_THUMB_QUANTUM_MS = 1000L

/** 拖动预览目标尺寸（匹配 UI 的 160dp×90dp，在 xxhdpi 上足够清晰）。 */
private const val SEEK_THUMB_W = 320
private const val SEEK_THUMB_H = 180

// ===================== v2.3.1（P2+P4）：AI 弹幕编排参数 =====================

/**
 * 首次取帧前的等待时间（ms）。
 * 刚进播放页时画面可能还是黑帧/封面图，立刻抓一帧会让模型看到无意义内容（浪费 token）。
 */
private const val FIRST_CAPTURE_DELAY_MS = 8_000L

/** 请求取帧后，轮询结果的最大尝试次数（GL 回读发生在下一帧） */
private const val FRAME_POLL_MAX_TRIES = 20

/** 每次轮询的间隔（ms）—— 20 × 100ms = 最多等 2s */
private const val FRAME_POLL_INTERVAL_MS = 100L

/**
 * 弹幕 seek 检测的轮询间隔（ms）。
 *
 * 只用于比较「播放位置是否突跳 > 2s」，不做任何重活，故可以放慢；
 * 200ms 足以让跳转后的陈旧弹幕在肉眼反应前被清掉。
 */
private const val DANMU_SEEK_POLL_MS = 200L

/**
 * 把抓到的原始帧缩放到拖动预览尺寸（320×180，16:9）。
 *
 * 源不是 16:9 时先**等比缩放**再**居中裁切**，避免直接拉伸导致人脸变形
 * （拖动预览正是用来看清画面的，变形会误导用户）。
 * 返回的 Bitmap 与入参不是同一对象时，调用方负责回收入参。
 */
private fun scaleSeekThumb(src: Bitmap): Bitmap {
    val srcW = src.width
    val srcH = src.height
    if (srcW <= 0 || srcH <= 0) return src

    val targetRatio = SEEK_THUMB_W.toFloat() / SEEK_THUMB_H
    val srcRatio = srcW.toFloat() / srcH

    // 先等比放大/缩小，使短边覆盖目标尺寸
    val (scaledW, scaledH) = if (srcRatio > targetRatio) {
        // 源更宽 → 以高为准
        (srcW.toFloat() * SEEK_THUMB_H / srcH).toInt().coerceAtLeast(SEEK_THUMB_W) to SEEK_THUMB_H
    } else {
        SEEK_THUMB_W to (srcH.toFloat() * SEEK_THUMB_W / srcW).toInt().coerceAtLeast(SEEK_THUMB_H)
    }

    val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
    // 居中裁切到目标尺寸
    val x = ((scaledW - SEEK_THUMB_W) / 2).coerceAtLeast(0)
    val y = ((scaledH - SEEK_THUMB_H) / 2).coerceAtLeast(0)
    val out = if (scaledW == SEEK_THUMB_W && scaledH == SEEK_THUMB_H) {
        scaled
    } else {
        Bitmap.createBitmap(scaled, x, y, SEEK_THUMB_W, SEEK_THUMB_H)
    }
    if (out !== scaled && scaled !== src) {
        scaled.recycle()
    }
    return out
}


