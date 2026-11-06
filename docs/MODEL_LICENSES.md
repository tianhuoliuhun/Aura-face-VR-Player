# 模型许可核实 / Model License Verification

> 核实日期：**2026-11-06**（v2.8.0 换模型后复核）　｜　对应版本：v2.8.0
> 目的：确认应用内使用/分发的 AI 模型是否允许**商业使用**，以及需要履行哪些义务。

## 结论摘要

| 模型 | 许可 | 可否商用 | 需要做什么 |
|---|---|---|---|
| **Dolphin ASR**（DataoceanAI） | **Apache-2.0** | ✅ 可以 | 保留版权与许可声明（本项目已在 `NOTICE`、`THIRD_PARTY_NOTICES.md`、应用内许可页登记） |
| **Index-Translate Omni 2B**（bilibili / IndexTeam） | **Apache-2.0** | ✅ 可以 | 同上；**无额外登记/申请要求** |

> 之前担心的"唯一可能真侵权的两个点"，经核实**都不成立**——两者均为 Apache-2.0，
> 与本次核实前标注的"待核实"相比，结论是放宽而不是收紧。

---

## 一、Dolphin（DataoceanAI 多语言 ASR）

| 项 | 内容 |
|---|---|
| 使用方式 | **随包分发**：`app/src/main/assets/dolphin/model.int8.onnx`（约 103 MB）+ `tokens.txt` |
| 权利方 | Dataocean AI 与清华大学联合训练 |
| 许可（代码与权重） | **Apache License 2.0** |
| 官方原文 | "Dolphin's code and model weights are released under the Apache 2.0 License." |
| 来源 | HuggingFace 模型卡 <https://huggingface.co/DataoceanAI/dolphin-small>；GitHub <https://github.com/DataoceanAI/Dolphin>（LICENSE 段同样声明）；PyPI 包元数据 `License: Apache-2.0` |
| 商用限制 | 无（Apache-2.0 本身允许商用、修改、再分发） |
| 义务 | 保留版权声明、许可文本；若修改过文件需注明（本项目为 ONNX 转换件，见下） |

**关于 ONNX 转换件**：本项目打包的是 int8 量化的 `.onnx`（由上游 PyTorch 权重转换而来）。
权重许可不因格式转换而改变，仍为 Apache-2.0；转换本身属于"修改"，应在声明中说明
"converted to ONNX int8 for on-device inference"。

---

## 二、Index-Translate Omni 2B（bilibili / IndexTeam）

| 项 | 内容 |
|---|---|
| 使用方式 | **不随包分发**：用户首次使用时从 HuggingFace `IndexTeam/Index-Translate-omni-2B-GGUF` 下载（见 `LocalLlm.kt` 的 `MODELS`） |
| 许可 | **Apache License 2.0**（仓库根 `LICENSE` 为 Apache-2.0；HF 模型卡 `license:apache-2.0` 标签） |
| 额外条款 | 仓库与模型卡均**未提及**商用限制、登记流程或附加协议 |
| 来源 | <https://github.com/bilibili/Index-Translate>（核实于 2026-10-10 更新）、<https://huggingface.co/IndexTeam/Index-Translate-omni-2B-GGUF>（核实于 2026-11-06，commit `41eb7bb`） |
| 商用限制 | 无 |
| 用途 | Omni = 文本翻译（150 语言，术语表 / 格式约束 / 内容保留类指令）+ 图像翻译（视觉 mmproj，无 OCR 依赖）。⚠️ **GGUF 版不含音频模块**（官方「Audio limitation」：只有 language model + 可选 mmproj vision），故本项目的语音识别仍用自带 Dolphin |

**为什么换到它**（v2.8.0 起用文本版，v2.8.1 起用 Omni 版）：
原先用的 MiniCPM-V 4.7 1B 是**通用多模态**模型，做字幕翻译属于"顺带能翻"；
Index-Translate 是**翻译专用**模型（bilibili 开源，2B 档），指令跟随与术语表约束更对口，
且同样提供 GGUF + mmproj，架构为 `qwen35`，与项目内置的 llama.cpp 构建一致（无需重编 native）。
Omni 版在**文本翻译基准上与文本版完全相同**（FLORES COMET22 0.8655 / WMT24++ 0.8489），多出来的是图像翻译与语音模态 ——
其中语音在 GGUF 里被裁掉，因此实际额外可用的只有图像翻译。

**⚠️ 版本/规模提醒**：
同系列还有 9B、35B-A3B（preview）、以及 Omni / Echo（语音）/ Homura / NativeLong 等衍生模型，
**许可均为 Apache-2.0**，但体积与用途不同。若将来切换规模或换到语音类（Echo）模型，
需重新确认"GGUF 仓库里是否只含 LLM 主干"（官方明确：Echo 的 GGUF **只含文本主干**，
完整语音流水线在 FP8/FP4 仓库），否则会下到一个"看起来能用、实际缺模块"的权重。

**分发方式对责任划分的影响**：
- 本项目只提供**下载地址**，权重由用户设备直接从 HuggingFace 获取，不构成再分发；
- 因此即使许可将来变化，主要义务落在"告知用户其许可"这一层，而非"本项目再分发权重"。

**历史备注**：v2.4.59 ~ v2.7.1 用 openbmb 的 MiniCPM-V 4.7 1B；v2.8.0 用 Index-Translate 2B（纯文本版）；
v2.8.1 起用 Omni 2B。旧权重文件会被 `LocalLlmManager.deleteLegacyModelFiles()` 清理（MiniCPM 约 1.8GB、文本版约 1.7GB）。

---

## 三、建议动作（均已在本轮完成或已存在）

1. ✅ 在 `NOTICE` 中列出 Dolphin 与 Index-Translate（Apache-2.0，标注版权方）。
2. ✅ 在 `THIRD_PARTY_NOTICES.md` 的「模型」表格中把"待核实"改为"Apache-2.0，允许商用"。
3. ✅ 应用内「开源许可」页的模型分组显示许可与上游链接。
4. ⬜ 若未来内置 Dolphin 的**其它规模**（medium/large），需确认该权重的许可（当前上游只公开了 base/small）。
5. ⬜ 若切换到 Index-Translate 的其它规模（9B / 35B-A3B）或语音类模型，重新核实许可与仓库内容（见上）。

---

## 四、未决问题

- Dolphin 官方只公开了 **base / small** 两个规模；本项目使用的是 small 的 int8 量化版，
  **上游并未直接提供 ONNX int8 产物**，属于本项目自行转换。转换件是否需要在文件名/文档中标注
  "derived from DataoceanAI/dolphin-small (Apache-2.0)"，建议在发布页注明（低风险，但更规范）。
- Index-Translate 的 GGUF 仓库与主仓库共享 Apache-2.0，但**以主仓库与 LICENSE 正文为准**；
  若上游单独给 GGUF 仓库加条款，需重新核实。

---

## 五、核实方法说明

- Dolphin：读取 HuggingFace 模型卡的 License 段（官方原文）、GitHub 仓库 LICENSE 声明、PyPI 元数据。
- Index-Translate Omni 2B：读取 HF API 的 `tags`（`license:apache-2.0`）、仓库根 `LICENSE`，
  并检查 README 是否含 commercial/registration 等附加条款（结论：无）。
- 二手来源（技术媒体、聚合站）仅作交叉印证，**结论以官方页面为准**。
