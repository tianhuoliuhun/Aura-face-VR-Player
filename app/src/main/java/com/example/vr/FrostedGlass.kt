package com.example.vr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
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
 *   - [glassFrosted]：磨砂档的全部绘制逻辑（正常路径 + 降级路径）
 *   - [FrostedGrain]：磨砂颗粒噪点图（128×128 随机白点平铺）
 *   - [drawGrainOverlay]：降级路径（无 backdrop 时）的颗粒平铺
 *
 * ⚠️ 除新增的本文件级说明与函数签名外，逻辑与 v2.4.55 的磨砂分支逐字一致
 *    （原样搬运，未调任何参数）。磨砂档引用的 [GlassCapability] 探测器仍在 GlassUi.kt。
 */
internal fun Modifier.glassFrosted(
    backdrop: Backdrop,
    shape: () -> Shape,
    onDrawSurface: DrawScope.() -> Unit,
    blur: Dp,
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
    // 修法：**模糊与质感解耦** ——
    //   1) 系统 backdrop blur **照常保留**（背景确实有 Compose 内容的场合，
    //      例如设置面板叠在主控栏上时，能拿到真正的模糊）；
    //   2) 再叠一层**不依赖背景内容的磨砂质感**：淡白底 + 细颗粒噪点。
    //      噪点是磨砂玻璃最本质的视觉特征（光线在粗糙表面漫射形成的颗粒感），
    //      即使底下是纯色也能一眼看出"这是一块磨砂玻璃"。
    // v2.4.51：去掉白蒙层（用户要求「去除白色遮罩」）—— 磨砂档改为「纯模糊 + 颗粒」，
    // 不再蒙白雾；颗粒保留（那是磨砂玻璃的质感、也是用户觉得磨砂好看的原因）。
    val frostedSurface: DrawScope.() -> Unit = {
        // 调用方原本铺的底色（面板/球体各自的主题底）
        onDrawSurface()
        // 细颗粒：平铺噪点（v2.4.49：颗粒 alpha 0.55 → 0.35，更轻、更透）
        drawRect(
            brush = ShaderBrush(
                ImageShader(FrostedGrain.bitmap, TileMode.Repeated, TileMode.Repeated)
            ),
            alpha = 0.35f,
        )
    }

    // v2.1.229：**澎湃 OS / MIUI 降级**。
    // HyperOS 对实时模糊有硬件分级：被屏蔽的机型上 RenderEffect 会失效或抛异常
    // （社区实测报 "nativePtr is null"），表现就是「选了磨砂但什么都没发生」。
    // 探测到不可用时**跳过系统模糊**，只保留上面那层质感 ——
    // 观感上仍然是磨砂玻璃，而不是「点了没反应」。
    if (!GlassCapability.supportsBlur) {
        return this
            // v2.4.49：磨砂档降级白底 0.16 → 0.10（与正常档一致更透）
            .background(Color.White.copy(alpha = 0.10f), shape = shape())
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
            // v2.4.49：降级磨砂颗粒 0.5 → 0.35，更轻
            alpha = 0.35f,
        )
    }
