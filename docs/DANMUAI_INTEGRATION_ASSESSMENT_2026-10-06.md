# DanmuAI 接入可行性评估报告

> **评估对象**：`https://github.com/PEPETII/danmuai`
> **评估目标**：在「**不修改 DanmuAI 代码**」的前提下，评估将其接入 **Aura-face-VR-Player**（本项目）的难度
> **报告日期**：2026-10-06
> **评估基准版本**：DanmuAI v0.5.0（commit `00e60ee`，2026-10-04）
> **本项目版本**：v2.1.248（versionCode 248）
> **报告性质**：只读调研，**本报告不涉及任何代码改动**

---

## 一、结论（先说答案）

| 维度 | 结论 |
|---|---|
| **能不能接** | **技术上不能「接入」，只能「并列共存」**。两者是**两个互相独立运行的程序**。 |
| **总体难度** | 🔴 **很高**（不是因为技术难，而是因为**架构与许可证双重阻断**） |
| **核心阻断 1** | **平台不兼容**：DanmuAI 是 **Windows 桌面程序**（PyQt6 + pywebview），本项目是 **Android APK**。两者无法打包进同一个应用。 |
| **核心阻断 2** | **GPL-3.0-or-later 许可证**：本项目当前为**闭源/未声明开源许可**的 Android 应用。任何形式的代码级复用都会触发 GPL 传染，要求**整个 App 开源**。 |
| **「不修改代码」这个前提** | 恰好**规避了许可证风险**（不修改、不分发其代码 → 不触发 copyleft），但也同时**封死了唯一的技术通路**（无法把它的 Python 逻辑搬进 Android）。 |
| **真正可行的形态** | **方案 E：双端并行** —— 手机上跑本项目放 VR 视频，PC 上跑 DanmuAI 对另一块屏幕生成 AI 弹幕。两者通过局域网 MQTT/HTTP 联动（**需改本项目代码，不改 DanmuAI 代码**）。 |
| **建议** | **不建议直接接入**。若确实想要 AI 弹幕，**自研一个精简 Android 版**（复用其"思路"而非"代码"，思路不受 GPL 约束）性价比更高。 |

**一句话总结**：
> DanmuAI 是**竞品位置**（同样做 AI 视频相关增强），不是**可复用组件**。它没有任何 Android 接口、没有任何可嵌入的 SDK、没有任何独立可用的服务端 API，且许可证与本项目不兼容。**「不修改代码」这个约束下，唯一能做的是让两者各自独立运行、在局域网层面握手。**

---

## 二、DanmuAI 是什么 —— 事实核查

### 2.1 项目定位

> **Windows 桌面 AI 弹幕助手**：看懂画面、生成弹幕、读出声音，并把回应显示在屏幕上。

它做的事：按设定间隔**截取指定显示器画面** → 调用**视觉模型**理解内容 → 生成一批弹幕 → 通过**透明置顶窗口**滚动展示 → 可选 **TTS 朗读**。

### 2.2 关键元数据（GitHub API 实测）

| 项 | 值 |
|---|---|
| 描述 | 独属于你的 AI 主播和 AI 弹幕，支持 AI 读弹幕，允许自由配置你喜欢的 AI 模型 |
| 主语言 | **Python** |
| Stars / Forks | 362 / 21 |
| 仓库体积 | 92,336 KB（约 90 MB） |
| 创建时间 | 2026-05-17 |
| 最后推送 | 2026-10-04 |
| 最新版本 | **v0.5.0**（2026-10-04） |
| **许可证** | **GPL-3.0-or-later**（SPDX：`GPL-3.0-or-later`） |
| 官网 | https://danmuai.xyz/ |
| Topics | `danmaku-ai-assistant` |
| 开放 Issue | 2 |

### 2.3 技术栈（`requirements.txt` 逐行实测）

```
PyQt6>=6.6,<7            ← Windows 桌面 GUI（弹幕浮层、托盘、主线程）
live2d-py==0.7.0.4       ← Live2D 虚拟主播
PyOpenGL>=3.1,<4         ← Live2D 渲染
httpx[http2]>=0.27,<1    ← HTTP 客户端（调视觉模型）
keyboard>=0.13,<1        ← 全局热键（Win32 键盘钩子）
cryptography>=44.0.1,<45 ← 凭据加密（Windows DPAPI/文件加密）
python-Levenshtein>=0.23,<1  ← 弹幕去重（编辑距离）
Pillow>=10.0,<12         ← 图像处理
sounddevice>=0.4.6,<1    ← 麦克风采集（PortAudio）
numpy>=1.24,<3
fastapi>=0.115.0,<1      ← 本地 Web 控制台服务
python-multipart>=0.0.20,<0.1
uvicorn[standard]>=0.32.0,<1
websockets>=12.0,<14
pywebview>=5.0,<6        ← 桌面壳（WebView2）
velopack>=1.2.0,<2       ← Windows 安装器/自动更新框架
dashscope>=1.24.6,<2
trafilatura>=1.12,<2     ← 网页正文抽取
markdown-it-py>=3.0,<5
```

**关键判读**：
- `PyQt6` + `pywebview` + `velopack` → **纯 Windows 桌面应用**，打包产物是 `Setup.exe` / `Portable.zip`
- **没有**任何 Android / Kotlin / Java / JNI 相关依赖
- **没有** PyPI 发布包、**没有**可嵌入的 SDK、**没有**独立服务端

### 2.4 架构规模（`app/` 目录实测）

`app/` 下有 **100+ 个 Python 模块**，其中重量级文件：

| 文件 | 体积 | 职责 |
|---|---|---|
| `model_catalog.py` | 61 KB | 模型目录 |
| `main_lifecycle_mixin.py` | 55 KB | 生命周期 |
| `floating_panel_style.py` | 40 KB | 弹幕样式 |
| `floating_panel_overlay.py` | 40 KB | 弹幕浮层 |
| `model_providers.py` | 30 KB | 平台适配 |
| `api_probe.py` | 30 KB | 接口探测 |
| `overlay.py` | 30 KB | 透明窗口 |
| `main_request_context_mixin.py` | 29 KB | 请求上下文 |
| `danmu_read_service.py` | 28 KB | TTS 读弹幕 |
| `web_console.py` | 28 KB | Web 控制台 |

`main.py` 本身 43 KB，`DanmuApp` 类**继承 12 个 mixin + QObject**。

**判读**：这是一个**高度工程化的单体桌面应用**，不是库。代码量约 **10 万行级 Python**，且深度绑定 Qt 事件循环与 Windows API。

### 2.5 关键实现细节（决定成败的三处）

**① 截图 —— 深度绑定 Qt，无法在 Android 复用**

`app/snipper.py` 实测：
```python
from PyQt6.QtGui import QPixmap
from PyQt6.QtWidgets import QApplication
...
screens = QApplication.screens()
target_screen = screens[plan.screen_index]
return target_screen.grabWindow(0, plan.grab_x, plan.grab_y, plan.grab_w, plan.grab_h)
```
- 屏幕枚举靠 `QApplication.screens()`
- 像素抓取靠 `QScreen.grabWindow()`
- 窗口捕获模式靠 **`hwnd`（Win32 窗口句柄）** + `app.window_capture.grab_window()`
- 另有 `app/win32_overlay_zorder.py` → **Win32 窗口层级控制**

→ **全都是 Windows + Qt 专有 API。**

**② 弹幕上屏 —— 透明置顶窗口，Android 无对应物**

`app/floating_panel_overlay.py` / `app/overlay.py` 走 Qt 无边框透明窗口 + Win32 z-order 操作。Android 上类似的只有 `TYPE_APPLICATION_OVERLAY` 悬浮窗（需额外权限、且**无法覆盖在 VR 分屏渲染之上**）。

**③ 视觉模型调用 —— 这一层是唯一"通用"的**

`app/ai_client.py` 实测：
- 使用 `httpx.Client(timeout=Timeout(30.0, connect=5.0), http2=True)`
- 双模式：`openai`（Chat Completions）与 `doubao`（Responses API）
- 图片以 **data URI**（base64）传给视觉模型
- 凭据用 `cryptography` 加密落盘

→ **这是唯一与本项目能力重叠、且理论上可互通的一层**，但它**不是一个对外暴露的 API 服务**，而是进程内的函数调用。

### 2.6 运行链路（README 官方描述）

```
定时截图 → 内存压缩 → 视觉模型请求 → 回复解析与校验 → 回复队列 → Qt 透明弹幕浮层
```
默认本地 Web 服务只监听 `127.0.0.1:18765`，**仅供其自带 Web 控制台使用**，且带应用内会话鉴权。

---

## 三、本项目（Aura-face-VR-Player）现状对照

| 维度 | Aura-face-VR-Player | DanmuAI | 是否兼容 |
|---|---|---|---|
| 平台 | **Android**（APK，minSdk 24） | **Windows**（exe） | ❌ |
| 语言 | Kotlin + C++（JNI/NDK） | Python 3.12 | ❌ |
| UI 框架 | Jetpack Compose | PyQt6 | ❌ |
| 打包形式 | APK（241 MB） | Setup.exe / Portable.zip | ❌ |
| 屏幕来源 | **播放器内部解码帧** / VR 分屏 | 桌面屏幕抓取 | ❌ 概念不同 |
| AI 调用 | ✅ **已有** OkHttp + `Bearer` + `/chat/completions` | httpx | ⚠️ 协议同、代码不同 |
| 弹幕功能 | ❌ **完全没有**（全仓 grep `danmu`/`弹幕` = 0 命中） | ✅ 核心功能 | — |

**重要发现 —— 本项目已有可复用的 AI 调用基建**：

`app/src/main/java/com/example/vr/SubtitleTranslator.kt`（69,945 B）已经实现了完整的：
```kotlin
private val httpClient = OkHttpClient.Builder()...
val request = Request.Builder()
    .addHeader("Authorization", "Bearer $apiKey")
    ...
if (!baseUrl.endsWith("/chat/completions")) baseUrl = "$baseUrl/chat/completions"
```

→ 也就是说，**调用 OpenAI 兼容视觉模型的能力，本项目早就有了**。缺的只是"截图 → 生成弹幕 → 上屏"这条业务链。

---

## 四、为什么"不修改代码"是致命约束

用户的约束是「**不修改 DanmuAI 代码**」。这个约束的含义需要拆开看：

### 4.1 它规避了什么

✅ **规避了 GPL 传染**。GPL-3.0 的义务（第 4、5、6 节）只在**分发**（convey）时触发。如果只是本机运行、不修改、不分发其二进制或源码，**不产生任何许可证义务**。

### 4.2 它封死了什么

❌ **封死了所有实质性的技术复用路径**。因为 DanmuAI 的每一项能力都**焊死在它的进程内**：

| 想要的 | 不修改代码能做到吗 | 原因 |
|---|---|---|
| 把它的截图逻辑搬到 Android | ❌ | 依赖 `QApplication.screens()` / `grabWindow()` / `hwnd`，是 Python + Qt + Win32 三位一体，搬不走 |
| 把它的弹幕浮层搬到 Android | ❌ | 依赖 Qt 透明窗口 + Win32 z-order，Android 无对应实现 |
| 调用它的"视觉→弹幕"函数 | ❌ | **它是函数，不是 API**。没有对外的、无鉴权的生成接口 |
| 用它的 Web 控制台 API | ❌ | 只监听 `127.0.0.1:18765`，带会话鉴权，且它是**配置面板**，不是**生成服务** |
| 用它的 Python 包 | ❌ | **未发布到 PyPI**，无 `setup.py`/`pyproject` 打包为库（有 `pyproject.toml` 但 565 B，是工程配置非打包发布） |
| 编译成 Android 可用的 `.so` | ❌ | Python + PyQt6 无 Android 移植路径；即便有，也需大量修改源码（违反约束） |

### 4.3 结论

> **「不修改代码」+「Android 端接入」= 数学上的空集。**
>
> 因为 DanmuAI **没有任何为外部消费而设计的接口**（no SDK / no library / no public API / no CLI 生成模式）。它的所有能力都必须通过**启动它自己的完整桌面程序**、并**人工在它的 Web 控制台里操作**才能获得。

---

## 五、可行的接入形态（按难度排序）

即便在"不改 DanmuAI 代码"的约束下，仍有几种形态，但**都不是"接入"，而是"并存"**：

### 方案 A：完全独立并行 —— 🟢 难度 1/10（零代码）

**做法**：PC 上跑 DanmuAI 看某个窗口/屏幕；手机上跑本项目放 VR 视频。两者互不认识。

**优点**：0 成本，0 风险，0 许可问题。
**缺点**：不是"接入"，是两个独立软件。
**适用**：只是想同时用两个工具。

---

### 方案 B：LAN 握手 —— 🟡 难度 5/10（需改**本项目**代码）

**做法**：
1. PC 端 DanmuAI 正常运行（弹幕投到 PC 屏幕 / 采集卡回灌）
2. 本项目新增一个 **HTTP 服务端**（复用现有 OkHttp + NanoHTTPD/Ktor），暴露 `/danmu` 接口
3. 写一个**中间胶水程序**（自己写，约 100 行 Python）：从 DanmuAI 的本地库/日志/DB 读弹幕文本 → POST 到手机
4. 本项目的 Compose 层新增弹幕渲染

**难点**：
- DanmuAI 的弹幕数据**没有对外读取接口**。它内部有 `reply_queue.py`、`danmu_pool.py`、`history_writer.py`，但都是进程内的。要拿数据只能**读它的落盘历史文件**（`history_writer.py` 会写），属于**脆弱的逆向**。
- 需要在本项目里**从零实现弹幕渲染层**（轨道分配、变速滚动、去重）。

**成本**：本项目新增约 1500–2500 行 Kotlin。**DanmuAI 侧 0 改动**（但依赖其历史文件格式，版本升级可能失效）。

---

### 方案 C：改用 DanmuAI 的"创意"而非"代码" —— 🟢 难度 4/10（自研）

**做法**：**重新实现一条精简链路**，只借鉴设计思路：

```
本项目截图（已有能力：VR 帧 / 屏幕捕获 MediaProjection）
  → OkHttp 调视觉模型（本项目已有 SubtitleTranslator 范式可复用）
  → 解析出弹幕文本
  → Compose 弹幕层渲染
```

**关键点**：**思路不受 GPL 约束**（版权保护的是表达，不是思想）。只要不复制其代码，完全合法。

**成本**：本项目新增约 2000–3000 行 Kotlin。约 2–4 天。
**优势**：无许可证风险、无跨平台障碍、可深度适配 VR 分屏。

---

### 方案 D：变更许可证后复用 —— 🔴 难度 10/10（不推荐）

要把 DanmuAI 的代码搬进本项目，必须：
1. 本项目整体转为 GPL-3.0-or-later（**放弃闭源**）
2. 或获得作者单独的**商业授权**（需联系 PEPETII，未知是否提供）

**结论**：对一个商业化的 VR 播放器而言，**这等于放弃商业模式**，不现实。

---

### 方案 E：等待/推动作者提供 API —— ⚪ 完全被动

它已经有一个 FastAPI 本地服务（`127.0.0.1:18765`）。理论上作者可以扩展出一个 `/api/danmu/stream` 公开接口。但这**依赖第三方排期**，不可控。

---

## 六、难度评分汇总

| 方案 | 改 DanmuAI？ | 改本项目？ | 技术难度 | 许可风险 | 工作量 | 总评 |
|---|---|---|---|---|---|---|
| **A. 完全并行** | 否 | 否 | 🟢 1/10 | ✅ 无 | 0 | ⭐⭐⭐ 零风险 |
| **B. LAN 握手** | 否 | 是 | 🟡 5/10 | ✅ 无 | 中（+2500 行） | ⭐⭐ 脆弱 |
| **C. 借鉴自研** | 否 | 是 | 🟢 4/10 | ✅ 无 | 中（+3000 行） | ⭐⭐⭐⭐ **推荐** |
| **D. 代码复用** | 否 | 是 | 🔴 10/10 | ❌ **致命** | 高 | ❌ 不可行 |
| **E. 等官方 API** | 否 | 是 | ⚪ 未知 | ✅ 无 | 未知 | ⭐ 被动 |

---

## 七、核心技术风险清单

| # | 风险 | 等级 | 说明 |
|---|---|---|---|
| R1 | **平台鸿沟** | 🔴 阻断 | Windows 桌面 → Android APK，**无自动化移植路径** |
| R2 | **GPL-3.0 传染** | 🔴 阻断 | 任何代码级复用 → 本项目必须整体开源（第 5(c) 条：「entire work, as a whole」） |
| R3 | **无对外接口** | 🔴 阻断 | 无 SDK / 无库 / 无公开 API / 无 PyPI 包 → 不改代码则无任何可调用点 |
| R4 | **截图机制不可移植** | 🟠 高 | 依赖 `QApplication` + `grabWindow` + `hwnd`，三者皆为 Win32/Qt 专有 |
| R5 | **弹幕上屏机制不可移植** | 🟠 高 | Qt 透明窗口 + Win32 z-order；Android 悬浮窗**无法覆盖 VR 分屏渲染** |
| R6 | **Python 运行时不可嵌入** | 🟠 高 | Android 上跑 CPython + PyQt6 不现实（PyQt6 无 Android wheel） |
| R7 | **接口协议无稳定性承诺** | 🟡 中 | 若走读历史文件的路线，v0.5.0 → 后续版本格式可能变更 |
| R8 | **性能模型不同** | 🟡 中 | DanmuAI 按 5 秒间隔截图调 API；VR 播放器对帧率和发热更敏感 |
| R9 | **隐私/合规** | 🟡 中 | 屏幕内容上传第三方视觉模型，需明确告知用户；本项目已有 `FIREBASE_ANALYTICS.md` 等合规文档，需同步 |
| R10 | **维护归属** | 🟡 中 | 依赖外部 Windows 软件的落盘格式 = 长期技术债 |

---

## 八、建议

### 8.1 直接回答"难度"

> **接入难度：极高，且属于「结构性不可行」，而非「工程量很大」。**
>
> 三个阻断点（平台、许可证、无接口）**任一个单独出现都足以否决**，而它们同时成立。
> 特别是「**不修改代码**」这条约束，与「DanmuAI 没有任何对外接口」直接冲突——**不改它的代码，就没有任何东西可以调用**。

### 8.2 如果确实想要 AI 弹幕能力

推荐**方案 C（借鉴自研）**，理由：

1. **本项目已有 80% 的基建**：OkHttp + `Bearer` + `/chat/completions` 范式在 `SubtitleTranslator.kt` 里已跑通；VR 帧 / MediaProjection 截屏能力也在
2. **VR 场景与桌面场景根本不同**：DanmuAI 是"看桌面窗口"，本项目是"看 VR 分屏"，它的弹幕轨道算法直接搬过来也不适配
3. **零许可证风险**：只借鉴设计思路，不复制代码
4. **可深度优化**：能针对 VR 分屏做双眼独立渲染、针对华为 VR Glass 做优化，这是拿别人的 Windows 程序永远做不到的

**若要做，建议的最小可行版本（MVP）**：
- 复用 `SubtitleTranslator.kt` 的 HTTP 层
- 新增 `DanmuEngine.kt`（轨道分配 + 变速滚动 + 去重，参考其 Levenshtein 思路但自己实现）
- 新增 `DanmuOverlay.kt`（Compose 层，与现有 `SubtitleOverlay` 并列）
- 截图源：先做「**当前帧**」而非「屏幕捕获」（更省电、且 VR 场景下屏幕捕获无意义）

### 8.3 如果只是想同时用

**方案 A**：PC 跑 DanmuAI，手机跑本项目，互不干扰。**今天就能做，零成本。**

---

## 九、附：本次核查的原始依据

| 事实 | 来源 |
|---|---|
| 仓库元数据（语言 Python、362 stars、92 MB、GPL、v0.5.0） | GitHub REST API `/repos/PEPETII/danmuai` |
| GPL-3.0-or-later 及第 5(c) 条全文 | `raw.githubusercontent.com/PEPETII/danmuai/main/LICENSE`（35,444 B） |
| 全部依赖（PyQt6 / pywebview / velopack / live2d-py 等 20 项） | `requirements.txt`（399 B） |
| 截图机制为 Qt + Win32 | `app/snipper.py` 全文（`QApplication.screens()` / `grabWindow` / `hwnd`） |
| `app/` 下 100+ 模块与体积 | GitHub Contents API `/contents/app` |
| `main.py` 前 200 行（12 mixin 继承、Qt 主线程模型） | `raw.githubusercontent.com/.../main.py` |
| 配置项全表（无任何 Android 相关字段） | `app/config_defaults.py` 全文 |
| AI 调用为进程内 httpx 函数 | `app/ai_client.py` 全文 |
| 本项目已有 OkHttp + Bearer + /chat/completions | `app/src/main/java/com/example/vr/SubtitleTranslator.kt:1276,1301,1303` |
| 本项目无弹幕功能 | 全仓 grep `danmu` / `弹幕` → **0 命中** |
| 本项目版本 v2.1.248 | `app/build.gradle.kts:19-20` |

---

*本报告为只读调研产物，未修改任何源码。*
