package com.example.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenRouter `:free` 池挑选逻辑的单测（v2.6.11）。
 *
 * 这些断言守的是**「池」能不能真的兜底**：
 * 顺序错 → 每次都在同一个坏模型上死磕；
 * 去重漏 → 「换一个模型重试」变成原地重试；
 * 冷却过滤漏 → 明知限流还去打，把延迟翻倍。
 */
class OpenRouterModelPoolTest {

    // ---------- isFreeModel ----------

    @Test
    fun `free suffix is recognised`() {
        assertTrue(OpenRouterModelPool.isFreeModel("google/gemma-4-31b-it:free"))
        assertTrue(OpenRouterModelPool.isFreeModel("  meta-llama/llama-3.3-70b:free  "))
        assertTrue(OpenRouterModelPool.isFreeModel("vendor/model:FREE"))
    }

    @Test
    fun `paid or malformed ids are rejected`() {
        assertFalse(OpenRouterModelPool.isFreeModel("openai/gpt-4o"))
        assertFalse(OpenRouterModelPool.isFreeModel("openai/gpt-4o:freebie"))
        assertFalse(OpenRouterModelPool.isFreeModel(""))
        assertFalse(OpenRouterModelPool.isFreeModel("free"))
    }

    // ---------- isLikelyTranslatable ----------

    @Test
    fun `code and safety models are not preferred`() {
        // 判据只看名字关键词 —— OpenRouter 元信息没有统一的「用途」字段
        assertFalse(OpenRouterModelPool.isLikelyTranslatable("acme/code-llama:free"))
        assertFalse(OpenRouterModelPool.isLikelyTranslatable("cohere/north-mini-code:free"))
        assertFalse(OpenRouterModelPool.isLikelyTranslatable("nvidia/nemotron-3.5-content-safety:free"))
        assertTrue(OpenRouterModelPool.isLikelyTranslatable("google/gemma-4-31b-it:free"))
    }

    // ---------- rankCandidates ----------

    @Test
    fun `rank keeps order and drops duplicates`() {
        val out = OpenRouterModelPool.rankCandidates(
            listOf(" b:free", "a:free", "b:free", "")
        )
        assertEquals(listOf("b:free", "a:free"), out)
    }

    @Test
    fun `rank filters unsuited models`() {
        val out = OpenRouterModelPool.rankCandidates(
            listOf("x/coder-7b:free", "google/gemma-4-31b-it:free", "y/guard:free")
        )
        assertEquals(listOf("google/gemma-4-31b-it:free"), out)
    }

    @Test
    fun `rank falls back when everything is filtered out`() {
        // 宁可试一个不合适的，也不要返回空池（空池 = 整片翻不出来）
        val onlyBad = listOf("x/coder-7b:free", "y/guard:free")
        assertEquals(onlyBad, OpenRouterModelPool.rankCandidates(onlyBad))
    }

    @Test
    fun `rank handles empty input`() {
        assertEquals(emptyList<String>(), OpenRouterModelPool.rankCandidates(emptyList()))
        assertEquals(emptyList<String>(), OpenRouterModelPool.rankCandidates(listOf("  ", "")))
    }

    // ---------- splitUserModels ----------

    @Test
    fun `user models split on mixed separators`() {
        val out = OpenRouterModelPool.splitUserModels(
            "a:free, b:free，c:free; d:free\n e:free\tf:free"
        )
        assertEquals(
            listOf("a:free", "b:free", "c:free", "d:free", "e:free", "f:free"),
            out
        )
    }

    @Test
    fun `blank user input yields no models`() {
        assertEquals(emptyList<String>(), OpenRouterModelPool.splitUserModels("   "))
    }

    // ---------- buildAttemptOrder ----------

    @Test
    fun `user models win over seed pool`() {
        val order = OpenRouterModelPool.buildAttemptOrder(
            "mine/model:free",
            listOf("discovered/model:free")
        )
        assertEquals(listOf("mine/model:free"), order)
    }

    @Test
    fun `seed pool comes first then discovered`() {
        val order = OpenRouterModelPool.buildAttemptOrder(
            "",
            listOf("discovered/a:free", "discovered/b:free")
        )
        assertEquals(OpenRouterModelPool.SEED_MODELS.size + 2, order.size)
        assertEquals(OpenRouterModelPool.SEED_MODELS.first(), order.first())
        assertEquals("discovered/a:free", order[OpenRouterModelPool.SEED_MODELS.size])
    }

    @Test
    fun `seed and discovered overlap is deduplicated`() {
        // 发现结果常会把精选池里那几个也报回来 —— 不去重就会「换模型」换到同一个
        val overlap = OpenRouterModelPool.SEED_MODELS.first()
        val order = OpenRouterModelPool.buildAttemptOrder("", listOf(overlap))
        assertEquals(order.size, order.distinct().size)
        assertEquals(OpenRouterModelPool.SEED_MODELS.size, order.size)
    }

    @Test
    fun `all seed models are free`() {
        // 防回归：SEED_MODELS 里绝不能出现付费模型（会直接产生费用）
        OpenRouterModelPool.SEED_MODELS.forEach { model ->
            assertTrue("种子模型必须带 :free 后缀 → $model", OpenRouterModelPool.isFreeModel(model))
        }
    }

    // ---------- availableNow / earliestRecoveryInMs ----------

    @Test
    fun `cooling model is skipped`() {
        val cooldown = mapOf("a:free" to 2_000L)
        val picked = OpenRouterModelPool.availableNow(
            listOf("a:free", "b:free"),
            nowMs = 1_000L,
            cooldownUntil = { cooldown[it] ?: 0L }
        )
        assertEquals("b:free", picked)
    }

    @Test
    fun `all cooling yields null`() {
        val picked = OpenRouterModelPool.availableNow(
            listOf("a:free", "b:free"),
            nowMs = 1_000L,
            cooldownUntil = { 5_000L }
        )
        assertNull(picked)
    }

    @Test
    fun `earliest recovery reports shortest wait`() {
        val cooldown = mapOf("a:free" to 9_000L, "b:free" to 4_000L)
        val left = OpenRouterModelPool.earliestRecoveryInMs(
            listOf("a:free", "b:free"),
            nowMs = 1_000L,
            cooldownUntil = { cooldown[it] ?: 0L }
        )
        assertEquals(3_000L, left)
    }

    @Test
    fun `earliest recovery is zero when nothing is cooling`() {
        val left = OpenRouterModelPool.earliestRecoveryInMs(
            listOf("a:free"),
            nowMs = 1_000L,
            cooldownUntil = { 0L }
        )
        assertEquals(0L, left)
    }
}
