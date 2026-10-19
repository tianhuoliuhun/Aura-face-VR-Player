package com.example.vr

/**
 * v2.6.4：**网页直链嗅探 —— 纯识别逻辑**（不含任何 Compose / Android 依赖，可直接单测）。
 *
 * ## 为什么不做「站点白名单」
 * 按域名维护规则表（一个站点一条正则）的写法，每加一个站点就要加一条规则，维护不动。
 * 这里反过来：**只要请求长得像视频流就抓**（扩展名 + 路径特征 + 清晰度标记），不关心是哪个站。
 *
 * ## 三条判据（都要过）
 *  1. **扩展名/协议特征**：`.m3u8` / `.mp4` / `.flv` / `.mpd` / `.ts`
 *  2. **排除旁支资源**：海报、预览图、缩略图、图标（`poster` / `thumb` / `preview` / `.jpg` `.png` `.webp`）
 *  3. **必须 http(s)**：`data:` / `blob:` 一律不要（前者是内嵌数据，后者拿不到持久地址）
 *
 * ## 隐私：显示名脱敏
 * 直链常带 `?token=xxx` / `?sign=yyy` / `&expires=...`，**显示给用户时只保留
 * 主机名 + 类型 + 清晰度**，完整 URL 只在点击播放时内部传递，不出现在界面、日志与
 * 「最近播放」列表里。见 [displayName]。
 */
object VideoSniffer {

    enum class StreamType(val label: String) {
        HLS("HLS"),
        MP4("MP4"),
        FLV("FLV"),
        DASH("DASH"),
        TS("TS"),
        OTHER("视频"),
    }

    data class SniffedStream(
        val url: String,
        val type: StreamType,
        /** 清晰度标签（如 "1080P"），识别不出为 null */
        val quality: String?,
        /** 主机名（如 cdn.example.com），用于脱敏展示 */
        val host: String,
    )

    /** 明确不是视频流的旁支资源关键词（命中即排除） */
    private val NOISE = listOf(
        "poster", "thumbnail", "thumb", "preview", "sprite", "cover",
        "avatar", "logo", "icon", "banner", "screenshot", "snapshot",
    )

    /** 图片/字幕/字体等非视频扩展名（命中即排除） */
    private val NON_VIDEO_EXT = listOf(
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif", ".svg", ".ico",
        ".vtt", ".srt", ".ass", ".ssa", ".woff", ".woff2", ".ttf", ".css", ".js", ".json",
    )

    /**
     * 判断一个 URL 是否是视频直链；不是返回 null。
     *
     * @param rawUrl WebView 拦截到的原始 URL（可能带 HTML 实体转义）
     */
    fun parse(rawUrl: String): SniffedStream? {
        if (rawUrl.isBlank()) return null
        // HTML 实体 / JS 转义还原（页面内联 JSON 里的直链常是 \u0026 或 &amp;）
        val url = rawUrl
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .trim()
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) return null

        val lower = url.lowercase()
        val path = lower.substringBefore('?').substringBefore('#')
        if (NON_VIDEO_EXT.any { path.endsWith(it) }) return null
        // 排除词只匹配**路径段**，避免把 "https://x.com/preview.mp4" 之外的
        // "https://poster-cdn.example.com/a.mp4" 误杀（主机名含关键词不代表内容是旁支）
        val pathOnly = path.substringAfter("://").substringAfter('/')
        if (NOISE.any { pathOnly.contains(it) }) return null

        val type = when {
            path.endsWith(".m3u8") -> StreamType.HLS
            path.endsWith(".mpd") -> StreamType.DASH
            path.endsWith(".flv") -> StreamType.FLV
            path.endsWith(".mp4") -> StreamType.MP4
            // .ts 只在带分片特征时认（裸 .ts 太多误报：P segment 命名常见）
            path.endsWith(".ts") && (path.contains("segment") || path.contains("/hls") ||
                path.contains(".ts?")) -> StreamType.TS
            // 无扩展名但路径含 m3u8（部分 CDN 用 /playlist/xxx 形式）
            path.contains("m3u8") -> StreamType.HLS
            else -> return null
        }

        val host = runCatching {
            java.net.URI(url).host
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: url.substringAfter("://").substringBefore('/')

        return SniffedStream(
            url = url,
            type = type,
            quality = extractQuality(url),
            host = host,
        )
    }

    /**
     * 提取清晰度：优先 `(\d{3,4})P`（含 `/1440P.mp4` 这种），
     * 退化到 `\d{3,4}p`；取不到返回 null。
     */
    fun extractQuality(url: String): String? {
        val path = url.substringBefore('?')
        // 形如 /1440P.mp4、/1080p/index.m3u8
        Regex("(\\d{3,4})[pP]").find(path)?.let { return it.groupValues[1] + "P" }
        // 形如 ?quality=1080 / &res=720p
        Regex("[?&](?:quality|res|resolution|height)=(\\d{3,4})", RegexOption.IGNORE_CASE)
            .find(url)?.let { return it.groupValues[1] + "P" }
        return null
    }

    /**
     * **隐私脱敏展示名**：主机名 + 类型 + 清晰度，绝不显示完整 URL。
     *
     * 例：`cdn.example.com · MP4 · 1080P`
     *     `video.example.com · HLS`
     */
    fun displayName(s: SniffedStream): String {
        val q = s.quality?.let { " · $it" } ?: ""
        return "${s.host} · ${s.type.label}$q"
    }

    /**
     * 列表去重：同一个 URL 只留一条；同一主机 + 同类型的多条保留（可能是不同清晰度）。
     */
    fun dedupe(streams: List<SniffedStream>): List<SniffedStream> {
        val seen = HashSet<String>()
        val out = ArrayList<SniffedStream>()
        for (s in streams) {
            if (seen.add(s.url)) out.add(s)
        }
        // 清晰度高的排前面，同清晰度按类型（MP4 优先于 HLS：MP4 拖动更准）
        return out.sortedWith(
            compareByDescending<SniffedStream> { it.quality?.filter { c -> c.isDigit() }?.toIntOrNull() ?: 0 }
                .thenBy { it.type.ordinal }
        )
    }
}
