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
- 陀螺仪朝向模式：**手持横屏** / **VR 眼镜平放**两种轴向映射；另提供「反转陀螺仪转向」开关适配个别机型（v125）
  - Gyro orientation: handheld landscape / VR-box modes, plus a direction-inversion toggle (v125)
- 8K 硬解实验（默认关闭，设置内按需开启）：SPS level 适配、强制硬解选择器、分辨率头欺骗、缩小输出缓冲、补充解码参数注入、硬解失败自动切软解
  - Experimental 8K decoding (off by default): SPS level patch, forced hardware selector, resolution spoofing, downscaled output, codec param injection, auto software fallback
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
> ✅ **v1.0.117 起已生效**（修复 `.cube` 关键字解析后链路打通）
> Working since v1.0.117 — earlier versions parsed `LUT_3D_SIZE` incorrectly and failed silently.
- 12 款内置 LUT：经典青橙、电影暗调、柔和胶片、日系清新、暖阳日落、冷蓝夜色、复古胶片、赛博朋克、黑白电影、强烈青橙、柔和青绿、高对比
  - 12 bundled LUTs: Classic Teal-Orange, Cinematic Dark, Soft Film, JP Fresh, Warm Sunset, Cool Blue Night, Retro Film, Cyberpunk, B&W, Strong Teal-Orange, Soft Teal-Green, High Contrast
- 手机自选 LUT：可导入任意 `.cube` 文件（系统会弹出文件选择器）
  - Import any custom `.cube` file from your phone
- 强度调节：0–100% 混合强度滑杆，实时预览
  - Intensity slider (0–100%) with live preview
- 全部 LUT 由项目自研脚本（numpy）程序化生成，无第三方版权
  - All LUTs are self-generated via numpy scripts (no third-party copyright)

### 🗣️ 字幕与语音转写 / Subtitles & ASR
- 离线语音识别：**SenseVoice-Small**（sherpa-onnx，CPU int8，约 229MB）
  - 中/英/日/韩/粤 5 语言，自带标点，RTF 0.026；首次使用自动下载（含断点续传）
  - Offline ASR: SenseVoice-Small (sherpa-onnx, CPU int8, ~229MB) — zh/en/ja/ko/yue with punctuation
- **实时 AI 字幕**：边播边生成，不写临时文件
  - 独立解码音频（AudioTee）+ **Silero VAD** 分段 + 按优先级全局生成
  - 优先补当前播放点（**含前 5 秒回补**）及其后内容，再回头补齐其余；跳转后可即时命中已生成部分
  - 推理线程数可调（1–10，推荐 4–6）；字幕悬浮窗支持一键「重新生成」
  - Realtime subtitles generated while playing — independent audio decode + Silero VAD, priority-based global generation
- **字幕导出**：一键导出 SRT（直接由内存字幕缓存生成）
  - One-tap SRT export from the in-memory subtitle cache
- 整片转写：后台生成带时间轴的 SRT 字幕（静音断句 + 标点断句 + 14 字智能换行）
  - Full-video transcription to timed SRT (silence/punctuation segmentation, 14-char line wrap)
- 转写策略：离线模型按**语音段整段识别**（Silero VAD 断句：静音 0.5s 或单段满 8s），
  较逐块推理大幅减少推理次数
  - Segment-level offline inference (Silero VAD: 0.5s silence or 8s max per segment)
- 长句自动切分：单条超过 20 字或 5 秒时按标点拆成多条，按字数比例分配时间（无标点时按字数等分）
  - Results >20 chars or >5s are split by punctuation with proportional timing
- 翻译：**预读翻译**（提前翻译播放点前方 60 秒内的字幕，显示零等待）+ 磁盘缓存（换视频/重启后仍命中）
  - Translation: ahead-of-playback prefetch + on-disk cache
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
│  ＋ AsrBatchSection / PlayerControlBar /            │
│    BeautySettingsSections（v120–v121 按功能拆出）    │
├─────────────────────────────────────────────────────┤
│  渲染层（GLSurfaceView + 自定义 GLES 着色器管线）     │
│  VRGLRenderer：投影变形/立体映射/美颜/LUT/字幕叠加     │
├─────────────────────────────────────────────────────┤
│  播放内核（Media3 ExoPlayer + Transformer）          │
│  硬解 8K、变速播放、音轨/字幕轨选择                  │
├─────────────────────────────────────────────────────┤
│  智能模块 / Intelligence                             │
│  MediaPipe Face Landmarker（人脸关键点 468 点）      │
│  SenseVoice-Small + Silero VAD（实时字幕引擎）        │
│  多引擎字幕翻译                                       │
│  Room 持久化（设置记忆/字幕缓存）                   │
└─────────────────────────────────────────────────────┘
```

### 关键组件 / Key Components

| 模块 | 技术 | 说明 |
|---|---|---|
| `VRGLRenderer.kt` | OpenGL ES 2.0 Shader | 核心渲染：投影、变形、美颜、LUT、合成（1513 行） |
| `VRPlayerScreen.kt` | Compose | 播放器主界面 + 设置面板（4834 行，v120/v121 已按功能拆分） |
| `AsrBatchSection.kt` | Compose | 后台转写区块（设置面板与字幕快捷面板复用，v121 拆出，~500 行） |
| `PlayerControlBar.kt` | Compose | 播放控制栏三组按钮 + 宽窄屏自适应布局（v121 拆出） |
| `BeautySettingsSections.kt` | Compose | 美颜/模式提示/对比原图/预设等设置区块（v120–v121 拆出） |
| `VRPlayerComponents.kt` | Compose | 通用组件：`TooltipIconButton` / `BeautySliderItem` / `ExperimentalSwitchRow` |
| `MediaPipeFaceManager.kt` | MediaPipe Tasks | 468 点人脸关键点检测（arm64 真机） |
| `SherpaAsrManager.kt` | sherpa-onnx | SenseVoice 模型下载与识别器管理（线程数可配） |
| `RealtimeSubtitleEngine.kt` | 自研 | 实时字幕引擎：独立音频解码 + Silero VAD + 优先级调度 + seek 处理 |
| `SubtitleCache.kt` | 自研 | 字幕稀疏时间索引（TreeMap + 二分查找，O(log n)） |
| `SubtitleExporter.kt` | 自研 | SRT 导出（由内存字幕缓存生成） |
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
│       │   ├── luts/              # 12 款内置 3D LUT（.cube，v117 起生效，支持自选 .cube）
│       │   ├── face_landmarker.task  # MediaPipe 人脸模型
│       │   ├── silero_vad.onnx    # Silero VAD 语音活动检测（629KB）
│       │   └── licenses.json      # 开源许可清单（自动生成）
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

**发布包形态 / Release artifact**：

| 项目 | 说明 / Notes |
|---|---|
| 产物 | **单个全架构 APK**（`app-release.apk`，约 183MB）/ Single universal APK (~183MB) |
| 包含 ABI | `arm64-v8a` + `armeabi-v7a` + `x86_64` + `x86` 全包含 / All ABIs in one package |
| 安装 | 系统自动选取匹配 ABI 的原生库，无需挑选 / The OS picks the matching native libs |

> 说明：早期版本曾按 ABI 拆分为 4 个包发布，v116 起改为**单包全架构**发布，
> 省去用户判断设备 ABI 的麻烦（体积换易用性）。
> Note: early releases shipped 4 ABI-split APKs; since v116 we ship a single universal APK.

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
# 产物：app\build\outputs\apk\release\app-release.apk（单包全架构，约 183MB）
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
- **云端数据（可选）**：字幕翻译（用户自配 API Key）、ASR 模型下载（SenseVoice）、Firebase 统计
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
| 1 | **8K 硬解为实验功能**——默认关闭，需在「设置 → 8K 硬解实验」中按需开启，可能花屏或失败 | **8K decoding is experimental** — off by default; enable under Settings → 8K experiments; artifacts possible |
| 2 | **ASR 模型需先下载**——SenseVoice-Small 约 229MB，首次开启实时字幕前需联网下载 | **ASR model needs download** — SenseVoice-Small ~229MB, downloaded on first use |
| 3 | **陀螺仪漂移**——长时间观看后水平朝向缓慢漂移，双击画面重置视角即可（原理性，GAME_ROTATION_VECTOR 无绝对北向基准） | **Gyroscope drift** — yaw drifts slowly over long sessions; double-tap to recenter (inherent to game rotation vector) |
| 4 | **AI 字幕多行时间线可能不匹配**——断句/静音判断误差导致时间轴偏移 | **Multi-line ASR subtitle timing mismatch** — auto-generated timestamps may not perfectly align |
| 5 | **必应免费翻译端点风险**——非官方网页端点，随时可能失效 | **Bing free endpoint risk** — unofficial web endpoint may break anytime; LLM API keys recommended |

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
| v112 | 快捷面板语言同步 + 模型解压目录嵌套修复 + 中文大模型更新 / Quick-panel language sync, unzip path fix, larger ZH model |
| v113 | MPEG-L2 软件解码回退（兼容 K80 Pro 等机型）/ Software decode fallback for MPEG-L2 |
| v114 | 输出缓冲队列修复 / Output buffer queue fix |
| v115 | 音频提取重写为标准 MediaCodec / Audio extraction rewritten on MediaCodec |
| v116 | MPEG-L2 软件解码器回退策略完善 / Refined MPEG-L2 fallback (c2.android → OMX.google) |
| v117 | 修复 4 条 P0；切换解码设置不再重置视角 / 4 P0 fixes; decode setting no longer resets view |
| v118 | SenseVoice QNN 修复（ADSP 路径 + 运行库落盘 + SoC 自动匹配）/ SenseVoice QNN fixes |
| v119 | 修复 #7 播放位置恢复语义混乱 + #8 内嵌字幕跨媒体残留 / Playback-position restore + stale embedded subtitle fixes |
| v120 | VRPlayerScreen.kt 按功能拆分（5765 → 5372 行，纯重构）/ VRPlayerScreen split by feature (pure refactor) |
| v121 | 第二轮拆分：ASR 区块 / 控制栏 / 美颜小组件外置（累计 -963 行）/ Second split pass (cumulative −963 lines) |
| v122 | Qwen3-ASR 优化：离线模型改为按语音段整段识别，推理次数 -99.6% / Qwen3-ASR: segment-level inference (~99.6% fewer passes) |
| v123 | Vosk 转写优化（断句 reset / 400ms 喂入 / 模型缓存）+ 修复「模型不可用」死锁 / Vosk optimization + “model unavailable” deadlock fix |
| v124 | 修复 8K 输入缓冲被拒后退回 1MB（一帧都放不下）/ Fix 8K input buffer rejected → fallback to 1MB |
| v125 | 修复陀螺仪方向上下左右全部反向（另附转向反转开关）/ Fix inverted gyroscope direction (+ inversion toggle) |
| **v1.0.126** | **实时 AI 字幕落地**：边播边生成（独立解码 + Silero VAD + 优先级全局生成，当前点前 5 秒回补）/ 翻译预读 + 磁盘缓存 / 整片转写停用 / 只保留 SenseVoice 引擎（移除 Vosk·Qwen3·QNN 与 136MB QNN 运行库）/ 修复 SRT 导出 / 推理线程 1–10 可调 / 字幕重新生成 · **Realtime AI subtitles**: decode-on-the-fly with Silero VAD & priority scheduling, translation prefetch + disk cache, SenseVoice-only (Vosk/Qwen3/QNN removed), SRT export fix, 1–10 threads |

---

## 🗺️ 未来规划 / Roadmap

- [x] ~~**实现并验证 LUT 滤镜链路**（UI → 纹理 → 着色器采样）~~ ✅ v117 已完成 / Done in v117
- [ ] 修复陀螺仪漂移（方向问题已在 v125 修正，剩余为长时间 yaw 漂移）/ Fix gyro drift (direction fixed in v125; residual slow yaw drift)
- [ ] 字幕时间轴对齐优化 / Subtitle timing alignment (VAD/endpoint calibration)
- [ ] 人脸关键点 x86_64 支持 / x86_64 face-landmark support (emulator beauty)
- [ ] 更多投影模式（CAVE / 半球）/ More projection modes (CAVE / hemisphere)
- [ ] 字幕样式模板 / Subtitle style templates
- [ ] 播放列表与历史记录同步 / Playlist & history sync
- [ ] 国际语言包 / i18n language packs
- [ ] 8K 硬解实验转正（当前为实验功能，默认关闭）/ Graduate experimental 8K decoding

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
