package com.example.vr

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.Surface
import tv.danmaku.ijk.media.player.IjkMediaPlayer

/**
 * ijkplayer（Bilibili，FFmpeg 内核）接入层 —— v2.1.233，v2.1.242 校正能力描述。
 *
 * ## 为什么还要留 ijk
 * ExoPlayer/MediaCodec 只认设备厂商提供的编解码器与**它自己认得的容器**；ijk 自带
 * FFmpeg 的**容器解析**通路，能打开 EXO 完全不认的容器（**ASF/WMV、RealMedia(RM/RMVB)**）。
 * 对 VR 播放器来说，"这个片源打不开"是最致命的，多一条通路就有兜底。
 *
 * ## ⚠️ 能力边界（v2.1.242 二进制实测校正，勿凭印象改）
 * 这个 so 里的 FFmpeg 是**白名单式裁剪版**：`--disable-demuxers --enable-demuxer=...`，
 * 实测只注册了 **23 个 demuxer / 28 个 decoder**。要点：
 *  - **容器**：asf / rm / mov / matroska / flv / mpegts / mpegps / mpegvideo / rtsp /
 *    hls / aac / mp3 / flac / hevc / concat / data / ivr / rdt / mpegtsraw / webm_dash 等。
 *  - **软件解码器**：只有 aac / flac / flv / h263 / h264 / hevc / mpeg4 / mp3 / vp6~vp9 / pcm*。
 *  - ⚠️ **没有** `ff_wmv3_decoder` / `ff_vc1_decoder` / `ff_mpeg2video_decoder`。
 *    so 里能搜到 `wmv3`/`vc1`/`mpeg2video` 字样，但那只是 FFmpeg 的 **codec_tag /
 *    descriptor 名字表**，不是可用的软解实现 —— 别被字符串骗了。
 *    这些编码的实际解码靠 **IJK 自研的 MediaCodec 硬解通道**（`ffpipeline_android_media`
 *    + `MediaCodec_*`），即最终还是走设备厂商的硬解器。
 *  - ⚠️ **没有 `ff_avi_demuxer` / `ff_riff_demuxer`**（实测 0 命中）→ **AVI 不能交给 ijk**，
 *    必须走 EXO 的 `AviExtractor`。同理 Ogg 也不行。见 `MediaFormats.EXO_ONLY`。
 *
 * ## 三条硬规则（都是踩过才知道的）
 * 1. **必须先 loadLibrariesOnce + native_profileBegin**，否则第一次 new IjkMediaPlayer()
 *    就 UnsatisfiedLinkError 直接崩 —— 而且崩在 native 层，Java 栈看不到原因。
 * 2. **所有 setOption 必须在 setDataSource 之前**：FFmpeg 的 AVFormatContext 在
 *    prepare 时才读这些 option，事后设置无效（表现为"改了参数没反应"）。
 * 3. **ijk 不支持 smb 协议**（它只有 FFmpeg 的协议集：file / http / https / rtmp / rtsp…）。
 *    ⚠️ 注释里**绝不要出现「斜杠紧跟星号」**：Kotlin 的块注释是可嵌套的，
 *    那样的连续字符会被当成新一层注释开始，使文件后半段整体变成注释
 *    （编译器只在文件末尾报一句 "Unclosed comment"，极难定位）。
 *    遇到不支持的 scheme 必须**回退 Exo**，由调用方处理（见 VRPlayerScreen）。
 */
object IjkPlayerFactory {

    private const val TAG = "IjkPlayer"

    @Volatile private var loaded = false
    @Volatile private var loadError: String? = null

    /** 上一次加载失败的原因（供 UI 提示），成功则为 null。 */
    val lastError: String? get() = loadError

    /** 本机是否已成功加载 ijk native 库（幂等，首次调用会触发加载）。 */
    val isAvailable: Boolean get() = ensureLibraries()

    /**
     * 加载 ijk 的 native 库。幂等；失败只记一次原因，之后直接返回 false（不重复抛）。
     *
     * ⚠️ 必须两个都调：
     *   `loadLibrariesOnce`  LoadLibrary 三件套（ijkffmpeg / ijkplayer / ijksdl）
     *   `native_profileBegin` 开启 FFmpeg 内部性能统计；**缺了它 ijk 会直接拒绝初始化**
     */
    @Synchronized
    fun ensureLibraries(): Boolean {
        if (loaded) return true
        if (loadError != null) return false
        return try {
            IjkMediaPlayer.loadLibrariesOnce(null)
            IjkMediaPlayer.native_profileBegin("libijkplayer.so")
            loaded = true
            Log.i(TAG, "ijk native 库加载成功")
            true
        } catch (e: Throwable) {
            // 用 Throwable 而非 Exception：UnsatisfiedLinkError 是 Error 不是 Exception
            loadError = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "ijk native 库加载失败: $loadError", e)
            false
        }
    }

    /**
     * ijk（FFmpeg）能直接吃的协议。
     *
     * ⚠️ 这里**故意不含** content://（要转 FileDescriptor，见 [create]）与
     *    smb://（FFmpeg 没这个协议，只能回退 Exo）。
     */
    fun supportsDirect(uri: Uri): Boolean {
        val s = uri.scheme?.lowercase() ?: return false
        return s == "file" || s == "http" || s == "https" ||
            s == "rtmp" || s == "rtsp" || s == "rtp" ||
            s == "mms" || s == "mmsh" || s == "tcp" || s == "udp" || s == "data"
    }

    /** content:// 不能直接喂给 ijk，但可转成 FileDescriptor 后喂。 */
    fun needsFileDescriptor(uri: Uri): Boolean = uri.scheme == "content"

    /** 该 URI 能否交给 ijk（含 content 的 FileDescriptor 通路）。 */
    fun supports(uri: Uri): Boolean = supportsDirect(uri) || needsFileDescriptor(uri)

    /**
     * 建一个 ijk 播放器（已 setDataSource + setSurface + prepareAsync）。
     * 返回 null 表示不可用（native 库没加载上 / 数据源打不开），调用方应回退 Exo。
     *
     * @param options 解码器参数（对应设置面板里 IJK 那几项，见 [IjkOptions]）
     * @param callbacks 事件回调；全部在**主线程**触发（ijk 的 EventHandler 绑主线程 Looper）
     */
    fun create(
        context: Context,
        uri: Uri,
        surface: Surface,
        options: IjkOptions,
        callbacks: Callbacks
    ): IjkBackend? {
        if (!ensureLibraries()) {
            Log.w(TAG, "create 跳过：native 库不可用（${loadError}）")
            return null
        }
        val mp = IjkMediaPlayer()
        // ⚠️ backend **只建一个实例**：onPrepared 回调与返回值必须是同一个对象，
        //    否则调用方在回调里拿到的 backend 与 playerInstance 是两个实例，
        //    release() 只关掉其中一个 → 另一个泄漏且 fd 永不释放。
        val backend = IjkBackend(mp, uri)
        try {
            // ===== 参数必须在 setDataSource 之前（见类注释第 2 条）=====
            // 硬解：走 Android MediaCodec；关掉则用 FFmpeg 软解
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", if (options.mediaCodec) 1L else 0L)
            // 硬解下让 ijk 自己处理旋转与分辨率突变（拍摄视频/直播常见，否则画面会拉歪）
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1L)
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1L)
            // 不用 OpenSL ES 输出，统一走 AudioTrack（与 Exo 的 AudioSink 行为一致）
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0L)
            // 覆盖层像素格式：RV32 = 0x32335652（'2','3','V','R'）。
            // ⚠️ 这里写字面量而不用 IjkMediaPlayer.SDL_FCC_RV32 —— 0.8.8 的 Java 层
            //    未必暴露该常量，写常量名一旦不存在就是编译失败。
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", 0x32335652L)
            // 丢帧：解码跟不上时主动丢帧保流畅（画质换流畅，慢机可开）
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", if (options.frameDrop) 1L else 0L)
            // 不要"prepare 完就自动播" —— 我们要先恢复上次播放位置，再手动 start
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0L)
            // 变速：soundtouch = 变速不变调（1）；为 0 则是简单重采样（会变调）
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", if (options.soundTouch) 1L else 0L)
            // 精确 seek：seek 到最近关键帧再解到目标点（慢但准）
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "enable-accurate-seek", if (options.accurateSeek) 1L else 0L)
            // 缓冲上限（字节）。0 = 用 ijk 默认。大缓冲抗网络抖动，小缓冲更省内存
            if (options.maxBufferBytes > 0) {
                mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", options.maxBufferBytes)
            }
            // 探测（probesize）：只解开头若干字节判断封装/编码，缩短首帧时间。
            // 0 = 不限制（ijk 默认）。设太小会在非常见封装上误判成"打不开"。
            if (options.probeSizeBytes > 0) {
                mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", options.probeSizeBytes)
            }
            // 环路滤波跳过阈值：48 = 跳过部分 deblock，明显省 CPU、画质损失很小。
            // 8K/高码率片源在手机上很吃这一项。
            mp.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", options.skipLoopFilter)

            // ===== 数据源 =====
            if (needsFileDescriptor(uri)) {
                // content:// → FileDescriptor。
                // ⚠️ ParcelFileDescriptor **不能**用 use{} 立刻关掉：ijk 内部会 dup 一份
                //    fd，但关闭时机在 prepare 之后；提前关会报 "Bad file descriptor"。
                //    这里交给 IjkBackend 持有，release 时再关。
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw java.io.IOException("openFileDescriptor 返回 null: $uri")
                mp.setDataSource(pfd.fileDescriptor)
                // fd 交给 backend 持有，release 时才关（挂 object 单例会被下一个播放器覆盖）
                backend.ownedFd = pfd
            } else {
                mp.setDataSource(context, uri)
            }

            mp.setSurface(surface)
            mp.setOnPreparedListener { callbacks.onPrepared(backend) }
            mp.setOnVideoSizeChangedListener { _: Any?, width: Int, height: Int, _: Int, _: Int ->
                callbacks.onVideoSizeChanged(width, height)
            }
            mp.setOnCompletionListener { callbacks.onCompletion() }
            mp.setOnErrorListener { _: Any?, what: Int, extra: Int ->
                callbacks.onError(what, extra)
                // 返回 true = 已处理，不再触发 onCompletion（避免"播完"与"出错"双重回调）
                true
            }
            mp.setOnInfoListener { _: Any?, what: Int, _: Int ->
                if (what == IjkMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) callbacks.onFirstFrame()
                false
            }
            mp.prepareAsync()
            Log.i(TAG, "ijk 已创建并开始 prepare: ${uri.scheme}://… options=$options")
            return backend
        } catch (e: Throwable) {
            Log.e(TAG, "ijk 创建失败: ${e.message}", e)
            runCatching { backend.ownedFd?.close() }
            runCatching { mp.release() }
            return null
        }
    }

    interface Callbacks {
        fun onVideoSizeChanged(width: Int, height: Int)
        fun onPrepared(backend: VrPlayerBackend)
        fun onCompletion()
        fun onError(what: Int, extra: Int)
        fun onFirstFrame()
    }
}

/**
 * 设置面板里 IJK 那几项可调参数的快照。
 *
 * 全部字段都有"ijk 默认"取值，用户没动过就与 ijk 官方默认行为一致 —— 这样
 * "切到 IJK 就播不了" 类问题不会因为参数默认值太激进而被误伤。
 */
data class IjkOptions(
    /** MediaCodec 硬解（关 = FFmpeg 软解）。默认开：硬解省电、8K 才有机会跑动。 */
    val mediaCodec: Boolean = true,
    /** 解码跟不上时丢帧保流畅。默认关：VR 画面丢帧会有明显的空间跳动感。 */
    val frameDrop: Boolean = false,
    /** 精确 seek。默认开：seek 不准在 VR 里很难受（进度条拖到哪就是哪）。 */
    val accurateSeek: Boolean = true,
    /** 变速不变调。默认开。 */
    val soundTouch: Boolean = true,
    /** 缓冲上限（字节）。0 = ijk 默认。 */
    val maxBufferBytes: Long = 0L,
    /** 探测字节数。0 = ijk 默认（不限）。 */
    val probeSizeBytes: Long = 0L,
    /** 环路滤波跳过阈值。48 = 官方 demo 值（省 CPU）；0 = 不跳过（画质最好）。 */
    val skipLoopFilter: Long = 48L
) {
    companion object {
        /** 缓冲档位：字节数。0 表示交回 ijk 默认。 */
        val BUFFER_CHOICES: List<Long> = listOf(0L, 2L * 1024 * 1024, 6L * 1024 * 1024, 15L * 1024 * 1024)

        fun load(prefs: android.content.SharedPreferences?): IjkOptions {
            if (prefs == null) return IjkOptions()
            return IjkOptions(
                mediaCodec = prefs.getBoolean("ijk_mediacodec", true),
                frameDrop = prefs.getBoolean("ijk_framedrop", false),
                accurateSeek = prefs.getBoolean("ijk_accurate_seek", true),
                soundTouch = prefs.getBoolean("ijk_soundtouch", true),
                maxBufferBytes = prefs.getLong("ijk_max_buffer", 0L),
                probeSizeBytes = prefs.getLong("ijk_probe_size", 0L),
                skipLoopFilter = prefs.getLong("ijk_skip_loop_filter", 48L)
            )
        }
    }
}

/**
 * ijk 的 [VrPlayerBackend] 实现。
 *
 * ⚠️ ijk 的状态机比 Exo 严格得多：**在非法状态下调 getCurrentPosition/isPlaying 会抛
 *    IllegalStateException**（而不是返回 0）。而 VRPlayerScreen 里进度轮询是 150ms 一次
 *    的死循环，一次异常就会把整个协程打断 → 字幕时间轴与进度条一起卡死。
 *    所以这里**每个 getter 都用 [safe] 包一层**，把异常降级成"当前读不到"。
 */
class IjkBackend(
    private val mp: IjkMediaPlayer,
    override val currentUri: android.net.Uri
) : VrPlayerBackend {

    override val engine: DecoderEngine = DecoderEngine.IJK
    override val exo: androidx.media3.exoplayer.ExoPlayer? = null

    /** 是否已 release。release 之后所有调用都走 [safe] 的空返回分支。 */
    @Volatile private var released = false

    /** content:// 通路占用的 fd，由 IjkPlayerFactory.create 挂进来，release 时关闭。 */
    internal var ownedFd: android.os.ParcelFileDescriptor? = null

    override val currentPosition: Long
        get() = safe { mp.currentPosition } ?: 0L
    override val duration: Long
        get() = safe { mp.duration } ?: 0L
    override val isPlaying: Boolean
        get() = safe { mp.isPlaying } ?: false

    override fun play() { safe { mp.start() } }
    override fun pause() { safe { mp.pause() } }
    override fun seekTo(positionMs: Long) { safe { mp.seekTo(positionMs) } }

    /** ⚠️ `setSpeed` 是 IjkMediaPlayer 的**自有**方法，不在 IMediaPlayer 接口里，
     *  所以本类持有的是 IjkMediaPlayer 具体类型而非接口类型。 */
    override fun setPlaybackSpeed(speed: Float) {
        // ijk 的变速依赖 soundtouch；倍速超出 [0.5, 2.0] 时 FFmpeg 侧会失真，
        // 但 ijk 不会报错，这里只是夹一下避免出现 0 或负数导致的除零/静音。
        safe { mp.setSpeed(speed.coerceIn(0.25f, 4.0f)) }
    }

    override fun setSurface(surface: Surface?) { safe { mp.setSurface(surface) } }

    override fun release() {
        if (released) return
        // 审查 #1：原把 released=true 放在 safe{} 之前，safe 一见 released 就返回 null →
        // mp.stop()/mp.release() 永不被调用 → 解码线程 / MediaCodec / ownedFd 全部泄漏。
        // 改为直接 try，并置 released=true 于最后。
        try { mp.stop() } catch (_: Throwable) {}
        try { mp.release() } catch (_: Throwable) {}
        // content:// 通路占用的 fd 在此关闭（见 IjkPlayerFactory.create 的说明）
        try { ownedFd?.close() } catch (_: Throwable) {}
        ownedFd = null
        released = true
    }

    /** 把 ijk 的"非法状态异常"降级为 null。 */
    private inline fun <T> safe(block: () -> T): T? =
        try {
            if (released) null else block()
        } catch (e: Throwable) {
            null
        }

    // ===================== v2.1.234：视频信息 =====================

    /**
     * 读当前视频信息（「视频信息」面板的数据源）。
     *
     * ijk 能给的比 mpv 少：没有帧率，也没有直接的"容器格式"字段。
     * 但有 [MediaInfo] —— 里面的 `mVideoDecoder` / `mAudioDecoder` 是**实际用到的
     * 解码器名**（形如 `h264` / `aac`），`mVideoDecoderImpl` 是解码实现
     * （`MediaCodec` = 硬解 / `FFmpeg` = 软解），这三项对排查"这个片源到底走了软解还是硬解"
     * 最有用处，所以优先取它们。
     *
     * ⚠️ 全部在 [safe] 里读：ijk 在 prepare 完成前调这些会抛 IllegalStateException。
     */
    fun videoInfo(): VideoInfo = VideoInfo(
        engine = DecoderEngine.IJK,
        videoCodec = safe { mp.mediaInfo?.mVideoDecoder },
        width = safe { mp.videoWidth } ?: 0,
        height = safe { mp.videoHeight } ?: 0,
        durationMs = safe { mp.duration } ?: 0L,
        audioCodec = safe { mp.mediaInfo?.mAudioDecoder },
        decoding = safe { mp.mediaInfo?.mVideoDecoderImpl }
    )
}
