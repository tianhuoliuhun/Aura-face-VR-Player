package com.example.vr.cast;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

import org.jupnp.binding.annotations.UpnpAction;
import org.jupnp.binding.annotations.UpnpInputArgument;
import org.jupnp.binding.annotations.UpnpOutputArgument;
import org.jupnp.binding.annotations.UpnpService;
import org.jupnp.binding.annotations.UpnpServiceId;
import org.jupnp.binding.annotations.UpnpServiceType;
import org.jupnp.binding.annotations.UpnpStateVariable;
import org.jupnp.model.types.UnsignedIntegerFourBytes;
import org.jupnp.model.types.UnsignedIntegerTwoBytes;

/**
 * v2.4.58：DLNA / UPnP-AV 的 `RenderingControl:1` 服务（DMR 的音量/静音）。
 *
 * DMC 在投屏后常会调用 `GetVolume/SetVolume/GetMute/SetMute`；
 * 这里把它们桥接到 Android 的 `STREAM_MUSIC` 音量（UPnP 用 0~100，系统用 0~max）。
 *
 * ⚠️ 本类需要 `Context` 读系统音量 —— 用带参构造传入实例，
 *    `AnnotationLocalServiceBinder` 只读取类上的注解、不负责实例化，因此不受影响。
 */
@UpnpService(
        serviceId = @UpnpServiceId("RenderingControl"),
        serviceType = @UpnpServiceType(value = "RenderingControl", version = 1)
)
public class RenderingControlService {

    private static final String TAG = "CastRenderingControl";

    @UpnpStateVariable(name = "PresetNameList", defaultValue = "FactoryDefaults", sendEvents = false)
    private String presetNameList = "FactoryDefaults";

    @UpnpStateVariable(name = "Volume", datatype = "ui2", defaultValue = "0", sendEvents = false)
    private UnsignedIntegerTwoBytes volume = new UnsignedIntegerTwoBytes(0L);

    @UpnpStateVariable(name = "Mute", datatype = "boolean", defaultValue = "0", sendEvents = false)
    private boolean mute = false;

    @UpnpStateVariable(name = "A_ARG_TYPE_Channel", defaultValue = "Master", sendEvents = false)
    private String argChannel = "Master";

    @UpnpStateVariable(name = "A_ARG_TYPE_InstanceID", datatype = "ui4", defaultValue = "0", sendEvents = false)
    private long argInstanceId = 0L;

    /**
     * ⚠️ **必须提供无参构造**：jUPnP 的 {@code DefaultServiceManager} 会自己按
     * {@code serviceClass} 反射实例化本类（不接收实现实例），因此不能用构造参数注入 Context。
     * Context 改由 {@link CastEnv} 全局持有（见 CastReceiverService 启动时写入）。
     */
    public RenderingControlService() {
    }

    @UpnpAction(name = "ListPresets", out =
            @UpnpOutputArgument(name = "CurrentPresetNameList", stateVariable = "PresetNameList", getterName = "getList"))
    public PresetResult listPresets(
            @UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID")
            UnsignedIntegerFourBytes instanceId) {
        return new PresetResult(presetNameList);
    }

    @UpnpAction(name = "SelectPreset")
    public void selectPreset(
            @UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID")
            UnsignedIntegerFourBytes instanceId,
            @UpnpInputArgument(name = "PresetName", stateVariable = "PresetNameList")
            String presetName) {
        // 只有 FactoryDefaults 一个预设，选择即空操作
    }

    @UpnpAction(name = "GetVolume", out =
            @UpnpOutputArgument(name = "CurrentVolume", stateVariable = "Volume", getterName = "getVolume"))
    public VolumeResult getVolume(
            @UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID")
            UnsignedIntegerFourBytes instanceId,
            @UpnpInputArgument(name = "Channel", stateVariable = "A_ARG_TYPE_Channel")
            String channel) {
        this.volume = new UnsignedIntegerTwoBytes(readSystemVolume());
        return new VolumeResult(volume);
    }

    @UpnpAction(name = "SetVolume")
    public void setVolume(
            @UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID")
            UnsignedIntegerFourBytes instanceId,
            @UpnpInputArgument(name = "Channel", stateVariable = "A_ARG_TYPE_Channel")
            String channel,
            @UpnpInputArgument(name = "DesiredVolume", stateVariable = "Volume")
            UnsignedIntegerTwoBytes desiredVolume) {
        int v = desiredVolume == null ? 0 : (int) desiredVolume.getValue().longValue();
        writeSystemVolume(v);
        this.volume = new UnsignedIntegerTwoBytes(v);
        Log.i(TAG, "SetVolume -> " + v);
    }

    @UpnpAction(name = "GetMute", out =
            @UpnpOutputArgument(name = "CurrentMute", stateVariable = "Mute", getterName = "getMute"))
    public MuteResult getMute(
            @UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID")
            UnsignedIntegerFourBytes instanceId,
            @UpnpInputArgument(name = "Channel", stateVariable = "A_ARG_TYPE_Channel")
            String channel) {
        this.mute = readSystemMute();
        return new MuteResult(mute);
    }

    @UpnpAction(name = "SetMute")
    public void setMute(
            @UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID")
            UnsignedIntegerFourBytes instanceId,
            @UpnpInputArgument(name = "Channel", stateVariable = "A_ARG_TYPE_Channel")
            String channel,
            @UpnpInputArgument(name = "DesiredMute", stateVariable = "Mute")
            boolean desiredMute) {
        writeSystemMute(desiredMute);
        this.mute = desiredMute;
    }

    // ============================================================== 输出 JavaBean

    public static class PresetResult {
        private final String list;

        public PresetResult(String list) {
            this.list = list;
        }

        public String getList() { return list; }
    }

    public static class VolumeResult {
        private final UnsignedIntegerTwoBytes volume;

        public VolumeResult(UnsignedIntegerTwoBytes volume) {
            this.volume = volume;
        }

        public UnsignedIntegerTwoBytes getVolume() { return volume; }
    }

    public static class MuteResult {
        private final boolean mute;

        public MuteResult(boolean mute) {
            this.mute = mute;
        }

        public boolean getMute() { return mute; }
    }

    // ============================================================== 系统音量桥接

    private AudioManager am() {
        Context c = CastEnv.context();
        return c == null ? null : (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
    }

    private int readSystemVolume() {
        try {
            AudioManager a = am();
            if (a == null) {
                return 0;
            }
            int max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = a.getStreamVolume(AudioManager.STREAM_MUSIC);
            return max <= 0 ? 0 : Math.round(cur * 100f / max);
        } catch (Exception e) {
            return 0;
        }
    }

    private void writeSystemVolume(int v0to100) {
        try {
            AudioManager a = am();
            if (a == null) {
                return;
            }
            int max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int target = Math.round(Math.max(0, Math.min(100, v0to100)) * max / 100f);
            a.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        } catch (Exception ignored) {
        }
    }

    private boolean readSystemMute() {
        try {
            AudioManager a = am();
            return a != null && a.isStreamMute(AudioManager.STREAM_MUSIC);
        } catch (Exception e) {
            return false;
        }
    }

    private void writeSystemMute(boolean m) {
        try {
            AudioManager a = am();
            if (a == null) {
                return;
            }
            a.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    m ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
        } catch (Exception ignored) {
        }
    }
}
