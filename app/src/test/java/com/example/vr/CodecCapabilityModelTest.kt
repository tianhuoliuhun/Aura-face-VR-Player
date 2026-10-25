package com.example.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CodecCapabilityModel] 的纯逻辑单测。
 *
 * 这些断言防的都是**静默失真**：档位算错会让「最高硬解」虚报 8K，
 * 码率/帧率格式化写错会让用户拿到看不懂的报告，
 * 容器分组错了会出现「能播但检测页说不能播」。
 */
class CodecCapabilityModelTest {

    // ===================== 分辨率档位 =====================

    @Test
    fun `tier 8k exact`() {
        assertEquals("8K", CodecCapabilityModel.tierName(7680, 4320))
    }

    @Test
    fun `tier dci 4k counts as 4k`() {
        // 4096×2160 是 DCI 4K，像素数大于 3840×2160 → 必须也判 4K
        assertEquals("4K", CodecCapabilityModel.tierName(4096, 2160))
    }

    @Test
    fun `tier uhd 4k`() {
        assertEquals("4K", CodecCapabilityModel.tierName(3840, 2160))
    }

    @Test
    fun `tier 5k`() {
        assertEquals("5K", CodecCapabilityModel.tierName(5120, 2880))
    }

    @Test
    fun `tier 1440p is 2k`() {
        assertEquals("2K", CodecCapabilityModel.tierName(2560, 1440))
    }

    @Test
    fun `tier below 360 is sd`() {
        assertEquals("SD", CodecCapabilityModel.tierName(320, 240))
    }

    @Test
    fun `resolution text uses multiply sign and dash when invalid`() {
        assertEquals("7680×4320", CodecCapabilityModel.resolutionText(7680, 4320))
        assertEquals("—", CodecCapabilityModel.resolutionText(0, 2160))
    }

    @Test
    fun `resolution with tier combines both`() {
        assertEquals("3840×2160 (4K)", CodecCapabilityModel.resolutionWithTier(3840, 2160))
    }

    // ===================== 码率 / 帧率格式化 =====================

    @Test
    fun `bitrate switches unit by magnitude`() {
        assertEquals("160.0 Mbps", CodecCapabilityModel.bitrateText(160_000_000))
        assertEquals("640 kbps", CodecCapabilityModel.bitrateText(640_000))
        assertEquals("512 bps", CodecCapabilityModel.bitrateText(512))
        assertEquals("—", CodecCapabilityModel.bitrateText(0))
    }

    @Test
    fun `bitrate range drops redundant low bound`() {
        assertEquals("160.0 Mbps", CodecCapabilityModel.bitrateRangeText(0, 160_000_000))
        assertEquals("1.0 Mbps – 160.0 Mbps", CodecCapabilityModel.bitrateRangeText(1_000_000, 160_000_000))
        assertEquals("—", CodecCapabilityModel.bitrateRangeText(0, 0))
    }

    @Test
    fun `fps prints integer without decimal`() {
        assertEquals("30 fps", CodecCapabilityModel.fpsText(30.0))
        assertEquals("29.9 fps", CodecCapabilityModel.fpsText(29.94))
        assertEquals("—", CodecCapabilityModel.fpsText(0.0))
    }

    @Test
    fun `fps treats sentinel as unknown`() {
        // 部分驱动返回 1e6 表示「不限」，直接展示会误导
        assertEquals("—", CodecCapabilityModel.fpsText(1_000_000.0))
        assertEquals("—", CodecCapabilityModel.fpsRangeText(0.0, 1_000_000.0))
    }

    // ===================== MIME =====================

    @Test
    fun `known mime gets friendly name`() {
        assertEquals("H.265 / HEVC", CodecCapabilityModel.mimeLabel("video/hevc"))
        assertEquals("AV1", CodecCapabilityModel.mimeLabel("video/av01"))
        assertEquals("AAC", CodecCapabilityModel.mimeLabel("audio/mp4a-latm"))
    }

    @Test
    fun `unknown mime is echoed not hidden`() {
        // 新编码出来时，用户至少要能看到 MIME 原文
        assertEquals("video/x-future", CodecCapabilityModel.mimeLabel("video/x-future"))
    }

    @Test
    fun `mime label is case insensitive`() {
        assertEquals("H.264 / AVC", CodecCapabilityModel.mimeLabel("VIDEO/AVC"))
    }

    // ===================== 色彩格式 =====================

    @Test
    fun `surface color format name`() {
        assertEquals("Surface", CodecCapabilityModel.colorFormatName(0x7F000789))
    }

    @Test
    fun `flexible color format name`() {
        assertEquals("YUV420 Flexible", CodecCapabilityModel.colorFormatName(0x7F420888))
    }

    @Test
    fun `unknown color format falls back to hex`() {
        assertEquals("0x7a", CodecCapabilityModel.colorFormatName(122))
    }

    @Test
    fun `color formats text truncates long lists`() {
        val text = CodecCapabilityModel.colorFormatsText(listOf(19, 21, 0x7F420888, 39, 54))
        assertTrue(text.startsWith("YUV420 Planar / YUV420 SemiPlanar"))
        assertTrue(text.endsWith("…"))
    }

    // ===================== 最佳硬解 =====================

    private fun video(
        w: Int, h: Int, tierW: Int, tierH: Int, tierFps: Double
    ) = CodecCapabilityModel.VideoCaps(
        minWidth = 16, maxWidth = w, minHeight = 16, maxHeight = h,
        widthAlign = 2, heightAlign = 2,
        minFps = 0.0, maxFps = tierFps,
        minBitrate = 0, maxBitrate = 100_000_000,
        colorFormats = listOf(21), profiles = listOf("Main"),
        secure = true, adaptive = true, tunneled = false, maxInstances = 4,
        tierWidth = tierW, tierHeight = tierH, tierMaxFps = tierFps
    )

    private fun entry(
        name: String, mime: String, hw: Boolean, enc: Boolean = false,
        v: CodecCapabilityModel.VideoCaps? = null
    ) = CodecCapabilityModel.CodecEntry(
        name = name, mime = mime, encoder = enc, hardware = hw, vendor = true, video = v
    )

    private fun report(entries: List<CodecCapabilityModel.CodecEntry>) =
        CodecCapabilityModel.CodecReport(
            device = CodecCapabilityModel.DeviceInfo(
                model = "Test", androidRelease = "14", sdk = 34,
                soc = "test", abi = "arm64-v8a", currentEngine = "EXO", mpvInstalled = true
            ),
            entries = entries,
            containers = emptyList()
        )

    @Test
    fun `best hardware decoder prefers biggest tier`() {
        val r = report(
            listOf(
                entry("c2.android.hevc.decoder", "video/hevc", hw = false, v = video(4096, 2160, 4096, 2160, 60.0)),
                entry("c2.qti.avc.decoder", "video/avc", hw = true, v = video(4096, 2160, 3840, 2160, 60.0)),
                entry("c2.qti.hevc.decoder", "video/hevc", hw = true, v = video(8192, 8192, 7680, 4320, 30.0))
            )
        )
        val best = CodecCapabilityModel.bestHardwareDecoder(r)
        assertEquals("c2.qti.hevc.decoder", best?.name)
        assertEquals("7680×4320 @ 30 fps", CodecCapabilityModel.tierSummary(best))
    }

    @Test
    fun `best hardware decoder ignores encoders`() {
        val r = report(
            listOf(
                entry("c2.qti.hevc.encoder", "video/hevc", hw = true, enc = true, v = video(8192, 8192, 7680, 4320, 60.0)),
                entry("c2.qti.avc.decoder", "video/avc", hw = true, v = video(4096, 2160, 3840, 2160, 30.0))
            )
        )
        assertEquals("c2.qti.avc.decoder", CodecCapabilityModel.bestHardwareDecoder(r)?.name)
    }

    @Test
    fun `best hardware decoder null when only software`() {
        val r = report(
            listOf(entry("c2.android.avc.decoder", "video/avc", hw = false, v = video(3840, 2160, 3840, 2160, 30.0)))
        )
        assertNull(CodecCapabilityModel.bestHardwareDecoder(r))
        assertEquals("—", CodecCapabilityModel.tierSummary(null))
    }

    // ===================== 容器分组 =====================

    @Test
    fun `containers with same ability are merged`() {
        fun g(ext: String, exo: Boolean, ijk: Boolean, mpv: Boolean) =
            ext to CodecCapabilityModel.ContainerGroup(ext, listOf(ext), exo, ijk, mpv)

        val groups = CodecCapabilityModel.groupContainers(
            listOf(
                g("mp4", true, true, true),
                g("mkv", true, true, true),
                g("wmv", false, false, true)
            )
        )
        assertEquals(2, groups.size)
        // 三内核全通的那组在前（排序按 exo 优先），且成员被合并
        assertEquals(listOf("mkv", "mp4"), groups[0].exts)
        assertTrue(groups[0].exo && groups[0].ijk && groups[0].mpv)
        assertEquals(listOf("wmv"), groups[1].exts)
        assertTrue(!groups[1].exo && !groups[1].ijk && groups[1].mpv)
    }

    @Test
    fun `mpv only group is not playable without mpv`() {
        val group = CodecCapabilityModel.ContainerGroup("WMV / ASF", listOf("wmv"), false, false, true)
        assertTrue(!group.playable(mpvReady = false))
        assertTrue(group.playable(mpvReady = true))
    }

    // ===================== 报告文本 =====================

    @Test
    fun `report text contains summary and container lines`() {
        val group = CodecCapabilityModel.ContainerGroup("MKV / WebM 等 2 种", listOf("mkv", "webm"), true, true, true)
        val r = CodecCapabilityModel.CodecReport(
            device = CodecCapabilityModel.DeviceInfo(
                model = "MuMu", androidRelease = "12", sdk = 32,
                soc = "arm64", abi = "arm64-v8a", currentEngine = "EXO", mpvInstalled = false
            ),
            entries = listOf(
                entry("c2.qti.hevc.decoder", "video/hevc", hw = true, v = video(8192, 8192, 7680, 4320, 30.0))
            ),
            containers = listOf(group)
        )
        val text = CodecCapabilityModel.buildReportText(r)
        assertTrue(text.contains("设备: MuMu"))
        assertTrue(text.contains("最高硬件解码: 7680×4320 @ 30 fps"))
        assertTrue(text.contains("mkv,webm  ->  EXO/IJK/MPV"))
        assertTrue(text.contains("c2.qti.hevc.decoder [video/hevc] HW"))
        // 不能出现异常的小数点逗号格式（Locale 陷阱）
        assertTrue(!text.contains("30,0"))
    }
}
