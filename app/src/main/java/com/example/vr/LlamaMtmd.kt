package com.example.vr

import android.util.Log

/**
 * **自建 llama.cpp + libmtmd** 的 JNI 接口（v2.4.13）。
 *
 * ## 为什么不用现成 AAR
 * `dev.ffmpegkit-maintained:llama-android:0.1.1` **不含 `libmtmd`**，其 native 接口
 * 只有 5 个方法、没有任何接收图像的入口 → 无法做「本地看画面」。
 * 因此本项目用 NDK 自建了 `libllama.so` / `libmtmd.so`
 * （编译命令与踩坑记录见项目记忆），这里只负责暴露给 Kotlin。
 *
 * ## 与旧后端的取舍
 * ⚠️ **不要同时使用本类与 `dev.ffmpegkit.llama.Llama`** ——
 * 两者各自持有独立的 native 模型实例，同时加载会让一份 0.6GB 的权重占两份内存。
 * 迁移完成后应移除 AAR 依赖。
 *
 * ## native 库加载
 * JNI 实现（`llama_mtmd_jni.cpp`）被编进 **`libauravr.so`**
 * （与项目既有的 native 代码同库），所以这里只是兜底 `loadLibrary`：
 * 若上层已加载过则不会重复加载。
 * ⚠️ `libmtmd.so` / `libllama.so` / `libggml*.so` 的依赖关系由 Android 的
 *    动态链接器按 `DT_NEEDED` 自动解析，**不需要**手动按顺序 load。
 */
object LlamaMtmd {

    private const val TAG = "LlamaMtmd"

    /** 记录 native 库是否可用（不可用时所有调用直接返回降级值，不抛异常）。 */
    val available: Boolean = try {
        System.loadLibrary("auravr")
        true
    } catch (t: Throwable) {
        // ⚠️ catch Throwable 而非 Exception：UnsatisfiedLinkError 是 Error
        Log.e(TAG, "libauravr.so 加载失败，本地 LLM 不可用：${t.message}")
        false
    }

    external fun nativeVersion(): String

    /**
     * 加载模型（可选 mmproj）。
     *
     * @param mmprojPath 空串 = 纯文本模式
     * @return 是否成功（mmproj 加载失败**不会**导致整体失败）
     */
    external fun nativeInit(
        modelPath: String,
        mmprojPath: String,
        nCtx: Int,
        nThreads: Int
    ): Boolean

    /** 是否具备视觉能力（mmproj 已加载且模型支持 vision）。 */
    external fun nativeHasVision(): Boolean

    external fun nativeFree()

    /**
     * 一次完整推理（图文或纯文本）。
     *
     * @param rgb **RGB，3 字节/像素**（mtmd 的格式要求，不是 ARGB）；null = 纯文本
     * @return 生成的文本；失败返回空串
     */
    external fun nativeComplete(
        prompt: String,
        system: String,
        rgb: ByteArray?,
        imgW: Int,
        imgH: Int,
        maxTokens: Int,
        temperature: Float
    ): String

    /** 安全的封装：native 库不可用时返回空串而非抛异常。 */
    fun completeSafe(
        prompt: String,
        system: String,
        rgb: ByteArray?,
        imgW: Int,
        imgH: Int,
        maxTokens: Int,
        temperature: Float
    ): String = try {
        if (!available) "" else nativeComplete(prompt, system, rgb, imgW, imgH, maxTokens, temperature)
    } catch (t: Throwable) {
        Log.e(TAG, "nativeComplete 异常：${t.message}")
        ""
    }
}
