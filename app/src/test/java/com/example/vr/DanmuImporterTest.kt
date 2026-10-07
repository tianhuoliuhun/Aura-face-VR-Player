package com.example.vr

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4.7：外部弹幕导入解析（[DanmuImporter]）单测。
 *
 * 重点覆盖三类「真实文件里一定会出现」的情况：
 * 1. **B 站 XML** 的字段位序（尤其「颜色是十进制 RGB888，不是 ARGB」这个致命坑）；
 * 2. **容错**：坏数据只跳过它自己，绝不能整体失败；
 * 3. **JSON** 的字段别名与颜色写法。
 */
class DanmuImporterTest {

    // ===============================================================
    // 一、B 站 XML
    // ===============================================================

    @Test
    fun `解析标准 B 站 XML 取到时间与文本`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <i>
              <chatserver>chat.bilibili.com</chatserver>
              <d p="12.34500,1,25,16777215,1535026933,0,abc123,1001">第一条弹幕</d>
              <d p="30.00000,1,25,16711680,1535026934,0,abc124,1002">第二条弹幕</d>
            </i>
        """.trimIndent()
        val r = DanmuImporter.parse(xml)
        assertEquals(DanmuImporter.Format.BILI_XML, r.format)
        assertEquals(2, r.size)
        assertEquals(12_345L, r.items[0].timeMs)
        assertEquals("第一条弹幕", r.items[0].text)
        assertEquals(30_000L, r.items[1].timeMs)
    }

    @Test
    fun `结果是按时间升序的（即使文件里乱序）`() {
        val xml = """
            <i>
              <d p="50.0,1,25,16777215,1,0,h,1">后面</d>
              <d p="10.0,1,25,16777215,1,0,h,2">前面</d>
              <d p="30.0,1,25,16777215,1,0,h,3">中间</d>
            </i>
        """.trimIndent()
        val r = DanmuImporter.parse(xml)
        assertEquals(listOf(10_000L, 30_000L, 50_000L), r.items.map { it.timeMs })
        assertEquals("前面", r.items[0].text)
    }

    @Test
    fun `颜色按十进制 RGB888 解析（白 = 16777215 必须不透明）`() {
        // ⚠️ 这是最容易踩的坑：16777215 = 0x00FFFFFF，若直接当 ARGB → alpha=0（全透明，看不见）
        val color = DanmuImporter.rgbIntToColor(16777215)
        assertEquals(Color.White, color)
        // alpha 必须为 1（不透明）
        assertEquals(1f, color.alpha, 0.001f)

        // 红 FE0302 = 16646914
        val red = DanmuImporter.rgbIntToColor(16646914)
        assertEquals(1f, red.alpha, 0.001f)
        assertEquals(0xFE / 255f, red.red, 0.01f)
        assertEquals(0x03 / 255f, red.green, 0.01f)
        assertEquals(0x02 / 255f, red.blue, 0.01f)
    }

    @Test
    fun `黑色弹幕的颜色也是不透明的（RGB 全 0 不能被当成透明）`() {
        val c = DanmuImporter.rgbIntToColor(0x000000)
        assertEquals(1f, c.alpha, 0.001f)
        assertEquals(Color.Black, c)
    }

    @Test
    fun `只接受滚动类弹幕（模式 1 2 3），顶部底部逆向被跳过`() {
        val xml = """
            <i>
              <d p="1.0,1,25,16777215,1,0,h,1">滚动1</d>
              <d p="2.0,2,25,16777215,1,0,h,2">滚动2</d>
              <d p="3.0,3,25,16777215,1,0,h,3">滚动3</d>
              <d p="4.0,4,25,16777215,1,0,h,4">底部</d>
              <d p="5.0,5,25,16777215,1,0,h,5">顶部</d>
              <d p="6.0,6,25,16777215,1,0,h,6">逆向</d>
              <d p="7.0,7,25,16777215,1,0,h,7">高级</d>
            </i>
        """.trimIndent()
        val r = DanmuImporter.parse(xml)
        assertEquals(3, r.size)
        assertEquals(listOf("滚动1", "滚动2", "滚动3"), r.items.map { it.text })
        assertEquals(4, r.skipped)
    }

    @Test
    fun `字段缺失的条目被跳过而不是整体失败`() {
        val xml = """
            <i>
              <d p="1.0,1,25,16777215,1,0,h,1">正常</d>
              <d p="坏数据">字段不足</d>
              <d p="abc,1,25,16777215,1,0,h,3">时间非法</d>
              <d p="2.0,1,25,16777215,1,0,h,4">也正常</d>
            </i>
        """.trimIndent()
        val r = DanmuImporter.parse(xml)
        assertEquals(2, r.size)
        assertEquals(2, r.skipped)
        assertEquals(listOf("正常", "也正常"), r.items.map { it.text })
    }

    @Test
    fun `空文本条目被跳过`() {
        val xml = """
            <i>
              <d p="1.0,1,25,16777215,1,0,h,1">   </d>
              <d p="2.0,1,25,16777215,1,0,h,2">有内容</d>
            </i>
        """.trimIndent()
        val r = DanmuImporter.parse(xml)
        assertEquals(1, r.size)
        assertEquals("有内容", r.items[0].text)
    }

    @Test
    fun `超长文本被跳过（脏数据防护）`() {
        val long = "啊".repeat(DanmuImporter.MAX_TEXT_CHARS + 1)
        val xml = """
            <i>
              <d p="1.0,1,25,16777215,1,0,h,1">$long</d>
              <d p="2.0,1,25,16777215,1,0,h,2">短的</d>
            </i>
        """.trimIndent()
        val r = DanmuImporter.parse(xml)
        assertEquals(1, r.size)
        assertEquals("短的", r.items[0].text)
    }

    @Test
    fun `buildFromP 直接可用（不必构造完整 XML）`() {
        val it = DanmuImporter.buildFromP("5.5,1,18,16711680,1,0,h,9", "直接构造")
        assertNotNull(it)
        assertEquals(5_500L, it!!.timeMs)
        assertEquals("直接构造", it.text)
        assertEquals(18, it.fontSizePx)
        assertNotNull(it.color)

        assertNull(DanmuImporter.buildFromP(null, "没字段"))
        assertNull(DanmuImporter.buildFromP("1,2,3", "字段不足"))
    }

    @Test
    fun `没有 p 属性的 d 标签被跳过`() {
        val xml = """
            <i>
              <d>无属性</d>
              <d p="1.0,1,25,16777215,1,0,h,1">有属性</d>
            </i>
        """.trimIndent()
        val r = DanmuImporter.parse(xml)
        assertEquals(1, r.size)
    }

    // ===============================================================
    // 二、JSON
    // ===============================================================

    @Test
    fun `解析 JSON 数组基本形态`() {
        val json = """
            [
              {"time": 1.5, "text": "第一条", "color": "#FF0000"},
              {"time": 3.0, "text": "第二条", "color": "#00FF00"}
            ]
        """.trimIndent()
        val r = DanmuImporter.parse(json)
        assertEquals(DanmuImporter.Format.JSON, r.format)
        assertEquals(2, r.size)
        assertEquals(1_500L, r.items[0].timeMs)
        assertEquals("第一条", r.items[0].text)
        assertNotNull(r.items[0].color)
    }

    @Test
    fun `JSON 字段别名都能识别`() {
        val json = """
            [
              {"t": 1.0, "content": "别名t+content"},
              {"start": 2.0, "body": "别名start+body"},
              {"time": 3.0, "msg": "别名time+msg"}
            ]
        """.trimIndent()
        val r = DanmuImporter.parse(json)
        assertEquals(3, r.size)
        assertEquals(listOf("别名t+content", "别名start+body", "别名time+msg"), r.items.map { it.text })
    }

    @Test
    fun `JSON 的 timeMs 字段按毫秒处理`() {
        val json = """[{"timeMs": 2500, "text": "毫秒"}]"""
        val r = DanmuImporter.parse(json)
        assertEquals(1, r.size)
        assertEquals(2_500L, r.items[0].timeMs)
    }

    @Test
    fun `JSON 里文本包含逗号与花括号不会破坏解析`() {
        // ⚠️ 这正是"不能简单按 } 切分"的原因
        val json = """
            [
              {"time": 1.0, "text": "有,逗号,还有}花括号{和]方括号"},
              {"time": 2.0, "text": "第二条"}
            ]
        """.trimIndent()
        val r = DanmuImporter.parse(json)
        assertEquals(2, r.size)
        assertEquals("有,逗号,还有}花括号{和]方括号", r.items[0].text)
        assertEquals("第二条", r.items[1].text)
    }

    @Test
    fun `JSON 里转义引号与换行被还原`() {
        val json = """[{"time":1.0,"text":"他说\"你好\"\n换行"}]"""
        val r = DanmuImporter.parse(json)
        assertEquals(1, r.size)
        assertEquals("他说\"你好\"\n换行", r.items[0].text)
    }

    @Test
    fun `JSON 缺 text 的条目被跳过`() {
        val json = """
            [
              {"time": 1.0},
              {"time": 2.0, "text": "有文本"}
            ]
        """.trimIndent()
        val r = DanmuImporter.parse(json)
        assertEquals(1, r.size)
        assertEquals(1, r.skipped)
    }

    @Test
    fun `JSON 缺 time 的条目被跳过`() {
        val json = """[{"text":"没时间"}]"""
        val r = DanmuImporter.parse(json)
        assertEquals(0, r.size)
        assertEquals(1, r.skipped)
    }

    // ===============================================================
    // 三、颜色解析
    // ===============================================================

    @Test
    fun `颜色支持 6 位 8 位 3 位十六进制与十进制`() {
        assertEquals(Color(0xFFFF0000), DanmuImporter.parseColorValue("#FF0000"))
        assertEquals(Color(0x80FF0000), DanmuImporter.parseColorValue("#80FF0000"))
        assertEquals(Color(0xFFFF0000), DanmuImporter.parseColorValue("#F00"))
        // 十进制 16711680 = 0xFF0000
        assertEquals(Color(0xFFFF0000), DanmuImporter.parseColorValue("16711680"))
        // 带引号的原始值（未去掉引号时）也要能处理
        assertEquals(Color(0xFFFF0000), DanmuImporter.parseColorValue("\"#FF0000\""))
    }

    @Test
    fun `无法解析的颜色返回 null 而不是白色`() {
        assertNull(DanmuImporter.parseColorValue(""))
        assertNull(DanmuImporter.parseColorValue("不是颜色"))
        assertNull(DanmuImporter.parseColorValue("#GGGGGG"))
        assertNull(DanmuImporter.parseColorValue("#12345"))  // 5 位，非法
    }

    @Test
    fun `颜色为 null 时条目仍被保留（由调用方回落配置色）`() {
        val json = """[{"time":1.0,"text":"无色","color":"乱写"}]"""
        val r = DanmuImporter.parse(json)
        assertEquals(1, r.size)
        assertNull("颜色应保持 null 而不是被填成白色", r.items[0].color)
    }

    // ===============================================================
    // 四、格式识别
    // ===============================================================

    @Test
    fun `空内容与无法识别的格式返回 UNKNOWN`() {
        assertEquals(DanmuImporter.Format.UNKNOWN, DanmuImporter.parse("").format)
        assertEquals(DanmuImporter.Format.UNKNOWN, DanmuImporter.parse("   \n  ").format)
        assertEquals(0, DanmuImporter.parse("").size)
        assertEquals(DanmuImporter.Format.UNKNOWN, DanmuImporter.parse("随便一段文本").format)
    }

    @Test
    fun `带 BOM 的 JSON 也能识别`() {
        val json = "\uFEFF[{\"time\":1.0,\"text\":\"带BOM\"}]"
        val r = DanmuImporter.parse(json)
        assertEquals(DanmuImporter.Format.JSON, r.format)
        assertEquals(1, r.size)
    }

    @Test
    fun `部分损坏的 XML 仍能保留已解析条目`() {
        // 结尾缺失（文件被截断）
        val xml = """
            <i>
              <d p="1.0,1,25,16777215,1,0,h,1">可解析</d>
              <d p="2.0,1,25,16777215,1,0,h,2">也应保留</d>
        """.trimIndent()
        val r = DanmuImporter.parse(xml)
        assertTrue("至少应保留已解析的条目，实际 ${r.size}", r.size >= 1)
    }

    @Test
    fun `lastTimeMs 反映覆盖时长`() {
        val json = """[{"time":1.0,"text":"a"},{"time":42.5,"text":"b"}]"""
        val r = DanmuImporter.parse(json)
        assertEquals(42_500L, r.lastTimeMs)
    }

    @Test
    fun `单条分析时间非法（负数）被跳过`() {
        val xml = """<i><d p="-5.0,1,25,16777215,1,0,h,1">负时间</d></i>"""
        val r = DanmuImporter.parse(xml)
        assertEquals(0, r.size)
        assertEquals(1, r.skipped)
    }
}
