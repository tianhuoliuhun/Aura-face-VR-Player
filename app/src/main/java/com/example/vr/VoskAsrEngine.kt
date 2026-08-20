package com.example.vr

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
        get() {
            val lang = language
            val sz = modelSize
            var match: VoskModelOption? = null
            for (model in VoskModels) {
                if (model.language == lang && model.size == sz) {
                    match = model
                    break
                }
            }
            if (match != null) return match
            for (model in VoskModels) {
                if (model.language == lang && model.size == VoskModelSize.SMALL) {
                    return model
                }
            }
            return VoskModels.first()
        }
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
    var downloadStatus by mutableStateOf("就绪")

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    private var downloadJob: Job? = null

    /**
     * 供后台批处理转写（AsrBatchTranscriber）复用——获取/下载模型目录。
     */
    suspend fun ensureModelForBatch(model: VoskModelOption): File? = ensureModel(model)

    /**
     * 开始下载模型（可在外部调用，如用户点击下载按钮时）
     */
    fun startModelDownload(model: VoskModelOption) {
        if (isModelDownloading) return
        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            ensureModel(model)
        }
    }

    /** 取消正在进行的下载 */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        isModelDownloading = false
        modelDownloadProgress = 0f
        downloadStatus = "已取消"
    }

    /**
     * 下载并解压模型（内部使用，也供外部调用）。
     * 含 3 次重试、取消支持、进度回调。
     */
    private suspend fun ensureModel(model: VoskModelOption): File? = withContext(Dispatchers.IO) {
        val targetDir = File(context.filesDir, "vosk_models/${model.modelName}")
        val marker = File(targetDir, ".ready")

        // 快速路径：模型已下载
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
            downloadStatus = "准备下载 ${model.label} (${model.sizeMb}MB)..."
        }

        var retries = 3
        while (retries > 0) {
            if (Thread.currentThread().isInterrupted) return@withContext null
            withContextMain {
                isModelDownloading = true
                modelDownloadProgress = 0f
                downloadStatus = if (retries < 3) "第 ${4 - retries} 次重试..." else "正在连接服务器..."
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
                    if (total <= 0) {
                        Log.e("VoskAsr", "Content-Length missing")
                        return@use false
                    }
                    var downloaded = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(tmp).use { out ->
                            val buf = ByteArray(128 * 1024)
                            while (true) {
                                if (Thread.currentThread().isInterrupted) {
                                    tmp.delete()
                                    return@use false
                                }
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                downloaded += n
                                if (total > 0) {
                                    val p = downloaded.toFloat() / total
                                    withContextMain {
                                        modelDownloadProgress = p
                                        downloadStatus = "下载中 ${(p * 100).toInt()}% (${
                                            downloaded / (1024 * 1024)
                                        }MB/${total / (1024 * 1024)}MB)"
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
                        Log.w("VoskAsr", "Download failed, retries left: $retries")
                        withContextMain {
                            downloadStatus = "下载失败，2 秒后重试（剩余 $retries 次）..."
                        }
                        kotlinx.coroutines.delay(2000)
                        continue
                    } else {
                        withContextMain {
                            downloadStatus = "下载失败，请检查网络后重试"
                        }
                        return@withContext null
                    }
                }

                // 解压 zip
                withContextMain { downloadStatus = "解压模型中..." }
                ZipInputStream(FileInputStream(tmp)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (Thread.currentThread().isInterrupted) {
                            tmp.delete()
                            return@withContext null
                        }
                        val name = entry.name.replace("\\", "/")
                        val safeName = name.split("/").filter {
                            it.isNotBlank() && it != ".."
                        }.joinToString("/")
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

                val realModelDir = findModelDir(targetDir) ?: targetDir
                marker.writeText(realModelDir.absolutePath)

                withContextMain {
                    isModelDownloading = false
                    modelDownloadProgress = 1f
                    downloadStatus = "模型 ${model.label} 已就绪"
                    statusMessage = "模型 ${model.label} 已就绪"
                }
                Log.i("VoskAsr", "Model ready at: ${realModelDir.absolutePath}")
                return@withContext realModelDir

            } catch (e: CancellationException) {
                withContextMain {
                    isModelDownloading = false
                    downloadStatus = "已取消"
                }
                throw e
            } catch (e: Exception) {
                retries--
                Log.e("VoskAsr", "Download/extract failed (retries left: $retries)", e)
                if (retries > 0) {
                    withContextMain {
                        downloadStatus = "出错: ${e.message}，2 秒后重试..."
                    }
                    kotlinx.coroutines.delay(2000)
                    continue
                } else {
                    withContextMain {
                        isModelDownloading = false
                        downloadStatus = "下载失败: ${e.message}"
                    }
                    return@withContext null
                }
            }
        }

        withContextMain {
            isModelDownloading = false
            downloadStatus = "下载失败，请检查网络"
        }
        null
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

    fun shutdown() {
        cancelDownload()
    }
}
