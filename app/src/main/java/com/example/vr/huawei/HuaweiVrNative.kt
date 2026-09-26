package com.example.vr.huawei

import android.app.Activity
import android.util.Log

/**
 * 华为 VR（VR Engine / OpenXR）native 层门面。
 *
 * 对应 native 实现：`app/src/main/cpp/aura_vr_jni.cpp`
 * 设计文档：`HUAWEI_VR_ENGINE_PLAN_2026-09-26.md` 第 5 节。
 *
 * ⚠️ 本类**必须**与 native 侧的方法名/签名严格一致，改一处要同步改另一处：
 * ```
 * com/example/vr/huawei/HuaweiVrNative   ← 包名+类名
 * nativeSetSurface(Surface) : void
 * nativeInitialize(Activity, int) : boolean
 * nativeStart() : boolean
 * nativeShutdown() : void
 * nativeLastError() : String
 * nativeState() : int
 * nativeRecommendEyeSize() : int[]
 * nativeAcquireEyeTargets() : int[]
 * nativeEyeTargetsEx() : float[]
 * nativeSetExternalRendererEnabled(boolean) : void
 * nativeHasPendingFrame() : boolean
 * nativeSubmitFrame() : void
 * nativeIsSdkAvailable() : boolean
 * ```
 */
object HuaweiVrNative {

    private const val TAG = "HuaweiVrNative"

    /** 会话状态（与 C++ `AuraVrState` 一一对应，勿改数值顺序） */
    object State {
        const val IDLE = 0
        const val INSTANCE_CREATED = 1
        const val SESSION_READY = 2
        const val RUNNING = 3
        const val STOPPING = 4
        const val ERROR = 5
    }

    /** 每眼在 [eyeTargetsEx] 返回数组里占用的浮点数量（16 矩阵 + 4 FOV） */
    const val VALUES_PER_EYE = 20

    /**
     * `.so` 是否加载成功。
     *
     * 说明：工程在**华为 SDK 未接入时**也能正常构建 —— 此时 `libauravr.so` 里
     * 是「桩实现」（`nativeIsSdkAvailable()` 返回 false），不会影响非华为设备。
     * 只有 SDK 到位并在 CMake 中启用后，才是真实 OpenXR 实现。
     */
    val isLibraryLoaded: Boolean = try {
        System.loadLibrary("auravr")
        // ⚠️ 华为 OpenXR Loader 的真实文件名是 libxr_loader.so（不是 lib_loader.so），
        //    所以这里必须写 "xr_loader"。
        try {
            System.loadLibrary("xr_loader")
        } catch (e: UnsatisfiedLinkError) {
            // 桩路径下不存在，属预期；真实路径下若失败会在 initialize 时报错
            Log.i(TAG, "libxr_loader.so 未加载（SDK 未接入时为预期）: ${e.message}")
        }
        true
    } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "libauravr.so 加载失败", e)
        false
    }

    /** 编译期是否真正接入了华为 OpenXR SDK（false = 桩实现） */
    val isSdkBuiltIn: Boolean
        get() = isLibraryLoaded && runCatching { nativeIsSdkAvailable() }.getOrDefault(false)

    /**
     * 设置 Java 侧 Surface（EGL window surface 的来源）。
     *
     * ⚠️ 必须在 [initialize] **之前**调用，且要等 `SurfaceView.surfaceCreated` 触发。
     * 华为的 EGL 走 window surface 模式，没有 Surface 就没有可上屏的交换链。
     *
     * @param surface `SurfaceHolder.getSurface()`；传 null 表示 Surface 已销毁
     */
    fun setSurface(surface: android.view.Surface?) {
        if (!isLibraryLoaded) return
        runCatching { nativeSetSurface(surface) }
            .onFailure { Log.e(TAG, "setSurface 抛异常", it) }
    }

    /**
     * 初始化 OpenXR 会话（创建 instance / system / EGL / session / swapchain）。
     * @param renderScalePercent 每眼分辨率相对 Runtime 推荐值的百分比（50/75/100）
     * @return 成功与否；失败时用 [lastError] 取可读原因
     */
    fun initialize(activity: Activity, renderScalePercent: Int = 100): Boolean {
        if (!isLibraryLoaded) {
            Log.e(TAG, "initialize: native 库未加载")
            return false
        }
        return try {
            nativeInitialize(activity, renderScalePercent)
        } catch (t: Throwable) {
            Log.e(TAG, "initialize 抛异常", t)
            false
        }
    }

    /** 启动帧循环 */
    fun start(): Boolean = if (!isLibraryLoaded) false
    else runCatching { nativeStart() }.getOrDefault(false)

    /** 停止并清理（幂等） */
    fun shutdown() {
        if (!isLibraryLoaded) return
        runCatching { nativeShutdown() }
            .onFailure { Log.e(TAG, "shutdown 抛异常", it) }
    }

    /** 上次失败原因（给用户看，不要吞掉） */
    fun lastError(): String =
        if (!isLibraryLoaded) "native 库未加载"
        else runCatching { nativeLastError() }.getOrDefault("")

    /** 当前会话状态（见 [State]） */
    fun state(): Int =
        if (!isLibraryLoaded) State.ERROR
        else runCatching { nativeState() }.getOrDefault(State.ERROR)

    /**
     * Runtime 推荐的每眼尺寸 `[width, height]`。
     * 参考项目真机实测：VR Glass 为 **1552×1552/眼**。
     */
    fun recommendEyeSize(): IntArray =
        runCatching { nativeRecommendEyeSize() }.getOrNull() ?: intArrayOf(0, 0)

    /**
     * 取当前帧双眼渲目标：`[texL, wL, hL, texR, wR, hR]`。
     *
     * @return null 表示本帧无可用目标 → **保留上一帧，不要清屏**
     *         （参考项目经验：清屏会导致眼镜内闪黑）
     */
    fun acquireEyeTargets(): IntArray? =
        runCatching { nativeAcquireEyeTargets() }.getOrNull()

    /**
     * 取当前帧双眼的**完整渲染参数**：长度 40 的浮点数组。
     * - 眼 0（左）占 `[0..19]`，眼 1（右）占 `[20..39]`
     * - 每眼前 16 个 = 视图矩阵（**列主序**，可直传 GLSL `mat4`）
     * - 每眼后 4 个 = FOV `{left, right, up, down}`（弧度）
     *
     * @return null 表示本帧无新姿态 → 保留上一帧矩阵，不要重置
     */
    fun eyeTargetsEx(): FloatArray? =
        runCatching { nativeEyeTargetsEx() }.getOrNull()

    /**
     * 开关「Kotlin 负责画」模式。
     *
     * ⚠️ 必须在 [start] 之前调用才生效：
     * - `true`  = Kotlin 把自己的画面画进 swapchain texture（真机 3D 贴片通路）
     * - `false` = native 只维持会话与姿态，不提交画面层
     *   （桌面/无眼镜场景安全自测用，避免层结构不被支持导致黑屏）
     */
    fun setExternalRendererEnabled(enabled: Boolean) {
        if (!isLibraryLoaded) return
        runCatching { nativeSetExternalRendererEnabled(enabled) }
            .onFailure { Log.e(TAG, "setExternalRendererEnabled 抛异常", it) }
    }

    /** 本帧是否有待提交的 swapchain（Kotlin 渲染前判断用） */
    fun hasPendingFrame(): Boolean =
        if (!isLibraryLoaded) false
        else runCatching { nativeHasPendingFrame() }.getOrDefault(false)

    /** 提交当前帧 */
    fun submitFrame() {
        if (!isLibraryLoaded) return
        runCatching { nativeSubmitFrame() }
            .onFailure { Log.e(TAG, "submitFrame 抛异常", it) }
    }

    // ---------------- native 声明（与 C++ 严格一致） ----------------
    private external fun nativeSetSurface(surface: android.view.Surface?)
    private external fun nativeInitialize(activity: Activity, renderScalePercent: Int): Boolean
    private external fun nativeStart(): Boolean
    private external fun nativeShutdown()
    private external fun nativeLastError(): String
    private external fun nativeState(): Int
    private external fun nativeRecommendEyeSize(): IntArray
    private external fun nativeAcquireEyeTargets(): IntArray?
    private external fun nativeEyeTargetsEx(): FloatArray?
    private external fun nativeSetExternalRendererEnabled(enabled: Boolean)
    private external fun nativeHasPendingFrame(): Boolean
    private external fun nativeSubmitFrame()
    private external fun nativeIsSdkAvailable(): Boolean
}
