package com.example.vr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * v110：后台批处理转写（双引擎：Vosk / Qwen3-ASR via sherpa-onnx）
 *
 * 从视频文件提取音频轨（MediaExtractor + MediaCodec 解码为 PCM16），
 * 降混单声道、重采样 16kHz 后喂给选定的 ASR 引擎，最终生成带真实
 * 视频时间戳的 SRT 字幕文件。全程后台协程，不依赖播放状态。
 */
class AsrBatchTranscriber(private val context: Context) {

    @Volatile var isRunning = false
    @Volatile var progress = 0f
    @Volatile var statusMessage = ""

    companion object {
        private const val VAD_THRESHOLD = 0.008f
        private const val VAD_RESET_MS = 300L
    }

    // ===== 主入口（Vosk 引擎）=====
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

            statusMessage = "正在提取音轨并识别（Vosk）..."
            onStatus(statusMessage)
            Log.i("AsrBatch", "transcribe start: uri=$mediaUri engine=Vosk model=${modelOption.modelName}")
            val cues = extractAndRecognizeVosk(mediaUri, modelDir, onProgress)
            finishTranscribe(cues, mediaUri, videoTitle, onStatus, onProgress)
        } catch (e: Exception) {
            Log.e("AsrBatch", "transcribe failed", e)
            statusMessage = "转写失败: ${e.message}"
            onStatus(statusMessage)
            null
        } finally {
            isRunning = false
        }
    }

    // ===== 主入口（sherpa-onnx 引擎：Qwen3-ASR / SenseVoice QNN）=====
    suspend fun transcribeToSrtSherpa(
        mediaUri: Uri,
        videoTitle: String?,
        engine: AsrEngineType = AsrEngineType.QWEN3,
        language: String = "auto",
        onStatus: (String) -> Unit = {},
        onProgress: (Float) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        isRunning = true
        progress = 0f
        val engineLabel = when (engine) {
            AsrEngineType.QWEN3 -> "Qwen3-ASR"
            AsrEngineType.SENSEVOICE_QNN -> "SenseVoice QNN"
            else -> "Sherpa"
        }
        try {
            statusMessage = "初始化 $engineLabel 引擎..."
            onStatus(statusMessage)
            val recognizer = SherpaAsrManager.createRecognizer(context, engine, language)
            if (recognizer == null) {
                statusMessage = "$engineLabel 模型不可用，请先下载"
                onStatus(statusMessage)
                return@withContext null
            }

            statusMessage = "正在提取音轨并识别（$engineLabel，语言=$language）..."
            onStatus(statusMessage)
            Log.i("AsrBatch", "transcribe start: uri=$mediaUri engine=$engineLabel lang=$language")
            val cues = extractAndRecognizeSherpa(mediaUri, recognizer, onProgress)
            recognizer.release()
            finishTranscribe(cues, mediaUri, videoTitle, onStatus, onProgress)
        } catch (e: Exception) {
            Log.e("AsrBatch", "transcribe failed", e)
            statusMessage = "转写失败: ${e.message}"
            onStatus(statusMessage)
            null
        } finally {
            isRunning = false
        }
    }

    // ===== 收尾公共逻辑 =====
    private suspend fun finishTranscribe(
        cues: List<SubtitleCue>,
        mediaUri: Uri,
        videoTitle: String?,
        onStatus: (String) -> Unit,
        onProgress: (Float) -> Unit
    ): File? {
        if (cues.isEmpty()) {
            statusMessage = "未识别到语音内容（或音轨解码失败）"
            onStatus(statusMessage)
            return null
        }
        val srtFile = writeSrt(mediaUri, videoTitle, cues)
        statusMessage = "字幕生成完成：${cues.size} 句"
        onStatus(statusMessage)
        onProgress(1f)
        return srtFile
    }

    // ===== Vosk 识别 =====
    private fun extractAndRecognizeVosk(
        mediaUri: Uri,
        modelDir: File,
        onProgress: (Float) -> Unit
    ): List<SubtitleCue> {
        val model = org.vosk.Model(modelDir.absolutePath)
        val recognizer = org.vosk.Recognizer(model, 16000f)
        return try {
            extractAndRecognizeGeneric(mediaUri, onProgress) { samples ->
                val pcm = floatToPcm16(samples)
                val accepted = recognizer.acceptWaveForm(pcm, pcm.size)
                val text = if (accepted) parseText(recognizer.result) else parseText(recognizer.partialResult)
                AsrSegmentResult(
                    text = text,
                    isFinal = accepted,
                    needsReset = !accepted && shouldResetVosk(text, samples.size, 16000)
                )
            }.let { cues ->
                // Vosk finalResult 收尾
                val finalText = parseText(recognizer.finalResult)
                if (finalText.isNotBlank() && cues.isEmpty()) {
                    cues + SubtitleCue(cues.size + 1, 0L, 500L, finalText)
                } else if (finalText.isNotBlank()) {
                    val last = cues.last()
                    cues + SubtitleCue(cues.size + 1, last.endTimeMs, last.endTimeMs + 500, finalText)
                } else cues
            }
        } finally {
            try { recognizer.close() } catch (_: Exception) {}
            try { model.close() } catch (_: Exception) {}
        }
    }

    // Vosk 断句逻辑：超时 6 秒或句末标点
    private var sentenceElapsedMs = 0L
    private fun shouldResetVosk(text: String, samplesSize: Int, sampleRate: Int): Boolean {
        sentenceElapsedMs += (samplesSize * 1000L / sampleRate)
        val endsWithPunct = text.length >= 2 &&
            (text.last() == '。' || text.last() == '！' || text.last() == '？' ||
             text.last() == '.' || text.last() == '!' || text.last() == '?')
        return sentenceElapsedMs > 6000L || endsWithPunct
    }

    // ===== Qwen3-ASR / sherpa-onnx 识别 =====
    private fun extractAndRecognizeSherpa(
        mediaUri: Uri,
        recognizer: com.k2fsa.sherpa.onnx.OfflineRecognizer,
        onProgress: (Float) -> Unit
    ): List<SubtitleCue> {
        return extractAndRecognizeGeneric(mediaUri, onProgress) { samples ->
            val stream = recognizer.createStream()
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text.trim()
            stream.release()
            // Qwen3 是离线模型（整段识别），每次送入的 chunk 都可视为最终结果
            AsrSegmentResult(text = text, isFinal = true, needsReset = false)
        }
    }

    // ===== 通用音频提取 + 分块喂入框架 =====
    private data class AsrSegmentResult(
        val text: String,
        val isFinal: Boolean,
        val needsReset: Boolean   // 用于 Vosk 的识别器重置
    )

    /**
     * 通用音频提取框架：解码视频音频为 PCM，按 100ms 分块，VAD 过滤后喂入 recognizer 回调。
     * @param recognizeBlock 接收 [0,1] 归一化 float 采样，返回识别结果
     */
    private fun extractAndRecognizeGeneric(
        mediaUri: Uri,
        onProgress: (Float) -> Unit,
        recognizeBlock: (FloatArray) -> AsrSegmentResult
    ): List<SubtitleCue> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, mediaUri, null)
            var audioTrack = -1
            var mime = ""
            var durationUs = 0L
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    audioTrack = i; mime = m
                    durationUs = if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
                    break
                }
            }
            if (audioTrack < 0) return emptyList()
            extractor.selectTrack(audioTrack)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(extractor.getTrackFormat(audioTrack), null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            val cues = mutableListOf<SubtitleCue>()
            var monoBuf = FloatArray(0)
            var chunkStartUs = 0L; var sentenceStartUs = 0L; var lastEndUs = 0L
            var outputRate = 16000; var outputChannels = 1
            var silentRunMs = 0L; var fedSilenceReset = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx) ?: continue
                        val sz = extractor.readSampleData(inBuf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0); extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx) ?: continue
                    val ptsUs = bufferInfo.presentationTimeUs
                    val fmt = codec.outputFormat
                    if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) outputRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) outputChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val bytes = ByteArray(bufferInfo.size)
                    outBuf.get(bytes)
                    codec.releaseOutputBuffer(outIdx, false)

                    val frames = bytes.size / (2 * outputChannels.coerceAtLeast(1))
                    val mono = FloatArray(frames)
                    for (f in 0 until frames) {
                        var sum = 0
                        for (ch in 0 until outputChannels.coerceAtLeast(1)) {
                            val off = (f * outputChannels + ch) * 2
                            sum += ((bytes[off].toInt() and 0xff) or (bytes[off + 1].toInt() shl 8)).toShort().toInt()
                        }
                        mono[f] = sum / outputChannels.coerceAtLeast(1).toFloat() / 32768f
                    }
                    val combined = FloatArray(monoBuf.size + mono.size)
                    System.arraycopy(monoBuf, 0, combined, 0, monoBuf.size)
                    System.arraycopy(mono, 0, combined, monoBuf.size, mono.size)
                    monoBuf = combined

                    val chunkLen = (outputRate / 10).coerceAtLeast(160)
                    while (monoBuf.size >= chunkLen) {
                        val chunk = monoBuf.copyOfRange(0, chunkLen)
                        monoBuf = monoBuf.copyOfRange(chunkLen, monoBuf.size)
                        if (chunkStartUs == 0L) chunkStartUs = ptsUs.coerceAtLeast(0L)
                        if (sentenceStartUs == 0L) sentenceStartUs = chunkStartUs

                        var rms = 0f; for (s in chunk) rms += s * s; rms = kotlin.math.sqrt(rms / chunk.size)
                        val isSilent = rms < VAD_THRESHOLD
                        val shouldFeed = if (isSilent) {
                            silentRunMs += 100L
                            if (silentRunMs >= VAD_RESET_MS && !fedSilenceReset) { fedSilenceReset = true; true } else false
                        } else { silentRunMs = 0L; fedSilenceReset = false; true }

                        if (shouldFeed) {
                            val resampled = resampleLinear(chunk, outputRate, 16000)
                            val result = recognizeBlock(resampled)
                            if (result.text.isNotBlank()) {
                                if (result.isFinal) {
                                    val endUs = chunkStartUs + 100_000L
                                    cues.add(SubtitleCue(cues.size + 1,
                                        (sentenceStartUs / 1000).coerceAtLeast(0L),
                                        (endUs / 1000).coerceAtLeast(sentenceStartUs / 1000 + 500),
                                        result.text))
                                    lastEndUs = endUs; sentenceStartUs = 0L
                                } else if (sentenceStartUs > 0 && result.needsReset) {
                                    // Vosk 超时/标点断句
                                    val endUs = chunkStartUs + 100_000L
                                    cues.add(SubtitleCue(cues.size + 1,
                                        (sentenceStartUs / 1000).coerceAtLeast(0L),
                                        (endUs / 1000).coerceAtLeast(sentenceStartUs / 1000 + 500),
                                        result.text))
                                    lastEndUs = endUs; sentenceStartUs = 0L
                                    sentenceElapsedMs = 0L  // 重置 Vosk 计时
                                }
                            }
                        }
                        chunkStartUs += 100_000L
                    }
                    if (durationUs > 0) {
                        val p = (ptsUs.toFloat() / durationUs).coerceIn(0f, 1f)
                        progress = p; onProgress(p)
                        if (cues.size % 100 == 0 && cues.size > 0) Log.i("AsrBatch", "progress=${(p * 100).toInt()}% cues=${cues.size}")
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            progress = 1f; onProgress(1f)
            Log.i("AsrBatch", "decode done: ${cues.size} cues")
            return cues
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    // ===== SRT 写入 =====
    private fun writeSrt(mediaUri: Uri, videoTitle: String?, cues: List<SubtitleCue>): File? {
        val baseName = videoTitle?.substringBeforeLast('.')
            ?: mediaUri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "video"
        val sb = StringBuilder()
        cues.forEachIndexed { i, cue ->
            sb.append(i + 1).append('\n')
            sb.append(formatTime(cue.startTimeMs)).append(" --> ").append(formatTime(cue.endTimeMs)).append('\n')
            sb.append(wrapText(cue.text, 14)).append("\n\n")
        }
        val file = File(context.getExternalFilesDir(null), "${baseName}_asr.srt")
        file.parentFile?.mkdirs()
        file.writeText(sb.toString(), Charsets.UTF_8)
        return file
    }

    private fun formatTime(ms: Long): String {
        val s = if (ms < 0) 0L else ms
        return "%02d:%02d:%02d,%03d".format(s / 3600000, (s % 3600000) / 60000, (s % 60000) / 1000, s % 1000)
    }

    private fun parseText(raw: String): String = try { JSONObject(raw).optString("text", "").trim() } catch (_: Exception) { "" }

    private fun floatToPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2); var i = 0
        for (s in samples) { val v = (s.coerceIn(-1f, 1f) * 32767).toInt(); out[i++] = (v and 0xff).toByte(); out[i++] = ((v shr 8) and 0xff).toByte() }
        return out
    }

    private fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate) return input
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val outSize = (input.size / ratio).toInt().coerceAtLeast(0)
        val out = FloatArray(outSize)
        for (i in 0 until outSize) { val pos = i * ratio; val i0 = pos.toInt().coerceAtMost(input.size - 1); val frac = (pos - i0).toFloat(); out[i] = input[i0] + (input[(i0 + 1).coerceAtMost(input.size - 1)] - input[i0]) * frac }
        return out
    }
}
