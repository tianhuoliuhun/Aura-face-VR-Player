package com.example.vr

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay


@Composable
fun SubtitleOverlay(
    currentPositionMs: Long,
    subtitleCues: List<SubtitleCue>,
    showTranslation: Boolean = true,
    strokeWidthDp: Float = -1f,
    translator: SubtitleTranslator? = null,
    isSubtitleEnabled: Boolean = true,
    subtitleFont: SubtitleFont = SubtitleFont.SYSTEM,
    fontSizeSp: Int = 22,
    fontWeightVal: Int = 400,
    isItalic: Boolean = false,
    textColor: Color = Color.White,
    textAlpha: Float = 1.0f,
    strokeOption: SubtitleStrokeOption = SubtitleStrokeOption.MEDIUM_BLACK,
    bgOption: SubtitleBgOption = SubtitleBgOption.SEMI_BLACK,
    offsetYRatio: Float = 0.12f,
    offsetXRatio: Float = 0.0f,
    subtitleDelayMs: Long = 0L,
    textAlign: TextAlign = TextAlign.Center,
    maxLines: Int = 2,
    isSplitScreenVR: Boolean = false,
    vrIpdOffsetRatio: Float = 0.0f,
    exoCueText: String? = null, // Fallback for ExoPlayer embedded cues
    /** v2.0.154：显示时去除标点（原文与译文都去；内部原始文本不受影响） */
    stripPunctuation: Boolean = true,
    /**
     * v2.6.24：字幕文本切换的 160ms 淡入。**默认关闭** —— 直播/密集换行场景下淡入会拖影。
     */
    enableFadeIn: Boolean = false,
    /**
     * v2.6.24（直播 P2）：直播态。开启后翻译走"尾随模式"（不预读、不落盘、超时丢弃），
     * 由本组件把当前 cue 的过期时刻写进 [SubtitleTranslator.liveCueDeadlineMs]。
     */
    isLiveStream: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (!isSubtitleEnabled) return

    val fontFamily = SubtitleFontHelper.getFontFamily(subtitleFont, fontWeightVal, isItalic)
    val italicFont = SubtitleFontHelper.getFontFamily(subtitleFont, fontWeightVal, true)
    // Caption panel position is controlled exclusively by the settings sliders
    // (offsetX/offsetY). The only runtime gesture kept is pinch-zoom. State is
    // hoisted above block() so both VR eyes share the same zoom.
    var panelZoom by remember { mutableFloatStateOf(1f) }
    fun transformPanelModifier(): Modifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                if (pressed > 1) {
                    panelZoom = (panelZoom * event.calculateZoom()).coerceIn(0.55f, 2.4f)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    val verticalBias = (1.0f - 2.0f * offsetYRatio).coerceIn(-0.95f, 0.95f)
    val scale = if (isSplitScreenVR) 0.85f else 1f
    val effectiveStrokeWidth = if (strokeWidthDp > 0f) strokeWidthDp else strokeOption.widthDp


    // ===== Loaded SRT / embedded cue path =====
    // Calculate raw active cue text based on current position (or ExoPlayer fallback).
    val rawCueText by remember(
        currentPositionMs,
        subtitleDelayMs,
        subtitleCues,
        exoCueText
    ) {
        derivedStateOf {
            if (subtitleCues.isNotEmpty()) {
                val lookupTime = (currentPositionMs + subtitleDelayMs).coerceAtLeast(0L)
                val cue = subtitleCues.firstOrNull { lookupTime in it.startTimeMs..it.endTimeMs }
                cue?.text ?: ""
            } else {
                exoCueText ?: ""
            }
        }
    }

    if (rawCueText.isBlank()) return

    // Apply translation if enabled.
    var translatedText by remember(rawCueText) { mutableStateOf<String?>(null) }
    // v2.0.155：key 精确到「真正影响翻译结果」的字段。
    // 原先以整个 config（data class）为 key —— 任何设置改动都会重启本 effect，
    // 把当前这条字幕重新翻一遍（哪怕改的只是与翻译无关的项）。
    LaunchedEffect(
        rawCueText,
        isLiveStream,
        translator?.config?.isEnabled,
        translator?.config?.engine,
        translator?.config?.targetLanguage,
        translator?.config?.displayMode
    ) {
        if (translator != null) {
            // v2.6.24（直播 P2）：直播下给本条 cue 一个"过期时刻"（现在 + 剩余显示时长 + 2s 宽限）。
            // 翻译器拿到结果时若已过期就直接丢弃：直播里"这句说完了译文才到"只会和下一句抢屏。
            // ⚠️ 模式位 [SubtitleTranslator.liveMode] 由 VRPlayerScreen 统一设置（权威），
            //    这里只写"本条 cue 何时过期" —— 同一状态两处登记必然失步。
            translator.liveCueDeadlineMs = if (isLiveStream) {
                val now = currentPositionMs + subtitleDelayMs
                val activeCue = subtitleCues.firstOrNull { now in it.startTimeMs..it.endTimeMs }
                val remainMs = activeCue?.let { (it.endTimeMs - now).coerceAtLeast(0L) } ?: 0L
                System.currentTimeMillis() + remainMs + 2_000L
            } else {
                0L
            }
            translatedText = translator.translateOrOriginal(
                rawCueText,
                onTranslated = { result -> translatedText = result }
            )
        } else {
            translatedText = rawCueText
        }
    }
    val mergedCueText = translatedText ?: rawCueText
    // v2.0.154：只在「即将渲染」时去标点 —— rawCueText 保持原样，
    // SubtitledText 的智能断行与翻译流程仍使用带标点的原文
    val activeCueText =
        if (stripPunctuation) SubtitlePunctuation.strip(mergedCueText) else mergedCueText

    // v2.6.23（simul-subtitle 可移植清单 #3）：文本切换时 160ms 淡入 ——
    // 配合下方 AnchoredSubtitleBox 的 animateContentSize，cue 切换不再“吧”地跳变。
    // v2.6.24：淡入改为**可关的开关**（默认关）。关闭时恒为 1，等于旧行为直出。
    val appear = remember(activeCueText, enableFadeIn) {
        Animatable(if (enableFadeIn) 0f else 1f)
    }
    LaunchedEffect(activeCueText, enableFadeIn) {
        if (enableFadeIn) appear.animateTo(1f, tween(160, easing = FastOutSlowInEasing))
    }

    // In bilingual mode the output is "source block + translated block", so the
    // translated block keeps exactly as many lines as the source.
    val isBilingual = translator != null && translator.config.isEnabled &&
        translator.config.displayMode == TranslationDisplayMode.DUAL_LANGUAGE
    val effectiveMaxLines = if (isBilingual) (maxLines * 2) else maxLines

    if (isSplitScreenVR) {
        // VR Dual-Eye Mode (Left & Right Eye split screen)
        Row(modifier = modifier.fillMaxSize()) {
            // Left Eye Viewport
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                AnchoredSubtitleBox(
                    hBias = ((offsetXRatio + vrIpdOffsetRatio) * 2.2f).coerceIn(-0.95f, 0.95f),
                    verticalBias = verticalBias
                ) {
                SubtitledText(
                    text = activeCueText,
                    fontFamily = fontFamily,
                    fontSizeSp = (fontSizeSp * 0.85f).toInt(), // Slightly scaled for VR FOV
                    fontWeightVal = fontWeightVal,
                    isItalic = isItalic,
                    textColor = textColor,
                    textAlpha = textAlpha * appear.value,
                    strokeColor = strokeOption.strokeColor,
                    strokeWidthDp = strokeOption.widthDp,
                    backgroundColor = bgOption.bgColor,
                    backgroundAlpha = bgOption.alpha,
                    textAlign = textAlign,
                    maxLines = effectiveMaxLines,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                }
            }

            // Right Eye Viewport
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                AnchoredSubtitleBox(
                    hBias = ((offsetXRatio - vrIpdOffsetRatio) * 2.2f).coerceIn(-0.95f, 0.95f),
                    verticalBias = verticalBias
                ) {
                SubtitledText(
                    text = activeCueText,
                    fontFamily = fontFamily,
                    fontSizeSp = (fontSizeSp * 0.85f).toInt(), // Slightly scaled for VR FOV
                    fontWeightVal = fontWeightVal,
                    isItalic = isItalic,
                    textColor = textColor,
                    textAlpha = textAlpha * appear.value,
                    strokeColor = strokeOption.strokeColor,
                    strokeWidthDp = strokeOption.widthDp,
                    backgroundColor = bgOption.bgColor,
                    backgroundAlpha = bgOption.alpha,
                    textAlign = textAlign,
                    maxLines = effectiveMaxLines,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                }
            }
        }
    } else {
        // Flat Mode (Standard Single Screen)
        AnchoredSubtitleBox(
            hBias = (offsetXRatio * 2.2f).coerceIn(-0.95f, 0.95f),
            verticalBias = verticalBias,
            modifier = modifier
        ) {
            SubtitledText(
                text = activeCueText,
                fontFamily = fontFamily,
                fontSizeSp = fontSizeSp,
                fontWeightVal = fontWeightVal,
                isItalic = isItalic,
                textColor = textColor,
                textAlpha = textAlpha * appear.value,
                strokeColor = strokeOption.strokeColor,
                strokeWidthDp = strokeOption.widthDp,
                backgroundColor = bgOption.bgColor,
                backgroundAlpha = bgOption.alpha,
                textAlign = textAlign,
                maxLines = effectiveMaxLines,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}
/**
 * v2.6.23（simul-subtitle 可移植清单 #3）：**边缘锚定 + 平滑过渡**的字幕容器。
 *
 * 旧布局用 BiasAlignment 锚定块**中心** —— 行数一变（单行↔双语两行、cue 换行数不同）
 * 整块上下跳。本容器改为锚定**边缘**：偏下（verticalBias ≥ 0）时底边固定、向上生长，
 * 偏上时顶边固定、向下生长（simul-subtitle 的方案：正在读的行不被挤走）。
 *
 * 位置语义微调：锚边落在「旧中心位置」，字幕整体比旧版向屏幕边移约半块高（更像常规播放器）。
 * 高度变化走 200ms animateContentSize，文本切换另有 160ms 淡入（见调用侧 appear）。
 */
@Composable
private fun AnchoredSubtitleBox(
    hBias: Float,
    verticalBias: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val containerH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val vB = verticalBias.coerceIn(-0.95f, 0.95f)
        // 旧版「中心」所在 y（BiasAlignment 语义）：y = H * (1 + bias) / 2
        val yCenter = containerH * (1f + vB) / 2f
        val anchorBottom = vB >= 0f
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    bottom = if (anchorBottom) with(density) { (containerH - yCenter).toDp() } else 0.dp,
                    top = if (anchorBottom) 0.dp else with(density) { yCenter.toDp() }
                ),
            contentAlignment = BiasAlignment(
                horizontalBias = hBias.coerceIn(-0.95f, 0.95f),
                verticalBias = if (anchorBottom) 1f else -1f
            )
        ) {
            content()
        }
    }
}
