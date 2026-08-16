package com.example.vr

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONObject

/**
 * v107：用户统计上报（可选功能，隐私合规优先）。
 *
 * 引擎：火山引擎「增长分析 DataFinder」（Android SDK: com.volcengine:applog）。
 *
 * 隐私原则（重要）：
 *  - 默认【不采集】：未获用户同意前不初始化 SDK、不上报任何数据
 *  - 首次启动弹窗征得同意后才初始化并上报启动/活跃
 *  - 用户拒绝或关闭统计后停止一切上报（setConsent(false)）
 *
 * 集成说明：
 *  - 需在火山引擎控制台（console.volcengine.com）创建应用，获得 app_id
 *  - SDK 依赖与仓库见 build.gradle.kts 及 VOLCANO_ANALYTICS.md
 *  - app_id 配置在 AndroidManifest.xml 的 meta-data（APP_ID），占位符则不启用
 *  - 本类通过反射调用 AppLog，SDK 未引入时构建/运行均不受影响（自动跳过）
 */
object AnalyticsManager {

    private const val TAG = "Analytics"
    private const val PREFS = "analytics_prefs"
    private const val KEY_CONSENT = "analytics_consent"
    private const val APP_ID_PLACEHOLDER = "YOUR_VOLCENGINE_APP_ID"
    private const val APP_LOG_CLASS = "com.volcengine.applog.AppLog"

    @Volatile private var initialized = false
    @Volatile private var sdkReady = false
    @Volatile private var appId = ""

    /** 用户是否已同意统计 */
    fun hasConsent(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CONSENT, false)

    /** 设置同意状态；同意则初始化并开始采集，拒绝/关闭则停止一切上报 */
    fun setConsent(context: Context, granted: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CONSENT, granted).apply()
        if (granted) init(context.applicationContext) else shutdown()
    }

    /** 初始化火山引擎 SDK（仅在已同意时有效） */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appId = readMeta(context, "APP_ID").ifBlank { readMeta(context, "app_id") }
        val channel = readMeta(context, "APP_CHANNEL").ifBlank { "default" }
        try {
            // 反射检测 SDK 是否已引入；未引入则整个统计静默关闭
            Class.forName(APP_LOG_CLASS)
            if (appId.isBlank() || appId == APP_ID_PLACEHOLDER) {
                Log.i(TAG, "app_id 未配置（AndroidManifest APP_ID），统计未启用")
                return
            }
            val appLog = Class.forName(APP_LOG_CLASS)
            // AppLog.init(Context, String appId, String channel)
            val initMethod = appLog.getMethod(
                "init", Context::class.java, String::class.java, String::class.java
            )
            initMethod.invoke(null, context, appId, channel)
            sdkReady = true
            Log.i(TAG, "Volcengine DataFinder ready (appId=$appId)")
        } catch (e: Throwable) {
            Log.w(TAG, "Volcengine SDK 未集成或初始化失败: ${e.message}")
        }
    }

    /** 应用启动事件（MainActivity 首次进入时调用） */
    fun reportAppOpen(context: Context) {
        logEvent(context, "app_open")
    }

    /** 自定义事件上报（播放、LUT、翻译等埋点可扩展） */
    fun logEvent(context: Context, eventName: String, params: Map<String, String> = emptyMap()) {
        if (!initialized || !hasConsent(context) || !sdkReady) return
        try {
            val appLog = Class.forName(APP_LOG_CLASS)
            // AppLog.onEvent(String eventName, JSONObject params)
            val onEvent = appLog.getMethod("onEvent", String::class.java, JSONObject::class.java)
            val json = JSONObject()
            params.forEach { (k, v) -> json.put(k, v) }
            onEvent.invoke(null, eventName, json)
        } catch (e: Throwable) {
            Log.w(TAG, "Volcengine event failed: ${e.message}")
        }
    }

    /** 停止一切采集（用户拒绝/关闭统计后调用） */
    private fun shutdown() {
        sdkReady = false
        appId = ""
    }

    private fun readMeta(context: Context, key: String): String {
        return try {
            val ai = context.packageManager.getApplicationInfo(
                context.packageName, PackageManager.GET_META_DATA
            )
            ai.metaData?.getString(key) ?: ""
        } catch (e: Throwable) {
            ""
        }
    }
}
