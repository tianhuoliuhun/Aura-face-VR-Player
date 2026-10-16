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
import com.kyant.backdrop.effects.lens

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
     * v2.1.232：**高斯模糊**（纯 Gaussian）。
     *
     * 与 [Frosted] 的区别 —— 磨砂是「模糊 + 噪点颗粒 + 淡白」（模拟粗糙表面漫射）；
     * 高斯模糊则是**纯粹的均匀糊化 + 轻微提亮**，没有颗粒、不模拟材质，
     * 观感更接近 iOS 的 `.systemUltraThinMaterial`：干净、通透、只有虚实之分。
     *
     * 半径也是两档里更大的（80dp），追求「背后的东西完全化作色块」。
     */
    Gaussian,

    /**
     * **Liquid Glass（iOS 13+ / iOS 26 取舍后的增强版）**
     *
     * v2.1.218：原先 Liquid 与 Liquid26 是两档，用户反馈两档观感接近、选择意义不大，
     * 故**合并为一档并直接采用 iOS 26 的参数**（折射更强、边缘更镜面、模糊略小更通透）。
     */
    Liquid,
}

/**
 * 主控栏 / 大面板：模糊半径（按风格分档）。
 * v2.4.57：玻璃已**去掉模糊层**（不再对背景做 RenderEffect 高斯模糊），
 * 半径常量保留仅为 API 兼容，实际不再使用。
 */
val GlassPanelBlur: Dp = 22.dp
private val GlassPanelBlurFrosted: Dp = 32.dp

/**
 * v2.4.47（用户反馈「高斯模糊没生效」）：**80dp → 32dp**。
 *
 * 🔴 根因：系统的 `RenderEffect.createBlurEffect` 在**半径过大时会静默不绘制** ——
 * 表现不是报错，而是「模糊完全没发生」（面板只剩那层 10% 提亮白，看起来像"没生效"）。
 * 本项目 **v2.1.221 就已经踩过并记录过**这一点（见上面 Frosted 的注释：
 * 「40dp 过大…降到 32dp（面板）/ 18dp（球体）实测可用」），
 * 但 v2.1.232 新增高斯档时又把半径设成了 80dp/40dp —— **与自己的实测结论相矛盾**，
 * 于是高斯档成了唯一「看不出模糊」的一档（液态 22dp / 磨砂 32dp 都正常）。
 *
 * 现收敛到**实测可用区间**：面板 32dp、球体 18dp。
 * 与磨砂档的视觉差别不靠半径，而靠 **无颗粒 + 轻微提亮/去饱和**（见 Gaussian 分支）。
 * ⚠️ 想再调大必须**真机验证**（不同 GPU/驱动能承受的上限不同）。
 */
private val GlassPanelBlurGaussian: Dp = 32.dp

/** 悬浮球 / 小控件：模糊半径（按风格分档） */
val GlassSmallBlur: Dp = 11.dp
private val GlassSmallBlurFrosted: Dp = 18.dp
private val GlassSmallBlurGaussian: Dp = 18.dp

/**
 * 玻璃面板（矩形/任意形状）。
 *
 * @param style 玻璃风格。[GlassStyle.Liquid] 通透立体；[GlassStyle.Frosted] 柔和磨砂。
 * @param blurRadius （v2.4.57 起已不使用）模糊半径；保留参数仅为调用方兼容。
 *        v2.4.57 玻璃去掉模糊层后，帧率开销主要来自 backdrop 采样本身。
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
    // ⚠️ 只服务 Liquid 分支：降级分支用它撑出半透明白底；正常路径在末尾
    //    `.drawWithContentOverlay(overlay)` 再盖一层 5% 白（v2.4.45 行为：彩色厚玻璃观感）。
    // （v2.4.49 曾把 0.05 → 0.03，v2.4.56 回退液态/高斯到 2.4.45 时 Liquid 恢复 0.05。）
    val overlay = tint ?: Color.White.copy(alpha = 0.05f)

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
    // ============ v2.1.232：高斯模糊（Gaussian） ============
    // 与 Frosted 同属「系统 RenderEffect 模糊家族」，差别在**要不要模拟材质**：
    //   Frosted  = 模糊 + 噪点颗粒 + 淡白 —— 模拟粗糙表面漫射，手感「涩」
    //   Gaussian = 只有模糊 + 轻微提亮 —— 不模拟材质，观感干净通透
    if (style == GlassStyle.Gaussian) {
        // v2.4.57：**去掉模糊层**（用户指定「玻璃不再模糊背景」）——
        //   高斯档不再对背景做 RenderEffect 高斯模糊，视频从面板下清晰透出；
        //   只保留 surface 层的极淡白提亮 + 边框。无任何 RenderEffect 特效，
        //   HyperOS 模糊降级分支一并移除（清晰透出全平台可用）。
        // v2.4.58：**增强高斯与磨砂的视觉差异** ——
        //   高斯档提升到 18% 提亮（比磨砂的 12% 更透亮），边框加亮到 0.35（更明显），
        //   给用户明确的"干净清爽"感觉（无颗粒）vs "质感磨砂"（有颗粒）。
        val gaussianSurface: DrawScope.() -> Unit = {
            onDrawSurface()
            // 相比磨砂更强的提亮：0.12% → 0.18%，强化干净通透的观感
            drawRect(Color.White.copy(alpha = 0.18f))
        }
        return this
            .drawBackdrop(
                backdrop = backdrop,
                shape = shape,
                onDrawSurface = gaussianSurface,
                effects = {},
            )
            .border(
                width = 1.dp,
                // 相比磨砂更亮的边框：0.28 → 0.35，强化"玻璃感"
                color = Color.White.copy(alpha = 0.35f),
                shape = shape(),
            )
    }

    if (style == GlassStyle.Frosted) {
        // v2.4.56：磨砂档（标杆）独立到 FrostedGlass.kt，单独维护，避免被液态/高斯的改动误伤。
        return this.glassFrosted(backdrop = backdrop, shape = shape, onDrawSurface = onDrawSurface)
    }

    // ============ Liquid Glass：折射 + 边缘高光 ============
    //
    // 🔴 v2.4.45（修「液态玻璃在 Android 12 闪退」）：**必须与 Frosted / Gaussian 一样先过
    //    `GlassCapability.supportsBlur`**。此前只有那两档做了探测降级，**Liquid 档漏了** ——
    //    于是在「系统 / 厂商 ROM 屏蔽实时模糊」的 Android 12 设备（典型：MIUI 13 基于 Android 12）
    //    上，`drawBackdrop` 内部创建 `RenderEffect` 会抛 `nativePtr is null`；
    //    而 `drawBackdrop` 在**绘制阶段**执行，异常无法在业务层 catch → **直接闪退**。
    //    探测失败时降级为「半透明白底 + 高光描边」，不再崩。
    // v2.4.56：整个分支回退到 v2.4.45 行为（用户指定）。
    // v2.4.57：**去掉模糊层**（用户指定「玻璃不再模糊背景」）——
    //    撤掉 backdropBlur / vibrancy / colorControls，只保留 `lens()` 折射；
    //    supportsBlur 降级分支保留（lens 仍是 RenderEffect 特效，需兜底）。
    if (!GlassCapability.supportsBlur) {
        return this
            .background(overlay, shape = shape())
            .border(
                width = 1.dp,
                // v2.4.58：降级分支也用强边框 0.28，保持视觉一致性
                color = Color.White.copy(alpha = 0.28f),
                shape = shape(),
            )
    }
    return this
        .drawBackdrop(
            backdrop = backdrop,
            shape = shape,
            onDrawSurface = onDrawSurface,
            effects = {
                // 🔴 v2.4.57：去掉模糊层后液态档只保留 `lens()` 折射。
                //    仍包 `runCatching`：lens 属 RenderEffect 特效，异常时退化为「无特效 backdrop」。
                runCatching {
                    // ⚠️ 必须保留 SDK>=33 的版本保护：`lens()` 依赖 Android 13(API 33) 才有的
                    //    RenderEffect SDF 能力，且库对 <33 **是抛异常**
                    //    （反编译可见 `LensKt.throwUnsupportedSDFException()`），不是静默降级。
                    if (Build.VERSION.SDK_INT >= 33) {
                        // v2.1.218：采用 iOS 26 参数（折射更强、边缘更「厚玻璃聚光」）
                        lens(refractionHeight = 18f, refractionAmount = 0.55f)
                    }
                }.onFailure {
                    android.util.Log.w(
                        "GlassUi",
                        "Liquid 特效应用失败，已降级为「无特效 backdrop」：${it.message}"
                    )
                }
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
    // v2.1.232：球体必须用**控件专用**的小半径。
    // 原先这里直接把 null 传给 glassPanel，于是小球拿到的是面板档（22/32dp）——
    // 54dp 的球配 32dp 模糊，糊得完全没有形状，看起来就是一团色。
    // GlassSmallBlur* 此前定义了却从未被用到，原因就在这行。
    blurRadius = blurRadius ?: when (style) {
        GlassStyle.Liquid -> GlassSmallBlur
        GlassStyle.Frosted -> GlassSmallBlurFrosted
        GlassStyle.Gaussian -> GlassSmallBlurGaussian
    },
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

    /** 是否运行在小米系 ROM 上（HyperOS / MIUI）—— 仅用于诊断日志 */
    val isXiaomiRom: Boolean by lazy {
        val brand = android.os.Build.BRAND.lowercase()
        val manufacturer = android.os.Build.MANUFACTURER.lowercase()
        brand.contains("xiaomi") || brand.contains("redmi") ||
            brand.contains("poco") || manufacturer.contains("xiaomi")
    }
}
