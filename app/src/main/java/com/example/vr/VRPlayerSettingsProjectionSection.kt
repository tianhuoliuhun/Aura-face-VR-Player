package com.example.vr

import android.content.SharedPreferences
import androidx.compose.ui.platform.testTag
import kotlin.math.roundToInt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

@Composable
internal fun PlayerSettingsProjectionSection(
    prefs: SharedPreferences,
    accentColor: Color,
    accentOnColor: Color,
    renderer: VRGLRenderer?,
    isSmartProjectionEnabled: Boolean,
    onSmartProjectionEnabledChange: (Boolean) -> Unit,
    projectionMode: ProjectionMode,
    onProjectionModeChange: (ProjectionMode) -> Unit,
    onProjectionModeUserAdjustedChange: (Boolean) -> Unit,
    domeHalfSelect: Int,
    onDomeHalfSelectChange: (Int) -> Unit,
    stereoMode: StereoMode,
    onStereoModeChange: (StereoMode) -> Unit,
    fisheyeFovDeg: Int,
    onFisheyeFovDegChange: (Int) -> Unit,
    fovDeg: Float,
    onFovDegChange: (Float) -> Unit,
    videoCurvature: Float,
    onVideoCurvatureChange: (Float) -> Unit,
    warpMode: WarpMode,
    onWarpModeChange: (WarpMode) -> Unit,
    isVideoMirrored: Boolean,
    onVideoMirroredChange: (Boolean) -> Unit,
    isAudioMirrored: Boolean,
    onAudioMirroredChange: (Boolean) -> Unit,
    gyroOrientationMode: VRSensorManager.OrientationMode,
    onGyroOrientationModeChange: (VRSensorManager.OrientationMode) -> Unit,
    gyroInverted: Boolean,
    onGyroInvertedChange: (Boolean) -> Unit,
    isEyesSwapped: Boolean,
    onEyesSwappedChange: (Boolean) -> Unit,
    isCardboardDistortionEnabled: Boolean,
    onCardboardDistortionEnabledChange: (Boolean) -> Unit,
    vrCinemaScale: Float,
    onVrCinemaScaleChange: (Float) -> Unit,
    vrIpdOffsetRatio: Float,
    onVrIpdOffsetRatioChange: (Float) -> Unit,
    isGazePointerEnabled: Boolean,
    onGazePointerEnabledChange: (Boolean) -> Unit,
    forceVideoType: Int,
    onForceVideoTypeChange: (Int) -> Unit,
    maxResolution: MaxResolution,
    onMaxResolutionChange: (MaxResolution) -> Unit,
    onKeepUiAlight: () -> Unit,
) {
Text(
    text = stringResource(R.string.settings_group_projection),
    color = accentColor,
    fontSize = 12.sp,
    fontWeight = FontWeight.Bold
)
Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    // Smart projection detection master switch (8/1 功能)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.projection_auto_detect), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(stringResource(R.string.projection_auto_detect_desc), color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        AuraSwitch(
            checked = isSmartProjectionEnabled,
            onCheckedChange = {
                onSmartProjectionEnabledChange(it)
                prefs.edit().putBoolean("smart_projection_enabled", it).apply()
                onKeepUiAlight()
            },
            accentColor = accentColor,
            accentOnColor = accentOnColor
        )
    }
    // 强制视频类型判断（自动/2D/360°/180°/3D 左右/3D 上下）
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.force_video_type), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
        val forceOptions = listOf(
            0 to stringResource(R.string.auto),
            1 to "2D",
            2 to "360°",
            3 to "180°",
            4 to stringResource(R.string.video_type_3d_sbs),
            5 to stringResource(R.string.video_type_3d_tab)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            forceOptions.forEach { (type, label) ->
                val isSel = forceVideoType == type
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSel) accentColor else Color.White.copy(alpha = 0.08f))
                        .clickable {
                            onForceVideoTypeChange(type)
                            prefs.edit().putInt("force_video_type", type).apply()
                            onKeepUiAlight()
                        }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (isSel) accentOnColor else Color.White,
                        fontSize = 9.sp,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.force_video_type_desc),
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 8.sp
        )
    }
    Text(stringResource(R.string.view_format), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
    ProjectionMode.values().toList().chunked(3).forEach { rowModes ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            rowModes.forEach { mode ->
                val isSelected = projectionMode == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                        .background(
                            if (isSelected) accentColor else Color.White.copy(alpha = 0.05f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable {
                            onProjectionModeChange(mode)
                            onProjectionModeUserAdjustedChange(true)
                            onKeepUiAlight()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(mode.labelRes),
                        color = if (isSelected) accentOnColor else Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            // Fill empty spaces if a row is incomplete
            repeat(3 - rowModes.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}
// v2.1.211：**鱼眼视场角** —— 仅在 FISHEYE 模式下显示
// （其它模式读不到这个值，显示出来只会让人困惑）。
// 不同鱼眼镜头片源的实际视角不同（常见 180/190/200/220），
// 选错会出现「画面鼓成球」或「中心挤成一团」。
if (projectionMode == ProjectionMode.FISHEYE) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.fisheye_fov), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(180, 190, 200, 220).forEach { fov ->
                val isSel = fisheyeFovDeg == fov
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                        .background(
                            if (isSel) accentColor else Color.White.copy(alpha = 0.05f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable {
                            onFisheyeFovDegChange(fov)
                            onProjectionModeUserAdjustedChange(true)
                            onKeepUiAlight()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${fov}°",
                        color = if (isSel) accentOnColor else Color.White.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
// ⚠️ v2.1.210 曾在此处新增「投影模式」选择器 —— **已删除**。
// 原因：项目在下方「视角格式」(`R.string.view_format`) 处**早就有**
// 同一个 `ProjectionMode` 的选择器（带 projectionModeUserAdjusted 标记）。
// 我当初 grep `R.string.proj_*` 没命中就误判为「没有入口」，
// 但那里是用 `mode.labelRes` **动态取标签**，静态 grep 搜不到 ——
// 教训：查「某枚举有没有 UI 入口」要用**枚举类型名**搜，不要搜资源 key。
// 新增的 EAC 会自动出现在原有选择器里（它遍历 ProjectionMode.values()），
// 无需额外 UI。
Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(stringResource(R.string.stereo_format), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        StereoMode.values().forEach { mode ->
            val isSelected = stereoMode == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .background(
                        if (isSelected) accentColor else Color.White.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable {
                        onStereoModeChange(mode)
                        // 🔴 v2.6.9：**手动选立体格式必须锁住自动检测**。
                        //    以前只改 stereoMode 不置标志，于是下一次
                        //    onVideoSizeChanged 触发智能检测时：
                        //    2:1 的上下拼接全景会被判成「360 + 单目」，
                        //    检测发现 stereo 不同 → 直接把你选的「上下3D」改回单目。
                        //    表现就是「选了上下3D没反应 / 过一会儿自己变回2D」，
                        //    上下屏 VR 片源几乎一定会踩到。
                        onProjectionModeUserAdjustedChange(true)
                        onKeepUiAlight()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (mode) {
                        StereoMode.MONO -> stringResource(R.string.stereo_2d)
                        StereoMode.SBS -> stringResource(R.string.stereo_sbs)
                        StereoMode.TAB -> stringResource(R.string.stereo_tab)
                    },
                    color = if (isSelected) accentOnColor else Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(stringResource(R.string.video_mirror), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(false to stringResource(R.string.mirror_normal), true to stringResource(R.string.mirror_hflip)).forEach { (mirrored, label) ->
            val isSelected = isVideoMirrored == mirrored
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .background(
                        if (isSelected) accentColor else Color.White.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable {
                        onVideoMirroredChange(mirrored)
                        onKeepUiAlight()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (isSelected) accentOnColor else Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                 )
            }
        }
    }
}
// 陀螺仪朝向模式：决定"头部转动"如何映射为画面视角。
// 手持横屏与 VR 眼镜平放时正确的轴向完全不同，选错会导致方向错乱。
Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(stringResource(R.string.gyro_orientation_mode), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(
            VRSensorManager.OrientationMode.HANDHELD to stringResource(R.string.gyro_handheld),
            VRSensorManager.OrientationMode.VR_BOX to stringResource(R.string.gyro_vr_flat)
        ).forEach { (mode, label) ->
            val isSelected = gyroOrientationMode == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .background(
                        if (isSelected) accentColor else Color.White.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable {
                        onGyroOrientationModeChange(mode)
                        onKeepUiAlight()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (isSelected) accentOnColor else Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
    Text(
        text = stringResource(R.string.gyro_orientation_desc),
        color = Color.White.copy(alpha = 0.4f),
        fontSize = 8.sp
    )
}
// v125：陀螺仪转向反转。默认关闭（v125 起已修正为正确方向），
// 个别机型或 VR 眼镜模式下若仍上下/左右相反，打开此项即可。
ExperimentalSwitchRow(
    title = stringResource(R.string.gyro_invert),
    desc = stringResource(R.string.gyro_invert_desc),
    checked = gyroInverted,
    onChanged = { onGyroInvertedChange(it); onKeepUiAlight() },
    accentColor = accentColor,
    accentOnColor = accentOnColor
)
Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(stringResource(R.string.audio_channel_mirror), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(false to stringResource(R.string.audio_normal), true to stringResource(R.string.audio_swapped)).forEach { (mirrored, label) ->
            val isSelected = isAudioMirrored == mirrored
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .background(
                        if (isSelected) accentColor else Color.White.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable {
                        onAudioMirroredChange(mirrored)
                        onKeepUiAlight()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (isSelected) accentOnColor else Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                 )
            }
        }
    }
}
if (projectionMode == ProjectionMode.VR_180) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.dome_half_crop), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(1 to stringResource(R.string.dome_half_left), 0 to stringResource(R.string.dome_half_right)).forEach { (half, label) ->
                val isSelected = domeHalfSelect == half
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                        .background(
                            if (isSelected) accentColor else Color.White.copy(alpha = 0.05f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable {
                            onDomeHalfSelectChange(half)
                            onKeepUiAlight()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) accentOnColor else Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}
Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(stringResource(R.string.fov_title), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
        Text("${fovDeg.toInt()}°", color = accentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
    Slider(
        value = fovDeg,
        onValueChange = {
            onFovDegChange(it)
            onKeepUiAlight()
        },
        valueRange = 25f..125f,
        colors = SliderDefaults.colors(
            thumbColor = accentColor,
            activeTrackColor = accentColor,
            inactiveTrackColor = Color.White.copy(alpha = 0.15f)
        ),
        modifier = Modifier
            .height(26.dp)
            .testTag("fov_slider")
    )
}
Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(stringResource(R.string.warp_title), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
    WarpMode.values().toList().chunked(3).forEach { rowModes ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            rowModes.forEach { mode ->
                val isSelected = warpMode == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .background(
                            if (isSelected) accentColor else Color.White.copy(alpha = 0.05f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable {
                            onWarpModeChange(mode)
                            onKeepUiAlight()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(mode.labelRes),
                        color = if (isSelected) accentOnColor else Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            // Fill empty spaces if a row is incomplete
            repeat(3 - rowModes.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
    
    if (warpMode != WarpMode.NONE) {
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val label = when (warpMode) {
                WarpMode.CYLINDER_RECT -> stringResource(R.string.warp_equirect_cylinder)
                WarpMode.CYLINDER -> stringResource(R.string.warp_equirect_column)
                WarpMode.SPHERE -> stringResource(R.string.warp_sphere_expand)
                WarpMode.CURVE -> stringResource(R.string.warp_ring_curve)
                WarpMode.ANTI_SPHERE -> stringResource(R.string.warp_sphere_shrink)
                WarpMode.ANTI_CURVE -> stringResource(R.string.warp_curve_shrink)
                else -> stringResource(R.string.warp_zoom_curve)
            }
            Text(label, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
            Text(String.format("%.2f", videoCurvature), color = accentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Slider(
            value = videoCurvature,
            onValueChange = {
                onVideoCurvatureChange(it)
                onKeepUiAlight()
            },
            valueRange = 0.0f..0.8f,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = Color.White.copy(alpha = 0.1f)
            ),
            modifier = Modifier.height(26.dp)
        )
    }
}
Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(stringResource(R.string.max_resolution_title), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
    MaxResolution.values().toList().chunked(3).forEach { rowResolutions ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            rowResolutions.forEach { res ->
                val isSelected = maxResolution == res
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .background(
                            if (isSelected) accentColor else Color.White.copy(alpha = 0.05f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable {
                            onMaxResolutionChange(res)
                            onKeepUiAlight()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(res.labelRes),
                        color = if (isSelected) accentOnColor else Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            // Fill empty spaces if a row is incomplete
            repeat(3 - rowResolutions.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}
/** 区块：Google Cardboard VR 优化（透镜反畸变 / 适眼巨幕 / 免触控凝视） */
Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
        text = "Google Cardboard / VR 盒子优化",
        color = accentColor,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp)
    )
    ExperimentalSwitchRow(
        title = stringResource(R.string.cardboard_anti_distortion),
        desc = stringResource(R.string.cardboard_anti_distortion_desc),
        checked = isCardboardDistortionEnabled,
        onChanged = { onCardboardDistortionEnabledChange(it) },
        accentColor = accentColor,
        accentOnColor = accentOnColor
    )
    ExperimentalSwitchRow(
        title = stringResource(R.string.gaze_pointer_enabled),
        desc = stringResource(R.string.gaze_pointer_enabled_desc),
        checked = isGazePointerEnabled,
        onChanged = { onGazePointerEnabledChange(it) },
        accentColor = accentColor,
        accentOnColor = accentOnColor
    )
    // 参考 vrplayer-gemini：左右眼序对调
    ExperimentalSwitchRow(
        title = stringResource(R.string.vr_swap_eyes),
        desc = stringResource(R.string.vr_swap_eyes_desc),
        checked = isEyesSwapped,
        onChanged = { onEyesSwappedChange(it) },
        accentColor = accentColor,
        accentOnColor = accentOnColor
    )
    // 物理瞳距预设档位（58mm / 64mm / 70mm）
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.vr_ipd_title), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
            val estimatedMm = (64 + vrIpdOffsetRatio * 100f).roundToInt()
            Text("${estimatedMm}mm", color = accentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                -0.06f to R.string.vr_ipd_preset_narrow,
                0.0f to R.string.vr_ipd_preset_standard,
                0.06f to R.string.vr_ipd_preset_wide
            ).forEach { (offset, labelRes) ->
                val isSelected = kotlin.math.abs(vrIpdOffsetRatio - offset) < 0.02f
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .background(
                            if (isSelected) accentColor else Color.White.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(6.dp)
                        )
                        .clickable {
                            onVrIpdOffsetRatioChange(offset)
                            renderer?.vrIpdOffsetRatio = offset
                            onKeepUiAlight()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(labelRes),
                        color = if (isSelected) accentOnColor else Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(stringResource(R.string.cardboard_cinema_scale), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
        Text("${(vrCinemaScale * 100).toInt()}%", color = accentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
    Slider(
        value = vrCinemaScale,
        onValueChange = {
            onVrCinemaScaleChange(it)
            renderer?.vrCinemaScale = it
            onKeepUiAlight()
        },
        valueRange = 0.60f..1.0f,
        colors = SliderDefaults.colors(
            thumbColor = accentColor,
            activeTrackColor = accentColor,
            inactiveTrackColor = Color.White.copy(alpha = 0.1f)
        ),
        modifier = Modifier.height(26.dp)
    )
}

}
