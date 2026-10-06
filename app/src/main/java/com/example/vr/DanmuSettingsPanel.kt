package com.example.vr

import com.example.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AI 弹幕设置面板（v2.2.0 / P1，v2.4.2 起与字幕面板统一范式）
 *
 * 结构照 `SubtitleSettingsPanel.kt` 的既定范式（无自持状态，全部参数由调用方传入/回写），
 * 避免出现「同一功能两份 UI」这一项目头号事故源。
 *
 * ⚠️ 本面板**不持有** SharedPreferences：落盘由调用方（VRPlayerScreen）按三处同步规则完成。
 * ⚠️ 配色与 `SubtitleSettingsPanel` 一致：一律用 `Color.White` 的不同 alpha，
 *    不引入主题变量（该面板在播放页浮层中使用，背景恒为深色）。
 *
 * ## v2.4.2 改动：向字幕面板看齐
 * 用户反馈「AI 字幕的 UI 不统一，复用其他 UI」→ 澄清后为**弹幕面板向字幕面板看齐**：
 * 1. 七个纯文字小标题（`DanmuSubTitle`）→ 可折叠区块 [`SubtitleSection`]
 *    （图标 + 标题 + 折叠摘要 + 展开动画），展开状态持久化到 prefs（本面板负责读写）。
 * 2. 四个裸 `OutlinedTextField` → 加 [`OutlinedTextFieldDefaults.colors`] 深色定制。
 * 3. 裸 `Slider` → 加 [`SliderDefaults.colors`] 定制（与字幕面板同口径）。
 * 4. 新增「预设风格」chip 行（v2.4.2 需求：增加预设人格），点选填入提示词、仍可手改。
 */
@Composable
fun DanmuSettingsPanel(
    config: DanmuConfig,
    onConfigChange: (DanmuConfig) -> Unit,
    /** 记忆模式关时禁止写入敏感项（与项目既有 is_memory_mode_enabled 门控一致） */
    canPersistSecrets: Boolean,
    /** v2.3.1：已生成的弹幕累计条数（渲染层未接前，用于确认「取帧→请求→入队」链路已通） */
    generatedCount: Int = 0,
    /** v2.3.1：最近一次失败原因（空串 = 无错误） */
    lastError: String = "",
    /**
     * v2.4.2：主题强调色（与字幕面板同一入口，由调用方传 `AccentColor`）。
     * 默认值仅在预览/测试下生效。
     */
    accentColor: Color = Color(0xFFD0BCFF),
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
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

        // ===== v2.3.1：运行状态（仅在开启时显示）=====
        if (config.isEnabled) {
            DanmuStatusCard(
                isConfigured = config.isReadyToRequest(),
                generatedCount = generatedCount,
                lastError = lastError
            )
        }

        // ===== v2.4.2：各折叠区块的展开状态（照字幕面板范式，持久化到同一 prefs）=====
        // 默认：视觉模型 / 人格 展开（高频），其余折叠 —— 让面板一进来不至于太长。
        val sectionPrefs = remember {
            context.getSharedPreferences("vr_player_prefs", android.content.Context.MODE_PRIVATE)
        }
        var secModelExpanded by remember {
            mutableStateOf(sectionPrefs.getBoolean("danmu_section_expanded_model", true))
        }
        var secPersonaExpanded by remember {
            mutableStateOf(sectionPrefs.getBoolean("danmu_section_expanded_persona", true))
        }
        var secSourceExpanded by remember {
            mutableStateOf(sectionPrefs.getBoolean("danmu_section_expanded_source", false))
        }
        var secRuntimeExpanded by remember {
            mutableStateOf(sectionPrefs.getBoolean("danmu_section_expanded_runtime", false))
        }
        var secDisplayExpanded by remember {
            mutableStateOf(sectionPrefs.getBoolean("danmu_section_expanded_display", false))
        }
        var secColorExpanded by remember {
            mutableStateOf(sectionPrefs.getBoolean("danmu_section_expanded_color", false))
        }
        fun setSectionExpanded(id: String, value: Boolean) {
            when (id) {
                "model" -> secModelExpanded = value
                "persona" -> secPersonaExpanded = value
                "source" -> secSourceExpanded = value
                "runtime" -> secRuntimeExpanded = value
                "display" -> secDisplayExpanded = value
                "color" -> secColorExpanded = value
            }
            sectionPrefs.edit().putBoolean("danmu_section_expanded_$id", value).apply()
        }

        // ===== 视觉模型 =====
        val modelSummary = config.modelName.ifBlank { stringResource(R.string.danmu_section_model) }
        SubtitleSection(
            id = "model",
            title = stringResource(R.string.danmu_section_model),
            icon = Icons.Default.Psychology,
            accentColor = accentColor,
            expanded = secModelExpanded,
            onToggle = { setSectionExpanded("model", !secModelExpanded) },
            summary = modelSummary,
            tagPrefix = "danmu"
        ) {

        OutlinedTextField(
            value = config.baseUrl,
            onValueChange = { onConfigChange(config.copy(baseUrl = it)) },
            label = { Text(stringResource(R.string.danmu_base_url), fontSize = 8.sp) },
            placeholder = { Text(DanmuConfig.DEFAULT_BASE_URL, fontSize = 8.sp) },
            singleLine = true,
            enabled = canPersistSecrets,
            textStyle = DanmuFieldTextStyle,
            colors = DanmuTextFieldColors(accentColor),
            // ⚠️ v2.4.3：**不设固定高度**。v2.4.2 用 height(48.dp) 导致
            //    「文字下沉贴下边框 / label 被下边线穿过」（M3 单行输入框
            //    含 label 浮动动画需要约 56dp）。交给组件自身默认高度。
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = config.modelName,
            onValueChange = { onConfigChange(config.copy(modelName = it)) },
            label = { Text(stringResource(R.string.danmu_model_name), fontSize = 8.sp) },
            placeholder = { Text(DanmuConfig.DEFAULT_MODEL, fontSize = 8.sp) },
            singleLine = true,
            enabled = canPersistSecrets,
            textStyle = DanmuFieldTextStyle,
            colors = DanmuTextFieldColors(accentColor),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = config.apiKey,
            onValueChange = { onConfigChange(config.copy(apiKey = it)) },
            label = { Text(stringResource(R.string.danmu_api_key), fontSize = 8.sp) },
            placeholder = { Text("sk-...", fontSize = 8.sp) },
            singleLine = true,
            enabled = canPersistSecrets,
            visualTransformation = PasswordVisualTransformation(),
            textStyle = DanmuFieldTextStyle,
            colors = DanmuTextFieldColors(accentColor),
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
        }

        // ===== 人格提示词（v2.4.2：加预设 chip 行）=====
        val personaPresetId = DanmuConfig.matchPersonaPresetId(config.personaPrompt)
        val personaSummary = when (personaPresetId) {
            DanmuConfig.PERSONA_CUSTOM_ID -> stringResource(R.string.danmu_persona_preset_custom)
            else -> DanmuConfig.PERSONA_PRESETS
                .firstOrNull { it.id == personaPresetId }
                ?.let { stringResource(it.labelRes) }
                ?: stringResource(R.string.danmu_persona_preset_custom)
        }
        SubtitleSection(
            id = "persona",
            title = stringResource(R.string.danmu_section_persona),
            icon = Icons.Default.Person,
            accentColor = accentColor,
            expanded = secPersonaExpanded,
            onToggle = { setSectionExpanded("persona", !secPersonaExpanded) },
            summary = personaSummary,
            tagPrefix = "danmu"
        ) {
        // 预设风格：一行 chip 选填（点选即把提示词写进输入框，**不锁定**，仍可手改）。
        // 「自定义」不由用户点选 —— 它只在用户手改到与任何预设都不相等时自动高亮。
        DanmuTextChipRow(
            title = stringResource(R.string.danmu_persona_preset),
            options = DanmuConfig.PERSONA_PRESETS,
            selectedId = personaPresetId,
            labelOf = { stringResource(it.labelRes) },
            onPick = { presetId ->
                DanmuConfig.PERSONA_PRESETS.firstOrNull { it.id == presetId }?.let {
                    onConfigChange(config.copy(personaPrompt = it.prompt))
                }
            }
        )
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = config.personaPrompt,
            onValueChange = { onConfigChange(config.copy(personaPrompt = it)) },
            label = { Text(stringResource(R.string.danmu_persona), fontSize = 8.sp) },
            minLines = 3,
            maxLines = 8,
            textStyle = DanmuFieldTextStyle,
            colors = DanmuTextFieldColors(accentColor),
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(R.string.danmu_persona_hint),
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        }

        // ===== v2.4.1：素材来源（画面 / 台词 / 两者）=====
        val sourceSummary = DanmuSourceMode.values()
            .firstOrNull { it.id == config.sourceModeId }
            ?.let { stringResource(it.labelRes) }
            ?: stringResource(R.string.danmu_section_source)
        SubtitleSection(
            id = "source",
            title = stringResource(R.string.danmu_section_source),
            icon = Icons.Default.Source,
            accentColor = accentColor,
            expanded = secSourceExpanded,
            onToggle = { setSectionExpanded("source", !secSourceExpanded) },
            summary = sourceSummary,
            tagPrefix = "danmu"
        ) {
        DanmuTextChipRow(
            title = stringResource(R.string.danmu_source),
            options = DanmuSourceMode.values().toList(),
            selectedId = config.sourceModeId,
            labelOf = { stringResource(it.labelRes) },
            onPick = { onConfigChange(config.copy(sourceModeId = it)) }
        )
        Text(
            text = stringResource(R.string.danmu_source_hint),
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        }

        // ===== 运行参数 =====
        SubtitleSection(
            id = "runtime",
            title = stringResource(R.string.danmu_section_runtime),
            icon = Icons.Default.Settings,
            accentColor = accentColor,
            expanded = secRuntimeExpanded,
            onToggle = { setSectionExpanded("runtime", !secRuntimeExpanded) },
            summary = "${config.intervalSec}s · ${config.batchSize}",
            tagPrefix = "danmu"
        ) {
        DanmuSliderRow(
            title = stringResource(R.string.danmu_interval),
            valueText = "${config.intervalSec}s",
            value = config.intervalSec.toFloat(),
            range = DanmuConfig.MIN_INTERVAL_SEC.toFloat()..DanmuConfig.MAX_INTERVAL_SEC.toFloat(),
            steps = (DanmuConfig.MAX_INTERVAL_SEC - DanmuConfig.MIN_INTERVAL_SEC) - 1,
            accentColor = accentColor,
            onChange = { onConfigChange(config.copy(intervalSec = it.toInt())) }
        )
        DanmuSliderRow(
            title = stringResource(R.string.danmu_batch_size),
            valueText = config.batchSize.toString(),
            value = config.batchSize.toFloat(),
            range = DanmuConfig.MIN_BATCH_SIZE.toFloat()..DanmuConfig.MAX_BATCH_SIZE.toFloat(),
            steps = DanmuConfig.MAX_BATCH_SIZE - DanmuConfig.MIN_BATCH_SIZE - 1,
            accentColor = accentColor,
            onChange = { onConfigChange(config.copy(batchSize = it.toInt())) }
        )
        }

        // ===== 显示参数 =====
        SubtitleSection(
            id = "display",
            title = stringResource(R.string.danmu_section_display),
            icon = Icons.Default.Visibility,
            accentColor = accentColor,
            expanded = secDisplayExpanded,
            onToggle = { setSectionExpanded("display", !secDisplayExpanded) },
            summary = "${config.opacityPercent}% · ${config.fontSizeSp}sp",
            tagPrefix = "danmu"
        ) {
        DanmuSliderRow(
            title = stringResource(R.string.danmu_speed),
            valueText = config.speedPxPerSec.toString(),
            value = config.speedPxPerSec.toFloat(),
            range = 60f..600f,
            steps = 8,
            accentColor = accentColor,
            onChange = { onConfigChange(config.copy(speedPxPerSec = it.toInt())) }
        )
        DanmuSliderRow(
            title = stringResource(R.string.danmu_max_tracks),
            valueText = config.maxTracks.toString(),
            value = config.maxTracks.toFloat(),
            range = 2f..DanmuConfig.MAX_TRACKS_LIMIT.toFloat(),
            steps = DanmuConfig.MAX_TRACKS_LIMIT - 3,
            accentColor = accentColor,
            onChange = { onConfigChange(config.copy(maxTracks = it.toInt())) }
        )
        DanmuSliderRow(
            title = stringResource(R.string.danmu_opacity),
            valueText = "${config.opacityPercent}%",
            value = config.opacityPercent.toFloat(),
            range = 20f..100f,
            steps = 7,
            accentColor = accentColor,
            onChange = { onConfigChange(config.copy(opacityPercent = it.toInt())) }
        )
        DanmuSliderRow(
            title = stringResource(R.string.danmu_font_size),
            valueText = config.fontSizeSp.toString(),
            value = config.fontSizeSp.toFloat(),
            range = 12f..32f,
            steps = 19,
            accentColor = accentColor,
            onChange = { onConfigChange(config.copy(fontSizeSp = it.toInt())) }
        )
        }

        // ===== v2.3.0：全局颜色（作用于全部弹幕）=====
        SubtitleSection(
            id = "color",
            title = stringResource(R.string.danmu_section_color),
            icon = Icons.Default.Palette,
            accentColor = accentColor,
            expanded = secColorExpanded,
            onToggle = { setSectionExpanded("color", !secColorExpanded) },
            summary = stringResource(R.string.danmu_text_color),
            tagPrefix = "danmu"
        ) {
        // 文字颜色：色块网格（照 SubtitleSettingsPanel 的既有交互）
        DanmuColorRow(
            title = stringResource(R.string.danmu_text_color),
            options = SubtitleColorOption.values().toList(),
            selectedId = config.textColorId,
            swatchColor = { it.color },
            labelOf = { stringResource(it.labelRes) },
            onPick = { onConfigChange(config.copy(textColorId = it)) }
        )

        // 描边
        DanmuTextChipRow(
            title = stringResource(R.string.danmu_stroke),
            options = SubtitleStrokeOption.values().toList(),
            selectedId = config.strokeId,
            labelOf = { stringResource(it.labelRes) },
            onPick = { onConfigChange(config.copy(strokeId = it)) }
        )

        // 背景底
        DanmuTextChipRow(
            title = stringResource(R.string.danmu_bg),
            options = SubtitleBgOption.values().toList(),
            selectedId = config.bgId,
            labelOf = { stringResource(it.labelRes) },
            onPick = { onConfigChange(config.copy(bgId = it)) }
        )

        Text(
            text = stringResource(R.string.danmu_color_hint),
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.danmu_privacy_hint),
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 10.sp
        )
    }
}

/**
 * v2.3.1：运行状态卡片。
 *
 * 让用户能**一眼看出链路是否通了** —— 本版弹幕还没有渲染层（P5 未做），
 * 若不显示状态，用户开启后会以为「没反应 = 坏了」。
 */
@Composable
private fun DanmuStatusCard(
    isConfigured: Boolean,
    generatedCount: Int,
    lastError: String
) {
    val statusColor = when {
        !isConfigured -> Color(0xFFFFB74D)   // 橙：配置不全
        lastError.isNotEmpty() -> Color(0xFFEF5350)  // 红：最近一次失败
        generatedCount > 0 -> Color(0xFF81C784)      // 绿：已产出
        else -> Color.White.copy(alpha = 0.6f)       // 灰：等待首次结果
    }
    val statusText = when {
        !isConfigured -> stringResource(R.string.danmu_status_not_configured)
        lastError.isNotEmpty() -> lastError
        generatedCount > 0 -> stringResource(R.string.danmu_status_generated, generatedCount)
        else -> stringResource(R.string.danmu_status_waiting)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(statusColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.danmu_status_title),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            text = statusText,
            color = statusColor,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
        // v2.4.0：弹幕**已上屏**（渲染层 DanmuOverlay），此提示改为告知「没看到时怎么办」
        Text(
            text = stringResource(R.string.danmu_status_render_pending),
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 10.sp,
            lineHeight = 14.sp
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
}

/**
 * v2.4.2：输入框深色定制配色（与 `SubtitleSettingsPanel` 同一口径）。
 *
 * 字幕面板里这段 colors 是**逐处内联**的（6 处）；弹幕面板有 4 处，
 * 抽成一个函数，避免「同一件事写四遍」——本项目头号事故源。
 */
@Composable
private fun DanmuTextFieldColors(accentColor: Color) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = accentColor,
    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
    focusedLabelColor = accentColor,
    unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    // ⚠️ v2.4.2 修复：placeholder 与光标也要跟着深色主题，否则默认深色在深底上看不见
    focusedPlaceholderColor = Color.White.copy(alpha = 0.3f),
    unfocusedPlaceholderColor = Color.White.copy(alpha = 0.3f),
    cursorColor = accentColor
)

/**
 * v2.4.2：输入框文字样式。
 *
 * ⚠️ **这一条是「文字被切一半」的真正修复**。
 *    在 `Modifier.height(48.dp)` 的约束下，若正文/标签用默认的 16sp，
 *    单行输入框内部可用高度不够 → 文字**上下都被裁掉**（截图现象）。
 *    字幕面板之所以没事，是因为它每处 label 都显式写了 `fontSize = 8.sp`。
 *    这里统一用 12sp 正文 + 8sp 标签，与字幕面板完全一致。
 */
private val DanmuFieldTextStyle = androidx.compose.ui.text.TextStyle(
    fontSize = 12.sp,
    color = Color.White
)

@Composable
private fun DanmuSliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    accentColor: Color,
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
            // v2.4.2：与字幕面板统一，活动轨用主题强调色
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 带色块的选项网格（用于**文字颜色**这类需要直观看到颜色的选择）。
 *
 * 泛型 T 让「颜色 / 描边 / 背景」三种枚举共用同一份实现 —— 避免为每种枚举
 * 各写一份（「同一功能两份 UI」是本项目头号事故源）。
 */
@Composable
private fun <T> DanmuColorRow(
    title: String,
    options: List<T>,
    selectedId: Int,
    swatchColor: (T) -> Color,
    labelOf: @Composable (T) -> String,
    onPick: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        options.chunked(4).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowOptions.forEach { opt ->
                    val id = colorOptionId(opt)
                    val isSelected = id == selectedId
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isSelected) Color.White.copy(alpha = 0.18f)
                                else Color.White.copy(alpha = 0.05f)
                            )
                            .clickable { onPick(id) }
                            .padding(vertical = 5.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(swatchColor(opt))
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = labelOf(opt),
                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.65f),
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                repeat(4 - rowOptions.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 纯文字 chip 行（用于**描边 / 背景**这类无需色块的选择）。
 * 同样泛型复用，三个枚举共用一份实现。
 */
@Composable
private fun <T> DanmuTextChipRow(
    title: String,
    options: List<T>,
    selectedId: Int,
    labelOf: @Composable (T) -> String,
    onPick: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        options.chunked(3).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                rowOptions.forEach { opt ->
                    val id = colorOptionId(opt)
                    val isSelected = id == selectedId
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isSelected) Color.White.copy(alpha = 0.18f)
                                else Color.White.copy(alpha = 0.05f)
                            )
                            .clickable { onPick(id) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = labelOf(opt),
                            color = if (isSelected) Color.White else Color.White.copy(alpha = 0.65f),
                            fontSize = 9.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                repeat(3 - rowOptions.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 从各个带 `id` 属性的枚举之一取出 `id`。
 *
 * ⚠️ 这些枚举都有 `id` 属性，但 Kotlin 泛型无法直接访问 —— 用 `when` 显式分发。
 *    **新增枚举时必须在此补一行**：`else -> 0` 会让新枚举静默全部选中 0 号项
 *    （表现为「点了没反应」），因此这里刻意保留 else 兜底并把分支写全。
 */
private fun colorOptionId(opt: Any?): Int = when (opt) {
    is SubtitleColorOption -> opt.id
    is SubtitleStrokeOption -> opt.id
    is SubtitleBgOption -> opt.id
    is DanmuSourceMode -> opt.id
    // v2.4.2：预设人格 chip 行（选中 id 用 DanmuConfig.matchPersonaPresetId 匹配）
    is DanmuPersonaPreset -> opt.id
    else -> 0
}
