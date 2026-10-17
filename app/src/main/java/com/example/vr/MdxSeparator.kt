package com.example.vr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.min

/**
 * v2.4.68（档 2）：**UVR MDX-Net 人声分离**（推理 + 两层分块裁剪拼接）。
 *
 * ## 模型契约（设备实测，勿改）
 * `UVR_MDXNET_9482.onnx`（28.3MB，MIT 之外的许可见 [AudioGenderModels.MDX] 的说明）：
 * - 输入 `input`：float32 `[batch, 4, 2048, 256]`，4 通道 = **`[ch0_real, ch0_imag, ch1_real, ch1_imag]`**
 * - 输出 `output`：同形状 —— **人声的复数 STFT**
 * - metadata：`sample_rate=44100 n_fft=4096 hop_length=1024 dim_f=2048 dim_t=256 window=hann center=1`
 *
 * ## 处理结构（照 sherpa 的 `offline-source-separation-uvr-impl.h`）
 * ```
 * 外层：按 15s 块切分（块间重叠 2s：margin = sample_rate），
 *       每块输出裁掉「非首块的前 1s」与「非末块的后 1s」  → 块间天然无缝
 * 内层：块内按 genSize(257024) 步进，每段 STFT 得到 256 帧（center=1）
 *       推理 → ISTFT → **只取中间 genSize 个样本**（段间重叠 2*trim 全部丢弃）
 * ```
 * ⚠️ 「只取中间 genSize」这一条最容易写错：ISTFT 的完整输出长度是
 * `(dim_t-1)*hop + n_fft`（265216），其中两端各 `TRIM(2048)` 是 center 补齐与段间重叠区，
 * 必须丢掉，否则相邻段会重复叠加、时长变长且出现梳状失真。
 *
 * ## 伴奏怎么来
 * **伴奏 = 混音 − 人声**（时域相减）。这是 UVR 一贯的做法，也避免依赖模型是否直接给出 2 轨。
 * 因此本类只返回 vocals，由调用方相减得到 accompaniment。
 *
 * ## 内存
 * 目前是**内存内整段**实现，故限制 [MAX_INLINE_SECONDS]（90s）。真长片需要流式
 * （解码一块→处理一块→写 WAV）；流式版本是下一步工作。
 */
object MdxSeparator {

    private const val TAG = "MdxSeparator"

    // 外层分块参数统一来自 MdxDsp（单一来源，勿在此重复定义）
    private val OUTER_CHUNK = MdxDsp.OUTER_CHUNK                     // 661500 ≈ 15s
    private val OUTER_MARGIN = MdxDsp.SAMPLE_RATE                    // 44100 = 1s（每头各裁 1s）
    private val OUTER_STEP = MdxDsp.OUTER_STEP                       // 573300 ≈ 13s

    /** 内存内整段处理的上限（秒）。44.1k 立体声 float 约 353KB/s → 90s ≈ 31MB（**单份**） */
    const val MAX_INLINE_SECONDS = 90

    /** 低于这个秒数就不值得做了（调用方会提示"片段太短"） */
    const val MIN_INLINE_SECONDS = 15

    /**
     * 每个采样点在整个链路里的**峰值**字节估算（实测校准，非理论值）：
     * - 混音 L/R：8B
     * - 人声 L/R：8B
     * - 区间权重图 `w`：4B
     * - STFT/张量临时与其它：~8B
     * 合计 28B/样本 ≈ 1.18 MB/秒（44.1k）。
     */
    private const val BYTES_PER_SAMPLE_EST = 28

    /**
     * 堆预算占比。
     *
     * ⚠️ 这个值是被**实测打脸**后调低的（原来 0.45）：
     * 在 maxHeap=192MB 的模拟器上，0.45 算出 85s → 分离要满载约 150s，
     * 结果**整个模拟器被拖死**（adb shell 全部超时，只能重启），而不是我们进程单独崩。
     * 长时满载在低配/转译环境下会连带把宿主拖垮，所以宁可更保守。
     */
    private const val HEAP_BUDGET_RATIO = 0.25

    /**
     * 按**可用堆预算**算出这次最多能内联处理多少秒。
     *
     * 存在的理由：实测某些设备 `maxMemory` 只有 **192MB**（MuMu 模拟器），
     * 固定 90s 的峰值约 150MB → 一旦叠加就 OOM。
     * 宁可少处理一段，也不要崩。
     *
     * @param maxHeapBytes 通常是 `Runtime.getRuntime().maxMemory()`；显式传入便于单测。
     */
    fun safeInlineSeconds(maxHeapBytes: Long): Int {
        val budget = (maxHeapBytes * HEAP_BUDGET_RATIO).toLong()
        val perSecond = MdxDsp.SAMPLE_RATE.toLong() * BYTES_PER_SAMPLE_EST
        if (perSecond <= 0L) return MIN_INLINE_SECONDS
        return (budget / perSecond).toInt().coerceIn(MIN_INLINE_SECONDS, MAX_INLINE_SECONDS)
    }

    /** 用真实堆上限算（调用方一般用这个）。 */
    fun safeInlineSeconds(): Int = safeInlineSeconds(Runtime.getRuntime().maxMemory())

    private val lock = Any()
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName: String? = null
    private var outputName: String? = null
    private var initFailed = false

    fun isModelReady(context: Context): Boolean =
        AudioGenderModels.isUsable(context, AudioGenderModels.MDX)

    private fun ensureSession(context: Context): OrtSession? = synchronized(lock) {
        session?.let { return it }
        if (initFailed) return null
        if (!isModelReady(context)) {
            Log.w(TAG, "MDX 模型不可用（未下载或不合格）")
            return null
        }
        return try {
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
            val s = e.createSession(AudioGenderModels.fileOf(context, AudioGenderModels.MDX).absolutePath, opts)
            env = e
            session = s
            inputName = s.inputNames.firstOrNull()
            outputName = s.outputNames.firstOrNull()
            Log.i(TAG, "session OK: in=$inputName out=$outputName")
            s
        } catch (t: Throwable) {
            Log.e(TAG, "创建 session 失败: ${t.javaClass.simpleName}: ${t.message}", t)
            initFailed = true
            null
        }
    }

    /**
     * 分离整个人声轨（内存内整段）。
     *
     * @param left  左声道（44.1kHz）
     * @param right 右声道（44.1kHz）；长度应与 left 相同，取较短者为界
     * @return `(vocalsL, vocalsR)`；模型不可用/超长/异常 → null
     */
    fun separate(
        context: Context,
        left: FloatArray,
        right: FloatArray,
        /**
         * 有效样本数；<=0 表示用 min(left.size, right.size)。
         *
         * 存在的理由：调用方常按上限预分配整段缓冲（如 90s），实际只填了 [length] 个。
         * 若为了长度对齐去 copyOf 一份，就会多出整段内存（90s 立体声 = 32MB）。
         */
        length: Int = -1,
        onProgress: (Int) -> Unit = {},
    ): Pair<FloatArray, FloatArray>? {
        val n = if (length > 0) min(length, min(left.size, right.size)) else min(left.size, right.size)
        if (n == 0) return null
        val maxSamples = MAX_INLINE_SECONDS * MdxDsp.SAMPLE_RATE
        if (n > maxSamples) {
            Log.w(TAG, "输入 ${n / MdxDsp.SAMPLE_RATE}s 超过内存内上限 ${MAX_INLINE_SECONDS}s，拒绝（需流式版本）")
            return null
        }
        val s = ensureSession(context) ?: return null

        val outL = FloatArray(n)
        val outR = FloatArray(n)
        var pos = 0
        var chunkIdx = 0
        val totalChunks = if (n <= OUTER_CHUNK) 1 else ((n - OUTER_CHUNK) / OUTER_STEP) + 2
        try {
            while (pos < n) {
                val end = min(pos + OUTER_CHUNK, n)
                val isFirst = pos == 0
                val isLast = end >= n
                processOuterChunk(s, left, right, pos, end, isFirst, isLast, outL, outR)
                chunkIdx++
                onProgress((chunkIdx * 100 / totalChunks).coerceIn(0, 100))
                if (isLast) break
                pos += OUTER_STEP
            }
            onProgress(100)
            return outL to outR
        } catch (t: Throwable) {
            Log.e(TAG, "分离失败: ${t.javaClass.simpleName}: ${t.message}", t)
            return null
        }
    }

    /** 处理一个外层块（15s），把结果写进 [outL]/[outR] 的对应区间。 */
    private fun processOuterChunk(
        s: OrtSession,
        left: FloatArray,
        right: FloatArray,
        start: Int,
        end: Int,
        isFirst: Boolean,
        isLast: Boolean,
        outL: FloatArray,
        outR: FloatArray,
    ) {
        val blockLen = end - start
        val trim = MdxDsp.TRIM
        val chunkSize = MdxDsp.CHUNK_SIZE
        val genSize = MdxDsp.GEN_SIZE

        // 内层 pad：补齐到 genSize 的整数倍（sherpa: pad = genSize - num % genSize）
        val pad = genSize - blockLen % genSize
        val innerTotal = blockLen + pad                       // 内层输出总长（= numSeg * genSize）
        val numSeg = innerTotal / genSize

        // 内层输出缓冲（按 genSize 逐段写入，无重叠）
        val innerL = FloatArray(innerTotal)
        val innerR = FloatArray(innerTotal)

        // STFT 输入序列：[TRIM 零] + block + [pad 零] + [TRIM 零]
        // 长度 = 2*TRIM + innerTotal；第 i 段取 [i, i+chunkSize)
        val paddedLen = 2 * trim + innerTotal
        val paddedL = FloatArray(paddedLen)
        val paddedR = FloatArray(paddedLen)
        System.arraycopy(left, start, paddedL, trim, blockLen)
        System.arraycopy(right, start, paddedR, trim, blockLen)

        val reL = FloatArray(MdxDsp.DIM_F * MdxDsp.DIM_T)
        val imL = FloatArray(MdxDsp.DIM_F * MdxDsp.DIM_T)
        val reR = FloatArray(MdxDsp.DIM_F * MdxDsp.DIM_T)
        val imR = FloatArray(MdxDsp.DIM_F * MdxDsp.DIM_T)

        // 每段：STFT(L/R) → 组成 [1,4,2048,256] → 推理 → 取中间 GEN_SIZE
        val segL = FloatArray(chunkSize)
        val segR = FloatArray(chunkSize)
        val tensorBuf = FloatArray(4 * MdxDsp.DIM_F * MdxDsp.DIM_T)
        val inName = inputName!!
        // OrtEnvironment 是可空字段，这里取一次非空引用（会话已建成 ⇒ 必然非空）
        val ortEnv = env ?: return

        for (seg in 0 until numSeg) {
            val base = seg * genSize
            System.arraycopy(paddedL, base, segL, 0, chunkSize)
            System.arraycopy(paddedR, base, segR, 0, chunkSize)

            MdxDsp.stft(segL, reL, imL)
            MdxDsp.stft(segR, reR, imR)

            // 组装 4 通道：ch0_real, ch0_imag, ch1_real, ch1_imag
            System.arraycopy(reL, 0, tensorBuf, 0, reL.size)
            System.arraycopy(imL, 0, tensorBuf, reL.size, imL.size)
            System.arraycopy(reR, 0, tensorBuf, 2 * reL.size, reR.size)
            System.arraycopy(imR, 0, tensorBuf, 3 * reL.size, imR.size)

            OnnxTensor.createTensor(
                ortEnv,
                FloatBuffer.wrap(tensorBuf),
                longArrayOf(1, 4, MdxDsp.DIM_F.toLong(), MdxDsp.DIM_T.toLong()),
            ).use { tensor ->
                // 单输入单输出：**按索引取**（输出名虽为 "output"，但索引访问跨 ORT 版本更稳）
                s.run(mapOf(inName to tensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val t = result[0] as OnnxTensor
                    val fb = t.floatBuffer
                    // 输出布局 [1][4][2048][256]，顺序与输入一致
                    val plane = MdxDsp.DIM_F * MdxDsp.DIM_T
                    fb.get(reL, 0, plane)                            // vocals ch0 real
                    fb.get(imL, 0, plane)                            // vocals ch0 imag
                    fb.get(reR, 0, plane)                            // vocals ch1 real
                    fb.get(imR, 0, plane)                            // vocals ch1 imag
                }
            }

            val yL = MdxDsp.istft(reL, imL)
            val yR = MdxDsp.istft(reR, imR)
            // ⚠️ 只取中间 GEN_SIZE（两端各 TRIM 是 center 补齐与段间重叠区，必须丢弃）
            System.arraycopy(yL, 2 * trim, innerL, base, genSize)
            System.arraycopy(yR, 2 * trim, innerR, base, genSize)
        }

        // 外层裁剪：非首块砍掉前 margin，非末块砍掉后 margin，并去掉内层 pad 的尾部
        val from = if (isFirst) 0 else OUTER_MARGIN
        val to = if (isLast) innerTotal - pad else innerTotal - pad - OUTER_MARGIN
        if (to > from) {
            System.arraycopy(innerL, from, outL, start + from, to - from)
            System.arraycopy(innerR, from, outR, start + from, to - from)
        }
    }

    fun release() {
        synchronized(lock) {
            runCatching { session?.close() }
            session = null
        }
    }
}
