package com.example.vr

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * v106：开源许可声明页。
 *
 * 数据源：assets/licenses.json（由 scripts/gen_licenses.py 从 Gradle 依赖树自动生成，
 * 覆盖所有打包进 APK 的依赖 + 字体/模型/LUT 等非 Gradle 分发资源）。
 * 用于满足 Apache-2.0 / LGPL 等许可证的再分发署名义务。
 */

/** 单个许可条目 */
data class LicenseItem(
    val name: String,       // 组件名（group:name 或资源名）
    val license: String,    // 许可证（如 Apache-2.0 / LGPL）
    val version: String,    // 版本（非 Gradle 资源可能为空）
    val url: String,        // 许可证 URL（可为空）
    val note: String        // 附加说明（字体授权限制等）
)

/** 后台线程解析 assets/licenses.json（IO 操作不阻塞 UI） */
private fun loadLicenses(context: Context): List<LicenseItem> {
    return try {
        val json = context.assets.open("licenses.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONObject(json).getJSONArray("items")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            LicenseItem(
                name = o.optString("name"),
                license = o.optString("license"),
                version = o.optString("version"),
                url = o.optString("url"),
                note = o.optString("note")
            )
        }
    } catch (e: Exception) {
        listOf(LicenseItem("加载失败", "无法读取 licenses.json", "", "", e.message ?: ""))
    }
}

/** 记住并异步加载许可列表 */
@Composable
private fun rememberLicenses(): List<LicenseItem> {
    val context = LocalContext.current
    var licenses by remember { mutableStateOf<List<LicenseItem>?>(null) }
    LaunchedEffect(Unit) {
        licenses = withContext(Dispatchers.IO) { loadLicenses(context) }
    }
    return licenses ?: emptyList()
}

/**
 * 开源许可对话框。
 * 深色主题与主界面一致；列表可滚动，点击条目可展开许可证 URL（如有）。
 */
@Composable
fun OpenSourceLicensesDialog(onDismiss: () -> Unit) {
    val licenses = rememberLicenses()
    // 复用主界面主题色（与设置面板保持一致）
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("vr_player_prefs", Context.MODE_PRIVATE)
    }
    val accent = remember { UiThemes.byId(UiThemes.loadThemeId(prefs)).accent }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFC18171C),
        title = {
            Text(
                text = "开源软件许可",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (licenses.isEmpty()) {
                Text("正在加载许可清单…", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        Text(
                            text = "本应用使用了以下开源软件与资源（共 ${licenses.size} 项）。" +
                                "许可证全文可通过链接查看；LGPL 组件按 LGPL 2.1 条款提供。",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                    items(licenses) { item ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = item.name + if (item.version.isNotBlank()) " ${item.version}" else "",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = item.license,
                                color = accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            if (item.note.isNotBlank()) {
                                Text(
                                    text = item.note,
                                    color = Color.White.copy(alpha = 0.55f),
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp
                                )
                            }
                            if (item.url.isNotBlank()) {
                                Text(
                                    text = item.url,
                                    color = Color.White.copy(alpha = 0.4f),
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = accent)
            }
        }
    )
}
