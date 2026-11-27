package com.example.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.abs

class LutUtilsAlutTest {

    @Test
    fun parseAlutToRgba_matchesParseCubeToRgbaWithinOneLsb() {
        // 构造一个 4x4x4 的测试 LUT（含线性梯度）
        val n = 4
        val cubeSb = StringBuilder("LUT_3D_SIZE $n\n")
        val alutBytes = ArrayList<Byte>()
        alutBytes.addAll("ALUT".toByteArray().toList())
        alutBytes.add((n and 0xFF).toByte())
        alutBytes.add(((n ushr 8) and 0xFF).toByte())
        alutBytes.add(8.toByte())
        alutBytes.add(0.toByte())

        for (b in 0 until n) {
            for (g in 0 until n) {
                for (r in 0 until n) {
                    val rf = r / (n - 1f)
                    val gf = g / (n - 1f)
                    val bf = b / (n - 1f)
                    cubeSb.append("$rf $gf $bf\n")
                    alutBytes.add((rf * 255f + 0.5f).toInt().toByte())
                    alutBytes.add((gf * 255f + 0.5f).toInt().toByte())
                    alutBytes.add((bf * 255f + 0.5f).toInt().toByte())
                }
            }
        }

        val cubeRgba = LutUtils.parseCubeToRgba(ByteArrayInputStream(cubeSb.toString().toByteArray()))
        val alutRgba = LutUtils.parseAlutToRgba(ByteArrayInputStream(alutBytes.toByteArray()))

        assertEquals(LutUtils.TEX_SIZE * LutUtils.TEX_SIZE * 4, cubeRgba.size)
        assertEquals(cubeRgba.size, alutRgba.size)
        var maxDiff = 0
        for (i in cubeRgba.indices) {
            val d = abs((cubeRgba[i].toInt() and 0xFF) - (alutRgba[i].toInt() and 0xFF))
            if (d > maxDiff) maxDiff = d
        }
        assertTrue("maxDiff=$maxDiff exceeded 1 LSB", maxDiff <= 1)
    }

    @Test
    fun allBuiltinLuts_haveValidAlutAssets() {
        val dir = listOf(
            File("app/src/main/assets/luts"),
            File("src/main/assets/luts")
        ).firstOrNull { it.isDirectory }
        if (dir == null) return

        for (lut in LutUtils.builtinLuts) {
            val f = File(dir, "${lut.fileName}.alut")
            assertTrue("Missing .alut for ${lut.fileName}", f.isFile)
            val rgba = f.inputStream().use { LutUtils.parseAlutToRgba(it) }
            assertEquals(LutUtils.TEX_SIZE * LutUtils.TEX_SIZE * 4, rgba.size)
        }
    }
}
