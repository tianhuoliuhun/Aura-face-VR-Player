# 🎬 Aura美颜VR播放器 / Aura face VR Player

> 一款面向移动端的**专业级美颜 VR 播放器**：支持 360°/180° 全景、鱼眼、3D SBS/TAB 立体视频，
> 内置实时 AI 人脸美颜、3D LUT 电影调色、离线语音转字幕、多引擎在线翻译与局域网 SMB 播放。
>
> A professional mobile VR player with real-time AI beauty filters, 3D LUT color grading,
> offline ASR subtitles, multi-engine online translation and LAN (SMB) playback.

![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B-green) ![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-purple) ![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-blue) ![License](https://img.shields.io/badge/License-Apache%202.0-blue)

---

# 📖 中文版（Chinese）

## ✨ 核心功能 / Features

### 🥽 VR 播放能力 / VR Playback
- **多投影模式**：标准平面 / 鱼眼 / 360° 球面 / 180° 穹幕，一键切换
- **3D 立体支持**：Side-by-Side（左右）与 Top-and-Bottom（上下）3D 视频
- **体感操控**：陀螺仪视角跟随，支持手动偏移、重置视角中心
- **触控交互**：单指拖曳查看、双指缩放、捏合旋转，UI 误操作 2 秒自动隐藏
- **曲面沉浸**：圆柱面曲率可调，双中心变形（Warp Dual Center）优化

### ✨ 实时 AI 美颜 / Real-time AI Beauty（GLES 着色器方案）
- **通用美颜**：磨皮（双边滤波肤色平滑）、美白、亮度/对比度微调——2D/3D 模式均生效
- **2D 人像精修**（基于 MediaPipe 468 点面部关键点）：瘦脸、大眼、去黑眼圈、鼻梁塑形、嘴型调整、牙齿美白、口红、腮红、眉毛
- **美颜预设**：自然 / 淡妆 / 浓妆 / 自定义，支持对比原图（一键关美颜）
- 纯 Shader 实现（v102 起移除 GPUPixel 依赖），低功耗、零额外库体积

### 🎨 3D LUT 电影调色 / LUT Color Grading
> ⚠️ **开发中，尚未实现 / Under development, NOT yet implemented**

- 12 款内置 LUT（经典青橙、电影暗调、柔和胶片、日系清新、暖阳日落、冷蓝夜色、复古胶片、赛博朋克、黑白电影、强烈青橙、柔和青绿、高对比）——**LUT 资源已内置，但滤镜应用尚未实现/未生效**
- 手机自选 LUT（导入任意 `.cube` 文件）——**未实现**
- 强度调节——**未实现**
- 全部 LUT 由项目自研脚本（numpy）程序化生成，无第三方版权风险

### 🗣️ 字幕与语音转写 / Subtitles & ASR
- **离线语音识别**：Vosk 引擎（Kaldi 架构），支持中/英/日文模型，模型按需下载（40MB~1.1GB）
- **整片转写**：后台生成带时间轴的 SRT 字幕（静音断句 + 标点断句 + 14 字智能换行）
- **字幕样式**：字体/字号/位置/描边自定义，内置 MiSans、OPPO Sans 等中文字体

### 🌐 字幕在线翻译 / Online Translation
- 多引擎：DeepSeek / 通义千问 / 智谱 GLM / MiniMax / OpenAI GPT / 必应翻译（免费端点）
- 必应翻译实现思路参考 [plainheart/bing-translate-api](https://github.com/plainheart/bing-translate-api)（MIT，自研 Kotlin HTTP 实现，未直接引入 npm 包）
- 用户自配 API Key（LLM 引擎），按需调用；结果本地缓存，避免重复请求

### 📁 局域网播放 / LAN Playback
- **SMB 协议**（jcifs-ng）：浏览局域网共享、直连播放 NAS/PC 视频
- 支持本地文件、流媒体地址多来源

---

## 🏗️ 技术架构 / Architecture

```
┌─────────────────────────────────────────────────────┐
│  UI 层（Jetpack Compose + Material3）               │
│  VRPlayerScreen（播放器主界面/设置面板/快捷面板）     │
├─────────────────────────────────────────────────────┤
│  渲染层（GLSurfaceView + 自定义 GLES 着色器管线）     │
│  VRGLRenderer：投影变形/立体映射/美颜/LUT/字幕叠加     │
├─────────────────────────────────────────────────────┤
│  播放内核（Media3 ExoPlayer + Transformer）          │
│  硬解 8K、变速播放、音轨/字幕轨选择                  │
├─────────────────────────────────────────────────────┤
│  智能模块                                          │
│  MediaPipe Face Landmarker（人脸关键点 468 点）      │
│  Vosk ASR（离线转写） + 多引擎翻译                   │
│  Room 持久化（设置记忆/字幕缓存）                   │
└─────────────────────────────────────────────────────┘
```

### 关键组件 / Key Components

| 模块 Module | 技术 Tech | 说明 Description |
|---|---|---|
| `VRGLRenderer.kt` | OpenGL ES 2.0 Shader | 核心渲染：投影、变形、美颜、LUT、合成（1495 行） |
| `VRPlayerScreen.kt` | Compose | 播放器主界面 + 设置面板（5000+ 行） |
| `MediaPipeFaceManager.kt` | MediaPipe Tasks | 468 点人脸关键点检测（arm64 真机） |
| `LutUtils.kt` | 自研 | .cube 解析 + 三线性重采样 + 512×512 网格打包 |
| `VoskAsrEngine.kt` / `AsrBatchTranscriber.kt` | Vosk | 离线语音转字幕 |
| `SubtitleTranslator.kt` | 自研多引擎 | 字幕翻译（6 种引擎可切换） |

---

## 📂 目录结构 / Directory Layout

```
GfaceVRplayer/
├── app/
│   ├── build.gradle.kts          # 应用构建配置（版本/签名/依赖）
│   └── src/main/
│       ├── java/com/example/vr/ # Kotlin 源码
│       ├── assets/
│       │   ├── luts/            # 12 款内置 3D LUT（.cube，未启用）
│       │   ├── face_landmarker.task  # MediaPipe 模型
│       │   └── licenses.json    # 开源许可清单（自动生成）
│       └── res/                 # 资源与字体（MiSans/OPPO Sans）
├── gradle/libs.versions.toml    # 依赖版本目录
├── scripts/gen_licenses.py      # 许可清单生成脚本
├── RELEASE_SIGNING.md           # 签名与发布流程
└── README.md
```

---

## 📱 兼容性 / Compatibility

**平台**：本应用为 **Android 手机/平板应用**（非 iOS / 桌面 / Web）。

**支持安卓版本**：
- **minSdk 24（Android 7.0 Nougat）** —— 最低支持 Android 7.0
- **targetSdk 36（Android 16）** —— 针对最新系统适配
- 推荐 Android 10+ 以获得最佳性能

**芯片架构（ABI）**：

| ABI | 支持度 | 说明 |
|---|---|---|
| **arm64-v8a** | ✅ 完整 | 主流真机（推荐），含 MediaPipe 人脸关键点美颜 |
| **armeabi-v7a** | ✅ 完整 | 旧款 32 位设备 |
| **x86_64 / x86** | ⚠️ 部分 | 模拟器可运行；MediaPipe 人脸关键点不可用（官方库未提供 x86_64 版本），其余功能正常 |

> 说明：APK 内含 7 种 ABI 的本地库，安装时 Android 会自动选择匹配当前设备的架构。

---

## 🔧 构建 / Build

### 环境要求 / Requirements
- Android Studio（含 JDK 17+）
- Android SDK（compileSdk 36, minSdk 24, targetSdk 36）
- Gradle 9.6.1（或使用项目内置配置）

### 构建命令 / Commands

```powershell
# Debug 包（开发测试）
gradlew.bat assembleDebug

# Release 包（正式分发，必须！见 RELEASE_SIGNING.md）
gradlew.bat assembleRelease

# 依赖许可证清单导出（生成 assets/licenses.json 数据源）
gradlew.bat :app:dumpDependencies
python scripts/gen_licenses.py
```

> ⚠️ **正式分发只允许 Release 包**：Release 使用项目私有签名（`my-upload-key.jks`），
> Debug 包使用公开的 Android debug key（密码 `android`），外发 Debug 包可被任何人重签伪造更新。

---

## 🔒 隐私与数据统计 / Privacy & Analytics

- **本地优先**：视频播放、美颜、LUT、离线语音转写均在设备本地完成
- **可选匿名统计（Firebase Analytics，免费）**：仅在你**首次启动明确同意后**才采集设备型号/系统版本/启动与活跃次数；拒绝或随时关闭后不再采集
- **云端数据（可选）**：字幕翻译（用户自配 API Key）、Vosk 语音模型下载、Firebase 统计
- **不采集**：任何个人身份信息（姓名/账号/联系方式）、视频内容、字幕内容
- 接入说明见 [FIREBASE_ANALYTICS.md](FIREBASE_ANALYTICS.md)

---

## 📦 依赖与开源许可 / Dependencies & Licenses

本项目基于 Google AI Studio 生成的项目骨架，核心功能均为自研实现。主要开源依赖：

| 依赖 Dependency | 许可证 License | 用途 Usage |
|---|---|---|
| Jetpack Compose / Material3 | Apache-2.0 | UI 框架 |
| Media3 ExoPlayer / Transformer | Apache-2.0 | 播放内核 |
| MediaPipe Tasks Vision | Apache-2.0 | 人脸关键点 |
| Vosk (Kaldi) | Apache-2.0 | 离线语音识别 |
| Retrofit / OkHttp / Moshi | Apache-2.0 | 网络与 JSON |
| jcifs-ng | LGPL-2.1 | SMB 局域网播放 |
| JNA | LGPL-2.1 / Apache-2.0 | 原生库桥接 |
| Room | Apache-2.0 | 本地持久化 |
| [bing-translate-api](https://github.com/plainheart/bing-translate-api)（参考实现） | MIT | 必应翻译免费端点（自研 Kotlin 实现） |

**资源 Resources**：MiSans / OPPO Sans 字体（免费商用授权）、MediaPipe 模型（Apache-2.0）、
12 款 LUT（项目自研生成，numpy 脚本，无第三方版权）。

完整 141 项许可清单见应用内「设置 → 关于与开源许可」或 `app/src/main/assets/licenses.json`。

---

## ⚠️ 已知问题 / Known Issues

> 以下问题为当前版本（v106）已知状态，请如实知悉：

1. **LUT 视频滤镜尚未实现 / LUT filter NOT implemented**
   - LUT 资源（12 款 .cube）已内置，但滤镜应用链路（UI → 纹理上传 → 着色器采样）**未实现/未生效**
   - The LUT assets are bundled, but the filter pipeline is not yet implemented / not working.
2. **陀螺仪漂移 / Gyroscope drift**
   - 长时间观看后视角存在缓慢漂移，需要定期手动重置视角中心
   - The view drifts slowly over long sessions; manual recenter is required periodically.
3. **AI 字幕多行生成时间线可能不匹配 / Multi-line ASR subtitle timing mismatch**
   - 语音转写生成的多行字幕，其时间轴可能与视频画面不完全对齐（断句/静音判断误差）
   - Auto-generated multi-line subtitles may have timing not perfectly aligned with the video.
4. **必应免费翻译端点风险 / Bing free endpoint risk**
   - 必应翻译使用非官方网页端点（cn.bing.com/ttranslatev3），可能随时失效或触发风控；建议使用自配 LLM API Key
   - The Bing translator uses an unofficial web endpoint that may break anytime; LLM API keys are recommended.

---

## 📜 版本历史 / Changelog

| 版本 | 内容 |
|---|---|
| v86 | ASR 重构：移除实时识别，专注整片转写 |
| v90 | 设置面板二级菜单（可折叠分组） |
| v91 | 字幕模块提级为主入口 |
| v94 | 快捷面板点击外部关闭 |
| v100 | 字幕智能断句换行（句末标点优先） |
| v101 | 转写断句+换行+时间：超时/标点断句、SRT 14 字换行 |
| v102 | 移除 GPUPixel，改用纯 Shader 美颜（性能与体积优化） |
| v103 | 悬浮球拖动不误触，速度提示条 1 秒 |
| v104 | LUT 视频滤镜（资源与框架接入，滤镜链路未完成） |
| v105 | LUT slice 计算修复（滤镜仍未生效，见已知问题） |
| v106 | 开源许可声明页 + Release 签名流程 + 许可证自动收集 |

---

## 🗺️ 未来规划 / Roadmap

- [ ] **实现并验证 LUT 滤镜链路**（UI → 纹理 → 着色器采样）
- [ ] 修复陀螺仪漂移（融合加速度计/磁力计姿态估计）
- [ ] 字幕时间轴对齐优化（端点检测/VAD 校准）
- [ ] 人脸关键点 x86_64 支持（模拟器美颜）
- [ ] 更多投影模式（CAVE / 半球）
- [ ] 字幕样式模板（预设主题）
- [ ] 播放列表与历史记录同步
- [ ] 国际语言包

---

## 📄 许可声明 / License Notice

本项目采用 **Apache License 2.0** 开源（见 [LICENSE](LICENSE)），
Copyright © 2026 tianhuoliuhun。
可自由使用、修改、商用与再分发（保留版权与许可声明即可）。
注意：内置字体（MiSans/OPPO Sans）遵循其各自授权条款（免费商用但禁止修改），
第三方依赖遵循其各自许可证（见上文清单与 `app/src/main/assets/licenses.json`）。
*Licensed under the Apache License, Version 2.0 (see [LICENSE](LICENSE)).*

---

## ⚠️ 免责声明

> **此项目（Aura face VR Player）是个人为了兴趣而开发，仅用于学习和测试，请于下载后 24 小时内删除。**
> 所用 API 皆从官方网站收集，不提供任何破解内容。

---

# 🌐 English Version

## ✨ Features

### 🥽 VR Playback
- **Projection modes**: Standard / Fisheye / 360° Sphere / 180° Dome
- **3D support**: Side-by-Side and Top-and-Bottom stereo videos
- **Gyro control**: head-tracking view, manual offset, recenter
- **Touch**: drag to look around, pinch to zoom, UI auto-hides after 2s
- **Immersive**: adjustable cylinder curvature, dual-center warp

### ✨ Real-time AI Beauty (GLES shader pipeline)
- General beauty: skin smoothing (bilateral filter), whitening, brightness/contrast — works in 2D/3D
- 2D portrait retouch (MediaPipe 468 landmarks): face slimming, big eyes, dark-circle removal, nose shaping, mouth adjust, teeth whitening, lipstick, blush, eyebrows
- Presets: Natural / Light / Heavy / Custom; one-tap before/after compare
- Pure shader since v102 (GPUPixel removed) — low power, no extra libs

### 🎨 LUT Color Grading
> ⚠️ **Under development — NOT yet implemented**

- 12 bundled LUTs (assets included, filter pipeline not working yet)
- Import custom .cube — **not implemented**
- Intensity control — **not implemented**
- All LUTs are self-generated via numpy scripts (no third-party copyright)

### 🗣️ Subtitles & ASR
- Offline speech recognition (Vosk/Kaldi), ZH/EN/JA models, downloaded on demand (40MB–1.1GB)
- Full-video transcription to timed SRT (silence/punctuation segmentation, 14-char line wrap)
- Customizable styles (font/size/position/outline), bundled MiSans / OPPO Sans

### 🌐 Online Translation
- Engines: DeepSeek / Qwen / Zhipu GLM / MiniMax / OpenAI GPT / Bing (free endpoint)
- Bing implementation inspired by [plainheart/bing-translate-api](https://github.com/plainheart/bing-translate-api) (MIT; self-written Kotlin HTTP, the npm package is NOT bundled)
- LLM engines require user-provided API keys; results cached locally

### 📁 LAN Playback
- SMB (jcifs-ng) browsing & direct playback from NAS/PC
- Local files and stream URLs

---

## 📱 Compatibility

**Platform**: Android **smartphone/tablet** app (not iOS / desktop / web).

**Android versions**:
- **minSdk 24 (Android 7.0 Nougat)** — minimum supported OS
- **targetSdk 36 (Android 16)** — adapted to the latest system
- Android 10+ recommended for best performance

**Chip architectures (ABI)**:

| ABI | Support | Notes |
|---|---|---|
| **arm64-v8a** | ✅ Full | Mainstream devices (recommended); includes MediaPipe face-landmark beauty |
| **armeabi-v7a** | ✅ Full | Older 32-bit devices |
| **x86_64 / x86** | ⚠️ Partial | Runs on emulators; MediaPipe face landmarks unavailable (official lib lacks x86_64), everything else works |

> The APK bundles native libraries for 7 ABIs; Android picks the matching one at install time.

---

## ⚠️ Known Issues (v106)

1. **LUT filter NOT implemented** — assets are bundled but the apply pipeline (UI → texture upload → shader sampling) does not work yet.
2. **Gyroscope drift** — view drifts slowly during long sessions; periodic manual recenter needed.
3. **Multi-line ASR subtitle timing may mismatch** — auto-generated subtitle timestamps may not perfectly align with the video.
4. **Bing free endpoint risk** — the Bing translator uses an unofficial web endpoint that can break anytime; LLM API keys are recommended.

---

## 🔒 Privacy & Analytics

- **Local-first**: playback, beauty, LUT and offline ASR all run on-device.
- **Optional anonymous analytics (Firebase Analytics, free)**: collects device model / OS version /
  launches & active counts **only after you explicitly agree** on first launch; can be disabled anytime.
- **Optional cloud data**: subtitle translation (user-provided API keys), Vosk model download,
  Firebase analytics.
- **Never collected**: personal identity, video content, subtitle content.
- Integration guide: [FIREBASE_ANALYTICS.md](FIREBASE_ANALYTICS.md)

---

## 📦 Dependencies & Licenses

| Dependency | License | Usage |
|---|---|---|
| Jetpack Compose / Material3 | Apache-2.0 | UI |
| Media3 ExoPlayer / Transformer | Apache-2.0 | Playback |
| MediaPipe Tasks Vision | Apache-2.0 | Face landmarks |
| Vosk (Kaldi) | Apache-2.0 | Offline ASR |
| Retrofit / OkHttp / Moshi | Apache-2.0 | Networking / JSON |
| jcifs-ng | LGPL-2.1 | SMB |
| JNA | LGPL-2.1 / Apache-2.0 | Native bridge |
| Room | Apache-2.0 | Persistence |
| bing-translate-api (inspired) | MIT | Bing free translation |

Resources: MiSans / OPPO Sans fonts (free commercial license), MediaPipe model (Apache-2.0), 12 self-generated LUTs.
Full 141-item license list: in-app “Settings → Open-Source Licenses” or `app/src/main/assets/licenses.json`.

---

## 📜 Changelog

| v86 | ASR refactor: batch transcription focus |
| v90 | Settings accordion groups |
| v91 | Subtitles promoted to main entry |
| v94 | Quick panel closes on outside tap |
| v100 | Smart subtitle line-breaking |
| v101 | SRT segmentation + 14-char wrapping |
| v102 | GPUPixel removed, pure shader beauty |
| v103 | Floating ball drag UX fixes |
| v104 | LUT filter scaffolding (assets + framework; pipeline incomplete) |
| v105 | LUT slice fix (still not working — see Known Issues) |
| v106 | Open-source licenses page + Release signing flow |

---

## 🗺️ Roadmap

- [ ] Implement & validate the LUT filter pipeline (UI → texture → shader)
- [ ] Fix gyro drift (sensor-fusion attitude estimation)
- [ ] Subtitle timing alignment (VAD/endpoint calibration)
- [ ] x86_64 face-landmark support (emulator beauty)
- [ ] More projection modes, subtitle style templates, playlist sync, i18n

---

## 📄 License Notice

Licensed under the **Apache License, Version 2.0** (see [LICENSE](LICENSE)).
Copyright © 2026 tianhuoliuhun.
You may use, modify, distribute and commercially use the code freely, provided that
the copyright and license notices are retained. Note that the bundled fonts
(MiSans / OPPO Sans) are subject to their own terms (free for commercial use but
modification prohibited); third-party dependencies remain under their respective
licenses (see the list above and `app/src/main/assets/licenses.json`).

---

## ⚠️ 免责声明 / Disclaimer

> **此项目（Aura face VR Player）是个人为了兴趣而开发，仅用于学习和测试，请于下载后 24 小时内删除。**
> 所用 API 皆从官方网站收集，不提供任何破解内容。
>
> *This project (Aura face VR Player) is developed for personal interest,
> intended for learning and testing purposes only. Please delete it within 24 hours after download.
> All APIs used are collected from official websites. No cracked content is provided.*

---

## 🔗 本项目使用的开源项目 / Open Source Dependencies

### 播放与渲染 / Playback & Rendering
- **ExoPlayer / Media3**： Google 视频播放框架（Apache-2.0）
- **MediaPipe**： Google 机器学习框架，人脸关键点（Apache-2.0）

### 语音识别 / Speech Recognition
- **Vosk**： 离线语音识别引擎（Apache-2.0）

### 网络与数据 / Networking & Data
- **Retrofit**：https://github.com/square/retrofit — HTTP 客户端框架（Apache-2.0）
- **OkHttp**：https://github.com/square/okhttp — HTTP 引擎（Apache-2.0）
- **Moshi**：https://github.com/square/moshi — JSON 解析（Apache-2.0）
- **jcifs-ng**：https://github.com/agno3/jcifs-ng — SMB 局域网播放（LGPL-2.1）

### UI 框架 / UI Framework
- **Jetpack Compose**（androidx）： 响应式 UI（Apache-2.0）
- **backdrop**： Android 液态玻璃效果（Apache-2.0）

### 图像处理 / Image Processing
- **android.graphics.path**： Android 图形路径库（Apache-2.0）

### 翻译参考 / Translation Reference
- **bing-translate-api**：https://github.com/plainheart/bing-translate-api — 必应翻译 API 封装（MIT，仅参考接口协议，未直接引入）

### 基础库 / Foundation Libraries
- **Kotlin**：https://github.com/JetBrains/kotlin — 编程语言（Apache-2.0）
- **Kotlin Coroutines**： 异步编程（Apache-2.0）
- **Firebase Android SDK**： 统计与分析（Apache-2.0）
- **Guava**： Google 核心工具库（Apache-2.0）
- **Protobuf**： 序列化（BSD-3-Clause）
- **Room**： 本地数据库（Apache-2.0）
- **Bouncy Castle**： 加密算法库（MIT）
- **JNA**： Java 原生接口桥接（LGPL-2.1 / Apache-2.0）
- **Auto Value**： 代码生成（Apache-2.0）
- **SLF4J**： 日志门面（MIT）
- **Flogger**： Google 日志框架（Apache-2.0）
- **Checker Framework**： 代码校验（MIT）
- **JSR 305**： 注解规范（Apache-2.0）
- **jspecify**： 空安全注解（Apache-2.0）
- **Accompanist**： Compose 辅助工具（Apache-2.0）

### 内置资源 / Bundled Resources
- **MiSans 字体**： 小米免费商用字体（非开源，免费授权）
- **OPPO Sans 字体**：OPPO 官方 — 免费商用字体（非开源，免费授权）
- **12 款 3D LUT 调色预设**：项目自研（numpy 脚本生成，无第三方版权）

> 以上所有 API 与资源均来自官方网站或正规渠道，不涉及任何破解内容。
