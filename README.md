# 🎬 Aura美颜VR播放器 / Aura face VR Player

> 一款面向移动端的**专业级美颜 VR 播放器**：支持 360°/180° 全景、鱼眼、3D SBS/TAB 立体视频，
> 内置实时 AI 人脸美颜、3D LUT 电影调色、**17 种语言的离线语音转字幕**、9 引擎在线翻译与局域网 SMB 播放。
>
> A professional mobile VR player with real-time AI beauty filters, 3D LUT color grading,
> offline ASR subtitles (**17 languages**), 9-engine online translation and LAN (SMB) playback.

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
- 美颜预设：自然 / 淡妆 / 浓妆 / 自定义（**已持久化**，切语言也不会失配），支持对比原图（一键关美颜）
  - Presets: Natural / Light / Heavy / Custom (persisted); one-tap before/after compare
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
- 离线语音识别：**SenseVoice-Small**（sherpa-onnx，CPU int8）
  - 中/英/日/韩/粤 5 语言，自带标点，RTF 0.026
  - **模型已内置**（约 229MB 打进 APK），开箱即用、无需联网下载
  - Offline ASR: SenseVoice-Small bundled in the APK — zh/en/ja/ko/yue with punctuation, no download needed
- 🌍 **多语言扩展识别（共 17 种语言）/ Multi-language ASR (17 languages)**
  - 内置 5 语之外，可在设置里**按需下载**官方离线模型：越南语 / 俄语 / 法语 / 德语 / 西班牙语 / 白俄罗斯语 / 克罗地亚语 / 意大利语 / 波兰语 / 乌克兰语 / 泰语
  - Beyond the 5 bundled languages, more official offline models can be **downloaded on demand** in Settings: Vietnamese / Russian / French / German / Spanish / Belarusian / Croatian / Italian / Polish / Ukrainian / Thai
  - **模型不打进 APK**（否则安装包会涨到 1GB+），下载到**应用私有目录**，因此**不需要任何存储权限**
  - Models are **NOT bundled** (the APK would exceed 1GB) and are downloaded into the **app-private directory**, so **no storage permission is required**
  - 11 种语言共用同一份 FastConformer 模型 → **下载一次，这 11 种语言全部可用**
  - The 11 languages share a single FastConformer model — **download once, all 11 become available**
- **实时 AI 字幕**：边播边生成，不写临时文件
  - 独立解码音频（AudioTee）+ **Silero VAD** 分段 + 按优先级全局生成
  - 优先补当前播放点（**含前 5 秒回补**）及其后内容，再回头补齐其余；跳转后可即时命中已生成部分
  - 推理线程数可调（1–10，推荐 4–6）；字幕悬浮窗支持一键「重新生成」
  - Realtime subtitles generated while playing — independent audio decode + Silero VAD, priority-based global generation
- **字幕导出**：一键导出 SRT（直接由内存字幕缓存生成）；启用翻译时文件名**带语言后缀**（如 `影片_20260920-110000_zh.srt`，双语再加 `_bi`），同一部片子的多语言字幕互不覆盖
  - One-tap SRT export from the in-memory subtitle cache; when translation is on the filename carries a **language suffix** (e.g. `movie_20260920-110000_zh.srt`, plus `_bi` for bilingual) so multiple languages never overwrite each other
- 整片转写：后台生成带时间轴的 SRT 字幕（静音断句 + 标点断句 + 14 字智能换行）
  - Full-video transcription to timed SRT (silence/punctuation segmentation, 14-char line wrap)
- 转写策略：离线模型按**语音段整段识别**（Silero VAD 断句：静音 0.5s 或单段满 8s）
  - Segment-level offline inference (Silero VAD: 0.5s silence or 8s max per segment)
- 长句自动切分：单条超过 20 字或 5 秒时按标点拆成多条，按字数比例分配时间
  - Results >20 chars or >5s are split by punctuation with proportional timing
- 翻译：**预读翻译**（提前翻译播放点前方 60 秒内的字幕）+ 磁盘缓存（换视频/重启后仍命中）
  - Translation: ahead-of-playback prefetch + on-disk cache
- **模型下载**：进度显示、**断点续传（Range）**、**5 次重试**、读超时 90 秒自动重连；
  无「按文件」源的模型（如泰语）走 **tar.bz2 整包下载 + 流式解压**（只保留所需文件后删包）
  - Downloads: progress, resume, 5 retries, 90s read-timeout reconnect; tar.bz2 whole-package fallback with streaming extract
- 字幕样式：字体/字号/位置/描边自定义，内置 MiSans、OPPO Sans 等中文字体；**字号为无级连续调节**
  - Subtitle styles: font/size/position/outline customizable (stepless size slider); bundled MiSans / OPPO Sans

#### 🌍 多语言识别支持矩阵 / ASR Language Matrix

| 语言 / Language | 模型 / Model | 体积 / Size | 下载源 / Source |
|---|---|---|---|
| 自动·中·英·日·韩·粤<br>Auto / zh / en / ja / ko / yue | SenseVoice-Small（**已内置 / bundled**） | 随 APK（229MB）<br>in APK (229MB) | 无需下载 / none |
| 越南语 / Vietnamese | `sherpa-onnx-zipformer-vi-int8` | ≈74MB | hf-mirror |
| 俄·法·德·西·白俄·克·意·波·乌<br>ru / fr / de / es / be / hr / it / pl / uk | `NeMo FastConformer 20k int8`<br>（**一个模型覆盖 11 语 / one model, 11 languages**） | 整包 102MB → 解压 ≈132MB<br>pkg 102MB → ≈132MB extracted | GitHub releases |
| 泰语 / Thai | `sherpa-onnx-zipformer-thai-2024-06-20` | 整包 664MB → 解压 ≈154MB<br>pkg 664MB → ≈154MB extracted | GitHub releases |

### 🌐 字幕在线翻译 / Online Translation
- **9 种引擎**：必应翻译（免费） / **MyMemory**（免费） / **LibreTranslate**（免费，可自建） / DeepSeek / 通义千问 / 智谱 GLM / MiniMax / OpenAI GPT / 自定义（OpenAI 兼容）
  - 9 engines: Bing (free) / MyMemory (free) / LibreTranslate (free, self-hostable) / DeepSeek / Qwen / Zhipu GLM / MiniMax / OpenAI GPT / Custom
- 显示模式：**双语（原文+译文）** 与 **仅译文** 一键切换，选择**已持久化**
  - Display modes: bilingual / translation-only, both persisted
- MyMemory：匿名额度 **5000 字符/天**，程序内置**串行限速 + 错误文案识别 + 配额冷却 10 分钟 + 超长句跳过**，避免触发其限流
  - MyMemory: built-in pacing, error-text detection and 10-min cooldown to respect its quota limits
- LibreTranslate：标准 `/translate` 协议，设置面板可填 Base URL 指向**私有实例**
  - LibreTranslate follows the standard protocol; Base URL configurable for a private instance
- 必应翻译参考 [plainheart/bing-translate-api](https://github.com/plainheart/bing-translate-api)（MIT，自研 Kotlin HTTP 实现，未直接引入 npm 包）
  - Bing translation inspired by [plainheart/bing-translate-api](https://github.com/plainheart/bing-translate-api) (MIT; self-written Kotlin HTTP, npm package NOT bundled)
- **引擎 / 目标语言 / API Key / Base URL / 模型名 / 显示模式全部持久化**（重启不丢）
  - Engine, target language, API key, base URL, model and display mode are all persisted
- **本地翻译词库（缓存）**：内存 + 磁盘双层，**按目标语言分文件**（`translation/cache_<语言>.tsv`，启动只加载当前语言），单语言上限 **32MB（约 20 万条）**，跨视频、跨重启都命中，因此同一句话只翻一次
- **缓存失效策略**：条目带「最近使用时间 + 命中次数」→ 压缩时按 **LRU** 淘汰（低频且久未用优先，替代原先的随机淘汰）+ **TTL 180 天**过期；文件头带**版本号**，译文口径变更时可整份作废（旧文件改名 `.stale` 留档）
- 缓存键做**空白归一化**（多余空格/换行差异视为同一句）；设置里可看**缓存统计**（各语言条目数/体积、命中率、会话用量）并**按语言清空**
  - Local translation memory: in-memory + on-disk, **one file per target language** (`translation/cache_<lang>.tsv`; only the current language is loaded at startup), 32MB / ~200k entries per language, survives restarts
  - Invalidation: each entry stores last-used time + hit count → **LRU eviction** (least-used & least-recent first, replacing the old random drop) + **180-day TTL**; the file header carries a **version tag** so a change in translation convention can invalidate the cache wholesale (renamed `.stale`, kept for reference)
  - Cache keys are whitespace-normalized; Settings shows **cache stats** (per-language entries/size, hit rate, session usage) with per-language clearing

### 📁 局域网与远程播放 / LAN & Remote Playback
- SMB 协议（jcifs-ng）：浏览局域网共享、直连播放 NAS/PC 视频
  - SMB (jcifs-ng) browsing & direct playback from NAS/PC
- **远程视频 seek 优化**：HTTP 分支包 `CacheDataSource` + `SimpleCache`（512MB LRU），
  即使对方不支持 Range 也能边下边播、正常拖动；moov 在尾部的 MP4 也能先读 moov
  - Remote seek: HTTP path wrapped with a 512MB LRU cache, enabling seek even without Range support
- **远程视频也能生成实时字幕/转写**：`AudioTee` 对 `http(s)://` 走框架 MediaExtractor、对 `smb://` 用 jcifs 随机访问封装 `MediaDataSource`
  - Realtime subtitles work for http and SMB sources too

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
│  硬解 8K、变速播放、音轨/字幕轨选择、缓存数据源        │
├─────────────────────────────────────────────────────┤
│  智能模块 / Intelligence                             │
│  MediaPipe Face Landmarker（人脸关键点 468 点）      │
│  SenseVoice + 多语言 transducer + Silero VAD        │
│  9 引擎字幕翻译                                       │
│  Room 持久化（设置记忆/字幕缓存）                     │
└─────────────────────────────────────────────────────┘
```

### 关键组件 / Key Components

| 模块 | 技术 | 说明 |
|---|---|---|
| `VRGLRenderer.kt` | OpenGL ES 2.0 Shader | 核心渲染：投影、变形、美颜、LUT、合成 |
| `VRPlayerScreen.kt` | Compose | 播放器主界面 + 设置面板（v120/v121 已按功能拆分） |
| `AsrBatchSection.kt` | Compose | 后台转写区块（设置面板与字幕快捷面板复用，v121 拆出） |
| `PlayerControlBar.kt` | Compose | 播放控制栏三组按钮 + 宽窄屏自适应布局（v121 拆出） |
| `BeautySettingsSections.kt` | Compose | 美颜/模式提示/对比原图/预设等设置区块（v120–v121 拆出） |
| `VRPlayerComponents.kt` | Compose | 通用组件：`TooltipIconButton` / `BeautySliderItem` / `ExperimentalSwitchRow` |
| `MediaPipeFaceManager.kt` | MediaPipe Tasks | 468 点人脸关键点检测（arm64 真机） |
| `SherpaAsrManager.kt` | sherpa-onnx | 识别器管理：内置 SenseVoice + **可下载的多语言扩展模型（transducer）**；含断点续传 / 整包解压 / 尺寸校验 |
| `AsrExtModels.kt` | 自研 | **多语言扩展模型注册表**（语言 → 文件名清单 / 下载源 / 校验体积）；支持**多语言共用一个模型** |
| `RealtimeSubtitleEngine.kt` | 自研 | 实时字幕引擎：独立音频解码 + Silero VAD + 优先级调度 + seek 处理 |
| `SubtitleCache.kt` | 自研 | 字幕稀疏时间索引（TreeMap + 二分查找，O(log n)） |
| `SubtitleExporter.kt` | 自研 | SRT 导出（由内存字幕缓存生成） |
| `SubtitleTranslator.kt` | 自研多引擎 | 字幕翻译（**9 种引擎**可切换，含 MyMemory 限速与配额冷却） |
| `LutUtils.kt` | 自研 | .cube 解析 + 三线性重采样 + 512×512 网格打包 |
| `SchemeRoutingDataSource.kt` | 自研 | 按 scheme 分流数据源（`smb://` → jcifs，其余 → HTTP + 缓存） |

---

## 📂 目录结构 / Directory Layout

```
Aura-face-VR-Player/
├── app/
│   ├── build.gradle.kts            # 构建配置（版本/签名/依赖）
│   ├── libs/
│   │   └── sherpa-onnx-1.13.6.aar  # sherpa-onnx ASR 引擎
│   └── src/main/
│       ├── java/com/example/vr/   # Kotlin 源码
│       ├── assets/
│       │   ├── luts/              # 12 款内置 3D LUT（.cube，v117 起生效，支持自选 .cube）
│       │   ├── face_landmarker.task  # MediaPipe 人脸模型
│       │   ├── silero_vad.onnx    # Silero VAD 语音活动检测（629KB）
│       │   ├── sense-voice/       # SenseVoice 识别模型（内置；model.int8.onnx 由 scripts/fetch_asr_model.py 拉取）
│       │   └── licenses.json      # 开源许可清单（自动生成）
│       └── res/                   # 资源与字体（MiSans/OPPO Sans）
├── gradle/libs.versions.toml      # 依赖版本目录
├── scripts/fetch_asr_model.py     # 内置 ASR 模型拉取脚本（hf-mirror，支持断点续传）
├── scripts/gen_licenses.py        # 许可清单生成脚本
├── LICENSE                        # Apache License 2.0
├── RELEASE_SIGNING.md             # 签名与发布流程
├── FIREBASE_ANALYTICS.md          # 统计接入说明
└── README.md
```

> 多语言扩展模型**不在仓库内**，由 App 运行时按需下载到设备私有目录。

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
| 产物 | **单个全架构 APK**（`Aura-face-VR-Player-v<版本>.apk`，含内置 ASR 模型）/ Single universal APK (ASR model bundled) |
| 体积 | 约 **348MB**（其中内置 SenseVoice 模型占 229MB）/ ~348MB (229MB is the bundled ASR model) |
| 包含 ABI | `arm64-v8a` + `armeabi-v7a` + `x86_64` + `x86` 全包含 / All ABIs in one package |
| 安装 | 系统自动选取匹配 ABI 的原生库，无需挑选 / The OS picks the matching native libs |

> 说明：早期版本曾按 ABI 拆分为 4 个包发布，v116 起改为**单包全架构**发布，
> 省去用户判断设备 ABI 的麻烦（体积换易用性）。
> Note: early releases shipped 4 ABI-split APKs; since v116 we ship a single universal APK.

---

## 🔧 构建 / Build

### 环境要求 / Requirements
- JDK 17+（本机实测 JDK 21）
- Android SDK（compileSdk 36, minSdk 24, targetSdk 36）
- Gradle 9.6.1

> ⚠️ **本仓库不包含 Gradle Wrapper**（没有 `gradlew` / `gradlew.bat`）。
> 请用本机安装的 Gradle 直接调用，并设置 `JAVA_HOME`：
>
> ```powershell
> $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"   # 按本机路径调整
> & "C:\Users\<你>\.gradle\dist\gradle-9.6.1\bin\gradle.bat" -p . assembleRelease
> ```
>
> This repo has **no Gradle Wrapper** — invoke your local Gradle installation instead of `gradlew`.

### 第一步：拉取内置 ASR 模型（首次 clone 后必做）/ Fetch bundled ASR model

`model.int8.onnx`（约 228MB）超过 GitHub 单文件 100MB 限制，**不纳入 git**，
需先跑脚本拉到 `app/src/main/assets/sense-voice/`，否则 APK 不会内置模型
（仍能编译，但离线字幕会退回运行时下载模式）。

```powershell
python scripts/fetch_asr_model.py          # 缺失才下载，支持断点续传
python scripts/fetch_asr_model.py --check  # 只检查是否就绪
```

> 镜像源为 `hf-mirror.com`；不可达时脚本会提示手动下载地址（HuggingFace 官方仓库）。
> 多语言（17 语）模型中只有 SenseVoice 需要随包：**其余 11 种语言由 App 运行时按需下载**。
>
> Mirror: `hf-mirror.com`; the script prints a manual download URL when unreachable.
> Of the 17 languages, **only SenseVoice ships inside the APK** — the other 11 are downloaded on demand at runtime.

### 构建命令 / Commands

```powershell
# Debug 包（开发测试）
gradle.bat assembleDebug

# Release 包（正式分发，必须！见 RELEASE_SIGNING.md）
# 产物：app\build\outputs\apk\release\Aura-face-VR-Player-v<版本>.apk（单包全架构，含内置模型）
gradle.bat assembleRelease

# 依赖许可证清单导出
gradle.bat :app:dumpDependencies
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
- **云端数据（可选）**：字幕翻译（用户自配 API Key 或免费公共端点）、多语言 ASR 模型下载、Firebase 统计
  - Optional cloud data: subtitle translation, multi-language ASR model download, Firebase analytics
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
| Media3 ExoPlayer / Transformer | Apache-2.0 | 播放内核 + 缓存数据源 |
| MediaPipe Tasks Vision | Apache-2.0 | 人脸关键点 |
| sherpa-onnx | Apache-2.0 | 离线语音识别（SenseVoice + 多语言 transducer） |
| commons-compress | Apache-2.0 | tar.bz2 整包解压（多语言模型兜底下载） |
| Retrofit / OkHttp / Moshi | Apache-2.0 | 网络与 JSON |
| jcifs-ng | LGPL-2.1 | SMB 局域网播放 |
| JNA | LGPL-2.1 / Apache-2.0 | 原生库桥接 |
| Room | Apache-2.0 | 本地持久化 |
| [bing-translate-api](https://github.com/plainheart/bing-translate-api)（参考） | MIT | 必应翻译免费端点（自研 Kotlin 实现） |

**资源 / Resources**：MiSans / OPPO Sans 字体（免费商用授权）、MediaPipe 模型（Apache-2.0）、12 款 LUT（项目自研 numpy 脚本生成，无第三方版权）。

**ASR 模型许可 / ASR model licenses**：SenseVoice、zipformer、NeMo FastConformer 均为 Apache-2.0；泰语 zipformer 模型源自 `icefall-asr-gigaspeech2`，亦为 Apache-2.0。
All ASR models — the bundled SenseVoice and the downloadable zipformer / NeMo FastConformer ones — are Apache-2.0; the Thai zipformer derives from `icefall-asr-gigaspeech2` (also Apache-2.0).

完整许可清单见应用内「设置 → 关于与开源许可」或 `app/src/main/assets/licenses.json`。

---

## ⚠️ 已知问题 / Known Issues

| # | 中文 | English |
|---|---|---|
| 1 | **8K 硬解为实验功能**——默认关闭，需在「设置 → 8K 硬解实验」中按需开启，可能花屏或失败 | **8K decoding is experimental** — off by default; enable under Settings → 8K experiments; artifacts possible |
| 2 | **安装包较大（约 348MB）**——ASR 模型已内置以保证开箱即用；若需精简版可自行移除 `assets/sense-voice/` 并改用下载兜底 | **Large APK (~348MB)** — the ASR model is bundled for out-of-the-box use |
| 3 | **陀螺仪漂移**——长时间观看后水平朝向缓慢漂移，双击画面重置视角即可（原理性，GAME_ROTATION_VECTOR 无绝对北向基准） | **Gyroscope drift** — yaw drifts slowly over long sessions; double-tap to recenter (inherent to game rotation vector) |
| 4 | **AI 字幕多行时间线可能不匹配**——断句/静音判断误差导致时间轴偏移 | **Multi-line ASR subtitle timeline mismatch** — auto-generated timestamps may not perfectly align |
| 5 | **免费翻译端点有额度限制**——必应为非官方网页端点、MyMemory 匿名仅 5000 字符/天（已内置限速与冷却），重度使用建议自配 LLM API Key 或自建 LibreTranslate | **Free translation endpoints are rate-limited** — Bing is unofficial; MyMemory allows ~5000 chars/day anonymously (pacing & cooldown built in) |
| 6 | **多语言 ASR 模型需联网首次下载**——11 种语言共用 102MB 包；泰语无「按文件」源，需下 664MB 整包（解压后仅保留约 154MB） | **Multi-language ASR models need a one-time download** — 11 languages share a 102MB package; Thai has no per-file source (664MB package, ~154MB kept) |

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
| **v2.0.127** | **ASR 模型内置**（SenseVoice 打进 APK，开箱即用，无需下载）/ 启动时自动清理已废弃引擎（Vosk·Qwen3·QNN）遗留的模型目录 / 下载链路保留为兜底与更新通道 · **Bundled ASR model** (off-the-shelf, no download) + auto-cleanup of legacy model dirs |
| **v2.0.128** | 修复**字幕开关不记忆**（自动加载字幕时会把用户关掉的字幕重新打开）/ 修复**字幕翻译开关不记忆**（只写不读 + 字幕面板内的开关未落盘）/ 字幕设置面板改为**限高滚动并带滚动条** · Fixed subtitle & translation toggle not persisting; scrollable subtitle settings panel |
| **v2.0.129** | **界面多语言（第一批）**：支持简体中文 / 繁体中文 / English，默认跟随系统，设置里可手动切换（切换后重建界面）。首批覆盖字幕快捷面板与完整字幕设置面板共 45 条文案 · **i18n (batch 1)**: zh-CN / zh-TW / en, follows system by default with in-app switch |
| **v2.0.130** | **界面多语言（第二批，累计 352 条）**：繁体改为**大陆用词+繁体字形**（线程/缓存/视频/搜索/导出，非台灣慣用詞）；翻译范围扩到播放控制栏、视频信息、降级转码、8K 硬解、悬浮球、解码器、美颜分区、开源许可、隐私弹窗与全部 Toast 提示 · **i18n batch 2**: traditional Chinese now uses mainland terminology; 352 strings localized |
| **v2.0.131** | 切换语言**不再重建 Activity**（当前视频/进度/预览图全部保留）/ 语言选项移入「UI 主题」分区 / 新增**日语、韩语**（五语各 400 条）/ 枚举选项名（字幕字体·颜色·描边·背景、投影/Warp/分辨率/立体/解码器）完成多语言 · **i18n**: ja/ko added, language switch keeps playback, 400 strings x 5 languages |
| **v2.0.132** | **修复 v2.0.131 切语言闪退**：`LanguageManager.wrap` 改为只覆盖 `getResources()`、base 仍指向 Activity 的 `ContextWrapper`，恢复 `rememberLauncherForActivityResult` 顺着 LocalContext 找 `ActivityResultRegistryOwner` 的链路 |
| **v2.0.133** | 修复切语言后**语言选项高亮停留原语言**：`currentLangTag` 改用 `remember(context)`（context=LocalContext.current，切语言后是新的 localizedContext 对象）重算，高亮实时跟随当前语言 |
| **v2.0.134** | ① 修复**五语格式占位符双写 `%%`** 致参数被丢弃、界面显示 `%1$s` 字面（「已就绪 %1$s」、导出 SRT 等），5 语统一修正 58~63 处/语，英文 `//n`→`\n`；② 修复**实时字幕生成卡在 99% 不完成**：单窗口解码/识别异常会穿透预读循环中断整条生成链路，已将该窗口处理包 try/catch、失败仅跳过并继续；③ 修复**看一会儿字幕就消失**：`onSeek` 之前只清缓存未同步清理「已扫描区间」记录，导致被清掉的后方字幕不再补回（新增 `trimScannedAfter`）；且播放位置同步 effect 以 `isVideoPlaying` 为 key，暂停/恢复重启会把基准误判成 >2s 大跳而误触发 seek 清空缓存，已改为首帧仅初始化基准 |
| **v2.0.135** | 实测（logcat）推翻 v2.0.134 ③ 的判断：生成速度仅 **≈1x 实时**，seek 清掉前沿后播放头 12s 内必然追上、字幕必消失。① **`onSeek` 不再清任何缓存/扫描记录**（seek 后前方已生成字幕依然正确，清掉再按 1x 补回纯属浪费；往回拖瞬时命中、往前拖直接可用）；② 修 `firstGapIn` **1ms 滑移**（已扫描区间闭区间末尾被当新 gap，每窗重复解码上窗末尾 1ms）；③ 解码窗口 20s→60s（摊薄每窗 seek 到关键帧+flush 的固定开销，提升生成吞吐） |
| **v2.0.136** | ① **修复引擎重启后多预读协程并发踩踏**（logcat 实证：同一窗口被重复识别 2~3 遍、进度倒跳、窗口边界每轮 -1ms）：引入**代际 token**——旧代协程在窗口处理完成后发现已被新一代取代立即退出且不写共享状态；`scannedRanges` 全部访问加锁；② 实时字幕**全片生成完成后自动保存** `subtitles/<视频名>_<时间戳>.srt`（应用 data 目录，多次生成不覆盖）；③ **打开视频时自动加载**该目录下匹配的最新一份；④ 字幕悬浮窗新增**字幕源选择器**：实时 AI 生成 / 历史保存字幕一键切换（五语适配） |
| **v2.0.137** | 修复**设置面板字幕浮窗**底部大片空白、语言选择与水印/主题卡片重叠、以及硬编码的中文颜色名（如「青橙」「赛博朋克」）；浮窗布局改为自适应高度 · Settings subtitle panel: whitespace/overlap fixes + hardcoded color-name cleanup |
| **v2.0.138** | **设置面板硬编码中文全面多语言化（五语）**：翻译引擎（必应/DeepSeek/通义/智谱/MIMO/OpenAI/自定义）、目标语言、ASR 语言、12 款 LUT 滤镜、ASR 模型状态、字幕翻译状态、实时字幕状态、各类 Toast，以及投影/立体/解码器/分辨率模式名，全部迁入 `values-*` 字符串资源并适配简/繁/英/日/韩 · **Settings-panel i18n**: all hardcoded Chinese (engines, languages, LUTs, ASR/translation/realtime statuses, toasts, projection/stereo/decoder/resolution modes) moved to string resources, 5 languages |
| **v2.0.139** | ① **修复第三方文件管理器（MT 管理器等）经 FTP/SMB 远程打开视频无法播放**：MT 对远程文件经本地回环 HTTP 代理（`http://127.0.0.1:port/...`）交给播放器，而 `DefaultDataSource` 只处理 file/asset/content、其余 scheme 全部落到 base 数据源——base 固定为 SmbDataSource 导致 http URI 被拿去 SMB 连接 127.0.0.1 而失败。新增 `SchemeRoutingDataSource` 按 scheme 分流：`smb://` → jcifs，其余 → `DefaultHttpDataSource`；并开启 `usesCleartextTraffic` 允许回环明文 HTTP；② **SMB 播放 seek 改真随机访问**：`SmbFileInputStream.skip()` 对大偏移要顺序读丢数据、长视频拖动极慢，改用 `SmbRandomAccessFile` |
| **v2.0.140** | 修复远程视频（MT 回环代理）**实时字幕/批量转写误报「该视频没有可用的音轨」**：`AudioTee.open()` 第一步 `openFileDescriptor(uri)` 对 `http://` URI 必然抛异常、且临时文件兜底同样依赖它，导致 http 源永远报无音轨。现 http/https 先走框架 `MediaExtractor.setDataSource(context, uri, null)`（原生 HTTP 栈、支持 Range seek，无需下载），失败再回退经代理整文件下载到缓存打开；批量转写复用 AudioTee 一并修复 · Realtime/batch subtitle: fix false "no audio track" for remote http sources |
| **v2.0.141** | 解码器列表新增 **MPV 占位项**：`DecoderEngine.MPV` 出现在选择器并标注为「占位」，**尚未真实接入**（选中会回退到内置解码器），为后续接入预留入口 · MPV decoder **placeholder** (selectable & labeled as placeholder; not yet wired, falls back to the built-in decoder) |
| **v2.0.142** | ① **应用内 SMB 浏览器播放也能生成实时字幕/批量转写**：`AudioTee.open()` 新增 `smb://` 分支，用 jcifs `SmbRandomAccessFile` 包成 framework `MediaDataSource` 真随机访问喂给 `MediaExtractor`（直连失败再回退 jcifs 整文件下载到缓存），修掉「该视频没有可用的音轨」误报（v2.0.140 只修了 http）；② **远程视频 seek 优化**：`SchemeRoutingDataSource` 的 http 分支包 `CacheDataSource`+`SimpleCache`（512MB LRU），回环 HTTP 代理即使不支持 Range 也能按需拉取字节、正常 seek（边下边播），moov 在尾部的 MP4 也能先读 moov；③ **硬编码中文收尾审计**：活跃源码已无用户可见漏网硬编码中文（UI 全走 `labelRes`/`R.string`，仅保留 LLM prompt 与母语名等故意项） · SMB realtime subtitle + remote seek cache |
| **v2.0.143** | **翻译引擎新增两个免费源**：① **MyMemory**（无需 key，实测可用）——`GET .../get?q=…&langpair=Autodetect 到 zh-CN`，支持自动识别来源语言、简/繁目标；② **LibreTranslate**（自托管或公共实例，标准 `/translate` 协议，可选 api_key）——新增设置面板「Base URL」输入便于指向私有实例。两者均接入统一缓存与限流出口，UI 引擎列表自动出现 · Add free translation engines: MyMemory + LibreTranslate |
| **v2.0.144** | ① **翻译设置全面固化**：此前只有「字幕翻译」开关落盘，**引擎选择**、**「双语/仅译文」显示模式**、目标语言、API Key、Base URL、模型名重启即回默认（用户以为"选了没用"）；现全部持久化，并加「已恢复」门控，避免写回 effect 用默认值覆盖已存设置；② **美颜预设固化**：预设高亮改用稳定 id 落盘、恢复时按当前语言映射回本地化名（切语言不失配）；③ **字幕字号移到「字重」正下方**：原埋在「布局与时间」区不易发现，现为无级连续 slider（12~40）；④ **MyMemory 降速防报错**（依 usagelimits：匿名 5000 字符/天、按调用频率限流、超限以 HTTP 200 回错误文案）：新增「串行 + 最小间隔」限速、错误文案识别（不入缓存）、配额冷却 10 分钟、超 500 字节长句跳过 · Persist all translation settings + beauty preset; move font-size slider under font-weight; throttle MyMemory |
| **v2.0.145** | **多语言 ASR（越南语样板）**：ASR 语言选择在 SenseVoice 之外支持**可下载的离线扩展模型**——新增 `AsrExtModels` 注册表 + `SherpaAsrManager` 扩展通道（按需下载到 `filesDir`、离线 transducer 识别、多文件断点续传、逐文件尺寸校验防残缺）；越南语用 `sherpa-onnx-zipformer-vi-int8`（encoder/joiner int8 + decoder fp32 + tokens，≈74MB，hf-mirror 源，**不需新增权限**）；语言 chips 与模型状态区改为**随所选语言动态**，语言过多时每行 4 个自动换行 · Multi-language ASR sample: Vietnamese via downloadable offline zipformer transducer |
| **v2.0.146** | **多语言 ASR 补齐 ru / fr / de / es / th**：① ru/fr/de/es 起初共用 `nemo-parakeet-tdt-0.6b-v3-int8`（≈639MB）；② **泰语没有可按文件下载的源** → 新增**整包兜底**：下载官方 tar.bz2（664MB）后**流式解压只提取需要的 int8 文件**（≈154MB）再删包（`BZip2CompressorInputStream` + `TarArchiveInputStream`，按 basename 匹配）；③ 识别语言增至 12 项 · Multi-language ASR: Thai via tar.bz2 whole-package extract fallback |
| **v2.0.147** | **修复多语言模型「下载不动」**：设备实测日志 `encoder.int8.onnx HTTP 401` —— **hf-mirror 的「按文件」源按出口 IP / 缓存命中区别对待**，未被缓存的仓库在真机上直接 401（沙箱 IP 却是 206），导致 639MB 的 parakeet 根本下不来。① **ru/fr/de/es 改用官方同门 NeMo FastConformer**：`nemo-fast-conformer-transducer-be-de-en-es-fr-hr-it-pl-ru-uk-20k-int8`，**一个包覆盖 ru/de/es/fr**，整包 **102MB**、解压后 ≈132MB，走 **GitHub releases**（不再依赖 hf-mirror 按文件源）；② **下载加固**：读超时 600s→**90s**（卡住即抛超时→自动重试并按 **Range 续传**）、重试 **3→5** 次、请求统一带浏览器 UA；③ 整包完成判定改为 **97% 体积**，避免半包被当完整包、到解压才失败 · Fix "download stuck": hf-mirror per-file source 401s by egress IP; switch to a 102MB FastConformer + download hardening |
| **v2.0.148** | **补齐 FastConformer 包全部语言**：该包名 `…-be-de-en-es-fr-hr-it-pl-ru-uk-…` 共覆盖 **11 种**语言。在已有 ru/de/es/fr 之外，新增 **be 白俄罗斯语 / hr 克罗地亚语 / it 意大利语 / pl 波兰语 / uk 乌克兰语**（en 英语不重复登记，内置 SenseVoice 已覆盖）。全部条目共用同一目录 → **下载一次（102MB），这 11 种语言全部可用**；识别语言由此增至 **17 项** · Add all remaining languages of the FastConformer package (be/hr/it/pl/uk) |
| **v2.0.149** | **泰语改用更小的 Whisper-tiny**：泰语专用模型官方只有 **664MB 整包**（且 hf-mirror 对它的按文件源一律 401，只能整包下载再解压），体积代价过大 → 改用 **Whisper-tiny int8**（`tiny-encoder.int8.onnx` 12.9MB + `tiny-decoder.int8.onnx` 89.9MB + `tiny-tokens.txt` 0.8MB ≈ **99MB**，**hf-mirror 支持按文件下载**，体积降到 1/6.7）；识别器新增 **Whisper 分支**（`OfflineWhisperModelConfig` + `language=th`，多语言模型须显式指定语言）；代价是 tiny 精度弱于专用 zipformer · Thai switched to Whisper-tiny int8 (~99MB, per-file download) instead of the 664MB Thai zipformer package<br>⚠️ **本条已于 v2.0.152 撤回** / **reverted in v2.0.152** |
| **v2.0.150** | **翻译缓存优化（增大本地词库）**：① 磁盘缓存压缩阈值 **4MB → 32MB**（约可存 20 万条）—— 原值偏小，稍长的剧集就会把缓存文件顶到阈值以上，而原实现重写后文件仍大于阈值，**导致此后每次翻译都要做一次全量重写写盘**（几 MB/次，既慢又费电），这是个真问题；② `rewriteDiskCache` 增加**软上限裁剪**（重写前把内存缓存裁到 20 万条），使重写后文件回落到阈值以下，写入恢复为 O(1) 追加；③ 启动加载改用 `readLine` 循环并加**最大行数保护**（60 万行），避免超大缓存拖慢首屏；④ 加载/重写日志补充条数与 MB/KB，便于观察词库规模 · Translation cache: threshold 4MB→32MB, soft-cap trim on rewrite (fixes repeated full rewrites), load-time line cap, richer logs |
| **v2.0.151** | **翻译缓存命中率优化（缓存键归一化）**：字幕里同一句话常因**多余空格 / 换行**差异被当成两条（`"Hello  world"` vs `"Hello world"`），从而重复调用翻译接口。现所有缓存键统一经 `makeCacheKey()` **折叠连续空白（含全角空格）并去首尾空白**后再入库/查找，**5 处 key 构造点全部收口**；加载旧磁盘缓存时也按新规则归一化，**升级后老词条仍能命中并自动去重**。⚠️ 刻意**不做**大小写折叠与标点归一：那会把语义不同的句子混到同一 key（问句/陈述句、`12:30` 与 `1230`），返回不合适译文的代价比多翻一次更大 · Translation cache hit-rate: keys are whitespace-normalized via a single `makeCacheKey()` choke point (5 call sites), legacy on-disk entries migrated on load; case/punctuation intentionally NOT normalized to avoid false hits |
| **v2.0.152** | **撤回 v2.0.149 的泰语改动（保留 v2.0.150 / v2.0.151）**：v2.0.149 曾把泰语从 `sherpa-onnx-zipformer-thai-2024-06-20`（整包 664MB）改为 Whisper-tiny（≈99MB、按文件下载），本次**整体撤回**该改动 —— 泰语恢复为**专用 zipformer + 整包兜底**方案（`AsrExtModels.kt` / `SherpaAsrManager.kt` 回到 v2.0.148 状态，含移除 `whisperLanguage` 字段与 Whisper 识别分支）。v2.0.150（翻译缓存 32MB / 软上限裁剪）与 v2.0.151（缓存键空白归一化）**不受影响，完整保留** · Revert the Thai change from v2.0.149 (Whisper-tiny → back to the dedicated Thai zipformer with tar.bz2 fallback); v2.0.150/v2.0.151 caching work kept intact |
| **v2.0.153** | **翻译缓存重构 + 缓存统计面板**：① **按目标语言分文件**落盘（`filesDir/translation/cache_<lang>.tsv`，启动只加载当前语言 → 首屏更快、可单独清空），旧单文件自动按语言前缀拆分迁移（原文件改名 `.migrated` 留档）；② **失效策略升级**：条目记录「最近使用时间 + 命中次数」→ 压缩时按 **LRU** 淘汰（低频且久未用优先，替换原先按 HashMap 迭代序的随机淘汰）+ **TTL 180 天**过期 + 文件头**版本号**（译文口径变更时可整份作废，改名 `.stale`）；③ 修复 `clearCache()` **只清内存不清磁盘**（清空后重启缓存"复活"，等于没清）；④ **修复目标语言选择器只显示前 5 种**（fr / de / es / ru 在 UI 上根本选不到），改为每行 5 个自动换行；⑤ **导出字幕按语言命名**（`_zh` / `_zh_bi`），历史字幕加载优先匹配当前语言；⑥ 新增**缓存统计面板**（各语言条目数与体积、命中率、会话用量、按语言/全部清空） · Translation cache rebuilt: per-language files, LRU + TTL + version invalidation, stats panel, language-suffixed SRT export |

---

## 🗺️ 未来规划 / Roadmap

- [x] ~~**实现并验证 LUT 滤镜链路**（UI → 纹理 → 着色器采样）~~ ✅ v117 已完成 / Done in v117
- [x] ~~**实时 AI 字幕**（边播边生成 + 翻译预读）~~ ✅ v1.0.126 / Done in v1.0.126
- [x] ~~**多语言 ASR（11 种可下载语言 + 内置 5 语）**~~ ✅ v2.0.148 / Done in v2.0.148
- [ ] **把多语言模型搬到 ModelScope**，免去 102MB/664MB 的整包下载（可改为按文件、国内更快）/ Mirror models on ModelScope for faster per-file downloads
- [ ] 修复陀螺仪漂移（方向问题已在 v125 修正，剩余为长时间 yaw 漂移）/ Fix gyro drift (direction fixed in v125; residual slow yaw drift)
- [ ] 字幕时间轴对齐优化 / Subtitle timing alignment (VAD/endpoint calibration)
- [ ] 人脸关键点 x86_64 支持 / x86_64 face-landmark support (emulator beauty)
- [ ] **MPV 解码器真实接入**（v2.0.141 仅为占位）/ Wire the MPV decoder for real (v2.0.141 is a placeholder only)
- [ ] 更多投影模式（CAVE / 半球）/ More projection modes (CAVE / hemisphere)
- [ ] 字幕样式模板 / Subtitle style templates
- [ ] 播放列表与历史记录同步 / Playlist & history sync
- [ ] 8K 硬解实验转正（当前为实验功能，默认关闭）/ Graduate experimental 8K decoding

---

## 📄 许可声明 / License Notice

本项目采用 **Apache License 2.0** 开源（见 [LICENSE](LICENSE)），Copyright © 2026 tianhuoliuhun。
可自由使用、修改、商用与再分发（保留版权与许可声明即可）。
