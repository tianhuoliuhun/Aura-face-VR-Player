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

/** Accent used for the ▶ current-line marker and the character-sync highlight. */
private val AsrHighlight = Color(0xFFFFD54F)

/** Accent for the characters that changed in the latest translation. */
private val DiffHighlight = Color(0xFF69F0AE)

/** Punctuation stripped from subtitle translations. */
private val PUNCT_REGEX = Regex("[,，。;；:：!！?？…~～·、()（）\\[\\]【】《》\"\\\"“”‘’'-]")

/** Subtitle translations are rendered without punctuation marks. */
private fun stripPunctuation(s: String): String = s.replace(PUNCT_REGEX, "")

/** Length of the longest common prefix of two strings. */
private fun commonPrefixLen(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    var i = 0
    while (i < n && a[i] == b[i]) i++
    return i
}

/** Length of the longest common suffix (not overlapping the common prefix). */
private fun commonSuffixLen(a: String, b: String, prefixLen: Int): Int {
    val maxS = minOf(a.length, b.length) - prefixLen
    var s = 0
    while (s < maxS && a[a.length - 1 - s] == b[b.length - 1 - s]) s++
    return s
}

/**
 * A row that gently slides up and fades in the first time it is composed
 * (smooth roll-in of new subtitle lines without jumping).
 */
@Composable
private fun AppearingRow(content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    val appear by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(450, easing = FastOutSlowInEasing),
        label = "asrRowAppear"
    )
    LaunchedEffect(Unit) { shown = true }
    Box(
        modifier = Modifier.graphicsLayer {
            alpha = appear
            translationY = (1f - appear) * 24f * density
        }
    ) { content() }
}

/**
 * Typewriter animation for the live caption / translation line: the displayed
 * text eases toward [text] one character at a time — characters backspace out
 * (edit/delete) and new characters appear in sequence, exactly like typing.
 * The character typed most recently is tinted with [diffColor] (optional).
 * State survives text updates (same composition slot), so partial updates flow
 * seamlessly instead of re-flashing.
 */
@Composable
private fun TypewriterText(
    text: String,
    diffColor: Color? = null,
    charDelayMs: Long = 26L,
    modifier: Modifier = Modifier,
    fontFamily: FontFamily = FontFamily.Default,
    fontSizeSp: Int = 22,
    fontWeightVal: Int = 400,
    isItalic: Boolean = false,
    textColor: Color = Color.White,
    textAlpha: Float = 1.0f,
    strokeColor: Color = Color.Black,
    strokeWidthDp: Float = 2.0f,
    backgroundColor: Color = Color.Black,
    backgroundAlpha: Float = 0.5f,
    textAlign: TextAlign = TextAlign.Center,
    maxLines: Int = 2,
    maxCharsPerLine: Int = 25
) {
    if (text.isEmpty()) return
    var shown by remember { mutableStateOf("") }
    LaunchedEffect(text) {
        val cur = shown
        if (cur == text) return@LaunchedEffect
        var common = 0
        while (common < cur.length && common < text.length && cur[common] == text[common]) common++
        // If the common prefix is short relative to the longer string, the text is
        // completely different (e.g. switched languages). Jump directly instead of
        // animating a slow backspace+retype across the whole string.
        val longerLen = maxOf(cur.length, text.length)
        if (longerLen > 4 && common * 2 < longerLen) {
            shown = text
            return@LaunchedEffect
        }
        while (shown.length > common) {
            shown = shown.dropLast(1)
            delay(charDelayMs / 2)
        }
        while (shown.length < text.length) {
            shown = text.substring(0, shown.length + 1)
            delay(charDelayMs)
        }
    }
    val lastCharRange = if (diffColor != null && shown.isNotEmpty()) {
        (shown.length - 1)..(shown.length - 1)
    } else null
    SubtitledText(
        text = shown,
        modifier = modifier,
        fontFamily = fontFamily,
        fontSizeSp = fontSizeSp,
        fontWeightVal = fontWeightVal,
        isItalic = isItalic,
        textColor = textColor,
        textAlpha = textAlpha,
        strokeColor = strokeColor,
        strokeWidthDp = strokeWidthDp,
        backgroundColor = backgroundColor,
        backgroundAlpha = backgroundAlpha,
        textAlign = textAlign,
        maxLines = maxLines,
        maxCharsPerLine = maxCharsPerLine,
        highlightRange = lastCharRange,
        highlightColor = diffColor ?: textColor
    )
}

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
    modifier: Modifier = Modifier
) {
    if (!isSubtitleEnabled) return

    val fontFamily = SubtitleFontHelper.getFontFamily(subtitleFont, fontWeightVal, isItalic)
    val italicFont = SubtitleFontHelper.getFontFamily(subtitleFont, fontWeightVal, true)
    // Caption panel position is controlled exclusively by the settings sliders
    // (offsetX/offsetY). The only runtime gesture kept is pinch-zoom. State is
    // hoisted above block() so both VR eyes share the same zoom.
    var panelZoom by remember { mutableStateOf(1f) }
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
    LaunchedEffect(rawCueText, translator?.config) {
        if (translator != null) {
            translatedText = translator.translateOrOriginal(
                rawCueText,
                onTranslated = { result -> translatedText = result }
            )
        } else {
            translatedText = rawCueText
        }
    }
    val activeCueText = translatedText ?: rawCueText

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
                    .fillMaxHeight(),
                contentAlignment = BiasAlignment(
                    horizontalBias = ((offsetXRatio + vrIpdOffsetRatio) * 2.2f).coerceIn(-0.95f, 0.95f),
                    verticalBias = verticalBias
                )
            ) {
                SubtitledText(
                    text = activeCueText,
                    fontFamily = fontFamily,
                    fontSizeSp = (fontSizeSp * 0.85f).toInt(), // Slightly scaled for VR FOV
                    fontWeightVal = fontWeightVal,
                    isItalic = isItalic,
                    textColor = textColor,
                    textAlpha = textAlpha,
                    strokeColor = strokeOption.strokeColor,
                    strokeWidthDp = strokeOption.widthDp,
                    backgroundColor = bgOption.bgColor,
                    backgroundAlpha = bgOption.alpha,
                    textAlign = textAlign,
                    maxLines = effectiveMaxLines,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // Right Eye Viewport
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = BiasAlignment(
                    horizontalBias = ((offsetXRatio - vrIpdOffsetRatio) * 2.2f).coerceIn(-0.95f, 0.95f),
                    verticalBias = verticalBias
                )
            ) {
                SubtitledText(
                    text = activeCueText,
                    fontFamily = fontFamily,
                    fontSizeSp = (fontSizeSp * 0.85f).toInt(), // Slightly scaled for VR FOV
                    fontWeightVal = fontWeightVal,
                    isItalic = isItalic,
                    textColor = textColor,
                    textAlpha = textAlpha,
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
    } else {
        // Flat Mode (Standard Single Screen)
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = BiasAlignment(
                horizontalBias = (offsetXRatio * 2.2f).coerceIn(-0.95f, 0.95f),
                verticalBias = verticalBias
            )
        ) {
            SubtitledText(
                text = activeCueText,
                fontFamily = fontFamily,
                fontSizeSp = fontSizeSp,
                fontWeightVal = fontWeightVal,
                isItalic = isItalic,
                textColor = textColor,
                textAlpha = textAlpha,
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
