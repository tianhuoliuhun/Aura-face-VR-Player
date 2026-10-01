package com.example.vr

import androidx.annotation.StringRes
import com.example.R

/**
 * ============================================================================
 * 视频画质增强：MEMC 插帧 + FSR 超分 —— 配置模型与规则引擎
 * ============================================================================
 *
 * 本文件只负责「配置语义」这一件事，不碰任何 GL 代码：
 *   ① 开关状态   —— memcEnabled / fsrEnabled
 *   ② 规则判定   —— FSR 默认规则 / 自定义规则，以及两者的优先级
 *   ③ 档位定义   —— FSR 目标分辨率、MEMC 目标帧率
 *
 * 渲染侧的接入见 VRGLRenderer（fsrEnabled / memcEnabled 等 @Volatile 属性）。
 * 这样切分的好处：规则可以纯函数单测，渲染侧只消费「判定结果」，
 * 不会出现「UI 显示 1440p、渲染却在用 1080p」这类两套逻辑对不上的问题。
 *
 * ---------------------------------------------------------------------------
 * 【FSR 规则口径（用户需求原文 + 本次确认）】
 *
 *   默认规则（DEFAULT）：
 *     源 > 1440p            → 不启用超分
 *     源 = 1080p            → 超分至 1440p
 *     其余（含 1440p 本身）  → 超分至 1080p
 *     ★ 再加一层保护：目标 ≤ 源 时判定为「不启用」
 *       —— 否则 1440p 源会被按字面「超分」到 1080p，那是降画质而非超分。
 *
 *   自定义规则（CUSTOM）：
 *     ★ 完全接管 —— 一旦选了自定义目标分辨率，默认规则**不再参与判定**。
 *       用户选 720p 目标就按 720p 走，不做「是否够格超分」的二次判断。
 *
 *   优先级总结：CUSTOM 覆盖 DEFAULT；两者互斥，不叠加。
 *
 * ---------------------------------------------------------------------------
 * 【「画面分辨率」的判定基准】
 *
 * 取**视频源分辨率**（VRGLRenderer.videoWidth / videoHeight，由 ExoPlayer 的
 * onVideoSizeChanged 回填），不是屏幕/眼缓冲分辨率 —— 因为规则说的是
 * 「1080p 画面超分到 1440p」，主语是**播放的内容**而不是显示器。
 *
 * 判定用**短边**（min(w, h)）：横屏 1920x1080 与竖屏 1080x1920 都识别为 1080p，
 * 不会因为旋转元数据而判成两档。
 * ============================================================================
 */

/** FSR 超分规则模式：默认规则 / 自定义规则 */
enum class FsrRuleMode(val id: String, @StringRes val labelRes: Int) {
    /** 按内置默认规则自动决定目标分辨率（>1440p 不启用；1080p→1440p；其余→1080p） */
    DEFAULT("default", R.string.fsr_rule_default),

    /** 用户指定目标分辨率，完全接管默认规则 */
    CUSTOM("custom", R.string.fsr_rule_custom);

    companion object {
        /** ⚠️ 用 id 查找而非 ordinal —— 枚举增删后 ordinal 会错位（见项目持久化约定） */
        fun fromId(id: String?): FsrRuleMode = values().find { it.id == id } ?: DEFAULT
    }
}

/**
 * FSR 自定义目标分辨率档位。
 *
 * 宽高都显式写死，不用「按 16:9 推算」—— 因为 3840p 并非标准 16:9 规格
 * （16:9 下垂直 3840 对应宽度约 6827，是个很怪的尺寸），显式写死更透明，
 * UI 上也直接把「3840p (7680×3840)」显示给用户，避免口径不清。
 */
enum class FsrTargetResolution(
    val id: String,
    val width: Int,
    val height: Int
) {
    P720("720p", 1280, 720),
    P1080("1080p", 1920, 1080),
    P1440("1440p", 2560, 1440),
    P2160("2160p", 3840, 2160),
    P3840("3840p", 7680, 3840),
    P4320("4320p", 7680, 4320);

    /** UI 展示用：「2160p (3840×2160)」 */
    val label: String get() = "$id ($width×$height)"

    companion object {
        fun fromId(id: String?): FsrTargetResolution = values().find { it.id == id } ?: P1080
    }
}

/** FSR 判定结果 —— 渲染侧只消费这个，不自己再判一次 */
data class FsrDecision(
    /** 是否真的启用超分 pass */
    val enabled: Boolean,
    /** 超分目标宽（enabled=false 时无意义，恒 0） */
    val targetWidth: Int,
    /** 超分目标高（enabled=false 时无意义，恒 0） */
    val targetHeight: Int,
    /** 判定原因，用于 UI 提示与 logcat 排查 */
    val reason: FsrReason
) {
    companion object {
        val OFF_NO_SOURCE = FsrDecision(false, 0, 0, FsrReason.NO_SOURCE)
    }
}

/** FSR 判定原因（决定 UI 上给用户看哪句提示） */
enum class FsrReason(@StringRes val labelRes: Int) {
    /** 源 > 1440p：按规则不启用 */
    SOURCE_ABOVE_1440P(R.string.fsr_reason_source_above_1440p),

    /** 源 = 1080p → 1440p */
    FHD_TO_1440P(R.string.fsr_reason_fhd_to_1440p),

    /** 其余 → 1080p */
    OTHERS_TO_1080P(R.string.fsr_reason_others_to_1080p),

    /** 目标 ≤ 源：保护性不启用，避免降画质 */
    TARGET_NOT_HIGHER(R.string.fsr_reason_target_not_higher),

    /** 用户自定义目标，完全接管 */
    CUSTOM_TARGET(R.string.fsr_reason_custom),

    /** 尚无有效源分辨率（未开始播放） */
    NO_SOURCE(R.string.fsr_reason_no_source)
}

/**
 * 规则引擎。全部为纯函数，便于单测与在 UI 层预演「当前会怎样」。
 */
object VideoEnhanceRules {

    /** 默认规则的分水岭：源短边**高于**此值就不启用超分 */
    const val NO_UPSCALE_ABOVE_HEIGHT = 1440

    /** 默认规则里被特殊照顾的分辨率（1080p 提升到 1440p，而不是 1080p） */
    const val SPECIAL_SOURCE_HEIGHT = 1080

    /** 默认规则下 1080p 源的目标 */
    const val FHD_TARGET_WIDTH = 2560
    const val FHD_TARGET_HEIGHT = 1440

    /** 默认规则下「其余分辨率」的目标 */
    const val OTHER_TARGET_WIDTH = 1920
    const val OTHER_TARGET_HEIGHT = 1080

    /** MEMC 目标帧率可选档位（用户要求范围 48~120，取常见刷新率档） */
    val MEMC_TARGET_FPS_OPTIONS = listOf(48, 60, 72, 90, 120)

    /** MEMC 目标帧率默认值 */
    const val MEMC_TARGET_FPS_DEFAULT = 60

    /** MEMC 目标帧率的允许区间，用于加载持久化值时兜底 */
    const val MEMC_TARGET_FPS_MIN = 48
    const val MEMC_TARGET_FPS_MAX = 120

    /** 把任意读到的帧率收敛到合法区间 */
    fun clampMemcTargetFps(raw: Int): Int =
        raw.coerceIn(MEMC_TARGET_FPS_MIN, MEMC_TARGET_FPS_MAX)

    /**
     * 取「画面分辨率」的判定短边。
     * 横竖屏统一：1920x1080 与 1080x1920 都是 1080。
     */
    fun sourceShortSide(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 0
        return if (width < height) width else height
    }

    /**
     * FSR 判定入口。
     *
     * @param sourceWidth  视频源宽（VRGLRenderer.videoWidth）
     * @param sourceHeight 视频源高（VRGLRenderer.videoHeight）
     * @param ruleMode     DEFAULT / CUSTOM
     * @param customTarget CUSTOM 时使用的目标档位（DEFAULT 时忽略）
     */
    fun resolveFsr(
        sourceWidth: Int,
        sourceHeight: Int,
        ruleMode: FsrRuleMode,
        customTarget: FsrTargetResolution = FsrTargetResolution.P1080
    ): FsrDecision {
        val srcShort = sourceShortSide(sourceWidth, sourceHeight)

        // 自定义规则：完全接管，不看默认规则，也不做「目标是否更高」的保护
        // （用户明确选择了「自定义完全接管」—— 选了 720p 就按 720p 走）
        if (ruleMode == FsrRuleMode.CUSTOM) {
            return FsrDecision(
                enabled = true,
                targetWidth = customTarget.width,
                targetHeight = customTarget.height,
                reason = FsrReason.CUSTOM_TARGET
            )
        }

        // 默认规则：需要有效源分辨率
        if (srcShort <= 0) return FsrDecision.OFF_NO_SOURCE

        // ① >1440p 一律不启用
        if (srcShort > NO_UPSCALE_ABOVE_HEIGHT) {
            return FsrDecision(false, 0, 0, FsrReason.SOURCE_ABOVE_1440P)
        }

        // ② 1080p 提到 1440p；③ 其余（含 1440p 本身）提到 1080p
        val targetW: Int
        val targetH: Int
        val reason: FsrReason
        if (srcShort == SPECIAL_SOURCE_HEIGHT) {
            targetW = FHD_TARGET_WIDTH
            targetH = FHD_TARGET_HEIGHT
            reason = FsrReason.FHD_TO_1440P
        } else {
            targetW = OTHER_TARGET_WIDTH
            targetH = OTHER_TARGET_HEIGHT
            reason = FsrReason.OTHERS_TO_1080P
        }

        // ④ 保护：目标不高于源 → 不启用。
        //    典型场景：1440p 源按字面会被「超分」到 1080p（降画质），这里直接拦掉。
        //    又或 1080x1080 这种方形源，短边 1080 走「其余→1080p」，目标与源同高，同样拦掉。
        if (targetH <= srcShort) {
            return FsrDecision(false, 0, 0, FsrReason.TARGET_NOT_HIGHER)
        }

        return FsrDecision(true, targetW, targetH, reason)
    }
}
