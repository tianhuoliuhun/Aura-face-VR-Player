package com.example.vr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

/**
 * IJK 内核的参数面板（v2.1.233 引入，v2.1.234 改为复用共用控件）。
 *
 * ⚠️ 抽成独立文件而不是内联在 VRPlayerScreen 里，是为了避免设置面板那边
 *    再长出一坨 200 行的 Column —— 本项目已经因为「同功能两份实现」出过 6 次事。
 *
 * ⚠️ v2.1.234：开关与档位控件**改成复用 [DecoderToggle] / [ParamChoiceRow]**
 *    （`DecoderParamWidgets.kt`）。这两个控件原先在本文件里是私有实现，
 *    加 MPV 面板时如果不抽出来就会长出第二份 —— 那正是本项目反复出事的模式。
 *    本文件现在只负责「IJK 有哪些参数」，不再管控件长什么样。
 *
 * ⚠️ 改任何一项都会直接写回 `ijkOptions` state；该 state 是重建 effect 的 key，
 *    因此**改完会自动重建播放器并从原位置续播**，不需要额外的「应用」按钮。
 */
@Composable
internal fun IjkOptionsPanel(
    options: IjkOptions,
    onOptionsChange: (IjkOptions) -> Unit,
    accentColor: Color,
    accentOnColor: Color,
    /** 「缓冲上限 = 默认」那一档的文案。⚠️ 由调用方用 stringResource 算好再传进来 ——
     *  档位是在 `forEach { }` 的**非 Composable lambda** 里渲染的，
     *  在那种作用域里调 @Composable 函数会直接编译失败。 */
    defaultBufferLabel: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        DecoderToggle(
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
                lineHeight = 9.sp
            )
        }
        DecoderToggle(
            label = stringResource(R.string.ijk_param_framedrop),
            checked = options.frameDrop,
            accentColor = accentColor,
            onCheckedChange = { onOptionsChange(options.copy(frameDrop = it)) }
        )
        DecoderToggle(
            label = stringResource(R.string.ijk_param_accurate_seek),
            checked = options.accurateSeek,
            accentColor = accentColor,
            onCheckedChange = { onOptionsChange(options.copy(accurateSeek = it)) }
        )
        ParamChoiceRow(
            label = stringResource(R.string.ijk_param_buffer),
            choices = IjkOptions.BUFFER_CHOICES,
            selected = options.maxBufferBytes,
            accentColor = accentColor,
            accentOnColor = accentOnColor,
            // 普通函数：0 = 交回 ijk 默认；其余直接显示 MB 数（数字无需翻译）
            labelOf = { bytes -> if (bytes <= 0L) defaultBufferLabel else "${bytes / 1024 / 1024}MB" },
            onSelect = { bytes -> onOptionsChange(options.copy(maxBufferBytes = bytes)) }
        )
    }
}
