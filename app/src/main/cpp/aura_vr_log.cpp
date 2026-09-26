// ============================================================================
// aura_vr_log.cpp —— OpenXR 结果码 → 可读字符串
// ----------------------------------------------------------------------------
// 数值来源：OpenXR 1.0 规范 + 华为 VR Engine 扩展。
// 这里**故意不 include openxr.h**，改用数值常量，好处：
//   1. 本文件可在没有华为 SDK 时也编译通过（便于 CI / 本地语法检查）
//   2. 避免不同 SDK 版本枚举差异导致编译失败
// 若 SDK 更新引入新错误码，只需在此表补一行。
// ============================================================================

#include "aura_vr_log.h"

const char* aura_vr_result_str(long result) {
    switch (result) {
        case 0:    return "XR_SUCCESS";
        case 1:    return "XR_TIMEOUT_EXPIRED";
        case 2:    return "XR_SESSION_LOSS_PENDING";
        case 3:    return "XR_EVENT_UNAVAILABLE";
        case 4:    return "XR_SPACE_BOUNDS_UNAVAILABLE";
        case 5:    return "XR_SESSION_NOT_FOCUSED";
        case 7:    return "XR_FRAME_DISCARDED";

        // ---------- 错误 ----------
        case -1:   return "XR_ERROR_VALIDATION_FAILURE";
        case -2:   return "XR_ERROR_RUNTIME_FAILURE";
        case -3:   return "XR_ERROR_OUT_OF_MEMORY";
        case -4:   return "XR_ERROR_API_VERSION_UNSUPPORTED";
        case -6:   return "XR_ERROR_INITIALIZATION_FAILED";
        case -7:   return "XR_ERROR_FUNCTION_UNSUPPORTED";
        case -8:   return "XR_ERROR_FEATURE_UNSUPPORTED";
        case -9:   return "XR_ERROR_EXTENSION_NOT_PRESENT";
        case -10:  return "XR_ERROR_LIMIT_REACHED";
        case -11:  return "XR_ERROR_SIZE_INSUFFICIENT";
        case -12:  return "XR_ERROR_HANDLE_INVALID";
        case -13:  return "XR_ERROR_INSTANCE_LOST";
        case -14:  return "XR_ERROR_SESSION_RUNNING";
        case -16:  return "XR_ERROR_SESSION_NOT_RUNNING";
        case -17:  return "XR_ERROR_SESSION_LOST";
        // 注意：-18/-19 在不同版本中含义有差异，按 1.0 规范映射
        case -18:  return "XR_ERROR_SYSTEM_INVALID";
        case -19:  return "XR_ERROR_PATH_INVALID";
        case -20:  return "XR_ERROR_PATH_COUNT_EXCEEDED";
        case -21:  return "XR_ERROR_PATH_FORMAT_INVALID";
        case -22:  return "XR_ERROR_PATH_UNSUPPORTED";
        case -23:  return "XR_ERROR_LAYER_INVALID";
        case -24:  return "XR_ERROR_LAYER_LIMIT_EXCEEDED";
        case -25:  return "XR_ERROR_SWAPCHAIN_RECT_INVALID";
        case -26:  return "XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED";
        case -27:  return "XR_ERROR_ACTION_TYPE_MISMATCH";
        case -28:  return "XR_ERROR_SESSION_NOT_READY";
        case -29:  return "XR_ERROR_SESSION_NOT_STOPPING";
        case -30:  return "XR_ERROR_TIME_INVALID";
        case -31:  return "XR_ERROR_REFERENCE_SPACE_UNSUPPORTED";
        case -32:  return "XR_ERROR_FILE_ACCESS_ERROR";
        case -33:  return "XR_ERROR_FILE_CONTENTS_INVALID";
        case -34:  return "XR_ERROR_FORM_FACTOR_UNSUPPORTED";
        case -35:  return "XR_ERROR_FORM_FACTOR_UNAVAILABLE";
        case -36:  return "XR_ERROR_API_LAYER_NOT_PRESENT";
        case -37:  return "XR_ERROR_CALL_ORDER_INVALID";
        case -38:  return "XR_ERROR_GRAPHICS_DEVICE_INVALID";
        case -39:  return "XR_ERROR_POSE_INVALID";
        case -40:  return "XR_ERROR_INDEX_OUT_OF_RANGE";
        case -41:  return "XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED";
        case -42:  return "XR_ERROR_ENVIRONMENT_BLEND_MODE_UNSUPPORTED";

        // ---------- 扩展错误 ----------
        case -1000000000: return "XR_ERROR_NAME_DUPLICATED";
        case -1000000001: return "XR_ERROR_NAME_INVALID";
        case -1000000002: return "XR_ERROR_ACTIONSET_NOT_ATTACHED";
        case -1000000003: return "XR_ERROR_ACTIONSETS_ALREADY_ATTACHED";
        case -1000000004: return "XR_ERROR_LOCALIZED_NAME_DUPLICATED";
        case -1000000005: return "XR_ERROR_LOCALIZED_NAME_INVALID";
        case -1000000006: return "XR_ERROR_GRAPHICS_REQUIREMENTS_CALL_MISSING";
        case -1000000007: return "XR_ERROR_RUNTIME_UNAVAILABLE";
        case -1000000008: return "XR_ERROR_EXTENSION_DEPENDENCY_NOT_ENABLED";
        case -1000000009: return "XR_ERROR_PERMISSION_INSUFFICIENT";
        case -1000000010: return "XR_ERROR_ANDROID_THREAD_SETTINGS_ID_INVALID_KHR";
        case -1000000011: return "XR_ERROR_ANDROID_THREAD_SETTINGS_FAILURE_KHR";
        case -1000000012: return "XR_ERROR_CREATE_SPATIAL_ANCHOR_FAILED_MSFT";
        case -1000000013: return "XR_ERROR_SECONDARY_VIEW_CONFIGURATION_TYPE_NOT_ENABLED_MSFT";
        case -1000000014: return "XR_ERROR_CONTROLLER_MODEL_KEY_INVALID_MSFT";
        case -1000000015: return "XR_ERROR_PASSTHROUGH_COLOR_LUT_BUFFER_SIZE_MISMATCH_META";
        case -1000000016: return "XR_ERROR_HINT_ALREADY_SET_QCOM";
        case -1000710000: return "XR_ERROR_SPATIAL_ANCHOR_NOT_FOUND_MSFT";
        case -1000710001: return "XR_ERROR_SPATIAL_ANCHOR_NAME_NOT_FOUND_MSFT";

        default: return "XR_UNKNOWN_RESULT";
    }
}
