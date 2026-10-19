package com.example.vr

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.R
import kotlin.math.roundToInt

/**
 * v2.6.4：**播放控制球**。
 *
 * 为什么单独做一个球，而不是复用播放条上的按钮：
 * 戴着头显时去摸屏幕上的播放条很别扭（要先摘眼镜或靠手柄瞄准），
 * 而「播放/暂停」是观看过程中最高频的操作。把它做成可拖到顺手位置的悬浮球，
 * 一抬手就能点到。
 *
 * 交互：
 *  - **单击** = 播放 / 暂停切换（球面图标随之切换 ▶ / ❚❚）；
 *  - **长按** = 播报当前状态（见 [onFeedback]，HUD 文案由调用方提供）。
 *
 * 位置、手势、玻璃材质与 `InfoFloatingBall` / `SeekFloatingBall` 完全同一套：
 * `floatingBallGesture` + `FloatingBallPositions`（存 0~1 比例）+ `glassModifier`。
 *
 * ⚠️ 本组件**不持有**播放状态：`isPlaying` 由调用方从播放器读取后传入，
 * 图标只是状态的镜像。这样播放器状态变化（自动暂停、播完、外部控制）都能反映到球面上。
 */
@Composable
fun ControlFloatingBall(
    isPlaying: Boolean,
    maxX: Float,
    maxY: Float,
    accentColor: Color,
    accentOnColor: Color,
    onTogglePlayPause: () -> Unit,
    onFeedback: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialXRatio: Float = 1f,
    initialYRatio: Float = 0.42f,
    isLiquidGlass: Boolean = false,
    glassModifier: Modifier = Modifier,
    prefs: SharedPreferences? = null,
    ballId: String = "",
    rememberPosition: Boolean = false,
) {
    val persistPosition = rememberPosition && prefs != null && ballId.isNotEmpty()
    var pressed by remember { mutableStateOf(false) }

    // 位置用比例持有（与 InfoFloatingBall 一致，理由见那边的长注释）
    var xRatio by remember { mutableFloatStateOf(Float.NaN) }
    var yRatio by remember { mutableFloatStateOf(Float.NaN) }

    LaunchedEffect(maxX, maxY) {
        if (xRatio.isNaN() && maxX > 0f && maxY > 0f) {
            val sx = if (persistPosition) FloatingBallPositions.loadX(prefs!!, ballId) else Float.NaN
            val sy = if (persistPosition) FloatingBallPositions.loadY(prefs!!, ballId) else Float.NaN
            xRatio = (if (!sx.isNaN()) sx else initialXRatio).coerceIn(0f, 1f)
            yRatio = (if (!sy.isNaN()) sy else initialYRatio).coerceIn(0f, 1f)
        }
    }

    // HUD 文案在语句位置取好 —— @Composable 调用不能塞进手势 lambda
    val hudPlaying = stringResource(R.string.control_ball_hud_playing)
    val hudPaused = stringResource(R.string.control_ball_hud_paused)

    val ballDp = 54.dp
    val icon: ImageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow

    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    x = InfoBallGeometry.pixel(xRatio, maxX).roundToInt(),
                    y = InfoBallGeometry.pixel(yRatio, maxY).roundToInt(),
                )
            }
            .size(ballDp)
            .shadow(
                elevation = if (pressed) 12.dp else 6.dp,
                shape = CircleShape,
                spotColor = if (pressed) accentColor else Color.Black,
            )
            .background(
                brush = if (isLiquidGlass) {
                    Brush.radialGradient(colors = listOf(Color.Transparent, Color.Transparent))
                } else if (pressed) {
                    Brush.radialGradient(colors = listOf(accentColor, Color(0xFF9A82DB)))
                } else {
                    Brush.radialGradient(colors = listOf(Color(0xEE2A2733), Color(0xDD18171C)))
                },
                shape = CircleShape,
            )
            .then(glassModifier)
            .border(
                width = if (pressed) 2.dp else 1.5.dp,
                brush = if (pressed) {
                    SolidColor(Color.White)
                } else {
                    Brush.linearGradient(colors = listOf(Color(0x99D0BCFF), Color(0x33FFFFFF)))
                },
                shape = CircleShape,
            )
            .floatingBallGesture(
                maxX = maxX,
                maxY = maxY,
                offsetX = { InfoBallGeometry.pixel(xRatio, maxX) },
                offsetY = { InfoBallGeometry.pixel(yRatio, maxY) },
                onOffset = { x, y ->
                    if (maxX > 0f) xRatio = InfoBallGeometry.ratio(x, maxX)
                    if (maxY > 0f) yRatio = InfoBallGeometry.ratio(y, maxY)
                },
                onPressed = { pressed = it },
                onDragEnd = {
                    if (persistPosition && !xRatio.isNaN() && !yRatio.isNaN()) {
                        FloatingBallPositions.save(prefs!!, ballId, xRatio, yRatio)
                    }
                },
                onSingleTap = {
                    onTogglePlayPause()
                    onFeedback(if (isPlaying) hudPlaying else hudPaused)
                },
                // 双击 = 播报当前状态（不改变播放，避免误触把正在看的片子暂停）
                onDoubleTap = { onFeedback(if (isPlaying) hudPlaying else hudPaused) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isPlaying) accentOnColor else Color.White,
            modifier = Modifier.size(if (isPlaying) 26.dp else 28.dp)
        )
    }
}
