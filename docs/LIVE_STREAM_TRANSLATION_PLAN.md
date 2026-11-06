# 直播流翻译方案

> 状态：**P0–P3 已实现**（v2.6.24，2026-11-06）。
> 背景：原翻译链路是为**点播文件**设计的——字幕来自外挂字幕或整片 ASR，翻译走「按播放位置取 cue + 预读翻译」。
> 直播流引入四个新约束：**时长无限、不可 seek、无未来内容、位置会跳变（重连/切码率）**。
>
> ## 实现落点（v2.6.24）
>
> | 层 | 实现在哪 |
> |---|---|
> | 直播识别 | `VrPlayerBackend.isLive`（EXO 以 `Timeline.Window.isLive` 为权威）＋ `VRPlayerScreen` 连续 3 次采样确认 |
> | **直播强制 EXO** | `MediaFormats.isLikelyLiveStream`（打开前判据）＋ `liveForceExoUri`（运行时兜底，触发一次重建） |
> | LIVE 态 UI | 进度条禁用、时间区 `● LIVE`、断点读写跳过、快进/快退（手柄 + 悬浮球）只提示不执行 |
> | P1 字幕源 | 直播提示开「实时 AI 字幕」；流内嵌 CC 走既有 `exoCueText` |
> | P2 翻译 | `SubtitleTranslator.liveMode`（不预读 / 不落盘 / 超时丢弃）＋ `liveCueDeadlineMs` |
> | P3 稳定性 | `trimRealtimeCues`（10 分钟 / 500 条）、30s 跳变清 cue、`STATE_BUFFERING` 5s 提示 |
> | P4 直播延迟 | 字幕样式面板的「直播延迟」滑块（0~60s）→ `VrPlayerBackend.setLiveDelayMs` → EXO `MediaItem.LiveConfiguration.targetOffsetMs` |
>
> ## 🟥 顺手修掉的一个"直播根本打不开"的真 bug（v2.6.24）
>
> **现象**：任何 HLS 直播在 EXO 下都会在 ~30 秒内抛
> `HlsPlaylistTracker$PlaylistStuckException`，然后走 Exo 失败回退 → 最终"直播只能用 MPV"。
>
> **根因**：`SchemeRoutingDataSource` 对 http(s) 一律包了 `CacheDataSource`，把 **media playlist
> （m3u8/mpd）也缓存住**了 —— 清单本来是持续变化的，缓存后每次 reload 都读到同一份旧内容，
> Media3 的 HLS 追踪器因此判定"清单卡住"。
> 与片源无关：本地自造流、Akamai、Unified Streaming 的公网直播流全部复现。
>
> **修法**：`MediaFormats.isStreamManifest()` 判定清单请求，命中则**绕过缓存**直连
> （分片 `.ts/.m4s` 仍走缓存 —— 分片内容不可变，缓存能省流量）。
>
> 修完之后：真实直播流在 EXO 下正常出画，`● LIVE` 正常显示，
> 且「用户设置成 MPV 内核 → 打开直播被强制切到 EXO」这条路径实测生效。
>
>
> ⚠️ **已知限制（P1）**：本项目的实时 ASR 走 `AudioTee`（`MediaExtractor` 独立解文件），
> 对 **HLS/DASH 多分片直播**打不开音频（`AudioTee.open()` 返回失败并给出状态提示）。
> 因此直播字幕当前实际可用的是「**流内嵌 CC/TTML**」这一路；
> 要让 HLS 直播也能出实时 ASR，需要改从播放器音频管线（Media3 `TeeAudioProcessor`）旁路取 PCM，
> 属于独立任务，不在 P1 范围内。

## 一、现状盘点（代码事实）

| 能力 | 现状 | 代码位置 |
|---|---|---|
| 直播识别 | **完全没有**：无 `isCurrentMediaItemLive`、无 `TYPE_LIVE` 判定 | `VrPlayerBackend.kt`（接口只有 `duration: Long`） |
| 时长/进度 | `duration > 0` 才更新进度；直播 `duration` 为 `C.TIME_UNSET`（负数）→ 进度条恒 0、时长显示 `00:00` | `VRPlayerScreen.kt` 轮询段（约 4438） |
| 实时字幕 | 已有（AudioTee 独立解码 → VAD → sherpa ASR），需**手动开启** | `RealtimeSubtitleEngine.kt`、`VRPlayerScreen.kt` 约 4370 |
| 字幕源 | 外挂字幕 / 内嵌 cue（`exoCueText`）/ 实时 ASR 三路 | `SubtitleOverlay` 的 `subtitleCues` 与 `exoCueText` |
| 翻译 | `translateOrOriginal` 逐句 + `pretranslateAhead` 预读 | `SubtitleTranslator` |
| 缓存 | 内存 `ConcurrentHashMap` + **磁盘 TSV**（`cache_<lang>.tsv`） | `SubtitleTranslator` 磁盘缓存段 |
| 断点续播 | 有（`PlaybackPositions`），直播无意义且会 seek 到失效位置 | `PlaybackPositions.kt` |

**结论**：直播不是"翻译要不要支持"的问题，而是**整条「位置—字幕—翻译—UI」链路缺一个直播态**。

## 二、方案分层

### P0 · 直播态识别与 UI（最小可用，先让直播能正常播）

1. **后端加直播能力**（`VrPlayerBackend`）
   ```kotlin
   /** 直播流：时长未知/不可 seek。EXO 用 isCurrentMediaItemLive，其它内核按 duration≤0 兜底 */
   val isLive: Boolean
   ```
   - `ExoBackend`：`exo.isCurrentMediaItemLive || exo.duration <= 0`（EXO 对 HLS/DASH 直播有原生判定）
   - `IjkPlayerBackend`：`duration <= 0`（IJK 对 live 常返回 0/负）
   - `MpvPlayerBackend`：`duration <= 0`
   - `SystemPlayerBackend`：`duration <= 0`
2. **进度条切 LIVE 态**（`VRPlayerScreen`）
   - 直播时：进度条禁用拖动、不显示百分比；时间文本显示 `LIVE`（可用 `● LIVE` + 缓冲延迟秒数）
   - 隐藏/禁用快进快退、A-B 循环等依赖时长的控件
3. **关掉断点续播与「播完」逻辑**：`PlaybackPositions` 在直播下跳过读写；播完/循环判定不参与

> 验收：HLS 直播源能正常播放、进度区显示 LIVE、拖动不误操作、退出重进不 seek 到失效位置。

### P1 · 字幕源（直播只有"正在说"）

1. **直播默认强制实时 ASR 字幕**（当前是手动开关，且 ASR 引擎对本项目是独立解码链路）
   - 直播下若用户开了字幕但没开实时 ASR，直接提示"直播字幕需开启实时识别"
2. **HLS 内嵌字幕轨（CC/TTML）**：EXO 能出 cue 时走现有 `exoCueText` 路径，无需新增逻辑
3. **cue 时间轴**：实时 ASR cue 用「本地播放位置」打戳，直播位置单调递增 → 现有 `SubtitleOverlay` 按 `currentPositionMs` 取 cue 可直接复用

> 验收：直播开字幕 3 秒内出第一句原文。

### P2 · 翻译策略（直播与点播的核心差异）

| 点播做法 | 直播要做 | 原因 |
|---|---|---|
| `pretranslateAhead` 预读（提前翻整段未来字幕） | **关闭预读**，改「尾随翻译」：只翻最近 N 秒（当前 cue + 前 1~2 句） | 直播没有未来内容，预读等于空转 |
| 全量缓存 + 写磁盘 TSV | **只写内存**（容量上限 + TTL），不落盘 | 直播内容不复现，落盘只会让 tsv 无限增长 |
| 翻译可等 | **超时丢弃**：cue 已结束超过 `2s` 仍没翻完 → 直接放弃，不补翻 | 直播场景迟到的字幕没意义，还会占并发额度 |
| 逐句串行 | 短句优先 + 并发 2~4（现有 semaphore 已支持） | 控制端到端延迟 ≤ 1~2s |

1. **新增 `isLive` 到翻译上下文**：`SubtitleTranslator` 增加 `@Volatile var liveMode: Boolean`，
   `pretranslateAhead` / 磁盘缓存写入 / 过期丢弃三处按它分支
2. **延迟观测**：沿用现有 `SeekDbg` 式临时日志思路，新增一行 `live translate: cueAge=…ms cost=…ms`，
   真机调优有依据（不常驻）

> 验收：直播下"说话结束 → 译文上屏"延迟 ≤ 2s（中英/日中），且内存与磁盘不随时间增长。

### P3 · 内存、重连与异常

1. **cue 列表裁剪**：`realtimeCues` 只保留最近 **10 分钟 / 500 条**（弹幕取词窗口只需 ±15s），
   引擎回调已是"完整快照"，在 `onCuesUpdated` 处截断即可
2. **位置跳变检测**（直播重连 / 切码率 / 拖回实时边缘）：
   - `|newPos - lastPos| > 30s` → 清空 `realtimeCues` 与翻译内存缓存，ASR 重新起一个窗口
   - 直播 seek 只允许"跳到实时边缘"（`seekToDefaultPosition()`），不做任意 seek
3. **断流/缓冲状态**：`Player.STATE_BUFFERING` 超过 5s 或 `onPlayerError` → 字幕层提示
   "直播缓冲中/已断开"，ASR 暂停以免堆积错误文本

> 验收：断网 30s 再恢复，字幕不乱、不重复、不残留旧句。

### P4 · 可选增强

- **LIVE 延迟补偿**：直播本身有端到端延迟（HLS 3~15s），字幕延迟已有 `subtitleDelayMs`，
  可在直播下把默认延迟按流类型预设（HLS 低延迟 vs 标准）
- **双语直播字幕**：直播场景常用"原文+译文"双语，现有 `DUAL_LANGUAGE` 模式直接可用
- **术语表**：直播（尤其 VUP/电竞）专有名词多，v2.6.23 已加术语表，直播下价值更高

## 三、风险与取舍

| 风险 | 说明 | 缓解 |
|---|---|---|
| ASR + 翻译双重延迟 | 直播总延迟 = 编码延迟 + ASR(0.5~2s) + 翻译(0.5~2s) | 短句优先、超时丢弃、术语表减少回改 |
| 不可回看 | 翻错无法重放 | 宁短勿长；不追求长句连贯 |
| 缓存膨胀 | cue / 翻译缓存随时长线性增长 | P3 的窗口裁剪 + 不落盘 |
| 内核差异 | IJK/MPV 的 live 判定靠 duration≤0，可能误判（点播元数据解析中也会短时为 0） | 加二次确认：连续 3 次采样 duration≤0 才认定直播 |
| HLS 加密/DRM | 部分直播受 DRM 限制，音频无法进 ASR | 提示"受版权保护的直播不支持字幕" |

## 四、建议实施顺序

1. **P0**（1~2 天量级）：直播能正常播 + UI LIVE 态 —— 这是"直播可用"的地基
2. **P1 + P2**（2~3 天）：实时 ASR 强制 + 尾随翻译 + 不落盘，直播字幕翻译真正可用
3. **P3**（1~2 天）：长时运行稳定性（裁剪、跳变、断流）
4. P4 视需求

## 五、待确认的问题（需要产品决策）

1. 直播**是否自动开启实时 ASR 字幕**？自动开比较顺手，但 ASR 有功耗/CPU 成本
2. 直播字幕**默认单语还是双语**？（双语信息量全，但占屏更多）
3. 是否要**显示直播延迟秒数**（距离实时边缘多久）？
4. 直播翻译**是否只对中文目标**优化（当前多数用户目标为中文）？
