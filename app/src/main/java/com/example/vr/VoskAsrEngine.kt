package com.example.vr

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Offline language model for Vosk. Small models (~40-50MB zip) suitable for
 * mobile devices. The model is downloaded and extracted on first use.
 */
enum class VoskLanguage(val id: Int, val label: String) {
    ZH(0, "中文"),
    EN(1, "英文"),
    JA(2, "日文")
}

/** 模型大小档位：小模型（快、省空间）与大模型（准确率高、体积大） */
enum class VoskModelSize(val id: Int, val label: String) {
    SMALL(0, "小模型（快速）"),
    LARGE(1, "大模型（高准确）")
}

/** 具体模型选项（语言 × 大小） */
data class VoskModelOption(
    val language: VoskLanguage,
    val size: VoskModelSize,
    val label: String,
    val modelName: String,
    val downloadUrl: String,
    val sizeMb: Int
)

/** 全部可用模型（v87：支持按语言/大小选择，首次使用时下载缓存） */
val VoskModels: List<VoskModelOption> = listOf(
    // 中文
    VoskModelOption(
        VoskLanguage.ZH, VoskModelSize.SMALL, "中文 · 小模型",
        "vosk-model-small-cn-0.22",
        "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip", 40
    ),
    VoskModelOption(
        VoskLanguage.ZH, VoskModelSize.LARGE, "中文 · 大模型",
        "vosk-model-cn-0.3",
        "https://alphacephei.com/vosk/models/vosk-model-cn-0.3.zip", 300
    ),
    // 英文
    VoskModelOption(
        VoskLanguage.EN, VoskModelSize.SMALL, "英文 · 小模型",
        "vosk-model-small-en-us-0.15",
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", 40
    ),
    VoskModelOption(
        VoskLanguage.EN, VoskModelSize.LARGE, "英文 · 大模型",
        "vosk-model-en-us-0.22-lgraph",
        "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22-lgraph.zip", 128
    ),
    // 日文
    VoskModelOption(
        VoskLanguage.JA, VoskModelSize.SMALL, "日文 · 小模型",
        "vosk-model-small-ja-0.22",
        "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip", 40
    ),
    VoskModelOption(
        VoskLanguage.JA, VoskModelSize.LARGE, "日文 · 大模型",
        "vosk-model-ja-0.22",
        "https://alphacephei.com/vosk/models/vosk-model-ja-0.22.zip", 1100
    )
)

data class VoskAsrConfig(
    val language: VoskLanguage = VoskLanguage.ZH,
    val modelSize: VoskModelSize = VoskModelSize.SMALL,
    val sampleRate: Int = 16000
) {
    /** 当前语言+大小对应的具体模型（找不到时回退到小模型） */
    val modelOption: VoskModelOption
        get() = VoskModels.firstOrNull { it.language == language && it.size == modelSize }
            ?: VoskModels.first { it.language == language && it.size == VoskModelSize.SMALL }
}

/**
 * Vosk 模型管理（v86 重构：实时识别已移除，本类只负责模型下载/缓存，
 * 供后台批处理转写（AsrBatchTranscriber）复用）。
 */
class RealtimeAsrManager(private val context: Context) {

    var config by mutableStateOf(VoskAsrConfig())
    var isModelDownloading by mutableStateOf(false)
    var modelDownloadProgress by mutableFloatStateOf(0f)
    var statusMessage by mutableStateOf("Vosk 离线识别模型就绪")

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * 供后台批处理转写（AsrBatchTranscriber）复用——获取/下载模型目录。
     */
    suspend fun ensureModelForBatch(model: VoskModelOption): File? = ensureModel(model)

    /**
     * Downloads (once) and extracts the Vosk model for the selected option.
     * Returns the directory containing the model files, or null on failure.
     */
    private suspend fun ensureModel(model: VoskModelOption): File? = withContext(Dispatchers.IO) {
        val targetDir = File(context.filesDir, "vosk_models/${model.modelName}")
        val marker = File(targetDir, ".ready")

        // Fast path: model already downloaded. The marker stores the real model
        // directory (the zip contains a top-level folder of the same name).
        if (marker.exists()) {
            val storedDir = marker.readText().trim()
            val cachedDir = if (storedDir.isNotBlank() && File(storedDir).exists()) {
                File(storedDir)
            } else {
                findModelDir(targetDir) ?: return@withContext null
            }
            Log.i("VoskAsr", "Model cached at: ${cachedDir.absolutePath}")
            return@withContext cachedDir
        }

        Log.i("VoskAsr", "Model not cached, downloading from ${model.downloadUrl}")
        withContextMain {
            isModelDownloading = true
            modelDownloadProgress = 0f
            statusMessage = "正在下载 ${model.label} (${model.modelName})..."
        }

        try {
            targetDir.mkdirs()
            val tmp = File(context.cacheDir, "vosk_download_${model.modelName}.zip")
            val request = Request.Builder().url(model.downloadUrl).build()
            val downloadedOk = httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e("VoskAsr", "Model download HTTP ${resp.code}")
                    return@use false
                }
                val body = resp.body ?: return@use false
                val total = body.contentLength()
                var downloaded = 0L
                body.byteStream().use { input ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            downloaded += n
                            if (total > 0) {
                                val p = downloaded.toFloat() / total
                                withContextMain { modelDownloadProgress = p }
                            }
                        }
                    }
                }
                true
            }

            if (!downloadedOk) {
                return@withContext null
            }

            // Extract the zip archive (models are plain zip files)
            ZipInputStream(FileInputStream(tmp)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.replace("\\", "/")
                    // Basic traversal protection
                    val safeName = name.split("/").filter { it.isNotBlank() && it != ".." }.joinToString("/")
                    val outFile = File(targetDir, safeName)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out -> zip.copyTo(out) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            tmp.delete()

            // The zip usually contains a single top-level directory with the model files
            val realModelDir = findModelDir(targetDir) ?: targetDir
            marker.writeText(realModelDir.absolutePath)

            withContextMain {
                isModelDownloading = false
                statusMessage = "模型下载完成"
            }
            realModelDir
        } catch (e: CancellationException) {
            // 切换语言/停止识别会取消下载协程——这是正常取消，不是失败。
            withContextMain { isModelDownloading = false }
            throw e
        } catch (e: Exception) {
            Log.e("VoskAsr", "Model download/extract failed", e)
            withContextMain { isModelDownloading = false }
            null
        }
    }

    private fun findModelDir(dir: File): File? {
        val children = dir.listFiles() ?: return null
        for (f in children) {
            if (f.isDirectory && File(f, "am").exists() && File(f, "conf").exists()) {
                return f
            }
        }
        return null
    }

    private suspend fun withContextMain(block: () -> Unit) {
        withContext(Dispatchers.Main) {
            block()
        }
    }
}
