package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.example.R

private val DarkColorScheme =
  darkColorScheme(primary = Purple80, secondary = PurpleGrey80, tertiary = Pink80)

private val LightColorScheme =
  lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
  )

// 全局默认字体：OPPO Sans 4.0（v79 起整个应用统一使用）
// 字体文件位于 res/font/oppo_sans_4_0.ttf（约 21.7MB，含简体中文全量字库）
private val AppFontFamily: FontFamily = FontFamily(Font(R.font.oppo_sans_4_0))

// material3 1.3（BOM 2024.09）的 Typography 没有 defaultFontFamily 参数，
// 因此将全部预置样式统一替换为 OPPO Sans。
private fun Typography.withAppFont(): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = AppFontFamily),
    displayMedium = displayMedium.copy(fontFamily = AppFontFamily),
    displaySmall = displaySmall.copy(fontFamily = AppFontFamily),
    headlineLarge = headlineLarge.copy(fontFamily = AppFontFamily),
    headlineMedium = headlineMedium.copy(fontFamily = AppFontFamily),
    headlineSmall = headlineSmall.copy(fontFamily = AppFontFamily),
    titleLarge = titleLarge.copy(fontFamily = AppFontFamily),
    titleMedium = titleMedium.copy(fontFamily = AppFontFamily),
    titleSmall = titleSmall.copy(fontFamily = AppFontFamily),
    bodyLarge = bodyLarge.copy(fontFamily = AppFontFamily),
    bodyMedium = bodyMedium.copy(fontFamily = AppFontFamily),
    bodySmall = bodySmall.copy(fontFamily = AppFontFamily),
    labelLarge = labelLarge.copy(fontFamily = AppFontFamily),
    labelMedium = labelMedium.copy(fontFamily = AppFontFamily),
    labelSmall = labelSmall.copy(fontFamily = AppFontFamily)
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = true,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(
    colorScheme = colorScheme,
    typography = Typography().withAppFont(),
    content = content
  )
}
