package com.example.vr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import org.json.JSONObject
import java.io.File

/**
 * v85：后台批处理转写——从视频文件提取音频轨（MediaExtractor + MediaCodec 解码
 * 为 PCM16），降混单声道、重采样 16kHz 后喂给 Vosk 识别，最终生成带真实
 * 视频时间戳的 SRT 字幕文件。全程在后台协程执行，不依赖播放状态。
 */
class AsrBatchTranscriber(private val context: Context) {

    @Volatile var isRunning = false
    @Volatile var progress = 0f          // 0..1（按解码音频时间/视频总时长）
    @Volatile var statusMessage = ""

    companion object {
        // VAD 静音阈值：低于该 RMS 能量视为静音（跳过喂入，防噪声误识别）
        private const val VAD_THRESHOLD = 0.008f
        // 连续静音达到该时长后喂一个静音块，帮助 Vosk 分句
        private const val VAD_RESET_MS = 300L
    }

    /**
     * 转写视频并生成 SRT 文件。
     * @param modelProvider 由 RealtimeAsrManager.ensureModelForBatch 提供模型目录
     * @return 生成的 SRT 文件，失败返回 null
     */
    suspend fun transcribeToSrt(
        mediaUri: Uri,
        videoTitle: String?,
        modelOption: VoskModelOption,
        modelProvider: suspend (VoskModelOption) -> File?,
        onStatus: (String) -> Unit = {},
        onProgress: (Float) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        isRunning = true
        progress = 0f
        try {
            statusMessage = "准备 ${modelOption.label} 识别模型..."
            onStatus(statusMessage)
            val modelDir = modelProvider(modelOption)
            if (modelDir == null) {
                statusMessage = "模型不可用，请检查网络后重试"
                onStatus(statusMessage)
                return@withContext null
            }

            statusMessage = "正在提取音轨并识别..."
            onStatus(statusMessage)
            Log.i("AsrBatch", "transcribe start: uri=$mediaUri model=${modelOption.modelName}")
            val cues = extractAndRecognize(mediaUri, modelDir, onProgress)
            if (cues.isEmpty()) {
                statusMessage = "未识别到语音内容（或音轨解码失败）"
                onStatus(statusMessage)
                return@withContext null
            }

            val srtFile = writeSrt(mediaUri, videoTitle, cues)
            statusMessage = "字幕生成完成：${cues.size} 句"
            onStatus(statusMessage)
            onProgress(1f)
            srtFile
        } catch (e: Exception) {
            Log.e("AsrBatch", "transcribe failed", e)
            statusMessage = "转写失败: ${e.message}"
            onStatus(statusMessage)
            null
        } finally {
            isRunning = false
        }
    }

    // ===== 音频提取 + 解码 + 识别 =====
    private fun extractAndRecognize(
        mediaUri: Uri,
        modelDir: File,
        onProgress: (Float) -> Unit
    ): List<SubtitleCue> {
        val model = Model(modelDir.absolutePath)
        val recognizer = Recognizer(model, 16000f)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, mediaUri, null)

            // 选择音频轨
            var audioTrack = -1
            var mime = ""
            var durationUs = 0L
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    audioTrack = i
                    mime = m
                    durationUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
                    break
                }
            }
            if (audioTrack < 0) {
                Log.e("AsrBatch", "no audio track found")
                return emptyList()
            }
            extractor.selectTrack(audioTrack)
            Log.i("AsrBatch", "audio track: mime=$mime durationUs=$durationUs")

            // 解码器（同步模式）
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(extractor.getTrackFormat(audioTrack), null, null, 0)
            codec.start()
            Log.i("AsrBatch", "codec started: $mime")

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            // 识别状态
            val cues = mutableListOf<SubtitleCue>()
            var monoBuf = FloatArray(0)          // 源采样率 mono 累积
            var chunkStartUs = 0L                // 当前块在视频中的起始时间
            var sentenceStartUs = 0L             // 当前句子起始时间
            var lastEndUs = 0L
            var outputRate = 16000
            var outputChannels = 1
            var silentRunMs = 0L                 // v88 VAD：连续静音时长
            var fedSilenceReset = false          // v88 VAD：是否已喂分句静音块

            while (!outputDone) {
                // --- 输入 ---
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx) ?: continue
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // --- 输出 ---
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx) ?: continue
                    val ptsUs = bufferInfo.presentationTimeUs
                    val fmt = codec.outputFormat
                    if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        outputRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        outputChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    val bytes = ByteArray(bufferInfo.size)
                    outBuf.get(bytes)
                    codec.releaseOutputBuffer(outIdx, false)

                    // PCM16 交错 → mono float
                    val frames = bytes.size / (2 * outputChannels.coerceAtLeast(1))
                    val mono = FloatArray(frames)
                    for (f in 0 until frames) {
                        var sum = 0
                        for (ch in 0 until outputChannels.coerceAtLeast(1)) {
                            val off = (f * outputChannels + ch) * 2
                            val v = (bytes[off].toInt() and 0xff) or (bytes[off + 1].toInt() shl 8)
                            sum += v.toShort().toInt()
                        }
                        mono[f] = sum / outputChannels.coerceAtLeast(1).toFloat() / 32768f
                    }

                    // 累积并喂识别器（每 100ms 源音频一块）
                    val combined = FloatArray(monoBuf.size + mono.size)
                    System.arraycopy(monoBuf, 0, combined, 0, monoBuf.size)
                    System.arraycopy(mono, 0, combined, monoBuf.size, mono.size)
                    monoBuf = combined

                    val chunkLen = (outputRate / 10).coerceAtLeast(160) // 100ms
                    while (monoBuf.size >= chunkLen) {
                        val chunk = monoBuf.copyOfRange(0, chunkLen)
                        monoBuf = monoBuf.copyOfRange(chunkLen, monoBuf.size)
                        if (chunkStartUs == 0L) chunkStartUs = ptsUs.coerceAtLeast(0L)
                        if (sentenceStartUs == 0L) sentenceStartUs = chunkStartUs

                        // v88 VAD 静音过滤：低能量块跳过喂入（省 CPU + 减少噪声误识别），
                        // 连续静音 ≥300ms 时喂一个静音块帮助 Vosk 分句
                        var rms = 0f
                        for (s in chunk) rms += s * s
                        rms = kotlin.math.sqrt(rms / chunk.size)
                        val isSilent = rms < VAD_THRESHOLD
                        val shouldFeed = if (isSilent) {
                            silentRunMs += 100L
                            if (silentRunMs >= 300L && !fedSilenceReset) {
                                fedSilenceReset = true
                                true
                            } else {
                                false
                            }
                        } else {
                            silentRunMs = 0L
                            fedSilenceReset = false
                            true
                        }

                        if (shouldFeed) {
                            val resampled = resampleLinear(chunk, outputRate, 16000)
                            val pcm = floatToPcm16(resampled)
                            val recognized = recognizer.acceptWaveForm(pcm, pcm.size)
                            if (recognized) {
                                // Vosk 自主断句成功（静默/完整句子）
                                val text = parseText(recognizer.result)
                                if (text.isNotBlank()) {
                                    val endUs = chunkStartUs + 100_000L
                                    cues.add(
                                        SubtitleCue(
                                            id = cues.size + 1,
                                            startTimeMs = (sentenceStartUs / 1000).coerceAtLeast(0L),
                                            endTimeMs = (endUs / 1000).coerceAtLeast(sentenceStartUs / 1000 + 500),
                                            text = text
                                        )
                                    )
                                    lastEndUs = endUs
                                    sentenceStartUs = 0L
                                }
                            } else {
                                // v101 自主断句：连续说话时 Vosk 可能很久不返回 true，
                                // 需要超时断句（≥6秒）或标点断句（句末标点）。
                                val partialText = parseText(recognizer.partialResult)
                                if (sentenceStartUs > 0 && partialText.isNotBlank()) {
                                    val elapsed = (chunkStartUs + 100_000L) - sentenceStartUs
                                    val endsWithPunct = partialText.length >= 2 &&
                                        (partialText.last() == '。' || partialText.last() == '！' ||
                                         partialText.last() == '？' || partialText.last() == '.' ||
                                         partialText.last() == '!' || partialText.last() == '?')
                                    if (elapsed > 6_000_000L || endsWithPunct) {
                                        val endUs = chunkStartUs + 100_000L
                                        cues.add(
                                            SubtitleCue(
                                                id = cues.size + 1,
                                                startTimeMs = (sentenceStartUs / 1000).coerceAtLeast(0L),
                                                endTimeMs = (endUs / 1000).coerceAtLeast(sentenceStartUs / 1000 + 500),
                                                text = partialText
                                            )
                                        )
                                        lastEndUs = endUs
                                        sentenceStartUs = 0L
                                        // 重置识别器上下文，防止下一句被上一句残留干扰
                                        recognizer.reset()
                                    }
                                }
                            }
                        }
                        chunkStartUs += 100_000L
                    }

                    // 进度（按已解码音频 PTS / 视频总时长）
                    if (durationUs > 0) {
                        val p = (ptsUs.toFloat() / durationUs).coerceIn(0f, 1f)
                        progress = p
                        onProgress(p)
                        if (cues.size % 100 == 0 && cues.size > 0) {
                            Log.i("AsrBatch", "progress=${(p * 100).toInt()}% cues=${cues.size} ptsUs=$ptsUs")
                        }
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }

            // 收尾：处理剩余缓冲 + finalResult
            val finalText = parseText(recognizer.finalResult)
            if (finalText.isNotBlank()) {
                val start = if (sentenceStartUs > 0) sentenceStartUs else lastEndUs
                cues.add(
                    SubtitleCue(
                        id = cues.size + 1,
                        startTimeMs = (start / 1000).coerceAtLeast(0L),
                        endTimeMs = (chunkStartUs / 1000).coerceAtLeast(start / 1000 + 500),
                        text = finalText
                    )
                )
            }
            progress = 1f
            onProgress(1f)
            Log.i("AsrBatch", "decode done: ${cues.size} cues, last chunkStartUs=$chunkStartUs")
            return cues
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            try { recognizer.close() } catch (_: Exception) {}
            try { model.close() } catch (_: Exception) {}
        }
    }

    // ===== 写 SRT =====
    private fun writeSrt(mediaUri: Uri, videoTitle: String?, cues: List<SubtitleCue>): File? {
        val baseName = videoTitle?.substringBeforeLast('.')
            ?: mediaUri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
            ?: "video"
        val sb = StringBuilder()
        cues.forEachIndexed { i, cue ->
            val wrappedText = wrapText(cue.text, 14)  // v101：按14字自动换行
            sb.append(i + 1).append('\n')
            sb.append(formatTime(cue.startTimeMs)).append(" --> ").append(formatTime(cue.endTimeMs)).append('\n')
            sb.append(wrappedText).append("\n\n")
        }
        val file = File(context.getExternalFilesDir(null), "${baseName}_asr.srt")
        file.parentFile?.mkdirs()
        file.writeText(sb.toString(), Charsets.UTF_8)
        return file
    }

    private fun formatTime(ms: Long): String {
        val safeMs = if (ms < 0) 0L else ms
        val totalSeconds = safeMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val millis = safeMs % 1000
        return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }

    // ===== 工具 =====
    private fun parseText(raw: String): String {
        return try {
            JSONObject(raw).optString("text", "").trim()
        } catch (e: Exception) {
            ""
        }
    }

    private fun floatToPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var i = 0
        for (s in samples) {
            val v = (s.coerceIn(-1f, 1f) * 32767).toInt()
            out[i++] = (v and 0xff).toByte()
            out[i++] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }

    private fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate <= 0 || dstRate <= 0 || srcRate == dstRate) return input
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val outSize = (input.size / ratio).toInt().coerceAtLeast(0)
        val out = FloatArray(outSize)
        for (i in 0 until outSize) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceAtMost(input.size - 1)
            val frac = (pos - i0).toFloat()
            val v0 = input[i0]
            val v1 = input[(i0 + 1).coerceAtMost(input.size - 1)]
            out[i] = v0 + (v1 - v0) * frac
        }
        return out
    }
}
