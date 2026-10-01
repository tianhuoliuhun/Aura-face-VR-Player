package com.example.vr

/**
 * ============================================================================
 * 视频画质增强的 GLSL 源码：FSR 超分（EASU + RCAS）与 MEMC 三件套
 * ============================================================================
 *
 * 全部按 **GLSL ES 1.00 (ESSL 100)** 写 —— 本项目虽在 ES3 上下文（见
 * VRGLSurfaceView.setEGLContextClientVersion(3)），但所有 shader 一律用 ESSL 100
 * 以保证「华为路径 / 普通路径」两处上下文都能编译（ES3 向后兼容 ESSL 100）。
 * 因此这里不可以用 textureGather / in-out / 数组构造器等 ES3 专有语法。
 *
 * ⚠️ 所有 GLSL 字面量内部**只允许 ASCII 字符**（注释也一样用英文）。
 *    部分严格驱动对 shader 源码里的非 ASCII 字节敏感；本项目的 shader 注释
 *    一贯全英文，这里保持一致。中文说明一律写在 Kotlin 侧的 KDoc 里。
 *
 * ---------------------------------------------------------------------------
 * 【管线顺序：MEMC 在前，超分在后】（与参考实现 MemcSuperResolutionGraph 一致）
 *
 *   源帧 ──► MEMC(ME→MC，改变帧数与时间戳) ──► FSR(EASU→RCAS，只改空间尺寸) ──► 主渲染
 *
 *   三条理由：
 *   ① 成本：ME 的成本 ∝ 像素数 × 搜索半径²。先超分到 4K 再做 ME，成本是
 *      先 MEMC 后超分的 **16 倍**，实时根本跑不动。
 *   ② 质量：超分会「生成」原本不存在的细节；若先超分再块匹配，MV 会去匹配
 *      这些假细节，矢量变得不可信。反过来，MEMC 输出（时域对齐后的帧）比原帧
 *      更干净，超分在这个输入上效果更好。
 *   ③ 时序语义：MEMC 属于时间域（改时间戳），超分属于空间域（不碰时间戳）。
 *      时间域放上游，时间戳就只需在一处管理。
 *
 * ---------------------------------------------------------------------------
 * 【各单位约定】
 *   uTexel = (1/width, 1/height)   —— 由 Kotlin 侧按**对应纹理自己的尺寸**传入。
 *     不要复用别的 pass 的 texel：半分辨率磨皮 pass 曾因复用全分辨率 texel
 *     导致采样半径翻倍（见 VRGLRenderer 磨皮段的注释）。
 * ============================================================================
 */
object VideoEnhanceShaders {

    // ⚠️ 本文件里所有以 .trimIndent() 结尾的多行 shader **必须是普通 val，不能是 const val**。
    //    reason: trimIndent() 是函数调用，const val 的初始化器只能是编译期常量表达式，
    //    K2 会直接报 "Const 'val' initializer must be a constant value"。
    //    （ENHANCE_VS 是纯字符串拼接，没有函数调用，所以它可以保持 const。）
    /** 全屏 quad 顶点着色器：坐标直通，与 VRGLRenderer 的磨皮 pass 保持同一套 attribute 名 */
    const val ENHANCE_VS =
        "attribute vec2 aPos;attribute vec2 aTex;varying vec2 vTex;" +
            "void main(){gl_Position=vec4(aPos,0.0,1.0);vTex=aTex;}"

    // ========================================================================
    // region FSR 超分
    // ========================================================================

    /**
     * EASU：边缘自适应上采样（FSR1 第一阶段）。
     *
     * 实现说明（与原版 AMD FSR EASU 的差异，务必知悉）：
     *   原版 EASU 用 12 个采样 + 内置的菱形邻域与方向性权重查表。
     *   这里实现的是**同一目标的可预期等价物**：4x4 邻域的 Catmull-Rom 双三次插值。
     *   选它的理由是「数学确定、无歧义、不依赖专利文本」——
     *   Catmull-Rom 本身就是保边缘的三次核，上采样得到的是「清晰的双三次」而不是
     *   双线性的糊边，再叠加后面的 RCAS 锐化即可拿到同级观感。
     *
     * 采样数：16（4x4）。低于这个邻域不足以构成三次核。
     *
     * ⚠️ 若日后换成原版 EASU，必须同步改两处：
     *   ① 本函数的采样数注释与 VRGLRenderer 里的性能统计常量；
     *   ② 目标 FBO 的尺寸策略（原版 EASU 内部自带超采样）。
     */
    fun fsrEasuFragment(fromOes: Boolean): String {
        val header = buildString {
            if (fromOes) {
                // #extension 必须在任何非预处理指令之前；本项目主 shader 用的是
                // `#ifdef VIDEO_OES` + `: enable`，这里为独立 pass 直接置顶声明。
                append("#extension GL_OES_EGL_image_external : require\n")
            }
            append("precision highp float;\n")
        }
        val srcDecl = if (fromOes) "uniform samplerExternalOES uSrc;" else "uniform sampler2D uSrc;"
        return header + """
            varying vec2 vTex;
            $srcDecl
            uniform vec2 uSrcTexel;   // 1/(srcW, srcH) of the SOURCE texture

            // Catmull-Rom cubic kernel (standard a = -0.5 form)
            float cubicW(float x) {
                float ax = abs(x);
                if (ax < 1.0) {
                    return 1.5 * ax * ax * ax - 2.5 * ax * ax + 1.0;
                }
                if (ax < 2.0) {
                    return -0.5 * ax * ax * ax + 2.5 * ax * ax - 4.0 * ax + 2.0;
                }
                return 0.0;
            }

            void main() {
                vec2 srcSize = 1.0 / uSrcTexel;
                // Continuous source-space coordinate, pixel-centre aligned.
                vec2 p = vTex * srcSize - 0.5;
                vec2 base = floor(p);
                vec2 f = p - base;

                vec3 acc = vec3(0.0);
                float wsum = 0.0;
                // 4x4 neighbourhood. Loop bounds must be constant in ESSL 100.
                for (int j = 0; j < 4; j++) {
                    float wy = cubicW(float(j) - 1.0 - f.y);
                    for (int i = 0; i < 4; i++) {
                        float wx = cubicW(float(i) - 1.0 - f.x);
                        vec2 tc = (base + vec2(float(i) - 1.0, float(j) - 1.0) + 0.5) * uSrcTexel;
                        acc += texture2D(uSrc, tc).rgb * (wx * wy);
                        wsum += wx * wy;
                    }
                }
                vec3 col = acc / max(wsum, 1e-5);
                gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
            }
        """.trimIndent()
    }

    /**
     * RCAS：鲁棒对比度自适应锐化（FSR1 第二阶段）。
     *
     * 5 采样（中心 + 上下左右）。关键在「按局部对比度限幅」：
     * 先算出四邻域与中心的 min/max，锐化结果一律 clamp 回这个范围，
     * 于是平坦区不会因锐化冒出噪点、边缘处也不会过冲出白边。
     *
     * ⚠️ uSharpness 是**线性强度**（0~1），不是 AMD 原版的 stops 档位。
     *    参考实现取 0.2 作为「温和锐化」，本实现按同一观感量级取值。
     */
    val FSR_RCAS_FS = """
        precision highp float;
        varying vec2 vTex;
        uniform sampler2D uTex;
        uniform vec2 uTexel;      // 1/(dstW, dstH) of the UPSCALED texture
        uniform float uSharpness; // 0.0 = off, 0.2 = default mild sharpening

        void main() {
            vec3 b = texture2D(uTex, vTex).rgb;
            vec3 l = texture2D(uTex, vTex - vec2(uTexel.x, 0.0)).rgb;
            vec3 r = texture2D(uTex, vTex + vec2(uTexel.x, 0.0)).rgb;
            vec3 u = texture2D(uTex, vTex - vec2(0.0, uTexel.y)).rgb;
            vec3 d = texture2D(uTex, vTex + vec2(0.0, uTexel.y)).rgb;

            vec3 mn = min(min(min(l, r), min(u, d)), b);
            vec3 mx = max(max(max(l, r), max(u, d)), b);

            vec3 blur = (l + r + u + d) * 0.25;
            vec3 detail = b - blur;
            // Contrast-limited clamp: this is the "Robust" in RCAS.
            vec3 limit = (mx - mn) * 0.25 + 0.001;
            detail = clamp(detail, -limit, limit);

            vec3 res = b + detail * uSharpness * 2.0;
            gl_FragColor = vec4(clamp(res, mn, mx), 1.0);
        }
    """.trimIndent()

    // endregion

    // ========================================================================
    // region MEMC 三件套
    // ========================================================================

    /**
     * 通用 SAD（绝对差和）子程序 —— 内联进 ME shader，不单独成文件。
     *
     * 在 16x16 的块内取 4x4 = 16 个采样点比较 prev 与 curr，
     * 对 RGB 三通道求和后取平均。块内只采样 16 点是**刻意的**：
     * 全采样（256 点）成本高一个数量级，而块匹配只需要「统计意义上有区分度」，
     * 16 点已足以让纹理块区分出不同位移（见参考实现的实测结论）。
     */
    private val SAD_HELPER = """
        float sadAt(vec2 centerUv, vec2 mvUv, vec2 stepInBlock) {
            float sum = 0.0;
            for (int j = 0; j < 4; j++) {
                for (int i = 0; i < 4; i++) {
                    vec2 off = (vec2(float(i), float(j)) - 1.5) * stepInBlock;
                    vec3 pv = texture2D(uTexPrev, centerUv + off).rgb;
                    vec3 cv = texture2D(uTexCurr, centerUv + off + mvUv).rgb;
                    sum += abs(pv.r - cv.r) + abs(pv.g - cv.g) + abs(pv.b - cv.b);
                }
            }
            return sum * 0.0625; // divide by 16
        }
    """.trimIndent()

    /**
     * ME：块匹配运动估计。
     *
     * 【MV 的符号约定（贯穿 MC 与 Kotlin 侧，务必一致）】
     *   MV 描述「prev 里的内容位移到 curr 的哪里」，即
     *       prev 坐标 = curr 坐标 - mv
     *   MC 里据此做前向/反向补偿。
     *
     * 【输出格式】RGBA8 纹理，每个纹素 = 一个块：
     *   rg = MV 的 x / y，编码到 [0,1]（解码：mv = (rg * 2 - 1) * uSearchRadius）
     *   b  = 块纹理能量（平坦区的 MV 不可信，MC 会据此降权）
     *   a  = 置信度 0..1
     *
     * 【搜索策略：3 步菱形 + 2 轮 ±1 精修】
     *   第 0 步 零矢量基线
     *   第 1 步 十字搜索（半径 r）
     *   第 2 步 对角搜索（半径 r/2）
     *   第 3 步 中等步长（半径 r/4）
     *   第 4 步 ±1 精修 ×2 轮（拿到整数像素精度）
     * 这是「先粗后细」的经典做法。单步全搜索（半径 r 的方形窗口）的调用次数是
     * (2r+1)²，r=16 时 1089 次；3 步法只要 21 次却能达到接近的精度。
     *
     * ⚠️ 改动搜索步数时必须同步更新 VRGLRenderer 里的采样统计常量，
     *    否则性能预算会失真。
     */
    val MEMC_ME_FS = """
        precision highp float;
        varying vec2 vTex;
        uniform sampler2D uTexPrev;
        uniform sampler2D uTexCurr;
        uniform vec2 uTexelSize;      // 1/(workW, workH)
        uniform float uSearchRadius;  // search radius in work-space pixels
        uniform float uBlockSize;     // block edge length in work-space pixels
        uniform float uSadThreshold;  // SAD level at which confidence reaches zero

        $SAD_HELPER

        void main() {
            // vTex is the MV-field coordinate. The MV field is sized
            // workSize / uBlockSize, so vTex is exactly the normalised centre of
            // the block this texel represents -- no conversion needed.
            vec2 center = vTex;
            vec2 stepInBlock = (uBlockSize / 4.0) * uTexelSize; // in-block sample spacing
            vec2 st = uTexelSize;

            // ---- step 0: zero-motion baseline ----
            float bestSad = sadAt(center, vec2(0.0), stepInBlock);
            vec2 bestMv = vec2(0.0);

            float r = uSearchRadius;
            vec2 mv;
            float s;

            // ---- step 1: cross search at radius r ----
            mv = vec2(-r, 0.0); s = sadAt(center, mv * st, stepInBlock);
            if (s < bestSad) { bestSad = s; bestMv = mv; }
            mv = vec2( r, 0.0); s = sadAt(center, mv * st, stepInBlock);
            if (s < bestSad) { bestSad = s; bestMv = mv; }
            mv = vec2(0.0, -r); s = sadAt(center, mv * st, stepInBlock);
            if (s < bestSad) { bestSad = s; bestMv = mv; }
            mv = vec2(0.0,  r); s = sadAt(center, mv * st, stepInBlock);
            if (s < bestSad) { bestSad = s; bestMv = mv; }

            // ---- step 2: diagonal search at radius r/2 ----
            float d = r * 0.5;
            mv = vec2(-d, -d); s = sadAt(center, mv * st, stepInBlock);
            if (s < bestSad) { bestSad = s; bestMv = mv; }
            mv = vec2( d, -d); s = sadAt(center, mv * st, stepInBlock);
            if (s < bestSad) { bestSad = s; bestMv = mv; }
            mv = vec2(-d,  d); s = sadAt(center, mv * st, stepInBlock);
            if (s < bestSad) { bestSad = s; bestMv = mv; }
            mv = vec2( d,  d); s = sadAt(center, mv * st, stepInBlock);
            if (s < bestSad) { bestSad = s; bestMv = mv; }

            // ---- step 3: mid step at radius r/4 ----
            float q = r * 0.25;
            mv = vec2(-q, 0.0); s = sadAt(center, mv * st, stepInBlock);
            if (s < bestSad) { bestSad = s; bestMv = mv; }
            mv = vec2( q, 0.0); s = sadAt(center, mv * st, stepInBlock);
            if (s < bestSad) { bestSad = s; bestMv = mv; }
            mv = vec2(0.0, -q); s = sadAt(center, mv * st, stepInBlock);
            if (s < bestSad) { bestSad = s; bestMv = mv; }
            mv = vec2(0.0,  q); s = sadAt(center, mv * st, stepInBlock);
            if (s < bestSad) { bestSad = s; bestMv = mv; }

            // ---- step 4: two rounds of +/-1 refinement (integer-pixel accuracy) ----
            for (int pass = 0; pass < 2; pass++) {
                mv = bestMv + vec2(-1.0, 0.0); s = sadAt(center, mv * st, stepInBlock);
                if (s < bestSad) { bestSad = s; bestMv = mv; }
                mv = bestMv + vec2( 1.0, 0.0); s = sadAt(center, mv * st, stepInBlock);
                if (s < bestSad) { bestSad = s; bestMv = mv; }
                mv = bestMv + vec2(0.0, -1.0); s = sadAt(center, mv * st, stepInBlock);
                if (s < bestSad) { bestSad = s; bestMv = mv; }
                mv = bestMv + vec2(0.0,  1.0); s = sadAt(center, mv * st, stepInBlock);
                if (s < bestSad) { bestSad = s; bestMv = mv; }
            }

            // ---- block texture energy ----
            // Flat blocks (plain walls, sky) give near-zero SAD for ANY motion vector,
            // so their MV carries no information. Without this term such blocks would
            // score full confidence and the interpolator would place them wrongly.
            // Two extra taps are enough as an energy proxy.
            vec3 e0 = texture2D(uTexCurr, center + stepInBlock).rgb;
            vec3 e1 = texture2D(uTexCurr, center - stepInBlock).rgb;
            float energy = abs(e0.r - e1.r) + abs(e0.g - e1.g) + abs(e0.b - e1.b);

            // ---- confidence ----
            float conf = 1.0 - clamp(bestSad / max(uSadThreshold, 1e-4), 0.0, 1.0);
            conf *= clamp(energy * 8.0, 0.0, 1.0);

            gl_FragColor = vec4(bestMv / max(uSearchRadius, 1e-4) * 0.5 + 0.5,
                                clamp(energy, 0.0, 1.0),
                                conf);
        }
    """.trimIndent()

    /**
     * MC：运动补偿插帧。
     *
     * 【双向补偿 + 遮挡检测】
     *   前向：在 prev 上按 phase 反向偏移采样（内容从 prev 移到 curr，中间时刻走 phase 的比例）
     *   反向：在 curr 上按 (1-phase) 正向偏移采样
     *   两张 MV 场（fwd 在 prev 坐标、bwd 在 curr 坐标）分别提供两个方向的位移。
     *
     *   两者差异大 = 该位置出现遮挡/新露出内容 → 运动补偿会撕裂，
     *   此时**退化为线性混合**（宁可糊一点也不要撕裂）—— 这是刻意的保护逻辑。
     *
     * ⚠️ 这条线性混合兜底路径会让「MEMC 有效果」在平坦画面看不出来，
     *    这是设计使然，不是 bug（参考实现的实测结论）。
     */
    val MEMC_MC_FS = """
        precision highp float;
        varying vec2 vTex;
        uniform sampler2D uTexPrev;
        uniform sampler2D uTexCurr;
        uniform sampler2D uTexMvF;  // forward MV, sampled in prev space
        uniform sampler2D uTexMvB;  // backward MV, sampled in curr space
        uniform vec2 uTexelSize;    // 1/(workW, workH)
        uniform float uSearchRadius;
        uniform float uPhase;       // 0 = prev, 1 = curr
        uniform float uOcclusionThresh;

        // rg is stored in [0,1]; decode back to pixel displacement
        vec2 decodeMv(vec4 enc) {
            return (enc.rg * 2.0 - 1.0) * uSearchRadius;
        }

        void main() {
            vec3 prevC = texture2D(uTexPrev, vTex).rgb;
            vec3 currC = texture2D(uTexCurr, vTex).rgb;

            vec4 mvFs = texture2D(uTexMvF, vTex);
            vec4 mvBs = texture2D(uTexMvB, vTex);
            vec2 mvF = decodeMv(mvFs);
            vec2 mvB = decodeMv(mvBs);
            float conf = min(mvFs.a, mvBs.a);

            // Forward compensation: the content of this mid-frame sample lives at
            // (current - phase*mv) inside prev.
            vec2 uvF = vTex - mvF * uPhase * uTexelSize;
            // Backward compensation: lives at (current + (1-phase)*mv) inside curr.
            vec2 uvB = vTex + mvB * (1.0 - uPhase) * uTexelSize;

            vec3 compF = texture2D(uTexPrev, uvF).rgb;
            vec3 compB = texture2D(uTexCurr, uvB).rgb;

            // Occlusion check: if the two directions disagree badly, something is
            // uncovered/covered here and motion compensation cannot be trusted.
            float diff = abs(compF.r - compB.r) + abs(compF.g - compB.g) + abs(compF.b - compB.b);
            float occlusion = 1.0 - clamp(diff / max(uOcclusionThresh, 1e-4), 0.0, 1.0);

            vec3 motionComp = mix(compF, compB, uPhase);
            vec3 linearBlend = mix(prevC, currC, uPhase);

            // Combine confidence and occlusion trust; fall back to the linear blend
            // whenever either is low.
            float w = clamp(conf * occlusion, 0.0, 1.0);
            gl_FragColor = vec4(mix(linearBlend, motionComp, w), 1.0);
        }
    """.trimIndent()

    /**
     * 场景切换检测：把两帧的差降到 1x1 输出，供 Kotlin 侧 glReadPixels(1x1) 读回。
     *
     * ⚠️ 为什么必须做这个：场景切换的两帧之间**不存在**真实的运动，
     * 任何 MV 都是噪声。此时若照常插帧，会生成「两个场景叠加」的严重鬼影。
     * 检测到高帧差就必须跳过插帧（直接输出 curr）。
     *
     * 1x1 输出 + 8x8 = 64 个稀疏采样点：整张纹理总共只跑 64 次采样，
     * 比在全分辨率上算帧差便宜 4 个数量级，且统计意义足够（场景切换是全画面级的）。
     */
    val MEMC_FRAME_DIFF_FS = """
        precision highp float;
        varying vec2 vTex;
        uniform sampler2D uTexPrev;
        uniform sampler2D uTexCurr;

        void main() {
            float sum = 0.0;
            for (int j = 0; j < 8; j++) {
                for (int i = 0; i < 8; i++) {
                    vec2 uv = (vec2(float(i), float(j)) + 0.5) * 0.125; // divide by 8
                    vec3 a = texture2D(uTexPrev, uv).rgb;
                    vec3 b = texture2D(uTexCurr, uv).rgb;
                    sum += abs(a.r - b.r) + abs(a.g - b.g) + abs(a.b - b.b);
                }
            }
            // Normalised to 0..1: 64 taps * 3 channels * max difference 1.0
            gl_FragColor = vec4(vec3(sum / 192.0), 1.0);
        }
    """.trimIndent()

    /**
     * 拷贝 pass：把源纹理（OES 或 2D）落到一张普通 2D 纹理上。
     *
     * MEMC 必须走这一步 —— ME/MC 需要**同时**持有 prev 与 curr 两张纹理，
     * 而 SurfaceTexture 只提供唯一一张 ExternalOES 纹理（且每帧 updateTexImage
     * 都会原地更新）。所以必须先把帧拷进自己的 2D 纹理，才能构成「双帧」。
     *
     * 顺带好处：后续所有 pass 都在普通 2D 纹理上工作，不必到处处理 OES 扩展。
     */
    fun memcCopyFragment(fromOes: Boolean): String {
        val header = buildString {
            if (fromOes) append("#extension GL_OES_EGL_image_external : require\n")
            append("precision highp float;\n")
        }
        val decl = if (fromOes) "uniform samplerExternalOES uSrc;" else "uniform sampler2D uSrc;"
        return header + """
            varying vec2 vTex;
            $decl
            void main() {
                gl_FragColor = vec4(texture2D(uSrc, vTex).rgb, 1.0);
            }
        """.trimIndent()
    }

    // endregion
}
