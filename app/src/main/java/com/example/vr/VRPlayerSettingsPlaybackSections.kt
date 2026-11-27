package com.example.vr

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.remember
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

@Composable
internal fun PlayerSettingsDecoderSection(
    context: Context,
    accentColor: Color,
    accentOnColor: Color,
    decoderEngine: DecoderEngine,
    onDecoderEngineChange: (DecoderEngine) -> Unit,
    isSoftwareDecoding: Boolean,
    onSoftwareDecodingChange: (Boolean) -> Unit,
    maxFps: Int,
    onMaxFpsChange: (Int) -> Unit,
    ijkOptions: IjkOptions,
    onIjkOptionsChange: (IjkOptions) -> Unit,
    mpvOptions: MpvOptions,
    onMpvOptionsChange: (MpvOptions) -> Unit,
    currentVideoUri: Uri?,
    playerInstance: VrPlayerBackend?,
    renderer: VRGLRenderer?,
    onKeepUiAlight: () -> Unit,
) {
Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
        text = stringResource(R.string.settings_group_decoder),
        color = accentColor,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold
    )
    // 解码器切换 EXO/MPV
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.decoder_engine), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
        // v2.1.232：IJK 加入「预留位」。是否置灰**由枚举自己说了算**
        //（DecoderEngine.isImplemented）—— 将来接好了只需改那一处，UI 不用动。
        // v2.1.236：改成 filter 只展示**已启用**的内核 ——
        // 停用的（如 MPV，见 MPV_ENABLED）会**直接不出现**，
        // 而不是显示成置灰的「开发中」（那会让人以为功能坏了）。
        // ⚠️ 过滤依据仍是 isImplemented，所以启用/停用内核
        //    依然不需要动这里。
        DecoderEngine.values().filter { it.isImplemented }.forEach { engine ->
            // 过滤后恒为 false，保留是为了兼容下面依赖它的样式分支
            val isPlaceholder = !engine.isImplemented
            val isSelected = !isPlaceholder && decoderEngine == engine
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .background(
                            if (isSelected) accentColor else Color.White.copy(alpha = 0.05f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable(enabled = !isPlaceholder) {
                            onDecoderEngineChange(engine)
                            Toast.makeText(context, context.getString(R.string.toast_decoder_switched, context.getString(engine.labelRes)), Toast.LENGTH_SHORT).show()
                            onKeepUiAlight()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isPlaceholder) {
                            stringResource(engine.labelRes) + " · " + stringResource(R.string.decoder_coming_soon)
                        } else stringResource(engine.labelRes),
                        color = when {
                            isPlaceholder -> Color.White.copy(alpha = 0.35f)
                            isSelected -> accentOnColor
                            else -> Color.White
                        },
                        fontSize = if (isPlaceholder) 9.sp else 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
    // v2.1.232：**解码器参数（预留）** ——
    // 用户要求「设置里预留地方修改」。这里按当前选中的内核留出一块
    // 参数区：每个内核将来可以挂自己的调参项（缓冲/探测尺寸/硬解策略…）。
    // 未接通的内核显示说明文案，接好后把分支内容替换成真实控件即可。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = stringResource(R.string.decoder_params_section) +
                " · " + stringResource(decoderEngine.labelRes),
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (!decoderEngine.isImplemented) {
            Text(
                text = stringResource(R.string.decoder_params_placeholder),
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 8.sp,
                lineHeight = 11.sp
            )
        } else if (decoderEngine == DecoderEngine.IJK) {
            // v2.1.233：IJK 已接通 → 挂上真实调参项。
            // 改任何一项都会更新 ijkOptions state，而它是重建 effect 的 key，
            // 因此改完自动重建播放器并从原位置续播，无需「应用」按钮。
            IjkOptionsPanel(
                options = ijkOptions,
                onOptionsChange = { onIjkOptionsChange(it) },
                accentColor = accentColor,
                accentOnColor = accentOnColor,
                defaultBufferLabel = stringResource(R.string.ijk_buffer_default)
            )
        } else if (decoderEngine == DecoderEngine.MPV) {
            // v2.1.235：MPV 的 native 库不进 APK，
            // 所以先给一个"解码库"区块（未装时是下载入口）
            MpvLibPanel(
                context = context,
                accentColor = accentColor,
                accentOnColor = accentOnColor
            )
            // v2.1.234：MPV 参数（同样的机制：改完自动重建 + 续播）
            MpvOptionsPanel(
                options = mpvOptions,
                onOptionsChange = { onMpvOptionsChange(it) },
                accentColor = accentColor,
                accentOnColor = accentOnColor,
                defaultLabel = stringResource(R.string.ijk_buffer_default)
            )
        } else {
            // EXO 目前可调项已在下面「软件/硬件解码」等开关里，
            // 这里保留给将来与内核绑定的参数。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.decoder_coming_soon),
                    color = Color.White.copy(alpha = 0.35f),
                    fontSize = 8.sp
                )
                Text(
                    text = stringResource(decoderEngine.labelRes),
                    color = accentColor,
                    fontSize = 8.sp
                )
            }
        }
    }
    // v2.1.234：**视频信息** ——
    // 显示当前片源的容器/编码/分辨率/帧率/码率/音轨/实际解码方式。
    // 三个内核（Exo / IJK / MPV）各自能给的字段不同，统一由
    // VideoInfoPanel 内部走 currentVideoInfo() 分发，这里不需要判断内核。
    VideoInfoPanel(
        player = playerInstance,
        uri = currentVideoUri,
        isSoftwareDecoding = isSoftwareDecoding,
        accentColor = accentColor
    )
    // 软硬解码切换
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.decode_mode), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(false to stringResource(R.string.decode_hw), true to stringResource(R.string.decode_sw)).forEach { (isSw, label) ->
                val isSelected = isSoftwareDecoding == isSw
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .background(
                            if (isSelected) accentColor else Color.White.copy(alpha = 0.05f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable {
                            onSoftwareDecodingChange(isSw)
                            // v2.6.10：软件解码名也走资源（decode_sw），
                            // 原硬编码中文会让英日韩用户看到「软件解码」
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.toast_switched_codec,
                                    context.getString(
                                        if (isSw) R.string.decode_sw
                                        else R.string.info_hw_decode
                                    )
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                            onKeepUiAlight()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) accentOnColor else Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
    // 帧率限制 12/18/24/30/48/60/90/120
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.fps_limit), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
        val fpsList = listOf(0 to stringResource(R.string.no_limit), 12 to "12", 18 to "18", 24 to "24", 30 to "30", 48 to "48", 60 to "60", 90 to "90", 120 to "120")
        fpsList.chunked(5).forEach { rowFps ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                rowFps.forEach { (fpsVal, label) ->
                    val isSelected = maxFps == fpsVal
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                            .background(
                                if (isSelected) accentColor else Color.White.copy(alpha = 0.05f),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable {
                                onMaxFpsChange(fpsVal)
                                renderer?.maxFps = fpsVal
                                onKeepUiAlight()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) accentOnColor else Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                repeat(5 - rowFps.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

}

@Composable
internal fun PlayerSettingsEnhanceSection(
    accentColor: Color,
    accentOnColor: Color,
    isMemcEnabled: Boolean,
    onMemcEnabledChange: (Boolean) -> Unit,
    memcTargetFps: Int,
    onMemcTargetFpsChange: (Int) -> Unit,
    isFsrEnabled: Boolean,
    onFsrEnabledChange: (Boolean) -> Unit,
    fsrRuleMode: FsrRuleMode,
    onFsrRuleModeChange: (FsrRuleMode) -> Unit,
    fsrCustomTarget: FsrTargetResolution,
    onFsrCustomTargetChange: (FsrTargetResolution) -> Unit,
    videoSourceWidth: Int,
    videoSourceHeight: Int,
    renderer: VRGLRenderer?,
    onKeepUiAlight: () -> Unit,
) {
// FSR 的「当前判定结果」—— 与渲染侧用**同一个纯函数**算出来，
// 这样 UI 上显示的原因文案与 renderer 的实际行为永远不会打架。
val fsrDecision = remember(
    videoSourceWidth, videoSourceHeight, fsrRuleMode, fsrCustomTarget
) {
    VideoEnhanceRules.resolveFsr(
        videoSourceWidth, videoSourceHeight, fsrRuleMode, fsrCustomTarget
    )
}
Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
        text = stringResource(R.string.settings_group_enhance),
        color = accentColor,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold
    )
    // ===== MEMC 插帧 =====
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), shape = RoundedCornerShape(10.dp))
            .clickable { onMemcEnabledChange(!isMemcEnabled); onKeepUiAlight() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.memc_enabled), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(stringResource(R.string.memc_desc), color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        AuraSwitch(
            checked = isMemcEnabled,
            onCheckedChange = { onMemcEnabledChange(it); onKeepUiAlight() },
            accentColor = accentColor,
            accentOnColor = accentOnColor,
            modifier = Modifier.scale(0.8f)
        )
    }
    // 目标帧率：仅插帧打开时才显示（关闭时它没有任何作用对象）
    if (isMemcEnabled) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.memc_target_fps), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                VideoEnhanceRules.MEMC_TARGET_FPS_OPTIONS.forEach { fps ->
                    val isSelected = memcTargetFps == fps
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                            .background(
                                if (isSelected) accentColor else Color.White.copy(alpha = 0.05f),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable { onMemcTargetFpsChange(fps); onKeepUiAlight() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$fps",
                            color = if (isSelected) accentOnColor else Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
    // ===== FSR 超分 =====
    // 副标题直接显示「当前会怎样」，用户不用猜规则有没有生效
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.05f), shape = RoundedCornerShape(10.dp))
            .clickable { onFsrEnabledChange(!isFsrEnabled); onKeepUiAlight() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.fsr_enabled), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // 副标题 = 判定原因 +（启用时）**实际生效的目标尺寸**。
            // ⚠️ 实际尺寸按「档位高度 × 源宽高比」推出，非 16:9 片源
            //    会与档位的 16:9 参考值不同，必须显示真实值 ——
            //    否则用户（或下次排查的我）会以为设置没生效。
            //    ⚠️ stringResource 先算成 val 再用 if —— 别把它写进 if/else 分支里。
            val baseReason = stringResource(fsrDecision.reason.labelRes)
            val reasonLine = if (fsrDecision.enabled) {
                "$baseReason · ${fsrDecision.targetWidth}×${fsrDecision.targetHeight}"
            } else baseReason
            Text(reasonLine, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        AuraSwitch(
            checked = isFsrEnabled,
            onCheckedChange = { onFsrEnabledChange(it); onKeepUiAlight() },
            accentColor = accentColor,
            accentOnColor = accentOnColor,
            modifier = Modifier.scale(0.8f)
        )
    }
    if (isFsrEnabled) {
        // 规则模式：默认规则 / 自定义（自定义一旦选中即完全接管）
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.fsr_rule_mode), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FsrRuleMode.values().forEach { mode ->
                    val isSelected = fsrRuleMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .background(
                                if (isSelected) accentColor else Color.White.copy(alpha = 0.05f),
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable { onFsrRuleModeChange(mode); onKeepUiAlight() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(mode.labelRes),
                            color = if (isSelected) accentOnColor else Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
        // 自定义目标分辨率：仅在自定义规则下出现。
        // 档位按 3 列排布，label 里带上实际宽高（如「2160p (3840×2160)」），
        // 避免 3840p 这类非标准命名产生歧义。
        if (fsrRuleMode == FsrRuleMode.CUSTOM) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.fsr_target_resolution), color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                val allRes = FsrTargetResolution.values().toList()
                allRes.chunked(3).forEach { rowRes ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        rowRes.forEach { res ->
                            val isSelected = fsrCustomTarget == res
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(28.dp)
                                    .background(
                                        if (isSelected) accentColor else Color.White.copy(alpha = 0.05f),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable { onFsrCustomTargetChange(res); onKeepUiAlight() },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = res.label,
                                    color = if (isSelected) accentOnColor else Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        repeat(3 - rowRes.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
    Text(
        text = stringResource(R.string.enhance_perf_hint),
        color = Color.White.copy(alpha = 0.35f),
        fontSize = 10.sp
    )
}

}
