package com.example

import android.app.Application
import com.example.vr.AnalyticsManager

/**
 * v107：应用入口。
 * 仅做无副作用预初始化：统计 SDK 的初始化延后到用户明确同意后
 * （见 AnalyticsManager.setConsent），未同意前不采集任何数据。
 */
class AppApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 不在此处初始化统计 SDK；是否采集完全由用户同意决定
        // （同意弹窗由 MainActivity 首次启动时展示）
    }
}
