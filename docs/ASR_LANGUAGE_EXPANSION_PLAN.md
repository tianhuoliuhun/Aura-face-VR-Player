# 多语言 ASR 扩展方案评估 / ASR Language Expansion

> 评估日期：2026-10-01 · 对应代码：`AsrExtModels.kt` / `SherpaAsrManager.kt`
> 本文用于**决策**：列出当前支持的语言、可新增的候选、各方案的体积与改造量。

---

## 0. 实施状态（2026-10-02 更新，**以此节为准**）

> ⚠️ 下文正文写于 **2026-10-01 方案评估阶段**，保留当时的候选对比与决策过程（有史料价值）。
> **实际落地已与正文口径不同**，差异见下表：

| 项 | 本文档的设想 | 实际落地 |
|---|---|---|
| **Dolphin** | 100MB 整包，**按需下载** | ✅ 已接入，且**内置进 APK**（`assets/dolphin/` 的 ONNX，99MB），**开箱即用** |
| Dolphin 下载源 | GitHub releases 整包 | 改为 **ModelScope 按文件下载**（`csukuangfj/sherpa-onnx-dolphin-*`）；内置后该路径不再是必需 |
| **IndicConformer**（南亚 8 语）| 线 1，计划接入 | ⚠️ 代码保留但**未登记启用** —— 被 Dolphin 以 1/15 体积覆盖；日后某语言需专用模型可快速恢复 |
| **Parakeet v3**（欧洲 25 语）| 线 2，计划接入 | ✅ 已接入（仍走 GitHub 整包 487MB，**待搬 ModelScope**）|
| **SenseVoice**（原内置 5 语）| 保留为内置 | ❌ **已移除**（连 assets 一起，APK 因此 348MB→186MB）；原 5 语改由 Dolphin 承接 |
| 语言总数 | 预计 87 项 | ✅ 87 项（**65 种语言 + 22 种中文方言**）|
| 语言选择 UI | 未涉及 | 新增：**「按语言 / 按模型」双视角**、8 语区折叠（默认只展开常用）、**重复语言分开标**（英语1/2/3）|

**当前实际模型清单（截至 v2.1.209）**

| 模型 | 来源 | 体积 | 覆盖 |
|---|---|---|---|
| **Dolphin** | **内置 assets** | 随 APK（99MB）| 40 种东方语言 + 22 种中文方言，**自带语种识别**（`auto` 走它）|
| Parakeet TDT 0.6B v3 | 按需下载（GitHub 整包）| 487MB | 25 种欧洲语言 |
| FastConformer | 按需下载（GitHub 整包）| 102MB | 9 种欧洲语言 |
| zipformer-vi / thai | 按需下载 | 74MB / 664MB | 越南语 / 泰语 |

**遗留待办**：把 FastConformer / 泰语 / Parakeet v3 也搬到 ModelScope 按文件下载（README 待办 B1）。

---

## 1. 当前支持的语言（共 17 种）

| 来源 | 语言 | 体积 | 下载源 |
|---|---|---|---|
| **SenseVoice（内置）** | 中 `zh` · 英 `en` · 日 `ja` · 韩 `ko` · 粤 `yue` | 随 APK（229MB） | 无需下载 |
| **zipformer-vi** | 越南语 `vi` | 74MB（按文件） | hf-mirror |
| **NeMo FastConformer 20k** | 俄 `ru` · 法 `fr` · 德 `de` · 西 `es` · 白俄 `be` · 克 `hr` · 意 `it` · 波 `pl` · 乌 `uk` | 整包 102MB → 解压 132MB | GitHub releases |
| **zipformer-thai** | 泰语 `th` | 整包 664MB → 解压 154MB | GitHub releases |

**合计 5 + 1 + 9 + 1 + （en 由 SenseVoice 覆盖）= 17 种**

> 注：FastConformer 的包名里含 `en`，但英语已由内置 SenseVoice 覆盖，**未重复登记**。

---

## 2. 候选方案

### 方案 A — Parakeet TDT 0.6B v3（+16 种欧洲语言）

**新增什么**：在现有 FastConformer 之外，补上欧洲其余主要语言 ——
`bg` 保加利亚 · `cs` 捷克 · `da` 丹麦 · `nl` 荷兰 · `et` 爱沙尼亚 · `fi` 芬兰 ·
`el` 希腊 · `hu` 匈牙利 · `lv` 拉脱维亚 · `lt` 立陶宛 · `mt` 马耳他 ·
`pt` 葡萄牙 · `ro` 罗马尼亚 · `sk` 斯洛伐克 · `sl` 斯洛文尼亚 · `sv` 瑞典

| 项 | 值 |
|---|---|
| 覆盖 | **25 种欧洲语言**（含现有 FastConformer 的 10 种） |
| 体积 | encoder 622MB + decoder 12MB + joiner 6.1MB + tokens 92KB ≈ **640MB** |
| modelType | `nemo_transducer`（**与现有 FastConformer 同门**） |
| 下载源 | GitHub releases 的 tar.bz2 整包 |
| 精度 | WER **6.34%**（优于 Whisper large-v3 的 7.44%） |
| 速度 | 快（FastConformer-TDT 架构，比 Whisper 快约一个数量级） |

**优点**
- 一次性 +16 种，且都是欧洲常用语言
- 精度目前同体积下最好；TDT 架构**静音段不会幻觉出文字**（Whisper 的通病）
- 与现有 FastConformer **同一个 modelType 与后端**，代码改造量最小

**缺点**
- ⚠️ **640MB**（是 FastConformer 102MB 的 6.3 倍），手机上整包下载体验差
- ⚠️ 与 FastConformer 有 **10 种重叠** → 两个包并存时体积严重浪费，需决定「替代还是共存」
- ⚠️ **不支持**中文（已有）、日语（已有）、韩语（已有），也不支持阿拉伯语/印地语/土耳其语

---

### 方案 B — Whisper 多语言（**99 种语言**）

**新增什么**：一次性获得 **99 种语言**的覆盖能力，重点是现有体系完全没有的语言 ——
`ar` 阿拉伯 · `hi` 印地 · `tr` 土耳其 · `id` 印尼 · `ms` 马来 · `fa` 波斯 ·
`he` 希伯来 · `ta` 泰米尔 · `ur` 乌尔都 · `sw` 斯瓦希里 …… 等

| 型号 | 体积（int8） | 定位 |
|---|---|---|
| **tiny** | encoder 12MB + decoder 86MB + tokens 0.8MB ≈ **99MB** | 最快、体积最小，精度最弱 |
| **base** | 约 **200MB** | 体积与精度折中 |
| **small** | 约 **500MB** | 精度更好，但已接近 Parakeet v3 |

| 项 | 值 |
|---|---|
| modelType | `whisper`（**与 transducer 不同**，需新增识别分支） |
| 文件命名 | `tiny-encoder.int8.onnx` / `tiny-decoder.int8.onnx` / `tiny-tokens.txt` |
| 关键约束 | **多语言 Whisper 必须显式指定 `language`** —— 不能自动检测 |
| 下载源 | hf-mirror（有按文件源）或 GitHub releases 整包 |

**优点**
- ✅ **一份模型覆盖 99 种语言**，只下载一次 → 长尾语言的唯一可行方案
- ✅ tiny 仅 **99MB**，比 Parakeet v3 小 6.5 倍
- ✅ 补上了当前完全空白的语区（阿拉伯语系、南亚语系、东南亚非泰语）

**缺点**
- ⚠️ **需显式指定语言** → 每种语言要在注册表里**单独登记一条**（同一个模型目录、不同 `language` 值）
- ⚠️ **需要新增 `whisper` 识别分支**（本项目曾在 v2.0.149 加过、v2.0.152 撤回，需重新引入）
- ⚠️ tiny 精度明显弱于专用模型（尤其中文/日语这类）。**但用于长尾语言时，有总比没有好**
- ⚠️ Whisper 家族在**静音段可能幻觉出文字**，需配 VAD（本项目已有 Silero VAD）

---

### 方案 C — 混合（推荐）

| 语区 | 用什么 | 体积 |
|---|---|---|
| 中/英/日/韩/粤 | 内置 SenseVoice（不变） | 0 |
| 越南语 | zipformer-vi（不变） | 74MB |
| 欧洲常见 9 语 | FastConformer（不变） | 102MB |
| 泰语 | zipformer-thai（不变） | 664MB 整包 |
| **欧洲其余 16 语 + 长尾语言** | **新增 Whisper tiny** | **+99MB** |

**优点**：以 **99MB** 的代价同时拿到「欧洲剩余语言」**和**「99 语长尾覆盖」，
不必上 640MB 的 Parakeet v3。

**代价**：欧洲长尾语言（如荷兰语、瑞典语）用 Whisper tiny 的精度，**低于** Parakeet v3。

**若追求欧洲语言精度**：可再叠加方案 A（+640MB），但那时总体积会到 ~1GB 级别。

---

### 方案 D — AI4Bharat IndicConformer（南亚 22 语，**非 Whisper 路线**）

> 若目标是**南亚语言**且不接受 Whisper，这是唯一可行路线。

**来源**：HuggingFace `parismitaglobalsolutions/indicconformer-sherpa-onnx`
（社区整理，源模型为 AI4Bharat IndicConformer，**MIT 许可**）
作者声明已在生产 App（Android 端 36 语言字幕）中使用，且**专为 sherpa-onnx Kotlin API 导出**。

| 项 | 值 |
|---|---|
| 覆盖 | **22 种印度官方语言**：`as` 阿萨姆 · **`bn` 孟加拉** · `brx` 博多 · `doi` 多格拉 · `gu` 古吉拉特 · **`hi` 印地** · `kn` 卡纳达 · `ks` 克什米尔 · `kok` 孔卡尼 · `mai` 迈蒂利 · `ml` 马拉雅拉姆 · `mni` 曼尼普尔 · `mr` 马拉地 · `ne` 尼泊尔 · `or` 奥里亚 · `pa` 旁遮普 · `sa` 梵语 · `sat` 桑塔利 · `sd` 信德 · **`ta` 泰米尔** · `te` 泰卢固 · **`ur` 乌尔都** |
| 体积 | **每种语言约 188MB**（`model.int8.onnx`）+ **共享 `tokens.txt` 67KB** |
| modelType | **`ctc`**（`OfflineNemoEncDecCtcModelConfig`）—— **与现有 transducer 不同** |
| 下载源 | **hf-mirror 实测 HTTP 206（支持 Range 续传）**，见下方实测记录 |

**✅ 2026-10-01 实测（hf-mirror，带 Range 请求）**
```
tokens.txt             HTTP 206  Content-Range: bytes 0-99/67605
hi/model.int8.onnx     HTTP 206  Content-Range: bytes 0-99/197595593   (188MB)
ur/model.int8.onnx     HTTP 206  Content-Range: bytes 0-99/197585089   (188MB)
ta/model.int8.onnx     HTTP 206  Content-Range: bytes 0-99/197595513   (188MB)
```
→ **下载源可靠**（对比：泰语 zipformer 的 hf-mirror 按文件源是 401，见 `AsrExtModels.kt` 注释）

**优点**
- ✅ **不走 Whisper** 就能拿到南亚主流语言
- ✅ MIT 许可、专为移动端导出、作者已生产验证
- ✅ 22 语**共享一份 tokens.txt**（多语言时只多下 188MB/语言）
- ✅ **CTC 架构解码快**，比 Whisper 轻

**缺点**
- ⚠️ **每种语言 188MB** —— 是 Whisper tiny（99MB/99 语）的 1.9 倍**且只覆盖 1 种语言**。
  **4 种南亚语言 = 752MB**，这是走非 Whisper 路线的核心代价
- ⚠️ 需**新增 `ctc` 识别分支**（现有只有 transducer / nemo_transducer）
- ⚠️ **第三方社区仓库**（非 k2-fsa 官方），长期可用性需要留意；若下游下架需自行重新导出
- ⚠️ 只覆盖印度语言，**不含**阿拉伯语 / 土耳其语 / 波斯语（那些仍只有 Whisper）

---

### 方案 E — 🏆 Dolphin（40 东方语言 + 22 中文方言，**100MB**）

> **2026-10-01 追加**：这是调研后期才发现的方案，**性价比远超方案 A/B/C/D**，
> 建议用它替代方案 D 的大部分。

**来源**：清华电子系语音与音频技术实验室 × 海天瑞声（DataoceanAI）
→ k2-fsa 官方转换：`sherpa-onnx-dolphin-base-ctc-multi-lang-int8-2025-04-02`

| 项 | 值 |
|---|---|
| 覆盖 | **40 种东方语言** + **22 种中文方言**（含普通话） |
| 体积 | `model.int8.onnx` **99MB** + `tokens.txt` 493KB ≈ **100MB** |
| modelType | **`dolphin`**（CTC 单模型结构，`OfflineDolphinModelConfig`） |
| 速度 | **RTF 0.094**（官方四个 Dolphin 模型中最快） |
| 下载源 | GitHub releases 的 tar.bz2 整包 |
| 训练数据 | 21.2 万小时；WER 比 Whisper large-v3 低 **54.1%**（small 版对比） |
| 语言指定 | ⚠️ **无需指定** —— sherpa-onnx 侧只传 `--dolphin-model` + `--tokens`，模型自带语种识别（LID） |

**40 种语言**（✓ = 用户已选要加的）：
- 东亚：`zh` 中文 · `ja` 日语 · `ko` 韩语 · `mn` 蒙古语
- 东南亚：`th` 泰语 · `vi` 越南语 · `id` 印尼语 · `ms` 马来语 · `my` 缅甸语 · `km` 高棉语 · `lo` 老挝语 · `jv` 爪哇语 · `su` 巽他语 · `tl`/`fil` 菲律宾语
- 南亚：**✓`hi` 印地** · **✓`ur` 乌尔都** · **✓`bn` 孟加拉** · **✓`ta` 泰米尔** · **✓`te` 泰卢固** · **✓`gu` 古吉拉特** · **✓`mr` 马拉地** · `ne` 尼泊尔 · `or` 奥里亚 · `pa` 旁遮普 · `ks` 克什米尔 · `si` 僧伽罗
- 中东/中亚：`ar` 阿拉伯语 · `fa` 波斯语 · `uz` 乌兹别克 · `kk` 哈萨克 · `ky` 吉尔吉斯 · `tg` 塔吉克 · `az` 阿塞拜疆 · `ba` 巴什基尔 · `ug` 维吾尔 · `ps` 普什图 · `kab` 卡拜尔
- 其他：`ru` 俄语

**22 种中文方言**：普通话 · 台湾 · 吴语 · 四川话 · 山西话 · 安徽话 · 天津话 · 宁夏话 · 陕西话 · 河北话 · 山东话 · 广东话 · 上海话 · 湖北话 · 辽宁话 · 甘肃话 · 福建话 · 湖南话 · 河南话 · 云南话 · **闽南语** · 温州话

**优点**
- ✅ **100MB 覆盖 40 语言 + 22 方言** —— 单位体积的语言数是所有方案中最高的
- ✅ **速度最快**（RTF 0.094），比 Whisper 快约一个数量级
- ✅ **精度高**：同尺寸下 WER 比 Whisper 低 60%+；中文 WER 9.2% vs Whisper large-v3 的 27.9%
- ✅ **模型自带语种识别** —— 不需要用户选语言，也不需要像 Whisper 那样显式传 `language`
- ✅ 与现有架构天然契合：40 种语言各自登记一条、**共用同一个 `dirName`**
  （与 FastConformer 的「一包多语」写法完全同构）

**缺点**
- ⚠️ **不含欧洲语言**（除俄语）→ 欧洲仍需方案 A（Parakeet v3）或现有 FastConformer
- ⚠️ **不含 `kn` 卡纳达语**（用户所选的 8 种南亚语言中唯一的缺口）
- ⚠️ 下载源是 GitHub releases **整包**（无按文件源），与泰语同类

---

### 🔄 据此调整后的推荐组合（**体积从 2.14GB 降到约 828MB**）

| 语区 | 方案 | 体积 | 覆盖 |
|---|---|---|---|
| 中/英/日/韩/粤 | 内置 SenseVoice | 0 | 5 |
| **亚洲 40 语 + 中文方言 22** | **Dolphin base-int8** | **+100MB** | **62** |
| 越南语 | zipformer-vi | 74MB | 1 |
| 欧洲 25 语 | Parakeet v3（与 FastConformer 共存） | 640MB | 25 |
| 泰语 | zipformer-thai | 664MB 整包 | 1 |
| **`kn` 卡纳达语**（Dolphin 无） | IndicConformer | **+188MB** | 1 |
| **小计新增** | | **≈ 288MB**（+ Parakeet v3 的 640MB） | |

**对比原方案**：原计划南亚 8 语用 IndicConformer = 1.5GB；
改为「Dolphin + 单独补 `kn`」= **288MB**，**省下 1.2GB，且覆盖从 8 语扩到 62 语**。

---

## 3. 改造清单（按方案）

### 所有方案都需要的（基础改造）

| # | 改造点 | 文件 | 说明 |
|---|---|---|---|
| 1 | 注册表条目 | `AsrExtModels.kt` | 新增 `AsrExtModel(...)`：语言键、文件名（**逐模型写死**）、体积、下载源 |
| 2 | 5 语文案 | `res/values*/strings.xml` | 每种新语言加 `asr_lang_xx`（简/繁/英/日/韩 各一份） |
| 3 | 语言选择 UI | `SherpaAsrManager.kt` / `VRPlayerScreen.kt` | 语言 chips 列表（每行 4–5 个自动换行） |
| 4 | 下载与校验 | `SherpaAsrManager.kt` | 按文件下载（Range 续传 + 尺寸校验）或整包流式解压 |

### 方案 A 额外需要的

| # | 改造点 | 说明 |
|---|---|---|
| 5 | 整包下载 | 复用现有 `AsrExtArchive` 机制（泰语已在用），**无需新代码** |
| 6 | 与 FastConformer 的关系 | ⚠️ **需要决策**：替代（删掉 102MB 那套，欧洲语言改用 640MB 这套）还是共存 |

### 方案 B / C 额外需要的

| # | 改造点 | 说明 |
|---|---|---|
| 5 | **新增 `whisper` 识别分支** | `SherpaAsrManager` 里加 `OfflineWhisperModelConfig` + `language=<码>`；这是**主要工作量**（本项目曾实现过又撤回，需重新引入并验证） |
| 6 | 模型类型字段 | `AsrExtModel.modelType` 支持 `"whisper"`（当前只有 `transducer` / `nemo_transducer`） |
| 7 | 一条模型多条语言 | 同一 `dirName` 下按 `language` 参数区分 → 注册表要多语言共享同一份文件的写法 |
| 8 | 语言码映射 | Whisper 用 ISO-639-1（`ar` / `hi` / `nl` …），需与 UI 语言键对齐 |

### 方案 D 额外需要的

| # | 改造点 | 说明 |
|---|---|---|
| 5 | **新增 `ctc` 识别分支** | `SherpaAsrManager` 里加 `OfflineNemoEncDecCtcModelConfig`（只有 model + tokens 两个文件，**无 encoder/decoder/joiner 三件套**） |
| 6 | 模型类型字段 | `AsrExtModel.modelType` 支持 `"ctc"`（当前为 `transducer` / `nemo_transducer`） |
| 7 | **多语言共享 tokens** | 22 语共用一份 `tokens.txt`（仓库根目录）→ 注册表要支持「多个语言条目引用同一份 tokens 文件」，避免重复下载 |
| 8 | 单一模型文件 | 与 transducer 的「四文件」结构不同，需要让下载/校验逻辑兼容「单文件模型」 |

---

## 4. 体积与收益对照

| 方案 | 总新增体积 | 新增语言数 | 覆盖语区 |
|---|---|---|---|
| 保持现状 | 0 | 0 | 17 |
| **C. 混合（Whisper tiny）** | **+99MB** | **+83**（99 − 已覆盖的重叠）| 全球主要语区 |
| C + A | +739MB | +99 | 全球 + 欧洲高精度 |
| A. Parakeet v3 | +640MB | +16 | 欧洲 |
| B. Whisper base | +200MB | +83 | 全球（精度好于 tiny） |
| B. Whisper small | +500MB | +83 | 全球（精度接近专用模型） |
| **D. IndicConformer**（非 Whisper） | **+188MB × 语言数**（如 4 语 = **+752MB**） | +南亚语言数 | 南亚 22 语（印度官方语言） |

> **方案 D 的体积代价要点**：Whisper tiny 是 **99MB 覆盖 99 语**，
> 而 IndicConformer 是 **188MB 只覆盖 1 语**。
> 换句话说，**不用 Whisper 的代价 ≈ 每加一种语言就多 188MB**。
> 若只加 1~2 种南亚语言（如印地语 + 泰米尔语，+376MB）尚可接受；
> 加满 4 种则 752MB —— 已超过 Parakeet v3 的 640MB。

---

## 5. ✅ 已确定的落地方案（2026-10-01 用户决策）

**不走 Whisper**。分两条线并行扩展：

### 线 1 — 南亚 8 语（AI4Bharat IndicConformer，**非 Whisper**）

| 语言 | 码 | 体积 |
|---|---|---|
| 印地语 | `hi` | 188MB |
| 乌尔都语 | `ur` | 188MB |
| 泰米尔语 | `ta` | 188MB |
| 孟加拉语 | `bn` | 188MB |
| 马拉地语 | `mr` | 188MB |
| 泰卢固语 | `te` | 188MB |
| 古吉拉特语 | `gu` | 188MB |
| 卡纳达语 | `kn` | 188MB |
| **小计** | | **≈ 1.5GB**（+ 共享 `tokens.txt` 67KB） |

### 线 2 — 欧洲语言升级到 25 语（Parakeet TDT 0.6B v3）

欧洲语言从当前 **9 种**扩到 **25 种**（+16：`bg` `cs` `da` `nl` `et` `fi` `el` `hu` `lv` `lt` `mt` `pt` `ro` `sk` `sl` `sv`），
体积 **+640MB**，精度 WER 6.34%。

### 落地后总量

| 项 | 语言数 | 累计体积 |
|---|---|---|
| 内置 SenseVoice | 5 | 随 APK |
| zipformer-vi | 1 | 74MB |
| Parakeet v3（替代/共存 FastConformer，见下） | 25 | 640MB |
| zipformer-thai | 1 | 664MB 整包 |
| **IndicConformer 南亚 8 语** | **8** | **1.5GB** |
| **合计可选语言** | **≈ 37 种**（去重后） | —— |

> ⚠️ 全部下载量约 **2.9GB**（不含内置）。用户仍是**按需下载**，不会一次性占用。

---

## 6. 实施计划

### 第 1 步 — 抽象层：让 `AsrExtModel` 支持多种模型结构（地基）

当前 `AsrExtModel` 只描述 transducer 的「四文件」结构（encoder / decoder / joiner / tokens）。
需要泛化为支持三种：

| modelType | 文件结构 | 用于 |
|---|---|---|
| `transducer` | encoder + decoder + joiner + tokens | vi / th |
| `nemo_transducer` | encoder + decoder + joiner + tokens | Parakeet v3 |
| **`ctc`（新增）** | **仅 model + tokens** | **IndicConformer 南亚 8 语** |

要点：
- 新增 `ctc` 分支：`OfflineNemoEncDecCtcModelConfig(model, tokens)`
- 支持「**多个语言条目共享同一份 tokens 文件**」（IndicConformer 22 语共用）
- 支持「**单文件模型**」的下载与尺寸校验

### 第 2 步 — 注册表：登记 8 个南亚条目 + 1 个 Parakeet v3 条目

- IndicConformer：8 条（`hi`/`ur`/`ta`/`bn`/`mr`/`te`/`gu`/`kn`），共享 tokens
- Parakeet v3：条数取决于「替代 / 共存」决策

### 第 3 步 — 5 语文案

`res/values{,-en,-zh-rTW,-ja,-ko}/strings.xml` 各加 8 个南亚语言名 + 16 个欧洲语言名。

### 第 4 步 — UI

语言 chips 列表扩容（当前每行 4–5 个自动换行），并考虑**按语区分组**（避免 37 个 chip 平铺过长）。

### 第 5 步 — 验证

- 下载链路：IndicConformer（hf-mirror 按文件，已实测 206）+ Parakeet v3（GitHub releases 整包）
- 识别链路：各 modelType 分支实际解码一次
- 实机：MuMu 上跑通至少一种

---

## 7. 待确认的决策点

1. **优先级**：是想要「**更广的语言覆盖**」（选 Whisper），还是「**欧洲语言的更高精度**」（选 Parakeet v3）？
2. **体积容忍度**：手机端单次下载多少 MB 可接受？（99MB / 200MB / 640MB）
3. **Parakeet v3 与 FastConformer**：若引入 v3，是**替代**（省得下两个包）还是**共存**（用户自选）？
4. **Whisper 型号**：tiny（99MB，够用即好）还是 base（200MB，精度更稳）？
5. **落地语言清单**：Whisper 可覆盖 99 种，但 UI 不宜一次列 99 个 chip —— 需要圈定一个**首批清单**（建议 10~20 种高频语言）。

---

## 8. ✅ 最终决策与落地（2026-10-01）

### 最终方案（用户决策）

| 语区 | 方案 | 体积 | 条目数 |
|---|---|---|---|
| 中/英/日/韩/粤 | 内置 SenseVoice | 随 APK | 6（含 auto）|
| 越南语 | zipformer-vi | 74MB | 1 |
| 泰语 | zipformer-thai | 664MB 整包 | 1 |
| 欧洲 25 语 | Parakeet v3 **与** FastConformer 共存 | 640MB / 102MB | 25 |
| **亚洲 40 语 + 中文 22 方言** | **Dolphin base-int8** | **100MB** | **54** |
| （原 IndicConformer 南亚 8 语） | **已停用**（被 Dolphin 取代） | —— | 0 |

**UI 语言 chip 总数 = 87 个**（Dolphin 54 + 欧洲 25 + 内置 6 + vi/th 2），
按 `chunked(4)` 排布为 **22 行**。

> ⚠️ **待优化**：22 行 chips 偏长，建议后续按语区分组或加折叠。
> 当前实现未做分组（保持最小改动）。

**新增下载量**：Dolphin 100MB + Parakeet v3 640MB = **740MB**（原 IndicConformer 方案为 1.5GB）
**实际新增语言覆盖**：+54 项（其中 32 种语言此前完全没有覆盖）

### 已停用：IndicConformer（代码保留）

原计划的 AI4Bharat IndicConformer（南亚 8 语、188MB/语言、共 1.5GB）**不再登记进 `ALL`**，
原因是 Dolphin 以 **1/15 的体积**覆盖了其中 7 种语言。

条目本身**保留在 `AsrExtModels.kt` 中**（`HINDI` / `URDU` / … / `KANNADA` 与 `indicConformer()` 工厂），
以便日后需要时快速恢复（例如某语言的 Dolphin 识别效果不理想、想换专用模型）。
恢复方式：把需要的条目加回 `ALL` 即可 —— 装配路径（`isSingleModel` → `ctc` 分支）已经支持。

---

## 9. 实施进度（2026-10-01 已落地）

### ✅ 已完成（编译通过）

| 项 | 文件 | 说明 |
|---|---|---|
| 注册表结构扩展 | `AsrExtModels.kt` | `AsrExtModel` 支持三种 modelType；新增 `tokensDirName`（多语言共享 tokens）与 `isSingleModel`；`AsrExtFile` 新增 `dir`（落盘目录，用于共享文件） |
| 24 个语言条目 | `AsrExtModels.kt` | **南亚 8**（hi/ur/ta/bn/mr/te/gu/kn，CTC）+ **欧洲 16**（Parakeet v3 独有） |
| 多候选机制 | `AsrExtModels.kt` | 新增 `candidatesByKey()` / `DISTINCT_LANGS` —— 同一语言可有多个模型（俄语 = FastConformer + Parakeet v3），UI 只显示一次 |
| CTC 识别分支 | `SherpaAsrManager.kt` | `createExtRecognizer` 按 `isSingleModel` 分支：CTC 用 `nemo = OfflineNemoEncDecCtcModelConfig(model)` + `modelType="nemo_ctc"` |
| 共享 tokens 落盘 | `SherpaAsrManager.kt` | 新增 `fileOf()` / `extSubDir()`；`expectedFiles` 改为 `List<Pair<File, Long>>`；下载时按 `dir` 落盘并 `mkdirs` |
| 多候选选取 | `SherpaAsrManager.kt` | `createRecognizer` / `isModelReadyFor` / `modelInfoFor` / `startDownloadFor` 全部改为按候选列表取「第一个已就绪」 |
| 5 语文案 | `res/values*/strings.xml` | 24 个语言名 × 5 语 = **120 条** |

### ⚠️ 实施中踩到的坑

**`OfflineModelConfig` 的字段名是 `nemo` 而不是 `nemoCtc`。**
从 Java 类名 `OfflineNemoEncDecCtc` 很容易直觉写成 `nemoCtc`，但 Kotlin data class 的形参名就是 `nemo`。
写错得到 `No parameter with name 'nemoCtc' found`（已实测）。
> 排查方法：解压 `app/libs/sherpa-onnx-*.aar` 里的 `classes.jar`，
> 用 `javap -p com/k2fsa/sherpa/onnx/OfflineModelConfig.class` 直接看字段名 ——
> **比翻文档快且不会错**。

### ⏳ 未完成

1. **构建 APK** 与**实机验证**（下载链路 + 识别链路均未跑过）
   - 需验证：IndicConformer 的 `hi/model.int8.onnx` 是否正确落到 `ext-indic-hi/`、
     `tokens.txt` 是否落到共享目录 `ext-indic-shared/`
   - 需验证：CTC 识别器能否真正完成一次解码
2. **UI 分组优化**（可选）：当前 41 个语言 chip 按每行 4 个 = 11 行，
   未按语区分组；若觉得过长可后续加「语区折叠」
3. 文档未同步到 README 的功能表 / CHANGELOG（待发版时一并做）
