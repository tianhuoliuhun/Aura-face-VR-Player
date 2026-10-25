package com.example.vr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import kotlinx.coroutines.launch

/**
 * 「实验性功能」分组里的**系统编解码能力检测**区块。
 *
 * ## 为什么单独成文件
 * `VRPlayerScreen.kt` 已经过万行，项目约定（见记忆库）是「按功能块拆成局部
 * `@Composable`」。这个区块自成一体（自带状态机 + 三种列表 + 复制报告），
 * 塞进 SettingsSection8K 会让那个函数再膨胀 300 行。
 *
 * ## 为什么是「点一次才检测」而不是进面板就跑
 * `MediaCodecList(ALL_CODECS)` 在部分 ROM 上要逐个创建 codec 实例才能读到能力，
 * 30+ 个 codec 能卡几百毫秒。设置面板是用户频繁开关的入口，
 * 不能为了一个**诊断用**的功能每次都扫一遍。
 */
@Composable
fun CodecCapabilitySection(
    accentColor: Color,
    accentOnColor: Color,
    decoderEngine: DecoderEngine,
    onInteract: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var report by remember { mutableStateOf<CodecCapabilityModel.CodecReport?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    Text(
        text = stringResource(R.string.codec_probe_title),
        color = accentColor,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 6.dp)
    )
    Text(
        text = stringResource(R.string.codec_probe_desc),
        color = Color.White.copy(alpha = 0.5f),
        fontSize = 10.sp,
        lineHeight = 14.sp
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ProbeButton(
            text = when {
                loading -> stringResource(R.string.codec_probe_running)
                report == null -> stringResource(R.string.codec_probe_start)
                else -> stringResource(R.string.codec_probe_again)
            },
            enabled = !loading,
            accentColor = accentColor,
            accentOnColor = accentOnColor,
            modifier = Modifier.weight(1f)
        ) {
            if (loading) return@ProbeButton
            onInteract()
            loading = true
            failed = false
            scope.launch {
                report = try {
                    CodecCapabilityProbe.probe(context, decoderEngine)
                } catch (e: Exception) {
                    failed = true
                    null
                }
                loading = false
            }
        }
        if (report != null) {
            ProbeButton(
                text = stringResource(R.string.codec_probe_copy),
                enabled = true,
                accentColor = accentColor,
                accentOnColor = accentOnColor,
                modifier = Modifier.weight(1f)
            ) {
                onInteract()
                copyReport(context, report!!)
            }
        }
    }

    if (failed) {
        Text(
            text = stringResource(R.string.codec_probe_failed),
            color = Color(0xFFFF9E9E),
            fontSize = 10.sp,
            lineHeight = 14.sp
        )
    }

    val r = report ?: return
    val best = CodecCapabilityModel.bestHardwareDecoder(r)
    val mpvReady = r.device.mpvInstalled

    // ===== 摘要 =====
    InfoCard {
        SummaryLine(
            label = stringResource(R.string.codec_probe_best),
            value = CodecCapabilityModel.tierSummary(best),
            highlight = accentColor
        )
        SummaryLine(
            label = stringResource(R.string.codec_probe_best_codec),
            value = best?.name ?: "—"
        )
        // VR 全景两档单独列：最高档只说「能吃多大」，不说「8192×4096 这张行不行」
        SummaryLine(
            label = stringResource(R.string.codec_probe_vr_tiers),
            value = CodecCapabilityModel.vrTiersText(best),
            highlight = accentColor
        )
        SummaryLine(
            label = stringResource(R.string.codec_probe_counts),
            value = stringResource(
                R.string.codec_probe_counts_value,
                r.videoDecoders().count { it.hardware },
                r.videoEncoders().count { it.hardware },
                r.audioEntries().size
            )
        )
        SummaryLine(
            label = stringResource(R.string.codec_probe_device),
            value = "${r.device.model} · Android ${r.device.androidRelease} (API ${r.device.sdk})"
        )
        SummaryLine(
            label = stringResource(R.string.codec_probe_engine),
            value = r.device.currentEngine +
                if (r.device.mpvInstalled) "" else " · MPV ${stringResource(R.string.codec_probe_mpv_missing)}"
        )
    }

    // ===== 视频解码 =====
    CodecListBlock(
        title = stringResource(R.string.codec_probe_video_dec),
        entries = r.videoDecoders(),
        accentColor = accentColor,
        showTier = true
    )

    // ===== 视频编码 =====
    CodecListBlock(
        title = stringResource(R.string.codec_probe_video_enc),
        entries = r.videoEncoders(),
        accentColor = accentColor,
        showTier = false
    )

    // ===== 音频 =====
    CodecListBlock(
        title = stringResource(R.string.codec_probe_audio),
        entries = r.audioEntries(),
        accentColor = accentColor,
        showTier = false
    )

    // ===== 封装 =====
    Text(
        text = stringResource(R.string.codec_probe_containers),
        color = accentColor,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp)
    )
    Text(
        text = stringResource(R.string.codec_probe_containers_hint),
        color = Color.White.copy(alpha = 0.45f),
        fontSize = 9.sp,
        lineHeight = 12.sp
    )
    InfoCard {
        r.containers.forEach { c ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = c.label,
                    color = if (c.playable(mpvReady)) Color.White else Color.White.copy(alpha = 0.4f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                val engines = buildList {
                    if (c.exo) add("EXO")
                    if (c.ijk) add("IJK")
                    if (c.mpv) add("MPV")
                }
                Text(
                    text = engines.joinToString(" / ").ifEmpty { "—" },
                    color = accentColor.copy(alpha = 0.85f),
                    fontSize = 9.sp
                )
                if (c.mpv && !c.exo && !c.ijk) {
                    // 仅 MPV 的容器：等于「MPV 没装就完全不能播」，必须显式提醒
                    Text(
                        text = if (mpvReady) stringResource(R.string.codec_probe_need_mpv_ok)
                        else stringResource(R.string.codec_probe_need_mpv),
                        color = if (mpvReady) Color.White.copy(alpha = 0.45f) else Color(0xFFFFC46B),
                        fontSize = 9.sp
                    )
                }
            }
        }
    }
}

// ===================== 子组件 =====================

@Composable
private fun ProbeButton(
    text: String,
    enabled: Boolean,
    accentColor: Color,
    accentOnColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .background(
                if (enabled) accentColor else Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) accentOnColor else Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun InfoCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x0CFFFFFF), shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) { content() }
}

@Composable
private fun SummaryLine(label: String, value: String, highlight: Color? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 9.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = highlight ?: Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * 编解码器列表块（默认只展开前 [COLLAPSED_COUNT] 条）。
 *
 * 为什么默认折叠：一台机器的视频解码器常有三四十条（每个 codec 的每个 MIME 一条），
 * 全铺开会把这个设置分组撑成几十屏，用户根本划不到下面的「封装」。
 */
@Composable
private fun CodecListBlock(
    title: String,
    entries: List<CodecCapabilityModel.CodecEntry>,
    accentColor: Color,
    showTier: Boolean
) {
    if (entries.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) entries else entries.take(COLLAPSED_COUNT)

    Text(
        text = "$title  ·  ${entries.size}",
        color = accentColor,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp)
    )
    InfoCard {
        shown.forEach { e -> CodecRow(e, accentColor, showTier) }
        if (entries.size > COLLAPSED_COUNT) {
            Text(
                text = if (expanded) {
                    stringResource(R.string.codec_probe_collapse)
                } else {
                    stringResource(R.string.codec_probe_expand_all, entries.size - COLLAPSED_COUNT)
                },
                color = accentColor.copy(alpha = 0.8f),
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun CodecRow(
    e: CodecCapabilityModel.CodecEntry,
    accentColor: Color,
    showTier: Boolean
) {
    val tag = if (e.hardware) {
        stringResource(R.string.codec_probe_hw)
    } else {
        stringResource(R.string.codec_probe_sw)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = CodecCapabilityModel.mimeLabel(e.mime),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = tag,
                color = if (e.hardware) accentColor else Color.White.copy(alpha = 0.4f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
            if (e.encoder) {
                Text(
                    text = stringResource(R.string.codec_probe_enc_tag),
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 8.sp
                )
            }
            Text(
                text = e.name,
                color = Color.White.copy(alpha = 0.32f),
                fontSize = 8.sp,
                modifier = Modifier.weight(1f)
            )
        }
        val v = e.video
        if (v != null) {
            if (showTier) {
                Text(
                    text = stringResource(
                        R.string.codec_probe_tier_line,
                        if (v.tierWidth > 0) CodecCapabilityModel.resolutionWithTier(v.tierWidth, v.tierHeight) else "—",
                        CodecCapabilityModel.fpsText(v.tierMaxFps),
                        CodecCapabilityModel.resolutionText(v.maxWidth, v.maxHeight)
                    ),
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 9.sp,
                    lineHeight = 12.sp
                )
            } else {
                Text(
                    text = stringResource(
                        R.string.codec_probe_enc_line,
                        CodecCapabilityModel.resolutionText(v.maxWidth, v.maxHeight),
                        CodecCapabilityModel.fpsRangeText(v.minFps, v.maxFps),
                        CodecCapabilityModel.bitrateRangeText(v.minBitrate, v.maxBitrate)
                    ),
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 9.sp,
                    lineHeight = 12.sp
                )
            }
            val extra = buildList {
                if (v.profiles.isNotEmpty()) add(v.profiles.joinToString("/"))
                if (v.secure) add("secure")
                if (v.adaptive) add("adaptive")
                if (v.tunneled) add("tunneled")
                if (v.maxInstances > 0) add("×${v.maxInstances}")
                val vrOk = CodecCapabilityModel.vrTiersSupportedText(v)
                if (vrOk.isNotEmpty()) add("VR $vrOk")
            }
            if (extra.isNotEmpty()) {
                Text(
                    text = extra.joinToString(" · "),
                    color = Color.White.copy(alpha = 0.32f),
                    fontSize = 8.sp,
                    lineHeight = 11.sp
                )
            }
        }
        val a = e.audio
        if (a != null) {
            Text(
                text = stringResource(
                    R.string.codec_probe_audio_line,
                    a.maxChannels,
                    a.minSampleRate,
                    a.maxSampleRate,
                    CodecCapabilityModel.bitrateRangeText(a.minBitrate, a.maxBitrate),
                    a.maxInstances
                ),
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 9.sp,
                lineHeight = 12.sp
            )
        }
    }
}

private const val COLLAPSED_COUNT = 6

private fun copyReport(context: Context, report: CodecCapabilityModel.CodecReport) {
    val text = CodecCapabilityModel.buildReportText(report)
    try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("codec-report", text))
        Toast.makeText(context, R.string.codec_probe_copied, Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, R.string.codec_probe_copy_failed, Toast.LENGTH_SHORT).show()
    }
}
