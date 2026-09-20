package com.example.vr

import android.content.Context
import android.util.Log
import java.io.File

/**
 * v127e：字幕导出（SRT）。
 *
 * 背景：此前"导出 SRT"按钮的回调 `onExportSubtitle` 从未接线，点上去没有任何反应；
 * 且原导出依赖整片转写生成的临时文件，而该链路已随实时字幕方案移除。
 * 现在直接**从内存字幕缓存导出**——实时字幕与手动加载的字幕都能导出。
 *
 * 落点：`Android/data/<pkg>/files/` 下（应用外部目录，免权限、文件管理器可访问），
 * 与原先 `_asr.srt` 的位置一致，用户此前已习惯在该目录取文件。
 */
object SubtitleExporter {

    private const val TAG = "SubtitleExporter"

    /** 每行最大字数（与项目 SRT 换行口径一致） */
    private const val CHARS_PER_LINE = 14

    /**
     * 导出为 SRT。
     * @return 写出的文件；[cues] 为空时返回 null
     */
    fun exportSrt(
        context: Context,
        title: String?,
        cues: List<SubtitleCue>,
        langSuffix: String = ""
    ): File? {
        val sorted = cues
            .filter { it.text.isNotBlank() }
            .sortedBy { it.startTimeMs }
        if (sorted.isEmpty()) return null

        val baseName = safeBaseName(title)
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        dir.mkdirs()
        val file = File(dir, "$baseName$langSuffix.srt")
        return writeSrt(file, sorted)
    }

    /**
     * v2.0.153：按「目标语言 + 显示模式」生成文件名后缀，让**翻译字幕按语言分文件**。
     *
     * 例：仅译文导出 → `_zh`；双语导出 → `_zh_bi`；未启用翻译 → 空串（保持原命名）。
     * 这样同一部片子导出中文与英文译文不会互相覆盖，历史字幕也能按语言区分。
     */
    fun langSuffix(translator: SubtitleTranslator?): String {
        val t = translator ?: return ""
        if (!t.config.isEnabled) return ""
        val code = t.config.targetLanguage.code
        return when (t.config.displayMode) {
            TranslationDisplayMode.TARGET_ONLY -> "_$code"
            TranslationDisplayMode.DUAL_LANGUAGE -> "_${code}_bi"
        }
    }

    /**
     * v2.0.136：实时字幕全片生成完成后**自动保存**。
     * 落点：`Android/data/<pkg>/files/subtitles/`（应用专属 data 目录，免权限）。
     * 命名：`<视频名>_<yyyyMMdd-HHmmss><语言后缀>.srt`（时间戳后缀，多次生成互不覆盖，
     * 字典序即时间序；v2.0.153 起带语言后缀，见 [langSuffix]）。
     * 打开同一视频时可按名匹配自动加载。
     *
     * @param langSuffix 语言/模式后缀，如 `_zh`（仅译文）、`_zh_bi`（双语）；不翻译时传空串
     * @return 写出的文件；[cues] 为空或写入失败时返回 null
     */
    fun saveTimestamped(
        context: Context,
        title: String?,
        cues: List<SubtitleCue>,
        langSuffix: String = ""
    ): File? {
        val sorted = cues
            .filter { it.text.isNotBlank() }
            .sortedBy { it.startTimeMs }
        if (sorted.isEmpty()) return null

        val baseName = safeBaseName(title)
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "subtitles")
        dir.mkdirs()
        val file = File(dir, "${baseName}_${stamp}${langSuffix}.srt")
        return writeSrt(file, sorted)
    }

    /**
     * v2.0.136：列出 data 目录 subtitles/ 下属于某视频的历史字幕。
     * 匹配 `<videoBase>_<时间戳>.srt`；按文件名倒序（最新在前）。
     */
    fun listSavedSubtitles(context: Context, videoBase: String?): List<File> {
        val base = safeBaseName(videoBase)
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "subtitles")
        return dir.listFiles { f ->
            f.isFile && f.name.startsWith("${base}_") && f.name.endsWith(".srt")
        }?.sortedByDescending { it.name } ?: emptyList()
    }

    /** 视频标题 → 安全文件名基础（去扩展名、替换非法字符） */
    fun safeBaseName(title: String?): String =
        (title ?: "subtitles")
            .substringBeforeLast('.')
            .ifBlank { "subtitles" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")

    private fun writeSrt(file: File, sorted: List<SubtitleCue>): File? {
        val sb = StringBuilder()
        sorted.forEachIndexed { i, cue ->
            sb.append(i + 1).append('\n')
            sb.append(formatTime(cue.startTimeMs)).append(" --> ").append(formatTime(cue.endTimeMs)).append('\n')
            sb.append(wrapText(cue.text, CHARS_PER_LINE)).append("\n\n")
        }
        return try {
            file.writeText(sb.toString(), Charsets.UTF_8)
            Log.i(TAG, "字幕已导出：${file.absolutePath}（${sorted.size} 条）")
            file
        } catch (e: Exception) {
            Log.e(TAG, "字幕导出失败：${e.message}", e)
            null
        }
    }

    private fun formatTime(ms: Long): String {
        val s = if (ms < 0) 0L else ms
        return "%02d:%02d:%02d,%03d".format(
            s / 3600000, (s % 3600000) / 60000, (s % 60000) / 1000, s % 1000
        )
    }

    /** 按 [maxCharsPerLine] 折行；数字后不加空格，避免把 "3.14" 拆断 */
    private fun wrapText(text: String, maxCharsPerLine: Int): String {
        val trimmed = text.trim()
        if (trimmed.length <= maxCharsPerLine) return trimmed
        val sb = StringBuilder()
        var count = 0
        for (ch in trimmed) {
            if (ch == '\n') {
                sb.append('\n'); count = 0; continue
            }
            sb.append(ch); count++
            if (count >= maxCharsPerLine) {
                sb.append('\n'); count = 0
            }
        }
        return sb.toString().trimEnd('\n')
    }
}
