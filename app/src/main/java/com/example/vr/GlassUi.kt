package com.example.vr

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur as backdropBlur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * v2.1.217：**玻璃效果的统一实现**，支持两种风格。
 *
 * ## 为什么要抽这个工厂
 * 此前 7 处 `drawBackdrop` 各写一遍 `effects { vibrancy(); blur(...) }`，问题有三：
 * 1. **参数散落**：主控栏 blur 20dp、悬浮球 12dp，想统一调一次得改 7 个地方；
 * 2. **欠配**：只用了 `vibrancy() + blur()`，而库还提供 `lens()`（透镜/折射）、
 *    `colorControls()`（饱和/对比/亮度）等 —— 没用上这些，玻璃就只是「模糊的半透明板」；
 * 3. **改不统一**：调参时极易漏改某几处。
 *
 * ## 两种风格的差别（不是换个名字，是参数与效果都相反）
 * | | [Liquid]（iOS 13+ Liquid Glass）| [Frosted]（iOS 11 磨砂）|
 * |---|---|---|
 * | 透镜折射 `lens` | ✅ 有（边缘把内容"掰弯"）| ❌ **无**（iOS 11 没有这效果）|
 * | 饱和度 | 1.35（通透）| **0.88（略去色，磨砂发灰）** |
 * | 模糊半径 | 22dp | **30dp（更强）** |
 * | vibrancy | ✅ | ❌ 关闭 |
 * | 底色 | 极轻白 5% | **半透明白 12%**（更实、有实体感）|
 * | 观感 | 通透、边缘折射、立体 | **均匀糊开、柔和、平面** |
 */
enum class GlassStyle {
    /** iOS 11 磨砂玻璃：无折射 + 高模糊 + 低饱和，均匀柔和 */
    Frosted,

    /**
     * **Liquid Glass（iOS 13+ / iOS 26 取舍后的增强版）**
     *
     * v2.1.218：原先 Liquid 与 Liquid26 是两档，用户反馈两档观感接近、选择意义不大，
     * 故**合并为一档并直接采用 iOS 26 的参数**（折射更强、边缘更镜面、模糊略小更通透）。
     */
    Liquid,
}

/** 主控栏 / 大面板：模糊半径（按风格分档） */
val GlassPanelBlur: Dp = 22.dp
private val GlassPanelBlurFrosted: Dp = 40.dp

/** 悬浮球 / 小控件：模糊半径（按风格分档） */
val GlassSmallBlur: Dp = 11.dp
private val GlassSmallBlurFrosted: Dp = 20.dp

/**
 * 玻璃面板（矩形/任意形状）。
 *
 * @param style 玻璃风格。[GlassStyle.Liquid] 通透立体；[GlassStyle.Frosted] 柔和磨砂。
 * @param blurRadius 模糊半径；传 null 则用该风格的推荐值。
 *        ⚠️ 半径越大 GPU 开销越高（backdrop 采样 + RenderEffect）；
 *        帧率吃紧时应优先降这个值，而不是去掉 lens。
 */
fun Modifier.glassPanel(
    backdrop: Backdrop,
    shape: () -> Shape,
    style: GlassStyle = GlassStyle.Liquid,
    blurRadius: Dp? = null,
    tint: Color? = null,
    /** 在 backdrop 之上、border 之下再画一层（原本各调用点用它铺面板底色）。 */
    onDrawSurface: DrawScope.() -> Unit = {}
): Modifier {
    val blur = blurRadius ?: when (style) {
        GlassStyle.Liquid -> GlassPanelBlur
        GlassStyle.Frosted -> GlassPanelBlurFrosted
    }
    // 磨砂需要更"实"的半透明底，Liquid 只留极轻的一层
    val overlay = tint ?: when (style) {
        GlassStyle.Liquid -> Color.White.copy(alpha = 0.05f)
        GlassStyle.Frosted -> Color.White.copy(alpha = 0.20f)
    }

    // ============ 磨砂：**完全不同的管线** ============
    // v2.1.219：用户要求「调用系统高斯模糊，去除高亮折射等玻璃特性」。
    // 磨砂不是"弱化的 Liquid Glass"，而是**纯粹的毛玻璃**，所以：
    //   ① backdrop **只负责采样背后的内容**，不施加任何 effects（无 lens / 无 vibrancy / 无 colorFilter）；
    //   ② 模糊交给**系统高斯模糊** `Modifier.blur`（底层即 RenderEffect.createBlurEffect）；
    //   ③ **不画任何边框** —— 玻璃高光/镜面折射是 Liquid Glass 的特征，磨砂不该有。
    if (style == GlassStyle.Frosted) {
        return this
            .drawBackdrop(
                backdrop = backdrop,
                shape = shape,
                onDrawSurface = onDrawSurface,
                effects = {},                 // 空 effects：只采样，不做玻璃特效
            )
            // 系统高斯模糊。⚠️ Modifier.blur 依赖 Android 12+(API 31) 的 RenderEffect，
            // 低版本会静默不生效（Compose 已做版本检查），因此这里不必再手动判断 SDK。
            .blur(blur)
            .drawWithContentOverlay(overlay)
    }

    // ============ Liquid Glass：折射 + 增饱和 + 边缘高光 ============
    return this
        .drawBackdrop(
            backdrop = backdrop,
            shape = shape,
            onDrawSurface = onDrawSurface,
            effects = {
                // ⚠️ 必须保留 SDK>=33 的版本保护：lens() 依赖 Android 13(API 33) 才有的
                //    RenderEffect SDF 能力，低版本直接调会崩。项目原本就有这个保护，
                //    抽到工厂时**不能丢**。
                if (Build.VERSION.SDK_INT >= 33) {
                    // v2.1.218：采用 iOS 26 参数（折射更强、边缘更「厚玻璃聚光」）
                    lens(refractionHeight = 18f, refractionAmount = 0.55f)
                }
                vibrancy()
                colorControls(saturation = 1.45f, contrast = 1.06f, brightness = 1.05f)
                backdropBlur(blur.toPx())
            },
        )
        .border(
            border = BorderStroke(
                width = 1.dp,
                // 玻璃高光边：上/左偏亮、下/右偏暗，模拟环境光从上方来
                brush = Brush.linearGradient(
                    colors = listOf(
                        // v2.1.218：采用 iOS 26 强度 —— 边缘镜面聚光
                        Color.White.copy(alpha = 0.62f),
                        Color.White.copy(alpha = 0.14f),
                        Color.White.copy(alpha = 0.05f),
                    ),
                    start = androidx.compose.ui.geometry.Offset.Zero,
                    end = androidx.compose.ui.geometry.Offset.Infinite,
                ),
            ),
            shape = shape(),
        )
        .drawWithContentOverlay(overlay)
}

/**
 * 玻璃球体（悬浮球等圆形控件）。
 *
 * @param blurRadius 球体专用模糊半径；传 null 用该风格的推荐值。
 * @param onDrawSurface 与 [glassPanel] 同一参数（球体原本用它画圆形底色）。
 */
fun Modifier.glassBall(
    backdrop: Backdrop,
    style: GlassStyle = GlassStyle.Liquid,
    blurRadius: Dp? = null,
    onDrawSurface: DrawScope.() -> Unit = {}
): Modifier = glassPanel(
    backdrop = backdrop,
    shape = { CircleShape },
    style = style,
    blurRadius = blurRadius,
    onDrawSurface = onDrawSurface,
)


/**
 * 在内容之上叠一层半透明色（模拟玻璃表面反射 / 磨砂的实体感）。
 *
 * 放在 `drawBackdrop` **之后**：backdrop 负责采样+模糊+折射，
 * 本函数只在其上盖一层薄色，两者互不干扰。
 */
private fun Modifier.drawWithContentOverlay(color: Color): Modifier =
    if (color.alpha <= 0.001f) this
    else this.drawWithContent {
        drawContent()
        drawRect(color)
    }
