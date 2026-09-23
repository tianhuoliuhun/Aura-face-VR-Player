# 美颜方案可选项改造规划：GPUPixel / GLSL 双引擎

> 状态：**规划稿 —— 尚未执行**
> 日期：2026-09-23
> 需求来源：用户提出「增加美颜方案可选项，支持 GPUPixel 与 GLSL 两套方案；对比原图改为美颜开关；
> 两套方案人脸识别各自独立；细分选项分别配置；GPUPixel 方案增加 VR 视频人脸美颜与一键开关」
> 参考：https://github.com/pixpark/gpupixel

---

## 一、需求拆解与确认

| # | 需求 | 规划中的落实方式 |
|---|---|---|
| 1 | 美颜方案可选项（GPUPixel / GLSL） | 抽象 `BeautyEngine`，两个实现，设置里切换（见 §3） |
| 2 | 「对比原图」改为「美颜开关」 | `beautyCompareEnabled` → `beautyMasterEnabled`（见 §4.2） |
| 3 | 两套方案的人脸识别**不复用、各自独立** | GLSL 侧 = MediaPipe + FaceDetector；GPUPixel 侧 = Mars-Face（见 §3.3） |
| 4 | 细分选项可不同、分别配置 | 两套参数各自独立存储与 UI（见 §3.4） |
| 5 | GPUPixel 方案增加「VR 视频人脸美颜」+ 一键开关 | 屏幕空间后处理 + `gpuPixelVrFaceBeauty` 开关（见 §5） |

**术语说明**：需求里写的「GLGS」按上下文理解为 **GLSL**（即当前纯 shader 方案）。

---

## 二、GPUPixel 调研结论（基于仓库实测，非二手文章）

仓库：`pixpark/gpupixel`（C++17 + OpenGL ES，CMake 构建，MIT 许可）

### 2.1 关键类与参数（头文件实测）

| 类 | 头文件 | 公开 setter | 对应我们的功能 |
|---|---|---|---|
| `BeautyFaceFilter` | `filter/beauty_face_filter.h` | `SetBlurAlpha` `SetWhite` `SetSharpen` `SetRadius` `SetHighPassDelta` | 磨皮 / 美白 / 锐化 |
| `FaceReshapeFilter` | `filter/face_reshape_filter.h` | `SetFaceSlimLevel` `SetEyeZoomLevel` + **`SetFaceLandmarks(vector<float>)`** | 瘦脸 / 大眼 |
| `FaceMakeupFilter` | `filter/face_makeup_filter.h` | （待 POC 时确认） | 口红 / 腮红 / 眉毛 |
| `LipstickFilter` / `BlusherFilter` | 同名头文件 | （待确认） | 单妆效 |
| `FilterGroup` | `filter/filter_group.h` | 滤镜链容器 | 串联多个滤镜 |
| `BilateralFilter` | `filter/bilateral_filter.h` | — | 磨皮内部实现 |

⚠️ 注意 **`BeautyFaceFilter` 只管磨皮/美白/锐化**，**瘦脸大眼在 `FaceReshapeFilter`** ——
所以我们的参数要**拆成两组**分别对接，不能指望一个 filter 包打天下。

### 2.2 人脸检测

- 独立目录 `include/gpupixel/face_detector/`，`third_party` 里的 commit 显示用的是 **Mars-Face**
  （历史上用过 Face++ 需联网、VNN；v1.3.0 起改为 Mars-Face，本地、无网络依赖）
- `FaceReshapeFilter::SetFaceLandmarks()` 说明**关键点由外部喂入** —— 由 GPUPixel 自己的 detector 产出，
  也**技术上允许**换别的来源（但本需求明确要求不复用，见 §3.3）

### 2.3 集成与体积

- Android 产出 **AAR 约 2.1~2.4 MB**（官方数据），另有 Mars-Face 模型
- 性能：官方 1080p 数据约 **5~6 ms / CPU 3~5%**（小米 10 / 华为 Mate30）
- 许可：**MIT**（需保留版权声明并入库 LICENSE 全文）
- 输入支持 RGBA / YUV(I420) / JPEG / PNG；**NV21 标注为「计划中」** —— 我们的 OES 纹理需先转 RGBA

### 2.4 ⚠️ 前置条件：必须先 POC

`AI_MIGRATION_PLAN.md` 有硬性要求（原文）：

> GPUPixel 是否能够稳定运行于目标 HarmonyOS NEXT 环境：**必须 POC 验证**。
> 禁止 AI 根据网络文章或猜测直接判断。

因此本规划的**第一步是 POC，而不是直接接入**（见 §6）。

---

## 三、架构设计

### 3.1 总体结构

```
                    ┌─────────────────────────┐
                    │  BeautySettings (UI)    │
                    │  · 方案选择 GLSL/GPUPixel│
                    │  · 美颜总开关           │
                    │  · 各方案自己的参数区   │
                    └───────────┬─────────────┘
                                │
                    ┌───────────▼─────────────┐
                    │   BeautyEngine (接口)   │
                    └───────┬─────────┬───────┘
                            │         │
              ┌─────────────▼──┐  ┌───▼──────────────┐
              │ GlslBeautyEngine│  │ GpuPixelBeautyEngine│
              │ · 现有 GLSL     │  │ · GPUPixel C++ 库   │
              │ · MediaPipe 478 │  │ · Mars-Face（独立） │
              │   + FaceDetector│  │ · BeautyFaceFilter  │
              │ · 11 项细分参数 │  │   + FaceReshape +   │
              │                │  │   FaceMakeup        │
              └────────────────┘  └──────────────────┘
```

### 3.2 `BeautyEngine` 接口（新增）

```kotlin
enum class BeautyEngineType(val id: Int, val nameResId: Int) {
    GLSL(0, R.string.beauty_engine_glsl),
    GPU_PIXEL(1, R.string.beauty_engine_gpupixel)
}

interface BeautyEngine {
    val type: BeautyEngineType
    /** 生命周期：GL 上下文就绪后调用 */
    fun attach(context: Context, glContextProvider: () -> Any?)
    fun release()

    /** 应用美颜：输入渲染好的纹理，输出处理后的纹理 id */
    fun render(inputTexId: Int, width: Int, height: Int, params: BeautyParams): Int

    /** 该引擎支持哪些可调项（决定 UI 显示哪些滑块） */
    fun supportedParams(): Set<BeautyParamKey>

    /** 人脸检测状态（各引擎自己维护，不共享） */
    fun lastFaceState(): FaceState?
}
```

⚠️ `render()` 的返回纹理方式（离屏 FBO / 共享 context）**是 POC 的核心验证点**，见 §6.1。

### 3.3 人脸检测各自独立（按需求）

| | GLSL 引擎 | GPUPixel 引擎 |
|---|---|---|
| 检测器 | MediaPipe `face_landmarker.task`（478 点）+ `FaceDetector` 兜底 | Mars-Face（GPUPixel 自带） |
| 实现 | 现有 `MediaPipeFaceManager`（保留） | 新增 `MarsFaceManager`（或对 GPUPixel detector 的薄封装） |
| 采样 | 现有 `maybeScheduleFaceSampling()`（每 8 帧、单眼视口中心 512×512） | **另起一套**（频率/尺寸可不同，见 §5.2） |
| 结果 | 只喂 GLSL 的 uniform | 只喂 `SetFaceLandmarks` / GPUPixel 内部 |
| 状态 | 独立缓存，`FaceState` 不互传 | 独立 |

**设计约束**：两条链**不共享任何检测结果与中间状态** —— 切换引擎时也不需要"热迁移"。
代价是同时只跑一条（引擎切换时销毁另一条），内存不会翻倍。

### 3.4 参数分离

现有（GLSL）11 项 + v2.0.159 新增的「皮肤质感」共 12 项：
磨皮 / 皮肤质感 / 美白 / 瘦脸 / 大眼 / 去黑眼圈 / 瘦鼻 / 嘴型 / 白牙 / 口红 / 腮红 / 眉毛
（另有与脸无关的：亮度 / 对比度 / 长腿 / 小头 / LUT）

GPUPixel 侧（按其 API）拟提供：
磨皮 `BlurAlpha` / 美白 `White` / 锐化 `Sharpen` / 半径 `Radius` / 瘦脸 `FaceSlimLevel` / 大眼 `EyeZoomLevel`
/ 口红 / 腮红（FaceMakeup 细分待 POC 确认）

**存储**：prefs key 加引擎前缀，互不干扰
```
beauty_glsl_smooth / beauty_glsl_texture / …
beauty_gp_smooth  / beauty_gp_white     / …
```

**UI**：`BeautySettingsSections` 按当前引擎**只渲染该引擎支持的滑块**（`supportedParams()` 驱动），
切换引擎时整个参数区重建 —— 避免"改了 A 的参数却影响 B"的困惑。

---

## 四、改造点清单

### 4.1 新增文件（计划）

| 文件 | 职责 |
|---|---|
| `BeautyEngine.kt` | `BeautyEngineType` 枚举 + `BeautyEngine` 接口 + `BeautyParams` 数据类 |
| `GlslBeautyEngine.kt` | 把现有 VRGLRenderer 里的美颜逻辑**抽壳**（尽量不改算法，只做接口适配） |
| `GpuPixelBeautyEngine.kt` | GPUPixel 的 JNI 封装 + Mars-Face 管理 + 参数映射 |
| `MarsFaceManager.kt` | GPUPixel 侧独立的人脸检测（不复用 MediaPipe） |
| `gpupixel/`（JNI 模块） | CMake + `libgpupixel.so` + Java/Kotlin binding |

### 4.2 「对比原图」→「美颜开关」

现状（`VRPlayerScreen` / `VRGLRenderer`）：
- `VRPlayerScreen:172` `var beautyCompareEnabled`
- `VRGLRenderer:264` 同名字段
- `VRGLRenderer:1139` `val bc = if (beautyCompareEnabled) 0f else 1f`（所有美颜 uniform 归零）
- `VRGLRenderer:1163` `uniform1i(hFaceDetected, if (compare) 0 else …)`
- `VRGLRenderer:1535` `isBeautyActive()` 内 return false
- `VRPlayerScreen:4777` UI Switch

改造：
```kotlin
// 改名并反转语义
var beautyMasterEnabled by remember { mutableStateOf(true) }   // 默认开
// renderer 侧
@Volatile var beautyMasterEnabled = true
```
- 关闭时：走**原图直通**（等价于旧的对比模式），且 `isBeautyActive()` 返回 false（不采样）
- 打开时：按当前引擎渲染
- UI：原来的「对比原图」Switch → **「美颜开关」**（默认开）
  - ⚠️ 若希望保留"长按对比"的能力，可另加一个**按住对比**的交互（可选，待确认，见 §8）

**迁移**：prefs 里旧的 `beauty_compare_enabled` 不再使用；默认总开关为**开**。

### 4.3 引擎切换的 UI

设置 → 美颜 区块顶部放一行 **方案选择**（两个按钮/分段控件）：
```
[ GLSL（内置） ]  [ GPUPixel ]
```
- 切换时：释放旧引擎 → 初始化新引擎（GPUPixel 首次初始化要加载 so + 模型，**需异步**并显示 loading）
- 若 GPUPixel 不可用（POC 未过 / so 缺失 / 初始化失败）→ 按钮置灰 + 提示，并回退 GLSL

### 4.4 字符串（5 语，实施时补）

`beauty_engine_glsl` / `beauty_engine_gpupixel` / `beauty_master_switch` /
`beauty_gp_vr_face` （VR 视频人脸美颜）/ `beauty_gp_vr_face_hint` / `beauty_engine_switching` / `beauty_engine_unavailable`

---

## 五、GPUPixel 的「VR 视频人脸美颜」设计

### 5.1 现状

- 面部效果（瘦脸/大眼/妆容）目前**只在 `uProjectionMode == 0`（平面模式）生效**
  （`VRGLRenderer:596 / 739 / 1208`）
- 但采样环节**已经支持分屏 VR**（`maybeScheduleFaceSampling` 在 `isSplitScreenVR` 下取单眼视口）
  → 即"VR 下能检测到脸，只是不画"

### 5.2 方案：**屏幕空间后处理**（推荐）

```
VR 渲染（全景/分屏）→ 单眼画面（纹理）
        ↓
   人脸检测（Mars-Face，对单眼画面）      ← 每 N 帧一次，后台线程
        ↓
   GPUPixel：BeautyFaceFilter + FaceReshapeFilter（用检测到的 landmarks）
        ↓
   输出纹理 → 上屏
```

- **优点**：与现有"分屏 VR 单眼采样"的经验一致；不需要在球面 UV 上重新推导人脸坐标；转身时逐帧重新检测即可
- **缺点**：人脸出现在视野边缘时，投影拉伸会让形变略微不自然（可接受；可在 POC 中评估）
- **一键开关**：`gpuPixelVrFaceBeauty`（默认**关**，避免意外开启导致 VR 观感/性能变化）
  - 开启后：VR/全景模式下也走 GPUPixel 链路
  - 关闭时：VR 模式直通原图（与现状一致）

**开关层级**（建议）：
```
美颜总开关（原「对比原图」改造而来）
└── 方案：GPUPixel
    ├── 磨皮 / 美白 / 锐化 / 半径 / 瘦脸 / 大眼 / 口红 / 腮红
    └── 【VR 视频人脸美颜】 ← 一键开关（默认关）
```

### 5.3 性能预算（POC 要测）

| 环节 | 预算 |
|---|---|
| Mars-Face 检测（每 N 帧） | < 4 ms（后台线程，不阻塞渲染） |
| GPUPixel 处理 | < 3 ms（官方 5~6 ms，取上限需谨慎） |
| 目标帧率 | 60 fps 不掉的硬指标；VR 分屏（两倍绘制）需额外评估 |

---

## 六、实施步骤（建议顺序）

```
P0  POC（必须，且优先于任何业务代码）
    P0.1 编译 gpupixel Android AAR（CMake/NDK），确认 so 体积与 ABI 覆盖
    P0.2 最小 Demo：OES 纹理 → RGBA → GPUPixel → 上屏，确认不黑屏、无 context 冲突
    P0.3 EGL 上下文：GPUPixelContext 与 VRGLRenderer 的 EGL context 能否共享/协同
    P0.4 Mars-Face 独立跑通（不接 MediaPipe），记录模型体积与单次耗时
    P0.5 分屏 VR（左右眼两次绘制）下的调用方式与开销
    P0.6 许可合规：MIT 全文入库 + 版权声明
    → 输出《GPUPixel POC 报告》，任一关键项不通过即暂停接入

P1  抽象层（不引入 GPUPixel 也能先做）
    P1.1 新增 BeautyEngine 接口 + GlslBeautyEngine（把现有逻辑抽壳）
    P1.2 「对比原图」→「美颜开关」改造
    P1.3 prefs 参数按引擎前缀分离
    P1.4 UI：方案选择（此时 GPUPixel 项置灰/提示"待 POC"）

P2  GPUPixel 接入
    P2.1 JNI 模块 + GpuPixelBeautyEngine + MarsFaceManager
    P2.2 参数映射与 UI（只显示该引擎支持的滑块）
    P2.3 引擎切换的生命周期管理（异步初始化 + 失败回退）

P3  VR 视频人脸美颜
    P3.1 屏幕空间后处理链路
    P3.2 一键开关 + 默认关 + 持久化
    P3.3 性能实测与开关提示
```

---

## 七、风险与验收

| 风险 | 影响 | 缓解 |
|---|---|---|
| **EGL 上下文冲突**（P0.3） | 黑屏 / 纹理丢失 —— 最高风险 | POC 优先验证；准备 shared context 与"离屏 FBO + 回读"两条备选 |
| OES → RGBA 转换开销 | 1080p 下额外一 pass | POC 实测；必要时降采样处理 |
| 分屏 VR 双倍开销 | 帧率骤降 | VR 美颜默认关；提供开关与提示 |
| Mars-Face 与 MediaPipe 结果不一致 | 两方案切换时观感跳变 | 接受（需求本就要求独立）；UI 上明确"两套参数独立" |
| GLSL 抽壳引入回归 | 现有美颜行为变化 | 抽壳只搬代码、不改算法；抽壳后回归测试（真机） |
| 体积增长 | AAR 2.1MB + Mars-Face 模型 | 可接受；但需确认是否随 APK 全量打包 |
| 鸿蒙迁移冲突 | `DEPENDENCY_MAP.md` 定的是"优先移植 GLSL" | 本需求是 Android 侧新增**可选**方案，默认仍是 GLSL → 不违背；但 P0 的 POC 结论要回写迁移计划 |

**验收清单（GPUPixel 方案）**
1. 切到 GPUPixel 后画面正常、美颜生效（不黑屏）
2. 磨皮/美白/瘦脸/大眼各滑块独立可调，且与 GLSL 侧参数互不影响
3. 切回 GLSL 后参数与观感恢复（两套独立）
4. 关掉「美颜开关」→ 直通原图（等价旧对比模式）
5. VR 视频人脸美颜开/关对比明显，且**开关关闭时与现状完全一致**
6. 60 fps 帧率测试（平面 + VR 分屏）
7. 冷启动/切换引擎不崩溃；GPUPixel 不可用时优雅回退

---

## 八、待确认（实施前需要你拍板）

1. **`render()` 的接入位置**：是在主渲染 pass 之后做**全屏后处理**，
   还是只对"人脸所在区域"做局部处理（更快但要额外遮罩）？→ 倾向全屏后处理（简单、风险低）
2. **切换引擎时是否保留"长按对比原图"**？旧对比模式消失后，可能需要一个临时对比手段（按住按钮看原图）
3. **VR 美颜的默认采样频率**（每 N 帧）——需要 POC 实测后定，暂定 N=8（与现有一致）
4. **GPUPixel 失败时的行为**：静默回退 GLSL + Toast，还是弹提示让用户手动切回？
5. **ABI 覆盖**：是否只打 `arm64-v8a`（体积优先）还是含 `armeabi-v7a`

---

## 九、实施状态（2026-09-23 当日更新）

> 用户决策（同日拍板）：忽略鸿蒙兼容性问题、`render()` 只处理人脸区域、不做长按看原图、
> 采样频率 8 帧、GPUPixel 失败弹提示并自动回退 GLSL、ABI 含 armeabi-v7a（x86_64 视官方包情况）。
> **P0–P3 已全部实施（v2.0.160）**：

| 阶段 | 状态 | 说明 |
|---|---|---|
| P0 | ✅ | 未自编译 —— 直接采用官方 Release **v1.3.1 预编译 AAR**（约 5MB，`app/libs/gpupixel-release.aar`）。实测其内容：arm64-v8a / armeabi-v7a 两个 ABI 的 `libgpupixel.so` + `libmars-face-kit.so`；assets 自带 Mars-Face 模型（face_det / face_align）与妆容素材。Java API 经 javap 确认：`GPUPixel.Init` / `GPUPixelSourceRawData.ProcessData` / `GPUPixelSinkRawData.GetRgbaBuffer` / `GPUPixelFilter.Create + SetProperty(String, float/float[])` / `FaceDetector.detect`。⚠️ **不含 x86_64**：模拟器上初始化失败 → 自动回退 GLSL 并 Toast 提示 |
| P1 | ✅ | `GpuPixelBeauty.kt`（引擎封装）+「对比原图」→「美颜总开关」（`beautyMasterEnabled`，默认开）+ prefs 按引擎分离（GLSL 沿用 `beauty_*`，GPUPixel 用 `beauty_gp_*`）+ `BeautyEngineSection` UI（方案选择 + 按引擎过滤参数区；GLSL 专属的预设/通用/人像精修区在 GPUPixel 模式下隐藏，LUT 调色不受引擎影响） |
| P2 | ✅ | **raw-data 模式**（AAR 唯一 Java 通道，byte[] 进出）——顺带**消解了原规划里最高风险的 EGL 上下文共享问题**（GPUPixel 内部自建 GL 上下文）。渲染侧：GPUPixel 激活时主渲染写入**离屏 FBO** → 复用现有 8 帧采样的回读像素 → faceExecutor 里 Mars-Face 独立检测 + GPUPixel 处理 → 结果 `glTexSubImage2D` **贴回人脸区域**（与回读同区）→ 极简 blit pass 上屏。GLSL 侧美颜 uniform 在 GPUPixel 模式下全部归零（bc 机制），避免双重美颜 |
| P3 | ✅ | `gpuPixelVrFaceBeauty` 一键开关（默认关）：开启后非平面模式也采样并走同一条区域处理链路（屏幕空间后处理） |

### 实施中踩的坑（记入项目记忆）

1. **官方包没有"一个全功能滤镜"**：磨皮/美白/锐化在 `BeautyFaceFilter`，瘦脸/大眼在 `FaceReshapeFilter`，参数要分两组 `SetProperty`
2. **property 名按 C++ 字段名推断**（`blur_alpha` / `white` / `sharpen` / `thin_face_delta` / `big_eye_delta` / `face_landmarks`）—— **真机 POC 需对照官方 demo 核对**
3. 两处 Compose 老坑再次验证：缺 `Box` / `Surface` import 会伪装成「@Composable invocations」错误；**具名参数 lambda 没有 `return@标签`**，要用 if/else 分支
4. 大括号平衡用脚本自查（`{` / `}` 计数 + 深度扫描）比肉眼可靠

### 仍待验证（需真机）

- GPUPixel property 名是否与 native 端一致（若不一致：磨皮不生效但不会崩溃）
- Mars-Face 对「上下颠倒的回读帧」的检测质量（与 MediaPipe 同一约束）
- 区域贴回在运动画面下的延迟观感（8 帧节奏）
- VR 模式（`gpuPixelVrFaceBeauty` 开启）的帧率表现
