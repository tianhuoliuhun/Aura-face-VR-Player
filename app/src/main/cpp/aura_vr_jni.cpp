// ============================================================================
// aura_vr_jni.cpp —— Kotlin ↔ native 门面
// ----------------------------------------------------------------------------
// 包名/类名必须与 Kotlin 侧一致：com.example.vr.huawei.HuaweiVrNative
// 设计原则：本层**只做参数转换与错误传递**，不含业务逻辑。
// ============================================================================

#include "aura_vr_session.h"
#include "aura_vr_log.h"
// v2.0.200：手柄状态轮询（nativePollVrInput）
#include "aura_vr_input.h"

#include <jni.h>
#include <string>
#include <vector>

namespace {

// 与 Kotlin 侧 object 的全限定名保持一致；保留为常量便于后续做 JNI 签名校验/反射调用。
[[maybe_unused]] const char* kNativeClass = "com/example/vr/huawei/HuaweiVrNative";

// 把 C++ 字符串转成 jstring
jstring toJString(JNIEnv* env, const char* s) {
    return env->NewStringUTF(s ? s : "");
}

} // namespace

extern "C" {

// ---------------------------------------------------------------------------
// nativeInitialize(activity, renderScalePercent) : boolean
// ---------------------------------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_com_example_vr_huawei_HuaweiVrNative_nativeInitialize(
        JNIEnv* env, jclass /*clazz*/, jobject activity, jint renderScalePercent) {

    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK || vm == nullptr) {
        AURA_LOGE("nativeInitialize: 获取 JavaVM 失败");
        return JNI_FALSE;
    }

    bool ok = AuraVrSession::Get().initialize(vm, activity, renderScalePercent);
    AURA_LOGI("nativeInitialize -> %s", ok ? "OK" : "FAIL");
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// nativeSetSurface(surface) : void
// ---------------------------------------------------------------------------
// ⚠️ 必须在 initialize 之前调用（且 SurfaceView.surfaceCreated 已触发）。
// 华为的 EGL 是 window surface 模式，没有 Surface 就建不了 swapchain 上屏。
// 传 null 表示 Surface 已销毁（surfaceDestroyed）。
JNIEXPORT void JNICALL
Java_com_example_vr_huawei_HuaweiVrNative_nativeSetSurface(
        JNIEnv* env, jclass /*clazz*/, jobject surface) {
    AuraVrSession::Get().setSurface(env, surface);
}

// ---------------------------------------------------------------------------
// nativeStart() : boolean
// ---------------------------------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_com_example_vr_huawei_HuaweiVrNative_nativeStart(JNIEnv* /*env*/, jclass /*clazz*/) {
    return AuraVrSession::Get().start() ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// nativeShutdown() : void
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_example_vr_huawei_HuaweiVrNative_nativeShutdown(JNIEnv* /*env*/, jclass /*clazz*/) {
    AuraVrSession::Get().shutdown();
}

// ---------------------------------------------------------------------------
// nativeLastError() : String —— 失败原因（Kotlin 侧 Toast 展示）
// ---------------------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_example_vr_huawei_HuaweiVrNative_nativeLastError(JNIEnv* env, jclass /*clazz*/) {
    return toJString(env, AuraVrSession::Get().lastError());
}

// ---------------------------------------------------------------------------
// nativeState() : int —— 映射 AuraVrState
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_example_vr_huawei_HuaweiVrNative_nativeState(JNIEnv* /*env*/, jclass /*clazz*/) {
    return static_cast<jint>(AuraVrSession::Get().state());
}

// ---------------------------------------------------------------------------
// nativeRecommendEyeSize() : int[2] —— {width, height}，供日志与 UI 参考
// ---------------------------------------------------------------------------
JNIEXPORT jintArray JNICALL
Java_com_example_vr_huawei_HuaweiVrNative_nativeRecommendEyeSize(JNIEnv* env, jclass /*clazz*/) {
    jintArray arr = env->NewIntArray(2);
    if (arr == nullptr) return nullptr;
    const jint vals[2] = {
        AuraVrSession::Get().recommendedEyeWidth(),
        AuraVrSession::Get().recommendedEyeHeight()
    };
    env->SetIntArrayRegion(arr, 0, 2, vals);
    return arr;
}

// ---------------------------------------------------------------------------
// nativeAcquireEyeTargets() : int[6] —— 每眼 3 个值 {textureId, width, height}
// 失败返回 null（Kotlin 侧保留上一帧，不要清屏）
// ---------------------------------------------------------------------------
JNIEXPORT jintArray JNICALL
Java_com_example_vr_huawei_HuaweiVrNative_nativeAcquireEyeTargets(
        JNIEnv* env, jclass /*clazz*/) {

    std::vector<AuraEyeTarget> targets;
    if (!AuraVrSession::Get().acquireEyeTargets(targets) || targets.size() < 2) {
        return nullptr;   // ⚠️ 参考项目经验：无新帧时保留上一帧，不清屏
    }

    jintArray arr = env->NewIntArray(6);
    if (arr == nullptr) return nullptr;
    const jint vals[6] = {
        static_cast<jint>(targets[0].textureId), targets[0].width, targets[0].height,
        static_cast<jint>(targets[1].textureId), targets[1].width, targets[1].height,
    };
    env->SetIntArrayRegion(arr, 0, 6, vals);
    return arr;
}

// ---------------------------------------------------------------------------
// nativeEyeTargetsEx() : float[40] —— 每眼 20 个值
//   [0..15] 视图矩阵（列主序，直传 GLSL mat4）
//   [16..19] FOV {left, right, up, down}（弧度）
// 失败返回 null
// ---------------------------------------------------------------------------
JNIEXPORT jfloatArray JNICALL
Java_com_example_vr_huawei_HuaweiVrNative_nativeEyeTargetsEx(
        JNIEnv* env, jclass /*clazz*/) {

    std::vector<AuraEyeTarget> targets;
    if (!AuraVrSession::Get().acquireEyeTargets(targets) || targets.size() < 2) {
        return nullptr;
    }

    jfloatArray arr = env->NewFloatArray(40);
    if (arr == nullptr) return nullptr;
    jfloat vals[40] = {0};
    for (size_t eye = 0; eye < 2; ++eye) {
        const AuraEyeTarget& t = targets[eye];
        const size_t base = eye * 20;
        for (int k = 0; k < 16; ++k) vals[base + k] = t.viewMatrix[k];
        vals[base + 16] = t.fovLeft;
        vals[base + 17] = t.fovRight;
        vals[base + 18] = t.fovUp;
        vals[base + 19] = t.fovDown;
    }
    env->SetFloatArrayRegion(arr, 0, 40, vals);
    return arr;
}

// ---------------------------------------------------------------------------
// nativeSetExternalRendererEnabled(enabled) : void
//   true  = Kotlin 负责把画面画进 swapchain texture（3D 贴片通路）
//   false = native 只提交姿态、不提交画面层（桌面/无眼镜场景，用于安全自测）
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_example_vr_huawei_HuaweiVrNative_nativeSetExternalRendererEnabled(
        JNIEnv* /*env*/, jclass /*clazz*/, jboolean enabled) {
    AuraVrSession::Get().setExternalRendererEnabled(enabled == JNI_TRUE);
}

// ---------------------------------------------------------------------------
// nativeHasPendingFrame() : boolean
// ---------------------------------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_com_example_vr_huawei_HuaweiVrNative_nativeHasPendingFrame(
        JNIEnv* /*env*/, jclass /*clazz*/) {
    return AuraVrSession::Get().hasPendingFrame() ? JNI_TRUE : JNI_FALSE;
}

// ---------------------------------------------------------------------------
// nativeBindEyeFramebuffer(eyeIndex) : int
//   把指定眼的 swapchain image 绑成 GL framebuffer，返回 FBO 名称（0 = 失败）
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_example_vr_huawei_HuaweiVrNative_nativeBindEyeFramebuffer(
        JNIEnv* /*env*/, jclass /*clazz*/, jint eyeIndex) {
    return static_cast<jint>(AuraVrSession::Get().bindEyeFramebuffer(static_cast<int>(eyeIndex)));
}

// ---------------------------------------------------------------------------
// nativeUnbindEyeFramebuffer() : void
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_example_vr_huawei_HuaweiVrNative_nativeUnbindEyeFramebuffer(
        JNIEnv* /*env*/, jclass /*clazz*/) {
    AuraVrSession::Get().unbindEyeFramebuffer();
}

// ---------------------------------------------------------------------------
// nativeFrameResult() : int —— 最近一次提交的 OpenXR 结果码（0 = 正常）
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL
Java_com_example_vr_huawei_HuaweiVrNative_nativeFrameResult(
        JNIEnv* /*env*/, jclass /*clazz*/) {
    return static_cast<jint>(AuraVrSession::Get().lastFrameResult());
}

// ---------------------------------------------------------------------------
// nativeSubmitFrame() : void
// ---------------------------------------------------------------------------
JNIEXPORT void JNICALL
Java_com_example_vr_huawei_HuaweiVrNative_nativeSubmitFrame(JNIEnv* /*env*/, jclass /*clazz*/) {
    AuraVrSession::Get().submitFrame();
}

// ---------------------------------------------------------------------------
// nativeIsSdkAvailable() : boolean —— 编译期是否接入了华为 SDK
// ---------------------------------------------------------------------------
JNIEXPORT jboolean JNICALL
Java_com_example_vr_huawei_HuaweiVrNative_nativeIsSdkAvailable(JNIEnv* /*env*/, jclass /*clazz*/) {
#if defined(AURA_HAVE_OPENXR) && AURA_HAVE_OPENXR
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

// ---------------------------------------------------------------------------
// nativePollVrInput() : int[] —— 轮询华为 VR Glass 手柄状态（v2.0.200）
// ---------------------------------------------------------------------------
// 返回定长 6 的 int 数组，约定：
//   [0] selectLeft       左/右手 select 当前是否按下（0/1）
//   [1] selectRight      同上
//   [2] pressedEdge      本帧是否有「刚按下」（用于触发一次性动作，如播放/暂停）
//   [3] controllerAvail  华为手柄是否可用（0/1）
//   [4] selectCount      累计按下次数（诊断，截断为低 31 位）
//   [5] inputReady       动作集是否就绪（0/1）
// ⚠️ 之所以返回数组而不是多个 boolean：JNI 往返有成本，播放器每帧要轮询，
//    一次取回全部状态最省。Kotlin 侧封装成 VrInputState 数据类再使用。
JNIEXPORT jintArray JNICALL
Java_com_example_vr_huawei_HuaweiVrNative_nativePollVrInput(JNIEnv* env, jclass /*clazz*/) {
    const aura::XrInputState& s = aura::XrInputGet();
    jint buf[6];
    buf[0] = s.selectLeft ? 1 : 0;
    buf[1] = s.selectRight ? 1 : 0;
    buf[2] = (s.selectLeftPressed || s.selectRightPressed) ? 1 : 0;
    buf[3] = s.controllerAvailable ? 1 : 0;
    buf[4] = static_cast<jint>(s.selectCount & 0x7fffffffULL);
    buf[5] = aura::XrInputReady() ? 1 : 0;

    jintArray out = env->NewIntArray(6);
    if (out == nullptr) {
        return nullptr;
    }
    env->SetIntArrayRegion(out, 0, 6, buf);
    return out;
}

} // extern "C"

// ---------------------------------------------------------------------------
// System.loadLibrary 时机：由 Kotlin 侧 companion object 主动触发
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
    AURA_LOGI("libauravr.so 已加载");
    return JNI_VERSION_1_6;
}
