package com.example.vr

import android.content.SharedPreferences

/**
 * v2.1.231：**悬浮球位置记忆**。
 *
 * ## 为什么存「比例」而不是「像素」
 * 悬浮球的可移动范围是 `[0, 容器宽 − 球直径]`，容器尺寸随设备、横竖屏、分屏变化。
 * 若直接存像素，横屏存的 x 用到竖屏上会跑出屏幕（或贴边），因此统一存
 * **相对可移动范围的 0~1 比例**，恢复时再乘以当次的 maxX/maxY。
 *
 * ## 为什么由组件自己读写 prefs，而不是走父级的写回 LaunchedEffect
 * 位置是**拖动结束时一次性提交**的高频交互值。若塞进父级那个巨大的
 * `LaunchedEffect(key 列表)`，一是要往 key 列表里加 8 个 key（本项目头号坑：
 * 漏 key = 永不落盘），二是拖动过程中每帧重组都会触发写回 effect。
 * 由组件在「抬手瞬间」直接 commit 更内聚、也更好排查。
 *
 * ⚠️ 读与写都必须受「记忆模式」门控：关闭记忆模式时既不读（用默认比例）也不写。
 */
object FloatingBallPositions {
    /** 加速球（按住生效的倍速球） */
    const val SPEED = "speed"
    /** 快进球 */
    const val SEEK_FWD = "seek_fwd"
    /** 后退球 */
    const val SEEK_BWD = "seek_bwd"
    /** 时间标记球 */
    const val MARKER = "marker"

    private fun keyX(id: String) = "ball_pos_${id}_xr"
    private fun keyY(id: String) = "ball_pos_${id}_yr"

    /** 无存档时返回 null（调用方回落到各自的默认比例） */
    fun hasSaved(prefs: SharedPreferences, id: String): Boolean =
        prefs.contains(keyX(id)) && prefs.contains(keyY(id))

    fun loadX(prefs: SharedPreferences, id: String): Float =
        prefs.getFloat(keyX(id), Float.NaN)

    fun loadY(prefs: SharedPreferences, id: String): Float =
        prefs.getFloat(keyY(id), Float.NaN)

    fun save(prefs: SharedPreferences, id: String, xRatio: Float, yRatio: Float) {
        // 钳制到 [0,1]：屏幕尺寸变化导致的越界会在下次恢复时被 coerceIn，但存档本身也别越界
        prefs.edit()
            .putFloat(keyX(id), xRatio.coerceIn(0f, 1f))
            .putFloat(keyY(id), yRatio.coerceIn(0f, 1f))
            .apply()
    }

    /** 记忆模式关闭时清掉全部存档（与项目「关闭即 remove」的约定一致） */
    fun clearAll(prefs: SharedPreferences) {
        prefs.edit().apply {
            listOf(SPEED, SEEK_FWD, SEEK_BWD, MARKER).forEach { id ->
                remove(keyX(id))
                remove(keyY(id))
            }
        }.apply()
    }
}
