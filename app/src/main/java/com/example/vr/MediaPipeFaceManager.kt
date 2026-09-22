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
                .setNumFaces(1)
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
                val landmarks = landmarksList[0] // Get first detected face

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
                android.media.FaceDetector(bitmap.width, bitmap.height, 1).also {
                    fallbackDetector = it
                    fallbackDetectorW = bitmap.width
                    fallbackDetectorH = bitmap.height
                }
            } else {
                fallbackDetector!!
            }
            val faces = fallbackFaces
                ?: arrayOfNulls<android.media.FaceDetector.Face>(1).also { fallbackFaces = it }
            faces[0] = null
            val count = detector.findFaces(rgb565Bmp, faces)

            if (rgb565Bmp != bitmap) {
                rgb565Bmp.recycle()
            }

            if (count > 0 && faces[0] != null) {
                val face = faces[0]!!
                val pt = android.graphics.PointF()
                face.getMidPoint(pt)
                val dist = face.eyesDistance()

                val centerX = pt.x / bitmap.width.toFloat()
                val centerY = pt.y / bitmap.height.toFloat()
                val eyeDistance = dist / bitmap.width.toFloat()

                return FaceResult(true, centerX, centerY, eyeDistance)
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
