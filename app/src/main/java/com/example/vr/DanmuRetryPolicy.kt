package com.example.vr

import com.example.vr.DanmuVisionClient.ChatErrorKind
import kotlin.random.Random

/**
 * v2.6.8：AI 弹幕的**自动重试策略**（纯逻辑，无 Android 依赖，可直接单测）。
 *
 * ## 为什么需要它
 * v2.6.7 及之前，编排循环是「`while (true) { 请求 → 失败就记一句错误 → delay(periodMs) }`」，
 * 也就是**固定周期无条件重试**。这带来三个问题：
 *
 * 1. **致命错误永不停止**：密钥错（`AUTH`）、模型名错（`MODEL_NOT_FOUND`）这类错误
 *    重试一万次也不会成功，却仍然按 interval 一直打请求 —— 白耗流量与电量，
 *    还可能把第三方网关的限流/风控触发得更狠。
 * 2. **临时错误不减速**：429 限流、5xx、断网时，服务端最需要的是"别马上再来"，
 *    而固定周期等于持续加压。
 * 3. **用户不知道在自愈**：界面只显示最后一条错误，看不出"系统还在等 X 秒后重试"。
 *
 * ## 策略（两类四档）
 *
 * | 错误类别 | 判定 | 退避 |
 * |---|---|---|
 * | `NOT_CONFIGURED` / `BAD_URL` / `AUTH` / `MODEL_NOT_FOUND` | **致命**（必须用户去改配置） | 固定 [FATAL_RETRY_MS]（5 分钟一次） |
 * | `RATE_LIMIT`（429） | 限流 | 基数 ×3 起算，再指数增长 |
 * | 其余（5xx / 网络 / 超时 / 空响应…） | 临时 | 基数 ×1 起算，指数增长 |
 *
 * 指数增长为 `periodMs × 2^(n-1)`，单次等待**封顶 [MAX_DELAY_MS]**（60 秒）。
 * 也就是说连续失败到第 4 次之后，就稳定在「每分钟一次」——既不会彻底放弃
 *（用户改完配置能自动恢复），也不会持续烧额度。
 *
 * ⚠️ **故意不"熔断停止"**：`LaunchedEffect` 的 key 只有 `isEnabled / intervalSec / sourceTypeId`，
 * 用户修改密钥/模型名**不会重启循环**。若在这里 `break`，用户改完配置也永远不恢复，
 * 只能手动关开弹幕 —— 比"每分钟试一次"体验更差。
 */
internal object DanmuRetryPolicy {

    /** 致命错误的固定重试间隔：5 分钟（只为"用户改完配置后能自动恢复"） */
    const val FATAL_RETRY_MS = 300_000L

    /** 单次等待的绝对上限：60 秒 */
    const val MAX_DELAY_MS = 60_000L

    /** 指数退避的最大倍数（相对基础周期） */
    const val MAX_MULTIPLIER = 8

    /** 限流类错误的基数倍数（429 需要比普通错误更保守） */
    const val RATE_LIMIT_BASE_MULTIPLIER = 3L

    /**
     * 抖动比例：实际等待 = 计算值 × `[1-JITTER, 1+JITTER]`。
     *
     * 作用是**打散多个同时失败的请求**（比如双开、或几台设备同时重连），
     * 避免它们在同一个时间点再次齐射。
     */
    const val JITTER_RATIO = 0.15f

    /**
     * 是否属于「重试基本无望、必须用户改配置」的类别。
     *
     * 这四类都不是"服务端暂时不可用"，而是"我们的请求本身就错了"：
     * - `NOT_CONFIGURED`：baseUrl / model / key 缺项
     * - `BAD_URL`：`hhttps://` 这类语法错误
     * - `AUTH`：401/403，密钥无效或过期
     * - `MODEL_NOT_FOUND`：404，模型名不被识别（大小写敏感，v2.4.4 头号坑）
     */
    fun isFatal(kind: ChatErrorKind): Boolean = when (kind) {
        ChatErrorKind.NOT_CONFIGURED,
        ChatErrorKind.BAD_URL,
        ChatErrorKind.AUTH,
        ChatErrorKind.MODEL_NOT_FOUND -> true

        else -> false
    }

    /**
     * 下一次重试前应等待多久（**确定性**，不含抖动 —— 便于单测）。
     *
     * @param periodMs            用户设定的生成周期（已 clamp 过的真实间隔）
     * @param consecutiveFailures 连续失败次数，从 1 开始（本次失败就是第 1 次）
     * @param kind                本次失败类别
     */
    fun baseDelayMs(periodMs: Long, consecutiveFailures: Int, kind: ChatErrorKind): Long {
        if (isFatal(kind)) return FATAL_RETRY_MS

        val period = periodMs.coerceAtLeast(1L)
        val n = consecutiveFailures.coerceAtLeast(1)
        // 2^(n-1)，但先按 MAX_MULTIPLIER 截断，避免大 n 时 Long 溢出
        val shift = (n - 1).coerceAtMost(MAX_MULTIPLIER)
        val multiplier = 1L shl shift

        val baseMultiplier = if (kind == ChatErrorKind.RATE_LIMIT) RATE_LIMIT_BASE_MULTIPLIER else 1L
        val raw = period * baseMultiplier * multiplier
        return raw.coerceIn(1L, MAX_DELAY_MS)
    }

    /**
     * 实际使用的等待时长 = [baseDelayMs] 加 ±[JITTER_RATIO] 抖动，且仍不超过 [MAX_DELAY_MS]。
     *
     * @param random 可注入，便于单测断言上下界
     */
    fun nextDelayMs(
        periodMs: Long,
        consecutiveFailures: Int,
        kind: ChatErrorKind,
        random: Random = Random.Default
    ): Long {
        val base = baseDelayMs(periodMs, consecutiveFailures, kind)
        if (isFatal(kind)) return base   // 致命错误不需要抖动：本来就稀疏
        val factor = 1f + (random.nextFloat() * 2f - 1f) * JITTER_RATIO
        return (base * factor).toLong().coerceIn(1L, MAX_DELAY_MS)
    }

    /**
     * 给 UI 用的倒计时秒数（**向上取整**，且至少 1 秒）。
     *
     * 必须是 ceil 而不是 round：5.4 秒四舍五入成 5，用户看到"5 秒后重试"到点却没动静，
     * 会以为卡住了。向上取整只会让提示略保守。
     */
    fun delaySeconds(delayMs: Long): Int =
        ((delayMs + 999L) / 1000L).toInt().coerceAtLeast(1)
}
