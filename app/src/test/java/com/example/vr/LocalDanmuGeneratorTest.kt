package com.example.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4.12：本地弹幕**输出解析**的单测。
 *
 * 为什么值得单独测：小模型（0.8B）的输出格式**不稳定** ——
 * 会加编号、加引号、加开场白、把多条挤在一行。解析层是这类功能最容易出问题的地方，
 * 而且出错时**不会崩**，只会"弹幕看起来怪怪的"，很难在真机上定位。
 */
class LocalDanmuGeneratorTest {

    @Test
    fun `基本多行解析`() {
        val out = LocalDanmuGenerator.parseLines("这条好笑\n太真实了\n哈哈哈哈哈")
        assertEquals(3, out.size)
        assertEquals("这条好笑", out[0])
        assertEquals("哈哈哈哈哈", out[2])
    }

    @Test
    fun `去掉数字编号前缀`() {
        val out = LocalDanmuGenerator.parseLines("1. 第一条\n2、第二条\n3) 第三条")
        assertEquals(listOf("第一条", "第二条", "第三条"), out)
    }

    @Test
    fun `去掉列表符号`() {
        val out = LocalDanmuGenerator.parseLines("- 甲\n• 乙\n* 丙")
        assertEquals(listOf("甲", "乙", "丙"), out)
    }

    @Test
    fun `去掉包裹引号`() {
        val out = LocalDanmuGenerator.parseLines("\"带引号\"\n\u201C弯引号\u201D\n「方括号」")
        assertEquals(listOf("带引号", "弯引号", "方括号"), out)
    }

    @Test
    fun `丢弃元话语行`() {
        // ⚠️ 这两句都 > 12 字，才会被判为「自言自语」
        val out = LocalDanmuGenerator.parseLines(
            "好的，以下是生成的弹幕内容：\n这条是真弹幕\n根据台词为你生成如下弹幕："
        )
        assertEquals("应只剩真正的弹幕", 1, out.size)
        assertEquals("这条是真弹幕", out[0])
    }

    @Test
    fun `短的元话语词不被误杀`() {
        // "好的" 本身就是一条完全合理的弹幕 → 长度阈值必须保护它
        assertEquals(listOf("好的"), LocalDanmuGenerator.parseLines("好的"))
    }

    @Test
    fun `空行与纯空白行被跳过`() {
        val out = LocalDanmuGenerator.parseLines("甲\n\n\n乙\n   \n丙")
        assertEquals(3, out.size)
    }

    @Test
    fun `空输入返回空列表`() {
        assertTrue(LocalDanmuGenerator.parseLines("").isEmpty())
        assertTrue(LocalDanmuGenerator.parseLines("   \n  \n").isEmpty())
    }

    @Test
    fun `limit 生效`() {
        val raw = (1..50).joinToString("\n") { "弹幕$it" }
        assertEquals(5, LocalDanmuGenerator.parseLines(raw, limit = 5).size)
    }

    @Test
    fun `超长行被截断到上限`() {
        val out = LocalDanmuGenerator.parseLines("啊".repeat(100))
        assertEquals(LocalDanmuGenerator.MAX_ITEM_CHARS, out[0].length)
    }

    @Test
    fun `行内空格不会被拆分`() {
        assertEquals(listOf("这条 有 空格"), LocalDanmuGenerator.parseLines("这条 有 空格"))
    }

    @Test
    fun `只加编号不加内容的行被丢弃`() {
        // 编号分隔符集合是 . 、 ) ）（不含半角逗号 —— 那更可能是正文里的标点）
        assertTrue(LocalDanmuGenerator.parseLines("1.\n2、\n3)").isEmpty())
    }
}
