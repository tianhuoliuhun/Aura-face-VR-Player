/*
 * aura_vr_ui —— Huawei VR Glass 的 VR 内 UI 层（native GL）
 * ============================================================================
 * 为什么必须自己做：VR 模式下画面由 native **逐眼 GL 绘制**，Jetpack Compose
 * 根本不在渲染路径里 —— 2D 的控制条（PlayerControlBar）在这里一行都用不上。
 *
 * ## 本层负责什么
 *   1) 准星（射线指向的可视化）
 *   2) 控制条（4 个按钮 + 进度条）—— 图标用**几何图形**画，不依赖字体
 *   3) 射线与控制条平面的求交，得出「当前命中项」
 *   4) 把命中项暴露给调用方（输入路由据此在 select 时触发动作）
 *
 * ## 坐标系约定（上下层必须一致，写错必然错位）
 *   · 统一在**视图空间**（view space）工作：x 右、y 上、z 向后（-z 为前方）
 *   · 控制条固定挂在 z = -kBarDistance（前方 1.5m）、y = kBarY（视线下方约 12°）
 *   · 射线原点取头部（原点 (0,0,0)），方向 = 头部姿态 ⊗ 手柄姿态（由输入层算好）
 *
 * ## 为什么图标不用文字
 * native 侧没有字体。上 FreeType 渲染中文是另一个量级的工程，而控制条上只有
 * 「播放/暂停/快退/快进/更多」几个符号 —— 用三角形/竖线/箭头等几何图形画出来
 * 完全够用，且零依赖、零体积。文字（如时间码）等 UI 稳定后再单独评估。
 *
 * ## 渲染归属
 * render() 由渲染线程在**画完视频之后**调用，直接使用当前 GL 上下文，
 * 叠加在双眼画面之上。本层不管理 FBO / 不切换 framebuffer。
 */
#pragma once

// ⚠️ v2.4.16：此处**原本**有一行 `#include <openxr.h>`，但它**完全用不到** ——
//    本文件是纯 C++ 接口（VrUiItem / VrUiState / float[3] / bool），
//    正文没有任何 OpenXR 类型。
//    而它会被**核心**的 aura_vr_jni.cpp 引用 → 那一行让「主线构建」平白多出
//    一个「必须有 VR SDK」的硬依赖。移除后 standard flavor 可直接编译。

namespace aura {

/** 控制条上的可交互项 */
enum class VrUiItem {
  None = -1,
  Rewind10 = 0,   // 快退 10s
  PlayPause = 1,  // 播放 / 暂停
  Forward10 = 2,  // 快进 10s
  More = 3,       // 打开主菜单
  Count = 4,
};

/** UI 层状态（供调用方读取） */
struct VrUiState {
  bool visible = false;              // 是否显示（无操作 5 秒后自动淡出）
  VrUiItem hovered = VrUiItem::None; // 当前射线命中项
  float alpha = 0.f;                 // 当前不透明度（0~1，用于淡入淡出）
};

/** 初始化 GL 资源（着色器/缓冲）。需在 GL 线程调用。失败返回 false。 */
bool VrUiInit();

/** 释放 GL 资源。需在 GL 线程调用。 */
void VrUiShutdown();

/**
 * 每帧更新：喂入射线，更新命中项与显隐。
 *
 * @param rayDir    射线方向（视图空间，已由输入层用「头部⊗手柄」算好）。
 *                  传入 (0,0,-1) 表示「无有效手柄」，此时退化为只看视野中心。
 * @param hasAim    手柄姿态是否有效。false 时用视野中心作为准星（兜底）
 * @param selectEdge 本帧是否有 select「刚按下」
 * @return 本帧被触发的项（无触发返回 VrUiItem::None）
 */
VrUiItem VrUiUpdate(const float rayDir[3], bool hasAim, bool selectEdge,
                    float dtSeconds);

/** 当前状态（只读） */
const VrUiState& VrUiGetState();

/** 主动显示/隐藏（如播放开始时隐藏、长按呼出菜单时隐藏控制条） */
void VrUiSetVisible(bool visible);

/**
 * 渲染 UI。由渲染线程在画完视频后调用，直接复用当前 GL 上下文。
 *
 * @param eyeViewProj 该眼的 view*projection 矩阵（列主序，16 float），
 *                    用于把视图空间的 UI 投到该眼的裁剪空间
 */
void VrUiRender(const float eyeViewProj[16]);

/** 进度条显示比例（0~1），由播放器每帧喂入；<0 表示未知、不画进度条 */
void VrUiSetProgress(float ratio);

/** 当前是否处于播放状态（决定中间按钮画三角形还是两竖线） */
void VrUiSetPlaying(bool playing);

}  // namespace aura
