package com.example.vr

/*
 * v2.4.63：从 VRPlayerScreen.kt 拆出 —— **华为 VR Glass（VR Engine）接入支持**。
 *
 * 原文件（9111 行）末尾的这段与 UI 无关，是纯探测/路由逻辑，独立成文件便于维护。
 * 内容与拆分前逐字一致（仅 `private` 可见性未变，因为原本就是 public）。
 */

import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import com.example.vr.huawei.HuaweiVrActivity
import kotlin.math.roundToInt
import android.net.Uri
import android.util.Log

// ============================================================================
// 华为 VR Glass（VR Engine）接入支持
// ----------------------------------------------------------------------------
// 设计见 HUAWEI_VR_ENGINE_PLAN_2026-09-26.md 第 6 节。本段只做「用户可见的一层」：
// 运行时探测 + 进入路由 + 降级提示。真正的 OpenXR 会话层（lib_loader.so /
// HuaweiVrActivity / GLES3.2 上下文）属于 P0~P2，需要华为真机才能实施与验证。
// ============================================================================
object HuaweiVrRuntime {
    /** 华为 VR SDK Service（插眼镜后自动安装的运行时服务） */
    const val PKG_SDK_SERVICE = "com.huawei.hvrsdkserverapp"

    /** 华为 VR 设备管理/手柄服务 */
    const val PKG_VRHANDLE = "com.huawei.vrhandle"

    /** 官方「2D 应用 → VR」提示页 Action（由 hvrprompt.aar 的 HVRModeActivity 响应） */
    const val ACTION_VR_PROMPT = "com.huawei.android.vr.PROMPT"

    /** 眼镜推荐 swapchain 尺寸（每眼），仅作注释用途 */
    const val RECOMMENDED_EYE_SIZE = 1552

    /** 眼镜刷新率 */
    const val DISPLAY_REFRESH_RATE_HZ = 70
}

/**
 * 探测华为 VR 运行时是否可用。
 *
 * 注意：`PackageManager.getPackageInfo` 在包不存在时抛 `NameNotFoundException`，
 * 某些定制 ROM 还可能抛其它 SecurityException，因此统一按「查得到即可用」处理，
 * 任何异常都视为不可用（安全降级）。
 */
fun isHuaweiVrRuntimeAvailable(context: Context): Boolean {
    val pm = context.packageManager ?: return false
    for (pkg in listOf(HuaweiVrRuntime.PKG_SDK_SERVICE, HuaweiVrRuntime.PKG_VRHANDLE)) {
        try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
            return true
        } catch (_: Exception) {
            // 该包不存在，继续探测下一个
        }
    }
    return false
}

/**
 * 构建并「预备」进入华为 VR 的 Intent。
 *
 * 两条路一起给出，按可靠性排序：
 *
 * ① **显式指向自身的 `HuaweiVrActivity`**（主路径，本函数实际使用）
 *    该 Activity 在 Manifest 中声明了 `action=com.huawei.android.vr.PROMPT` 的
 *    `<intent-filter>`，同时又是显式组件。显式 `setClass` 启动最稳，
 *    不依赖 `hvrprompt.aar` 是否已集成，也不受各 ROM 对隐式 Intent 的限制。
 *
 * ② `action=PROMPT` + `setPackage(自身包名)`（备用，见 [HuaweiVrRuntime.ACTION_VR_PROMPT]）
 *    集成 `hvrprompt.aar` 后由 `com.huawei.vrlab.HVRModeActivity` 响应，
 *    插眼镜可跳过 VrLauncher。P1 再启用。
 *
 * ⚠️ 本函数有副作用：会写入 `HuaweiVrActivity.onBeforeKill` 与
 * `HuaweiVrActivity.onVideoSurfaceNeeded`（静态回调）。
 * 这是刻意的——Activity 退出时 `killProcess`，设置必须在同一进程内先落盘；
 * 视频源也必须跨 Activity 边界交给华为侧的渲染器。
 * 回调用可空静态引用 + `@Volatile`，进程重启后自动失效，不会泄漏 Activity/Context。
 *
 * @param renderScale 渲染分辨率倍率（0.5f~1.0f），转成百分比传给 Activity
 * @param onBeforeKill 退出前落盘动作；传 null 则不覆盖既有回调
 * @param onVideoSurfaceNeeded P1 视频源接线；见 [HuaweiVrActivity.onVideoSurfaceNeeded]
 */
fun buildHuaweiVrPromptIntent(
    context: Context,
    renderScale: Float = 1.0f,
    onBeforeKill: (() -> Unit)? = null,
    onVideoSurfaceNeeded: ((SurfaceTexture) -> Unit)? = null
): Intent {
    if (onBeforeKill != null) {
        HuaweiVrActivity.onBeforeKill = onBeforeKill
    }
    // ⚠️ 视频接线回调也必须每次覆盖（哪怕是 null）：上一次会话的 lambda 可能捕获了
    //    已失效的 player/view 引用，留着会在华为侧触发时静默失败或崩。
    HuaweiVrActivity.onVideoSurfaceNeeded = onVideoSurfaceNeeded

    val percent = (renderScale.coerceIn(0.5f, 1.0f) * 100f).roundToInt()
    return Intent(context, HuaweiVrActivity::class.java).apply {
        action = HuaweiVrRuntime.ACTION_VR_PROMPT
        putExtra(HuaweiVrActivity.EXTRA_RENDER_SCALE, percent)
        putExtra(HuaweiVrActivity.EXTRA_EXTERNAL_RENDERER, true)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
