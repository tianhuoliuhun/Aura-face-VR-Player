package com.example.vr

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log

/**
 * Queries the device's hardware video decoders for their maximum supported
 * resolution, so videos that exceed it (e.g. 8192x4096 on a decoder limited to
 * 7680x4320) can be automatically downscaled before hardware decoding.
 */
object DecoderCapabilities {

    private const val TAG = "DecoderCapabilities"

    data class DecoderCap(
        val width: Int,
        val height: Int,
        val name: String
    )

    /**
     * v117 修复：判断编解码器是否为硬件实现。
     *
     * `MediaCodecInfo.isHardwareAccelerated()` 是 **API 29（Android 10）** 才引入的方法，
     * 而本应用 `minSdk = 24`。在 Android 7/8/9 上直接调用会抛 `NoSuchMethodError` ——
     * 它是 `Error` 而非 `Exception`，外层 `catch (e: Exception)` 完全拦不住，直接崩溃。
     * 因此低版本改用编解码器名称的启发式判断（AOSP 与各厂商的软件解码器均有固定命名特征）。
     */
    fun isHardwareAccelerated(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated
        }
        val name = info.name.lowercase()
        return !(name.startsWith("omx.google.") ||
            name.startsWith("c2.android.") ||
            name.contains(".sw.") ||
            name.contains("software"))
    }

    /**
     * Returns the largest resolution supported by any hardware decoder for the
     * given mime type, or null when no hardware decoder exists.
     */
    fun getHardwareDecoderMax(mimeType: String): DecoderCap? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        var best: DecoderCap? = null
        for (info in list.codecInfos) {
            if (info.isEncoder || !isHardwareAccelerated(info)) continue
            val supported = info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            if (!supported) continue
            try {
                val caps = info.getCapabilitiesForType(mimeType)
                val vc = caps.videoCapabilities ?: continue
                val w = vc.supportedWidths.upper
                val h = vc.supportedHeights.upper
                if (best == null || w.toLong() * h > best.width.toLong() * best.height) {
                    best = DecoderCap(w, h, info.name)
                }
            } catch (e: Exception) {
                Log.w(TAG, "capabilities query failed for ${info.name}", e)
            }
        }
        return best
    }

    /**
     * Best-effort hardware decoder max for the common 8K codec (HEVC first,
     * fallback AVC) so callers don't need to know the exact codec.
     */
    fun getBestHardwareDecoderMax(): DecoderCap? {
        return getHardwareDecoderMax("video/hevc")
            ?: getHardwareDecoderMax("video/avc")
    }
}
