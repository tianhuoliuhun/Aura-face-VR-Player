# 模型许可核实 / Model License Verification

> 核实日期：**2026-10-18**　｜　对应版本：v2.6.2
> 目的：确认应用内使用/分发的 AI 模型是否允许**商业使用**，以及需要履行哪些义务。

## 结论摘要

| 模型 | 许可 | 可否商用 | 需要做什么 |
|---|---|---|---|
| **Dolphin ASR**（DataoceanAI） | **Apache-2.0** | ✅ 可以 | 保留版权与许可声明（本项目已在 `NOTICE`、`THIRD_PARTY_NOTICES.md`、应用内许可页登记） |
| **MiniCPM-V 4.7 1B**（openbmb） | **Apache-2.0** | ✅ 可以 | 同上；**无额外登记/申请要求** |

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

## 二、MiniCPM-V 4.7 1B（openbmb / 面壁智能）

| 项 | 内容 |
|---|---|
| 使用方式 | **不随包分发**：用户首次使用时从 HuggingFace `openbmb/MiniCPM-V-4.7-1B-gguf` 下载（见 `LocalLlm.kt` 的 `MODELS`） |
| 许可 | **Apache License 2.0**（HuggingFace 模型卡 `License: apache-2.0` 标签） |
| 额外条款 | 模型卡正文**未提及**任何商用限制、登记流程或附加协议 |
| 来源 | <https://huggingface.co/openbmb/MiniCPM-V-4.7-1B>（核实于 2026-10-18）、<https://github.com/OpenBMB/MiniCPM-V> |
| 商用限制 | 无 |

**⚠️ 版本差异提醒（重要）**：
MiniCPM **早期版本**（如 MiniCPM 2.x / 3.x）使用的是自定义的 "MiniCPM Model License"，
其中包含**商用需向官方免费登记**等条款。**4.7 版已改为 Apache-2.0**。
若将来回退模型版本或替换为同系列旧模型，**必须重新核实**，不能沿用本文结论。

**分发方式对责任划分的影响**：
- 本项目只提供**下载地址**，权重由用户设备直接从 HuggingFace 获取，不构成再分发；
- 因此即使许可将来变化，主要义务落在"告知用户其许可"这一层，而非"本项目再分发权重"。

---

## 三、建议动作（均已在本轮完成或已存在）

1. ✅ 在 `NOTICE` 中列出 Dolphin 与 MiniCPM-V（Apache-2.0，标注版权方）。
2. ✅ 在 `THIRD_PARTY_NOTICES.md` 的「模型」表格中把"待核实"改为"Apache-2.0，允许商用"。
3. ✅ 应用内「开源许可」页的模型分组显示许可与上游链接。
4. ⬜ 若未来内置 Dolphin 的**其它规模**（medium/large），需确认该权重的许可（当前上游只公开了 base/small）。
5. ⬜ 若替换为 MiniCPM 旧版本，重新核实许可（见上）。

---

## 四、未决问题

- Dolphin 官方只公开了 **base / small** 两个规模；本项目使用的是 small 的 int8 量化版，
  **上游并未直接提供 ONNX int8 产物**，属于本项目自行转换。转换件是否需要在文件名/文档中标注
  "derived from DataoceanAI/dolphin-small (Apache-2.0)"，建议在发布页注明（低风险，但更规范）。
- MiniCPM-V 4.7 的 GGUF 仓库（`-gguf`）与主仓库共享 Apache-2.0，但**以主仓库标签为准**；
  若上游单独给 GGUF 仓库加条款，需重新核实。

---

## 五、核实方法说明

- Dolphin：读取 HuggingFace 模型卡的 License 段（官方原文）、GitHub 仓库 LICENSE 声明、PyPI 元数据。
- MiniCPM-V 4.7 1B：读取 HuggingFace 模型页的 `License: apache-2.0` 标签，并检查模型卡正文
  是否含 commercial/registration 等附加条款（结论：无）。
- 二手来源（技术媒体、聚合站）仅作交叉印证，**结论以官方页面为准**。
