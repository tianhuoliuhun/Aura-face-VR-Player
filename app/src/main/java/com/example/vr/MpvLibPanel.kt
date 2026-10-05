package com.example.vr

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

/**
 * 「MPV 解码库」的状态 / 管理面板。
 *
 * ## v2.1.243 起的语义变化
 * MPV 的 native 库**已改回内置 APK**（见 `app/build.gradle.kts` 的 packaging 注释）。
 * 所以正常情况下这里显示的是「**已内置**」——不提供下载、也不提供删除
 * （内置库跟着 APK 走，删了下次也还在，给"删除"按钮只会让用户困惑）。
 *
 * 旧的「后下载」模式**仍然保留为兜底**：若因某种原因 APK 没带上这些 so，
 * 用户可以回到这里下载（逻辑仍在 [MpvLibLoader]）。
 *
 * ⚠️ `isInstalled` 会读文件系统、`isReady` 会触发一次 native 加载（幂等有缓存），
 *    **不能每次重组都调** —— 都用 `remember` 缓存，以 `isInstalling` 作为失效依据。
 */
@Composable
internal fun MpvLibPanel(
    context: Context,
    accentColor: Color,
    accentOnColor: Color,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    // 安装中/完成都会让 isInstalling 变化 → 这会作为 remember 的 key 使缓存失效
    val installing = MpvLibLoader.isInstalling
    val installed = remember(context, installing) { MpvLibLoader.isInstalled(context) }
    // v2.1.243：APK 内置库是否可用（幂等，内部有 loaded 缓存）
    val builtInReady = remember(context, installing) { MpvLibLoader.isReady(context) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.mpv_lib_section),
                color = accentColor,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            when {
                // 内置可用优先展示"已内置"（下载目录即便有旧副本也不提，避免误导）
                builtInReady && !installing -> {
                    Text(
                        text = stringResource(R.string.mpv_lib_builtin),
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 8.sp
                    )
                }
                installed && !installing -> {
                    Text(
                        text = stringResource(R.string.mpv_lib_installed) + " · " +
                            "%.1f MB".format(MpvLibLoader.installedBytes(context) / 1048576.0),
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 8.sp
                    )
                }
            }
        }

        when {
            installing -> {
                val p = MpvLibLoader.progress
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = accentColor,
                    trackColor = Color.White.copy(alpha = 0.12f)
                )
                Text(
                    text = installStatusText() + "  ${(p * 100).toInt()}%",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 8.sp
                )
            }

            // v2.1.243：内置库随 APK 分发，**不给删除按钮**（删了也没用，下次仍在）
            builtInReady -> {
                Text(
                    text = stringResource(R.string.mpv_lib_builtin_hint),
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 7.sp,
                    lineHeight = 9.sp
                )
            }

            installed -> {
                Text(
                    text = stringResource(R.string.mpv_lib_ready_hint),
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 7.sp,
                    lineHeight = 9.sp
                )
                LibActionButton(
                    text = stringResource(R.string.mpv_lib_delete),
                    accentColor = Color.White.copy(alpha = 0.10f),
                    textColor = Color.White.copy(alpha = 0.7f),
                    onClick = { MpvLibLoader.uninstall(context) }
                )
            }

            else -> {
                Text(
                    text = stringResource(R.string.mpv_lib_not_installed),
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 7.sp,
                    lineHeight = 9.sp
                )
                LibActionButton(
                    text = stringResource(R.string.mpv_lib_download),
                    accentColor = accentColor,
                    textColor = accentOnColor,
                    onClick = {
                        MpvLibLoader.install(context, scope) { ok -> /* 结果由 state 反映到 UI */ }
                    }
                )
            }
        }

        // 失败原因（只在非安装中且有 status 时显示 —— status 仅失败时保留）
        if (!installing && MpvLibLoader.status.isNotBlank() && !builtInReady && !installed) {
            Text(
                text = stringResource(R.string.mpv_lib_failed) + ": " + MpvLibLoader.status,
                color = Color(0xFFFF8A80),
                fontSize = 7.sp,
                lineHeight = 9.sp
            )
        }
    }
}

/** 把 MpvLibLoader 的英文状态码映射成 5 语文案。 */
@Composable
private fun installStatusText(): String = when (MpvLibLoader.status) {
    "downloading" -> stringResource(R.string.mpv_lib_downloading)
    "extracting" -> stringResource(R.string.mpv_lib_extracting)
    "verifying" -> stringResource(R.string.mpv_lib_verifying)
    else -> stringResource(R.string.mpv_lib_installing)
}

@Composable
private fun LibActionButton(
    text: String,
    accentColor: Color,
    textColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(accentColor)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text = text, color = textColor, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
    }
}
