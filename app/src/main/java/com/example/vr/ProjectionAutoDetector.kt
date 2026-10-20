package com.example.vr

/**
 * v2.6.8：**投影智能检测**（纯逻辑，无 Compose / Android 依赖，可直接单测）。
 *
 * ## 为什么抽出来
 * 这段判定原先内嵌在 `onVideoSizeChanged` 回调里，规则只能靠读代码猜，
 * 而且**同一组阈值被用了两次**（检测 360、以及"平面投影下清残留 SBS"），
 * 两处各写一份 `1.80f..2.20f` —— 改一处漏一处就会出现"检测判 2D、清立体却按 360 判断"
 * 这种自相矛盾的状态（本项目头号坑：同一份数据两处登记）。
 *
 * ## 阈值为什么从 1.80..2.20 收窄到 1.95..2.05
 * 等距柱状全景（equirectangular）在编码上**必须**是精确 2:1：
 * 1920×960 / 2048×1024 / 3840×1920 / 5760×2880 / 7680×3840 全部为 2.000。
 * 而 1.80..2.20 这个窗口会把两类**普通电影**误判成全景并弹「已识别 360 全景」：
 * - **1.85:1**（1998×1080 ≈ 1.850）—— 影院宽银幕的常见裁切比例
 * - **2.20:1**（如 2.2:1 的手机/流媒体画幅）
 * 一旦误判，普通电影会被塞进球面投影，画面严重变形，用户还得手动改回来。
 *
 * 收窄到 ±2.5% 后，标准 2:1 片源仍全部命中（含 2.05 这类轻微偏差的封装）。
 *
 * ## 新增 4:1 → 左右立体 3D 全景
 * SBS 360 片源是「左右各一张 2:1 equirect」= **4:1**（3840×960 / 4096×1024）。
 * 旧逻辑把它判成普通平面并顺手把立体模式清成 MONO，等于 3D 全景完全不可用。
 * 渲染层对 `VR_360 + SBS` 是支持的（shader 里 `uStereoMode` 与投影独立计算，
 * 且 `uWarpDualCenter` 只在 MONO 下生效），因此这里可以直接给出该组合。
 */
internal object ProjectionAutoDetector {

    /**
     * 等距柱状全景（2:1）的宽高比窗口。
     *
     * ⚠️ 这个常量是**唯一来源**：`VRPlayerScreen` 里"平面投影下清残留 SBS"的判定
     * 也走 [isEquirect]，不要再在别处写死区间。
     */
    const val EQUIRECT_ASPECT_MIN = 1.95f
    const val EQUIRECT_ASPECT_MAX = 2.05f

    /** 左右立体 3D 全景（4:1）的宽高比窗口 */
    const val SBS_360_ASPECT_MIN = 3.90f
    const val SBS_360_ASPECT_MAX = 4.10f

    /** 触发检测结果的原因（决定提示文案） */
    enum class Reason {
        /** 2:1 单目全景 */
        EQUIRECT_360,

        /** 4:1 左右立体全景 */
        EQUIRECT_360_SBS,

        /** 普通平面视频（含宽银幕、竖屏、方形） */
        FLAT
    }

    /**
     * 一条**建议**（不是命令）：调用方要和当前模式比较，只有不同才应用 + 提示，
     * 否则每次尺寸回调都会重复弹 Toast。
     */
    data class Suggestion(
        val mode: ProjectionMode,
        val stereo: StereoMode,
        val warpDualCenter: Boolean,
        val reason: Reason
    )

    /** 宽高比是否落在 2:1 全景窗口内（检测与"清残留 SBS"共用） */
    fun isEquirect(aspect: Float): Boolean =
        aspect >= EQUIRECT_ASPECT_MIN && aspect <= EQUIRECT_ASPECT_MAX

    /** 宽高比是否落在 4:1 立体全景窗口内 */
    fun isSbsPano(aspect: Float): Boolean =
        aspect >= SBS_360_ASPECT_MIN && aspect <= SBS_360_ASPECT_MAX

    /**
     * 根据视频尺寸给出投影建议。
     *
     * @return 尺寸无效（任一边 ≤ 0，常见于解码器初始回调）时返回 null ——
     *         此时**不要**套用任何结论，否则刚进页面那一下会把用户已有模式重置成平面。
     */
    fun detect(width: Int, height: Int): Suggestion? {
        if (width <= 0 || height <= 0) return null
        val aspect = width.toFloat() / height.toFloat()
        return when {
            isSbsPano(aspect) -> Suggestion(
                mode = ProjectionMode.VR_360,
                stereo = StereoMode.SBS,
                // 双中心变形只在 MONO 下生效（shader 里显式判断），这里给 false 更诚实
                warpDualCenter = false,
                reason = Reason.EQUIRECT_360_SBS
            )

            isEquirect(aspect) -> Suggestion(
                mode = ProjectionMode.VR_360,
                stereo = StereoMode.MONO,
                warpDualCenter = true,
                reason = Reason.EQUIRECT_360
            )

            else -> Suggestion(
                mode = ProjectionMode.STANDARD,
                stereo = StereoMode.MONO,
                warpDualCenter = false,
                reason = Reason.FLAT
            )
        }
    }
}
