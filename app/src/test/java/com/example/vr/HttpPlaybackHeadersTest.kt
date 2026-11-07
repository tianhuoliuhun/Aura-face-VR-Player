package com.example.vr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.0：回归「手动粘贴直链时的 Referer 推断缺陷」。
 *
 * 旧实现在没有来源页时把直链自己的 origin（`https://<cdn host>/`）当 Referer 发出去，
 * 但 CDN 校验的是**站点页面地址**，直链 origin 实测会直接 403（见 2026-10-19 日志对照表）。
 * 现在改为「无来源页不发 Referer」，只带桌面 UA + `Range: bytes=0-`。
 *
 * 纯 JVM 测试：HttpPlaybackHeaders 只依赖 Kotlin 标准库，无 Android/Context。
 */
class HttpPlaybackHeadersTest {

    @After
    fun tearDown() {
        HttpPlaybackHeaders.clear()
    }

    @Test
    fun noPageUrl_omitsReferer_keepsUaAndRange() {
        HttpPlaybackHeaders.forWebPlayback(null, "https://cdn.example.com/v.mp4?token=abc")

        assertNull(HttpPlaybackHeaders.referer)
        assertEquals(HttpPlaybackHeaders.DESKTOP_UA, HttpPlaybackHeaders.userAgent)

        val props = HttpPlaybackHeaders.asRequestProperties()!!
        assertFalse("无来源页时不应发送 Referer", props.containsKey("Referer"))
        assertEquals(HttpPlaybackHeaders.DESKTOP_UA, props["User-Agent"])
        assertEquals("bytes=0-", props["Range"])
        assertTrue(HttpPlaybackHeaders.isActive)
    }

    @Test
    fun blankPageUrl_treatedAsAbsent() {
        HttpPlaybackHeaders.forWebPlayback("   ", "https://cdn.example.com/v.mp4")

        assertNull(HttpPlaybackHeaders.referer)
        assertFalse(HttpPlaybackHeaders.asRequestProperties()!!.containsKey("Referer"))
    }

    @Test
    fun pageUrl_usedAsReferer() {
        val page = "https://site.example.com/watch/123"
        HttpPlaybackHeaders.forWebPlayback(page, "https://cdn.example.com/v.mp4")

        assertEquals(page, HttpPlaybackHeaders.referer)
        assertEquals(page, HttpPlaybackHeaders.asRequestProperties()!!["Referer"])
        assertEquals("bytes=0-", HttpPlaybackHeaders.asRequestProperties()!!["Range"])
    }

    @Test
    fun pageUrl_isTrimmed() {
        HttpPlaybackHeaders.forWebPlayback(
            "  https://site.example.com/p  ",
            "https://cdn.example.com/v.mp4",
        )
        assertEquals("https://site.example.com/p", HttpPlaybackHeaders.referer)
    }

    @Test
    fun neverFallsBackToStreamOrigin() {
        // 旧缺陷的直接回归断言：任何请求头里都不应出现直链 CDN 域名
        HttpPlaybackHeaders.forWebPlayback(null, "https://cdn3.example.com/xyz.mp4")

        val props = HttpPlaybackHeaders.asRequestProperties()!!
        assertFalse(
            props.values.any { it.contains("cdn3.example.com", ignoreCase = true) },
        )
    }

    @Test
    fun clear_removesWholeContext() {
        HttpPlaybackHeaders.forWebPlayback(
            "https://site.example.com/p",
            "https://cdn.example.com/v.mp4",
        )
        HttpPlaybackHeaders.clear()

        assertNull(HttpPlaybackHeaders.asRequestProperties())
        assertFalse(HttpPlaybackHeaders.isActive)
    }

    // ======================================================================
    // v2.9.1：MPV 内核也要带上同一份 UA/Referer（否则回退 MPV 就是裸请求 → 403）
    // ======================================================================

    @Test
    fun mpvFields_emptyWithoutWebContext() {
        assertTrue(
            "本地文件播放时不能给 mpv 塞任何头，否则会污染后续播放",
            HttpPlaybackHeaders.asMpvHeaderFields().isEmpty(),
        )
    }

    @Test
    fun mpvFields_uaOnlyWhenNoPageUrl() {
        HttpPlaybackHeaders.forWebPlayback(null, "https://cdn.example.com/v.mp4")

        assertEquals(
            listOf("User-Agent: ${HttpPlaybackHeaders.DESKTOP_UA}"),
            HttpPlaybackHeaders.asMpvHeaderFields(),
        )
    }

    @Test
    fun mpvFields_uaThenReferer() {
        val page = "https://site.example.com/watch/1"
        HttpPlaybackHeaders.forWebPlayback(page, "https://cdn.example.com/v.mp4")

        val fields = HttpPlaybackHeaders.asMpvHeaderFields()
        assertEquals(2, fields.size)
        assertTrue("User-Agent 应排在前", fields[0].startsWith("User-Agent: "))
        assertEquals("Referer: $page", fields[1])
    }

    @Test
    fun mpvFields_neverContainRange() {
        // 🔴 关键回归：mpv 的 Range 由 lavf 按 seek 位置自己生成，
        // 固定 bytes=0- 会让「拖动后仍只拿文件头」。
        HttpPlaybackHeaders.forWebPlayback("https://site.example.com/p", "https://cdn.example.com/v.mp4")

        assertFalse(
            "MPV 的头里绝不能出现 Range（会破坏 seek）",
            HttpPlaybackHeaders.asMpvHeaderFields().any {
                it.startsWith("Range", ignoreCase = true)
            },
        )
        // 对照：Exo 那份仍然要有 Range
        assertEquals("bytes=0-", HttpPlaybackHeaders.asRequestProperties()!!["Range"])
    }

    @Test
    fun mpvFields_uaKeepsLiteralComma() {
        // 🔴 桌面 UA 天然含逗号（`(KHTML, like Gecko)`）。
        // mpv 的 `http-header-fields` 是**逗号分隔**的 string list，拼成一个字符串会被拆坏。
        // 因此注入侧必须走 `http-header-fields-append` 逐条追加（见 MpvPlayerFactory）。
        // 这条断言把「UA 本来就有逗号」这个前提固定住 —— 否则有人会误以为过滤掉才对。
        HttpPlaybackHeaders.forWebPlayback(null, "https://cdn.example.com/v.mp4")

        val uaField = HttpPlaybackHeaders.asMpvHeaderFields().single()
        assertTrue("桌面 UA 本就含逗号，注入侧必须逐条 append 而不是拼串", uaField.contains(','))
        assertTrue(uaField.startsWith("User-Agent: "))
    }
}
