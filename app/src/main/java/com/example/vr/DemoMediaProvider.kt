package com.example.vr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

object DemoMediaProvider {

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

    fun loadDemoBitmap(id: String): Bitmap {
        return when (id) {
            "demo_360_beauty" -> generate360Demo()
            "demo_180_dome" -> generate180Demo()
            "demo_fisheye_portrait" -> generateFisheyeDemo()
            "demo_3d_sbs_portrait" -> generate3D_SBS_Demo()
            else -> generateStandardDemo()
        }
    }

    private fun generateStandardDemo(): Bitmap {
        val width = 1280
        val height = 720
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Background rich dark violet gradient look
        canvas.drawColor(Color.parseColor("#181924"))
        
        // Draw decorative grids
        val gridPaint = Paint().apply {
            color = Color.parseColor("#2C2D3D")
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        for (i in 0..width step 100) {
            canvas.drawLine(i.toFloat(), 0f, i.toFloat(), height.toFloat(), gridPaint)
        }
        for (j in 0..height step 100) {
            canvas.drawLine(0f, j.toFloat(), width.toFloat(), j.toFloat(), gridPaint)
        }

        // Title and header
        val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 45f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("标准人像平面测试图 (Aesthetic Portrait Calibration)", (width / 2).toFloat(), 80f, textPaint)

        // Draw Portrait Face
        drawTestingPortrait(canvas, (width / 2).toFloat(), (height / 2 + 30).toFloat(), 180f, "中心测试人脸")

        // Draw instructions text card
        val captionPaint = Paint().apply {
            color = Color.parseColor("#8E92B0")
            textSize = 28f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("滑动右侧美颜面板 -> 拖动 '磨皮/美肤强度'，面部的微细褐色斑点将被实时平滑滤除", (width / 2).toFloat(), height - 45f, captionPaint)

        return bitmap
    }

    private fun generateFisheyeDemo(): Bitmap {
        val width = 1024
        val height = 1024
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(Color.parseColor("#0E0F14"))

        // Draw concentric circular radial grids representing physical fisheye barrel distortion
        val circlePaint = Paint().apply {
            color = Color.parseColor("#1F3B4D")
            strokeWidth = 3f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        val center = 512f
        for (r in 100..450 step 80) {
            canvas.drawCircle(center, center, r.toFloat(), circlePaint)
        }

        // Draw radiating spokes
        for (angle in 0 until 360 step 30) {
            val rad = Math.toRadians(angle.toDouble())
            val x = center + (450 * Math.cos(rad)).toFloat()
            val y = center + (450 * Math.sin(rad)).toFloat()
            canvas.drawLine(center, center, x, y, circlePaint)
        }

        // Draw central portrait card
        drawTestingPortrait(canvas, center, center, 140f, "鱼眼镜头视区人像")

        val textPaint = Paint().apply {
            color = Color.parseColor("#00E5FF") // Cyan GLow
            textSize = 34f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("鱼眼镜头畸变映射 (Fisheye Lens Projection Mode)", center, 80f, textPaint)

        return bitmap
    }

    private fun generate3D_SBS_Demo(): Bitmap {
        val width = 2048
        val height = 1024
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Fill background
        canvas.drawColor(Color.parseColor("#10121A"))

        // Split divider line
        val dividerPaint = Paint().apply {
            color = Color.parseColor("#00F2FE")
            strokeWidth = 6f
        }
        canvas.drawLine(1024f, 0f, 1024f, 1024f, dividerPaint)

        // Draw Left Eye layout
        val titlePaint = Paint().apply {
            color = Color.parseColor("#FF007F") // Neon Pink
            textSize = 42f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("【左眼 L - View】", 512f, 90f, titlePaint)
        // Face shifted slightly left to simulate horizontal parallax视差 depth
        drawTestingPortrait(canvas, 492f, 512f, 180f, "3D 立体人像 (L)")

        // Draw Right Eye layout
        titlePaint.color = Color.parseColor("#00F2FE") // Neon Cyan
        canvas.drawText("【右眼 R - View】", 1536f, 90f, titlePaint)
        // Face shifted slightly right to simulate horizontal parallax视差 depth
        drawTestingPortrait(canvas, 1556f, 512f, 180f, "3D 立体人像 (R)")

        // Bottom label
        val sbsPaint = Paint().apply {
            color = Color.WHITE
            textSize = 28f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("[双眼具有 24px 投影水平视差 - 配合 VR 眼镜呈现沉浸三维深度]", 1024f, 960f, sbsPaint)

        return bitmap
    }

    private fun generate360Demo(): Bitmap {
        val width = 2048
        val height = 1024
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Fill background with simulated space constellation look
        canvas.drawColor(Color.parseColor("#0D0E15"))

        // Coordinates grid
        val gridPaint = Paint().apply {
            color = Color.parseColor("#222435")
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        for (i in 0..width step 128) {
            canvas.drawLine(i.toFloat(), 0f, i.toFloat(), height.toFloat(), gridPaint)
        }
        for (j in 0..height step 128) {
            canvas.drawLine(0f, j.toFloat(), width.toFloat(), j.toFloat(), gridPaint)
        }

        // Draw Panorama indicators
        val labelPaint = Paint().apply {
            color = Color.parseColor("#626685")
            textSize = 26f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("[经度 -180° / 极左]", 100f, 60f, labelPaint)
        canvas.drawText("[经度 0° / 正前方前方]", 1024f, 60f, labelPaint)
        canvas.drawText("[经度 +180° / 极右]", 1948f, 60f, labelPaint)

        // Center front: Portrait 1
        drawTestingPortrait(canvas, 1024f, 512f, 160f, "正前主展台 (A0)")

        // Panned Left (Looking -90 degrees): Portrait 2
        drawTestingPortrait(canvas, 512f, 512f, 140f, "左侧展台 (B1)")

        // Panned Right (Looking +90 degrees): Portrait 3
        drawTestingPortrait(canvas, 1536f, 512f, 140f, "右侧展台 (C2)")

        // Draw title overlay
        val textPaint = Paint().apply {
            color = Color.parseColor("#00F2FE")
            textSize = 45f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("360° 等距圆柱全景画展廊 (Equirectangular 360 Panorama Canvas)", 1024f, 130f, textPaint)

        return bitmap
    }

    private fun generate180Demo(): Bitmap {
        val width = 1024
        val height = 1024
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        canvas.drawColor(Color.parseColor("#0B0C10"))

        // Dome grid
        val gridPaint = Paint().apply {
            color = Color.parseColor("#1F2833")
            strokeWidth = 3f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        
        val center = 512f
        canvas.drawCircle(center, center, 480f, gridPaint)
        canvas.drawCircle(center, center, 300f, gridPaint)
        canvas.drawCircle(center, center, 120f, gridPaint)
        
        canvas.drawLine(center, 32f, center, 992f, gridPaint)
        canvas.drawLine(32f, center, 992f, center, gridPaint)

        // Portraits distributed in dome
        drawTestingPortrait(canvas, center, center - 160f, 120f, "穹顶主视角")
        drawTestingPortrait(canvas, center - 240f, center + 120f, 100f, "左倾侧视角")
        drawTestingPortrait(canvas, center + 240f, center + 120f, 100f, "右倾侧视角")

        val titlePaint = Paint().apply {
            color = Color.parseColor("#AB47BC") // Lilac
            textSize = 36f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("180° Half Dome 穹幕投影测试", center, 80f, titlePaint)

        return bitmap
    }

    // Helper to draw a testing head portrait with skin tone gradients and fine artificial spots blemishes
    private fun drawTestingPortrait(canvas: Canvas, x: Float, y: Float, radius: Float, label: String) {
        val facePaint = Paint().apply {
            color = Color.parseColor("#FFDAB9") // Peach skin tone
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(x, y, radius, facePaint)

        // Draw shadow under chin
        val chinShadow = Paint().apply {
            color = Color.parseColor("#F5B99F")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawArc(
            x - radius, y + radius / 3, x + radius, y + radius,
            0f, 180f, true, chinShadow
        )

        // Draw Blush cheeks
        val blushPaint = Paint().apply {
            color = Color.parseColor("#FFA0A0")
            style = Paint.Style.FILL
            isAntiAlias = true
            alpha = 110
        }
        canvas.drawCircle(x - radius * 0.45f, y + radius * 0.15f, radius * 0.22f, blushPaint)
        canvas.drawCircle(x + radius * 0.45f, y + radius * 0.15f, radius * 0.22f, blushPaint)

        // Draw eyes (distinct edge features, must remain razor-sharp after beauty smoothing)
        val eyePaint = Paint().apply {
            color = Color.parseColor("#1B1A55") // Deep blue eyes
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
            color = Color.parseColor("#31363F")
            strokeWidth = radius * 0.06f
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(x - radius * 0.55f, y - radius * 0.38f, x - radius * 0.15f, y - radius * 0.34f, browPaint)
        canvas.drawLine(x + radius * 0.15f, y - radius * 0.34f, x + radius * 0.55f, y - radius * 0.38f, browPaint)

        // Nose line
        val nosePaint = Paint().apply {
            color = Color.parseColor("#ECA78C")
            strokeWidth = radius * 0.05f
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(x, y - radius * 0.15f, x, y + radius * 0.15f, nosePaint)
        canvas.drawLine(x, y + radius * 0.15f, x - radius * 0.1f, y + radius * 0.15f, nosePaint)

        // Hair (large contrasting region)
        val hairPaint = Paint().apply {
            color = Color.parseColor("#2C1D13") // Brown hair
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val hairPath = Path()
        // Top hair cap
        hairPath.addArc(x - radius * 1.1f, y - radius * 1.1f, x + radius * 1.1f, y - radius * 0.2f, 180f, 180f)
        canvas.drawPath(hairPath, hairPaint)

        // Draw smiling mouth in red
        val mouthPaint = Paint().apply {
            color = Color.parseColor("#E94560")
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
            color = Color.parseColor("#D2B48C") // Light brown freckles color
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
            color = Color.parseColor("#2E3842")
            textSize = radius * 0.14f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
        // Draw label background plate
        val platePaint = Paint().apply {
            color = Color.parseColor("#A0E2E7EA")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawRoundRect(
            x - radius * 0.7f, y + radius * 0.65f, x + radius * 0.7f, y + radius * 0.95f,
            12f, 12f, platePaint
        )
        canvas.drawText(label, x, y + radius * 0.85f, labelPaint)
    }
}
