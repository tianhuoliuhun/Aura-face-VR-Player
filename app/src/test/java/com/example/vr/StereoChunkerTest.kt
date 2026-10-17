package com.example.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4.68：**流式窗口切分（StereoChunker）单测**。
 *
 * 为什么必须测：窗口错一帧，整段输出就会**整体偏移**，而且听起来只是"对不上嘴"，
 * 很难反推到"分块边界算错了"。用「样本值 = 全局索引」这种自描述数据，
 * 就能精确断言每个窗口的起点、长度与重叠区内容。
 *
 * 用小尺度参数（15s/13s 缩到 1500/1300）等价验证逻辑，跑得快且易读。
 */
class StereoChunkerTest {

    @Test
    fun `windows step by hop and overlap by win-hop`() {
        val win = 1500
        val hop = 1300
        val total = 3000

        val l = FloatArray(total) { it.toFloat() }
        val r = FloatArray(total) { it.toFloat() }
        val windows = mutableListOf<FloatArray>()

        val chunker = StereoChunker(win, hop)
        val ok = chunker.feed(l, r, total) { wl, _, n ->
            windows.add(wl.copyOf(n))
            true
        }
        chunker.flush { wl, _, n ->
            windows.add(wl.copyOf(n))
            true
        }

        assertTrue("feed 应正常返回", ok)
        // 3000 样本 / 步进 1300：第 1 窗 0..1499、第 2 窗 1300..2799、flush 剩 2800..2999
        assertEquals("应产出 3 个窗口（2 满窗 + 1 尾巴）", 3, windows.size)

        assertEquals(win, windows[0].size)
        assertEquals(0f, windows[0][0], 0f)
        assertEquals((win - 1).toFloat(), windows[0][win - 1], 0f)

        assertEquals(win, windows[1].size)
        assertEquals(hop.toFloat(), windows[1][0], 0f)
        assertEquals((hop + win - 1).toFloat(), windows[1][win - 1], 0f)

        // 尾巴：2600..2999（= 保留的 200 重叠样本 + 新增 200，共 400）
        // ⚠️ 末窗**包含重叠部分**是正确的 —— MDX 对非首块会裁掉前 margin（1s），
        //    所以重叠区由调用方裁掉即可；这里若只有 200 反而说明数据丢了。
        assertEquals(400, windows[2].size)
        assertEquals(2600f, windows[2][0], 0f)

        // 🔴 关键：相邻窗的**重叠区内容必须一致**（说明"保留重叠 + 续写"没写错）
        val overlap = win - hop
        for (i in 0 until overlap) {
            assertEquals(
                "重叠区第 $i 个样本应一致",
                windows[0][hop + i],
                windows[1][i],
                0f,
            )
        }
    }

    @Test
    fun `single short input yields one flushed window`() {
        val win = 1000
        val hop = 800
        val l = FloatArray(300) { it.toFloat() }
        val r = FloatArray(300) { it.toFloat() }
        val windows = mutableListOf<Int>()
        val c = StereoChunker(win, hop)
        c.feed(l, r, 300) { _, _, n -> windows.add(n); true }
        c.flush { _, _, n -> windows.add(n); true }
        assertEquals(1, windows.size)
        assertEquals(300, windows[0])
    }

    @Test
    fun `abort from callback stops feeding`() {
        val c = StereoChunker(1000, 800)
        val l = FloatArray(5000) { it.toFloat() }
        val r = FloatArray(5000) { it.toFloat() }
        var count = 0
        val ok = c.feed(l, r, 5000) { _, _, _ ->
            count++
            count < 2   // 第二个窗口时要求中止
        }
        assertTrue("回调返回 false 时 feed 应返回 false", !ok)
        assertEquals("只应产出 2 个窗口就停下", 2, count)
    }

    @Test
    fun `real parameters match mdx outer chunking`() {
        // 与 MDX 的外层分块严格对齐：15s 窗 / 13s 步进
        assertEquals(MdxDsp.OUTER_CHUNK, MdxDsp.OUTER_CHUNK_SEC * MdxDsp.SAMPLE_RATE)
        assertEquals(MdxDsp.OUTER_STEP, (MdxDsp.OUTER_CHUNK_SEC - MdxDsp.OUTER_OVERLAP_SEC) * MdxDsp.SAMPLE_RATE)
        assertEquals(661500, MdxDsp.OUTER_CHUNK)
        assertEquals(573300, MdxDsp.OUTER_STEP)
        // 重叠 = 2 秒
        assertEquals(2 * MdxDsp.SAMPLE_RATE, MdxDsp.OUTER_CHUNK - MdxDsp.OUTER_STEP)
    }

    @Test
    fun `invalid parameters are rejected`() {
        var threw = false
        try {
            StereoChunker(100, 200)   // hop > win
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("hop > win 应被拒绝", threw)
    }
}
