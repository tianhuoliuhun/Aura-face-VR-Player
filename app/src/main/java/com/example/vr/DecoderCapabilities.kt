package com.example.vr

import android.media.MediaCodecList
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
     * Returns the largest resolution supported by any hardware decoder for the
     * given mime type, or null when no hardware decoder exists.
     */
    fun getHardwareDecoderMax(mimeType: String): DecoderCap? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        var best: DecoderCap? = null
        for (info in list.codecInfos) {
            if (info.isEncoder || !info.isHardwareAccelerated) continue
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
