# MIGRATION_AUDIT — 光域 Aurelia 仓库审计

> Task: **P0-01 Repository Audit**
> Phase: Phase 0
> 审计对象: `tianhuoliuhun/Aura-face-VR-Player` @ `main` / commit `86ec109` / versionName `2.0.138`
> 审计日期: 2026-09-16
> 审计方式: 只读。统计脚本见 `.workbuddy/tmp/audit_scan.py`、`.workbuddy/tmp/audit_deps.py`（临时文件，可复现）
> **本次审计未修改 `app/` 下任何业务代码**（计划 §15 禁止条款）

---

## 1. 结论摘要

| 项 | 结论 |
| --- | --- |
| 项目形态 | **单模块 Android 应用**（`app/`），无 Gradle 多模块拆分，无 native 层 |
| 代码规模 | 40 个 Kotlin 文件 / 16,065 行（其中代码行 13,245） |
| 架构现状 | **单 Composable 巨石**：`VRPlayerScreen.kt` 5,321 行承载全部业务逻辑，无 ViewModel、无 Repository、无 Interface 抽象 |
| Core 抽取难度 | **中高**。VR 数学、字幕缓存/解析、几何生成三块已天然平台无关（约占 2,000 行可直接进 Core）；播放、渲染、人脸、持久化四块与 Android 强耦合 |
| 最大有利条件 | 渲染全部为 **GLSL ES 2.0 + GLES20**，与 HarmonyOS 的 EGL/GLES 路线同源，**shader 与几何算法可跨端复用** |
| 最大不利条件 | 无 C++ 层（`cpp/` 不存在），Harmony 侧等于从零起步；且本机 DevEco 工具链已卸载 |
| 与迁移计划冲突点 | **4 处**（GPUPixel 路线、Phase 9 Room、FaceLandmarks 粒度、ASR 交付形态），详见 §6 |
| Phase 0 建议 | 计划可执行，但需先补齐 §6 的 4 项决策，并明确 R-01（工具链）的处置方式 |

---

## 2. 现状总览

### 2.1 技术栈基线

| 项 | 值 |
| --- | --- |
| AGP / Gradle | 9.1.1 / 9.6.1（配置缓存默认开启） |
| Kotlin | 2.2.10 |
| Compose BOM | 2024.09.00（Compose 1.7 系列） |
| minSdk / targetSdk | 24 / 36 |
| applicationId | `com.aistudio.vrplayer.vrmjpy` |
| namespace | `com.example` |
| 打包形态 | universal 单 APK（ABI 分包代码存在但被注释） |
| release APK 体积参考 | 347.9 MB（v2.0.127，含 228MB 内置 ASR 模型） |

### 2.2 代码规模

```
总文件 40 个 .kt / 16,065 行 / 代码行 13,245

VRPlayerScreen.kt           5321 行  ← 巨石，占全项目 33%
VRGLRenderer.kt             1513 行
SubtitleSettingsPanel.kt    1411 行
RealtimeSubtitleEngine.kt   1036 行
SubtitleTranslator.kt        790 行
ExperimentalDecode.kt        478 行
BeautySettingsSections.kt    455 行
SherpaAsrManager.kt          431 行
DemoMediaProvider.kt         426 行
SubtitleOverlay.kt           379 行
SubtitledText.kt             255 行
VRSensorManager.kt           244 行
PlayerControlBar.kt          232 行
MediaPipeFaceManager.kt      228 行
GeometryHelper.kt            222 行
VRGLSurfaceView.kt           218 行
AsrBatchSection.kt           216 行
VideoRemuxer.kt              205 行
LutUtils.kt                  183 行
LicensesScreen.kt            168 行
MainActivity.kt              162 行
VRPlayerComponents.kt        159 行
LanguageManager.kt           147 行
SubtitleModels.kt            133 行
OnlineSubtitleSearch.kt      125 行
SubtitleExporter.kt          124 行
UiThemePalette.kt            108 行
AnalyticsManager.kt           88 行
其余 12 个文件                ≤ 84 行
```

包结构是**扁平**的：`com.example.vr` 下 38 个文件平铺，没有 `ui/`、`player/`、`subtitle/` 这类分层目录。`com.example.ui.theme` 只有 3 个主题文件。

**这就是计划 §3 要解决的问题的实体形态**：`VRPlayerScreen.kt` 一个文件里同时存在 UI 布局、ExoPlayer 装配、SMB 浏览、ASR 调度、字幕渲染、设置持久化、悬浮球动画。

### 2.3 无 native 层

```
app/src/main/cpp/      不存在
app/src/main/jniLibs/  空（0 文件）
app/libs/              sherpa-onnx-1.13.6.aar（46.82 MB，仅 Android .so）
```

**这是一个关键事实**：计划 §12/§13 设想 Harmony 侧走 `XComponent → NativeWindow → EGL → OpenGL ES → C/C++`。由于 Android 侧**没有可复用的 C++ 层**，Harmony 的 native 渲染管线不是"移植"，而是**全新实现**（可用 Kotlin 侧算法作参考）。这一点必须在排期时承认。

### 2.4 资源与体积构成

| 位置 | 体积 | 内容 |
| --- | --- | --- |
| `app/src/main/assets` | 244.21 MB | sense-voice 模型 228.15MB、12 个 LUT 共 11.5MB、face_landmarker.task 3.58MB、silero_vad.onnx 0.61MB、licenses.json |
| `app/src/main/res` | 41.29 MB | **MiSans 19.07MB + OPPO Sans 21.69MB**（两个字体占 98.7%） |
| `app/libs` | 46.82 MB | sherpa-onnx AAR |
| `app/src/main/java` | 6.84 MB | 源码 |

字体是仅次于 ASR 模型的第二个体积项。计划 §14 把 MiSans / OPPO Sans 定为候选 UI 字体——**现状是这两个字体已经在字幕字体选择器里以全量 ttf 打包**（`SubtitleFont.MI_SANS` / `OPPO_SANS`），而不是"候选"。

### 2.5 多语言现状（超出计划假设）

```
res/values         （简体中文，默认）
res/values-en
res/values-zh-rTW
res/values-ja
res/values-ko
```

已是**五语言**体系（计划 §14 未涉及，但 Phase 10 的 Harmony UI 需要同步）。语言切换器由 `LanguageManager` 管理，tag 存 `vr_player_prefs` 的 `app_language` 键，走 `ContextWrapper` 包装（不重建 Activity）。

---

## 3. 功能盘点（对照计划 §1 的 13 项核心方向）

| 计划功能 | 现状 | 主要实现 | 备注 |
| --- | --- | --- | --- |
| 普通视频播放 | ✅ 完整 | `VRPlayerScreen.setupVideoPlayer()` + ExoPlayer 1.4.1 | 含硬解/软解切换、8K 硬解补丁、降级转码、remux 修复 |
| 360° VR | ✅ | `GeometryHelper.generateSphere()` + `VRGLRenderer` | 经纬球面 40×40 细分 |
| 180° VR | ✅ | 同上 `isHalfSphere=true` | |
| Fisheye | ✅ | `ProjectionMode.FISHEYE` + shader | |
| 盒子模式（Box） | ✅ **计划未列** | `GeometryHelper.generateBox()` | 六面体 16×16 细分 + 逆向射线 UV |
| SBS / TAB | ✅ | `StereoMode.SBS/TAB` | 含左右眼单眼偏好、IPD 偏移 |
| 7 种 Warp 变形 | ✅ **计划未列** | `WarpMode` 枚举 + shader | 柱面/球面/环幕/反向曲面，含双中心变形 |
| 实时美颜 | ✅ | 内置 GLSL shader（`VRGLRenderer.buildMainProgram`） | **GPUPixel 已于 v102 移除**，见 §6.1 |
| 人脸关键点 | ✅ | `MediaPipeFaceManager` + tasks-vision 0.10.14 | VIDEO 模式，478 点模型；**实际只取 12 个语义点**，见 §6.3 |
| AI 语音识别 | ✅ | `SherpaAsrManager` + sherpa-onnx 1.13.6 | SenseVoice int8，中英日韩粤，RTF≈0.026 |
| 实时字幕 | ✅ | `RealtimeSubtitleEngine` + `SubtitleCache` | 窗口预读 + VAD 分段 + 进度；v2.0.135 已修 seek 丢字幕 |
| 翻译 | ✅ | `SubtitleTranslator`（OkHttp + Bing / OpenAI 兼容 API） | 含磁盘缓存、会话限额、双语/仅译文模式 |
| 本地媒体 | ✅ | SAF 文件选择 + `DemoMediaProvider` | |
| NAS / SMB | ✅ | `SmbDataSource`（jcifs-ng 2.1.8） | ExoPlayer DataSource 直连，含目录浏览 |
| **WebDAV** | ❌ **未实现** | — | 计划 §23 列为 P2 项，与现状一致 |
| **Room 持久化** | ❌ **未使用** | 实际为 SharedPreferences | 见 §6.2 |
| 字幕格式 | ⚠️ 仅 SRT / VTT | `SubtitleParser.parseSrtOrVtt()` | **ASS 未实现**（计划 §21 的 P6-03） |
| 解码器 MPV | ⚠️ **幽灵选项** | `DecoderEngine.MPV` 枚举存在，**零实现** | UI 有切换入口，选了无效果，见 §6.4 |
| Firebase Analytics | ⚠️ 已编码未激活 | `AnalyticsManager` | `google-services.json` 未入库 → 运行时自动禁用 |

**已从历史版本中移除、计划文本未反映的技术**：Vosk（Kaldi 流式）、Qwen3-ASR 0.6B（838MB）、SenseVoice QNN（骁龙 NPU 专用，136MB 运行库）、GPUPixel AAR。v127 收敛到单一 ASR 路线（SenseVoice CPU）。

---

## 4. 平台耦合热点（Core 抽取的障碍量化）

按「使用点计数 / 涉及文件数」统计：

```
Compose UI            444 处 / 18 文件   ████████████████████
OpenGL ES             213 处 /  4 文件   ██████████
ExoPlayer / Media3     68 处 /  7 文件   ███
Context / Activity     44 处 / 19 文件   ██   ← 19/40 文件持有 Android Context
Files / SAF            35 处 / 19 文件   ██
Android Sensor         30 处 /  3 文件   █
SharedPreferences      16 处 /  7 文件   █
MediaCodec / Extractor 16 处 /  4 文件   █
sherpa-onnx            15 处 /  2 文件
OkHttp / Retrofit      11 处 /  3 文件
Bitmap / Canvas         9 处 /  5 文件
AudioTrack/Processor    8 处 /  2 文件
SMB (jcifs-ng)          6 处 /  2 文件
MediaPipe               5 处 /  1 文件
Firebase                2 处 /  1 文件
```

### 4.1 关键观察

1. **`Context` 渗透 19 个文件**（近一半）。这不是"UI 层问题"——`SherpaAsrManager`、`RealtimeSubtitleEngine`、`MediaPipeFaceManager`、`SubtitleTranslator`、`LanguageManager`、`PlaybackPositions`、`LutUtils` 都靠 Context 拿 `assets` / `filesDir` / `prefs` / `getString`。Core 抽取必须先把「资源获取」抽象成 `Repository`，否则这些类剥不掉 Context。

2. **SAF（`android.content.*` + `DocumentFile` + `ActivityResultContracts`）涉及 19 个文件**。其中相当一部分只是拿 `Context.getString()`（文案）。**这意味着"SAF 依赖"这一项被高估**，真实文件访问集中在 `MainActivity` 与几个 picker launcher。

3. **OpenGL ES 只集中在 4 个文件**（`VRGLRenderer` / `VRGLSurfaceView` / `VRPlayerScreen` / `VRSensorManager`）。渲染层的耦合面比想象中窄，是可控的。

4. **ExoPlayer 68 处集中在 7 个文件**，但真正构建设置的只有 `VRPlayerScreen.setupVideoPlayer()`（约 370 行）与 `ExperimentalDecode.kt`（478 行，硬解黑科技）。这两块是 Android Adapter 的核心资产。

5. **`VRGLRenderer` 把四件事塞在一个类里**：投影几何选择、双目渲染、美颜 shader 参数（17 个 `@Volatile` 字段）、人脸检测调度（含线程池 + 帧缓冲复用）。Core 抽取时必须拆分。

---

## 5. Core 可抽取性评估

分四档：**🟢 可直接进 Core** / **🟡 剥离 UI 属性后可进** / **🟠 需重构后部分进入** / **🔴 属 Adapter，不进 Core**

| 模块 | 行数 | 档位 | 说明 |
| --- | --- | --- | --- |
| `GeometryHelper` | 222 | 🟢 | **完全平台无关**（仅 `java.nio` + `kotlin.math`）。含球面/半球/六面体生成与逆向射线 UV 映射。唯一障碍：`FloatBuffer` 属 JVM 专有，Harmony 侧需改写为 C++/ArkTS 数组（算法 1:1 可复用） |
| `SubtitleCache` | 76 | 🟢 | 纯 Kotlin `TreeMap` 稀疏索引，零 Android 依赖。`floorEntry` 查询 + `invalidateAfter` 语义可直接搬 |
| `SubtitleCue` | — | 🟢 | 纯数据类 |
| `SubtitleParser` | ~74 | 🟢 | SRT/VTT 解析为纯文本处理。**但与 5 个携带 Compose Color 的枚举同处一个文件**，抽取时需拆文件 |
| `AsrEngineType` | 18 | 🟢 | 纯枚举 |
| `MediaItem` | — | 🟢 | `uri` 已是 `String` 而非 `android.net.Uri`，解耦已做好 |
| `ProjectionMode` / `StereoMode` / `WarpMode` / `MaxResolution` / `DecoderEngine` | ~60 | 🟡 | 逻辑（`id`、几何参数）纯净，但每个枚举项携带 `displayName: String`（硬编码中文）+ `@StringRes labelRes`。需拆为 Core 枚举（只留 id/参数）+ UI 文案映射表 |
| `FaceResult`（`MediaPipeFaceManager` 内部类） | ~25 | 🟡 | 已是「中心点 + 眼距 + 眼/嘴/下巴坐标 + confidence 标志」的语义结构，是 `FaceLandmarks` 的雏形。但**只有 12 个语义点，不是计划 §9 设想的 `points[]` 全点位数组**，见 §6.3 |
| `RealtimeSubtitleEngine` | 1036 | 🟠 | **核心资产是调度算法**：稀疏区间扫描（`scannedRanges` + `firstGapIn`）、lookahead 预读（`DECODE_WINDOW_MS=60s`）、seek 不清缓存策略、VAD 分段、长句切分。这些都该进 Core。但类本身持有 `Context` / `MediaCodec` / `MediaExtractor` / `Uri` / `PowerManager`，需要按「调度器（Core）+ 音频解码源（Adapter）」切一刀 |
| `SherpaAsrManager` | 431 | 🟠 | 模型下载/校验/目录管理 + `OfflineRecognizer` 封装。**识别器生命周期管理可进 Core（接口化），模型 I/O 属 Adapter** |
| `SubtitleTranslator` | 790 | 🟠 | 分句、双语排版 `formatOutput`、磁盘缓存格式、会话限额 → Core。HTTP 调用（OkHttp + Bing 抓取 token）→ Adapter |
| `VRSensorManager` | 244 | 🟠 | **算法部分非常有价值**：四元数指数移动平均（含双覆盖符号处理）、姿态基准对齐 `R_当前 × R_基准ᵀ`、手持/VR 眼镜双模式轴映射、屏幕旋转补偿。这些应进 Core（`HeadPose`）。但类直接持有 `SensorManager` 与 `VRGLRenderer` 引用 → 需改为「Core 算法对象 + Adapter 喂数据」 |
| `SubtitleModels` 的 5 个样式枚举 | ~120 | 🔴 | `SubtitleFont/ColorOption/StrokeOption/BgOption/AlignOption` 携带 `androidx.compose.ui.graphics.Color` 与 `TextAlign` → 纯 UI，留 Adapter |
| `VRGLRenderer` | 1513 | 🟠/🔴 | **GLSL shader 源码可跨端复用**（GLSL ES 2.0，Harmony 的 GLES 同样吃）→ 这部分是跨端资产。但 Java 实现（`GLES20.*` 约 213 处）+ `GLSurfaceView.Renderer` 接口 → Adapter。**17 个美颜 `@Volatile` 参数**应转为 Core `BeautyParameters` 数据类 |
| `VRGLSurfaceView` | 218 | 🔴 | `GLSurfaceView` + `GestureDetector` + `ScaleGestureDetector` → 纯 Adapter（Harmony 用 XComponent + 手势识别） |
| `MediaPipeFaceManager` | 228 | 🔴 | MediaPipe Android API 直连 → Adapter。**但需先定义 Core `FaceDetector` 接口再改造**，否则会继续反向依赖 |
| `SmbDataSource` | 59 | 🔴 | `BaseDataSource`（Media3 接口）→ Adapter |
| `VRPlayerScreen` | 5321 | 🔴 | 需整体拆解，见 §5.1 |

### 5.1 `VRPlayerScreen.kt` 拆解清单（5,321 行）

按现有代码块边界，可切出以下 Core/Adapter 归属（**只是归属判定，不是本次执行**）：

| 代码块（行号区间） | 行数 | 归属 |
| --- | --- | --- |
| 全局 ~110 个 `remember` 状态声明（109–533 / 697–710 / 907–929 / 1176–1202 / 2148–2165） | ~400 | → Core `PlayerUiState` / `SettingsState` + Adapter 的 Compose state 桥 |
| `changeAsrLanguage` / `exportSubtitleSrt` / `regenerateRealtimeSubtitle` / `loadSavedSubtitleFile` / `startBatchTranscribe`（571–696） | 126 | → `SubtitleRepository` + `AsrService` |
| `keepUiAlight` / `toggleUiVisibility` / `getMediaDisplayName`（932–979） | 48 | → UI |
| `startDownscalingTranscode`（1204–1329） | 126 | → Adapter（Media3 transformer 特有的降级转码） |
| SMB 浏览/连接/播放（1330–1386） | 57 | → `NetworkRepository` + Adapter DataSource |
| `startRemuxFix` / `startLevelPatchFix`（1387–1491） | 105 | → Adapter（Android MediaCodec 特性修补） |
| 轨道选择 / 视频信息（1492–1616） | 125 | → Adapter |
| **`setupVideoPlayer`（1617–1983）** | **367** | → Adapter（ExoPlayer 装配，含硬解/软解选择器、8K 补丁注入、SMB 数据源） |
| 播放监听与解码器重绑（1984–2106） | 123 | → Adapter |
| 实时字幕自动保存/加载（2107–2147） | 41 | → `SubtitleRepository` |
| 陀螺仪装配（2148–2165） | 18 | → Adapter |
| Compose UI 布局（2166–5321） | **3156** | → Adapter（Harmony 侧必须用 ArkUI 重写，**不共享**） |

**结论：5,321 行里真正值得进 Core 的不足 400 行；约 1,100 行属 Android Adapter 资产（可复用逻辑）；3,200 行是 Compose UI 布局（Harmony 侧重写）。**

这正是计划 §25「禁止逐行翻译 Compose」的数据依据：**UI 占 60%，且完全不可移植**。

---

## 6. 与迁移计划的偏差（需用户决策）

### 6.1 GPUPixel 路线已过时（计划 §10）

计划把 **GPUPixel 作为候选 GPU Beauty Backend**，并要求 POC 验证其在 HarmonyOS NEXT 的可用性。

**实测现状**：`app/build.gradle.kts` 第 141 行明确记录「v102：GPUPixel 原生美颜引擎已移除（删除 aar），改用内置 GLSL shader 美颜方案」。仓库已无 GPUPixel 依赖，美颜全部由 `VRGLRenderer` 内嵌 GLSL 实现（磨皮/美白/瘦脸/大眼/黑眼圈/瘦鼻/嘴型/美齿/口红/腮红/眉毛/长腿/小头，共 13 项参数）。

**影响**：计划 §20 的 `P5-06 GPUPixel POC` 目标已无意义。

**建议（待确认）**：
- 方案 A（推荐）：Core 只定义 `BeautyEngine` 接口 + `BeautyParameters`；Android 保持现有 GLSL 实现；Harmony 先移植 **GLSL 源码**（跨端可复用，风险最低），GPUPixel 降级为"仅在 GLSL 方案性能不达标时才评估的备选"。
- 方案 B：坚持先做 GPUPixel POC（会引入新的 C++ 依赖与双份实现）。

### 6.2 Phase 9 的 Room 与现实不符（计划 §24）

计划写「Android：Room」。**实测：全项目 `@Entity` / `@Dao` / `@Database` / `Room.databaseBuilder` 出现 0 次**——虽然 `build.gradle.kts` 引了 `room-runtime` / `room-ktx` / `room-compiler`（KSP），但**零使用**。

真实持久化形态：

| 载体 | 位置 | 内容 |
| --- | --- | --- |
| `vr_player_prefs`（SharedPreferences） | 7 个文件读写 | **67 个键**：全部播放/VR/美颜/字幕/UI 设置 + `app_language` + `subtitle_user_disabled` + `is_memory_mode_enabled`（总开关） |
| `analytics_prefs` | `AnalyticsManager` | 隐私同意标志 |
| 字幕 SRT 文件 | 应用目录（`_asr.srt`） | `RealtimeSubtitleEngine` 自动保存/加载 |
| 翻译磁盘缓存 | 应用目录 | `SubtitleTranslator` 自维护，带 `escapeCache` 转义 |
| 播放位置 | `PlaybackPositions`（prefs） | 按媒体 URI 记录 |

**建议（待确认）**：Phase 9 的 `SettingsRepository` 应从 **67 个 prefs 键**抽象，而不是从 Room 迁移。是否引入 Room 属于独立决策（当前无查询需求，Room 收益低）。

### 6.3 FaceLandmarks 粒度与计划不符（计划 §9）

计划 §9 定义：

```
FaceLandmarks
├── faceId
├── points          ← 点位数组
├── boundingBox
├── confidence
├── timestamp
```

**实测**：`MediaPipeFaceManager` 使用 478 点模型（`face_landmarker.task`，判据 `landmarks.size > 454`），但**只挑出 12 个语义点**后立即丢弃原始数组：

```
33 / 133   左眼外/内角      263 / 362   右眼外/内角
4          鼻尖            152         下巴
234 / 454  左/右脸颊        61 / 291    嘴角左/右
13 / 14    上/下唇中点
```

输出为 `FaceResult(detected, centerX, centerY, eyeDistance, eyeLeftX/Y, eyeRightX/Y, mouthX/Y, chinX/Y, hasDetailedLandmarks)` —— 全部是**归一化后的语义标量**，无 `points[]`、无 `boundingBox`、无 `faceId`、无 `timestamp`。

**影响**：若 Core 按计划定义 `points: FloatArray`（478×3），Harmony 侧 Provider 必须产出同规格数组才能对齐，实现成本高且无当前用途（美颜 shader 只用那 12 个点）。

**建议（待确认）**：
- 方案 A（推荐）：Core `FaceLandmarks` 定义为**语义关键点集**（`FaceFeature` 枚举 + 归一化坐标），同时保留可选 `points`（`null` 表示 Provider 未提供全点位）。Android/Harmony 都只需产出语义点即可满足现有美颜需求。
- 方案 B：严格按计划保留全点位数组（按 478 点对齐 MediaPipe，Harmony 需自行实现 478 点检测——成本极高）。

### 6.4 `DecoderEngine.MPV` 幽灵选项

`VRMediaModels.kt` 第 41-44 行定义 `DecoderEngine { EXO, MPV }`，UI 有「解码器切换 EXO/MPV」入口（`VRPlayerScreen.kt:4209`），但**全项目无任何 MPV 实现**（唯一的另一处提及是一句注释）。用户选 MPV 后无任何行为差异。

**影响**：Core 抽取时若照搬该枚举，会把一个死功能带入跨端契约。

**建议（待确认）**：Core 枚举只保留 `EXO`（或改名 `PLATFORM_DEFAULT`），MPV 从 UI 移除或明确标注「未实现」。

### 6.5 其他偏差（低风险，仅登记）

| 项 | 计划假设 | 实测 |
| --- | --- | --- |
| 语言数 | 未提 | 已 5 语言（简/繁/英/日/韩） |
| 字幕格式 | SRT / ASS / VTT 三者（§21） | 仅 SRT + VTT 已实现，ASS 未实现 |
| 立体模式 | Mono / SBS / TAB | 一致 |
| 投影模式 | Flat / 360 / 180 / Fisheye | **多出 Box 模式**；另有 7 种 Warp 变形 |
| 字体 | "优先考虑 MiSans / OPPO Sans" | 二者**已全量打包**（40.76MB），非候选 |

---

## 7. 字体合规复核（计划 §14 专项）

计划要求：「必须确认许可证 → 记录来源 → 确认是否允许 App 内嵌 → 再加入项目」。

**实测**：

| 项 | 状态 |
| --- | --- |
| 字体文件 | `res/font/mi_sans_vf.ttf`（19.07MB，可变字体）、`res/font/oppo_sans_4_0.ttf`（21.69MB） |
| 应用内声明 | ✅ `assets/licenses.json:1217-1224` 已登记，含 license 与 note（"OPPO 官方免费商用字体；禁止修改、需保留版权声明"），应用内「设置 → 关于与开源许可」可查看 |
| README 声明 | ✅ 提到"免费商用授权"，并注明"免费商用但禁止修改" |
| **厂商许可原文** | ❌ **仓库内未见**（无 MiSans/OPPO Sans 官方许可全文文件） |
| 是否修改过字体 | 未见修改（原样打包 ttf） |

**判断**：声明层已做，**但缺少「官方许可原文入库」这一环**。计划 §14 明确"AI 不得自行判断某个字体应该可以商用"。当前 README 的措辞（"免费商用"）属于项目历史上的**自行判断**，缺少可追溯的官方授权文件。

**建议**：Phase 1/2 之前补做一次字体合规归档（下载两家官方许可页存档到 `docs/licenses/`，或改为只保留可无争议分发的字体）。Harmony 侧若继续内嵌字体，同样需完成此步。**此项不阻塞 Phase 1 的 Core 抽取（纯代码），但阻塞任何"字体随包分发"的新增动作。**

---

## 8. 风险清单

| ID | 风险 | 等级 | 证据 | 建议处置 |
| --- | --- | --- | --- | --- |
| **R-01** | **本机无 HarmonyOS 工具链**：DevEco Studio 已卸载，仅剩 `hdc.exe`。计划 §17 Phase 2 要求建立 Harmony 工程、§31 要求 XComponent/EGL/NAPI POC | **BLOCKER**（针对 Phase 2，不影响 Phase 1） | 环境记录：`D:\Program Files\Huawei\DevEco Studio` 仅存 toolchains | 用户决策：重装 DevEco Studio / 指定其他构建机 / 先做纯文档与 Core 抽取（Phase 1 不需要工具链） |
| **R-02** | sherpa-onnx 交付形态是 **Android AAR（46.82MB，含 Android .so）**，Harmony 无法直接使用 | **HIGH** | `app/libs/sherpa-onnx-1.13.6.aar` | Phase 7 前必须先做 **Harmony native 编译 POC**（CMake + OHOS NDK），验证 SenseVoice int8 模型加载与 RTF |
| **R-03** | Harmony 人脸 Provider（Vision / HiAI）可用性未知 | **HIGH** | 计划 §20 已标"无法验证则 STOP" | 保持 STOP 语义；Phase 5 前先只做 **API 可用性验证**，不做实现 |
| **R-04** | Android 侧**无 C++ 层**，Harmony native 渲染管线要从零写 | MEDIUM | `cpp/` 不存在，渲染是 Kotlin + GLES20 | 承认这是新增工作量；但 shader 与算法可复用，降低实现风险 |
| **R-05** | GPUPixel 路线与现状冲突 | MEDIUM | v102 已移除 GPUPixel | 按 §6.1 决策 |
| **R-06** | 字体 40.76MB + ASR 模型 228MB 的包体压力在 Harmony 侧重现 | MEDIUM | 体积统计 | Harmony 侧优先做「模型按需下载」（现有 `fetch_asr_model.py` 已是此思路） |
| **R-07** | `VRPlayerScreen.kt` 单文件 5,321 行、~110 个 state，任何改动都有回归风险 | MEDIUM | 规模统计 | Phase 11 前**不做**大规模重构；Core 抽取走"旁路新增"，不动现有 UI |
| **R-08** | 无性能数据基线（计划 §32 要求设备矩阵） | LOW | 仓库内无 FPS/CPU/GPU 记录 | Phase 3 起建立基线，Android 侧同步补一次作为对照 |
| **R-09** | Compose BOM 2024.09 偏旧（已知 `matchParentSize` 不可 import、`BoxWithConstraints` 非 inline 等坑），后续 Android 改造受版本约束 | LOW | 项目记忆中已多次记录 | 不在本轮升级（升级风险 > 收益） |
| **R-10** | 持久化是 67 个散落的 prefs 键 + 自维护文件格式，无 schema 版本 | LOW | prefs 键清单 | Repository 抽象时一并加版本号与迁移钩子 |

---

## 9. Phase 0 结论

### 9.1 计划可执行性判定

**可执行，但需先完成 4 项决策（§6.1–6.4）与 1 项环境决策（R-01）。**

理由：

1. **架构方向正确**：项目当前的深耦合问题（单文件巨石 + Context 渗透 19 文件）确实是计划要解决的核心痛点，`Aura Core + Adapter` 的切分方向与实测的耦合面（GLES 仅 4 文件、ExoPlayer 仅 7 文件）匹配，**切分点是清晰的**。
2. **有一块天然可复用的跨端资产**：GLSL ES 2.0 shader（投影 + 双目 + 13 项美颜）与 VR 数学（球面/半球/六面体几何、四元数姿态平滑、双目 IPD）。这是计划没有点明、但**对 Harmony 复用价值最高**的部分。
3. **Phase 1（Core 抽取）不依赖 Harmony 工具链**，可以在 R-01 未解决的情况下先行推进——这是当前最稳的启动路径。

### 9.2 建议的 Phase 1 起点（按依赖顺序）

```
P1-07 StereoMode / P1-06 VRProjection      ← 无依赖，可直接搬（🟢🟡）
P1-08 Subtitle Model                        ← 无依赖，SubtitleCache + SubtitleCue 已纯净（🟢）
P1-03 FaceLandmarks                         ← 需先决策 §6.3
P1-04 FaceDetector Interface                ← 依赖 P1-03
P1-05 BeautyEngine Interface                ← 需先决策 §6.1
P1-10 Repository Interface                  ← 需先决策 §6.2（从 67 个 prefs 键抽象）
P1-01/P1-02 Player Interface + State        ← 依赖最多，放最后
P1-09 ASR Interface                         ← 需先决策 R-02 的处置
```

### 9.3 下一步

**STOP**（计划 §38）。等待用户：

1. 确认 §6 的 4 项决策
2. 明确 R-01（Harmony 工具链）的处置方式
3. 确认是否启动 P1-01（或按 §9.2 顺序从 P1-06/P1-07 开始）

---

## 10. 附录：审计方法与可复现性

| 项 | 说明 |
| --- | --- |
| 规模统计 | `.workbuddy/tmp/audit_scan.py`（遍历 `app/src/main`，统计行数/体积/资源） |
| 依赖面扫描 | `.workbuddy/tmp/audit_deps.py`（正则匹配 16 类平台 API 使用点 + 提取 prefs 键 + import 分类） |
| 只读性验证 | `git status --short` 在审计开始与结束时均为空（无业务代码改动） |
| 未覆盖范围 | 未做 APK 实测性能、未做 Harmony 侧任何验证、未读取 `build/` 与 `app/build/` 产物目录 |
| 已知统计口径 | "代码行" = 非空且非注释起始行；"使用点" = 正则命中次数（含重复引用同一 API 的多行） |
