package com.example.vr.cast

import org.jupnp.binding.annotations.AnnotationLocalServiceBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.4.58：投屏接收（DLNA DMR）的「**注解绑定**」单测。
 *
 * ## 为什么需要它
 * jUPnP 的注解式服务是在**运行时**由 `AnnotationLocalServiceBinder.read(Class)` 解析的 ——
 * 注解写错（action 名/输出参数/状态变量不匹配）在**编译期完全不报错**，
 * 只有真机跑到 `registry.addDevice()` 时才抛 `LocalServiceBindingException`。
 * 本测试在 JVM 上提前跑一遍绑定，把这类错误拦在提交前。
 */
class CastServiceBindingTest {

    @Test
    fun avTransport_bindingSucceeds_andHasCoreActions() {
        val svc = AnnotationLocalServiceBinder().read(AvTransportService::class.java)
        assertNotNull("AVTransport 服务绑定失败", svc)
        val names = svc.actions.map { it.name }.toSet()
        // 投屏链路必须的 action
        assertTrue("缺少 SetAVTransportURI（投屏入口）：$names", "SetAVTransportURI" in names)
        assertTrue("缺少 Play：$names", "Play" in names)
        assertTrue("缺少 Pause：$names", "Pause" in names)
        assertTrue("缺少 Stop：$names", "Stop" in names)
        assertTrue("缺少 Seek：$names", "Seek" in names)
        assertTrue("缺少 GetTransportInfo：$names", "GetTransportInfo" in names)
        assertTrue("缺少 GetPositionInfo：$names", "GetPositionInfo" in names)
        assertTrue("缺少 GetMediaInfo：$names", "GetMediaInfo" in names)
        assertTrue("缺少 GetCurrentTransportActions：$names", "GetCurrentTransportActions" in names)
    }

    @Test
    fun connectionManager_bindingSucceeds_andAdvertisesSinkFormats() {
        val svc = AnnotationLocalServiceBinder().read(ConnectionManagerService::class.java)
        assertNotNull("ConnectionManager 服务绑定失败", svc)
        val names = svc.actions.map { it.name }.toSet()
        assertTrue("缺少 GetProtocolInfo（DMC 判断能否投屏依赖它）：$names", "GetProtocolInfo" in names)
        assertTrue("缺少 GetCurrentConnectionIDs：$names", "GetCurrentConnectionIDs" in names)
    }

    @Test
    fun renderingControl_bindingSucceeds() {
        val svc = AnnotationLocalServiceBinder().read(RenderingControlService::class.java)
        assertNotNull("RenderingControl 服务绑定失败", svc)
        val names = svc.actions.map { it.name }.toSet()
        assertTrue("缺少 GetVolume：$names", "GetVolume" in names)
        assertTrue("缺少 SetVolume：$names", "SetVolume" in names)
        assertTrue("缺少 GetMute：$names", "GetMute" in names)
    }

    @Test
    fun deviceFactory_buildsValidMediaRendererDevice() {
        val device = MediaRendererDeviceFactory.create("TestRenderer")
        assertEquals("MediaRenderer", device.type.type)
        assertEquals(3, device.services.size)
        // UDN 必须稳定（同一设备名两次创建得到同一 UDN）
        val device2 = MediaRendererDeviceFactory.create("TestRenderer")
        assertEquals(device.identity.udn, device2.identity.udn)
    }

    @Test
    fun durationFormatting_roundTrip() {
        assertEquals(0L, AvTransportService.parseDuration("00:00:00"))
        assertEquals(3_723_000L, AvTransportService.parseDuration("01:02:03"))
        assertEquals(3_723_000L, AvTransportService.parseDuration("01:02:03.000"))
        assertEquals("01:02:03", AvTransportService.formatDuration(3_723_000L))
        assertEquals("00:00:00", AvTransportService.formatDuration(-5L))
        assertEquals(0L, AvTransportService.parseDuration("not-a-time"))
    }
}
