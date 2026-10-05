package com.example.vr

import android.net.Uri
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/**
 * 当前播放视频的信息快照 —— v2.1.234。
 *
 * ## 为什么要统一成一个结构
 * 三个解码内核各自能拿到的信息**不一样**，如果不统一，UI 就得写三套；
 * 而本项目「同一功能两份实现」已经出过 6 次事故（语言面板、字幕面板、玻璃档位…）。
 * 所以这里定一个**字段可能为空**的统一结构：谁能给就填谁，给不了就留空，
 * UI 只认这一个结构 —— 加内核时只加一个转换函数，UI 一行都不用动。
 *
 * ## 各内核能提供到什么程度
 * | 字段 | Exo | ijk | mpv |
 * |---|---|---|---|
 * | 分辨率 / 时长 | ✅ | ✅ | ✅ |
 * | 视频编码 | ✅（mime/codecs） | ⚠️ 仅像素格式 | ✅ |
 * | 帧率 | ✅ | ❌ | ✅ |
 * | 码率 | ✅ | ❌ | ✅ |
 * | 容器格式 | ⚠️ 按扩展名推断 | ❌ | ✅ |
 * | 音轨编码/声道/采样率 | ✅ | ⚠️ 部分 | ✅ |
 * | 实际硬解方式 | ✅ | ❌ | ✅ |
 *
 * 空值在 UI 上统一显示为「—」，不显示 "null"。
 */
data class VideoInfo(
    /** 来源内核，用于在 UI 上标明"这些数据是哪个内核报的" */
    val engine: DecoderEngine,
    /** 片名 / 文件路径（取文件名） */
    val title: String? = null,
    /** 容器封装，如 mp4 / mkv / flv */
    val container: String? = null,
    /** 视频编码名，如 h264 / hevc / av1 / vp9 */
    val videoCodec: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    /** 帧率（fps） */
    val frameRate: Double = 0.0,
    /** 视频码率（bps） */
    val videoBitrate: Long = 0L,
    /** 总码率（bps） */
    val totalBitrate: Long = 0L,
    val durationMs: Long = 0L,
    val audioCodec: String? = null,
    val audioChannels: Int = 0,
    val audioSampleRate: Int = 0,
    val audioBitrate: Long = 0L,
    /** 实际生效的解码方式（硬解/软解），mpv 与 Exo 可给 */
    val decoding: String? = null
) {
    /** UI 用：把 bps 格式化成 kbps / Mbps。 */
    fun bitrateText(bps: Long): String = when {
        bps <= 0L -> "—"
        bps >= 1_000_000L -> String.format("%.1f Mbps", bps / 1_000_000.0)
        bps >= 1_000L -> String.format("%.0f kbps", bps / 1_000.0)
        else -> "$bps bps"
    }

    /** UI 用：毫秒 → hh:mm:ss。 */
    fun durationText(): String {
        if (durationMs <= 0L) return "—"
        val total = durationMs / 1000
        return String.format(
            "%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60
        )
    }
}

/** 从 URI 猜容器（Exo 没有直接给出封装的 API，只能按扩展名推）。 */
private fun guessContainer(uri: Uri?): String? {
    val path = uri?.path ?: return null
    val ext = path.substringAfterLast('.', "")
    return ext.takeIf { it.isNotEmpty() && it.length <= 5 }?.lowercase()
}

/** 从文件名取片名（Uri 的 lastPathSegment 在 content:// 下往往是纯数字 id，尽量取 path）。 */
private fun guessTitle(uri: Uri?): String? {
    val seg = uri?.path?.trimEnd('/')?.substringAfterLast('/') ?: return null
    return seg.takeIf { it.isNotBlank() }
}

/**
 * 把 Exo 的 [MimeTypes] 转成人能读的编码名。
 *
 * ⚠️ Exo 给的是 `video/avc` / `video/hevc` 这类 **MIME**，跟用户熟悉的
 * `h264` / `hevc` 对不上，所以做一次映射。`format.codecs` 里有更精确的
 * profile（如 `avc1.640028`），优先用它显示。
 */
private fun friendlyVideoCodec(format: Format): String? {
    val byMime = when (format.sampleMimeType) {
        MimeTypes.VIDEO_H264 -> "h264"
        MimeTypes.VIDEO_H265 -> "hevc"
        MimeTypes.VIDEO_AV1 -> "av1"
        MimeTypes.VIDEO_VP9 -> "vp9"
        MimeTypes.VIDEO_VP8 -> "vp8"
        MimeTypes.VIDEO_MP4V -> "mpeg4"
        MimeTypes.VIDEO_MPEG2 -> "mpeg2"
        MimeTypes.VIDEO_H263 -> "h263"
        else -> format.sampleMimeType?.removePrefix("video/")
    } ?: return null
    // codecs 形如 "avc1.640028"；把 profile 一并显示更有用（能看到 High/Main）
    val cs = format.codecs
    return if (!cs.isNullOrBlank() && !cs.equals(byMime, ignoreCase = true)) "$byMime ($cs)" else byMime
}

private fun friendlyAudioCodec(format: Format): String? = when (format.sampleMimeType) {
    MimeTypes.AUDIO_AAC -> "aac"
    MimeTypes.AUDIO_MPEG -> "mp3"
    MimeTypes.AUDIO_AC3 -> "ac3"
    MimeTypes.AUDIO_E_AC3 -> "eac3"
    MimeTypes.AUDIO_DTS -> "dts"
    MimeTypes.AUDIO_DTS_HD -> "dts-hd"
    MimeTypes.AUDIO_TRUEHD -> "truehd"
    MimeTypes.AUDIO_OPUS -> "opus"
    MimeTypes.AUDIO_VORBIS -> "vorbis"
    MimeTypes.AUDIO_FLAC -> "flac"
    MimeTypes.AUDIO_ALAC -> "alac"
    MimeTypes.AUDIO_AMR_NB -> "amr-nb"
    MimeTypes.AUDIO_AMR_WB -> "amr-wb"
    else -> format.sampleMimeType?.removePrefix("audio/")
}

/**
 * 从 ExoPlayer 的当前轨道提取信息。
 *
 * ⚠️ **不能缓存结果**：`currentTracks` 在 prepare 完成前后是不同的对象
 * （prepare 前是空 Tracks），且切换码流/轨道后也会变。每次打开面板时现取。
 */
fun exoVideoInfo(
    player: androidx.media3.exoplayer.ExoPlayer,
    uri: Uri?,
    isSoftwareDecoding: Boolean
): VideoInfo {
    var out = VideoInfo(
        engine = DecoderEngine.EXO,
        title = guessTitle(uri),
        container = guessContainer(uri),
        durationMs = player.duration.coerceAtLeast(0L),
        decoding = if (isSoftwareDecoding) "软解" else "硬解"
    )
    try {
        val tracks = player.currentTracks
        tracks.groups.forEach { group ->
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                val mime = f.sampleMimeType ?: continue
                if (MimeTypes.isVideo(mime) && out.width == 0) {
                    out = out.copy(
                        videoCodec = friendlyVideoCodec(f),
                        width = f.width.coerceAtLeast(0),
                        height = f.height.coerceAtLeast(0),
                        frameRate = if (f.frameRate > 0f) f.frameRate.toDouble() else 0.0,
                        videoBitrate = if (f.bitrate > 0) f.bitrate.toLong() else 0L,
                        totalBitrate = if (f.bitrate > 0) f.bitrate.toLong() else 0L
                    )
                } else if (MimeTypes.isAudio(mime) && out.audioCodec == null) {
                    out = out.copy(
                        audioCodec = friendlyAudioCodec(f),
                        audioChannels = f.channelCount.coerceAtLeast(0),
                        audioSampleRate = f.sampleRate.coerceAtLeast(0),
                        audioBitrate = if (f.bitrate > 0) f.bitrate.toLong() else 0L
                    )
                }
            }
        }
    } catch (t: Throwable) {
        // 轨道还没就绪时读会抛，忽略即可 —— 面板会显示「—」
    }
    return out
}

/**
 * 取当前播放器的信息 —— **三个内核的统一入口**。
 *
 * 刻意做成"一处分发"而不是让 UI 自己判断类型：本项目「同一逻辑多份实现」
 * 已经出过 6 次事故（语言面板 / 字幕面板 / 玻璃档位…），凡是"按内核分支"
 * 的地方都尽量收敛到一个函数里。
 *
 * ⚠️ Exo 的信息来自 `currentTracks`，它在 prepare 完成前后不同、切流后也会变，
 *    所以**每次打开面板都重新取**，不要缓存成长期 state。
 */
fun currentVideoInfo(
    player: VrPlayerBackend?,
    uri: Uri?,
    isSoftwareDecoding: Boolean
): VideoInfo? = when (player) {
    null -> null
    is MpvBackend -> player.videoInfo()
    is IjkBackend -> player.videoInfo()
    // ⚠️ 新增内核时必须在这里补一个分支 —— 否则会静默落到 else，
    //    而 SystemBackend/IjkBackend/MpvBackend 的 `exo` 都是 null，
    //    `player.exo?.let { … }` 直接返回 null → 面板显示「暂无信息」，
    //    看起来像"读不到元数据"，实际是分发漏了。
    is SystemBackend -> player.videoInfo()
    else -> player.exo?.let { exoVideoInfo(it, uri, isSoftwareDecoding) }
}
