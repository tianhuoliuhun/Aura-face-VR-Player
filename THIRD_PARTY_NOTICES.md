# 第三方组件声明 / Third-Party Notices

> 本文档列出 Aura face VR Player（下称"本项目"）分发时包含的第三方组件、许可与义务。
> 应用内同一份内容见「设置 → 关于与开源许可」；根目录 `NOTICE` 是 Apache-2.0 要求的简短署名文件。
>
> 本项目自身以 **Apache License 2.0** 发布，见根目录 `LICENSE`。

**文档更新时间**：2026-10-18
**对应版本**：v2.6.2

---

## 一、LGPL 义务说明（重要）

本项目包含三个以 **LGPL-2.1-or-later** 许可分发的原生库：

| 组件 | 用途 | 上游 |
|---|---|---|
| **libmpv**（mpv-android 封装） | WMV / ASF / RM / RMVB 等容器的兜底解码内核 | https://github.com/mpv-player/mpv |
| **FFmpeg**（libavcodec / libavformat / libavutil） | 容器解析、软解、无重编码转封装 | https://ffmpeg.org |
| **ijkplayer** | 第三方解码内核选项 | https://github.com/bilibili/ijkplayer |

**它们不是 GPL**：libmpv 与 FFmpeg 均为未启用 `--enable-gpl` 的 LGPL 构建
（已核对：`libmpv.so` 内含 "GNU Lesser General Public" 声明；FFmpeg 侧无 `libx264.so` 依赖、
无 `x264_encoder_*` / `ff_libx264_encoder` 符号，`.so` 中出现的 "x264" 仅是 H.264 码流解析 x264 SEI 的文本）。
因此本项目可以继续以 Apache-2.0 发布自有代码，但需履行 LGPL 的三项义务：

1. **随附许可文本**：LGPL-2.1 全文在应用内「开源许可」页展示，并在
   https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html 提供。
2. **提供源码获取方式**：上述三个上游仓库地址即为源码位置；本项目**未修改**这些库的源码，
   使用的是其公开预编译产物（`third_party/ffmpeg-libs/`、`app/libs/ijkplayer-k0.8.9-release.aar`、
   `io.github.marlboro-advance:mpv-android:1.0.0`）。若需重新构建，按各上游的构建说明即可。
3. **允许用户替换库**：这些库以**独立的动态库文件（`.so`）**形式链接，未做静态链接或代码内联；
   用户可自行重新打包 APK 替换 `libmpv.so` / `libavcodec.so` 等文件。

> 说明：`io.github.marlboro-advance:mpv-android` 的 POM 声明 MIT，但该声明仅覆盖其 Java 封装层；
> **libmpv 本体按其上游以 LGPL 分发**，本文档按更严格的那一侧处理。

---

## 二、组件清单

### 2.1 解码与渲染内核

| 组件 | 版本 / 来源 | 许可 | 用途 |
|---|---|---|---|
| libmpv | mpv-android 1.0.0 | LGPL-2.1-or-later | MPV 内核（WMV/ASF/RM/RMVB） |
| FFmpeg | third_party/ffmpeg-libs（arm64-v8a） | LGPL-2.1-or-later | 解析 / 软解 / 转封装 |
| ijkplayer | k0.8.9（debugly 构建） | LGPL-2.1 | IJK 内核 |
| GPUPixel | third_party/gpupixel（源码内嵌） | Apache-2.0 | GPUPixel 美颜引擎 |

### 2.2 原生推理

| 组件 | 许可 | 说明 |
|---|---|---|
| llama.cpp / libmtmd | MIT | 端侧 LLM 推理（`jniLibs/arm64-v8a/libllama.so` 等） |
| sherpa-onnx | Apache-2.0 | ASR / VAD / 说话人相关 |
| ONNX Runtime | MIT | 性别分类 / 源分离等 ONNX 模型推理 |

### 2.3 Android / Java 依赖（共 181 项）

完整清单见 `app/src/main/assets/licenses.json`（由 `scripts/gen_licenses.py` 从 Gradle 依赖树生成），
并在应用内「开源许可」页逐条展示。许可分布如下：

| 许可 | 条目数 |
|---|---:|
| Apache License 2.0（含三种写法） | 155 |
| Android SDK License | 13 |
| MIT | 3 |
| LGPL-2.1 / LGPL-2.1-or-later | 3 |
| Common Public License 1.0 | 1 |
| Bouncy Castle Licence | 1 |
| GPL-2.0 with Class-Path Exception（`checker-compat-qual`，仅编译期注解） | 1 |
| 字体与自研资源登记项 | 4 |

其中需要留意的 copyleft 项：

- `jlayer`（LGPL-2.1）、`jcifs-ng`（LGPL）、`jna`（LGPL-2.1-or-later / Apache-2.0 双许可）
  —— 均为 Java 库，以动态加载方式使用，不修改其源码；用户可用同名 jar 替换。
- `checker-compat-qual` 是**编译期注解库**，不进入运行时产物，且带 Class-Path Exception。

### 2.4 模型

| 模型 | 随包分发 | 许可 | 商用条款 |
|---|---|---|---|
| Dolphin ASR（`assets/dolphin/model.int8.onnx`，约 103 MB） | 是 | **Apache-2.0** | ✅ 允许商用；需保留版权与许可声明（核实于 2026-10-18） |
| MiniCPM-V 4.7 1B（GGUF） | 否，用户端下载 | **Apache-2.0** | ✅ 允许商用；无额外登记要求（核实于 2026-10-18） |
| MediaPipe Face Landmarker（`face_landmarker.task`） | 是 | Apache-2.0 | 允许商用 |
| Silero VAD（`silero_vad.onnx`） | 是 | MIT | 允许商用 |

> 核实过程与来源见 `docs/MODEL_LICENSES.md`。
> ⚠️ MiniCPM **4.7 之前的版本**使用自定义 "MiniCPM Model License"（商用需登记），
> 若替换为旧版模型必须重新核实。

### 2.5 字体

> v2.6.4：已移除 MiSans（省约 20 MB），仅保留 OPPO Sans。

| 字体 | 文件 | 授权要点 |
|---|---|---|
| OPPO Sans | `res/font/oppo_sans_4_0.ttf` | OPPO「免费商用」授权：条件同上 |

> 两家授权均以厂商官方发布的条款为准；如后续替换为其它字体，请同步更新本表。

### 2.6 LUT 预设（42 个 `.cube`）

| 编号 | 数量 | 来源 | 许可 |
|---|---:|---|---|
| 01–12 | 12 | **来源未登记**（见 `docs/LUT_SOURCES.md` 的待补清单） | 待确认 |
| 19–24 | 6 | `t0saki/lumix-original-looks` | MIT |
| 25–30 | 6 | `scernst13/HaldCLUT-Cube-Files` | CC0-1.0 |
| 31–42 | 12 | `t0saki/lumix-original-looks` | MIT |
| 43–48 | 6 | 本项目自研生成 | 随本项目 Apache-2.0 |

全部 42 个文件仅做**格式规范化**（重写 TITLE、统一换行、统一小数位），数值未做改动。
逐条台账见 `docs/LUT_SOURCES.md`。

---

## 三、商标声明

本项目使用的产品名与商标——包括但不限于 **Huawei VR Glass**、**Pico**、**Quest**、**OpenAI**、
**Bilibili（B 站）**、**MiSans**、**OPPO Sans**、**Dolby / DTS**（如涉及）——均为其各自所有者的商标或注册商标，
本项目仅在**描述兼容性 / 文件格式**的意义上使用，**不代表任何形式的隶属、合作、赞助或背书**。

应用名称中的 "Aura" 为本项目自有命名，不主张与任何同名第三方产品存在关联。

---

## 四、第三方服务与内容声明

完整版见 **`docs/CONTENT_AND_SERVICE_DISCLAIMER.md`**，要点：

### 4.1 播放内容

本应用是**纯播放工具**，不提供、不内置、不分发任何音视频内容；播放的内容（本地文件 / 网络地址 /
SMB / DLNA 推送）均由用户自行提供，**用户须确保拥有合法权利并自行承担相应责任**。
字幕转写、翻译、AI 弹幕、截图与录制等衍生内容同样由用户负责。

### 4.2 Bing 翻译

「字幕在线翻译」的 Bing 引擎调用的是 **Bing 网页版翻译端点**（`cn.bing.com/ttranslatev3`），
**不是 Microsoft 官方 API**。本项目与 Microsoft Corporation **无关联、未获授权与背书**；
"Bing / Microsoft" 为其各自所有者的商标，仅用于描述所调用服务。

⚠️ 该端点面向浏览器交互设计，第三方程序的自动化调用**可能不符合 Microsoft 服务条款**，
且其可用性随时可能变化。**商业分发或大规模使用建议改用有明确授权的官方接口**
（如 Azure AI Translator）。启用该引擎时字幕文本会发送至该第三方端点，请勿翻译敏感内容。

### 4.3 用户自配置的 AI 服务

应用可连接用户自行配置的 OpenAI 兼容接口（含第三方聚合网关）。
这些请求由用户发起、使用用户自己的凭据，须遵守对应服务商的服务条款与隐私政策；
本项目不代用户上传数据，也不对第三方服务的内容与可用性作出承诺。

---

## 五、更正与联系

若您是权利人，认为本项目的某处使用不当或遗漏了署名，请通过仓库 issue 联系维护者，
我们会在核实后尽快更正或移除相关资源。

- 仓库：https://github.com/tianhuoliuhun/Aura-face-VR-Player
- 相关文档：`NOTICE`、`docs/MODEL_LICENSES.md`、`docs/LUT_SOURCES.md`、`docs/PRIVACY_AND_LICENSES.md`
