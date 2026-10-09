// ============================================================================
// llama.cpp + libmtmd 的 JNI 封装（v2.4.13 引入 / v2.4.14 并发加固）
// ----------------------------------------------------------------------------
// 为什么自己写而不是用现成 AAR：
//   现用的 `dev.ffmpegkit-maintained:llama-android` **不含 libmtmd**，
//   其 native 接口只有 5 个方法、没有任何接收图像的入口 → 无法做「本地看画面」。
//   因此本项目用 NDK 自建了 `libllama.so` / `libmtmd.so`（见项目记忆的编译命令），
//   这里只负责把它们暴露给 Kotlin。
//
// 🔴 v2.4.14 修的是什么（运行时闪退的根因）：
//   此前所有入口**完全无锁**地读写进程级的全局指针（g_model / g_ctx / g_mtmd），
//   而 Kotlin 侧的推理原本跑在 `Dispatchers.IO`（默认 **64 并发**），
//   字幕翻译与弹幕生成又是**两条独立链路** —— 一旦并发：
//     · 同一 g_ctx 上同时 `llama_memory_clear` + `llama_decode` → 数据竞争 → SIGSEGV
//     · `ensureLoaded` 先 release() 再 nativeInit()，中间被插队 → use-after-free
//   现在**全部入口持同一把全局互斥锁**，一次推理原子完成。
//
// ⚠️ 编译注意：本文件在 **D 盘项目路径下 clang 无法启动**
//    （`Exception Code: 0x000006BA` RPC 不可用，疑似安全软件拦截该路径），
//    用 `-fsyntax-only` 自检时要把文件复制到 C 盘临时目录再跑。
//
// API 版本：llama.cpp b9878（新命名，如 `llama_model_load_from_file` /
//   `llama_init_from_model` / `llama_model_get_vocab`）—— 与旧文档里的
//   `llama_load_model_from_file` 等不同，改代码时注意别照抄老例子。
// ============================================================================

#include <jni.h>
#include <android/log.h>

#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define LOG_TAG "LlamaMtmdJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

llama_model       * g_model = nullptr;
llama_context     * g_ctx   = nullptr;
mtmd_context      * g_mtmd  = nullptr;
const llama_vocab * g_vocab = nullptr;

/**
 * 🔴 **全局互斥锁**（v2.4.14）。
 *
 * ## 为什么必须有
 * 上面那几个指针是**进程级全局状态**，而 Kotlin 侧的 `complete()` 原本跑在
 * `Dispatchers.IO`（默认 64 并发）。字幕翻译与弹幕生成是两条独立链路，
 * 一旦并发就会在同一 ctx 上同时 `llama_memory_clear` + `llama_decode`
 * → 数据竞争 → **SIGSEGV**（native 崩溃，Java 层 catch 不到）。
 *
 * ## 为什么用「整把锁」而不是细粒度
 * 一次推理（清 KV → decode → 采样）**必须原子完成**：中间被插队会拿到半清空的 KV。
 * 代价是「翻译与弹幕不能真并行」—— 但同一个 ctx 本来就不支持并发，排队才是正确语义。
 *
 * ⚠️ Kotlin 侧另有一层**单线程推理调度器**做前置排队，
 *    目的不是替代这把锁，而是避免几十个线程空等锁（白占资源）。
 */
std::mutex g_mutex;

/// 后端只初始化一次（`llama_backend_init` 虽幂等，但没必要重复调用）。
bool g_backend_inited = false;

/// 单次 decode 的批大小。CPU 推理下 512 是稳当的取值。
constexpr int32_t N_BATCH = 512;

/// prompt 字符上限（防御：极端长输入会撑爆 KV cache 并触发 native abort）。
constexpr size_t MAX_PROMPT_CHARS = 8192;

/// 生成的 token 上限（防御性钳位）。
constexpr int MAX_OUTPUT_TOKENS = 1024;

/**
 * **视觉 token 上限**（v2.4.14 新增）。
 *
 * ⚠️ 此前没设，走的是 mmproj metadata 里的默认值 —— 对**动态分辨率**的视觉模型
 *    （Qwen-VL 系列就是）来说，一张稍大的图就可能吃掉上千个 token，
 *    直接撑爆 `n_ctx` → `llama_decode` 失败，严重时 native abort。
 * 768 对「看个大概画面来生成弹幕」这个用途足够，且给文本留足空间。
 */
constexpr int IMAGE_MAX_TOKENS = 768;

/// ⚠️ 内部函数：**调用方必须已持 g_mutex**。
void freeAllLocked() {
    if (g_mtmd)  { mtmd_free(g_mtmd);          g_mtmd  = nullptr; }
    if (g_ctx)   { llama_free(g_ctx);          g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model);  g_model = nullptr; }
    g_vocab = nullptr;
}

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string r = (c != nullptr) ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return r;
}

/**
 * 构造 jstring 并兜底。
 *
 * ⚠️ `NewStringUTF` 遇到非法 UTF-8 会抛异常并返回 nullptr，
 *    而 Kotlin 侧声明的是**非空 `String`** → 会在 JNI 边界抛 NPE。
 *    模型输出的字节序列不保证是合法 UTF-8（token piece 拼接可能截断多字节字符），
 *    所以这里必须兜底：清掉异常、降级成空串（上层会回落云端引擎）。
 */
jstring newStringSafe(JNIEnv * env, const std::string & s) {
    jstring r = env->NewStringUTF(s.c_str());
    if (r == nullptr) {
        env->ExceptionClear();
        LOGW("输出不是合法 UTF-8（%zu 字节），已降级为空串", s.size());
        r = env->NewStringUTF("");
    }
    return r;
}

/// 把一次 decode + 采样的循环跑完（图文与纯文本共用）。⚠️ 调用方须持锁。
std::string sampleLoop(llama_sampler * smpl, int maxTokens) {
    std::string out;
    if (!g_ctx || !g_vocab) return out;

    for (int i = 0; i < maxTokens; ++i) {
        // -1 = 取最后一个 logits（此时只 decode 了一个 token）
        const llama_token id = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(g_vocab, id)) break;

        char buf[512];
        const int n = llama_token_to_piece(g_vocab, id, buf, (int32_t) sizeof(buf), 0, true);
        if (n > 0) out.append(buf, (size_t) n);

        llama_sampler_accept(smpl, id);

        llama_token tok = id;
        llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("采样阶段 llama_decode 失败，提前结束（已生成 %zu 字节）", out.size());
            break;
        }
    }
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_example_vr_LlamaMtmd_nativeVersion(JNIEnv * env, jclass) {
    return env->NewStringUTF("self-built llama.cpp + libmtmd (arm64-v8a, mutex-guarded)");
}

/**
 * 加载模型（可选加载 mmproj）。
 *
 * @param mmprojPath 空串 = 纯文本模式（不初始化 mtmd）
 * @return 是否成功；mmproj 加载失败**不**让整体失败（纯文本仍可用）
 */
JNIEXPORT jboolean JNICALL
Java_com_example_vr_LlamaMtmd_nativeInit(
        JNIEnv * env, jclass,
        jstring jModelPath, jstring jMmprojPath, jint nCtx, jint nThreads) {

    const std::string modelPath  = jstr(env, jModelPath);
    const std::string mmprojPath = jstr(env, jMmprojPath);
    if (modelPath.empty()) {
        LOGE("模型路径为空");
        return JNI_FALSE;
    }

    // 🔴 全程持锁：释放旧模型 + 加载新模型必须原子。
    //    否则会与正在推理的线程产生 use-after-free（原闪退根因之一）。
    std::lock_guard<std::mutex> lock(g_mutex);

    // 线程数收敛（⚠️ **只在这里**钳位，避免「同一份约束两处登记」）：
    //   llama.cpp 的 n_threads 超过物理核会过度订阅，解码反而更慢甚至卡死。
    //   移动端（含模拟器）实测 4 线程最优；且模拟器上报的核数常是**宿主机**核数，不可信。
    int threads = (int) nThreads;
    if (threads < 1) threads = 4;
    if (threads > 4) threads = 4;

    int ctxSize = (int) nCtx;
    if (ctxSize < 512)   ctxSize = 512;
    if (ctxSize > 32768) ctxSize = 32768;

    freeAllLocked();

    if (!g_backend_inited) {
        llama_backend_init();
        g_backend_inited = true;
    }

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;   // 本构建是 CPU / NEON 版，没有 GPU 后端
    g_model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (!g_model) {
        LOGE("模型加载失败（文件损坏 / 内存不足 / mmap 失败）：%s", modelPath.c_str());
        return JNI_FALSE;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t) ctxSize;
    cparams.n_batch         = N_BATCH;
    cparams.n_threads       = threads;
    cparams.n_threads_batch = threads;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        // 这一条最常见的原因就是 KV cache 分配失败（n_ctx 开太大 / 内存不够）
        LOGE("llama_context 创建失败（n_ctx=%d，多为 KV cache 分配失败）", ctxSize);
        freeAllLocked();
        return JNI_FALSE;
    }
    g_vocab = llama_model_get_vocab(g_model);

    if (!mmprojPath.empty()) {
        mtmd_context_params mpar = mtmd_context_params_default();
        mpar.use_gpu        = false;
        mpar.n_threads      = threads;
        mpar.print_timings  = false;
        // 🔴 v2.4.14：限制视觉 token 数 —— 不设就吃 metadata 默认值，
        //    动态分辨率模型下一张图可能上千 token，直接撑爆 n_ctx。
        mpar.image_max_tokens = IMAGE_MAX_TOKENS;
        g_mtmd = mtmd_init_from_file(mmprojPath.c_str(), g_model, mpar);
        if (!g_mtmd) {
            // ⚠️ 刻意不返回失败：mmproj 坏了/不匹配时，纯文本推理仍然可用，
            //    让上层能降级而不是整个本地功能不可用。
            LOGE("mmproj 加载失败（纯文本模式仍可用）：%s", mmprojPath.c_str());
        } else {
            LOGI("mmproj 已加载，vision=%d，image_max_tokens=%d",
                 mtmd_support_vision(g_mtmd) ? 1 : 0, IMAGE_MAX_TOKENS);
        }
    }

    LOGI("初始化完成：model=%s mmproj=%s n_ctx=%d threads=%d",
         modelPath.c_str(), mmprojPath.c_str(), ctxSize, threads);
    return JNI_TRUE;
}

/** 当前是否具备视觉能力（mmproj 已加载且模型支持 vision）。 */
JNIEXPORT jboolean JNICALL
Java_com_example_vr_LlamaMtmd_nativeHasVision(JNIEnv *, jclass) {
    // 也要持锁：可能与 nativeFree 并发，读到已释放的指针。
    std::lock_guard<std::mutex> lock(g_mutex);
    return (g_mtmd != nullptr && mtmd_support_vision(g_mtmd)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_vr_LlamaMtmd_nativeFree(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    freeAllLocked();
    LOGI("已释放本地模型与 mtmd 上下文");
}

/**
 * 一次完整的推理（图文或纯文本）。
 *
 * @param jRgb  RGB 字节（**3 字节/像素**，无 alpha —— 这是 mtmd 的格式要求）；
 *              为 null 或尺寸非法时走纯文本路径
 * @return 生成的文本；失败返回空串（调用方据此回落）
 */
JNIEXPORT jstring JNICALL
Java_com_example_vr_LlamaMtmd_nativeComplete(
        JNIEnv * env, jclass,
        jstring jPrompt, jstring jSystem,
        jbyteArray jRgb, jint imgW, jint imgH,
        jint maxTokens, jfloat temperature) {

    std::string prompt = jstr(env, jPrompt);
    std::string system = jstr(env, jSystem);

    // 🔴 全程持锁（见文件头的说明）：一次推理原子完成，
    //    同时与 nativeInit / nativeFree 互斥 → 绝不会用到已释放的 ctx。
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_model || !g_ctx || !g_vocab) {
        LOGE("引擎未就绪（model=%p ctx=%p），忽略本次推理",
             (void *) g_model, (void *) g_ctx);
        return newStringSafe(env, "");
    }

    if (!system.empty()) prompt = system + "\n\n" + prompt;
    if (prompt.size() > MAX_PROMPT_CHARS) {
        LOGW("prompt 过长（%zu 字符），截断到 %zu", prompt.size(), MAX_PROMPT_CHARS);
        prompt.resize(MAX_PROMPT_CHARS);
    }

    // 采样参数钳位。
    // ⚠️ temp<=0 时 `llama_sampler_init_dist` 的语义会退化成"按概率抽"而不是
    //    确定性输出，小模型更容易跑偏 → 明确走 greedy（对翻译也更合适）。
    float temp = temperature;
    const bool greedy = (temp <= 0.01f);
    if (temp < 0.01f) temp = 0.01f;
    if (temp > 2.0f)  temp = 2.0f;

    int maxTok = (int) maxTokens;
    if (maxTok <= 0)                maxTok = 128;
    if (maxTok > MAX_OUTPUT_TOKENS) maxTok = MAX_OUTPUT_TOKENS;

    // 每次推理都从干净的 KV 开始（本引擎是"一次性问答"，不留上下文）
    llama_memory_t mem = llama_get_memory(g_ctx);
    if (mem) {
        llama_memory_clear(mem, true);
    } else {
        LOGE("llama_get_memory 返回空，跳过 KV 清理");
    }

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    if (!smpl) {
        LOGE("采样器创建失败");
        return newStringSafe(env, "");
    }
    if (greedy) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    // 图像输入的长度校验（⚠️ 用 64 位算，防 imgW*imgH*3 整数溢出）
    const bool withImage = (g_mtmd != nullptr && jRgb != nullptr && imgW > 0 && imgH > 0);

    std::string out;
    if (withImage) {
        const jsize len = env->GetArrayLength(jRgb);
        const long long need = (long long) imgW * (long long) imgH * 3LL;
        if ((long long) len < need) {
            LOGE("RGB 数据长度不足：%d < %lld", (int) len, need);
            llama_sampler_free(smpl);
            return newStringSafe(env, "");
        }
        std::vector<unsigned char> rgb((size_t) len);
        env->GetByteArrayRegion(jRgb, 0, len, reinterpret_cast<jbyte *>(rgb.data()));

        // ⚠️ prompt 里**必须**包含媒体标记（marker），否则 mtmd_tokenize 返回 1
        //    （「图片数与 prompt 里的 marker 数不匹配」）。
        //    这里自动补上，免得 Kotlin 侧硬编码那个字符串 —— 它由 llama.cpp 决定，
        //    换版本可能变化（见 mtmd_default_marker()）。
        const char * marker = mtmd_default_marker();
        if (marker && *marker && prompt.find(marker) == std::string::npos) {
            prompt = std::string(marker) + "\n" + prompt;
        }

        mtmd_bitmap * bmp = mtmd_bitmap_init((uint32_t) imgW, (uint32_t) imgH, rgb.data());
        if (!bmp) {
            LOGE("mtmd_bitmap_init 失败（%dx%d）", (int) imgW, (int) imgH);
            llama_sampler_free(smpl);
            return newStringSafe(env, "");
        }

        mtmd_input_chunks * chunks = mtmd_input_chunks_init();
        mtmd_input_text text;
        text.text          = prompt.c_str();
        text.text_len      = prompt.size();
        text.add_special   = true;
        text.parse_special = true;

        const mtmd_bitmap * bitmaps[1] = { bmp };
        int32_t rc = mtmd_tokenize(g_mtmd, chunks, &text, bitmaps, 1);
        if (rc != 0) {
            // 返回码含义：1 = 图片数与 prompt 里的 marker 数不匹配；2 = 媒体预处理失败
            LOGE("mtmd_tokenize 失败 rc=%d", rc);
            mtmd_input_chunks_free(chunks);
            mtmd_bitmap_free(bmp);
            llama_sampler_free(smpl);
            return newStringSafe(env, "");
        }

        llama_pos n_past = 0;
        rc = mtmd_helper_eval_chunks(g_mtmd, g_ctx, chunks, 0, 0, N_BATCH, true, &n_past);
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bmp);
        if (rc != 0) {
            LOGE("mtmd_helper_eval_chunks 失败 rc=%d（n_ctx 是否够放下图片 token？）", rc);
            llama_sampler_free(smpl);
            return newStringSafe(env, "");
        }
        out = sampleLoop(smpl, maxTok);
    } else {
        // 纯文本：自己 tokenize 后 decode，再进同一套采样循环
        const int32_t maxTokIn = (int32_t) prompt.size() + 32;
        std::vector<llama_token> toks((size_t) maxTokIn);
        const int32_t n = llama_tokenize(
                g_vocab, prompt.c_str(), (int32_t) prompt.size(),
                toks.data(), maxTokIn, true, true);
        if (n <= 0) {
            LOGE("llama_tokenize 失败 n=%d", n);
            llama_sampler_free(smpl);
            return newStringSafe(env, "");
        }
        toks.resize((size_t) n);

        llama_batch batch = llama_batch_get_one(toks.data(), n);
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("prompt decode 失败（prompt %zu 字符，n_ctx=%u 是否不够？）",
                 prompt.size(), (unsigned) llama_n_ctx(g_ctx));
            llama_sampler_free(smpl);
            return newStringSafe(env, "");
        }
        out = sampleLoop(smpl, maxTok);
    }

    llama_sampler_free(smpl);
    return newStringSafe(env, out);
}

} // extern "C"
