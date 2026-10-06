package com.example.vr

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.ssa.SsaParser

/**
 * **给 EXO 补上内嵌 ASS/SSA 字幕支持**（v2.1.248 新增）。
 *
 * ## 为什么需要这个类
 *
 * `androidx.media3:media3-extractor:1.4.1` **其实自带** `ssa/SsaParser`
 * （同包的 `ssa/SsaDialogueFormat`、`ssa/SsaStyle` 也都在，实测 classes.jar 里
 * 有 `SsaParser.class`），但 **`DefaultSubtitleParserFactory` 没有把它登记进去** ——
 * 反编译 `DefaultSubtitleParserFactory` 只看到 6 个 MIME 字面量：
 *
 * ```
 * application/dvbsubs        application/pgs
 * application/ttml+xml       application/x-mp4-vtt
 * application/x-quicktime-tx3g   application/x-subrip
 * ```
 *
 * **没有 `text/x-ssa`**。于是 MKV 里内挂的 ASS/SSA 轨会走到
 * `Unsupported MIME type: text/x-ssa`，字幕静默不显示
 * （播放器不崩、只是内嵌字幕永远出不来）。
 *
 * ## 做法
 *
 * 实现 [SubtitleParser.Factory]，把 `text/x-ssa` 路由到 [SsaParser]，
 * **其余 MIME 原样委托给 [DefaultSubtitleParserFactory]** —— 这样既补上了 ASS，
 * 又不改变 SRT / VTT / TTML / PGS / DVB / tx3g 的既有行为（零回归）。
 *
 * ## ⚠️ 生效前提
 *
 * 内嵌字幕要在**抽取阶段**就被解析，才会通过 `Player.Listener.onCues` 回调出来。
 * 因此调用点必须同时设 `experimentalParseSubtitlesDuringExtraction(true)`
 * （见 `VRPlayerScreen` 里 EXO 的构造链）。只设 parserFactory 而不开这个开关，
 * 内嵌 ASS 仍然不会显示。
 *
 * ## 与自研 SubtitleParser 的关系（不要混淆）
 *
 * - **本类**（Media3 的 `SubtitleParser.Factory`）：管**容器内封装的**字幕轨。
 * - **`com.example.vr.SubtitleParser`**：管**外挂文件**（`.srt` / `.ass` 文件导入）。
 *
 * 两者名字撞了，但服务的场景完全不同，各改各的，不要试图合并。
 */
class SsaAwareSubtitleParserFactory : SubtitleParser.Factory {

    private val fallback = DefaultSubtitleParserFactory()

    /** ASS / SSA 在容器里的标准 MIME 是 `text/x-ssa`（ASS 与 SSA 共用）。 */
    private fun isSsa(format: Format): Boolean =
        format.sampleMimeType == MimeTypes.TEXT_SSA ||
            format.sampleMimeType == "text/x-ass" ||
            format.codecs?.contains("ass", ignoreCase = true) == true ||
            format.codecs?.contains("ssa", ignoreCase = true) == true

    override fun supportsFormat(format: Format): Boolean =
        isSsa(format) || fallback.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int =
        if (isSsa(format)) SsaParser.CUE_REPLACEMENT_BEHAVIOR
        else fallback.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser =
        if (isSsa(format)) SsaParser() else fallback.create(format)
}
