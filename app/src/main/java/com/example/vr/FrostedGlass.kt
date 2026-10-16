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
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop

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
        // 淡白蒙层（v2.4.51 去掉后，v2.4.58 恢复：增强磨砂与高斯的对比）
        // 磨砂需要 12% 白底来"实现质感"，高斯 18% 会更清爽
        drawRect(Color.White.copy(alpha = 0.12f))
        // 细颗粒：平铺噪点（v2.4.49：颗粒 alpha 0.55 → 0.35；v2.4.58：强化到 0.42）
        // 颗粒更明显，磨砂感更突出
        drawRect(
            brush = ShaderBrush(
                ImageShader(FrostedGrain.bitmap, TileMode.Repeated, TileMode.Repeated)
            ),
            alpha = 0.42f,
        )
    }

    return this
        .drawBackdrop(
            backdrop = backdrop,
            shape = shape,
            onDrawSurface = frostedSurface,
            // v2.4.57：去掉模糊层——无任何 RenderEffect 特效，背景清晰透出
            effects = {},
        )
        .border(
            width = 1.dp,
            // v2.4.58：边框调整为 0.16（比高斯的 0.35 更暗），强化"质感 vs 清爽"的对比
            color = Color.White.copy(alpha = 0.16f),
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

