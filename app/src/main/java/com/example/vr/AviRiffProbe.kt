package com.example.vr

/**
 * AVI（RIFF）**索引充分性探测** —— 判断一个 AVI 的索引能否覆盖**全片**（v2.4.9 引入，v2.4.19 增强）。
 *
 * ## 它回答的问题（v2.4.19 起不再只是「有没有索引」）
 *
 * 1. **有没有 `idx1`**？
 * 2. **`idx1` 实际覆盖到哪里**？—— ✅ **这是最直接的判据**（v2.4.19 二轮补上）
 * 3. **有没有 OpenDML 索引**（`indx` / `ix00` …）？—— 有它时 Media3Avi **优先用它**
 *    （`AviExtractor`：`if (riffType == AVIX || getIndexBoxList().size() > 0)` → 跳过 `idx1`）。
 * 4. **是否存在多个 `movi` 段**（`LIST … AVIX`）？—— 一个**结构性旁证**。
 *
 * ## 🔴 为什么会「`idx1` 只覆盖第一段」（用户确认该情形真实存在）
 *
 * `idx1` 的 offset 字段是 **32 位**、且**相对第一个 `movi` 起点**。文件大到需要分段时
 * （OpenDML / >1~2GB，老工具常见），后续内容被放进额外的 `LIST AVIX` 里，
 * 而**唯一的 `idx1` 往往只描述第一段**。
 *
 * Media3Avi 的 `parseIdx1()` 只要 `idx1` ≥16 字节就**无条件接受**并 `buildSeekMap()` ——
 * **它从不核对 `idx1` 覆盖到哪里**。于是第二段的 chunk 没有索引条目 →
 * 拖到后半段会落到「最后一个已知位置」（第一段末尾）→ 表现为
 * **「拖到后半段，画面停在前面不动 / 跳到却不继续播」**。
 *
 * ## ⚠️ 判据演进（走过的弯路，别再退回去）
 *
 * - **v1（错）**：只判「有没有 `idx1`」→ 有就放过 → 漏判「只覆盖第一段」。
 * - **v2（不充分）**：改成判「**多段 `AVIX` 且无 `indx`**」—— 这个判据**依赖结构标记**，
 *   隐含假设「只覆盖第一段 ⟺ 文件带 `AVIX`」。但**老工具/Bug 工具完全可能生成
 *   「多段 movi 却没写 `AVIX` 标记」或「单段但 `idx1` 只写了一部分」的文件** →
 *   **仍然漏判**。
 * - **v3（当前，正确）**：**直接量 `idx1` 的覆盖范围** ——
 *   用「`idx1` 最后一条目的 offset + size」换算成**绝对文件位置**，与**文件大小**比较。
 *   这是**事实判据**，不依赖任何结构标记，无论文件怎么写都能抓到。
 *   `AVIX`/`indx` 判据**保留**为辅助（`indx` 可让「多段」情形免于无谓重封装）。
 * - **v4（当前，v2.4.21）**：**有 `indx` 就一律豁免** —— v3 仍有漏洞：
 *   它把「没有 `idx1`」直接判成「需重建」，**忽略了 `indx` 本身就能提供 seek**。
 *   🔴 用户给的 **1.19GB 真实样本**正好命中：**有多段 `AVIX` + 有 2 个 `indx` + 完全没有 `idx1`**
 *   （`AVIX` 在 **1.07GB** 处 → 旧判据的「尾部 1MB 找 AVIX」**必然漏检**）。
 *   按 v3 会**白重封装 1.19GB**。→ 现在 `!hasOpenDmlIndex` 提到最外层。
 *
 * ⚠️ 实测参考（上游 3 个真实样本）：`idx1` 覆盖 98.7%~99.9%、无 `AVIX`/`indx` ——
 * 即**正常单段 AVI 的覆盖率接近 100%**，所以「覆盖率 < 90%」是个**很安全的判据**
 * （不会误伤正常文件，又能抓住只覆盖前半段的）。
 *
 * ## 设计约束
 * - **纯逻辑、零 Android 依赖**（只吃 `ByteArray`）→ 可在纯 JVM 单测里跑。
 *   文件 IO 与「文件大小/尾部起始偏移」由调用方提供（`VRPlayerScreen`）。
 * - **不信任 chunk 长度**：`size` 字段来自文件内容，所有偏移都做边界检查。
 * - **判不出来时一律返回「没问题」**（`indexCoversToEnd = true`）——
 *   宁可漏修也不能误修（重封装要读写一整份文件，大文件几十秒）。
 * - ⚠️ 本类**不判断视频编码**（历史上用于识别「AVI 内的 AV1」的逻辑已按用户要求整体移除）。
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
         * 是否存在**多个 `movi` 段**（`LIST … AVIX`）—— **结构性旁证**。
         *
         * ⚠️ 它只是旁证：没有它**不代表** `idx1` 就完整（老工具可能不写 `AVIX`）。
         * 真正说了算的是 [indexCoversToEnd]。
         */
        val hasMultiSegment: Boolean = false,
        /**
         * `idx1` 是否**覆盖到文件尾**（v2.4.19 二轮新增的**事实判据**）。
         *
         * ⚠️ **默认 true** —— 判不出来时视为「没问题」，绝不因此触发重封装。
         */
        val indexCoversToEnd: Boolean = true,
        /**
         * 第一个 `movi` 列表的**数据起点**（绝对文件偏移）；-1 = 未知（头部里没找到）。
         *
         * 供调用方换算 `idx1` 覆盖位置（[idx1CoversToEnd] 的相对基准要用它）。
         */
        val moviStart: Long = -1L
    ) {
        /**
         * 索引**不足以覆盖全片** → seek 会「跳到却不继续播 / 停在前段」→ 应重封装。
         *
         * ⚠️ 非 AVI 恒为 false：别的容器（mp4/mkv）有自己的索引机制，
         * 不能用「AVI 的索引够不够」去推断它们。
         *
         * ## 🔴 v2.4.21 修正：**有 OpenDML 索引 ⇒ 一律不必重建**
         *
         * 起因是用户给的真实样本（**1.19GB**，多段 OpenDML）实测：
         * - **有 2 个 `indx`**（两个流各一，`size=56 / wLongsPerEntry=4`）；
         * - **完全没有 `idx1`**（全文件扫描只命中 2 处「字面量巧合」，周围都是随机字节）；
         * - **有 `AVIX` 多段**，位置在 **1.07GB** 处 —— 而旧判据只在**尾部 1MB** 找它，**必然漏检**。
         *
         * 旧写法 `!hasIndexChunk` 会把这种文件判成「完全没有索引 → 需重建」，
         * **但它的 `indx` 足以让 Media3Avi 正常 seek** → 会**白白重封装 1.19GB**（几分钟）。
         *
         * → 现在把 `!hasOpenDmlIndex` 提到最外层：**只要检测到 OpenDML 索引就不重建**
         *   （Media3Avi 明确优先用 `indx`：`if (riffType == AVIX || getIndexBoxList().size() > 0)`）。
         */
        val needsIndexRebuild: Boolean
            get() = isAvi
                // 🔴 有 OpenDML 索引 ⇒ 免检（它覆盖全片，Media3Avi 会优先用它）
                && !hasOpenDmlIndex
                && (
                    // ① 没有任何索引 —— 连第一段都 seek 不了
                    !hasIndexChunk
                        // ② 事实判据：idx1 实测覆盖不到文件尾（只覆盖第一段）
                        || !indexCoversToEnd
                        // ③ 结构性旁证：多段 movi
                        //    ⚠️ 它依赖「尾部窗口里能看到 AVIX」，而 AVIX 的位置随分段点而定
                        //    （实测样本在 1.07GB 处 → 尾部 1MB 抓不到）→ **只是旁证**，
                        //    真正的兜底是 ① 和 ②。
                        || hasMultiSegment
                    )
    }

    /**
     * 判定「是不是 AVI」+ 找 OpenDML 索引 / `movi` 起点所需的头部字节数。
     *
     * ⚠️ v2.4.19：**1KB → 64KB**。两个原因：
     * ① `indx` 是 `strl` 的子块、位于 `hdrl` 里，而 `hdrl`（含 avih + 各 strl/strf）**经常超过 1KB**；
     * ② 换算 `idx1` 覆盖位置需要 **`movi` 起点**（见 [findMoviStart]），它常在 1~40KB 处。
     * 读 1KB 会同时漏掉这两样 → 判据失效。
     */
    const val HEAD_BYTES = 64 * 1024

    /** 扫描 `idx1` / `AVIX` 时读取的文件尾部长度。 */
    const val TAIL_BYTES = 1024 * 1024

    /** 单个 `idx1` 条目固定 16 字节 → chunk 大小必须是它的整数倍（强判据，防误命中）。 */
    private const val IDX1_ENTRY_BYTES = 16L

    /** `idx1` / `indx` 大小上限（512MB），超过视为误命中。 */
    private const val IDX1_MAX_BYTES = 512L * 1024 * 1024

    /**
     * **RIFF chunk 长度**的合理上限（4GB）—— 即 32 位长度的自然上限。
     *
     * ## 🔴 为什么必须与 [IDX1_MAX_BYTES] 分开（v2.4.21 实测踩坑）
     * 用户那个 **1.19GB** 样本的 `LIST movi` 其 size = **1,064,676,266（1.06GB）**——
     * 那是**视频数据量**，GB 级完全正常。
     * 而 `findMoviStart()` 原先复用了 [IDX1_MAX_BYTES]（512MB）做上限检查 →
     * **把合法的 movi 判成「畸形长度」直接 return -1** →
     * 真机日志实测：`movi起点=-1`（本该是 4104）。
     *
     * 📌 **教训**：同一个「看起来差不多」的数值上限，
     * **不能跨语义复用**（索引大小 vs 数据块长度是两回事）。
     */
    private const val MAX_RIFF_CHUNK_BYTES = 4L * 1024 * 1024 * 1024

    /** `indx` 头最短长度（wLongsPerEntry 起算的固定字段）。 */
    private const val INDX_MIN_BYTES = 24

    /**
     * 覆盖率下限（百分比）。低于它才判定「索引不完整」。
     *
     * 取 90 是基于实测的安全值：正常单段 AVI 的 `idx1` 覆盖 98.7%~99.9%，
     * 而「只覆盖第一段」的文件覆盖率通常 <50%。中间留足余量，两个方向都不易错。
     */
    private const val COVERAGE_MIN_PERCENT = 90

    // ===================================================================
    // 一、容器判定 + OpenDML 索引 + movi 起点（头部）
    // ===================================================================

    /**
     * 头部字节是否像 AVI：`RIFF` + 4 字节长度 + `AVI `；同时探 `indx` 与 `movi` 起点。
     *
     * ⚠️ 即使调用方已按扩展名筛过仍需要它：扩展名会撒谎，
     * 一个名为 `.avi` 的文件完全可能是别的容器 —— 那样就不该按 AVI 的逻辑去重封装。
     */
    fun parseHeader(head: ByteArray): Probe {
        if (head.size < 12) return Probe(isAvi = false, hasIndexChunk = false)
        if (ascii(head, 0, 4) != "RIFF") return Probe(false, false)
        if (ascii(head, 8, 4) != "AVI ") return Probe(false, false)
        return Probe(
            isAvi = true,
            hasIndexChunk = false,
            hasOpenDmlIndex = containsOpenDmlIndex(head),
            moviStart = findMoviStart(head)
        )
    }

    /**
     * 在头部缓冲里找**第一个 `movi` 列表的数据起点**（绝对文件偏移）。
     *
     * 返回它的原因：`idx1` 的 offset 字段**相对第一个 `movi` 起点**（也有写绝对偏移的，
     * 见 [idx1CoversToEnd] 的双基准处理），换算覆盖位置必须用它。
     *
     * 实现：从偏移 12 起按 RIFF 顶层块步进，找 `LIST … movi`。
     * ⚠️ 头部缓冲可能**被截断**（文件比 64KB 小、或 movi 在 64KB 之外）→ 返回 -1。
     */
    fun findMoviStart(head: ByteArray): Long {
        if (head.size < 12) return -1L
        // ⚠️ 用 Long 累加：单个 chunk 可达 GB 级，Int 会溢出
        var i = 12L
        var guard = 0
        while (i + 12 <= head.size && guard++ < 4096) {
            val off = i.toInt()
            val id = ascii(head, off, 4)
            val sz = le32(head, off + 4)
            // ⚠️ 只判「越界/畸形」（le32 越界返回 -1）；
            //    **不能**用 IDX1_MAX_BYTES 当上限 —— movi 的 size 是数据量，GB 级正常
            //    （v2.4.21 实测：1.06GB 的 movi 被误判成畸形 → movi起点=-1）。
            if (sz < 0L || sz > MAX_RIFF_CHUNK_BYTES) return -1L
            if (id == "LIST" && ascii(head, off + 8, 4) == "movi") {
                return i + 8                                       // type 之后即数据起点
            }
            val step = 8L + sz + (sz and 1L)                       // chunk 按偶数对齐
            if (step <= 0L) return -1L
            i += step
        }
        return -1L
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
     * 校验：`size >= 24`、`wLongsPerEntry ∈ {2,4,8,16}`、`bIndexType ∈ {0,1}`。
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
    fun containsIndexMarker(buffer: ByteArray): Boolean = findIdx1(buffer) != null

    /** 定位 `idx1`：返回 (标记偏移, 载荷大小)；找不到返回 null。 */
    private fun findIdx1(buffer: ByteArray): Pair<Int, Long>? {
        if (buffer.size < 8) return null
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
                    return i to sz
                }
                // 命中字面量但结构不符 → 大概率是数据里的巧合，继续找；
                // 只容忍有限次，避免在畸形/超大缓冲上退化成全扫。
                nearMiss++
                if (nearMiss > 16) return null
            }
            i++
        }
        return null
    }

    // ===================================================================
    // 三、idx1 实际覆盖范围 —— v2.4.19 二轮的**核心判据**
    // ===================================================================

    /**
     * **`idx1` 是否覆盖到文件尾** —— 直接量出来的事实判据。
     *
     * ## 算法
     * 1. 在尾部缓冲里定位 `idx1`；取 `n = size / 16`
     * 2. 读**最后一条目**的 `offset`（+8）与 `size`（+12）
     * 3. 换算成绝对文件位置，**两种基准都算、取较大者**（宽松，避免误判）：
     *    - 绝对基准：`offset + size`
     *    - 相对基准：`moviStart + 8 + offset + size`
     *      （`idx1` 规范要求 offset 相对 `movi` 起点，但实测存在写绝对偏移的 muxer ——
     *        Media3Avi 也为此做了双基准判断）
     * 4. 覆盖率 ≥ [COVERAGE_MIN_PERCENT] 视为完整
     *
     * ## ⚠️ 分母用「数据区大小」（fileSize - moviStart），不是文件总大小
     * 头部（`hdrl`/`INFO` 等）与索引本身都在 `movi` 之外，用文件总大小当分母会**低估**覆盖率。
     * 而「只覆盖第一段」的判定恰恰依赖分母准确 —— 例如 1.6GB 文件只有第一段（1GB）被索引，
     * 用总大小算覆盖率是 62%，用数据区算也是 62%，但**与 movi 起点对齐后**才能正确处理
     * 「头部很大」的样本。
     *
     * ## ⚠️ 为什么用「末条目」而不是「条目数」
     * `idx1` 的条目含**音视频全部 chunk**，与 `avih.dwTotalFrames`（只数视频帧）
     * 没有固定比例关系（上游样本实测比值 2.7~290），拿条目数比对上界不可靠，
     * 而 OpenDML 文件的 `dwTotalFrames` 常被置 1（更不可靠）。
     * 直接看「索引指到的最后一个位置」才是**与 seek 能力直接相关**的量。
     *
     * ## ⚠️ 判不出来时返回 **true**（不触发重封装）
     * 以下情形都返回 true（保守）：
     * - 尾部缓冲里没有 `idx1`（大索引的标记可能在缓冲之外；`hasIndexChunk` 会另行判定）
     * - 末条目不在缓冲内（`idx1` 比尾部窗口还大）
     * - 字段畸形（长度超范围）
     *
     * @param tail 文件尾部缓冲（长度 = 实际读到的字节数）
     * @param tailFileOffset `tail[0]` 对应的**绝对文件偏移**（= 文件大小 - tail.size）
     * @param moviStart 第一个 `movi` 数据起点（[findMoviStart] 结果；-1 表示未知）
     * @param fileSize 文件总大小（字节）
     */
    fun idx1CoversToEnd(
        tail: ByteArray,
        tailFileOffset: Long,
        moviStart: Long,
        fileSize: Long
    ): Boolean {
        if (fileSize <= 0L) return true
        val (pos, size) = findIdx1(tail) ?: return true
        val n = size / IDX1_ENTRY_BYTES
        if (n <= 0L) return true
        val lastEntry = pos + 8 + ((n - 1) * IDX1_ENTRY_BYTES).toInt()
        if (lastEntry < 0 || lastEntry + 16 > tail.size) return true   // 末条目不在缓冲内
        val off = le32(tail, lastEntry + 8)
        val sz = le32(tail, lastEntry + 12)
        if (off < 0L || sz < 0L) return true

        // 🔴 双基准取较大者（宽松，避免因基准约定不同而误判）：
        //    · 绝对基准：offset 直接就是文件偏移（部分 muxer 这么写，Media3Avi 也做了兼容）
        //    · 相对基准：offset 相对第一个 movi 数据起点（**规范要求**）
        val absCandidate = off + sz
        val relCandidate = if (moviStart > 0L) moviStart + 8L + off + sz else -1L
        val covered = maxOf(absCandidate, relCandidate)

        // ⚠️ 分母 = 数据区（`movi` 之后的部分）；moviStart 未知时退回文件大小。
        //    实测正常单段 AVI 的 covered ≈ 数据区大小（覆盖 98.7%~99.9%）。
        val dataSize = if (moviStart > 0L) (fileSize - moviStart) else fileSize
        if (dataSize <= 0L) return true
        return covered >= dataSize * COVERAGE_MIN_PERCENT / 100L
    }

    // ===================================================================
    // 四、多段 movi（AVIX）存在性 —— 结构性旁证
    // ===================================================================

    /**
     * 尾部缓冲里是否存在**多段 `movi` 容器**（`LIST … AVIX`）。
     *
     * ⚠️ 这是**旁证**，不是充分判据（老工具可能多段却不写 `AVIX`）——
     * 真正说了算的是 [idx1CoversToEnd]。保留它是因为：
     * 「多段 + 无 `indx`」时**即使 `idx1` 恰好覆盖到尾部附近**，seek 也可能不准，
     * 多一层保险。
     *
     * ⚠️ 同样要做**结构性校验**：`AVIX` 必须出现在 `LIST` 的 **type 位置**
     * （即 `'LIST' | size(4) | 'AVIX'`），而不是随便 4 个字节。
     */
    fun containsMultiSegmentMarker(buffer: ByteArray): Boolean {
        if (buffer.size < 12) return false
        var i = 4  // 保证 i-8 >= 0
        while (i + 4 <= buffer.size) {
            if (buffer[i] == 'A'.code.toByte() && buffer[i + 1] == 'V'.code.toByte() &&
                buffer[i + 2] == 'I'.code.toByte() && buffer[i + 3] == 'X'.code.toByte()
            ) {
                // 前 8 字节应是 LIST + 合理的 size
                // ⚠️ v2.4.21：上限用 MAX_RIFF_CHUNK_BYTES（4GB）而不是 IDX1_MAX_BYTES ——
                //    `AVIX` 那个 LIST 的 size 同样是**数据量**（用户样本第一段就 1.06GB），
                //    用 512MB 会把真实的多段容器判成「不是 LIST」（实测正是如此）。
                if (i >= 8 &&
                    ascii(buffer, i - 8, 4) == "LIST" &&
                    le32(buffer, i - 4) in 4..MAX_RIFF_CHUNK_BYTES
                ) {
                    return true
                }
            }
            i++
        }
        return false
    }

    // ===================================================================
    // 五、字节小工具（全部带边界检查，绝不抛 IndexOutOfBounds）
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
