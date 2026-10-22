# 🎬 Aura Lens 灵境

> 一款面向移动端的**专业级美颜 VR 播放器**：支持 360°/180° 全景、鱼眼、3D SBS/TAB 立体与普通 **2D 平面视频**，
> 内置实时 AI 人脸美颜、3D LUT 电影调色、**60+ 种语言与方言的离线语音转字幕**、14 种翻译引擎与局域网 SMB 播放。
>
> A professional mobile VR player for **plain 2D, 360°/180° panorama, fisheye and 3D SBS/TAB** videos with real-time AI beauty filters, 3D LUT color grading,
> offline ASR subtitles (**60+ languages & dialects**), 13 translation engines and LAN (SMB) playback.

> **版本里程碑 / Milestones**：
> **v1.0** 完成 VR 播放的基本功能（投影 / 立体 / 陀螺仪 / LUT 调色）·
> **v2.0** 完成 AI 语音识别与翻译（实时字幕 / 离线 ASR / 在线翻译 / AI 美颜双引擎）·
> **v2.1** 修复美颜引擎的问题（GPUPixel 闪退 / 覆盖范围 / 闪烁与性能），引入画质增强（MEMC 插帧 / FSR 超分）；
> ASR 扩展至 **87 项语言与方言** 并把 **Dolphin 内置进 APK**（开箱即用），新增时间标记悬浮球与语言选择多级收纳；
> 后期（v2.1.24x）把播放内核扩到 **四个**（EXO / IJK / MPV / 系统解码），
> **MPV 内置进 APK** 作为 WMV·ASF·RM·RMVB 的兜底内核，并用**容器白名单**做内核自动路由；
> **v2.2–v2.4** 引入 **AI 弹幕**（画面 / 台词驱动、上屏渲染、颜色与行距、时间抖动、外部弹幕导入）与**端侧本地大模型**（MiniCPM-V 4.7 1B，一次性下载后**离线**翻译与弹幕生成 —— 🚧 尚未完成）；新增 **VR 眼镜支持**（华为 VR Glass / Pico / Quest，按 flavor 分包 —— 🚧 尚未完成），完成多轮代码审查修复（美颜双引擎 / 本地 AI / 设置面板），并在 **v2.4.58** 加入**投屏接收**（本机作为标准 DLNA 渲染器，接收手机视频 App 推送的 URL 并播放）；
> **v2.6** 重构界面材质 —— 三档玻璃（纯色 / 液态 / 磨砂）：液态改为**真半透明**、直出全分辨率画面，磨砂恢复高斯模糊层，两档共用同一套 Canvas 体感层（顶部受光 / 内侧厚度 / 棱边高光）。
>
> **v1.0** delivered the core VR playback (projection / stereo / gyro / LUT) ·
> **v2.0** delivered AI speech recognition & translation (realtime subtitles, offline ASR, online translation, dual-engine beauty) ·
> **v2.1** fixed the beauty-engine issues (GPUPixel crash, coverage, flicker & performance) and added video enhancement (MEMC / FSR) plus a major ASR language expansion;
> later (v2.1.24x) expanded to **four playback kernels** (EXO / IJK / MPV / system) with **MPV bundled in-APK** as the fallback for WMV/ASF/RM/RMVB, plus container-based auto-routing;
> **v2.2–v2.4** added **AI danmaku** (frame / subtitle-line driven, on-screen rendering, colors, adaptive spacing, timestamp jitter, external import) and an **on-device local LLM** (MiniCPM-V 4.7 1B; offline translation & danmaku generation after a one-time download — work in progress); introduced **VR glasses support** (Huawei VR Glass / Pico / Quest via per-flavor packaging — work in progress) plus several rounds of code-review fixes (beauty engines / local AI / settings panels); **v2.6** reworked the interface material into three glass styles — Liquid is now truly translucent (full-resolution passthrough) and Frosted regains its blur layer, both sharing one canvas-based body layer (top lighting / inner thickness / rim light).

![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-green) ![Kotlin](https://img.shields.io/badge/Kotlin-2.2.10-purple) ![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-blue) ![License](https://img.shields.io/badge/License-Apache%202.0-blue)

---

## ✨ 功能亮点 / Highlights

> 每个功能的完整说明见 **[功能详解](docs/FEATURES.md)**；画质增强另有 **[专题文档](docs/VIDEO_ENHANCE_MEMC_FSR.md)**。

| 功能 / Feature | 亮点 / Highlights |
|---|---|
| 🥽 **VR 播放**<br>VR Playback | 360°/180° 全景、鱼眼、标准平面四种投影，**普通 2D 视频开箱即播**；3D SBS/TAB 立体；陀螺仪跟随与手动偏移；圆柱曲面沉浸；8K 硬解实验（默认关）<br>Four projection modes — **plain 2D videos play out of the box**; SBS/TAB stereo, gyro tracking, cylinder curvature, experimental 8K decoding |
| 🕶️ **VR 眼镜 / OpenXR**<br>VR Glasses & OpenXR | 🚧 **未完成（开发中）** —— 目标支持 **华为 VR Glass / Pico / Quest** 等 VR 眼镜（OpenXR 会话与立体渲染）；已按 **flavor 分包**（主线 `standard`（不编 OpenXR、体积最小）/ 全兼容 `all`（发版）/ 单眼镜 `huawei`·`pico`·`quest`），眼镜包在普通手机上同样可运行；**真机上的渲染与交互验收尚未完成，暂不作为正式功能**<br>🚧 **Work in progress** — intended OpenXR support for Huawei VR Glass / Pico / Quest, split into **per-flavor builds** (mainline `standard` / all-in-one `all` / single-glasses `huawei`·`pico`·`quest`); on-device rendering and interaction are not yet validated, so it is not a shipped feature |
| ⚡ **画质增强**<br>Video Enhancement | **MEMC 运动补偿插帧**（48/60/72/90/120 fps）+ **FSR 超分**（EASU + RCAS，默认规则与自定义 6 档，自定义完全接管）<br>Motion-compensated interpolation plus FSR upscaling with default and fully-overriding custom rules |
| ✨ **实时 AI 美颜**<br>Real-time AI Beauty | **GLSL / GPUPixel 双引擎**可切换；478 点人像精修（瘦脸/大眼/去黑眼圈/鼻梁/嘴型/牙齿/口红/腮红/眉毛）；磨皮为频域分离 + 可调皮肤质感<br>Dual switchable engines, 478-landmark portrait retouch, frequency-separation smoothing |
| 🎨 **3D LUT 调色**<br>LUT Color Grading | 36 款内置 LUT（人像美颜 24 + 风格滤镜 12，全部自研/开源可商用）；可导入自定义 `.cube`<br>36 bundled LUTs plus custom `.cube` import |
| 🗣️ **字幕与语音转写**<br>Subtitles & ASR | 离线 **Dolphin 已内置**（开箱即用）：**40 种东方语言 + 22 种中文方言**，自带语种识别；实时生成、整片转写、SRT 导出、去标点。语言面板点**「自动」即直接使用随包内置模型**（真·语种识别，不下载）<br>Bundled offline ASR (Dolphin): 40 Eastern languages + 22 Chinese dialects with auto language detection, realtime generation, batch transcription and SRT export. The 「Auto」 entry uses the **bundled** model directly — no download |
| 🌐 **字幕在线翻译**<br>Online Translation | **14 种引擎**（6 个免密端点：必应 / Google / MyMemory / LibreTranslate / **Pollinations** / **Reverso**；另有官方 API 的 DeepL 与完全本地的离线模型）；本地词库缓存（LRU + TTL + 按语言分文件）<br>14 engines including 6 keyless endpoints, the official DeepL API and an on-device model, with a local per-language translation memory |
| 🎤 **发声人分离 / 去男声**<br>Speaker Separation | 🚧 **未完成（开发中）** —— 目标：分析音轨判断「谁在说话、什么性别」，并把男声段落从人声里分离出去（sherpa-onnx 说话人分离 + ONNX 性别分类 + MDX-Net 源分离）；目前**离线分析面板与单段处理已跑通**，长片流式处理与效果验收未完成<br>🚧 **Work in progress** — intended speaker diarization plus gender classification and MDX-Net source separation so male-voice segments can be removed; the offline analysis panel and short-clip path work, long-video streaming and quality validation are not done |
| 🧠 **端侧本地大模型**<br>On-device LLM | 🚧 **未完成（开发中）** —— 目标是下载一次后**全程离线**：本地 AI 翻译 + 本地弹幕生成（**MiniCPM-V 4.7 1B，Q4_K_M 量化，Apache 2.0** + 自建 llama.cpp / libmtmd JNI；主权重约 672MB，可选视觉编码器 mmproj 约 1.11GB）；**推理链路已跑通**（单 worker 调度 + 优先级队列：实时字幕 > 弹幕 > 远期预翻译；超时与失败原因如实回传；不联网、不上传内容），但**翻译与弹幕的实际输出质量尚未达到可用**，故按未完成论<br>🚧 **Work in progress** — goal is fully offline local AI translation and danmaku generation (**MiniCPM-V 4.7 1B, Q4_K_M, Apache 2.0** with a self-built llama.cpp / libmtmd JNI; ~672MB main weights, optional ~1.11GB mmproj). The inference path works (single-worker scheduler with a **priority queue**, timeouts and real failure reasons surfaced), but output quality is not yet usable, so it is not a shipped feature |
| 📁 **局域网与远程播放**<br>LAN & Remote | SMB（jcifs-ng）浏览与直连；HTTP 缓存数据源让不支持 Range 的源也能拖动；远程源同样支持实时字幕<br>SMB browsing, a caching data source for seamless remote seek, subtitles for remote sources |
| 📺 **投屏接收（DLNA）**<br>Cast Receiver | **v2.4.58**：设置里一个开关，本机即以标准 **DLNA 渲染器（`MediaRenderer:1`）** 出现在手机视频 App 的投屏列表中；手机把视频 **URL** 推过来后，**复用整条播放链路**（投影 / 字幕与 ASR / AI 弹幕 / 美颜 / 画质增强 / VR 渲染，四内核自动路由），并支持手机端遥控播放与进度回读。⚠️ 仅「URL 推送式」投屏，**不支持屏幕镜像**<br>**v2.4.58** DLNA renderer: flip one switch and phone video apps can cast to this device; the pushed URL **reuses the entire playback pipeline** with remote play/pause/seek. URL-push only — no screen mirroring |
| 🧊 **界面材质 / Glass UI** | **三档可切换**（纯色 / 液态玻璃 / 磨砂玻璃，Android 12+）：**液态档真半透明** —— 不做位图采样，把 Compose 半透明层与视频图层直接合成，透出**全分辨率**画面，并保留轻薄的顶部受光与棱边高光；**磨砂档**采样 96px 降采样帧 + 高斯模糊（面板 32dp）+ 颗粒噪点，背后内容被真正漫射掉。两档共用同一套 Canvas 体感层（顶部受光 / 内侧厚度 / 棱边高光），零 RenderEffect，被 ROM 屏蔽实时模糊的设备同样可用；悬浮球与面板**同源**（`glassBall` = `glassPanel` + `CircleShape`）<br>**Three switchable styles** (Solid / Liquid / Frosted, Android 12+): Liquid is truly translucent — no bitmap sampling, the translucent Compose layer composites straight over the video layer to show the **full-resolution** frame while keeping a light top highlight and rim light; Frosted samples a 96px downscaled frame plus a 32dp blur and a grain overlay so the content behind is genuinely diffused. Both share one canvas-based body layer with zero RenderEffect, so they also work where the ROM blocks real-time blur; floating balls reuse the panel implementation |
| ⏩ **进度条拖动**<br>Seeking | **松手才定位**（拖动中只更新预览，不堆积 seek）—— **AVI / AV1 也能顺畅拖动**；容器不支持定位时**不重新编码**地自动重封装修复；AVI 内嵌 AV1 明确提示改用 MPV 内核<br>Seek-on-release keeps AVI/AV1 dragging smooth; auto remux (no re-encode) when a file cannot seek |
| 🎞️ **多内核与格式兼容**<br>Multi-kernel & Formats | **四个内核可选**（EXO / IJK / MPV / 系统解码）+ 自动路由：打开 **WMV / ASF / RM / RMVB / ISO / VOB** 等片源时**本次自动切换**到能吃它的内核（不改写你的设置）。**MPV 已内置进 APK**，无需下载即可播放上述格式<br>Four selectable kernels with container-based auto-routing; **MPV is bundled in-APK** so WMV/ASF/RM/RMVB/ISO/VOB play out of the box |
| 📄 **字幕格式**<br>Subtitle Formats | 外挂 **SRT / VTT / ASS / SSA**（按内容自动识别）；**MKV 内挂 ASS/SRT 字幕轨**也可切换；字幕文件**编码自动探测**（BOM / UTF-8 / GBK / Big5），中文老字幕不再乱码<br>External **SRT / VTT / ASS / SSA** (auto-detected), **embedded ASS/SRT in MKV**, and charset auto-detection (BOM / UTF-8 / GBK / Big5) |
| ✅ **AI 弹幕**<br>AI Danmaku | **v2.4.0 起弹幕真正上屏，v2.4.1 起可用 AI 台词作为素材，v2.4.2 面板与字幕统一并加预设人格，v2.4.4 换默认端点并加「测试连接」，v2.4.5 失败原因如实显示，v2.4.6 行距自适应 + 随机颜色，v2.4.7 时间戳正态抖动 + 外部弹幕导入**：独立顶级设置分组 + 颜色（文字/描边/背景，与字幕色独立）；**颜色模式三选一**（单一颜色 / **完全随机** / **80% 白 + 其余随机**，逐条独立掷色、入队时定死；随机色板 6 色**含黑除外**，因弹幕无底框、黑字在暗场景不可读）；**行距自适应**（轨道高度 = `max(字号 × 1.9, 区域高 ÷ 轨道数)`，字号调大时行距自动变大、不再上下贴紧）；**时间戳正态抖动**（同批弹幕以基准时刻为均值做高斯抖动、±2.5σ 截断，抖动**只向未来**取用并按升序分配给原下标 → 保持 AI 返回顺序严格单调、错落出现而非「一坨同时冒出」，且每条都从屏幕右侧飞入；强度可调、0 = 关闭）；**外部弹幕导入**（「内容来源」二选一：AI 生成 / 本地导入，支持 **B 站 XML** 与 **JSON 数组**、逐条容错、只收滚动类；导入内容只驻留内存不占 prefs，seek 自动重置游标）；六个**可折叠区块**（展开状态跨会话记忆），**「预设风格」chip 一行选**（默认/搞笑/影迷/情绪/安静，点选填入提示词**不锁定**、手改即转「自定义」）；**素材三选一**（画面+台词 / 仅画面 / 仅台词，台词取自当前位置前 15 秒 / 后 5 秒的 AI 字幕，仅台词模式**不请求截图**、更快更省流量）；画面识别（OpenAI 兼容 `image_url`，默认 **AMD Radeon 平台 · MiMo-V2.6-Flash**）→ 弹幕引擎（相似度去重 + 轨道避让，附单测）→ **在画面上方约 30% 区域分轨道实时飘出**。**分屏 VR 左右眼各一份**，暂停即冻结、seek 即清空；**「测试连接」按钮**一次点按即逐类报出「密钥无效 / 模型名不被识别 / 连接被重置 / 超时」等原因，不再需要翻日志<br>**Danmaku lands on screen in v2.4.0; v2.4.1 adds AI subtitle lines as source material; v2.4.2 unifies the panel with subtitles and adds persona presets; v2.4.4 changes the default endpoint and adds "Test connection"; v2.4.5 makes failures report their real cause; v2.4.6 adds adaptive line spacing and random colors; v2.4.7 adds normal-distributed timestamps and external import**: own settings group + color (text/stroke/background, independent from subtitles); **three color modes** (single / **fully random** / **80% white + random** — rolled per danmaku and fixed at enqueue time; the 6-color palette **excludes black** since danmaku has no backing box); **adaptive line spacing** (track height = `max(font size × 1.9, area height ÷ track count)`); **normal-distributed timestamps** (Gaussian jitter around the batch base instant, clipped at ±2.5σ; jitters are **sorted then consumed by original index**, keeping the AI output order strictly monotonic so danmaku stagger instead of clumping; strength is adjustable, 0 = off); **external danmaku import** ("Content source": AI generated / local import, supporting **Bilibili XML** and **JSON arrays** with per-item fault tolerance and scrolling-only filtering; imported content stays in memory rather than prefs, and seek resets the cursor); six **collapsible sections** (state persisted) and a **preset-style chip row** (Default / Comedy / Cinephile / Emotional / Calm — fills the prompt **without locking it**); **three-way source** (frame+lines / frame only / lines only — lines taken from AI subtitles 15s before and 5s after the playhead; lines-only mode **sends no screenshot**, faster and lighter); vision (OpenAI-compatible `image_url`, defaults to the **AMD Radeon platform · MiMo-V2.6-Flash**) → engine (dedup + track avoidance, unit tested) → **scrolling across the top ~30% of the screen on multiple tracks**. Per-eye rendering in split-screen VR; pause freezes, seek clears; the **"Test connection" button** reports invalid key / model not found / reset connection / timeout individually, no more log digging |

---

## 📱 兼容性 / Compatibility

**平台**：本应用为 **Android 手机/平板应用**（非 iOS / 桌面 / Web）
**Platform**: Android **smartphone/tablet** app (not iOS / desktop / web)

**支持安卓版本 / Android Versions**：
- **minSdk 26（Android 8.0 Oreo）** — 最低支持 Android 8.0（v2.4.58 起：DLNA 投屏接收所依赖的 Jetty 需要 `java.lang.invoke`，系统要求 API 26+）
- **targetSdk 36（Android 16）** — 针对最新系统适配
- 推荐 Android 10+ / Android 10+ recommended

**发布包形态 / Release artifact**：

| 项目 | 说明 / Notes |
|---|---|
| 产物 | **单个 APK**（含内置 ASR 模型与解码内核）；文件名由 Gradle 任务**自动带版本号**：`Aura-Lens-v<版本>-<flavor>.apk`（发版用 `all`，如 `Aura-Lens-v2.6.9-all.apk`）/ Single APK with the ASR model & decoder kernels bundled; the Gradle rename task stamps the version into the file name: `Aura-Lens-v<version>-<flavor>.apk` (`all` for releases) |
| 体积 | 约 **210MB**（`all` 全兼容包；其中内置 Dolphin ASR 模型约 99MB、原生解码 / 推理库约 43MB）/ ~210MB (the `all` build; ~99MB Dolphin ASR model + ~43MB native decoder / inference libs) |
| 包含 ABI | **仅 `arm64-v8a`**（v2.4.10 起）。闭源预编译库（旷视 Mars-Face / MediaPipe / VR SDK）只提供 arm64 / v7a，`CMakeLists.txt` 对缺失 ABI 是 `FATAL_ERROR`，故 **x86 / x86_64 物理上无法构建** / **`arm64-v8a` only** (since v2.4.10): the closed-source prebuilt libs ship only arm64 / v7a and CMake fails hard on a missing ABI, so x86 / x86_64 cannot be built at all |
| 安装 | 普通手机与 VR 眼镜均直接安装，系统自动选原生库 / Installs directly on both phones and VR glasses; the OS picks the native libs |

**构建 flavor / Build flavors**（`flavorDimensions = "vr"`）：

| flavor | 用途 / Purpose | OpenXR |
|---|---|---|
| `standard` | 主线包（日常自用，体积最小，不编 OpenXR）/ Mainline build (daily use, smallest, no OpenXR) | — |
| `all` | **全兼容发版包** / All-in-one release build | ✓ |
| `huawei` · `pico` · `quest` | 单眼镜优化包 / Single-glasses builds | ✓ |

> 说明：早期版本曾按 ABI 拆分为 4 个包发布，v116 起改为**单包**发布；v2.4.10 起 ABI 进一步收敛为 **arm64-v8a**。
> Note: early releases shipped 4 ABI-split APKs; since v116 it is a **single package**, and since v2.4.10 the ABI is narrowed to **arm64-v8a**.

---

## 🚀 快速开始 / Quick Start

```powershell
# 0) 环境：JDK 17+、Android SDK、Gradle 9.6.1（本机示例为 JDK 21）
#    ⚠️ 本仓库不含 Gradle Wrapper（无 gradlew），请直接调用本机 gradle
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"   # 按本机路径调整

# 1) 构建 —— ⚠️ 必须带 flavor，否则 assembleRelease 会把 5 个 flavor 全编一遍
gradle.bat :app:assembleStandardRelease                     # 日常主线包（体积最小，不编 OpenXR）
gradle.bat :app:assembleAllRelease -x lintVitalAllRelease   # 发版全兼容包
# 产物：app\build\outputs\apk\all\release\Aura-Lens-v<版本>-all.apk（单 APK，arm64-v8a，含内置模型）

# 可选：额外拉取 SenseVoice-Small（约 228MB，超 GitHub 单文件限制故不入 git）
#       内置的 Dolphin 已随仓库提供，走「自动」语种无需此步
# python scripts/fetch_asr_model.py
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
| 2 | **安装包较大（约 210MB）**——ASR 模型（Dolphin）已内置以保证开箱即用；若需精简版可自行移除 `assets/dolphin/` 并改用下载兜底 | **Large APK (~210MB)** — the bundled ASR model (Dolphin) makes it work out of the box; remove `assets/dolphin/` for a slimmer build with download fallback |
| 3 | **陀螺仪漂移**——长时间观看后水平朝向缓慢漂移，双击画面重置视角即可（原理性，GAME_ROTATION_VECTOR 无绝对北向基准） | **Gyroscope drift** — yaw drifts slowly over long sessions; double-tap to recenter (inherent to game rotation vector) |
| 4 | **AI 字幕多行时间线可能不匹配**——断句/静音判断误差导致时间轴偏移 | **Multi-line ASR subtitle timeline mismatch** — auto-generated timestamps may not perfectly align |
| 5 | **免费翻译端点有额度限制**——必应与 Google 均为非官方网页端点、MyMemory 匿名仅 5000 字符/天（已内置限速与冷却），重度使用建议自配 LLM API Key 或自建 LibreTranslate | **Free translation endpoints are rate-limited** — Bing and Google are both unofficial web endpoints; MyMemory allows ~5000 chars/day anonymously (pacing & cooldown built in) |
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
- [x] ~~**人脸关键点 x86_64 支持**~~ ❌ **不再计划** —— v2.4.10 起 ABI 收敛为 `arm64-v8a`，闭源库只提供 arm64 / v7a，x86_64 无法构建 / ~~x86_64 face-landmark support~~ **dropped** — the ABI is `arm64-v8a` only since v2.4.10 (blobs ship arm64 / v7a)
- [x] ~~**MPV 解码器**：native 库可选下载、UI 暂时停用~~ ✅ v2.1.243 已**内置进 APK 并恢复 UI 选项**，作为 WMV/ASF/RM/RMVB 的唯一兜底内核 / MPV decoder: **bundled in-APK and re-enabled in the engine picker** since v2.1.243 — the only kernel that can decode WMV/ASF/RM/RMVB
- [x] ~~**MPV 兼容渲染**：WMV 等老编码无硬解器时播放失败~~ ✅ v2.1.244 起改用 `vo=gpu` 渲染，软解硬解都能出画 / MPV compatibility rendering: since v2.1.244 the player uses `vo=gpu` for legacy codecs that lack a hardware decoder
- [x] ~~**MPV 播完被误判为出错**（一播完就回退内核）~~ ✅ v2.1.245 改用事件自带的结束原因判定，并补「打不开的文件」超时提示 / Fixed in v2.1.245: end-of-file detection now uses the event's own reason field, plus a timeout prompt for unopenable files
- [ ] 更多投影模式（CAVE / 半球）/ More projection modes (CAVE / hemisphere)
- [ ] 字幕样式模板 / Subtitle style templates
- [ ] 播放列表与历史记录同步 / Playlist & history sync
- [ ] 8K 硬解实验转正（当前为实验功能，默认关闭）/ Graduate experimental 8K decoding

---

## ⚠️ 版权与免责声明 / Copyright & Disclaimer

**播放内容**：本应用是**纯播放工具**，不提供、不内置、不分发任何音视频内容。所有播放内容
（本地文件 / 网络地址 / SMB / DLNA 推送）均由用户自行选择或提供，**用户须确保其拥有合法权利，
并自行承担因播放未授权内容产生的责任**。字幕转写、翻译、AI 弹幕、截图与录制等衍生内容同样由用户负责。
<br>**Played content**: this app is a pure playback tool — it ships no media. All content is provided by
the user, who is responsible for having the rights to play it.

**Bing 翻译**：「字幕在线翻译」的 Bing 引擎调用 **Bing 网页版翻译端点**（`cn.bing.com/ttranslatev3`），
**不是 Microsoft 官方 API**。本项目与 Microsoft **无关联、未获授权与背书**（"Bing / Microsoft" 为微软商标，
仅用于描述所调用的服务）。该端点面向浏览器设计，第三方程序自动化调用**可能不符合 Microsoft 服务条款**，
可用性也可能随时变化 —— **商业分发建议改用有明确授权的官方接口**（如 Azure AI Translator）。
启用该引擎时字幕文本会发送至该端点，请勿翻译敏感内容。
<br>**Bing translation**: the Bing engine calls Bing's **web** endpoint, not an official Microsoft API.
This project is not affiliated with or endorsed by Microsoft; automated use of that endpoint may not
comply with Microsoft's terms — prefer an officially licensed API for commercial distribution.

**Google 翻译**：「字幕在线翻译」的 Google 引擎调用 **Chrome 词典扩展所用的网页端点**
（`clients5.google.com/translate_a/t?client=dict-chrome-ex`），**不是 Google Cloud Translation 官方 API**
（官方接口需 API Key 与计费账户）。本项目与 Google LLC **无关联、未获授权与背书**（"Google / Google
Translate" 为其商标，仅用于描述所调用的服务）。该端点面向浏览器扩展设计，第三方程序自动化调用
**可能不符合 Google 服务条款**，可用性也可能随时变化（会在 `200` / `429` / `403` 之间切换）——
**商业分发建议改用有明确授权的官方接口**或其它合规引擎（内置的 DeepL 官方 API、自配 Key 的接口、
本地离线模型）。启用该引擎时字幕文本会发送至该端点，请勿翻译敏感内容。
<br>**Google translation**: the Google engine calls the **web** endpoint used by Chrome's dictionary
extension, not the official Google Cloud Translation API (which requires a key and billing). This project
is not affiliated with or endorsed by Google; automated use of that endpoint may not comply with Google's
terms and its availability may change at any time — prefer an officially licensed API for commercial use.

**Pollinations 翻译**：「字幕在线翻译」的 Pollinations 引擎调用其**公开的免密钥文本网关**
（`text.pollinations.ai`，短请求模式 `GET /<prompt>`），无需注册与 API Key。该服务由**第三方独立运营**，
本项目与其**无关联、未获背书**；可用性、频率限制与输出内容均由服务方决定、可能随时变化
（免费层有限流，命中时本应用暂停 60 秒并提示恢复时间）。启用该引擎时字幕文本会发送至该服务，
请勿翻译敏感内容。
<br>**Pollinations translation**: the Pollinations engine calls its **public keyless text gateway**
(short-request mode `GET /<prompt>`); no sign-up or API key is needed. It is an independently operated
third-party service with no affiliation to this project; its availability, rate limits and output are
outside our control and may change at any time (the free tier is rate-limited — the app pauses for 60 s
and reports the recovery time). Subtitle text is sent to that service — do not translate sensitive content.

**Reverso 翻译**：「字幕在线翻译」的 Reverso 引擎调用 **reverso.net 的网页翻译端点**
（`api.reverso.net/translate/v1/translation`，其后端为 Lingvanex 引擎），无需注册与 API Key。
本项目与 Reverso Technologies **无关联、未获授权与背书**；该端点面向网页端设计，第三方程序自动化调用
**可能不符合其服务条款**，可用性亦可能随时变化（前置 Cloudflare 会对机房 / 代理 IP 返回 403 挑战，
命中时本应用暂停 60 秒并提示恢复时间），免费额度限制由服务方单方决定 —— 商业分发建议改用
**有明确授权的官方接口**（如内置的 DeepL 官方 API）或本地离线模型。启用该引擎时字幕文本会发送至该端点，
请勿翻译敏感内容。
<br>**Reverso translation**: the Reverso engine calls **reverso.net's web translation endpoint**
(`api.reverso.net/translate/v1/translation`, backed by the Lingvanex engine) with no sign-up or API key.
This project is not affiliated with, licensed by or endorsed by Reverso Technologies; the endpoint is built
for the web app and automated third-party use may not comply with their terms, and its availability may
change at any time (Cloudflare fronting returns a 403 challenge for datacenter / proxy IPs — the app then
pauses for 60 s and reports the recovery time). Free-tier limits are set unilaterally by the operator —
prefer an officially licensed API (such as the bundled DeepL API) or an on-device model for commercial use.
Subtitle text is sent to that endpoint — do not translate sensitive content.

完整版见 **[内容与第三方服务声明](docs/CONTENT_AND_SERVICE_DISCLAIMER.md)**，
第三方组件许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) 与 [NOTICE](NOTICE)。

## 📄 许可声明 / License Notice

本项目采用 **Apache License 2.0** 开源（见 [LICENSE](LICENSE)），Copyright © 2026 tianhuoliuhun。
可自由使用、修改、商用与再分发（保留版权与许可声明即可）。

