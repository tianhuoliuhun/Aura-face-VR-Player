package com.example.vr

import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException

/**
 * 直播流断流自动重连（指数退避）与清晰度标签格式化纯逻辑工具。
 */
object LiveReconnectPolicy {
    const val MAX_ATTEMPTS = 6
    const val BASE_DELAY_MS = 2_000L
    const val MAX_DELAY_MS = 30_000L

    /**
     * 计算第 [attemptIndex] 次（从 0 开始）重连前的等待时间（毫秒）。
     * 序列：2s -> 4s -> 8s -> 16s -> 30s -> 30s
     */
    fun nextDelayMs(attemptIndex: Int): Long {
        if (attemptIndex <= 0) return BASE_DELAY_MS
        val shift = attemptIndex.coerceAtMost(10)
        val raw = BASE_DELAY_MS shl shift
        return raw.coerceAtMost(MAX_DELAY_MS)
    }

    /**
     * 判断该 Exo 错误是否属于直播场景下可通过重连自愈的瞬时/网络/窗口落后类错误。
     * 解码器不支持（DECODER_INIT_FAILED / DECODING_FAILED）或 403/401 鉴权失败不走纯重连。
     */
    fun isRetryableLiveError(errorCode: Int, exoType: Int): Boolean {
        if (errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR ||
            errorCode == PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED
        ) {
            return false
        }
        if (errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) return true
        if (errorCode in PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) {
            // IO_BAD_HTTP_STATUS 可能是 403/404，403 已在 Cookie 刷新逻辑前置拦截；
            // 其余 IO 错误（断网、超时、连接重置）及网络恢复后的 5xx 均允许退避重连。
            return true
        }
        if (errorCode in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED) {
            // 直播切片切换瞬间的清单/分片解析异常往往是临时缺片，值得重连
            return true
        }
        return exoType == ExoPlaybackException.TYPE_SOURCE ||
            exoType == ExoPlaybackException.TYPE_UNEXPECTED
    }

    /**
     * 将视频轨分辨率与码率格式化为易读清晰的档位文本（如 `1080p · 5.2 Mbps`）。
     */
    fun formatVideoTrackLabel(
        width: Int,
        height: Int,
        bitrateBps: Int,
        fallbackIndex: Int
    ): String {
        val resPart = when {
            width > 0 && height > 0 -> {
                val minSide = minOf(width, height)
                val tag = when {
                    maxOf(width, height) >= 7680 -> "8K"
                    maxOf(width, height) >= 3840 -> "4K"
                    minSide >= 2160 -> "4K"
                    minSide >= 1440 -> "1440p"
                    minSide >= 1080 -> "1080p"
                    minSide >= 720 -> "720p"
                    minSide >= 480 -> "480p"
                    else -> "${minSide}p"
                }
                "$tag (${width}×${height})"
            }
            height > 0 -> "${height}p"
            else -> "#${fallbackIndex + 1}"
        }
        val bpsPart = when {
            bitrateBps >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f Mbps", bitrateBps / 1_000_000.0)
            bitrateBps >= 1_000 -> "${bitrateBps / 1_000} kbps"
            else -> null
        }
        return if (bpsPart != null) "$resPart · $bpsPart" else resPart
    }
}
