package com.example.vr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v119 拆分：从 VRPlayerScreen.kt 搬出的通用设置面板组件。
 *
 * 三者都是**无业务状态**的纯 UI 组件，被 VRPlayerScreen 的设置面板与快捷面板共用。
 */

/**
 * v92: 带长按提示的图标按钮（控制栏统一组件）
 * 长按显示功能名称 Tooltip；选中态背景色 0x55 对比度高于原 0x33。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipIconButton(
    tooltip: String,
    onClick: () -> Unit,
    icon: ImageVector,
    iconSize: Dp = 20.dp,
    isActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    val tooltipState = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = tooltipState
    ) {
        IconButton(
            onClick = onClick,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (isActive) Color(0x55D0BCFF) else Color.White.copy(alpha = 0.08f),
                contentColor = if (isActive) Color(0xFFD0BCFF) else Color.White
            ),
            modifier = modifier.size(40.dp)
        ) {
            Icon(imageVector = icon, contentDescription = tooltip, modifier = Modifier.size(iconSize))
        }
    }
}

/** 带百分比读数的滑杆行（美颜/人像精修参数统一组件） */
@Composable
fun BeautySliderItem(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    accentColor: Color = Color(0xFFD0BCFF),
    enabled: Boolean = true,
    badge: String? = null
) {
    Column(modifier = if (enabled) Modifier else Modifier.alpha(0.35f)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (badge != null) {
                    Text(badge, color = Color(0xFFE8A33D), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Text("${(value * 100).toInt()}%", color = accentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = Color.White.copy(alpha = 0.15f)
            ),
            modifier = Modifier.height(26.dp)
        )
    }
}

/** 实验性开关行（8K 硬解等，全部默认关闭） */
@Composable
fun ExperimentalSwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onChanged: (Boolean) -> Unit,
    accentColor: Color,
    accentOnColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = desc,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 9.sp,
                lineHeight = 12.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChanged,
            colors = SwitchDefaults.colors(
                checkedThumbColor = accentOnColor,
                checkedTrackColor = accentColor,
                uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
            ),
            modifier = Modifier.height(26.dp)
        )
    }
}
