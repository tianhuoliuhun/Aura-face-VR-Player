# AV1 与 MPEG-4 支持优化方案（含 AVI 容器专题）

> 版本：v2.1.247 方案稿
> 日期：2026-10-05
> 性质：**诊断 + 方案**（本文件成稿时**尚未改任何代码**）

---

## 〇、先说结论（免得看半天）

**如果拿到的是一个 `.avi` 文件 —— 那大概率不是「AV1 支持不行」，而是「AVI 容器里的 AV1 根本没人认」。**

本项目实测已确认三条硬事实：

| # | 事实 | 证据 |
|---|---|---|
| 1 | EXO 的 `AviExtractor` **只认 5 种视频编码**，`AV01` 不在其中 | 反编译 `media3-extractor-1.4.1` 的 `StreamFormatChunk.getMimeTypeFromCompression` |
| 2 | IJK **完全没有 AVI demuxer**，任何 AVI 都不能给它 | `libijkplayer.so` 里 `ff_avi_demuxer` / `ff_riff_demuxer` 命中 0 |
| 3 | MPV **有完整 avformat**，`AVI (Audio Video Interleaved)` demuxer 在，且认 `V_AV1` | `libavformat.so` 字符串实测 |

所以：

- **AVI + MPEG-4（Xvid/DivX/DX50/MP42/MP43）→ 本项目已经能播**（走 EXO，见下表），无需改动。
- **AVI + AV1 → 目前播不了**，因为 EXO 的 AVI extractor 看到 fourcc `AV01` 会直接
  `Ignoring track with unsupported compression AV01` 然后**丢掉整条视频轨**。
  唯一出路是 **MPV**（它有完整 FFmpeg），但**前提是 AVI 内的 AV1 能被 libavformat 正确识别**。

---

## 一、编码格式识别

### 1.1 本项目的识别链路（两个层次，别混淆）

**层次 A：按扩展名/容器判「放不放行、给哪个内核」** —— `MediaFormats.kt`（单一判定入口）

```kotlin
// 当前 COMMON 里的 MPEG-4 家族扩展名（L103）
"mp4", "m4v", "mov", "3gp", "3g2", "3gpp", "3gpp2", "ismv", "f4v"
// AVI 家族（L107）
"avi", "divx"
// ⚠️ AV1 专属扩展名：零登记
```

**层次 B：按 MIME 判「显示什么编码名」** —— `VideoInfo.kt`（只用于 UI 展示）

```kotlin
// VideoInfo.kt L92-116
MimeTypes.VIDEO_AV1  -> "av1"      // ✅ 已有（L96）
MimeTypes.VIDEO_MP4V -> "mpeg4"    // ✅ 已有（L99）
```

⚠️ **这里是本项目的一个关键认知点**：`VideoInfo.kt` 认识 `video/av01`，**不等于**能播 AV1。
它只是 ExoPlayer 成功解析出这条轨道之后，把 `Format.sampleMimeType` 翻成人话。
**轨道压根没被解析出来时，这个分支永远不会执行。**

### 1.2 容器里的编码是靠什么识别的 —— fourcc

AVI 是 RIFF 容器，每条流在 `strf` chunk 里带一个 **4 字节 fourcc**（如 `XVID`、`H264`）。
解码器必须靠这张映射表把 fourcc 翻译成 MIME。

**EXO（Media3 1.4.1）的完整映射表**（反编译实测，14 个 fourcc → 5 个 MIME）：

| fourcc | 含义 | 映射 MIME | 本项目能否播 |
|---|---|---|---|
| `H264` `AVC1` `avc1` | H.264 | `video/avc` | ✅ |
| `MP42` | MS MPEG-4 v2 | `video/mp42` | ✅（走 EXO 的 mp4v 软/硬解） |
| `MP43` | MS MPEG-4 v3 | `video/mp43` | ✅ |
| `DX50` `XVID` `DIVX` `DIV3` `FMP4` `xvid` `divx` | MPEG-4 ASP | `video/mp4v-es` | ✅ |
| `MJPG` `mjpg` | Motion JPEG | `video/mjpeg` | ✅ |
| **`AV01` / `av01`** | **AV1** | **❌ 不在表里** | **❌ 整轨被丢弃** |

证据（javap 反编译 `StreamFormatChunk.class`）：

```
private static java.lang.String getMimeTypeFromCompression(int);
   0: iload_0
   1: lookupswitch  { // 14
        808802372: 133     // DX50 → video/mp4v-es
        826496577: 130     // AVC1 → video/avc
        828601953: 130     // avc1 → video/avc
        842289229: 124     // MP42 → video/mp42
        859066445: 127     // MP43 → video/mp43
        875967048: 130     // H264 → video/avc
        877677894: 133     // FMP4 → video/mp4v-es
       1145656883: 133     // DIV3 → video/mp4v-es
       1145656920: 133     // XVID → video/mp4v-es
       1196444237: 136     // MJPG → video/mjpeg
       1482049860: 133     // DIVX → video/mp4v-es
       1684633208: 133     // xvid → video/mp4v-es
       1735420525: 136     // mjpg → video/mjpeg
       2021026148: 133     // divx → video/mp4v-es
         default: 139      // → null（未识别）
      }
   139: aconst_null
   140: areturn
```

**`AV01`（0x31305641）/ `av01`（0x31307661）都不在 14 个 case 里** → 走 default → 返回 `null`
→ 上层 `Log.w("StreamFormatChunk", "Ignoring track with unsupported compression AV01")`
→ **视频轨被整条忽略**，只剩下音频，播放器表现为「有声音没画面」或直接报错。

### 1.3 正确的识别姿势（推荐做法）

不要只信扩展名，也不要只信 `MediaMetadataRetriever`。推荐**三级探测**：

```kotlin
// 伪代码：三级探测，逐级放宽
fun probeVideoCodec(context: Context, uri: Uri): String? {
    // 1) 容器层（快，无需解码）：MediaExtractor 给的是容器声明的 codec
    MediaExtractor().use { ex ->
        ex.setDataSource(context, uri, null)
        for (i in 0 until ex.trackCount) {
            val fmt = ex.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                // ⚠️ 注意：AVI 里 MPEG-4 常报成 "video/mp4v-es"；AV1 必须是 "video/av01"
                //    若容器不声明 mime（老 AVI 常见），这里会拿不到 → 落第 2 级
                return mime
            }
        }
    }
    // 2) 帧层（慢，要解一帧）：MediaMetadataRetriever
    //    METADATA_KEY_MIMETYPE 对部分容器比 MediaExtractor 更宽容
    val mmr = MediaMetadataRetriever()
    try {
        mmr.setDataSource(context, uri)
        return mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
    } finally { mmr.release() }
    // 3) 兜底：交给内核自己试（见第三节的解码方式选择）
}
```

**关键判断**：
- `video/av01` → AV1（只有它能触发 AV1 硬解走 `c2.android.av1.decoder` / `OMX.qcom.video.decoder.av1`）
- `video/mp4v-es` → MPEG-4 ASP（Xvid/DivX 的容器内 MIME）
- `video/mp42` / `video/mp43` → MS MPEG-4 v2/v3（**不是** MPEG-4 ASP，硬解器通常不认，只能软解）
- 拿不到 MIME → **不要猜**，直接让内核试并读它的错误码（见 1.4）

### 1.4 一个必须知道的坑：fourcc 大小写

AVI 里 fourcc 是 4 个字节，**大小写敏感**。libavformat 的 `codec_tag` 表里 `XVID` 与 `xvid`、
`DIVX` 与 `divx` 是不同的 key（EXO 的 14 项里两套都在，说明官方踩过这个坑）。
自研映射表时**必须把两套都列上**，或者统一转大写后再查。

---

## 二、解码方式选择

### 2.1 四个内核的真实能力（v2.1.246 实测，勿凭印象）

| 编码 | EXO (Media3 1.4.1) | IJK（裁剪 FFmpeg） | MPV（全量 FFmpeg） | SYSTEM（MediaPlayer） |
|---|---|---|---|---|
| **AV1** | ✅ 硬解（设备门控，走 MediaCodec） | ❌ 白名单里无 av1 | ✅ `libdav1d` + `av1dec` | ⚠️ 看 ROM |
| **MPEG-4 ASP** | ✅ `video/mp4v-es` 硬解 | ✅ 软解（`mpeg4` 在 28 个 decoder 里） | ✅ 软解 + `mpeg4_mediacodec` | ⚠️ 看 ROM |
| **MS MPEG-4 v2/v3** | ✅ `video/mp42`/`mp43` | ⚠️ 未列入 | ✅ `msmpeg4v1/2/3` | ❌ |
| **AVI 容器** | ✅ `AviExtractor` | ❌ **无 demuxer** | ✅ `AVI (Audio Video Interleaved)` | ⚠️ |

### 2.2 AV1 的解码路径（三档阶梯）

```
① EXO + MediaCodec 硬解
   ├─ 条件：设备有 AV1 硬解器（Android 上从骁龙 8 Gen 1 / Tensor G1 起）
   ├─ 探测：MediaCodecList.findDecoderForFormat(MediaFormat.createVideoFormat("video/av01", w, h))
   └─ 失败 ↓
② MPV + libdav1d 软解
   ├─ 条件：MPV native 库已下载（MpvLibLoader，约 36MB）
   ├─ 代价：软解 1080p AV1 在手机上约 3~8 核占用，4K 基本跑不动
   └─ 失败 ↓
③ 明确报错，不黑屏
   └─ 提示「本片为 AV1，设备无硬解且 MPV 未安装」
```

**⚠️ 本项目当前的一个真实缺口**：`VRPlayerScreen.kt` 的内核自动路由（L2415-2464）
只有两条判据 —— `shouldRouteToMpv(ext)` 和 `shouldAutoRouteToIjk(ext)`，
**两者都只看扩展名，不看编码**。

这意味着：一个 `.mp4` 封装的 AV1，在**没有 AV1 硬解**的老设备上，
会走 EXO → MediaCodec 找不到解码器 → 报错，**不会自动降级到 MPV**。

→ 这是值得修的一条：**按编码（而非容器）决定是否降级到 MPV**。

### 2.3 硬解探测必须运行时做，不能信设备名

```kotlin
// 正确：问系统要解码器，不要维护机型白名单
fun hasAv1HardwareDecoder(): Boolean {
    if (Build.VERSION.SDK_INT < 29) return false  // AV1 硬解最早 Android 10 出现
    val fmt = MediaFormat.createVideoFormat("video/av01", 1920, 1080)
    return try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .findDecoderForFormat(fmt)?.let { name ->
                // 排除纯软解器（c2.android.av1.decoder 是软解！）
                !name.contains("c2.android", true) && !name.startsWith("OMX.google.", true)
            } ?: false
    } catch (e: Exception) { false }
}
```

⚠️ **`c2.android.av1.decoder` 是 AOSP 的软解器**，很多设备都有它 ——
如果不排除，会误判成「有 AV1 硬解」，实际播放时 CPU 跑满、掉帧。
**AV1 硬解器的名字特征**：`c2.qti.av1.decoder`（高通）、`c2.exynos.av1.decoder`（三星）、
`OMX.MTK.VIDEO.DECODER.AV1`（联发科）、`c2.android.av1.decoder` ← **这个是软的，要排除**。

### 2.4 MPEG-4 的解码选择

MPEG-4 ASP（Xvid/DivX）情况简单得多：

- **`video/mp4v-es`（fourcc XVID/DIVX/DX50）** → 硬解普及率高，EXO 直接走 MediaCodec 即可。
- **`video/mp42`/`video/mp43`（MS MPEG-4 v2/v3）** → 这是 90 年代的老编码，
  **大多数现代硬解器都不支持**，只能软解。EXO 会走 `c2.android.mpeg4.decoder`（软解），
  1080p 下 CPU 单核就能扛，问题不大。
- **AVI 容器中的 MPEG-4** → 必须走 EXO（IJK 无 AVI demuxer，见 2.1）。

---

## 三、seek（进度条拖动）实现

### 3.1 原理：为什么 seek 有时候不准

视频是**关键帧（I 帧）+ 预测帧（P/B 帧）**的结构。
拖到 `t = 37.4s` 时，如果 37.4s 不是关键帧，解码器有两种选择：

| 策略 | 做法 | 精度 | 速度 |
|---|---|---|---|
| **关键帧 seek**（默认） | 跳到 37.4s **之前最近的关键帧**（如 35s） | 差（可能差半个 GOP） | 快 |
| **精确 seek** | 跳到 35s，然后**解码并丢弃**到 37.4s 的所有帧 | 准 | 慢（要解码一段） |

**AV1 和 MPEG-4 在这个问题上没有本质区别**，但有两个实际差异：

1. **GOP 长度**：AV1 通常 GOP 更长（8~16 秒），关键帧间隔稀疏 → 关键帧 seek 的误差更明显。
2. **索引表**：MP4 有 `stss`（sync sample table）精确定位关键帧；
   AVI 有 `idx1` 索引（EXO 的 `AviSeekMap` 就是读它）。

### 3.2 本项目的现状（四个后端各自的实现）

| 后端 | seek 实现 | 精度 | 位置 |
|---|---|---|---|
| **EXO** | `player.seekTo(ms)` | **默认关键帧**（未设 `SeekParameters`） | `VrPlayerBackend.kt:67` |
| **IJK** | `mp.seekTo(ms)` + `enable-accurate-seek=1` | ✅ 精确 | `IjkPlayerBackend.kt:142` |
| **MPV** | `command("seek", sec, "absolute")` | ✅ 精确（ffmpeg 默认精确） | `MpvPlayerBackend.kt:382` |
| **SYSTEM** | `mp.seekTo(ms, SEEK_CLOSEST)` (API 26+) | ✅ 精确 | `SystemPlayerBackend.kt:200` |

**⚠️ 发现的不一致**：**只有 EXO 没开精确 seek**。

EXO 的默认 `SeekParameters.DEFAULT` 是 `EXACT`（Media3 后来改过默认值），
但为了确定性，**应该显式设**：

```kotlin
// VRPlayerScreen.kt，ExoPlayer.Builder 之后（约 L2807）
.setSeekParameters(SeekParameters.EXACT)   // 精确 seek，拖动到哪就是哪
// 或 SeekParameters.CLOSEST_SYNC          // 快速 seek，容忍关键帧误差
```

**但要小心**：`EXACT` 在长 GOP 的 AV1 上，每次拖动都要解码从关键帧到目标点的所有帧，
**拖动过程中会卡顿**。推荐做法：

```kotlin
// 拖动中（onValueChange）→ CLOSEST_SYNC，跟手、不卡
// 松手后（onValueChangeFinished）→ EXACT，落点精确
```

### 3.3 拖动中的两级 seek（本项目已有一半）

本项目已经在 `onValueChangeFinished` 里做了「seek 后校验」：

```kotlin
// VRPlayerScreen.kt L4003-4024
onValueChangeFinished = {
    isSeekingActive = false
    val seekTarget = hoverTimeMs
    scope.launch {
        delay(1000L)
        if (seekTarget > 3000L) {
            val pos = playerInstance?.currentPosition ?: -1L
            if (pos < 2000L && !seekUnsupported && !isRemuxing && selectedMediaItem.isVideo) {
                startRemuxFix()   // ← 位置回 0 = 容器不支持 seek → 自动重封装
            }
        }
    }
    ...
}
```

这个「**位置回 0 = seek 失败**」的判据很重要，它覆盖了最恶劣的情况：
**容器没有索引表**（如某些 AVI/MKV 用 `moov` 在文件尾且无索引）。

⚠️ **但它对 AVI + AV1 无效** —— 因为 AVI+AV1 是「轨道压根没解析出来」，
不是「seek 失败」，会走到别的错误路径（见 3.4）。

### 3.4 容器无索引时的 seek（线性估算）

有些 AVI（尤其从老设备导出的）**没有 `idx1` chunk**。
EXO 的 `AviExtractor` 在这种情况下 `isSeekable()` 仍返回 `true`
（因为它可以在 chunk 里线性扫描），但**每次 seek 都要从头扫**，极慢。

**处理办法（本项目已有雏形）**：重封装成 MP4。
`VideoRemuxer.remux()` 会把原片**不重编码**地塞进新 MP4 容器，重建 `moov` 索引。
这正是 `startRemuxFix()` 做的事 —— 而且**不重编码**，速度只受 IO 限制。

**建议的扩展**：把「容器不支持 seek」的判据从「位置回 0」
扩展到「seek 耗时 > 阈值」：

```kotlin
val t0 = SystemClock.elapsedRealtime()
playerInstance?.seekTo(seekTarget)
// ...1 秒后...
if (SystemClock.elapsedRealtime() - t0 > 3000 && pos < seekTarget - 5000) {
    // seek 慢且没到位 → 也是容器问题，同样触发重封装
    startRemuxFix()
}
```

---

## 四、拖动时的加载与缓冲处理

### 4.1 拖动预览缩略图（本项目已做得不错，v2.0.180）

当前实现（`VRPlayerScreen.kt` L1466-1545）：

```kotlin
val seekThumbCache = remember { object : LruCache<String, Bitmap>(24) {} }  // 约 24 张 @320×180
LaunchedEffect(hoverTimeMs, selectedMediaItem.uri, isHoverActive) {
    // ① 节流：SEEK_THUMB_THROTTLE_MS 内不重复抓帧
    if (now - lastSeekThumbAt < SEEK_THUMB_THROTTLE_MS) return@LaunchedEffect
    // ② 时间量化：对齐到 SEEK_THUMB_QUANTUM_MS，提高命中率
    val quantizedMs = (hoverTimeMs / SEEK_THUMB_QUANTUM_MS) * SEEK_THUMB_QUANTUM_MS
    // ③ key 带视频标识，避免跨片串味
    val cacheKey = "${uriStr.hashCode()}:$quantizedMs"
    // ④ 缓存命中直接返回
    // ⑤ 未命中 → MediaMetadataRetriever.getFrameAtTime(OPTION_CLOSEST_SYNC)
}
```

**三个优化点已经到位**：节流 + 时间量化 + 跨片 key。这套设计对 AV1/MPEG-4 同样有效。

⚠️ **一个针对 AV1 的注意点**：`MediaMetadataRetriever.getFrameAtTime` 对 AV1 支持
**取决于设备的 MediaCodec 是否有 AV1 解码器**。如果设备无 AV1 硬解，
`MediaMetadataRetriever` 拿不到帧 → 缩略图区域空白。
**建议**：抓帧失败时显示一个「AV1 预览不可用」的占位，而不是留空白让人以为卡了。

### 4.2 seek 时的缓冲策略

拖动产生的数据跳转会造成「**buffer 全废**」——已缓冲的 0~30s 数据对 37s 之后的播放毫无用处。

| 后端 | 缓冲参数 | 本项目配置 |
|---|---|---|
| **EXO** | `DefaultLoadControl`（未自定义） | 默认：min 50s / max 50s |
| **MPV** | `cache=yes` + `demuxer-max-bytes` | `mpv_cache_mb`（默认 0 = 关闭） |
| **IJK** | `packet-buffering` | 见 `IjkPlayerBackend` |

**关键优化：seek 后不要从目标点开始缓冲，要从目标点前 5~10 秒开始。**
原因是需要**重新解码一个关键帧**：
如果 seek 到 37.4s，恰好落在两个关键帧之间，播放器需要从 35s 的关键帧开始解 ——
所以缓冲应该覆盖 `[关键帧起点, 目标点]`，否则会立刻卡住。

EXO 内部已经这样做了（`SeekParameters` 会向前找 sync point），
**MPV 需要显式设 `hr-seek=yes`**（本项目当前用的是绝对 seek，MPV 会自动处理）。

---

## 五、常见浏览器与设备的兼容性

> ⚠️ 本节说的是 **Web 端 / 其他设备**，与 Android 原生播放器无关，供参考。

### 5.1 浏览器 AV1 支持矩阵（2026 现状）

| 浏览器 | AV1 硬解 | AV1 软解 | 备注 |
|---|---|---|---|
| **Chrome 70+** | ✅（有硬解时） | ✅ `dav1d` | 覆盖最全 |
| **Firefox 67+** | ✅ | ✅ `dav1d`（默认） | 软解性能最好 |
| **Edge 79+ / 121+** | ✅ | ✅ | 基于 Chromium |
| **Safari** | ⚠️ **仅在 M3+/A17 Pro+ 设备** | ❌ | iOS/iPadOS Safari **完全不支持 AV1** |
| **iOS Safari** | ❌ | ❌ | 硬缺口，只能靠 HEVC/H.264 降级 |

**全局浏览器 AV1 覆盖约 90~93%**（桌面接近满，移动端受 iOS 拖累）。

### 5.2 设备 AV1 硬解（Android / PC）

| 平台 | 起始硬件 |
|---|---|
| **高通骁龙** | 8 Gen 1 / 8 Gen 2+（含部分 7 Gen 系列） |
| **Google Tensor** | G1 / G2+（Pixel 6 / 7 起） |
| **联发科天玑** | 9000+ / 1000+ 系列 |
| **三星 Exynos** | 2200+ |
| **Apple** | M3+ / A17 Pro / A18 |
| **Intel** | 11 代酷睿+ |
| **NVIDIA** | RTX 30 系+ |
| **AMD** | RX 6000 系+ |

约 **80~88% 的 2021 年后设备**有 AV1 硬解。

### 5.3 兼容性降级阶梯（推荐）

```
AV1 → HEVC (H.265) → H.264 (AVC)
 ↑         ↑              ↑
最新     普及率高      全覆盖
```

**分发建议**：
- 视频内容同时提供 **AV1（省带宽）+ H.264（保兼容）** 两版，
  用 `<source>` 顺序降级：
  ```html
  <video>
    <source src="v.av1.mp4"  type='video/mp4; codecs="av01.0.05M.08"'>
    <source src="v.h264.mp4" type='video/mp4; codecs="avc1.640028"'>
  </video>
  ```
- **MPEG-4 ASP（Xvid/DivX）不要用于 Web 分发** —— 浏览器基本不支持，
  只适合本地/老设备播放。

---

## 六、本项目可落地的改动清单（建议，尚未执行）

按「收益 / 风险」排序：

| # | 改动 | 文件 | 收益 | 风险 |
|---|---|---|---|---|
| **1** | **exoplayer 显式设 `SeekParameters`**：拖动中 `CLOSEST_SYNC`、松手 `EXACT` | `VRPlayerScreen.kt` ~L2807 | 拖动跟手、落点精确 | 低 |
| **2** | **AV1 按编码降级**：EXO 播放时若 `sampleMimeType == video/av01` 且无硬解器 → 提示或切 MPV | `VRPlayerScreen.kt` 内核路由段 | 老设备也能播 AV1 | 中（需实测） |
| **3** | **AVI + AV1 明确提示**：EXO 解析后若视频轨缺失但容器是 avi → 提示「AVI 内的 AV1 需 MPV 内核」 | `VRPlayerScreen.kt` + `strings.xml` ×5 | 用户知道该干嘛，而不是黑屏 | 低 |
| **4** | **缩略图抓帧失败占位**：`getFrameAtTime` 返回 null 时显示占位图 | `VRPlayerScreen.kt` L1524 | 不会让人误以为卡了 | 低 |
| **5** | `MediaFormats.COMMON` 补 AV1 相关扩展名（`.av1`/`.obu`/`.ivf`） | `MediaFormats.kt` L101 | 裸 AV1 流能被识别 | 低（但要注意：这些是裸流，不是容器） |

⚠️ **第 5 项的注意点**：`.av1` / `.obu` / `.ivf` 是 **AV1 裸流 / 简单容器**，
不是 mp4/webm/mkv。**实际遇到的 AV1 绝大多数封装在 mp4/webm/mkv 里**，
扩展名是 `.mp4`/`.webm`/`.mkv`（已在 COMMON 里）。
所以第 5 项收益有限，**优先级最低**。

---

## 七、验证方法（改完后怎么确认）

1. **造测试样本**（ffmpeg，无需重新编码时优先 `-c copy`）：
   ```bash
   # AVI + MPEG-4（应能播）
   ffmpeg -i src.mp4 -c:v mpeg4 -vtag XVID -c:a mp3 out_xvid.avi
   # AVI + AV1（EXO 会丢轨，测降级）
   ffmpeg -i src.mkv -c:v libsvtav1 -c:a libmp3lame out_av1.avi
   # MP4 + AV1（主测 seek）
   ffmpeg -i src.mkv -c copy out_av1.mp4
   ```
2. **看 logcat 关键行**：
   - `StreamFormatChunk: Ignoring track with unsupported compression AV01` → 命中本方案的根因
   - `VRPlayerScreen: 容器 .xxx 需完整 FFmpeg，本次自动改用 MPV 内核` → 路由生效
3. **seek 精度**：拖到整 10 秒，看 `currentPosition` 是否 ≈ 10000ms（±200ms）。

---

## 附：本文件的实证来源

| 结论 | 命令 / 方法 |
|---|---|
| EXO AVI 只认 5 种编码 | `javap -p -c -constants StreamFormatChunk.class` 解 `lookupswitch` |
| fourcc 键值 | 14 个整数常量按小端解 4 字符 |
| IJK 无 AVI demuxer | `libijkplayer.so` 搜 `ff_avi_demuxer` = 0 命中 |
| MPV 有 AVI + AV1 | `libavformat.so` 搜 `AVI (Audio Video Interleaved)` / `V_AV1` |
| EXO 无 seek 参数 | `VRPlayerScreen.kt` 搜 `setSeekParameters` = 0 命中 |
| 四后端 seek 实现 | 逐文件读 `seekTo` 实现 |
