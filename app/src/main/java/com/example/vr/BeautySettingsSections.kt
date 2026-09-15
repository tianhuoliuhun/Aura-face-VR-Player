package com.example.vr

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v119 拆分：从 VRPlayerScreen.kt 设置面板右列抽出的三大美颜区块。
 *
 * 抽离原则：
 * - 这些区块原本是主函数里的**内联 UI**（共约 230 行），直接读写主函数的几十个 `by remember`
 *   状态；外置后改为**单向数据流**：值由参数传入，修改通过回调上抛，由调用方统一写回状态。
 * - 组件内不持有业务状态，只持有纯 UI 状态（如 LUT 的加载中标志也由外部提供）。
 * - 头像精修的 10 个同构滑杆用 [PortraitParam] 列表描述，避免 10 组重复参数。
 */

// ===================== 通用美颜 =====================

/**
 * 通用美颜（磨皮 / 曝光 / 对比度 / 美白），2D 与 3D 模式均生效。
 *
 * 四个滑杆的行为一致：改值 → 预设置为「自定义」→ 点亮 UI 防自动隐藏，
 * 因此这里只暴露 `onXxxChange`，由调用方统一附加上述副作用。
 */
@Composable
fun GeneralBeautySection(
    accentColor: Color,
    beautyLevel: Float,
    onBeautyLevelChange: (Float) -> Unit,
    brightnessLevel: Float,
    onBrightnessLevelChange: (Float) -> Unit,
    contrastLevel: Float,
    onContrastLevelChange: (Float) -> Unit,
    whiteningLevel: Float,
    onWhiteningLevelChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SectionTitle(stringResource(R.string.beauty_section_common), accentColor)

        BeautySliderItem(
            stringResource(R.string.beauty_smooth), beautyLevel, onBeautyLevelChange, accentColor = accentColor
        )

        // 曝光：值域 -0.3~0.3，显示带正负号，故未复用 BeautySliderItem
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.beauty_brightness), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                Text(
                    if (brightnessLevel >= 0) "+${(brightnessLevel * 100).toInt()}" else "${(brightnessLevel * 100).toInt()}",
                    color = accentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = brightnessLevel,
                onValueChange = onBrightnessLevelChange,
                valueRange = -0.3f..0.3f,
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                ),
                modifier = Modifier.height(26.dp)
            )
        }

        // 对比度：值域 0.7~1.3（以 1.0 为中性），显示为百分比
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.beauty_contrast), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                Text("${(contrastLevel * 100).toInt()}%", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = contrastLevel,
                onValueChange = onContrastLevelChange,
                valueRange = 0.7f..1.3f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                ),
                modifier = Modifier.height(26.dp)
            )
        }

        BeautySliderItem(stringResource(R.string.beauty_whitening), whiteningLevel, onWhiteningLevelChange, accentColor = accentColor)
    }
}

// ===================== LUT 视频滤镜 =====================

/**
 * LUT 视频滤镜（内置 12 款 + 手机自选 .cube）。
 *
 * @param onApplyLutRgba 把解析出的 RGBA LUT 交给渲染器（null 表示关闭滤镜）
 * @param onPickCustomLut 打开系统文件选择器（调用方持有 ActivityResultLauncher）
 * @param onUserInteraction 用户操作后点亮 UI，防止面板自动隐藏
 */
@Composable
fun LutFilterSection(
    accentColor: Color,
    lutName: String,
    onLutNameChange: (String) -> Unit,
    lutMix: Float,
    onLutMixChange: (Float) -> Unit,
    isLutLoading: Boolean,
    onLutLoadingChange: (Boolean) -> Unit,
    onApplyLutRgba: (ByteArray?) -> Unit,
    onPickCustomLut: () -> Unit,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context: Context = LocalContext.current
    val scope = rememberCoroutineScope()

    val ctx = LocalContext.current
    Column(modifier = modifier) {
        SectionTitle(stringResource(R.string.lut_section_title), accentColor)

        // 内置 LUT 横向选择（无滤镜 + 12 款风格）
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                val selected = lutName == stringResource(R.string.lut_none)
                LutChip(
                    text = stringResource(R.string.lut_none),
                    selected = selected,
                    accentColor = accentColor,
                    onClick = {
                        onLutNameChange(ctx.getString(R.string.lut_none))
                        onApplyLutRgba(null)
                        onLutMixChange(0f)
                        onUserInteraction()
                    }
                )
            }
            items(LutUtils.builtinLuts) { (file, cnName) ->
                val selected = lutName == cnName
                LutChip(
                    text = cnName,
                    selected = selected,
                    accentColor = accentColor,
                    onClick = {
                        if (isLutLoading) return@LutChip
                        onLutLoadingChange(true)
                        onLutNameChange(cnName)
                        scope.launch(Dispatchers.IO) {
                            try {
                                val rgba = context.assets.open("luts/$file.cube").use {
                                    LutUtils.parseCubeToRgba(it)
                                }
                                withContext(Dispatchers.Main) {
                                    onApplyLutRgba(rgba)
                                    onLutMixChange(lutMix)
                                }
                            } catch (e: Exception) {
                                Log.e("LutFilterSection", "LUT $file load failed", e)
                            } finally {
                                withContext(Dispatchers.Main) { onLutLoadingChange(false) }
                            }
                        }
                        onUserInteraction()
                    }
                )
            }
        }

        // 强度滑块 + 手机自选按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.lut_intensity), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                    Text("${(lutMix * 100).toInt()}%", color = accentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                Slider(
                    value = lutMix,
                    onValueChange = onLutMixChange,
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = accentColor,
                        activeTrackColor = accentColor,
                        inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.height(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Surface(
                color = if (isLutLoading) Color.White.copy(alpha = 0.10f) else accentColor,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.clickable(enabled = !isLutLoading) {
                    onPickCustomLut()
                    onUserInteraction()
                }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    if (isLutLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = accentColor
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = Color(0xFF1A1A2E),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isLutLoading) stringResource(R.string.loading) else stringResource(R.string.lut_custom_pick),
                        color = Color(0xFF1A1A2E),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.lut_current, lutName),
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 9.sp
        )
    }
}

@Composable
private fun LutChip(
    text: String,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) accentColor else Color.White.copy(alpha = 0.10f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            color = if (selected) Color(0xFF1A1A2E) else Color.White.copy(alpha = 0.85f),
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

// ===================== 2D 人像精修 =====================

/** 人像精修单项滑杆的描述（标签 + 当前值 + 回写） */
data class PortraitParam(
    val label: String,
    val value: Float,
    val onChange: (Float) -> Unit
)

/**
 * 2D 人像精修（瘦脸 / 大眼 / 瘦鼻 … 共 10 项），仅 2D 模式且检测到人脸时生效。
 *
 * @param enabled false 时全部滑杆置灰并显示「3D 停用」角标
 */
@Composable
fun PortraitRetouchSection(
    accentColor: Color,
    enabled: Boolean,
    params: List<PortraitParam>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SectionTitle(stringResource(R.string.beauty_section_portrait), accentColor)
        params.forEach { p ->
            BeautySliderItem(
                label = p.label,
                value = p.value,
                onValueChange = p.onChange,
                accentColor = accentColor,
                enabled = enabled,
                badge = if (enabled) null else stringResource(R.string.beauty_3d_disabled)
            )
        }
    }
}

// ===================== 公共小件 =====================

@Composable
internal fun SectionTitle(text: String, accentColor: Color) {
    Text(
        text = text,
        color = accentColor,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold
    )
}

// ===================== 模式提示 / 对比原图 / 美颜预设 =====================

/**
 * 2D/3D 模式提示条：告诉用户当前模式下哪些美颜可用。
 *
 * 只传 [is2DMode] 与 [modeName] 而不传 ProjectionMode，避免这个通用组件反向依赖播放器的投影枚举。
 */
@Composable
fun BeautyModeHintBar(
    is2DMode: Boolean,
    modeName: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = if (is2DMode) Color(0xFF1B4D2E) else Color(0xFF4D331B),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = if (is2DMode)
                stringResource(R.string.beauty_status_2d, modeName)
            else
                stringResource(R.string.beauty_status_3d, modeName),
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 9.sp,
            lineHeight = 12.sp,
            modifier = Modifier.padding(8.dp)
        )
    }
}

/** 对比原图开关：开启后临时关闭全部美颜 */
@Composable
fun BeautyCompareSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accentColor: Color,
    accentOnColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.beauty_compare_original), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.beauty_compare_desc), color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = accentOnColor,
                checkedTrackColor = accentColor,
                uncheckedThumbColor = Color.White.copy(alpha = 0.7f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
            ),
            modifier = Modifier.height(26.dp)
        )
    }
}

/** 美颜预设：自然 / 淡妆 / 浓妆 / 自定义 */
@Composable
fun BeautyPresetRow(
    preset: String,
    onPresetChange: (String) -> Unit,
    accentColor: Color,
    presets: List<String> = listOf(stringResource(R.string.beauty_preset_natural), stringResource(R.string.beauty_preset_light), stringResource(R.string.beauty_preset_heavy), stringResource(R.string.beauty_preset_custom)),
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        presets.forEach { p ->
            Surface(
                color = if (preset == p) accentColor else Color.White.copy(alpha = 0.10f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .clickable { onPresetChange(p) }
            ) {
                Text(
                    text = p,
                    textAlign = TextAlign.Center,
                    color = if (preset == p) Color(0xFF1A1A2E) else Color.White.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    fontWeight = if (preset == p) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(vertical = 5.dp).fillMaxWidth()
                )
            }
        }
    }
}
