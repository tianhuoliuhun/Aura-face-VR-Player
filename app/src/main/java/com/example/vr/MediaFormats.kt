package com.example.vr

import android.net.Uri

/**
 * 媒体容器/扩展名的**单一判定入口** —— v2.1.241「WMV 与 ISO 兼容」。
 *
 * ## 为什么要有这个文件
 * 项目里原本至少 3 处各自手写扩展名白名单（SMB 浏览、系统选择器、未来的 FTP/SFTP），
 * 一旦新增一种容器就要满仓库找，漏一处就是「有的入口能看到、有的看不到」。
 * 这与本项目头号事故源「同一功能两份 UI」是同一类问题 ——
 * 所以这里把判定收成**一份**，所有入口都调它。
 *
 * ## 分类依据（实测，非猜测）
 * 用 `llvm-readelf` / 二进制字符串扫描 `app/libs/ijkplayer-k0.8.9-release.aar` 里
 * `jni/arm64-v8a/libijkplayer.so`，确认 IJK（FFmpeg）**确实注册**了：
 *   - 解码器：`wmv3` / `wmv2` / `wmv1` / `vc1` / `wmav1` / `wmav2` / `wmapro` / `mpeg2video`
 *   - 解复用：`ff_asf_demuxer`（含 `ff_asf_header` / `ff_asf_stream_header` 等符号）
 *   - 镜像：`udf` / `UDF`（FFmpeg 的 UDF/ISO9660 读取器）
 * 而 ExoPlayer(Media3) 的 `DefaultExtractorsFactory` **不含 ASF 解析器**，
 * `android.media.MediaPlayer` 同样不认 ASF 与 ISO 镜像 → 这两个容器
 * 只能交给 IJK。
 */

/** 媒体类型判定的唯一入口。 */
object MediaFormats {

    // ===================== 容器扩展名白名单 =====================

    /** Exo / 系统解码 / IJK 都能直接吃的常见容器。 */
    val COMMON = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "flv", "ts", "m2ts", "mts",
        "3gp", "mpg", "mpeg", "rmvb", "rm", "ogv", "divx", "f4v", "wtv"
    )

    /**
     * **必须走 IJK（FFmpeg）内核**的容器。
     *
     * - `wmv` / `asf`：ASF 容器，内含 WMV3/VC-1 视频 + WMA 音频。
     *   EXO 不解析 ASF 容器；系统解码同样不认。IJK 实测带全套解码器与 `ff_asf_demuxer`。
     * - `iso`：光盘镜像。**注意能力边界** —— FFmpeg 只能读**未加密的 UDF / ISO9660
     *   *数据*镜像**；DVD-Video 的 `.VOB` + `VIDEO_TS.IFO` 有 CSS 加密与
     *   导航（IFO）结构、蓝光有 BDMV 结构与 AACS，这些**都不在本项目能力范围内**，
     *   会走 [NEEDS_SPECIAL_HANDLING] 给出明确提示，而不是黑屏。
     * - `vob` / `m2ts`（原盘 TS 流）：FFmpeg 可解，EXO 也能解部分，
     *   但为稳妥一并归入 IJK 优先。
     */
    val IJK_ONLY = setOf("wmv", "asf", "iso", "vob")

    /**
     * **本项目无法保证播放**、应当明确告知用户而不是静默失败的容器。
     *
     * 目前是 ISO 的两个特例：加密（CSS / AACS）与导航结构（IFO / BDMV）。
     * 判定不能靠扩展名（两者扩展名都是 `.iso`），只能靠**首部魔数探测**，
     * 见 [inspectIso]。
     */
    val NEEDS_SPECIAL_HANDLING = setOf("iso")

    // ===================== 扩展名提取 =====================

    /**
     * 从 URI / 路径取小写扩展名（不含点），取不到返回空串。
     *
     * ⚠️ `content://` 的 path 常常是纯数字 id（如 `content://media/external/video/media/1234`），
     * 此时拿不到扩展名 —— 调用方需退回用 `contentResolver.getType(uri)` 的 MIME 判断，
     * 见 [looksLikeVideoUri]。
     */
    fun extensionOf(uri: Uri?): String {
        val path = uri?.path ?: return ""
        // 去掉 query 后再取（file:///a/b.wmv?x=1 这类）
        val seg = path.substringAfterLast('/')
        val ext = seg.substringAfterLast('.', "")
        return ext.lowercase().takeIf { it.isNotEmpty() && it.length <= 5 } ?: ""
    }

    /** 从文件名（SMB / FTP 列表项）取扩展名。 */
    fun extensionOfName(name: String?): String {
        val n = name ?: return ""
        val ext = n.substringAfterLast('.', "")
        return ext.lowercase().takeIf { it.isNotEmpty() && it.length <= 5 } ?: ""
    }

    // ===================== 能力判定 =====================

    /** 是否是本应用放行的视频容器（含仅 IJK 支持的那几种）。 */
    fun isSupportedVideoExtension(ext: String): Boolean =
        ext in COMMON || ext in IJK_ONLY

    /** 该容器是否**必须**交给 IJK，EXO / 系统解码接不住。 */
    fun requiresIjk(ext: String): Boolean = ext in IJK_ONLY

    /**
     * 该 URI 是否需要「特殊处理提示」（ISO 镜像）。
     *
     * ⚠️ 只看扩展名，**不做 IO** —— 这个方法会在 Compose 重组路径上被调用，
     * 绝不能在里面读文件。真正的镜像内容探测在 [inspectIso]（IO 线程）。
     */
    fun needsSpecialHandling(uri: Uri?): Boolean =
        extensionOf(uri) in NEEDS_SPECIAL_HANDLING

    /**
     * 该 URI 看起来是不是视频（无法从扩展名判断时的兜底）。
     *
     * 用途：系统 `PickVisualMedia` 返回的 `content://` 常常没有扩展名，
     * 只能靠 MIME。`video/x-ms-wmv` 这类非标准 MIME 也在 `video/` 前缀内，
     * 所以 `startsWith("video/")` 是够用的判据。
     *
     * ⚠️ `mimeType` 允许传 null/空（某些 provider 不返回），此时**不武断判成非视频**，
     * 而是交给调用方按「扩展名也拿不到 → 按视频处理」的既有策略走，
     * 避免把 WMV 误判成图片而走位图解码路径（那才是真的崩）。
     */
    fun looksLikeVideoUri(uri: Uri?, mimeType: String?): Boolean {
        if (!mimeType.isNullOrEmpty() && mimeType.startsWith("video")) return true
        val ext = extensionOf(uri)
        if (ext.isNotEmpty()) return isSupportedVideoExtension(ext)
        return false
    }

    // ===================== ISO 镜像内容探测 =====================

    /** [inspectIso] 的结论。 */
    enum class IsoKind {
        /** UDF / ISO9660 数据镜像：FFmpeg 可以直接按镜像读取里面的视频文件。 */
        DATA_IMAGE,

        /** 疑似 DVD-Video（有 `VIDEO_TS` 目录或 `VIDEO_TS.IFO`）。本项目**不支持**。 */
        DVD_VIDEO,

        /** 疑似蓝光（有 `BDMV` 目录或 `index.bdmv`）。本项目**不支持**。 */
        BLU_RAY,

        /** 读不出来（不是镜像 / 权限不足 / 加密）。 */
        UNKNOWN
    }

    /**
     * 探测 ISO 镜像的种类。
     *
     * **必须在 IO 线程调用**（最多读 2MB）。
     *
     * ## 探测原理
     * ISO9660 的**主卷描述符（PVD）固定在第 16 个逻辑扇区**（扇区 2048 字节，
     * 偏移 16 × 2048 = 32768）。PVD 里偏移 156 处是「根目录记录」，
     * 其偏移 2 处（即全文件偏移 `32768 + 156 + 2`）是根目录的 **Extent Location**
     * （4 字节小端）= 根目录所在扇区号。
     *
     * 于是：
     *  ① 读 32KB+ 头部，确认偏移 1 处有魔数 `CD001`（ISO9660 标识）；
     *  ② 解析根目录扇区号，读该扇区（2048 字节）；
     *  ③ 在目录记录里逐条扫 `file identifier`，看是否含 `VIDEO_TS` / `BDMV`
     *     这类**小写不敏感**的目录名。
     *
     * DVD-Video 与蓝光镜像一定带这些目录；普通数据镜像不会。
     *
     * ## 为什么不干脆交给 FFmpeg 报错
     * FFmpeg 遇到 DVD-Video 镜像会尝试当数据镜像读，**可能**恰好读到某个
     * `.VOB` 并开始播放 —— 但那是**无导航的裸流**：没有章节、可能选到错误的
     * 片段（花絮/菜单循环），而且是 CSS 加密盘就直接失败。
     * 与其给用户一个「能播但播的是花絮」的结果，不如提前判定并说明。
     */
    fun inspectIso(open: () -> java.io.InputStream?): IsoKind {
        return try {
            open()?.use { ins ->
                val head = ByteArray(0x8000 + 2048) // 32KB 头 + 1 个扇区余量
                var read = 0
                while (read < head.size) {
                    val n = ins.read(head, read, head.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < 0x8006) return IsoKind.UNKNOWN
                // 魔数 'CD001' 位于逻辑扇区 16 的偏移 1 处
                val magicOk = head[0x8000 + 1] == 'C'.code.toByte() &&
                    head[0x8000 + 2] == 'D'.code.toByte() &&
                    head[0x8000 + 3] == '0'.code.toByte() &&
                    head[0x8000 + 4] == '0'.code.toByte() &&
                    head[0x8000 + 5] == '1'.code.toByte()
                if (!magicOk) {
                    // UDF-only 镜像（少数蓝光）没有 ISO9660 PVD，就只能靠魔数兜底
                    return detectByMagicOnly(head, read)
                }
                // PVD 内偏移 156 是根目录记录；其偏移 2 是 extent LBA（小端 4 字节）
                val rootRecOff = 0x8000 + 156
                val lba = le32(head, rootRecOff + 2)
                val rootDirOff = lba * 2048L
                if (rootDirOff + 2048 <= read) {
                    val dir = ByteArray(2048)
                    System.arraycopy(head, rootDirOff.toInt(), dir, 0, 2048)
                    // ⚠️ 已经读到根目录了 —— 此时若没看到 VIDEO_TS / BDMV，
                    //    结论就是「数据镜像」，**不能**再要求根目录里出现 "CD001"
                    //    字样（那只是 PVD 的标识，根目录里不会有）。
                    classifyRootDir(String(dir, Charsets.ISO_8859_1))
                        ?: IsoKind.DATA_IMAGE
                } else {
                    // 根目录在 33KB 之外：没读到就**不能**断言是数据镜像，
                    // 只能退回字符串扫描 + 保守判定（见 detectByMagicOnly 的说明）。
                    detectByMagicOnly(head, read)
                }
            } ?: IsoKind.UNKNOWN
        } catch (t: Throwable) {
            IsoKind.UNKNOWN
        }
    }

    /** 只扫头部字符串（PVD / 根目录读不到时的兜底）。 */
    private fun detectByMagicOnly(head: ByteArray, read: Int): IsoKind {
        val s = String(head, 0, read, Charsets.ISO_8859_1).uppercase()
        return when {
            s.contains("BDMV") || s.contains("INDEX.BDMV") || s.contains("AACS") -> IsoKind.BLU_RAY
            s.contains("VIDEO_TS") || s.contains("VIDEO_TS.IFO") -> IsoKind.DVD_VIDEO
            s.contains("CD001") -> IsoKind.DATA_IMAGE
            else -> IsoKind.UNKNOWN
        }
    }

    /**
     * 在根目录记录里找特征名。
     * 返回 null 表示**没有**发现 DVD/蓝光特征（即普通数据镜像）。
     */
    private fun classifyRootDir(dir: String): IsoKind? {
        val up = dir.uppercase()
        return when {
            up.contains("BDMV") || up.contains("INDEX.BDMV") || up.contains("AACS") -> IsoKind.BLU_RAY
            // VTS_01_1 是 DVD 的正片文件名前缀，比 VIDEO_TS 更可靠（有些数据盘
            // 恰好也有个叫 VIDEO_TS 的普通文件夹，但不会有 VTS_01_1.VOB）
            up.contains("VIDEO_TS.IFO") || up.contains("VTS_01_1") || up.contains("VIDEO_TS") ->
                IsoKind.DVD_VIDEO
            else -> null
        }
    }

    /** 小端 32 位读取（ISO9660 的 extent location 是小端双份存储，取前 4 字节即可）。 */
    private fun le32(b: ByteArray, off: Int): Long {
        if (off + 4 > b.size) return 0L
        return (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)
    }
}
