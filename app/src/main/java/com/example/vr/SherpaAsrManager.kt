package com.example.vr

import android.content.Context
import android.util.Log
import com.example.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
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

    /** 下载最多尝试次数（配合 Range 断点续传；大文件在弱网下首次失败很常见） */
    private const val MAX_DOWNLOAD_ATTEMPTS = 5

    /** 下载请求统一使用的 UA（个别镜像对默认 okhttp UA 的策略不同，用浏览器 UA 更稳） */
    private const val DOWNLOAD_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

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
    var downloadStatus by mutableStateOf("")

    /** 最近一次识别器初始化失败的原因；null 表示未失败（供 UI 显示真实原因而非"请先下载"） */
    var lastInitError: String? = null
        private set

    private var downloadJob: Job? = null
    /**
     * 下载用 HTTP 客户端。
     *
     * `readTimeout` 刻意取 **90 秒**：一旦读阻塞超过它就抛 SocketTimeoutException，
     * 从而让「连接挂死 / 被限速到零」能触发上层重试并按 **Range 续传**，
     * 而不是干等到 600 秒才发现卡住（大文件下载体验的关键）。
     */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    // ===== 模型路径 =====

    /** 下载版模型目录（filesDir） */
    private fun svcDir(context: Context): File =
        File(context.filesDir, "sherpa_models/$SVC_DIR_NAME")

    // =======================================================================
    // v2.1.208：**Dolphin 内置 assets**（模型随 APK 打包，替代原 SenseVoice 的内置地位）
    // =======================================================================
    //
    // 【为什么能直接用 assets】sherpa-onnx 的 Kotlin API 提供
    //   `OfflineRecognizer(assetManager, config)` 构造函数，
    // 此时 config 里的 model/tokens 传**assets 内的相对路径**即可，
    // 不需要先把文件复制到 filesDir（省掉一次 99MB 拷贝与额外存储占用）。
    //
    // 【与下载版的关系】优先级 = **内置 assets > filesDir 下载版**
    //   （下载版保留为兜底：assets 打包遗漏/损坏时仍可工作，也便于不发版换模型）
    private const val DOLPHIN_ASSET_DIR = "dolphin"
    private const val DOLPHIN_ASSET_MODEL = "$DOLPHIN_ASSET_DIR/model.int8.onnx"
    private const val DOLPHIN_ASSET_TOKENS = "$DOLPHIN_ASSET_DIR/tokens.txt"

    /** Dolphin 内置 assets 是否可用（损坏/遗漏时返回 false → 回退下载版） */
    fun dolphinAssetAvailable(context: Context): Boolean = try {
        context.assets.open(DOLPHIN_ASSET_MODEL).close()
        context.assets.open(DOLPHIN_ASSET_TOKENS).close()
        true
    } catch (e: Exception) {
        false
    }

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
        downloadedModelReady(context) -> context.getString(R.string.asr_source_downloaded)
        assetModelAvailable(context) -> context.getString(R.string.asr_source_assets)
        else -> context.getString(R.string.asr_source_unavailable)
    }

    /**
     * ASR 语言/模型 chip。
     *
     * ⚠️ v2.0.208 起**同一语言可以出现多条 chip**（每个候选模型一条），
     * chip 显示「语言名+序号」（如 英语1 / 英语2 / 英语3），
     * 点击 = 「切语言 + 选模型」一步完成。
     */
    data class SherpaLang(
        val code: String,             // 语言键（sherpa_lang_code）
        val labelResId: Int,
        /** 该 chip 绑定的模型 id：`builtin` / 扩展模型的 `dirName`；null = 不涉及选模型 */
        val modelId: String? = null,
        /** 显示后缀（同语言多 chip 时为 "1"/"2"…，单 chip 语言为 null） */
        val suffix: String? = null,
        val group: AsrExtModels.AsrLangGroup
    )

    /**
     * 可选语言 chip 列表 = SenseVoice 内置 + 扩展模型语言。
     *
     * v2.0.208：**重复语言分开标**（用户决策）——
     * 英语有 3 个候选（内置 / FastConformer 轻量 / Parakeet v3 全量）→ 3 条 chip；
     * 俄法德西等 8 种重叠语言 → 各 2 条 chip。
     * 点击 chip = `changeAsrLanguage(code)` + `setModelChoice(code, modelId)` 一步完成。
     */
    val sherpaLanguages: List<SherpaLang> = buildList {
        // —— 内置 SenseVoice 覆盖的语言 ——
        // 「自动」= **语种识别**：改用 **Dolphin**（自带 LID，覆盖 40 语 + 22 方言）。
        // 2026-10-02 用户要求「自动模型也改为这个」——
        // SenseVoice 的 auto 实际上只能在 中/英/日/韩/粤 里猜，而 Dolphin 能真正自动判定语种，
        // 语义上更贴合「自动」。
        add(SherpaLang("auto", R.string.asr_lang_auto, modelId = AsrExtModels.DOLPHIN_DIR, group = AsrExtModels.AsrLangGroup.COMMON))
        add(SherpaLang("zh", R.string.asr_lang_zh, modelId = AsrExtModels.DOLPHIN_DIR, group = AsrExtModels.AsrLangGroup.CHINESE))
        add(SherpaLang("ja", R.string.asr_lang_ja, modelId = AsrExtModels.DOLPHIN_DIR, group = AsrExtModels.AsrLangGroup.EAST_ASIA))
        add(SherpaLang("ko", R.string.asr_lang_ko, modelId = AsrExtModels.DOLPHIN_DIR, group = AsrExtModels.AsrLangGroup.EAST_ASIA))
        add(SherpaLang("yue", R.string.asr_lang_yue, modelId = AsrExtModels.DOLPHIN_DIR, group = AsrExtModels.AsrLangGroup.CHINESE))
        // —— 英语：3 个候选分开标（内置 / 轻量 / 全量），全部放「常用」组便于对比 ——
        // —— 英语：3 个候选分开标 ——
        //    「英语1（内置）」放常用组；「英语2/3」的模型是欧洲包（FC/V3），归入欧洲组，
        //    避免常用组里堆 3 个英语让首屏变乱（用户 2026-10-01 要求整理）。
        add(SherpaLang("en", R.string.asr_lang_en, modelId = AsrExtModels.DOLPHIN_DIR, suffix = "1", group = AsrExtModels.AsrLangGroup.COMMON))
        add(SherpaLang("en", R.string.asr_lang_en, modelId = AsrExtModels.FASTCONF_DIR, suffix = "2", group = AsrExtModels.AsrLangGroup.EUROPE))
        add(SherpaLang("en", R.string.asr_lang_en, modelId = AsrExtModels.PARAKEET_V3_DIR, suffix = "3", group = AsrExtModels.AsrLangGroup.EUROPE))
        // —— 其余扩展语言：按候选数生成 chip（多候选加序号，单候选无后缀）——
        //    en 已在上面手动处理，这里跳过；Dolphin 的 54 条与 vi/th/FastConformer 9 语在此生成。
        AsrExtModels.ALL
            .filter { it.key != "en" }
            .groupBy { it.key }
            .forEach { (key, models) ->
                val labelRes = models.first().labelResId
                val grp = AsrExtModels.LANG_OPTIONS.firstOrNull { it.key == key }?.group
                    ?: AsrExtModels.AsrLangGroup.OTHER
                models.forEachIndexed { i, m ->
                    add(
                        SherpaLang(
                            key, labelRes,
                            modelId = m.dirName,
                            suffix = if (models.size > 1) "${i + 1}" else null,
                            group = grp
                        )
                    )
                }
            }
    }

    /** 按语区收纳后的 chip 分组（供 UI 折叠渲染） */
    fun groupedChips(): List<Pair<AsrExtModels.AsrLangGroup, List<SherpaLang>>> {
        // ⚠️ 枚举不能起别名（`val G = AsrLangGroup` 会编译失败），一律写全限定名。
        val out = ArrayList<Pair<AsrExtModels.AsrLangGroup, List<SherpaLang>>>()
        // 「常用」组是**与其它语区重叠**的快捷入口：按**语言键**判定（而非 group 字段），
        // 这样中文/日语/韩语（group 是 CHINESE / EAST_ASIA）也能出现在常用组里
        // （用户 2026-10-02 要求），同时它们在原语区里仍然保留。
        val common = sherpaLanguages.filter { AsrExtModels.isCommonKey(it.code) }
        if (common.isNotEmpty()) out += AsrExtModels.AsrLangGroup.COMMON to common
        // 其余语区按 group 字段 + sortOrder
        sherpaLanguages.groupBy { it.group }.entries
            .filter { it.key != AsrExtModels.AsrLangGroup.COMMON }
            .sortedBy { it.key.sortOrder }
            .forEach { (g, list) -> if (list.isNotEmpty()) out += g to list }
        return out
    }

    /**
     * 按**模型**收纳：`(模型 id, 该模型覆盖的语言 chips)`。
     *
     * 「按模型」是「多模型对比」的入口：同一语言出现在多个模型下
     * （如英语 = builtin + FastConformer + Parakeet v3 三条 chip），
     * 用户从这里能直观看到每个模型覆盖哪些语言、各自多大体积。
     */
    fun groupedByModel(): List<Pair<String, List<SherpaLang>>> {
        val byId = LinkedHashMap<String, MutableList<SherpaLang>>()
        sherpaLanguages.forEach { chip ->
            chip.modelId?.let { id -> byId.getOrPut(id) { mutableListOf() } += chip }
        }
        return byId.map { (id, chips) -> id to chips }
    }

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

    fun cancelDownload(context: Context) {
        downloadJob?.cancel()
        downloadJob = null
        isModelDownloading = false
        modelDownloadProgress = 0f
        downloadStatus = context.getString(R.string.asr_canceled)
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
            downloadStatus = context.getString(R.string.asr_preparing_download, SVC_MODEL_MB)
        }

        // 模型占 99% 体积，词表瞬间完成，因此进度按 0.99 / 0.01 分配
        val modelOk = downloadFileWithResume(
            url = SVC_MODEL_URL,
            dest = dir.resolve(SVC_MODEL),
            progressBase = 0f,
            progressSpan = 0.99f,
            expectMinBytes = 100_000_000L,
            label = context.getString(R.string.asr_download_model)
        )
        if (!modelOk) {
            withContextMain {
                isModelDownloading = false
                downloadStatus = context.getString(R.string.asr_model_download_failed)
            }
            return@withContext null
        }
        val tokensOk = downloadFileWithResume(
            url = SVC_TOKENS_URL,
            dest = dir.resolve(SVC_TOKENS),
            progressBase = 0.99f,
            progressSpan = 0.01f,
            expectMinBytes = 1024L,
            label = context.getString(R.string.asr_vocab_label)
        )
        withContextMain {
            isModelDownloading = false
            modelDownloadProgress = 1f
            downloadStatus = if (tokensOk) context.getString(R.string.asr_model_ready) else context.getString(R.string.asr_vocab_download_failed)
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
        while (attempt < MAX_DOWNLOAD_ATTEMPTS) {
            attempt++
            try {
                val existing = if (dest.exists()) dest.length() else 0L
                val req = Request.Builder().url(url).apply {
                    if (existing > 0) addHeader("Range", "bytes=$existing-")
                    addHeader("User-Agent", DOWNLOAD_UA)
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
        // v2.0.145：扩展语言（越南语等）走各自独立的离线模型，与内置 SenseVoice 完全隔离，互不影响。
        // v2.0.208：**尊重用户在「选择模型」里的选择** ——
        //   解析结果为 null 时走内置 SenseVoice（用户选了内置、或该语言没有扩展候选）。
        resolveExtModel(context, language)?.let { ext ->
            return createExtRecognizer(context, ext, threads)
        }
        lastInitError = null
        if (!isModelReady(context)) {
            Log.w(TAG, "SenseVoice model not ready（内置缺失且无下载版）")
            lastInitError = context.getString(R.string.asr_model_unavailable_detail, SVC_MODEL_MB)
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
            lastInitError = context.getString(R.string.asr_init_failed, e.message ?: e.javaClass.simpleName)
            null
        }
    }

    // ===== 扩展语言模型（v2.0.145：越南语样板）=====
    //
    // 这些模型**不内置进 APK**，由用户按需下载到 filesDir/sherpa_models/ext-<dirName>。
    // 下载源与文件清单见 [AsrExtModels]（各模型文件名不统一，必须逐个写死）。

    /** 按目录名取落盘目录（`filesDir/sherpa_models/ext-<dirName>`） */
    private fun extSubDir(context: Context, dirName: String): File =
        File(context.filesDir, "sherpa_models/ext-$dirName")

    /** 扩展模型**自身**的落盘目录 */
    private fun extDir(context: Context, m: AsrExtModel): File = extSubDir(context, m.dirName)

    /**
     * 某个文件的落盘位置。
     *
     * ⚠️ 两点不能想当然（v2.0.208 引入 CTC 后踩到）：
     * 1. **落盘名取 basename** —— 远端可能带子目录（`hi/model.int8.onnx`），
     *    但那只是 URL 定位用，本地不该生成 `ext-indic-hi/hi/model.onnx` 这种嵌套。
     * 2. **落盘目录可能不是模型自己的目录** —— IndicConformer 的 22 语共用一份
     *    `tokens.txt`，它要落到共享目录 `ext-indic-shared/`，
     *    否则每种语言都要重下一份 67KB（危害不大但没意义）。
     */
    private fun fileOf(context: Context, m: AsrExtModel, f: AsrExtFile): File =
        File(extSubDir(context, f.dir ?: m.dirName), File(f.name).name)

    /**
     * 扩展模型的期望文件表：`File -> 期望最小字节数`。
     * 按文件下载用 [AsrExtModel.files]，整包回退用 [AsrExtArchive.wanted]（整包解出的文件都落在模型目录）。
     */
    private fun expectedFiles(context: Context, m: AsrExtModel): List<Pair<File, Long>> =
        if (m.files.isNotEmpty()) {
            m.files.map { fileOf(context, m, it) to it.minBytes }
        } else {
            val dir = extDir(context, m)
            (m.archive?.wanted ?: emptyMap()).map { (name, min) -> dir.resolve(name) to min }
        }

    /** 扩展模型是否就绪（逐文件尺寸校验，防中断下载的残缺文件被误判） */
    fun isExtModelReady(context: Context, m: AsrExtModel): Boolean {
        // v2.1.208：**Dolphin 已内置到 APK 的 assets** —— 只要 assets 完好即视为就绪，
        // 不再要求用户先下载（下载版仍保留为兜底，但不参与就绪判定，
        // 否则「明明能用却提示要下载」）。
        if (m.modelType == "dolphin" && dolphinAssetAvailable(context)) return true
        val expect = expectedFiles(context, m)
        return expect.isNotEmpty() && expect.all { (file, min) ->
            file.exists() && file.length() >= min
        }
    }

    /** 指定语言键的模型是否就绪（SenseVoice 语言 → 内置模型；扩展语言 → **任一候选**就绪即算就绪） */
    fun isModelReadyFor(context: Context, langKey: String): Boolean {
        val cands = AsrExtModels.candidatesByKey(langKey)
        return if (cands.isNotEmpty()) cands.any { isExtModelReady(context, it) }
        else isModelReady(context)
    }

    /**
     * 指定语言键的模型展示信息：名称 / 体积MB / 是否为需下载的扩展模型。
     *
     * v2.0.208：新增 [context]。多候选时优先取**第一个尚未就绪**的 ——
     * 这样提示文案里的体积正是「还需要下多大」，对用户最有信息量；
     * 全都就绪时退回第一个（此时体积已不重要）。
     */
    fun modelInfoFor(context: Context, langKey: String): Triple<String, Int, Boolean> {
        val cands = AsrExtModels.candidatesByKey(langKey)
        // v2.1.208：内置模型已改为 Dolphin（SenseVoice 已移除），无扩展候选时显示 Dolphin
        if (cands.isEmpty()) return Triple("Dolphin", 99, false)
        val pick = cands.firstOrNull { !isExtModelReady(context, it) } ?: cands.first()
        return Triple(pick.dirName, pick.sizeMb, true)
    }

    // =======================================================================
    // v2.0.208：多模型选择（同一语言有多个候选模型时的「选择模型」交互）
    // =======================================================================

    /** 一条模型候选（供「选择模型」UI 展示） */
    data class AsrModelCandidate(
        /** 候选标识：`builtin` 或扩展模型的 `dirName` */
        val id: String,
        /** 展示名 */
        val label: String,
        /** 体积 MB；**0 = 内置（无需下载）** */
        val sizeMb: Int,
        val builtin: Boolean
    )

    /** SenseVoice 内置覆盖的语言（这些语言的候选列表最前面会带一条内置） */
    private val BUILTIN_LANGS = setOf("zh", "en", "ja", "ko", "yue")

    private fun choicePrefs(context: Context) =
        context.getSharedPreferences("vr_player_prefs", Context.MODE_PRIVATE)

    /** 用户为某语言选中的模型 id；空串 = 未选择（自动取第一个已就绪的） */
    fun modelChoiceFor(context: Context, langKey: String): String =
        choicePrefs(context).getString("asr_model_choice_$langKey", "") ?: ""

    /**
     * 模型选择版本号：**任何一次 [setModelChoice] 都会自增**。
     *
     * 识别器重建的 LaunchedEffect 必须把它加进 key ——
     * 同一语言下换模型时 `langCode` 不变，仅靠 langCode 做 key **不会触发重建**
     * （v2.0.208 实测：点英语2 后识别仍在用英语1 的模型）。
     * 放在 Manager 而非 UI 局部状态，是因为语言 chip 有**两处渲染**
     * （设置面板 + 字幕快捷面板），两处触发都要能联动重建。
     */
    var modelChoiceVersion: Int by mutableStateOf(0)
        private set

    fun setModelChoice(context: Context, langKey: String, modelId: String) {
        choicePrefs(context).edit().putString("asr_model_choice_$langKey", modelId).apply()
        modelChoiceVersion++
    }

    /**
     * 指定语言的**全部候选模型**（内置 SenseVoice 在前，扩展模型在后）。
     *
     * 这正是「多模型对比」的数据源：同一语言（如英语）可能同时被
     * 内置 SenseVoice、FastConformer、Parakeet v3 覆盖 ——
     * 用户在这里能直观看到「有哪些模型可选、各占多大体积」。
     */
    fun modelCandidatesFor(context: Context, langKey: String): List<AsrModelCandidate> {
        val out = mutableListOf<AsrModelCandidate>()
        if (langKey in BUILTIN_LANGS) {
            out += AsrModelCandidate("builtin", "Dolphin", 0, true)
        }
        AsrExtModels.candidatesByKey(langKey).forEach { ext ->
            out += AsrModelCandidate(ext.dirName, ext.dirName, ext.sizeMb, false)
        }
        return out
    }

    /**
     * 解析「该语言实际应该用哪个候选」：
     * 用户选择 → 校验（候选存在且已就绪）→ 生效；无效则回退到自动选择。
     *
     * 返回 `null` = 走内置 SenseVoice 通路（没有扩展候选，或用户选了内置）。
     */
    fun resolveExtModel(context: Context, langKey: String): AsrExtModel? {
        val cands = AsrExtModels.candidatesByKey(langKey)
        if (cands.isEmpty()) return null

        val choice = modelChoiceFor(context, langKey)
        // 用户明确选了某个扩展模型（非 builtin）→ 只认它
        if (choice.isNotEmpty() && choice != "builtin") {
            cands.firstOrNull { it.dirName == choice }?.let { return it }
        }
        // ⚠️ v2.1.208：内置 SenseVoice 已移除，「builtin」这个历史选择值**不再特殊处理** ——
        //    旧版本可能把它存进 prefs，这里让它继续往下走「自动选择」分支（等价于未选择），
        //    否则会 return null 而走到已失效的 SenseVoice 路径上。
        //    （用户历史存档无需迁移，行为自然收敛到 Dolphin。）

        // 自动：第一个已就绪的扩展模型
        return cands.firstOrNull { isExtModelReady(context, it) }
    }

    /** 按语言键下载对应模型（扩展语言 → 扩展模型；其余 → SenseVoice 兜底通道） */
    fun startDownloadFor(context: Context, langKey: String) {
        val cands = AsrExtModels.candidatesByKey(langKey)
        if (cands.isEmpty()) {
            startModelDownload(context)
            return
        }
        // 已有任一候选就绪 → 无需再下；否则下「第一个未就绪」的
        if (cands.any { isExtModelReady(context, it) }) return
        startExtModelDownload(context, cands.first())
    }

    fun startExtModelDownload(context: Context, m: AsrExtModel) {
        if (isModelDownloading) return
        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            downloadExtModel(context, m)
        }
    }

    /** 下载扩展模型（多文件，逐个断点续传；进度按各文件最小字节数加权分配） */
    private suspend fun downloadExtModel(context: Context, m: AsrExtModel): File? =
        withContext(Dispatchers.IO) {
            val dir = extDir(context, m)
            if (isExtModelReady(context, m)) {
                Log.i(TAG, "ext model cached: $dir")
                return@withContext dir
            }
            dir.mkdirs()
            withContextMain {
                isModelDownloading = true
                modelDownloadProgress = 0f
                downloadStatus = context.getString(R.string.asr_preparing_download, m.sizeMb)
            }
            // 整包回退（如泰语：没有可按文件下载的源，只能下 tar.bz2 再解出需要的文件）
            m.archive?.let { arc ->
                val ok = downloadTarBz2AndExtract(context, arc, dir, m)
                withContextMain {
                    isModelDownloading = false
                    if (ok) {
                        modelDownloadProgress = 1f
                        downloadStatus = context.getString(R.string.asr_model_ready)
                    } else {
                        downloadStatus = context.getString(R.string.asr_model_download_failed)
                    }
                }
                return@withContext if (ok) dir else null
            }
            val totalMin = m.files.sumOf { it.minBytes }.coerceAtLeast(1L)
            var doneMin = 0L
            for (f in m.files) {
                // ⚠️ 用 fileOf() 而不是 dir.resolve(f.name)：
                //    ① 远端名可能带子目录（`hi/model.int8.onnx`），本地只取 basename；
                //    ② 共享文件（IndicConformer 的 tokens）要落到 ext-indic-shared/，
                //       那个目录可能还没建，所以必须 mkdirs。
                val dest = fileOf(context, m, f)
                dest.parentFile?.mkdirs()
                val ok = downloadFileWithResume(
                    url = f.url,
                    dest = dest,
                    progressBase = doneMin.toFloat() / totalMin,
                    progressSpan = f.minBytes.toFloat() / totalMin,
                    expectMinBytes = f.minBytes,
                    label = f.name
                )
                if (!ok) {
                    withContextMain {
                        isModelDownloading = false
                        downloadStatus = context.getString(R.string.asr_model_download_failed)
                    }
                    return@withContext null
                }
                doneMin += f.minBytes
            }
            withContextMain {
                isModelDownloading = false
                modelDownloadProgress = 1f
                downloadStatus = context.getString(R.string.asr_model_ready)
            }
            dir
        }

    /**
     * 下载 tar.bz2 **整包**并只解出需要的文件（整包兜底方案）。
     *
     * 用于「没有可按文件下载源」的模型（如泰语：hf-mirror 一律 401）。
     * 整包几百 MB，但最终只保留 int8 组合；解压按 basename 匹配（忽略包内目录层级），
     * 完成后删除整包，避免长期占用。进度：下载占 85%，解压按已解出文件数占 15%。
     */
    /**
     * 探测远端文件实际大小：`Range: bytes=0-0` → 读 `Content-Range` 里的总长度。
     *
     * 用途：整包下载的**完成判定阈值**。手填的 [AsrExtArchive.packageMb] 与上游实际
     * 包体积经常不一致（上游会重新打包/替换文件），硬编码阈值会误判「下载不完整」。
     * 失败时返回 -1，由调用方回退到 packageMb 估算。
     */
    private fun probeContentLength(url: String): Long = try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("Range", "bytes=0-0")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 20_000
        conn.connect()
        val cr = conn.getHeaderField("Content-Range")   // 形如 bytes 0-0/80671385
        conn.inputStream.close()
        conn.disconnect()
        cr?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1L
    } catch (e: Exception) {
        Log.w(TAG, "probeContentLength 失败：$url -> ${e.message}")
        -1L
    }

    private suspend fun downloadTarBz2AndExtract(
        context: Context,
        arc: AsrExtArchive,
        destDir: File,
        m: AsrExtModel
    ): Boolean {
        val pkg = File(destDir, "__pkg.tar.bz2")
        return try {
            withContextMain {
                downloadStatus = context.getString(R.string.asr_ext_archive_start, arc.packageMb)
            }
            // ⚠️ 完成阈值**按 HTTP Content-Length 自适应**，而不是手填的 packageMb。
            //    手填值与上游实际包体积不一致时会造成「明明下载完了却判不完整」——
            //    实测：Dolphin 填 100 实际 76.9MiB、Parakeet v3 填 640 实际 464.6MiB，
            //    两者都会恒定失败，表现就是「模型下载不了」。2026-10-02 修复。
            val realBytes = probeContentLength(arc.url)
            val expectMin = when {
                realBytes > 0 -> realBytes * 97L / 100L
                else -> (arc.packageMb.toLong() * 1024L * 1024L * 97L / 100L).coerceAtLeast(1L)
            }
            Log.i(TAG, "整包 ${m.dirName}：声明=${arc.packageMb}MB 实测=${realBytes}B 判定阈值=$expectMin")
            val downloaded = downloadFileWithResume(
                url = arc.url,
                dest = pkg,
                progressBase = 0f,
                progressSpan = 0.85f,
                // 97% 阈值：不足视为半包 → 继续 Range 续传，
                // 避免"下到一半就当成完整包、到解压才失败"的浪费
                expectMinBytes = expectMin,
                label = m.dirName
            )
            if (!downloaded) return false
            withContextMain {
                downloadStatus = context.getString(R.string.asr_ext_archive_extracting)
            }
            var done = 0
            var broken = false
            pkg.inputStream().buffered(1 shl 20).use { fin ->
                BZip2CompressorInputStream(fin).use { bz ->
                    TarArchiveInputStream(bz).use { tar ->
                        var entry = tar.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                val base = entry.name.substringAfterLast('/')
                                val min = arc.wanted[base]
                                if (min != null) {
                                    val out = destDir.resolve(base)
                                    out.outputStream().use { fos -> tar.copyTo(fos, 1 shl 20) }
                                    if (out.length() < min) {
                                        Log.w(TAG, "解压文件不完整：$base（${out.length()} < $min）")
                                        out.delete()
                                        broken = true
                                        break
                                    }
                                    done++
                                    val frac = 0.85f + 0.15f * done / arc.wanted.size
                                    withContextMain { modelDownloadProgress = frac.coerceAtMost(1f) }
                                }
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
            }
            if (broken) return false
            val ok = arc.wanted.all { (name, min) ->
                val f = destDir.resolve(name)
                f.exists() && f.length() >= min
            }
            if (ok) Log.i(TAG, "整包解压完成：${m.key}（保留 ${arc.wanted.size} 个文件）")
            ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "整包下载/解压失败：${e.message}")
            false
        } finally {
            try {
                pkg.delete()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 创建扩展语言识别器（离线 transducer：encoder/decoder/joiner + tokens）。
     *
     * 模型文件都在 filesDir，必须传绝对路径且 AssetManager 传 null
     * （sherpa-onnx 对「绝对路径 + 非空 AssetManager」会判定冲突并终止进程）。
     */
    fun createExtRecognizer(
        context: Context,
        m: AsrExtModel,
        threads: Int = 0
    ): OfflineRecognizer? {
        lastInitError = null
        if (!isExtModelReady(context, m)) {
            Log.w(TAG, "ext model not ready: ${m.key}")
            lastInitError = context.getString(R.string.asr_ext_model_unready, m.sizeMb)
            return null
        }
        val auto = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val numThreads = if (threads in MIN_THREADS..MAX_THREADS) threads else auto
        val dir = extDir(context, m)
        // 主模型与 tokens **可能不在同一目录**（IndicConformer 的 tokens 是 22 语共享的，
        // 落在 ext-indic-shared/），所以 tokens 必须按 effectiveTokensDir 定位，
        // 不能简单用 dir.resolve(m.tokens)。
        val modelPath = dir.resolve(m.encoder).absolutePath
        val tokensPath = extSubDir(context, m.effectiveTokensDir).resolve(m.tokens).absolutePath
        Log.i(
            TAG,
            "ext ASR (${m.key}): type=${m.modelType} single=${m.isSingleModel} " +
                "model=$modelPath tokens=$tokensPath threads=$numThreads"
        )
        // 内置 assets 优先：为 null 时走 filesDir 路径（下载版）
        var assetMgr: android.content.res.AssetManager? = null
        return try {
            val modelConfig = when (m.modelType) {
                // —— Dolphin（亚洲 40 语 + 中文 22 方言）：单一 model.int8.onnx
                // ⚠️ 字段名是 `dolphin`（已用 javap 核对 AAR，不是猜测）；
                //    modelType 用 sherpa 约定的 `dolphin`。
                //    与 CTC 的唯一区别就是这两个名字，结构同为「单文件」。
                //
                // v2.1.208：**优先用内置 assets**（模型已随 APK 打包）——
                //   此时路径换成 assets 内相对路径，并把 AssetManager 交给
                //   OfflineRecognizer(assetManager, config)，无需先把 99MB 复制到 filesDir。
                // assets 缺失/损坏时自动回退到下面的下载版路径。
                "dolphin" -> if (dolphinAssetAvailable(context)) {
                    assetMgr = context.assets
                    OfflineModelConfig(
                        dolphin = OfflineDolphinModelConfig(model = DOLPHIN_ASSET_MODEL),
                        modelType = "dolphin",
                        tokens = DOLPHIN_ASSET_TOKENS,
                        numThreads = numThreads,
                        debug = false,
                        provider = "cpu",
                    )
                } else {
                    OfflineModelConfig(
                        dolphin = OfflineDolphinModelConfig(model = modelPath),
                        modelType = "dolphin",
                        tokens = tokensPath,
                        numThreads = numThreads,
                        debug = false,
                        provider = "cpu",
                    )
                }

                // —— CTC（IndicConformer 南亚语）：**单一 model.onnx**，无 encoder/decoder/joiner 三分。
                // ⚠️ 必须填 `nemo` 字段（而不是 transducer）：填错字段时 sherpa 会因为
                //    「transducer 的 decoder/joiner 路径不存在」而直接初始化失败。
                //    ⚠️ 字段名是 `nemo` 而**不是** `nemoCtc`（后者是 Java 类名 OfflineNemoEncDecCtc
                //       带给人的直觉，但 Kotlin data class 的形参名就是 `nemo`）——
                //       写错会得到 `No parameter with name 'nemoCtc' found`，已实测。
                // ⚠️ modelType 用 sherpa 约定的 `nemo_ctc`，不是注册表里的简化名 `ctc`。
                "ctc" -> OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(model = modelPath),
                    modelType = "nemo_ctc",
                    tokens = tokensPath,
                    numThreads = numThreads,
                    debug = false,
                    provider = "cpu",
                )
                // —— transducer（vi / th）与 nemo_transducer（FastConformer / Parakeet v3）：
                //    encoder + decoder + joiner + tokens 四文件结构
                else -> OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = modelPath,
                        decoder = dir.resolve(m.decoder).absolutePath,
                        joiner = dir.resolve(m.joiner).absolutePath,
                    ),
                    modelType = m.modelType,
                    tokens = tokensPath,
                    numThreads = numThreads,
                    debug = false,
                    provider = "cpu",
                )
            }
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = modelConfig,
                decodingMethod = "greedy_search",
            )
            // assetMgr 非空 = 走内置 assets；为 null = 走 filesDir 下载版
            OfflineRecognizer(assetMgr, config).also {
                Log.i(TAG, "ext recognizer created: ${m.key}")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "ext recognizer init failed: ${e.message}", e)
            lastInitError = context.getString(R.string.asr_init_failed, e.message ?: e.javaClass.simpleName)
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
        downloadJob?.cancel()
        downloadJob = null
        isModelDownloading = false
    }

    private suspend fun withContextMain(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
}
