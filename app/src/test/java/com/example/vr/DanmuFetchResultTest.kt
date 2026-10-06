package com.example.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4.5：`DanmuVisionClient.DanmuFetchResult` 语义测试。
 *
 * ## 背景（实测事故）
 * v2.4.4 发布后用户反馈「模型未返回可用弹幕 请稍后重试」，但日志里实际是：
 * ```
 * E DanmuVisionClient: 视觉请求失败（AUTH）: HTTP 401 | {"detail":"Invalid bearer token"}
 * ```
 * —— **密钥无效**，与「模型没返回内容」是完全不同的问题。
 * 原因是失败一律塌缩成空列表，编排层拿不到原因，只能显示笼统文案。
 *
 * 本测试锁住 `isSuccess` 的判定：**只有"拿到至少一条弹幕"才算成功**，
 * 避免以后有人误把「kind == NONE」当成成功（那样空列表也会被判成功，
 * 用户会看到「已生成 0 条」这种自相矛盾的状态）。
 */
class DanmuFetchResultTest {

    @Test
    fun `有弹幕且 kind 为 NONE 时算成功`() {
        val r = DanmuVisionClient.DanmuFetchResult(
            lines = listOf("这条弹幕", "那条弹幕"),
            kind = DanmuVisionClient.ChatErrorKind.NONE
        )
        assertTrue(r.isSuccess)
    }

    @Test
    fun `空列表即使 kind 为 NONE 也不算成功`() {
        // 这种组合不该出现（客户端会返回 NO_CONTENT），但若出现也不能判成功
        val r = DanmuVisionClient.DanmuFetchResult(
            lines = emptyList(),
            kind = DanmuVisionClient.ChatErrorKind.NONE
        )
        assertFalse(r.isSuccess)
    }

    @Test
    fun `密钥无效时不算成功`() {
        val r = DanmuVisionClient.DanmuFetchResult(
            lines = emptyList(),
            kind = DanmuVisionClient.ChatErrorKind.AUTH,
            detail = "HTTP 401 | {\"detail\":\"Invalid bearer token\"}"
        )
        assertFalse(r.isSuccess)
        assertEquals(DanmuVisionClient.ChatErrorKind.AUTH, r.kind)
    }

    @Test
    fun `模型名不被识别时不算成功`() {
        val r = DanmuVisionClient.DanmuFetchResult(
            lines = emptyList(),
            kind = DanmuVisionClient.ChatErrorKind.MODEL_NOT_FOUND,
            detail = "HTTP 404 | model_not_found"
        )
        assertFalse(r.isSuccess)
    }

    @Test
    fun `流被重置时不算成功`() {
        val r = DanmuVisionClient.DanmuFetchResult(
            lines = emptyList(),
            kind = DanmuVisionClient.ChatErrorKind.STREAM_RESET
        )
        assertFalse(r.isSuccess)
    }

    @Test
    fun `detail 默认值为空串（调用方可选填）`() {
        val r = DanmuVisionClient.DanmuFetchResult(emptyList(), DanmuVisionClient.ChatErrorKind.TIMEOUT)
        assertEquals("", r.detail)
    }

    @Test
    fun `错误类别覆盖 401 404 与 RST 三个关键场景`() {
        // 这三类是本轮排查遇到的核心场景，必须有独立类别（不能混进 NETWORK）
        val kinds = DanmuVisionClient.ChatErrorKind.values().map { it.name }
        assertTrue(kinds.contains("AUTH"))
        assertTrue(kinds.contains("MODEL_NOT_FOUND"))
        assertTrue(kinds.contains("STREAM_RESET"))
    }
}
