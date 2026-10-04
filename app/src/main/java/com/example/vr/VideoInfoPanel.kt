package com.example.vr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import kotlinx.coroutines.delay

/**
 * 「视频信息」面板 —— v2.1.234。
 *
 * 显示当前片源的容器 / 视频编码 / 分辨率 / 帧率 / 码率 / 音轨 / 实际解码方式。
 *
 * ## 设计要点
 * 1. **数据只认 [VideoInfo] 一个结构**，三个内核（Exo / ijk / mpv）各自填自己能给的字段，
 *    给不了的显示「—」。UI 不需要知道当前用的是哪个内核 —— 加内核时这里一行都不用改。
 * 2. **自己负责刷新**：mpv/Exo 的信息在文件加载完成后才有效，且播放中可能变化
 *    （切码流、VIDEO_RECONFIG）。所以内部带一个 1 秒的 tick 定时重取，
 *    而不是指望外部的重组时机 —— 那样会在"面板刚展开但还没加载完"时一直显示空。
 * 3. 只在**展开时**才订阅（由调用方用 if 包住），避免长期跑一个每秒的协程。
 */
@Composable
fun VideoInfoPanel(
    player: VrPlayerBackend?,
    uri: android.net.Uri?,
    isSoftwareDecoding: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    // 每秒重取一次：够用且几乎无开销（读的是各自内存里的值，mpv 走一次 JNI getProperty）
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(player) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    val info: VideoInfo? = remember(player, isSoftwareDecoding, tick) {
        currentVideoInfo(player, uri, isSoftwareDecoding)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.video_info_section),
                color = accentColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = info?.engine?.let { stringResource(it.labelRes) }
                    ?: stringResource(R.string.video_info_no_source),
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 8.sp
            )
        }

        if (info == null) {
            Text(
                text = stringResource(R.string.video_info_empty),
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 8.sp
            )
            return@Column
        }

        InfoRow(
            label = stringResource(R.string.video_info_resolution),
            value = if (info.width > 0 && info.height > 0) "${info.width} × ${info.height}" else null
        )
        InfoRow(label = stringResource(R.string.video_info_vcodec), value = info.videoCodec)
        InfoRow(
            label = stringResource(R.string.video_info_fps),
            value = if (info.frameRate > 0.0) String.format("%.2f fps", info.frameRate) else null
        )
        InfoRow(
            label = stringResource(R.string.video_info_bitrate),
            value = if (info.totalBitrate > 0L) info.bitrateText(info.totalBitrate)
            else if (info.videoBitrate > 0L) info.bitrateText(info.videoBitrate) else null
        )
        InfoRow(label = stringResource(R.string.video_info_container), value = info.container)
        InfoRow(label = stringResource(R.string.video_info_duration), value = info.durationText())
        InfoRow(label = stringResource(R.string.video_info_acodec), value = info.audioCodec)
        InfoRow(
            label = stringResource(R.string.video_info_audio_detail),
            value = buildString {
                if (info.audioChannels > 0) append(channelText(info.audioChannels))
                if (info.audioSampleRate > 0) {
                    if (isNotEmpty()) append(" / ")
                    append("${info.audioSampleRate} Hz")
                }
            }.takeIf { it.isNotEmpty() }
        )
        InfoRow(label = stringResource(R.string.video_info_decoding), value = info.decoding)
    }
}

/** 声道数 → 人能读的写法（2 声道 = 立体声）。 */
private fun channelText(channels: Int): String = when (channels) {
    1 -> "1 (单声道)"
    2 -> "2 (立体声)"
    6 -> "6 (5.1)"
    8 -> "8 (7.1)"
    else -> channels.toString()
}

/**
 * 一行 "标签  值"。值为空时显示「—」——
 * 统一在这里处理，避免每个调用点各写一遍（也就不会出现有的地方显示 "null"）。
 */
@Composable
private fun InfoRow(label: String, value: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 8.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value ?: "—",
            color = if (value != null) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.3f),
            fontSize = 8.sp,
            fontWeight = if (value != null) FontWeight.Medium else FontWeight.Normal
        )
    }
}
