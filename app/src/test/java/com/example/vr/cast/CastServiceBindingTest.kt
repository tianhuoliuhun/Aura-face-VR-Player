package com.example.vr.cast

import org.jupnp.binding.annotations.AnnotationLocalServiceBinder
import org.jupnp.model.types.UnsignedIntegerFourBytes
import org.jupnp.support.lastchange.LastChangeDelegator
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

    // ==================== v2.4.59：GENA / LastChange 事件（元数据层面） ====================

    @Test
    fun avTransport_exposesLastChangeAsTheOnlyEventedVariable() {
        val svc = AnnotationLocalServiceBinder().read(AvTransportService::class.java)
        // 事件标志在 StateVariable.eventDetails 里（StateVariable 本身没有 isSendEvents）
        val evented = svc.stateVariables.filter { it.eventDetails.isSendEvents }.map { it.name }
        // DLNA 规范：AVTransport 只 evented LastChange（其它变量靠 GetXxx 轮询）
        assertEquals("事件化变量应只有 LastChange，实际：$evented", listOf("LastChange"), evented)
        assertNotNull(
            "缺少 LastChange 状态变量 —— DMC 将无法订阅播放状态变化",
            svc.stateVariables.firstOrNull { it.name == "LastChange" },
        )
    }

    @Test
    fun avTransport_implementsLastChangeDelegator() {
        val svc = AvTransportService()
        assertTrue("必须实现 LastChangeDelegator 才能被 LastChangeAwareServiceManager 驱动", svc is LastChangeDelegator)
        assertNotNull("事件通道不能为空", svc.propertyChangeSupport)
        assertEquals("实例 ID 只有一个（单实例渲染器）", 1, svc.currentInstanceIds.size)
        // 完整状态快照可生成且不抛异常（订阅初值走这条路径）
        svc.appendCurrentState(svc.lastChange, UnsignedIntegerFourBytes(0L))
        assertTrue(
            "初始快照应含 TransportState：" + svc.lastChange,
            svc.lastChange.toString().contains("TransportState"),
        )
    }
}
