/**
 * v2.9.0：**网页直链播放 UI 层**，入口在「选择文件来源」对话框的入口 4
 * （`VRPlayerScreen` 的 `showWebSniffer` → 本全屏覆盖层）。
 *
 * 历史：v2.6.5 曾按用户要求移除入口（当时只保留实现），原因是 Exo 首帧报错后
 * 自动回退 MPV 内核的观感不可接受。v2.9.0 已把入口加回，并给 URL 播放加了
 * 可选「来源页面」输入框 + 修正 Referer 推断（见 [HttpPlaybackHeaders]）。
 *
 * ⚠️ **仍未处理**：v2.6.5 记录的内核选择问题（Exo → MPV 自动换核）没有改动。
 * 重新启用入口后若再次出现换核，需要在 `VRPlayerScreen` 的内核路由里
 * 对网页/URL 来源做特判 —— 这是重启该功能的验收条件，别误以为已经解决。
 */
package com.example.vr

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v2.8.3：**一次网页播放的完整上下文**（直链 + 来源页 + 会话 + 选中的外挂字幕）。
 *
 * 为什么要打包成一个对象：这几项必须**同时**交给播放器 —— 少了 Cookie 会 403，
 * 少了 Referer 也会 403，少了字幕就是"网页有字幕、我们放出来没有"。
 * 以前 [WebSnifferScreen] 的 `onPlay` 只有 `(url, pageUrl)` 两个参数，
 * 每加一项就要改一次回调签名与所有调用点，容易漏。
 */
data class WebPlayChoice(
    /** 直链（可能是重定向后的最终地址） */
    val url: String,
    /** 嗅探来源页 —— 作为 Referer */
    val pageUrl: String,
    /** WebView 的 Cookie（无会话态时为 null） —— v2.8.3 新增 */
    val cookie: String? = null,
    /** 选中的外挂字幕（未选为空） */
    val subtitleCues: List<SubtitleCue> = emptyList(),
    /** 字幕展示名（写进「已加载字幕」提示与导出文件名） */
    val subtitleName: String? = null,
)

/**
 * v2.8.3：**待挂载的网页字幕**（嗅探面板 → 播放器的一次性交接）。
 *
 * 为什么要单独一个类型、由播放器侧的 `LaunchedEffect(selectedMediaItem.uri)` 消费：
 * 切换媒体时那个 Effect 开头会**清空** `loadedSubtitleCues`（防止上一个视频的字幕残留），
 * 如果面板直接写 `loadedSubtitleCues`，刚设好的字幕会在同一帧内被清掉 ——
 * 表现为"选了字幕，进播放器却没有"。
 */
data class WebSubtitle(
    val name: String,
    val cues: List<SubtitleCue>,
)

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
    onPlay: (WebPlayChoice) -> Unit,
    onClose: () -> Unit,
    accentColor: Color,
    accentOnColor: Color,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var currentHost by remember { mutableStateOf("") }
    // 当前页面完整地址 —— 作为直链的 Referer（多数 CDN 校验，缺失直接 403）
    var pageUrl by remember { mutableStateOf("") }
    val streams = remember { mutableStateListOf<VideoSniffer.SniffedStream>() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // ---------------- v2.8.3 新增状态 ----------------
    /** 正在探测（跟 302 / 解析主清单）的流；条目上转圈，同时防重复点击 */
    var resolvingUrl by remember { mutableStateOf<String?>(null) }
    /** 解析出多档码率时暂存「哪个流 + 哪几档」，交给用户选 */
    var variantPicker by remember {
        mutableStateOf<Pair<VideoSniffer.SniffedStream, List<HlsPlaylist.Variant>>?>(null)
    }
    /** 已抓到的外挂字幕（`.vtt/.srt/.ass` 直链，以及 HLS 的 SUBTITLES 轨） */
    val subtitleCandidates = remember { mutableStateListOf<WebSubtitleSniffer.Candidate>() }
    /** 当前选中的字幕 URL；null = 不挂字幕 */
    var selectedSubUrl by remember { mutableStateOf<String?>(null) }
    /** 正在下载的字幕 URL（去重用，纯内存集合，不参与重组） */
    val fetchingSubs = remember { mutableSetOf<String>() }
    /** 已认领的**分片目录**（HLS 字幕分片同目录只抓第一片，见 enqueueSubtitle） */
    val claimedSegDirs = remember { mutableSetOf<String>() }
    /**
     * HLS 字幕逐片合并进度：`url -> (已完成, 总数)`。
     *
     * 必须让用户看见：长片字幕要下上千片，几十秒里面板一片空白，
     * 用户会以为「没抓到字幕」直接点播放走人，白等一场。
     */
    val subProgress = remember { mutableStateMapOf<String, Pair<Int, Int>>() }

    /** 候选字幕上限：多语言轨一般不超过 3~4 条，给 6 条余量足够 */
    val maxSubCandidates = 6

    /**
     * 取 Cookie：先按**直链**取，取不到再按**来源页**取。
     *
     * 为什么两个都要试：站点常把票据种在页面域名上（`www.site.com`），
     * 而直链在 CDN 域名（`cdn.site.com`）—— `CookieManager.getCookie(cdn)` 会是空的。
     * 反过来也有站点把票据种在 CDN 上。两个都试一遍成本极低，漏一个就 403。
     */
    fun cookieFor(url: String): String? =
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: runCatching { CookieManager.getInstance().getCookie(pageUrl) }.getOrNull()
                ?.takeIf { it.isNotBlank() }

    /**
     * 探测 / 抓字幕用的请求头：桌面 UA + 页面 Referer + 会话 Cookie。
     * ⚠️ **不带 `Range`** —— [StreamProbe] 自己决定要不要带（清单要全文，直链只嗅 64KB）。
     */
    fun headersFor(url: String): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = DESKTOP_UA
        pageUrl.takeIf { it.isNotBlank() }?.let { h["Referer"] = it }
        cookieFor(url)?.let { h["Cookie"] = it }
        return h
    }

    /** 真正开始播放：把直链 + 来源页 + Cookie + 选中字幕一次性交出去 */
    fun startPlay(s: VideoSniffer.SniffedStream, url: String) {
        val sub = subtitleCandidates.firstOrNull { it.url == selectedSubUrl }
        onPlay(
            WebPlayChoice(
                url = url,
                pageUrl = pageUrl,
                cookie = cookieFor(url),
                subtitleCues = sub?.cues ?: emptyList(),
                subtitleName = sub?.label,
            )
        )
    }

    /**
     * 后台抓一份字幕（`.vtt` / `.srt` / `.ass` / HLS 字幕清单）。
     *
     * ⚠️ 为什么要在**嗅探阶段**就抓、而不是等播放时再抓：直链与字幕地址常常带
     * 一次性签名，面板关闭后签名可能已过期；而且用户点「播放」的那一刻就该
     * 已经知道"有没有字幕"，否则要等几十秒才出现字幕，观感像卡住。
     *
     * @param labelOverride HLS `#EXT-X-MEDIA` 给的人类可读名（`中文 (zh)`）
     */
    fun enqueueSubtitle(url: String, labelOverride: String? = null) {
        if (url.isBlank()) return
        if (subtitleCandidates.size >= maxSubCandidates) return
        // HLS 字幕分片会"一片一个请求"地连续打过来（`seg0.vtt`/`seg1.vtt`…）。
        // 若每片都当独立字幕去抓，一部片子会冒几百个候选、白吃流量 ——
        // 对带数字命名的分片按**目录**认领，只抓第一片。
        // 真正的语言文件（`zh.vtt`/`en.vtt`）不含数字，各抓一份，不会漏语言。
        val isSegment = WebSubtitleSniffer.looksLikeSegment(url)
        val dir = url.substringBefore('?').substringBefore('#').substringBeforeLast('/')
        synchronized(fetchingSubs) {
            if (isSegment && dir in claimedSegDirs) return
            if (!fetchingSubs.add(url)) return
            if (isSegment) claimedSegDirs.add(dir)
        }
        if (subtitleCandidates.any { it.url == url }) {
            synchronized(fetchingSubs) { fetchingSubs.remove(url) }
            return
        }
        val headers = headersFor(url)
        Thread {
            val c = WebSubtitleSniffer.fetch(url, headers, labelOverride) { d, total ->
                // 回调在下载线程上；节流已在 sniffer 侧做过（每 5% 一档），这里只负责切主线程
                mainHandler.post {
                    val prev = subProgress[url]
                    if (prev == null || d > prev.first) subProgress[url] = d to total
                }
            }
            mainHandler.post {
                synchronized(fetchingSubs) { fetchingSubs.remove(url) }
                subProgress.remove(url)
                if (c != null && subtitleCandidates.none { it.url == c.url }) {
                    subtitleCandidates.add(c)
                    // 第一份抓到的自动选中：用户直接点播放就能带上字幕，不用再点一次
                    if (selectedSubUrl == null) selectedSubUrl = c.url
                }
            }
        }.apply {
            name = "web-subtitle"
            isDaemon = true
            // 🔴 原为 MIN_PRIORITY：下载线程被 UI 线程长期抢占，长片字幕实测慢一倍以上。
            // 这是用户正盯着进度等的链路，用默认优先级。
            priority = Thread.NORM_PRIORITY
        }.start()
    }

    /**
     * 点击一条流：先探测（跟 302 + 解析主清单），再决定怎么播。
     *
     * 三种结果：
     *  1. **主清单且有多档** → 展开码率选择，让用户自己挑（不再由内核慢慢升档）；
     *  2. **主清单但只有一档** → 直接播那一档（顺手把声明的字幕轨加进候选）；
     *  3. **不是清单 / 探测失败** → 用最终地址播。**探测失败不阻断播放**：
     *     个别 CDN 对额外请求过敏，或只认特定 Range，此时原样播放往往仍能成功。
     */
    fun tapStream(s: VideoSniffer.SniffedStream) {
        if (resolvingUrl != null) return
        variantPicker = null
        resolvingUrl = s.url
        val headers = headersFor(s.url)   // 必须在主线程取 Cookie
        scope.launch {
            val r = withContext(Dispatchers.IO) { StreamProbe.probe(s.url, headers) }
            resolvingUrl = null
            val master = r.playlist
            if (master != null) {
                // HLS 里声明的外挂字幕轨（TYPE=SUBTITLES）—— 这是「网页字幕」最可靠的来源，
                // 比等页面去请求 .vtt 更早拿到，也不依赖页面的播放器实现。
                master.subtitleTracks.forEach { t -> t.uri?.let { enqueueSubtitle(it, t.label) } }
                if (master.variants.size > 1) {
                    variantPicker = s to master.variants
                } else {
                    startPlay(s, master.variants.first().uri)
                }
                return@launch
            }
            if (!r.ok && r.reason != null) {
                Toast.makeText(context, r.reason, Toast.LENGTH_SHORT).show()
            }
            startPlay(s, r.finalUrl.ifBlank { s.url })
        }
    }

    fun open() {
        val raw = input.trim()
        if (raw.isBlank()) return
        val url = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw
                  else "https://$raw"
        streams.clear()
        // v2.8.3：换页面必须把上一页的字幕/码率选择一并清掉，
        // 否则用户会在新页面上看到上一个站点的字幕候选（点下去必然解析失败）
        subtitleCandidates.clear()
        selectedSubUrl = null
        variantPicker = null
        resolvingUrl = null
        synchronized(fetchingSubs) {
            fetchingSubs.clear()
            claimedSegDirs.clear()
        }
        // 旧页面的合并进度同样要清：否则新页面上会一直挂着上一页的「合并字幕 x/y」
        subProgress.clear()
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
                // 🔴 v2.9.1：**必须显式给文字颜色**。原来只设了 fontSize，颜色走默认值
                //   （深色背景下是接近黑），于是"粘完地址看不见自己输入了什么"——
                //   实测 dump 里 value 是 `http://127.0.0.1:8099/`，但截图上输入框一片空白。
                //   placeholder 因为单独指定了白色所以一直可见，更容易让人误判成"没粘上"。
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Color.White),
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
                        // 🔴🔴 v2.9.1：**必须禁用 WebView 的硬件层**，否则它画在窗口原点。
                        //
                        // 实测（MuMu / 1600x900，`dumpsys activity top` + 截图取证）：
                        //   布局完全正确 —— `ViewFactoryHolder{0,186-1600,786}`，
                        //   `WebView{0,0-1600,600}`（相对 holder）；
                        //   但截图上 WebView 的底色实际覆盖 `y=0~786`（宽度铺满）。
                        // 即 WebView 的绘制位置停在窗口原点、高度取了 holder 的 bottom。
                        // 后果：它盖住顶栏与地址栏 → 用户打开「网页直链播放」只看到
                        // 一片黑 + 底部结果条，地址栏根本点不到（虽然命中测试是好的）。
                        //
                        // 根因是 WebView 内部合成层走 `LAYER_TYPE_HARDWARE` 的 RenderNode，
                        // 其位置更新没有被应用到 Compose 的 `AndroidViewsHandler` 变换上。
                        // 强制软件层后，WebView 直接用父容器的 canvas 绘制，位置就归位了。
                        // 代价：网页渲染不走 GPU 合成（本页只用于嗅探抓流，可接受）。
                        setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
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
                                // v2.8.3：**字幕也"旁听"** —— 页面自己会去拉 `.vtt`（放行不拦），
                                // 我们只在后台另抓一份并解析成 cue，用于挂载与导出。
                                // 判据在 WebSubtitleSniffer 里（扩展名 + `?format=vtt` 两种形式）。
                                if (WebSubtitleSniffer.looksLikeSubtitle(u)) {
                                    mainHandler.post { enqueueSubtitle(u) }
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
            } else if (variantPicker == null) {
                // 展开码率选择时先收起流列表：两者叠在一起会把 WebView 挤成 0 高度，
                // 而且此刻用户已经选定了某条流，列表留着也没用（取消后会回来）。
                LazyColumn(
                    modifier = Modifier.heightIn(max = 190.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(streams, key = { it.url }) { s ->
                        val resolving = resolvingUrl == s.url
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.07f))
                                // 🔴 v2.8.3：点击不再直接播 —— 先跟 302 / 解析主清单，
                                //   多档码率时交给用户选（见 tapStream）。探测期间禁止再点，
                                //   否则会并发发出多个探测请求、并在两个回调里各播一次。
                                .clickable(enabled = resolvingUrl == null) { tapStream(s) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (resolving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = accentColor,
                                )
                            } else {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
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

            // ---------- v2.8.3：码率选择（只有主清单给出多档时才出现） ----------
            variantPicker?.let { (stream, variants) ->
                Text(
                    text = stringResource(R.string.web_sniffer_quality_title),
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 150.dp).padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(variants) { v ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.09f))
                                .clickable {
                                    variantPicker = null
                                    startPlay(stream, v.uri)
                                }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = v.label,
                                color = Color.White,
                                fontSize = 11.sp,
                                modifier = Modifier.weight(1f),
                            )
                            // 码率之外再给一眼分辨率：两者不一定成正比
                            // （有的站 1080P 只有 800kbps，纯看码率反而选错）
                            v.resolution?.let {
                                Text(
                                    text = it,
                                    color = Color.White.copy(alpha = 0.45f),
                                    fontSize = 9.sp,
                                )
                            }
                        }
                    }
                }
                TextButton(onClick = { variantPicker = null }) {
                    Text(
                        stringResource(R.string.action_cancel),
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                    )
                }
            }

            // ---------- v2.8.3：抓到的字幕（选中后随片挂载，可导出） ----------
            // 进度优先：长片字幕要合上千片、几十秒，面板一片空白时用户会以为没抓到直接走人
            val inProgress = subProgress.values.firstOrNull()
            if (subtitleCandidates.isNotEmpty() || inProgress != null) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    if (subtitleCandidates.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.web_sniffer_subtitle_title,
                                subtitleCandidates.size,
                            ),
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                        )
                    }
                    if (inProgress != null) {
                        Text(
                            text = stringResource(
                                R.string.web_sniffer_subtitle_progress,
                                inProgress.first,
                                inProgress.second,
                            ),
                            color = accentColor.copy(alpha = 0.85f),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(top = if (subtitleCandidates.isEmpty()) 0.dp else 2.dp),
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    subtitleCandidates.forEach { c ->
                        SubtitleChip(
                            candidate = c,
                            selected = c.url == selectedSubUrl,
                            accentColor = accentColor,
                            // 再点一次取消选中：有些片子的外挂字幕反而更差（机翻 / 错轴）
                            onClick = { selectedSubUrl = if (c.url == selectedSubUrl) null else c.url },
                        )
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

/**
 * 一条字幕候选的 chip。
 *
 * 文本构成：`名称 · 条数 [· 仅前 MM:SS] [· 缺 N 片]`。
 * 后两段只在**真的发生**截断 / 缺口时出现 —— 用户必须知道这份字幕覆盖到哪，
 * 否则「导出的 SRT 后半段没字幕」会被当成导出功能坏了。
 */
@Composable
private fun SubtitleChip(
    candidate: WebSubtitleSniffer.Candidate,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
) {
    val partial = if (candidate.truncated) {
        val cov = candidate.coverageLabel
        if (cov != null) stringResource(R.string.web_sniffer_subtitle_partial, cov) else null
    } else null
    val gaps = if (candidate.failedSegments > 0) {
        stringResource(R.string.web_sniffer_subtitle_gaps, candidate.failedSegments)
    } else null
    val text = listOfNotNull(candidate.label, candidate.cues.size.toString(), partial, gaps)
        .joinToString(" · ")

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) accentColor.copy(alpha = 0.22f)
                else Color.White.copy(alpha = 0.08f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            color = if (selected) accentColor else Color.White.copy(alpha = 0.75f),
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}
