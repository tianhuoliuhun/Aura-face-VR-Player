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
 * 「MPV 解码库」的下载 / 管理面板 —— v2.1.235。
 *
 * MPV 的 native 库不进 APK（省 arm64 36.5MB / v7a 31.8MB），用户首次使用
 * MPV 内核时在这里下载（约 16MB 压缩包）。下载、解压、加载的全部逻辑在
 * [MpvLibLoader]，本文件只负责展示与触发。
 *
 * ⚠️ `isInstalled` 会读文件系统，**不能每次重组都调** —— 用 `remember` 缓存，
 *    并以 `isInstalling` 作为失效依据（安装结束后 isInstalling 由 true→false，
 *    正好触发重算，不需要额外的刷新信号）。
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
            if (installed && !installing) {
                Text(
                    text = stringResource(R.string.mpv_lib_installed) + " · " +
                        "%.1f MB".format(MpvLibLoader.installedBytes(context) / 1048576.0),
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 8.sp
                )
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
        if (!installing && MpvLibLoader.status.isNotBlank() && !installed) {
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
