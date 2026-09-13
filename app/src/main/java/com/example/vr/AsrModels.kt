package com.example.vr

/**
 * v119 拆分：从 VRPlayerScreen.kt 搬出的 ASR 相关公共类型。
 *
 * [AsrEngineType] 在 VRPlayerScreen、SherpaAsrManager、AsrBatchTranscriber、
 * VoskAsrEngine 之间共享，原先定义在 VRPlayerScreen.kt 内，导致这几个文件都
 * 反向依赖播放界面文件。集中到此处后依赖方向变干净。
 */

/** v110：ASR 引擎类型枚举 */
enum class AsrEngineType { VOSK, QWEN3, SENSEVOICE_QNN }
