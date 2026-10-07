package com.example.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4.8：翻译缓存 key 归一化的单测。
 *
 * 归一化是**缓存命中率的总入口**，一旦行为错了会有两种后果：
 * 1. **归一化不足** → 该合并的没合并，命中率上不去（收益打折，不致命）；
 * 2. **归一化过度** → 语义不同的句子塌缩到同一 key，**返回错误译文**（致命，静默发生）。
 *
 * 因此本测试的重点不是「有没有合并」，而是 **② 过度合并的红线不能被越过**。
 *
 * ⚠️ 被测函数全部在 `SubtitleTranslator.Companion` 里（纯函数，无 Context 依赖）
 *    —— 这正是把实现从 private 实例方法搬过来的原因：可在纯 JVM 单测中直接调用。
 */
class SubtitleCacheKeyTest {

    private fun norm(text: String) = SubtitleTranslator.normalizeCacheText(text)
    private fun key(lang: String, text: String) = SubtitleTranslator.normalizeCacheKey("$lang:$text")

    // ===================================================================
    // 一、① 空白折叠（v2.0.151 既有行为，回归保护）
    // ===================================================================

    @Test
    fun `连续半角空格折叠为单个`() {
        assertEquals(norm("Hello world"), norm("Hello   world"))
        assertEquals("Hello world", norm("Hello   world"))
    }

    @Test
    fun `首尾空白被去除`() {
        assertEquals("Hello", norm("   Hello   "))
        assertEquals("Hello", norm("\tHello\n"))
    }

    @Test
    fun `全角空格视同空白并被折叠`() {
        assertEquals("你好 世界", norm("你好\u3000世界"))
        assertEquals(norm("你好 世界"), norm("你好\u3000世界"))
    }

    @Test
    fun `换行与制表符都折叠为单个空格`() {
        assertEquals("a b c", norm("a\nb\tc"))
        assertEquals("a b", norm("a \n\t b"))
    }

    // ===================================================================
    // 二、② 全角/半角统一（v2.4.8 新增）
    // ===================================================================

    @Test
    fun `全角字母数字统一为半角`() {
        assertEquals("ABC123", norm("ＡＢＣ１２３"))
        assertEquals("abcXYZ789", norm("ａｂｃＸＹＺ７８９"))
    }

    @Test
    fun `全角英文字母与半角等价`() {
        assertEquals(norm("Hello"), norm("Ｈｅｌｌｏ"))
    }

    @Test
    fun `全角标点统一为半角`() {
        assertEquals("你好!", norm("你好！"))
        assertEquals("a,b", norm("a，b"))
        assertEquals("a:b", norm("a：b"))
        assertEquals("a;b", norm("a；b"))
        assertEquals("(笑)", norm("（笑）"))
        assertEquals("a?b", norm("a？b"))
    }

    @Test
    fun `全角冒号时分与半角时分等价`() {
        assertEquals(norm("12:30"), norm("12：30"))
    }

    @Test
    fun `全角逗号千分位与半角等价`() {
        assertEquals(norm("1,000"), norm("1，000"))
    }

    @Test
    fun `全角波浪号与半角等价`() {
        assertEquals(norm("hi~"), norm("hi～"))
    }

    @Test
    fun `下划线与竖线等符号全半角等价`() {
        assertEquals(norm("a_b"), norm("a＿b"))
        assertEquals(norm("a|b"), norm("a｜b"))
        assertEquals(norm("a{b}"), norm("a｛b｝"))
    }

    // ===================================================================
    // 三、③ 标点形式统一（v2.4.8 新增）
    // ===================================================================

    @Test
    fun `英文弯引号统一为直引号`() {
        assertEquals(norm("it's"), norm("it\u2019s"))   // it's / it’s
        assertEquals(norm("'quoted'"), norm("\u2018quoted\u2019"))
    }

    @Test
    fun `英文弯双引号统一为直双引号`() {
        assertEquals(norm("\"hi\""), norm("\u201Chi\u201D"))
    }

    @Test
    fun `各种破折号统一为半角连字符`() {
        val variants = listOf(
            "5-3",          // 半角连字符
            "5\u20103",     // ‐ HYPHEN
            "5\u20113",     // ‑ NON-BREAKING HYPHEN
            "5\u20123",     // ‒ FIGURE DASH
            "5\u20133",     // – EN DASH
            "5\u20143",     // — EM DASH
            "5\u20153",     // ― HORIZONTAL BAR
            "5\u22123"      // − MINUS SIGN
        )
        val canonical = norm("5-3")
        variants.forEach { v ->
            assertEquals("破折号变体应统一：$v", canonical, norm(v))
        }
    }

    @Test
    fun `省略号展开为三点并与半角三点等价`() {
        // 这是最容易做错的一条：若把 … 只映射成单个 '.'，则 "……" → ".." 而 "..." → "..."
        assertEquals("...", norm("\u2026"))
        assertEquals(norm("..."), norm("\u2026"))
        assertEquals(norm("......"), norm("\u2026\u2026"))
    }

    @Test
    fun `角分角秒符号并入引号`() {
        assertEquals(norm("5'"), norm("5\u2032"))
        assertEquals(norm("5\""), norm("5\u2033"))
    }

    // ===================================================================
    // 四、🔴 红线：绝不做的过度合并（最重要的一组）
    // ===================================================================

    @Test
    fun `标点不被去除_时分与纯数字必须区分`() {
        assertNotEquals("12:30 与 1230 语义不同，不能合并", norm("12:30"), norm("1230"))
    }

    @Test
    fun `标点不被去除_小数与整数必须区分`() {
        assertNotEquals("3.14 与 314 语义不同，不能合并", norm("3.14"), norm("314"))
    }

    @Test
    fun `标点不被去除_点号分隔的缩写必须区分`() {
        assertNotEquals("A.B 与 AB 语义不同，不能合并", norm("A.B"), norm("AB"))
    }

    @Test
    fun `问号不被去除_疑句与陈述必须区分`() {
        assertNotEquals("真的？与真的 语义不同", norm("真的？"), norm("真的"))
    }

    @Test
    fun `感叹号与问号不能互相合并`() {
        assertNotEquals(norm("真的！"), norm("真的？"))
    }

    @Test
    fun `大小写必须保留`() {
        assertNotEquals("US 与 us 语义不同", norm("US"), norm("us"))
        assertNotEquals(norm("Hello"), norm("hello"))
    }

    @Test
    fun `中文顿号与半角逗号不合并`() {
        // 顿号在中文里是独立语义标点，不是半角逗号的"等价写法"
        assertNotEquals(norm("a、b"), norm("a,b"))
    }

    @Test
    fun `中文句号与半角句点不合并`() {
        assertNotEquals(norm("结束。"), norm("结束."))
    }

    @Test
    fun `中文书名号与尖括号不合并`() {
        assertNotEquals(norm("《书》"), norm("<书>"))
    }

    @Test
    fun `连字符与空格不合并`() {
        assertNotEquals(norm("a-b"), norm("a b"))
        assertNotEquals(norm("a-b"), norm("ab"))
    }

    // ===================================================================
    // 五、幂等性（关键：非幂等会导致每次加载都产出新 key，缓存无限膨胀）
    // ===================================================================

    @Test
    fun `归一化是幂等的`() {
        val samples = listOf(
            "你好！", "ＡＢＣ１２３", "“引号”", "it’s", "5—3=2",
            "\u2026", "……", "1，000", "Ａ Ｂ", "（笑）",
            "...", "…...", "⋯", "Hello  world", "你好\u3000世界",
            "5\u22123", "a＿b|c", " 混合　全角 and 半角！ ",
            "12:30", "3.14", "A.B", "真的？", "US", "《书》"
        )
        samples.forEach { s ->
            val once = norm(s)
            val twice = norm(once)
            assertEquals("幂等性失败：$s → $once → $twice", once, twice)
        }
    }

    // ===================================================================
    // 六、key 层面的行为（含语言前缀的隔离性）
    // ===================================================================

    @Test
    fun `normalizeCacheKey 保留语言前缀`() {
        val k = key("zh", "Hello！")
        assertTrue("应保留 zh: 前缀", k.startsWith("zh:"))
        assertEquals("zh:Hello!", k)
    }

    @Test
    fun `不同目标语言的同一原文不合并`() {
        assertNotEquals(key("zh", "Hello"), key("ja", "Hello"))
    }

    @Test
    fun `key 无冒号前缀时原样返回`() {
        // 防御性：格式异常的 key 不应被破坏
        assertEquals("noColonHere", SubtitleTranslator.normalizeCacheKey("noColonHere"))
        assertEquals("", SubtitleTranslator.normalizeCacheKey(""))
    }

    @Test
    fun `冒号在文首时不解析为语言前缀`() {
        // sep <= 0 → 直接返回原串，避免把 ":abc" 误切成 lang="" + text="abc"
        assertEquals(":abc", SubtitleTranslator.normalizeCacheKey(":abc"))
    }

    @Test
    fun `归一化后应命中的真实场景组合`() {
        // 模拟字幕里常见的"同一句话、排版不同"——这些必须命中同一条缓存。
        // ⚠️ 注意每组内**标点必须一致**：带感叹号与不带感叹号是语义不同的句子，
        //    「你好」与「你好！」本来就不该合并（见第四组的红线测试）。
        val groups = listOf(
            // 全角/半角感叹号 + 首尾空白差异
            listOf("你好！", "你好!", " 你好!  ", "你好！ "),
            // 半角三点 / 单码位省略号 / 制表符
            listOf("Wait...", "Wait…", "Wait\u2026", "Wait...  "),
            // 全角数字 vs 半角数字
            listOf("１２３", "123", " 123 "),
            // 弯引号 + 连续空格
            listOf("it’s ok", "it's ok", "it's  ok")
        )
        groups.forEach { g ->
            val canonical = norm(g.first())
            g.forEach { variant ->
                assertEquals("应命中同一 key：$variant", canonical, norm(variant))
            }
        }
    }

    @Test
    fun `无标点与带标点不应合入同一组`() {
        // 与上一条测试配对，明确划出边界：这些**不该**命中同一缓存
        assertNotEquals(norm("你好"), norm("你好!"))
        assertNotEquals(norm("Wait"), norm("Wait..."))
        assertNotEquals(norm("123"), norm("123,"))
    }
}
