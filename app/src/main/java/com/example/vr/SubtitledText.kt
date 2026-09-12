package com.example.vr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Subtitle text with an 8-directional hard outline.
 *
 * Uses a single TextMeasurer + Canvas pass (9 drawText calls) instead of the old
 * 8 stacked Text composables, which is far cheaper to lay out and draw — important
 * in VR split-screen where two instances render per frame.
 */

// Hard wrap limit per line: long space-less scripts (Japanese, Chinese) would
// otherwise spill into the translated block or get truncated away entirely.
private const val MAX_CHARS_PER_LINE = 14

// Sentence-ending punctuation: best break points (keep full sentences together)
private fun isSentenceEnd(c: Char): Boolean =
    c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?' || c == '…'

// Mid-sentence punctuation: secondary break points (clause boundaries)
private fun isClauseBreak(c: Char): Boolean =
    c == '，' || c == '、' || c == '；' || c == '：' || c == ',' || c == ';' || c == ':'

/**
 * v100：智能断句换行——优先在句末标点断行（。！？），其次在逗号等子句标点断行，
 * 再次在空格处断行（英文词边界），最后硬字符断行（CJK）。
 * 每行保持 ~14 字，确保语义完整和视觉美观。
 */
internal fun wrapText(text: String, maxChars: Int = MAX_CHARS_PER_LINE): String {
    if (text.length <= maxChars) return text
    val sb = StringBuilder(text.length + text.length / maxChars + 2)
    for (line in text.split("\n")) {
        if (line.isEmpty()) {
            sb.append('\n')
            continue
        }
        var start = 0
        val len = line.length
        while (start < len) {
            val end = start + maxChars
            if (end >= len) {
                sb.append(line, start, len).append('\n')
                break
            }
            // 优先级断句：句末 > 子句 > 空格 > 硬切
            var breakAt = end
            // 1st: 句末标点（。！？.!?…）
            for (k in end - 1 downTo start + 1) {
                if (isSentenceEnd(line[k])) { breakAt = k + 1; break }
            }
            // 2nd: 子句标点（，、；：,;:）
            if (breakAt == end) {
                for (k in end - 1 downTo start + 1) {
                    if (isClauseBreak(line[k])) { breakAt = k + 1; break }
                }
            }
            // 3rd: 空格（英文词边界）
            if (breakAt == end) {
                for (k in end - 1 downTo start + 1) {
                    if (line[k] == ' ') { breakAt = k + 1; break }
                }
            }
            // 4th: 硬切（CJK 无空格）
            if (breakAt == end) breakAt = end
            sb.append(line, start, breakAt).append('\n')
            start = breakAt
            if (start < len && line[start] == ' ') start++
        }
    }
    return sb.toString().trimEnd('\n')
}

@Composable
fun SubtitledText(
    text: String,
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
    maxCharsPerLine: Int = MAX_CHARS_PER_LINE,
    highlightRange: IntRange? = null,
    highlightColor: Color = Color.Transparent
) {
    if (text.isBlank()) return

    val fontSize = fontSizeSp.sp
    val fontWeight = SubtitleFontHelper.getFontWeight(fontWeightVal)
    val fontStyle = SubtitleFontHelper.getFontStyle(isItalic)
    val effectiveTextColor = textColor.copy(alpha = textAlpha.coerceIn(0f, 1f))
    val effectiveBgColor = backgroundColor.copy(alpha = backgroundAlpha.coerceIn(0f, 1f))
    val density = LocalDensity.current
    val textMeasurer: TextMeasurer = rememberTextMeasurer()

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(effectiveBgColor)
    ) {
        val strokeWidthPx = with(density) { strokeWidthDp.dp.toPx() }
        val maxTextWidthPx = with(density) { (maxWidth - 24.dp).toPx().toInt().coerceAtLeast(1) }

        val textStyle = TextStyle(
            fontSize = fontSize,
            fontFamily = fontFamily,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            color = effectiveTextColor,
            textAlign = textAlign,
            lineHeight = (fontSizeSp * 1.3f).sp
        )

        // Hard-wrap long lines (max 25 chars) BEFORE measuring: this guarantees
        // reliable line breaks for space-less scripts such as Japanese, keeps the
        // translated block line-aligned with the source block, and lets the
        // maxLines truncation work on well-formed short lines.
        val wrappedText = remember(text, maxCharsPerLine) { wrapText(text, maxCharsPerLine) }

        // Optionally tint a character range (e.g. the char a typewriter just
        // typed) — the range indexes the wrapped text passed in by the caller.
        val annotatedText = remember(wrappedText, highlightRange, highlightColor, textAlpha) {
            buildAnnotatedString {
                append(wrappedText)
                val r = highlightRange
                if (r != null && highlightColor != Color.Transparent) {
                    val start = r.first.coerceIn(0, wrappedText.length)
                    val end = (r.last + 1).coerceIn(start, wrappedText.length)
                    if (end > start) {
                        addStyle(
                            SpanStyle(color = highlightColor.copy(alpha = textAlpha.coerceIn(0f, 1f))),
                            start,
                            end
                        )
                    }
                }
            }
        }

        // Measure with automatic wrapping, truncated to at most [maxLines] lines
        // (binary search for the longest prefix that still fits the line budget).
        val layout = remember(wrappedText, annotatedText, textStyle, maxTextWidthPx, maxLines) {
            val full = textMeasurer.measure(
                text = annotatedText,
                style = textStyle,
                constraints = Constraints(maxWidth = maxTextWidthPx)
            )
            if (maxLines <= 0 || full.lineCount <= maxLines) {
                full
            } else {
                var lo = 0
                var hi = wrappedText.length
                var best = ""
                while (lo <= hi) {
                    val mid = (lo + hi) / 2
                    val candidate = wrappedText.take(mid)
                    val l = textMeasurer.measure(
                        text = candidate,
                        style = textStyle,
                        constraints = Constraints(maxWidth = maxTextWidthPx)
                    )
                    if (l.lineCount <= maxLines) {
                        best = candidate
                        lo = mid + 1
                    } else {
                        hi = mid - 1
                    }
                }
                // Re-fit with an ellipsis (drop one char if it overflows)
                var truncated = best + "…"
                var l = textMeasurer.measure(
                    text = truncated,
                    style = textStyle,
                    constraints = Constraints(maxWidth = maxTextWidthPx)
                )
                if (l.lineCount > maxLines && best.isNotEmpty()) {
                    truncated = best.dropLast(1) + "…"
                    l = textMeasurer.measure(
                        text = truncated,
                        style = textStyle,
                        constraints = Constraints(maxWidth = maxTextWidthPx)
                    )
                }
                l
            }
        }

        val w: Dp = with(density) { layout.size.width.toDp() }
        val h: Dp = with(density) { layout.size.height.toDp() }

        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .size(width = w, height = h)
        ) {
            Canvas(Modifier.size(width = w, height = h)) {
                val hasStroke = strokeWidthDp > 0f && strokeColor != Color.Transparent
                if (hasStroke) {
                    val d = strokeWidthPx / 2f
                    val offsets = listOf(
                        Offset(-d, -d), Offset(0f, -d), Offset(d, -d),
                        Offset(-d, 0f), Offset(d, 0f),
                        Offset(-d, d), Offset(0f, d), Offset(d, d)
                    )
                    for (o in offsets) {
                        drawText(
                            textLayoutResult = layout,
                            topLeft = o,
                            color = strokeColor.copy(alpha = textAlpha.coerceIn(0f, 1f))
                        )
                    }
                }
                // Fill layer: no explicit color so the annotated spans (highlight)
                // are honored, otherwise falls back to the style color.
                drawText(textLayoutResult = layout)
            }
        }
    }
}