package com.example.vr

/**
 * 图像缩放的**目标尺寸计算** —— 唯一入口（v2.4.11）。
 *
 * ## 为什么要有这个文件
 * 「按长边等比缩放」在项目里有**两个调用点**：
 * ① `VRGLRenderer.captureDanmuFrameIfNeeded` —— 决定 GL 回读用的 FBO 尺寸；
 * ② `DanmuVisionClient.scaleDown` —— 决定最终交给模型的图尺寸。
 *
 * ⚠️ 两处若各写一份，必然出现「回读按宽、最终按长边」这类**不一致**：
 *   竖屏片源下回读出来的图长边会**超过**用户设定值
 *   （设 256 却得到 256×455，长边 455），白耗 token 与带宽。
 *   这正是本项目「同一份数据两处登记」的老毛病，故收敛到这里一处。
 *
 * ## 为什么是「长边」而不是「宽」
 * 用户设定的是「**长边**分辨率」—— 对**横屏**片源长边就是宽，对**竖屏**片源长边是高。
 * 只按宽度缩的话，竖屏片源会得到一张长边远超设定值的图。
 *
 * ## ⚠️ 只缩不放
 * 源图长边已小于目标时**原样返回**。放大不会带来更多信息，只会让 token 与带宽白涨
 * （模型看到的是插值出来的假细节）。这与「压缩」的语义一致。
 *
 * ## 纯函数
 * 零 Android 依赖 → 可在纯 JVM 单测里直接覆盖（本项目对这类几何计算一律如此）。
 */
object ImageScale {

    /** 允许的长边下限：再小就什么都看不出了（也防止 0/负数把 FBO 搞坏）。 */
    const val MIN_LONG_SIDE = 64

    /** 允许的长边上限：4320 是用户提供的最大档位（8K 宽的一半量级）。 */
    const val MAX_LONG_SIDE = 4320

    /**
     * GL 回读路径的**像素总数上限**（v2.4.11）。
     *
     * ## 为什么必须有
     * `VRGLRenderer` 为一帧回读要分配**两份堆数组**（`IntBuffer` + `IntArray`），
     * 每像素 4 字节 → 长边 4320 的竖屏视频（2430×4320 ≈ 10.5M 像素）
     * 需要 **约 84MB 堆**，再加 42MB GL 纹理 → **必然 OOM**。
     *
     * 4,000,000 像素（≈2048×2048）对应每份 16MB、两份 32MB —— 在不牺牲
     * 1920×1080（2.07M）与 2560×1440（3.69M）这两个常用档位的前提下，
     * 把风险压在可控范围。
     *
     * ⚠️ 超限时**等比缩到上限以内**（而不是报错），用户仍能得到一张合法的图，
     *    只是小于所设档位；渲染层会打警告日志。
     */
    const val READBACK_MAX_PIXELS = 4_000_000L

    /**
     * 计算等比缩放后的目标尺寸。
     *
     * @param srcW 源宽（<=0 时返回 1×1，**不抛异常** —— GL 首帧尺寸未就绪时会遇到）
     * @param srcH 源高（同上）
     * @param maxLongSide 目标长边；会被夹到 [MIN_LONG_SIDE]..[MAX_LONG_SIDE]
     * @return `intArrayOf(w, h)`，两值均 >= 1。
     *         **源图长边 <= 目标时原样返回 `[srcW, srcH]`**（不放大）。
     *
     * ⚠️ 返回 IntArray 而不是 `Pair<Int,Int>`：本函数会在 **GL 线程**被每帧调用，
     *    避免 Pair 的装箱分配。
     */
    fun fitLongSide(srcW: Int, srcH: Int, maxLongSide: Int): IntArray {
        if (srcW <= 0 || srcH <= 0) return intArrayOf(1, 1)
        val limit = maxLongSide.coerceIn(MIN_LONG_SIDE, MAX_LONG_SIDE)
        val longSide = if (srcW >= srcH) srcW else srcH
        if (longSide <= limit) return intArrayOf(srcW, srcH)
        val ratio = limit.toDouble() / longSide
        val w = (srcW * ratio).toInt().coerceAtLeast(1)
        val h = (srcH * ratio).toInt().coerceAtLeast(1)
        return intArrayOf(w, h)
    }

    /**
     * 缩放后是否真的会改变尺寸（即调用方需要新建 Bitmap / 重设 FBO）。
     *
     * 用于「能复用就复用」的判据 —— 避免每帧白建一个等大的 Bitmap。
     */
    fun needsScale(srcW: Int, srcH: Int, maxLongSide: Int): Boolean {
        val r = fitLongSide(srcW, srcH, maxLongSide)
        return r[0] != srcW || r[1] != srcH
    }

    /**
     * 按长边的等比**缩放系数**（<1 缩小 / =1 不缩放）。
     *
     * 单独提供是为了让调用方能直接乘算（例如换算预估字节数），
     * 而不必先取尺寸再除。
     */
    fun ratio(srcW: Int, srcH: Int, maxLongSide: Int): Float {
        if (srcW <= 0 || srcH <= 0) return 1f
        val limit = maxLongSide.coerceIn(MIN_LONG_SIDE, MAX_LONG_SIDE)
        val longSide = if (srcW >= srcH) srcW else srcH
        return if (longSide <= limit) 1f else limit.toFloat() / longSide
    }

    // ===================================================================
    // 通道序修正（v2.4.11：修「传给模型的图偏色」）
    // ===================================================================

    /**
     * **ARGB int 像素 → RGB 字节数组**（每像素 3 字节）—— v2.4.13。
     *
     * ## 为什么需要
     * `libmtmd` 的 `mtmd_bitmap_init` 要求 **RGB、3 字节/像素**；
     * 而本项目从 GL 回读 / `Bitmap.getPixels` 拿到的都是 **ARGB int**（4 字节/像素）。
     * 两者**不能直接互传** —— 传错会偏色（把 A 当成 R）甚至越界崩。
     *
     * ## 与 [swapRedBlueInPlace] 的关系
     * 那个函数是在 ARGB 内部**交换 R/B**（修 GL 回读的字节序问题）；
     * 本函数是**换容器**（int → 3 字节紧凑数组）。两者用途不同，都别省。
     *
     * ⚠️ 丢弃 alpha：mtmd 的位图没有 alpha 通道，直接把 A 丢掉即可
     *    （不是"用 A 去混合"，那样在预乘 alpha 的 Bitmap 上会得到偏暗的结果）。
     */
    fun argbToRgbBytes(argb: IntArray, count: Int = argb.size): ByteArray {
        val n = if (count < argb.size) count else argb.size
        val out = ByteArray(n * 3)
        var o = 0
        for (i in 0 until n) {
            val v = argb[i]
            out[o++] = ((v ushr 16) and 0xFF).toByte()   // R
            out[o++] = ((v ushr 8) and 0xFF).toByte()    // G
            out[o++] = (v and 0xFF).toByte()             // B
        }
        return out
    }

    /**
     * 把 **ABGR** 布局的像素改成 Android Bitmap 期望的 **ARGB**（交换 R 与 B）。
     *
     * ## 为什么需要这一步（本次偏色 bug 的根因）
     * `glReadPixels(..., GL_RGBA, GL_UNSIGNED_BYTE, buf)` 向缓冲写入的是
     * **字节序 `R,G,B,A`**（每像素 4 字节）。
     * 而当这个缓冲被当作 `IntBuffer` 逐元素取出时（小端设备），
     * 4 字节会被组装成：
     * ```
     * byte[0]=R → bit 0..7      byte[1]=G → bit 8..15
     * byte[2]=B → bit 16..23    byte[3]=A → bit 24..31
     * ⇒ int = (A<<24)|(B<<16)|(G<<8)|R  = ABGR
     * ```
     * 但 `Bitmap.createBitmap(int[], w, h, ARGB_8888)` 期望 **ARGB**：
     * ```
     * (A<<24)|(R<<16)|(G<<8)|B
     * ```
     * → **R 与 B 被互换** → 画面**红蓝偏色**。
     *
     * 注意：**这不是压缩造成的** —— 在 GL 回读那一刻就已经错了，
     * 压缩（缩放 + JPEG）只是把错的东西原样带到模型面前。
     *
     * ## 实现
     * 用位掩码交换 16..23 位与 0..7 位，A（24..31）与 G（8..15）保持不动：
     * ```
     * 0xFF00FF00  → A + G（保留）
     * 0x00FF0000  → B（右移 16 位到 0..7）
     * 0x000000FF  → R（左移 16 位到 16..23）
     * ```
     *
     * ## ⚠️ 就地修改
     * 直接改传入的数组（不新建），因为调用点就在 GL 线程的热路径上、
     * 且该数组随后立即被交给业务层，无需保留原值。
     *
     * @param px ARGB/ABGR 像素数组（就地交换）
     * @param count 处理的像素个数（默认全部）
     */
    fun swapRedBlueInPlace(px: IntArray, count: Int = px.size) {
        val n = if (count < px.size) count else px.size
        for (i in 0 until n) {
            val v = px[i]
            // ⚠️ 掩码必须写 `0xFF00FF00.toInt()` —— `0xFF00FF00` 超出 Int 范围，
            //    在 Kotlin 里是 **Long** 字面量；而想当然写成 `-0x10000` 会得到
            //    `0xFFFF0000`（= A+R，**错了**）。这个笔误曾被单测当场抓住。
            px[i] = (v and MASK_A_KEEP_G) or        // A（24..31）+ G（8..15）保持不动
                ((v and 0x00FF0000) ushr 16) or     // B（16..23）→ 移到 0..7
                ((v and 0x000000FF) shl 16)         // R（0..7）  → 移到 16..23
        }
    }

    /** `A + G` 的位掩码；单独提出来是为了给上面那处易错点一个明确的名字。 */
    private const val MASK_A_KEEP_G: Int = 0xFF00FF00.toInt()
}
