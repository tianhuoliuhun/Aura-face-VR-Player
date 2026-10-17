package com.example.vr

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * v2.4.67：**两套 `libonnxruntime.so` 的共存自检**（阶段 A 的关键验证）。
 *
 * ## 为什么需要它
 * 项目里已经有一个 onnxruntime —— **sherpa-onnx 的 AAR 自带** `jni/<abi>/libonnxruntime.so`
 * （实测 1.13.6 内嵌 **1.27.1**）。而「档 1 性别分类 / 档 2 源分离」要用的自定义 ONNX 模型
 * 需要 `onnxruntime-android`（选的 **1.27.0**，同一 minor）。
 *
 * 两套都会提供**同名**的 `libonnxruntime.so` → 打包时用 `pickFirsts` 去重；
 * 但**去重之后两份 Java 层能否共用同一份 native 库**，只能**在同进程里真跑一次**才知道。
 *
 * 🔴 **为什么必须实测**：native 层不兼容（ABI/符号）会直接 **abort / SIGSEGV** ——
 * Java 的 `try/catch` **抓不到**，进程当场消失。所以：
 * - 本自检**延后 5 秒**执行（主界面已起来，能看清崩在哪一步）；
 * - 结果靠 **logcat 的 `OnnxCoexist` 标签 + 进程是否存活**共同判断；
 * - 只跑一次（用独立 prefs 记录），不重复占用启动时间。
 *
 * ## 判断标准
 * | 现象 | 结论 |
 * |---|---|
 * | 打印了「ORT env OK」→「sherpa Vad OK」→「共存自检结束」，且进程活着 | ✅ 可共存 |
 * | 打印到某一步后**进程消失**（logcat 出现 `has died`） | ❌ 该步的 native 库有问题 |
 * | 打印了 Java 异常（如 UnsatisfiedLinkError） | ⚠️ 加载问题，可换 pickFirst 来源再试 |
 */
object OnnxCoexistSelfTest {

    private const val TAG = "OnnxCoexist"

    /** 独立 prefs，避免污染项目自己的 `vr_player_prefs` */
    private const val PREF_FILE = "onnx_coexist_selftest"
    private const val PREF_KEY = "checked_v1"

    /**
     * 只执行一次（幂等）。**异步 + 延后**，绝不阻塞启动。
     */
    fun runOnce(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_KEY, false)) return
        prefs.edit().putBoolean(PREF_KEY, true).apply()

        val app = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            delay(5_000) // 让主界面先起来：万一 native 崩，日志能定位到这一步
            Log.i(TAG, "===== native 共存自检开始（onnxruntime-android vs sherpa-onnx）=====")

            // ① 先单独用 ort：加载 so + 建 session
            try {
                ortStep(app)
            } catch (t: Throwable) {
                Log.e(TAG, "① ORT 步骤抛 Java 异常：${t.javaClass.simpleName}: ${t.message}", t)
            }

            // ② 再用 sherpa：加载它自己的 so（含它内嵌的 ORT）
            try {
                sherpaStep(app)
            } catch (t: Throwable) {
                Log.e(TAG, "② sherpa 步骤抛 Java 异常：${t.javaClass.simpleName}: ${t.message}", t)
            }

            // ③ 档1：性别分类模型（ECAPA-TDNN）冒烟 —— 用合成音频跑一次真实推理
            try {
                genderStep(app)
            } catch (t: Throwable) {
                Log.e(TAG, "③ 性别模型步骤抛 Java 异常：${t.javaClass.simpleName}: ${t.message}", t)
            }

            // ④ 档2：MDX-Net 源分离模型的**签名探测**（输入/输出的名字、类型、形状）
            //    这一步只把「模型契约」打出来 —— 拿到它才能确定 STFT 参数与张量布局，
            //    否则 DSP 只能靠猜。
            try {
                mdxProbeStep(app)
            } catch (t: Throwable) {
                Log.e(TAG, "④ MDX 探测步骤抛 Java 异常：${t.javaClass.simpleName}: ${t.message}", t)
            }

            Log.i(TAG, "===== ✓ 共存自检结束（本行出现且进程存活 ⇒ 两套 libonnxruntime 可共存）=====")
        }
    }

    /** ① ONNX Runtime：建环境 + 列 providers（都会进 native），有条件再建一个真实 session。 */
    private fun ortStep(context: Context) {
        val env = OrtEnvironment.getEnvironment()
        Log.i(TAG, "① ORT env OK")
        val providers = try {
            // ⚠️ ORT Java API 里这是**静态**方法（`OrtEnvironment.getAvailableProviders()`），
            //    不是 `env` 的实例属性 —— 写成属性会 Unresolved reference。
            OrtEnvironment.getAvailableProviders().toString()
        } catch (t: Throwable) {
            "查询失败: ${t.message}"
        }
        Log.i(TAG, "① ORT availableProviders = $providers")

        // 用**已有的** ONNX 模型真建一次 session —— 这一步最能证明 native 可用
        //（silero_vad.onnx 是标准 ONNX，可被 ort 直接加载；没下载就跳过）
        val vadFile = AudioGenderModels.fileOf(context, AudioGenderModels.VAD)
        if (!vadFile.isFile) {
            Log.i(TAG, "① ORT：本地无 ONNX 模型（VAD 未下载），跳过建 session（已足以证明 so 已加载）")
            return
        }
        var opts: OrtSession.SessionOptions? = null
        var session: OrtSession? = null
        try {
            opts = OrtSession.SessionOptions()
            session = env.createSession(vadFile.absolutePath, opts)
            Log.i(TAG, "① ORT session OK: inputs=${session.inputNames} outputs=${session.outputNames}")
        } finally {
            runCatching { session?.close() }
            runCatching { opts?.close() }
        }
    }

    /** ② sherpa-onnx：构造一个最轻的 VAD 并真跑一次（会加载 sherpa 的 jni + 它依赖的 onnxruntime）。 */    private fun sherpaStep(context: Context) {
        val vadFile = AudioGenderModels.fileOf(context, AudioGenderModels.VAD)
        if (!vadFile.isFile) {
            Log.i(TAG, "② sherpa：VAD 模型未下载，跳过（无法在此步骤加载 sherpa native）")
            return
        }
        val cfg = VadModelConfig(
            // ⚠️ v2.4.66 教训：用**绝对路径**时必须让 assetManager 为 null，否则 sherpa 会
            //    把它当 assets 相对路径去找 → 读失败 → native abort。
            sileroVadModelConfig = SileroVadModelConfig(model = vadFile.absolutePath),
            sampleRate = 16000,
        )
        var vad: Vad? = null
        try {
            vad = Vad(null, cfg)
            // 喂 0.5 秒静音，跑通一次完整调用（acceptWaveform + flush）
            vad.acceptWaveform(FloatArray(8000))
            vad.flush()
            Log.i(TAG, "② sherpa Vad OK（已接受一次波形并 flush）")
        } finally {
            runCatching { vad?.release() }
        }
    }

    /**
     * ③ 档1 冒烟：性别分类模型（ECAPA-TDNN）—— 用 1.5 s **合成正弦**跑一次真实推理。
     *
     * ⚠️ 这里**只验证链路**（模型能加载、输入输出形状对、能算出概率），
     * **不校验判定结果** —— 合成单音本来就不是人声，男/女都无意义。
     * 真正的准确率要用日语语料（JVS）验收，见 docs/AUDIO_GENDER_FILTER_TECH_STACK.md。
     */
    private suspend fun genderStep(context: Context) {
        if (!GenderClassifier.isAvailable(context)) {
            // 缺失 → 走**应用自己的下载逻辑**补齐（顺便验证真实下载链路 + URL 正确性）
            Log.i(TAG, "③ 性别模型缺失 → 开始下载（约 16MB，走 AudioGenderModels 的真实路径）")
            val ok = AudioGenderModels.download(context, AudioGenderModels.GENDER) { pct ->
                if (pct == 100 || pct % 25 == 0) Log.i(TAG, "③ 下载进度 $pct%")
            }
            if (!ok) {
                Log.e(TAG, "③ 性别模型下载失败 → 跳过冒烟（判定将回退 F0；检查网络/URL）")
                return
            }
            Log.i(TAG, "③ 性别模型下载完成: ${AudioGenderModels.fileOf(context, AudioGenderModels.GENDER).length() / 1048576}MB")
        }
        val sr = 16000
        val pcm = FloatArray(sr * 3 / 2) { i ->
            (0.3f * kotlin.math.sin(2.0 * Math.PI * 180.0 * i / sr)).toFloat()
        }
        val t0 = System.currentTimeMillis()
        val prob = GenderClassifier.predictWindow(context, pcm)
        val ms = System.currentTimeMillis() - t0
        if (prob == null) {
            Log.e(TAG, "③ 性别模型推理**失败**（返回 null，将回退 F0）—— 见上面 GenderClassifier 报错")
        } else {
            Log.i(TAG, "③ 性别模型推理 OK（${ms}ms）: male=%.3f female=%.3f".format(prob.first, prob.second))
        }
    }

    /**
     * ④ 档2：**MDX-Net 模型签名探测**（只读，不推理）。
     *
     * 为什么先做这一步：MDX-Net 的输入布局（是波形？还是复数 STFT 的 real/imag 四通道？）
     * 与频率/时间维的顺序**因导出而异**。猜错的话 DSP 与推理全错，而且很难从结果倒推。
     * 这里把 `inputInfo` / `outputInfo` 的**名字、类型、形状**原样打出来，作为实现的唯一依据。
     */
    private suspend fun mdxProbeStep(context: Context) {
        val spec = AudioGenderModels.MDX
        if (!AudioGenderModels.isUsable(context, spec)) {
            Log.i(TAG, "④ MDX 模型未就位 → 开始下载（约 ${spec.approxMb}MB，仅用于签名探测）")
            val ok = AudioGenderModels.download(context, spec) { pct ->
                if (pct == 100 || pct % 25 == 0) Log.i(TAG, "④ 下载进度 $pct%")
            }
            if (!ok) {
                Log.e(TAG, "④ MDX 下载失败 → 跳过签名探测")
                return
            }
            Log.i(
                TAG,
                "④ MDX 下载完成: ${AudioGenderModels.fileOf(context, spec).length() / 1048576}MB",
            )
        }
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
        env.createSession(AudioGenderModels.fileOf(context, spec).absolutePath, opts).use { s ->
            Log.i(TAG, "④ MDX session OK（模型已加载）")
            // ⚠️ 不调用 NodeInfo 的具体 getter（跨 ORT 版本可能变化），直接 toString ——
            //    它包含 name / type / shape，正是我们要的契约信息。
            for ((name, info) in s.inputInfo) {
                Log.i(TAG, "④ MDX INPUT  $name -> $info")
            }
            for ((name, info) in s.outputInfo) {
                Log.i(TAG, "④ MDX OUTPUT $name -> $info")
            }
            // 🔴 关键：**DSP 参数就存在 ONNX 模型的 metadata 里**
            //    （sherpa-onnx 的 C++ 实现 `offline-source-separation-uvr-model.cc` 就是从这读的：
            //     n_fft / hop_length / win_length / dim_f / dim_t / dim_c / sample_rate /
            //     window_type / center / stems）
            //    → **绝对不要硬编码猜测**，按模型自带参数走。
            val md = s.metadata
            Log.i(TAG, "④ MDX metadata: producer=${md.producerName} graph=${md.graphName} ver=${md.version}")
            for ((k, v) in md.customMetadata) {
                Log.i(TAG, "④ MDX meta  $k = $v")
            }
        }
        runCatching { opts.close() }
    }
}
