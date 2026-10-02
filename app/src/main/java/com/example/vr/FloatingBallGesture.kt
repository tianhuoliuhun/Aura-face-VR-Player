package com.example.vr

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 双击判定窗口（单击需等这个窗口结束才执行，以便与双击区分） */
const val DOUBLE_TAP_WINDOW_MS = 280L

/**
 * 悬浮球**公共手势**：拖动（带边界钳制）+ 单击 / 双击（带延迟判定 + 拖动抑制）。
 *
 * 2026-10-02 从 `SeekFloatingBall` 抽取，供「快进/后退球」与「时间标记球」共用 ——
 * 以后调整手感（判定窗口、长按阈值、拖动抑制规则）只需改这一处。
 *
 * ## 行为约定
 * | 情况 | 结果 |
 * |---|---|
 * | 位移超过 `touchSlop`，或按住超过系统 `longPressTimeout` | 进入**拖动**模式，位置被钳制在 `[0,maxX]×[0,maxY]` |
 * | 拖动过程中 | `change.consume()` 消费事件 → 不会触发单击 |
 * | 拖动结束抬手 | **不触发**单击/双击 |
 * | 未拖动的抬手，距上次抬手 ≤ [DOUBLE_TAP_WINDOW_MS] | 触发 [onDoubleTap] |
 * | 未拖动的抬手，之后 [DOUBLE_TAP_WINDOW_MS] 内没有第二次抬手 | 触发 [onSingleTap] |
 *
 * ⚠️ 单击采用「延迟触发」而不是「先触发再撤回」：两者在手感上都对，但延迟触发
 *    不会出现"已经 seek 了又被双击取消"的抖动。代价是单击响应有约 280ms 的固有延迟，
 *    这是与双击共存的必要成本。
 *
 * ⚠️ offset 由**调用方持有 state**（通过 [offsetX]/[offsetY] 读取、[onOffset] 写回），
 *    而不是由本函数内部持有 —— 这样组件被移出重组时位置不会莫名回到原点，
 *    也便于调用方按需读取/持久化位置。
 *
 * @param maxX 可移动范围（像素），通常 = 容器宽 − 球直径
 * @param maxY 可移动范围（像素），通常 = 容器高 − 球直径
 * @param offsetX 读取当前 X 偏移（lambda 而非值，避免把旧值捕获进 pointerInput 闭包导致拖动漂移）
 * @param offsetY 读取当前 Y 偏移
 * @param onOffset 写回新偏移（已钳制）
 * @param onPressed 按下状态变化（用于换高亮底色/阴影）
 */
@Composable
fun Modifier.floatingBallGesture(
    maxX: Float,
    maxY: Float,
    offsetX: () -> Float,
    offsetY: () -> Float,
    onOffset: (Float, Float) -> Unit,
    onSingleTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onPressed: (Boolean) -> Unit = {},
    /** 手势协程的 key：把会影响手势判定的参数放进来，变化时重建手势 */
    gestureKey: Any? = Unit
): Modifier {
    val viewConfig = LocalViewConfiguration.current
    val touchSlop = viewConfig.touchSlop
    val longPressMs = viewConfig.longPressTimeoutMillis
    val scope = rememberCoroutineScope()

    // ⚠️⚠️ 关键：`pointerInput(gestureKey)` 的协程**只在 key 变化时重建**，
    //   闭包会一直捕获**首次组合那一刻**的参数与回调。
    //   这直接导致了「双击后标记时间没更新」——因为 onDoubleTap 里读的
    //   `currentPositionMs` 还是进入播放页时的旧值（通常是 0）。
    //
    //   修法：用 rememberUpdatedState 把「总是取最新值」的引用交给手势闭包。
    //   注意**不能**把 currentPositionMs 放进 gestureKey —— 那样播放进度每秒变化都会
    //   重建手势协程，正在进行的拖动会被打断。
    val latestSingleTap by rememberUpdatedState(onSingleTap)
    val latestDoubleTap by rememberUpdatedState(onDoubleTap)
    val latestOnOffset by rememberUpdatedState(onOffset)
    val latestOffsetX by rememberUpdatedState(offsetX)
    val latestOffsetY by rememberUpdatedState(offsetY)
    val latestMaxX by rememberUpdatedState(maxX)
    val latestMaxY by rememberUpdatedState(maxY)
    val latestOnPressed by rememberUpdatedState(onPressed)
    // 上次抬手时间（ms）：0 表示「当前没有待判定的单击」
    var lastTapMs by remember { mutableLongStateOf(0L) }

    return this.pointerInput(gestureKey) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val downAt = System.currentTimeMillis()
            var dragging = false
            latestOnPressed(true)

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) break

                val drag = change.positionChange()
                val elapsed = System.currentTimeMillis() - downAt
                // 位移超 slop 或按住超长按阈值 → 进入拖动（此后抬手不再算点击）
                if (!dragging && (drag.getDistance() > touchSlop || elapsed >= longPressMs)) {
                    dragging = true
                }
                if (dragging && drag != Offset.Zero) {
                    latestOnOffset(
                        (latestOffsetX() + drag.x).coerceIn(0f, latestMaxX.coerceAtLeast(0f)),
                        (latestOffsetY() + drag.y).coerceIn(0f, latestMaxY.coerceAtLeast(0f))
                    )
                    change.consume()
                }
            }
            latestOnPressed(false)

            // 拖动结束不判定点击
            if (!dragging) {
                val now = System.currentTimeMillis()
                if (now - lastTapMs <= DOUBLE_TAP_WINDOW_MS) {
                    // 与上一次抬手构成双击 → 取消掉那个待判定的单击
                    lastTapMs = 0L
                    latestDoubleTap()
                } else {
                    lastTapMs = now
                    val tappedAt = now
                    scope.launch {
                        delay(DOUBLE_TAP_WINDOW_MS)
                        // 窗口内没有第二次抬手 → 确认是单击
                        if (lastTapMs == tappedAt) {
                            lastTapMs = 0L
                            latestSingleTap()
                        }
                    }
                }
            }
        }
    }
}
