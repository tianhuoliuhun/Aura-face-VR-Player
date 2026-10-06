package com.example.vr

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 单条弹幕的运行态。
 *
 * @param text        文本
 * @param track       轨道下标（0 = 最上一行）
 * @param bornMs      入队时刻（SystemClock.elapsedRealtime）
 * @param widthPx     文本绘制宽度（由渲染层用 TextMeasurer 量出后回填；引擎只用它做轨道避让）
 */
data class DanmuItem(
    val text: String,
    val track: Int,
    var bornMs: Long,
    var widthPx: Float = 0f
) {
    /**
     * 当前水平位置（px，左边缘）。
     *
     * 从屏幕右缘 [screenW] 出发，以 [speedPxPerSec] 向左匀速移动；完全移出左缘（x + width < 0）
     * 即视为过期。**位置完全由时间推导**（不累加位移），这样：
     * - 帧率波动不会导致弹幕忽快忽慢
     * - 暂停/恢复只需平移 [bornMs]，不必逐条改状态
     */
    fun currentX(nowMs: Long, screenW: Float, speedPxPerSec: Int): Float {
        val elapsedSec = (nowMs - bornMs) / 1000f
        return screenW - elapsedSec * speedPxPerSec
    }

    /** 是否已完全离开屏幕（可回收） */
    fun isExpired(nowMs: Long, screenW: Float, speedPxPerSec: Int): Boolean {
        val x = currentX(nowMs, screenW, speedPxPerSec)
        return x + widthPx < 0f
    }
}

/**
 * AI 弹幕引擎（v2.3.1 / P4）
 *
 * 职责**只有三件事**，全部是纯计算、无 Android UI 依赖，因此可单测：
 * 1. **相似度去重** —— 视觉模型很容易在相邻两帧里给出几乎一样的弹幕
 * 2. **轨道分配** —— 决定新弹幕放第几行，并保证同一轨道上前后两条**不追尾**
 * 3. **生命周期** —— 过期回收、队列上限、暂停/恢复
 *
 * ## 为什么位置用「时间推导」而不是「每帧加位移」
 * 累加位移在掉帧时会明显变慢（少加几次），而弹幕是**时间轴上的匀速运动**，
 * 用 `(now - born) * speed` 直接算才是对的。这也让暂停恢复变成「平移 born」这一件事。
 *
 * ## 坐标系约定（与 GL 渲染同向）
 * `x` 为距屏幕**左边缘**的像素，向右为正；弹幕从右往左（x 递减）。
 * 渲染层需按此换算到 GL 的 NDC（左眼）；分屏时右眼再加 IPD 偏移。
 *
 * ⚠️ **不要**在引擎里持有 Bitmap / Context / View —— 保持它能被纯 JVM 单测。
 */
class DanmuEngine {

    /** 当前存活弹幕（按入队顺序，天然按 bornMs 递增） */
    private val items = mutableListOf<DanmuItem>()

    /** 最近若干条的文本，用于相似度去重（只存文本，代价极低） */
    private val recentTexts = ArrayDeque<String>()

    // ---- 可调运行参数（由调用方按 DanmuConfig 同步进来）----

    /** 滚动速度 px/s */
    var speedPxPerSec: Int = DanmuConfig.DEFAULT_SPEED_PX_PER_SEC
        set(value) {
            val clamped = value.coerceIn(MIN_SPEED, MAX_SPEED)
            val old = field
            if (old == clamped) return
            field = clamped
            // 变速时平移出生时间，让已有弹幕**不瞬移**。
            // 由 x = W - (now-born)/1000 * speed 可解出：
            //   born' = now - (now - born) * oldSpeed / newSpeed
            if (items.isNotEmpty()) {
                // ⚠️ nowProvider 是可变属性，不能依赖智能转换 → 先取到局部变量
                val provider = nowProvider
                if (provider != null) {
                    val now = provider.invoke()
                    for (item in items) {
                        val elapsed = now - item.bornMs
                        if (elapsed > 0) {
                            item.bornMs = now - (elapsed.toDouble() * old / clamped).toLong()
                        }
                    }
                }
            }
        }

    /** 最大轨道数 */
    var maxTracks: Int = DanmuConfig.DEFAULT_MAX_TRACKS

    /** 相似度去重阈值 0–100（越大越严格，越不容易被判为重复） */
    var dedupThresholdPercent: Int = DanmuConfig.DEFAULT_DEDUP_PERCENT

    /** 屏幕宽度（px）。用于计算位置与「是否已离开」。 */
    var screenWidthPx: Float = 1080f

    /** 轨道高度（px）。由渲染层按字号算好后同步。 */
    var trackHeightPx: Float = 0f

    /** 队列上限：防止模型高频返回时无限堆积（参考 DanmuAI 的 300） */
    var maxPending: Int = MAX_PENDING_DEFAULT

    /**
     * 时钟提供者。
     *
     * 默认用 `SystemClock.elapsedRealtime()`（单调时钟，不受系统时间调整影响）；
     * 注入后可在**单元测试**里完全控制时间推进，也让本类不硬依赖 Android 运行时。
     */
    var nowProvider: (() -> Long)? = null

    /** 取当前时刻（ms）；未注入时用 uptimeMillis（纯 JVM 下也能跑） */
    private fun now(): Long = nowProvider?.invoke() ?: android.os.SystemClock.elapsedRealtime()

    /** 当前存活条数 */
    val size: Int get() = items.size

    /** 只读快照（渲染层遍历用；不要修改返回的列表） */
    fun snapshot(): List<DanmuItem> = items

    /**
     * 批量入队新弹幕（来自视觉模型）。
     *
     * 对每条依次做：去重 → 分配轨道。分配不到轨道的**直接丢弃**
     * （宁可不显示，也不重叠成一团 —— 弹幕重叠比少几条难看得多）。
     *
     * ## ⚠️ 为什么同批要「错开出生时间」+「强制分散轨道」
     * 若一批全部用同一个 [nowMs]，它们在位置公式下**完全重合**（x 相同），
     * `pickTrack` 的间距判据恒为「太近」→ 只有第一条能进。
     * 但若只靠错开时间让它们先后挤进同一轨道，轨道上限的防护就形同虚设。
     * 因此**两者都要**：
     * - 用 [BATCH_STAGGER_MS] 错开出生时间（保证位置判据有意义）
     * - 用 `usedTracks` **禁止本批内共用轨道**（保证一批 N 条占 N 条轨道，视觉上是"同时飘出"）
     *
     * @return 实际入队条数
     */
    fun enqueue(texts: List<String>, nowMs: Long): Int {
        var added = 0
        val usedTracks = HashSet<Int>()

        for (raw in texts) {
            val text = raw.trim()
            if (text.isEmpty()) continue

            // ① 队列上限
            if (items.size >= maxPending) break

            // ② 相似度去重
            if (isDuplicate(text)) continue

            // ③ 错开出生时间后分配轨道（本批不共用轨道，见上方说明）
            val bornMs = nowMs + added * BATCH_STAGGER_MS
            val track = pickTrack(bornMs, usedTracks) ?: continue

            items.add(DanmuItem(text = text, track = track, bornMs = bornMs))
            usedTracks.add(track)
            recentTexts.addLast(text)
            while (recentTexts.size > DEDUP_WINDOW) recentTexts.removeFirst()
            added++
        }
        return added
    }

    /**
     * 回收已过期弹幕。渲染层每帧调用一次即可（O(n)，n 只有几十，无需优化）。
     */
    fun prune(nowMs: Long) {
        if (items.isEmpty()) return
        val it = items.iterator()
        while (it.hasNext()) {
            val item = it.next()
            if (item.isExpired(nowMs, screenWidthPx, speedPxPerSec)) it.remove()
        }
    }

    /** 清空全部弹幕（切换视频 / 关闭功能时调用） */
    fun clear() {
        items.clear()
        // ⚠️ 去重窗口**不清**：用户切个视频，模型很可能立刻又给出相似内容，
        //    保留窗口能继续压制，避免切集后立刻刷屏同样的话。
    }

    /** 彻底重置（连去重窗口一起清；退出功能时用） */
    fun reset() {
        items.clear()
        recentTexts.clear()
    }

    /**
     * 暂停/恢复：把已存活弹幕的出生时间整体平移，于是位置公式自然「冻结」。
     *
     * ⚠️ 原地修改 `bornMs`（不重建对象）—— 渲染层可能缓存了 [DanmuItem] 引用，
     *    重建会让它的引用失效。
     *
     * @param deltaMs 暂停时长（恢复时传入），>0
     */
    fun shiftBornTime(deltaMs: Long) {
        if (deltaMs <= 0) return
        for (item in items) {
            item.bornMs += deltaMs
        }
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 与最近窗口内的文本做相似度比较。
     * 用**归一化编辑距离**（1 - dist/maxLen），比 Levenshtein 原始值更好设阈值。
     */
    private fun isDuplicate(candidate: String): Boolean {
        if (recentTexts.isEmpty()) return false
        val norm = normalize(candidate)
        if (norm.isEmpty()) return true
        val threshold = dedupThresholdPercent.coerceIn(0, 100) / 100f
        for (prev in recentTexts) {
            val p = normalize(prev)
            if (p == norm) return true  // 完全相同，直接判重（省掉距离计算）
            val d = editDistance(norm, p)
            val maxLen = max(norm.length, p.length)
            if (maxLen == 0) continue
            val similarity = 1f - d.toFloat() / maxLen
            if (similarity >= threshold) return true
        }
        return false
    }

    /**
     * 归一化：去掉空白与标点，只留实义字符。
     *
     * 理由：模型常给出「哈哈哈」与「哈哈哈！」这种仅标点差异的重复项，
     * 按原串比会漏判。⚠️ 只去 ASCII 与常见中英文标点，不破坏 CJK 字符本身。
     */
    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            if (ch.isWhitespace()) continue
            if (isPunctuation(ch)) continue
            sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    private fun isPunctuation(ch: Char): Boolean {
        if (ch in '!'..'/' || ch in ':'..'@' || ch in '['..'`' || ch in '{'..'~') return true
        return when (ch) {
            '，', '。', '、', '；', '：', '？', '！', '“', '”', '‘', '’',
            '（', '）', '《', '》', '「', '」', '『', '』', '…', '—', '·', '～' -> true
            else -> false
        }
    }

    /**
     * 编辑距离（滚动数组版，空间 O(min(m,n))）。
     *
     * 弹幕文本很短（通常 < 20 字符），即便窗口 50 条也毫无压力；
     * 若将来窗口显著变大，可考虑改为「长度差先剪枝 + 只算前缀」的近似版。
     */
    private fun editDistance(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        // 保证 n <= m，滚动数组取短的那个
        val (s, t) = if (m >= n) a to b else b to a
        val sl = s.length
        val tl = t.length

        var prev = IntArray(tl + 1) { it }
        var cur = IntArray(tl + 1)

        for (i in 1..sl) {
            cur[0] = i
            val si = s[i - 1]
            for (j in 1..tl) {
                val cost = if (si == t[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[tl]
    }

    /**
     * 挑一条可用轨道。
     *
     * 判据：该轨道上**最后一条**弹幕的尾部是否已经完全进入屏幕左侧、与新弹幕的头部之间
     * 留出 [minGapPx] 的间隔。
     *
     * @param nowMs      新弹幕的出生时刻（用于算已有弹幕当前位置）
     * @param usedTracks **本批**已占用的轨道，禁止复用（保证一批 N 条分散到 N 条轨道）
     * @return 轨道下标；没有可用轨道时返回 null
     */
    private fun pickTrack(nowMs: Long, usedTracks: Set<Int> = emptySet()): Int? {
        val trackCount = maxTracks.coerceIn(1, MAX_TRACKS_LIMIT)
        if (trackCount <= 0) return null

        // 每条轨道上「最靠右（最新）」的那条弹幕
        val lastOnTrack = arrayOfNulls<DanmuItem>(trackCount)
        for (item in items) {
            if (item.track in 0 until trackCount) {
                val cur = lastOnTrack[item.track]
                if (cur == null || item.bornMs >= cur.bornMs) lastOnTrack[item.track] = item
            }
        }

        // 优先选「空轨道」；都有占用时选最宽松的那条
        var bestTrack = -1
        var bestGap = Float.NEGATIVE_INFINITY

        for (t in 0 until trackCount) {
            if (t in usedTracks) continue  // 本批已用，跳过

            val last = lastOnTrack[t]
            if (last == null) return t  // 空轨道，直接用，且优先用最上面的

            // last 的右边缘位置（新弹幕从右缘出发，需要等它右边缘也移进来）
            // ⚠️ widthPx 未回填时按 0 处理 → 退化为「按时间间隔避让」，仍可用
            val lastX = last.currentX(nowMs, screenWidthPx, speedPxPerSec)
            val lastRight = lastX + last.widthPx
            // 新弹幕头部（屏幕右缘）与 last 尾巴的间距
            val gap = screenWidthPx - lastRight
            if (gap > bestGap) {
                bestGap = gap
                bestTrack = t
            }
        }

        // 间距足够才允许复用该轨道
        return if (bestTrack >= 0 && bestGap >= minGapPx()) bestTrack else null
    }

    /**
     * 最小安全间距。
     *
     * 与速度挂钩：速度越快，前后两条擦肩而过的时间越短，需要更大的空间间隔
     * （否则视觉上会「粘」在一起）。经验值：速度(px/s) × 0.35 秒。
     */
    private fun minGapPx(): Float {
        val bySpeed = speedPxPerSec * TRACK_GAP_SECONDS
        return max(GAP_PX_MIN, bySpeed)
    }

    companion object {
        /** 队列上限默认值（对齐 DanmuAI 的 DEFAULT_DANMU_PENDING_ENTRY_CAP） */
        const val MAX_PENDING_DEFAULT = 300

        /** 去重比较窗口：只与最近 N 条比，避免「一句话被永久封杀」 */
        const val DEDUP_WINDOW = 50

        /** 轨道最小间距下限（px） */
        private const val GAP_PX_MIN = 48f

        /** 轨道安全间隔（秒） */
        private const val TRACK_GAP_SECONDS = 0.35f

        /**
         * 同批弹幕的出生时间错开量（ms）。
         *
         * 见 [enqueue] 的说明：不错开则同批位置完全重合，轨道分配失效。
         * 120ms 在视觉上仍是"同一批"，但足够让 [pickTrack] 区分先后。
         */
        private const val BATCH_STAGGER_MS = 120L

        private const val MIN_SPEED = 40
        private const val MAX_SPEED = 1200
        private const val MAX_TRACKS_LIMIT = DanmuConfig.MAX_TRACKS_LIMIT
    }
}
