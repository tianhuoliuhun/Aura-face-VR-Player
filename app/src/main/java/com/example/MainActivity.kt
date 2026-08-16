package com.example

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.ui.theme.MyApplicationTheme
import com.example.vr.VRPlayerScreen

class MainActivity : ComponentActivity() {
    private var externalMediaUriState by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemUi()

        handleIncomingIntent(intent)

        setContent {
            MyApplicationTheme {
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

