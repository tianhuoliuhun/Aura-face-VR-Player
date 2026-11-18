package com.example.vr

import android.util.Log

/**
 * v2.8.3：**网页字幕抓取** —— 把网页播放器用的外挂字幕（`.vtt` / `.srt` / `.ass`）拿下来，
 * 解析成 [SubtitleCue] 后交给主播放器挂载、进而可导出。
 *
 * ## 为什么单独抓一份，而不是让播放器直接读那个 URL
 * 两个原因：
 *  1. **播放器读不到它**。网页的字幕是 `<track src>` 或 MSE 里的文本轨，播放器的内核
 *     （Exo / MPV）只看容器内的字幕轨，不会去请求页面声明的 `<track>`。所以"网页有字幕"
 *     与"我们放出来有字幕"是两件事 —— 这也是此前「网页播放看不到字幕」的原因。
 *  2. **要能导出**。导出的前提是内存里有完整的 cue 列表（[SubtitleExporter] 吃的是
 *     `List<SubtitleCue>`），流式跟随播放进度去要字幕做不到"导出整片"。
 *
 * ## 为什么不复用 WebView 已经下载过的那份
 * `shouldInterceptRequest` 返回 null 表示"放行"，WebView 自己会去下载 —— 那个 body
 * **拿不到**（要拿就必须返回一个包装过的 `WebResourceResponse`，等于把整条渲染链路
 * 接管过来，代价远大于收益）。所以这里在**后台另发一次**同样的请求：
 * 字幕文件通常几十 KB ~ 几 MB，多一次请求完全可接受。
 *
 * ## 隐私
 * 这里拿到的 URL 与直链同级敏感（带 `?token=`），**只存内存、不写日志、不进最近播放**；
 * 日志里只打主机名与条数，不打完整地址。
 */
object WebSubtitleSniffer {
    private const val TAG = "WebSubtitleSniffer"

    /** 字幕文件上限。1 小时 VTT 约 200KB~1MB，4MB 足够且能兜住 ASS 全特效字幕。 */
    private const val MAX_SUBTITLE_BYTES = 4 * 1024 * 1024

    /** 单个 HLS 字幕分片上限（一片通常几 KB） */
    private const val MAX_SEGMENT_BYTES = 2 * 1024 * 1024

    /**
     * HLS 字幕合并的**时长预算** —— 覆盖到这么久就停。
     *
     * 🔴 这里曾经用「最多 240 片」，是个口径错误：片数与片长成反比，
     * 2 秒/片的清单 240 片只有 **8 分钟**，10 秒/片的清单有 40 分钟，
     * 而常见 6 秒/片的清单是 24 分钟 —— 同样的上限，覆盖时长差 5 倍且不可控。
     * 用户导出的是"这片的字幕"，唯一有意义的边界是**时间**，不是片数。
     *
     * 取 6 小时：覆盖任何正常影片，同时兜住"清单其实是无限循环"的病态情况。
     */
    private const val MAX_HLS_COVERAGE_MS = 6L * 60 * 60 * 1000

    /**
     * 片数硬上限（防病态清单，不是正常片长的约束）。
     *
     * 6 小时按 2 秒一片是 10800 片 —— 那种清单基本是异常/循环清单，
     * 且逐片请求会打满 CDN。4000 片按 2 秒算仍有 2.2 小时，够用。
     */
    private const val MAX_HLS_SEGMENTS = 4000

    /** 分片下载并发度。太高会被 CDN 限速（反而更慢）；8 路实测比 4 路快约 1.7 倍。 */
    private const val SEGMENT_PARALLEL = 8

    /** 单片失败重试次数（CDN 偶发 429/5xx，重试一次能救回绝大多数） */
    private const val SEGMENT_RETRY = 1

    /**
     * 失败片占比超过这个值就判定"这份字幕不可用"（返回 null）。
     *
     * 3 分之 1：再高的话字幕会大段大段地缺，用户拿到的是坏文件而不是"缺一点"。
     * 低于这个比例仍然返回，但会在 [Candidate.failedSegments] 里如实标注。
     */
    private const val MAX_FAILED_RATIO = 1.0 / 3.0

    /**
     * 整份字幕合并的**总时限**（毫秒）。
     *
     * 为什么要它：单片超时是 8s connect + 12s read（[StreamProbe]），
     * CDN 直接挂掉时每片都要等满 20s —— 4000 片 / 8 并发 × 20s ≈ **2.8 小时**，
     * 后台线程会一直挂着、面板永远显示"合并中"。
     *
     * 90 秒：正常情况（1200 片 / 8 并发 / 每片 150ms ≈ 22 秒）用不满，
     * 只有真出问题才触发；到点后把已拿到的交出来，缺口如实标记。
     */
    private const val MERGE_BUDGET_MS = 90_000L

    /** 识别为字幕的扩展名 */
    private val SUBTITLE_EXT = listOf(".vtt", ".srt", ".ass", ".ssa", ".sub", ".sbv", ".lrc")

    data class Candidate(
        /** 字幕源地址（含签名参数，仅内存留存） */
        val url: String,
        /** 展示名：文件名，或 HLS 轨的 `中文 (zh)` */
        val label: String,
        val cues: List<SubtitleCue>,
        /**
         * 这份字幕**覆盖到的最后时间点**（毫秒）。0 = 未知（非 HLS 的单文件字幕）。
         *
         * 用途：HLS 合并被上限截断时，必须让用户看见"只到 1:24:00"——
         * 否则导出的 SRT 在后半段断掉，用户只会以为文件坏了。
         */
        val coveredMs: Long = 0L,
        /** HLS 清单没合完（被 [MAX_HLS_COVERAGE_MS] / [MAX_HLS_SEGMENTS] 截断） */
        val truncated: Boolean = false,
        /** 下载失败被跳过的分片数（>0 说明字幕有空洞，但没到大面积缺失的程度） */
        val failedSegments: Int = 0,
    ) {
        /** `1:24:00` 形式的覆盖进度；未知时 null */
        val coverageLabel: String?
            get() {
                if (coveredMs <= 0L) return null
                val totalSec = coveredMs / 1000
                val h = totalSec / 3600
                val m = (totalSec % 3600) / 60
                val s = totalSec % 60
                return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
                else String.format(java.util.Locale.US, "%02d:%02d", m, s)
            }
    }

    /**
     * URL 是否指向字幕文件。
     *
     * 判据两类（与 [VideoSniffer] 同一思路：不按站点维护规则表）：
     *  1. 路径扩展名是 `.vtt` / `.srt` / `.ass` / `.ssa` / …
     *  2. 查询参数形如 `?format=vtt` / `&f=srt` / `&type=ass`（不少站点走同一个
     *     `/subtitle?id=1&format=vtt` 接口，路径上没有扩展名）
     */
    fun looksLikeSubtitle(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val path = lower.substringBefore('?').substringBefore('#')
        if (SUBTITLE_EXT.any { path.endsWith(it) }) return true
        val query = lower.substringAfter('?', "")
        if (query.isEmpty()) return false
        return Regex("""(?:^|&)(?:format|fmt|type|f|ext|sub_format)=(vtt|srt|ass|ssa|sub)\b""")
            .containsMatchIn(query)
    }

    /** 展示名：优先文件名，退化到主机名 —— **绝不显示完整 URL**（含 token）。 */
    fun labelOf(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val name = path.substringAfterLast('/')
        if (name.isNotBlank() && name.contains('.')) return name
        return runCatching { java.net.URI(url).host }.getOrNull() ?: "字幕"
    }

    /**
     * 该 URL 是否像**分片**（文件名主干里带数字：`seg0.vtt` / `zh_0001.vtt` / `0.vtt`）。
     *
     * 用途：HLS 字幕播放时会以"一片一个请求"的节奏连续请求，若把每片都当成独立字幕去抓，
     * 一部片子会产生几百个候选（每个只含几秒内容）、白白吃掉流量。
     * 对分片按**目录**去重，只抓第一片；真正的语言文件（`zh.vtt` / `en.vtt`）不含数字，
     * 仍会各自抓一份，不会因为去重而漏掉别的语言。
     */
    fun looksLikeSegment(url: String): Boolean {
        val name = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        if (!name.contains('.')) return false
        return name.substringBeforeLast('.').any { it.isDigit() }
    }

    /**
     * 下载并解析一个字幕源。**阻塞**，调用方必须在后台线程。
     *
     * @param headers 请求头（UA / Referer / Cookie）—— 字幕同样受防盗链保护
     * @param labelOverride HLS `#EXT-X-MEDIA` 给的展示名（如 `中文 (zh)`）
     * @param onProgress HLS 逐片合并的进度回调 `(已完成, 总数)`。**在下载线程上回调**，
     *   UI 层需自行切回主线程。非 HLS 字幕不会回调（一个请求就完事）。
     * @return 解析不出任何 cue 时返回 null（不做"空字幕"候选，避免用户选了却没反应）
     */
    fun fetch(
        url: String,
        headers: Map<String, String>,
        labelOverride: String? = null,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): Candidate? {
        val label = labelOverride?.takeIf { it.isNotBlank() } ?: labelOf(url)
        val blob = StreamProbe.fetchBlob(url, headers, MAX_SUBTITLE_BYTES) ?: run {
            Log.w(TAG, "字幕下载失败：$label")
            return null
        }
        val content = SubtitleParser.decodeBytes(blob.bytes ?: return null)

        // HLS 的 SUBTITLES 轨指向的又是一层 m3u8（WebVTT 分片清单）。
        // 🔴 这里**不能只取第一片**：分片内 cue 用的是分片局部时间（从 0 开始），
        //    只抓第一片导出的 SRT 会在前几分钟就断掉，用户会以为是文件坏了。
        //    必须逐片取回、按 X-TIMESTAMP-MAP / #EXTINF 累计平移后合并（见 fetchHls）。
        if (content.trimStart().startsWith("#EXTM3U")) {
            return fetchHls(label, blob.finalUrl, content, headers, onProgress)
        }

        val cues = SubtitleParser.parse(content)
        if (cues.isEmpty()) {
            Log.w(TAG, "字幕解析出 0 条（不是字幕或格式不支持）：$label")
            return null
        }
        Log.i(TAG, "字幕就绪：$label，${cues.size} 条")
        return Candidate(url, label, cues)
    }

    /** [planCoverage] 的结果：合并多少片、覆盖多久、是否被截断 */
    internal data class Coverage(val take: Int, val coveredMs: Long, val truncated: Boolean)

    /**
     * 按时长预算 + 片数硬上限，决定一份清单要合并**前多少片**。
     *
     * 抽成纯函数是为了能单测 —— 这里正是「长片只出前 24 分钟字幕」那个 bug 的修复点：
     * 旧代码用固定 240 片，而片长在 2~10 秒之间浮动，导致覆盖时长完全不可控。
     *
     * @param isVod 非 VOD（直播/事件）只取一片：没有完整时间轴，也无从"导出全片"
     */
    internal fun planCoverage(segs: List<HlsPlaylist.Segment>, isVod: Boolean): Coverage {
        if (segs.isEmpty()) return Coverage(0, 0L, false)
        if (!isVod) return Coverage(1, segs[0].durationMs.coerceAtLeast(0L), false)
        var n = 0
        var ms = 0L
        while (n < segs.size && n < MAX_HLS_SEGMENTS && ms < MAX_HLS_COVERAGE_MS) {
            ms += segs[n].durationMs.coerceAtLeast(0L)
            n++
        }
        return Coverage(n, ms, n < segs.size)
    }

    /**
     * HLS 字幕清单 → 逐片下载、按时间平移、合并成一条完整字幕。
     *
     * ## 时间轴平移为什么必须做
     * RFC 8216 §3.5：WebVTT 分片里的 cue 时间戳是**该分片内的局部时间**，
     * 真实位置靠 `X-TIMESTAMP-MAP=LOCAL:0,MPEGTS:<90kHz>` 映射到呈现时间轴。
     * 直接拼接文本会让每一片的字幕都堆在开头几秒（时间轴全错，且**不会报错**）。
     *
     * 平移量取值顺序：
     *  1. 该片自己的 `X-TIMESTAMP-MAP` → `MPEGTS/90 - LOCAL(ms)`（最准）；
     *  2. 没有 map 时用 `#EXTINF` 累计的片起点；
     *  3. 但若该片第一个 cue 已经落在累计起点之后，说明它本来就是全局时间 → 不再平移
     *     （否则会二次偏移，字幕整体后移一整段）。
     *
     * 合并范围由 [planCoverage] 决定；直播（无 `#EXT-X-ENDLIST`）只取一片。
     */
    private fun fetchHls(
        label: String,
        playlistUrl: String,
        playlistText: String,
        headers: Map<String, String>,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
    ): Candidate? {
        val segs = HlsPlaylist.parseSegments(playlistText, playlistUrl)
        if (segs.isEmpty()) {
            Log.w(TAG, "字幕清单里没有分片：$label")
            return null
        }
        val isVod = playlistText.contains("#EXT-X-ENDLIST", true) ||
            playlistText.contains("PLAYLIST-TYPE:VOD", true)

        // 🔴 按时长预算决定取多少片，不是按固定片数 —— 见 MAX_HLS_COVERAGE_MS 的注释
        val plan = planCoverage(segs, isVod)
        val take = plan.take
        if (take <= 0) {
            Log.w(TAG, "字幕清单里没有可用分片：$label")
            return null
        }

        // 每片的起始时刻（按 #EXTINF 累加）—— 下载前就要算好，供各线程平移用
        val starts = LongArray(take)
        var acc = 0L
        for (i in 0 until take) {
            starts[i] = acc
            acc += segs[i].durationMs.coerceAtLeast(0L)
        }
        val coveredMs = acc
        val truncated = plan.truncated
        if (truncated) {
            // 明确记下"合到哪"，而不是以前那种「按 6 秒/片估」的假数字
            Log.w(TAG, "字幕清单过长（${segs.size} 片），只合并 $take 片（覆盖 ${fmtMs(coveredMs)}）")
        }

        val parts = arrayOfNulls<List<SubtitleCue>>(take)
        val failed = java.util.concurrent.atomic.AtomicInteger(0)
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        // 进度节流：几千片逐片回调会把主线程打满，按 5% 一档报
        val step = (take / 20).coerceAtLeast(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(SEGMENT_PARALLEL)
        var timedOut = 0
        try {
            val futures = (0 until take).map { i ->
                pool.submit(
                    Runnable {
                        try {
                            val segText = fetchSegment(segs[i].uri, headers)
                                ?.let { SubtitleParser.decodeBytes(it) }
                            if (segText == null) {
                                failed.incrementAndGet()
                            } else {
                                // 该片解析出 0 条是**正常**的（静默段），不计失败
                                val cues = SubtitleParser.parse(segText)
                                if (cues.isNotEmpty()) {
                                    val mapShift = timestampMapShift(segText)
                                    val shift = mapShift
                                        ?: if (cues.first().startTimeMs >= starts[i]) 0L else starts[i]
                                    parts[i] = cues.map {
                                        SubtitleCue(0, it.startTimeMs + shift, it.endTimeMs + shift, it.text)
                                    }
                                }
                            }
                        } finally {
                            val d = done.incrementAndGet()
                            if (onProgress != null && (d == take || d % step == 0)) {
                                runCatching { onProgress(d, take) }
                            }
                        }
                    }
                )
            }

            // 总时限：CDN 挂掉时单片要等满 8s+12s 超时，几千片能挂一小时。
            // 到点就停止等待，把已拿到的部分交给用户（并如实标记缺口）。
            val deadline = System.nanoTime() + MERGE_BUDGET_MS * 1_000_000L
            for (f in futures) {
                val remainMs = (deadline - System.nanoTime()) / 1_000_000L
                if (remainMs <= 0L) {
                    f.cancel(true)
                    timedOut++
                    continue
                }
                // Future.get 提供 happens-before：等完成的片再读 parts，不会看到半写状态
                runCatching { f.get(remainMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
                    .onFailure { timedOut++ }
            }
        } finally {
            if (timedOut > 0) pool.shutdownNow() else pool.shutdown()
        }
        // 超时被取消的片同样计入缺口（它们确实没有内容）
        val failedCount = failed.get() + timedOut
        if (timedOut > 0) {
            Log.w(TAG, "字幕合并超时：$timedOut/$take 片未完成，按缺口处理（$label）")
        }

        val merged = parts.filterNotNull().flatten().sortedBy { it.startTimeMs }
        if (merged.isEmpty()) {
            Log.w(TAG, "HLS 字幕合并后为空：$label")
            return null
        }
        // 失败率过高就判定这份字幕不可用：大段缺失的 SRT 比没有更糟
        if (failedCount.toDouble() / take > MAX_FAILED_RATIO) {
            Log.w(TAG, "HLS 字幕失败过多（$failedCount/$take），放弃：$label")
            return null
        }
        Log.i(
            TAG,
            "HLS 字幕就绪：$label，${merged.size} 条（合并 $take/${segs.size} 片，" +
                "覆盖 ${fmtMs(coveredMs)}，失败 $failedCount" +
                (if (truncated) "，已截断" else "") + "）"
        )
        return Candidate(playlistUrl, label, merged, coveredMs, truncated, failedCount)
    }

    /**
     * 下载一片字幕，失败重试 [SEGMENT_RETRY] 次。     *
     * 重试是为了救回 CDN 偶发的 429 / 5xx（字幕分片小、请求密度高，最容易撞限速）。
     * 仍失败则返回 null，由调用方计入缺口。
     */
    private fun fetchSegment(uri: String, headers: Map<String, String>): ByteArray? {
        repeat(SEGMENT_RETRY + 1) { attempt ->
            StreamProbe.fetchBlob(uri, headers, MAX_SEGMENT_BYTES)?.bytes?.let { return it }
            if (attempt < SEGMENT_RETRY) {
                // 轻微退避：被限速时立刻重试大概率还是被拒
                runCatching { Thread.sleep(120L * (attempt + 1)) }
            }
        }
        return null
    }

    /** 毫秒 → `1:24:00` / `24:00`（仅日志用） */
    private fun fmtMs(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s % 60)
        else String.format(java.util.Locale.US, "%02d:%02d", m, s % 60)
    }

    /** `X-TIMESTAMP-MAP=LOCAL:hh:mm:ss.mmm,MPEGTS:900000` → 全局偏移毫秒。 */
    private val TS_MAP_REGEX = Regex(
        """X-TIMESTAMP-MAP=LOCAL:(\d+):(\d+):(\d+)\.(\d+),\s*MPEGTS:(\d+)""",
        RegexOption.IGNORE_CASE
    )

    private fun timestampMapShift(vtt: String): Long? {
        val m = TS_MAP_REGEX.find(vtt.take(4096)) ?: return null
        val g = m.groupValues
        val localMs = (g[1].toLong() * 3600 + g[2].toLong() * 60 + g[3].toLong()) * 1000 +
            g[4].padEnd(3, '0').take(3).toLong()
        // MPEGTS 是 90kHz 时钟
        val globalMs = g[5].toLong() / 90
        return (globalMs - localMs).coerceAtLeast(0L)
    }
}
