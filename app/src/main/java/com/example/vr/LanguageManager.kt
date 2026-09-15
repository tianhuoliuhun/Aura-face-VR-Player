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

    /** 跟随系统语言（默认） */
    const val SYSTEM = "system"

    /** 简体中文 */
    const val ZH_CN = "zh-CN"

    /** 繁体中文 */
    const val ZH_TW = "zh-TW"

    /** 英语 */
    const val EN = "en"

    private const val PREF_FILE = "vr_player_prefs"
    private const val PREF_KEY = "app_language"

    /** 界面上可选的语言（顺序即展示顺序） */
    val options: List<String> = listOf(SYSTEM, ZH_CN, ZH_TW, EN)

    /**
     * 语言选择项自己的名称。刻意用各自语言书写（不随界面语言变化），
     * 这样用户切错语言后仍能看懂选项、切回来。
     */
    fun displayName(tag: String): String = when (tag) {
        ZH_CN -> "简体中文"
        ZH_TW -> "繁體中文"
        EN -> "English"
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
     * 保存并立即生效（重建当前 Activity）。
     * @return true 表示已触发重建
     */
    fun applyAndRecreate(context: Context, tag: String): Boolean {
        setTag(context, tag)
        // 注意：Compose 里的 LocalContext.current 常被 ContextThemeWrapper 等包装多层，
        // 必须逐层解包才能拿到 Activity，只转一次会静默失败。
        val activity = findActivity(context) ?: return false
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
        else -> null // system：不覆盖，交给系统 locale
    }
}
