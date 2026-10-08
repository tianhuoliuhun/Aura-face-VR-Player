package com.example.vr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4.11：图像缩放与**通道序修正**的单测。
 *
 * 被测的两件事都在「取帧 → 交给视觉模型」这条链路上，出错都不会崩、只会静默劣化：
 * 1. [ImageScale.fitLongSide] 算错 → 图被放大（白耗 token）或长边超出设定值；
 * 2. [ImageScale.swapRedBlueInPlace] 漏做/多做 → **画面红蓝偏色**（本次要修的 bug）。
 */
class ImageScaleTest {

    // ===============================================================
    // 一、长边等比缩放
    // ===============================================================

    @Test
    fun `横屏片源按宽缩小`() {
        // 1920×1080，长边是宽 → 缩到 480×270
        val r = ImageScale.fitLongSide(1920, 1080, 480)
        assertEquals(480, r[0])
        assertEquals(270, r[1])
    }

    @Test
    fun `竖屏片源按高缩小（这是「长边」与「按宽」的关键区别）`() {
        // 1080×1920，长边是高 → 必须缩到高 = 480，即 270×480
        // ⚠️ 旧实现按宽缩会得到 480×853，长边 853 远超设定值 480
        val r = ImageScale.fitLongSide(1080, 1920, 480)
        assertEquals(270, r[0])
        assertEquals(480, r[1])
    }

    @Test
    fun `正方形两边同时缩到目标`() {
        val r = ImageScale.fitLongSide(1000, 1000, 256)
        assertEquals(256, r[0])
        assertEquals(256, r[1])
    }

    @Test
    fun `默认 256 下的常见片源`() {
        val r = ImageScale.fitLongSide(1920, 1080, 256)
        assertEquals(256, r[0])
        assertEquals(144, r[1])
    }

    @Test
    fun `长边结果永远不超过设定值`() {
        val cases = listOf(
            Triple(1920, 1080, 256), Triple(1080, 1920, 384),
            Triple(3840, 2160, 720), Triple(720, 1280, 512),
            Triple(2560, 1440, 1024), Triple(100, 4000, 512)
        )
        cases.forEach { (w, h, limit) ->
            val r = ImageScale.fitLongSide(w, h, limit)
            val longSide = maxOf(r[0], r[1])
            assertTrue("$w×$h → ${r[0]}×${r[1]} 长边 $longSide 超过 $limit", longSide <= limit)
        }
    }

    @Test
    fun `不放大`() {
        // 源图长边已小于目标 → 原样返回
        assertArrayEquals(intArrayOf(200, 100), ImageScale.fitLongSide(200, 100, 256))
        assertArrayEquals(intArrayOf(256, 144), ImageScale.fitLongSide(256, 144, 256))
    }

    @Test
    fun `宽高比基本保持`() {
        val r = ImageScale.fitLongSide(1920, 1080, 256)
        val srcRatio = 1920.0 / 1080
        val dstRatio = r[0].toDouble() / r[1]
        assertTrue("宽高比偏差过大：$srcRatio vs $dstRatio", kotlin.math.abs(srcRatio - dstRatio) < 0.02)
    }

    @Test
    fun `尺寸永远至少为 1`() {
        // 极端窄长：1920×1 缩到长边 64 → 高可能算成 0，必须兜成 1
        val r = ImageScale.fitLongSide(1920, 1, 64)
        assertTrue("宽必须 >= 1", r[0] >= 1)
        assertTrue("高必须 >= 1（否则 createScaledBitmap 抛异常）", r[1] >= 1)
    }

    @Test
    fun `非法输入返回 1x1 且不崩`() {
        listOf(
            Triple(0, 100, 256), Triple(100, 0, 256),
            Triple(-5, 100, 256), Triple(100, -5, 256)
        ).forEach { (w, h, l) ->
            assertArrayEquals("w=$w h=$h", intArrayOf(1, 1), ImageScale.fitLongSide(w, h, l))
        }
    }

    @Test
    fun `长边参数被夹到合法区间`() {
        // 过小 → 夹到 MIN；过大 → 夹到 MAX
        assertEquals(ImageScale.MIN_LONG_SIDE, ImageScale.fitLongSide(10000, 10000, 1)[0])
        assertEquals(ImageScale.MAX_LONG_SIDE, ImageScale.fitLongSide(100000, 100000, 999999)[0])
    }

    @Test
    fun `needsScale 与 fitLongSide 一致`() {
        assertTrue(ImageScale.needsScale(1920, 1080, 256))
        assertFalse(ImageScale.needsScale(200, 100, 256))
        assertFalse(ImageScale.needsScale(256, 256, 256))
    }

    @Test
    fun `rati 在不需要缩放时为 1`() {
        assertEquals(1f, ImageScale.ratio(200, 100, 256), 1e-6f)
        assertTrue("需要时应小于 1", ImageScale.ratio(1920, 1080, 256) < 1f)
    }

    @Test
    fun `可选档位与 ImageScale 的上下限自洽`() {
        DanmuConfig.IMAGE_LONG_SIDE_CHOICES.forEach { v ->
            assertTrue(
                "档位 $v 超出 ImageScale 允许范围",
                v >= ImageScale.MIN_LONG_SIDE && v <= ImageScale.MAX_LONG_SIDE
            )
        }
        assertEquals("默认档位必须在候选列表里", true,
            DanmuConfig.DEFAULT_IMAGE_LONG_SIDE in DanmuConfig.IMAGE_LONG_SIDE_CHOICES)
        assertEquals("默认应为 256", 256, DanmuConfig.DEFAULT_IMAGE_LONG_SIDE)
    }

    // ===============================================================
    // 二、通道序修正（本次偏色 bug 的修复点）
    // ===============================================================

    @Test
    fun `ABGR 被正确转成 ARGB`() {
        // GL 回读：字节序 [R][G][B][A] = [0x33][0x22][0x11][0xFF]
        // 小端读成 int：低 8 位=R → 0xFF112233
        val glInt = 0xFF112233.toInt()
        val px = intArrayOf(glInt)
        ImageScale.swapRedBlueInPlace(px)
        // 期望 ARGB：(A=FF)(R=33)(G=22)(B=11) = 0xFF332211
        assertEquals(0xFF332211.toInt(), px[0])
    }

    @Test
    fun `交换只动 R 与 B，A 与 G 不变`() {
        val a = 0xAB
        val g = 0xCD
        val px = intArrayOf((a shl 24) or (0x11 shl 16) or (g shl 8) or 0x22)
        ImageScale.swapRedBlueInPlace(px)
        assertEquals("A 必须不变", a, (px[0] ushr 24) and 0xFF)
        assertEquals("G 必须不变", g, (px[0] ushr 8) and 0xFF)
        assertEquals("R 应拿到原 B", 0x22, (px[0] ushr 16) and 0xFF)
        assertEquals("B 应拿到原 R", 0x11, px[0] and 0xFF)
    }

    @Test
    fun `交换两次回到原值（幂等性护栏）`() {
        val px = intArrayOf(0xFF112233.toInt(), 0x00123456, 0xFFFFFFFF.toInt(), 0)
        val before = px.copyOf()
        ImageScale.swapRedBlueInPlace(px)
        ImageScale.swapRedBlueInPlace(px)
        assertArrayEquals("交换两次应复原", before, px)
    }

    @Test
    fun `纯灰不受交换影响`() {
        // R==G==B 时交换无变化 —— 这解释了为什么灰调画面看不出偏色
        val gray = (0xFF shl 24) or (0x80 shl 16) or (0x80 shl 8) or 0x80
        val px = intArrayOf(gray)
        ImageScale.swapRedBlueInPlace(px)
        assertEquals(gray, px[0])
    }

    @Test
    fun `count 参数只处理前 n 个`() {
        val px = intArrayOf(0xFF112233.toInt(), 0xFF112233.toInt())
        ImageScale.swapRedBlueInPlace(px, count = 1)
        assertEquals(0xFF332211.toInt(), px[0])
        assertEquals("第 2 个不应被处理", 0xFF112233.toInt(), px[1])
    }

    @Test
    fun `count 超过数组长度时不越界`() {
        val px = intArrayOf(0xFF112233.toInt())
        ImageScale.swapRedBlueInPlace(px, count = 999)
        assertEquals(0xFF332211.toInt(), px[0])
    }

    @Test
    fun `空数组不崩`() {
        ImageScale.swapRedBlueInPlace(IntArray(0))
        ImageScale.swapRedBlueInPlace(IntArray(0), count = 5)
    }
}
