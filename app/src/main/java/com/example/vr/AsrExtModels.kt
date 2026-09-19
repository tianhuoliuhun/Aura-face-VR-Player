package com.example.vr

import com.example.R

/**
 * 扩展 ASR 模型中的单个文件（下载 URL + 期望最小字节数，用于校验完整性）。
 *
 * 下载走 hf-mirror 的 `resolve/main/<文件名>` 直链（支持按文件、支持 Range 续传）。
 */
data class AsrExtFile(
    val name: String,
    val url: String,
    /** 期望最小字节数：中断/被截断的残缺文件不达标，避免误判"已下载" */
    val minBytes: Long
)

/**
 * 「扩展语言」ASR 模型注册表条目（sherpa-onnx **离线 transducer**）。
 *
 * 背景：内置 SenseVoice 只覆盖 中/英/日/韩/粤；其余语言各需一个独立离线模型。
 * 本表逐个接入，**不内置进 APK**（体积原因），由用户在设置面板按需下载到
 * `filesDir/sherpa_models/ext-<dirName>`。
 *
 * 设计约定（均为实测踩过的坑，勿凭直觉改）：
 * 1. **文件名必须逐模型写死**：sherpa 各模型命名风格不统一
 *    （vi 是 `encoder-epoch-12-avg-8.int8.onnx`，ru 是 `encoder.int8.onnx`），
 *    不能靠拼字符串推导。
 * 2. **int8 通常只量化 encoder**：decoder 多数仍是 fp32（vi 的 decoder 就是 fp32），
 *    因此"文件名带不带 .int8"要逐个确认。
 * 3. 下载源目前**只有 hf-mirror 能按文件取**；官方 GitHub releases 只有整包 tar.bz2
 *    （300~680MB，且 tar.bz2 无法部分下载），不适合端上按需下载。
 * 4. 每个模型上线前必须实测 hf-mirror 可达（不可达会 401，见 MEMORY.md 2026-09-19）。
 */
data class AsrExtModel(
    /** 与 `sherpa_lang_code` 复用同一取值空间；同时作为目录/展示标识 */
    val key: String,
    val labelResId: Int,
    /** 落盘目录名：filesDir/sherpa_models/ext-<dirName> */
    val dirName: String,
    /** sherpa 的 modelType：zipformer 用 "transducer"，NeMo 用 "nemo_transducer" */
    val modelType: String,
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
    /** 展示用体积（MB，向上取整） */
    val sizeMb: Int,
    val files: List<AsrExtFile>,
    /**
     * 整包回退方案：**仅在没有「可按文件下载」的源时使用**（如泰语）。
     * 有 [files] 时优先走按文件下载。
     */
    val archive: AsrExtArchive? = null
)

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

    // ===== ru / fr / de / es：四语**共用**一个 NeMo FastConformer（20k int8）=====
    //
    // 为什么**不用** parakeet-tdt-0.6b-v3-int8（639MB）：
    //  ① 体积过大，手机上下载体验极差、极易半途失败；
    //  ② 它**只有 hf-mirror 一条「按文件」源**，而该源在真机上会返回 **401**——
    //     设备实测日志：`encoder.int8.onnx HTTP 401` / `下载不完整：0 字节`
    //     （同一 URL 在沙箱出口 IP 却是 206 → 说明 hf-mirror 按**出口 IP / 缓存命中**
    //      区别对待，未命中就回源失败，属**不可靠源**）。
    // 本模型为官方同门 NeMo FastConformer：**一个包覆盖 ru / de / es / fr**
    // （另有 en / hr / it / pl / uk），整包 102MB、解压后 ≈132MB，
    // 走 **GitHub releases**（实测可达且支持 Range，不依赖 hf-mirror 的按文件源）。
    // 文件命名与 NeMo 规范一致（encoder/decoder/joiner.int8.onnx + tokens.txt），
    // 用法同为 modelType = "nemo_transducer"。
    private const val FASTCONF_DIR = "nemo-fast-conformer-20k-int8"
    private const val FASTCONF_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-nemo-fast-conformer-transducer-be-de-en-es-fr-hr-it-pl-ru-uk-20k-int8.tar.bz2"

    /** 四个语言键指向同一 [dirName] → 下载一次，四语通用（就绪状态也自动共享） */
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

    val RUSSIAN = fastConformer("ru", R.string.asr_lang_ru)
    val FRENCH = fastConformer("fr", R.string.asr_lang_fr)
    val GERMAN = fastConformer("de", R.string.asr_lang_de)
    val SPANISH = fastConformer("es", R.string.asr_lang_es)

    /**
     * 泰语 —— zipformer transducer（int8 encoder）。
     *
     * ⚠️ 泰语**没有可按文件下载的源**（2026-09-19 实测）：
     *  - `hf-mirror` 对 `csukuangfj/sherpa-onnx-zipformer-thai-2024-06-20` **一律 401**
     *    （`resolve` / `raw` / `api`、`?download=true`、其他镜像域名全部不可用）
     *  - ModelScope 上没有该模型；官方也只发 GitHub releases 的 tar.bz2
     * → 只能走**整包兜底**：下 664MB 的 tar.bz2，流式解出 int8 组合（≈154MB）后删包。
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
    val ALL: List<AsrExtModel> =
        listOf(VIETNAMESE, RUSSIAN, FRENCH, GERMAN, SPANISH, THAI)

    fun byKey(key: String): AsrExtModel? = ALL.firstOrNull { it.key == key }

    /** 该语言键是否由扩展模型承接（而非内置 SenseVoice） */
    fun isExtKey(key: String): Boolean = byKey(key) != null
}
