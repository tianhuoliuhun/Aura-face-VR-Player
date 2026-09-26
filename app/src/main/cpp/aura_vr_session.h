// ============================================================================
// aura_vr_session.h —— 华为 VR OpenXR 会话层（路径 A′）
// ----------------------------------------------------------------------------
// 职责：只管理 OpenXR 会话与 swapchain，不管内容渲染。
//   Kotlin 侧拿到 swapchain image 的 GL texture id，用自己的 shader 画进去。
//
// 实现参考：华为官方 SDK 示例 `sdkDemo/HVRSDK_XrDemo/jni/xr_demo.cpp`
// （hvrsdk-openxr-3.5.0.79），关键差异点均已对齐：
//   1. ✅ EGL 必须用 **Java Surface 建 window surface**（不能只用 pbuffer）
//   2. ✅ 创建 session 前必须先 xrGetOpenGLESGraphicsRequirementsKHR
//   3. ✅ 必须 xrEnumerateSwapchainFormats 确认 GL_RGBA8 被支持
//   4. ✅ environmentBlendMode 官方用 **ALPHA_BLEND**（不是 OPAQUE）
//   5. ⚠️ eye framebuffer 每眼有 90° 物理方向，**不要人为转正**
//   6. ⚠️ 无新帧时**保留上一帧**，不要清屏（否则闪黑）
//   7. ✅ 会话状态机必须处理（READY→xrBeginSession / STOPPING→xrEndSession）
// ============================================================================

#ifndef AURA_VR_SESSION_H
#define AURA_VR_SESSION_H

// ⚠️ 这两个宏必须在包含 openxr.h 之前定义（决定 XrGraphicsBinding* 等类型是否可见）
#ifndef XR_USE_PLATFORM_ANDROID
#  define XR_USE_PLATFORM_ANDROID 1
#endif
#ifndef XR_USE_GRAPHICS_API_OPENGL_ES
#  define XR_USE_GRAPHICS_API_OPENGL_ES 1
#endif

#include <jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>   // ANativeWindow_fromSurface 的声明所在

#include <atomic>
#include <mutex>
#include <thread>
#include <vector>

// OpenXR 头只在真正接入 SDK 时包含（AURA_HAVE_OPENXR 由 CMake 传入）
#if defined(AURA_HAVE_OPENXR) && AURA_HAVE_OPENXR
#  include <openxr.h>
#  include <openxr_platform.h>
#  include <openxr_hw.h>
#endif

// ---------------------------------------------------------------------------
// 每眼渲染目标（Kotlin 侧通过 JNI 取 texture id 后用现有 shader 渲染）
// ---------------------------------------------------------------------------
struct AuraEyeTarget {
    int      eyeIndex   = -1;      // 0 = 左, 1 = 右
    uint32_t textureId  = 0;       // GL texture 名称（swapchain image 内部封装）
    int      width      = 0;
    int      height     = 0;
    // 投影参数（P1 传给 Kotlin 的 shader 做透视矩阵）
    float    fovLeft    = 0.f;     // 弧度，左眼通常为负
    float    fovRight   = 0.f;
    float    fovUp      = 0.f;
    float    fovDown    = 0.f;
    // 视图矩阵（列主序 4x4，可直传 shader）
    float    viewMatrix[16] = {0};
};

// ---------------------------------------------------------------------------
// 会话状态（数值与 Kotlin HuaweiVrNative.State 一一对应，勿改顺序）
// ---------------------------------------------------------------------------
enum class AuraVrState {
    IDLE = 0,
    INSTANCE_CREATED,
    SESSION_READY,
    RUNNING,
    STOPPING,
    ERROR
};

// ---------------------------------------------------------------------------
// 全局会话（单例；一个进程只能有一个 OpenXR instance）
// ---------------------------------------------------------------------------
class AuraVrSession {
public:
    static AuraVrSession& Get();

    // 初始化：创建 instance / system / EGL / session / swapchain
    // 返回 false 时 lastError() 给出可读原因（Kotlin 侧提示用）
    bool initialize(JavaVM* vm, jobject activity, int renderScalePercent);

    // 设置 Java 侧 SurfaceView 的 Surface（EGL window surface 需要）
    // 对应官方 XrDemo::setSurface(jni, surface)
    void setSurface(JNIEnv* env, jobject surface);

    // 启动帧循环（内部线程）
    bool start();

    // 停止帧循环并清理（退出前调用）
    void shutdown();

    // Kotlin 在渲染回调里取当前帧的双眼目标
    bool acquireEyeTargets(std::vector<AuraEyeTarget>& outTargets);

    // Kotlin 画完后提交该帧（仅 external 渲染模式下由 Kotlin 调用）
    void submitFrame();

    // 把外部的 EGL 上下文绑定到本会话（external 渲染模式必须叫一次）
    // Kotlin 侧确保该 context 已 makeCurrent，之后 swapchain texture 即在其上可写
    void setExternalEglContext(EGLDisplay display, EGLContext context, EGLConfig config);

    // external 渲染模式：true = Kotlin 画（3D 贴片）；false = 只提交姿态、不提交画面层
    void setExternalRendererEnabled(bool enabled);
    bool externalRendererEnabled() const { return externalRenderer_.load(); }

    // 桌面模式下把画面层降级为单层 quad（结构简单、全系统兼容）
    void setPreferQuadLayer(bool prefer) { preferQuadLayer_.store(prefer); }

    // 本帧是否有待提交的 swapchain（Kotlin 渲染前判断用）
    bool hasPendingFrame() const { return pendingFrame_; }

    /**
     * 把第 eyeIndex 眼的 swapchain image 绑定成 GL framebuffer，返回 FBO 名称。
     *
     * ⚠️ 必须在**已 acquire 的那一帧**内调用（`acquireEyeTargets` 之后、
     *    `submitFrame` 之前），且在写画面的同一个 EGL 上下文里。
     * ⚠️ 画完必须调 [unbindEyeFramebuffer]，否则后续绘制会继续写进 swapchain。
     *
     * @return FBO 名称；0 表示失败（无待提交帧 / 眼索引越界），调用方应跳过该眼
     */
    uint32_t bindEyeFramebuffer(int eyeIndex);

    /** 解绑（回到默认 framebuffer 0），幂等 */
    void unbindEyeFramebuffer();

    /** 供日志/调试：最近一次提交结果 */
    int lastFrameResult() const { return lastFrameResult_; }

    AuraVrState state() const { return state_.load(); }
    const char* lastError() const { return lastError_; }
    void setLastError(const char* msg);

    // 推荐每眼尺寸（供日志与 Kotlin 参考）
    int recommendedEyeWidth()  const { return eyeWidth_; }
    int recommendedEyeHeight() const { return eyeHeight_; }

private:
    AuraVrSession() = default;
    ~AuraVrSession();
    AuraVrSession(const AuraVrSession&) = delete;
    AuraVrSession& operator=(const AuraVrSession&) = delete;

    // 分步初始化（便于精确定位失败环节）
    bool createInstance();
    bool getSystem();
    bool createEglContext();                            // display/config/context
    bool createEglSurfaceFromWindow();                  // 用 Java Surface 建 window surface
    bool createSession(JavaVM* vm, jobject activity);   // 绑定 EGL 后 xrCreateSession
    bool createSwapchains();

    void frameLoop();
    void processEvents(bool* exitRenderLoop, bool* sessionRunning);
    bool renderFrame();                                 // 完成一次完整 xrWait/Begin/EndFrame
    bool renderFrameExternal();                         // acquire → 等 Kotlin → release → endFrame
    bool buildAndSubmitLayers();                        // 构造 quad/projection 层并 xrEndFrame
    void releaseAcquiredImages();                       // 幂等释放本帧已 acquire 的 swapchain image

    // 清理（幂等，可重复调用）
    void destroySwapchains();
    void destroySession();
    void destroyInstance();
    void destroyEgl();

private:
    std::atomic<AuraVrState> state_{AuraVrState::IDLE};
    std::thread              thread_;
    std::atomic<bool>        running_{false};

    char lastError_[512] = {0};
    std::mutex errorMutex_;

    int  renderScalePercent_ = 100;
    int  eyeWidth_  = 0;
    int  eyeHeight_ = 0;

    // Java 侧 Surface（native window 源）
    jobject        javaSurface_ = nullptr;   // 全局引用
    ANativeWindow* nativeWindow_ = nullptr;

    // EGL
    EGLDisplay eglDisplay_ = EGL_NO_DISPLAY;
    EGLConfig  eglConfig_  = nullptr;
    EGLContext eglContext_ = EGL_NO_CONTEXT;
    EGLSurface eglSurface_ = EGL_NO_SURFACE;

    // external 渲染模式：Kotlin 把自己的 EGL 上下文交给 native 使用
    std::atomic<bool> externalRenderer_{false};
    std::atomic<bool> preferQuadLayer_{false};
    std::atomic<bool> externalEglValid_{false};
    EGLDisplay        externalDisplay_ = EGL_NO_DISPLAY;
    EGLContext        externalContext_ = EGL_NO_CONTEXT;
    EGLConfig         externalConfig_  = nullptr;
    std::mutex        frameMutex_;          // 保护 pendingFrame_ / acquired 状态
    std::atomic<bool> pendingFrame_{false};
    std::atomic<int>  lastFrameResult_{0};  // 0 = 正常，非 0 = 最近一次 OpenXR 结果码

    // 供 Kotlin 写画面的 FBO（复用同一对象，每次重挂 texture，避免每帧泄漏）
    uint32_t eyeFbo_       = 0;
    int      boundEyeIndex_ = -1;

    // 最近一次 xrLocateViews 得到的双眼投影参数（external 模式的层构造要用它）
    // ⚠️ 类型来自 openxr.h → 必须放在条件编译内，否则无 SDK 时桩路径编不过。
#if defined(AURA_HAVE_OPENXR) && AURA_HAVE_OPENXR
    std::vector<XrCompositionLayerProjectionView> projViews_;
    XrCompositionLayerProjection                  projLayer_{XR_TYPE_COMPOSITION_LAYER_PROJECTION};

    // ---------------- OpenXR 句柄（仅真实路径） ----------------
    struct EyeSwapchain {
        XrSwapchain                              handle = XR_NULL_HANDLE;
        int32_t                                  width  = 0;
        int32_t                                  height = 0;
        std::vector<XrSwapchainImageOpenGLESKHR> images;   // 每张 image 的 GL texture
        uint32_t                                 acquiredImageIndex = 0;
        bool                                     acquired = false;
    };

    XrInstance              instance_   = XR_NULL_HANDLE;
    XrSystemId              systemId_   = XR_NULL_SYSTEM_ID;
    XrSession               session_    = XR_NULL_HANDLE;
    XrSpace                 appSpace_   = XR_NULL_HANDLE;

    XrViewConfigurationType viewConfigType_ = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    XrEnvironmentBlendMode  blendMode_      = XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND;

    // 动态数量（官方按 viewCount 分配；VR Glass 上是 2）
    std::vector<EyeSwapchain> eyeSwapchains_;

    // 最近一次 xrLocateViews 的结果（供 acquireEyeTargets 填 fov/viewMatrix）
    XrViewState         viewState_{XR_TYPE_VIEW_STATE};
    std::vector<XrView> views_;

    // 当前帧状态（xrWaitFrame 返回）
    XrFrameState        currentFrameState_{XR_TYPE_FRAME_STATE};
    bool                frameBegun_ = false;
#endif
};

#endif // AURA_VR_SESSION_H
