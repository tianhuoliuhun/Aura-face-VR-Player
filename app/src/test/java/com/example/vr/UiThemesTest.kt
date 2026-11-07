package com.example.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * v2.9.2：配色预设的纯 JVM 回归。
 *
 * `byId` 是**按下标**取主题的，一旦有人插队、跳号或把 Monet 的伪 ID 写进 list，
 * 用户已保存的 `ui_theme_id` 就会静默指向另一套颜色（项目里踩过同类 legacy 值复用坑）。
 * 这几条断言把「id == 下标、连续、唯一」钉死。
 */
class UiThemesTest {

    @Test
    fun presets_areContiguousAndUnique() {
        val list = UiThemes.list
        assertEquals("v2.9.2 应有 16 套静态 MD 配色", 16, list.size)
        assertEquals(list.indices.toList(), list.map { it.id })
        assertEquals(16, list.map { it.id }.toSet().size)
        assertEquals(16, list.map { it.nameRes }.toSet().size)
    }

    @Test
    fun byId_returnsMatchingPreset() {
        UiThemes.list.forEach { preset ->
            assertSame(preset, UiThemes.byId(preset.id))
        }
    }

    @Test
    fun byId_outOfRangeFallsBackToFirst() {
        assertSame(UiThemes.list[0], UiThemes.byId(UiThemes.MONET_THEME_ID))
        assertSame(UiThemes.list[0], UiThemes.byId(Int.MIN_VALUE))
        assertSame(UiThemes.list[0], UiThemes.byId(UiThemes.list.size))
        assertSame(UiThemes.list[0], UiThemes.byId(999))
    }

    @Test
    fun monetId_isNotAStaticPreset() {
        assertFalse(
            "Monet 是伪 ID，绝不能撞上任何静态预设下标",
            UiThemes.list.any { it.id == UiThemes.MONET_THEME_ID }
        )
        assertEquals(0, UiThemes.DEFAULT_THEME_ID)
        assertSame(UiThemes.list[0], UiThemes.byId(UiThemes.DEFAULT_THEME_ID))
    }
}
