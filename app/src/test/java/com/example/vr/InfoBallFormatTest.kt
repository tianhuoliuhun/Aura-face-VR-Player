package com.example.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 信息球格式化逻辑的单测。
 *
 * 这些规则（12 小时制的 0 点 = 12、电量未知不能显示 -1%、温度缺失不能显示 NaN°）
 * 在设备上只能靠肉眼盯着小圆球看，因此全部抽成纯函数放在这里验证。
 */
class InfoBallFormatTest {

    // ---------------- 时钟 ----------------

    @Test
    fun `24h without seconds uses zero padding`() {
        val (main, sub) = InfoBallFormat.clock(14, 5, 9, hour12 = false, showSeconds = false)
        assertEquals("14:05", main)
        assertEquals("24H", sub)
    }

    @Test
    fun `24h with seconds includes zero-padded seconds`() {
        val (main, _) = InfoBallFormat.clock(0, 0, 3, hour12 = false, showSeconds = true)
        assertEquals("00:00:03", main)
    }

    @Test
    fun `12h midnight shows 12 not 0`() {
        // ⚠️ 最容易写错的边界：0 点在 12 小时制下必须是 12，且属于 AM
        val (main, sub) = InfoBallFormat.clock(0, 5, 0, hour12 = true, showSeconds = false)
        assertEquals("12:05", main)
        assertEquals("AM", sub)
    }

    @Test
    fun `12h afternoon maps and reports PM`() {
        val (main, sub) = InfoBallFormat.clock(13, 5, 0, hour12 = true, showSeconds = false)
        assertEquals("1:05", main)
        assertEquals("PM", sub)
    }

    @Test
    fun `12h noon shows 12 PM`() {
        val (main, sub) = InfoBallFormat.clock(12, 0, 0, hour12 = true, showSeconds = false)
        assertEquals("12:00", main)
        assertEquals("PM", sub)
    }

    @Test
    fun `11h59 is still AM`() {
        val (_, sub) = InfoBallFormat.clock(11, 59, 0, hour12 = true, showSeconds = false)
        assertEquals("AM", sub)
    }

    // ---------------- 电量 ----------------

    @Test
    fun `battery percent renders normally`() {
        assertEquals("85%", InfoBallFormat.batteryMain(85, 4.05f, showVolt = false))
    }

    @Test
    fun `unknown percent shows placeholder not negative`() {
        // 未知电量（-1）绝不能显示成 "-1%" —— 那是「电池不存在」的哨兵值
        assertEquals("--%", InfoBallFormat.batteryMain(-1, Float.NaN, showVolt = false))
    }

    @Test
    fun `voltage mode formats with two decimals`() {
        assertEquals("4.05V", InfoBallFormat.batteryMain(85, 4.05f, showVolt = true))
    }

    @Test
    fun `voltage mode with missing value shows placeholder`() {
        assertEquals("--V", InfoBallFormat.batteryMain(85, Float.NaN, showVolt = true))
    }

    @Test
    fun `charging sub line prefixes bolt and keeps temperature`() {
        assertEquals("⚡32°", InfoBallFormat.batterySub(charging = true, tempC = 32.4f))
    }

    @Test
    fun `charging sub line without temperature keeps only bolt`() {
        assertEquals("⚡", InfoBallFormat.batterySub(charging = true, tempC = Float.NaN))
    }

    @Test
    fun `not charging sub line is temperature only`() {
        assertEquals("28°", InfoBallFormat.batterySub(charging = false, tempC = 28.0f))
    }

    @Test
    fun `not charging and no temperature gives empty sub line`() {
        // 空串 → UI 不绘制副行（否则会出现一个孤零零的空白行把内容挤偏）
        assertEquals("", InfoBallFormat.batterySub(charging = false, tempC = Float.NaN))
    }

    // ---------------- 低电量阈值 ----------------

    @Test
    fun `low battery threshold is inclusive up to 15`() {
        assertTrue(InfoBallFormat.isLowBattery(0))
        assertTrue(InfoBallFormat.isLowBattery(15))
        assertFalse(InfoBallFormat.isLowBattery(16))
    }

    @Test
    fun `unknown battery is not treated as low`() {
        // -1（未知）不应触发红色告警
        assertFalse(InfoBallFormat.isLowBattery(-1))
    }

    // ---------------- 位置换算（比例 ↔ 像素） ----------------

    @Test
    fun `ratio converts pixel to fraction`() {
        assertEquals(0.5f, InfoBallGeometry.ratio(540f, 1080f), 1e-6f)
        assertEquals(0f, InfoBallGeometry.ratio(0f, 1080f), 1e-6f)
        assertEquals(1f, InfoBallGeometry.ratio(1080f, 1080f), 1e-6f)
    }

    @Test
    fun `ratio never divides by zero when container is not measured yet`() {
        // 首帧 maxX 可能是 0 —— 必须返回 0，而不是 NaN/Infinity（那会让球消失）
        assertEquals(0f, InfoBallGeometry.ratio(100f, 0f), 1e-6f)
        assertEquals(0f, InfoBallGeometry.ratio(100f, -5f), 1e-6f)
    }

    @Test
    fun `ratio clamps out-of-range pixels`() {
        assertEquals(1f, InfoBallGeometry.ratio(2000f, 1000f), 1e-6f)
        assertEquals(0f, InfoBallGeometry.ratio(-50f, 1000f), 1e-6f)
    }

    @Test
    fun `pixel returns zero before ratio is initialized`() {
        // NaN（尚未初始化）不能让 offset 变成 NaN
        assertEquals(0f, InfoBallGeometry.pixel(Float.NaN, 1080f), 1e-6f)
        assertEquals(0f, InfoBallGeometry.pixel(0.5f, 0f), 1e-6f)
    }

    @Test
    fun `pixel scales with container and clamps`() {
        assertEquals(540f, InfoBallGeometry.pixel(0.5f, 1080f), 1e-6f)
        assertEquals(1000f, InfoBallGeometry.pixel(1.5f, 1000f), 1e-6f)
        assertEquals(0f, InfoBallGeometry.pixel(-0.2f, 1000f), 1e-6f)
    }

    @Test
    fun `position survives container resize by keeping the same fraction`() {
        // 核心诉求：同一比例在横屏/竖屏下都应落在相对位置（球不会跑出屏幕）
        val ratio = InfoBallGeometry.ratio(900f, 1000f)   // 拖动后落在右侧 90%
        assertEquals(0.9f, ratio, 1e-6f)
        // 容器缩到 600 宽 → 像素应变成 540，而不是仍停在 900（越界）
        assertEquals(540f, InfoBallGeometry.pixel(ratio, 600f), 1e-6f)
    }
}
