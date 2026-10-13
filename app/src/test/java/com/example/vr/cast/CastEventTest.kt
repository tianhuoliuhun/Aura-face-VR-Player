package com.example.vr.cast

import org.jupnp.model.types.UnsignedIntegerFourBytes
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v2.4.59：**GENA / LastChange 事件推送**的行为测试。
 *
 * ## 为什么用 Robolectric
 * 触发状态变化（`setAVTransportURI` / `play`）会走到 `android.util.Log` ——
 * 纯 JVM 单测里 `Log` 是未实现的桩会抛异常，故这里需要 Android 运行时替身。
 *
 * ## 验的是什么
 * `LastChange` 是 AVTransport 规范里**唯一的事件化变量**：状态一变就应生成
 * AVT 命名空间的 XML 并 `firePropertyChange("LastChange", …)` —— 即 GENA `NOTIFY` 的数据源。
 * 这里直接挂监听器捕获推送值，验证「事件确实发出且内容正确」。
 */
@RunWith(RobolectricTestRunner::class)
class CastEventTest {

    @Test
    fun setAvTransportUri_firesLastChange_withUriAndStoppedState() {
        val svc = AvTransportService()
        var captured: String? = null
        svc.propertyChangeSupport.addPropertyChangeListener { evt ->
            if (evt.propertyName == "LastChange") {
                captured = evt.newValue as? String
            }
        }

        svc.setAVTransportURI(
            UnsignedIntegerFourBytes(0L),
            "http://192.168.1.5:8080/movie.mp4",
            "<DIDL-Lite/>",
        )

        val xml = captured
        assertNotNull("SetAVTransportURI 后应触发 GENA LastChange 事件", xml)
        assertTrue("事件 XML 应带 AVT 命名空间: $xml", xml!!.contains("urn:schemas-upnp-org:metadata-1-0/AVT/"))
        assertTrue("事件 XML 应含 TransportState: $xml", xml.contains("TransportState"))
        assertTrue("换源后状态应为 STOPPED: $xml", xml.contains("STOPPED"))
        assertTrue("事件 XML 应带上被投送的地址: $xml", xml.contains("192.168.1.5"))
    }

    @Test
    fun play_then_pause_emitPlayingThenPaused() {
        val svc = AvTransportService()
        val seen = mutableListOf<String>()
        svc.propertyChangeSupport.addPropertyChangeListener { evt ->
            if (evt.propertyName == "LastChange") {
                (evt.newValue as? String)?.let { seen.add(it) }
            }
        }

        svc.play(UnsignedIntegerFourBytes(0L), "1")
        svc.pause(UnsignedIntegerFourBytes(0L))
        svc.stop(UnsignedIntegerFourBytes(0L))

        assertTrue("应至少收到 3 次事件（play/pause/stop），实际 ${seen.size}", seen.size >= 3)
        assertTrue("PLAY 应推送 TransportState=PLAYING：$seen", seen.any { it.contains("PLAYING") })
        assertTrue("PAUSE 应推送 PAUSED_PLAYBACK：$seen", seen.any { it.contains("PAUSED_PLAYBACK") })
        assertTrue("STOP 应推送 STOPPED：$seen", seen.any { it.contains("STOPPED") })
    }
}
