package com.example.vr.cast

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * v2.4.58：**投屏接收（DLNA/UPnP-AV MediaRenderer）与应用内的通信总线**。
 *
 * ## 为什么要有它
 * DLNA 的服务实现（Java，跑在 jUPnP 的网络线程里）与 Compose/播放器（主线程）之间
 * **不互相持有引用**，统一通过本对象通信，避免线程与生命周期耦合：
 *
 * - [requests]        ：DMR 收到 `SetAVTransportURI` → 投递 URL → UI 起播（**核心链路**）
 * - [commands]        ：DMR 收到 `Play/Pause/Stop/Seek` → UI 作用于现有播放器
 * - [playbackState]   ：UI 回写播放状态 → DMR 的 `GetTransportInfo/GetPositionInfo` 回读
 *
 * ## 用法
 * - Java 侧（服务类）只调用带 `@JvmStatic` 的静态方法；
 * - Kotlin 侧（UI）`collect` 两个 Flow、用 [reportState] 回写状态。
 */
object CastMediaBus {

    /** UI 侧收集：收到别人投送过来的投屏请求 */
    val requests = MutableSharedFlow<CastRequest>(extraBufferCapacity = 8)

    /** UI 侧收集：DMC（手机端）下发的播放控制指令 */
    val commands = MutableSharedFlow<CastCommand>(extraBufferCapacity = 16)

    /** DMR 侧读取：当前播放状态（由 UI 用 [reportState] 更新） */
    val playbackState = MutableStateFlow(CastPlaybackState())

    /** DMR（Java）调用：收到投屏 URL */
    @JvmStatic
    fun onSetUri(uri: String, title: String?) {
        requests.tryEmit(CastRequest(uri = uri, title = title))
    }

    /** DMR（Java）调用：收到播放控制指令 */
    @JvmStatic
    fun onCommand(type: CastCommand.Type, positionMs: Long) {
        commands.tryEmit(CastCommand(type, positionMs))
    }

    /** UI 调用：回写完整播放状态（供 DMC 轮询 `GetTransportInfo` / `GetPositionInfo`） */
    @JvmStatic
    fun reportState(state: CastPlaybackState) {
        playbackState.value = state
    }

    /**
     * UI 调用：**只回写播放进度**。
     *
     * 与 [reportState] 的区别：`transportState` 由 DMR 服务侧（收到 Play/Pause/Stop 时）维护，
     * UI 只负责提供播放器才知道的进度 —— 避免两边各写一次把状态互相覆盖。
     */
    @JvmStatic
    fun reportProgress(positionMs: Long, durationMs: Long, currentUri: String) {
        val cur = playbackState.value
        playbackState.value = cur.copy(
            positionMs = positionMs,
            durationMs = durationMs,
            currentUri = currentUri,
        )
    }
}

/** 一次投屏请求（来自 DMR 的 `SetAVTransportURI`） */
data class CastRequest(
    val uri: String,
    val title: String? = null,
)

/** 一条播放控制指令（来自 DMC） */
data class CastCommand(val type: Type, val positionMs: Long = 0L) {
    enum class Type { PLAY, PAUSE, STOP, SEEK }
}

/**
 * 播放状态快照。
 *
 * `transportState` 取值遵循 AVTransport:1 规范：
 * `NO_MEDIA_PRESENT` / `STOPPED` / `PLAYING` / `PAUSED_PLAYBACK` / `TRANSITIONING`
 */
data class CastPlaybackState(
    val transportState: String = "NO_MEDIA_PRESENT",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val currentUri: String = "",
)
