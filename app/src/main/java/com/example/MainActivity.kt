package com.example

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.ui.theme.MyApplicationTheme
import com.example.vr.AnalyticsManager
import com.example.vr.LanguageManager
import com.example.vr.VRPlayerScreen

class MainActivity : ComponentActivity() {
    private var externalMediaUriState by mutableStateOf<String?>(null)
    // v107：首次启动隐私同意弹窗（未同意前不采集任何数据）
    private var showPrivacyDialog by mutableStateOf(false)

    // v2.0.129：应用内界面语言。必须在 attachBaseContext 阶段包装 Context，
    // 晚于此（如 onCreate 里改 configuration）对已加载的资源不生效。
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemUi()

        handleIncomingIntent(intent)

        // v107：统计隐私同意检查 —— 默认不采集，用户同意后才初始化统计 SDK
        if (!AnalyticsManager.hasConsent(this)) {
            showPrivacyDialog = true
        } else {
            AnalyticsManager.init(this)
            AnalyticsManager.reportAppOpen(this)
        }

        setContent {
            MyApplicationTheme {
                // v107：隐私与数据统计同意弹窗（不可忽略，必须二选一）
                if (showPrivacyDialog) {
                    AlertDialog(
                        onDismissRequest = { /* 必须明确选择，不允许点击外部关闭 */ },
                        title = { Text(stringResource(R.string.privacy_dialog_title)) },
                        text = {
                            Text(
                                stringResource(R.string.privacy_dialog_msg1) +
                                    stringResource(R.string.privacy_dialog_msg2) +
                                    stringResource(R.string.privacy_bullet_1) +
                                    stringResource(R.string.privacy_bullet_2) +
                                    stringResource(R.string.privacy_bullet_3) +
                                    stringResource(R.string.privacy_bullet_4)
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                AnalyticsManager.setConsent(this@MainActivity, true)
                                showPrivacyDialog = false
                                AnalyticsManager.reportAppOpen(this@MainActivity)
                            }) { Text(stringResource(R.string.action_agree)) }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                AnalyticsManager.setConsent(this@MainActivity, false)
                                showPrivacyDialog = false
                            }) { Text(stringResource(R.string.action_decline)) }
                        }
                    )
                }
                VRPlayerScreen(
                    modifier = Modifier.fillMaxSize(),
                    initialVideoUri = externalMediaUriState,
                    onExternalUriConsumed = {
                        externalMediaUriState = null
                    }
                )
            }
        }
    }

    // v107：活跃统计由火山引擎 SDK 自动采集（前台/后台切换），无需手动上报

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi()
        }
    }

    private fun hideSystemUi() {
        val window = this.window ?: return
        val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type
        Log.d("MainActivity", "handleIncomingIntent: action=$action, type=$type")

        if (Intent.ACTION_VIEW == action) {
            val dataUri = intent.data
            if (dataUri != null) {
                externalMediaUriState = dataUri.toString()
                Log.d("MainActivity", "ACTION_VIEW video Uri: $externalMediaUriState")
            }
        } else if (Intent.ACTION_SEND == action && type != null) {
            if (type.startsWith("video/")) {
                val streamUri = intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? android.net.Uri
                if (streamUri != null) {
                    externalMediaUriState = streamUri.toString()
                    Log.d("MainActivity", "ACTION_SEND video Uri: $externalMediaUriState")
                }
            }
        }
    }
}
