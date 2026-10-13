package com.example.vr.cast;

import org.jupnp.binding.LocalServiceBinder;
import org.jupnp.binding.annotations.AnnotationLocalServiceBinder;
import org.jupnp.model.DefaultServiceManager;
import org.jupnp.model.ValidationException;
import org.jupnp.model.meta.DeviceDetails;
import org.jupnp.model.meta.DeviceIdentity;
import org.jupnp.model.meta.LocalDevice;
import org.jupnp.model.meta.LocalService;
import org.jupnp.model.meta.ManufacturerDetails;
import org.jupnp.model.meta.ModelDetails;
import org.jupnp.model.types.UDADeviceType;
import org.jupnp.model.types.UDN;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * v2.4.58：**把三个服务组装成一台标准的 `MediaRenderer:1` 设备**。
 *
 * 组装方式与 jUPnP 官方手册一致：
 * `LocalDevice(DeviceIdentity, UDADeviceType, DeviceDetails, LocalService[])`，
 * 其中每个 `LocalService` 由 `AnnotationLocalServiceBinder.read(Class)` 依据注解生成，
 * 再 `setManager(new DefaultServiceManager<>(localService, 实现类))` 绑定实现。
 *
 * ⚠️ `DefaultServiceManager(LocalService<T>, Class<T>)` 的第一个参数是 **LocalService**，
 *    它会按 `Class<T>` **自己反射实例化**服务实现 —— 所以三个服务类都必须是**无参构造**。
 *
 * ⚠️ **UDN 必须稳定**：`UDN` 是设备在局域网内的唯一标识，DMC 靠它记住「这台设备」。
 *    这里用「包名 + 设备名」的确定性 UUID（`nameUUIDFromBytes`），
 *    保证应用重启/重装后 UDN 不变（不要用 `UUID.randomUUID()`）。
 */
public final class MediaRendererDeviceFactory {

    private MediaRendererDeviceFactory() {
    }

    /**
     * @param friendlyName 手机端「投屏」列表里显示的名字（建议用设备型号或应用名）
     */
    public static LocalDevice create(String friendlyName) throws ValidationException {
        UUID uuid = UUID.nameUUIDFromBytes(
                ("com.example.vr.cast/" + friendlyName).getBytes(StandardCharsets.UTF_8));
        DeviceIdentity identity = new DeviceIdentity(new UDN(uuid));
        UDADeviceType type = new UDADeviceType("MediaRenderer", 1);
        DeviceDetails details = new DeviceDetails(
                friendlyName,
                new ManufacturerDetails("Aura"),
                new ModelDetails("AuraFaceVRPlayer", "VR 播放器 · 投屏接收", "1"));

        LocalServiceBinder binder = new AnnotationLocalServiceBinder();

        @SuppressWarnings("unchecked")
        LocalService<AvTransportService> avTransport =
                (LocalService<AvTransportService>) binder.read(AvTransportService.class);
        avTransport.setManager(new DefaultServiceManager<>(avTransport, AvTransportService.class));

        @SuppressWarnings("unchecked")
        LocalService<ConnectionManagerService> connectionManager =
                (LocalService<ConnectionManagerService>) binder.read(ConnectionManagerService.class);
        connectionManager.setManager(
                new DefaultServiceManager<>(connectionManager, ConnectionManagerService.class));

        @SuppressWarnings("unchecked")
        LocalService<RenderingControlService> renderingControl =
                (LocalService<RenderingControlService>) binder.read(RenderingControlService.class);
        renderingControl.setManager(
                new DefaultServiceManager<>(renderingControl, RenderingControlService.class));

        return new LocalDevice(identity, type, details,
                new LocalService[]{avTransport, connectionManager, renderingControl});
    }
}
