package com.example.vr

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 端侧 ASR 模型管理（sherpa-onnx）。
 *
 * v127：**只保留 SenseVoice-Small（CPU int8）**。此前并存的三条路线已移除：
 *  - Vosk（Kaldi 流式）：按语言各下一份模型、输出无标点，且上下文累积问题较多
 *  - Qwen3-ASR 0.6B：838MB 模型，且作为离线模型需逐块推理，代价高
 *  - SenseVoice QNN：只有个别骁龙 SoC 有官方 context binary，模型与设备强绑定
 *
 * SenseVoice-Small 的依据（《模型来源与下载地址》）：234M 参数、中英日韩粤、
 * RTF 0.026、自带标点，是通用机型上实时字幕最合适的选择。
 *
 * v2.0.127：模型**内置到 assets**（app/src/main/assets/sense-voice/），开箱即用。
 * 识别器用 `OfflineRecognizer(assets, cfg)` 构造，模型与词表传 asset 相对路径，
 * 因此无需把 239MB 复制到 filesDir（省一次复制与一份空间）。
 * 配套：build.gradle.kts 里 `androidResources.noCompress += "onnx"`，
 * 否则读取时需解压到内存（239MB 峰值）。
 *
 * **下载链路保留**，定位改为「兜底 + 更新」：
 *  - 内置 assets 缺失或损坏时，自动回退到 filesDir 下的下载版模型；
 *  - 需要替换/升级模型时，仍可通过 [startModelDownload] 下载到 filesDir，
 *    且下载版优先于内置版生效（便于不发版换模型）。
 */
object SherpaAsrManager {

    private const val TAG = "SherpaAsr"

    // ===== SenseVoice 配置（v2.0.127：内置 assets + 可选下载兜底）=====
    //
    // 模型：sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17 的 model.int8.onnx
    // （239MB）+ tokens.txt。234M 参数，中英日韩粤，RTF 0.026，自带标点。
    private const val SVC_ASSET_DIR = "sense-voice"
    private const val SVC_ASSET_MODEL = "$SVC_ASSET_DIR/model.int8.onnx"
    private const val SVC_ASSET_TOKENS = "$SVC_ASSET_DIR/tokens.txt"

    // 下载版（filesDir）：内置缺失时兜底，或用于不发版替换模型
    private const val SVC_DIR_NAME = "sense-voice-cpu"
    private const val SVC_MODEL = "model.int8.onnx"
    private const val SVC_TOKENS = "tokens.txt"
    private const val SVC_MODEL_MB = 229
    private const val SVC_MODEL_URL =
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx"
    private const val SVC_TOKENS_URL =
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt"

    /** v127e：推理线程数可选范围与推荐值（推荐 4–6，兼顾速度与播放流畅度） */
    const val MIN_THREADS = 1
    const val MAX_THREADS = 10
    const val DEFAULT_THREADS = 4
    val RECOMMENDED_THREADS = 4..6

    // ===== 共享状态 =====
    var isModelDownloading by mutableStateOf(false)
    var modelDownloadProgress by mutableFloatStateOf(0f)
    var downloadStatus by mutableStateOf("模型已内置，开箱即用")

    /** 最近一次识别器初始化失败的原因；null 表示未失败（供 UI 显示真实原因而非"请先下载"） */
    var lastInitError: String? = null
        private set

    private var downloadJob: Job? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    // ===== 模型路径 =====

    /** 下载版模型目录（filesDir） */
    private fun svcDir(context: Context): File =
        File(context.filesDir, "sherpa_models/$SVC_DIR_NAME")

    /** 内置 assets 是否可用（打包遗漏或损坏时返回 false，交由下载版兜底） */
    private fun assetModelAvailable(context: Context): Boolean = try {
        context.assets.open(SVC_ASSET_MODEL).close()
        context.assets.open(SVC_ASSET_TOKENS).close()
        true
    } catch (e: Exception) {
        Log.w(TAG, "内置模型不可用：${e.message}")
        false
    }

    /** 下载版是否就绪（带最小尺寸校验，避免中断下载留下的残缺文件被误判） */
    private fun downloadedModelReady(context: Context): Boolean {
        val dir = svcDir(context)
        val model = dir.resolve(SVC_MODEL)
        val tok = dir.resolve(SVC_TOKENS)
        return model.exists() && model.length() > 100_000_000L &&
            tok.exists() && tok.length() > 1024L
    }

    /**
     * 当前生效的模型来源。
     * **下载版优先**：便于不发版替换模型（把新文件放进去即生效）。
     */
    fun activeModelSource(context: Context): String = when {
        downloadedModelReady(context) -> "下载版（filesDir）"
        assetModelAvailable(context) -> "内置版（assets）"
        else -> "不可用"
    }

    /** SenseVoice 支持的语言（语言标签直接透传给模型） */
    val sherpaLanguages = listOf(
        "auto" to "自动",
        "zh" to "中文",
        "en" to "英文",
        "ja" to "日文",
        "ko" to "韩文",
        "yue" to "粤语"
    )

    /** 模型是否就绪：下载版或内置版任一可用即可 */
    fun isModelReady(context: Context): Boolean =
        downloadedModelReady(context) || assetModelAvailable(context)

    // ===== 下载管理 =====

    fun startModelDownload(context: Context) {
        if (isModelDownloading) return
        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            downloadSenseVoiceCpu(context)
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        isModelDownloading = false
        modelDownloadProgress = 0f
        downloadStatus = "已取消"
    }

    /** 下载 SenseVoice 模型（单文件 ×2，带进度与断点续传） */
    private suspend fun downloadSenseVoiceCpu(context: Context): File? = withContext(Dispatchers.IO) {
        if (isModelReady(context)) {
            Log.i(TAG, "SenseVoice cached: ${svcDir(context)}")
            return@withContext svcDir(context)
        }
        val dir = svcDir(context)
        dir.mkdirs()
        withContextMain {
            isModelDownloading = true
            modelDownloadProgress = 0f
            downloadStatus = "准备下载 SenseVoice 模型（约 ${SVC_MODEL_MB}MB）..."
        }

        // 模型占 99% 体积，词表瞬间完成，因此进度按 0.99 / 0.01 分配
        val modelOk = downloadFileWithResume(
            url = SVC_MODEL_URL,
            dest = dir.resolve(SVC_MODEL),
            progressBase = 0f,
            progressSpan = 0.99f,
            expectMinBytes = 100_000_000L,
            label = "SenseVoice 模型"
        )
        if (!modelOk) {
            withContextMain {
                isModelDownloading = false
                downloadStatus = "SenseVoice 模型下载失败（可重试）"
            }
            return@withContext null
        }
        val tokensOk = downloadFileWithResume(
            url = SVC_TOKENS_URL,
            dest = dir.resolve(SVC_TOKENS),
            progressBase = 0.99f,
            progressSpan = 0.01f,
            expectMinBytes = 1024L,
            label = "词表"
        )
        withContextMain {
            isModelDownloading = false
            modelDownloadProgress = 1f
            downloadStatus = if (tokensOk) "SenseVoice 模型就绪" else "词表下载失败（可重试）"
        }
        if (tokensOk) dir else null
    }

    /**
     * 单文件下载（支持 Range 断点续传 + 进度回调）。
     * 不做解压，用于 onnx 这类单文件模型。
     */
    private suspend fun downloadFileWithResume(
        url: String,
        dest: File,
        progressBase: Float,
        progressSpan: Float,
        expectMinBytes: Long,
        label: String
    ): Boolean {
        if (dest.exists() && dest.length() >= expectMinBytes) return true
        var attempt = 0
        while (attempt < 3) {
            attempt++
            try {
                val existing = if (dest.exists()) dest.length() else 0L
                val req = Request.Builder().url(url).apply {
                    if (existing > 0) addHeader("Range", "bytes=$existing-")
                }.build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code != 200 && resp.code != 206) {
                        Log.w(TAG, "$label HTTP ${resp.code}")
                        return@use false
                    }
                    val body = resp.body ?: return@use false
                    val total = body.contentLength().let { if (it > 0) it + existing else -1L }
                    val append = existing > 0 && resp.code == 206
                    if (!append) dest.delete()
                    body.byteStream().use { input ->
                        FileOutputStream(dest, append).use { output ->
                            val buf = ByteArray(1 shl 20)
                            var written = if (append) existing else 0L
                            var lastReport = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                written += n
                                val now = System.currentTimeMillis()
                                if (now - lastReport > 300) {
                                    lastReport = now
                                    val frac = if (total > 0) (written.toFloat() / total) else 0f
                                    val p = progressBase + progressSpan * frac.coerceIn(0f, 1f)
                                    val writtenMb = written / 1048576
                                    val totalMb = if (total > 0) "${total / 1048576}MB" else "?"
                                    withContextMain {
                                        modelDownloadProgress = p
                                        downloadStatus = "$label ${writtenMb}MB / $totalMb"
                                    }
                                }
                            }
                        }
                    }
                }
                if (dest.exists() && dest.length() >= expectMinBytes) return true
                Log.w(TAG, "$label 下载不完整：${dest.length()} 字节")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$label 下载异常（第 $attempt 次）：${e.message}")
                delay(1200L * attempt)
            }
        }
        return false
    }

    // ===== 识别器创建 =====

    /**
     * v2.0.127：清理已废弃引擎遗留的模型目录，释放设备存储。
     *
     * 被移除的引擎（v1.0.126 起不再使用）会留下数百 MB 到 1GB+ 的模型：
     *  - `sherpa_models/sherpa-onnx-qwen3-*`：Qwen3-ASR（约 838MB）
     *  - `sherpa_models/sherpa-onnx-qnn-*`：SenseVoice QNN（各 SoC 一套，约 161~241MB）
     *  - `vosk_models/`：Vosk 各语言模型（40MB~1.3GB）
     *
     * **只删已知废弃目录，不做通配清理**；当前在用的
     * `sherpa_models/sense-voice-cpu`（下载版兜底/更新通道）与 `silero_vad.onnx` 一律保留。
     *
     * 供 Application.onCreate 在后台线程调用（删除量大，不能占用主线程）。
     * @return 释放的字节数
     */
    fun cleanupLegacyModels(context: Context): Long {
        var freed = 0L
        try {
            // 1) sherpa_models 下已废弃引擎的目录
            val root = File(context.filesDir, "sherpa_models")
            if (root.isDirectory) {
                root.listFiles()?.forEach { child ->
                    if (!child.isDirectory) return@forEach
                    val name = child.name
                    val isLegacy = name.startsWith("sherpa-onnx-qwen3") ||
                        name.startsWith("sherpa-onnx-qnn-")
                    if (isLegacy) {
                        val size = dirSize(child)
                        if (child.deleteRecursively()) {
                            freed += size
                            Log.i(TAG, "已清理废弃模型目录：$name（${size / 1048576}MB）")
                        } else {
                            Log.w(TAG, "清理失败：${child.absolutePath}")
                        }
                    }
                }
            }
            // 2) Vosk 模型目录（整个引擎已移除）
            val vosk = File(context.filesDir, "vosk_models")
            if (vosk.exists()) {
                val size = dirSize(vosk)
                if (vosk.deleteRecursively()) {
                    freed += size
                    Log.i(TAG, "已清理 Vosk 模型目录（${size / 1048576}MB）")
                }
            }
            if (freed > 0) {
                Log.i(TAG, "旧模型清理完成，共释放 ${freed / 1048576}MB")
            }
        } catch (e: Exception) {
            Log.w(TAG, "旧模型清理出错：${e.message}")
        }
        return freed
    }

    /** 递归统计目录体积（清理前先算，便于日志量化） */
    private fun dirSize(dir: File): Long {
        var sum = 0L
        dir.listFiles()?.forEach { f ->
            sum += if (f.isDirectory) dirSize(f) else f.length()
        }
        return sum
    }

    /**
     * 创建 SenseVoice 识别器。
     *
     * [threads] 为推理线程数（1~10）；传 0 或越界时按设备核心数自动取 `min(核数, 4)`。
     * 推荐值 4–6：太少跟不上播放速度，太多会挤占视频解码与渲染。
     *
     * 模型来源：下载版（filesDir）优先，其次内置 assets —— 前者便于不发版换模型。
     * 传 asset 路径时必须同时给非空 AssetManager，传绝对路径时必须给 null
     * （sherpa-onnx 对"绝对路径 + 非空 AssetManager"会判定冲突并终止进程）。
     */
    fun createRecognizer(
        context: Context,
        language: String = "auto",
        threads: Int = 0
    ): OfflineRecognizer? {
        lastInitError = null
        if (!isModelReady(context)) {
            Log.w(TAG, "SenseVoice model not ready（内置缺失且无下载版）")
            lastInitError = "模型不可用：内置资源缺失且未下载（约 ${SVC_MODEL_MB}MB）"
            return null
        }
        val auto = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val numThreads = if (threads in MIN_THREADS..MAX_THREADS) threads else auto
        val useDownloaded = downloadedModelReady(context)

        val assetManager: android.content.res.AssetManager?
        val modelPath: String
        val tokensPath: String
        if (useDownloaded) {
            val dir = svcDir(context)
            assetManager = null
            modelPath = dir.resolve(SVC_MODEL).absolutePath
            tokensPath = dir.resolve(SVC_TOKENS).absolutePath
        } else {
            assetManager = context.assets
            modelPath = SVC_ASSET_MODEL
            tokensPath = SVC_ASSET_TOKENS
        }
        Log.i(
            TAG,
            "SenseVoice: source=${if (useDownloaded) "download" else "assets"} " +
                "model=$modelPath lang=$language threads=$numThreads（自动值=$auto）"
        )
        return try {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = modelPath,
                        language = language,                // "auto" / "zh" / "en" / "ja" / "ko" / "yue"
                        useInverseTextNormalization = true, // 数字/日期正规化，字幕更可读
                    ),
                    tokens = tokensPath,
                    numThreads = numThreads,
                    debug = false,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            )
            OfflineRecognizer(assetManager, config).also {
                Log.i(TAG, "SenseVoice recognizer created (lang=$language, threads=$numThreads)")
            }
        } catch (e: Throwable) {
            // 用 Throwable：native 初始化失败可能抛 UnsatisfiedLinkError 等 Error 子类
            Log.e(TAG, "SenseVoice init failed: ${e.message}", e)
            lastInitError = "初始化失败：${e.message ?: e.javaClass.simpleName}"
            null
        }
    }

    /** 识别单段 PCM float 采样（[-1,1] 归一化，16kHz 单声道） */
    fun recognizeSegment(recognizer: OfflineRecognizer, samples: FloatArray): String {
        return try {
            val stream = recognizer.createStream()
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text.trim()
            stream.release()
            text
        } catch (e: Exception) {
            Log.e(TAG, "recognizeSegment failed: ${e.message}", e)
            ""
        }
    }

    fun shutdown() {
        cancelDownload()
    }

    private suspend fun withContextMain(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
}
