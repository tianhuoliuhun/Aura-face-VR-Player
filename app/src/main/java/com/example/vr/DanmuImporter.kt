package com.example.vr

import androidx.compose.ui.graphics.Color
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * 外部弹幕文件解析（v2.4.7）
 *
 * ## 支持的格式
 * 1. **B 站 XML**（最主流）：`<d p="出现时间,模式,字号,颜色,发送时间戳,弹幕池,用户hash,行id">文本</d>`
 * 2. **通用 JSON 数组**：`[{"time":12.5,"text":"哈哈","color":"#FFFFFF"}, ...]`
 *
 * ## 为什么支持这两种
 * - XML 是弹幕站导出的**事实标准**（B 站/ A 站等），用户手上最多的就是这类文件；
 * - JSON 则是**本项目自己的产物**（AI 生成的弹幕若要导出/迁移，用 JSON 最自然），
 *   也方便用户手写少量测试弹幕。
 *
 * ## ⚠️ 解析必须容错，不能一遇到坏数据就整体失败
 * 真实弹幕文件动辄几万行，其中夹杂空行、注释、异常字段是常态。
 * 因此本解析器**逐条容错**：单条解析失败就跳过它，继续解析后面的条目，
 * 最后在 [DanmuImportResult.skipped] 里报告跳过了多少条 —— 而不是抛异常让整次导入失败。
 *
 * ## 与 AI 弹幕的关系
 * 导入的弹幕**不经过视觉模型**，自带时间戳，因此：
 * - 每条携带 [ImportedDanmu.timeMs]（**视频内出现时间**，ms），由调度层按时投递；
 * - 颜色直接采用文件里的值（XML 的十进制 RGB / JSON 的 #RRGGBB）；
 * - ⚠️ 不使用 `DanmuColorMode` 随机上色 —— 文件已经指定了颜色，再随机就覆盖了原文件的意图。
 *   （但若文件里**没有**颜色字段，则回落到配置里的单一颜色。）
 *
 * ⚠️ 本项目 `minSdk = 24` 且**零新增依赖**：这里用 Android 自带的
 *    `XmlPullParser`（`org.xmlpull.v1`，Android 平台内置）与**手写**的 JSON 解析，
 *    不引入 Gson/Moshi 之类库（见 `android-native-dep-audit` skill 的约定）。
 */
object DanmuImporter {

    /** 单条导入弹幕 */
    data class ImportedDanmu(
        /** 弹幕文本 */
        val text: String,
        /** 视频内出现时间（ms）—— 调度层按它投递 */
        val timeMs: Long,
        /** 文字颜色；未指定时为 null（调用方回落到配置色） */
        val color: Color? = null,
        /** 字号（px，B 站口径：18/25/36）；null 表示未指定 */
        val fontSizePx: Int? = null
    )

    /**
     * 导入结果。
     *
     * @param items   成功解析的弹幕（**已按时间升序排序**）
     * @param skipped 跳过的条数（字段缺失/时间非法/文本为空等）
     * @param format  识别出的格式名（用于日志与 UI 提示）
     */
    data class Result(
        val items: List<ImportedDanmu>,
        val skipped: Int,
        val format: Format
    ) {
        val size: Int get() = items.size
        /** 最后一条的时间（ms），用于 UI 展示「覆盖时长」 */
        val lastTimeMs: Long get() = items.lastOrNull()?.timeMs ?: 0L
    }

    /** 识别出的文件格式 */
    enum class Format {
        /** B 站 XML */
        BILI_XML,
        /** JSON 数组 */
        JSON,
        /** 都没认出来 */
        UNKNOWN
    }

    /** 解析上限：防止超大文件把内存打爆（同类弹幕站一般也就几万条） */
    const val MAX_ITEMS = 20_000

    /**
     * 日志钩子（默认不做事）。
     *
     * ## ⚠️ 为什么不用 `android.util.Log`（踩过的坑）
     * 本对象是**纯解析逻辑**，单测跑在纯 JVM 上。而 `android.util.Log.w` 在未 mock 的
     * 单元测试里会直接抛 `RuntimeException: Method w in android.util.Log not mocked`。
     *
     * 后果比"测试失败"更严重：这些 `Log` 调用**全都在 `catch` 块里** ——
     * 本该「容错吞掉异常、继续返回已解析结果」的分支，反而因为打印日志而崩溃，
     * 把整个容错设计废掉（本项目 9 条 XML 单测就是这么一起挂掉的）。
     *
     * 因此改为**可注入的钩子**：生产代码在启动时把它接到 `Log` 上，
     * 单测里保持默认（不做事）即可 —— 解析器自身零 Android 依赖。
     */
    var logger: (String) -> Unit = {}

    /** 单条文本长度上限（字符）—— 超长文本多为脏数据，且会撑爆 layout 缓存 */
    const val MAX_TEXT_CHARS = 200

    /**
     * 解析弹幕文件内容（自动识别格式）。
     *
     * @param content 文件全文
     * @return 解析结果；无法识别格式时返回 [Format.UNKNOWN] 且 items 为空
     */
    fun parse(content: String): Result {
        val trimmed = content.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (trimmed.isEmpty()) return Result(emptyList(), 0, Format.UNKNOWN)

        return when {
            looksLikeJson(trimmed) -> parseJson(trimmed)
            looksLikeXml(trimmed) -> parseBiliXml(trimmed)
            else -> Result(emptyList(), 0, Format.UNKNOWN)
        }
    }

    /** JSON 数组以 `[` 开头（跳过 BOM/空白后） */
    private fun looksLikeJson(s: String): Boolean = s.startsWith("[")

    /** XML 以 `<` 开头且含有 `<d` 或 `<?xml` —— 避免把普通文本误判 */
    private fun looksLikeXml(s: String): Boolean =
        s.startsWith("<") && (s.contains("<d ") || s.contains("<d\u003e") || s.contains("<?xml"))

    // ================================================================
    // 一、B 站 XML
    // ================================================================

    /**
     * 解析 B 站弹幕 XML。
     *
     * ## `p` 属性字段含义（已核实，见 `bilibili-API-collect`）
     * | 下标 | 含义 | 说明 |
     * |---|---|---|
     * | 0 | 出现时间 | **秒**（浮点）→ 本方法换算为 ms |
     * | 1 | 模式 | 1/2/3 滚动、4 底部、5 顶部、6 逆向、7 高级、8 代码、9 BAS |
     * | 2 | 字号 | 18 小 / 25 标准 / 36 大（也见过 12/16/45/64） |
     * | 3 | 颜色 | **十进制 RGB888**，如 16777215 = 白 |
     * | 4 | 发送时间 | Unix 时间戳（**与弹幕出现时间无关**，勿混用） |
     * | 5 | 弹幕池 | 0 普通 / 1 字幕 / 2 特殊 |
     * | 6 | 用户 hash | |
     * | 7 | 行 id | |
     *
     * ## ⚠️ 本项目只取「滚动」类弹幕（模式 1/2/3）
     * 顶部/底部/逆向/高级弹幕的定位逻辑与「从右往左飘」完全不同，
     * 本项目渲染层只实现了滚动一种。若把 4/5 号弹幕当滚动弹幕画，
     * 它们会以错误的轨迹乱飘 —— **宁可丢弃也不要画错**。
     * 丢弃数量会计入 [Result.skipped]。
     */
    fun parseBiliXml(xml: String): Result {
        val out = mutableListOf<ImportedDanmu>()
        var skipped = 0
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "d") {
                    if (out.size >= MAX_ITEMS) break
                    val p = parser.getAttributeValue(null, "p")
                    val text = parser.nextText().orEmpty().trim()
                    val item = buildFromP(p, text)
                    if (item != null) out.add(item) else skipped++
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            // 整体解析异常（文件被截断等）：保留已解析到的部分，不抛
            logger("B站 XML 解析中断: ${e.message}")
        }
        out.sortBy { it.timeMs }
        return Result(items = out, skipped = skipped, format = Format.BILI_XML)
    }

    /**
     * 由 `p` 属性与文本构造一条弹幕；不合法返回 null。
     *
     * 抽出成函数是为了**可单测**（不必构造完整 XML）。
     */
    fun buildFromP(p: String?, text: String): ImportedDanmu? {
        if (text.isEmpty() || text.length > MAX_TEXT_CHARS) return null
        if (p.isNullOrBlank()) return null
        val parts = p.split(',')
        if (parts.size < 4) return null  // 至少要有 时间/模式/字号/颜色 才能用

        val timeSec = parts[0].trim().toDoubleOrNull() ?: return null
        if (!timeSec.isFinite() || timeSec < 0) return null

        // 模式：只接受滚动类（1/2/3）
        val mode = parts[1].trim().toIntOrNull() ?: 1
        if (mode !in SCROLL_MODES) return null

        val fontSize = parts[2].trim().toIntOrNull()

        // 颜色：十进制 RGB888 → Compose Color
        val colorInt = parts[3].trim().toIntOrNull()
        val color = colorInt?.let { rgbIntToColor(it) }

        return ImportedDanmu(
            text = text,
            timeMs = (timeSec * 1000.0).toLong(),
            color = color,
            fontSizePx = fontSize
        )
    }

    /** 滚动类弹幕模式（1/2/3 都是从右往左；4/5/6/7/8/9 本项目不支持） */
    private val SCROLL_MODES = setOf(1, 2, 3)

    /**
     * 十进制 RGB888 → Compose [Color]。
     *
     * ⚠️ 必须**屏蔽掉 alpha 位**：B 站该字段只有 RGB（24 位），
     * 直接 `Color(value)` 会把它当成 **ARGB**，于是 16777215（白）变成
     * `#00FFFFFF` —— alpha = 0 → **完全透明，弹幕看不见**。
     * 这是最容易踩的一个坑（颜色字段的高 8 位恒为 0）。
     */
    fun rgbIntToColor(rgb: Int): Color =
        Color(0xFF000000.toInt() or (rgb and 0x00FFFFFF))

    // ================================================================
    // 二、JSON 数组（手写解析，零依赖）
    // ================================================================

    /**
     * 解析 JSON 数组。
     *
     * ## 支持的字段名（都做了别名兼容，尽量宽容）
     * - 时间：`time` / `t` / `start` / `timeMs`（**数值，秒；若给 `timeMs` 则按毫秒**）
     * - 文本：`text` / `content` / `body` / `msg`
     * - 颜色：`color` / `colour`（`"#RRGGBB"` 字符串，或十进制整数）
     * - 字号：`size` / `fontSize` / `font_size`
     *
     * ## 为什么手写而不用库
     * 只解析「对象数组、值都是基本类型」这一种子集，手写不到百行；
     * 引库要动 `app/build.gradle.kts` 且与项目「零新增依赖」约束冲突。
     * 更重要的是**容错策略不同**：库遇到一处坏数据往往整体失败，而这里要逐条跳过。
     */
    fun parseJson(json: String): Result {
        val out = mutableListOf<ImportedDanmu>()
        var skipped = 0
        try {
            val objects = splitTopLevelObjects(json)
            for (obj in objects) {
                if (out.size >= MAX_ITEMS) break
                val item = buildFromJsonObject(obj)
                if (item != null) out.add(item) else skipped++
            }
        } catch (e: Exception) {
            logger("JSON 解析中断: ${e.message}")
        }
        out.sortBy { it.timeMs }
        return Result(items = out, skipped = skipped, format = Format.JSON)
    }

    /** 由单个 JSON 对象文本构造弹幕；不合法返回 null（抽出来便于单测） */
    fun buildFromJsonObject(obj: String): ImportedDanmu? {
        val map = parseFlatJsonObject(obj) ?: return null

        val text = (map["text"] ?: map["content"] ?: map["body"] ?: map["msg"])
            ?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_TEXT_CHARS) return null

        // 时间：优先 timeMs（毫秒），否则 time/t/start（秒）
        val timeMs: Long = map["timems"]?.toDoubleOrNull()?.let { it.toLong() }
            ?: (map["time"] ?: map["t"] ?: map["start"])?.toDoubleOrNull()?.let { (it * 1000.0).toLong() }
            ?: return null
        if (timeMs < 0) return null

        val color = (map["color"] ?: map["colour"])?.let { parseColorValue(it) }
        val fontSize = (map["size"] ?: map["fontsize"] ?: map["font_size"])?.toDoubleOrNull()?.toInt()

        return ImportedDanmu(text = text, timeMs = timeMs, color = color, fontSizePx = fontSize)
    }

    /**
     * 解析颜色值：支持 `"#RRGGBB"`、`"#AARRGGBB"`、`"RRGGBB"` 与十进制整数串。
     *
     * 返回 null 表示「无法解析」→ 调用方回落到配置色（**不要**返回白色，
     * 否则用户明明写了颜色却没生效会很难查）。
     */
    fun parseColorValue(raw: String): Color? {
        val s = raw.trim().removeSurrounding("\"").trim()
        if (s.isEmpty()) return null
        if (s.startsWith("#")) {
            val hex = s.substring(1)
            return when (hex.length) {
                6 -> hex.toLongOrNull(16)?.let { Color(0xFF000000L or it) }
                8 -> hex.toLongOrNull(16)?.let { Color(it) }
                3 -> {  // #RGB 简写 → 展开为 #RRGGBB
                    val r = hex[0]; val g = hex[1]; val b = hex[2]
                    val full = "$r$r$g$g$b$b"
                    full.toLongOrNull(16)?.let { Color(0xFF000000L or it) }
                }
                else -> null
            }
        }
        // 纯数字：可能是十进制 RGB888 或十六进制（不带 #）
        s.toIntOrNull()?.let { return rgbIntToColor(it) }
        s.toLongOrNull(16)?.let { v ->
            return if (s.length >= 8) Color(v) else Color(0xFF000000L or v)
        }
        return null
    }

    /**
     * 从 JSON 数组文本里切出顶层对象串（不解析，只按括号配平切分）。
     *
     * 用括号配平而非 `split("},")`：文本内容里完全可能出现 `}` 或 `,`
     * （弹幕就是人话），简单切分会把一条弹幕切碎。
     * ⚠️ 需要跳过字符串内部的括号（用 [inString] 与转义标记跟踪状态）。
     */
    private fun splitTopLevelObjects(json: String): List<String> {
        val start = json.indexOf('[')
        if (start < 0) return emptyList()
        val result = mutableListOf<String>()
        var depth = 0
        var objStart = -1
        var inString = false
        var escaped = false

        for (i in start + 1 until json.length) {
            val c = json[i]
            if (escaped) { escaped = false; continue }
            if (c == '\\') { escaped = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue

            when (c) {
                '{' -> { if (depth == 0) objStart = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && objStart >= 0) {
                        result.add(json.substring(objStart, i + 1))
                        objStart = -1
                    }
                }
                ']' -> if (depth == 0) return result
            }
            if (depth < 0) break
        }
        return result
    }

    /**
     * 解析**扁平** JSON 对象（值不含嵌套对象/数组）→ Map（key 统一小写）。
     *
     * 只支持扁平结构：弹幕条目本来就是 `{time, text, color}` 这种简单形态，
     * 支持嵌套只会让手写解析器复杂度暴涨而没有实际收益。
     */
    private fun parseFlatJsonObject(obj: String): Map<String, String>? {
        if (!obj.startsWith("{") || !obj.endsWith("}")) return null
        val inner = obj.substring(1, obj.length - 1)
        val map = mutableMapOf<String, String>()

        var i = 0
        while (i < inner.length) {
            // 找 key 起始引号
            while (i < inner.length && inner[i] != '"') i++
            if (i >= inner.length) break
            val keyEnd = findStringEnd(inner, i)
            if (keyEnd < 0) return null
            val key = inner.substring(i + 1, keyEnd).lowercase()

            // 找冒号
            var j = keyEnd + 1
            while (j < inner.length && inner[j] != ':') j++
            if (j >= inner.length) break
            j++
            while (j < inner.length && inner[j].isWhitespace()) j++
            if (j >= inner.length) break

            val value: String
            if (inner[j] == '"') {
                val ve = findStringEnd(inner, j)
                if (ve < 0) return null
                value = unescapeJson(inner.substring(j + 1, ve))
                i = ve + 1
            } else {
                var k = j
                while (k < inner.length && inner[k] != ',' && inner[k] != '}') k++
                value = inner.substring(j, k).trim()
                i = k
            }
            map[key] = value
            // 跳到下一个逗号之后
            while (i < inner.length && inner[i] != ',') {
                if (inner[i] == '"') {  // 值里还有引号 → 跳过整个字符串
                    val e2 = findStringEnd(inner, i)
                    if (e2 < 0) break
                    i = e2 + 1
                    continue
                }
                i++
            }
            i++
        }
        return map
    }

    /** 从 [from]（指向开引号）起找配对的闭引号下标；找不到返回 -1 */
    private fun findStringEnd(s: String, from: Int): Int {
        var i = from + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return -1
    }

    /** 还原常见 JSON 转义（弹幕文本里可能含 `\"` `\\` `\n`） */
    private fun unescapeJson(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        if (i + 5 < s.length) {
                            val hex = s.substring(i + 2, i + 6)
                            hex.toIntOrNull(16)?.let { sb.append(it.toChar()) } ?: sb.append(n)
                            i += 4
                        } else sb.append(n)
                    }
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** 日志 tag */
    private const val TAG = "DanmuImporter"

    /**
     * 生产环境启用日志（在 `Application`/Activity 启动时调一次）。
     *
     * 单独提供而不是让解析器直接依赖 `android.util.Log` —— 理由见 [logger] 的注释：
     * 那会把纯 JVM 单测的容错分支变成崩溃。
     */
    fun enableAndroidLog() {
        logger = { msg -> android.util.Log.w(TAG, msg) }
    }
}
