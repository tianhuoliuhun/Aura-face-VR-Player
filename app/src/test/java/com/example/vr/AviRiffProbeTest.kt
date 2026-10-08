package com.example.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4.9：AVI 索引表探测的单测。
 *
 * 被测判据决定「要不要为这个 AVI 重建索引」，判错的两个方向都有代价：
 * - **该修没修** → 用户继续看到「跳到那一帧就不动了」（本次要解决的核心症状）；
 * - **不该修却修** → 白白读+写一整份文件（重封装虽不重编码，大文件仍要数十秒）。
 *
 * ⚠️ 用例全部用**手工构造的真实 RIFF 字节**，连 chunk 对齐这类易错处一并覆盖。
 * ⚠️ 本测试**刻意不含任何编码/AV1 相关用例** —— 该逻辑已移除，见 `AviRiffProbe` 类注释。
 */
class AviRiffProbeTest {

    // ===================================================================
    // 测试替身：按 RIFF 规范拼字节
    // ===================================================================

    private fun le32(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte()
    )

    /** RIFF 规定 chunk 载荷按偶数字节对齐：奇数长度后补 1 字节。 */
    private fun pad(n: Int): ByteArray = if (n % 2 == 1) ByteArray(1) else ByteArray(0)

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    private fun chunk(id: String, payload: ByteArray): ByteArray =
        ascii(id) + le32(payload.size.toLong()) + payload + pad(payload.size)

    /** LIST chunk：`LIST<size:u32><type:4><payload>`；⚠️ size **包含** type 那 4 字节。 */
    private fun list(type: String, payload: ByteArray): ByteArray {
        val body = ascii(type) + payload
        return ascii("LIST") + le32(body.size.toLong()) + body + pad(body.size)
    }

    private fun riff(form: String, payload: ByteArray): ByteArray {
        val body = ascii(form) + payload
        return ascii("RIFF") + le32(body.size.toLong()) + body
    }

    /** 一个最小的 AVI 头（hdrl 里放一个空 strl 即可，本类不再解析流内编码）。 */
    private fun aviHeader(): ByteArray =
        riff("AVI ", list("hdrl", list("strl", chunk("strh", ByteArray(48)))))

    // ===================================================================
    // 一、容器识别
    // ===================================================================

    @Test
    fun `识别标准 AVI 容器`() {
        assertTrue(AviRiffProbe.parseHeader(aviHeader()).isAvi)
    }

    @Test
    fun `非 RIFF 数据不被认作 AVI`() {
        val mp4 = le32(32) + ascii("ftypisom") + ByteArray(32)
        assertFalse(AviRiffProbe.parseHeader(mp4).isAvi)
    }

    @Test
    fun `RIFF 但不是 AVI 形式（如 WAVE）不被认作 AVI`() {
        assertFalse(AviRiffProbe.parseHeader(riff("WAVE", chunk("fmt ", ByteArray(16)))).isAvi)
    }

    @Test
    fun `空数组与超短数组不崩`() {
        listOf(ByteArray(0), ByteArray(4), ByteArray(11)).forEach { b ->
            assertFalse("长度 ${b.size} 不应被判为 AVI", AviRiffProbe.parseHeader(b).isAvi)
        }
    }

    @Test
    fun `解析结果默认不含索引`() {
        // parseHeader 只判容器，索引由调用方另行探测后 copy 进去
        assertFalse(AviRiffProbe.parseHeader(aviHeader()).hasIndexChunk)
    }

    // ===================================================================
    // 二、idx1 索引检测
    // ===================================================================

    @Test
    fun `识别出合法的 idx1 索引`() {
        assertTrue(AviRiffProbe.containsIndexMarker(ascii("idx1") + le32(16) + ByteArray(16)))
    }

    @Test
    fun `多个条目的 idx1 也被识别`() {
        assertTrue(
            AviRiffProbe.containsIndexMarker(ascii("idx1") + le32(16L * 500) + ByteArray(16 * 500))
        )
    }

    @Test
    fun `没有 idx1 时返回 false`() {
        assertFalse(AviRiffProbe.containsIndexMarker(ByteArray(4096)))
    }

    @Test
    fun `idx1 后面大小不是 16 的倍数时视为巧合不认`() {
        // ⚠️ 防误判的关键：视频数据里完全可能偶然出现 "idx1" 四个字节
        assertFalse(
            "大小非 16 倍数应视为巧合",
            AviRiffProbe.containsIndexMarker(ascii("idx1") + le32(17) + ByteArray(64))
        )
    }

    @Test
    fun `idx1 后面大小为 0 时不认`() {
        assertFalse(AviRiffProbe.containsIndexMarker(ascii("idx1") + le32(0) + ByteArray(64)))
    }

    @Test
    fun `idx1 后面大小超出上限时不认`() {
        assertFalse(
            AviRiffProbe.containsIndexMarker(ascii("idx1") + le32(1L shl 40) + ByteArray(64))
        )
    }

    @Test
    fun `缓冲太短时不崩`() {
        listOf(ByteArray(0), ByteArray(3), ByteArray(7)).forEach { b ->
            assertFalse(AviRiffProbe.containsIndexMarker(b))
        }
    }

    @Test
    fun `非索引数据里出现 idx1 字面量不会被误判`() {
        // 模拟视频数据：一堆随机字节里偶然夹了 "idx1" 但后面不是合法大小
        val data = ByteArray(2048)
        val at = 1000
        ascii("idx1").copyInto(data, at)
        assertFalse(AviRiffProbe.containsIndexMarker(data))
    }

    @Test
    fun `真实 AVI 尾部缓冲能识别出索引`() {
        val movi = list("movi", ByteArray(4096))
        val idx1 = chunk("idx1", ByteArray(16 * 100))
        val whole = riff("AVI ", list("hdrl", aviHeader().drop(12).toByteArray()) + movi + idx1)
        val tail = whole.copyOfRange(maxOf(0, whole.size - AviRiffProbe.TAIL_BYTES), whole.size)
        assertTrue(AviRiffProbe.containsIndexMarker(tail))
    }

    @Test
    fun `真实 AVI 无 idx1 时尾部扫描返回 false`() {
        val movi = list("movi", ByteArray(64 * 1024))
        val whole = riff("AVI ", list("hdrl", ByteArray(64)) + movi)
        val tail = whole.copyOfRange(maxOf(0, whole.size - AviRiffProbe.TAIL_BYTES), whole.size)
        assertFalse(AviRiffProbe.containsIndexMarker(tail))
    }

    // ===================================================================
    // 三、组合语义（needsIndexRebuild）—— 本次改动的核心开关
    // ===================================================================

    @Test
    fun `AVI 无索引时标记为需要重建索引`() {
        val p = AviRiffProbe.Probe(isAvi = true, hasIndexChunk = false)
        assertTrue("这正是「跳到那一帧就不动了」的场景", p.needsIndexRebuild)
    }

    @Test
    fun `AVI 有索引时不需要重建索引`() {
        val p = AviRiffProbe.Probe(isAvi = true, hasIndexChunk = true)
        assertFalse(p.needsIndexRebuild)
    }

    @Test
    fun `非 AVI 不触发索引重建`() {
        // ⚠️ 关键防线：mp4/mkv 没有 idx1 是正常的，绝不能因此去重封装它们
        val p = AviRiffProbe.Probe(isAvi = false, hasIndexChunk = false)
        assertFalse("非 AVI 容器不该被这个判据命中", p.needsIndexRebuild)
    }

    @Test
    fun `常量值与测试预期一致`() {
        assertEquals(1024, AviRiffProbe.HEAD_BYTES)
        assertEquals(1024 * 1024, AviRiffProbe.TAIL_BYTES)
    }

    @Test
    fun `畸形 chunk 大小不导致崩溃或死循环`() {
        // 声明 1MB 但只给了 16 字节
        val bad = ascii("RIFF") + le32(1024 * 1024) + ascii("AVI ") + ByteArray(16)
        val p = AviRiffProbe.parseHeader(bad)
        assertTrue(p.isAvi)   // 容器头合法
    }
}
