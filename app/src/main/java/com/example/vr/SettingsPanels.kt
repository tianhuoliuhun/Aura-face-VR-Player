package com.example.vr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

/**
 * v2.6.7：设置面板的容器与页脚（从 `VRPlayerScreen` 搬出，布局与行为未改）。
 *
 * ## 为什么用显式参数而不是 CompositionLocal
 * 试过 Local 方案（`LocalSettingsUi` + `CompositionLocalProvider`），但注入点必须包住
 * 设置面板的 `Card` 块，而那个块的 `Card(...)` 参数里含有 `{}` 空 lambda，
 * 脚本按花括号配平会算错 —— 而手工改又要把 2000 行内容整体缩进。
 *
 * 显式参数虽然让 10 处调用变长，但**错误在编译期就暴露**，不会出现运行时
 * 「Local 未提供」这种线上崩溃。对这个 9642 行的文件来说，可预测性更重要。
 *
 * ⚠️ 布局约束是有意为之（v2.1.213 修过两次），改的时候别动 maxLines。
 */
@Composable
fun SettingsGroup(
    title: String,
    key: String,
    icon: ImageVector? = null,
    expandedGroups: Set<String>,
    onToggle: (String) -> Unit,
    accentColor: Color,
    onInteract: () -> Unit,
    content: @Composable () -> Unit
) {
    val expanded = key in expandedGroups
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "settingsArrow"
    )
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (expanded) Color.White.copy(alpha = 0.07f)
                    else Color.White.copy(alpha = 0.03f)
                )
                .clickable {
                    onToggle(key)
                    onInteract()
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // v2.6.4：分组标题补图标 —— 10 个分组纯文字时辨识度低，
            // 折叠状态下只能靠读标题区分；图标 + 12sp 让一级/二级层级更清楚。
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (expanded) accentColor else Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = title,
                color = if (expanded) accentColor else Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) {
                    stringResource(R.string.action_collapse)
                } else {
                    stringResource(R.string.action_expand)
                },
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = arrowRotation }
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(modifier = Modifier.padding(top = 8.dp)) { content() }
        }
    }
}

