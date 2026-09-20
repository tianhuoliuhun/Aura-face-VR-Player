package com.example.vr

/**
 * 字幕标点净化（v2.0.154）。
 *
 * 需求：字幕（ASR 原文与译文）在**显示与导出**时都不带标点，观感更干净。
 *
 * ⚠️ 设计要点：**只在显示/导出层净化，内部原始文本一字不改**。原因有三：
 *  1. [SubtitledText] 的智能断行按「句末标点 > 子句标点 > 空格 > 硬切」优先级切分，
 *     若在源头去掉标点，断行会退化成硬切，字幕断句明显变差；
 *  2. 翻译引擎靠标点判断句子边界，标点缺失会拉低译文质量；
 *  3. 翻译缓存 key 基于原文文本，在源头改文本会导致「同一句话两个 key」，命中率下降。
 *
 * 因此净化只发生在「即将渲染」与「即将写文件」的时刻。
 */
object SubtitlePunctuation {

    /**
     * 直接删除的标点：中英常见标点。
     * **刻意不含英文句点 `.`** —— 句点单独按「句尾」规则处理，见 [TRAILING_DOT]。
     */
    private val PUNCT = Regex("[,，。;；:：!！?？…~～·、()（）\\[\\]【】《》\"“”‘’'-]")

    /**
     * **句尾句点**：仅当 `.` 后面是空白（含换行）或已到字符串结尾时才删除。
     *
     * 这样 `3.14`、`U.S.`、`192.168.1.1` 里的点会保留（它们后面跟的是数字/字母），
     * 而 `Hello.` / `End of line.\n` 这类句末句点会被去掉。
     */
    private val TRAILING_DOT = Regex("\\.(?=\\s|$)")

    /** 标点被删掉后可能留下连续空格，折叠为单个 */
    private val MULTI_SPACE = Regex("[ \\t]{2,}")

    /**
     * 去标点。
     *
     * 双语字幕是 `原文\n译文` 的多行文本，逐行独立处理 —— 每行的行尾句点都会被正确识别。
     * 处理完逐行去首尾空白，避免「标点被删后在行首/行尾留下空格」。
     */
    fun strip(text: String): String {
        if (text.isEmpty()) return text
        var s = text.replace(PUNCT, "")
        s = s.replace(TRAILING_DOT, "")
        s = s.replace(MULTI_SPACE, " ")
        return if (s.indexOf('\n') >= 0) {
            s.split('\n').joinToString("\n") { it.trim() }
        } else {
            s.trim()
        }
    }
}
