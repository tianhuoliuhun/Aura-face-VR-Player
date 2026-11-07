package com.example.vr

/**
 * v2.6.5：**网页播放的请求头上下文**（Referer / User-Agent 的跨层传递通道）。
 *
 * ## 为什么需要它
 * 网页嗅探到的直链几乎都**不能裸放**：用 Exo 的默认 UA、无 Referer 去请求，
 * 直接返回 **403**（实测日志：`HttpDataSource$InvalidResponseCodeException: Response code: 403`
 *  → 回退 MPV 后仍是 `loading failed`）。
 *
 * 🔴 v2.9.0 实测对照组结论（`2026-10-19.md`）：真正决定能否播的是
 * **「有没有 UA」+「有没有 Range」**，不是 Referer：
 * | 无头 | 仅 Referer | 仅 UA | UA+Referer 无 Range | UA+Referer+Range |
 * | 403 | 403 | **206** | 403 | **206** |
 * 而**直链自己的 origin 当 Referer 反而会触发 403**（页面在站点域名、流在 CDN 域名，
 * CDN 校验的是前者）。所以无来源页时**不发送 Referer**，见 [forWebPlayback]。
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
     * 🔴 v2.9.0 修正「手动粘贴直链时的 Referer 推断缺陷」：旧实现在 [pageUrl] 为空时
     * 退化成 `scheme://<直链 host>/`。但直链在 CDN 域名、页面在站点域名，这个 origin
     * **不是** CDN 要的 Referer，实测会直接 403（见类注释的对照组）。
     * 现在改为「没有来源页就不发 Referer」，只带桌面 UA + `Range: bytes=0-`。
     *
     * 网页嗅探路径（入口 4）由 `WebSnifferScreen.onPlay` 传入 `onPageFinished` 记录的页面地址，
     * 所以 Referer 有值；URL 播放路径（入口 3）留空来源页时为 null，属预期行为。
     *
     * @param pageUrl 嗅探来源页（可为 null —— 手动粘贴 URL 时没有页面）
     * @param streamUrl 直链；保留以兼容两个调用点，**不再参与 Referer 推断**
     */
    @Suppress("UNUSED_PARAMETER")
    fun forWebPlayback(pageUrl: String?, streamUrl: String) {
        userAgent = DESKTOP_UA
        referer = pageUrl?.trim()?.takeIf { it.isNotBlank() }
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

    /**
     * 🔴 v2.9.1：给 **MPV 内核**用的请求头（`--http-header-fields` 的字段列表）。
     *
     * ## 为什么需要单独一个方法
     * [asRequestProperties] 是给 **Exo** 的（`Range: bytes=0-` 靠 Media3 在
     * `dataSpec.position != 0` 时自行覆盖）。MPV 没有这层机制：
     * 它的 Range 由 libavformat 按 seek 位置自己生成，**一旦把 `Range: bytes=0-`
     * 固定塞进 header，拖动进度条就会永远只拿到文件头**（表现为"拖了还是从头播"
     * 或直接卡住）。所以这里**只给 UA / Referer，绝不给 Range**。
     *
     * ## 为什么必须要给
     * v2.6.5 起 Exo 侧的网页流是带头的，但 MPV 创建时一个头都不设 →
     * 网页直链一旦被 `mpvRetryUri` 自动回退到 MPV，MPV 就是**裸请求**
     * （mpv 默认 UA 是 `libmpv`），CDN 直接 403，用户看到"换了个内核还是打不开"。
     * 这是 2026-10-19 实测「回退 MPV 后仍是 loading failed」的直接原因。
     *
     * ⚠️ 格式约束：mpv 的 `http-header-fields` 是**逗号分隔的 string list**，
     * 所以含逗号的字段必须丢掉（否则会被拆成两个畸形头）。桌面 UA 里没有逗号，
     * Referer 通常也没有 —— 由调用方 `MpvPlayerFactory` 再做一次过滤兜底。
     *
     * @return 形如 `["User-Agent: ...", "Referer: ..."]`；无网页上下文时为空列表
     */
    fun asMpvHeaderFields(): List<String> {
        val out = ArrayList<String>(2)
        userAgent?.takeIf { it.isNotBlank() }?.let { out += "User-Agent: $it" }
        referer?.takeIf { it.isNotBlank() }?.let { out += "Referer: $it" }
        return out
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
