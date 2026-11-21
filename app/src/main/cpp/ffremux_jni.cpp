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
#include <libavutil/mem.h>
#include <chrono>
#include <cstdarg>

#define TAG "FfmpegRemux"

/**
 * v2.8.3：把 **FFmpeg 内部日志**转到 logcat（前缀 `FfmpegLog`）。
 *
 * 为什么需要：`avformat_open_input` 失败时 `av_strerror` 只给一句笼统的
 * `I/O error`（AVERROR(EIO)）—— 到底是 DNS、TCP 连接被重置、TLS 握手失败
 * 还是 CDN 返回 403，全在内部日志里（`tls: ...` / `http: ...`）。
 * 没有这层日志就只能瞎猜。
 *
 * 诊断期用 AV_LOG_DEBUG（能看到 http 请求/响应行与 mbedtls 握手细节）；
 * 正式包/稳定期保持 AV_LOG_WARNING，避免泄露直链 URL token 与 Cookie 头，并减少刷屏。
 */
static void ffLogToLogcat(void *, int level, const char *fmt, va_list vl) {
    if (level > AV_LOG_WARNING) return;
    char line[1024];
    vsnprintf(line, sizeof(line), fmt, vl);
    int prio = (level <= AV_LOG_ERROR) ? ANDROID_LOG_ERROR
             : ANDROID_LOG_WARN;
    __android_log_print(prio, "FfmpegLog", "%s", line);
}

/** 幂等地装上日志回调（App 启动时 nativeVersion 会调用一次，remux 前再兜一次）。 */
static void ensureFfLog() {
    static bool inited = false;
    if (inited) return;
    av_log_set_level(AV_LOG_WARNING);
    av_log_set_callback(ffLogToLogcat);
    inited = true;
}

/**
 * v2.8.3：**给 HLS 的所有子请求补上网络选项** —— 这是「转封装失败」的第二层根因。
 *
 * 实测证据链：
 * ① `avformat_open_input` 的 options 只作用于**主连接** → master.m3u8 能读到（752 字节）；
 * ② HLS demuxer 打开 variant 清单 / 分片 / AES-128 密钥时会走 `s->io_open`，
 *    用的是它自己的 `c->avio_opts`，**不含我们传的 ca_file / user_agent / headers**；
 * ③ 于是子请求在 TLS 阶段就失败：
 *      `mbedtls_ssl_get_verify_result ... flags: 8`
 *      `The certificate is not correctly signed by the trusted CA`
 *      `parse_playlist error I/O error [...]` → 0 个流 → 被判成「直播」。
 *
 * 修法：覆盖 `AVFormatContext.io_open`，每次子请求都把这组选项合并进去。
 * `av_dict_copy` 只补**缺失**项，不覆盖 demuxer 自己设的值。
 */
static AVDictionary *g_netOpts = nullptr;

static int ioOpenWithNetOpts(AVFormatContext *s, AVIOContext **pb, const char *url,
                             int flags, AVDictionary **options) {
    AVDictionary *tmp = nullptr;
    if (options && *options) av_dict_copy(&tmp, *options, 0);
    if (g_netOpts) av_dict_copy(&tmp, g_netOpts, 0);
    int ret = avio_open2(pb, url, flags, &s->interrupt_callback, &tmp);
    if (options) {
        av_dict_free(options);
        *options = tmp;
    } else {
        av_dict_free(&tmp);
    }
    return ret;
}

/**
 * v2.8.3：单调时钟（微秒）。
 *
 * ⚠️ 这里**刻意不用 `av_gettime_relative()`**：本项目的 libavutil 来自 MPV 的 AAR，
 * 是裁剪过的构建 —— 该符号**没有导出**，链接期直接
 * `ld.lld: error: undefined symbol: av_gettime_relative()`。
 * `std::chrono::steady_clock` 等价且零外部依赖。
 */
static int64_t nowUs() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

/**
 * v2.8.3：把一条 HTTP 头追加进 `dst`，每条以 `\r\n` 结尾（FFmpeg 的 `headers` 选项
 * 不会自己补行尾，少一个 `\r\n` 会把下一条头并进同一个值里）。
 *
 * 🔴 **必须过滤 CR/LF**：Cookie 由站点通过 `document.cookie` 设置，Referer 可由用户手填，
 * 未过滤时 `a=1\r\nX-Evil: 1` 就能往请求里塞任意头（header 注入）。
 * `value` 为空时什么都不做。
 */
static void appendHeader(char *dst, size_t dstSize, size_t *off,
                         const char *name, const char *value) {
    if (!value || !value[0]) return;
    int n = snprintf(dst + *off, dstSize - *off, "%s: ", name);
    if (n < 0 || (size_t) n >= dstSize - *off) return;
    *off += (size_t) n;
    for (size_t i = 0; value[i] && *off + 2 < dstSize; ++i) {
        char c = value[i];
        if (c == '\r' || c == '\n') continue;
        dst[(*off)++] = c;
    }
    dst[(*off)++] = '\r';
    dst[(*off)++] = '\n';
    dst[*off] = '\0';
}

/**
 * v2.8.3：**网络源**（http/https/HLS）重封装的中断回调。
 *
 * 为什么必须有：本地文件读取是纯 IO、可预期；而网络源一旦 CDN 卡住，
 * `avformat_open_input` / `av_read_frame` 会**无限阻塞**在整个实时字幕线程上，
 * 用户看到的就是「实时字幕一直停在准备中」且关不掉。
 *
 * `opaque` 指向一个 `int64_t` 绝对截止时刻（`nowUs()` 的微秒值）；
 * 返回 1 表示「请求中断当前阻塞调用」（FFmpeg 会以 AVERROR_EXIT 返回）。
 */
static int remuxInterruptCb(void *opaque) {
    auto *deadline = static_cast<int64_t *>(opaque);
    if (deadline && *deadline > 0 && nowUs() > *deadline) return 1;
    return 0;
}
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

/**
 * 返回 libavformat 的版本标识（如 "Lavf63.7.100"）—— 启动时打一条，验证头/.so 匹配。
 */
JNIEXPORT jstring JNICALL
Java_com_example_vr_FfmpegRemuxer_nativeVersion(JNIEnv *env, jclass) {
    ensureFfLog();
    char buf[64];
    unsigned v = avformat_version();
    snprintf(buf, sizeof(buf), "Lavf%u.%u.%u", AV_VERSION_MAJOR(v),
             AV_VERSION_MINOR(v), AV_VERSION_MICRO(v));
    return env->NewStringUTF(buf);
}

/**
 * 共用的转封装实现（JNI 入口的公共体）。
 *
 * `fmtName`：`"matroska"`=MKV（字幕链路兜底，兼容性最宽）；`"mov"`=MP4（**容器修复**用，
 * 本库 libavformat 已编入 movenc —— 会写 stbl + **ctts**，**保留 B 帧**且可 seek）。
 *
 * 返回 0 表示成功；负数为失败码（Java 侧映射成可读文案）：
 *   -1 打不开输入        -2 find_stream_info 失败
 *   -3 写头失败          -4 无可写流
 *   -5 打不开输出文件    -6 建流/拷参数失败
 *   -7 写帧失败（写出了部分数据时也按失败处理，产物不可信）
 *
 * ⚠️ 同步调用，在调用方的后台线程执行。1.19GB 实测约 30~60s（纯 IO，不解码）。
 */
static int remuxJni(JNIEnv *env, jstring jIn, jstring jOut, const char *fmtName,
                    jstring jUa, jstring jRef, jstring jCookie, jstring jCaFile,
                    jint maxSeconds, jboolean isNetwork) {
    ensureFfLog();
    const char *inPath = env->GetStringUTFChars(jIn, nullptr);
    const char *outPath = env->GetStringUTFChars(jOut, nullptr);
    const char *ua = jUa ? env->GetStringUTFChars(jUa, nullptr) : nullptr;
    const char *referer = jRef ? env->GetStringUTFChars(jRef, nullptr) : nullptr;
    const char *cookie = jCookie ? env->GetStringUTFChars(jCookie, nullptr) : nullptr;
    const char *caFile = jCaFile ? env->GetStringUTFChars(jCaFile, nullptr) : nullptr;
    if (!inPath || !outPath) {
        if (inPath) env->ReleaseStringUTFChars(jIn, inPath);
        if (outPath) env->ReleaseStringUTFChars(jOut, outPath);
        if (ua) env->ReleaseStringUTFChars(jUa, ua);
        if (referer) env->ReleaseStringUTFChars(jRef, referer);
        if (cookie) env->ReleaseStringUTFChars(jCookie, cookie);
        if (caFile) env->ReleaseStringUTFChars(jCaFile, caFile);
        return -1;
    }

    AVFormatContext *in = nullptr;
    AVFormatContext *out = nullptr;
    AVDictionary *opts = nullptr;
    int *streamMap = nullptr;
    int ret = 0;
    // 在顶部声明：C++ 禁止 goto 跳过带初始化的变量声明，而下面多处 `goto done`
    // 会越过「建流」段（第一版把 audioStreams 写在段内，编译直接报 6 处）。
    int audioStreams = 0;
    char errbuf[128];

    // v2.8.3：网络源的总预算（微秒，0=不限）。到点后 interrupt 回调会让阻塞中的
    // avformat 调用以 AVERROR_EXIT 返回，避免实时字幕线程被卡死。
    int64_t deadlineUs = 0;
    if (isNetwork) {
        const int64_t budget = (maxSeconds > 0 ? maxSeconds : 600) * 1000000LL;
        deadlineUs = nowUs() + budget;
    }

    // 1) 打开输入。⚠️ AVI 的 OpenDML 多段（AVIX）由 libavformat 通过 `indx` 索引处理，
    //    用户那个 1.19GB 样本（无 idx1、有 indx）实测能被完整读出。
    //    v2.8.3：网络源（http/https/HLS）额外注入以下选项 ——
    //    ① `protocol_whitelist`：m3u8 会引出子清单 / 分片 / **AES-128 密钥**，
    //       不放行 `crypto` 时加密 HLS 会直接失败；`httpproxy` 兼容系统代理。
    //    ② `user_agent` / `headers`：防盗链 CDN 只认桌面 UA + 页面 Referer
    //       （与播放器侧 HttpPlaybackHeaders 同一口径）。
    //    ③ `rw_timeout`：单次 IO 15s，配合 interrupt 回调形成两层兜底。
    if (isNetwork) {
        av_dict_set(&opts, "protocol_whitelist",
                    "file,crypto,data,http,https,tcp,tls,httpproxy", 0);
        av_dict_set(&opts, "rw_timeout", "15000000", 0);
        av_dict_set(&opts, "reconnect", "1", 0);
        av_dict_set(&opts, "reconnect_streamed", "1", 0);
        // 🔴 v2.8.3：**TLS 信任链** —— 这是「所有 https 流都打不开」的根因。
        //   本项目的 mbedtls 找不到 Android 的 CA：系统证书是
        //   `/system/etc/security/cacerts/` 下 145 个独立 PEM，而 mbedtls 只认**单个 bundle**。
        //   实测日志：`mbedtls_ssl_get_verify_result ... flags: 8`
        //            `The certificate is not correctly signed by the trusted CA` → AVERROR(EIO)。
        //   Java 侧已把系统 CA 拼成 bundle 传进来；读不到时才退化为关闭校验
        //   （本链路只用于取音频，不放行任何凭据类请求）。
        if (caFile && caFile[0]) {
            av_dict_set(&opts, "ca_file", caFile, 0);
            LOGI("TLS: 使用 CA bundle -> %s", caFile);
        } else {
            av_dict_set(&opts, "verify", "0", 0);
            LOGW("TLS: 未提供 CA bundle → 关闭证书校验（仅本音频链路）");
        }
        if (ua && ua[0]) av_dict_set(&opts, "user_agent", ua, 0);
        // 🔴 v2.8.3：Referer + **Cookie** 合并成一个 `headers` 串。
        //   需要登录态的站点（sessionid / cf_clearance）只认 Cookie：只给 UA + Referer
        //   仍是 403，而 403 在 avformat 层只表现为一句 `I/O error`，极难定位。
        //   ⚠️ 每条必须以 `\r\n` 结尾（FFmpeg 不补）；CR/LF 在 appendHeader 里被过滤。
        {
            char hdr[4096];
            size_t off = 0;
            hdr[0] = '\0';
            appendHeader(hdr, sizeof(hdr), &off, "Referer", referer);
            appendHeader(hdr, sizeof(hdr), &off, "Cookie", cookie);
            if (off > 0) {
                av_dict_set(&opts, "headers", hdr, 0);
                LOGI("已附带请求头: Referer=%s Cookie=%s",
                     (referer && referer[0]) ? "有" : "无",
                     (cookie && cookie[0]) ? "有" : "无");
            }
        }
        // 必须先 alloc 才能挂 interrupt_callback（open 之前就要生效，
        // 否则域名解析/握手阶段卡住时无处中断）。
        in = avformat_alloc_context();
        if (!in) { ret = -1; goto done; }
        in->interrupt_callback.callback = remuxInterruptCb;
        in->interrupt_callback.opaque = &deadlineUs;
        // 关键：所有子请求（variant / 分片 / key）都走这个回调拿网络选项
        in->io_open = ioOpenWithNetOpts;
        g_netOpts = opts;
    }
    if ((ret = avformat_open_input(&in, inPath, nullptr, &opts)) < 0) {
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
    // v2.8.3：网络源没有总时长 = 直播（或无结束标记的清单）→ 重封装永远不会结束，
    // 直接以专用码 -8 返回，由 Java 侧显示「直播不支持实时字幕」。
    // ⚠️ 但「0 个流」是**解析失败**（子清单/分片全部失败），不能误判成直播 ——
    //    实测：master 能读、variant 全挂时 nb_streams=0 且 duration=0，曾被错报成直播。
    if (isNetwork && in->nb_streams == 0) {
        LOGE("清单解析出 0 个流（子清单/分片全部失败？）→ 按解析失败处理");
        ret = -2;
        goto done;
    }
    if (isNetwork && in->duration <= 0) {
        LOGE("网络流无总时长（直播？）→ 放弃重封装");
        ret = -8;
        goto done;
    }
    LOGI("输入: %u 个流, 时长 %.1fs → 容器 %s", in->nb_streams,
         in->duration > 0 ? in->duration / 1000000.0 : 0.0, fmtName);

    // 2) 输出上下文：显式指定容器名（不靠文件后缀猜）。
    if ((ret = avformat_alloc_output_context2(&out, nullptr, fmtName, outPath)) < 0) {
        av_strerror(ret, errbuf, sizeof(errbuf));
        LOGE("alloc_output_context2(%s) 失败: %s", fmtName, errbuf);
        ret = -3;
        goto done;
    }

    // 3) 逐流建流 + 拷参数。
    //    v2.8.3：**只保留音频流**。网络源（尤其 HLS 点播）重封装会顺序下载所有分片，
    //    若把视频也写进临时 MKV，一部 1 小时 1080p 的片子就是几个 GB 的缓存占用；
    //    而字幕链路只读音轨。`streamMap` 记录「输入流索引 → 输出流索引」，
    //    -1 表示该流被丢弃（读包时直接跳过，不写盘）。
    if (in->nb_streams > 0) {
        streamMap = static_cast<int *>(av_malloc(sizeof(int) * in->nb_streams));
        if (!streamMap) { ret = -6; goto done; }
        for (unsigned i = 0; i < in->nb_streams; i++) streamMap[i] = -1;
    }
    for (unsigned i = 0; i < in->nb_streams; i++) {
        AVStream *is = in->streams[i];
        if (is->codecpar->codec_type != AVMEDIA_TYPE_AUDIO) continue;
        AVStream *os = avformat_new_stream(out, nullptr);
        if (!os) { ret = -6; goto done; }
        if ((ret = avcodec_parameters_copy(os->codecpar, is->codecpar)) < 0) {
            av_strerror(ret, errbuf, sizeof(errbuf));
            LOGE("codec_parameters_copy(流 %u) 失败: %s", i, errbuf);
            ret = -6;
            goto done;
        }
        os->codecpar->codec_tag = 0;   // mkv 不用原容器里的 tag
        streamMap[i] = os->index;
        audioStreams++;
    }
    if (out->nb_streams == 0 || audioStreams == 0) {
        LOGE("没有可用的音频流（共 %u 个流）→ 放弃重封装", in->nb_streams);
        ret = -4;
        goto done;
    }

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
        long long written = 0, skipped = 0, dropped = 0, failed = 0;
        bool truncated = false;
        while ((ret = av_read_frame(in, pkt)) >= 0) {
            // v2.8.3：网络源到点就停 —— 已写出的音频仍然可用（字幕覆盖到截断点），
            // 比继续卡在慢 CDN 上最后整个失败更有价值。
            if (deadlineUs > 0 && nowUs() > deadlineUs) {
                truncated = true;
                break;
            }
            if (pkt->stream_index < 0 || pkt->stream_index >= (int) in->nb_streams) {
                dropped++;
                av_packet_unref(pkt);
                continue;
            }
            // 只搬音频：视频包直接丢弃，不进临时文件（见第 3 步说明）
            const int outIdx = streamMap ? streamMap[pkt->stream_index] : pkt->stream_index;
            if (outIdx < 0) {
                dropped++;
                av_packet_unref(pkt);
                continue;
            }
            if (pkt->pts == AV_NOPTS_VALUE || pkt->dts == AV_NOPTS_VALUE) {
                skipped++;
                av_packet_unref(pkt);
                continue;
            }
            AVStream *is = in->streams[pkt->stream_index];
            AVStream *os = out->streams[outIdx];
            av_packet_rescale_ts(pkt, is->time_base, os->time_base);
            pkt->pos = -1;
            pkt->stream_index = outIdx;
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
        LOGI("remux 完成: 写出 %lld 包（跳过无时间戳 %lld，丢弃非音频 %lld，失败 %lld%s）",
             written, skipped, dropped, failed, truncated ? "，已按时限截断" : "");
        // 🔴 v2.4.27：`av_read_frame` 正常读完文件时返回 **AVERROR_EOF**（= -541478725，
        //   即 -("EOF ")），**不是 0**。此前直接把循环结束后的 `ret` 返回，
        //   于是「转封装成功」被误判成失败（日志：写出 16149 包、失败 0，却报 code=-541478725）
        //   → 表现为「自动修复失败」。这里把 EOF 归一为成功；其它负值才真是错误。
        if (ret == AVERROR_EOF) ret = 0;
        // v2.8.3：被 interrupt 回调打断（AVERROR_EXIT）时，只要已经写出过音频包，
        // 就按「部分成功」处理 —— 否则慢网下每次都是彻底失败，用户连前段字幕都拿不到。
        if (ret == AVERROR_EXIT && written > 0) { truncated = true; ret = 0; }
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
    if (streamMap) av_free(streamMap);
    g_netOpts = nullptr;          // 先断开全局引用，再释放 opts，避免悬垂指针
    if (opts) av_dict_free(&opts);
    if (inPath) env->ReleaseStringUTFChars(jIn, inPath);
    if (outPath) env->ReleaseStringUTFChars(jOut, outPath);
    if (ua) env->ReleaseStringUTFChars(jUa, ua);
    if (referer) env->ReleaseStringUTFChars(jRef, referer);
    if (cookie) env->ReleaseStringUTFChars(jCookie, cookie);
    if (caFile) env->ReleaseStringUTFChars(jCaFile, caFile);
    if (ret == 0) LOGI("remux 成功 → %s", outPath);
    else LOGE("remux 失败 code=%d → %s", ret, outPath);
    return ret;
}

/**
 * 转封装为 Matroska（MKV）—— 字幕链路 `AudioTee` 的兜底（兼容性最宽）。
 */
JNIEXPORT jint JNICALL
Java_com_example_vr_FfmpegRemuxer_nativeRemuxToMkv(JNIEnv *env, jclass,
                                                   jstring jIn, jstring jOut) {
    return remuxJni(env, jIn, jOut, "matroska", nullptr, nullptr, nullptr, nullptr, 0, JNI_FALSE);
}

/**
 * v2.8.3：**网络源（http/https/HLS）→ MKV** —— 实时字幕在网页流上的音频旁路。
 *
 * 背景：`AudioTee` 用框架 `MediaExtractor` 读音轨，而它**不认 m3u8**（那只是文本清单）。
 * 于是网页流开实时字幕必然走到「音频读取失败（IO）」。这里用 libavformat 直接打开
 * URL（本库已编入 hls demuxer + http/https/tcp/tls/crypto/httpproxy 协议），
 * 把**音轨**重封装成本地 MKV，再交给框架 extractor。
 *
 * `maxSeconds`：重封装预算（超时即截断，已写出的部分仍可用）；<=0 时按 600s。
 * `jUa` / `jReferer`：防盗链 CDN 必需（与播放器侧同一口径）；可为 null。
 * `jCaFile`：**系统 CA 拼成的 bundle 路径** —— 不传的话 mbedtls 会拒绝所有 https
 *   （`certificate is not correctly signed by the trusted CA`）。
 *
 * ⚠️ 同步、耗时（取决于片长与网速）—— 调用方必须在后台线程。
 */
JNIEXPORT jint JNICALL
Java_com_example_vr_FfmpegRemuxer_nativeRemuxUrlToMkv(JNIEnv *env, jclass,
                                                      jstring jUrl, jstring jOut,
                                                      jstring jUa, jstring jReferer,
                                                      jstring jCookie, jstring jCaFile,
                                                      jint maxSeconds) {
    return remuxJni(env, jUrl, jOut, "matroska", jUa, jReferer, jCookie, jCaFile,
                    maxSeconds, JNI_TRUE);
}

/**
 * 转封装为 MP4（mov muxer）—— **容器修复**用。
 *
 * ⚠️ 为什么容器修复必须走 FFmpeg 而不是 MediaExtractor+MediaMuxer：
 *    `MediaMuxer` 要求时间戳单调递增，而含 B 帧的源（fMP4 的 trun 带非零
 *    sample_composition_time_offset → PTS 在解码顺序下非单调）会触发旧实现的
 *    「时间戳倒退就跳过样本」→ 实测把 6302 帧砍到 464 帧（≈2fps）→ 播放极卡。
 *    libavformat 的 `av_interleaved_write_frame` 会按时间缓冲重排、写出 ctts，
 *    **不丢帧**；stbl 采样表保证 ExoPlayer 可 seek。
 */
JNIEXPORT jint JNICALL
Java_com_example_vr_FfmpegRemuxer_nativeRemuxToMp4(JNIEnv *env, jclass,
                                                   jstring jIn, jstring jOut) {
    return remuxJni(env, jIn, jOut, "mov", nullptr, nullptr, nullptr, nullptr, 0, JNI_FALSE);
}

} // extern "C"
