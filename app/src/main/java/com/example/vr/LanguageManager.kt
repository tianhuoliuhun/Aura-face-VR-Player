package com.example.vr

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * v2.0.129：界面多语言管理。
 *
 * 设计要点：
 * 1. **不引入 appcompat**：项目是纯 Compose + Material3，`MainActivity` 继承
 *    `ComponentActivity`。为应用内切语言而拉进整个 appcompat 不划算，这里直接用
 *    `createConfigurationContext()` 包装 Context，兼容 minSdk 24。
 * 2. **跟随系统**：tag = [SYSTEM] 时不做任何包装，走系统语言（values-zh-rTW / values-en
 *    由系统 locale 自动命中）。
 * 3. **持久化**：沿用 `vr_player_prefs`（与其它设置同一个文件）。
 *
 * 新增语言的步骤：
 *   - 在 [options] 与 [displayName] 加一项
 *   - 在 [localeOf] 加映射
 *   - 新建 `app/src/main/res/values-<qualifier>/strings.xml`
 *
 * 切换语言的调用方负责 `Activity.recreate()`（见 VRPlayerScreen 的界面语言选项）。
 */
object LanguageManager {

    /**
     * 宿主 Activity 需要实现的接口。
     * v2.0.131：切语言改用「替换 LocalContext + 重组」，不再 Activity.recreate()，
     * 这样当前视频、播放进度、预览缩略图都不会丢。
     */
    interface LanguageHost {
        fun applyLanguage(tag: String)
    }

    /** 跟随系统语言（默认） */
    const val SYSTEM = "system"

    /** 简体中文 */
    const val ZH_CN = "zh-CN"

    /** 繁体中文 */
    const val ZH_TW = "zh-TW"

    /** 英语 */
    const val EN = "en"

    /** 日语 */
    const val JA = "ja"

    /** 韩语 */
    const val KO = "ko"

    private const val PREF_FILE = "vr_player_prefs"
    private const val PREF_KEY = "app_language"

    /** 界面上可选的语言（顺序即展示顺序） */
    val options: List<String> = listOf(SYSTEM, ZH_CN, ZH_TW, EN, JA, KO)

    /**
     * 语言选择项自己的名称。刻意用各自语言书写（不随界面语言变化），
     * 这样用户切错语言后仍能看懂选项、切回来。
     */
    fun displayName(tag: String): String = when (tag) {
        ZH_CN -> "简体中文"
        ZH_TW -> "繁體中文"
        EN -> "English"
        JA -> "日本語"
        KO -> "한국어"
        else -> "跟随系统 / Follow system"
    }

    fun getTag(context: Context): String =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .getString(PREF_KEY, SYSTEM) ?: SYSTEM

    fun setTag(context: Context, tag: String) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().putString(PREF_KEY, tag).apply()
    }

    /**
     * 按 [tag] 包装 Context。供 `Activity.attachBaseContext()` 调用，
     * 使 Activity 及其 Compose 树使用指定语言的资源。
     */
    fun wrap(context: Context, tag: String = getTag(context)): Context {
        val locale = localeOf(tag) ?: return context
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    /**
     * 保存并立即生效。
     * 优先走 [LanguageHost]（只换 LocalContext，不重建 Activity，播放状态不丢）；
     * 宿主没实现该接口时才退回 recreate（此时会丢失播放状态，仅作兜底）。
     * @return true 表示已应用
     */
    fun apply(context: Context, tag: String): Boolean {
        setTag(context, tag)
        // 注意：Compose 里的 LocalContext.current 常被 ContextThemeWrapper 等包装多层，
        // 必须逐层解包才能拿到 Activity，只转一次会静默失败。
        val activity = findActivity(context) ?: return false
        if (activity is LanguageHost) {
            activity.applyLanguage(tag)
            return true
        }
        activity.recreate()
        return true
    }

    /** 逐层解 ContextWrapper 找到宿主 Activity */
    private fun findActivity(context: Context?): Activity? {
        var c = context
        while (c != null) {
            if (c is Activity) return c
            c = (c as? android.content.ContextWrapper)?.baseContext
        }
        return null
    }

    private fun localeOf(tag: String): Locale? = when (tag) {
        ZH_CN -> Locale.SIMPLIFIED_CHINESE
        ZH_TW -> Locale.TRADITIONAL_CHINESE
        EN -> Locale.ENGLISH
        JA -> Locale.JAPANESE
        KO -> Locale.KOREAN
        else -> null // system：不覆盖，交给系统 locale
    }
}
