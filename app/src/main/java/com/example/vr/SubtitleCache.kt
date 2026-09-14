package com.example.vr

import java.util.TreeMap

/**
 * v126：实时字幕的稀疏时间索引缓存（方案文档第五章）。
 *
 * ## 为什么用 TreeMap 而不是 List
 * 字幕查询由渲染路径**每帧调用**（60fps → 每秒 60 次）。`List` + 线性扫描在
 * 长视频上会累积成可观开销：2 小时视频约 1800 条字幕，每帧扫一遍就是
 * 每秒十万次比较。`TreeMap.floorEntry` 把查询压到 O(log n)，且天然按时间有序，
 * 不需要额外排序。
 *
 * ## 为什么不做淘汰策略
 * 文档实测：4 分钟 31 条 ≈ 6KB、33 分钟 499 条 ≈ 100KB、2 小时约 1800 条 ≈ 360KB。
 * 全片常驻内存完全可行，淘汰只会让「向回拖动」失去瞬时命中的能力。
 *
 * ## 线程约定
 * 写入来自预读线程（ASR 完成后），查询来自渲染/主线程，两者并发，
 * 因此所有公开方法都加锁。锁内只做 TreeMap 操作，不涉及 IO 与计算，
 * 不会成为瓶颈。
 */
class SubtitleCache {

    /** key = startTimeMs，保证按时间有序 */
    private val cues = TreeMap<Long, SubtitleCue>()

    /** ASR 识别完成后写入 */
    @Synchronized
    fun put(cue: SubtitleCue) {
        cues[cue.startTimeMs] = cue
    }

    @Synchronized
    fun putAll(list: List<SubtitleCue>) {
        for (c in list) cues[c.startTimeMs] = c
    }

    /**
     * 查询 [timeMs] 时刻应显示的字幕。
     *
     * 返回最近一条 `startTimeMs <= timeMs` 的 cue，且要求 `timeMs < endTimeMs`
     * （否则说明已播过该条，间隙期应显示空）。
     */
    @Synchronized
    fun find(timeMs: Long): SubtitleCue? {
        val entry = cues.floorEntry(timeMs) ?: return null
        return entry.value.takeIf { timeMs < it.endTimeMs }
    }

    /** 供 UI 渲染的快照（有序） */
    @Synchronized
    fun snapshot(): List<SubtitleCue> = ArrayList(cues.values)

    @Synchronized
    fun size(): Int = cues.size

    /**
     * Seek 时作废「已跑过头」的部分。
     * 只清 [timeMs] 之后的条目——前方（往回拖的方向）已生成的字幕必须保留，
     * 这正是「向后拖动瞬时命中」的依据。
     */
    @Synchronized
    fun invalidateAfter(timeMs: Long) {
        if (cues.isNotEmpty()) cues.tailMap(timeMs, false).clear()
    }

    /** 已生成区间末端，用于预读调度判断"是否需要继续往前做" */
    @Synchronized
    fun lastEndMs(): Long = cues.lastEntry()?.value?.endTimeMs ?: 0L

    @Synchronized
    fun clear() {
        cues.clear()
    }
}
