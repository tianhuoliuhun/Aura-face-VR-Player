package com.example.vr

/**
 * v2.8.3：**HLS 主清单（master playlist）解析** —— 纯逻辑，不依赖 Android，可直接单测。
 *
 * ## 为什么需要它
 * 网页嗅探拿到的往往只是 `master.m3u8`（一个几十行的文本清单）：
 * ```
 * #EXTM3U
 * #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="中文",LANGUAGE="zh",URI="audio/zh.m3u8"
 * #EXT-X-STREAM-INF:BANDWIDTH=5200000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
 * 1080p/index.m3u8
 * #EXT-X-STREAM-INF:BANDWIDTH=1100000,RESOLUTION=854x480
 * 480p/index.m3u8
 * ```
 * 此前把它直接交给播放器，问题有两个：
 *  1. **用户看不到有几档码率**，也选不了 —— 列表里只有一条「主机 · HLS」，
 *     实际播放清晰度由内核自己决定（Exo 从低码率起播逐步升档，前十几秒是糊的）。
 *  2. **主清单里声明的独立音轨 / 外挂字幕轨完全没人解析** ——
 *     `#EXT-X-MEDIA:TYPE=AUDIO` / `TYPE=SUBTITLES` 的 URI 就这样被丢掉，
 *     于是「网页字幕导出」在 HLS 站点上永远抓不到东西。
 *
 * ## 只解析、不判断可用性
 * 变体地址是否真的能拉、码率标注是否属实，都要靠 [StreamProbe] 实际请求。
 * 本文件纯文本处理，因此可以放心单测（见 `HlsPlaylistTest`）。
 */
object HlsPlaylist {

    /** 主清单里的一档码率（`#EXT-X-STREAM-INF` + 紧随其后的 URI 行）。 */
    data class Variant(
        val uri: String,
        val bandwidth: Long,
        /** 形如 `1920x1080`；清单没写时为 null */
        val resolution: String?,
        val codecs: String?,
        /** 关联的 `#EXT-X-MEDIA` AUDIO 组 id */
        val audioGroup: String?,
        val frameRate: Float?,
    ) {
        /** 纵向像素数（1080P / 720P…）；解析不出返回 null */
        val heightPx: Int?
            get() = resolution?.substringAfter('x', "")?.toIntOrNull()

        /** `1080P`；清单只给了 RESOLUTION 才有值 */
        val qualityLabel: String?
            get() = heightPx?.let { "${it}P" }

        /** `2.4 Mbps` / `860 kbps` —— 码率标注比分辨率更能反映"糊不糊" */
        val bandwidthLabel: String
            get() = when {
                // 不用 String.format：它跟随默认 Locale，德语等区域会输出 `2,4 Mbps`，
                // 而这里只是给用户看一个近似量级，自己取一位小数更可控。
                bandwidth >= 1_000_000 ->
                    "${kotlin.math.round(bandwidth / 100_000.0) / 10.0} Mbps"
                bandwidth >= 1_000 -> "${bandwidth / 1_000} kbps"
                else -> "$bandwidth bps"
            }

        /** 列表展示用：`1080P · 5.2 Mbps`，缺分辨率时只显示码率 */
        val label: String
            get() = qualityLabel?.let { "$it · $bandwidthLabel" } ?: bandwidthLabel
    }

    /**
     * `#EXT-X-MEDIA` 声明的一条渲染轨（独立音轨 / 字幕 / 隐藏字幕）。
     *
     * ⚠️ `TYPE=CLOSED-CAPTIONS` 是**内嵌在视频流里**的 EIA-608/708，没有 URI
     * （规范规定此时不得有 URI），因此不能当可下载字幕用。
     */
    data class Rendition(
        val type: String,
        val groupId: String?,
        val name: String?,
        val language: String?,
        val uri: String?,
        val isDefault: Boolean,
        val forced: Boolean,
    ) {
        /** 展示名：`中文 (zh)`；名称缺失时退回语言或类型 */
        val label: String
            get() {
                val n = name?.trim()?.takeIf { it.isNotEmpty() }
                val l = language?.trim()?.takeIf { it.isNotEmpty() }
                return when {
                    n != null && l != null -> "$n ($l)"
                    n != null -> n
                    l != null -> l
                    else -> type.lowercase()
                }
            }
    }

    /** 解析结果。 */
    data class Master(
        val variants: List<Variant>,
        val renditions: List<Rendition>,
    ) {
        /** 可直接下载的独立音轨 */
        val audioTracks: List<Rendition>
            get() = renditions.filter { it.type.equals("AUDIO", true) && !it.uri.isNullOrBlank() }

        /** 外挂字幕轨（`TYPE=SUBTITLES`，带 URI） */
        val subtitleTracks: List<Rendition>
            get() = renditions.filter { it.type.equals("SUBTITLES", true) && !it.uri.isNullOrBlank() }

        /** 最高码率档；空清单返回 null */
        fun best(): Variant? = variants.maxByOrNull { it.bandwidth }
    }

    /** 是否含主清单特征（`#EXT-X-STREAM-INF` 或 `#EXT-X-MEDIA`）。 */
    fun isMaster(text: String): Boolean {
        if (text.isBlank()) return false
        // 只看前 64KB：主清单本身很小，超大文本说明根本不是清单
        val head = text.take(64 * 1024)
        return head.contains("#EXT-X-STREAM-INF") || head.contains("#EXT-X-MEDIA:")
    }

    /**
     * 解析主清单。
     *
     * @param text 清单全文
     * @param baseUrl 清单自身的 URL —— 用来把相对路径（`1080p/index.m3u8`）绝对化。
     *   这是必须的：HLS 里的 URI 绝大多数是相对路径，直接丢给播放器会 404。
     * @return 不含任何变体时返回 null（说明这不是主清单，或是坏清单）
     */
    fun parseMaster(text: String, baseUrl: String): Master? {
        if (text.isBlank()) return null
        val variants = ArrayList<Variant>()
        val renditions = ArrayList<Rendition>()

        val lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")

        // 待消费的 STREAM-INF 属性；下一行非注释内容就是它的 URI
        var pending: Map<String, String>? = null

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) {
                pending = splitAttributes(line.substringAfter(':'))
                continue
            }
            if (line.startsWith("#EXT-X-MEDIA:", ignoreCase = true)) {
                val a = splitAttributes(line.substringAfter(':'))
                val type = a["TYPE"] ?: continue
                val uri = a["URI"]?.takeIf { it.isNotBlank() }?.let { resolveUri(baseUrl, it) }
                renditions += Rendition(
                    type = type,
                    groupId = a["GROUP-ID"],
                    name = a["NAME"],
                    language = a["LANGUAGE"],
                    uri = uri,
                    isDefault = a["DEFAULT"].equals("YES", true),
                    forced = a["FORCED"].equals("YES", true),
                )
                continue
            }
            // 其它 `#` 开头的是标签（#EXT-X-VERSION / #EXT-X-I-FRAME-STREAM-INF 等），跳过
            if (line.startsWith("#")) continue

            // 普通行 —— 只有前面刚读到一个 STREAM-INF 时才是变体 URI
            val attrs = pending ?: continue
            pending = null
            variants += Variant(
                uri = resolveUri(baseUrl, line),
                bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                resolution = attrs["RESOLUTION"]?.takeIf { it.contains('x') },
                codecs = attrs["CODECS"],
                audioGroup = attrs["AUDIO"],
                frameRate = attrs["FRAME-RATE"]?.toFloatOrNull(),
            )
        }

        if (variants.isEmpty()) return null
        // 码率高的在前：用户点开就是要看最清楚的
        return Master(variants.sortedByDescending { it.bandwidth }, renditions)
    }

    /**
     * 从**媒体播放列表**里取第一个分片地址。
     *
     * 用途：`#EXT-X-MEDIA:TYPE=SUBTITLES` 指向的通常是又一个 m3u8（WebVTT 分片清单），
     * 要拿到真正含字幕文本的 `.vtt` 还得再下一层。这里只取第一片 ——
     * 用于「只想确认这条轨有没有内容」的场合。
     *
     * ⚠️ 要**完整导出字幕**请用 [parseSegments] 取全部分片：只抓第一片只能覆盖
     * 前几秒，导出的 SRT 会在开头就断掉（用户会以为是字幕文件坏了）。
     *
     * @return 绝对化后的分片 URL；没有可读行时返回 null
     */
    fun firstSegment(text: String, baseUrl: String): String? {
        if (text.isBlank()) return null
        for (raw in text.replace("\r\n", "\n").split("\n")) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            return resolveUri(baseUrl, line)
        }
        return null
    }

    /** 媒体播放列表里的一个分片。 */
    data class Segment(val uri: String, val durationMs: Long)

    /**
     * 解析媒体播放列表的全部**媒体分片**（忽略 `#EXT-X-MAP` 初始化段）。
     *
     * `#EXTINF:6.006,` 给出该片时长（秒，可带小数）—— 这个时长是**字幕分片合并的关键**：
     * HLS 的 WebVTT 分片里 cue 用的是分片内局部时间（从 0 开始），
     * 合并时必须按累计时长/`X-TIMESTAMP-MAP` 平移，否则整片字幕会全部叠在开头几秒。
     *
     * @return 分片顺序列表；不是媒体列表时返回空
     */
    fun parseSegments(text: String, baseUrl: String): List<Segment> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<Segment>()
        var pendingMs = 0L
        for (raw in text.replace("\r\n", "\n").split("\n")) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                val sec = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                // ⚠️ 必须 round 而不是直接 toLong()：`6.006 * 1000` 的 Double 结果是
                //    6005.999999999999，截断会得到 6005 —— 每片少 1ms，600 片累计差 0.6 秒。
                pendingMs = kotlin.math.round(sec * 1000.0).toLong()
                continue
            }
            if (line.startsWith("#")) continue
            out += Segment(resolveUri(baseUrl, line), pendingMs)
            pendingMs = 0L
        }
        return out
    }

    /**
     * 解析一行属性列表：`BANDWIDTH=5200000,RESOLUTION=1920x1080,CODECS="avc1.64,mp4a.40.2"`。
     *
     * ⚠️ **不能直接按逗号 split**：`CODECS` 的值本身含逗号（`"avc1.640028,mp4a.40.2"`），
     * 直接切会把一个值拆成两半、后面所有键整体错位。必须做引号状态机。
     */
    fun splitAttributes(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val sb = StringBuilder()
        var inQuotes = false
        val parts = ArrayList<String>()
        for (c in s) {
            when {
                c == '"' -> { inQuotes = !inQuotes; sb.append(c) }
                c == ',' && !inQuotes -> { parts += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) parts += sb.toString()

        for (p in parts) {
            val idx = p.indexOf('=')
            if (idx <= 0) continue
            val k = p.substring(0, idx).trim().uppercase()
            val v = p.substring(idx + 1).trim().trim('"')
            if (k.isNotEmpty()) out[k] = v
        }
        return out
    }

    /**
     * 相对 URI 绝对化。
     *
     * 用 `java.net.URI.resolve` 处理规范里的各种相对形式（`a/b.m3u8`、`../x.m3u8`、`/root.m3u8`）；
     * 它抛异常时（清单里偶见未转义的空格、中文文件名）退化为手工拼接，
     * 保证不会因为一行脏数据让整个清单解析失败。
     */
    fun resolveUri(base: String, ref: String): String {
        val r = ref.trim()
        if (r.startsWith("http://", true) || r.startsWith("https://", true)) return r
        if (r.startsWith("//")) return "https:" + r
        return try {
            java.net.URI(base).resolve(r).toString()
        } catch (_: Throwable) {
            val dir = base.substringBeforeLast('/', "")
            if (r.startsWith("/")) {
                val origin = base.substringBefore("://").substringBefore("/") + "://" +
                    base.substringAfter("://").substringBefore('/')
                origin + r
            } else "$dir/$r"
        }
    }
}
