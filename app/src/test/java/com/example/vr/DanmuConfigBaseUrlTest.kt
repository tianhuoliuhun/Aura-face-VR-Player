package com.example.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `DanmuConfig.baseUrlProblem()` 单元测试（v2.4.2）
 *
 * ## 背景（真实事故）
 * 用户把 Base URL 填成 `hhttps://api.xiaomimimo.com/v1`（多打了一个 h）：
 * v2.4.1 只校验「非空」→ 通过 → `resolveEndpoint()` 拼出非法 URL →
 * OkHttp 在 `Request.Builder.url()` 抛 `IllegalArgumentException` →
 * 编排循环归入笼统的「意外错误」，每 5 秒重试一次，**用户完全看不出是拼错**。
 *
 * 这里覆盖该方法的所有分支，确保错误能被准确分类（→ 映射到不同文案）。
 */
class DanmuConfigBaseUrlTest {

    private fun cfg(url: String) = DanmuConfig(baseUrl = url)

    // ------------------------------------------------------------ EMPTY

    @Test
    fun `空字符串判定为空`() {
        assertEquals(DanmuConfig.BaseUrlProblem.EMPTY, cfg("").baseUrlProblem())
    }

    @Test
    fun `纯空白也判定为空`() {
        assertEquals(DanmuConfig.BaseUrlProblem.EMPTY, cfg("   \t ").baseUrlProblem())
    }

    // -------------------------------------------------- MISSING_SCHEME

    @Test
    fun `没有协议的裸域名判定为缺少前缀`() {
        assertEquals(DanmuConfig.BaseUrlProblem.MISSING_SCHEME, cfg("api.xiaomimimo.com/v1").baseUrlProblem())
    }

    @Test
    fun `只有斜杠开头判定为缺少前缀`() {
        assertEquals(DanmuConfig.BaseUrlProblem.MISSING_SCHEME, cfg("//api.xiaomimimo.com/v1").baseUrlProblem())
    }

    // ------------------------------------------------------ BAD_SCHEME

    /** 这就是本次线上事故的输入：多打一个 h */
    @Test
    fun `hhttps 判定为协议错误`() {
        assertEquals(DanmuConfig.BaseUrlProblem.BAD_SCHEME, cfg("hhttps://api.xiaomimimo.com/v1").baseUrlProblem())
    }

    @Test
    fun `htps 判定为协议错误`() {
        assertEquals(DanmuConfig.BaseUrlProblem.BAD_SCHEME, cfg("htps://api.xiaomimimo.com/v1").baseUrlProblem())
    }

    @Test
    fun `ftp 判定为协议错误`() {
        assertEquals(DanmuConfig.BaseUrlProblem.BAD_SCHEME, cfg("ftp://api.xiaomimimo.com/v1").baseUrlProblem())
    }

    @Test
    fun `协议大小写不敏感 HTTPS 视为合法`() {
        assertNull(cfg("HTTPS://api.xiaomimimo.com/v1").baseUrlProblem())
    }

    @Test
    fun `协议大小写不敏感 Http 视为合法`() {
        assertNull(cfg("Http://api.xiaomimimo.com/v1").baseUrlProblem())
    }

    // ------------------------------------------------------ MISSING_HOST

    @Test
    fun `只有 https 冒号斜杠判定为缺少主机名`() {
        assertEquals(DanmuConfig.BaseUrlProblem.MISSING_HOST, cfg("https://").baseUrlProblem())
    }

    @Test
    fun `https 加斜杠开头判定为缺少主机名`() {
        assertEquals(DanmuConfig.BaseUrlProblem.MISSING_HOST, cfg("https:///v1").baseUrlProblem())
    }

    // ------------------------------------------------------------- null

    @Test
    fun `标准地址没有问题`() {
        assertNull(cfg("https://api.xiaomimimo.com/v1").baseUrlProblem())
    }

    @Test
    fun `带尾部斜杠的地址没有问题`() {
        assertNull(cfg("https://api.xiaomimimo.com/v1/").baseUrlProblem())
    }

    @Test
    fun `带端口号的地址没有问题`() {
        assertNull(cfg("http://127.0.0.1:8080/v1").baseUrlProblem())
    }

    @Test
    fun `首尾空白会被裁掉后判定合法`() {
        assertNull(cfg("  https://api.xiaomimimo.com/v1  ").baseUrlProblem())
    }

    @Test
    fun `默认配置本身是合法的`() {
        assertNull(DanmuConfig().baseUrlProblem())
    }

    // ------------------------------------------------- 与 resolveEndpoint 的关系

    /**
     * 回归保护：`baseUrlProblem()` 报错时，`resolveEndpoint()` 拼出来的东西
     * 一定是 OkHttp 会拒绝的 —— 这正是需要在**发请求前**拦住的原因。
     */
    @Test
    fun `协议错误时拼接结果仍是非法 URL`() {
        val url = cfg("hhttps://api.xiaomimimo.com/v1").resolveEndpoint()
        assertEquals("hhttps://api.xiaomimimo.com/v1/chat/completions", url)
        // 提取 scheme，确认它不在允许集合内（模拟 OkHttp 的判定口径）
        val scheme = url.substringBefore(':').lowercase()
        assertEquals("hhttps", scheme)
    }
}
