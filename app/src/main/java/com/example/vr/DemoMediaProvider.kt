package com.example.vr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache

/**
 * 内置演示图（合成测试卡）提供者。
 *
 * v2.0.180 性能优化（缩略图 / 测试卡加载）：
 *  - **LruCache 缓存**：这些测试卡是**完全确定性**的（同一 id 每次绘制结果一模一样），
 *    原先每次切到该媒体都重新 `createBitmap` + 全量 Canvas 绘制（2048×1024 ARGB_8888 = 8 MB，
 *    内部还重复 `drawTestingPortrait` 多达 3 次、每次都 new 一堆 Paint）→ 单次几十~上百 ms 且
 *    连续切换时产生大量大对象触发 GC 抖动。现改为按 id 缓存**母本**位图。
 *  - **Paint / 颜色常量化**：原先每个生成函数内部 `Paint().apply{}` + `Color.parseColor(...)`
 *    重复构造（一次生成可 new 出十几个 Paint + 几十次 parseColor）。现全部提为 object 级
 *    复用的常量，绘制时不再重复分配。
 *  - **绘制尺寸下调**：测试卡最终只是喂给 GL 上传成纹理再做 GL_LINEAR 采样，
 *    原先 2048/1280 的宽度远超实际需要。现按 ~1024 上限等比缩放（宽高比保持不变，
 *    因为 `VRGLRenderer.imageWidth/Height` 靠宽高比决定投影），显存与生成耗时同步下降。
 *
 * ⚠️ **recycle 契约（重要）**：`VRGLRenderer` 在上传纹理后会对传入的 Bitmap 调 `recycle()`
 * （见 `VRGLRenderer.drawFrame` 内 `GLUtils.texImage2D` 之后）。因此本 Provider **对外一律返回
 * 母本的副本**（`copy()`），母本留在缓存里不被回收 —— 否则第二次取同一 id 会拿到已回收的实例
 * 并抛 `IllegalStateException: Can't call texImage2D on a recycled bitmap`。
 * 若调用方只想探测"是否已有缓存"而不想付出复制代价，用 [peekCached]。
 */
object DemoMediaProvider {

    /** 缓存母本（不可交给外部 recycle）。按"1/8 可用堆"上限，超出按 LRU 淘汰。 */
    private val cache: LruCache<String, Bitmap> by lazy {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt()
        object : LruCache<String, Bitmap>(maxKb.coerceAtLeast(8 * 1024)) {
            override fun sizeOf(key: String, value: Bitmap): Int =
                value.byteCount / 1024
            override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
                // 淘汰时回收母本（仅当确实是被换出而非替换成同一对象）
                if (evicted && oldValue !== newValue && !oldValue.isRecycled) {
                    oldValue.recycle()
                }
            }
        }
    }

    /** 生成统计（便于排查缓存命中率） */
    private var genCount = 0L
    private var cacheHitCount = 0L

    // ===== 复用常量：Paint 与颜色（避免每次生成重复构造）=====

    private val colorBgStandard = Color.parseColor("#181924")
    private val colorGridStandard = Color.parseColor("#2C2D3D")
    private val colorCaption = Color.parseColor("#8E92B0")

    private val colorBgDark = Color.parseColor("#0E0F14")
    private val colorFisheyeGrid = Color.parseColor("#1F3B4D")
    private val colorCyanGlow = Color.parseColor("#00E5FF")

    private val colorBgSbs = Color.parseColor("#10121A")
    private val colorSbsDivider = Color.parseColor("#00F2FE")
    private val colorNeonPink = Color.parseColor("#FF007F")

    private val colorBg360 = Color.parseColor("#0D0E15")
    private val colorGrid360 = Color.parseColor("#222435")
    private val colorLabel360 = Color.parseColor("#626685")

    private val colorBg180 = Color.parseColor("#0B0C10")
    private val colorGrid180 = Color.parseColor("#1F2833")
    private val colorLilac = Color.parseColor("#AB47BC")

    // drawTestingPortrait 内部用到的颜色
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

    private val labelPlatePath = Path()

    // Preset list of demoware media content
    val demoMediaList = listOf(
        MediaItem(
            id = "demo_360_beauty",
            title = "【全景360°】美颜环密画廊",
            uri = null,
            isVideo = false,
            isDemo = true,
            description = "360度全景等距矩形图像，环绕分布多组美白对比人像，包含仿真皮肤瑕疵以供实时调节美颜效果。"
        ),
        MediaItem(
            id = "demo_180_dome",
            title = "【全景180°】仰望穹幕星空",
            uri = null,
            isVideo = false,
            isDemo = true,
            description = "180度前半球 Dome 环幕照片，模拟宽视角穹顶星空与人像特写。"
        ),
        MediaItem(
            id = "demo_fisheye_portrait",
            title = "【鱼眼VR】极圈广角广域人像",
            uri = null,
            isVideo = false,
            isDemo = true,
            description = "模拟超广角鱼眼镜头拍摄出的桶形畸变图像，用于极度广角场景调试。"
        ),
        MediaItem(
            id = "demo_3d_sbs_portrait",
            title = "【立体3D】左右分屏深度测试",
            uri = null,
            isVideo = false,
            isDemo = true,
            description = "3D SBS（Side-by-Side 立体并排）格式，左右视图含瞳距视差。适合放入智能手机VR眼镜盒后呈现身临其境的深度立体感。"
        ),
        MediaItem(
            id = "demo_standard_portrait",
            title = "【标准常规】近景美颜人像测试",
            uri = null,
            isVideo = false,
            isDemo = true,
            description = "标准16:9平面图像，中心绘制面部颗粒与淡褐斑点。美白与磨皮滤镜的理想验证卡。"
        )
    )

    /**
     * 取指定 id 的演示图**副本**（可安全交给渲染器回收）。
     *
     * 命中缓存时直接复制母本，不再走 Canvas 绘制；未命中才生成并缓存母本。
     * 之所以返回副本而非母本本身，见文件头「recycle 契约」说明。
     */
    fun loadDemoBitmap(id: String): Bitmap {
        val master = obtainMaster(id)
        // 返回副本：渲染器上传纹理后会 recycle 传入的 bitmap，母本必须留存
        return master.copy(master.config ?: Bitmap.Config.ARGB_8888, false)
            ?: master
    }

    /** 探测是否已有缓存母本（不复制、不生成）。调用方可据此避免重复申请大对象。 */
    fun peekCached(id: String): Boolean = cache.get(id) != null

    /** 清空缓存（如内存吃紧时可由调用方主动释放）。 */
    fun clearCache() {
        cache.evictAll()
    }

    /** 生成/缓存统计，格式 "generated=X hit=Y size=Z" */
    fun cacheStats(): String = "generated=$genCount hit=$cacheHitCount size=${cache.size()}"

    /** 取缓存母本；未命中则生成并写入缓存。母本**不可**交给外部 recycle。 */
    private fun obtainMaster(id: String): Bitmap {
        cache.get(id)?.let {
            if (!it.isRecycled) {
                cacheHitCount++
                return it
            }
            // 已被回收（异常路径）→ 移除后重新生成
            cache.remove(id)
        }
        val fresh = generate(id)
        genCount++
        cache.put(id, fresh)
        return fresh
    }

    private fun generate(id: String): Bitmap = when (id) {
        "demo_360_beauty" -> generate360Demo()
        "demo_180_dome" -> generate180Demo()
        "demo_fisheye_portrait" -> generateFisheyeDemo()
        "demo_3d_sbs_portrait" -> generate3D_SBS_Demo()
        else -> generateStandardDemo()
    }

    private fun generateStandardDemo(): Bitmap {
        // 原 1280x720 → 960x540（16:9 保持不变），生成耗时与显存同降约 44%
        val width = 960
        val height = 540
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(colorBgStandard)

        // Draw decorative grids
        val gridPaint = Paint().apply {
            color = colorGridStandard
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        for (i in 0..width step 80) {
            canvas.drawLine(i.toFloat(), 0f, i.toFloat(), height.toFloat(), gridPaint)
        }
        for (j in 0..height step 80) {
            canvas.drawLine(0f, j.toFloat(), width.toFloat(), j.toFloat(), gridPaint)
        }

        // Title and header
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 34f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("标准人像平面测试图 (Aesthetic Portrait Calibration)", (width / 2).toFloat(), 62f, textPaint)

        // Draw Portrait Face
        drawTestingPortrait(canvas, (width / 2).toFloat(), (height / 2 + 22).toFloat(), 135f, "中心测试人脸")

        // Draw instructions text card
        val captionPaint = Paint().apply {
            color = colorCaption
            textSize = 21f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("滑动右侧美颜面板 -> 拖动 '磨皮/美肤强度'，面部的微细褐色斑点将被实时平滑滤除", (width / 2).toFloat(), height - 34f, captionPaint)

        return bitmap
    }

    private fun generateFisheyeDemo(): Bitmap {
        // 原 1024x1024 → 768x768（1:1 保持不变）
        val width = 768
        val height = 768
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(colorBgDark)

        val circlePaint = Paint().apply {
            color = colorFisheyeGrid
            strokeWidth = 3f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        val center = width / 2f
        val maxR = center * 0.88f
        for (r in 75..335 step 60) {
            canvas.drawCircle(center, center, r.toFloat(), circlePaint)
        }

        // Draw radiating spokes
        for (angle in 0 until 360 step 30) {
            val rad = Math.toRadians(angle.toDouble())
            val x = center + (maxR * Math.cos(rad)).toFloat()
            val y = center + (maxR * Math.sin(rad)).toFloat()
            canvas.drawLine(center, center, x, y, circlePaint)
        }

        // Draw central portrait card
        drawTestingPortrait(canvas, center, center, 105f, "鱼眼镜头视区人像")

        val textPaint = Paint().apply {
            color = colorCyanGlow
            textSize = 25f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("鱼眼镜头畸变映射 (Fisheye Lens Projection Mode)", center, 60f, textPaint)

        return bitmap
    }

    private fun generate3D_SBS_Demo(): Bitmap {
        // 原 2048x1024 → 1024x512（2:1 保持不变）
        val width = 1024
        val height = 512
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(colorBgSbs)

        // Split divider line
        val dividerPaint = Paint().apply {
            color = colorSbsDivider
            strokeWidth = 3f
        }
        val half = width / 2f
        canvas.drawLine(half, 0f, half, height.toFloat(), dividerPaint)

        // Draw Left Eye layout
        val titlePaint = Paint().apply {
            color = colorNeonPink
            textSize = 21f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("【左眼 L - View】", width / 4f, 45f, titlePaint)
        // Face shifted slightly left to simulate horizontal parallax视差 depth
        drawTestingPortrait(canvas, width / 4f - 10f, height / 2f, 90f, "3D 立体人像 (L)")

        // Draw Right Eye layout
        titlePaint.color = colorCyanGlow
        canvas.drawText("【右眼 R - View】", width * 3f / 4f, 45f, titlePaint)
        // Face shifted slightly right to simulate horizontal parallax视差 depth
        drawTestingPortrait(canvas, width * 3f / 4f + 10f, height / 2f, 90f, "3D 立体人像 (R)")

        // Bottom label
        val sbsPaint = Paint().apply {
            color = Color.WHITE
            textSize = 14f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("[双眼具有 24px 投影水平视差 - 配合 VR 眼镜呈现沉浸三维深度]", half, height - 16f, sbsPaint)

        return bitmap
    }

    private fun generate360Demo(): Bitmap {
        // 原 2048x1024 → 1024x512（等距圆柱 2:1 保持不变）
        val width = 1024
        val height = 512
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(colorBg360)

        // Coordinates grid
        val gridPaint = Paint().apply {
            color = colorGrid360
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        for (i in 0..width step 64) {
            canvas.drawLine(i.toFloat(), 0f, i.toFloat(), height.toFloat(), gridPaint)
        }
        for (j in 0..height step 64) {
            canvas.drawLine(0f, j.toFloat(), width.toFloat(), j.toFloat(), gridPaint)
        }

        // Draw Panorama indicators
        val labelPaint = Paint().apply {
            color = colorLabel360
            textSize = 13f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("[经度 -180° / 极左]", 50f, 30f, labelPaint)
        canvas.drawText("[经度 0° / 正前方前方]", width / 2f, 30f, labelPaint)
        canvas.drawText("[经度 +180° / 极右]", width - 50f, 30f, labelPaint)

        // Center front: Portrait 1
        drawTestingPortrait(canvas, width / 2f, height / 2f, 80f, "正前主展台 (A0)")

        // Panned Left (Looking -90 degrees): Portrait 2
        drawTestingPortrait(canvas, width / 4f, height / 2f, 70f, "左侧展台 (B1)")

        // Panned Right (Looking +90 degrees): Portrait 3
        drawTestingPortrait(canvas, width * 3f / 4f, height / 2f, 70f, "右侧展台 (C2)")

        // Draw title overlay
        val textPaint = Paint().apply {
            color = colorCyanGlow
            textSize = 23f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("360° 等距圆柱全景画展廊 (Equirectangular 360 Panorama Canvas)", width / 2f, 65f, textPaint)

        return bitmap
    }

    private fun generate180Demo(): Bitmap {
        // 原 1024x1024 → 768x768（1:1 保持不变）
        val width = 768
        val height = 768
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(colorBg180)

        // Dome grid
        val gridPaint = Paint().apply {
            color = colorGrid180
            strokeWidth = 3f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        val center = width / 2f
        canvas.drawCircle(center, center, 360f, gridPaint)
        canvas.drawCircle(center, center, 225f, gridPaint)
        canvas.drawCircle(center, center, 90f, gridPaint)

        canvas.drawLine(center, 24f, center, height - 24f, gridPaint)
        canvas.drawLine(24f, center, width - 24f, center, gridPaint)

        // Portraits distributed in dome
        drawTestingPortrait(canvas, center, center - 120f, 90f, "穹顶主视角")
        drawTestingPortrait(canvas, center - 180f, center + 90f, 75f, "左倾侧视角")
        drawTestingPortrait(canvas, center + 180f, center + 90f, 75f, "右倾侧视角")

        val titlePaint = Paint().apply {
            color = colorLilac
            textSize = 27f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("180° Half Dome 穹幕投影测试", center, 60f, titlePaint)

        return bitmap
    }

    // Helper to draw a testing head portrait with skin tone gradients and fine artificial spots blemishes
    private fun drawTestingPortrait(canvas: Canvas, x: Float, y: Float, radius: Float, label: String) {
        val facePaint = Paint().apply {
            color = colorSkin
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(x, y, radius, facePaint)

        // Draw shadow under chin
        val chinShadow = Paint().apply {
            color = colorChinShadow
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawArc(
            x - radius, y + radius / 3, x + radius, y + radius,
            0f, 180f, true, chinShadow
        )

        // Draw Blush cheeks
        val blushPaint = Paint().apply {
            color = colorBlush
            style = Paint.Style.FILL
            isAntiAlias = true
            alpha = 110
        }
        canvas.drawCircle(x - radius * 0.45f, y + radius * 0.15f, radius * 0.22f, blushPaint)
        canvas.drawCircle(x + radius * 0.45f, y + radius * 0.15f, radius * 0.22f, blushPaint)

        // Draw eyes (distinct edge features, must remain razor-sharp after beauty smoothing)
        val eyePaint = Paint().apply {
            color = colorEye
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(x - radius * 0.35f, y - radius * 0.2f, radius * 0.12f, eyePaint)
        canvas.drawCircle(x + radius * 0.35f, y - radius * 0.2f, radius * 0.12f, eyePaint)

        // Pupil shine dots (very fine, ensures detail retention check)
        eyePaint.color = Color.WHITE
        canvas.drawCircle(x - radius * 0.32f, y - radius * 0.23f, radius * 0.04f, eyePaint)
        canvas.drawCircle(x + radius * 0.38f, y - radius * 0.23f, radius * 0.04f, eyePaint)

        // Eyebrows
        val browPaint = Paint().apply {
            color = colorBrow
            strokeWidth = radius * 0.06f
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(x - radius * 0.55f, y - radius * 0.38f, x - radius * 0.15f, y - radius * 0.34f, browPaint)
        canvas.drawLine(x + radius * 0.15f, y - radius * 0.34f, x + radius * 0.55f, y - radius * 0.38f, browPaint)

        // Nose line
        val nosePaint = Paint().apply {
            color = colorNose
            strokeWidth = radius * 0.05f
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(x, y - radius * 0.15f, x, y + radius * 0.15f, nosePaint)
        canvas.drawLine(x, y + radius * 0.15f, x - radius * 0.1f, y + radius * 0.15f, nosePaint)

        // Hair (large contrasting region)
        val hairPaint = Paint().apply {
            color = colorHair
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val hairPath = Path()
        // Top hair cap
        hairPath.addArc(x - radius * 1.1f, y - radius * 1.1f, x + radius * 1.1f, y - radius * 0.2f, 180f, 180f)
        canvas.drawPath(hairPath, hairPaint)

        // Draw smiling mouth in red
        val mouthPaint = Paint().apply {
            color = colorMouth
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawArc(
            x - radius * 0.35f, y + radius * 0.2f, x + radius * 0.35f, y + radius * 0.5f,
            0f, 180f, true, mouthPaint
        )

        // --- ARTIFICIAL BLEMISHES / SPOT SPOTS (淡褐色斑点) ---
        // These are colored close to skin tone so that the bilateral beauty filter can smooth them,
        // illustrating actual real-time beauty effects perfectly to the user!
        val spotPaint = Paint().apply {
            color = colorSpot
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        // Small cluster of acne/spot freckles around cheeks and forehead
        canvas.drawCircle(x + radius * 0.3f, y + radius * 0.05f, radius * 0.045f, spotPaint)
        canvas.drawCircle(x - radius * 0.28f, y + radius * 0.06f, radius * 0.05f, spotPaint)
        canvas.drawCircle(x + radius * 0.22f, y + radius * 0.12f, radius * 0.038f, spotPaint)
        canvas.drawCircle(x - radius * 0.22f, y + radius * 0.14f, radius * 0.041f, spotPaint)

        canvas.drawCircle(x - radius * 0.1f, y - radius * 0.05f, radius * 0.035f, spotPaint)
        canvas.drawCircle(x + radius * 0.12f, y - radius * 0.04f, radius * 0.043f, spotPaint)

        // Forehead spots
        canvas.drawCircle(x - radius * 0.2f, y - radius * 0.6f, radius * 0.045f, spotPaint)
        canvas.drawCircle(x + radius * 0.25f, y - radius * 0.58f, radius * 0.038f, spotPaint)

        // Text label tag
        val labelPaint = Paint().apply {
            color = colorLabelText
            textSize = radius * 0.14f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
        // Draw label background plate（复用 Path 对象，避免每次 new）
        labelPlatePath.reset()
        labelPlatePath.addRoundRect(
            x - radius * 0.7f, y + radius * 0.65f, x + radius * 0.7f, y + radius * 0.95f,
            12f, 12f, Path.Direction.CW
        )
        val platePaint = Paint().apply {
            color = colorLabelPlate
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawPath(labelPlatePath, platePaint)
        canvas.drawText(label, x, y + radius * 0.85f, labelPaint)
    }
}
