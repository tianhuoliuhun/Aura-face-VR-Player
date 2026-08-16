package com.example.vr

import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.nio.ByteBuffer

/**
 * Experimental 8K hardware-decode helpers, all opt-in via settings (default off):
 *
 *  A. ForcedHardwareMediaCodecSelector: skips the platform's resolution/capability
 *     filtering and hands the stream to every registered hardware decoder so the
 *     driver itself gets a chance to actually decode it.
 *  B. spoofSpsResolution: bit-level rewrite of the SPS width/height (and optionally
 *     level_idc) inside csd-0, so drivers that allocate resources from the header
 *     accept an 8K stream. No re-encoding; artifacts/black screen are possible.
 *  E. ParamsAddingMediaCodecAdapterFactory: injects extra MediaFormat keys
 *     (e.g. KEY_MAX_INPUT_SIZE) into the decoder configuration.
 */
object ExperimentalDecode {

    private const val TAG = "ExperimentalDecode"

    // ==================== A. Forced hardware selector ====================

    object ForcedHardwareMediaCodecSelector : MediaCodecSelector {

        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean
        ): List<MediaCodecInfo> {
            val result = mutableListOf<MediaCodecInfo>()
            try {
                val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                for (info in list.codecInfos) {
                    if (info.isEncoder || !info.isHardwareAccelerated) continue
                    if (!info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) continue
                    try {
                        val caps = info.getCapabilitiesForType(mimeType)
                        val name = info.name
                        val softwareOnly = name.startsWith("OMX.google.", ignoreCase = true) ||
                            name.startsWith("c2.android.", ignoreCase = true)
                        val secure = if (requiresSecureDecoder) {
                            MediaFormat.createVideoFormat(mimeType, 1920, 1080).apply {
                                setFeatureEnabled("feature-secure-playback", true)
                            }.let { caps.isFormatSupported(it) }
                        } else true
                        val tunneling = if (requiresTunnelingDecoder) {
                            MediaFormat.createVideoFormat(mimeType, 1920, 1080).apply {
                                setFeatureEnabled("feature-tunneled-playback", true)
                            }.let { caps.isFormatSupported(it) }
                        } else true
                        if (!secure || !tunneling) continue
                        val mci = buildMediaCodecInfo(
                            name, mimeType, caps,
                            softwareOnly = softwareOnly,
                            adaptive = supportsAdaptive(caps),
                            tunneling = tunneling,
                            secure = secure
                        )
                        if (mci != null) result.add(mci)
                    } catch (e: Exception) {
                        Log.w(TAG, "skip codec ${info.name}: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "forced selector failed, falling back to default", e)
            }
            if (result.isEmpty()) {
                return MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType, requiresSecureDecoder, requiresTunnelingDecoder
                )
            }
            // Prefer vendor drivers over generic software wrappers
            result.sortBy { if (it.softwareOnly) 1 else 0 }
            return result
        }
    }

    /**
     * The MediaCodecInfo constructor is package-private in media3 1.4.1, so it
     * is invoked through reflection (debug builds are not minified).
     */
    private fun supportsAdaptive(caps: android.media.MediaCodecInfo.CodecCapabilities): Boolean {
        return try {
            caps.javaClass.getField("isAdaptivePlaybackSupported").getBoolean(caps)
        } catch (e: Exception) {
            false
        }
    }

    private fun buildMediaCodecInfo(
        name: String,
        mimeType: String,
        caps: android.media.MediaCodecInfo.CodecCapabilities,
        softwareOnly: Boolean,
        adaptive: Boolean,
        tunneling: Boolean,
        secure: Boolean
    ): MediaCodecInfo? {
        return try {
            val ctor = MediaCodecInfo::class.java.declaredConstructors.first()
            ctor.isAccessible = true
            ctor.newInstance(
                name,
                mimeType,
                mimeType,
                caps,
                true,      // hardwareAccelerated
                softwareOnly,
                false,     // vendor
                adaptive,
                tunneling,
                secure
            ) as MediaCodecInfo
        } catch (e: Exception) {
            Log.w(TAG, "reflection MediaCodecInfo failed: ${e.message}")
            null
        }
    }

    // ==================== E. Decoder params injection ====================

    class ParamsAddingMediaCodecAdapterFactory(
        private val inner: MediaCodecAdapter.Factory
    ) : MediaCodecAdapter.Factory {

        override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
            val mime = configuration.format.sampleMimeType
            if (mime != null && mime.startsWith("video/")) {
                try {
                    val mf = MediaFormat(configuration.mediaFormat)
                    val w = if (mf.containsKey(MediaFormat.KEY_WIDTH)) mf.getInteger(MediaFormat.KEY_WIDTH) else 1920
                    val h = if (mf.containsKey(MediaFormat.KEY_HEIGHT)) mf.getInteger(MediaFormat.KEY_HEIGHT) else 1080
                    // Generous input buffer so 8K I-frames are never rejected
                    mf.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, (w * h * 4L).coerceAtLeast(16L * 1024 * 1024).toInt())
                    val cfg = MediaCodecAdapter.Configuration.createForVideoDecoding(
                        configuration.codecInfo,
                        mf,
                        configuration.format,
                        configuration.surface,
                        configuration.crypto
                    )
                    return inner.createAdapter(cfg)
                } catch (e: Exception) {
                    Log.w(TAG, "params injection failed, using original config", e)
                }
            }
            return inner.createAdapter(configuration)
        }
    }

    // ==================== B. SPS resolution spoofing ====================

    /**
     * Rewrites the width/height (and optionally level_idc) inside the SPS of
     * csd-0, keeping all other syntax elements intact. Returns a new csd-0 or
     * null when the SPS cannot be safely rewritten (e.g. H.264 scaling matrix
     * present, or Annex B input).
     */
    fun spoofSpsResolution(
        format: MediaFormat,
        targetWidth: Int,
        targetHeight: Int,
        levelIdc: Int? = null
    ): ByteBuffer? {
        try {
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val isHevc = mime.contains("hevc", ignoreCase = true) || mime.contains("h265", ignoreCase = true)
            val isAvc = mime.contains("avc", ignoreCase = true) || mime.contains("h264", ignoreCase = true)
            if (!isHevc && !isAvc) return null
            val csd0 = format.getByteBuffer("csd-0") ?: return null
            val data = ByteArray(csd0.remaining())
            val pos = csd0.position()
            csd0.get(data)
            csd0.position(pos)

            // Only length-prefixed (AVCC) csd-0 is rewritten
            if (data.size < 4) return null
            if (data[0].toInt() == 0 && data[1].toInt() == 0 &&
                (data[2].toInt() == 1 || (data[2].toInt() == 0 && data[3].toInt() == 1))
            ) {
                return null
            }

            val out = java.io.ByteArrayOutputStream()
            var i = 0
            var changed = false
            while (i + 4 <= data.size) {
                val len = ((data[i].toInt() and 0xFF) shl 24) or
                    ((data[i + 1].toInt() and 0xFF) shl 16) or
                    ((data[i + 2].toInt() and 0xFF) shl 8) or
                    (data[i + 3].toInt() and 0xFF)
                val nalStart = i + 4
                if (len <= 0 || nalStart + len > data.size) break
                val nal = data.copyOfRange(nalStart, nalStart + len)
                val rewritten = if (isHevc) {
                    rewriteHevcSps(nal, targetWidth, targetHeight, levelIdc)
                } else {
                    rewriteAvcSps(nal, targetWidth, targetHeight, levelIdc)
                }
                if (rewritten != null) {
                    changed = true
                    writeU32(out, rewritten.size)
                    out.write(rewritten)
                } else {
                    out.write(data, i, 4 + len)
                }
                i = nalStart + len
            }
            if (!changed) return null
            val result = ByteBuffer.wrap(out.toByteArray())
            Log.i(TAG, "SPS spoofed to ${targetWidth}x${targetHeight} (mime=$mime)")
            return result
        } catch (e: Exception) {
            Log.w(TAG, "SPS spoof skipped", e)
            return null
        }
    }

    // ---- bit helpers ----

    private class BitReader(private val data: ByteArray) {
        var pos = 0
            private set
        private var ended = false

        fun readBit(): Int {
            if (ended) return 0
            if (pos >= data.size * 8) { ended = true; return 0 }
            val b = data[pos / 8].toInt() and 0xFF
            val v = (b shr (7 - pos % 8)) and 1
            pos++
            return v
        }

        fun readBits(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or readBit() }
            return v
        }

        fun readUe(): Int {
            var zeros = 0
            while (readBit() == 0) zeros++
            return if (zeros == 0) 0 else ((1 shl zeros) - 1) + readBits(zeros)
        }
    }

    private class BitWriter {
        private val bytes = java.io.ByteArrayOutputStream()
        private var current = 0
        private var bitCount = 0

        fun writeBit(b: Int) {
            current = (current shl 1) or (b and 1)
            bitCount++
            if (bitCount == 8) {
                bytes.write(current)
                current = 0
                bitCount = 0
            }
        }

        fun writeBits(v: Int, n: Int) {
            for (i in n - 1 downTo 0) writeBit((v shr i) and 1)
        }

        fun writeUe(v: Int) {
            var x = v + 1
            val len = 32 - Integer.numberOfLeadingZeros(x)
            repeat(len - 1) { writeBit(0) }
            writeBits(x, len)
        }

        fun finish(): ByteArray {
            if (bitCount > 0) bytes.write(current shl (8 - bitCount))
            return bytes.toByteArray()
        }
    }

    private fun removeEmulation(bytes: ByteArray): ByteArray {
        val out = ByteArray(bytes.size)
        var o = 0
        var zeros = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v == 3) {
                zeros = 0
                continue
            }
            out[o++] = b
            if (v == 0) zeros++ else zeros = 0
        }
        return out.copyOf(o)
    }

    private fun insertEmulation(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var zeros = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(v)
            if (v == 0) zeros++ else zeros = 0
        }
        return out.toByteArray()
    }

    private fun writeU32(out: java.io.ByteArrayOutputStream, v: Int) {
        out.write((v shr 24) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private val HEVC_SPS_TYPE = 33
    private val AVC_SPS_TYPE = 7
    private val HIGH_PROFILES = intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /**
     * HEVC SPS (NAL data includes the 2-byte NAL header).
     * RBSP: vps_id(4)+sub_layers(3)+nesting(1) + profile_tier_level(95 bits,
     * level_idc at bits 87..94) + seq_id(ue) + chroma_format_idc(ue) [+sep flag]
     * + pic_width(ue) + pic_height(ue) + conformance_window_flag(1) [+4 ue] +
     * rest copied verbatim.
     */
    private fun rewriteHevcSps(nal: ByteArray, w: Int, h: Int, levelIdc: Int?): ByteArray? {
        if (nal.size < 4) return null
        if (((nal[0].toInt() and 0xFF) shr 1) and 0x3F != HEVC_SPS_TYPE) return null
        if (w <= 0 || h <= 0) return null
        val rbsp = removeEmulation(nal.copyOfRange(2, nal.size))
        val r = BitReader(rbsp)
        val first8 = r.readBits(8)
        // profile_tier_level: 95 bits; level_idc is the last 8 bits
        val ptl = IntArray(87)
        for (i in 0 until 87) ptl[i] = r.readBit()
        val oldLevel = r.readBits(8)
        val seqId = r.readUe()
        val chroma = r.readUe()
        if (chroma == 3) r.readBit() // separate_colour_plane_flag
        val oldW = r.readUe()
        val oldH = r.readUe()
        val confFlag = r.readBit()
        val conf = IntArray(4)
        if (confFlag == 1) for (i in 0 until 4) conf[i] = r.readUe()
        // everything after is copied verbatim
        val restBits = maxOf(0, rbsp.size * 8 - r.pos)

        val wr = BitWriter()
        wr.writeBits(first8, 8)
        for (i in 0 until 87) wr.writeBit(ptl[i])
        wr.writeBits(levelIdc ?: oldLevel, 8)
        wr.writeUe(seqId)
        wr.writeUe(chroma)
        if (chroma == 3) wr.writeBit(0)
        wr.writeUe(w)
        wr.writeUe(h)
        wr.writeBit(0) // conformance_window_flag = 0, reset cropping to avoid out-of-range
        // copy the remaining raw bits
        copyRawBits(r, wr, restBits)
        val newRbsp = wr.finish()
        return insertEmulation(newRbsp).let { ep ->
            ByteArray(2 + ep.size).also { out ->
                out[0] = nal[0]
                out[1] = nal[1]
                System.arraycopy(ep, 0, out, 2, ep.size)
            }
        }
    }

    /**
     * H.264 SPS (NAL data includes the 1-byte NAL header).
     * RBSP: profile_idc(8)+constraint(8)+level_idc(8) + seq_id(ue) [+high
     * profile extras] + pic_width_mbs_minus1(ue) + pic_height_map_units(ue) +
     * frame_mbs_only(1) [+mb_adaptive(1)] + direct_8x8(1) + cropping(1) [+4 ue]
     * + rest copied verbatim. Scaling matrices are not supported (returns null).
     */
    private fun rewriteAvcSps(nal: ByteArray, w: Int, h: Int, levelIdc: Int?): ByteArray? {
        if (nal.size < 4) return null
        if ((nal[0].toInt() and 0x1F) != AVC_SPS_TYPE) return null
        if (w % 16 != 0 || h % 16 != 0) return null
        val rbsp = removeEmulation(nal.copyOfRange(1, nal.size))
        val r = BitReader(rbsp)
        val profile = r.readBits(8)
        val constraint = r.readBits(8)
        val oldLevel = r.readBits(8)
        val seqId = r.readUe()
        if (profile in HIGH_PROFILES) {
            val chroma = r.readUe()
            if (chroma == 3) r.readBit()
            r.readUe() // bit_depth_luma_minus8
            r.readUe() // bit_depth_chroma_minus8
            r.readBit() // qpprime_y_zero_transform_bypass_flag
            val scalingPresent = r.readBit()
            if (scalingPresent == 1) return null // unsupported
        }
        r.readUe() // pic_width_in_mbs_minus1 (discard, we rewrite)
        r.readUe() // pic_height_in_map_units_minus1 (discard, we rewrite)
        val frameMbsOnly = r.readBit()
        if (frameMbsOnly == 0) r.readBit() // mb_adaptive_frame_field_flag
        r.readBit() // direct_8x8_inference_flag
        val cropFlag = r.readBit()
        if (cropFlag == 1) {
            r.readUe(); r.readUe(); r.readUe(); r.readUe()
        }
        val restBits = maxOf(0, rbsp.size * 8 - r.pos)

        val wr = BitWriter()
        wr.writeBits(profile, 8)
        wr.writeBits(constraint, 8)
        wr.writeBits(levelIdc ?: oldLevel, 8)
        wr.writeUe(seqId)
        wr.writeUe(w / 16 - 1)
        wr.writeUe(h / 16 - 1)
        wr.writeBit(frameMbsOnly)
        if (frameMbsOnly == 0) wr.writeBit(0)
        wr.writeBit(1) // direct_8x8_inference_flag
        wr.writeBit(0) // frame_cropping_flag = 0, reset cropping
        copyRawBits(r, wr, restBits)
        val newRbsp = wr.finish()
        return insertEmulation(newRbsp).let { ep ->
            ByteArray(1 + ep.size).also { out ->
                out[0] = nal[0]
                System.arraycopy(ep, 0, out, 1, ep.size)
            }
        }
    }

    private fun copyRawBits(r: BitReader, wr: BitWriter, count: Int) {
        var remaining = count
        while (remaining >= 32) {
            wr.writeBits(r.readBits(32), 32)
            remaining -= 32
        }
        while (remaining >= 8) {
            wr.writeBits(r.readBits(8), 8)
            remaining -= 8
        }
        while (remaining > 0) {
            wr.writeBit(r.readBit())
            remaining--
        }
    }
}
