package com.example.vr

import com.example.R
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 加速悬浮球与长按快进提示 HUD。
 *
 * 交互说明：
 * - 长按/按住：临时切为 [floatingBallSpeed] 倍速，松开恢复 [basePlaybackSpeed]
 * - 双击：在 1.5X / 2.0X / 3.0X 循环切换加速档位
 * - 拖动：位置限制在 [0, maxX] x [0, maxY]，拖动结束自动按比例存档
 */
@Composable
fun BoxScope.SpeedFloatingBall(
    floatingBallSpeed: Float,
    basePlaybackSpeed: Float,
    isPressed: Boolean,
    onPressedChange: (Boolean) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onFeedback: (String) -> Unit,
    maxX: Float,
    maxY: Float,
    accentColor: Color,
    accentOnColor: Color,
    isLiquidGlass: Boolean,
    ballMaterialModifier: Modifier = Modifier,
    prefs: SharedPreferences? = null,
    rememberPosition: Boolean = false,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val ballDp = 54.dp

    var ballOffsetX by remember { mutableFloatStateOf(0f) }
    var ballOffsetY by remember { mutableFloatStateOf(0f) }
    var isBallPositionInitialized by remember { mutableStateOf(false) }
    var showSpeedHud by remember { mutableStateOf(false) }

    val persistPosition = rememberPosition && prefs != null

    LaunchedEffect(maxX, maxY) {
        if (!isBallPositionInitialized && maxX > 0f && maxY > 0f) {
            val sx = if (persistPosition) FloatingBallPositions.loadX(prefs!!, FloatingBallPositions.SPEED) else Float.NaN
            val sy = if (persistPosition) FloatingBallPositions.loadY(prefs!!, FloatingBallPositions.SPEED) else Float.NaN
            if (!sx.isNaN() && !sy.isNaN()) {
                ballOffsetX = (sx * maxX).coerceIn(0f, maxX)
                ballOffsetY = (sy * maxY).coerceIn(0f, maxY)
            } else {
                ballOffsetX = maxX - with(density) { 20.dp.toPx() }
                ballOffsetY = maxY / 2f
            }
            isBallPositionInitialized = true
        }
    }

    val speedText = if (floatingBallSpeed == floatingBallSpeed.toInt().toFloat()) {
        "${floatingBallSpeed.toInt()}X"
    } else {
        "${floatingBallSpeed}X"
    }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = ballOffsetX.coerceIn(0f, maxX.coerceAtLeast(0f)).roundToInt(),
                    y = ballOffsetY.coerceIn(0f, maxY.coerceAtLeast(0f)).roundToInt()
                )
            }
            .size(ballDp)
            .liquidPressFx(isLiquidGlass) { isPressed }
            .ballShadow(
                isLiquid = isLiquidGlass,
                elevation = if (isPressed) 12.dp else 6.dp,
                spotColor = if (isPressed) accentColor else Color.Black
            )
            .background(
                brush = if (isLiquidGlass) {
                    Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color.Transparent)
                    )
                } else if (isPressed) {
                    Brush.radialGradient(
                        colors = listOf(accentColor, Color(0xFF9A82DB))
                    )
                } else {
                    Brush.radialGradient(
                        colors = listOf(Color(0xEE2A2733), Color(0xDD18171C))
                    )
                },
                shape = CircleShape
            )
            .then(ballMaterialModifier)
            .border(
                width = if (isPressed) 2.dp else 1.5.dp,
                brush = if (isPressed) {
                    SolidColor(Color.White)
                } else {
                    Brush.linearGradient(
                        colors = listOf(Color(0x99D0BCFF), Color(0x33FFFFFF))
                    )
                },
                shape = CircleShape
            )
            .pointerInput(floatingBallSpeed, basePlaybackSpeed) {
                val touchSlop = viewConfiguration.touchSlop
                var lastTapUpTime = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onPressedChange(true)
                    showSpeedHud = true

                    val pointer = down.id
                    var dragDetected = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointer }
                        if (change == null || !change.pressed) {
                            onPressedChange(false)
                            showSpeedHud = false
                            break
                        }
                        val dragAmount = change.positionChange()
                        if (!dragDetected && dragAmount.getDistance() > touchSlop) {
                            dragDetected = true
                        }
                        if (dragDetected && dragAmount != Offset.Zero) {
                            ballOffsetX = (ballOffsetX + dragAmount.x).coerceIn(0f, maxX.coerceAtLeast(0f))
                            ballOffsetY = (ballOffsetY + dragAmount.y).coerceIn(0f, maxY.coerceAtLeast(0f))
                            change.consume()
                        }
                    }

                    if (!dragDetected) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapUpTime <= DOUBLE_TAP_WINDOW_MS) {
                            lastTapUpTime = 0L
                            val nextSpeedVal = nextBallSpeed(floatingBallSpeed)
                            onSpeedChange(nextSpeedVal)
                            val nextSpeedLabel = if (nextSpeedVal == nextSpeedVal.toInt().toFloat()) {
                                "${nextSpeedVal.toInt()}X"
                            } else {
                                "${nextSpeedVal}X"
                            }
                            onFeedback(context.getString(R.string.ball_speed_switched, nextSpeedLabel))
                        } else {
                            lastTapUpTime = now
                        }
                    } else {
                        lastTapUpTime = 0L
                        if (persistPosition && maxX > 0f && maxY > 0f) {
                            FloatingBallPositions.save(
                                prefs!!,
                                FloatingBallPositions.SPEED,
                                ballOffsetX / maxX,
                                ballOffsetY / maxY
                            )
                        }
                    }
                }
            }
            .testTag("floating_speed_ball"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.FastForward,
                contentDescription = stringResource(R.string.speed_control),
                tint = if (isPressed) accentOnColor else accentColor,
                modifier = Modifier.size(if (isPressed) 20.dp else 16.dp)
            )
            Text(
                text = speedText,
                color = if (isPressed) accentOnColor else Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    LaunchedEffect(showSpeedHud) {
        if (showSpeedHud) {
            delay(1000L)
            showSpeedHud = false
        }
    }

    AnimatedVisibility(
        visible = showSpeedHud,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 28.dp)
            .testTag("speed_fast_forward_hud")
    ) {
        Surface(
            color = Color(0xEE18171C),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.6f)),
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FastForward,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.fast_forwarding, speedText),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
