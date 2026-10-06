package com.example.vr

import androidx.annotation.StringRes
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
    /** 最大轨道数 */
    val maxTracks: Int = DEFAULT_MAX_TRACKS,
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

    // ===== v2.4.1：素材来源 =====
    /** 素材来源模式（画面 / 台词 / 两者） */
    val sourceModeId: Int = DanmuSourceMode.IMAGE_AND_SUBTITLE.id
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
        const val DEFAULT_OPACITY = 90
        const val DEFAULT_FONT_SIZE_SP = 18
        const val DEFAULT_DEDUP_PERCENT = 80

        // v2.3.0：全局颜色默认值（id 取自 SubtitleModels.kt 的既有枚举）
        const val DEFAULT_TEXT_COLOR_ID = 0  // SubtitleColorOption.WHITE
        const val DEFAULT_STROKE_ID = 2      // SubtitleStrokeOption.MEDIUM_BLACK
        const val DEFAULT_BG_ID = 0          // SubtitleBgOption.TRANSPARENT

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
