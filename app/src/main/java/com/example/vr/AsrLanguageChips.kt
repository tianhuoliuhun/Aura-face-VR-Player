package com.example.vr

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

/**
 * v2.1.232：**AI 字幕识别语言的唯一实现**。
 *
 * ## 为什么要抽出来
 * 此前这份 UI 有**两份逐字雷同的副本** —— 一份在 `VRPlayerScreen`（设置面板），
 * 一份在 `AsrBatchSection`（悬浮窗 / 设置面板共用）。
 * 直接后果就是「一处修了、另一处没修」反复发生：
 *   - v2.1.226 修「内置候选要点亮必须显式写 builtin」→ 只改了 VRPlayerScreen
 *   - v2.1.231 改「四项分类」→ 又只改了 VRPlayerScreen
 * 悬浮窗与设置面板于是各显示一套不同的分类，且悬浮窗一直带着 v2.1.226 那个
 * 「内置候选选不中」的老 bug。
 *
 * 现在两处都调这里，**同一份代码、同一套选中判定**。以后改语言面板只需改这一处。
 *
 * ## 选中判定
 * uid = `语言@模型`。同语言的多个 chip uid 互不相同（`en@builtin` / `en@nemo-fast…`），
 * 从结构上杜绝「多个一起亮」。
 *
 * @param activeModelId 当前生效的模型 id（`builtin` 或扩展模型 dirName），由调用方统一解析
 * @param onPick 用户点了某个候选：`(语言码, 模型id)`。**顺序很重要** —— 调用方必须
 *               先切语言、再写模型选择（同语言换模型时「切语言」那边会直接 return，
 *               全靠写模型选择来触发识别器重建）
 * @param compact true = 悬浮窗等窄容器（字号/内距更小）
 */
@Composable
fun AsrLanguageChips(
    context: Context,
    sherpaLangCode: String,
    activeModelId: String,
    accentColor: Color,
    accentOnColor: Color,
    onPick: (String, String) -> Unit,
    onUserInteraction: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** 每行几个 chip（悬浮窗窄一点放 4 个刚好） */
    columns: Int = 4
) {
    // —— 浏览态是**临时**的，不持久化（折叠 / 视角只是当前这次查看的方式）——
    // v2.1.231：默认视角由「按模型」改为「按四项分类」（用户反馈按模型找语言太绕），
    //           「按模型」作为对比视角保留。
    var groupModeByModel by remember { mutableStateOf(false) }
    // 默认只展开「常用语言」，其余折叠（▶）—— 87 个 chip 全铺开要 22 行，首屏太长
    var collapsedGroups by remember {
        mutableStateOf(
            SherpaAsrManager.AsrLangCategory.values()
                .filter { it != SherpaAsrManager.AsrLangCategory.COMMON }
                .map { it.name }
                .toSet()
        )
    }
    fun toggleGroup(id: String) {
        collapsedGroups = if (id in collapsedGroups) collapsedGroups - id else collapsedGroups + id
    }

    // 尺寸档（悬浮窗 vs 设置面板）
    val titleSp = if (compact) 9.sp else 10.sp
    val chipSp = if (compact) 8.sp else 9.sp
    val subTitleSp = if (compact) 7.sp else 8.sp
    val countSp = if (compact) 8.sp else 9.sp
    val chipPadV: Dp = if (compact) 4.dp else 5.dp
    val rowGap: Dp = if (compact) 3.dp else 4.dp

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // —— 视角切换：按分类 / 按模型 ——
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                false to stringResource(R.string.asr_group_by_lang),
                true to stringResource(R.string.asr_group_by_model)
            ).forEach { (byModel, label) ->
                val sel = groupModeByModel == byModel
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (sel) accentColor else Color.White.copy(alpha = 0.08f))
                        .clickable { groupModeByModel = byModel; onUserInteraction() }
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (sel) accentOnColor else Color.White.copy(alpha = 0.75f),
                        fontSize = countSp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        if (!groupModeByModel) {
            // ================= 四项分类（可折叠） =================
            // 常用语言 / 中日韩英 / 内置模型语言 / 下载模型语言，四类互斥 ——
            // 每个 chip 只出现一次，面板不会因重复铺开而变得难找。
            SherpaAsrManager.groupedByCategory().forEach { (cat, langs) ->
                val cCollapsed = cat.name in collapsedGroups
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .clickable { toggleGroup(cat.name); onUserInteraction() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(cat.labelRes),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = titleSp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${langs.size}  ${if (cCollapsed) "▶" else "▼"}",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = countSp
                    )
                }
                if (!cCollapsed) {
                    // 组内超过一个语区时加**语区小标题**分段 ——
                    // 「下载模型语言」可能有 40+ 个 chip，不分段就是一长串找不到目标。
                    val subGroups = langs.groupBy { it.group }.entries.sortedBy { it.key.sortOrder }
                    val showSubTitle = subGroups.size > 1
                    subGroups.forEach { (sub, subLangs) ->
                        if (showSubTitle) {
                            Text(
                                text = stringResource(sub.labelRes),
                                color = Color.White.copy(alpha = 0.4f),
                                fontSize = subTitleSp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                            )
                        }
                        ChipGrid(
                            langs = subLangs,
                            columns = columns,
                            sherpaLangCode = sherpaLangCode,
                            activeModelId = activeModelId,
                            accentColor = accentColor,
                            accentOnColor = accentOnColor,
                            chipSp = chipSp,
                            chipPadV = chipPadV,
                            rowGap = rowGap,
                            onPick = onPick,
                            onUserInteraction = onUserInteraction
                        )
                    }
                }
            }
        } else {
            // ================= 按模型分组（多模型对比视角） =================
            // 一个模型一组：它能识别哪些语言、模型多大 —— 点语言即选模型，一步完成。
            SherpaAsrManager.groupedByModel().forEach { (modelId, langs) ->
                Column(verticalArrangement = Arrangement.spacedBy(rowGap)) {
                    Text(
                        // v2.1.224：显示友好名而不是原始 dirName
                        //（原样会显示成 dolphin-base-ctc-multi-lang-int8 这种极长的目录名）
                        text = "▸ " + AsrExtModels.friendlyModelName(modelId) + "  ·  ${langs.size}",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = titleSp,
                        fontWeight = FontWeight.SemiBold
                    )
                    ChipGrid(
                        langs = langs,
                        columns = columns,
                        sherpaLangCode = sherpaLangCode,
                        activeModelId = activeModelId,
                        accentColor = accentColor,
                        accentOnColor = accentOnColor,
                        chipSp = chipSp,
                        chipPadV = chipPadV,
                        rowGap = rowGap,
                        onPick = onPick,
                        onUserInteraction = onUserInteraction
                    )
                }
            }
        }
    }
}

/** 一组 chip 按 [columns] 个一行铺成网格；不足一行用 Spacer 补齐保持等宽 */
@Composable
private fun ChipGrid(
    langs: List<SherpaAsrManager.SherpaLang>,
    columns: Int,
    sherpaLangCode: String,
    activeModelId: String,
    accentColor: Color,
    accentOnColor: Color,
    chipSp: androidx.compose.ui.unit.TextUnit,
    chipPadV: Dp,
    rowGap: Dp,
    onPick: (String, String) -> Unit,
    onUserInteraction: () -> Unit
) {
    langs.chunked(columns).forEach { rowLangs ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(rowGap)
        ) {
            rowLangs.forEach { lang ->
                val code = lang.code
                val modelId = lang.modelId ?: "builtin"
                val label = stringResource(lang.labelResId) + (lang.suffix?.let { " $it" } ?: "")
                // ⚠️ 选中判定 = uid 精确匹配（语言@模型）
                val sel = "$code@$modelId" == "$sherpaLangCode@$activeModelId"
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (sel) accentColor else Color.White.copy(alpha = 0.08f))
                        .clickable {
                            onUserInteraction()
                            // v2.1.226：**内置候选也要写 "builtin"**（不能 `modelId?.let{}` 跳过）。
                            // 从扩展模型切回内置时，「切语言」那边会因 code 相同直接 return，
                            // 全靠这里写模型选择触发识别器重建 —— 漏了就是「点了没反应」。
                            onPick(code, modelId)
                        }
                        .padding(vertical = chipPadV),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (sel) accentOnColor else Color.White.copy(alpha = 0.85f),
                        fontSize = chipSp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
            repeat(columns - rowLangs.size) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}
