package com.example.vr

import androidx.annotation.StringRes
import com.example.R

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign

data class SubtitleCue(
    val id: Int = 0,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String
)

enum class SubtitleFont(val displayName: String, @StringRes val labelRes: Int, val id: Int) {
    SYSTEM("系统默认", R.string.font_system, 0),
    OPPO_SANS("OPPO Sans (默认)", R.string.font_oppo_sans, 1),
    MI_SANS("MiSans (小米)", R.string.font_mi_sans, 7),
    SANS_SERIF("无衬线 (Sans-Serif)", R.string.font_sans_serif, 3),
    SERIF("衬线 (Serif)", R.string.font_serif, 4),
    MONOSPACE("等宽 (Monospace)", R.string.font_monospace, 5),
    CURSIVE("手写花体 (Cursive)", R.string.font_cursive, 6)
}

enum class SubtitleColorOption(val displayName: String, @StringRes val labelRes: Int, val color: Color, val id: Int) {
    WHITE("纯白", R.string.color_white, Color.White, 0),
    YELLOW("柠檬黄", R.string.color_lemon, Color(0xFFFFEB3B), 1),
    CYAN("青蓝", R.string.color_cyan, Color(0xFF00E5FF), 2),
    GREEN("荧光绿", R.string.color_green, Color(0xFF00E676), 3),
    PINK("樱花粉", R.string.color_pink, Color(0xFFFF4081), 4),
    ORANGE("暖阳橙", R.string.color_orange, Color(0xFFFF9100), 5),
    BLACK("漆黑", R.string.color_black, Color.Black, 6),
    RED("鲜红", R.string.color_red, Color(0xFFFF1744), 7)
}

enum class SubtitleStrokeOption(val displayName: String, @StringRes val labelRes: Int, val strokeColor: Color, val widthDp: Float, val id: Int) {
    NONE("无描边", R.string.stroke_none, Color.Transparent, 0f, 0),
    THIN_BLACK("细黑边 (1dp)", R.string.stroke_thin_black, Color.Black, 1.5f, 1),
    MEDIUM_BLACK("中黑边 (2.5dp)", R.string.stroke_medium_black, Color.Black, 2.5f, 2),
    THICK_BLACK("粗黑边 (4dp)", R.string.stroke_thick_black, Color.Black, 4f, 3),
    WHITE_BORDER("白描边 (2dp)", R.string.stroke_white, Color.White, 2f, 4),
    YELLOW_BORDER("黄描边 (2dp)", R.string.stroke_yellow, Color(0xFFFFD600), 2f, 5)
}

enum class SubtitleBgOption(val displayName: String, @StringRes val labelRes: Int, val bgColor: Color, val alpha: Float, val id: Int) {
    TRANSPARENT("无背景 (全透明)", R.string.bg_transparent, Color.Black, 0.0f, 0),
    SEMI_BLACK("半透明黑色", R.string.bg_semi_black, Color.Black, 0.5f, 1),
    DARK_BLACK("深暗底框", R.string.bg_dark, Color.Black, 0.75f, 2),
    SOLID_BLACK("纯黑方框", R.string.bg_solid_black, Color.Black, 1.0f, 3),
    SEMI_NAVY("深蓝半透", R.string.bg_semi_navy, Color(0xFF0D1B2A), 0.65f, 4)
}

enum class SubtitleAlignOption(val displayName: String, @StringRes val labelRes: Int, val textAlign: TextAlign, val id: Int) {
    CENTER("居中", R.string.align_center, TextAlign.Center, 0),
    LEFT("左对齐", R.string.align_left, TextAlign.Left, 1),
    RIGHT("右对齐", R.string.align_right, TextAlign.Right, 2)
}

object SubtitleParser {
    /**
     * Parse standard .srt or .vtt subtitle content strings into a sorted list of SubtitleCue
     */
    fun parseSrtOrVtt(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        if (content.isBlank()) return cues

        // Normalize line endings
        val lines = content.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        var i = 0
        var cueIndex = 1

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty() || line.startsWith("WEBVTT") || line.startsWith("NOTE")) {
                i++
                continue
            }

            // Check if line is timestamp e.g. 00:00:01,000 --> 00:00:04,000 or 00:01.000 --> 00:04.000
            val timeMatch = TIMESTAMPS_REGEX.find(line)
            if (timeMatch != null) {
                val startMs = parseTimestampToMs(timeMatch.groupValues[1])
                val endMs = parseTimestampToMs(timeMatch.groupValues[2])

                val textBuilder = StringBuilder()
                i++
                while (i < lines.size && lines[i].trim().isNotEmpty()) {
                    val textLine = lines[i].trim()
                        .replace(HTML_TAGS_REGEX, "") // Strip HTML formatting tags
                    if (textLine.isNotEmpty()) {
                        if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                        textBuilder.append(textLine)
                    }
                    i++
                }

                if (startMs >= 0 && endMs > startMs && textBuilder.isNotEmpty()) {
                    cues.add(SubtitleCue(cueIndex++, startMs, endMs, textBuilder.toString()))
                }
            } else {
                i++
            }
        }
        return cues.sortedBy { it.startTimeMs }
    }

    private val TIMESTAMPS_REGEX = Regex("""(\d{1,2}:\d{2}:\d{2}[.,]\d{3}|\d{2}:\d{2}[.,]\d{3})\s*-->\s*(\d{1,2}:\d{2}:\d{2}[.,]\d{3}|\d{2}:\d{2}[.,]\d{3})""")
    private val HTML_TAGS_REGEX = Regex("""<[^>]*>""")

    private fun parseTimestampToMs(ts: String): Long {
        return try {
            val normalized = ts.replace(',', '.')
            val parts = normalized.split(':')
            if (parts.size == 3) {
                val hours = parts[0].toLong()
                val minutes = parts[1].toLong()
                val secondsAndMillis = parts[2].split('.')
                val seconds = secondsAndMillis[0].toLong()
                val millis = secondsAndMillis.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
                (hours * 3600 + minutes * 60 + seconds) * 1000 + millis
            } else if (parts.size == 2) {
                val minutes = parts[0].toLong()
                val secondsAndMillis = parts[1].split('.')
                val seconds = secondsAndMillis[0].toLong()
                val millis = secondsAndMillis.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
                (minutes * 60 + seconds) * 1000 + millis
            } else 0L
        } catch (_: Exception) {
            0L
        }
    }
}
