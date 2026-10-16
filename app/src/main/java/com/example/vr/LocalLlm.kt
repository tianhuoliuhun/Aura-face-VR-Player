package com.example.vr

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.Executors
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

    /**
     * KV cache 的经验系数：**每 1024 个上下文 token 约需 96MB**（Qwen3.5-0.8B 量级）。
     *
     * 用于加载前的内存预检。系数刻意保守 —— 宁可高估也不能低估，
     * 否则预检形同虚设（低估 → 放过 → native 分配失败 → abort → 闪退）。
     */
    private const val KV_MB_PER_1K_CTX = 96L

    /** 下载重试次数。 */
    private const val MAX_DOWNLOAD_ATTEMPTS = 3

    /**
     * 单次推理总超时（毫秒）。v2.4.23 审查 #1：本地推理在 CPU 上可达数十秒，
     * 一旦卡住（高负载 / 内存抖动 / 采样异常）没有任何取消手段会让后续全部排队挂死。
     * 与 native `sampleLoop` 的 180s 看门狗对齐；超时后标记「引擎不健康」，下次推理前重建。
     */
    private const val INFERENCE_TIMEOUT_MS = 180_000L

    /**
     * 下载用的 User-Agent。
     *
     * ⚠️ 必须带：Hugging Face 对**空 UA / 脚本式 UA** 会返回 403，
     * 而 403 会被 `downloadFileGeneric` 判为「该源不可用」直接换源 ——
     * 表现为「主源莫名其妙总是失败」。
     */
    private const val DOWNLOAD_UA = "Mozilla/5.0 (Android) AuraFaceVRPlayer"

    // v2.4.36 审查 #12：模型下载 URL **锁定到具体 commit hash**，不再用 `resolve/main`。
    // 上游 main 一旦更新同名文件（或主模型与 mmproj 不配套），`resolve/main` 会静默换成新内容；
    // 锁到 commit 可保证「下载即确定」，与已落盘的模型完全可复现。
    // v2.4.59：主模型换为 MiniCPM-V 4.7 1B（openbmb 官方 GGUF，Apache 2.0）。
    // 文件名与 commit 均经 HF 仓库目录页核实；下载 URL 锁定到该 commit（与原 Qwen 做法一致）。
    const val MODEL_ID_MINICPMV_47_1B = "minicpmv-4.7-1b-q4km"
    private const val MODEL_REPO_MINICPM = "openbmb/MiniCPM-V-4.7-1B-gguf"
    private const val MODEL_REPO_MINICPM_COMMIT = "6ceac0238408005c4a1bbc779d9beb235ba71c57"

    /**
     * 已登记的本地模型。
     *
     * ⚠️ 只登记**一个**（与 [LocalModelSection] 的单模型界面一致；`MODELS.firstOrNull()` 是它的前提）。
     *    v2.4.59 由 Qwen3.5 0.8B 替换为 MiniCPM-V 4.7 1B。若将来要支持多个，需先把 UI 改成模型选择器。
     */
    val MODELS: List<LocalLlmModel> = listOf(
        LocalLlmModel(
            id = MODEL_ID_MINICPMV_47_1B,
            displayName = "MiniCPM-V 4.7 1B（Q4_K_M）",
            fileName = "MiniCPM-V-4_7-1B-Q4_K_M.gguf",
            sizeBytes = 672_000_000L,
            urls = listOf(
                // 主源：Hugging Face 官方 openbmb 仓库（锁定 commit）
                "https://huggingface.co/$MODEL_REPO_MINICPM/resolve/$MODEL_REPO_MINICPM_COMMIT/MiniCPM-V-4_7-1B-Q4_K_M.gguf",
                // 兜底：镜像（允许失败）
                "https://hf-mirror.com/$MODEL_REPO_MINICPM/resolve/$MODEL_REPO_MINICPM_COMMIT/MiniCPM-V-4_7-1B-Q4_K_M.gguf"
            ),
            // 上下文保守取 2048（沿用原值）：该模型的 KV cache 参数未核实，
            // 真机上需实测后再调整；estimateNeedMb 中的 KV 系数是按 Qwen 量级估的。
            contextSize = 2048,
            description = "OpenBMB 发布的 MiniCPM-V 4.7 1B（Apache 2.0，约 1.55B 参数，支持图像/视频理解）。" +
                "Q4_K_M 量化约 672MB，纯 CPU 推理。",
            // 视觉编码器：官方仓库 mmproj-model-f16.gguf（1.11GB，体积较大，**可选**）。
            // ⚠️ 不下载则纯文本可用；mmproj 与主权重的配套性未在真机验证。
            mmprojFileName = "mmproj-model-f16.gguf",
            mmprojSizeBytes = 1_110_000_000L,
            mmprojUrls = listOf(
                "https://huggingface.co/$MODEL_REPO_MINICPM/resolve/$MODEL_REPO_MINICPM_COMMIT/mmproj-model-f16.gguf",
                "https://hf-mirror.com/$MODEL_REPO_MINICPM/resolve/$MODEL_REPO_MINICPM_COMMIT/mmproj-model-f16.gguf"
            )
        )
    )

    // ===================== 线程模型（v2.4.14） =====================

    /**
     * **推理专用单线程调度器**。
     *
     * ⚠️ 原先推理直接跑在 `Dispatchers.IO` 上 —— 它默认 **64 并发**。
     *    而底层是**同一份 native 全局状态**（一个 ctx）：并发调用要么崩
     *    （v2.4.13 的 native 实现完全无锁），要么全部堆在锁上白占几十个线程。
     *    用单线程把排队**提前到 Kotlin 侧**：资源占用可控，也有明确的先来后到。
     *
     * ⚠️ 用 `Executors.newSingleThreadExecutor` 而不是 `limitedParallelism(1)`：
     *    后者在协程版本的实验/稳定边界上变过，前者永远稳定。
     * ⚠️ 线程带名字 —— 便于在 logcat / tombstone 里一眼认出是哪条线程崩的。
     */
    private val inferenceExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "local-llm-inference")
    }
    private val inferenceDispatcher = inferenceExecutor.asCoroutineDispatcher()

    // ===================== 推理优先级队列（v2.4.36 审查 #2） =====================
    /**
     * 本地推理**优先级**（数字越大越先执行）：
     * 当前播放点附近的**实时字幕翻译** > **弹幕** > **远期预翻译**。
     *
     * 单条推理在 CPU 上要几十秒，三者共用一个单线程引擎；没有优先级时，
     * 弹幕/预翻译会排在实时字幕前面，用户看到「字幕一直不出来」。
     *
     * ⚠️ 这里只做**排队排序**，不抢占正在执行的推理（native 采样无法被取消）；
     *   排序保证「实时字幕」在队列里插到最前，等当前那条跑完就轮到它。
     */
    const val PRIORITY_SUBTITLE_LIVE = 3
    const val PRIORITY_DANMU = 2
    const val PRIORITY_SUBTITLE_PRETRANSLATE = 1

    /**
     * 一个待执行的推理任务。实现 [Comparable] 以便 [PriorityBlockingQueue] 按
     * 「优先级降序、同优先级按入队顺序（seq 升序）」排序 —— 高优先级先出队、同优先级不饿死。
     */
    private class InferenceTask(
        val priority: Int,
        val seq: Long,
        val deferred: CompletableDeferred<String?>,
        val block: suspend () -> String?
    ) : Comparable<InferenceTask> {
        override fun compareTo(other: InferenceTask): Int {
            val p = other.priority.compareTo(priority)
            if (p != 0) return p
            return seq.compareTo(other.seq)
        }
    }

    /** 待执行任务的优先级队列（单 worker 串行消费，天然就是「先来后到 + 优先级」）。 */
    private val inferenceQueue = PriorityBlockingQueue<InferenceTask>()
    private val inferenceSeq = AtomicLong(0)
    private val inferenceWorkerStarted = AtomicBoolean(false)
    private val inferenceScheduler = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 懒启动唯一的消费协程：从队列取最高优先级任务、执行、完成其 deferred。 */
    private fun ensureInferenceWorker() {
        if (inferenceWorkerStarted.compareAndSet(false, true)) {
            inferenceScheduler.launch {
                while (true) {
                    val task = inferenceQueue.take() // 阻塞直到有任务
                    try {
                        task.deferred.complete(task.block())
                    } catch (e: Throwable) {
                        task.deferred.completeExceptionally(e)
                    }
                }
            }
        }
    }

    /**
     * 引擎**生命周期锁**：串行化 [ensureLoaded] 与 [release]。
     *
     * ⚠️ native 侧那把锁只保证**单次调用**的原子性；
     *    「先 release 再 nativeInit」这种**跨调用组合**必须在这里串行化，
     *    否则两个协程同时加载 → 白占两份 ~1GB 内存（必然 OOM）。
     *
     * ⚠️ 加锁顺序固定为 `engineLock → inferenceDispatcher`，
     *    故 [release] **不能**跑在 inferenceDispatcher 上（会反向等待 → 死锁）。
     */
    private val engineLock = Mutex()

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

    // ===================== 推理状态（v2.4.16） =====================

    /**
     * 是否**正在推理**。
     *
     * ⚠️ 为什么必须暴露这个状态：本地推理跑在 **CPU** 上（0.8B 模型），
     *    单条实测要**几十秒**（模拟器转译下更慢）。而 UI 此前**没有任何反馈** ——
     *    用户点开本地翻译后只看到「一直没译文」，很容易判定成「功能坏了 / 无法执行」。
     *    （实测日志：单次推理 57~115 秒。）
     */
    var isInferencing by mutableStateOf(false)
        private set

    /**
     * 是否**正在排队**（已进入 [complete] 但尚未抢到单线程推理调度器）。
     *
     * v2.4.35（审查 #3）：与 [isInferencing] 区分「排队中」与「推理中」。
     * 此前 [isInferencing] 在进入 [complete] 时就（抢到线程之前）置 true，
     * 导致 UI 把「在等别人」也显示成「推理中」，用户无法分辨「慢」与「卡在队列」。
     * 现在：进入即 `isQueued=true`；真正开始执行（进入推理调度器）时才置 `isInferencing=true`。
     */
    var isQueued by mutableStateOf(false)
        private set

    /**
     * 引擎健康标记（v2.4.23 审查 #1）。推理超时后无法取消正在执行的 native 采样，
     * 只能标记本字段；下次 [ensureLoaded] 看到它为真时先释放再重建引擎，避免把卡死状态
     * 带入下一次推理。
     */
    @Volatile private var engineUnhealthy = false

    /**
     * 最近一次推理耗时（毫秒）。
     *
     * 供 UI 显示「上次 12.3s」这类信息 —— 让用户能判断"慢"是正常的还是异常了。
     * 0 表示还没跑过。
     */
    var lastInferenceMs by mutableLongStateOf(0L)
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

    /** 模型是否已就绪（体积 + GGUF 魔数双重校验，避免把下载中断的残片当成可用）。 */
    fun isReady(context: Context, model: LocalLlmModel): Boolean {
        val f = fileOf(context, model)
        return f.isFile && f.length() >= MIN_VALID_BYTES && hasGgufMagic(f)
    }

    /** 文件头 4 字节是否为 GGUF 魔数。v2.4.23 审查 #10。 */
    private fun hasGgufMagic(f: File): Boolean {
        if (!f.isFile || f.length() < 4) return false
        return try {
            RandomAccessFile(f, "r").use { raf ->
                val magic = ByteArray(4)
                raf.read(magic) == 4 &&
                    magic[0] == 'G'.code.toByte() && magic[1] == 'G'.code.toByte() &&
                    magic[2] == 'U'.code.toByte() && magic[3] == 'F'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * v2.4.23 审查 #10：下载完成后的完整性校验。
     * - 已知期望大小时，written 必须达到期望大小（服务器提前关闭连接会少字节）；
     * - 检查 GGUF 魔数，避免把截断/错误文件当成可用模型。
     * 主权重与 mmproj 均为 GGUF 格式，魔数校验对两者通用。
     */
    private fun verifyDownloadedFile(file: File, expectedFull: Long, minBytes: Long): Boolean {
        val len = file.length()
        if (len < minBytes) return false
        // 期望大小已知且大于最小阈值时，必须达到期望大小（容忍 ≤1MB 的内容长度误差）。
        if (expectedFull > minBytes && len < expectedFull - 1_048_576L) return false
        return hasGgufMagic(file)
    }

    /** 已下载的模型（没有则 null）。 */
    fun readyModel(context: Context): LocalLlmModel? = MODELS.firstOrNull { isReady(context, it) }

    /**
     * v2.4.59：清理已被替换的旧模型（Qwen3.5 0.8B）的文件。
     *
     * 旧文件已不再被任何代码识别，留着只会永久占用约 0.8GB 存储。
     * 与 [deleteModel] 一样持 [engineLock]，避免与加载并发。
     */
    fun deleteLegacyModelFiles(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            engineLock.withLock {
                LEGACY_FILE_NAMES.forEach { name ->
                    runCatching { File(dir(context), name).delete() }
                }
            }
        }
    }

    private val LEGACY_FILE_NAMES = listOf(
        "Qwen_Qwen3.5-0.8B-Q4_K_M.gguf",
        "Qwen_Qwen3.5-0.8B-Q4_K_M.gguf.part",
        "mmproj-Qwen_Qwen3.5-0.8B-f16.gguf",
        "mmproj-Qwen_Qwen3.5-0.8B-f16.gguf.part"
    )

    /** 已下载体积（MB），供 UI 显示。 */
    fun downloadedMb(context: Context, model: LocalLlmModel): Long =
        fileOf(context, model).let { if (it.isFile) it.length() / 1048576 else 0L }

    // ===================== 下载 =====================

    fun cancelDownload() {
        val job = downloadJob
        downloadJob = null
        if (job == null) {
            isDownloading = false
            downloadProgress = 0f
            downloadStatus = ""
            return
        }
        // v2.4.23 审查 #13：取消时等待旧 job 真正退出再清状态，期间保留 isDownloading=true，
        // 避免新下载在旧 job 仍阻塞读流时并发写同一个 .part。
        CoroutineScope(Dispatchers.IO).launch {
            job.cancel()
            job.join()
            withContext(Dispatchers.Main) {
                isDownloading = false
                downloadProgress = 0f
                downloadStatus = ""
            }
        }
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
        // v2.4.23 审查 #13：启动前**同步**置位，堵住「双击 → 两个协程同写 .part」的竞态窗口
        // （原代码 isDownloading 只在 downloadFileGeneric 内部才置 true，存在竞态）。
        isDownloading = true
        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val ok = downloadInternal(context, model)
                withContext(Dispatchers.Main) {
                    downloadProgress = if (ok) 1f else 0f
                    onDone(ok)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isDownloading = false
                    downloadJob = null
                }
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
        // 已经够大且魔数正确 → 视为已完成（避免重复下载；损坏文件会被 isReady 判否并触发重下）
        if (dest.isFile && dest.length() >= minBytes && hasGgufMagic(dest)) return@withContext true

        if (!dir.exists() && !dir.mkdirs()) {
            lastError = "无法创建模型目录：${dir.absolutePath}"
            Log.e(TAG, lastError!!)
            return@withContext false
        }
        val tmp = File(dir, fileName + ".part")
        // v2.4.23 审查 #10：完整文件期望大小（跨 attempt 保留，供完成判据使用）
        var expectedFull = expectBytes

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
                        // v2.4.23 审查 #11：416（Range 不可满足）说明 .part 已 ≥ 真实大小（损坏/过大），
                        // 清除残片后 return，下一轮 attempt 重新计算 existing=0 并从头下载。
                        if (resp.code == 416) {
                            Log.w(TAG, "$label 收到 416，清除 .part 并从头重下（$url）")
                            tmp.delete()
                            return@use
                        }
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
                        val contentLen = body.contentLength()
                        // v2.4.23 审查 #10：算出**完整文件**期望大小（用于完成判据，而非仅进度上限）。
                        //   200 → contentLen（若已知）；206 → contentLen + 已下字节；未知则退回 expectBytes。
                        expectedFull = if (contentLen > 0) {
                            if (resp.code == 206) contentLen + existing else contentLen
                        } else {
                            expectBytes
                        }
                        val total = expectedFull
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
                    if (ok) {
                        // v2.4.23 审查 #10：完整性 + GGUF 魔数校验，通过才改名为最终文件。
                        if (verifyDownloadedFile(tmp, expectedFull, minBytes)) {
                            if (dest.exists()) dest.delete()
                            if (tmp.renameTo(dest)) {
                                Log.i(TAG, "$label 下载完成：${dest.absolutePath}（${dest.length() / 1048576}MB）")
                                return@withContext true
                            }
                        } else {
                            Log.w(
                                TAG,
                                "$label 下载校验失败（${tmp.length()} 字节 / 期望 ${expectBytes} 字节，第 $attempt 次），保留 .part 重试"
                            )
                        }
                    } else {
                        Log.w(TAG, "$label 下载不完整（${tmp.length()} 字节，第 $attempt 次）")
                    }
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
        // v2.4.23 审查 #13：与 startDownload 一致，启动前同步置位，堵双击竞态。
        isDownloading = true
        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val ok = downloadFileGeneric(
                    context = context,
                    fileName = fileName,
                    urls = model.mmprojUrls,
                    expectBytes = model.mmprojSizeBytes,
                    minBytes = MIN_MMPROJ_BYTES,
                    label = "视觉编码器"
                )
                withContext(Dispatchers.Main) {
                    downloadProgress = if (ok) 1f else 0f
                    onDone(ok)
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isDownloading = false
                    downloadJob = null
                }
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
        // ⚠️ v2.4.14：用**生命周期锁**串行化整个「释放 + 加载」组合。
        //    两个协程同时进来会各自加载一份 ~1GB 的引擎（必然 OOM）。
        return engineLock.withLock {
            // 双重检查：等锁期间可能已被别的协程加载好了
            if (loadedModelId == model.id) return@withLock true

            // v2.4.23 审查 #1：上轮推理超时 → 标记不健康。此处先释放再重建引擎，
            // 把卡死状态清掉。nativeFree 与卡住的采样都持同一把 g_mutex，会自然串行，
            // 不会并发访问全局 ctx（配合 native 侧 180s 看门狗，卡死调用最迟 180s 内交还）。
            if (engineUnhealthy) {
                Log.w(TAG, "引擎被标记不健康（上轮推理超时），先释放再重建")
                releaseInternal()
                engineUnhealthy = false
            }

            // 🔴 加载前**内存预检**（v2.4.14）
            //    为什么必须做：llama.cpp 在权重 / KV cache 分配失败时会调 abort()
            //    → **SIGABRT**。那是 native 崩溃，Java 的 catch (Throwable)
            //    **完全抓不到** —— 用户看到的就是「点一下本地模型就闪退」，
            //    既没有堆栈也没有提示。所以宁可在这里明确报「内存不足」。
            val needMb = estimateNeedMb(context, model)
            val availMb = availableMemMb(context)
            Log.i(
                TAG,
                "内存预检：需约 ${needMb}MB，系统可用约 " +
                    (if (availMb > 0) "${availMb}MB" else "未知")
            )
            if (availMb > 0 && availMb < needMb * 5 / 4) {
                lastError = "可用内存不足（需约 ${needMb}MB，当前可用约 ${availMb}MB）—— " +
                    "请先关闭其它应用再试，或改回云端引擎"
                Log.e(TAG, lastError!!)
                return@withLock false
            }

            // v2.4.35（审查 #15）：等锁期间模型文件可能被并发删除，加载前再确认一次；
            //    删除路径（deleteModel）同样持 engineLock，与这里互斥，故该检查可靠。
            if (!isReady(context, model)) {
                lastError = "模型文件已被删除（可能被并发删除），请重新下载"
                Log.e(TAG, lastError!!)
                return@withLock false
            }

            // ⚠️ 用内部版而不是 release()：后者会另起协程，在持锁状态下调它会绕开本锁
            releaseInternal()

            // ⚠️ 加载放到**单线程推理调度器**上：与后续推理排同一队，
            //    保证「加载完成之后才可能开始推理」，也避免加载期间被别处插进 native 调用。
            withContext(inferenceDispatcher) {
                val mmprojPath = if (isMmprojReady(context, model)) {
                    mmprojFileOf(context, model)?.absolutePath.orEmpty()
                } else {
                    ""
                }
                // ⚠️ nThreads 传 **0 = 自动**：真正的核数探测与钳位在 native 侧完成
                //    （折叠超线程、钳到 [1,8]），避免「同一份约束两处登记」+ 模拟器误报宿主机核数。
                //    >0 仅作调试覆盖用。
                val ok = LlamaMtmd.nativeInit(
                    modelPath = fileOf(context, model).absolutePath,
                    mmprojPath = mmprojPath,
                    nCtx = model.contextSize,
                    nThreads = 0
                )
                if (ok) {
                    loadedModelId = model.id
                    visionAvailable = LlamaMtmd.nativeHasVision()
                    lastError = null
                    Log.i(
                        TAG,
                        "模型已加载：${model.displayName}（ctx=${model.contextSize}，" +
                            "nThreads=0(自动探测物理核)，vision=$visionAvailable，" +
                            "mmproj=${mmprojPath.ifEmpty { "无" }}）"
                    )
                } else {
                    lastError = "模型加载失败（文件损坏或内存不足）"
                    Log.e(TAG, "模型加载失败：${model.displayName}")
                }
                ok
            }
        }
    }

    /**
     * 释放模型（切换功能 / 退出时调用）。
     *
     * v2.4.13：改为释放**自建引擎**的全局实例（模型 + mtmd 上下文一起释放）。
     */
    fun release() {
        // ⚠️ v2.4.14：改为**后台执行** —— 释放 574MB 权重 + KV cache 要几百毫秒，
        //    而 UI 的三个「删除 / 重下 mmproj」按钮都在主线程直接调它，
        //    同步执行会阻塞主线程（VR 里表现为掉帧，严重时 ANR）。
        //
        // ⚠️ 跑在 `Dispatchers.IO` 而**不是** inferenceDispatcher：
        //    加锁顺序固定为 engineLock → inferenceDispatcher；
        //    这里若反过来先占 inferenceDispatcher 再要 engineLock，
        //    而此刻 ensureLoaded 正持 engineLock 在等 inferenceDispatcher → **死锁**。
        //
        // ⚠️ 与「删除模型文件」并发是安全的：llama.cpp 用 mmap 加载，
        //    Linux 下 unlink 会保留 inode 直到最后一个引用释放。
        CoroutineScope(Dispatchers.IO).launch {
            engineLock.withLock { releaseInternal() }
        }
    }

    /**
     * v2.4.35（审查 #15）：删除模型文件（含 mmproj）。
     *
     * ⚠️ 持 [engineLock] 执行「释放 + 删除」组合，与 [ensureLoaded] 的加载互斥，
     *    避免「删除与加载并发」—— 否则 [ensureLoaded] 可能读到已被删除的文件、
     *    nativeInit 失败并误报「文件损坏或内存不足」，误导用户。
     *    （此前的 `release()` 只在锁内释放引擎，文件删除发生在锁外，故存在竞态。）
     */
    fun deleteModel(context: Context, model: LocalLlmModel) {
        CoroutineScope(Dispatchers.IO).launch {
            engineLock.withLock {
                releaseInternal()
                runCatching { fileOf(context, model).delete() }
                runCatching { mmprojFileOf(context, model)?.delete() }
            }
        }
    }

    /** ⚠️ **内部**释放：不做排队，供已持有 [engineLock] 的调用方使用。 */
    private fun releaseInternal() {
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
        temperature: Float = 0.3f,
        // v2.4.36 审查 #2：推理优先级（实时字幕 > 弹幕 > 远期预翻译）。
        // 默认给弹幕；字幕翻译链路显式传更高/更低优先级。
        priority: Int = PRIORITY_DANMU
    ): String? {
        if (loadedModelId == null) {
            Log.w(TAG, "complete 被调用但模型未加载")
            // v2.4.35（审查 #9）：模型未加载也要暴露原因，而不是让调用方只看到空结果
            lastError = "模型未加载，无法执行本地推理（请先下载并加载模型）"
            return null
        }
        // v2.4.35（#3）：进入即标记「排队中」—— 此刻已入优先级队列、但还没轮到执行，
        //    真正开始执行（worker 取出本任务）时才置 isInferencing（running）。
        //    UI 据此区分「慢（推理中）」与「在等别人（排队中）」。
        isQueued = true
        try {
            var blankOutput = false
            val task = InferenceTask(
                priority = priority,
                seq = inferenceSeq.incrementAndGet(),
                deferred = CompletableDeferred(),
                block = {
                    // 被 worker 取出 → 离开排队、进入真正推理
                    isQueued = false
                    isInferencing = true
                    try {
                        // ===== v2.4.36 审查 #4 + #6 =====
                        // #4：拼成 ChatML 角色格式（system/user/assistant），替代 native 里的纯文本拼接，
                        //     提升指令遵从、抑制 Qwen3 系列 <think> 段泄漏（解析层已剥 #5）。
                        // #6：persona/风格提示词没有长度限制，易超 2048 token 的 n_ctx；
                        //     按粗略 token 估算截断 user 的前部（保留尾部台词/格式指令），并提示「提示词过长」。
                        val systemText = systemPrompt ?: ""
                        val userText = truncatePromptIfNeeded(prompt, systemText, maxTokens)
                        val chatml = buildChatML(systemText, userText)
                        // 把 chatml 放进 system 字段、prompt 留空：native 的 `system + "\n\n" + prompt`
                        // 会变成 `chatml + "\n\n"`，恰好是 assistant 角色前应有的换行（无需改 native）。
                        val t0 = System.currentTimeMillis()
                        val r = LlamaMtmd.completeSafe(
                            prompt = "",
                            system = chatml,
                            rgb = rgb,
                            imgW = imgW,
                            imgH = imgH,
                            maxTokens = maxTokens,
                            temperature = temperature
                        )
                        val ms = System.currentTimeMillis() - t0
                        lastInferenceMs = ms
                        if (r.isBlank()) {
                            // v2.4.35（审查 #9）：空输出也要暴露原因，而不是塌缩成空结果
                            Log.w(TAG, "推理返回空（${ms}ms，图=${rgb?.size ?: 0}B）")
                            lastError = "本地推理返回空（模型未产出内容，可能 prompt 过长或模板不匹配）"
                            blankOutput = true
                            null
                        } else {
                            Log.d(TAG, "推理完成 ${ms}ms / ${r.length} 字（${if (rgb != null) "含图" else "纯文本"}）")
                            r
                        }
                    } finally {
                        isInferencing = false
                    }
                }
            )
            inferenceQueue.put(task)
            ensureInferenceWorker()
            // v2.4.23 审查 #1：外裹总超时。超时 → 标记引擎不健康，下次推理前重建
            //    （native 采样无法被取消；worker 仍会把卡死的调用跑完，只是上层不再等）。
            val text = withTimeoutOrNull(INFERENCE_TIMEOUT_MS) { task.deferred.await() }
            if (text == null) {
                if (blankOutput) {
                    // 空输出：lastError 已在上面设置，仅补日志
                    Log.e(TAG, lastError!!)
                } else {
                    // 超时：native 仍在跑但本协程已放弃。标记不健康，下次推理前重建。
                    engineUnhealthy = true
                    lastError = "本地推理超时（>${INFERENCE_TIMEOUT_MS / 1000}s），已标记引擎需重建"
                    Log.e(TAG, lastError!!)
                }
                return null
            }
            return text
        } finally {
            isQueued = false
        }
    }

    // ===================== 提示词构造（v2.4.36 审查 #4 / #6） =====================

    /**
     * v2.4.36 审查 #4：把 system + user 拼成 **ChatML** 角色格式（Qwen3.5 使用）。
     * 替代 native 里的 `system + "\n\n" + prompt` 纯文本拼接 —— 角色标记能显著提升指令遵从，
     * 也避免模型把「不要编号 / 不要引号 / 不要开场白」这类约束当成可选项。
     *
     * 在 user 角色开头用空 `<think>\n\n</think>` 抑制 Qwen3 系列思考段：
     * 若模型不支持思考则无害，解析层（审查 #5）也已剥 `<think>` 段兜底。
     */
    private fun buildChatML(system: String, user: String): String = buildString {
        append("<|im_start|>system\n")
        append(system)
        append("<|im_end|>\n")
        append("<|im_start|>user\n")
        append("<think>\n\n</think>\n\n")
        append(user)
        append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }

    /**
     * v2.4.36 审查 #6：persona/风格提示词没有长度限制，拼进 prompt 后很容易超过
     * n_ctx（2048 token）。这里做**粗略 token 估算**（1 码点 ≈ 1 token：CJK 准确，
     * ASCII 偏保守 → 更早截断，落在安全侧），若整段超出预算则**截断 user 的前部**、
     * 保留尾部（台词/字幕通常在尾部，人设/风格在前面），避免把正文切掉。
     * 截断发生时在 UI 提示「提示词过长」。
     */
    private fun truncatePromptIfNeeded(user: String, system: String, maxTokens: Int): String {
        val budget = currentContextSize() - maxTokens - 64
        if (budget <= 0) return user
        // 角色标记 + `<|im_start|>` 等固定开销约 80 token
        val overhead = system.codePointCount(0, system.length) + 80
        val allowedUser = (budget - overhead).coerceAtLeast(256)
        val userCp = user.codePointCount(0, user.length)
        if (userCp <= allowedUser) return user
        // 截断前部、保留尾部（尾部通常是真正要翻译/处理的台词）
        lastError = "提示词过长，已自动截断并保留台词（请缩短风格/人设描述）"
        Log.w(TAG, "prompt 超长（${userCp} 码点 > 预算 ${allowedUser}），截断前部")
        val drop = userCp - allowedUser
        return user.substring(user.offsetByCodePoints(0, drop))
    }

    /** 当前已加载模型的上下文长度（用于 #6 的预算估算）；未知时退回 2048。 */
    private fun currentContextSize(): Int {
        val id = loadedModelId ?: return 2048
        return MODELS.firstOrNull { it.id == id }?.contextSize ?: 2048
    }

    // ===================== 内存预检（v2.4.14） =====================

    /**
     * 估算加载该模型需要多少内存（MB）。
     *
     * ## 为什么必须预检
     * llama.cpp 在权重 / KV cache 分配失败时会调 abort() → **SIGABRT**。
     * 那是 native 崩溃，Java 的 catch (Throwable) **抓不到**，
     * 用户看到的就是「点一下本地模型就闪退」—— 没有堆栈、没有提示。
     * 所以在进 native 之前先算清楚。
     *
     * ## 口径（刻意保守：宁可高估）
     * - **权重**：文件体积 × 1.15（加载期还需一份临时缓冲）
     * - **KV cache**：[KV_MB_PER_1K_CTX] × (contextSize / 1024)
     * - **mmproj**：已下载则一并算上，另加 64MB 余量（它自身的激活 / 缓冲）
     */
    private fun estimateNeedMb(context: Context, model: LocalLlmModel): Long {
        val weights = model.sizeBytes / 1048576 * 115 / 100
        val kv = model.contextSize.toLong() / 1024L * KV_MB_PER_1K_CTX
        val mmproj = if (isMmprojReady(context, model)) model.mmprojSizeBytes / 1048576 + 64 else 0L
        return weights + kv + mmproj
    }

    /**
     * 系统当前可用内存（MB）；读不到时返回 **-1 表示「未知」**。
     *
     * ⚠️ 返回 -1 时调用方必须**跳过**判断 —— 不能因为读不到内存信息就拒绝加载。
     *    注意别用 0 表示未知：0 < need 恒成立 → 会变成「永远拒绝加载」。
     */
    private fun availableMemMb(context: Context): Long = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        info.availMem / 1048576
    } catch (t: Throwable) {
        Log.w(TAG, "读取可用内存失败：${t.message}")
        -1L
    }
}
