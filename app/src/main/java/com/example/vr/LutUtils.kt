package com.example.vr

import java.io.InputStream
import android.content.Context
import com.example.R
import kotlin.math.floor

/**
 * v104：3D LUT（.cube）解析与纹理打包工具。
 *
 * 支持两种来源：
 *  - 内置 assets/luts 目录下的 cube 文件（33x33x33，Rec.709）
 *  - 手机自选 cube 文件（任意 N 尺寸）
 *
 * 处理流程：解析 .cube → 三线性重采样到 64³ → 打包为 512x512 RGBA 纹理
 * （8x8 网格，每格 64x64 像素；层 z 位于 row=z/8, col=z%8，格内 x=r, y=g）。
 * shader 侧用 GL_LINEAR + 像素中心偏移采样，实现格内双线性 + 层间线性插值。
 */
object LutUtils {

    const val LUT_OUT = 64          // 重采样输出尺寸
    const val GRID = 8              // 每行格数
    const val TEX_SIZE = 512        // 输出纹理尺寸（GRID * LUT_OUT）

    /** 内置 LUT 预设列表（assets/luts/ 下的文件名 → 本地化名称资源 ID） */
    data class BuiltinLut(val fileName: String, val nameResId: Int)
    val builtinLuts: List<BuiltinLut> = listOf(
        BuiltinLut("01_Classic_Cyan_Orange", R.string.lut_classic_cyan_orange),
        BuiltinLut("02_Cinematic_Dark", R.string.lut_cinematic_dark),
        BuiltinLut("03_Soft_Film", R.string.lut_soft_film),
        BuiltinLut("04_Japanese_Fresh", R.string.lut_japanese_fresh),
        BuiltinLut("05_Warm_Sunset", R.string.lut_warm_sunset),
        BuiltinLut("06_Cool_Blue_Night", R.string.lut_cool_blue_night),
        BuiltinLut("07_Vintage_Film", R.string.lut_vintage_film),
        BuiltinLut("08_Cyberpunk", R.string.lut_cyberpunk),
        BuiltinLut("09_Black_White_Cinema", R.string.lut_black_white_cinema),
        BuiltinLut("10_Intense_Cyan_Orange", R.string.lut_intense_cyan_orange),
        BuiltinLut("11_Soft_Teal", R.string.lut_soft_teal),
        BuiltinLut("12_High_Contrast", R.string.lut_high_contrast)
    )

    /** 由文件名解析本地化显示名；非内置（自定义）LUT 直接返回文件名 */
    fun lutDisplayName(fileName: String, context: Context): String {
        return builtinLuts.firstOrNull { it.fileName == fileName }?.let { context.getString(it.nameResId) } ?: fileName
    }

    /**
     * 解析 .cube 流并返回 512x512 RGBA（每像素 4 字节，自上而下）。
     * 输入坐标域默认 [0,1]（未声明 DOMAIN_MIN/MAX 时）。
     */
    fun parseCubeToRgba(input: InputStream): ByteArray {
        val lines = input.bufferedReader().use { it.readLines() }
        var size = 0
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)
        val values = ArrayList<FloatArray>()

        for (line in lines) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val lower = t.lowercase()
            if (lower.startsWith("title")) continue
            // v117 修复：.cube 的标准写法是大写（LUT_3D_SIZE / DOMAIN_MIN / DOMAIN_MAX），
            // 这里必须用已小写化的 lower 去截取。原先用原始行 t 截取时，因 "LUT_3D_SIZE 33"
            // 里找不到小写分隔符，substringAfter 会返回整行本身，随后 toInt() 抛
            // NumberFormatException —— 导致内置 12 个 LUT 与 Adobe 标准写法导入的 cube 全部失效。
            if (lower.startsWith("lut_3d_size")) {
                size = lower.substringAfter("lut_3d_size").trim().toIntOrNull() ?: size
                continue
            }
            if (lower.startsWith("domain_min")) {
                domainMin = parseVec3(lower.substringAfter("domain_min"))
                continue
            }
            if (lower.startsWith("domain_max")) {
                domainMax = parseVec3(lower.substringAfter("domain_max"))
                continue
            }
            val parts = t.split(Regex("\\s+"))
            if (parts.size >= 3) {
                values.add(floatArrayOf(parts[0].toFloat(), parts[1].toFloat(), parts[2].toFloat()))
            }
        }
        require(size > 1 && values.size == size * size * size) {
            "invalid cube: size=$size points=${values.size}"
        }

        val inSize = size.toFloat() - 1f
        val out = ByteArray(TEX_SIZE * TEX_SIZE * 4)
        val outSize = LUT_OUT.toFloat() - 1f

        // 直接按行填充：遍历输出层 z、格内 y=g、x=r
        for (z in 0 until LUT_OUT) {
            val b = z / outSize // 0..1
            val bPos = b * inSize
            val row = z / GRID
            val col = z % GRID
            for (y in 0 until LUT_OUT) {
                val g = y / outSize
                val gPos = g * inSize
                for (x in 0 until LUT_OUT) {
                    val r = x / outSize
                    val rPos = r * inSize
                    val rgb = trilinear(values, size, rPos, gPos, bPos)
                    // 归一化输出值到像素（8bit）
                    val o = FloatArray(3)
                    for (c in 0..2) {
                        // 输出域按 [0,1] 归一化（若 LUT 输出超出 0..1 则夹取）
                        o[c] = (rgb[c].coerceIn(0f, 1f) * 255f + 0.5f).toInt().toFloat()
                    }
                    val tx = col * LUT_OUT + x
                    val ty = row * LUT_OUT + y
                    val idx = (ty * TEX_SIZE + tx) * 4
                    out[idx] = o[0].toInt().toByte()
                    out[idx + 1] = o[1].toInt().toByte()
                    out[idx + 2] = o[2].toInt().toByte()
                    out[idx + 3] = 255.toByte()
                }
            }
        }
        return out
    }

    private fun parseVec3(s: String): FloatArray {
        val p = s.trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
        return if (p.size >= 3) floatArrayOf(p[0], p[1], p[2]) else floatArrayOf(0f, 0f, 0f)
    }

    /**
     * 三线性插值采样 .cube 数据。
     * 数据顺序（Adobe 标准）：index = b*N*N + g*N + r（r 变化最快）。
     */
    private fun trilinear(
        values: List<FloatArray>,
        n: Int,
        rPos: Float,
        gPos: Float,
        bPos: Float
    ): FloatArray {
        val r0 = floor(rPos).toInt().coerceIn(0, n - 1)
        val r1 = (r0 + 1).coerceAtMost(n - 1)
        val fr = rPos - floor(rPos)
        val g0 = floor(gPos).toInt().coerceIn(0, n - 1)
        val g1 = (g0 + 1).coerceAtMost(n - 1)
        val fg = gPos - floor(gPos)
        val b0 = floor(bPos).toInt().coerceIn(0, n - 1)
        val b1 = (b0 + 1).coerceAtMost(n - 1)
        val fb = bPos - floor(bPos)

        fun at(r: Int, g: Int, b: Int): FloatArray = values[b * n * n + g * n + r]

        // 沿 r 插值（每个 g/b 组合）
        fun lerpR(g: Int, b: Int): FloatArray {
            val a = at(r0, g, b)
            val c = at(r1, g, b)
            return floatArrayOf(
                a[0] + (c[0] - a[0]) * fr,
                a[1] + (c[1] - a[1]) * fr,
                a[2] + (c[2] - a[2]) * fr
            )
        }

        // 沿 g 插值
        fun lerpG(b: Int): FloatArray {
            val a = lerpR(g0, b)
            val c = lerpR(g1, b)
            return floatArrayOf(
                a[0] + (c[0] - a[0]) * fg,
                a[1] + (c[1] - a[1]) * fg,
                a[2] + (c[2] - a[2]) * fg
            )
        }

        // 沿 b 插值
        val a = lerpG(b0)
        val c = lerpG(b1)
        return floatArrayOf(
            a[0] + (c[0] - a[0]) * fb,
            a[1] + (c[1] - a[1]) * fb,
            a[2] + (c[2] - a[2]) * fb
        )
    }
}
