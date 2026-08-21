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
 * v110：sherpa-onnx Qwen3-ASR 离线语音识别引擎管理。
 *
 * 与 Vosk 并行，供用户选择作为 AI 字幕生成引擎。
 * Qwen3-ASR 0.6B INT8 支持 29 种语言 + 20 种中文方言，准确率高。
 *
 * 模型下载：GitHub Releases（.tar.bz2，838MB 压缩包，解压后 ~940MB）
 * 推荐首次下载后缓存到内部存储，后续离线使用。
 */
object SherpaAsrManager {

    private const val TAG = "SherpaAsr"

    // Qwen3-ASR 0.6B INT8 模型配置
    private const val MODEL_ARCHIVE = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2"
    private const val MODEL_DIR_NAME = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"
    private const val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$MODEL_ARCHIVE"
    private const val MODEL_SIZE_MB = 838

    // 模型文件名
    private const val CONV_FRONTEND = "conv_frontend.onnx"       // 42MB
    private const val ENCODER = "encoder.int8.onnx"              // 174MB
    private const val DECODER = "decoder.int8.onnx"              // 721MB
    private const val TOKENIZER_DIR = "tokenizer"                // vocab + merges

    // 状态（Compose mutableState 供 UI 订阅）
    var isModelDownloading by mutableStateOf(false)
    var modelDownloadProgress by mutableFloatStateOf(0f)
    var downloadStatus by mutableStateOf("就绪")

    private var downloadJob: Job? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    // ===== 模型路径 =====

    private fun modelDir(context: Context): File =
        File(context.filesDir, "sherpa_models/$MODEL_DIR_NAME")

    fun isModelReady(context: Context): Boolean {
        val dir = modelDir(context)
        return dir.resolve(CONV_FRONTEND).exists() &&
            dir.resolve(ENCODER).exists() &&
            dir.resolve(DECODER).exists() &&
            dir.resolve(TOKENIZER_DIR).resolve("vocab.json").exists()
    }

    // ===== 下载管理 =====

    fun startModelDownload(context: Context) {
        if (isModelDownloading) return
        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            downloadAndExtract(context)
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        isModelDownloading = false
        modelDownloadProgress = 0f
        downloadStatus = "已取消"
    }

    private suspend fun downloadAndExtract(context: Context): File? = withContext(Dispatchers.IO) {
        if (isModelReady(context)) {
            Log.i(TAG, "Model cached: ${modelDir(context)}")
            return@withContext modelDir(context)
        }

        val targetDir = modelDir(context)
        targetDir.mkdirs()
        val archiveFile = File(context.cacheDir, MODEL_ARCHIVE)

        withContextMain {
            isModelDownloading = true
            modelDownloadProgress = 0f
            downloadStatus = "连接 GitHub Releases..."
        }

        var retries = 3
        while (retries > 0) {
            withContextMain {
                modelDownloadProgress = 0f
                downloadStatus = if (retries < 3) "第 ${4 - retries} 次重试..." else "连接中..."
            }

            try {
                // 下载 .tar.bz2
                val req = Request.Builder().url(MODEL_URL).build()
                val downloadedOk = httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "HTTP ${resp.code}")
                        return@use false
                    }
                    val body = resp.body ?: return@use false
                    val total = body.contentLength()
                    if (total <= 0) return@use false
                    var downloaded = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(archiveFile).use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                if (Thread.interrupted()) { archiveFile.delete(); return@use false }
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                downloaded += n
                                if (total > 0) {
                                    val p = downloaded.toFloat() / total
                                    withContextMain {
                                        modelDownloadProgress = p
                                        downloadStatus = "下载 ${(p * 100).toInt()}% (${downloaded / (1024 * 1024)}/${total / (1024 * 1024)}MB)"
                                    }
                                }
                            }
                        }
                    }
                    true
                }

                if (!downloadedOk) {
                    retries--
                    if (retries > 0) {
                        withContextMain { downloadStatus = "下载失败，2秒后重试..." }
                        delay(2000)
                        continue
                    } else {
                        withContextMain { downloadStatus = "下载失败，请检查网络" }
                        return@withContext null
                    }
                }

                // 解压 tar.bz2（commons-compress）
                withContextMain { downloadStatus = "解压模型中（~${MODEL_SIZE_MB}MB，首次较慢）..." }
                extractTarBz2(archiveFile, targetDir)
                archiveFile.delete()

                if (!isModelReady(context)) {
                    withContextMain { downloadStatus = "解压失败，文件不完整" }
                    return@withContext null
                }

                withContextMain {
                    isModelDownloading = false
                    modelDownloadProgress = 1f
                    downloadStatus = "Qwen3-ASR 模型就绪"
                }
                Log.i(TAG, "Model ready: ${targetDir.absolutePath}")
                return@withContext targetDir

            } catch (e: CancellationException) {
                withContextMain { isModelDownloading = false; downloadStatus = "已取消" }
                throw e
            } catch (e: Exception) {
                retries--
                Log.e(TAG, "Error (retries=$retries): ${e.message}", e)
                if (retries > 0) {
                    withContextMain { downloadStatus = "出错，2秒后重试..." }
                    delay(2000)
                } else {
                    withContextMain { isModelDownloading = false; downloadStatus = "失败: ${e.message}" }
                }
            }
        }
        withContextMain { isModelDownloading = false; downloadStatus = "下载失败，请检查网络" }
        null
    }

    // ===== tar.bz2 解压（commons-compress 纯 Java）=====

    private fun extractTarBz2(archive: File, destDir: File) {
        FileInputStream(archive).use { fis ->
            BufferedInputStream(fis).use { bis ->
                BZip2CompressorInputStream(bis).use { bz2 ->
                    TarArchiveInputStream(bz2).use { tar ->
                        var entry = tar.nextEntry
                        while (entry != null) {
                            if (Thread.interrupted()) throw InterruptedException("解压被取消")
                            val name = entry.name.replace("\\", "/")
                            // 安全路径（防目录遍历）
                            val safeName = name.split("/").filter {
                                it.isNotBlank() && it != ".."
                            }.joinToString("/")
                            if (safeName.isEmpty()) {
                                entry = tar.nextEntry
                                continue
                            }
                            val outFile = File(destDir, safeName)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out ->
                                    tar.copyTo(out)
                                }
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
            }
        }
    }

    // ===== 识别器创建（供 AsrBatchTranscriber 调用）=====

    /**
     * 创建 OfflineRecognizer（调用方负责 release）。
     * 仅在模型已下载就绪时返回非 null。
     */
    fun createRecognizer(context: Context): OfflineRecognizer? {
        val dir = modelDir(context)
        if (!isModelReady(context)) {
            Log.w(TAG, "Model not ready")
            return null
        }
        return try {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    qwen3Asr = OfflineQwen3AsrModelConfig(
                        convFrontend = dir.resolve(CONV_FRONTEND).absolutePath,
                        encoder = dir.resolve(ENCODER).absolutePath,
                        decoder = dir.resolve(DECODER).absolutePath,
                        tokenizer = dir.resolve(TOKENIZER_DIR).absolutePath,
                    ),
                    tokens = "",        // Qwen3 用 tokenizer 目录，tokens.txt 为空
                    numThreads = 3,     // 官方推荐
                    debug = false,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
                maxActivePaths = 4,
                blankPenalty = 0.0f,
            )
            OfflineRecognizer(null, config).also { Log.i(TAG, "Recognizer created (Qwen3-ASR 0.6B INT8)") }
        } catch (e: Exception) {
            Log.e(TAG, "createRecognizer failed: ${e.message}", e)
            null
        }
    }

    /**
     * 识别单段 PCM16 float 采样（[-1,1] 归一化，16kHz 单声道）。
     * @return 识别文本，失败返回空字符串
     */
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

    // ===== 工具函数 =====

    fun shutdown() { cancelDownload() }

    private suspend fun withContextMain(block: () -> Unit) =
        withContext(Dispatchers.Main) { block() }
}
