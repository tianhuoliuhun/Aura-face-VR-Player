package com.example.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.8.3：HLS 主清单解析的回归测试。
 *
 * 覆盖三个曾经会**静默出错**的点：
 *  1. `CODECS="avc1.640028,mp4a.40.2"` 里的逗号 —— 直接按 `,` split 会让后面所有键错位，
 *     表现为"变体一个都解析不出来"（不报错，只是列表空）。
 *  2. 相对 URI —— 主清单里的变体地址几乎都是相对路径，`1080p/index.m3u8` 必须拼到
 *     清单自身所在目录，否则播放器 404。
 *  3. `#EXT-X-STREAM-INF` 与其 URI **分属两行** —— 中间可能夹注释，必须用"待消费"状态承接。
 *
 * 纯 JVM 测试：HlsPlaylist 不依赖 Android。
 */
class HlsPlaylistTest {

    private val master = """
        #EXTM3U
        #EXT-X-VERSION:6
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="中文",LANGUAGE="zh",DEFAULT=YES,URI="audio/zh.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",URI="audio/en.m3u8"
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="sub",NAME="中文",LANGUAGE="zh",URI="subs/zh.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=5200000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2",AUDIO="aud"
        1080p/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=1100000,RESOLUTION=854x480,CODECS="avc1.4d401f,mp4a.40.2"
        480p/index.m3u8
    """.trimIndent()

    private val base = "https://cdn.example.com/hls/abc/master.m3u8?sign=xyz"

    @Test
    fun parsesVariantsWithQuotedCommaInCodecs() {
        val m = HlsPlaylist.parseMaster(master, base)!!
        assertEquals(2, m.variants.size)

        // CODECS 的逗号不能把属性表拆坏：两个变体的 RESOLUTION 都必须读得到
        val low = m.variants.first { it.bandwidth == 1_100_000L }
        assertEquals("854x480", low.resolution)
        assertEquals("480P", low.qualityLabel)

        val high = m.variants.first { it.bandwidth == 5_200_000L }
        assertEquals("1920x1080", high.resolution)
        assertEquals("1080P", high.qualityLabel)
        // 引号内的整串要原样保留（含中间的逗号）
        assertEquals("avc1.640028,mp4a.40.2", high.codecs)
        assertEquals("aud", high.audioGroup)
    }

    @Test
    fun variantsSortedByBandwidthDescending() {
        val m = HlsPlaylist.parseMaster(master, base)!!
        assertTrue("最高码率必须排第一，用户点开默认就是最清楚的",
            m.variants[0].bandwidth > m.variants[1].bandwidth)
        assertEquals(5_200_000L, m.best()!!.bandwidth)
    }

    @Test
    fun resolvesRelativeUrisAgainstPlaylistUrl() {
        val m = HlsPlaylist.parseMaster(master, base)!!
        // 必须拼到 master.m3u8 所在目录（/hls/abc/），不能拼到站点根
        assertEquals("https://cdn.example.com/hls/abc/1080p/index.m3u8", m.variants[0].uri)
        assertEquals("https://cdn.example.com/hls/abc/480p/index.m3u8", m.variants[1].uri)
        // #EXT-X-MEDIA 的 URI 同样要绝对化
        assertEquals("https://cdn.example.com/hls/abc/subs/zh.m3u8", m.subtitleTracks.first().uri)
    }

    @Test
    fun mediaTracksSeparatedByType() {
        val m = HlsPlaylist.parseMaster(master, base)!!
        assertEquals(2, m.audioTracks.size)
        assertEquals(1, m.subtitleTracks.size)
        val sub = m.subtitleTracks.first()
        assertEquals("中文", sub.name)
        assertEquals("zh", sub.language)
        assertEquals("中文 (zh)", sub.label)
        assertTrue("AUDIO 轨不能混进字幕轨", m.subtitleTracks.none { it.type == "AUDIO" })
    }

    @Test
    fun closedCaptionsWithoutUriIsNotDownloadable() {
        // TYPE=CLOSED-CAPTIONS 规范上不允许有 URI（EIA-608 内嵌在视频流里）
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=CLOSED-CAPTIONS,GROUP-ID="cc",NAME="CC1"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            v.m3u8
        """.trimIndent()
        val m = HlsPlaylist.parseMaster(text, "https://x.com/a/master.m3u8")!!
        assertTrue("没有 URI 的轨不能当字幕源", m.subtitleTracks.isEmpty())
    }

    @Test
    fun absoluteAndProtocolRelativeUrisPassThrough() {
        assertEquals(
            "https://other.cdn.com/x.m3u8",
            HlsPlaylist.resolveUri(base, "https://other.cdn.com/x.m3u8"),
        )
        assertEquals(
            "https://other.cdn.com/x.m3u8",
            HlsPlaylist.resolveUri(base, "//other.cdn.com/x.m3u8"),
        )
        assertEquals(
            "https://cdn.example.com/root.m3u8",
            HlsPlaylist.resolveUri(base, "/root.m3u8"),
        )
    }

    @Test
    fun mediaPlaylistIsNotMaster() {
        val media = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.006,
            seg0.ts
        """.trimIndent()
        assertFalse(HlsPlaylist.isMaster(media))
        assertNull("媒体播放列表不该被当成主清单", HlsPlaylist.parseMaster(media, base))
    }

    @Test
    fun firstSegmentSkipsTagsAndComments() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.006,
            seg0.vtt
            #EXTINF:6.006,
            seg1.vtt
        """.trimIndent()
        // 分片是相对**字幕清单自身**（/hls/abc/subs/zh.m3u8）的，所以要落在 subs/ 下
        assertEquals(
            "https://cdn.example.com/hls/abc/subs/seg0.vtt",
            HlsPlaylist.firstSegment(media, "https://cdn.example.com/hls/abc/subs/zh.m3u8"),
        )
    }

    @Test
    fun parseSegmentsKeepsOrderAndDurations() {
        val media = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.006,
            seg0.vtt
            #EXTINF:5.994,
            seg1.vtt
        """.trimIndent()
        val segs = HlsPlaylist.parseSegments(media, "https://cdn.example.com/hls/abc/subs/zh.m3u8")
        assertEquals(2, segs.size)
        assertEquals("https://cdn.example.com/hls/abc/subs/seg0.vtt", segs[0].uri)
        // 6.006s 必须算出 6006ms，不能是 6005（Double 截断会少 1ms，600 片累计差 0.6 秒）
        assertEquals(6006L, segs[0].durationMs)
        assertEquals(5994L, segs[1].durationMs)
    }

    @Test
    fun malformedLinesDoNotKillTheWholeParse() {
        // 变体行之间夹了注释与空行，还有一条没有 URI 的 STREAM-INF
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=900000
            # a comment that is not a uri
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720

            a.m3u8
        """.trimIndent()
        val m = HlsPlaylist.parseMaster(text, "https://x.com/p/master.m3u8")
        assertNotNull(m)
        // 注释行被跳过 → 第一条 STREAM-INF 由 `a.m3u8` 消费；这里只要求不崩且能读出码率
        assertEquals(1, m!!.variants.size)
        assertEquals(2_000_000L, m.variants[0].bandwidth)
        assertEquals("720P", m.variants[0].qualityLabel)
    }

    @Test
    fun bandwidthLabelReadable() {
        fun v(bw: Long) = HlsPlaylist.Variant("u", bw, null, null, null, null)
        assertEquals("5.2 Mbps", v(5_200_000L).bandwidthLabel)
        assertEquals("820 kbps", v(820_000L).bandwidthLabel)
        // 只有达到 1 Mbps 才切到 Mbps 单位：低码率用 Mbps 会显示成 `0.5 Mbps`，
        // 不如 `500 kbps` 直观（也便于和清单里的 BANDWIDTH 数字对照）
        assertEquals("500 kbps", v(500_000L).bandwidthLabel)
        assertEquals("1.0 Mbps", v(1_000_000L).bandwidthLabel)
    }
}
