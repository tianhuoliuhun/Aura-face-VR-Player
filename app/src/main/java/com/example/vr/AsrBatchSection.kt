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
 * - 模型状态与下载（内置 Dolphin 无需下载；其它模型按需下载，含进度与取消）
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
    /**
     * v2.4.30：是否在面板**顶部单独列出 SenseVoice-Small**（提高其 UI 优先等级）。
     * AI 字幕面板（字幕浮层）传 true；设置面板保持 false（那里语言/模型列表已足够）。
     */
    senseVoiceFirst: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // v2.0.145：模型就绪状态按**所选语言**判断（扩展语言如越南语是独立模型，需单独下载）
    var modelReady by remember { mutableStateOf(SherpaAsrManager.isModelReadyFor(context, sherpaLangCode)) }
    var downloadProgress by remember { mutableFloatStateOf(SherpaAsrManager.modelDownloadProgress) }
    // 下载状态在单例里，这里跟随同步以便重组
    var isDownloading by remember { mutableStateOf(SherpaAsrManager.isModelDownloading) }

    LaunchedEffect(sherpaLangCode, SherpaAsrManager.isModelDownloading, SherpaAsrManager.modelDownloadProgress) {
        modelReady = SherpaAsrManager.isModelReadyFor(context, sherpaLangCode)
        downloadProgress = SherpaAsrManager.modelDownloadProgress
        isDownloading = SherpaAsrManager.isModelDownloading
    }

    // 当前生效模型统一解析一次（chips 选中判定要用）。
    // v2.4.30：从下方上移到这里 —— 顶部的 SenseVoice 快捷区块也要用它做选中判定。
    val activeModelId = SherpaAsrManager.activeModelIdFor(context, sherpaLangCode)

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

            // ===== v2.4.30：SenseVoice-Small **单独列出 + 置顶**（提高 UI 优先等级）=====
            // 为什么必须在最前面单独给一块：默认的「按分类」视角里，
            // `dedupeKeepPreferred` 对**每种语言只显示首选一条** —— 中/日/韩/粤 的首选是
            // **内置 Dolphin**，于是 SenseVoice 在这些语言下**在面板里根本选不到**。
            // 这里给它一个独立入口：点语言即「切到该语言 + 选 SenseVoice」。
            if (senseVoiceFirst) {
                SenseVoiceQuickBlock(
                    context = context,
                    sherpaLangCode = sherpaLangCode,
                    activeModelId = activeModelId,
                    accentColor = accentColor,
                    accentOnColor = accentOnColor,
                    onUserInteraction = onUserInteraction,
                    onPick = { code ->
                        onSherpaLangCodeChange(code)
                        SherpaAsrManager.setModelChoice(context, code, AsrExtModels.SENSE_VOICE_DIR)
                    }
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
                            // v2.0.145：模型名随所选语言变化（扩展语言是独立模型）
                            SherpaAsrManager.modelInfoFor(context, sherpaLangCode).first,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            // v2.0.127：模型内置，这里显示实际生效来源（下载版优先于内置版）
                            // v2.0.145：扩展语言无内置版，就绪即显示「已就绪」
                            text = if (modelReady)
                                if (SherpaAsrManager.modelInfoFor(context, sherpaLangCode).third) stringResource(R.string.asr_ready)
                                else stringResource(R.string.asr_model_ready_source, SherpaAsrManager.activeModelSource(context))
                            else stringResource(R.string.asr_model_unavailable),
                            color = if (modelReady) accentColor.copy(alpha = 0.9f)
                            else Color(0xFFFFB74D),
                            fontSize = 8.sp
                        )
                    }
                    if (!isDownloading) {
                        // v2.1.232：**先问「到底要不要下载」**，不再无条件给一个可点按钮。
                        // 此前「自动」（以及任一内置语种）走到这里会触发 SenseVoice-Small 的
                        // 下载流程 —— 但 SenseVoice 早在 v2.1.208 就被内置 Dolphin 取代了，
                        // 下完也用不上（用户反馈的原话：「自动还是会下载 smallVocie」）。
                        // 下载通路本身保留：确实需要下载的扩展语言按钮照常可用。
                        val needDownload = SherpaAsrManager.isDownloadNeededFor(context, sherpaLangCode)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    when {
                                        !needDownload -> Color.White.copy(alpha = 0.06f)
                                        modelReady -> Color.White.copy(alpha = 0.12f)
                                        else -> accentColor.copy(alpha = 0.85f)
                                    }
                                )
                                .clickable(enabled = needDownload) {
                                    onUserInteraction()
                                    SherpaAsrManager.startDownloadFor(context, sherpaLangCode)
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                when {
                                    !needDownload -> stringResource(R.string.asr_no_download_needed)
                                    modelReady -> stringResource(R.string.asr_update_model)
                                    else -> stringResource(R.string.asr_download_model)
                                },
                                color = when {
                                    !needDownload -> Color.White.copy(alpha = 0.35f)
                                    modelReady -> Color.White.copy(alpha = 0.85f)
                                    else -> accentOnColor
                                },
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (isDownloading) {
                    Text(
                        if (SherpaAsrManager.downloadStatus.isEmpty()) stringResource(R.string.asr_status_builtin_ready) else SherpaAsrManager.downloadStatus,
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
                            .clickable { SherpaAsrManager.cancelDownload(context) }
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(stringResource(R.string.asr_cancel_download), color = Color(0xFFEF5350), fontSize = 8.sp)
                    }
                }
            }

            // v2.4.30：`activeModelId` 已上移到函数开头统一计算（顶部 SenseVoice 区块也要用）。
            // ===== 识别语言 =====
            // v2.1.232：改用与设置面板**同一份** AsrLanguageChips。
            // 此前这里是另一份独立副本，两个 bug 都源自它：
            //   1) `lang.modelId?.let{}` 会跳过内置候选 → 内置语言选不中（v2.1.226 的同类问题）
            //   2) 仍用旧的 7 语区分组，没跟上 v2.1.231 的四项分类
            Text(stringResource(R.string.asr_language), color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
            AsrLanguageChips(
                context = context,
                sherpaLangCode = sherpaLangCode,
                activeModelId = activeModelId,
                accentColor = accentColor,
                accentOnColor = accentOnColor,
                compact = true,
                onPick = { code, modelId ->
                    onSherpaLangCodeChange(code)
                    SherpaAsrManager.setModelChoice(context, code, modelId)
                },
                onUserInteraction = onUserInteraction
            )
            Text(
                stringResource(R.string.asr_language_hint),
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 8.sp,
                lineHeight = 11.sp
            )
        }
    }
}

/**
 * v2.4.30：**SenseVoice-Small 专属快捷区块**（AI 字幕面板置顶，单独列出）。
 *
 * ## 为什么必须「单独列出」
 * 默认的「按分类」视角里，`AsrLanguageChips.dedupeKeepPreferred` 对**每种语言只显示
 * 首选一条**；中/日/韩/粤 的首选是**内置 Dolphin**（rank 1），于是 SenseVoice
 * 在这些语言下**在面板里选不到**（只能切到「按模型」视角才能找到）。
 * 这里给 SenseVoice 一个独立、置顶、描强调色边框的入口：
 *   · 5 个语言 chip —— 点 = 「切到该语言 + 选 SenseVoice」，一步到位；
 *   · 未下载时给一个下载按钮（带体积），下载中由下方通用进度条显示。
 */
@Composable
private fun SenseVoiceQuickBlock(
    context: android.content.Context,
    sherpaLangCode: String,
    activeModelId: String,
    accentColor: Color,
    accentOnColor: Color,
    onUserInteraction: () -> Unit,
    onPick: (String) -> Unit
) {
    // SenseVoice 的 5 条登记**共用同一个 dirName/模型**，取第一条即可代表整个模型。
    val entry = AsrExtModels.SENSE_VOICE_ALL.first()
    var ready by remember { mutableStateOf(SherpaAsrManager.isExtModelReady(context, entry)) }
    var downloading by remember { mutableStateOf(SherpaAsrManager.isModelDownloading) }
    LaunchedEffect(SherpaAsrManager.isModelDownloading, SherpaAsrManager.modelDownloadProgress) {
        ready = SherpaAsrManager.isExtModelReady(context, entry)
        downloading = SherpaAsrManager.isModelDownloading
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            // 强调色底 + 描边 → 视觉上「优先于」下方的一般模型区块
            .background(accentColor.copy(alpha = 0.14f))
            .border(1.dp, accentColor.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
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
                    stringResource(R.string.asr_sv_quick_title),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(R.string.asr_sv_quick_sub),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 8.sp
                )
            }
            if (ready) {
                Text(
                    stringResource(R.string.asr_ready),
                    color = accentColor.copy(alpha = 0.95f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            } else if (!downloading) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(accentColor.copy(alpha = 0.9f))
                        .clickable {
                            onUserInteraction()
                            SherpaAsrManager.startExtModelDownload(context, entry)
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        stringResource(R.string.asr_sv_download, entry.sizeMb),
                        color = accentOnColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 5 个语言 chip（中/英/日/韩/粤）。每行 5 个 —— 列表恰好 5 条，故只有一行。
        val perRow = 5
        AsrExtModels.SENSE_VOICE_ALL.chunked(perRow).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                rowItems.forEach { m ->
                    val code = m.key
                    // 选中判定与别处完全一致：uid = `语言@模型`
                    val sel = "$code@${AsrExtModels.SENSE_VOICE_DIR}" == "$sherpaLangCode@$activeModelId"
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (sel) accentColor else Color.White.copy(alpha = 0.10f))
                            .clickable {
                                onUserInteraction()
                                onPick(code)
                            }
                            .padding(vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(m.labelResId),
                            color = if (sel) accentOnColor else Color.White.copy(alpha = 0.88f),
                            fontSize = 8.sp,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                }
                repeat(perRow - rowItems.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
