# 华为 VR Engine（VR Glass）接入可行性报告与实施方案

> 文档版本：v1 · 2026-09-26
> 目标应用：GfaceVRplayer（`com.aistudio.vrplayer.vrmjpy`，当前 v2.0.173，GLES2 + ExoPlayer 的 VR 视频播放器）
> 参考实现：[LHT02/HuaweiVRGlass-ALVR](https://github.com/LHT02/HuaweiVRGlass-ALVR)（Huawei VR Glass 专用 ALVR 串流客户端）
> **状态：本文档仅为调研与方案，未修改任何代码。**

---

## 0. 结论速览

| 项目 | 结论 |
|---|---|
| 技术可行性 | **可行**。华为 VR Engine 是标准 **OpenXR 1.0.3** 实现，不依赖私有渲染 API；参考项目已在真机跑通 |
| 最大阻塞点 | **渲染上下文要求 OpenGL ES 3.2**（参考项目 Manifest 声明 `glEsVersion=0x00030002`），本项目当前是 **GLES2**；且 OpenXR 会话必须在 **native（C/C++）** 层创建 |
| 第二阻塞点 | **无华为真机无法验证**（需华为旗舰手机 + VR Glass + 数据线；MuMu 模拟器上完全没有华为 VR Runtime，且多了一项沙箱加封装） |
| 推荐路径 | **路径 A′**：新增 C++/JNI 层只管 OpenXR 会话与帧循环，**复用现有 Kotlin + GLES 渲染代码**（shader/几何/投影逻辑），渲染目标由「窗口 Surface」换成「OpenXR 每眼 swapchain 纹理」 |
| 设置开关 | 可在「镜头投影与视角模式」分组新增「华为 VR Glass（VR Engine）」记忆开关，默认关；开启后用华为官方 2D 提示页 `com.huawei.android.vr.PROMPT` 引导进入独立 VR Activity，失败自动回退现有内置 VR 分屏 |
| 预估工作量 | POC 2~4 天（阻塞在真机）→ 渲染接入 1~2 周 → 输入与生命周期 3~5 天 → 设置开关与文案 1~2 天 → 真机验收 3~5 天 |
| 合规提醒 | 华为 SDK（`lib_loader.so` / `hvrbridge.jar` / `hvrprompt.aar`）**不得提交到 Git**、公开再分发前须核对华为许可；正式上架须走 AppGallery Connect |

---

## 1. 需求与范围

用户需求（原文）：

> 尝试接入华为 VR engine，在设置里加一个新的开关，参考 https://github.com/LHT02/HuaweiVRGlass-ALVR ，暂不更改代码给 md 文件报告。

据此本报告界定范围：

1. 摸清华为 VR Engine 的接入方式、硬性约束与获取途径；
2. 解剖参考项目，提取可直接复用的做法与它已经踩过的坑；
3. 对比本项目现状，给出**可落地的接入架构**与**分阶段计划**；
4. 设计设置页新增开关（名称、位置、prefs 键、默认值、联动与降级）；
5. 明确风险、前置条件与「无人能替代」的真机验证环节。

**不在范围内**：本次不做任何代码改动、不集成 SDK、不发版。

---

## 2. 华为 VR Engine / VR Glass 技术基础

### 2.1 硬件与 Runtime 参数（参考项目真机实测）

| 项目 | 实测值 |
|---|---|
| 眼镜 | HUAWEI GLASS CV10；外接显示 **3200×1600 @ 70 Hz** |
| OpenXR 推荐交换链 | **1552×1552 / 眼** |
| Runtime FOV | 每眼 **95°×95°**，对称投影中心 |
| IPD | **63 mm**（眼位约 ±31.5 mm） |
| 手机（验证机） | HUAWEI LYA-AL00，HarmonyOS 4.2 / Android 10（API 29） |
| 软件栈版本 | VR SDK Service 3.5.0.82、OpenXR SDK 3.5.0.79 |
| 手柄 | Huawei 6DoF Gaming Kit（左右手独立 Pose） |
| ABI | 发布包为 **ARMv7**（说明 SDK 同时有 32/64 位库；本项目主力 arm64-v8a） |

> 提示：这些是**单一机型 + 单一 Runtime 版本**的实测值，其它华为机型/套装需重新验证。

### 2.2 软件栈分层

```
┌──────────────────────────────────────────────────────┐
│ 我们的 APP（Java/Kotlin + C++ OpenXR 调用）           │
│   ├─ 2D 主界面（现有 VRPlayerScreen，保持不变）        │
│   └─ VR Activity（新增，OpenXR 渲染会话）              │
├──────────────────────────────────────────────────────┤
│ OpenXR Loader：lib_loader.so（华为提供，随 APK 打包） │
├──────────────────────────────────────────────────────┤
│ Huawei VR SDK Service（系统侧，随眼镜首次连接自动安装）│
├──────────────────────────────────────────────────────┤
│ Huawei VR Runtime / Compositor（畸变、重投影、显示）  │
└──────────────────────────────────────────────────────┘
        ↑ 由 Huawei VR Launcher / 系统 VR 服务拉起
```

- **APP → Loader → SDK** 三层，Loader 集成在应用的 `lib_loader.so` 中，配合 OpenXR 标准头文件即构成「OpenXR 插件」；
- SDK 提供 **`xr_demo.cpp` / GL2JNILib.java** 作为 Android 原生工程示例（Java 侧经 JNI 调 C++ OpenXR 接口）；
- **VR 相关调用全部是标准 OpenXR C API**（`xrCreateInstance` / `xrCreateSession` / `xrWaitFrame` / `xrAcquireSwapchainImage` / `xrEndFrame` …），华为只在 Loader/Service 层做实现，**不要求使用私有渲染接口**。

### 2.3 SDK 获取

| 项 | 值 |
|---|---|
| SDK 包名 | `hvrsdk-openxr-3.5.0.79.zip`（另一版本 `hvrsdk-openxr-3.0.0.33.zip`） |
| 发布 | 华为软件技术有限公司，2022/11/02 |
| SHA256 | `0fcf46b0f1d789493b09fc2b3e3f1d134215f791df37c7fe52771f652edd77d9` |
| 下载页 | https://developer.huawei.com/consumer/cn/doc/graphics-Library/openxr-sdk-download-0000001148849243 |
| 开发前提 | EMUI 10.0+（Android Q+）、OpenXR 1.0.3、**需具备 C++ / OpenGL ES / Android / Java 基础** |
| 环境准备 | 官网下载「HUAWEI VR SDK 3.5 For OpenXR」+「OpenXR Demo」两个包；手机联网插入眼镜自动安装 VR 运行环境 |

参考项目从**仓库外的 SDK 目录**读取并校验这些文件（说明这些文件需要单独获取、不随开源代码分发）：

- Huawei OpenXR Loader（`lib_loader.so`）
- `hvrbridge.jar`（Java 桥：`com.huawei.hvr.LibUpdateClient`、`com.huawei.vrlab.HVRModeActivity`）
- `hvrprompt.aar`（官方 2D 提示页）
- 真机 Runtime 兼容库

### 2.4 强制 Manifest 配置（官方文档，逐条）

**meta-data（application 级）**

| 键 | 值 | 作用 |
|---|---|---|
| `com.huawei.android.vr.application.mode` | `vr_only` | 标明应用为华为 VR 应用 |
| `com.huawei.android.vr.application.type` | `game` 或 `video`（默认 game） | **SDK 据此调整温控策略** —— 播放器建议 `video` |
| `com.huawei.vr.application.freeDegree` | `3dof` / `6dof` / `3dof|6dof` | 手柄接入模式 |
| `android.max_aspect` | `2.1` | Mate10 Pro 等特殊比例屏幕适配 |
| `com.huawei.android.vr.sensor.mode` | `mobile` | **仅调试**（不戴眼镜看手机显示），正式包**必须删除** |

**权限**

```xml
<uses-permission android:name="com.huawei.android.permission.VR" />
<uses-permission android:name="com.huawei.vrhandle.permission.DEVICE_MANAGER" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />  <!-- 系统截图 -->
```

**API 30+ 追加（否则手柄连接异常）**

```xml
<queries>
    <package android:name="com.huawei.vrhandle" />
    <package android:name="com.huawei.hvrsdkserverapp" />
    <intent><action android:name="com.huawei.vrhandle.service.vrdeviceservice" /></intent>
</queries>
```

**VR Activity 的 intent-filter（去桌面图标 + 让 VrLauncher 可跳转）**

```xml
<intent-filter>
    <action android:name="com.huawei.android.vr.action.MAIN" />
    <category android:name="android.intent.category.DEFAULT" />
</intent-filter>
```

### 2.5 「2D 界面 → VR」的官方通道（对本项目最有用的一条）

华为 SDK 3.5 提供 **2D 提示页**（`hvrprompt.aar`，**默认打包含在应用内**）。存在 2D Activity、需要动态切到 VR Activity 时：

```java
Intent intent = new Intent();
intent.setPackage(getPackageName());
intent.setAction("com.huawei.android.vr.PROMPT");   // 关键 action
startActivity(intent);
```

弹提示 → 用户插入头盔 → **跳过 VrLauncher 直接启动应用自己的 VR Activity**。

这正好对应本项目的设置开关场景：**播放页/设置页仍是普通 2D Compose 界面，点开关后用这条通道跳到华为 VR Activity**。

### 2.6 上架与合规

- VR Engine 同时是「内容开发 + 上架平台」，正式发布需在 **AppGallery Connect** 补齐信息并申请上架，华为会做兼容性测试；
- 华为 SDK 隐私声明与合规使用指南要求随集成一并处理；
- **SDK 原始包、签名文件、设备日志不得入仓**；公开或商业再分发前须自行核对华为条款（参考项目明确写了这条）。

---

## 3. 参考项目解剖：LHT02/HuaweiVRGlass-ALVR

### 3.1 结构与实现要点

- 基础：ALVR v20.14.1（Rust + OpenXR）的非官方华为专版；
- 华为适配代码集中在 **`alvr/client_openxr/android_huawei/`**：
  - `AndroidManifest.xml`（可直接当模板，见附录 B）
  - `src/com/lht/huaweivr/alvr/`（Java 层）
  - `assets/`（VR Launcher 双层图标：`vr_icon_background.png` / `vr_icon_foreground.png`）
  - `icon_source/`（由官方 SVG 生成上述双层图标）
- 三个 Activity 的分工非常清晰：

| Activity | 角色 |
|---|---|
| `HuaweiVrLauncherActivity` | 手机 2D 入口（LAUNCHER intent-filter、竖屏、浅色主题）——连状态、IP、连接步骤、直接启动入口 |
| `HuaweiVrActivity` | **VR 渲染主体**（华为 `com.huawei.android.vr.action.MAIN`、landscape、singleTask） |
| `com.huawei.vrlab.HVRModeActivity` | **来自 `hvrbridge.jar`** 的官方提示页，manifest 里声明 `action=com.huawei.android.vr.PROMPT` 即可用 |

- `HuaweiVrActivity` 源码要点（原文摘要）：

```java
public final class HuaweiVrActivity extends Activity implements SurfaceHolder.Callback {
    private native void nativeStart(Surface surface);
    private native void nativeStop();

    protected void onCreate(Bundle b) {
        ... 全屏 + 横屏 ...
        SurfaceView view = new SurfaceView(this);   // 注意：不是 GLSurfaceView
        setContentView(view);
        view.getHolder().addCallback(this);
        new LibUpdateClient(this).runUpdate();      // ← hvrbridge.jar：初始化 VR SDK Service 桥
        System.loadLibrary("alvr_client_openxr");
    }
    public void surfaceChanged(...) { if (!nativeStarted) { nativeStarted = true; nativeStart(holder.getSurface()); } }
    protected void onStop() {
        stopNativeIfNeeded();
        super.onStop();
        finishAndRemoveTask();
        android.os.Process.killProcess(android.os.Process.myPid());   // 返回 Glass Home 即退进程
    }
}
```

**关键读数**：
1. Java 侧**极薄**——Activity + SurfaceView + `LibUpdateClient.runUpdate()` + 一个 native 入口；
2. VR 渲染会话在 **native** 层（Rust/C++）建立，Java 只把 `Surface` 递进去；
3. `onStop` 就 `killProcess` —— 华为侧生命周期很硬，不能后台驻留；
4. Manifest 中 `vr_only` 与手机 LAUNCHER 入口**并存**：`vr_only` 并不等于「手机上没有图标」，2D 入口仍可保留（用于插眼镜前的准备与引导）。

### 3.2 它已经踩过的坑（每条都对我们有直接含义）

| # | 现象 | 根因与处理 | 对本项目的含义 |
|---|---|---|---|
| 1 | 双眼图看起来各转了 90° | **acquire-bound 帧缓冲的原始方向不能按普通外接显示处理**：Runtime 要求的每眼物理方向就是 90°，须保留；另单独修视频 **Y 轴镜像** | 我们接入时不要自作聪明「转正」，要按 Runtime 给的朝向渲染，只修自己的视频纹理 Y 向 |
| 2 | 画面偏广角、斜看立方体顶面变形 | 通用倾斜视锥正交化把 95°×95° **扩张成 ≈102.8°**（投影尺度降约 12.9%） | 华为路径**直接用 Runtime 原始 FOV**，不要套用我们现有的「FOV 滑块 → 对称透视」逻辑 |
| 3 | 画面固定、头转画面不跟 | 厂商解码器（HiSilicon）改写 Surface 时间戳单位，`AImage.timestamp` 与 pose 队列错位 → 重投影方向错、裁剪、抖动 | 我们的播放器用 ExoPlayer/MediaCodec，接入后需要**把视频帧时间戳与 OpenXR predicted display time 对齐**，否则运动画面会抖 |
| 4 | 间歇蓝屏/空白闪 | 解码器暂无新帧时应**保留上一张完整帧**，不要清成占位色 | 我们现有渲染在无帧时的清屏策略要为此单独调整 |
| 5 | 返回 Glass Home 后进程仍在跑 | `onStop` 里必须停流 + `finishAndRemoveTask` + 退出进程 | 新增的 VR Activity 要照做，避免后台占用解码器 |
| 6 | 只有一只手柄有位置 | 左右手必须**分别**创建单 subaction path 的 Pose Action / ActionSpace | 若做手柄支持（可选功能），注意这条 |
| 7 | 摇杆静置仍有输入（漂移） | 华为摇杆轴是 **0~1、中心 0.5**（不是 -1~1、中心 0）→ 先 `(raw-0.5)×2` 中心化，再 Y 取负、再 0.12 圆形死区 | 若映射手柄到我们的 seek/UI，必须做这套归一化 |
| 8 | 安装一直阻塞 / 眼镜黑屏 | 眼镜占用 USB-C 时安装确认页可能只显示在手机且眼镜里点不到 → **先拔眼镜安装授权**；黑屏时确认 SDK Service/Launcher 正常，**从 Glass Home 手动启动，不要用 ADB 强启 VR Activity** | 我们的调试流程要照此：拔线安装 → 插线 → 手动从 Glass Home 进 |
| 9 | VR Launcher/商店图标不对 | 需在 APK 内固定 `assets/vr_icon_background.png` + `vr_icon_foreground.png`（**双层**，Launcher 做景深合成），且不影响普通 `@mipmap/ic_launcher` | 想让自己出现在 Glass Home 里好看，需要额外做一套双层图标 |
| 10 | targetSdk 兼容 | 参考项目用 `minSdk 26 / targetSdk 28`，README 明确「Huawei 兼容 target SDK 28」 | 我们 `targetSdk 36`、`minSdk 24` —— **必须 POC 验证**华为 Runtime 在高 targetSdk 下是否正常（API 30+ 至少需要上面那个 `<queries>` 块） |

### 3.3 可直接复用的结论

1. **Manifest 模板**（附录 B）可以照抄结构，把 `freeDegree` 改成 `3dof|6dof`、`application.type` 改成 `video`；
2. **2D → VR 通道**用官方 `PROMPT` Activity，不需要自己写提示页；
3. **VR Activity 生命周期**照参考项目抄（停 native → 退任务 → 杀进程）；
4. **GLES 3.2 + native OpenXR** 是既定事实，不要指望纯 Java/GLES2 就能出图。

---

## 4. 本项目现状与差距分析

### 4.1 现有 VR 能力（v2.0.173）

| 能力 | 实现位置 | 说明 |
|---|---|---|
| 左右分屏立体 | `VRGLRenderer.onDrawFrame` | `glViewport(0,0,halfW,h)` / `(halfW,0,halfW,h)` 两次绘制 |
| 投影模式 | `ProjectionMode` 枚举 | STANDARD / FISHEYE / VR_360 / VR_180 / BOX（穹顶、曲率、Warp 变量） |
| 头姿 | Android `SensorManager`（陀螺仪）+ 手动拖拽 | `isGyroEnabled` / `gyroInverted` / manualYaw-Pitch |
| 视角参数 | `fovDeg`（滑块，默认 75°）、`vrIpdOffsetRatio` | 对称透视投影 + IPD 偏移 |
| 立体模式 | `StereoMode`（MONO / 左右等） | `stereo_mode` |
| 渲染承载 | `VRGLSurfaceView : GLSurfaceView` | `setEGLContextClientVersion(2)`、`setEGLConfigChooser(8,8,8,8,16,0)` |
| 依赖 | GLES2 shader（含美颜 GLSL 管线）、GPUPixel AAR（arm64/armv7） | 无任何 XR/VR SDK |

### 4.2 差距表（这是本报告最重要的一张表）

| 维度 | 现状 | 华为 OpenXR 要求 | 差距等级 |
|---|---|---|---|
| 图形 API | **OpenGL ES 2.0** 上下文 | Manifest 声明 **GLES 3.2**（`0x00030002`） | **高** —— 需建 GLES3.2 上下文；ES2 风格 shader 可在 ES3 上下文编译（`#version 100`），但需实测；`glReadPixels`、FBO 等 API 兼容 |
| 渲染目标 | GLSurfaceView 的 window surface（默认 FBO） | OpenXR swapchain 每眼纹理（须 FBO 绑定到该纹理） | **中** —— 渲染目标抽象化，shader/几何不动 |
| 会话管理 | 无 | native 层 `xrCreateInstance/Session/Swapchain`、帧循环 | **高** —— 新增 C++/JNI 模块（SDK 只给 C API） |
| 头姿 | 手机传感器（相对姿态）+ 手动拖拽 | Runtime 提供 per-view pose（predicted display time） | **中高** —— 头姿来源替换；现有手动拖拽/陀螺仪在华为模式下应停用 |
| 视角/FOV | 用户滑块（默认 75°，可变 1:1 缩放） | Runtime 四元组 FOV（95°×95°，非对称） | **中** —— 华为模式下 FOV/IPD 滑块必须禁用（否则冲突） |
| IPD | `vrIpdOffsetRatio` 用户偏移 | 63 mm 固定（Runtime） | 低（禁用即可） |
| Activity 结构 | 单 `MainActivity`（landscape、Compose 全界面） | 需要独立的 VR Activity（硬退出、landscape、无标题栏） | **中** —— 新增 1~2 个 Activity |
| Manifest | 常规 | `vr_only` + `freeDegree` + 华为权限 + `queries` | 低（照抄） |
| 生命周期 | 后台播放/长驻 | VR Activity `onStop` 即退出进程 | **中** —— 需与现有播放/字幕/翻译状态协调（退出会丢未保存状态？需设计回写时机） |
| 手柄 | 无 | 可选（`freeDegree=3dof` 可先不做） | 低（可延后） |
| targetSdk | **36** | 华为兼容性以 28 为验收基线 | **中** —— 需 POC 验证；不行则考虑为 VR Activity 所在的变体单独降 target 或用 `tools:targetApi` 处理 |
| ABI | arm64-v8a（+x86_64 走模拟器） | SDK 有 armv7/arm64 | 低 |
| 验证环境 | MuMu 模拟器（x86_64） | **必须华为手机 + VR Glass** | **高** —— 无替代方案 |

---

## 5. 接入方案

### 5.1 推荐架构（路径 A′：native 只管会话，Kotlin 复用渲染）

```
MainActivity（现有 Compose 播放器，2D，不动）
   │  设置页开关「华为 VR Glass（VR Engine）」= 开
   │   ↓ Intent(action = com.huawei.android.vr.PROMPT, package = 自己)
HVRModeActivity（hvrprompt.aar，官方提示页）
   │  用户插入 VR Glass → 系统跳过 VrLauncher
   ↓
HuaweiVrActivity（新增，landscape / fullscreen / singleTask）
   ├─ new LibUpdateClient(this).runUpdate()          // hvrbridge.jar
   ├─ 加载 libvrplayer_openxr.so（新增 native 库）
   │     └─ OpenXR：xrCreateInstance → xrGetSystem(HUAWEI)
   │           → xrGetOpenGLESGraphicsRequirementsKHR（拿到所需 GLES 版本）
   │           → xrCreateSession（绑定我们自建的 EGL display/context/config）
   │           → 每帧：xrWaitFrame → xrBeginFrame
   │                 → 每眼 xrAcquireSwapchainImage → 回调 JNI 把 texture id + view 参数给 Kotlin
   │                 → Kotlin 侧用**现有 shader/几何**把该眼画到 eye FBO
   │                 → xrReleaseSwapchainImage → xrEndFrame
   └─ Kotlin 渲染线程（复用 VRGLRenderer 的绘制逻辑，目标改为 eye texture）
```

**为什么这样分层**：OpenXR 的 GLES binding 会把 swapchain image 暴露为 **GL texture id**（`XrSwapchainImageOpenGLESKHR.texture`）。因此「会话/帧节奏」在 native，「画什么」仍可留在 Kotlin 的现有 GLES 代码里 —— 这是让本次改造量可控的关键。

### 5.2 新增模块清单（预估）

| 模块 | 语言 | 规模（估） | 说明 |
|---|---|---|---|
| `lib_loader.so` + OpenXR 头文件 | 预编译 + 头 | —— | 来自华为 SDK，不入库 |
| `vrplayer_openxr`（native） | C++ | 约 600~1200 行 | Instance/System/Session/Swapchain/帧循环/view 参数查询/事件轮询；JNI 门面 |
| `HuaweiVrActivity` | Kotlin | 约 150~250 行 | 生命周期、`LibUpdateClient`、native 启停、退出即杀进程 |
| `OpenXrRenderBridge` | Kotlin | 约 200~400 行 | 连接 native 与现有 renderer：把 eye texture 包成 FBO，注入 per-view FOV/pose，驱动绘制 |
| `VRGLRenderer` 改造 | Kotlin | 约 200~400 行改动 | 抽出「渲染目标」与「视图参数」来源；华为模式下忽略 fovDeg/IPD 滑块，改用 Runtime 值 |
| Manifest / gradle | 配置 | 小 | 华为 meta-data、权限、queries、`externalNativeBuild`、GLES3.2 声明 |
| 设置开关 + 文案 | Kotlin + 5 语 | 小 | 见第 6 节 |

> 说明：`GLES2 → GLES3.2` 的迁移风险集中在 **shader 编译与 API 使用**。ES2 语法 shader（`#version 100` / `attribute` / `varying` / `texture2D`）在 ES3 上下文里**允许**编译，但若 Runtime 强制要求 3.2 且不允许 ES2 目标，则需把美颜等 shader 迁到 `#version 300 es`（`in/out`、`texture()`、`layout`）。这是 P0 必须实测的第一件事。

### 5.3 帧循环（伪代码）

```kotlin
// Kotlin 侧每帧（由 native 帧节奏驱动）
fun onOpenXrFrame(eyeTextures: IntArray, views: Array<EyeView>) {
    for (eye in 0..1) {
        bindEyeFbo(eyeTextures[eye])                 // glGenFramebuffers + glFramebufferTexture2D
        glViewport(0, 0, views[eye].width, views[eye].height)
        // ↓ 复用现有渲染：投影矩阵改为 FOV 四元组 + pose 旋转
        renderScene(projection = fovToPerspectiveMatrix(views[eye].fov),
                    view = poseToViewMatrix(views[eye].pose),
                    texture = videoTexture)
    }
}
```

要点：
- **视频帧仍走现有 ExoPlayer → SurfaceTexture → GL 纹理**（这一层不动）；
- 投影矩阵由 Runtime 的 `fov`（左右上下四角）构造**非对称透视**，不能再用现有的 `Matrix.perspectiveM(fovDeg, aspect, ...)`；
- 视图矩阵由 Runtime 的 per-view pose 构造（替换传感器陀螺仪）；
- 每眼分辨率用 Runtime 的 `recommendedImageRectWidth/Height`（1552×1552，实际协商常向下对齐到 1536×1536）。

### 5.4 头姿与输入

| 输入 | 华为模式下的处理 |
|---|---|
| 头姿 | 改用 OpenXR `xrLocateViews`（predicted display time）——现有陀螺仪代码在华为模式下**停用**（避免两套姿态打架） |
| 手动拖拽转视角 | 保留（作为 pose 的额外 yaw/pitch 偏移，参考项目也是这么做的正交化） |
| 手势/触摸 | VR Activity 内触摸不可用（手机不在手上或有线连接）—— UI 操作要靠手柄或**进入前**在手机上准备好 |
| 6DoF 手柄 | **P2 可选**：`freeDegree=3dof|6dof` + 手柄归一化（0.5 中心、Y 取负、0.12 死区）映射到 播放/暂停、seek、菜单。**建议第一版先不做**，`freeDegree=3dof` |
| 交互 UI | 现有 Compose 播放控件在华为模式下无法显示在眼镜里（除非做立体 UI 渲染）→ 第一版策略：**进入前的 2D 界面准备好一切（选片、字幕、设置），VR 内只做播放** |

### 5.5 显示方向 / 镜像 / FOV 的三个坑

1. **每眼 90° 物理方向**：Runtime 的 eye texture 朝向就是它在眼镜里的物理朝向，**不要转正**；参考项目特意保留。
2. **Y 轴镜像**：华为显示链路需要单独修正 Y 镜像——本项目现有 `isVideoMirrored` 逻辑可复用，但方向判定要按参考项目的结论（Y 轴，而非 X）。
3. **FOV 用原值**：Runtime 报 95°×95° 就按 95°×95° 渲染；**不要**套用「倾斜视锥正交化」把双眼局部姿态统一，否则会扩张成 ≈102.8° 导致画面偏广角。

### 5.6 生命周期与退出

```
用户按 Home / 摘下眼镜 → onStop：
   1. 停止 native 帧循环（xrEndSession / xrDestroySession）
   2. 持久化必要状态（字幕进度、播放位置——走现有 prefs 写回）
   3. finishAndRemoveTask()
   4. 退出进程（照参考项目）
```

> ⚠️ 与现有功能的冲突点：本项目有「记忆模式」写回 `LaunchedEffect`（设置项持久化）。VR Activity 直接杀进程会**跳过 Compose 的正常销毁流程**，因此**必须在 stop 前主动落盘**（不能依赖 `onDestroy`/`LaunchedEffect` 的取消路径）。这是我们比参考项目（无持久化需求）多出来的一个必须处理项。

---

## 6. 设置开关设计（用户明确要求的部分）

### 6.1 开关的形态选择

| 方案 | 描述 | 优点 | 缺点 |
|---|---|---|---|
| **H1（推荐）** | 设置页新增开关 `华为 VR Glass（VR Engine）`，默认**关**；开启后**下次进入 VR** 走华为路径；播放页出现「进入华为 VR」按钮（调用 `PROMPT` Intent） | 与用户表述一致；手机 2D 体验零影响 | 需要两个 Activity 并存 |
| H2 | 只做「进入华为 VR」按钮，不做开关 | 简单 | 不满足「加一个新开关」的要求 |
| H3 | 把现有「分屏 VR」开关直接改成三态（关 / 内置分屏 / 华为 VR） | 概念统一 | 改动现有交互，风险高；且华为路径与内置分屏并非并列关系 |

**采纳 H1 + H2 组合**：设置页有开关（记忆），播放页/设置页提供显式「进入华为 VR」入口，二者语义一致（开关只决定**默认后端与入口是否显示**）。

### 6.2 UI 位置与文案

- 位置：设置页分组 **「1. 镜头投影与视角模式」** 内（与投影模式、分屏、FOV、IPD 同一组），放在「立体模式」之后；
- 建议文案（5 语，与现有 `seek_ball_desc` 风格一致）：

| 语言 | 标题 | 说明 |
|---|---|---|
| zh-CN | 华为 VR Glass（VR Engine） | 通过华为 VR 运行时输出到 VR Glass；开启后需连接眼镜并从 VR 入口进入 |
| zh-TW | 華為 VR Glass（VR Engine） | 透過華為 VR 執行時輸出到 VR Glass；開啟後需連接眼鏡並從 VR 入口進入 |
| en | Huawei VR Glass (VR Engine) | Output through the Huawei VR runtime; connect the glasses and enter from the VR entry |
| ja | Huawei VR Glass（VR Engine） | Huawei VR ランタイム経由で出力。グラス接続後、VR 入口から起動してください |
| ko | Huawei VR Glass (VR Engine) | Huawei VR 런타임으로 출력합니다. 안경 연결 후 VR 입구에서 실행하세요 |

### 6.3 建议新增的 prefs 键

| 键 | 类型 | 默认 | 门控 | 说明 |
|---|---|---|---|---|
| `huawei_vr_enabled` | Boolean | `false` | 受 `is_memory_mode_enabled`（与其它设置一致） | 开关本体；**默认关**（华为设备占比低，避免误入） |
| `huawei_vr_render_scale` | Float | `1.0f` | 同上 | 每眼分辨率相对 Runtime 推荐值的比例（预留性能调档：1.0 / 0.75 / 0.5） |
| `huawei_vr_prefer_6dof` | Boolean | `false` | 同上 | 手柄模式（P2 才用） |
| `huawei_vr_last_result` | Int | `0` | 不上屏 | 上次启动结果（0 未知 / 1 成功 / 2 Runtime 缺失 / 3 初始化失败），用于给用户提示 |

> ⚠️ **写回陷阱（本项目已有前车之鉴）**：新增开关状态**必须同时加入集中写回 `LaunchedEffect` 的 key 列表**，否则只改它不会触发写回、prefs 永不落盘（v2.0.172 刚修过 14 个缺失 key 的同类问题）。

### 6.4 路由与降级逻辑

```kotlin
fun onHuaweiVrToggle(enabled: Boolean) {
    huaweiVrEnabled = enabled                    // → 走现有写回 Effect 落盘
    if (enabled && !isHuaweiVrRuntimeAvailable()) {
        // 无 VR SDK Service / 非华为机型：不阻断用户，但给出提示
        showHint(R.string.huawei_vr_runtime_missing)   // 「未检测到华为 VR 运行时，将使用内置分屏 VR」
    }
}

fun enterVr() {
    if (huaweiVrEnabled && isHuaweiVrRuntimeAvailable()) {
        startActivity(Intent("com.huawei.android.vr.PROMPT").setPackage(packageName))  // 官方提示页 → VR Activity
    } else {
        startVrPlaybackWithInternalSplitScreen()   // 现有路径，零改动
    }
}

fun isHuaweiVrRuntimeAvailable(): Boolean =
    packageManager.getPackageInfo("com.huawei.hvrsdkserverapp", 0) != null ||
    packageManager.getPackageInfo("com.huawei.vrhandle", 0) != null
```

**降级原则**：任何一步失败（Runtime 缺失、`xrCreateInstance` 失败、GLES 版本不足、眼镜未连接）→ **提示一句话 + 自动回退到内置分屏 VR**，绝不让用户卡在黑屏。

### 6.5 与现有设置的联动矩阵

| 现有设置 | 内置分屏 VR | 华为 VR 模式 | 说明 |
|---|---|---|---|
| `isSplitScreenVR` | 生效 | **由 Runtime 接管**（每眼 swapchain）→ 忽略 | 华为下不读该值 |
| `ProjectionMode`（STANDARD/360/180/BOX…） | 生效 | **生效**（几何内容仍需判断平面/球面/穹顶） | 保留 |
| `fovDeg` | 生效 | **忽略**（用 Runtime FOV） | 建议 UI 上置灰并提示 |
| `vrIpdOffsetRatio` | 生效 | **忽略**（用 Runtime IPD 63mm） | 同上 |
| `isGyroEnabled` | 生效 | **停用**（改用 OpenXR pose） | 避免两套姿态冲突 |
| `stereoMode` | 生效 | 忽略（Runtime 决定双眼） | —— |
| 美颜（GLSL/GPUPixel） | 生效 | **可生效但需评估性能**（每眼 1552×1552@70Hz + 全帧美颜 = 重负载） | 建议华为模式下默认降档或提示 |
| 字幕/翻译 | 生效 | 生效（渲染在画面层） | 字幕覆盖层需按每眼分别绘制 |
| 悬浮球/播控 UI | 生效 | **不可用**（VR 内无触摸） | 进入前准备好 |

---

## 7. 风险评估

| # | 风险 | 影响 | 概率 | 缓解措施 |
|---|---|---|---|---|
| 1 | **无华为真机** | 完全无法验证，方案只能停留在纸面 | 高（已确定） | 需用户提供华为手机 + VR Glass（+ 可选 6DoF 套件）；否则只能做到「代码就绪、待验证」 |
| 2 | GLES2 → GLES3.2 | shader/API 需迁移，美颜管线受影响 | 中高 | P0 先做最小 Demo：ES3.2 上下文 + 现有 shader 编译测试 |
| 3 | targetSdk 36 vs 华为验收 28 | Runtime 行为异常/手柄异常 | 中 | POC 验证；必要时为 VR 变体单独下调 target 或按官方 `<queries>` 补齐 |
| 4 | SDK 许可与再分发 | 合规问题 | 中 | SDK 文件不入库、不走公开 Release；上架前核对华为条款 |
| 5 | 性能（1552×1552×2 @70Hz + 全帧美颜 + 解码） | 掉帧、发热、温控降频 | 中高 | `application.type=video`（温控策略）；美颜默认降档；预留 `huawei_vr_render_scale` |
| 6 | 生命周期杀进程导致设置丢失 | 用户体验问题 | 中 | stop 前主动落盘（见 5.6） |
| 7 | 「vr_only」与手机日常使用冲突 | 可能影响普通手机上的启动行为 | 中 | POC 验证；参考项目证明「vr_only + LAUNCHER 入口」可共存 |
| 8 | 手柄兼容（P2） | 需额外适配与归一化 | 低（可延后） | 第一版 `3dof`、不做手柄 |
| 9 | 华为 Runtime 版本碎片 | 不同机型行为不同 | 中 | 记录 `SDK Service` 版本；以用户实机为准 |
| 10 | 与现有「旋转/镜像」历史坑重叠 | 方向类改动容易反复 | 中 | 遵守 5.5 的三条结论，先做单眼方向验证再做双眼 |

---

## 8. 分阶段实施计划

| 阶段 | 目标 | 产出 | 验收标准 | 前置依赖 |
|---|---|---|---|---|
| **P0** POC | 证明「能在华为眼镜里出图」 | 最小 OpenXR Demo：单色/测试图，双眼正确、可转头 | 眼镜内看到稳定画面，`adb logcat` 无 OpenXR 错误 | **华为手机 + VR Glass**、SDK、NDK 环境 |
| **P1** 渲染接入 | 视频画面进入 OpenXR 双眼 | 播放本地视频，双眼正确 FOV/IPD/朝上 | 与手机 2D 播放内容一致，无明显畸变 | P0 |
| **P2** 输入与生命周期 | 头姿跟随、退出干净 | pose 跟随头动；Home 退出后进程结束、设置已落盘 | 转头画面跟手；重进后设置仍在 | P1 |
| **P3** 设置开关与路由 | 用户可见的开关与入口 | 设置页开关（5 语）+ 播放页入口 + 降级提示 | 非华为机型开关开启后自动回退且提示正确 | P2（可先做 UI，后接后端） |
| **P4** 真机验收与打磨 | 观感与性能达标 | 帧率/温控/画质报告；`render_scale` 调档默认值确定 | 用户佩戴验收通过 | P3 |
| (P5) 可选 | 6DoF 手柄 | 手柄映射播放控制 | 手柄可暂停/seek | P4 |

**建议的推进方式**：P3 的设置开关+文案+路由（纯 Kotlin/Compose，不依赖真机）**可以先做**，用开关把华为路径「藏」在默认关后面；P0~P2 的真机部分等设备到位再上。这样在没有华为设备的情况下也能先把「用户可见的一层」落地。

---

## 9. 前置条件清单

- [ ] **华为手机**（旗舰机型，EMUI 10 / Android Q 以上）
- [ ] **华为 VR Glass**（含自带 C2C 数据线）
- [ ] 手机已插眼镜完成 VR 运行环境自动安装（或手动安装 VR SDK Service）
- [ ] 华为开发者账号（下载 SDK、后续上架）
- [ ] `hvrsdk-openxr-3.5.0.79.zip`（校验 SHA256：`0fcf46b0…`）+ OpenXR Demo 包
- [ ] Android NDK / CMake（新增 native 模块）
- [ ] （可选）Huawei 6DoF Gaming Kit
- [ ] 真机调试方式：**先用 USB 开 `adb tcpip 5555` + 无线 adb，再把 USB-C 让给眼镜**

---

## 10. 附录

### A. 官方文档与资源

| 资源 | 链接 |
|---|---|
| VR Engine 简介 | https://developer.huawei.com/consumer/cn/doc/graphics-Guides/introduction-0000001144238265 |
| 使用入门（环境/流程/自检/上架） | https://developer.huawei.com/consumer/cn/doc/development/graphics-Guides/dev-process-0000001144238279 |
| 应用开发（OpenXR 插件、Demo、Manifest） | https://developer.huawei.com/consumer/cn/doc/graphics-Guides/app-dev-0000001097518330 |
| Manifest 配置（键名原文） | https://developer.huawei.com/consumer/cn/doc/graphics-Guides/configuration-manifest-0000001205526407 |
| 2D 界面提示设置（PROMPT） | https://developer.huawei.com/consumer/cn/doc/graphics-guides/configuration-set-prompt-0000001205644943 |
| 自定义 Activity 设置（HVRActivity/jar） | https://developer.huawei.com/consumer/cn/doc/graphics-Guides/configuration-custom-activity-0000001160324976 |
| SDK 下载（OpenXR） | https://developer.huawei.com/consumer/cn/doc/graphics-Library/openxr-sdk-download-0000001148849243 |
| 参考项目 | https://github.com/LHT02/HuaweiVRGlass-ALVR （技术细节见 `HUAWEI_VR_GLASS.md`） |

### B. Manifest 参考模板（改编自参考项目，**未实际使用**）

```xml
<!-- 权限 -->
<uses-permission android:name="com.huawei.android.permission.VR" />
<uses-permission android:name="com.huawei.vrhandle.permission.DEVICE_MANAGER" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />

<uses-feature android:name="android.hardware.vr.headtracking" android:required="false" android:version="1" />
<uses-feature android:glEsVersion="0x00030002" android:required="false" />

<queries>
    <package android:name="com.huawei.vrhandle" />
    <package android:name="com.huawei.hvrsdkserverapp" />
    <intent><action android:name="com.huawei.vrhandle.service.vrdeviceservice" /></intent>
</queries>

<application>
    <!-- 华为 VR 应用声明 -->
    <meta-data android:name="com.huawei.android.vr.application.mode" android:value="vr_only" />
    <meta-data android:name="com.huawei.android.vr.application.type" android:value="video" />
    <meta-data android:name="com.huawei.vr.application.freeDegree" android:value="3dof|6dof" />
    <meta-data android:name="android.max_aspect" android:value="2.1" />
    <!-- 调试专用（不戴眼镜看手机显示），正式包必须删除
    <meta-data android:name="com.huawei.android.vr.sensor.mode" android:value="mobile" />
    -->

    <!-- 现有 2D 主入口（保持不变） -->
    <activity android:name=".MainActivity" android:exported="true" android:screenOrientation="landscape">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
        <!-- 现有 VIEW / SEND 过滤器保持 -->
    </activity>

    <!-- 新增：VR 渲染 Activity（华为 intent-filter，去桌面图标、供 VrLauncher 跳转） -->
    <activity
        android:name=".vr.HuaweiVrActivity"
        android:exported="true"
        android:launchMode="singleTask"
        android:screenOrientation="landscape"
        android:configChanges="density|keyboard|keyboardHidden|navigation|orientation|screenLayout|screenSize|uiMode">
        <intent-filter>
            <action android:name="com.huawei.android.vr.action.MAIN" />
            <category android:name="android.intent.category.DEFAULT" />
        </intent-filter>
    </activity>

    <!-- 官方 2D 提示页（来自 hvrprompt.aar），声明即可用 -->
    <activity
        android:name="com.huawei.vrlab.HVRModeActivity"
        android:exported="true"
        android:launchMode="singleTask"
        android:screenOrientation="portrait"
        android:taskAffinity=":finishing">
        <intent-filter>
            <action android:name="com.huawei.android.vr.PROMPT" />
            <category android:name="android.intent.category.DEFAULT" />
        </intent-filter>
    </activity>
</application>
```

### C. 待 POC 确认清单（真机到手后逐条打勾）

1. [ ] `hvrbridge.jar` 的公开类清单（`javap`）——确认除 `LibUpdateClient` / `HVRModeActivity` 外是否还有可用的 **Java 级 VR API**（若有，可减少 native 工作量）
2. [ ] OpenXR Runtime 是否强制 **GLES 3.2**；ES2 风格 shader（`#version 100`）在 3.2 上下文能否直接编译
3. [ ] `targetSdk 36` 下华为 Runtime 是否正常（手柄、显示、生命周期）
4. [ ] `vr_only` 与手机桌面图标/日常 2D 使用的共存表现
5. [ ] `application.type=video` 与 `game` 的温控差异（对我们的全帧美颜负载很关键）
6. [ ] 每眼 90° 物理方向 + Y 镜像的**具体符号**（用测试图逐眼确认，不要猜）
7. [ ] 解码帧时间戳与 `xrLocateViews(predictedDisplayTime)` 的对齐方式（参考项目为此踩了大坑）
8. [ ] 1552×1552/眼 @70Hz 下，开启全帧美颜（GPUPixel）的实际帧率与发热

### D. 一句话总结

> 华为 VR Engine 接入在技术上是**标准 OpenXR 的工程问题**，不是「有没有接口」的问题。真正的门槛有两个：**要把渲染上下文升到 GLES 3.2 并在 native 层建 OpenXR 会话**，以及**必须有一台华为手机 + VR Glass 才能验证**。设置开关本身很轻（一个 prefs 布尔 + 一个官方 PROMPT 跳转 + 失败回退），可以先落地；重活集中在 P0~P2。
