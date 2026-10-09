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
# AURA_HAVE_OPENXR：**是否接入 VR 眼镜（OpenXR）** 的总开关（v2.4.16）
# ---------------------------------------------------------------------------
# ⚠️ 判定必须放在**最前面** —— 下面「定位 VR 眼镜 SDK」整段都要靠它决定是否执行。
#    主线（standard flavor）不传这个宏时，连 SDK 都不会去找，自然也不会因缺 SDK 报错。
#
# 由 gradle 按 flavor 传入（见 app/build.gradle.kts）：
#   standard（主线）        → 不传 → 默认 0 → 不编 OpenXR、不打包任何 VR loader
#   huawei / pico / quest / all → -DAURA_HAVE_OPENXR=1
if(NOT DEFINED AURA_HAVE_OPENXR)
    set(AURA_HAVE_OPENXR 0)
endif()
message(STATUS "AURA_HAVE_OPENXR = ${AURA_HAVE_OPENXR}")

# ---------------------------------------------------------------------------
# 定位 VR 眼镜 SDK —— **仅在 AURA_HAVE_OPENXR=1 时**
# ---------------------------------------------------------------------------
# ⚠️ 这里要的是 **Khronos 标准 OpenXR 头文件**（openxr.h / openxr_platform.h）。
#    华为 / PICO / Meta Quest 三家**共用同一套标准头**，差异只在 **loader 二进制**：
#      huawei → libxr_loader.so（华为 SDK 自带）
#      pico   → libopenxr_loader.so（Khronos AAR）
#      quest  → 同上
#    所以即使只出 pico/quest flavor，也同样需要这份 SDK 的头文件。
#
# ⚠️ 历史坑：这一整段此前是**无条件**的 —— 于是「不接 VR」也必须先装华为 SDK，
#    否则 CMake 直接 FATAL_ERROR。这正是「主线被 VR 眼镜绑架」的根源，本次修正。
if(AURA_HAVE_OPENXR)

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

endif()  # if(AURA_HAVE_OPENXR) —— 定位 VR 眼镜 SDK

# ---------------------------------------------------------------------------
# 源文件清单
# ---------------------------------------------------------------------------
# ===========================================================================
# 源文件清单 —— **核心**（任何 flavor 都编）
# ---------------------------------------------------------------------------
# v2.4.16：把「VR 眼镜专属」的三个源文件从这里挪出去（见下方条件块），
#   使主线包（standard flavor）**不需要 VR SDK** 就能编译。
#
# 归属判据 = **这个文件是否直接依赖 OpenXR 类型/函数**：
#   · aura_vr_jni.cpp  —— 核心：JNI 入口（VR 相关调用已用 #if 包住）
#   · aura_vr_log.cpp  —— 核心：日志（对 XrResult 只做数值映射，不依赖 openxr.h）
#   · aura_vr_ui.cpp   —— 核心：VR 内 UI（纯 GL + 数学；**不**依赖 OpenXR）
# ===========================================================================
set(AURAVR_SOURCES
    aura_vr_jni.cpp
    aura_vr_log.cpp
    # v2.0.202：VR 内 UI 层（控制条 / 准星 / 射线求交，native GL 绘制）
    aura_vr_ui.cpp
)

# ===========================================================================
# VR 眼镜专属源文件 —— 仅 AURA_HAVE_OPENXR=1 时编译
# ---------------------------------------------------------------------------
#   aura_vr_session.cpp —— OpenXR 会话/交换链/帧循环（13 处 #if 保护）
#   aura_xr_loader.cpp  —— 多 loader 运行时选择（dlopen + 函数指针重定向）
#   aura_vr_input.cpp   —— 手柄输入（OpenXR Action 系统）
#
# ⚠️ 这三个都**直接**调用 OpenXR API，无 SDK 时无法编译 → 必须排除在主线之外。
# ===========================================================================
if(AURA_HAVE_OPENXR)
    list(APPEND AURAVR_SOURCES
        aura_vr_session.cpp
        # v2.0.195：多 OpenXR loader 运行时选择（dlopen + 函数指针重定向）
        aura_xr_loader.cpp
        # v2.0.199：华为 VR Glass 手柄输入（OpenXR Action 系统）
        aura_vr_input.cpp
    )
endif()

# ==============================================================================
# v2.4.13：**自建 llama.cpp + libmtmd** 的 JNI 封装
#   （用途：本地 AI 字幕翻译 + 本地 AI 弹幕生成，含「本地看画面」）
# ------------------------------------------------------------------------------
# ⚠️ 只在 **arm64-v8a** 编译 —— 自建时只编了该 ABI（与 v2.4.10 起的单 ABI 一致）；
#    其它 ABI 上会因为找不到 libllama.so / libmtmd.so 而**链接失败**，
#    所以必须放在条件里，而不是直接写进 AURAVR_SOURCES。
# ==============================================================================
if(ANDROID_ABI STREQUAL "arm64-v8a")
    list(APPEND AURAVR_SOURCES llama_mtmd_jni.cpp)
endif()

add_library(auravr SHARED ${AURAVR_SOURCES})

target_include_directories(auravr PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    # v2.4.13：llama.cpp / mtmd 头文件（随源码置于 cpp/llama/include，无需外部依赖）
    ${CMAKE_CURRENT_SOURCE_DIR}/llama/include
)

# ⚠️ VR SDK 的头目录**按条件加**：变量为空时不能直接传（CMake 会报错）。
if(AURA_HAVE_OPENXR)
    target_include_directories(auravr PRIVATE ${HUAWEI_VR_INCLUDE_DIR})
endif()

# 链接：Android 系统库 + EGL/GLES
# ⚠️ v2.0.195：**不再链接** huawei_openxr_loader —— 改为运行时 dlopen
#    （见 aura_xr_loader.cpp）。原因：链接会把 libauravr.so 的 DT_NEEDED 钉死在
#    某一个 loader 上，而一份 APK 要同时支持华为 / PICO 新老固件 / Quest。
#    华为的 libxr_loader.so 仍会被**拷贝进 APK**（见本文件末尾的 POST_BUILD），
#    因为 dlopen 需要它在 libs/<abi>/ 下存在；dl 库本身来自 Android 系统，无需链接。
# ==============================================================================
# v2.4.13：**自建 llama.cpp + libmtmd** 的编译期链接
# ------------------------------------------------------------------------------
# .so 由 `app/src/main/jniLibs/<abi>/` 提供（AGP 会**自动**打进 APK，
# 且该目录**不受 huawei.vr.enable 开关影响** —— 这是刻意的，
# 见 app/build.gradle.kts 里对该目录的说明）。
# 这里只负责**编译期链接**，让 libauravr.so 的 DT_NEEDED 指向它们。
#
# ⚠️ 不链接也不会立刻报错（因为 JNI 是运行时解析符号），但**运行时会 dlopen 失败**。
# ==============================================================================
set(AURA_LLAMA_LIBS "")
if(ANDROID_ABI STREQUAL "arm64-v8a")
    add_library(llama SHARED IMPORTED)
    set_target_properties(llama PROPERTIES
        IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libllama.so")

    add_library(mtmd SHARED IMPORTED)
    set_target_properties(mtmd PROPERTIES
        IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libmtmd.so")

    set(AURA_LLAMA_LIBS llama mtmd)
    message(STATUS "已启用自建 llama.cpp + libmtmd（${ANDROID_ABI}）")
else()
    message(STATUS "ABI ${ANDROID_ABI} 无自建 llama 库 → 本地 LLM 功能在该 ABI 上不可用")
endif()

target_link_libraries(auravr
    android
    log
    EGL
    GLESv3      # ⚠️ 华为要求 GLES 3.2，用 GLESv3（提供 ES 3.x 符号）
    dl          # v2.0.195：dlopen/dlsym
    ${AURA_LLAMA_LIBS}
)

# 华为 SDK 用 XR_USE_PLATFORM_ANDROID / XR_USE_GRAPHICS_API_OPENGL_ES 开关，
# 必须在包含 openxr.h 之前定义（头文件里也定义了，此处保险起见也传一份）。
#
# ⚠️ AURA_HAVE_OPENXR：这是「是否接入华为 SDK」的总开关宏。
#    由 gradle 以 -DAURA_HAVE_OPENXR=1 传入（见 app/build.gradle.kts）。
#    这里用 CMake 变量判断后，再作为**编译宏**下发给编译器——
#    否则 CMake 会报「Manually-specified variables were not used by the project」。
# ⚠️ v2.4.16：AURA_HAVE_OPENXR 的默认值判定已**提前到文件开头**
#    （因为「定位 SDK」那段要用它决定是否执行），此处不再重复。

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
# ⚠️ v2.4.16：**仅接入 VR 眼镜时**才复制 loader ——
#    主线包（standard）不该带任何 VR loader（省约 4.1MB，且不再需要 SDK）。
if(AURA_HAVE_OPENXR)
    add_custom_command(TARGET auravr POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E make_directory
                "${CMAKE_CURRENT_SOURCE_DIR}/libs/${ANDROID_ABI}"
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
                "${HUAWEI_VR_LIB_DIR}/libxr_loader.so"
                "${CMAKE_CURRENT_SOURCE_DIR}/libs/${ANDROID_ABI}/libxr_loader.so"
        COMMENT "复制华为 libxr_loader.so 到 jniLibs (${ANDROID_ABI})"
    )
endif()

# ===========================================================================
# GPUPixel（本地 fork，git submodule: third_party/gpupixel）—— 源码级集成
# ---------------------------------------------------------------------------
# 为什么改用源码而非 AAR：
#   需要 fork 新增的 texture 通道（共享 EGLContext + SinkTexture），把处理结果
#   留在 GPU 上供本渲染器直接采样，从而消除每帧「回读成字节 → 再上传」的跨界
#   搬运（1080p 实测约 5 × 8.29 MB/帧，占 gpu 段 19~20 ms + 回读 8~9 ms 的绝大部分）。
#   AAR 只暴露 raw-data 通道，无法接入。
# 设计见 GPUPIXEL_TEXTURE_PATH_FEASIBILITY_2026-09-29.md（§2.2 含 PoC 实测结论）。
#
# 要点：
#   · GPUPIXEL_FORK_DIR 由 app/build.gradle.kts 传入（指向 third_party/gpupixel）
#   · GPUPixel 顶层自带 project()，其内部 PROJECT_SOURCE_DIR 会正确指向自己
#   · 产出 libgpupixel.so 由 AGP 自动收集打包
#   · libmars-face-kit.so 是 fork 内预编译二进制，须拷进 jniLibs（同 libxr_loader.so 的做法）
#   · ⚠️ 与 libs/gpupixel-release.aar 互斥：AAR 里也有 libgpupixel.so，二者同时存在
#     会触发 mergeNativeLibs 的「2 files found with path」冲突
# ===========================================================================
if(NOT DEFINED GPUPIXEL_FORK_DIR OR GPUPIXEL_FORK_DIR STREQUAL "")
    message(FATAL_ERROR
        "未提供 GPUPIXEL_FORK_DIR（应指向 third_party/gpupixel）。"
        "它由 app/build.gradle.kts 的 externalNativeBuild.cmake.arguments 传入。")
endif()
file(TO_CMAKE_PATH "${GPUPIXEL_FORK_DIR}" GPUPIXEL_FORK_DIR)
message(STATUS "GPUPixel fork 路径: ${GPUPIXEL_FORK_DIR}")

set(_gpupixel_mars_so
    "${GPUPIXEL_FORK_DIR}/third_party/mars-face-kit/libs/android/${ANDROID_ABI}/libmars-face-kit.so")
if(NOT EXISTS "${_gpupixel_mars_so}")
    message(FATAL_ERROR
        "GPUPixel 缺少 ${ANDROID_ABI} 的 libmars-face-kit.so：${_gpupixel_mars_so}\n"
        "（官方预编译只含 arm64-v8a / armeabi-v7a；本工程 abiFilters 只有 arm64-v8a）")
endif()

# 独立 build 目录，避免与 auravr 的产物混淆
add_subdirectory("${GPUPIXEL_FORK_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/gpupixel_build")

# ⚠️ 这里**不能**用 `add_custom_command(TARGET gpupixel POST_BUILD ...)` —— CMake 规定
#    该形式只能作用于「本 CMakeLists 创建的 target」，而 gpupixel 建在子目录里，
#    会报 `CMake Error at ... (add_custom_command)`。
#    libmars-face-kit.so 改由 gradle 侧挂 jniLibs 源目录解决（见 app/build.gradle.kts），
#    那个目录结构 `.../libs/android/<abi>/*.so` 正好符合 jniLibs 的 ABI 约定。
