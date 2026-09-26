# DEPENDENCY_MAP — 光域 Aurelia 依赖映射

> Task: **P0-01 Repository Audit**
> 配套文档: `MIGRATION_AUDIT.md`（规模与风险）、`AI_MIGRATION_PLAN.md`（架构目标）
> 审计对象: `tianhuoliuhun/Aura-face-VR-Player` @ `86ec109` / v2.0.138
>
> **本文件是后续每个 Task 的"寻路地图"**：新增任何 Core 接口前先在这里查表，确认它对应哪一块现有实现、哪个平台 API、以及 Harmony 侧的替代路径是否已验证。

---

## 1. 当前依赖拓扑（实测，非设计）

### 1.1 包结构现状

```
com.example
├── MainActivity.kt              ← Activity + 隐私弹窗 + Intent 接收 + 语言 Host
├── AppApplication.kt            ← Application
├── ui/theme/                    ← 3 个主题文件（Compose）
└── vr/                          ← 38 个文件平铺，无子目录
    ├── 【UI 巨石】              VRPlayerScreen(5321) / SubtitleSettingsPanel(1411)
    │                            BeautySettingsSections(455) / PlayerControlBar(232)
    │                            VRPlayerComponents(159) / LicensesScreen(168)
    │                            AsrBatchSection(216) / SubtitleOverlay(379) / SubtitledText(255)
    ├── 【渲染】                  VRGLRenderer(1513) / VRGLSurfaceView(218)
    │                            GeometryHelper(222) / LutUtils(183)
    ├── 【播放】                  ExperimentalDecode(478) / VideoRemuxer(205)
    │                            DecoderCapabilities(76) / PlaybackPositions(48)
    │                            StereoChannelSwappingAudioProcessor(51)
    ├── 【人脸/美颜】             MediaPipeFaceManager(228) + VRGLRenderer 内 shader
    ├── 【字幕】                  SubtitleModels(133) / SubtitleCache(76) / SubtitleExporter(124)
    │                            SubtitleFontHelper(54) / OnlineSubtitleSearch(125)
    ├── 【ASR】                   RealtimeSubtitleEngine(1036) / SherpaAsrManager(431)
    │                            AsrModels(18)
    ├── 【翻译】                  SubtitleTranslator(790)
    ├── 【媒体/网络】             DemoMediaProvider(426) / SmbDataSource(59)
    ├── 【传感器】                VRSensorManager(244)
    ├── 【设置/本地化】           LanguageManager(147) / UiThemePalette(108) / VRMediaModels(54)
    └── 【统计】                  AnalyticsManager(88)
```

### 1.2 依赖方向问题（Core 抽取必须先解决的 3 个）

| 问题 | 现象 | 后果 |
| --- | --- | --- |
| **① Context 渗透** | 19/40 个文件持有 `android.content.Context`，用于 `assets` / `filesDir` / `prefs` / `getString` | 任何"业务类"都带上 Android 依赖，无法作为 Core |
| **② 反向依赖** | `SherpaAsrManager`、`RealtimeSubtitleEngine`、`LanguageManager` 等**反向 import `com.example.R`** 取字符串资源 | 业务逻辑依赖 UI 资源表。`AsrModels.kt` 的注释显示历史上已有过一次此类清理（v119 把 `AsrEngineType` 从 `VRPlayerScreen.kt` 搬出） |
| **③ 上帝类引用** | `VRSensorManager(context, renderer: VRGLRenderer)` 直接持有渲染器；`VRGLRenderer` 直接持有 `MediaPipeFaceManager`；`VRPlayerScreen` 持有其余全部 | 传感器、渲染、人脸三方互相直连，无法单独替换任一实现 |

### 1.3 编译期依赖分类

**A. Android 强绑定（Harmony 必须替换）**

| 依赖 | 版本 | 使用点 | 说明 |
| --- | --- | --- | --- |
| `androidx.compose.*` | BOM 2024.09.00 | 444 / 18 文件 | 全部 UI。Harmony 用 ArkUI 重写 |
| `androidx.activity.compose` | 1.10.1 | 3 | 含 `rememberLauncherForActivityResult`（SAF 选取、LUT 选取） |
| `androidx.lifecycle.*` | 2.8.7 | — | runtime/viewmodel/compose |
| `androidx.media3:media3-exoplayer` | 1.4.1 | 68 / 7 文件 | 播放核心 + `DataSource` + `AudioProcessor` |
| `androidx.media3:media3-transformer` | 1.4.1 | 7 | 降级转码、remux 修复 |
| `androidx.media3:media3-effect` | 1.4.1 | — | 转码特效链 |
| `androidx.media3:media3-common` | 1.4.1 | 4 | |
| `com.google.mediapipe:tasks-vision` | 0.10.14 | 5 / 1 文件 | Face Landmarker（478 点，VIDEO 模式） |
| `com.google.firebase:firebase-analytics` | BOM 34.12.0 | 2 / 1 文件 | **当前未激活**（无 google-services.json） |
| `io.github.kyant0:backdrop` | 1.0.6 | — | ``Compose`` Liquid Glass 特效 |
| `androidx.room:*` | 2.7.0 | **0** | **未使用**（见 §7） |
| sherpa-onnx | AAR 1.13.6 | 15 / 2 文件 | Android 专用 AAR |

**B. 纯 Java / 可移植（Core 或 Harmony 可复用或易替代）**

| 依赖 | 版本 | 使用点 | 可移植性 |
| --- | --- | --- | --- |
| `kotlinx-coroutines-core/android` | 1.10.2 | 广泛 | KMP 原生支持 |
| `okhttp3` | 4.10.0 | 11 / 3 文件 | 纯 Java HTTP。Harmony 可用 `@ohos.net.http` 替代 |
| `eu.agno3.jcifs:jcifs-ng` | 2.1.8 | 6 / 2 文件 | 纯 Java SMB 客户端。**理论可移植**，但依赖 JVM socket/线程（Harmony 侧需验证或改用 native SMB） |
| `com.googlecode.soundlibs:jlayer` | 1.0.1.4 | — | 纯 Java MPEG Layer I/II/III 解码兜底。Harmony 需等价方案或 native 解码 |
| `org.apache.commons:commons-compress` | 1.27.1 | — | 纯 Java，tar.bz2 解压（ASR 模型包） |
| `org.json` | 平台自带 | 4 | Harmony 有 `@ohos.util.json` |
| **未使用但已引入**：`retrofit` 2.12.0 / `converter-moshi` 2.12.0 / `moshi-kotlin` 1.15.2 / `logging-interceptor` 4.10.0 | — | **0** | 见 §7 |

**C. 仅编译期 / 测试**

| 依赖 | 用途 |
| --- | --- |
| `com.google.devtools.ksp` 2.3.5 | Room / Moshi 代码生成（Room 未用，Moshi 未用 → KSP 实际空转） |
| `robolectric` 4.16.1 / `roborazzi` 1.59.0 | 单元测试与截图测试（`app/src/test` 仅 4 文件） |
| `secrets-gradle-plugin` 2.0.1 | `.env` 注入 |

---

## 2. 目标依赖拓扑（计划 §2 / §5）

```
                     ┌──────────────────────────────┐
                     │   Aura Core（平台无关）       │
                     │  model / player / vr /       │
                     │  renderer / face / beauty /  │
                     │  subtitle / asr / translator │
                     │  network / repository        │
                     └──────────────┬───────────────┘
               ┌────────────────────┼────────────────────┐
               ▼                    ▼                    ▼
      ┌────────────────┐   ┌────────────────┐   ┌────────────────┐
      │ Android Adapter│   │ Harmony Adapter│   │  iOS Adapter   │
      │  Compose       │   │   ArkUI        │   │  SwiftUI       │
      │  ExoPlayer     │   │   AVPlayer     │   │  AVFoundation  │
      │  MediaCodec    │   │   AVCodec      │   │  VideoToolbox  │
      │  MediaPipe     │   │   Vision/HiAI? │   │  Vision        │
      │  GLSurfaceView │   │   XComponent   │   │  Metal         │
      │  SensorManager │   │   Sensor       │   │  CoreMotion    │
      │  SharedPreferences │  RDB/Preferences│  │  UserDefaults │
      └────────────────┘   └────────────────┘   └────────────────┘
```

**Core 内允许出现的唯一 JVM 依赖**：`kotlinx-coroutines-core`（纯 Kotlin 协程，无平台 API）。其余一律不得进入 Core。

---

## 3. 逐能力映射表（核心章节）

每张表列：**能力 → 当前实现 → 平台 API → 拟定 Core 接口 → Harmony 实现路径 → 风险**。

### 3.1 播放 / Player

| 项 | 内容 |
| --- | --- |
| 当前实现 | `VRPlayerScreen.setupVideoPlayer()`（1617–1983，367 行）、`PlaybackPositions.kt`、`ExperimentalDecode.kt` |
| 平台 API | `ExoPlayer.Builder` / `DefaultRenderersFactory` / `MediaCodecSelector` / `DefaultDataSource.Factory` / `Player.Listener` / `TrackSelectionParameters` / `Surface` |
| 能力清单 | 播放/暂停/seek/倍速/音量、轨道选择（音/字幕）、视频尺寸回调、重复模式、最大分辨率限制、硬解/软解切换、8K 硬解参数注入、MediaCodec 降级与重绑、超分辨率欺骗、降级转码（transformer）、remux 修复、Level 5.1 补丁、播放位置记忆 |
| 拟定 Core 接口 | `Player`（load/play/pause/seekTo/stop/release/setVolume/setPlaybackSpeed + `StateFlow<PlayerState>`）、`PlaybackPositionRepository` |
| Harmony 路径 | `@ohos.multimedia.media.AVPlayer`（播放/seek/倍速/音量/轨道）。**`Surface` 等价物是 `XComponent` 的 `SurfaceId`**。硬解黑科技（`ExperimentalDecode` 478 行）大多不可直接迁移，需按 Harmony 解码器行为重做 |
| 风险 | **MEDIUM**。基础播放可控；`ExperimentalDecode` / `VideoRemuxer` 是 Android MediaCodec 深度定制，属"重新实现"而非"移植" |

### 3.2 渲染 / Renderer + VR 数学

| 项 | 内容 |
| --- | --- |
| 当前实现 | `VRGLRenderer.kt`(1513) / `VRGLSurfaceView.kt`(218) / `GeometryHelper.kt`(222) |
| 平台 API | `GLSurfaceView`、`GLES20.*`（213 处）、`GLES11Ext`（OES 外部纹理）、`SurfaceTexture`、`android.opengl.Matrix`、`javax.microedition.khronos.egl.EGLConfig` |
| GL 版本 | **EGL 上下文版本 2**（`setEGLContextClientVersion(2)`）、GLSL **ES 1.00** shader、8/8/8/8/16/0 配置 |
| 几何 | 球面/半球（40×40 细分）、六面体（16×16 细分 + 逆向射线 UV 映射）、Quad；曲率变形 `cylinderCurvature` |
| 顶点/片元 shader | 内嵌于 `VRGLRenderer`（`buildMainProgram(videoVariant)`），承载投影变形 + 立体 + 13 项美颜 + LUT |
| 拟定 Core 接口 | `Renderer`（initialize/resize/render/setProjection/setTexture/setEffect/release）、`VRProjection`、`StereoMode`、`CameraState` / `HeadPose`、`BeautyParameters` |
| Harmony 路径 | `XComponent(type=SURFACE)` → `NativeWindow` → EGL → OpenGL ES。**关键有利点：GLSL ES 1.00 shader 源码可直接复用**（Harmony 的 GLES 支持同版本 GLSL） |
| 风险 | **LOW-MEDIUM**。shader 与几何算法可复用是最大的跨端红利；Android 侧无 C++ 层意味着 Harmony native 部分要新写，但**参考实现完整存在** |

### 3.3 人脸 / 美颜

| 项 | 内容 |
| --- | --- |
| 当前实现 | 检测：`MediaPipeFaceManager.kt`(228)；美颜：`VRGLRenderer` 内 GLSL |
| 平台 API | MediaPipe `FaceLandmarker`（VIDEO 模式）、`BitmapImageBuilder`、`android.media.FaceDetector`（兜底）、`glReadPixels` 取帧 |
| 检测规格 | 478 点模型，**实际只取 12 个语义点**（眼内外角/鼻尖/下巴/脸颊/嘴角/唇中点），输出 12 个归一化标量 + `hasDetailedLandmarks` |
| 检测调度 | `faceExecutor` 单线程池 + 双缓冲帧传递（GL 线程发布、检测线程消费）+ 512→256 降采样 + 隔帧采样 |
| 美颜参数（17 个 `@Volatile`） | `beautyLevel`(磨皮) / `brightnessLevel` / `contrastLevel` / `beautyWhitening` / `beautyFaceSlimming` / `beautyBigEyes` / `beautyDarkCircles` / `beautyNoseSlimming` / `beautyMouth` / `beautyTeethWhitening` / `beautyLipstick` / `beautyBlush` / `beautyEyebrows` / `beautyLongLegs` / `beautySmallHead` + `isSplitScreenVR` / `monoEyePreference` |
| 拟定 Core 接口 | `FaceDetector`（detect(frame) → FaceLandmarks）、`FaceLandmarks`、`BeautyEngine`（enable/disable/setParameter/process/reset）、`BeautyParameters` |
| Harmony 路径 | 检测：**待验证**（Harmony Vision / HiAI / 或自建 sherpa-onnx 同类 ONNX 推理）。美颜：**优先移植现有 GLSL**（跨端可复用），GPUPixel 降级为备选 |
| 风险 | **HIGH**（检测侧）。计划 §20 已设 STOP 条件，本审计确认该 STOP 条件必须保留 |

### 3.4 字幕

| 项 | 内容 |
| --- | --- |
| 当前实现 | `SubtitleModels.kt`(133，含解析器 + 5 个样式枚举) / `SubtitleCache.kt`(76) / `SubtitleOverlay.kt`(379) / `SubtitledText.kt`(255) / `SubtitleExporter.kt`(124) / `SubtitleFontHelper.kt`(54) / `OnlineSubtitleSearch.kt`(125) |
| 支持格式 | **SRT / VTT（同一解析器）**；**ASS 未实现** |
| 时间轴 | `SubtitleCue(id, startTimeMs, endTimeMs, text)`；`SubtitleCache` 用 `TreeMap<Long, SubtitleCue>` 做 O(log n) `floorEntry` 查询（每帧调用） |
| 渲染 | Compose 绘制（`SubtitleOverlay` + `SubtitledText`），支持字体/字号/字重/斜体/颜色/透明度/描边/背景/对齐/偏移 X-Y/延迟/最大行数 |
| 自动保存/加载 | `_asr.srt` 与视频同目录；`subtitle_user_disabled` 标记阻止自动重新开启 |
| 拟定 Core 接口 | `SubtitleCue`、`SubtitleTrack`、`SubtitleRepository`（load/save/cache）、`SubtitleParser`（SRT/VTT/ASS） |
| Harmony 路径 | 解析与缓存**直接复用**（纯 Kotlin）。渲染用 ArkUI Text 组件重做（样式映射需重写） |
| 风险 | **LOW**。是全项目最干净、最适合第一个进 Core 的模块 |

### 3.5 ASR

| 项 | 内容 |
| --- | --- |
| 当前实现 | `RealtimeSubtitleEngine.kt`(1036) / `SherpaAsrManager.kt`(431) / `AsrModels.kt`(18) |
| 平台 API | `android.media.MediaExtractor` / `MediaCodec`（音频解码）、`Context.assets` / `filesDir`、`PowerManager`（热状态降级 lookahead）、`Uri` |
| 引擎 | sherpa-onnx 1.13.6 + SenseVoice int8（228MB，中英日韩粤，RTF≈0.026）+ Silero VAD（0.61MB，能量法兜底） |
| 核心算法（应进 Core） | 稀疏区间扫描（`scannedRanges` + `firstGapIn` + 1ms 滑移修正）、lookahead 预读（`DECODE_WINDOW_MS=60s`）、seek **不清缓存**策略、热降级（`THERMAL_STATUS_SEVERE→NARROW`）、长句切分 `splitLongSentence` |
| 模型分发 | 内置 assets（228MB，gitignore 排除，脚本 `scripts/fetch_asr_model.py` 拉取）+ 运行时下载兜底 |
| 拟定 Core 接口 | `AsrEngine`（create/recognizeSegment/release）、`AudioSegmentSource`、`SubtitleScheduler`（区间扫描与预读调度） |
| Harmony 路径 | **必须自编译 sherpa-onnx C/C++ 版本**（Harmony NDK + CMake）。AAR 不可用 |
| 风险 | **HIGH**（R-02）。计划 §22 已要求 Native C/C++ POC，本审计确认这是**仅次于工具链的第二大风险** |

### 3.6 翻译 / 在线字幕

| 项 | 内容 |
| --- | --- |
| 当前实现 | `SubtitleTranslator.kt`(790，OkHttp) / `OnlineSubtitleSearch.kt`(125) |
| 引擎 | Bing 翻译（含 token 抓取 `fetchBingConfig`）+ OpenAI 兼容 API（自定义 baseUrl/model/apiKey） |
| 平台 API | OkHttp、`Context`（磁盘缓存目录 + `getString` 状态文案）、`mutableStateOf`（把 `config` / `isTranslating` 暴露成 Compose 状态） |
| 逻辑资产 | 分句 `translateMultiLine`、双语排版 `formatOutput`、磁盘缓存（自维护转义格式）、会话限额 `maxSessionTranslations`、预翻译 `pretranslateAhead` |
| 拟定 Core 接口 | `Translator`（translate/translateBatch/cancel）、`TranslationConfig`、`TranslationCacheRepository` |
| Harmony 路径 | HTTP 用 `@ohos.net.http`；其余逻辑复用。Bing token 抓取脚本（HTML 解析）需按 Harmony 的响应行为重测 |
| 风险 | **LOW-MEDIUM**。网络层替换工作量可控；需注意 Harmony 的网络权限与证书配置 |

### 3.7 媒体来源 / 网络

| 项 | 内容 |
| --- | --- |
| 当前实现 | `DemoMediaProvider.kt`(426，演示媒体) / `SmbDataSource.kt`(59) / `VRPlayerScreen` 的 SMB 浏览块（1330–1386） |
| 平台 API | SAF（`ActivityResultContracts.OpenDocument` / `DocumentFile`）、`Uri`、`ContentResolver`；jcifs-ng（`SmbFile` / `SmbFileInputStream`）；Media3 `BaseDataSource` |
| 支持协议 | 本地文件（content/file 方案）、`smb://`、演示资产；**WebDAV 未实现** |
| 拟定 Core 接口 | `MediaRepository`、`MediaSource`（local/smb/webdav 枚举 + 打开/列举）、`SmbBrowser` |
| Harmony 路径 | 本地：`@ohos.file.picker` + `@ohos.file.fs`。SMB：**需重做客户端**（jcifs-ng 是 JVM 实现，Harmony 上可行性未验证）或走 native。WebDAV：`@ohos.net.http` 实现 `PROPFIND` |
| 风险 | **MEDIUM**（SMB）。计划 §23 已允许「SMB 延后到 P2/P3」，本审计认同 |

### 3.8 持久化 / 设置

| 项 | 内容 |
| --- | --- |
| 当前实现 | 无 Repository。直接 `SharedPreferences` + 自维护文件 |
| 载体 1 | `vr_player_prefs`：**67 个键**（播放/VR/美颜/字幕/UI/语言），7 个文件读写 |
| 载体 2 | `analytics_prefs`：`analytics_consent` |
| 载体 3 | 字幕 SRT 文件（`_asr.srt`） |
| 载体 4 | 翻译磁盘缓存（自维护转义格式） |
| 总开关 | `is_memory_mode_enabled`（默认 true）门控所有设置读写 |
| 拟定 Core 接口 | `SettingsRepository`、`HistoryRepository`（播放位置）、`SubtitleRepository`、`MediaRepository` |
| Harmony 路径 | `@ohos.data.preferences`（轻量 KV，与 prefs 语义接近）+ `@ohos.data.relationalStore`（如需查询） |
| 风险 | **LOW**。但 67 个键需要一个显式的 schema 与版本号（当前无版本管理） |

### 3.9 UI / 本地化

| 项 | 内容 |
| --- | --- |
| 当前实现 | Compose（444 处 / 18 文件）。主体 `VRPlayerScreen.kt` 的 3156 行布局 + `SubtitleSettingsPanel`(1411) + `BeautySettingsSections`(455) + `PlayerControlBar`(232) 等 |
| 本地化 | 5 语言（`values` / `-en` / `-zh-rTW` / `-ja` / `-ko`）。`LanguageManager` 用 `ContextWrapper` 包装资源，切语言**不重建 Activity** |
| 动画/特效 | `backdrop`（Liquid Glass 背景模糊）、多种 `Easing`、悬浮球、手势拖动/惯性 |
| 拟定 Core 接口 | UI 不进 Core。仅共享 `State` / `Model` / `Action` / 业务逻辑（计划 §25） |
| Harmony 路径 | ArkUI 重写。**可复用的只有文案表（可导出为 JSON）+ 状态语义**。5 语言文案可直接转 ArkTS 资源 |
| 风险 | **LOW**（技术）/ **HIGH**（工作量）。这是迁移中**最大的一块纯工作量**，约占 Android 代码 60% |

### 3.10 传感器 / 头部姿态

| 项 | 内容 |
| --- | --- |
| 当前实现 | `VRSensorManager.kt`(244) |
| 平台 API | `SensorManager`（GAME_ROTATION_VECTOR 优先，ROTATION_VECTOR 兜底，`SENSOR_DELAY_GAME`）、`Surface.rotation`（屏幕旋转补偿）、`android.opengl.Matrix` |
| 算法资产（应进 Core） | 四元数指数移动平均（含双覆盖符号处理 + 归一化，α=0.25）、姿态基准对齐（输出 `R_当前 × R_基准ᵀ`，基准转置缓存）、双朝向模式（`HANDHELD` 随屏幕旋转 / `VR_BOX` 固定 X-Z 轴）、`recenter` 机制、`gyroInverted` 兜底开关 |
| 拟定 Core 接口 | `HeadPose`、`OrientationMode`、`SensorProvider`（Adapter 只负责喂原始四元数） |
| Harmony 路径 | `@ohos.sensor`（`SENSOR_TYPE_ID_GAME_ROTATION_VECTOR` / `ORIENTATION`）。算法可复用，矩阵运算改用自实现的 4×4 数学（不依赖 `android.opengl.Matrix`） |
| 风险 | **LOW-MEDIUM**。算法清晰、无第三方依赖 |

---

## 4. 平台 API → HarmonyOS 能力对照

| Android API | 使用点 | HarmonyOS 对应 | 已验证？ |
| --- | --- | --- | --- |
| `ExoPlayer` / Media3 | 68 | `@ohos.multimedia.media.AVPlayer` | ❌ 待 POC |
| `Surface` / `SurfaceTexture` | 3 | `XComponent` + `SurfaceId` | ❌ 待 POC |
| `GLSurfaceView` + `GLES20` | 213 | `XComponent(SURFACE)` → `NativeWindow` → EGL → GLES | ❌ 待 POC（计划 §12 第一步） |
| `MediaCodec` / `MediaExtractor` | 16 | `AVCodec` / `AVDemuxer`（Native API） | ❌ 待 POC |
| `MediaMuxer` / `Media3 Transformer` | 7 | `AVMuxer` / 无直接等价 | ❌ 待验证 |
| `SensorManager` + `GAME_ROTATION_VECTOR` | 30 | `@ohos.sensor` | ❌ 待验证 |
| `SharedPreferences` | 16 | `@ohos.data.preferences` | 通常可用，待本地验证（工具链缺失） |
| `MediaPipe tasks-vision` | 5 | **无官方等价** — 需自建 ONNX/TFLite 推理或 Harmony Vision | ❌ **最大不确定性** |
| `android.media.FaceDetector`（兜底） | — | 无等价 | — |
| `ContentResolver` / SAF | 35 | `@ohos.file.picker` / `fileAccess` | ❌ 待验证 |
| OkHttp | 11 | `@ohos.net.http` / `rcp` | 通常可用 |
| jcifs-ng（SMB） | 6 | **无等价** — 需 native 或自实现 | ❌ 高风险 |
| Firebase Analytics | 2 | `@ohos.analytics`（华为分析） | 可替代（业务上是可选项） |
| Compose | 444 | ArkUI / ArkTS | ❌ 全量重写 |

**R-01 的前置影响**：上表中**所有 ❌ 项都无法在本机验证**，因为 DevEco Studio 已卸载（仅剩 `hdc.exe`）。这意味着 **Phase 2 及以后全部依赖此项决策**；而 **Phase 1（Core 抽取）不受影响**。

---

## 5. 数据流（三条关键链路）

### 5.1 播放帧链路（当前）

```
媒体源（本地/SMB）
   ↓  ExoPlayer（MediaCodec 硬/软解 + 可选 8K 参数注入）
Surface(SurfaceTexture)
   ↓  GLES11Ext.GL_TEXTURE_EXTERNAL_OES 外部纹理
VRGLRenderer.onDrawFrame()
   ├─ 读 gyroRotationMatrix（来自 VRSensorManager）
   ├─ 读 faceCenterX/Y + 12 个语义点 uniform（来自 MediaPipeFaceManager，隔帧异步）
   ├─ 选择几何体（Quad / Sphere360 / Sphere180 / Box）
   ├─ calculateAndApplyMatrices(isLeft) × 2（双目：SBS/TAB/Mono + IPD 偏移）
   └─ shader：投影变形 → 美颜（13 项）→ LUT → 输出
   ↓
屏幕（GLSurfaceView）
```

**Core 可抽取的部分**：几何生成、投影矩阵计算、双目视锥计算、`HeadPose` 应用、`BeautyParameters` 语义。
**Adapter 专属**：OES 外部纹理绑定、`glReadPixels` 取帧、线程模型、EGL 配置。

### 5.2 字幕链路（当前）

```
视频音轨
   ↓  MediaExtractor + MediaCodec（Audio → PCM）
Silero VAD 分段 / 能量法兜底
   ↓
SherpaAsrManager.recognizeSegment（SenseVoice int8）
   ↓  文本 + 时间戳
splitLongSentence（长句切分）
   ↓
SubtitleCache.put（TreeMap，按 startTimeMs）
   ↓  每帧 cache.find(positionMs)（floorEntry + endTimeMs 校验）
SubtitleOverlay 渲染（Compose）
   ↓
自动保存 `_asr.srt`
```

**热管理**：`PowerManager.getCurrentThermalStatus()` → 动态收窄 lookahead（SEVERE→窄、MODERATE→8s）——这个策略**应进 Core**（抽象为 `ThermalProvider`）。

### 5.3 美颜链路（当前）

```
帧 → faceExecutor（512×512 区域读取 → 256×256 降采样）
   ↓  MediaPipe FaceLandmarker（VIDEO 模式，478 点）
12 个语义点（归一化）
   ↓  平滑（background thread 渐进更新 uniform）
VRGLRenderer 的 17 个 @Volatile uniform
   ↓  shader（磨皮/美白/瘦脸/大眼/…）
渲染结果
```

---

## 6. 跨端可复用资产清单（本审计的重要发现）

计划未点明、但对 Harmony 复用价值最高的三块：

| 资产 | 位置 | 复用方式 |
| --- | --- | --- |
| **GLSL ES 1.00 shader 源码** | `VRGLRenderer.buildMainProgram()` | Harmony 的 EGL/GLES 环境可**直接编译同一份 GLSL**（投影变形 + 立体 + 13 项美颜 + LUT 全在 shader 内） |
| **VR 几何与投影数学** | `GeometryHelper`（球面/半球/六面体/逆向射线 UV）、`VRGLRenderer.calculateAndApplyMatrices` | 算法 1:1 移植（注意 `FloatBuffer` 需改为平台数组/native buffer） |
| **姿态算法** | `VRSensorManager`（四元数 EMA、基准对齐、双模式轴映射） | 算法 1:1 移植，矩阵库改为自实现 |

**结论：渲染与 VR 的迁移风险远低于 ASR 与人脸检测**——前者是"复用 + 重写绑定层"，后者是"从零找替代实现"。

---

## 7. 依赖清理建议（非本次执行）

以下问题不阻塞迁移，但会在 Core 抽取时固化，建议在 Phase 1 前清理：

| 项 | 问题 | 建议 |
| --- | --- | --- |
| `retrofit` / `converter-moshi` / `moshi-kotlin` / `logging-interceptor` | **零 import，完全未使用**（网络层用裸 OkHttp + org.json） | 移除依赖（连带 KSP 可去掉一半用途） |
| `androidx.room:*` + `room-compiler` | **零使用**（无 `@Entity` / `@Dao` / `@Database`） | 移除，或明确保留为 Phase 9 备用 |
| `DecoderEngine.MPV` | 枚举存在、UI 有入口、**无实现** | Core 只保留 `EXO`；UI 移除或标注未实现（见 `MIGRATION_AUDIT.md` §6.4） |
| `com.google.firebase:firebase-analytics` | 已编码，但配置未入库 → 运行时禁用 | 保留（属可选能力），Core 侧抽象为 `AnalyticsService` 空实现兜底 |
| `androidx.compose.material.icons.extended` | 大体积图标库 | 仅为减少体积，非迁移问题 |
| 67 个 prefs 键 | 无 schema 版本、无双端约定 | Repository 抽象时引入版本号 |

---

## 8. 风险摘要（详见 `MIGRATION_AUDIT.md` §8）

| 能力 | 迁移风险 | 一句话 |
| --- | --- | --- |
| 字幕（模型/解析/缓存） | **LOW** | 纯 Kotlin，可直接复用 → 建议第一个进 Core |
| VR 数学 / 几何 / 姿态算法 | **LOW** | 算法可 1:1 移植 |
| 翻译 / 网络 | **LOW-MEDIUM** | 换 HTTP 实现即可 |
| 持久化 / 设置 | **LOW** | prefs → preferences，语义接近 |
| 渲染 / 播放 | **MEDIUM** | shader 可复用，绑定层需重写 |
| SMB | **MEDIUM** | jcifs-ng 不可用，需重做客户端 |
| 人脸检测 | **HIGH** | Harmony 无 MediaPipe 等价物，Provider 未验证 |
| ASR（sherpa-onnx） | **HIGH** | 只有 Android AAR，需自编译 C/C++ |
| **本地工具链（R-01）** | **BLOCKER** | DevEco Studio 已卸载 → Phase 2 起无法验证 |
