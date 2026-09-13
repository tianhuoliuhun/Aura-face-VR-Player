package com.example.vr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer

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
        private const val SAMPLE_RATE = 16000

        // v122：离线引擎（Qwen3-ASR / SenseVoice）的语音段切分参数。
        // 这两个模型都是**整段离线推理**（非流式），若沿用流式引擎的 100ms 喂入方式，
        // 10 分钟视频会触发约 6000 次 0.6B 前向推理，慢到不可用，且相邻块独立识别
        // 会产生大量重复、割裂的短字幕。改为「按语音段整段送入」：
        // - 静音持续超过 SPLIT_SILENCE_MS 视为一句话结束
        // - 单段最长 MAX_SEGMENT_SEC，防止长音频导致显存/耗时爆炸
        private const val SPLIT_SILENCE_MS = 700L
        private const val MAX_SEGMENT_SEC = 25
        private const val MIN_SEGMENT_MS = 300L   // 短于此的残段直接丢弃（多为爆音/噪声）

        // 长句切分阈值：单条字幕超过 40 字或 8 秒就按标点拆开
        private const val MAX_CUE_CHARS = 40
        private const val MAX_CUE_MS = 8000L
        private const val MIN_SPLIT_CHARS = 12
        private const val MIN_PIECE_MS = 900L

        // v123（Vosk）：喂入单元 400ms。Vosk 虽是流式引擎，但每 100ms 一次 JNI 调用
        // + 一次 JSON 解析的开销很明显；400ms 仍在可接受的流式延迟内，调用量降为 1/4。
        private const val VOSK_FEED_MS = 400

        // v123：Vosk 模型加载很贵（小模型数秒、大模型数十秒），
        // 同一路径的 Model 复用；切换模型时自动释放旧的。
        @Volatile private var cachedModel: org.vosk.Model? = null
        @Volatile private var cachedModelPath: String? = null

        @Synchronized
        private fun obtainVoskModel(path: String): org.vosk.Model {
            val c = cachedModel
            if (c != null && cachedModelPath == path) {
                Log.i("AsrBatch", "Vosk 模型复用缓存: $path")
                return c
            }
            try { cachedModel?.close() } catch (_: Exception) {}
            val m = org.vosk.Model(path)
            cachedModel = m
            cachedModelPath = path
            return m
        }

        /** 释放缓存的 Vosk 模型 native 内存（退出界面或切换模型时调用） */
        @Synchronized
        fun releaseVoskModel() {
            try { cachedModel?.close() } catch (_: Exception) {}
            cachedModel = null
            cachedModelPath = null
            Log.i("AsrBatch", "Vosk 模型缓存已释放")
        }
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
                // v123：旧提示一律是"请检查网络后重试"，但模型下载失败、解压失败、
                // 本地缓存损坏都会走到这里，用户无从判断。带上模型名与本地缓存状态，
                // 便于区分"没网/下载失败"与"本地文件坏了"。
                val cacheDir = File(context.filesDir, "vosk_models/${modelOption.modelName}")
                val hint = if (cacheDir.exists()) "本地缓存存在但不可用（可尝试重新下载）" else "尚未下载（需联网下载 ${modelOption.sizeMb}MB）"
                statusMessage = "模型不可用：${modelOption.label}——$hint"
                onStatus(statusMessage)
                Log.w("AsrBatch", "model unavailable: ${modelOption.modelName}, $hint")
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
                // v118：区分"没下载"与"初始化失败"——后者此前被误报成"请先下载"，误导排查方向
                val reason = SherpaAsrManager.lastInitError
                statusMessage = if (reason != null) "$engineLabel 不可用：$reason"
                    else "$engineLabel 模型不可用，请先下载"
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
    /**
     * v123 优化：
     * 1) 断句后调用 recognizer.reset() —— 此前从不 reset，Vosk 内部上下文无限累积，
     *    既拖慢识别又让后续文本越来越不准，"断句"其实只切了字幕没切解码状态；
     * 2) 每次转写前归零 sentenceElapsedMs —— 它是实例级状态，此前跨次累积，
     *    导致第二次转写刚开始就被判定"超时"而立刻断句；
     * 3) 直接喂 float[]（Vosk 支持），省掉每块一次的 floatToPcm16 转换；
     * 4) 以 400ms 为一个喂入单元，JNI 调用与 JSON 解析量降为原来的 1/4；
     * 5) Model 按路径缓存复用，连续转写省去重复加载（数十秒）。
     */
    private fun extractAndRecognizeVosk(
        mediaUri: Uri,
        modelDir: File,
        onProgress: (Float) -> Unit
    ): List<SubtitleCue> {
        sentenceElapsedMs = 0L
        val model = obtainVoskModel(modelDir.absolutePath)
        val recognizer = org.vosk.Recognizer(model, 16000f)
        val feedSamples = SAMPLE_RATE * VOSK_FEED_MS / 1000   // 6400
        var acc = FloatArray(0)
        var feedCount = 0
        val t0 = System.currentTimeMillis()
        return try {
            extractAndRecognizeGeneric(mediaUri, onProgress, feedAll = false) { samples, _ ->
                acc = appendFloat(acc, samples)
                if (acc.size < feedSamples) {
                    // 攒够 400ms 再喂
                    AsrSegmentResult(text = "", isFinal = false, needsReset = false)
                } else {
                    val buf = acc
                    acc = FloatArray(0)
                    feedCount++
                    val accepted = recognizer.acceptWaveForm(buf, buf.size)
                    val text = if (accepted) parseText(recognizer.result) else parseText(recognizer.partialResult)
                    val needsReset = !accepted && shouldResetVosk(text, buf.size, SAMPLE_RATE)
                    when {
                        accepted -> sentenceElapsedMs = 0L
                        needsReset -> {
                            // 主动断句：重置解码状态，避免上下文无限累积
                            recognizer.reset()
                            sentenceElapsedMs = 0L
                        }
                    }
                    AsrSegmentResult(text = text, isFinal = accepted, needsReset = needsReset)
                }
            }.let { cues ->
                // 收尾：不足一个喂入单元的残余采样也要送进去，否则尾部语音会丢
                if (acc.isNotEmpty()) {
                    recognizer.acceptWaveForm(acc, acc.size)
                    acc = FloatArray(0)
                }
                val finalText = parseText(recognizer.finalResult)
                if (finalText.isNotBlank() && cues.isEmpty()) {
                    cues + SubtitleCue(cues.size + 1, 0L, 500L, finalText)
                } else if (finalText.isNotBlank()) {
                    val last = cues.last()
                    cues + SubtitleCue(cues.size + 1, last.endTimeMs, last.endTimeMs + 500, finalText)
                } else cues
            }
        } finally {
            Log.i(
                "AsrBatch",
                "Vosk done: 喂入 ${feedCount + 1} 次（${VOSK_FEED_MS}ms/次，优化前约 ${(feedCount + 1) * (VOSK_FEED_MS / 100)} 次）, " +
                    "耗时 ${System.currentTimeMillis() - t0}ms"
            )
            try { recognizer.close() } catch (_: Exception) {}
            // Model 由缓存持有，不在此关闭（换模型或退出界面时才释放）
        }
    }

    private fun appendFloat(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(a.size + b.size)
        System.arraycopy(a, 0, out, 0, a.size)
        System.arraycopy(b, 0, out, a.size, b.size)
        return out
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
    /**
     * 把过长的识别结果按标点切成多条字幕，时间按字数比例分配。
     *
     * 离线模型一次识别的是整段语音（可达 25 秒），输出常是一整段话；
     * 直接生成一条字幕会长时间铺满屏幕。这里按句末/句中标点切分，
     * 每条至少 [MIN_PIECE_MS]，避免过短的碎片。
     */
    private data class TextPiece(val text: String, val startMs: Long, val endMs: Long)

    private fun splitLongSentence(text: String, startMs: Long, endMs: Long): List<TextPiece> {
        val total = endMs - startMs
        if (text.length <= MAX_CUE_CHARS && total <= MAX_CUE_MS) {
            return listOf(TextPiece(text, startMs, endMs))
        }
        // 按标点切句（保留标点）
        val parts = mutableListOf<String>()
        var buf = StringBuilder()
        for (ch in text) {
            buf.append(ch)
            if (ch in "。！？；!?;" || (ch in "，,、" && buf.length >= MIN_SPLIT_CHARS)) {
                parts.add(buf.toString()); buf = StringBuilder()
            }
        }
        if (buf.isNotBlank()) parts.add(buf.toString())
        var usable = parts.filter { it.isNotBlank() }
        if (usable.isEmpty()) return listOf(TextPiece(text, startMs, endMs))

        // v122 兜底：整段没有标点（模型常输出不带标点的长句）时，按字数强制等分，
        // 否则一条 30 秒、几十字的字幕会一直糊在屏幕上。
        if (usable.size == 1 && usable[0].length > MAX_CUE_CHARS) {
            val whole = usable[0]
            val chunks = mutableListOf<String>()
            var i = 0
            while (i < whole.length) {
                chunks.add(whole.substring(i, minOf(i + MAX_CUE_CHARS, whole.length)))
                i += MAX_CUE_CHARS
            }
            usable = chunks
        }

        val totalChars = usable.sumOf { it.length }.coerceAtLeast(1)
        val out = mutableListOf<TextPiece>()
        var cursor = startMs
        usable.forEachIndexed { i, part ->
            val share = if (i == usable.lastIndex) endMs - cursor
            else (total * part.length / totalChars.toFloat()).toLong()
            val segEnd = if (i == usable.lastIndex) endMs
            else (cursor + share).coerceAtLeast(cursor + MIN_PIECE_MS).coerceAtMost(endMs)
            out.add(TextPiece(part.trim(), cursor, segEnd))
            cursor = segEnd
        }
        return out
    }

    /**
     * v122 优化：离线引擎改为**按语音段整段识别**。
     *
     * 原实现对每个 100ms 音频块都 `createStream → acceptWaveform → decode`，
     * 即每 100ms 跑一次完整的 0.6B 模型前向推理：
     * - 性能：10 分钟视频约 6000 次推理，实测慢到不可用；
     * - 质量：每块独立识别、互不感知上下文，相邻块重复输出同一句话，字幕碎片化。
     *
     * 现在用能量 VAD 把连续语音累积成段，遇到静音 >700ms 或段长达到 25s 才送一次识别，
     * 推理次数通常下降一到两个数量级，且每段都有完整上下文。
     */
    private fun extractAndRecognizeSherpa(
        mediaUri: Uri,
        recognizer: com.k2fsa.sherpa.onnx.OfflineRecognizer,
        onProgress: (Float) -> Unit
    ): List<SubtitleCue> {
        val pending = ArrayList<Float>(SAMPLE_RATE * MAX_SEGMENT_SEC)
        val out = ArrayList<SubtitleCue>()
        var segStartMs = 0L
        var segEndMs = 0L
        var silenceRunMs = 0L
        var segmentCount = 0

        /** 把已累积的采样整段送入识别器，产出一个字幕条目 */
        fun flush(endMs: Long) {
            if (pending.isEmpty()) return
            val samples = pending.toFloatArray()
            pending.clear()
            val segMs = samples.size * 1000L / SAMPLE_RATE
            if (segMs < MIN_SEGMENT_MS) return

            segmentCount++
            statusMessage = "正在识别第 $segmentCount 段语音…"
            val t0 = System.currentTimeMillis()
            val text = try {
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    recognizer.decode(stream)
                    recognizer.getResult(stream).text.trim()
                } finally {
                    stream.release()
                }
            } catch (e: Exception) {
                Log.w("AsrBatch", "sherpa segment #$segmentCount failed: ${e.message}")
                ""
            }
            val cost = System.currentTimeMillis() - t0
            if (text.isNotBlank()) {
                val start = segStartMs.coerceAtLeast(0L)
                val end = endMs.coerceAtLeast(start + 500)
                // v122：整段识别常返回一长句，直接做一条字幕会糊满屏幕。
                // 按标点切成多条，并按**字数比例**分配时间轴（近似但远好于整句一屏）。
                for (piece in splitLongSentence(text, start, end)) {
                    out.add(SubtitleCue(out.size + 1, piece.startMs, piece.endMs, piece.text))
                }
                Log.i("AsrBatch", "sherpa segment #$segmentCount: ${segMs}ms -> ${text.length}字/${out.size}条, 耗时 ${cost}ms")
            } else {
                Log.i("AsrBatch", "sherpa segment #$segmentCount: ${segMs}ms -> 空, 耗时 ${cost}ms")
            }
        }

        // feedAll=true：离线引擎需要看到**每一个**块（含静音）才能自行判断语音段边界，
        // 不能让通用框架把静音块过滤掉（那是为 Vosk 这类流式引擎设计的）。
        extractAndRecognizeGeneric(mediaUri, onProgress, feedAll = true) { samples, chunkStartMs ->
            var rms = 0f
            for (s in samples) rms += s * s
            rms = kotlin.math.sqrt(rms / samples.size)
            val chunkMs = (samples.size * 1000L / SAMPLE_RATE).coerceAtLeast(1L)

            if (rms >= VAD_THRESHOLD) {
                if (pending.isEmpty()) segStartMs = chunkStartMs
                for (s in samples) pending.add(s)
                silenceRunMs = 0L
                segEndMs = chunkStartMs + chunkMs
                // 超长保护：整段过长时强制切分，避免单次推理耗时/内存不可控
                if (pending.size >= SAMPLE_RATE * MAX_SEGMENT_SEC) flush(segEndMs)
            } else if (pending.isNotEmpty()) {
                silenceRunMs += chunkMs
                if (silenceRunMs >= SPLIT_SILENCE_MS) {
                    flush(chunkStartMs)
                    silenceRunMs = 0L
                } else {
                    // 短静音（<700ms）保留在段内，避免把一句话从词中间切断
                    for (s in samples) pending.add(s)
                    segEndMs = chunkStartMs + chunkMs
                }
            }
            // 字幕由 flush() 内部产出，这里不返回文本
            AsrSegmentResult(text = "", isFinal = false, needsReset = false)
        }

        flush(segEndMs)  // 收尾：最后一段
        Log.i("AsrBatch", "sherpa done: $segmentCount 段 -> ${out.size} 条字幕")
        return out
    }

    // ===== 通用音频提取 + 分块喂入框架 =====
    private data class AsrSegmentResult(
        val text: String,
        val isFinal: Boolean,
        val needsReset: Boolean   // 用于 Vosk 的识别器重置
    )

    /**
     * 通用音频提取 + 分块喂入框架。
     * 先尝试 MediaExtractor + MediaCodec（最快），失败则回退到软件解码（支持 MPEG Layer II 等）。
     *
     * @param feedAll true = 每一个 100ms 块都会回调（含静音），供离线引擎自行切分语音段；
     *                false = 按 Vosk 习惯过滤掉连续静音块（流式引擎用）
     * @param recognizeBlock 接收 [0,1] 归一化 16kHz 单声道 PCM float 采样与该块起始毫秒，返回识别结果
     */
    private fun extractAndRecognizeGeneric(
        mediaUri: Uri,
        onProgress: (Float) -> Unit,
        feedAll: Boolean = false,
        recognizeBlock: (FloatArray, Long) -> AsrSegmentResult
    ): List<SubtitleCue> {
        // 尝试 MediaExtractor + MediaCodec（快速路径）
        return try {
            extractWithMediaCodec(mediaUri, onProgress, feedAll, recognizeBlock)
        } catch (e: Exception) {
            // 兜底：纯 Java 软件解码（JLayer）。
            // 典型场景是 MPEG-1 Audio Layer II（Android 里 MIME 为 audio/mpeg-L2）：
            // 它在 Android 上属"可选支持"格式，不少机型（实测骁龙8 Elite / SM8850）
            // 虽声明了对应解码器，却会在 configure 阶段失败，报
            // "Failed to initialize audio/mpeg-L2, error 0x..."。
            // 这种情况 ExoPlayer 也救不了（它内部同样走 MediaCodec），只有自带解码器才行。
            Log.w("AsrBatch", "MediaCodec 解码失败（${e.message}），回退到软件解码器...")
            statusMessage = "系统解码器不可用，正在用软件解码器解码（速度较慢）..."
            extractWithSoftwareMpegDecoder(mediaUri, onProgress, feedAll, recognizeBlock)
        }
    }

    /**
     * 快速路径：直接用 MediaExtractor + MediaCodec 解码。
     * 正常格式（AAC/MP3/OGG/WAV 等）都能走这条路。
     */
    private fun extractWithMediaCodec(
        mediaUri: Uri,
        onProgress: (Float) -> Unit,
        feedAll: Boolean = false,
        recognizeBlock: (FloatArray, Long) -> AsrSegmentResult
    ): List<SubtitleCue> {
        val (extractor, src) = prepareExtractor(mediaUri)
        var codec: MediaCodec? = null
        try {

            // 选择音频轨
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
            if (audioTrack < 0) {
                Log.e("AsrBatch", "no audio track found")
                return emptyList()
            }
            extractor.selectTrack(audioTrack)
            Log.i("AsrBatch", "audio track: mime=$mime durationUs=$durationUs")

            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                Log.w("AsrBatch", "Hardware decoder failed for $mime (${e.message}), searching software decoder...")
                val swName = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                    .codecInfos
                    .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
                    .sortedBy { info ->
                        when {
                            info.name.startsWith("c2.android.") -> 0
                            info.name.startsWith("OMX.google.") -> 1
                            else -> 2
                        }
                    }
                    .firstOrNull()?.name
                if (swName != null) {
                    Log.i("AsrBatch", "Using software decoder: $swName for $mime")
                    android.media.MediaCodec.createByCodecName(swName)
                } else {
                    throw Exception("No decoder found for $mime on this device")
                }
            }
            codec.configure(extractor.getTrackFormat(audioTrack), null, null, 0)
            codec.start()
            Log.i("AsrBatch", "codec started: $mime (name=${codec.name})")

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
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
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 500)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val fmt = codec.outputFormat
                    if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) outputRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) outputChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    continue
                }
                if (outIdx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) { continue }
                if (outIdx < 0) { continue }

                val outBuf = codec.getOutputBuffer(outIdx) ?: continue
                val ptsUs = bufferInfo.presentationTimeUs
                val bytes = ByteArray(bufferInfo.size)
                outBuf.get(bytes)
                codec.releaseOutputBuffer(outIdx, true)

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
                    // v122：feedAll=true 时不过滤静音块（离线引擎需要自己看静音来断句）
                    val shouldFeed = if (feedAll) true
                        else if (isSilent) { silentRunMs += 100L; if (silentRunMs >= VAD_RESET_MS && !fedSilenceReset) { fedSilenceReset = true; true } else false }
                        else { silentRunMs = 0L; fedSilenceReset = false; true }
                    if (shouldFeed) {
                        val resampled = resampleLinear(chunk, outputRate, 16000)
                        val result = recognizeBlock(resampled, chunkStartUs / 1000L)
                        if (result.text.isNotBlank()) {
                            if (result.isFinal) {
                                val endUs = chunkStartUs + 100_000L
                                cues.add(SubtitleCue(cues.size + 1, (sentenceStartUs / 1000).coerceAtLeast(0L), (endUs / 1000).coerceAtLeast(sentenceStartUs / 1000 + 500), result.text))
                                lastEndUs = endUs; sentenceStartUs = 0L
                            } else if (sentenceStartUs > 0 && result.needsReset) {
                                val endUs = chunkStartUs + 100_000L
                                cues.add(SubtitleCue(cues.size + 1, (sentenceStartUs / 1000).coerceAtLeast(0L), (endUs / 1000).coerceAtLeast(sentenceStartUs / 1000 + 500), result.text))
                                lastEndUs = endUs; sentenceStartUs = 0L; sentenceElapsedMs = 0L
                            }
                        }
                    }
                    chunkStartUs += 100_000L
                }
                if (durationUs > 0) { val p = (ptsUs.toFloat() / durationUs).coerceIn(0f, 1f); progress = p; onProgress(p) }
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
            val endUs = chunkStartUs + 100_000L
            if (monoBuf.isNotEmpty()) {
                val resampled = resampleLinear(monoBuf, outputRate, 16000)
                val result = recognizeBlock(resampled, chunkStartUs / 1000L)
                if (result.text.isNotBlank()) cues.add(SubtitleCue(cues.size + 1, sentenceStartUs.coerceAtLeast(0L) / 1000, endUs.coerceAtLeast(sentenceStartUs / 1000 + 500), result.text))
            }
            progress = 1f; onProgress(1f)
            Log.i("AsrBatch", "MediaCodec done: ${cues.size} cues")
            return cues
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            src.close()
        }
    }

    /** 打开的媒体源句柄，用完统一释放（fd 关闭 / 临时文件删除） */
    private class DataSourceHolder {
        var pfd: android.os.ParcelFileDescriptor? = null
        var tmpFile: File? = null
        fun close() {
            try { pfd?.close() } catch (_: Exception) {}
            try { tmpFile?.delete() } catch (_: Exception) {}
        }
    }

    /**
     * v123：为 MediaExtractor 准备数据源，三级回退。
     *
     * 背景：`setDataSource(context, uri, null)` 对系统相册（photo picker）返回的
     * URI 会抛 "Failed to instantiate extractor"；改用 fd 后**部分机型依然失败**
     * （picker 给的 fd 不一定可 seek，而 MediaExtractor 必须能随机访问）。
     * 因此最后兜底复制到临时文件再打开——多一次磁盘 IO，但保证能转写。
     */
    private fun prepareExtractor(uri: Uri): Pair<MediaExtractor, DataSourceHolder> {
        val h = DataSourceHolder()
        // 每次尝试都新建 MediaExtractor —— 实测失败过的实例会永久处于坏状态，
        // 复用它会导致后续（即使是可用数据源）一律再失败。
        // 1) 文件描述符
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                try {
                    val ex = MediaExtractor()
                    ex.setDataSource(pfd.fileDescriptor)
                    h.pfd = pfd
                    return ex to h
                } catch (e: Exception) {
                    Log.w("AsrBatch", "fd 方式失败：${e.message}，尝试带 offset 打开")
                }
                // 2) 带 offset/length
                try {
                    val afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
                    if (afd != null) {
                        val ex = MediaExtractor()
                        ex.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                        afd.close()
                        h.pfd = pfd
                        return ex to h
                    }
                } catch (e2: Exception) {
                    Log.w("AsrBatch", "afd 方式失败：${e2.message}")
                }
                pfd.close()
            }
        } catch (e: Exception) {
            Log.w("AsrBatch", "打开 fd 失败：${e.message}")
        }

        // 3) 兜底：把媒体内容写到临时文件（本地路径一定可 seek）。
        // 注意别用 contentResolver.openInputStream(uri)——实测对 photo picker 的 URI
        // 它只返回一张缩略图（本次源 1062353 字节，复制出来只有 22600 字节），
        // 必须直接读文件描述符的字节流。
        Log.i("AsrBatch", "回退：复制媒体到临时文件后再解码")
        val tmp = File(context.cacheDir, "asr_src_${System.currentTimeMillis()}.tmp")
        val pfd2 = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw Exception("无法读取媒体源：$uri")
        try {
            // 体积保护：不要把超大文件搬到内部存储（可能撑爆空间），
            // 超过 1.5GB 直接放弃并给出可理解的提示
            val size = pfd2.statSize
            if (size > 1_500_000_000L) {
                throw Exception("视频过大（${size / 1_048_576}MB），无法复制到内部存储处理")
            }
            java.io.FileInputStream(pfd2.fileDescriptor).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
        } finally {
            pfd2.close()
        }
        Log.i("AsrBatch", "临时文件已就绪: ${tmp.absolutePath}, size=${tmp.length()} bytes")
        val ex = MediaExtractor()
        ex.setDataSource(tmp.absolutePath)
        h.tmpFile = tmp
        return ex to h
    }

    /**
     * 兜底路径：纯 Java 软件解码（JLayer，支持 MPEG-1/2/2.5 的 Layer I/II/III）。
     *
     * 为什么需要它：MediaCodec 只能解"设备自带解码器"的格式。MPEG-1 Audio Layer II
     * （Android 里的 MIME 是 audio/mpeg-L2）在 Android 上属于可选支持格式，很多机型
     * 虽声明了对应解码器却在 configure 阶段失败（Failed to initialize audio/mpeg-L2）。
     * 这类问题 ExoPlayer 同样无解——它内部还是走 MediaCodec，只有自带解码器才行。
     *
     * 实现：MediaExtractor 只负责"拆容器"取出原始 MPEG 音频帧流，真正的解码交给 JLayer。
     */
    private fun extractWithSoftwareMpegDecoder(
        mediaUri: Uri,
        onProgress: (Float) -> Unit,
        feedAll: Boolean = false,
        recognizeBlock: (FloatArray, Long) -> AsrSegmentResult
    ): List<SubtitleCue> {
        val (extractor, src) = prepareExtractor(mediaUri)
        try {
            // 选择音频轨（顺带记下时长，用于估算进度）
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
            if (audioTrack < 0) {
                Log.e("AsrBatch", "software decode: no audio track")
                return emptyList()
            }
            extractor.selectTrack(audioTrack)
            Log.i("AsrBatch", "software decode start: mime=$mime durationUs=$durationUs")

            val bitstream = Bitstream(ExtractorInputStream(extractor))
            val decoder = Decoder()

            val cues = mutableListOf<SubtitleCue>()
            var monoBuf = FloatArray(0)
            var chunkStartMs = 0L; var sentenceStartMs = 0L
            var silentRunMs = 0L; var fedSilenceReset = false
            var sampleRate = 16000
            var frameCount = 0
            var decodedSamplesPerChannel = 0L

            while (true) {
                // 逐帧读取 MPEG 音频帧。流结束或数据损坏都会让 readFrame 返回 null / 抛异常，
                // 两种都按"解码结束"处理（已解出的部分仍然保留）。
                val header = try {
                    bitstream.readFrame()
                } catch (e: javazoom.jl.decoder.BitstreamException) {
                    Log.w("AsrBatch", "software decode: bitstream ended (${e.message})")
                    null
                } ?: break

                val output = try {
                    decoder.decodeFrame(header, bitstream) as? SampleBuffer
                } catch (e: Exception) {
                    Log.w("AsrBatch", "software decode: frame decode failed (${e.message})")
                    null
                } finally {
                    try { bitstream.closeFrame() } catch (_: Exception) {}
                }
                if (output == null) continue

                sampleRate = output.sampleFrequency
                val channels = output.channelCount.coerceAtLeast(1)
                val pcm = output.buffer
                val frames = output.bufferLength / channels
                decodedSamplesPerChannel += frames

                // 降混单声道并归一化到 [-1,1]
                val mono = FloatArray(frames)
                for (f in 0 until frames) {
                    var sum = 0
                    for (c in 0 until channels) {
                        val idx = f * channels + c
                        if (idx < pcm.size) sum += pcm[idx].toInt()
                    }
                    mono[f] = sum / channels.toFloat() / 32768f
                }

                val combined = FloatArray(monoBuf.size + mono.size)
                System.arraycopy(monoBuf, 0, combined, 0, monoBuf.size)
                System.arraycopy(mono, 0, combined, monoBuf.size, mono.size)
                monoBuf = combined

                // 与 MediaCodec 路径保持一致：按 100ms 分块 + VAD 过滤后喂入识别器
                val chunkLen = (sampleRate / 10).coerceAtLeast(160)
                while (monoBuf.size >= chunkLen) {
                    val chunk = monoBuf.copyOfRange(0, chunkLen)
                    monoBuf = monoBuf.copyOfRange(chunkLen, monoBuf.size)
                    if (sentenceStartMs == 0L) sentenceStartMs = chunkStartMs

                    var rms = 0f; for (s in chunk) rms += s * s; rms = kotlin.math.sqrt(rms / chunk.size)
                    val isSilent = rms < VAD_THRESHOLD
                    val shouldFeed = if (feedAll) true
                        else if (isSilent) { silentRunMs += 100L; if (silentRunMs >= VAD_RESET_MS && !fedSilenceReset) { fedSilenceReset = true; true } else false }
                        else { silentRunMs = 0L; fedSilenceReset = false; true }
                    if (shouldFeed) {
                        val resampled = resampleLinear(chunk, sampleRate, 16000)
                        val result = recognizeBlock(resampled, chunkStartMs)
                        if (result.text.isNotBlank()) {
                            if (result.isFinal) {
                                val endMs = chunkStartMs + 100
                                cues.add(SubtitleCue(cues.size + 1, sentenceStartMs.coerceAtLeast(0L), endMs.coerceAtLeast(sentenceStartMs + 500), result.text))
                                sentenceStartMs = 0L
                            } else if (sentenceStartMs > 0 && result.needsReset) {
                                val endMs = chunkStartMs + 100
                                cues.add(SubtitleCue(cues.size + 1, sentenceStartMs.coerceAtLeast(0L), endMs.coerceAtLeast(sentenceStartMs + 500), result.text))
                                sentenceStartMs = 0L; sentenceElapsedMs = 0L
                            }
                        }
                    }
                    chunkStartMs += 100
                }

                // 软件解码比 MediaCodec 慢，进度不必每帧刷新（每 16 帧约 0.4 秒一次）
                frameCount++
                if (durationUs > 0 && frameCount % 16 == 0) {
                    val decodedUs = decodedSamplesPerChannel * 1_000_000L / sampleRate.coerceAtLeast(1)
                    val p = (decodedUs.toFloat() / durationUs).coerceIn(0f, 1f)
                    progress = p; onProgress(p)
                }
            }

            // 收尾：剩余不足一块的采样也送一次识别
            if (monoBuf.isNotEmpty()) {
                val resampled = resampleLinear(monoBuf, sampleRate, 16000)
                val result = recognizeBlock(resampled, chunkStartMs)
                if (result.text.isNotBlank()) {
                    cues.add(SubtitleCue(cues.size + 1, sentenceStartMs.coerceAtLeast(0L), (chunkStartMs + 500).coerceAtLeast(sentenceStartMs + 500), result.text))
                }
            }

            progress = 1f; onProgress(1f)
            Log.i("AsrBatch", "software decode done: frames=$frameCount cues=${cues.size}")
            return cues
        } finally {
            try { extractor.release() } catch (_: Exception) {}
            src.close()
        }
    }

    /**
     * 把 MediaExtractor 的采样数据包装成顺序 InputStream，供 JLayer 逐帧读取。
     * JLayer 只做顺序读取（不需要 seek），因此这里维护一个滑动缓冲：
     * 缓冲耗尽时向 MediaExtractor 要下一个采样。
     */
    private class ExtractorInputStream(private val extractor: MediaExtractor) : InputStream() {
        private val buffer = ByteBuffer.allocate(256 * 1024)
        private var pos = 0
        private var limit = 0
        private var eos = false

        override fun read(): Int {
            if (!fill()) return -1
            return buffer.get(pos++).toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, limit - pos)
            buffer.position(pos)
            buffer.get(b, off, n)
            pos += n
            return n
        }

        /** 缓冲空了就取下一个采样；返回 false 表示流已结束 */
        private fun fill(): Boolean {
            if (pos < limit) return true
            if (eos) return false
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size <= 0) { eos = true; return false }
            extractor.advance()
            pos = 0
            limit = size
            return true
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
