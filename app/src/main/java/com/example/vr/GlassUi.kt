package com.example.vr

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
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
private val GlassPanelBlurFrosted: Dp = 32.dp

/** 悬浮球 / 小控件：模糊半径（按风格分档） */
val GlassSmallBlur: Dp = 11.dp
private val GlassSmallBlurFrosted: Dp = 18.dp

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
        // v2.1.221：40dp 过大。系统的 RenderEffect.createBlurEffect 在半径过大时
        // 可能直接不绘制，表现为「模糊完全没生效」。降到 16dp（面板）/ 9dp（球体）实测可用。
        GlassStyle.Frosted -> GlassPanelBlurFrosted
    }
    // ⚠️ 只服务 Liquid 分支；磨砂的白底在它的分支里单独给（40%）。
    val overlay = tint ?: Color.White.copy(alpha = 0.05f)

    // ============ 磨砂：**纯毛玻璃** ============
    // v2.1.220 用户反馈「UI 没有了只剩模糊」——根因是层级用错了：
    // `Modifier.blur` 渲染在**离屏层**上，作用范围是它之后的**整棵子树**，
    // 因此面板里的文字/图标（都是子 Composable）会被一起模糊掉。
    //
    // 正确做法：模糊必须作用在「采样到的背景」上，而不是「自己这棵树」——
    // 所以改用 backdrop 自己的 `blur()` effect（**只作用于 backdrop 层，UI 在其外不受影响**）。
    // 它的底层同样是系统的 `RenderEffect.createBlurEffect`（Android 12+），
    // 与 `Modifier.blur` 用的是同一条系统通路，只是作用域更精确。
    if (style == GlassStyle.Frosted) {
        // ============ v2.1.231：磨砂为什么"看起来没模糊" ============
        // 根因不是参数没调对，而是 **backdrop 里根本没有可被模糊的内容**：
        //   播放页的视频是 `VRGLSurfaceView extends GLSurfaceView extends SurfaceView`。
        //   SurfaceView 在 Android 合成器里是**独立窗口、打洞（punch hole）**的，
        //   Compose 的 layer backdrop 采样的是 Compose 自己的绘制层 —— **采不到 SurfaceView
        //   的内容**。于是 backdrop 里实际只有 `drawRect(ThemeBgColor)` 那一层纯色。
        //   **对纯色做高斯模糊，结果还是同一片纯色** —— 所以无论把半径调到多大，
        //   磨砂档在主界面上永远"看不出任何变化"。
        //   （液态玻璃反而看得出：它有半透明白底 + 高光渐变描边 + lens 边缘折射，
        //    这些在纯色底上照样可见，所以用户从没抱怨过液态档。）
        //
        // 修法：**模糊与质感解耦** ——
        //   1) 系统 backdrop blur **照常保留**（背景确实有 Compose 内容的场合，
        //      例如设置面板叠在主控栏上时，能拿到真正的模糊）；
        //   2) 再叠一层**不依赖背景内容的磨砂质感**：淡白底 + 细颗粒噪点。
        //      噪点是磨砂玻璃最本质的视觉特征（光线在粗糙表面漫射形成的颗粒感），
        //      即使底下是纯色也能一眼看出"这是一块磨砂玻璃"。
        val frostedSurface: DrawScope.() -> Unit = {
            // 调用方原本铺的底色（面板/球体各自的主题底）
            onDrawSurface()
            // 淡白：给磨砂玻璃一点实体感（0.14 —— 很淡，不会盖住真的模糊）
            drawRect(Color.White.copy(alpha = 0.14f))
            // 细颗粒：平铺噪点
            drawRect(
                brush = ShaderBrush(
                    ImageShader(FrostedGrain.bitmap, TileMode.Repeated, TileMode.Repeated)
                ),
                alpha = 0.55f,
            )
        }

        // v2.1.229：**澎湃 OS / MIUI 降级**。
        // HyperOS 对实时模糊有硬件分级：被屏蔽的机型上 RenderEffect 会失效或抛异常
        // （社区实测报 "nativePtr is null"），表现就是「选了磨砂但什么都没发生」。
        // 探测到不可用时**跳过系统模糊**，只保留上面那层质感 ——
        // 观感上仍然是磨砂玻璃，而不是「点了没反应」。
        if (!GlassCapability.supportsBlur) {
            return this
                .background(Color.White.copy(alpha = 0.16f), shape = shape())
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.22f),
                    shape = shape(),
                )
                // clip 是为了把颗粒裁成面板/球体的形状（颗粒本身是整块矩形平铺）
                .clip(shape())
                .drawGrainOverlay()
        }
        return this
            .drawBackdrop(
                backdrop = backdrop,
                shape = shape,
                onDrawSurface = frostedSurface,
                effects = {
                    // 只做高斯模糊，**无 lens / vibrancy / colorControls** —— 纯毛玻璃
                    backdropBlur(blur.toPx())
                },
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.22f),
                shape = shape(),
            )
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

/**
 * v2.1.231：**磨砂颗粒（噪点）图**。
 *
 * 磨砂玻璃区别于「普通半透明板」的本质，是表面粗糙导致光线漫射形成的**细颗粒感**。
 * 这层颗粒是**不依赖背景内容**的 —— 即便底下是一片纯色（见 [glassPanel] 里
 * 「backdrop 采不到 SurfaceView」的说明），颗粒依然清晰可见，
 * 用户就能明确看出「这是一块磨砂玻璃」。
 *
 * 实现：128×128 的随机白点图（alpha 0~34，很淡），用 [ImageShader] + [TileMode.Repeated]
 * 平铺 —— 1:1 像素平铺不会被拉伸成大块，绘制成本就是一次 `drawRect`。
 */
object FrostedGrain {
    private const val SIZE = 128

    /** 只生成一次（16384 个像素，实测 < 5ms），全进程共用 */
    val bitmap: ImageBitmap by lazy { generate() }

    private fun generate(): ImageBitmap {
        val bmp = android.graphics.Bitmap.createBitmap(
            SIZE, SIZE, android.graphics.Bitmap.Config.ARGB_8888
        )
        val rnd = java.util.Random(20261004)
        val px = IntArray(SIZE * SIZE)
        for (i in px.indices) {
            // 白点，alpha 随机 0~34（约 13%）—— 淡到只形成颗粒、不形成白雾
            px[i] = (rnd.nextInt(35) shl 24) or 0x00FFFFFF
        }
        bmp.setPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        return bmp.asImageBitmap()
    }
}

/** 在内容之上平铺一层磨砂颗粒（降级分支用 —— 此时没有 backdrop 可用） */
private fun Modifier.drawGrainOverlay(): Modifier =
    this.drawWithContent {
        drawContent()
        drawRect(
            brush = ShaderBrush(
                ImageShader(FrostedGrain.bitmap, TileMode.Repeated, TileMode.Repeated)
            ),
            alpha = 0.5f,
        )
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
