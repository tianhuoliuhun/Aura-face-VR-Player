package com.example.vr

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface

/**
 * **系统解码**内核 —— 走 Android Framework 自带的 `android.media.MediaPlayer`。
 *
 * ## 它和 Exo / IJK / MPV 有什么本质区别
 * 那三个都是「应用自带一套解码栈」：
 *   · Exo  → MediaCodec（找系统解码器，但由 Exo 自己管管线）
 *   · IJK  → 自带 FFmpeg（.so）
 *   · MPV  → 自带 libmpv（.so）
 * 而 `MediaPlayer` 是 **Framework 自己的播放器**，走的是**厂商 ROM 的解码管线**。
 * 国产 ROM（华为 / 小米 / OPPO…）常在这条管线里集成**私有增强解码器** ——
 * 自研格式支持、更激进的功耗控制、部分 DRM/超分能力。
 *
 * 所以它的定位是**一条独立的兜底路径**：同一个片源在 Exo/IJK 下表现异常时，
 * 值得切到系统解码试一次 —— 很可能走的是完全不同的硬解实现。
 *
 * ## ⚠️ 代价：API 老、各 ROM 行为差异大
 * `MediaPlayer` 从 API 1 就在，各厂商实现并不统一：
 *   · 某些机型对 `setPlaybackParams`（变速）支持不完整；
 *   · `seekTo` 的精度在旧版本上只有 `SEEK_PREVIOUS_SYNC`（关键帧）级别；
 *   · 部分 ROM 在错误回调里给的 what/extra 语义模糊。
 * 因此本内核**全程按"可能失败"设计**：每个环节失败都走回退（见下），
 * 绝不因为系统播放器的问题让用户看到黑屏。
 *
 * ## 回退策略（三层，全部指向 EXO）
 * 1. **协议不支持** → [supports] 返回 false，调用方**根本不选它**；
 * 2. **创建/设置数据源失败** → [create] 返回 null，调用方回退 EXO；
 * 3. **prepare 或播放中出错** → `setOnErrorListener` 触发 [Callbacks.onError]，
 *    调用方 Toast 提示并切回 EXO（与 IJK 完全同一套机制）。
 */
object SystemPlayerFactory {

    private const val TAG = "SystemPlayer"

    /**
     * 系统播放器**不需要任何 native 库或可选组件**，所以恒为可用。
     * （真正的判定发生在运行时：`create()` 能不能把 MediaPlayer 建起来。）
     */
    fun isAvailable(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = true

    /**
     * 该 URI 是否该交给系统解码。
     *
     * ⚠️ **必须排除 `smb://`**：本项目用 jcifs 自己实现了 SMB 数据源
     * （见 `SmbDataSource`），而 `MediaPlayer` 只认 Framework 支持的协议 ——
     * 直接喂 smb:// 会在 `setDataSource` 抛 `IOException`（然后回退，纯属浪费一次尝试）。
     *
     * ⚠️ 与 IJK 的处理刻意不同：IKJ 的协议集是固定清单所以要预筛；
     * 这里只排除"确定的短板"，其余（content/file/http/https/rtsp/rtmp…）
     * 一律交给 MediaPlayer 自己判断 —— 它打不开会走 onError 回退。
     */
    fun supports(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        return scheme != "smb"
    }

    /**
     * 建一个系统解码播放器（已 setSurface + prepareAsync，**未自动播放**）。
     * 返回 null 表示不可用，调用方应回退 EXO。
     *
     * @param callbacks 事件回调；**全部已切到主线程**
     *   （MediaPlayer 的回调本来就发在主线程，这里仍显式 post 一次，
     *    避免将来有人在子线程创建它时行为变异）
     */
    fun create(
        context: Context,
        uri: Uri,
        surface: Surface,
        callbacks: Callbacks
    ): SystemBackend? {
        val mainHandler = Handler(Looper.getMainLooper())
        val mp = MediaPlayer()
        val backend = SystemBackend(mp = mp, callbacks = callbacks, mainHandler = mainHandler, currentUri = uri)

        return try {
            // ⚠️ 顺序：先 setSurface 再 setDataSource 再 prepare。
            //    在 prepare 之前 setSurface 是允许的（Framework 会在首帧到达时用上它），
            //    这样能少一次"surface 变化触发重配"的抖动。
            mp.setSurface(surface)

            // ---- 事件 ----
            mp.setOnPreparedListener {
                backend.onPreparedInternal()
            }
            mp.setOnVideoSizeChangedListener { _, width, height ->
                // ⚠️ MediaPlayer 给的是**视频原始尺寸**（未应用旋转），与 Exo 的
                //    Format.width/height 语义一致 → 交给同一个回调，上层不需要区分内核。
                if (width > 0 && height > 0) {
                    mainHandler.post { callbacks.onVideoSizeChanged(width, height) }
                }
            }
            mp.setOnCompletionListener {
                mainHandler.post { callbacks.onCompletion() }
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer 错误: what=$what extra=$extra -> 回退 EXO")
                mainHandler.post { callbacks.onError(what, extra) }
                // ⚠️ 必须返回 true：返回 false 时 Framework 会继续调用
                //    OnCompletionListener，被上层误判成"正常播完" → 不触发回退。
                true
            }
            mp.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    mainHandler.post { callbacks.onFirstFrame() }
                }
                false
            }

            // ---- 数据源 + 异步准备 ----
            // 用 (Context, Uri) 这个重载：它能正确处理 content:// 与 file://，
            // 也是唯一能在不自己开 fd 的前提下支持 content 的方式。
            mp.setDataSource(context, uri)
            mp.prepareAsync()

            Log.i(TAG, "系统解码已创建: scheme=${uri.scheme}")
            backend
        } catch (t: Throwable) {
            Log.e(TAG, "系统解码创建失败: ${t.message}", t)
            runCatching { backend.release() }
            null
        }
    }

    interface Callbacks {
        fun onVideoSizeChanged(width: Int, height: Int)
        fun onPrepared(backend: VrPlayerBackend)
        fun onCompletion()
        /** ⚠️ 与 ijk 同签名（what/extra），便于 `VRPlayerScreen` 用同一套回退处理 */
        fun onError(what: Int, extra: Int)
        fun onFirstFrame()
    }
}

/**
 * 系统解码的 [VrPlayerBackend] 实现。
 *
 * ## 为什么每个调用都包 [safe]
 * `MediaPlayer` 是个**状态机非常严格**的老 API：在非法状态下调用（比如未 prepared 就
 * `isPlaying`、已 released 还 `seekTo`）会抛 `IllegalStateException`。
 * 而 `VRPlayerScreen` 的进度轮询是 150ms 一次，任何一次异常都会打断协程 →
 * 字幕时间轴与进度条一起卡死（同 ijk / mpv 的坑）。
 *
 * ## 与 Exo 的语义对齐
 * · `duration` 在 prepared 前是 **-1**（MediaPlayer 的约定）→ 归一到 **0**，
 *   与 `ExoBackend`（C.TIME_UNSET 为负）保持"调用方只需判 > 0"的一致前提。
 * · `setPlaybackSpeed` 用 `PlaybackParams`（API 23+，本项目 minSdk 24 覆盖）。
 */
class SystemBackend(
    private val mp: MediaPlayer,
    private val callbacks: SystemPlayerFactory.Callbacks,
    private val mainHandler: Handler,
    override val currentUri: android.net.Uri
) : VrPlayerBackend {

    override val engine: DecoderEngine = DecoderEngine.SYSTEM
    override val exo: androidx.media3.exoplayer.ExoPlayer? = null

    @Volatile private var released = false

    /** 是否已经 prepared（决定 `duration` 可不可信、能不能 `start`）。 */
    @Volatile private var prepared = false

    /** 调用方在 prepared 之前设的倍速，prepared 时补应用一次。 */
    @Volatile private var pendingSpeed: Float? = null

    override val currentPosition: Long
        get() = (safe { mp.currentPosition } ?: 0).toLong()

    override val duration: Long
        get() = (safe { mp.duration } ?: -1).let { if (it > 0) it.toLong() else 0L }

    override val isPlaying: Boolean
        get() = safe { mp.isPlaying } ?: false

    override fun play() {
        safe { mp.start() }
    }

    override fun pause() {
        safe { mp.pause() }
    }

    override fun seekTo(positionMs: Long) {
        safe {
            val clamped = positionMs.coerceIn(0L, Int.MAX_VALUE.toLong())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // ⚠️ API 26+ 才有 `seekTo(long, int)` 精确模式；SEEK_CLOSEST 比默认的
                //    SEEK_PREVIOUS_SYNC（只到关键帧）准得多。
                //    ⚠️ 注意这个重载的第一个参数是 **Long**（不是 Int）——
                //       传 Int 会编译失败（Argument type mismatch）。
                mp.seekTo(clamped, MediaPlayer.SEEK_CLOSEST)
            } else {
                // 老版本只有 `seekTo(int)`（且只能到关键帧）
                mp.seekTo(clamped.toInt())
            }
        }
    }

    /**
     * ⚠️ `setPlaybackParams` 在**未 prepared** 时调用会抛 `IllegalStateException`，
     *    而调用方（`onPrepared` 之前）就可能设倍速 → 这里先记下来，
     *    等 prepared 时补一次（见 [onPreparedInternal]）。
     */
    override fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4.0f)
        if (!prepared) {
            pendingSpeed = clamped
            return
        }
        safe {
            mp.playbackParams = mp.playbackParams.setSpeed(clamped)
        }
    }

    override fun setSurface(surface: Surface?) {
        safe { mp.setSurface(surface) }
    }

    override fun release() {
        if (released) return
        // 审查 #1：原把 released=true 放在 safe{} 之前，safe 一见 released 就返回 null →
        // 监听清理 / reset / release 永不被调用 → MediaPlayer 不 release、旧回调打到新播放上。
        // 改为直接 try，并置 released=true 于最后。
        try { mp.setOnPreparedListener(null) } catch (_: Throwable) {}
        try { mp.setOnCompletionListener(null) } catch (_: Throwable) {}
        try { mp.setOnErrorListener(null) } catch (_: Throwable) {}
        try { mp.setOnInfoListener(null) } catch (_: Throwable) {}
        try { mp.setOnVideoSizeChangedListener(null) } catch (_: Throwable) {}
        try { mp.reset() } catch (_: Throwable) {}   // 回归 Idle 状态，release 才不会被内部状态卡住
        try { mp.release() } catch (_: Throwable) {}
        released = true
    }

    // ===================== 内部：prepared 处理 =====================

    /** 由 [SystemPlayerFactory.create] 的 OnPreparedListener 调用。 */
    internal fun onPreparedInternal() {
        prepared = true
        // prepared 之前设过的倍速在这里补应用（那时调用是无效的）
        pendingSpeed?.let { sp ->
            pendingSpeed = null
            safe { mp.playbackParams = mp.playbackParams.setSpeed(sp) }
        }
        mainHandler.post { callbacks.onPrepared(this) }
    }

    /** 把 MediaPlayer 的状态机异常降级为 null。 */
    private inline fun <T> safe(block: () -> T): T? =
        try {
            if (released) null else block()
        } catch (t: Throwable) {
            null
        }

    // ===================== 视频信息 =====================

    /**
     * 读当前视频信息（「视频信息」面板的数据源）。
     *
     * ⚠️ 系统播放器**能暴露的字段很少** —— MediaPlayer 只给视频宽高与时长，
     *    没有编码名、码率、帧率、容器格式。这些在上层会显示成「—」，
     *    但**分辨率与时长是准的**，而 VR 场景下这两项恰恰最有用
     *    （投影模式判定、进度条都依赖它们）。
     *
     * ⚠️ 宽高与 duration 都**必须 prepared 之后**才有效 —— 之前读会得到 0 / -1，
     *    所以这里用 safe 包装并在无值时返回 0（UI 会显示「—」）。
     */
    fun videoInfo(): VideoInfo = VideoInfo(
        engine = DecoderEngine.SYSTEM,
        width = safe { mp.videoWidth } ?: 0,
        height = safe { mp.videoHeight } ?: 0,
        durationMs = (safe { mp.duration } ?: -1).let { if (it > 0) it.toLong() else 0L },
        // 其余字段系统播放器不暴露，留空 → UI 统一显示「—」
        decoding = "系统原生（MediaPlayer）"
    )
}
