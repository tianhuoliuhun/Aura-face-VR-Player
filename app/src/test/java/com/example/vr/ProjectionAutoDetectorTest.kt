package com.example.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.8：`ProjectionAutoDetector` 语义测试。
 *
 * ## 背景
 * 原判定写死在 `onVideoSizeChanged` 回调里，窗口是 `1.80f..2.20f`：
 * - **1.85:1 的普通电影**（1998×1080）落在窗口内 → 被判成 360 全景，
 *   画面被塞进球面投影严重变形，还得手动改回来；
 * - **2.2:1** 同样被误判；
 * - **4:1 的左右立体全景**（3840×960）落在窗口外 → 判成平面，
 *   并顺手把立体模式清成 MONO，3D 全景完全不可用。
 *
 * 本测试锁住三条语义：
 * 1. 只有**真 2:1**（±2.5%）才判全景；
 * 2. **4:1 判 360 + SBS**；
 * 3. 尺寸无效（≤0）返回 null —— 不能把 0x0 当成"平面视频"去重置用户已有模式。
 */
class ProjectionAutoDetectorTest {

    @Test
    fun `标准等距柱状全景判 360 单目`() {
        // 2026 年实际片源的常见尺寸：1920x960 / 2048x1024 / 3840x1920 / 5760x2880
        listOf(
            1920 to 960,
            2048 to 1024,
            3840 to 1920,
            5760 to 2880,
            7680 to 3840
        ).forEach { (w, h) ->
            val s = ProjectionAutoDetector.detect(w, h)
            assertEquals("${w}x$h 应为 360", ProjectionMode.VR_360, s!!.mode)
            assertEquals("${w}x$h 应为单目", StereoMode.MONO, s.stereo)
            assertTrue("${w}x$h 应开双中心变形", s.warpDualCenter)
            assertEquals(ProjectionAutoDetector.Reason.EQUIRECT_360, s.reason)
        }
    }

    @Test
    fun `185 比 1 的普通电影不再被误判成全景`() {
        // 🔴 回归用例：旧窗口 1.80~2.20 会命中 1.85，导致普通宽银幕电影被球面化
        val s = ProjectionAutoDetector.detect(1998, 1080)!!
        assertEquals(ProjectionMode.STANDARD, s.mode)
        assertEquals(StereoMode.MONO, s.stereo)
        assertFalse(s.warpDualCenter)
        assertEquals(ProjectionAutoDetector.Reason.FLAT, s.reason)
    }

    @Test
    fun `22 比 1 也不被误判成全景`() {
        // 🔴 旧窗口上限正好是 2.20，2.2:1 的画幅会踩在边界上
        val s = ProjectionAutoDetector.detect(2200, 1000)!!
        assertEquals(ProjectionMode.STANDARD, s.mode)
        assertEquals(ProjectionAutoDetector.Reason.FLAT, s.reason)
    }

    @Test
    fun `左右立体 3D 全景判 360 且带 SBS`() {
        // 4:1 = 左右各一张 2:1 equirect
        listOf(3840 to 960, 4096 to 1024).forEach { (w, h) ->
            val s = ProjectionAutoDetector.detect(w, h)!!
            assertEquals("${w}x$h 应为 360", ProjectionMode.VR_360, s.mode)
            assertEquals("${w}x$h 应为 SBS", StereoMode.SBS, s.stereo)
            // 双中心变形只在 MONO 下生效（shader 显式判断 uStereoMode == 0）
            assertFalse(s.warpDualCenter)
            assertEquals(ProjectionAutoDetector.Reason.EQUIRECT_360_SBS, s.reason)
        }
    }

    @Test
    fun `普通画幅一律判平面`() {
        listOf(
            1920 to 1080,    // 16:9
            1280 to 720,
            2048 to 858,     // 2.39 宽银幕
            1080 to 1920,    // 竖屏
            1000 to 1000,    // 方形
            3840 to 1600     // 2.4:1
        ).forEach { (w, h) ->
            val s = ProjectionAutoDetector.detect(w, h)!!
            assertEquals("${w}x$h 应为平面", ProjectionMode.STANDARD, s.mode)
            assertEquals(ProjectionAutoDetector.Reason.FLAT, s.reason)
        }
    }

    @Test
    fun `窗口边界按闭区间处理`() {
        // 1.95 / 2.05 是刻意留的 ±2.5% 容差，边界本身必须算命中
        assertEquals(ProjectionMode.VR_360, ProjectionAutoDetector.detect(1872, 960)!!.mode)  // 1.95
        assertEquals(ProjectionMode.VR_360, ProjectionAutoDetector.detect(1968, 960)!!.mode)  // 2.05
        // 刚出窗口一侧
        assertEquals(ProjectionMode.STANDARD, ProjectionAutoDetector.detect(1776, 960)!!.mode) // 1.85
        assertEquals(ProjectionMode.STANDARD, ProjectionAutoDetector.detect(2064, 960)!!.mode) // 2.15
    }

    @Test
    fun `尺寸无效时返回 null 而不是当成平面`() {
        // 解码器初始化时可能先回调 0x0；若返回 FLAT，会立刻把用户已设好的模式重置
        assertNull(ProjectionAutoDetector.detect(0, 0))
        assertNull(ProjectionAutoDetector.detect(1920, 0))
        assertNull(ProjectionAutoDetector.detect(0, 1080))
        assertNull(ProjectionAutoDetector.detect(-1, 1080))
    }

    @Test
    fun `isEquirect 与 detect 使用同一窗口`() {
        // 「平面投影下清残留 SBS」那段走的是 isEquirect，必须与 detect 判定一致，
        // 否则会出现「检测判 2D、清立体却按 360 判断」的自相矛盾状态。
        assertTrue(ProjectionAutoDetector.isEquirect(2.0f))
        assertTrue(ProjectionAutoDetector.isEquirect(1.95f))
        assertTrue(ProjectionAutoDetector.isEquirect(2.05f))
        assertFalse(ProjectionAutoDetector.isEquirect(1.85f))
        assertFalse(ProjectionAutoDetector.isEquirect(2.39f))
        assertFalse(ProjectionAutoDetector.isEquirect(4.0f))
    }

    @Test
    fun `isSbsPano 窗口与 2 比 1 不重叠`() {
        assertTrue(ProjectionAutoDetector.isSbsPano(4.0f))
        assertTrue(ProjectionAutoDetector.isSbsPano(3.90f))
        assertTrue(ProjectionAutoDetector.isSbsPano(4.10f))
        assertFalse(ProjectionAutoDetector.isSbsPano(2.0f))
        assertFalse(ProjectionAutoDetector.isSbsPano(3.5f))
    }
}
