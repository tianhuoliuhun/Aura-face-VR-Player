package com.example.vr

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import kotlinx.coroutines.delay

/**
 * 播放器全部悬浮球组（倍速 / 时间标记 / 时钟 / 电量 / 播放 / 性能 / 快进 / 快退）及顶部 HUD 提示条，
 * 从 VRPlayerScreen.kt 拆出以精简主视图树。
 */
@Composable
internal fun BoxScope.PlayerFloatingBallsOverlay(
    context: Context,
    prefs: SharedPreferences,
    maxWidthPx: Float,
    maxHeightPx: Float,
    ballsAllowed: Boolean,
    isMemoryModeEnabled: Boolean,
    isLiquidGlass: Boolean,
    ballMaterialModifier: Modifier,
    accentColor: Color,
    accentOnColor: Color,
    playerInstance: VrPlayerBackend?,
    isLiveStream: Boolean,
    currentPositionMs: Long,
    isFloatingBallEnabled: Boolean,
    floatingBallSpeed: Float,
    basePlaybackSpeed: Float,
    isFloatingBallPressed: Boolean,
    onFloatingBallPressedChange: (Boolean) -> Unit,
    onFloatingBallSpeedChange: (Float) -> Unit,
    isMarkerBallEnabled: Boolean,
    isClockBallEnabled: Boolean,
    isBatteryBallEnabled: Boolean,
    isPlayBallEnabled: Boolean,
    isPerfBallEnabled: Boolean,
    isSeekForwardBallEnabled: Boolean,
    seekForwardStep: Int,
    onSeekForwardStepChange: (Int) -> Unit,
    isSeekBackwardBallEnabled: Boolean,
    seekBackwardStep: Int,
    onSeekBackwardStepChange: (Int) -> Unit,
    seekHudText: String?,
    onSeekHudTextChange: (String?) -> Unit,
) {
    val ballPx = with(LocalDensity.current) { 54.dp.toPx() }
    val seekMaxX = maxWidthPx - ballPx
    val seekMaxY = maxHeightPx - ballPx

    if (isFloatingBallEnabled && ballsAllowed) {
        SpeedFloatingBall(
            floatingBallSpeed = floatingBallSpeed,
            basePlaybackSpeed = basePlaybackSpeed,
            isPressed = isFloatingBallPressed,
            onPressedChange = onFloatingBallPressedChange,
            onSpeedChange = onFloatingBallSpeedChange,
            onFeedback = onSeekHudTextChange,
            maxX = seekMaxX,
            maxY = seekMaxY,
            accentColor = accentColor,
            accentOnColor = accentOnColor,
            isLiquidGlass = isLiquidGlass,
            ballMaterialModifier = ballMaterialModifier,
            prefs = prefs,
            rememberPosition = isMemoryModeEnabled
        )
    }

    val fwdHudLabel = stringResource(R.string.seek_forward_hud, seekForwardStep)
    val bwdHudLabel = stringResource(R.string.seek_backward_hud, seekBackwardStep)
    val fwdStepSwitched = stringResource(R.string.seek_step_switched, nextSeekStep(seekForwardStep))
    val bwdStepSwitched = stringResource(R.string.seek_step_switched, nextSeekStep(seekBackwardStep))

    if (isMarkerBallEnabled && ballsAllowed) {
        MarkerFloatingBall(
            currentPositionMs = currentPositionMs,
            maxX = seekMaxX,
            maxY = seekMaxY,
            onSeekTo = { ms -> playerInstance?.seekTo(ms) },
            accentColor = accentColor,
            accentOnColor = accentOnColor,
            onFeedback = onSeekHudTextChange,
            initialXRatio = 0f,
            initialYRatio = 0.5f,
            isLiquidGlass = isLiquidGlass,
            glassModifier = ballMaterialModifier,
            prefs = prefs,
            rememberPosition = isMemoryModeEnabled
        )
    }

    if (isClockBallEnabled && ballsAllowed) {
        InfoFloatingBall(
            kind = InfoBallKind.CLOCK,
            maxX = seekMaxX,
            maxY = seekMaxY,
            accentColor = accentColor,
            accentOnColor = accentOnColor,
            onFeedback = onSeekHudTextChange,
            initialXRatio = 0f,
            initialYRatio = 0.10f,
            isLiquidGlass = isLiquidGlass,
            glassModifier = ballMaterialModifier,
            prefs = prefs,
            ballId = FloatingBallPositions.CLOCK,
            rememberPosition = isMemoryModeEnabled
        )
    }

    if (isBatteryBallEnabled && ballsAllowed) {
        InfoFloatingBall(
            kind = InfoBallKind.BATTERY,
            maxX = seekMaxX,
            maxY = seekMaxY,
            accentColor = accentColor,
            accentOnColor = accentOnColor,
            onFeedback = onSeekHudTextChange,
            initialXRatio = 1f,
            initialYRatio = 0.10f,
            isLiquidGlass = isLiquidGlass,
            glassModifier = ballMaterialModifier,
            prefs = prefs,
            ballId = FloatingBallPositions.BATTERY,
            rememberPosition = isMemoryModeEnabled
        )
    }

    if (isPlayBallEnabled && ballsAllowed) {
        ControlFloatingBall(
            isPlaying = playerInstance?.isPlaying == true,
            maxX = seekMaxX,
            maxY = seekMaxY,
            accentColor = accentColor,
            accentOnColor = accentOnColor,
            onTogglePlayPause = {
                val p = playerInstance
                if (p != null) {
                    if (p.isPlaying) p.pause() else p.play()
                }
            },
            onFeedback = onSeekHudTextChange,
            initialXRatio = 1f,
            initialYRatio = 0.42f,
            isLiquidGlass = isLiquidGlass,
            glassModifier = ballMaterialModifier,
            prefs = prefs,
            ballId = FloatingBallPositions.PLAY,
            rememberPosition = isMemoryModeEnabled
        )
    }

    if (isPerfBallEnabled && ballsAllowed) {
        PerfFloatingBall(
            maxX = seekMaxX,
            maxY = seekMaxY,
            accentColor = accentColor,
            accentOnColor = accentOnColor,
            onFeedback = onSeekHudTextChange,
            initialXRatio = 0f,
            initialYRatio = 0.26f,
            isLiquidGlass = isLiquidGlass,
            glassModifier = ballMaterialModifier,
            prefs = prefs,
            ballId = FloatingBallPositions.PERF,
            rememberPosition = isMemoryModeEnabled
        )
    }

    if (isSeekForwardBallEnabled && ballsAllowed) {
        SeekFloatingBall(
            forward = true,
            stepSeconds = seekForwardStep,
            maxX = seekMaxX,
            maxY = seekMaxY,
            accentColor = accentColor,
            accentOnColor = accentOnColor,
            initialYRatio = 0.28f,
            onStepCycle = {
                onSeekForwardStepChange(nextSeekStep(seekForwardStep))
                onSeekHudTextChange(fwdStepSwitched)
            },
            onTrigger = { step ->
                if (isLiveStream) {
                    onSeekHudTextChange(context.getString(R.string.live_seek_disabled))
                } else {
                    val p = playerInstance
                    if (p != null) {
                        val dur = if (p.duration > 0) p.duration else Long.MAX_VALUE
                        p.seekTo((p.currentPosition + step * 1000L).coerceIn(0L, dur))
                    }
                    onSeekHudTextChange(fwdHudLabel)
                }
            },
            onFeedback = onSeekHudTextChange,
            isLiquidGlass = isLiquidGlass,
            glassModifier = ballMaterialModifier,
            prefs = prefs,
            ballId = FloatingBallPositions.SEEK_FWD,
            rememberPosition = isMemoryModeEnabled
        )
    }

    if (isSeekBackwardBallEnabled && ballsAllowed) {
        SeekFloatingBall(
            forward = false,
            stepSeconds = seekBackwardStep,
            maxX = seekMaxX,
            maxY = seekMaxY,
            accentColor = accentColor,
            accentOnColor = accentOnColor,
            initialYRatio = 0.68f,
            onStepCycle = {
                onSeekBackwardStepChange(nextSeekStep(seekBackwardStep))
                onSeekHudTextChange(bwdStepSwitched)
            },
            onTrigger = { step ->
                if (isLiveStream) {
                    onSeekHudTextChange(context.getString(R.string.live_seek_disabled))
                } else {
                    val p = playerInstance
                    if (p != null) {
                        p.seekTo((p.currentPosition - step * 1000L).coerceAtLeast(0L))
                    }
                    onSeekHudTextChange(bwdHudLabel)
                }
            },
            onFeedback = onSeekHudTextChange,
            isLiquidGlass = isLiquidGlass,
            glassModifier = ballMaterialModifier,
            prefs = prefs,
            ballId = FloatingBallPositions.SEEK_BWD,
            rememberPosition = isMemoryModeEnabled
        )
    }

    LaunchedEffect(seekHudText) {
        if (seekHudText != null) {
            delay(1200L)
            onSeekHudTextChange(null)
        }
    }
    AnimatedVisibility(
        visible = seekHudText != null,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut() + scaleOut(targetScale = 0.8f),
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 28.dp)
            .testTag("seek_ball_hud")
    ) {
        Surface(
            color = Color(0xEE18171C),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.6f)),
            shadowElevation = 8.dp
        ) {
            Text(
                text = seekHudText ?: "",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}
