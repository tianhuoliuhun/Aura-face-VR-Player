package com.example.vr

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
    val description: String,

    // ===== v2.4.13：可选的**视觉编码器**（mmproj）=====
    // ⚠️ 只有**自建**的 libmtmd 才能用它（旧的 AAR 不含该库、无图像入口）。
    // 下载后本地模型才能「看画面」；不下载则只有纯文本能力。

    /** mmproj 落盘文件名；null = 该模型没有配套视觉编码器。 */
    val mmprojFileName: String? = null,
    /** mmproj 期望体积（用于 UI 显示与完整性校验）。 */
    val mmprojSizeBytes: Long = 0L,
    /** mmproj 下载源（按顺序尝试）。 */
    val mmprojUrls: List<String> = emptyList()
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

    /**
     * mmproj 的最小有效体积。
     *
     * 取 50MB：q8_0 档约 116MB、f16 档约 207MB，都远大于它；
     * 而下载中断的残片通常只有几 MB。⚠️ 不能沿用主模型的 100MB 阈值
     * （那会把 116MB 的 q8_0 档误判为"未下载"，见 isMmprojReady 的说明）。
     */
    private const val MIN_MMPROJ_BYTES = 50L * 1024 * 1024

    /** 下载重试次数。 */
    private const val MAX_DOWNLOAD_ATTEMPTS = 3

    /**
     * 下载用的 User-Agent。
     *
     * ⚠️ 必须带：Hugging Face 对**空 UA / 脚本式 UA** 会返回 403，
     * 而 403 会被 `downloadFileGeneric` 判为「该源不可用」直接换源 ——
     * 表现为「主源莫名其妙总是失败」。
     */
    private const val DOWNLOAD_UA = "Mozilla/5.0 (Android) AuraFaceVRPlayer"

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
                "约 0.57GB，纯 CPU 推理",
            // v2.4.13：**可选的视觉编码器**。
            // ⚠️ 主权重是纯文本的 —— 不下这个文件，本地模型就「看不到画面」，
            //    只能靠台词生成弹幕；下载后才有视觉能力（需自建 libmtmd，已具备）。
            // 选 q8_0 档（116MB）：f16 档 207MB 质量略好，但视觉编码器对量化不那么敏感，
            // 而 116MB 对手机存储友好得多。
            mmprojFileName = "Qwen3.5-0.8B.mmproj-q8_0.gguf",
            mmprojSizeBytes = 116_000_000L,
            mmprojUrls = listOf(
                "https://huggingface.co/bartowski/Qwen_Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B.mmproj-q8_0.gguf",
                "https://hf-mirror.com/bartowski/Qwen_Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B.mmproj-q8_0.gguf"
            )
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

    /**
     * v2.4.13：当前已加载模型是否具备**视觉能力**（mmproj 已加载且模型支持 vision）。
     *
     * ⚠️ 与「mmproj 文件已下载」是两件事：文件在 ≠ 加载成功
     *    （mmproj 与主模型不匹配时会加载失败，而纯文本仍可用）。
     */
    var visionAvailable by mutableStateOf(false)
        private set

    /** 最近一次错误（供 UI 显示真实原因，而不是笼统的"不可用"）。 */
    var lastError: String? = null
        private set

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

    /** v2.4.13：mmproj（视觉编码器）文件；模型没有配套 mmproj 时返回 null。 */
    fun mmprojFileOf(context: Context, model: LocalLlmModel): File? =
        model.mmprojFileName?.let { File(dir(context), it) }

    /**
     * mmproj 是否已就绪。
     *
     * ⚠️ 用**独立的**最小体积阈值（[MIN_MMPROJ_BYTES]）：mmproj 比主模型小得多
     *    （116~207MB vs 574MB），沿用主模型的 100MB 阈值会把 q8_0 那个档位误判。
     */
    fun isMmprojReady(context: Context, model: LocalLlmModel): Boolean {
        val f = mmprojFileOf(context, model) ?: return false
        return f.isFile && f.length() >= MIN_MMPROJ_BYTES
    }

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

    /**
     * 下载**任意模型文件**（主权重与 mmproj 共用这一份实现）。
     *
     * ## 为什么要参数化而不是各写一份
     * 「多源回退 + Range 续传 + 401 换源 + 进度上报 + 残片校验」这套逻辑有十几处易错点，
     * 复制成两份必然出现「主模型修了、mmproj 那份没修」——本项目「同一份逻辑两处登记」
     * 是头号事故源。所以只留这一个实现。
     *
     * @param expectBytes 期望体积（仅用于进度上限与日志，**不作为完成判据**）
     * @param minBytes    完成判据的最小体积（主模型 100MB / mmproj 50MB，两者不同）
     */
    private suspend fun downloadFileGeneric(
        context: Context,
        fileName: String,
        urls: List<String>,
        expectBytes: Long,
        minBytes: Long,
        label: String
    ): Boolean = withContext(Dispatchers.IO) {
        val dir = dir(context)
        val dest = File(dir, fileName)
        // 已经够大 → 视为已完成（避免重复下载）
        if (dest.isFile && dest.length() >= minBytes) return@withContext true

        if (!dir.exists() && !dir.mkdirs()) {
            lastError = "无法创建模型目录：${dir.absolutePath}"
            Log.e(TAG, lastError!!)
            return@withContext false
        }
        val tmp = File(dir, fileName + ".part")

        withContext(Dispatchers.Main) {
            isDownloading = true
            downloadProgress = 0f
            downloadStatus = "准备下载 $label…"
        }

        for (url in urls) {
            var attempt = 0
            while (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                attempt++
                try {
                    val existing = if (tmp.exists()) tmp.length() else 0L
                    val req = Request.Builder().url(url).apply {
                        if (existing > 0) addHeader("Range", "bytes=$existing-")
                        addHeader("User-Agent", DOWNLOAD_UA)
                    }.build()
                    var ok = false
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.code != 200 && resp.code != 206) {
                            Log.w(TAG, "$label 下载 HTTP ${resp.code}（$url）")
                            // 401/403 常见于镜像需要鉴权、404 是路径不对
                            // → 都是重试同一个源没有意义的错误，直接换源
                            if (resp.code == 401 || resp.code == 403 || resp.code == 404) {
                                attempt = MAX_DOWNLOAD_ATTEMPTS
                            }
                            return@use
                        }
                        val body = resp.body ?: return@use
                        val total = body.contentLength().let { if (it > 0) it + existing else expectBytes }
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
                                            downloadStatus = "$label ${written / 1048576}MB / ${total / 1048576}MB"
                                        }
                                    }
                                }
                            }
                        }
                        ok = true
                    }
                    if (ok && tmp.length() >= minBytes) {
                        if (dest.exists()) dest.delete()
                        if (tmp.renameTo(dest)) {
                            Log.i(TAG, "$label 下载完成：${dest.absolutePath}（${dest.length() / 1048576}MB）")
                            return@withContext true
                        }
                    }
                    Log.w(TAG, "$label 下载不完整（${tmp.length()} 字节，第 $attempt 次）")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "$label 下载异常（第 $attempt 次，$url）：${e.message}")
                    delay(1500L * attempt)
                }
            }
            Log.w(TAG, "$label 该源失败，尝试下一个：$url")
        }
        lastError = "$label 所有下载源均失败（约 ${expectBytes / 1048576}MB，请检查网络）"
        false
    }

    /** 下载主权重（带最小体积校验，避免把下载中断的残片当成可用）。 */
    /**
     * 下载**视觉编码器**（mmproj）—— v2.4.13。
     *
     * ⚠️ 与主权重分开下载（116MB vs 574MB）：用户可能只想先试纯文本，
     *    不该强制他一次下完 690MB。
     * ⚠️ 下载完成后**需要重新加载模型**才会生效（mmproj 是在 init 时加载的）——
     *    调用方下完应调 `release()` 让下次 `ensureLoaded` 重新初始化。
     */
    fun startMmprojDownload(
        context: Context,
        model: LocalLlmModel,
        onDone: (Boolean) -> Unit = {}
    ) {
        val fileName = model.mmprojFileName ?: run {
            Log.w(TAG, "该模型没有配套 mmproj，忽略下载请求")
            onDone(false)
            return
        }
        if (isDownloading) return
        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            val ok = downloadFileGeneric(
                context = context,
                fileName = fileName,
                urls = model.mmprojUrls,
                expectBytes = model.mmprojSizeBytes,
                minBytes = MIN_MMPROJ_BYTES,
                label = "视觉编码器"
            )
            withContext(Dispatchers.Main) {
                isDownloading = false
                downloadProgress = if (ok) 1f else 0f
                onDone(ok)
            }
        }
    }

    private suspend fun downloadInternal(context: Context, model: LocalLlmModel): Boolean {
        if (isReady(context, model)) return true
        return downloadFileGeneric(
            context = context,
            fileName = model.fileName,
            urls = model.urls,
            expectBytes = model.sizeBytes,
            minBytes = MIN_VALID_BYTES,
            label = model.displayName
        )
    }

    // ===================== 加载 / 释放 =====================

    /**
     * 确保模型已加载（同一个 id 已加载则直接复用）。
     *
     * v2.4.13：改用**自建**的 [LlamaMtmd]（含 libmtmd → 支持图像输入）。
     *
     * ⚠️ 与旧的 `dev.ffmpegkit.llama.Llama` **不可并存**：两者各自持有独立的
     *    native 模型实例，同时加载会让 0.6GB 的权重占两份内存。
     *
     * ## mmproj 的处理
     * 已下载就一起加载（拿到视觉能力）；没下载则传空串走纯文本。
     * ⚠️ mmproj 加载失败**不会**让整体失败 —— 纯文本仍可用（见 native 侧实现）。
     *
     * @return 是否就绪（false 时调用方应回落云端引擎）
     */
    suspend fun ensureLoaded(context: Context, model: LocalLlmModel): Boolean {
        if (!LlamaMtmd.available) {
            lastError = "本地推理库未加载（libauravr.so 缺失？）"
            return false
        }
        if (loadedModelId == model.id) return true
        if (!isReady(context, model)) {
            lastError = "模型未下载"
            return false
        }
        release()
        return withContext(Dispatchers.IO) {
            val mmprojPath = if (isMmprojReady(context, model)) {
                mmprojFileOf(context, model)?.absolutePath.orEmpty()
            } else {
                ""
            }
            val ok = LlamaMtmd.nativeInit(
                modelPath = fileOf(context, model).absolutePath,
                mmprojPath = mmprojPath,
                nCtx = model.contextSize,
                nThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            )
            if (ok) {
                loadedModelId = model.id
                visionAvailable = LlamaMtmd.nativeHasVision()
                lastError = null
                Log.i(TAG, "模型已加载：${model.displayName}（ctx=${model.contextSize}，" +
                    "vision=$visionAvailable，mmproj=${mmprojPath.ifEmpty { "无" }}）")
            } else {
                lastError = "模型加载失败（文件损坏或内存不足）"
                Log.e(TAG, "模型加载失败：${model.displayName}")
            }
            ok
        }
    }

    /**
     * 释放模型（切换功能 / 退出时调用）。
     *
     * v2.4.13：改为释放**自建引擎**的全局实例（模型 + mtmd 上下文一起释放）。
     */
    fun release() {
        try {
            if (LlamaMtmd.available) LlamaMtmd.nativeFree()
        } catch (t: Throwable) {
            Log.w(TAG, "释放模型异常：${t.message}")
        }
        loadedModelId = null
        visionAvailable = false
    }

    // ===================== 推理 =====================

    /**
     * 单轮补全（**无对话历史** —— 翻译与弹幕都是一次性任务，不需要多轮）。
     *
     * v2.4.13：新增**可选图像输入**（走自建 libmtmd）。
     *
     * @param rgb **RGB，3 字节/像素**（mtmd 的格式要求 —— **不是** ARGB）；
     *            传 null 或尺寸非法时自动走纯文本路径
     * @return 生成文本；未加载 / 失败返回 null（由调用方回落云端引擎）
     */
    suspend fun complete(
        prompt: String,
        systemPrompt: String? = null,
        rgb: ByteArray? = null,
        imgW: Int = 0,
        imgH: Int = 0,
        maxTokens: Int = 256,
        temperature: Float = 0.3f
    ): String? = withContext(Dispatchers.IO) {
        if (loadedModelId == null) {
            Log.w(TAG, "complete 被调用但模型未加载")
            return@withContext null
        }
        val t0 = System.currentTimeMillis()
        val text = LlamaMtmd.completeSafe(
            prompt = prompt,
            system = systemPrompt ?: "",
            rgb = rgb,
            imgW = imgW,
            imgH = imgH,
            maxTokens = maxTokens,
            temperature = temperature
        )
        val ms = System.currentTimeMillis() - t0
        if (text.isBlank()) {
            Log.w(TAG, "推理返回空（${ms}ms，图=${rgb?.size ?: 0}B）")
            null
        } else {
            Log.d(TAG, "推理完成 ${ms}ms / ${text.length} 字（${if (rgb != null) "含图" else "纯文本"}）")
            text
        }
    }
}
