package com.example.vr

/**
 * v119 拆分：从 VRPlayerScreen.kt 搬出的 ASR 相关公共类型。
 * [AsrEngineType] 在 VRPlayerScreen、SherpaAsrManager 之间共享，原先定义在
 * VRPlayerScreen.kt 内，导致这些文件反向依赖播放界面文件。集中到此处后依赖方向变干净。
 */

/**
 * ASR 引擎类型。
 *
 * v127：只保留 **SenseVoice CPU**（sherpa-onnx，model.int8.onnx，中英日韩粤，
 * RTF 0.026 自带标点）。此前并存的三条路线已全部移除：
 * - Vosk（Kaldi，流式）：需按语言各下模型、无标点，且上下文累积问题多
 * - Qwen3-ASR 0.6B：838MB 模型、逐块离线推理代价高
 * - SenseVoice QNN：仅个别骁龙 SoC 可用，模型与设备绑定，兼容面太窄
 */
enum class AsrEngineType { SENSEVOICE }
