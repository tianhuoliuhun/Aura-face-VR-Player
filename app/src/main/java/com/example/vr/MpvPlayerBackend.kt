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
     * ⚠️ v2.1.235 曾把 MPV 的 so 从 APK 移除（改后下载），当时这里必须走
     * `MpvLibLoader.isReady()`（检查下载目录）。
     * **v2.1.243 已改回内置**：so 随 APK 分发，`MpvLibLoader.ensureLoaded()` 会
     * **优先用 `System.loadLibrary`** 从 APK 的 `nativeLibraryDir` 加载，失败才回退
     * 到下载目录。所以这里的语义恢复为「内置可用 **或** 下载已就绪」，一个方法覆盖两种。
     *
     * 仍**不应**直接试 `MPVLib.create` —— 那只在"库已加载"时才安全；
     * 未加载时调用会抛 `UnsatisfiedLinkError`，而我们要的是一个干净的 false。
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
        val backend = MpvBackend(callbacks = callbacks, mainHandler = mainHandler, currentUri = uri)

        return try {
            MPVLib.create(context)

            // ===== 1. 选项：必须在 init() 之前（见类注释第 1 条）=====
            MPVLib.setOptionString("config", "no")     // 不读外部 mpv.conf，行为完全由本 App 决定
            MPVLib.setOptionString("terminal", "no")
            // 加载后先暂停 —— 与 Exo/ijk 一致：先恢复上次位置，再由调用方 play()
            MPVLib.setOptionString("pause", "yes")
            // 视频输出：Android 专有 vo，把解码结果直接投到 Surface。
            // ⚠️ 不能用 vo=gpu —— 那会让 mpv 另建一个 GL 上下文，与我们的 SurfaceTexture 打架。
            //    （v2.1.244 更正：见 MpvVoMode 注释 —— vo=gpu 用的是 Surface 的 buffer queue，
            //     与 SurfaceTexture 并不冲突，故保留为「软解兜底」模式。）
            MPVLib.setOptionString("vo", options.voMode.voName)
            if (options.voMode == MpvVoMode.GPU) {
                // Android 上的 GL context；现代 mpv 由它把帧渲进 Surface 的 buffer queue
                MPVLib.setOptionString("gpu-context", "android")
            }
            // 硬解（见第 2 条）
            // ⚠️ EMBED 模式必须硬解（它只吃硬件帧）；GPU 模式让 mpv 自己选，
            //    无硬解器时能顺利退回软解 —— 这正是兜底能生效的关键。
            val hwdecValue = when {
                options.voMode == MpvVoMode.EMBED -> if (options.hwdec) "mediacodec" else "no"
                options.hwdec -> "auto-safe"
                else -> "no"
            }
            MPVLib.setOptionString("hwdec", hwdecValue)
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
            backend.created = true   // native 上下文已就绪，release 时方可 destroy

            // ===== 3. 事件订阅 =====
            MPVLib.observeProperty("video-params/w", MPVLib.MpvFormat.MPV_FORMAT_INT64)
            MPVLib.observeProperty("video-params/h", MPVLib.MpvFormat.MPV_FORMAT_INT64)

            backend.observer = object : MPVLib.EventObserver {
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
                            backend.post { callbacks.onPrepared(backend) }
                        }
                        MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> backend.onVideoParamsChanged()
                        MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                            // ⚠️ v2.1.245：**必须用事件自带的 reason，不能用 eof-reached 属性**。
                            //    v2.1.244 曾用 `getPropertyBoolean("eof-reached")` 判定，实测在
                            //    END_FILE 回调时刻该属性恒为 false（mpv 在发事件前后会重置播放状态）
                            //    → 正常播完被误判成「非正常结束」→ 触发回退 EXO（用户报「能播但自动回退」）。
                            //    END_FILE 的 MPVNode 里带权威字段（mpv 源码 player/client.c
                            //    mpv_event_to_node）：reason ∈ {eof,stop,quit,error,redirect}。
                            val reason = backend.endFileReason(data)
                            if (reason == "eof") {
                                backend.post { callbacks.onCompletion() }
                            } else if (reason == "error") {
                                // 审查 #5：原仅 fileLoadedOnce 时上报，导致根本打不开的文件被静默
                                // 忽略、用户看 15 秒黑屏。END_FILE 带 error 一律立即上报，并撤掉
                                // 看门狗避免重复上报。
                                val detail = runCatching { data.get("file_error")?.asString() }.getOrNull()
                                backend.cancelWatchdog()
                                backend.post {
                                    callbacks.onError("mpv 解码错误" + (detail?.let { ": $it" } ?: ""))
                                }
                            }
                            // stop / quit / redirect：主动切源或退出，既不是播完也不是错误 → 静默
                        }
                        MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                            backend.post { callbacks.onFirstFrame() }
                        }
                    }
                }
            }

            // ===== 3.5 注册观察者（存为字段，release 时移除，对应审查 #2）=====
            MPVLib.addObserver(backend.observer!!)

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

            // ===== 6. 「迟迟不 onPrepared」看门狗 =====
            // ⚠️ 必须在 loadfile 之后启动：打不开的文件不会发 FILE_LOADED，
            //    没有兜底就会一直黑屏无提示（见 MpvBackend.startWatchdog 注释）。
            backend.startWatchdog()

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
    val cacheMb: Int = 0,
    /**
     * 视频输出模式。**不持久化**，由二段兜底在内存里切换（见 [MpvVoMode]）。
     */
    val voMode: MpvVoMode = MpvVoMode.EMBED
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
                // voMode 故意不从 prefs 读：它只用于「本片二段兜底」，不该跨片记住
            )
        }
    }
}

/**
 * mpv 的视频输出（vo）模式 —— v2.1.244 引入，用于 **WMV/RM 等老编码的二段兜底**。
 *
 * ## 为什么需要它（v2.1.243 踩到的真实故障）
 * 原实现固定用 `vo=mediacodec_embed`。查 mpv 源码 `video/out/vo_mediacodec_embed.c`：
 * ```c
 * static int query_format(struct vo *vo, int format) {
 *     return format == IMGFMT_MEDIACODEC;   // ← 只接受硬件帧格式
 * }
 * ```
 * 它**天生不支持软解**。而 WMV1/WMV2、RealVideo 这类老编码在现代 Android 上
 * 通常**没有硬件解码器** → mpv 退回软解、输出 `yuv420p` → vo 拒收：
 * ```
 * [autoconvert:error] Failed to create HW uploader for format yuv420p
 * [autoconvert:error] can't find video conversion for yuv420p
 * → MPV_EVENT_END_FILE（非正常结束）
 * ```
 * 这是 vo 的**架构性限制**，调 hwdec 参数绕不过去（`mediacodec-copy` 同样要
 * 硬件帧格式），只能换 vo。
 *
 * ## 两个模式
 * - [EMBED]：`mediacodec_embed`。**零拷贝**（解码器直接投 Surface），真机首选。
 *   但只吃硬解帧 → 无硬解器的编码会失败。
 * - [GPU]：`vo=gpu` + `gpu-context=android`。**软解硬解都能出画**，兼容性最好。
 *   代价是硬解路径多一次 GPU 拷贝（性能略低），且它自建 GL 上下文，
 *   渲染到我们给的 Surface 的 buffer queue（不是 SurfaceTexture，故不冲突）。
 *
 * ⚠️ 默认仍是 [EMBED]（真机最优），只在 EMBED 失败时由应用层自动切 [GPU] 重试一次。
 */
enum class MpvVoMode(val id: Int, val voName: String) {
    EMBED(0, "mediacodec_embed"),
    GPU(1, "gpu");

    companion object {
        fun fromId(id: Int): MpvVoMode = entries.firstOrNull { it.id == id } ?: EMBED
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
    private val mainHandler: Handler,
    override val currentUri: android.net.Uri
) : VrPlayerBackend {

    private companion object {
        const val TAG = "MpvPlayer"

        /**
         * 看门狗超时（毫秒）。取 15s —— 远超正常加载（本地/局域网 1~2s，大文件或
         * 慢速 SMB 也远小于此），又短到用户不会以为「卡死」。
         */
        const val WATCHDOG_MS = 15_000L
    }

    override val engine: DecoderEngine = DecoderEngine.MPV
    override val exo: androidx.media3.exoplayer.ExoPlayer? = null

    @Volatile private var released = false

    /**
     * MPVLib.create + init 是否成功完成。只有成功过才允许 destroy：
     * 否则 [MpvPlayerFactory.create] 的 catch 里调用 release() 时，destroy 会作用在
     * 一个从未初始化的上下文上 → native 崩（v2.4.x 修复）。
     */
    @Volatile internal var created = false

    /** content:// 通路占用的 fd，release 时才关（见 MpvPlayerFactory 类注释第 3 条）。 */
    internal var ownedFd: ParcelFileDescriptor? = null

    /**
     * 当前播放订阅的事件观察者，存为字段以便 release 时移除。
     * MPVLib 是全局单例，观察者列表属于它而非某个播放；若不移除，第 N 次播放时
     * 同一事件会发给 N 个观察者，旧观察者里的 fileLoadedOnce 已为 true，会把当前
     * 播放误回退（v2.4.x 修复，对应审查 #2）。
     */
    // 注：create() 是顶层工厂函数（非类成员），需经 backend.observer 写入，故 internal 可见性
    internal var observer: MPVLib.EventObserver? = null

    /** 是否收到过 FILE_LOADED —— 用于区分「根本没加载成功」与「加载后出错」。 */
    @Volatile internal var fileLoadedOnce = false
        private set

    /**
     * 「迟迟不 onPrepared」看门狗（v2.1.245）。
     *
     * ⚠️ 为什么必须有它：MPV 打不开文件时（损坏片源、极端不支持的容器）**根本不发
     * `MPV_EVENT_FILE_LOADED`** → `fileLoadedOnce` 恒为 false → END_FILE 的 error 分支
     * 被 `if (fileLoadedOnce)` 挡住 → 既不上报 onError、也永远等不到 onPrepared
     * → 用户看到的是**一直黑屏、没有任何提示**（比报错更糟：无从判断）。
     * 因此加一个「N 秒内没加载成功就当作失败」的兜底上报。
     */
    private var watchdog: Runnable? = null

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
        cancelWatchdog()
        // 审查 #2：移除本播放订阅的观察者，避免它留在 MPVLib 全局单例里打到新播放上。
        observer?.let { runCatching { MPVLib.removeObserver(it) } }
        observer = null
        // ⚠️ 关键修复（v2.4.x，审查 #1）：原实现先置 released=true 再用 safe{} 调 native，
        //    而 safe 一见 released 就返回 null → detachSurface/destroy 永不被调用 →
        //    全局单例里的上一个播放从不销毁，下次 MPVLib.init() 撞 !initialized 断言崩。
        //    改为：每步各自 try（绕过 released 检查），最后才置 released=true。
        // ⚠️ 顺序：先 detachSurface 再 destroy。反过来的话 mpv 销毁 vo 时仍持有
        //    Surface，某些设备上会 native 崩（"Surface has been released"）。
        if (created) {
            try { MPVLib.detachSurface() } catch (_: Throwable) {}
            try { MPVLib.destroy() } catch (_: Throwable) {}
        }
        try { ownedFd?.close() } catch (_: Throwable) {}
        ownedFd = null
        released = true
    }

    // ===================== 事件辅助（由 MpvPlayerFactory 调用）=====================

    /**
     * 启动「迟迟不 onPrepared」看门狗（在 `loadfile` 之后调用）。
     *
     * ⚠️ 只在**从未加载成功**时上报：若 `fileLoadedOnce` 已为 true，说明播放早已开始，
     * 此后的沉默（暂停/播完）是正常状态，绝不能误报错误（与 END_FILE 的教训同型）。
     */
    internal fun startWatchdog() {
        cancelWatchdog()
        val r = Runnable {
            watchdog = null
            if (released || fileLoadedOnce) return@Runnable
            Log.e(TAG, "看门狗：${WATCHDOG_MS}ms 内未收到 FILE_LOADED，判定为无法打开 → 上报错误")
            callbacks.onError("mpv 无法打开该文件（${WATCHDOG_MS / 1000} 秒内未就绪）")
        }
        watchdog = r
        mainHandler.postDelayed(r, WATCHDOG_MS)
    }

    internal fun cancelWatchdog() {
        watchdog?.let { mainHandler.removeCallbacks(it) }
        watchdog = null
    }

    internal fun onFileLoaded() {
        fileLoadedOnce = true
        cancelWatchdog()   // 已就绪，撤掉兜底上报
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
        post { callbacks.onVideoSizeChanged(w, h) }
    }

    /**
     * 解析 `MPV_EVENT_END_FILE` 自带的结束原因。
     *
     * ⚠️ **不要改用 `eof-reached` 属性** —— 实测在 END_FILE 回调时刻恒为 false
     * （v2.1.244 的误回退就是这么来的）。事件的 `MPVNode` 里有权威的 `reason` 字段：
     *
     * | 值 | 含义 |
     * |---|---|
     * | `eof` | 正常播完 |
     * | `error` | 解码/打开失败（另有 `file_error` 说明原因） |
     * | `stop` | 被外部停止（stop 命令、切源） |
     * | `quit` | 播放器退出 |
     * | `redirect` | 播放列表重定向 |
     *
     * 来源：mpv `player/client.c` → `mpv_event_to_node()`。
     * 读不到时返回 null，调用方按「忽略」处理（避免误报错误吓用户）。
     */
    internal fun endFileReason(data: MPVNode): String? =
        safe { data.get("reason")?.asString() }

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

    /**
     * 对外回调统一过闸：release 之后已入队的回调不再执行，避免旧观察者 / 旧播放的
     * 回调误写当前界面状态（isVideoPlaying 被写成 true 等，对应审查 #2）。
     */
    // internal：create() 顶层工厂内的观察者需经 backend.post 投递到主线程
    internal fun post(block: () -> Unit) = mainHandler.post { if (!released) block() }
}
