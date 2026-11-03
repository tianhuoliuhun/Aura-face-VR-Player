package com.example.vr

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/**
 * v2.6.20（P2，用户选定）：**Liquid Merge** —— 两颗液态球靠近时的"拉丝融合"。
 *
 * ## 实现口径（v1 视觉近似，非库级 metaball）
 * iOS 26 的 Liquid Merge 是"两个液滴合并成一个新液滴"，折射跟着合并后的形状走。
 * 本项目的折射来自 backdrop 库的 `lens`（SDF 驱动、绑定在每个控件自己的 shape 上），
 * 两颗球是两个独立 Composable，**没有共享图层**，做"真合并"需要把悬浮球重构成
 * 单一液滴容器 —— 架构级改动。
 *
 * v1 的落点：一颗**融合膜**画在所有球之下（[LiquidMergeOverlay]），两球中心距小于
 * 阈值时渐显：形状是"颈部收窄的拉丝"（两条二次贝塞尔），填充极淡白 + 描边白线，
 * 与球本体 0.8dp 渐变白边同一种材质语言。距离越近膜越宽越亮，拉开即消失。
 * 连接膜内部没有折射（它不采样 backdrop）—— 2dp 环带尺度下不可感知。
 *
 * ## 数据流
 * 每颗液态球通过 [Modifier.liquidPressFx] 顺带注册 [LiquidBallNode]
 * （中心点 = `positionInWindow` + 半径），拖动时逐帧更新；
 * overlay 相对自身原点绘制（`positionInWindow` 统一坐标系）。
 * 节点数 ≤ 6，两两配对最多 15 对，每对一条贝塞尔路径 —— 绘制成本可忽略。
 */
object LiquidMergeBus {

    /** 当前所有液态球的节点（快照 state：增删会触发 overlay 重绘） */
    val nodes = mutableStateListOf<LiquidBallNode>()

    fun register(node: LiquidBallNode) {
        if (!nodes.contains(node)) nodes.add(node)
    }

    fun unregister(node: LiquidBallNode) {
        nodes.remove(node)
    }
}

/** 一颗液态球在 window 坐标系里的实时位置与半径（px）。 */
class LiquidBallNode {
    var center by mutableStateOf(Offset.Zero)
    var radiusPx by mutableFloatStateOf(0f)
}

/**
 * 全屏融合膜绘制层。放在**所有悬浮球之下、视频画面之上**的层级；
 * 不消费任何触摸（Canvas 只绘制），球的手势不受影响。
 */
@Composable
fun LiquidMergeOverlay(modifier: Modifier = Modifier) {
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    Canvas(modifier.onGloballyPositioned { overlayOrigin = it.positionInWindow() }) {
        val nodes = LiquidMergeBus.nodes
        if (nodes.size < 2) return@Canvas
        for (i in nodes.indices) {
            for (j in i + 1 until nodes.size) {
                val a = nodes[i]
                val b = nodes[j]
                if (a.radiusPx <= 0f || b.radiusPx <= 0f) continue
                val ca = a.center - overlayOrigin
                val cb = b.center - overlayOrigin
                val d = hypot(cb.x - ca.x, cb.y - ca.y)
                val rSum = a.radiusPx + b.radiusPx
                // 融合强度：d = 2.6×rSum（远离）→ 0；d = 1.4×rSum（几乎贴合）→ 1
                val t = ((2.6f * rSum - d) / (1.2f * rSum)).coerceIn(0f, 1f)
                if (t <= 0f) continue
                drawMergeStrand(ca, a.radiusPx, cb, b.radiusPx, t)
            }
        }
    }
}

/**
 * 画两球之间的拉丝融合膜：
 * 两条二次贝塞尔（沿连线法线），颈部宽度随融合强度 [t] 收放 ——
 * 刚靠近时是细丝，贴近时鼓成几乎连成一体的"膜"。
 * 两端插进球体半径的 95% 处，由球本体的玻璃圆面盖住接缝。
 */
private fun DrawScope.drawMergeStrand(a: Offset, ra: Float, b: Offset, rb: Float, t: Float) {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val d = hypot(dx, dy)
    if (d < 1f) return
    val dirX = dx / d
    val dirY = dy / d
    val nx = -dirY
    val ny = dirX
    val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    // 颈部半宽：t=0 → 0（细丝消失），t=1 → 平均半径的 85%（几乎连成一体）
    val neck = (ra + rb) / 2f * 0.85f * t
    val inset = 0.95f // 两端插进球体，接缝被玻璃圆面盖住
    val p1 = Offset(a.x + nx * ra * inset, a.y + ny * ra * inset)
    val p3 = Offset(b.x + nx * rb * inset, b.y + ny * rb * inset)
    val c1 = Offset(mid.x + nx * neck, mid.y + ny * neck)
    val p2 = Offset(a.x - nx * ra * inset, a.y - ny * ra * inset)
    val p4 = Offset(b.x - nx * rb * inset, b.y - ny * rb * inset)
    val c2 = Offset(mid.x - nx * neck, mid.y - ny * neck)

    val path = Path().apply {
        moveTo(p1.x, p1.y)
        quadraticBezierTo(c1.x, c1.y, p3.x, p3.y)
        lineTo(p4.x, p4.y)
        quadraticBezierTo(c2.x, c2.y, p2.x, p2.y)
        close()
    }
    // 填充：极淡白（融合越强越明显），与"玻璃本体无色"的材质语言一致
    drawPath(path, Color.White.copy(alpha = 0.04f + 0.10f * t))
    // 描边：与球本体 0.8dp 渐变白边同族的轮廓光
    val rimAlpha = 0.10f + 0.28f * t
    drawPath(path, Color.White.copy(alpha = rimAlpha), style = Stroke(width = 1.dp.toPx()))
}
