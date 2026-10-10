package com.example.vr

import android.view.Surface
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

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)
    /** Exo 侧映射到 `setVideoSurface`；传 null 表示解绑。 */
    fun setSurface(surface: Surface?)
    fun release()
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
    override val isPlaying: Boolean get() = exo.isPlaying
    override fun play() = exo.play()
    override fun pause() = exo.pause()
    override fun seekTo(positionMs: Long) = exo.seekTo(positionMs)
    override fun setPlaybackSpeed(speed: Float) = exo.setPlaybackSpeed(speed)
    override fun setSurface(surface: Surface?) = exo.setVideoSurface(surface)
    override fun release() = exo.release()
}
