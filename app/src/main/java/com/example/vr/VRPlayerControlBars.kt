package com.example.vr

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOutQuart
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 播放器锁屏按钮 + 顶部标题操作栏（从 VRPlayerScreen.kt 拆出）。
 */
@Composable
internal fun BoxScope.PlayerTopAndLockOverlay(
    isUiVisible: Boolean,
    isUiLocked: Boolean,
    onToggleUiLocked: () -> Unit,
    isSettingsDialogOpen: Boolean,
    onToggleSettingsDialog: () -> Unit,
    isSplitScreenVR: Boolean,
    onExitSplitScreenVR: () -> Unit,
    onToggleSplitScreenVR: () -> Unit,
    themePanelBgColor: Color,
    accentColor: Color,
    textLightColor: Color,
    translucentWhite10: Color,
    mediaDisplayName: String,
    onOpenPickerSource: () -> Unit,
    onKeepUiAlight: () -> Unit,
) {
    AnimatedVisibility(
        visible = isUiVisible,
        enter = slideInHorizontally(animationSpec = tween(500, easing = CustomEaseOutBack)) { -it } +
            fadeIn(animationSpec = tween(350, easing = EaseInOutCubic)),
        exit = slideOutHorizontally(animationSpec = tween(350, easing = EaseInOutCubic)) { -it } +
            fadeOut(animationSpec = tween(250, easing = EaseInOutCubic)),
        modifier = Modifier
            .align(Alignment.CenterStart)
            .padding(start = 24.dp)
    ) {
        FilledIconButton(
            onClick = {
                onToggleUiLocked()
                onKeepUiAlight()
            },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = themePanelBgColor,
                contentColor = if (isUiLocked) Color(0xFFFF5252) else accentColor
            ),
            modifier = Modifier
                .size(54.dp)
                .testTag("ui_lock_button")
        ) {
            Icon(
                imageVector = if (isUiLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                contentDescription = if (isUiLocked) stringResource(R.string.unlock_ui) else stringResource(R.string.lock_ui),
                modifier = Modifier.size(24.dp)
            )
        }
    }

    AnimatedVisibility(
        visible = isUiVisible && !isSettingsDialogOpen && !isUiLocked,
        enter = slideInVertically(animationSpec = tween(550, easing = EaseOutQuart)) { -it } +
            fadeIn(animationSpec = tween(400, easing = EaseInOutCubic)),
        exit = slideOutVertically(animationSpec = tween(400, easing = EaseInOutCubic)) { -it } +
            fadeOut(animationSpec = tween(300, easing = EaseInOutCubic)),
        modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.TopCenter)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xE01C1B1F), Color(0xA01C1B1F), Color.Transparent)
                    )
                )
                .clickable(enabled = true, onClick = { onKeepUiAlight() })
        ) {
            if (isSplitScreenVR) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.app_desc),
                            color = textLightColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        IconButton(
                            onClick = {
                                onExitSplitScreenVR()
                                onKeepUiAlight()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ViewInAr,
                                contentDescription = stringResource(R.string.cd_exit_vr_split),
                                tint = accentColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(32.dp))
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.app_desc),
                            color = textLightColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        IconButton(
                            onClick = {
                                onExitSplitScreenVR()
                                onKeepUiAlight()
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ViewInAr,
                                contentDescription = stringResource(R.string.cd_exit_vr_split),
                                tint = accentColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Face,
                                contentDescription = stringResource(R.string.cd_beauty_icon),
                                tint = accentColor,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.app_desc),
                                color = textLightColor,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.testTag("app_title_text")
                            )
                        }
                        Text(
                            text = stringResource(R.string.current_media, mediaDisplayName),
                            color = accentColor.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 240.dp)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        IconButton(
                            onClick = {
                                onKeepUiAlight()
                                onOpenPickerSource()
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = Color(0x2BD0BCFF),
                                contentColor = accentColor
                            ),
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("local_file_picker_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.import_local_media)
                            )
                        }

                        IconButton(
                            onClick = {
                                onToggleSplitScreenVR()
                                onKeepUiAlight()
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isSplitScreenVR) Color(0x33D0BCFF) else translucentWhite10,
                                contentColor = if (isSplitScreenVR) accentColor else Color.White
                            ),
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("vr_splitscreen_toggle_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.ViewInAr,
                                contentDescription = stringResource(R.string.cd_vr_split_mode),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                onToggleSettingsDialog()
                                onKeepUiAlight()
                            },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (isSettingsDialogOpen) Color(0x33D0BCFF) else translucentWhite10,
                                contentColor = if (isSettingsDialogOpen) accentColor else Color.White
                            ),
                            modifier = Modifier
                                .size(44.dp)
                                .testTag("top_settings_orchestra_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.beauty_projection_menu),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 播放器底部进度条与 seek/AVI 卡死检测（从 VRPlayerScreen.kt 拆出）。
 */
@Composable
internal fun PlayerProgressBarControl(
    isLiveStream: Boolean,
    videoDurationText: String,
    accentColor: Color,
    videoPlaybackProgress: Float,
    onVideoPlaybackProgressChange: (Float) -> Unit,
    isSliderDragged: Boolean,
    isSeekingActive: Boolean,
    onIsSeekingActiveChange: (Boolean) -> Unit,
    seekStartProgress: Float,
    onSeekStartProgressChange: (Float) -> Unit,
    seekStartValue: Float,
    onSeekStartValueChange: (Float) -> Unit,
    onIsHoverActiveChange: (Boolean) -> Unit,
    onKeepUiAlight: () -> Unit,
    playerInstance: VrPlayerBackend?,
    hoverTimeMs: Long,
    onHoverTimeMsChange: (Long) -> Unit,
    playbackEnded: Boolean,
    onPlaybackEndedChange: (Boolean) -> Unit,
    onIsVideoPlayingChange: (Boolean) -> Unit,
    aviNeedsRebuild: Boolean,
    scope: CoroutineScope,
    seekUnsupported: Boolean,
    isRemuxing: Boolean,
    selectedMediaItem: MediaItem,
    onStartRemuxFix: () -> Unit,
    sliderInteractionSource: MutableInteractionSource,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = if (isLiveStream) "● LIVE"
            else if (videoDurationText.contains("/")) videoDurationText.split("/")[0].trim()
            else "00:00",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )

        if (isLiveStream) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accentColor.copy(alpha = 0.55f))
                )
            }
        } else {
            Slider(
                value = videoPlaybackProgress,
                enabled = !isLiveStream,
                onValueChange = { seeker ->
                    val adjustedProgress = if (isSliderDragged) {
                        if (!isSeekingActive) {
                            onIsSeekingActiveChange(true)
                            onSeekStartProgressChange(videoPlaybackProgress)
                            onSeekStartValueChange(seeker)
                        }
                        val delta = seeker - seekStartValue
                        (seekStartProgress + delta * 0.25f).coerceIn(0f, 1f)
                    } else {
                        onIsSeekingActiveChange(false)
                        seeker
                    }
                    onVideoPlaybackProgressChange(adjustedProgress)
                    onIsHoverActiveChange(true)
                    onKeepUiAlight()
                    playerInstance?.let { mp ->
                        try {
                            val duration = mp.duration
                            if (duration > 0) {
                                onHoverTimeMsChange((adjustedProgress * duration).toLong())
                            }
                        } catch (_: Exception) {}
                    }
                },
                onValueChangeFinished = {
                    onIsSeekingActiveChange(false)
                    val finalProgress = videoPlaybackProgress
                    var seekTarget = hoverTimeMs
                    var seekIssued = false
                    playerInstance?.let { mp ->
                        try {
                            val duration = mp.duration
                            if (duration > 0) {
                                seekTarget = (finalProgress * duration).toLong()
                                    .coerceIn(0L, duration)
                                onHoverTimeMsChange(seekTarget)
                                mp.seekTo(seekTarget)
                                seekIssued = true
                            } else {
                                if (seekTarget > 0) {
                                    mp.seekTo(seekTarget)
                                    seekIssued = true
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    if (seekIssued) {
                        onVideoPlaybackProgressChange(finalProgress)
                        if (playbackEnded) {
                            onPlaybackEndedChange(false)
                            playerInstance?.play()
                            onIsVideoPlayingChange(true)
                        }
                    }
                    val seekIssuedFlag = seekIssued
                    scope.launch {
                        delay(2500L)
                        if (!seekIssuedFlag || seekTarget <= 3000L) return@launch
                        val inst = playerInstance ?: return@launch
                        if (inst.engine != DecoderEngine.EXO) return@launch
                        if (seekUnsupported || isRemuxing || !selectedMediaItem.isVideo) return@launch
                        if (inst.exo?.playbackState == Player.STATE_BUFFERING) return@launch

                        val posA = inst.currentPosition
                        val stuckAtStart = posA < 2000L && seekTarget > 5000L

                        var frozen = false
                        if (!stuckAtStart && kotlin.math.abs(posA - seekTarget) <= 5000L) {
                            var prev = posA
                            var stalls = 0
                            repeat(3) {
                                delay(500L)
                                val cur = inst.currentPosition
                                val buffering = inst.exo?.playbackState == Player.STATE_BUFFERING
                                if (!buffering && inst.isPlaying) {
                                    if (cur - prev <= 0L) stalls++ else stalls = 0
                                } else {
                                    stalls = 0
                                }
                                prev = cur
                            }
                            frozen = stalls >= 2
                        }

                        if (!stuckAtStart && !frozen) return@launch

                        Log.i(
                            "VRPlayerScreen",
                            "seek 异常（stuckAtStart=$stuckAtStart frozen=$frozen " +
                                "pos=${posA / 1000}s target=${seekTarget / 1000}s " +
                                "aviNoIdx1=$aviNeedsRebuild）→ 重建索引"
                        )
                        onStartRemuxFix()
                    }
                    scope.launch {
                        delay(2500L)
                        onIsHoverActiveChange(false)
                    }
                },
                interactionSource = sliderInteractionSource,
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(16.dp)
            )
        }

        Text(
            text = if (isLiveStream) ""
            else if (videoDurationText.contains("/")) videoDurationText.split("/")[1].trim()
            else "00:00",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
