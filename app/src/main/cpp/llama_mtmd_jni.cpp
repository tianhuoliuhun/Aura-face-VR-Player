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

#include <cctype>
#include <dirent.h>
#include <fstream>
#include <set>
#include <thread>

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
 * 探测**物理核数**（折叠超线程 / SMT）。
 *
 * ## 为什么不能信 `availableProcessors()`
 * 在安卓模拟器里它常返回**宿主机逻辑核数**（例如 16 / 32），而其中一半是超线程。
 * 若直接把它当 `n_threads` 传给 llama.cpp → 矩阵乘被**过度订阅**，
 * 上下文切换开销盖过并行收益 → 解码反而更慢甚至卡死。
 * 真机 ARM 大多无 SMT，物理核 == 逻辑核；x86 宿主有超线程，必须折叠。
 *
 * ## 做法
 * 扫 `/sys/devices/system/cpu/cpuN/topology/core_id`，统计**去重后的 core_id 数**
 * （同一物理核上的超线程共享一个 core_id）。读不到则退化到
 * `std::thread::hardware_concurrency()`（逻辑核数）。
 */
int getPhysicalCoreCount() {
    std::set<int> phys;
    DIR * d = opendir("/sys/devices/system/cpu");
    if (d) {
        struct dirent * e;
        while ((e = readdir(d)) != nullptr) {
            std::string name = e->d_name;
            if (name.rfind("cpu", 0) != 0) continue;
            bool allDigit = true;
            for (size_t i = 3; i < name.size(); ++i) {
                if (!std::isdigit(static_cast<unsigned char>(name[i]))) {
                    allDigit = false; break;
                }
            }
            if (!allDigit) continue;                     // 跳过 cpuidle / cpufreq 等
            std::string path = "/sys/devices/system/cpu/" + name + "/topology/core_id";
            std::ifstream f(path);
            int cid = -1;
            if (f >> cid) phys.insert(cid);
        }
        closedir(d);
    }
    if (!phys.empty()) return static_cast<int>(phys.size());
    unsigned n = std::thread::hardware_concurrency();    // 逻辑核（可能被超线程放大）
    return n > 0 ? static_cast<int>(n) : 1;
}

/**
 * 把**标准 UTF-8** 安全地转成 jstring。
 *
 * ## 🔴 为什么不能用 `env->NewStringUTF`
 * ART 的 `NewStringUTF` 要求 **Modified UTF-8**。遇到非法序列（或标准 UTF-8 的
 * **4 字节序列**，如 emoji）时它会**直接 abort()**，而不是返回 nullptr：
 * ```
 * JNI DETECTED ERROR IN APPLICATION: input is not valid Modified UTF-8: illegal continuation byte 0
 * Fatal signal 6 (SIGABRT) ... libauravr.so (Java_com_example_vr_LlamaMtmd_nativeComplete+...)
 * ```
 * → 所以「先看返回值、再兜底」的写法**根本救不了**（v2.4.14 就是这么写的，实测无效，
 *   v2.4.16 实测崩溃即为此）。
 *
 * ## 做法
 * 自己把 UTF-8 解码成 UTF-16，再走 `env->NewString`：
 *   · 1/2/3 字节序列 → 对应码点；⚠️ 若落在**代理区**（U+D800~U+DFFF）→ 换成 U+FFFD
 *   · 4 字节序列 → 拆成 **UTF-16 代理对**
 *   · 任何非法 / 截断 / 超范围序列 → U+FFFD（替换字符），**绝不 abort**
 *
 * ## 为什么模型会产出非法 UTF-8
 * llama.cpp 是**按 token** 逐个 decode 的，而某些 token 的 piece 是**不完整的
 * 多字节序列**，逐个拼接就可能拼出非法串。
 * 📌 实测（v2.4.16）：本地翻译输出退化成「一长串重复文字」直到吃满 maxTokens 时，
 *    正好踩到这个 abort —— 也就是说**输出失控**与**崩溃**是同一个场景里一起出现的。
 */
jstring utf8ToJString(JNIEnv * env, const std::string & s) {
    std::vector<jchar> u16;
    u16.reserve(s.size() + 8);

    const unsigned char * p = reinterpret_cast<const unsigned char *>(s.data());
    const size_t n = s.size();
    size_t i = 0;

    while (i < n) {
        const unsigned char c = p[i];
        uint32_t cp = 0;
        size_t extra = 0;

        if (c < 0x80) {
            cp = c;                       extra = 0;   // 1 字节
        } else if ((c & 0xE0) == 0xC0) {
            cp = c & 0x1Fu;               extra = 1;   // 2 字节
        } else if ((c & 0xF0) == 0xE0) {
            cp = c & 0x0Fu;               extra = 2;   // 3 字节
        } else if ((c & 0xF8) == 0xF0) {
            cp = c & 0x07u;               extra = 3;   // 4 字节
        } else {
            u16.push_back(0xFFFD); ++i; continue;      // 非法起始字节
        }

        if (i + extra >= n) {                          // 被截断（这也是最常见的一种）
            u16.push_back(0xFFFD);
            break;
        }

        bool contOk = true;
        for (size_t k = 1; k <= extra; ++k) {
            const unsigned char cc = p[i + k];
            if ((cc & 0xC0) != 0x80) { contOk = false; break; }
            cp = (cp << 6) | (cc & 0x3Fu);
        }
        if (!contOk) {                                 // 续字节非法
            u16.push_back(0xFFFD); ++i; continue;
        }
        i += extra + 1;

        if (cp > 0x10FFFF) { u16.push_back(0xFFFD); continue; }   // 超出 Unicode

        if (cp <= 0xFFFF) {
            if (cp >= 0xD800 && cp <= 0xDFFF) u16.push_back(0xFFFD);  // 裸代理码点非法
            else                              u16.push_back(static_cast<jchar>(cp));
        } else {
            const uint32_t v = cp - 0x10000u;          // 4 字节 → 代理对
            u16.push_back(static_cast<jchar>(0xD800u + (v >> 10)));
            u16.push_back(static_cast<jchar>(0xDC00u + (v & 0x3FFu)));
        }
    }

    // ⚠️ 空串时 data() 可能是 nullptr，别直接传给 NewString
    static const jchar kEmpty = 0;
    return env->NewString(u16.empty() ? &kEmpty : u16.data(),
                          static_cast<jsize>(u16.size()));
}

/**
 * 安全的 jstring 构造（**永远不 abort**）。
 *
 * ⚠️ Kotlin 侧声明的是**非空 `String`**，所以这里也保证不返回 nullptr。
 * ⚠️ 具体解码交给 [utf8ToJString] —— 那里解释了为什么**不能**用 `NewStringUTF`。
 */
jstring newStringSafe(JNIEnv * env, const std::string & s) {
    jstring r = utf8ToJString(env, s);
    if (r == nullptr) {
        // 理论上到不了这里（NewString 只在 OOM 时失败），但兜一层保险
        env->ExceptionClear();
        LOGW("jstring 构造失败（%zu 字节），降级为空串", s.size());
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
    //   nThreads<=0 → 自动探测**物理核数**（折叠超线程，见 getPhysicalCoreCount）；
    //   >0 → 显式覆盖（调试用）。
    //   关键：模拟器里 `availableProcessors()` 常返回**宿主机逻辑核数**（16/32），
    //   直接拿来用会**过度订阅** → 解码更慢甚至卡死；ARM 真机无 SMT 则物理==逻辑。
    //   最终钳到 [1, 8]：8 已覆盖绝大多数手机（含 8 核旗舰），
    //   又能防止模拟器把 16~32 核当真。v2.4.16 之前硬编码 4 → 8 核机白白浪费一半核。
    int threads = (int) nThreads;
    if (threads <= 0) threads = getPhysicalCoreCount();
    if (threads < 1) threads = 1;
    if (threads > 8) threads = 8;

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

    // 🔴 v2.4.17：开 Flash Attention。对 CPU 后端它是**等价**的注意力实现，
    //   但走分块计算 → 注意力阶段更快、且**峰值显存更低**（不影响已分配的 KV cache 总量，
    //   但本项目 KV 与 n_ctx 成正比、内存本就紧张，低峰值更稳）。
    //   小模型短文本收益有限，但零行为风险、纯增益，故默认开启。
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;

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
        // 🔴 v2.4.16：补上 **重复惩罚 + top_k / top_p**。
        //    此前采样链只有 temp + dist → 小模型极易退化成「一句话无限重复」——
        //    实测本地翻译输出成一长串**重复的韩文**，直到**吃满 maxTokens** 才停
        //    （日志：`推理完成 115058ms / 512 字`，512 就是上限），
        //    而且那串重复文本正好触发了 NewStringUTF 的 abort（见 utf8ToJString 的说明）。
        //
        //    顺序遵循 llama.cpp 官方示例：penalties → top_k → top_p → temp → dist。
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(
                llama_vocab_n_tokens(g_vocab),
                /*penalty_last_n  */ 64,      // 只惩罚最近 64 个 token（0 = 关闭）
                /*penalty_repeat  */ 1.15f,   // > 1.0 才生效；1.0 = 关闭
                /*penalty_freq    */ 0.0f,    // 频率惩罚，0 = 关闭
                /*penalty_present */ 0.0f));  // 存在惩罚，0 = 关闭
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.95f, /*min_keep=*/1));
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
