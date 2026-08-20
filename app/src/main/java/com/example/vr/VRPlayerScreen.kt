package com.example.vr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.net.Uri
import android.util.Log
import android.os.Build
import android.view.Surface
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
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
    var selectedMediaItem by remember { mutableStateOf(DemoMediaProvider.demoMediaList[0]) }
    var projectionMode by remember {
        mutableStateOf(
            if (isMemoryModeEnabled) {
                val modeId = prefs.getInt("projection_mode", ProjectionMode.VR_180.id)
                ProjectionMode.values().firstOrNull { it.id == modeId } ?: ProjectionMode.VR_180
            } else {
                ProjectionMode.VR_180
            }
        )
    }
    // 智能投影自动识别（8/1 功能）：False until the user explicitly picks a projection mode.
    // Smart auto-detection (2D video -> STANDARD, 2:1 panorama -> VR_360) only applies
    // while this is false, so a manual choice is never overridden.
    var projectionModeUserAdjusted by remember { mutableStateOf(false) }
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
    var beautyPreset by remember { mutableStateOf("自定义") } // 预设：自然/淡妆/浓妆/自定义
    var beautyCompareEnabled by remember { mutableStateOf(false) } // 对比原图开关
    var beautyLevel by remember {
        mutableFloatStateOf(
            if (isMemoryModeEnabled) prefs.getFloat("beauty_level", 0.65f) else 0.65f
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
                val id = prefs.getInt("decoder_engine_id", 0)
                DecoderEngine.values().find { it.id == id } ?: DecoderEngine.EXO
            } else DecoderEngine.EXO
        )
    }

    // Subtitle System States
    var isSubtitleEnabled by remember {
        mutableStateOf(if (isMemoryModeEnabled) prefs.getBoolean("is_subtitle_enabled", true) else true)
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
    // v86：移除实时 AI 字幕，保留模型管理（供后台批处理转写使用）
    val asrManager = remember {
        RealtimeAsrManager(context).apply {
            if (isMemoryModeEnabled) {
                config = VoskAsrConfig(
                    language = VoskLanguage.entries.find { it.id == prefs.getInt("asr_language_id", 0) }
                        ?: VoskLanguage.ZH,
                    modelSize = VoskModelSize.entries.find { it.id == prefs.getInt("asr_model_size", 0) }
                        ?: VoskModelSize.SMALL
                )
            }
        }
    }
    val subtitleTranslator = remember { SubtitleTranslator(context) }
    // 后台批处理转写（v85）：提取视频音频生成 SRT
    val batchTranscriber = remember { AsrBatchTranscriber(context) }
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
                    val content = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                    val cues = SubtitleParser.parseSrtOrVtt(content)
                    withContext(Dispatchers.Main) {
                        loadedSubtitleCues = cues
                        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "外部字幕.srt"
                        loadedSubtitleFileName = name
                        Toast.makeText(context, "成功加载 ${cues.size} 条字幕！", Toast.LENGTH_SHORT).show()
                        if (subtitleTranslator.config.isEnabled) {
                            subtitleTranslator.translateCuesBatch(cues)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VRPlayerScreen", "Error reading subtitle file", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "字幕加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // 后台生成全片 SRT 字幕（v85）：提取视频音频 → Vosk 离线识别 → SRT 文件
    fun startBatchTranscribe() {
        val uriStr = selectedMediaItem.uri ?: return
        if (isBatchTranscribing) return
        val modelOption = asrManager.config.modelOption
        scope.launch {
            isBatchTranscribing = true
            batchTranscribeProgress = 0f
            batchTranscribeStatus = "准备识别模型..."
            val file = batchTranscriber.transcribeToSrt(
                mediaUri = Uri.parse(uriStr),
                videoTitle = selectedMediaItem.title,
                modelOption = modelOption,
                modelProvider = { opt -> asrManager.ensureModelForBatch(opt) },
                onStatus = { batchTranscribeStatus = it },
                onProgress = { batchTranscribeProgress = it }
            )
            isBatchTranscribing = false
            if (file != null) {
                // v89：生成后自动加载并显示字幕
                val content = withContext(Dispatchers.IO) { file.readText() }
                val cues = SubtitleParser.parseSrtOrVtt(content)
                loadedSubtitleCues = cues
                loadedSubtitleFileName = file.name
                isSubtitleEnabled = true
                Toast.makeText(context, "字幕已生成并加载：${file.name}（${cues.size} 句）", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "字幕生成失败：${batchTranscriber.statusMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }



    var isFloatingBallPressed by remember { mutableStateOf(false) }
    // v103：悬浮球按下时的速度提示条（仅显示 1 秒后自动隐藏）
    var showSpeedHud by remember { mutableStateOf(false) }
    // v104：LUT 视频滤镜状态
    var lutName by remember { mutableStateOf("无滤镜") }       // 当前滤镜名
    var lutMix by remember { mutableFloatStateOf(0.8f) }       // 滤镜强度 0~1
    var isLutLoading by remember { mutableStateOf(false) }     // 解析中
    // v91：主界面字幕快捷面板
    var isSubtitleQuickPanelOpen by remember { mutableStateOf(false) }
    // v91：设置面板左列折叠分组展开状态（需在外层供快捷面板引用）
    var expandedSettings by remember { mutableStateOf(setOf("theme")) }
    var ballOffsetX by remember { mutableFloatStateOf(0f) }
    var ballOffsetY by remember { mutableFloatStateOf(0f) }
    var isBallPositionInitialized by remember { mutableStateOf(false) }

    // Dynamic Reactive Settings Memory Auto-Persistence Task
    LaunchedEffect(
        isMemoryModeEnabled,
        projectionMode,
        stereoMode,
        beautyLevel,
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
        fovDeg,
        isVideoMirrored,
        domeHalfSelect,
        warpMode,
        isAudioMirrored,
        videoCurvature,
        maxResolution,
        isFloatingBallEnabled,
        floatingBallSpeed,
        basePlaybackSpeed,
        maxFps,
        isSoftwareDecoding,
        decoderEngine,
        isSubtitleEnabled,
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
        asrManager.config
    ) {
        prefs.edit().apply {
            putBoolean("is_memory_mode_enabled", isMemoryModeEnabled)
            if (isMemoryModeEnabled) {
                putInt("projection_mode", projectionMode.id)
                putInt("stereo_mode", stereoMode.id)
                putFloat("beauty_level", beautyLevel)
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
                putBoolean("is_split_screen_vr", isSplitScreenVR)
                putBoolean("is_gyro_enabled", isGyroEnabled)
                putFloat("fov_deg", fovDeg)
                putBoolean("is_video_mirrored", isVideoMirrored)
                putInt("dome_half_select", domeHalfSelect)
                putInt("warp_mode", warpMode.id)
                putBoolean("is_audio_mirrored", isAudioMirrored)
                putFloat("video_curvature", videoCurvature)
                putInt("max_resolution_id", maxResolution.id)
                putBoolean("is_floating_ball_enabled", isFloatingBallEnabled)
                putFloat("floating_ball_speed", floatingBallSpeed)
                putFloat("base_playback_speed", basePlaybackSpeed)
                putInt("max_fps", maxFps)
                putBoolean("is_software_decoding", isSoftwareDecoding)
                putInt("decoder_engine_id", decoderEngine.id)
                putBoolean("is_subtitle_enabled", isSubtitleEnabled)
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
                putInt("asr_language_id", asrManager.config.language.id)
                putInt("asr_model_size", asrManager.config.modelSize.id)
            } else {
                remove("projection_mode")
                remove("stereo_mode")
                remove("beauty_level")
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
                remove("is_split_screen_vr")
                remove("is_gyro_enabled")
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
                remove("is_subtitle_enabled")
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
    @Composable
    fun BatchTranscribeSection() {
                                // ===== 后台生成全片字幕（v85）=====
                                Surface(
                                    color = Color.White.copy(alpha = 0.06f),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(
                                                    "后台生成全片字幕 (SRT)",
                                                    color = Color.White,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    text = if (isBatchTranscribing)
                                                        "转写中：${(batchTranscribeProgress * 100).toInt()}%　${batchTranscribeStatus}"
                                                    else
                                                        "识别语言：${asrManager.config.language.label} · ${asrManager.config.modelOption.label}（${asrManager.config.modelOption.sizeMb}MB）",
                                                    color = Color.White.copy(alpha = 0.55f),
                                                    fontSize = 9.sp,
                                                    lineHeight = 12.sp
                                                )
                                            }
                                            Button(
                                                onClick = { startBatchTranscribe() },
                                                enabled = !isBatchTranscribing,
                                                colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.height(30.dp)
                                            ) {
                                                Text(
                                                    if (isBatchTranscribing) "转写中…" else "开始",
                                                    color = AccentOnColor,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }

                                        // ===== 识别语言选择（v87）=====
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text("语言", color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
                                            VoskLanguage.entries.forEach { lang ->
                                                val sel = asrManager.config.language == lang
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(if (sel) AccentColor else Color.White.copy(alpha = 0.08f))
                                                        .clickable(enabled = !isBatchTranscribing) {
                                                            asrManager.config = asrManager.config.copy(language = lang)
                                                            if (isMemoryModeEnabled) prefs.edit().putInt("asr_language_id", lang.id).apply()
                                                            keepUiAlight()
                                                        }
                                                        .padding(vertical = 4.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        lang.label,
                                                        color = if (sel) AccentOnColor else Color.White.copy(alpha = 0.8f),
                                                        fontSize = 10.sp,
                                                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                }
                                            }
                                        }

                                        // ===== 模型大小选择（v108：支持下载进度、重试、取消）=====
                                        // 模型选择区：干净的选项卡片
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text("模型", color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
                                            VoskModelSize.entries.forEach { size ->
                                                val sel = asrManager.config.modelSize == size
                                                val opt = VoskModels.firstOrNull { it.language == asrManager.config.language && it.size == size }
                                                val isDownloaded = opt?.let {
                                                    File(context.filesDir, "vosk_models/${it.modelName}/.ready").exists()
                                                } ?: false
                                                val isActive = asrManager.isModelDownloading && asrManager.config.modelSize == size
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(
                                                            when {
                                                                isActive -> AccentColor.copy(alpha = 0.25f)
                                                                sel -> AccentColor
                                                                else -> Color.White.copy(alpha = 0.08f)
                                                            }
                                                        )
                                                        .clickable(enabled = !isBatchTranscribing && !asrManager.isModelDownloading) {
                                                            if (!isDownloaded) {
                                                                asrManager.config = asrManager.config.copy(modelSize = size)
                                                                if (isMemoryModeEnabled) prefs.edit().putInt("asr_model_size", size.id).apply()
                                                                opt?.let { asrManager.startModelDownload(it) }
                                                                keepUiAlight()
                                                            } else {
                                                                asrManager.config = asrManager.config.copy(modelSize = size)
                                                                if (isMemoryModeEnabled) prefs.edit().putInt("asr_model_size", size.id).apply()
                                                                keepUiAlight()
                                                            }
                                                        }
                                                        .padding(vertical = 6.dp, horizontal = 4.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Column(
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                                    ) {
                                                        Text(
                                                            text = if (opt != null) "${size.label}\n${opt.sizeMb}MB" else size.label,
                                                            color = if (sel) AccentOnColor else Color.White.copy(alpha = 0.85f),
                                                            fontSize = 9.sp,
                                                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                                            textAlign = TextAlign.Center,
                                                            lineHeight = 11.sp
                                                        )
                                                        // 状态指示小圆点
                                                        Text(
                                                            text = when {
                                                                isActive -> "⬇下载中"
                                                                isDownloaded -> "✓ 就绪"
                                                                else -> "点击下载"
                                                            },
                                                            color = when {
                                                                isActive -> Color(0xFF4FC3F7)  // 亮蓝
                                                                isDownloaded -> Color(0xFF81C784) // 柔绿
                                                                else -> AccentColor.copy(alpha = 0.7f)
                                                            },
                                                            fontSize = 8.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        // ===== 下载进度区（独立卡片，不挤在模型选项里）=====
                                        if (asrManager.isModelDownloading) {
                                            val downloadingModel = VoskModels.firstOrNull {
                                                it.language == asrManager.config.language && it.size == asrManager.config.modelSize
                                            }
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Color.Black.copy(alpha = 0.3f))
                                                    .border(
                                                        width = 1.dp,
                                                        color = AccentColor.copy(alpha = 0.3f),
                                                        shape = RoundedCornerShape(8.dp)
                                                    )
                                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalArrangement = Arrangement.spacedBy(5.dp)
                                            ) {
                                                // 模型名称 + 大小
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = "正在下载 ${downloadingModel?.label ?: ""}",
                                                        color = Color.White.copy(alpha = 0.9f),
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text(
                                                        text = downloadingModel?.let { "${it.sizeMb}MB" } ?: "",
                                                        color = Color.White.copy(alpha = 0.5f),
                                                        fontSize = 9.sp
                                                    )
                                                }
                                                // 进度条
                                                LinearProgressIndicator(
                                                    progress = asrManager.modelDownloadProgress,
                                                    color = AccentColor,
                                                    trackColor = Color.White.copy(alpha = 0.15f),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(6.dp)
                                                        .clip(RoundedCornerShape(3.dp))
                                                )
                                                // 百分比 + 详细状态
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = "下载中 ${(asrManager.modelDownloadProgress * 100).toInt()}%",
                                                        color = Color(0xFF4FC3F7), // 亮蓝，与进度条呼应
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = asrManager.downloadStatus,
                                                        color = Color.White.copy(alpha = 0.6f),
                                                        fontSize = 9.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                // 取消按钮
                                                Text(
                                                    text = "取消下载",
                                                    color = Color(0xFFEF5350), // 柔红
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier
                                                        .align(Alignment.End)
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(Color(0xFFEF5350).copy(alpha = 0.15f))
                                                        .clickable { asrManager.cancelDownload() }
                                                        .padding(horizontal = 10.dp, vertical = 3.dp)
                                                )
                                            }
                                        }
                                        if (isBatchTranscribing) {
                                            LinearProgressIndicator(
                                                progress = { batchTranscribeProgress },
                                                color = AccentColor,
                                                trackColor = Color.White.copy(alpha = 0.12f),
                                                modifier = Modifier.fillMaxWidth().height(4.dp)
                                            )
                                        }
                                    }
                                }
    }
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
                ?.takeIf { it.isNotBlank() } ?: "外部视频"
            val customItem = MediaItem(
                id = "imported_" + System.currentTimeMillis(),
                title = realName,
                uri = initialVideoUri,
                isVideo = true,
                isDemo = false,
                description = "从第三方软件导入播放的视频"
            )
            selectedMediaItem = customItem
            projectionMode = ProjectionMode.STANDARD
            stereoMode = StereoMode.MONO
            photoReloadTrigger++
            onExternalUriConsumed?.invoke()
        }
    }

    // Effect to retrieve video seek preview thumbnails on user scrub dragging
    LaunchedEffect(hoverTimeMs, selectedMediaItem.uri, isHoverActive) {
        if (selectedMediaItem.isVideo && selectedMediaItem.uri != null && isHoverActive) {
            val uriStr = selectedMediaItem.uri ?: return@LaunchedEffect
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
                    val bmp = retriever.getFrameAtTime(hoverTimeMs * 1000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (bmp != null) {
                        val scaled = Bitmap.createScaledBitmap(bmp, 160, 90, true)
                        if (scaled != bmp) {
                            bmp.recycle()
                        }
                        withContext(Dispatchers.Main) {
                            hoverPreviewBitmap = scaled
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VRPlayerScreen", "Error of hover frame retrieval", e)
                } finally {
                    try { retriever?.release() } catch (e: Exception) {}
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
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri) ?: ""
            val isVideo = mimeType.startsWith("video") || uri.toString().contains(".mp4")

            val realName = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringBeforeLast('.')
                ?.takeIf { it.isNotBlank() }
                ?: if (isVideo) "导入视频" else "导入图像"
            val customItem = MediaItem(
                id = "custom_" + System.currentTimeMillis(),
                title = realName,
                uri = uri.toString(),
                isVideo = isVideo,
                isDemo = false,
                description = "用户从本地相册导入的媒体内容。路径: ${uri.lastPathSegment}"
            )

            // Setup smart default projections
            if (!isVideo) {
                // If it is an image, let's load the bitmap in memory
                scope.launch {
                    try {
                        val bmp = withContext(Dispatchers.IO) {
                            resolver.openInputStream(uri)?.use { stream ->
                                val opts = BitmapFactory.Options().apply {
                                    inSampleSize = 1 // load full size, panorama requires quality
                                }
                                BitmapFactory.decodeStream(stream, null, opts)
                            }
                        }
                        if (bmp != null) {
                            customBitmap = bmp
                            // Detect if the aspect ratio is 2:1 (common panoramic format)
                            val r = bmp.width.toFloat() / bmp.height.toFloat()
                            if (r in 1.8f..2.2f) {
                                projectionMode = ProjectionMode.VR_360
                            } else {
                                projectionMode = ProjectionMode.STANDARD
                            }
                            selectedMediaItem = customItem
                            photoReloadTrigger++
                        }
                    } catch (e: Exception) {
                        Log.e("VRPlayerScreen", "Error loading custom picked bitmap", e)
                    }
                }
            } else {
                projectionMode = ProjectionMode.STANDARD // default to standard 2D view for Video
                selectedMediaItem = customItem
                photoReloadTrigger++
            }
        }
    }

    // Handle modern ExoPlayer lifecycle and Surface Texture streaming in Compose
    var playerInstance by remember { mutableStateOf<ExoPlayer?>(null) }

    // Synchronize player speed with basePlaybackSpeed and floating ball long-press boost
    LaunchedEffect(basePlaybackSpeed, floatingBallSpeed, isFloatingBallPressed, playerInstance) {
        val speedToApply = if (isFloatingBallPressed) floatingBallSpeed else basePlaybackSpeed
        playerInstance?.setPlaybackSpeed(speedToApply)
    }
    var currentGlSurfaceView by remember { mutableStateOf<VRGLSurfaceView?>(null) }

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
                                ?: "自定义 LUT"
                            Toast.makeText(context, "LUT 已应用：$lutName", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "LUT 解析失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VRPlayerScreen", "LUT load failed", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "LUT 加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
    var showResolutionTip by remember { mutableStateOf(false) }
    var resolutionTipText by remember { mutableStateOf("") }

    var isTranscoding by remember { mutableStateOf(false) }
    var transcodingProgress by remember { mutableStateOf(0) }
    var transcodingStatusText by remember { mutableStateOf("") }
    // Seek-failure auto-fix state: some mp4 containers reset position to 0 on seek. (8/1 功能)
    var isRemuxing by remember { mutableStateOf(false) }
    var seekUnsupported by remember { mutableStateOf(false) }

    // LAN (SMB) browser state (8/2 功能)
    var smbDialogOpen by remember { mutableStateOf(false) }
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
    // 播放位置恢复（8/1 功能）
    var restorePositionMs by remember { mutableLongStateOf(0L) }

    // Media3 Transformer downscaling function
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun startDownscalingTranscode() {
        val uriStr = selectedMediaItem.uri ?: return
        if (maxResolution == MaxResolution.UNRESTRICTED) {
            Toast.makeText(context, "请先选择一个分辨率限制后再进行降级转码", Toast.LENGTH_SHORT).show()
            return
        }

        val targetHeight = maxResolution.height
        val targetWidth = maxResolution.width
        
        // Uniquely identify transcoded file by item id and target height to avoid collision
        val cacheFile = File(context.cacheDir, "transcoded_${selectedMediaItem.id}_${targetHeight}.mp4")

        // If cached file already exists, load and play it immediately
        if (cacheFile.exists() && cacheFile.length() > 1024) {
            Toast.makeText(context, "检测到已缓存的 ${maxResolution.displayName} 降级版本，直接播放！", Toast.LENGTH_SHORT).show()
            val transcodedMediaItem = selectedMediaItem.copy(
                title = selectedMediaItem.title + " (${maxResolution.displayName} 降级版)",
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
        transcodingStatusText = "正在初始化转码引擎..."

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
                        transcodingStatusText = "正在降轨转码为 ${maxResolution.displayName}... ${transcodingProgress}%"
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
                Toast.makeText(context, "降轨转码成功！正在播放 ${maxResolution.displayName} 视频", Toast.LENGTH_LONG).show()
                val transcodedMediaItem = selectedMediaItem.copy(
                    title = selectedMediaItem.title + " (${maxResolution.displayName} 降级版)",
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
                Toast.makeText(context, "转码降轨失败: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Dynamic track selection parameters update when maxResolution limit is changed
    LaunchedEffect(maxResolution) {
        playerInstance?.let { player ->
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
                    withContext(Dispatchers.Main) { smbError = "路径不存在: $path" }
                    return@launch
                }
                val entries = dir.listFiles()?.toList() ?: emptyList()
                withContext(Dispatchers.Main) {
                    smbPath = path
                    smbEntries = entries
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { smbError = "连接失败: ${e.message}" }
            }
        }
    }

    fun connectSmb() {
        val host = smbHost.trim()
        if (host.isEmpty()) {
            smbError = "请输入服务器地址"
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
            "检测到视频拖动定位异常，正在自动修复容器（无需转码）...",
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
                        if (result.audioIncluded) "视频容器已修复，现在可以正常拖动定位了" else "视频容器已修复（音频轨道不兼容，播放将无声）",
                        Toast.LENGTH_LONG
                    ).show()
                    selectedMediaItem = selectedMediaItem.copy(
                        uri = Uri.fromFile(out).toString(),
                        title = selectedMediaItem.title + " (已修复)"
                    )
                    photoReloadTrigger++
                } else {
                    seekUnsupported = true
                    Toast.makeText(context, "该视频格式不支持拖动定位（自动修复失败）", Toast.LENGTH_SHORT).show()
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
            "视频超出硬件解码标称上限，正在修改编码头尝试硬件解码（实验性）...",
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
                        "已生成硬件解码兼容版本，正在播放（若花屏/黑屏/无声请反馈芯片型号）",
                        Toast.LENGTH_LONG
                    ).show()
                    selectedMediaItem = selectedMediaItem.copy(
                        uri = Uri.fromFile(out).toString(),
                        title = selectedMediaItem.title + " (硬解适配)"
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
        playerInstance?.let { p ->
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
        sb.append("文件: $displayName\n")
        if (title != null && title != displayName) {
            sb.append("标题: $title\n")
        }
        var gotAny = false
        playerInstance?.let { p ->
            try {
                val dur = p.duration
                if (dur > 0) {
                    gotAny = true
                    sb.append("时长: ${dur / 1000 / 60}分${(dur / 1000) % 60}秒\n")
                }
                val groups = p.currentTracks?.groups
                if (groups != null && groups.isNotEmpty()) {
                    gotAny = true
                    for (g in groups) {
                        val f = g.mediaTrackGroup.getFormat(0)
                        val mime = f.sampleMimeType ?: "未知"
                        if (mime.startsWith("video/")) {
                            if (f.width > 0 && f.height > 0) {
                                sb.append("分辨率: ${f.width} × ${f.height}\n")
                            }
                            sb.append("视频编码: $mime\n")
                            if (f.frameRate > 0f) sb.append("帧率: ${f.frameRate} fps\n")
                            if (f.bitrate > 0) sb.append("码率: ${f.bitrate / 1000} kbps\n")
                        } else if (mime.startsWith("audio/")) {
                            sb.append("音轨: $mime ${f.language ?: ""}\n")
                        } else {
                            sb.append("轨道: $mime ${f.language ?: ""}\n")
                        }
                    }
                }
                sb.append("解码: ${if (isSoftwareDecoding) "软件" else "硬件"}\n")
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
                    if (w != null && h != null && !sb.contains("分辨率")) {
                        sb.append("分辨率: $w × $h\n")
                    }
                    val rotation = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    if (rotation != null && rotation != "0") sb.append("旋转: $rotation°\n")
                    val fps = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    if (fps != null && fps != "-1" && !sb.contains("帧率")) sb.append("帧率: $fps fps\n")
                    val bitrate = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    if (bitrate != null && !sb.contains("码率")) sb.append("码率: ${bitrate.toLong() / 1000} kbps\n")
                    val mime = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    if (mime != null && !sb.contains("编码")) sb.append("编码: $mime\n")
                }
            } catch (e: Exception) {
                Log.e("VRPlayerScreen", "video info retriever failed", e)
            } finally {
                try { retriever?.release() } catch (_: Exception) {}
            }

            val info = if (gotAny) sb.toString() else "未能读取视频信息"
            withContext(Dispatchers.Main) {
                videoInfoDialogText = info
            }
        }
    }

    // Helper inside Compose to rebuild/re-bind Android ExoPlayer to GLES
    fun setupVideoPlayer(surfaceTexture: SurfaceTexture, videoUriStr: String) {
        try {
            playerInstance?.release()
            isVideoPlaying = false

            val decodedUri = Uri.parse(videoUriStr)
            
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
            // SMB-aware data source factory so smb:// URIs stream over the LAN (8/2)
            val smbAwareFactory = androidx.media3.datasource.DefaultDataSource.Factory(
                context,
                SmbDataSource.Factory()
            )
            val exo = ExoPlayer.Builder(context, renderersFactory)
                .setMediaSourceFactory(
                    androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
                        .setDataSourceFactory(smbAwareFactory)
                )
                .build()
                .apply {
                setVideoSurface(nativeSurface)
                setMediaItem(ExoMediaItem.fromUri(decodedUri))
                repeatMode = Player.REPEAT_MODE_ALL
                
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setMaxVideoSize(maxResolution.width, maxResolution.height)
                    .build()
                
                addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        val width = videoSize.width
                        val height = videoSize.height
                        if (width > 0 && height > 0) {
                            try {
                                surfaceTexture.setDefaultBufferSize(
                                    effectiveOutputSize(width, height).first,
                                    effectiveOutputSize(width, height).second
                                )
                                currentGlSurfaceView?.renderer?.let { r ->
                                    r.videoWidth = width
                                    r.videoHeight = height
                                }
                                Log.d("VRPlayerScreen", "ExoPlayer onVideoSizeChanged: updated surface texture buffer to ${width}x${height}")
                                
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
                                            "已强制切换为 ${targetMode.displayName}${if (targetStereo != StereoMode.MONO) " + ${targetStereo.displayName}" else ""}",
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
                                                Toast.makeText(context, "检测到 2:1 全景视频，已切换至 VR_360 全景（单目）", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                        else -> {
                                            // Ordinary flat video: don't let the 180° dome distort it
                                            if (projectionMode != ProjectionMode.STANDARD) {
                                                projectionMode = ProjectionMode.STANDARD
                                                stereoMode = StereoMode.MONO
                                                currentGlSurfaceView?.renderer?.warpDualCenter = false
                                                Toast.makeText(context, "检测到普通 2D 视频，已切换至平面模式", Toast.LENGTH_SHORT).show()
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
                                    Toast.makeText(context, "普通 2D 视频已恢复单目显示（3D 立体仅适用于 3D 片源）", Toast.LENGTH_SHORT).show()
                                }
                                
                                // Detect ultra high resolution (like 8K or exceeds user set resolution limit)
                                if (width > maxResolution.width || height > maxResolution.height) {
                                    resolutionTipText = "当前视频分辨率 (${width}x${height}) 超过设置限制，可能导致卡顿。"
                                    showResolutionTip = true
                                } else if (width >= 7680 || height >= 4320) {
                                    resolutionTipText = "该视频为 ${width}x${height} 8K超高清，若卡顿建议开启解码限制。"
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
                                        resolutionTipText = "视频 ${width}x${height} 超出硬件解码标称上限，正在尝试修改编码头硬解"
                                        showResolutionTip = true
                                        startLevelPatchFix()
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("VRPlayerScreen", "Error setting SurfaceTexture buffer size to ${width}x${height}", e)
                            }
                        }
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
                    
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e("VRPlayerScreen", "ExoPlayer playback error", error)
                        // F. Auto fallback to software decoding when the hardware
                        // decoder fails to initialize or decode (opt-in, off by default). (8/3 功能)
                        if (autoFallbackSoftEnabled && !isSoftwareDecoding &&
                            (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED)
                        ) {
                            Toast.makeText(context, "硬件解码失败，自动切换软件解码", Toast.LENGTH_SHORT).show()
                            isSoftwareDecoding = true
                        }
                    }
                })
                
                prepare()
                // 恢复上次播放位置（8/1 功能）
                if (restorePositionMs > 0L && restorePositionMs < (playerInstance?.duration ?: Long.MAX_VALUE)) {
                    seekTo(restorePositionMs)
                }
                playWhenReady = true
                isVideoPlaying = true
            }
            playerInstance = exo
        } catch (e: Exception) {
            Log.e("VRPlayerScreen", "Error preparing video content", e)
        }
    }

    // Continuously sync playback position for subtitle timing + 记录播放位置用于恢复（8/1 功能）
    LaunchedEffect(playerInstance, isVideoPlaying) {
        while (true) {
            playerInstance?.let { player ->
                currentPositionMs = player.currentPosition
                if (player.isPlaying && player.currentPosition > 0) {
                    restorePositionMs = player.currentPosition
                }
            }
            delay(150L)
        }
    }

    // Effect that triggers when the media selection changes or decoder settings change
    LaunchedEffect(selectedMediaItem, photoReloadTrigger, isSoftwareDecoding, decoderEngine) {
        keepUiAlight()
        val view = currentGlSurfaceView ?: return@LaunchedEffect

        // Standard clean transition
        if (selectedMediaItem.isVideo) {
            // Default to 180° Dome, Left visual eye perspective, SBS 3D, and disable gyroscope when video opened
            projectionMode = ProjectionMode.VR_180
            domeHalfSelect = 1
            isGyroEnabled = false
            stereoMode = StereoMode.SBS

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

            // Load Bitmap asynchronously on background thread to prevent ANR during canvas generation
            val bmp: Bitmap = withContext(Dispatchers.IO) {
                if (selectedMediaItem.isDemo) {
                    DemoMediaProvider.loadDemoBitmap(selectedMediaItem.id)
                } else {
                    customBitmap ?: DemoMediaProvider.loadDemoBitmap("demo_standard_portrait")
                }
            }
            view.updateImage(bmp)
        }
    }

    // v93：切换视频时自动加载已生成的字幕（应用目录下的 _asr.srt）
    // v95 修复：先清空上一个视频的字幕，确保每个视频字幕独立
    // v96 修复：用更宽松的文件名匹配（扫描应用目录找匹配的 _asr.srt）
    LaunchedEffect(selectedMediaItem.uri) {
        // 先清空旧字幕，避免上一个视频的字幕残留
        loadedSubtitleCues = emptyList()
        loadedSubtitleFileName = ""

        val uri = selectedMediaItem.uri ?: return@LaunchedEffect
        // 多重匹配：优先用 title，再用 URI 文件名
        val candidateNames = mutableSetOf<String>()
        selectedMediaItem.title?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }?.let { candidateNames.add(it) }
        Uri.parse(uri).lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }?.let { candidateNames.add(it) }
        if (candidateNames.isEmpty()) return@LaunchedEffect

        val filesDir = context.getExternalFilesDir(null) ?: return@LaunchedEffect
        val srtFiles = filesDir.listFiles { f -> f.name.endsWith("_asr.srt") } ?: emptyArray()
        // 按候选名精确匹配（忽略大小写），无匹配则放弃（不兜底）
        val matchedFile = srtFiles.firstOrNull { srt ->
            val srtBase = srt.name.removeSuffix("_asr.srt").lowercase()
            candidateNames.any { cand -> cand.lowercase() == srtBase }
        }

        if (matchedFile != null && matchedFile.length() > 0) {
            try {
                val content = withContext(Dispatchers.IO) { matchedFile.readText() }
                val cues = SubtitleParser.parseSrtOrVtt(content)
                if (cues.isNotEmpty()) {
                    loadedSubtitleCues = cues
                    loadedSubtitleFileName = matchedFile.name
                    isSubtitleEnabled = true
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "已加载字幕：${matchedFile.name}（${cues.size} 句）", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.w("VRPlayerScreen", "Auto-load generated subtitle failed: ${e.message}")
            }
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

    LaunchedEffect(isGyroEnabled, sensorManager) {
        if (isGyroEnabled) {
            sensorManager?.start()
        } else {
            sensorManager?.stop()
        }
    }

    // Main layout
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1C1B1F)) // High Density Theme deep background color
            .testTag("player_root_container")
    ) {
        // Liquid glass backdrop：捕获视频层 + 主题底色，供玻璃面板绘制（Backdrop 库，Android 12+）
        val isLiquidGlass = glassMode == 1 && Build.VERSION.SDK_INT >= 31
        val liquidBackdrop = rememberLayerBackdrop {
            drawRect(ThemeBgColor)
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
                view.renderer.stereoMode = stereoMode
                view.renderer.beautyLevel = beautyLevel
                view.renderer.beautyCompareEnabled = beautyCompareEnabled
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

        // 2.5 Dual-Eye & Flat Mode Universal Subtitle Overlay

                                // ===== v84 UI recomposition isolation: subtitle layer =====
                                @Composable
                                fun SubtitleLayer() {
        SubtitleOverlay(
            currentPositionMs = currentPositionMs,
            subtitleCues = loadedSubtitleCues,
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
            modifier = Modifier.fillMaxSize()
        )
                                }

                                SubtitleLayer()
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
                        contentDescription = "分辨率提示",
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
                            text = "一键降级转码",
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
                        text = "我知道了",
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
                            text = "视频分辨率降轨转换中",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Text(
                            text = "正在利用底层硬件编码器将 8K 超清视频高保真转码为较小分辨率以保证完美流畅播放。请勿退出应用。",
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
                    contentDescription = if (isUiLocked) "解锁控制界面" else "锁定控制界面",
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
                                contentDescription = "美颜图标",
                                tint = AccentColor,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "美颜VR播放器",
                                color = TextLightColor,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("app_title_text")
                            )
                        }
                        Text(
                            text = "当前媒体: ${getMediaDisplayName()}",
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
                                filePickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                                )
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
                                contentDescription = "导入本地照片或视频"
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
                                contentDescription = "VR分屏戴戴模式",
                                modifier = Modifier.size(20.dp)
                            )
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
                                contentDescription = "磨皮参数与投影二级控制菜单",
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
                                if (isLiquidGlass) Modifier.drawBackdrop(
                                    backdrop = liquidBackdrop,
                                    shape = { RoundedCornerShape(24.dp) },
                                    effects = {
                                        vibrancy()
                                        blur(20f.dp.toPx())
                                        if (Build.VERSION.SDK_INT >= 33) {
                                            lens(14f.dp.toPx(), 24f.dp.toPx())
                                        }
                                    },
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
                                                        contentDescription = "视频拖拽预览缩略图",
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
                                                playerInstance?.let { mp ->
                                                    try {
                                                        val duration = mp.duration
                                                        if (duration > 0) {
                                                            val targetTime = (adjustedProgress * duration).toLong()
                                                            hoverTimeMs = targetTime
                                                            mp.seekTo(targetTime)
                                                        }
                                                    } catch (e: Exception) {}
                                                }
                                            },
                                            onValueChangeFinished = {
                                                isSeekingActive = false
                                                // Verify the seek took effect: some mp4 containers
                                                // (moov-at-end / fragmented) reset the position to 0,
                                                // in which case we offer an automatic container fix. (8/1 功能)
                                                val seekTarget = hoverTimeMs
                                                scope.launch {
                                                    delay(1000L)
                                                    if (seekTarget > 3000L) {
                                                        val pos = playerInstance?.currentPosition ?: -1L
                                                        if (pos < 2000L && !seekUnsupported && !isRemuxing &&
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
                                            contentDescription = "图像状态",
                                            tint = Color(0xFFFFD700),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("全景图像已静态渲染 • 手指拖曳查看", color = AccentColor, fontSize = 11.sp)
                                    }
                                    Text("支持双指缩放", color = TextSoftColor, fontSize = 11.sp)
                                }
                            }

                            // Playback controls row with modern arrangement（播控组绝对居中）
                            BoxWithConstraints(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                            @Composable
                            fun LeftControls() {
                                // Left Controls Group
                                Row(
                                    modifier = Modifier.align(Alignment.CenterStart),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 陀螺仪开关（Explore 图标）
                                    TooltipIconButton(
                                        tooltip = if (isGyroEnabled) "陀螺仪已开启" else "陀螺仪已关闭",
                                        onClick = { isGyroEnabled = !isGyroEnabled; keepUiAlight() },
                                        icon = Icons.Default.Explore,
                                        isActive = isGyroEnabled
                                    )

                                    // 视角锁定（Lock/LockOpen 图标，区别于屏幕旋转）
                                    TooltipIconButton(
                                        tooltip = if (isViewLocked) "视角已锁定" else "视角自由",
                                        onClick = { isViewLocked = !isViewLocked; keepUiAlight() },
                                        icon = if (isViewLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                        isActive = isViewLocked
                                    )

                                    // 屏幕旋转（ScreenRotation 图标）
                                    TooltipIconButton(
                                        tooltip = if (!isLandscape) "已锁定竖屏" else "点击锁定竖屏",
                                        onClick = { isLandscape = !isLandscape; keepUiAlight() },
                                        icon = Icons.Default.ScreenRotation,
                                        isActive = !isLandscape
                                    )
                                }
                            }

                            @Composable
                            fun CenterControls() {
                                // Center Controls Group: Prev, Play/Pause, Next（绝对居中）
                                Row(
                                    modifier = Modifier.align(Alignment.Center),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = {
                                            keepUiAlight()
                                            val currentIndex = DemoMediaProvider.demoMediaList.indexOfFirst { it.id == selectedMediaItem.id }
                                            if (currentIndex >= 0) {
                                                val prevIndex = if (currentIndex > 0) currentIndex - 1 else DemoMediaProvider.demoMediaList.size - 1
                                                customBitmap = null
                                                selectedMediaItem = DemoMediaProvider.demoMediaList[prevIndex]
                                            }
                                        },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.SkipPrevious,
                                            contentDescription = "上一首",
                                            tint = Color.White,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(54.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(AccentColor)
                                            .clickable {
                                                keepUiAlight()
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
                                            }
                                            .testTag("video_play_pause_button"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (selectedMediaItem.isVideo && isVideoPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = "播放暂停",
                                            tint = AccentOnColor,
                                            modifier = Modifier.size(30.dp)
                                        )
                                    }

                                    IconButton(
                                        onClick = {
                                            keepUiAlight()
                                            val currentIndex = DemoMediaProvider.demoMediaList.indexOfFirst { it.id == selectedMediaItem.id }
                                            if (currentIndex >= 0) {
                                                val nextIndex = if (currentIndex < DemoMediaProvider.demoMediaList.size - 1) currentIndex + 1 else 0
                                                customBitmap = null
                                                selectedMediaItem = DemoMediaProvider.demoMediaList[nextIndex]
                                            }
                                        },
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.SkipNext,
                                            contentDescription = "下一首",
                                            tint = Color.White,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }

                            @Composable
                            fun RightControls() {
                                // Right Controls Group: 4 个核心按钮（局域网/视频信息/音轨选择已移入设置面板）
                                Row(
                                    modifier = Modifier.align(Alignment.CenterEnd),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // 重置视角中心
                                    TooltipIconButton(
                                        tooltip = "重置视角中心",
                                        onClick = {
                                            keepUiAlight()
                                            currentGlSurfaceView?.renderer?.run { manualYaw = 0f; manualPitch = 0f }
                                        },
                                        icon = Icons.Default.MyLocation,
                                        iconSize = 18.dp
                                    )

                                    // VR 分屏模式
                                    TooltipIconButton(
                                        tooltip = if (isSplitScreenVR) "退出 VR 分屏" else "VR 分屏模式",
                                        onClick = { isSplitScreenVR = !isSplitScreenVR; keepUiAlight() },
                                        icon = Icons.Default.ViewInAr,
                                        isActive = isSplitScreenVR
                                    )

                                    // 字幕快捷入口
                                    TooltipIconButton(
                                        tooltip = "字幕与转写",
                                        onClick = { isSubtitleQuickPanelOpen = !isSubtitleQuickPanelOpen; keepUiAlight() },
                                        icon = Icons.Default.Subtitles,
                                        isActive = isSubtitleQuickPanelOpen
                                    )

                                    // 设置面板
                                    TooltipIconButton(
                                        tooltip = "播放参数与美颜",
                                        onClick = { isSettingsDialogOpen = !isSettingsDialogOpen; keepUiAlight() },
                                        icon = Icons.Default.Settings,
                                        isActive = isSettingsDialogOpen
                                    )
                                }
                            }
                            if ((3 * 40 + 2 * 8 + 40 + 54 + 40 + 2 * 12 + 6 * 40 + 5 * 8 + 32).dp > maxWidth) {
                                // ????????????+?????
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CenterControls()
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        LeftControls()
                                        RightControls()
                                    }
                                }
                            } else {
                                // ????/?/?????
                                Box(modifier = Modifier.fillMaxWidth()) {
                                    Box(modifier = Modifier.align(Alignment.CenterStart)) { LeftControls() }
                                    Box(modifier = Modifier.align(Alignment.Center)) { CenterControls() }
                                    Box(modifier = Modifier.align(Alignment.CenterEnd)) { RightControls() }
                                }
                            }
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
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 字幕开关
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("字幕", color = AccentColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    if (isSubtitleEnabled) "显示中" else "已关闭",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 10.sp
                                )
                                Switch(
                                    checked = isSubtitleEnabled,
                                    onCheckedChange = { isSubtitleEnabled = it; keepUiAlight() },
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
                                    "字幕翻译",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (subtitleTranslator.config.isEnabled)
                                        "${subtitleTranslator.config.engine.displayName} → ${subtitleTranslator.config.targetLanguage.displayName}"
                                    else "未开启",
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

                        // 后台生成全片字幕（含语言/模型选择/进度）
                        BatchTranscribeSection()

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
                            Text("完整字幕设置…", color = AccentColor, fontSize = 10.sp)
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
                                    text = "高级播放参数与美肤协同微调",
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
                                        contentDescription = "局域网播放",
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
                                        contentDescription = "视频信息",
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
                                        contentDescription = "音轨/字幕轨选择",
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
                                    contentDescription = "关闭",
                                    tint = Color.White.copy(alpha = 0.6f)
                                )
                            }
                        }
                                }
                                /** 区块 0：UI 主题与玻璃效果（8/2 功能） */
                                @Composable
                                fun SettingsSection0() {
                                Text(
                                    text = "0. UI 主题与玻璃效果（8/2 功能）",
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
                                        text = UiThemes.byId(uiThemeId).name,
                                        color = AccentColor,
                                        fontSize = 12.sp
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf(0 to "实色", 1 to "液体玻璃").forEach { (m, label) ->
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
                                }
                                /** 区块 1：镜头投影与视角模式（2D/鱼眼/360/180/盒子 + 变形） */
                                @Composable
                                fun SettingsSection1() {
                                Text(
                                    text = "1. 镜头投影与视角模式",
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
                                            Text("投影智能检测", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                            Text("自动识别 2:1 全景 / 普通 2D 内容并切换投影与立体模式", color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp)
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
                                        Text("强制视频类型", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                        val forceOptions = listOf(
                                            0 to "自动",
                                            1 to "2D",
                                            2 to "360°",
                                            3 to "180°",
                                            4 to "3D左右",
                                            5 to "3D上下"
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
                                            text = "自动：按宽高比识别；强制：固定视频类型并锁定（2:1 全景自动开启双中心变形）",
                                            color = Color.White.copy(alpha = 0.4f),
                                            fontSize = 8.sp
                                        )
                                    }

                                    Text("视角格式", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
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
                                                        text = mode.displayName,
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

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("立体格式", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
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
                                                        StereoMode.MONO -> "平面2D"
                                                        StereoMode.SBS -> "左右3D"
                                                        StereoMode.TAB -> "上下3D"
                                                    },
                                                    color = if (isSelected) AccentOnColor else Color.White,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("视频画面镜像", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(false to "正常画面", true to "左右镜像").forEach { (mirrored, label) ->
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

                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("音频声道镜像", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(false to "正常声道", true to "声道反转").forEach { (mirrored, label) ->
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
                                        Text("180°穹幕源画面裁剪", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            listOf(1 to "仅左半屏", 0 to "仅右半屏").forEach { (half, label) ->
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

                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("视场角 (FOV) 视野广度", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
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
                                    Text("视频变形/透视效果", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
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
                                                        text = mode.displayName,
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
                                                WarpMode.CYLINDER_RECT -> "等距矩形柱面弯曲率"
                                                WarpMode.CYLINDER -> "等距圆柱弯曲率"
                                                WarpMode.SPHERE -> "球面立体膨胀度"
                                                WarpMode.CURVE -> "环幕曲率调节"
                                                WarpMode.ANTI_SPHERE -> "反向球面收缩度"
                                                WarpMode.ANTI_CURVE -> "反向曲率收缩度"
                                                else -> "变焦弯曲率"
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
                                    Text("最大视频解码分辨率限制 (低配设备推荐 4K 或 2K)", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
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
                                                        text = res.displayName,
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
                                    text = "8K 硬解实验（实验性，默认关闭）",
                                    color = AccentColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                ExperimentalSwitchRow(
                                    title = "编码头 level 适配（4.1）",
                                    desc = "超出硬件解码上限的 8K 视频自动修改 SPS level 尝试硬解",
                                    checked = levelPatchEnabled,
                                    onChanged = { levelPatchEnabled = it },
                                    accentColor = AccentColor,
                                    accentOnColor = AccentOnColor
                                )
                                ExperimentalSwitchRow(
                                    title = "level 压至 5.1（更激进）",
                                    desc = "配合上方开关，把 level 压到 5.1 绕过等级校验，花屏风险更高",
                                    checked = level51Enabled,
                                    onChanged = { level51Enabled = it },
                                    accentColor = AccentColor,
                                    accentOnColor = AccentOnColor
                                )
                                ExperimentalSwitchRow(
                                    title = "强制硬解选择器",
                                    desc = "跳过系统能力过滤，把所有硬件解码器都试一遍",
                                    checked = forceHwDecoderEnabled,
                                    onChanged = { forceHwDecoderEnabled = it },
                                    accentColor = AccentColor,
                                    accentOnColor = AccentOnColor
                                )
                                ExperimentalSwitchRow(
                                    title = "分辨率头欺骗（改 4K）",
                                    desc = "位级重写 SPS 宽高为 3840x2160，驱动按头分配资源，可能花屏",
                                    checked = spoofResolutionEnabled,
                                    onChanged = { spoofResolutionEnabled = it },
                                    accentColor = AccentColor,
                                    accentOnColor = AccentOnColor
                                )
                                ExperimentalSwitchRow(
                                    title = "缩小输出缓冲硬解",
                                    desc = "硬解输出缩到 1920px 宽，降低带宽/OOM 风险，画质略降",
                                    checked = downscaleOutputEnabled,
                                    onChanged = { downscaleOutputEnabled = it },
                                    accentColor = AccentColor,
                                    accentOnColor = AccentOnColor
                                )
                                ExperimentalSwitchRow(
                                    title = "补充解码参数",
                                    desc = "给解码器注入更大的输入缓冲等参数，部分机型有效",
                                    checked = addCodecParamsEnabled,
                                    onChanged = { addCodecParamsEnabled = it },
                                    accentColor = AccentColor,
                                    accentOnColor = AccentOnColor
                                )
                                ExperimentalSwitchRow(
                                    title = "硬解失败自动切软件",
                                    desc = "硬件解码报错时自动切换软件解码兜底",
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
                                        text = "说明：360°与180°全景支持陀螺仪或滑动实现多视角流畅环顾。",
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
                                    Text(
                                        text = "3. 悬浮球控速与播放倍速",
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
                                            Text("启用屏幕悬浮球", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                            Text("长按悬浮球快进，支持自由拖动放置", color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp)
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

                                    if (isFloatingBallEnabled) {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text("悬浮球长按倍速", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
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
                                        Text("基础常规播放倍速", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
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
                                        text = "4. 解码内核与帧率限制",
                                        color = AccentColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )

                                    // 解码器切换 EXO/MPV
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("解码器引擎", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            DecoderEngine.values().forEach { engine ->
                                                val isSelected = decoderEngine == engine
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(30.dp)
                                                        .background(
                                                            if (isSelected) AccentColor else Color.White.copy(alpha = 0.05f),
                                                            shape = RoundedCornerShape(8.dp)
                                                        )
                                                        .clickable {
                                                            decoderEngine = engine
                                                            Toast.makeText(context, "已切换解码器为: ${engine.displayName}", Toast.LENGTH_SHORT).show()
                                                            keepUiAlight()
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = engine.displayName,
                                                        color = if (isSelected) AccentOnColor else Color.White,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // 软硬解码切换
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("解码模式", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            listOf(false to "硬件解码 (GPU加速)", true to "软件解码 (CPU兼容)").forEach { (isSw, label) ->
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
                                                            Toast.makeText(context, "已切换为: ${if (isSw) "软件解码" else "硬件解码"}", Toast.LENGTH_SHORT).show()
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
                                        Text("帧率限制 (FPS Limit)", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                                        val fpsList = listOf(0 to "不限制", 12 to "12", 18 to "18", 24 to "24", 30 to "30", 48 to "48", 60 to "60", 90 to "90", 120 to "120")
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
                                /** 区块 5：字幕功能设置（SubtitleSettingsPanel：字体/位置/ASR/翻译入口） */
                                @Composable
                                fun SettingsSectionSubtitle() {

                                SubtitleSettingsPanel(
                                    isSubtitleEnabled = isSubtitleEnabled,
                                    onSubtitleEnabledChange = { isSubtitleEnabled = it },
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
                                    vrIpdOffsetRatio = vrIpdOffsetRatio,
                                    onVrIpdOffsetRatioChange = { vrIpdOffsetRatio = it },
                                    subtitleSearchApiKey = subtitleSearchApiKey,
                                    onSubtitleSearchApiKeyChange = {
                                        subtitleSearchApiKey = it
                                        prefs.edit().putString("subtitle_search_api_key", it).apply()
                                    },
                                    defaultSearchQuery = selectedMediaItem.title,
                                    translator = subtitleTranslator,
                                    onTranslateFileRequested = { subtitleTranslator.translateCuesBatch(loadedSubtitleCues) },
                                    accentColor = AccentColor,
                                    accentOnColor = AccentOnColor,
                                    onUserActivity = { keepUiAlight() }
                                )

                                // ===== 鍚庡彴鐢熸垚鍏ㄧ墖瀛楀箷锛坴91 鎻愬彇澶嶇敤锛?====
                                BatchTranscribeSection()
                                }
                                /** 区块 6：美颜设置（Shader 实时磨皮美白 + 预设方案 + 对比原图 + 2D 人像精修） */
                                @Composable
                                fun SettingsColumn2() {
                                // 应用美颜预设（13 项参数，顺序与下方滑块一致）
                                fun applyBeautyPreset(name: String) {
                                    beautyPreset = name
                                    val p = when (name) {
                                        "自然" -> floatArrayOf(0.4f, 0.3f, 0.2f, 0.15f, 0.15f, 0.1f, 0.1f, 0.2f, 0.15f, 0.15f, 0.3f, 0.1f, 0.1f)
                                        "淡妆" -> floatArrayOf(0.6f, 0.5f, 0.4f, 0.3f, 0.3f, 0.2f, 0.2f, 0.3f, 0.35f, 0.35f, 0.45f, 0.2f, 0.2f)
                                        "浓妆" -> floatArrayOf(0.9f, 0.8f, 0.7f, 0.6f, 0.5f, 0.35f, 0.35f, 0.5f, 0.6f, 0.6f, 0.7f, 0.4f, 0.35f)
                                        else -> return
                                    }
                                    beautyLevel = p[0]; beautyWhitening = p[1]; beautyFaceSlimming = p[2]; beautyBigEyes = p[3]
                                    beautyDarkCircles = p[4]; beautyNoseSlimming = p[5]; beautyMouth = p[6]; beautyTeethWhitening = p[7]
                                    beautyLipstick = p[8]; beautyBlush = p[9]; beautyEyebrows = p[10]; beautyLongLegs = p[11]; beautySmallHead = p[12]
                                    keepUiAlight()
                                }

                                Text(
                                    text = "2. Shader 实时美颜",
                                    color = AccentColor,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                // ===== 2D/3D 模式提示条 =====
                                val is2DBeautyMode = projectionMode == ProjectionMode.STANDARD ||
                                    projectionMode == ProjectionMode.FISHEYE
                                Surface(
                                    color = if (is2DBeautyMode) Color(0xFF1B4D2E) else Color(0xFF4D331B),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = if (is2DBeautyMode)
                                            "当前 2D 模式（${projectionMode.displayName}）：全部美颜可用，人像精修（瘦脸/大眼/口红等）需检测到人脸"
                                        else
                                            "当前 3D 模式（${projectionMode.displayName}）：仅磨皮/美白等通用效果生效；瘦脸/大眼/口红等 2D 人像精修已停用，切换 2D 模式后自动恢复",
                                        color = Color.White.copy(alpha = 0.92f),
                                        fontSize = 9.sp,
                                        lineHeight = 12.sp,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }

                                // ===== 对比原图开关 =====
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("对比原图", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        Text("开启后临时关闭全部美颜，直观对比效果", color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp)
                                    }
                                    Switch(
                                        checked = beautyCompareEnabled,
                                        onCheckedChange = { beautyCompareEnabled = it; keepUiAlight() },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = AccentOnColor,
                                            checkedTrackColor = AccentColor,
                                            uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                                            uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                                        ),
                                        modifier = Modifier.height(26.dp)
                                    )
                                }

                                // ===== 美颜预设 =====
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf("自然", "淡妆", "浓妆", "自定义").forEach { p ->
                                        Surface(
                                            color = if (beautyPreset == p) AccentColor else Color.White.copy(alpha = 0.10f),
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { applyBeautyPreset(p) }
                                        ) {
                                            Text(
                                                text = p,
                                                textAlign = TextAlign.Center,
                                                color = if (beautyPreset == p) Color(0xFF1A1A2E) else Color.White.copy(alpha = 0.85f),
                                                fontSize = 10.sp,
                                                fontWeight = if (beautyPreset == p) FontWeight.Bold else FontWeight.Normal,
                                                modifier = Modifier.padding(vertical = 5.dp).fillMaxWidth()
                                            )
                                        }
                                    }
                                }

                                // ===== 通用美颜（2D/3D 均生效）=====
                                Text(
                                    text = "通用美颜 · 2D/3D 均生效",
                                    color = AccentColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                BeautySliderItem("美颜强度 (磨皮)", beautyLevel, { beautyLevel = it; beautyPreset = "自定义"; keepUiAlight() }, accentColor = AccentColor)

                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("画面曝光 (亮度)", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                                        Text(if (brightnessLevel >= 0) "+${(brightnessLevel * 100).toInt()}" else "${(brightnessLevel * 100).toInt()}", color = AccentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Slider(
                                        value = brightnessLevel,
                                        onValueChange = {
                                            brightnessLevel = it
                                            beautyPreset = "自定义"
                                            keepUiAlight()
                                        },
                                        valueRange = -0.3f..0.3f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = AccentColor,
                                            activeTrackColor = AccentColor,
                                            inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                                        ),
                                        modifier = Modifier.height(26.dp)
                                    )
                                }

                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("五官轮廓塑形 (对比度)", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                                        Text("${(contrastLevel * 100).toInt()}%", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Slider(
                                        value = contrastLevel,
                                        onValueChange = {
                                            contrastLevel = it
                                            beautyPreset = "自定义"
                                            keepUiAlight()
                                        },
                                        valueRange = 0.7f..1.3f,
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color.White,
                                            activeTrackColor = Color.White,
                                            inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                                        ),
                                        modifier = Modifier.height(26.dp)
                                    )
                                }

                                BeautySliderItem("美白", beautyWhitening, { beautyWhitening = it; beautyPreset = "自定义"; keepUiAlight() }, accentColor = AccentColor)

                                // ===== v104 LUT 视频滤镜（内置 12 款 + 手机自选 .cube）=====
                                Text(
                                    text = "LUT 视频滤镜",
                                    color = AccentColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                // 内置 LUT 横向选择（无滤镜 + 12 款风格）
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    item {
                                        val selected = lutName == "无滤镜"
                                        Surface(
                                            color = if (selected) AccentColor else Color.White.copy(alpha = 0.10f),
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.clickable {
                                                lutName = "无滤镜"
                                                currentGlSurfaceView?.renderer?.setLutTexture(null)
                                                currentGlSurfaceView?.renderer?.lutMix = 0f
                                                keepUiAlight()
                                            }
                                        ) {
                                            Text(
                                                text = "无滤镜",
                                                textAlign = TextAlign.Center,
                                                color = if (selected) Color(0xFF1A1A2E) else Color.White.copy(alpha = 0.85f),
                                                fontSize = 10.sp,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                    items(LutUtils.builtinLuts) { (file, cnName) ->
                                        val selected = lutName == cnName
                                        Surface(
                                            color = if (selected) AccentColor else Color.White.copy(alpha = 0.10f),
                                            shape = RoundedCornerShape(14.dp),
                                            modifier = Modifier.clickable {
                                                if (isLutLoading) return@clickable
                                                isLutLoading = true
                                                lutName = cnName
                                                scope.launch(Dispatchers.IO) {
                                                    try {
                                                        val rgba = context.assets.open("luts/$file.cube").use {
                                                            LutUtils.parseCubeToRgba(it)
                                                        }
                                                        withContext(Dispatchers.Main) {
                                                            currentGlSurfaceView?.renderer?.setLutTexture(rgba)
                                                            currentGlSurfaceView?.renderer?.lutMix = lutMix
                                                        }
                                                    } catch (e: Exception) {
                                                        Log.e("VRPlayerScreen", "LUT $file load failed", e)
                                                    } finally {
                                                        withContext(Dispatchers.Main) { isLutLoading = false }
                                                    }
                                                }
                                                keepUiAlight()
                                            }
                                        ) {
                                            Text(
                                                text = cnName,
                                                textAlign = TextAlign.Center,
                                                color = if (selected) Color(0xFF1A1A2E) else Color.White.copy(alpha = 0.85f),
                                                fontSize = 10.sp,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }

                                // LUT 强度滑块 + 手机自选按钮
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text("滤镜强度", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                                            Text("${(lutMix * 100).toInt()}%", color = AccentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                        }
                                        Slider(
                                            value = lutMix,
                                            onValueChange = {
                                                lutMix = it
                                                currentGlSurfaceView?.renderer?.lutMix = it
                                                keepUiAlight()
                                            },
                                            valueRange = 0f..1f,
                                            colors = SliderDefaults.colors(
                                                thumbColor = AccentColor,
                                                activeTrackColor = AccentColor,
                                                inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                                            ),
                                            modifier = Modifier.height(26.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Surface(
                                        color = if (isLutLoading) Color.White.copy(alpha = 0.10f) else AccentColor,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.clickable(enabled = !isLutLoading) {
                                            lutPickerLauncher.launch("application/octet-stream")
                                            keepUiAlight()
                                        }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                                        ) {
                                            if (isLutLoading) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(12.dp),
                                                    strokeWidth = 2.dp,
                                                    color = AccentColor
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.Add,
                                                    contentDescription = null,
                                                    tint = Color(0xFF1A1A2E),
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (isLutLoading) "加载中" else "自选 LUT",
                                                color = Color(0xFF1A1A2E),
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = "当前：$lutName（内置 12 款，支持 .cube 文件）",
                                    color = Color.White.copy(alpha = 0.45f),
                                    fontSize = 9.sp
                                )

                                // ===== 2D 人像精修（仅 2D 模式 + 人脸检测生效）=====
                                Text(
                                    text = "2D 人像精修 · 需 2D 模式 + 人脸检测",
                                    color = AccentColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                BeautySliderItem("瘦脸", beautyFaceSlimming, { beautyFaceSlimming = it; beautyPreset = "自定义"; keepUiAlight() }, accentColor = AccentColor, enabled = is2DBeautyMode, badge = if (is2DBeautyMode) null else "3D 停用")
                                BeautySliderItem("大眼", beautyBigEyes, { beautyBigEyes = it; beautyPreset = "自定义"; keepUiAlight() }, accentColor = AccentColor, enabled = is2DBeautyMode, badge = if (is2DBeautyMode) null else "3D 停用")
                                BeautySliderItem("去黑眼圈", beautyDarkCircles, { beautyDarkCircles = it; beautyPreset = "自定义"; keepUiAlight() }, accentColor = AccentColor, enabled = is2DBeautyMode, badge = if (is2DBeautyMode) null else "3D 停用")
                                BeautySliderItem("瘦鼻", beautyNoseSlimming, { beautyNoseSlimming = it; beautyPreset = "自定义"; keepUiAlight() }, accentColor = AccentColor, enabled = is2DBeautyMode, badge = if (is2DBeautyMode) null else "3D 停用")
                                BeautySliderItem("嘴型调整", beautyMouth, { beautyMouth = it; beautyPreset = "自定义"; keepUiAlight() }, accentColor = AccentColor, enabled = is2DBeautyMode, badge = if (is2DBeautyMode) null else "3D 停用")
                                BeautySliderItem("美牙", beautyTeethWhitening, { beautyTeethWhitening = it; beautyPreset = "自定义"; keepUiAlight() }, accentColor = AccentColor, enabled = is2DBeautyMode, badge = if (is2DBeautyMode) null else "3D 停用")
                                BeautySliderItem("口红", beautyLipstick, { beautyLipstick = it; beautyPreset = "自定义"; keepUiAlight() }, accentColor = AccentColor, enabled = is2DBeautyMode, badge = if (is2DBeautyMode) null else "3D 停用")
                                BeautySliderItem("腮红", beautyBlush, { beautyBlush = it; beautyPreset = "自定义"; keepUiAlight() }, accentColor = AccentColor, enabled = is2DBeautyMode, badge = if (is2DBeautyMode) null else "3D 停用")
                                BeautySliderItem("眉毛", beautyEyebrows, { beautyEyebrows = it; beautyPreset = "自定义"; keepUiAlight() }, accentColor = AccentColor, enabled = is2DBeautyMode, badge = if (is2DBeautyMode) null else "3D 停用")
                                BeautySliderItem("长腿", beautyLongLegs, { beautyLongLegs = it; beautyPreset = "自定义"; keepUiAlight() }, accentColor = AccentColor, enabled = is2DBeautyMode, badge = if (is2DBeautyMode) null else "3D 停用")
                                BeautySliderItem("小头", beautySmallHead, { beautySmallHead = it; beautyPreset = "自定义"; keepUiAlight() }, accentColor = AccentColor, enabled = is2DBeautyMode, badge = if (is2DBeautyMode) null else "3D 停用")
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
                                        text = "记忆当前所有的微调参数",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "开启后退出重进时可恢复设置",
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
                                Text("确认并应用", color = AccentOnColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                                                    contentDescription = if (expanded) "收起" else "展开",
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

                                    // 左列分组（二级菜单）：主题 → 投影 → 8K → 倍速 → 解码 → 字幕
                                    SettingsGroup("主题与玻璃效果", "theme") { SettingsSection0() }
                                    SettingsGroup("镜头投影与视角", "proj") { SettingsSection1() }
                                    SettingsGroup("8K 硬解实验", "8k") { SettingsSection8K() }
                                    SettingsGroup("悬浮球与播放倍速", "ball") { SettingsSection3() }
                                    SettingsGroup("解码内核与帧率", "decode") { SettingsSection4() }
                                    SettingsGroup("字幕功能设置", "sub") { SettingsSectionSubtitle() }
                                    // v106：关于与开源许可（合规署名入口）
                                    SettingsGroup("关于与开源许可", "about") {
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
                                                    text = "开源软件许可",
                                                    color = Color.White.copy(alpha = 0.85f),
                                                    fontSize = 11.sp
                                                )
                                                Text(
                                                    text = "查看本项目使用的开源组件、字体与模型",
                                                    color = Color.White.copy(alpha = 0.45f),
                                                    fontSize = 9.sp
                                                )
                                            }
                                            Text(
                                                text = "查看",
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
                title = { Text("视频信息", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
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
                        Text("关闭", color = AccentColor)
                    }
                }
            )
        }

        // Audio / subtitle track selection dialog (8/2 功能)
        if (trackDialogOpen) {
            val groups = playerInstance?.currentTracks?.groups ?: emptyList()
            val audioGroup = groups.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }
            val textGroup = groups.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
            AlertDialog(
                onDismissRequest = { trackDialogOpen = false },
                containerColor = Color(0xFC18171C),
                title = { Text("音轨与字幕轨", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (audioGroup == null && textGroup == null) {
                            Text("当前媒体没有可切换的音轨/字幕轨", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                        }

                        audioGroup?.let { g ->
                            Text("音轨", color = AccentColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
                            Text("字幕轨（内嵌）", color = AccentColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
                                Text("关闭内嵌字幕", color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp)
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
                            text = "内嵌字幕与外部 SRT 均可显示；外部字幕优先于内嵌字幕",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 9.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { trackDialogOpen = false }) {
                        Text("关闭", color = AccentColor)
                    }
                }
            )
        }

        if (smbDialogOpen) {
            AlertDialog(
                onDismissRequest = { smbDialogOpen = false },
                containerColor = Color(0xFC18171C),
                title = { Text("局域网播放 (SMB)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
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
                            label = { Text("服务器地址 (IP 或主机名)", fontSize = 10.sp) },
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
                                label = { Text("用户名（可选）", fontSize = 9.sp) },
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
                                label = { Text("密码", fontSize = 9.sp) },
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
                                Text("连接", fontSize = 11.sp)
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
                                    Text("上级", fontSize = 10.sp, color = AccentColor)
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
                                            } else if (entry.name.endsWith(".mp4", true) ||
                                                entry.name.endsWith(".mkv", true) ||
                                                entry.name.endsWith(".avi", true) ||
                                                entry.name.endsWith(".mov", true) ||
                                                entry.name.endsWith(".webm", true)
                                            ) {
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
                        Text("关闭", color = AccentColor)
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
                    ballOffsetX = maxXPx - with(density) { 20.dp.toPx() }
                    ballOffsetY = maxYPx / 2f
                    isBallPositionInitialized = true
                }
            }

            val speedText = if (floatingBallSpeed == floatingBallSpeed.toInt().toFloat()) {
                "${floatingBallSpeed.toInt()}X"
            } else {
                "${floatingBallSpeed}X"
            }

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
                        if (isLiquidGlass) Modifier.drawBackdrop(
                            backdrop = liquidBackdrop,
                            shape = { CircleShape },
                            effects = {
                                vibrancy()
                                blur(12f.dp.toPx())
                                if (Build.VERSION.SDK_INT >= 33) {
                                    lens(8f.dp.toPx(), 16f.dp.toPx())
                                }
                            },
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
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // v103：点按/拖动悬浮球不点亮其他 UI（不再调用 keepUiAlight）
                            isFloatingBallPressed = true
                            showSpeedHud = true

                            var pointer = down.id
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointer }
                                if (change == null || !change.pressed) {
                                    isFloatingBallPressed = false
                                    showSpeedHud = false
                                    break
                                }
                                val dragAmount = change.positionChange()
                                if (dragAmount != Offset.Zero) {
                                    ballOffsetX = (ballOffsetX + dragAmount.x).coerceIn(0f, maxXPx.coerceAtLeast(0f))
                                    ballOffsetY = (ballOffsetY + dragAmount.y).coerceIn(0f, maxYPx.coerceAtLeast(0f))
                                    change.consume()
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
                        contentDescription = "倍速",
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
                            text = "长按快进中 $speedText",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * v92: 带长按提示的图标按钮（控制栏统一组件）
 * 长按显示功能名称 Tooltip；选中态背景色 0x55 对比度高于原 0x33。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipIconButton(
    tooltip: String,
    onClick: () -> Unit,
    icon: ImageVector,
    iconSize: Dp = 20.dp,
    isActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    val tooltipState = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = tooltipState
    ) {
        IconButton(
            onClick = onClick,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (isActive) Color(0x55D0BCFF) else Color.White.copy(alpha = 0.08f),
                contentColor = if (isActive) Color(0xFFD0BCFF) else Color.White
            ),
            modifier = modifier.size(40.dp)
        ) {
            Icon(imageVector = icon, contentDescription = tooltip, modifier = Modifier.size(iconSize))
        }
    }
}

@Composable
fun BeautySliderItem(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    accentColor: Color = Color(0xFFD0BCFF),
    enabled: Boolean = true,
    badge: String? = null
) {
    Column(modifier = if (enabled) Modifier else Modifier.alpha(0.35f)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (badge != null) {
                    Text(badge, color = Color(0xFFE8A33D), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Text("${(value * 100).toInt()}%", color = accentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = Color.White.copy(alpha = 0.15f)
            ),
            modifier = Modifier.height(26.dp)
        )
    }
}

// Row for the experimental 8K hardware-decode switches (all off by default) (8/2 功能)
@Composable
fun ExperimentalSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onChanged: (Boolean) -> Unit,
    accentColor: Color,
    accentOnColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = desc,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 9.sp,
                lineHeight = 12.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChanged,
            colors = SwitchDefaults.colors(
                checkedThumbColor = accentOnColor,
                checkedTrackColor = accentColor,
                uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
            ),
            modifier = Modifier.height(26.dp)
        )
    }
}

class StereoChannelSwappingAudioProcessor : androidx.media3.common.audio.BaseAudioProcessor() {
    @Volatile
    var isSwappingEnabled = false

    override fun onConfigure(inputAudioFormat: androidx.media3.common.audio.AudioProcessor.AudioFormat): androidx.media3.common.audio.AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) {
            throw androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount != 2) {
            return androidx.media3.common.audio.AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val outputBuffer = replaceOutputBuffer(remaining)

        if (isSwappingEnabled) {
            // PCM_16BIT stereo: 4 bytes per frame (Left 2 bytes, Right 2 bytes)
            while (inputBuffer.remaining() >= 4) {
                val l0 = inputBuffer.get()
                val l1 = inputBuffer.get()
                val r0 = inputBuffer.get()
                val r1 = inputBuffer.get()

                // Swap Left and Right channels
                outputBuffer.put(r0)
                outputBuffer.put(r1)
                outputBuffer.put(l0)
                outputBuffer.put(l1)
            }
            // Put residue bytes if any
            while (inputBuffer.hasRemaining()) {
                outputBuffer.put(inputBuffer.get())
            }
        } else {
            outputBuffer.put(inputBuffer)
        }
        outputBuffer.flip()
    }
}

