package com.example.vr

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.QnnConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * v111：sherpa-onnx 多引擎离线语音识别管理器。
 *
 * 支持两种引擎（由 AsrEngineType 控制）：
 *  - Qwen3-ASR 0.6B INT8：29 语言 + 20 种方言，CPU 推理，~940MB 模型
 *  - SenseVoice QNN：中英日韩粤 5 语言，高通 NPU 加速（SM8850 专属），~241MB 模型
 *
 * 模型均为首次使用时下载缓存，支持断点续传。
 */
object SherpaAsrManager {

    private const val TAG = "SherpaAsr"

    // ===== Qwen3-ASR 配置 =====
    private const val QWEN3_ARCHIVE = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2"
    private const val QWEN3_DIR_NAME = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"
    private const val QWEN3_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$QWEN3_ARCHIVE"
    private const val QWEN3_SIZE_MB = 838
    private const val QWEN3_CONV_FRONTEND = "conv_frontend.onnx"
    private const val QWEN3_ENCODER = "encoder.int8.onnx"
    private const val QWEN3_DECODER = "decoder.int8.onnx"
    private const val QWEN3_TOKENIZER = "tokenizer"

    // ===== SenseVoice QNN SM8850 配置 =====
    private const val SV_ARCHIVE = "sherpa-onnx-qnn-SM8850-binary-5-seconds-sense-voice-zh-en-ja-ko-yue-2024-07-17-int8.tar.bz2"
    private const val SV_DIR_NAME = "sherpa-onnx-qnn-SM8850-binary-5-seconds-sense-voice-zh-en-ja-ko-yue-2024-07-17-int8"
    private const val SV_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn-binary/$SV_ARCHIVE"
    private const val SV_SIZE_MB = 161
    private const val SV_MODEL_BIN = "model.bin"
    private const val SV_TOKENS = "tokens.txt"

    // ===== 共享状态 =====
    var isModelDownloading by mutableStateOf(false)
    var modelDownloadProgress by mutableFloatStateOf(0f)
    var downloadStatus by mutableStateOf("就绪")

    private var downloadJob: Job? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    // ===== 模型路径 =====

    private fun qwen3Dir(context: Context) = File(context.filesDir, "sherpa_models/$QWEN3_DIR_NAME")
    private fun svDir(context: Context) = File(context.filesDir, "sherpa_models/$SV_DIR_NAME")

    /** 检查指定引擎的模型是否就绪 */
    fun isModelReady(context: Context, engine: AsrEngineType): Boolean = when (engine) {
        AsrEngineType.QWEN3 -> {
            val dir = qwen3Dir(context)
            dir.resolve(QWEN3_CONV_FRONTEND).exists() &&
                dir.resolve(QWEN3_ENCODER).exists() &&
                dir.resolve(QWEN3_DECODER).exists() &&
                dir.resolve(QWEN3_TOKENIZER).resolve("vocab.json").exists()
        }
        AsrEngineType.SENSEVOICE_QNN -> {
            val dir = svDir(context)
            dir.resolve(SV_MODEL_BIN).exists() && dir.resolve(SV_TOKENS).exists()
        }
        else -> false
    }

    // ===== 下载管理 =====

    fun startModelDownload(context: Context, engine: AsrEngineType) {
        if (isModelDownloading) return
        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            when (engine) {
                AsrEngineType.QWEN3 -> downloadQwen3(context)
                AsrEngineType.SENSEVOICE_QNN -> downloadSenseVoice(context)
                else -> {}
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        isModelDownloading = false
        modelDownloadProgress = 0f
        downloadStatus = "已取消"
    }

    // ===== Qwen3-ASR 下载 =====

    private suspend fun downloadQwen3(context: Context): File? = withContext(Dispatchers.IO) {
        if (isModelReady(context, AsrEngineType.QWEN3)) {
            Log.i(TAG, "Qwen3 cached: ${qwen3Dir(context)}")
            return@withContext qwen3Dir(context)
        }
        val targetDir = qwen3Dir(context)
        targetDir.mkdirs()
        val archive = File(context.cacheDir, QWEN3_ARCHIVE)
        downloadAndExtract(archive, QWEN3_URL, QWEN3_SIZE_MB, targetDir) ?: return@withContext null
        // 修复目录嵌套
        val inner = File(targetDir, QWEN3_DIR_NAME)
        if (inner.exists()) { inner.listFiles()?.forEach { it.renameTo(File(targetDir, it.name)) }; inner.delete() }
        targetDir
    }

    // ===== SenseVoice QNN 下载 =====

    private suspend fun downloadSenseVoice(context: Context): File? = withContext(Dispatchers.IO) {
        if (isModelReady(context, AsrEngineType.SENSEVOICE_QNN)) {
            Log.i(TAG, "SenseVoice QNN cached: ${svDir(context)}")
            return@withContext svDir(context)
        }
        val targetDir = svDir(context)
        targetDir.mkdirs()
        val archive = File(context.cacheDir, SV_ARCHIVE)
        downloadAndExtract(archive, SV_URL, SV_SIZE_MB, targetDir) ?: return@withContext null
        // 修复目录嵌套
        val inner = File(targetDir, SV_DIR_NAME)
        if (inner.exists()) { inner.listFiles()?.forEach { it.renameTo(File(targetDir, it.name)) }; inner.delete() }
        targetDir
    }

    // ===== 通用下载+解压（断点续传）=====

    private suspend fun downloadAndExtract(archive: File, url: String, sizeMB: Int, destDir: File): File? {
        withContextMain { isModelDownloading = true; modelDownloadProgress = 0f; downloadStatus = "连接服务器..." }

        var retries = 3
        while (retries > 0) {
            withContextMain {
                modelDownloadProgress = 0f
                downloadStatus = if (retries < 3) "第 ${4 - retries} 次重试..." else "连接中..."
            }
            try {
                // 断点续传
                val existingSize = if (archive.exists()) archive.length() else 0L
                val reqBuilder = Request.Builder().url(url)
                if (existingSize > 0) reqBuilder.addHeader("Range", "bytes=$existingSize-")
                val req = reqBuilder.build()

                val ok = httpClient.newCall(req).execute().use { resp ->
                    if (resp.code != 200 && resp.code != 206) {
                        Log.e(TAG, "HTTP ${resp.code}")
                        if (existingSize > 0) archive.delete()
                        return@use false
                    }
                    val body = resp.body ?: return@use false
                    val contentLen = body.contentLength()
                    val total = if (resp.code == 206) existingSize + contentLen else contentLen
                    if (contentLen <= 0) return@use false
                    var downloaded = if (resp.code == 206) existingSize else 0L
                    body.byteStream().use { input ->
                        FileOutputStream(archive, resp.code == 206).use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                if (Thread.interrupted()) return@use false
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                downloaded += n
                                if (total > 0) {
                                    val p = downloaded.toFloat() / total
                                    withContextMain {
                                        modelDownloadProgress = p
                                        val dmb = downloaded / (1024 * 1024); val tmb = total / (1024 * 1024)
                                        downloadStatus = "下载 ${(p * 100).toInt()}% ($dmb/${tmb}MB)"
                                            .let { if (resp.code == 206) "续传 $it" else it }
                                    }
                                }
                            }
                        }
                    }
                    true
                }

                if (!ok) {
                    retries--
                    val partialMB = if (archive.exists()) archive.length() / (1024 * 1024) else 0
                    if (retries > 0) {
                        withContextMain { downloadStatus = if (partialMB > 0) "中断（已缓存 ${partialMB}MB），2秒后续传..." else "下载失败，2秒后重试..." }
                        delay(2000); continue
                    } else {
                        withContextMain { downloadStatus = "下载失败，请检查网络" }
                        return null
                    }
                }

                // 解压
                withContextMain { downloadStatus = "解压模型中（${sizeMB}MB）..." }
                extractTarBz2(archive, destDir)
                archive.delete()
                return destDir

            } catch (e: CancellationException) {
                withContextMain { isModelDownloading = false; downloadStatus = "已取消" }
                throw e
            } catch (e: Exception) {
                retries--
                if (retries > 0) {
                    withContextMain { downloadStatus = "出错，2秒后重试..." }
                    delay(2000)
                } else {
                    withContextMain { isModelDownloading = false; downloadStatus = "失败: ${e.message}" }
                    return null
                }
            }
        }
        withContextMain { isModelDownloading = false; downloadStatus = "下载失败" }
        return null
    }

    // ===== tar.bz2 解压 =====

    private fun extractTarBz2(archive: File, destDir: File) {
        // 方案1：系统 tar（toybox，Android 自带，支持 bzip2）
        try {
            val pb = ProcessBuilder("tar", "xjf", archive.absolutePath, "-C", destDir.absolutePath)
            pb.redirectErrorStream(true)
            val proc = pb.start(); val out = proc.inputStream.bufferedReader().readText(); val exit = proc.waitFor()
            if (exit == 0) { Log.i(TAG, "Extracted via system tar"); return }
            Log.w(TAG, "system tar failed (exit $exit): ${out.take(500)}")
        } catch (e: Exception) { Log.w(TAG, "system tar unavailable: ${e.message}") }

        // 方案2：commons-compress
        try {
            FileInputStream(archive).use { fis ->
                BufferedInputStream(fis).use { bis ->
                    BZip2CompressorInputStream(bis).use { bz2 ->
                        TarArchiveInputStream(bz2).use { tar ->
                            var entry = tar.nextEntry
                            while (entry != null) {
                                val name = entry.name.replace("\\", "/")
                                val safeName = name.split("/").filter { it.isNotBlank() && it != ".." }.joinToString("/")
                                if (safeName.isNotEmpty()) {
                                    val outFile = File(destDir, safeName)
                                    if (entry.isDirectory) outFile.mkdirs()
                                    else { outFile.parentFile?.mkdirs(); FileOutputStream(outFile).use { out -> tar.copyTo(out) } }
                                }
                                entry = tar.nextEntry
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Extract failed", e); throw e }
    }

    // ===== 识别器创建（供 AsrBatchTranscriber 调用）=====

    fun createRecognizer(context: Context, engine: AsrEngineType, language: String = ""): OfflineRecognizer? = when (engine) {
        AsrEngineType.QWEN3 -> createQwen3Recognizer(context, language)
        AsrEngineType.SENSEVOICE_QNN -> createSenseVoiceQnnRecognizer(context, language)
        else -> null
    }

    /** 支持的语言（中/英/日/韩 + 自动检测） */
    val sherpaLanguages = listOf(
        "auto" to "自动",
        "zh" to "中文",
        "en" to "英文",
        "ja" to "日文",
        "ko" to "韩文"
    )

    private fun createQwen3Recognizer(context: Context, language: String = ""): OfflineRecognizer? {
        val dir = qwen3Dir(context)
        if (!isModelReady(context, AsrEngineType.QWEN3)) { Log.w(TAG, "Qwen3 model not ready"); return null }
        val langCode = if (language == "auto") "" else language  // Qwen3: 空字符串=自动
        return try {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    qwen3Asr = OfflineQwen3AsrModelConfig(
                        convFrontend = dir.resolve(QWEN3_CONV_FRONTEND).absolutePath,
                        encoder = dir.resolve(QWEN3_ENCODER).absolutePath,
                        decoder = dir.resolve(QWEN3_DECODER).absolutePath,
                        tokenizer = dir.resolve(QWEN3_TOKENIZER).absolutePath,
                        // Qwen3 通过 tokenizer 自动检测语言，无需 language 参数
                    ),
                    tokens = "",
                    numThreads = 3,
                    debug = false,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
                maxActivePaths = 4,
                blankPenalty = 0.0f,
            )
            OfflineRecognizer(null, config).also { Log.i(TAG, "Qwen3-ASR recognizer created (lang=$langCode)") }
        } catch (e: Exception) { Log.e(TAG, "Qwen3 init failed: ${e.message}", e); null }
    }

    private fun createSenseVoiceQnnRecognizer(context: Context, language: String = "auto"): OfflineRecognizer? {
        val dir = svDir(context)
        if (!isModelReady(context, AsrEngineType.SENSEVOICE_QNN)) { Log.w(TAG, "SenseVoice QNN model not ready"); return null }
        val qnnHtpPath = File(context.applicationInfo.nativeLibraryDir, "libQnnHtp.so").absolutePath
        val qnnSysPath = File(context.applicationInfo.nativeLibraryDir, "libQnnSystem.so").absolutePath
        val contextBinPath = dir.resolve(SV_MODEL_BIN).absolutePath
        val tokensPath = dir.resolve(SV_TOKENS).absolutePath
        Log.i(TAG, "SenseVoice QNN: htp=$qnnHtpPath ctx=$contextBinPath lang=$language")
        return try {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        language = language,
                        qnnConfig = QnnConfig(
                            backendLib = qnnHtpPath,
                            systemLib = qnnSysPath,
                            contextBinary = contextBinPath,
                        ),
                    ),
                    tokens = tokensPath,
                    numThreads = 1,
                    debug = false,
                    provider = "qnn",
                ),
                decodingMethod = "greedy_search",
            )
            OfflineRecognizer(null, config).also { Log.i(TAG, "SenseVoice QNN recognizer created (lang=$language)") }
        } catch (e: Exception) { Log.e(TAG, "SenseVoice QNN init failed: ${e.message}", e); null }
    }

    /** 识别单段 PCM16 float 采样（[-1,1] 归一化，16kHz 单声道） */
    fun recognizeSegment(recognizer: OfflineRecognizer, samples: FloatArray): String {
        return try {
            val stream = recognizer.createStream()
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text.trim()
            stream.release()
            text
        } catch (e: Exception) { Log.e(TAG, "recognizeSegment failed: ${e.message}", e); "" }
    }

    fun shutdown() { cancelDownload() }

    private suspend fun withContextMain(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
}
