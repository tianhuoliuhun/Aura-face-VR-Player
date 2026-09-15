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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 「实时字幕」的引擎与模型区块（设置面板、主界面字幕快捷面板共用）。
 *
 * v120 拆分自 VRPlayerScreen.kt；v127 起只保留 SenseVoice-Small（CPU）一条路线，
 * 因此删掉了原先的三引擎选择器与 Vosk 语言/模型档位 UI，只留：
 * - 模型状态与下载（约 229MB，含下载进度、取消）
 * - 识别语言选择（自动/中/英/日/韩，直接透传给 SenseVoice）
 *
 * 整片转写（生成 _asr.srt）已停用，改由 RealtimeSubtitleEngine 边播边生成，
 * 因此「开始」按钮与转写进度条一并移除。
 */
@Composable
fun BatchTranscribeSection(
    accentColor: Color,
    accentOnColor: Color,
    sherpaLangCode: String,
    onSherpaLangCodeChange: (String) -> Unit,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var modelReady by remember { mutableStateOf(SherpaAsrManager.isModelReady(context)) }
    var downloadProgress by remember { mutableFloatStateOf(SherpaAsrManager.modelDownloadProgress) }
    // 下载状态在单例里，这里跟随同步以便重组
    var isDownloading by remember { mutableStateOf(SherpaAsrManager.isModelDownloading) }

    LaunchedEffect(SherpaAsrManager.isModelDownloading, SherpaAsrManager.modelDownloadProgress) {
        modelReady = SherpaAsrManager.isModelReady(context)
        downloadProgress = SherpaAsrManager.modelDownloadProgress
        isDownloading = SherpaAsrManager.isModelDownloading
    }

    Surface(
        color = Color.White.copy(alpha = 0.06f),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.asr_section_title),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.asr_sensevoice_bundled),
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 9.sp,
                    lineHeight = 12.sp
                )
            }

            // ===== 模型状态 / 下载 =====
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.25f))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            "SenseVoice-Small",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            // v2.0.127：模型内置，这里显示实际生效来源（下载版优先于内置版）
                            text = if (modelReady) stringResource(R.string.asr_model_ready_source, SherpaAsrManager.activeModelSource(context))
                            else stringResource(R.string.asr_model_unavailable),
                            color = if (modelReady) accentColor.copy(alpha = 0.9f)
                            else Color(0xFFFFB74D),
                            fontSize = 8.sp
                        )
                    }
                    if (!isDownloading) {
                        // 内置模型已就绪时这里作为「更新/替换模型」入口保留
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (modelReady) Color.White.copy(alpha = 0.12f)
                                    else accentColor.copy(alpha = 0.85f)
                                )
                                .clickable {
                                    onUserInteraction()
                                    SherpaAsrManager.startModelDownload(context)
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                if (modelReady) stringResource(R.string.asr_update_model) else stringResource(R.string.asr_download_model),
                                color = if (modelReady) Color.White.copy(alpha = 0.85f) else accentOnColor,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (isDownloading) {
                    Text(
                        SherpaAsrManager.downloadStatus,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    LinearProgressIndicator(
                        progress = { downloadProgress.coerceIn(0f, 1f) },
                        color = accentColor,
                        trackColor = Color.White.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth().height(3.dp)
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.End)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFEF5350).copy(alpha = 0.15f))
                            .clickable { SherpaAsrManager.cancelDownload() }
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(stringResource(R.string.asr_cancel_download), color = Color(0xFFEF5350), fontSize = 8.sp)
                    }
                }
            }

            // ===== 识别语言（直接透传给 SenseVoice）=====
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(stringResource(R.string.asr_language), color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
                SherpaAsrManager.sherpaLanguages.forEach { (code, label) ->
                    val sel = sherpaLangCode == code
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (sel) accentColor.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.08f))
                            .clickable {
                                onUserInteraction()
                                onSherpaLangCodeChange(code)
                            }
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = if (sel) accentOnColor else Color.White.copy(alpha = 0.8f),
                            fontSize = 8.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }
            }
            Text(
                stringResource(R.string.asr_language_hint),
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 8.sp,
                lineHeight = 11.sp
            )
        }
    }
}
