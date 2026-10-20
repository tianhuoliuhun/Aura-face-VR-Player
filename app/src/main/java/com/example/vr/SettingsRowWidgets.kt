package com.example.vr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v2.6.7：设置面板的**开关行**（从 `VRPlayerScreen` 搬出，逻辑未改）。
 *
 * 原来它是 `SettingsSection3()` 里的内嵌函数，靠闭包读 `AccentColor` / `AccentOnColor`
 * 与 `keepUiAlight()`。搬出来必须显式传：
 *  - [accentColor] / [accentOnColor]：主题色来自 `uiTheme.accent`，是**局部**变量，无法全局取；
 *  - [onInteract]：原 `keepUiAlight()` 的作用（记录交互时间、点亮 UI），由调用方决定。
 *
 * ⚠️ 布局约束是有意为之（v2.1.213 修过两次），改的时候别把 maxLines 去掉：
 *  - 标题限 1 行：某些语言（德/俄语系）标题很长，不限行会把 Switch 挤出可视区；
 *  - 描述限 2 行：描述一长（如时间标记球那句 33 字）会撑成 4~5 行，视觉上像"UI 溢出"；
 *  - Switch 固定 46dp + scale(0.8)：`scale()` 不改变布局占位，不固定宽度会把左侧文字挤窄。
 */
@Composable
fun BallSwitchRow(
    title: String,
    desc: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    tag: String,
    accentColor: Color,
    accentOnColor: Color,
    onInteract: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), shape = RoundedCornerShape(10.dp))
            .clickable {
                onChange(!checked)
                onInteract()
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // ⚠️ 不用 desc?.let{} —— @Composable 调用嵌进普通 lambda 会丢作用域
            if (desc != null) {
                Text(
                    desc,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        AuraSwitch(
            checked = checked,
            onCheckedChange = {
                onChange(it)
                onInteract()
            },
            accentColor = accentColor,
            accentOnColor = accentOnColor,
            modifier = Modifier
                .width(46.dp)
                .scale(0.8f)
                .testTag(tag)
        )
    }
}
