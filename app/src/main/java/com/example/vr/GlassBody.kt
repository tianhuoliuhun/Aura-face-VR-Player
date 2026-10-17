package com.example.vr

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp

/**
 * v2.4.68：**玻璃「体感层」** —— 纯 Canvas 绘制的「顶部受光 + 内侧厚度 + 棱边高光」。
 *
 * ## 为什么液态档必须靠这一层
 * 液态档**不再采样 backdrop 的 96px 降采样帧**（那张"小的模糊图"，见 [VideoBackdrop]），
 * 改为真·半透明（Compose 半透明内容直接与下方的 SurfaceView 视频合成）。
 * 于是"玻璃感"不再有任何偷懒的办法 —— 没有位图可采样、没有 lens 折射可借，
 * 必须由这一层画出**厚度与受光**，否则面板就只是一块薄色片。
 *
 * 真实玻璃的三个廉价但决定的视觉信号：
 *   - **顶部受光**：上边缘接到的环境光最多，越往下越暗；
 *   - **内侧厚度**：玻璃有实体厚度，内壁把从边缘进来的光吸掉一部分 → 沿内边缘一圈柔和暗带；
 *   - **棱边高光**（rim light）：玻璃棱边形成一条比面更亮的细线。
 *
 * ## 实现方式：纯 Canvas，零 RenderEffect / 零离屏层
 * 只用 `clipPath` + 渐变 `drawRect` + 多层 `drawPath(Stroke)`，
 * 每帧只有十几次填充调用，且在「被 ROM 屏蔽实时模糊」的设备上照样有效。
 *
 * 内侧阴影用「多层递增宽度、等量 alpha 的描边」近似（inset-shadow 的经典近似法）：
 * 距边 `d` 处恰好被 `n - d/depth*n` 层覆盖，累积 alpha 随 `d` **线性**衰减，
 * 比单层粗描边自然得多（单层会出现一条清晰的内缘线）。
 *
 * ## 绘制顺序
 * 玻璃表面的反光在物体**前方**，所以本层必须画在内容之后
 * （[glassBody] 内部即 `drawWithContent { drawContent(); … }`）。
 */
internal class GlassBody(
    /** 顶部受光峰值 alpha（白，贴顶边，向下渐隐） */
    val topLight: Float,
    /** 内侧厚度阴影总 alpha（黑，沿内边缘向中心线性衰减） */
    val insetShadow: Float,
    /** 内阴影厚度：占控件**短边**的比例（纯比例，不套 dp 上限） */
    val insetDepth: Float,
    /** 内侧棱边高光 alpha（白，只有上半部亮） */
    val rimLight: Float,
    /** 斜向镜面光泽 alpha（白，从左上射入；磨砂档应为 0） */
    val sheen: Float,
) {
    companion object {
        /**
         * 液态玻璃：**通透第一**。
         *
         * 它透出的是**全分辨率真实视频**（不是降采样帧），所以厚度必须薄：
         * 内壁太重会把清晰的画面压成"发闷的暗板"，反而退回旧观感。
         * 靠**棱边高光 + 顶部受光**承担"这是玻璃"的表达，面尽量空着。
         */
        val Liquid = GlassBody(
            topLight = 0.16f,
            insetShadow = 0.14f,
            insetDepth = 0.08f,
            rimLight = 0.34f,
            sheen = 0.05f,
        )

        /**
         * 磨砂：叠在「backdrop + 模糊层 + 颗粒层」之上，只负责厚度。
         * **不加光泽** —— 磨砂表面漫射，不可能出现镜面反光（加了就变成塑料膜）。
         */
        val Frosted = GlassBody(
            topLight = 0.10f,
            insetShadow = 0.16f,
            insetDepth = 0.09f,
            rimLight = 0.14f,
            sheen = 0f,
        )

        fun of(style: GlassStyle): GlassBody = when (style) {
            GlassStyle.Liquid -> Liquid
            GlassStyle.Frosted -> Frosted
        }
    }
}

/** 内侧阴影的近似层数。6 层已看不出分段（再多纯属浪费绘制调用）。 */
private const val INSET_LAYERS = 6

/** 棱边高光宽度 */
private val RIM_WIDTH = 1.4.dp

/**
 * 给任意 [shape] 叠一层玻璃体感（受光 / 厚度 / 棱边 / 光泽）。
 *
 * 画在**内容之上**（详见 [GlassBody] 的说明）。
 *
 * @param circle **圆形专用路径**（悬浮球）。为 `true` 时内阴影/棱边改用 `drawCircle`
 *   （原生圆形），不再用「多层 Path 描边」近似 —— 后者在球上会被三角化成一圈
 *   **多边形棱面**（v2.4.68 用户截图反馈："球上有多个多边形"）。
 */
internal fun Modifier.glassBody(
    shape: () -> Shape,
    body: GlassBody,
    circle: Boolean = false,
): Modifier =
    this.drawWithContent {
        drawContent()
        drawGlassBody(shape(), body, circle)
    }

/**
 * v2.4.68：**液态玻璃的底色**（极淡的中性深色）。
 *
 * 关键前提：Compose 的半透明内容会与**下方的 SurfaceView 图层**（真实视频）直接合成，
 * 所以这里的 alpha 就是"玻璃的雾度"—— 越低越通透、视频越清晰，但面板上的白字对比度也越低。
 *
 * 0x2E ≈ 18%：在「3D 场景可能很亮」的画面（本项目播放 VR 全景视频）上仍能保住文字可读性，
 * 同时人脸/字幕等内容清晰可辨。想更透就往下调，但不要低于 0x1E。
 */
val LiquidGlassTint: Color = Color(0x2E12111A)

/**
 * v2.4.68：**液态档「悬浮球」用的底色**（比面板浓得多）。
 *
 * ## 为什么不跟面板一样透
 * 面板面积大、背后内容是"一片画面"，透出来是整体观感（通透）；
 * 但球只有 54dp，透出来的往往是**画面里的某个高对比色块**——
 * 实测背后角色的深蓝色眼睛会整块穿过球体，看起来像"球上贴了个蓝色多边形"，
 * 而且球里的时钟/电量数字也会跟着糊掉。
 *
 * 所以球的规则是：**材质语言（受光/棱边/厚度）与面板一致，但底子要压实**。
 * 0xE0 ≈ 88%：留一点点背景色温（不是死黑），又能保证球面干净、文字清晰。
 */
val LiquidGlassBallTint: Color = Color(0xE0141319)

/**
 * 体感层的实际绘制。
 *
 * ⚠️ 全部绘制都 `clipPath` 在形状内部：`drawPath(Stroke)` 是**骑在轮廓线上**的
 * （内外各一半），只有裁掉外侧那一半，才是真正的「内侧」阴影/高光。
 */
internal fun DrawScope.drawGlassBody(
    shape: Shape,
    body: GlassBody,
    circle: Boolean = false,
) {
    if (size.width < 1f || size.height < 1f) return

    val outline = runCatching { shape.createOutline(size, layoutDirection, this) }.getOrNull()
        ?: return
    // 同一份 outline 转 Path，后面 clip 与描边共用（少建一次路径）
    val path = Path().also { it.addOutline(outline) }

    clipPath(path) {
        // ① 顶部受光：双层 —— 贴顶一条窄高光（棱边把光聚起来）+ 一片宽柔光
        if (body.topLight > 0f) {
            // 1a. 宽柔光：从顶边向下铺到 46% 高处，快速衰减
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = body.topLight * 0.62f),
                    0.55f to Color.White.copy(alpha = body.topLight * 0.14f),
                    1f to Color.Transparent,
                    startY = 0f,
                    endY = size.height * 0.46f,
                )
            )
            // 1b. 窄高光带：只占顶边 4~8dp —— 没有这一条，宽柔光看起来只是"整体变亮"，
            //     有了它才有"光打在棱边上"的实体感（圆角处会被 clip 自然收成弧线）
            val band = (size.minDimension * 0.06f).coerceIn(2.5f, 8.dp.toPx())
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = body.topLight),
                    1f to Color.Transparent,
                    startY = 0f,
                    endY = band,
                )
            )
        }

        // ② 内侧厚度阴影
        if (body.insetShadow > 0f) {
            if (circle) {
                // 🔴 圆形专用（悬浮球）：**不用 Path 描边** ——
                //    多层递增宽度的 `drawPath(Stroke)` 在圆上会被三角化成一圈**多边形棱面**
                //    （球体上肉眼可见"钻石切面"，用户截图反馈）。
                //    改用原生 `drawCircle` + 径向渐变：数学上就是同心圆，不可能出现棱角。
                val r = size.minDimension / 2f
                // 暗带从 (1 - depth*2) 半径处开始，到边缘最强
                val inner = (1f - body.insetDepth * 2f).coerceIn(0f, 0.92f)
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to Color.Transparent,
                        inner to Color.Transparent,
                        1f to Color.Black.copy(alpha = body.insetShadow),
                        center = center,
                        radius = r,
                    ),
                    radius = r,
                    center = center,
                )
            } else {
                // ⚠️ 厚度**只按短边比例**算，不套 dp 上限 ——
                //    曾给过 12dp 上限，在 1080p 横屏被截成 24px（只占面板高度 10%），厚度感全没了。
                val depth = (size.minDimension * body.insetDepth)
                    .coerceIn(2f, size.minDimension * 0.25f)
                val stepAlpha = body.insetShadow / INSET_LAYERS
                for (i in 1..INSET_LAYERS) {
                    val w = depth * i / INSET_LAYERS
                    // `DrawScope.drawOutline` 在本项目 Compose 版本里**不是可解析的顶层扩展**，
                    // 所以统一走 drawPath（path 已经建好了）
                    drawPath(
                        path = path,
                        color = Color.Black.copy(alpha = stepAlpha),
                        style = Stroke(width = w * 2f),
                    )
                }
            }
            // 2b. 底部加厚：光从上方来，底部内壁接收到的反射最少、最暗
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black.copy(alpha = body.insetShadow * 0.55f),
                    startY = size.height * 0.62f,
                    endY = size.height,
                )
            )
        }

        // ③ 棱边高光：上半部亮 → 下半部无（光源在上方）
        if (body.rimLight > 0f) {
            val rimBrush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = body.rimLight),
                0.40f to Color.White.copy(alpha = body.rimLight * 0.22f),
                0.72f to Color.Transparent,
                startY = 0f,
                endY = size.height,
            )
            if (circle) {
                // 同样的理由：圆用原生 drawCircle，避免 Path 三角化
                drawCircle(
                    brush = rimBrush,
                    radius = size.minDimension / 2f - RIM_WIDTH.toPx() / 2f,
                    center = center,
                    style = Stroke(width = RIM_WIDTH.toPx()),
                )
            } else {
                drawPath(
                    path = path,
                    brush = rimBrush,
                    style = Stroke(width = RIM_WIDTH.toPx() * 2f),
                )
            }
        }

        // ④ 斜向镜面光泽（仅液态）：从左上角射入的一道柔光
        //    圆形上跳过 —— 斜向线性渐变被圆裁出来的亮弧在球上像"棱面"，与 ② 的多边形同源观感。
        if (body.sheen > 0f && !circle) {
            drawRect(
                brush = Brush.linearGradient(
                    0f to Color.White.copy(alpha = body.sheen),
                    0.45f to Color.White.copy(alpha = body.sheen * 0.18f),
                    1f to Color.Transparent,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, size.height),
                )
            )
        }
    }
}
