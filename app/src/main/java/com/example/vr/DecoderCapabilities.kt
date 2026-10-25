package com.example.vr

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log

/**
 * Queries the device's hardware video decoders for their supported sizes, so videos that
 * exceed them (e.g. 8192x4096 on a decoder limited to 7680x4320) can be routed to a
 * software kernel instead of failing on MediaCodec.
 *
 * ## 两个不同的问题，别混用
 * - [getHardwareDecoderMax]：**标称**上限（`supportedWidths/Heights.upper`），只适合
 *   「大概知道机器是哪一档」这种粗判断。
 * - [findHardwareFit]：**目标尺寸到底行不行**（走 `isSizeSupported` + 逐高度可达宽度），
 *   播放路由必须用这个。
 *
 * ⚠️ 实测背景（v2.6.13）：7680×4320 能解、8192×4096 不能的机器上，两者像素只差 1.1%，
 * 标称宽度也都是 8192 —— 差异来自 block-count 上限，只有 `isSizeSupported` 才能反映。
 * 用标称值排序会把「标称最大但实际不解」的解码器选成最优解，然后配置失败。
 */
object DecoderCapabilities {

    private const val TAG = "DecoderCapabilities"

    data class DecoderCap(
        val width: Int,
        val height: Int,
        val name: String
    )

    /**
     * v117 修复：判断编解码器是否为硬件实现。
     *
     * `MediaCodecInfo.isHardwareAccelerated()` 是 **API 29（Android 10）** 才引入的方法，
     * 而本应用 `minSdk = 24`。在 Android 7/8/9 上直接调用会抛 `NoSuchMethodError` ——
     * 它是 `Error` 而非 `Exception`，外层 `catch (e: Exception)` 完全拦不住，直接崩溃。
     * 因此低版本改用编解码器名称的启发式判断（AOSP 与各厂商的软件解码器均有固定命名特征）。
     */
    fun isHardwareAccelerated(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated
        }
        val name = info.name.lowercase()
        return !(name.startsWith("omx.google.") ||
            name.startsWith("c2.android.") ||
            name.contains(".sw.") ||
            name.contains("software"))
    }

    /**
     * Returns the largest resolution supported by any hardware decoder for the
     * given mime type, or null when no hardware decoder exists.
     */
    fun getHardwareDecoderMax(mimeType: String): DecoderCap? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        var best: DecoderCap? = null
        for (info in list.codecInfos) {
            if (info.isEncoder || !isHardwareAccelerated(info)) continue
            val supported = info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            if (!supported) continue
            try {
                val caps = info.getCapabilitiesForType(mimeType)
                val vc = caps.videoCapabilities ?: continue
                val w = vc.supportedWidths.upper
                val h = vc.supportedHeights.upper
                if (best == null || w.toLong() * h > best.width.toLong() * best.height) {
                    best = DecoderCap(w, h, info.name)
                }
            } catch (e: Exception) {
                Log.w(TAG, "capabilities query failed for ${info.name}", e)
            }
        }
        return best
    }

    /**
     * Best-effort hardware decoder max for the common 8K codec (HEVC first,
     * fallback AVC) so callers don't need to know the exact codec.
     */
    fun getBestHardwareDecoderMax(): DecoderCap? {
        return getHardwareDecoderMax("video/hevc")
            ?: getHardwareDecoderMax("video/avc")
    }

    // ===================== 目标尺寸可行性（播放路由用这个） =====================

    /**
     * 某个具体尺寸在本机硬件解码器上的结论。
     *
     * [limitWidth] / [limitHeight] 只在 [supported] = false 时才有意义，表示**该解码器实际
     * 能吃下的最大尺寸**（受 block-count 影响，可能远小于标称的 `supportedWidths.upper`）。
     * 例：标称 8192×8192 的解码器在 4096 高度下只能到 7680 宽，这里就给 7680×4320。
     */
    data class SizeFit(
        val supported: Boolean,
        val decoder: String?,
        val limitWidth: Int,
        val limitHeight: Int
    )

    /**
     * 本机是否有硬件解码器能解 `width × height`，不能时给出实际可达上限。
     *
     * ## 为什么必须遍历全部解码器，而不是取「标称最大」那个
     * 同一 MIME 常有多个组件（厂商主组件 / 低延迟变体 / 别名），上限各不相同；
     * 而且标称最大 ≠ 目标尺寸可行。**只要有一个 `isSizeSupported` 返回 true 就算能解。**
     *
     * ⚠️ `getCapabilitiesForType()` 在部分 ROM 上会抛（见 [CodecCapabilityProbe] 的注释），
     * 所以每个 codec 单独 try，坏掉的组件不能影响结论。
     */
    fun findHardwareFit(mimeType: String, width: Int, height: Int): SizeFit {
        if (width <= 0 || height <= 0) return SizeFit(false, null, 0, 0)
        val list = try {
            // ALL_CODECS：有些硬件组件不在 REGULAR 列表里（如某些只给低延迟用的实现）
            MediaCodecList(MediaCodecList.ALL_CODECS)
        } catch (e: Exception) {
            Log.w(TAG, "MediaCodecList 创建失败", e)
            return SizeFit(false, null, 0, 0)
        }
        var fallback: SizeFit? = null
        var fallbackPixels = -1L
        for (info in list.codecInfos) {
            if (info.isEncoder || !isHardwareAccelerated(info)) continue
            if (info.supportedTypes.none { it.equals(mimeType, ignoreCase = true) }) continue
            val vc = try {
                info.getCapabilitiesForType(mimeType).videoCapabilities
            } catch (e: Exception) {
                Log.w(TAG, "capabilities query failed for ${info.name}", e)
                null
            } ?: continue
            val ok = try {
                vc.isSizeSupported(width, height)
            } catch (e: Exception) {
                false
            }
            if (ok) return SizeFit(true, info.name, 0, 0)

            // 不支持 → 记住「标称最大」的那一个，用它解释差在哪
            val w = try { vc.supportedWidths.upper } catch (e: Exception) { 0 }
            val h = try { vc.supportedHeights.upper } catch (e: Exception) { 0 }
            if (w.toLong() * h > fallbackPixels) {
                fallbackPixels = w.toLong() * h
                fallback = SizeFit(
                    supported = false,
                    decoder = info.name,
                    limitWidth = reachableWidth(vc, width, height),
                    limitHeight = reachableHeight(vc, width, height)
                )
            }
        }
        return fallback ?: SizeFit(false, null, 0, 0)
    }

    /** 便捷判据：[findHardwareFit] 的布尔形态。 */
    fun isHardwareSizeSupported(mimeType: String, width: Int, height: Int): Boolean =
        findHardwareFit(mimeType, width, height).supported

    /**
     * 在 `height` 这个高度下，解码器实际能到多宽。
     *
     * `getSupportedWidthsFor(h)` 是**公开 API**，它把 block-count / 每秒块数一起算进去了，
     * 所以它给出的值才是「这个高度下真正能吃的宽度」—— 这正是
     * 「8192×4096 不行、7680×4320 行」这类现象的答案所在。
     */
    private fun reachableWidth(vc: MediaCodecInfo.VideoCapabilities, width: Int, height: Int): Int {
        val cap = try { vc.supportedWidths.upper } catch (e: Exception) { 0 }
        if (cap <= 0) return 0
        val maxHeight = try { vc.supportedHeights.upper } catch (e: Exception) { 0 }
        // 高度本身就超范围时 getSupportedWidthsFor 会抛 → 只能给宽度的单维上限
        if (height > maxHeight) return cap
        // 目标宽度本身没超单维上限，说明问题不在宽度 → 不要误导成「宽度只能到 w」
        if (width <= cap) {
            val atH = try { vc.getSupportedWidthsFor(height).upper } catch (e: Exception) { 0 }
            if (atH in 1 until cap) return atH
        }
        return cap
    }

    /** 在 `width` 这个宽度下，解码器实际能到多高。 */
    private fun reachableHeight(vc: MediaCodecInfo.VideoCapabilities, width: Int, height: Int): Int {
        val cap = try { vc.supportedHeights.upper } catch (e: Exception) { 0 }
        if (cap <= 0) return 0
        val maxWidth = try { vc.supportedWidths.upper } catch (e: Exception) { 0 }
        if (width > maxWidth) return cap
        if (height <= cap) {
            val atW = try { vc.getSupportedHeightsFor(width).upper } catch (e: Exception) { 0 }
            if (atW in 1 until cap) return atW
        }
        return cap
    }
}
