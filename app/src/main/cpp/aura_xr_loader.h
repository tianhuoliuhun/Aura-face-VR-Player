/*
 * AuraXrLoader —— 多 OpenXR loader 运行时选择（本地扩展）
 * ============================================================================
 * 背景：一份 APK 要同时支持三类 VR 设备，它们的 OpenXR loader 不同：
 *
 *   华为 VR Glass            libxr_loader.so                （华为 SDK 定制）
 *   PICO 4 Ultra 及之后      libopenxr_loader.so            （Khronos 1.1.x）
 *   PICO Neo3 / PICO 4 等    libopenxr_loader_legacy.so     （Khronos 1.0.34 legacy）
 *   Meta Quest 2+           libopenxr_loader.so            （Khronos 1.1.x）
 *
 * 三者文件名不同 → 可同时打进同一 APK（见 v2.0.194）。本模块负责在**运行时**
 * 按设备选择并 dlopen 对应的 loader，再通过 xrGetInstanceProcAddr 解析出所有
 * 用到的 OpenXR 入口。
 *
 * ## 为什么用宏重定向
 * 直接调用 `xrCreateInstance(...)` 是**链接期**解析 —— 那会把 libauravr.so 的
 * DT_NEEDED 钉死在某一个 loader 上，换设备就失效。
 * 本头文件在包含 openxr.h **之后**把这些函数名 `#define` 到同名的函数指针，
 * 于是 business 代码里 70+ 处 `xrXxx(...)` 调用**一行都不用改**，自动走指针。
 *
 * ## 用法（顺序不能颠倒）
 * ```
 *   AuraXrSelectAndLoad();                              // 1) 按设备选 loader 并 dlopen
 *   AuraXrInitAndroidLoader(env, activity);             // 2) 标准 loader 必须的 KHR 初始化
 *   ... 之后照常调用 xrCreateInstance(...) 等 ...
 * ```
 * ⚠️ 华为 loader 不需要（也可能不支持）xrInitializeLoaderKHR —— 本模块对它做了容错：
 *    函数不存在时跳过而不报错。
 */
#pragma once

#include <jni.h>

// openxr_platform.h 里引用了 EGL 类型（XrGraphicsBindingOpenGLESAndroidKHR 等），
// 必须先包含 EGL 头，否则报 'unknown type name EGLDisplay'。
#include <EGL/egl.h>
#include <GLES3/gl3.h>

// ⚠️ 头文件路径按**华为 SDK 的目录布局**：其 openxr.h 直接位于
//    sdkDemo/openXRsdk/jni/openxr/ 下（该目录已加到 include path），
//    故是 <openxr.h> 而不是 <openxr/openxr.h>。
// ⚠️ v2.4.16：本头**只应被 VR 眼镜接入层引用**（aura_vr_input.cpp / aura_vr_session.cpp），
//    它们已在 CMake 里按 AURA_HAVE_OPENXR 条件编译。
//    这里再加一道显式闸门 —— 一旦将来被**核心**代码误引，会立刻编译失败，
//    而不是悄悄把「必须有 VR SDK」的依赖带回主线（那正是 v2.4.16 之前的问题）。
#if !defined(AURA_HAVE_OPENXR) || !AURA_HAVE_OPENXR
#  error "aura_xr_loader.h 仅限 AURA_HAVE_OPENXR=1（VR 眼镜接入层）引用"
#endif

#include <openxr.h>
#include <openxr_platform.h>

// v2.0.199：华为扩展（手柄可用性/类型探测：xrIsControllerAvailableHW 等）。
// ⚠️ 只有华为 SDK 提供该头，且它只在与华为头同一 include path 下才可用 ——
//    用 __has_include 兜底，避免未来换 SDK 时编译中断。
#if defined(__has_include)
#if __has_include(<openxr_hw.h>)
#include <openxr_hw.h>
#define AURA_HAVE_OPENXR_HW 1
#endif
#endif

// ============================================================================
// 兼容补丁：华为 SDK 的 openxr_platform.h **不含** XR_KHR_loader_init_android
// ----------------------------------------------------------------------------
// 该扩展（xrInitializeLoaderKHR）是后来加入 OpenXR 的，华为 SDK 版本里只有
// XR_KHR_android_create_instance。但**Khronos 标准 loader（PICO / Quest 用）
// 必须**先调 xrInitializeLoaderKHR 才能 xrCreateInstance。
// 故这里按 Khronos 官方定义补上缺失的类型与常量（仅在未定义时生效）。
// ============================================================================
#if !defined(XR_KHR_loader_init_android)

#define XR_KHR_loader_init_android 1
#define XR_KHR_loader_init_android_spec_version 1
#define XR_KHR_loader_init_android_extension_name "XR_KHR_loader_init_android"

#define XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR ((XrStructureType)1000012000)

typedef struct XrLoaderInitInfoAndroidKHR {
  XrStructureType type;
  const void* XR_MAY_ALIAS next;
  void* XR_MAY_ALIAS applicationVM;   // JavaVM*
  void* XR_MAY_ALIAS applicationContext;  // jobject
} XrLoaderInitInfoAndroidKHR;

// ⚠️ 形参用 `const void*` 而不是官方头里的 `const XrLoaderInitInfoBaseHeaderKHR*`：
//    华为 SDK 的 openxr.h 版本较旧，**没有** XrLoaderInitInfoBaseHeaderKHR 类型。
//    二者 ABI 等价（都是结构体指针），loader 侧按官方结构体解释即可，故用 void* 最兼容。
typedef XrResult(XRAPI_PTR* PFN_xrInitializeLoaderKHR)(
    const void* loaderInitInfo);

#endif  // !XR_KHR_loader_init_android

namespace aura {

/** 已加载的 loader 的 SO 文件名（用于日志 / 诊断；未加载时为空） */
const char* AuraXrLoaderName();

/** 按设备选择并 dlopen 合适的 loader；返回是否成功。幂等。 */
bool AuraXrSelectAndLoad();

/**
 * 显式指定 loader（调试 / 强制覆盖用）。
 * @param soName 例如 "libopenxr_loader_legacy.so"
 */
bool AuraXrLoadByName(const char* soName);

/**
 * 调用 `xrInitializeLoaderKHR`（XR_KHR_loader_init_android）把 JVM + Activity 交给 loader。
 *
 * ⚠️ **标准 Khronos loader 必须**先做这一步才能 xrCreateInstance；华为 loader 不需要
 *    （其实现自行获取）。本函数在入口不存在时**静默跳过**并返回 false（非致命）。
 */
bool AuraXrInitAndroidLoader(JNIEnv* env, jobject activity);

/**
 * 在 `xrCreateInstance` **成功之后**调用：用 instance 解析其余 24 个 instance 相关入口。
 *
 * ⚠️ OpenXR 规定：`xrEnumerateInstanceExtensionProperties` / `xrCreateInstance` /
 *    `xrGetInstanceProcAddr` 之外的入口都必须带**有效 instance** 才解析得到，
 *    用 XR_NULL_HANDLE 取会失败。故必须分两阶段（本函数是第二阶段）。
 *
 * @return 是否全部解析成功
 */
bool AuraXrResolveInstanceEntries(XrInstance instance);

}  // namespace aura

// ============================================================================
// 函数指针 + 宏重定向
// ----------------------------------------------------------------------------
// 覆盖 aura_vr_session.cpp 实际用到的全部入口（27 个）。
// PFN_* 类型由 openxr.h 提供；`PFN_xrVoidFunction` 用于 xrGetInstanceProcAddr 取值。
// ============================================================================

#define AURA_XR_FUNCS(X)                    \
  X(xrGetInstanceProcAddr)                  \
  X(xrEnumerateInstanceExtensionProperties) \
  X(xrCreateInstance)                       \
  X(xrDestroyInstance)                      \
  X(xrGetInstanceProperties)                \
  X(xrGetSystem)                            \
  X(xrEnumerateEnvironmentBlendModes)       \
  X(xrEnumerateViewConfigurationViews)      \
  X(xrEnumerateSwapchainFormats)            \
  X(xrCreateSession)                        \
  X(xrDestroySession)                       \
  X(xrBeginSession)                         \
  X(xrEndSession)                           \
  X(xrPollEvent)                            \
  X(xrWaitFrame)                            \
  X(xrBeginFrame)                           \
  X(xrEndFrame)                             \
  X(xrLocateViews)                          \
  X(xrCreateReferenceSpace)                 \
  X(xrDestroySpace)                         \
  X(xrCreateSwapchain)                      \
  X(xrDestroySwapchain)                     \
  X(xrEnumerateSwapchainImages)             \
  X(xrAcquireSwapchainImage)                \
  X(xrWaitSwapchainImage)                   \
  X(xrReleaseSwapchainImage)                \
  X(xrGetOpenGLESGraphicsRequirementsKHR)   \
  /* v2.0.199：OpenXR Action 系统（手柄/输入）。 */ \
  /* 华为手柄（3DoF + 触摸板）走标准 Action 系统读取， */ \
  /* 这些入口同样是 instance 相关，必须第二阶段解析。 */ \
  X(xrCreateActionSet)                      \
  X(xrDestroyActionSet)                     \
  X(xrCreateAction)                         \
  X(xrDestroyAction)                        \
  X(xrStringToPath)                          \
  X(xrPathToString)                          \
  X(xrSuggestInteractionProfileBindings)    \
  X(xrAttachSessionActionSets)              \
  X(xrSyncActions)                          \
  X(xrGetActionStateBoolean)                \
  X(xrGetActionStateFloat)                  \
  X(xrGetActionStateVector2f)               \
  X(xrCreateActionSpace)                    \
  X(xrLocateSpace)                          \
  X(xrGetCurrentInteractionProfile)         \
  X(xrEnumerateBoundSourcesForAction)       \
  X(xrGetInputSourceLocalizedName)          \
  /* 华为扩展（openxr_hw.h）：手柄可用性/类型探测 */ \
  X(xrIsControllerAvailableHW)              \
  X(xrGetControllerTypeHW)

// 声明函数指针（定义在 aura_xr_loader.cpp）
// ⚠️ 变量名统一加 pfn_ 前缀，避免与下方「同名重定向宏」冲突
#define AURA_XR_DECLARE(name) extern PFN_##name pfn_##name;
AURA_XR_FUNCS(AURA_XR_DECLARE)
#undef AURA_XR_DECLARE

// 宏重定向：business 代码里的 xrXxx(...) 自动走同名函数指针
// ⚠️ 必须放在 openxr.h 之后、业务代码之前（本头文件的包含位置即满足）
// ⚠️ PFN_xrXxx 是另一个标识符，不会被这些宏误替换
#define xrGetInstanceProcAddr pfn_xrGetInstanceProcAddr
#define xrEnumerateInstanceExtensionProperties pfn_xrEnumerateInstanceExtensionProperties
#define xrCreateInstance pfn_xrCreateInstance
#define xrDestroyInstance pfn_xrDestroyInstance
#define xrGetInstanceProperties pfn_xrGetInstanceProperties
#define xrGetSystem pfn_xrGetSystem
#define xrEnumerateEnvironmentBlendModes pfn_xrEnumerateEnvironmentBlendModes
#define xrEnumerateViewConfigurationViews pfn_xrEnumerateViewConfigurationViews
#define xrEnumerateSwapchainFormats pfn_xrEnumerateSwapchainFormats
#define xrCreateSession pfn_xrCreateSession
#define xrDestroySession pfn_xrDestroySession
#define xrBeginSession pfn_xrBeginSession
#define xrEndSession pfn_xrEndSession
#define xrPollEvent pfn_xrPollEvent
#define xrWaitFrame pfn_xrWaitFrame
#define xrBeginFrame pfn_xrBeginFrame
#define xrEndFrame pfn_xrEndFrame
#define xrLocateViews pfn_xrLocateViews
#define xrCreateReferenceSpace pfn_xrCreateReferenceSpace
#define xrDestroySpace pfn_xrDestroySpace
#define xrCreateSwapchain pfn_xrCreateSwapchain
#define xrDestroySwapchain pfn_xrDestroySwapchain
#define xrEnumerateSwapchainImages pfn_xrEnumerateSwapchainImages
#define xrAcquireSwapchainImage pfn_xrAcquireSwapchainImage
#define xrWaitSwapchainImage pfn_xrWaitSwapchainImage
#define xrReleaseSwapchainImage pfn_xrReleaseSwapchainImage
#define xrGetOpenGLESGraphicsRequirementsKHR pfn_xrGetOpenGLESGraphicsRequirementsKHR
// v2.0.199：Action 系统（手柄输入）
#define xrCreateActionSet pfn_xrCreateActionSet
#define xrDestroyActionSet pfn_xrDestroyActionSet
#define xrCreateAction pfn_xrCreateAction
#define xrDestroyAction pfn_xrDestroyAction
#define xrStringToPath pfn_xrStringToPath
#define xrPathToString pfn_xrPathToString
#define xrSuggestInteractionProfileBindings pfn_xrSuggestInteractionProfileBindings
#define xrAttachSessionActionSets pfn_xrAttachSessionActionSets
#define xrSyncActions pfn_xrSyncActions
#define xrGetActionStateBoolean pfn_xrGetActionStateBoolean
#define xrGetActionStateFloat pfn_xrGetActionStateFloat
#define xrGetActionStateVector2f pfn_xrGetActionStateVector2f
#define xrCreateActionSpace pfn_xrCreateActionSpace
#define xrLocateSpace pfn_xrLocateSpace
#define xrGetCurrentInteractionProfile pfn_xrGetCurrentInteractionProfile
#define xrEnumerateBoundSourcesForAction pfn_xrEnumerateBoundSourcesForAction
#define xrGetInputSourceLocalizedName pfn_xrGetInputSourceLocalizedName
// 华为扩展（openxr_hw.h）—— 手柄可用性/类型探测
#define xrIsControllerAvailableHW pfn_xrIsControllerAvailableHW
#define xrGetControllerTypeHW pfn_xrGetControllerTypeHW
