package com.example.vr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * Real MediaPipe Face Landmarker (face_landmarker.task, 478 points).
 *
 * Runs in RunningMode.VIDEO so the internal tracker keeps detection stable
 * across frames. Exposes precise facial feature positions (eyes, mouth, chin)
 * that drive the beauty shader, plus an Android FaceDetector fallback when the
 * MediaPipe model is missing.
 */
class MediaPipeFaceManager(private val context: Context) {
    companion object {
        private const val TAG = "MediaPipeFaceManager"
        private const val MODEL_PATH = "face_landmarker.task"
    }

    private var faceLandmarker: FaceLandmarker? = null
    private var isInitialized = false
    private var lastTimestampMs = 0L

    // v2.0.156：MediaPipe 不可用时会走 FaceDetector 兜底（x86 模拟器等），
    // 该路径下每次检测都 new 一个 FaceDetector 是没必要的开销 —— 实例与结果数组都复用，
    // 只在输入尺寸变化时重建。（FaceDetector 自 API 31 起标记废弃，但仍是唯一的内置兜底。）
    private var fallbackDetector: android.media.FaceDetector? = null
    private var fallbackDetectorW = 0
    private var fallbackDetectorH = 0
    private var fallbackFaces: Array<android.media.FaceDetector.Face?>? = null

    init {
        initializeFaceLandmarker()
    }

    private fun initializeFaceLandmarker() {
        try {
            // Check if model file exists in assets before trying to load it
            val assetExists = try {
                context.assets.open(MODEL_PATH).close()
                true
            } catch (e: Exception) {
                false
            }

            if (!assetExists) {
                Log.w(TAG, "MediaPipe face_landmarker.task model file is missing in assets. Running on hybrid smart CPU tracker fallback mode.")
                return
            }

            val baseOptionsBuilder = BaseOptions.builder()
                .setModelAssetPath(MODEL_PATH)

            val optionsBuilder = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptionsBuilder.build())
                .setMinFaceDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setNumFaces(3) // v2.0.164：多人脸（最多 3 张），主脸取尺度最大的一张
                .setRunningMode(RunningMode.VIDEO)

            faceLandmarker = FaceLandmarker.createFromOptions(context, optionsBuilder.build())
            isInitialized = true
            Log.i(TAG, "Google MediaPipe FaceLandmarker (VIDEO mode) successfully initialized!")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaPipe FaceLandmarker. Using smart fallback face tracker.", e)
            isInitialized = false
        }
    }

    fun isMediaPipeActive(): Boolean = isInitialized && faceLandmarker != null

    /**
     * Detects face landmarks using Google MediaPipe (streaming VIDEO mode).
     * Returns: FaceResult(detected, faceCenter, eyeDistance, eyeLeft, eyeRight, mouth, chin)
     * All coordinates are normalized to [0..1] relative to the input bitmap.
     */
    fun detectFace(bitmap: Bitmap): FaceResult {
        if (!isInitialized || faceLandmarker == null) {
            return fallbackDetect(bitmap)
        }

        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            // Timestamp must be monotonically increasing for VIDEO mode tracking.
            lastTimestampMs = System.currentTimeMillis().coerceAtLeast(lastTimestampMs + 1)
            val result: FaceLandmarkerResult =
                faceLandmarker?.detectForVideo(mpImage, lastTimestampMs) ?: return fallbackDetect(bitmap)

            val landmarksList = result.faceLandmarks()
            if (!landmarksList.isNullOrEmpty()) {
                // v2.0.164：多人脸 —— 上面 setNumFaces(3)，这里从最多 3 张脸里选**尺度最大**的一张
                // 作为「主脸」交给 GLSL 管线（两眼距离最长者 ≈ 最近/最大的脸）。
                // ⚠️ GLSL 的面部效果只有一组锚点 uniform，真正的多脸渲染需把 uniform 改成
                // 数组 + shader 内循环，属结构性改造，另行排期；GPUPixel 引擎的 landmarks 是
                // **全量透传**，多脸由库内部处理，不受此限制。
                val candidates = landmarksList.filter { it.size > 454 }
                val landmarks = candidates.maxByOrNull { lmk ->
                    val lx = (lmk[33].x() + lmk[133].x()) / 2f
                    val ly = (lmk[33].y() + lmk[133].y()) / 2f
                    val rx = (lmk[263].x() + lmk[362].x()) / 2f
                    val ry = (lmk[263].y() + lmk[362].y()) / 2f
                    Math.hypot((rx - lx).toDouble(), (ry - ly).toDouble())
                } ?: return fallbackDetect(bitmap)

                if (landmarks.size > 454) {
                    // Eye corners
                    val leftEyeOuter = landmarks[33]
                    val leftEyeInner = landmarks[133]
                    val rightEyeOuter = landmarks[263]
                    val rightEyeInner = landmarks[362]
                    // Nose / cheeks / chin
                    val noseTip = landmarks[4]
                    val leftCheek = landmarks[234]
                    val rightCheek = landmarks[454]
                    val chin = landmarks[152]
                    // Mouth
                    val mouthLeft = landmarks[61]
                    val mouthRight = landmarks[291]
                    val mouthTop = landmarks[13]
                    val mouthBottom = landmarks[14]

                    val leftEyeX = (leftEyeOuter.x() + leftEyeInner.x()) / 2f
                    val leftEyeY = (leftEyeOuter.y() + leftEyeInner.y()) / 2f
                    val rightEyeX = (rightEyeOuter.x() + rightEyeInner.x()) / 2f
                    val rightEyeY = (rightEyeOuter.y() + rightEyeInner.y()) / 2f

                    val eyeDistance = Math.hypot(
                        (rightEyeX - leftEyeX).toDouble(),
                        (rightEyeY - leftEyeY).toDouble()
                    ).toFloat()

                    // Mouth center (average of the 4 mouth corners/edges)
                    val mouthX = (mouthLeft.x() + mouthRight.x() + mouthTop.x() + mouthBottom.x()) / 4f
                    val mouthY = (mouthLeft.y() + mouthRight.y() + mouthTop.y() + mouthBottom.y()) / 4f

                    // v2.0.159：嘴部形状 —— 半宽用嘴角左右缘（61 / 291），
                    // 半高用**外**唇上下缘（0 = 上唇顶、17 = 下唇底；13/14 是内唇，偏小），
                    // 倾角用嘴角连线。这些值让 shader 能画一个贴合唇形的椭圆而不是正圆。
                    val mouthUpperLip = landmarks[0]
                    val mouthLowerLip = landmarks[17]
                    val mouthHalfWidth = Math.abs(mouthRight.x() - mouthLeft.x()) / 2f
                    val mouthHalfHeight = Math.abs(mouthLowerLip.y() - mouthUpperLip.y()) / 2f
                    val mouthAngle = Math.atan2(
                        (mouthRight.y() - mouthLeft.y()).toDouble(),
                        (mouthRight.x() - mouthLeft.x()).toDouble()
                    ).toFloat()

                    // Face center: horizontal center of the jaw/cheeks blended with the nose,
                    // vertical center between the eye line and the chin.
                    val centerX = ((leftCheek.x() + rightCheek.x()) / 2f + noseTip.x()) * 0.5f
                    val centerY = (leftEyeY + rightEyeY) / 2f * 0.5f + (chin.y() + noseTip.y()) / 2f * 0.5f

                    Log.v(TAG, "MediaPipe detected face! Center: ($centerX, $centerY), EyeDistance: $eyeDistance")
                    return FaceResult(
                        detected = true,
                        centerX = centerX,
                        centerY = centerY,
                        eyeDistance = eyeDistance,
                        eyeLeftX = leftEyeX,
                        eyeLeftY = leftEyeY,
                        eyeRightX = rightEyeX,
                        eyeRightY = rightEyeY,
                        mouthX = mouthX,
                        mouthY = mouthY,
                        chinX = chin.x(),
                        chinY = chin.y(),
                        mouthHalfWidth = mouthHalfWidth,
                        mouthHalfHeight = mouthHalfHeight,
                        mouthAngle = mouthAngle,
                        faceCount = candidates.size,
                        // v117 修复：此前漏传该字段（默认 false），于是 shader 里 uHasDetailed 恒为 0，
                        // MediaPipe 算出的眼/嘴/下巴精细点位全部被丢弃，只能退回粗略中心锚点。
                        hasDetailedLandmarks = true
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaPipe detection error, using fallback", e)
        }

        return fallbackDetect(bitmap)
    }

    /**
     * Highly stable fallback face tracker utilizing Android's built-in FaceDetector
     */
    private fun fallbackDetect(bitmap: Bitmap): FaceResult {
        try {
            // FaceDetector requires RGB_565 config
            val rgb565Bmp = if (bitmap.config != Bitmap.Config.RGB_565) {
                bitmap.copy(Bitmap.Config.RGB_565, false)
            } else {
                bitmap
            }

            val detector = if (fallbackDetector == null ||
                fallbackDetectorW != bitmap.width || fallbackDetectorH != bitmap.height
            ) {
                android.media.FaceDetector(bitmap.width, bitmap.height, 3).also {
                    fallbackDetector = it
                    fallbackDetectorW = bitmap.width
                    fallbackDetectorH = bitmap.height
                }
            } else {
                fallbackDetector!!
            }
            val faces = fallbackFaces
                ?: arrayOfNulls<android.media.FaceDetector.Face>(3).also { fallbackFaces = it }
            faces[0] = null
            val count = detector.findFaces(rgb565Bmp, faces)

            if (rgb565Bmp != bitmap) {
                rgb565Bmp.recycle()
            }

            if (count > 0) {
                // v2.0.164：兜底检测同样支持多脸（最多 3 张），与 MediaPipe 路径一致，
                // 取**眼睛距离最大**的一张作为主脸
                var best: android.media.FaceDetector.Face? = null
                var bestDist = 0f
                for (i in 0 until minOf(count, faces.size)) {
                    val f = faces[i] ?: continue
                    val d = f.eyesDistance()
                    if (d > bestDist) {
                        bestDist = d
                        best = f
                    }
                }
                val face = best ?: return FaceResult.default()
                val pt = android.graphics.PointF()
                face.getMidPoint(pt)
                val dist = face.eyesDistance()

                val centerX = pt.x / bitmap.width.toFloat()
                val centerY = pt.y / bitmap.height.toFloat()
                val eyeDistance = dist / bitmap.width.toFloat()

                return FaceResult(true, centerX, centerY, eyeDistance, faceCount = count)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallback FaceDetector failed", e)
        }
        return FaceResult.default()
    }

    fun release() {
        try {
            faceLandmarker?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaPipe face landmarker", e)
        }
        faceLandmarker = null
        isInitialized = false
        // v2.0.156：兜底检测器与结果数组一并释放
        fallbackDetector = null
        fallbackFaces = null
        fallbackDetectorW = 0
        fallbackDetectorH = 0
    }

    data class FaceResult(
        val detected: Boolean,
        val centerX: Float,
        val centerY: Float,
        val eyeDistance: Float,
        // Precise 468-point features (normalized), only meaningful when detected via MediaPipe
        val eyeLeftX: Float = 0f,
        val eyeLeftY: Float = 0f,
        val eyeRightX: Float = 0f,
        val eyeRightY: Float = 0f,
        val mouthX: Float = 0f,
        val mouthY: Float = 0f,
        val chinX: Float = 0f,
        val chinY: Float = 0f,
        // v2.0.159：嘴部形状（归一化到采样图，与 eyeDistance 同尺度）。
        // 供 shader 做「椭圆软遮罩」，取代原先「以嘴中心为圆心的正圆」——
        // 嘴唇是横向长条，用正圆必然「圆小涂不到嘴角、圆大溢出到下巴」。
        val mouthHalfWidth: Float = 0f,
        val mouthHalfHeight: Float = 0f,
        val mouthAngle: Float = 0f,
        /** v2.0.164：本次检测到的人脸数量（最多 3），供日志/调试与后续多脸渲染使用 */
        val faceCount: Int = 1,
        val hasDetailedLandmarks: Boolean = false
    ) {
        companion object {
            fun default(): FaceResult = FaceResult(
                detected = false,
                centerX = 0.5f,
                centerY = 0.45f,
                eyeDistance = 0.14f
            )
        }
    }
}
