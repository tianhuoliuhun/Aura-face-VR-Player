// ============================================================================
// aura_vr_session.cpp —— 华为 VR OpenXR 会话实现（路径 A′）
// ----------------------------------------------------------------------------
// ⚠️ 关于本文件的两条路径：
//
//   【AURA_HAVE_OPENXR=1】有华为 SDK 时：走真实 OpenXR API
//   【AURA_HAVE_OPENXR=0】无 SDK 时：编译成可运行的桩（返回清晰错误），
//                        保证工程在 SDK 到位前也能正常构建，不影响现有功能。
//
// 实现按华为官方示例 `sdkDemo/HVRSDK_XrDemo/jni/xr_demo.cpp` 的流程对齐。
// 本文件只写「会话骨架」：instance → system → EGL → session → swapchain → 帧循环。
// 内容渲染由 Kotlin 侧负责（复用现有 shader），通过 swapchain 的 GL texture 交互。
// ============================================================================

#include "aura_vr_session.h"
#include "aura_vr_log.h"

#include <cstring>
#include <cstdio>
#include <chrono>

// 华为 VR Glass 真机实测的推荐每眼尺寸（参考项目 LHT02/HuaweiVRGlass-ALVR）。
// 仅在 Runtime 未给出有效推荐值时兜底使用。
static constexpr int32_t HuaweiDefaultEyeSize = 1552;

#if defined(AURA_HAVE_OPENXR) && AURA_HAVE_OPENXR
// ---------------------------------------------------------------------------
// 数学：XrPosef（四元数 + 位置）→ 列主序 4x4 视图矩阵
// ---------------------------------------------------------------------------
// 视图矩阵 = R(q)^T * T(-p)（即「世界 → 相机」），列主序直传 GLSL 的 mat4。
//   R(q) 为单位四元数旋转矩阵（行主序展开）：
//       [1-2(y²+z²)   2(xy-wz)    2(xz+wy) ]
//       [2(xy+wz)     1-2(x²+z²)  2(yz-wx) ]
//       [2(xz-wy)     2(yz+wx)    1-2(x²+y²)]
//   视图矩阵取其转置（正交矩阵的逆 = 转置），再右乘平移 T(-p)。
// ⚠️ OpenXR 坐标系：+X 右、+Y 上、-Z 前（右手系，与 OpenGL 一致），可直接用。
// ⚠️ 必须放在文件靠前位置：acquireEyeTargets() 在真实路径段之前就要调用它。
// ---------------------------------------------------------------------------
static void auraPoseToViewMatrix(const XrPosef& pose, float out[16]) {
    const float x = pose.orientation.x;
    const float y = pose.orientation.y;
    const float z = pose.orientation.z;
    const float w = pose.orientation.w;

    const float xx = x * x, yy = y * y, zz = z * z;
    const float xy = x * y, xz = x * z, yz = y * z;
    const float wx = w * x, wy = w * y, wz = w * z;

    // R(q) 的行主序元素
    const float r00 = 1.f - 2.f * (yy + zz);
    const float r01 = 2.f * (xy - wz);
    const float r02 = 2.f * (xz + wy);
    const float r10 = 2.f * (xy + wz);
    const float r11 = 1.f - 2.f * (xx + zz);
    const float r12 = 2.f * (yz - wx);
    const float r20 = 2.f * (xz - wy);
    const float r21 = 2.f * (yz + wx);
    const float r22 = 1.f - 2.f * (xx + yy);

    const float px = pose.position.x;
    const float py = pose.position.y;
    const float pz = pose.position.z;

    // 列主序：out[col * 4 + row]
    // 旋转部分 = R^T（转置即逆）
    out[0]  = r00; out[1]  = r01; out[2]  = r02; out[3]  = 0.f;
    out[4]  = r10; out[5]  = r11; out[6]  = r12; out[7]  = 0.f;
    out[8]  = r20; out[9]  = r21; out[10] = r22; out[11] = 0.f;
    // 平移部分 = -R^T * p
    out[12] = -(r00 * px + r10 * py + r20 * pz);
    out[13] = -(r01 * px + r11 * py + r21 * pz);
    out[14] = -(r02 * px + r12 * py + r22 * pz);
    out[15] = 1.f;
}
#endif

// ---------------------------------------------------------------------------
// 单例
// ---------------------------------------------------------------------------
AuraVrSession& AuraVrSession::Get() {
    static AuraVrSession instance;
    return instance;
}

AuraVrSession::~AuraVrSession() {
    shutdown();
}

void AuraVrSession::setLastError(const char* msg) {
    std::lock_guard<std::mutex> lock(errorMutex_);
    std::snprintf(lastError_, sizeof(lastError_), "%s", msg ? msg : "(null)");
    AURA_LOGE("AuraVrSession: %s", lastError_);
}

#if defined(AURA_HAVE_OPENXR) && AURA_HAVE_OPENXR
// 小工具：把 XrResult 转成 "描述 (码)" 写进 buf
static void formatXrError(char* buf, size_t bufSize, const char* what, XrResult r) {
    std::snprintf(buf, bufSize, "%s: %s (%ld)", what, aura_vr_result_str(r),
                  static_cast<long>(r));
}
#endif

// ---------------------------------------------------------------------------
// Surface 传递（EGL window surface 的来源）
// ---------------------------------------------------------------------------
void AuraVrSession::setSurface(JNIEnv* env, jobject surface) {
    if (env == nullptr) return;

    // 释放旧的全局引用
    if (javaSurface_ != nullptr) {
        env->DeleteGlobalRef(javaSurface_);
        javaSurface_ = nullptr;
    }
    if (nativeWindow_ != nullptr) {
        ANativeWindow_release(nativeWindow_);
        nativeWindow_ = nullptr;
    }

    if (surface == nullptr) {
        AURA_LOGW("setSurface: surface 为 null（退出→无窗口）");
        return;
    }

    javaSurface_ = env->NewGlobalRef(surface);

#if defined(AURA_HAVE_OPENXR) && AURA_HAVE_OPENXR
    nativeWindow_ = ANativeWindow_fromSurface(env, surface);
    AURA_LOGI("setSurface: ANativeWindow=%p", static_cast<void*>(nativeWindow_));
#else
    AURA_LOGI("setSurface: 已记录 Surface（桩路径不建 EGL 窗口表面）");
#endif
}

// ---------------------------------------------------------------------------
// 初始化入口
// ---------------------------------------------------------------------------
bool AuraVrSession::initialize(JavaVM* vm, jobject activity, int renderScalePercent) {
    AURA_LOGI("=== 华为 VR 会话初始化开始 (renderScale=%d%%) ===", renderScalePercent);

    if (state_.load() != AuraVrState::IDLE) {
        setLastError("会话已初始化，拒绝重复初始化");
        return false;
    }

    renderScalePercent_ = (renderScalePercent <= 0 || renderScalePercent > 100)
                          ? 100 : renderScalePercent;

#if !defined(AURA_HAVE_OPENXR) || !AURA_HAVE_OPENXR
    // ---- 桩路径：SDK 未到位 ----
    setLastError("华为 OpenXR SDK 未接入（编译时 AURA_HAVE_OPENXR=0）。"
                 "请配置 huawei.vr.sdk.dir 后重新构建。");
    state_.store(AuraVrState::ERROR);
    return false;
#else
    // ---- 真实路径（顺序与官方示例一致）----
    // 1) OpenXR instance
    if (!createInstance()) { state_.store(AuraVrState::ERROR); return false; }
    state_.store(AuraVrState::INSTANCE_CREATED);

    // 2) system（HMD）
    if (!getSystem()) { state_.store(AuraVrState::ERROR); return false; }

    // 3) EGL：display/config/context（必须在 xrCreateSession 之前）
    if (!createEglContext()) { state_.store(AuraVrState::ERROR); return false; }

    // 4) 从 Java Surface 建 window surface 并 makeCurrent
    //    ⚠️ 官方示例走这条路；若 Surface 还没到（surfaceCreated 未触发），
    //       这里会失败并给出明确提示，由 Java 侧稍后重试。
    if (!createEglSurfaceFromWindow()) { state_.store(AuraVrState::ERROR); return false; }

    // 5) session
    if (!createSession(vm, activity)) { state_.store(AuraVrState::ERROR); return false; }

    // 6) swapchain
    if (!createSwapchains()) { state_.store(AuraVrState::ERROR); return false; }

    state_.store(AuraVrState::SESSION_READY);
    AURA_LOGI("=== 华为 VR 会话初始化成功（每眼 %dx%d）===", eyeWidth_, eyeHeight_);
    return true;
#endif
}

// ---------------------------------------------------------------------------
// 帧循环 / 启动 / 停止
// ---------------------------------------------------------------------------
bool AuraVrSession::start() {
    if (state_.load() != AuraVrState::SESSION_READY) {
        setLastError("状态不是 SESSION_READY，无法启动帧循环");
        return false;
    }
    running_.store(true);
    state_.store(AuraVrState::RUNNING);
    thread_ = std::thread(&AuraVrSession::frameLoop, this);
    AURA_LOGI("帧循环已启动");
    return true;
}

void AuraVrSession::frameLoop() {
    AURA_LOGI("帧循环线程进入");

#if defined(AURA_HAVE_OPENXR) && AURA_HAVE_OPENXR
    bool exitRenderLoop   = false;
    bool sessionRunning   = false;
    bool firstFrameLogged = false;

    while (running_.load() && !exitRenderLoop) {
        // 1) 处理会话事件（状态机：READY→BeginSession，STOPPING→EndSession 等）
        processEvents(&exitRenderLoop, &sessionRunning);

        if (exitRenderLoop) break;

        // 2) 未 running（如刚创建、眼镜在待机）→ 稍等，不要空转
        if (!sessionRunning) {
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
            continue;
        }

        // 3) 渲染并提交一帧
        //    external 模式：native 只 acquire，等 Kotlin 画完再 release（P1 真通路）
        //    非 external：native 全托管，提交空层/内置层（桌面模式下仅保留姿态跟随）
        const bool ok = externalRenderer_.load() ? renderFrameExternal() : renderFrame();
        if (!ok) {
            AURA_LOGW("帧提交失败，继续下一轮（保留上一帧，不清屏）");
        }
        if (!firstFrameLogged) {
            firstFrameLogged = true;
            AURA_LOGI("首帧已提交（external=%d）", externalRenderer_.load() ? 1 : 0);
        }
    }

    // 若仍在 running 状态退出（如异常），确保会话端已收尾
    if (session_ != XR_NULL_HANDLE && sessionRunning) {
        AURA_LOGI("帧循环退出前 xrEndSession");
        xrEndSession(session_);
    }
#else
    // 桩路径：短暂驻留后退出，避免空线程空转
    while (running_.load()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(16));
    }
#endif

    AURA_LOGI("帧循环线程退出");
}

void AuraVrSession::shutdown() {
    AURA_LOGI("=== 会话清理开始 ===");

    state_.store(AuraVrState::STOPPING);

    running_.store(false);
    if (thread_.joinable()) {
        thread_.join();
    }

    destroySwapchains();
    destroyEgl();
    destroySession();
    destroyInstance();

    // 释放 Surface 全局引用
    if (nativeWindow_ != nullptr) {
        ANativeWindow_release(nativeWindow_);
        nativeWindow_ = nullptr;
    }
    javaSurface_ = nullptr;   // 全局引用由 JNI 层在 detach 时清理

    state_.store(AuraVrState::IDLE);
    AURA_LOGI("=== 会话清理完成 ===");
}

// ---------------------------------------------------------------------------
// 供 Kotlin 取双眼目标 / 提交帧
// ---------------------------------------------------------------------------
bool AuraVrSession::acquireEyeTargets(std::vector<AuraEyeTarget>& outTargets) {
    outTargets.clear();

#if defined(AURA_HAVE_OPENXR) && AURA_HAVE_OPENXR
    if (state_.load() != AuraVrState::RUNNING) return false;
    if (eyeSwapchains_.empty()) return false;

    for (size_t i = 0; i < eyeSwapchains_.size() && i < 2; ++i) {
        EyeSwapchain& sc = eyeSwapchains_[i];
        if (!sc.acquired ||
            sc.acquiredImageIndex >= sc.images.size()) {
            return false;   // 无可用目标 → Kotlin 保留上一帧
        }

        AuraEyeTarget t;
        t.eyeIndex  = static_cast<int>(i);
        t.textureId = sc.images[sc.acquiredImageIndex].image;
        t.width     = sc.width;
        t.height    = sc.height;

        // FOV / 姿态（来自本帧 xrLocateViews）
        if (i < views_.size()) {
            const XrFovf& fov = views_[i].fov;
            t.fovLeft  = fov.angleLeft;
            t.fovRight = fov.angleRight;
            t.fovUp    = fov.angleUp;
            t.fovDown  = fov.angleDown;

            // pose → 视图矩阵（P1：完整四元数 → 旋转矩阵 + 平移）
            auraPoseToViewMatrix(views_[i].pose, t.viewMatrix);
        }
        outTargets.push_back(t);
    }
    return outTargets.size() == eyeSwapchains_.size();
#else
    return false;
#endif
}

void AuraVrSession::submitFrame() {
#if defined(AURA_HAVE_OPENXR) && AURA_HAVE_OPENXR
    // Kotlin 已把内容画进 swapchain texture → 这里 release + endFrame 完成上屏
    if (!externalRenderer_.load()) {
        AURA_LOGV("submitFrame 被忽略（非 external 渲染模式）");
        return;
    }

    std::lock_guard<std::mutex> lock(frameMutex_);
    if (!pendingFrame_) {
        AURA_LOGV("submitFrame 无待提交帧");
        return;
    }
    pendingFrame_ = false;

    // ⚠️ 顺序要求：release 必须在 Kotlin 的 GL 命令写入之后、endFrame 之前
    releaseAcquiredImages();

    const int r = buildAndSubmitLayers() ? 0 : 1;
    lastFrameResult_.store(r);

    frameBegun_ = false;
    AURA_LOGV("submitFrame 完成 (r=%d)", r);
#else
    // 桩路径：无操作
#endif
}

void AuraVrSession::setExternalEglContext(EGLDisplay display, EGLContext context, EGLConfig config) {
    externalDisplay_ = display;
    externalContext_ = context;
    externalConfig_  = config;
    externalEglValid_.store(display != EGL_NO_DISPLAY && context != EGL_NO_CONTEXT);

    // ⚠️ 关键：必须把会话内的 eglDisplay_/eglContext_ 切到 Kotlin 的上下文，
    //    否则 xrCreateSwapchain 生成的 GL texture 属于 native 自己的上下文，
    //    Kotlin 的 GL 线程无法写入（表现为「眼镜内全黑但无报错」）。
    if (externalEglValid_.load()) {
        eglDisplay_ = display;
        eglContext_ = context;
        eglConfig_  = config;
    }
    AURA_LOGI("external EGL 已绑定 (valid=%d)", externalEglValid_.load() ? 1 : 0);
}

void AuraVrSession::setExternalRendererEnabled(bool enabled) {
    externalRenderer_.store(enabled);
    AURA_LOGI("external 渲染模式 = %s", enabled ? "开" : "关");
}

// ---------------------------------------------------------------------------
// 双眼 FBO 绑定（供 Kotlin 把画面画进 swapchain image）
// ---------------------------------------------------------------------------
uint32_t AuraVrSession::bindEyeFramebuffer(int eyeIndex) {
#if defined(AURA_HAVE_OPENXR) && AURA_HAVE_OPENXR
    if (!pendingFrame_) {
        AURA_LOGW("bindEyeFramebuffer: 无待提交帧（应先 acquireEyeTargets 并成功）");
        return 0;
    }
    if (eyeIndex < 0 || static_cast<size_t>(eyeIndex) >= eyeSwapchains_.size()) {
        AURA_LOGW("bindEyeFramebuffer: 眼索引越界 %d", eyeIndex);
        return 0;
    }
    const EyeSwapchain& sc = eyeSwapchains_[eyeIndex];
    if (!sc.acquired || sc.acquiredImageIndex >= sc.images.size()) {
        AURA_LOGW("bindEyeFramebuffer: eye%d 未 acquire", eyeIndex);
        return 0;
    }
    const GLuint tex = static_cast<GLuint>(sc.images[sc.acquiredImageIndex].image);
    if (tex == 0) {
        AURA_LOGW("bindEyeFramebuffer: eye%d textureId 为 0", eyeIndex);
        return 0;
    }

    // 复用同一个 FBO，只换挂载的 texture（比每帧 glGenFramebuffers 更省、也不会泄漏）
    if (eyeFbo_ == 0) {
        GLuint fbo = 0;
        glGenFramebuffers(1, &fbo);
        if (fbo == 0) {
            AURA_LOGE("bindEyeFramebuffer: glGenFramebuffers 失败");
            return 0;
        }
        eyeFbo_ = fbo;
    }

    glBindFramebuffer(GL_FRAMEBUFFER, eyeFbo_);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);

    const GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        AURA_LOGE("bindEyeFramebuffer: eye%d FBO 不完整 (0x%x)", eyeIndex, status);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return 0;
    }

    boundEyeIndex_ = eyeIndex;
    return eyeFbo_;
#else
    (void) eyeIndex;
    return 0;
#endif
}

void AuraVrSession::unbindEyeFramebuffer() {
#if defined(AURA_HAVE_OPENXR) && AURA_HAVE_OPENXR
    if (boundEyeIndex_ >= 0) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        boundEyeIndex_ = -1;
    }
#endif
}

// ===========================================================================
// 真实路径实现
// ===========================================================================

#if defined(AURA_HAVE_OPENXR) && AURA_HAVE_OPENXR

// ---------------------------------------------------------------------------
// 1) instance
// ---------------------------------------------------------------------------
bool AuraVrSession::createInstance() {
    AURA_LOGI("创建 OpenXR instance…");

    // 先探测扩展，避免 xrCreateInstance 直接失败时日志难读
    uint32_t extCount = 0;
    XrResult r = xrEnumerateInstanceExtensionProperties(nullptr, 0, &extCount, nullptr);
    if (XR_FAILED(r) || extCount == 0) {
        formatXrError(lastError_, sizeof(lastError_),
                      "xrEnumerateInstanceExtensionProperties 失败（loader 是否正常？）", r);
        AURA_LOGE("%s", lastError_);
        return false;
    }
    std::vector<XrExtensionProperties> exts(extCount, {XR_TYPE_EXTENSION_PROPERTIES});
    r = xrEnumerateInstanceExtensionProperties(nullptr, extCount, &extCount, exts.data());
    if (XR_FAILED(r)) {
        setLastError("枚举 OpenXR 扩展属性失败");
        return false;
    }

    bool hasGles = false, hasAndroidCreate = false;
    for (const auto& e : exts) {
        if (std::strcmp(e.extensionName, XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME) == 0) hasGles = true;
        if (std::strcmp(e.extensionName, XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME) == 0) hasAndroidCreate = true;
        AURA_LOGI("  可用扩展: %s (v%u)", e.extensionName, e.extensionVersion);
    }
    if (!hasGles) {
        setLastError("Runtime 不支持 XR_KHR_OPENGL_ES_ENABLE（请确认已插入 VR Glass）");
        return false;
    }
    if (!hasAndroidCreate) {
        setLastError("Runtime 不支持 XR_KHR_ANDROID_CREATE_INSTANCE");
        return false;
    }

    std::vector<const char*> enabledExts;
    enabledExts.push_back(XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME);
    enabledExts.push_back(XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME);

    XrInstanceCreateInfo createInfo{XR_TYPE_INSTANCE_CREATE_INFO};
    createInfo.enabledExtensionCount      = static_cast<uint32_t>(enabledExts.size());
    createInfo.enabledExtensionNames      = enabledExts.data();
    createInfo.applicationInfo.apiVersion = XR_CURRENT_API_VERSION;
    std::snprintf(createInfo.applicationInfo.applicationName,
                  XR_MAX_APPLICATION_NAME_SIZE, "Aura face VR Player");
    createInfo.applicationInfo.applicationVersion = 1;
    std::snprintf(createInfo.applicationInfo.engineName,
                  XR_MAX_ENGINE_NAME_SIZE, "AuraVr");

    r = xrCreateInstance(&createInfo, &instance_);
    if (XR_FAILED(r)) {
        formatXrError(lastError_, sizeof(lastError_), "xrCreateInstance 失败", r);
        AURA_LOGE("%s", lastError_);
        return false;
    }

    XrInstanceProperties props{XR_TYPE_INSTANCE_PROPERTIES};
    if (XR_SUCCEEDED(xrGetInstanceProperties(instance_, &props))) {
        AURA_LOGI("OpenXR Runtime: %s v%u.%u.%u",
                  props.runtimeName,
                  XR_VERSION_MAJOR(props.runtimeVersion),
                  XR_VERSION_MINOR(props.runtimeVersion),
                  XR_VERSION_PATCH(props.runtimeVersion));
    }
    AURA_LOGI("instance 创建成功");
    return true;
}

// ---------------------------------------------------------------------------
// 2) system
// ---------------------------------------------------------------------------
bool AuraVrSession::getSystem() {
    AURA_LOGI("获取 system…");

    XrSystemGetInfo getInfo{XR_TYPE_SYSTEM_GET_INFO};
    getInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;

    XrResult r = xrGetSystem(instance_, &getInfo, &systemId_);
    if (XR_FAILED(r)) {
        char detail[128];
        formatXrError(detail, sizeof(detail), "xrGetSystem 失败", r);
        std::snprintf(lastError_, sizeof(lastError_),
                      "%s —— 通常表示眼镜未连接或 Runtime 未就绪", detail);
        AURA_LOGE("%s", lastError_);
        return false;
    }
    AURA_LOGI("systemId=%llu", static_cast<unsigned long long>(systemId_));

    // 环境混合模式：官方示例用 ALPHA_BLEND（VR 内可与系统环境混合）
    uint32_t blendCount = 0;
    if (XR_SUCCEEDED(xrEnumerateEnvironmentBlendModes(
            instance_, systemId_, viewConfigType_, 0, &blendCount, nullptr)) && blendCount > 0) {
        std::vector<XrEnvironmentBlendMode> modes(blendCount);
        if (XR_SUCCEEDED(xrEnumerateEnvironmentBlendModes(
                instance_, systemId_, viewConfigType_, blendCount, &blendCount, modes.data()))) {
            bool hasAlpha = false;
            for (auto m : modes) {
                if (m == XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND) hasAlpha = true;
            }
            // ⚠️ 对齐官方示例：ALPHA_BLEND
            blendMode_ = hasAlpha ? XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND
                                  : XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
            AURA_LOGI("环境混合模式选定: %d", static_cast<int>(blendMode_));
        }
    }

    // 校验 GLES 图形要求（官方在创建 session 前必做）
    XrGraphicsRequirementsOpenGLESKHR reqs{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
    r = xrGetOpenGLESGraphicsRequirementsKHR(instance_, systemId_, &reqs);
    if (XR_FAILED(r)) {
        formatXrError(lastError_, sizeof(lastError_),
                      "xrGetOpenGLESGraphicsRequirementsKHR 失败", r);
        AURA_LOGE("%s", lastError_);
        return false;
    }
    AURA_LOGI("GLES 要求: minVersion=0x%llx maxVersion=0x%llx",
              static_cast<unsigned long long>(reqs.minApiVersionSupported),
              static_cast<unsigned long long>(reqs.maxApiVersionSupported));

    return true;
}

// ---------------------------------------------------------------------------
// 3) EGL display/config/context
// ---------------------------------------------------------------------------
bool AuraVrSession::createEglContext() {
    AURA_LOGI("创建 EGL 上下文（GLES 3.x）…");

    eglDisplay_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (eglDisplay_ == EGL_NO_DISPLAY) {
        setLastError("eglGetDisplay 失败");
        return false;
    }
    EGLint major = 0, minor = 0;
    if (!eglInitialize(eglDisplay_, &major, &minor)) {
        setLastError("eglInitialize 失败");
        return false;
    }
    AURA_LOGI("EGL %d.%d vendor=%s", major, minor,
              eglQueryString(eglDisplay_, EGL_VENDOR) ?: "?");

    // ⚠️ 官方示例要求 config 同时支持 EGL_WINDOW_BIT 与 EGL_PBUFFER_BIT，
    //    因为 eglCreateWindowSurface 需要 WINDOW_BIT。
    const EGLint configAttribs[] = {
        EGL_SURFACE_TYPE,    EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE,   8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE,  8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24,
        EGL_SAMPLES,    0,
        EGL_NONE
    };
    EGLint numConfigs = 0;
    if (!eglChooseConfig(eglDisplay_, configAttribs, &eglConfig_, 1, &numConfigs) || numConfigs < 1) {
        // 退一步：不强制 ES3，避免部分驱动的位标志差异导致直接失败
        AURA_LOGW("EGL_OPENGL_ES3_BIT 配置未找到，回退到不带 renderable_type 的配置");
        const EGLint fallbackAttribs[] = {
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
            EGL_RED_SIZE,   8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE,  8,
            EGL_DEPTH_SIZE, 24,
            EGL_NONE
        };
        if (!eglChooseConfig(eglDisplay_, fallbackAttribs, &eglConfig_, 1, &numConfigs) || numConfigs < 1) {
            setLastError("eglChooseConfig 未找到可用配置");
            return false;
        }
    }

    // ⚠️ 华为示例从 ES3 逐级回退到 ES2 尝试（GLES_VERSION..2）
    EGLContext ctx = EGL_NO_CONTEXT;
    for (EGLint ver = 3; ver >= 2; --ver) {
        const EGLint contextAttribs[] = {
            EGL_CONTEXT_CLIENT_VERSION, ver,
            EGL_NONE
        };
        ctx = eglCreateContext(eglDisplay_, eglConfig_, EGL_NO_CONTEXT, contextAttribs);
        if (ctx != EGL_NO_CONTEXT) {
            AURA_LOGI("EGL 上下文创建成功，client version = %d", ver);
            break;
        }
        AURA_LOGW("eglCreateContext(ES%d) 失败，尝试更低版本", ver);
    }
    if (ctx == EGL_NO_CONTEXT) {
        setLastError("eglCreateContext 失败（ES3/ES2 均失败）");
        return false;
    }
    eglContext_ = ctx;
    return true;
}

// ---------------------------------------------------------------------------
// 4) 从 Java Surface 建 EGL window surface（官方 XrDemo::initEGLSurface）
// ---------------------------------------------------------------------------
bool AuraVrSession::createEglSurfaceFromWindow() {
    if (nativeWindow_ == nullptr) {
        setLastError("Surface 尚未就绪：请确保 SurfaceView.surfaceCreated 已触发后再初始化");
        return false;
    }
    if (eglDisplay_ == EGL_NO_DISPLAY || eglConfig_ == nullptr || eglContext_ == EGL_NO_CONTEXT) {
        setLastError("createEglSurfaceFromWindow: EGL display/config/context 未就绪");
        return false;
    }

    const EGLint attribs[] = { EGL_NONE };
    eglSurface_ = eglCreateWindowSurface(eglDisplay_, eglConfig_, nativeWindow_, attribs);
    if (eglSurface_ == EGL_NO_SURFACE) {
        setLastError("eglCreateWindowSurface 失败");
        return false;
    }
    if (eglMakeCurrent(eglDisplay_, eglSurface_, eglSurface_, eglContext_) != EGL_TRUE) {
        setLastError("eglMakeCurrent 失败");
        return false;
    }

    const char* glVer = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    AURA_LOGI("EGL window surface 就绪，GL_VERSION=%s", glVer ? glVer : "?");
    return true;
}

// ---------------------------------------------------------------------------
// 5) session
// ---------------------------------------------------------------------------
bool AuraVrSession::createSession(JavaVM* vm, jobject activity) {
    AURA_LOGI("创建 session（绑定 EGL）…");
    if (instance_ == XR_NULL_HANDLE || systemId_ == XR_NULL_SYSTEM_ID) {
        setLastError("createSession: instance/system 尚未就绪");
        return false;
    }
    if (vm == nullptr || activity == nullptr) {
        setLastError("createSession: JavaVM/Activity 为空");
        return false;
    }

    XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
    binding.display = eglDisplay_;
    binding.config  = eglConfig_;
    binding.context = eglContext_;

    XrSessionCreateInfo sessionCreateInfo{XR_TYPE_SESSION_CREATE_INFO};
    sessionCreateInfo.next     = &binding;
    sessionCreateInfo.systemId = systemId_;

    XrResult r = xrCreateSession(instance_, &sessionCreateInfo, &session_);
    if (XR_FAILED(r)) {
        formatXrError(lastError_, sizeof(lastError_),
                      "xrCreateSession 失败（EGL 上下文是否已 makeCurrent？）", r);
        AURA_LOGE("%s", lastError_);
        return false;
    }

    // 应用参考空间（官方用 LOCAL）
    XrReferenceSpaceCreateInfo spaceInfo{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    spaceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    spaceInfo.poseInReferenceSpace = {{0.f, 0.f, 0.f, 1.f}, {0.f, 0.f, 0.f}};
    r = xrCreateReferenceSpace(session_, &spaceInfo, &appSpace_);
    if (XR_FAILED(r)) {
        formatXrError(lastError_, sizeof(lastError_), "xrCreateReferenceSpace 失败", r);
        AURA_LOGE("%s", lastError_);
        return false;
    }

    AURA_LOGI("session 创建成功，参考空间 = LOCAL");
    return true;
}

// ---------------------------------------------------------------------------
// 6) swapchain
// ---------------------------------------------------------------------------
bool AuraVrSession::createSwapchains() {
    AURA_LOGI("创建 swapchain…");
    if (session_ == XR_NULL_HANDLE) {
        setLastError("createSwapchains: session 尚未创建");
        return false;
    }

    // 1) 视图配置
    uint32_t viewCount = 0;
    XrResult r = xrEnumerateViewConfigurationViews(
            instance_, systemId_, viewConfigType_, 0, &viewCount, nullptr);
    if (XR_FAILED(r) || viewCount == 0) {
        setLastError("xrEnumerateViewConfigurationViews 枚举数量失败");
        return false;
    }
    std::vector<XrViewConfigurationView> cfgViews(viewCount, {XR_TYPE_VIEW_CONFIGURATION_VIEW});
    r = xrEnumerateViewConfigurationViews(
            instance_, systemId_, viewConfigType_, viewCount, &viewCount, cfgViews.data());
    if (XR_FAILED(r)) {
        setLastError("xrEnumerateViewConfigurationViews 填充失败");
        return false;
    }
    AURA_LOGI("viewCount=%u（VR Glass 应为 2）", viewCount);

    // 2) 枚举支持的 swapchain 格式，确认 GL_RGBA8 可用（官方做法）
    uint32_t formatCount = 0;
    bool rgba8Supported = false;
    if (XR_SUCCEEDED(xrEnumerateSwapchainFormats(session_, 0, &formatCount, nullptr)) &&
        formatCount > 0) {
        std::vector<int64_t> formats(formatCount);
        if (XR_SUCCEEDED(xrEnumerateSwapchainFormats(session_, formatCount, &formatCount, formats.data()))) {
            for (auto f : formats) {
                AURA_LOGI("  支持 swapchain 格式: 0x%llx", static_cast<unsigned long long>(f));
                if (f == static_cast<int64_t>(GL_RGBA8)) rgba8Supported = true;
            }
        }
    }
    const int64_t chosenFormat = rgba8Supported ? static_cast<int64_t>(GL_RGBA8) : GL_RGBA8;

    const float scale = static_cast<float>(renderScalePercent_) / 100.0f;

    eyeSwapchains_.clear();
    eyeSwapchains_.resize(viewCount);

    for (uint32_t i = 0; i < viewCount; ++i) {
        // ⚠️ 官方直接用 recommendedImageRectWidth/Height；缩放仅是我们自己的画质档。
        int32_t w = static_cast<int32_t>(cfgViews[i].recommendedImageRectWidth  * scale);
        int32_t h = static_cast<int32_t>(cfgViews[i].recommendedImageRectHeight * scale);
        if (w <= 0 || h <= 0) { w = HuaweiDefaultEyeSize; h = HuaweiDefaultEyeSize; }

        XrSwapchainCreateInfo scInfo{XR_TYPE_SWAPCHAIN_CREATE_INFO};
        scInfo.createFlags = 0;
        scInfo.usageFlags  = XR_SWAPCHAIN_USAGE_SAMPLED_BIT |
                             XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
        scInfo.format      = chosenFormat;
        scInfo.sampleCount = 1;
        scInfo.width       = w;
        scInfo.height      = h;
        scInfo.faceCount   = 1;
        scInfo.arraySize   = 1;
        scInfo.mipCount    = 1;

        EyeSwapchain& sc = eyeSwapchains_[i];
        sc.width  = w;
        sc.height = h;

        r = xrCreateSwapchain(session_, &scInfo, &sc.handle);
        if (XR_FAILED(r)) {
            char detail[128];
            formatXrError(detail, sizeof(detail), "xrCreateSwapchain 失败", r);
            std::snprintf(lastError_, sizeof(lastError_),
                          "%s (eye=%u, %dx%d)", detail, i, w, h);
            AURA_LOGE("%s", lastError_);
            return false;
        }

        uint32_t imgCount = 0;
        r = xrEnumerateSwapchainImages(sc.handle, 0, &imgCount, nullptr);
        if (XR_FAILED(r) || imgCount == 0) {
            setLastError("xrEnumerateSwapchainImages 数量失败");
            return false;
        }
        sc.images.assign(imgCount, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
        r = xrEnumerateSwapchainImages(
                sc.handle, imgCount, &imgCount,
                reinterpret_cast<XrSwapchainImageBaseHeader*>(sc.images.data()));
        if (XR_FAILED(r)) {
            setLastError("xrEnumerateSwapchainImages 填充失败");
            return false;
        }

        AURA_LOGI("eye%u swapchain: %dx%d, %u 张 image, 首张 textureId=%u",
                  i, w, h, imgCount, sc.images[0].image);
        if (i == 0) { eyeWidth_ = w; eyeHeight_ = h; }
    }

    views_.assign(viewCount, {XR_TYPE_VIEW});
    projViews_.assign(viewCount, {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW});
    AURA_LOGI("swapchain 创建完成（每眼 %dx%d）", eyeWidth_, eyeHeight_);
    return true;
}

// ---------------------------------------------------------------------------
// 事件处理（会话状态机，官方 XrDemo::ProcessEvents 简化版）
// ---------------------------------------------------------------------------
void AuraVrSession::processEvents(bool* exitRenderLoop, bool* sessionRunning) {
    *exitRenderLoop = false;

    XrEventDataBuffer buffer{XR_TYPE_EVENT_DATA_BUFFER};
    XrEventDataBaseHeader* header = reinterpret_cast<XrEventDataBaseHeader*>(&buffer);

    while (xrPollEvent(instance_, &buffer) == XR_SUCCESS) {
        switch (header->type) {
            case XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED: {
                const auto* stateEvent =
                        reinterpret_cast<const XrEventDataSessionStateChanged*>(header);
                switch (stateEvent->state) {
                    case XR_SESSION_STATE_READY: {
                        AURA_LOGI("会话状态: READY → xrBeginSession");
                        XrSessionBeginInfo beginInfo{XR_TYPE_SESSION_BEGIN_INFO};
                        beginInfo.primaryViewConfigurationType = viewConfigType_;
                        XrResult r = xrBeginSession(session_, &beginInfo);
                        if (XR_FAILED(r)) {
                            AURA_LOGW("xrBeginSession 失败 (%ld)", static_cast<long>(r));
                        } else {
                            *sessionRunning = true;
                        }
                        break;
                    }
                    case XR_SESSION_STATE_STOPPING: {
                        AURA_LOGI("会话状态: STOPPING → xrEndSession");
                        *sessionRunning = false;
                        xrEndSession(session_);
                        break;
                    }
                    case XR_SESSION_STATE_EXITING:
                    case XR_SESSION_STATE_LOSS_PENDING: {
                        AURA_LOGI("会话状态: EXITING/LOSS_PENDING → 退出渲染循环");
                        *sessionRunning = false;
                        *exitRenderLoop = true;
                        break;
                    }
                    default:
                        break;
                }
                break;
            }
            case XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING: {
                AURA_LOGW("instance 即将丢失 → 退出渲染循环");
                *exitRenderLoop = true;
                break;
            }
            default:
                break;
        }
        // 重置 buffer 供下次轮询
        buffer = {XR_TYPE_EVENT_DATA_BUFFER};
        header = reinterpret_cast<XrEventDataBaseHeader*>(&buffer);
    }
}

// ---------------------------------------------------------------------------
// 单帧提交（官方 XrDemo::RenderFrame + RenderLayer 结构）
// ---------------------------------------------------------------------------
bool AuraVrSession::renderFrame() {
    // 1) wait / begin
    XrFrameWaitInfo waitInfo{XR_TYPE_FRAME_WAIT_INFO};
    XrResult r = xrWaitFrame(session_, &waitInfo, &currentFrameState_);
    if (XR_FAILED(r)) {
        AURA_LOGW("xrWaitFrame 失败 (%ld)", static_cast<long>(r));
        return false;
    }
    XrFrameBeginInfo beginInfo{XR_TYPE_FRAME_BEGIN_INFO};
    r = xrBeginFrame(session_, &beginInfo);
    if (XR_FAILED(r)) {
        AURA_LOGW("xrBeginFrame 失败 (%ld)", static_cast<long>(r));
        return false;
    }
    frameBegun_ = true;

    // 2) 定位双眼（用 predictedDisplayTime 与 pose 队列对齐 —— 关键！）
    XrViewLocateInfo locateInfo{XR_TYPE_VIEW_LOCATE_INFO};
    locateInfo.viewConfigurationType = viewConfigType_;
    locateInfo.displayTime           = currentFrameState_.predictedDisplayTime;
    locateInfo.space                 = appSpace_;
    viewState_ = {XR_TYPE_VIEW_STATE};
    uint32_t viewCountOut = 0;
    r = xrLocateViews(session_, &locateInfo, &viewState_,
                      static_cast<uint32_t>(views_.size()), &viewCountOut, views_.data());
    if (XR_FAILED(r)) {
        AURA_LOGW("xrLocateViews 失败 (%ld)", static_cast<long>(r));
        // 仍需 endFrame，否则下一帧会卡
        XrFrameEndInfo endInfo{XR_TYPE_FRAME_END_INFO};
        endInfo.displayTime = currentFrameState_.predictedDisplayTime;
        endInfo.environmentBlendMode = blendMode_;
        endInfo.layerCount = 0;
        endInfo.layers = nullptr;
        xrEndFrame(session_, &endInfo);
        frameBegun_ = false;
        return false;
    }

    // ✅ 到这里：views_ 已刷新 → Kotlin 侧可通过 acquireEyeTargets() 取 FOV/姿态
    //    非 external 模式（桌面/仅姿态跟随）：acquire 后立即返回，不提交画面层。
    if (!externalRenderer_.load()) {
        frameBegun_ = false;
        return buildAndSubmitLayers();
    }

    std::vector<XrCompositionLayerProjectionView> projViews(views_.size());
    bool anyAcquired = false;

    for (size_t i = 0; i < eyeSwapchains_.size() && i < views_.size(); ++i) {
        EyeSwapchain& sc = eyeSwapchains_[i];

        XrSwapchainImageAcquireInfo acquireInfo{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
        uint32_t imageIndex = 0;
        r = xrAcquireSwapchainImage(sc.handle, &acquireInfo, &imageIndex);
        if (XR_FAILED(r)) {
            AURA_LOGW("eye%zu xrAcquireSwapchainImage 失败 (%ld)", i, static_cast<long>(r));
            continue;
        }
        XrSwapchainImageWaitInfo swWaitInfo{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
        swWaitInfo.timeout = XR_INFINITE_DURATION;
        r = xrWaitSwapchainImage(sc.handle, &swWaitInfo);
        if (XR_FAILED(r)) {
            AURA_LOGW("eye%zu xrWaitSwapchainImage 失败 (%ld)", i, static_cast<long>(r));
            continue;
        }

        sc.acquired            = true;
        sc.acquiredImageIndex  = imageIndex;
        anyAcquired            = true;

        projViews[i] = {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW};
        projViews[i].pose   = views_[i].pose;
        projViews[i].fov    = views_[i].fov;
        projViews[i].subImage.swapchain        = sc.handle;
        projViews[i].subImage.imageRect.offset = {0, 0};
        projViews[i].subImage.imageRect.extent = {sc.width, sc.height};
        projViews[i].subImage.imageArrayIndex  = 0;
    }

    if (anyAcquired) {
        // 复用统一层构造（projViews_ 已由上方循环填充）
        projViews_.assign(projViews.begin(), projViews.end());
        buildAndSubmitLayers();
        releaseAcquiredImages();
    } else {
        // ⚠️ 拿不到 image 时**仍要 endFrame 空层**：否则 Runtime 认为我们卡住。
        //    并且**不要清屏**，Kotlin 侧会保留上一帧内容。
        buildAndSubmitLayers();
    }

    frameBegun_ = false;
    return true;
}

// ---------------------------------------------------------------------------
// external 渲染模式：acquire 完等 Kotlin 画，画完由 submitFrame() 收尾
// ---------------------------------------------------------------------------
bool AuraVrSession::renderFrameExternal() {
    // 1) wait / begin
    XrFrameWaitInfo waitInfo{XR_TYPE_FRAME_WAIT_INFO};
    XrResult r = xrWaitFrame(session_, &waitInfo, &currentFrameState_);
    if (XR_FAILED(r)) {
        AURA_LOGW("xrWaitFrame 失败 (%ld)", static_cast<long>(r));
        return false;
    }
    XrFrameBeginInfo beginInfo{XR_TYPE_FRAME_BEGIN_INFO};
    r = xrBeginFrame(session_, &beginInfo);
    if (XR_FAILED(r)) {
        AURA_LOGW("xrBeginFrame 失败 (%ld)", static_cast<long>(r));
        return false;
    }
    frameBegun_ = true;

    // 2) 定位双眼（用 predictedDisplayTime 对齐 pose 队列 —— 关键）
    XrViewLocateInfo locateInfo{XR_TYPE_VIEW_LOCATE_INFO};
    locateInfo.viewConfigurationType = viewConfigType_;
    locateInfo.displayTime           = currentFrameState_.predictedDisplayTime;
    locateInfo.space                 = appSpace_;
    viewState_ = {XR_TYPE_VIEW_STATE};
    uint32_t viewCountOut = 0;
    r = xrLocateViews(session_, &locateInfo, &viewState_,
                      static_cast<uint32_t>(views_.size()), &viewCountOut, views_.data());
    if (XR_FAILED(r)) {
        AURA_LOGW("xrLocateViews 失败 (%ld)", static_cast<long>(r));
        // 仍需 endFrame，否则下一帧会卡住
        pendingFrame_ = false;
        releaseAcquiredImages();
        buildAndSubmitLayers();
        frameBegun_ = false;
        return false;
    }

    // 3) acquire 双眼 swapchain image（真正画之前必须先拿到 texture）
    std::lock_guard<std::mutex> lock(frameMutex_);

    size_t acquiredCount = 0;
    for (size_t i = 0; i < eyeSwapchains_.size() && i < views_.size(); ++i) {
        EyeSwapchain& sc = eyeSwapchains_[i];

        XrSwapchainImageAcquireInfo acquireInfo{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
        uint32_t imageIndex = 0;
        r = xrAcquireSwapchainImage(sc.handle, &acquireInfo, &imageIndex);
        if (XR_FAILED(r)) {
            AURA_LOGW("eye%zu xrAcquireSwapchainImage 失败 (%ld)", i, static_cast<long>(r));
            continue;
        }
        XrSwapchainImageWaitInfo swWaitInfo{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
        swWaitInfo.timeout = XR_INFINITE_DURATION;
        r = xrWaitSwapchainImage(sc.handle, &swWaitInfo);
        if (XR_FAILED(r)) {
            AURA_LOGW("eye%zu xrWaitSwapchainImage 失败 (%ld)", i, static_cast<long>(r));
            continue;
        }

        sc.acquired           = true;
        sc.acquiredImageIndex = imageIndex;
        ++acquiredCount;

        // 供 acquireEyeTargets() 回传给 Kotlin
        projViews_[i] = {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW};
        projViews_[i].pose   = views_[i].pose;
        projViews_[i].fov    = views_[i].fov;
        projViews_[i].subImage.swapchain        = sc.handle;
        projViews_[i].subImage.imageRect.offset = {0, 0};
        projViews_[i].subImage.imageRect.extent = {sc.width, sc.height};
        projViews_[i].subImage.imageArrayIndex  = 0;
    }

    if (acquiredCount == 0) {
        // 没拿到图 → 本轮不提交内容，但必须 endFrame 空层（否则 Runtime 判定卡死）
        // ⚠️ 空层是唯一能让 Runtime 继续调度的手段，绝不能 return 而不 endFrame。
        pendingFrame_ = false;
        buildAndSubmitLayers();
        frameBegun_ = false;
        return false;
    }

    pendingFrame_ = true;
    AURA_LOGV("external acquire 完成，等待 Kotlin 渲染（%zu 眼）", acquiredCount);
    return true;
}

// ---------------------------------------------------------------------------
// 构造并提交合成层（quad 优先，失败退回 projection）
// ---------------------------------------------------------------------------
bool AuraVrSession::buildAndSubmitLayers() {
    XrFrameEndInfo endInfo{XR_TYPE_FRAME_END_INFO};
    endInfo.displayTime          = currentFrameState_.predictedDisplayTime;
    endInfo.environmentBlendMode = blendMode_;

    // 桌面模式下没有真眼镜 → 不提交内容层，只维持会话与姿态，
    // 这样 App 不会崩、也不会因为层结构不被支持而黑屏。
    if (!externalRenderer_.load()) {
        endInfo.layerCount = 0;
        endInfo.layers     = nullptr;
        XrResult r = xrEndFrame(session_, &endInfo);
        if (XR_FAILED(r)) AURA_LOGW("xrEndFrame(空层) 失败 (%ld)", static_cast<long>(r));
        return XR_SUCCEEDED(r);
    }

    // 真机路径：projection 层（左眼 + 右眼各一份 swapchain，官方推荐结构）
    // ⚠️ quad 层需要一块「双眼并排」的专用 swapchain；当前内容直接画进 eye swapchain，
    //    因此不做 quad。preferQuadLayer_ 仅保留开关，待后续 P1.5 引入专用纹理再启用。
    if (projViews_.empty()) {
        endInfo.layerCount = 0;
        endInfo.layers     = nullptr;
        XrResult r = xrEndFrame(session_, &endInfo);
        if (XR_FAILED(r)) AURA_LOGW("xrEndFrame(空层) 失败 (%ld)", static_cast<long>(r));
        return XR_SUCCEEDED(r);
    }

    projLayer_ = {XR_TYPE_COMPOSITION_LAYER_PROJECTION};
    projLayer_.layerFlags = 0;
    projLayer_.space      = appSpace_;
    projLayer_.viewCount  = static_cast<uint32_t>(projViews_.size());
    projLayer_.views      = projViews_.data();

    const XrCompositionLayerBaseHeader* layers[] = {
        reinterpret_cast<const XrCompositionLayerBaseHeader*>(&projLayer_)
    };
    endInfo.layerCount = 1;
    endInfo.layers     = layers;

    XrResult r = xrEndFrame(session_, &endInfo);
    if (XR_FAILED(r)) {
        AURA_LOGW("xrEndFrame(projection) 失败 (%ld)", static_cast<long>(r));
        return false;
    }
    return true;
}

// ---------------------------------------------------------------------------
// 释放本帧已 acquire 的 image（幂等）
// ---------------------------------------------------------------------------
void AuraVrSession::releaseAcquiredImages() {
    for (auto& sc : eyeSwapchains_) {
        if (sc.acquired && sc.handle != XR_NULL_HANDLE) {
            XrSwapchainImageReleaseInfo releaseInfo{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
            XrResult r = xrReleaseSwapchainImage(sc.handle, &releaseInfo);
            if (XR_FAILED(r)) {
                AURA_LOGW("xrReleaseSwapchainImage 失败 (%ld)", static_cast<long>(r));
            }
            sc.acquired = false;
        }
    }
}

// ---------------------------------------------------------------------------
// 清理
// ---------------------------------------------------------------------------
void AuraVrSession::destroySwapchains() {
    // FBO 必须先于 swapchain 销毁（它引用 swapchain 的 texture）
    if (eyeFbo_ != 0) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        GLuint fbo = static_cast<GLuint>(eyeFbo_);
        glDeleteFramebuffers(1, &fbo);
        eyeFbo_ = 0;
    }
    boundEyeIndex_ = -1;
    pendingFrame_  = false;

    for (auto& sc : eyeSwapchains_) {
        if (sc.handle != XR_NULL_HANDLE) {
            xrDestroySwapchain(sc.handle);
            sc.handle = XR_NULL_HANDLE;
        }
        sc.images.clear();
        sc.acquired = false;
    }
    eyeSwapchains_.clear();
    AURA_LOGI("swapchain 已销毁");
}

void AuraVrSession::destroyEgl() {
    if (eglDisplay_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(eglDisplay_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (eglSurface_ != EGL_NO_SURFACE) { eglDestroySurface(eglDisplay_, eglSurface_); eglSurface_ = EGL_NO_SURFACE; }
        if (eglContext_ != EGL_NO_CONTEXT) { eglDestroyContext(eglDisplay_, eglContext_); eglContext_ = EGL_NO_CONTEXT; }
        eglTerminate(eglDisplay_);
        eglDisplay_ = EGL_NO_DISPLAY;
    }
    eglConfig_ = nullptr;
    AURA_LOGI("EGL 已销毁");
}

void AuraVrSession::destroySession() {
    if (appSpace_ != XR_NULL_HANDLE) { xrDestroySpace(appSpace_); appSpace_ = XR_NULL_HANDLE; }
    if (session_  != XR_NULL_HANDLE) { xrDestroySession(session_); session_ = XR_NULL_HANDLE; }
    AURA_LOGI("session 已销毁");
}

void AuraVrSession::destroyInstance() {
    if (instance_ != XR_NULL_HANDLE) {
        xrDestroyInstance(instance_);
        instance_ = XR_NULL_HANDLE;
    }
    AURA_LOGI("instance 已销毁");
}

#else  // ===== 桩路径 =====

// 桩路径下的空实现（避免链接错误）
bool AuraVrSession::createInstance()            { return false; }
bool AuraVrSession::getSystem()                 { return false; }
bool AuraVrSession::createEglContext()          { return false; }
bool AuraVrSession::createEglSurfaceFromWindow(){ return false; }
bool AuraVrSession::createSession(JavaVM*, jobject) { return false; }
bool AuraVrSession::createSwapchains()          { return false; }
void AuraVrSession::processEvents(bool*, bool*) {}
bool AuraVrSession::renderFrame()               { return false; }
bool AuraVrSession::renderFrameExternal()       { return false; }
bool AuraVrSession::buildAndSubmitLayers()      { return false; }
void AuraVrSession::releaseAcquiredImages()     {}
// ⚠️ bindEyeFramebuffer / unbindEyeFramebuffer 不在此列：
//    它们在文件前部定义，且内部自带 #if 分支，两路径都有实现。

void AuraVrSession::destroySwapchains() {}
void AuraVrSession::destroyEgl()        {}
void AuraVrSession::destroySession()    {}
void AuraVrSession::destroyInstance()   {}

#endif
