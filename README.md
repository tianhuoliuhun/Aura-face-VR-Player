# 🎬 Aura美颜VR播放器 / Aura face VR Player

> 一款面向移动端的**专业级美颜 VR 播放器**：支持 360°/180° 全景、鱼眼、3D SBS/TAB 立体视频，
> 内置实时 AI 人脸美颜、3D LUT 电影调色、**60+ 种语言与方言的离线语音转字幕**、10 引擎在线翻译与局域网 SMB 播放。
>
> A professional mobile VR player with real-time AI beauty filters, 3D LUT color grading,
> offline ASR subtitles (**60+ languages & dialects**), 10-engine online translation and LAN (SMB) playback.

> **版本里程碑 / Milestones**：
> **v1.0** 完成 VR 播放的基本功能（投影 / 立体 / 陀螺仪 / LUT 调色）·
> **v2.0** 完成 AI 语音识别与翻译（实时字幕 / 离线 ASR / 在线翻译 / AI 美颜双引擎）·
> **v2.1** 修复美颜引擎的问题（GPUPixel 闪退 / 覆盖范围 / 闪烁与性能），引入画质增强（MEMC 插帧 / FSR 超分）；
> ASR 扩展至 **87 项语言与方言** 并把 **Dolphin 内置进 APK**（开箱即用），新增时间标记悬浮球与语言选择多级收纳；
> 后期（v2.1.24x）把播放内核扩到 **四个**（EXO / IJK / MPV / 系统解码），
> **MPV 内置进 APK** 作为 WMV·ASF·RM·RMVB 的兜底内核，并用**容器白名单**做内核自动路由。
>
> **v1.0** delivered the core VR playback (projection / stereo / gyro / LUT) ·
> **v2.0** delivered AI speech recognition & translation (realtime subtitles, offline ASR, online translation, dual-engine beauty) ·
> **v2.1** fixed the beauty-engine issues (GPUPixel crash, coverage, flicker & performance) and added video enhancement (MEMC / FSR) plus a major ASR language expansion;
> later (v2.1.24x) expanded to **four playback kernels** (EXO / IJK / MPV / system) with **MPV bundled in-APK** as the fallback for WMV/ASF/RM/RMVB, plus container-based auto-routing.

![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B-green) ![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-purple) ![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-blue) ![License](https://img.shields.io/badge/License-Apache%202.0-blue)

---

## ✨ 功能亮点 / Highlights

> 每个功能的完整说明见 **[功能详解](docs/FEATURES.md)**；画质增强另有 **[专题文档](docs/VIDEO_ENHANCE_MEMC_FSR.md)**。

| 功能 / Feature | 亮点 / Highlights |
|---|---|
| 🥽 **VR 播放**<br>VR Playback | 360°/180° 全景、鱼眼、标准平面四种投影；3D SBS/TAB 立体；陀螺仪跟随与手动偏移；圆柱曲面沉浸；8K 硬解实验（默认关）<br>Four projection modes, SBS/TAB stereo, gyro tracking, cylinder curvature, experimental 8K decoding |
| ⚡ **画质增强**<br>Video Enhancement | **MEMC 运动补偿插帧**（48/60/72/90/120 fps）+ **FSR 超分**（EASU + RCAS，默认规则与自定义 6 档，自定义完全接管）<br>Motion-compensated interpolation plus FSR upscaling with default and fully-overriding custom rules |
| ✨ **实时 AI 美颜**<br>Real-time AI Beauty | **GLSL / GPUPixel 双引擎**可切换；478 点人像精修（瘦脸/大眼/去黑眼圈/鼻梁/嘴型/牙齿/口红/腮红/眉毛）；磨皮为频域分离 + 可调皮肤质感<br>Dual switchable engines, 478-landmark portrait retouch, frequency-separation smoothing |
| 🎨 **3D LUT 调色**<br>LUT Color Grading | 36 款内置 LUT（人像美颜 24 + 风格滤镜 12，全部自研/开源可商用）；可导入自定义 `.cube`<br>36 bundled LUTs plus custom `.cube` import |
| 🗣️ **字幕与语音转写**<br>Subtitles & ASR | 离线 **Dolphin 已内置**（开箱即用）：**40 种东方语言 + 22 种中文方言**，自带语种识别；实时生成、整片转写、SRT 导出、去标点。语言面板点**「自动」即直接使用随包内置模型**（真·语种识别，不下载）<br>Bundled offline ASR (Dolphin): 40 Eastern languages + 22 Chinese dialects with auto language detection, realtime generation, batch transcription and SRT export. The 「Auto」 entry uses the **bundled** model directly — no download |
| 🌐 **字幕在线翻译**<br>Online Translation | **10 种引擎**（4 个免密/免费：必应 / Google 免密 / MyMemory / LibreTranslate）；本地词库缓存（LRU + TTL + 按语言分文件）<br>10 engines including 4 keyless/free, with a local per-language translation memory |
| 📁 **局域网与远程播放**<br>LAN & Remote | SMB（jcifs-ng）浏览与直连；HTTP 缓存数据源让不支持 Range 的源也能拖动；远程源同样支持实时字幕<br>SMB browsing, a caching data source for seamless remote seek, subtitles for remote sources |
| ⏩ **进度条拖动**<br>Seeking | **松手才定位**（拖动中只更新预览，不堆积 seek）—— **AVI / AV1 也能顺畅拖动**；容器不支持定位时**不重新编码**地自动重封装修复；AVI 内嵌 AV1 明确提示改用 MPV 内核<br>Seek-on-release keeps AVI/AV1 dragging smooth; auto remux (no re-encode) when a file cannot seek |
| 🎞️ **多内核与格式兼容**<br>Multi-kernel & Formats | **四个内核可选**（EXO / IJK / MPV / 系统解码）+ 自动路由：打开 **WMV / ASF / RM / RMVB / ISO / VOB** 等片源时**本次自动切换**到能吃它的内核（不改写你的设置）。**MPV 已内置进 APK**，无需下载即可播放上述格式<br>Four selectable kernels with container-based auto-routing; **MPV is bundled in-APK** so WMV/ASF/RM/RMVB/ISO/VOB play out of the box |

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
| 产物 | **单个全架构 APK**（`Aura-face-VR-Player-v<版本>.apk`，含内置 ASR 模型与解码内核）/ Single universal APK (ASR model & decoder kernels bundled) |
| 体积 | 约 **241MB**（其中内置 Dolphin ASR 模型占 99MB、MPV 解码内核占约 43MB）/ ~241MB (99MB ASR model + ~43MB MPV decoder) |
| 包含 ABI | `arm64-v8a` + `armeabi-v7a` + `x86_64` + `x86` 全包含 / All ABIs in one package |
| 安装 | 系统自动选取匹配 ABI 的原生库，无需挑选 / The OS picks the matching native libs |

> 说明：早期版本曾按 ABI 拆分为 4 个包发布，v116 起改为**单包全架构**发布，
> 省去用户判断设备 ABI 的麻烦（体积换易用性）。
> Note: early releases shipped 4 ABI-split APKs; since v116 we ship a single universal APK.

---

## 🚀 快速开始 / Quick Start

```powershell
# 0) 环境：JDK 17+、Android SDK、Gradle 9.6.1
#    ⚠️ 本仓库不含 Gradle Wrapper（无 gradlew），请直接调用本机 gradle
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"   # 按本机路径调整

# 1) 首次 clone 后必做：拉取内置 ASR 模型（约 228MB，超 GitHub 单文件限制故不入 git）
python scripts/fetch_asr_model.py

# 2) 构建正式包
gradle.bat assembleRelease
# 产物：app\build\outputs\apk\release\Aura-face-VR-Player-v<版本>.apk（单包全架构，含内置模型）
```

> ⚠️ **正式分发只允许 Release 包** —— Debug 包使用公开的 Android debug key（密码 `android`），
> 外发可被他人重签伪造更新。
>
> ⚠️ **Ship release APKs only** — debug builds are signed with the publicly known `android` key.
>
> 完整环境要求、签名配置、许可清单生成与常见问题见 **[构建与发布](docs/BUILD_AND_RELEASE.md)**。

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
| 7 | **画质增强只在视频播放时生效**——MEMC / FSR 的启用判定基于**视频源分辨率**，图片与全景浏览模式下开关虽可打开但不参与（副标题会显示「等待视频信息」） | **Video enhancement applies to video playback only** — MEMC / FSR are keyed off the video source resolution, so the toggles stay inert while browsing images or panoramas (the subtitle reads "waiting for video info") |
| 8 | **MEMC 目标帧率是「输出上限」而非保证值**——它只能向下限制输出节奏，无法把渲染循环帧率提上去；实际可达帧率受**屏幕刷新率**与 GPU 负载限制（例如 60Hz 屏上选 120 不会有额外效果，但也不会出错） | **The MEMC target frame rate is an output cap, not a guarantee** — it can only slow the output down, never raise the render loop's rate; the achievable rate is bounded by the display refresh rate and GPU load |
| 9 | **超分不能增加信息量**——FSR 的本质是「低分辨率渲染 + 高质量放大」，只能减少放大模糊。**源分辨率越接近显示分辨率，效果越不可见**（如 1920×960 源在 1080p 屏上几乎无差别）；另外 3840p / 4320p 档位的 ping-pong 纹理约占 236MB 显存，低端设备慎用 | **Upscaling cannot add information** — FSR is high-quality magnification, so it only reduces blur; the closer the source resolution is to the display, the less visible the difference. The 3840p / 4320p tiers also need roughly 236MB of VRAM for their ping-pong textures |

---

## 📚 文档导航 / Documentation

README 只保留**总览与上手**；展开的说明都拆在下列文档里。

The README keeps only the **overview and getting started**; everything else is split into the documents below.

### 本项目文档 / In this repo

| 文档 / Document | 内容 / Contents |
|---|---|
| **[功能详解](docs/FEATURES.md)** | VR 播放、画质增强、实时美颜、LUT 调色、字幕与 ASR、在线翻译、局域网播放 —— 每项功能的完整说明与参数 |
| **[画质增强专题](docs/VIDEO_ENHANCE_MEMC_FSR.md)** | MEMC 插帧与 FSR 超分：默认/自定义规则、优先级、判定边界与保护、管线顺序、实现要点、性能与限制、诊断日志判读 |
| **[技术架构与目录结构](docs/ARCHITECTURE.md)** | 分层架构图、关键组件职责表、仓库目录布局 |
| **[构建与发布](docs/BUILD_AND_RELEASE.md)** | 环境要求、内置 ASR 模型拉取、构建命令、签名与分发纪律 |
| **[隐私与开源许可](docs/PRIVACY_AND_LICENSES.md)** | 本地优先原则、匿名统计的采集范围与关闭方式、依赖与许可清单 |
| **[版本历史](docs/CHANGELOG.md)** | 逐版变更记录（早期简略、近期详细） |
| **[华为 VR Glass UI 设计](docs/HUAWEI_VR_UI_DESIGN.md)** | 华为 VR Glass 侧的界面设计 |

### 仓库根专题文档 / Topic documents

这些是开发过程中的规划与实验记录，记录的是**当时**的设计与结论，实现可能已随版本演进。

| 文档 / Document | 内容 / Contents |
|---|---|
| [HUAWEI_VR_ENGINE_PLAN_2026-09-26.md](HUAWEI_VR_ENGINE_PLAN_2026-09-26.md) | 华为 VR Engine（OpenXR）接入规划与实施记录 |
| [GPUPIXEL_PERF_FACE_OPTIMIZATION_2026-09-29.md](GPUPIXEL_PERF_FACE_OPTIMIZATION_2026-09-29.md) | GPUPixel 美颜性能与人脸检测优化 |
| [GPUPIXEL_TEXTURE_PATH_FEASIBILITY_2026-09-29.md](GPUPIXEL_TEXTURE_PATH_FEASIBILITY_2026-09-29.md) | GPUPixel 纹理零拷贝通道可行性验证 |
| [BEAUTY_EVALUATION_2026-09-23.md](BEAUTY_EVALUATION_2026-09-23.md) | 美颜算法评估报告（椭圆遮罩、频域分离磨皮的依据） |
| [BEAUTY_DUAL_ENGINE_PLAN_2026-09-23.md](BEAUTY_DUAL_ENGINE_PLAN_2026-09-23.md) | 美颜双引擎（GLSL / GPUPixel）规划 |
| [RELEASE_SIGNING.md](RELEASE_SIGNING.md) | Release 签名与发布流程（细节） |
| [DEPENDENCY_MAP.md](DEPENDENCY_MAP.md) | 模块依赖图 |
| [BUG_AUDIT_2026-09-12.md](BUG_AUDIT_2026-09-12.md) | 缺陷审计记录 |
| [FIREBASE_ANALYTICS.md](FIREBASE_ANALYTICS.md) | 用户统计接入说明 |

---

## 🗺️ 未来规划 / Roadmap

- [x] ~~**实现并验证 LUT 滤镜链路**（UI → 纹理 → 着色器采样）~~ ✅ v117 已完成 / Done in v117
- [x] ~~**实时 AI 字幕**（边播边生成 + 翻译预读）~~ ✅ v1.0.126 / Done in v1.0.126
- [x] ~~**多语言 ASR（11 种可下载语言 + 内置 5 语）**~~ ✅ v2.0.148 / Done in v2.0.148
- [ ] **把多语言模型搬到 ModelScope**，免去 102MB/664MB 的整包下载（可改为按文件、国内更快）/ Mirror models on ModelScope for faster per-file downloads
- [ ] 修复陀螺仪漂移（方向问题已在 v125 修正，剩余为长时间 yaw 漂移）/ Fix gyro drift (direction fixed in v125; residual slow yaw drift)
- [ ] 字幕时间轴对齐优化 / Subtitle timing alignment (VAD/endpoint calibration)
- [ ] 人脸关键点 x86_64 支持 / x86_64 face-landmark support (emulator beauty)
- [x] ~~**MPV 解码器**：native 库可选下载、UI 暂时停用~~ ✅ v2.1.243 已**内置进 APK 并恢复 UI 选项**，作为 WMV/ASF/RM/RMVB 的唯一兜底内核 / MPV decoder: **bundled in-APK and re-enabled in the engine picker** since v2.1.243 — the only kernel that can decode WMV/ASF/RM/RMVB
- [x] ~~**MPV 兼容渲染**：WMV 等老编码无硬解器时播放失败~~ ✅ v2.1.244 起改用 `vo=gpu` 渲染，软解硬解都能出画 / MPV compatibility rendering: since v2.1.244 the player uses `vo=gpu` for legacy codecs that lack a hardware decoder
- [x] ~~**MPV 播完被误判为出错**（一播完就回退内核）~~ ✅ v2.1.245 改用事件自带的结束原因判定，并补「打不开的文件」超时提示 / Fixed in v2.1.245: end-of-file detection now uses the event's own reason field, plus a timeout prompt for unopenable files
- [ ] 更多投影模式（CAVE / 半球）/ More projection modes (CAVE / hemisphere)
- [ ] 字幕样式模板 / Subtitle style templates
- [ ] 播放列表与历史记录同步 / Playlist & history sync
- [ ] 8K 硬解实验转正（当前为实验功能，默认关闭）/ Graduate experimental 8K decoding

---

## 📄 许可声明 / License Notice

本项目采用 **Apache License 2.0** 开源（见 [LICENSE](LICENSE)），Copyright © 2026 tianhuoliuhun。
可自由使用、修改、商用与再分发（保留版权与许可声明即可）。

