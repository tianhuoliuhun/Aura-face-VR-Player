package com.example.vr

import android.opengl.GLES20
import android.util.Log

internal class VRMainGLProgram(
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
    val hTextureDetail: Int,
    val hBrightness: Int,
    val hContrast: Int,
    val hTexelSize: Int,
    val hIsMirrored: Int,
    val hWarpMode: Int,
    val hCurvature: Int,
    /** v2.1.211：鱼眼视场角句柄 */
    val hFisheyeFov: Int,
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
    // v2.6.22（多脸）：全部改成数组 uniform 的位置（数组首元素的 location 即可，
    // glUniform*v 会连续写入 N 个元素）。
    val hFaceCount: Int,
    val hFaceCenters: Int,
    val hEyeDistances: Int,
    val hHasDetaileds: Int,
    val hEyeLefts: Int,
    val hEyeRights: Int,
    val hMouthPoss: Int,
    val hMouthHalfWs: Int,
    val hMouthHalfHs: Int,
    val hMouthAngles: Int,
    val hChins: Int,
    val hWarpDualCenter: Int,
    val hLutTexture: Int,
    val hLutMix: Int
)

internal object VRGLShaders {
    private const val TAG = "VRGLShaders"

    val vertexShaderCode = """
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
    val fallbackVertexShaderCode = """
        attribute vec4 aPosition;
        attribute vec2 aTextureCoord;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = aPosition;
            vTextureCoord = aTextureCoord;
        }
    """.trimIndent()

    val fallbackFragmentShaderCode = """
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform sampler2D uSampler;
        void main() {
            gl_FragColor = texture2D(uSampler, vTextureCoord);
        }
    """.trimIndent()

    val fragmentShaderCode = """
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
        // v2.1.211：鱼眼视场角（度）。仅 uProjectionMode==1 时被读取
        uniform float uFisheyeFov;
        
        uniform float uBeautyStrength; 
        uniform float uBrightness;
        uniform float uContrast;
        uniform vec2 uTexelSize;
        
        // Detailed cosmetics uniforms
        // v2.0.159：磨皮「高频保留度」—— 频域分离后的纹理叠回系数（<1 更平滑，>1 相当于 USM 锐化）
        uniform float uTextureDetail;
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
        uniform int uFaceCount;
        // v2.6.22（多脸）：每张脸一组锚点，最多 3 张（与 MediaPipe setNumFaces(3) 一致）
        uniform vec2 uFaceCenters[3];
        uniform float uEyeDistances[3];
        
        // Precise MediaPipe 468-point features (normalized UV space)
        uniform int uHasDetaileds[3];
        uniform vec2 uEyeLefts[3];
        uniform vec2 uEyeRights[3];
        uniform vec2 uMouthPoss[3];
        // v2.0.159：嘴部形状参数（由 MediaPipe 唇部关键点算出）—— 供椭圆 mask 使用，
        // 取代原先「以嘴中心为圆心的正圆」。值为 0 表示未取到（shader 内回退到 fUnit 比例）。
        uniform float uMouthHalfWs[3];
        uniform float uMouthHalfHs[3];
        uniform float uMouthAngles[3];
        uniform vec2 uChins[3];
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

        // v2.0.159：soft-light 混合 —— 用于腮红 / 口红等妆容。
        // 相比原来的「纯加法」，它能保留底层皮肤的明暗层次，不会把脸颊糊成一片纯色，
        // 也不会把高光区顶到溢出。
        vec3 softLight(vec3 base, vec3 blend) {
            vec3 lo = 2.0 * base * blend + base * base * (1.0 - 2.0 * blend);
            vec3 hi = sqrt(max(base, vec3(0.0))) * (2.0 * blend - 1.0) + 2.0 * base * (1.0 - blend);
            return clamp(mix(lo, hi, step(vec3(0.5), blend)), 0.0, 1.0);
        }

        // v2.0.159：带倾角的椭圆软遮罩，返回 0~1 权重（1 = 中心，0 = 边界外）。
        // 取代原来「以关键点为圆心的正圆」判定 —— 嘴唇是横向长条、腮红是椭圆、眉是斜长条，
        // 正圆必然「圆小涂不到嘴角、圆大溢出到下巴」。softness 控制边界羽化宽度（0.2~0.4 效果自然）。
        // 参数名刻意避开 center / scale —— main() 里有同名局部变量，免得遮蔽后看代码犯迷糊
        float ellipseMask(vec2 p, vec2 ctr, vec2 rad, float angle, float softness) {
            float ca = cos(angle);
            float sa = sin(angle);
            vec2 d = p - ctr;
            vec2 q = vec2(ca * d.x - sa * d.y, sa * d.x + ca * d.y);
            float rr = length(q / max(rad, vec2(1e-4)));
            return 1.0 - smoothstep(1.0 - softness, 1.0, rr);
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
            // v2.1.210：5 = EAC（同样是球体内侧，翻转方向与 360/180 一致，一并纳入）
            if (uProjectionMode == 2 || uProjectionMode == 3 || uProjectionMode == 5 ||
                uProjectionMode == 6) {
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
                    // v2.1.211：**FOV 可调** —— 以 180° 为基准（压缩指数 2.0），
                    // FOV 越大则边缘压缩越强（视场越广、边缘越"卷")。
                    // ⚠️ 注意本模式是「把平面画面压成穹顶观感」的**观感滤镜**，
                    //    不是还原真实鱼眼片源 —— 所以这里调的是压缩强度，
                    //    而非教科书里的「屏幕半径 → 球面角」映射。
                    //    用错方向（把指数改小）会得到「边缘被拉平、画面鼓出」的反效果。
                    float fovK = clamp(uFisheyeFov, 160.0, 240.0) / 180.0;
                    float rf = pow(rn, 2.0 * fovK) * rMax;
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
                // ===== v2.6.22（多脸）：长腿是**全屏**形变，必须先于逐脸循环 =====
                // 它只依赖 tc.y，与具体哪张脸无关。放进 per-face 循环会被每张脸各拉伸一次，
                // 3 张脸叠加后人物会被拉成面条。
                float normY = (tc.y - center.y) / scale.y;
                
                // 1. Long Legs vertical stretch (bottom part of standard flat video projection)
                if (normY > 0.05) {
                    normY = normY - (normY - 0.05) * (uLongLegs * 0.08);
                    tc.y = center.y + normY * scale.y;
                }

                // ===== v2.6.22（多脸）：逐脸形变，最多 3 张 =====
                // 每张脸先做包围盒剔除：形变影响半径 ≤ 1.1 * fUnit，盒外像素直接跳过。
                for (int fi = 0; fi < 3; fi++) {
                    if (fi >= uFaceCount) break;
                    vec2 fCenter = uFaceCenters[fi];
                    float fUnit = clamp(uEyeDistances[fi], 0.02, 0.25);
                    // 包围盒剔除（形变最大影响半径 1.1 * fUnit，宽高各留余量）
                    if (abs(tc.x - fCenter.x) > 1.4 * fUnit || abs(tc.y - fCenter.y) > 1.5 * fUnit) continue;

                    // Derive facial feature anchors. When MediaPipe 468-point landmarks are
                    // available, use them for pixel-accurate cosmetic placement.
                    vec2 eyeL = fCenter + vec2(-0.5 * fUnit, 0.0);
                    vec2 eyeR = fCenter + vec2(0.5 * fUnit, 0.0);
                    vec2 mouthC = fCenter + vec2(0.0, 0.75 * fUnit);
                    float chinY = fCenter.y + fUnit;
                    if (uHasDetaileds[fi] == 1) {
                        eyeL = uEyeLefts[fi];
                        eyeR = uEyeRights[fi];
                        mouthC = uMouthPoss[fi];
                        chinY = uChins[fi].y;
                    }


                    // 2. Face Slimming (centered around the chin/jawline relative to tracked face center)
                    float jawY = uHasDetaileds[fi] == 1 ? chinY - 0.15 * fUnit : fCenter.y + 0.5 * fUnit;
                    float distYFace = abs(tc.y - jawY);
                    if (distYFace < 1.1 * fUnit) {
                        float factor = (1.0 - distYFace / (1.1 * fUnit)) * uFaceSlimming * 0.08;
                        tc.x = mix(tc.x, fCenter.x, factor);
                    }

                    // Small Head (squeezing forehead region horizontally)
                    float headY = uHasDetaileds[fi] == 1 ? (eyeL.y + eyeR.y) * 0.5 - 0.55 * fUnit : fCenter.y - 0.7 * fUnit;
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
                    vec2 noseCenter = uHasDetaileds[fi] == 1
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
            // ===== v2.0.177：皮肤判定前置，非皮肤像素**跳过 8 次邻域采样** =====
            // 旧实现：先把 8 个远邻域全采样完，最后才按 isSkin 决定混合强度
            // （皮肤 1.0 / 非皮肤 0.25）—— 而非皮肤区（背景、头发、衣物）占画面 60~80%，
            // 这些像素白付 8 次纹理采样，最后只换来一个乘 0.25 的轻微降噪（视频场景肉眼不可见）。
            // 移动 GPU 上纹理采样是 fragment 的主要瓶颈：1080p60 下 8 次/像素 ≈ 每秒 10 亿次采样，
            // 其中约 6.5 亿次是纯浪费。前置判定后，非皮肤区采样数 9 → 1。
            //
            // ⚠️ 这是一个「失败即退化」的优化：isSkin 是 RGB 阈值快速判定（见其定义处），
            //    对暖光墙 / 木地板 / 肤色家具会**误判成皮肤** → 只是退化回全采样路径，
            //    只损失收益、绝不会画错。这正是该优化风险极低的根本原因。
            // 注：GPU 以 2x2 quad 为单位执行分支，quad 内皮肤/非皮肤混合时两条分支都会跑
            //    （无收益但无害）；因 isSkin 在局部区域高度一致，quad 分歧概率低，收益基本保留。
            if (uBeautyStrength > 0.01) {
                // ===== v2.0.159：磨皮改为「频域分离」 =====
                // 把画面拆成两层：
                //   低频 = 大半径**保边**均值 → 色块与光影（痘印 / 色斑就住在这层）
                //   高频 = 原图 − 低频        → 毛孔、发丝、五官边缘
                // 只平滑低频、再按 uTextureDetail 把高频叠回去 —— 于是「磨皮强度」与「纹理保留度」
                // 解耦：既能抹平色块，又不会变成塑料脸。
                // （旧实现是单尺度双边 + 0.90 强混合：半径不足只能靠混合硬拉，观感是"糊"不是"净"。）
                if (isSkin(color.rgb)) {   // ← v2.0.177：纯标量比较，零采样成本
                // v2.4.36 审查 #11：磨皮半径固定 6 纹素 → 随分辨率变化覆盖范围。
                //   改成按画面宽度相对 1080p 等比缩放（用 1/uTexelSize.x 反推宽度，免新增 uniform），
                //   使 4K 与 1080p 的皮肤覆盖一致。
                float smoothRadiusScale = (1.0 / uTexelSize.x) / 1080.0;
                vec2 stepF = uTexelSize * 6.0 * smoothRadiusScale;

                // 8 个远邻域（±6 像素，已按分辨率缩放）
                vec4 n1 = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc + stepF) : texture2D(uSamplerImage, tc + stepF);
                vec4 n2 = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc - stepF) : texture2D(uSamplerImage, tc - stepF);
                vec4 n3 = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc + vec2(stepF.x, 0.0)) : texture2D(uSamplerImage, tc + vec2(stepF.x, 0.0));
                vec4 n4 = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc - vec2(stepF.x, 0.0)) : texture2D(uSamplerImage, tc - vec2(stepF.x, 0.0));
                vec4 n5 = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc + vec2(0.0, stepF.y)) : texture2D(uSamplerImage, tc + vec2(0.0, stepF.y));
                vec4 n6 = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc - vec2(0.0, stepF.y)) : texture2D(uSamplerImage, tc - vec2(0.0, stepF.y));
                vec4 n7 = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc + vec2(stepF.x, -stepF.y)) : texture2D(uSamplerImage, tc + vec2(stepF.x, -stepF.y));
                vec4 n8 = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc - vec2(stepF.x, -stepF.y)) : texture2D(uSamplerImage, tc - vec2(stepF.x, -stepF.y));

                // 保边权重：邻域色差越大权重越低。大半径下阈值放宽到 0.30 ——
                // 否则远邻域会被整片判成"边缘"，权重全 0，低频层退化成原图、磨皮等于没做。
                float deltaThres = 0.30;
                float w1 = max(0.0, 1.0 - distance(color.rgb, n1.rgb) / deltaThres);
                float w2 = max(0.0, 1.0 - distance(color.rgb, n2.rgb) / deltaThres);
                float w3 = max(0.0, 1.0 - distance(color.rgb, n3.rgb) / deltaThres);
                float w4 = max(0.0, 1.0 - distance(color.rgb, n4.rgb) / deltaThres);
                float w5 = max(0.0, 1.0 - distance(color.rgb, n5.rgb) / deltaThres);
                float w6 = max(0.0, 1.0 - distance(color.rgb, n6.rgb) / deltaThres);
                float w7 = max(0.0, 1.0 - distance(color.rgb, n7.rgb) / deltaThres);
                float w8 = max(0.0, 1.0 - distance(color.rgb, n8.rgb) / deltaThres);

                vec4 low = (color + n1 * w1 + n2 * w2 + n3 * w3 + n4 * w4
                    + n5 * w5 + n6 * w6 + n7 * w7 + n8 * w8)
                    / (1.0 + w1 + w2 + w3 + w4 + w5 + w6 + w7 + w8);

                // 高频层：纹理 + 边缘
                vec3 detail = color.rgb - low.rgb;

                // 低频 + 高频×保留度：uTextureDetail < 1 更平滑，> 1 相当于 USM 锐化（找回通透感）
                vec3 smoothed = low.rgb + detail * uTextureDetail;

                color.rgb = mix(color.rgb, smoothed, uBeautyStrength);
                } else {
                    // ===== v2.0.178 修复「美颜只显示一小块区域」 =====
                    // v2.0.177 让非皮肤像素**完全不做**磨皮（省 8 次采样）。但 isSkin 是
                    // RGB 阈值判定，实测一段真实视频（1920x1080，人物占中下 3/4）：
                    //   上 1/4 的粉/紫背景 → 通过率 0%，中下人物区 → 20~93%，总通过率仅 35%。
                    // 于是皮肤区被磨皮、背景完全没动，**看上去就是「美颜只在小块区域生效」**，
                    // 且皮肤/背景交界处出现可见的一圈分界。
                    //
                    // 这里恢复「非皮肤也轻度处理」，但**只采 2 个垂直邻域**（不是旧的 8 个）：
                    //   · 采样数 9 → 3（仅比 v2.0.177 多 2 次，远低于旧的 9 次）
                    //   · 背景获得极轻降噪 → 分界线消失，观感统一
                    // 权重固定 0.25（与 v2.0.176 的非皮肤分支一致），保持「背景不被过度处理」。
                    // v2.4.34（#12）：非皮肤分支改为对称 2D 采样（上下左右 4 邻域），
                    // 消除对水平边缘（窗框/地平线/字幕）造成的纵向拖影。
                    vec2 stepV = vec2(0.0, uTexelSize.y * 6.0);
                    vec2 stepH = vec2(uTexelSize.x * 6.0, 0.0);
                    vec3 vUp = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc + stepV).rgb
                                               : texture2D(uSamplerImage, tc + stepV).rgb;
                    vec3 vDn = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc - stepV).rgb
                                               : texture2D(uSamplerImage, tc - stepV).rgb;
                    vec3 vL  = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc - stepH).rgb
                                               : texture2D(uSamplerImage, tc - stepH).rgb;
                    vec3 vR  = (uIsVideo == 1) ? texture2D(uSamplerVideo, tc + stepH).rgb
                                               : texture2D(uSamplerImage, tc + stepH).rgb;
                    vec3 vAvg = (color.rgb + vUp + vDn + vL + vR) / 5.0;
                    color.rgb = mix(color.rgb, vAvg, uBeautyStrength * 0.25);
                }
            }
            
            // ===== v2.0.190 修复：GLSL 美白改为**全屏生效** =====
            // 此前美白被放在下方 `if (uProjectionMode == 0 && uFaceDetected == 1)` 之内，导致：
            //   ① 未检出人脸时**完全不生效**（用户反馈的「GLSL 美白不生效」正是此现象）；
            //   ② VR / 全景模式（uProjectionMode != 0）下**根本不执行**。
            // 美白只依赖肤色判定 isSkin()，**既不需要人脸位置，也不该受投影模式限制** ——
            // 现在与 GPUPixel 引擎（磨皮/美白全屏生效、不依赖检测）的行为对齐。
            // 开销可忽略：isSkin 是纯比较运算（无纹理采样），全屏皮肤像素只多做一次加法。
            // 注：黑眼圈/口红/腮红/白牙等**必须**有人脸位置的妆容仍留在下方块内（逻辑不变）。
            if (isSkin(color.rgb)) {
                color.rgb += vec3(uWhitening * 0.14) * (1.0 - color.rgb);
            }

            // Apply advanced fine cosmetics
            // v2.0.173：妆容/局部效果 gate 到 uFaceDetected == 1 —— 检测丢失时完全不画
            // （否则会按默认中心 (0.5,0.45) 把口红/腮红画到画面中央，产生跳变）。
            // 与 Kotlin 侧的「连续 3 次失败才置 0」防抖配合，短暂检测失败不再闪。
            if (uProjectionMode == 0 && uFaceDetected == 1) {
                // ===== v2.6.22（多脸）：逐脸妆容，最多 3 张 =====
                // 每张脸各自做包围盒剔除（妆容最远半径 1.5/1.8 * fUnit），
                // 因此画面上大部分像素只会为「离自己最近的那张脸」付出算术开销。
                for (int fi = 0; fi < 3; fi++) {
                    if (fi >= uFaceCount) break;
                    vec2 fCenter = uFaceCenters[fi];
                    float fUnit = clamp(uEyeDistances[fi], 0.02, 0.25);

                    // ===== v2.0.177：妆容 gate 到人脸包围盒 =====
                    // 下方整段妆容（美白/黑眼圈/眉毛/口红/腮红/白牙）要跑 10+ 次 ellipseMask，
                    // 每次含 cos/sin/length/smoothstep —— 这些是**纯算术**开销（无纹理采样）。
                    // 但人脸通常只占画面一小部分，画面上 80%+ 的像素算完 mask 发现权重为 0 就丢弃。
                    // 这里先做一次包围盒剔除：最远的腮红中心距 fCenter 约 0.72*fUnit、半径约 0.46*fUnit，
                    // 再加上椭圆边界羽化，1.5*fUnit / 1.8*fUnit 的包围盒足以包住全部妆容区域且有余量。
                    // ⚠️ 注意：这只是算术优化的「快路径」，盒外像素直接跳过整段妆容计算。
                    bool inFaceBox =
                        abs(tc.x - fCenter.x) < 1.5 * fUnit &&
                        abs(tc.y - fCenter.y) < 1.8 * fUnit;

                    if (inFaceBox) {
                    // Derive facial feature anchors (MediaPipe 468-point aware)
                    vec2 eyeL = fCenter + vec2(-0.5 * fUnit, 0.0);
                    vec2 eyeR = fCenter + vec2(0.5 * fUnit, 0.0);
                    vec2 mouthC = fCenter + vec2(0.0, 0.75 * fUnit);
                    if (uHasDetaileds[fi] == 1) {
                        eyeL = uEyeLefts[fi];
                        eyeR = uEyeRights[fi];
                        mouthC = uMouthPoss[fi];
                    }

                    // 2. Dark Circles removal (黑眼圈) just below the tracked eyes
                    vec2 bagL = eyeL + vec2(0.0, 0.3 * fUnit);
                    vec2 bagR = eyeR + vec2(0.0, 0.3 * fUnit);
                    float distBagL = distance(tc, bagL);
                    float distBagR = distance(tc, bagR);
                    // v2.0.159：正圆 → 横向椭圆（眼袋是横卧在眼下的月牙形），并保留「保高光提亮」
                    vec2 bagRadius = vec2(0.28 * fUnit, 0.13 * fUnit);
                    float bagMask = max(
                        ellipseMask(tc, bagL, bagRadius, 0.0, 0.55),
                        ellipseMask(tc, bagR, bagRadius, 0.0, 0.55)
                    );
                    if (bagMask > 0.001 && isSkin(color.rgb)) {
                        color.rgb += vec3(uDarkCircles * 0.15) * (1.0 - color.rgb) * bagMask;
                    }

                    // 3. Eyebrows definitions (眉毛) darkening directly above the eyes
                    vec2 browL = eyeL + vec2(0.0, -0.5 * fUnit);
                    vec2 browR = eyeR + vec2(0.0, -0.5 * fUnit);
                    float distBrowL = distance(tc, browL);
                    float distBrowR = distance(tc, browR);
                    // v2.0.159：正圆 → **横向椭圆**（眉是斜长条，正圆只会涂成一团）。
                    // 倾角用固定 ±0.14 rad（≈8°）近似眉毛走向。
                    vec2 browRadius = vec2(0.30 * fUnit, 0.115 * fUnit);
                    float browMaskL = ellipseMask(tc, browL, browRadius, -0.14, 0.45);
                    if (browMaskL > 0.001) {
                        color.rgb = mix(color.rgb, color.rgb * vec3(0.52, 0.50, 0.53), browMaskL * uEyebrows * 0.70);
                    }
                    float browMaskR = ellipseMask(tc, browR, browRadius, 0.14, 0.45);
                    if (browMaskR > 0.001) {
                        color.rgb = mix(color.rgb, color.rgb * vec3(0.52, 0.50, 0.53), browMaskR * uEyebrows * 0.70);
                    }

                    // 4. Lipstick coloring (口红) around the tracked mouth
                    float distLip = distance(tc, mouthC);
                    // v2.0.159：正圆 → **贴合唇形的椭圆**。宽/高/倾角来自 MediaPipe 唇部关键点
                    // （uMouthHalfWs[fi] 为 0 表示未取到，回退按 fUnit 估算，兼容 FaceDetector 兜底路径）。
                    // 采样图 y 与视口 UV 的 y 方向相反，所以倾角取负。
                    vec2 mouthRadius = (uMouthHalfWs[fi] > 0.0001)
                        ? vec2(uMouthHalfWs[fi] * 1.18, max(uMouthHalfHs[fi], uMouthHalfWs[fi] * 0.30) * 1.60)
                        : vec2(0.34 * fUnit, 0.13 * fUnit);
                    float lipMask = ellipseMask(tc, mouthC, mouthRadius, -uMouthAngles[fi], 0.35);
                    if (lipMask > 0.001) {
                        // soft-light 上色：保留唇部原有明暗与高光（不再三通道硬拉）
                        color.rgb = mix(
                            color.rgb,
                            softLight(color.rgb, vec3(0.74, 0.16, 0.26)),
                            lipMask * uLipstick * 0.70
                        );
                    }

                    // 5. Cheek Blush coloring (腮红) on the cheeks outside the eyes
                    vec2 blushL = eyeL + vec2(-0.55 * fUnit, 0.55 * fUnit);
                    vec2 blushR = eyeR + vec2(0.55 * fUnit, 0.55 * fUnit);
                    float distBlushL = distance(tc, blushL);
                    float distBlushR = distance(tc, blushR);
                    // v2.0.159：正圆 → 椭圆（腮红是沿脸颊斜向下的大椭圆），并保留 soft-light 上色
                    vec2 blushRadius = vec2(0.46 * fUnit, 0.30 * fUnit);
                    float blushMaskL = ellipseMask(tc, blushL, blushRadius, -0.28, 0.50);
                    if (blushMaskL > 0.001 && isSkin(color.rgb)) {
                        color.rgb = mix(color.rgb, softLight(color.rgb, vec3(0.93, 0.62, 0.66)), blushMaskL * uBlush * 0.75);
                    }
                    float blushMaskR = ellipseMask(tc, blushR, blushRadius, 0.28, 0.50);
                    if (blushMaskR > 0.001 && isSkin(color.rgb)) {
                        color.rgb = mix(color.rgb, softLight(color.rgb, vec3(0.93, 0.62, 0.66)), blushMaskR * uBlush * 0.75);
                    }

                    // 6. Teeth Whitening (白牙) inside the lip cavity
                    float distTeethSpace = distance(tc, mouthC);
                    // v2.0.159：正圆 → 细长椭圆（牙齿在嘴腔里是横条），并跟随嘴的倾角
                    vec2 teethRadius = (uMouthHalfWs[fi] > 0.0001)
                        ? vec2(uMouthHalfWs[fi] * 0.72, max(uMouthHalfHs[fi], uMouthHalfWs[fi] * 0.30) * 0.55)
                        : vec2(0.20 * fUnit, 0.075 * fUnit);
                    float teethMask = ellipseMask(tc, mouthC, teethRadius, -uMouthAngles[fi], 0.40);
                    if (teethMask > 0.001) {
                        float luma = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                        if (luma > 0.45) {
                            color.rgb = mix(color.rgb, vec3(luma + 0.12), teethMask * uTeethWhitening * 0.75);
                        }
                    }
                    }   // v2.0.177: end if (inFaceBox)
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


    fun uniformArrayLocation(progId: Int, name: String): Int {
        val withIndex = GLES20.glGetUniformLocation(progId, "$name[0]")
        return if (withIndex != -1) withIndex else GLES20.glGetUniformLocation(progId, name)
    }

    fun compileShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val err = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compile error (type=$type): $err")
        }
        return shader
    }

    fun createFboTexture(w: Int, h: Int): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return tex[0]
    }

    fun createFboFor(texId: Int): Int {
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, texId, 0
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return fbo[0]
    }

    fun buildMainProgram(videoVariant: Boolean, onError: (String) -> Unit): VRMainGLProgram? {
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
            VRMainGLProgram(
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
                hTextureDetail = GLES20.glGetUniformLocation(progId, "uTextureDetail"),
                hBrightness = GLES20.glGetUniformLocation(progId, "uBrightness"),
                hContrast = GLES20.glGetUniformLocation(progId, "uContrast"),
                hTexelSize = GLES20.glGetUniformLocation(progId, "uTexelSize"),
                hIsMirrored = GLES20.glGetUniformLocation(progId, "uIsMirrored"),
                hWarpMode = GLES20.glGetUniformLocation(progId, "uWarpMode"),
                hCurvature = GLES20.glGetUniformLocation(progId, "uCurvature"),
                hFisheyeFov = GLES20.glGetUniformLocation(progId, "uFisheyeFov"),
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
                hFaceCount = GLES20.glGetUniformLocation(progId, "uFaceCount"),
                hFaceCenters = uniformArrayLocation(progId, "uFaceCenters"),
                hEyeDistances = uniformArrayLocation(progId, "uEyeDistances"),
                hHasDetaileds = uniformArrayLocation(progId, "uHasDetaileds"),
                hEyeLefts = uniformArrayLocation(progId, "uEyeLefts"),
                hEyeRights = uniformArrayLocation(progId, "uEyeRights"),
                hMouthPoss = uniformArrayLocation(progId, "uMouthPoss"),
                hMouthHalfWs = uniformArrayLocation(progId, "uMouthHalfWs"),
                hMouthHalfHs = uniformArrayLocation(progId, "uMouthHalfHs"),
                hMouthAngles = uniformArrayLocation(progId, "uMouthAngles"),
                hChins = uniformArrayLocation(progId, "uChins"),
                hWarpDualCenter = GLES20.glGetUniformLocation(progId, "uWarpDualCenter"),
                hLutTexture = GLES20.glGetUniformLocation(progId, "uLutTexture"),
                hLutMix = GLES20.glGetUniformLocation(progId, "uLutMix")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compile/link ${if (videoVariant) "video" else "image"} GLES program", e)
            onError(e.message ?: "shader compile failed")
            null
        }
    }

}
