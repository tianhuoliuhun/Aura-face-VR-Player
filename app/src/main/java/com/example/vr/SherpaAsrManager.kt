package com.example.vr

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import com.example.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
 * ⚠️ v2.4.29 现状：SenseVoice 已**不再是内置模型**（内置地位自 v2.1.208 起由内置 Dolphin 接管）。
 * 现以**可下载扩展模型**接入（登记在 `AsrExtModels.SENSE_VOICE_ALL`），覆盖 中/英/日/韩/粤，
 * 并承接「英语」的默认路由（**Dolphin 不含英语**）。旧的 SenseVoice 专用下载通路已删除，
 * 下载统一走扩展模型通道。
 */
object SherpaAsrManager {

    private const val TAG = "SherpaAsr"

    /** 下载最多尝试次数（配合 Range 断点续传；大文件在弱网下首次失败很常见） */
    private const val MAX_DOWNLOAD_ATTEMPTS = 5

    /** 下载请求统一使用的 UA（个别镜像对默认 okhttp UA 的策略不同，用浏览器 UA 更稳） */
    private const val DOWNLOAD_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // ===== SenseVoice-Small（v2.4.29 起为**可下载扩展模型**）=====
    //
    // 模型：`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17` 的
    // model.int8.onnx（239MB）+ tokens.txt。234M 参数，中英日韩粤，RTF 0.026，自带标点。
    //
    // ⚠️ v2.4.29：原先这里有一整套**专用下载通路**（`SVC_*` 常量 + `svcDir` +
    // `downloadedModelReady` + `startModelDownload` + `downloadSenseVoiceCpu`）。
    //    自 SenseVoice 于 v2.1.208 退役后它**已无任何调用方**（死代码），
    //    而它的 URL/体积又与「把 SenseVoice 接成扩展模型」会构成
    //    **同一份数据两处登记**（本项目头号事故源）→ **整套删除**。
    //    现在统一由 `AsrExtModels.SENSE_VOICE_ALL` + 通用扩展下载
    //    （`startExtModelDownload`）承接，落盘目录 `sherpa_models/ext-sense-voice`。

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

    // v2.4.29：`svcDir()`（旧 SenseVoice 下载版目录）已随旧下载通路一并删除。

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

    // ===== v2.1.233：Dolphin 下载版路径（内置 assets 缺失时的兜底） =====
    // 与 createExtRecognizer 里 dolphin 分支的路径算法保持一致：
    // 模型落在 `ext-<dirName>/`，tokens 也在同一目录（Dolphin 没有共享 tokens）。
    private fun dolphinDownloadModelPath(context: Context): String =
        extSubDir(context, AsrExtModels.DOLPHIN_DIR).resolve("model.int8.onnx").absolutePath

    private fun dolphinDownloadTokensPath(context: Context): String =
        extSubDir(context, AsrExtModels.DOLPHIN_DIR).resolve("tokens.txt").absolutePath

    private fun downloadedDolphinReady(context: Context): Boolean {
        val m = File(dolphinDownloadModelPath(context))
        val t = File(dolphinDownloadTokensPath(context))
        return m.isFile && m.length() > 1_000_000L && t.isFile && t.length() > 1000L
    }

    // v2.1.244：原 `assetModelAvailable()`（探测 SenseVoice 内置 assets）已删除。
    // 它自 v2.1.214（assets 移除）起就成了死代码，且每次调用都必然打出
    // 「内置模型不可用：sense-voice/model.int8.onnx」的 W 级警告（纯噪音）。
    // 详见文件上方 SVC 配置区的说明。

    // v2.4.29：`downloadedModelReady()`（旧 SenseVoice 下载版就绪判定）已随旧下载通路删除。

    /**
     * 当前生效的模型来源。
     *
     * ⚠️ v2.1.244 更正：原先第一个分支是 SenseVoice 的「内置 assets」——
     * 但 SenseVoice 的内置 assets **自 v2.1.214 起已移除**（内置地位由 Dolphin 接管），
     * 该分支永远不会命中，属死代码；且它的存在会让「模型来源」显示成已不存在的
     * 「内置版（assets）/SenseVoice」。改为按 **Dolphin（当前唯一内置模型）** 判定。
     *
     * 优先级：内置 assets（Dolphin）→ 下载版（filesDir）→ 不可用。
     */
    fun activeModelSource(context: Context): String = when {
        dolphinAssetAvailable(context) -> context.getString(R.string.asr_source_assets)
        downloadedDolphinReady(context) -> context.getString(R.string.asr_source_downloaded)
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
        // ⚠️ v2.1.246：`modelId = DOLPHIN_DIR` 必须与 `AsrExtModels.DOLPHIN_LANGS` 里的
        //    `"auto"` 条目**配套**（那里已登记）—— chips 的选中判定是 `code@modelId`
        //    的精确匹配，`activeModelId` 走 `resolveExtModel(..., "auto")?.dirName ?: "builtin"`；
        //    少登记一处就退回 `"builtin"`，这条 chip 永不点亮。
        add(SherpaLang("auto", R.string.asr_lang_auto, modelId = AsrExtModels.DOLPHIN_DIR, group = AsrExtModels.AsrLangGroup.COMMON))
        // ⚠️ v2.4.29：中/日/韩/粤 现在各有两个候选 —— ①「1」= 内置 **Dolphin**（开箱即用、默认），
        //    ②「2」= **SenseVoice-Small**（需下载）。两处手动登记即与下方循环生成的条目
        //    uid 相同 → 循环那两条会被去重丢掉，从而**标签稳定**（否则循环按索引会给
        //    SenseVoice 编号「1」、Dolphin 没有号，看起来颠倒）。
        add(SherpaLang("zh", R.string.asr_lang_zh, modelId = AsrExtModels.DOLPHIN_DIR, suffix = "1", group = AsrExtModels.AsrLangGroup.CHINESE))
        add(SherpaLang("zh", R.string.asr_lang_zh, modelId = AsrExtModels.SENSE_VOICE_DIR, suffix = "2", group = AsrExtModels.AsrLangGroup.CHINESE))
        add(SherpaLang("ja", R.string.asr_lang_ja, modelId = AsrExtModels.DOLPHIN_DIR, suffix = "1", group = AsrExtModels.AsrLangGroup.EAST_ASIA))
        add(SherpaLang("ja", R.string.asr_lang_ja, modelId = AsrExtModels.SENSE_VOICE_DIR, suffix = "2", group = AsrExtModels.AsrLangGroup.EAST_ASIA))
        add(SherpaLang("ko", R.string.asr_lang_ko, modelId = AsrExtModels.DOLPHIN_DIR, suffix = "1", group = AsrExtModels.AsrLangGroup.EAST_ASIA))
        add(SherpaLang("ko", R.string.asr_lang_ko, modelId = AsrExtModels.SENSE_VOICE_DIR, suffix = "2", group = AsrExtModels.AsrLangGroup.EAST_ASIA))
        add(SherpaLang("yue", R.string.asr_lang_yue, modelId = AsrExtModels.DOLPHIN_DIR, suffix = "1", group = AsrExtModels.AsrLangGroup.CHINESE))
        add(SherpaLang("yue", R.string.asr_lang_yue, modelId = AsrExtModels.SENSE_VOICE_DIR, suffix = "2", group = AsrExtModels.AsrLangGroup.CHINESE))
        // —— 英语：3 个候选分开标（默认 SenseVoice / 轻量 / 全量）——
        //    「英语1」放常用组，用 **SenseVoice-Small**（v2.4.29）；
        //    「英语2/3」的模型是欧洲包（FC/V3），归入欧洲组，避免常用组里堆 3 个英语。
        // ⚠️ v2.4.29：原先「英语1」指向 `DOLPHIN_DIR` —— 但 **Dolphin 不含英语**，
        //    等于「英语」没有任何可用模型（选中后必然初始化失败或跑错模型）。
        //    现改指 SenseVoice（英文是其强项），与 `AsrExtModels.SENSE_VOICE_ALL` 成对。
        add(SherpaLang("en", R.string.asr_lang_en, modelId = AsrExtModels.SENSE_VOICE_DIR, suffix = "1", group = AsrExtModels.AsrLangGroup.COMMON))
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
            // ===================================================================
            // v2.1.233：**去重** —— 用户反馈「AI 字幕的选择有重复项」
            //
            // 真重复（uid 完全相同）来自两处登记撞车：
            //   上面手动 add 了 zh / ja / ko / yue（Dolphin + SenseVoice 各一条），
            //   而 AsrExtModels 里**也**含这些键，于是下面的循环又生成一遍 ——
            //   同语言同模型 = 两条一模一样的 chip（选中判定用 uid，两条会**同时高亮**）。
            //
            // 按 uid（`语言@模型`，**不含序号**）去重，保留第一条（= 手动登记的那条，
            // 从而标签可控：如「中文 1」= Dolphin、「中文 2」= SenseVoice）。
            // ===================================================================
        }.distinctBy { "${it.code}@${it.modelId}" }

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
     * v2.1.231：语言选择的**四项分类**（用户 2026-10-03 要求）。
     *
     * | 分类 | 判定 | 解决什么问题 |
     * |---|---|---|
     * | [COMMON] | [AsrExtModels.isCommonKey] | 高频语言一键可达 |
     * | [CJK_EN] | 中 / 粤 / 日 / 韩 / 英（含中文方言） | 按语种集合找 |
     * | [BUILTIN] | chip 的模型是内置 Dolphin | **不用下载就能用** |
     * | [DOWNLOAD] | 其余（需额外下载扩展模型） | 明确知道要付出下载成本 |
     *
     * ⚠️ 四类是**互斥**的（[categoryOf] 按上表顺序取第一个命中），每个 chip 恰好出现在一处 ——
     *    87 个 chip 若在各分类里重复铺开，面板会滚很久，从上往下找反而更慢。
     */
    enum class AsrLangCategory(@StringRes val labelRes: Int, val sortOrder: Int) {
        COMMON(R.string.asr_cat_common, 0),
        CJK_EN(R.string.asr_cat_cjk_en, 1),
        BUILTIN(R.string.asr_cat_builtin, 2),
        DOWNLOAD(R.string.asr_cat_download, 3),
    }

    /** 「中日韩英」语种集合：`zh` / `yue` / `ja` / `ko` / `en` + 全部 `zh_*` 方言 */
    private fun isCjkEnKey(code: String): Boolean =
        code == "zh" || code == "yue" || code == "ja" || code == "ko" || code == "en" ||
            code.startsWith("zh_")

    /**
     * 单个 chip 归属哪个分类（互斥，按 [AsrLangCategory] 表的顺序取第一个命中）。
     *
     * 「内置」的判据是 **模型是不是内置 Dolphin**（而不是该语言是否在五语列表里）——
     * Dolphin 本身是多语种模型、随 APK 发布，凡由它背书的 chip 都不需要下载。
     */
    fun categoryOf(chip: SherpaLang): AsrLangCategory = when {
        AsrExtModels.isCommonKey(chip.code) -> AsrLangCategory.COMMON
        isCjkEnKey(chip.code) -> AsrLangCategory.CJK_EN
        chip.modelId == AsrExtModels.DOLPHIN_DIR -> AsrLangCategory.BUILTIN
        else -> AsrLangCategory.DOWNLOAD
    }

    /** 按四项分类收纳后的 chip 分组（已按 [AsrLangCategory.sortOrder] 排序） */
    fun groupedByCategory(): List<Pair<AsrLangCategory, List<SherpaLang>>> {
        val byCat = sherpaLanguages.groupBy { categoryOf(it) }
        return AsrLangCategory.values()
            .sortedBy { it.sortOrder }
            .mapNotNull { cat -> byCat[cat]?.let { cat to it } }
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
        // v2.4.31：**SenseVoice 组置顶**（提高其 UI 优先等级）——
        // 「按模型」视角第一组就是它，不用往下翻。`sortedBy` 稳定，其余组保持原相对顺序。
        return byId.map { (id, chips) -> id to chips }
            .sortedBy { if (it.first == AsrExtModels.SENSE_VOICE_DIR) 0 else 1 }
    }

    /**
     * 模型是否就绪。
     *
     * ⚠️ v2.1.244 更正：原先 = `downloadedModelReady(老 SenseVoice 目录) ||
     * assetModelAvailable(SenseVoice assets)`。但 SenseVoice 的内置 assets 自 v2.1.214
     * 已移除、且内置地位由 Dolphin 接管 → 这两个判据都已失效（前者查的是废弃目录，
     * 后者恒 false）。改为按 **Dolphin** 判定，与 `activeModelSource` 保持一致。
     */
    fun isModelReady(context: Context): Boolean =
        dolphinAssetAvailable(context) || downloadedDolphinReady(context)

    // ===== 下载管理 =====

    // v2.4.29：`startModelDownload()`（旧 SenseVoice 专用下载入口，下载到
    // `sherpa_models/sense-voice-cpu`）已删除 —— 它自 SenseVoice 退役后就没有调用方，
    // 且与新的扩展模型登记重复。需要下载 SenseVoice 时走 `startDownloadFor` /
    // `startExtModelDownload`（登记在 AsrExtModels.SENSE_VOICE_ALL）。

    fun cancelDownload(context: Context) {
        downloadJob?.cancel()
        downloadJob = null
        isModelDownloading = false
        modelDownloadProgress = 0f
        downloadStatus = context.getString(R.string.asr_canceled)
    }

    // v2.4.29：`downloadSenseVoiceCpu()`（旧 SenseVoice 专用下载）已删除。
    // 见上方「下载管理」处的说明 —— 统一走扩展模型下载。

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
     * **只删已知废弃目录，不做通配清理**；当前在用的扩展模型目录
     * （`sherpa_models/ext-*`，含 v2.4.29 起的 `ext-sense-voice`）与 `silero_vad.onnx` 一律保留。
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
     * 创建离线识别器（当前实现只有 **Dolphin** 一条主线，SenseVoice 已于 v2.1.208 退役）。
     *
     * [threads] 为推理线程数（1~10）；传 0 或越界时按设备核心数自动取 `min(核数, 4)`。
     * 推荐值 4–6：太少跟不上播放速度，太多会挤占视频解码与渲染。
     *
     * 路由（v2.1.246 起）：
     * - `language == "auto"` → **[createDolphinRecognizer]**（内置 assets 优先）
     * - 有扩展候选且用户选了它 → [createExtRecognizer]
     * - 其余（`zh` / `ja` / `ko` / `yue` …）→ [createDolphinRecognizer]
     *
     * ⚠️ 模型来源：**内置 assets 优先**，assets 缺失时才回退 filesDir 的下载版
     * （v2.1.208 起模型已随 APK 打包，不再需要先复制到 filesDir）。
     * 传 asset 路径时必须同时给非空 AssetManager，传绝对路径时必须给 null
     * （sherpa-onnx 对"绝对路径 + 非空 AssetManager"会判定冲突并终止进程）。
     */
    fun createRecognizer(
        context: Context,
        language: String = "auto",
        threads: Int = 0
    ): OfflineRecognizer? {
        // =======================================================================
        // v2.1.233：「自动」必须**真的自动**
        //
        // 「自动」= 语种识别（LID）。能真正做 LID 的只有 **Dolphin** ——
        // 它是自带语种判定的多语种模型。用 javap 核对过 AAR：
        //   `OfflineDolphinModelConfig` **只有一个 `model` 字段，没有 language**，
        //   也就是说 Dolphin 在 sherpa-onnx 里**没有语种参数**，默认行为就是自动判定。
        //
        // 而 SenseVoice 的 `language="auto"` 只能在 中/英/日/韩/粤 **五种语言里猜** ——
        // 这既不符合「自动」的语义，且 SenseVoice 本身已在 v2.1.208 退役
        // （assets 可能已不存在）。此前选「自动」就落在这条废弃路径上，
        // 表现为「识别器创建失败」或「只能在五语里瞎猜」。
        //
        // ⚠️ v2.1.246（用户：「AI 字幕面板自动选项路由到内置模型」）：
        //    这个短路分支**显式锁定内置 Dolphin**，不再依赖「`candidatesByKey("auto")`
        //    恰好为空 → 落到最后的兜底 return」这条隐式路径。
        //    配套改动见 [AsrExtModels.DOLPHIN_LANGS] —— 那里把 `auto` 登记成了
        //    Dolphin 的语言键，于是 `resolveExtModel(ctx, "auto")` 不再返回 null，
        //    chip 的选中判定（`auto@<DOLPHIN_DIR>`）终于能对上 `activeModelId`。
        //    两条路都通向 `createDolphinRecognizer`，结论一致。
        // =======================================================================
        if (language == "auto") {
            return createDolphinRecognizer(context, threads)
        }

        // v2.0.145：扩展语言（越南语等）走各自独立的离线模型，与内置模型完全隔离。
        // v2.0.208：**尊重用户在「选择模型」里的选择**
        resolveExtModel(context, language)?.let { ext ->
            return createExtRecognizer(context, ext, threads)
        }

        // v2.1.233：没有扩展候选 → 该语言由内置 Dolphin 覆盖（如 zh / ja / ko / yue）。
        // ⚠️ 原先这里落到 **已退役的 SenseVoice** 通路：assets 一旦不存在就直接
        //    返回 null（识别器创建失败）。现在统一由 Dolphin 兜底。
        return createDolphinRecognizer(context, threads)
    }

    /**
     * 用**内置 Dolphin** 建识别器（不指定语种 = 自动识别；也是无扩展模型时的兜底通路）。
     *
     * Dolphin 在 sherpa-onnx 里没有语种参数（见 [createRecognizer] 里的 javap 结论），
     * 因此这个函数同时承担两件事：
     *   - 处理 `language == "auto"`（真正的 LID）
     *   - 作为内置语种（zh / ja / ko / yue …）的兜底实现
     */
    fun createDolphinRecognizer(
        context: Context,
        threads: Int = 0
    ): OfflineRecognizer? {
        lastInitError = null
        if (!dolphinAssetAvailable(context) && !downloadedDolphinReady(context)) {
            Log.w(TAG, "Dolphin not available（内置 assets 缺失且无下载版）")
            lastInitError = context.getString(R.string.asr_dolphin_unavailable)
            return null
        }
        val auto = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        val numThreads = if (threads in MIN_THREADS..MAX_THREADS) threads else auto
        // 内置 assets 优先；assets 缺失时回退到 filesDir 的下载版
        val useAsset = dolphinAssetAvailable(context)
        val assetManager: android.content.res.AssetManager? = if (useAsset) context.assets else null
        val modelPath = if (useAsset) DOLPHIN_ASSET_MODEL else dolphinDownloadModelPath(context)
        val tokensPath = if (useAsset) DOLPHIN_ASSET_TOKENS else dolphinDownloadTokensPath(context)
        Log.i(TAG, "Dolphin: source=${if (useAsset) "assets" else "download"} threads=$numThreads")
        return try {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    dolphin = OfflineDolphinModelConfig(model = modelPath),
                    modelType = "dolphin",
                    tokens = tokensPath,
                    numThreads = numThreads,
                    debug = false,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            )
            OfflineRecognizer(assetManager, config).also {
                Log.i(TAG, "Dolphin recognizer created（自动语种识别）")
            }
        } catch (e: Throwable) {
            // 用 Throwable：native 初始化失败可能抛 UnsatisfiedLinkError 等 Error 子类
            Log.e(TAG, "Dolphin init failed: ${e.message}", e)
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

    /**
     * 指定语言键的模型是否就绪。
     *
     * v2.1.233：无扩展候选时（如 `auto` / `zh` / `ja` / `ko` / `yue`）判定的是
     * **内置 Dolphin** 的可用性，而不是原先那条**已退役的 SenseVoice** 通路 ——
     * 否则选「自动」时这里永远返回 false，UI 显示「模型不可用」，
     * 可实际上 Dolphin 一直随包可用。
     */
    fun isModelReadyFor(context: Context, langKey: String): Boolean {
        val cands = AsrExtModels.candidatesByKey(langKey)
        if (cands.isNotEmpty()) {
            // 候选里有内置 Dolphin 且 assets 完好 → 开箱即用，无需下载
            if (cands.any { it.modelType == "dolphin" && dolphinAssetAvailable(context) }) return true
            return cands.any { isExtModelReady(context, it) }
        }
        return dolphinAssetAvailable(context) || downloadedDolphinReady(context)
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
        // v2.1.208：内置模型已改为 Dolphin（SenseVoice 已移除），无扩展候选时显示 Dolphin。
        // 体积给 0：Dolphin 是**随 APK 内置**的，不该让用户看到"需下载 99MB"。
        if (cands.isEmpty()) return Triple("Dolphin", 0, false)
        // ⚠️ Dolphin 已内置（assets 完好）→ 直接返回「内置、0MB、非扩展下载」三态，
        //    否则会走到下面的 pick 分支、显示成需要下载的扩展模型（且体积 99MB），
        //    与「开箱即用」的实际体验矛盾。
        if (cands.any { it.modelType == "dolphin" && dolphinAssetAvailable(context) }) {
            return Triple("Dolphin", 0, false)
        }
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

    /** 由**内置 Dolphin**覆盖的语言 —— 这些语言的候选列表最前面会带一条「内置」项。 */
    // v2.1.223：补上 "auto"。此前漏了它 → 「自动」语言走不到内置分支，
    // 在「选择模型」里被当成需要下载的扩展模型（显示 ~100MB），但它其实是内置的。
    // ⚠️ v2.4.29：**移除 "en"** —— Dolphin **不含英语**，原先给「英语」列一条
    //    「内置 Dolphin」是错的（选中即失败）。英语改由 SenseVoice-Small 承接
    //    （见 sherpaLanguages 的英语1 与 AsrExtModels.SENSE_VOICE_ALL）。
    private val BUILTIN_LANGS = setOf("auto", "zh", "ja", "ko", "yue")

    private fun choicePrefs(context: Context) =
        context.getSharedPreferences("vr_player_prefs", Context.MODE_PRIVATE)

    /** 用户为某语言选中的模型 id；空串 = 未选择（自动取第一个已就绪的） */
    /**
     * 当前该语言选中的模型 id。
     *
     * v2.1.226：默认值由 `""` 改为 **`"builtin"`**。
     * 原因：chips 的选中判断是 `"$code@${lang.modelId ?: "builtin"}"`，
     * 内置候选拼出来是 `en@builtin`；而这里原先返回 `""` 时，
     * 与 activeModelId 拼出的 `en@` 对不上 → **内置候选永远显示为未选中**。
     */
    fun modelChoiceFor(context: Context, langKey: String): String =
        choicePrefs(context).getString("asr_model_choice_$langKey", "builtin") ?: "builtin"

    /**
     * 模型选择版本号：**任何一次 [setModelChoice] 都会自增**。
     *
     * 识别器重建的 LaunchedEffect 必须把它加进 key ——
     * 同一语言下换模型时 `langCode` 不变，仅靠 langCode 做 key **不会触发重建**
     * （v2.0.208 实测：点英语2 后识别仍在用英语1 的模型）。
     * 放在 Manager 而非 UI 局部状态，是因为语言 chip 有**两处渲染**
     * （设置面板 + 字幕快捷面板），两处触发都要能联动重建。
     */
    var modelChoiceVersion: Int by mutableIntStateOf(0)
        private set

    /**
     * 当前生效的模型 id（`builtin` 或扩展模型 dirName）—— **UI 选中判定的唯一来源**。
     *
     * ⚠️ v2.4.31：抽成单一实现。此前「设置面板 / AI 字幕面板」各自写了一遍
     * `resolveExtModel(context, sherpaLangCode)?.dirName ?: "builtin"` ——
     * 属本项目「同一份逻辑两处登记」的头号事故源（改一处漏一处）。
     */
    fun activeModelIdFor(context: Context, langKey: String): String =
        resolveExtModel(context, langKey)?.dirName ?: "builtin"

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
        // v2.1.223：Dolphin 随 APK 内置（体积 0、随包可用），因此作为「内置项」列出。
        // ⚠️ 原来这里硬编码 `true`（意为"需下载"），是 SenseVoice 时代的残留 ——
        //    内置化之后必须改为 assets 的实际可用性，否则 UI 会显示"需下载"却其实已内置。
        if (langKey in BUILTIN_LANGS) {
            out += AsrModelCandidate(
                id = "builtin",
                label = "Dolphin",
                sizeMb = 0,
                builtin = dolphinAssetAvailable(context)
            )
        }
        // ⚠️ 已内置（assets 完好）时不再把 Dolphin 作为「扩展下载项」重复列出 ——
        //    否则同一语言会出现两条 Dolphin（一条 0MB 内置、一条 100MB 需下载），自相矛盾。
        val dolphinBundled = dolphinAssetAvailable(context)
        AsrExtModels.candidatesByKey(langKey).forEach { ext ->
            if (ext.modelType == "dolphin" && dolphinBundled) return@forEach
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

    /**
     * 该语言**是否真的需要下载**模型。
     *
     * v2.1.232：新增。此前 UI 只要看到按钮就点得到，而 [startDownloadFor] 内部会静默
     * 走到 SenseVoice 兜底通道 —— 于是用户反馈「选『自动』还是会下载 SenseVoice-Small」。
     * 判据与 [startDownloadFor] 完全一致，供 UI 决定按钮要不要禁用。
     */
    fun isDownloadNeededFor(context: Context, langKey: String): Boolean {
        val cands = AsrExtModels.candidatesByKey(langKey)
        // 无扩展候选 → 走内置 Dolphin（随 APK），nothing to download
        if (cands.isEmpty()) return false
        // v2.4.29：**用户明确选了某个候选时只看该候选** —— 否则「选了 SenseVoice（中/日/韩/粤
        //   也登记了它）却因该语言另有内置 Dolphin 而显示『无需下载』」→ 选中后初始化失败。
        val choice = modelChoiceFor(context, langKey)
        if (choice.isNotEmpty() && choice != "builtin") {
            cands.firstOrNull { it.dirName == choice }?.let { return !isExtModelReady(context, it) }
        }
        // 候选里有内置 Dolphin 且 assets 完好 → 该语言开箱即用
        if (cands.any { it.modelType == "dolphin" && dolphinAssetAvailable(context) }) return false
        // 已有任一候选就绪 → 无需再下
        return !cands.any { isExtModelReady(context, it) }
    }

    /**
     * 按语言键下载对应模型（扩展语言 → 扩展模型；其余 → **无需下载**）。
     *
     * v2.1.232 修正：原先这里在「没有扩展候选」时会下载一个**根本用不上**的模型。
     * 无扩展候选 → 由**随包的 Dolphin** 覆盖 → 直接返回、不下载。
     *
     * ⚠️ v2.4.29：SenseVoice 已作为扩展模型重新接入（中/英/日/韩/粤），
     * 因此**新增「用户显式选择优先」** —— 用户选了 SenseVoice 时，即使该语言
     * 另有内置 Dolphin，也要真的去下 SenseVoice（否则选中后初始化失败）。
     */
    fun startDownloadFor(context: Context, langKey: String) {
        val cands = AsrExtModels.candidatesByKey(langKey)
        if (cands.isEmpty()) {
            Log.i(TAG, "startDownloadFor($langKey)：无扩展候选，由内置 Dolphin 覆盖，无需下载")
            return
        }
        // v2.4.29：**用户明确选了某个候选 → 优先下它**（即使该语言另有内置 Dolphin 覆盖）。
        val choice = modelChoiceFor(context, langKey)
        if (choice.isNotEmpty() && choice != "builtin") {
            cands.firstOrNull { it.dirName == choice }?.let { m ->
                if (!isExtModelReady(context, m)) startExtModelDownload(context, m)
                return
            }
        }
        // ⚠️ v2.1.208：Dolphin 已内置到 assets —— 对该语言而言无需任何下载，
        //    直接返回（否则 UI 的「下载模型」按钮会触发一次无意义的下载流程）。
        if (cands.any { it.modelType == "dolphin" && dolphinAssetAvailable(context) }) return
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

                // —— SenseVoice-Small（中/英/日/韩/粤）：单一 `model.int8.onnx` + tokens（v2.4.29）
                // ⚠️ 字段名是 `senseVoice`（已用 javap 核对 AAR：OfflineModelConfig.senseVoice），
                //    modelType 用 sherpa 约定的 `sense_voice`；结构与 Dolphin 同为「单文件」。
                // `language` 传该语言自己的 SenseVoice 代码（zh/en/ja/ko/yue）；
                // 其余（理论上到不了）退 `auto` 让模型自带 LID。
                "sense_voice" -> OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = modelPath,
                        language = when (m.key) {
                            "zh" -> "zh"
                            "en" -> "en"
                            "ja" -> "ja"
                            "ko" -> "ko"
                            "yue" -> "yue"
                            else -> "auto"
                        },
                        useInverseTextNormalization = false,
                    ),
                    modelType = "sense_voice",
                    tokens = tokensPath,
                    numThreads = numThreads,
                    debug = false,
                    provider = "cpu",
                )

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
