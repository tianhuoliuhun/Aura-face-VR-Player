package com.example.vr

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * AI 弹幕视觉模型客户端（v2.3.1 / P2）
 *
 * 把「一张画面帧」交给支持图片输入的 OpenAI 兼容视觉模型，拿回若干条弹幕文本。
 *
 * 协议固定为 **OpenAI 兼容**（用户 2026-10-06 定案）：
 * ```
 * POST {baseUrl}/chat/completions
 * {
 *   "model": "...",
 *   "messages": [
 *     {"role":"system", "content":"<人格提示词>"},
 *     {"role":"user",   "content":[
 *        {"type":"text",      "text":"..."},
 *        {"type":"image_url", "image_url":{"url":"data:image/jpeg;base64,..."}}
 *     ]}
 *   ]
 * }
 * ```
 *
 * ## 设计要点
 * - **纯逻辑，无 Android UI 依赖**：可在任意线程/单测里跑
 * - **不持 SharedPreferences**：配置由调用方通过 [DanmuConfig] 传入（与 `SubtitleTranslator` 同范式）
 * - **失败静默**：网络异常/超时/额度问题只记日志并返回空列表，**绝不抛给调用方**
 *   （弹幕是锦上添花，不能因为一次请求失败影响播放）
 * - **编码在 IO 线程**：JPEG 压缩 + base64 都是 CPU 密集，务必 [Dispatchers.IO]
 *
 * ## 与 `SubtitleTranslator` 的关系
 * HTTP 范式（OkHttp + Bearer + `/chat/completions`）与其一致，但**有意不复用同一个实例**：
 * 翻译用文本模型、弹幕用视觉模型，共用 client 会让超时/并发策略互相干扰（方案文档 D1 决策）。
 */
class DanmuVisionClient {

    /**
     * ⚠️ 超时必须比翻译更宽松：
     * 视觉模型要**先编码一张图再推理**，比纯文本慢得多。实测 MiMo V2.6 Flash 在
     * 1024 宽 JPEG 下约 3–8s；给出 30s 读超时留足余量（低频调用，不占带宽）。
     */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 请求一批弹幕。
     *
     * v2.4.1 起支持三种素材组合（由 [config] 的 `sourceMode` 决定）：
     * - **画面 + 台词**：同时发图片与台词文本，模型可对台词本身做出反应（弹幕的灵魂）
     * - **仅画面**：与 v2.4.0 行为一致
     * - **仅台词**：不发图，只发台词；省带宽省 token，但看不到画面
     *
     * @param frame   画面帧（ARGB_8888，行序**已翻正**）。仅台词模式下可传 null
     * @param subtitleText 当前播放位置附近的台词（已拼成纯文本）。无台词/不用台词时传空串
     * @param config  弹幕配置（端点/模型/密钥/人格/批量/图像参数/素材来源）
     * @return 弹幕文本列表；任何失败都返回**空列表**（不抛异常）
     */
    suspend fun requestDanmu(
        frame: Bitmap?,
        config: DanmuConfig,
        subtitleText: String = ""
    ): List<String> = withContext(Dispatchers.IO) {
        if (!config.isReadyToRequest()) {
            Log.w(TAG, "配置不完整，跳过视觉请求（apiKey/baseUrl/model 需齐全）")
            return@withContext emptyList()
        }

        val endpoint = config.resolveEndpoint()
        if (endpoint.isBlank()) {
            Log.w(TAG, "端点为空，跳过视觉请求")
            return@withContext emptyList()
        }

        // ① 编码（仅画面模式需要；CPU 密集，已在 IO 线程）
        //    ⚠️ 仅台词模式**完全跳过编码**，省掉 JPEG 压缩与 base64（那是耗时大头）
        val dataUrl = if (config.needsImage) {
            if (frame == null) {
                Log.w(TAG, "需要画面但帧为 null，跳过本次请求")
                return@withContext emptyList()
            }
            encodeFrameToDataUrl(frame, config)
        } else {
            null
        }
        if (config.needsImage && dataUrl == null) {
            Log.e(TAG, "帧编码失败")
            return@withContext emptyList()
        }

        // ② 组请求体
        val persona = config.personaPrompt.ifBlank { DanmuConfig.DEFAULT_PERSONA }
        val prompt = persona.replace("{count}", config.batchSize.toString())

        val instruction = buildUserInstruction(
            count = config.batchSize,
            hasImage = dataUrl != null,
            hasSubtitle = subtitleText.isNotBlank(),
            subtitleText = subtitleText
        )

        val bodyJson = JSONObject().apply {
            put("model", config.modelName.trim())
            // 弹幕要「像真人、有变化」，温度比翻译高（翻译是 0.2）
            put("temperature", 1.0)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", prompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    // ⚠️ OpenAI 协议允许 content 为**纯字符串**（纯文本时）或**数组**（含图片时）。
                    //    仅台词模式没有图片 → 用字符串形式，兼容性最好
                    //    （部分实现不接受 `[{type:text}]` 这种单元素数组）。
                    put("content", buildUserContent(instruction, dataUrl))
                })
            })
        }
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        // ③ 发送
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // ⚠️ 不要把 response body 直接打全（可能很大），只打 code + 前 300 字符
                    val snippet = try {
                        response.body?.string()?.take(300)
                    } catch (e: Exception) {
                        null
                    }
                    Log.e(TAG, "视觉请求失败 HTTP ${response.code} ${response.message} | $snippet")
                    return@withContext emptyList()
                }

                val bodyStr = response.body?.string()
                if (bodyStr.isNullOrBlank()) {
                    Log.e(TAG, "视觉请求返回空 body")
                    return@withContext emptyList()
                }

                val content = extractMessageContent(bodyStr)
                if (content.isNullOrBlank()) {
                    Log.e(TAG, "响应中没有解析到 content")
                    return@withContext emptyList()
                }

                val lines = parseDanmuLines(content, config.batchSize)
                Log.d(TAG, "视觉请求成功，解析出 ${lines.size} 条弹幕")
                lines
            }
        } catch (e: Exception) {
            // 网络异常/超时/JSON 解析失败 —— 一律降级为空，绝不影响播放
            Log.e(TAG, "视觉请求异常: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 把帧压成 JPEG 并转 base64 data URL。
     *
     * ⚠️ **先缩放再编码**（不是编码后再缩）—— 这是 token 与带宽的大头：
     * 1920×1080 原图 PNG 转 base64 约 2–4 MB，而 1024 宽 JPEG(70) 通常只有 60–150 KB。
     *
     * @return `data:image/jpeg;base64,xxxx`；失败返回 null
     */
    private fun encodeFrameToDataUrl(frame: Bitmap, config: DanmuConfig): String? {
        return try {
            val scaled = scaleDown(frame, config.imageMaxWidth)
            // ⚠️ 缩放产生了新对象就要回收它；若返回的是原图则绝不能回收（生命周期归调用方）
            val needRecycle = scaled !== frame
            try {
                val bos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, config.imageQuality, bos)
                val bytes = bos.toByteArray()
                bos.close()
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                Log.d(TAG, "帧编码: ${scaled.width}x${scaled.height} -> ${bytes.size / 1024} KB (b64 ${b64.length / 1024} KB)")
                "data:image/jpeg;base64,$b64"
            } finally {
                if (needRecycle && !scaled.isRecycled) scaled.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "帧编码异常: ${e.message}", e)
            null
        }
    }

    /**
     * 等比缩放到最大宽度 [maxWidth] 以内。
     *
     * ⚠️ `Bitmap.createScaledBitmap` 保持长宽比时若高度算成 0 会抛异常 → 用 coerceAtLeast(1)。
     * ⚠️ 已经足够小则**原样返回**（不产生新对象，调用方据此判断是否需要回收）。
     */
    private fun scaleDown(frame: Bitmap, maxWidth: Int): Bitmap {
        val limit = maxWidth.coerceIn(64, 4096)
        if (frame.width <= limit) return frame
        val ratio = limit.toFloat() / frame.width
        val h = (frame.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(frame, limit, h, true)
    }

    /** 从 OpenAI 兼容响应里取 `choices[0].message.content`（兼容 content 为字符串） */
    private fun extractMessageContent(bodyStr: String): String? {
        return try {
            val root = JSONObject(bodyStr)
            val choices = root.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val msg = choices.optJSONObject(0)?.optJSONObject("message") ?: return null
            msg.optString("content", "").ifBlank { null }
        } catch (e: Exception) {
            Log.e(TAG, "解析响应 JSON 失败: ${e.message}")
            null
        }
    }

    /**
     * 把模型输出拆成弹幕列表。
     *
     * 模型可能不听话（加序号、加引号、加说明、用 markdown 列表）—— 这里做**宽容清洗**：
     * 1. 按行拆
     * 2. 去掉行首序号（`1.` / `1、` / `-` / `•` / `*`）
     * 3. 去掉成对引号（中英文）
     * 4. 丢弃空行、超长行、明显是说明性的行
     * 5. 最多取 [limit] 条
     */
    private fun parseDanmuLines(content: String, limit: Int): List<String> {
        return content.split('\n')
            .asSequence()
            .map { it.trim() }
            .map { stripLeadingBullet(it) }
            .map { stripWrappingQuotes(it) }
            .filter { it.isNotEmpty() }
            .filter { it.length <= MAX_SINGLE_LEN }
            .filterNot { isLikelyMetaLine(it) }
            .take(limit)
            .toList()
    }

    /** 去掉行首编号/项目符号 */
    private fun stripLeadingBullet(line: String): String {
        var s = line.trim()
        // 形如 "1." / "1、" / "1)" / "1：" / "#1"
        s = s.replace(Regex("^\\s*#?\\d+\\s*[.、)）:：]\\s*"), "")
        // 形如 "- " / "• " / "* " / "· "
        s = s.replace(Regex("^\\s*[-•*·]\\s+"), "")
        return s.trim()
    }

    /** 去掉包裹引号（成对才去；中英文都处理） */
    private fun stripWrappingQuotes(s: String): String {
        var t = s.trim()
        val pairs = listOf("\"" to "\"", "“" to "”", "'" to "'", "「" to "」", "『" to "』")
        for ((open, close) in pairs) {
            if (t.length >= 2 && t.startsWith(open) && t.endsWith(close)) {
                t = t.substring(1, t.length - 1).trim()
            }
        }
        return t
    }

    /**
     * 判断是否像「模型的解释性废话」而非弹幕本身。
     * 只拦最典型的一类，宁可漏拦也不要误杀正常弹幕。
     */
    private fun isLikelyMetaLine(s: String): Boolean {
        val metaHints = listOf(
            "这是一张图片", "以下是我", "根据画面", "图片中显示", "识别结果",
            "以下是", "弹幕如下", "Here are", "This image", "Based on"
        )
        return metaHints.any { s.contains(it, ignoreCase = true) }
    }

    /**
     * 组装 `content` 字段。
     *
     * - 有图片 → 数组形式 `[{type:text}, {type:image_url}]`
     * - 仅文本 → **纯字符串**（兼容性最好；部分 OpenAI 兼容实现不接受单元素数组）
     */
    private fun buildUserContent(instruction: String, dataUrl: String?): Any {
        if (dataUrl == null) return instruction
        return JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", instruction)
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply { put("url", dataUrl) })
            })
        }
    }

    /**
     * 生成 user 指令。
     *
     * v2.4.1：按**实际已有的素材**措辞 —— 只给台词时若还说"请观察这张截图"，
     * 模型会困惑甚至幻觉出画面内容。措辞必须与素材严格对应。
     */
    private fun buildUserInstruction(
        count: Int,
        hasImage: Boolean,
        hasSubtitle: Boolean,
        subtitleText: String
    ): String {
        val head = when {
            hasImage && hasSubtitle ->
                "请结合这张视频画面截图、以及当前这段台词，写出 $count 条弹幕。"
            hasImage ->
                "请观察这张视频画面截图，写出 $count 条弹幕。"
            hasSubtitle ->
                "请根据当前这段台词，写出 $count 条弹幕。"
            else ->
                "请写出 $count 条弹幕。"
        }
        val sb = StringBuilder(head)
        sb.append("直接输出弹幕文本，每行一条。")
        if (hasSubtitle) {
            sb.append("\n\n当前台词（仅供参考，不要复述台词，要像观众那样对台词做出反应）：\n")
            sb.append(subtitleText)
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "DanmuVisionClient"

        /** 单条弹幕最大长度（字符）：超过基本是模型跑偏了 */
        private const val MAX_SINGLE_LEN = 60

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
