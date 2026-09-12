package com.example.vr

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.QnnConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * v111：sherpa-onnx 多引擎离线语音识别管理器。
 *
 * 支持两种引擎（由 AsrEngineType 控制）：
 *  - Qwen3-ASR 0.6B INT8：29 语言 + 20 种方言，CPU 推理，~940MB 模型
 *  - SenseVoice QNN：中英日韩粤 5 语言，高通 NPU 加速（SM8850 专属），~241MB 模型
 *
 * 模型均为首次使用时下载缓存，支持断点续传。
 */
object SherpaAsrManager {

    private const val TAG = "SherpaAsr"

    // ===== Qwen3-ASR 配置 =====
    private const val QWEN3_ARCHIVE = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2"
    private const val QWEN3_DIR_NAME = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"
    private const val QWEN3_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$QWEN3_ARCHIVE"
    private const val QWEN3_SIZE_MB = 838
    private const val QWEN3_CONV_FRONTEND = "conv_frontend.onnx"
    private const val QWEN3_ENCODER = "encoder.int8.onnx"
    private const val QWEN3_DECODER = "decoder.int8.onnx"
    private const val QWEN3_TOKENIZER = "tokenizer"

    // ===== SenseVoice QNN 配置（v118：模型与 SoC 绑定，改为按设备自动选择）=====
    //
    // 官方 QNN context binary 是为具体 SoC 编译的产物，跨 SoC 加载必然失败。
    // 旧代码把 SM8850 写死，导致非 SM8850 设备即使修好运行库也跑不起来。
    // 下面这些 SoC 官方都发布了 5 秒档 sense-voice（中英日韩粤）模型。
    // 秒数档保持 5 秒：字幕通常一句 2~5 秒，档位越大越容易把多句并成一条。
    private val QNN_SUPPORTED_SOCS = listOf(
        "SM8850", "SM8750", "SM8650", "SM8550", "SM8475", "SM8450", "SM8350",
        "SA8295", "SA8255", "QCS9100"
    )
    private const val SV_MODEL_STEM =
        "sherpa-onnx-qnn-%s-binary-5-seconds-sense-voice-zh-en-ja-ko-yue-2024-07-17-int8"
    private const val SV_BASE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn-binary"
    private const val SV_SIZE_MB = 161
    private const val SV_MODEL_BIN = "model.bin"
    private const val SV_TOKENS = "tokens.txt"

    // ===== 共享状态 =====
    var isModelDownloading by mutableStateOf(false)
    var modelDownloadProgress by mutableFloatStateOf(0f)
    var downloadStatus by mutableStateOf("就绪")

    /** v118：最近一次识别器初始化失败的原因；null 表示未失败（供 UI 显示真实原因而非"请先下载"） */
    var lastInitError: String? = null
        private set

    private var downloadJob: Job? = null
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .build()

    // ===== 模型路径 =====

    private fun qwen3Dir(context: Context) = File(context.filesDir, "sherpa_models/$QWEN3_DIR_NAME")

    /**
     * 当前设备 SoC 型号（供 UI 展示）。QNN 模型按它自动匹配，
     * 因此界面上直接显示真实 SoC 比写死 "SM8850" 更有参考价值。
     */
    fun deviceSocName(): String = deviceSocModel()

    /**
     * 当前设备对应的 SenseVoice QNN 模型目录名，
     * 形如 `sherpa-onnx-qnn-SM8750-binary-5-seconds-sense-voice-...-int8`。
     * 设备 SoC 不在官方支持列表内时返回 null（QNN 无法在该设备上工作）。
     */
    fun senseVoiceModelDirName(context: Context): String? {
        val soc = deviceSocModel()
        val hit = QNN_SUPPORTED_SOCS.firstOrNull { soc.contains(it, ignoreCase = true) }
        return hit?.let { SV_MODEL_STEM.format(it) }
    }

    private fun svDir(context: Context): File =
        File(context.filesDir, "sherpa_models/${senseVoiceModelDirName(context) ?: "sensevoice-unsupported"}")

    /** 检查指定引擎的模型是否就绪 */
    fun isModelReady(context: Context, engine: AsrEngineType): Boolean = when (engine) {
        AsrEngineType.QWEN3 -> {
            val dir = qwen3Dir(context)
            dir.resolve(QWEN3_CONV_FRONTEND).exists() &&
                dir.resolve(QWEN3_ENCODER).exists() &&
                dir.resolve(QWEN3_DECODER).exists() &&
                dir.resolve(QWEN3_TOKENIZER).resolve("vocab.json").exists()
        }
        AsrEngineType.SENSEVOICE_QNN -> {
            val dir = svDir(context)
            // v118：加最小尺寸校验，避免下载中断留下的 0 字节残留文件被误判为"已就绪"
            val bin = dir.resolve(SV_MODEL_BIN)
            val tok = dir.resolve(SV_TOKENS)
            // SoC 不在官方支持列表时，模型再完整也跑不了 QNN，一律视为未就绪
            senseVoiceModelDirName(context) != null &&
                bin.exists() && bin.length() > 1_000_000L && tok.exists() && tok.length() > 1024L
        }
        else -> false
    }

    // ===== 下载管理 =====

    fun startModelDownload(context: Context, engine: AsrEngineType) {
        if (isModelDownloading) return
        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            when (engine) {
                AsrEngineType.QWEN3 -> downloadQwen3(context)
                AsrEngineType.SENSEVOICE_QNN -> downloadSenseVoice(context)
                else -> {}
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        isModelDownloading = false
        modelDownloadProgress = 0f
        downloadStatus = "已取消"
    }

    // ===== Qwen3-ASR 下载 =====

    private suspend fun downloadQwen3(context: Context): File? = withContext(Dispatchers.IO) {
        if (isModelReady(context, AsrEngineType.QWEN3)) {
            Log.i(TAG, "Qwen3 cached: ${qwen3Dir(context)}")
            return@withContext qwen3Dir(context)
        }
        val targetDir = qwen3Dir(context)
        targetDir.mkdirs()
        val archive = File(context.cacheDir, QWEN3_ARCHIVE)
        downloadAndExtract(archive, QWEN3_URL, QWEN3_SIZE_MB, targetDir) ?: return@withContext null
        // 修复目录嵌套
        val inner = File(targetDir, QWEN3_DIR_NAME)
        if (inner.exists()) { inner.listFiles()?.forEach { it.renameTo(File(targetDir, it.name)) }; inner.delete() }
        targetDir
    }

    // ===== SenseVoice QNN 下载 =====

    private suspend fun downloadSenseVoice(context: Context): File? = withContext(Dispatchers.IO) {
        val dirName = senseVoiceModelDirName(context)
        if (dirName == null) {
            val soc = deviceSocModel()
            val msg = "当前设备 SoC（$soc）无官方 QNN 模型，SenseVoice NPU 加速不可用"
            Log.e(TAG, "$msg；支持列表=$QNN_SUPPORTED_SOCS")
            withContextMain { isModelDownloading = false; downloadStatus = msg }
            return@withContext null
        }
        if (isModelReady(context, AsrEngineType.SENSEVOICE_QNN)) {
            Log.i(TAG, "SenseVoice QNN cached: ${svDir(context)}")
            return@withContext svDir(context)
        }
        val targetDir = svDir(context)
        targetDir.mkdirs()
        val archive = File(context.cacheDir, "$dirName.tar.bz2")
        val url = "$SV_BASE_URL/$dirName.tar.bz2"
        Log.i(TAG, "SenseVoice QNN download: $url -> $targetDir")
        downloadAndExtract(archive, url, SV_SIZE_MB, targetDir) ?: return@withContext null
        // 修复目录嵌套：tar 包内含同名顶级目录，解压后需要把内容上提一层
        val inner = File(targetDir, dirName)
        if (inner.exists()) { inner.listFiles()?.forEach { it.renameTo(File(targetDir, it.name)) }; inner.delete() }
        targetDir
    }

    // ===== 通用下载+解压（断点续传）=====

    private suspend fun downloadAndExtract(archive: File, url: String, sizeMB: Int, destDir: File): File? {
        withContextMain { isModelDownloading = true; modelDownloadProgress = 0f; downloadStatus = "连接服务器..." }

        var retries = 3
        while (retries > 0) {
            withContextMain {
                modelDownloadProgress = 0f
                downloadStatus = if (retries < 3) "第 ${4 - retries} 次重试..." else "连接中..."
            }
            try {
                // 断点续传
                val existingSize = if (archive.exists()) archive.length() else 0L
                val reqBuilder = Request.Builder().url(url)
                if (existingSize > 0) reqBuilder.addHeader("Range", "bytes=$existingSize-")
                val req = reqBuilder.build()

                val ok = httpClient.newCall(req).execute().use { resp ->
                    if (resp.code != 200 && resp.code != 206) {
                        Log.e(TAG, "HTTP ${resp.code}")
                        if (existingSize > 0) archive.delete()
                        return@use false
                    }
                    val body = resp.body ?: return@use false
                    val contentLen = body.contentLength()
                    val total = if (resp.code == 206) existingSize + contentLen else contentLen
                    if (contentLen <= 0) return@use false
                    var downloaded = if (resp.code == 206) existingSize else 0L
                    body.byteStream().use { input ->
                        FileOutputStream(archive, resp.code == 206).use { out ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                if (Thread.interrupted()) return@use false
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                downloaded += n
                                if (total > 0) {
                                    val p = downloaded.toFloat() / total
                                    withContextMain {
                                        modelDownloadProgress = p
                                        val dmb = downloaded / (1024 * 1024); val tmb = total / (1024 * 1024)
                                        downloadStatus = "下载 ${(p * 100).toInt()}% ($dmb/${tmb}MB)"
                                            .let { if (resp.code == 206) "续传 $it" else it }
                                    }
                                }
                            }
                        }
                    }
                    true
                }

                if (!ok) {
                    retries--
                    val partialMB = if (archive.exists()) archive.length() / (1024 * 1024) else 0
                    if (retries > 0) {
                        withContextMain { downloadStatus = if (partialMB > 0) "中断（已缓存 ${partialMB}MB），2秒后续传..." else "下载失败，2秒后重试..." }
                        delay(2000); continue
                    } else {
                        withContextMain { downloadStatus = "下载失败，请检查网络" }
                        return null
                    }
                }

                // 解压
                withContextMain { downloadStatus = "解压模型中（${sizeMB}MB）..." }
                extractTarBz2(archive, destDir)
                archive.delete()

                // 修复目录嵌套：tar 包内含顶级目录，解压后变成 destDir/model-dir/file
                // 需要把内容上提一层到 destDir/file
                val expectedEntries = destDir.listFiles()?.filter { it.isDirectory }?.flatMap { it.listFiles()?.toList() ?: emptyList() } ?: emptyList()
                if (expectedEntries.any { it.name.endsWith(".onnx") || it.name.endsWith(".bin") || it.name == "tokens.txt" }) {
                    // 有嵌套目录，上提内容
                    destDir.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
                        subDir.listFiles()?.forEach { it.renameTo(File(destDir, it.name)) }
                        subDir.delete()
                    }
                }

                withContextMain {
                    isModelDownloading = false
                    modelDownloadProgress = 1f
                    downloadStatus = "模型下载完成"
                }
                return destDir

            } catch (e: CancellationException) {
                withContextMain { isModelDownloading = false; downloadStatus = "已取消" }
                throw e
            } catch (e: Exception) {
                retries--
                if (retries > 0) {
                    withContextMain { downloadStatus = "出错，2秒后重试..." }
                    delay(2000)
                } else {
                    withContextMain { isModelDownloading = false; downloadStatus = "失败: ${e.message}" }
                    return null
                }
            }
        }
        withContextMain { isModelDownloading = false; downloadStatus = "下载失败" }
        return null
    }

    // ===== tar.bz2 解压 =====

    private fun extractTarBz2(archive: File, destDir: File) {
        // 方案1：系统 tar（toybox，Android 自带，支持 bzip2）
        try {
            val pb = ProcessBuilder("tar", "xjf", archive.absolutePath, "-C", destDir.absolutePath)
            pb.redirectErrorStream(true)
            val proc = pb.start(); val out = proc.inputStream.bufferedReader().readText(); val exit = proc.waitFor()
            if (exit == 0) { Log.i(TAG, "Extracted via system tar"); return }
            Log.w(TAG, "system tar failed (exit $exit): ${out.take(500)}")
        } catch (e: Exception) { Log.w(TAG, "system tar unavailable: ${e.message}") }

        // 方案2：commons-compress
        try {
            FileInputStream(archive).use { fis ->
                BufferedInputStream(fis).use { bis ->
                    BZip2CompressorInputStream(bis).use { bz2 ->
                        TarArchiveInputStream(bz2).use { tar ->
                            var entry = tar.nextEntry
                            while (entry != null) {
                                val name = entry.name.replace("\\", "/")
                                val safeName = name.split("/").filter { it.isNotBlank() && it != ".." }.joinToString("/")
                                if (safeName.isNotEmpty()) {
                                    val outFile = File(destDir, safeName)
                                    if (entry.isDirectory) outFile.mkdirs()
                                    else { outFile.parentFile?.mkdirs(); FileOutputStream(outFile).use { out -> tar.copyTo(out) } }
                                }
                                entry = tar.nextEntry
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "Extract failed", e); throw e }
    }

    // ===== QNN 运行库准备（v118）=====

    /** QNN 必需的基础运行库，缺任一都会让 deviceCreate 失败 */
    private val QNN_REQUIRED_LIBS = listOf(
        "libQnnHtp.so",
        "libQnnSystem.so",
        "libQnnHtpPrepare.so"
    )

    /** 当前设备 SoC 型号（QNN 的 context binary 与 SoC 强绑定，如 SM8850 专用） */
    @Suppress("NewApi")
    private fun deviceSocModel(): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.ifBlank { Build.HARDWARE }
        } else {
            Build.HARDWARE
        }
    } catch (e: Throwable) {
        Build.HARDWARE
    }

    /**
     * 准备 QNN 运行环境，返回 QNN 运行库所在目录；不可用时返回 null。
     *
     * 两件事缺一不可（对照 sherpa-onnx 官方 Android QNN demo）：
     *
     * 1) 设置 ADSP_LIBRARY_PATH，指向存放 libQnnHtpV*Skel.so 的目录。
     *    不设置时 qnn_interface.deviceCreate() 返回错误码 1008，HTP 完全起不来。
     *    sherpa 把实现放在 JNI 里（PrependAdspLibraryPath，见 jni/common.cc），
     *    经 OfflineRecognizer.prependAdspLibraryPath 暴露；内部用 ";" 前置拼接，
     *    必须是前置（prepend），且分隔符是分号不是冒号。
     *
     * 2) 运行库必须是磁盘上的真实文件 —— QNN 侧用 dlopen 打开，不会去 APK 内部找。
     *    targetSdk>=30 时 AGP 默认 extractNativeLibs=false，so 以未压缩形式留在 APK 内、
     *    不展开到 lib/<abi>/（实测该目录为空），故 app/build.gradle.kts 里设了
     *    packaging.jniLibs.useLegacyPackaging = true 让 so 落盘。
     */
    private fun prepareQnnRuntime(context: Context): String? {
        val libDir = context.applicationInfo.nativeLibraryDir
        val missing = QNN_REQUIRED_LIBS.filter { !File(libDir, it).exists() }
        if (missing.isNotEmpty()) {
            Log.e(
                TAG,
                "QNN 运行库缺失 $missing（目录 $libDir）。请确认 app/build.gradle.kts 中 " +
                    "packaging.jniLibs.useLegacyPackaging = true，且 jniLibs/arm64-v8a 下 QNN 库完整"
            )
            return null
        }
        return try {
            OfflineRecognizer.prependAdspLibraryPath(libDir)
            Log.i(TAG, "QNN: ADSP_LIBRARY_PATH 已前置 $libDir（DSP 由此加载 skel 库）")
            libDir
        } catch (e: Throwable) {
            Log.e(TAG, "QNN: prependAdspLibraryPath 失败: ${e.message}", e)
            null
        }
    }

    // ===== 识别器创建（供 AsrBatchTranscriber 调用）=====

    fun createRecognizer(context: Context, engine: AsrEngineType, language: String = ""): OfflineRecognizer? = when (engine) {
        AsrEngineType.QWEN3 -> createQwen3Recognizer(context, language)
        AsrEngineType.SENSEVOICE_QNN -> createSenseVoiceQnnRecognizer(context, language)
        else -> null
    }

    /** 支持的语言（中/英/日/韩 + 自动检测） */
    val sherpaLanguages = listOf(
        "auto" to "自动",
        "zh" to "中文",
        "en" to "英文",
        "ja" to "日文",
        "ko" to "韩文"
    )

    private fun createQwen3Recognizer(context: Context, language: String = ""): OfflineRecognizer? {
        lastInitError = null
        val dir = qwen3Dir(context)
        if (!isModelReady(context, AsrEngineType.QWEN3)) {
            Log.w(TAG, "Qwen3 model not ready")
            lastInitError = "模型未下载或文件不完整"
            return null
        }
        val langCode = if (language == "auto") "" else language  // Qwen3: 空字符串=自动
        return try {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    qwen3Asr = OfflineQwen3AsrModelConfig(
                        convFrontend = dir.resolve(QWEN3_CONV_FRONTEND).absolutePath,
                        encoder = dir.resolve(QWEN3_ENCODER).absolutePath,
                        decoder = dir.resolve(QWEN3_DECODER).absolutePath,
                        tokenizer = dir.resolve(QWEN3_TOKENIZER).absolutePath,
                        // Qwen3 通过 tokenizer 自动检测语言，无需 language 参数
                    ),
                    tokens = "",
                    numThreads = 3,
                    debug = false,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
                maxActivePaths = 4,
                blankPenalty = 0.0f,
            )
            OfflineRecognizer(null, config).also { Log.i(TAG, "Qwen3-ASR recognizer created (lang=$langCode)") }
        } catch (e: Throwable) {
            Log.e(TAG, "Qwen3 init failed: ${e.message}", e)
            lastInitError = "初始化失败：${e.message ?: e.javaClass.simpleName}"
            null
        }
    }

    private fun createSenseVoiceQnnRecognizer(context: Context, language: String = "auto"): OfflineRecognizer? {
        lastInitError = null
        val soc = deviceSocModel()
        // 先判 SoC：官方 context binary 与 SoC 绑定，没有对应模型就没必要往下走
        val modelDir = senseVoiceModelDirName(context)
        if (modelDir == null) {
            Log.e(TAG, "SenseVoice QNN: 当前 SoC=$soc 不在官方支持列表 $QNN_SUPPORTED_SOCS 内")
            lastInitError = "当前设备（$soc）无官方 QNN 模型，请改用 Qwen3-ASR 或 Vosk"
            return null
        }
        val dir = svDir(context)
        if (!isModelReady(context, AsrEngineType.SENSEVOICE_QNN)) {
            Log.w(TAG, "SenseVoice QNN model not ready: ${dir.absolutePath}")
            lastInitError = "模型未下载或文件不完整"
            return null
        }

        // v118：QNN 运行库须落盘 + 设置 ADSP_LIBRARY_PATH，否则 HTP deviceCreate 报 1008
        val libDir = prepareQnnRuntime(context) ?: run {
            lastInitError = "QNN 运行库不可用（libQnnHtp.so / libQnnSystem.so 未落盘）"
            return null
        }
        val qnnHtpPath = File(libDir, "libQnnHtp.so").absolutePath
        val qnnSysPath = File(libDir, "libQnnSystem.so").absolutePath
        val contextBinPath = dir.resolve(SV_MODEL_BIN).absolutePath
        val tokensPath = dir.resolve(SV_TOKENS).absolutePath
        Log.i(TAG, "SenseVoice QNN: soc=$soc model=$modelDir htp=$qnnHtpPath ctx=$contextBinPath lang=$language")
        return try {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        language = language,
                        qnnConfig = QnnConfig(
                            backendLib = qnnHtpPath,
                            systemLib = qnnSysPath,
                            contextBinary = contextBinPath,
                        ),
                    ),
                    tokens = tokensPath,
                    numThreads = 1,
                    debug = false,
                    provider = "qnn",
                ),
                decodingMethod = "greedy_search",
            )
            OfflineRecognizer(null, config).also { Log.i(TAG, "SenseVoice QNN recognizer created (lang=$language)") }
        } catch (e: Throwable) {
            // 注意用 Throwable：native 初始化失败可能抛 UnsatisfiedLinkError 等 Error 子类，
            // catch Exception 拦不住（同 DecoderCapabilities 的教训）
            Log.e(TAG, "SenseVoice QNN init failed: ${e.message}", e)
            lastInitError = "QNN 初始化失败：${e.message ?: e.javaClass.simpleName}"
            null
        }
    }

    /** 识别单段 PCM16 float 采样（[-1,1] 归一化，16kHz 单声道） */
    fun recognizeSegment(recognizer: OfflineRecognizer, samples: FloatArray): String {
        return try {
            val stream = recognizer.createStream()
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text.trim()
            stream.release()
            text
        } catch (e: Exception) { Log.e(TAG, "recognizeSegment failed: ${e.message}", e); "" }
    }

    fun shutdown() { cancelDownload() }

    private suspend fun withContextMain(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
}
