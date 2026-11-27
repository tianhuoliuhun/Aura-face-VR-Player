package com.example.vr

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 字幕设置分组（从 VRPlayerScreen.kt 拆出）。
 */
@Composable
internal fun PlayerSettingsSubtitleSection(
    context: Context,
    prefs: SharedPreferences,
    scope: CoroutineScope,
    selectedMediaItem: MediaItem,
    isMemoryModeEnabled: Boolean,
    isSubtitleEnabled: Boolean,
    onSubtitleEnabledChange: (Boolean) -> Unit,
    isStripSubtitlePunctuation: Boolean,
    onStripSubtitlePunctuationChange: (Boolean) -> Unit,
    subtitleFadeIn: Boolean,
    onFadeInChange: (Boolean) -> Unit,
    loadedSubtitleFileName: String,
    loadedSubtitleCues: List<SubtitleCue>,
    onSubtitleCuesLoaded: (List<SubtitleCue>, String) -> Unit,
    onPickSubtitleFile: () -> Unit,
    subtitleFont: SubtitleFont,
    onFontChange: (SubtitleFont) -> Unit,
    subtitleFontSizeSp: Int,
    onFontSizeChange: (Int) -> Unit,
    subtitleFontWeightVal: Int,
    onFontWeightChange: (Int) -> Unit,
    isSubtitleItalic: Boolean,
    onItalicChange: (Boolean) -> Unit,
    subtitleColorOpt: SubtitleColorOption,
    onColorOptionChange: (SubtitleColorOption) -> Unit,
    subtitleTextAlpha: Float,
    onTextAlphaChange: (Float) -> Unit,
    subtitleStrokeOpt: SubtitleStrokeOption,
    onStrokeOptionChange: (SubtitleStrokeOption) -> Unit,
    subtitleBgOpt: SubtitleBgOption,
    onBgOptionChange: (SubtitleBgOption) -> Unit,
    subtitleOffsetYRatio: Float,
    onOffsetYRatioChange: (Float) -> Unit,
    subtitleOffsetXRatio: Float,
    onOffsetXRatioChange: (Float) -> Unit,
    subtitleDelayMs: Long,
    onDelayMsChange: (Long) -> Unit,
    subtitleTextAlignOpt: SubtitleAlignOption,
    onTextAlignChange: (SubtitleAlignOption) -> Unit,
    subtitleMaxLines: Int,
    onMaxLinesChange: (Int) -> Unit,
    vrIpdOffsetRatio: Float,
    onVrIpdOffsetRatioChange: (Float) -> Unit,
    subtitleSearchApiKey: String,
    onSubtitleSearchApiKeyChange: (String) -> Unit,
    isGenderFilterEnabled: Boolean,
    onGenderFilterEnabledChange: (Boolean) -> Unit,
    genderFilterProcessor: GenderFilterAudioProcessor,
    playerInstance: VrPlayerBackend?,
    subtitleTranslator: SubtitleTranslator,
    onExportSubtitle: () -> Unit,
    accentColor: Color,
    accentOnColor: Color,
    onKeepUiAlight: () -> Unit,
) {
    SubtitleSettingsPanel(
        audioGenderMediaUri = selectedMediaItem.uri,
        isSubtitleEnabled = isSubtitleEnabled,
        onSubtitleEnabledChange = {
            onSubtitleEnabledChange(it)
            if (isMemoryModeEnabled) {
                prefs.edit().putBoolean("subtitle_user_disabled", !it).apply()
            }
        },
        stripPunctuation = isStripSubtitlePunctuation,
        onStripPunctuationChange = onStripSubtitlePunctuationChange,
        fadeIn = subtitleFadeIn,
        onFadeInChange = onFadeInChange,
        loadedSubtitleFileName = loadedSubtitleFileName,
        loadedCueCount = loadedSubtitleCues.size,
        onPickSubtitleFile = onPickSubtitleFile,
        subtitleFont = subtitleFont,
        onFontChange = onFontChange,
        fontSizeSp = subtitleFontSizeSp,
        onFontSizeChange = onFontSizeChange,
        fontWeightVal = subtitleFontWeightVal,
        onFontWeightChange = onFontWeightChange,
        isItalic = isSubtitleItalic,
        onItalicChange = onItalicChange,
        selectedColorOption = subtitleColorOpt,
        onColorOptionChange = onColorOptionChange,
        textAlpha = subtitleTextAlpha,
        onTextAlphaChange = onTextAlphaChange,
        selectedStrokeOption = subtitleStrokeOpt,
        onStrokeOptionChange = onStrokeOptionChange,
        selectedBgOption = subtitleBgOpt,
        onBgOptionChange = onBgOptionChange,
        offsetYRatio = subtitleOffsetYRatio,
        onOffsetYRatioChange = onOffsetYRatioChange,
        offsetXRatio = subtitleOffsetXRatio,
        onOffsetXRatioChange = onOffsetXRatioChange,
        delayMs = subtitleDelayMs,
        onDelayMsChange = onDelayMsChange,
        textAlign = subtitleTextAlignOpt,
        onTextAlignChange = onTextAlignChange,
        maxLines = subtitleMaxLines,
        onMaxLinesChange = onMaxLinesChange,
        vrIpdOffsetRatio = vrIpdOffsetRatio,
        onVrIpdOffsetRatioChange = onVrIpdOffsetRatioChange,
        subtitleSearchApiKey = subtitleSearchApiKey,
        onSubtitleSearchApiKeyChange = {
            onSubtitleSearchApiKeyChange(it)
            prefs.edit().putString("subtitle_search_api_key", it).apply()
        },
        defaultSearchQuery = selectedMediaItem.title,
        isGenderFilterEnabled = isGenderFilterEnabled,
        onGenderFilterEnabledChange = { on ->
            onGenderFilterEnabledChange(on)
            genderFilterProcessor.isFilterEnabled = on
            if (on) {
                playerInstance?.currentPosition?.let {
                    genderFilterProcessor.updatePlaybackPositionMs(it, forceResync = true)
                }
            }
        },
        onGenderFilterWavReady = { wav ->
            genderFilterProcessor.setFilteredWav(wav)
            playerInstance?.currentPosition?.let {
                genderFilterProcessor.updatePlaybackPositionMs(it, forceResync = true)
            }
        },
        onSubtitleFileLoaded = { file: File ->
            scope.launch(Dispatchers.IO) {
                try {
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
                        onSubtitleCuesLoaded(cues, file.name)
                        onSubtitleEnabledChange(true)
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
            if (isMemoryModeEnabled) {
                prefs.edit().putBoolean("translation_enabled", enabled).apply()
            }
        },
        onTranslateFileRequested = { subtitleTranslator.translateCuesBatch(loadedSubtitleCues) },
        onExportSubtitle = onExportSubtitle,
        accentColor = accentColor,
        accentOnColor = accentOnColor,
        onUserActivity = onKeepUiAlight,
    )
}

/**
 * AI 弹幕设置分组（从 VRPlayerScreen.kt 拆出）。
 */
@Composable
internal fun PlayerSettingsDanmuSection(
    danmuConfig: DanmuConfig,
    onConfigChange: (DanmuConfig) -> Unit,
    canPersistSecrets: Boolean,
    generatedCount: Int,
    lastError: String,
    accentColor: Color,
    importedCount: Int,
    importedName: String,
    importStatus: DanmuImportStore.LoadResult?,
    onPickImportFile: () -> Unit,
) {
    DanmuSettingsPanel(
        config = danmuConfig,
        onConfigChange = onConfigChange,
        canPersistSecrets = canPersistSecrets,
        generatedCount = generatedCount,
        lastError = lastError,
        accentColor = accentColor,
        importedCount = importedCount,
        importedName = importedName,
        importStatus = importStatus,
        onPickImportFile = onPickImportFile,
    )
}

/**
 * 美颜设置右列分组（从 VRPlayerScreen.kt 拆出）。
 */
@Composable
internal fun PlayerSettingsBeautySection(
    accentColor: Color,
    accentOnColor: Color,
    projectionMode: ProjectionMode,
    beautyMasterEnabled: Boolean,
    onBeautyMasterEnabledChange: (Boolean) -> Unit,
    beautyEngineType: Int,
    onBeautyEngineTypeChange: (Int) -> Unit,
    gpuPixelVrFaceBeauty: Boolean,
    onGpuPixelVrFaceBeautyChange: (Boolean) -> Unit,
    gpuPixelHalfResBeauty: Boolean,
    onGpuPixelHalfResBeautyChange: (Boolean) -> Unit,
    beautyPreset: String,
    onBeautyPresetChange: (String) -> Unit,
    beautyLevel: Float,
    onBeautyLevelChange: (Float) -> Unit,
    beautyTextureDetail: Float,
    onBeautyTextureDetailChange: (Float) -> Unit,
    brightnessLevel: Float,
    onBrightnessLevelChange: (Float) -> Unit,
    contrastLevel: Float,
    onContrastLevelChange: (Float) -> Unit,
    beautyWhitening: Float,
    onBeautyWhiteningChange: (Float) -> Unit,
    lutName: String,
    onLutNameChange: (String) -> Unit,
    lutMix: Float,
    onLutMixChange: (Float) -> Unit,
    isLutLoading: Boolean,
    onLutLoadingChange: (Boolean) -> Unit,
    currentGlSurfaceView: VRGLSurfaceView?,
    onPickCustomLut: () -> Unit,
    beautyFaceSlimming: Float,
    onBeautyFaceSlimmingChange: (Float) -> Unit,
    beautyBigEyes: Float,
    onBeautyBigEyesChange: (Float) -> Unit,
    beautyDarkCircles: Float,
    onBeautyDarkCirclesChange: (Float) -> Unit,
    beautyNoseSlimming: Float,
    onBeautyNoseSlimmingChange: (Float) -> Unit,
    beautyMouth: Float,
    onBeautyMouthChange: (Float) -> Unit,
    beautyTeethWhitening: Float,
    onBeautyTeethWhiteningChange: (Float) -> Unit,
    beautyLipstick: Float,
    onBeautyLipstickChange: (Float) -> Unit,
    beautyBlush: Float,
    onBeautyBlushChange: (Float) -> Unit,
    beautyEyebrows: Float,
    onBeautyEyebrowsChange: (Float) -> Unit,
    beautyLongLegs: Float,
    onBeautyLongLegsChange: (Float) -> Unit,
    beautySmallHead: Float,
    onBeautySmallHeadChange: (Float) -> Unit,
    onKeepUiAlight: () -> Unit,
) {
    fun applyBeautyPreset(presetId: String) {
        onBeautyPresetChange(presetId)
        val p = when (presetId) {
            BEAUTY_PRESET_NATURAL -> floatArrayOf(0.4f, 0.3f, 0.2f, 0.15f, 0.15f, 0.1f, 0.1f, 0.2f, 0.15f, 0.15f, 0.3f, 0.1f, 0.1f)
            BEAUTY_PRESET_LIGHT -> floatArrayOf(0.6f, 0.5f, 0.4f, 0.3f, 0.3f, 0.2f, 0.2f, 0.3f, 0.35f, 0.35f, 0.45f, 0.2f, 0.2f)
            BEAUTY_PRESET_HEAVY -> floatArrayOf(0.9f, 0.8f, 0.7f, 0.6f, 0.5f, 0.35f, 0.35f, 0.5f, 0.6f, 0.6f, 0.7f, 0.4f, 0.35f)
            else -> return
        }
        onBeautyLevelChange(p[0]); onBeautyWhiteningChange(p[1]); onBeautyFaceSlimmingChange(p[2]); onBeautyBigEyesChange(p[3])
        onBeautyDarkCirclesChange(p[4]); onBeautyNoseSlimmingChange(p[5]); onBeautyMouthChange(p[6]); onBeautyTeethWhiteningChange(p[7])
        onBeautyLipstickChange(p[8]); onBeautyBlushChange(p[9]); onBeautyEyebrowsChange(p[10]); onBeautyLongLegsChange(p[11]); onBeautySmallHeadChange(p[12])
        onKeepUiAlight()
    }

    Text(
        text = stringResource(R.string.settings_group_beauty),
        color = accentColor,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold
    )

    val is2DBeautyMode = projectionMode == ProjectionMode.STANDARD ||
        projectionMode == ProjectionMode.FISHEYE

    BeautyModeHintBar(is2DMode = is2DBeautyMode, modeName = stringResource(projectionMode.labelRes))

    BeautyCompareSwitch(
        checked = beautyMasterEnabled,
        onCheckedChange = { onBeautyMasterEnabledChange(it); onKeepUiAlight() },
        accentColor = accentColor,
        accentOnColor = accentOnColor
    )

    val engineContext = LocalContext.current
    BeautyEngineSection(
        accentColor = accentColor,
        engineType = beautyEngineType,
        onEngineChange = { type ->
            if (type == BEAUTY_ENGINE_GPUPIXEL && !GpuPixelBeauty.available && !GpuPixelBeauty.init(engineContext, force = true)) {
                Toast.makeText(
                    engineContext,
                    engineContext.getString(R.string.beauty_engine_unavailable),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                onBeautyEngineTypeChange(type)
                onKeepUiAlight()
            }
        },
        vrFace = gpuPixelVrFaceBeauty,
        onVrFaceChange = { onGpuPixelVrFaceBeautyChange(it); onKeepUiAlight() },
        halfRes = gpuPixelHalfResBeauty,
        onHalfResChange = { onGpuPixelHalfResBeautyChange(it); onKeepUiAlight() }
    )

    BeautyPresetRow(
        presetId = beautyPreset,
        onPresetChange = { applyBeautyPreset(it) },
        accentColor = accentColor
    )

    GeneralBeautySection(
        accentColor = accentColor,
        beautyLevel = beautyLevel,
        onBeautyLevelChange = { onBeautyLevelChange(it); onBeautyPresetChange(BEAUTY_PRESET_CUSTOM); onKeepUiAlight() },
        textureDetail = beautyTextureDetail,
        onTextureDetailChange = { onBeautyTextureDetailChange(it); onBeautyPresetChange(BEAUTY_PRESET_CUSTOM); onKeepUiAlight() },
        brightnessLevel = brightnessLevel,
        onBrightnessLevelChange = { onBrightnessLevelChange(it); onBeautyPresetChange(BEAUTY_PRESET_CUSTOM); onKeepUiAlight() },
        contrastLevel = contrastLevel,
        onContrastLevelChange = { onContrastLevelChange(it); onBeautyPresetChange(BEAUTY_PRESET_CUSTOM); onKeepUiAlight() },
        whiteningLevel = beautyWhitening,
        onWhiteningLevelChange = { onBeautyWhiteningChange(it); onBeautyPresetChange(BEAUTY_PRESET_CUSTOM); onKeepUiAlight() }
    )

    LutFilterSection(
        accentColor = accentColor,
        lutName = lutName,
        onLutNameChange = onLutNameChange,
        lutMix = lutMix,
        onLutMixChange = { onLutMixChange(it); currentGlSurfaceView?.renderer?.lutMix = it; onKeepUiAlight() },
        isLutLoading = isLutLoading,
        onLutLoadingChange = onLutLoadingChange,
        onApplyLutRgba = { currentGlSurfaceView?.renderer?.setLutTexture(it) },
        onPickCustomLut = onPickCustomLut,
        onUserInteraction = onKeepUiAlight
    )

    if (beautyEngineType == BEAUTY_ENGINE_GPUPIXEL) {
        Text(
            text = stringResource(R.string.beauty_engine_gp_limited),
            color = Color(0xFFE0A030),
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
    }

    PortraitRetouchSection(
        accentColor = accentColor,
        enabled = is2DBeautyMode,
        params = listOf(
            PortraitParam(stringResource(R.string.beauty_face_slim), beautyFaceSlimming) { onBeautyFaceSlimmingChange(it); onBeautyPresetChange(BEAUTY_PRESET_CUSTOM); onKeepUiAlight() },
            PortraitParam(stringResource(R.string.beauty_big_eyes), beautyBigEyes) { onBeautyBigEyesChange(it); onBeautyPresetChange(BEAUTY_PRESET_CUSTOM); onKeepUiAlight() },
            PortraitParam(stringResource(R.string.beauty_dark_circles), beautyDarkCircles) { onBeautyDarkCirclesChange(it); onBeautyPresetChange(BEAUTY_PRESET_CUSTOM); onKeepUiAlight() },
            PortraitParam(stringResource(R.string.beauty_nose_slim), beautyNoseSlimming) { onBeautyNoseSlimmingChange(it); onBeautyPresetChange(BEAUTY_PRESET_CUSTOM); onKeepUiAlight() },
            PortraitParam(stringResource(R.string.beauty_mouth), beautyMouth) { onBeautyMouthChange(it); onBeautyPresetChange(BEAUTY_PRESET_CUSTOM); onKeepUiAlight() },
            PortraitParam(stringResource(R.string.beauty_teeth), beautyTeethWhitening) { onBeautyTeethWhiteningChange(it); onBeautyPresetChange(BEAUTY_PRESET_CUSTOM); onKeepUiAlight() },
            PortraitParam(stringResource(R.string.beauty_lipstick), beautyLipstick) { onBeautyLipstickChange(it); onBeautyPresetChange(BEAUTY_PRESET_CUSTOM); onKeepUiAlight() },
            PortraitParam(stringResource(R.string.beauty_blush), beautyBlush) { onBeautyBlushChange(it); onBeautyPresetChange(BEAUTY_PRESET_CUSTOM); onKeepUiAlight() },
            PortraitParam(stringResource(R.string.beauty_eyebrows), beautyEyebrows) { onBeautyEyebrowsChange(it); onBeautyPresetChange(BEAUTY_PRESET_CUSTOM); onKeepUiAlight() },
            PortraitParam(stringResource(R.string.beauty_long_legs), beautyLongLegs) { onBeautyLongLegsChange(it); onBeautyPresetChange(BEAUTY_PRESET_CUSTOM); onKeepUiAlight() },
            PortraitParam(stringResource(R.string.beauty_small_head), beautySmallHead) { onBeautySmallHeadChange(it); onBeautyPresetChange(BEAUTY_PRESET_CUSTOM); onKeepUiAlight() }
        )
    )
}
