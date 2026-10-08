package com.example.vr

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
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
 * 可下载的**本地 LLM**（v2.4.12）。
 *
 * 用于「本地 AI 字幕翻译」与「本地 AI 弹幕生成」—— 两者**共用同一个模型与引擎**，
 * 不额外下载第二份权重（0.5GB 级别的文件对手机存储不是小数目）。
 */
data class LocalLlmModel(
    val id: String,
    val displayName: String,
    /** 落盘文件名（同时用于判断是否已下载）。 */
    val fileName: String,
    /** 期望体积（用于「下完没下完」的校验，以及 UI 显示）。 */
    val sizeBytes: Long,
    /** 下载源，**按顺序尝试**（放镜像在最后兜底）。 */
    val urls: List<String>,
    /** 推理上下文长度。 */
    val contextSize: Int,
    val description: String
)

/**
 * 本地 LLM 管理器（v2.4.12）—— **下载 + 加载 + 推理**三层。
 *
 * ## 运行时的选择（已逐项实测核对）
 * `dev.ffmpegkit-maintained:llama-android:0.1.1`：
 * - **minSdk 24**（与本项目一致；另一候选 `net.ladenthin` 是 minSdk 28，**会 manifest 合并失败**）
 * - **只有 arm64-v8a**（与 v2.4.10 起的单 ABI 完全吻合）
 * - llama.cpp **build b9878**（已支持 Qwen3.5 的 `qwen35` 架构）
 *
 * ## 设计要点
 * - **单例模型**：llama 的 `LlamaModel` 持有 native 资源（数百 MB），
 *   全局只允许一份 —— 与 MPV 的单例约束同理，反复加载会 OOM。
 * - **不持有 Context**：路径由调用方传（与 `DanmuVisionClient` / `SubtitleTranslator` 同范式）。
 * - **失败静默降级**：模型没下载 / 加载失败 → 返回 null，由调用方回落到云端引擎，
 *   绝不抛给播放链路（弹幕与字幕都是「锦上添花」，不能拖垮播放）。
 */
object LocalLlmManager {

    private const val TAG = "LocalLlmManager"

    /** 模型目录：`filesDir/llm/`。与 sherpa 的 `sherpa_models/` 平级，便于清理时区分。 */
    private const val DIR_NAME = "llm"

    /** 最小有效体积（防止下载中断留下的残缺文件被当成「已就绪」）。 */
    private const val MIN_VALID_BYTES = 100L * 1024 * 1024

    /** 下载重试次数。 */
    private const val MAX_DOWNLOAD_ATTEMPTS = 3

    const val MODEL_ID_QWEN35_08B = "qwen35-0.8b-q4km"

    /**
     * 已登记的本地模型。
     *
     * ⚠️ 只登记**一个**：本地 LLM 的价值在"离线可用"，多份权重只是纯占存储；
     *    确实需要第二个时再往这里加（UI 会自动多出一行）。
     */
    val MODELS: List<LocalLlmModel> = listOf(
        LocalLlmModel(
            id = MODEL_ID_QWEN35_08B,
            displayName = "Qwen3.5 0.8B（Q4_K_M）",
            fileName = "Qwen3.5-0.8B-Q4_K_M.gguf",
            sizeBytes = 574_000_000L,
            urls = listOf(
                // 主源：Hugging Face 官方
                "https://huggingface.co/bartowski/Qwen_Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf",
                // 兜底：镜像（⚠️ 历史上「按文件下载」在真机曾返回 401，故放最后且允许失败）
                "https://hf-mirror.com/bartowski/Qwen_Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf"
            ),
            contextSize = 4096,
            description = "阿里 Qwen3.5 最轻量档（Apache 2.0）。中英双语翻译与短文本生成，" +
                "约 0.57GB，纯 CPU 推理"
        )
    )

    // ===================== 共享状态（供 UI 订阅） =====================

    var isDownloading by mutableStateOf(false)
        private set
    var downloadProgress by mutableFloatStateOf(0f)
        private set
    var downloadStatus by mutableStateOf("")
        private set

    /** 当前已加载的模型 id；null = 未加载。 */
    var loadedModelId by mutableStateOf<String?>(null)
        private set

    /** 最近一次错误（供 UI 显示真实原因，而不是笼统的"不可用"）。 */
    var lastError: String? = null
        private set

    private var loadedModel: LlamaModel? = null
    private var downloadJob: Job? = null

    /**
     * 下载用 HTTP 客户端。
     *
     * ⚠️ `readTimeout` 取 90 秒：大文件下载一旦读阻塞超时抛异常，
     * 上层才能按 `Range` **续传**，而不是干等到天荒地老。
     */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    // ===================== 路径 =====================

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    fun fileOf(context: Context, model: LocalLlmModel): File = File(dir(context), model.fileName)

    /** 模型是否已就绪（带最小体积校验，避免把下载中断的残片当成可用）。 */
    fun isReady(context: Context, model: LocalLlmModel): Boolean {
        val f = fileOf(context, model)
        return f.isFile && f.length() >= MIN_VALID_BYTES
    }

    /** 已下载的模型（没有则 null）。 */
    fun readyModel(context: Context): LocalLlmModel? = MODELS.firstOrNull { isReady(context, it) }

    /** 已下载体积（MB），供 UI 显示。 */
    fun downloadedMb(context: Context, model: LocalLlmModel): Long =
        fileOf(context, model).let { if (it.isFile) it.length() / 1048576 else 0L }

    // ===================== 下载 =====================

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        isDownloading = false
        downloadProgress = 0f
        downloadStatus = ""
    }

    /**
     * 下载指定模型（带进度、断点续传、多源回退）。
     *
     * @param onDone 完成回调（成功传 true）
     */
    fun startDownload(
        context: Context,
        model: LocalLlmModel,
        onDone: (Boolean) -> Unit = {}
    ) {
        if (isDownloading) return
        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            val ok = downloadInternal(context, model)
            withContext(Dispatchers.Main) {
                isDownloading = false
                downloadProgress = if (ok) 1f else 0f
                onDone(ok)
            }
        }
    }

    private suspend fun downloadInternal(context: Context, model: LocalLlmModel): Boolean =
        withContext(Dispatchers.IO) {
            if (isReady(context, model)) return@withContext true
            val dir = dir(context)
            if (!dir.exists() && !dir.mkdirs()) {
                lastError = "无法创建模型目录：${dir.absolutePath}"
                Log.e(TAG, lastError!!)
                return@withContext false
            }
            val dest = fileOf(context, model)
            val tmp = File(dir, model.fileName + ".part")

            withContext(Dispatchers.Main) {
                isDownloading = true
                downloadProgress = 0f
                downloadStatus = "准备下载 ${model.displayName}…"
            }

            for (url in model.urls) {
                var attempt = 0
                while (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                    attempt++
                    try {
                        val existing = if (tmp.exists()) tmp.length() else 0L
                        val req = Request.Builder().url(url).apply {
                            if (existing > 0) addHeader("Range", "bytes=$existing-")
                            addHeader("User-Agent", "Mozilla/5.0")
                        }.build()
                        var ok = false
                        httpClient.newCall(req).execute().use { resp ->
                            if (resp.code != 200 && resp.code != 206) {
                                Log.w(TAG, "下载 HTTP ${resp.code}（$url）")
                                // 401/403 常见于镜像需要鉴权 → 换下一个源，不重试当前源
                                if (resp.code == 401 || resp.code == 403 || resp.code == 404) {
                                    attempt = MAX_DOWNLOAD_ATTEMPTS
                                }
                                return@use
                            }
                            val body = resp.body ?: return@use
                            val total = body.contentLength().let { if (it > 0) it + existing else model.sizeBytes }
                            val append = existing > 0 && resp.code == 206
                            if (!append) tmp.delete()
                            body.byteStream().use { input ->
                                FileOutputStream(tmp, append).use { output ->
                                    val buf = ByteArray(1 shl 20)
                                    var written = if (append) existing else 0L
                                    var lastReport = 0L
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        output.write(buf, 0, n)
                                        written += n
                                        val now = System.currentTimeMillis()
                                        if (now - lastReport > 400) {
                                            lastReport = now
                                            val frac = (written.toFloat() / total).coerceIn(0f, 1f)
                                            withContext(Dispatchers.Main) {
                                                downloadProgress = frac
                                                downloadStatus = "${written / 1048576}MB / ${total / 1048576}MB"
                                            }
                                        }
                                    }
                                }
                            }
                            ok = true
                        }
                        if (ok && tmp.length() >= MIN_VALID_BYTES) {
                            if (dest.exists()) dest.delete()
                            if (tmp.renameTo(dest)) {
                                Log.i(TAG, "模型下载完成：${dest.absolutePath}（${dest.length() / 1048576}MB）")
                                return@withContext true
                            }
                        }
                        Log.w(TAG, "下载不完整（${tmp.length()} 字节，第 $attempt 次）")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "下载异常（第 $attempt 次，$url）：${e.message}")
                        delay(1500L * attempt)
                    }
                }
                Log.w(TAG, "该源失败，尝试下一个：$url")
            }
            lastError = "所有下载源均失败（模型约 ${model.sizeBytes / 1048576}MB，请检查网络）"
            false
        }

    // ===================== 加载 / 释放 =====================

    /**
     * 确保模型已加载（同一个 id 已加载则直接复用）。
     *
     * ⚠️ **单例约束**：llama 的 `LlamaModel` 持有数百 MB native 资源，
     *    重复 `loadModel` 会成倍占用内存 → 这里做 id 级复用，
     *    并保证加载新模型前先释放旧的。
     */
    suspend fun ensureLoaded(context: Context, model: LocalLlmModel): LlamaModel? {
        loadedModel?.let { if (loadedModelId == model.id) return it }
        if (!isReady(context, model)) {
            lastError = "模型未下载"
            return null
        }
        release()
        return withContext(Dispatchers.IO) {
            try {
                val m = Llama.loadModel(
                    modelPath = fileOf(context, model).absolutePath,
                    config = LlamaConfig(
                        contextSize = model.contextSize,
                        threads = Runtime.getRuntime().availableProcessors()
                            .coerceIn(2, 6),
                        gpuLayers = 0   // 纯 CPU：本 AAR 是 CPU/NEON 版，无 Vulkan
                    )
                )
                loadedModel = m
                loadedModelId = model.id
                lastError = null
                Log.i(TAG, "模型已加载：${model.displayName}（ctx=${model.contextSize}）")
                m
            } catch (t: Throwable) {
                lastError = "模型加载失败：${t.message}"
                Log.e(TAG, "模型加载失败", t)
                null
            }
        }
    }

    /** 释放模型（切换功能/退出时调用）。 */
    fun release() {
        loadedModel?.let {
            try {
                Llama.releaseModel(it)
            } catch (t: Throwable) {
                Log.w(TAG, "释放模型异常：${t.message}")
            }
        }
        loadedModel = null
        loadedModelId = null
    }

    // ===================== 推理 =====================

    /**
     * 单轮文本补全（**无对话历史** —— 翻译与弹幕都是一次性任务，不需要多轮）。
     *
     * @return 生成文本；未加载/失败返回 null（由调用方回落云端引擎）
     */
    suspend fun complete(
        model: LlamaModel,
        prompt: String,
        systemPrompt: String? = null,
        maxTokens: Int = 256,
        temperature: Float = 0.3f
    ): String? = withContext(Dispatchers.IO) {
        try {
            val r = Llama.complete(
                model = model,
                prompt = prompt,
                systemPrompt = systemPrompt ?: "",
                maxTokens = maxTokens
            )
            Log.d(
                TAG,
                "推理完成：${r.tokensGenerated} tok, ${"%.1f".format(r.tokensPerSecond)} tok/s, " +
                    "prompt ${r.promptEvalTimeMs}ms, gen ${r.generateTimeMs}ms"
            )
            r.text
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            lastError = "推理失败：${t.message}"
            Log.e(TAG, "推理失败", t)
            null
        }
    }
}
