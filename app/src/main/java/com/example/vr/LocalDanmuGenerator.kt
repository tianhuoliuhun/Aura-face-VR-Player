package com.example.vr

import android.content.Context
import android.util.Log

/**
 * **本地 LLM 弹幕生成**（v2.4.12）—— 端侧 llama.cpp + Qwen3.5-0.8B。
 *
 * ## ⚠️ 与云端视觉模型（[DanmuVisionClient]）的本质差别
 * | | 云端视觉模型 | 本地 LLM |
 * |---|---|---|
 * | 看画面 | ✅ | ❌（纯文本权重，视觉编码器 `mmproj` 未下载） |
 * | 素材 | 画面 / 台词 / 两者 | **仅台词** |
 * | 费用与联网 | 需要 | **零费用、完全离线** |
 *
 * 所以本类**只吃台词**（见 [DanmuConfig.needsSubtitle] 在本地模式下恒为 true）。
 *
 * ## 失败即空列表
 * 与 `DanmuVisionClient` 同范式：网络/模型问题一律返回空列表，
 * **绝不抛给播放链路** —— 弹幕是锦上添花，不能因为一次失败影响播放。
 *
 * ## 纯逻辑可测
 * 解析部分抽成 [parseLines]（零 Android 依赖），可在纯 JVM 单测里覆盖 ——
 * 小模型的输出格式**不稳定**，这一层是最容易出问题的地方。
 */
object LocalDanmuGenerator {

    private const val TAG = "LocalDanmuGenerator"

    /** 单条弹幕的最大字符数：超出直接截断（小模型偶尔会写出长句）。 */
    const val MAX_ITEM_CHARS = 30

    /** 解析时最多接受的条数（防止模型「复读」把队列刷爆）。 */
    const val MAX_ITEMS = 30

    /**
     * 系统提示词。
     *
     * ⚠️ 每条限制都对应一个真实的小模型毛病：
     * - 「不要编号」→ 不加会输出 `1. xxx`
     * - 「不要引号」→ 不加会输出 `"xxx"`
     * - 「不要解释/开场白」→ 不加会输出「好的，以下是弹幕：」
     * - 「每行一条」→ 不加可能把多条挤在一行用顿号分隔
     */
    private const val SYSTEM_PROMPT = """你是一个弹幕生成器，根据给定的台词生成中文弹幕。
要求：
1. 每条不超过 20 个字，口语化、有趣，像真实视频网站的弹幕
2. 不要编号、不要引号、不要任何解释或开场白
3. 每行只输出一条弹幕，不要有空行"""

    /**
     * 生成一批弹幕。
     *
     * @param subtitleText 当前播放位置附近的台词（唯一素材）
     * @param personaPrompt 用户的风格提示词（可为空）
     * @param batchSize 期望条数
     * @return 弹幕文本列表；任何前置条件不满足或推理失败都返回**空列表**
     */
    suspend fun generate(
        context: Context,
        subtitleText: String,
        personaPrompt: String,
        batchSize: Int,
        /** v2.4.13：可选的画面帧（ARGB Bitmap）。仅当 mmproj 已就绪时才会真正被使用。 */
        frame: android.graphics.Bitmap? = null
    ): List<String> {
        // v2.4.13：有图 **且** mmproj 已就绪时才启用视觉路径。
        // ⚠️ 两个条件缺一不可：只判断 frame != null 会在没下 mmproj 时白传一张图
        //    （native 侧会退回纯文本，白做一次格式转换）。
        val useVision = frame != null && LocalLlmManager.visionAvailable
        if (subtitleText.isBlank() && !useVision) {
            // ⚠️ **纯文本**模式下唯一素材就是台词 —— 没有台词就没有依据，
            //    这里刻意不编造（否则模型会凭空输出与画面无关的内容）。
            //    ⚠️ 但**有图时不受此限**：视觉模型可以只看画面。
            Log.d(TAG, "无台词且无画面可用，本地弹幕跳过本次生成")
            return emptyList()
        }
        val model = LocalLlmManager.readyModel(context) ?: run {
            Log.w(TAG, "本地弹幕：模型未下载")
            return emptyList()
        }
        if (!LocalLlmManager.ensureLoaded(context, model)) {
            Log.w(TAG, "本地弹幕：模型未就绪（${LocalLlmManager.lastError}）")
            return emptyList()
        }

        val n = batchSize.coerceIn(1, MAX_ITEMS)
        val persona = personaPrompt.trim().takeIf { it.isNotBlank() }
            ?.let { "\n另外，整体风格要求：$it" }
            .orEmpty()
        // v2.4.13：有画面时改写提示词 —— 让模型知道它可以看图，
        // 否则它会忽略图片只按台词发挥（白白浪费视觉编码器的算力与那 116MB 存储）。
        val prompt = if (useVision) {
            val linesPart = if (subtitleText.isNotBlank()) "\n台词：\n$subtitleText" else ""
            "根据这张画面${if (subtitleText.isNotBlank()) "和以下台词" else ""}生成 $n 条弹幕。$linesPart$persona"
        } else {
            "台词：\n$subtitleText\n\n请生成 $n 条弹幕。$persona"
        }

        // 转成 mtmd 要的 RGB 3 字节/像素（⚠️ 不是 ARGB）
        var rgbBytes: ByteArray? = null
        var rgbW = 0
        var rgbH = 0
        if (useVision && frame != null) {
            val w = frame.width
            val h = frame.height
            if (w > 0 && h > 0) {
                val px = IntArray(w * h)
                frame.getPixels(px, 0, w, 0, 0, w, h)
                rgbBytes = ImageScale.argbToRgbBytes(px)
                rgbW = w
                rgbH = h
            }
        }

        val raw = LocalLlmManager.complete(
            prompt = prompt,
            systemPrompt = SYSTEM_PROMPT,
            rgb = rgbBytes,
            imgW = rgbW,
            imgH = rgbH,
            // 弹幕要多样 → 温度比翻译高（翻译用 0.2，追求稳定）
            maxTokens = 512,
            temperature = 0.9f
        ) ?: return emptyList()

        val parsed = parseLines(raw, n)
        Log.d(TAG, "本地弹幕：模型输出 ${raw.lines().size} 行 → 解析出 ${parsed.size} 条")
        return parsed
    }

    /**
     * 把模型输出解析成弹幕列表（**纯函数**，可单测）。
     *
     * 小模型的输出格式不稳定，这一层做了四类清洗：
     * 1. **去编号前缀**（`1.` / `1、` / `1)`）；
     * 2. **去列表符号**（`- ` / `• `）；
     * 3. **去包裹引号**（`"xxx"` / `“xxx”`）；
     * 4. **丢弃「自言自语」行**（如「好的，以下是弹幕：」）。
     *
     * ⚠️ 第 4 条用 [isMetaLine] 判据而不是"长度阈值"：
     *    短弹幕本来就短，按长度过滤会**误杀真正的弹幕**。
     */
    fun parseLines(raw: String, limit: Int = MAX_ITEMS): List<String> {
        if (raw.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        for (line in raw.lines()) {
            var s = line.trim()
            if (s.isEmpty()) continue

            // ①② 去列表符号
            s = s.removePrefix("- ").removePrefix("• ").removePrefix("* ").trim()
            // ① 去编号前缀（数字 + 分隔符）
            s = s.replace(Regex("^\\d+\\s*[.、)）]\\s*"), "").trim()
            // ③ 去包裹引号（中英文都处理）
            s = unwrapQuotes(s)
            if (s.isEmpty()) continue

            if (s.length > MAX_ITEM_CHARS) s = s.substring(0, MAX_ITEM_CHARS)
            // ④ 丢掉"模型的自言自语"
            if (isMetaLine(s)) continue

            out.add(s)
            if (out.size >= limit) break
        }
        return out
    }

    /** 去掉首尾成对的引号（直引号 / 弯引号 / 书名号）。 */
    private fun unwrapQuotes(s: String): String {
        val pairs = listOf(
            '"' to '"',
            '\u201C' to '\u201D',   // “ ”
            '\u2018' to '\u2019',   // ‘ ’
            '「' to '」',
            '『' to '』'
        )
        for ((a, b) in pairs) {
            if (s.length >= 2 && s.first() == a && s.last() == b) {
                return s.substring(1, s.length - 1).trim()
            }
        }
        // 只出现在开头/结尾的孤立引号也去掉
        return s.trim('"', '\u201C', '\u201D', '\u2018', '\u2019')
    }

    /**
     * 是否像「模型的自言自语」而不是弹幕本身。
     *
     * ⚠️ 必须**同时**满足「以元话语开头」且「有一定长度」：
     *    否则「好的」这种本身就可能是弹幕的短句会被误杀。
     */
    private fun isMetaLine(s: String): Boolean {
        if (s.length <= 12) return false
        val meta = listOf(
            "以下是", "弹幕如下", "生成如下", "好的，", "当然，", "解释：", "注：", "注意：",
            "这些弹幕", "根据台词", "我为你", "希望能"
        )
        return meta.any { s.startsWith(it) }
    }
}
