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
 * 这三类只能交给「完整 FFmpeg」。
 *
 * **(3) MPV 真实能力（v2.1.243 补充）** —— libmpv 自带**完整** libavcodec/libavformat
 * （10 个 so，avcodec+avformat+avfilter 齐全），因此**能解** IJK 裁剪版解不了的一切：
 * `wmv/asf`（wmv2/vc1/wmav2）、`rm/rmvb`（rv10~rv40）、以及各种冷门编码。
 * → 它是本项目唯一的「万能兜底内核」。**代价**：native 库按需下载（约 36MB，
 *   见 `MpvLibLoader`），未安装时会明确提示而不是黑屏。
 *
 * ## 由此得出的分组（务必保持与上表一致）
 * | 容器 | IJK | EXO | MPV | 归入 |
 * |---|---|---|---|---|
 * | mp4/m4v/mov/3gp/3g2 | ✅ mov | ✅ Mp4 | ✅ | COMMON |
 * | mkv/webm | ✅ matroska | ✅ Matroska | ✅ | COMMON |
 * | avi/divx | ❌ 无 demuxer | ✅ Avi | ✅ | COMMON（**必须靠 EXO**，见 EXO_ONLY） |
 * | flv/f4v | ✅ flv | ✅ Flv | ✅ | COMMON |
 * | ts/m2ts/mts | ✅ mpegts | ✅ Ts | ✅ | COMMON |
 * | mpg/mpeg/vob/dat | ✅ mpegps | ✅ Ps | ✅ | COMMON |
 * | ogv | ❌ 无 ogg | ✅ Ogg | ✅ | COMMON（**必须靠 EXO**） |
 * | **wmv/asf** | ❌ 无 wmv2/wmav2 解码器 | ❌ | ✅ | **仅 MPV**（见 [shouldRouteToMpv]） |
 * | **rm/rmvb/ra** | ❌ 无 rv 系解码器 | ❌ | ✅ | **仅 MPV** |
 * | **iso** | ⚠️ 仅数据镜像 | ❌ | ⚠️ | IJK_ONLY + 特殊处理 |
 * | mp3/flac/m4a/ogg/mka/wma… | 部分 | 部分 | ✅ | AUDIO_ONLY |
 *
 * ⚠️⚠️ **上表 wmv/rm 两行的「❌」是 v2.1.243 才查实的** —— v2.1.241/242 曾以为
 * 「IJK 有 asf/rm demuxer 就能解」，实测被证伪。详见 [IJK_ONLY] 的注释。
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
     * **AVI / RIFF 家族**的扩展名（v2.4.9 抽出为单一常量）。
     *
     * ## 为什么值得单独抽一个常量
     *
     * AVI 是本项目**唯一需要额外读文件字节**才能决定 seek 策略的家族：
     * 老设备导出的 AVI 常常**没有 `idx1` 索引**，此时 seek 会「跳到目标帧却不继续播放」
     * —— 需要靠 [AviRiffProbe] 提前探明并触发重封装（见 `VRPlayerScreen`）。
     *
     * 于是「哪些扩展名要走这一步探测」就成为一个**独立的判定**，
     * 而它必须与白名单里的 AVI 项**永远一致** —— 若两处各写一份，
     * 改了白名单忘了改探测判据，就会出现「加了新扩展名但探测不生效」。
     * 这类「同一份数据两处登记」是本项目反复踩过的坑（见记忆库硬规则），
     * 所以直接收成一个常量，下面 [COMMON] / [EXO_ONLY] 都引用它。
     */
    val AVI_EXTS = setOf("avi", "divx")

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
    val COMMON = AVI_EXTS + setOf(
        // —— MPEG-4 家族（IJK: ff_mov_demuxer / EXO: Mp4Extractor）——
        "mp4", "m4v", "mov", "3gp", "3g2", "3gpp", "3gpp2", "ismv", "f4v",
        // —— Matroska 家族（IJK: ff_matroska_demuxer / EXO: MatroskaExtractor）——
        "mkv", "webm",
        // —— ⚠️ AVI / RIFF 由开头的 [AVI_EXTS] 并入，此处不再重复列出 ——
        //    仅 EXO 有 `AviExtractor`（IJK 实测无 `ff_avi_demuxer`）；
        //    无 `idx1` 索引的 AVI 需重封装才能正常拖动，见 [AviRiffProbe]。
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
     * **只有「完整 FFmpeg」才可能打开**的容器 —— EXO 与系统解码都接不住。
     *
     * ⚠️⚠️ **v2.1.243 重要更正（此前 v2.1.241/242 的论断是错的）**
     *
     * 曾经认为「IJK 有 `ff_asf_demuxer` / `ff_rm_demuxer`，所以这些容器交给 IJK 就行」。
     * 实测（MuMu + 真机日志）证明**这是错的**：**本项目那个 IJK 构建根本解不了**，
     * 它只会在软解器查找阶段失败，压根走不到任何兜底：
     *
     * ```
     * No codec could be found with id 86024     ← wmav2 (AV_CODEC_ID_WMAV2)
     * No codec could be found with id 18        ← wmv2  (AV_CODEC_ID_WMV2)
     * Failed to open file 'pipe:153' or configure filtergraph
     * IjkMediaPlayer: Error (-10000,0)
     * ```
     *
     * 根因（从 `libijkplayer.so` 的编译横幅与符号表实测得到）—— **三条路全堵**：
     *  1. **软解器被裁掉**：横幅是 `--disable-decoders --enable-decoder=aac/flv/h264/
     *     mp3-star/vp6f/flac/hevc/vp8/vp9/pcm-star` —— **没有 wmv2/wmav2/vc1**。
     *     （上行的 `mp3-star` / `pcm-star` 原文是 `mp3` 与 `pcm` 后接通配符星号；
     *      此处刻意写成 star 是为了避免出现会提前闭合块注释的两字符序列。）
     *  2. **FFmpeg hwaccel 全关**：横幅 `--disable-hwaccels`，编译期整体禁用。
     *  3. **IJK 自建 MediaCodec 通道**：符号齐全（`AMediaCodec*` 163 处），但它的
     *     codec→MIME 映射表**只有 12 项**（`video/avc`、`video/hevc`、`video/mp4`、
     *     `video/mp4v-es`、`video/mpeg2`、`video/webm`、`video/x-matroska`、`audio/aac`…），
     *     **没有 `video/x-ms-wmv` / `video/x-msvideo` / `audio/x-ms-wma`**，
     *     且这些表**编译进 so，改不了**。所以 `mediacodec=1` 开了也是徒劳。
     *
     * 结论：`wmv/asf/rm/rmvb` 这类**只有 MPV 内核能解**（MPV 自带完整 libavcodec）。
     * 路由与降级请看 [shouldRouteToMpv]；`IJK_ONLY` 这个名字保留是历史原因，
     * 语义已变为「IJK 的名字占位」，**不要再据此把片源交给 IJK**。
     *
     * - `iso`：光盘镜像。**注意能力边界** —— FFmpeg 只能读**未加密的 UDF / ISO9660
     *   *数据*镜像**；DVD-Video 的 `.VOB` + `VIDEO_TS.IFO` 有 CSS 加密与
     *   导航（IFO）结构、蓝光有 BDMV 结构与 AACS，这些**都不在本项目能力范围内**，
     *   会走 [NEEDS_SPECIAL_HANDLING] 给出明确提示，而不是黑屏。
     */
    val IJK_ONLY = setOf(
        "wmv", "asf", "wmvhd",           // ASF 家族（.wmvhd 是老高清 WMV 的写法）
        "rm", "rmvb", "ra", "ram", "rmhd", // RealMedia 家族
        "iso",                           // 光盘镜像
        "ivf"                            // ⚠️ v2.1.248 新增：AV1 裸流（IVF 容器）
    )

    /**
     * **AV1 裸流容器**（v2.1.248 新增）。
     *
     * 为什么单列：`.ivf` 是本项目**唯一**会被归入 [IJK_ONLY]（= 仅 MPV 能解）
     * 的「现代编码」格式 —— 它既不是老旧的 WMV/RM，也和 `iso` 的特殊处理无关，
     * 分开记录便于将来审计时一眼看出「MPV 兜底集合里混进了一个 AV1 容器」。
     *
     * ⚠️⚠️ **实证依据（务必别再改动这里而不重新验证）**：
     *  - `androidx.media3:media3-extractor:1.4.1` 的 `classes.jar` 共 **453 个类**，
     *    搜 `Ivf` / `Obu` / `Av1` **全部零命中** → **EXO 没有 IVF 抽取器**，不可行。
     *  - MPV 的 `libavformat.so`（3.38 MB，全量 FFmpeg）在偏移 `@371556` 处有
     *    **`On2 IVF`** 这个字符串 —— 那正是 FFmpeg `ivf` demuxer 的 `long_name`
     *    （紧邻 `matroska,webm`等同级 demuxer 描述串）→ **MPV 可以开 `.ivf`**。
     *  - `.ivf` 文件内部是 `AV1` 编码 → MPV 侧有 `libdav1d` / `cbs_av1`（已实测存在）。
     *
     * ⚠️ **`obu`（裸 OBU 流）与 `av1`（裸 AV1 扩展名）故意不登记**：
     *    FFmpeg 没有 `.obu` 的 demuxer（`libavformat.so` 里 `obu_*` 全是
     *    `cbs_av1` 的语法元素名，不是 demuxer 名），登记了只会「能选中、打不开」。
     *    宁可不放行，也不要重蹈 v2.1.241「搜到名字就以为支持」的覆辙。
     */
    val AV1_RAW = setOf("ivf")

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
    val EXO_ONLY = AVI_EXTS + setOf(
        "ogv", "ogg", "oga", "ogx", "ogm", "spx"
    )

    /**
     * 该扩展名是否属于 **AVI / RIFF 家族**（v2.4.9）。
     *
     * 用途：只有返回 `true` 才值得去读 RIFF 头做进一步的编码判定
     * （见 [AviRiffProbe]）—— 对 mp4/mkv 做这套探测纯属浪费 IO。
     *
     * ⚠️ 判定必须走这里，**不要在调用点手写 `ext == "avi"`** ——
     * 那会漏掉 `divx`（同族扩展名），正是本项目「手写白名单」的老毛病。
     */
    fun isAviFamily(ext: String): Boolean = ext in AVI_EXTS

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
        ext in COMMON || ext in IJK_ONLY || ext in AUDIO_ONLY || ext in AV1_RAW

    /** 只判「视频容器」（不含纯音频）。 */
    fun isSupportedVideoOnly(ext: String): Boolean =
        ext in COMMON || ext in IJK_ONLY || ext in AV1_RAW

    /** 该容器是否**必须**交给 IJK，EXO / 系统解码接不住。 */
    fun requiresIjk(ext: String): Boolean = ext in IJK_ONLY && ext !in AV1_RAW

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
     *
     * ⚠️ **v2.1.243 提示**：由于 `IJK_ONLY` 现在装的其实都是「只有完整 FFmpeg
     * （= MPV）能解」的格式，本方法**实际会命中的正是 wmv/rm 那一批**。
     * 调用点（`VRPlayerScreen`）的正确顺序是：**先 [shouldRouteToMpv] 把这类
     * 派给 MPV**，本方法作为「MPV 不可用时的次要尝试」。
     * 换句话说：将来若 IJK 换成了全量 FFmpeg 构建，本方法才真正独立起作用。
     */
    fun shouldAutoRouteToIjk(ext: String): Boolean =
        requiresIjk(ext) && !isExoOnly(ext)

    /**
     * 该容器是否**只有 MPV 内核能解**（v2.1.243 新增）。
     *
     * 详见 [IJK_ONLY] 头部那段 v2.1.243 更正 —— 简言之：`wmv/asf/rm/rmvb` 这些
     * 「完整 FFmpeg 才有的格式」，**本项目的 IJK 构建解不了**（软解器被裁、
     * 硬解全关、MediaCodec 白名单无 WMV），EXO 与系统解码也接不住。
     * 唯一出路是 MPV（自带完整 libavcodec）。
     *
     * ⚠️ 判据里**排除 `iso`**：ISO 走 [NEEDS_SPECIAL_HANDLING] 的魔数探测流程
     * （可能是加密镜像/数据镜像），不应被「路由到 MPV」抢先接管。
     *
     * 用途：内核路由与失败降级都据此决定是否改用 MPV。
     */
    fun requiresMpv(ext: String): Boolean =
        (ext in IJK_ONLY || ext in AV1_RAW) && ext != "iso"

    /**
     * 该容器是否值得「自动切换到 MPV」。
     *
     * 与 [shouldAutoRouteToIjk] 互斥（前者管「只有 MPV 能解」、后者管「只有 IJK 能解」），
     * 调用方按顺序判：先 [requiresMpv] 再 [shouldAutoRouteToIjk]。
     *
     * 之所以单独提供一个 `should...` 名字而不是直接暴露 [requiresMpv]：
     * 与 [shouldAutoRouteToIjk] 保持同名风格，将来若 MPV 侧要加例外（如某格式
     * EXO 也能凑合）只需改这一处，调用点不动。
     */
    fun shouldRouteToMpv(ext: String): Boolean = requiresMpv(ext)

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
