package com.example.vr

import android.content.SharedPreferences

/**
 * v119 修复(#7)：按媒体 URI 持久化播放位置。
 * v119 拆分：从 VRPlayerScreen.kt 搬出为独立文件。
 *
 * 旧实现用单个 `restorePositionMs` 变量，存在两个致命问题：
 * 1) 语义名不副实 —— 它被 150ms 的轮询写成"当前播放位置"，只是 currentPosition 的镜像，
 *    并不是"上次看到哪儿"；
 * 2) 跨媒体共享 —— 切到新视频时该值仍是上一个视频的位置，唯一的"保护"是边界判断
 *    `restorePositionMs < playerInstance?.duration`，而此处读的是**已 release 的旧播放器**
 *    （新 exo 尚未赋给 playerInstance），其 duration 在 release 后为 C.TIME_UNSET（负数），
 *    条件恒假。也就是说 seek 之所以没出错，纯属依赖了未声明的行为，ExoPlayer 一旦改变
 *    release 后 getDuration() 的返回，就会立刻变成"新视频跳到上个视频的位置"。
 *
 * 现在改为：位置按 URI 存 SharedPreferences，恢复时边界判断用**新建且已 READY 的播放器**
 * 自身的 duration；播放到结尾自动清除记录，下次从头开始。
 */
internal object PlaybackPositions {
    private const val KEY_PREFIX = "playback_pos_v1_"
    private const val END_TOLERANCE_MS = 1_000L // 距结尾 1s 内视为已看完

    private fun key(uri: String) = KEY_PREFIX + uri

    fun load(prefs: SharedPreferences, uri: String?): Long {
        if (uri.isNullOrBlank()) return 0L
        return prefs.getLong(key(uri), 0L).coerceAtLeast(0L)
    }

    fun save(prefs: SharedPreferences, uri: String?, positionMs: Long) {
        if (uri.isNullOrBlank() || positionMs <= 0L) return
        prefs.edit().putLong(key(uri), positionMs).apply()
    }

    fun clear(prefs: SharedPreferences, uri: String?) {
        if (uri.isNullOrBlank()) return
        prefs.edit().remove(key(uri)).apply()
    }

    /** 是否值得恢复：既要有记录，又不能已经播到结尾 */
    fun shouldResume(savedMs: Long, durationMs: Long): Boolean {
        if (savedMs <= 0L) return false
        if (durationMs <= 0L) return true // duration 尚未就绪时不拦，交给播放器自行 clamp
        return savedMs < durationMs - END_TOLERANCE_MS
    }
}
