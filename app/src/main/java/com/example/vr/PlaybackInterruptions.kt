package com.example.vr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.core.content.ContextCompat

/**
 * v2.6.20：**播放被打断的统一处理** —— 音频焦点（来电 / 其它 App 抢占）+ 拔耳机 / 断蓝牙。
 *
 * ## 为什么需要它
 * 切后台暂停（见 `VRPlayerScreen` 的 LifecycleEventObserver）只覆盖了"用户主动离开"的场景；
 * 而"系统/别的应用要求我们安静"的场景此前完全没有处理：来电时视频声音和铃声叠着放、
 * 拔掉耳机瞬间外放 —— 这两个都是视频 App 的基本礼仪缺口。
 *
 * ## 规则
 *  - `AUDIOFOCUS_LOSS`（永久失去，如用户在别处点了播放）→ 暂停，**不自动恢复**；
 *  - `AUDIOFOCUS_LOSS_TRANSIENT` / `_CAN_DUCK`（临时失去，如来电、导航语音）→ 暂停，
 *    并记住 `transientPaused`；焦点归还（`AUDIOFOCUS_GAIN`）时**自动恢复** ——
 *    "挂了电话接着看"是视频场景的常规预期；
 *  - `ACTION_AUDIO_BECOMING_NOISY`（拔耳机 / 断蓝牙）→ 暂停，**不自动恢复**：
 *    用户拔耳机就是要换一种听法（或不想外放），替他续播只会当众翻车。
 *  - 用户**手动暂停**后焦点回调不会触发（我们不再 play），互不干扰。
 *
 * ## 生命周期约定
 * 由调用方在「播放器就绪且正在播放」时 [start]、「暂停或释放」时 [stop] ——
 * 焦点是稀缺资源，暂停后继续占着会把别的 App 的音乐一直压着。
 * 本类不持有播放器引用，全部通过回调反转控制，方便单测。
 *
 * ⚠️ `AudioFocusRequest` 是 API 26+ 的 API（`minSdk = 26`，无版本分支问题）。
 */
class PlaybackInterruptions(
    private val context: Context,
    /** 是否正在播放（用播放器门面的 isPlaying，别用 Compose 状态，避免重组时序差）。 */
    private val isPlaying: () -> Boolean,
    /** 暂停播放（调用方负责同步 UI 状态）。 */
    private val pause: () -> Unit,
    /** 恢复播放（仅临时失去焦点后的归还场景会被调用）。 */
    private val resume: () -> Unit,
) {
    private var focusRequest: AudioFocusRequest? = null
    private var noisyRegistered = false

    /** 临时失去焦点期间被我们暂停过 → 归还时恢复；永久失去/拔耳机不置位。 */
    private var transientPaused = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                transientPaused = false
                if (isPlaying()) pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                // CAN_DUCK 理论上可以只压音量，但视频内容压着音量继续播画面+字幕
                // 的体验并不好，统一按暂停处理，归还后续播。
                if (isPlaying()) {
                    transientPaused = true
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (transientPaused) {
                    transientPaused = false
                    resume()
                }
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                transientPaused = false
                if (isPlaying()) pause()
            }
        }
    }

    /** 请求音频焦点 + 注册拔耳机广播。重复调用幂等。 */
    fun start() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (focusRequest == null) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        }
        if (!noisyRegistered) {
            // 系统受保护广播，targetSdk 34+ 要求显式声明导出标志；
            // NOT_EXPORTED 下系统广播仍可送达（只有第三方应用被挡）。
            ContextCompat.registerReceiver(
                context,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyRegistered = true
        }
    }

    /** 放弃焦点 + 注销广播。重复调用幂等。 */
    fun stop() {
        if (focusRequest != null) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.abandonAudioFocusRequest(focusRequest!!)
            focusRequest = null
        }
        if (noisyRegistered) {
            runCatching { context.unregisterReceiver(noisyReceiver) }
            noisyRegistered = false
        }
        transientPaused = false
    }
}
