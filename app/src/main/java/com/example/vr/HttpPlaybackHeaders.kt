package com.example.vr

/**
 * v2.6.5：**网页播放的请求头上下文**（Referer / User-Agent 的跨层传递通道）。
 *
 * ## 为什么需要它
 * 网页嗅探到的直链几乎都**不能裸放**：CDN 普遍校验 `Referer` 与 `User-Agent`，
 * 用 Exo 的默认 UA、无 Referer 去请求，直接返回 **403**
 * （实测日志：`HttpDataSource$InvalidResponseCodeException: Response code: 403`
 *  → 回退 MPV 后仍是 `loading failed`）。
 *
 * 数据源在 `SmbDataSource` 里创建，拿不到「这个 URL 是从哪个页面嗅探来的」这一信息，
 * 所以用一个进程级单例把上下文传下去。**只在网页/URL 播放期间有值**，
 * 本地文件播放时两个字段都是 null，数据源行为与以前完全一致。
 *
 * ⚠️ 这是"进程级可变全局状态"，但写入点只有两处（嗅探播放 / URL 播放），
 * 且播放器重建是串行的，不存在并发竞态；用完由调用方及时 [clear]。
 */
object HttpPlaybackHeaders {

    /** 页面地址（嗅探时记录）。用作 `Referer` */
    @Volatile
    var referer: String? = null
        private set

    /** 与嗅探层一致的桌面 UA。很多 CDN 会拒绝空/异常 UA */
    @Volatile
    var userAgent: String? = null
        private set

    /** 嗅探播放时的桌面 UA（与 WebSnifferScreen 的 WebSettings 保持一致） */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /**
     * 设置网页播放上下文。
     *
     * @param pageUrl 嗅探来源页（可为 null —— 手动粘贴 URL 时没有页面）
     * @param streamUrl 直链（用于在没有 pageUrl 时退化出同源 Referer）
     */
    fun forWebPlayback(pageUrl: String?, streamUrl: String) {
        userAgent = DESKTOP_UA
        referer = pageUrl?.takeIf { it.isNotBlank() }
            ?: runCatching {
                // 没有来源页时用直链的 origin 作 Referer —— 部分站的 CDN 只做「同源」宽松校验
                val u = java.net.URI(streamUrl)
                "${u.scheme}://${u.host}/"
            }.getOrNull()
    }

    /**
     * 组装成数据源要的 request properties；未设置过网页上下文时返回 null。
     *
     * ## 为什么要塞一个 `Range: bytes=0-`
     * 个别 CDN 对**不带 Range 的完整 GET 直接 403 `Access denied`**
     * （实测：`UA + Referer` 无 Range → 403；加上 `Range: bytes=0-` → 206）。
     * 而 Exo 播放 MP4 的**首个请求恰恰不带 Range**（`position=0` 且 `length=UNSET`），
     * 于是必然被拒 —— 这是"其它播放器能放、我们放不了"的典型差异。
     *
     * ⚠️ 安全性：Media3 的 `DefaultHttpDataSource` 在 `dataSpec.position != 0` 时会
     * **用自己的 Range 覆盖**本默认值（它是在合并 request properties **之后**才 set 的），
     * 因此拖动进度条时的分段请求不受影响。
     */
    fun asRequestProperties(): Map<String, String>? {
        val m = LinkedHashMap<String, String>()
        referer?.takeIf { it.isNotBlank() }?.let { m["Referer"] = it }
        userAgent?.takeIf { it.isNotBlank() }?.let { m["User-Agent"] = it }
        if (m.isEmpty()) return null
        m["Range"] = "bytes=0-"
        return m
    }

    /** 是否处于「网页/URL 播放」上下文（决定数据源要不要走缓存层） */
    val isActive: Boolean
        get() = !referer.isNullOrBlank() || !userAgent.isNullOrBlank()

    /** 播放结束/切换到本地文件时必须调用，避免把网页头带到下一次播放 */
    fun clear() {
        referer = null
        userAgent = null
    }
}
