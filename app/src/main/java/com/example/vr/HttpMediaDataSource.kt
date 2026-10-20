package com.example.vr

import android.media.MediaDataSource
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * v2.6.7：把 http(s) 暴露成 framework 的 [MediaDataSource]，让 AudioTee 能**带请求头 + 真随机访问**地读音轨。
 *
 * ## 为什么需要它
 * AudioTee 走「独立音频解码」（另开一套 MediaExtractor + MediaCodec）。之前 http 分支直接
 * `setDataSource(context, uri, null)` —— 那是框架自带 HTTP 栈，**不带 User-Agent、不带 Range**；
 * 对校验这两个头的 CDN 一律 403。回退的整文件下载用的是裸 `HttpURLConnection`，同样没头。
 * 于是双双失败，UI 只显示「音频读取失败（IO）」。
 *
 * ## 块大小 256 KiB 的由来
 * 初版按 CDN 的 5 MiB 分块边界对齐，结果**实际卡死**：MediaExtractor 解析 MP4 时大量是几 KB
 * 的小读（stbl / moov 采样表），每次小读都要拉 5 MiB，一个 15 秒音频窗口要横跨多块，
 * 网络往返被放大到不可接受。改为 256 KiB + LRU 后正常。
 * CDN 侧「单次最多给 5 MiB」只是上限，请求更小范围它照样按 Range 返回。
 *
 * ## 两项性能优化（v2.6.7 第二轮）
 * 1. **OkHttp 连接池**：之前每次 `HttpURLConnection.disconnect()` 都会丢掉连接，
 *    下一个块重新 TCP + TLS 握手。改走共享的 OkHttpClient 后连接复用，单块耗时明显下降。
 * 2. **异步预取**：读指针越过当前块 60% 时，后台线程预取下一块。顺序播放时下一块几乎总是立刻命中。
 */
internal class HttpMediaDataSource(private val url: String) : MediaDataSource() {

    private companion object {
        const val TAG = "HttpMediaDataSource"

        /** 单块字节数：匹配 MediaExtractor 的典型小读规模 */
        const val BLOCK = 256 * 1024

        /** LRU 保留块数（8 × 256 KiB ≈ 2 MB，顺序读 + 回跳基本全命中） */
        const val MAX_BLOCKS = 8

        const val CONNECT_TIMEOUT_MS = 10_000L
        const val READ_TIMEOUT_MS = 20_000L

        /** 单次 readAt 超过这个耗时就记一条 W 日志（排查卡顿） */
        const val SLOW_READ_MS = 1_500L

        /** 读指针越过块的这个比例后，异步预取下一块 */
        const val PREFETCH_RATIO = 0.6f

        /**
         * 共享连接池。放在 companion 里是为了**跨实例复用**：
         * 每次 seek 都会重建 AudioTee（也就重建本类），连接池必须比实例活得久才有意义。
         */
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    private val extraHeaders: Map<String, String> =
        HttpPlaybackHeaders.asRequestProperties() ?: emptyMap()

    @Volatile
    private var totalSize = -1L

    /** 块缓存：块起点 -> 数据。accessOrder = true 即 LRU */
    private val blocks = object : LinkedHashMap<Long, ByteArray>(MAX_BLOCKS, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Long, ByteArray>?
        ): Boolean = size > MAX_BLOCKS
    }

    /** 同一时刻只跑一个预取线程，避免拖动时堆积 */
    private var prefetching = false

    override fun getSize(): Long {
        if (totalSize >= 0) return totalSize
        // 用 Range: bytes=0-0 探测总长（HEAD 在部分 CDN 上同样要求 Range，不可靠）
        try {
            val req = request(0, 0)
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val total = resp.header("Content-Range")
                        ?.substringAfterLast('/', "")
                        ?.trim()
                        ?.toLongOrNull()
                    if (total != null && total > 0) totalSize = total
                } else {
                    Log.w(TAG, "探测长度失败：HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "探测长度异常：${e.message}")
        }
        Log.i(TAG, "总长 = $totalSize")
        return totalSize
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0) return -1
        val sz = getSize()
        if (sz > 0 && position >= sz) return -1

        val t0 = android.os.SystemClock.elapsedRealtime()
        val blockStart = position / BLOCK * BLOCK
        val data = synchronized(blocks) {
            blocks[blockStart] ?: fetchBlock(blockStart)?.also { blocks[blockStart] = it }
        } ?: return -1

        val inBlock = (position - blockStart).toInt()
        if (inBlock >= data.size) return -1
        val n = minOf(size, data.size - inBlock)
        System.arraycopy(data, inBlock, buffer, offset, n)

        val cost = android.os.SystemClock.elapsedRealtime() - t0
        if (cost > SLOW_READ_MS) {
            Log.w(TAG, "慢读 ${cost}ms（pos=$position, size=$size）")
        }

        // 读到当前块后半段 → 预取下一块（不阻塞本次返回）
        if (inBlock > data.size * PREFETCH_RATIO) {
            prefetch(blockStart + BLOCK)
        }
        return n
    }

    /** 异步预取一块；已在缓存里或已有预取在跑就跳过 */
    private fun prefetch(start: Long) {
        synchronized(blocks) {
            if (prefetching) return
            if (blocks.containsKey(start)) return
            val sz = totalSize
            if (sz > 0 && start >= sz) return
            prefetching = true
        }
        Thread {
            try {
                fetchBlock(start)?.let { data ->
                    synchronized(blocks) { blocks[start] = data }
                }
            } finally {
                synchronized(blocks) { prefetching = false }
            }
        }.apply {
            name = "asr-prefetch-$start"
            // 后台优先级：不要和播放/解码抢 CPU
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    /** 拉取 [start, start+BLOCK)；失败返回 null */
    private fun fetchBlock(start: Long): ByteArray? = try {
        client.newCall(request(start, start + BLOCK - 1)).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "拉块失败：HTTP ${resp.code}（start=$start）")
                null
            } else {
                resp.body?.bytes()
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "拉块异常：${e.message}（start=$start）")
        null
    }

    private fun request(rangeStart: Long, rangeEnd: Long): Request =
        Request.Builder()
            .url(url)
            .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
            // Range 显式给，覆盖 HttpPlaybackHeaders 里默认的 "bytes=0-"
            .header("Range", "bytes=$rangeStart-$rangeEnd")
            .build()

    override fun close() {
        synchronized(blocks) { blocks.clear() }
    }
}
