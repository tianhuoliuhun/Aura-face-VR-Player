package com.example.vr.vrinput

import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * VR 设备兼容层：**设备识别 + 手柄按键/摇杆抽象**。
 *
 * ## 为什么做「平台无关的抽象」
 * 奇遇（爱奇艺 / 梦想绽放）、华为 VR Glass、Pico 等一体机的手柄，最终都是通过
 * **标准 Android 输入事件**（`KeyEvent` / `MotionEvent`）上报的 —— 键码大体一致
 * （A/B/X/Y、L1/R1、摇杆、扳机）。所以这里**不直接绑死奇遇**，而是：
 *
 * ```
 * 平台原生事件  ──►  [本文件] 归一化成 VrGamepadAction  ──►  播放器消费
 * ```
 *
 * 好处：
 * - 新增一款 VR 设备时，通常**只需在 [VrGamepad.mapKeyEvent] 里补几条键码映射**，
 *   播放器侧的消费逻辑一行都不用改；
 * - 将来若接入奇遇 Native SDK（拿到 6DoF 手柄姿态），也只需把 SDK 的姿态/按键
 *   转换成同一套 [VrGamepadAction] 投递进来，**上层保持稳定**。
 *
 * ⚠️ 目前项目**没有奇遇真机**，本层为「代码预留」：映射表按 Android 标准键码编写，
 *    待真机到手后按实际键值校准（尤其是摇杆轴与扳机轴的方向/量程）。
 */
object VrGamepad {

    private const val TAG = "VrGamepad"

    /**
     * VR 设备识别（用于决定是否启用一体机专属行为，如关闭手机陀螺仪、切换渲染路径）。
     *
     * 奇遇的 ROM 标识：`ro.product.brand` / `ro.product.manufacturer` 含 `qiyu` / `iqiyi`，
     * `ro.build.version.qiyu` 为其系统版本号。保守起见**多字段一起判断**，
     * 任一命中即认为是奇遇设备（不同机型/固件版本字段不完全一致）。
     */
    fun isQiyuDevice(): Boolean = try {
        val brand = (Build.BRAND ?: "").lowercase()
        val manufacturer = (Build.MANUFACTURER ?: "").lowercase()
        val product = (Build.PRODUCT ?: "").lowercase()
        val device = (Build.DEVICE ?: "").lowercase()
        val model = (Build.MODEL ?: "").lowercase()
        val hay = "$brand|$manufacturer|$product|$device|$model"
        hay.contains("qiyu") || hay.contains("iqiyi") || hay.contains("奇迹") ||
            hay.startsWith("mirage") // 奇遇部分机型 product 名为 mirage
    } catch (e: Exception) {
        false
    }

    /** 是否运行在「VR 一体机」上（当前识别到的：奇遇；后续可扩展 Pico / Quest 等） */
    fun isStandaloneVr(): Boolean = isQiyuDevice()

    /** 设备描述（用于界面提示与日志） */
    fun deviceLabel(): String = when {
        isQiyuDevice() -> "奇遇 VR（QIYU OS）"
        isStandaloneVr() -> "VR 一体机"
        else -> "通用 Android"
    }

    /**
     * 把 Android `KeyEvent` 归一化成 [VrGamepadAction]。
     *
     * 只处理 `ACTION_DOWN`（长按/重复交给上层决定是否需要），未映射的返回 null。
     *
     * ## 映射表（按 Android 标准键码）
     * | 按键 | Action | 播放器语义 |
     * |---|---|---|
     * | A / 扳机（部分机型上报为 A） | [VrGamepadAction.PLAY_PAUSE] | 播放 / 暂停 |
     * | B | [VrGamepadAction.BACK] | 返回上一层 / 显示控制栏 |
     * | X | [VrGamepadAction.TOGGLE_SUBTITLE] | 字幕开关 |
     * | Y | [VrGamepadAction.RECENTER] | 画面重居中（陀螺仪方案专用）|
     * | L1 / R1 | [VrGamepadAction.SEEK_BACKWARD] / [SEEK_FORWARD] | 快退 / 快进 |
     * | 十字键左右 | 同上 | 快退 / 快进 |
     * | 十字键上下 | 音量 / 亮度（由上层定）| — |
     * | START | [VrGamepadAction.MENU] | 打开设置面板 |
     * | THUMBL/THUMBR（按下摇杆）| [VrGamepadAction.TOGGLE_UI] | 显示/隐藏控制栏 |
     *
     * ⚠️ 未映射的按键**不拦截**（返回 null → 交回系统），避免影响系统级快捷键。
     */
    fun mapKeyEvent(event: KeyEvent): VrGamepadAction? {
        if (event.action != KeyEvent.ACTION_DOWN) return null
        val a = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> VrGamepadAction.PLAY_PAUSE
            KeyEvent.KEYCODE_BUTTON_B -> VrGamepadAction.BACK
            KeyEvent.KEYCODE_BUTTON_X -> VrGamepadAction.TOGGLE_SUBTITLE
            KeyEvent.KEYCODE_BUTTON_Y -> VrGamepadAction.RECENTER
            KeyEvent.KEYCODE_BUTTON_L1 -> VrGamepadAction.SEEK_BACKWARD
            KeyEvent.KEYCODE_BUTTON_R1 -> VrGamepadAction.SEEK_FORWARD
            KeyEvent.KEYCODE_DPAD_LEFT -> VrGamepadAction.SEEK_BACKWARD
            KeyEvent.KEYCODE_DPAD_RIGHT -> VrGamepadAction.SEEK_FORWARD
            KeyEvent.KEYCODE_BUTTON_START -> VrGamepadAction.MENU
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR -> VrGamepadAction.TOGGLE_UI
            else -> null
        }
        if (a != null) Log.i(TAG, "手柄按键: ${KeyEvent.keyCodeToString(event.keyCode)} -> $a")
        return a
    }

    /**
     * 摇杆 / 扳机（`MotionEvent` 轴）归一化。
     *
     * ⚠️ **待真机校准**：本项目目前拿不到奇遇手柄，轴索引与方向按 Android 常见约定编写
     *    （左摇杆 X = `AXIS_X`，扳机 = `AXIS_LTRIGGER`/`AXIS_RTRIGGER`，量程 0~1）。
     *    真机到手后需核对：① 是否需要死区；② 方向是否与预期相反。
     *
     * @return 播放器所需的「进度拖动」意图：-1~1（负=后退，正=前进），无输入返回 null
     */
    fun mapStick(event: MotionEvent): Float? {
        if (event.action != MotionEvent.ACTION_MOVE) return null
        val x = event.getAxisValue(MotionEvent.AXIS_X)
        // 死区 0.25：摇杆回中不精确时会持续输出微小值，直接当输入会让进度一直漂
        return if (kotlin.math.abs(x) > 0.25f) x else null
    }

    /** 是否为正被按住的「加速」扳机（长按加速播放） */
    fun isFastForwardTriggerPressed(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_MOVE) return false
        val lt = event.getAxisValue(MotionEvent.AXIS_LTRIGGER)
        val rt = event.getAxisValue(MotionEvent.AXIS_RTRIGGER)
        return lt > 0.6f || rt > 0.6f
    }
}

/**
 * 与平台无关的「手柄动作」——播放器只认这一套语义，不关心是哪个设备发来的。
 */
enum class VrGamepadAction {
    /** 播放 / 暂停 */
    PLAY_PAUSE,

    /** 返回：优先关面板，无面板则退出确认 */
    BACK,

    /** 字幕开关 */
    TOGGLE_SUBTITLE,

    /** 画面重居中（陀螺仪 VR 方案专用） */
    RECENTER,

    /** 快进（步长由播放器设置决定） */
    SEEK_FORWARD,

    /** 快退 */
    SEEK_BACKWARD,

    /** 打开设置面板 */
    MENU,

    /** 显示 / 隐藏控制栏 */
    TOGGLE_UI,
}

/**
 * 手柄事件总线：`MainActivity` 在 `dispatchKeyEvent` 里捕获并按 [VrGamepad] 归一化后投递，
 * 当前可见的播放界面通过 [handler] 注册消费。
 *
 * 之所以用「单处理器」而不是多播：手柄动作是**全局唯一意图**（同时只应有一个界面响应），
 * 多播会导致「面板和播放器同时响应一次按键」这类重复触发。
 *
 * ⚠️ 处理器必须用 `DisposableEffect` 注册并在 `onDispose` 置空，
 *    否则退出播放界面后按键仍会被已销毁的界面吃掉。
 */
object VrGamepadBus {

    private const val TAG = "VrGamepadBus"

    /** 返回 true 表示已消费（不再交回系统） */
    @Volatile
    var handler: ((VrGamepadAction) -> Boolean)? = null

    /** 投递一个动作；无消费者时返回 false（交回系统处理） */
    fun dispatch(action: VrGamepadAction): Boolean {
        val h = handler ?: return false
        return try {
            h(action)
        } catch (e: Exception) {
            Log.w(TAG, "手柄动作处理异常: $action -> ${e.message}")
            false
        }
    }
}

/**
 * 奇遇 VR **原生 SDK 接入点（预留）**。
 *
 * 官方 SDK：`dev-qiyu.iqiyi.com` → Native SDK（C++/OpenGL ES）/ Unity XR SDK。
 * 接入后能拿到：① 6DoF 头部姿态（替代当前手机陀螺仪的 3DoF）；② 追光手柄的 6DoF 姿态。
 *
 * ## 接入时要做什么（备忘）
 * 1. 把 SDK 的 `QiyuNativeSDK/`（头文件 + `.so`）放进 `app/src/main/cpp/` 与 `jniLibs/`；
 * 2. 参照现有华为那套（`com.example.vr.huawei.HuaweiVrActivity`）建一个**独立 Activity**，
 *    由它承载 SDK 的渲染循环（与当前 GLSurfaceView 方案并行存在，互不影响）；
 * 3. 设备识别已在 [VrGamepad.isQiyuDevice] 就绪，可据此在设置页/入口处提示；
 * 4. 手柄姿态若从 SDK 获取，转换后**投递到 [VrGamepadBus]** 即可复用现有全部上层逻辑。
 *
 * ⚠️ 当前**未接入**（无真机、无 SDK 包）——本对象只用于集中记录接入契约，
 *    避免这些信息散落在注释里丢失。
 */
object QiyuVrRuntime {

    /** 奇遇系统要求的最低版本（官方文档：奇遇3 需 QIYU OS v5.0.15+，Dream 需 v5.1.15+） */
    const val MIN_OS_VERSION_Q3 = "5.0.15"
    const val MIN_OS_VERSION_DREAM = "5.1.15"

    /** 是否已接入原生 SDK（接入后改为 true，并在此暴露能力查询） */
    const val NATIVE_SDK_INTEGRATED = false

    fun isAvailable(): Boolean = VrGamepad.isQiyuDevice()
}
