package com.example.vr

import androidx.compose.ui.graphics.Color
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
 * @param color       v2.4.6：本条弹幕的**文字颜色**。
 *                    在**入队时**按颜色模式一次性掷定并**固定在条目上** ——
 *                    ⚠️ 不能等到渲染时再随机：每帧都会重算 → 颜色会高频闪烁。
 */
data class DanmuItem(
    val text: String,
    val track: Int,
    var bornMs: Long,
    var widthPx: Float = 0f,
    val color: Color = Color.White
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

    // ---- v2.4.6：颜色（逐条随机）----

    /** 颜色模式（单一 / 完全随机 / 80%白+随机） */
    var colorMode: DanmuColorMode = DanmuColorMode.SINGLE

    /** [DanmuColorMode.SINGLE] 下使用的颜色（由调用方从 `DanmuConfig.textColorOption` 同步） */
    var singleColor: Color = Color.White

    /**
     * 随机数提供者（返回 0..1）。
     *
     * 注入以便**单元测试**用确定性序列验证「80% 白」的分布与「完全随机」的取值，
     * 否则随机行为无法断言。默认用 `kotlin.random.Random.nextFloat()`。
     */
    var randomProvider: () -> Float = { kotlin.random.Random.nextFloat() }

    /** 轨道高度（px）。由渲染层按字号算好后同步。 */
    var trackHeightPx: Float = 0f

    // ---- v2.4.7：批次时间戳（正态分布抖动）----

    /**
     * 同批弹幕的时间抖动标准差（ms）。0 = 关闭抖动（整批同一时刻，行为同 v2.4.5 之前的"同刻"）。
     *
     * 由调用方从 `DanmuConfig.timeJitterMs` 同步。详见 [spreadBornTimes] 的说明。
     */
    var timeJitterMs: Float = DEFAULT_TIME_JITTER_MS

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
     * - 用**时间抖动**错开出生时间（保证位置判据有意义）
     * - 用 `usedTracks` **禁止本批内共用轨道**（保证一批 N 条占 N 条轨道，视觉上是"同时飘出"）
     *
     * ## v2.4.7：线性错开 → 正态分布抖动
     * v2.4.6 及之前每条固定延后 `120ms × index`（见 [BATCH_STAGGER_MS]），节奏机械且整批被抻长。
     * 本版改为以 [nowMs] 为均值的正态抖动（见 [spreadBornTimes]），
     * 并**保证顺序不变**（抖动值升序后按原下标取用）。
     *
     * @param texts       待入队文本（**顺序即排布顺序**，会被保留）
     * @param nowMs       本批的名义时刻（抖动中心）
     * @param baseTimesMs 可选：每条**指定的出生时刻**（长度不足时余下走抖动）。
     *
     *   给「外部导入弹幕」用 —— 导入的弹幕带自己的时间戳，不该再被随机抖动打散。
     *   传入时该条**直接采用该时刻**，并跳过正态抖动。
     * @return 实际入队条数
     */
    fun enqueue(texts: List<String>, nowMs: Long, baseTimesMs: LongArray? = null): Int {
        if (texts.isEmpty()) return 0

        // ① 先算好「本次要通过的文本+出生时刻」，再做轨道分配。
        //
        //   ⚠️ v2.4.7 起必须**先定时间、再排轨道**，且按**出生时刻升序**分配轨道 ——
        //      抖动的意义就是让它们先后错开，若仍按原下标顺序分配轨道，
        //      会出现「时间上更晚的条目先占了轨道、更早的反而被判为太近而丢弃」。
        //      （v2.4.6 的线性错开天然递增，所以当时不存在这个问题。）
        val candidates = mutableListOf<Candidate>()

        // 需要抖动的条目下标（baseTimesMs 里没有给定时时刻的那些）
        val jitterIndices = mutableListOf<Int>()

        for ((index, raw) in texts.withIndex()) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            if (items.size + candidates.size >= maxPending) break

            val explicit = baseTimesMs?.getOrNull(index)
            if (explicit != null) {
                candidates.add(Candidate(text = text, bornMs = explicit))
            } else {
                jitterIndices.add(candidates.size)
                candidates.add(Candidate(text = text, bornMs = nowMs))
            }
        }
        if (candidates.isEmpty()) return 0

        // ② 给需要抖动的条目统一掷一组正态抖动（升序 → 保持原顺序）
        if (jitterIndices.isNotEmpty()) {
            val spread = spreadBornTimes(jitterIndices.size, nowMs, timeJitterMs, randomProvider)
            for ((k, ci) in jitterIndices.withIndex()) {
                // ⚠️ 非负钳位放在**这里**而不是 spreadBornTimes 内部：
                //    spreadBornTimes 是纯数学函数，钳位会破坏分布形态（见其注释）；
                //    而 bornMs 参与位置公式 `x = W - (now-born)*speed`，负值会让弹幕瞬移。
                //    真实调用方传的是 elapsedRealtime（远大于抖动上限），此处只是兜底。
                candidates[ci].bornMs = spread[k].coerceAtLeast(0L)
            }
        }

        // ③ 交给共用的插入流程（去重 + 轨道分配 + 上色）
        return insertCandidates(candidates) { null }  // null → 按 colorMode 掷色（AI 弹幕的行为）
    }

    /** [enqueue] 的内部候选（文本 + 已定出生时刻） */
    private class Candidate(val text: String, var bornMs: Long)

    /**
     * v2.4.7：带**指定颜色**的入队（供外部导入弹幕使用）。
     *
     * ## 为什么不复用 [enqueue]
     * [enqueue] 按 [colorMode] 给每条掷色 —— 这是 AI 弹幕想要的（用户选了随机模式）。
     * 但**导入的弹幕自带颜色**（B 站 XML 第 3 项 / JSON 的 `color` 字段），
     * 再走一遍随机就把文件里的颜色意图覆盖掉了。
     *
     * ## 实现要点
     * 轨道分配、去重、上限等逻辑与 [enqueue] **完全共用**（抽到 [insertCandidates]），
     * 唯一差别是颜色来源：这里用调用方给的 [colors]（允许元素为 null → 回落 [singleColor]）。
     *
     * @param entries 每条 = (文本, 出生时刻, 指定颜色 or null)
     * @return 实际入队条数
     */
    fun enqueueTimed(
        entries: List<Triple<String, Long, Color?>>
    ): Int {
        if (entries.isEmpty()) return 0
        val candidates = mutableListOf<Candidate>()
        val colors = mutableListOf<Color?>()
        for ((text, bornMs, color) in entries) {
            val t = text.trim()
            if (t.isEmpty()) continue
            if (items.size + candidates.size >= maxPending) break
            candidates.add(Candidate(text = t, bornMs = bornMs))
            colors.add(color)
        }
        if (candidates.isEmpty()) return 0
        return insertCandidates(candidates) { idx -> colors[idx] }
    }

    /**
     * 把候选按**出生时刻升序**做去重 + 轨道分配 + 入库（[enqueue] / [enqueueTimed] 共用）。
     *
     * ⚠️ 抽出共用的理由：这条流程里有几处容易写错的约束
     * （按时间排序分配、本批禁复用轨道、去重窗口维护、颜色来源可替换），
     * 复制成两份必然改一处漏一处 —— 本项目「同一份逻辑两处登记」是头号事故源。
     *
     * @param colorOf 按下标提供颜色；返回 null 表示回落 [singleColor]
     */
    private fun insertCandidates(
        candidates: List<Candidate>,
        colorOf: (Int) -> Color?
    ): Int {
        // 按出生时刻升序分配轨道（同时刻保持原下标序 —— sortedBy 是稳定排序）
        val order = candidates.indices.sortedBy { candidates[it].bornMs }

        var added = 0
        val usedTracks = HashSet<Int>()
        for (ci in order) {
            val c = candidates[ci]
            if (isDuplicate(c.text)) continue
            val track = pickTrack(c.bornMs, usedTracks) ?: continue

            // 指定色优先；未指定才按颜色模式掷（保证外部导入不会被随机覆盖）
            val specified = colorOf(ci)
            val color = specified ?: DanmuConfig.pickColor(colorMode, singleColor, randomProvider)

            items.add(DanmuItem(text = c.text, track = track, bornMs = c.bornMs, color = color))
            usedTracks.add(track)
            recentTexts.addLast(c.text)
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
         * 同批弹幕的出生时间错开量（ms）。—— **v2.4.7 起已被正态抖动取代**，
         * 保留常量仅用于说明抖动强度的量级参照（见 [DEFAULT_TIME_JITTER_MS]）。
         *
         * ⚠️ 不要再在新代码里使用它。历史上见 [enqueue] 的说明：
         * 当时若不错开则同批位置完全重合、轨道分配失效；120ms 是那个年代的经验值。
         */
        @Deprecated("v2.4.7 起改用正态抖动，见 DEFAULT_TIME_JITTER_MS / spreadBornTimes")
        const val BATCH_STAGGER_MS = 120L

        private const val MIN_SPEED = 40
        private const val MAX_SPEED = 1200
        private const val MAX_TRACKS_LIMIT = DanmuConfig.MAX_TRACKS_LIMIT

        /**
         * v2.4.6：行距自适应 —— 轨道高度相对**字号**的系数。
         *
         * ## 为什么需要
         * v2.4.5 及之前轨道高度 = `弹幕区高度 / 轨道数`，于是：
         * - **字号调大时行距不变** → 大字号下上下行贴在一起；
         * - **轨道数调多时行距被压小** → 8 轨变 20 轨，行距只剩原来的 40%。
         * 用户反馈「优化多条弹幕排布」，要的就是**行距跟着字号自适应**。
         *
         * ## 取值
         * 1.9 倍字号 ≈ 行间留出约一个字符高度的空隙；调小会贴紧，调大会很快占满弹幕区。
         *
         * ⚠️ 定义在引擎（而非渲染层）的理由：这是**与字号/轨道数相关的布局常量**，
         *    且需要被单测覆盖；放在 `DanmuOverlay`（Composable 私有作用域）就测不到。
         */
        const val TRACK_HEIGHT_FONT_FACTOR = 1.9f

        /**
         * v2.4.6：弹幕区高度占屏高的**硬上限**比例。
         *
         * ## 为什么不能只给「区域高度 = 轨道数 × 行距」
         * 轨道数上限是 [DanmuConfig.MAX_TRACKS_LIMIT]（20），大字号下
         * `20 × 32sp × 1.9` 会算出超过整个屏幕的高度，弹幕会一路压到字幕区、
         * 甚至溢出屏幕下缘。因此必须有一个「无论怎样都不能超过」的比例上限。
         *
         * ## 为什么提高到 0.45
         * v2.4.5 及之前固定 0.30（[DanmuOverlay.DEFAULT_AREA_HEIGHT_RATIO]）。
         * ⚠️ 实测发现 0.30 在**默认参数**下就已经不够：1080×2400 / 密度 3x / 8 轨时
         * 区域只有 720px，均分 90px，而字号 18sp(=54px) × 1.9 = 102.6px **已超出** ——
         * 也就是「行距自适应」在默认配置下会被区域封顶、**完全感受不到变化**。
         * 这正是本版把区域改为「随内容自适应」而非固定值的原因。
         * 0.45 给内容留出足够空间，同时仍把字幕区（通常在下半部）让出来。
         */
        const val AREA_HEIGHT_RATIO_MAX = 0.45f

        /**
         * v2.4.6：行距自适应 —— 计算**弹幕区高度**（px）。
         *
         * ## 公式
         * ```
         * 弹幕区高度 = min(轨道数 × 字号 × 系数, 屏高 × AREA_HEIGHT_RATIO_MAX)
         * ```
         *
         * ## 语义：区域**跟着内容长**，而不是反过来压内容
         * v2.4.5 是「固定 30% 区域 → 除以轨道数得行距」，行距**永远是被动结果**；
         * 本版改为「先按字号定行距 → 区域按需撑开」，于是：
         * - **字号调大** → 区域变高、行距真的变大（用户要的效果）；
         * - **轨道数变少** → 区域自动变矮，不白占画面；
         * - **内容太多** → 顶到 [AREA_HEIGHT_RATIO_MAX] 上限后行距才开始被压缩。
         *
         * @param fontSizePx 字号折算出的像素高
         * @param screenHeightPx 整屏高（px）
         * @param trackCount 轨道数（<1 时按 1 处理）
         */
        fun computeAreaHeightPx(
            fontSizePx: Float,
            screenHeightPx: Float,
            trackCount: Int
        ): Float {
            val tracks = trackCount.coerceAtLeast(1)
            val desired = tracks * fontSizePx * TRACK_HEIGHT_FONT_FACTOR
            if (screenHeightPx <= 0f) return desired
            val cap = screenHeightPx * AREA_HEIGHT_RATIO_MAX
            return min(desired, cap)
        }

        /**
         * v2.4.6：行距自适应 —— 计算**轨道高度**（px）。
         *
         * ```
         * 轨道高度 = min(字号 × 系数, 弹幕区高度 ÷ 轨道数)
         * ```
         *
         * ## 为什么保留 min（⚠️ 初版写成 max，被单测抓到）
         * - **字号基准**（`fontSizePx × 系数`）是「理想舒适行距」；
         * - **区域均分**（`areaHeightPx / trackCount`）是「物理上限」。
         *
         * 当区域由 [computeAreaHeightPx] 按内容算出时，两者通常**相等**
         * （因为区域本来就 = 轨道数 × 理想行距）；只在内容顶到 [AREA_HEIGHT_RATIO_MAX]
         * 上限时 `fit < natural`，此时才真正压缩行距 —— 这正是我们要的行为。
         *
         * 若误写成 `max`：区域一大就永远取均分值，字号从 20 调到 50 行距都不变，
         * **行距自适应彻底失效**（`DanmuTrackHeightTest` 专门锁死方向性）。
         *
         * @param fontSizePx   字号折算出的像素高（渲染层用 LocalDensity 换算后传入）
         * @param areaHeightPx 弹幕区总高度（px）；<=0 时视为「无上限」只按字号
         * @param trackCount   轨道数（<1 时按 1 处理）
         */
        fun computeTrackHeightPx(
            fontSizePx: Float,
            areaHeightPx: Float,
            trackCount: Int
        ): Float {
            val tracks = trackCount.coerceAtLeast(1)
            val natural = fontSizePx * TRACK_HEIGHT_FONT_FACTOR
            // 区域高度未知（布局首帧）时不做上限约束，先用理想行距
            if (areaHeightPx <= 0f) return natural
            val fit = areaHeightPx / tracks
            return min(natural, fit)
        }

        // ================================================================
        // v2.4.7：批次时间戳 —— 正态分布抖动
        // ================================================================

        /**
         * 同批弹幕的时间抖动标准差（ms）—— **默认值**，可被 [DanmuConfig.timeJitterMs] 覆盖。
         *
         * ## 为什么是「抖动」而不是「线性错开」
         * v2.4.6 及之前用固定步长 [BATCH_STAGGER_MS]（120ms）线性错开：
         * 第 k 条固定延后 `k × 120ms`，一批 8 条就抻成 `7 × 120 = 840ms`。
         * 问题有两个：
         * - **节奏机械**：看上去像"点名报到"，一眼能看出是程序排的；
         * - **前后差过大**：8 条要 0.84s 才铺完，而一条弹幕穿过屏幕约 5s ——
         *   整批在时间轴上明显「拉长」，与真人弹幕那种"一簇涌出"的观感不符。
         *
         * 改成以批次时刻为均值、**两侧对称抖动**的正态分布后：
         * - 绝大多数弹幕紧贴均值（一簇涌出，像真人）；
         * - 少数自然落到 ±1σ / ±2σ 之外（错落感来自统计，而不是硬排）。
         *
         * ## 为什么 400ms
         * 与 [BATCH_STAGGER_MS] 的「总量级」对齐（8 条线性错开总跨度 840ms，
         * 而 ±1σ=400ms 覆盖 68%、±2σ 覆盖 95%，跨度相当但**中间密、两头疏**）。
         * 另外 400ms 相对一条弹幕约 5s 的存活期很短，不会让整批显得断续。
         *
         * ⚠️ σ 是「抖动强度」不是「总跨度」：取 400ms 时理论极值可达 ±1.2s 以上
         *    （3σ），因此实现里另有 [SPREAD_MAX_ABS_MS] 做硬截断。
         */
        const val DEFAULT_TIME_JITTER_MS = 400f

        /**
         * 单条抖动量的**绝对值上限**（ms）。
         *
         * 正态分布理论上无穷远，必须截断 —— 否则极小概率会摇出一个 ±3s 开外的值，
         * 让某条弹幕「凭空迟到几秒」才出现，观感上像卡顿。
         * 取 2.5σ（σ=400 时为 1000ms）：保留 98.8% 的分布形态，又封死长尾。
         */
        const val SPREAD_MAX_ABS_MS = 1000L

        /**
         * 从均匀随机数生成**标准正态**随机数（Box–Muller 变换）。
         *
         * ## 为什么自己实现而不用现成的
         * 1. `kotlin.random.Random` 只提供均匀分布，取正态要么自己写、要么引入
         *    `java.util.Random.nextGaussian()` —— 但后者**无法注入**，
         *    会让「时间分布」变成不可单测的行为（本项目单测全部是纯 JVM，不碰真随机）。
         * 2. 这里需要的是**可注入**：调用方传 `roll()`，测试即可给出确定序列。
         *
         * ## 公式
         * ```
         * Z = sqrt(-2·ln(u1)) · cos(2π·u2)      // u1,u2 ~ U(0,1]
         * ```
         * 取 `cos` 分支、**丢弃 `sin` 那一路**：对「逐条独立掷」的用途完全够用，
         * 少写一个缓存变量也少一处出错点（省一半随机数消耗，代价可忽略）。
         *
         * ⚠️ `u1` 必须 **> 0**（`ln(0) = -∞`）→ 用 `coerceAtLeast` 兜住 `roll()` 返回 0 的情形。
         *
         * @param roll 0..1 的随机数提供者（注入以便单测）
         */
        fun standardNormal(roll: () -> Float): Float {
            val u1 = roll().coerceAtLeast(MIN_LOG_EPSILON)
            val u2 = roll()
            return (kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1.toDouble())) *
                    kotlin.math.cos(2.0 * Math.PI * u2)).toFloat()
        }

        /** `ln()` 的输入下限，避免 `u1 == 0` 时得到 `-Infinity` */
        private const val MIN_LOG_EPSILON = 1e-6f

        /**
         * v2.4.7：为**一批**弹幕计算各自的出生时刻（正态分布抖动）。
         *
         * ## 语义
         * 以 [centerMs]（本批的「名义时刻」）为均值 `μ`、[sigmaMs] 为标准差 `σ`，
         * 为 `count` 条弹幕各掷一个抖动 `d ~ N(0, σ)`，得到 `centerMs + d`。
         *
         * ## ⚠️「按顺序」的含义 —— 必须单调不减
         * 用户要求「按顺序正态分布」，即**抖动后仍要保持 AI 返回的先后顺序**。
         * 若各自独立掷完就用，可能出现第 3 条落在第 2 条之前 → 渲染时后一条反而先出现，
         * 视觉上就是「顺序错乱」（用户明确要求保持顺序）。
         *
         * 因此实现分两步：
         * 1. **掷出一组抖动并升序排序**（[sortedJitters]）；
         * 2. **按原下标依次取用**（第 i 条拿第 i 小的抖动）。
         *
         * 这样得到的序列**严格单调不减**，同时整体仍服从正态分布
         * （排序只是改变了「哪个下标拿到哪个分位」，不改变边缘分布 ——
         *  因为所有抖动都来自同一个 N(0,σ)，是可交换的）。
         *
         * 这相当于把「同一批同时到达的弹幕」重新摊成**有序但疏密不均**的一组时刻，
         * 正是「按顺序 + 正态分布」想要的效果。
         *
         * ## 为什么中心化到批次时刻而不是从批次时刻起算
         * 「两侧对称」才有正态的形态（中间密两头疏）。
         * 若只往后抖（`centerMs + |d|`）就变成半正态，前几条会挤在 0 附近、
         * 整批整体后移，相当于凭空延迟。
         *
         * ## ⚠️ 不要在这里对结果做「非负保护」（踩过的坑）
         * 曾写过 `(centerMs + jitter).coerceAtLeast(0L)`，结果是**左半边分布被压平**：
         * 当 `centerMs` 接近 0 时（如测试传 0，或极早启动），所有负抖动全部被截成同一个 0 →
         * `first20` 打出来是一串 0、标准差从 400 掉到 228、`|d|<1σ` 占比从 0.68 涨到 0.84。
         * 即**看似"更安全"的钳位反而破坏了分布形态**，而"非负"并不是本函数的职责 ——
         * 真实调用方传的是 `SystemClock.elapsedRealtime()`（开机毫秒数，恒远大于
         * [SPREAD_MAX_ABS_MS]），本就不可能靠抖动摇到负数。
         *
         * 教训：**在纯数学函数里加"业务保护"，很容易把数学性质改坏**。
         * 该做的是让函数保持纯粹语义，把约束交给调用方（此处已无约束需要）。
         *
         * @param count    条数（<=0 返回空数组）
         * @param centerMs 批次名义时刻（通常 = 入队时的 now）
         * @param sigmaMs  标准差（ms）；<=0 时等价于「不抖动」，全部返回 [centerMs]
         * @param roll     0..1 随机数提供者（每条消耗 2 个）
         * @return 长度 = [count] 的数组，**单调不减**
         */
        fun spreadBornTimes(
            count: Int,
            centerMs: Long,
            sigmaMs: Float,
            roll: () -> Float
        ): LongArray {
            if (count <= 0) return LongArray(0)
            // σ 无效 → 不抖动（保持旧式「全部同一时刻」，由调用方决定是否需要）
            if (sigmaMs <= 0f || !sigmaMs.isFinite()) return LongArray(count) { centerMs }

            // ① 掷 count 个抖动（截断到 ±SPREAD_MAX_ABS_MS，防止正态长尾）
            val jitters = LongArray(count)
            for (i in 0 until count) {
                val z = standardNormal(roll)
                val jitter = (z * sigmaMs).toLong().coerceIn(-SPREAD_MAX_ABS_MS, SPREAD_MAX_ABS_MS)
                jitters[i] = jitter
            }
            // ② 升序 → 按原下标依次取用，保证单调不减
            jitters.sort()
            val out = LongArray(count)
            for (i in 0 until count) {
                out[i] = centerMs + jitters[i]
            }
            return out
        }
    }
}
