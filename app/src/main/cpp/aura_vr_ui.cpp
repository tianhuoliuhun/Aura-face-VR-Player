/*
 * aura_vr_ui —— VR 内 UI 层的实现
 * 设计与坐标系约定见 aura_vr_ui.h
 */
#include "aura_vr_ui.h"

#include <GLES3/gl3.h>

#include <cmath>
#include <cstring>

#include "aura_vr_log.h"

namespace aura {
namespace {

// ---------------------------------------------------------------------------
// 几何布局（视图空间；x 右、y 上、-z 前）
// ---------------------------------------------------------------------------
constexpr float kBarDistance = -1.5f;   // 控制条所在平面（前方 1.5m）
constexpr float kBarY = -0.32f;         // 视线下方约 12°（atan(0.32/1.5)≈12°）
constexpr float kBarW = 1.20f;          // 控制条宽
constexpr float kBarH = 0.16f;          // 控制条高
constexpr float kBtnSize = 0.11f;       // 按钮边长
constexpr float kCrossSize = 0.006f;    // 准星半径（视野角约 0.23°）
constexpr float kAutoHideSec = 5.0f;    // 无操作自动淡出
constexpr float kFadeSec = 0.25f;       // 淡入淡出时长

constexpr float kBgR = 0.05f, kBgG = 0.06f, kBGB = 0.09f;      // 控制条底
constexpr float kHiR = 0.25f, kHiG = 0.88f, kHiB = 0.82f;      // 高亮（青）
constexpr float kFgR = 0.90f, kFgG = 0.93f, kFgB = 0.98f;      // 图标前景

// ---------------------------------------------------------------------------
// GL 资源
// ---------------------------------------------------------------------------
GLuint g_prog = 0, g_vbo = 0, g_vao = 0;
GLint g_locMVP = -1, g_locColor = -1, g_locAlpha = -1;
bool g_glReady = false;

// 顶点缓冲：每帧按需填充（三角形列表，每顶点 2 float）
constexpr int kMaxVerts = 4096;
float g_verts[kMaxVerts * 2];
int g_vertCount = 0;

// ---------------------------------------------------------------------------
// 状态
// ---------------------------------------------------------------------------
VrUiState g_state{};
float g_idleSec = 0.f;
VrUiItem g_pressedThisFrame = VrUiItem::None;
float g_progress = -1.f;
bool g_playing = false;

// ---------------------------------------------------------------------------
// 着色器
// ---------------------------------------------------------------------------
const char* kVS = R"GLSL(
#version 300 es
layout(location = 0) in vec2 aPos;
uniform mat4 uMVP;
void main() {
    gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
}
)GLSL";

const char* kFS = R"GLSL(
#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 fragColor;
void main() {
    fragColor = uColor;
}
)GLSL";

GLuint Compile(GLenum type, const char* src) {
  GLuint s = glCreateShader(type);
  glShaderSource(s, 1, &src, nullptr);
  glCompileShader(s);
  GLint ok = GL_FALSE;
  glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
  if (ok != GL_TRUE) {
    char log[512] = {0};
    glGetShaderInfoLog(s, sizeof(log) - 1, nullptr, log);
    AURA_LOGE("VrUi: 着色器编译失败: %s", log);
    glDeleteShader(s);
    return 0;
  }
  return s;
}

// ---------------------------------------------------------------------------
// 顶点累积（全部按视图空间坐标喂入，再由 eyeViewProj 投出去）
// ---------------------------------------------------------------------------
void ResetVerts() { g_vertCount = 0; }

void PushVert(float x, float y) {
  if (g_vertCount >= kMaxVerts) return;
  g_verts[g_vertCount * 2 + 0] = x;
  g_verts[g_vertCount * 2 + 1] = y;
  ++g_vertCount;
}

/** 轴对齐矩形（视图空间，z 固定） */
void PushRect(float cx, float cy, float w, float h, float z) {
  const float x0 = cx - w * 0.5f, x1 = cx + w * 0.5f;
  const float y0 = cy - h * 0.5f, y1 = cy + h * 0.5f;
  PushVert(x0, y0); PushVert(x1, y0); PushVert(x1, y1);
  PushVert(x0, y0); PushVert(x1, y1); PushVert(x0, y1);
  (void)z;  // z 由 uMVP 前的视图空间常量决定（本层所有 UI 共面）
}

/** 三角形（三点） */
void PushTri(float x0, float y0, float x1, float y1, float x2, float y2) {
  PushVert(x0, y0); PushVert(x1, y1); PushVert(x2, y2);
}

void Flush(const float mvp[16], float r, float g, float b, float a) {
  if (g_vertCount == 0) return;
  glUseProgram(g_prog);
  glUniformMatrix4fv(g_locMVP, 1, GL_FALSE, mvp);
  glUniform4f(g_locColor, r, g, b, a);
  glBindVertexArray(g_vao);
  glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
  glBufferSubData(GL_ARRAY_BUFFER, 0,
                  static_cast<GLsizeiptr>(g_vertCount * 2 * sizeof(float)),
                  g_verts);
  glDrawArrays(GL_TRIANGLES, 0, g_vertCount);
  glBindVertexArray(0);
  ResetVerts();
}

// ---------------------------------------------------------------------------
// 布局计算
// ---------------------------------------------------------------------------
/** 第 i 个按钮的中心 x（均匀分布在控制条内） */
float ButtonX(int i) {
  const float step = kBarW / static_cast<float>(static_cast<int>(VrUiItem::Count));
  return -kBarW * 0.5f + step * (static_cast<float>(i) + 0.5f);
}

/**
 * 射线与控制条平面求交 → 命中项。
 * 射线在视图空间：原点 (0,0,0)、方向 dir。平面 z = kBarDistance。
 * ⚠️ 方向 z 必须为负（朝前），否则射线朝后、不该命中。
 */
VrUiItem HitTest(const float dir[3]) {
  if (dir[2] >= -1e-4f) {
    return VrUiItem::None;
  }
  const float t = kBarDistance / dir[2];
  if (t <= 0.f) {
    return VrUiItem::None;
  }
  const float hx = dir[0] * t;
  const float hy = dir[1] * t;
  // 先看是否落在控制条矩形内
  if (std::fabs(hx) > kBarW * 0.5f || std::fabs(hy - kBarY) > kBarH * 0.5f) {
    return VrUiItem::None;
  }
  for (int i = 0; i < static_cast<int>(VrUiItem::Count); ++i) {
    if (std::fabs(hx - ButtonX(i)) <= kBtnSize * 0.5f) {
      return static_cast<VrUiItem>(i);
    }
  }
  return VrUiItem::None;
}

// ---------------------------------------------------------------------------
// 图标绘制（几何图形，不用字体）
// ---------------------------------------------------------------------------
void DrawPlayTriangle(float cx, float cy, float s, bool play) {
  if (play) {
    // ▶ 播放：右向三角形
    PushTri(cx - s * 0.35f, cy - s * 0.5f,
            cx + s * 0.50f, cy,
            cx - s * 0.35f, cy + s * 0.5f);
  } else {
    // ⏸ 暂停：两条竖线
    const float bw = s * 0.16f;
    PushRect(cx - s * 0.26f, cy, bw, s, 0.f);
    PushRect(cx + s * 0.26f, cy, bw, s, 0.f);
  }
}

void DrawSkip(float cx, float cy, float s, bool forward) {
  const float dir = forward ? 1.f : -1.f;
  // 三角形 + 一条竖线（快进/快退的标准图标形态）
  PushTri(cx - dir * s * 0.15f, cy - s * 0.42f,
          cx + dir * s * 0.38f, cy,
          cx - dir * s * 0.15f, cy + s * 0.42f);
  const float bw = s * 0.13f;
  PushRect(cx + dir * s * 0.45f, cy, bw, s * 0.9f, 0.f);
}

void DrawMore(float cx, float cy, float s) {
  // ⋯ 更多：三个圆点用短粗矩形近似
  const float r = s * 0.11f;
  for (int i = -1; i <= 1; ++i) {
    PushRect(cx + static_cast<float>(i) * s * 0.30f, cy, r, r, 0.f);
  }
}

}  // namespace

// ---------------------------------------------------------------------------
// 对外接口
// ---------------------------------------------------------------------------
bool VrUiInit() {
  if (g_glReady) {
    return true;
  }
  GLuint vs = Compile(GL_VERTEX_SHADER, kVS);
  GLuint fs = Compile(GL_FRAGMENT_SHADER, kFS);
  if (vs == 0 || fs == 0) {
    return false;
  }
  g_prog = glCreateProgram();
  glAttachShader(g_prog, vs);
  glAttachShader(g_prog, fs);
  glLinkProgram(g_prog);
  GLint ok = GL_FALSE;
  glGetProgramiv(g_prog, GL_LINK_STATUS, &ok);
  glDeleteShader(vs);
  glDeleteShader(fs);
  if (ok != GL_TRUE) {
    char log[512] = {0};
    glGetProgramInfoLog(g_prog, sizeof(log) - 1, nullptr, log);
    AURA_LOGE("VrUi: 程序链接失败: %s", log);
    glDeleteProgram(g_prog);
    g_prog = 0;
    return false;
  }
  g_locMVP = glGetUniformLocation(g_prog, "uMVP");
  g_locColor = glGetUniformLocation(g_prog, "uColor");

  glGenVertexArrays(1, &g_vao);
  glGenBuffers(1, &g_vbo);
  glBindVertexArray(g_vao);
  glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
  glBufferData(GL_ARRAY_BUFFER,
               static_cast<GLsizeiptr>(kMaxVerts * 2 * sizeof(float)),
               nullptr, GL_DYNAMIC_DRAW);
  glEnableVertexAttribArray(0);
  glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 2 * sizeof(float), nullptr);
  glBindVertexArray(0);

  g_glReady = true;
  AURA_LOGI("VrUi: GL 资源就绪（控制条 %dx%d 按钮 + 准星）",
            static_cast<int>(VrUiItem::Count), 1);
  return true;
}

void VrUiShutdown() {
  if (g_vbo != 0) { glDeleteBuffers(1, &g_vbo); g_vbo = 0; }
  if (g_vao != 0) { glDeleteVertexArrays(1, &g_vao); g_vao = 0; }
  if (g_prog != 0) { glDeleteProgram(g_prog); g_prog = 0; }
  g_glReady = false;
}

VrUiItem VrUiUpdate(const float rayDir[3], bool hasAim, bool selectEdge,
                    float dtSeconds) {
  g_pressedThisFrame = VrUiItem::None;

  // 射线方向：有手柄姿态就用它；没有则退化为「视野中心」（正前方）
  float dir[3] = {0.f, 0.f, -1.f};
  if (hasAim && rayDir != nullptr) {
    dir[0] = rayDir[0];
    dir[1] = rayDir[1];
    dir[2] = rayDir[2];
    // 归一化（输入应为单位向量，这里兜底）
    const float len = std::sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
    if (len > 1e-6f) {
      dir[0] /= len; dir[1] /= len; dir[2] /= len;
    }
  } else {
    // 兜底：准星在视野中心，此时「瞄准」控制条正下方 → 直接按 y 偏移命中
    // （用 0.32/1.5 的比例把射线指向控制条位置，让中心准星能落在播放按钮上）
    dir[1] = kBarY / (-kBarDistance);
    const float len = std::sqrt(dir[1] * dir[1] + 1.f);
    dir[1] /= len;
    dir[2] = -1.f / len;
  }

  g_state.visible = (g_idleSec < kAutoHideSec);

  // 命中判定
  g_state.hovered = g_state.visible ? HitTest(dir) : VrUiItem::None;

  // 单击 → 触发（返回给调用方执行）
  if (selectEdge && g_state.visible && g_state.hovered != VrUiItem::None) {
    g_pressedThisFrame = g_state.hovered;
    g_idleSec = 0.f;  // 有操作 → 重置空闲计时
    AURA_LOGI("VrUi: 触发控制条项 %d", static_cast<int>(g_pressedThisFrame));
  } else if (selectEdge && !g_state.visible) {
    // 无 UI 时单击 → 呼出控制条（与设计文档 §2 一致）
    VrUiSetVisible(true);
  }

  // 空闲计时 & 淡入淡出
  const bool active = g_state.visible;
  if (active) {
    g_idleSec += (dtSeconds > 0.f ? dtSeconds : 0.f);
  }
  const float target = active ? 1.f : 0.f;
  const float step = (kFadeSec > 0.f) ? (dtSeconds / kFadeSec) : 1.f;
  if (g_state.alpha < target) {
    g_state.alpha = std::fmin(target, g_state.alpha + step);
  } else if (g_state.alpha > target) {
    g_state.alpha = std::fmax(target, g_state.alpha - step);
  }

  return g_pressedThisFrame;
}

const VrUiState& VrUiGetState() { return g_state; }

void VrUiSetVisible(bool visible) {
  if (visible) {
    g_idleSec = 0.f;
  } else {
    g_idleSec = kAutoHideSec + 1.f;
  }
}

void VrUiSetProgress(float ratio) { g_progress = ratio; }
void VrUiSetPlaying(bool playing) { g_playing = playing; }

void VrUiRender(const float eyeViewProj[16]) {
  if (!g_glReady || g_state.alpha <= 0.001f) {
    return;
  }
  const float a = g_state.alpha;

  // ---- 1) 准星 ----
  if (g_state.visible) {
    // 命中时准星放大 + 变青（悬停反馈）
    const bool hit = (g_state.hovered != VrUiItem::None);
    const float cs = kCrossSize * (hit ? 1.8f : 1.0f);
    // 准星画在射线与控制条平面的交点附近；无手柄时即在视野中心偏下
    const float cz = kBarDistance;
    const float cy = kBarY * (hit ? 1.f : 1.f);
    PushRect(0.f, cy, cs, cs, cz);
    if (hit) {
      Flush(eyeViewProj, kHiR, kHiG, kHiB, a);
    } else {
      Flush(eyeViewProj, kFgR, kFgG, kFgB, a * 0.85f);
    }
  }

  // ---- 2) 控制条底板 ----
  PushRect(0.f, kBarY, kBarW + 0.06f, kBarH + 0.05f, kBarDistance);
  Flush(eyeViewProj, kBgR, kBgG, kBGB, a * 0.82f);

  // ---- 3) 进度条（细长，贯穿控制条宽） ----
  if (g_progress >= 0.f) {
    const float pz = kBarDistance;
    const float py = kBarY - kBarH * 0.5f - 0.028f;
    // 轨道
    PushRect(0.f, py, kBarW, 0.008f, pz);
    Flush(eyeViewProj, 0.30f, 0.33f, 0.38f, a * 0.9f);
    // 已播部分
    const float w = kBarW * (g_progress < 0.f ? 0.f : (g_progress > 1.f ? 1.f : g_progress));
    PushRect(-kBarW * 0.5f + w * 0.5f, py, w, 0.008f, pz);
    Flush(eyeViewProj, kHiR, kHiG, kHiB, a);
  }

  // ---- 4) 四个按钮的图标 ----
  // 高亮背景先画（避免盖住图标）
  if (g_state.hovered != VrUiItem::None) {
    const int hi = static_cast<int>(g_state.hovered);
    PushRect(ButtonX(hi), kBarY, kBtnSize + 0.02f, kBtnSize + 0.02f, kBarDistance);
    Flush(eyeViewProj, kHiR, kHiG, kHiB, a * 0.30f);
  }
  const float s = kBtnSize * 0.62f;
  // 快退
  DrawSkip(ButtonX(0), kBarY, s, false);
  Flush(eyeViewProj, kFgR, kFgG, kFgB, a);
  // 播放/暂停
  DrawPlayTriangle(ButtonX(1), kBarY, s, g_playing);
  Flush(eyeViewProj, kFgR, kFgG, kFgB, a);
  // 快进
  DrawSkip(ButtonX(2), kBarY, s, true);
  Flush(eyeViewProj, kFgR, kFgG, kFgB, a);
  // 更多
  DrawMore(ButtonX(3), kBarY, s);
  Flush(eyeViewProj, kFgR, kFgG, kFgB, a);
}

}  // namespace aura
