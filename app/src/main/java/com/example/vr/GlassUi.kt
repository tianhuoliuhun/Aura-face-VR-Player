package com.example.vr

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow

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
    /** iOS 11 磨砂玻璃：无折射 + 高模糊 + 低饱和，均匀柔和，带细颗粒噪点 */
    Frosted,

    /**
     * **Liquid Glass（iOS 13+ / iOS 26 取舍后的增强版）**
     *
     * v2.1.218：原先 Liquid 与 Liquid26 是两档，用户反馈两档观感接近、选择意义不大，
     * 故**合并为一档并直接采用 iOS 26 的参数**（折射更强、边缘更镜面、模糊略小更通透）。
     */
    Liquid,
}

/**
 * 历史：主控栏 / 大面板的模糊半径。
 *
 * ⚠️ **v2.6.18 起已无任何调用点**（液态档去掉全局模糊 → 面板不再需要半径；
 * 磨砂档用自己的 [GlassPanelBlurFrosted]）。常量保留仅为二进制/调用兼容，
 * 新代码不要再引用它。
 */
val GlassPanelBlur: Dp = 22.dp

/**
 * 磨砂面板的模糊半径。
 *
 * ⚠️ **32dp 是实测可用上限**：系统的 `RenderEffect.createBlurEffect` 在半径过大时会
 * **静默不绘制** —— 表现不是报错，而是「模糊完全没发生」（面板只剩那层提亮白，
 * 看起来像"没生效"）。v2.1.221 实测 40dp 就已经"完全没模糊"，收敛到面板 32dp / 球体 18dp。
 * 想再调大必须**真机验证**（不同 GPU/驱动能承受的上限不同）。
 *
 * v2.4.68：原「高斯模糊」档已按用户要求删除，这一档的半径常量一并移除。
 */
internal val GlassPanelBlurFrosted: Dp = 32.dp

/**
 * 悬浮球 / 小控件：模糊半径（按风格分档）。
 *
 * ⚠️ v2.6.18：液态档已去掉全局模糊 → `Liquid -> GlassSmallBlur` 传下去的值会被
 * [glassPanel] 忽略（分支保留只为方便回滚）。当前真正生效的只有磨砂档那一档。
 */
val GlassSmallBlur: Dp = 11.dp
private val GlassSmallBlurFrosted: Dp = 18.dp

/**
 * 玻璃面板（矩形/任意形状）。
 *
 * @param style 玻璃风格。
 *   - [GlassStyle.Liquid]：**真半透明**，透出全分辨率视频（不采样 backdrop，见下方说明）；
 *   - [GlassStyle.Frosted]：采样 backdrop 的 96px 降采样帧 + 模糊层 + 颗粒层。
 * @param backdrop 仅磨砂档使用。液态档已不再采样任何位图。
 * @param blurRadius 磨砂档的模糊半径；液态档忽略（它没有模糊层）。
 * @param tint 保留参数（v2.4.68 起液态档用 [LiquidGlassTint]，不走这个）。
 * @param onDrawSurface 磨砂档在 backdrop 之上、border 之下画的一层（各调用点用它铺底色）。
 */
fun Modifier.glassPanel(
    backdrop: Backdrop,
    shape: () -> Shape,
    style: GlassStyle = GlassStyle.Liquid,
    blurRadius: Dp? = null,
    tint: Color? = null,
    /**
     * v2.4.68：`true` = 圆形（悬浮球）。体感层会走 `drawCircle` 原生圆形路径，
     * 避免「多层 Path 描边」在球上三角化成多边形棱面。
     */
    isCircle: Boolean = false,
    /** 在 backdrop 之上、border 之下再画一层（原本各调用点用它铺面板底色）。 */
    onDrawSurface: DrawScope.() -> Unit = {}
): Modifier {

    // v2.4.57：三档均已**去掉模糊层**（不再 RenderEffect 高斯模糊），以下历史说明保留供参考。
    // ============ 磨砂：**纯毛玻璃** ============
    // v2.1.220 用户反馈「UI 没有了只剩模糊」——根因是层级用错了：
    // `Modifier.blur` 渲染在**离屏层**上，作用范围是它之后的**整棵子树**，
    // 因此面板里的文字/图标（都是子 Composable）会被一起模糊掉。
    //
    // 正确做法：模糊必须作用在「采样到的背景」上，而不是「自己这棵树」——
    // 所以改用 backdrop 自己的 `blur()` effect（**只作用于 backdrop 层，UI 在其外不受影响**）。
    // 它的底层同样是系统的 `RenderEffect.createBlurEffect`（Android 12+），
    // 与 `Modifier.blur` 用的是同一条系统通路，只是作用域更精确。
    // ============ v2.4.68：高斯模糊档已删除 ============
    // 该档（v2.1.232 加入）与磨砂档的差别只有「无颗粒 + 更白」，实际观感几乎分不出来，
    // 反而让设置里多一个让人纠结的选择 —— 用户要求去掉，档位回到
    // 0 = 纯色 / 1 = 液态 / 2 = 磨砂 三档。
    // 原 Gaussian 分支的注释「与 Frosted 同属 RenderEffect 模糊家族」仍然成立：
    // 磨砂档就是这条通路的唯一出口（见 FrostedGlass.kt）。
    if (style == GlassStyle.Frosted) {
        // v2.4.56：磨砂档（标杆）独立到 FrostedGlass.kt，单独维护，避免被液态档的改动误伤。
        // v2.4.68：blurRadius 透传下去 —— 磨砂恢复模糊层后，面板与球体各用不同半径
        //（球体已不采样 backdrop，实际只有面板会用到）。
        return this.glassFrosted(
            backdrop = backdrop,
            shape = shape,
            onDrawSurface = onDrawSurface,
            blurRadius = blurRadius,
            isCircle = isCircle,
        )
    }

    // ============ Liquid Glass：真·半透明（不采样 backdrop）============
    //
    // 🔴 v2.4.68（用户要求「移除那张 96px 小模糊图，液态更通透清晰」）：
    //    液态档**彻底不走 `drawBackdrop`**，因此：
    //      · 不再需要 `GlassCapability.supportsBlur` 探测降级（本分支已无 RenderEffect）；
    //      · 不再需要 `lens()` 折射（它作用在 backdrop 位图上）；
    //      · 不再需要那层 5% 白 overlay（白雾是"发闷"的来源）。
    //
    // ## 为什么这样反而更通透
    //    `drawBackdrop` 是「把采样到的 backdrop 位图当纹理画进形状里」——
    //    那张位图来自 [VideoBackdrop] 的 96px 降采样帧，**不透明且极糊**，
    //    所以旧实现里"看到视频"其实看到的是"一张 96px 小图放大后的糊块"。
    //    而本项目 `VRGLSurfaceView` **没有** `setZOrderOnTop`（SurfaceView 位于窗口之下），
    //    Compose 的半透明内容会与下方的 SurfaceView 图层**直接合成** ——
    //    也就是说：只要不画不透明位图，用真半透明色就能透过**全分辨率真实视频**。
    //
    //    于是液态档 = 极淡中性色（[LiquidGlassTint]，18%）+ 体感层（受光/厚度/棱边/光泽）。
    //    没有任何降采样、没有任何位图放大，画面是清晰的。
    //
    // ⚠️ 代价：失去 `lens` 的边缘折射。用「棱边高光 + 内侧厚度」在视觉上补足"厚玻璃边缘"，
    //    实测观感更接近 iOS 26 的"薄玻璃"而不是"厚透镜"。
    //
    // 🔴 v2.4.68（用户反馈「球上有多边形」，其实背后角色的深蓝色眼睛穿透了球）：
    //    **球体用更浓的底色**（[LiquidGlassBallTint] 88%）——
    //    54dp 的球透出的永远是画面里某个高对比色块，看起来就像"球上贴了个多边形"，
    //    球里的数字也跟着糊。面板面积大、透出的是整片画面，所以面板仍用 18%。
    //    材质语言（受光/棱边/厚度）两处完全一致，只有"底子厚度"按面积区分。
    // ============ v2.6.18：**真液态玻璃** ============
    // 走 Kyant0 backdrop 的完整效果链（这就是 iOS 26 Liquid Glass 的开源实现）：
    //   vibrancy + 增饱和 + lens 边缘折射（含逐通道色散）+ Ambient 环境高光 + 内侧阴影。
    // ⚠️ v2.6.18：链上的**模糊一环已移除**（见下方 effects 内的说明）——
    //   真液态玻璃是"清晰背景 + 边缘 SDF 折射"，不是"糊背景 + 亮描边"。
    // 前提：VideoBackdrop 必须在采样（VRPlayerScreen 已对液态档开启），
    // 否则 backdrop 只有纯色，折射无从谈起（lens 作用在 backdrop 位图上）。
    if (GlassCapability.supportsLens) {
        return this
            .drawBackdrop(
                backdrop = backdrop,
                shape = shape,
                onDrawSurface = {
                    // 薄底色保证文字可读（叠在**清晰**背景上，只做轻度压暗）
                    drawRect(if (isCircle) LiquidGlassBallTint else LiquidGlassTint)
                    onDrawSurface()
                },
                effects = {
                    // 🔴 v2.6.18（用户对比 Liquid Glass 2.0 后指出）：**去掉全局模糊**。
                    //
                    // 这一条是「液态玻璃 2.0 单 pass AGSL lens pipeline」与
                    // 「Classic 毛玻璃 pipeline（API 24+）」之间最本质的分界：
                    //   · 毛玻璃（Classic）= 全局高斯模糊 + 色差 + 边缘画一圈亮边。
                    //     整块玻璃是一张**糊掉的照片**，边缘高光只是贴在糊图上的描边；
                    //   · 液态玻璃（AGSL lens，API 33+）= 只有一次由 SDF 驱动的成像：
                    //     玻璃本体**保持背景原有清晰度**，只有边缘那一圈被 SDF 折射"掰弯"
                    //     （edge compression ring），并沿法线做逐通道色散。
                    //     于是看到的是"一块透镜压在画面上"，而不是"一块磨砂塑料"。
                    //
                    // 再叠一层全局模糊的后果不是"更润"，而是把透镜又糊回磨砂：
                    // lens 掰出来的边缘结构被糊平、色散细边被抹掉，
                    // 只剩"一块半透明毛玻璃 + 一圈看不出名堂的亮边" —— 就是仍然叠着模糊那版的观感。
                    // 顺带：每帧省掉一次 RenderEffect blur pass。
                    runCatching {
                        // 通透感：背景增饱和（对应 iOS 的 vibrancy）
                        vibrancy()
                        // brightness 是"偏移量"（0 = 不变），不是倍数：+2% 提亮、+5% 对比、1.2 饱和
                        // 对应 Liquid Glass 2.0 的 "Adaptive Luminance Tint"：
                        // 玻璃自身不糊背景，只对透过的光做色度/亮度适配。
                        //
                        // ⚠️ v2.6.18：饱和 **1.4 → 1.2**。1.4 是当年"背景已被糊成一片色块、
                        // 靠拉饱和把画面救回来"的补偿值；现在背景是清晰的，
                        // 再拉 1.4 会让玻璃内的画面明显比玻璃外艳一圈，一眼就看得出"盖了块东西"。
                        colorControls(0.02f, 1.05f, 1.2f)
                    }.onFailure {
                        android.util.Log.w("GlassUi", "液态色调链不可用，跳过：${it.message}")
                    }
                    // 边缘折射 —— 液态玻璃最标志性的效果：玻璃把背景"掰弯"。
                    // 🔴 v2.6.18：去掉全局模糊后，**lens 就是这条链唯一的成像来源**。
                    //   它依赖 AGSL RuntimeShader（Android 13 / API 33+，见 `supportsLens`）；
                    //   在 31/32 上它不可用，而此刻又已经没有模糊兜底 ——
                    //   所以门控必须跟着抬到 33，否则 31/32 会得到"既没糊也没折射"的半透明板，
                    //   还不如走下面的纯 Canvas 体感层降级。
                    //
                    // v2.6.18（backdrop 2.0.0）：**打开色散**（chromaticAberration）。
                    // 这是 iOS 26 Liquid Glass 与普通"毛玻璃"最本质的区别 ——
                    // 2.x 会切到 `RoundedRectRefractionWithDispersion` 这张专用 shader，
                    // 沿法线对 R/G/B 用**不同折射量**，于是玻璃边缘出现细窄的光谱色边。
                    // 关掉它，边缘只是"被掰弯"，观感仍是 iOS 13 的薄玻璃。
                    //
                    // 折射高度按件型分档：面板边缘厚（20dp），球体小（7dp）——
                    // 球上套 20dp 的折射带会把整个球吃掉，看起来像一颗色块。
                    runCatching {
                        lens(
                            refractionHeight = (if (isCircle) 7.dp else 20.dp).toPx(),
                            refractionAmount = if (isCircle) 0.9f else 0.75f,
                            depthEffect = true,
                            chromaticAberration = true,
                        )
                    }.onFailure {
                        android.util.Log.w("GlassUi", "lens 折射不可用，跳过：${it.message}")
                    }
                },
                // 环境光高光：库内置 Ambient 风格（比手写 glassBody 更接近 iOS 26）
                highlight = { Highlight.Ambient },
                // 内侧阴影：玻璃厚度感
                innerShadow = { InnerShadow.Default },
            )
            .border(
                width = 0.8.dp,
                // 极细轮廓：上/左偏亮、下/右偏暗，模拟环境光从上方来
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.55f),
                        Color.White.copy(alpha = 0.10f),
                        Color.White.copy(alpha = 0.04f),
                    ),
                    start = androidx.compose.ui.geometry.Offset.Zero,
                    end = androidx.compose.ui.geometry.Offset.Infinite,
                ),
                shape = shape(),
            )
    }

    // ============ 降级：无 RenderEffect 能力（SDK<31 / ROM 屏蔽实时模糊）============
    // 保留 v2.4.68 的「真半透明 + 纯 Canvas 体感层」—— 不依赖任何系统特效，全平台可用。
    return this
        .background(if (isCircle) LiquidGlassBallTint else LiquidGlassTint, shape = shape())
        .border(
            border = BorderStroke(
                width = 1.dp,
                // 玻璃高光边：上/左偏亮、下/右偏暗，模拟环境光从上方来
                brush = Brush.linearGradient(
                    colors = listOf(
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
        // v2.4.68：纯 Canvas 体感层（顶部受光 / 内侧厚度 / 棱边高光 / 斜向光泽）
        .glassBody(shape, GlassBody.Liquid, isCircle)
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
    // v2.1.232：球体必须用**控件专用**的小半径。
    // 原先这里直接把 null 传给 glassPanel，于是小球拿到的是面板档（22/32dp）——
    // 54dp 的球配 32dp 模糊，糊得完全没有形状，看起来就是一团色。
    // GlassSmallBlur* 此前定义了却从未被用到，原因就在这行。
    blurRadius = blurRadius ?: when (style) {
        GlassStyle.Liquid -> GlassSmallBlur
        GlassStyle.Frosted -> GlassSmallBlurFrosted
    },
    // v2.4.68：球 = 圆形 → 体感层走 drawCircle，避免 Path 描边三角化成多边形
    isCircle = true,
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

/**
 * v2.1.229：**澎湃 OS（HyperOS）/ MIUI 的模糊能力探测与降级**。
 *
 * ## 为什么需要这个
 * 实测与社区反馈（Haze 库 issue、小米官方说明）表明 HyperOS 对实时模糊有**硬件分级**：
 *   - 仅部分芯片支持 real-time blur（骁龙 8+ Gen1 及以上 / 天玑 9200+ 及以上）
 *   - 被分级屏蔽的机型上，`RenderEffect.createBlurEffect()` 会**抛 "nativePtr is null"**
 *     或**静默失效** —— 表现就是「设置了模糊但完全看不到效果」
 *   - 动态调整模糊半径时更容易触发（我们在切换玻璃风格时正是动态改半径）
 *
 * ## 探测方式
 * 直接尝试创建一个最小半径的 RenderEffect 并立即释放：
 * 成功 → 本机可用；抛异常 → 降级。
 * 比读 Build.MODEL 白名单可靠（HyperOS 的分级随版本变化，硬编码名单会过期）。
 *
 * ## 降级策略
 * 不支持模糊时，磨砂档退化为**半透明白 + 轻微描边**（而不是什么都不做），
 * 至少让用户看到「这里有一层玻璃」，而不是「点了没反应」。
 * v2.4.57：玻璃去掉模糊层后，磨砂/高斯不再依赖本探测（已无 RenderEffect 特效）；
 * 仅液态档的 `lens()` 折射仍用它兜底。
 */
object GlassCapability {

    /**
     * 本机是否支持 RenderEffect 高斯模糊。
     *
     * ⚠️ 用 `by lazy` 缓存：探测本身要创建一次 RenderEffect，
     * 不该每帧或每次组合都做。
     */
    val supportsBlur: Boolean by lazy {
        if (android.os.Build.VERSION.SDK_INT < 31) return@lazy false
        try {
            // 最小成本的探测：建一个 1px 半径的效果再丢了
            val effect = android.graphics.RenderEffect.createBlurEffect(
                1f, 1f, android.graphics.Shader.TileMode.CLAMP
            )
            // 触发一次 native 侧分配，确保不是"延迟到绘制时才失败"
            effect.hashCode()
            true
        } catch (t: Throwable) {
            // HyperOS 被分级屏蔽的机型会在这里抛 nativePtr is null
            android.util.Log.w(
                "GlassCapability",
                "本机不支持 RenderEffect 模糊，玻璃效果将降级为半透明底：${t.message}"
            )
            false
        }
    }

    /**
     * v2.6.18：**真液态玻璃（AGSL lens）是否可用** —— 液态档的新门控。
     *
     * 去掉全局模糊之后，液态档的全部视觉重量都压在 `lens()` 上：
     * 它是 `RuntimeShader`（AGSL）驱动的 SDF 折射，**API 33+ 才完整可用**。
     * 若仍按老门控（[supportsBlur]，31+）放行，31/32 的机器会拿到一块
     * "清晰背景 + 18% 深色"的板子 —— 既没有模糊也没有折射，
     * 反而不如走 [glassPanel] 末尾那套纯 Canvas 体感层（棱边高光/厚度/光泽）。
     *
     * 与 [supportsBlur] 串联：后者已验证本机 RenderEffect 通路没被 ROM 屏蔽，
     * 是 lens 能出图的前提条件之一。
     */
    val supportsLens: Boolean by lazy {
        Build.VERSION.SDK_INT >= 33 && supportsBlur
    }

    /** 是否运行在小米系 ROM 上（HyperOS / MIUI）—— 仅用于诊断日志 */
    val isXiaomiRom: Boolean by lazy {
        val brand = android.os.Build.BRAND.lowercase()
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        brand.contains("xiaomi") || brand.contains("redmi") ||
            brand.contains("poco") || manufacturer.contains("xiaomi")
    }
}
