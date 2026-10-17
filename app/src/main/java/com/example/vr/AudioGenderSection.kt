package com.example.vr

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Alignment
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
import java.io.File
import kotlin.math.min

/**
 * v2.4.68：**音频性别分析 / 去人声区块**（设置面板内）。
 *
 * ## 交互（v2.4.68 起）
 * 1. **分析**：抽音轨 → 说话人分离（sherpa-onnx）→ 性别判定（ECAPA-TDNN，F0 回退）；
 * 2. **逐个列出说话人**：每人一行（性别 / F0 / 时长 / 段数）+ **一个开关**，
 *    开关的含义是「**这个人要从音轨里去掉**」，**男声默认打开**；
 * 3. **应用**：按勾选者做「人声分离（MDX-Net）→ 把该说话人的区间换成伴奏 → 输出 WAV」。
 *
 * ## 为什么按说话人给开关，而不是「男声一律去掉」
 * 用户常常只想去掉**某几个人**（保留旁白男声、只去掉对手戏男声）。按说话人粒度可控得多，
 * 而底层 `MdxReprocessor` 正好是按 `speakerId` 选择区间处理的。
 *
 * ## ⚠️ 当前限制（如实告知）
 * - 人声分离走的是「整段内存内」版本，**上限 90 秒** → 「应用」只处理**前 90 秒**（UI 文案里写明）；
 * - 男女**同时说话**的区间，去掉其中一人会把同段的人声一起去掉（源分离不认性别，
 *   要解决需 TSE，见 `docs/AUDIO_GENDER_FILTER_TECH_STACK.md` §3.6）。
 *
 * ## 为什么单独成文件
 * 与 `LocalModelSection` 同一范式：模型下载 + 长耗时任务 + 进度/结果都自包含，
 * 母面板只负责摆位置 —— 避免把这段塞进 8000+ 行的 `VRPlayerScreen`。
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

    // v2.4.68：说话人列表 + 勾选（勾选 = 要去掉）
    var rows by remember { mutableStateOf<List<MdxReprocessor.Row>>(emptyList()) }
    // 分析得到的原始语音段（供「应用」时按 speakerId 选区间用）
    var segments by remember { mutableStateOf<List<AudioGenderAnalyzer.SpeakerSegment>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var processing by remember { mutableStateOf(false) }
    var processStage by remember { mutableStateOf("") }
    var processPct by remember { mutableIntStateOf(0) }

    val ready = remember(refreshKey) { AudioGenderAnalyzer.modelsReady(context) }
    val downloadedMb = remember(refreshKey) { AudioGenderModels.downloadedMb(context) }
    val missingMb = remember(refreshKey) { AudioGenderModels.missing(context).sumOf { it.approxMb } }
    val mdxReady = remember(refreshKey) { MdxSeparator.isModelReady(context) }

    // ⚠️ Compose 规则：stringResource(...) 是 @Composable，**不能在 scope.launch { } 里调用**
    //    → 在 Composable 作用域先取出来，协程里只用这些局部变量。
    val textDownloadFailed = stringResource(R.string.audio_gender_download_failed)
    val textStageExtract = stringResource(R.string.audio_gender_stage_extract)
    val textStageSeparate = stringResource(R.string.audio_gender_stage_separate)
    val textExtractFailed = stringResource(R.string.audio_gender_extract_failed)
    val textAnalyzeFailed = stringResource(R.string.audio_gender_analyze_failed)
    val textTruncated = stringResource(R.string.audio_gender_truncated)
    val textGProcessing = stringResource(R.string.audio_gender_processing)
    val textGDone = stringResource(R.string.audio_gender_processed)
    val textGMdxFail = stringResource(R.string.audio_gender_mdx_failed)

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

        // ---------------- 分析模型状态 / 下载 ----------------
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
            enabled = ready && !analyzing && !processing && !mediaUri.isNullOrBlank(),
            accentColor = accentColor,
        ) {
            scope.launch {
                analyzing = true
                progress = 0
                resultText = ""
                rows = emptyList()
                segments = emptyList()
                selected = emptySet()
                try {
                    stage = textStageExtract
                    when (val ex = AudioPcmExtractor.extract(context, Uri.parse(mediaUri)) { progress = it / 2 }) {
                        is AudioPcmExtractor.ExtractResult.Fail -> {
                            resultText = ex.reason.ifBlank { textExtractFailed }
                        }
                        is AudioPcmExtractor.ExtractResult.Ok -> {
                            stage = textStageSeparate
                            val result = AudioGenderAnalyzer.analyze(context, ex.samples) { progress = 50 + it / 2 }
                            if (result == null) {
                                resultText = textAnalyzeFailed
                            } else {
                                // v2.4.68：把说话人**逐个列出**（每人一个开关），男声默认勾选
                                val list = MdxReprocessor.speakerRows(result)
                                rows = list
                                segments = result.segments
                                selected = list.filter { it.defaultSelected() }.map { it.id }.toSet()
                                val head = if (ex.truncated) textTruncated + "\n" else ""
                                resultText = head + result.summary()
                            }
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

        // ---------------- 说话人列表（每人一个开关） ----------------
        if (rows.isNotEmpty()) {
            Text(
                text = stringResource(R.string.audio_gender_speakers_title),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            rows.forEach { row ->
                val checked = row.id in selected
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(
                            R.string.audio_gender_spk_row,
                            row.id,
                            stringResource(genderLabelRes(row.gender)),
                            row.f0Hz.toInt(),
                            (row.speechMs / 1000).toInt(),
                            row.segmentCount,
                        ),
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f),
                    )
                    AuraSwitch(
                        checked = checked,
                        onCheckedChange = { on ->
                            selected = if (on) selected + row.id else selected - row.id
                        },
                        accentColor = accentColor,
                        enabled = !analyzing && !processing,
                    )
                }
            }

            // MDX 分离模型（应用时才需要，所以放在这里提示）
            if (!mdxReady) {
                Text(
                    text = stringResource(R.string.audio_gender_need_mdx, AudioGenderModels.MDX.approxMb),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                )
                ChipButton(
                    label = if (downloading) "$downloadLabel $downloadPct%" else stringResource(R.string.audio_gender_mdx_download),
                    enabled = !downloading && !processing,
                    accentColor = accentColor,
                ) {
                    scope.launch {
                        downloading = true
                        downloadPct = 0
                        val ok = AudioGenderModels.download(context, AudioGenderModels.MDX) { downloadPct = it }
                        downloading = false
                        refreshKey++
                        if (!ok) resultText = textDownloadFailed
                    }
                }
            } else {
                // ---------------- 应用：按勾选去人声 ----------------
                ChipButton(
                    label = if (processing) {
                        "$processStage $processPct%"
                    } else {
                        stringResource(R.string.audio_gender_apply)
                    },
                    enabled = !processing && !analyzing && selected.isNotEmpty(),
                    accentColor = accentColor,
                ) {
                    scope.launch {
                        processing = true
                        processPct = 0
                        resultText = ""
                        try {
                            // ① 44.1k 立体声流式解码 → 只取前 MAX_INLINE_SECONDS 秒（分离的内存上限）
                            processStage = textGProcessing
                            val maxSamples = MdxSeparator.MAX_INLINE_SECONDS * MdxDsp.SAMPLE_RATE
                            val mixL = FloatArray(maxSamples)
                            val mixR = FloatArray(maxSamples)
                            var filled = 0
                            AudioPcmExtractor44k.stream(context, Uri.parse(mediaUri)) { l, r, n ->
                                val take = min(n, maxSamples - filled)
                                System.arraycopy(l, 0, mixL, filled, take)
                                System.arraycopy(r, 0, mixR, filled, take)
                                filled += take
                                processPct = (filled * 15 / maxSamples)
                                filled < maxSamples      // 够了就中止
                            }
                            if (filled <= 0) {
                                resultText = textGMdxFail
                                return@launch
                            }
                            val mL = mixL.copyOf(filled)
                            val mR = mixR.copyOf(filled)

                            // ② 分离人声
                            val vocals = MdxSeparator.separate(context, mL, mR) { processPct = 15 + it * 70 / 100 }
                            if (vocals == null) {
                                resultText = textGMdxFail
                                return@launch
                            }

                            // ③ 按勾选的说话人重组（区间换伴奏 + 边界淡化）
                            processStage = textGProcessing
                            val out = MdxReprocessor.apply(
                                mL, mR, vocals.first, vocals.second,
                                segments = segments,
                                selected = selected,
                            )

                            // ④ 写出 WAV
                            val file = File(context.cacheDir, "audio_gender_processed.wav")
                            val okW = WavIo.writeStereo16(file, out.first, out.second, MdxDsp.SAMPLE_RATE)
                            processPct = 100
                            resultText = if (okW) {
                                textGDone.format(file.absolutePath, filled / MdxDsp.SAMPLE_RATE)
                            } else {
                                textGMdxFail
                            }
                        } finally {
                            processing = false
                        }
                    }
                }
            }
            if (processing) {
                LinearProgressIndicator(
                    progress = { processPct / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = accentColor,
                )
            }

            Text(
                text = stringResource(R.string.audio_gender_credit_uvr),
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 9.sp,
            )
        }

        // ---------------- 结果文本 ----------------
        if (resultText.isNotEmpty()) {
            Text(
                text = resultText,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 10.sp,
            )
        }
    }
}

/** 性别 → 文案资源 */
private fun genderLabelRes(g: AudioGenderAnalyzer.Gender): Int = when (g) {
    AudioGenderAnalyzer.Gender.MALE -> R.string.audio_gender_g_male
    AudioGenderAnalyzer.Gender.FEMALE -> R.string.audio_gender_g_female
    AudioGenderAnalyzer.Gender.UNKNOWN -> R.string.audio_gender_g_unknown
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
