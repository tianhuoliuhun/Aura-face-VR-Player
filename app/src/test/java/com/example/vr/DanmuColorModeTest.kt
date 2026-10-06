package com.example.vr

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4.6：弹幕颜色模式测试。
 *
 * ## 需求
 * 用户要求「颜色增加两种模式：完全随机 和 80%白色和其他随机」。
 *
 * ## 本测试锁住什么
 * 1. **单一颜色**：原样返回 `singleColor`（与 v2.3.0 行为一致，回归保护）
 * 2. **完全随机**：在色板内等概率取值，且**能取到色板里的每一个色**
 *    （若实现写成 `roll()` 只用于索引计算却忘了取整，会出现「永远取第一色」的静默 bug）
 * 3. **80% 白**：分布接近 80/20，且那 20% **不再出现白色**
 *    （否则实际白占比会变成 80% + 20%×(1/6) ≈ 83.3%，与文案不符）
 * 4. **色板不含黑色**：弹幕无底框 + 黑描边，黑字在暗场景不可读（用户明确要求换色板）
 *
 * ⚠️ 随机数通过 `roll` 参数注入，因此断言是**确定性**的，不依赖 `Random` 的真实行为。
 */
class DanmuColorModeTest {

    private val white = Color.White

    // ---------------------------------------------------------------- 单一颜色

    @Test
    fun `单一模式原样返回设定颜色`() {
        val single = Color(0xFF123456)
        val got = DanmuConfig.pickColor(DanmuColorMode.SINGLE, single) { 0.99f }
        assertEquals(single, got)
    }

    @Test
    fun `单一模式不受随机数影响`() {
        val single = Color(0xFFABCDEF)
        // 极端的 roll 也不该改变结果
        assertEquals(single, DanmuConfig.pickColor(DanmuColorMode.SINGLE, single) { 0f })
        assertEquals(single, DanmuConfig.pickColor(DanmuColorMode.SINGLE, single) { 1f })
    }

    // ---------------------------------------------------------------- 完全随机

    @Test
    fun `完全随机能取到色板的首色`() {
        val got = DanmuConfig.pickColor(DanmuColorMode.RANDOM, white) { 0f }
        assertEquals(DanmuConfig.DANMU_PALETTE[0], got)
    }

    @Test
    fun `完全随机能取到色板的末色`() {
        // roll 接近 1 → 索引应落到最后一个（关键：验证取整与边界 clamp 正确）
        val got = DanmuConfig.pickColor(DanmuColorMode.RANDOM, white) { 0.999999f }
        val last = DanmuConfig.DANMU_PALETTE.last()
        assertEquals(last, got)
    }

    @Test
    fun `完全随机的 roll 恰好为 1 时不越界`() {
        // roll() 理论上 < 1，但注入式随机可能返回 1f（如测试替身写错）→ 不能崩也不能越界
        val got = DanmuConfig.pickColor(DanmuColorMode.RANDOM, white) { 1f }
        assertTrue(DanmuConfig.DANMU_PALETTE.contains(got))
    }

    @Test
    fun `完全随机在色板范围内逐档取到全部颜色`() {
        // 用确定序列覆盖每个档位，验证「每个色都能取到」（防"永远取第一色"）
        val n = DanmuConfig.DANMU_PALETTE.size
        val seen = mutableSetOf<Color>()
        for (i in 0 until n) {
            val r = (i + 0.5f) / n  // 落在第 i 档中心
            seen.add(DanmuConfig.pickColor(DanmuColorMode.RANDOM, white) { r })
        }
        assertEquals("随机应覆盖色板全部 $n 色", n, seen.size)
    }

    @Test
    fun `完全随机与单一色板无关`() {
        // 单一色即使不在色板内，随机模式也只从色板取
        val weird = Color(0xFF000001)
        val got = DanmuConfig.pickColor(DanmuColorMode.RANDOM, weird) { 0.5f }
        assertNotEquals(weird, got)
        assertTrue(DanmuConfig.DANMU_PALETTE.contains(got))
    }

    // ---------------------------------------------------------------- 80% 白 + 其余随机

    @Test
    fun `八成白模式在低 roll 时返回白色`() {
        // roll=0.0 → 0 < 80 → 白
        assertEquals(white, DanmuConfig.pickColor(DanmuColorMode.MOSTLY_WHITE, white) { 0f })
        // roll=0.79 → 79 < 80 → 仍为白（边界内）
        assertEquals(white, DanmuConfig.pickColor(DanmuColorMode.MOSTLY_WHITE, white) { 0.79f })
    }

    @Test
    fun `八成白模式在高 roll 时返回彩色`() {
        // roll=0.81 → 81 >= 80 → 进入彩色分支
        val got = DanmuConfig.pickColor(DanmuColorMode.MOSTLY_WHITE, white) { 0.81f }
        assertNotEquals("超过阈值不应再返回白色", white, got)
        assertTrue(DanmuConfig.DANMU_PALETTE.contains(got))
    }

    @Test
    fun `八成白模式的彩色分支永不返回白色`() {
        // 遍历彩色分支的所有滚动值，白色必须一次都不出现
        val colored = DanmuConfig.DANMU_PALETTE.subList(1, DanmuConfig.DANMU_PALETTE.size)
        for (j in colored.indices) {
            val r = 0.80f + (j + 0.5f) / colored.size * 0.20f
            val got = DanmuConfig.pickColor(DanmuColorMode.MOSTLY_WHITE, white) { r }
            assertNotEquals("彩色分支出现白色 → 实际白占比会偏高", white, got)
        }
    }

    @Test
    fun `八成白模式实测分布接近八成`() {
        // 用固定种子的伪随机跑 10000 次，统计白占比（容差 ±3 个百分点）
        val rnd = java.util.Random(20261006L)
        var whiteCount = 0
        val total = 10_000
        repeat(total) {
            val c = DanmuConfig.pickColor(DanmuColorMode.MOSTLY_WHITE, white) { rnd.nextFloat() }
            if (c == white) whiteCount++
        }
        val pct = whiteCount * 100.0 / total
        assertTrue("白占比 $pct% 应接近 80%", pct in 77.0..83.0)
    }

    // ---------------------------------------------------------------- 色板约束

    @Test
    fun `色板不含黑色`() {
        assertFalse(
            "弹幕无底框 + 黑描边，黑字在暗场景不可读 → 色板必须排除黑色",
            DanmuConfig.DANMU_PALETTE.contains(Color.Black)
        )
    }

    @Test
    fun `色板首色为白`() {
        // MOSTLY_WHITE 依赖「首位是白」这一约定（PALETTE_WHITE_INDEX = 0）
        assertEquals(Color.White, DanmuConfig.DANMU_PALETTE[DanmuConfig.PALETTE_WHITE_INDEX])
    }

    @Test
    fun `色板规模在 5 到 6 色之间`() {
        // 用户要求「用更适合弹幕的 5-6 色」
        assertTrue(
            "色板应为 5~6 色，实际 ${DanmuConfig.DANMU_PALETTE.size}",
            DanmuConfig.DANMU_PALETTE.size in 5..6
        )
    }

    @Test
    fun `色板无重复色`() {
        assertEquals(
            "色板存在重复色 → 等概率取色会偏袒该色",
            DanmuConfig.DANMU_PALETTE.size,
            DanmuConfig.DANMU_PALETTE.toSet().size
        )
    }

    // ---------------------------------------------------------------- 枚举契约

    @Test
    fun `颜色模式三档 id 稳定且有序`() {
        assertEquals(0, DanmuColorMode.SINGLE.id)
        assertEquals(1, DanmuColorMode.RANDOM.id)
        assertEquals(2, DanmuColorMode.MOSTLY_WHITE.id)
    }

    @Test
    fun `未知 id 回落到单一颜色`() {
        // 旧 prefs 脏 id 不能让 App 崩，也不能静默变成随机
        assertEquals(DanmuColorMode.SINGLE, DanmuColorMode.fromId(999))
        assertEquals(DanmuColorMode.SINGLE, DanmuColorMode.fromId(-1))
    }

    @Test
    fun `已知 id 正确解析`() {
        DanmuColorMode.values().forEach { m ->
            assertEquals(m, DanmuColorMode.fromId(m.id))
        }
    }

    @Test
    fun `默认颜色模式为单一颜色`() {
        // 与 v2.3.0 行为一致：升级后不会突然满屏彩色
        assertEquals(DanmuColorMode.SINGLE.id, DanmuConfig.DEFAULT_COLOR_MODE_ID)
        assertEquals(DanmuColorMode.SINGLE, DanmuConfig().colorMode)
    }

    @Test
    fun `八成白的阈值常量为 80`() {
        assertEquals(80, DanmuConfig.MOSTLY_WHITE_PERCENT)
    }
}
