/**
 * ⚠️ v2.6.5：**本文件当前没有 UI 入口**（用户要求移除「网页直链播放」入口）。
 *
 * 保留代码的原因：嗅探链路本身可用（实测能抓到直链并成功播放），
 * 只是"播放时会回退 MPV 内核"的观感问题尚未解决。若将来重新启用，
 * 需要把入口加回「选择文件来源」并处理内核选择，别直接删这个文件。
 */
package com.example.vr

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R

/**
 * v2.6.4：**网页直链播放**（网页嗅探 → 直接交给播放器）。
 *
 * ## 工作方式
 * 用一个不可见的抓取层（WebView + `shouldInterceptRequest`）加载用户给出的网页，
 * 页面发出的**每一个**请求都过一遍 [VideoSniffer.parse]：扩展名/路径长得像视频流就收下来。
 * 全程**不拦截请求**（`shouldInterceptRequest` 返回 null），视频该加载照常加载，只是"旁听"。
 *
 * ## 为什么不注入 JS hook
 * 注入 JS 抓 `<video>.src` / hook XHR 那套，在 WebView 里要处理
 * `evaluateJavascript` 时序、CSP、以及 MSE 分片地址，坑多且容易漏。
 * 走 `shouldInterceptRequest` 是**原生层旁听**，页面怎么请求都能看到，
 * 也不受页面 CSP 影响 —— 这是"大范围匹配"的关键：不依赖任何站点规则。
 *
 * ## 隐私（用户明确要求）
 * 结果列表**只显示脱敏名**（主机名 · 类型 · 清晰度），完整 URL（含 `?token=` / `?sign=`）
 * 只在点击播放的那一刻内部传给播放器，不出现在界面、不写日志、不进「最近播放」。
 */
@Composable
fun WebSnifferScreen(
    onPlay: (url: String, pageUrl: String) -> Unit,
    onClose: () -> Unit,
    accentColor: Color,
    accentOnColor: Color,
) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var currentHost by remember { mutableStateOf("") }
    // 当前页面完整地址 —— 作为直链的 Referer（多数 CDN 校验，缺失直接 403）
    var pageUrl by remember { mutableStateOf("") }
    val streams = remember { mutableStateListOf<VideoSniffer.SniffedStream>() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun open() {
        val raw = input.trim()
        if (raw.isBlank()) return
        val url = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw
                  else "https://$raw"
        streams.clear()
        loading = true
        webViewRef?.loadUrl(url)
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFC18171C))) {
        // ---------- 顶栏 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.04f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.web_sniffer_title),
                color = accentColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer8()
            Text(
                text = stringResource(R.string.web_sniffer_privacy_hint),
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_close),
                    tint = Color.White.copy(alpha = 0.7f),
                )
            }
        }

        // ---------- 地址栏 ----------
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).height(52.dp),
                placeholder = {
                    Text(
                        stringResource(R.string.web_sniffer_url_hint),
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.35f),
                    )
                },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
            )
            Spacer8()
            TextButton(
                onClick = { open() },
                enabled = input.isNotBlank(),
            ) {
                Text(stringResource(R.string.web_sniffer_open), color = accentColor, fontSize = 12.sp)
            }
        }

        // ---------- 网页层 ----------
        Box(modifier = Modifier.weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    @SuppressLint("SetJavaScriptEnabled")
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // 桌面 UA：部分站点对移动 UA 不返回视频流（只给 App 跳转）
                        settings.userAgentString = DESKTOP_UA
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): WebResourceResponse? {
                                val u = request?.url?.toString() ?: return null
                                VideoSniffer.parse(u)?.let { s ->
                                    mainHandler.post {
                                        if (streams.none { it.url == s.url }) {
                                            streams.add(s)
                                            val sorted = VideoSniffer.dedupe(streams.toList())
                                            streams.clear()
                                            streams.addAll(sorted)
                                        }
                                    }
                                }
                                return null   // 只旁听，不拦截
                            }

                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: Bitmap?,
                            ) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                pageUrl = url ?: ""
                                currentHost = url?.let { runCatching { java.net.URI(it).host }.getOrNull() } ?: ""
                            }
                        }
                        webViewRef = this
                    }
                },
            )
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp).align(Alignment.TopEnd).padding(8.dp),
                    color = accentColor,
                )
            }
        }

        // ---------- 嗅探结果（隐私：只显示脱敏名） ----------
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.05f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.web_sniffer_found, streams.size),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
            )
            if (streams.isEmpty()) {
                Text(
                    text = stringResource(R.string.web_sniffer_empty),
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 190.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(streams, key = { it.url }) { s ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.07f))
                                .clickable { onPlay(s.url, pageUrl) }   // 完整 URL + 来源页只在这里传给播放器
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer8()
                            Text(
                                text = VideoSniffer.displayName(s),
                                color = Color.White,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 桌面版 Chrome UA —— 让站点按桌面规则返回视频源 */
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/** 小间距（避免在每处都写 Modifier.padding） */
@Composable
private fun Spacer8() {
    Box(modifier = Modifier.size(8.dp))
}
