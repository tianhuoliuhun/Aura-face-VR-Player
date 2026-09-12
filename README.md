# 🎬 Aura美颜VR播放器 / Aura face VR Player

> 一款面向移动端的**专业级美颜 VR 播放器**：支持 360°/180° 全景、鱼眼、3D SBS/TAB 立体视频，
> 内置实时 AI 人脸美颜、3D LUT 电影调色、离线语音转字幕、多引擎在线翻译与局域网 SMB 播放。
>
> A professional mobile VR player with real-time AI beauty filters, 3D LUT color grading,
> offline ASR subtitles, multi-engine online translation and LAN (SMB) playback.

![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B-green) ![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-purple) ![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-blue) ![License](https://img.shields.io/badge/License-Apache%202.0-blue)

---

## ✨ 核心功能 / Features

### 🥽 VR 播放能力 / VR Playback
- 多投影模式：标准平面 / 鱼眼 / 360° 球面 / 180° 穹幕，一键切换
  - Projection modes: Standard / Fisheye / 360° Sphere / 180° Dome
- 3D 立体支持：Side-by-Side（左右）与 Top-and-Bottom（上下）3D 视频
  - 3D stereo: Side-by-Side and Top-and-Bottom formats
- 体感操控：陀螺仪视角跟随，支持手动偏移、重置视角中心
  - Gyro control: head-tracking view, manual offset, recenter
- 触控交互：单指拖曳查看、双指缩放、捏合旋转，UI 误操作 2 秒自动隐藏
  - Touch: drag to look around, pinch to zoom, UI auto-hides after 2s idle
- 曲面沉浸：圆柱面曲率可调，双中心变形（Warp Dual Center）优化
  - Immersive: adjustable cylinder curvature, dual-center warp distortion

### ✨ 实时 AI 美颜 / Real-time AI Beauty（GLES 着色器）
- 通用美颜：磨皮（双边滤波）、美白、亮度/对比度微调——2D/3D 模式均生效
  - General beauty: skin smoothing (bilateral filter), whitening, brightness/contrast — works in 2D/3D
- 2D 人像精修（MediaPipe 468 点面部关键点）：瘦脸、大眼、去黑眼圈、鼻梁塑形、嘴型调整、牙齿美白、口红、腮红、眉毛
  - 2D portrait retouch (MediaPipe 468 landmarks): face slimming, big eyes, dark-circle removal, nose shaping, mouth adjust, teeth whitening, lipstick, blush, eyebrows
- 美颜预设：自然 / 淡妆 / 浓妆 / 自定义，支持对比原图（一键关美颜）
  - Presets: Natural / Light / Heavy / Custom; one-tap before/after compare
- 纯 Shader 实现（v102 起移除 GPUPixel），低功耗、零额外库体积
  - Pure shader pipeline since v102 (GPUPixel removed) — low power, zero extra libs

### 🎨 3D LUT 电影调色 / LUT Color Grading
> ⚠️ **开发中，尚未实现 / Under development, NOT yet implemented**
- 12 款内置 LUT（经典青橙、电影暗调、柔和胶片、日系清新、暖阳日落、冷蓝夜色、复古胶片、赛博朋克、黑白电影、强烈青橙、柔和青绿、高对比）——LUT 资源已内置，但滤镜应用链路尚未实现/未生效
  - 12 bundled LUTs (assets included, but filter pipeline NOT yet implemented / NOT working)
- 手机自选 LUT（导入任意 `.cube` 文件）——**未实现** / Import custom .cube — **not implemented**
- 强度调节——**未实现** / Intensity control — **not implemented**
- 全部 LUT 由项目自研脚本（numpy）程序化生成，无第三方版权
  - All LUTs are self-generated via numpy scripts (no third-party copyright)

### 🗣️ 字幕与语音转写 / Subtitles & ASR
- 离线语音识别：支持多引擎
  - **Vosk**（Kaldi 架构）：中/英/日，模型按需下载（40MB~1.1GB），断点续传
  - **Qwen3-ASR 0.6B**（sherpa-onnx）：29 语言 + 20 种方言，CPU 推理，~940MB 模型
  - **SenseVoice QNN**（高通骁龙 NPU 加速）：中英日韩粤，SM8850 专属，~241MB 模型
  - Offline speech recognition: multi-engine — Vosk (Kaldi), Qwen3-ASR (sherpa-onnx, CPU), SenseVoice QNN (Qualcomm NPU, SM8850)
- 整片转写：后台生成带时间轴的 SRT 字幕（静音断句 + 标点断句 + 14 字智能换行）
  - Full-video transcription to timed SRT (silence/punctuation segmentation, 14-char line wrap)
- ASR 语言选择：自动 / 中文 / 英文 / 日文 / 韩文
  - ASR language: Auto / Chinese / English / Japanese / Korean
- 模型下载进度提示、断点续传、3 次重试
  - Download progress, resume on interruption, 3 retries
- 字幕样式：字体/字号/位置/描边自定义，内置 MiSans、OPPO Sans 等中文字体
  - Subtitle styles: font/size/position/outline customizable; bundled MiSans / OPPO Sans

### 🌐 字幕在线翻译 / Online Translation
- 多引擎：DeepSeek / 通义千问 / 智谱 GLM / MiniMax / OpenAI GPT / 必应翻译（免费端点）
  - Engines: DeepSeek / Qwen / Zhipu GLM / MiniMax / OpenAI GPT / Bing (free endpoint)
- 必应翻译参考 [plainheart/bing-translate-api](https://github.com/plainheart/bing-translate-api)（MIT，自研 Kotlin HTTP 实现，未直接引入 npm 包）
  - Bing translation inspired by [plainheart/bing-translate-api](https://github.com/plainheart/bing-translate-api) (MIT; self-written Kotlin HTTP, npm package NOT bundled)
- 用户自配 API Key（LLM 引擎），结果本地缓存，避免重复请求
  - LLM engines require user-provided API keys; results cached locally

### 📁 局域网播放 / LAN Playback
- SMB 协议（jcifs-ng）：浏览局域网共享、直连播放 NAS/PC 视频
  - SMB (jcifs-ng) browsing & direct playback from NAS/PC
- 支持本地文件、流媒体地址多来源
  - Local files and stream URLs supported

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
│  智能模块 / Intelligence                             │
│  MediaPipe Face Landmarker（人脸关键点 468 点）      │
│  Vosk / Qwen3-ASR / SenseVoice QNN（多引擎 ASR）     │
│  多引擎字幕翻译                                       │
│  Room 持久化（设置记忆/字幕缓存）                   │
└─────────────────────────────────────────────────────┘
```

### 关键组件 / Key Components

| 模块 | 技术 | 说明 |
|---|---|---|
| `VRGLRenderer.kt` | OpenGL ES 2.0 Shader | 核心渲染：投影、变形、美颜、LUT、合成（1495 行） |
| `VRPlayerScreen.kt` | Compose | 播放器主界面 + 设置面板（5000+ 行） |
| `MediaPipeFaceManager.kt` | MediaPipe Tasks | 468 点人脸关键点检测（arm64 真机） |
| `SherpaAsrManager.kt` | sherpa-onnx | Qwen3-ASR / SenseVoice QNN 离线识别引擎管理 |
| `AsrBatchTranscriber.kt` | 多引擎 | 批量字幕转写（Vosk / Qwen3 / SenseVoice 三引擎路由） |
| `LutUtils.kt` | 自研 | .cube 解析 + 三线性重采样 + 512×512 网格打包 |
| `SubtitleTranslator.kt` | 自研多引擎 | 字幕翻译（6 种引擎可切换） |

---

## 📂 目录结构 / Directory Layout

```
Aura-face-VR-Player/
├── app/
│   ├── build.gradle.kts            # 构建配置（版本/签名/ABI 分包/依赖）
│   ├── libs/
│   │   └── sherpa-onnx-1.13.6.aar  # sherpa-onnx ASR 引擎
│   └── src/main/
│       ├── java/com/example/vr/   # Kotlin 源码
│       ├── assets/
│       │   ├── luts/              # 12 款内置 3D LUT（.cube，未启用）
│       │   ├── face_landmarker.task  # MediaPipe 人脸模型
│       │   └── licenses.json      # 开源许可清单（自动生成）
│       ├── jniLibs/arm64-v8a/     # QNN 加速库（15 个 .so，SM8850 专属）
│       └── res/                   # 资源与字体（MiSans/OPPO Sans）
├── gradle/libs.versions.toml      # 依赖版本目录
├── scripts/gen_licenses.py        # 许可清单生成脚本
├── LICENSE                        # Apache License 2.0
├── RELEASE_SIGNING.md             # 签名与发布流程
├── FIREBASE_ANALYTICS.md          # 统计接入说明
└── README.md
```

---

## 📱 兼容性 / Compatibility

**平台**：本应用为 **Android 手机/平板应用**（非 iOS / 桌面 / Web）
**Platform**: Android **smartphone/tablet** app (not iOS / desktop / web)

**支持安卓版本 / Android Versions**：
- **minSdk 24（Android 7.0 Nougat）** — 最低支持 Android 7.0
- **targetSdk 36（Android 16）** — 针对最新系统适配
- 推荐 Android 10+ / Android 10+ recommended

**ABI 分包发布 / Split APKs**：

| ABI | 大小 / Size | 说明 / Notes |
|---|---|---|
| **arm64-v8a** | ~243 MB | 主流真机（推荐），含 QNN 加速库 / Mainstream devices, includes QNN libs |
| **armeabi-v7a** | ~93 MB | 旧款 32 位设备 / Older 32-bit devices |
| **x86_64** | ~99 MB | PC 模拟器 / PC emulators |
| **x86** | ~121 MB | 旧模拟器 / Legacy emulators |

> 请根据设备选择对应 ABI 的 APK 下载。真机用户请选择 arm64-v8a 版本。
> Choose the APK matching your device ABI. Device users: choose arm64-v8a.

---

## 🔧 构建 / Build

### 环境要求 / Requirements
- Android Studio（含 JDK 17+）
- Android SDK（compileSdk 36, minSdk 24, targetSdk 36）
- Gradle 9.6.1（或使用项目内置 wrapper）

### 构建命令 / Commands

```powershell
# Debug 包（开发测试）
gradlew.bat assembleDebug

# Release 包（正式分发，必须！见 RELEASE_SIGNING.md）
# 生成 4 个分包 APK（arm64/armv7/x86_64/x86）
gradlew.bat assembleRelease

# 依赖许可证清单导出
gradlew.bat :app:dumpDependencies
python scripts/gen_licenses.py
```

> ⚠️ **正式分发只允许 Release 包**：Release 使用项目私有签名（`my-upload-key.jks`），
> Debug 包使用公开的 Android debug key（密码 `android`），外发 Debug 包可被任何人重签伪造更新。
>
> ⚠️ **Only release APKs for distribution**: debug keys use the publicly known password `android`.

---

## 🔒 隐私与数据统计 / Privacy & Analytics

- **本地优先**：视频播放、美颜、LUT、离线语音转写均在设备本地完成
  - Local-first: playback, beauty, LUT, and offline ASR all run on-device
- **可选匿名统计（Firebase Analytics，免费）**：仅在你**首次启动明确同意后**才采集设备型号/系统版本/启动与活跃次数；拒绝或随时关闭后不再采集
  - Optional anonymous analytics (Firebase Analytics, free): collects device model / OS version / launches & active counts **only after you explicitly agree**; can be disabled anytime
- **云端数据（可选）**：字幕翻译（用户自配 API Key）、ASR 模型下载（Vosk/Qwen3/SenseVoice）、Firebase 统计
  - Optional cloud data: subtitle translation (user-provided API keys), ASR model download, Firebase analytics
- **不采集**：任何个人身份信息、视频内容、字幕内容
  - Never collected: personal identity, video content, subtitle content
- 接入说明 / Integration guide: [FIREBASE_ANALYTICS.md](FIREBASE_ANALYTICS.md)

---

## 📦 依赖与开源许可 / Dependencies & Licenses

本项目基于 Google AI Studio 生成的项目骨架，核心功能均为自研实现。
Built on a Google AI Studio generated skeleton; core features are self-developed.

| 依赖 / Dependency | 许可证 / License | 用途 / Usage |
|---|---|---|
| Jetpack Compose / Material3 | Apache-2.0 | UI 框架 |
| Media3 ExoPlayer / Transformer | Apache-2.0 | 播放内核 |
| MediaPipe Tasks Vision | Apache-2.0 | 人脸关键点 |
| Vosk (Kaldi) | Apache-2.0 | 离线语音识别 |
| sherpa-onnx | Apache-2.0 | Qwen3-ASR / SenseVoice QNN 识别 |
| Retrofit / OkHttp / Moshi | Apache-2.0 | 网络与 JSON |
| jcifs-ng | LGPL-2.1 | SMB 局域网播放 |
| JNA | LGPL-2.1 / Apache-2.0 | 原生库桥接 |
| Room | Apache-2.0 | 本地持久化 |
| [bing-translate-api](https://github.com/plainheart/bing-translate-api)（参考） | MIT | 必应翻译免费端点（自研 Kotlin 实现） |

**资源 / Resources**：MiSans / OPPO Sans 字体（免费商用授权）、MediaPipe 模型（Apache-2.0）、12 款 LUT（项目自研 numpy 脚本生成，无第三方版权）。

完整 141 项许可清单见应用内「设置 → 关于与开源许可」或 `app/src/main/assets/licenses.json`。

---

## ⚠️ 已知问题 / Known Issues

| # | 中文 | English |
|---|---|---|
| 1 | **LUT 视频滤镜尚未实现**——LUT 资源已内置，但滤镜应用链路未实现/未生效 | **LUT filter NOT implemented** — assets bundled, filter pipeline not working yet |
| 2 | **陀螺仪漂移**——长时间观看后视角缓慢漂移，需手动重置 | **Gyroscope drift** — view drifts slowly over long sessions; manual recenter needed |
| 3 | **AI 字幕多行时间线可能不匹配**——断句/静音判断误差导致时间轴偏移 | **Multi-line ASR subtitle timing mismatch** — auto-generated timestamps may not perfectly align |
| 4 | **必应免费翻译端点风险**——非官方网页端点，随时可能失效 | **Bing free endpoint risk** — unofficial web endpoint may break anytime; LLM API keys recommended |

---

## 📜 版本历史 / Changelog

| 版本 / Version | 更新内容 / Changes |
|---|---|
| v86 | ASR 重构：移除实时识别，专注整片转写 / ASR refactor: batch transcription focus |
| v90 | 设置面板二级菜单 / Settings accordion groups |
| v91 | 字幕模块提级为主入口 / Subtitles promoted to main entry |
| v94 | 快捷面板点击外部关闭 / Quick panel closes on outside tap |
| v100 | 字幕智能断句换行 / Smart subtitle line-breaking |
| v101 | 转写断句+换行+时间：超时/标点断句、SRT 14 字换行 / SRT segmentation + 14-char wrapping |
| v102 | 移除 GPUPixel，改用纯 Shader 美颜 / GPUPixel removed, pure shader beauty |
| v103 | 悬浮球拖动不误触，速度提示条 1 秒 / Floating ball drag UX fixes |
| v104 | LUT 视频滤镜框架接入（资源+框架，链路未完成）/ LUT filter scaffolding (assets + framework; pipeline incomplete) |
| v105 | LUT slice 计算修复（仍未生效）/ LUT slice fix (still not working) |
| v106 | 开源许可声明页 + Release 签名流程 / Open-source licenses page + Release signing flow |
| v107 | Firebase Analytics 用户统计（免费，隐私弹窗）/ Firebase Analytics (free, privacy consent) |
| v108 | Vosk 模型下载进度提示 + 3 次重试 / Vosk model download progress + 3 retries |
| v109 | 下载进度 UI 优化 / Download progress UI improvements |
| v110 | 新增 Qwen3-ASR 引擎（sherpa-onnx）+ 引擎选择 UI / New Qwen3-ASR engine (sherpa-onnx) + engine selector UI |
| v111 | SenseVoice QNN 引擎 + ASR 语言选择（中英日韩）+ ABI 分包发布 / SenseVoice QNN engine + ASR language selector + ABI split APKs |

---

## 🗺️ 未来规划 / Roadmap

- [ ] **实现并验证 LUT 滤镜链路**（UI → 纹理 → 着色器采样）/ Implement & validate LUT filter pipeline
- [ ] 修复陀螺仪漂移 / Fix gyro drift (sensor-fusion attitude estimation)
- [ ] 字幕时间轴对齐优化 / Subtitle timing alignment (VAD/endpoint calibration)
- [ ] 人脸关键点 x86_64 支持 / x86_64 face-landmark support (emulator beauty)
- [ ] 更多投影模式（CAVE / 半球）/ More projection modes (CAVE / hemisphere)
- [ ] 字幕样式模板 / Subtitle style templates
- [ ] 播放列表与历史记录同步 / Playlist & history sync
- [ ] 国际语言包 / i18n language packs

---

## 📄 许可声明 / License Notice

本项目采用 **Apache License 2.0** 开源（见 [LICENSE](LICENSE)），Copyright © 2026 tianhuoliuhun。
可自由使用、修改、商用与再分发（保留版权与许可声明即可）。
内置字体（MiSans/OPPO Sans）遵循各自授权条款（免费商用但禁止修改），
第三方依赖遵循各自许可证（见上方清单与 `app/src/main/assets/licenses.json`）。

Licensed under the **Apache License, Version 2.0** (see [LICENSE](LICENSE)). Copyright © 2026 tianhuoliuhun.
You may use, modify, distribute and commercially use the code freely, provided that
the copyright and license notices are retained. Bundled fonts (MiSans / OPPO Sans)
are subject to their own terms (free for commercial use but modification prohibited);
third-party dependencies remain under their respective licenses.

---

## ⚠️ 免责声明 / Disclaimer

> **此项目（Aura face VR Player）是个人为了兴趣而开发，仅用于学习和测试，请于下载后 24 小时内删除。**
> 所用 API 皆从官方网站收集，不提供任何破解内容。
>
> *This project (Aura face VR Player) is developed for personal interest,
> intended for learning and testing purposes only. Please delete it within 24 hours after download.
> All APIs used are collected from official websites. No cracked content is provided.*

---

## 🔗 使用的开源项目 / Open Source Dependencies

### 播放与渲染 / Playback & Rendering
- **ExoPlayer / Media3**：Google 视频播放框架（Apache-2.0）
- **MediaPipe**：Google 机器学习框架，人脸关键点（Apache-2.0）

### 语音识别 / Speech Recognition
- **Vosk**：离线语音识别引擎（Apache-2.0）— https://github.com/nicehash
- **sherpa-onnx**：Qwen3-ASR / SenseVoice QNN 离线识别（Apache-2.0）— https://github.com/k2-fsa/sherpa-onnx

### 网络与数据 / Networking & Data
- **Retrofit**：https://github.com/square/retrofit — HTTP 客户端（Apache-2.0）
- **OkHttp**：https://github.com/square/okhttp — HTTP 引擎（Apache-2.0）
- **Moshi**：https://github.com/square/moshi — JSON 解析（Apache-2.0）
- **jcifs-ng**：https://github.com/agno3/jcifs-ng — SMB 局域网播放（LGPL-2.1）

### UI 框架 / UI Framework
- **Jetpack Compose**（androidx）：响应式 UI（Apache-2.0）
- **backdrop**：Android 液态玻璃效果（Apache-2.0）

### 翻译参考 / Translation Reference
- **bing-translate-api**：https://github.com/plainheart/bing-translate-api — 必应翻译封装（MIT，仅参考接口协议）

### 基础库 / Foundation Libraries
- **Kotlin**：https://github.com/JetBrains/kotlin（Apache-2.0）
- **Firebase Android SDK**：统计与分析（Apache-2.0）— https://github.com/nicehash
- **Guava**：Google 核心工具库（Apache-2.0）— https://github.com/nicehash
- **Room**：本地数据库（Apache-2.0）— https://github.com/nicehash
- **Bouncy Castle**：加密算法库（MIT）— https://github.com/nicehash
- **JNA**：Java 原生接口桥接（LGPL-2.1 / Apache-2.0）— https://github.com/nicehash
- **SLF4J**：日志门面（MIT）— https://github.com/nicehash

### 内置资源 / Bundled Resources
- **MiSans 字体**：小米免费商用字体（非开源，免费授权）
- **OPPO Sans 字体**：OPPO 官方 — 免费商用字体（非开源，免费授权）
- **12 款 3D LUT 调色预设**：项目自研（numpy 脚本生成，无第三方版权）

> 以上所有 API 与资源均来自官方网站或正规渠道，不涉及任何破解内容。
> All APIs and resources above are from official websites or legitimate channels. No cracked content is provided.
