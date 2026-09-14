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
 * 模型首次使用时下载缓存，单文件直链 + Range 断点续传。
 */
object SherpaAsrManager {

    private const val TAG = "SherpaAsr"

    // ===== SenseVoice CPU 配置 =====
    //
    // 依据《模型来源与下载地址》：
    //   sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17，取 model.int8.onnx
    //   （239MB）+ tokens.txt。234M 参数，中英日韩粤，RTF 0.026，自带标点。
    //
    // 下载走 hf-mirror 单文件直链而非官方 tar.bz2：239MB 的 bz2 在手机端解压很慢，
    // 且中断后要整体重下；单文件可断点续传，配合国内镜像更稳。
    private const val SVC_DIR_NAME = "sense-voice-cpu"
    private const val SVC_MODEL = "model.int8.onnx"
    private const val SVC_TOKENS = "tokens.txt"
    private const val SVC_MODEL_MB = 228
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
    var downloadStatus by mutableStateOf("就绪")

    /** 最近一次识别器初始化失败的原因；null 表示未失败（供 UI 显示真实原因而非"请先下载"） */
    var lastInitError: String? = null
        private set

    private var downloadJob: Job? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    // ===== 模型路径 =====

    /** SenseVoice CPU 模型目录 */
    private fun svcDir(context: Context): File =
        File(context.filesDir, "sherpa_models/$SVC_DIR_NAME")

    /** SenseVoice 支持的语言（语言标签直接透传给模型） */
    val sherpaLanguages = listOf(
        "auto" to "自动",
        "zh" to "中文",
        "en" to "英文",
        "ja" to "日文",
        "ko" to "韩文",
        "yue" to "粤语"
    )

    /** 模型是否就绪（带最小尺寸校验，避免中断下载留下的残缺文件被当作可用） */
    fun isModelReady(context: Context): Boolean {
        val dir = svcDir(context)
        val model = dir.resolve(SVC_MODEL)
        val tok = dir.resolve(SVC_TOKENS)
        return model.exists() && model.length() > 100_000_000L &&
            tok.exists() && tok.length() > 1024L
    }

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
     * 创建 SenseVoice 识别器。
     *
     * [threads] 为推理线程数（1~10）；传 0 或越界时按设备核心数自动取
     * `min(核数, 4)`。推荐值 4–6：
     * - 太少：RTF 变差，实时字幕跟不上播放速度
     * - 太多：把核心吃满，挤压视频解码与渲染，反而卡顿
     */
    fun createRecognizer(
        context: Context,
        language: String = "auto",
        threads: Int = 0
    ): OfflineRecognizer? {
        lastInitError = null
        if (!isModelReady(context)) {
            Log.w(TAG, "SenseVoice model not ready: ${svcDir(context)}")
            lastInitError = "模型未下载或文件不完整（约 ${SVC_MODEL_MB}MB）"
            return null
        }
        val dir = svcDir(context)
        val auto = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val numThreads = if (threads in MIN_THREADS..MAX_THREADS) threads else auto
        val modelPath = dir.resolve(SVC_MODEL).absolutePath
        val tokensPath = dir.resolve(SVC_TOKENS).absolutePath
        Log.i(TAG, "SenseVoice: model=$modelPath lang=$language threads=$numThreads（自动值=$auto）")
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
            OfflineRecognizer(null, config).also {
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
