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

    /** ② sherpa-onnx：构造一个最轻的 VAD 并真跑一次（会加载 sherpa 的 jni + 它依赖的 onnxruntime）。 */
    private fun sherpaStep(context: Context) {
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
}
