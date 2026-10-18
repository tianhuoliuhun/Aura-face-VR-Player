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
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
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
        listOf(LicenseItem(context.getString(R.string.licenses_load_failed), context.getString(R.string.licenses_read_failed), "", "", e.message ?: ""))
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
 * v2.6.3：**非 Maven 组件分组**（native 库 / 模型 / 字体 / LUT）。
 *
 * `licenses.json` 只由 Gradle 依赖树生成，**覆盖不到** libmpv / FFmpeg / ijkplayer 这类
 * 预编译 `.so`、以及模型、字体、LUT —— 而 LGPL 的署名与"源码获取方式"义务恰恰落在这几项上。
 * 所以另存一份 `assets/third_party_native.json`，在这里合并展示。
 *
 * `titleKey` 存的是字符串资源**名**（不是 id），用 `getIdentifier` 解析以便 5 语本地化；
 * 解析失败时退回 key 本身，不会崩。
 */
data class LicenseGroup(val title: String, val items: List<LicenseItem>)

private fun loadNativeGroups(context: Context): List<LicenseGroup> {
    return try {
        val json = context.assets.open("third_party_native.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val groups = JSONObject(json).getJSONArray("groups")
        (0 until groups.length()).map { i ->
            val g = groups.getJSONObject(i)
            val titleKey = g.optString("titleKey")
            val resId = runCatching {
                context.resources.getIdentifier(titleKey, "string", context.packageName)
            }.getOrDefault(0)
            val title = if (resId != 0) context.getString(resId) else titleKey
            val arr = g.optJSONArray("items")
            val items = if (arr == null) emptyList() else (0 until arr.length()).map { k ->
                val o = arr.getJSONObject(k)
                LicenseItem(
                    name = o.optString("name"),
                    license = o.optString("license"),
                    version = o.optString("version"),
                    url = o.optString("url"),
                    note = o.optString("note")
                )
            }
            LicenseGroup(title, items)
        }
    } catch (e: Exception) {
        emptyList()   // 数据文件缺失时整块不显示，不影响 Maven 列表
    }
}

@Composable
private fun rememberNativeGroups(): List<LicenseGroup> {
    val context = LocalContext.current
    var groups by remember { mutableStateOf<List<LicenseGroup>?>(null) }
    LaunchedEffect(Unit) {
        groups = withContext(Dispatchers.IO) { loadNativeGroups(context) }
    }
    return groups ?: emptyList()
}

/**
 * v2.6.3：单条许可条目（Maven 与 native 两组共用，避免两套样式）。
 *
 * 带 `url` 的条目**可点击**，用系统浏览器打开 —— 这是 LGPL「提供源码获取方式」义务的落地形式。
 */
@Composable
private fun LicenseEntryRow(item: LicenseItem, accent: Color) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .then(
                if (item.url.isNotBlank()) {
                    Modifier.clickable { runCatching { uriHandler.openUri(item.url) } }
                } else Modifier
            )
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
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 开源许可对话框。
 * 深色主题与主界面一致；列表可滚动，点击条目可展开许可证 URL（如有）。
 */
@Composable
fun OpenSourceLicensesDialog(onDismiss: () -> Unit) {
    val licenses = rememberLicenses()
    // v2.6.3：native 库 / 模型 / 字体 / LUT 分组（Maven 依赖覆盖不到的那部分）
    val nativeGroups = rememberNativeGroups()
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
                text = stringResource(R.string.licenses_title),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (licenses.isEmpty() && nativeGroups.isEmpty()) {
                Text(stringResource(R.string.licenses_loading), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.licenses_count, licenses.size) +
                                stringResource(R.string.licenses_note),
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                    // ===== v2.6.3：先列「非 Maven」组件（LGPL 内核 / 模型 / 字体 / LUT）=====
                    // LGPL 的署名与「源码获取方式」义务主要落在这一组，所以放在最前面，
                    // 而不是埋在上百条 Maven 依赖底下。
                    nativeGroups.forEach { group ->
                        item(key = "header_" + group.title) {
                            Text(
                                text = group.title,
                                color = accent,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                            )
                        }
                        items(group.items) { entry ->
                            LicenseEntryRow(entry, accent)
                        }
                    }
                    item {
                        Text(
                            text = stringResource(R.string.licenses_lgpl_notice),
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 10.sp,
                            lineHeight = 15.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    item {
                        Text(
                            text = stringResource(R.string.licenses_group_maven),
                            color = accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                        )
                    }
                    items(licenses) { item ->
                        LicenseEntryRow(item, accent)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_close), color = accent)
            }
        }
    )
}
