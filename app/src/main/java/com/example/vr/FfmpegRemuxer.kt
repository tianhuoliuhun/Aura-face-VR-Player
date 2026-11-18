package com.example.vr

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/**
 * **FFmpeg 转封装**（v2.4.21）—— 字幕链路对「框架 MediaExtractor 解不开的容器」的兜底。
 *
 * ## 为什么需要它
 * 实时字幕的音频来源 [RealtimeSubtitleEngine.AudioTee] 只有框架 `MediaExtractor` 一条路。
 * 实测（用户 1.19GB 多段 OpenDML AVI）：框架对它 **`Failed to instantiate extractor`**——
 * 连「复制整个 1.19GB 到临时文件再打开」都无济于事（容器本身解不了，与路径无关）。
 * 而 MPV（libavformat）能完整播放该文件 → 证明 FFmpeg 能解、框架不能。
 *
 * ## 怎么做
 * `libavformat` **转封装**（remux）：不解码、纯容器转换（AVI → Matroska/MKV），
 * 代价低（1.19GB 实测约 30~60s，纯 IO）。选 MKV 而非 MP4 是因为 Matroska muxer
 * 对 AVI 里各种音频编码（PCM / MP3 / AC3…）兼容性最宽，且框架有 MatroskaExtractor。
 *
 * ## C++ 实现
 * `ffremux_jni.cpp`（libauravr.so）。链接的 `libavformat/libavcodec/libavutil` 来自
 * **MPV 的 AAR**（FFmpeg 7.1 / Lavf63.7.100，编译期链接 third_party/ffmpeg-libs/），
 * **运行时与 APK 里 MPV 的那份是同一组 .so**，不增加体积。
 * 头文件版本必须与 .so 匹配（n7.1 提取，third_party/ffmpeg-libs/include/）。
 *
 * ## 输入支持
 * - `file://` → 直接路径
 * - `content://` → `/proc/self/fd/N`（fd 由本类持有到 remux 结束；同步调用，安全）
 * - `smb://` / `http(s)://` → **不支持**（AudioTee 的既有「临时文件下载」兜底先落地成
 *   本地文件，但本地文件仍解不开时走不到这里——网络源的容器问题维持现状）
 */
object FfmpegRemuxer {
    private const val TAG = "FfmpegRemux"

    init {
        // JNI 实现在 libauravr.so；loadLibrary 幂等（llama 侧也会加载它）。
        System.loadLibrary("auravr")
        try {
            Log.i(TAG, "FFmpeg 转封装就绪：${nativeVersion()}")
        } catch (_: Throwable) {
        }
    }

    data class Result(
        val success: Boolean,
        /** 成功时的产物路径。 */
        val path: String?,
        /** C++ 返回码（0 成功）。 */
        val code: Int,
        val message: String?
    )

    private external fun nativeVersion(): String
    private external fun nativeRemuxToMkv(inputPath: String, outputPath: String): Int
    private external fun nativeRemuxToMp4(inputPath: String, outputPath: String): Int

    /**
     * v2.8.3：**网络源（http/https/HLS）→ MKV**，只搬音轨。
     * 对应 `ffremux_jni.cpp` 的 `nativeRemuxUrlToMkv`。
     *
     * v2.8.3（第二轮）：多一个 `cookie` —— 需要登录态的站点（sessionid / cf_clearance）
     * 光有 UA + Referer 仍会 403，重封装会在 `avformat_open_input` 就失败，
     * 表现为「实时字幕永远准备中」。
     */
    private external fun nativeRemuxUrlToMkv(
        inputUrl: String,
        outputPath: String,
        userAgent: String?,
        referer: String?,
        cookie: String?,
        caFile: String?,
        maxSeconds: Int
    ): Int

    /** 网络源重封装总预算（秒）：到点截断，已写出的音频仍可用来生成前段字幕。 */
    private const val NETWORK_REMUX_BUDGET_SECONDS = 600

    /**
     * 把 [inputUri] 转封装成 Matroska（MKV）写入 [outFile] —— 字幕链路兜底用。
     *
     * ⚠️ **同步、耗时**（1.19GB 实测 30~60s）——调用方必须在后台线程。
     * ⚠️ 失败时产物不可信（可能部分写出），调用方应删除。
     */
    fun remuxToMkv(context: Context, inputUri: Uri, outFile: File): Result =
        runRemux(context, inputUri, outFile, mp4 = false)

    /**
     * 把 [inputUri] 转封装成 **MP4** 写入 [outFile] —— **容器修复**用（v2.4.26）。
     *
     * 走 libavformat 的 mov muxer：会写 stbl + **ctts**，**保留 B 帧**（不像
     * MediaExtractor+MediaMuxer 会因时间戳非单调而丢帧），且 ExoPlayer 可 seek。
     */
    fun remuxToMp4(context: Context, inputUri: Uri, outFile: File): Result =
        runRemux(context, inputUri, outFile, mp4 = true)

    private fun runRemux(context: Context, inputUri: Uri, outFile: File, mp4: Boolean): Result {
        var pfd: ParcelFileDescriptor? = null
        val inPath: String = when (inputUri.scheme?.lowercase()) {
            "file" -> inputUri.path
                ?: return Result(false, null, -100, "file URI 无路径")
            "content" -> {
                // content://（相册）没有真实路径 → 走 fd：libavformat 打开 /proc/self/fd/N
                // ⚠️ fd 必须活到 native 调用返回（本函数 finally 里统一关闭）
                pfd = context.contentResolver.openFileDescriptor(inputUri, "r")
                    ?: return Result(false, null, -100, "openFileDescriptor 返回 null")
                "/proc/self/fd/${pfd.fd}"
            }
            "http", "https" -> {
                // v2.8.3：网络源改走独立 JNI 入口 —— libavformat 直接打开 URL（含 m3u8），
                // 只搬音轨、带 UA/Referer、带总预算，见 ffremux_jni.cpp 的说明。
                // 适用于「实时字幕在网页流上必然报『音频读取失败（IO）』」那个缺口。
                if (mp4) {
                    return Result(false, null, -100, "网络 URI 只支持转 MKV（MP4 用于本地容器修复）")
                }
                val h = HttpPlaybackHeaders.asRequestProperties() ?: emptyMap()
                return try {
                    val code = nativeRemuxUrlToMkv(
                        inputUri.toString(),
                        outFile.absolutePath,
                        h["User-Agent"],
                        h["Referer"],
                        // v2.8.3：会话 Cookie（无会话态时为 null，native 侧跳过该头）
                        h["Cookie"],
                        ensureCaBundle(context),
                        NETWORK_REMUX_BUDGET_SECONDS
                    )
                    if (code == 0) Result(true, outFile.absolutePath, 0, null)
                    else Result(false, null, code, codeMessage(code))
                } catch (t: Throwable) {
                    Log.e(TAG, "网络 remux 异常", t)
                    Result(false, null, -200, t.message ?: t.javaClass.simpleName)
                }
            }
            "smb" ->
                return Result(false, null, -100, "SMB 不支持转封装（先经 AudioTee 的临时下载兜底落地为本地文件）")
            else -> inputUri.toString()
        }
        return try {
            val code = if (mp4) nativeRemuxToMp4(inPath, outFile.absolutePath)
            else nativeRemuxToMkv(inPath, outFile.absolutePath)
            if (code == 0) Result(true, outFile.absolutePath, 0, null)
            else Result(false, null, code, codeMessage(code))
        } catch (t: Throwable) {
            Log.e(TAG, "remux 异常", t)
            Result(false, null, -200, t.message ?: t.javaClass.simpleName)
        } finally {
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    /**
     * v2.8.3：把 Android 系统 CA 拼成 **单个 PEM bundle**。
     *
     * 为什么必须：FFmpeg 的 mbedtls 只认 `ca_file` 指向的**单个**文件，而 Android 的系统
     * 证书是 `/system/etc/security/cacerts/` 下 140+ 个独立 PEM。不给 bundle 时所有 https 流都会：
     * ```
     * mbedtls_ssl_get_verify_result ... flags: 8
     * The certificate is not correctly signed by the trusted CA
     * → avformat_open_input 返回 AVERROR(EIO)（日志里只显示一句 I/O error）
     * ```
     * 表现为「网页流音频旁路全部失败」，极容易误判成网络问题。
     *
     * 产物缓存到 `cacheDir/ff_ca_bundle.pem`（只生成一次）；读不到系统目录时返回 null，
     * native 侧会退化为关闭校验（本链路只用于取音频，不承载凭据）。
     */
    private fun ensureCaBundle(context: Context): String? {
        val out = File(context.cacheDir, "ff_ca_bundle.pem")
        if (out.exists() && out.length() > 1024L) return out.absolutePath
        return try {
            val files = File("/system/etc/security/cacerts").listFiles()
                ?.filter { it.isFile } ?: return null
            if (files.isEmpty()) return null
            out.outputStream().bufferedWriter().use { w ->
                files.forEach { f ->
                    runCatching {
                        val text = f.readText()
                        if (text.contains("BEGIN CERTIFICATE")) {
                            w.write(text)
                            if (!text.endsWith("\n")) w.write("\n")
                        }
                    }
                }
            }
            if (out.length() > 1024L) {
                Log.i(TAG, "已生成 CA bundle：${files.size} 张证书 → ${out.length()} B")
                out.absolutePath
            } else {
                out.delete()
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "生成 CA bundle 失败：${t.message}")
            null
        }
    }

    private fun codeMessage(code: Int): String = when (code) {
        -1 -> "打不开输入文件"
        -2 -> "读取流信息失败"
        -3 -> "写 MKV 头失败（可能存在 muxer 不接受的编码）"
        -4 -> "无可写流"
        -5 -> "打不开输出文件"
        -6 -> "建流失败"
        -7 -> "写帧失败"
        -8 -> "直播/无固定时长的流，无法提取音频"
        -9 -> "网络读取超时"
        -100 -> "输入路径解析失败"
        else -> "未知错误 $code"
    }
}
