package com.example.vr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.exp

/**
 * v2.4.67（档 1）：**ECAPA-TDNN 性别分类**（ONNX Runtime 推理）。
 *
 * ## 模型
 * `Alice-Sabrina-Ivy/voice-gender-classifier-onnx-q8-v2`（**MIT**，约 16MB，int8 动态量化）
 * - 上游权重：`JaesungHuh/voice-gender-classifier`（ECAPA-TDNN C=1024）
 * - **输入**：raw mono float32 @ **16 kHz**，shape `(batch, time)`（**time 可变长**）
 * - **输出**：pre-softmax logits，shape `(batch, 2)`，约定 **`{0: male, 1: female}`**
 * - **log-mel 预处理（STFT + mel + log + 逐帧归一化）已内嵌进 ONNX 图**
 *   → App 侧**只喂原始波形**；且**不要**再做音频侧归一化（模型卡 `do_normalize: false`）
 *
 * ## 为什么它比 F0 阈值可靠
 * 模型卡的对照数据：Hillenbrand 语料男女准确率 **100%**（v1 全量量化时只有 95.6%）；
 * 组内 raw_std 从 0.216 降到 0.010（= 同一说话人的判定**稳定得多**）。
 * v1→v2 的改进只是**量化时排除一个节点**（注意力池化的 matmul），细节见技术栈文档。
 *
 * ## 窗口策略（照搬模型卡给出的评估配置）
 * **rolling 0.75 s window / 150 ms hop / EMA α=0.2** —— 比「整段一次推理」稳得多，
 * 也能顺带抵抗短时噪声。
 *
 * ## ⚠️ 执行提供者只用 CPU
 * 模型卡明确警告：该量化图（Res2Net/Concat 密集）在 **WebGPU EP 上会运行期报错并更慢**，
 * 且「session 创建成功、run 时才炸」，try-catch 兜不住。Android 侧同理**保守只用 CPU**
 * （不 `addNnapi()`），避免量化算子在 NNAPI 上的兼容性问题。
 *
 * ## 失败必须可回退
 * 模型缺失 / session 建不起来 / 推理抛错 → 一律返回 `null`，由调用方**回退到 F0 判定**
 * （见 [AudioGenderAnalyzer]），绝不因为一个可选增强把整条分析链路弄挂。
 */
object GenderClassifier {

    private const val TAG = "GenderClassifier"

    /** 滚动窗长度（秒）——模型卡评估配置 */
    private const val WINDOW_SEC = 0.75f

    /** 滚动窗步长（秒）——模型卡评估配置 */
    private const val HOP_SEC = 0.15f

    /** 指数移动平均系数 ——模型卡评估配置 */
    private const val EMA_ALPHA = 0.2f

    /** 太短的片段（<0.4 s）不做推理：不足一个窗，统计上没意义 */
    private const val MIN_SEC = 0.4f

    /** 判定阈值：两类概率差超过它才下结论，否则 UNKNOWN（避免临界乱猜） */
    private const val DECISION_MARGIN = 0.10f

    private val lock = Any()
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName: String? = null

    /** 初始化失败后不再重试（避免每段都白跑一遍） */
    private var initFailed = false

    /** 模型文件是否可用（存在 + 足够大 + 可读） */
    fun isAvailable(context: Context): Boolean {
        val f = AudioGenderModels.fileOf(context, AudioGenderModels.GENDER)
        return f.isFile && f.length() >= AudioGenderModels.GENDER.minBytes && f.canRead()
    }

    /** 当前是否已经成功建好 session（供 UI 显示状态，不触发初始化） */
    fun isReady(): Boolean = synchronized(lock) { session != null }

    private fun ensureSession(context: Context): OrtSession? = synchronized(lock) {
        session?.let { return it }
        if (initFailed) return null
        if (!isAvailable(context)) {
            Log.w(TAG, "性别模型不可用（未下载或不合格），将回退 F0 判定")
            return null
        }
        return try {
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                // ⚠️ 刻意不加 addNnapi()：量化 int8 图在非 CPU EP 上有兼容风险（见类注释）
            }
            val path = AudioGenderModels.fileOf(context, AudioGenderModels.GENDER).absolutePath
            val s = e.createSession(path, opts)
            env = e
            session = s
            inputName = s.inputNames.firstOrNull()
            Log.i(TAG, "session OK: input=$inputName outputs=${s.outputNames} providers=[CPU]")
            s
        } catch (t: Throwable) {
            Log.e(TAG, "创建 session 失败（将回退 F0）: ${t.javaClass.simpleName}: ${t.message}", t)
            initFailed = true
            null
        }
    }

    /**
     * 单窗推理 → `(male, female)` 概率（softmax 后）。失败返回 null。
     *
     * @param pcm 16 kHz 单声道原始波形（**不要**预先归一化）
     */
    fun predictWindow(context: Context, pcm: FloatArray): Pair<Float, Float>? {
        if (pcm.isEmpty()) return null
        val s = ensureSession(context) ?: return null
        val inName = inputName ?: return null
        return try {
            synchronized(lock) {
                OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(pcm),
                    longArrayOf(1, pcm.size.toLong()),
                ).use { tensor ->
                    s.run(mapOf(inName to tensor)).use { out ->
                        // 输出 shape (1,2) → [[male, female]]（pre-softmax logits）
                        @Suppress("UNCHECKED_CAST")
                        val row = (out[0].value as Array<FloatArray>)[0]
                        softmax2(row[0], row[1])
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "推理失败: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /**
     * 对一段语音做**滚动窗推理 + EMA 平滑**，返回聚合后的 `(male, female)` 概率。
     *
     * 末尾不足一窗时，**取该段最后 0.75 s**（而不是补零）—— 补零会被当作静音，
     * 而静音段在特征上会让模型倾向某一类，反而污染结果。
     */
    fun classifySegment(context: Context, pcm: FloatArray, sampleRate: Int): Pair<Float, Float>? {
        val win = (WINDOW_SEC * sampleRate).toInt()
        if (win <= 0 || pcm.size < (MIN_SEC * sampleRate).toInt()) return null
        val hop = (HOP_SEC * sampleRate).toInt().coerceAtLeast(1)

        var emaM = 0f
        var emaF = 0f
        var n = 0
        var start = 0
        while (true) {
            val block: FloatArray
            val end = start + win
            if (end <= pcm.size) {
                block = pcm.copyOfRange(start, end)
            } else {
                // 末尾不足一窗 → 用最后一段（仅当还从没跑过任何窗时才需要，保证至少有一窗）
                if (n > 0) break
                block = pcm.copyOfRange(pcm.size - win, pcm.size)
            }
            val r = predictWindow(context, block) ?: return null
            if (n == 0) {
                emaM = r.first
                emaF = r.second
            } else {
                emaM = emaM * (1 - EMA_ALPHA) + r.first * EMA_ALPHA
                emaF = emaF * (1 - EMA_ALPHA) + r.second * EMA_ALPHA
            }
            n++
            if (end >= pcm.size) break
            start += hop
        }
        return if (n > 0) emaM to emaF else null
    }

    /** 由聚合概率判性别；`null`（模型不可用）返回 `UNKNOWN`，由调用方回退。 */
    fun genderOf(prob: Pair<Float, Float>?): AudioGenderAnalyzer.Gender {
        if (prob == null) return AudioGenderAnalyzer.Gender.UNKNOWN
        val (m, f) = prob
        return when {
            m > f + DECISION_MARGIN -> AudioGenderAnalyzer.Gender.MALE
            f > m + DECISION_MARGIN -> AudioGenderAnalyzer.Gender.FEMALE
            else -> AudioGenderAnalyzer.Gender.UNKNOWN
        }
    }

    fun release() {
        synchronized(lock) {
            runCatching { session?.close() }
            session = null
            inputName = null
        }
    }

    /** 数值稳定的二类 softmax */
    internal fun softmax2(a: Float, b: Float): Pair<Float, Float> {
        val mx = maxOf(a, b)
        val ea = exp((a - mx).toDouble()).toFloat()
        val eb = exp((b - mx).toDouble()).toFloat()
        val sum = ea + eb
        if (sum <= 0f) return 0.5f to 0.5f
        return ea / sum to eb / sum
    }
}
