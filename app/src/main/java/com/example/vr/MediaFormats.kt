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
 * ## 分类依据（v2.1.242 全量实测重写，非猜测）
 *
 * 两条实测证据链，缺一不可：
 *
 * **(1) IJK(FFmpeg) 真实能力** —— 扫描 `app/libs/ijkplayer-k0.8.9-release.aar`
 * 里 `jni/arm64-v8a/libijkplayer.so`。它用的是**白名单式裁剪**的 FFmpeg：
 * config 横幅里有 `--disable-demuxers --enable-demuxer=...`，只放行了极少几个。
 * 实测注册的 demuxer 共 23 个（`ff_xxx_demuxer` 符号）：
 * ```
 * aac asf concat data flac flv hevc hls ijklas ijklivehook ivr live_flv
 * matroska mov mp3 mpegps mpegts mpegtsraw mpegvideo rdt rm rtsp webm_dash_manifest
 * ```
 * 实测注册的 decoder 共 28 个：
 * ```
 * aac aac_latm flac flv h263 h263p h264 hevc mpeg4 mp3* pcm_*
 * vp6 vp6a vp6f vp8 vp9
 * ```
 * ⚠️ **踩过的坑**：`.so` 里能搜到 `wmv3` / `vc1` / `mpeg2video` / `wmav2` 字符串，
 * 一度被误认为「IJK 有这些解码器」。实测上下文全是 `xan_wc4.rv30.wmv3.indeo2...`
 * 这种**连续短名列表** = FFmpeg 的 `codec_tag` / `AVCodecDescriptor` **名字表**，
 * **不是 `ff_xxx_decoder` 注册符号**。真正的注册列表里**没有** wmv3/vc1/mpeg2video。
 * → 结论：WMV/VC-1/MPEG-2 靠 **IJK 自己的 MediaCodec 通道硬解**
 * （so 里有完整的 `ffpipeline_android_media` / `MediaCodec_*` 符号，
 * 而 FFmpeg 自带 hwaccel 被 `--disable-hwaccel` 关了，走的是 IJK 自研 MediaCodec 链路）。
 * 这也解释了 `video/mpeg2` 会出现在 so 的 MIME 表里。
 *
 * ⚠️ **关键发现**：IJK 里 **没有 `ff_avi_demuxer` / `ff_riff_demuxer`**（0 命中），
 * 搜到的 82 次 `avi` 全是 FFmpeg 的 `avio_*` I/O 函数名，与 AVI 容器无关。
 * → **AVI 不能交给 IJK**，必须靠 EXO。
 *
 * **(2) EXO(Media3 1.4.1) 真实能力** —— 从 `media3-extractor-1.4.1` 的 classes.jar
 * 枚举 `DefaultExtractorsFactory` 注册的 extractor：
 * ```
 * Avi Ac3 Ac4 Adts Amr Avif Bmp Flac Flv FragmentedMp4 Heif Jpeg
 * Matroska(MKV/WebM) Mp3 Mp4 Ogg Png Ps(mpg/vob) SingleSample
 * Subtitle Ts Wav Webp
 * ```
 * ⚠️ **没有 ASF/WMV 解析器、没有 RealMedia(RM/RMVB)、没有 ISO** →
 * 这三类只能交给 IJK。
 *
 * ## 由此得出的分组（务必保持与上表一致）
 * | 容器 | IJK | EXO | 归入 |
 * |---|---|---|---|
 * | mp4/m4v/mov/3gp/3g2 | ✅ mov | ✅ Mp4 | COMMON |
 * | mkv/webm | ✅ matroska | ✅ Matroska | COMMON |
 * | avi/divx | ❌ 无 demuxer | ✅ Avi | COMMON（**必须靠 EXO**，见 EXO_ONLY） |
 * | flv/f4v | ✅ flv | ✅ Flv | COMMON |
 * | ts/m2ts/mts | ✅ mpegts | ✅ Ts | COMMON |
 * | mpg/mpeg/vob/dat | ✅ mpegps | ✅ Ps | COMMON |
 * | ogv | ❌ 无 ogg | ✅ Ogg | COMMON（**必须靠 EXO**） |
 * | **wmv/asf** | ✅ asf | ❌ | IJK_ONLY |
 * | **rm/rmvb/ra** | ✅ rm | ❌ | IJK_ONLY |
 * | **iso** | ⚠️ 仅数据镜像 | ❌ | IJK_ONLY + 特殊处理 |
 * | mp3/flac/m4a/ogg/mka/wma… | 部分 | 部分 | AUDIO_ONLY |
 *
 * ## ⚠️ 三个集合的语义不同，允许重叠，勿"去重"掉
 * - [COMMON] / [IJK_ONLY] / [AUDIO_ONLY] 管的是「**放不放行**」（能不能点开）。
 * - [EXO_ONLY] 管的是「**能不能给 IJK**」（内核路由安全）。
 * - 所以 `ogg` 可以既在 AUDIO_ONLY（放行）又在 EXO_ONLY（禁路由到 IJK）——
 *   这不是冗余，是两件不同的事。曾经有人因为看到重叠而删掉一个，直接导致回归。
 */

/** 媒体类型判定的唯一入口。 */
object MediaFormats {

    // ===================== 容器扩展名白名单 =====================

    /**
     * **EXO 或 IJK 至少一方能直接吃**的常见容器（全量补齐版）。
     *
     * 分组依据见文件头的能力对照表。这里刻意按「同一底层格式的所有常见扩展名」
     * 收全，避免用户拿到 `m1v` / `m4v` / `mts` 这类少见写法时被白名单挡在门外。
     *
     * ⚠️ 集合里同时包含两类：
     *   - EXO + IJK 双通（mp4/mkv/flv/ts/mpg…）
     *   - **仅 EXO 通**（`avi` `divx` `ogv` `ogg` —— IJK 无对应 demuxer，
     *     若被路由到 IJK 会直接失败，见 [requiresIjk] 与 [isExoOnly]）
     */
    val COMMON = setOf(
        // —— MPEG-4 家族（IJK: ff_mov_demuxer / EXO: Mp4Extractor）——
        "mp4", "m4v", "mov", "3gp", "3g2", "3gpp", "3gpp2", "ismv", "f4v",
        // —— Matroska 家族（IJK: ff_matroska_demuxer / EXO: MatroskaExtractor）——
        "mkv", "webm",
        // —— AVI / RIFF（⚠️ 仅 EXO 有 AviExtractor，IJK 无 avi demuxer）——
        "avi", "divx",
        // —— FLV（IJK: ff_flv_demuxer / EXO: FlvExtractor）——
        "flv",
        // —— MPEG-TS（IJK: ff_mpegts_demuxer / EXO: TsExtractor）——
        "ts", "m2ts", "mts", "m2t", "tsv", "tsa", "tp", "trp",
        // —— MPEG-PS / Program Stream（IJK: ff_mpegps_demuxer / EXO: PsExtractor）——
        "mpg", "mpeg", "mpe", "m1v", "m2v", "mpv", "vob", "dat", "ps",
        // —— Ogg 视频（⚠️ 仅 EXO 有 OggExtractor，IJK 无 ogg demuxer）——
        "ogv",
        // —— Windows Media Center 录制（EXO 走 TsExtractor / PsExtractor 探测）——
        "wtv", "dvr-ms"
    )

    /**
     * **纯音频容器** —— 本项目播放器同样能播（`isSupportedMediaExtension` 放行）。
     *
     * 之所以从 [COMMON] 单列：`COMMON` 的语义是「视频容器」，
     * 而 `.mp3` / `.flac` / `.mka` / `.ogg` 这类塞进去会让概念含糊；
     * 需要严格「只要视频」的地方可以只看 [COMMON]。
     *
     * ⚠️ `ogg` / `oga` / `spx` 与 [EXO_ONLY] 有交集，这是**有意为之**：
     *   它们既是音频（本集合），也绝不能路由到 IJK（EXO_ONLY）。
     *   两个集合语义不同（一个管「放不放行」、一个管「能不能给 IJK」），
     *   所以允许重叠 —— 切勿因为看到重叠就去掉其中一个。
     */
    val AUDIO_ONLY = setOf(
        // Ogg 音频家族（EXO: OggExtractor；IJK 无 ogg demuxer）
        "ogg", "oga", "ogx", "ogm", "spx",
        // Matroska 音频（EXO: MatroskaExtractor / IJK: ff_matroska_demuxer）
        "mka",
        // MP4 家族音频（EXO: Mp4Extractor / IJK: ff_mov_demuxer）
        "m4a", "m4b",
        // 裸流 / 其他（EXO 有 Adts/Mp3/Flac/Wav/Amr/Ac3/Ac4 对应 extractor）
        "aac", "mp3", "flac", "wav", "amr", "awb", "ac3", "eac3", "opus",
        // WMA 是 ASF 容器内的音频，IJK 的 ff_asf_demuxer 能解
        "wma"
    )

    /**
     * **必须走 IJK（FFmpeg）内核**的容器 —— EXO 与系统解码都接不住。
     *
     * 判定依据（实测）：
     * - `wmv` / `asf`：ASF 容器。EXO 的 `DefaultExtractorsFactory` **没有 ASF 解析器**；
     *   系统 `MediaPlayer` 同样不认。IJK 有 `ff_asf_demuxer`，视频走 MediaCodec 硬解
     *   （so 内无 `ff_wmv3_decoder` 软件解码器，全靠硬解通道）、音频 WMA 走 MediaCodec。
     * - `rm` / `rmvb` / `ra`：RealMedia。EXO **没有 RealMedia 解析器**；
     *   IJK 有 `ff_rm_demuxer`（`ff_sipr_*` / `rv10~rv40` 名字表也在）。
     *   ⚠️ v2.1.241 原先把 `rm/rmvb` 错放在 COMMON，实测 EXO 打不开 → v2.1.242 修正。
     * - `iso`：光盘镜像。**注意能力边界** —— FFmpeg 只能读**未加密的 UDF / ISO9660
     *   *数据*镜像**；DVD-Video 的 `.VOB` + `VIDEO_TS.IFO` 有 CSS 加密与
     *   导航（IFO）结构、蓝光有 BDMV 结构与 AACS，这些**都不在本项目能力范围内**，
     *   会走 [NEEDS_SPECIAL_HANDLING] 给出明确提示，而不是黑屏。
     */
    val IJK_ONLY = setOf(
        "wmv", "asf", "wmvhd",           // ASF 家族（.wmvhd 是老高清 WMV 的写法）
        "rm", "rmvb", "ra", "ram", "rmhd", // RealMedia 家族
        "iso"                            // 光盘镜像
    )

    /**
     * **只有 EXO 能开、绝不能路由到 IJK** 的容器。
     *
     * 这是 v2.1.242 新增的「反向白名单」。IJK 的 FFmpeg 是裁剪版，
     * **没有 riff/avi demuxer、没有 ogg demuxer**（实测 0 命中），
     * 所以 `avi` / `divx` / `ogv` / `ogg` 一旦被送到 IJK 必然失败。
     *
     * 用途：内核自动路由时**跳过**这些，即使用户手动设了 IJK 也保持原核。
     * 早先用「凡是常见格式就切 IJK」的一刀切会把这些格式**从能播改成不能播**，
     * 这是必须避免的回归。
     */
    val EXO_ONLY = setOf(
        "avi", "divx",
        "ogv", "ogg", "oga", "ogx", "ogm", "spx"
    )

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

    /**
     * 是否是本应用**放行的媒体容器**（视频 + 纯音频 + 仅 IJK 支持的那几种）。
     *
     * 用途：SMB / FTP 列表的「这一项能不能点开」、选择器返回结果的兜底判定。
     * 名字保留 `isSupportedVideoExtension` 是历史原因（v2.1.241 引入时只考虑视频），
     * 语义已扩展为「媒体容器」；需要**严格只要视频**时请用 [isSupportedVideoOnly]。
     */
    fun isSupportedVideoExtension(ext: String): Boolean =
        ext in COMMON || ext in IJK_ONLY || ext in AUDIO_ONLY

    /** 只判「视频容器」（不含纯音频）。 */
    fun isSupportedVideoOnly(ext: String): Boolean =
        ext in COMMON || ext in IJK_ONLY

    /** 该容器是否**必须**交给 IJK，EXO / 系统解码接不住。 */
    fun requiresIjk(ext: String): Boolean = ext in IJK_ONLY

    /**
     * 该容器是否**只能**由 EXO 打开（IJK 的裁剪版 FFmpeg 没有对应 demuxer）。
     *
     * 见 [EXO_ONLY] 的说明。内核自动路由必须用它做**排除**，
     * 否则会把本来能播的 avi/ogv 路由到 IJK 变成不能播。
     */
    fun isExoOnly(ext: String): Boolean = ext in EXO_ONLY

    /**
     * 该容器是否值得「自动切换到 IJK」。
     *
     * = 必须走 IJK，且**不是**只有 EXO 能开的那几种。
     * 两者互斥（IJK_ONLY 与 EXO_ONLY 无交集），这里写成显式判据是为了
     * 让调用点读起来一目了然，也便于将来任一侧加成员时行为仍然正确。
     */
    fun shouldAutoRouteToIjk(ext: String): Boolean =
        requiresIjk(ext) && !isExoOnly(ext)

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
