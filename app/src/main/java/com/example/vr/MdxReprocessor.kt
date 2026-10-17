package com.example.vr

import kotlin.math.min

/**
 * v2.4.68（档 2）：**按说话人重组音轨** —— 把「被选中要处理」的说话人的语音区间换成伴奏。
 *
 * ## 为什么要「按说话人」而不是「按性别一刀切」
 * 分析结果里是「说话人 A（男）/ B（女）/ C（男）…」，用户可能只想去掉其中**某几个人**
 * （比如保留旁白男声、只去掉对手戏男声）。所以 UI 上每个说话人一个开关，
 * 处理时只对**被勾选者**的区间动刀 —— 这比「所有男声一律去掉」更可控。
 *
 * ## 替换方式
 * 对选中说话人的区间，把「混音」换成「**伴奏 = 混音 − 人声**」：
 * - 保留环境音与音乐 ✅（这是「去人声但留环境音」的关键）
 * - 边界做 [crossfadeMs] 的**线性淡入淡出**（默认 60ms），避免硬切产生爆音
 * - **多段重叠时取最大权重**（同一位置被多个段覆盖不会重复叠加）
 *
 * ## 已知边界（必须如实告知用户）
 * 若两个说话人**同时说话**，把其中一人去掉时，同段内的另一个人声也会一起被削弱
 * （因为替换的是整段的人声成分）。要做到「只去男声、保留同时说话的女声」需要 TSE
 * （目标说话人提取），不在本版范围（见 `docs/AUDIO_GENDER_FILTER_TECH_STACK.md` §3.6）。
 */
object MdxReprocessor {

    /** 默认边界交叉淡化（毫秒） */
    const val DEFAULT_CROSSFADE_MS = 60

    /**
     * @param mixL/mixR   原始混音（44.1k）
     * @param vocalL/vocalR MDX 分离出的人声轨（44.1k，长度应与混音一致）
     * @param segments    分析得到的语音段（含 speakerId）
     * @param selected    **要处理（去掉）**的 speakerId 集合
     * @param crossfadeMs 边界淡入淡出长度
     * @return 处理后的立体声
     */
    fun apply(
        mixL: FloatArray,
        mixR: FloatArray,
        vocalL: FloatArray,
        vocalR: FloatArray,
        segments: List<AudioGenderAnalyzer.SpeakerSegment>,
        selected: Set<Int>,
        crossfadeMs: Int = DEFAULT_CROSSFADE_MS,
    ): Pair<FloatArray, FloatArray> {
        val n = minOf(mixL.size, mixR.size, vocalL.size, vocalR.size)
        if (n <= 0) return FloatArray(0) to FloatArray(0)

        val outL = mixL.copyOf(n)
        val outR = mixR.copyOf(n)
        if (selected.isEmpty() || segments.isEmpty()) return outL to outR

        // 伴奏 = 混音 − 人声（UVR 的一贯做法）
        val accL = FloatArray(n) { mixL[it] - vocalL[it] }
        val accR = FloatArray(n) { mixR[it] - vocalR[it] }

        // 权重图：0 = 保留原音，1 = 完全换成伴奏
        val w = FloatArray(n)
        val fade = (crossfadeMs.coerceAtLeast(0) * MdxDsp.SAMPLE_RATE / 1000).coerceAtLeast(1)
        var touched = false
        for (seg in segments) {
            if (seg.speakerId !in selected) continue
            val a = (seg.startMs * MdxDsp.SAMPLE_RATE / 1000).toInt().coerceIn(0, n)
            val b = (seg.endMs * MdxDsp.SAMPLE_RATE / 1000).toInt().coerceIn(a, n)
            if (b <= a) continue
            touched = true
            for (i in a until b) {
                // 到两端距离的最小值 → 线性淡入/淡出；段中部为 1
                val d0 = i - a
                val d1 = b - 1 - i
                val f = min(min(d0, d1), fade).toFloat() / fade
                if (f > w[i]) w[i] = f
            }
        }
        if (!touched) return outL to outR

        for (i in 0 until n) {
            val k = w[i]
            if (k > 0f) {
                outL[i] = mixL[i] * (1f - k) + accL[i] * k
                outR[i] = mixR[i] * (1f - k) + accR[i] * k
            }
        }
        return outL to outR
    }

    /**
     * 把分析结果整理成「按说话人聚合」的列表，供 UI 逐个列出 + 每个一个开关。
     *
     * @return 每个说话人：id、性别、F0、语音时长、段数；按**语音时长降序**（主要说话人排前面）
     */
    fun speakerRows(result: AudioGenderAnalyzer.Result): List<Row> =
        result.speakers
            .map { s ->
                Row(
                    id = s.id,
                    gender = s.gender,
                    f0Hz = s.f0MedianHz,
                    speechMs = s.speechMs,
                    segmentCount = s.segmentCount,
                )
            }
            .sortedByDescending { it.speechMs }

    /** UI 用的行模型（一个说话人一行 + 一个开关） */
    data class Row(
        val id: Int,
        val gender: AudioGenderAnalyzer.Gender,
        val f0Hz: Float,
        val speechMs: Long,
        val segmentCount: Int,
    ) {
        /** 默认是否勾选（要不要去掉）：**默认勾选男声**，其余不勾 */
        fun defaultSelected(): Boolean = gender == AudioGenderAnalyzer.Gender.MALE
    }
}
