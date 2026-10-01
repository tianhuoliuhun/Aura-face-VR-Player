# 隐私与开源许可 / Privacy & Licenses

> 📖 本文是 [README](../README.md) 的拆分文档之一。返回主文档请点上面的链接。


本地优先原则、可选匿名统计的采集范围、依赖与开源许可清单。

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
- 接入说明 / Integration guide: [FIREBASE_ANALYTICS.md](../FIREBASE_ANALYTICS.md)

---

## 📦 依赖与开源许可 / Dependencies & Licenses

本项目基于 Google AI Studio 生成的项目骨架，核心功能均为自研实现。
Built on a Google AI Studio generated skeleton; core features are self-developed.

| 依赖 / Dependency | 许可证 / License | 用途 / Usage |
|---|---|---|
| Jetpack Compose / Material3 | Apache-2.0 | UI 框架 |
| Media3 ExoPlayer / Transformer | Apache-2.0 | 播放内核 + 缓存数据源 |
| MediaPipe Tasks Vision | Apache-2.0 | 人脸关键点（GLSL 引擎） |
| [GPUPixel](https://github.com/pixpark/gpupixel) | Apache-2.0 | 可选美颜引擎（预编译 AAR，含 Mars-Face 关键点 + `libgpupixel.so`；**仅 arm64-v8a / armeabi-v7a**） |
| sherpa-onnx | Apache-2.0 | 离线语音识别（SenseVoice + 多语言 transducer） |
| commons-compress | Apache-2.0 | tar.bz2 整包解压（多语言模型兜底下载） |
| Retrofit / OkHttp / Moshi | Apache-2.0 | 网络与 JSON |
| jcifs-ng | LGPL-2.1 | SMB 局域网播放 |
| JNA | LGPL-2.1 / Apache-2.0 | 原生库桥接 |
| Room | Apache-2.0 | 本地持久化 |
| [bing-translate-api](https://github.com/plainheart/bing-translate-api)（参考） | MIT | 必应翻译免费端点（自研 Kotlin 实现） |

**资源 / Resources**：MiSans / OPPO Sans 字体（免费商用授权）、MediaPipe 模型（Apache-2.0）、GPUPixel AAR 内置的 **Mars-Face** 人脸关键点模型（MIT）、36 款内置 LUT —— 前 12 款风格滤镜为项目自研 numpy 脚本生成（无第三方版权）；19~24、31~42 共 18 款取自 [t0saki/lumix-original-looks](https://github.com/t0saki/lumix-original-looks)（**MIT**）；25~30 共 6 款（柯达胶片仿真）取自 [scernst13/HaldCLUT-Cube-Files](https://github.com/scernst13/HaldCLUT-Cube-Files)（**CC0-1.0 公共领域**）。全部仅做格式规范化，数值未改动，许可均允许商用。

**Assets**：MiSans / OPPO Sans fonts (free commercial licence), MediaPipe models (Apache-2.0), **Mars-Face (MIT) bundled inside the GPUPixel AAR**, and 36 bundled LUTs — the first 12 style filters are generated in-house by a numpy script (no third-party rights); 18 come from [t0saki/lumix-original-looks](https://github.com/t0saki/lumix-original-looks) (**MIT**); 6 Kodak film emulations come from [scernst13/HaldCLUT-Cube-Files](https://github.com/scernst13/HaldCLUT-Cube-Files) (**CC0-1.0**, public domain). All are format-normalised only with values untouched, and every licence permits commercial use.

**ASR 模型许可 / ASR model licenses**：SenseVoice、zipformer、NeMo FastConformer 均为 Apache-2.0；泰语 zipformer 模型源自 `icefall-asr-gigaspeech2`，亦为 Apache-2.0。
All ASR models — the bundled SenseVoice and the downloadable zipformer / NeMo FastConformer ones — are Apache-2.0; the Thai zipformer derives from `icefall-asr-gigaspeech2` (also Apache-2.0).

完整许可清单见应用内「设置 → 关于与开源许可」或 `app/src/main/assets/licenses.json`。

---
