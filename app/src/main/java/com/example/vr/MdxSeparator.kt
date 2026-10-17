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

    /** sherpa 的 `num_chunks` 默认值（metadata 外的预配置常量） */
    private const val NUM_CHUNKS = 15

    private val OUTER_CHUNK = NUM_CHUNKS * MdxDsp.SAMPLE_RATE        // 661500 ≈ 15s
    private val OUTER_MARGIN = MdxDsp.SAMPLE_RATE                    // 44100 = 1s
    private val OUTER_STEP = OUTER_CHUNK - 2 * OUTER_MARGIN          // 573300 ≈ 13s

    /** 内存内整段处理的上限（秒）。44.1k 立体声 float 约 353KB/s → 90s ≈ 31MB */
    const val MAX_INLINE_SECONDS = 90

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
        onProgress: (Int) -> Unit = {},
    ): Pair<FloatArray, FloatArray>? {
        val n = min(left.size, right.size)
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
