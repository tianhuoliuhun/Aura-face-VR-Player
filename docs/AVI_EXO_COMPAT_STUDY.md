# Exo（Media3）对 AVI 的兼容边界与可选方案

> 调研日期：2026-10-09
> 调研动机：本项目 AVI 的 seek 问题（「能跳过去、画面停在那一帧」）已用 `AviRiffProbe` +
> `VideoRemuxer`（重封装重建索引）解决，但那是**自研绕路**。本文回答：
> **Exo 官方到底支持 AVI 到什么程度？有没有更正规的做法？**
>
> 所有结论均基于**源码实证**（不靠印象），证据文件留在 `.workbuddy/tmp/avi_research/`。

---

## 一、官方 `AviExtractor` 的支持边界（Media3，1.4.1 ~ release）

### 1.1 只认 `idx1` 一种索引 —— 状态机里没有第二种

官方 `AviExtractor` 的状态机常量（`Official_AviExtractor.java` L100-118）：

```java
STATE_SKIPPING_TO_HDRL, STATE_READING_HDRL_HEADER, STATE_READING_HDRL_BODY,
STATE_FINDING_MOVI_HEADER,
STATE_FINDING_IDX1_HEADER,      // ← 索引相关只有这两个
STATE_READING_IDX1_BODY,
STATE_READING_SAMPLES,
```

它的 fourcc 常量表（L60-79）只有：
`FOURCC_RIFF` / `FOURCC_AVI_` / `FOURCC_LIST` / `FOURCC_hdrl` / `FOURCC_avih` /
`FOURCC_strl` / `FOURCC_strh` / `FOURCC_strf` / `FOURCC_strn` / `FOURCC_vids` /
`FOURCC_auds` / `FOURCC_txts` / `FOURCC_movi` / `FOURCC_idx1` / `FOURCC_JUNK`。

⚠️ **没有 `INDX`（OpenDML 索引块）、没有 `AVIX`（OpenDML 扩展段）、没有 `DMLH`（扩展头）**。

### 1.2 没有 `idx1` → 直接宣告**不可 seek**

`Official_AviExtractor.java` L277-286：

```java
if (!seekMapHasBeenOutput) {
  if (checkNotNull(aviHeader).hasIndex()) {        // hasIndex() = avih.dwFlags 的 AVIF_HASINDEX
    state = STATE_FINDING_IDX1_HEADER;
    pendingReposition = moviEnd;
    return RESULT_CONTINUE;
  } else {
    extractorOutput.seekMap(new SeekMap.Unseekable(durationUs));   // ← 彻底不可 seek
    seekMapHasBeenOutput = true;
  }
}
```

📌 **这正是本项目 AVI 问题的官方根因**：
判据只看 `avih` 里的 `AVIF_HASINDEX` 标志位 —— 该位置 0（老编码器常不设），
官方就**整个视频不给 seek 能力**，而不会去尝试别的索引结构。

### 1.3 只支持**单个** `movi` 段 —— 不支持 OpenDML

官方只有一组 `moviStart` / `moviEnd`（L160-161，单值而非列表），
`readMoviChunks` 一读到 `moviEnd` 就 `RESULT_END_OF_INPUT`（L460-461）。

→ 对 **>1GB 的 OpenDML AVI**（必须切成 `movi` + `AVIX` 多段），官方**只能读第一段**。

### 1.4 idx1 的 offset 基准有**两种约定**，官方做了兼容

`peekSeekOffset()`（L429-447）：

```java
int offset = idx1Body.readLittleEndianInt();
// moviStart 指向 LIST 起点，而 seek offset 基于 movi 的 fourCC 起点，故 +8 对齐
long seekOffset = offset > moviStart ? 0L : moviStart + 8;
```

注释说明：规范说 offset 基于 `movi` 列表，但**有些文件用绝对文件位置** → 用 `offset > moviStart` 判。

### 1.5 未知 fourcc → **整条轨丢弃**（本项目已实测，14 个 fourcc）

见项目记忆 `topics/decoder-backends.md` §AVI fourcc。
官方 `StreamFormatChunk.getMimeTypeFromCompression` 的 `lookupswitch` 只有 14 个键，
`AV01` 不在其中 → 返回 null → 丢轨（「有声音无画面」）。

---

## 二、社区方案：`dburckh/Media3Avi`

**仓库**：https://github.com/dburckh/Media3Avi ｜**许可：MIT**（可自由使用/修改/闭源分发）
**最新 tag**：`2.7.1`（2025-07，对应 **Media3 1.7.1**）

READ 原话：*"Although ExoPlayer/Media3 now has AVI support, it is quite limited.
This should support most AVI files."*

### 2.1 它补了什么（**逐条对照官方缺口**）

| 能力 | 官方 | Media3Avi | 对应类 |
|---|---|---|---|
| `idx1` 索引 | ✅ | ✅ | `AviExtractor.parseIdx1()` |
| **OpenDML `indx` 索引块** | ❌ | ✅ | `IndexBox`（fourcc `INDX`=0x78646E69） |
| **OpenDML `ix##` 索引块** | ❌ | ✅ | `AviExtractor$IdxxBox` |
| **`AVIX` 多 movi 段** | ❌ | ✅ | `AviExtractor.moviList`（列表） |
| **`DMLH` 扩展头（总帧数）** | ❌ | ✅ | `ExtendedAviHeader`（fourcc `DMLH`） |
| **`rec ` 嵌套 LIST** | ❌ | ✅ | `AviExtractor$MoviBox` |
| **无索引时的兜底** | ❌（Unseekable） | ✅ | `ChunkIndex.getChunkSubset(durationUs, chunkRate)` |
| **更多 H264 fourcc** | 较少 | 更多（2.2.1 changelog） | `VideoFormat.getMimeType()` |
| **规避 Mp3Extractor 误判** | — | ✅ | `AviExtractorsFactory.patchExtractors()` |
| 未知 fourcc | 丢轨 | 丢轨 | 相同 |

### 2.2 三个值得学的**实现技巧**

**① `rec ` LIST 与提前退出**（`AviExtractor$RiffReader.read`）：

```java
if (type == MOVI) {
  addMovi(new MoviBox(...));
  if (riffType == AVIX || getIndexBoxList().size() > 0) {
    // 有 OpenDML 索引就直接结束，跳过 idx1（避免重复读、避免只拿到第一段）
    position = getEnd();
    return true;
  }
}
```

**② `size` 字段的 **bit31 是关键帧标志**（AVI 规范，`IdxxBox.read`）：

```java
final int size31 = size & 0x7f_ff_ff_ff;      // bit31 是「非关键帧」标记
chunkIndex.add(baseOffset + (offset & 0xffffffffL), size31, size == size31);
//                                                              ↑ 相等 = 关键帧
```

**③ 无索引时按时间**均匀生成稀疏关键帧**（`ChunkIndex.getChunkSubset(durationUs, chunkRate)`）——
不依赖任何索引，用「总时长 ÷ chunk 数 + 每 N 秒取一个」近似出可 seek 的点集。
⚠️ 这招**不保证落在真关键帧上**，是「有 seek 总比没有强」的兜底，不是精确解。

**④ idx1 offset 基准的**另一套**判据**（与官方不同，可对比）：

```java
if (indexByteBuffer.getInt(8) < firstChunkPos) baseOffset = firstChunkPos - 4;  // 相对 movi
else                                            baseOffset = 0L;                 // 绝对位置
```

### 2.3 用法（会**替换**官方 AVI extractor）

```kotlin
val exoPlayer = ExoPlayer.Builder(context)
    .setMediaSourceFactory(DefaultMediaSourceFactory(context, AviExtractorsFactory()))
    .setRenderersFactory(MjpegRenderersFactory(context))   // 可选：MJPEG
    .build()
```

`AviExtractorsFactory` 内部：从 `DefaultExtractorsFactory.createExtractors()` 里**移除**官方
`androidx.media3.extractor.avi.AviExtractor`，并在 `Mp3Extractor` **之前**插入自己的
（注释原文：*"Mp3Extractor falsely sniff()s AVI files"*）。

---

## 三、🔴 引入成本实测（**关键结论**）

### 3.1 二进制产物本身极轻

| 项 | 值 |
|---|---|
| jitpack AAR（`com.github.dburckh:Media3Avi:2.7.1`） | **55 KB** |
| 内含 | `classes.jar` 59KB / 35 个类；**无 native、无资源** |
| `AndroidManifest.xml` | 202 字节（**未声明 minSdk** → 继承项目 24）✅ |
| 源码许可 | MIT ✅ |

### 3.2 ⚠️ 但它会**强制升级整个项目的 Media3**

POM 依赖（实测下载 `jitpack_2.7.1.pom`）：

```xml
<dependency>androidx.media3 : media3-common    : 1.7.1 <scope>runtime</scope></dependency>
<dependency>androidx.media3 : media3-container : 1.7.1 <scope>runtime</scope></dependency>
<dependency>androidx.media3 : media3-extractor : 1.7.1 <scope>runtime</scope></dependency>
<dependency>androidx.media3 : media3-exoplayer : 1.7.1 <scope>runtime</scope></dependency>
```

**本项目当前是 `1.4.1`**（`app/build.gradle.kts` L392-422 共 11 处 media3 依赖）。
Gradle 的版本冲突解析会**取高版本** → 实际结果是：

> **引入这个库 = 把项目的整个 Media3 栈从 1.4.1 升到 1.7.1。**

这不是「加一个 55KB 的库」，而是**一次架构级升级**：
- 11 处 media3 依赖需同步升到 1.7.1
- `@UnstableApi` 的 API 在 1.4→1.7 之间**有过签名变更**，需全量回归
- 本项目对 Media3 的用法很深（Exo/IJK/SYSTEM 三内核、Transformer、SimpleCache、自定义 decoder）

### 3.3 版本对应关系（**没有匹配 1.4.1 的版本**）

| Media3Avi | 对应 Media3 | 时间 |
|---|---|---|
| **2.7.1** | **1.7.1** | 2025-07 |
| 2.3.0 | 1.1.1 | 2023-11 |
| 2.2.1 ~ 2.1 | Media3 beta03 ~ | 2022 |
| 1.1 / 1.0 | ExoPlayer 时代 | 2022 |

⚠️ 仓库**只打了 `2.7.1` 与 `2.3.0` 两个 tag**（README changelog 里的 2.4~2.6 未打 tag），
→ **没有任何版本对应 Media3 1.4.1**。最接近的两头都不合适
（1.1.1 太老、1.7.1 要连带升级）。

---

## 四、与本项目现有方案的对比

本项目现状（v2.4.9 起）：

| 组件 | 作用 |
|---|---|
| `AviRiffProbe.kt`（143 行，纯 JVM） | 探测 AVI 头部 + 是否含 `idx1`（带结构校验） |
| `VideoRemuxer.kt`（254 行） | **不重编码**重封装为 MP4，重建索引 |
| `VRPlayerScreen` | 无索引 → 第一次 seek 就触发重封装；失败则提示切 MPV |

**结论：现有方案在「正确性」上是成立的**，因为：

- 官方**只认 `idx1`** → 没 `idx1` 一律 `Unseekable`；
- 官方**不认 OpenDML** → 即使文件有 `indx`/`ix##`，官方**照样读不懂**；
- 所以「**没有可用索引 → 重封装成 MP4 重建索引**」这条路
  **同时覆盖了「完全没有索引」与「只有 OpenDML 索引」两种情况** —— 这是对的。

两种方案的本质差异只有**体验**：

| | 现有（重封装） | 引入 Media3Avi |
|---|---|---|
| 首次 seek 是否等待 | ⏳ 需等重封装（整文件处理） | ✅ 直接可 seek |
| 额外磁盘 | ⚠️ 需要一份新 MP4 | ✅ 无 |
| 能否提升**播放**兼容 | ❌ 不改（丢轨/多 movi 仍不行） | ✅ 多 movi 能读、H264 fourcc 更多 |
| 依赖成本 | 0 | ⚠️ **Media3 1.4.1 → 1.7.1** |
| 供应链风险 | 无 | 第三方单人维护 |

---

## 五、本次调研顺带发现的**潜在缺陷**（值得单独确认）

### ⚠️ 「有 idx1」≠「索引覆盖全文件」

`AviRiffProbe` 现在的判据是「**有没有** `idx1`」。

但 OpenDML AVI（>1GB）常见形态是：**`idx1` 只记录第一段，`indx`/`ix##` 才覆盖全部**。
这类文件会被我们判定为「有索引 → 无需重封装」，
而 Exo 又只支持单个 `movi` —— 结果就是**超过第一段的部分 seek 失效/播放中断**。

**建议的判据增强**（尚未实施，留待确认）：
拿到 `idx1` 后，**核对它覆盖的最后一个 chunk 位置是否接近文件尾**；
若 `idx1` 只覆盖明显小于文件的区间 → 视为「索引不完整」→ 同样走重封装（或提示切 MPV）。

---

## 六、建议

| 方案 | 说明 | 风险 |
|---|---|---|
| **A. 维持现状**（推荐） | 现有「探测 + 重封装」已覆盖两种索引缺失场景，零依赖 | 首次 seek 有等待成本 |
| **B. 修 `AviRiffProbe` 的「索引不完整」判据** | 见 §五。改动小（纯 JVM + 单测），能堵住一个真实盲区 | 低 —— **建议至少做这个** |
| **C. 引入 Media3Avi** | 免去重封装等待，且顺带提升播放兼容（多 movi / H264 fourcc） | ⚠️ 需把 Media3 升到 1.7.1，回归面大 |
| **D. 移植 OpenDML 解析到自己实现里** | 学它，但自己写 extractor | 工作量大；且**无法解决**多 movi 播放问题（那在官方 extractor 的读取逻辑里） |

---

## 附：证据文件清单

留档于 `.workbuddy/tmp/avi_research/`（可复核）：

| 文件 | 说明 |
|---|---|
| `Official_AviExtractor.java` | 官方 AviExtractor 全文（22KB） |
| `AviExtractor.java` | Media3Avi 版全文（22KB） |
| `AviSeekMap.java` / `ChunkIndex.java` / `IndexBox.java` / `ExtendedAviHeader.java` | 索引与 seek 核心 |
| `Media3Avi_AviExtractorsFactory.java` | 替换官方 extractor 的机制 |
| `jitpack_2.7.1.pom` / `jitpack_2.7.1.aar` | 依赖与产物实测 |
| `Media3Avi_LICENSE` / `Media3Avi_constants.gradle` | MIT 许可 / 目标 Media3 版本 |
