/*
 * aura_xr_compat —— OpenXR 类型的「接入 / 未接入」兼容层（v2.4.16）
 * ============================================================================
 * ## 为什么需要它
 * 本项目的 native 代码分两类：
 *   · **核心**（VR 渲染管线 / JNI 入口 / VR 内 UI）—— 任何 flavor 都要编
 *   · **VR 眼镜接入**（OpenXR 会话 / 手柄 / loader 选择）—— 只有接 VR 时才编
 *
 * 核心代码里有若干**函数签名**用到了 OpenXR 句柄类型（`XrInstance` / `XrSession` /
 * `XrSpace` / `XrTime` 等），但**并不调用**任何 OpenXR API。
 * 若不接 VR 眼镜（standard flavor）也不想引入 OpenXR 头文件 —— 否则主线构建会被
 * 「必须先装华为 SDK」绑架（这正是 v2.4.16 之前的问题）。
 *
 * 所以这里做一层最小兼容：
 *   接入时   → 直接 include 官方头
 *   未接入时 → 只给**不透明句柄**与几个结构体，仅让签名能编译
 *
 * ## 约束
 * ⚠️ 占位句柄必须与官方**同形**（`struct Xxx_T*`）—— 用的是 OpenXR 官方的
 *    `XR_DEFINE_HANDLE` 宏，保证将来核心与接入层混编时类型一致。
 * ⚠️ 这里**只**放「签名需要」的类型。核心代码若新增对 OpenXR 类型的依赖，
 *    请先想清楚它是不是真该留在核心里。
 * ⚠️ 未接入时这些类型**绝不会参与运行** —— 因为对应的实现（aura_vr_session.cpp
 *    等）在 CMake 里已被排除，不存在会调用它们的代码路径。
 */
#pragma once

#include <cstdint>

#if defined(AURA_HAVE_OPENXR) && AURA_HAVE_OPENXR

// ===== 已接入 VR 眼镜：直接用官方头 =====
// ⚠️ 下面三个**必须先于** openxr_platform.h 引入，否则会报：
//      unknown type name 'jobject'    ← 来自 jni.h
//      unknown type name 'EGLDisplay' / 'EGLConfig' / 'EGLContext'  ← 来自 EGL/egl.h
//    （原来的两个头文件各自在 include openxr 之前引了它们，所以没暴露；
//      抽到统一兼容层后这一步最容易漏 —— 本次编译就踩到了。）
#include <jni.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>

#include <openxr.h>
#include <openxr_platform.h>

#else

// ===== 未接入：最小占位（仅让签名能编译）=====
#ifndef XR_DEFINE_HANDLE
#define XR_DEFINE_HANDLE(object) typedef struct object##_T* object;
#endif

XR_DEFINE_HANDLE(XrInstance)
XR_DEFINE_HANDLE(XrSession)
XR_DEFINE_HANDLE(XrSpace)
XR_DEFINE_HANDLE(XrSwapchain)

typedef uint64_t XrSystemId;
typedef int64_t  XrTime;

typedef struct XrQuaternionf {
    float x, y, z, w;
} XrQuaternionf;

typedef struct XrVector3f {
    float x, y, z;
} XrVector3f;

#endif  // AURA_HAVE_OPENXR
