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
     * 请求一批弹幕（**保持向后兼容的薄封装**）。
     *
     * ⚠️ v2.4.5 起请优先用 [fetchDanmu] —— 它会同时带回**失败原因**。
     *    本方法只返回列表，调用方无法区分「模型真的没返回内容」和
     *    「401 密钥无效 / 404 模型名错」—— v2.4.4 的实测事故正是如此：
     *    日志里明明是 `HTTP 401 Invalid bearer token`，UI 却显示
     *    「模型未返回可用弹幕」，把用户引向了完全错误的方向。
     *
     * @return 弹幕文本列表；任何失败都返回**空列表**（不抛异常）
     */
    suspend fun requestDanmu(
        frame: Bitmap?,
        config: DanmuConfig,
        subtitleText: String = ""
    ): List<String> = fetchDanmu(frame, config, subtitleText).lines

    /**
     * 请求一批弹幕，**并带回失败原因**（v2.4.5）。
     *
     * v2.4.1 起支持三种素材组合（由 [config] 的 `sourceMode` 决定）：
     * - **画面 + 台词**：同时发图片与台词文本，模型可对台词本身做出反应（弹幕的灵魂）
     * - **仅画面**：与 v2.4.0 行为一致
     * - **仅台词**：不发图，只发台词；省带宽省 token，但看不到画面
     *
     * ## 为什么要有这个版本（v2.4.5）
     * v2.4.4 之前，失败一律塌缩成「空列表」→ 编排层只能显示
     * 「模型未返回可用弹幕」这种**笼统且常常错误**的提示。
     * 实测事故：日志里是 `HTTP 401 Invalid bearer token`（密钥无效），
     * 面板却提示「模型未返回可用弹幕」—— 用户会去检查模型名/网络，**方向全错**。
     *
     * 现在把 [ChatErrorKind] 一路带到 UI，让用户看到**真实原因**。
     *
     * @param frame   画面帧（ARGB_8888，行序**已翻正**）。仅台词模式下可传 null
     * @param subtitleText 当前播放位置附近的台词（已拼成纯文本）。无台词/不用台词时传空串
     * @param config  弹幕配置（端点/模型/密钥/人格/批量/图像参数/素材来源）
     * @return [DanmuFetchResult]：`kind == NONE` 表示成功
     */
    suspend fun fetchDanmu(
        frame: Bitmap?,
        config: DanmuConfig,
        subtitleText: String = ""
    ): DanmuFetchResult = withContext(Dispatchers.IO) {
        if (!config.isReadyToRequest()) {
            Log.w(TAG, "配置不完整，跳过视觉请求（apiKey/baseUrl/model 需齐全）")
            return@withContext DanmuFetchResult(emptyList(), ChatErrorKind.NOT_CONFIGURED)
        }

        // v2.4.2：先做 URL 语法粗筛。
        // ⚠️ 必要性：`https://` 打成 `hhttps://` 时，OkHttp 会在 Request.Builder.url()
        //    抛 IllegalArgumentException，被编排循环归为笼统的「意外错误」——
        //    用户完全看不出是 URL 拼错。这里提前拦下并给出**可操作**的日志。
        //    （编排层在调用前也会查一次并显示本地化提示；这里兜底防漏。）
        val problem = config.baseUrlProblem()
        if (problem != null) {
            Log.e(TAG, "Base URL 不合法（$problem）: '${config.baseUrl.trim()}' —— 需要形如 https://host/v1")
            return@withContext DanmuFetchResult(emptyList(), ChatErrorKind.BAD_URL, problem.name)
        }

        val endpoint = config.resolveEndpoint()
        if (endpoint.isBlank()) {
            Log.w(TAG, "端点为空，跳过视觉请求")
            return@withContext DanmuFetchResult(emptyList(), ChatErrorKind.BAD_URL)
        }


        // ① 编码（仅画面模式需要；CPU 密集，已在 IO 线程）
        //    ⚠️ 仅台词模式**完全跳过编码**，省掉 JPEG 压缩与 base64（那是耗时大头）
        val dataUrl = if (config.needsImage) {
            if (frame == null) {
                Log.w(TAG, "需要画面但帧为 null，跳过本次请求")
                return@withContext DanmuFetchResult(emptyList(), ChatErrorKind.NO_CONTENT, "frame=null")
            }
            encodeFrameToDataUrl(frame, config)
        } else {
            null
        }
        if (config.needsImage && dataUrl == null) {
            Log.e(TAG, "帧编码失败")
            return@withContext DanmuFetchResult(emptyList(), ChatErrorKind.NO_CONTENT, "encode failed")
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
        //    ⚠️ v2.4.5：**必须把 kind 带回去**。此前这里失败只 `Log.e` 后返回空列表，
        //       编排层拿不到原因 → UI 只能显示笼统的「模型未返回可用弹幕」。
        //       实测事故：日志是 `HTTP 401 Invalid bearer token`，UI 却说「模型未返回可用弹幕」，
        //       用户会去查模型名/网络，**方向全错**。
        when (val result = executeChat(request, config)) {
            is ChatResult.Success -> {
                val lines = parseDanmuLines(result.content, config.batchSize)
                Log.d(TAG, "视觉请求成功，解析出 ${lines.size} 条弹幕")
                if (lines.isEmpty()) {
                    // 拿到了 200 但一条也没解析出来 —— 这是**真的**"模型没返回可用内容"
                    // （例如模型只回了「好的」这类无弹幕内容，或全被清洗规则过滤）
                    Log.w(TAG, "响应成功但解析为空。原始内容前 200 字: ${result.content.take(200)}")
                    DanmuFetchResult(emptyList(), ChatErrorKind.NO_CONTENT, result.content.take(200))
                } else {
                    DanmuFetchResult(lines, ChatErrorKind.NONE)
                }
            }
            is ChatResult.Failure -> {
                Log.e(TAG, "视觉请求失败（${result.kind}）: ${result.detail}")
                DanmuFetchResult(emptyList(), result.kind, result.detail)
            }
        }
    }

    /**
     * 执行一次 chat/completions 请求，把结果**结构化**返回（v2.4.4 新抽）。
     *
     * ## 为什么抽出来
     * 原来「发送 + 判 status + 取 content」全内联在 [requestDanmu] 里，导致：
     * ① 错误只有一句 `Log.e`，**无法在 UI 上得知失败原因**（用户必须翻 logcat）；
     * ② 无法复用（测试连接按钮需要同一套逻辑）。
     *
     * 现在把「网络 + 状态码 + 解析」收在这里，[requestDanmu] 与 [testConnection] 共用，
     * 保证**测试连接走的就是真实请求路径**（否则测通了也可能真跑不通）。
     */
    private fun executeChat(request: Request, config: DanmuConfig): ChatResult {
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // ⚠️ 不要把 response body 直接打全（可能很大），只打 code + 前 300 字符
                    val snippet = try {
                        response.body?.string()?.take(300)
                    } catch (e: Exception) {
                        null
                    }
                    return ChatResult.Failure(
                        kind = classifyHttpCode(response.code),
                        detail = "HTTP ${response.code} ${response.message} | $snippet"
                    )
                }

                val bodyStr = response.body?.string()
                if (bodyStr.isNullOrBlank()) {
                    return ChatResult.Failure(ChatErrorKind.EMPTY_RESPONSE, "返回空 body")
                }

                val content = extractMessageContent(bodyStr)
                if (content.isNullOrBlank()) {
                    return ChatResult.Failure(
                        ChatErrorKind.NO_CONTENT,
                        "响应中没有解析到 content: ${bodyStr.take(200)}"
                    )
                }
                ChatResult.Success(content)
            }
        } catch (e: Exception) {
            // 网络异常/超时/JSON 解析失败
            // ⚠️ v2.4.4：`StreamResetException`（HTTP/2 RST_STREAM）单独归类。
            //    实测该异常最常见的成因是**模型名不被识别**——网关不返回 404 JSON，
            //    而是直接复位 h2 流。若不单独提示，用户只会看到笼统的"网络异常"。
            ChatResult.Failure(classifyException(e), "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * v2.4.4：把 HTTP 状态码映射为可展示的错误类别。
     */
    private fun classifyHttpCode(code: Int): ChatErrorKind = when (code) {
        401, 403 -> ChatErrorKind.AUTH
        404 -> ChatErrorKind.MODEL_NOT_FOUND
        429 -> ChatErrorKind.RATE_LIMIT
        in 500..599 -> ChatErrorKind.SERVER_ERROR
        else -> ChatErrorKind.HTTP_ERROR
    }

    /**
     * v2.4.4：把异常映射为可展示的错误类别。
     *
     * ⚠️ `StreamResetException` 是 `okhttp3.internal.http2` 里的内部类，
     *    直接 import 会依赖内部 API（本项目为 K2，可能报 Unresolved）。
     *    → 用**类名判断**，稳妥。
     */
    private fun classifyException(e: Exception): ChatErrorKind {
        if (e is java.net.SocketTimeoutException) return ChatErrorKind.TIMEOUT
        if (e is java.net.UnknownHostException) return ChatErrorKind.DNS
        if (e is java.net.ConnectException) return ChatErrorKind.CONNECT
        val name = e.javaClass.simpleName
        if (name.contains("StreamReset") || name.contains("StreamResetException")) {
            return ChatErrorKind.STREAM_RESET
        }
        return ChatErrorKind.NETWORK
    }

    /**
     * v2.4.4：**测试连接** —— 发一个最小请求，返回可展示的结果。
     *
     * 设计目的：本次（v2.4.3→v2.4.4）排查「弹幕完全不出现」时，先后踩了
     * ① URL 拼错（`hhttps://`）、② 模型名大小写错（`MiMo-V2.6-Flash` vs `mimo-v2.6-flash`），
     * 两者都**只能靠翻 logcat** 才发现。加这个方法后，用户点一下就能看到原因。
     *
     * ⚠️ **走的是与真实弹幕请求完全相同的代码路径**（[executeChat]），
     *    只把内容换成"请回复 OK"以最小化耗时与 token。
     * ⚠️ **不含图片**：测试连接只验证 端点/密钥/模型名 三件事，
     *    不验证视觉能力（那需要真的截一张图，代价大）。
     *    模型不支持视觉时，正式请求会失败并显示 [ChatErrorKind.NO_CONTENT]/[ChatErrorKind.SERVER_ERROR]。
     *
     * @return 供 UI 展示的结果
     */
    suspend fun testConnection(config: DanmuConfig): ConnectionTestResult = withContext(Dispatchers.IO) {
        if (!config.isReadyToRequest()) {
            return@withContext ConnectionTestResult(
                ok = false, kind = ChatErrorKind.NOT_CONFIGURED,
                message = "配置不完整（需要 Base URL / 模型名 / API Key）"
            )
        }
        val problem = config.baseUrlProblem()
        if (problem != null) {
            return@withContext ConnectionTestResult(
                ok = false, kind = ChatErrorKind.BAD_URL,
                message = "Base URL 不合法（$problem）：'${config.baseUrl.trim()}'"
            )
        }

        val endpoint = config.resolveEndpoint()
        val bodyJson = JSONObject().apply {
            put("model", config.modelName.trim())
            put("temperature", 0.0)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "请只回复两个字：可用")
                })
            })
        }
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        when (val r = executeChat(request, config)) {
            is ChatResult.Success -> ConnectionTestResult(
                ok = true, kind = ChatErrorKind.NONE,
                message = "连接成功，模型已响应", sample = r.content.trim().take(60)
            )
            is ChatResult.Failure -> ConnectionTestResult(
                ok = false, kind = r.kind, message = r.detail.take(300)
            )
        }
    }

    /** v2.4.4：一次 chat 请求的结果 */
    private sealed interface ChatResult {
        data class Success(val content: String) : ChatResult
        data class Failure(val kind: ChatErrorKind, val detail: String) : ChatResult
    }

    /**
     * v2.4.4：弹幕请求的失败类别。
     *
     * 每个类别都对应**一条可操作的修复建议**（见 UI 层文案），
     * 而不是让用户对着 `INTERNAL_ERROR` 猜。
     */
    enum class ChatErrorKind {
        /** 成功 */
        NONE,
        /** 配置不完整（baseUrl/model/key 缺） */
        NOT_CONFIGURED,
        /** Base URL 语法错误（`hhttps://` 之类） */
        BAD_URL,
        /** HTTP 401/403：密钥无效或过期 */
        AUTH,
        /** HTTP 404：**模型名不被识别**（v2.4.4 头号坑：大小写敏感） */
        MODEL_NOT_FOUND,
        /** HTTP 429：限流/额度不足 */
        RATE_LIMIT,
        /** HTTP 5xx */
        SERVER_ERROR,
        /** 其他非 2xx */
        HTTP_ERROR,
        /** HTTP/2 流被复位（RST_STREAM）—— 常见成因即上面的 MODEL_NOT_FOUND */
        STREAM_RESET,
        /** DNS 解析失败 */
        DNS,
        /** 连接被拒 */
        CONNECT,
        /** 超时 */
        TIMEOUT,
        /** 其他网络异常 */
        NETWORK,
        /** 2xx 但没解析出内容 */
        NO_CONTENT,
        /** 2xx 但 body 为空 */
        EMPTY_RESPONSE
    }

    /**
     * v2.4.4：测试连接的结果（供 UI 直接展示）。
     *
     * @param ok      是否通过
     * @param kind    失败类别（成功为 [ChatErrorKind.NONE]）
     * @param message 可展示的详情
     * @param sample  成功时模型回的内容片段（证明真的通了）
     */
    data class ConnectionTestResult(
        val ok: Boolean,
        val kind: ChatErrorKind,
        val message: String,
        val sample: String = ""
    )

    /**
     * v2.4.5：一次弹幕请求的结果（**带失败原因**）。
     *
     * ## 为什么需要它
     * v2.4.4 及之前，[requestDanmu] 失败一律返回空列表 → 编排层无法区分
     * 「模型真的没返回可用内容」与「401 密钥无效 / 404 模型名错 / 连接被重置」，
     * 只能显示笼统的「模型未返回可用弹幕」。
     *
     * 实测事故（v2.4.4 发布后）：日志里明明是
     * `视觉请求失败（AUTH）: HTTP 401 | {"detail":"Invalid bearer token"}`
     * —— **密钥无效**，但面板显示「模型未返回可用弹幕」，
     * 用户会去查模型名与网络，**排查方向完全错**。
     *
     * @param lines  解析出的弹幕（失败时为空）
     * @param kind   结果类别；[ChatErrorKind.NONE] 表示成功
     * @param detail 原始失败详情（HTTP code / body 片段 / 异常信息），供诊断
     */
    data class DanmuFetchResult(
        val lines: List<String>,
        val kind: ChatErrorKind,
        val detail: String = ""
    ) {
        /** 是否成功（拿到了至少一条弹幕） */
        val isSuccess: Boolean get() = kind == ChatErrorKind.NONE && lines.isNotEmpty()
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
            val scaled = scaleDown(frame, config.imageMaxLongSide)
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
     * 按**长边**等比缩放到 [maxLongSide] 以内（v2.4.11，原为「按最大宽度」）。
     *
     * ⚠️ 尺寸计算走 [ImageScale.fitLongSide] —— 与 `VRGLRenderer` 的 GL 回读尺寸
     *    **共用同一套**。两处各写一份必然不一致（竖屏片源下回读按宽、这里按长边，
     *    或反之），属本项目「同一份数据两处登记」的老毛病。
     *
     * ⚠️ **只缩不放**：已经足够小则**原样返回**（不产生新对象，
     *    调用方据此判断是否需要回收）。放大不会带来更多信息，只会白涨 token。
     */
    private fun scaleDown(frame: Bitmap, maxLongSide: Int): Bitmap {
        val target = ImageScale.fitLongSide(frame.width, frame.height, maxLongSide)
        if (target[0] == frame.width && target[1] == frame.height) return frame
        return Bitmap.createScaledBitmap(frame, target[0], target[1], true)
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
