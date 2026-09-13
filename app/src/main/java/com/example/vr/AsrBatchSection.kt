package com.example.vr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * v120 拆分：从 VRPlayerScreen.kt 抽出的「后台生成全片字幕」区块（约 430 行）。
 *
 * 该区块同时被**设置面板**与**主界面字幕快捷面板**复用（v91 提取），原先是主函数里的
 * 内嵌 @Composable，直接读写主函数的十余个状态；外置后改为单向数据流：
 * - 只读值（引擎类型 / 转写进度 / Vosk 引擎实例…）由参数传入
 * - 写操作通过回调上抛，由调用方统一写回状态并持久化到 prefs
 * - SherpaAsrManager 是进程内单例，组件内直接读取，无需传参
 */
@Composable
fun BatchTranscribeSection(
    accentColor: Color,
    accentOnColor: Color,
    asrManager: RealtimeAsrManager,
    asrEngineType: AsrEngineType,
    onAsrEngineTypeChange: (AsrEngineType) -> Unit,
    sherpaLangCode: String,
    onSherpaLangCodeChange: (String) -> Unit,
    isBatchTranscribing: Boolean,
    batchTranscribeProgress: Float,
    batchTranscribeStatus: String,
    onStartBatchTranscribe: () -> Unit,
    onSelectVoskLanguage: (VoskLanguage) -> Unit,
    onSelectVoskModelSize: (VoskModelSize) -> Unit,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Surface(
        color = Color.White.copy(alpha = 0.06f),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "后台生成全片字幕 (SRT)",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (isBatchTranscribing)
                                "转写中：${(batchTranscribeProgress * 100).toInt()}%　${batchTranscribeStatus}"
                            else
                                "识别语言：${asrManager.config.language.label} · ${asrManager.config.modelOption.label}（${asrManager.config.modelOption.sizeMb}MB）",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 9.sp,
                            lineHeight = 12.sp
                        )
                    }
                    Button(
                        onClick = { onStartBatchTranscribe() },
                        enabled = !isBatchTranscribing,
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(
                            if (isBatchTranscribing) "转写中…" else "开始",
                            color = accentOnColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // ===== ASR 引擎选择（v111：Vosk / Qwen3-ASR / SenseVoice QNN）=====
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("引擎", color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
                    AsrEngineType.entries.forEach { engine ->
                        val sel = asrEngineType == engine
                        val label = when (engine) {
                            AsrEngineType.VOSK -> "Vosk"
                            AsrEngineType.QWEN3 -> "Qwen3"
                            AsrEngineType.SENSEVOICE_QNN -> "SV QNN"
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (sel) accentColor.copy(alpha = 0.8f)
                                    else Color.White.copy(alpha = 0.08f)
                                )
                                .clickable(enabled = !isBatchTranscribing) {
                                    onAsrEngineTypeChange(engine)
                                    onUserInteraction()
                                }
                                .padding(vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (sel) accentOnColor else Color.White.copy(alpha = 0.85f),
                                fontSize = 9.sp,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // ===== sherpa-onnx 模型状态（v111：Qwen3/SenseVoice）=====
                if (asrEngineType == AsrEngineType.QWEN3 || asrEngineType == AsrEngineType.SENSEVOICE_QNN) {
                    val sherpaReady = remember { mutableStateOf(SherpaAsrManager.isModelReady(context, asrEngineType)) }
                    LaunchedEffect(asrEngineType, SherpaAsrManager.isModelDownloading, SherpaAsrManager.modelDownloadProgress) {
                        sherpaReady.value = SherpaAsrManager.isModelReady(context, asrEngineType)
                    }
                    val modelName = when (asrEngineType) { AsrEngineType.QWEN3 -> "Qwen3-ASR 0.6B"; AsrEngineType.SENSEVOICE_QNN -> "SenseVoice QNN"; else -> "" }
                    val modelDesc = when (asrEngineType) { AsrEngineType.QWEN3 -> "29语言+20方言 · ~838MB"; AsrEngineType.SENSEVOICE_QNN -> "中英日韩粤 · 高通 NPU · ~161MB"; else -> "" }
                    val modelSize = when (asrEngineType) { AsrEngineType.QWEN3 -> 838; AsrEngineType.SENSEVOICE_QNN -> 161; else -> 0 }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.25f))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        // 模型信息
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = modelName,
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = modelDesc,
                                    color = Color.White.copy(alpha = 0.4f),
                                    fontSize = 8.sp
                                )
                            }
                            // 状态标签
                            Text(
                                text = when {
                                    SherpaAsrManager.isModelDownloading -> "⬇ 下载中"
                                    sherpaReady.value -> "✓ 已就绪"
                                    else -> "未下载"
                                },
                                color = when {
                                    SherpaAsrManager.isModelDownloading -> Color(0xFF4FC3F7)
                                    sherpaReady.value -> Color(0xFF81C784)
                                    else -> Color.White
                                },
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // 下载进度条（仅下载中显示）
                        if (SherpaAsrManager.isModelDownloading) {
                            LinearProgressIndicator(
                                progress = SherpaAsrManager.modelDownloadProgress,
                                color = accentColor,
                                trackColor = Color.White.copy(alpha = 0.15f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "下载 ${(SherpaAsrManager.modelDownloadProgress * 100).toInt()}%",
                                    color = Color(0xFF4FC3F7),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = SherpaAsrManager.downloadStatus,
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 8.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            // 取消
                            Text(
                                text = "取消下载",
                                color = Color(0xFFEF5350),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .align(Alignment.End)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFFEF5350).copy(alpha = 0.15f))
                                    .clickable { SherpaAsrManager.cancelDownload() }
                                    .padding(horizontal = 10.dp, vertical = 3.dp)
                            )
                        }

                        // 下载按钮（未下载且未在下载时）
                        if (!sherpaReady.value && !SherpaAsrManager.isModelDownloading) {
                            // v118：QNN 模型与 SoC 绑定，设备无对应模型时不该让用户白等下载
                            val qnnSocOk = asrEngineType != AsrEngineType.SENSEVOICE_QNN ||
                                SherpaAsrManager.senseVoiceModelDirName(context) != null
                            Text(
                                text = if (qnnSocOk) "点击下载 $modelName（${modelSize}MB）"
                                else "当前设备（${SherpaAsrManager.deviceSocName()}）无官方 QNN 模型，请改用 Qwen3-ASR",
                                color = if (qnnSocOk) Color.White else Color(0xFFEF9A9A),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (qnnSocOk) accentColor.copy(alpha = 0.85f)
                                        else Color.White.copy(alpha = 0.08f)
                                    )
                                    .clickable(enabled = qnnSocOk) { SherpaAsrManager.startModelDownload(context, asrEngineType) }
                                    .padding(vertical = 8.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // ===== 识别语言选择（v87/v111）=====
                if (asrEngineType == AsrEngineType.VOSK) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("语言", color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
                    VoskLanguage.entries.forEach { lang ->
                        val sel = asrManager.config.language == lang
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (sel) accentColor else Color.White.copy(alpha = 0.08f))
                                .clickable(enabled = !isBatchTranscribing) {
                                    onSelectVoskLanguage(lang)
                                    onUserInteraction()
                                }
                                .padding(vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                lang.label,
                                color = if (sel) accentOnColor else Color.White.copy(alpha = 0.8f),
                                fontSize = 10.sp,
                                fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                // ===== sherpa 引擎语言选择（v111：快捷面板同步）=====
                if (asrEngineType == AsrEngineType.QWEN3 || asrEngineType == AsrEngineType.SENSEVOICE_QNN) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("语言", color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
                        SherpaAsrManager.sherpaLanguages.forEach { (code, label) ->
                            val sel = sherpaLangCode == code
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (sel) accentColor else Color.White.copy(alpha = 0.08f))
                                    .clickable { onSherpaLangCodeChange(code); onUserInteraction() }
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (sel) accentOnColor else Color.White.copy(alpha = 0.85f),
                                    fontSize = 9.sp,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                // ===== 模型大小选择（v108：支持下载进度、重试、取消）=====
                // 模型选择区：干净的选项卡片
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("模型", color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
                    VoskModelSize.entries.forEach { size ->
                        val sel = asrManager.config.modelSize == size
                        val opt = VoskModels.firstOrNull { it.language == asrManager.config.language && it.size == size }
                        val isDownloaded = opt?.let {
                            File(context.filesDir, "vosk_models/${it.modelName}/.ready").exists()
                        } ?: false
                        val isActive = asrManager.isModelDownloading && asrManager.config.modelSize == size
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    when {
                                        isActive -> accentColor.copy(alpha = 0.25f)
                                        sel -> accentColor
                                        else -> Color.White.copy(alpha = 0.08f)
                                    }
                                )
                                .clickable(enabled = !isBatchTranscribing && !asrManager.isModelDownloading) {
                                    // 未下载的模型：回调内部会切换配置并触发下载；已下载的只切换配置
                                    onSelectVoskModelSize(size)
                                    onUserInteraction()
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = if (opt != null) "${size.label}\n${opt.sizeMb}MB" else size.label,
                                    color = if (sel) accentOnColor else Color.White.copy(alpha = 0.85f),
                                    fontSize = 9.sp,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 11.sp
                                )
                                // 状态指示小圆点
                                Text(
                                    text = when {
                                        isActive -> "⬇下载中"
                                        isDownloaded -> "✓ 就绪"
                                        else -> "点击下载"
                                    },
                                    color = when {
                                        isActive -> Color(0xFF4FC3F7)  // 亮蓝
                                        isDownloaded -> Color(0xFF81C784) // 柔绿
                                        else -> Color.White // 纯白，清晰可见
                                    },
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                } // end if (asrEngineType == VOSK)

                // ===== 下载进度区（Vosk 独立卡片）=====
                if (asrManager.isModelDownloading) {
                    val downloadingModel = VoskModels.firstOrNull {
                        it.language == asrManager.config.language && it.size == asrManager.config.modelSize
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black.copy(alpha = 0.3f))
                            .border(
                                width = 1.dp,
                                color = accentColor.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        // 模型名称 + 大小
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "正在下载 ${downloadingModel?.label ?: ""}",
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = downloadingModel?.let { "${it.sizeMb}MB" } ?: "",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 9.sp
                            )
                        }
                        // 进度条
                        LinearProgressIndicator(
                            progress = asrManager.modelDownloadProgress,
                            color = accentColor,
                            trackColor = Color.White.copy(alpha = 0.15f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                        // 百分比 + 详细状态
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "下载中 ${(asrManager.modelDownloadProgress * 100).toInt()}%",
                                color = Color(0xFF4FC3F7), // 亮蓝，与进度条呼应
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = asrManager.downloadStatus,
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 9.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        // 取消按钮
                        Text(
                            text = "取消下载",
                            color = Color(0xFFEF5350), // 柔红
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.End)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFFEF5350).copy(alpha = 0.15f))
                                .clickable { asrManager.cancelDownload() }
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }
                if (isBatchTranscribing) {
                    LinearProgressIndicator(
                        progress = { batchTranscribeProgress },
                        color = accentColor,
                        trackColor = Color.White.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth().height(4.dp)
                    )
                }
            }
        }
}
