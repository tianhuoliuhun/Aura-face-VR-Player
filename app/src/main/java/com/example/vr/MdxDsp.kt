package com.example.vr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * v2.4.68（档 2）：MDX-Net 需要的 **DSP 原语**（FFT / STFT / ISTFT）。
 *
 * ## 参数从哪来（重要：不是猜的）
 * 全部**直接读自 ONNX 模型的 metadata**（设备实测）：
 * ```
 * model_name=UVR_MDXNET_9482  sample_rate=44100  n_fft=4096  win_length=4096
 * hop_length=1024  dim_f=2048  dim_t=256  window_type=hann  center=1  stems=2
 * ```
 * 分块与裁剪照 sherpa-onnx 的 `offline-source-separation-uvr-impl.h` 逐行对齐：
 * - `trim      = n_fft / 2              = 2048`
 * - `chunkSize = hop * (dim_t - 1)      = 261120`（内层段长，正好喂出 256 帧）
 * - `genSize   = chunkSize - 2 * trim   = 257024`（内层步进）
 * - STFT **center=1**：前后各补 `trim` 个零 → 帧数恰好 `dim_t`
 * - ISTFT 后**每段两端各裁 `trim`** → 段间天然无缝（**不需要交叉淡化**）
 *
 * ## 归一化约定
 * 与 PyTorch `torch.stft/istft(normalized=False)` 一致：**正向 FFT 不缩放、逆向除以 N**。
 * ISTFT 的 overlap-add 另外要**除以窗平方的累加和**（见 [istft]），否则幅度会随重叠次数放大。
 */
object MdxDsp {

    /** 模型要求（metadata 实测）：44.1 kHz 立体声 */
    const val SAMPLE_RATE = 44100

    const val N_FFT = 4096
    const val HOP = 1024

    /** 模型只吃前 `n_fft/2 = 2048` 个频点（完整 bin 数是 `n_fft/2+1 = 2049`，最后一个丢弃） */
    const val DIM_F = 2048

    /** 每次推理的时间帧数（固定） */
    const val DIM_T = 256

    /** 通道数：`[ch0_real, ch0_imag, ch1_real, ch1_imag]` */
    const val DIM_C = 4

    val TRIM = N_FFT / 2
    val CHUNK_SIZE = HOP * (DIM_T - 1)
    val GEN_SIZE = CHUNK_SIZE - 2 * TRIM

    /** ISTFT 输出长度（overlap-add 后的完整长度，裁两端 TRIM 后正好是 CHUNK_SIZE） */
    val ISTFT_FULL = (DIM_T - 1) * HOP + N_FFT

    // ------------------------------------------------------------------ 外层分块（单一来源）
    // ⚠️ 这三个值同时被 MdxSeparator（处理）与 AudioPcmExtractor44k（流式产出）使用，
    //    必须**只有一处定义** —— 否则改一边忘另一边就是「同一份数据两处登记」那个老坑。

    /** 外层块时长（秒）—— sherpa 的 `num_chunks` 默认 15 */
    const val OUTER_CHUNK_SEC = 15

    /** 外层块间重叠时长（秒）—— `margin = sample_rate` ⇒ 1 秒；两头各裁 1 秒 ⇒ 重叠 2 秒 */
    const val OUTER_OVERLAP_SEC = 2

    val OUTER_CHUNK = OUTER_CHUNK_SEC * SAMPLE_RATE

    val OUTER_STEP = (OUTER_CHUNK_SEC - OUTER_OVERLAP_SEC) * SAMPLE_RATE

    /** periodic Hann 窗（与 `torch.hann_window` 默认一致） */
    private val window: FloatArray = FloatArray(N_FFT) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / N_FFT)).toFloat()
    }

    /**
     * 预计算「窗平方的 overlap-add 归一化分母」。
     *
     * 帧 k 覆盖样本区间 `[k*HOP, k*HOP + N_FFT)`；对每个样本位置累加 `w[i]^2`，
     * ISTFT 时逐点相除 —— 这是 WOLA（weighted overlap-add）的标准做法，
     * 少了它幅度会随重叠帧数放大。
     */
    private val wss: FloatArray = FloatArray(ISTFT_FULL).also { acc ->
        for (k in 0 until DIM_T) {
            val base = k * HOP
            for (i in 0 until N_FFT) {
                val idx = base + i
                if (idx < acc.size) acc[idx] += window[i] * window[i]
            }
        }
        for (i in acc.indices) if (acc[i] < 1e-8f) acc[i] = 1f
    }

    // ---------------------------------------------------------------- FFT

    /**
     * 迭代式 radix-2 复数 FFT（原地）。长度必须是 2 的幂。
     *
     * 自写而非引第三方库：只需要 4096 点定点长度，几十行就够，也避免再加一个依赖
     * （BoomingSS 用的是 JTransforms，我们没这个必要）。
     */
    class Fft(private val n: Int) {
        private val levels = Integer.numberOfTrailingZeros(n)
        private val cosTab = FloatArray(n / 2) { i -> cos(2.0 * PI * i / n).toFloat() }
        private val sinTab = FloatArray(n / 2) { i -> sin(2.0 * PI * i / n).toFloat() }
        private val rev = IntArray(n).also { r ->
            for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - levels)
        }

        /** 正向变换（不缩放） */
        fun forward(re: FloatArray, im: FloatArray) = transform(re, im, inverse = false)

        /** 逆向变换（**除以 n**） */
        fun inverse(re: FloatArray, im: FloatArray) = transform(re, im, inverse = true)

        private fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
            require(re.size >= n && im.size >= n) { "FFT 长度不足" }
            // 位反转重排
            for (i in 0 until n) {
                val j = rev[i]
                if (j > i) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            val sign = if (inverse) 1f else -1f
            var len = 2
            while (len <= n) {
                val half = len / 2
                val step = n / len
                var start = 0
                while (start < n) {
                    var k = 0
                    var idx = 0
                    while (k < half) {
                        val wr = cosTab[idx]
                        val wi = sign * sinTab[idx]
                        val i0 = start + k
                        val i1 = i0 + half
                        val xr = re[i1] * wr - im[i1] * wi
                        val xi = re[i1] * wi + im[i1] * wr
                        re[i1] = re[i0] - xr
                        im[i1] = im[i0] - xi
                        re[i0] += xr
                        im[i0] += xi
                        k++
                        idx += step
                    }
                    start += len
                }
                len = len shl 1
            }
            if (inverse) {
                val inv = 1f / n
                for (i in 0 until n) {
                    re[i] *= inv
                    im[i] *= inv
                }
            }
        }
    }

    private val fft = Fft(N_FFT)

    // ---------------------------------------------------------------- STFT

    /**
     * 对**一个内层段**做 STFT（center=1，hann 窗）。
     *
     * @param chunk 长度应为 [CHUNK_SIZE]；不足部分按 0 处理（调用方负责 pad）
     * @param outRe 输出实部，长度 `DIM_F * DIM_T`，按 `[f * DIM_T + t]` 排列
     * @param outIm 输出虚部，同上
     */
    fun stft(chunk: FloatArray, outRe: FloatArray, outIm: FloatArray) {
        require(outRe.size >= DIM_F * DIM_T && outIm.size >= DIM_F * DIM_T)
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)
        // center=1：第 t 帧覆盖 padded 信号 [t*HOP, t*HOP+N_FFT)，其中 padded 前补 TRIM 个零
        for (t in 0 until DIM_T) {
            val startInChunk = t * HOP - TRIM
            for (i in 0 until N_FFT) {
                val src = startInChunk + i
                val v = if (src in chunk.indices) chunk[src] else 0f
                re[i] = v * window[i]
                im[i] = 0f
            }
            fft.forward(re, im)
            for (f in 0 until DIM_F) {
                outRe[f * DIM_T + t] = re[f]
                outIm[f * DIM_T + t] = im[f]
            }
        }
    }

    /**
     * 逆 STFT：输入 `[DIM_F, DIM_T]` 的实/虚部（**其余频点视为 0**），
     * 输出长度 [ISTFT_FULL] 的波形（已做 WOLA 归一化）。
     *
     * 调用方随后应**裁掉两端各 [TRIM] 个样本**，得到 [CHUNK_SIZE] 长度的段。
     */
    fun istft(inRe: FloatArray, inIm: FloatArray): FloatArray {
        require(inRe.size >= DIM_F * DIM_T && inIm.size >= DIM_F * DIM_T)
        val out = FloatArray(ISTFT_FULL)
        val re = FloatArray(N_FFT)
        val im = FloatArray(N_FFT)
        for (t in 0 until DIM_T) {
            // 组装整条频谱（实信号 → 共轭对称）；dim_f 之后的高频置 0
            java.util.Arrays.fill(re, 0f)
            java.util.Arrays.fill(im, 0f)
            for (f in 0 until DIM_F) {
                val vr = inRe[f * DIM_T + t]
                val vi = inIm[f * DIM_T + t]
                re[f] = vr
                im[f] = vi
                // 🔴 实信号频谱必须**共轭对称**：X[N-f] = conj(X[f])。
                //    只填 0..dim_f-1 会让 IFFT 得到「解析信号」——**实部只有原信号的一半**
                //    （单测 `istft amplitude ...` 当场抓到 0.5 → 0.25 的 2 倍幅度差）。
                //    f=0（DC）与 f=N/2（Nyquist）自共轭，不要重复填。
                val g = N_FFT - f
                if (g in 1 until N_FFT && g != f) {
                    re[g] = vr
                    im[g] = -vi
                }
            }
            fft.inverse(re, im)
            val base = t * HOP
            for (i in 0 until N_FFT) {
                val idx = base + i
                if (idx < out.size) out[idx] += re[i] * window[i]
            }
        }
        for (i in out.indices) out[i] /= wss[i]
        return out
    }

    /** 供单测/调试：把一段波形按 `genSize` 切段所需的 pad 长度（对齐 sherpa 的 `ComputeStft` 公式）。 */
    fun computePad(numSamples: Int): Int = GEN_SIZE - numSamples % GEN_SIZE

    /** 供单测/调试：窗平方累加和（检查 WOLA 归一化是否合理） */
    internal fun windowSumSquaresSample(idx: Int): Float = wss[idx]
}
