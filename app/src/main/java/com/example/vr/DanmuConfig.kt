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
 * AI 弹幕配置（v2.2.0 / P1）
 *
 * 设计说明（与方案文档 `docs/DANMUAI_PLAN_C_IMPLEMENTATION_2026-10-06.md` 对应）：
 * - **API 协议固定为 OpenAI 兼容**（用户 2026-10-06 定案）：POST {baseUrl}/chat/completions
 * - 默认视觉模型 **MiMo V2.6 Flash**：
 *     · 端点 https://api.xiaomimimo.com/v1
 *     · 模型 id `mimo-v2.6-flash`
 *     · 官方支持 `input: ["text", "image"]`（即图片输入可用）
 *     · Base64 data URL 单图上限 50 MB，格式 JPEG/PNG/GIF/WebP/BMP
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

    /** 拼接后的完整 chat/completions 端点（幂等：已带后缀则不重复拼） */
    fun resolveEndpoint(): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    /** 是否已具备可请求的最少信息 */
    fun isReadyToRequest(): Boolean =
        apiKey.isNotBlank() && baseUrl.isNotBlank() && modelName.isNotBlank()

    companion object {
        /** 小米 MiMo 官方 OpenAI 兼容端点 */
        const val DEFAULT_BASE_URL = "https://api.xiaomimimo.com/v1"

        /** 默认模型 id（MiMo V2.6 Flash，支持图片输入） */
        const val DEFAULT_MODEL = "mimo-v2.6-flash"

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
    }
}
