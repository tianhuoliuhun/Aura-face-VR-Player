package com.example.vr

import androidx.annotation.StringRes
import com.example.R

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign

import java.nio.charset.Charset

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
     * **统一入口**：按内容自动嗅探格式，分派到对应的解析器。
     *
     * ⚠️ v2.1.248：此前这里只有 SRT/VTT 一条路，`.ass` / `.ssa` 交给它会
     * **解析出 0 条 cue 且不报任何错**（ASS 的时间戳写在 `Dialogue:` 行里，
     * 没有 SRT 的 `-->` 箭头，`TIMESTAMPS_REGEX` 一条都匹配不上）。
     * 文件选择器用的是全通配符 MIME（星号加斜杠加星号，此处刻意不复写该字面量，
     * 否则会提前闭合块注释），所以用户能选中 `.ass`、点了却毫无反应 ——
     * 典型的「静默失效」。现在改为先嗅探再分流。
     *
     * 嗅探顺序（严格按「特征越强越先判」）：
     *  1. 含 `[Script Info]` / `[V4+ Styles]` / `[Events]` 段头，或任意一行以
     *     `Dialogue:` / `Comment:` 开头 → **ASS/SSA**（含 `.ass`、`.ssa` 两种变体）
     *  2. 其余 → SRT/VTT（保持原有行为不变，零回归风险）
     */
    fun parse(content: String): List<SubtitleCue> {
        if (content.isBlank()) return emptyList()
        return if (looksLikeAss(content)) parseAss(content) else parseSrtOrVtt(content)
    }

    /**
     * **字节数组 → 文本**，带 BOM 检测与中文编码回退（v2.1.248 新增）。
     *
     * 为什么需要：此前调用点一律用 `File.readText()` / `bufferedReader()`，
     * 走的是平台默认字符集（Android 上是 UTF-8）。而**中文老字幕大量是 GBK**
     * （B 站/射手网早期资源、国产 DVD 提取轨迹），用 UTF-8 强行解码会得到
     * 整片 `锟斤拷`／`????` —— 而且**不会抛异常**（UTF-8 解码器默认替换非法字节），
     * 所以同样是「静默变乱码」。
     *
     * 探测顺序：
     *  1. **BOM**（最可靠）：UTF-8 BOM / UTF-16 LE / UTF-16 BE
     *  2. **严格 UTF-8 解码**（`CodingErrorAction.REPORT`）成功 → UTF-8
     *  3. 失败 → 依次尝试 GBK → GB18030 → Big5（取第一个能严格解码成功的）
     *  4. 全失败 → 退回 UTF-8 宽松解码（有损但至少有内容）
     */
    fun decodeBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        // 1) BOM 检测
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }

        // 2) 严格 UTF-8
        decodeStrict(bytes, Charsets.UTF_8)?.let { return it }

        // 3) 中文编码回退
        for (name in listOf("GBK", "GB18030", "Big5")) {
            val cs = runCatching { Charset.forName(name) }.getOrNull() ?: continue
            decodeStrict(bytes, cs)?.let { return it }
        }

        // 4) 有损兜底
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * 严格解码：非法字节**抛异常**而不是替换成 `?`。
     *
     * ⚠️ 必须先 `rewind()` —— `decode()` 会推进 buffer 位置，不重置会导致
     * 第二次调用（换个字符集）从末尾开始、什么都读不到（假成功）。
     */
    private fun decodeStrict(bytes: ByteArray, cs: Charset): String? {
        return try {
            val decoder = cs.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            val buf = java.nio.ByteBuffer.wrap(bytes)
            val out = decoder.decode(buf)
            out.toString()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 判断内容是否是 ASS / SSA（SubStation Alpha）字幕。
     *
     * 判据取「段头」与「事件行」两类**强特征**，不用扩展名 ——
     * 因为调用点拿到的是文件内容或 `content://` URI（后者往往没有扩展名）。
     * 只扫前 200 行即可（段头与 `[Events]` 一定在文件前部），
     * 避免大文件被整份扫一遍。
     */
    fun looksLikeAss(content: String): Boolean {
        if (content.isBlank()) return false
        // 先看有没有 SRT/VTT 的 `-->` 时间轴：有的话优先按 SRT 处理，
        // 避免把「文本里恰好提到 Dialogue:」的 SRT 误判成 ASS。
        val head = content.take(64 * 1024)
        var scanned = 0
        for (raw in head.replace("\r\n", "\n").replace("\r", "\n").split("\n")) {
            scanned++
            if (scanned > 200) break
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[Script Info]") || line.startsWith("[V4+ Styles]") ||
                line.startsWith("[V4 Styles]") || line.startsWith("[Events]") ||
                line.startsWith("ScriptType:")
            ) {
                return true
            }
            if (line.startsWith("Dialogue:") || line.startsWith("Comment:")) {
                return true
            }
            // 命中 SRT/VTT 时间轴 → 直接判为 SRT，不再往下看
            if (line.contains("-->")) return false
        }
        return false
    }

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

    // ===================== ASS / SSA（SubStation Alpha）=====================

    /**
     * 解析 ASS / SSA 字幕（v2.1.248 新增）。
     *
     * 格式要点（与 SRT 的差异都在这里）：
     *  - 时间戳是 `H:MM:SS.cc`（**厘秒**，两位小数），不是毫秒；分隔符是 `,`
     *  - 一行一个事件：`Dialogue: 层,开始,结束,样式,名字,边距L,边距R,边距V,效果,文本`
     *  - **各列顺序由 `[Events]` 段的 `Format:` 行决定**，不是硬编码 —— 有些工具
     *    会把 `Marked` / `Layer` 等列插在不同位置，所以必须先读 `Format:` 再按名取列。
     *  - 文本里的 `{\...}` 是覆盖标签（定位/颜色/字体），`\N` 是硬换行、
     *    `\n` 是软换行、`\h` 是不换行空格 —— 都要转成可读文本。
     *
     * ⚠️ 只取 `Dialogue:` 行，**跳过 `Comment:` 行**（那是注释，不应显示）。
     */
    fun parseAss(content: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        if (content.isBlank()) return cues

        val lines = content.replace("\r\n", "\n").replace("\r", "\n").split("\n")

        // `Format:` 行给出的列名 → 下标。默认按 ASS 常见顺序，读到 Format 行后覆盖。
        var columns = DEFAULT_ASS_COLUMNS
        var cueIndex = 1

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            // 进入 / 离开 Events 段。Format 行只在 Events 段内有意义。
            if (line.startsWith("[")) {
                continue
            }

            if (line.startsWith("Format:", ignoreCase = true)) {
                val names = line.substringAfter(':').split(',')
                    .map { it.trim().lowercase() }
                if (names.size >= 3) columns = names
                continue
            }

            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue

            // ⚠️ Dialogue 行按 `,` 切列，但**文本列内部也含逗号** ——
            //    所以只能切出「前 N-1 列」，剩下的整段都算文本。
             val body = line.substringAfter(':').trim()
            val textIdx = columns.indexOf("text")
            if (textIdx < 0) continue
            val parts = body.split(',', limit = textIdx + 1)
            if (parts.size <= textIdx) continue

            val startRaw = parts.getOrNull(columns.indexOf("start"))?.trim() ?: continue
            val endRaw = parts.getOrNull(columns.indexOf("end"))?.trim() ?: continue
            val startMs = parseAssTimestampToMs(startRaw)
            val endMs = parseAssTimestampToMs(endRaw)
            if (endMs <= startMs) continue

            val text = cleanAssText(parts[textIdx])
            if (text.isEmpty()) continue

            cues.add(SubtitleCue(cueIndex++, startMs, endMs, text))
        }
        return cues.sortedBy { it.startTimeMs }
    }

    /** ASS 默认列序（`[Events]` 段未给 `Format:` 行时兜底）。 */
    private val DEFAULT_ASS_COLUMNS =
        listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")

    /** `{\...}` 覆盖标签（含 `{\pos(..)}` 这类嵌套括号）。 */
    private val ASS_OVERRIDE_REGEX = Regex("""\{[^}]*\}""")

    /**
     * 清理 ASS 文本：去掉覆盖标签、把 `\N` / `\n` 换成换行、`\h` 换成空格。
     *
     * ⚠️ 顺序很重要：**必须先剥 `{\...}` 再处理 `\N`**。反过来会把
     * `{\pos(320,240)}` 里的内容搅乱（虽然这个例子里没有 `\N`，但
     * `{\fad(200,200)\N}` 这种写法是存在的）。
     */
    private fun cleanAssText(raw: String): String {
        var t = ASS_OVERRIDE_REGEX.replace(raw, "")
        t = t.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
        // 兜底：文本里偶见 HTML 标签（有些转换工具留下）
        t = t.replace(HTML_TAGS_REGEX, "")
        return t.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    /**
     * ASS 时间戳 → 毫秒。格式 `H:MM:SS.cc`（厘秒）或 `H:MM:SS.mmm`。
     *
     * ⚠️ **厘秒 vs 毫秒**是 ASS 解析最容易错的地方：`.50` 表示 50 厘秒 = 500ms，
     * 而不是 50ms。判据按小数位数：不足 3 位一律当厘秒补零（`.5` → 500）。
     * 这与 `parseTimestampToMs`（SRT/VTT，`.50` 当 500ms 的等宽补零语义）不同，
     * 所以单列一个函数，避免复用出错。
     */
    private fun parseAssTimestampToMs(ts: String): Long {
        return try {
            val parts = ts.replace(',', '.').split(':')
            if (parts.size != 3) return 0L
            val h = parts[0].toLong()
            val m = parts[1].toLong()
            val secParts = parts[2].split('.')
            val s = secParts[0].toLong()
            val fracRaw = secParts.getOrNull(1) ?: ""
            // 厘秒语义：不足 3 位则补零到 3 位；超过 3 位截断
            val millis = when {
                fracRaw.isEmpty() -> 0L
                fracRaw.length <= 2 -> fracRaw.padEnd(2, '0').toLong() * 10L
                else -> fracRaw.take(3).padEnd(3, '0').toLong()
            }
            (h * 3600 + m * 60 + s) * 1000 + millis
        } catch (_: Exception) {
            0L
        }
    }

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
