/*
 * AuraXrLoader —— 多 OpenXR loader 运行时选择的实现
 * 设计与用法见 aura_xr_loader.h
 */
#include "aura_xr_loader.h"

#include <dlfcn.h>
#include <sys/system_properties.h>

#include <cstring>
#include <string>

#include "aura_vr_log.h"

// ---- 函数指针定义（头文件里 extern 声明，这里给出实体）----
#define AURA_XR_DEFINE(name) PFN_##name pfn_##name = nullptr;
AURA_XR_FUNCS(AURA_XR_DEFINE)
#undef AURA_XR_DEFINE

namespace aura {
namespace {

void* g_loaderHandle = nullptr;
std::string g_loaderName;
PFN_xrInitializeLoaderKHR g_pfnInitializeLoaderKHR = nullptr;

std::string Prop(const char* key) {
  char value[PROP_VALUE_MAX] = {0};
  __system_property_get(key, value);
  return std::string(value);
}

bool Contains(const std::string& hay, const char* needle) {
  return hay.find(needle) != std::string::npos;
}

/**
 * 通过 xrGetInstanceProcAddr 解析「全局（instance 无关）」入口。
 *
 * OpenXR 规定：用 XR_NULL_HANDLE 请求 instance 无关的函数是合法的；
 * instance 相关的函数必须等 xrCreateInstance 成功后再用 instance 解析。
 */
bool ResolveGlobalEntries() {
  if (pfn_xrGetInstanceProcAddr == nullptr) {
    return false;
  }

  // 这些入口可以用 null instance 取到
  struct Entry {
    const char* name;
    PFN_xrVoidFunction* out;
  };
  const Entry entries[] = {
      {"xrEnumerateInstanceExtensionProperties",
       reinterpret_cast<PFN_xrVoidFunction*>(
           &pfn_xrEnumerateInstanceExtensionProperties)},
      {"xrCreateInstance",
       reinterpret_cast<PFN_xrVoidFunction*>(&pfn_xrCreateInstance)},
      {"xrGetInstanceProcAddr", reinterpret_cast<PFN_xrVoidFunction*>(
                                    &pfn_xrGetInstanceProcAddr)},
  };

  for (const auto& e : entries) {
    PFN_xrVoidFunction fn = nullptr;
    XrResult r = pfn_xrGetInstanceProcAddr(XR_NULL_HANDLE, e.name, &fn);
    if (XR_FAILED(r) || fn == nullptr) {
      AURA_LOGE("AuraXrLoader: 解析 %s 失败（0x%x）", e.name, r);
      return false;
    }
    *e.out = fn;
  }
  return true;
}

}  // namespace

const char* AuraXrLoaderName() { return g_loaderName.c_str(); }

bool AuraXrLoadByName(const char* soName) {
  if (soName == nullptr) {
    return false;
  }
  // 已加载同一个 → 幂等返回
  if (g_loaderHandle != nullptr && g_loaderName == soName) {
    return true;
  }
  // 换 loader：先卸载旧的，并清空已解析的指针（避免新旧混用）
  if (g_loaderHandle != nullptr) {
    dlclose(g_loaderHandle);
    g_loaderHandle = nullptr;
    g_pfnInitializeLoaderKHR = nullptr;
#define AURA_XR_CLEAR(name) pfn_##name = nullptr;
    AURA_XR_FUNCS(AURA_XR_CLEAR)
#undef AURA_XR_CLEAR
  }

  void* handle = dlopen(soName, RTLD_NOW | RTLD_LOCAL);
  if (handle == nullptr) {
    const char* err = dlerror();
    AURA_LOGW("AuraXrLoader: dlopen(%s) 失败: %s", soName, err ? err : "?");
    return false;
  }

  // 唯一必须 dlsym 的入口：其余全部由它解析（OpenXR 标准做法）
  auto getProc = reinterpret_cast<PFN_xrGetInstanceProcAddr>(
      dlsym(handle, "xrGetInstanceProcAddr"));
  if (getProc == nullptr) {
    AURA_LOGE("AuraXrLoader: %s 中找不到 xrGetInstanceProcAddr", soName);
    dlclose(handle);
    return false;
  }

  g_loaderHandle = handle;
  g_loaderName = soName;
  pfn_xrGetInstanceProcAddr = getProc;

  if (!ResolveGlobalEntries()) {
    dlclose(g_loaderHandle);
    g_loaderHandle = nullptr;
    g_loaderName.clear();
    pfn_xrGetInstanceProcAddr = nullptr;
    return false;
  }

  // 可选入口：Android 专用 loader 初始化扩展（华为 loader 可能没有）
  PFN_xrVoidFunction initFn = nullptr;
  XrResult r = pfn_xrGetInstanceProcAddr(XR_NULL_HANDLE,
                                         "xrInitializeLoaderKHR", &initFn);
  g_pfnInitializeLoaderKHR =
      XR_SUCCEEDED(r) ? reinterpret_cast<PFN_xrInitializeLoaderKHR>(initFn)
                      : nullptr;

  AURA_LOGI("AuraXrLoader: 已加载 %s（xrInitializeLoaderKHR %s）", soName,
            g_pfnInitializeLoaderKHR ? "可用" : "不可用");
  return true;
}

bool AuraXrSelectAndLoad() {
  const std::string manufacturer = Prop("ro.product.manufacturer");
  const std::string brand = Prop("ro.product.brand");
  const std::string model = Prop("ro.product.model");
  AURA_LOGI("AuraXrLoader: manufacturer=%s brand=%s model=%s",
            manufacturer.c_str(), brand.c_str(), model.c_str());

  // 华为 VR Glass：只有华为自己的 loader 能对接其 VR Engine 运行时
  if (Contains(manufacturer, "HUAWEI") || Contains(manufacturer, "Huawei") ||
      Contains(brand, "HUAWEI") || Contains(brand, "Huawei") ||
      Contains(brand, "HONOR")) {
    if (AuraXrLoadByName("libxr_loader.so")) {
      return true;
    }
    AURA_LOGW("AuraXrLoader: 华为 loader 加载失败，尝试标准 loader");
  }

  // PICO / Quest / 其他 OpenXR 设备：
  // 先试标准版（新固件），失败再退回 legacy（PICO Neo3 / PICO 4 老固件）。
  // ⚠️ 注意：这里只解决「加载」；若加载成功但 xrCreateInstance 仍失败
  //    （老固件的运行时与 1.1.x loader 不匹配），调用方应改用
  //    AuraXrLoadByName("libopenxr_loader_legacy.so") 重试 —— 见 session 侧。
  if (AuraXrLoadByName("libopenxr_loader.so")) {
    return true;
  }
  if (AuraXrLoadByName("libopenxr_loader_legacy.so")) {
    return true;
  }

  AURA_LOGE("AuraXrLoader: 未找到可用的 OpenXR loader");
  return false;
}

bool AuraXrResolveInstanceEntries(XrInstance instance) {
  if (instance == XR_NULL_HANDLE || pfn_xrGetInstanceProcAddr == nullptr) {
    AURA_LOGE("AuraXrResolveInstanceEntries: instance 或 loader 未就绪");
    return false;
  }

  // instance 相关入口（除全局那 3 个之外的全部）
  struct Entry {
    const char* name;
    PFN_xrVoidFunction* out;
  };
  const Entry entries[] = {
      {"xrDestroyInstance",
       reinterpret_cast<PFN_xrVoidFunction*>(&pfn_xrDestroyInstance)},
      {"xrGetInstanceProperties",
       reinterpret_cast<PFN_xrVoidFunction*>(&pfn_xrGetInstanceProperties)},
      {"xrGetSystem", reinterpret_cast<PFN_xrVoidFunction*>(&pfn_xrGetSystem)},
      {"xrEnumerateEnvironmentBlendModes",
       reinterpret_cast<PFN_xrVoidFunction*>(
           &pfn_xrEnumerateEnvironmentBlendModes)},
      {"xrEnumerateViewConfigurationViews",
       reinterpret_cast<PFN_xrVoidFunction*>(
           &pfn_xrEnumerateViewConfigurationViews)},
      {"xrEnumerateSwapchainFormats",
       reinterpret_cast<PFN_xrVoidFunction*>(
           &pfn_xrEnumerateSwapchainFormats)},
      {"xrCreateSession",
       reinterpret_cast<PFN_xrVoidFunction*>(&pfn_xrCreateSession)},
      {"xrDestroySession",
       reinterpret_cast<PFN_xrVoidFunction*>(&pfn_xrDestroySession)},
      {"xrBeginSession", reinterpret_cast<PFN_xrVoidFunction*>(&pfn_xrBeginSession)},
      {"xrEndSession", reinterpret_cast<PFN_xrVoidFunction*>(&pfn_xrEndSession)},
      {"xrPollEvent", reinterpret_cast<PFN_xrVoidFunction*>(&pfn_xrPollEvent)},
      {"xrWaitFrame", reinterpret_cast<PFN_xrVoidFunction*>(&pfn_xrWaitFrame)},
      {"xrBeginFrame", reinterpret_cast<PFN_xrVoidFunction*>(&pfn_xrBeginFrame)},
      {"xrEndFrame", reinterpret_cast<PFN_xrVoidFunction*>(&pfn_xrEndFrame)},
      {"xrLocateViews", reinterpret_cast<PFN_xrVoidFunction*>(&pfn_xrLocateViews)},
      {"xrCreateReferenceSpace",
       reinterpret_cast<PFN_xrVoidFunction*>(&pfn_xrCreateReferenceSpace)},
      {"xrDestroySpace", reinterpret_cast<PFN_xrVoidFunction*>(&pfn_xrDestroySpace)},
      {"xrCreateSwapchain",
       reinterpret_cast<PFN_xrVoidFunction*>(&pfn_xrCreateSwapchain)},
      {"xrDestroySwapchain",
       reinterpret_cast<PFN_xrVoidFunction*>(&pfn_xrDestroySwapchain)},
      {"xrEnumerateSwapchainImages",
       reinterpret_cast<PFN_xrVoidFunction*>(&pfn_xrEnumerateSwapchainImages)},
      {"xrAcquireSwapchainImage",
       reinterpret_cast<PFN_xrVoidFunction*>(&pfn_xrAcquireSwapchainImage)},
      {"xrWaitSwapchainImage",
       reinterpret_cast<PFN_xrVoidFunction*>(&pfn_xrWaitSwapchainImage)},
      {"xrReleaseSwapchainImage",
       reinterpret_cast<PFN_xrVoidFunction*>(&pfn_xrReleaseSwapchainImage)},
      {"xrGetOpenGLESGraphicsRequirementsKHR",
       reinterpret_cast<PFN_xrVoidFunction*>(
           &pfn_xrGetOpenGLESGraphicsRequirementsKHR)},
  };

  int missing = 0;
  for (const auto& e : entries) {
    PFN_xrVoidFunction fn = nullptr;
    XrResult r = pfn_xrGetInstanceProcAddr(instance, e.name, &fn);
    if (XR_FAILED(r) || fn == nullptr) {
      AURA_LOGW("AuraXrLoader: 解析 %s 失败（0x%x）", e.name, r);
      ++missing;
      continue;
    }
    *e.out = fn;
  }
  if (missing > 0) {
    AURA_LOGE("AuraXrLoader: 有 %d 个入口未解析，后续调用会崩溃", missing);
    return false;
  }
  AURA_LOGI("AuraXrLoader: instance 相关入口全部解析完成（%zu 个）",
            sizeof(entries) / sizeof(entries[0]));
  return true;
}

bool AuraXrInitAndroidLoader(JNIEnv* env, jobject activity) {
  if (g_pfnInitializeLoaderKHR == nullptr) {
    // 华为 loader 不需要这一步（它自己从 JNI 拿环境）→ 非致命
    AURA_LOGI("AuraXrLoader: 该 loader 无 xrInitializeLoaderKHR，跳过初始化");
    return false;
  }

  // ⚠️ applicationVM 必须是**真实的** JavaVM：Khronos 标准 loader 会校验非空
  //    （用它做 JNI 调用去查 runtime service）。从 JNIEnv 取最稳妥。
  JavaVM* vm = nullptr;
  if (env != nullptr) {
    env->GetJavaVM(&vm);
  }

  XrLoaderInitInfoAndroidKHR info{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
  info.applicationVM = vm;
  info.applicationContext = activity;

  XrResult r = g_pfnInitializeLoaderKHR(reinterpret_cast<const void*>(&info));
  if (XR_FAILED(r)) {
    AURA_LOGE("AuraXrLoader: xrInitializeLoaderKHR 失败（0x%x）", r);
    return false;
  }
  AURA_LOGI("AuraXrLoader: xrInitializeLoaderKHR 成功");
  return true;
}

}  // namespace aura
