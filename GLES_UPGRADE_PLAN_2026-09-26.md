# GLES 版本升级评估与改动清单（GLES2 → GLES3.2）

> 文档性质：**只做评估与规划，未修改任何代码。**
> 生成日期：2026-09-26
> 目标：明确从当前的 GLES2 基线升级到 GLES3.2 需要改动哪些文件、哪些行、有什么风险。

---

## ⚠️ 2026-09-26 更新：阶段 1 已实施，阶段 2 决定不做

**实际只做了「阶段 1」（一行版本号），阶段 2（shader 迁移）经评估后决定取消。**

### 已实施（v2.0.176）
| 改动 | 位置 |
|---|---|
| `setEGLContextClientVersion(2)` → `(3)` | `VRGLSurfaceView.kt:42` |
| 新增 `GL_VERSION` / `VENDOR` 启动日志 | `VRGLRenderer.kt:onSurfaceCreated` |

### 真机实测结果（MuMu / Adreno 640）
```
VRGLRenderer: GL_VERSION = OpenGL ES 3.2 v334 R | VENDOR = Qualcomm
SurfaceFlinger: GLES: Qualcomm, Adreno (TM) 640, OpenGL ES 3.2 v334 R
```
| 验证项 | 结果 |
|---|---|
| 上下文版本 | **拿到 ES 3.2**（不是只有 3.0）✅ |
| shader 编译（6 个，ESSL 100） | 全部通过，无 error ✅ |
| GL error / FATAL | 无 ✅ |
| MediaPipe 人脸引擎初始化 | 成功 ✅ |
| Adreno 640（最挑剔的驱动之一） | 兼容 ✅ |

> **关键结论 1**：驱动在 ES3 上下文下**默认就暴露 3.2** → §2.3.2 的
> `EGL_CONTEXT_MAJOR/MINOR_VERSION` 精确指定**完全不需要做**（原本也只是「可选」）。
>
> **关键结论 2**：Adreno 640 上 ESSL 100 shader 在 ES3 上下文里编译无问题 →
> 证实了「ES3 向后兼容 GLSL ES 1.00」这个前提。

### 为什么阶段 2（shader 迁移）决定不做
| 考量 | 结论 |
|---|---|
| 收益 | **零**。本项目 shader 是单 quad / 球面网格，顶点数极少，VAO 省不出东西；美颜全在 fragment shader 做，用不到 compute / UBO / instancing |
| 风险 | **非零**。迁移必须改 OES 扩展名为 `_essl3`，漏改 = 视频路径黑屏；且 `varying` 方向、`#version` 与 `#define` 拼接顺序都是易错点 |
| 结论 | 典型的**负期望改动** —— 画面一模一样、性能基本一样，却引入黑屏级风险 |

→ **阶段 2 / 阶段 3 均不再计划实施。** 下方 §2.1 的 shader 改动清单保留作为「万一将来需要」的参考。

### 唯一值得留意的副作用
ES3 下 fragment `highp` 从「可选」变为「强制」。主 shader 本就写了 `precision highp float;`，
在 ES2 设备上可能被驱动降级为 mediump 运行，切 ES3 后会真正使用 highp
→ 美颜算法的数值表现**可能有肉眼可见的细微变化**（边缘更锐利等）。
→ **需在真机上扫一眼美颜效果**，这是本次改动唯一的功能性观察点。

---

## 0. 结论速览

| 评估项 | 结论 |
|---|---|
| 升级难度 | **中低** —— 代码结构非常干净，没有历史包袱 |
| 涉及文件 | **仅 3 个**（1 个 Kotlin 渲染器 + 1 个 Kotlin SurfaceView + 1 个 C++ 会话） |
| 最大工作量 | shader 迁移到 `#version 300 es`（6 个 shader，约 30 处关键字） |
| 最大风险 | **OES 外部纹理扩展换名** + **`#version` 与 `#define` 的顺序冲突** |
| 最省事的部分 | 279 处 `GLES20.*` **完全不用改**；C++ 侧**已经是 GLES3 写法** |
| 是否必须升级 | 非必须。GLES3 上下文**向后兼容 GLSL ES 1.00**，当前 shader 在 ES3 下能编译。升级的真正收益是「用上 GLES3.2 特性 + 与华为路径统一」 |

### 当前状态实测数据

| 指标 | 当前值 |
|---|---|
| Kotlin 侧 `GLES20.` 调用 | **279 处**，全部在 `VRGLRenderer.kt` 一个文件 |
| Kotlin 侧 `GLES30./31./32.` 调用 | **0 处** |
| shader 数量 | **6 个**（4 个命名常量 + 2 个内联局部变量） |
| shader 独立资源文件（`.glsl`/`.vert`/`.frag`） | **0 个**（全部内联在 Kotlin 字符串里） |
| C++ 侧 GLES 头 | `GLES3/gl3.h`（**已是 ES3**） |
| C++ 侧链接库 | `GLESv3`（**已正确**，注释写明「华为要求 GLES 3.2」） |
| C++ 侧专属 GLES3 函数 | `glGenFramebuffers` / `glFramebufferTexture2D` 等**已在使用** |

---

## 1. 现状盘点

### 1.1 两条渲染路径的 GLES 版本不一致 ⚠️

这是当前最值得先修的问题——**同一个 `VRGLRenderer` 跑在两个不同版本的上下文中**：

| 路径 | 文件 | 行 | 上下文版本 |
|---|---|---|---|
| 主路径（手机普通模式 / 分屏 VR） | `VRGLSurfaceView.kt` | **38** | `setEGLContextClientVersion(2)` ← **ES2** |
| 华为 VR 路径 | `huawei/HuaweiVrActivity.kt` | **151** | `setEGLContextClientVersion(3)` ← ES3 |

**影响**：
- 同一个 shader 源码需要在 ES2 和 ES3 两种上下文里都能编译 → 目前能工作是因为 shader 用的是最保守的 ESSL 1.00 语法
- 一旦把 shader 迁到 `#version 300 es`，**主路径会立刻全线崩溃**（`#version 300 es` 在 ES2 上下文里是编译错误）
- 因此 **shader 升级必须先于 / 同时于 `setEGLContextClientVersion(2)` → `(3)`**，两者是绑定关系，不能拆开做

### 1.2 EGL 配置（无需大改）

| 位置 | 配置 | 评价 |
|---|---|---|
| `VRGLSurfaceView.kt:39` | `setEGLConfigChooser(8, 8, 8, 8, 16, 0)` | 可保留 |
| `HuaweiVrActivity.kt:152` | `setEGLConfigChooser(8, 8, 8, 8, 16, 0)` | 可保留 |
| C++ `aura_vr_session.cpp:577-587` | `EGL_OPENGL_ES3_BIT` + RGBA8 + depth24 + samples 0 | **已是 ES3**，且有降级 |

**好消息**：全项目 **没有任何自定义 `EGLContextFactory` / `EGLConfigChooser` / `EGLWindowSurfaceFactory`**，
全部使用 `GLSurfaceView` 的系统默认实现。
→ 改版本号只需改 `setEGLContextClientVersion(n)` 的参数，**没有隐藏的 EGL 属性需要手工调整**。

### 1.3 全部 shader 清单（6 个，全内联）

| # | 变量名 | 文件:行 | 类型 | 有无 `#version` |
|---|---|---|---|---|
| 1 | `vertexShaderCode` | `VRGLRenderer.kt:395`（内容 396-403） | vertex | ❌ 无 |
| 2 | `fallbackVertexShaderCode` | `VRGLRenderer.kt:408`（内容 409-415） | vertex | ❌ 无 |
| 3 | `fallbackFragmentShaderCode` | `VRGLRenderer.kt:418`（内容 419-424） | fragment | ❌ 无 |
| 4 | `fragmentShaderCode` | `VRGLRenderer.kt:453`（内容 454-973，**约 520 行**） | fragment | ❌ 无 |
| 5 | `vs`（局部） | `VRGLRenderer.kt:1937` | vertex | ❌ 无 |
| 6 | `fs`（局部） | `VRGLRenderer.kt:1939` | fragment | ❌ 无 |

> 全部无 `#version` → 按 ESSL **1.00** 语义编译。
> 第 5、6 个是 `ensureBlitProgram()` 里的 blit（纹理拷贝）shader，单行写法，最容易改。

---

## 2. 改动清单（按文件）

### 2.1 `VRGLRenderer.kt` —— shader 语法迁移（主要工作量）

#### 2.1.1 补 `#version 300 es`（6 处）

⚠️ **必须注意顺序陷阱**（见 §3.1）：
```kotlin
// 现状（第 979 行）
val fCode = if (videoVariant) "#define VIDEO_OES 1\n" + fragmentShaderCode else fragmentShaderCode
```
`#version` 必须是 shader 的**第一条有效指令**，而这里已经把 `#define` 拼在最前面了。
→ 迁移后拼接顺序必须是：`#version 300 es` → `#define VIDEO_OES 1` → 其余源码。
→ 建议直接把 `#version 300 es` 写进 `fragmentShaderCode` 字符串的开头，然后**改成**：
```kotlin
val fCode = if (videoVariant) {
    fragmentShaderCode.replaceFirst("#version 300 es", "#version 300 es\n#define VIDEO_OES 1")
} else fragmentShaderCode
```
（或更稳妥：把宏定义也写进字符串模板，用 Kotlin 插值处理。）

#### 2.1.2 `attribute` → `in`（6 处）

| 行 | 原文 | 改为 |
|---|---|---|
| 397 | `attribute vec4 aPosition;` | `in vec4 aPosition;` |
| 398 | `attribute vec2 aTextureCoord;` | `in vec2 aTextureCoord;` |
| 409 | `attribute vec4 aPosition;` | `in vec4 aPosition;` |
| 410 | `attribute vec2 aTextureCoord;` | `in vec2 aTextureCoord;` |
| 1937 | `attribute vec2 aPos;` | `in vec2 aPos;` |
| 1937 | `attribute vec2 aTex;` | `in vec2 aTex;` |

> 可选优化：GLES3 支持 `layout(location = N) in vec4 aPosition;`，
> 这样 Kotlin 侧就不用 `glGetAttribLocation` 查询了。但**建议先不做**，
> 保持 `glGetAttribLocation` 逻辑不变可降低回归风险。

#### 2.1.3 `varying` → `out`（vertex 端）/ `in`（fragment 端）（6 处）

| 行 | 所属 shader | 方向 | 改为 |
|---|---|---|---|
| 399 | `vertexShaderCode` | vertex 输出 | `out vec2 vTextureCoord;` |
| 411 | `fallbackVertexShaderCode` | vertex 输出 | `out vec2 vTextureCoord;` |
| 420 | `fallbackFragmentShaderCode` | fragment 输入 | `in vec2 vTextureCoord;` |
| 459 | `fragmentShaderCode` | fragment 输入 | `in vec2 vTextureCoord;` |
| 1937 | blit vertex | vertex 输出 | `out vec2 vTex;` |
| 1939 | blit fragment | fragment 输入 | `in vec2 vTex;` |

> ⚠️ **同名 `varying` 在两个 shader 里方向相反**，批量替换会出错，必须逐处按所属 shader 判断。

#### 2.1.4 `texture2D(` → `texture(`（17 行 / 约 24 次调用）

| 行 | 出现次数 |
|---|---|
| 423 | 1（fallback fragment） |
| 537 | 1 |
| 538 | 1 |
| 790 | 1 |
| 792 | 1 |
| **808–815** | **每行 2 次**（三元表达式两分支）→ 共 16 次 |
| 1940 | 1（blit fragment） |

> 机械替换即可，`texture()` 在 GLES3 中是重载函数，对 `sampler2D` 和（开启扩展后的）`samplerExternalOES` 都适用。

#### 2.1.5 `gl_FragColor` → 声明 `out vec4`（3 个 fragment shader）

| 行 | 所属 | 处理 |
|---|---|---|
| 423 | `fallbackFragmentShaderCode` | 加 `out vec4 fragColor;`，`gl_FragColor` → `fragColor` |
| 972 | `fragmentShaderCode`（主 shader，`main()` 末尾） | 加 `out vec4 fragColor;`，`gl_FragColor` → `fragColor` |
| 1940 | blit `fs` | 加 `out vec4 fragColor;`，`gl_FragColor` → `fragColor` |

> 建议用 `layout(location = 0) out vec4 fragColor;` 显式绑定点，更明确。
> 注意主 shader 里 `gl_FragColor = color;` 只出现在末尾一处（中间都是操作局部变量 `color`），**不要误改**中间的同名局部变量。

#### 2.1.6 ⚠️ OES 扩展换名（**最容易漏、最致命**）

| 行 | 现状 | 必须改为 |
|---|---|---|
| 455 | `#extension GL_OES_EGL_image_external : enable` | `#extension GL_OES_EGL_image_external_essl3 : require` |
| 464 | `uniform samplerExternalOES uSamplerVideo;` | **类型名不变**（仍是 `samplerExternalOES`） |

**原因**：`GL_OES_EGL_image_external` 是 **ESSL 1.00 专用**的扩展名。
在 `#version 300 es` 下，必须用 **`GL_OES_EGL_image_external_essl3`** 这个 ESSL3 版本。
漏改的后果：
- 轻则 shader 编译报错 → 走 fallback shader（画面失去美颜/投影处理）
- 重则整个主 shader 编译失败 → 黑屏（`shaderError` 会显示出来）

同时 `enable` 建议改 `require`：视频播放路径下这个扩展是**必需**的，用 `require` 能在编译期就暴露不支持，而不是运行期拿到坏纹理。

> 设备支持度：`GL_OES_EGL_image_external_essl3` 自 Android 4.3 / GLES 3.0 起是**事实标准**，
> 华为 VR Glass 所接机型（Mate X2 等，ES3.2 级别）必然支持。低端老设备需注意。

##### 2.1.6-bis ⚠️ blit shader 的多段拼接（1938/1940 行）

blit shader 是**两段字符串用 `+` 拼接**的，且使用独立的 `compileGpuShader()`：

```kotlin
// 现状（1937-1940 行，ensureBlitProgram() 内）
val vs = "attribute vec2 aPos;attribute vec2 aTex;varying vec2 vTex;" +
         "void main(){gl_Position=vec4(aPos,0.0,1.0);vTex=aTex;}"
val fs = "precision mediump float;varying vec2 vTex;uniform sampler2D uTex;" +
         "void main(){gl_FragColor=texture2D(uTex,vTex);}"
```

**迁移后应为**：
```kotlin
val vs = "#version 300 es\n" +
         "in vec2 aPos;in vec2 aTex;out vec2 vTex;" +
         "void main(){gl_Position=vec4(aPos,0.0,1.0);vTex=aTex;}"
val fs = "#version 300 es\n" +
         "precision mediump float;in vec2 vTex;uniform sampler2D uTex;" +
         "layout(location=0) out vec4 fragColor;" +
         "void main(){fragColor=texture(uTex,vTex);}"
```

注意：
- `#version` 必须加在**第一段字符串的最开头**（拼接后即第 1 行）
- 这两段 shader 是**单行紧凑写法**（无缩进），改动时保持风格即可
- 它走 `compileGpuShader()`（与主 shader 的 `compileShader()` 不同），
  若要做 §2.1.10 的日志增强，**两个函数都要改**（见下方补充）

#### 2.1.10-bis 两个编译函数的日志都要增强

| 函数 | 位置 | 用途 |
|---|---|---|
| `compileShader(type, code)` | `VRGLRenderer.kt:1731` | 主 shader / fallback shader |
| `compileGpuShader(type, src)` | `VRGLRenderer.kt:1966` | GPUPixel blit shader |

迁移期间建议**两个函数都**在失败时打印完整源码 + 编译日志。

### 2.1.7 `precision` 声明（可保留，无需改）

| 行 | 内容 | 说明 |
|---|---|---|
| 419 | `precision mediump float;` | GLES3 语义不变，保留 |
| 457 | `precision highp float;` | 保留 |
| 1939 | `precision mediump float;` | 保留 |

> 可选优化：主 shader 用了 `highp`，可考虑给采样坐标也显式指定精度。
> 另外 GLES3 中 vertex shader 默认 `highp`，无需声明（当前也没声明，保持即可）。

#### 2.1.8 `GLES20.*` 调用（279 处）—— **无需修改** ✅

**关键结论**：`android.opengl.GLES20` 是一个「常量 + static 方法」的容器类。
本项目用到的全部常量**都是 ES2/ES3 共有常量**，数值在 GLES3 中完全保留：

- `GL_TEXTURE_2D`(33)、`GL_RGBA`(6)、`GL_FLOAT`(6)、`GL_TRIANGLES`、`GL_LINEAR`、`GL_CLAMP_TO_EDGE`
- `GL_FRAMEBUFFER`(7)、`GL_COLOR_ATTACHMENT0`、`GL_DEPTH_TEST`、`GL_VERTEX_SHADER`、`GL_FRAGMENT_SHADER`
- `GL_TEXTURE0..2`(5+)、`GL_COLOR_BUFFER_BIT`、`GL_COMPILE_STATUS`、`GL_LINK_STATUS`

FBO 相关（`GL_FRAMEBUFFER` / `GL_COLOR_ATTACHMENT0`）在 GLES2 中需 `GL_OES_framebuffer_object` 扩展，
但在 GLES3 中是**核心功能**，常量值不变 → `GLES20.glGenFramebuffers(...)` 在 ES3 上下文中**正常工作**。

> **这 279 处是本次升级中"零成本"的部分。** 只有想主动使用 ES3 新特性（VAO / UBO / instancing）时，
> 才需要改为 `GLES30.*` / `GLES31.*` / `GLES32.*` 类。**本次升级建议不引入。**

#### 2.1.9 `GLES11Ext` 外部纹理（无需改）

| 行 | 用途 |
|---|---|
| 6 | `import android.opengl.GLES11Ext` |
| 1494 | `glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)` |
| 1753–1757 | `createOESTexture()` 里的参数设置 |

> `GL_TEXTURE_EXTERNAL_OES` 在 GLES3 中**完整支持**，这些调用**不需要改**。
> （注意：需要改的是**shader 侧的扩展名**，见 2.1.6；Java API 侧不用动。）

#### 2.1.10 `compileShader()` 的日志能力（建议增强，非必需）

现状（`VRGLRenderer.kt:1731-1747`）编译失败只抛异常。迁移期间建议：
- 在抛异常前把 **shader 源码全文 + 编译日志** 一起打到 logcat
- 目前 `shaderError` 只存了 log，不便于定位是哪个 shader 变体（`VIDEO_OES` 还是非 OES）失败

---

### 2.2 `VRGLSurfaceView.kt` —— 上下文版本

| 行 | 现状 | 改为 |
|---|---|---|
| 38 | `setEGLContextClientVersion(2)` | `setEGLContextClientVersion(3)` |

⚠️ **这一行必须与 §2.1 的 shader 迁移同时上线**，否则：
- 若先改这一行 → ES3 上下文 + ESSL 1.00 shader，**可以工作**（向后兼容），但没任何收益
- 若先改 shader → ES2 上下文 + `#version 300 es`，**黑屏**（编译必失败）

**正确顺序**：`shader 迁移到 300 es` 与 `setEGLContextClientVersion(3)` **在同一个 commit 里**。

- 第 39 行 `setEGLConfigChooser(8, 8, 8, 8, 16, 0)`：保留

---

### 2.3 `C++ aura_vr_session.cpp` —— EGL 精确指定 3.2（可选）

#### 2.3.1 当前实现（已相当完善）

```cpp
// 第 577-587 行：config 层已用 ES3 位
const EGLint configAttribs[] = {
    EGL_SURFACE_TYPE,    EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
    EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,          // ← 已是 ES3
    EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
    EGL_DEPTH_SIZE, 24, EGL_SAMPLES, 0,
    EGL_NONE
};
// 第 589-604 行：找不到时降级（不强制 renderable_type）
```

```cpp
// 第 606-625 行：context 层逐级降级
for (EGLint ver = 3; ver >= 2; --ver) {
    const EGLint contextAttribs[] = { EGL_CONTEXT_CLIENT_VERSION, ver, EGL_NONE };
    ctx = eglCreateContext(eglDisplay_, eglConfig_, EGL_NO_CONTEXT, contextAttribs);
    if (ctx != EGL_NO_CONTEXT) { AURA_LOGI("EGL 上下文创建成功，client version = %d", ver); break; }
    AURA_LOGW("eglCreateContext(ES%d) 失败，尝试更低版本", ver);
}
```

#### 2.3.2 局限：拿不到「真正的 3.2」

`EGL_CONTEXT_CLIENT_VERSION` **只能表达大版本**（1 / 2 / 3），无法指定 3.1 / 3.2。
传 3 得到的上下文，其**实际能力位由驱动决定**（多数驱动在 ES3 上下文下默认暴露 3.2）。

**若要精确指定 3.2**，需要走 `EGL_KHR_create_context` 扩展路径：
```cpp
// 需要 EGL_KHR_create_context（或 EGL 1.5 核心）
const EGLint ctxAttribs[] = {
    EGL_CONTEXT_MAJOR_VERSION, 3,
    EGL_CONTEXT_MINOR_VERSION, 2,     // ← 精确到 3.2
    EGL_NONE
};
```
- 无 `_KHR` 后缀的 `EGL_CONTEXT_MAJOR_VERSION`（值 `0x3098`）需要 **EGL 1.5**；
  老版本需用带 `_KHR` 后缀的常量（值相同，`EGL_KHR_create_context` 扩展）。
- 需先 `eglQueryString(eglDisplay_, EGL_EXTENSIONS)` 检查是否含 `EGL_KHR_create_context`。
- **建议**：先加一个**能力探测日志**（`glGetIntegerv(GL_MAJOR_VERSION/GL_MINOR_VERSION)`）确认实际拿到的版本，
  再决定是否值得引入这套复杂度。**大概率不需要改。**

#### 2.3.3 建议增加的探测日志

在 `aura_vr_session.cpp:652`（现有 `GL_VERSION` 日志）附近补充：
```cpp
GLint major = 0, minor = 0;
glGetIntegerv(GL_MAJOR_VERSION, &major);
glGetIntegerv(GL_MINOR_VERSION, &minor);
AURA_LOGI("GL 版本 %d.%d (GL_VERSION=%s)", major, minor, glGetString(GL_VERSION));
```
目的：确认真机上实际拿到的是 3.0 还是 3.2。**这是决定 §2.3.2 是否要做的依据。**

> ⚠️ `GL_MAJOR_VERSION` / `GL_MINOR_VERSION` 在 GLES2 上下文下查询是**无效的**（返回 0 或报错），
> 所以这段探测要放在确认 `ver == 3` 的分支里，或加保护。

#### 2.3.4 C++ 侧 FBO 代码（已符合 GLES3，无需改）

`aura_vr_session.cpp:388-407` 的 `glGenFramebuffers` / `glFramebufferTexture2D` /
`glCheckFramebufferStatus` **都是 GLES3 核心函数**，在 ES2 下需要
`GL_OES_framebuffer_object` 扩展。既然 `aura_vr_session.h:31` 已经 include `GLES3/gl3.h`
且 `CMakeLists.txt:128` 链接的是 `GLESv3`，**这部分已经完全就绪**。

> `CMakeLists.txt:127-128`：
> ```
> EGL
> GLESv3      # ⚠️ 华为要求 GLES 3.2，用 GLESv3（提供 ES 3.x 符号）
> ```
> 注释已经写明了意图，且**未链接 GLESv2**，无冲突。

#### 2.3.5 未使用 VAO（可选，不影响功能）

C++ 侧**未使用** `glGenVertexArrays` / `glBindVertexArray`，
仍然是 GLES2 风格的「每次绘制设置顶点属性指针」。GLES3 中默认 VAO 0 仍可用，因此**不改也能工作**。
若后续要提升绘制效率，可考虑引入 VAO —— 属独立优化项，**不列入本次升级**。

---

### 2.4 第三方库：GPUPixel（需验证，但大概率无需改）

`app/build.gradle.kts:216`：
```kotlin
implementation(files("libs/gpupixel-release.aar"))
```

GPUPixel 是**预编译 AAR**（含 arm64-v8a / armeabi-v7a 的 `libgpupixel.so`）。

**风险评估**：
- GPUPixel 内部有自己的 GL 上下文管理（走 raw-data 模式 + 离屏 FBO，见 v2.0.160 记录）
- 它作为**独立引擎**运行时，走的是自己创建的 FBO/纹理流程，与主渲染器的 GLES 版本**相对解耦**
- 但若它内部硬编码了 ESSL 1.00 shader，在 ES3 上下文中**仍可编译**（向后兼容）→ 预计无影响
- ⚠️ **真机必须实测**：GPUPixel 引擎开启时是否正常。这是升级后需要独立的回归项。

**需要验证的点**：
1. GPUPixel 引擎在 ES3 上下文下初始化是否成功（不弹「初始化失败，回退 GLSL」提示）
2. 全屏美颜（每帧回读处理）是否正常，有无闪烁
3. 若失败 → 检查 `libgpupixel.so` 是否依赖 `libGLESv2.so`

> 验证命令（真机/模拟器）：
> ```
> adb shell "readelf -d /data/app/*/lib/arm64/libgpupixel.so 2>/dev/null | grep NEEDED"
> ```
> 如果 `NEEDED` 里有 `libGLESv2.so`，在只有 `libGLESv3.so` 的环境下**仍能加载**
> （Android 上 `libGLESv2.so` 始终存在，`libGLESv3.so` 是它的超集/别名）。

---

### 2.5 其他文件（**无需改动**）

| 文件 | 结论 |
|---|---|
| `huawei/HuaweiVrActivity.kt:151` | 已经是 `setEGLContextClientVersion(3)`，**无需改** |
| `huawei/HuaweiVrActivity.kt:152` | `setEGLConfigChooser(8,8,8,8,16,0)` 保留 |
| `huawei/HuaweiVrNative.kt` | 纯 JNI 桥接，无 GL 版本相关代码 |
| `aura_vr_session.h` | 已 include `GLES3/gl3.h`，无需改 |
| `aura_vr_jni.cpp` | 无 GLES 版本相关代码 |
| `CMakeLists.txt` | 已链接 `GLESv3`，无需改 |
| `app/src/main/assets/` | **无 shader 文件**（只有模型/LUT/token），无需改 |
| `app/src/main/res/raw/` | 目录不存在 |
| `build.gradle.kts` | 无 `minSdk`/GLES 相关配置需要调整 |

---

## 3. 关键风险与陷阱

### 3.1 ⚠️ 陷阱 1：`#version` 与 `#define` 的顺序（**必踩**）

**现状**（`VRGLRenderer.kt:979`）：
```kotlin
val fCode = if (videoVariant) "#define VIDEO_OES 1\n" + fragmentShaderCode else fragmentShaderCode
```

**问题**：GLSL 规范要求 `#version` 必须是 shader 源码的**第一条指令**（之前只允许注释和空白）。
如果把 `#version 300 es` 写在 `fragmentShaderCode` 字符串里，拼接后就变成了：
```glsl
#define VIDEO_OES 1        ← 第 1 行
#version 300 es            ← 第 2 行  ❌ 非法！
```
编译报错：`'#version' must be the first statement in a shader`（或类似）。

**解法**（三选一）：
1. **字符串插值**（推荐）：把宏定义改为模板
   ```kotlin
   private val fragmentShaderCode = """
       #version 300 es
       ${'$'}{VIDEO_OES_DEFINE}
       precision highp float;
       ...
   """
   ```
   在编译时用 `replace` 填入 `#define VIDEO_OES 1` 或空串。
2. **`replaceFirst` 注入**：
   ```kotlin
   val fCode = if (videoVariant)
       fragmentShaderCode.replaceFirst("#version 300 es", "#version 300 es\n#define VIDEO_OES 1")
   else fragmentShaderCode
   ```
3. **写进 `#ifdef`**：把 `#version` 放在字符串第一行，宏定义放在 Kotlin 侧拼接时置于其后。
   （本质同 2，需保证 `#version` 是拼接结果的第一行。）

> 同理，第 1937/1939 行的 blit shader 也是拼接的（`"attribute ..." + "..."` 多段字符串），
> 迁移时务必确认 `#version` 在最终拼接结果的第 1 行。

### 3.2 ⚠️ 陷阱 2：OES 扩展名（**必踩**）

见 §2.1.6。`GL_OES_EGL_image_external` → `GL_OES_EGL_image_external_essl3`。
**这一处漏改 = 视频播放路径主 shader 编译失败 = 黑屏。**

建议在改完后**专门跑一次视频播放**验证（这是最容易漏的路径，因为图片/全景路径不用 OES）。

### 3.3 ⚠️ 陷阱 3：两条路径版本不一致

见 §1.1。`VRGLSurfaceView` 是 ES2、`HuaweiVrActivity` 是 ES3，**共享同一份 shader 源码**。
→ shader 迁移和 `setEGLContextClientVersion(3)` 必须**同一个 commit**。

### 3.4 ⚠️ 陷阱 4：`varying` 方向

同名 `varying vec2 vTextureCoord;` 在 vertex shader 里是**输出**（`out`），
在 fragment shader 里是**输入**（`in`）。
批量正则替换（如全部 `varying` → `out`）会**把 fragment 侧改错**，导致 link 失败。
→ **必须逐处按所属 shader 判断方向。**

### 3.5 ⚠️ 陷阱 5：fallback shader 也要同步改

`fallbackVertexShaderCode` / `fallbackFragmentShaderCode`（408-425 行）是主 shader 编译失败时的兜底。
如果只改了主 shader 而忘了 fallback，**一旦主 shader 出问题走 fallback，fallback 自己又编译不过** → 彻底黑屏。
→ 6 个 shader **一个都不能漏**。

### 3.6 ⚠️ 陷阱 6：ES3 上下文下的「静默行为差异」

即使 shader 不改（保持 ESSL 1.00），仅把上下文切到 ES3，也可能出现细微差异：
- `highp` 精度支持范围变化（ES3 强制要求 fragment `highp`，ES2 是可选）
  → 本项目主 shader 已用 `highp`，在 ES2 设备上可能被降级为 `mediump`，切 ES3 后**精度提升**，画面可能有**可见变化**（美颜边缘更锐利等）
- 部分驱动在 ES3 下对 `precision` 缺省值处理不同
→ 建议：**先只切上下文（保持 ESSL 1.00 shader）跑一轮回归**，确认无视觉回归后再迁 shader。
   这是把「一个大的不可控变更」拆成「两个小的可控变更」的策略。

---

## 4. 建议的实施顺序

> 分 4 个阶段，每阶段独立可验证、可回滚。

### 阶段 0：准备（不改功能）
1. 备份 `VRGLRenderer.kt`、`VRGLSurfaceView.kt`、`aura_vr_session.cpp`（按项目备份规则）
2. 增强 `compileShader()` 的日志：失败时打印**完整 shader 源码 + 编译日志 + 变体标记**
3. 在 `onSurfaceCreated` 里加 GL 版本探测日志
   ```kotlin
   Log.i("GLVer", "GL_VERSION=" + GLES20.glGetString(GLES20.GL_VERSION))
   ```
4. 真机/模拟器跑一遍，**记录基线**：正常播放、美颜、VR 分屏、华为 VR 各自的表现

### 阶段 1：只切上下文版本（最小风险，收益先拿到）✅ 建议先做
1. `VRGLSurfaceView.kt:38` → `setEGLContextClientVersion(3)`
2. **shader 完全不改**（保持 ESSL 1.00，ES3 向后兼容）
3. 回归验证：4 条路径全走一遍（图片 / 视频 / 分屏 VR / 华为 VR）
4. 重点观察：画面精度/颜色是否有可见变化、GPUPixel 是否仍能初始化

> 本阶段的价值：**用最小改动验证 ES3 上下文在真机上无副作用**。
> 如果这步就出问题，说明升级风险高，应停下重新评估。

### 阶段 2：shader 迁移到 `#version 300 es`
按 §2.1 的 6 个子项逐个改，**同一 commit 内完成**：
1. 6 个 shader 各补 `#version 300 es`（注意 §3.1 顺序陷阱）
2. `attribute` → `in`（6 处）
3. `varying` → `out`/`in`（6 处，注意 §3.4 方向）
4. `texture2D` → `texture`（17 行）
5. `gl_FragColor` → `out vec4 fragColor`（3 处）
6. OES 扩展换名（§2.1.6，**最关键**）
7. 回归验证：重点测**视频播放**（OES 路径）+ 美颜（主 shader 全量逻辑）

### 阶段 3（可选）：C++ 侧 3.2 精确化
1. 加 `GL_MAJOR_VERSION` / `GL_MINOR_VERSION` 探测（§2.3.3）
2. 若实测只拿到 3.0 且需要 3.2 → 引入 `EGL_CONTEXT_MAJOR/MINOR_VERSION`（§2.3.2）
3. 若已拿到 3.2 → **不做任何改动**，仅记录日志

---

## 5. 验收清单

### 功能回归（4 条路径全走）
- [ ] **本地视频播放**（OES 路径，最易漏 —— 验 §3.2）
- [ ] **图片 / 全景图显示**（非 OES 路径）
- [ ] **内置分屏 VR**（走 `VRGLSurfaceView`，验 §3.3）
- [ ] **华为 VR Glass**（真机，走 `HuaweiVrActivity`）
- [ ] **美颜 - GLSL 引擎**（主 shader 全量逻辑，验 `texture2D` → `texture` 改动）
- [ ] **美颜 - GPUPixel 引擎**（第三方库，验 §2.4）
- [ ] **LUT 滤镜**（`texture2D(uLutTexture, ...)`，行 537/538）
- [ ] **投影模式切换**（标准/鱼眼/360/180）
- [ ] **左右眼 / SBS / TAB 立体模式**
- [ ] **旋转 180° / 上下镜像**
- [ ] **后台处理（GPUPixel 回读）无闪烁**（v2.0.173 修过的问题不能回归）

### 技术指标
- [ ] `logcat` 中 shader 编译无 error（6 个 shader 全部）
- [ ] `shaderError` 恒为 null（UI 上无 shader 报错提示）
- [ ] `GL_VERSION` 日志显示 3.x（记录实际拿到的版本）
- [ ] 帧率无下降（对比阶段 0 的基线）
- [ ] 无内存增长（FBO/纹理无泄漏）

### 回滚预案
- 每个阶段独立 commit，出问题 `git revert` 单个 commit 即可
- 阶段 2 若失败 → 回滚到「阶段 1 的 ES3 上下文 + ESSL 1.00 shader」这个稳定态

---

## 6. 收益与成本评估

### 收益
| 收益 | 说明 |
|---|---|
| **与华为路径统一** | 消除 §1.1 的「同一渲染器跑两个 GLES 版本」的隐患 |
| **可用 GLES 3.2 特性** | 未来可用 VAO（减少顶点属性设置开销）、UBO、instancing、`texelFetch` |
| **计算着色器（3.1+）** | 若未来做 GPU 美颜/双线性降采样，可用 compute shader 替代 fragment 技巧 |
| **精度提升** | fragment `highp` 成为强制，美颜算法数值稳定性更好 |
| **无 ES3 特性时的性能提升** | 部分驱动在 ES3 上下文中对 FBO/纹理路径有优化 |

### 成本
| 成本 | 量级 |
|---|---|
| shader 语法迁移 | 6 个 shader / 约 30 处关键字 —— **约 1~2 小时** |
| 上下文版本切换 | 1 行 —— **1 分钟** |
| 回归测试 | 4 条路径 × 11 个功能点 —— **主要成本在这里** |
| C++ 精确 3.2 | 可选，视探测结果 —— **0 或 2 小时** |
| 风险 | 中低（无自定义 EGL、无独立 shader 文件、无第三方 shader 依赖） |

### 建议
**推荐分阶段推进，且阶段 1（只切上下文）优先做、独立验证。**
- 若阶段 1 顺利 → 阶段 2 的风险大幅下降，可按计划推进
- 若阶段 1 就出问题 → 说明真机驱动有兼容性坑，此时**应当停在阶段 1 之前**（保持 ES2），
  而不是硬推 shader 迁移

**不建议**把 shader 迁移和上下文切换放在同一次改动里一次到位——
那会把「驱动兼容性风险」和「shader 语法风险」叠加，出问题难以定位。

---

## 7. 附：完整改动点索引（按行号）

### `app/src/main/java/com/example/vr/VRGLRenderer.kt`

| 行 | 现状 | 目标 | 优先级 |
|---|---|---|---|
| 395 | `private val vertexShaderCode = """` | 首行加 `#version 300 es` | P0 |
| 397 | `attribute vec4 aPosition;` | `in vec4 aPosition;` | P0 |
| 398 | `attribute vec2 aTextureCoord;` | `in vec2 aTextureCoord;` | P0 |
| 399 | `varying vec2 vTextureCoord;` | `out vec2 vTextureCoord;` | P0 |
| 408 | `fallbackVertexShaderCode = """` | 首行加 `#version 300 es` | P0 |
| 409 | `attribute vec4 aPosition;` | `in vec4 aPosition;` | P0 |
| 410 | `attribute vec2 aTextureCoord;` | `in vec2 aTextureCoord;` | P0 |
| 411 | `varying vec2 vTextureCoord;` | `out vec2 vTextureCoord;` | P0 |
| 418 | `fallbackFragmentShaderCode = """` | 首行加 `#version 300 es` | P0 |
| 419 | `precision mediump float;` | 保留 | — |
| 420 | `varying vec2 vTextureCoord;` | `in vec2 vTextureCoord;` | P0 |
| 422–423 | `void main() { gl_FragColor = texture2D(...)` | 加 `out vec4 fragColor;` + 换名 + `texture()` | P0 |
| 453 | `fragmentShaderCode = """` | 首行加 `#version 300 es` | P0 |
| 454–456 | `#ifdef VIDEO_OES` / `#extension GL_OES_EGL_image_external : enable` / `#endif` | 扩展名改 `_essl3` + `require` | **P0 关键** |
| 457 | `precision highp float;` | 保留 | — |
| 459 | `varying vec2 vTextureCoord;` | `in vec2 vTextureCoord;` | P0 |
| 464 | `uniform samplerExternalOES uSamplerVideo;` | **不改**（类型名不变） | — |
| 537–538 | `texture2D(uLutTexture, ...)` ×2 | `texture(...)` | P0 |
| 790, 792 | `texture2D(uSamplerVideo/Image, tc)` | `texture(...)` | P0 |
| 808–815 | `texture2D(...)` ×16 | `texture(...)` | P0 |
| 972 | `gl_FragColor = color;` | `fragColor = color;` | P0 |
| 979 | `"#define VIDEO_OES 1\n" + fragmentShaderCode` | 调整拼接顺序（§3.1） | **P0 关键** |
| 1731–1747 | `compileShader()` | 增强日志（可选） | P2 |
| 1937 | blit `vs` 字符串（两段拼接） | 第 1 段开头加 `#version 300 es` + `in`/`out` | P0 |
| 1939 | blit `fs` 字符串（两段拼接） | 第 1 段开头加 `#version 300 es` + `in` + `out vec4 fragColor` | P0 |
| 1940 | `gl_FragColor=texture2D(uTex,vTex);` | `fragColor=texture(uTex,vTex);` | P0 |
| 1966 | `compileGpuShader()` | 增强日志（可选；与 1731 同步改） | P2 |

### `app/src/main/java/com/example/vr/VRGLSurfaceView.kt`

| 行 | 现状 | 目标 | 优先级 |
|---|---|---|---|
| 38 | `setEGLContextClientVersion(2)` | `setEGLContextClientVersion(3)` | **P0（与 shader 同一 commit）** |
| 39 | `setEGLConfigChooser(8, 8, 8, 8, 16, 0)` | 保留 | — |

### `app/src/main/cpp/aura_vr_session.cpp`

| 行 | 现状 | 目标 | 优先级 |
|---|---|---|---|
| 577–587 | `EGL_OPENGL_ES3_BIT` config | 保留 | — |
| 608–619 | `for (ver = 3; ver >= 2; --ver)` | 保留（或改精确 3.2） | P3 可选 |
| 652 | `GL_VERSION` 日志 | 补 `GL_MAJOR/MINOR_VERSION` 探测 | P2 |

### 无需改动
- `huawei/HuaweiVrActivity.kt:151`（已是 3）
- `aura_vr_session.h:31`（已 include GLES3/gl3.h）
- `CMakeLists.txt:127-128`（已链 GLESv3）
- `VRGLRenderer.kt` 中 279 处 `GLES20.*`
- `VRGLRenderer.kt:6, 1494, 1753-1757` 的 `GLES11Ext`
- `app/src/main/assets/`（无 shader 文件）

---

## 8. 一句话总结

> 这个项目的 GLES 升级**本质上是「6 个内联 shader 的语法迁移」+「1 行上下文版本号」**，
> 因为它的结构极其干净：C++ 侧已经是 GLES3、无自定义 EGL、无独立 shader 文件、279 处 `GLES20.*` 不用动。
> **唯二的两个真陷阱是**：`#version` 与 `#define` 的拼接顺序、以及 OES 扩展必须换名为 `_essl3`。
> 建议**先只切上下文版本（阶段 1）独立验证**，确认驱动无兼容问题后再迁 shader（阶段 2）。
