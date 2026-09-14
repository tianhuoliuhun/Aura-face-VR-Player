package com.example.vr

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class TranslationEngine(
    val id: Int,
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val requiresApiKey: Boolean
) {
    BING(
        id = 0,
        displayName = "必应翻译",
        defaultBaseUrl = "https://cn.bing.com/ttranslatev3",
        defaultModel = "bing-translate",
        requiresApiKey = false
    ),
    DEEPSEEK(
        id = 1,
        displayName = "DeepSeek API",
        defaultBaseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        requiresApiKey = true
    ),
    QWEN(
        id = 2,
        displayName = "通义千问",
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen-turbo",
        requiresApiKey = true
    ),
    GLM(
        id = 3,
        displayName = "智谱",
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
        defaultModel = "glm-4-flash",
        requiresApiKey = true
    ),
    MIMO(
        id = 4,
        displayName = "MiniMax / MIMO",
        defaultBaseUrl = "https://api.minimax.chat/v1",
        defaultModel = "abab6.5g-chat",
        requiresApiKey = true
    ),
    OPENAI(
        id = 5,
        displayName = "OpenAI API",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        requiresApiKey = true
    ),
    CUSTOM(
        id = 6,
        displayName = "自定义 LLM / API",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-3.5-turbo",
        requiresApiKey = true
    )
}

enum class TranslationDisplayMode(val id: Int, val displayName: String) {
    DUAL_LANGUAGE(0, "双语对照"),
    TARGET_ONLY(1, "仅显示译文")
}

enum class TranslationTargetLanguage(val id: Int, val displayName: String, val code: String) {
    ZH(0, "简体中文", "zh"),
    EN(1, "英语", "en"),
    JA(2, "日语", "ja"),
    KO(3, "韩语", "ko"),
    ZH_HANT(4, "繁体中文", "zh-TW"),
    FR(5, "法语", "fr"),
    DE(6, "德语", "de"),
    ES(7, "西班牙语", "es"),
    RU(8, "俄语", "ru")
}

data class TranslationConfig(
    val isEnabled: Boolean = false,
    val engine: TranslationEngine = TranslationEngine.BING,
    val displayMode: TranslationDisplayMode = TranslationDisplayMode.DUAL_LANGUAGE,
    val targetLanguage: TranslationTargetLanguage = TranslationTargetLanguage.ZH,
    val apiKey: String = "",
    val baseUrl: String = "",
    val modelName: String = ""
)

class SubtitleTranslator(private val context: Context) {

    companion object {
        /** 单次播放会话最多翻译多少条（超出后不再发起新请求，已翻译的照常显示） */
        const val MAX_SESSION_TRANSLATIONS = 600

        /** 预读翻译的前瞻窗口（秒）与单轮条数上限 */
        private const val PREFETCH_LOOKAHEAD_MS = 60_000L
        private const val PREFETCH_MAX_ITEMS = 20
    }

    var config by mutableStateOf(TranslationConfig())
    var statusMessage by mutableStateOf("字幕翻译就绪")
    var isTranslating by mutableStateOf(false)

    // Translation cache: key = "$targetLangCode:$sourceText" -> translated text
    private val translationCache = ConcurrentHashMap<String, String>()

    // v127：翻译结果磁盘缓存。原先只有内存缓存，换个视频或重启应用就要把同样的
    // 句子重翻一遍——既费流量/API 额度，也拖慢首屏。这里按 TSV 落盘：
    // 每行 "key<TAB>译文"，key 已含目标语言前缀，因此换语言不会串味。
    // 放 filesDir（而非 cacheDir）是因为系统可能清理 cache，而翻译结果值得留存。
    private val diskCacheFile by lazy { java.io.File(context.filesDir, "translation_cache.tsv") }
    private val diskWriteLock = Any()

    /** 磁盘缓存在 scope 就绪后异步加载（scope 声明在下方，故此处不做前向引用） */
    private fun startDiskCacheLoad() {
        scope.launch { loadDiskCache() }
    }

    private fun loadDiskCache() {
        try {
            if (!diskCacheFile.exists()) return
            var loaded = 0
            diskCacheFile.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@forEachLine
                val key = line.substring(0, tab)
                val value = unescapeCache(line.substring(tab + 1))
                if (key.isNotBlank() && value.isNotBlank()) {
                    translationCache[key] = value
                    loaded++
                }
            }
            Log.i("SubtitleTranslator", "翻译缓存加载完成：$loaded 条")
        } catch (e: Exception) {
            Log.w("SubtitleTranslator", "翻译缓存加载失败：${e.message}")
        }
    }

    /** 追加一条到磁盘缓存（TSV；译文里的换行/制表符转义后写入） */
    private fun appendDiskCache(key: String, value: String) {
        try {
            synchronized(diskWriteLock) {
                // 体积保护：超过 4MB 就整体重写（去重后的最新内容）
                if (diskCacheFile.exists() && diskCacheFile.length() > 4L * 1024 * 1024) {
                    rewriteDiskCache()
                    return
                }
                diskCacheFile.appendText("$key\t${escapeCache(value)}\n", Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.w("SubtitleTranslator", "翻译缓存写入失败：${e.message}")
        }
    }

    private fun rewriteDiskCache() {
        try {
            val sb = StringBuilder()
            for ((k, v) in translationCache) {
                if (k.isBlank() || v.isBlank()) continue
                sb.append(k).append('\t').append(escapeCache(v)).append('\n')
            }
            diskCacheFile.writeText(sb.toString(), Charsets.UTF_8)
            Log.i("SubtitleTranslator", "翻译缓存已重写（${translationCache.size} 条）")
        } catch (e: Exception) {
            Log.w("SubtitleTranslator", "翻译缓存重写失败：${e.message}")
        }
    }

    private fun escapeCache(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")

    private fun unescapeCache(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    else -> { sb.append(c); i++ }
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    // In-flight request deduplication: key = "$targetLangCode:$sourceText" -> listeners.
    // Multiple calls for the same text while a request is in flight share a single HTTP request.
    private val pendingTranslations = ConcurrentHashMap<String, MutableList<(String) -> Unit>>()

    // v84：并发从 2 提升到 4（LLM API 无免费限流顾虑；Bing 失败会自动重试）
    // 允许几个并发请求，使慢行不再阻塞整条字幕队列。
    private val translationSemaphore = Semaphore(4)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var batchTranslationJob: Job? = null

    /** v127：预读翻译任务（游标移动时取消重建） */
    private var prefetchTranslationJob: Job? = null

    /**
     * v127e：翻译用量控制，防止把免费端点/API 额度一次性用光。
     *
     * 现实问题是两条路一起放大用量：整片批量翻译（一条不落）+ 预读翻译（每 90 秒
     * 窗口再来一轮）。这里做三层约束：
     *  1. [MAX_SESSION_TRANSLATIONS]：单次播放会话的翻译总条数上限，达到即停止新增请求；
     *  2. 预读窗口收敛为 [PREFETCH_LOOKAHEAD_MS]，单轮条数上限 [PREFETCH_MAX_ITEMS]；
     *  3. 命中内存/磁盘缓存的条目不计入用量，也不会发请求。
     */
    private val sessionTranslated = java.util.concurrent.atomic.AtomicInteger(0)
    private val maxSessionTranslations = MAX_SESSION_TRANSLATIONS

    /** 已翻译条数（供 UI 展示用量） */
    val sessionUsage: Int get() = sessionTranslated.get()

    /** 是否已触及本次会话的翻译上限 */
    val isSessionLimitReached: Boolean get() = sessionTranslated.get() >= maxSessionTranslations

    init {
        // scope 已就绪，异步加载磁盘缓存（文件可能上千行，不能阻塞构造）
        startDiskCacheLoad()
    }

    fun getActiveBaseUrl(): String {
        return if (config.baseUrl.isNotBlank()) config.baseUrl else config.engine.defaultBaseUrl
    }

    fun getActiveModel(): String {
        return if (config.modelName.isNotBlank()) config.modelName else config.engine.defaultModel
    }

    /**
     * Translates text and returns a display-ready string (formatted by displayMode).
     *
     * Multi-line subtitle text is translated line by line so the translated block
     * keeps the same number of lines as the original, and results appear
     * progressively: each finished line updates the display immediately while the
     * remaining lines still show the source text.
     */
    fun translateOrOriginal(text: String, onTranslated: ((String) -> Unit)? = null): String {
        if (!config.isEnabled || text.isBlank()) return text

        val lines = text.split("\n")
        if (lines.size > 1) {
            return translateMultiLine(lines, onTranslated)
        }
        return translateSingleLine(text, onTranslated)
    }

    private fun translateSingleLine(text: String, onTranslated: ((String) -> Unit)? = null): String {
        val targetLang = config.targetLanguage.code
        val cacheKey = "$targetLang:$text"

        val cached = translationCache[cacheKey]
        if (cached != null) {
            return formatOutput(text, cached)
        }

        // Deduplicate: if a request for this exact text is already in flight, only
        // register the listener and return the original text without starting a new request.
        synchronized(pendingTranslations) {
            val existing = pendingTranslations[cacheKey]
            if (existing != null) {
                onTranslated?.let { existing.add(it) }
                return text
            }
            pendingTranslations[cacheKey] = mutableListOf<((String) -> Unit)>().also { list ->
                onTranslated?.let { list.add(it) }
            }
        }

        // Trigger asynchronous translation with automatic retries on failure.
        // The pending entry stays registered during the retries so concurrent calls
        // for the same text keep sharing this single attempt.
        scope.launch {
            var translatedText = ""
            for (attempt in 1..3) {
                translatedText = fetchTranslation(text, targetLang)
                if (translatedText.isNotBlank()) break
                if (attempt < 3) {
                    delay(1200L * attempt) // backoff: 1.2s, 3.6s
                }
            }
            val listeners = synchronized(pendingTranslations) { pendingTranslations.remove(cacheKey) }
            if (translatedText.isNotBlank()) {
                translationCache[cacheKey] = translatedText
            }
            val output = if (translatedText.isNotBlank()) formatOutput(text, translatedText) else text
            withContext(Dispatchers.Main) {
                listeners?.forEach { it(output) }
            }
        }

        // Return original text in the meantime
        return text
    }

    /**
     * Multi-line progressive translation. Each source line is translated on its own
     * (raw translation, no formatting), and every finished line immediately triggers
     * a callback with the current combined result — translated lines plus source
     * lines for those still in flight.
     */
    private fun translateMultiLine(
        lines: List<String>,
        onTranslated: ((String) -> Unit)?
    ): String {
        val results = arrayOfNulls<String>(lines.size)
        val lock = Any()

        fun combined(): String {
            return if (config.displayMode == TranslationDisplayMode.TARGET_ONLY) {
                results.mapIndexed { i, r -> r ?: lines[i] }.joinToString("\n")
            } else {
                lines.joinToString("\n") + "\n" +
                    results.mapIndexed { i, r -> r ?: lines[i] }.joinToString("\n")
            }
        }

        lines.forEachIndexed { i, line ->
            if (line.isBlank()) {
                synchronized(lock) { results[i] = "" }
                return@forEachIndexed
            }
            val raw = translateLineRaw(line) { t ->
                synchronized(lock) { results[i] = t }
                onTranslated?.invoke(combined())
            }
            synchronized(lock) {
                // Cache hits are filled synchronously; async completions overwrite later.
                if (results[i] == null) results[i] = raw
            }
            onTranslated?.invoke(combined())
        }

        return combined()
    }

    /**
     * Translates a single line and returns the RAW translation (no displayMode
     * formatting). Callers composing multi-line output must not receive formatted
     * rows, otherwise the bilingual mode would nest line-by-line pairs.
     */
    private fun translateLineRaw(line: String, onRaw: ((String) -> Unit)?): String {
        val targetLang = config.targetLanguage.code
        val cacheKey = "$targetLang:$line"

        val cached = translationCache[cacheKey]
        if (cached != null) return cached

        synchronized(pendingTranslations) {
            val existing = pendingTranslations[cacheKey]
            if (existing != null) {
                onRaw?.let { existing.add(it) }
                return line
            }
            pendingTranslations[cacheKey] = mutableListOf<((String) -> Unit)>().also { list ->
                onRaw?.let { list.add(it) }
            }
        }

        scope.launch {
            var translated = ""
            for (attempt in 1..3) {
                translated = fetchTranslation(line, targetLang)
                if (translated.isNotBlank()) break
                if (attempt < 3) {
                    delay(1200L * attempt)
                }
            }
            val listeners = synchronized(pendingTranslations) { pendingTranslations.remove(cacheKey) }
            if (translated.isNotBlank()) {
                translationCache[cacheKey] = translated
            }
            withContext(Dispatchers.Main) {
                listeners?.forEach { it(translated.ifBlank { line }) }
            }
        }

        return line
    }

    /**
     * Batch translate file cues in background
     */
    /**
     * v127：**预读翻译** —— 提前把播放游标前方即将显示的字幕翻好。
     *
     * 与实时字幕同样的思路：字幕是边播边生成的，若等"该显示这一条了"才去翻译，
     * 网络往返（几百毫秒~数秒）会让译文明显迟于原文出现。
     * 这里在播放游标前方 [lookaheadMs] 的窗口内提前翻译，显示时直接命中
     * [translationCache]，表现为译本与原文同时出现。
     *
     * 与 [translateCuesBatch] 的区别：
     * - batch 是"整片从头翻到尾"，适合导出；预读只翻游标附近，避免做无用功
     * - 游标大幅移动（seek）会取消上一轮预读并重建，不会把旧位置翻完
     * - 已缓存（含显示路径刚翻过的）条目不重复请求
     *
     * @param maxItems 单轮最多翻译条数，防止瞬间打出上百个请求
     */
    fun pretranslateAhead(
        cues: List<SubtitleCue>,
        cursorMs: Long,
        lookaheadMs: Long = PREFETCH_LOOKAHEAD_MS,
        maxItems: Int = PREFETCH_MAX_ITEMS
    ) {
        if (!config.isEnabled || cues.isEmpty()) return
        if (isSessionLimitReached) return   // 用量已达上限：只显示已有译文
        val targetLang = config.targetLanguage.code

        // 只取游标前方窗口内、且尚未翻译的文本（去重后保持时间顺序）
        val todo = cues.asSequence()
            .filter { it.startTimeMs >= cursorMs - 5_000L && it.startTimeMs <= cursorMs + lookaheadMs }
            .map { it.text.trim() }
            .filter { it.isNotBlank() && !translationCache.containsKey("$targetLang:$it") }
            .distinct()
            .take(maxItems)
            .toList()

        if (todo.isEmpty()) return

        prefetchTranslationJob?.cancel()
        prefetchTranslationJob = scope.launch {
            for (text in todo) {
                val key = "$targetLang:$text"
                if (translationCache.containsKey(key)) continue
                try {
                    val translated = fetchTranslation(text, targetLang)
                    if (translated.isNotBlank()) translationCache[key] = translated
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("SubtitleTranslator", "预读翻译失败：${e.message}")
                }
                if (!isActive) break
            }
        }
    }

    fun translateCuesBatch(cues: List<SubtitleCue>, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        if (!config.isEnabled || cues.isEmpty()) return

        batchTranslationJob?.cancel()
        batchTranslationJob = scope.launch {
            isTranslating = true
            val total = cues.size
            val targetLang = config.targetLanguage.code
            var doneCount = 0

            withContext(Dispatchers.Main) {
                statusMessage = "开始批量翻译字幕文件 (${total} 条)..."
            }

            for (i in cues.indices) {
                val cue = cues[i]
                val text = cue.text.trim()
                if (text.isBlank()) continue

                val cacheKey = "$targetLang:$text"
                if (isSessionLimitReached) {
                    withContext(Dispatchers.Main) {
                        isTranslating = false
                        statusMessage = "已达到本次会话翻译上限（$maxSessionTranslations 条），已停止翻译"
                    }
                    return@launch
                }
                if (!translationCache.containsKey(cacheKey)) {
                    val translated = fetchTranslation(text, targetLang)
                    if (translated.isNotBlank()) {
                        translationCache[cacheKey] = translated
                    }
                    // v84：去掉 delay(50) 限速（并发已由 Semaphore(4) 控制）
                }

                doneCount++
                if (doneCount % 5 == 0 || doneCount == total) {
                    val count = doneCount
                    withContext(Dispatchers.Main) {
                        statusMessage = "字幕翻译进度: $count/$total"
                        onProgress(count, total)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                isTranslating = false
                statusMessage = "字幕全片翻译完成 (${total} 条)"
            }
        }
    }

    /**
     * Formats final display string according to displayMode (Dual-Language or Target-Only)
     */
    fun formatOutput(originalText: String, translatedText: String): String {
        if (!config.isEnabled || translatedText.isBlank()) return originalText

        return when (config.displayMode) {
            TranslationDisplayMode.TARGET_ONLY -> translatedText
            TranslationDisplayMode.DUAL_LANGUAGE -> "$originalText\n$translatedText"
        }
    }

    /**
     * Translates input text using the selected engine (Bing free endpoint or LLM API).
     * Requests are serialized by a mutex to stay within free-tier rate limits.
     */
    private suspend fun fetchTranslation(text: String, targetLangCode: String): String {
        val result = translationSemaphore.withPermit {
            try {
                if (config.engine == TranslationEngine.BING) {
                    translateViaBing(text, targetLangCode)
                } else {
                    translateViaOpenAiApi(text, targetLangCode)
                }
            } catch (e: Exception) {
                Log.e("SubtitleTranslator", "Translation failed for engine ${config.engine}", e)
                ""
            }
        }
        // v127：所有翻译路径的统一出口——内存缓存 + 磁盘缓存都在这里落一次，
        // 调用方不必各自处理（display 路径、预读路径、批量路径共用）。
        if (result.isNotBlank()) {
            val key = "$targetLangCode:$text"
            translationCache[key] = result
            appendDiskCache(key, result)
            sessionTranslated.incrementAndGet()
        }
        return result
    }

    // Cached Bing web-endpoint config (IG, IID, key, token) fetched from the
    // translator page. The token expires hourly, so the cache is short-lived.
    private data class BingConfig(
        val ig: String,
        val iid: String,
        val key: String,
        val token: String,
        val fetchedAt: Long
    )

    private var bingConfig: BingConfig? = null
    private val bingUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /**
     * Free Bing web translation endpoint (no API key required).
     * Mirrors the ttranslatev3 endpoint used by the browser translator:
     * the request body must carry the page's `key` and `token`
     * (params_AbusePreventionHelper), otherwise the API returns 205.
     */
    private suspend fun translateViaBing(text: String, targetLangCode: String): String {
        val toLang = when (targetLangCode) {
            "zh" -> "zh-Hans"
            "zh-TW" -> "zh-Hant"
            else -> targetLangCode
        }

        return withContext(Dispatchers.IO) {
            var config = getBingConfig()
            var result = config?.let { bingRequest(it, text, toLang) }
            if (result == null) {
                // 205 / HTTP error: the config (token/IG) expired — refresh and retry once
                bingConfig = null
                config = getBingConfig()
                result = config?.let { bingRequest(it, text, toLang) }
            }
            result ?: ""
        }
    }

    private fun getBingConfig(): BingConfig? {
        bingConfig?.let { c ->
            if (System.currentTimeMillis() - c.fetchedAt < 50 * 60 * 1000L) {
                return c
            }
        }
        bingConfig = fetchBingConfig() ?: return null
        return bingConfig
    }

    private fun fetchBingConfig(): BingConfig? {
        return try {
            val request = Request.Builder()
                .url("https://cn.bing.com/translator")
                .addHeader("User-Agent", bingUserAgent)
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val html = resp.body?.string() ?: return null
                val ig = Regex("""IG:"([^"]+)"""").find(html)?.groupValues?.get(1)
                val iid = Regex("""data-iid="([^"]+)"""").find(html)?.groupValues?.get(1)
                val params = Regex("""params_AbusePreventionHelper\s?=\s?(\[[^\]]+\])""")
                    .find(html)?.groupValues?.get(1)
                if (ig == null || iid == null || params == null) return null
                val arr = try { JSONArray(params) } catch (e: Exception) { return null }
                if (arr.length() < 2) return null
                BingConfig(
                    ig = ig,
                    iid = iid,
                    key = arr.getString(0),
                    token = arr.getString(1),
                    fetchedAt = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            Log.e("SubtitleTranslator", "Fetch Bing config failed", e)
            null
        }
    }

    /** Returns the translated text, or null when the request needs a config refresh. */
    private fun bingRequest(config: BingConfig, text: String, toLang: String): String? {
        return try {
            val formBody = FormBody.Builder()
                .add("fromLang", "auto-detect")
                .add("text", text)
                .add("to", toLang)
                .add("token", config.token)
                .add("key", config.key)
                .build()
            val request = Request.Builder()
                .url("https://cn.bing.com/ttranslatev3?isVertical=1&&IG=${config.ig}&IID=${config.iid}")
                .addHeader("User-Agent", bingUserAgent)
                .addHeader("Referer", "https://cn.bing.com/translator")
                .post(formBody)
                .build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyStr = resp.body?.string() ?: return null
                if (bodyStr.contains("statusCode")) {
                    // {"statusCode":205} etc. → config expired, refresh needed
                    return null
                }
                parseBingResponse(bodyStr)
            }
        } catch (e: Exception) {
            Log.e("SubtitleTranslator", "Bing request failed", e)
            null
        }
    }

    private fun parseBingResponse(body: String): String {
        return try {
            val root = JSONArray(body)
            if (root.length() > 0) {
                val translations = root.getJSONObject(0).optJSONArray("translations")
                if (translations != null && translations.length() > 0) {
                    translations.getJSONObject(0).optString("text", "").trim()
                } else ""
            } else ""
        } catch (e: Exception) {
            Log.e("SubtitleTranslator", "Bing response parse failed: $body", e)
            ""
        }
    }

    /**
     * Standard OpenAI Compatible API Translation Client
     * Compatible with DeepSeek, Qwen DashScope, GLM-4, MIMO, OpenAI, Custom endpoints.
     */
    private suspend fun translateViaOpenAiApi(text: String, targetLangCode: String): String {
        val apiKey = config.apiKey.trim()
        if (config.engine.requiresApiKey && apiKey.isBlank()) {
            withContext(Dispatchers.Main) {
                statusMessage = "请先设置 ${config.engine.displayName} 的 API Key"
            }
            return ""
        }

        var baseUrl = getActiveBaseUrl().trim().trimEnd('/')
        if (!baseUrl.endsWith("/chat/completions")) {
            baseUrl = "$baseUrl/chat/completions"
        }

        val model = getActiveModel()
        val langName = config.targetLanguage.displayName

        val systemPrompt = "You are an expert subtitle translator. Translate the given video subtitle text accurately into $langName. Output ONLY the translated text without explanations, quotes, or markdown formatting."

        val jsonBody = JSONObject().apply {
            put("model", model)
            put("temperature", 0.2)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    // OpenAI protocol expects "content"; the old "value" key caused HTTP 400
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", text)
                })
            })
        }

        val request = Request.Builder()
            .url(baseUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("SubtitleTranslator", "API HTTP Error: ${response.code} ${response.message}")
                        withContext(Dispatchers.Main) {
                            statusMessage = "API 错误: HTTP ${response.code}"
                        }
                        return@withContext ""
                    }

                    val bodyStr = response.body?.string() ?: return@withContext ""
                    val root = JSONObject(bodyStr)

                    val choices = root.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val choice = choices.getJSONObject(0)
                        val message = choice.optJSONObject("message")
                        val content = message?.optString("content")?.trim() ?: ""
                        content
                    } else {
                        ""
                    }
                }
            } catch (e: Exception) {
                Log.e("SubtitleTranslator", "HTTP Request Exception", e)
                withContext(Dispatchers.Main) {
                    statusMessage = "翻译失败: ${e.localizedMessage}"
                }
                ""
            }
        }
    }

    fun clearCache() {
        translationCache.clear()
        statusMessage = "翻译缓存已清空"
    }

    /**
     * Cancels any in-flight batch translation job. Called when the UI is disposed
     * to avoid background work continuing after the screen is gone.
     */
    fun cancelAll() {
        batchTranslationJob?.cancel()
        batchTranslationJob = null
    }
}
