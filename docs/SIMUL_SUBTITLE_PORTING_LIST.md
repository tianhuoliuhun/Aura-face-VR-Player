# simul-subtitle 可移植清单

> 来源：[KorenKrita/simul-subtitle](https://github.com/KorenKrita/simul-subtitle)（MIT，2026-10 活跃）
> —— 日/英实时同传悬浮字幕，管线为 `采集 → Silero VAD → 分句 → sherpa-onnx ASR → BYOK 翻译 → 悬浮层`。
> 本文按其 `docs/architecture.md` / `docs/asr.md` / `docs/translation.md`（2026-11-04 抓取）逐条评估可移植性。
> 定位：**不是引入它的代码，是移植它的踩坑结论与产品形态**（自研弹幕/字幕架构见 `ai-danmu` 与 `SubtitleOverlay` 路径）。

## 总览

| # | 条目 | 来源 | 本项目对应位置 | 成本 | 建议 |
|---|---|---|---|---|---|
| 1 | Index-Translate 请求带 User-Agent | translation.md | `SubtitleTranslator.translateViaOpenAiApi` | **极低（1 行）** | 🔴 立即 |
| 2 | ASR 首尾补 0.3s 静音 | asr.md | `RealtimeSubtitleEngine` 送 `acceptWaveform` 处 | 低 | P1 |
| 3 | 字幕防跳动（底边锚定 + 平滑滑动） | README | `SubtitleOverlay.kt` | 中 | P1 |
| 4 | 术语表 + 场景背景（instTrans 形态） | translation.md | `SubtitleTranslator` system prompt + 设置面板 | 中高 | P1（观察项落地） |
| 5 | 关闭思考 7 参数自动探测 + 缓存 | translation.md | `translateViaOpenAiApi` / `translateViaIndexTranslate` | 中 | P1 |
| 6 | 前缀缓存排布（固定内容全进 system） | translation.md | 同上（system prompt 结构） | 低 | P2 |
| 7 | 上文窗口（最近 N 句定稿原文） | translation.md | 翻译请求 user 消息组装 | 中 | P2 |
| 8 | 草稿/定稿双态分句 | architecture.md | `RealtimeSubtitleEngine`（现整段出） | 高 | P2 |
| 9 | ASR/MT 双线程 + 过时草稿折叠 + 过载保护 | architecture.md | `VRGLRenderer` 检测线程池 / 翻译并发 | 中 | P2 |
| 10 | 安全加固（https-only / 不跟随重定向 / 日志脱敏 / Key 加密） | translation.md | `SubtitleTranslator`、prefs | 低-中 | P2 |
| 11 | 流式翻译 + 首字 5s / 整句 8s 超时 | translation.md | `translateViaOpenAiApi`（现非流式） | 中 | P3 |
| 12 | JSONL 会话日志（.part 原子转正 + 脱敏） | architecture.md | 现无（诊断日志走 logcat） | 低-中 | P3 |
| 13 | 暂停语义（说到一半立即定稿） | README | `RealtimeSubtitleEngine` 暂停路径 | 低 | P3 |
| 14 | ReazonSpeech vs SenseVoice 实测数据 | asr.md | （选型参考，本项目有 SenseVoice 扩展） | 0 | 参考 |
| 15 | "说完再出"省电模式 / 四角缩放 / 锁定 | README | `SubtitleOverlay` / 悬浮球交互 | 低-中 | P3 |

---

## 逐条说明

### 1. 🔴 Index-Translate 请求带 User-Agent（立即做）

simul-subtitle 的 Index-Translate 配置表明确写着：**User-Agent 填 `Index-Translate-Client/1.0`，否则会被公网防火墙拦截（HTTP 412）**。
本项目 `translateViaOpenAiApi` 走 OkHttp 默认 UA（`okhttp/x.y`），2026-11-04 实测未被拦（可能因为没有流式/频率低），但这是**潜在的随机故障源**。
改法：该函数（或 `translateViaIndexTranslate` 入口）按引擎补 `User-Agent: Index-Translate-Client/1.0`；顺带把「Key 为空时不发 `Authorization` 头」对齐（simul 的安全策略，也避免个别网关因空 Bearer 401）。
**成本：1~3 行。**

### 2. ASR 首尾补 0.3s 静音

ReazonSpeech 实测「有效语音从第 0 个采样点开始会吞掉句首第一个词」，送识别前前后各补 0.3s 静音。本项目同用 sherpa-onnx，`Dolphin/SenseVoice` 是否有同类问题未验证——但补静音零风险。
改法：`RealtimeSubtitleEngine` 送 `stream.acceptWaveform(samples, 16000)`（约 931 行）前拼 `FloatArray(4800)`（0.3s@16k）头尾。
**成本：几行 + 真机验证一次。**

### 3. 字幕防跳动

它的方案（2026-10-16 专项 commit）：字幕框未调高度时随文字伸缩——**放屏幕下半部时底边不动、向上生长**（正在读的行不被挤走），上半部顶边不动；行上移时 **~200ms 平滑滑动**而非跳变，锚定最新句；Android 同帧重排窗口并**关掉系统窗口移动动画**。
本项目 `SubtitleOverlay.kt` 的翻译浮层/字幕行更新时有同类"跳行"问题（双语模式下尤其明显）。
**成本：中（布局锚定 + 动画，约 100~200 行）。**

### 4. 术语表 + 场景背景（观察项「术语表 prompt」的成品形态）

场景 = 名称（20 字）+ 背景说明（1000 字）+ 术语表（200 条原文→译文）；**术语表绑定目标语言（termsLang），仅当目标语言一致时才拼进提示词**；支持「AI 生成术语」（只增不改、格式清洗）。内置示例：「B 站 VTuber 直播」（ビリビリ→B站 等）。
本项目已有验证：Index-Translate 术语表探针 ✓（2026-11-04 实测「店長=店长」严格遵守）。
改法：`TranslationConfig` 加场景字段；system prompt 注入背景+术语表；设置面板加编辑 UI；可先做「术语表」不做「AI 生成」。
**成本：中高（UI+持久化+提示词，300~500 行）；纯提示词注入版可缩到 100 行。**

### 5. 关闭思考 7 参数自动探测

各家关 Reasoning 的字段不统一，simul 固定一句日语依次试 7 种参数（`thinking:{type:disabled}` / `chat_template_kwargs` / `enable_thinking:false` / `reasoning:{enabled:false}` / `reasoning_effort:"none"` / `"minimal"+提示词` / 不加），命中缓存到「地址+模型」；判定标记：`reasoning_content` / `reasoning` 字段、`<think>`、Gemma 思考通道、`usage.reasoning_tokens > 0`。
本项目现状：Index-Translate 实测思考在独立 `reasoning` 字段（content 干净），已做防御剥离；但接用户自配 LLM（CUSTOM/OPENAI 引擎）时思考污染仍是风险（历史事故：译文混 `<think>`）。
改法：`translateViaOpenAiApi` 增加一次性探测 + prefs 缓存；剥离逻辑从「防御」升级为「判定+提示」。
**成本：中（约 150 行）。**

### 6. 前缀缓存排布

固定内容（翻译规则、源/目标语言、场景背景、整份术语表）全部收敛到 system 消息；user 只有【前文】【当前句】【修正说明】。服务端前缀缓存只复用开头完全一致的 token——system 不变才能吃到缓存。`temperature: 0`；输出上限中文 160 token / 其它 256。
本项目 system prompt 已基本固定，缺术语表注入位（条目 4 落地时按此结构排）。
**成本：低（提示词重排）。**

### 7. 上文窗口

「结合上文」默认开、最近 2 句定稿**原文**（1-5 句可调）；**段内上下文锁定**（该段上文在前一句定稿翻译完成时固定，草稿→定稿提示词一致）。
⚠️ simul 早期 commit 实测"No cross-sentence MT context（made finals worse）"，后来做成可选项且默认 2 句——说明**上下文要给但要给对**（只给定稿原文、不给草稿、少量）。
本项目翻译逐句独立，人称/指代类误译可借此改善。
**成本：中。**

### 8. 草稿/定稿双态分句（价值大、成本也大）

Segmenter 状态机：说话中每 **0.7s** 刷草稿（灰）；静音 **0.4s** 定稿（白）；已说满 3s 的长句静音 **0.19s** 即定稿；单句上限 **7s**（在最长停顿处强切）；短句（<0.4s）最多等 **1.5s** 合并。
本项目 `RealtimeSubtitleEngine` 是整段出字幕，交互上"说完才出"。移植后 ASR 字幕可实时跟话。
**成本：高（分句状态机 + UI 双态渲染，建议按其参数直接起步再调）。**

### 9. 双线程 Pipeline 与过载保护

ASR / MT 独立工作线程；过时草稿折叠（新草稿到来丢弃在途旧草稿结果）；**finals 永不丢**；显式过载信号；非阻塞 stop + `awaitStopped` 后再 release native（防 use-after-free）。
本项目刚修过线程池 shutdown 停摆（v2.6.22 P0-1），可再补「过时草稿折叠」语义。
**成本：中。**

### 10. 安全加固

仅接受 `https://`；请求**不跟随重定向**（防 Key 被重定向泄露）；原始流式报文/思考内容/错误正文（可能回显 Key）不落日志；API Key 按平台加密（Android Keystore / macOS 钥匙串 / Windows DPAPI）。
本项目 Key 目前明文存 prefs（`translate_api_key` / `danmu_*`），日志偶有完整响应。**商业分发前建议至少做 Key 加密与日志脱敏。**
**成本：低-中。**

### 11. 流式翻译 + 超时预算

流式请求；首字 5s 未到判失败，首字后整句最长 8s；失败不自动重试（弹幕/字幕场景要快失败）。
本项目字幕翻译非流式，OkHttp 整句超时。移植后字幕"逐字蹦出"体验更好。
**成本：中。**

### 12~13、15. 产品细节

JSONL 会话日志（`.part` 写入、正常结束原子转正、脱敏）；暂停时"说到一半立即定稿、不拆两次记录"；"说完再出"省电模式；悬浮窗四角拖拽缩放 / 锁定 / 点按工具栏。
**成本各低-中，按需取用。**

### 14. 选型参考（不移植）

simul 的 ReazonSpeech vs SenseVoice-Small 实测（Mac M4 Pro，FLEURS）：
| | ReazonSpeech k2 v2 | SenseVoice-Small |
|---|---|---|
| 日语 CER | 8.48% | **7.52%** |
| 英语 WER | 22.66% | **8.33%** |
| RTF | **0.0075** | 0.0164 |
| 单句解码中位 | **93/71ms** | 203/157ms |
| 体积 | **77MB** | 239MB |
| 内存增量 | **+214MB** | +837~973MB |

对本项目的含义：SenseVoice 准确率更好但慢 2 倍、内存 4 倍——手机常驻 ASR 场景下轻量模型优先的取舍成立（本项目 Dolphin 100MB 路线同理）；SenseVoice 的 `language` 参数无效（实测强制 ja 拦不住英语误识），与本项目「SenseVoice 无语言强制」的既有认知一致。

---

## 不移植

- **音频捕获**（AudioPlaybackCapture / WASAPI loopback）：它是"捕获别的 App"，我们是播放器本体，音频/字幕流直接在手。
- **HY-MT 本地 MT / Jev 云服务**：前者我们有 llama.cpp + Index-Translate-2B GGUF 候选；后者闭源商业、默认关闭。
- **ReazonSpeech 模型本体**：本项目 ASR 矩阵已按多语言规划（Dolphin/SenseVoice/zipformer/parakeet），ja/en 双语不是当前缺口。

## 建议落地顺序

1. **条目 1（UA）**——随下一批改动顺手带上，防随机 412。
2. **条目 2 + 3**（补静音 + 防跳动）：低成本、体感直接。
3. **条目 4 + 5**（术语表 + 关思考探测）：把两个翻译观察项一次落地。
4. 条目 8/9（双态分句）单独立项评估。
