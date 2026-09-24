package com.example.vr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** v2.0.165：可循环的步长档位（秒）—— 双击在 5 → 10 → 15 → 30 → 5 之间循环 */
val SEEK_STEP_OPTIONS = intArrayOf(5, 10, 15, 30)

/** 把当前步长推进到下一个档位（回到 5 重新开始） */
fun nextSeekStep(current: Int): Int {
    val idx = SEEK_STEP_OPTIONS.indexOf(current)
    return SEEK_STEP_OPTIONS[(idx + 1) % SEEK_STEP_OPTIONS.size].let {
        if (idx < 0) SEEK_STEP_OPTIONS[0] else it
    }
}

/**
 * v2.0.165：快进 / 后退悬浮球。
 *
 * 与「加速悬浮球」的区别 —— 加速球是 **按住生效**（松手恢复倍速），本球是
 * **单击 / 双击 / 长按拖动** 三态：
 *
 * | 手势 | 行为 |
 * |---|---|
 * | 单击 | 执行 seek（±当前步长），有 [DOUBLE_TAP_WINDOW_MS] 判定窗口用于与双击区分 |
 * | 双击 | 步长按 5 → 10 → 15 → 30 → 5 循环切换（不执行 seek） |
 * | 长按 | 500ms（系统 longPressTimeout）后进入**拖动模式**，此时抬手**不会**触发单击 |
 * | 拖动 | 位置限制在 `[0, maxX] × [0, maxY]`（与加速球同一套边界，由父级按容器尺寸传入） |
 *
 * 位置状态由组件**内部持有**（避免把 State 值捕获进 pointerInput 闭包导致拖动漂移），
 * 首次布局时按 [initialYRatio] 落在右侧边缘。
 *
 * @param forward true = 快进球，false = 后退球（只影响图标与语义，交互一致）
 * @param glassModifier 玻璃主题下的 `Modifier.drawBackdrop(...)`（父级构建后传入，保持与加速球一致）
 */
@Composable
fun SeekFloatingBall(
    forward: Boolean,
    stepSeconds: Int,
    maxX: Float,
    maxY: Float,
    accentColor: Color,
    accentOnColor: Color,
    onStepCycle: () -> Unit,
    onTrigger: (Int) -> Unit,
    onFeedback: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialYRatio: Float = 0.5f,
    /** 玻璃主题（glassMode=1 且 Android 12+）—— 底色需换成半透明白，与加速球保持视觉一致 */
    isLiquidGlass: Boolean = false,
    glassModifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val viewConfig = LocalViewConfiguration.current
    val longPressMs = viewConfig.longPressTimeoutMillis
    val touchSlop = viewConfig.touchSlop
    // ⚠️ LocalDensity 是 @Composable 读取，必须在语句位置取好，不能在 LaunchedEffect 的 lambda 里读
    val density = androidx.compose.ui.platform.LocalDensity.current

    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var positionInitialized by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var lastTapAt by remember { mutableLongStateOf(0L) }

    // 首次布局：贴右侧边缘，纵向按 initialYRatio 错开（快进 / 后退 / 加速三个球互不重叠）
    LaunchedEffect(maxX, maxY) {
        if (!positionInitialized && maxX > 0f && maxY > 0f) {
            offsetX = maxX - with(density) { 20.dp.toPx() }
            offsetY = (maxY * initialYRatio).coerceIn(0f, maxY)
            positionInitialized = true
        }
    }

    val ballDp = 54.dp
    val icon = if (forward) Icons.Default.FastForward else Icons.Default.FastRewind

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
                // v2.0.165 修正：玻璃主题下底色必须是**半透明白**（与加速球完全一致）——
                // 之前漏了这一支，导致玻璃模式下小球是深色底，观感与倍速球对不上
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
                    Brush.linearGradient(colors = listOf(Color(0x99D0BCFF), Color(0x33FFFFFF)))
                },
                shape = CircleShape
            )
            .pointerInput(stepSeconds) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downAt = System.currentTimeMillis()
                    var dragging = false
                    pressed = true

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) break

                        val drag = change.positionChange()
                        val elapsed = System.currentTimeMillis() - downAt
                        // 移动超过 slop 或按住超过长按阈值 → 进入拖动模式（长按后抬手不再算单击）
                        if (!dragging && (drag.getDistance() > touchSlop || elapsed >= longPressMs)) {
                            dragging = true
                        }
                        if (dragging && drag != Offset.Zero) {
                            offsetX = (offsetX + drag.x).coerceIn(0f, maxX.coerceAtLeast(0f))
                            offsetY = (offsetY + drag.y).coerceIn(0f, maxY.coerceAtLeast(0f))
                            change.consume()
                        }
                    }
                    pressed = false

                    // 抬手：未进入拖动才判定单击 / 双击（长按拖动不会触发单击）
                    if (!dragging) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapAt <= DOUBLE_TAP_WINDOW_MS) {
                            lastTapAt = 0L
                            onStepCycle()
                        } else {
                            lastTapAt = now
                            val tappedAt = now
                            scope.launch {
                                delay(DOUBLE_TAP_WINDOW_MS)
                                if (lastTapAt == tappedAt) {
                                    lastTapAt = 0L
                                    onTrigger(stepSeconds)
                                }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (pressed) accentOnColor else accentColor,
                modifier = Modifier.size(if (pressed) 20.dp else 16.dp)
            )
            Text(
                text = "${stepSeconds}s",
                color = if (pressed) accentOnColor else Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** 双击判定窗口（单击需等这个窗口结束才执行，以便与双击区分） */
const val DOUBLE_TAP_WINDOW_MS = 280L
