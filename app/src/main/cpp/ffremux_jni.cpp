// ==============================================================================
// FfmpegRemuxer 的 JNI 实现（v2.4.21）
// ------------------------------------------------------------------------------
// 用途：把「框架 MediaExtractor 解不开」的容器（实测：多段 OpenDML AVI，
//   `AudioTee` 直接 `Failed to instantiate extractor`，连复制成临时文件也无济于事
//   —— 见 docs/AVI_LOCAL_SUBTITLE_DIAGNOSIS_2026-10-09.md）用 libavformat
//   **转封装**成 Matroska（MKV）：不解码、纯容器转换，代价低。
//   MKV 的 muxer 几乎收所有编码（AVI 里的 PCM / MP3 / AC3 都能进），
//   且框架有 MatroskaExtractor —— 转完之后字幕链路（AudioTee）就能正常抽音频。
//
// 为什么选 MKV 而不是 MP4：MP4 的 muxer 会拒绝部分编码（如裸 PCM），
//   Matroska 兼容性最宽；且框架（MatroskaExtractor）解 MKV 没问题。
//
// ⚠️ 库来源：libavformat / libavcodec / libavutil 来自 **MPV 的 AAR**
//   （io.github.marlboro-advance:mpv-android，FFmpeg 7.1 / Lavf63.7.100），
//   运行时与 APK 里 MPV 的那份是同一组 .so —— 见 CMakeLists.txt 的说明。
// ⚠️ 头文件版本必须与 .so 匹配（n7.1）—— 用错版本的公开结构体字段会静默出错。
// ==============================================================================

#include <jni.h>
#include <android/log.h>

// 🔴 FFmpeg 7.x 的头文件**移除了 extern "C" 包裹**（官方 n7.1 的 avformat.h 全文
//    都没有 extern "C" —— 与 6.x 的 breaking change）。C++ 编译单元若直接 include，
//    所有声明会被 C++ mangled → 链接时全部 undefined（lld 还会贴心地提示
//    `did you mean: extern "C" avformat_version`）。必须自己包：
extern "C" {
#include <libavformat/avformat.h>
}

#include <libavutil/error.h>
#include <libavutil/log.h>

#define TAG "FfmpegRemux"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

/**
 * 返回 libavformat 的版本标识（如 "Lavf63.7.100"）—— 启动时打一条，验证头/.so 匹配。
 */
JNIEXPORT jstring JNICALL
Java_com_example_vr_FfmpegRemuxer_nativeVersion(JNIEnv *env, jclass) {
    char buf[64];
    unsigned v = avformat_version();
    snprintf(buf, sizeof(buf), "Lavf%u.%u.%u", AV_VERSION_MAJOR(v),
             AV_VERSION_MINOR(v), AV_VERSION_MICRO(v));
    return env->NewStringUTF(buf);
}

/**
 * 把 `inPath` 转封装为 Matroska 写到 `outPath`（不解码，纯容器转换）。
 *
 * 返回 0 表示成功；负数为失败码（Java 侧映射成可读文案）：
 *   -1 打不开输入        -2 find_stream_info 失败
 *   -3 写头失败          -4 无可写流
 *   -5 打不开输出文件    -6 建流/拷参数失败
 *   -7 写帧失败（写出了部分数据时也按失败处理，产物不可信）
 *
 * ⚠️ 同步调用，在调用方的后台线程执行（RealtimeSubtitle 的启动协程）。
 *    1.19GB 实测约 30~60s（纯 IO，不解码）。
 */
JNIEXPORT jint JNICALL
Java_com_example_vr_FfmpegRemuxer_nativeRemuxToMkv(JNIEnv *env, jclass,
                                                   jstring jIn, jstring jOut) {
    const char *inPath = env->GetStringUTFChars(jIn, nullptr);
    const char *outPath = env->GetStringUTFChars(jOut, nullptr);
    if (!inPath || !outPath) {
        if (inPath) env->ReleaseStringUTFChars(jIn, inPath);
        if (outPath) env->ReleaseStringUTFChars(jOut, outPath);
        return -1;
    }

    AVFormatContext *in = nullptr;
    AVFormatContext *out = nullptr;
    int ret = 0;
    char errbuf[128];

    // 1) 打开输入。⚠️ AVI 的 OpenDML 多段（AVIX）由 libavformat 通过 `indx` 索引处理，
    //    用户那个 1.19GB 样本（无 idx1、有 indx）实测能被完整读出。
    if ((ret = avformat_open_input(&in, inPath, nullptr, nullptr)) < 0) {
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE("open_input(%s) 失败: %s", inPath, errbuf);
        ret = -1;
        goto done;
    }
    if ((ret = avformat_find_stream_info(in, nullptr)) < 0) {
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE("find_stream_info 失败: %s", errbuf);
        ret = -2;
        goto done;
    }
    LOGI("输入: %u 个流, 时长 %.1fs", in->nb_streams,
         in->duration > 0 ? in->duration / 1000000.0 : 0.0);

    // 2) 输出上下文：显式指定 matroska（不靠文件后缀猜）。
    if ((ret = avformat_alloc_output_context2(&out, nullptr, "matroska", outPath)) < 0) {
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE("alloc_output_context2 失败: %s", errbuf);
        ret = -3;
        goto done;
    }

    // 3) 逐流建流 + 拷参数。
    for (unsigned i = 0; i < in->nb_streams; i++) {
        AVStream *is = in->streams[i];
        AVStream *os = avformat_new_stream(out, nullptr);
        if (!os) { ret = -6; goto done; }
        if ((ret = avcodec_parameters_copy(os->codecpar, is->codecpar)) < 0) {
            av_strerror(ret, errbuf, sizeof(errbuf));
            LOGE("codec_parameters_copy(流 %u) 失败: %s", i, errbuf);
            ret = -6;
            goto done;
        }
        os->codecpar->codec_tag = 0;   // mkv 不用原容器里的 tag
    }
    if (out->nb_streams == 0) { ret = -4; goto done; }

    // 4) 打开输出文件。
    if (!(out->oformat->flags & AVFMT_NOFILE)) {
        if ((ret = avio_open(&out->pb, outPath, AVIO_FLAG_WRITE)) < 0) {
            av_strerror(ret, errbuf, sizeof(errbuf));
            LOGE("avio_open(%s) 失败: %s", outPath, errbuf);
            ret = -5;
            goto done;
        }
    }

    // 5) 写头。
    if ((ret = avformat_write_header(out, nullptr)) < 0) {
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE("write_header 失败: %s", errbuf);
        ret = -3;
        goto done;
    }

    // 6) 逐包搬运：时间基换算（in_stream→out_stream）。
    //    ⚠️ Java 版 VideoRemuxer 踩过的两个坑在 C++ 里同样要处理：
    //    ① AVI 的时间戳不保证单调 —— 但 `av_interleaved_write_frame` 会**缓冲重排**，
    //      对乱序比 MediaMuxer 宽容得多，因此无需手动丢弃；
    //    ② 无时间戳的包（pts == AV_NOPTS_VALUE）必须跳过（mkv 无法写入）。
    {
        AVPacket *pkt = av_packet_alloc();
        if (!pkt) { ret = -7; goto done; }
        long long written = 0, skipped = 0, failed = 0;
        while ((ret = av_read_frame(in, pkt)) >= 0) {
            if (pkt->pts == AV_NOPTS_VALUE || pkt->dts == AV_NOPTS_VALUE) {
                skipped++;
                av_packet_unref(pkt);
                continue;
            }
            AVStream *is = in->streams[pkt->stream_index];
            AVStream *os = out->streams[pkt->stream_index];
            av_packet_rescale_ts(pkt, is->time_base, os->time_base);
            pkt->pos = -1;
            pkt->stream_index = pkt->stream_index;   // 流序一一对应
            if ((ret = av_interleaved_write_frame(out, pkt)) < 0) {
                failed++;
                if (failed > 100) {                  // 大面积失败才放弃（个别包容忍）
                    av_strerror(ret, errbuf, sizeof(errbuf));
                    LOGE("write_frame 持续失败: %s", errbuf);
                    av_packet_unref(pkt);
                    av_packet_free(&pkt);
                    ret = -7;
                    goto trailer;
                }
            } else {
                written++;
            }
            av_packet_unref(pkt);
        }
        av_packet_free(&pkt);
        LOGI("remux 完成: 写出 %lld 包（跳过无时间戳 %lld，失败 %lld）", written, skipped, failed);
        if (written == 0) { ret = -7; goto trailer; }
    }

trailer:
    // 7) 收尾（trailer 失败不覆盖主错误码，除非前面是成功的）。
    {
        int tr = av_write_trailer(out);
        if (tr < 0 && ret == 0) ret = -7;
    }

done:
    if (in) avformat_close_input(&in);
    if (out) {
        if (!(out->oformat->flags & AVFMT_NOFILE)) {
            avio_closep(&out->pb);
        }
        avformat_free_context(out);
    }
    if (inPath) env->ReleaseStringUTFChars(jIn, inPath);
    if (outPath) env->ReleaseStringUTFChars(jOut, outPath);
    if (ret == 0) LOGI("remux 成功 → %s", outPath);
    else LOGE("remux 失败 code=%d → %s", ret, outPath);
    return ret;
}

} // extern "C"
