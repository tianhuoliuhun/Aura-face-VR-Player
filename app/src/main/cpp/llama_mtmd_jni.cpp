// ============================================================================
// llama.cpp + libmtmd 的 JNI 封装（v2.4.13）
// ----------------------------------------------------------------------------
// 为什么自己写而不是用现成 AAR：
//   现用的 `dev.ffmpegkit-maintained:llama-android` **不含 libmtmd**，
//   其 native 接口只有 5 个方法、没有任何接收图像的入口 → 无法做「本地看画面」。
//   因此本项目用 NDK 自建了 `libllama.so` / `libmtmd.so`（见项目记忆的编译命令），
//   这里只负责把它们暴露给 Kotlin。
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

#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define LOG_TAG "LlamaMtmdJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

llama_model       * g_model = nullptr;
llama_context     * g_ctx   = nullptr;
mtmd_context      * g_mtmd  = nullptr;
const llama_vocab * g_vocab = nullptr;

/// 单次 decode 的批大小。CPU 推理下 512 是稳当的取值。
constexpr int32_t N_BATCH = 512;

void freeAll() {
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

/// 把一次 decode + 采样的循环跑完（图文与纯文本共用）。
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
            LOGE("采样阶段 llama_decode 失败，提前结束");
            break;
        }
    }
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_example_vr_LlamaMtmd_nativeVersion(JNIEnv * env, jclass) {
    return env->NewStringUTF("self-built llama.cpp + libmtmd (arm64-v8a)");
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

    freeAll();
    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;   // 本构建是 CPU / NEON 版，没有 GPU 后端
    g_model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (!g_model) {
        LOGE("模型加载失败：%s", modelPath.c_str());
        return JNI_FALSE;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (uint32_t) nCtx;
    cparams.n_batch         = N_BATCH;
    cparams.n_threads       = nThreads;
    cparams.n_threads_batch = nThreads;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("llama_context 创建失败");
        freeAll();
        return JNI_FALSE;
    }
    g_vocab = llama_model_get_vocab(g_model);

    if (!mmprojPath.empty()) {
        mtmd_context_params mpar = mtmd_context_params_default();
        mpar.use_gpu       = false;
        mpar.n_threads     = nThreads;
        mpar.print_timings = false;
        g_mtmd = mtmd_init_from_file(mmprojPath.c_str(), g_model, mpar);
        if (!g_mtmd) {
            // ⚠️ 刻意不返回失败：mmproj 坏了/不匹配时，纯文本推理仍然可用，
            //    让上层能降级而不是整个本地功能不可用。
            LOGE("mmproj 加载失败（纯文本模式仍可用）：%s", mmprojPath.c_str());
        } else {
            LOGI("mmproj 已加载，vision=%d", mtmd_support_vision(g_mtmd) ? 1 : 0);
        }
    }

    LOGI("初始化完成：model=%s mmproj=%s ctx=%d threads=%d",
         modelPath.c_str(), mmprojPath.c_str(), nCtx, nThreads);
    return JNI_TRUE;
}

/** 当前是否具备视觉能力（mmproj 已加载且模型支持 vision）。 */
JNIEXPORT jboolean JNICALL
Java_com_example_vr_LlamaMtmd_nativeHasVision(JNIEnv *, jclass) {
    return (g_mtmd != nullptr && mtmd_support_vision(g_mtmd)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_vr_LlamaMtmd_nativeFree(JNIEnv *, jclass) {
    freeAll();
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

    if (!g_ctx) return env->NewStringUTF("");

    std::string prompt = jstr(env, jPrompt);
    std::string system = jstr(env, jSystem);
    if (!system.empty()) {
        prompt = system + "\n\n" + prompt;
    }

    // 每次推理都从干净的 KV 开始（本引擎是"一次性问答"，不留上下文）
    llama_memory_clear(llama_get_memory(g_ctx), true);

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    const bool withImage = (g_mtmd != nullptr && jRgb != nullptr && imgW > 0 && imgH > 0);

    std::string out;
    if (withImage) {
        const jsize len = env->GetArrayLength(jRgb);
        if (len < (jsize) imgW * imgH * 3) {
            LOGE("RGB 数据长度不足：%d < %d", (int) len, (int) (imgW * imgH * 3));
            llama_sampler_free(smpl);
            return env->NewStringUTF("");
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
            LOGE("mtmd_bitmap_init 失败");
            llama_sampler_free(smpl);
            return env->NewStringUTF("");
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
            return env->NewStringUTF("");
        }

        llama_pos n_past = 0;
        rc = mtmd_helper_eval_chunks(g_mtmd, g_ctx, chunks, 0, 0, N_BATCH, true, &n_past);
        mtmd_input_chunks_free(chunks);
        mtmd_bitmap_free(bmp);
        if (rc != 0) {
            LOGE("mtmd_helper_eval_chunks 失败 rc=%d", rc);
            llama_sampler_free(smpl);
            return env->NewStringUTF("");
        }
        out = sampleLoop(smpl, maxTokens);
    } else {
        // 纯文本：自己 tokenize 后 decode，再进同一套采样循环
        const int32_t maxTok = (int32_t) prompt.size() + 32;
        std::vector<llama_token> toks((size_t) maxTok);
        const int32_t n = llama_tokenize(
                g_vocab, prompt.c_str(), (int32_t) prompt.size(),
                toks.data(), maxTok, true, true);
        if (n <= 0) {
            LOGE("llama_tokenize 失败 n=%d", n);
            llama_sampler_free(smpl);
            return env->NewStringUTF("");
        }
        toks.resize((size_t) n);

        llama_batch batch = llama_batch_get_one(toks.data(), n);
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("prompt decode 失败");
            llama_sampler_free(smpl);
            return env->NewStringUTF("");
        }
        out = sampleLoop(smpl, maxTokens);
    }

    llama_sampler_free(smpl);
    return env->NewStringUTF(out.c_str());
}

} // extern "C"
