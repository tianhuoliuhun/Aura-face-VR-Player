package com.example.vr

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.example.R

/**
 * 弹幕素材来源模式（v2.4.1）
 *
 * 背景：弹幕的输入原本只有**截图**（视觉模型看画面）。但本项目自带 ASR 实时字幕，
 * 那批台词文本已经在内存里（`realtimeCues`）—— 让它一起参与，弹幕才能"接住剧情"：
 * 光看画面只能吐槽构图与人物，加上台词才能对台词本身做出反应（这才是弹幕的灵魂）。
 *
 * 用户 2026-10-06 定案：**做成开关让用户自己选**，而不是替他决定。
 */
enum class DanmuSourceMode(@StringRes val labelRes: Int, val id: Int) {
    /** 画面 + 台词（默认）：视觉模型同时收到截图与当前时间附近的台词 */
    IMAGE_AND_SUBTITLE(R.string.danmu_source_both, 0),

    /** 仅画面：与 v2.4.0 行为一致 */
    IMAGE_ONLY(R.string.danmu_source_image, 1),

    /** 仅台词：不发图，只把台词交给模型（成本低、速度快，但看不到画面） */
    SUBTITLE_ONLY(R.string.danmu_source_subtitle, 2);

    companion object {
        /** id 失配时安全回落（防旧 prefs 脏 id） */
        fun fromId(id: Int): DanmuSourceMode = values().find { it.id == id } ?: IMAGE_AND_SUBTITLE
    }
}

/**
 * 弹幕**内容来源**类型（v2.4.7）
 *
 * ## 与 [DanmuSourceMode] 的区别（⚠️ 极易混淆，务必分清）
 * 这是**两个完全正交的维度**，不要合并：
 * - [DanmuSourceMode]（v2.4.1）= **AI 生成时「喂什么素材给模型」**：
 *   截图 / 台词 / 两者。它只在「由 AI 生成」这条路里起作用。
 * - [DanmuSourceType]（本枚举）= **弹幕内容从哪来**：AI 现场生成 / 用户导入文件。
 *   若选了「导入」，[DanmuSourceMode] 就完全不参与（不给模型发任何请求）。
 *
 * 之所以用新枚举而不是往 [DanmuSourceMode] 里加两个档位：那会让「喂素材」与「内容来源」
 * 两个维度纠缠在一个枚举里，之后想加「AI 也导入文件同时显示」就无法表达了。
 *
 * 用户 2026-10-06 定案：**二选一、手动切换**（不做叠加、不做导入优先兜底）——
 * 叠加会让同屏条数失控，兜底会让"为什么我导入的没出现"变得难排查。
 */
enum class DanmuSourceType(@StringRes val labelRes: Int, val id: Int) {
    /** AI 现场生成（默认，与 v2.4.6 行为一致） */
    AI(R.string.danmu_source_type_ai, 0),

    /** 本地导入文件（B 站 XML / JSON） */
    IMPORT(R.string.danmu_source_type_import, 1);

    companion object {
        /** id 失配时安全回落（防旧 prefs 脏 id） */
        fun fromId(id: Int): DanmuSourceType = values().find { it.id == id } ?: AI
    }
}

/**
 * 弹幕颜色模式（v2.4.6）
 *
 * 背景：v2.3.0 起弹幕只有「全局单一颜色」——所有弹幕同一个色，视觉上比较单调，
 * 与真实弹幕网站的观感有差距（真人弹幕本身就五颜六色）。
 *
 * 用户 2026-10-06 定案：在「单一颜色」之外增加两种**逐条随机**模式，
 * 并给出真实弹幕站最常见的分布 —— **大部分白、少量彩色**（既不单调，也不花哨到看不清）。
 *
 * ⚠️ 随机色板刻意**不含黑色**：弹幕浮在视频画面上，黑字在暗场景里几乎看不见
 *    （且默认描边就是黑边，黑字+黑边 = 糊成一团）。
 *    字幕面板的 8 色里黑色是合理的（字幕有背景底框可衬），弹幕则不适合直接复用那套。
 */
enum class DanmuColorMode(@StringRes val labelRes: Int, val id: Int) {
    /** 单一颜色（默认）：全部弹幕用 textColorId 指定的那一个颜色 —— 与 v2.3.0 行为一致 */
    SINGLE(R.string.danmu_color_mode_single, 0),

    /** 完全随机：每条从 [DanmuConfig.DANMU_PALETTE] 里等概率取色 */
    RANDOM(R.string.danmu_color_mode_random, 1),

    /** 80% 白 + 其余随机：80% 概率纯白，20% 概率从彩色板里取 —— 最接近真实弹幕站观感 */
    MOSTLY_WHITE(R.string.danmu_color_mode_mostly_white, 2);

    companion object {
        /** id 失配时安全回落（防旧 prefs 脏 id） */
        fun fromId(id: Int): DanmuColorMode = values().find { it.id == id } ?: SINGLE
    }
}

/**
 * AI 弹幕配置（v2.2.0 / P1，v2.4.4 更换默认端点）
 *
 * 设计说明（与方案文档 `docs/DANMUAI_PLAN_C_IMPLEMENTATION_2026-10-06.md` 对应）：
 * - **API 协议固定为 OpenAI 兼容**（用户 2026-10-06 定案）：POST {baseUrl}/chat/completions
 * - **v2.4.4 默认端点改为 AMD Radeon 开发者平台**（用户 2026-10-06 指定）：
 *     · 端点 https://developer.amd.com.cn/radeon/api/v1
 *     · 模型 id `MiMo-V2.6-Flash`（⚠️ 大小写敏感，小写会 404）
 *     · 该平台是聚合网关，`/v1/models` 实测返回 9 个模型，其中支持图像输入的：
 *         - `MiMo-V2.6-Flash`（默认）
 *         - `DeepSeek-V4.1-Flash`
 *         - `DeepSeek-V4-Flash-Vision-Exp`
 *         - `Qwen3.8-27B`
 *         - `Qwen3.8-Flash-Next`
 *       （`DeepSeek-V4-Flash` / `GLM-5.3-Flash` / `MinerU2.5-Pro` / `MiniCPM5-2B` 为纯文本）
 *     · Base64 data URL 带图实测通过（192 KB 请求体正常返回 200）
 *
 * ⚠️ 与 `TranslationEngine.MIMO` 的区别：翻译引擎里的 MIMO 填的是
 *    `api.minimax.chat` + `abab6.5g-chat`（那是 MiniMax 平台，与小米 MiMo 不是一回事）。
 *    两者**互不复用**，弹幕视觉模型走本文件自己的默认值。
 *
 * ⚠️ 与翻译配置**有意分离**（方案文档 D1 决策）：
 *    翻译用文本模型、弹幕用视觉模型，复用同一份配置会互相污染。
 *
 * 参考 DanmuAI 的量级（`app/config_defaults.py` 实测）：
 * - `DEFAULT_IMAGE_MAX_WIDTH = 1024`     → 本项目同取 1024
 * - `DEFAULT_DANMU_PENDING_ENTRY_CAP = 300` → 本项目同取 300
 */
data class DanmuConfig(
    /** 总开关 */
    val isEnabled: Boolean = false,
    /** 视觉模型 API Key */
    val apiKey: String = "",
    /** OpenAI 兼容端点（不含 /chat/completions，由客户端拼接） */
    val baseUrl: String = DEFAULT_BASE_URL,
    /** 模型 id */
    val modelName: String = DEFAULT_MODEL,
    /** 人格提示词（默认原创，不照抄任何第三方人格文本） */
    val personaPrompt: String = DEFAULT_PERSONA,
    /** 截图识别间隔（秒）。低频是硬要求：glReadPixels 同步阻塞，且要省 token */
    val intervalSec: Int = DEFAULT_INTERVAL_SEC,
    /** 每批期望生成的弹幕条数 */
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    /** 送模型前缩放后的图片最大宽度（px） */
    val imageMaxWidth: Int = DEFAULT_IMAGE_MAX_WIDTH,
    /** JPEG 编码质量 */
    val imageQuality: Int = DEFAULT_IMAGE_QUALITY,
    /** 弹幕滚动速度（px/秒） */
    val speedPxPerSec: Int = DEFAULT_SPEED_PX_PER_SEC,
    /** 最大轨道数（开启 [autoTracks] 时它是**上限**，而非直接使用的值） */
    val maxTracks: Int = DEFAULT_MAX_TRACKS,
    /**
     * v2.4.10：**行数自适应** —— 轨道数按「字号 + 可用区域」自动计算。
     *
     * 开启时 [maxTracks] 退化为上限（限制最多几行）；关闭时完全沿用 [maxTracks]（旧行为）。
     * 计算逻辑见 `DanmuEngine.autoTrackCount`。
     */
    val autoTracks: Boolean = DEFAULT_AUTO_TRACKS,
    /** 弹幕不透明度 0-100 */
    val opacityPercent: Int = DEFAULT_OPACITY,
    /** 弹幕字号（sp） */
    val fontSizeSp: Int = DEFAULT_FONT_SIZE_SP,
    /** 相似度去重阈值 0-100（越大越严格） */
    val dedupThresholdPercent: Int = DEFAULT_DEDUP_PERCENT,

    // ===== v2.3.0：全局颜色（作用于**全部**弹幕，非逐条随机）=====
    // 复用 `SubtitleModels.kt` 里既有枚举，避免为弹幕另造一套同义类型
    //（「同一份数据两处登记」是本项目反复事故源）。
    /** 弹幕文字颜色（全局） */
    val textColorId: Int = SubtitleColorOption.WHITE.id,
    /** 弹幕描边（全局） */
    val strokeId: Int = SubtitleStrokeOption.MEDIUM_BLACK.id,
    /** 弹幕背景底（全局） */
    val bgId: Int = SubtitleBgOption.TRANSPARENT.id,

    // ===== v2.4.6：颜色模式（单一 / 完全随机 / 80%白+随机）=====
    /**
     * 颜色模式。
     *
     * ⚠️ 与 [textColorId] 的关系：**只在 [DanmuColorMode.SINGLE] 下才用 [textColorId]**；
     *    两种随机模式下 [textColorId] 被忽略（但**不重置**，用户切回单一色时仍保留上次的选择）。
     *    这是刻意的 —— 「切到随机再切回来发现颜色被重置了」是典型的体验刺点。
     */
    val colorModeId: Int = DanmuColorMode.SINGLE.id,

    // ===== v2.4.1：素材来源 =====
    /** 素材来源模式（画面 / 台词 / 两者） */
    val sourceModeId: Int = DanmuSourceMode.IMAGE_AND_SUBTITLE.id,

    // ===== v2.4.7：内容来源（AI 生成 / 本地导入）=====
    /**
     * 弹幕内容来源。
     *
     * ⚠️ 与 [sourceModeId] 是两个正交维度：
     * [sourceModeId] 决定「AI 生成时喂什么素材」，本字段决定「内容从哪来」。
     * 选 [DanmuSourceType.IMPORT] 时 [sourceModeId] 不参与。
     */
    val sourceTypeId: Int = DanmuSourceType.AI.id,

    /**
     * 导入的弹幕文件 URI（持久化字符串）。
     *
     * 只存 URI 字符串而**不存内容**：弹幕文件可达数 MB（几万条），
     * 存进 prefs 会显著拖慢每次读写（prefs 是全量加载的 XML）。
     * 内容在运行时按需读取并缓存在内存里的 [DanmuImportStore]。
     */
    val importedUri: String = "",

    /** 导入文件的显示名（仅用于 UI 展示「已导入 xxx.xml」） */
    val importedName: String = "",

    /**
     * 批次时间抖动标准差（ms）—— **v2.4.7 正态分布抖动的强度**。
     *
     * - `0` = 关闭抖动（同批弹幕同一时刻出场；⚠️ 此时受轨道避让限制，一批通常只能进 1~2 条）
     * - 默认 [DEFAULT_TIME_JITTER_MS] = 400ms，见 `DanmuEngine.spreadBornTimes`
     *
     * ⚠️ 对**导入的弹幕无效**：导入弹幕自带时间戳，必须原样采用（否则会打乱原文件的时间轴）。
     */
    val timeJitterMs: Int = DEFAULT_TIME_JITTER_MS_INT
) {
    /** 解析后的素材来源（id 找不到时回落到「画面+台词」） */
    val sourceMode: DanmuSourceMode
        get() = DanmuSourceMode.fromId(sourceModeId)

    /** 是否需要把截图发给模型 */
    val needsImage: Boolean
        get() = sourceMode != DanmuSourceMode.SUBTITLE_ONLY

    /** 是否需要把台词文本发给模型 */
    val needsSubtitle: Boolean
        get() = sourceMode != DanmuSourceMode.IMAGE_ONLY

    /** 解析后的文字颜色（id 找不到时回落到白色，不抛异常） */
    val textColorOption: SubtitleColorOption
        get() = SubtitleColorOption.values().find { it.id == textColorId } ?: SubtitleColorOption.WHITE

    /** 解析后的描边（id 找不到时回落到无描边） */
    val strokeOption: SubtitleStrokeOption
        get() = SubtitleStrokeOption.values().find { it.id == strokeId } ?: SubtitleStrokeOption.NONE

    /** 解析后的背景（id 找不到时回落到全透明） */
    val bgOption: SubtitleBgOption
        get() = SubtitleBgOption.values().find { it.id == bgId } ?: SubtitleBgOption.TRANSPARENT

    /** v2.4.6：解析后的颜色模式（id 找不到时回落到单一颜色） */
    val colorMode: DanmuColorMode
        get() = DanmuColorMode.fromId(colorModeId)

    /** v2.4.7：解析后的内容来源（id 找不到时回落到 AI 生成） */
    val sourceType: DanmuSourceType
        get() = DanmuSourceType.fromId(sourceTypeId)

    /** v2.4.7：是否走本地导入（此时**完全不请求模型**） */
    val isImportMode: Boolean
        get() = sourceType == DanmuSourceType.IMPORT

    /** v2.4.7：抖动强度（浮点，供引擎使用） */
    val timeJitterMsF: Float
        get() = timeJitterMs.coerceIn(0, MAX_TIME_JITTER_MS).toFloat()

    /**
     * v2.4.7：是否真的能进入主循环。
     *
     * ⚠️ 与 [isReadyToRequest] 的区别：导入模式**不需要 API Key / URL / 模型名**，
     *    只需要有一个已选的导入文件。若沿用 [isReadyToRequest] 判断，
     *    用户在导入模式下会被要求填 Key（明明用不到），是明显的体验错误。
     */
    fun isReadyToRun(hasImportedContent: Boolean): Boolean =
        if (isImportMode) hasImportedContent else isReadyToRequest()

    /**
     * 拼接后的完整 chat/completions 端点（幂等：已带后缀则不重复拼）。
     *
     * v2.4.2：**不再对非法 scheme 做「宽容拼接」** —— 见 [baseUrlProblem]。
     */
    fun resolveEndpoint(): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    /** 是否已具备可请求的最少信息 */
    fun isReadyToRequest(): Boolean =
        apiKey.isNotBlank() && baseUrl.isNotBlank() && modelName.isNotBlank()

    /**
     * v2.4.2：Base URL 本身的问题（`null` = 没问题）。
     *
     * ## 为什么需要它
     * v2.4.1 及之前只判断 `baseUrl.isNotBlank()`。用户把 `https://` 打成 `hhttps://`（多一个 h）时：
     * - 一路通过校验 →
     * - `resolveEndpoint()` 拼成 `hhttps://api.xiaomimimo.com/v1/chat/completions` →
     * - OkHttp 在 `Request.Builder.url()` 抛 `IllegalArgumentException:
     *   Expected URL scheme 'http' or 'https' but was 'hhttps'` →
     * - 编排循环把它归到笼统的 `danmu_err_unexpected`。
     *
     * 结果是**用户完全看不出是 URL 拼错**（会以为是网络/密钥问题），且每 5 秒重复一次。
     * 这里把「明显能一眼判定的 URL 错误」提前暴露成**可操作的提示**。
     *
     * ⚠️ 只做**语法层面**的粗筛（scheme 是否存在、是否为 http/https），
     *    不做 DNS / 可达性判断 —— 后者靠真实请求失败提示。
     *
     * @return 错误码，供 UI 映射到本地化文案；`null` 表示语法上没问题
     */
    fun baseUrlProblem(): BaseUrlProblem? {
        val base = baseUrl.trim()
        if (base.isEmpty()) return BaseUrlProblem.EMPTY
        // 取出 scheme（第一个 ':' 之前）
        val colon = base.indexOf(':')
        if (colon <= 0) return BaseUrlProblem.MISSING_SCHEME
        val scheme = base.substring(0, colon).lowercase()
        if (scheme !in VALID_SCHEMES) {
            // 常见笔误：https 多打/少打一个字母、写成 httpx 等 → 提示期望的是什么
            return BaseUrlProblem.BAD_SCHEME
        }
        // 形如 "https://" 但没有主机名
        val afterScheme = base.substring(colon + 1).removePrefix("//").trim()
        val host = afterScheme.substringBefore('/')
        if (host.isBlank()) return BaseUrlProblem.MISSING_HOST
        return null
    }

    /** v2.4.2：Base URL 的语法问题分类 */
    enum class BaseUrlProblem {
        /** 空 */
        EMPTY,
        /** 没有 `://`，例如 `api.xiaomimimo.com/v1` */
        MISSING_SCHEME,
        /** scheme 不是 http/https，例如 `hhttps://...` */
        BAD_SCHEME,
        /** 只有 `https://` 没有主机名 */
        MISSING_HOST
    }

    companion object {
        /**
         * 默认 OpenAI 兼容端点（v2.4.4 改为 AMD Radeon 开发者平台）。
         *
         * ## 为什么换
         * v2.4.3 及之前默认指向小米官方 `api.xiaomimimo.com` + `mimo-v2.6-flash`。
         * v2.4.4 排查「弹幕完全不出现」时实测发现：
         * - 该平台的模型 id 是**大小写敏感**的 `MiMo-V2.6-Flash`，
         *   小写 `mimo-v2.6-flash` 会得到 **HTTP 404 model_not_found**；
         * - 在 OkHttp 的 HTTP/2 通道下，这个 404 表现为
         *   `StreamResetException: stream was reset: INTERNAL_ERROR`，
         *   用户**完全看不出是模型名写错**（会误判成网络/密钥问题）。
         *
         * AMD Radeon 平台是**聚合网关**（内置 9 个模型：DeepSeek / GLM / MiMo / Qwen 等，
         * 且都提供 OpenAI 兼容的 `/chat/completions`），对多模型切换更友好。
         *
         * ⚠️ 迁移提示：老的 prefs 里若已存过小米端点，**不会被自动覆盖**
         *    （设置项遵循「用户填过就不动」原则）→ 需用户自行在面板改，
         *    或用「测试连接」按钮发现 404。
         */
        const val DEFAULT_BASE_URL = "https://developer.amd.com.cn/radeon/api/v1"

        /**
         * 默认模型 id（v2.4.4）。
         *
         * ⚠️⚠️ **大小写敏感**：必须是 `MiMo-V2.6-Flash`（`M`/`i`/`M`/`o` 与 `V`/`F` 大写）。
         *     实测同一端点下：
         *       - `MiMo-V2.6-Flash` → HTTP 200 ✅
         *       - `mimo-v2.6-flash` → HTTP 404 `model_not_found` ❌
         *     平台返回的 `model` 字段也会回显为 `self-dploy/MiMo-V2.6-Flash`。
         *
         * 该模型 `architecture.input_modalities = ["text","image"]`、`vision = true`，
         * 即**支持图片输入**（弹幕所需的视觉能力）。
         */
        const val DEFAULT_MODEL = "MiMo-V2.6-Flash"

        /**
         * 默认人格提示词 —— **完全原创**。
         *
         * ⚠️ 刻意不采用 DanmuAI 内置的 14 个人格（胡桃/阿库娅/银狼/芙莉莲等）：
         *    那些人格的描述文本属于**受版权保护的"表达"**，而"借鉴思路不复制表达"
         *    正是本方案（方案 C）能规避 GPL/AGPL 的前提。详见方案文档 §D8。
         *
         * 用户可在设置面板中完全替换本提示词。
         */
        val DEFAULT_PERSONA = """
            你是一位陪伴观众看片的吐槽型观众，正在实时观看当前画面。
            请根据画面内容，写出 {count} 条自然、口语化、有网感的中文弹幕。
            要求：
            1. 每条弹幕独立成行，不加序号、不加引号、不加任何前缀
            2. 单条长度不超过 20 个汉字，越短越有弹幕感
            3. 结合画面里的具体人物、动作、场景、字幕、台词来写，不要泛泛而谈
            4. 语气轻松幽默，像真人发的弹幕，不要像 AI 解说
            5. 不要描述"这是一张图片"之类的话，直接说弹幕内容本身
        """.trimIndent()

        const val DEFAULT_INTERVAL_SEC = 5
        const val DEFAULT_BATCH_SIZE = 8
        const val DEFAULT_IMAGE_MAX_WIDTH = 1024
        const val DEFAULT_IMAGE_QUALITY = 70
        const val DEFAULT_SPEED_PX_PER_SEC = 220
        const val DEFAULT_MAX_TRACKS = 8
        /**
         * v2.4.10：行数自适应**默认开启**（用户明确要求「行数自适应」）。
         *
         * ⚠️ 默认开启意味着**升级后轨道数会变**（例如 1080×2400 / 18sp 下约 10 行，
         *    比默认的 8 行多）。这是刻意的：自适应的目的就是让行数跟着字号走，
         *    而旧的固定 8 行在大字号下会把行距压扁。用户仍可关掉它回到手填模式。
         */
        const val DEFAULT_AUTO_TRACKS = true
        const val DEFAULT_OPACITY = 90
        const val DEFAULT_FONT_SIZE_SP = 18
        const val DEFAULT_DEDUP_PERCENT = 80

        // v2.3.0：全局颜色默认值（id 取自 SubtitleModels.kt 的既有枚举）
        const val DEFAULT_TEXT_COLOR_ID = 0  // SubtitleColorOption.WHITE
        const val DEFAULT_STROKE_ID = 2      // SubtitleStrokeOption.MEDIUM_BLACK
        const val DEFAULT_BG_ID = 0          // SubtitleBgOption.TRANSPARENT

        // ===== v2.4.6：颜色模式 =====

        /** 默认颜色模式（单一颜色，与 v2.3.0 行为一致） */
        const val DEFAULT_COLOR_MODE_ID = 0  // DanmuColorMode.SINGLE

        /**
         * 「80% 白 + 其余随机」模式下的纯白占比（百分比）。
         *
         * 取 80% 的理由：真实弹幕站的弹幕**绝大多数是白色**（默认色），彩色只占少数。
         * 比例太高（全彩）会显得花哨且影响读画面；太低（如 95%）则几乎看不出随机效果。
         */
        const val MOSTLY_WHITE_PERCENT = 80

        /**
         * 弹幕随机色板（v2.4.6）—— **5 色，刻意不含黑色/深色**。
         *
         * ⚠️ 与 `SubtitleColorOption`（8 色）是**两套不同用途**的色板，不要合并：
         * - 字幕有背景底框（可选半透明黑），深色字仍可读 → 8 色含黑合理；
         * - 弹幕**没有底框**（默认全透明）且描边是黑边，黑字在暗场景会糊掉 → 必须排除。
         *
         * 同时排除「漆黑」与过深的色，只留高亮、在深浅两种画面上都够醒目的颜色。
         * 顺序即权重（等概率取，顺序仅影响可读性）。
         */
        val DANMU_PALETTE: List<Color> = listOf(
            Color.White,               // 纯白（出现频率最高，见 MOSTLY_WHITE_PERCENT）
            Color(0xFFFFEB3B),         // 柠檬黄
            Color(0xFF00E5FF),         // 青蓝
            Color(0xFF00E676),         // 荧光绿
            Color(0xFFFF4081),         // 樱花粉
            Color(0xFFFF9100)          // 暖阳橙
        )

        /** 色板里的「纯白」下标（[DANMU_PALETTE] 的首位） */
        const val PALETTE_WHITE_INDEX = 0

        /**
         * 按颜色模式为**单条**弹幕选色（v2.4.6）。
         *
         * ⚠️ **逐条独立随机**，不是每批一个色 —— 同批内颜色各异才有真实弹幕的层次感。
         *    （若每批统一一个色，视觉上仍像「单一颜色」，失去随机的意义。）
         *
         * @param mode        颜色模式
         * @param singleColor [DanmuColorMode.SINGLE] 下使用的颜色（即 `textColorOption.color`）
         * @param roll        0..1 的随机数提供者（注入以便单测确定性验证）
         */
        fun pickColor(mode: DanmuColorMode, singleColor: Color, roll: () -> Float): Color =
            when (mode) {
                DanmuColorMode.SINGLE -> singleColor

                // 完全随机：等概率从整个色板取
                DanmuColorMode.RANDOM ->
                    DANMU_PALETTE[(roll() * DANMU_PALETTE.size).toInt()
                        .coerceIn(0, DANMU_PALETTE.size - 1)]

                // 80% 白 + 其余随机：先掷一次决定「是不是白」
                DanmuColorMode.MOSTLY_WHITE -> {
                    if (roll() * 100f < MOSTLY_WHITE_PERCENT) {
                        DANMU_PALETTE[PALETTE_WHITE_INDEX]
                    } else {
                        // 从**彩色部分**取（跳过首位白色，否则 20% 里还会再出现白色）
                        val colored = DANMU_PALETTE.subList(1, DANMU_PALETTE.size)
                        colored[(roll() * colored.size).toInt().coerceIn(0, colored.size - 1)]
                    }
                }
            }

        /** 间隔下限（秒）：低于此值会因 glReadPixels 同步阻塞拖累 GL 线程 */
        const val MIN_INTERVAL_SEC = 2
        const val MAX_INTERVAL_SEC = 120

        /** 批量条数上下限 */
        const val MIN_BATCH_SIZE = 1
        const val MAX_BATCH_SIZE = 30

        /** 轨道数上限（超过会挤压画面） */
        const val MAX_TRACKS_LIMIT = 20

        // ===== v2.4.1：台词窗口 =====
        /**
         * 取当前播放位置**之前**多少毫秒的台词。
         *
         * 取「之前」而非「之后」为主：弹幕是对**刚刚发生**的画面的反应。
         * 15 秒约等于 3~5 句对白，足够让模型理解上下文，又不至于把文本撑大。
         */
        const val SUBTITLE_WINDOW_BEFORE_MS = 15_000L

        /**
         * 取当前播放位置**之后**多少毫秒的台词。
         *
         * 少量「后视」是为了让模型知道"话还没说完"，避免对半句话做反应；
         * 不宜过大，否则模型会**剧透**（把还没播的剧情写进弹幕）。
         */
        const val SUBTITLE_WINDOW_AFTER_MS = 5_000L

        /** 台词文本总长上限（字符）：防止长片源一次性塞爆 prompt */
        const val SUBTITLE_TEXT_MAX_CHARS = 600

        // v2.4.1：素材来源默认值
        const val DEFAULT_SOURCE_MODE_ID = 0  // DanmuSourceMode.IMAGE_AND_SUBTITLE

        // ===== v2.4.7：内容来源（AI / 导入）=====

        /** 默认内容来源（AI 现场生成，与 v2.4.6 行为一致） */
        const val DEFAULT_SOURCE_TYPE_ID = 0  // DanmuSourceType.AI

        /** 抖动强度下限（ms）：0 = 关闭抖动 */
        const val MIN_TIME_JITTER_MS = 0

        /**
         * 抖动强度上限（ms）。
         *
         * ## v2.4.10：1500 → **10000（10 秒）**
         * 用户明确要求「把正态分布的时间范围扩展到 10 秒」。上限即**标准差**的
         * 可调最大值，10 秒意味着允许整批弹幕在约 ±10 秒（1σ）内铺开 ——
         * 用于「一批弹幕当作一小段高潮慢慢放出」的场景。
         *
         * ⚠️ 旧值 1500 的理由是「再大会出现上一批没走完下一批又来了的错乱」——
         *    那仍成立，所以**默认值保持 400ms 不变**，10 秒只是把**上限**放开，
         *    由用户按需要的节奏自行调（0 = 关闭）。
         *
         * ⚠️ 与 [DanmuEngine.SPREAD_MAX_ABS_MS] 配套：截断上限会随 σ 缩放，
         *    详见那里的说明。
         */
        const val MAX_TIME_JITTER_MS = 10_000

        /** 抖动强度默认值（ms）= `DanmuEngine.DEFAULT_TIME_JITTER_MS` 的整数形式 */
        const val DEFAULT_TIME_JITTER_MS_INT = 400

        /**
         * v2.4.2：允许的 URL scheme。
         *
         * 只放 http/https：OkHttp 也**只接受这两个**，其余会在
         * `Request.Builder.url()` 抛 IllegalArgumentException。
         * 提前用同一口径校验，就能把「URL 拼错」和「网络失败」区分开。
         */
        private val VALID_SCHEMES = setOf("http", "https")

        // ===== v2.4.2：预设人格 =====

        /**
         * 预设人格列表（**全部原创**）。
         *
         * ⚠️ **版权红线**：DanmuAI 内置的 14 个人格（胡桃 / 阿库娅 / 银狼 / 芙莉莲等）
         *    属于**受版权保护的角色与表达**，「借鉴思路、不复制表达」正是本方案能规避
         *    其 GPL/AGPL 与角色版权的前提（见方案文档 §D8）。
         *    因此这里**只提供风格描述，不含任何第三方角色的名称、口癖或台本**。
         *
         * 用法：面板上以 chip 一行呈现，点选后把 `prompt` 填进 `personaPrompt`
         *      （**可编辑**，用户改完不会被覆盖 —— 见 DanmuSettingsPanel 的选中判定）。
         *      `Custom` 档不参与"选中高亮"，代表"我自己写的"。
         */
        val PERSONA_PRESETS = listOf(
            DanmuPersonaPreset(
                id = 0,
                labelRes = R.string.danmu_persona_preset_default,
                prompt = DEFAULT_PERSONA
            ),
            DanmuPersonaPreset(
                id = 1,
                labelRes = R.string.danmu_persona_preset_humor,
                prompt = """
                    你是一位反应极快的搞笑型观众，正在实时观看当前画面。
                    请根据画面内容，写出 {count} 条自然、口语化、有网感的中文弹幕。
                    要求：
                    1. 每条弹幕独立成行，不加序号、不加引号、不加任何前缀
                    2. 单条长度不超过 18 个汉字，越短越有弹幕感
                    3. 多用反差、夸张、自嘲、接梗的方式制造笑点，但不要人身攻击
                    4. 紧扣画面里的具体人物、动作、场景，别写放之四海皆准的废话
                    5. 不要描述"这是一张图片"，直接说弹幕内容本身
                """.trimIndent()
            ),
            DanmuPersonaPreset(
                id = 2,
                labelRes = R.string.danmu_persona_preset_pro,
                prompt = """
                    你是一位懂行的影迷观众，正在实时观看当前画面。
                    请根据画面内容，写出 {count} 条自然、有见地的中文弹幕。
                    要求：
                    1. 每条弹幕独立成行，不加序号、不加引号、不加任何前缀
                    2. 单条长度不超过 24 个汉字
                    3. 从镜头语言、表演细节、剧情铺垫、道具布景等角度点评，像行家聊天
                    4. 只谈论**画面上已经出现**的内容，绝不推测或剧透后续剧情
                    5. 语气专业但不掉书袋，不要写成影评段落
                """.trimIndent()
            ),
            DanmuPersonaPreset(
                id = 3,
                labelRes = R.string.danmu_persona_preset_emo,
                prompt = """
                    你是一位情绪外放、共情力强的观众，正在实时观看当前画面。
                    请根据画面内容，写出 {count} 条自然、口语化的中文弹幕。
                    要求：
                    1. 每条弹幕独立成行，不加序号、不加引号、不加任何前缀
                    2. 单条长度不超过 16 个汉字，要短促有力
                    3. 以第一人称直白表达当下的感受（惊讶、心疼、紧张、被甜到等）
                    4. 感受必须由**画面上具体发生了什么**触发，不能空喊
                    5. 不要描述"这是一张图片"，直接说弹幕内容本身
                """.trimIndent()
            ),
            DanmuPersonaPreset(
                id = 4,
                labelRes = R.string.danmu_persona_preset_zen,
                prompt = """
                    你是一位冷静克制的观众，正在实时观看当前画面。
                    请根据画面内容，写出 {count} 条简短、留白感强的中文弹幕。
                    要求：
                    1. 每条弹幕独立成行，不加序号、不加引号、不加任何前缀
                    2. 单条长度不超过 14 个汉字，宁短勿长
                    3. 像在安静地发一句感慨，不要浮夸，不要堆形容词
                    4. 只针对画面里真实可见的元素，不添加画面外的信息
                    5. 不要描述"这是一张图片"，直接说弹幕内容本身
                """.trimIndent()
            )
        )

        /** 「自定义」档的代表 id：与任何预设都不相等（`PERSONA_PRESETS` 的 id 从 0 起） */
        const val PERSONA_CUSTOM_ID = -1

        /**
         * 判断当前 `personaPrompt` 是否**恰好等于**某个预设的提示词。
         *
         * 用于 chip 行的选中高亮：用户只要改过一个字，就不该再高亮那个预设
         * （否则会出现「明明改了却还显示选中 🔒 原预设」的错位感）。
         *
         * @return 命中的预设 id；没有命中返回 [PERSONA_CUSTOM_ID]
         */
        fun matchPersonaPresetId(personaPrompt: String): Int {
            val trimmed = personaPrompt.trim()
            if (trimmed.isEmpty()) return PERSONA_CUSTOM_ID
            return PERSONA_PRESETS.firstOrNull { it.prompt.trim() == trimmed }?.id
                ?: PERSONA_CUSTOM_ID
        }
    }
}

/**
 * v2.4.2：预设人格。
 *
 * @param id       稳定 id（**勿用 ordinal**，项目既有约定）
 * @param labelRes chip 上显示的名字
 * @param prompt   点击后填入 `DanmuConfig.personaPrompt` 的全文（含 `{count}` 占位符）
 */
data class DanmuPersonaPreset(
    val id: Int,
    @androidx.annotation.StringRes val labelRes: Int,
    val prompt: String
)
