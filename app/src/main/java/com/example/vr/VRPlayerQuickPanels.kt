package com.example.vr

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * 字幕与投屏快捷悬浮面板（从 VRPlayerScreen.kt 拆出）。
 */
@Composable
internal fun BoxScope.PlayerSubtitleQuickPanel(
    context: Context,
    prefs: SharedPreferences,
    isSubtitleQuickPanelOpen: Boolean,
    onClosePanel: () -> Unit,
    themePanelBgColor: Color,
    accentColor: Color,
    accentOnColor: Color,
    isMemoryModeEnabled: Boolean,
    isSubtitleEnabled: Boolean,
    onSubtitleEnabledChange: (Boolean) -> Unit,
    subtitleTranslator: SubtitleTranslator,
    castReceiverEnabled: Boolean,
    onCastReceiverEnabledChange: (Boolean) -> Unit,
    sherpaLangCode: String,
    onChangeAsrLanguage: (String) -> Unit,
    asrThreads: Int,
    onAsrThreadsChange: (Int) -> Unit,
    isRealtimeAsrActive: Boolean,
    realtimeSubtitleEngine: RealtimeSubtitleEngine,
    isRealtimeSubtitleEnabled: Boolean,
    onRealtimeSubtitleEnabledChange: (Boolean) -> Unit,
    bgAsrEnabled: Boolean,
    onBgAsrEnabledChange: (Boolean) -> Unit,
    realtimeSubtitleStatus: String,
    realtimeDone: Boolean,
    realtimeTotalMs: Long,
    realtimeGeneratedMs: Long,
    onRegenerateRealtimeSubtitle: () -> Unit,
    onExportSubtitleSrt: () -> Unit,
    selectedMediaItem: MediaItem,
    loadedSubtitleFileName: String,
    onLoadSavedSubtitleFile: (File) -> Unit,
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
    liveDelaySec: Int,
    onLiveDelaySecChange: (Int) -> Unit,
    isLiveStream: Boolean,
    subtitleTextAlignOpt: SubtitleAlignOption,
    onTextAlignChange: (SubtitleAlignOption) -> Unit,
    subtitleMaxLines: Int,
    onMaxLinesChange: (Int) -> Unit,
    vrIpdOffsetRatio: Float,
    onVrIpdOffsetRatioChange: (Float) -> Unit,
    onOpenFullSubtitleSettings: () -> Unit,
    onKeepUiAlight: () -> Unit,
) {
    if (!isSubtitleQuickPanelOpen) return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClosePanel(); onKeepUiAlight() },
        contentAlignment = Alignment.TopEnd
    ) {
        Surface(
            color = themePanelBgColor,
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
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val viewportH = minOf(maxHeight, QUICK_PANEL_VIEWPORT_DP)
                val quickPanelScroll = rememberScrollState()
                val density = LocalDensity.current
                Column(
                    modifier = Modifier
                        .heightIn(max = viewportH)
                        .verticalScroll(quickPanelScroll)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.subtitle_quick_title),
                            color = accentColor,
                            fontSize = 14.sp,
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
                            AuraSwitch(
                                checked = isSubtitleEnabled,
                                onCheckedChange = {
                                    onSubtitleEnabledChange(it)
                                    if (isMemoryModeEnabled) {
                                        prefs.edit().putBoolean("subtitle_user_disabled", !it).apply()
                                    }
                                    onKeepUiAlight()
                                },
                                accentColor = accentColor,
                                accentOnColor = accentOnColor
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                stringResource(R.string.subtitle_translate_title),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                text = if (subtitleTranslator.config.isEnabled)
                                    "${stringResource(subtitleTranslator.config.engine.displayNameResId)} → ${stringResource(subtitleTranslator.config.targetLanguage.nameResId)}"
                                else stringResource(R.string.subtitle_translate_off),
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 11.sp
                            )
                        }
                        AuraSwitch(
                            checked = subtitleTranslator.config.isEnabled,
                            onCheckedChange = {
                                subtitleTranslator.config = subtitleTranslator.config.copy(isEnabled = it)
                                if (isMemoryModeEnabled) prefs.edit().putBoolean("translation_enabled", it).apply()
                                onKeepUiAlight()
                            },
                            accentColor = accentColor,
                            accentOnColor = accentOnColor
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                stringResource(R.string.cast_receiver_title),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                text = stringResource(
                                    if (castReceiverEnabled) R.string.cast_receiver_on
                                    else R.string.cast_receiver_off
                                ),
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 11.sp
                            )
                        }
                        AuraSwitch(
                            checked = castReceiverEnabled,
                            onCheckedChange = {
                                onCastReceiverEnabledChange(it)
                                if (isMemoryModeEnabled) {
                                    prefs.edit().putBoolean("cast_receiver_enabled", it).apply()
                                }
                                onKeepUiAlight()
                            },
                            accentColor = accentColor,
                            accentOnColor = accentOnColor
                        )
                    }

                    BatchTranscribeSection(
                        accentColor = accentColor,
                        accentOnColor = accentOnColor,
                        sherpaLangCode = sherpaLangCode,
                        onSherpaLangCodeChange = onChangeAsrLanguage,
                        onUserInteraction = onKeepUiAlight,
                        senseVoiceFirst = true
                    )

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
                                color = if (asrThreads in SherpaAsrManager.RECOMMENDED_THREADS) accentColor
                                else Color(0xFFFFB74D),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Slider(
                            value = asrThreads.toFloat(),
                            onValueChange = { v ->
                                onAsrThreadsChange(v.toInt().coerceIn(SherpaAsrManager.MIN_THREADS, SherpaAsrManager.MAX_THREADS))
                            },
                            onValueChangeFinished = {
                                if (isMemoryModeEnabled) {
                                    prefs.edit().putInt("asr_threads", asrThreads).apply()
                                }
                                if (isRealtimeAsrActive) realtimeSubtitleEngine.restart()
                                onKeepUiAlight()
                            },
                            valueRange = SherpaAsrManager.MIN_THREADS.toFloat()..SherpaAsrManager.MAX_THREADS.toFloat(),
                            steps = SherpaAsrManager.MAX_THREADS - SherpaAsrManager.MIN_THREADS - 1,
                            colors = SliderDefaults.colors(
                                thumbColor = accentColor,
                                activeTrackColor = accentColor,
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

                    ExperimentalSwitchRow(
                        title = stringResource(R.string.realtime_subtitle_title),
                        desc = stringResource(R.string.realtime_subtitle_desc),
                        checked = isRealtimeSubtitleEnabled,
                        onChanged = {
                            onRealtimeSubtitleEnabledChange(it)
                            if (isMemoryModeEnabled) {
                                prefs.edit().putBoolean("realtime_subtitle_enabled", it).apply()
                            }
                            onKeepUiAlight()
                        },
                        accentColor = accentColor,
                        accentOnColor = accentOnColor
                    )

                    ExperimentalSwitchRow(
                        title = stringResource(R.string.bg_asr_title),
                        desc = stringResource(R.string.bg_asr_desc),
                        checked = bgAsrEnabled,
                        onChanged = {
                            onBgAsrEnabledChange(it)
                            if (isMemoryModeEnabled) {
                                prefs.edit().putBoolean("bg_asr_enabled", it).apply()
                            }
                            onKeepUiAlight()
                        },
                        accentColor = accentColor,
                        accentOnColor = accentOnColor
                    )

                    if (isRealtimeSubtitleEnabled && realtimeSubtitleStatus.isNotBlank()) {
                        Text(
                            text = realtimeSubtitleStatus,
                            color = if (realtimeDone) accentColor else Color.White.copy(alpha = 0.6f),
                            fontSize = 9.sp,
                            lineHeight = 12.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                    }

                    if (isRealtimeSubtitleEnabled && !realtimeDone && realtimeTotalMs > 0L) {
                        LinearProgressIndicator(
                            progress = {
                                val total = realtimeTotalMs
                                if (total <= 0L) 0f
                                else (realtimeGeneratedMs.toFloat() / total).coerceIn(0f, 1f)
                            },
                            color = accentColor,
                            trackColor = Color.White.copy(alpha = 0.12f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .padding(start = 4.dp, end = 4.dp)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(accentColor.copy(alpha = 0.85f))
                                .clickable {
                                    onKeepUiAlight()
                                    onRegenerateRealtimeSubtitle()
                                }
                                .padding(vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.subtitle_regenerate),
                                color = accentOnColor,
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
                                    onKeepUiAlight()
                                    onExportSubtitleSrt()
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

                    var savedSubtitleFiles by remember { mutableStateOf<List<File>>(emptyList()) }
                    LaunchedEffect(isSubtitleQuickPanelOpen, selectedMediaItem.uri) {
                        if (!isSubtitleQuickPanelOpen) return@LaunchedEffect
                        val base = SubtitleExporter.safeBaseName(selectedMediaItem.title)
                        savedSubtitleFiles = withContext(Dispatchers.IO) {
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
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isRealtimeSubtitleEnabled) accentColor.copy(alpha = 0.30f)
                                    else Color.White.copy(alpha = 0.08f)
                                )
                                .clickable {
                                    onKeepUiAlight()
                                    onRealtimeSubtitleEnabledChange(true)
                                    if (isMemoryModeEnabled) {
                                        prefs.edit().putBoolean("realtime_subtitle_enabled", true).apply()
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(
                                stringResource(R.string.subtitle_source_realtime),
                                color = if (isRealtimeSubtitleEnabled) accentColor else Color.White,
                                fontSize = 10.sp,
                                fontWeight = if (isRealtimeSubtitleEnabled) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        savedSubtitleFiles.forEach { f ->
                            val selected = !isRealtimeSubtitleEnabled && loadedSubtitleFileName == f.name
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (selected) accentColor.copy(alpha = 0.30f)
                                        else Color.White.copy(alpha = 0.08f)
                                    )
                                    .clickable {
                                        onKeepUiAlight()
                                        onLoadSavedSubtitleFile(f)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    f.name,
                                    color = if (selected) accentColor else Color.White.copy(alpha = 0.85f),
                                    fontSize = 10.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    var styleSecExpanded by remember { mutableStateOf(false) }
                    var layoutSecExpanded by remember { mutableStateOf(false) }
                    SubtitleStyleSettings(
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
                        liveDelaySec = liveDelaySec,
                        onLiveDelaySecChange = onLiveDelaySecChange,
                        isLiveStream = isLiveStream,
                        textAlign = subtitleTextAlignOpt,
                        onTextAlignChange = onTextAlignChange,
                        maxLines = subtitleMaxLines,
                        onMaxLinesChange = onMaxLinesChange,
                        vrIpdOffsetRatio = vrIpdOffsetRatio,
                        onVrIpdOffsetRatioChange = onVrIpdOffsetRatioChange,
                        accentColor = accentColor,
                        accentOnColor = accentOnColor,
                        onUserActivity = onKeepUiAlight,
                        styleExpanded = styleSecExpanded,
                        onStyleExpandedChange = { styleSecExpanded = it },
                        layoutExpanded = layoutSecExpanded,
                        onLayoutExpandedChange = { layoutSecExpanded = it }
                    )

                    TextButton(
                        onClick = {
                            onOpenFullSubtitleSettings()
                            onKeepUiAlight()
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(
                            stringResource(R.string.subtitle_open_full_settings),
                            color = accentColor,
                            fontSize = 10.sp
                        )
                    }
                }

                if (quickPanelScroll.maxValue > 0) {
                    val viewH = with(density) { viewportH.toPx() }
                    val totalH = quickPanelScroll.maxValue + viewH
                    val thumbRatio = (viewH / totalH).coerceIn(0.15f, 1f)
                    val progress = quickPanelScroll.value.toFloat() /
                        quickPanelScroll.maxValue.toFloat().coerceAtLeast(1f)
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .height(viewportH)
                            .padding(vertical = 6.dp, horizontal = 2.dp)
                            .width(7.dp),
                        contentAlignment = Alignment.TopEnd
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.10f))
                        )
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
                                .background(accentColor.copy(alpha = 0.75f))
                        )
                    }
                }
            }
        }
    }
}
