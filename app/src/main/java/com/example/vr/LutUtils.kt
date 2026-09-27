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
        BuiltinLut("12_High_Contrast", R.string.lut_high_contrast),
        // ===== v2.0.178：6 款「人像美颜向」LUT =====
        // 上面 12 款是**风格化**滤镜（青橙 / 赛博朋克 / 黑白…），不适合当美颜用；
        // 这 6 款专做「柔和 / 通透 / 肤色友好」，与磨皮美白叠加时不会互相打架。
        //
        // 来源：开源项目 t0saki/lumix-original-looks（GitHub），**MIT 许可**，
        //       `.cube` 文件可自由使用（含商用，注明来源为佳）。
        //       上游为 LUMIX Real Time LUT 的 33 点 look，在 OKLab/OKLCh 空间参数化生成，
        //       全套共用「肤色保护窗」（OKLCh 色相 30–70° 羽化），因此人脸在所有款下表现一致；
        //       生成是确定性的，白点严格映射到 [1,1,1]、灰轴严格单调 ⇒ 不会丢高光层次。
        //       我们对上游文件仅做**格式规范化**（重写 TITLE、统一 CRLF、补齐 6 位小数），
        //       数值数据逐条原样保留，未做任何色调改动。
        // 上游仓库：https://github.com/t0saki/lumix-original-looks  （LICENSE: MIT）
        BuiltinLut("19_Heartland_Warm_Portrait", R.string.lut_heartland_warm_portrait),
        BuiltinLut("20_Meridian_Bright_Daylight", R.string.lut_meridian_bright_daylight),
        BuiltinLut("21_Skylight_Clear_Open", R.string.lut_skylight_clear_open),
        BuiltinLut("22_Postcard_Pure_Hue", R.string.lut_postcard_pure_hue),
        BuiltinLut("23_Almond_Soft_Skin", R.string.lut_almond_soft_skin),
        BuiltinLut("24_Burin_Subtle_Film", R.string.lut_burin_subtle_film),

        // ===== v2.0.179：18 款「人像美颜 / 美白」向 LUT（与上面 6 款同属「人像美颜」分组）=====
        // 25~30：柯达经典胶片仿真，来自 scernst13/HaldCLUT-Cube-Files（GitHub），**CC0-1.0**
        //         （公共领域贡献，可自由商用；原始 HaldCLUT 出自 darktable/RT 社区，Portra 系列以
        //          柔和肤色、低反差、暖调著称，是公认的人像胶片），cube 由 LUT Lab 转换。
        // 31~42：LUMIX 原创 look，来自 t0saki/lumix-original-looks（GitHub），**MIT**，
        //         与 19~24 同一上游，主打通透 / 柔雾 / 明亮，肤色友好。
        // 两个来源均只做**格式规范化**（重写 TITLE、统一 CRLF、数据保留 6 位小数），
        // 数值逐条原样保留，未做任何色调改动。
        // CC0 上游：https://github.com/scernst13/HaldCLUT-Cube-Files
        // MIT 上游：https://github.com/t0saki/lumix-original-looks
        BuiltinLut("25_Portra_400", R.string.lut_portra_400),
        BuiltinLut("26_Portra_160", R.string.lut_portra_160),
        BuiltinLut("27_Portra_800", R.string.lut_portra_800),
        BuiltinLut("28_Ektachrome_100VS", R.string.lut_ektachrome_100vs),
        BuiltinLut("29_Elite_Color_400", R.string.lut_elite_color_400),
        BuiltinLut("30_Kodachrome_64", R.string.lut_kodachrome_64),
        BuiltinLut("31_Glaze", R.string.lut_glaze),
        BuiltinLut("32_Gilt", R.string.lut_gilt),
        BuiltinLut("33_Viride", R.string.lut_viride),
        BuiltinLut("34_Clear", R.string.lut_clear),
        BuiltinLut("35_Voile", R.string.lut_voile),
        BuiltinLut("36_Arcade", R.string.lut_arcade),
        BuiltinLut("37_Tinsel", R.string.lut_tinsel),
        BuiltinLut("38_Splice", R.string.lut_splice),
        BuiltinLut("39_Sodium", R.string.lut_sodium),
        BuiltinLut("40_Argent", R.string.lut_argent),
        BuiltinLut("41_Canopy", R.string.lut_canopy),
        BuiltinLut("42_Dusk_Tide", R.string.lut_dusk_tide)
    )

    /** 风格化滤镜分组（青橙 / 赛博朋克 / 黑白…），不针对人像肤色优化 */
    val styleLuts: List<BuiltinLut> = builtinLuts.filter {
        it.fileName.substringBefore('_').toIntOrNull()?.let { n -> n in 1..12 } == true
    }

    /** 人像美颜分组（胶片仿真 / 通透柔肤 / 暖调肤色），适合与磨皮美白叠加 */
    val portraitLuts: List<BuiltinLut> = builtinLuts.filter {
        it.fileName.substringBefore('_').toIntOrNull()?.let { n -> n >= 19 } == true
    }

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
