package com.example.vr

import android.content.BroadcastReceiver
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.core.content.ContextCompat
// ⚠️ R 类在 com.example 包，本文件在 com.example.vr —— 必须显式 import（同包才可省略）
import com.example.R
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/** 信息球种类 —— 新增一种只加一个枚举值 + 一个 `when` 分支，**不要复制一个新组件** */
enum class InfoBallKind {
    /** 时钟 */
    CLOCK,

    /** 电量 */
    BATTERY,
}

/**
 * 信息球的**纯格式化逻辑**（不含任何 Compose / Android 依赖，可直接单测）。
 *
 * 抽出来单独放的理由：时间/电量的显示规则有明显的边界情况（12 小时制的 0 点 = 12、
 * 电量未知时不能显示 `-1%`、温度缺失时不能显示 `NaN°`），放在 Composable 里只能靠肉眼验证。
 */
object InfoBallFormat {

    /**
     * 时钟的主行 + 副行文案。
     *
     * @param hourOfDay 0..23
     * @return `main` 如 `14:23` / `2:23:45`；`sub` 为 `AM`/`PM`（12 小时制）或 `24H`
     */
    fun clock(
        hourOfDay: Int,
        minute: Int,
        second: Int,
        hour12: Boolean,
        showSeconds: Boolean,
    ): Pair<String, String> {
        val main = if (hour12) {
            // 12 小时制：0 点显示 12，13 点显示 1
            val h = hourOfDay % 12
            val hh = if (h == 0) 12 else h
            if (showSeconds) {
                String.format(Locale.US, "%d:%02d:%02d", hh, minute, second)
            } else {
                String.format(Locale.US, "%d:%02d", hh, minute)
            }
        } else {
            if (showSeconds) {
                String.format(Locale.US, "%02d:%02d:%02d", hourOfDay, minute, second)
            } else {
                String.format(Locale.US, "%02d:%02d", hourOfDay, minute)
            }
        }
        val sub = if (hour12) {
            if (hourOfDay < 12) "AM" else "PM"
        } else {
            "24H"
        }
        return main to sub
    }

    /** 电量主行：`85%`，或电压模式下的 `4.05V`；未知时用占位而不是负数 */
    fun batteryMain(pct: Int, voltV: Float, showVolt: Boolean): String {
        if (showVolt) {
            return if (voltV.isNaN()) "--V" else String.format(Locale.US, "%.2fV", voltV)
        }
        return if (pct < 0) "--%" else String.format(Locale.US, "%d%%", pct)
    }

    /** 电量副行：充电时带闪电符号，温度未知则只留符号 */
    fun batterySub(charging: Boolean, tempC: Float): String {
        val t = if (tempC.isNaN()) "" else String.format(Locale.US, "%.0f°", tempC)
        return when {
            charging && t.isEmpty() -> "⚡"
            charging -> "⚡$t"
            else -> t
        }
    }

    /** 低电量告警阈值（含）：主行数字变红 */
    fun isLowBattery(pct: Int): Boolean = pct in 0..15
}

/**
 * 悬浮球位置在「比例 ↔ 像素」之间的换算（纯函数，可单测）。
 *
 * 为什么单独抽出来：这一层要同时防住两种会让球"跑飞"的情况 ——
 * 1. 容器尚未测量完时 `max = 0`（除零 → NaN 位置 → 球消失）；
 * 2. 恢复存档/初始化前比例是 `NaN`（直接乘会得到 NaN）。
 */
object InfoBallGeometry {

    /** 像素 → 比例（0..1）。`max <= 0` 时返回 0，绝不产生除零。 */
    fun ratio(pixel: Float, max: Float): Float =
        if (max <= 0f) 0f else (pixel / max).coerceIn(0f, 1f)

    /** 比例 → 像素。比例为 NaN（未初始化）或 `max <= 0` 时返回 0。 */
    fun pixel(ratio: Float, max: Float): Float =
        if (ratio.isNaN() || max <= 0f) 0f else (ratio * max).coerceIn(0f, max)
}

/** 一次电量快照（不可变，便于放进单一 `mutableStateOf` 里整体替换） */
private data class BatterySnapshot(
    val pct: Int = -1,
    val charging: Boolean = false,
    val tempC: Float = Float.NaN,
    val voltV: Float = Float.NaN,
)

/**
 * 时间 / 电量**信息悬浮球**。
 *
 * 与其它悬浮球（加速球 / 快进 / 后退 / 时间标记球）**完全共用同一套底层**：
 * - 拖动 + 单击/双击判定 → [Modifier.floatingBallGesture]（带边界钳制与拖动抑制）
 * - 位置记忆 → [FloatingBallPositions]（存 0~1 比例，适配横竖屏/分屏）
 * - 生命周期 → 挂在播放页最外层 Box 里，随组合销毁；**不申请任何系统权限**
 *   （本项目悬浮球都是 Compose 内绘制，不是 `TYPE_APPLICATION_OVERLAY` 系统悬浮窗）
 *
 * 与它们唯一的差别是**显示内容**：本组件按 [kind] 绘制时间或电量。
 *
 * 数据更新方式：
 * - 时钟：`LaunchedEffect` 内循环，显示秒时对齐到下一秒、不显示秒时**对齐到下一整分**
 *   （既不丢精度也不白白每秒重组）。
 * - 电量：`DisposableEffect` 注册 `ACTION_BATTERY_CHANGED`（系统广播，电量变化才发），
 *   `onDispose` 注销 —— 页面退出即释放，不留监听。
 *
 * 交互：
 * | 球 | 单击 | 双击 |
 * |---|---|---|
 * | 时钟 | 12 / 24 小时制切换 | 显示 / 隐藏秒 |
 * | 电量 | 立即刷新一次 | 百分比 ↔ 电压切换 |
 *
 * @param initialXRatio 初始横向位置比例（0 = 贴左边缘，1 = 贴右边缘）
 * @param prefs 位置存档用的 SharedPreferences（null = 不记忆位置）
 * @param ballId 位置存档 id（[FloatingBallPositions.CLOCK] / [FloatingBallPositions.BATTERY]）
 * @param rememberPosition 是否记忆位置（= 用户开启了「设置记忆」）
 */
@Composable
fun InfoFloatingBall(
    kind: InfoBallKind,
    maxX: Float,
    maxY: Float,
    accentColor: Color,
    accentOnColor: Color,
    onFeedback: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialXRatio: Float = 0.5f,
    initialYRatio: Float = 0.10f,
    isLiquidGlass: Boolean = false,
    glassModifier: Modifier = Modifier,
    prefs: SharedPreferences? = null,
    ballId: String = "",
    rememberPosition: Boolean = false,
) {
    val context = LocalContext.current
    // 位置存档：prefs + id + 记忆开关三者齐备才读写（与 SeekFloatingBall / MarkerFloatingBall 一致）
    val persistPosition = rememberPosition && prefs != null && ballId.isNotEmpty()

    var pressed by remember { mutableStateOf(false) }

    // 位置用**比例**（0..1）持有，而不是像素 —— 与其它悬浮球的差别就在这里，且是有意的：
    // 其它球把像素 offset 在首次布局时定下来，之后不再换算；一旦容器尺寸再变（横竖屏、
    // 分屏、页面切换导致 maxX/maxY 与首次不同），球在视觉上就会偏离当初拖到的相对位置。
    // 用比例持有后 `offset = 比例 × 当前 maxX/maxY`，尺寸怎么变都保持相对位置。
    // 存档单位（FloatingBallPositions 存的就是 0~1 比例）也因此完全一致，无需来回换算。
    // NaN = 尚未初始化（此时不绘制偏移，等 LaunchedEffect 读到尺寸再定）。
    var xRatio by remember { mutableFloatStateOf(Float.NaN) }
    var yRatio by remember { mutableFloatStateOf(Float.NaN) }

    LaunchedEffect(maxX, maxY) {
        // 只初始化一次；比例与尺寸无关，所以首帧 maxX 偏小也不会把位置定歪
        if (xRatio.isNaN() && maxX > 0f && maxY > 0f) {
            val sx = if (persistPosition) FloatingBallPositions.loadX(prefs!!, ballId) else Float.NaN
            val sy = if (persistPosition) FloatingBallPositions.loadY(prefs!!, ballId) else Float.NaN
            if (!sx.isNaN() && !sy.isNaN()) {
                // 有存档 → 恢复用户上次拖到的比例
                xRatio = sx.coerceIn(0f, 1f)
                yRatio = sy.coerceIn(0f, 1f)
            } else {
                xRatio = initialXRatio.coerceIn(0f, 1f)
                yRatio = initialYRatio.coerceIn(0f, 1f)
            }
        }
    }

    // ---------------- 时钟 ----------------
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var hour12 by remember { mutableStateOf(false) }
    var showSeconds by remember { mutableStateOf(false) }

    if (kind == InfoBallKind.CLOCK) {
        LaunchedEffect(showSeconds) {
            while (true) {
                val now = System.currentTimeMillis()
                nowMs = now
                val wait = if (showSeconds) {
                    // 对齐到下一个整秒
                    1000L - (now % 1000L)
                } else {
                    // 对齐到下一个整分：分钟一跳就更新，且不每秒白白重组
                    val cal = Calendar.getInstance().apply { timeInMillis = now }
                    val toNextMinute = ((59 - cal.get(Calendar.SECOND)) * 1000L) +
                        (1000L - cal.get(Calendar.MILLISECOND))
                    toNextMinute.coerceIn(200L, 60_000L)
                }
                kotlinx.coroutines.delay(wait)
            }
        }
    }

    // ---------------- 电量 ----------------
    // 用一个不可变快照整体替换，避免 4 个独立 state 各触发一次重组
    var battery by remember { mutableStateOf(BatterySnapshot()) }
    var showVolt by remember { mutableStateOf(false) }
    // 双击「立即刷新」时自增，触发 LaunchedEffect 重读 sticky 广播
    var refreshTick by remember { mutableStateOf(0) }

    if (kind == InfoBallKind.BATTERY) {
        DisposableEffect(context) {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    battery = parseBattery(intent)
                }
            }
            // 先读一次 sticky 拿初始值（传 null 不注册 receiver，官方支持的读法，无泄漏）
            battery = parseBattery(context.registerReceiver(null, filter))
            // 系统广播用 NOT_EXPORTED 即可（其它应用无法伪造），退出时在 onDispose 注销
            runCatching {
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
            }
            onDispose {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
        // 手动刷新（双击）
        LaunchedEffect(refreshTick) {
            if (refreshTick > 0) {
                battery = parseBattery(
                    context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                )
            }
        }
    }

    // HUD 文案在语句位置取好 —— @Composable 调用不能塞进手势 lambda
    val hudClock12 = stringResource(R.string.info_ball_hud_clock_12h)
    val hudClock24 = stringResource(R.string.info_ball_hud_clock_24h)
    val hudSecOn = stringResource(R.string.info_ball_hud_clock_sec_on)
    val hudSecOff = stringResource(R.string.info_ball_hud_clock_sec_off)
    val hudRefreshed = stringResource(R.string.info_ball_hud_battery_refresh)
    val hudVolt = stringResource(R.string.info_ball_hud_battery_volt)
    val hudPct = stringResource(R.string.info_ball_hud_battery_pct)

    val ballDp = 54.dp

    Box(
        modifier = modifier
            .offset {
                // 比例 × 当前可用范围；maxX/maxY 变化（横竖屏、分屏、页面切换）时自动贴合
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
                // 与其它球完全一致的三种底色：玻璃 / 按下 / 常态
                brush = if (isLiquidGlass) {
                    Brush.radialGradient(colors = listOf(Color(0x66FFFFFF), Color(0x33FFFFFF)))
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
                // 手势层按**像素**工作，这里把比例换算成像素喂给它
                offsetX = { InfoBallGeometry.pixel(xRatio, maxX) },
                offsetY = { InfoBallGeometry.pixel(yRatio, maxY) },
                // 回写时换算回比例；maxX/maxY 为 0 时保持不变（避免除零把位置打回原点）
                onOffset = { x, y ->
                    if (maxX > 0f) xRatio = InfoBallGeometry.ratio(x, maxX)
                    if (maxY > 0f) yRatio = InfoBallGeometry.ratio(y, maxY)
                },
                onPressed = { pressed = it },
                onDragEnd = {
                    // 抬手即存 —— 存档单位就是比例，不需要再换算
                    if (persistPosition && !xRatio.isNaN() && !yRatio.isNaN()) {
                        FloatingBallPositions.save(prefs!!, ballId, xRatio, yRatio)
                    }
                },
                onSingleTap = {
                    when (kind) {
                        InfoBallKind.CLOCK -> {
                            hour12 = !hour12
                            onFeedback(if (hour12) hudClock12 else hudClock24)
                        }
                        InfoBallKind.BATTERY -> {
                            refreshTick++
                            onFeedback(hudRefreshed)
                        }
                    }
                },
                onDoubleTap = {
                    when (kind) {
                        InfoBallKind.CLOCK -> {
                            showSeconds = !showSeconds
                            onFeedback(if (showSeconds) hudSecOn else hudSecOff)
                        }
                        InfoBallKind.BATTERY -> {
                            showVolt = !showVolt
                            onFeedback(if (showVolt) hudVolt else hudPct)
                        }
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (kind) {
                InfoBallKind.CLOCK -> {
                    val cal = remember(nowMs, hour12, showSeconds) {
                        Calendar.getInstance().apply { timeInMillis = nowMs }
                    }
                    val (main, sub) = InfoBallFormat.clock(
                        hourOfDay = cal.get(Calendar.HOUR_OF_DAY),
                        minute = cal.get(Calendar.MINUTE),
                        second = cal.get(Calendar.SECOND),
                        hour12 = hour12,
                        showSeconds = showSeconds,
                    )
                    Text(
                        text = main,
                        color = if (pressed) accentOnColor else Color.White,
                        // 带秒时字符多，字号收小以免溢出 54dp
                        fontSize = if (showSeconds) 10.sp else 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = sub,
                        color = if (pressed) accentOnColor else accentColor,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }

                InfoBallKind.BATTERY -> {
                    val snap = battery
                    val main = InfoBallFormat.batteryMain(snap.pct, snap.voltV, showVolt)
                    val sub = InfoBallFormat.batterySub(snap.charging, snap.tempC)
                    val low = !showVolt && InfoBallFormat.isLowBattery(snap.pct)
                    Text(
                        text = main,
                        color = when {
                            pressed -> accentOnColor
                            low -> Color(0xFFFF6B6B)
                            else -> Color.White
                        },
                        fontSize = if (showVolt) 11.sp else 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                    if (sub.isNotEmpty()) {
                        Text(
                            text = sub,
                            color = if (pressed) accentOnColor else accentColor,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** 从 `ACTION_BATTERY_CHANGED` 的 Intent 解析出电量快照（Intent 可为 null → 返回未知快照） */
private fun parseBattery(intent: Intent?): BatterySnapshot {
    if (intent == null) return BatterySnapshot()
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
        status == BatteryManager.BATTERY_STATUS_FULL
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    val pct = if (level >= 0 && scale > 0) (level * 100f / scale).roundToInt() else -1
    // 温度单位是 0.1 摄氏度
    val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
    val tempC = if (tempTenths == Int.MIN_VALUE) Float.NaN else tempTenths / 10f
    // 电压单位 mV
    val voltMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
    val voltV = if (voltMv > 0) voltMv / 1000f else Float.NaN
    return BatterySnapshot(pct = pct, charging = charging, tempC = tempC, voltV = voltV)
}
