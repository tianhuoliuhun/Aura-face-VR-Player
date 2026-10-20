package com.example.vr

import com.example.vr.DanmuVisionClient.ChatErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * v2.6.8：`DanmuRetryPolicy` 语义测试。
 *
 * ## 背景
 * v2.6.7 及之前，AI 弹幕是「失败 → 记一句文案 → delay(periodMs)」的**固定周期无条件重试**：
 * 密钥错（AUTH）、模型名错（MODEL_NOT_FOUND）这类"改配置才能好"的错误也会按周期一直打，
 * 429 限流时更是持续加压。
 *
 * 这里锁住三条不可退化的语义：
 * 1. **致命类别必须被识别**（否则会退回"无限重试致命错误"）；
 * 2. **临时错误随失败次数增长，但有上限**（否则要么不减速、要么永远等下去）；
 * 3. **429 比普通错误更保守**（基数 ×3）。
 */
class DanmuRetryPolicyTest {

    private val period = 5_000L   // 5 秒

    @Test
    fun `致命类别只有改配置才能解决的四类`() {
        assertTrue(DanmuRetryPolicy.isFatal(ChatErrorKind.NOT_CONFIGURED))
        assertTrue(DanmuRetryPolicy.isFatal(ChatErrorKind.BAD_URL))
        assertTrue(DanmuRetryPolicy.isFatal(ChatErrorKind.AUTH))
        assertTrue(DanmuRetryPolicy.isFatal(ChatErrorKind.MODEL_NOT_FOUND))
    }

    @Test
    fun `临时错误与限流不属于致命`() {
        // 🔴 这几类都是"等一等可能就好了"，一旦被判致命就会退避到 5 分钟，
        //    等于把自愈能力砍掉 —— 必须守住。
        listOf(
            ChatErrorKind.RATE_LIMIT,
            ChatErrorKind.SERVER_ERROR,
            ChatErrorKind.HTTP_ERROR,
            ChatErrorKind.STREAM_RESET,
            ChatErrorKind.DNS,
            ChatErrorKind.CONNECT,
            ChatErrorKind.TIMEOUT,
            ChatErrorKind.NETWORK,
            ChatErrorKind.NO_CONTENT,
            ChatErrorKind.EMPTY_RESPONSE
        ).forEach {
            assertFalse("$it 不应被判为致命", DanmuRetryPolicy.isFatal(it))
        }
    }

    @Test
    fun `致命错误退避固定为五分钟`() {
        val kind = ChatErrorKind.AUTH
        assertEquals(DanmuRetryPolicy.FATAL_RETRY_MS, DanmuRetryPolicy.baseDelayMs(period, 1, kind))
        // 失败次数不影响它：用户改完配置前，间隔保持同一档
        assertEquals(DanmuRetryPolicy.FATAL_RETRY_MS, DanmuRetryPolicy.baseDelayMs(period, 9, kind))
    }

    @Test
    fun `临时错误按失败次数指数增长`() {
        val kind = ChatErrorKind.TIMEOUT
        assertEquals(5_000L, DanmuRetryPolicy.baseDelayMs(period, 1, kind))
        assertEquals(10_000L, DanmuRetryPolicy.baseDelayMs(period, 2, kind))
        assertEquals(20_000L, DanmuRetryPolicy.baseDelayMs(period, 3, kind))
        assertEquals(40_000L, DanmuRetryPolicy.baseDelayMs(period, 4, kind))
    }

    @Test
    fun `指数增长封顶六十秒`() {
        val kind = ChatErrorKind.SERVER_ERROR
        // 5s → 40s 之后继续翻倍会到 80s，必须被 MAX_DELAY_MS 截断
        assertEquals(DanmuRetryPolicy.MAX_DELAY_MS, DanmuRetryPolicy.baseDelayMs(period, 5, kind))
        // 失败次数极大时也不能溢出成负数
        assertEquals(DanmuRetryPolicy.MAX_DELAY_MS, DanmuRetryPolicy.baseDelayMs(period, 80, kind))
    }

    @Test
    fun `限流比普通错误更保守`() {
        val timeout = DanmuRetryPolicy.baseDelayMs(period, 1, ChatErrorKind.TIMEOUT)
        val rate = DanmuRetryPolicy.baseDelayMs(period, 1, ChatErrorKind.RATE_LIMIT)
        assertEquals(period * DanmuRetryPolicy.RATE_LIMIT_BASE_MULTIPLIER, rate)
        assertTrue("429 首档必须比普通错误长", rate > timeout)
    }

    @Test
    fun `抖动不会突破上下界`() {
        val kind = ChatErrorKind.NETWORK
        val base = DanmuRetryPolicy.baseDelayMs(period, 2, kind)
        val lo = (base * (1 - DanmuRetryPolicy.JITTER_RATIO)).toLong()
        val hi = (base * (1 + DanmuRetryPolicy.JITTER_RATIO)).toLong()
        repeat(200) {
            val d = DanmuRetryPolicy.nextDelayMs(period, 2, kind, Random(it))
            assertTrue("抖动越界：$d 不在 [$lo, $hi]", d in lo..hi)
        }
    }

    @Test
    fun `致命错误不加抖动`() {
        // 致命错误本来就稀疏（5 分钟一次），抖动只会让"改完配置多久恢复"不可预期
        val d = DanmuRetryPolicy.nextDelayMs(period, 3, ChatErrorKind.MODEL_NOT_FOUND, Random(7))
        assertEquals(DanmuRetryPolicy.FATAL_RETRY_MS, d)
    }

    @Test
    fun `倒计时秒数向上取整且不为零`() {
        assertEquals(1, DanmuRetryPolicy.delaySeconds(1))
        assertEquals(5, DanmuRetryPolicy.delaySeconds(5_000))
        assertEquals(6, DanmuRetryPolicy.delaySeconds(5_400))   // 5.4s → 6s，不能显示 5s 后其实没重试
        assertEquals(300, DanmuRetryPolicy.delaySeconds(300_000))
    }
}
