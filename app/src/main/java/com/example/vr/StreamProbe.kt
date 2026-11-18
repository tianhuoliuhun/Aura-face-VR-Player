package com.example.vr

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * v2.8.3：**流地址探测 / 文本抓取**（手动跟随重定向 + 识别 HLS 主清单）。
 *
 * ## 为什么需要"手动跟 302"
 * 网页里拿到的直链经常是**签发地址**，真正的内容在另一台机器上：
 * ```
 * https://www.site.com/stream/abc  → 302 Location: https://cdn3.site.com/hls/abc/master.m3u8?sign=...
 * ```
 * `HttpURLConnection` 默认会自动跟随，但**跟随之后我们拿不到最终地址**。
 * 而最终地址恰恰是后面几步的关键：
 *  - 播放器要拿到它（签发地址可能一次性、且对 Range 支持差）；
 *  - **字幕/音轨的相对 URI 要以它为基准解析** —— 用签发地址当 base 会拼出
 *    `https://www.site.com/hls/...` 这种 404 路径；
 *  - 用户能一眼看出流量到底落在哪个主机上（用于判断"是不是被换源了"）。
 *
 * 所以这里 `instanceFollowRedirects = false`，自己逐跳跟随并记录。
 *
 * ## 探测失败不等于不能播
 * 个别 CDN 对"多余的探测请求"很敏感（限速、只认特定 `Range`、或探测会消耗一次性签名），
 * 所以调用方**在探测失败时仍应允许照原样播放** —— 这里只负责给出 `HTTP 403` 这类
 * 可读原因，不做"拦截"决策。
 */
object StreamProbe {
    private const val TAG = "StreamProbe"

    /** 最多跟几跳。真实站点 2~3 跳，5 跳足够且能防重定向环。 */
    private const val MAX_REDIRECTS = 5

    /** 清单文本上限：主清单/媒体清单都很小，512KB 是极宽裕的上限。 */
    private const val MAX_TEXT_BYTES = 512 * 1024

    /**
     * 只嗅类型时读多少。
     *
     * 取 64KB 而不是 1KB：有些 CDN 的清单地址根本没有 `.m3u8` 后缀
     * （`/playlist/abc?id=1`），只能靠**内容开头 `#EXTM3U`** 判断；
     * 而主清单里 `#EXT-X-STREAM-INF` 的条目可能排到几 KB 之后 ——
     * 读 1KB 会漏掉所有变体，误判成"没有码率信息"。64KB 对 MP4 直链也无所谓
     * （只读一次、不落盘、`Range` 限定在 64KB 内）。
     */
    private const val TEXT_SNIFF_BYTES = 64 * 1024

    data class Result(
        /** 跟随重定向后的最终地址（无重定向时等于入参） */
        val finalUrl: String,
        /** 最终状态码（连不上时为 0） */
        val code: Int,
        val hops: Int,
        val contentType: String?,
        /** 最终地址是 HLS **主清单**时的解析结果 */
        val playlist: HlsPlaylist.Master?,
        /** 读到的文本（清单原文等）；只嗅类型且不是清单时为 null */
        val text: String?,
        /** 失败原因（HTTP 码 / 超时 / 未知主机），成功时为 null */
        val reason: String?,
    ) {
        val ok: Boolean get() = code in 200..299
    }

    /**
     * 探测一个地址：跟完重定向、拿最终 URL 与状态码，若是主清单则解析出码率档。
     *
     * @param headers 请求头（**不要带 `Range`** —— 本方法自己决定要不要带）
     */
    fun probe(url: String, headers: Map<String, String>): Result {
        val sniffOnly = !looksLikePlaylistUrl(url)
        val raw = rawRun(url, headers, if (sniffOnly) TEXT_SNIFF_BYTES else MAX_TEXT_BYTES)
        val text = raw.bytes?.toString(Charsets.UTF_8)
        val playlist =
            if (text != null && HlsPlaylist.isMaster(text)) HlsPlaylist.parseMaster(text, raw.finalUrl)
            else null
        return Result(
            finalUrl = raw.finalUrl,
            code = raw.code,
            hops = raw.hops,
            contentType = raw.contentType,
            playlist = playlist,
            // 不是清单就不把二进制垃圾留在内存里
            text = if (playlist == null && sniffOnly) null else text,
            reason = raw.reason,
        )
    }

    /** 抓一段文本（清单 / 字幕）。与 [probe] 同一套重定向逻辑，一定会读 body。 */
    fun fetchText(url: String, headers: Map<String, String>, maxBytes: Int = MAX_TEXT_BYTES): String? =
        fetchBytes(url, headers, maxBytes)?.toString(Charsets.UTF_8)

    /**
     * 抓一段原始字节（附带最终地址）。
     *
     * ⚠️ 字幕链路必须拿 **bytes** 而不是 String：`.srt` 有大量 GBK / Big5 老资源，
     * 直接按 UTF-8 解码会静默变乱码（见 [SubtitleParser.decodeBytes]）。
     *
     * 为什么不直接复用 [fetchBytes]：HLS 字幕轨的清单里是**相对路径分片**，
     * 必须以**重定向后的最终地址**为基准才拼得对。
     */
    fun fetchBlob(url: String, headers: Map<String, String>, maxBytes: Int = MAX_TEXT_BYTES): Blob? {
        val raw = rawRun(url, headers, maxBytes)
        if (raw.code !in 200..299) return null
        return Blob(raw.bytes, raw.finalUrl, raw.code)
    }

    data class Blob(val bytes: ByteArray?, val finalUrl: String, val code: Int)

    /** 抓一段原始字节。 */
    fun fetchBytes(url: String, headers: Map<String, String>, maxBytes: Int = MAX_TEXT_BYTES): ByteArray? =
        fetchBlob(url, headers, maxBytes)?.bytes

    private data class Raw(
        val finalUrl: String,
        val code: Int,
        val hops: Int,
        val contentType: String?,
        val bytes: ByteArray?,
        val reason: String?,
    )

    /** URL 看起来是 m3u8 就走"读全文"，否则只嗅前 64KB 看内容类型。 */
    private fun looksLikePlaylistUrl(url: String): Boolean {
        val path = url.substringBefore('#').lowercase()
        return path.contains(".m3u8") || path.contains("m3u")
    }

    private fun rawRun(originalUrl: String, headers: Map<String, String>, maxBytes: Int): Raw {
        var current = originalUrl
        var hops = 0
        var code = 0
        var type: String? = null

        while (true) {
            val conn = try {
                URL(current).openConnection() as HttpURLConnection
            } catch (t: Throwable) {
                Log.w(TAG, "地址不合法：${t.message}")
                return Raw(current, 0, hops, type, null, t.message ?: "地址不合法")
            }

            try {
                conn.connectTimeout = 8_000
                conn.readTimeout = 12_000
                // 关键：自己跟，才能拿到 Location 链与最终地址
                conn.instanceFollowRedirects = false
                conn.requestMethod = "GET"
                headers.forEach { (k, v) -> runCatching { conn.setRequestProperty(k, v) } }
                // 只嗅类型时明确限定前 64KB：既省流量，也让"仅支持 Range 的 CDN"正常回 206
                if (maxBytes <= TEXT_SNIFF_BYTES) {
                    conn.setRequestProperty("Range", "bytes=0-${TEXT_SNIFF_BYTES - 1}")
                }

                code = conn.responseCode
                type = conn.contentType

                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    if (loc.isNullOrBlank() || hops >= MAX_REDIRECTS) {
                        return Raw(current, code, hops, type, null, "重定向异常（$code）")
                    }
                    current = HlsPlaylist.resolveUri(current, loc)
                    hops++
                    continue
                }

                if (code !in 200..299) {
                    return Raw(current, code, hops, type, null, "HTTP $code")
                }

                return Raw(current, code, hops, type, readLimited(conn, maxBytes), null)
            } catch (t: Throwable) {
                Log.w(TAG, "探测失败：${t.javaClass.simpleName} ${t.message}")
                return Raw(current, code, hops, type, null, t.message ?: t.javaClass.simpleName)
            } finally {
                runCatching { conn.disconnect() }
            }
        }
    }

    /**
     * 读最多 [limit] 字节。
     *
     * ⚠️ 必须**限量**：`inputStream.readBytes()` 会把整个响应读完 ——
     * 对 MP4 直链那就是把整部片子拉下来。
     */
    private fun readLimited(conn: HttpURLConnection, limit: Int): ByteArray? {
        val stream = try {
            conn.inputStream
        } catch (t: Throwable) {
            // 4xx 时 inputStream 抛异常，body 在 errorStream 里（对我们没用）
            return null
        }
        return try {
            stream.use { input ->
                val buf = ByteArray(16 * 1024)
                val out = java.io.ByteArrayOutputStream(minOf(limit, 64 * 1024))
                var total = 0
                while (total < limit) {
                    val n = input.read(buf, 0, minOf(buf.size, limit - total))
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    total += n
                }
                out.toByteArray()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读 body 失败：${t.message}")
            null
        }
    }
}
