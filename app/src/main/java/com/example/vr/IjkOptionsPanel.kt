package com.example.vr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// ⚠️ R 在 namespace `com.example` 下（见 build.gradle.kts），与 package `com.example.vr`
//    不同包，必须显式 import —— 否则所有 R.string.* 都是 Unresolved。
import com.example.R

/**
 * IJK 内核的参数面板（v2.1.233：把 v2.1.232 的「参数（预留）」区块接成真实可调）。
 *
 * ⚠️ 抽成独立文件而不是内联在 VRPlayerScreen 里，是为了避免设置面板那边
 *    再长出一坨 200 行的 Column —— 本项目已经因为「同功能两份实现」出过 6 次事。
 *
 * ⚠️ 改任何一项都会直接写回 `ijkOptions` state；该 state 是 Effect B
 *    （`LaunchedEffect(isSoftwareDecoding, decoderEngine, ijkOptions, …)`）的 key，
 *    因此**改完会自动重建播放器并从原位置续播**，不需要额外的「应用」按钮。
 */
@Composable
internal fun IjkOptionsPanel(
    options: IjkOptions,
    onOptionsChange: (IjkOptions) -> Unit,
    accentColor: Color,
    accentOnColor: Color,
    /** 「缓冲上限 = 默认」那一档的文案。⚠️ 由调用方用 stringResource 算好再传进来 ——
     *  下面的档位是在 `forEach { }` 的**非 Composable lambda** 里渲染的，
     *  在那种作用域里调 @Composable 函数会直接编译失败。 */
    defaultBufferLabel: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        IjkToggle(
            label = stringResource(R.string.ijk_param_mediacodec),
            checked = options.mediaCodec,
            accentColor = accentColor,
            onCheckedChange = { onOptionsChange(options.copy(mediaCodec = it)) }
        )
        // 关掉硬解时才提示"这等于切 FFmpeg 软解"—— 开着的时候不用解释
        if (!options.mediaCodec) {
            Text(
                text = stringResource(R.string.ijk_param_hw_hint),
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 7.sp,
                lineHeight = 9.sp,
                modifier = Modifier.padding(start = 2.dp)
            )
        }
        IjkToggle(
            label = stringResource(R.string.ijk_param_framedrop),
            checked = options.frameDrop,
            accentColor = accentColor,
            onCheckedChange = { onOptionsChange(options.copy(frameDrop = it)) }
        )
        IjkToggle(
            label = stringResource(R.string.ijk_param_accurate_seek),
            checked = options.accurateSeek,
            accentColor = accentColor,
            onCheckedChange = { onOptionsChange(options.copy(accurateSeek = it)) }
        )

        // ===== 缓冲上限档位 =====
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.ijk_param_buffer),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 8.sp,
                modifier = Modifier.weight(1f)
            )
            IjkOptions.BUFFER_CHOICES.forEach { bytes ->
                val selected = options.maxBufferBytes == bytes
                // 非 Composable 的普通函数：档位文案在这里算好（不在 Composable 作用域内）
                val label = bufferLabel(bytes, defaultBufferLabel)
                Box(
                    modifier = Modifier
                        .padding(start = 3.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (selected) accentColor else Color.White.copy(alpha = 0.06f))
                        .clickable { onOptionsChange(options.copy(maxBufferBytes = bytes)) }
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = label,
                        color = if (selected) accentOnColor else Color.White.copy(alpha = 0.6f),
                        fontSize = 7.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun IjkToggle(
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
        // 一个极小的开关样式（与面板 8sp 的紧凑风格一致，不用 Switch 控件——太大）
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
 * 普通函数（**不是** @Composable）：档位文案。
 * 0 = 交回 ijk 默认；其余直接显示 MB 数（数字无需翻译）。
 */
private fun bufferLabel(bytes: Long, defaultLabel: String): String {
    if (bytes <= 0L) return defaultLabel
    return "${bytes / 1024 / 1024}MB"
}
