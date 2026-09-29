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

  // ===== v2.0.201：射线指向所需的手柄姿态 =====
  /**
   * 手柄 aim 姿态是否有效（本帧 xrLocateSpace 成功且位姿被 tracked）。
   * ⚠️ 华为 3DoF 手柄只有**旋转**没有平移，所以只用它的 orientation；
   *    position 即使有值也不可靠（可能是 runtime 编的固定值）。
   */
  bool aimValid = false;
  /** aim 姿态的方向四元数（x,y,z,w）。世界空间；算射线方向时取它的前向 -Z 轴 */
  float aimOrientation[4] = {0.f, 0.f, 0.f, 1.f};
  /** aim 姿态的位置（诊断用；3DoF 下不可信，**不要**拿它当射线原点） */
  float aimPosition[3] = {0.f, 0.f, 0.f};

  /**
   * 本帧的头部姿态四元数（由 session 侧的 xrLocateViews 结果填入，x,y,z,w）。
   * 用于按「手柄相对头部的偏角」生成射线：
   *     射线方向 = 头部姿态 ⊗ 手柄姿态
   * 这样手柄"往前指"时射线也朝正前方，符合直觉；
   * 若直接用世界空间的手柄姿态，转头后射线会把 UI 甩到视野外。
   */
  float headOrientation[4] = {0.f, 0.f, 0.f, 1.f};
  bool headValid = false;
};

/** 初始化动作集（需 instance 已创建）；可重复调用，幂等 */
bool XrInputInit(XrInstance instance);

/** 会话就绪后附加动作集（xeAttachSessionActionSets）；可重复调用，幂等 */
bool XrInputAttach(XrSession session);

/** 每帧调用：同步并刷新内部状态快照 */
void XrInputSync(XrSession session);

/**
 * v2.0.201：告诉输入模块本帧的坐标系上下文（射线姿态定位需要）。
 *
 * @param localSpace 会话的 LOCAL 参考空间 —— ⚠️ 必须与头部姿态用同一个，
 *                   否则「头部姿态 ⊗ 手柄姿态」的合成会因坐标系不一致而错乱
 * @param frameTime  本帧的 predictedDisplayTime（与 xrLocateViews 用同一个）
 */
void XrInputSetFrameContext(XrSpace localSpace, XrTime frameTime);

/** v2.0.201：由 session 在 xrLocateViews 之后填入头部姿态（用于射线合成） */
void XrInputSetHeadOrientation(const XrQuaternionf& orientation);

/** 取当前状态快照（只读） */
const XrInputState& XrInputGet();

/** 释放（销毁 action set；可重复调用） */
void XrInputShutdown();

/** 本模块是否已成功初始化（动作集创建成功） */
bool XrInputReady();

}  // namespace aura
