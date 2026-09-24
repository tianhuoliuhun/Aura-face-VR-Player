package com.example.vr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * v120 拆分：从 VRPlayerScreen.kt 抽出的播放控制栏（左/中/右三组按钮 + 自适应布局）。
 *
 * 原先是主函数内 BoxWithConstraints 里的三个内嵌 @Composable（LeftControls /
 * CenterControls / RightControls），直接读写十余个状态。外置后：
 * - 所有开关状态以「值 + onToggle」成对传入，组件内不持有业务状态
 * - 媒体切换（上一首/下一首）由 onPrev / onNext 上抛，组件不感知 MediaItem 与 DemoMediaProvider
 * - 重置视角中心需要访问渲染器，做成 onResetViewCenter 回调
 *
 * 布局：宽度不足（按钮总宽 + 间距超过可用宽度）时改为上下两行，否则左/中/右绝对定位。
 */
@Composable
fun PlayerControlButtons(
    accentColor: Color,
    accentOnColor: Color,
    isVideo: Boolean,
    isVideoPlaying: Boolean,
    isGyroEnabled: Boolean,
    onToggleGyro: () -> Unit,
    isViewLocked: Boolean,
    onToggleViewLock: () -> Unit,
    isLandscape: Boolean,
    onToggleOrientation: () -> Unit,
    /** v2.0.165：界面上下翻转 —— 与横竖屏按钮同款样式与联动逻辑（isActive 高亮 + onUserInteraction 点亮 UI） */
    isVerticallyFlipped: Boolean,
    onToggleVerticalFlip: () -> Unit,
    isSplitScreenVR: Boolean,
    onToggleSplitScreen: () -> Unit,
    isSubtitlePanelOpen: Boolean,
    onToggleSubtitlePanel: () -> Unit,
    isSettingsOpen: Boolean,
    onToggleSettings: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onResetViewCenter: () -> Unit,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        @Composable
        fun LeftControls() {
            Row(
                modifier = Modifier.align(Alignment.CenterStart),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 陀螺仪开关
                TooltipIconButton(
                    tooltip = if (isGyroEnabled) stringResource(R.string.cd_gyro_on) else stringResource(R.string.cd_gyro_off),
                    onClick = { onToggleGyro(); onUserInteraction() },
                    icon = Icons.Default.Explore,
                    isActive = isGyroEnabled
                )
                // 视角锁定（区别于屏幕旋转）
                TooltipIconButton(
                    tooltip = if (isViewLocked) stringResource(R.string.cd_view_locked) else stringResource(R.string.cd_view_free),
                    onClick = { onToggleViewLock(); onUserInteraction() },
                    icon = if (isViewLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    isActive = isViewLocked
                )
                // 屏幕旋转
                TooltipIconButton(
                    tooltip = if (!isLandscape) stringResource(R.string.cd_portrait_locked) else stringResource(R.string.cd_lock_portrait),
                    onClick = { onToggleOrientation(); onUserInteraction() },
                    icon = Icons.Default.ScreenRotation,
                    isActive = !isLandscape
                )
                // v2.0.165：界面上下反转（紧邻横竖屏按钮，样式与联动逻辑一致）
                TooltipIconButton(
                    tooltip = if (isVerticallyFlipped) {
                        stringResource(R.string.cd_vflip_on)
                    } else {
                        stringResource(R.string.cd_vflip_off)
                    },
                    onClick = { onToggleVerticalFlip(); onUserInteraction() },
                    icon = Icons.Default.SwapVert,
                    isActive = isVerticallyFlipped
                )
            }
        }

        @Composable
        fun CenterControls() {
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onPrev(); onUserInteraction() }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = stringResource(R.string.cd_prev),
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // 播放 / 暂停（54dp 主按钮）
                PlayPauseButton(
                    accentColor = accentColor,
                    accentOnColor = accentOnColor,
                    isVideo = isVideo,
                    isPlaying = isVideoPlaying,
                    onToggle = { onTogglePlayPause(); onUserInteraction() }
                )

                IconButton(onClick = { onNext(); onUserInteraction() }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = stringResource(R.string.cd_next),
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        @Composable
        fun RightControls() {
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TooltipIconButton(
                    tooltip = stringResource(R.string.cd_reset_view),
                    onClick = { onResetViewCenter(); onUserInteraction() },
                    icon = Icons.Default.MyLocation,
                    iconSize = 18.dp
                )
                TooltipIconButton(
                    tooltip = if (isSplitScreenVR) stringResource(R.string.cd_exit_vr_split) else stringResource(R.string.cd_vr_split),
                    onClick = { onToggleSplitScreen(); onUserInteraction() },
                    icon = Icons.Default.ViewInAr,
                    isActive = isSplitScreenVR
                )
                TooltipIconButton(
                    tooltip = stringResource(R.string.cd_subtitle_panel),
                    onClick = { onToggleSubtitlePanel(); onUserInteraction() },
                    icon = Icons.Default.Subtitles,
                    isActive = isSubtitlePanelOpen
                )
                TooltipIconButton(
                    tooltip = stringResource(R.string.cd_playback_settings),
                    onClick = { onToggleSettings(); onUserInteraction() },
                    icon = Icons.Default.Settings,
                    isActive = isSettingsOpen
                )
            }
        }

        // v2.0.165：左侧图标按钮由 3 个增至 4 个（新增「上下反转」），窄屏判定同步更新
        if ((4 * 40 + 3 * 8 + 40 + 54 + 40 + 2 * 12 + 6 * 40 + 5 * 8 + 32).dp > maxWidth) {
            // 窄屏：控制组上下排列
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CenterControls()
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LeftControls()
                    RightControls()
                }
            }
        } else {
            // 宽屏：左 / 中 / 右绝对定位（播控组绝对居中）
            Box(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.align(Alignment.CenterStart)) { LeftControls() }
                Box(modifier = Modifier.align(Alignment.Center)) { CenterControls() }
                Box(modifier = Modifier.align(Alignment.CenterEnd)) { RightControls() }
            }
        }
    }
}

/**
 * 播放 / 暂停主按钮（54dp 圆形，位于控制栏正中）。
 *
 * 单独抽成组件是因为它是 CenterControls 里唯一带状态分支的按钮，
 * 且与 [PlayerControlButtons] 的其余部分解耦后更便于复用。
 */
@Composable
fun PlayPauseButton(
    accentColor: Color,
    accentOnColor: Color,
    isVideo: Boolean,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(54.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(accentColor)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isVideo && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = stringResource(R.string.cd_play_pause),
            tint = accentOnColor,
            modifier = Modifier.size(30.dp)
        )
    }
}
