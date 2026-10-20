package com.example.vr

import androidx.compose.animation.core.Easing

/**
 * v2.6.7：UI 通用缓动曲线（从 `VRPlayerScreen` 搬出，逻辑未改）。
 *
 * `EaseOutBack`：先冲过目标再回弹。用于分组展开、悬浮球按下等"有生命感"的动作。
 */
internal val CustomEaseOutBack = Easing { fraction ->
    val t = fraction - 1.0f
    val c1 = 1.70158f
    val c3 = c1 + 1.0f
    1.0f + c3 * t * t * t + c1 * t * t
}
