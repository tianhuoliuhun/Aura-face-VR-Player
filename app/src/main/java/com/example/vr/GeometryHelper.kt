package com.example.vr

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeometryHelper {

    fun createFloatBuffer(array: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(array.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(array)
                position(0)
            }
        }
    }

    val quadPositions = floatArrayOf(
        -1.0f,  1.0f, 0.0f, // top left
        -1.0f, -1.0f, 0.0f, // bottom left
         1.0f,  1.0f, 0.0f, // top right
         1.0f, -1.0f, 0.0f  // bottom right
    )

    val quadTexCoords = floatArrayOf(
        0.0f, 0.0f, // top left
        0.0f, 1.0f, // bottom left
        1.0f, 0.0f, // top right
        1.0f, 1.0f  // bottom right
    )

    val quadVertexCount = 4

    // ==========================================================================
    // EAC（Equi-Angular Cubemap，等角立方体贴图）—— YouTube / Google 的 360° 片源格式
    // --------------------------------------------------------------------------
    // 与 generateSphere 的关系：**顶点位置完全相同**（同一个球面），
    // 只有 UV 不同 —— 球面网格的 uv 是等距柱状（Equirectangular），
    // EAC 版则按「方向 → 立方体面 → 面内等角坐标 → 3×2 atlas」重新计算。
    //
    // ## 算法（两处权威来源交叉验证，结论一致）
    // 1) RWTH Aachen 学位论文 2.4.3.3.2 节：`SPAF_EAC(u) = tan(π/4 · u)`
    // 2) oximedia-360 的 Rust 实现 eac.rs：`t = tan(π/4·(2s−1))` / `s = (atan(t)/(π/4)+1)/2`
    //
    // 正向（编码，等距柱状 → EAC）用 tan；**本函数做的是反向（解码，EAC → 采样）**，
    // 所以对每个方向的立方体面内坐标 t ∈ [−1,1] 取：
    //
    //     s = (atan(t) / (π/4) + 1) / 2        s ∈ [0,1]
    //
    // ⚠️ 为什么不能像 CMP 那样直接用线性 `(t+1)/2`：CMP 的样本在球面上**角间距不等**
    //    （面中心采样稀疏、边界密集），EAC 正是为此做的等角修正。
    //    用错公式的典型症状是「立方体面边界处明显拉伸/压缩、移动视角时图像错位」。
    //
    // ## 立方体面内坐标与面索引约定（JVET 项目 CMP 映射表，与 YouTube 一致）
    //   face 0 (+X): u=-z/nx, v=-y/nx      face 1 (−X): u= z/nx, v=-y/nx
    //   face 2 (+Y): u= x/ny, v= z/ny      face 3 (−Y): u= x/ny, v=-z/ny
    //   face 4 (+Z): u= x/nz, v=-y/nz      face 5 (−Z): u=-x/nz, v=-y/nz
    //   （u,v 落在 [−1,1]，因为面半宽对应的角度是 π/4、tan(π/4)=1）
    //
    // ## atlas 布局：3 列 × 2 行
    //   col = face % 3, row = face / 3, 即
    //   [0 1 2]
    //   [3 4 5]
    // ==========================================================================

    /** 把立方体面索引 + 面内等角坐标 (s,t ∈ [0,1]) 映射到 3×2 atlas 的 UV */
    private fun eacFaceToAtlas(face: Int, s: Float, t: Float): Pair<Float, Float> {
        val col = face % 3
        val row = face / 3
        return (col + s) / 3f to (row + t) / 2f
    }

    /**
     * 球面单位方向 → EAC atlas UV。
     *
     * @param nx 单位方向 x（余弦分量）
     * @param ny 单位方向 y
     * @param nz 单位方向 z
     */
    private fun eacDirToUv(nx: Float, ny: Float, nz: Float): Pair<Float, Float> {
        val ax = kotlin.math.abs(nx)
        val ay = kotlin.math.abs(ny)
        val az = kotlin.math.abs(nz)

        // 主轴决定落在哪一面；面内坐标 = 另外两轴 / 主轴分量
        val face: Int
        var tu: Float
        var tv: Float
        if (ax >= ay && ax >= az) {
            // ±X
            val inv = 1f / nx
            tu = -nz * inv
            tv = -ny * inv
            face = if (nx >= 0f) 0 else 1
            if (face == 1) tu = -tu          // −X 面：u =  z/nx = −(−z/nx)
        } else if (ay >= ax && ay >= az) {
            // ±Y
            val inv = 1f / ny
            tu = nx * inv
            tv = nz * inv
            face = if (ny >= 0f) 2 else 3
            if (face == 3) tv = -tv          // −Y 面：v = −z/ny
        } else {
            // ±Z
            val inv = 1f / nz
            tu = nx * inv
            tv = -ny * inv
            face = if (nz >= 0f) 4 else 5
            if (face == 5) tu = -tu          // −Z 面：u = −x/nz
        }

        // ⚠️ 夹到面的有效范围（|t| ≤ 1）。主轴分量理论上保证 ≤1，但浮点误差可能越界，
        //    atan 本身能接受任意值，夹一下更稳（避免落到相邻面的重复区域）。
        tu = tu.coerceIn(-1f, 1f)
        tv = tv.coerceIn(-1f, 1f)

        // —— EAC 等角逆映射（核心，见上文推导）——
        val k = (Math.PI / 4.0).toFloat()
        val s = (kotlin.math.atan(tu) / k + 1f) * 0.5f
        val t = (kotlin.math.atan(tv) / k + 1f) * 0.5f

        return eacFaceToAtlas(face, s, t)
    }

    /**
     * 生成 EAC 布局的球体网格：**顶点位置与 [generateSphere] 完全相同**，
     * UV 按 EAC atlas 重新计算。
     *
     * 用法与 [generateSphere] 一致（返回 `positions to texCoords`），
     * 于是渲染侧只需在 `when (projectionMode)` 里多一个分支，不必改顶点着色器。
     */
    fun generateEacSphere(
        radius: Float,
        latBands: Int = 40,
        lngBands: Int = 40
    ): Pair<FloatBuffer, FloatBuffer> {
        val posList = FloatArrayList()
        val texList = FloatArrayList()

        // 复用球面几何参数化：与 generateSphere 保持一致的朝向，
        // 避免 EAC 模式相对 360 模式出现「上下颠倒/左右镜像」这类朝向差异。
        for (i in 0 until latBands) {
            val lat0 = Math.PI * i.toDouble() / latBands
            val lat1 = Math.PI * (i + 1).toDouble() / latBands
            val sinLat0 = sin(lat0).toFloat()
            val cosLat0 = cos(lat0).toFloat()
            val sinLat1 = sin(lat1).toFloat()
            val cosLat1 = cos(lat1).toFloat()

            for (j in 0 until lngBands) {
                val lng0 = 2.0 * Math.PI * j.toDouble() / lngBands
                val lng1 = 2.0 * Math.PI * (j + 1).toDouble() / lngBands
                val sinLng0 = sin(lng0).toFloat()
                val cosLng0 = cos(lng0).toFloat()
                val sinLng1 = sin(lng1).toFloat()
                val cosLng1 = cos(lng1).toFloat()

                // 四个角点（与 generateSphere 相同的参数化，索引：0=P00, 1=P10, 2=P01, 3=P11）
                val xs = floatArrayOf(
                    sinLat0 * sinLng0, sinLat1 * sinLng0,
                    sinLat0 * sinLng1, sinLat1 * sinLng1
                )
                val ys = floatArrayOf(cosLat0, cosLat1, cosLat0, cosLat1)
                val zs = floatArrayOf(
                    sinLat0 * cosLng0, sinLat1 * cosLng0,
                    sinLat0 * cosLng1, sinLat1 * cosLng1
                )
                // ⚠️ 必须与 generateSphere 的三角剖分**逐顶点一致**：
                //    Triangle 1 = P00,P10,P01   Triangle 2 = P01,P10,P11
                //    每个 quad 输出 **6 个顶点**（GL_TRIANGLES）。
                //    若图省事只输出 4 个顶点（四边形），drawMethod 又是 GL_TRIANGLES，
                //    三角形会两两错配、整张球面撕裂成乱面。
                val order = intArrayOf(0, 1, 2, 2, 1, 3)
                for (k in order) {
                    posList.add(radius * xs[k])
                    posList.add(radius * ys[k])
                    posList.add(radius * zs[k])
                    // ⚠️ 用**单位方向**算 UV（不乘 radius）——
                    //    半径≠1 时若乘了 radius，主轴判断虽不变，但 tu/tv 会被缩放、UV 全错。
                    val (u, v) = eacDirToUv(xs[k], ys[k], zs[k])
                    texList.add(u)
                    texList.add(v)
                }
            }
        }
        return Pair(
            createFloatBuffer(posList.toArray()),
            createFloatBuffer(texList.toArray())
        )
    }

    fun generateSphere(
        radius: Float,
        latBands: Int = 40,
        lngBands: Int = 40,
        isHalfSphere: Boolean = false
    ): Pair<FloatBuffer, FloatBuffer> {
        val posList = FloatArrayList()
        val texList = FloatArrayList()

        val maxLngFactor = if (isHalfSphere) 1.0f else 2.0f

        for (i in 0 until latBands) {
            val lat0 = Math.PI * i.toDouble() / latBands
            val lat1 = Math.PI * (i + 1).toDouble() / latBands

            val sinLat0 = sin(lat0).toFloat()
            val cosLat0 = cos(lat0).toFloat()
            val sinLat1 = sin(lat1).toFloat()
            val cosLat1 = cos(lat1).toFloat()

            for (j in 0 until lngBands) {
                val lng0 = maxLngFactor * Math.PI * j.toDouble() / lngBands
                val lng1 = maxLngFactor * Math.PI * (j + 1).toDouble() / lngBands

                val sinLng0 = sin(lng0).toFloat()
                val cosLng0 = cos(lng0).toFloat()
                val sinLng1 = sin(lng1).toFloat()
                val cosLng1 = cos(lng1).toFloat()

                // P00 (lat0, lng0)
                val x00 = radius * sinLat0 * sinLng0
                val y00 = radius * cosLat0
                val z00 = radius * sinLat0 * cosLng0
                val u00 = j.toFloat() / lngBands
                val v00 = i.toFloat() / latBands

                // P10 (lat1, lng0)
                val x10 = radius * sinLat1 * sinLng0
                val y10 = radius * cosLat1
                val z10 = radius * sinLat1 * cosLng0
                val u10 = j.toFloat() / lngBands
                val v10 = (i + 1).toFloat() / latBands

                // P01 (lat0, lng1)
                val x01 = radius * sinLat0 * sinLng1
                val y01 = radius * cosLat0
                val z01 = radius * sinLat0 * cosLng1
                val u01 = (j + 1).toFloat() / lngBands
                val v01 = i.toFloat() / latBands

                // P11 (lat1, lng1)
                val x11 = radius * sinLat1 * sinLng1
                val y11 = radius * cosLat1
                val z11 = radius * sinLat1 * cosLng1
                val u11 = (j + 1).toFloat() / lngBands
                val v11 = (i + 1).toFloat() / latBands

                // Triangle 1: P00, P10, P01
                posList.add(x00); posList.add(y00); posList.add(z00)
                texList.add(u00); texList.add(v00)

                posList.add(x10); posList.add(y10); posList.add(z10)
                texList.add(u10); texList.add(v10)

                posList.add(x01); posList.add(y01); posList.add(z01)
                texList.add(u01); texList.add(v01)

                // Triangle 2: P01, P10, P11
                posList.add(x01); posList.add(y01); posList.add(z01)
                texList.add(u01); texList.add(v01)

                posList.add(x10); posList.add(y10); posList.add(z10)
                texList.add(u10); texList.add(v10)

                posList.add(x11); posList.add(y11); posList.add(z11)
                texList.add(u11); texList.add(v11)
            }
        }

        return Pair(
            createFloatBuffer(posList.toArray()),
            createFloatBuffer(texList.toArray())
        )
    }

    // 盒子模式（Box Mode）：六面体细分 + 逆向射线 UV 映射（等距柱状全景 → 立方体内部）
    // 参考：HarmonyOS VR 播放器六面体贴图映射算法（细分 16×16，逐顶点球面反算 UV）
    fun generateBox(
        size: Float = 1.0f,
        subdivisions: Int = 16
    ): Pair<FloatBuffer, FloatBuffer> {
        val posList = FloatArrayList()
        val texList = FloatArrayList()
        val PI = Math.PI

        // 六面体轴向步进表：center, sideU, sideV
        // 按文章规范：Front/Back/Left/Right/Top/Bottom 各面法向 + UV 切向
        val faces = listOf(
            floatArrayOf(0f, 0f, -1f,  1f, 0f, 0f,  0f, 1f, 0f),   // Front
            floatArrayOf(0f, 0f, 1f,  -1f, 0f, 0f,  0f, 1f, 0f),   // Back
            floatArrayOf(-1f, 0f, 0f,  0f, 0f, 1f,  0f, 1f, 0f),   // Left
            floatArrayOf(1f, 0f, 0f,   0f, 0f, -1f, 0f, 1f, 0f),   // Right
            floatArrayOf(0f, 1f, 0f,   1f, 0f, 0f,  0f, 0f, 1f),   // Top
            floatArrayOf(0f, -1f, 0f,  1f, 0f, 0f,  0f, 0f, -1f)   // Bottom
        )

        for (face in faces) {
            val cx = face[0]; val cy = face[1]; val cz = face[2]
            val ux = face[3]; val uy = face[4]; val uz = face[5]
            val vx = face[6]; val vy = face[7]; val vz = face[8]

            for (i in 0 until subdivisions) {
                for (j in 0 until subdivisions) {
                    val u0 = j.toFloat() / subdivisions - 0.5f
                    val v0 = i.toFloat() / subdivisions - 0.5f
                    val u1 = (j + 1).toFloat() / subdivisions - 0.5f
                    val v1 = (i + 1).toFloat() / subdivisions - 0.5f

                    // 四个角顶点的 3D 坐标
                    val p00 = floatArrayOf(
                        (cx + u0 * ux + v0 * vx) * size, (cy + u0 * uy + v0 * vy) * size, (cz + u0 * uz + v0 * vz) * size
                    )
                    val p10 = floatArrayOf(
                        (cx + u0 * ux + v1 * vx) * size, (cy + u0 * uy + v1 * vy) * size, (cz + u0 * uz + v1 * vz) * size
                    )
                    val p01 = floatArrayOf(
                        (cx + u1 * ux + v0 * vx) * size, (cy + u1 * uy + v0 * vy) * size, (cz + u1 * uz + v0 * vz) * size
                    )
                    val p11 = floatArrayOf(
                        (cx + u1 * ux + v1 * vx) * size, (cy + u1 * uy + v1 * vy) * size, (cz + u1 * uz + v1 * vz) * size
                    )

                    // 逆向射线映射：顶点 → 经纬度 → UV（与球面等距柱状坐标一致）
                    // Triangle 1: P00, P10, P01
                    addBoxVertex(posList, texList, p00, PI)
                    addBoxVertex(posList, texList, p10, PI)
                    addBoxVertex(posList, texList, p01, PI)
                    // Triangle 2: P01, P10, P11
                    addBoxVertex(posList, texList, p01, PI)
                    addBoxVertex(posList, texList, p10, PI)
                    addBoxVertex(posList, texList, p11, PI)
                }
            }
        }

        return Pair(
            createFloatBuffer(posList.toArray()),
            createFloatBuffer(texList.toArray())
        )
    }

    // 逆向射线采样：从立方体中心向顶点 P 投射，反推经纬度 → UV
    // u = (lon + π) / 2π（0..1 横向 360°）；v = 1 - lat/π（与球面 v 方向一致：v=0 底部）
    private fun addBoxVertex(posList: FloatArrayList, texList: FloatArrayList, p: FloatArray, pi: Double) {
        posList.add(p[0]); posList.add(p[1]); posList.add(p[2])
        val r = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])
        val lon = atan2(p[2].toDouble(), p[0].toDouble())
        val lat = acos((p[1] / r).toDouble().coerceIn(-1.0, 1.0))
        texList.add(((lon + pi) / (2.0 * pi)).toFloat())
        texList.add((1.0 - lat / pi).toFloat())
    }

    // Helper lightweight float dynamic array to avoid boxing overhead / allocations
    private class FloatArrayList(initialCapacity: Int = 1000) {
        var data = FloatArray(initialCapacity)
        var size = 0

        fun add(element: Float) {
            if (size == data.size) {
                val newData = FloatArray(data.size * 2)
                System.arraycopy(data, 0, newData, 0, size)
                data = newData
            }
            data[size++] = element
        }

        fun toArray(): FloatArray {
            val result = FloatArray(size)
            System.arraycopy(data, 0, result, 0, size)
            return result
        }
    }
}
