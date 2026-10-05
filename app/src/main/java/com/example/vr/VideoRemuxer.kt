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
        val message: String? = null,
        /**
         * ⚠️ v2.1.247：源文件**有视频轨但被系统丢弃**（典型：AVI 里的 AV1 ——
         * `MediaExtractor` 认不出 fourcc `AV01`，`trackCount` 里根本没有视频轨）。
         * 这种情况重封装**必然失败或产出纯音频**，调用方应给出针对性提示
         * （改用 MPV 内核），而不是笼统的「容器修复失败」。
         */
        val videoTrackMissing: Boolean = false
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
            // ⚠️ v2.1.247（AVI + AV1 场景）：
            //    若系统 extractor 只给出了音频轨（视频轨 fourcc 不被识别，见
            //    `AviExtractor` 只认 14 个 fourcc、无 `AV01`），重封装出来的必然
            //    是「纯音频 MP4」—— 对用户毫无价值，还会把原播放项换成一个残缺文件。
            //    这里**直接判定失败**并让调用方走「换 MPV 内核」的提示路径。
            if (videoTrack < 0 && audioTrack >= 0) {
                Log.w(TAG, "no video track recognized (AVI+AV1?) — abort remux")
                return RemuxResult(false, false, "no video track", videoTrackMissing = true)
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
                // ⚠️ v2.1.247（AVI 重封装成 MP4 的坑）：
                //    MP4 的 `MediaMuxer` 要求**写入的样本时间戳单调不减**，否则抛
                //    `IllegalArgumentException`（"Timestamp must be monotonically increasing"）。
                //    而 AVI 的 chunk 时间戳**不保证有序**（B 帧交错、老编码器时间基准不同），
                //    直接搬运会让整个 remux 失败。
                //    这里做两件事：
                //      ① 记 `lastPts`，遇到**倒退或相等**的时间戳就**跳过该样本**
                //         （丢掉个别 B 帧首帧，不破坏整体可播性）；
                //      ② 首样本强制为 0（MP4 要求，否则部分播放器会前插黑屏）。
                var lastPts = -1L
                var firstWritten = false
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val sampleTime = extractor.sampleTime
                    // ① 时间戳倒退/相等 → 跳过（不写）
                    if (sampleTime <= lastPts) {
                        if (!extractor.advance()) break
                        continue
                    }
                    bufferInfo.offset = 0
                    bufferInfo.size = size
                    // ② 首样本归零
                    bufferInfo.presentationTimeUs = if (!firstWritten) 0L else sampleTime
                    // ⚠️ 这里**不能**直接 `bufferInfo.flags = extractor.sampleFlags`。
                    //    `MediaExtractor.SAMPLE_FLAG_*` 与 `MediaCodec.BUFFER_FLAG_*` 是
                    //    **两套不同的位定义**，只是「同步帧/keyframe」恰好都等于 1 ——
                    //    直接透传会造成两处错误映射：
                    //      SAMPLE_FLAG_ENCRYPTED(2)     → 被当成 BUFFER_FLAG_CODEC_CONFIG(2)
                    //      SAMPLE_FLAG_PARTIAL_FRAME(4) → 被当成 BUFFER_FLAG_END_OF_STREAM(4)
                    //    后者会让 muxer **提前认为流已结束**，前者会把加密样本交给解码器当配置帧。
                    //    只映射真正对得上的 SYNC 位，其余一律 0（不猜）。
                    bufferInfo.flags =
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                            android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else {
                            0
                        }
                    muxer.writeSampleData(muxerIdx, buffer, bufferInfo)
                    lastPts = sampleTime
                    firstWritten = true
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
