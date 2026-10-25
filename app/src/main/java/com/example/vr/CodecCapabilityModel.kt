package com.example.vr

/**
 * 系统编解码能力报告的**纯数据层 + 格式化层**。
 *
 * ## 为什么单独拆一个文件
 * 探测本身（[CodecCapabilityProbe]）必须调 `MediaCodecList` 这类 android API，
 * 在 JVM 单测里跑不了（本项目 `testOptions` 没有 `returnDefaultValues`）。
 * 但「档位换算 / 码率格式化 / MIME 显示名 / 容器分组 / 报告文本」这些**纯计算**
 * 恰恰是最容易写错、最需要断言的部分（例如 `7680×4320` 到底算 8K 还是 4K、
 * `2130708361` 这种色彩格式值对应什么名字）。
 *
 * 所以本文件**一个 `android.*` 都不 import** —— 单测可以直接 new 出数据断言，
 * 而探测层只负责「把 android 的字段抄过来」。
 */
object CodecCapabilityModel {

    // ===================== 数据结构 =====================

    /** 视频能力。字段与 `MediaCodecInfo.VideoCapabilities` 一一对应。 */
    data class VideoCaps(
        val minWidth: Int,
        val maxWidth: Int,
        val minHeight: Int,
        val maxHeight: Int,
        val widthAlign: Int,
        val heightAlign: Int,
        val minFps: Double,
        val maxFps: Double,
        val minBitrate: Int,
        val maxBitrate: Int,
        /** 原始色彩格式值，名称由 [colorFormatName] 映射（避免在这里 import android）。 */
        val colorFormats: List<Int>,
        /** 已文本化的 profile 名（如 `High` / `Main 10`），level 不展示。 */
        val profiles: List<String>,
        val secure: Boolean,
        val adaptive: Boolean,
        val tunneled: Boolean,
        val maxInstances: Int,
        /**
         * 用 `isSizeSupported()` **实测**出来的最大可用档位，而非标称上限。
         *
         * ⚠️ 这两个数字经常不一样：不少解码器标称 `supportedWidths.upper = 4096`，
         * 但 4096×2160 并不真的能解（`isSizeSupported` 返回 false）。
         * 用户真正想知道的是「我这台机器能放多大的片子」，所以摘要里只认这一组。
         */
        val tierWidth: Int = 0,
        val tierHeight: Int = 0,
        /** 在上述档位下的最大帧率（`getSupportedFrameRatesFor(w,h).upper`）。 */
        val tierMaxFps: Double = 0.0,
        /**
         * [VR_PANORAMA_TIERS] 每一项的实测结果（顺序与常量一致）。
         *
         * 与 [tierWidth]/[tierHeight] 的区别：那组只回答「最高能吃哪一档」，
         * 这组回答「这个具体的 2:1 尺寸能不能解」，是 VR 全景选片的关键依据。
         */
        val vrTiers: List<VrSupport> = emptyList(),
        /**
         * 在 [PANORAMA_HEIGHT] 这个高度下，该组件实际能到多宽
         * （`getSupportedWidthsFor(PANORAMA_HEIGHT).upper`）；0 = 无信息。
         *
         * ## 为什么它比「实测档位」更该常显
         * 「实测档位」只回答「最高能过哪一档」，用户还得自己反推出「那 8192×4096 到底行不行」。
         * 而这个数字直接就是答案：**4096 高度下能到 8192 宽 ⇔ 能吃 8K 全景 2:1**。
         * 它也是唯一能把「block 预算」暴露出来的问法 —— 标称 8192×8192 的组件这里会给 8192，
         * 而标称 8192×4320 却吃不下 8192×4096 的组件这里只给 7680。
         *
         * ⚠️ 高度本身到不了 [PANORAMA_HEIGHT] 时无从谈起，保持 0（UI 不显示）。
         */
        val widthAtPanoramaHeight: Int = 0
    )

    /** 一个 VR 全景档位的实测结果。 */
    data class VrSupport(
        val width: Int,
        val height: Int,
        val supported: Boolean,
        /** 该档下的最大帧率；[supported] 为 false 时为 0。 */
        val maxFps: Double = 0.0,
        /**
         * 不支持时：该高度下解码器实际能到的最大宽度；0 = 无信息（或不适用）。
         *
         * 为什么要单独存：实测过「7680×4320 能解、8192×4096 不能」的机器 ——
         * 两者像素只差 1.1%，标称宽度同样都是 8192，真正卡住的是 block-count。
         * `getSupportedWidthsFor(h)` 才能给出「在 4096 高度下只到 7680 宽」这个答案，
         * 不然用户只会看到「8192×4096 —」而不知道为什么。
         */
        val limitWidth: Int = 0,
        /** 不支持时：该宽度下解码器实际能到的最大高度；0 = 无信息。 */
        val limitHeight: Int = 0
    )

    /** 音频能力。 */
    data class AudioCaps(
        val maxChannels: Int,
        val minSampleRate: Int,
        val maxSampleRate: Int,
        val minBitrate: Int,
        val maxBitrate: Int,
        val tunneled: Boolean,
        val maxInstances: Int
    )

    /**
     * 一个编解码器在一个 MIME 下的能力。
     *
     * ⚠️ 粒度是「codec × mime」而不是「codec」：同一个 codec 实例常常同时注册
     * `video/avc` 与 `video/hevc`，两者的分辨率上限完全不同，合并展示会误导。
     */
    data class CodecEntry(
        val name: String,
        val mime: String,
        val encoder: Boolean,
        val hardware: Boolean,
        val vendor: Boolean,
        val video: VideoCaps? = null,
        val audio: AudioCaps? = null
    ) {
        val isVideo: Boolean get() = video != null
    }

    /** 设备/运行环境摘要。 */
    data class DeviceInfo(
        val model: String,
        val androidRelease: String,
        val sdk: Int,
        val soc: String,
        val abi: String,
        /** 当前播放内核名（EXO / IJK / MPV / SYSTEM）。 */
        val currentEngine: String,
        val mpvInstalled: Boolean
    )

    /**
     * 一组**能力完全相同**的容器扩展名。
     *
     * 分组的目的是避免出现第二份白名单：扩展名一律来自 `MediaFormats` 的集合，
     * 这里只按「哪些内核能开」把签名相同的收成一组
     * （例如 `mp4/m4v/mov/3gp…` 三个内核都能开 → 一组）。
     */
    data class ContainerGroup(
        val label: String,
        val exts: List<String>,
        val exo: Boolean,
        val ijk: Boolean,
        val mpv: Boolean
    ) {
        /** 至少有一个已就绪的内核能开，才算「本机可播」。 */
        fun playable(mpvReady: Boolean): Boolean = exo || ijk || (mpv && mpvReady)
    }

    data class CodecReport(
        val device: DeviceInfo,
        val entries: List<CodecEntry>,
        val containers: List<ContainerGroup>
    ) {
        fun videoDecoders() = entries.filter { it.isVideo && !it.encoder }
        fun videoEncoders() = entries.filter { it.isVideo && it.encoder }
        fun audioEntries() = entries.filter { !it.isVideo }
    }

    // ===================== 分辨率档位 =====================

    /**
     * 已知分辨率档位（从高到低），用于把「标称上限」换成「实测能吃下的档」。
     *
     * 只列行业标准档，中间尺寸（如 2880×1620）不列入 —— 因为用户关心的是
     * 「能不能放 8K / 4K」，而不是某个非标的奇数尺寸。
     */
    val KNOWN_TIERS: List<Pair<Int, Int>> = listOf(
        8192 to 4096,   // 8K 全景（等距柱状 2:1）
        7680 to 4320,   // 8K UHD
        5120 to 2880,   // 5K
        4096 to 2160,   // DCI 4K
        4096 to 2048,   // 4K 全景（等距柱状 2:1）
        3840 to 2160,   // 4K UHD
        2560 to 1440,   // 2K / QHD
        1920 to 1080,   // 1080p
        1280 to 720,    // 720p
        854 to 480,     // 480p
        640 to 360      // 360p
    )

    /**
     * VR 全景片源的主流分辨率，**与上面 [KNOWN_TIERS] 分开单列**。
     *
     * 为什么不只是「加两档进 KNOWN_TIERS 就完事」：等比 2:1 的片子在解码器上的
     * 落点与 16:9 完全不同 —— 宽度 8192 常常先撞到 `supportedWidths.upper`
     * （很多 8K 解码器标称宽只到 7680），而高度 4096 反而还有余量；
     * 4096×2048 同理，它可能被支持、而 3840×2160 被对齐规则判否。
     * 而 [KNOWN_TIERS] 的语义是「最高能吃哪一档」，只会保留一个命中项，
     * 回答不了「8192×4096 这张全景到底能不能硬解」。
     * 所以这几档**逐项各测一次**，结果存 [VideoCaps.vrTiers]。
     *
     * ⚠️ 第三档 `8192×8192` 是**方形 8K**（鱼眼 / VR180 展开 / cube map 摊平），
     * 不是 2:1 等距柱状。它存在的目的不是「常见」，而是**作为能力标尺**：
     * 能吃这一档的组件，其 block 预算足以覆盖任何 8192 宽的 2:1 全景；
     * 反过来标称 `8192×8192` 的组件必然能吃 `8192×4096`。放在最后，避免抢 2:1 的版面。
     */
    val VR_PANORAMA_TIERS: List<Pair<Int, Int>> = listOf(
        8192 to 4096,   // 8K 全景（等距柱状 2:1）
        4096 to 2048,   // 4K 全景（等距柱状 2:1）
        8192 to 8192    // 8K 方形（鱼眼 / VR180 / cube map）
    )

    /**
     * [VideoCaps.widthAtPanoramaHeight] 的探测高度。
     *
     * 取 4096 而不是 8192：8K 全景的标准形态是 `8192×4096`，**「4096 高能到多宽」
     * 就是「能不能吃 8K 全景」的直接答案**；而 8192 高度在移动 SoC 上几乎无人能到，
     * 拿它当尺子会让所有解码器都显示 0（无信息）。
     */
    const val PANORAMA_HEIGHT: Int = 4096

    fun pixels(w: Int, h: Int): Long = w.toLong() * h.toLong()

    /** `7680×4320` —— 用全角乘号，与 UI 里其它尺寸文案一致。 */
    fun resolutionText(w: Int, h: Int): String = if (w <= 0 || h <= 0) "—" else "$w×$h"

    /**
     * 档位简称。判据用**像素数**而不是宽高相等，
     * 这样 4096×2160（DCI 4K）与 3840×2160（UHD 4K）都能落到 4K。
     */
    fun tierName(w: Int, h: Int): String {
        val p = pixels(w, h)
        return when {
            p >= pixels(7680, 4320) -> "8K"
            p >= pixels(5120, 2880) -> "5K"
            p >= pixels(3840, 2160) -> "4K"
            p >= pixels(2560, 1440) -> "2K"
            p >= pixels(1920, 1080) -> "1080p"
            p >= pixels(1280, 720) -> "720p"
            p >= pixels(854, 480) -> "480p"
            p >= pixels(640, 360) -> "360p"
            else -> "SD"
        }
    }

    /** `7680×4320 (8K)` */
    fun resolutionWithTier(w: Int, h: Int): String =
        if (w <= 0 || h <= 0) "—" else "${resolutionText(w, h)} (${tierName(w, h)})"

    // ===================== 数值格式化 =====================

    /** 码率：`160 Mbps` / `640 kbps` / `—`。 */
    fun bitrateText(bps: Int): String = when {
        bps <= 0 -> "—"
        // ⚠️ 必须显式 Locale.US：部分地区默认 Locale 用逗号作小数点，
        //    会输出「160,0 Mbps」，在技术文本里看着像千分位、极易误读。
        bps >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f Mbps", bps / 1_000_000.0)
        bps >= 1_000 -> "${bps / 1_000} kbps"
        else -> "$bps bps"
    }

    fun bitrateRangeText(min: Int, max: Int): String {
        if (max <= 0) return "—"
        // 两端同量级时只写一个单位，避免「0.1 Mbps – 160 Mbps」这种啰嗦写法
        return if (min <= 0) bitrateText(max) else "${bitrateText(min)} – ${bitrateText(max)}"
    }

    /** 帧率：整数不显示小数（`30 fps` 而不是 `30.0 fps`）。 */
    fun fpsText(fps: Double): String = when {
        fps <= 0.0 -> "—"
        fps >= 1000.0 -> "—" // 部分驱动返回 960/1e6 这类哨兵值，直接当未知
        fps == fps.toInt().toDouble() -> "${fps.toInt()} fps"
        else -> String.format(java.util.Locale.US, "%.1f fps", fps)
    }

    fun fpsRangeText(min: Double, max: Double): String {
        if (max <= 0.0 || max >= 1000.0) return "—"
        return if (min <= 0.0) fpsText(max) else "${fpsText(min)} – ${fpsText(max)}"
    }

    // ===================== MIME 显示名 =====================

    /**
     * MIME → 人话。未知 MIME 原样返回（**不要**猜成「未知」，
     * 否则新编码出来时用户看不到具体是什么）。
     */
    fun mimeLabel(mime: String): String = when (mime.lowercase()) {
        "video/avc" -> "H.264 / AVC"
        "video/hevc" -> "H.265 / HEVC"
        "video/mp4v-es" -> "MPEG-4 Part 2"
        "video/h263" -> "H.263"
        "video/mpeg2" -> "MPEG-2"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/av01" -> "AV1"
        "video/dolby-vision" -> "Dolby Vision"
        "video/vc1" -> "VC-1"
        "video/x-ms-wmv" -> "WMV"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        "audio/ac3" -> "AC-3"
        "audio/eac3" -> "E-AC-3"
        "audio/raw" -> "PCM"
        "audio/amr-wb" -> "AMR-WB"
        "audio/g711-alaw" -> "G.711 A-law"
        "audio/g711-mlaw" -> "G.711 µ-law"
        else -> mime
    }

    // ===================== 色彩格式 =====================

    /**
     * `MediaCodecInfo.CodecCapabilities.COLOR_Format*` 常量值 → 名称。
     *
     * ⚠️ 这里**必须写死数值**：本文件不能 import `android.media.*`（见文件头）。
     * 数值取自 AOSP `MediaCodecInfo.java`，注释里标注了常量名，改动前请回查。
     */
    fun colorFormatName(value: Int): String = when (value) {
        19 -> "YUV420 Planar"             // COLOR_FormatYUV420Planar
        20 -> "YUV420 PackedPlanar"       // COLOR_FormatYUV420PackedPlanar
        21 -> "YUV420 SemiPlanar"         // COLOR_FormatYUV420SemiPlanar
        39 -> "YUV420 PackedSemiPlanar"   // COLOR_FormatYUV420PackedSemiPlanar
        22 -> "YUV422 Planar"             // COLOR_FormatYUV422Planar
        54 -> "YUVP010"                   // COLOR_FormatYUVP010
        0x7F000789 -> "Surface"           // COLOR_FormatSurface
        0x7F420888 -> "YUV420 Flexible"   // COLOR_FormatYUV420Flexible
        0x7F422888 -> "YUV422 Flexible"   // COLOR_FormatYUV422Flexible
        0x7F444888 -> "YUV444 Flexible"   // COLOR_FormatYUV444Flexible
        0x7F00A888 -> "32bit ABGR8888"    // COLOR_Format32bitABGR8888
        0x7F000007 -> "8bit RGB332"       // COLOR_Format8bitRGB332
        else -> "0x" + Integer.toHexString(value)
    }

    fun colorFormatsText(values: List<Int>): String =
        if (values.isEmpty()) "—" else values.take(4).joinToString(" / ") { colorFormatName(it) } +
            if (values.size > 4) " …" else ""

    // ===================== 摘要 =====================

    /**
     * 本机最强的**硬件视频解码**能力（按实测档位排序，再按该档帧率）。
     *
     * 只统计硬件解码：软件解码器（`c2.android.*`）虽然常常标称 4K，
     * 但播放器里没人真的用它放 8K —— 混在一起会让摘要虚高。
     */
    fun bestHardwareDecoder(report: CodecReport): CodecEntry? =
        report.videoDecoders()
            .filter { it.hardware && (it.video?.tierWidth ?: 0) > 0 }
            .maxWithOrNull(
                // ⚠️ 不要在这里写 it.video!!.xxx：filter 之后 lambda 里仍然拿不到智能转换，
                //    用 `?.` + 默认值最稳（video 非空的条目才会走到比较，默认值只是兜底）。
                compareBy(
                    { pixels(it.video?.tierWidth ?: 0, it.video?.tierHeight ?: 0) },
                    { it.video?.tierMaxFps ?: 0.0 }
                )
            )

    /** `7680×4320 @ 30 fps` */
    fun tierSummary(entry: CodecEntry?): String {
        val v = entry?.video ?: return "—"
        if (v.tierWidth <= 0) return "—"
        return "${resolutionText(v.tierWidth, v.tierHeight)} @ ${fpsText(v.tierMaxFps)}"
    }

    /**
     * VR 全景档位可读文本：`8192×4096 @ 30 fps · 4096×2048 —`。
     *
     * 支持的档给出该尺寸下的真实帧率，不支持的档用 `—`（与项目里其它「无」的写法一致，
     * 不用 ✓/✗ 之类的符号，避免个别字体缺字形显示成豆腐块）。
     *
     * 不支持**且**知道实际上限时追加 `(≤7680×4320)`：光看到一个破折号没人知道差在哪，
     * 而差的那 1.1% 恰好是「换个 2:1 尺寸就行」还是「这台机器完全吃不下 8K」的分水岭。
     * `≤` 只用数字与一个通用符号，不需要为 5 种语言各写一份文案。
     */
    fun vrTiersText(entry: CodecEntry?): String {
        val tiers = entry?.video?.vrTiers ?: return "—"
        if (tiers.isEmpty()) return "—"
        return tiers.joinToString(" · ") { t ->
            val size = resolutionText(t.width, t.height)
            when {
                t.supported -> "$size @ ${fpsText(t.maxFps)}"
                // 只有真的收缩了某一维才显示上限，否则「(≤8192×4096)」等于没说
                (t.limitWidth in 1 until t.width) || (t.limitHeight in 1 until t.height) ->
                    "$size — (≤${resolutionText(t.limitWidth, t.limitHeight)})"
                else -> "$size —"
            }
        }
    }

    /** 该解码器能吃下的 VR 全景档位（只返回支持的），供列表行紧凑展示。 */
    fun vrTiersSupportedText(v: VideoCaps): String =
        v.vrTiers.filter { it.supported }
            .joinToString("/") { resolutionText(it.width, it.height) }

    // ===================== 容器分组 =====================

    /** 扩展名 → 族名（仅用于展示；能力判定一律走 `MediaFormats`）。 */
    private val FAMILY_NAMES = mapOf(
        "mp4" to "MP4 / MOV",
        "mkv" to "MKV / WebM",
        "avi" to "AVI / DivX",
        "flv" to "FLV",
        "ts" to "MPEG-TS",
        "mpg" to "MPEG-PS",
        "ogv" to "Ogg",
        "wmv" to "WMV / ASF",
        "rm" to "RealMedia",
        "iso" to "ISO 光盘镜像",
        "ivf" to "AV1 (IVF)",
        "mp3" to "纯音频",
        "m4a" to "纯音频"
    )

    /**
     * 按「能力签名」把扩展名分组。
     *
     * 输入只来自 `MediaFormats` 的集合 —— 这样新增容器时**不用改这里**，
     * 分组会自动跟上（本项目「同一份数据两处登记」的老坑）。
     */
    fun groupContainers(items: List<Pair<String, ContainerGroup>>): List<ContainerGroup> {
        // items 的 value 里 exts 只有一个元素，这里按 (exo,ijk,mpv) 合并
        val merged = LinkedHashMap<Triple<Boolean, Boolean, Boolean>, MutableList<String>>()
        for ((ext, g) in items) {
            merged.getOrPut(Triple(g.exo, g.ijk, g.mpv)) { mutableListOf() }.add(ext)
        }
        return merged.map { (key, exts) ->
            val sorted = exts.distinct().sorted()
            ContainerGroup(
                label = labelOf(sorted),
                exts = sorted,
                exo = key.first,
                ijk = key.second,
                mpv = key.third
            )
        }.sortedWith(
            // ⚠️ Boolean 不是 Comparable：排序键必须转 Int（写 `{ !it.exo }` 编译不过）
            compareBy<ContainerGroup>({ if (it.exo) 0 else 1 }, { it.label })
        )
    }

    private fun labelOf(exts: List<String>): String {
        // 优先用族名表里**最先出现**的那个（exts 已排序，这里再按族名表顺序取）
        val named = exts.firstOrNull { it in FAMILY_NAMES }
        val base = named?.let { FAMILY_NAMES[it] } ?: exts.firstOrNull().orEmpty().uppercase()
        // 族成员超过 1 个时，把成员数量标出来，避免用户以为只有那一种扩展名
        return if (exts.size > 1) "$base 等 ${exts.size} 种" else base
    }

    // ===================== 报告文本（可复制/粘贴到 issue） =====================

    fun buildReportText(report: CodecReport): String = buildString {
        val d = report.device
        appendLine("=== Aura Lens 系统编解码能力检测 ===")
        appendLine("设备: ${d.model}")
        appendLine("Android: ${d.androidRelease} (API ${d.sdk})")
        appendLine("SoC: ${d.soc}")
        appendLine("ABI: ${d.abi}")
        appendLine("当前内核: ${d.currentEngine}  |  MPV 原生库: ${if (d.mpvInstalled) "已安装" else "未安装"}")
        val best = bestHardwareDecoder(report)
        appendLine("最高硬件解码: ${tierSummary(best)}${best?.let { "  (${it.name})" } ?: ""}")
        appendLine("VR 全景档位: ${vrTiersText(best)}")
        appendLine()

        appendLine("--- 封装支持（按当前内核能力）---")
        for (c in report.containers) {
            val engines = buildList {
                if (c.exo) add("EXO")
                if (c.ijk) add("IJK")
                if (c.mpv) add("MPV")
            }.joinToString("/").ifEmpty { "无" }
            appendLine("${c.exts.joinToString(",")}  ->  $engines")
        }
        appendLine()

        appendLine("--- 视频解码 ---")
        for (e in report.videoDecoders()) {
            val v = e.video ?: continue
            appendLine(
                "${e.name} [${e.mime}] ${if (e.hardware) "HW" else "SW"} " +
                    "标称=${resolutionText(v.maxWidth, v.maxHeight)} " +
                    "实测=${if (v.tierWidth > 0) resolutionWithTier(v.tierWidth, v.tierHeight) else "无"} " +
                    "@${fpsText(v.tierMaxFps)} 码率=${bitrateRangeText(v.minBitrate, v.maxBitrate)} " +
                    vrTiersSupportedText(v).takeIf { it.isNotEmpty() }?.let { "VR=$it " }.orEmpty() +
                    "色格式=${colorFormatsText(v.colorFormats)}" +
                    (if (v.secure) " secure" else "") +
                    (if (v.adaptive) " adaptive" else "") +
                    (if (v.tunneled) " tunneled" else "")
            )
        }
        appendLine()
        appendLine("--- 视频编码 ---")
        for (e in report.videoEncoders()) {
            val v = e.video ?: continue
            appendLine(
                "${e.name} [${e.mime}] ${if (e.hardware) "HW" else "SW"} " +
                    "标称=${resolutionText(v.maxWidth, v.maxHeight)} " +
                    "码率=${bitrateRangeText(v.minBitrate, v.maxBitrate)} " +
                    "帧率=${fpsRangeText(v.minFps, v.maxFps)}"
            )
        }
        appendLine()
        appendLine("--- 音频编码/解码 ---")
        for (e in report.audioEntries()) {
            val a = e.audio ?: continue
            appendLine(
                "${e.name} [${e.mime}] ${if (e.encoder) "ENC" else "DEC"} " +
                    "${if (e.hardware) "HW" else "SW"} " +
                    "声道=${a.maxChannels} 采样率=${a.minSampleRate}-${a.maxSampleRate} " +
                    "码率=${bitrateRangeText(a.minBitrate, a.maxBitrate)} " +
                    "实例=${a.maxInstances}"
            )
        }
    }.trimEnd()
}
