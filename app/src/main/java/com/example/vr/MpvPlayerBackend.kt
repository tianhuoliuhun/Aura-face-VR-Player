package com.example.vr

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
// ⚠️⚠️ `is.xyz.mpv.*` 的包名首段是 **`is`** —— 而 `is` 是 Kotlin 的**硬关键字**，
//      `import is.xyz.mpv.MPVLib` 会直接报 "Syntax error: Expecting qualified name"，
//      且连锁导致下面所有 `MPVLib` 都 Unresolved（看起来像"库没依赖上"，其实是语法）。
//      必须给首段加反引号。这是这个库在 Kotlin 项目里的必踩项。
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode

/**
 * MPV（libmpv，FFmpeg 内核）接入层 —— v2.1.234。
 *
 * ## 为什么再加一个内核
 * ijk 与 mpv 都能解设备不认的格式，但定位不同：
 *  - **ijk**：轻（单 so ~7MB），老的 RMVB / VC-1 类片源兼容性好；
 *  - **mpv**：解码/渲染管线最完整（自带 libass），对奇怪封装、音视频同步、
 *    各种字幕的处理更强，是目前开源播放器里"什么都能播"的代表。
 * 两者都留着，用户按片源自己选。
 *
 * ## 用的是哪个 mpv，为什么换过一次
 * 先用的是 `dev.jdtech.mpv:libmpv`，但它 **minSdk = 26**，而本项目 minSdk = 24
 * → 清单合并直接失败（`uses-sdk:minSdkVersion 24 cannot be smaller than 26`）。
 * 提高 minSdk 会砍掉全部 Android 7.x 设备，是破坏性变更，所以改用
 * **`io.github.marlboro-advance:mpv-android:1.0.0`**：
 *   · **minSdk = 24**，与本项目一致，无需改清单；
 *   · 同样是 **libmpv 的纯 JNI 绑定，不带自己的 View**（我们要的正是"把画面吐到
 *     我给的 Surface"，而不是现成的播放器控件）；
 *   · 四个 ABI 齐全（arm64-v8a / armeabi-v7a / x86 / x86_64）；
 *   · libmpv.so 内含 **`mediacodec_embed`**（Android 专有 vo，画面直出 Surface）、
 *     `mediacodec` 硬解、`video-params` 等属性。
 *
 * ## ⚠️ 它是**全局单例**（Kotlin `object`）
 * `is.xyz.mpv.MPVLib` 是 object，`create/init/destroy` 都作用在**同一份** native 上下文上。
 * 这带来两个后果：
 *  1. **同一时刻只能有一个 mpv 播放器** —— 本项目同一时间也只播一个片，
 *     所以没问题，但将来若要做"画中画/双路对比"就必须改；
 *  2. `destroy()` 之后**任何** MPVLib 调用都会崩在 native 层（不是抛 Java 异常），
 *     所以 [MpvBackend.safe] 这层保护是必需的。
 *
 * ## 三条硬规则
 * 1. **所有 `setOptionString` 必须在 `init()` 之前**：libmpv 在 init 时读取配置并
 *    建立内部管线（含 vo/ao 的选择），事后设置无效 —— 表现为"改了没反应"。
 * 2. **`vo=mediacodec_embed` 必须配 `hwdec=mediacodec`**：前者只是把解码器输出
 *    投到 Surface 的通路，它自己不解码；不硬解就没有帧产出（黑屏）。
 * 3. **`content://` 不能直接喂给 mpv**（FFmpeg 不认 Android 私有协议）。做法是
 *    打开 fd 后交 `/proc/self/fd/<n>`，且该 fd **必须持有到播放结束**
 *    （提前关会 "Bad file descriptor"）→ 由 [MpvBackend.ownedFd] 持有。
 */
object MpvPlayerFactory {

    private const val TAG = "MpvPlayer"

    /**
     * 库是否可用。
     *
     * ⚠️ v2.1.235 起 MPV 的 so **不再打进 APK**（见 `MpvLibLoader`），
     * 所以这里不能再去试 `MPVLib.create` —— 那样只会抛 UnsatisfiedLinkError。
     * 必须先走 `MpvLibLoader.isReady()`：它负责"已安装 且 已按依赖顺序加载成功"。
     * 未安装时返回 false，调用方（VRPlayerScreen）会提示下载并回退 Exo。
     */
    fun isAvailable(context: Context): Boolean = MpvLibLoader.isReady(context)

    /**
     * mpv 自带 FFmpeg，协议集最全 —— 除 `content://` 外**不做预筛**，
     * 一律交给 mpv 自己判断（打不开时由回调走回退 Exo 的路径）。
     *
     * ⚠️ 这一点与 ijk **刻意不同**：ijk 的协议集是固定清单，所以那里要预筛
     * （见 `IjkPlayerFactory.supportsDirect`）；mpv 不需要，写了反而会误伤。
     */
    fun supports(uri: Uri): Boolean = uri.scheme != null || uri.path != null

    private fun needsFileDescriptor(uri: Uri): Boolean = uri.scheme == "content"

    /**
     * 建一个 mpv 播放器（已 attachSurface + loadfile，**初始暂停**）。
     * 返回 null 表示不可用，调用方应回退 Exo。
     *
     * @param callbacks 事件回调；**全部已切到主线程**（mpv 回调在它自己的 JNI 线程）
     */
    fun create(
        context: Context,
        uri: Uri,
        surface: Surface,
        options: MpvOptions,
        callbacks: Callbacks
    ): MpvBackend? {
        val mainHandler = Handler(Looper.getMainLooper())
        val backend = MpvBackend(callbacks = callbacks, mainHandler = mainHandler)

        return try {
            MPVLib.create(context)

            // ===== 1. 选项：必须在 init() 之前（见类注释第 1 条）=====
            MPVLib.setOptionString("config", "no")     // 不读外部 mpv.conf，行为完全由本 App 决定
            MPVLib.setOptionString("terminal", "no")
            // 加载后先暂停 —— 与 Exo/ijk 一致：先恢复上次位置，再由调用方 play()
            MPVLib.setOptionString("pause", "yes")
            // 视频输出：Android 专有 vo，把解码结果直接投到 Surface。
            // ⚠️ 不能用 vo=gpu —— 那会让 mpv 另建一个 GL 上下文，与我们的 SurfaceTexture 打架。
            MPVLib.setOptionString("vo", "mediacodec_embed")
            // 硬解（见第 2 条）
            MPVLib.setOptionString("hwdec", if (options.hwdec) "mediacodec" else "no")
            MPVLib.setOptionString("ao", "audiotrack")
            // 变速不变调（mpv 默认就是 yes，显式写出以防默认值变化）
            MPVLib.setOptionString("audio-pitch-correction", "yes")
            // ⚠️ 不把字幕烧进视频帧：本项目有自己的字幕层（样式可调、可翻译），
            //    烧进去会出现"双重字幕"
            MPVLib.setOptionString("sub-visibility", "no")
            if (options.cacheMb > 0) {
                MPVLib.setOptionString("cache", "yes")
                MPVLib.setOptionString("demuxer-max-bytes", (options.cacheMb * 1024L * 1024L).toString())
            }
            // 丢帧保流畅（默认关：VR 里丢帧有空间跳动感）
            MPVLib.setOptionString("framedrop", if (options.frameDrop) "decoder" else "no")
            // 只收 error 级日志，避免 logcat 被 mpv 刷屏
            MPVLib.setOptionString("msg-level", "all=error")

            // ===== 2. 初始化 =====
            MPVLib.init()

            // ===== 3. 事件订阅 =====
            MPVLib.observeProperty("video-params/w", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            MPVLib.observeProperty("video-params/h", MPVLib.MpvFormat.MPV_FORMAT_INT64)

            MPVLib.addObserver(object : MPVLib.EventObserver {
                override fun eventProperty(property: String) { /* 未使用 */ }
                override fun eventProperty(property: String, value: Long) {
                    // 宽或高任一变化都走到这里，交给同一处理函数去重
                    if (property.startsWith("video-params")) backend.onVideoParamsChanged()
                }
                override fun eventProperty(property: String, value: Boolean) { /* 进度靠轮询 */ }
                override fun eventProperty(property: String, value: String) { /* 同上 */ }
                override fun eventProperty(property: String, value: Double) { /* 同上 */ }
                override fun eventProperty(property: String, value: MPVNode) { /* 同上 */ }

                // ⚠️ 签名必须是 (Int, MPVNode) 且参数名叫 data —— 这个接口是 Kotlin 写的
                //    （javap 只能看到 `event(int, MPVNode)`，看不出参数名与可空性），
                //    写错会报 "'event' overrides nothing"。
                override fun event(eventId: Int, data: MPVNode) {
                    when (eventId) {
                        MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                            backend.onFileLoaded()
                            mainHandler.post { callbacks.onPrepared(backend) }
                        }
                        MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> backend.onVideoParamsChanged()
                        MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                            // ⚠️ 播完与出错都会发 END_FILE，必须靠 eof-reached 区分，
                            //    否则「解码失败」会被当成「正常播完」→ 不触发回退，用户看到黑屏
                            if (backend.isNormalEof()) {
                                mainHandler.post { callbacks.onCompletion() }
                            } else if (backend.fileLoadedOnce) {
                                mainHandler.post { callbacks.onError("mpv END_FILE 非正常结束") }
                            }
                        }
                        MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                            mainHandler.post { callbacks.onFirstFrame() }
                        }
                    }
                }
            })

            // ===== 4. 交出 Surface =====
            MPVLib.attachSurface(surface)

            // ===== 5. 数据源 =====
            val target: String = if (needsFileDescriptor(uri)) {
                // content:// → /proc/self/fd/N（见类注释第 3 条）
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw java.io.IOException("openFileDescriptor 返回 null: $uri")
                backend.ownedFd = pfd
                "/proc/self/fd/" + pfd.fd
            } else {
                uri.toString()
            }
            // ⚠️ command 是 vararg，不是数组参数
            MPVLib.command("loadfile", target)

            Log.i(TAG, "mpv 已创建: scheme=${uri.scheme} options=$options")
            backend
        } catch (t: Throwable) {
            Log.e(TAG, "mpv 创建失败: ${t.message}", t)
            runCatching { backend.release() }
            null
        }
    }

    interface Callbacks {
        fun onVideoSizeChanged(width: Int, height: Int)
        fun onPrepared(backend: VrPlayerBackend)
        fun onCompletion()
        /** ⚠️ 与 ijk 的 `onError(what, extra)` 不同：mpv 只给一段原因文本 */
        fun onError(reason: String)
        fun onFirstFrame()
    }
}

/**
 * 设置面板里 MPV 那几项可调参数的快照。
 * 默认值都取"mpv 默认行为"或本项目推荐的保守值。
 */
data class MpvOptions(
    /** MediaCodec 硬解。默认开。⚠️ `mediacodec_embed` 依赖它，关掉会没有画面。 */
    val hwdec: Boolean = true,
    /** 解码跟不上时丢帧保流畅。默认关（VR 里丢帧有空间跳动感）。 */
    val frameDrop: Boolean = false,
    /** 网络流缓冲上限（MB）。0 = 用 mpv 默认。 */
    val cacheMb: Int = 0
) {
    companion object {
        /** 缓冲档位（MB）。0 表示交回 mpv 默认。 */
        val CACHE_CHOICES: List<Int> = listOf(0, 8, 32, 128)

        fun load(prefs: android.content.SharedPreferences?): MpvOptions {
            if (prefs == null) return MpvOptions()
            return MpvOptions(
                hwdec = prefs.getBoolean("mpv_hwdec", true),
                frameDrop = prefs.getBoolean("mpv_framedrop", false),
                cacheMb = prefs.getInt("mpv_cache_mb", 0)
            )
        }
    }
}

/**
 * mpv 的 [VrPlayerBackend] 实现。
 *
 * ⚠️ 它**不持有**任何 mpv 实例 —— `is.xyz.mpv.MPVLib` 是全局单例 object，
 *    所有调用都打到同一份 native 上下文（见 [MpvPlayerFactory] 类注释）。
 *
 * ## 为什么每个调用都包 [safe]
 * mpv 的 C API 在 `destroy()` 之后调用会**直接崩在 native 层**（不抛 Java 异常），
 * 且播放结束/切源期间属性会短暂不可读。而 VRPlayerScreen 的进度轮询是 150ms
 * 一次，任何一次异常都会打断协程 → 字幕时间轴与进度条一起卡死（同 ijk 的坑）。
 *
 * ## 时间单位
 * mpv 的 `time-pos` / `duration` 是**秒（Double）**，而 [VrPlayerBackend] 用毫秒。
 * 换算全部集中在本类，调用方不需要知道。
 */
class MpvBackend(
    private val callbacks: MpvPlayerFactory.Callbacks,
    private val mainHandler: Handler
) : VrPlayerBackend {

    override val engine: DecoderEngine = DecoderEngine.MPV
    override val exo: androidx.media3.exoplayer.ExoPlayer? = null

    @Volatile private var released = false

    /** content:// 通路占用的 fd，release 时才关（见 MpvPlayerFactory 类注释第 3 条）。 */
    internal var ownedFd: ParcelFileDescriptor? = null

    /** 是否收到过 FILE_LOADED —— 用于区分「根本没加载成功」与「加载后出错」。 */
    @Volatile internal var fileLoadedOnce = false
        private set

    private var lastW = 0
    private var lastH = 0

    override val currentPosition: Long
        get() = safe { (MPVLib.getPropertyDouble("time-pos") ?: 0.0) * 1000.0 }?.toLong() ?: 0L

    override val duration: Long
        get() = safe { (MPVLib.getPropertyDouble("duration") ?: 0.0) * 1000.0 }?.toLong() ?: 0L

    override val isPlaying: Boolean
        get() = safe { MPVLib.getPropertyBoolean("pause")?.not() } ?: false

    override fun play() {
        safe { MPVLib.setPropertyBoolean("pause", false) }
    }

    override fun pause() {
        safe { MPVLib.setPropertyBoolean("pause", true) }
    }

    override fun seekTo(positionMs: Long) {
        // ⚠️ mpv 的 seek 单位是**秒**，且必须显式给 "absolute" —— 默认是相对 seek
        safe { MPVLib.command("seek", (positionMs / 1000.0).toString(), "absolute") }
    }

    override fun setPlaybackSpeed(speed: Float) {
        // mpv 名义上支持 0.01~100，但超出 [0.25,4] 后音质与同步都不好用，夹一下
        safe { MPVLib.setPropertyDouble("speed", speed.coerceIn(0.25f, 4.0f).toDouble()) }
    }

    override fun setSurface(surface: Surface?) {
        safe { if (surface != null) MPVLib.attachSurface(surface) else MPVLib.detachSurface() }
    }

    override fun release() {
        if (released) return
        released = true
        // ⚠️ 顺序：先 detachSurface 再 destroy。反过来的话 mpv 销毁 vo 时仍持有
        //    Surface，某些设备上会 native 崩（"Surface has been released"）。
        safe { MPVLib.detachSurface() }
        safe { MPVLib.destroy() }
        safe { ownedFd?.close() }
        ownedFd = null
    }

    // ===================== 事件辅助（由 MpvPlayerFactory 调用）=====================

    internal fun onFileLoaded() {
        fileLoadedOnce = true
        // 文件加载完才有真实的 video-params，主动上报一次分辨率
        onVideoParamsChanged()
    }

    /**
     * 分辨率变化上报（mpv 的宽高是两个独立属性，这里拼起来交给与 Exo/ijk 共用的回调）。
     * 用 [lastW]/[lastH] 去重 —— VIDEO_RECONFIG 在播放中会多次触发。
     */
    internal fun onVideoParamsChanged() {
        val w = safe { MPVLib.getPropertyInt("video-params/w") } ?: return
        val h = safe { MPVLib.getPropertyInt("video-params/h") } ?: return
        if (w <= 0 || h <= 0) return
        if (w == lastW && h == lastH) return
        lastW = w
        lastH = h
        mainHandler.post { callbacks.onVideoSizeChanged(w, h) }
    }

    /**
     * 区分「正常播完」与「出错/被中断」。
     *
     * ⚠️ 必须用 `eof-reached`：END_FILE 两种情况都会发。播完时该属性为 true，
     * 出错/切源时为 false。**不能**用 playlist 位置判断 —— 单文件播放时它恒为 0。
     */
    internal fun isNormalEof(): Boolean =
        safe { MPVLib.getPropertyBoolean("eof-reached") } ?: false

    // ===================== 视频信息 =====================

    /**
     * 读当前视频信息（「视频信息」面板的数据源）。
     *
     * mpv 的属性**在文件加载后才有效**，加载前读会返回 null —— 所以所有字段
     * 都允许为空，UI 统一显示「—」。
     */
    fun videoInfo(): VideoInfo {
        fun s(name: String): String? = safe { MPVLib.getPropertyString(name) }?.takeIf { it.isNotBlank() }
        fun i(name: String): Int = safe { MPVLib.getPropertyInt(name) } ?: 0
        fun d(name: String): Double = safe { MPVLib.getPropertyDouble(name) } ?: 0.0
        // mpv 没有"总码率"这个属性，用 file-size / duration 反算（仅本地文件有意义）
        val fileSize = d("file-size")
        val dur = d("duration")
        val totalBps = if (fileSize > 0.0 && dur > 0.0) (fileSize * 8.0 / dur).toLong() else 0L
        return VideoInfo(
            engine = DecoderEngine.MPV,
            title = s("filename"),
            container = s("file-format"),
            videoCodec = s("video-format"),
            width = i("video-params/w"),
            height = i("video-params/h"),
            frameRate = d("container-fps"),
            videoBitrate = d("video-bitrate").toLong(),
            totalBitrate = totalBps,
            durationMs = (dur * 1000.0).toLong(),
            audioCodec = s("audio-codec-name"),
            audioChannels = i("audio-params/channel-count"),
            audioSampleRate = i("audio-params/samplerate"),
            audioBitrate = d("audio-bitrate").toLong(),
            decoding = s("hwdec-current")
        )
    }

    /** 把 mpv 的 native 崩溃风险挡在 Java 侧：任何异常都降级为「读不到」。 */
    private inline fun <T> safe(block: () -> T): T? =
        try {
            if (released) null else block()
        } catch (t: Throwable) {
            null
        }
}
