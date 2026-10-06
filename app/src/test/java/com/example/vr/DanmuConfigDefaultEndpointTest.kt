package com.example.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4.4 默认端点与模型名回归测试。
 *
 * ## 背景（真实事故，本轮排查的根因）
 * v2.4.3 及之前默认 `baseUrl = https://api.xiaomimimo.com/v1`、`model = mimo-v2.6-flash`。
 * 但用户实际使用的 **AMD Radeon 开发者平台**要求：
 * - 端点 `https://developer.amd.com.cn/radeon/api/v1`
 * - 模型 id **大小写敏感** `MiMo-V2.6-Flash`
 *
 * 实测同一端点下：
 * - `MiMo-V2.6-Flash` → HTTP 200 ✅
 * - `mimo-v2.6-flash` → HTTP 404 `model_not_found` ❌
 *
 * 而在 OkHttp 的 HTTP/2 通道下，这个 404 表现为
 * `StreamResetException: stream was reset: INTERNAL_ERROR` ——
 * **用户完全看不出是模型名写错**，排查代价极大。
 *
 * ## 本测试的作用
 * 把「默认值必须是这两个字符串」这件事**钉死**。任何人若把默认值改回小米官方
 * （或把小写模型名写回默认），这里会立刻失败并指向事故原因。
 *
 * ⚠️ 注意：这里只断言**默认值**，不断言用户自定义值（用户填什么是自由的）。
 */
class DanmuConfigDefaultEndpointTest {

    @Test
    fun `默认端点是 AMD Radeon 平台`() {
        assertEquals(
            "https://developer.amd.com.cn/radeon/api/v1",
            DanmuConfig.DEFAULT_BASE_URL
        )
    }

    @Test
    fun `默认模型名是大小写敏感的 MiMo-V2-6-Flash`() {
        // ⚠️ 这四个大写字母都不能改：M-i-M-o / V / F
        //    写成小写会得到 HTTP 404 model_not_found
        assertEquals("MiMo-V2.6-Flash", DanmuConfig.DEFAULT_MODEL)
    }

    @Test
    fun `默认模型名不是全小写（防回归）`() {
        // 显式断言：绝不能等于旧的小写写法
        assertTrue(
            "默认模型名不得为小写的 mimo-v2.6-flash（该写法在 AMD 平台返回 404）",
            DanmuConfig.DEFAULT_MODEL != "mimo-v2.6-flash"
        )
    }

    @Test
    fun `默认配置能拼出完整端点`() {
        val cfg = DanmuConfig()
        assertEquals(
            "https://developer.amd.com.cn/radeon/api/v1/chat/completions",
            cfg.resolveEndpoint()
        )
    }

    @Test
    fun `默认配置的 Base URL 语法合法`() {
        assertEquals(null, DanmuConfig().baseUrlProblem())
    }

    @Test
    fun `默认配置在填好 Key 后判定为可请求`() {
        val cfg = DanmuConfig(apiKey = "rc-test-key")
        assertTrue(cfg.isReadyToRequest())
    }

    @Test
    fun `默认素材来源为画面加台词`() {
        assertEquals(DanmuSourceMode.IMAGE_AND_SUBTITLE, DanmuConfig().sourceMode)
    }

    @Test
    fun `默认配置需要画面也需要台词`() {
        val cfg = DanmuConfig()
        assertTrue(cfg.needsImage)
        assertTrue(cfg.needsSubtitle)
    }

    // ===== 预设人格完整性（v2.4.2 引入，此处顺带保护）=====

    @Test
    fun `预设人格非空且 id 唯一`() {
        val presets = DanmuConfig.PERSONA_PRESETS
        assertTrue("预设人格不能为空", presets.isNotEmpty())
        val ids = presets.map { it.id }
        assertEquals("预设人格 id 不能重复", ids.size, ids.distinct().size)
    }

    @Test
    fun `默认人格提示词恰好命中第一个预设`() {
        // DEFAULT_PERSONA 必须与 PERSONA_PRESETS[0].prompt 完全一致，
        // 否则面板一进来就显示「自定义」（用户会困惑）
        assertEquals(
            DanmuConfig.PERSONA_PRESETS.first().id,
            DanmuConfig.matchPersonaPresetId(DanmuConfig.DEFAULT_PERSONA)
        )
    }

    @Test
    fun `改动一个字后不再命中任何预设`() {
        // ⚠️ 注意不能只用「加尾部空格」来构造差异 ——
        //    matchPersonaPresetId 内部会 trim()，尾部/首部空白**不算改动**
        //    （这是有意为之：用户手滑多打一个空格不该被判定为「自定义」）。
        val modified = DanmuConfig.DEFAULT_PERSONA.replace("观众", "观人")
        assertTrue("替换后确实变了", modified != DanmuConfig.DEFAULT_PERSONA)
        assertEquals(
            DanmuConfig.PERSONA_CUSTOM_ID,
            DanmuConfig.matchPersonaPresetId(modified)
        )
    }

    @Test
    fun `首尾空白不算改动仍命中原预设`() {
        // 与上一条互为对照：trim 后相等 → 仍算命中该预设
        val padded = "\n  " + DanmuConfig.DEFAULT_PERSONA + "  \n"
        assertEquals(
            DanmuConfig.PERSONA_PRESETS.first().id,
            DanmuConfig.matchPersonaPresetId(padded)
        )
    }

    @Test
    fun `每个预设都含 count 占位符`() {
        for (p in DanmuConfig.PERSONA_PRESETS) {
            assertTrue(
                "预设 ${p.id} 的提示词必须含 {count} 占位符",
                p.prompt.contains("{count}")
            )
        }
    }

    @Test
    fun `预设人格不含第三方角色名（版权红线）`() {
        // DanmuAI 的 14 个人格含受版权保护的角色名。本项目方案 C 的前提是
        // 「借鉴思路不复制表达」——这里做一道机械门禁。
        val forbidden = listOf("胡桃", "阿库娅", "银狼", "芙莉莲", "原神", "崩坏")
        for (p in DanmuConfig.PERSONA_PRESETS) {
            for (f in forbidden) {
                assertTrue(
                    "预设 ${p.id} 不得含第三方角色名「$f」",
                    !p.prompt.contains(f)
                )
            }
        }
    }

    // ===== 弹幕失败类别映射（v2.4.4 新增枚举）=====

    @Test
    fun `失败类别枚举包含关键的模型未找到与流复位`() {
        // 这两个是本轮事故的两个表现，必须有独立类别（不能混进笼统的 NETWORK）
        assertNotNull(DanmuVisionClient.ChatErrorKind.valueOf("MODEL_NOT_FOUND"))
        assertNotNull(DanmuVisionClient.ChatErrorKind.valueOf("STREAM_RESET"))
        assertNotNull(DanmuVisionClient.ChatErrorKind.valueOf("AUTH"))
        assertNotNull(DanmuVisionClient.ChatErrorKind.valueOf("BAD_URL"))
    }
}
