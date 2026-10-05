package com.example.vr

import com.example.R

/**
 * 扩展 ASR 模型中的单个文件（下载 URL + 期望最小字节数，用于校验完整性）。
 *
 * 下载走 hf-mirror 的 `resolve/main/<文件名>` 直链（支持按文件、支持 Range 续传）。
 */
data class AsrExtFile(
    /** 远端文件名；**可含子目录**（如 `hi/model.int8.onnx`） */
    val name: String,
    val url: String,
    /** 期望最小字节数：中断/被截断的残缺文件不达标，避免误判"已下载" */
    val minBytes: Long,
    /**
     * 落盘目录名（相对于 `filesDir/sherpa_models/`）。
     * - `null` = 落到该模型自己的 `ext-<dirName>` 目录（默认）
     * - 指定值 = 落到 `ext-<该值>`，用于**多语言共享文件**
     *   （如 IndicConformer 的 22 语共用一份 `tokens.txt`）
     *
     * 落盘文件名一律取 [name] 的 basename —— 远端带子目录只为定位 URL，
     * 本地不保留那层目录（避免 `ext-indic-hi/hi/model.onnx` 这种别扭结构）。
     */
    val dir: String? = null
)

/**
 * 「扩展语言」ASR 模型注册表条目。
 *
 * 背景：内置 SenseVoice 只覆盖 中/英/日/韩/粤；其余语言各需一个独立离线模型。
 * 本表逐条接入，**不内置进 APK**（体积原因），由用户在设置面板按需下载到
 * `filesDir/sherpa_models/ext-<dirName>`。
 *
 * ---------------------------------------------------------------------------
 * 【三种模型结构（v2.0.208 起支持）】
 *
 * | modelType          | 需要的文件                                   | 用在哪 |
 * |--------------------|---------------------------------------------|--------|
 * | `transducer`       | encoder + decoder + joiner + tokens          | vi / th |
 * | `nemo_transducer`  | encoder + decoder + joiner + tokens          | FastConformer / Parakeet v3 |
 * | `ctc`              | **仅 model + tokens**（无 encoder/decoder/joiner 三分） | IndicConformer 南亚语 |
 *
 * 三者都走 `OfflineModelConfig`，但**填的字段不同**：
 * transducer 填 `transducer = OfflineTransducerModelConfig(...)`；
 * CTC 填 `nemoCtc = OfflineNemoEncDecCtcModelConfig(...)`。
 * 具体装配见 [SherpaAsrManager.createExtRecognizer]。
 *
 * ---------------------------------------------------------------------------
 * 【设计约定（均为实测踩过的坑，勿凭直觉改）】
 * 1. **文件名必须逐模型写死**：sherpa 各模型命名风格不统一
 *    （vi 是 `encoder-epoch-12-avg-8.int8.onnx`，ru 是 `encoder.int8.onnx`），
 *    不能靠拼字符串推导。
 * 2. **int8 通常只量化 encoder**：decoder 多数仍是 fp32（vi 的 decoder 就是 fp32），
 *    因此"文件名带不带 .int8"要逐个确认。
 * 3. 下载源优先选 **hf-mirror 的按文件源**（支持 Range 续传）；不可达时退到
 *    GitHub releases 的整包 tar.bz2（见 [AsrExtArchive]）。
 *    ⚠️ hf-mirror 对**部分仓库**会按出口 IP / 缓存命中返回 401（泰语即如此），
 *    因此每个模型上线前必须实测可达。
 * 4. **同一语言可以有多个模型**（v2.0.208 起）：例如俄语同时存在
 *    FastConformer（102MB 包，与 8 语共用）与 Parakeet v3（640MB 包，与 24 语共用）。
 *    此时 `key` 会重复，用 [AsrExtModels.candidatesByKey] 取全部候选，
 *    就绪判定与识别器创建取**第一个已就绪**的。
 */
data class AsrExtModel(
    /** 语言键；同一语言存在多个模型时**允许重复**（见文件头约定 4） */
    val key: String,
    val labelResId: Int,
    /** 模型文件的落盘目录名：filesDir/sherpa_models/ext-<dirName> */
    val dirName: String,
    /** sherpa 的 modelType：`transducer` / `nemo_transducer` / `ctc` */
    val modelType: String,
    /**
     * 主模型文件名。
     * - transducer / nemo_transducer：encoder 文件名
     * - **ctc：唯一的 `model.onnx` / `model.int8.onnx`**
     */
    val encoder: String,
    /** transducer 专用；CTC 下为空串 */
    val decoder: String = "",
    /** transducer 专用；CTC 下为空串 */
    val joiner: String = "",
    val tokens: String,
    /**
     * tokens 的落盘目录名。默认与 [dirName] 相同；
     * **多语言共享 tokens** 时（如 IndicConformer 的 22 语）指向同一个公共目录。
     */
    val tokensDirName: String = "",
    /** 展示用体积（MB，向上取整） */
    val sizeMb: Int,
    val files: List<AsrExtFile>,
    /**
     * 整包回退方案：**仅在没有「可按文件下载」的源时使用**（如泰语）。
     * 有 [files] 时优先走按文件下载。
     */
    val archive: AsrExtArchive? = null
) {
    /** 实际使用的 tokens 目录名（未显式指定时同 [dirName]） */
    val effectiveTokensDir: String get() = tokensDirName.ifEmpty { dirName }

    /**
     * 是否为「单文件模型」——
     * `ctc`（IndicConformer）与 `dolphin`（Dolphin）都只有**一个** `model.onnx`，
     * 没有 encoder/decoder/joiner 三件套；transducer 家族则需要。
     */
    val isSingleModel: Boolean get() = modelType == "ctc" || modelType == "dolphin"
}

/**
 * tar.bz2 整包（GitHub releases）。
 *
 * 代价：必须把整包全部下完（几百 MB），再流式解压出需要的几个文件，最后删包——
 * 下载量远大于最终占用。因此只作为**兜底**，能拿到按文件源时应优先用 [AsrExtFile]。
 */
data class AsrExtArchive(
    val url: String,
    /** 包内文件 basename -> 期望最小字节数（按 basename 匹配，忽略包内目录层级） */
    val wanted: Map<String, Long>,
    /** 整包体积（MB，仅用于提示文案） */
    val packageMb: Int
)

object AsrExtModels {

    private const val HF = "https://hf-mirror.com/csukuangfj/"

    private fun f(repo: String, name: String, minBytes: Long) = AsrExtFile(
        name = name,
        url = "$HF$repo/resolve/main/$name",
        minBytes = minBytes
    )

    /** 任意 hf-mirror 仓库下的文件（第三方仓库用，非 csukuangfj 命名空间） */
    private fun fAny(repo: String, name: String, minBytes: Long, dir: String? = null) = AsrExtFile(
        name = name,
        url = "https://hf-mirror.com/$repo/resolve/main/$name",
        minBytes = minBytes,
        dir = dir
    )

    /**
     * **ModelScope 文件直链**（`resolve/master/<文件名>`，支持 Range 续传）。
     *
     * 2026-10-02 引入：GitHub releases 只有整包 tar.bz2（要整包下载 + 流式解压，
     * 且完成判定依赖包体积、上游一变就误判），而 ModelScope 上 csukuangfj 命名空间下有
     * **已转好的 ONNX 单文件**，可直接按文件下载 —— 更快也更稳。
     * ⚠️ 本注释里不要写 "csukuangfj/*"：块注释中的 "*/" 会**提前结束注释**，
     *    导致其后代码结构崩塌（本次就是这么踩的）。
     *
     * ⚠️ 注意命名空间的坑：
     *    `DataoceanAI/dolphin-base` 是**原始 PyTorch 权重**（base.pt 561MB），
     *    sherpa-onnx **用不了**；必须取 `csukuangfj/sherpa-onnx-dolphin-*` 的 ONNX 转换版。
     */
    private fun fMs(repo: String, name: String, minBytes: Long) = AsrExtFile(
        name = name,
        url = "https://www.modelscope.cn/models/csukuangfj/$repo/resolve/master/$name",
        minBytes = minBytes
    )

    // =======================================================================
    // 越南语
    // =======================================================================

    /**
     * 越南语 —— zipformer transducer（int8 encoder）。
     *
     * 源：`csukuangfj/sherpa-onnx-zipformer-vi-int8-2025-04-20`（hf-mirror 已实测可达）
     * 实测体积：encoder 70,876,129 + decoder 5,165,084 + joiner 1,033,417 + tokens 25,847
     * ≈ **73.5MB**（`bpe.model` 未下载：官方命令行示例未使用 modelingUnit，tokens.txt 已自足）。
     */
    val VIETNAMESE = AsrExtModel(
        key = "vi",
        labelResId = R.string.asr_lang_vi,
        dirName = "zipformer-vi-int8",
        modelType = "transducer",
        encoder = "encoder-epoch-12-avg-8.int8.onnx",
        decoder = "decoder-epoch-12-avg-8.onnx",
        joiner = "joiner-epoch-12-avg-8.int8.onnx",
        tokens = "tokens.txt",
        sizeMb = 74,
        files = listOf(
            f("sherpa-onnx-zipformer-vi-int8-2025-04-20", "encoder-epoch-12-avg-8.int8.onnx", 60_000_000L),
            f("sherpa-onnx-zipformer-vi-int8-2025-04-20", "decoder-epoch-12-avg-8.onnx", 3_000_000L),
            f("sherpa-onnx-zipformer-vi-int8-2025-04-20", "joiner-epoch-12-avg-8.int8.onnx", 700_000L),
            f("sherpa-onnx-zipformer-vi-int8-2025-04-20", "tokens.txt", 10_000L)
        )
    )

    // =======================================================================
    // 欧洲 —— NeMo FastConformer 20k（轻量档：整包 102MB / 覆盖 10 语）
    // =======================================================================
    //
    // 与 Parakeet v3（见下）**共存**，两者覆盖 10 种语言重叠。
    // 本档定位「轻量」：102MB 拿 10 语；v3 定位「全量高精度」：640MB 拿 25 语。
    //
    // 为什么**不用** parakeet-tdt-0.6b-**v3** 作为唯一档位见下方 PARAKEET 段说明；
    // 而 v2 时代的 parakeet-0.6b-v2-int8（639MB）之所以被弃用，是因为
    //   ① 体积过大，手机上下载体验极差、极易半途失败；
    //   ② 它**只有 hf-mirror 一条「按文件」源**，而该源在真机上会返回 **401**
    //      —— 设备实测日志：`encoder.int8.onnx HTTP 401` / `下载不完整：0 字节`
    //      （同一 URL 在沙箱出口 IP 却是 206 → 说明 hf-mirror 按**出口 IP / 缓存命中**
    //       区别对待，未命中就回源失败，属**不可靠源**）。
    // 本模型为官方同门 NeMo FastConformer：**一个包覆盖 ru / de / es / fr**
    // （另有 en / hr / it / pl / uk），整包 102MB、解压后 ≈132MB，
    // 走 **GitHub releases**（实测可达且支持 Range，不依赖 hf-mirror 的按文件源）。
    // 文件命名与 NeMo 规范一致（encoder/decoder/joiner.int8.onnx + tokens.txt），
    // 用法同为 modelType = "nemo_transducer"。
    const val FASTCONF_DIR = "nemo-fast-conformer-20k-int8"
    private const val FASTCONF_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-nemo-fast-conformer-transducer-be-de-en-es-fr-hr-it-pl-ru-uk-20k-int8.tar.bz2"

    /** 多个语言键指向同一 [dirName] → 下载一次，多语通用（就绪状态也自动共享） */
    private fun fastConformer(key: String, labelResId: Int) = AsrExtModel(
        key = key,
        labelResId = labelResId,
        dirName = FASTCONF_DIR,
        modelType = "nemo_transducer",
        encoder = "encoder.int8.onnx",
        decoder = "decoder.int8.onnx",
        joiner = "joiner.int8.onnx",
        tokens = "tokens.txt",
        sizeMb = 132,
        files = emptyList(),
        archive = AsrExtArchive(
            url = FASTCONF_URL,
            wanted = mapOf(
                "encoder.int8.onnx" to 120_000_000L,
                "decoder.int8.onnx" to 4_000_000L,
                "joiner.int8.onnx" to 2_000_000L,
                "tokens.txt" to 10_000L
            ),
            packageMb = 102
        )
    )

    // 该包名 `…-be-de-en-es-fr-hr-it-pl-ru-uk-…` 共覆盖 **10 种**语言。
    // ⚠️ 全部条目共用同一 [dirName]：**下载一次，这些语言全部可用**。
    // 注：en（英语）**未在此登记**——内置 SenseVoice 已覆盖，
    //     再登记会出现两个「英语」条目造成歧义。
    val RUSSIAN = fastConformer("ru", R.string.asr_lang_ru)
    val FRENCH = fastConformer("fr", R.string.asr_lang_fr)
    val GERMAN = fastConformer("de", R.string.asr_lang_de)
    val SPANISH = fastConformer("es", R.string.asr_lang_es)
    val BELARUSIAN = fastConformer("be", R.string.asr_lang_be)
    val CROATIAN = fastConformer("hr", R.string.asr_lang_hr)
    val ITALIAN = fastConformer("it", R.string.asr_lang_it)
    val POLISH = fastConformer("pl", R.string.asr_lang_pl)
    val UKRAINIAN = fastConformer("uk", R.string.asr_lang_uk)

    // =======================================================================
    // 欧洲 —— Parakeet TDT 0.6B v3（全量档：整包 640MB / 覆盖 25 语）
    // =======================================================================
    //
    // 【为什么与 FastConformer 共存而不是替代】
    //   v3 是全量高精度档（WER 6.34%，优于 Whisper large-v3 的 7.44%），
    //   但它 640MB 且与 FastConformer 的 10 语重叠。两者**共存**让用户自选：
    //   只想要常见欧洲语言 → 下 102MB 的 FastConformer；
    //   要全 25 语或更高精度 → 下 640MB 的 v3。
    //   ⚠️ 因此同一语言键会**重复出现**（如 ru 有两条），
    //      查找一律用 [candidatesByKey] 取候选列表。
    //
    // 【来源】nvidia/parakeet-tdt-0.6b-v3 → k2-fsa 官方转换
    //   文件：encoder.int8.onnx 622M + decoder.int8.onnx 12M + joiner.int8.onnx 6.1M + tokens.txt 92K
    //   modelType = "nemo_transducer"（与 FastConformer 同门，装配代码完全复用）
    const val PARAKEET_V3_DIR = "parakeet-tdt-0.6b-v3-int8"
    private const val PARAKEET_V3_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2"

    private fun parakeetV3(key: String, labelResId: Int) = AsrExtModel(
        key = key,
        labelResId = labelResId,
        dirName = PARAKEET_V3_DIR,
        modelType = "nemo_transducer",
        encoder = "encoder.int8.onnx",
        decoder = "decoder.int8.onnx",
        joiner = "joiner.int8.onnx",
        tokens = "tokens.txt",
        sizeMb = 640,
        files = emptyList(),
        archive = AsrExtArchive(
            url = PARAKEET_V3_URL,
            wanted = mapOf(
                "encoder.int8.onnx" to 550_000_000L,
                "decoder.int8.onnx" to 10_000_000L,
                "joiner.int8.onnx" to 5_000_000L,
                "tokens.txt" to 50_000L
            ),
            // ⚠️ 实测整包 = 487,170,055 B ≈ **465MiB**（此前填 640 是按解压后体积算的，
            //    完成判定因此要求 ≥621MiB → 恒定误判"下载不完整"）
            packageMb = 465
        )
    )

    // v3 覆盖 25 种欧洲语言；其中 10 种与 FastConformer 重叠（不重复登记，
    // 由 candidatesByKey 合并为「同一语言的多个候选」），
    // 下列 **16 种为 FastConformer 没有的**：
    //   bg 保加利亚 · cs 捷克 · da 丹麦 · nl 荷兰 · et 爱沙尼亚 · fi 芬兰 ·
    //   el 希腊 · hu 匈牙利 · lv 拉脱维亚 · lt 立陶宛 · mt 马耳他 ·
    //   pt 葡萄牙 · ro 罗马尼亚 · sk 斯洛伐克 · sl 斯洛文尼亚 · sv 瑞典
    val BULGARIAN = parakeetV3("bg", R.string.asr_lang_bg)
    val CZECH = parakeetV3("cs", R.string.asr_lang_cs)
    val DANISH = parakeetV3("da", R.string.asr_lang_da)
    val DUTCH = parakeetV3("nl", R.string.asr_lang_nl)
    val ESTONIAN = parakeetV3("et", R.string.asr_lang_et)
    val FINNISH = parakeetV3("fi", R.string.asr_lang_fi)
    val GREEK = parakeetV3("el", R.string.asr_lang_el)
    val HUNGARIAN = parakeetV3("hu", R.string.asr_lang_hu)
    val LATVIAN = parakeetV3("lv", R.string.asr_lang_lv)
    val LITHUANIAN = parakeetV3("lt", R.string.asr_lang_lt)
    val MALTESE = parakeetV3("mt", R.string.asr_lang_mt)
    val PORTUGUESE = parakeetV3("pt", R.string.asr_lang_pt)
    val ROMANIAN = parakeetV3("ro", R.string.asr_lang_ro)
    val SLOVAK = parakeetV3("sk", R.string.asr_lang_sk)
    val SLOVENIAN = parakeetV3("sl", R.string.asr_lang_sl)
    val SWEDISH = parakeetV3("sv", R.string.asr_lang_sv)

    // =======================================================================
    // ⚠️ v2.0.208 新增：英语 + 与 FastConformer 重叠的语言，在 Parakeet v3 上**也登记**
    // =======================================================================
    //
    // 【为什么之前不登记】避免同语言出现两个条目造成歧义。
    // 【为什么现在要登记】用户要求「重复出现的语言（如英语）应支持选择模型」——
    //   没有重复条目就没有候选可选。现在改为：
    //   同语言 = 多个候选模型（FastConformer 轻量档 + Parakeet v3 全量档），
    //   UI 上语言 chip 只出现一次，点击后在下方展开「选择模型」。
    // 【 SenseVoice 内置候选】不在本注册表里（它走内置通路，0MB 下载），
    //   由 SherpaAsrManager.modelCandidatesFor() 在列表最前面补上。
    val ENGLISH_FC = fastConformer("en", R.string.asr_lang_en)      // 内置五语之外唯一重登的 FastConformer 条目
    val ENGLISH_V3 = parakeetV3("en", R.string.asr_lang_en)
    val RUSSIAN_V3 = parakeetV3("ru", R.string.asr_lang_ru)
    val FRENCH_V3 = parakeetV3("fr", R.string.asr_lang_fr)
    val GERMAN_V3 = parakeetV3("de", R.string.asr_lang_de)
    val SPANISH_V3 = parakeetV3("es", R.string.asr_lang_es)
    val BELARUSIAN_V3 = parakeetV3("be", R.string.asr_lang_be)
    val CROATIAN_V3 = parakeetV3("hr", R.string.asr_lang_hr)
    val ITALIAN_V3 = parakeetV3("it", R.string.asr_lang_it)
    val POLISH_V3 = parakeetV3("pl", R.string.asr_lang_pl)
    val UKRAINIAN_V3 = parakeetV3("uk", R.string.asr_lang_uk)

    // =======================================================================
    // 亚洲 40 语 + 中文 22 方言 —— Dolphin（CTC 单模型，≈100MB **全覆盖**）
    // =======================================================================
    //
    // 【为什么选它（2026-10-01 调研后取代了 IndicConformer 方案）】
    //   清华电子系语音与音频技术实验室 × 海天瑞声（DataoceanAI）出品，官方已转 ONNX。
    //   对比之前的南亚方案（AI4Bharat IndicConformer，188MB / 语言、8 语共 1.5GB）：
    //     · Dolphin  **100MB 覆盖 40 语言 + 22 中文方言**
    //     · 速度最快（官方四个 Dolphin 模型中 base-int8 的 RTF 0.094）
    //     · 同尺寸下 WER 比 Whisper 低 60%+（中文 9.2% vs Whisper large-v3 的 27.9%）
    //   → 体积省下约 1.2GB，覆盖从 8 语扩到 62 项。
    //
    // 【结构】CTC 单模型：`model.int8.onnx`（99MB）+ `tokens.txt`（493KB）
    //   modelType = "dolphin" → 装配用 `dolphin = OfflineDolphinModelConfig(model)`
    //   ⚠️ 与 CTC 一样是**单文件**（无 encoder/decoder/joiner 三分），
    //      所以复用同一套 `isSingleModel` 路径。
    //
    // 【语言/方言指定】⚠️ **不需要指定** —— sherpa-onnx 侧只传模型与 tokens，
    //   模型自带语种识别（LID）。因此这里 62 个条目**全部指向同一个 dirName**，
    //   用户下载一次即可使用全部语言与方言（与 FastConformer 的「一包多语」同构）。
    //
    // 【覆盖范围】不含欧洲语言（除 ru）—— 欧洲仍由 FastConformer / Parakeet v3 承接。
    const val DOLPHIN_DIR = "dolphin-base-ctc-multi-lang-int8"
    /** ModelScope 上的 ONNX 转换版仓库（下载主源） */
    private const val DOLPHIN_MS_REPO = "sherpa-onnx-dolphin-base-ctc-multi-lang-int8-2025-04-02"
    /** GitHub 整包（兜底源，当前不用） */
    private const val DOLPHIN_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-dolphin-base-ctc-multi-lang-int8-2025-04-02.tar.bz2"

    private fun dolphin(key: String, labelResId: Int) = AsrExtModel(
        key = key,
        labelResId = labelResId,
        dirName = DOLPHIN_DIR,
        modelType = "dolphin",
        encoder = "model.int8.onnx",
        tokens = "tokens.txt",
        sizeMb = 100,
        // ✅ 2026-10-02：改为 **ModelScope 按文件下载**（不再走 GitHub 整包）。
        //   实测 model.int8.onnx = 103,729,802B、tokens.txt = 504,662B，Range 返回 206。
        //   好处：省掉整包下载 + 流式解压，也不再受整包体积变化影响。
        files = listOf(
            fMs(DOLPHIN_MS_REPO, "model.int8.onnx", 90_000_000L),
            fMs(DOLPHIN_MS_REPO, "tokens.txt", 400_000L)
        ),
        // 整包方案保留为兜底（ModelScope 不可达时可切回），当前不使用：
        // ⚠️ files 非空时优先走按文件下载，archive 不会生效
        archive = AsrExtArchive(
            url = DOLPHIN_URL,
            wanted = mapOf(
                "model.int8.onnx" to 90_000_000L,
                "tokens.txt" to 300_000L
            ),
            // ⚠️ 实测整包体积 = 80,671,385 B ≈ **77MiB**（2026-10-02，HTTP Content-Length）。
            //    此前填 100 → 完成判定要求 ≥97MiB，而实际只有 76.9MiB → **恒定误判"下载不完整"**。
            packageMb = 77
        )
    )
    // ⚠️ packageMb 现在只用于「提示文案」与「Content-Length 探测失败时的兜底阈值」；
    //    正常下载已改为按 HTTP Content-Length 自适应（见 SherpaAsrManager.downloadTarBz2AndExtract），
    //    上游整包体积变化时不再需要改这个值。

    /**
     * Dolphin 覆盖的**语言**（已排除内置 SenseVoice 覆盖的 zh/ja/ko/ct，
     * 以及已有专用模型的 ru/th/vi，避免同语言三四个候选）。
     */
    private val DOLPHIN_LANGS: List<Pair<String, Int>> = listOf(
        // ⚠️ v2.1.246：**「自动」必须登记在这里**（用户 2026-10-05 要求
        //    「AI 字幕面板自动选项路由到内置模型」）。
        //
        // 病根：`SherpaAsrManager.sherpaLanguages` 里「自动」那条 chip 的
        //   `modelId = DOLPHIN_DIR`，而 chips 的选中判定是
        //   `"$code@$modelId" == "$langCode@$activeModelId"`；
        //   但 `activeModelId` 走 `resolveExtModel(ctx, "auto")?.dirName ?: "builtin"`，
        //   而 `candidatesByKey("auto")` 原先**返回空**（下表没有 auto 键）
        //   → 解析出 `null` → 回落成 `"builtin"`
        //   → chip 拼出 `auto@dolphin-base-ctc-multi-lang-int8`、当前值拼出 `auto@builtin`
        //   → **永不相等 → 「自动」永远不高亮**（识别本身其实已走内置 Dolphin，
        //     因为 `createRecognizer` 对 "auto" 有短路分支，但用户看不到任何选中反馈）。
        //
        // 登记后：`candidatesByKey("auto")` → [Dolphin] → `resolveExtModel` 返回它
        //   → `activeModelId = DOLPHIN_DIR` → 与 chip uid 对齐 → 正常高亮。
        // 顺带让 `isModelReadyFor("auto")` / `isDownloadNeededFor("auto")` 走**显式**候选链路
        //   （此前靠 `cands.isEmpty()` 的兜底分支，结论相同但现在有据可依）。
        "auto" to R.string.asr_lang_auto,  // 自动（Dolphin 自带 LID，真·语种识别）
        // 2026-10-02 新增：**中日韩** —— Dolphin 同样覆盖这三种语言，
        // 因此它们与内置 SenseVoice 形成「同语言多候选」，用户可切换（中文1=内置 / 中文2=Dolphin）。
        // ⚠️ 注：`zh` 与方言 `zh_cn`（普通话）语义重叠，但分属不同组、序号不同，
        //    界面上分别显示为「中文 2」与「普通话」，可按需选用。
        "zh" to R.string.asr_lang_zh,      // 中文
        "ja" to R.string.asr_lang_ja,      // 日语
        "ko" to R.string.asr_lang_ko,      // 韩语
        // 东南亚 / 南亚 / 中东 / 中亚 —— 这些在此前完全没有覆盖
        "id" to R.string.asr_lang_id,      // 印尼语
        "ms" to R.string.asr_lang_ms,      // 马来语
        "my" to R.string.asr_lang_my,      // 缅甸语
        "km" to R.string.asr_lang_km,      // 高棉语
        "lo" to R.string.asr_lang_lo,      // 老挝语
        "jv" to R.string.asr_lang_jv,      // 爪哇语
        "su" to R.string.asr_lang_su,      // 巽他语
        "tl" to R.string.asr_lang_tl,      // 菲律宾语
        "hi" to R.string.asr_lang_hi,      // 印地语
        "ur" to R.string.asr_lang_ur,      // 乌尔都语
        "bn" to R.string.asr_lang_bn,      // 孟加拉语
        "ta" to R.string.asr_lang_ta,      // 泰米尔语
        "te" to R.string.asr_lang_te,      // 泰卢固语
        "gu" to R.string.asr_lang_gu,      // 古吉拉特语
        "mr" to R.string.asr_lang_mr,      // 马拉地语
        "ne" to R.string.asr_lang_ne,      // 尼泊尔语
        "or" to R.string.asr_lang_or,      // 奥里亚语
        "pa" to R.string.asr_lang_pa,      // 旁遮普语
        "ks" to R.string.asr_lang_ks,      // 克什米尔语
        "si" to R.string.asr_lang_si,      // 僧伽罗语
        "ar" to R.string.asr_lang_ar,      // 阿拉伯语
        "fa" to R.string.asr_lang_fa,      // 波斯语
        "uz" to R.string.asr_lang_uz,      // 乌兹别克语
        "kk" to R.string.asr_lang_kk,      // 哈萨克语
        "ky" to R.string.asr_lang_ky,      // 吉尔吉斯语
        "tg" to R.string.asr_lang_tg,      // 塔吉克语
        "az" to R.string.asr_lang_az,      // 阿塞拜疆语
        "ba" to R.string.asr_lang_ba,      // 巴什基尔语
        "ug" to R.string.asr_lang_ug,      // 维吾尔语
        "ps" to R.string.asr_lang_ps,      // 普什图语
        "kab" to R.string.asr_lang_kab,    // 卡拜尔语
        "mn" to R.string.asr_lang_mn       // 蒙古语
    )

    /**
     * Dolphin 覆盖的**22 种中文方言**。
     *
     * key 用 `zh_<地区>` 形式，与 Dolphin 官方的 region code 对齐
     * （`zh-WU` 吴语 / `zh-SICHUAN` 四川话 …），但**大小写与连字符做了本地化**，
     * 避免与「语言键」取值空间混淆。
     */
    private val DOLPHIN_DIALECTS: List<Pair<String, Int>> = listOf(
        "zh_cn" to R.string.asr_sub_zh_cn,            // 普通话（与内置 SenseVoice 的 zh 区分）
        "zh_tw" to R.string.asr_sub_zh_tw,            // 台湾
        "zh_wu" to R.string.asr_sub_zh_wu,            // 吴语
        "zh_sichuan" to R.string.asr_sub_zh_sichuan,  // 四川话
        "zh_shanxi" to R.string.asr_sub_zh_shanxi,    // 山西话
        "zh_anhui" to R.string.asr_sub_zh_anhui,      // 安徽话
        "zh_tianjin" to R.string.asr_sub_zh_tianjin,  // 天津话
        "zh_ningxia" to R.string.asr_sub_zh_ningxia,  // 宁夏话
        "zh_shaanxi" to R.string.asr_sub_zh_shaanxi,  // 陕西话
        "zh_hebei" to R.string.asr_sub_zh_hebei,      // 河北话
        "zh_shandong" to R.string.asr_sub_zh_shandong,// 山东话
        "zh_guangdong" to R.string.asr_sub_zh_gd,     // 广东话
        "zh_shanghai" to R.string.asr_sub_zh_shanghai,// 上海话
        "zh_hubei" to R.string.asr_sub_zh_hubei,      // 湖北话
        "zh_liaoning" to R.string.asr_sub_zh_liaoning,// 辽宁话
        "zh_gansu" to R.string.asr_sub_zh_gansu,      // 甘肃话
        "zh_fujian" to R.string.asr_sub_zh_fujian,    // 福建话
        "zh_hunan" to R.string.asr_sub_zh_hunan,      // 湖南话
        "zh_henan" to R.string.asr_sub_zh_henan,      // 河南话
        "zh_yunnan" to R.string.asr_sub_zh_yunnan,    // 云南话
        "zh_minnan" to R.string.asr_sub_zh_minnan,    // 闽南语
        "zh_wenzhou" to R.string.asr_sub_zh_wenzhou   // 温州话
    )

    /** Dolphin 的全部条目（语言 32 + 方言 22），全部指向同一个 [DOLPHIN_DIR] */
    val DOLPHIN_ALL: List<AsrExtModel> =
        (DOLPHIN_LANGS + DOLPHIN_DIALECTS).map { (k, r) -> dolphin(k, r) }

    // =======================================================================
    // 南亚 —— AI4Bharat IndicConformer（CTC，每语言 188MB / 22 语共享 tokens）
    // =======================================================================
    //
    // ⚠️ 【2026-10-01 已停用】调研后期发现 Dolphin 以 1/15 的体积覆盖了其中 7 种语言
    //    （仅缺 `kn` 卡纳达语），而用户明确选择「只用 Dolphin，放弃 kn」。
    //    因此下列条目**不再登记进 [ALL]**，代码保留以备日后需要时快速恢复
    //    （例如某语言的 Dolphin 识别效果不理想、想换成专用模型）。
    //    恢复方式：把需要的条目加回 [ALL] 即可，装配路径已经支持。
    //
    // 【为什么用它】南亚语言在官方 sherpa-onnx 模型库里是**空白**
    //   （zipformer / NeMo 只有欧洲 + 俄日韩泰粤越），唯一的官方覆盖是 Whisper。
    //   本项目**不走 Whisper**，因此采用社区整理的 IndicConformer：
    //   HuggingFace `parismitaglobalsolutions/indicconformer-sherpa-onnx`
    //   源模型 AI4Bharat IndicConformer（**MIT 许可**），作者已在生产 App 使用，
    //   且**专为 sherpa-onnx 的 Kotlin/Java API 导出**。
    //
    // 【结构】与 transducer 完全不同：
    //   · 每语言一个 `model.int8.onnx`（188MB）—— 没有 encoder/decoder/joiner 三分
    //   · **22 语共享一份 `tokens.txt`（67KB，在仓库根目录）**
    //   · modelType = "ctc"  →  装配用 OfflineNemoEncDecCtcModelConfig
    //
    // 【成本】188MB / 语言。对比 Whisper tiny 是 99MB / 99 语 ——
    //   即「不走 Whisper」的代价约等于「每加一种语言多 188MB」。
    //
    // ✅ 2026-10-01 实测 hf-mirror 可达（带 Range 请求，均返回 HTTP 206）：
    //      tokens.txt            .../67605
    //      hi/model.int8.onnx    .../197595593
    //      ur/model.int8.onnx    .../197585089
    //      ta/model.int8.onnx    .../197595513
    private const val INDIC_REPO = "parismitaglobalsolutions/indicconformer-sherpa-onnx"
    /** 22 语共享的 tokens 落盘目录（各语言条目都指向它，避免重复下载） */
    private const val INDIC_TOKENS_DIR = "indic-shared"
    private const val INDIC_TOKENS_BYTES = 60_000L

    private fun indicConformer(key: String, labelResId: Int) = AsrExtModel(
        key = key,
        labelResId = labelResId,
        dirName = "indic-$key",
        modelType = "ctc",
        encoder = "model.int8.onnx",
        tokens = "tokens.txt",
        tokensDirName = INDIC_TOKENS_DIR,
        sizeMb = 188,
        files = listOf(
            // 模型文件远端在 <lang>/ 子目录下；本地落到本模型自己的目录，不保留那层子目录
            fAny(INDIC_REPO, "$key/model.int8.onnx", 150_000_000L),
            // tokens 在仓库根、22 语共用 → 落到共享目录，避免每种语言重复下一份
            fAny(INDIC_REPO, "tokens.txt", INDIC_TOKENS_BYTES, dir = INDIC_TOKENS_DIR)
        )
    )

    // 22 种印度官方语言中**先落地 8 种高频语言**（用户 2026-10-01 决策）：
    //   hi 印地 · ur 乌尔都 · ta 泰米尔 · bn 孟加拉 ·
    //   mr 马拉地 · te 泰卢固 · gu 古吉拉特 · kn 卡纳达
    // 其余（as/brx/doi/kok/ks/mai/ml/mni/ne/or/pa/sa/sat/sd）可按同一工厂函数继续追加。
    val HINDI = indicConformer("hi", R.string.asr_lang_hi)
    val URDU = indicConformer("ur", R.string.asr_lang_ur)
    val TAMIL = indicConformer("ta", R.string.asr_lang_ta)
    val BENGALI = indicConformer("bn", R.string.asr_lang_bn)
    val MARATHI = indicConformer("mr", R.string.asr_lang_mr)
    val TELUGU = indicConformer("te", R.string.asr_lang_te)
    val GUJARATI = indicConformer("gu", R.string.asr_lang_gu)
    val KANNADA = indicConformer("kn", R.string.asr_lang_kn)

    // =======================================================================
    // 泰语
    // =======================================================================

    /**
     * 泰语 —— zipformer transducer（int8 encoder）。
     *
     * ⚠️ 泰语**没有可按文件下载的源**（2026-09-19 实测）：
     *  - `hf-mirror` 对 `csukuangfj/sherpa-onnx-zipformer-thai-2024-06-20` **一律 401**
     *    （`resolve` / `raw` / `api`、`?download=true`、其他镜像域名全部不可用）
     *  - ModelScope 上没有该模型；官方也只发 GitHub releases 的 tar.bz2
     *  → 只能走**整包兜底**：下 664MB 的 tar.bz2，流式解出 int8 组合（≈154MB）后删包。
     *
     * 官方 int8 用法：`encoder…int8.onnx` + **decoder 用 fp32**（decoder 不量化）+ `joiner…int8.onnx` + tokens.txt
     */
    val THAI = AsrExtModel(
        key = "th",
        labelResId = R.string.asr_lang_th,
        dirName = "zipformer-thai-2024-06-20",
        modelType = "transducer",
        encoder = "encoder-epoch-12-avg-5.int8.onnx",
        decoder = "decoder-epoch-12-avg-5.onnx",
        joiner = "joiner-epoch-12-avg-5.int8.onnx",
        tokens = "tokens.txt",
        sizeMb = 154,
        files = emptyList(),
        archive = AsrExtArchive(
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-zipformer-thai-2024-06-20.tar.bz2",
            wanted = mapOf(
                "encoder-epoch-12-avg-5.int8.onnx" to 130_000_000L,
                "decoder-epoch-12-avg-5.onnx" to 4_000_000L,
                "joiner-epoch-12-avg-5.int8.onnx" to 800_000L,
                "tokens.txt" to 20_000L
            ),
            packageMb = 664
        )
    )

    /**
     * 全部已接入的扩展模型（顺序即 UI 中的显示顺序）。
     * 语言键直接复用 `sherpa_lang_code` 取值空间。
     */
    val ALL: List<AsrExtModel> = listOf(
        // 东南亚
        VIETNAMESE, THAI,
        // 欧洲（轻量档：102MB / 10 语）
        RUSSIAN, FRENCH, GERMAN, SPANISH,
        BELARUSIAN, CROATIAN, ITALIAN, POLISH, UKRAINIAN,
        // 欧洲（全量档：640MB / 25 语；16 种为轻量档没有的，
        //       另有 11 条为「同语言多候选」—— 英语 2 条 + 与轻量档重叠的 9 种）
        BULGARIAN, CZECH, DANISH, DUTCH, ESTONIAN, FINNISH, GREEK, HUNGARIAN,
        LATVIAN, LITHUANIAN, MALTESE, PORTUGUESE, ROMANIAN, SLOVAK, SLOVENIAN, SWEDISH,
        ENGLISH_V3, RUSSIAN_V3, FRENCH_V3, GERMAN_V3, SPANISH_V3,
        BELARUSIAN_V3, CROATIAN_V3, ITALIAN_V3, POLISH_V3, UKRAINIAN_V3,
        ENGLISH_FC,
        // 亚洲 40 语 + 中文 22 方言（Dolphin，100MB **一次性覆盖**）
        // ⚠️ 这是本轮扩展的主力：一次性补齐东南亚 / 南亚 / 中东 / 中亚语言与全部中文方言。
        //    全部 54 个条目共用同一个 dirName → 用户下载一次即可使用全部。
        // ⚠️ 原 IndicConformer 南亚 8 语方案已停用（见其定义处的说明），
        //    条目仍在文件中但**不在此列出**。
    ) + DOLPHIN_ALL

    /**
     * 取该语言键的**全部候选模型**（v2.0.208 起一个语言可对应多个模型）。
     *
     * 顺序即优先级：就绪判定与识别器创建取**第一个已就绪**的。
     * 例如俄语的候选是 [FastConformer（102MB 包）, Parakeet v3（640MB 包）] ——
     * 用户下了哪个就用哪个。
     */
    fun candidatesByKey(key: String): List<AsrExtModel> = ALL.filter { it.key == key }

    /** 第一个候选（用于 UI 显示体积等；不代表用户实际会用到哪一个） */
    fun byKey(key: String): AsrExtModel? = ALL.firstOrNull { it.key == key }

    /** 该语言键是否由扩展模型承接（而非内置 SenseVoice） */
    fun isExtKey(key: String): Boolean = byKey(key) != null

    /**
     * UI 语言列表：同一语言键只出现一次（多模型候选合并为一条）。
     * 返回 (语言键, 展示用文案资源) 的有序列表。
     */
    val DISTINCT_LANGS: List<Pair<String, Int>> =
        ALL.map { it.key to it.labelResId }.distinct()

    // =======================================================================
    // v2.0.208：语言选择的「多级收纳」——语区分组
    // =======================================================================

    /**
     * 语区（UI 一级收纳的折叠组）。
     * [sortOrder] 决定 UI 中的显示顺序（常用置顶）。
     */
    enum class AsrLangGroup(val labelRes: Int, val sortOrder: Int) {
        COMMON(R.string.asr_group_common, 0),
        EAST_ASIA(R.string.asr_group_east_asia, 1),
        CHINESE(R.string.asr_group_chinese, 2),
        SEA(R.string.asr_group_sea, 3),
        SOUTH_ASIA(R.string.asr_group_south_asia, 4),
        MIDEAST(R.string.asr_group_mideast, 5),
        EUROPE(R.string.asr_group_europe, 6),
        OTHER(R.string.asr_group_other, 7)
    }

    /**
     * 语言 → 语区映射。
     *
     * ⚠️ 只列「非欧洲、非中文方言」的例外情况 + 需要归到特定的；
     *    未列出的默认按 [defaultGroupOf] 推断（欧洲键 → EUROPE，`zh_*` → CHINESE）。
     */
    private val GROUP_OVERRIDE: Map<String, AsrLangGroup> = buildMap {
        // 东亚
        put("ja", AsrLangGroup.EAST_ASIA)
        put("ko", AsrLangGroup.EAST_ASIA)
        put("mn", AsrLangGroup.EAST_ASIA)
        // 东南亚
        for (k in listOf("vi", "th", "id", "ms", "my", "km", "lo", "jv", "su", "tl")) {
            put(k, AsrLangGroup.SEA)
        }
        // 南亚
        for (k in listOf("hi", "ur", "bn", "ta", "te", "gu", "mr", "ne", "or", "pa", "ks", "si")) {
            put(k, AsrLangGroup.SOUTH_ASIA)
        }
        // 中东与中亚（含高加索）
        for (k in listOf("ar", "fa", "uz", "kk", "ky", "tg", "az", "ug", "ps")) {
            put(k, AsrLangGroup.MIDEAST)
        }
        // 其他（欧洲边缘/北非，Dolphin 独有）
        for (k in listOf("kab", "ba")) {
            put(k, AsrLangGroup.OTHER)
        }
    }

    /**
     * 「常用」语言 —— 这些**额外**出现在 [AsrLangGroup.COMMON] 组里做快捷入口
     * （原语区里仍然保留，不移动）。选取依据：中文用户最高频的影视语言。
     */
    private val COMMON_KEYS: Set<String> = setOf(
        "auto",                                // 自动（语种识别）
        "zh", "en", "ja", "ko", "yue",        // 内置五语（用户 2026-10-02 要求常用含中日韩）
        "zh_cn", "zh_sichuan", "zh_minnan", "zh_shanghai", "zh_gd",  // 高频中文方言
        "vi", "th", "ru", "ar", "hi", "fr", "de", "es", "pt", "id"
    )

    /**
     * 「常用」组的判定：**按语言键**（而非 group 字段）。
     *
     * 原因：中文/日语/韩语的 group 是 CHINESE / EAST_ASIA，但用户要求它们出现在常用组里。
     * 用键集判定后，常用组成为**与其它语区重叠**的快捷入口——
     * 同一语言既在常用里一键可达，也在原语区里保持完整。
     */
    fun isCommonKey(key: String): Boolean = key in COMMON_KEYS

    /**
     * v2.1.224：把内部 modelId / dirName 换成**用户可读的友好名**。
     *
     * 这些 id 是仓库目录名（如 `dolphin-base-ctc-multi-lang-int8`），
     * 直接显示在「按模型分类」的组标题里会非常长且难懂。
     */
    fun friendlyModelName(modelId: String): String = when {
        modelId == "builtin" -> "Dolphin（内置）"
        modelId.startsWith("dolphin") -> "Dolphin"
        modelId.startsWith("parakeet") -> "Parakeet v3"
        modelId.contains("fast-conformer") -> "FastConformer"
        modelId.contains("thai") -> "Zipformer 泰语"
        modelId.contains("zipformer-vi") -> "Zipformer 越南语"
        modelId.contains("indicconformer") -> "IndicConformer"
        else -> modelId.removePrefix("sherpa-onnx-")
    }

    private fun defaultGroupOf(key: String): AsrLangGroup = when {
        key.startsWith("zh_") -> AsrLangGroup.CHINESE
        // ⚠️ `zh` 不匹配上面的 `zh_` 前缀分支，若不单独列出就会落进 else → 被误判成 EUROPE
        key == "zh" -> AsrLangGroup.CHINESE
        key == "yue" -> AsrLangGroup.CHINESE
        // v2.1.246：`auto`（语种识别）不属于任何语区 —— 显式归 COMMON。
        // 漏了它会落进 else → EUROPE，于是「按语区」视角会在「欧洲」组里
        // 冒出一条「自动」，语义完全不对。
        key == "auto" -> AsrLangGroup.COMMON
        GROUP_OVERRIDE.containsKey(key) -> GROUP_OVERRIDE.getValue(key)
        else -> AsrLangGroup.EUROPE       // ru/fr/de/es/be/hr/it/pl/uk + Parakeet v3 的 16 种
    }

    /** 一条 UI 语言选项（含归属语区） */
    data class AsrLangOption(
        val key: String,
        val labelResId: Int,
        val group: AsrLangGroup
    )

    /** 全部语言选项（按语区 → 原始登记顺序排列） */
    val LANG_OPTIONS: List<AsrLangOption> =
        DISTINCT_LANGS.map { (k, r) -> AsrLangOption(k, r, defaultGroupOf(k)) }

    /**
     * 按语区收纳后的分组列表：`(语区, 该语区下的语言)`，已按 [AsrLangGroup.sortOrder] 排序。
     *
     * 「常用」组是与其它组**重叠**的快捷入口（同一个语言会同时出现在常用与它的原语区里）——
     * 这是刻意的：既方便高频语言一键可达，又不破坏「按语区找全」的完整性。
     */
    fun groupedOptions(): List<Pair<AsrLangGroup, List<AsrLangOption>>> {
        val byGroup = LANG_OPTIONS.groupBy { it.group }
        val out = ArrayList<Pair<AsrLangGroup, List<AsrLangOption>>>()

        // 常用（快捷入口，保持 LANG_OPTIONS 的原始顺序以便稳定）
        val common = LANG_OPTIONS.filter { it.key in COMMON_KEYS }
        if (common.isNotEmpty()) out += AsrLangGroup.COMMON to common

        // 其余语区按 sortOrder
        AsrLangGroup.values()
            .filter { it != AsrLangGroup.COMMON }
            .sortedBy { it.sortOrder }
            .forEach { g ->
                val list = byGroup[g].orEmpty()
                if (list.isNotEmpty()) out += g to list
            }
        return out
    }

    /**
     * 按**模型**收纳 —— 移至 `SherpaAsrManager.groupedByModel()`：
     * 该视角需要 chip 列表（含 modelId），而 chips 定义在 Manager 里，
     * 放那边可以避免两个 object 之间的循环依赖。
     */
}
