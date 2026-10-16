# 音频性别过滤：技术栈与选型建议

**日期**：2026-10-16
**背景**：`docs/AUDIO_GENDER_FILTER_DESIGN.md` 给了目标（去男声、留女声与环境音）与大致流水线；
v2.4.64～v2.4.66 落地了「抽音轨 + 说话人分离 + 基频判性别」并修掉了 OOM 与 native 闪退。
本文回答的是**下一步该怎么选技术栈**：哪些现成可用、哪些要补、日语怎么办、Android 怎么接。

---

## 0. 结论速览（一张表）

| 环节 | 推荐选型 | 体积 | 许可 | 语言相关性 | 现状 |
|---|---|---|---|---|---|
| VAD | sherpa-onnx `Vad`（`silero_vad.onnx`） | 0.6 MB | MIT | **语言无关**（声学） | ✅ 已用 |
| 说话人分离 | sherpa-onnx `OfflineSpeakerDiarization`（pyannote 分割 + WeSpeaker 嵌入 + 聚类） | 32 MB | Apache-2.0 | **语言无关** | ✅ 已用 |
| **性别分类** | ① **零新依赖**：`SpeakerEmbeddingExtractor` 特征 + 自己训的线性分类器<br>② **更准**：`Alice-Sabrina-Ivy/voice-gender-classifier-onnx-q8-v2`（ECAPA-TDNN） | ① ~2 KB<br>② **16 MB** | ② MIT | 训练集以英语为主；**声学特征跨语言**，需实测校准 | ⚠️ 待做 |
| **源分离（去人声）** | ① `Spleeter` 2stems **fp32 ONNX**（2×37 MB）<br>② `HT-Demucs` vocals-only ONNX（质量最好但大） | ① **74 MB**<br>② 316 MB fp32 / ~75 MB 量化 | 均 MIT | **语言无关**（纯信号分离） | ❌ 未做 |
| 推理运行时 | `onnxruntime-android`（可 `addNnapi()`） | ~10–25 MB | MIT | — | ❌ 未引入 |

**一句话**：**VAD + 说话人分离已经在手**；**最缺的是「源分离」**（它能决定「保留环境音」这个核心诉求能否达成）；
**性别分类可以先用零新依赖的方案显著变准，再按需升级到 ML 模型**。

---

## 1. 为什么需要换/补（当前方案的硬伤）

| 现状 | 问题 | 后果 |
|---|---|---|
| 性别判定 = **自相关 F0 阈值**（<160Hz 男 / >175Hz 女） | 对**童声、假声（falsetto）、变调、噪声段**不稳；说话人重叠时 F0 混叠 | 性别标签会错 → 后续处理错 |
| **没有源分离** | 只能「把男声段整体压低/静音」 | **环境音也一起没了** —— 达不到设计文档「preserve ambient sounds」 |
| 说话人分离已可用 | 只能告诉你「谁在什么时候说」 | 这是**基础**，不是终点 |

> 关键认知：**「去男声但留环境音」在技术上等价于「按目标说话人做源分离」**，
> 而不是「按时间段做闸门」。这就是必须引入源分离的原因。

---

## 2. 候选方案对比

### 2.1 性别分类

| 方案 | 模型/做法 | 体积 | 输入 | 许可 | 精度参考 | Android |
|---|---|---|---|---|---|---|
| **A（零依赖，推荐先做）** | WeSpeaker embedding（**已下载的 25 MB 模型**）+ 逻辑回归 | 系数 ~2 KB | 16k mono | 自有 | 取决于训练数据（JVS 男女各 20 条即可起步） | 纯 Kotlin ✅ |
| **B（推荐升级）** | `Alice-Sabrina-Ivy/voice-gender-classifier-onnx-q8-v2`（ECAPA-TDNN, C=1024） | **16.1 MB**（q8） | **16 kHz raw float**，**log-mel 预处理已内嵌进 ONNX 图** | **MIT** | Hillenbrand 男女 **100%**；v1（全量 q8）仅 95.6% | onnxruntime-android ✅ |
| C | `audeering/wav2vec2-large-robust-6-ft-age-gender` | ~1.2 GB（可量化） | 16k | CC-BY-4.0 | Common Voice 德语 96.3% | 太重，不建议 |
| D（现状） | 自相关 F0 阈值 | 0 | 16k | — | 干净语音尚可，假声/童声差 | 零依赖 ✅ |

**关于 B 的两个要点**（值得记住）：
- **v2 相对 v1 的改进是「量化时只排除一个节点」**：`nodes_to_exclude=["node_matmul"]`。
  因为注意力池化的那个 matmul 是**两个运行时激活相乘**，动态量化对它伤害最大（mean |Δp| 0.155 vs 卷积最差 0.016）。
  排除它之后 **q8 的 16 MB 就能达到 FP32 精度** —— 这是「不量化一切」的好例子。
- **特征提取在模型内部**（STFT + mel + log + 逐帧均值归一化都在图里），
  所以 App 侧只要喂 **16 kHz 原始 float 波形**，并且 **不要**再做一遍归一化（`do_normalize: false`）。

### 2.2 源分离（去人声）

| 方案 | 体积 | 质量 | 许可 | 手机可行性 | 备注 |
|---|---|---|---|---|---|
| **Spleeter 2stems ONNX（fp32）** | **2×37 MB** | 卡拉OK/粗分轨够用 | MIT | ✅ 有实践者直接打进 App | **int8 量化会让分离质量明显劣化**（残留/伪影）→ 用 fp32 |
| **HT-Demucs vocals-only ONNX** | 316 MB fp32 / **~75 MB 量化** | 最好 | MIT | ⚠️ 体积与耗时大 | 2026 年才有可用的 ONNX 导出；**单 stem 专用模型比完整 4-stem bag 快约 5.7×** |
| sherpa-onnx 内置源分离 | — | — | — | ❌ | **Java API 未暴露**（1.13.6 / 最新 1.13.8 实测都无 `OfflineSourceSeparation`） |
| UVR / MDX-Net ONNX | ~50–100 MB | 好 | ⚠️ 需逐个确认 | ⚠️ | 许可不统一，落地前必须核对 |

> **实测参考**：HT-Demucs 单 specialist 在 Apple M4 Pro CPU 上处理 3 分钟歌约 **22 秒**（RTF 0.12）。
> 手机 CPU 会慢数倍 —— 但本功能是**一次性离线预处理**，慢可以接受（设计文档也允许 30–60 s/片）。

### 2.3 日语支持（专门回答）

**结论：不存在「日语专用性别分类模型」，但也不需要 —— 但必须用日语素材做验收与校准。**

| 维度 | 情况 |
|---|---|
| VAD / 说话人分离 / 源分离 | **语言无关**（都是声学信号处理），日语无需特殊适配 ✅ |
| 性别分类（F0） | 语言无关（成人男声 85–155 Hz / 女声 165–255 Hz 是**生理共性**）✅ |
| 性别分类（ML 模型） | 训练集多为英语（VoxCeleb / Hillenbrand）→ 对日语**预期可用但需实测** ⚠️ |
| **可用日语验收语料** | **JVS corpus**：100 位日语母语专业说话人（**49 男 / 51 女**）、30.4 小时、24 kHz/16-bit；**含手工标注的 F0 范围**；三种风格（normal / **whisper** / **falsetto**）⚠️ 音频许可：学术机构、商业组织内的非商业研究、个人使用免费，**商业用途需按其项目页条款确认** |
| 单说话人语料 | JSUT（女性单说话人，10 小时）—— 适合做「不应被误判为男声」的反例 |
| 建议验收方式 | ① 取 JVS 男女各 10–20 条 → 测性别判定准确率（目标 ≥ 95%）；② **用 falsetto 子集做压力测试**（假声是 F0 法最容易翻车的场景）；③ 用 JVS 的**手工 F0 标注**校准阈值 |

> ⚠️ **JVS 的 falsetto（假声）子集正是「F0 阈值法」的死穴** —— 男声假声的 F0 会进入女声区间。
> 这从侧面说明：**只要条件允许，性别判定应升级到 ML 模型（方案 B）**，而不是靠调阈值硬扛。

---

## 3. 推荐路线（按投入分三档，可独立交付）

### 档 0 · 零新依赖，先把准确率拉起来（约 1–2 天）

**做法**：复用**已经在用的** WeSpeaker 嵌入模型，把性别判定从「单一 F0 阈值」换成
**「F0 + 说话人嵌入的轻量分类器」**。

- 用 `SpeakerEmbeddingExtractor`（已下载的 25 MB 模型）对每个说话人的语音段提 **256 维嵌入**；
- 用少量标注数据（JVS 男女各 10–20 条，或你手头的素材）训一个**逻辑回归 / 线性 SVM**；
- 系数导出成 Kotlin 常量（~2 KB）→ **零额外依赖、零额外体积**；
- F0 作为**辅助特征**一起喂进去（对假声仍会误判，但比纯阈值稳）。

**优点**：不动依赖、不改 native、立刻能验证「日语准不准」。
**缺点**：不如专用 ML 模型准（尤其假声）。

### 档 1 · 引入 ML 性别分类（约 +16 MB，2–3 天）

- 依赖：`com.microsoft.onnxruntime:onnxruntime-android`；
- 模型：`Alice-Sabrina-Ivy/voice-gender-classifier-onnx-q8-v2`（MIT，16 MB，**特征内嵌**）；
- **必须先做一件事**：验证 **onnxruntime-android 与 sherpa-onnx 的 `libonnxruntime.so` 同名冲突**（见 §4.1）。

### 档 2 · 引入源分离，真正达成设计目标（约 +74 MB，3–5 天）

- 模型：**Spleeter 2stems fp32 ONNX**（2×37 MB，MIT）；
- 处理逻辑变成：**男声段 → 用「伴奏轨」替换**（环境音留住 ✅）；女声段 → 保留原音；
- 这一步做完，才算真正实现设计文档的「remove male voices while preserving ambient sounds」。

> 建议顺序：**档 0 → 档 1 → 档 2**，每档都能独立验证。若预算只够做一件，**优先做档 2**（它是核心诉求）。

---

## 3.5 档 1 与档 2 到底差在哪（决策用）

### 一句话概括

> **档 1 解决「判得准」，档 2 解决「删得掉」。两者不是递进包含关系，而是能力互补。**

- **档 1**（ML 性别分类）只改变**判定环节**的准确率 —— 它**不改变处理方式**，处理仍是「对男声段时间段做音量压制」；
- **档 2**（源分离）改变的是**处理能力本身** —— 从「压低音量」升级为「把该段的人声换成伴奏轨」，这才可能**保留环境音**。

### 对照表

| 维度 | 档 1（ML 性别分类） | 档 2（+ Spleeter fp32 源分离） |
|---|---|---|
| **解决什么** | 「哪些时段是男声」判得准（~85% → ~96%+） | 「男声时段怎么处理」：从压制音量 → **只留伴奏/环境音** |
| **处理后的听感** | 男声段**变小或静音**；静音则**环境音一起消失** | 男声段**人声消失、环境音与音乐保留** ✅ |
| **新增体积** | +16 MB 模型（+ ort ~20 MB） | **+74 MB**（Spleeter fp32；累计 ≈110 MB + ort） |
| **新增算力** | 低（每 0.75–1.5 s 窗一次推理，毫秒级） | **高**（HT-Demucs/Spleeter 是定长分块模型，RTF 0.12 @M4 Pro，手机估计 0.5–2） |
| **能否达成设计目标**<br>（去男声 + 保留环境音） | ❌ **不能**（保留环境音做不到） | ✅ **能** —— 这是目前唯一的正解 |
| **主要风险** | `.so` 冲突（见 §4.1） | `.so` 冲突 **+** 处理耗时 **+** 分离伪影 |
| **是否可独立用** | 可独立（判定结果也能给 UI 用） | **需要「性别判定」来驱动** → 依赖档 1 或现有 F0 |

### 🔴 三个容易忽略的边界（看文档前必须知道）

1. **源分离不认性别**：Spleeter / Demucs 分出的是 `vocals / accompaniment`（按**声源类型**分），
   **不是**「男声 / 女声」。所以「男声段 → 用 accompaniment 替换」的真实效果是：
   **该时段的「所有人声」都消失**（不只是男声）。
2. **重叠语音会被误伤**：如果男声与女声**同时在说话**（overlap），这一段的人声会**一起被删**，女声也留不住。
   要「只去男声、留下同时说话的女声」，需要 **TSE / 目标说话人提取**（见 §3.6），而不是源分离。
3. **档 2 单独上反而更危险**：判定错的地方会从「判错」升级成「**误删人声**」（听感上更刺耳、不可逆）。
   → **正确组合是：档 1 先把判定做准，档 2 再做处理。**

### 决策建议（三句话）

- **只想先知道「日语判定准不准」** → **档 0 就够**（零新依赖、当天可验证）；
- **要达成设计文档的目标**（去男声 + 留环境音）→ **必须做档 2**，且**建议连着档 1 一起做**（判定准了，处理才有的放矢）；
- **两档共享同一个前置条件**（引入 `onnxruntime-android` 并解决 `.so` 冲突）→ 所以
  **先花 ~1 小时做「两套 libonnxruntime 共存」的最小验证**；通过后**档 1 + 档 2 一次做完**，
  比分两次搭环境更省（它们要改的是同一个推理层）。

### 3.6 更进一步的方案：TSE（目标说话人提取）—— 本次不实施

上面第 2 条边界（重叠语音）是**分段式方案的天花板**。要突破它，需要**在「女性说话人」条件下去提取语音**：

| 方向 | 代表工作 | 说明 |
|---|---|---|
| 说话人条件下的语音增强/提取 | VoiceFilter（Google）、SpeakerBeam（NTT） | 用目标说话人的 embedding 作为条件，直接从混音里提取该说话人 |
| 轻量化/移动端版本 | VoiceFilter-Lite 等 | 专为端侧设计，但**是否已有可用的 ONNX 导出需另行调研** |
| 复音提取 | pDCCRN / TF-GridNet 等 | 质量更好，但计算量对手机偏重 |

**判断**：TSE 是「真正只留女声、且能处理重叠语音」的正解，但它是一条**独立的技术路线**（模型选型、许可、
ONNX 导出成熟度都要重新调研），**不建议与档 1/档 2 混在一起做**。建议：**先按档 1+2 落地并验收**，
若实测发现「重叠语音误伤」是主要痛点，再单独立项评估 TSE。

---

## 4. Android 集成

### 4.1 ⚠️ 首先要解决的冲突：两套 `libonnxruntime.so`

- sherpa-onnx 的 AAR **自带** `jni/*/libonnxruntime.so`（1.13.6 内嵌 onnxruntime 1.17.x 量级）；
- `onnxruntime-android` 也带**同名** so → 打包时冲突。

可选处理方式（**按推荐度排序**）：

| 方案 | 做法 | 风险 |
|---|---|---|
| **① 先做隔离验证**（推荐第一步） | 只加依赖 + `pickFirst("**/libonnxruntime.so")`，写个最小 Demo 同时初始化 sherpa 与 ort，各跑一次推理 | 若 ABI 不兼容会在 native 层崩（Java 抓不到）→ 所以**必须先独立验证** |
| ② 版本对齐 | 选与 sherpa 内嵌版本接近的 `onnxruntime-android`（如 1.17.x/1.18.x），减少 ABI 差异 | 仍需实测 |
| ③ 不引入 ort，改用纯 Kotlin 复现 | 例如只做 Spleeter 的推理层手写（工作量极大，不现实） | ❌ 不建议 |
| ④ 拆进程 | 用 `:separate` 独立进程跑 ort，主进程跑 sherpa | 复杂度高，但能彻底隔离 |

```kotlin
// app/build.gradle.kts
android {
    packaging {
        jniLibs {
            // ⚠️ sherpa-onnx 与 onnxruntime-android 都带 libonnxruntime.so（同名不同版本）
            //    先 pickFirst 打通，但**必须验证**两个引擎在同进程都能正常跑（否则 native 崩，Java 抓不到）
            pickFirsts += "**/libonnxruntime.so"
        }
    }
}
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
```

### 4.2 性别分类推理（Kotlin 思路）

```kotlin
// 关键点：模型内部已含 log-mel 预处理 → 只喂 16 kHz raw float，且不要二次归一化
object GenderClassifier {
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    fun init(modelPath: String) {
        val e = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            // 可选：addNnapi() 走 NPU；不可用时自动回退 CPU
        }
        env = e
        session = e.createSession(modelPath, opts)
    }

    /** @param pcm 16 kHz 单声道、[-1,1]；返回 (male, female) 概率 */
    fun predict(pcm: FloatArray): Pair<Float, Float> {
        val s = session ?: return 0f to 0f
        // 输入名按模型签名（本模型第一个输入即音频，shape (batch, time)）
        val inputName = s.inputNames.first()
        OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(pcm), longArrayOf(1, pcm.size.toLong()))
            .use { tensor ->
                s.run(mapOf(inputName to tensor)).use { out ->
                    val logits = (out[0].value as Array<FloatArray>)[0]   // shape (1,2)
                    val m = softmax2(logits[0], logits[1])
                    return m.first to m.second
                }
            }
    }
}
```

**聚合策略**（重要）：不要拿「整段 3 分钟」一次推理，而是**按说话人分段、逐个小窗（0.75–2 s）推理后投票/取均值**：

```kotlin
// 对 diarization 给出的每个 speaker，取其若干语音段 → 逐窗推理 → 概率平均
val windows = speakerSegments.flatMap { segmentWindows(it, winSec = 1.5) }
val avg = windows.map { GenderClassifier.predict(it) }
    .reduce { a, b -> (a.first + b.first) / 2 to (a.second + b.second) / 2 }
```

### 4.3 源分离推理（Kotlin 思路）

Spleeter / Demucs 都是**定长分块**模型（如 7.8 s 一段），要在外面做**滑窗 + 重叠相加**：

```kotlin
fun separateVocals(pcm16k: FloatArray, sr: Int = 16000): FloatArray {
    val chunk = 7.8f * sr      // 模型期望的输入长度（按所选模型的签名调整）
    val hop = (chunk * 0.75f).toInt()
    val outV = FloatArray(pcm16k.size)
    val wsum = FloatArray(pcm16k.size)
    var start = 0
    while (start < pcm16k.size) {
        val end = minOf(start + chunk.toInt(), pcm16k.size)
        val block = FloatArray(chunk.toInt())             // 不足部分补零
        System.arraycopy(pcm16k, start, block, 0, end - start)
        val (vocals, accomp) = Spleeter.run(block)        // 一次拿两条轨
        val win = hann(chunk.toInt())                     // 加窗，避免拼接爆音
        for (i in 0 until (end - start)) {
            outV[start + i] += vocals[i] * win[i]
            wsum[start + i] += win[i]
        }
        start += hop
    }
    for (i in outV.indices) if (wsum[i] > 1e-6f) outV[i] /= wsum[i]
    return outV
}
```

**然后重组**：
```
男声时段 → 用 伴奏轨(accompaniment) 替换（环境音保留 ✅）
女声/无语音时段 → 用 原始混音
边界做 50–100 ms 交叉淡入淡出（设计文档要求）
```

### 4.4 与现有代码的整合点

| 现有 | 改动 |
|---|---|
| `AudioGenderAnalyzer` | 性别判定从 `estimateF0Median` 升级为「embedding + 分类器」或「ONNX 模型」；`Segment.gender` 的产出方式变了，但**数据结构不用动** |
| `AudioPcmExtractor` | 已有 16 kHz 单声道 PCM 产出 ✅ 直接复用（源分离前注意**别丢掉重采样质量控制**） |
| `AudioGenderModels` | 新增两个模型登记（性别分类 16 MB、Spleeter 74 MB），沿用现有下载/校验机制（含 `minBytes` 防御闸） |
| `AudioGenderSection` | 增加「处理模式」选择（仅分析 / 分析+去男声），以及处理进度（源分离比分析慢得多） |

---

## 5. 日语验收方案（可直接执行）

1. **取样本**：JVS 男女各 10 位说话人，各取 3 段 normal（共 60 段，约 1–2 分钟/段）；
2. **跑判定**：用当前实现 + 候选方案各跑一遍，统计性别准确率（目标 **≥ 95%**）；
3. **压力测试**：JVS 的 **falsetto（假声）** 子集 —— 预期 F0 法会显著失分，ML 模型应更稳；
4. **阈值校准**：用 JVS 的**手工 F0 标注**反推更合适的男女分界（不要照搬教科书的 165 Hz）；
5. **端到端**：选一段日语对白视频，人工核对「男声时段」标注是否与听感一致，再做「去男声」试听。

---

## 6. 工作量与风险

| 档 | 内容 | 新增体积 | 主要风险 | 验证方式 |
|---|---|---|---|---|
| 0 | embedding + 轻量分类器 | ~0 | 训练样本不足 → 过拟合 | JVS 准确率 |
| 1 | + ML 性别分类（onnxruntime） | +16 MB（+ort ~20 MB） | **`libonnxruntime.so` 冲突**（native 崩，Java 抓不到） | 最小 Demo 同时初始化两引擎 |
| 2 | + Spleeter fp32 源分离 | +74 MB | 手机 RTF 慢（长片处理久）、内存峰值 | 3 分钟片段计时 + 试听 |

**始终适用的两条铁律**（本项目已踩过）：
1. **native 崩溃 Java 抓不到** → 所有 native 调用前必须**前置校验**（文件存在/尺寸/可读、参数合法性）；
2. **别用 `ArrayList<Float>` 攒大量数值** → 用裸 `FloatArray` + 手动扩容，并**尽早降采样**。

---

## 7. 附：一句话回答「日语怎么办」

> **声学环节（VAD / 分离 / 源分离）天然支持日语；性别分类的现成模型是英语训练的，对日语大概率可用但必须实测。**
> 落地做法：用 **JVS**（49 男 / 51 女 + 手工 F0 标注 + 假声子集）做准确率验收与阈值校准；
> 若 F0 法在假声上失分严重，就上 **ECAPA-TDNN 的 q8 ONNX（16 MB, MIT）**。
