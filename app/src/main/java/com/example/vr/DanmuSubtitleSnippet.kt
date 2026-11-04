package com.example.vr

/**
 * 台词片段提取（v2.4.1）
 *
 * 用途：把 ASR / 外挂字幕的 [SubtitleCue] 列表，按**当前播放位置**裁出一小段台词文本，
 * 交给弹幕的视觉模型当作素材 —— 有了台词，弹幕才能"接住剧情"，
 * 而不只是对着画面构图泛泛而谈。
 *
 * ## 为什么是「纯函数 + 独立文件」
 * 这段逻辑最容易出错（时间窗口边界、溢出去重、超长截断），而它又**完全不依赖 Android**
 * —— 抽出来就能纯 JVM 单测，不必启动模拟器。
 *
 * ## 时间窗口的取舍
 * - **之前 15 秒**为主：弹幕是对**刚刚发生**的画面的反应。
 * - **之后 5 秒**为辅：让模型知道"话还没说完"，避免对半句话做反应。
 * - ⚠️ 之后不宜过大，否则模型会**剧透**（把还没播的剧情写进弹幕）。
 */
object DanmuSubtitleSnippet {

    /**
     * 取出 `positionMs` 附近的台词，拼成一段文本。
     *
     * v2.6.23（用户要求「AI 弹幕链路同时获得字幕的原稿和翻译」）：新增 [translationOf] ——
     * 每条台词若能查到译文，就拼成「原文\n译文」两行（`SubtitleCue.text` 恒为原文，
     * 译文在翻译缓存里，由调用方以 lambda 注入查询，本函数保持纯函数可单测）。
     * 无译文或译文 == 原文时退回纯原文，行为与旧版完全一致。
     *
     * @param cues        全部已生成的台词（可为空）
     * @param positionMs  当前播放位置（ms）
     * @param windowBeforeMs 向前取多久（默认 [DanmuConfig.SUBTITLE_WINDOW_BEFORE_MS]）
     * @param windowAfterMs  向后取多久（默认 [DanmuConfig.SUBTITLE_WINDOW_AFTER_MS]）
     * @param maxChars    结果字符上限（超出则保留**靠后**的部分 —— 越近的台词越相关；
     *                    双语模式下调用方应适当调大，如 `SUBTITLE_TEXT_MAX_CHARS * 2`）
     * @param translationOf 原文 → 译文 的只读查询（默认不查 = 纯原文模式）
     * @return 拼好的台词文本；无可用台词时返回**空串**（调用方据此判断"没素材"）
     */
    fun extract(
        cues: List<SubtitleCue>,
        positionMs: Long,
        windowBeforeMs: Long = DanmuConfig.SUBTITLE_WINDOW_BEFORE_MS,
        windowAfterMs: Long = DanmuConfig.SUBTITLE_WINDOW_AFTER_MS,
        maxChars: Int = DanmuConfig.SUBTITLE_TEXT_MAX_CHARS,
        translationOf: (String) -> String? = { null }
    ): String {
        if (cues.isEmpty()) return ""
        if (maxChars <= 0) return ""

        val from = positionMs - windowBeforeMs
        val to = positionMs + windowAfterMs

        // 与窗口有交集的都算：cue 用 [start, end] 闭区间，故 end >= from && start <= to
        // ⚠️ 不能只判 `start in from..to` —— 长句会跨越整个窗口却被整体漏掉。
        //
        // v2.4.1：调用方传进来的 cues 通常来自 SubtitleCache.snapshot()（TreeMap → 天然有序），
        // 但**本函数不假设有序**：先花 O(n·log n) 排一次序，让后面的 `break` 成为真正的
        // 早退优化而非正确性依赖。cues 规模约几百~几千条，排序开销可忽略。
        // （顺序若已正确，Kotlin 的 sortedBy 是稳定排序，结果与原顺序一致，零回归。）
        val ordered = if (cues.size > 1) cues.sortedBy { it.startTimeMs } else cues
        val picked = ArrayList<SubtitleCue>(8)
        for (cue in ordered) {
            if (cue.endTimeMs < from) continue
            if (cue.startTimeMs > to) break   // 已升序，后面都不在窗口内
            val text = cue.text.trim()
            if (text.isEmpty()) continue
            picked.add(cue)
        }
        if (picked.isEmpty()) return ""

        // 逐条拼，超过上限就从头丢（保留最近的）
        // 先用 deque 收集，再按需从头部移除
        val parts = ArrayDeque<String>(picked.size)
        for (cue in picked) {
            val t = cue.text.trim().replace('\n', ' ')
            if (t.isEmpty()) continue
            // 双语：译文独立成行（原文在前），便于 LLM 分辨两种语言；
            // 查不到译文 / 译文与原文相同（未翻译的目标语言）时退回纯原文
            val tr = runCatching { translationOf(t) }.getOrNull()?.trim()
                ?.replace('\n', ' ')
            val block = if (tr.isNullOrBlank() || tr == t) t else "$t\n$tr"
            parts.addLast(block)
        }
        if (parts.isEmpty()) return ""

        // 从尾部往前累加，保证「越近的台词一定保留」
        // （cost 按拼好后的实际长度算，双语块自然比单语贵，无需特判）
        val out = ArrayList<String>(parts.size)
        var total = 0
        var i = parts.size - 1
        while (i >= 0) {
            val t = parts[i]
            val cost = t.length + if (out.isEmpty()) 0 else 1  // 换行/连接符开销
            if (total + cost > maxChars) break
            out.add(t)
            total += cost
            i--
        }
        if (out.isEmpty()) {
            // 单条就超长 → 退化为「截断最后一条」，总比返回空好
            val last = parts.last()
            return if (last.length > maxChars) last.take(maxChars) else last
        }
        out.reverse()
        return out.joinToString(separator = "\n")
    }
}
