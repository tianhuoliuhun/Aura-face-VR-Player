# 方案 C 实施计划：AI 弹幕（借鉴自研）

> **目标**：为 Aura-face-VR-Player 新增 **AI 弹幕**能力 —— 截取当前播放画面 → 调用视觉模型理解内容 → 生成弹幕 → 在 VR 分屏上滚动展示。
> **来源**：借鉴 `PEPETII/danmuai` 的**设计思路**（思想不受 GPL 约束），**不复制任何代码**。
> **计划日期**：2026-10-06
> **当前版本基线**：v2.1.248（versionCode 248）
> **预计目标版本**：v2.2.0（功能级新增，建议走次版本号）
> **配套报告**：`docs/DANMUAI_INTEGRATION_ASSESSMENT_2026-10-06.md`

---

## 零、前置结论回顾（为什么这么做）

| 为什么不用方案 B（LAN 握手） | 为什么不用方案 D（代码复用） |
|---|---|
| 需读 DanmuAI 落盘历史文件，属**脆弱逆向**，其 v0.5.0 格式无稳定性承诺 | GPL-3.0-or-later 第 5(c) 条要求「entire work, as a whole」→ 本项目必须整体开源 |
| 且 DanmuAI 是「看桌面窗口」，本项目是「看 VR 分屏」，轨道算法本就不适配 | 对商业 VR 播放器 = 放弃商业模式 |

**方案 C 的两个核心优势**：
1. **零许可证风险** —— 只借鉴思想，不复制表达
2. **深度适配 VR** —— 可做双眼独立渲染、华为 VR Glass 优化，这是拿一个 Windows 程序永远做不到的

---

## 一、现状盘点：本项目已有 vs 需要新建

### 1.1 ✅ 已有可复用基建（实测确认）

| 能力 | 位置 | 可复用度 |
|---|---|---|
| **OkHttp 客户端** | `SubtitleTranslator.kt:745` `private val httpClient = OkHttpClient.Builder()...` | 🟢 直接复用配置范式 |
| **OpenAI 兼容调用** | `SubtitleTranslator.kt:1266-1320` `translateViaOpenAiApi()` | 🟢 **范式可直接照搬** |
| **多平台引擎枚举** | `SubtitleTranslator.kt:33` `enum class TranslationEngine` | 🟢 已有 deepseek / qwen / glm / mimo / openai / custom |
| **自定义 baseUrl + model** | `SubtitleTranslator.kt:787,791` `getActiveBaseUrl()` / `getActiveModel()` | 🟢 逻辑可平移 |
| **Bitmap 缩放** | `VRPlayerScreen.kt:7885` `Bitmap.createScaledBitmap(src, scaledW, scaledH, true)` | 🟢 已有 |
| **GL 降采样 FBO** | `VRGLRenderer.kt:2894` `ensureGpHalfFbo()` + `glBlitFramebuffer` | 🟢 **VR 帧回读的正确姿势已实现** |
| **Compose 浮层范式** | `SubtitleOverlay.kt:78` `fun SubtitleOverlay(...)`（271 行） | 🟢 弹幕层可照此结构写 |
| **设置持久化范式** | `VRPlayerScreen.kt:655` `XxxOption.values().find { it.id == id }` | 🟢 遵循既有三处改动规则 |

### 1.2 ❌ 完全没有、需要新建

| 缺口 | 说明 | 严重度 |
|---|---|---|
| **弹幕引擎** | 全仓 grep `danmu`/`弹幕` = **0 命中** | 🔴 从零 |
| **弹幕渲染层** | 无轨道分配、无滚动、无去重 | 🔴 从零 |
| **视觉模型调用** | 现有 `translateViaOpenAiApi` 只发**纯文本**，无 `image_url` | 🟠 需扩展 |
| **base64 图片编码** | 全仓无 `Base64` 使用 | 🟠 需新增 |
| **VR 帧回读给业务层** | `glReadPixels` 只服务于美颜，未暴露给 UI 层 | 🟠 需开入口 |
| **多模态设置项** | 现有设置是「翻译引擎」，非「视觉模型」 | 🟠 需新增 |

### 1.3 ⚠️ 三条必须遵守的项目红线（来自记忆）

1. **「同一功能两份 UI」是头号事故源** —— 动手前先 grep 同型写法，副本 ≥2 就直接抽成单一实现
2. **设置持久化必须同时改三处** —— 写回 `LaunchedEffect` 的 **key 列表** + `put*` 写回块 + `else` 分支的 `remove(...)`；漏 key = 永不落盘
3. **改任何已有文件前必须先备份** —— 放 `.workbuddy/tmp/src_bak_archive/<时间戳>/`，**绝不**在 `res/` 同目录留 `.bak`

---

## 二、总体架构

```
┌─────────────────────────────────────────────────────────────┐
│  DanmuOrchestrator（编排器，新增）                            │
│  职责：定时触发 → 取帧 → 调模型 → 解析 → 喂引擎 → 上屏          │
└───────┬──────────────┬──────────────┬───────────────────────┘
        │              │              │
        ▼              ▼              ▼
┌───────────────┐ ┌──────────────┐ ┌──────────────────┐
│ DanmuFrameSrc │ │ DanmuVision  │ │  DanmuEngine     │
│ （取帧）       │ │ Client       │ │  （弹幕调度）      │
│               │ │ （调模型）    │ │                  │
│ 优先：GL 帧    │ │              │ │ · 轨道分配        │
│ 回退：占位图   │ │ 复用 OkHttp   │ │ · 速度控制        │
│               │ │ + image_url  │ │ · Levenshtein 去重│
└───────────────┘ └──────────────┘ └────────┬─────────┘
                                             │
                                             ▼
                                   ┌──────────────────┐
                                   │ DanmuOverlay     │
                                   │ （Compose 渲染）  │
                                   │ 支持 VR 分屏双眼   │
                                   └──────────────────┘
```

**设计原则**：
- **单向数据流**：编排器 → 引擎 → 渲染，渲染层**只读**，不反向修改引擎
- **失败不阻断播放**：任何环节失败只影响弹幕，绝不影响视频解码/渲染主链路
- **与字幕层解耦**：`SubtitleOverlay` 已完成独立（v120），弹幕层**新增独立文件**，不侵入它

---

## 三、分阶段实施

### Phase 1 —— 骨架与设置项（可独立验收）

**目标**：能配置、能开关、能持久化，但还没有实际弹幕。

| # | 任务 | 文件 | 说明 |
|---|---|---|---|
| 1.1 | 新增 `DanmuConfig.kt` | 新建 | 数据类：`enabled` / `intervalSec` / `apiKey` / `baseUrl` / `modelName` / `personaPrompt` / `batchSize` / `opacity` / `fontSize` / `speed` / `maxTracks` |
| 1.2 | 新增设置面板 `DanmuSettingsPanel.kt` | 新建 | 照 `SubtitleSettingsPanel.kt` 结构；**先 grep 确认无同类副本** |
| 1.3 | 设置项持久化 | 改 `VRPlayerScreen.kt` | ⚠️ **必须三处同步**：key 列表 / `put*` 块 / `else` 的 `remove(...)` |
| 1.4 | 新增字符串 × 5 语 | 改 5 个 `strings.xml` | `values` / `-en` / `-ja` / `-ko` / `-zh-rTW` |
| 1.5 | 设置入口接线 | 改 `VRPlayerScreen.kt` | 挂到设置面板，加开关 |

**验收**：开关能记住、重启后保持、5 语都显示正常中文/对应语言。

**风险**：🔴 设置持久化的三处漏改（本项目头号坑）→ 必须逐项核对。

---

### Phase 2 —— 视觉模型调用（可独立验收）

**目标**：能把手上一张图发给视觉模型、拿到弹幕文本。

| # | 任务 | 文件 | 说明 |
|---|---|---|---|
| 2.1 | 新增 `DanmuVisionClient.kt` | 新建 | 复用 `SubtitleTranslator` 的 OkHttp 构建范式（超时、重试） |
| 2.2 | 实现 `image_url` 消息体 | 同上 | **本项目首次引入** base64 图片上传：`{type:"image_url", image_url:{url:"data:image/jpeg;base64,..."}}` |
| 2.3 | 提示词工程 | 同上 | 系统提示 = 人格设定 + 「输出 N 条弹幕，每行一条，不要编号」；用户内容 = 图片 |
| 2.4 | 回复解析 | 新建 `DanmuReplyParser.kt` | 行分割 → 剥编号/引号/markdown → 长度过滤 → 丢弃空行 |
| 2.5 | 错误处理 | 同上 | HTTP 状态 / 超时 / 空回复 / JSON 结构异常，**全部降级为静默返回空列表** |

**关键技术点**：
```kotlin
// 图片编码（本项目首次）：先缩放再编码，控制请求体积
private fun encodeJpegBase64(bmp: Bitmap, quality: Int = 70): String {
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
    return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}
```
⚠️ **必须先缩放**：1920×1080 原图 base64 约 **2–4 MB**，直接发会超时且费流量。目标 **≤1024 宽、JPEG 70**（DanmuAI 的 `DEFAULT_IMAGE_MAX_WIDTH = 1024` 也是这个量级）。

**验收**：用一张测试图，能拿到一批中文弹幕文本（可先用日志打印，不做 UI）。

---

### Phase 3 —— VR 帧取图（本方案最难点）

**目标**：拿到「用户当前正在看的画面」的位图。

**为什么难**：本项目**没有** MediaProjection（那是桌面截屏思路，VR 场景下也不该用）。正确做法是**从 GL 管线回读当前帧**。

| 方案 | 做法 | 评价 |
|---|---|---|
| **P3-A（推荐）** | 复用 `VRGLRenderer` 已有的**降采样 FBO** 机制（`ensureGpHalfFbo` + `glBlitFramebuffer`），读一张小尺寸纹理 | 🟢 复用已验证的实现 |
| P3-B | 新增独立小 FBO 专门给弹幕用 | 🟡 多占一份显存，但互不干扰 |
| P3-C | MediaProjection 屏幕捕获 | 🔴 需额外权限、耗电、且**VR 分屏下画面是并排双眼**，语义不对 |

**P3-A 的三个必须注意点**（来自本项目已踩过的坑）：

1. ⚠️ **`glReadPixels(x,y,w,h)` 只裁剪、不缩放** —— 想降采样必须先用 `glBlitFramebuffer` 真缩放（v2.0.182/183 判例）
2. ⚠️ **`glReadPixels` 行序自下而上**（GL 原点在左下），填进 Bitmap 会**上下颠倒** → 需翻转
3. ⚠️ **`glReadPixels` 是同步阻塞**，会让 GL 线程 flush 整条管线 → 必须**低频**（弹幕间隔 5s+ 完全可接受），且**不能每帧调**

**分屏处理**：
- 若 `isSplitScreenVR`，回读的是**并排双画面** → 取帧时**只裁左眼**（或左右各取一半后二选一）
- 送模型时**只送单眼图像**，否则模型会看到两张一样的图，生成重复弹幕

| # | 任务 | 说明 |
|---|---|---|
| 3.1 | 在 `VRGLRenderer` 开一个**低频帧回读入口** | 形如 `fun requestDanmuFrame(): Bitmap?`，内部走降采样 FBO |
| 3.2 | 处理行序翻转 + 分屏裁剪 | 见上述三点 |
| 3.3 | 加**节流**（最小间隔守卫） | 防止业务层误调导致 GL 线程被打爆 |
| 3.4 | 生命周期管理 | 新增的 FBO/纹理必须随 `onSurfaceChanged` 释放重建 |

**验收**：日志里能打出当前帧的 Bitmap 尺寸 + 一张可查看的缩略图，且**播放流畅度无肉眼可见下降**。

**风险**：🔴 **最高风险阶段**。可能干扰美颜管线（GPUPixel 也在用回读）。**必须做好隔离**：弹幕帧回读走**独立 FBO**，不复用美颜的 `gpHalfFbo`。

---

### Phase 4 —— 弹幕引擎（纯逻辑，可单测）

**目标**：把「一批文本」变成「带轨道和时间的弹幕对象」。

| # | 任务 | 文件 | 说明 |
|---|---|---|---|
| 4.1 | 数据模型 | `DanmuEngine.kt`（新建） | `data class DanmuItem(text, trackIndex, startMs, durationMs, widthPx, color)` |
| 4.2 | 轨道分配 | 同上 | `maxTracks` 条轨道，选**最早空闲**的；无空闲则丢弃或最长轨道复用 |
| 4.3 | 速度与时长 | 同上 | `durationMs = (viewportWidth + textWidth) / speedPxPerMs`，保证**匀速穿越** |
| 4.4 | Leave-one-out 去重 | 同上 | 与最近 N 条（同批次 + 30s 内）比相似度，超阈值丢弃 |
| 4.5 | 容量保护 | 同上 | 待上屏队列上限（参考 DanmuAI 的 `DEFAULT_DANMU_PENDING_ENTRY_CAP = 300`） |
| 4.6 | 时间推进接口 | 同上 | `fun tick(nowMs)` 推进位置 + 淘汰过期条目 |

**相似度实现注意**：
⚠️ 本项目记忆里有 **`FloatArray.indexOf` 在 K2 下 Unresolved** 的坑 → 手写 `for` 循环。
Levenshtein 距离**自己实现**（滚动数组 O(nm)），**不要引第三方库**（避免新依赖 + 许可审查）。

**验收**：单元测试可跑（喂 100 条文本，验证轨道不重叠、超长文本被丢弃、重复文本被去重）。

---

### Phase 5 —— 弹幕渲染层（Compose）

**目标**：在 VR 画面上看到滚动的弹幕。

| # | 任务 | 说明 |
|---|---|---|
| 5.1 | 新建 `DanmuOverlay.kt` | 照 `SubtitleOverlay.kt`（271 行）的结构：`@Composable fun DanmuOverlay(...)` |
| 5.2 | 用 `Canvas` + `drawText` 绘制 | 复用 `SubtitleOverlay` 里的 `rememberTextMeasurer` / `TextMeasurer` 范式 |
| 5.3 | 位置计算 | `x = viewportWidth - (nowMs - startMs) * speedPxPerMs` |
| 5.4 | **VR 分屏双眼对齐** | 关键参数 `isSplitScreenVR` + `vrIpdOffsetRatio`（与 `SubtitleOverlay` 同款） |
| 5.5 | 描边/半透明底 | 复用 `SubtitleOverlay` 的 `strokeOption` / `bgOption` 思路 |
| 5.6 | 接线到播放画面 | 在 `VRPlayerScreen.kt:3540` 附近（`SubtitleOverlay` 调用点旁）挂载 |

**⚠️ 硬约束（来自 Composable 相关记忆）**：
- `@Composable` 调用**别嵌在 lambda 或 if/else 三元实参里** → 先算成 `val`
- 双眼渲染时**状态必须 hoisted**（`SubtitleOverlay` 用 `panelZoom` 的 hoist 做法可参考）

**验收**：弹幕出现在画面上、匀速滚动、进出屏幕自然、分屏下双眼位置正确。

---

### Phase 6 —— 编排与联调

**目标**：串成完整闭环。

| # | 任务 | 说明 |
|---|---|---|
| 6.1 | `DanmuOrchestrator.kt`（新建） | `CoroutineScope` + `while(isActive) { delay(interval); 取帧→调模型→解析→喂引擎 }` |
| 6.2 | 开关联动 | 弹幕开 ↔ 停止时取消协程；播放暂停 ↔ 弹幕暂停 |
| 6.3 | 首次冷启动提示 | 未配 API Key 时提示去设置（复用 `translate_please_set_api_key` 的思路） |
| 6.4 | 统计与日志 | 本场生成条数 / 丢弃条数 / 失败次数（照 DanmuAI 的 `lifetime_stats` 思路） |
| 6.5 | 与美颜共存压测 | ⚠️ **重点**：美颜 + 弹幕 + 解码三开，看帧率与发热 |

**验收**：完整跑一场视频，弹幕持续产出且不影响播放。

---

## 四、工作量与优先级

| Phase | 内容 | 新增/改动 | 预估规模 | 优先级 | 可独立验收 |
|---|---|---|---|---|---|
| **P1** | 骨架 + 设置项 | 新建 2 + 改 3 | ~600 行 | ⭐⭐⭐ | ✅ |
| **P2** | 视觉模型调用 | 新建 2 | ~400 行 | ⭐⭐⭐ | ✅ |
| **P3** | VR 帧取图 | 改 1（VRGLRenderer） | ~250 行 | ⭐⭐⭐ **最难** | ✅ |
| **P4** | 弹幕引擎 | 新建 1 | ~500 行 | ⭐⭐ | ✅（可单测） |
| **P5** | 渲染层 | 新建 1 + 改 1 | ~450 行 | ⭐⭐ | ✅ |
| **P6** | 编排联调 | 新建 1 + 改 1 | ~350 行 | ⭐⭐ | ✅ |
| | **合计** | **新建 8 + 改 6** | **~2,550 行** | | |

> ⚠️ **注**：上表为**自研**方案的规模。若 P4+P5 改用开源库，规模会显著下降，但**本项目特殊性决定了自研更优**——详见第十章「开源弹幕引擎调研结论」。

### 建议的落版策略

**分三版发**，而不是一次性堆在一个版本里：

| 版本 | 内容 | 理由 |
|---|---|---|
| **v2.2.0** | P1 + P3 | 先打通「能配置 + 能取到帧」这条**最高风险**的路；取帧可视化后可立刻判断可行性 |
| **v2.3.0** | P2 + P4 | 纯逻辑层，风险低，可快速迭代提示词质量 |
| **v2.4.0** | P5 + P6 | 上屏 + 联调，最后做视觉打磨 |

**为什么要先做 P3**：帧回读是**唯一可能推翻整个方案**的环节（若 GL 回读会严重干扰美颜/解码，方案就得改）。**先验证最危险的部分**，避免 2000 行写完才发现路走不通。

---

## 五、关键设计决策（需确认）

| # | 决策点 | 建议 | 备选 |
|---|---|---|---|
| D1 | **API 配置复用还是独立** | **独立**一套「视觉模型」配置 | 复用翻译引擎配置 —— ❌ 会互相污染（翻译用文本模型，弹幕要视觉模型） |
| D2 | **取帧来源** | GL 降采样 FBO 回读 | MediaProjection —— ❌ 权限+耗电+VR 语义不对 |
| D3 | **弹幕 VS 字幕共存** | 弹幕默认**靠上**，字幕靠下，避免重叠 | 互斥开关 —— 会牺牲易用性 |
| D4 | **去重算法** | 自研 Levenshtein（滚动数组） | 引第三方 —— ❌ 新依赖 + 许可审查成本 |
| D5 | **分屏取帧** | **只取左眼**送模型 | 双眼各送一次 —— ❌ 浪费一倍 token 且必产生重复弹幕 |
| D6 | **弹幕触发时机** | 固定间隔（默认 5s） | 场景变化触发 —— 需场景检测，P6 之后再考虑 |
| D7 | **是否做 TTS 朗读** | **本期不做** | 后续版本可接（本项目已有 ASR 基建，但 TTS 输出是另一条链） |
| D8 | **人格设定** | 提供 1 个默认 + 允许自定义文本框 | 抄 DanmuAI 的 14 个人格 —— ❌ **版权风险**（人格文本是受保护表达） |

⚠️ **D8 特别提示**：DanmuAI 内置的 14 个人格（胡桃、阿库娅、银狼、芙莉莲等）**其人格描述文本是受版权保护的表达**，**不要照抄**。可以让用户自己写，或提供完全原创的通用提示词。

---

## 六、风险清单与对策

| # | 风险 | 等级 | 对策 |
|---|---|---|---|
| R1 | **GL 帧回读干扰美颜/解码** | 🔴 高 | 独立 FBO；严格节流；P3 单独发版验证；测帧率 |
| R2 | **设置持久化三处漏改** | 🔴 高 | 逐项核对 key 列表 / `put*` / `remove`；本项目已 6 次踩坑 |
| R3 | **请求体积过大导致超时** | 🟠 中 | 强制「先缩放再编码」，≤1024 宽 + JPEG 70；实测控制 <200 KB |
| R4 | **token 成本失控** | 🟠 中 | 默认间隔 5s（而非 1s）；限制单次输出条数；显示用量统计 |
| R5 | **隐私：画面外传** | 🟠 中 | 首次开启时**明确告知**画面将上传第三方；提供关闭开关；README/docs 补说明 |
| R6 | **同一功能两份 UI** | 🟠 中 | 动手前 grep 同型写法；副本 ≥2 直接抽单一实现 |
| R7 | **分屏下双眼弹幕错位** | 🟡 中 | 复用 `SubtitleOverlay` 已验证的 `isSplitScreenVR` + `vrIpdOffsetRatio` 范式 |
| R8 | **`glReadPixels` 行序颠倒** | 🟡 中 | 记得翻转（本项目已踩过） |
| R9 | **模型回复格式不稳定** | 🟡 中 | 解析器做**宽容**处理：剥编号、剥引号、剥 markdown、超长截断 |
| R10 | **模拟器上验证不了 GL 行为** | 🟡 中 | MuMu 是 GPU 直通，GL 回读行为**可能与真机不同** → 关键验收需真机 |

---

## 七、验收标准

### 7.1 功能验收（用户侧）
- [ ] 设置里能开/关 AI 弹幕，重启后状态保持
- [ ] 填入视觉模型（如 Qwen-VL / GPT-4o 等 OpenAI 兼容端点）后能正常产出弹幕
- [ ] 弹幕匀速滚动、进出自然、不重叠、不闪烁
- [ ] **VR 分屏下双眼弹幕位置正确对齐**
- [ ] 弹幕与字幕**不互相遮挡**
- [ ] 弹幕开启时**播放流畅度无明显下降**

### 7.2 工程验收（我侧）
- [ ] `compileDebugKotlin` 通过
- [ ] 5 语 `strings.xml` 全部补齐
- [ ] 设置持久化三处改动齐全（自查）
- [ ] 新建 FBO/纹理全部有对应的释放路径
- [ ] 崩溃日志零 FATAL / SIGSEGV（装 MuMu 验证）
- [ ] 备份归档完整（`.workbuddy/tmp/src_bak_archive/<时间戳>/`）

---

## 八、需要你确认的事项

### 8.1 已确认（2026-10-06）

| 项 | 你的决定 | 对计划的影响 |
|---|---|---|
| **API 协议** | ✅ **走 OpenAI 兼容** | P2 只需实现一种协议：`POST {baseUrl}/chat/completions`，请求体含 `image_url` 消息；复用 `SubtitleTranslator.kt:1276` 的 `/chat/completions` 拼接与 `Bearer` 头范式 |
| **开源弹幕引擎** | ✅ **已调研，结论为不采用** | 见第十章：4 个候选各有硬伤（2 个已停维、1 个私有仓库、1 个 AGPL-3.0）。**P4/P5 确定自研**，复用 `SubtitleOverlay.kt` 范式，**零新增依赖** |
| **人格设定** | ✅ **接受「1 个原创默认 + 用户自定义」** | D8 定案：不照抄 DanmuAI 的 14 个人格（其描述文本受版权保护），只提供 1 个原创通用提示词 + 自定义输入框 |

### 8.2 仍需你确认

| 项 | 说明 |
|---|---|
| **先做哪个 Phase** | 我建议 **P1 + P3 先行**（先验证最高风险的 GL 帧回读环节） |
| **视觉模型具体选哪家** | 走 OpenAI 兼容协议已定，但具体默认端点/模型名建议（如 Qwen-VL / GLM-4V / GPT-4o）需你给个倾向，用于填默认值 |

---

## 九、附：本计划的依据来源

| 结论 | 依据 |
|---|---|
| 本项目已有 OkHttp + Bearer + /chat/completions | `SubtitleTranslator.kt:745, 1276-1303` |
| 本项目已有引擎枚举 9 种 | `SubtitleTranslator.kt:33-113` |
| 本项目已有 Bitmap 缩放 | `VRPlayerScreen.kt:7885` |
| 本项目已有 GL 降采样 FBO | `VRGLRenderer.kt:2894` `ensureGpHalfFbo()` |
| 本项目**无** MediaProjection / base64 / 视觉调用 | 全仓 grep = 0 命中 |
| 本项目**无**弹幕功能 | 全仓 grep `danmu`/`弹幕` = 0 命中 |
| 字幕层已独立成文件 | `SubtitleOverlay.kt`（271 行），`VRPlayerScreen.kt:3542` 调用 |
| GL 回读三个坑 | 项目记忆：`glReadPixels` 只裁剪不缩放 / 行序自下而上 / 同步阻塞 |
| DanmuAI 的图片宽度量级 | 其 `config_defaults.py`：`DEFAULT_IMAGE_MAX_WIDTH = 1024` |
| DanmuAI 的队列上限量级 | 其 `config_defaults.py`：`DEFAULT_DANMU_PENDING_ENTRY_CAP = 300` |
| GPL 阻断 | `PEPETII/danmuai` LICENSE 第 5(c) 条 |

---

## 十、开源弹幕引擎调研结论（2026-10-06 补充）

> **调研指令**：用户要求「先去找开源的弹幕引擎」。
> **调研结果**：找到 4 个候选，**结论是不采用任何一个**——理由不是它们不好，而是**本项目的渲染架构特殊**。

### 10.1 四个候选的实测数据

| 库 | 许可 | 语言 | Stars | 最后提交 | 渲染方式 | minSdk | 依赖仓库 |
|---|---|---|---|---|---|---|---|
| **DanmakuFlameMaster**（B站） | Apache-2.0 ✅ | Java | 9,609 | **2020-02-27** ⚠️ | View / SurfaceView / TextureView | 未声明 | **jcenter（已关闭）** ⚠️ |
| **AkDanmaku**（快手） | MIT ✅ | Kotlin | 492 | **2021-12-30** ⚠️ | **libGDX**（自带图形框架） | 未声明 | 未知 |
| **DanmakuRenderEngine**（字节） | Apache-2.0 ✅ | — | — | 活跃（西瓜/抖音在用） | View | — | **字节私有 maven** ⚠️ |
| **Animeko / Ani**（open-ani） | **AGPL-3.0** ❌ | Kotlin | 20,520 | **2026-10-06（今天）** ✅ | **Compose Multiplatform** | — | — |

### 10.2 逐个体检

**① DanmakuFlameMaster（B站 DFM）—— 否决：已停维 6 年 + 依赖仓库关闭**

- 最后提交 **2020-02-27**，距今 **6 年 7 个月**
- Gradle 坐标走 **jcenter**，而 **jcenter 已于 2021 年停止服务** → 按官方 README 写法**直接拉不到包**
- 更致命：它捆绑 **`libndkbitmap.so`（NDK）**，只提供 `armv7a` / `armv5` / `x86` 三种 ABI，**没有 arm64-v8a**（现代手机/华为 VR Glass 的主力 ABI）
- 作者本人（B站）已在新版中**下架 DFM**，改用自研 `Chronos` 框架

**② AkDanmaku（快手）—— 否决：基于 libGDX，引入成本过高 + 已停维**

- 最后提交 **2021-12-30**
- **核心问题**：它基于 **libGDX**（跨平台 Java 游戏框架），带 **ECS 架构 + 自己的图形处理框架**。引入它等于**在 App 里再塞一个游戏引擎**，与现有的 GL 渲染管线必然冲突
- MIT 许可本身友好，但技术形态不匹配

**③ DanmakuRenderEngine（字节）—— 否决：私有仓库 + 版本不透明**

- 依赖 `maven { url 'https://artifact.bytedance.com/repository/releases/' }`（**字节私有仓库**）
- 许可虽是 Apache-2.0，但**依赖第三方私有 maven** 对本项目是可维护性风险（仓库变更即构建失败）
- 文档示例用 `$latest_version` 占位，**未给出明确版本号**

**④ Animeko / Ani —— 否决：AGPL-3.0（这是最可惜的一个）**

- **它是唯一真正现代的方案**：**Compose Multiplatform 弹幕引擎**，100% Kotlin/Compose，**今天还在提交**（2026-10-06），20,520 stars
- 技术形态与本项目**完全吻合**（同样是 Kotlin + Compose + ExoPlayer）
- **但许可是 AGPL-3.0** —— 比 DanmuAI 的 GPL-3.0 **更严格**：不仅分发触发义务，**通过网络提供服务也会触发**。第 13 条明确「网络交互」要求提供源码
- 与排除 DanmuAI 是**同一类理由**，且程度更重 → **同样必须排除**

### 10.3 为什么「不采用开源引擎」才是正确决策

这是本次调研最重要的结论。因为**本项目的渲染场景与所有弹幕库的设计前提都不同**：

| 前提 | 普通弹幕库的假设 | 本项目的实际情况 |
|---|---|---|
| 画面来源 | 视频 View 之上叠一层 View | **GL 渲染**（`VRGLRenderer`，`GLSurfaceView` + EGL） |
| 显示形态 | 单屏 | **VR 分屏（并排双眼）** |
| 坐标系统 | 一个 viewport | **两个 viewport**，各有 IPD 偏移 |
| 叠加方式 | View 层级 z-order | **GL 之后由 Compose 层覆盖**，需与 `SubtitleOverlay` 同款处理 |

**四个候选全部是 View / Canvas / SurfaceView 体系**（Animeko 是 Compose，最接近，但许可不通）。而本项目的画面是 **GL 直接渲染到 Surface**，弹幕层必须走 **Compose 覆盖层**——这正是 `SubtitleOverlay.kt` 已经验证过的路径（v120 已独立成文件）。

所以：
- **用 View 系弹幕库** → 要在一个 GL Surface 上再叠 SurfaceView，**z-order 与合成开销双输**
- **用 Animeko** → 许可不通
- **自研 Compose 弹幕层** → 🟢 完全复用 `SubtitleOverlay.kt` 的成熟范式（`rememberTextMeasurer` / `Canvas` / `drawText` / `isSplitScreenVR` / `vrIpdOffsetRatio`），**且能正确处理 VR 分屏双眼**

### 10.4 结论与调整

| 项 | 决定 |
|---|---|
| P4（弹幕引擎） | **自研**（~500 行）—— 轨道分配 + 速度 + 去重是纯逻辑，无库可替代，也不该引库 |
| P5（渲染层） | **自研 Compose 层**（~450 行）—— 复用 `SubtitleOverlay.kt` 范式，**且这是 VR 分屏适配的唯一正确路径** |
| 开源库 | **全部不引入**（详见 10.2 逐条否决理由） |
| 是否需要新依赖 | **不需要** —— 零新增第三方依赖，避免 minSdk 冲突与许可审查 |

> **⚠️ 关于「不引入库」的一个重要补充**：
> 这不是"为了自研而自研"。**本项目的渲染前提（GL + VR 分屏）与所有弹幕库的设计前提都不一致**，
> 强行引入会带来三重代价：① 与 GL 管线冲突 ② 无法处理双眼 ③ 许可/仓库/ABI 均有问题。
> 自研反而**更省**：复用已有的 `SubtitleOverlay` 范式，代码量可控，且天然适配 VR。

> **📌 minSdk 约束提示**：本项目 `minSdk = 24`（`app/build.gradle.kts:17`），
> 而部分现代库要求 minSdk 26+（项目曾有 `dev.jdtech.mpv:libmpv` 因 minSdk=26
> 导致**清单合并直接失败**的判例）。这也是"不引入新依赖"的一个额外好处。

### 10.5 调研依据一览

| 结论 | 依据 |
|---|---|
| DFM 最后提交 2020-02-27 | GitHub API `/repos/bilibili/DanmakuFlameMaster` → `pushed_at` |
| DFM 走 jcenter、仅 armv7a/armv5/x86 ABI | 其 README 的 Gradle 坐标段 |
| AkDanmaku 最后提交 2021-12-30、基于 libGDX | GitHub API `/repos/KwaiAppTeam/AkDanmaku` + 其 README |
| DanmakuRenderEngine 用字节私有 maven | 其 README 的 `maven { url 'https://artifact.bytedance.com/...' }` |
| Animeko 为 AGPL-3.0、Compose Multiplatform、今天仍在提交 | GitHub API `/repos/open-ani/animeko` → `license.spdx_id = AGPL-3.0`，`pushed_at = 2026-10-06` |
| 本项目 minSdk = 24 | `app/build.gradle.kts:17` |
| 本项目渲染为 GL（`VRGLRenderer` + EGL） | `VRGLRenderer.kt`（5,195 行）；`GLES20/GLES30` 大量使用 |
| 字幕层已是 Compose 覆盖层范式 | `SubtitleOverlay.kt:78`，调用点 `VRPlayerScreen.kt:3542` |

---

*本计划为实施前规划文档，未修改任何源码。开工时需按项目规矩先备份。*

