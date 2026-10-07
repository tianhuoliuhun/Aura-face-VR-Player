package com.example.vr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.SystemClock
import kotlin.math.max

/**
 * AI 弹幕渲染层（v2.4.0 / P5）
 *
 * ## 职责
 * 把 [DanmuEngine] 里排好队的弹幕**画到屏幕上**。引擎负责「哪条、哪一轨、什么时候出生」，
 * 本层只负责「按当前时间算出 x，画出来」。
 *
 * ## 为什么用 Canvas 而不是每弹幕一个 Text composable
 * 与 [SubtitledText] 同款理由：**一次测量 + 多次 drawText** 远比堆叠 composable 便宜。
 * 弹幕同屏可达几十条，若每条一个 composable，仅 layout 就会在 VR 分屏（一帧两遍）下崩掉帧率。
 * 这里对**每条弹幕只测量一次**（并按文本缓存 layout），之后每帧只做 `drawText`。
 *
 * ## 分屏 VR 的处理（与字幕层一致）
 * 分屏时用 `Row { Box(weight = 1f) x2 }` 把可用宽度**平分给两只眼**，
 * 于是每只眼各自是一个「宽度 = 整屏 / 2」的独立坐标系。
 * ⚠️ 引擎的 `screenWidthPx` 必须同步为**单眼宽**，否则弹幕会按整屏宽算位置 →
 *    左眼还看得见时右眼已经飘到屏幕外（或反之），表现为「弹幕跑到中间就消失 / 一半不动」。
 *    —— 这与美颜分屏踩过的坑同源（见 `beauty-dual-engine.md`）。
 *
 * ## 关掉时零开销
 * `enabled == false` 直接 `return`，不注册帧回调、不做任何测量。
 *
 * @param engine           弹幕引擎（数据来源）
 * @param config           弹幕配置（颜色/字号/不透明度/速度）
 * @param isSplitScreenVR  是否分屏 VR（决定单眼还是整屏坐标系）
 * @param areaHeightRatio  弹幕区高度占画面比例（顶部若干轨道，默认 0.30）
 */
@Composable
fun DanmuOverlay(
    engine: DanmuEngine,
    config: DanmuConfig,
    isSplitScreenVR: Boolean = false,
    areaHeightRatio: Float = DEFAULT_AREA_HEIGHT_RATIO,
    modifier: Modifier = Modifier
) {
    if (!config.isEnabled) return

    // 每帧重新读一次「现在几毫秒」，驱动弹幕移动。
    // ⚠️ 不能用 LaunchedEffect + delay(16) 自建定时器：那样与 VSync 不同步，
    //    会出现抖动；withFrameNanos 直接挂在合成器的帧回调上，天然对齐。
    var frameTimeMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { /* 仅用于等待下一帧 */ }
            val t = SystemClock.elapsedRealtime()
            frameTimeMs = t
            // ⚠️ prune 必须在这里（effect 的挂起点）调用，**不能放进 Canvas 的 draw 块**：
            //    draw 阶段应是纯绘制、无副作用；在绘制中删列表元素属于改状态，
            //    且会与同一帧正在进行的遍历打架（ConcurrentModification 风险）。
            engine.prune(t)
        }
    }

    // 引擎的时间基准与这里必须一致（都用 elapsedRealtime），否则位置会跳。
    val nowMs = frameTimeMs

    // 画面高度比例 → 实际 dp。用 BoxWithConstraints 拿到真实可用高度。
    val textColor = config.textColorOption.color
    val strokeOption = config.strokeOption
    val bgOption = config.bgOption
    val alpha = (config.opacityPercent.coerceIn(0, 100)) / 100f
    val fontSizeSp = config.fontSizeSp.coerceIn(MIN_FONT_SP, MAX_FONT_SP)

    // v2.4.6：把颜色模式与「单一颜色」同步给引擎 —— 逐条颜色在**入队时**决定，
    // 因此引擎需要知道用哪种模式、以及 SINGLE 模式下的颜色。
    val colorMode = config.colorMode
    // v2.4.7：批次时间抖动标准差（0 = 关闭抖动）。
    // ⚠️ 也必须同步给引擎 —— 它在 `enqueue` 时决定各条出生时刻，
    //    渲染层只是在每帧按 bornMs 算位置，改这里就等于改了"出来的节奏"。
    //    用 `timeJitterMsF` 做上下限收敛，避免脏 prefs 值把节奏搞乱。
    val timeJitterMs = config.timeJitterMsF
    LaunchedEffect(colorMode, textColor, timeJitterMs) {
        engine.colorMode = colorMode
        engine.singleColor = textColor
        engine.timeJitterMs = timeJitterMs
    }

    if (isSplitScreenVR) {
        // 分屏：左右眼各一份，各自宽度 = 整屏 / 2
        Row(modifier = modifier.fillMaxSize()) {
            DanmuEye(
                engine = engine,
                nowMs = nowMs,
                strokeColor = strokeOption.strokeColor,
                strokeWidthDp = strokeOption.widthDp,
                bgColor = bgOption.bgColor,
                bgAlpha = bgOption.alpha,
                alpha = alpha,
                fontSizeSp = fontSizeSp,
                areaHeightRatio = areaHeightRatio,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            DanmuEye(
                engine = engine,
                nowMs = nowMs,
                strokeColor = strokeOption.strokeColor,
                strokeWidthDp = strokeOption.widthDp,
                bgColor = bgOption.bgColor,
                bgAlpha = bgOption.alpha,
                alpha = alpha,
                fontSizeSp = fontSizeSp,
                areaHeightRatio = areaHeightRatio,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    } else {
        DanmuEye(
            engine = engine,
            nowMs = nowMs,
            strokeColor = strokeOption.strokeColor,
            strokeWidthDp = strokeOption.widthDp,
            bgColor = bgOption.bgColor,
            bgAlpha = bgOption.alpha,
            alpha = alpha,
            fontSizeSp = fontSizeSp,
            areaHeightRatio = areaHeightRatio,
            modifier = modifier.fillMaxSize()
        )
    }
}

/**
 * 单只眼的弹幕绘制。
 *
 * 坐标系：`x` 为距本眼**左边缘**的像素（与引擎约定一致），y 由轨道号推出。
 */
@Composable
private fun DanmuEye(
    engine: DanmuEngine,
    nowMs: Long,
    strokeColor: Color,
    strokeWidthDp: Float,
    bgColor: Color,
    bgAlpha: Float,
    alpha: Float,
    fontSizeSp: Int,
    areaHeightRatio: Float,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val textMeasurer: TextMeasurer = rememberTextMeasurer()
    val layoutCache = remember { DanmuLayoutCache() }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        if (widthPx <= 0f || heightPx <= 0f) return@BoxWithConstraints

        val strokeWidthPx = with(density) { strokeWidthDp.dp.toPx() }
        val fontSizePx = with(density) { fontSizeSp.sp.toPx() }
        val trackCount = engine.maxTracks.coerceAtLeast(1)

        // ===== v2.4.6：行距自适应（区域随内容长）=====
        // 公式抽到 DanmuEngine 的纯函数（可单测）—— 避免「同一份逻辑两处登记」。
        //
        // ## 与 v2.4.5 的根本区别
        // 旧：区域固定 30% 屏高 → 除以轨道数得行距（行距是**被动结果**，字号调大也变不了）
        // 新：**先按字号定理想行距 → 区域按需撑开**（区域是**主动跟随**）
        //
        // ⚠️ 为什么要改区域而不是只改行距公式：
        //    实测 1080×2400 / 密度 3x / 8 轨 / 18sp 时，固定 30% 区域只有 720px，
        //    均分 90px，而理想行距 18×3×1.9 = 102.6px **已超出** → 会被上限压回 90px，
        //    即「行距自适应」在**默认配置下完全感受不到**。区域必须跟着内容长才有效。
        // ⚠️ `areaHeightRatio` 参数现在只作为**下限基准**（兼容旧行为/外部传入），
        //    真正的上限由 DanmuEngine.AREA_HEIGHT_RATIO_MAX 控制。
        // 区域 = max(内容所需, 基准比例)，再用硬上限封顶。
        // ⚠️ 但若**基准比例本身**就超过硬上限（外部显式传入更大比例时），以基准为准 ——
        //    调用方显式指定的意图优先于内部上限，否则会出现「传了 0.6 却被压到 0.45」的意外。
        val contentAreaPx = trackCount * fontSizePx * DanmuEngine.TRACK_HEIGHT_FONT_FACTOR
        val baseAreaPx = heightPx * areaHeightRatio.coerceIn(0.10f, 1.0f)
        val capAreaPx = heightPx * DanmuEngine.AREA_HEIGHT_RATIO_MAX
        val areaHeightPx = if (baseAreaPx >= capAreaPx) {
            max(contentAreaPx, baseAreaPx)
        } else {
            max(contentAreaPx, baseAreaPx).coerceAtMost(capAreaPx)
        }
        val trackHeightPx = DanmuEngine.computeTrackHeightPx(
            fontSizePx = fontSizePx,
            areaHeightPx = areaHeightPx,
            trackCount = trackCount
        )

        // ⚠️ 同步给引擎：分屏时这里拿到的就是**单眼宽**，天然正确。
        //    轨道高度也在此同步（引擎的间距判据会用到）。
        LaunchedEffect(widthPx, areaHeightPx, engine.maxTracks, trackHeightPx) {
            engine.screenWidthPx = widthPx
            engine.trackHeightPx = trackHeightPx
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { areaHeightPx.toDp() })
        ) {
            // 弹幕只在顶部区域内飘，超出区域的部分裁掉（避免长文本压到字幕区）
            clipRect(left = 0f, top = 0f, right = size.width, bottom = size.height) {
                for (item in engine.snapshot()) {
                    val x = item.currentX(nowMs, widthPx, engine.speedPxPerSec)
                    // 整条已在右侧屏外（还没进来）或左侧屏外（已走完）→ 跳过
                    if (x > widthPx) continue
                    if (x + item.widthPx < 0f) continue

                    val top = item.track * trackHeightPx

                    // ===== v2.4.6：文字颜色逐条取 `item.color` =====
                    // 入队时已按颜色模式掷定并固定在条目上（见 DanmuItem.color），
                    // 这里只是把「本条的颜色 + 全局不透明度」组成实际绘制样式。
                    // ⚠️ 不透明度仍是**全局**的（opacityPercent），随机只作用于色相。
                    val itemStyle = textColorStyleFor(item.color, fontSizeSp, alpha)

                    // 每条只测量一次；宽度回填给引擎用于轨道避让
                    // ⚠️ 缓存 key 必须含颜色 —— 同一句话在不同颜色下是不同 layout，
                    //    否则先来的颜色会「传染」给后来的同文本条目（随机色会失效）。
                    val layout = layoutCache.measure(
                        textMeasurer = textMeasurer,
                        text = item.text,
                        style = itemStyle,
                        colorKey = item.color
                    )
                    if (item.widthPx <= 0f && layout.size.width > 0) {
                        item.widthPx = layout.size.width.toFloat()
                    }

                    val w = layout.size.width.toFloat()
                    val h = layout.size.height.toFloat()
                    // 垂直：在轨道内居中
                    val y = top + ((trackHeightPx - h) / 2f).coerceAtLeast(0f)

                    // 背景底：仅在选了非透明背景时画
                    if (bgAlpha > 0f) {
                        drawRect(
                            color = bgColor.copy(alpha = bgAlpha * alpha),
                            topLeft = Offset(x - PAD_X_PX, y - PAD_Y_PX),
                            size = Size(w + PAD_X_PX * 2f, h + PAD_Y_PX * 2f)
                        )
                    }

                    // 描边：8 方向偏移 + 中心填充（与 SubtitledText 同款做法）
                    val hasStroke = strokeWidthPx > 0f && strokeColor != Color.Transparent
                    if (hasStroke) {
                        val d = strokeWidthPx / 2f
                        for (o in DANMU_STROKE_OFFSETS) {
                            drawText(
                                textLayoutResult = layout,
                                topLeft = Offset(x + o.x * d, y + o.y * d),
                                color = strokeColor.copy(alpha = alpha)
                            )
                        }
                    }
                    drawText(textLayoutResult = layout, topLeft = Offset(x, y))
                }
            }
        }
    }
}

/**
 * 测量并缓存弹幕文本的 layout。
 *
 * 用简单的 LRU（容量 [LAYOUT_CACHE_SIZE]）避免每帧对同一条反复测量。
 * 弹幕文本很短、同屏条数有限，这个缓存足以把「每帧测量」降到接近零。
 *
 * ⚠️ 缓存是**每个绘制实例独立**的（由 `remember` 持有），不是全局单例 ——
 *    全局单例会在页面重进后残留旧字号/旧颜色的条目，也会在左右眼之间
 *    因 style 不同而反复互相淘汰。
 *
 * ⚠️ v2.4.6：key **必须包含本条的颜色**（[colorKey]）。
 *    随机模式下一句话可能以不同颜色多次出现（如「哈哈」出现两次、颜色不同），
 *    若 key 只含文本+字号，后一条会命中前一条的 layout（里面记着**前一条的颜色**），
 *    表现为「随机色时有时无 / 颜色串台」。
 */
private class DanmuLayoutCache {
    private val map = object : LinkedHashMap<String, TextLayoutResult>(
        LAYOUT_CACHE_SIZE, 0.75f, true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, TextLayoutResult>?
        ): Boolean = size > LAYOUT_CACHE_SIZE
    }

    fun measure(
        textMeasurer: TextMeasurer,
        text: String,
        style: TextStyle,
        colorKey: Color
    ): TextLayoutResult {
        // key 里带上字号与颜色，避免设置变更后拿到旧样式
        val key = "$text|${style.fontSize}|$colorKey"
        map[key]?.let { return it }
        val result = textMeasurer.measure(
            text = text,
            style = style,
            constraints = Constraints()  // 单行，不限宽
        )
        map[key] = result
        return result
    }
}

/**
 * v2.4.6：按「本条颜色 + 全局不透明度」构造绘制样式。
 *
 * ⚠️ 全局不透明度（[alpha]）与颜色是**两个正交维度**：随机只作用于色相，
 *    透明度始终由设置里的 opacityPercent 统一控制。
 */
private fun textColorStyleFor(color: Color, fontSizeSp: Int, alpha: Float): TextStyle =
    TextStyle(
        fontSize = fontSizeSp.sp,
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        color = color.copy(alpha = alpha)
    )

/** 默认弹幕区高度占比（顶部 30%） */
const val DEFAULT_AREA_HEIGHT_RATIO = 0.30f

private const val MIN_FONT_SP = 10
private const val MAX_FONT_SP = 40

/** 背景底内边距（px） */
private const val PAD_X_PX = 6f
private const val PAD_Y_PX = 2f

private const val LAYOUT_CACHE_SIZE = 256

/** 8 方向描边偏移（单位向量，实际偏移量再乘半个描边宽） */
private val DANMU_STROKE_OFFSETS = listOf(
    Offset(-1f, -1f), Offset(0f, -1f), Offset(1f, -1f),
    Offset(-1f, 0f), Offset(1f, 0f),
    Offset(-1f, 1f), Offset(0f, 1f), Offset(1f, 1f)
)
