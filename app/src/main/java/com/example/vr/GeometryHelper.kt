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
