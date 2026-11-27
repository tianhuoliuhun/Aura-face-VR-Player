package com.example.vr

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * 播放器媒体探测与网络错误识别纯辅助方法（从 VRPlayerScreen.kt 拆出）。
 */
internal object VRPlayerMediaHelpers {

    /**
     * v2.6.13：探测「这个片源的真实分辨率是否超出本机**全部**硬件解码器的能力」。
     */
    fun probeOversizedVideo(context: Context, uri: Uri): String? {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            val configured = try {
                if (uri.scheme == "content" || uri.scheme == "file") {
                    retriever.setDataSource(context, uri)
                } else {
                    retriever.setDataSource(uri.toString(), hashMapOf<String, String>())
                }
                true
            } catch (e: Exception) {
                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use {
                        retriever.setDataSource(it.fileDescriptor)
                    }
                    true
                } catch (e2: Exception) {
                    false
                }
            }
            if (!configured) return null

            val w = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: return null
            val h = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: return null
            if (w <= 0 || h <= 0) return null

            val containerMime = retriever
                .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val candidates = if (containerMime != null && containerMime.startsWith("video/")) {
                listOf(containerMime)
            } else {
                listOf("video/hevc", "video/av1", "video/avc")
            }
            val anySupported = candidates.any {
                DecoderCapabilities.findHardwareFit(it, w, h).supported
            }
            if (anySupported) null else "$w×$h"
        } catch (e: Exception) {
            Log.w("VRPlayerScreen", "probeOversizedVideo 失败：${e.javaClass.simpleName}")
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * v2.8.4（P1）：判断播放异常链中是否包含 HTTP 403 (Forbidden) 或 410 (Gone) 鉴权失效错误。
     */
    fun isHttpAuthOrForbiddenError(throwable: Throwable?): Boolean {
        var cur: Throwable? = throwable
        while (cur != null) {
            if (cur is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                if (cur.responseCode == 403 || cur.responseCode == 410) {
                    return true
                }
            }
            val msg = cur.message?.lowercase() ?: ""
            if (msg.contains("response code: 403") || msg.contains("response code: 410") ||
                msg.contains("http 403") || msg.contains("http 410") ||
                msg.contains("forbidden")
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }
}
