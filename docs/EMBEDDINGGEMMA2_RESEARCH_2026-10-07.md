# EmbeddingGemma 2 端侧 AI 能力调研报告

> **调研对象**：`https://huggingface.co/google/embeddinggemma-2`
> **调研缘起**：用户提出「做一套方案兼容 embeddinggemma-2」，目标为**端侧字幕翻译 + 端侧 AI 弹幕生成**
> **报告日期**：2026-10-07
> **本项目版本**：v2.4.7（versionCode 259）
> **报告性质**：**只读调研**，本报告不涉及任何代码改动，不产生依赖变更

---

## 一、结论（先说答案）

| 维度 | 结论 |
|---|---|
| **能不能用来做翻译/生成** | 🔴 **不能**。EmbeddingGemma 2 是**嵌入模型**，只把输入编码成向量，**不产出任何文字**。 |
| **官方原始口径** | Google 模型卡 FAQ 原文：*「Can it generate text like a chatbot? **No.** An embedding model only converts input into vectors. If you want generation as well, pair it with a model such as **Gemma 4**.」* |
| **与用户目标的差距** | 用户要的是「端侧翻译 / 端侧弹幕生成」→ 属于**生成式**任务 → **模型选型方向错了**，应当选 **Gemma 3n / LiteRT-LM 系列**，而非本模型。 |
| **本模型真正能做的** | **语义检索 / 去重 / 聚类 / 跨模态匹配**——即「选哪条、档哪条、复用哪条」，属 RAG 检索层。 |
| **对本项目是否有价值** | 🟡 **有，但属于「增强」而非「实现」**。可作为弹幕语义去重、翻译缓存语义命中的底层能力；**不能替代**现有 `DanmuVisionClient`（生成弹幕）与 `SubtitleTranslator`（产出译文）。 |
| **建议** | **不建议按原目标接入**。若要端侧翻译/生成，另立「Gemma 3n 端侧生成」专题评估（体量 2–4GB，内存门槛高）；若只要检索能力，本模型文本档（INT8 约 300MB）可行。 |

**一句话总结**：
> EmbeddingGemma 2 是**检索层组件**，不是**生成层组件**。它能告诉你「这两句话意思一样」，但不能告诉你「这句话翻译成中文是什么」。用它做翻译/生成属于**选型错位**，不是调参能弥补的问题。

---

## 二、目标与前提的事实核查

### 2.1 用户原始需求链

| 轮次 | 用户表述 | 性质 |
|---|---|---|
| 1 | 「做一套方案兼容 https://huggingface.co/google/embeddinggemma-2」 | 指定了**模型**，未指定**任务** |
| 2 | 用途：**做字幕翻译和 AI 弹幕生成** | 明确了**任务**（生成式） |
| 3 | 环境：**Android 端侧** | 明确了**部署形态** |
| 4 | 体积：**按需下载，不进 APK** | 明确了**分发策略** |
| 5 | 补充：**「我其实想要端侧翻译/生成」** | ⚠️ **任务性质被再次确认为生成式** |
| 6 | 最终：「**不做了写成调研报告**」 | 本报告 |

**关键矛盾**：第 2、5 条明确要求**生成式能力**，而第 1 条指定的模型**不具备生成能力**。该矛盾无法在实现层消解，只能通过**更换模型**或**降级目标为「检索增强」**来解决。

### 2.2 本项目现状（已核对，作为可行性基线）

| 项 | 现状 | 出处 |
|---|---|---|
| minSdk | **24** | `app/build.gradle.kts:17` |
| ABI | 仅 `arm64-v8a` + `armeabi-v7a`（**无 x86**） | `app/build.gradle.kts:147` |
| 已有 native ONNX Runtime | ✅ **`libonnxruntime.so` arm64 20MB**（随 `sherpa-onnx-1.13.6.aar` 进包） | AAR 实测 |
| 下载通道 | ✅ 已有 `downloadFileWithResume()`：OkHttp + **Range 断点续传** + 进度回调 + 重试 | `SherpaAsrManager.kt:440` |
| 下载源现状 | `hf-mirror.com` 按文件下载 **真机会 401** → 已改用 GitHub Releases 整包兜底 | `translation-cache-asr.md` |
| 字幕翻译 | `SubtitleTranslator`（149 行起）：MyMemory / Google / Bing / LibreTranslate / OpenAI 兼容 LLM，**全部云端** | `SubtitleTranslator.kt` |
| 翻译缓存 | **按语言分文件** `cache_<lang>.tsv`，key 走 `makeCacheKey()`（**字符串精确匹配**） | `SubtitleTranslator.kt:676` |
| AI 弹幕 | `DanmuVisionClient`（48 行起）：OpenAI 兼容视觉模型，**云端** | `DanmuVisionClient.kt` |
| 弹幕去重现状 | v2.4.7 已有**字符串级**去重（`DanmuEngine`） | `ai-danmu-import.md` |
| 可用推理框架 | sherpa-onnx（含 onnxruntime）、MediaPipe tasks-vision 0.10.14 | `libs.versions.toml:92` |
| **无** | 无 LiteRT / LiteRT-LM / TFLite / sentencepiece 依赖 | `build.gradle.kts` 实测 |

> **基线结论**：本项目**已具备** ONNX 推理能力与成熟的大文件下载通道，但**完全不具备**生成式端侧推理框架（LiteRT-LM）与 SentencePiece tokenizer。

---

## 三、EmbeddingGemma 2 技术档案（已核实）

### 3.1 基本信息

| 项 | 值 |
|---|---|
| 发布方 / 时间 | Google DeepMind / **2026-10-06** |
| 许可证 | **Apache 2.0**，**非 gated**（无需登录或接受条款即可下载） |
| HF 名称 | `google/embeddinggemma-2` |
| 总参数 | **740M**（模块化，按需加载） |
| 主干 | **Gemma 4**（共享其文本 tokenizer 与音频编码器） |
| 输出维度 | **768**，支持 **MRL 截断**至 512 / 256 / 128 |
| 上下文 | **8,192 token**（v1 的 4 倍） |
| 语言 | **100+**（训练数据覆盖 140+ 语言）+ 代码 |
| 训练数据截止 | 2025 年 1 月 |

### 3.2 模块化参数构成

| 模块 | 参数 | 加载后档位 | 说明 |
|---|---|---|---|
| 文本主干 | 270M（130M transformer + 140M embedder） | **270M** | 纯文本/代码 |
| 视觉编码器 | 170M | 440M | 叠加图像检索 |
| 音频编码器 | 300M | 570M | 叠加音频检索 |
| 全部 | 740M | 740M | 全模态 |

**token 成本**：文本 1/subword · 图像 280/张 · 视频 140/帧 · 音频 25/秒。
8K 上下文 ≈ **29 张图 / 58 视频帧 / 5.5 分钟音频**。

### 3.3 基准成绩

| 基准 | EmbeddingGemma 2 | EmbeddingGemma 1 |
|---|---|---|
| MTEB multilingual v2 | **61.36** | 61.15 |
| MTEB code v1 | **78.68** | 68.76（**+9.92**） |
| MIEB lite（图像） | 64.64 | — |
| MMEB v2（视频） | 50.67 | — |
| MSEB（音频检索） | 69.54 | — |

**判读**：**纯文本质量与 v1 基本持平**（+0.21），提升集中在**代码检索（+14%）**与**新模态**。若只为文本检索而升级，收益有限。

### 3.4 🔴 三条硬性技术约束

| # | 约束 | 后果 |
|---|---|---|
| 1 | **不支持 float16** | 会产生 NaN 或静默降级 → 必须用 **bfloat16 / float32 / q8 / q4** |
| 2 | **任务指令前缀必需** | 文本任务不加前缀 → 嵌入质量**次优**。非对称检索需：query `task: search result \| query: ` / doc `title: none \| text: ` |
| 3 | **`sentence_embedding` 不可复现** | 它是**任务感知投影头**的产出，**不能**用 `last_hidden_state` 做 mean/cls/last pooling 复现（余弦 ≈ 0）→ 必须直接消费预池化输出 |
| 4 | **MRL 截断后必须重新 L2 归一化** | 否则余弦相似度失真 |
| 5 | **v1 与 v2 向量不互通** | 换模型必须**重建整个索引** |

### 3.5 体积清单（端侧实测口径）

| 形态 | 体积 | 备注 |
|---|---|---|
| 文本档 INT8 | **~300MB** | cookieshake 社区版 `model_int8.onnx`（cosine 漂移 ~0.016） |
| 文本档 FP32 | ~1.2GB（ONNX）/ ~1.3GB（bundle） | 精度最高 |
| GGUF 文本（Q8_0） | 310MB | llama.cpp 路线 |
| GGUF 文本（BF16） | 558MB | |
| 全模态 INT8 | ~740MB | 含视觉 + 音频 |
| **tokenizer.json** | ⚠️ **~20MB** | Gemma 系 SentencePiece **256k 词表**（BERT 仅 700KB） |
| 实测活跃内存 | 文本档 **~191MB** / 全模态 **~567MB** | Pixel 11 Pro |
| 向量存储 | 100 万条 768 维 bf16 ≈ 1.5GB；**128 维 ≈ 250MB**（6× 节省） | |

---

## 四、端侧推理路径对比

### 4.1 三条可行路线

| 路线 | 运行时 | APK 增量 | 与现有资产冲突 | 适配工作量 | 评价 |
|---|---|---|---|---|---|
| **A. 复用现有 ONNX Runtime** | 项目内已有 `libonnxruntime.so`(arm64 20MB) | **≈0** | 🔴 **同名 `.so` 冲突**，需处理 | 高（需自研 JNI 封装 + tokenizer） | 体积最优，工程量最大 |
| **B. 引入 LiteRT** | `com.google.ai.edge.litert` | ~5–10MB | ✅ 不冲突 | 中 | **Google 官方端侧路径**，推荐 |
| **C. Llama.cpp / GGUF** | 需自建 NDK 工程 | ~5MB | ✅ 不冲突 | 高 | 与项目现有 NDK 约束（可选开关）耦合 |

> **路线 A 的冲突细节**：`sherpa-onnx-1.13.6.aar` 已把 `libonnxruntime.so` 打进包内（arm64 20MB / armv7a 14MB / x86 24MB / x86_64 23MB）。若再引入官方 `onnxruntime-android`，两者**同名同路径** → Gradle `jniLibs` 会报重复（或需 `pickFirst`，风险由运行时承担：**sherpa 与官方发行版的 ORT 版本可能不一致，静默崩溃**）。项目 `packaging.jniLibs` 当前**未配置任何 `pickFirst`**。
>
> **更稳的做法**：让 sherpa-onnx AAR 内的 ORT 对外暴露 C++ API 供自研 JNI 复用（需确认该 AAR 是否导出了 ORT 头文件与符号），或干脆走路线 B。

### 4.2 社区已有资源

| 资源 | 内容 |
|---|---|
| `onnx-community/embeddinggemma-300m-ONNX` | 2 输入（`input_ids` + `attention_mask`，**无 `token_type_ids`**）；输出 `last_hidden_state` 3D `[batch,seq,768]` + `sentence_embedding` 2D `[batch,768]` |
| `cookieshake/embeddinggemma-300m-onnx` | `model.onnx`（fp32 ~1.2GB）+ `model_int8.onnx`（INT8 ~310MB） |
| `litert-community/embeddinggemma-2-740m-litert-lm` | **Google 官方端侧优化版本**（LiteRT 路线首选） |
| `ggml-org/embeddinggemma-2-GGUF` / `unsloth/embeddinggemma-2-GGUF` | llama.cpp 路线 |
| Ollama tags | `:270m`(378MB) / `:440m`(714MB) / `:570m`(990MB) / `:740m`(1.3GB) |

> ⚠️ **INT8 与 FP32 向量不可混用同一索引**（余弦空间不同）。

---

## 五、若降级为「检索增强」——两个可用场景

### 5.1 场景 A：弹幕语义去重（最匹配本模型）

**现状**：v2.4.7 `DanmuEngine` 已有**字符串级**去重。AI 生成弹幕时，跨批次/跨导入仍会出现**语义重复但字面不同**的情况（如「笑死我了」/「哈哈哈哈哈」/「太搞笑了吧」）。

**本模型可做**：把每条弹幕编码为 768 维（可截断至 **128 维**）向量，与最近 N 条已有弹幕做余弦比较，超过阈值即丢弃。

| 项 | 值 |
|---|---|
| 模型档位 | 仅文本 **270M**（INT8 ~300MB） |
| 向量维度 | **128**（弹幕短、语义浅，128 维足够） |
| 内存占用 | 向量缓存 1 万条 × 128 维 ≈ 5MB |
| 推理频率 | 每批弹幕一次（低频，非逐帧） |
| 收益 | 明显降低「复读机」观感 |
| 代价 | 需引入 LiteRT + SentencePiece tokenizer（+20MB tokenizer 文件） |

### 5.2 场景 B：翻译缓存语义命中

**现状**：`SubtitleTranslator` 缓存 key 走 `makeCacheKey()`，**纯字符串精确匹配**。字幕里「你好」与「你好！」「你 好」等细微差异会导致**重复调用云端 API**，白耗 MyMemory 的 5000 字/天额度。

**本模型可做**：在精确匹配 miss 后，加一层语义近邻查找，命中则复用已有译文。

| 项 | 值 |
|---|---|
| ⚠️ 风险 | **翻译对语义近似极为敏感**（「我要走了」vs「我要走了吗」），语义命中**可能返回错误译文** |
| 缓解 | 阈值必须**显著高于**去重场景；仅对「规范化后不同的纯标点/空白差异」启用 |
| 评价 | 🟡 **收益有限、风险偏高**，**优先级低于场景 A** |

### 5.3 场景 C（不推荐）：跨模态相关性筛选

需额外下载 **170M 视觉编码器**（总 440M），用于判断「弹幕是否真在回应画面」。但项目现有 `DanmuVisionClient` **已用云端视觉模型直接理解画面**，此层属**重复建设**。

---

## 六、若坚持「端侧翻译/生成」——正确的模型方向

### 6.1 模型对比

| 模型 | 类型 | 端侧可生成 | 体量 | 内存门槛 | 适配本项目 |
|---|---|---|---|---|---|
| **EmbeddingGemma 2** | 嵌入 | ❌ | 300MB–740MB | ~200–570MB | 仅检索层 |
| **Gemma 3n E2B** | 生成（多模态） | ✅ | ~2GB（int4） | ~3GB | 可能可行 |
| **Gemma 3n E4B** | 生成（多模态） | ✅ | ~3GB+ | ⚠️ **4–5GB** | VR 场景**风险高** |
| Gemma 3 270M | 生成（纯文本） | ✅ | 数百 MB | 低 | 可做翻译，质量有限 |

### 6.2 端侧生成的技术路径

| 项 | 内容 |
|---|---|
| 框架 | **LiteRT-LM**（`com.google.ai.edge:litert-lm:1.0.0`） |
| 模型格式 | `.litertlm` |
| HF 仓库 | `google/gemma-3n-E2B-it-litert-lm` |
| Kotlin API | `Engine.Builder(modelPath).setBackend(GPU).build()` → `createSession()` → `generateContentStream()` |
| 下载源 | `https://huggingface.co/litert-community/gemma-3n-2b-it/resolve/main/model.litertlm` |
| 后端 | GPU / CPU；参考实现可用 GPU 加速（`Engine.Backend.GPU`） |
| TTFT 参考 | GPU 后端 2B 模型 **<100ms** |

### 6.3 🔴 与本项目的硬冲突

| # | 冲突 | 严重度 |
|---|---|---|
| 1 | **VR 场景内存竞争**：E4B 需 4–5GB RAM，而 VR 播放本身要解码 4K/8K 视频 + 立体渲染管线，**极易 OOM 或被系统杀后台** | 🔴 高 |
| 2 | **minSdk 冲突**：多数 LiteRT-LM 参考实现要求 **API 26+**，本项目 **minSdk = 24** | 🟡 中 |
| 3 | **ABI 限制**：仅支持 ARM（**x86_64 模拟器不支持**）→ 本项目 abiFilters 恰为 arm64+v7a，**吻合** | ✅ 无 |
| 4 | **首次加载耗时**：大模型 on-device 编译可能 **>1 分钟**（AOT 编译可缓解） | 🟡 中 |
| 5 | **推理延迟 vs 播放**：端侧生成逐 token 串行，**无法像云端那样离线批处理完毕再播** | 🟡 中 |

### 6.4 可行性判读

> **端侧翻译**：若降级为 **Gemma 3 270M 纯文本档**（体量小、只做文本翻译），**技术可行**，但翻译质量明显低于现有云端方案，且需与 MyMemory/Google 做质量对比。
>
> **端侧弹幕生成**：若要求「同时看画面 + 出台词」，需**视觉理解能力** → 只有 E2B/E4B 够用 → **内存门槛与 VR 播放直接冲突** → **不推荐**。

---

## 七、落地成本估算（供决策参考）

### 7.1 若做「检索增强（场景 A）」

| 工作项 | 内容 |
|---|---|
| 依赖 | 新增 LiteRT（~5–10MB APK 增量） |
| 模型分发 | 复用 `SherpaAsrManager.downloadFileWithResume()`；⚠️ **hf-mirror 401 风险需真机先验**，兜底走 GitHub Releases |
| 新增文件 | tokenizer（~20MB）+ 模型（~300MB）+ 向量缓存 |
| 新增组件 | EmbeddingEngine（推理封装）、EmbeddingStore（向量存储 + 余弦检索）、Tokenizer 封装 |
| 集成点 | `DanmuEngine` 去重路径（1 处）、`DanmuSettingsPanel` 开关（1 处） |
| 持久化 | ⚠️ 必须**三处同步**（key 列表 / `put*` 块 / `else` 的 `remove`） |
| i18n | 新增文案需 **5 语同步** |
| 风险 | 首启下载 300MB；tokenizer 20MB 易被忽略；INT8/FP32 索引混用会静默降质 |

### 7.2 若做「端侧生成」

| 工作项 | 内容 |
|---|---|
| 体量 | **2–4GB 模型下载**（用户等待成本极高） |
| 内存 | E4B **4–5GB**，与 VR 播放冲突 |
| 质量 | 端侧小模型翻译质量**大概率不及**现有云端方案 |
| 结论 | **投入产出比低**，不建议在当前阶段推进 |

---

## 八、最终建议

| 优先级 | 建议 | 理由 |
|---|---|---|
| ⭐ **首选** | **维持现状，不接入 EmbeddingGemma 2** | 用户真实目标是「端侧翻译/生成」，本模型**不具备该能力**；强行接入只能做检索增强，收益与 300MB+ 下载成本不成正比 |
| ⭐⭐ | 若确实要**端侧能力**，先立「**Gemma 3 270M 端侧翻译**」专题评估 | 体量可控、任务匹配，可先做质量对比再定 |
| ⭐⭐⭐ | 若确实要**减少云端额度消耗**（MyMemory 5000 字/天），优先做**翻译缓存规范化**（纯字符串层，零下载） | 大部分「重复调用」其实源于标点/空白差异，**规范化即可解决**，无需向量检索 |
| ❌ 不建议 | 端侧全模态 EmbeddingGemma 2（740M） | 与本项目需求不匹配 + 内存/下载成本高 |
| ❌ 不建议 | 端侧 Gemma 3n E4B 生成弹幕 | 内存与 VR 播放直接冲突 |

### 备选：零成本的缓存命中率改进

在引入任何模型之前，**先量化现状**：统计 `cache_<lang>.tsv` 中「规范化后相同、但原始字符串不同」的条目占比。若占比可观，**直接在 `makeCacheKey()` 前加一层规范化**（去全角/半角统一、去多余空白、统一标点）即可吃到绝大部分收益——**无需模型、无需下载、无需新增依赖**。

---

## 九、参考资料

| 来源 | 链接 |
|---|---|
| Google 模型卡（官方 FAQ） | https://ai.google.dev/gemma/docs/embeddinggemma/model_card_2 |
| Hugging Face 模型页 | https://huggingface.co/google/embeddinggemma-2 |
| LiteRT-LM 端侧框架 | `com.google.ai.edge:litert-lm:1.0.0` |
| LiteRT NPU 加速（MediaTek 合作） | https://developers.googleblog.com/mediatek-npu-and-litert-powering-the-next-generation-of-on-device-ai/ |
| 社区 ONNX 版本 | `onnx-community/embeddinggemma-300m-ONNX` / `cookieshake/embeddinggemma-300m-onnx` |
| 官方端侧优化版 | `litert-community/embeddinggemma-2-740m-litert-lm` |

---

## 十、核查过程中的事实更正记录

调研中发现并更正了几条流传较广的错误信息，记录备查：

| # | 错误说法 | 核实结论 |
|---|---|---|
| 1 | 主干架构是 **Gemma 3 / Gemma 3 2B** | ❌ 应为 **Gemma 4**（共享其文本 tokenizer 与音频编码器） |
| 2 | 输出 **3072** 维 | ❌ 官方口径为 **768 维**，MRL 可截断至 512/256/128 |
| 3 | 可用于生成/翻译 | ❌ 官方 FAQ 明确 **No**，需配对生成模型 |
| 4 | v2 显著优于 v1 | 🟡 仅**代码检索**显著提升（+9.92），**纯文本基本持平**（+0.21） |

---

*本报告为只读调研结论，未对仓库产生任何代码或依赖变更。*
