package com.example.vr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BubbleChart
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

/**
 * 播放器二级设置弹窗外壳与双列分组骨架（从 VRPlayerScreen.kt 拆出）。
 */
@Composable
internal fun BoxScope.PlayerSettingsDialogOverlay(
    isSettingsDialogOpen: Boolean,
    onDismiss: () -> Unit,
    accentColor: Color,
    expandedSettings: Set<String>,
    onToggleSettings: (String) -> Unit,
    onShowLicensesDialog: () -> Unit,
    onKeepUiAlight: () -> Unit,
    headerContent: @Composable () -> Unit,
    sectionTheme: @Composable () -> Unit,
    sectionProjection: @Composable () -> Unit,
    sectionSubtitle: @Composable () -> Unit,
    sectionAiSubtitle: @Composable () -> Unit,
    sectionDanmu: @Composable () -> Unit,
    sectionFloatingBall: @Composable () -> Unit,
    sectionDecoder: @Composable () -> Unit,
    sectionEnhance: @Composable () -> Unit,
    section8K: @Composable () -> Unit,
    beautyColumnContent: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = isSettingsDialogOpen,
        enter = fadeIn(animationSpec = tween(300, easing = EaseInOutCubic)) +
            scaleIn(initialScale = 0.90f, animationSpec = tween(400, easing = CustomEaseOutBack)),
        exit = fadeOut(animationSpec = tween(250, easing = EaseInOutCubic)) +
            scaleOut(targetScale = 0.95f, animationSpec = tween(250, easing = EaseInOutCubic)),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .clickable { onDismiss() }
                .padding(horizontal = 40.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color(0x22FFFFFF)),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFC18171C)
                ),
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .clickable(enabled = false) {}
                    .testTag("secondary_settings_dialog_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    headerContent()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1.3f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            SettingsGroup(stringResource(R.string.group_title_ui_theme), "theme", Icons.Default.Palette, expandedGroups = expandedSettings, onToggle = onToggleSettings, accentColor = accentColor, onInteract = onKeepUiAlight) { sectionTheme() }
                            SettingsGroup(stringResource(R.string.group_title_projection), "proj", Icons.Default.ViewInAr, expandedGroups = expandedSettings, onToggle = onToggleSettings, accentColor = accentColor, onInteract = onKeepUiAlight) { sectionProjection() }
                            SettingsGroup(stringResource(R.string.group_title_subtitle), "sub", Icons.Default.Subtitles, expandedGroups = expandedSettings, onToggle = onToggleSettings, accentColor = accentColor, onInteract = onKeepUiAlight) { sectionSubtitle() }
                            SettingsGroup(stringResource(R.string.group_title_ai_subtitle), "aisub", Icons.Default.Mic, expandedGroups = expandedSettings, onToggle = onToggleSettings, accentColor = accentColor, onInteract = onKeepUiAlight) { sectionAiSubtitle() }
                            SettingsGroup(stringResource(R.string.group_title_danmu), "danmu", Icons.Default.ChatBubble, expandedGroups = expandedSettings, onToggle = onToggleSettings, accentColor = accentColor, onInteract = onKeepUiAlight) { sectionDanmu() }
                            SettingsGroup(stringResource(R.string.group_title_floating_ball), "ball", Icons.Default.BubbleChart, expandedGroups = expandedSettings, onToggle = onToggleSettings, accentColor = accentColor, onInteract = onKeepUiAlight) { sectionFloatingBall() }
                            SettingsGroup(stringResource(R.string.group_title_decoder), "decode", Icons.Default.Memory, expandedGroups = expandedSettings, onToggle = onToggleSettings, accentColor = accentColor, onInteract = onKeepUiAlight) { sectionDecoder() }
                            SettingsGroup(stringResource(R.string.group_title_enhance), "enhance", Icons.Default.AutoAwesome, expandedGroups = expandedSettings, onToggle = onToggleSettings, accentColor = accentColor, onInteract = onKeepUiAlight) { sectionEnhance() }
                            SettingsGroup(stringResource(R.string.group_title_8k_hw), "8k", Icons.Default.HighQuality, expandedGroups = expandedSettings, onToggle = onToggleSettings, accentColor = accentColor, onInteract = onKeepUiAlight) { section8K() }
                            SettingsGroup(stringResource(R.string.group_title_about), "about", Icons.Default.Info, expandedGroups = expandedSettings, onToggle = onToggleSettings, accentColor = accentColor, onInteract = onKeepUiAlight) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color.White.copy(alpha = 0.04f))
                                        .clickable { onShowLicensesDialog(); onKeepUiAlight() }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.licenses_title),
                                            color = Color.White.copy(alpha = 0.85f),
                                            fontSize = 11.sp
                                        )
                                        Text(
                                            text = stringResource(R.string.licenses_desc),
                                            color = Color.White.copy(alpha = 0.45f),
                                            fontSize = 10.sp
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.action_view),
                                        color = accentColor,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Column(
                            modifier = Modifier
                                .weight(1.3f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            beautyColumnContent()
                        }
                    }
                }
            }
        }
    }
}
