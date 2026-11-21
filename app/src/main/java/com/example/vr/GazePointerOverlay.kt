package com.example.vr

import com.example.R
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * v2.6.9 P0：Google Cardboard 免触屏视线凝视交互浮层（Gaze Pointer & Dwell Click）。
 *
 * ## 为什么需要
 * 手机插入 Cardboard / VR Box 盒子后，双手被外壳完全物理遮挡，无法直接触摸屏幕。
 * 本组件通过传感器追踪头部转动（Yaw, Pitch），在双目视野中驱动视线准星移动，
 * 悬停在控制按钮上凝视 1.2 秒自动触发点击，彻底实现 Hands-free 免手触闭环交互。
 */
@Composable
fun GazePointerOverlay(
    isSplitScreenVR: Boolean,
    isGazeEnabled: Boolean,
    isUiVisible: Boolean,
    isVideoPlaying: Boolean,
    vrIpdOffsetRatio: Float,
    yawDeg: Float,
    pitchDeg: Float,
    onPlayPauseToggle: () -> Unit,
    onSeekRelative: (seconds: Int) -> Unit,
    onExitVR: () -> Unit,
    onRecenter: () -> Unit,
    onSwapEyesToggle: () -> Unit = {},
    onKeepUiAlight: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isSplitScreenVR || !isGazeEnabled) return

    val view = LocalView.current
    val density = LocalDensity.current

    // 凝视触发所需时长（毫秒）
    val dwellTimeMs = 1200L

    // 目标按钮的全局 bounds 登记表 (按钮标识 -> 屏幕矩形区域)
    val buttonBounds = remember { mutableMapOf<String, Rect>() }

    // 当前准星命中的按钮标识（null 为未命中）
    var hoveredTarget by remember { mutableStateOf<String?>(null) }
    // 凝视进度 0.0f ~ 1.0f
    var dwellProgress by remember { mutableFloatStateOf(0f) }
    // 进入命中区域的时间戳
    var hoverStartTime by remember { mutableLongStateOf(0L) }

    // 头部姿态灵敏度：将偏航角与俯仰角（度）映射为单眼像素位移
    // 假设视线转动 ±20 度覆盖单眼屏幕的 ±300px
    val sensitivityPxPerDeg = with(density) { 15.dp.toPx() }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalWidthPx = constraints.maxWidth.toFloat()
        val totalHeightPx = constraints.maxHeight.toFloat()
        val halfWidthPx = totalWidthPx / 2f

        // 视口物理中心及瞳距视差微调
        val ipdPixelOffset = vrIpdOffsetRatio * 80f
        val leftEyeCenterX = halfWidthPx * 0.5f - ipdPixelOffset
        val rightEyeCenterX = halfWidthPx * 1.5f + ipdPixelOffset
        val eyeCenterY = totalHeightPx * 0.5f

        // 计算准星在当前屏幕上的绝对坐标（以左半屏作为基准命中判定）
        // 头部向右转（yaw > 0），视线向右移；低头（pitch < 0），视线向下移
        val reticleLeftX = (leftEyeCenterX + yawDeg * sensitivityPxPerDeg).coerceIn(40f, halfWidthPx - 40f)
        val reticleY = (eyeCenterY - pitchDeg * sensitivityPxPerDeg).coerceIn(40f, totalHeightPx - 40f)
        val reticleRightX = (rightEyeCenterX + yawDeg * sensitivityPxPerDeg).coerceIn(halfWidthPx + 40f, totalWidthPx - 40f)

        // 命中检测：用左眼准星位置检测落在哪个左眼按钮上
        val currentHovered = buttonBounds.entries.firstOrNull { (_, rect) ->
            rect.contains(Offset(reticleLeftX, reticleY))
        }?.key

        // 状态更新与计时循环
        LaunchedEffect(currentHovered) {
            if (currentHovered != null) {
                hoveredTarget = currentHovered
                hoverStartTime = System.currentTimeMillis()
                while (hoveredTarget == currentHovered) {
                    val elapsed = System.currentTimeMillis() - hoverStartTime
                    val p = (elapsed.toFloat() / dwellTimeMs).coerceIn(0f, 1f)
                    dwellProgress = p
                    if (p >= 1f) {
                        // 触发点击
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        when (currentHovered) {
                            "btn_play_pause" -> onPlayPauseToggle()
                            "btn_replay_10" -> onSeekRelative(-10)
                            "btn_forward_10" -> onSeekRelative(10)
                            "btn_recenter" -> onRecenter()
                            "btn_swap_eyes" -> onSwapEyesToggle()
                            "btn_exit_vr" -> onExitVR()
                        }
                        onKeepUiAlight()
                        // 触发后重置，防止连续触发
                        dwellProgress = 0f
                        hoverStartTime = System.currentTimeMillis() + 800L
                        delay(800L)
                    }
                    delay(16L) // ~60 FPS 刷新
                }
            } else {
                hoveredTarget = null
                dwellProgress = 0f
            }
        }

        // 双目对称的大按钮快捷凝视浮岛（Gaze Control HUD）
        AnimatedVisibility(
            visible = isUiVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // 左眼控制盘
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    GazeControlDock(
                        isVideoPlaying = isVideoPlaying,
                        hoveredTarget = hoveredTarget,
                        onPositioned = { id, rect -> buttonBounds[id] = rect },
                        modifier = Modifier.padding(bottom = 60.dp)
                    )
                }

                // 右眼控制盘（对称显示，用于双目融像）
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    GazeControlDock(
                        isVideoPlaying = isVideoPlaying,
                        hoveredTarget = hoveredTarget,
                        onPositioned = { _, _ -> /* 右眼仅用于显示渲染，点击命中由左眼基准计算即可 */ },
                        modifier = Modifier.padding(bottom = 60.dp)
                    )
                }
            }
        }

        // 左眼视线准星（Reticle）
        GazeReticle(
            centerX = reticleLeftX,
            centerY = reticleY,
            isHovering = hoveredTarget != null,
            progress = dwellProgress,
            isGhostMode = !isUiVisible
        )

        // 右眼视线准星（Reticle）
        GazeReticle(
            centerX = reticleRightX,
            centerY = reticleY,
            isHovering = hoveredTarget != null,
            progress = dwellProgress,
            isGhostMode = !isUiVisible
        )
    }
}

/**
 * 专为 Cardboard 打造的免触控凝视浮岛（大按钮设计，容易瞄准，防误触）
 */
@Composable
private fun GazeControlDock(
    isVideoPlaying: Boolean,
    hoveredTarget: String?,
    onPositioned: (String, Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(32.dp))
            .background(Color(0xCC121216))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(32.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 快退 10 秒
        GazeButtonItem(
            id = "btn_replay_10",
            icon = Icons.Default.Replay10,
            contentDescription = "-10s",
            isHovered = hoveredTarget == "btn_replay_10",
            onPositioned = onPositioned
        )

        // 播放 / 暂停 (核心大按钮)
        GazeButtonItem(
            id = "btn_play_pause",
            icon = if (isVideoPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (isVideoPlaying) "Pause" else "Play",
            isHovered = hoveredTarget == "btn_play_pause",
            size = 52,
            iconSize = 30,
            onPositioned = onPositioned
        )

        // 快进 10 秒
        GazeButtonItem(
            id = "btn_forward_10",
            icon = Icons.Default.Forward10,
            contentDescription = "+10s",
            isHovered = hoveredTarget == "btn_forward_10",
            onPositioned = onPositioned
        )

        // 视角归中 Recenter
        GazeButtonItem(
            id = "btn_recenter",
            icon = Icons.Default.CenterFocusStrong,
            contentDescription = stringResource(R.string.gaze_recenter),
            isHovered = hoveredTarget == "btn_recenter",
            onPositioned = onPositioned
        )

        // 眼序对调 Swap Eyes (3D 左右/上下对调)
        GazeButtonItem(
            id = "btn_swap_eyes",
            icon = Icons.Default.SwapHoriz,
            contentDescription = stringResource(R.string.vr_swap_eyes),
            isHovered = hoveredTarget == "btn_swap_eyes",
            onPositioned = onPositioned
        )

        // 退出分屏 VR
        GazeButtonItem(
            id = "btn_exit_vr",
            icon = Icons.Default.Close,
            contentDescription = "Exit VR",
            isHovered = hoveredTarget == "btn_exit_vr",
            onPositioned = onPositioned
        )
    }
}

/**
 * 凝视按钮单项
 */
@Composable
private fun GazeButtonItem(
    id: String,
    icon: ImageVector,
    contentDescription: String,
    isHovered: Boolean,
    size: Int = 44,
    iconSize: Int = 24,
    onPositioned: (String, Rect) -> Unit
) {
    val scale by animateFloatAsState(targetValue = if (isHovered) 1.15f else 1.0f, label = "gaze_scale")
    val bgColor = if (isHovered) Color(0xFF6750A4) else Color(0x33FFFFFF)
    val contentColor = if (isHovered) Color.White else Color(0xEEFFFFFF)

    Box(
        modifier = Modifier
            .size((size * scale).dp)
            .clip(CircleShape)
            .background(bgColor)
            .border(if (isHovered) 2.dp else 1.dp, if (isHovered) Color.White else Color(0x22FFFFFF), CircleShape)
            .onGloballyPositioned { coords ->
                onPositioned(id, coords.boundsInRoot())
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size((iconSize * scale).dp)
        )
    }
}

/**
 * 视线准星（Reticle）及 Dwell 进度环
 */
@Composable
private fun GazeReticle(
    centerX: Float,
    centerY: Float,
    isHovering: Boolean,
    progress: Float,
    isGhostMode: Boolean
) {
    val targetRadius = if (isHovering) 18.dp else if (isGhostMode) 5.dp else 8.dp
    val alpha = if (isGhostMode) 0.25f else if (isHovering) 0.95f else 0.70f

    Canvas(
        modifier = Modifier
            .fillMaxSize()
    ) {
        val center = Offset(centerX, centerY)
        val radiusPx = targetRadius.toPx()

        if (isGhostMode) {
            // 观影模式下的幽灵小点
            drawCircle(
                color = Color.White.copy(alpha = alpha),
                radius = radiusPx,
                center = center
            )
        } else {
            // 外圈底环
            drawCircle(
                color = Color.White.copy(alpha = alpha * 0.4f),
                radius = radiusPx,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )

            // 中心微瞄点
            drawCircle(
                color = Color.White.copy(alpha = alpha),
                radius = 2.5.dp.toPx(),
                center = center
            )

            // 命中悬停时顺时针绘制 Dwell 凝视计时环
            if (isHovering && progress > 0f) {
                drawArc(
                    color = Color(0xFFD0BCFF),
                    startAngle = -90f,
                    sweepAngle = progress * 360f,
                    useCenter = false,
                    topLeft = Offset(center.x - radiusPx, center.y - radiusPx),
                    size = Size(radiusPx * 2, radiusPx * 2),
                    style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
    }
}
