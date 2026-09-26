# ============================================================================
# GfaceVRplayer —— 华为 VR Glass（VR Engine / OpenXR）native 层
# ----------------------------------------------------------------------------
# 设计见 HUAWEI_VR_ENGINE_PLAN_2026-09-26.md 第 5 节。
#
# 职责边界（路径 A′）：
#   native 只负责「OpenXR 会话」：instance / system / session / swapchain / 帧循环
#   Kotlin 侧复用现有 shader 与几何（通过 swapchain 给出的 GL texture id 传参）
#
# ⚠️ 华为 SDK 不入仓。构建时通过以下任一方式提供路径：
#   1. local.properties:  huawei.vr.sdk.dir=D:/HuaweiVRSDK
#   2. 环境变量:          HUAWEI_VR_SDK_DIR
#   3. -P 参数:           -Phuawei.vr.sdk.dir=...
#
# 华为 SDK 真实目录结构（hvrsdk-openxr-3.5.0.79 实测）：
#   sdkDemo/openXRsdk/jni/openxr/{openxr.h, openxr_platform.h, openxr_hw.h, ...}
#   sdkDemo/openXRsdk/jni/{arm64-v8a,armeabi-v7a}/libxr_loader.so
#   sdkDemo/openXRsdk/libs/hvrbridge.jar
# 注意 loader 文件名是 libxr_loader.so（不是 lib_loader.so）
# ============================================================================

cmake_minimum_required(VERSION 3.22.1)
project(auravr LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------------------
# 定位华为 OpenXR SDK
# ---------------------------------------------------------------------------
if(NOT DEFINED HUAWEI_VR_SDK_DIR OR HUAWEI_VR_SDK_DIR STREQUAL "")
    if(DEFINED ENV{HUAWEI_VR_SDK_DIR})
        set(HUAWEI_VR_SDK_DIR "$ENV{HUAWEI_VR_SDK_DIR}")
    endif()
endif()

if(NOT DEFINED HUAWEI_VR_SDK_DIR OR HUAWEI_VR_SDK_DIR STREQUAL "")
    message(FATAL_ERROR
        "\n"
        "============================================================\n"
        " 未找到华为 OpenXR SDK 路径（HUAWEI_VR_SDK_DIR）。\n"
        "\n"
        " 需要华为 VR Engine SDK（hvrsdk-openxr-3.5.0.79 或更新）。\n"
        " 请在 local.properties 中加一行（指向解压根目录）：\n"
        "\n"
        "   huawei.vr.sdk.dir=D:/HuaweiVRSDK\n"
        "\n"
        " 或在环境变量中设置 HUAWEI_VR_SDK_DIR。\n"
        "============================================================\n")
endif()

file(TO_CMAKE_PATH "${HUAWEI_VR_SDK_DIR}" HUAWEI_VR_SDK_DIR)
message(STATUS "华为 VR SDK 路径: ${HUAWEI_VR_SDK_DIR}")

# 定位 openxr 头目录（兼容多种解压布局）
set(_hw_inc_candidates
    "${HUAWEI_VR_SDK_DIR}/sdkDemo/openXRsdk/jni/openxr"
    "${HUAWEI_VR_SDK_DIR}/sdkDemo/HVRSDK_XrDemo/jni/openxr"
    "${HUAWEI_VR_SDK_DIR}/openXRsdk/jni/openxr"
    "${HUAWEI_VR_SDK_DIR}/jni/openxr"
    "${HUAWEI_VR_SDK_DIR}/include/openxr"
    "${HUAWEI_VR_SDK_DIR}/include"
)
set(HUAWEI_VR_INCLUDE_DIR "")
foreach(_cand ${_hw_inc_candidates})
    if(EXISTS "${_cand}/openxr.h")
        set(HUAWEI_VR_INCLUDE_DIR "${_cand}")
        break()
    endif()
endforeach()

if(HUAWEI_VR_INCLUDE_DIR STREQUAL "")
    message(FATAL_ERROR
        "在 ${HUAWEI_VR_SDK_DIR} 下未找到 OpenXR 头文件（openxr.h）。\n"
        "请检查 SDK 是否完整解压；已尝试路径:\n  ${_hw_inc_candidates}")
endif()
message(STATUS "OpenXR 头文件目录: ${HUAWEI_VR_INCLUDE_DIR}")

# 定位 libxr_loader.so（华为 loader，各 ABI 预编译）
set(_hw_lib_candidates
    "${HUAWEI_VR_SDK_DIR}/sdkDemo/openXRsdk/jni/${ANDROID_ABI}"
    "${HUAWEI_VR_SDK_DIR}/sdkDemo/HVRSDK_XrDemo/jni/${ANDROID_ABI}"
    "${HUAWEI_VR_SDK_DIR}/openXRsdk/jni/${ANDROID_ABI}"
    "${HUAWEI_VR_SDK_DIR}/jni/${ANDROID_ABI}"
    "${HUAWEI_VR_SDK_DIR}/libs/${ANDROID_ABI}"
)
set(HUAWEI_VR_LIB_DIR "")
foreach(_cand ${_hw_lib_candidates})
    if(EXISTS "${_cand}/libxr_loader.so")
        set(HUAWEI_VR_LIB_DIR "${_cand}")
        break()
    endif()
endforeach()

if(HUAWEI_VR_LIB_DIR STREQUAL "")
    message(FATAL_ERROR
        "未找到 libxr_loader.so（ABI=${ANDROID_ABI}）。已尝试路径:\n  ${_hw_lib_candidates}\n"
        "请确认 SDK 的 jni 目录下是否有该 ABI 的 loader。")
endif()
message(STATUS "OpenXR Loader 目录: ${HUAWEI_VR_LIB_DIR}")

# ---------------------------------------------------------------------------
# 源文件清单
# ---------------------------------------------------------------------------
set(AURAVR_SOURCES
    aura_vr_jni.cpp
    aura_vr_session.cpp
    aura_vr_log.cpp
)

add_library(auravr SHARED ${AURAVR_SOURCES})

target_include_directories(auravr PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${HUAWEI_VR_INCLUDE_DIR}
)

# 链接：华为 OpenXR Loader + Android 系统库 + EGL/GLES
add_library(huawei_openxr_loader SHARED IMPORTED)
set_target_properties(huawei_openxr_loader PROPERTIES
    IMPORTED_LOCATION "${HUAWEI_VR_LIB_DIR}/libxr_loader.so")

target_link_libraries(auravr
    huawei_openxr_loader
    android
    log
    EGL
    GLESv3      # ⚠️ 华为要求 GLES 3.2，用 GLESv3（提供 ES 3.x 符号）
)

# 华为 SDK 用 XR_USE_PLATFORM_ANDROID / XR_USE_GRAPHICS_API_OPENGL_ES 开关，
# 必须在包含 openxr.h 之前定义（头文件里也定义了，此处保险起见也传一份）。
#
# ⚠️ AURA_HAVE_OPENXR：这是「是否接入华为 SDK」的总开关宏。
#    由 gradle 以 -DAURA_HAVE_OPENXR=1 传入（见 app/build.gradle.kts）。
#    这里用 CMake 变量判断后，再作为**编译宏**下发给编译器——
#    否则 CMake 会报「Manually-specified variables were not used by the project」。
if(NOT DEFINED AURA_HAVE_OPENXR)
    set(AURA_HAVE_OPENXR 0)
endif()
message(STATUS "AURA_HAVE_OPENXR = ${AURA_HAVE_OPENXR}")

target_compile_definitions(auravr PRIVATE
    XR_USE_PLATFORM_ANDROID=1
    XR_USE_GRAPHICS_API_OPENGL_ES=1
    AURA_HAVE_OPENXR=${AURA_HAVE_OPENXR}
)

target_compile_options(auravr PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    # OpenXR 结构体惯用 `XrXxxInfo info{XR_TYPE_XXX};` 聚合初始化（next 省略即 0），
    # 这是官方推荐写法，抑制该警告避免刷屏。
    -Wno-missing-field-initializers
    -fno-omit-frame-pointer     # 便于真机抓 backtrace
)

# ---------------------------------------------------------------------------
# 把华为 SDK 的 libxr_loader.so 一起打进 APK 的 libs/<abi>/
# 说明：libxr_loader.so 是预编译二进制，必须随包分发，否则运行时找不到。
#       ⚠️ 该目录已在 .gitignore 中忽略（华为许可禁止再分发 SDK 二进制）。
# ---------------------------------------------------------------------------
add_custom_command(TARGET auravr POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory
            "${CMAKE_CURRENT_SOURCE_DIR}/libs/${ANDROID_ABI}"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
            "${HUAWEI_VR_LIB_DIR}/libxr_loader.so"
            "${CMAKE_CURRENT_SOURCE_DIR}/libs/${ANDROID_ABI}/libxr_loader.so"
    COMMENT "复制华为 libxr_loader.so 到 jniLibs (${ANDROID_ABI})"
)
