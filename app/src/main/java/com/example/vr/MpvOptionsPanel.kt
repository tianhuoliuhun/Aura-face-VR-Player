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
 * MPV 内核的参数面板 —— v2.1.234。
 *
 * 开关与档位控件来自共用的 [DecoderToggle] / [ParamChoiceRow]
 * （见 `DecoderParamWidgets.kt` 的说明：不再各写一份）。
 *
 * ⚠️ 改任一项都会写回 `mpvOptions` state，而它是重建 effect 的 key 之一 →
 *    **改完会自动重建播放器并从原位置续播**，不需要额外的「应用」按钮。
 */
@Composable
internal fun MpvOptionsPanel(
    options: MpvOptions,
    onOptionsChange: (MpvOptions) -> Unit,
    accentColor: Color,
    accentOnColor: Color,
    /** 「缓冲 = 默认」那一档的文案。由调用方算好传入（见 DecoderParamWidgets 的约束 1）。 */
    defaultLabel: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        DecoderToggle(
            label = stringResource(R.string.mpv_param_hwdec),
            checked = options.hwdec,
            accentColor = accentColor,
            onCheckedChange = { onOptionsChange(options.copy(hwdec = it)) }
        )
        // ⚠️ 这一段是**关键提示**：mediacodec_embed 这个 vo 自己不解码，
        //    它只是"把解码结果投到 Surface"的通路。关掉硬解就没有帧产出（黑屏）。
        if (!options.hwdec) {
            Text(
                text = stringResource(R.string.mpv_param_hwdec_hint),
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 7.sp,
                lineHeight = 9.sp
            )
        }
        // v2.1.244：说明 vo 的二段兜底（EMBED 只吃硬解帧 → 无硬解器的老编码会自动切 GPU）
        Text(
            text = stringResource(R.string.mpv_vo_mode_hint),
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 7.sp,
            lineHeight = 9.sp
        )
        DecoderToggle(
            label = stringResource(R.string.mpv_param_framedrop),
            checked = options.frameDrop,
            accentColor = accentColor,
            onCheckedChange = { onOptionsChange(options.copy(frameDrop = it)) }
        )
        ParamChoiceRow(
            label = stringResource(R.string.mpv_param_cache),
            choices = MpvOptions.CACHE_CHOICES,
            selected = options.cacheMb,
            accentColor = accentColor,
            accentOnColor = accentOnColor,
            labelOf = { mb -> if (mb <= 0) defaultLabel else "${mb}MB" },
            onSelect = { mb -> onOptionsChange(options.copy(cacheMb = mb)) }
        )
    }
}
