package com.example

import android.app.Application
import android.util.Log
import com.example.vr.AnalyticsManager
import com.example.vr.SherpaAsrManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

        // v2.0.127：后台清理已废弃引擎（Vosk / Qwen3 / SenseVoice QNN）留下的模型目录。
        // 这些模型可能占用数百 MB 到 1GB+，而对应引擎已从应用中移除。
        // 放后台线程：删除量大，不能阻塞启动。
        CoroutineScope(Dispatchers.IO).launch {
            val freed = SherpaAsrManager.cleanupLegacyModels(this@AppApplication)
            if (freed > 0) {
                Log.i("AppApplication", "启动清理完成，释放 ${freed / 1048576}MB")
            }
        }
    }
}
