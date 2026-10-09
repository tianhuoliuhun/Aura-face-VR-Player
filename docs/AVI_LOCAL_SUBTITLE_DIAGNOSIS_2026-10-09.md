# AVI 播放问题 & 本地字幕生成问题 —— 根因诊断

> 2026-10-09 · 基线版本 v2.4.18 · **本文只做诊断，不含代码改动**
> 结论均标注证据来源（代码行号 / 源码级事实 / 实测），未验证的假设单列。

---

## 〇、结论摘要

两个问题**不是两件事，是同一个容器层缺陷在两个链路上的两种表现**：

| 链路 | 走谁解容器 | AVI 上的表现 |
|---|---|---|
| **播放** | Media3 `AviExtractor`（自研替换为 Media3Avi） | 索引缺失 → 拖动后画面停住；未知 fourcc → 丢轨 |
| **本地字幕生成** | **框架 `MediaExtractor` + `MediaCodec`**（`AudioTee`） | 音轨解不出/无解码器 → **报「没有可用音轨」**；无索引 → 生成极慢 |

**四条根因**（按影响排序）：

1. **AVI 索引（`idx1`）缺失/不完整** —— 播放 seek 卡死与字幕生成慢的**共同病根**。
2. **本地字幕只有一条音频解码路径**（框架 API），**没有任何 FFmpeg 回退** —— 框架解不了就彻底不可用，而播放至少有 4 个内核可切。
3. **错误提示把「格式不支持」误报成「没有音轨」** —— 诊断被带偏（明确的代码缺陷）。
4. **修复动作全是「事后触发」** —— 用户必须先被卡一次，系统才介入。

---

## 一、AVI 播放问题

### 1.1 根因（容器层，均有源码级证据）

| # | 事实 | 证据 | 影响 |
|---|---|---|---|
| A1 | 官方 Media3 `AviExtractor` **只认 `idx1`**；没有它就直接 `SeekMap.Unseekable` | `docs/AVI_EXO_COMPAT_STUDY.md`（反编译 `AviExtractor` 状态机，只有 `STATE_FINDING_IDX1_HEADER`/`READING_IDX1_BODY`） | **拖动后「跳到目标帧却不继续播」的官方根因** |
| A2 | 官方只支持**单个 `movi` 段** | 同上（`moviStart`/`moviEnd` 是单值） | >1GB 的 OpenDML AVI 只能读第一段 |
| A3 | 官方只认 **14 个 fourcc**（无 `AV01`） | 反编译 `StreamFormatChunk.getMimeTypeFromCompression` 的 `lookupswitch` | 未知编码**整条视频轨被丢弃** → 「有声音无画面」 |
| A4 | **IJK 完全没有 AVI demuxer** | `libijkplayer.so` 搜 `ff_avi_demuxer` = 0 命中（那 82 次 `avi` 全是 `avio_*` I/O 函数名） | AVI **不能路由到 IJK**；换内核这条路对 AVI 是死的 |

### 1.2 已实现的补救（v2.4.9 / v2.4.15）

| 措施 | 位置 | 说明 |
|---|---|---|
| 打开文件即探测 `idx1` | `AviRiffProbe.kt` | 读 RIFF 头 1KB + **文件尾 1MB** 找 `idx1`，带结构性校验（16 字节倍数、≤512MB） |
| 无索引 → 首次 seek 直接重封装 | `VRPlayerScreen.kt` `startRemuxFix()` | 不必先白卡一次 |
| 通用兜底：位置到位后 1s 不推进 → 重封装 | 同上 | 判据是「完全没动」而非「推进得慢」（免误伤慢速软解），且排除 `STATE_BUFFERING` |
| 接入 Media3Avi（补 OpenDML） | `DefaultMediaSourceFactory(context, AviExtractorsFactory())` | 补 `indx`/`ix##`/`AVIX`/多 `movi`/`DMLH`/无索引稀疏兜底 |

### 1.3 🔴 仍存在的盲区（**这是「没彻底解决」的部分**）

| # | 盲区 | 位置 | 后果 |
|---|---|---|---|
| **B1** | `AviRiffProbe` 只回答「**有没有** `idx1`」，**不判「`idx1` 是否覆盖全片」** | `AviRiffProbe.kt:52` `needsIndexRebuild = isAvi && !hasIndexChunk` | OpenDML AVI 常见「`idx1` 只覆盖第一段、`indx` 才覆盖全部」→ 被判定「有索引、不必重封装」→ **第一段之后 seek 依旧失效**。⚠️ 记忆标注：**该假设尚未用真实样本验证** |
| **B2** | 修复全部**事后触发** | `startRemuxFix` 仅在①首次 seek ②位置 1s 不动 时调用 | **用户不 seek、又只播第一段时，什么都不会发生**；且「播到某处卡住」仍要先卡一次 |
| **B3** | 重封装**依赖框架 `MediaExtractor`** | `VideoRemuxer.kt:85-88` | 框架认不出的编码，重封装**同样失败** → 无出路（只能提示用户自己转 MKV/MP4） |
| **B4** | 重封装**有损** | `VideoRemuxer.kt:174-177`（时间戳倒退/相等→**丢样本**）；`audioIncluded=false`（音轨 muxer 不支持→**静音**） | 丢个别帧；音频编码不受支持时产出**无声** MP4 |
| **B5** | AVI 默认仍走 EXO | `MediaFormats.kt:105/240`（`AVI_EXTS` ∈ `COMMON` + `EXO_ONLY`） | MPV 明明有完整 AVI 支持（`libavformat` 有 AVI + `V_AV1`），但**只有用户手动切内核才能用上** |

---

## 二、本地字幕生成问题

### 2.1 链路全貌（代码实证）

```
VRPlayerScreen.kt:3814   LaunchedEffect(isRealtimeSubtitleEnabled, uri, ...)
  → RealtimeSubtitleEngine.start()            // :189
      → AudioTee(context, uri).open()         // :236-242  ⚠️ 唯一音频来源
          → MediaExtractor（框架）+          // :898-923 / :926-954
            MediaCodec.createDecoderByType()  // :945
      → prefetchLoop()                        // :338  60s 窗口滚动预读
          → decodeWindow()                    // :1002 seekTo(CLOSEST_SYNC) + 解码
          → VAD 切分（Silero / 能量法）→ SenseVoice ASR → SubtitleCache
```

### 2.2 根因

| # | 事实 | 位置 | 后果 |
|---|---|---|---|
| **C1** | **`AudioTee` 只有框架 `MediaExtractor` + `MediaCodec` 一条路**，**无 FFmpeg 回退** | `RealtimeSubtitleEngine.kt:831-1000` | AVI 音轨若为 **AC3/DTS/WMA** 等框架无软解的编码 → 直接不可用。**播放有 4 个内核可切，字幕一条都没有** |
| **C2** | **「无音轨」与「有音轨但无解码器」被合并成同一个返回值** | `openCodec`：`audioTrack < 0 → false`（:941）；`createDecoderByType` 抛异常 → `false`（:950-953） | 调用方统一提示 **`rt_no_audio`「该视频没有可用的音轨」**（:239）→ **把「格式不支持」误报成「没有音轨」**，用户与排查双双被误导 |
| **C3** | 无索引 AVI → `seekTo(SEEK_TO_CLOSEST_SYNC)` 要线性扫描 | `:1007`；预读每 60s 窗口 seek 一次（`:77 DECODE_WINDOW_MS`） | 生成吞吐骤降（「能出字幕，但慢得离谱」） |
| **C4** | `open()` 失败即**终止引擎** | `:237-242`（`hasAudioTrack=false` + `return@launch`） | 没有任何降级/重试/替代路径 |
| **C5** | 字幕链路**只认 `isVideo`**，对 AVI 无任何特殊分支 | `:3827` | AVI 会正常尝试，失败时才会撞上 C2 的误导提示 |

### 2.3 ⚠️ 尚未确认项

- **AC3/DTS 等音轨在框架 AVI extractor 下究竟能否被识别** —— 需真实样本实测（本机 MuMu 已关闭、无线设备不稳定，未能实测）。
- 具体失败现象（不出字幕 / 很慢 / 提示没有音轨）**需用户确认**。

---

## 三、两条链路的交汇点（最重要的一节）

**重封装成果会被字幕链路继承** —— 这是唯一现成的「一根治两病」通路：

```
VRPlayerScreen.kt:2651   startRemuxFix 成功后：
    selectedMediaItem = copy(uri = 重封装后的 mp4)   ← 播放项 URI 被替换
    photoReloadTrigger++
VRPlayerScreen.kt:3814   实时字幕 LaunchedEffect 的 key **含 selectedMediaItem.uri**
    → URI 变化 → 字幕引擎自动重启 → AudioTee 打开的是重封装后的 MP4（框架能正常解）
```

**推论**：
- 重封装**发生之后** → 播放（seek 正常）+ 字幕（音轨可解、seek 快）**双双变好**；
- 重封装**没被触发**（用户不 seek、或 `AviRiffProbe` 误判「有索引」B1）→ **两条链路都停在原始 AVI 上**；
- 音轨**编码本身**不受框架支持时（C1）→ 重封装只换容器不换编码 → **仍然失败**。

→ 即：**当前架构下「彻底解决」的瓶颈已不在 seek，而在「谁来解析 AVI」这件事只做了一半。**

---

## 四、「彻底解决」的候选方案（按投入排序，供选型）

### 方案 1（最小改动）：把「事后触发」改成「事前预防」+ 修正误报
- **1a**：打开 AVI 时若探测到「无 `idx1`」**或**「`idx1` 不完整（B1）」→ **进场即重封装**（不再等用户 seek）。
- **1b**：`AudioTee.openCodec` 区分「无音轨」/「无解码器」，提示分别给（修 C2）。
- **1c**：AVI 默认内核改为 **MPV**（B5）——MPV 有完整 FFmpeg，AVI 播放天然可用。
- 成本：低（无新依赖）。效果：解决「不 seek 就不修」与「提示误导」。

### 方案 2（推荐，治本）：给字幕链路接一条 FFmpeg 音频路径
- 现状缺口：**AVI 友好 = FFmpeg，而字幕链路没有 FFmpeg**。
- 可选实现：
  - **2a** 复用 MPV 的 `libavformat`/`libavcodec`：新增「从容器抽音轨 → PCM」的 native 通道（与现有 `libmtmd`/MPV 集成方式同源）。
  - **2b** 把字幕链路的音频来源改为「**先重封装成 MP4 再交给 `AudioTee`**」——复用现成 `VideoRemuxer`，但**必须先触发重封装**（与方案 1a 天然配套）。
- 成本：2a 高（新增 native 通路）；**2b 低且立刻可用**。
- 效果：字幕在 AVI 上不再依赖框架 extractor。

### 方案 3（根治容器层）：换掉/补齐 AVI 解析实现
- 换用完整 FFmpeg 系 demuxer（MPV 已有）；或把 Media3Avi 升级到覆盖「`idx1` 只覆盖第一段」的判定。
- 成本：中～高；需真实 OpenDML 样本验证。

### 建议组合
**1b + 1c + 2b**（改动小、当天可验证）→ 再评估 **1a / 3**（需要真实 AVI 样本做回归集）。

---

## 五、需要用户确认/提供的信息

1. **「本地字幕生成问题」的具体现象**：是完全不出字幕 / 秒出但很慢 / 提示「没有可用的音轨」？
2. **出问题的 AVI 样本特征**：文件大小、编码（可用别的工具看）、是否有索引、来路（老设备导出？）。
3. 是否接受 **AVI 默认走 MPV**（会带来 MPV 的已知约束，如单例、`vo=gpu`）。
4. 是否需要「**打开 AVI 就自动重封装**」（代价：首次打开要等几秒~几十秒，且占缓存空间）。
