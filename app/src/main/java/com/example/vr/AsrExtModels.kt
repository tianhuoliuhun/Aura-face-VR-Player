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
    val files: List<AsrExtFile>
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

    /**
     * 全部已接入的扩展模型。
     * 后续按同一结构追加 ru / th / fr / de / es（清单与源见 MEMORY.md 2026-09-19）。
     */
    val ALL: List<AsrExtModel> = listOf(VIETNAMESE)

    fun byKey(key: String): AsrExtModel? = ALL.firstOrNull { it.key == key }

    /** 该语言键是否由扩展模型承接（而非内置 SenseVoice） */
    fun isExtKey(key: String): Boolean = byKey(key) != null
}
