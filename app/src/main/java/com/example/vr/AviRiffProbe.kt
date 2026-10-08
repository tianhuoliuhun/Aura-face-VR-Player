package com.example.vr

/**
 * AVI（RIFF）**索引表探测** —— 判断一个 AVI 是否带 `idx1` 索引（v2.4.9）。
 *
 * ## 只回答一个问题：有没有索引？
 *
 * ⚠️ 本类**刻意不判断视频编码**。历史上这里曾用于识别「AVI 内的 AV1」，
 * 该逻辑已整体移除（用户明确要求：不为 AV1 单独折腾）。
 * 想识别编码应走别处，**不要往这里加判据** ——
 * 本类的唯一用途是给 seek 修复提供依据，而编码种类对它没有影响：
 * 无论 XVID 还是别的，只要没有 `idx1`，seek 症状完全一样。
 *
 * ## 为什么「有没有 idx1」决定了 seek 能不能用
 *
 * AVI 的关键帧位置记录在文件尾部的 `idx1` chunk 里（`AviSeekMap` 读它）。
 *
 * - **有 `idx1`** → seek 能直接查到目标点附近的关键帧 → 定位快且准。
 * - **没有 `idx1`**（老设备导出的 AVI 很常见）→ 只能**从头线性扫描 chunk**。
 *   这不只是「慢」，更关键的是**扫到的位置未必落在真正的关键帧上** ——
 *   解码器从非关键帧开始**解不出后续画面**（缺 I 帧参考），
 *   表现就是用户报的那个症状：
 *   > **「能跳过去、画面停在那一帧、但不再继续播放」**
 *
 * 该症状**不是** seek 参数能修的（本项目已经设了 `SeekParameters`、也早已
 * 改成「拖动中不 seek、松手只 seek 一次」）—— 根因在**容器缺索引**，
 * 唯一解法是用 [VideoRemuxer] 不重编码地重新封装为 MP4（重建索引）。
 *
 * → 本类提前把「有没有索引」探明，让调用方**在第一次 seek 时就知道该不该重封装**，
 *   而不是等用户被卡一次之后才反应。
 *
 * ## 设计约束
 * - **纯逻辑、零 Android 依赖**（只吃 `ByteArray`）→ 可在纯 JVM 单测里跑。
 *   文件 IO 由调用方负责（`VRPlayerScreen` 读头 + 读尾）。
 * - **不信任 chunk 长度**：`size` 字段来自文件内容，所有偏移都做边界检查。
 */
object AviRiffProbe {

    /** 探测结果。 */
    data class Probe(
        /** 是否确实是 RIFF/AVI 容器（前 12 字节判定）。 */
        val isAvi: Boolean,
        /** 文件里是否存在 `idx1` 索引 chunk。 */
        val hasIndexChunk: Boolean
    ) {
        /**
         * 是 AVI 但**没有索引表** → seek 会「跳到却不继续播」，应重封装。
         *
         * ⚠️ 非 AVI 恒为 false：别的容器（mp4/mkv）有自己的索引机制，
         * 不能用「没有 idx1」去推断它们不能 seek。
         */
        val needsIndexRebuild: Boolean get() = isAvi && !hasIndexChunk
    }

    /** 判定「是不是 AVI」只需 12 字节；留一点余量便于将来扩展。 */
    const val HEAD_BYTES = 1024

    /** 扫描 `idx1` 时读取的文件尾部长度。索引 chunk 位于 `movi` 之后，即文件末尾。 */
    const val TAIL_BYTES = 1024 * 1024

    /** 单个 `idx1` 条目固定 16 字节 → chunk 大小必须是它的整数倍（强判据，防误命中）。 */
    private const val IDX1_ENTRY_BYTES = 16L

    /** `idx1` 大小上限（512MB），超过视为误命中。 */
    private const val IDX1_MAX_BYTES = 512L * 1024 * 1024

    // ===================================================================
    // 一、容器判定
    // ===================================================================

    /**
     * 头部字节是否像 AVI：`RIFF` + 4 字节长度 + `AVI `。
     *
     * ⚠️ 即使调用方已按扩展名筛过仍需要它：扩展名会撒谎，
     * 一个名为 `.avi` 的文件完全可能是别的容器 —— 那样就不该按 AVI 的逻辑去重封装。
     */
    fun parseHeader(head: ByteArray): Probe {
        if (head.size < 12) return Probe(isAvi = false, hasIndexChunk = false)
        if (ascii(head, 0, 4) != "RIFF") return Probe(false, false)
        if (ascii(head, 8, 4) != "AVI ") return Probe(false, false)
        return Probe(isAvi = true, hasIndexChunk = false)
    }

    // ===================================================================
    // 二、idx1 索引存在性
    // ===================================================================

    /**
     * 在一段缓冲里找 `idx1` chunk 标记。
     *
     * ⚠️ **不能只搜 ASCII `"idx1"`** —— 那 4 个字节完全可能偶然出现在
     * 视频/音频数据里，造成「本来没有索引却判定有索引」→
     * 该重封装的不重封装 → 用户的「跳到那一帧就不动了」永远治不好。
     *
     * 因此加了**结构性判据**：紧跟其后的 4 字节大小字段必须同时满足
     * ① 是 16 的整数倍（每个 `idx1` 条目固定 16 字节）
     * ② 落在 `[16, 512MB]` 区间内
     *
     * 传进来的应是**文件尾部**缓冲（索引在 `movi` 之后）。
     */
    fun containsIndexMarker(buffer: ByteArray): Boolean {
        if (buffer.size < 8) return false
        var i = 0
        var nearMiss = 0
        while (i + 8 <= buffer.size) {
            if (buffer[i] == 'i'.code.toByte() &&
                buffer[i + 1] == 'd'.code.toByte() &&
                buffer[i + 2] == 'x'.code.toByte() &&
                buffer[i + 3] == '1'.code.toByte()
            ) {
                val sz = le32(buffer, i + 4)
                if (sz >= IDX1_ENTRY_BYTES && sz % IDX1_ENTRY_BYTES == 0L && sz <= IDX1_MAX_BYTES) {
                    return true
                }
                // 命中字面量但结构不符 → 大概率是数据里的巧合，继续找；
                // 只容忍有限次，避免在畸形/超大缓冲上退化成全扫。
                nearMiss++
                if (nearMiss > 16) return false
            }
            i++
        }
        return false
    }

    // ===================================================================
    // 三、字节小工具（全部带边界检查，绝不抛 IndexOutOfBounds）
    // ===================================================================

    /** 读 4 字节 ASCII；越界返回空串（调用方按「没读到」处理）。 */
    private fun ascii(b: ByteArray, off: Int, len: Int): String {
        if (off < 0 || len <= 0 || off + len > b.size) return ""
        return String(b, off, len, Charsets.US_ASCII)
    }

    /** 读 4 字节小端无符号整数；越界返回 -1。 */
    private fun le32(b: ByteArray, off: Int): Long {
        if (off < 0 || off + 4 > b.size) return -1L
        return (b[off].toLong() and 0xFFL) or
            ((b[off + 1].toLong() and 0xFFL) shl 8) or
            ((b[off + 2].toLong() and 0xFFL) shl 16) or
            ((b[off + 3].toLong() and 0xFFL) shl 24)
    }
}
