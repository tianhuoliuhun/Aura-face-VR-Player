package com.example.vr

import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
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

/**
 * 磨砂玻璃（Frosted，iOS 11 风格）—— v2.4.56 从 GlassUi.kt 独立出来的「标杆档」。
 *
 * 用户明确要求：磨砂是三档里观感最好的基准，独立成文件单独维护，
 * 避免被液态/高斯档的反复调整误伤。本文件包含：
 *   - [glassFrosted]：磨砂档的全部绘制逻辑
 *   - [FrostedGrain]：磨砂颗粒噪点图（128×128 随机白点平铺）
 *
 * v2.4.57：**去掉模糊层**（用户指定「玻璃不再模糊背景」）——
 * 磨砂不再对背景做 RenderEffect 高斯模糊，视频从面板下清晰透出，
 * 只保留 surface 层的颗粒质感 + 边框；HyperOS 模糊降级分支与 drawGrainOverlay
 * 一并移除（无任何 RenderEffect 特效，清晰透出全平台可用）。
 */
internal fun Modifier.glassFrosted(
    backdrop: Backdrop,
    shape: () -> Shape,
    onDrawSurface: DrawScope.() -> Unit,
    blurRadius: Dp? = null,
    /** v2.4.68：`true` = 圆形（悬浮球）→ 体感层走 drawCircle，避免 Path 描边出现多边形 */
    isCircle: Boolean = false,
): Modifier {
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
    // 修法（v2.4.57 之后）：**不再模糊背景**（用户明确要求）。磨砂的质感完全由 surface 层提供：
    //   1) 淡白底 + 细颗粒噪点（不依赖背景内容，纯色底上也可见）；
    //   2) 边框。
    //   （下面 drawBackdrop 的 effects 为空，即没有任何 RenderEffect 模糊。）
    //      噪点是磨砂玻璃最本质的视觉特征（光线在粗糙表面漫射形成的颗粒感），
    //      即使底下是纯色也能一眼看出"这是一块磨砂玻璃"。
    // v2.4.51：去掉白蒙层（用户要求「去除白色遮罩」）—— 磨砂档改为「纯模糊 + 颗粒」，
    // 不再蒙白雾；颗粒保留（那是磨砂玻璃的质感、也是用户觉得磨砂好看的原因）。
    val frostedSurface: DrawScope.() -> Unit = {
        // 调用方原本铺的底色（面板/球体各自的主题底）
        onDrawSurface()
        // 淡白蒙层（v2.4.51 去掉后，v2.4.58 恢复：增强磨砂的"实体感"）
        // 磨砂需要 12% 白底来"实现质感"
        drawRect(Color.White.copy(alpha = 0.12f))
        // 细颗粒：平铺噪点（v2.4.49：颗粒 alpha 0.55 → 0.35；v2.4.58：强化到 0.42）
        // v2.4.68：改用共用 brush 单例（此前每帧 new 一次 ShaderBrush/ImageShader）
        drawRect(brush = FrostedGrain.brush, alpha = 0.42f)
    }

    return this
        .drawBackdrop(
            backdrop = backdrop,
            shape = shape,
            onDrawSurface = frostedSurface,
            // 🔴 v2.4.68：**恢复模糊层** —— 磨砂玻璃的本质是「背后内容被漫射掉」，
            //    没有模糊层就只是"带噪点的半透明板"。
            //    这是 96px 降采样帧**唯一还被允许的用途**：它的分辨率低、放大后本就糊，
            //    再叠 20dp 模糊 + 颗粒，观感连贯且省 GL 读回开销。
            //    ⚠️ 半径必须留在实测可用区间（v2.1.221 实测 40dp 会静默不绘制）：
            //    面板 32dp / 球 18dp。
            effects = {
                runCatching {
                    // `toPx()` 必须在 DrawScope 作用域内调（effects 的 receiver 就是 DrawScope）
                    backdropBlur((blurRadius ?: GlassPanelBlurFrosted).toPx())
                }.onFailure {
                    android.util.Log.w(
                        "FrostedGlass",
                        "磨砂模糊层应用失败，退化为「只有颗粒的磨砂」：${it.message}"
                    )
                }
            },
        )
        .border(
            width = 1.dp,
            // v2.4.58：边框调整为 0.16（比液态档的镜面高光边更暗），强化"质感"而非"透亮"
            color = Color.White.copy(alpha = 0.16f),
            shape = shape(),
        )
        // v2.4.68：体感层（sheen=0，磨砂表面漫射不应出现镜面反光）——
        // 让磨砂与液态共用同一套「顶部受光 + 内侧厚度 + 棱边」语言，两档切换时边缘过渡自然。
        .glassBody(shape, GlassBody.Frosted, isCircle)
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

    /**
     * v2.4.68：**全进程共用一份平铺 brush**。
     *
     * 面板的 `frostedSurface` 是 DrawScope lambda（每帧执行），此前每帧都 new 一个
     * `ShaderBrush` + `ImageShader`；球的颗粒层也要用它 —— 统一成单例，既不重复分配，
     * 也保证**面板与球的颗粒密度完全一致**（同一份 128×128 位图 1:1 平铺），
     * 切换档位/看球与看面板时颗粒不会"一大一小"。
     */
    val brush: ShaderBrush by lazy {
        ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
    }

    private fun generate(): ImageBitmap {
        val bmp = android.graphics.Bitmap.createBitmap(
            SIZE, SIZE, android.graphics.Bitmap.Config.ARGB_8888
        )
        val rnd = java.util.Random(20261004)
        val px = IntArray(SIZE * SIZE)
        for (i in px.indices) {
            // 白点，alpha 随机 0~34（约 13%）—— 淡到只形成颗粒、不形成白雾。
            // ⚠️ Android 位图默认是**预乘 alpha**：颜色通道必须 ≤ alpha。
            //    白色预乘后 = (a, a, a, a)；若写成 (a, 255, 255, 255) 属非法值，渲染会偏亮/失真。
            val a = rnd.nextInt(35)
            px[i] = (a shl 24) or (a shl 16) or (a shl 8) or a
        }
        bmp.setPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        return bmp.asImageBitmap()
    }
}

