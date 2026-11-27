package com.example.vr

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

@Composable
internal fun PlayerSettingsHeader(
    accentColor: Color,
    onOpenSmb: () -> Unit,
    onShowVideoInfo: () -> Unit,
    onOpenTrackDialog: () -> Unit,
    onClose: () -> Unit,
) {
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = null,
            tint = accentColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.settings_dialog_title),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        // v92: 从控制栏移入的工具按钮（局域网/视频信息/音轨选择）
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(
            onClick = { onOpenSmb() },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = stringResource(R.string.action_lan_play),
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp)
            )
        }
        IconButton(
            onClick = { onShowVideoInfo() },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = stringResource(R.string.action_media_info),
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp)
            )
        }
        IconButton(
            onClick = {
                onOpenTrackDialog()
            },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.QueueMusic,
                contentDescription = stringResource(R.string.action_track_select),
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
    IconButton(
        onClick = { onClose() },
        modifier = Modifier.size(36.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = stringResource(R.string.action_close),
            tint = Color.White.copy(alpha = 0.6f)
        )
    }
}
}

@Composable
internal fun PlayerSettings8KSection(
    accentColor: Color,
    accentOnColor: Color,
    decoderEngine: DecoderEngine,
    levelPatchEnabled: Boolean,
    onLevelPatchChange: (Boolean) -> Unit,
    level51Enabled: Boolean,
    onLevel51Change: (Boolean) -> Unit,
    forceHwDecoderEnabled: Boolean,
    onForceHwDecoderChange: (Boolean) -> Unit,
    spoofResolutionEnabled: Boolean,
    onSpoofResolutionChange: (Boolean) -> Unit,
    downscaleOutputEnabled: Boolean,
    onDownscaleOutputChange: (Boolean) -> Unit,
    addCodecParamsEnabled: Boolean,
    onAddCodecParamsChange: (Boolean) -> Unit,
    autoFallbackSoftEnabled: Boolean,
    onAutoFallbackSoftChange: (Boolean) -> Unit,
    onKeepUiAlight: () -> Unit,
) {
Text(
    text = stringResource(R.string.group_8k_hw),
    color = accentColor,
    fontSize = 11.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(top = 4.dp)
)
ExperimentalSwitchRow(
    title = stringResource(R.string.level_patch),
    desc = stringResource(R.string.level_patch_desc),
    checked = levelPatchEnabled,
    onChanged = { onLevelPatchChange(it) },
    accentColor = accentColor,
    accentOnColor = accentOnColor
)
ExperimentalSwitchRow(
    title = stringResource(R.string.level51),
    desc = stringResource(R.string.level51_desc),
    checked = level51Enabled,
    onChanged = { onLevel51Change(it) },
    accentColor = accentColor,
    accentOnColor = accentOnColor
)
ExperimentalSwitchRow(
    title = stringResource(R.string.force_hw_decoder),
    desc = stringResource(R.string.force_hw_decoder_desc),
    checked = forceHwDecoderEnabled,
    onChanged = { onForceHwDecoderChange(it) },
    accentColor = accentColor,
    accentOnColor = accentOnColor
)
ExperimentalSwitchRow(
    title = stringResource(R.string.spoof_resolution),
    desc = stringResource(R.string.spoof_resolution_desc),
    checked = spoofResolutionEnabled,
    onChanged = { onSpoofResolutionChange(it) },
    accentColor = accentColor,
    accentOnColor = accentOnColor
)
ExperimentalSwitchRow(
    title = stringResource(R.string.downscale_output),
    desc = stringResource(R.string.downscale_output_desc),
    checked = downscaleOutputEnabled,
    onChanged = { onDownscaleOutputChange(it) },
    accentColor = accentColor,
    accentOnColor = accentOnColor
)
ExperimentalSwitchRow(
    title = stringResource(R.string.add_codec_params),
    desc = stringResource(R.string.add_codec_params_desc),
    checked = addCodecParamsEnabled,
    onChanged = { onAddCodecParamsChange(it) },
    accentColor = accentColor,
    accentOnColor = accentOnColor
)
ExperimentalSwitchRow(
    title = stringResource(R.string.auto_fallback_soft),
    desc = stringResource(R.string.auto_fallback_soft_desc),
    checked = autoFallbackSoftEnabled,
    onChanged = { onAutoFallbackSoftChange(it) },
    accentColor = accentColor,
    accentOnColor = accentOnColor
)
// v2.6.12：系统编解码能力检测（读取 MediaCodec 上报的能力，
// 列出支持的编码/封装/最大分辨率）。点按钮才扫描，不拖慢设置面板。
CodecCapabilitySection(
    accentColor = accentColor,
    accentOnColor = accentOnColor,
    decoderEngine = decoderEngine,
    onInteract = { onKeepUiAlight() }
)
Box(
    modifier = Modifier
        .fillMaxWidth()
        .background(Color(0x0CFFFFFF), shape = RoundedCornerShape(10.dp))
        .padding(8.dp)
) {
    Text(
        text = stringResource(R.string.panorama_note),
        color = Color.White.copy(alpha = 0.5f),
        fontSize = 9.sp,
        lineHeight = 12.sp
    )
}
}

@Composable
internal fun PlayerSettingsAiSubtitleSection(
    context: Context,
    accentColor: Color,
    accentOnColor: Color,
    sherpaLangCode: String,
    onChangeAsrLanguage: (String) -> Unit,
    onKeepUiAlight: () -> Unit,
) {
// ===== ASR 引擎选择（v111：Vosk / Qwen3-ASR / SenseVoice QNN）=====
Column(
    modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .background(Color.White.copy(alpha = 0.04f))
        .padding(horizontal = 10.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp)
) {
    Text(
        text = stringResource(R.string.asr_engine_title),
        color = accentColor,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // v127：引擎固定为 SenseVoice，不再提供切换按钮
    }
    // 引擎说明
    Text(
        text = stringResource(R.string.asr_engine_sensevoice_desc),
        color = Color.White.copy(alpha = 0.45f),
        fontSize = 9.sp,
        lineHeight = 11.sp
    )
    // sherpa-onnx 引擎语言选择（v111；v127 含 SenseVoice CPU）
    // v2.0.208：多级收纳 —— 支持「按语言 / 按模型」两种分组视角，
    //           语区可折叠，同语言多模型时支持选择模型。
    // 当前生效模型统一解析一次（chips 选中判定要用）
    // v2.4.31：改调 `activeModelIdFor`（与 AI 字幕面板**共用同一实现**，
    //          不再两处各写一遍 `resolveExtModel(...)?.dirName ?: "builtin"`）
    val activeModelId = SherpaAsrManager.activeModelIdFor(context, sherpaLangCode)
    // v2.1.232：语言选择的**唯一实现**在 AsrLanguageChips ——
    // 此前设置面板与字幕悬浮窗各有一份副本，改一处漏一处已发生两次
    //（v2.1.226 内置候选选不中、v2.1.231 四项分类）。现在共用同一份。
    AsrLanguageChips(
        context = context,
        sherpaLangCode = sherpaLangCode,
        activeModelId = activeModelId,
        accentColor = accentColor,
        accentOnColor = accentOnColor,
        onPick = { code, modelId ->
            // ⚠️ 顺序不能反：先切语言、再写模型选择。
            //    同语言换模型时 changeAsrLanguage 会直接 return，
            //    全靠 setModelChoice 触发识别器重建。
            onChangeAsrLanguage(code)
            SherpaAsrManager.setModelChoice(context, code, modelId)
        },
        onUserInteraction = { onKeepUiAlight() }
    )
    // sherpa-onnx 引擎：模型状态 + 下载
    run {
        // v2.0.145：模型信息与就绪状态随所选语言变化
        // （扩展语言如越南语是独立模型，需单独下载，不能沿用 SenseVoice 的判断）
        val langKey = sherpaLangCode
        val modelInfo = SherpaAsrManager.modelInfoFor(context, langKey)
        val sherpaReady = remember(langKey) { mutableStateOf(SherpaAsrManager.isModelReadyFor(context, langKey)) }
        LaunchedEffect(langKey, SherpaAsrManager.isModelDownloading, SherpaAsrManager.modelDownloadProgress) {
            sherpaReady.value = SherpaAsrManager.isModelReadyFor(context, langKey)
        }
        val modelName = modelInfo.first
        val modelDesc = if (modelInfo.third) stringResource(R.string.asr_ext_model_tag) else stringResource(R.string.asr_sensevoice_tag)
        val modelSizeMB = modelInfo.second
        // ⚠️ v2.0.208：多模型的选择已上移到语言 chips（英语1/英语2/英语3
        //    点谁用谁，见上方按语区分组处），这里不再重复显示候选单选。
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            // 模型信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = modelName,
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = modelDesc,
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 9.sp
                    )
                }
                Text(
                    text = when {
                        SherpaAsrManager.isModelDownloading -> stringResource(R.string.asr_downloading)
                        sherpaReady.value -> stringResource(R.string.asr_ready)
                        else -> stringResource(R.string.asr_not_downloaded)
                    },
                    color = when {
                        SherpaAsrManager.isModelDownloading -> Color(0xFF4FC3F7)
                        sherpaReady.value -> Color(0xFF81C784)
                        else -> Color.White
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            // 下载进度
            if (SherpaAsrManager.isModelDownloading) {
                LinearProgressIndicator(
                    progress = SherpaAsrManager.modelDownloadProgress,
                    color = accentColor,
                    trackColor = Color.White.copy(alpha = 0.15f),
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.asr_download_percent, (SherpaAsrManager.modelDownloadProgress * 100).toInt()), color = Color(0xFF4FC3F7), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(if (SherpaAsrManager.downloadStatus.isEmpty()) stringResource(R.string.asr_status_builtin_ready) else SherpaAsrManager.downloadStatus, color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    text = stringResource(R.string.asr_cancel_download),
                    color = Color(0xFFEF5350),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.End)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFEF5350).copy(alpha = 0.15f))
                        .clickable { SherpaAsrManager.cancelDownload(context) }
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
            // 下载按钮
            if (!sherpaReady.value && !SherpaAsrManager.isModelDownloading) {
                Text(
                    text = stringResource(R.string.asr_click_to_download_prefix) + modelName + "（" + modelSizeMB + "MB）",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(accentColor.copy(alpha = 0.85f))
                        .clickable { SherpaAsrManager.startDownloadFor(context, sherpaLangCode) }
                        .padding(vertical = 7.dp)
                )
            }
            if (sherpaReady.value) {
                Text(
                    text = stringResource(R.string.asr_model_ready_hint, modelName),
                    color = Color(0xFF81C784),
                    fontSize = 9.sp
                )
            }
        }
    }
}
// ===== 后台生成全片字幕（v91 提取复用）=====
BatchTranscribeSection(
                            accentColor = accentColor,
                            accentOnColor = accentOnColor,
                            sherpaLangCode = sherpaLangCode,
                            onSherpaLangCodeChange = { onChangeAsrLanguage(it) },
                            onUserInteraction = { onKeepUiAlight() },
                            // v2.4.30：AI 字幕面板里把 SenseVoice 单独置顶
                            senseVoiceFirst = true
                        )
}
