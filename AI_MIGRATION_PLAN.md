# 光域 Aurelia

## AI 自动迁移与跨平台重构计划

> Project Name: **光域 Aurelia**
>
> Repository: `Aurelia`（当前物理仓库：`Aura-face-VR-Player`）
>
> Project Type: Immersive Media / VR / AI Player
>
> Target: Android + HarmonyOS NEXT
>
> Future: iOS interface reservation
>
> Execution Mode: AI Task-driven Migration
>
> Plan Version: v1.0（落盘于 2026-09-16，对应 Android 侧 commit `86ec109` / v2.0.138）

---

# 0. 项目新名称

## 中文品牌

**光域**

## 英文品牌

**Aurelia**

## 推荐正式写法

**光域 Aurelia**

英文产品名统一使用：

```text
Aurelia
```

中文产品名统一使用：

```text
光域
```

### 命名原则

不要继续使用：

```text
Aura-face-VR-Player
VR Beauty Player
```

这些名称会把项目限制在某一个功能上。

Aurelia 应作为整个产品品牌。

---

# 1. 项目定位

光域 Aurelia 是一个面向沉浸式媒体体验的跨平台播放器。

核心方向：

* 普通视频播放
* 360° VR
* 180° VR
* Fisheye
* SBS / TAB 立体视频
* 实时美颜
* 人脸关键点
* AI 语音识别
* 实时字幕
* 翻译
* 本地媒体
* NAS / WebDAV
* HarmonyOS NEXT
* Android
* 未来 iOS

目标不是简单地：

> Android App → HarmonyOS App

而是：

> Android 原有项目 → 跨平台 Core → HarmonyOS NEXT → 未来 iOS

---

# 2. 最终架构目标

```text
                         光域 Aurelia
                              │
                    ┌─────────┴─────────┐
                    │    Aura / Core    │
                    │ Platform Neutral  │
                    └─────────┬─────────┘
                              │
             ┌────────────────┼────────────────┐
             │                │                │
        Android Adapter   Harmony Adapter   iOS Adapter
             │                │                │
        Kotlin/Java        ArkTS/C++         Swift
             │                │                │
         ExoPlayer        AVPlayer/AVCodec   AVFoundation
         MediaPipe       XComponent/EGL      Metal
         GLES            Native/GLES         Vision
         Sensor          Sensor              CoreMotion
```

---

# 3. 最重要的架构原则

## 3.1 不进行 Android → ArkTS 逐文件翻译

禁止：

```text
VRPlayerScreen.kt
        ↓
VRPlayerScreen.ets
```

然后继续复制 Android 的全部逻辑。

正确方式：

```text
Android UI
     │
     ↓
Aura Core
     ↑
Harmony UI
```

---

# 4. Core 设计原则

Core 不允许直接依赖：

```text
Android SDK
Android Context
Activity
Compose
ExoPlayer
MediaPipe Android API
HarmonyOS API
ArkUI
XComponent
iOS UIKit
SwiftUI
Metal
```

Core 只保存：

* 数据结构
* 协议
* 播放状态
* VR 数学
* 几何计算
* 字幕时间轴
* 人脸数据模型
* 美颜接口
* ASR 接口
* Translator 接口
* Renderer 接口
* Repository 接口

---

# 5. Aura Core

推荐目录：

```text
Aura/
├── core/
│   ├── model/
│   ├── player/
│   ├── vr/
│   ├── renderer/
│   ├── beauty/
│   ├── face/
│   ├── subtitle/
│   ├── asr/
│   ├── translator/
│   ├── network/
│   └── repository/
│
├── android/
│   ├── ui/
│   ├── player/
│   ├── renderer/
│   └── platform/
│
├── harmony/
│   ├── ui/
│   ├── player/
│   ├── renderer/
│   └── platform/
│
└── ios/
    └── interfaces/
```

---

# 6. Core Player

定义平台无关的 Player：

```text
Player
├── load()
├── play()
├── pause()
├── seekTo()
├── stop()
├── release()
├── setVolume()
├── setPlaybackSpeed()
└── state
```

Android：

```text
Player
   ↓
ExoPlayer / Media3
```

HarmonyOS：

```text
Player
   ↓
AVPlayer
```

如果需要直接取得视频帧：

```text
Player
   ↓
VideoFrameProvider
   ↓
GPU Pipeline
```

必要时再验证 AVCodec 等底层能力。

不要在没有 POC 的情况下提前引入复杂 FFmpeg 管线。

---

# 7. VR Core

抽取：

```text
VRProjection
StereoMode
ProjectionMode
CameraState
HeadPose
LensParameter
```

支持：

```text
Flat
360
180
Fisheye
SBS
TAB
Mono
```

所有 VR 数学计算尽可能进入 Core。

Android：

```text
Core VR Math
      ↓
OpenGL ES Renderer
```

Harmony：

```text
Core VR Math
      ↓
EGL / OpenGL ES
```

iOS：

```text
Core VR Math
      ↓
Metal Renderer
```

---

# 8. Face / Beauty 架构

不要让 BeautyEngine 直接绑定 MediaPipe。

统一设计：

```text
FaceDetector
      ↓
FaceLandmarks
      ↓
BeautyEngine
      ↓
Renderer
```

FaceDetector 可以有多个 Provider：

```text
Android
└── MediaPipe

Harmony
├── Harmony Vision / HiAI（待验证）
└── Other Provider

iOS
└── Vision（未来）
```

---

# 9. FaceLandmarks 数据结构

Core 只定义统一数据模型：

```text
FaceLandmarks
├── faceId
├── points
├── boundingBox
├── confidence
├── timestamp
└── optional attributes
```

平台实现负责转换。

禁止：

```text
Core → MediaPipe FaceLandmarkerResult
```

正确：

```text
MediaPipe
    ↓
Adapter
    ↓
FaceLandmarks
    ↓
Core
```

---

# 10. BeautyEngine

统一接口：

```text
BeautyEngine
├── enable()
├── disable()
├── setParameter()
├── process()
└── reset()
```

参数可以包括：

```text
Skin
Smooth
Brightness
Sharpen
Eye
Face
Color
LUT
```

GPUPixel 作为候选 GPU Beauty Backend。

## 重要

GPUPixel 是否能够稳定运行于目标 HarmonyOS NEXT 环境：

**必须 POC 验证。**

禁止 AI 根据网络文章或猜测直接判断：

```text
GPUPixel 一定支持 HarmonyOS
```

---

# 11. Renderer

定义统一 Renderer 接口：

```text
Renderer
├── initialize()
├── resize()
├── render()
├── setProjection()
├── setTexture()
├── setEffect()
└── release()
```

Android：

```text
OpenGL ES
GLSurfaceView
VRGLRenderer
```

Harmony：

```text
XComponent
NativeWindow
EGL
OpenGL ES
C/C++
```

iOS：

```text
Metal
```

---

# 12. HarmonyOS NEXT 渲染路线

第一阶段：

```text
ArkUI
  ↓
XComponent
  ↓
NativeWindow
  ↓
EGL
  ↓
OpenGL ES
```

先实现：

```text
Clear Screen
↓
Triangle
↓
Texture
↓
Video Frame
↓
VR Sphere
↓
Shader
```

不要一开始就移植完整 VRGLRenderer.kt。

---

# 13. HarmonyOS Player 路线

第一阶段：

```text
AVPlayer
 ↓
普通视频
 ↓
播放/暂停
 ↓
Seek
 ↓
音量
 ↓
全屏
```

第二阶段：

```text
AVPlayer / AVCodec
 ↓
Video Frame
 ↓
GPU
```

第三阶段：

```text
Video
 ↓
VR Projection
 ↓
Beauty
 ↓
Subtitle
 ↓
Display
```

---

# 14. 字体规范

项目 UI 字体优先考虑：

```text
MiSans
OPPO Sans
```

## 授权原则

MiSans 和 OPPO Sans 可用于商业项目，但必须遵守各自官方许可条款。

AI 不得：

* 修改字体文件
* 单独出售字体
* 单独再分发字体文件
* 删除必要的版权/许可说明
* 未经确认更换为付费字体

如果打包 MiSans：

```text
保留官方要求的字体使用声明 / 许可信息
```

AI 不得自行判断某个字体：

```text
“应该可以商用”
```

对于新字体：

```text
必须确认许可证
↓
记录来源
↓
确认是否允许 App 内嵌
↓
再加入项目
```

---

# 15. Phase 0 — 项目审计

状态：

```text
P0-01 TODO
```

AI 第一个任务只能做：

```text
Repository Audit
```

需要分析：

```text
VRPlayerScreen.kt
VRGLRenderer.kt
PlayerControlBar.kt
VRPlayerComponents.kt
BeautySettingsSections.kt
RealtimeSubtitleEngine
SubtitleCache
Translator
ASR
Face Detection
Network
Room
SMB
```

输出：

```text
MIGRATION_AUDIT.md
DEPENDENCY_MAP.md
```

## 禁止

Phase 0 不修改业务代码。

---

# 16. Phase 1 — Core Extraction

任务：

```text
P1-01 Player Interface
P1-02 Player State
P1-03 FaceLandmarks
P1-04 FaceDetector Interface
P1-05 BeautyEngine Interface
P1-06 VRProjection
P1-07 StereoMode
P1-08 Subtitle Model
P1-09 ASR Interface
P1-10 Repository Interface
```

---

# 17. Phase 2 — HarmonyOS NEXT Skeleton

任务：

```text
P2-01 Harmony Project
P2-02 ArkUI Skeleton
P2-03 XComponent POC
P2-04 NativeWindow POC
P2-05 EGL POC
P2-06 OpenGL ES POC
P2-07 NAPI / C++ Bridge
```

验收：

```text
App Launch
↓
ArkUI
↓
XComponent
↓
Native EGL
↓
OpenGL ES
↓
Successfully Render
```

---

# 18. Phase 3 — Harmony Player

任务：

```text
P3-01 AVPlayer
P3-02 Local Video
P3-03 Play/Pause
P3-04 Seek
P3-05 Audio
P3-06 Video Frame Pipeline
P3-07 GPU Texture
```

---

# 19. Phase 4 — Harmony VR

任务：

```text
P4-01 Flat
P4-02 360
P4-03 180
P4-04 Fisheye
P4-05 SBS
P4-06 TAB
P4-07 Sensor
P4-08 Head Tracking
```

验收重点：

```text
稳定帧率
正确投影
正确双目
正确旋转
A/V Sync
```

---

# 20. Phase 5 — Face + Beauty

任务：

```text
P5-01 FaceDetector Interface
P5-02 Harmony Face Provider POC
P5-03 FaceLandmarks Adapter
P5-04 BeautyEngine
P5-05 GPU Beauty POC
P5-06 GPUPixel POC
P5-07 Beauty + VR
```

如果 Harmony Vision / HiAI API 无法验证：

```text
STOP
```

不要猜 API。

---

# 21. Phase 6 — Subtitle

任务：

```text
P6-01 Subtitle Model
P6-02 SRT
P6-03 ASS
P6-04 VTT
P6-05 Subtitle Timing
P6-06 Subtitle Rendering
P6-07 Subtitle Cache
```

---

# 22. Phase 7 — AI ASR

使用：

```text
sherpa-onnx
SenseVoice
Silero VAD
```

路线：

```text
Audio
 ↓
VAD
 ↓
ASR
 ↓
Text
 ↓
Subtitle Timeline
 ↓
Renderer
```

必须进行：

```text
Native C/C++ POC
```

确认：

```text
ABI
CPU
Memory
Latency
Model Loading
Long Running Stability
```

---

# 23. Phase 8 — Network

支持优先级：

```text
Local
 ↓
Harmony distributed capabilities
 ↓
WebDAV
 ↓
SMB
```

注意：

Harmony 的分布式文件能力不能直接视为：

```text
SMB replacement
```

因为两者解决的问题不同。

如果用户已有 NAS / SMB 使用需求：

```text
SMB 不删除
```

可以延后到 P2/P3。

---

# 24. Phase 9 — Persistence

Android：

```text
Room
```

Core：

```text
Repository
```

Harmony：

```text
RDB / Preferences
```

统一：

```text
SettingsRepository
HistoryRepository
SubtitleRepository
MediaRepository
```

---

# 25. Phase 10 — Harmony UI

禁止逐行翻译 Compose。

Android：

```text
Jetpack Compose
```

Harmony：

```text
ArkUI
```

两端共享：

```text
State
Model
Action
Business Logic
```

不共享：

```text
UI Implementation
```

---

# 26. Phase 11 — Android Refactor

Harmony Core 稳定后，再重新整理 Android。

目标：

```text
Android UI
     ↓
Android Adapter
     ↓
Aura Core
     ↓
ExoPlayer / MediaPipe / GLES
```

避免：

```text
Android Business Logic
+
Harmony Business Logic
```

双份维护。

---

# 27. Phase 12 — iOS Reservation

当前不实现 iOS。

只预留：

```text
Player
Renderer
FaceDetector
BeautyEngine
SensorProvider
Subtitle
ASR
Repository
```

未来：

```text
Swift
SwiftUI
AVFoundation
Metal
Vision
CoreMotion
```

iOS 不参与当前迁移验收。

---

# 28. AI 自动执行规则

AI 每次启动必须首先读取：

```text
AI_MIGRATION_PLAN.md
MIGRATION_STATUS.md
```

然后执行：

```text
READ PLAN
↓
READ STATUS
↓
CHECK GIT
↓
LOCATE CURRENT TASK
↓
READ REQUIRED FILES ONLY
↓
IMPLEMENT ONE TASK
↓
BUILD
↓
TEST
↓
UPDATE STATUS
↓
COMMIT IF REQUESTED
↓
STOP
```

---

# 29. Token / 修改范围控制

默认：

```text
每个 Task 最多修改 10 个文件
```

如果超过：

```text
拆分 Task
```

单次禁止：

```text
大规模重写核心代码
```

默认单次不超过：

```text
500 lines
```

如果确实需要：

```text
先拆任务
```

---

# 30. Git 安全规则

开始任务之前：

```text
git status
```

必须确认：

```text
没有覆盖用户未提交修改
```

修改后：

```text
git diff
```

然后：

```text
build
test
```

## Git 操作

AI 可以：

```text
查看
分析
修改
测试
```

除非明确要求：

```text
不要自动 push
```

不要擅自：

```text
git push
```

---

# 31. POC 优先原则

遇到以下技术：

```text
XComponent
EGL
OpenGL ES
AVPlayer
AVCodec
Face API
GPUPixel
sherpa-onnx
NativeWindow
NAPI
```

先：

```text
POC
```

再：

```text
Architecture
```

最后：

```text
Production Integration
```

禁止：

```text
猜 API
```

---

# 32. 性能验证

建立设备矩阵：

```text
Resolution
├── 1080p
├── 4K
└── 8K

Codec
├── H264
├── H265
└── 10bit

VR
├── Flat
├── 180
├── 360
├── SBS
└── TAB

Beauty
├── Off
├── Basic
└── Full
```

记录：

```text
FPS
Frame Time
CPU
GPU
NPU
RAM
Temperature
Dropped Frames
A/V Sync
Startup Time
Seek Time
```

---

# 33. 最终性能目标

AI 不允许为了追求功能数量牺牲基础播放稳定性。

优先级：

```text
播放稳定
>
A/V Sync
>
VR 正确性
>
GPU Pipeline
>
Beauty
>
ASR
>
Translation
```

---

# 34. 最终目录

```text
Aurelia/
│
├── AI_MIGRATION_PLAN.md
├── MIGRATION_STATUS.md
├── MIGRATION_AUDIT.md
├── DEPENDENCY_MAP.md
│
├── Aura/
│   ├── core/
│   │   ├── model/
│   │   ├── player/
│   │   ├── vr/
│   │   ├── renderer/
│   │   ├── face/
│   │   ├── beauty/
│   │   ├── subtitle/
│   │   ├── asr/
│   │   ├── translator/
│   │   ├── network/
│   │   └── repository/
│   │
│   ├── android/
│   ├── harmony/
│   └── ios/
│
└── docs/
```

---

# 35. MIGRATION_STATUS.md

AI 必须维护：

```text
# Aurelia Migration Status

## Current Task

P0-01

## Phase

Phase 0

## Status

TODO

## Completed

None

## Changed Files

None

## Tests

Not Started

## Performance

Not Started

## Risks

Not Started

## Decisions

Not Started

## Next Task

P0-01
```

每完成一个 Task 必须更新。

---

# 36. AI 停止条件

出现以下情况必须 STOP：

```text
无法确认 HarmonyOS API
无法确认第三方库兼容性
POC 失败
Build Failure 且原因不明确
需要修改超过 10 个文件
需要重写超过 500 行
发现用户已有修改可能被覆盖
需要引入重大新依赖
需要改变项目架构
```

停止时输出：

```text
Problem
Evidence
What Was Tried
Affected Files
Recommended Next Step
```

不要自行扩大任务范围。

---

# 37. AI 启动指令

将下面内容作为 AI Agent 的启动指令：

```text
读取 AI_MIGRATION_PLAN.md 和 MIGRATION_STATUS.md。

项目名称：
光域 Aurelia

目标：
将现有 Android VR Player 重构为跨平台 Core + Android Adapter + HarmonyOS NEXT Adapter，并为未来 iOS 保留接口。

严格按照当前 Task 执行。

执行规则：

1. 先检查 git status。
2. 阅读当前 Task 所需文件，不要扫描整个项目。
3. 不要跳过 POC。
4. 不要猜测 HarmonyOS API。
5. 不要把 Android Kotlin 代码逐行翻译成 ArkTS。
6. Core 不得依赖平台 API。
7. 默认每个 Task 最多修改 10 个文件。
8. 默认单次核心代码修改不超过 500 行。
9. 完成修改后必须 Build。
10. 能测试则必须测试。
11. 检查 git diff。
12. 更新 MIGRATION_STATUS.md。
13. 未明确要求时不要 git push。
14. 遇到无法验证的问题立即 STOP 并报告。
15. 不要自行扩大任务范围。
16. 不要删除现有 Android 功能，除非迁移计划明确要求。
17. 新增字体必须检查许可证。
18. MiSans / OPPO Sans 可以作为项目 UI 字体候选，但必须遵守官方许可要求。
19. 不要未经确认引入付费字体。
20. 当前任务完成后停止，不要一次执行多个大型 Task。

开始执行当前 MIGRATION_STATUS.md 中指定的 Task。
```

---

# 38. 第一阶段实际启动

项目第一次运行 AI Agent 时：

```text
Task = P0-01
```

AI 只执行：

```text
Repository Audit
```

输出：

```text
MIGRATION_AUDIT.md
DEPENDENCY_MAP.md
```

然后：

```text
STOP
```

不要直接开始 HarmonyOS 开发。

---

# 39. 核心路线总结

```text
                 光域 Aurelia
                       │
                       ▼
                Android 项目审计
                       │
                       ▼
                 抽取 Aura Core
                       │
             ┌─────────┴─────────┐
             ▼                   ▼
        Android Adapter     Harmony Adapter
             │                   │
        ExoPlayer/GLES       AVPlayer/GLES
        MediaPipe            XComponent
        Android Sensor       Harmony Sensor
             │                   │
             └─────────┬─────────┘
                       ▼
                  统一 Core
                       │
                       ▼
              功能逐步迁移完成
                       │
                       ▼
                 iOS Interface
                    Reserve
```

---

# 40. 最终目标

最终的光域 Aurelia 应该形成：

```text
光域 Aurelia
│
├── Core
│   ├── Media
│   ├── VR
│   ├── Beauty
│   ├── Face
│   ├── Subtitle
│   ├── ASR
│   └── Network
│
├── Android
│   ├── Compose
│   ├── Media3
│   ├── MediaPipe
│   └── OpenGL ES
│
├── HarmonyOS NEXT
│   ├── ArkUI
│   ├── AVPlayer
│   ├── XComponent
│   ├── EGL
│   ├── OpenGL ES
│   └── NAPI/C++
│
└── iOS
    └── Interface Reserved
```

最终产品品牌：

# 光域

## AURELIA

**Immersive Media Experience**

---

# 41. 本仓库落地说明（AI 维护，非原始计划内容）

本节记录计划与本仓库的物理对应关系与执行期约定，由 AI 在每完成一个 Task 后核对。

## 41.1 物理路径

```text
仓库根 = D:\Projects\GfaceVRplayer-main      （git remote: tianhuoliuhun/Aura-face-VR-Player）
分支   = main
文档   = 仓库根（现阶段；§34 的 Aura/ 目录在 Phase 1 建立）
Android 源码 = app/src/main/java/com/example/
```

当前尚未建立 §34 描述的 `Aura/` 物理目录。Phase 0 只产出文档，不改动 `app/` 下任何业务代码。

## 41.2 品牌改名策略

`Aura-face-VR-Player` 作为仓库名与 APK 产物名**继续保留**，不在此轮迁移中改名（改名会打断现有发布链路与已发布 Release 的下载地址）。品牌名「光域 Aurelia」先落地在文档、UI 关于页与后续版本说明中，仓库/包名改名作为独立 Task 另行决定。

包名 `com.aistudio.vrplayer.vrmjpy` 与 namespace `com.example` 同理：**不动**。更名会改变应用身份，导致存量用户升级链断裂。

## 41.3 每个 Task 的收尾动作

1. `git status` 前后各一次
2. 构建验证（`gradlew :app:assembleDebug`，产物 `Aura-face-VR-Player-v<ver>-debug.apk`）
3. 更新 `MIGRATION_STATUS.md`
4. 不自动 `git push`
5. **不删除** `app/` 下现有 Android 功能
