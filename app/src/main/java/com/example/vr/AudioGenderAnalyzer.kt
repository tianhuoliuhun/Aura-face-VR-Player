package com.example.vr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * v2.4.64：**音频性别分析（离线，第一段）** —— VAD / 说话人分离 + 基频判性别。
 *
 * ## v2.4.67（档 1）：性别判定升级为 **ECAPA-TDNN 模型**（F0 降级为回退）
 * 段级与说话人级都**优先**用 [GenderClassifier]（ONNX Runtime + 16MB q8 模型）判性别；
 * 模型缺失 / session 失败 / 推理抛错时**自动回退**到下面的 F0 判据，并在结果里标注走的是哪条路。
 *
 * ## 这一版做到哪、没做到哪（务实说明）
 * `docs/AUDIO_GENDER_FILTER_DESIGN.md` 的完整流水线是：
 * VAD → 说话人分离 → **性别分类** → **源分离（Spleeter）** → 拼接 → 缓存。
 * 但 **sherpa-onnx 的 Java API 没有暴露源分离**（实测 1.13.6 / 1.13.8 均无 `OfflineSourceSeparation`），
 * 而 ML 性别分类需要额外引入 onnxruntime。因此本版先落地**前面两步 + 一个不依赖模型的性别判据**：
 *
 * 1. **说话人分离**：`OfflineSpeakerDiarization`（pyannote 分割 + WeSpeaker 嵌入 + 内置聚类）；
 * 2. **性别判据：基频（F0）中位数** —— 男声典型 85~155Hz、女声 165~255Hz（成人语音学常识），
 *    用**自相关法**逐帧估计 F0，再对每个说话人取中位数。**无需任何额外模型**，
 *    但准确率低于 ML 分类器（会对童声/假声/变声误判）。
 *
 * ## 为什么先做这一段
 * 用户目标是「先验证识别准不准」。这一版产出**逐段的 [开始, 结束, 说话人, F0, 性别] 明细**，
 * 不碰播放链路 —— 先看结果对不对，再决定要不要投入源分离与播放集成。
 *
 * ## 性能约束
 * - 只对**分离出来的语音段**算 F0，且每段**限量取样**（[MAX_FRAMES_PER_SEGMENT] 帧）——
 *   自相关是 O(帧长 × 滞后范围)，全量逐帧会慢到不可用；
 * - 长音频的内存风险由调用方控制（`AudioPcmExtractor` 有 `maxSeconds` 上限）。
 */
object AudioGenderAnalyzer {

    private const val TAG = "AudioGenderAnalyzer"

    /** 判为男声的 F0 上界（Hz） */
    private const val F0_MALE_MAX_HZ = 160f
    /** 判为女声的 F0 下界（Hz） */
    private const val F0_FEMALE_MIN_HZ = 175f
    /** 两者之间视为「不确定」（童声 / 变声 / 噪声） */
    /** 每段最多取多少帧做 F0 估计（性能闸门） */
    private const val MAX_FRAMES_PER_SEGMENT = 40
    /** 分析帧长 / 步长（秒） */
    private const val FRAME_SEC = 0.04f
    private const val HOP_SEC = 0.02f
    /** 能量门限（RMS），低于它视为静音帧、不参与 F0 */
    private const val RMS_GATE = 0.008f
    /** F0 搜索范围 */
    private const val F0_MIN_HZ = 70f
    private const val F0_MAX_HZ = 400f

    enum class Gender { MALE, FEMALE, UNKNOWN }

    /** 一个语音段（由说话人分离给出） */
    data class SpeakerSegment(
        val startMs: Long,
        val endMs: Long,
        val speakerId: Int,
        /** 该段估计出的基频中位数；0 = 该段太短/无有效帧 */
        val f0Hz: Float,
        val gender: Gender,
        /** v2.4.67：该段的 ML 概率（male）；**<0 表示未跑 ML**（模型不可用 / 段太短） */
        val mlMale: Float = -1f,
        /** v2.4.67：该段的 ML 概率（female）；<0 表示未跑 ML */
        val mlFemale: Float = -1f,
    )

    /** 每个说话人的汇总 */
    data class SpeakerInfo(
        val id: Int,
        val gender: Gender,
        val f0MedianHz: Float,
        val speechMs: Long,
        val segmentCount: Int,
    )

    data class Result(
        val sampleRate: Int,
        val totalMs: Long,
        val speakers: List<SpeakerInfo>,
        val segments: List<SpeakerSegment>,
        val maleMs: Long,
        val femaleMs: Long,
        val unknownMs: Long,
        /** v2.4.67：本次性别判定实际走了哪条路（"ML 模型" / "F0 回退"）——便于用户确认效果来源 */
        val genderSource: String = "F0 回退",
    ) {
        /** 一行摘要，供日志/UI 直接显示 */
        fun summary(): String {
            val sb = StringBuilder()
            sb.append("时长 ${totalMs / 1000}s，检测到 ${speakers.size} 个说话人：")
            for (s in speakers) {
                val label = when (s.gender) {
                    Gender.MALE -> "男声"
                    Gender.FEMALE -> "女声"
                    Gender.UNKNOWN -> "不确定"
                }
                sb.append("\n  #${s.id} $label  F0≈${s.f0MedianHz.toInt()}Hz  语音 ${s.speechMs / 1000}s / ${s.segmentCount} 段")
            }
            sb.append("\n合计：男声 ${maleMs / 1000}s · 女声 ${femaleMs / 1000}s · 不确定 ${unknownMs / 1000}s")
            sb.append("\n判定来源：").append(genderSource)
            return sb.toString()
        }
    }

    /**
     * 跑一轮完整分析。
     *
     * @param pcm        16kHz 单声道、归一化到 [-1,1] 的采样（来自 [AudioPcmExtractor]）
     * @param onProgress 0..100
     * @return null = 模型缺失或初始化失败（具体原因已写日志）
     */
    suspend fun analyze(
        context: Context,
        pcm: FloatArray,
        sampleRate: Int = AudioPcmExtractor.TARGET_SAMPLE_RATE,
        onProgress: (Int) -> Unit = {},
    ): Result? = withContext(Dispatchers.Default) {
        if (pcm.isEmpty()) {
            Log.w(TAG, "输入 PCM 为空")
            return@withContext null
        }
        if (!AudioGenderModels.allReady(context)) {
            Log.w(TAG, "模型未就绪，缺失: ${AudioGenderModels.missing(context).map { it.displayName }}")
            return@withContext null
        }
        // 🔴 v2.4.66：把路径交给 native 之前的**最后一道闸**。
        //    sherpa-onnx 读到「不完整 / 不可读」的模型文件会打 `F` 日志后 **abort()** ——
        //    进程直接闪退，Java 的 try/catch **抓不到**（v2.4.66 就是这么闪退的）。
        //    所以只能在这里提前拦下：下载被截断、解压只出来半个文件、文件不可读。
        val bad = AudioGenderModels.ALL.filterNot { AudioGenderModels.isUsable(context, it) }
        if (bad.isNotEmpty()) {
            Log.w(
                TAG,
                "模型文件不合格（过小/不可读），已拦下以免 native abort：" +
                    bad.joinToString { "${it.displayName}(${AudioGenderModels.fileOf(context, it).length()}B)" },
            )
            return@withContext null
        }

        var diarization: OfflineSpeakerDiarization? = null
        try {
            val cfg = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                        model = AudioGenderModels.fileOf(context, AudioGenderModels.SEGMENTATION).absolutePath,
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
                embedding = SpeakerEmbeddingExtractorConfig(
                    model = AudioGenderModels.fileOf(context, AudioGenderModels.EMBEDDING).absolutePath,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
            )
            onProgress(5)
            // 🔴 v2.4.66：**AssetManager 必须传 null**。
            //    sherpa-onnx 的 C++ 侧规则：assetManager **非 null** 时会把 model 路径当
            //    **assets 相对路径**解析。我们的模型是下载到 filesDir 的**绝对路径**，于是它去 assets 里找
            //    这个绝对路径 → 找不到 → 打一行 `F sherpa-onnx: Read binary file: Load '...' failed`
            //    然后 **abort()** —— 进程直接闪退（native abort，Java 的 try/catch **抓不到**）。
            //    实测日志：
            //      W sherpa-onnx: You are using an absolute path '...', but assetManager is NOT set to null
            //      F sherpa-onnx: Read binary file: Load '.../pyannote-segmentation-3-0.onnx' failed
            //      I ActivityManager: Process ... has died: fg TOP
            //    → 传 null 表示「按普通文件系统路径读」。
            diarization = OfflineSpeakerDiarization(null, cfg)

            // 模型要求的采样率（pyannote/WeSpeaker 均为 16k，这里做一次校验式重采样兜底）
            val needed = diarization.sampleRate()
            val samples = if (needed != sampleRate && needed > 0) {
                Log.w(TAG, "模型要求 ${needed}Hz，输入 ${sampleRate}Hz → 重采样")
                AudioPcmExtractor.resampleLinear(pcm, sampleRate, needed)
            } else {
                pcm
            }
            val useRate = if (needed > 0) needed else sampleRate

            val totalMs = samples.size * 1000L / useRate
            Log.i(TAG, "开始说话人分离：${samples.size} 采样 / ${totalMs}ms")

            // 分离本身可能耗时较久；用带回调的版本把进度透出去
            // ⚠️ 回调签名：(numProcessedChunks, numTotalChunks, 耗时ms) -> 是否继续
            val raw = try {
                diarization.processWithCallback(samples, { done, total, _ ->
                    if (total > 0) onProgress(5 + (done * 55 / total))
                    0
                }, 0L)
            } catch (t: Throwable) {
                // 回调版本在个别机型/版本上可能不可用 → 退回同步版本
                Log.w(TAG, "processWithCallback 失败，退回 process(): ${t.message}")
                diarization.process(samples)
            }
            Log.i(TAG, "分离完成：${raw.size} 段")
            onProgress(65)

            // ---- 逐段估 F0 → 判段性别 ----
            val segs = ArrayList<SpeakerSegment>(raw.size)
            raw.forEachIndexed { idx, s ->
                val startMs = (s.start * 1000f).toLong()
                val endMs = (s.end * 1000f).toLong()
                val slice = slicePcm(samples, useRate, s.start, s.end)
                val f0 = estimateF0Median(slice, useRate)
                // v2.4.67（档 1）：段级优先用 ECAPA-TDNN 模型；不可用/推理失败则回退 F0。
                val ml = GenderClassifier.classifySegment(context, slice, useRate)
                val mlGender = GenderClassifier.genderOf(ml)
                segs.add(
                    SpeakerSegment(
                        startMs = startMs,
                        endMs = endMs,
                        speakerId = s.speaker,
                        f0Hz = f0,
                        gender = if (mlGender == Gender.UNKNOWN) genderOf(f0) else mlGender,
                        mlMale = ml?.first ?: -1f,
                        mlFemale = ml?.second ?: -1f,
                    )
                )
                if (raw.isNotEmpty()) onProgress(65 + (idx * 25 / raw.size))
            }

            // ---- 按说话人聚合（用「语音时长加权」的中位数，避免长段被短段稀释）----
            val mlUsed = segs.any { it.mlMale >= 0f }
            val speakers = segs.groupBy { it.speakerId }.map { (id, list) ->
                val weightedF0 = weightedMedianF0(list)
                // v2.4.67：说话人级优先用**时长加权的 ML 概率均值**（比段级更稳）；
                //    完全没有 ML 结果时才回退到 F0 中位数。
                val avgMl = weightedAvgMl(list)
                val g = if (avgMl != null) {
                    GenderClassifier.genderOf(avgMl).let { if (it == Gender.UNKNOWN) genderOf(weightedF0) else it }
                } else {
                    genderOf(weightedF0)
                }
                SpeakerInfo(
                    id = id,
                    gender = g,
                    f0MedianHz = weightedF0,
                    speechMs = list.sumOf { it.endMs - it.startMs },
                    segmentCount = list.size,
                )
            }.sortedBy { it.id }

            // 段的性别以「所属说话人」为准（段级 F0 噪声大，说话人级更稳）
            val speakerGender = speakers.associate { it.id to it.gender }
            val finalSegs = segs.map { it.copy(gender = speakerGender[it.speakerId] ?: it.gender) }

            val maleMs = finalSegs.filter { it.gender == Gender.MALE }.sumOf { it.endMs - it.startMs }
            val femaleMs = finalSegs.filter { it.gender == Gender.FEMALE }.sumOf { it.endMs - it.startMs }
            val unknownMs = finalSegs.filter { it.gender == Gender.UNKNOWN }.sumOf { it.endMs - it.startMs }

            onProgress(100)
            Result(
                sampleRate = useRate,
                totalMs = totalMs,
                speakers = speakers,
                segments = finalSegs,
                maleMs = maleMs,
                femaleMs = femaleMs,
                unknownMs = unknownMs,
                genderSource = if (mlUsed) "ML 模型（ECAPA-TDNN）" else "F0 回退",
            )
        } catch (t: Throwable) {
            // 与项目既有 AI 模块一致：失败**如实带回原因**，不静默吞
            Log.e(TAG, "音频性别分析失败: ${t.javaClass.simpleName}: ${t.message}", t)
            null
        } finally {
            runCatching { diarization?.release() }
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 按秒区间取 [pcm] 的子段（越界自动裁剪）。 */
    internal fun slicePcm(pcm: FloatArray, sampleRate: Int, startSec: Float, endSec: Float): FloatArray {
        val a = (startSec * sampleRate).toInt().coerceIn(0, pcm.size)
        val b = (endSec * sampleRate).toInt().coerceIn(a, pcm.size)
        return if (b > a) pcm.copyOfRange(a, b) else FloatArray(0)
    }

    /**
     * **自相关法**估计基频中位数（Hz）；无效返回 0。
     *
     * 只取最多 [MAX_FRAMES_PER_SEGMENT] 帧（均匀跨段取样）—— 见类注释的性能说明。
     */
    internal fun estimateF0Median(samples: FloatArray, sampleRate: Int): Float {
        if (samples.size < (sampleRate * FRAME_SEC).toInt() * 2) return 0f
        val frameLen = (sampleRate * FRAME_SEC).toInt()
        val hop = (sampleRate * HOP_SEC).toInt().coerceAtLeast(1)
        val minLag = (sampleRate / F0_MAX_HZ).toInt().coerceAtLeast(2)
        val maxLag = (sampleRate / F0_MIN_HZ).toInt().coerceAtMost(frameLen - 1)
        if (maxLag <= minLag) return 0f

        // 均匀取帧（不超过上限）
        val maxStart = samples.size - frameLen
        val naturalFrames = maxStart / hop + 1
        val step = if (naturalFrames > MAX_FRAMES_PER_SEGMENT) {
            (naturalFrames / MAX_FRAMES_PER_SEGMENT).coerceAtLeast(1) * hop
        } else hop

        val f0s = ArrayList<Float>(MAX_FRAMES_PER_SEGMENT)
        var start = 0
        while (start <= maxStart) {
            var mean = 0f
            for (i in 0 until frameLen) mean += samples[start + i]
            mean /= frameLen

            var energy = 0f
            val frame = FloatArray(frameLen) { samples[start + it] - mean }
            for (v in frame) energy += v * v
            val rms = sqrt(energy / frameLen)

            if (rms >= RMS_GATE) {
                // ① 原始自相关（**不**除以重叠长度）
                //    🔴 曾经的写法是 `sum /= (frameLen - lag)` —— 那会让**大 lag 被人为放大**，
                //    于是选中整数倍周期：220Hz 被估成 73Hz（= 220/3）。**这是单测抓出来的**
                //    （`AudioGenderCoreTest` 的 220Hz 用例一开始就是红的）。
                var globalMax = 0f
                val ac = FloatArray(maxLag + 2)
                for (lag in minLag..maxLag) {
                    var sum = 0f
                    val n = frameLen - lag
                    for (i in 0 until n) sum += frame[i] * frame[i + lag]
                    ac[lag] = sum
                    if (sum > globalMax) globalMax = sum
                }
                // ② 取「**第一个** ≥ 0.85×全局最大值 的局部峰」= 真正的基音周期。
                //    整数倍周期（2T/3T…）处同样会出现高峰 → 经典 octave error，
                //    取「第一个够高的峰」即可避开（这是稳且便宜的做法）。
                var bestLag = 0
                if (globalMax > 0f) {
                    val th = 0.85f * globalMax
                    for (lag in (minLag + 1) until maxLag) {
                        if (ac[lag] >= th && ac[lag] >= ac[lag - 1] && ac[lag] >= ac[lag + 1]) {
                            bestLag = lag
                            break
                        }
                    }
                    if (bestLag == 0) bestLag = (minLag..maxLag).maxByOrNull { ac[it] } ?: 0
                }
                // 峰值得足够强才算「有基频」（与帧能量同量纲比较，见上：都是未归一化的和）
                if (bestLag > 0 && ac[bestLag] > energy * 0.25f) {
                    f0s.add(sampleRate.toFloat() / bestLag)
                }
            }
            start += step
        }
        if (f0s.isEmpty()) return 0f
        f0s.sort()
        return f0s[f0s.size / 2]
    }

    /** v2.4.67：按语音时长加权的 ML 概率平均；没有任何 ML 结果时返回 null（→ 调用方回退 F0）。 */
    private fun weightedAvgMl(segs: List<SpeakerSegment>): Pair<Float, Float>? {
        var wm = 0f
        var wf = 0f
        var wsum = 0f
        for (s in segs) {
            if (s.mlMale < 0f) continue
            val w = (s.endMs - s.startMs).coerceAtLeast(1L).toFloat()
            wm += s.mlMale * w
            wf += s.mlFemale * w
            wsum += w
        }
        return if (wsum > 0f) (wm / wsum) to (wf / wsum) else null
    }

    /** 按语音时长加权的 F0 中位数（F0<=0 的段不参与）。 */
    private fun weightedMedianF0(segs: List<SpeakerSegment>): Float {
        val valid = segs.filter { it.f0Hz > 0f }
        if (valid.isEmpty()) return 0f
        val sorted = valid.sortedBy { it.f0Hz }
        val totalWeight = sorted.sumOf { (it.endMs - it.startMs).coerceAtLeast(1L) }
        var acc = 0L
        for (s in sorted) {
            acc += (s.endMs - s.startMs).coerceAtLeast(1L)
            if (acc * 2 >= totalWeight) return s.f0Hz
        }
        return sorted.last().f0Hz
    }

    internal fun genderOf(f0Hz: Float): Gender = when {
        f0Hz <= 0f -> Gender.UNKNOWN
        f0Hz < F0_MALE_MAX_HZ -> Gender.MALE
        f0Hz > F0_FEMALE_MIN_HZ -> Gender.FEMALE
        else -> Gender.UNKNOWN
    }

    /** 供 UI 显示模型体积（MB） */
    fun modelsMb(context: Context): Long = AudioGenderModels.downloadedMb(context)

    /** 模型是否齐备（UI 用） */
    fun modelsReady(context: Context): Boolean = AudioGenderModels.allReady(context)

    /** 缺失模型的总体积（MB），用于提示下载量 */
    fun missingMb(): Int = AudioGenderModels.ALL.sumOf { it.approxMb }

    /** 预留：诊断用（文件是否存在） */
    fun debugPaths(context: Context): String =
        AudioGenderModels.ALL.joinToString("\n") { spec ->
            val f = AudioGenderModels.fileOf(context, spec)
            "${spec.displayName}: ${if (f.isFile) "${f.length() / 1048576}MB" else "缺失"}"
        }
}
