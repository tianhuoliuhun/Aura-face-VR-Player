package com.example.vr

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * v107：用户统计上报（可选功能，隐私合规优先）。
 *
 * 引擎：Firebase Analytics（免费，Google Play 生态）。
 *
 * 隐私原则（重要）：
 *  - 默认【不采集】：未获用户同意前不初始化、不上报任何数据
 *  - 首次启动弹窗征得同意后才初始化并上报启动/活跃
 *  - 用户拒绝或关闭统计后停止一切上报（setConsent(false)）
 *
 * 启用步骤（见 FIREBASE_ANALYTICS.md）：
 *  - 在 Firebase 控制台创建 Android 应用（包名 com.aistudio.vrplayer.vrmjpy）
 *  - 下载 google-services.json 放入 app/ 目录（构建时自动启用 google-services 插件）
 *  - 未配置 google-services.json 时：本模块自动禁用（FirebaseApp 无默认实例），构建/运行均不受影响
 */
object AnalyticsManager {

    private const val TAG = "Analytics"
    private const val PREFS = "analytics_prefs"
    private const val KEY_CONSENT = "analytics_consent"

    @Volatile private var initialized = false
    @Volatile private var firebaseAvailable = false

    /** 用户是否已同意统计 */
    fun hasConsent(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CONSENT, false)

    /** 设置同意状态；同意则初始化并开始采集，拒绝/关闭则停止一切上报 */
    fun setConsent(context: Context, granted: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CONSENT, granted).apply()
        if (granted) init(context.applicationContext) else shutdown()
    }

    /** 初始化 Firebase Analytics（仅在已同意时有效；无 google-services.json 时自动禁用） */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        try {
            // google-services.json 生效时 FirebaseApp 会自动初始化默认实例；
            // 未配置时 getApps() 为空 → 统计静默关闭，不影响其它功能
            firebaseAvailable = FirebaseApp.getApps(context).isNotEmpty()
            if (firebaseAvailable) {
                Log.i(TAG, "Firebase Analytics ready")
            } else {
                Log.i(TAG, "google-services.json 未配置，统计未启用")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Firebase 初始化失败: ${e.message}")
        }
    }

    /** 应用启动事件（MainActivity 首次进入时调用） */
    fun reportAppOpen(context: Context) {
        logEvent(context, FirebaseAnalytics.Event.APP_OPEN)
    }

    /** 自定义事件上报（播放、LUT、翻译等埋点可扩展） */
    fun logEvent(context: Context, eventName: String, params: Map<String, String> = emptyMap()) {
        if (!initialized || !hasConsent(context) || !firebaseAvailable) return
        try {
            val fa = FirebaseAnalytics.getInstance(context)
            if (params.isEmpty()) {
                fa.logEvent(eventName, null)
            } else {
                val b = Bundle()
                params.forEach { (k, v) -> b.putString(k, v) }
                fa.logEvent(eventName, b)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Firebase 上报失败: ${e.message}")
        }
    }

    /** 停止一切采集（用户拒绝/关闭统计后调用） */
    private fun shutdown() {
        firebaseAvailable = false
    }
}
