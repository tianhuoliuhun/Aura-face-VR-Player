package com.example.vr

import android.opengl.GLES20
import android.util.Log
import java.nio.FloatBuffer

/**
 * GLSL 半分辨率双 pass 频域分离磨皮（降采样 → 保边低频均值 → 高频细节叠回混合），
 * 从 VRGLRenderer 拆出以保持渲染主循环精简。
 */
internal class VRGLHalfPassBeauty(
    private val getDisplayWidth: () -> Int,
    private val getDisplayHeight: () -> Int,
    private val isBeautyEnabled: () -> Boolean,
    private val getBeautyLevel: () -> Float,
    private val getTextureDetail: () -> Float,
    private val getEffectiveEngine: () -> Int,
    private val isGpuPixelActiveFn: () -> Boolean,
) {
    companion object {
        private const val TAG = "VRGLHalfPassBeauty"
    }

    private val displayWidth: Int get() = getDisplayWidth()
    private val displayHeight: Int get() = getDisplayHeight()
    private val beautyMasterEnabled: Boolean get() = isBeautyEnabled()
    private val beautyLevel: Float get() = getBeautyLevel()
    private val beautyTextureDetail: Float get() = getTextureDetail()
    private val effectiveBeautyEngine: Int get() = getEffectiveEngine()
    private fun isGpuPixelActive(): Boolean = isGpuPixelActiveFn()

    // 三个 pass：
    //   down  : 主渲染结果(全分辨率纹理) → 降采样 1/2
    //   blur  : 半分辨率上做 8 邻域保边均值（频域分离的低频层）
    //   blend : 全分辨率原图 + 半分辨率低频，按皮肤判定与 uBeautyStrength 混合上屏
    //
    // 收益：磨皮 pass 的片元数降到 1/4（该 pass 开销 ↓75%）。
    private var pDownProgram = 0
    private var pBlurProgram = 0
    private var pBlendProgram = 0
    private var downTexId = 0        // 主渲染结果的**全分辨率**拷贝
    var downFboId = 0
        private set
    private var halfLowTexId = 0     // 半分辨率低频层
    private var halfLowFboId = 0
    private var halfTexId = 0        // 半分辨率原图（供 blur pass 采样）
    private var halfFboId = 0
    private var halfW = 0
    private var halfH = 0
    private var halfFboW = 0
    private var halfFboH = 0
    private var downFboW = 0
    private var downFboH = 0

    val halfQuad: java.nio.FloatBuffer = java.nio.ByteBuffer.allocateDirect(4 * 4 * 4)
        .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f
            ))
            position(0)
        }
    /** 半分辨率 pass 是否为本次 onDrawFrame 铺好了数据（决定上屏走合成还是直绘） */
    var halfPassReady = false

    fun releasePrograms() {
        releaseFbos()
        if (pDownProgram != 0) { GLES20.glDeleteProgram(pDownProgram); pDownProgram = 0 }
        if (pBlurProgram != 0) { GLES20.glDeleteProgram(pBlurProgram); pBlurProgram = 0 }
        if (pBlendProgram != 0) { GLES20.glDeleteProgram(pBlendProgram); pBlendProgram = 0 }
        halfPassReady = false
    }


    // ===== v2.0.177：磨皮半分辨率 pass 的三个 program =====

    /** 全屏 quad 的顶点着色器：pos(-1..1) + tex(0..1)，直接透传，不做任何变换 */
    private val halffsVs = "attribute vec2 aPos;attribute vec2 aTex;varying vec2 vTex;" +
        "void main(){gl_Position=vec4(aPos,0.0,1.0);vTex=aTex;}"

    /** down pass：把全分辨率纹理降采样到 1/2（GL_LINEAR 自带 2x2 平均，已足够） */
    private val halfDownFs = "precision mediump float;varying vec2 vTex;uniform sampler2D uTex;" +
        "void main(){gl_FragColor=texture2D(uTex,vTex);}"

    /**
     * blur pass：半分辨率上做 8 邻域**保边**均值（频域分离的低频层）。
     *
     * 与主 shader 内联版算法的唯一区别是采样半径：主 shader 用 ±6 个**全分辨率**像素，
     * 这里纹理已是半分辨率，故半径取 ±6 个半分辨率像素 —— 视觉上等效于全分辨率的 ±12，
     * 只让低频更平滑一点（磨皮本就只要低频），并不会改变观感量级。
     * `uTexelSize` 由 Kotlin 侧按**半分辨率纹理**传入（绝不能复用全分辨率那份，
     * 否则半径会翻倍成 ±12 半分辨率像素 ≈ 全分辨率 ±24，磨皮会糊成一片）。
     */
    private val halfBlurFs = """
        precision mediump float;
        varying vec2 vTex;
        uniform sampler2D uTex;
        uniform vec2 uTexelSize;
        void main() {
            vec4 color = texture2D(uTex, vTex);
            // v2.4.36 审查 #11：半分辨率 pass 的磨皮半径同样按源宽度相对 1080p 缩放，
            //   与上面主 shader 的语义对齐（uTexelSize 此处是半分辨率纹理的纹素尺寸，
            //   反推出的宽度是半分辨率宽度，缩放后落在源像素上仍与全分辨率路径一致）。
            float smoothRadiusScale = (1.0 / uTexelSize.x) / 1080.0;
            vec2 stepF = uTexelSize * 6.0 * smoothRadiusScale;
            vec4 n1 = texture2D(uTex, vTex + stepF);
            vec4 n2 = texture2D(uTex, vTex - stepF);
            vec4 n3 = texture2D(uTex, vTex + vec2(stepF.x, 0.0));
            vec4 n4 = texture2D(uTex, vTex - vec2(stepF.x, 0.0));
            vec4 n5 = texture2D(uTex, vTex + vec2(0.0, stepF.y));
            vec4 n6 = texture2D(uTex, vTex - vec2(0.0, stepF.y));
            vec4 n7 = texture2D(uTex, vTex + vec2(stepF.x, -stepF.y));
            vec4 n8 = texture2D(uTex, vTex - vec2(stepF.x, -stepF.y));
            float deltaThres = 0.30;
            float w1 = max(0.0, 1.0 - distance(color.rgb, n1.rgb) / deltaThres);
            float w2 = max(0.0, 1.0 - distance(color.rgb, n2.rgb) / deltaThres);
            float w3 = max(0.0, 1.0 - distance(color.rgb, n3.rgb) / deltaThres);
            float w4 = max(0.0, 1.0 - distance(color.rgb, n4.rgb) / deltaThres);
            float w5 = max(0.0, 1.0 - distance(color.rgb, n5.rgb) / deltaThres);
            float w6 = max(0.0, 1.0 - distance(color.rgb, n6.rgb) / deltaThres);
            float w7 = max(0.0, 1.0 - distance(color.rgb, n7.rgb) / deltaThres);
            float w8 = max(0.0, 1.0 - distance(color.rgb, n8.rgb) / deltaThres);
            gl_FragColor = (color + n1 * w1 + n2 * w2 + n3 * w3 + n4 * w4
                + n5 * w5 + n6 * w6 + n7 * w7 + n8 * w8)
                / (1.0 + w1 + w2 + w3 + w4 + w5 + w6 + w7 + w8);
        }
    """.trimIndent()

    /**
     * blend pass：全分辨率原图 + 半分辨率低频 → 上屏。
     *
     * 频域分离在这里完成：
     *   低频 = texture2D(uLowTex, vTex)        （半分辨率，双线性上采样）
     *   高频 = 原图 - 低频                      （全分辨率，含毛孔/发丝/五官边缘）
     *   结果 = 低频 + 高频 × uTextureDetail
     * 再按 isSkin 与 uBeautyStrength 决定混合强度 —— 与主 shader 内联版语义完全一致，
     * 只是**皮肤判定也从主 shader 搬到了这里**（主 shader 不再做磨皮）。
     */
    private val halfBlendFs = """
        precision highp float;
        varying vec2 vTex;
        uniform sampler2D uFullTex;   // 全分辨率原图（主渲染结果）
        uniform sampler2D uLowTex;    // 半分辨率低频层
        uniform float uBeautyStrength;
        uniform float uTextureDetail;
        bool isSkin(vec3 rgb) {
            float r = rgb.r;
            float g = rgb.g;
            float b = rgb.b;
            return (r > 0.35 && g > 0.15 && b > 0.08 && r > g && r > b && (r - g) > 0.05);
        }
        void main() {
            vec4 full = texture2D(uFullTex, vTex);
            if (uBeautyStrength <= 0.01) {
                gl_FragColor = full;
                return;
            }
            vec3 low = texture2D(uLowTex, vTex).rgb;
            vec3 detail = full.rgb - low;
            vec3 smoothed = low + detail * uTextureDetail;
            if (isSkin(full.rgb)) {
                gl_FragColor = vec4(mix(full.rgb, smoothed, uBeautyStrength), full.a);
            } else {
                // v2.0.178 修复：与主 shader 一致 —— 非皮肤像素不再「完全跳过」。
                // 实测真实视频 isSkin 总通过率仅 35%（粉/紫背景 0%），若背景完全不动，
                // 皮肤与背景之间会出现可见的一圈分界，观感即「美颜只在一小块生效」。
                // 这里用半分辨率低频层也做一次 0.25 权重的轻度混合（零额外采样）。
                gl_FragColor = vec4(mix(full.rgb, smoothed, uBeautyStrength * 0.25), full.a);
            }
        }
    """.trimIndent()

    /** 编译并链接一个「aPos + aTex」全屏 pass program；失败返回 0 */
    fun buildQuadProgram(fs: String): Int {
        return try {
            val vsId = VRGLShaders.compileShader(GLES20.GL_VERTEX_SHADER, halffsVs)
            val fsId = VRGLShaders.compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vsId)
            GLES20.glAttachShader(prog, fsId)
            GLES20.glLinkProgram(prog)
            val st = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, st, 0)
            if (st[0] == 0) {
                Log.e(TAG, "半分辨率磨皮 pass 链接失败: ${GLES20.glGetProgramInfoLog(prog)}")
                0
            } else {
                prog
            }
        } catch (e: Throwable) {
            Log.e(TAG, "半分辨率磨皮 pass 编译失败", e)
            0
        }
    }

    fun ensurePrograms() {
        if (pDownProgram == 0) pDownProgram = buildQuadProgram(halfDownFs)
        if (pBlurProgram == 0) pBlurProgram = buildQuadProgram(halfBlurFs)
        if (pBlendProgram == 0) pBlendProgram = buildQuadProgram(halfBlendFs)
    }

    fun ensureFbos(w: Int, h: Int) {
        if (downTexId != 0 && downFboW == w && downFboH == h) return
        releaseFbos()
        downTexId = VRGLShaders.createFboTexture(w, h)
        downFboId = VRGLShaders.createFboFor(downTexId)
        halfW = (w / 2).coerceAtLeast(1)
        halfH = (h / 2).coerceAtLeast(1)
        halfTexId = VRGLShaders.createFboTexture(halfW, halfH)
        halfFboId = VRGLShaders.createFboFor(halfTexId)
        halfLowTexId = VRGLShaders.createFboTexture(halfW, halfH)
        halfLowFboId = VRGLShaders.createFboFor(halfLowTexId)
        downFboW = w
        downFboH = h
        halfFboW = halfW
        halfFboH = halfH
    }

    fun releaseFbos() {
        if (downTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(downTexId), 0)
        if (downFboId != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(downFboId), 0)
        if (halfTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(halfTexId), 0)
        if (halfFboId != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(halfFboId), 0)
        if (halfLowTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(halfLowTexId), 0)
        if (halfLowFboId != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(halfLowFboId), 0)
        downTexId = 0; downFboId = 0
        halfTexId = 0; halfFboId = 0
        halfLowTexId = 0; halfLowFboId = 0
        downFboW = 0; downFboH = 0; halfFboW = 0; halfFboH = 0
    }

    /**
     * 是否启用「磨皮半分辨率 pass」。
     *
     * 仅在**内置分屏/全屏通路**下启用（GPUPixel 通路走自己的渲染目标，
     * 混进来会把 FBO 绑定搞乱）。且必须磨皮真的在跑（GLSL 引擎 + 总开关 + 强度 > 0）。
     */
    fun isActive(): Boolean =
            !isGpuPixelActive() &&
            beautyMasterEnabled &&
            // v2.4.33（#1）：用 effectiveBeautyEngine —— GPUPixel 不可用时按 GLSL 跑半分辨率磨皮，
            // 而不是「三个判断全为假 → 美颜无输出」。
            effectiveBeautyEngine == BEAUTY_ENGINE_GLSL &&
            beautyLevel > 0.01f &&
            displayWidth > 0 && displayHeight > 0

    /**
     * 绘制一个全屏 quad 到当前绑定的 framebuffer（供三个 pass 复用）。
     * [texUnit0] / [texUnit1] 为 -1 表示不绑定该单元。
     */
    private fun drawHalfQuad(prog: Int, texUnit0: Int, texUnit1: Int, texelW: Float, texelH: Float) {
        val posLoc = GLES20.glGetAttribLocation(prog, "aPos")
        val texLoc = GLES20.glGetAttribLocation(prog, "aTex")
        GLES20.glUseProgram(prog)
        if (texUnit0 != -1) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texUnit0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTex"), 0)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uFullTex"), 0)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uTexelSize"), texelW, texelH)
        }
        if (texUnit1 != -1) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texUnit1)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uLowTex"), 1)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        }
        halfQuad.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, halfQuad)
        GLES20.glEnableVertexAttribArray(posLoc)
        halfQuad.position(2)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 16, halfQuad)
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
    }

    /**
     * 跑三个 pass，把「低频层」准备好。
     *
     * ⚠️ 副作用：结束时**不恢复 framebuffer 绑定**（停在 0 = 屏幕）。
     *    调用方需自行绑定到 [downFboId] 做正式绘制。
     */
    fun runSmoothingPasses() {
        ensurePrograms()
        if (pDownProgram == 0 || pBlurProgram == 0 || pBlendProgram == 0) {
            halfPassReady = false
            return
        }
        ensureFbos(displayWidth, displayHeight)

        // pass 1：全分辨率 → 半分辨率
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, halfFboId)
        GLES20.glViewport(0, 0, halfW, halfH)
        GLES20.glDisable(GLES20.GL_BLEND)
        drawHalfQuad(pDownProgram, downTexId, -1, 0f, 0f)

        // pass 2：半分辨率 8 邻域保边均值 → 半分辨率低频层
        // ⚠️ texel 尺寸必须按**半分辨率纹理**算（1/halfW），不能用 1/displayWidth
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, halfLowFboId)
        GLES20.glViewport(0, 0, halfW, halfH)
        drawHalfQuad(pBlurProgram, halfTexId, -1, 1.0f / halfW, 1.0f / halfH)

        halfPassReady = true
        // 交给调用方绑定 downFbo 做正式绘制（不在此恢复 framebuffer，避免多一次切换）
    }

    /**
     * 磨皮半分辨率 pass 的合成上屏：全分辨率原图 + 半分辨率低频 → 屏幕。
     *
     * 全程 `glDisable(GL_BLEND)`：主渲染用的是直写（不混合），而 FBO 预乘/混合语义易踩坑。
     */
    fun blendToScreen() {
        if (!halfPassReady || pBlendProgram == 0 || downTexId == 0 || halfLowTexId == 0) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, displayWidth, displayHeight)
        GLES20.glDisable(GLES20.GL_BLEND)
        val prog = pBlendProgram
        GLES20.glUseProgram(prog)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uBeautyStrength"), beautyLevel)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uTextureDetail"), beautyTextureDetail)
        drawHalfQuad(prog, downTexId, halfLowTexId, 0f, 0f)
    }

    // ========================================================================
    // v2.0.206：画质增强（MEMC 插帧 + FSR 超分）
    // ========================================================================
    //
    // 管线顺序：源帧 → MEMC(ME→MC) → FSR(EASU→RCAS) → 主 shader
    // 「为什么 MEMC 必须在前」的三条理由见 VideoEnhanceShaders 的头部注释。
    //
    // 【主 shader 如何拿到增强结果 —— 零 shader 改动】
    // 主 shader 里 uIsVideo 只有「选哪张纹理采样」这一处用途：
    //     if (uIsVideo == 1) color = texture2D(uSamplerVideo, tc); else ...
    // 而 imageProgram（VIDEO_OES 未定义的那份变体）里 uSamplerVideo 被声明为普通
    // sampler2D。于是只要：
    //     ① program 选 imageProgram，
    //     ② 把增强纹理绑到 samplerVideo 的槽位（TEXTURE1），
    //     ③ uIsVideo 置 1，
    // 主 shader 就直接采样增强结果 —— 不必给 shader 加任何分支或宏。
    // 这是本方案相对「改 15 处采样点」最大的风险削减。

}
