package com.example.vr

import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color

data class UiThemePalette(
    val id: Int,
    val name: String,
    val bg: Color,
    val panelBg: Color,
    val accent: Color,
    val accentOn: Color,
    val translucentWhite10: Color,
    val translucentWhite20: Color,
    val textLight: Color,
    val textSoft: Color
)

object UiThemes {
    val list = listOf(
        UiThemePalette(
            id = 0,
            name = "紫罗兰",
            bg = Color(0xFF1C1B1F),
            panelBg = Color(0xF22B2930),
            accent = Color(0xFFD0BCFF),
            accentOn = Color(0xFF381E72),
            translucentWhite10 = Color(0x1BFFFFFF),
            translucentWhite20 = Color(0x33FFFFFF),
            textLight = Color(0xFFE6E1E5),
            textSoft = Color(0xFF9095A6)
        ),
        UiThemePalette(
            id = 1,
            name = "靛蓝",
            bg = Color(0xFF1B1D2B),
            panelBg = Color(0xF22B2C42),
            accent = Color(0xFF9FA8FF),
            accentOn = Color(0xFF1A237E),
            translucentWhite10 = Color(0x1BFFFFFF),
            translucentWhite20 = Color(0x33FFFFFF),
            textLight = Color(0xFFE8E9F2),
            textSoft = Color(0xFF9296AD)
        ),
        UiThemePalette(
            id = 2,
            name = "湖青",
            bg = Color(0xFF17242B),
            panelBg = Color(0xF225323B),
            accent = Color(0xFF80DEEA),
            accentOn = Color(0xFF00363F),
            translucentWhite10 = Color(0x1BFFFFFF),
            translucentWhite20 = Color(0x33FFFFFF),
            textLight = Color(0xFFE4ECEF),
            textSoft = Color(0xFF8FA0A8)
        ),
        UiThemePalette(
            id = 3,
            name = "玫瑰",
            bg = Color(0xFF281C24),
            panelBg = Color(0xF2362632),
            accent = Color(0xFFF48FB1),
            accentOn = Color(0xFF4A1026),
            translucentWhite10 = Color(0x1BFFFFFF),
            translucentWhite20 = Color(0x33FFFFFF),
            textLight = Color(0xFFF0E4E9),
            textSoft = Color(0xFFA6909B)
        ),
        UiThemePalette(
            id = 4,
            name = "琥珀",
            bg = Color(0xFF272019),
            panelBg = Color(0xF2362B23),
            accent = Color(0xFFFFD54F),
            accentOn = Color(0xFF3E2C00),
            translucentWhite10 = Color(0x1BFFFFFF),
            translucentWhite20 = Color(0x33FFFFFF),
            textLight = Color(0xFFF0E9DE),
            textSoft = Color(0xFFA69A88)
        ),
        UiThemePalette(
            id = 5,
            name = "薄荷",
            bg = Color(0xFF18241E),
            panelBg = Color(0xF224342A),
            accent = Color(0xFFA5D6A7),
            accentOn = Color(0xFF0E3311),
            translucentWhite10 = Color(0x1BFFFFFF),
            translucentWhite20 = Color(0x33FFFFFF),
            textLight = Color(0xFFE4EEE6),
            textSoft = Color(0xFF8FA699)
        )
    )

    fun byId(id: Int): UiThemePalette = list.getOrElse(id) { list[0] }

    fun loadThemeId(prefs: SharedPreferences): Int = prefs.getInt("ui_theme_id", 0)

    fun loadGlassMode(prefs: SharedPreferences): Int {
        // Glass modes: 0 = solid, 1 = liquid glass. Legacy value 2 (transparent)
        // is no longer offered; fall back to solid.
        val mode = prefs.getInt("ui_glass_mode", 0)
        return if (mode == 2) 0 else mode
    }
}
