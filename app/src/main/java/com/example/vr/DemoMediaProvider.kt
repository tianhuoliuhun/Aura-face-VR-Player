package com.example.vr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.LruCache

/**
 * 内置演示图提供者。
 *
 * ===== v2.0.181：只保留 v2.0.179 的**第一张**（360° 全景画廊），并高清化 =====
 * 沿革（避免以后又绕回去，这里把决策链条记全）：
 *
 *  1. 原本有 5 张合成测试卡：`demo_360_beauty`(360 全景) / `demo_180_dome`(180 穹顶) /
 *     `demo_fisheye_portrait`(鱼眼) / `demo_3d_sbs_portrait`(3D 左右分屏) /
 *     `demo_standard_portrait`(标准近景)。
 *  2. 用户反馈「只留一张、要高清的那张、现在这张太糊了」→ 当时我判断"现存 5 张里没有高清的"，
 *     于是**重新画了一张** 4096×2048 的渐变人脸校准卡，并把默认投影改为 STANDARD。
 *  3. 用户看后表示「我还是喜欢原来的图」，并明确要求「**从 179 里提取第一张**」。
 *     → 即恢复 `demo_360_beauty`，用它作为**唯一**演示图。
 *
 * 所以本文件现在 = v2.0.179 的 `generate360Demo()` + `drawTestingPortrait()` 原画法，
 * 只做两处改动（都属于"高清化"，不改画面风格）：
 *  · **分辨率 2048×1024 → 4096×2048**（仍是 2:1 等距圆柱比例，投影语义不变）；
 *  · 所有绘制尺寸/坐标/字号按 **2× 等比放大**（原代码是硬编码像素值，直接放大分辨率
 *    会让内容缩到四分之一），并对半径相关元素改用相对比例，避免再次硬编码。
 *
 * ⚠️ **为什么必须配 VR_360 投影**：这张是 2:1 等距圆柱全景图，只有在 360 球面投影下
 * 才是一幅"环视画廊"；用 STANDARD 平面看会被压成一条窄带。因此 `VRPlayerScreen` 的
 * 默认投影已随之改回 **VR_360**（并让自适应逻辑识别 2:1 → 自动切 VR_360）。
 *
 * ----- 大图性能 -----
 * 4096×2048 ARGB_8888 = **32 MB**（母本）。上传走 `VRGLRenderer.uploadImageChunked()`
 * 分块通路（见该方法注释），避免 `GLUtils.texImage2D` 的 ~96 MB 瞬态峰值。
 * 缓存上限刻意**不**按堆比例算（单张 32 MB，按比例算反而容易 OOM），见 [CACHE_MAX_KB]。
 *
 * ⚠️ **recycle 契约**：`VRGLRenderer` 自 v2.0.180 起不再回收传入位图，生命周期归提供方。
 * 但本 Provider 仍**对外一律返回母本副本**，因为母本要留在缓存里复用 ——
 * 若把母本交出去，调用方一旦 recycle 就会击穿缓存。
 */
object DemoMediaProvider {

    /** 高清尺寸（2:1 等距圆柱全景） */
    const val HIGH_RES_W = 4096
    const val HIGH_RES_H = 2048

    /** 低清回退尺寸（2:1）：华为 VR 等每帧回读场景下使用 */
    const val LOW_RES_W = 2048
    const val LOW_RES_H = 1024

    /** 原始设计基准尺寸 —— 所有绘制坐标都按此基准写成，再按实际尺寸等比缩放 */
    private const val BASE_W = 2048f
    private const val BASE_H = 1024f

    private const val TAG = "DemoMediaProvider"

    /** 是否启用高清。默认 true；华为 VR 通路会临时置 false。 */
    @Volatile
    private var highResEnabled = true

    /**
     * 切换分辨率档位。变化时**清空缓存**（否则缓存 key 相同但尺寸不同会造成新旧混用）。
     * @return 是否发生了切换（调用方可据此决定是否重新下发当前图片）
     */
    fun setHighResEnabled(enabled: Boolean): Boolean {
        if (highResEnabled == enabled) return false
        highResEnabled = enabled
        cache.evictAll()
        android.util.Log.i(TAG, "highRes -> $enabled (cache evicted)")
        return true
    }

    /** 当前生效的分辨率档位描述，便于日志排查 */
    fun currentSizeDesc(): String =
        if (highResEnabled) "${HIGH_RES_W}x$HIGH_RES_H" else "${LOW_RES_W}x$LOW_RES_H}"

    /**
     * 母本缓存上限。
     *
     * 刻意**不**用「1/8 可用堆」这种大额度：单张高清母本就 32 MB，若上限按堆比例放大，
     * LruCache 会把好几张 32 MB 母本都留在堆上，反而更容易 OOM。这里只给「2 张 + 余量」。
     */
    private const val CACHE_MAX_KB = 2 * (HIGH_RES_W * HIGH_RES_H * 4 / 1024) + 4096

    private val cache: LruCache<String, Bitmap> by lazy {
        object : LruCache<String, Bitmap>(CACHE_MAX_KB) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024

            override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
                if (evicted && oldValue !== newValue && !oldValue.isRecycled) {
                    oldValue.recycle()
                }
            }
        }
    }

    /** 生成统计（便于排查缓存命中率） */
    private var genCount = 0L
    private var cacheHitCount = 0L

    // ===== 复用常量：Paint 与颜色 =====

    private val colorBg360 = Color.parseColor("#0D0E15")
    private val colorGrid360 = Color.parseColor("#222435")
    private val colorLabel360 = Color.parseColor("#626685")
    private val colorGlowCyan = Color.parseColor("#00F2FE")

    private val colorSkin = Color.parseColor("#FFDAB9")
    private val colorChinShadow = Color.parseColor("#F5B99F")
    private val colorBlush = Color.parseColor("#FFA0A0")
    private val colorEye = Color.parseColor("#1B1A55")
    private val colorBrow = Color.parseColor("#31363F")
    private val colorNose = Color.parseColor("#ECA78C")
    private val colorHair = Color.parseColor("#2C1D13")
    private val colorMouth = Color.parseColor("#E94560")
    private val colorSpot = Color.parseColor("#D2B48C")
    private val colorLabelText = Color.parseColor("#2E3842")
    private val colorLabelPlate = Color.parseColor("#A0E2E7EA")

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** 头发与标签底板轮廓，复用避免每次 new */
    private val hairPath = Path()
    private val labelPlatePath = Path()

    /**
     * 演示媒体列表 —— **只保留 v2.0.179 的第一张**。
     *
     * ⚠️ 刻意保留为列表（而非把 UI 改成"隐藏切歌按钮"）：`VRPlayerScreen` 的
     * 「上一个/下一个」按 size 取模轮转，单元素时 index 恒为 0 → 无副作用。改动面最小。
     */
    val demoMediaList: List<MediaItem> = listOf(
        MediaItem(
            id = "demo_360_beauty",
            title = "【全景360°】美颜环密画廊",
            uri = null,
            isVideo = false,
            isDemo = true,
            description = "360度全景等距矩形图像，环绕分布多组美白对比人像，包含仿真皮肤瑕疵以供实时调节美颜效果。"
        )
    )

    /** 当前唯一演示项的 id（避免调用方硬编码字符串） */
    val primaryDemoId: String get() = demoMediaList[0].id

    /**
     * 取指定 id 的演示图**副本**（可安全交给渲染器）。
     * 命中缓存时复制母本，不再走 Canvas 绘制；未命中才生成并缓存母本。
     */
    fun loadDemoBitmap(id: String): Bitmap {
        val master = obtainMaster(id)
        return master.copy(master.config ?: Bitmap.Config.ARGB_8888, false) ?: master
    }

    /** 取当前唯一演示图的副本（便捷入口） */
    fun loadPrimaryBitmap(): Bitmap = loadDemoBitmap(primaryDemoId)

    /** 探测是否已有缓存母本（不复制、不生成） */
    fun peekCached(id: String): Boolean = cache.get(id) != null

    /** 清空缓存（内存吃紧或切换分辨率档位时调用） */
    fun clearCache() {
        cache.evictAll()
    }

    /** 生成/缓存统计，格式 "generated=X hit=Y size=Z" */
    fun cacheStats(): String = "generated=$genCount hit=$cacheHitCount size=${cache.size()}"

    /** 取缓存母本；未命中则生成并写入缓存。母本**不可**交给外部 recycle。 */
    private fun obtainMaster(id: String): Bitmap {
        // 缓存 key 带分辨率档位，防止切档后新旧尺寸混用
        val key = "$id@${if (highResEnabled) "hi" else "lo"}"
        cache.get(key)?.let {
            if (!it.isRecycled) {
                cacheHitCount++
                return it
            }
            cache.remove(key)
        }
        val w = if (highResEnabled) HIGH_RES_W else LOW_RES_W
        val h = if (highResEnabled) HIGH_RES_H else LOW_RES_H
        val fresh = generate360Demo(w, h)
        genCount++
        cache.put(key, fresh)
        android.util.Log.i(TAG, "generate $key = ${fresh.width}x${fresh.height} (${fresh.byteCount / 1024} KB)")
        return fresh
    }

    // ======================= 绘制 =======================
    //
    // ⚠️ 下面所有函数里的**坐标与尺寸都按 2048×1024 基准写**，再乘 [s]（= 实际宽/BASE_W）
    //    等比缩放。这样既保留了 v2.0.179 原画法的视觉构成，又能直接支持任意分辨率。

    /**
     * 360° 等距圆柱全景画展廊 —— **v2.0.179 原画法**（仅分辨率与缩放方式改动）。
     *
     * 布局：三组「美白对比人像」均匀分布在 0° / -90° / +90° 三个展台位上，
     * 深色星空背景 + 经纬网格 + 三处经度标注，构成环视画廊。
     */
    private fun generate360Demo(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // 缩放系数：原代码基于 2048 宽，这里按比例还原
        val s = width / BASE_W

        canvas.drawColor(colorBg360)

        // 经纬网格（原 step 128 @2048 → 保持视觉疏密）
        strokePaint.color = colorGrid360
        strokePaint.strokeWidth = 2f * s
        val step = (128f * s).toInt().coerceAtLeast(8)
        var i = 0
        while (i <= width) {
            canvas.drawLine(i.toFloat(), 0f, i.toFloat(), height.toFloat(), strokePaint)
            i += step
        }
        var j = 0
        while (j <= height) {
            canvas.drawLine(0f, j.toFloat(), width.toFloat(), j.toFloat(), strokePaint)
            j += step
        }

        // 经度标注
        textPaint.color = colorLabel360
        textPaint.textSize = 26f * s
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = false
        canvas.drawText("[经度 -180° / 极左]", 100f * s, 60f * s, textPaint)
        canvas.drawText("[经度 0° / 正前方前方]", width / 2f, 60f * s, textPaint)
        canvas.drawText("[经度 +180° / 极右]", width - 100f * s, 60f * s, textPaint)

        // 三个展台位人像（正前 / 左 / 右）
        drawTestingPortrait(canvas, width / 2f, height / 2f, 160f * s, "正前主展台 (A0)")
        drawTestingPortrait(canvas, width / 4f, height / 2f, 140f * s, "左侧展台 (B1)")
        drawTestingPortrait(canvas, width * 3f / 4f, height / 2f, 140f * s, "右侧展台 (C2)")

        // 标题
        textPaint.color = colorGlowCyan
        textPaint.textSize = 45f * s
        textPaint.isFakeBoldText = true
        canvas.drawText(
            "360° 等距圆柱全景画展廊 (Equirectangular 360 Panorama Canvas)",
            width / 2f, 130f * s, textPaint
        )

        return bitmap
    }

    /**
     * 测试人像 —— **v2.0.179 原画法**。
     *
     * 保留原样：桃色圆形脸 + 下巴阴影 + 腮红 + 瞳孔高光 + 眉毛 + 鼻线 + 三段发帽 +
     * 微笑嘴 + 8 个淡褐色斑点（供磨皮验证）+ 底部标签牌。
     *
     * 唯一改动：所有 `Paint` 改用 object 级复用（原实现每次调用 new 十几个 Paint），
     * 头发与标签底板轮廓改用复用的 [hairPath] / [labelPlatePath]。
     */
    private fun drawTestingPortrait(canvas: Canvas, x: Float, y: Float, radius: Float, label: String) {
        val r = radius

        fillPaint.color = colorSkin
        canvas.drawCircle(x, y, r, fillPaint)

        // 下巴阴影
        fillPaint.color = colorChinShadow
        canvas.drawArc(x - r, y + r / 3f, x + r, y + r, 0f, 180f, true, fillPaint)

        // 腮红
        fillPaint.color = colorBlush
        fillPaint.alpha = 110
        canvas.drawCircle(x - r * 0.45f, y + r * 0.15f, r * 0.22f, fillPaint)
        canvas.drawCircle(x + r * 0.45f, y + r * 0.15f, r * 0.22f, fillPaint)
        fillPaint.alpha = 255

        // 眼睛（边缘锐利，用于验证磨皮后细节是否保留）
        fillPaint.color = colorEye
        canvas.drawCircle(x - r * 0.35f, y - r * 0.2f, r * 0.12f, fillPaint)
        canvas.drawCircle(x + r * 0.35f, y - r * 0.2f, r * 0.12f, fillPaint)

        // 瞳孔高光（极小，细节保留检测点）
        fillPaint.color = Color.WHITE
        canvas.drawCircle(x - r * 0.32f, y - r * 0.23f, r * 0.04f, fillPaint)
        canvas.drawCircle(x + r * 0.38f, y - r * 0.23f, r * 0.04f, fillPaint)

        // 眉毛
        strokePaint.color = colorBrow
        strokePaint.strokeWidth = r * 0.06f
        strokePaint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(x - r * 0.55f, y - r * 0.38f, x - r * 0.15f, y - r * 0.34f, strokePaint)
        canvas.drawLine(x + r * 0.15f, y - r * 0.34f, x + r * 0.55f, y - r * 0.38f, strokePaint)

        // 鼻线
        strokePaint.color = colorNose
        strokePaint.strokeWidth = r * 0.05f
        canvas.drawLine(x, y - r * 0.15f, x, y + r * 0.15f, strokePaint)
        canvas.drawLine(x, y + r * 0.15f, x - r * 0.1f, y + r * 0.15f, strokePaint)

        // 头发（大块高对比区域）
        fillPaint.color = colorHair
        hairPath.reset()
        hairPath.addArc(x - r * 1.1f, y - r * 1.1f, x + r * 1.1f, y - r * 0.2f, 180f, 180f)
        canvas.drawPath(hairPath, fillPaint)

        // 微笑的嘴
        fillPaint.color = colorMouth
        canvas.drawArc(
            x - r * 0.35f, y + r * 0.2f, x + r * 0.35f, y + r * 0.5f,
            0f, 180f, true, fillPaint
        )

        // --- 人工斑点（淡褐色）---
        // 刻意用接近肤色的浅褐，让双边磨皮滤镜能平滑掉，直观展示实时美颜效果。
        fillPaint.color = colorSpot
        canvas.drawCircle(x + r * 0.3f, y + r * 0.05f, r * 0.045f, fillPaint)
        canvas.drawCircle(x - r * 0.28f, y + r * 0.06f, r * 0.05f, fillPaint)
        canvas.drawCircle(x + r * 0.22f, y + r * 0.12f, r * 0.038f, fillPaint)
        canvas.drawCircle(x - r * 0.22f, y + r * 0.14f, r * 0.041f, fillPaint)
        canvas.drawCircle(x - r * 0.1f, y - r * 0.05f, r * 0.035f, fillPaint)
        canvas.drawCircle(x + r * 0.12f, y - r * 0.04f, r * 0.043f, fillPaint)
        // 额头
        canvas.drawCircle(x - r * 0.2f, y - r * 0.6f, r * 0.045f, fillPaint)
        canvas.drawCircle(x + r * 0.25f, y - r * 0.58f, r * 0.038f, fillPaint)

        // 标签牌
        textPaint.color = colorLabelText
        textPaint.textSize = r * 0.14f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.isFakeBoldText = true

        labelPlatePath.reset()
        labelPlatePath.addRoundRect(
            x - r * 0.7f, y + r * 0.65f, x + r * 0.7f, y + r * 0.95f,
            12f, 12f, Path.Direction.CW
        )
        fillPaint.color = colorLabelPlate
        canvas.drawPath(labelPlatePath, fillPaint)
        canvas.drawText(label, x, y + r * 0.85f, textPaint)
    }
}
