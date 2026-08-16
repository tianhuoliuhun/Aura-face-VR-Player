package com.example.vr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Log
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VRGLRenderer(private val context: Context) : GLSurfaceView.Renderer {

    companion object {
        const val TAG = "VRGLRenderer"
    }

    // Volatile settings accessible from Compose UI
    @Volatile var projectionMode = ProjectionMode.STANDARD
    @Volatile var stereoMode = StereoMode.MONO
    @Volatile var beautyLevel = 0.5f // 0.0f (off) to 1.0f (max smoothing)
    @Volatile var brightnessLevel = 0.0f // -0.5f to 0.5f
    @Volatile var contrastLevel = 1.0f // 0.5f to 1.5f
    @Volatile var beautyWhitening = 0.5f
    @Volatile var beautyFaceSlimming = 0.4f
    @Volatile var beautyBigEyes = 0.3f
    @Volatile var beautyDarkCircles = 0.3f
    @Volatile var beautyNoseSlimming = 0.2f
    @Volatile var beautyMouth = 0.2f
    @Volatile var beautyTeethWhitening = 0.3f
    @Volatile var beautyLipstick = 0.3f
    @Volatile var beautyBlush = 0.3f
    @Volatile var beautyEyebrows = 0.4f
    @Volatile var beautyLongLegs = 0.4f
    @Volatile var beautySmallHead = 0.3f
    @Volatile var isSplitScreenVR = false // Cardboard mode
    @Volatile var gyroEnabled = true
    @Volatile var isVideoActive = false
    @Volatile var isMirrored = false
    @Volatile var monoEyePreference = 1 // 1 for left half, 0 for right half
    // 双中心变形：2:1 全景视频左右半区各自以 25%/75% 为变形中心（VR_360 + MONO）
    @Volatile var warpDualCenter = false
    @Volatile var warpMode = WarpMode.NONE
    @Volatile var cylinderCurvature = 0.3f
    @Volatile var videoWidth = 0
    @Volatile var videoHeight = 0
    // Dimensions of the currently displayed image (used for 2D aspect fitting)
    @Volatile var imageWidth = 0
    @Volatile var imageHeight = 0
    @Volatile var maxFps = 0 // Frame rate limit: 0 (unlimited), 12, 18, 24, 30, 48, 60, 90, 120
    private var lastFrameTimeMs = 0L

    // Zoom/FOV
    @Volatile var fovDeg = 75f // Field of View (Pinch to Zoom)

    // Manual panning state (if gyro is off or combined)
    @Volatile var manualYaw = 0f
    @Volatile var manualPitch = 0f

    // Feel/touch sensitivity (drag panning and fling inertia)
    @Volatile var panSensitivity = 0.18f
    @Volatile var flingSensitivity = 0.18f

    // Inertia fling velocities
    @Volatile var flingVelocityYaw = 0f
    @Volatile var flingVelocityPitch = 0f

    // Gyroscope rotation matrix passed from SensorEventListener
    private val gyroRotationMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val gyroSyncLock = Any()

    fun updateGyroRotationMatrix(matrix: FloatArray) {
        synchronized(gyroSyncLock) {
            System.arraycopy(matrix, 0, gyroRotationMatrix, 0, 16)
        }
    }

    // Texture management
    private var imageTextureId = -1
    private var videoTextureId = -1
    var videoSurfaceTexture: SurfaceTexture? = null
    @Volatile var onVideoSurfaceCreated: ((SurfaceTexture) -> Unit)? = null
    
    // Google MediaPipe Face Landmarking & Smart Detection Manager
    private var mediaPipeManager: MediaPipeFaceManager? = null

    // Real-time Face Detection parameters updated smoothly via background thread
    @Volatile var faceDetectedUniform = 0
    @Volatile var faceCenterXUniform = 0.5f
    @Volatile var faceCenterYUniform = 0.45f
    @Volatile var eyeDistanceUniform = 0.14f
    // Precise MediaPipe 468-point features (normalized UV space)
    @Volatile var hasDetailedLandmarks = 0
    @Volatile var eyeLeftXUniform = 0f
    @Volatile var eyeLeftYUniform = 0f
    @Volatile var eyeRightXUniform = 0f
    @Volatile var eyeRightYUniform = 0f
    @Volatile var mouthXUniform = 0f
    @Volatile var mouthYUniform = 0f
    @Volatile var chinXUniform = 0f
    @Volatile var chinYUniform = 0f

    // Face detection sampling resolution. The GL thread reads a larger center
    // region (512) so faces off-center are still captured, then it is downscaled
    // to 256x256 for the MediaPipe landmarker.
    private val faceSampleSize = 256
    private val faceSampleArea = 256 * 256
    private val faceReadSize = 512

    private val faceExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val isDetectingFace = java.util.concurrent.atomic.AtomicBoolean(false)
    private var faceFrameCounter = 0

    // v84：检测线程缓冲复用（避免每帧创建 Bitmap/数组）
    private var faceArgBuffer: IntArray? = null
    private var faceBmp: Bitmap? = null
    private var faceScaledBmp: Bitmap? = null

    // Frame transfer between the GL thread and the face-detection executor.
    // The GL thread publishes a freshly allocated frame, and the executor consumes it,
    // so there is no shared mutable array being written and read concurrently.
    private val faceFrameLock = Any()
    private var pendingFaceFrame: ByteArray? = null
    private var pendingFaceFrameW = 0
    private var pendingFaceFrameH = 0

    // Video frame sync flag
    private var isVideoFrameAvailable = false

    // Queue for loading Bitmap securely on the GL thread
    private var pendingBitmap: Bitmap? = null
    private val bitmapLock = Any()

    fun updateImage(bitmap: Bitmap) {
        imageWidth = bitmap.width
        imageHeight = bitmap.height
        synchronized(bitmapLock) {
            pendingBitmap = bitmap
        }
    }

    // Geometry caches
    private var quadPositionBuffer: FloatBuffer = GeometryHelper.createFloatBuffer(GeometryHelper.quadPositions)
    private var quadTexCoordBuffer: FloatBuffer = GeometryHelper.createFloatBuffer(GeometryHelper.quadTexCoords)

    private var sphere360Positions: FloatBuffer? = null
    private var sphere360TexCoords: FloatBuffer? = null
    private var sphere360VertexCount = 0

    private var sphere180Positions: FloatBuffer? = null
    private var sphere180TexCoords: FloatBuffer? = null
    private var sphere180VertexCount = 0

    // 盒子模式（Box Mode）：六面体细分网格 + 逆向射线 UV
    private var boxPositions: FloatBuffer? = null
    private var boxTexCoords: FloatBuffer? = null
    private var boxVertexCount = 0

    // GL SL shaders variables
    private class GLProgram(
        val programId: Int,
        val hMVPMatrix: Int,
        val hPosition: Int,
        val hTextureCoord: Int,
        val hIsVideo: Int,
        val hSamplerImage: Int,
        val hSamplerVideo: Int,
        val hProjectionMode: Int,
        val hStereoMode: Int,
        val hLeftEye: Int,
        val hBeautyStrength: Int,
        val hBrightness: Int,
        val hContrast: Int,
        val hTexelSize: Int,
        val hIsMirrored: Int,
        val hWarpMode: Int,
        val hCurvature: Int,
        val hWhitening: Int,
        val hFaceSlimming: Int,
        val hBigEyes: Int,
        val hDarkCircles: Int,
        val hNoseSlimming: Int,
        val hMouth: Int,
        val hTeethWhitening: Int,
        val hLipstick: Int,
        val hBlush: Int,
        val hEyebrows: Int,
        val hLongLegs: Int,
        val hSmallHead: Int,
        val hFaceDetected: Int,
        val hFaceCenter: Int,
        val hEyeDistance: Int,
        val hHasDetailed: Int,
        val hEyeLeft: Int,
        val hEyeRight: Int,
        val hMouthPos: Int,
        val hChin: Int,
        val hWarpDualCenter: Int,
        val hLutTexture: Int,
        val hLutMix: Int
    )

    private var videoProgram: GLProgram? = null
    private var imageProgram: GLProgram? = null
    private var fallbackProgramId = -1
    private var hFallbackPosition = -1
    private var hFallbackTexCoord = -1
    private var hFallbackSampler = -1
    private var placeholderTextureId = -1

    // ===== 美颜对比模式（v102：GPUPixel 已移除，纯 shader 美颜）=====
    @Volatile var beautyCompareEnabled = false // 对比模式：关闭全部美颜显示原图（VRPlayerScreen 控制）

    // ===== v104 LUT 视频滤镜 =====
    @Volatile var lutMix = 0f                  // 0=关闭 ~ 1=完全应用（VRPlayerScreen 控制）
    @Volatile var lutName = ""                 // 当前 LUT 显示名（仅记录，UI 用）
    private var lutTextureId = -1              // LUT 纹理（GL_TEXTURE_2D, 512x512 RGBA）
    @Volatile private var pendingLutRgba: ByteArray? = null // 待上传（UI 线程写入，GL 线程消费）

    // Viewport and matrices
    private var displayWidth = 1080
    private var displayHeight = 1920
    private var lastScaleX = 0f
    private var lastScaleY = 0f
    private var lastSrcW = 0

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Shaders source code
    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 aPosition;
        attribute vec2 aTextureCoord;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTextureCoord = aTextureCoord;
        }
    """.trimIndent()

    // Minimal fallback shader used when the main shader fails to compile on a
    // strict device driver. Keeps photos/panoramas visible (plain textured quad).
    private val fallbackVertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTextureCoord;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = aPosition;
            vTextureCoord = aTextureCoord;
        }
    """.trimIndent()

    private val fallbackFragmentShaderCode = """
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform sampler2D uSampler;
        void main() {
            gl_FragColor = texture2D(uSampler, vTextureCoord);
        }
    """.trimIndent()

    // Latest shader compile error, surfaced to the UI so black-screen causes are visible
    @Volatile var shaderError: String? = null

    // Uniform setters that skip locations optimized out by strict device drivers
    // (Adreno frequently removes unused uniforms, leaving location = -1)
    private fun uniform1i(loc: Int, v: Int) {
        if (loc != -1) GLES20.glUniform1i(loc, v)
    }

    private fun uniform1f(loc: Int, v: Float) {
        if (loc != -1) GLES20.glUniform1f(loc, v)
    }

    private fun uniform2f(loc: Int, x: Float, y: Float) {
        if (loc != -1) GLES20.glUniform2f(loc, x, y)
    }

    private fun uniformMatrix4fv(loc: Int, count: Int, transpose: Boolean, value: FloatArray, offset: Int) {
        if (loc != -1) GLES20.glUniformMatrix4fv(loc, count, transpose, value, offset)
    }

    // Professional edge-preserving bilateral skin smoothing algorithm with customized facial cosmetics.
    // Two variants are compiled from this source:
    //   VIDEO_OES defined  -> uSamplerVideo is samplerExternalOES (video playback)
    //   VIDEO_OES undefined -> uSamplerVideo is sampler2D (images/panoramas, keeps
    //                          strict GPU drivers from touching an empty OES slot)
    private val fragmentShaderCode = """
        #ifdef VIDEO_OES
        #extension GL_OES_EGL_image_external : enable
        #endif
        precision highp float;
        
        varying vec2 vTextureCoord;
        
        uniform int uIsVideo;
        uniform sampler2D uSamplerImage;
        #ifdef VIDEO_OES
        uniform samplerExternalOES uSamplerVideo;
        #else
        uniform sampler2D uSamplerVideo;
        #endif
        
        // Settings
        uniform int uProjectionMode; // 0 = Standard, 1 = FishEye, 2 = 360 Sphere, 3 = 180 Dome
        uniform int uStereoMode;     // 0 = Mono, 1 = SBS, 2 = TAB
        uniform int uLeftEye;        // 1 = Left, 0 = Right
        uniform int uIsMirrored;
        uniform int uWarpMode;
        uniform float uCurvature;
        
        uniform float uBeautyStrength; 
        uniform float uBrightness;
        uniform float uContrast;
        uniform vec2 uTexelSize;
        
        // Detailed cosmetics uniforms
        uniform float uWhitening;
        uniform float uFaceSlimming;
        uniform float uBigEyes;
        uniform float uDarkCircles;
        uniform float uNoseSlimming;
        uniform float uMouth;
        uniform float uTeethWhitening;
        uniform float uLipstick;
        uniform float uBlush;
        uniform float uEyebrows;
        uniform float uLongLegs;
        uniform float uSmallHead;
        
        // Face recognition tracking uniforms
        uniform int uFaceDetected;
        uniform vec2 uFaceCenter;
        uniform float uEyeDistance;
        
        // Precise MediaPipe 468-point features (normalized UV space)
        uniform int uHasDetailed;
        uniform vec2 uEyeLeft;
        uniform vec2 uEyeRight;
        uniform vec2 uMouthPos;
        uniform vec2 uChin;
        uniform int uWarpDualCenter;
        
        // v104 LUT 视频滤镜（512x512 RGBA 打包：8x8 网格，每格 64x64）
        uniform sampler2D uLutTexture;
        uniform float uLutMix;   // 0 = 关闭，1 = 完全应用
        
        // 3D LUT 查找：层 z 位于 row=z/8, col=z%8，格内 x=r, y=g；
        // 像素中心偏移避免格间泄漏，GL_LINEAR 提供格内双线性 + 层间 mix
        vec3 applyLut(vec3 color) {
            const float SIZE = 64.0;
            const float GRID = 8.0;
            float scale = (SIZE - 1.0) / SIZE;
            float offset = 0.5 / SIZE;
            vec3 c = clamp(color, 0.0, 1.0) * scale + offset;
            float slice = floor(c.b * (SIZE - 1.0));  // v104 修复：用 SIZE-1 (63) 而非 GRID (8)，覆盖 64 个 slice
            float frac = c.b * (SIZE - 1.0) - slice;
            float row = floor(slice / GRID);
            float col = mod(slice, GRID);
            vec2 uv = vec2((col + c.r) / GRID, (row + c.g) / GRID);
            float slice2 = min(slice + 1.0, SIZE - 1.0);
            float row2 = floor(slice2 / GRID);
            float col2 = mod(slice2, GRID);
            vec2 uv2 = vec2((col2 + c.r) / GRID, (row2 + c.g) / GRID);
            vec3 lut0 = texture2D(uLutTexture, uv).rgb;
            vec3 lut1 = texture2D(uLutTexture, uv2).rgb;
            return mix(lut0, lut1, frac);
        }
        
        // Simple fast skin tone thresholding in RGB
        bool isSkin(vec3 rgb) {
            float r = rgb.r;
            float g = rgb.g;
            float b = rgb.b;
            return (r > 0.35 && g > 0.15 && b > 0.08 && r > g && r > b && (r - g) > 0.05);
        }
        
        void main() {
            vec2 tc = vTextureCoord;
            
            // 1. Calculate active sub-frame center and scale for 3D stereoscopic offset mappings
            vec2 center = vec2(0.5, 0.5);
            vec2 scale = vec2(1.0, 1.0);
            
            if (uStereoMode == 1) { // Side-by-Side (SBS)
                scale.x = 0.5;
                if (uLeftEye == 1) {
                    center.x = 0.25;
                } else {
                    center.x = 0.75;
                }
            } else if (uStereoMode == 2) { // Top-Bottom (TAB)
                scale.y = 0.5;
                if (uLeftEye == 1) {
                    center.y = 0.25;
                } else {
                    center.y = 0.75;
                }
            }
            
            // Map texture coordinates to active eye segment first
            if (uStereoMode == 1) { // Side-by-Side (SBS)
                if (uLeftEye == 1) {
                    tc.x = tc.x * 0.5;
                } else {
                    tc.x = 0.5 + tc.x * 0.5;
                }
            } else if (uStereoMode == 2) { // Top-Bottom (TAB)
                if (uLeftEye == 1) {
                    tc.y = tc.y * 0.5;
                } else {
                    tc.y = 0.5 + tc.y * 0.5;
                }
            }
            
            // 双中心变形：2:1 全景（VR_360 + MONO）左右半区各自以 25%/75% 为变形中心，
            // 使左右两侧的变形/透视效果对称自然（如 360° 球面观看时正前/正后不变形）。
            if (uWarpDualCenter == 1 && uStereoMode == 0) {
                center.x = (tc.x < 0.5) ? 0.25 : 0.75;
            }
            
            // 2. Lens imaging distortion: cylindrical / spherical / curved viewing-lens
            //    warps applied in the active eye segment coordinate space. This only
            //    distorts the rendered image (the "lens" you look through) and never
            //    modifies the video source itself.
            //    优化：所有变形因子用 clamp 防止高曲率下变负导致纹理翻转/崩溃，
            //    并使用平滑二次曲线使变形过渡自然。
            float curv = clamp(uCurvature, 0.0, 1.0);
            if (uWarpMode == 1) { // Original Cylinder (等距矩形柱面)
                vec2 coord = (tc - center) / (0.5 * scale);
                float k = curv * (coord.x * coord.x);
                coord.y = coord.y * clamp(1.0 + k * 1.2, 0.1, 3.0);
                coord.x = coord.x * clamp(1.0 + k * 0.5, 0.1, 2.0);
                tc = center + coord * (0.5 * scale);
            }
            else if (uWarpMode == 2) { // Cylinder (等距圆柱)
                vec2 coord = (tc - center) / (0.5 * scale);
                float k = curv * (coord.x * coord.x);
                coord.y = coord.y * clamp(1.0 + k * 2.5, 0.1, 3.0);
                coord.x = coord.x * clamp(1.0 + k * 1.0, 0.1, 2.0);
                tc = center + coord * (0.5 * scale);
            }
            else if (uWarpMode == 3) { // Sphere (立体球面)
                vec2 coord = (tc - center) / (0.5 * scale);
                float r = length(coord);
                if (r > 0.001) {
                    // 平滑枕形：边缘按 r² 平滑拉伸，clamp 防爆
                    float rf = r * clamp(1.0 + curv * r * r * 2.0, 0.1, 2.5);
                    tc = center + (coord / r) * rf * (0.5 * scale);
                }
            }
            else if (uWarpMode == 4) { // Curve (环幕曲面)
                vec2 coord = (tc - center) / (0.5 * scale);
                float k = curv * abs(coord.x);
                coord.y = coord.y * clamp(1.0 + k * 1.5, 0.1, 2.5);
                tc = center + coord * (0.5 * scale);
            }
            else if (uWarpMode == 5) { // Anti-Sphere (反向球面/桶形)
                vec2 coord = (tc - center) / (0.5 * scale);
                float r = length(coord);
                if (r > 0.001) {
                    // 桶形畸变：边缘向内收缩，clamp 保证非负（防纹理翻转）
                    float rf = r * clamp(1.0 - curv * r * r * 1.2, 0.1, 1.5);
                    tc = center + (coord / r) * rf * (0.5 * scale);
                }
            }
            else if (uWarpMode == 6) { // Anti-Curve (反向曲面)
                vec2 coord = (tc - center) / (0.5 * scale);
                float k = curv * abs(coord.x);
                coord.y = coord.y * clamp(1.0 - k * 1.0, 0.1, 1.5);
                tc = center + coord * (0.5 * scale);
            }
            
            // Mirror horizontally correctly within each eye segment center:
            // VR_360 (2) and VR_180 (3) spheres are inherently flipped on the inside.
            if (uProjectionMode == 2 || uProjectionMode == 3) {
                if (uIsMirrored == 1) {
                    // Naturally mirrored is already mirrored, so keep it as is
                } else {
                    // Normal state: un-reverse the naturally mirrored sphere mapping
                    tc.x = center.x - (tc.x - center.x);
                }
            } else {
                if (uIsMirrored == 1) {
                    tc.x = center.x - (tc.x - center.x);
                }
            }
            
            // Fish-eye local polar radial warp coordinate simulation, centered inside active video view segment
            // 优化：全域连续处理（r 最大 ~0.707），边缘平滑过渡无突变
            if (uProjectionMode == 1) {
                vec2 d = (tc - center) / scale;
                float r = length(d);
                float rMax = 0.7071;
                if (r > 0.0001) {
                    float theta = atan(d.y, d.x);
                    // 标准穹顶压缩：r 归一化后平方压缩，全域平滑
                    float rn = clamp(r / rMax, 0.0, 1.0);
                    float rf = rn * rn * rMax;
                    // 中心区域保持线性（小 r 时不变形过多），边缘平滑压缩
                    float blend = smoothstep(0.0, 1.0, rn);
                    rf = mix(r * 0.5, rf, blend);
                    tc = center + vec2(cos(theta), sin(theta)) * rf * scale;
                }
            }
            
            // Dynamic Geometry Squeezes / Deformations for beauty properties.
            // IMPORTANT: geometry deformations (thin face / big eyes / nose / mouth /
            // long legs / small head) only apply when a face is actually tracked,
            // otherwise a flat 2D picture gets squeezed/stretched for no reason.
            if (uProjectionMode == 0 && uFaceDetected == 1) {
                // Initialize landmarks
                vec2 fCenter = vec2(0.5, 0.45);
                float fUnit = 0.14;
                if (uFaceDetected == 1) {
                    fCenter = uFaceCenter;
                    fUnit = clamp(uEyeDistance, 0.05, 0.35);
                }

                // Derive facial feature anchors. When MediaPipe 468-point landmarks are
                // available, use them for pixel-accurate cosmetic placement.
                vec2 eyeL = fCenter + vec2(-0.5 * fUnit, 0.0);
                vec2 eyeR = fCenter + vec2(0.5 * fUnit, 0.0);
                vec2 mouthC = fCenter + vec2(0.0, 0.75 * fUnit);
                float chinY = fCenter.y + fUnit;
                if (uHasDetailed == 1) {
                    eyeL = uEyeLeft;
                    eyeR = uEyeRight;
                    mouthC = uMouthPos;
                    chinY = uChin.y;
                }

                float normY = (tc.y - center.y) / scale.y;
                
                // 1. Long Legs vertical stretch (bottom part of standard flat video projection)
                if (normY > 0.05) {
                    normY = normY - (normY - 0.05) * (uLongLegs * 0.08);
                    tc.y = center.y + normY * scale.y;
                }
                
                // 2. Face Slimming (centered around the chin/jawline relative to tracked face center)
                float jawY = uHasDetailed == 1 ? chinY - 0.15 * fUnit : fCenter.y + 0.5 * fUnit;
                float distYFace = abs(tc.y - jawY);
                if (distYFace < 1.1 * fUnit) {
                    float factor = (1.0 - distYFace / (1.1 * fUnit)) * uFaceSlimming * 0.08;
                    tc.x = mix(tc.x, fCenter.x, factor);
                }
                
                // Small Head (squeezing forehead region horizontally)
                float headY = uHasDetailed == 1 ? (eyeL.y + eyeR.y) * 0.5 - 0.55 * fUnit : fCenter.y - 0.7 * fUnit;
                float distYHead = abs(tc.y - headY);
                if (distYHead < 1.1 * fUnit) {
                    float factor = (1.0 - distYHead / (1.1 * fUnit)) * uSmallHead * 0.08;
                    tc.x = mix(tc.x, fCenter.x, factor);
                }
                
                // 3. Big Eyes radial expansion around tracked pupils
                float distEyeL = distance(tc, eyeL);
                if (distEyeL < 0.5 * fUnit) {
                    float f = (1.0 - distEyeL / (0.5 * fUnit)) * uBigEyes * 0.15;
                    tc = mix(tc, eyeL, -f);
                }
                float distEyeR = distance(tc, eyeR);
                if (distEyeR < 0.5 * fUnit) {
                    float f = (1.0 - distEyeR / (0.5 * fUnit)) * uBigEyes * 0.15;
                    tc = mix(tc, eyeR, -f);
                }
                
                // 4. Nose Slimming inside tracked nasal bridge
                vec2 noseCenter = uHasDetailed == 1
                    ? vec2((eyeL.x + eyeR.x) * 0.5, mix((eyeL.y + eyeR.y) * 0.5, chinY, 0.35))
                    : fCenter + vec2(0.0, 0.25 * fUnit);
                float distNoseWidth = abs(tc.x - noseCenter.x);
                float distNoseHeight = abs(tc.y - noseCenter.y);
                if (distNoseWidth < 0.4 * fUnit && distNoseHeight < 0.4 * fUnit) {
                    float f = (1.0 - distNoseWidth / (0.4 * fUnit)) * uNoseSlimming * 0.12;
                    tc.x = mix(tc.x, noseCenter.x, f);
                }
                
                // 5. Mouth resizing (squeeze slightly around mouth lip centroid)
                float distMouthArea = distance(tc, mouthC);
                if (distMouthArea < 0.45 * fUnit) {
                    float f = (1.0 - distMouthArea / (0.45 * fUnit)) * uMouth * 0.10;
                    tc = mix(tc, mouthC, f);
                }
            }
            
            // Read source frame pixel
            vec4 color;
            if (tc.x < 0.0 || tc.x > 1.0 || tc.y < 0.0 || tc.y > 1.0) {
                color = vec4(0.0, 0.0, 0.0, 1.0);
            } else {
                if (uIsVideo == 1) {
                    color = texture2D(uSamplerVideo, tc);
                } else {
                    color = texture2D(uSamplerImage, tc);
                }
            }
            
            // Realtime Skin-Smoothing Bilateral bilateral filter 3x3
            if (uBeautyStrength > 0.01) {
                vec4 sum = color;
                float totalWeight = 1.0;
                
                vec2 stepX = vec2(uTexelSize.x * 2.0, 0.0);
                vec2 stepY = vec2(0.0, uTexelSize.y * 2.0);
                
                // Read 8-connected neighbor pixels
                vec4 n1 = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc + stepX) : texture2D(uSamplerImage, tc + stepX);
                vec4 n2 = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc - stepX) : texture2D(uSamplerImage, tc - stepX);
                vec4 n3 = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc + stepY) : texture2D(uSamplerImage, tc + stepY);
                vec4 n4 = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc - stepY) : texture2D(uSamplerImage, tc - stepY);
                
                vec4 n5 = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc + stepX + stepY) : texture2D(uSamplerImage, tc + stepX + stepY);
                vec4 n6 = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc - stepX - stepY) : texture2D(uSamplerImage, tc - stepX - stepY);
                vec4 n7 = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc + stepX - stepY) : texture2D(uSamplerImage, tc + stepX - stepY);
                vec4 n8 = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc - stepX + stepY) : texture2D(uSamplerImage, tc - stepX + stepY);
                
                float deltaThres = 0.20;
                float w1 = max(0.0, 1.0 - distance(color.rgb, n1.rgb) / deltaThres);
                float w2 = max(0.0, 1.0 - distance(color.rgb, n2.rgb) / deltaThres);
                float w3 = max(0.0, 1.0 - distance(color.rgb, n3.rgb) / deltaThres);
                float w4 = max(0.0, 1.0 - distance(color.rgb, n4.rgb) / deltaThres);
                float w5 = max(0.0, 1.0 - distance(color.rgb, n5.rgb) / deltaThres);
                float w6 = max(0.0, 1.0 - distance(color.rgb, n6.rgb) / deltaThres);
                float w7 = max(0.0, 1.0 - distance(color.rgb, n7.rgb) / deltaThres);
                float w8 = max(0.0, 1.0 - distance(color.rgb, n8.rgb) / deltaThres);
                
                sum += n1 * w1 + n2 * w2 + n3 * w3 + n4 * w4 + n5 * w5 + n6 * w6 + n7 * w7 + n8 * w8;
                totalWeight += w1 + w2 + w3 + w4 + w5 + w6 + w7 + w8;
                
                vec4 blurred = sum / totalWeight;
                
                // Selective skin smoothing
                if (isSkin(color.rgb)) {
                    color.rgb = mix(color.rgb, blurred.rgb, uBeautyStrength * 0.90);
                } else {
                    color.rgb = mix(color.rgb, blurred.rgb, uBeautyStrength * 0.30); // light noise suppression
                }
            }
            
            // Apply advanced fine cosmetics
            if (uProjectionMode == 0) {
                // Initialize landmarks
                vec2 fCenter = vec2(0.5, 0.45);
                float fUnit = 0.14;
                if (uFaceDetected == 1) {
                    fCenter = uFaceCenter;
                    fUnit = clamp(uEyeDistance, 0.05, 0.35);
                }

                // Derive facial feature anchors (MediaPipe 468-point aware)
                vec2 eyeL = fCenter + vec2(-0.5 * fUnit, 0.0);
                vec2 eyeR = fCenter + vec2(0.5 * fUnit, 0.0);
                vec2 mouthC = fCenter + vec2(0.0, 0.75 * fUnit);
                if (uHasDetailed == 1) {
                    eyeL = uEyeLeft;
                    eyeR = uEyeRight;
                    mouthC = uMouthPos;
                }

                // 1. Skin Whitening (美白)
                if (isSkin(color.rgb)) {
                    color.rgb += vec3(uWhitening * 0.12);
                }
                
                // 2. Dark Circles removal (黑眼圈) just below the tracked eyes
                vec2 bagL = eyeL + vec2(0.0, 0.3 * fUnit);
                vec2 bagR = eyeR + vec2(0.0, 0.3 * fUnit);
                float distBagL = distance(tc, bagL);
                float distBagR = distance(tc, bagR);
                if ((distBagL < 0.35 * fUnit || distBagR < 0.35 * fUnit) && isSkin(color.rgb)) {
                    color.rgb += vec3(uDarkCircles * 0.13);
                }
                
                // 3. Eyebrows definitions (眉毛) darkening directly above the eyes
                vec2 browL = eyeL + vec2(0.0, -0.5 * fUnit);
                vec2 browR = eyeR + vec2(0.0, -0.5 * fUnit);
                float distBrowL = distance(tc, browL);
                float distBrowR = distance(tc, browR);
                if (distBrowL < 0.32 * fUnit) {
                    color.rgb = mix(color.rgb, color.rgb * 0.55, (1.0 - distBrowL / (0.32 * fUnit)) * uEyebrows * 0.65);
                }
                if (distBrowR < 0.32 * fUnit) {
                    color.rgb = mix(color.rgb, color.rgb * 0.55, (1.0 - distBrowR / (0.32 * fUnit)) * uEyebrows * 0.65);
                }
                
                // 4. Lipstick coloring (口红) around the tracked mouth
                float distLip = distance(tc, mouthC);
                if (distLip < 0.35 * fUnit) {
                    float f = (1.0 - distLip / (0.35 * fUnit)) * uLipstick * 0.35;
                    color.r = mix(color.r, 0.9, f);
                    color.g = mix(color.g, 0.15, f * 0.8);
                    color.b = mix(color.b, 0.3, f * 0.5);
                }
                
                // 5. Cheek Blush coloring (腮红) on the cheeks outside the eyes
                vec2 blushL = eyeL + vec2(-0.55 * fUnit, 0.55 * fUnit);
                vec2 blushR = eyeR + vec2(0.55 * fUnit, 0.55 * fUnit);
                float distBlushL = distance(tc, blushL);
                float distBlushR = distance(tc, blushR);
                if (distBlushL < 0.55 * fUnit && isSkin(color.rgb)) {
                    color.rgb += vec3(0.12, 0.04, 0.05) * (1.0 - distBlushL / (0.55 * fUnit)) * uBlush * 1.1;
                }
                if (distBlushR < 0.55 * fUnit && isSkin(color.rgb)) {
                    color.rgb += vec3(0.12, 0.04, 0.05) * (1.0 - distBlushR / (0.55 * fUnit)) * uBlush * 1.1;
                }
                
                // 6. Teeth Whitening (白牙) inside the lip cavity
                float distTeethSpace = distance(tc, mouthC);
                if (distTeethSpace < 0.22 * fUnit) {
                    float weight = uTeethWhitening * (1.0 - distTeethSpace / (0.22 * fUnit));
                    float luma = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                    if (luma > 0.45) {
                        color.rgb = mix(color.rgb, vec3(luma + 0.12), weight * 0.75);
                    }
                }
            }
            
            // Brightness
            color.rgb += uBrightness;
            
            // Contrast Adjustment
            color.rgb = (color.rgb - vec3(0.5)) * uContrast + vec3(0.5);
            
            // v104 LUT 视频滤镜（在调色之后、输出之前应用，可混合强度）
            if (uLutMix > 0.001) {
                color.rgb = mix(color.rgb, applyLut(color.rgb), uLutMix);
            }
            
            color.rgb = clamp(color.rgb, 0.0, 1.0);
            gl_FragColor = color;
        }
    """.trimIndent()

    private fun buildMainProgram(videoVariant: Boolean): GLProgram? {
        return try {
            val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            val fCode = if (videoVariant) "#define VIDEO_OES 1\n" + fragmentShaderCode else fragmentShaderCode
            val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fCode)
            val progId = GLES20.glCreateProgram().apply {
                GLES20.glAttachShader(this, vShader)
                GLES20.glAttachShader(this, fShader)
                GLES20.glLinkProgram(this)
                val linkStatus = IntArray(1)
                GLES20.glGetProgramiv(this, GLES20.GL_LINK_STATUS, linkStatus, 0)
                if (linkStatus[0] == 0) {
                    val log = GLES20.glGetProgramInfoLog(this)
                    throw RuntimeException("GL Program link error: $log")
                }
            }
            GLProgram(
                programId = progId,
                hMVPMatrix = GLES20.glGetUniformLocation(progId, "uMVPMatrix"),
                hPosition = GLES20.glGetAttribLocation(progId, "aPosition"),
                hTextureCoord = GLES20.glGetAttribLocation(progId, "aTextureCoord"),
                hIsVideo = GLES20.glGetUniformLocation(progId, "uIsVideo"),
                hSamplerImage = GLES20.glGetUniformLocation(progId, "uSamplerImage"),
                hSamplerVideo = GLES20.glGetUniformLocation(progId, "uSamplerVideo"),
                hProjectionMode = GLES20.glGetUniformLocation(progId, "uProjectionMode"),
                hStereoMode = GLES20.glGetUniformLocation(progId, "uStereoMode"),
                hLeftEye = GLES20.glGetUniformLocation(progId, "uLeftEye"),
                hBeautyStrength = GLES20.glGetUniformLocation(progId, "uBeautyStrength"),
                hBrightness = GLES20.glGetUniformLocation(progId, "uBrightness"),
                hContrast = GLES20.glGetUniformLocation(progId, "uContrast"),
                hTexelSize = GLES20.glGetUniformLocation(progId, "uTexelSize"),
                hIsMirrored = GLES20.glGetUniformLocation(progId, "uIsMirrored"),
                hWarpMode = GLES20.glGetUniformLocation(progId, "uWarpMode"),
                hCurvature = GLES20.glGetUniformLocation(progId, "uCurvature"),
                hWhitening = GLES20.glGetUniformLocation(progId, "uWhitening"),
                hFaceSlimming = GLES20.glGetUniformLocation(progId, "uFaceSlimming"),
                hBigEyes = GLES20.glGetUniformLocation(progId, "uBigEyes"),
                hDarkCircles = GLES20.glGetUniformLocation(progId, "uDarkCircles"),
                hNoseSlimming = GLES20.glGetUniformLocation(progId, "uNoseSlimming"),
                hMouth = GLES20.glGetUniformLocation(progId, "uMouth"),
                hTeethWhitening = GLES20.glGetUniformLocation(progId, "uTeethWhitening"),
                hLipstick = GLES20.glGetUniformLocation(progId, "uLipstick"),
                hBlush = GLES20.glGetUniformLocation(progId, "uBlush"),
                hEyebrows = GLES20.glGetUniformLocation(progId, "uEyebrows"),
                hLongLegs = GLES20.glGetUniformLocation(progId, "uLongLegs"),
                hSmallHead = GLES20.glGetUniformLocation(progId, "uSmallHead"),
                hFaceDetected = GLES20.glGetUniformLocation(progId, "uFaceDetected"),
                hFaceCenter = GLES20.glGetUniformLocation(progId, "uFaceCenter"),
                hEyeDistance = GLES20.glGetUniformLocation(progId, "uEyeDistance"),
                hHasDetailed = GLES20.glGetUniformLocation(progId, "uHasDetailed"),
                hEyeLeft = GLES20.glGetUniformLocation(progId, "uEyeLeft"),
                hEyeRight = GLES20.glGetUniformLocation(progId, "uEyeRight"),
                hMouthPos = GLES20.glGetUniformLocation(progId, "uMouthPos"),
                hChin = GLES20.glGetUniformLocation(progId, "uChin"),
                hWarpDualCenter = GLES20.glGetUniformLocation(progId, "uWarpDualCenter"),
                hLutTexture = GLES20.glGetUniformLocation(progId, "uLutTexture"),
                hLutMix = GLES20.glGetUniformLocation(progId, "uLutMix")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compile/link ${if (videoVariant) "video" else "image"} GLES program", e)
            shaderError = e.message ?: "shader compile failed"
            null
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.04f, 0.05f, 0.07f, 1.0f) // Dark night canvas background Hex #0a0c12
        GLES20.glDisable(GLES20.GL_DEPTH_TEST) // 2D or spherically projected mapping, depth test is unneeded

        // Build the two main programs: video variant samples an OES external texture,
        // image variant uses plain sampler2D so strict GPU drivers (Adreno etc.) never
        // hit an empty OES slot while displaying photos/panoramas.
        videoProgram = buildMainProgram(videoVariant = true)
        imageProgram = buildMainProgram(videoVariant = false)

        // Fallback program: keep images/panoramas visible even if the main shader
        // is rejected by a strict device driver.
        try {
            val vShader = compileShader(GLES20.GL_VERTEX_SHADER, fallbackVertexShaderCode)
            val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fallbackFragmentShaderCode)
            fallbackProgramId = GLES20.glCreateProgram().apply {
                GLES20.glAttachShader(this, vShader)
                GLES20.glAttachShader(this, fShader)
                GLES20.glLinkProgram(this)
            }
            hFallbackPosition = GLES20.glGetAttribLocation(fallbackProgramId, "aPosition")
            hFallbackTexCoord = GLES20.glGetAttribLocation(fallbackProgramId, "aTextureCoord")
            hFallbackSampler = GLES20.glGetUniformLocation(fallbackProgramId, "uSampler")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compile fallback shader", e)
            fallbackProgramId = -1
        }

        // Initialize textures
        videoTextureId = createOESTexture()
        imageTextureId = createStandardTexture()
        placeholderTextureId = createStandardTexture()

        // Generate sphere geometries
        val sphere360 = GeometryHelper.generateSphere(1.0f, 40, 40, false)
        sphere360Positions = sphere360.first
        sphere360TexCoords = sphere360.second
        sphere360VertexCount = sphere360Positions!!.capacity() / 3

        val sphere180 = GeometryHelper.generateSphere(1.0f, 40, 40, true)
        sphere180Positions = sphere180.first
        sphere180TexCoords = sphere180.second
        sphere180VertexCount = sphere180Positions!!.capacity() / 3

        // 盒子模式网格：六面体细分（16×16）+ 逆向射线 UV 映射
        val box = GeometryHelper.generateBox(size = 1.0f, subdivisions = 16)
        boxPositions = box.first
        boxTexCoords = box.second
        boxVertexCount = boxPositions!!.capacity() / 3

        // Setup OES SurfaceTexture for Media player playback
        if (videoTextureId != -1) {
            videoSurfaceTexture = SurfaceTexture(videoTextureId).apply {
                setOnFrameAvailableListener {
                    synchronized(this@VRGLRenderer) {
                        isVideoFrameAvailable = true
                    }
                }
            }
            // Fire callback to outside to setup Android MediaPlayer
            onVideoSurfaceCreated?.invoke(videoSurfaceTexture!!)
        }

        // Initialize/Configure Google MediaPipe Face Landmarker context on active GL thread.
        // Note: catch Throwable — MediaPipe ships no x86_64 native library, so on
        // x86_64 emulators loading it throws UnsatisfiedLinkError (an Error), which
        // must not kill the renderer thread.
        try {
            mediaPipeManager?.release()
            mediaPipeManager = MediaPipeFaceManager(context)
            Log.i(TAG, "Successfully initialized Google MediaPipe face landmarking engine.")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to instantiate Google MediaPipe beauty engine", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        displayWidth = width
        displayHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {

        // Enforce frame rate limit if maxFps > 0
        if (maxFps > 0) {
            val targetMs = 1000L / maxFps
            val now = android.os.SystemClock.uptimeMillis()
            val elapsed = now - lastFrameTimeMs
            if (elapsed in 1 until targetMs) {
                try {
                    Thread.sleep(targetMs - elapsed)
                } catch (_: Exception) {}
            }
            lastFrameTimeMs = android.os.SystemClock.uptimeMillis()
        }

        // Apply inertia decay for smooth drag/fling
        if (flingVelocityYaw != 0f || flingVelocityPitch != 0f) {
            manualYaw = (manualYaw + flingVelocityYaw) % 360f
            manualPitch = (manualPitch + flingVelocityPitch).coerceIn(-85f, 85f)
            
            val decay = 0.92f // smooth friction decay coefficient
            flingVelocityYaw *= decay
            flingVelocityPitch *= decay
            
            if (Math.abs(flingVelocityYaw) < 0.01f) flingVelocityYaw = 0f
            if (Math.abs(flingVelocityPitch) < 0.01f) flingVelocityPitch = 0f
        }

        // 1. Process any pending Bitmap from synchronization lock
        var bToLoad: Bitmap? = null
        synchronized(bitmapLock) {
            if (pendingBitmap != null) {
                bToLoad = pendingBitmap
                pendingBitmap = null
            }
        }
        bToLoad?.let {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, it, 0)
            it.recycle()
        }

        // 2. Fetch new video stream frames from SurfaceTexture on the GL thread
        synchronized(this) {
            if (isVideoFrameAvailable) {
                videoSurfaceTexture?.updateTexImage()
                isVideoFrameAvailable = false
            }
        }

        // v102：GPUPixel 原生美颜已移除，美颜效果全部由 shader 实时处理（下方 uniform 同步）

        // v104 LUT：消费待上传纹理数据（UI 线程写入，GL 线程在此上传）
        val pendingLut = pendingLutRgba
        if (pendingLut != null) {
            pendingLutRgba = null
            uploadLutTexture(pendingLut)
        }

        // Clear screen
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // v102：视频/图片统一走主程序 shader（美颜在片元着色器内实时计算）
        val prog = if (isVideoActive) videoProgram else imageProgram
        if (prog == null) {
            // Neither main program is available on this driver: use the plain 2D fallback.
            drawFallbackFrame()
            return
        }

        // Use shader
        GLES20.glUseProgram(prog.programId)

        // Bind active textures
        if (isVideoActive) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
            uniform1i(prog.hSamplerVideo, 1)
            uniform1i(prog.hIsVideo, 1)
        } else {
            // Image/panorama: unit 0 gets the photo texture; unit 1 gets the plain
            // 2D placeholder so the image program's sampler2D slot stays consistent.
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId)
            uniform1i(prog.hSamplerImage, 0)
            uniform1i(prog.hIsVideo, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, placeholderTextureId)
            uniform1i(prog.hSamplerVideo, 1)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        }

        // v104 LUT 视频滤镜：绑定 512x512 LUT 纹理到 TEXTURE2 并同步强度
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        if (lutTextureId != -1) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
            uniform1i(prog.hLutTexture, 2)
            uniform1f(prog.hLutMix, lutMix)
        } else {
            // 无 LUT 时绑定占位纹理，强度置 0（shader 分支跳过）
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, placeholderTextureId)
            uniform1i(prog.hLutTexture, 2)
            uniform1f(prog.hLutMix, 0f)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        // Load uniforms settings
        uniform1i(prog.hProjectionMode, projectionMode.id)
        uniform1i(prog.hStereoMode, stereoMode.id)
        
        // Sync beauty filter state with GL pipeline
        // 对比模式：所有美颜 uniform 归零，仅保留亮度/对比度等调色
        val bc = if (beautyCompareEnabled) 0f else 1f
        uniform1f(prog.hBeautyStrength, beautyLevel * bc)
        
        uniform1f(prog.hBrightness, brightnessLevel)
        uniform1f(prog.hContrast, contrastLevel)
        uniform1i(prog.hIsMirrored, if (isMirrored) 1 else 0)
        uniform1i(prog.hWarpMode, warpMode.id)
        uniform1f(prog.hCurvature, cylinderCurvature)

        uniform1f(prog.hWhitening, beautyWhitening * bc)
        uniform1f(prog.hFaceSlimming, beautyFaceSlimming * bc)
        uniform1f(prog.hBigEyes, beautyBigEyes * bc)
        uniform1f(prog.hDarkCircles, beautyDarkCircles * bc)
        uniform1f(prog.hNoseSlimming, beautyNoseSlimming * bc)
        uniform1f(prog.hMouth, beautyMouth * bc)
        uniform1f(prog.hTeethWhitening, beautyTeethWhitening * bc)
        uniform1f(prog.hLipstick, beautyLipstick * bc)
        uniform1f(prog.hBlush, beautyBlush * bc)
        uniform1f(prog.hEyebrows, beautyEyebrows * bc)
        uniform1f(prog.hLongLegs, beautyLongLegs * bc)
        uniform1f(prog.hSmallHead, beautySmallHead * bc)
        uniform1i(prog.hFaceDetected, if (beautyCompareEnabled) 0 else faceDetectedUniform)
        uniform2f(prog.hFaceCenter, faceCenterXUniform, faceCenterYUniform)
        uniform1f(prog.hEyeDistance, eyeDistanceUniform)

        // Precise MediaPipe 468-point landmarks (if the real model is active)
        uniform1i(prog.hHasDetailed, hasDetailedLandmarks)
        uniform2f(prog.hEyeLeft, eyeLeftXUniform, eyeLeftYUniform)
        uniform2f(prog.hEyeRight, eyeRightXUniform, eyeRightYUniform)
        uniform2f(prog.hMouthPos, mouthXUniform, mouthYUniform)
        uniform2f(prog.hChin, chinXUniform, chinYUniform)
        uniform1i(prog.hWarpDualCenter, if (warpDualCenter) 1 else 0)

        // Pass texel dimensions (size of 1 pixel) to fragment shaders
        uniform2f(prog.hTexelSize, 1.0f / displayWidth.coerceAtLeast(1), 1.0f / displayHeight.coerceAtLeast(1))

        // Check if Dual Viewport/VR Box split mode is requested
        if (isSplitScreenVR) {
            val halfWidth = displayWidth / 2

            // Left Eye Viewport
            GLES20.glViewport(0, 0, halfWidth, displayHeight)
            calculateAndApplyMatrices(isLeft = true, aspect = halfWidth.toFloat() / displayHeight.toFloat(), mvpLoc = prog.hMVPMatrix)
            uniform1i(prog.hLeftEye, 1)
            drawActiveGeometry(prog)

            // Right Eye Viewport
            GLES20.glViewport(halfWidth, 0, halfWidth, displayHeight)
            calculateAndApplyMatrices(isLeft = false, aspect = halfWidth.toFloat() / displayHeight.toFloat(), mvpLoc = prog.hMVPMatrix)
            uniform1i(prog.hLeftEye, 0)
            drawActiveGeometry(prog)
        } else {
            // Standard full width screen mode
            GLES20.glViewport(0, 0, displayWidth, displayHeight)
            calculateAndApplyMatrices(isLeft = true, aspect = displayWidth.toFloat() / displayHeight.toFloat(), mvpLoc = prog.hMVPMatrix)
            uniform1i(prog.hLeftEye, monoEyePreference) // default side center or user segment choose for dome
            drawActiveGeometry(prog)

            // Periodically extract a thumbnail of the displayed region for real-time face
            // landmark tracking. Only runs in planar (2D) modes where shader beauty effects
            // consume the landmarks; 3D/panorama modes skip it to save CPU/GPU.
            faceFrameCounter++
            val isPlanarFaceMode = projectionMode == ProjectionMode.STANDARD ||
                projectionMode == ProjectionMode.FISHEYE
            if (faceFrameCounter >= 8 && isPlanarFaceMode) {
                faceFrameCounter = 0
                val glW = displayWidth.coerceAtLeast(1)
                val glH = displayHeight.coerceAtLeast(1)
                val rw = minOf(faceReadSize, glW)
                val rh = minOf(faceReadSize, glH)
                try {
                    val bufferTemp = java.nio.ByteBuffer.allocateDirect(rw * rh * 4).order(java.nio.ByteOrder.nativeOrder())
                    GLES20.glReadPixels(
                        (glW - rw) / 2,
                        (glH - rh) / 2,
                        rw,
                        rh,
                        GLES20.GL_RGBA,
                        GLES20.GL_UNSIGNED_BYTE,
                        bufferTemp
                    )
                    bufferTemp.rewind()
                    val frame = ByteArray(rw * rh * 4)
                    bufferTemp.get(frame)
                    synchronized(faceFrameLock) {
                        pendingFaceFrame = frame
                        pendingFaceFrameW = rw
                        pendingFaceFrameH = rh
                    }
                    triggerBackgroundFaceDetection()
                } catch (e: Exception) {
                    // Ignore transient surface resizing safety errors
                }
            }
        }
    }

    private fun calculateAndApplyMatrices(isLeft: Boolean, aspect: Float, mvpLoc: Int) {
        // Flat projection (STANDARD / FISHEYE): use an orthographic projection so the
        // video/image quad fills the entire screen. The FOV slider becomes a zoom:
        // 75° = 1:1 full screen, larger FOV = zoom out, smaller = zoom in.
        val isPlanarMode = projectionMode == ProjectionMode.STANDARD ||
            projectionMode == ProjectionMode.FISHEYE
        if (isPlanarMode) {
            val zoom = (fovDeg / 75f).coerceIn(0.2f, 5f)
            Matrix.orthoM(projectionMatrix, 0, -zoom, zoom, -zoom, zoom, -5f, 5f)
        } else {
            // Perspective viewport configurations: Adjust FOV dynamically via Pinch-to-zoom
            Matrix.perspectiveM(projectionMatrix, 0, fovDeg, aspect, 0.1f, 100.0f)
        }

        // Setup standard eye look matrix looking inside the 3D dome / box
        if (projectionMode == ProjectionMode.VR_360 || projectionMode == ProjectionMode.VR_180 ||
            projectionMode == ProjectionMode.BOX
        ) {
            // Looking inside a virtual sphere / box, eye is exactly at center origin (0, 0, 0)
            Matrix.setLookAtM(viewMatrix, 0, 
                0.0f, 0.0f, 0.0f, 
                0.0f, 0.0f, -1.0f, 
                0.0f, 1.0f, 0.0f
            )
        } else {
            // standard direct camera positioning
            Matrix.setLookAtM(viewMatrix, 0, 
                0.0f, 0.0f, 2.5f, 
                0.0f, 0.0f, 0.0f, 
                0.0f, 1.0f, 0.0f
            )
        }

        // Apply physical eye pupillary deviation offset for immersive VR stereoscopic spacing
        if (isSplitScreenVR) {
            val offset = if (isLeft) -0.04f else 0.04f
            Matrix.translateM(viewMatrix, 0, offset, 0.0f, 0.0f)
        }

        // Base model transform
        Matrix.setIdentityM(modelMatrix, 0)

        // For standard 2D planar projection of video, scale to fit according to original aspect ratio.
        // Applies to both videos (16:9, 4:3, ...) and images so nothing is stretched.
        if (isPlanarMode) {
            val srcW = if (isVideoActive) videoWidth else imageWidth
            val srcH = if (isVideoActive) videoHeight else imageHeight
            if (srcW > 0 && srcH > 0) {
                val aScreen = aspect
                val aSrc = srcW.toFloat() / srcH.toFloat()
                var scaleX = 1.0f
                var scaleY = 1.0f
                if (aSrc > aScreen) {
                    // Source is wider than the display: match width, letterbox top/bottom
                    scaleY = aScreen / aSrc
                } else {
                    // Source is taller: match height, pillarbox left/right
                    scaleX = aSrc / aScreen
                }
                Matrix.scaleM(modelMatrix, 0, scaleX, scaleY, 1.0f)

                // Diagnostic: log once whenever the fit parameters change
                if (scaleX != lastScaleX || scaleY != lastScaleY || srcW != lastSrcW) {
                    Log.d(TAG, "2D fit: src=${srcW}x${srcH} aspect=$aSrc screenAspect=$aScreen scale=$scaleX,$scaleY")
                    lastScaleX = scaleX
                    lastScaleY = scaleY
                    lastSrcW = srcW
                }
            }
        }

        // 3D sensor camera rotations tracking.
        // When the gyro is enabled the sensor drives the view, and the manual
        // swipe yaw/pitch is layered on top as a persistent viewing offset, so the
        // user can still swipe to adjust the viewpoint while looking around with
        // the gyroscope (offset is applied to the world first, then the head pose).
        val isPanorama = projectionMode == ProjectionMode.VR_360 || projectionMode == ProjectionMode.VR_180 ||
            projectionMode == ProjectionMode.BOX
        val gyroActive = isPanorama && gyroEnabled
        if (gyroActive) {
            // The sensor matrix describes the device attitude in world space. The camera
            // must rotate by its inverse (the transpose of a rotation matrix), otherwise
            // yaw/pitch come out mirrored.
            val currentGyro = FloatArray(16)
            synchronized(gyroSyncLock) {
                System.arraycopy(gyroRotationMatrix, 0, currentGyro, 0, 16)
            }
            val gyroInv = FloatArray(16)
            Matrix.transposeM(gyroInv, 0, currentGyro, 0)

            // model = gyroInv * manualOffset * scale
            val manualMat = FloatArray(16)
            Matrix.setIdentityM(manualMat, 0)
            Matrix.rotateM(manualMat, 0, manualPitch, 1.0f, 0.0f, 0.0f)
            Matrix.rotateM(manualMat, 0, manualYaw, 0.0f, 1.0f, 0.0f)
            Matrix.multiplyMM(modelMatrix, 0, manualMat, 0, modelMatrix, 0)
            Matrix.multiplyMM(modelMatrix, 0, gyroInv, 0, modelMatrix, 0)
        } else if (projectionMode != ProjectionMode.STANDARD) {
            // Apply touch swipes manual rotational overrides for all projection modes except standard 2D
            Matrix.rotateM(modelMatrix, 0, manualPitch, 1.0f, 0.0f, 0.0f)
            Matrix.rotateM(modelMatrix, 0, manualYaw, 0.0f, 1.0f, 0.0f)
        }

        // Combine MVP matrix
        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)

        // Pass MVP matrix uniform to shaders
        uniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0)
    }

    /**
     * Minimal fallback rendering when the main shader failed to compile:
     * draws the current image texture as a full-screen quad (video requires
     * the OES extension and is left black on such devices).
     */
    private fun drawFallbackFrame() {
        if (fallbackProgramId == -1 || isVideoActive) return

        GLES20.glUseProgram(fallbackProgramId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId)
        GLES20.glUniform1i(hFallbackSampler, 0)

        GLES20.glEnableVertexAttribArray(hFallbackPosition)
        GLES20.glVertexAttribPointer(hFallbackPosition, 3, GLES20.GL_FLOAT, false, 3 * 4, quadPositionBuffer)
        GLES20.glEnableVertexAttribArray(hFallbackTexCoord)
        GLES20.glVertexAttribPointer(hFallbackTexCoord, 2, GLES20.GL_FLOAT, false, 2 * 4, quadTexCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, GeometryHelper.quadVertexCount)
        GLES20.glDisableVertexAttribArray(hFallbackPosition)
        GLES20.glDisableVertexAttribArray(hFallbackTexCoord)
    }

    private fun drawActiveGeometry(prog: GLProgram) {
        var posBuf: FloatBuffer = quadPositionBuffer
        var texBuf: FloatBuffer = quadTexCoordBuffer
        var totalVertices = GeometryHelper.quadVertexCount
        var drawMethod = GLES20.GL_TRIANGLE_STRIP

        // Choose mesh depending on projection
        when (projectionMode) {
            ProjectionMode.STANDARD, ProjectionMode.FISHEYE -> {
                posBuf = quadPositionBuffer
                texBuf = quadTexCoordBuffer
                totalVertices = GeometryHelper.quadVertexCount
                drawMethod = GLES20.GL_TRIANGLE_STRIP
            }
            ProjectionMode.VR_360 -> {
                sphere360Positions?.let { pos ->
                    sphere360TexCoords?.let { tex ->
                        posBuf = pos
                        texBuf = tex
                        totalVertices = sphere360VertexCount
                        drawMethod = GLES20.GL_TRIANGLES
                    }
                }
            }
            ProjectionMode.VR_180 -> {
                sphere180Positions?.let { pos ->
                    sphere180TexCoords?.let { tex ->
                        posBuf = pos
                        texBuf = tex
                        totalVertices = sphere180VertexCount
                        drawMethod = GLES20.GL_TRIANGLES
                    }
                }
            }
            ProjectionMode.BOX -> {
                boxPositions?.let { pos ->
                    boxTexCoords?.let { tex ->
                        posBuf = pos
                        texBuf = tex
                        totalVertices = boxVertexCount
                        drawMethod = GLES20.GL_TRIANGLES
                    }
                }
            }
        }

        // Pass positions
        GLES20.glEnableVertexAttribArray(prog.hPosition)
        GLES20.glVertexAttribPointer(prog.hPosition, 3, GLES20.GL_FLOAT, false, 3 * 4, posBuf)

        // Pass texture coordinates
        GLES20.glEnableVertexAttribArray(prog.hTextureCoord)
        GLES20.glVertexAttribPointer(prog.hTextureCoord, 2, GLES20.GL_FLOAT, false, 2 * 4, texBuf)

        // Draw arrays
        GLES20.glDrawArrays(drawMethod, 0, totalVertices)

        // Disable attrib arrays
        GLES20.glDisableVertexAttribArray(prog.hPosition)
        GLES20.glDisableVertexAttribArray(prog.hTextureCoord)
    }

    // Helper functions
    private fun compileShader(type: Int, code: String): Int {
        val shaderId = GLES20.glCreateShader(type)
        if (shaderId == 0) {
            throw RuntimeException("Could not create shader descriptor!")
        }
        GLES20.glShaderSource(shaderId, code)
        GLES20.glCompileShader(shaderId)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shaderId, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shaderId)
            GLES20.glDeleteShader(shaderId)
            throw RuntimeException("Shader compilation failed: $log")
        }
        return shaderId
    }

    private fun createOESTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val texId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE)
        return texId
    }

    private fun createStandardTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val texId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_S, GL10.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_T, GL10.GL_CLAMP_TO_EDGE)
        return texId
    }

    // ===== v104 LUT 视频滤镜：纹理上传与公开接口 =====

    /**
     * 设置 LUT 滤镜（任意线程调用，纹理在 GL 线程上传）。
     * @param rgba 512x512 RGBA 数据（LutUtils.parseCubeToRgba 产物）；null 表示关闭
     */
    fun setLutTexture(rgba: ByteArray?) {
        if (rgba == null) {
            pendingLutRgba = ByteArray(0) // 空数组标记清除
        } else {
            pendingLutRgba = rgba
        }
    }

    /** GL 线程：上传/更新 LUT 纹理；空数据则删除纹理（关闭滤镜） */
    private fun uploadLutTexture(rgba: ByteArray) {
        try {
            if (rgba.isEmpty()) {
                if (lutTextureId != -1) {
                    GLES20.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
                    lutTextureId = -1
                }
                return
            }
            if (lutTextureId == -1) {
                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                lutTextureId = ids[0]
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val buf = java.nio.ByteBuffer.wrap(rgba)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                LutUtils.TEX_SIZE, LutUtils.TEX_SIZE, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
            )
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            Log.i(TAG, "LUT texture uploaded (${rgba.size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "LUT texture upload failed", e)
        }
    }

    fun release() {
        videoSurfaceTexture?.setOnFrameAvailableListener(null)
        videoSurfaceTexture?.release()
        videoSurfaceTexture = null
        try {
            faceExecutor.shutdownNow()
            mediaPipeManager?.release()
            mediaPipeManager = null
            if (lutTextureId != -1) {
                GLES20.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
                lutTextureId = -1
            }
        } catch (e: Throwable) {
            // ignore
        }
    }

    private fun triggerBackgroundFaceDetection() {
        if (isDetectingFace.get()) return // Processing previous face detection

        var frame: ByteArray? = null
        var fw = 0
        var fh = 0
        synchronized(faceFrameLock) {
            frame = pendingFaceFrame
            pendingFaceFrame = null
            fw = pendingFaceFrameW
            fh = pendingFaceFrameH
        }
        val rgbaData = frame ?: return
        if (fw <= 0 || fh <= 0) return

        isDetectingFace.set(true)
        faceExecutor.execute {
            var bmp: Bitmap? = null
            var scaledBmp: Bitmap? = null
            try {
                // v84 性能优化：复用 Bitmap/数组缓冲（faceExecutor 单线程，安全）
                val area = fw * fh
                if (faceArgBuffer == null || faceArgBuffer!!.size < area) {
                    faceArgBuffer = IntArray(area)
                }
                val argb = faceArgBuffer!!
                for (i in 0 until area) {
                    val r = rgbaData[i * 4].toInt() and 0xff
                    val g = rgbaData[i * 4 + 1].toInt() and 0xff
                    val b = rgbaData[i * 4 + 2].toInt() and 0xff
                    argb[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                }
                if (faceBmp == null || faceBmp!!.width != fw || faceBmp!!.height != fh) {
                    faceBmp?.recycle()
                    faceBmp = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
                }
                bmp = faceBmp
                bmp!!.setPixels(argb, 0, fw, 0, 0, fw, fh)
                // Downscale the larger sampled region to the landmarker input size
                if (faceScaledBmp == null) {
                    faceScaledBmp = Bitmap.createBitmap(faceSampleSize, faceSampleSize, Bitmap.Config.ARGB_8888)
                }
                scaledBmp = faceScaledBmp
                val canvas = android.graphics.Canvas(scaledBmp!!)
                canvas.drawBitmap(
                    bmp!!,
                    android.graphics.Rect(0, 0, fw, fh),
                    android.graphics.Rect(0, 0, faceSampleSize, faceSampleSize),
                    null
                )

                // Track face landmarks using Google MediaPipe (with legacy fallback)
                val manager = mediaPipeManager
                if (manager != null) {
                    val result = manager.detectFace(scaledBmp)
                    if (result.detected) {
                        // Smooth tracking updates using low-pass lerp filter to eliminate jitter
                        faceDetectedUniform = 1
                        faceCenterXUniform = faceCenterXUniform * 0.75f + result.centerX * 0.25f
                        faceCenterYUniform = faceCenterYUniform * 0.75f + result.centerY * 0.25f
                        eyeDistanceUniform = eyeDistanceUniform * 0.75f + result.eyeDistance * 0.25f

                        // Sync the precise MediaPipe 468-point features when available
                        if (result.hasDetailedLandmarks) {
                            hasDetailedLandmarks = 1
                            eyeLeftXUniform = eyeLeftXUniform * 0.75f + result.eyeLeftX * 0.25f
                            eyeLeftYUniform = eyeLeftYUniform * 0.75f + result.eyeLeftY * 0.25f
                            eyeRightXUniform = eyeRightXUniform * 0.75f + result.eyeRightX * 0.25f
                            eyeRightYUniform = eyeRightYUniform * 0.75f + result.eyeRightY * 0.25f
                            mouthXUniform = mouthXUniform * 0.75f + result.mouthX * 0.25f
                            mouthYUniform = mouthYUniform * 0.75f + result.mouthY * 0.25f
                            chinXUniform = chinXUniform * 0.75f + result.chinX * 0.25f
                            chinYUniform = chinYUniform * 0.75f + result.chinY * 0.25f
                        } else {
                            // Fallback tracker: derive rough feature positions from center/eye distance
                            hasDetailedLandmarks = 0
                        }
                    } else {
                        // Decay smoothly back to default screen center coordinates
                        faceDetectedUniform = 0
                        hasDetailedLandmarks = 0
                        faceCenterXUniform = faceCenterXUniform * 0.92f + 0.50f * 0.08f
                        faceCenterYUniform = faceCenterYUniform * 0.92f + 0.45f * 0.08f
                        eyeDistanceUniform = eyeDistanceUniform * 0.92f + 0.14f * 0.08f
                    }
                }
                // v84：复用缓冲，不 recycle（仅尺寸变化时才重建）
            } catch (e: Throwable) {
                Log.e(TAG, "Background Google MediaPipe face tracking failed", e)
            } finally {
                isDetectingFace.set(false)
            }
        }
    }
}
