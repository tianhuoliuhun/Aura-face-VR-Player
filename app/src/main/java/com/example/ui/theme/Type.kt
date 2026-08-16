package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

// OPPO Sans 4.0 variable font (weight axis 100..900), bundled locally.
@OptIn(ExperimentalTextApi::class)
val OppoSansFontFamily = FontFamily(
    Font(
        resId = R.font.oppo_sans_4_0,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400))
    )
)

private fun TextStyle.withOppoSans(): TextStyle = copy(fontFamily = OppoSansFontFamily)

// All Material3 typography styles use the bundled OPPO Sans as the app UI font.
val Typography =
  Typography().let { base ->
    base.copy(
      displayLarge = base.displayLarge.withOppoSans(),
      displayMedium = base.displayMedium.withOppoSans(),
      displaySmall = base.displaySmall.withOppoSans(),
      headlineLarge = base.headlineLarge.withOppoSans(),
      headlineMedium = base.headlineMedium.withOppoSans(),
      headlineSmall = base.headlineSmall.withOppoSans(),
      titleLarge = base.titleLarge.withOppoSans(),
      titleMedium = base.titleMedium.withOppoSans(),
      titleSmall = base.titleSmall.withOppoSans(),
      bodyLarge = base.bodyLarge.withOppoSans(),
      bodyMedium = base.bodyMedium.withOppoSans(),
      bodySmall = base.bodySmall.withOppoSans(),
      labelLarge = base.labelLarge.withOppoSans(),
      labelMedium = base.labelMedium.withOppoSans(),
      labelSmall = base.labelSmall.withOppoSans()
    )
  }
