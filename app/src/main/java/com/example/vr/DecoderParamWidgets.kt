package com.example.vr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 解码内核参数面板的**共用控件** —— v2.1.234。
 *
 * ## 为什么要抽出来
 * IJK 与 MPV 的参数面板是同一套交互（开关行 + 档位行），如果各写一遍就是本项目
 * 反复踩的第 7 次「同一功能两份实现」—— 改一处必漏另一处。
 * 所以开关与档位都收敛到这里，两个面板只负责"有哪些参数"。
 *
 * ## 两个使用约束（都是踩过的）
 * 1. **`label` 必须由调用方用 `stringResource` 算好再传进来**：档位是在
 *    `forEach { }` 的**非 Composable lambda** 里渲染的，在那种作用域里直接调
 *    `@Composable` 函数会编译失败。
 * 2. 开关是自绘的小胶囊而**不用 `Switch` 控件** —— 面板字号只有 8sp，
 *    系统 Switch 的视觉尺寸会和整体完全不搭。
 */

/** 一行"标签 + 自绘开关"，整行可点。 */
@Composable
internal fun DecoderToggle(
    label: String,
    checked: Boolean,
    accentColor: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 8.sp,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .background(if (checked) accentColor else Color.White.copy(alpha = 0.10f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = if (checked) "ON" else "OFF",
                color = if (checked) Color.White else Color.White.copy(alpha = 0.45f),
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 一行"标签 + 若干档位"（单选）。泛型化是因为各内核的档位类型不同
 * （ijk 用 Long 表示字节数，mpv 用 Int 表示 MB）。
 *
 * @param labelOf 把档位值转成显示文案。**必须是普通函数**（见类注释第 1 条）。
 */
@Composable
internal fun <T> ParamChoiceRow(
    label: String,
    choices: List<T>,
    selected: T,
    accentColor: Color,
    accentOnColor: Color,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 8.sp,
            modifier = Modifier.weight(1f)
        )
        choices.forEach { c ->
            val isSel = c == selected
            val text = labelOf(c)   // 普通函数，非 Composable 作用域内也安全
            Box(
                modifier = Modifier
                    .padding(start = 3.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isSel) accentColor else Color.White.copy(alpha = 0.06f))
                    .clickable { onSelect(c) }
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    text = text,
                    color = if (isSel) accentOnColor else Color.White.copy(alpha = 0.6f),
                    fontSize = 7.sp,
                    fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}
