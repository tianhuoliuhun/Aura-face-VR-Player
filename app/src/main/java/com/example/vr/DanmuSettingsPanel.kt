package com.example.vr

import com.example.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AI 弹幕设置面板（v2.2.0 / P1）
 *
 * 结构照 `SubtitleSettingsPanel.kt` 的既定范式（无自持状态，全部参数由调用方传入/回写），
 * 避免出现「同一功能两份 UI」这一项目头号事故源。
 *
 * ⚠️ 本面板**不持有** SharedPreferences：落盘由调用方（VRPlayerScreen）按三处同步规则完成。
 * ⚠️ 配色与 `SubtitleSettingsPanel` 一致：一律用 `Color.White` 的不同 alpha，
 *    不引入主题变量（该面板在播放页浮层中使用，背景恒为深色）。
 */
@Composable
fun DanmuSettingsPanel(
    config: DanmuConfig,
    onConfigChange: (DanmuConfig) -> Unit,
    /** 记忆模式关时禁止写入敏感项（与项目既有 is_memory_mode_enabled 门控一致） */
    canPersistSecrets: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // ===== 总开关 =====
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.danmu_enable_title),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.danmu_enable_desc),
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 11.sp
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = config.isEnabled,
                onCheckedChange = { onConfigChange(config.copy(isEnabled = it)) }
            )
        }

        // ===== 视觉模型 =====
        DanmuSubTitle(stringResource(R.string.danmu_section_model))

        OutlinedTextField(
            value = config.baseUrl,
            onValueChange = { onConfigChange(config.copy(baseUrl = it)) },
            label = { Text(stringResource(R.string.danmu_base_url)) },
            singleLine = true,
            enabled = canPersistSecrets,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = config.modelName,
            onValueChange = { onConfigChange(config.copy(modelName = it)) },
            label = { Text(stringResource(R.string.danmu_model_name)) },
            singleLine = true,
            enabled = canPersistSecrets,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = config.apiKey,
            onValueChange = { onConfigChange(config.copy(apiKey = it)) },
            label = { Text(stringResource(R.string.danmu_api_key)) },
            singleLine = true,
            enabled = canPersistSecrets,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        if (!canPersistSecrets) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.danmu_memory_mode_hint),
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
            }
        }

        // ===== 人格提示词 =====
        DanmuSubTitle(stringResource(R.string.danmu_section_persona))
        OutlinedTextField(
            value = config.personaPrompt,
            onValueChange = { onConfigChange(config.copy(personaPrompt = it)) },
            label = { Text(stringResource(R.string.danmu_persona)) },
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(R.string.danmu_persona_hint),
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        // ===== 运行参数 =====
        DanmuSubTitle(stringResource(R.string.danmu_section_runtime))

        DanmuSliderRow(
            title = stringResource(R.string.danmu_interval),
            valueText = "${config.intervalSec}s",
            value = config.intervalSec.toFloat(),
            range = DanmuConfig.MIN_INTERVAL_SEC.toFloat()..DanmuConfig.MAX_INTERVAL_SEC.toFloat(),
            steps = (DanmuConfig.MAX_INTERVAL_SEC - DanmuConfig.MIN_INTERVAL_SEC) - 1,
            onChange = { onConfigChange(config.copy(intervalSec = it.toInt())) }
        )
        DanmuSliderRow(
            title = stringResource(R.string.danmu_batch_size),
            valueText = config.batchSize.toString(),
            value = config.batchSize.toFloat(),
            range = DanmuConfig.MIN_BATCH_SIZE.toFloat()..DanmuConfig.MAX_BATCH_SIZE.toFloat(),
            steps = DanmuConfig.MAX_BATCH_SIZE - DanmuConfig.MIN_BATCH_SIZE - 1,
            onChange = { onConfigChange(config.copy(batchSize = it.toInt())) }
        )

        // ===== 显示参数 =====
        DanmuSubTitle(stringResource(R.string.danmu_section_display))

        DanmuSliderRow(
            title = stringResource(R.string.danmu_speed),
            valueText = config.speedPxPerSec.toString(),
            value = config.speedPxPerSec.toFloat(),
            range = 60f..600f,
            steps = 8,
            onChange = { onConfigChange(config.copy(speedPxPerSec = it.toInt())) }
        )
        DanmuSliderRow(
            title = stringResource(R.string.danmu_max_tracks),
            valueText = config.maxTracks.toString(),
            value = config.maxTracks.toFloat(),
            range = 2f..DanmuConfig.MAX_TRACKS_LIMIT.toFloat(),
            steps = DanmuConfig.MAX_TRACKS_LIMIT - 3,
            onChange = { onConfigChange(config.copy(maxTracks = it.toInt())) }
        )
        DanmuSliderRow(
            title = stringResource(R.string.danmu_opacity),
            valueText = "${config.opacityPercent}%",
            value = config.opacityPercent.toFloat(),
            range = 20f..100f,
            steps = 7,
            onChange = { onConfigChange(config.copy(opacityPercent = it.toInt())) }
        )
        DanmuSliderRow(
            title = stringResource(R.string.danmu_font_size),
            valueText = config.fontSizeSp.toString(),
            value = config.fontSizeSp.toFloat(),
            range = 12f..32f,
            steps = 19,
            onChange = { onConfigChange(config.copy(fontSizeSp = it.toInt())) }
        )

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.danmu_privacy_hint),
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 10.sp
        )
    }
}

@Composable
private fun DanmuSubTitle(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
    )
}

@Composable
private fun DanmuSliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps.coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
