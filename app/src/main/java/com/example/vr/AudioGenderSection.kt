package com.example.vr

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// R 类在 `com.example` 包下（namespace），本文件在 `com.example.vr` —— 必须显式 import
import com.example.R
import kotlinx.coroutines.launch

/**
 * v2.4.64：**音频性别分析（实验）区块**（设置面板内）。
 *
 * ## 这一版做什么
 * 按 `docs/AUDIO_GENDER_FILTER_DESIGN.md` 的**第一段**落地：
 * 抽音轨 → 说话人分离（sherpa-onnx）→ **基频判性别** → 输出「哪些时间段是男声/女声」。
 * **刻意不改动播放**：先用真实视频验证识别准不准，再决定要不要投入源分离与播放集成。
 *
 * ## 为什么单独成文件
 * 与 `LocalModelSection` 同一范式：模型下载 + 长耗时任务 + 进度/结果展示放在一个**自包含组件**里，
 * 母面板（`SubtitleSettingsPanel`）只负责摆位置 —— 避免把这段逻辑再塞进已经 8000+ 行的
 * `VRPlayerScreen`（本项目「同一功能两份 UI」是头号事故源，这里也保持单一实现）。
 *
 * ## 模型
 * 由 [AudioGenderModels] 管理（VAD / pyannote 分割 / WeSpeaker 嵌入，合计约 32MB），
 * 下载到 `filesDir/audio_gender_models/`，与 ASR / 本地 LLM 的模型互不干扰。
 */
@Composable
fun AudioGenderSection(
    accentColor: Color,
    /** 要分析的媒体 URI（通常是当前打开的片子）；null = 还没打开任何媒体 */
    mediaUri: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 文件就绪属于「磁盘状态」，不在 Compose 观察范围内 → 用计数器手动刷新
    var refreshKey by remember { mutableIntStateOf(0) }
    var downloading by remember { mutableStateOf(false) }
    var downloadLabel by remember { mutableStateOf("") }
    var downloadPct by remember { mutableIntStateOf(0) }
    var analyzing by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var resultText by remember { mutableStateOf("") }

    val ready = remember(refreshKey) { AudioGenderAnalyzer.modelsReady(context) }
    val downloadedMb = remember(refreshKey) { AudioGenderModels.downloadedMb(context) }
    val missingMb = remember(refreshKey) { AudioGenderModels.missing(context).sumOf { it.approxMb } }

    // ⚠️ Compose 规则：stringResource(...) 是 @Composable，**不能在 scope.launch { } 里调用**
    //    （报 "Composable invocations can only happen from the context of a @Composable function"，
    //      且 try/catch 包住它还会报 "Try catch is not supported around composable function invocations"）。
    //    → 在 Composable 作用域先取出来，协程里只用这些局部变量。
    val textDownloadFailed = stringResource(R.string.audio_gender_download_failed)
    val textStageExtract = stringResource(R.string.audio_gender_stage_extract)
    val textStageSeparate = stringResource(R.string.audio_gender_stage_separate)
    val textExtractFailed = stringResource(R.string.audio_gender_extract_failed)
    val textAnalyzeFailed = stringResource(R.string.audio_gender_analyze_failed)
    val textTruncated = stringResource(R.string.audio_gender_truncated)

    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.audio_gender_title),
            color = accentColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.audio_gender_desc),
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 10.sp,
        )

        // ---------------- 模型状态 / 下载 ----------------
        Text(
            text = if (ready) {
                stringResource(R.string.audio_gender_models_ready, downloadedMb)
            } else {
                stringResource(R.string.audio_gender_models_need, missingMb)
            },
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 10.sp,
        )
        if (!ready) {
            ChipButton(
                label = if (downloading) "$downloadLabel $downloadPct%" else stringResource(R.string.audio_gender_download),
                enabled = !downloading,
                accentColor = accentColor,
            ) {
                scope.launch {
                    downloading = true
                    downloadPct = 0
                    val ok = AudioGenderModels.ensureAll(context) { name, pct ->
                        downloadLabel = name
                        downloadPct = pct
                    }
                    downloading = false
                    refreshKey++
                    if (!ok) resultText = textDownloadFailed
                }
            }
            if (downloading) {
                LinearProgressIndicator(
                    progress = { downloadPct / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = accentColor,
                )
            }
        }

        // ---------------- 开始分析 ----------------
        ChipButton(
            label = if (analyzing) {
                "$stage $progress%"
            } else if (mediaUri == null) {
                stringResource(R.string.audio_gender_no_media)
            } else {
                stringResource(R.string.audio_gender_analyze)
            },
            enabled = ready && !analyzing && !mediaUri.isNullOrBlank(),
            accentColor = accentColor,
        ) {
            scope.launch {
                analyzing = true
                progress = 0
                resultText = ""
                try {
                    stage = textStageExtract
                    // v2.4.65：抽取改为返回「成功 / 失败(带原因)」，失败原因**直接显示给用户**
                    //   —— v2.4.64 只显示一句笼统文案，导致「抽取失败」时无法分辨
                    //      是「没有音轨」「来源不支持」还是「内存不足」。
                    when (val ex = AudioPcmExtractor.extract(context, Uri.parse(mediaUri)) { progress = it / 2 }) {
                        is AudioPcmExtractor.ExtractResult.Fail -> {
                            resultText = ex.reason.ifBlank { textExtractFailed }
                        }
                        is AudioPcmExtractor.ExtractResult.Ok -> {
                            stage = textStageSeparate
                            val result = AudioGenderAnalyzer.analyze(context, ex.samples) { progress = 50 + it / 2 }
                            val head = if (ex.truncated) textTruncated + "\n" else ""
                            resultText = head + (result?.summary() ?: textAnalyzeFailed)
                        }
                    }
                } finally {
                    analyzing = false
                }
            }
        }
        if (analyzing) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = accentColor,
            )
        }

        // ---------------- 结果 ----------------
        if (resultText.isNotEmpty()) {
            Text(
                text = resultText,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 10.sp,
            )
        }
    }
}

/** 面板内的小按钮（与 `LocalModelSection` 视觉一致：圆角 + 描边 + 可点击）。 */
@Composable
private fun ChipButton(
    label: String,
    enabled: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.4f
    Text(
        text = label,
        color = Color.White.copy(alpha = alpha),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .background(accentColor.copy(alpha = 0.18f * alpha), RoundedCornerShape(8.dp))
            .border(1.dp, accentColor.copy(alpha = 0.45f * alpha), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}
