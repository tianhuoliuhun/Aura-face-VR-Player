/*
 * aura_vr_input —— 华为 VR Glass 手柄（3DoF）输入接入
 * ============================================================================
 * 背景：华为手柄没有走 Android 的 KeyEvent/MotionEvent，而是**标准 OpenXR Action
 * 系统**上报（openxr_hw.h 只提供「手柄是否可用 / 手柄类型」的探测接口，不负责按键）。
 *
 * 因此本模块用 OpenXR Action 系统的标准流程：
 *   1) xrCreateActionSet            创建动作集
 *   2) xrCreateAction               创建动作（select 点击 等）
 *   3) xrSuggestInteractionProfileBindings  建议绑定（关键：哪条 input path 绑哪个 action）
 *   4) xrAttachSessionActionSets    会话就绪后附加
 *   5) 每帧 xrSyncActions → xrGetActionState*  读取状态
 *
 * ## 绑定策略（务实取舍）
 * 只绑定 **KHR simple_controller** profile —— 它是 OpenXR 规定所有运行时都必须支持的
 * 兜底 profile，提供 `select`（点击）与 `menu`。华为 3DoF 手柄在该 profile 下把
 * 触摸板点击映射为 `select/click`。
 * ⚠️ 触摸板的**滑动**（continuous 2D）不在 simple_controller 里，需要厂商专有 profile。
 *    本模块已留出 `touchpad` 动作与绑定位置，但**尚未确认华为的专有 profile 名**
 *    （候选：/interaction_profiles/huawei/controller），故暂不绑定，避免无效 API 调用。
 *    待真机抓到实际 profile 名后再补 —— 见 GetCurrentInteractionProfile() 的日志。
 *
 * ## 与业务层的接口
 * 状态是「每帧刷新的只读快照」，业务层（Kotlin）通过 JNI 轮询
 * aura_vr_input_poll() 拿到打包好的位掩码/数值，自己决定映射成什么操作
 * （播放暂停 / 拖动进度 / 音量…）。本模块**不**做「点击 = 播放暂停」这类业务判断。
 */
#pragma once

#include <openxr.h>

namespace aura {

/** 手柄状态快照（每帧更新） */
struct XrInputState {
  /** 本帧是否有左/右手的 select（点击/触摸板按下） */
  bool selectLeft = false;
  bool selectRight = false;
  /** select 的「按下沿」（本帧刚按下，用于触发一次性动作） */
  bool selectLeftPressed = false;
  bool selectRightPressed = false;
  /** 控制器是否可用（来自华为扩展 xrIsControllerAvailableHW，不可用时为 false） */
  bool controllerAvailable = false;
  /** 当前生效的 interaction profile 名（诊断；空 = 尚未同步到） */
  char profile[128] = {0};
  /** 自启动以来收到的 select 次数（诊断，用于确认链路是否通） */
  unsigned long long selectCount = 0;
};

/** 初始化动作集（需 instance 已创建）；可重复调用，幂等 */
bool XrInputInit(XrInstance instance);

/** 会话就绪后附加动作集（xeAttachSessionActionSets）；可重复调用，幂等 */
bool XrInputAttach(XrSession session);

/** 每帧调用：同步并刷新内部状态快照 */
void XrInputSync(XrSession session);

/** 取当前状态快照（只读） */
const XrInputState& XrInputGet();

/** 释放（销毁 action set；可重复调用） */
void XrInputShutdown();

/** 本模块是否已成功初始化（动作集创建成功） */
bool XrInputReady();

}  // namespace aura
