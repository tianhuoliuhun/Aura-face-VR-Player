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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect
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
    // v2.0.154：字幕去标点开关（默认开）。只影响「屏幕显示」与「导出 SRT」，
    // 内部原文保持带标点，断句与翻译质量不受影响
    stripPunctuation: Boolean = true,
    onStripPunctuationChange: (Boolean) -> Unit = {},
    translator: SubtitleTranslator? = null,
    onTranslateFileRequested: () -> Unit = {},
    // v2.0.127：翻译开关的持久化回调。面板本身不持有 prefs，
    // 由调用方写 SharedPreferences（原先只改内存 config，重启后翻译开关必丢）
    onTranslateEnabledChange: (Boolean) -> Unit = {},
    accentColor: Color,
    accentOnColor: Color,
    onUserActivity: () -> Unit
) {
    // v2.0.158：原先这里是「限高 420dp + 内部滚动 + 自绘滚动条」（v2.0.127 加的）。
    // 但设置弹窗**自身**就在滚动，于是形成嵌套滚动 —— 在面板内滑动时手势容易与外层打架，
    // 用户也难判断到底该滚哪一层。
    // 现在改为「不再限高、跟随外层滚动」，面板长度交给各区块的折叠状态控制（见 SubtitleSection）。
    // 注：外层 BoxWithConstraints 必须保留 —— 下面有 `return@Column`，它依赖这个非 inline 作用域。
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // v2.0.158：各区块（Section）的展开状态提升到这里 ——
        //  ① 跨会话记忆：写进 prefs，下次打开设置保持上次的展开情况；
        //  ② 便于用「全部展开 / 全部折叠」一次性控制。
        // 默认值：字幕文件与实时翻译展开（高频），显示样式与布局折叠。
        val context = LocalContext.current
        val sectionPrefs = remember(context) {
            context.getSharedPreferences("vr_player_prefs", Context.MODE_PRIVATE)
        }
        var secFileExpanded by remember {
            mutableStateOf(sectionPrefs.getBoolean("subtitle_section_expanded_file", true))
        }
        var secTranslateExpanded by remember {
            mutableStateOf(sectionPrefs.getBoolean("subtitle_section_expanded_translate", true))
        }
        var secStyleExpanded by remember {
            mutableStateOf(sectionPrefs.getBoolean("subtitle_section_expanded_style", false))
        }
        var secLayoutExpanded by remember {
            mutableStateOf(sectionPrefs.getBoolean("subtitle_section_expanded_layout", false))
        }
        fun setSectionExpanded(id: String, value: Boolean) {
            when (id) {
                "file" -> secFileExpanded = value
                "translate" -> secTranslateExpanded = value
                "style" -> secStyleExpanded = value
                "layout" -> secLayoutExpanded = value
            }
            sectionPrefs.edit().putBoolean("subtitle_section_expanded_$id", value).apply()
        }
        fun setAllSectionsExpanded(value: Boolean) {
            listOf("file", "translate", "style", "layout").forEach { setSectionExpanded(it, value) }
        }

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
                    text = stringResource(R.string.subtitle_settings_group),
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
                    text = if (isSubtitleEnabled) stringResource(R.string.subtitle_enabled) else stringResource(R.string.subtitle_disabled),
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
                text = stringResource(R.string.subtitle_off_hint),
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 10.sp,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            // 注：外层已改为 BoxWithConstraints（非 inline），这里不能再写裸 return，
            // 否则编译报 "'return' is prohibited here"。用标签从 Column 内容返回，语义一致。
            return@Column
        }

        // v2.0.154：字幕去标点（屏幕显示 + 导出 SRT 都生效；内部原文保持带标点，
        // 因此断句与翻译质量不受影响）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.subtitle_strip_punct),
                    color = Color.White,
                    fontSize = 11.sp
                )
                Text(
                    text = stringResource(R.string.subtitle_strip_punct_hint),
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 9.sp
                )
            }
            Switch(
                checked = stripPunctuation,
                onCheckedChange = {
                    onStripPunctuationChange(it)
                    onUserActivity()
                },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = accentOnColor,
                    checkedTrackColor = accentColor
                ),
                modifier = Modifier.height(24.dp)
            )
        }

        // v2.0.158：区块展开快捷键（各区块的展开状态会记忆到 prefs，下次打开保持不变）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 注：这里刻意不用 `listOf(...).forEach { }` —— 那样包一层普通 lambda 后，
            // 其中的 `Text` 会报 "@Composable invocations can only happen from the context
            // of a @Composable function"。两个按钮直接写开更清楚，也免掉这个坑。
            Surface(
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        setAllSectionsExpanded(true)
                        onUserActivity()
                    }
            ) {
                Text(
                    text = stringResource(R.string.subtitle_expand_all),
                    textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(vertical = 5.dp).fillMaxWidth()
                )
            }
            Surface(
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        setAllSectionsExpanded(false)
                        onUserActivity()
                    }
            ) {
                Text(
                    text = stringResource(R.string.subtitle_collapse_all),
                    textAlign = TextAlign.Center,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(vertical = 5.dp).fillMaxWidth()
                )
            }
        }

        // ===== Section: 字幕文件与实时语音 =====
        // 摘要先在语句位置算好再传：Compose 对「实参里嵌 lambda / if 再调 @Composable」支持不稳定
        // （见上方按钮处的编译错误说明）
        val fileSectionSummary = if (loadedSubtitleFileName.isNotBlank()) {
            loadedSubtitleFileName
        } else {
            stringResource(R.string.subtitle_no_file)
        }
        SubtitleSection(
            id = "file",
            title = stringResource(R.string.subtitle_section_file),
            icon = Icons.Default.FileOpen,
            accentColor = accentColor,
            expanded = secFileExpanded,
            onToggle = { setSectionExpanded("file", !secFileExpanded) },
            summary = fileSectionSummary
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
                    text = if (loadedSubtitleFileName.isNotEmpty()) stringResource(R.string.subtitle_current_file, loadedSubtitleFileName) else stringResource(R.string.subtitle_no_file),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (loadedCueCount > 0) stringResource(R.string.subtitle_cues_loaded, loadedCueCount) else stringResource(R.string.subtitle_import_hint),
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
                    Text(stringResource(R.string.subtitle_import), fontSize = 10.sp)
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
                    Text(stringResource(R.string.subtitle_export), fontSize = 10.sp)
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
                Text(stringResource(R.string.subtitle_online_search), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.subtitle_search_need_key), color = Color.White.copy(alpha = 0.4f), fontSize = 8.sp)
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
                    label = { Text(stringResource(R.string.subtitle_search_keyword), fontSize = 8.sp) },
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
                    Text(stringResource(R.string.subtitle_search_language), color = Color.White.copy(alpha = 0.5f), fontSize = 8.sp)
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
                            searchStatus = searchContext.getString(R.string.subtitle_search_searching)
                            searchResults = subtitleSearch.search(subtitleSearchApiKey, searchQuery, searchLang)
                            searchBusy = false
                            searchStatus = if (searchResults.isEmpty()) searchContext.getString(R.string.subtitle_search_not_found)
                            else searchContext.getString(R.string.subtitle_search_found, searchResults.size)
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
                    Text(stringResource(R.string.subtitle_search_go), fontSize = 11.sp)
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
                                    searchStatus = searchContext.getString(R.string.subtitle_downloading, r.releaseName)
                                    val file = subtitleSearch.download(
                                        subtitleSearchApiKey, r.fileId,
                                        java.io.File(searchContext.cacheDir, "online_subtitle_${r.fileId}.srt")
                                    )
                                    searchBusy = false
                                    if (file != null) {
                                        searchStatus = searchContext.getString(R.string.subtitle_downloaded)
                                        onSubtitleFileLoaded(file)
                                    } else {
                                        searchStatus = searchContext.getString(R.string.subtitle_download_failed)
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
        // 折叠时也能一眼看到「当前用什么引擎翻成什么语言」—— 同样先在语句位置算好
        val translateSectionSummary = if (translator?.config?.isEnabled == true) {
            stringResource(translator.config.engine.displayNameResId) + " · " +
                stringResource(translator.config.targetLanguage.nameResId)
        } else {
            stringResource(R.string.subtitle_translate_off)
        }
        SubtitleSection(
            id = "translate",
            title = stringResource(R.string.subtitle_section_translate),
            icon = Icons.Default.Translate,
            accentColor = accentColor,
            expanded = secTranslateExpanded,
            onToggle = { setSectionExpanded("translate", !secTranslateExpanded) },
            summary = translateSectionSummary
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
                                text = stringResource(R.string.subtitle_translate_neural),
                                color = if (translator.config.isEnabled) accentOnColor else Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = stringResource(R.string.subtitle_translate_switch),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Switch(
                        checked = translator.config.isEnabled,
                        onCheckedChange = { enabled ->
                            translator.config = translator.config.copy(isEnabled = enabled)
                            onTranslateEnabledChange(enabled)
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
                                Text(stringResource(R.string.subtitle_translate_file), color = accentColor, fontSize = 8.sp, fontWeight = FontWeight.Bold)
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
                                Text(stringResource(R.string.subtitle_translate_clear_cache), color = Color.White.copy(alpha = 0.8f), fontSize = 8.sp)
                            }
                        }
                    }

                    // Target Language Selector
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.subtitle_translate_target_lang), color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
                        // v2.0.153 修复：原先 `.take(5)` 导致其余 4 种目标语言（fr / de / es / ru）
                        // 在 UI 上**根本选不到**（枚举里有、界面没入口）。改为每行 5 个自动换行。
                        for (langRow in TranslationTargetLanguage.values().toList().chunked(5)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                for (lang in langRow) {
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
                                            text = stringResource(lang.nameResId),
                                            color = if (isSel) accentOnColor else Color.White,
                                            fontSize = 8.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1
                                        )
                                    }
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
                            Text(stringResource(R.string.subtitle_display_mode), color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
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
                                            text = if (mode == TranslationDisplayMode.DUAL_LANGUAGE) stringResource(R.string.subtitle_mode_bilingual) else stringResource(R.string.subtitle_mode_translated_only),
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
                        Text(stringResource(R.string.subtitle_translate_engine), color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            val engines = TranslationEngine.values()
                            // v2.0.157：引擎已增至 10 个，原先写死的 take(3)/drop(3) 两行放不下
                            // → 改为每行 3 个自动换行；末行不足 3 个时用等宽 Spacer 补位，保持列宽一致。
                            val engineRows = engines.toList().chunked(3)
                            for (engRow in engineRows) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    for (eng in engRow) {
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
                                                text = stringResource(eng.displayNameResId),
                                                color = if (isSel) accentOnColor else Color.White,
                                                fontSize = 8.sp,
                                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                    repeat(3 - engRow.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    // v2.4.12：**选中本地 LLM 引擎时才显示模型下载区**。
                    // ⚠️ 刻意不做成常显：其它引擎用不到它，常显会让面板变长，
                    //    还会让人误以为"必须下载这个模型才能翻译"。
                    // ⚠️ 组件是**共用的**（弹幕面板调的是同一个 LocalModelSection）——
                    //    不要在这里另写一份（本项目「同一功能两份 UI」是头号事故源）。
                    if (translator.config.engine == TranslationEngine.LOCAL_LLM) {
                        LocalModelSection(accentColor = accentColor)
                    }

                    // API Key & Base URL Inputs for engines that require a key.
                    // 另外 LibreTranslate 允许自托管，因此也展示（用于填私有实例地址 / key）。
                    if (translator.config.engine.requiresApiKey ||
                        translator.config.engine == TranslationEngine.LIBRETRANSLATE ||
                        translator.config.engine == TranslationEngine.GOOGLE_FREE) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // v2.0.157：免密端点（Google clients5）不需要 API Key，隐藏该输入框避免误导；
                            // LibreTranslate 的 key 用于自托管实例，保留。
                            if (translator.config.engine.requiresApiKey ||
                                translator.config.engine == TranslationEngine.LIBRETRANSLATE) {
                            OutlinedTextField(
                                value = translator.config.apiKey,
                                onValueChange = { key ->
                                    translator.config = translator.config.copy(apiKey = key)
                                    onUserActivity()
                                },
                                label = { Text(stringResource(translator.config.engine.displayNameResId) + " API Key", fontSize = 9.sp) },
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
                            } // end API Key：免密端点不显示

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

                    // ===== v2.0.153：翻译缓存统计（按语言分文件 / 命中率 / 单语言清空）=====
                    val tr = translator
                    val cacheStats = remember { mutableStateOf<List<SubtitleTranslator.LangCacheStat>>(emptyList()) }
                    val statsTick = remember { mutableStateOf(0) }
                    LaunchedEffect(tr, statsTick.value) {
                        cacheStats.value = withContext(Dispatchers.IO) { tr.scanCacheStats() }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.subtitle_cache_stats),
                                color = accentColor,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                stringResource(R.string.subtitle_cache_stats_refresh),
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 8.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .clickable {
                                        statsTick.value++
                                        onUserActivity()
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            stringResource(
                                R.string.subtitle_cache_stats_current,
                                tr.currentLangTag,
                                tr.currentLangEntryCount
                            ),
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 8.sp
                        )
                        Text(
                            stringResource(
                                R.string.subtitle_cache_stats_hit,
                                (tr.cacheHitRate * 100).toInt(),
                                tr.cacheHitCount,
                                tr.cacheMissCount
                            ),
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 8.sp
                        )
                        Text(
                            stringResource(
                                R.string.subtitle_cache_stats_session,
                                tr.sessionUsage,
                                tr.sessionLimit
                            ),
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 8.sp
                        )
                        if (cacheStats.value.isEmpty()) {
                            Text(
                                stringResource(R.string.subtitle_cache_stats_empty),
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 8.sp
                            )
                        } else {
                            cacheStats.value.forEach { s ->
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        s.langTag,
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 8.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        stringResource(
                                            R.string.subtitle_cache_stats_row,
                                            s.entries,
                                            (s.bytes / 1024).toInt()
                                        ),
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 8.sp
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = {
                                    tr.clearCacheFor(tr.currentLangTag)
                                    statsTick.value++
                                    onUserActivity()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    stringResource(R.string.subtitle_cache_clear_current),
                                    fontSize = 8.sp,
                                    maxLines = 1
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    tr.clearAllCaches()
                                    statsTick.value++
                                    onUserActivity()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    stringResource(R.string.subtitle_cache_clear_all),
                                    fontSize = 8.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                }
            }
        }

        // ===== Section: 显示样式 =====
        } // end section 实时翻译
        // v2.1.232：样式与布局两块已抽到 SubtitleStyleSettings，
        // 设置面板与字幕悬浮窗共用同一份（悬浮窗此前完全没有这些选项）。
        SubtitleStyleSettings(
            subtitleFont = subtitleFont,
            onFontChange = onFontChange,
            fontSizeSp = fontSizeSp,
            onFontSizeChange = onFontSizeChange,
            fontWeightVal = fontWeightVal,
            onFontWeightChange = onFontWeightChange,
            isItalic = isItalic,
            onItalicChange = onItalicChange,
            selectedColorOption = selectedColorOption,
            onColorOptionChange = onColorOptionChange,
            textAlpha = textAlpha,
            onTextAlphaChange = onTextAlphaChange,
            selectedStrokeOption = selectedStrokeOption,
            onStrokeOptionChange = onStrokeOptionChange,
            selectedBgOption = selectedBgOption,
            onBgOptionChange = onBgOptionChange,
            offsetYRatio = offsetYRatio,
            onOffsetYRatioChange = onOffsetYRatioChange,
            offsetXRatio = offsetXRatio,
            onOffsetXRatioChange = onOffsetXRatioChange,
            delayMs = delayMs,
            onDelayMsChange = onDelayMsChange,
            textAlign = textAlign,
            onTextAlignChange = onTextAlignChange,
            maxLines = maxLines,
            onMaxLinesChange = onMaxLinesChange,
            vrIpdOffsetRatio = vrIpdOffsetRatio,
            onVrIpdOffsetRatioChange = onVrIpdOffsetRatioChange,
            accentColor = accentColor,
            accentOnColor = accentOnColor,
            onUserActivity = onUserActivity,
            styleExpanded = secStyleExpanded,
            onStyleExpandedChange = { setSectionExpanded("style", it) },
            layoutExpanded = secLayoutExpanded,
            onLayoutExpandedChange = { setSectionExpanded("layout", it) }
        )
    }
    }
}

/**
 * Collapsible section header used to group the subtitle settings into tidy,
 * foldable blocks instead of one long scrolling list.
 *
 * v2.0.158：改为**受控组件** —— 展开状态由调用方持有并持久化到 prefs（原先内部是
 * `remember { mutableStateOf(initiallyExpanded) }`，关闭设置再打开就回到默认值，
 * 用户每次都得重新展开自己关心的块）。同时折叠时在标题右侧显示**当前值摘要**，
 * 展开/收起带过渡动画，不再瞬间跳变。
 *
 * @param id      稳定标识（仅用于 testTag，便于 UI 自动化定位）
 * @param expanded 是否展开（受控）
 * @param onToggle 点击标题的回调
 * @param summary  折叠时显示的摘要，如「Google 免密 · 简体中文」
 */
@Composable
internal fun SubtitleSection(
    id: String,
    title: String,
    icon: ImageVector,
    accentColor: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
    summary: String? = null,
    /**
     * v2.4.2：testTag 前缀。
     *
     * ⚠️ 原先 testTag 硬编码为 `subtitle_section_$id`，但本组件已**被 AI 弹幕面板复用**
     *    （见 DanmuSettingsPanel）——同一个 `id` 名（如 "model"）会与字幕面板的
     *    testTag 语义混淆。改为显式传入前缀，字幕面板传 "subtitle"，弹幕面板传 "danmu"。
     *    默认值保留 "subtitle" 以免影响既有 UI 自动化脚本。
     */
    tagPrefix: String = "subtitle",
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onToggle() }
                .padding(horizontal = 6.dp, vertical = 8.dp)
                .testTag("${tagPrefix}_section_$id"),
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
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false)
            )
            // 折叠状态下把「当前值」显示在标题右侧，不用展开就能看到现状
            if (!expanded && !summary.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = summary,
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
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
        // v2.0.158：展开/收起加动画，避免长列表瞬间跳变（手感更稳）
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column { content() }
        }
    }
}
