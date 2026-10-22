package com.example.vr

/**
 * OpenRouter `:free` 模型池的挑选逻辑（v2.6.11）。
 *
 * ## 为什么需要「池」而不是一个模型
 * OpenRouter 的免费模型全部带 `:free` 后缀，它们**不是长期合约**：
 * 上游 provider 随时可能下线某个模型、或对该模型单独限流（返回 429 /
 * `No endpoints found`）。字幕翻译是**逐条、连续**的请求流，钉死一个模型时，
 * 只要它被限流，整部片就再也翻不出来。因此这里按「池」的思路组织：
 * 一个模型失败就换池里下一个，失败的模型单独进入冷却。
 *
 * ## 为什么池要能自动发现
 * 实测（2026-10-22）`/api/v1/models` 里 468 个模型中只有 **18 个 `:free`**、
 * 纯文本的可选范围更小，且名单几个月就会换代（`llama-3.x:free` 那一代已全部消失）。
 * 硬编码名单必然过时 → [SEED_MODELS] 只作**首轮优先尝试**，
 * 真正的兜底候选由 `SubtitleTranslator` 在运行时拉 `/models` 过滤得到。
 *
 * ## 为什么是纯函数
 * 本项目的单测跑在 JVM 上（`org.json` 只是 mock stub，不可用），
 * 所以这里**不碰 JSON**：解析留给调用方，本类只处理 `List<String>`，
 * 这样挑选逻辑（去重/过滤/冷却跳过）全部可单测。
 */
object OpenRouterModelPool {

    /**
     * 首轮优先尝试的精选池（全部 `:free`）。
     *
     * 只放**通用对话模型**——翻译是纯文本理解任务，代码模型（poolside/laguna、
     * cohere/north-mini-code）与风控模型（content-safety）虽然也是 `:free`，
     * 但输出习惯完全不匹配，实测容易答非所问或直接拒绝。
     *
     * ⚠️ 名单会过时，勿当成唯一来源：全部失败后调用方会用运行时发现结果接替。
     */
    val SEED_MODELS: List<String> = listOf(
        "google/gemma-4-31b-it:free",
        "nvidia/nemotron-3.5-lightning:free",
        "minimax/minimax-m3.1-flash:free",
        "nvidia/nemotron-3-super-120b-a12b:free",
        "dots-studio/dots-3-note-preview:free"
    )

    /**
     * 模型名里出现这些词就判为「不适合翻译」。
     * 只看名字不看能力：OpenRouter 的模型元信息没有统一的「用途」字段，
     * 名字关键词是唯一稳定可用的信号（代码/风控/向量/重排类模型命名都带它）。
     */
    private val REJECT_KEYWORDS = listOf(
        "code", "guard", "safety", "moderation", "embed", "rerank", "coder"
    )

    /** 是不是 OpenRouter 的免费模型：**以 `:free` 结尾**（这是唯一的判定依据）。 */
    fun isFreeModel(modelId: String): Boolean =
        modelId.trim().endsWith(":free", ignoreCase = true)

    /** 名字看起来是否适合翻译（不判 `:free`，那是 [isFreeModel] 的事）。 */
    fun isLikelyTranslatable(modelId: String): Boolean {
        val lower = modelId.lowercase()
        return REJECT_KEYWORDS.none { lower.contains(it) }
    }

    /**
     * 候选整理：去空 → 去重（保序）→ 剔掉不适合翻译的。
     *
     * ⚠️ 若过滤后为空（例如发现结果里全是代码模型），**回退到过滤前**：
     * 宁可试一个不合适的模型，也不要让「池」变成空池导致整片翻不出来。
     */
    fun rankCandidates(ids: List<String>): List<String> {
        val cleaned = ids.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (cleaned.isEmpty()) return emptyList()
        val preferred = cleaned.filter { isLikelyTranslatable(it) }
        return preferred.ifEmpty { cleaned }
    }

    /**
     * 拆用户填的模型名：支持**逗号（中英文）、分号、换行、空白**混合分隔。
     *
     * 用户手填时常常直接粘贴一串（`a:free, b:free`）——
     * 允许一串即视为「自定义小池」，与内置池同样轮换。
     */
    fun splitUserModels(raw: String): List<String> =
        raw.split(',', '，', ';', '；', '\n', '\r', ' ', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /**
     * 组装本次请求的**尝试顺序**。
     *
     * 优先级：用户手填 > [SEED_MODELS] > 运行时发现。
     * 去重保序 —— 同一模型重复出现会让「换一个模型重试」变成原地重试。
     */
    fun buildAttemptOrder(userSpecified: String, discovered: List<String>): List<String> {
        val user = splitUserModels(userSpecified)
        if (user.isNotEmpty()) return user
        val merged = ArrayList<String>(SEED_MODELS.size + discovered.size)
        merged.addAll(SEED_MODELS)
        merged.addAll(discovered)
        return merged.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * 取一个**当前不在冷却**的候选；全在冷却则返回 null。
     *
     * @param cooldownUntil 给定模型 id，返回其冷却截止时刻（ms），0 表示没冷却
     */
    fun availableNow(
        candidates: List<String>,
        nowMs: Long,
        cooldownUntil: (String) -> Long
    ): String? = candidates.firstOrNull { cooldownUntil(it) <= nowMs }

    /**
     * 全部候选都在冷却时，距离**最早恢复**还有多少毫秒（>0）。
     * 用于提示「N 秒后恢复」，避免用户以为功能坏了。
     */
    fun earliestRecoveryInMs(
        candidates: List<String>,
        nowMs: Long,
        cooldownUntil: (String) -> Long
    ): Long {
        if (candidates.isEmpty()) return 0L
        val earliest = candidates.minOf { cooldownUntil(it) }
        return (earliest - nowMs).coerceAtLeast(0L)
    }
}
