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

    /**
     * 把 [inputUri] 转封装成 Matroska 写入 [outFile]。
     *
     * ⚠️ **同步、耗时**（1.19GB 实测 30~60s）——调用方必须在后台线程。
     * ⚠️ 失败时产物不可信（可能部分写出），调用方应删除。
     */
    fun remuxToMkv(context: Context, inputUri: Uri, outFile: File): Result {
        var pfd: ParcelFileDescriptor? = null
        val inPath: String = when (inputUri.scheme?.lowercase()) {
            "file" -> inputUri.path
                ?: return Result(false, null, -100, "file URI 无路径")
            "content" -> {
                // content://（相册）没有真实路径 → 走 fd：libavformat 打开 /proc/self/fd/N
                // ⚠️ fd 必须活到 nativeRemuxToMkv 返回（本函数 finally 里统一关闭）
                pfd = context.contentResolver.openFileDescriptor(inputUri, "r")
                    ?: return Result(false, null, -100, "openFileDescriptor 返回 null")
                "/proc/self/fd/${pfd.fd}"
            }
            "smb", "http", "https" ->
                return Result(false, null, -100, "网络 URI 不支持（先经 AudioTee 的临时下载兜底落地为本地文件）")
            else -> inputUri.toString()
        }
        return try {
            val code = nativeRemuxToMkv(inPath, outFile.absolutePath)
            if (code == 0) Result(true, outFile.absolutePath, 0, null)
            else Result(false, null, code, codeMessage(code))
        } catch (t: Throwable) {
            Log.e(TAG, "remux 异常", t)
            Result(false, null, -200, t.message ?: t.javaClass.simpleName)
        } finally {
            try { pfd?.close() } catch (_: Exception) {}
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
        -100 -> "输入路径解析失败"
        else -> "未知错误 $code"
    }
}
