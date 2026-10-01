# 技术架构与目录结构 / Architecture

> 📖 本文是 [README](../README.md) 的拆分文档之一。返回主文档请点上面的链接。


分层架构、关键组件职责表、仓库目录布局。

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
│  10 引擎字幕翻译                                      │
│  Room 持久化（设置记忆/字幕缓存）                     │
└─────────────────────────────────────────────────────┘
```

## 关键组件 / Key Components

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
| `SubtitleTranslator.kt` | 自研多引擎 | 字幕翻译（**10 种引擎**可切换，含 MyMemory 限速与配额冷却、Google 免密端点） |
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
│       │   ├── luts/              # 36 款内置 3D LUT（.cube，v117 起生效；人像美颜 24 + 风格滤镜 12）
│       │   ├── gpupixel/          # GPUPixel 引擎资源（⚠️ 不可删！7 张 lookup png + 2 个 .mars_model）
│       │   ├── face_landmarker.task  # MediaPipe 人脸模型
│       │   ├── silero_vad.onnx    # Silero VAD 语音活动检测（629KB）
│       │   ├── sense-voice/       # SenseVoice 识别模型（内置；model.int8.onnx 由 scripts/fetch_asr_model.py 拉取）
│       │   └── licenses.json      # 开源许可清单（自动生成）
│       ├── cpp/                   # 华为 VR Glass 的 OpenXR 原生会话层
│       └── res/                   # 资源与字体（MiSans/OPPO Sans）
├── third_party/gpupixel/          # GPUPixel（git submodule，源码级集成；其 src/res 亦为 assets/gpupixel 的来源）
├── docs/                          # 专题文档（见下方「文档索引」）
├── gradle/libs.versions.toml      # 依赖版本目录
├── scripts/fetch_asr_model.py     # 内置 ASR 模型拉取脚本（hf-mirror，支持断点续传）
├── scripts/gen_licenses.py        # 许可清单生成脚本
├── LICENSE                        # Apache License 2.0
├── RELEASE_SIGNING.md             # 签名与发布流程
├── FIREBASE_ANALYTICS.md          # 统计接入说明
└── README.md
```

> 多语言扩展模型**不在仓库内**，由 App 运行时按需下载到设备私有目录。
> The multi-language ASR extension models are **not** in the repository — the app downloads them into its private directory at runtime.
