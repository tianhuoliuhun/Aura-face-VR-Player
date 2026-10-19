package com.example.vr

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.rtmp.RtmpDataSource
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile

/**
 * v2.0.142：回环 HTTP 代理（MT 等）的播放缓存，供 [SchemeRoutingDataSource] 的 http 分支
 * 复用——即使代理不支持 Range 也能按需拉取字节、正常 seek。上限 512MB，LRU 淘汰。
 */
private const val HTTP_CACHE_MAX_BYTES = 512L * 1024 * 1024

private var httpCacheSingleton: SimpleCache? = null

private fun getHttpCache(context: Context): SimpleCache {
    synchronized(SmbDataSource::class.java) {
        if (httpCacheSingleton == null) {
            val dir = java.io.File(context.cacheDir, "http_stream_cache")
            httpCacheSingleton = SimpleCache(
                dir,
                LeastRecentlyUsedCacheEvictor(HTTP_CACHE_MAX_BYTES),
                StandaloneDatabaseProvider(context)
            )
        }
        return httpCacheSingleton!!
    }
}

/**
 * ExoPlayer DataSource that streams video over SMB (SMB2/SMB3) via jcifs-ng,
 * so LAN shares can be played directly without downloading first.
 *
 * v2.0.139：seek 由 SmbFileInputStream.skip() 改为 SmbRandomAccessFile 真随机访问——
 * skip 对大偏移需要顺序读取并丢弃数据，长视频拖动会非常慢。
 */
class SmbDataSource : BaseDataSource(/* isNetwork = */ true) {

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource()
    }

    private var randomAccess: SmbRandomAccessFile? = null
    private var openedUri: Uri? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        openedUri = dataSpec.uri
        try {
            val smbFile = SmbFile(dataSpec.uri.toString())
            val raf = SmbRandomAccessFile(smbFile, "r")
            val fileSize = smbFile.length()
            raf.seek(dataSpec.position)
            randomAccess = raf
            bytesRemaining = when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                fileSize > 0L && dataSpec.position < fileSize -> fileSize - dataSpec.position
                else -> C.LENGTH_UNSET.toLong()
            }
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (e: Exception) {
            throw java.io.IOException("SMB open failed: ${e.message}", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val raf = randomAccess ?: return C.RESULT_END_OF_INPUT
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val read = try {
            raf.read(buffer, offset, length)
        } catch (e: Exception) {
            throw java.io.IOException("SMB read failed: ${e.message}", e)
        }
        if (read > 0) {
            bytesTransferred(read)
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
        }
        return if (read == -1) C.RESULT_END_OF_INPUT else read
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        try {
            randomAccess?.close()
        } catch (_: Exception) {
        }
        randomAccess = null
        openedUri = null
        bytesRemaining = C.LENGTH_UNSET.toLong()
    }
}

/**
 * DefaultDataSource 的 base 数据源按 scheme 分流（v2.0.139）。
 *
 * 背景：DefaultDataSource 自身只处理 file/asset/content，其余 scheme（含 http/https）
 * 全部落到 base 数据源。MT 管理器等文件管理器会把 FTP/SMB 远程文件经本地回环
 * HTTP 代理（http://127.0.0.1:port/...）交给播放器——base 若固定为 SmbDataSource，
 * http URI 会被拿去发起 SMB 连接 127.0.0.1 而失败（MuMu logcat 实测：
 * "SMB open failed: Failed to connect: 0.0.0.0<00>/127.0.0.1"）。
 * 这里按 scheme 分流：smb:// → SmbDataSource(jcifs)，其余 → DefaultHttpDataSource。
 */
class SchemeRoutingDataSource(private val context: Context) : BaseDataSource(/* isNetwork = */ true) {

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = SchemeRoutingDataSource(context)
    }

    private var delegate: DataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        close()
        val scheme = dataSpec.uri.scheme?.lowercase()
        val source: DataSource = when (scheme) {
            // v2.1.237：**RTMP 走专用 DataSource**（`media3-datasource-rtmp`，纯 Java 实现，
            // 无 native 库 —— 已核实 AAR 内没有任何 .so）。
            // ⚠️ 它**不能**包 CacheDataSource：RTMP 是**流式**协议，不支持按字节区间的
            //    随机请求（无 Range / 不可 seek），缓存层会尝试按区间拉取并失败。
            // ⚠️ 也因此 rtmp:// 天然不能拖动进度条 —— 这是协议本身的限制，不是 bug。
            "rtmp" -> RtmpDataSource()

            // smb:// → jcifs
            "smb" -> SmbDataSource()

            // v2.6.5：**本地/内嵌资源**交给 DefaultDataSource 处理（file / content / asset / data）。
            // 之所以放在这里，是因为 VRPlayerScreen 不再用 DefaultDataSource 包一层 ——
            // 那层对 http 会自建数据源且**不带 UA**（实测 CDN 无 UA 直接 403），
            // 而它的 http 工厂在本 Media3 版本没有对外 setter，无法注入。
            // 现在由本分流器全权决定，http 的 UA/Referer 才真正生效。
            "file", "content", "asset", "data", "rawresource", "android.resource" ->
                androidx.media3.datasource.DefaultDataSource.Factory(context).createDataSource()

            else -> {
                // v2.0.142：http(s)（MT 回环代理等）包 CacheDataSource——代理不支持 Range
                // 时也能按需拉取字节、正常 seek（边下边播），moov 在尾部的 MP4 也能先读 moov。
                //
                // v2.6.5：网页/URL 播放时带上 Referer + UA（由 HttpPlaybackHeaders 提供）。
                // 不带的话多数站的 CDN 直接 403 —— 实测：无 UA → 403；带任意 UA（含 Exo 默认）→ 206。
                //
                // 🔴 **UA 必须永不为空**：第一版写成「有值才设」，而 URL 播放那条路径若没走到
                //    forWebPlayback，userAgent 就是 null → 等于不设 UA → 依然 403。
                //    这里无条件给一个兜底 UA。
                val httpFactory = DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                val ua = HttpPlaybackHeaders.userAgent?.takeIf { it.isNotBlank() }
                    ?: androidx.media3.common.util.Util.getUserAgent(context, "Aura-face-VR-Player")
                httpFactory.setUserAgent(ua)
                HttpPlaybackHeaders.asRequestProperties()?.let {
                    httpFactory.setDefaultRequestProperties(it)
                }
                // 留一条 INFO 日志：网页直链出问题时先看这里 UA 有没有带上（403 的常见成因）
                android.util.Log.i(
                    "SchemeRouting",
                    "HTTP open: ua=$ua, referer=${HttpPlaybackHeaders.referer ?: "<null>"}"
                )
                val httpSource = httpFactory.createDataSource()
                // v2.6.5：**网页/URL 播放不走 CacheDataSource**。
                // 实测该 CDN 对 Range 请求按 5 MiB 分块返回，缓存层的 Tee 写入与 Content-Length
                // 校验会打架，表现为 `EOFException @ Mp4Extractor.readSample`（拿到数据但比声称的短）。
                // 直连 http 源少一层，行为更接近普通播放器；且网页直链带一次性 token，缓存无复用价值。
                if (HttpPlaybackHeaders.isActive) {
                    httpSource
                } else {
                    CacheDataSource(getHttpCache(context), httpSource)
                }
            }
        }
        delegate = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = delegate?.uri

    override fun close() {
        try {
            delegate?.close()
        } catch (_: Exception) {
        }
        delegate = null
    }
}
