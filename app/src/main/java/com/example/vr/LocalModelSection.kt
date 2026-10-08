package com.example.vr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// ⚠️ R 类在 `com.example` 包下（build.gradle.kts 的 namespace），而本文件在
//    `com.example.vr` 下 —— **不同包**，必须显式 import（漏了会报 Unresolved reference 'R'）。
import com.example.R

/**
 * **本地模型**区块（v2.4.12）—— 下载 / 进度 / 删除。
 *
 * ## 为什么抽成独立组件
 * 本地 LLM **同时服务两个功能**（本地 AI 字幕翻译 + 本地 AI 弹幕生成），
 * 因此入口会出现在**两个面板**里。
 * ⚠️ 若各写一份，必然出现「一个面板加了删除按钮、另一个没有」这类不一致 ——
 * 这正是本项目头号事故源「同一功能两份 UI」。
 * → UI 抽成这一个实现，两个面板都调它（与 `SubtitleStylePanel` / `AsrLanguageChips`
 *    / `MediaFormats` 同一范式）。
 *
 * ## 状态来源
 * 全部来自 [LocalLlmManager] 的共享 Compose 状态 —— 所以下载进度在**两个面板里同步**，
 * 不需要各自维护一份（那又会是「同一份数据两处登记」）。
 */
@Composable
fun LocalModelSection(
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val model = LocalLlmManager.MODELS.firstOrNull() ?: return

    // 文件是否就绪属于「磁盘状态」，不在 Compose 的观察范围内 →
    // 用一个计数器在下载/删除完成后手动触发重算。
    var refreshKey by remember { mutableIntStateOf(0) }
    val ready = remember(refreshKey) { LocalLlmManager.isReady(context, model) }
    val downloadedMb = remember(refreshKey) { LocalLlmManager.downloadedMb(context, model) }

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.local_model_title),
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = stringResource(R.string.local_model_desc),
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(6.dp))

        // 模型名 + 体积
        Text(
            text = "${model.displayName} · ${model.sizeBytes / 1048576}MB",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(6.dp))

        if (LocalLlmManager.isDownloading) {
            // ---- 下载中：进度条 + 取消 ----
            LinearProgressIndicator(
                progress = { LocalLlmManager.downloadProgress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = accentColor,
                trackColor = Color.White.copy(alpha = 0.15f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = LocalLlmManager.downloadStatus.ifBlank {
                        "${(LocalLlmManager.downloadProgress * 100).toInt()}%"
                    },
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                LocalModelButton(
                    text = stringResource(R.string.local_model_cancel),
                    accentColor = accentColor,
                    onClick = { LocalLlmManager.cancelDownload() }
                )
            }
        } else if (ready) {
            // ---- 已下载：状态 + 删除 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.local_model_ready, downloadedMb),
                    color = accentColor,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                if (LocalLlmManager.loadedModelId == model.id) {
                    Text(
                        text = stringResource(R.string.local_model_loaded),
                        color = accentColor,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                LocalModelButton(
                    text = stringResource(R.string.local_model_delete),
                    accentColor = accentColor,
                    onClick = {
                        LocalLlmManager.release()
                        if (LocalLlmManager.fileOf(context, model).delete()) refreshKey++
                    }
                )
            }
        } else {
            // ---- 未下载：下载按钮 ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.local_model_not_downloaded),
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                LocalModelButton(
                    text = stringResource(R.string.local_model_download),
                    accentColor = accentColor,
                    onClick = {
                        LocalLlmManager.startDownload(context, model) { ok ->
                            if (ok) refreshKey++
                        }
                    }
                )
            }
        }

        // 错误信息：把真实原因显示出来，而不是笼统的"不可用"
        LocalLlmManager.lastError?.let { err ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = err,
                color = Color(0xFFFF8A80),
                fontSize = 11.sp
            )
        }
    }
}

/** 小尺寸描边按钮（与面板里其它操作按钮同风格）。 */
@Composable
private fun LocalModelButton(
    text: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 11.sp,
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(6.dp))
            .border(1.dp, accentColor.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}
