package com.example.vr

/**
 * AVI（RIFF）**索引充分性探测** —— 判断一个 AVI 的索引能否覆盖**全片**（v2.4.9 引入，v2.4.19 增强）。
 *
 * ## 它回答的问题（v2.4.19 起不再只是「有没有索引」）
 *
 * 1. **有没有 `idx1`**？（原来唯一的问题）
 * 2. **有没有 OpenDML 索引**（`indx` / `ix00` …）？—— 有它时 Media3Avi 会**优先用它**
 *    （`AviExtractor`：`if (riffType == AVIX || getIndexBoxList().size() > 0)` → 跳过 `idx1`），
 *    即使 `idx1` 只覆盖第一段也能 seek 全片。
 * 3. **是否存在多个 `movi` 段**（`LIST … AVIX`）？—— 这是「`idx1` 只覆盖第一段」的**前提条件**。
 *
 * ## 🔴 为什么第 3 条才是关键（v2.4.19 新增的判据）
 *
 * `idx1` 的 offset 字段是 **32 位**（相对第一个 `movi` 起点），所以**文件大到需要分段时**
 * （>1~2GB，老工具常见），文件会变成 **多个 `movi` + `AVIX` 容器**，
 * 而 `idx1`（只有一个）**通常只索引第一段**。
 *
 * Media3Avi 的 `parseIdx1()` 只要 `idx1` ≥16 字节就**无条件接受**并 `buildSeekMap()` ——
 * **它不核对 `idx1` 覆盖到哪里**。于是第二段的 chunk **没有索引条目** →
 * 拖到后半段时会落到「最后一个已知位置」（第一段末尾）→ 表现为
 * **「拖到后半段，画面停在前面不动」**。
 *
 * ⚠️ **实测校正（v2.4.19）**：拿 3 个真实样本（含上游命名为 `odml` 的那个）测过，
 * **它们的 `idx1` 都完整覆盖到文件尾（98.7%~99.9%）、且都没有 `AVIX`/`indx`**。
 * 也就是说「`idx1` 只覆盖第一段」**不是常态**，必须满足
 * **「多段 `movi`（`AVIX`）**且**无 `indx`」**这两个条件才成立 ——
 * 所以判据必须**同时**看这两件事：不能一见到「有 idx1」就放过，也不能一见到 `idx1` 就重建。
 *
 * ## 判据汇总（[Probe.needsIndexRebuild]）
 * | 情形 | 判定 |
 * |---|---|
 * | 无 `idx1` | **重建**（原有判据；没有索引就没有 seek 能力） |
 * | 有 `idx1` + **多段 `AVIX`** + **无 `indx`** | **重建**（新增：idx1 只覆盖第一段） |
 * | 有 `idx1` + 多段 `AVIX` + **有 `indx`** | 不必重建（Media3Avi 会优先用 `indx`） |
 * | 有 `idx1` + 单段 `movi` | 不必重建（实测 idx1 覆盖到文件尾） |
 *
 * ## 设计约束
 * - **纯逻辑、零 Android 依赖**（只吃 `ByteArray`）→ 可在纯 JVM 单测里跑。
 *   文件 IO 由调用方负责（`VRPlayerScreen` 读头 + 读尾）。
 * - **不信任 chunk 长度**：`size` 字段来自文件内容，所有偏移都做边界检查。
 * - ⚠️ 本类**仍然不判断视频编码**（历史上用于识别「AVI 内的 AV1」的逻辑已按用户要求整体移除）。
 *   编码种类与 seek 修复无关 —— 无论 XVID 还是别的，只要索引不足以覆盖全片，症状完全一样。
 */
object AviRiffProbe {

    /** 探测结果。 */
    data class Probe(
        /** 是否确实是 RIFF/AVI 容器（前 12 字节判定）。 */
        val isAvi: Boolean,
        /** 文件里是否存在 `idx1` 索引 chunk。 */
        val hasIndexChunk: Boolean,
        /**
         * 头部（`hdrl` 内）是否存在 **OpenDML 索引**（`indx` / `ix00` / …）。
         *
         * 有它时 Media3Avi **优先用它**，`idx1` 不完整也不影响 seek。
         */
        val hasOpenDmlIndex: Boolean = false,
        /**
         * 是否存在**多个 `movi` 段**（`LIST … AVIX`）。
         *
         * 这是「`idx1` 只覆盖第一段」的**前提**；单段 `movi` 的 AVI 里 `idx1` 必然覆盖全片。
         */
        val hasMultiSegment: Boolean = false
    ) {
        /**
         * 索引**不足以覆盖全片** → seek 会「跳到却不继续播 / 停在前段」→ 应重封装。
         *
         * ⚠️ 非 AVI 恒为 false：别的容器（mp4/mkv）有自己的索引机制，
         * 不能用「AVI 的索引够不够」去推断它们。
         */
        val needsIndexRebuild: Boolean
            get() = isAvi && (
                // ① 完全没有索引 —— 连第一段都 seek 不了
                !hasIndexChunk
                    // ② 多段 movi（idx1 只可能覆盖第一段）**且**没有 OpenDML 索引可替代
                    || (hasMultiSegment && !hasOpenDmlIndex)
                )
    }

    /**
     * 判定「是不是 AVI」+ 找 OpenDML 索引所需的头部字节数。
     *
     * ⚠️ v2.4.19：**1KB → 64KB**。`indx` 是 `strl` 的子块、位于 `hdrl` 里，
     * 而 `hdrl`（含 avih + 各 strl/strf）**经常超过 1KB** ——
     * 只读 1KB 会漏掉 `indx` → 把「有 OpenDML 索引」误判成「没有」→
     * 多段 AVI 会被无谓地重封装（白等几十秒）。
     */
    const val HEAD_BYTES = 64 * 1024

    /** 扫描 `idx1` / `AVIX` 时读取的文件尾部长度。索引与多段容器都位于 `movi` 之后，即文件末尾附近。 */
    const val TAIL_BYTES = 1024 * 1024

    /** 单个 `idx1` 条目固定 16 字节 → chunk 大小必须是它的整数倍（强判据，防误命中）。 */
    private const val IDX1_ENTRY_BYTES = 16L

    /** `idx1` 大小上限（512MB），超过视为误命中。 */
    private const val IDX1_MAX_BYTES = 512L * 1024 * 1024

    /** `indx` 头最短长度（wLongsPerEntry 起算的固定字段）。 */
    private const val INDX_MIN_BYTES = 24

    // ===================================================================
    // 一、容器判定 + OpenDML 索引（头部）
    // ===================================================================

    /**
     * 头部字节是否像 AVI：`RIFF` + 4 字节长度 + `AVI `；同时探 `indx`。
     *
     * ⚠️ 即使调用方已按扩展名筛过仍需要它：扩展名会撒谎，
     * 一个名为 `.avi` 的文件完全可能是别的容器 —— 那样就不该按 AVI 的逻辑去重封装。
     */
    fun parseHeader(head: ByteArray): Probe {
        if (head.size < 12) return Probe(isAvi = false, hasIndexChunk = false)
        if (ascii(head, 0, 4) != "RIFF") return Probe(false, false)
        if (ascii(head, 8, 4) != "AVI ") return Probe(false, false)
        return Probe(isAvi = true, hasIndexChunk = false, hasOpenDmlIndex = containsOpenDmlIndex(head))
    }

    /**
     * 头部缓冲里是否存在 **OpenDML 索引**（`indx` / `ix00` / `ix01` …）。
     *
     * ⚠️ 必须做**内容校验**，不能只搜 4 字节字面量 —— 与 `idx1` 同理，
     * 那 4 个字符完全可能偶然出现在别的数据里。
     * `indx` 的结构（AVI 规范 / `IndexBox`）：
     * ```
     *   'indx' | size(4) | wLongsPerEntry(2) | bIndexSubType(1) | bIndexType(1)
     *          | nEntriesInUse(4) | dwChunkId(4) | …
     * ```
     * 校验：`size >= 24`、`wLongsPerEntry ∈ {2,4,8,16}`、`bIndexType ∈ {0,1}`（标准索引类型）。
     */
    fun containsOpenDmlIndex(buffer: ByteArray): Boolean {
        // 只需能读到 id(4)+size(4)+wLongsPerEntry(2)+subType(1)+indexType(1) = 12 字节即可校验；
        // 不要求整个 `indx` 都在缓冲内（它可能被缓冲边界截断）。
        if (buffer.size < 12) return false
        var i = 0
        var nearMiss = 0
        while (i + 12 <= buffer.size) {
            val isIndx = buffer[i] == 'i'.code.toByte() && buffer[i + 1] == 'n'.code.toByte() &&
                buffer[i + 2] == 'd'.code.toByte() && buffer[i + 3] == 'x'.code.toByte()
            val isIx = buffer[i] == 'i'.code.toByte() && buffer[i + 1] == 'x'.code.toByte() &&
                buffer[i + 2] == '0'.code.toByte()
            if (isIndx || isIx) {
                val sz = le32(buffer, i + 4)
                val wLongs = le16(buffer, i + 8)
                val indexType = buffer[i + 11].toInt() and 0xFF
                if (sz >= INDX_MIN_BYTES && sz <= IDX1_MAX_BYTES &&
                    (wLongs == 2 || wLongs == 4 || wLongs == 8 || wLongs == 16) &&
                    (indexType == 0 || indexType == 1)
                ) {
                    return true
                }
                nearMiss++
                if (nearMiss > 16) return false
            }
            i++
        }
        return false
    }

    // ===================================================================
    // 二、idx1 索引存在性（尾部）
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
    // 三、多段 movi（AVIX）存在性 —— v2.4.19 新增
    // ===================================================================

    /**
     * 尾部缓冲里是否存在**多段 `movi` 容器**（`LIST … AVIX`）。
     *
     * ## 为什么这是「idx1 只覆盖第一段」的判据
     * `idx1` 的 offset 是 32 位、**相对第一个 `movi` 起点**；文件大到需要分段时，
     * 后续内容被放进额外的 `LIST AVIX` 里，而**唯一的 `idx1` 只描述第一段**。
     * 所以「存在 `AVIX`」⇔「`idx1` 很可能不完整」。
     *
     * ⚠️ 同样要做**结构性校验**：`AVIX` 必须出现在 `LIST` 的 **type 位置**
     * （即 `'LIST' | size(4) | 'AVIX'`），而不是随便 4 个字节。
     * 单独出现的 `AVIX` 字面量（在压缩数据里）不算数。
     */
    fun containsMultiSegmentMarker(buffer: ByteArray): Boolean {
        if (buffer.size < 12) return false
        var i = 4  // 保证 i-8 >= 0
        while (i + 4 <= buffer.size) {
            if (buffer[i] == 'A'.code.toByte() && buffer[i + 1] == 'V'.code.toByte() &&
                buffer[i + 2] == 'I'.code.toByte() && buffer[i + 3] == 'X'.code.toByte()
            ) {
                // 前 8 字节应是 LIST + 合理的 size
                if (i >= 8 &&
                    ascii(buffer, i - 8, 4) == "LIST" &&
                    le32(buffer, i - 4) in 4..(512L * 1024 * 1024)
                ) {
                    return true
                }
            }
            i++
        }
        return false
    }

    // ===================================================================
    // 四、字节小工具（全部带边界检查，绝不抛 IndexOutOfBounds）
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

    /** 读 2 字节小端无符号整数；越界返回 -1。 */
    private fun le16(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 2 > b.size) return -1
        return (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    }
}
