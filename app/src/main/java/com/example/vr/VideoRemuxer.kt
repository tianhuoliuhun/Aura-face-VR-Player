package com.example.vr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Re-muxes a video into a fresh MP4 container without re-encoding.
 *
 * Two use cases:
 *  - fixContainer: repairs seek-unfriendly files (moov at the end, fragmented).
 *  - patchLevel: additionally rewrites the codec config (SPS) level_idc to a lower
 *    value, so a hardware decoder whose declared capability is lower (e.g. HEVC
 *    Level 6.0 / 7680x4320) accepts an 8K stream (8192x4096) and hardware-decodes
 *    it. This is an experimental "header spoofing" approach: the elementary
 *    stream data is NOT re-encoded, so decode failure/artifacts are possible.
 */
object VideoRemuxer {

    private const val TAG = "VideoRemuxer"

    data class RemuxResult(
        val success: Boolean,
        val audioIncluded: Boolean,
        val message: String? = null
    )

    fun remux(context: Context, inputUri: Uri, outputFile: File): RemuxResult {
        return doRemux(context, inputUri, outputFile, patchLevelIdc = null, spoofResolution = null)
    }

    /** Re-muxes and rewrites the SPS level_idc (HEVC: RBSP offset 12, H.264: RBSP offset 2). */
    fun remuxWithLevelPatch(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        levelIdc: Int
    ): RemuxResult {
        return doRemux(context, inputUri, outputFile, patchLevelIdc = levelIdc, spoofResolution = null)
    }

    /**
     * Re-muxes and bit-level rewrites the SPS width/height (optionally also the
     * level_idc) so drivers allocating resources from the header accept an 8K
     * stream. No re-encoding; artifacts/black screen are possible.
     */
    fun remuxWithSpsSpoof(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        targetWidth: Int,
        targetHeight: Int,
        levelIdc: Int? = null
    ): RemuxResult {
        return doRemux(
            context, inputUri, outputFile,
            patchLevelIdc = null,
            spoofResolution = Triple(targetWidth, targetHeight, levelIdc)
        )
    }

    private fun doRemux(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        patchLevelIdc: Int?,
        spoofResolution: Triple<Int, Int, Int?>?
    ): RemuxResult {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, inputUri, null)

            val trackCount = extractor.trackCount
            if (trackCount <= 0) return RemuxResult(false, false, "no tracks")

            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) outputFile.delete()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            var videoTrack = -1
            var audioTrack = -1
            for (i in 0 until trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoTrack < 0) videoTrack = i
                else if (mime.startsWith("audio/") && audioTrack < 0) audioTrack = i
            }
            val order = mutableListOf<Int>()
            if (videoTrack >= 0) order.add(videoTrack)
            if (audioTrack >= 0) order.add(audioTrack)
            if (order.isEmpty()) {
                for (i in 0 until trackCount) order.add(i)
            }

            val muxerTracks = IntArray(trackCount) { -1 }
            var audioIncluded = true
            for (t in order) {
                try {
                    val format = extractor.getTrackFormat(t)
                    // Rewrite the codec config SPS level so the hardware decoder
                    // accepts the stream (header spoofing, no re-encoding).
                    if (patchLevelIdc != null) {
                        patchFormatLevel(format, patchLevelIdc)
                    }
                    // Rewrite SPS width/height (and optionally level) to fool
                    // drivers that allocate resources from the header.
                    if (spoofResolution != null) {
                        val newCsd = ExperimentalDecode.spoofSpsResolution(
                            format,
                            spoofResolution.first,
                            spoofResolution.second,
                            spoofResolution.third
                        )
                        if (newCsd != null) {
                            format.setByteBuffer("csd-0", newCsd)
                        }
                    }
                    muxerTracks[t] = muxer.addTrack(format)
                } catch (e: Exception) {
                    Log.w(TAG, "track $t not supported by muxer", e)
                    if (t == audioTrack) audioIncluded = false
                }
            }

            muxer.start()
            val buffer = ByteBuffer.allocate(1 shl 20)
            val bufferInfo = MediaCodec.BufferInfo()

            for (t in order) {
                val muxerIdx = muxerTracks[t]
                if (muxerIdx < 0) continue
                extractor.selectTrack(t)
                buffer.clear()
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    bufferInfo.offset = 0
                    bufferInfo.size = size
                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    bufferInfo.flags = extractor.sampleFlags
                    muxer.writeSampleData(muxerIdx, buffer, bufferInfo)
                    if (!extractor.advance()) break
                }
                extractor.unselectTrack(t)
            }

            muxer.stop()
            muxer.release()
            muxer = null
            extractor.release()
            extractor = null
            return RemuxResult(true, audioIncluded)
        } catch (e: Exception) {
            Log.e(TAG, "remux failed", e)
            return try {
                outputFile.delete()
                RemuxResult(false, false, e.message)
            } catch (e2: Exception) {
                RemuxResult(false, false, e.message)
            }
        } finally {
            try { extractor?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Rewrites the level_idc inside the SPS (csd-0) of the track format.
     *  - H.264 SPS: byte 2 is level_idc
     *  - HEVC SPS: byte 7 is general_level_idc
     * Only the first SPS in csd-0 is patched.
     */
    private fun patchFormatLevel(format: MediaFormat, levelIdc: Int) {
        try {
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return
            val isHevc = mime.contains("hevc", ignoreCase = true) || mime.contains("h265", ignoreCase = true)
            val isAvc = mime.contains("avc", ignoreCase = true) || mime.contains("h264", ignoreCase = true)
            if (!isHevc && !isAvc) return

            val csd0 = format.getByteBuffer("csd-0") ?: return
            val sps = ByteArray(csd0.remaining())
            val pos = csd0.position()
            csd0.get(sps)
            csd0.position(pos)

            val levelIndex = if (isHevc) 7 else 2
            if (sps.size <= levelIndex) return
            val old = sps[levelIndex].toInt() and 0xff
            if (old == levelIdc) return
            sps[levelIndex] = levelIdc.toByte()
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
            Log.i(TAG, "SPS level patched: $old -> $levelIdc (mime=$mime)")
        } catch (e: Exception) {
            Log.w(TAG, "SPS level patch skipped", e)
        }
    }
}
