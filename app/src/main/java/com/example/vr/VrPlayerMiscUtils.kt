package com.example.vr

/*
 * v2.4.63：从 VRPlayerScreen.kt 拆出的**通用小工具**（媒体探测 / 缩略图 / 文案）。
 *
 * 说明：原文件内这 4 个函数都是 `private`（仅同文件使用），拆出后改为 `internal`
 * （同模块可见），调用点无需改动。实现逐字未变。
 */

import com.example.R
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import kotlin.math.roundToInt

// ===================== v2.0.180：拖动预览缩略图参数与工具 =====================

/** 拖动预览抓帧节流间隔（ms）：拖动期间最快每 120ms 抓一次，避免每帧都解码。 */
internal const val SEEK_THUMB_THROTTLE_MS = 120L

/**
 * 拖动预览时间量化步长（ms）：把请求时间对齐到该步长后再抓帧 / 查缓存。
 * 1 秒 ≈ 关键帧量级，配合 OPTION_CLOSEST_SYNC 能显著提高缓存命中率（同一秒内拖动不再重复解码）。
 */
internal const val SEEK_THUMB_QUANTUM_MS = 1000L

/** 拖动预览目标尺寸（匹配 UI 的 160dp×90dp，在 xxhdpi 上足够清晰）。 */
internal const val SEEK_THUMB_W = 320
internal const val SEEK_THUMB_H = 180

// ===================== v2.4.25：容器修复「收敛」判据 =====================

/**
 * 判断当前 URI 是否已经是**本机容器修复的产物**（`cacheDir` 下的
 * `remuxed_*.mp4` / `levelpatched_*.mp4`）。
 *
 * 为什么要这条判据：容器修复成功后，会把 `selectedMediaItem.uri` 换成修复产物并
 * 重载播放。而此时**仍在排队的 seek 校验协程**（每次拖动都会起一个，延迟 1.5~2.5s）
 * 会拿「已修复的文件」再触发一次修复 —— 输入与输出是**同一个 cache 路径**，
 * 于是同一文件被反复重封装、标题不断追加"（已修复）"、播放反复重载 → **卡顿**。
 *
 * 有了它：修复产物不再被当作"待修复源"，**每个文件最多修复一次**，链路自然收敛。
 */
internal fun isContainerFixOutput(uriStr: String?): Boolean {
    if (uriStr.isNullOrEmpty()) return false
    return try {
        val p = android.net.Uri.parse(uriStr).path ?: return false
        p.contains("/remuxed_") || p.contains("/levelpatched_")
    } catch (_: Throwable) {
        false
    }
}

/**
 * v2.4.28：文件是否**含视频轨**（容器修复「切换前先验能播」用）。
 *
 * 为什么需要：重封装可能产出「打不开 / 只剩音频」的坏文件（例如源里大量包无时间戳被跳过）。
 * 若直接切过去，就会从「能播但拖不动」变成「完全播不了」——用户报过这个。
 * 所以：**产物无法确认含视频轨就一直不换**（宁可不修，也不能把能播的搞坏）。
 *
 * ⚠️ 这是 **IO** 操作（打开文件），调用方必须在后台线程。
 */
internal fun hasVideoTrack(file: java.io.File): Boolean {
    if (!file.isFile || file.length() < 1024) return false
    val ex = android.media.MediaExtractor()
    return try {
        ex.setDataSource(file.absolutePath)
        var ok = false
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                ok = true
                break
            }
        }
        ok
    } catch (_: Throwable) {
        false
    } finally {
        try { ex.release() } catch (_: Throwable) {}
    }
}

// ===================== v2.3.1（P2+P4）：AI 弹幕编排参数 =====================

/**
 * 首次取帧前的等待时间（ms）。
 * 刚进播放页时画面可能还是黑帧/封面图，立刻抓一帧会让模型看到无意义内容（浪费 token）。
 */
internal const val FIRST_CAPTURE_DELAY_MS = 8_000L

/** 请求取帧后，轮询结果的最大尝试次数（GL 回读发生在下一帧） */
internal const val FRAME_POLL_MAX_TRIES = 20

/** 每次轮询的间隔（ms）—— 20 × 100ms = 最多等 2s */
internal const val FRAME_POLL_INTERVAL_MS = 100L

/**
 * 弹幕 seek 检测的轮询间隔（ms）。
 *
 * 只用于比较「播放位置是否突跳 > 2s」，不做任何重活，故可以放慢；
 * 200ms 足以让跳转后的陈旧弹幕在肉眼反应前被清掉。
 */
internal const val DANMU_SEEK_POLL_MS = 200L

/**
 * v2.4.7：导入弹幕的投递节拍（ms）。
 *
 * ## 为什么是固定节拍而不是跟着播放位置回调
 * 位置回调的频率由播放内核决定（Exo / IJK / MPV 各不相同），依赖它会让
 * 投递时机随内核变化；自持一个固定节拍更可控，也让三种内核行为一致。
 *
 * ## 为什么 120ms
 * 弹幕时间戳是毫秒级，但**人眼对 100ms 级的出场延迟无感**；
 * 再快只是徒增空转（本循环每拍只做一次纯内存比较）。
 * 取 120 与「同批抖动默认 σ=400ms」相比小一个量级，不会成为出场时机的瓶颈。
 */
internal const val DANMU_IMPORT_TICK_MS = 120L

/**
 * v2.4.41（弹幕审查 #6）：导入弹幕「无可用轨道」后的**重试窗口**（ms）。
 *
 * 旧实现 `pickTrack` 返回 null 就直接丢弃；密集批次下大量弹幕**无声消失**。
 * 现在未入队的条目会进待投递队列、下一拍重试；**超过本窗口仍未上屏才丢弃**
 * （≈1s，仍接近原时间点，「稍晚冒出」好过「永远不来」）。
 */
internal const val DANMU_IMPORT_RETRY_WINDOW_MS = 1_000L

/**
 * v2.4.41（弹幕审查 #10）：AI 弹幕**响应到达时**与截图时刻播放位置的**最大允许偏离**（ms）。
 *
 * 模型请求耗时 2~10s（读超时最长 30s），期间画面早已推进 —— 超过本阈值说明这批弹幕
 * 对应的画面「已经过去了」，直接丢弃，不再错位展示。
 */
internal const val DANMU_LATE_DROP_MS = 10_000L

/**
 * 把抓到的原始帧缩放到拖动预览尺寸（320×180，16:9）。
 *
 * 源不是 16:9 时先**等比缩放**再**居中裁切**，避免直接拉伸导致人脸变形
 * （拖动预览正是用来看清画面的，变形会误导用户）。
 * 返回的 Bitmap 与入参不是同一对象时，调用方负责回收入参。
 */
internal fun scaleSeekThumb(src: Bitmap): Bitmap {
    val srcW = src.width
    val srcH = src.height
    if (srcW <= 0 || srcH <= 0) return src

    val targetRatio = SEEK_THUMB_W.toFloat() / SEEK_THUMB_H
    val srcRatio = srcW.toFloat() / srcH

    // 先等比放大/缩小，使短边覆盖目标尺寸
    val (scaledW, scaledH) = if (srcRatio > targetRatio) {
        // 源更宽 → 以高为准
        (srcW.toFloat() * SEEK_THUMB_H / srcH).toInt().coerceAtLeast(SEEK_THUMB_W) to SEEK_THUMB_H
    } else {
        SEEK_THUMB_W to (srcH.toFloat() * SEEK_THUMB_W / srcW).toInt().coerceAtLeast(SEEK_THUMB_H)
    }

    val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
    // 居中裁切到目标尺寸
    val x = ((scaledW - SEEK_THUMB_W) / 2).coerceAtLeast(0)
    val y = ((scaledH - SEEK_THUMB_H) / 2).coerceAtLeast(0)
    val out = if (scaledW == SEEK_THUMB_W && scaledH == SEEK_THUMB_H) {
        scaled
    } else {
        Bitmap.createBitmap(scaled, x, y, SEEK_THUMB_W, SEEK_THUMB_H)
    }
    if (out !== scaled && scaled !== src) {
        scaled.recycle()
    }
    return out
}



/**
 * v2.4.5：把弹幕请求的失败原因（[DanmuVisionClient.ChatErrorKind]）映射为本地化文案。
 *
 * ## 为什么需要（实测事故）
 * v2.4.4 及之前，弹幕请求失败一律塌缩成「空列表」，编排层只能显示
 * `danmu_err_empty_response`（「模型未返回可用弹幕」）。而实际日志里可能是
 * `视觉请求失败（AUTH）: HTTP 401 | {"detail":"Invalid bearer token"}` ——
 * **密钥无效**，与「模型没返回内容」是完全不同的问题，
 * 用户会去查模型名与网络，**排查方向全错**。
 *
 * 现在按 kind 给出**准确的、可操作的**提示。
 *
 * ⚠️ `NO_CONTENT` / `EMPTY_RESPONSE` 才回落到原来那句「模型未返回可用弹幕」——
 *    因为只有这一类才是**真的**「模型没给出可用内容」。
 *
 * @param context 取字符串资源
 * @param kind    客户端返回的失败类别
 */
internal fun danmuErrorText(
    context: android.content.Context,
    kind: DanmuVisionClient.ChatErrorKind
): String = when (kind) {
    // 成功 —— 调用方不应走到这里；兜底返回空串（状态卡片会显示「已生成」）
    DanmuVisionClient.ChatErrorKind.NONE -> ""
    DanmuVisionClient.ChatErrorKind.NOT_CONFIGURED ->
        context.getString(R.string.danmu_err_not_configured)
    DanmuVisionClient.ChatErrorKind.BAD_URL ->
        context.getString(R.string.danmu_err_url_bad_scheme)
    // 🔴 v2.4.4 实测事故：密钥无效（HTTP 401 Invalid bearer token）
    DanmuVisionClient.ChatErrorKind.AUTH ->
        context.getString(R.string.danmu_err_auth)
    // 🔴 模型名不被识别（大小写敏感）—— v2.4.4 头号坑
    DanmuVisionClient.ChatErrorKind.MODEL_NOT_FOUND ->
        context.getString(R.string.danmu_err_model_not_found)
    // HTTP/2 RST_STREAM：多数情况下与 MODEL_NOT_FOUND 同源
    DanmuVisionClient.ChatErrorKind.STREAM_RESET ->
        context.getString(R.string.danmu_err_stream_reset)
    DanmuVisionClient.ChatErrorKind.RATE_LIMIT ->
        context.getString(R.string.danmu_test_hint_rate_limit)
    DanmuVisionClient.ChatErrorKind.SERVER_ERROR ->
        context.getString(R.string.danmu_test_hint_server_error)
    DanmuVisionClient.ChatErrorKind.HTTP_ERROR ->
        context.getString(R.string.danmu_err_network)
    DanmuVisionClient.ChatErrorKind.DNS,
    DanmuVisionClient.ChatErrorKind.CONNECT,
    DanmuVisionClient.ChatErrorKind.TIMEOUT,
    DanmuVisionClient.ChatErrorKind.NETWORK ->
        context.getString(R.string.danmu_err_network)
    // 只有这两类才是真的「模型返回了但不可用」
    DanmuVisionClient.ChatErrorKind.NO_CONTENT,
    DanmuVisionClient.ChatErrorKind.EMPTY_RESPONSE ->
        context.getString(R.string.danmu_err_empty_response)
}

/**
 * v2.6.8：把「失败原因」「第几次失败」「还有多久重试」拼成一行状态文案。
 *
 * ## 为什么集中在一处
 * 秒/分钟的换挡（≥90 秒按分钟显示）只该有一个实现，否则界面里会出现
 * 「300 秒后重试」这种既不友好、又让人以为卡住的数字。
 *
 * ## 为什么显示「第几次失败」
 * 只给倒计时的话，用户无法判断是"偶发一次马上就好"还是"已经连错二十次、该去检查配置了"。
 * 次数正是退避策略的输入，展示出来让自愈过程可观测。
 *
 * ## 为什么用 `getString(..., args)` 而不是字符串拼接
 * 各语言的括号与空格规则不同（日文用全角括号、英文要空格），
 * 交给 `strings.xml` 的占位符更稳；`%1$s` 里的内容不会被二次格式化。
 *
 * 占位符顺序在所有语言里统一为：`%1$s` 原因 / `%2$d` 第几次失败 / `%3$d` 秒或分钟。
 *
 * @param text     原始错误文案（[danmuErrorText] 的输出）；为空时原样返回
 * @param delayMs  本次退避时长
 * @param failures 连续失败次数（失败分支里已自增，从 1 开始）
 */
internal fun danmuRetryHint(
    context: android.content.Context,
    text: String,
    delayMs: Long,
    failures: Int
): String {
    if (text.isBlank()) return text
    val sec = DanmuRetryPolicy.delaySeconds(delayMs)
    val n = failures.coerceAtLeast(1)
    return if (sec >= 90) {
        context.getString(R.string.danmu_retry_in_min, text, n, (sec + 59) / 60)
    } else {
        context.getString(R.string.danmu_retry_in_sec, text, n, sec)
    }
}
