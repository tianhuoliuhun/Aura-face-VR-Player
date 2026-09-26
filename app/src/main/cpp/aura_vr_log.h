// ============================================================================
// aura_vr_log.h —— native 层日志与 OpenXR 错误码翻译
// ----------------------------------------------------------------------------
// P0 阶段的第一生产力：把 xrResult 翻译成人能读的字符串。
// OpenXR 的错误码是负数，直接打十进制完全无法排查，必须翻译。
// ============================================================================

#ifndef AURA_VR_LOG_H
#define AURA_VR_LOG_H

#include <android/log.h>

#define AURA_LOG_TAG "AuraVR"

// AURA_LOGV 默认编译掉（Verbose 在真机上会刷屏）：
// 需要时在 CMake 里加 -DAURA_ENABLE_VERBOSE_LOG=1 即可打开。
#if defined(AURA_ENABLE_VERBOSE_LOG) && AURA_ENABLE_VERBOSE_LOG
#  define AURA_LOGV(...) __android_log_print(ANDROID_LOG_VERBOSE, AURA_LOG_TAG, __VA_ARGS__)
#else
#  define AURA_LOGV(...) ((void)0)
#endif

#define AURA_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  AURA_LOG_TAG, __VA_ARGS__)
#define AURA_LOGW(...) __android_log_print(ANDROID_LOG_WARN,  AURA_LOG_TAG, __VA_ARGS__)
#define AURA_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, AURA_LOG_TAG, __VA_ARGS__)

// 把 XrResult 转成可读字符串（不依赖 openxr.h，用数值映射，避免头文件耦合）
const char* aura_vr_result_str(long result);

// 统一的结果检查：失败即打印「文件:行 表达式 -> 错误描述」并返回 false
// 用法：AURA_CHECK(xrCreateInstance(&ci, &instance));
#define AURA_CHECK(expr)                                                       \
    do {                                                                       \
        long _r = (long)(expr);                                                \
        if (_r < 0) {                                                          \
            AURA_LOGE("❌ %s:%d  %s  ->  %s (%ld)",                            \
                      __FILE__, __LINE__, #expr, aura_vr_result_str(_r), _r);  \
            return false;                                                      \
        }                                                                      \
    } while (0)

// 同上，但只记录不返回（用于清理路径）
#define AURA_CHECK_LOG(expr)                                                   \
    do {                                                                       \
        long _r = (long)(expr);                                                \
        if (_r < 0) {                                                          \
            AURA_LOGE("⚠️ %s:%d  %s  ->  %s (%ld)",                            \
                      __FILE__, __LINE__, #expr, aura_vr_result_str(_r), _r);  \
        }                                                                      \
    } while (0)

#endif // AURA_VR_LOG_H
