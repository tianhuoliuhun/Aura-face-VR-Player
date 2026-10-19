package com.example.vr

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * v2.6.4：**性能球**（CPU 占用 / 电池温度 / 实时帧率）。
 *
 * ## 为什么把它做成悬浮球
 * 播放 4K/HEVC 或跑 AI 弹幕时，设备发热与掉帧是"看得见但说不准"的问题 ——
 * 用户只能凭感觉。把三个关键指标贴在屏幕一角，调画质/引擎/倍速时能立刻看到代价。
 *
 * ## 数据来源（都不用权限）
 *  - **CPU**：读 `/proc/stat` 首行，取**两次采样的差值**算使用率。
 *    注意必须用差値：`/proc/stat` 的计数器是开机以来的累计值，直接除会得到"开机至今平均"。
 *  - **温度**：`BatteryManager.EXTRA_TEMPERATURE`（电池温度，单位 0.1°C）。
 *    不用 `/sys/class/thermal` —— 那些节点在不同 SoC 上路径与含义不统一，且部分需权限。
 *  - **帧率**：`Choreographer` 帧回调计数，1 秒窗口。
 *
 * ## 交互
 *  - **单击** = 切换显示模式：三项全显 ↔ 只显帧率（只看掉帧时用）
 *  - **双击** = 播报当前数值
 */
object PerfSampler {

    /** CPU 计数器快照（/proc/stat 首行的聚合值） */
    data class CpuTimes(val idle: Long, val total: Long)

    /**
     * 解析 `/proc/stat` 首行：`cpu  user nice system idle iowait irq softirq steal ...`
     * 返回 null 表示格式不认识（调用方应保持上一次的采样）。
     */
    fun parseCpuLine(line: String): CpuTimes? {
        val p = line.trim().split(Regex("\\s+"))
        if (p.size < 5 || p[0] != "cpu") return null
        val nums = p.drop(1).mapNotNull { it.toLongOrNull() }
        if (nums.size < 4) return null
        // idle = idle + iowait（iowait 也是"没在算"的时间，漏算会让使用率偏高）
        val idle = nums[3] + (nums.getOrNull(4) ?: 0L)
        return CpuTimes(idle = idle, total = nums.sum())
    }

    /** 用两次采样差算使用率百分比；total 差为 0（间隔太短）时返回 null */
    fun usagePercent(prev: CpuTimes, cur: CpuTimes): Int? {
        val dTotal = cur.total - prev.total
        val dIdle = cur.idle - prev.idle
        if (dTotal <= 0L) return null
        val used = (dTotal - dIdle).coerceAtLeast(0L)
        return ((used * 100f / dTotal).roundToInt())
    }

    /** 读 /proc/stat 首行；IO 失败返回 null */
    suspend fun readCpuTimes(): CpuTimes? = withContext(Dispatchers.IO) {
        runCatching {
            java.io.File("/proc/stat").useLines { seq ->
                seq.firstOrNull()?.let { parseCpuLine(it) }
            }
        }.getOrNull()
    }

    /**
     * 电池温度（℃）。取不到返回 null。
     *
     * ⚠️ `BatteryManager` **没有** `BATTERY_PROPERTY_TEMPERATURE` 这个属性
     * （它只有 CURRENT_NOW / CAPACITY / CHARGE_COUNTER / ENERGY_COUNTER）——
     * 温度只能从 `ACTION_BATTERY_CHANGED` 的 sticky 广播里取 `EXTRA_TEMPERATURE`（单位 0.1℃），
     * 与 InfoFloatingBall 读电量的方式一致。
     */
    fun batteryTempC(context: Context): Float? {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null
        val raw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        // 0 常见于「未上报」，当作无数据，避免显示 0.0°C 造成误导
        return if (raw != Int.MIN_VALUE && raw > 0) raw / 10f else null
    }
}

@Composable
fun PerfFloatingBall(
    maxX: Float,
    maxY: Float,
    accentColor: Color,
    accentOnColor: Color,
    onFeedback: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialXRatio: Float = 0f,
    initialYRatio: Float = 0.26f,
    isLiquidGlass: Boolean = false,
    glassModifier: Modifier = Modifier,
    prefs: SharedPreferences? = null,
    ballId: String = "",
    rememberPosition: Boolean = false,
) {
    val context = LocalContext.current
    val persistPosition = rememberPosition && prefs != null && ballId.isNotEmpty()
    var pressed by remember { mutableStateOf(false) }

    var xRatio by remember { mutableFloatStateOf(Float.NaN) }
    var yRatio by remember { mutableFloatStateOf(Float.NaN) }

    LaunchedEffect(maxX, maxY) {
        if (xRatio.isNaN() && maxX > 0f && maxY > 0f) {
            val sx = if (persistPosition) FloatingBallPositions.loadX(prefs!!, ballId) else Float.NaN
            val sy = if (persistPosition) FloatingBallPositions.loadY(prefs!!, ballId) else Float.NaN
            xRatio = (if (!sx.isNaN()) sx else initialXRatio).coerceIn(0f, 1f)
            yRatio = (if (!sy.isNaN()) sy else initialYRatio).coerceIn(0f, 1f)
        }
    }

    // ---------------- 采样状态 ----------------
    var cpuPct by remember { mutableIntStateOf(-1) }        // -1 = 尚无数据
    var tempC by remember { mutableStateOf<Float?>(null) }
    var fps by remember { mutableIntStateOf(0) }
    var fpsOnly by remember { mutableStateOf(false) }       // 单击切换

    // CPU：每秒取一次差值
    LaunchedEffect(Unit) {
        var prev = PerfSampler.readCpuTimes()
        while (true) {
            delay(1000)
            val cur = PerfSampler.readCpuTimes() ?: continue
            if (prev != null) {
                PerfSampler.usagePercent(prev, cur)?.let { cpuPct = it }
            }
            prev = cur
        }
    }

    // 温度：5 秒一次（变化慢，省点电）
    LaunchedEffect(Unit) {
        while (true) {
            tempC = PerfSampler.batteryTempC(context)
            delay(5000)
        }
    }

    // 帧率：Choreographer 主线程回调，1 秒窗口
    DisposableEffect(Unit) {
        val choreographer = android.view.Choreographer.getInstance()
        var count = 0
        var windowStart = android.os.SystemClock.elapsedRealtime()
        val cb = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                count++
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - windowStart >= 1000) {
                    fps = count
                    count = 0
                    windowStart = now
                }
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(cb)
        onDispose { choreographer.removeFrameCallback(cb) }
    }

    // HUD 文案在语句位置取好 —— @Composable 调用不能塞进手势 lambda
    val hudAll = stringResource(R.string.perf_ball_hud_all)
    val hudFpsOnly = stringResource(R.string.perf_ball_hud_fps_only)
    val hudReport = stringResource(
        R.string.perf_ball_hud_report,
        if (cpuPct >= 0) cpuPct.toString() else "-",
        tempC?.let { String.format("%.1f", it) } ?: "-",
        fps.toString()
    )

    val ballDp = 54.dp

    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    x = InfoBallGeometry.pixel(xRatio, maxX).roundToInt(),
                    y = InfoBallGeometry.pixel(yRatio, maxY).roundToInt(),
                )
            }
            .size(ballDp)
            .shadow(
                elevation = if (pressed) 12.dp else 6.dp,
                shape = CircleShape,
                spotColor = if (pressed) accentColor else Color.Black,
            )
            .background(
                brush = if (isLiquidGlass) {
                    Brush.radialGradient(colors = listOf(Color.Transparent, Color.Transparent))
                } else if (pressed) {
                    Brush.radialGradient(colors = listOf(accentColor, Color(0xFF9A82DB)))
                } else {
                    Brush.radialGradient(colors = listOf(Color(0xEE2A2733), Color(0xDD18171C)))
                },
                shape = CircleShape,
            )
            .then(glassModifier)
            .border(
                width = if (pressed) 2.dp else 1.5.dp,
                brush = if (pressed) {
                    SolidColor(Color.White)
                } else {
                    Brush.linearGradient(colors = listOf(Color(0x99D0BCFF), Color(0x33FFFFFF)))
                },
                shape = CircleShape,
            )
            .floatingBallGesture(
                maxX = maxX,
                maxY = maxY,
                offsetX = { InfoBallGeometry.pixel(xRatio, maxX) },
                offsetY = { InfoBallGeometry.pixel(yRatio, maxY) },
                onOffset = { x, y ->
                    if (maxX > 0f) xRatio = InfoBallGeometry.ratio(x, maxX)
                    if (maxY > 0f) yRatio = InfoBallGeometry.ratio(y, maxY)
                },
                onPressed = { pressed = it },
                onDragEnd = {
                    if (persistPosition && !xRatio.isNaN() && !yRatio.isNaN()) {
                        FloatingBallPositions.save(prefs!!, ballId, xRatio, yRatio)
                    }
                },
                onSingleTap = {
                    fpsOnly = !fpsOnly
                    onFeedback(if (fpsOnly) hudFpsOnly else hudAll)
                },
                onDoubleTap = { onFeedback(hudReport) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (fpsOnly) {
                Text(
                    text = fps.toString(),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.perf_ball_fps_unit),
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 8.sp
                )
            } else {
                Text(
                    text = if (cpuPct >= 0) "$cpuPct%" else "--",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = tempC?.let { String.format("%.1f°C", it) } ?: "--°C",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "$fps fps",
                    color = accentColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
