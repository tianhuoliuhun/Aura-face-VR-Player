package com.example.vr

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.ui.graphics.Color
import com.example.R

data class UiThemePalette(
    val id: Int,
    // v2.0.137：主题颜色名改走字符串资源（原硬编码中文，其他语言下不翻译）
    @StringRes val nameRes: Int,
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
            nameRes = R.string.theme_name_violet,
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
            nameRes = R.string.theme_name_indigo,
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
            nameRes = R.string.theme_name_lake_cyan,
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
            nameRes = R.string.theme_name_rose,
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
            nameRes = R.string.theme_name_amber,
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
            nameRes = R.string.theme_name_mint,
            bg = Color(0xFF18241E),
            panelBg = Color(0xF224342A),
            accent = Color(0xFFA5D6A7),
            accentOn = Color(0xFF0E3311),
            translucentWhite10 = Color(0x1BFFFFFF),
            translucentWhite20 = Color(0x33FFFFFF),
            textLight = Color(0xFFE4EEE6),
            textSoft = Color(0xFF8FA699)
        ),
        // v2.9.2：补齐 Material Design 主色（原 6 套 → 16 套）。色值取 MD 色板，
        // 每套仍按 bg / panelBg / accent / accentOn / textLight / textSoft 六色成套派生。
        UiThemePalette(
            id = 6,
            nameRes = R.string.theme_name_blue,
            bg = Color(0xFF161C29),
            panelBg = Color(0xF2232A3D),
            accent = Color(0xFF82B1FF),
            accentOn = Color(0xFF0D2F6B),
            translucentWhite10 = Color(0x1BFFFFFF),
            translucentWhite20 = Color(0x33FFFFFF),
            textLight = Color(0xFFE6EBF5),
            textSoft = Color(0xFF909BB0)
        ),
        UiThemePalette(
            id = 7,
            nameRes = R.string.theme_name_light_blue,
            bg = Color(0xFF141F27),
            panelBg = Color(0xF220303B),
            accent = Color(0xFF81D4FA),
            accentOn = Color(0xFF00344A),
            translucentWhite10 = Color(0x1BFFFFFF),
            translucentWhite20 = Color(0x33FFFFFF),
            textLight = Color(0xFFE3EFF5),
            textSoft = Color(0xFF8CA3AF)
        ),
        UiThemePalette(
            id = 8,
            nameRes = R.string.theme_name_teal,
            bg = Color(0xFF15241F),
            panelBg = Color(0xF222322E),
            accent = Color(0xFF80CBC4),
            accentOn = Color(0xFF003730),
            translucentWhite10 = Color(0x1BFFFFFF),
            translucentWhite20 = Color(0x33FFFFFF),
            textLight = Color(0xFFE2EEEB),
            textSoft = Color(0xFF8DA7A1)
        ),
        UiThemePalette(
            id = 9,
            nameRes = R.string.theme_name_lime,
            bg = Color(0xFF222410),
            panelBg = Color(0xF2313419),
            accent = Color(0xFFE6EE9C),
            accentOn = Color(0xFF334000),
            translucentWhite10 = Color(0x1BFFFFFF),
            translucentWhite20 = Color(0x33FFFFFF),
            textLight = Color(0xFFEFF0DF),
            textSoft = Color(0xFFA5A887)
        ),
        UiThemePalette(
            id = 10,
            nameRes = R.string.theme_name_yellow,
            bg = Color(0xFF262210),
            panelBg = Color(0xF2353019),
            accent = Color(0xFFFFF59D),
            accentOn = Color(0xFF3B3000),
            translucentWhite10 = Color(0x1BFFFFFF),
            translucentWhite20 = Color(0x33FFFFFF),
            textLight = Color(0xFFF3EFDD),
            textSoft = Color(0xFFAAA480)
        ),
        UiThemePalette(
            id = 11,
            nameRes = R.string.theme_name_orange,
            bg = Color(0xFF2A2016),
            panelBg = Color(0xF23B2C1D),
            accent = Color(0xFFFFCC80),
            accentOn = Color(0xFF4A2600),
            translucentWhite10 = Color(0x1BFFFFFF),
            translucentWhite20 = Color(0x33FFFFFF),
            textLight = Color(0xFFF5EADD),
            textSoft = Color(0xFFB09C86)
        ),
        UiThemePalette(
            id = 12,
            nameRes = R.string.theme_name_deep_orange,
            bg = Color(0xFF2A1B17),
            panelBg = Color(0xF23B2621),
            accent = Color(0xFFFFAB91),
            accentOn = Color(0xFF511500),
            translucentWhite10 = Color(0x1BFFFFFF),
            translucentWhite20 = Color(0x33FFFFFF),
            textLight = Color(0xFFF5E6E2),
            textSoft = Color(0xFFB29A94)
        ),
        UiThemePalette(
            id = 13,
            nameRes = R.string.theme_name_red,
            bg = Color(0xFF2A1A1A),
            panelBg = Color(0xF23B2626),
            accent = Color(0xFFEF9A9A),
            accentOn = Color(0xFF5C0E0E),
            translucentWhite10 = Color(0x1BFFFFFF),
            translucentWhite20 = Color(0x33FFFFFF),
            textLight = Color(0xFFF5E4E4),
            textSoft = Color(0xFFB39B9B)
        ),
        UiThemePalette(
            id = 14,
            nameRes = R.string.theme_name_blue_grey,
            bg = Color(0xFF1A2024),
            panelBg = Color(0xF2273036),
            accent = Color(0xFFB0BEC5),
            accentOn = Color(0xFF24343C),
            translucentWhite10 = Color(0x1BFFFFFF),
            translucentWhite20 = Color(0x33FFFFFF),
            textLight = Color(0xFFE7ECEF),
            textSoft = Color(0xFF94A1A8)
        ),
        UiThemePalette(
            id = 15,
            nameRes = R.string.theme_name_brown,
            bg = Color(0xFF241D1A),
            panelBg = Color(0xF2342A25),
            accent = Color(0xFFBCAAA4),
            accentOn = Color(0xFF3E241C),
            translucentWhite10 = Color(0x1BFFFFFF),
            translucentWhite20 = Color(0x33FFFFFF),
            textLight = Color(0xFFF0E9E6),
            textSoft = Color(0xFFAC9C96)
        )
    )

    /** id 即 [list] 下标；越界（含 [MONET_THEME_ID] = -1）一律回落首套静态主题 */
    fun byId(id: Int): UiThemePalette = list.getOrElse(id) { list[0] }

    // ===== v2.9.2：MD 莫奈取色（Android 12+ Material You / Monet 动态配色）=====

    /**
     * 「跟随系统壁纸」的伪主题 ID。它**不在 [list] 里**，所以静态色点网格不会多画一个点，
     * 由设置面板里独立的 chip 控制。用 -1 而不是 `list.size`：以后往 list 加主题时，
     * 已保存的 -1 不会被撞成某一套静态配色。
     */
    const val MONET_THEME_ID = -1

    /** 关闭 Monet 时回落的静态主题（紫罗兰，也是全新安装的默认主题） */
    const val DEFAULT_THEME_ID = 0

    fun isMonetAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * **统一解析入口**：主界面与 LicensesScreen 都走这里，避免动态色出现两份实现。
     * 低版本、系统色板读取失败时回落静态主题，绝不让配色为空。
     * 每次重组都会调用，配合下面 [monetPalette] 的值缓存，壁纸变化会自动跟随。
     */
    fun resolve(context: Context, themeId: Int): UiThemePalette =
        if (themeId == MONET_THEME_ID && isMonetAvailable()) {
            runCatching { monetPalette(context) }.getOrDefault(byId(DEFAULT_THEME_ID))
        } else {
            byId(themeId)
        }

    /**
     * 从系统 Monet 色板派生一套 [UiThemePalette]。
     *
     * ⚠️ **刻意不动 `Theme.kt` 的 `dynamicColor`**：v2.4.37 审查项 #1 把它从 true 改成
     * false，原因是一旦让 `MaterialTheme.colorScheme` 跟随壁纸，那些**未显式设色**的组件
     * （裸 Switch 等）就会跟着变色，设置面板配色失控。这里只把动态色映射到本项目自有的
     * 六个显式色值，所有组件仍走 `AccentColor` / `ThemeBgColor`，既实现「配色跟随壁纸」，
     * 又不会波及 MaterialTheme 层。
     *
     * 固定取 **dark** scheme：本项目 UI 恒为暗色（背景 `#18171C`），若按系统亮/暗切
     * light scheme，`onSurface` 会变深色、在暗底上直接看不清。
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun monetPalette(context: Context): UiThemePalette {
        val cs = dynamicDarkColorScheme(context)
        val built = UiThemePalette(
            id = MONET_THEME_ID,
            nameRes = R.string.theme_name_monet,
            bg = cs.background,
            panelBg = cs.surface.copy(alpha = 0.95f),
            accent = cs.primary,
            accentOn = cs.onPrimary,
            translucentWhite10 = Color(0x1BFFFFFF),
            translucentWhite20 = Color(0x33FFFFFF),
            textLight = cs.onSurface,
            textSoft = cs.onSurfaceVariant
        )
        // 值未变时复用旧对象，引用稳定 → Compose 不会因「每帧 new 一个对象」误触发重组；
        // 壁纸一变，值真的不同才换对象，配色随即跟随。
        val prev = cachedMonet
        if (prev != null && prev == built) return prev
        cachedMonet = built
        return built
    }

    @Volatile
    private var cachedMonet: UiThemePalette? = null

    fun loadThemeId(prefs: SharedPreferences): Int = prefs.getInt("ui_theme_id", 0)

    /**
     * 玻璃 / 模糊档位：0 = 纯色，1 = 液态玻璃（iOS 26 风），
     * 2 = 磨砂玻璃（系统模糊 + 噪点颗粒）。
     *
     * v2.4.68：**移除 3 = 高斯模糊**（用户要求）。它和磨砂档的差别只在
     * 「无颗粒 + 更白」，实际观感分不出来，反而让设置面板多一个让人纠结的选项。
     */
    fun loadGlassMode(prefs: SharedPreferences): Int {
        // ⚠️ v2.1.231 修正：这里原本写着「legacy value 2（旧的透明档）已不再提供，回落为纯色」。
        // 但 v2.1.218 之后 **2 已经被重新启用为「磨砂玻璃」**，于是出现：
        //   用户在设置里点「磨砂」→ putInt("ui_glass_mode", 2) → 重启后这里把它读成 0
        //   → 界面变回纯色。表现就是用户反馈的「选择的主题样式没有被记忆」。
        // ⚠️ 教训：**枚举/档位复用旧值时，一定要回头检查所有读取端的 legacy fallback**。
        //
        // v2.4.68：删掉高斯档后，老用户 prefs 里可能仍存着 3 —— 越界即回落 0（纯色），
        // 靠下面的 `in 0..MAX_GLASS_MODE` 兜住，不需要额外迁移代码。
        val mode = prefs.getInt("ui_glass_mode", 0)
        return if (mode in 0..MAX_GLASS_MODE) mode else 0
    }

    /** 当前支持的最大玻璃档位（新增/删除档位时记得同步，避免在这里又写死一个数字） */
    const val MAX_GLASS_MODE = 2
}
