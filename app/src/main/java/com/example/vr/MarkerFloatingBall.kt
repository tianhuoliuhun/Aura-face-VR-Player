package com.example.vr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * **时间标记球**：保存一个时间点，实时显示「当前播放位置 − 标记点」的差值。
 *
 * | 手势 | 行为 |
 * |---|---|
 * | 单击 | 跳转到标记时间点 |
 * | 双击 | 把标记时间更新为当前播放时间 |
 * | 拖动 | 移动位置（限制在传入的 `[0,maxX]×[0,maxY]` 内），拖动后抬手不触发点击 |
 *
 * 手势实现复用 [floatingBallGesture]（与「快进/后退球」同一套：拖动钳制、
 * 单击延迟判定、拖动抑制），只在语义层做区分。
 *
 * ## 差值显示规则
 * - `|当前 − 标记| ≤ [thresholdSec]` → 显示 **0**（淡化），避免播放位置每秒抖动导致数字乱跳
 * - 大于阈值 → **+12s**（标记在后方）/ **−5s**（标记在前方）
 *
 * ## 状态如何跟随播放进度实时同步
 * 本组件**不自己轮询播放位置**，而是把 [currentPositionMs] 当作受控参数接收：
 *
 * ```
 * 播放器 while(true){ currentPositionMs = player.currentPosition }   ← 上游 LaunchedEffect
 *                              │ 写入 Compose State → 触发重组
 *                              ▼
 *                       deltaSec 重算 → 球内文本刷新
 * ```
 *
 * 因为 `deltaSec` 是在 Composable 作用域内由这个 state 推导出来的，
 * 所以**无需任何额外定时器**，播放位置一变球上数字就跟着变，天然同步；
 * 暂停时上游不刷新 → 数字不动，也正是期望行为。
 *
 * [markerMs]（标记点）由组件内部 `remember` 持有，跨重组保留；
 * 双击时写入当前播放位置。
 *
 * @param initialXRatio 初始横向位置比例：0 = 贴左边缘（默认，与右侧的快进/后退球分开）
 * @param initialYRatio 初始纵向位置比例
 */
@Composable
fun MarkerFloatingBall(
    /** 当前播放位置（毫秒），由上游播放器循环写入的 state */
    currentPositionMs: Long,
    /** 可移动范围（像素）：容器宽/高 − 球直径 */
    maxX: Float,
    maxY: Float,
    /** 单击时请求跳转到该时间点 */
    onSeekTo: (Long) -> Unit,
    accentColor: Color,
    accentOnColor: Color,
    /** 反馈文案（如"已标记 12:34"） */
    onFeedback: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** 视为「一致」的阈值（秒） */
    thresholdSec: Int = 1,
    initialXRatio: Float = 0f,
    initialYRatio: Float = 0.5f,
    isLiquidGlass: Boolean = false,
    glassModifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    // —— 标记点：组件内部持有，跨重组保留 ——
    var markerMs by remember { mutableLongStateOf(0L) }

    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var positionInitialized by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }

    // 首次布局：贴左边缘（与右侧的快进/后退球错开），纵向按 initialYRatio
    LaunchedEffect(maxX, maxY) {
        if (!positionInitialized && maxX > 0f && maxY > 0f) {
            offsetX = (maxX * initialXRatio).coerceIn(0f, maxX)
            offsetY = (maxY * initialYRatio).coerceIn(0f, maxY)
            positionInitialized = true
        }
    }

    // —— 差值：直接由受控参数推导，上游每写一次 currentPositionMs 就重算一次 ——
    val deltaSec = ((currentPositionMs - markerMs) / 1000.0).roundToInt()
    val aligned = abs(deltaSec) <= thresholdSec

    val ballDp = 54.dp

    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    x = offsetX.coerceIn(0f, maxX.coerceAtLeast(0f)).roundToInt(),
                    y = offsetY.coerceIn(0f, maxY.coerceAtLeast(0f)).roundToInt()
                )
            }
            .size(ballDp)
            .shadow(
                elevation = if (pressed) 12.dp else 6.dp,
                shape = CircleShape,
                spotColor = if (pressed) accentColor else Color.Black
            )
            .background(
                brush = if (isLiquidGlass) {
                    Brush.radialGradient(colors = listOf(Color(0x66FFFFFF), Color(0x33FFFFFF)))
                } else if (pressed) {
                    Brush.radialGradient(colors = listOf(accentColor, Color(0xFF9A82DB)))
                } else {
                    Brush.radialGradient(colors = listOf(Color(0xEE2A2733), Color(0xDD18171C)))
                },
                shape = CircleShape
            )
            .then(glassModifier)
            .border(
                width = if (pressed) 2.dp else 1.5.dp,
                brush = if (pressed) {
                    SolidColor(Color.White)
                } else {
                    Brush.linearGradient(colors = listOf(Color(0x99D0B0FF), Color(0x33FFFFFF)))
                },
                shape = CircleShape
            )
            .floatingBallGesture(
                maxX = maxX,
                maxY = maxY,
                offsetX = { offsetX },
                offsetY = { offsetY },
                onOffset = { x, y -> offsetX = x; offsetY = y },
                onPressed = { pressed = it },
                onSingleTap = {
                    // 单击：跳回标记点
                    onSeekTo(markerMs)
                    onFeedback("⏱ 回到标记 " + formatMarkerTime(markerMs))
                },
                onDoubleTap = {
                    // 双击：把标记更新为当前播放位置
                    markerMs = currentPositionMs
                    onFeedback("📍 已标记 " + formatMarkerTime(currentPositionMs))
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 差值（核心信息）：一致显示 0 并淡化，否则带符号显示秒数
            Text(
                text = when {
                    aligned -> "0"
                    deltaSec > 0 -> "+${deltaSec}s"
                    else -> "${deltaSec}s"
                },
                color = when {
                    pressed -> accentOnColor
                    aligned -> Color.White.copy(alpha = 0.55f)
                    else -> accentColor
                },
                fontSize = if (deltaSec >= 100 || deltaSec <= -100) 10.sp else 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            // 标记时间：让用户知道"点一下会跳去哪"
            Text(
                text = formatMarkerTime(markerMs),
                color = if (pressed) accentOnColor.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.55f),
                fontSize = 8.sp,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

/** 毫秒 → mm:ss（超过 1 小时给 h:mm:ss）。与 seek HUD 的格式保持一致。 */
internal fun formatMarkerTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
