package com.example.vr

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import java.io.File
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun SubtitleSettingsPanel(
    isSubtitleEnabled: Boolean,
    onSubtitleEnabledChange: (Boolean) -> Unit,
    loadedSubtitleFileName: String,
    loadedCueCount: Int,
    onPickSubtitleFile: () -> Unit,
    onExportSubtitle: () -> Unit = {},
    subtitleFont: SubtitleFont,
    onFontChange: (SubtitleFont) -> Unit,
    fontSizeSp: Int,
    onFontSizeChange: (Int) -> Unit,
    fontWeightVal: Int,
    onFontWeightChange: (Int) -> Unit,
    isItalic: Boolean,
    onItalicChange: (Boolean) -> Unit,
    selectedColorOption: SubtitleColorOption,
    onColorOptionChange: (SubtitleColorOption) -> Unit,
    textAlpha: Float,
    onTextAlphaChange: (Float) -> Unit,
    selectedStrokeOption: SubtitleStrokeOption,
    onStrokeOptionChange: (SubtitleStrokeOption) -> Unit,
    selectedBgOption: SubtitleBgOption,
    onBgOptionChange: (SubtitleBgOption) -> Unit,
    offsetYRatio: Float,
    onOffsetYRatioChange: (Float) -> Unit,
    offsetXRatio: Float,
    onOffsetXRatioChange: (Float) -> Unit,
    delayMs: Long,
    onDelayMsChange: (Long) -> Unit,
    textAlign: SubtitleAlignOption,
    onTextAlignChange: (SubtitleAlignOption) -> Unit,
    vrIpdOffsetRatio: Float,
    onVrIpdOffsetRatioChange: (Float) -> Unit,
    maxLines: Int = 2,
    onMaxLinesChange: (Int) -> Unit = {},
    subtitleSearchApiKey: String = "",
    onSubtitleSearchApiKeyChange: (String) -> Unit = {},
    defaultSearchQuery: String = "",
    onSubtitleFileLoaded: (File) -> Unit = {},
    translator: SubtitleTranslator? = null,
    onTranslateFileRequested: () -> Unit = {},
    accentColor: Color,
    accentOnColor: Color,
    onUserActivity: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Section Header: 字幕与样式设置
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Subtitles,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.height(18.dp)
                )
                Text(
                    text = "5. 字幕功能与\n全向双眼样式",
                    color = accentColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Master Switch
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = if (isSubtitleEnabled) "字幕已开启" else "字幕已关闭",
                    color = if (isSubtitleEnabled) Color.White else Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp
                )
                Switch(
                    checked = isSubtitleEnabled,
                    onCheckedChange = {
                        onSubtitleEnabledChange(it)
                        onUserActivity()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = accentOnColor,
                        checkedTrackColor = accentColor
                    ),
                    modifier = Modifier.height(24.dp)
                )
            }
        }

        if (!isSubtitleEnabled) {
            Text(
                text = "字幕功能处于关闭状态，开启后可加载外部 SRT/VTT 文件并自定义 VR/平面渲染样式",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 10.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            return
        }

        // ===== Section: 字幕文件与实时语音 =====
        SubtitleSection(
            title = "字幕文件与实时语音",
            icon = Icons.Default.FileOpen,
            accentColor = accentColor,
            initiallyExpanded = true
        ) {
        // Subtitle File Import Row
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (loadedSubtitleFileName.isNotEmpty()) "当前字幕: $loadedSubtitleFileName" else "未加载外部字幕",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (loadedCueCount > 0) "已载入 $loadedCueCount 条时间轴字幕" else "支持导入本地 .srt / .vtt 文本字幕文件",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 9.sp
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        onPickSubtitleFile()
                        onUserActivity()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = accentOnColor
                    ),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier
                        .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
                        .height(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FileOpen,
                        contentDescription = null,
                        modifier = Modifier.height(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("导入SRT/VTT字幕", fontSize = 10.sp)
                }

                OutlinedButton(
                    onClick = {
                        onExportSubtitle()
                        onUserActivity()
                    },
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier
                        .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
                        .height(32.dp)
                ) {
                    Text("导出SRT", fontSize = 10.sp)
                }
            }
        }


        // Online Subtitle Search (OpenSubtitles.com)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.04f))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val searchScope = androidx.compose.runtime.rememberCoroutineScope()
            val subtitleSearch = androidx.compose.runtime.remember { OnlineSubtitleSearch() }
            val searchContext = LocalContext.current
            var searchQuery by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(defaultSearchQuery) }
            var searchLang by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("zh") }
            var searchResults by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<OnlineSubtitleSearch.SubtitleResult>>(emptyList()) }
            var searchBusy by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            var searchStatus by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("在线字幕搜索", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text("需免费 API Key", color = Color.White.copy(alpha = 0.4f), fontSize = 8.sp)
            }

            OutlinedTextField(
                value = subtitleSearchApiKey,
                onValueChange = onSubtitleSearchApiKeyChange,
                label = { Text("OpenSubtitles API Key", fontSize = 8.sp) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentColor,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("搜索关键词（如文件名）", fontSize = 8.sp) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                Column {
                    Text("语言", color = Color.White.copy(alpha = 0.5f), fontSize = 8.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (l in listOf("zh", "en", "ja")) {
                            val sel = searchLang == l
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (sel) accentColor else Color.White.copy(alpha = 0.1f))
                                    .clickable { searchLang = l }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(l, color = if (sel) accentOnColor else Color.White, fontSize = 9.sp)
                            }
                        }
                    }
                }
                Button(
                    onClick = {
                        searchScope.launch {
                            searchBusy = true
                            searchStatus = "搜索中..."
                            searchResults = subtitleSearch.search(subtitleSearchApiKey, searchQuery, searchLang)
                            searchBusy = false
                            searchStatus = if (searchResults.isEmpty()) "未找到结果（检查 Key 或关键词）" else "找到 ${searchResults.size} 条结果"
                        }
                    },
                    enabled = !searchBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    modifier = Modifier
                        .defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
                        .height(40.dp)
                ) {
                    Text("搜索", fontSize = 11.sp)
                }
            }

            if (searchStatus.isNotEmpty()) {
                Text(searchStatus, color = accentColor, fontSize = 9.sp)
            }

            if (searchResults.isNotEmpty()) {
                searchResults.forEach { r ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .clickable {
                                searchScope.launch {
                                    searchBusy = true
                                    searchStatus = "下载中: ${r.releaseName}..."
                                    val file = subtitleSearch.download(
                                        subtitleSearchApiKey, r.fileId,
                                        java.io.File(searchContext.cacheDir, "online_subtitle_${r.fileId}.srt")
                                    )
                                    searchBusy = false
                                    if (file != null) {
                                        searchStatus = "已下载并加载"
                                        onSubtitleFileLoaded(file)
                                    } else {
                                        searchStatus = "下载失败（可能需积分或 Key 无效）"
                                    }
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = r.language.uppercase(),
                            color = accentColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(32.dp)
                        )
                        Text(
                            text = r.releaseName,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Default.FileOpen,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.height(12.dp)
                        )
                    }
                }
            }
        }

        // ===== Section: 实时翻译 =====
        } // end section 字幕文件与实时语音
        SubtitleSection(
            title = "实时翻译",
            icon = Icons.Default.Translate,
            accentColor = accentColor,
            initiallyExpanded = false
        ) {
        // Subtitle Translation Control Panel (Bing / LLM API)
        if (translator != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(
                        1.dp,
                        if (translator.config.isEnabled) accentColor else Color.White.copy(alpha = 0.15f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (translator.config.isEnabled) accentColor else Color.White.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "AI 神经同传",
                                color = if (translator.config.isEnabled) accentOnColor else Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "字幕实时AI翻译",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Switch(
                        checked = translator.config.isEnabled,
                        onCheckedChange = { enabled ->
                            translator.config = translator.config.copy(isEnabled = enabled)
                            onUserActivity()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = accentOnColor,
                            checkedTrackColor = accentColor
                        ),
                        modifier = Modifier.height(24.dp)
                    )
                }

                if (translator.config.isEnabled) {
                    // Status Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.4f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = translator.statusMessage,
                            color = accentColor,
                            fontSize = 9.sp,
                            modifier = Modifier.weight(1f)
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(accentColor.copy(alpha = 0.2f))
                                    .clickable {
                                        onTranslateFileRequested()
                                        onUserActivity()
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("翻译字幕文件", color = accentColor, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                            }

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .clickable {
                                        translator.clearCache()
                                        onUserActivity()
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("清空缓存", color = Color.White.copy(alpha = 0.8f), fontSize = 8.sp)
                            }
                        }
                    }

                    // Target Language Selector
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("目标翻译语言", color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            for (lang in TranslationTargetLanguage.values().take(5)) {
                                val isSel = translator.config.targetLanguage == lang
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isSel) accentColor else Color.White.copy(alpha = 0.1f))
                                        .clickable {
                                            translator.config = translator.config.copy(targetLanguage = lang)
                                            onUserActivity()
                                        }
                                        .padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = lang.displayName.substringBefore(" "),
                                        color = if (isSel) accentOnColor else Color.White,
                                        fontSize = 8.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }

                    // Display Mode Selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("显示模式", color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (mode in TranslationDisplayMode.values()) {
                                    val isSel = translator.config.displayMode == mode
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (isSel) accentColor else Color.White.copy(alpha = 0.1f))
                                            .clickable {
                                                translator.config = translator.config.copy(displayMode = mode)
                                                onUserActivity()
                                            }
                                            .padding(vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = if (mode == TranslationDisplayMode.DUAL_LANGUAGE) "双语对照" else "仅显示译文",
                                            color = if (isSel) accentOnColor else Color.White,
                                            fontSize = 9.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Translation Engine Selector
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("翻译引擎", color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            val engines = TranslationEngine.values()
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (eng in engines.take(3)) {
                                    val isSel = translator.config.engine == eng
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (isSel) accentColor else Color.White.copy(alpha = 0.1f))
                                            .clickable {
                                                translator.config = translator.config.copy(engine = eng)
                                                onUserActivity()
                                            }
                                            .padding(vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = eng.displayName.substringBefore(" "),
                                            color = if (isSel) accentOnColor else Color.White,
                                            fontSize = 8.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (eng in engines.drop(3)) {
                                    val isSel = translator.config.engine == eng
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (isSel) accentColor else Color.White.copy(alpha = 0.1f))
                                            .clickable {
                                                translator.config = translator.config.copy(engine = eng)
                                                onUserActivity()
                                            }
                                            .padding(vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = eng.displayName.substringBefore(" "),
                                            color = if (isSel) accentOnColor else Color.White,
                                            fontSize = 8.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // API Key & Base URL Inputs for engines that require a key
                    // (the free Bing endpoint needs neither key nor base URL)
                    if (translator.config.engine.requiresApiKey) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                value = translator.config.apiKey,
                                onValueChange = { key ->
                                    translator.config = translator.config.copy(apiKey = key)
                                    onUserActivity()
                                },
                                label = { Text("${translator.config.engine.displayName} API Key", fontSize = 9.sp) },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = accentColor,
                                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                    focusedLabelColor = accentColor,
                                    unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                OutlinedTextField(
                                    value = translator.config.baseUrl,
                                    onValueChange = { url ->
                                        translator.config = translator.config.copy(baseUrl = url)
                                        onUserActivity()
                                    },
                                    label = { Text("Base URL", fontSize = 8.sp) },
                                    placeholder = { Text(translator.config.engine.defaultBaseUrl, fontSize = 8.sp) },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1.2f)
                                        .height(44.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = accentColor,
                                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                        focusedLabelColor = accentColor,
                                        unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    )
                                )

                                OutlinedTextField(
                                    value = translator.config.modelName,
                                    onValueChange = { model ->
                                        translator.config = translator.config.copy(modelName = model)
                                        onUserActivity()
                                    },
                                    label = { Text("Model", fontSize = 8.sp) },
                                    placeholder = { Text(translator.config.engine.defaultModel, fontSize = 8.sp) },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(0.8f)
                                        .height(44.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = accentColor,
                                        unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                                        focusedLabelColor = accentColor,
                                        unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // ===== Section: 显示样式 =====
        } // end section 实时翻译
        SubtitleSection(
            title = "显示样式",
            icon = Icons.Default.TextFields,
            accentColor = accentColor,
            initiallyExpanded = true
        ) {
        // Realtime Preview Panel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "― 实时字幕效果预览 ―",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 9.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            val sampleFontFamily = SubtitleFontHelper.getFontFamily(subtitleFont, fontWeightVal, isItalic)
            SubtitledText(
                text = "这是 VR 空间与平面同步字幕预览效果\nOPPO Sans VF 变体字体",
                fontFamily = sampleFontFamily,
                fontSizeSp = (fontSizeSp * 0.75f).toInt().coerceAtLeast(12),
                fontWeightVal = fontWeightVal,
                isItalic = isItalic,
                textColor = selectedColorOption.color,
                textAlpha = textAlpha,
                strokeColor = selectedStrokeOption.strokeColor,
                strokeWidthDp = selectedStrokeOption.widthDp,
                backgroundColor = selectedBgOption.bgColor,
                backgroundAlpha = selectedBgOption.alpha,
                textAlign = textAlign.textAlign
            )
        }

        // 1. Font Selection (包含 OPPO Sans VF)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("字幕字体", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
            val fontChunked = SubtitleFont.values().toList().chunked(4)
            for (rowOptions in fontChunked) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (fOpt in rowOptions) {
                        val isSel = subtitleFont == fOpt
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(28.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSel) accentColor else Color.White.copy(alpha = 0.05f))
                                .clickable {
                                    onFontChange(fOpt)
                                    onUserActivity()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = fOpt.displayName,
                                color = if (isSel) accentOnColor else Color.White,
                                fontSize = 9.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                    repeat(4 - rowOptions.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // 2. VF Font Weight & Italic
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("VF 变体字重: $fontWeightVal", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                    Text(
                        text = when (fontWeightVal) {
                            in 100..200 -> "极细"
                            in 201..350 -> "纤细"
                            in 351..450 -> "常规"
                            in 451..650 -> "中黑"
                            in 651..800 -> "粗体"
                            else -> "黑体"
                        },
                        color = accentColor,
                        fontSize = 10.sp
                    )
                }
                Slider(
                    value = fontWeightVal.toFloat(),
                    onValueChange = {
                        onFontWeightChange(it.roundToInt())
                        onUserActivity()
                    },
                    valueRange = 100f..900f,
                    steps = 7,
                    colors = SliderDefaults.colors(
                        thumbColor = accentColor,
                        activeTrackColor = accentColor
                    ),
                    modifier = Modifier.height(20.dp)
                )
            }

            Box(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isItalic) accentColor else Color.White.copy(alpha = 0.08f))
                    .clickable {
                        onItalicChange(!isItalic)
                        onUserActivity()
                    }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isItalic) "斜体: 开" else "斜体: 关",
                    color = if (isItalic) accentOnColor else Color.White,
                    fontSize = 10.sp
                )
            }
        }

        // 3. Text Color & Alpha
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("字幕颜色", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                Text("不透明度: ${(textAlpha * 100).roundToInt()}%", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (colorOpt in SubtitleColorOption.values()) {
                    val isSel = selectedColorOption == colorOpt
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(26.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(colorOpt.color)
                            .border(
                                width = if (isSel) 2.dp else 1.dp,
                                color = if (isSel) accentColor else Color.Gray.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable {
                                onColorOptionChange(colorOpt)
                                onUserActivity()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSel) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = if (colorOpt.color == Color.White || colorOpt.color == Color(0xFFFFEB3B)) Color.Black else Color.White,
                                modifier = Modifier.height(14.dp)
                            )
                        }
                    }
                }
            }

            Slider(
                value = textAlpha,
                onValueChange = {
                    onTextAlphaChange(it)
                    onUserActivity()
                },
                valueRange = 0.2f..1.0f,
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor
                ),
                modifier = Modifier.height(20.dp)
            )
        }

        // 4. Stroke / Outline & Background Options
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Stroke selector
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("文字描边", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                val strokeList = SubtitleStrokeOption.values()
                for (sOpt in strokeList.take(3)) {
                    val isSel = selectedStrokeOption == sOpt
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSel) accentColor else Color.White.copy(alpha = 0.05f))
                            .clickable {
                                onStrokeOptionChange(sOpt)
                                onUserActivity()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = sOpt.displayName,
                            color = if (isSel) accentOnColor else Color.White,
                            fontSize = 9.sp
                        )
                    }
                }
            }

            // Stroke selector part 2
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("高阶描边", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                val strokeList = SubtitleStrokeOption.values()
                for (sOpt in strokeList.drop(3)) {
                    val isSel = selectedStrokeOption == sOpt
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSel) accentColor else Color.White.copy(alpha = 0.05f))
                            .clickable {
                                onStrokeOptionChange(sOpt)
                                onUserActivity()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = sOpt.displayName,
                            color = if (isSel) accentOnColor else Color.White,
                            fontSize = 9.sp
                        )
                    }
                }
            }

            // Background selector
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("字幕背景", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                for (bgOpt in SubtitleBgOption.values().take(3)) {
                    val isSel = selectedBgOption == bgOpt
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSel) accentColor else Color.White.copy(alpha = 0.05f))
                            .clickable {
                                onBgOptionChange(bgOpt)
                                onUserActivity()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = bgOpt.displayName,
                            color = if (isSel) accentOnColor else Color.White,
                            fontSize = 9.sp
                        )
                    }
                }
            }
        }

        // ===== Section: 布局与时间 =====
        } // end section 显示样式
        SubtitleSection(
            title = "布局与时间",
            icon = Icons.Default.Tune,
            accentColor = accentColor,
            initiallyExpanded = false
        ) {
        // 5. Size & Alignment
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1.2f)) {
                Text("字幕大小: ${fontSizeSp}sp", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                Slider(
                    value = fontSizeSp.toFloat(),
                    onValueChange = {
                        onFontSizeChange(it.roundToInt())
                        onUserActivity()
                    },
                    valueRange = 12f..40f,
                    colors = SliderDefaults.colors(
                        thumbColor = accentColor,
                        activeTrackColor = accentColor
                    ),
                    modifier = Modifier.height(20.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text("每段行数", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    for (n in listOf(1, 2, 3)) {
                        val isSel = maxLines == n
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(26.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSel) accentColor else Color.White.copy(alpha = 0.05f))
                                .clickable {
                                    onMaxLinesChange(n)
                                    onUserActivity()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${n}行",
                                color = if (isSel) accentOnColor else Color.White,
                                fontSize = 9.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text("对齐方式", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    for (alignOpt in SubtitleAlignOption.values()) {
                        val isSel = textAlign == alignOpt
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(26.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSel) accentColor else Color.White.copy(alpha = 0.05f))
                                .clickable {
                                    onTextAlignChange(alignOpt)
                                    onUserActivity()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = alignOpt.displayName,
                                color = if (isSel) accentOnColor else Color.White,
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }
        }

        // 6. Subtitle Movable Position (可移动字幕 Y & X Offset)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("字幕垂直移动: ${(offsetYRatio * 100).roundToInt()}%", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("底部", color = accentColor, fontSize = 9.sp, modifier = Modifier.clickable { onOffsetYRatioChange(0.12f); onUserActivity() })
                    Text("居中", color = accentColor, fontSize = 9.sp, modifier = Modifier.clickable { onOffsetYRatioChange(0.50f); onUserActivity() })
                    Text("顶部", color = accentColor, fontSize = 9.sp, modifier = Modifier.clickable { onOffsetYRatioChange(0.82f); onUserActivity() })
                }
            }
            Slider(
                value = offsetYRatio,
                onValueChange = {
                    onOffsetYRatioChange(it)
                    onUserActivity()
                },
                valueRange = 0.02f..0.90f,
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor
                ),
                modifier = Modifier.height(20.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("字幕水平偏移: ${(offsetXRatio * 100).roundToInt()}%", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                Text("居中", color = accentColor, fontSize = 9.sp, modifier = Modifier.clickable { onOffsetXRatioChange(0.0f); onUserActivity() })
            }
            Slider(
                value = offsetXRatio,
                onValueChange = {
                    onOffsetXRatioChange(it)
                    onUserActivity()
                },
                valueRange = -0.4f..0.4f,
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor
                ),
                modifier = Modifier.height(20.dp)
            )
        }

        // 7. Subtitle Delay Compensation in range ±30s (字幕延迟补偿 ±30s)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.04f))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "字幕延迟时间补偿",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )

                val delaySec = delayMs / 1000.0f
                val delayText = when {
                    delayMs > 0 -> "+%.1fs".format(delaySec)
                    delayMs < 0 -> "%.1fs".format(delaySec)
                    else -> "0.0s"
                }
                Text(
                    text = delayText,
                    color = if (delayMs != 0L) accentColor else Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Quick Step Adjustment Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(-5000L to "-5s", -1000L to "-1s", -500L to "-0.5s", -100L to "-0.1s").forEach { (step, label) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(26.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable {
                                onDelayMsChange((delayMs + step).coerceIn(-30000L, 30000L))
                                onUserActivity()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = Color.White, fontSize = 9.sp)
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1.2f)
                        .height(26.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(accentColor)
                        .clickable {
                            onDelayMsChange(0L)
                            onUserActivity()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("0s", color = accentOnColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }

                listOf(100L to "+0.1s", 500L to "+0.5s", 1000L to "+1s", 5000L to "+5s").forEach { (step, label) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(26.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .clickable {
                                onDelayMsChange((delayMs + step).coerceIn(-30000L, 30000L))
                                onUserActivity()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = Color.White, fontSize = 9.sp)
                    }
                }
            }

            // Coarse Delay Slider ±30s
            Slider(
                value = delayMs.toFloat(),
                onValueChange = {
                    onDelayMsChange(it.toLong().coerceIn(-30000L, 30000L))
                    onUserActivity()
                },
                valueRange = -30000f..30000f,
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor
                ),
                modifier = Modifier.height(20.dp)
            )
        }

        // 8. VR Mode Dual-Eye IPD Fine Tuning
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("VR 双眼视差/瞳距微调", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                Text("${(vrIpdOffsetRatio * 100).roundToInt()}%", color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
            }
            Slider(
                value = vrIpdOffsetRatio,
                onValueChange = {
                    onVrIpdOffsetRatioChange(it)
                    onUserActivity()
                },
                valueRange = -0.1f..0.1f,
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor
                ),
                modifier = Modifier.height(20.dp)
            )
        }
        } // end section 布局与时间
    }
}

/**
 * Collapsible section header used to group the subtitle settings into tidy,
 * foldable blocks instead of one long scrolling list.
 */
@Composable
private fun SubtitleSection(
    title: String,
    icon: ImageVector,
    accentColor: Color,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }
        HorizontalDivider(
            thickness = 0.5.dp,
            color = Color.White.copy(alpha = 0.08f),
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        if (expanded) {
            content()
        }
    }
}
