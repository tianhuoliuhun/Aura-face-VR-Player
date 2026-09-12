package com.example.vr

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.example.R

/**
 * 字幕字体工具（v82：移除 Honor Sans，保留 OPPO/MiSans 可变字体）
 *
 * - OPPO Sans 4.0：可变字体（VF），通过 FontVariation.weight 动态渲染 100~900 字重
 * - MiSans VF：可变字体，同样支持动态字重
 */
object SubtitleFontHelper {

    /** 可变字体按字重生成（OPPO Sans VF / MiSans VF） */
    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    private fun variableFont(resId: Int, weight: Int): FontFamily = FontFamily(
        Font(
            resId,
            variationSettings = FontVariation.Settings(
                FontVariation.weight(weight.coerceIn(100, 900))
            )
        )
    )

    @Composable
    fun getFontFamily(fontOption: SubtitleFont, weight: Int, isItalic: Boolean): FontFamily {
        return remember(fontOption, weight, isItalic) {
            when (fontOption) {
                SubtitleFont.SYSTEM -> FontFamily.Default
                SubtitleFont.OPPO_SANS -> variableFont(R.font.oppo_sans_4_0, weight)
                SubtitleFont.MI_SANS -> variableFont(R.font.mi_sans_vf, weight)
                SubtitleFont.SANS_SERIF -> FontFamily.SansSerif
                SubtitleFont.SERIF -> FontFamily.Serif
                SubtitleFont.MONOSPACE -> FontFamily.Monospace
                SubtitleFont.CURSIVE -> FontFamily.Cursive
            }
        }
    }

    fun getFontWeight(weightValue: Int): FontWeight {
        val clamped = weightValue.coerceIn(100, 900)
        return FontWeight(clamped)
    }

    fun getFontStyle(isItalic: Boolean): FontStyle {
        return if (isItalic) FontStyle.Italic else FontStyle.Normal
    }
}
