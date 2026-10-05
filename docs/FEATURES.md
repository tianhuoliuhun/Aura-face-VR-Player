# 功能详解 / Features

> 📖 本文是 [README](../README.md) 的拆分文档之一。返回主文档请点上面的链接。


各功能的完整说明。总览与快速上手见 README。

---

## 🥽 VR 播放能力 / VR Playback
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
- **容器与格式兼容（v2.1.241 起，v2.1.242 全量补齐，v2.1.243 修正归属）**：WMV / ASF、RM / RMVB、ISO 镜像等「非常规」片源
  - **全量白名单**（按实测各内核真实能力分类，单一入口 `MediaFormats.kt`）：
    - 双内核通用：`mp4 / m4v / mov / 3gp / 3g2 / f4v / mkv / webm / flv / ts / m2ts / mts /
      mpg / mpeg / m1v / m2v / vob / dat / ogv / wtv`
    - **仅 EXO 可开**（IJK 的 FFmpeg 是裁剪版，无 avi/ogg 解复用器，送到 IJK 必然失败）：
      `avi / divx / ogv / ogg`
    - **仅 MPV 可开**（v2.1.243 更正 —— 原先 v2.1.242 标为「必须走 IJK」，但实测 **IJK 三条
      解码路径对 WMV/RM 全部无解**，详见下方）：`wmv / asf / wmvhd`、
      `rm / rmvb / ra / ram / rmhd`、`iso`
  - ⚠️ **v2.1.243 关键更正（IJK 解不了 WMV/RM 的实证）**：早期版本认为 WMV 交给 IJK 即可，
    但对 `libijkplayer.so` 做符号表 + 编译横幅侦察后确认 **IJK 物理上无法解 WMV/VC-1/RM**：
    (a) 软解器被 `--disable-decoders --enable-decoder=...` 白名单裁掉，**没有 wmv2/wmav2/vc1**；
    (b) `--disable-hwaccels` 在编译期关掉了所有硬解器；
    (c) IJK 自研的 MediaCodec 通道符号虽全，但 **codec→MIME 映射表只 12 项，没有 `video/x-ms-wmv`**，
    且该表编译进 so 无法运行时扩展。因此 **WMV / RM 必须交给 MPV 内核**（FFmpeg 全量构建）
  - **内核自动路由**：打开上述「仅 MPV 可开」的片源时，即使当前选的是 EXO / IJK / 系统解码，
    也会**本次自动改用 MPV 内核**播放，并弹出提示 —— 用户设置不会被改写
    （下次播普通 MP4 仍走原内核）。反向保护：`avi/ogv/ogg` 等多内核可开的格式**永不**被路由到 IJK
  - **MPV 内核已内置**（v2.1.243 起）：MPV 的 native 库直接打进 APK，**无需下载**即可使用；
    设置 → 解码内核里可正常看到 MPV 选项。IJK 播放失败时也会**自动降级到 MPV**（若该格式属仅 MPV 可开）
  - **选择器补全**：点顶部「+」会先问「相册」还是「任意文件」。相册入口按 `video/*` 过滤，
    **列不出 WMV / ISO**，此时请选「任意文件」
  - **ISO 能力边界**：只支持**未加密的数据镜像**（UDF / ISO9660）。DVD-Video（`VIDEO_TS`）
    与蓝光（`BDMV`）**不支持直接播放** —— 应用会读镜像头部自动判定并给出明确提示
    （而不是黑屏或播到花絮），请先用工具提取其中的 VOB / M2TS
  - **SMB 上的 WMV 无解**：WMV 需要 MPV 内核，而 MPV 内核不支持 `smb://` 协议，
    因此 SMB 共享里的 WMV 会提前提示「请先下载到本地」
  - **Format compatibility (v2.1.241; full list v2.1.242; routing corrected in v2.1.243)**: every
    container the app can open is declared once in `MediaFormats.kt`, split by **measured** kernel
    capability — dual-engine common formats, **EXO-only** containers (`avi/divx/ogv/ogg`; the
    trimmed FFmpeg inside IJK has no AVI/Ogg demuxer), and **MPV-only** containers
    (`wmv/asf`, `rm/rmvb`, `iso`) which are **auto-routed** to the MPV kernel for that playback
    without rewriting the user's decoder setting. v2.1.243 corrects an earlier assumption: IJK
    **cannot** decode WMV/RM at all (its software decoders, hardware accelerators and the 12-entry
    codec→MIME map of its MediaCodec path all lack WMV/VC-1), so MPV — now **bundled inside the
    APK**, no download needed — is the only kernel that can. The "+" button asks whether to pick
    from the **gallery** (filtered to `video/*`, so WMV/ISO will not show) or **any file**. ISO
    support covers **unencrypted data images** (UDF / ISO9660) only; DVD-Video and Blu-ray
    structures are detected from the image header and rejected with a clear message instead of
    going black.

## ⚡ 画质增强 / Video Enhancement（v2.0.206 起）
- **MEMC 运动补偿插帧**：在相邻两帧之间生成中间帧，让运动更顺滑
  - 目标帧率 **48 / 60 / 72 / 90 / 120** 可选
  - 运动估计（3 步菱形搜索 + ±1 精修）+ 双向运动补偿 + 遮挡检测（遮挡处退化为线性混合，宁可糊也不撕裂）
  - **场景切换自动跳过插帧**（1×1 帧差检测），避免两个场景叠加的鬼影；相位由**时间**驱动，渲染循环与源帧率不同步时也不抖动
  - **Motion-compensated frame interpolation**: target 48 / 60 / 72 / 90 / 120 fps; block-matching ME + bidirectional MC with occlusion handling; automatic scene-cut skip via a 1×1 frame-diff read-back; time-driven phase so it never jitters when the render loop is out of sync with the source frame rate
- **FSR 超分**：EASU（边缘自适应上采样）+ RCAS（对比度自适应锐化）
  - **默认规则**：源 >1440p 不启用；=1080p → 1440p；其余 → 1080p（另加保护：目标不高于源时不启用，避免降画质）
  - **自定义规则**：720p / 1080p / 1440p / 2160p / 3840p / 4320p 六档，**完全接管**默认规则
  - 档位语义为「目标高度」，宽度按**源宽高比**推导 → 非 16:9 片源不会变形；目标超出 GPU 纹理上限时自动降级为不超分（防静默黑屏）
  - **FSR upscaling**: EASU + RCAS. The default rule disables upscaling above 1440p, maps 1080p → 1440p and everything else → 1080p (plus a guard that refuses targets not higher than the source); a custom rule **fully overrides** it with 720p / 1080p / 1440p / 2160p / 3840p / 4320p. Tiers define a target *height* and the width derives from the source aspect ratio, so non-16:9 footage is never stretched; targets beyond `GL_MAX_TEXTURE_SIZE` degrade to a no-op rather than going silently black
- 管线顺序 **MEMC → 超分**（时间域在前、空间域在后：ME 成本 ∝ 像素数 × 半径²，先超分再估运动会贵 16 倍，且会去匹配超分生成的假细节）
  - Pipeline order is **MEMC then upscaling** — temporal before spatial (motion estimation costs scale with pixel count × radius², so upscaling first would be ~16× more expensive and would match against detail that was synthesised rather than captured)
- ⚠️ 两者**仅在视频播放时生效**；图片 / 全景浏览模式下开关打开也不参与
  - Both only apply during **video playback**; the toggles stay inert in image / panoramic browsing
- 📖 **完整规则口径、实现细节、性能与已知限制见 [`docs/VIDEO_ENHANCE_MEMC_FSR.md`](VIDEO_ENHANCE_MEMC_FSR.md)**
  - Full rule semantics, implementation notes, performance and limitations: [`docs/VIDEO_ENHANCE_MEMC_FSR.md`](VIDEO_ENHANCE_MEMC_FSR.md)

## ✨ 实时 AI 美颜 / Real-time AI Beauty（GLES 着色器）
- 通用美颜：磨皮（双边滤波）、美白、亮度/对比度微调——2D/3D 模式均生效
  - General beauty: skin smoothing (bilateral filter), whitening, brightness/contrast — works in 2D/3D
- 2D 人像精修（MediaPipe **478 点**面部关键点）：瘦脸、大眼、去黑眼圈、鼻梁塑形、嘴型调整、牙齿美白、口红、腮红、眉毛
  - 2D portrait retouch (MediaPipe **478** face landmarks): face slimming, big eyes, dark-circle removal, nose shaping, mouth adjust, teeth whitening, lipstick, blush, eyebrows
- 美颜预设：自然 / 淡妆 / 浓妆 / 自定义（**已持久化**，切语言也不会失配），支持对比原图（一键关美颜）
  - Presets: Natural / Light / Heavy / Custom (persisted); one-tap before/after compare
- **跟踪一致性修复（v2.0.156）**：人脸坐标会先由「采样窗口」换算回**整幅画面**再驱动妆容与变形（此前直接混用两套坐标 —— 脸一偏离画面中心，口红/腮红/大眼/瘦脸就会整体跑偏）；分屏 VR 下也持续跟踪；**只在开启依赖人脸的效果时才做检测**（只用磨皮或美白不再白付开销），采样缓冲与数组均已复用
  - Landmark coordinates are now mapped from the sampling window back to full-frame space before driving cosmetics and warping (the two spaces used to be mixed, which made cosmetics drift as soon as the face left the centre); tracking also runs in split-screen VR, sampling only happens when a face-dependent effect is enabled, and the sampling buffers/arrays are reused
- **双引擎可切换（v2.0.160 起）**：默认「GLSL 内置」纯着色器管线，低功耗、零额外库体积；另有「GPUPixel」可选引擎（预编译 AAR，含 Mars-Face 人脸关键点，端上 GPU 后处理），2D 覆盖整个视频画面、VR / 全景覆盖整个物理屏幕，并可独立开关「VR 下是否启用」
  - **Dual switchable engines (since v2.0.160)**: default "Built-in GLSL" pure-shader pipeline — low power, zero extra libs; plus an optional "GPUPixel" engine (prebuilt AAR with Mars-Face landmarks, on-device GPU post-processing). Its coverage spans the whole video frame in 2D and the entire physical screen in VR / panoramic, with an independent "enable in VR" toggle

## 🎨 3D LUT 电影调色 / LUT Color Grading
> ✅ **v1.0.117 起已生效**（修复 `.cube` 关键字解析后链路打通）
> Working since v1.0.117 — earlier versions parsed `LUT_3D_SIZE` incorrectly and failed silently.
- 36 款内置 LUT，按用途分为两组平铺展示（纯文字网格，一眼看全）
  - **人像美颜**（24 款）：暖调人像、明亮日光、通透清新、纯色胶片、柔杏肤、淡雅胶片、柯达波特拉 400/160/800、柯达爱泰 100VS、柯达丽彩 400、柯达克罗姆 64、琉璃通透、鎏金暖肤、苍翠清透、澄明明亮、轻纱柔雾、骑楼活力、千禧暖金、放映柔光、夜灯暖光、银盐高级灰、林荫清爽、暮潮冷调
  - **风格滤镜**（12 款）：经典青橙、电影暗调、柔和胶片、日系清新、暖阳日落、冷蓝夜色、复古胶片、赛博朋克、黑白电影、强烈青橙、柔和青绿、高对比
  - 36 bundled LUTs in two tiled groups — Portrait beauty (24): Warm Portrait, Bright Daylight, Clear & Open, Pure Hue Film, Soft Almond Skin, Subtle Film, Kodak Portra 400/160/800, Ektachrome 100VS, Elite Color 400, Kodachrome 64, Glaze, Gilt, Viride, Clear, Voile, Arcade, Tinsel, Splice, Sodium, Argent, Canopy, Dusk Tide; Style filters (12): Classic Teal-Orange, Cinematic Dark, Soft Film, JP Fresh, Warm Sunset, Cool Blue Night, Retro Film, Cyberpunk, B&W, Strong Teal-Orange, Soft Teal-Green, High Contrast
- 手机自选 LUT：可导入任意 `.cube` 文件（系统会弹出文件选择器）
  - Import any custom `.cube` file from your phone
- 强度调节：0–100% 混合强度滑杆，实时预览
  - Intensity slider (0–100%) with live preview
- 全部 LUT 由项目自研脚本（numpy）程序化生成，无第三方版权
  - All LUTs are self-generated via numpy scripts (no third-party copyright)

## 🗣️ 字幕与语音转写 / Subtitles & ASR
- 离线语音识别：**Dolphin**（sherpa-onnx，CPU int8，**已内置进 APK，开箱即用**）
  - **40 种东方语言 + 22 种中文方言**，自带语种识别（LID），无需指定语言
  - 中文 WER 9.2%（Whisper large-v3 为 27.9%）；RTF 0.094（官方四个 Dolphin 变体中最快）
  - **模型已内置**（约 229MB 打进 APK），开箱即用、无需联网下载
  - Offline ASR: **Dolphin bundled in the APK** — 40 Eastern languages + 22 Chinese dialects with auto language detection, no download needed
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
- **字幕导出**：一键导出 SRT（直接由内存字幕缓存生成）；启用翻译时文件名**带语言后缀**（如 `影片_20260920-110000_zh.srt`，双语再加 `_bi`），**内容同步使用已有译文**（仅译文＝译文；双语＝原文+译文），同一部片子的多语言字幕互不覆盖；尚未翻译的条目按原文写入，并在提示里告知条数
  - One-tap SRT export from the in-memory subtitle cache; when translation is on the filename carries a **language suffix** (e.g. `movie_20260920-110000_zh.srt`, plus `_bi` for bilingual) and the **content uses the cached translations** (target-only = translation; bilingual = source + translation); entries not translated yet fall back to the source text and the toast tells you how many
- **去除标点**（v2.0.154，默认开启，可关）：**屏幕显示与导出的 SRT 都不带标点**。只作用于「显示/导出」层，内部原文保留标点 → **字幕断句与翻译质量不受影响**；英文句点只去句尾，`3.14`、`U.S.`、`192.168.1.1` 里的点保留
  - Optional toggle (on by default) — both on-screen subtitles and exported SRT omit punctuation. Applied only at the render/export layer, so the source text keeps its punctuation and **segmentation / translation quality are unaffected**; English dots are removed only at sentence end (`3.14`, `U.S.`, `192.168.1.1` stay intact)
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
| 自动 · 中 · 日 · 韩 · 粤 · 英语 · 22 种中文方言 · 40 种东方语言<br>auto / zh / ja / ko / yue / en / 22 Chinese dialects / 40 Eastern languages | **Dolphin**（**已内置 / bundled**） | 随 APK（99MB）<br>in APK (99MB) | 无需下载 / none |
| 越南语 / Vietnamese | `sherpa-onnx-zipformer-vi-int8` | ≈74MB | hf-mirror |
| 俄·法·德·西·白俄·克·意·波·乌<br>ru / fr / de / es / be / hr / it / pl / uk | `NeMo FastConformer 20k int8`<br>（**一个模型覆盖 11 语 / one model, 11 languages**） | 整包 102MB → 解压 ≈132MB<br>pkg 102MB → ≈132MB extracted | GitHub releases |
| 泰语 / Thai | `sherpa-onnx-zipformer-thai-2024-06-20` | 整包 664MB → 解压 ≈154MB<br>pkg 664MB → ≈154MB extracted | GitHub releases |

## 🌐 字幕在线翻译 / Online Translation
- **10 种引擎**：必应翻译（免费） / **Google 免密** / **MyMemory**（免费） / **LibreTranslate**（免费，可自建） / DeepSeek / 通义千问 / 智谱 GLM / MiniMax / OpenAI GPT / 自定义（OpenAI 兼容）
  - 10 engines: Bing (free) / Google keyless / MyMemory (free) / LibreTranslate (free, self-hostable) / DeepSeek / Qwen / Zhipu GLM / MiniMax / OpenAI GPT / Custom
- 显示模式：**双语（原文+译文）** 与 **仅译文** 一键切换，选择**已持久化**
  - Display modes: bilingual / translation-only, both persisted
- MyMemory：匿名额度 **5000 字符/天**，程序内置**串行限速 + 错误文案识别 + 配额冷却 10 分钟 + 超长句跳过**，避免触发其限流
  - MyMemory: built-in pacing, error-text detection and 10-min cooldown to respect its quota limits
- LibreTranslate：标准 `/translate` 协议，设置面板可填 Base URL 指向**私有实例**
  - LibreTranslate follows the standard protocol; Base URL configurable for a private instance
- **Google 免密端点**（clients5）：`GET https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=…&q=…`，**无需 API Key、无需登录**；返回格式随 `sl` 变化（`sl=auto` → `[["译文","en"]]`，显式指定源语言 → `["译文"]`），解析统一取「每项里的第一个字符串」，两种都兼容；Base URL 可改为镜像
  - **Google keyless endpoint** (clients5): no API key or sign-in required; the response shape depends on `sl` (`sl=auto` → `[["text","en"]]`, explicit `sl` → `["text"]`), so the parser takes the first string of each item; Base URL is configurable for mirrors
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

## 📁 局域网与远程播放 / LAN & Remote Playback
- SMB 协议（jcifs-ng）：浏览局域网共享、直连播放 NAS/PC 视频
  - SMB (jcifs-ng) browsing & direct playback from NAS/PC
- **远程视频 seek 优化**：HTTP 分支包 `CacheDataSource` + `SimpleCache`（512MB LRU），
  即使对方不支持 Range 也能边下边播、正常拖动；moov 在尾部的 MP4 也能先读 moov
  - Remote seek: HTTP path wrapped with a 512MB LRU cache, enabling seek even without Range support
- **远程视频也能生成实时字幕/转写**：`AudioTee` 对 `http(s)://` 走框架 MediaExtractor、对 `smb://` 用 jcifs 随机访问封装 `MediaDataSource`
  - Realtime subtitles work for http and SMB sources too

---
