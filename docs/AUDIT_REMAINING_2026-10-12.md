# 审计文档剩余项核实报告 / Audit Remaining-Items Verification

> 生成时间：2026-10-12 · 基线：v2.4.39（versionCode 290）
> 范围：用户此前发来的 **7 份 v2.4.21 审计文档**（`D:/Downloads/`），合计 **106 项**。
> 方法：逐条读被指位置代码 + 对照 `docs/CHANGELOG.md` 与 `.workbuddy/memory/2026-10-*.md`。
> ⚠️ 本报告为**代码阅读**结论；未在真机复现（原文也是如此）。

---

## 一、总体状态

| # | 文档 | 项数 | ✅已修 | 🟡部分 | ❌仍存在 |
|---|---|---|---|---|---|
| 1 | Beauty Review（美颜） | 15 | **15** | 0 | 0 |
| 2 | Settings Panel Layout Review（设置面板） | 7 | **7** | 0 | 0 |
| 3 | Local AI Model Review（本地 AI） | 17 | **16** | 0 | 1（#14，已定暂缓） |
| 4 | **播放器内核审查** | 15 | 6 | 1 | **8** |
| 5 | **Local Subtitle Review（本地字幕）** | 15 | 3 | 0 | **12** |
| 6 | **视频容器转封装逻辑审查** | 21 | 1 | 4 | **16** |
| 7 | **Danmaku Review（弹幕）** | 16 | **16**（v2.4.40/41） | 0 | 0 |
| | **合计** | **106** | 64 | 5 | **37** |

> 📌 **进度更新（2026-10-12）**：**弹幕审查 16 项已全部完成** ——
> v2.4.40 修 #1/#2，v2.4.41 一次修完 #3~#16。
> ⚠️ 其中 #1 在 v2.4.40 首修时用了「虚拟时钟」，与入队方的原始 `elapsedRealtime` **时钟域不一致**
> （恢复后新入队条目会延迟「累计暂停」那么久才出现），**已在 v2.4.41 改为「暂停冻结 + 恢复时补偿 `bornMs`」**。

🔴 **弹幕 16 项一处未动**；本地字幕 / 内核 / 容器 只做过 v2.4.23 的「高危子集」+ 容器修复线。

---

## 二、逐条状态

### 4. 播放器内核审查（剩 8 项 + 1 部分）
| # | 标题 | 状态 | 代码证据（当前 v2.4.39） |
|---|---|---|---|
| 1,2,5,10,13,15 | release 真正释放 / observer 移除 / MPV 立即上报 / 位置轮询归属 / Surface 释放 / dvr-ms 长度 | ✅ v2.4.23 | `MediaFormats.kt:278,285` 已 `length <= 8` |
| 8 | EXO 出错没有跨内核兜底 | 🟡 部分 | `VRPlayerScreen.kt:3679-3685` 已加「源错误→MPV 重试一次」（v2.4.32）；但 **4002/4003 仍未纳入** 判定集 |
| 3 | 自动路由回退到 EXO 是空操作 | ❌ | 无 `failedEngines`/`rebuildNonce`；回退仍靠改 `decoderEngine` |
| 4 | 单文件出错永久改写全局内核偏好 | ❌ | `VRPlayerScreen.kt:3289/3375/3458` 仍 `decoderEngine = DecoderEngine.EXO` |
| 6 | 循环播放只对 EXO 生效 | ❌ | `:3606` 仅 ExoPlayer `REPEAT_MODE_ALL`；无 `setLooping` |
| 7 | mpvOptions 变化不触发重建 | ❌ | `:3828` key 无 `mpvOptions` |
| 9 | setupVideoPlayer 可能在 GL 线程执行 | ❌ | `:3774-3776` 回调内**直接**调 `setupVideoPlayer(...)`，未 post 主线程 |
| 11 | 续播 seek 发给未 prepare 的播放器 | ❌ | `:3835-3838` 重建后立即 `seekTo(resumeAt)` |
| 12 | release 在主线程同步执行 | ❌ | `:2940/3780/4110` 直接 `release()`，无后台执行器 |
| 14 | MpvLibLoader 下载库无完整性校验 | ❌ | `MpvLibLoader.kt:307-309` 仅 `isInstalled`（尺寸），无 SHA-256 / `setReadOnly` |

### 5. 本地字幕审查（剩 12 项）
| # | 标题 | 状态 | 代码证据 |
|---|---|---|---|
| 1,4,6 | 失败窗口不标已扫描 / 错误分类 / PCM 编码 | ✅ v2.4.23 | `RealtimeSubtitleEngine.kt:382/490` `failedRanges`；`:238-265` `AudioTeeOpenResult`；`:1217-1246` `KEY_PCM_ENCODING` 分支 |
| 2 | start() 先加载模型再验证音轨 | ❌ | `:220-222` 先 `factory.create()`，`:236-237` 才 `AudioTee.open()` |
| 3 | 旧协程仍读共享临时文件 | ❌ | `:1098` 固定名 `rt_asr_src.tmp` |
| 5 | 只尝试第一条音轨 | ❌ | `:1073-1078` `startsWith("audio/")` 即 `break` |
| 7 | 解码循环无超时/迭代上限 | ❌ | 无超时/迭代计数 |
| 8 | FFmpeg 兜底只挂 content 分支 | ❌ | `openViaFfmpegRemux()` 仅 1 处调用（content 分支） |
| 9 | 临时文件/转封产物无清理、key 可碰撞 | ❌ | `:1119` `asr_remuxed_${uri.hashCode()}.mkv`，无删除 |
| 10 | 窗口前导冲 + 边界 VAD 重置 → 断句/重叠 | ❌ | 无「窗口所有权（±2s + 只收 [from,to)）」 |
| 11 | 线性插值降采样无抗混叠 | ❌ | `:1330 resampleLinear` 无低通 / swr |
| 12 | 装箱 `ArrayList<Float>`，拼接 O(n²) | ❌ | `:771/1188` `ArrayList<Float>`；`:1292 appendFloat` |
| 13 | 时间分配产生零长 cue | ❌ | `:873` `coerceAtLeast(cursor+MIN_PIECE_MS).coerceAtMost(endMs)` 冲突仍在 |
| 14 | isRunning 新旧协程互相覆盖 | ❌ | `:126` 无 generation 守卫（当前无读取方，影响低） |
| 15 | 识别模型是否每次 start 重载 | ❌ | `SherpaAsrManager.createRecognizer` 无实例缓存 |

### 6. 视频容器转封装（剩 16 项 + 4 部分）
| # | 标题 | 状态 | 代码证据 |
|---|---|---|---|
| 1 | 成功路径返回 AVERROR_EOF | ✅ v2.4.27 | `ffremux_jni.cpp:180` `if (ret == AVERROR_EOF) ret = 0;` |
| 11,13,14,16 | B 帧丢样本 / 视频轨失败仍报成功 / 不交织 / 播放修复未接 FFmpeg | 🟡 部分 | v2.4.26 起 **startRemuxFix 改走 FFmpeg**（`VideoRemuxer` 仍服务 8K levelPatch）；v2.4.28 调用方加 `hasVideoTrack` 校验 |
| 2 | 丢弃 pts 或 dts 为空的包 | ❌ | `ffremux_jni.cpp:149` 仍 `\|\|` 即跳过；无 `AVFMT_FLAG_GENPTS` |
| 3 | 写失败计数与乱序假设 | ❌ | 无 `last_dts` 修正、无「连续失败」计数 |
| 4 | 不过滤流 | ❌ | 无流映射 / `avformat_query_codec` |
| 5 | 仍先白复制整个文件 | ❌ | `:1098 copyToTemp` 仍在 FFmpeg 之前 |
| 6 | smb/http 兜底是死代码 | ❌ | 网络分支不调 `openViaFfmpegRemux` |
| 7 | 缓存产物无原子性/校验/清理 | ❌ | 直写最终文件；无 `.part` + rename；无 LRU |
| 8 | native 循环不可取消 + 并发写同一文件 | ❌ | 无 `interrupt_callback` / `g_cancel` |
| 9 | loadLibrary 失败让引擎崩溃 | ❌ | `FfmpegRemuxer.kt:38-40` `init{ System.loadLibrary }` 无保护 |
| 10 | 只要音频却转封装整条视频 | ❌ | 无 `audioOnly`（**一次可同时缓解 #5/#7**） |
| 12 | 1MB 缓冲装不下大关键帧 | ❌ | `VideoRemuxer.kt:157` `allocate(1 shl 20)`；无按 `KEY_MAX_INPUT_SIZE` |
| 15 | 完成后写回的是当前条目 | ❌ | `startRemuxFix` 完成时未校验发起时快照 |
| 17 | idx1 >1MB 误判无索引 | ❌ | `AviRiffProbe.kt:135` `TAIL_BYTES=1MB` 尾部窗口仍在（文档给的「跳读 moviEnd」未采用） |
| 18 | AVIX 判据前缀写错 | ❌ | `:390-402` 仍要求 `LIST|size|AVIX`（真实为 `RIFF <size> AVIX`） |
| 19 | indx 豁免没校验条目数 | ❌ | `:235-262` 未检查 `nEntriesInUse`(+12) |
| 20 | 覆盖率分子分母单位不一致 | ❌ | `:368/373` 分子绝对位置 vs 分母相对 `moviStart` |
| 21 | emoji 路径 | ❌ | `ffremux_jni.cpp:68-69` 仍 `GetStringUTFChars` |

### 7. 弹幕审查（16 项 —— ✅ 已于 v2.4.40 / v2.4.41 全部完成）

> 下表是**核实当时**（v2.4.39）的状态，保留作记录。16 项现均已修复。
| # | 标题 | 代码证据 |
|---|---|---|
| 1 | 暂停时弹幕不冻结、恢复跳回 | `DanmuOverlay.kt:80-90` 帧回调无 `paused` 判断 |
| 2 | AI 弹幕暂停时仍请求模型 | AI 循环（`VRPlayerScreen.kt` ~2000-2106）无 `isVideoPlaying` 门控 |
| 3 | 自动行数开启仍按上限分配行 | `DanmuEngine.kt:441` `pickTrack` 用 `maxTracks`；`DanmuOverlay.kt:248-249` 未同步 `trackCount` |
| 4 | 改速度时屏上弹幕跳位 | `DanmuEngine.kt:87-88` 仍依赖未赋值的 `nowProvider` |
| 5 | 导入弹幕被去重吞掉 | `DanmuEngine.kt:295` `insertCandidates` 一律 `isDuplicate`，无 `dedupe` 参数 |
| 6 | 导入弹幕出生时间被打平、无位则丢 | `VRPlayerScreen.kt:2154-2156` 全部 `Triple(d.text, now, d.color)` 同一 `now` |
| 7 | 改不透明度对已缓存文字不生效 | `DanmuOverlay.kt:350` key `"$text|${style.fontSize}|$colorKey"` 不含全局 alpha |
| 8 | 每帧重组整个弹幕层 | `DanmuOverlay.kt:93` 在 Composable 主体读 `frameTimeMs` |
| 9 | 未上屏弹幕不测宽 | `DanmuOverlay.kt:262` 先 `continue`，`:282-283` 才回填宽度 |
| 10 | AI 入队时刻晚于截图时刻 | 无 `capturePositionMs`/`captureAtMs` |
| 11 | 导入存储非原子更新 | `DanmuImportStore.kt:87-90/101-104` items/cursor/uri 分字段顺序写 |
| 12 | 超 2 万条静默截断 | `DanmuImporter.kt:160/246` `break`，无 `truncated` |
| 13 | 颜色字符串解析有歧义 | `DanmuImporter.kt:286-300` 无 `#` 按十进制 |
| 14 | API Key 明文存储 + allowBackup | `VRPlayerScreen.kt:775/833`；`AndroidManifest.xml:118 allowBackup="true"` |
| 15 | 屏宽兜底分支永不触发 | `VRPlayerScreen.kt:2090/2148` `screenWidthPx <= 0f` 恒假 |
| 16 | 导入文件不绑定视频 | 仅 `LaunchedEffect(importedUri)`；无视频 URI 记录（**需产品确认是否预期**） |

---

## 三、建议修复顺序

**第 1 批（弹幕，用户直接可见 + 从未处理）**：#1+#2 暂停冻结/门控 → #3 行数同步 → #4 变速 → #5/#6 导入正确性 → #7/#8/#9 渲染 → #10/#11/#12/#13/#14/#15/#16。
**第 2 批（本地字幕正确性）**：#2 先验音轨 → #10 窗口所有权 → #11 抗混叠 → #7 超时 → #5 多音轨 → #3/#8/#9 工程性 → #12/#13/#14/#15 收尾。
**第 3 批（内核生命周期与回退）**：#12 release 移后台 + #13 Surface 生命周期（须与已修的 #1 配套）→ #9 回调转主线程 → #3+#4 引入 `failedEngines`/`rebuildNonce` → #8 补 4002/4003 → #6 循环 → #7 mpvOptions key → #11 续播 seek → #14 库校验。
**第 4 批（容器转封装 C++）**：#2+#3+#4（GENPTS + 时间戳修正 + 流映射）→ #10 `audioOnly`（一次缓解 #5/#7）→ #8 取消/串行 → #9 惰性加载 → #17+#18 改「跳读 moviEnd」→ #19/#20 → #12 → #15 → #21 emoji。

**依赖真机验证、排后**：字幕 #6/#10/#11（需样本文件）、容器 #17（需 30 分钟 AVI 样本）、内核 #6/#7（需多内核实测）。

**需产品拍板**：弹幕 #16（导入是否应绑定视频）、字幕 #15（识别模型是否缓存）。
