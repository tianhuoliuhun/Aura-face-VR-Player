package com.example.vr

import com.example.R
import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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
    val displayNameResId: Int,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val requiresApiKey: Boolean
) {
    BING(
        id = 0,
        displayNameResId = R.string.engine_bing,
        defaultBaseUrl = "https://cn.bing.com/ttranslatev3",
        defaultModel = "bing-translate",
        requiresApiKey = false
    ),
    DEEPSEEK(
        id = 1,
        displayNameResId = R.string.engine_deepseek,
        defaultBaseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        requiresApiKey = true
    ),
    QWEN(
        id = 2,
        displayNameResId = R.string.engine_qwen,
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen-turbo",
        requiresApiKey = true
    ),
    GLM(
        id = 3,
        displayNameResId = R.string.engine_glm,
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
        defaultModel = "glm-4-flash",
        requiresApiKey = true
    ),
    MIMO(
        id = 4,
        displayNameResId = R.string.engine_mimo,
        defaultBaseUrl = "https://api.minimax.chat/v1",
        defaultModel = "abab6.5g-chat",
        requiresApiKey = true
    ),
    OPENAI(
        id = 5,
        displayNameResId = R.string.engine_openai,
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        requiresApiKey = true
    ),
    CUSTOM(
        id = 6,
        displayNameResId = R.string.engine_custom,
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-3.5-turbo",
        requiresApiKey = true
    ),
    // 免费翻译：MyMemory 公共 API（无需 key，沙箱内实测可用）。
    MYMEMORY(
        id = 7,
        displayNameResId = R.string.engine_mymemory,
        defaultBaseUrl = "https://api.mymemory.translated.net",
        defaultModel = "",
        requiresApiKey = false
    ),
    // 免费翻译：LibreTranslate（自托管或公共实例，标准 /translate 协议）。
    // 沙箱内公共实例 TLS 被拦截、无法实测，真机端按标准协议自测。
    LIBRETRANSLATE(
        id = 8,
        displayNameResId = R.string.engine_libretranslate,
        defaultBaseUrl = "https://libretranslate.com",
        defaultModel = "",
        requiresApiKey = false
    )
}

enum class TranslationDisplayMode(val id: Int, val displayNameResId: Int) {
    DUAL_LANGUAGE(0, R.string.subtitle_mode_bilingual),
    TARGET_ONLY(1, R.string.subtitle_mode_translated_only)
}

enum class TranslationTargetLanguage(
    val id: Int,
    val displayName: String,
    val code: String,
    val nameResId: Int
) {
    ZH(0, "简体中文", "zh", R.string.lang_zh),
    EN(1, "英语", "en", R.string.lang_en),
    JA(2, "日语", "ja", R.string.lang_ja),
    KO(3, "韩语", "ko", R.string.lang_ko),
    ZH_HANT(4, "繁体中文", "zh-TW", R.string.lang_zh_hant),
    FR(5, "法语", "fr", R.string.lang_fr),
    DE(6, "德语", "de", R.string.lang_de),
    ES(7, "西班牙语", "es", R.string.lang_es),
    RU(8, "俄语", "ru", R.string.lang_ru)
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

        // ===== 翻译缓存容量（v2.0.150 上调）=====

        /**
         * 磁盘缓存的「压缩阈值」：文件超过它才做一次整体重写（去重 + 裁剪）。
         *
         * 原值 4MB **偏小**：稍长的剧集就会把文件顶到阈值之上，而原实现重写后
         * 文件大小 ≈ 内存缓存全量、往往仍高于阈值 → **此后每次翻译都会触发一次
         * 全量重写**（每次几 MB 写盘），既慢又费电。现提升到 32MB，并配合下面的
         * 软上限让重写后文件明显回落，恢复正常「追加一行」的 O(1) 写入。
         */
        private const val DISK_CACHE_COMPACT_THRESHOLD_BYTES = 32L * 1024 * 1024

        /**
         * 缓存条目软上限（同时决定压缩后保留的条数）。
         * 约 20 万条 × 平均 ~120 字节 ≈ 24MB（低于 32MB 阈值）。
         */
        private const val CACHE_SOFT_MAX_ENTRIES = 200_000

        /** 启动时最多加载多少行（超大文件保护，避免首屏被 IO 拖住） */
        private const val CACHE_MAX_LOAD_LINES = 600_000

        // ===== v2.0.153：按语言分文件 + 失效策略（LRU / TTL / 版本）=====

        /** 缓存文件结构版本（写入文件头）。不匹配则整份作废（改名为 .stale 留档）。 */
        private const val CACHE_FORMAT_VERSION = 2

        /**
         * 缓存**内容**版本：译文口径发生不兼容变化时递增，旧缓存整体作废。
         * 用途：修正了系统性误译、或换了质量明显不同的模型后，不让旧译文继续被命中。
         */
        private const val CACHE_CONTENT_VERSION = 1

        /** TTL：超过这么久没被命中的条目，在压缩时淘汰（180 天）。 */
        private const val CACHE_TTL_MS = 180L * 24 * 60 * 60 * 1000

        /** 命中元数据攒够这么多条就落盘一次，避免「每命中一次就写一次盘」。 */
        private const val META_FLUSH_DIRTY_THRESHOLD = 500

        /** 缓存目录与文件命名：`filesDir/translation/cache_<lang>.tsv` */
        private const val CACHE_DIR_NAME = "translation"
        private const val CACHE_FILE_PREFIX = "cache_"
        private const val CACHE_FILE_SUFFIX = ".tsv"

        /** 旧版单文件缓存（迁移源）：`filesDir/translation_cache.tsv` */
        private const val LEGACY_CACHE_FILE = "translation_cache.tsv"

        /** 预读翻译的前瞻窗口（秒）与单轮条数上限 */
        private const val PREFETCH_LOOKAHEAD_MS = 60_000L
        private const val PREFETCH_MAX_ITEMS = 20

        /**
         * MyMemory 限速常量（v2.0.144）。
         * usagelimits.php：免费匿名 5000 字符/天（提交邮箱可到 50000），按字符计量，
         * 且会按调用频率限流；超限时**不返回 HTTP 错误**，而是把警告文案塞进
         * responseData.translatedText。这里用「最小请求间隔降速」+「错误文案识别」+
         * 「配额冷却」三层兜底，避免并发连打导致持续报错。
         */
        private const val MYMEMORY_MIN_INTERVAL_MS = 1_500L
        /** MyMemory 单次请求 q 的上限为 500 字节 */
        private const val MYMEMORY_MAX_QUERY_BYTES = 500
        /** 触发配额/限流后的冷却时长 */
        private const val MYMEMORY_COOLDOWN_MS = 10 * 60 * 1000L
    }

    var config by mutableStateOf(TranslationConfig())
    var statusMessage by mutableStateOf(context.getString(R.string.subtitle_translate_ready))
    var isTranslating by mutableStateOf(false)

    // Translation cache: key = "$targetLangCode:$sourceText" -> translated text
    private val translationCache = ConcurrentHashMap<String, String>()

    /**
     * v2.0.153：命中元数据 `key -> [lastUsedMs, hitCount]`，供 **LRU 淘汰**与统计使用。
     *
     * 刻意放进**独立**的 map（而不是改造 translationCache 的 value 类型）：
     * 这样全项目 10 余处 `translationCache[...]` 读写点一行都不用动，改动面最小、风险最低。
     */
    private val cacheMeta = ConcurrentHashMap<String, LongArray>()

    /** 自上次落盘以来被"命中"更新过的元数据条数（攒够阈值才重写文件） */
    private val metaDirty = java.util.concurrent.atomic.AtomicInteger(0)

    /** 缓存命中 / 未命中计数（本次进程内累计，供统计面板展示命中率） */
    private val cacheHits = java.util.concurrent.atomic.AtomicInteger(0)
    private val cacheMisses = java.util.concurrent.atomic.AtomicInteger(0)

    /** 当前已加载进内存的目标语言（懒加载：切换语言时卸载旧语言、加载新语言） */
    @Volatile
    private var loadedLangTag: String? = null
    private val langLoadLock = Any()

    /**
     * v2.0.153：翻译缓存改为**按目标语言分文件**落盘。
     *
     * 目录 `filesDir/translation/`，文件 `cache_<lang>.tsv`（如 `cache_zh.tsv`）。
     * 好处：① 启动只加载当前语言，首屏更快；② 可单独查看/清空某语言词库；
     * ③ 单文件体积更小，压缩重写更快。放 filesDir（而非 cacheDir）是因为
     * 系统可能清理 cache，而翻译结果值得留存。
     */
    private val cacheDir by lazy { java.io.File(context.filesDir, CACHE_DIR_NAME) }

    private fun cacheFileFor(langTag: String) =
        java.io.File(cacheDir, "$CACHE_FILE_PREFIX${langTag.replace('/', '_')}$CACHE_FILE_SUFFIX")

    /** v2.0.153 之前的单文件缓存（迁移后改名保留，不直接删） */
    private val legacyCacheFile by lazy { java.io.File(context.filesDir, LEGACY_CACHE_FILE) }

    private val diskWriteLock = Any()

    /** 文件头：`#v2|1`（结构版本 | 内容版本） */
    private fun cacheHeader() = "#v$CACHE_FORMAT_VERSION|$CACHE_CONTENT_VERSION\n"

    /** 磁盘缓存在 scope 就绪后异步加载（scope 声明在下方，故此处不做前向引用） */
    private fun startDiskCacheLoad() {
        val lang = config.targetLanguage.code
        loadedLangTag = lang
        scope.launch {
            migrateLegacyCacheIfNeeded()
            loadDiskCache(lang)
        }
    }

    /** 解析结果（v2.0.153：key/value + LRU 元数据） */
    private data class ParsedCacheEntry(
        val key: String,
        val value: String,
        val lastUsedMs: Long,
        val hitCount: Long
    )

    /**
     * 解析一行缓存。兼容两代格式：
     *  - v2：`key<TAB>译文<TAB>lastUsedMs<TAB>hitCount`
     *  - v1：`key<TAB>译文`（无时间信息 → lastUsed 记作"现在"，避免升级后老词条立刻被 TTL 清掉）
     */
    private fun parseCacheLine(line: String, now: Long): ParsedCacheEntry? {
        if (line.isBlank() || line.startsWith("#")) return null
        val parts = line.split('\t')
        if (parts.size < 2) return null
        val rawKey = parts[0]
        val value = unescapeCache(parts[1])
        if (rawKey.isBlank() || value.isBlank()) return null
        return ParsedCacheEntry(
            key = normalizeCacheKey(rawKey),
            value = value,
            lastUsedMs = parts.getOrNull(2)?.toLongOrNull() ?: now,
            hitCount = parts.getOrNull(3)?.toLongOrNull() ?: 0L
        )
    }

    private fun putEntry(e: ParsedCacheEntry) {
        translationCache[e.key] = e.value
        cacheMeta[e.key] = longArrayOf(e.lastUsedMs, e.hitCount)
    }

    /** 文件头版本不匹配：改名 `.stale` 留档后作废（不删，便于排查） */
    private fun markStale(file: java.io.File) {
        try {
            val stale = java.io.File(file.parentFile, file.name + ".stale")
            if (stale.exists()) stale.delete()
            file.renameTo(stale)
            Log.w("SubtitleTranslator", "缓存版本不匹配，已作废：${file.name} → ${stale.name}")
        } catch (e: Exception) {
            Log.w("SubtitleTranslator", "缓存作废失败：${e.message}")
        }
    }

    private fun loadDiskCache(langTag: String) {
        val file = cacheFileFor(langTag)
        if (!file.exists()) return
        val now = System.currentTimeMillis()
        val fileMb = file.length() / 1048576.0
        var loaded = 0
        var lines = 0
        var headerCompatible = true
        try {
            file.bufferedReader(Charsets.UTF_8).use { reader ->
                // 首行可能是文件头（#v2|1），也可能是老格式的数据行
                val first = reader.readLine()
                if (first != null && first.startsWith("#")) {
                    val seg = first.substring(1).split('|')
                    val fmt = seg.getOrNull(0)?.removePrefix("v")
                    val content = seg.getOrNull(1)
                    if (fmt != CACHE_FORMAT_VERSION.toString() || content != CACHE_CONTENT_VERSION.toString()) {
                        headerCompatible = false
                    }
                } else if (first != null) {
                    lines++
                    parseCacheLine(first, now)?.let { putEntry(it); loaded++ }
                }
                if (!headerCompatible) return@use
                // 用 readLine 循环而非 forEachLine：便于在超大文件上**提前收手**
                while (loaded < CACHE_MAX_LOAD_LINES) {
                    val line = reader.readLine() ?: break
                    lines++
                    parseCacheLine(line, now)?.let { putEntry(it); loaded++ }
                }
            }
        } catch (e: Exception) {
            Log.w("SubtitleTranslator", "翻译缓存加载失败：${e.message}")
            return
        }
        if (!headerCompatible) {
            markStale(file)
            return
        }
        Log.i(
            "SubtitleTranslator",
            "翻译缓存加载完成 [$langTag]：$loaded 条（读取 $lines 行 / 文件 ${"%.1f".format(fileMb)}MB）"
        )
    }

    /**
     * v2.0.153：把旧版单文件缓存 `translation_cache.tsv` 按目标语言前缀**拆分**到
     * `translation/cache_<lang>.tsv`，完成后把旧文件改名 `.migrated` 留档（不删）。
     *
     * 幂等：① 旧文件不存在 → 直接返回；② 迁移中途失败 → 下次启动重做（重复行按 key 覆盖，无害）。
     * 先改名为 `.migrating` 再拆分，避免迁移中被并发重复触发。
     */
    private fun migrateLegacyCacheIfNeeded() {
        if (!legacyCacheFile.exists()) return
        try {
            val migrating = java.io.File(legacyCacheFile.parentFile, LEGACY_CACHE_FILE + ".migrating")
            if (migrating.exists()) migrating.delete()
            if (!legacyCacheFile.renameTo(migrating)) {
                Log.w("SubtitleTranslator", "旧缓存迁移：改名失败，跳过")
                return
            }
            cacheDir.mkdirs()
            val buckets = HashMap<String, StringBuilder>()
            var total = 0
            migrating.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val rawKey = line.substring(0, tab)
                    val sep = rawKey.indexOf(':')
                    if (sep <= 0) continue
                    val lang = rawKey.substring(0, sep)
                    val value = line.substring(tab + 1)
                    if (value.isBlank()) continue
                    buckets.getOrPut(lang) { StringBuilder() }
                        .append(normalizeCacheKey(rawKey)).append('\t').append(value).append('\n')
                    total++
                }
            }
            var written = 0
            for ((lang, sb) in buckets) {
                val target = cacheFileFor(lang)
                if (target.exists()) {
                    target.appendText(sb.toString(), Charsets.UTF_8)
                } else {
                    target.writeText(cacheHeader() + sb.toString(), Charsets.UTF_8)
                }
                written++
            }
            val done = java.io.File(legacyCacheFile.parentFile, LEGACY_CACHE_FILE + ".migrated")
            if (done.exists()) done.delete()
            migrating.renameTo(done)
            Log.i("SubtitleTranslator", "旧缓存已迁移：$total 条 → $written 个语言文件（原文件改名 .migrated）")
        } catch (e: Exception) {
            Log.w("SubtitleTranslator", "旧缓存迁移失败：${e.message}")
        }
    }

    /**
     * 追加一条到磁盘缓存（TSV 4 列；译文里的换行/制表符转义后写入）。
     *
     * v2.0.153：**按 key 里的目标语言前缀路由到对应文件** —— 即使正在切换语言，
     * 也不会把译文写进错误的文件。
     */
    private fun appendDiskCache(key: String, value: String) {
        try {
            val langTag = key.substringBefore(':')
            val file = cacheFileFor(langTag)
            synchronized(diskWriteLock) {
                cacheDir.mkdirs()
                // 体积保护：超过压缩阈值才整体重写（去重 + TTL/LRU 淘汰），其余情况只追加一行
                if (file.exists() && file.length() > DISK_CACHE_COMPACT_THRESHOLD_BYTES) {
                    rewriteDiskCache(langTag)
                    return
                }
                if (!file.exists()) {
                    file.writeText(cacheHeader(), Charsets.UTF_8)
                }
                file.appendText(
                    "$key\t${escapeCache(value)}\t${System.currentTimeMillis()}\t0\n",
                    Charsets.UTF_8
                )
            }
        } catch (e: Exception) {
            Log.w("SubtitleTranslator", "翻译缓存写入失败：${e.message}")
        }
    }

    /**
     * 重写某个语言的缓存文件：**去重 + TTL 过期 + LRU 淘汰到软上限**。
     *
     * v2.0.150 修的是「重写后文件仍大于阈值 → 此后每次写入都全量重写」；
     * v2.0.153 在此基础上把淘汰策略由「随机砍」换成**真 LRU**：
     *  1. 先剔除超过 [CACHE_TTL_MS] 未被命中的条目；
     *  2. 仍超 [CACHE_SOFT_MAX_ENTRIES] 时，按 **(命中次数升序, 最近使用时间升序)** 淘汰
     *     —— 优先丢弃「用得少且久未用」的，而不是按 HashMap 迭代序随机砍（旧行为可能
     *     把最常用的句子淘汰掉）。
     */
    private fun rewriteDiskCache(langTag: String) {
        try {
            val now = System.currentTimeMillis()
            val prefix = "$langTag:"

            // 1) TTL 过期
            var expired = 0
            for (k in translationCache.keys.filter { it.startsWith(prefix) }) {
                val meta = cacheMeta[k] ?: continue
                if (now - meta[0] > CACHE_TTL_MS) {
                    translationCache.remove(k)
                    cacheMeta.remove(k)
                    expired++
                }
            }

            // 2) 仍超软上限 → LRU（低频 + 久未用优先淘汰）
            val remain = translationCache.keys.filter { it.startsWith(prefix) }
            var evicted = 0
            if (remain.size > CACHE_SOFT_MAX_ENTRIES) {
                val victims = remain
                    .sortedWith(
                        compareBy(
                            { cacheMeta[it]?.get(1) ?: 0L },
                            { cacheMeta[it]?.get(0) ?: 0L }
                        )
                    )
                    .take(remain.size - CACHE_SOFT_MAX_ENTRIES)
                for (k in victims) {
                    translationCache.remove(k)
                    cacheMeta.remove(k)
                    evicted++
                }
            }

            // 3) 写文件（只写该语言的条目）
            val file = cacheFileFor(langTag)
            var count = 0
            synchronized(diskWriteLock) {
                cacheDir.mkdirs()
                val sb = StringBuilder(2048)
                sb.append(cacheHeader())
                for ((k, v) in translationCache) {
                    if (!k.startsWith(prefix)) continue
                    if (k.isBlank() || v.isBlank()) continue
                    val meta = cacheMeta[k]
                    sb.append(k).append('\t').append(escapeCache(v)).append('\t')
                        .append(meta?.get(0) ?: now).append('\t').append(meta?.get(1) ?: 0L)
                        .append('\n')
                    count++
                }
                file.writeText(sb.toString(), Charsets.UTF_8)
            }
            Log.i(
                "SubtitleTranslator",
                "缓存已重写 [$langTag]：$count 条 / ${file.length() / 1024}KB（TTL 过期 $expired / LRU 淘汰 $evicted）"
            )
        } catch (e: Exception) {
            Log.w("SubtitleTranslator", "翻译缓存重写失败：${e.message}")
        }
    }

    // ===== v2.0.153：命中记录 / 语言切换 / 统计 / 清理 =====

    /**
     * 命中缓存：更新 LRU 元数据（最近使用时间 + 命中次数）。
     *
     * 元数据**攒够 [META_FLUSH_DIRTY_THRESHOLD] 条才落盘**（后台重写当前语言文件），
     * 否则每翻一句字幕都要写一次盘 —— 那正是 v2.0.150 修掉的问题。
     */
    private fun touchCache(key: String) {
        val now = System.currentTimeMillis()
        val meta = cacheMeta.getOrPut(key) { longArrayOf(now, 0L) }
        meta[0] = now
        meta[1] = meta[1] + 1
        cacheHits.incrementAndGet()
        if (metaDirty.incrementAndGet() >= META_FLUSH_DIRTY_THRESHOLD) {
            metaDirty.set(0)
            loadedLangTag?.let { lang -> scope.launch { rewriteDiskCache(lang) } }
        }
    }

    /** 未命中缓存（即将发请求）：仅计数，供命中率统计 */
    private fun noteCacheMiss() {
        cacheMisses.incrementAndGet()
    }

    /**
     * 确保内存里装的是**当前目标语言**的缓存（语言切换时懒加载）。
     *
     * 切走时先把旧语言的元数据落盘，再清空内存、加载新语言 —— 否则内存里会越攒越多语言，
     * 「启动只加载当前语言」也就失去意义。
     */
    private fun ensureLanguageLoaded(langTag: String) {
        if (loadedLangTag == langTag) return
        synchronized(langLoadLock) {
            if (loadedLangTag == langTag) return
            val prev = loadedLangTag
            loadedLangTag = langTag
            scope.launch {
                try {
                    if (prev != null) rewriteDiskCache(prev)
                    translationCache.clear()
                    cacheMeta.clear()
                    metaDirty.set(0)
                    loadDiskCache(langTag)
                } catch (e: Exception) {
                    Log.w("SubtitleTranslator", "切换语言缓存失败：${e.message}")
                }
            }
        }
    }

    /** 单语言缓存统计（供设置面板展示） */
    data class LangCacheStat(val langTag: String, val entries: Int, val bytes: Long)

    val cacheHitCount: Int get() = cacheHits.get()
    val cacheMissCount: Int get() = cacheMisses.get()

    /** 命中率 0f~1f；一次都没查过时为 0 */
    val cacheHitRate: Float
        get() {
            val h = cacheHits.get()
            val m = cacheMisses.get()
            val t = h + m
            return if (t == 0) 0f else h.toFloat() / t
        }

    /** 当前语言已加载进内存的条目数 */
    val currentLangEntryCount: Int
        get() = loadedLangTag?.let { p -> translationCache.keys.count { it.startsWith("$p:") } } ?: 0

    /** 当前语言标签（zh / zh-TW / en …） */
    val currentLangTag: String get() = loadedLangTag ?: config.targetLanguage.code

    /** 扫描缓存目录，统计各语言的条目数与文件体积（**IO 操作，请后台线程调用**） */
    fun scanCacheStats(): List<LangCacheStat> {
        if (!cacheDir.exists()) return emptyList()
        val list = cacheDir.listFiles { f ->
            f.isFile && f.name.startsWith(CACHE_FILE_PREFIX) && f.name.endsWith(CACHE_FILE_SUFFIX)
        } ?: return emptyList()
        return list.map { f ->
            val lang = f.name.removePrefix(CACHE_FILE_PREFIX).removeSuffix(CACHE_FILE_SUFFIX)
            LangCacheStat(lang, countCacheLines(f), f.length())
        }.sortedByDescending { it.entries }
    }

    /** 数一个缓存文件里的有效条目数（跳过文件头） */
    private fun countCacheLines(f: java.io.File): Int {
        var n = 0
        try {
            f.bufferedReader(Charsets.UTF_8).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isBlank() || line.startsWith("#")) continue
                    n++
                }
            }
        } catch (e: Exception) {
            Log.w("SubtitleTranslator", "统计缓存条目失败：${e.message}")
        }
        return n
    }

    /** 清空**指定语言**的缓存（内存 + 磁盘；`.migrated` / `.stale` 归档文件不动） */
    fun clearCacheFor(langTag: String) {
        val prefix = "$langTag:"
        for (k in translationCache.keys.filter { it.startsWith(prefix) }) {
            translationCache.remove(k)
            cacheMeta.remove(k)
        }
        metaDirty.set(0)
        try {
            cacheFileFor(langTag).delete()
        } catch (e: Exception) {
            Log.w("SubtitleTranslator", "删除缓存文件失败：${e.message}")
        }
        Log.i("SubtitleTranslator", "已清空 [$langTag] 的翻译缓存")
    }

    /** 清空**全部语言**的缓存（内存 + 磁盘） */
    fun clearAllCaches() {
        translationCache.clear()
        cacheMeta.clear()
        metaDirty.set(0)
        try {
            cacheDir.listFiles()?.forEach { f ->
                if (f.name.startsWith(CACHE_FILE_PREFIX) && f.name.endsWith(CACHE_FILE_SUFFIX)) f.delete()
            }
        } catch (e: Exception) {
            Log.w("SubtitleTranslator", "清空缓存目录失败：${e.message}")
        }
        Log.i("SubtitleTranslator", "已清空全部翻译缓存")
    }

    // ===== 缓存 key 归一化（v2.0.151：提升命中率）=====

    /**
     * 生成缓存 key：`目标语言 + 归一化后的原文`。
     *
     * 归一化只做「折叠空白 + 去首尾空白」。字幕里同一句话常因换行/多空格差异被当成两条
     * （`"Hello  world"` vs `"Hello world"`），折叠后命中同一条缓存 —— **等效扩大词库、提升命中率**。
     *
     * ⚠️ **刻意不做**大小写折叠与标点归一：那会把语义不同的句子混到同一个 key
     * （例如问句/陈述句、`12:30` 与 `1230`），返回不合适译文的代价比多翻一次更大。
     */
    private fun makeCacheKey(targetLang: String, text: String): String =
        normalizeCacheKey(targetLang + ":" + text)

    /**
     * 对**已拼好的 key** 归一化（形如 `zh:文本`）。
     *
     * 单独抽出是因为加载旧磁盘缓存时也要走一遍：老文件里的 key 未归一化，
     * 直接入库会导致升级后全部命中不到；归一化后入库即可**自动迁移并去重**。
     */
    private fun normalizeCacheKey(key: String): String {
        val sep = key.indexOf(':')
        if (sep <= 0) return key
        return key.substring(0, sep + 1) + normalizeCacheText(key.substring(sep + 1))
    }

    /** 折叠连续空白（含全角空格 U+3000）为单个半角空格，并去掉首尾空白 */
    private fun normalizeCacheText(text: String): String {
        val sb = StringBuilder(text.length)
        var pendingSpace = false
        for (c in text) {
            if (c.isWhitespace() || c == '\u3000') {
                if (sb.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) {
                    sb.append(' ')
                    pendingSpace = false
                }
                sb.append(c)
            }
        }
        return sb.toString()
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

    // v2.0.144：MyMemory 专用节流器。与 translationSemaphore 不同，这里要求
    // 「串行 + 最小间隔」——MyMemory 按调用频率限流，并发连打最容易触发报错。
    private val myMemoryPacer = Mutex()
    private var myMemoryLastCallMs = 0L
    /** 配额/限流触发后的冷却截止时间（ms）；冷却期内直接跳过，不再打接口 */
    private var myMemoryCooldownUntilMs = 0L

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
        // v2.0.153：目标语言变了就懒加载对应语言的缓存文件（旧的先落盘再切）
        ensureLanguageLoaded(config.targetLanguage.code)

        val lines = text.split("\n")
        if (lines.size > 1) {
            return translateMultiLine(lines, onTranslated)
        }
        return translateSingleLine(text, onTranslated)
    }

    private fun translateSingleLine(text: String, onTranslated: ((String) -> Unit)? = null): String {
        val targetLang = config.targetLanguage.code
        val cacheKey = makeCacheKey(targetLang, text)

        val cached = translationCache[cacheKey]
        if (cached != null) {
            touchCache(cacheKey)
            return formatOutput(text, cached)
        }
        noteCacheMiss()

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
        val cacheKey = makeCacheKey(targetLang, line)

        val cached = translationCache[cacheKey]
        if (cached != null) {
            touchCache(cacheKey)
            return cached
        }
        noteCacheMiss()

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
            .filter { it.isNotBlank() && !translationCache.containsKey(makeCacheKey(targetLang, it)) }
            .distinct()
            .take(maxItems)
            .toList()

        if (todo.isEmpty()) return

        prefetchTranslationJob?.cancel()
        prefetchTranslationJob = scope.launch {
            for (text in todo) {
                val key = makeCacheKey(targetLang, text)
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
                statusMessage = context.getString(R.string.subtitle_batch_start, total)
            }

            for (i in cues.indices) {
                val cue = cues[i]
                val text = cue.text.trim()
                if (text.isBlank()) continue

                val cacheKey = makeCacheKey(targetLang, text)
                if (isSessionLimitReached) {
                    withContext(Dispatchers.Main) {
                        isTranslating = false
                        statusMessage = context.getString(R.string.subtitle_session_limit, maxSessionTranslations)
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
                        statusMessage = context.getString(R.string.subtitle_translate_progress, count, total)
                        onProgress(count, total)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                isTranslating = false
                statusMessage = context.getString(R.string.subtitle_batch_done, total)
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
                when (config.engine) {
                    TranslationEngine.BING -> translateViaBing(text, targetLangCode)
                    TranslationEngine.MYMEMORY -> translateViaMyMemory(text, targetLangCode)
                    TranslationEngine.LIBRETRANSLATE -> translateViaLibreTranslate(text, targetLangCode)
                    else -> translateViaOpenAiApi(text, targetLangCode)
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
                statusMessage = context.getString(R.string.translate_please_set_api_key, context.getString(config.engine.displayNameResId))
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
                            statusMessage = context.getString(R.string.subtitle_api_error, response.code)
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
                    statusMessage = context.getString(R.string.subtitle_translate_failed, e.localizedMessage ?: "")
                }
                ""
            }
        }
    }

    /**
     * MyMemory 免费翻译 API（无需 key，沙箱内实测可用）。
     * GET https://api.mymemory.translated.net/get?q=<text>&langpair=<src>|<tgt>
     * - src 支持 "Autodetect"（由 MyMemory 自动识别来源语言）
     * - 响应体：{ responseData: { translatedText: "..." } }
     *
     * v2.0.144 限速（依据 usagelimits.php）：免费匿名仅 5000 字符/天、按字符计量、
     * 且会按调用频率限流；**超限时不返回 HTTP 错误**，而是把警告文案写进
     * translatedText。因此这里做三层兜底：
     *  ① 串行 + 最小间隔（[MYMEMORY_MIN_INTERVAL_MS]）主动降速；
     *  ② 识别错误文案（配额/限流/参数）判为失败，不写缓存；
     *  ③ 命中配额类错误后进入冷却期（[MYMEMORY_COOLDOWN_MS]），期内直接跳过不再打接口。
     */
    private suspend fun translateViaMyMemory(text: String, targetLangCode: String): String {
        // ③ 冷却期内直接跳过，避免持续连打（配额耗尽后需要等一段时间才恢复）
        val nowMs = System.currentTimeMillis()
        if (nowMs < myMemoryCooldownUntilMs) {
            val leftMin = ((myMemoryCooldownUntilMs - nowMs) / 60000L).coerceAtLeast(1L)
            Log.w("SubtitleTranslator", "MyMemory 冷却中，约 ${leftMin} 分钟后恢复")
            withContext(Dispatchers.Main) {
                statusMessage = context.getString(R.string.translate_mymemory_cooldown, leftMin)
            }
            return ""
        }
        // 单次请求 500 字节上限：超长文本直接跳过，避免必然报错
        val byteLen = text.toByteArray(Charsets.UTF_8).size
        if (byteLen > MYMEMORY_MAX_QUERY_BYTES) {
            Log.w("SubtitleTranslator", "MyMemory 跳过超长文本（$byteLen 字节 > $MYMEMORY_MAX_QUERY_BYTES）")
            return ""
        }
        // ① 串行 + 最小间隔：主动降低请求速度，避开调用频率限制
        myMemoryPacer.withLock {
            val since = System.currentTimeMillis() - myMemoryLastCallMs
            val wait = MYMEMORY_MIN_INTERVAL_MS - since
            if (wait > 0) delay(wait)
            myMemoryLastCallMs = System.currentTimeMillis()
        }

        val target = mapMyMemoryLang(targetLangCode)
        val langPair = "Autodetect|$target"
        val base = getActiveBaseUrl().trim().trimEnd('/')
        val url = "$base/get?q=${Uri.encode(text)}&langpair=${Uri.encode(langPair)}"

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("SubtitleTranslator", "MyMemory HTTP Error: ${response.code} ${response.message}")
                        withContext(Dispatchers.Main) {
                            statusMessage = context.getString(R.string.subtitle_api_error, response.code)
                        }
                        return@withContext ""
                    }
                    val bodyStr = response.body?.string() ?: return@withContext ""
                    val root = JSONObject(bodyStr)
                    val translated = root.optJSONObject("responseData")
                        ?.optString("translatedText")?.trim().orEmpty()
                    // ② MyMemory 用 HTTP 200 + 文案表达错误（配额/限流/参数非法）
                    if (translated.isBlank() || isMyMemoryErrorText(translated)) {
                        if (isMyMemoryQuotaText(translated)) {
                            myMemoryCooldownUntilMs = System.currentTimeMillis() + MYMEMORY_COOLDOWN_MS
                            Log.w("SubtitleTranslator", "MyMemory 配额/限流触发，冷却 ${MYMEMORY_COOLDOWN_MS / 60000} 分钟")
                        }
                        val detail = translated.ifBlank { root.optString("responseMessage", "") }
                        Log.w("SubtitleTranslator", "MyMemory 返回异常，视为失败：$detail")
                        return@withContext ""
                    }
                    translated
                }
            } catch (e: Exception) {
                Log.e("SubtitleTranslator", "MyMemory request exception", e)
                withContext(Dispatchers.Main) {
                    statusMessage = context.getString(R.string.subtitle_translate_failed, e.localizedMessage ?: "")
                }
                ""
            }
        }
    }

    /** MyMemory 把错误当正文返回时的错误文案识别（配额/限流/参数/服务不可用） */
    private fun isMyMemoryErrorText(s: String): Boolean {
        val u = s.trim().uppercase()
        if (u.isEmpty()) return false
        val prefixBad = u.startsWith("PLEASE") || u.startsWith("INVALID") ||
            u.startsWith("NO QUERY") || u.startsWith("YOU USED") ||
            u.startsWith("MYMEMORY WARNING") ||
            u.startsWith("TRANSLATION SERVICE TEMPORARILY UNAVAILABLE") ||
            u.startsWith("QUERY LENGTH LIMIT")
        return prefixBad || u.contains("ALL AVAILABLE FREE TRANSLATIONS") ||
            u.contains("QUERY LENGTH LIMIT EXCEEDED")
    }

    /** 是否为「配额耗尽 / 服务暂不可用」类错误——命中则触发冷却 */
    private fun isMyMemoryQuotaText(s: String): Boolean {
        val u = s.trim().uppercase()
        return u.contains("YOU USED ALL") || u.contains("ALL AVAILABLE FREE TRANSLATIONS") ||
            u.contains("TEMPORARILY UNAVAILABLE") || u.contains("MYMEMORY WARNING")
    }

    /**
     * LibreTranslate 翻译（自托管或公共实例），标准 /translate 协议。
     * POST form: q / source / target / format，可选 api_key（填了就带）。
     * 响应体：{ translatedText: "..." }
     * 注意：沙箱内公共实例 TLS 被拦截、无法实测；按官方标准协议实现，真机端自测。
     */
    private suspend fun translateViaLibreTranslate(text: String, targetLangCode: String): String {
        val apiKey = config.apiKey.trim()
        val base = getActiveBaseUrl().trim().trimEnd('/')
        val url = if (base.endsWith("/translate")) base else "$base/translate"
        val target = mapLibreLang(targetLangCode)

        val formBuilder = FormBody.Builder()
            .add("q", text)
            .add("source", "auto")
            .add("target", target)
            .add("format", "text")
        if (apiKey.isNotBlank()) formBuilder.add("api_key", apiKey)

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .post(formBuilder.build())
            .build()

        return withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("SubtitleTranslator", "LibreTranslate HTTP Error: ${response.code} ${response.message}")
                        withContext(Dispatchers.Main) {
                            statusMessage = context.getString(R.string.subtitle_api_error, response.code)
                        }
                        return@withContext ""
                    }
                    val bodyStr = response.body?.string() ?: return@withContext ""
                    val root = JSONObject(bodyStr)
                    root.optString("translatedText").trim()
                }
            } catch (e: Exception) {
                Log.e("SubtitleTranslator", "LibreTranslate request exception", e)
                withContext(Dispatchers.Main) {
                    statusMessage = context.getString(R.string.subtitle_translate_failed, e.localizedMessage ?: "")
                }
                ""
            }
        }
    }

    /** MyMemory 目标语言码映射：简中→zh-CN，繁中→zh-TW，其余直接用 code */
    private fun mapMyMemoryLang(code: String): String = when (code) {
        "zh" -> "zh-CN"
        "zh-TW" -> "zh-TW"
        else -> code
    }

    /** LibreTranslate 目标语言码映射（与 MyMemory 基本一致） */
    private fun mapLibreLang(code: String): String = when (code) {
        "zh" -> "zh"
        "zh-TW" -> "zh-TW"
        else -> code
    }

    /**
     * 清空**当前语言**的缓存（内存 + 磁盘）。
     *
     * v2.0.153：原实现只 clear() 了内存，磁盘文件原封不动 —— 重启后缓存"复活"，
     * 用户以为清了其实没清。
     */
    fun clearCache() {
        clearCacheFor(currentLangTag)
        statusMessage = context.getString(R.string.subtitle_cache_cleared)
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
