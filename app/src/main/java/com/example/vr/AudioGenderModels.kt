package com.example.vr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * v2.4.64：**音频性别过滤（离线分析）的模型管理**。
 *
 * ## 背景
 * `docs/AUDIO_GENDER_FILTER_DESIGN.md` 规划了「去男声、留女声与环境音」的离线预处理流水线。
 * 本次先落地**第一段**：抽音轨 → VAD → 说话人分离 → 性别判定（先验证识别是否准）。
 *
 * ## 为什么只有 3 个模型（与设计文档的差异）
 * sherpa-onnx 的 Java API **没有**暴露 source separation（`OfflineSourceSeparation` —— 实测
 * 1.13.6 与最新 1.13.8 都没有），因此设计文档里的「Spleeter 源分离」与「ML 性别分类」当前都不可用。
 * 但 **VAD 与说话人分离是现成的**，故本阶段用它们 + **基频（F0）声学判性别**（无需额外模型）。
 *
 * ## 模型来源（全部为 sherpa-onnx 官方 release，已逐个 HEAD 实测可达）
 * | 用途 | 文件 | 体积 |
 * |---|---|---|
 * | VAD | `asr-models/silero_vad.onnx` | ~0.6 MB |
 * | 说话人分割 | `speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2` | ~6.6 MB |
 * | 说话人嵌入 | `speaker-recongition-models/wespeaker_en_voxceleb_resnet34.onnx` | ~25 MB |
 *
 * ⚠️ 注意中间那个 tag 名是上游的**拼写笔误**（`recongition`，少了一个 `i`）—— 用正确拼写会 404。
 *
 * ## 与项目既有模型的关系
 * 下载/解压逻辑刻意与 [SherpaAsrManager] 保持一致（okhttp + commons-compress tar.bz2），
 * 落到 `filesDir/audio_gender_models/`，**与 ASR / LLM 模型互不干扰**。
 */
object AudioGenderModels {

    private const val TAG = "AudioGenderModels"
    private const val DIR = "audio_gender_models"
    private const val GH = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 一个待下载的模型。
     *
     * @param archive true = 远端是 tar.bz2 压缩包，需要解压并取出其中的 `.onnx`
     * @param fileName 落到本地后的**文件名**（解压场景是解压出来的 onnx 名）
     */
    data class ModelSpec(
        val id: String,
        val displayName: String,
        val url: String,
        val archive: Boolean,
        val fileName: String,
        val approxMb: Int,
        /**
         * 最小合理字节数：**低于它视为下载/解压不完整**。
         *
         * 🔴 为什么必须有：sherpa-onnx 的 native 侧读到坏文件会打 `F` 级日志后 **abort()**，
         * 进程直接闪退 —— Java 的 try/catch **抓不到**（v2.4.66 实测）。只能在**调用前**拦下。
         */
        val minBytes: Long,
    )

    val VAD = ModelSpec(
        id = "vad",
        displayName = "Silero VAD",
        url = "$GH/asr-models/silero_vad.onnx",
        archive = false,
        fileName = "silero_vad.onnx",
        approxMb = 1,
        minBytes = 100_000L,
    )

    val SEGMENTATION = ModelSpec(
        id = "segmentation",
        displayName = "pyannote segmentation-3.0",
        url = "$GH/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
        archive = true,
        fileName = "pyannote-segmentation-3-0.onnx",
        approxMb = 7,
        minBytes = 500_000L,
    )

    val EMBEDDING = ModelSpec(
        id = "embedding",
        displayName = "WeSpeaker resnet34",
        url = "$GH/speaker-recongition-models/wespeaker_en_voxceleb_resnet34.onnx",
        archive = false,
        fileName = "wespeaker_en_voxceleb_resnet34.onnx",
        approxMb = 26,
        minBytes = 5_000_000L,
    )

    /**
     * **性别分类**（档 1，v2.4.67）—— ECAPA-TDNN + 线性二分类，int8 动态量化，**MIT**。
     *
     * ⚠️ 远端路径**带子目录**（`onnx/model_quantized.onnx`），本地落盘用扁平文件名。
     * 输入 = 16 kHz raw 单声道波形、输出 = logits(2) = `{0: male, 1: female}`，
     * **log-mel 预处理已内嵌进 ONNX 图**（App 侧不要再做音频归一化）—— 详见 [GenderClassifier]。
     *
     * 该模型在 Hillenbrand 语料上男女准确率 100%（v1 全量量化只有 95.6%，v2 排除一个注意力 matmul 节点后恢复）。
     */
    val GENDER = ModelSpec(
        id = "gender",
        displayName = "ECAPA-TDNN 性别分类（q8）",
        url = "https://huggingface.co/Alice-Sabrina-Ivy/voice-gender-classifier-onnx-q8-v2" +
            "/resolve/main/onnx/model_quantized.onnx",
        archive = false,
        fileName = "voice-gender-classifier-q8.onnx",
        approxMb = 16,
        minBytes = 5_000_000L,
    )

    /**
     * **人声分离**（档 2，v2.4.68）—— UVR MDX-Net（`UVR_MDXNET_9482`），**28.3MB**。
     *
     * ## ⚠️ 许可（重要，必读）
     * 该模型由 sherpa-onnx 官方 release 提供，但**转换自 TRvlvr 的公开 UVR 模型 release**；
     * TRvlvr 的仓库**没有单独的开源许可**；UVR GUI 是 MIT，但要求「使用其模型的第三方应用**致谢 UVR 及其开发者**」。
     * → **绝不能把权重打进 APK**（只允许应用内下载到私有目录），且**界面需致谢 UVR**。
     *
     * ## 用途与技术约束（来自 BoomingSS 的真机实测经验）
     * - 2-stem（vocals / instrumental）；我们只用它做「**男声段换成伴奏**」；
     * - 管线要求 **44.1 kHz 立体声**，且需客户端做 **STFT / ISTFT**；
     * - 模型**内部已含 overlap 与 trim** → 每窗只取**稳定中心区**，相邻段**按精确帧索引拼接、不要交叉淡化**。
     *
     * ⚠️ 刻意**不放进 [ALL]**：体积大且属「档 2」功能，等实装完成后才在 UI 提示下载。
     */
    val MDX = ModelSpec(
        id = "mdx",
        displayName = "UVR MDX-Net 9482（人声分离）",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/UVR_MDXNET_9482.onnx",
        archive = false,
        fileName = "UVR_MDXNET_9482.onnx",
        approxMb = 28,
        minBytes = 20_000_000L,
    )

    val ALL: List<ModelSpec> = listOf(VAD, SEGMENTATION, EMBEDDING, GENDER)

    // ------------------------------------------------------------------ 路径

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun fileOf(context: Context, spec: ModelSpec): File = File(dir(context), spec.fileName)

    fun isReady(context: Context, spec: ModelSpec): Boolean =
        fileOf(context, spec).let { it.isFile && it.length() > 0L }

    /**
     * 文件是否**可用**（存在、足够大、可读）—— 比 [isReady] 更严格。
     *
     * 专用于「即将把路径交给 native 之前」的最后一道闸：sherpa-onnx 读到不完整的模型会
     * **abort()**（进程闪退，Java 抓不到），所以这里要挡住「下载被截断」「解压只出来半个文件」
     * 「文件不可读」这几种情况。
     */
    fun isUsable(context: Context, spec: ModelSpec): Boolean {
        val f = fileOf(context, spec)
        return f.isFile && f.length() >= spec.minBytes && f.canRead()
    }

    fun allReady(context: Context): Boolean = ALL.all { isReady(context, it) }

    /** 已下载体积（MB），供 UI 显示 */
    fun downloadedMb(context: Context): Long =
        ALL.sumOf { f -> fileOf(context, f).let { if (it.isFile) it.length() else 0L } } / 1048576L

    /** 缺失的模型（用于 UI 提示「还需下载 xx MB」） */
    fun missing(context: Context): List<ModelSpec> = ALL.filter { !isReady(context, it) }

    fun deleteAll(context: Context) {
        runCatching { dir(context).deleteRecursively() }
            .onFailure { Log.w(TAG, "清理模型失败: ${it.message}") }
    }

    // ------------------------------------------------------------------ 下载

    /**
     * 下载单个模型（幂等：已就绪直接返回 true）。
     *
     * @param onProgress 0..100
     */
    suspend fun download(context: Context, spec: ModelSpec, onProgress: (Int) -> Unit = {}): Boolean =
        withContext(Dispatchers.IO) {
            if (isReady(context, spec)) {
                onProgress(100)
                return@withContext true
            }
            val target = fileOf(context, spec)
            val tmp = File(dir(context), spec.fileName + ".part")
            try {
                Log.i(TAG, "开始下载 ${spec.displayName}（约 ${spec.approxMb}MB）")
                val request = Request.Builder().url(spec.url).build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "下载失败 ${spec.displayName}: HTTP ${resp.code}")
                        return@withContext false
                    }
                    val body = resp.body ?: return@withContext false
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        FileOutputStream(tmp).use { out ->
                            val buf = ByteArray(1 shl 16)
                            var read: Long = 0
                            var lastPct = -1
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                read += n
                                if (total > 0) {
                                    val pct = (read * 100 / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        onProgress(pct)
                                    }
                                }
                            }
                        }
                    }
                }
                if (spec.archive) {
                    // tar.bz2：解压并把其中的 .onnx 落到目标文件名
                    if (!extractOnnxFromTarBz2(tmp, target)) {
                        tmp.delete()
                        return@withContext false
                    }
                    tmp.delete()
                } else {
                    if (!tmp.renameTo(target)) {
                        tmp.copyTo(target, overwrite = true)
                        tmp.delete()
                    }
                }
                onProgress(100)
                val ok = isReady(context, spec)
                Log.i(TAG, "${spec.displayName} 下载${if (ok) "完成" else "后校验失败"}: ${target.length()} 字节")
                ok
            } catch (t: Throwable) {
                Log.e(TAG, "下载 ${spec.displayName} 异常: ${t.message}", t)
                runCatching { tmp.delete() }
                false
            }
        }

    /** 依次下载全部缺失模型；任一失败即中止并返回 false。 */
    suspend fun ensureAll(context: Context, onProgress: (String, Int) -> Unit = { _, _ -> }): Boolean {
        for (spec in ALL) {
            if (isReady(context, spec)) {
                onProgress(spec.displayName, 100)
                continue
            }
            onProgress(spec.displayName, 0)
            val ok = download(context, spec) { pct -> onProgress(spec.displayName, pct) }
            if (!ok) return false
        }
        return true
    }

    /** 从 tar.bz2 里找出第一个 *.onnx 并写到 [target]。 */
    private fun extractOnnxFromTarBz2(archive: File, target: File): Boolean {
        return try {
            TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.lowercase().endsWith(".onnx")) {
                        target.outputStream().use { out ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = tar.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                            }
                        }
                        Log.i(TAG, "解压 ${entry.name} -> ${target.name}")
                        return true
                    }
                    entry = tar.nextEntry
                }
            }
            Log.e(TAG, "压缩包内未找到 .onnx：${archive.name}")
            false
        } catch (t: Throwable) {
            Log.e(TAG, "解压失败 ${archive.name}: ${t.message}", t)
            false
        }
    }
}
