/*
 * aura_vr_input —— 华为 VR Glass 手柄输入接入的实现
 * 设计与绑定策略见 aura_vr_input.h
 */
#include "aura_vr_input.h"

#include <cstdio>
#include <cstring>

#include "aura_vr_log.h"
#include "aura_xr_loader.h"  // 提供 pfn_xr* / xr* 宏重定向

namespace aura {
namespace {

XrInstance g_instance = XR_NULL_HANDLE;
XrActionSet g_actionSet = XR_NULL_HANDLE;
XrAction g_actionSelect = XR_NULL_HANDLE;
// v2.0.201：手柄 aim 姿态（射线指向用）
XrAction g_actionAim = XR_NULL_HANDLE;
XrSpace g_spaceAim = XR_NULL_HANDLE;
XrPath g_pathAimRight = XR_NULL_PATH;
// v2.0.201：定 aim 姿态需要的坐标系上下文（由 session 每帧提供）
// ⚠️ 必须与头部姿态用**同一个**参考空间（LOCAL），否则「头部⊗手柄」的合成没意义
XrSpace g_spaceLocal = XR_NULL_HANDLE;
XrTime g_frameTime = 0;
bool g_attached = false;
bool g_ready = false;

XrInputState g_state{};

// 上一次的 select 状态（用于算「按下沿」）
bool g_lastSelectLeft = false;
bool g_lastSelectRight = false;

XrPath P(const char* s) {
  XrPath path = XR_NULL_PATH;
  if (pfn_xrStringToPath == nullptr) {
    return path;
  }
  XrResult r = pfn_xrStringToPath(g_instance, s, &path);
  if (XR_FAILED(r)) {
    AURA_LOGW("XrInput: xrStringToPath(%s) 失败（0x%x）", s, r);
    return XR_NULL_PATH;
  }
  return path;
}

/** 每帧刷新「当前 interaction profile」到 state，便于真机排查实际生效的 profile */
void RefreshProfile(XrSession session) {
  if (pfn_xrGetCurrentInteractionProfile == nullptr) {
    return;
  }
  // 只查右手（华为单手柄场景；双手场景两条都查更准，但日志噪音大）
  XrPath handPath = P("/user/hand/right");
  if (handPath == XR_NULL_PATH) {
    return;
  }
  XrInteractionProfileState st{XR_TYPE_INTERACTION_PROFILE_STATE};
  if (XR_FAILED(pfn_xrGetCurrentInteractionProfile(session, handPath, &st))) {
    return;
  }
  if (st.interactionProfile == XR_NULL_PATH || pfn_xrPathToString == nullptr) {
    return;
  }
  char buf[XR_MAX_PATH_LENGTH] = {0};
  uint32_t len = 0;
  if (XR_SUCCEEDED(pfn_xrPathToString(g_instance, st.interactionProfile,
                                      sizeof(buf), &len, buf))) {
    if (std::strncmp(g_state.profile, buf, sizeof(g_state.profile)) != 0) {
      std::snprintf(g_state.profile, sizeof(g_state.profile), "%s", buf);
      AURA_LOGI("XrInput: 当前 interaction profile = %s", g_state.profile);
    }
  }
}

bool ReadBool(XrSession session, XrAction action, bool* out) {
  if (pfn_xrGetActionStateBoolean == nullptr || action == XR_NULL_HANDLE) {
    *out = false;
    return false;
  }
  XrActionStateGetInfo info{XR_TYPE_ACTION_STATE_GET_INFO};
  info.action = action;
  info.subactionPath = XR_NULL_PATH;
  XrActionStateBoolean state{XR_TYPE_ACTION_STATE_BOOLEAN};
  if (XR_FAILED(pfn_xrGetActionStateBoolean(session, &info, &state))) {
    *out = false;
    return false;
  }
  // isActive 表示该 action 当前被任何绑定驱动；changedSinceLastSync 表示本帧变化
  *out = (state.isActive == XR_TRUE) && (state.currentState == XR_TRUE);
  return true;
}

}  // namespace

bool XrInputInit(XrInstance instance) {
  if (g_ready) {
    return true;
  }
  if (instance == XR_NULL_HANDLE) {
    return false;
  }
  // Action 系统是后加的扩展，旧运行时可能没有 —— 缺入口就直接降级为「无手柄输入」
  if (pfn_xrCreateActionSet == nullptr || pfn_xrCreateAction == nullptr ||
      pfn_xrSuggestInteractionProfileBindings == nullptr ||
      pfn_xrStringToPath == nullptr) {
    AURA_LOGW("XrInput: 运行时未提供 Action 系统入口，手柄输入不可用");
    return false;
  }

  g_instance = instance;

  // ---- 1) action set ----
  XrActionSetCreateInfo setInfo{XR_TYPE_ACTION_SET_CREATE_INFO};
  std::snprintf(setInfo.actionSetName, XR_MAX_ACTION_SET_NAME_SIZE, "aura_player");
  std::snprintf(setInfo.localizedActionSetName,
                XR_MAX_LOCALIZED_ACTION_SET_NAME_SIZE, "Aura Player");
  setInfo.priority = 0;
  XrResult r = pfn_xrCreateActionSet(instance, &setInfo, &g_actionSet);
  if (XR_FAILED(r)) {
    AURA_LOGE("XrInput: xrCreateActionSet 失败（0x%x）", r);
    return false;
  }

  // ---- 2) actions ----
  // 只做 select（点击）。华为 3DoF 手柄在 khr/simple_controller 下把触摸板
  // 点击映射到 select/click，这是最通用、且所有运行时都必须支持的路径。
  XrActionCreateInfo actInfo{XR_TYPE_ACTION_CREATE_INFO};
  actInfo.actionType = XR_ACTION_TYPE_BOOLEAN_INPUT;
  std::snprintf(actInfo.actionName, XR_MAX_ACTION_NAME_SIZE, "select");
  std::snprintf(actInfo.localizedActionName, XR_MAX_LOCALIZED_ACTION_NAME_SIZE,
                "Select");
  actInfo.countSubactionPaths = 0;
  actInfo.subactionPaths = nullptr;
  r = pfn_xrCreateAction(g_actionSet, &actInfo, &g_actionSelect);
  if (XR_FAILED(r)) {
    AURA_LOGE("XrInput: xrCreateAction(select) 失败（0x%x）", r);
    return false;
  }

  // ---- 2b) v2.0.201：aim 姿态动作（射线指向）----
  // ⚠️ 华为 3DoF 手柄**有旋转姿态**（IMU），所以 aim pose 能给方向；
  //    没有平移（手不会在空间里被追踪到位置），所以射线原点不能用它 ——
  //    射线原点由 UI 层定为头部，方向取「头部姿态 ⊗ 手柄姿态」（用户选定的偏角方案）。
  XrActionCreateInfo aimInfo{XR_TYPE_ACTION_CREATE_INFO};
  aimInfo.actionType = XR_ACTION_TYPE_POSE_INPUT;
  std::snprintf(aimInfo.actionName, XR_MAX_ACTION_NAME_SIZE, "aim");
  std::snprintf(aimInfo.localizedActionName, XR_MAX_LOCALIZED_ACTION_NAME_SIZE,
                "Aim");
  aimInfo.countSubactionPaths = 0;
  aimInfo.subactionPaths = nullptr;
  r = pfn_xrCreateAction(g_actionSet, &aimInfo, &g_actionAim);
  if (XR_FAILED(r)) {
    // 不致命：没有 aim 就没有射线，退化为「焦点式导航」
    AURA_LOGW("XrInput: xrCreateAction(aim) 失败（0x%x），射线指向不可用", r);
    g_actionAim = XR_NULL_HANDLE;
  }

  // ---- 3) 建议绑定（KHR simple_controller，兜底且必备）----
  XrPath profilePath = P("/interaction_profiles/khr/simple_controller");
  XrPath clickLeft = P("/user/hand/left/input/select/click");
  XrPath clickRight = P("/user/hand/right/input/select/click");
  if (profilePath == XR_NULL_PATH || clickLeft == XR_NULL_PATH ||
      clickRight == XR_NULL_PATH) {
    AURA_LOGE("XrInput: 绑定路径转换失败，手柄输入不可用");
    return false;
  }
  XrActionSuggestedBinding bindings[4] = {};
  bindings[0].action = g_actionSelect;
  bindings[0].binding = clickLeft;
  bindings[1].action = g_actionSelect;
  bindings[1].binding = clickRight;
  int bindingCount = 2;
  // v2.0.201：aim 姿态绑定（右手优先；拿不到右手再退左手 —— 见下方 fallback）
  g_pathAimRight = P("/user/hand/right/input/aim/pose");
  XrPath aimLeft = P("/user/hand/left/input/aim/pose");
  if (g_actionAim != XR_NULL_HANDLE && g_pathAimRight != XR_NULL_PATH) {
    bindings[bindingCount].action = g_actionAim;
    bindings[bindingCount].binding = g_pathAimRight;
    ++bindingCount;
  }
  if (g_actionAim != XR_NULL_HANDLE && aimLeft != XR_NULL_PATH) {
    bindings[bindingCount].action = g_actionAim;
    bindings[bindingCount].binding = aimLeft;
    ++bindingCount;
  }

  XrInteractionProfileSuggestedBinding suggested{
      XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
  suggested.interactionProfile = profilePath;
  suggested.countSuggestedBindings = static_cast<uint32_t>(bindingCount);
  suggested.suggestedBindings = bindings;
  r = pfn_xrSuggestInteractionProfileBindings(instance, &suggested);
  if (XR_FAILED(r)) {
    AURA_LOGW("XrInput: 建议绑定 simple_controller 失败（0x%x），手柄可能无响应", r);
    // 不致命：部分运行时对未安装的 profile 返回错误，但仍可用其他 profile
  }

  // ---- 3b) 华为专有 profile（尽力而为）----
  // openxr_hw.h 里定义了 XR_HUAWEI_controller_interaction —— 说明华为有专有
  // interaction profile（触摸板等 non-simple 输入只可能在其中）。
  // ⚠️ 但该宏只给了名字、没给完整 path，且不同 SDK 版本命名可能不同，
  //    故这里对**多个候选**都尝试一次；绑定不存在的 profile 只是失败告警，无副作用。
  //    真机上可通过日志里的「当前 interaction profile」确认最终生效的是哪个。
  const char* hwProfiles[] = {
      "/interaction_profiles/huawei/XR_HUAWEI_controller_interaction",
      "/interaction_profiles/huawei/controller",
  };
  for (const char* prof : hwProfiles) {
    XrPath hwPath = P(prof);
    if (hwPath == XR_NULL_PATH) {
      continue;
    }
    XrInteractionProfileSuggestedBinding hwSuggested{
        XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
    hwSuggested.interactionProfile = hwPath;
    hwSuggested.countSuggestedBindings = static_cast<uint32_t>(bindingCount);
    hwSuggested.suggestedBindings = bindings;
    XrResult hr = pfn_xrSuggestInteractionProfileBindings(instance, &hwSuggested);
    AURA_LOGI("XrInput: 尝试华为 profile %s -> %s", prof,
              XR_SUCCEEDED(hr) ? "已接受" : "不受支持（忽略）");
  }

  g_ready = true;
  AURA_LOGI("XrInput: action set 就绪（select 已绑定 khr/simple_controller 左右手）");
  return true;
}

bool XrInputAttach(XrSession session) {
  if (!g_ready || g_attached || session == XR_NULL_HANDLE) {
    return g_attached;
  }
  if (pfn_xrAttachSessionActionSets == nullptr) {
    return false;
  }
  XrSessionActionSetsAttachInfo attach{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
  attach.countActionSets = 1;
  attach.actionSets = &g_actionSet;
  XrResult r = pfn_xrAttachSessionActionSets(session, &attach);
  if (XR_FAILED(r)) {
    AURA_LOGE("XrInput: xrAttachSessionActionSets 失败（0x%x）", r);
    return false;
  }
  g_attached = true;

  // v2.0.201：aim 动作空间必须在 session 就绪后创建（xrCreateActionSpace 要求 session）
  if (g_actionAim != XR_NULL_HANDLE && pfn_xrCreateActionSpace != nullptr &&
      g_spaceAim == XR_NULL_HANDLE) {
    XrActionSpaceCreateInfo sci{XR_TYPE_ACTION_SPACE_CREATE_INFO};
    sci.action = g_actionAim;
    sci.subactionPath = XR_NULL_PATH;
    sci.poseInActionSpace.orientation.w = 1.f;  // 单位四元数（无额外偏移）
    XrResult sr = pfn_xrCreateActionSpace(session, &sci, &g_spaceAim);
    if (XR_FAILED(sr)) {
      AURA_LOGW("XrInput: xrCreateActionSpace(aim) 失败（0x%x），射线指向不可用", sr);
      g_spaceAim = XR_NULL_HANDLE;
    } else {
      AURA_LOGI("XrInput: aim 动作空间已创建（射线指向可用）");
    }
  }
  // 无论能否读到华为扩展，都打印一次手柄可用性（诊断）
  // ⚠️ 华为扩展签名是 (XrInstance, XrPath, int32_t*) —— 不是 session！
  if (pfn_xrIsControllerAvailableHW != nullptr && g_instance != XR_NULL_HANDLE) {
    XrPath right = P("/user/hand/right");
    int32_t avail = 0;
    if (right != XR_NULL_PATH &&
        XR_SUCCEEDED(pfn_xrIsControllerAvailableHW(g_instance, right, &avail))) {
      g_state.controllerAvailable = (avail != 0);
      AURA_LOGI("XrInput: 华为手柄可用 = %s（右手）",
                g_state.controllerAvailable ? "是" : "否");
    } else {
      XrPath left = P("/user/hand/left");
      int32_t availL = 0;
      if (left != XR_NULL_PATH &&
          XR_SUCCEEDED(pfn_xrIsControllerAvailableHW(g_instance, left, &availL))) {
        g_state.controllerAvailable = (availL != 0);
        AURA_LOGI("XrInput: 华为手柄可用 = %s（左手）",
                  g_state.controllerAvailable ? "是" : "否");
      }
    }
  } else {
    AURA_LOGI("XrInput: 无 xrIsControllerAvailableHW（非华为设备或旧运行时）");
  }
  return true;
}

void XrInputSync(XrSession session) {
  if (!g_attached || session == XR_NULL_HANDLE) {
    return;
  }
  if (pfn_xrSyncActions == nullptr) {
    return;
  }
  XrActiveActionSet active{g_actionSet, XR_NULL_PATH};
  XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO};
  sync.countActiveActionSets = 1;
  sync.activeActionSets = &active;
  if (XR_FAILED(pfn_xrSyncActions(session, &sync))) {
    return;
  }

  bool sel = false;
  // 左右手都读；任一按下即视为 select（简单播放器不需要区分左右）
  bool anyLeft = false, anyRight = false;
  ReadBool(session, g_actionSelect, &sel);  // 汇总态（无 subaction 时为整条 action）
  anyLeft = sel;
  anyRight = sel;

  g_state.selectLeft = anyLeft;
  g_state.selectRight = anyRight;
  g_state.selectLeftPressed = anyLeft && !g_lastSelectLeft;
  g_state.selectRightPressed = anyRight && !g_lastSelectRight;
  if (g_state.selectLeftPressed || g_state.selectRightPressed) {
    g_state.selectCount++;
    AURA_LOGI("XrInput: select 按下（累计 %llu 次）", g_state.selectCount);
  }
  g_lastSelectLeft = anyLeft;
  g_lastSelectRight = anyRight;

  RefreshProfile(session);

  // v2.0.201：读取 aim 姿态（射线方向的数据源）
  // ⚠️ 用 LOCAL 空间定位：与头部姿态（同样 LOCAL）在同一坐标系下，
  //    这样「头部姿态 ⊗ 手柄姿态」的合成才有意义。
  if (g_spaceAim != XR_NULL_HANDLE && pfn_xrLocateSpace != nullptr) {
    XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};
    XrResult lr = pfn_xrLocateSpace(g_spaceAim, g_spaceLocal, g_frameTime,
                                    &loc);
    const XrSpaceLocationFlags need =
        XR_SPACE_LOCATION_ORIENTATION_VALID_BIT |
        XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT;
    if (XR_SUCCEEDED(lr) && (loc.locationFlags & need) == need) {
      g_state.aimValid = true;
      g_state.aimOrientation[0] = loc.pose.orientation.x;
      g_state.aimOrientation[1] = loc.pose.orientation.y;
      g_state.aimOrientation[2] = loc.pose.orientation.z;
      g_state.aimOrientation[3] = loc.pose.orientation.w;
      g_state.aimPosition[0] = loc.pose.position.x;
      g_state.aimPosition[1] = loc.pose.position.y;
      g_state.aimPosition[2] = loc.pose.position.z;
    } else {
      g_state.aimValid = false;
    }
  }
}

const XrInputState& XrInputGet() { return g_state; }

void XrInputSetFrameContext(XrSpace localSpace, XrTime frameTime) {
  g_spaceLocal = localSpace;
  g_frameTime = frameTime;
}

void XrInputSetHeadOrientation(const XrQuaternionf& o) {
  g_state.headOrientation[0] = o.x;
  g_state.headOrientation[1] = o.y;
  g_state.headOrientation[2] = o.z;
  g_state.headOrientation[3] = o.w;
  g_state.headValid = true;
}

bool XrInputReady() { return g_ready; }

void XrInputShutdown() {
  if (g_actionSet != XR_NULL_HANDLE && pfn_xrDestroyActionSet != nullptr) {
    pfn_xrDestroyActionSet(g_actionSet);
  }
  g_actionSet = XR_NULL_HANDLE;
  g_actionSelect = XR_NULL_HANDLE;
  g_instance = XR_NULL_HANDLE;
  g_attached = false;
  g_ready = false;
  g_lastSelectLeft = false;
  g_lastSelectRight = false;
}

}  // namespace aura
