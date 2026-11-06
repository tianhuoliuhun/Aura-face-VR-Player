package com.example.vr

import android.view.Surface
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer

/**
 * 播放器统一门面（v2.1.233：为接入 ijkplayer 而引入）。
 *
 * ## 为什么要有这一层
 * `VRPlayerScreen` 里有 20 多处直接操作 `ExoPlayer`（倍速、seek、暂停、进度轮询、
 * 悬浮球、手柄…）。若把 `playerInstance` 直接换成另一种播放器类型，这些调用点会
 * 全线编译不过、且要逐个改语义。
 *
 * 因此这里抽出一层**只含项目真正用到的操作**的接口，两个实现：
 *  - [ExoBackend] —— 包 ExoPlayer（原链路，行为完全不变）
 *  - [IjkBackend] —— 包 IjkMediaPlayer（FFmpeg 内核，见 IjkPlayerBackend.kt）
 *
 * ## 设计约束
 * 1. **方法名与签名刻意与 ExoPlayer 保持一致**（`play/pause/seekTo/currentPosition/
 *    duration/isPlaying/setPlaybackSpeed/release`）。这样 `playerInstance` 的类型从
 *    `ExoPlayer?` 改成 `VrPlayerBackend?` 后，绝大多数调用点**一个字都不用改**。
 * 2. `setSurface` 是唯一刻意改名的方法：Exo 叫 `setVideoSurface`，ijk 叫 `setSurface`，
 *    取 ijk 的名字（更通用）。
 * 3. **只留 [exo] 一个"逃生舱口"**：轨道选择（音轨/内嵌字幕轨）是 ExoPlayer 专有能力，
 *    ijk 没有等效 API。需要它的调用点改成 `playerInstance?.exo?.let { … }`，
 *    ijk 下自然拿不到 → UI 显示"无轨道"，而不是崩。
 *
 * ⚠️ 互斥原则：`exo` 非空 ⇒ 走 Exo；`exo` 为 null ⇒ 走 ijk。不要在这层里塞别的引擎
 *    专属方法，否则每加一个内核都要回来改接口（MPV 将来同理）。
 */
interface VrPlayerBackend {
    /** 当前实际生效的解码内核（用于日志与 UI 提示）。 */
    val engine: DecoderEngine

    /**
     * ExoPlayer 专有逃生舱口。**ijk 下恒为 null**。
     * 只有真正需要 Exo 专有 API（轨道选择、trackSelectionParameters）时才用它。
     */
    val exo: ExoPlayer?

    /**
     * 本播放器绑定的媒体 URI（创建时记录）。用于位置轮询时校验「播放器确实在播当前片」，
     * 防止切片瞬间把旧片位置误存到新片 URI 下（v2.4.x 修复，审查 #10）。
     */
    val currentUri: android.net.Uri?

    val currentPosition: Long
    val duration: Long
    val isPlaying: Boolean

    /**
     * v2.6.24（直播 P0）：是否**直播流**（时长未知 / 不可任意 seek）。
     *
     * EXO 走 `isCurrentMediaItemLive`（HLS/DASH 直播原生判定）；IJK / MPV / 系统内核
     * 没有等效 API，按 `duration <= 0` 兜底 —— 调用方（UI）需连续多次采样确认，
     * 避免点播元数据解析期 duration 短暂为 0 时误判。
     */
    val isLive: Boolean

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)
    /** Exo 侧映射到 `setVideoSurface`；传 null 表示解绑。 */
    fun setSurface(surface: Surface?)
    fun release()

    /**
     * v2.6.24：**直播延迟**（ms）—— 让播放位置主动落后实时边缘 N 毫秒。
     *
     * 用途：直播的字幕要靠后台识别/翻译生成，比实时慢 1~3 秒；把播放压在
     * 「实时边缘 - N 秒」就能让字幕追上画面（用实时性换字幕可用性）。
     *
     * 默认空实现：这是 EXO 的 `LivePlaybackConfiguration` 能力，
     * IJK/MPV/系统内核没有等价 API（它们连直播判定都是靠 `duration<=0` 猜的），
     * 传进来直接忽略 —— 不能因为"某个内核没有这个能力"就让调用方处处判内核类型。
     */
    fun setLiveDelayMs(delayMs: Long) {}
}

/**
 * Exo 实现：纯代理，**不新增任何行为** —— 这是"零回归"的保证。
 *
 * ⚠️ [duration] 在 buffering 阶段可能是负数（C.TIME_UNSET）。这里不做修正，
 *    保持与改造前完全一致：调用方原本就是自己判 `duration > 0`。
 */
class ExoBackend(override val exo: ExoPlayer, uri: android.net.Uri? = null) : VrPlayerBackend {
    override val engine: DecoderEngine = DecoderEngine.EXO
    override val currentUri: android.net.Uri? = uri
    override val currentPosition: Long get() = exo.currentPosition
    override val duration: Long get() = exo.duration

    override val isLive: Boolean
        get() = try {
            // v2.6.24：**以 Timeline.Window.isLive 为权威判定** —— 实测某些 HLS 直播流
            // 的 duration 会返回「滑动窗口时长」（如 13s）而非 0，且 isCurrentMediaItemLive
            // 未必命中；窗口的 isLive 才是 Media3 内部真正用来判直播的字段。
            val tl = exo.currentTimeline
            val idx = exo.currentMediaItemIndex
            var windowLive = false
            if (!tl.isEmpty && idx >= 0 && idx < tl.windowCount) {
                val w = Timeline.Window()
                tl.getWindow(idx, w)
                windowLive = w.isLive
            }
            val live = windowLive || exo.isCurrentMediaItemLive || exo.duration <= 0
            live
        } catch (_: Throwable) {
            exo.duration <= 0
        }
    override val isPlaying: Boolean get() = exo.isPlaying
    override fun play() = exo.play()
    override fun pause() = exo.pause()
    override fun seekTo(positionMs: Long) = exo.seekTo(positionMs)
    override fun setPlaybackSpeed(speed: Float) = exo.setPlaybackSpeed(speed)
    override fun setSurface(surface: Surface?) = exo.setVideoSurface(surface)
    override fun release() = exo.release()

    /**
     * v2.6.24：直播延迟 —— 用 `MediaItem.LiveConfiguration.targetOffsetMs` 实现。
     *
     * 语义：EXO 把播放位置稳定维持在「实时边缘 - targetOffsetMs」。
     *
     * ## 为什么是换 MediaItem 而不是某个 setXxx
     * Media3 1.7.1 **没有** `Player.setLiveConfiguration(...)` 这种 API（已核对
     * `media3-common`/`media3-exoplayer` 的 aar：只有
     * `MediaItem$LiveConfiguration` 与其 Builder）。所以只能把当前 MediaItem
     * `buildUpon()` 换上新的 LiveConfiguration 再 `replaceMediaItem` 回去。
     *
     * ⚠️ 代价：`replaceMediaItem` 会让该 item 重新加载（直播约 1~2s 重新拉清单/缓冲）。
     *    这是用户低频的显式操作，可以接受；但**不能在没变的情况下重复调用** ——
     *    播放器重建（强制 EXO、切换解码器）都会重新下发一次，若每次都重载，
     *    用户会看到"画面莫名其妙又缓一次"。故用 [appliedLiveDelayMs] 去重。
     */
    override fun setLiveDelayMs(delayMs: Long) {
        val v = delayMs.coerceAtLeast(0L)
        // 已下发过同一个值 → 直接返回（避免播放器重建时白重载一次）
        if (v == appliedLiveDelayMs) return
        // 首次且为 0：默认行为本来就是"尽量贴实时边缘"，不必重载。
        // ⚠️ 顺序很关键：**先判相等再判首次**。反过来写（首次就先 applied = v）
        //    会让紧接着的相等判断立刻命中 → 非零延迟永远下发不下去（实测踩过）。
        if (appliedLiveDelayMs == Long.MIN_VALUE && v == 0L) {
            appliedLiveDelayMs = 0L
            return
        }
        try {
            val cur = exo.currentMediaItem
            if (cur == null) {
                // ⚠️ **不要**在这里记 appliedLiveDelayMs：此时播放器还没载入媒体项，
                // 记了就等于"永久跳过"——用户看到滑块有值、实际没生效。
                // 不记的话，下一次触发（直播确认、播放器 ready）会把同一个值再下发一次。
                android.util.Log.i("ExoBackend", "直播延迟暂存（mediaItem 未就绪）：${v}ms")
                return
            }
            val cfg = androidx.media3.common.MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(v)
                .build()
            val newItem = cur.buildUpon().setLiveConfiguration(cfg).build()
            exo.replaceMediaItem(exo.currentMediaItemIndex, newItem)
            exo.seekToDefaultPosition()
            appliedLiveDelayMs = v
            android.util.Log.i("ExoBackend", "直播延迟已应用：落后实时边缘 ${v}ms（重载该 item）")
        } catch (t: Throwable) {
            // 点播 / 播放器未就绪时会抛：延迟只是增强项，失败不该影响播放
            android.util.Log.w("ExoBackend", "设置直播延迟失败（$delayMs ms）：${t.message}")
        }
    }

    /** 已下发的直播延迟（ms）。[Long.MIN_VALUE] = 还没设过 */
    private var appliedLiveDelayMs: Long = Long.MIN_VALUE
}
