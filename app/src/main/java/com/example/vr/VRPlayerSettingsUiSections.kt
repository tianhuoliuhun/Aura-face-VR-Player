package com.example.vr

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

@Composable
internal fun PlayerSettingsThemeSection(
    context: Context,
    prefs: SharedPreferences,
    accentColor: Color,
    accentOnColor: Color,
    translucentWhite10: Color,
    uiThemeId: Int,
    onUiThemeIdChange: (Int) -> Unit,
    uiTheme: UiThemePalette,
    glassMode: Int,
    onGlassModeChange: (Int) -> Unit,
    onKeepUiAlight: () -> Unit,
) {
Text(
    text = stringResource(R.string.settings_group_ui_theme),
    color = accentColor,
    fontSize = 12.sp,
    fontWeight = FontWeight.Bold
)
// v2.9.2：静态预设 6 → 16 套，单行 SpaceBetween 会把色点挤到
//   互相贴住 → 改为每行 8 个、共 2 行的网格；不足一行的尾部补
//   等宽占位，保证与上一行逐列对齐（同下方玻璃档 chip 的做法）。
// v2.4.45 两条约束继续保留：SpaceBetween 铺满行、选中不改尺寸
//   （选中只加粗白环，避免换主题时整行色点跟着抖）。
Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    UiThemes.list.chunked(8).forEach { rowThemes ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            rowThemes.forEach { t ->
                val selected = uiThemeId == t.id
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(t.accent)
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) Color.White
                            else Color.White.copy(alpha = 0.3f),
                            shape = CircleShape
                        )
                        .clickable {
                            onUiThemeIdChange(t.id)
                            prefs.edit().putInt("ui_theme_id", t.id).apply()
                            onKeepUiAlight()
                        }
                        .testTag("theme_dot_${t.id}")
                )
            }
            repeat(8 - rowThemes.size) {
                Spacer(modifier = Modifier.size(26.dp))
            }
        }
    }
}
// v2.9.2：MD 莫奈取色（Android 12+ Material You 动态配色）。
// 做成独立 chip 而非第 17 个色点：它没有固定颜色可展示，且低版本
// 需要明确禁用并说明原因。点任意静态色点即自动退出 Monet；
// 再点本 chip 关闭时回落到默认紫罗兰（DEFAULT_THEME_ID）。
val monetOn = uiThemeId == UiThemes.MONET_THEME_ID
val monetAvailable = UiThemes.isMonetAvailable()
Box(
    modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(50))
        .background(if (monetOn) accentColor else translucentWhite10)
        .clickable(enabled = monetAvailable) {
            val next = if (monetOn) UiThemes.DEFAULT_THEME_ID
            else UiThemes.MONET_THEME_ID
            onUiThemeIdChange(next)
            prefs.edit().putInt("ui_theme_id", next).apply()
            onKeepUiAlight()
        }
        .padding(horizontal = 10.dp, vertical = 5.dp)
        .testTag("theme_monet_chip"),
    contentAlignment = Alignment.Center
) {
    Text(
        text = stringResource(
            if (monetAvailable) R.string.theme_name_monet
            else R.string.theme_monet_unsupported
        ),
        color = if (monetOn) accentOnColor
        else Color.White.copy(alpha = if (monetAvailable) 1f else 0.45f),
        fontSize = 10.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}
// v2.4.45（主题选择界面布局优化）：
// 原先「主题名 + 4 个玻璃档 chip」硬塞在**同一行**（SpaceBetween，
// 且 chip 行**没有 weight**）—— 4 个中文 chip（实色 / 液体玻璃 / 磨砂 /
// 高斯模糊）合计约 220dp，加上主题名远超设置面板**窄列**的可用宽度
// → chip 被压扁、文字截断，甚至与左侧主题名互相叠压。
// 现改为**独立成块 + 两列两行**（与下方「界面语言」块同款：`chunked` +
// `weight(1f)` 等宽）—— 任何列宽下都不溢出，点击区也更大。
// v2.9.2：Monet 模式也要显示「跟随系统壁纸」，故直接取已解析的 uiTheme
Text(
    text = stringResource(uiTheme.nameRes),
    color = accentColor,
    fontSize = 12.sp
)
Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    listOf(
        0 to stringResource(R.string.theme_solid),
        1 to stringResource(R.string.theme_liquid_glass),
        // v2.1.218：v2.1.217 的 4 档并回 3 档 ——
        // Liquid 与 Liquid26 观感接近、选择意义不大，
        // 已合并为一档并采用 iOS 26 的增强参数。
        // v2.4.68：**删掉「高斯模糊」档**（用户要求）——
        // 它与磨砂的差别只有"无颗粒 + 更白"，观感几乎分不出来。
        2 to stringResource(R.string.theme_frosted_glass),
    ).chunked(2).forEach { rowModes ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            rowModes.forEach { (m, label) ->
                val sel = glassMode == m
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(if (sel) accentColor else translucentWhite10)
                        .clickable {
                            onGlassModeChange(m)
                            prefs.edit().putInt("ui_glass_mode", m).apply()
                            onKeepUiAlight()
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .testTag("glass_mode_$m"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (sel) accentOnColor else Color.White,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // v2.4.68：档位从 4 个减到 3 个 → 第二行只剩「磨砂」一个，
            // 若不给它右边补一个等宽占位，它会拉满整行、与上一行错位。
            if (rowModes.size < 2) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}
// v2.4.49：移除「玻璃诊断」提示文字（调试用，正式界面不再展示）
// v2.0.137：语言选择独立成块——原先塞在「主题名 … 玻璃模式」
// 同一行的中间（内部还有 fillMaxWidth 的两行按钮），垂直居中后
// 与左右内容互相叠压，表现为"语言选择叠在主题和玻璃效果上面"。
val currentLangTag = remember(context) { LanguageManager.getTag(context) }
Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
        stringResource(R.string.ui_language),
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold
    )
    // 6 种语言分两行排（一列太挤）
    LanguageManager.options.chunked(3).forEach { rowTags ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            rowTags.forEach { tag ->
                val sel = currentLangTag == tag
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (sel) accentColor
                            else Color.White.copy(alpha = 0.08f)
                        )
                        .clickable {
                            onKeepUiAlight()
                            LanguageManager.apply(context, tag)
                        }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        LanguageManager.displayName(tag),
                        color = if (sel) accentOnColor
                        else Color.White.copy(alpha = 0.75f),
                        fontSize = 9.sp,
                        fontWeight = if (sel) FontWeight.Bold
                        else FontWeight.Normal,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
    Text(
        stringResource(R.string.ui_language_hint),
        color = Color.White.copy(alpha = 0.4f),
        fontSize = 8.sp
    )
}

}

@Composable
internal fun PlayerSettingsFloatingBallSection(
    accentColor: Color,
    accentOnColor: Color,
    isFloatingBallEnabled: Boolean,
    onFloatingBallEnabledChange: (Boolean) -> Unit,
    isSeekForwardBallEnabled: Boolean,
    onSeekForwardBallEnabledChange: (Boolean) -> Unit,
    isSeekBackwardBallEnabled: Boolean,
    onSeekBackwardBallEnabledChange: (Boolean) -> Unit,
    isMarkerBallEnabled: Boolean,
    onMarkerBallEnabledChange: (Boolean) -> Unit,
    isClockBallEnabled: Boolean,
    onClockBallEnabledChange: (Boolean) -> Unit,
    isBatteryBallEnabled: Boolean,
    onBatteryBallEnabledChange: (Boolean) -> Unit,
    isPlayBallEnabled: Boolean,
    onPlayBallEnabledChange: (Boolean) -> Unit,
    isPerfBallEnabled: Boolean,
    onPerfBallEnabledChange: (Boolean) -> Unit,
    floatingBallSpeed: Float,
    onFloatingBallSpeedChange: (Float) -> Unit,
    basePlaybackSpeed: Float,
    onBasePlaybackSpeedChange: (Float) -> Unit,
    onKeepUiAlight: () -> Unit,
) {
Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
        text = stringResource(R.string.settings_group_floating_ball),
        color = accentColor,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), shape = RoundedCornerShape(10.dp))
            .clickable {
                onFloatingBallEnabledChange(!isFloatingBallEnabled)
                onKeepUiAlight()
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.floating_ball_enable), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(stringResource(R.string.floating_ball_desc), color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        AuraSwitch(
            checked = isFloatingBallEnabled,
            onCheckedChange = {
                onFloatingBallEnabledChange(it)
                onKeepUiAlight()
            },
            accentColor = accentColor,
            accentOnColor = accentOnColor,
            modifier = Modifier.scale(0.8f).testTag("floating_ball_switch")
        )
    }
    // v2.0.165：快进 / 后退悬浮球 —— 各自独立开关，默认均为**关**
    BallSwitchRow(
        title = stringResource(R.string.seek_ball_forward_enable),
        desc = stringResource(R.string.seek_ball_desc),
        checked = isSeekForwardBallEnabled,
        onChange = { onSeekForwardBallEnabledChange(it) },
        tag = "seek_forward_ball_switch",
        accentColor = accentColor,
        accentOnColor = accentOnColor,
        onInteract = { onKeepUiAlight() }
    )
    BallSwitchRow(
        title = stringResource(R.string.seek_ball_backward_enable),
        desc = stringResource(R.string.seek_ball_desc),
        checked = isSeekBackwardBallEnabled,
        onChange = { onSeekBackwardBallEnabledChange(it) },
        tag = "seek_backward_ball_switch",
        accentColor = accentColor,
        accentOnColor = accentOnColor,
        onInteract = { onKeepUiAlight() }
    )
    // v2.1.208：时间标记球开关（与上面两个同组、同一样式组件）
    BallSwitchRow(
        title = stringResource(R.string.marker_ball_enable),
        desc = stringResource(R.string.marker_ball_desc),
        checked = isMarkerBallEnabled,
        onChange = { onMarkerBallEnabledChange(it) },
        tag = "marker_ball_switch",
        accentColor = accentColor,
        accentOnColor = accentOnColor,
        onInteract = { onKeepUiAlight() }
    )
    // v2.4.67：信息球（时钟 / 电量）—— 同组、同一行样式组件
    BallSwitchRow(
        title = stringResource(R.string.info_ball_clock_enable),
        desc = stringResource(R.string.info_ball_clock_desc),
        checked = isClockBallEnabled,
        onChange = { onClockBallEnabledChange(it) },
        tag = "clock_ball_switch",
        accentColor = accentColor,
        accentOnColor = accentOnColor,
        onInteract = { onKeepUiAlight() }
    )
    BallSwitchRow(
        title = stringResource(R.string.info_ball_battery_enable),
        desc = stringResource(R.string.info_ball_battery_desc),
        checked = isBatteryBallEnabled,
        onChange = { onBatteryBallEnabledChange(it) },
        tag = "battery_ball_switch",
        accentColor = accentColor,
        accentOnColor = accentOnColor,
        onInteract = { onKeepUiAlight() }
    )
    // v2.6.4：播放控制球 / 性能球（同一组、同一样式）
    BallSwitchRow(
        title = stringResource(R.string.control_ball_enable),
        desc = stringResource(R.string.control_ball_desc),
        checked = isPlayBallEnabled,
        onChange = { onPlayBallEnabledChange(it) },
        tag = "play_ball_switch",
        accentColor = accentColor,
        accentOnColor = accentOnColor,
        onInteract = { onKeepUiAlight() }
    )
    BallSwitchRow(
        title = stringResource(R.string.perf_ball_enable),
        desc = stringResource(R.string.perf_ball_desc),
        checked = isPerfBallEnabled,
        onChange = { onPerfBallEnabledChange(it) },
        tag = "perf_ball_switch",
        accentColor = accentColor,
        accentOnColor = accentOnColor,
        onInteract = { onKeepUiAlight() }
    )
    if (isFloatingBallEnabled) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.floating_ball_speed), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(1.5f to "1.5X", 2.0f to "2.0X", 3.0f to "3.0X").forEach { (speed, label) ->
                    val isSelected = floatingBallSpeed == speed
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .background(
                                if (isSelected) accentColor else Color.White.copy(alpha = 0.05f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                onFloatingBallSpeedChange(speed)
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.base_playback_speed), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(0.75f to "0.75X", 1.0f to "1.0X", 1.25f to "1.25X", 1.5f to "1.5X", 2.0f to "2.0X").forEach { (speed, label) ->
                val isSelected = basePlaybackSpeed == speed
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .background(
                            if (isSelected) accentColor else Color.White.copy(alpha = 0.05f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable {
                            onBasePlaybackSpeedChange(speed)
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

}
