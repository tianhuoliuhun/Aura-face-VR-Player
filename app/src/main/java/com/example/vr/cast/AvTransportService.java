package com.example.vr.cast;

import android.util.Log;

import org.jupnp.binding.annotations.UpnpAction;
import org.jupnp.binding.annotations.UpnpInputArgument;
import org.jupnp.binding.annotations.UpnpOutputArgument;
import org.jupnp.binding.annotations.UpnpService;
import org.jupnp.binding.annotations.UpnpServiceId;
import org.jupnp.binding.annotations.UpnpServiceType;
import org.jupnp.binding.annotations.UpnpStateVariable;
import org.jupnp.internal.compat.java.beans.PropertyChangeSupport;
import org.jupnp.model.types.UnsignedIntegerFourBytes;
import org.jupnp.support.avtransport.lastchange.AVTransportLastChangeParser;
import org.jupnp.support.avtransport.lastchange.AVTransportVariable;
import org.jupnp.support.lastchange.EventedValue;
import org.jupnp.support.lastchange.LastChange;
import org.jupnp.support.lastchange.LastChangeDelegator;
import org.jupnp.support.model.TransportState;
import org.jupnp.support.model.TransportStatus;

import java.net.URI;

/**
 * v2.4.58：**DLNA / UPnP-AV 的 `AVTransport:1` 服务（DMR 接收端的核心）**。
 *
 * ## 它在投屏链路里做什么
 * 手机上的视频 App（DMC）点「投屏」后，会向本机（DMR）调用：
 * `SetAVTransportURI(InstanceID=0, CurrentURI=<视频地址>, CurrentURIMetaData=<DIDL-Lite>)`，
 * 随后通常紧跟一个 `Play`。**视频地址就是从这里拿到的** —— 拿到后不自己下载，
 * 而是通过 {@link CastMediaBus} 交给应用现有的播放管线（Exo/IJK/系统/MPV 四后端）。
 *
 * 其余 action（Pause/Stop/Seek/GetPositionInfo…）是 DMC 的遥控与进度轮询。
 *
 * ## 实现方式（jUPnP 注解式服务）
 * 用 `@UpnpService/@UpnpStateVariable/@UpnpAction` 声明 SCPD，由
 * `AnnotationLocalServiceBinder.read(AvTransportService.class)` 在运行时生成
 * `LocalService`（含 action/state variable 定义与调用绑定），**无需手写 XML**。
 *
 * ## 事件推送（GENA，v2.4.59 起）
 * 按 UPnP AV / DLNA 规范，本服务**只把 `LastChange` 声明为事件化变量**：播放状态变化时
 * 用 `LastChange.fire(...)` 生成 AVT 命名空间的 XML 并推给订阅者（`NOTIFY`），
 * DMC 因此能**实时**看到 Play/Pause/Stop/换源，而不必轮询。
 * 其余状态变量保持 `sendEvents = false`（规范如此，也避免多余事件流量）。
 *
 * ## 一处刻意的简化
 * **多输出的 action 返回一个 JavaBean**，由 `@UpnpOutputArgument(getterName=...)` 取字段
 * （jUPnP 3.x 没有 Cling 时代的 `*Holder` 类，这是官方推荐的多输出写法）。
 */
@UpnpService(
        serviceId = @UpnpServiceId("AVTransport"),
        serviceType = @UpnpServiceType(value = "AVTransport", version = 1)
)
public class AvTransportService implements LastChangeDelegator {

    private static final String TAG = "CastAvTransport";

    // ==================================================================== 状态变量

    @UpnpStateVariable(name = "TransportState", defaultValue = "NO_MEDIA_PRESENT", sendEvents = false)
    private String transportState = "NO_MEDIA_PRESENT";

    @UpnpStateVariable(name = "TransportStatus", defaultValue = "OK", sendEvents = false)
    private String transportStatus = "OK";

    @UpnpStateVariable(name = "PlaybackStorageMedium", defaultValue = "NETWORK", sendEvents = false)
    private String playbackStorageMedium = "NETWORK";

    @UpnpStateVariable(name = "RecordStorageMedium", defaultValue = "NOT_IMPLEMENTED", sendEvents = false)
    private String recordStorageMedium = "NOT_IMPLEMENTED";

    @UpnpStateVariable(name = "PossiblePlaybackStorageMedia", defaultValue = "NETWORK", sendEvents = false)
    private String possiblePlaybackStorageMedia = "NETWORK";

    @UpnpStateVariable(name = "PossibleRecordStorageMedia", defaultValue = "NOT_IMPLEMENTED", sendEvents = false)
    private String possibleRecordStorageMedia = "NOT_IMPLEMENTED";

    @UpnpStateVariable(name = "CurrentPlayMode", defaultValue = "NORMAL", sendEvents = false)
    private String currentPlayMode = "NORMAL";

    @UpnpStateVariable(name = "TransportPlaySpeed", defaultValue = "1", sendEvents = false)
    private String transportPlaySpeed = "1";

    @UpnpStateVariable(name = "RecordMediumWriteStatus", defaultValue = "NOT_IMPLEMENTED", sendEvents = false)
    private String recordMediumWriteStatus = "NOT_IMPLEMENTED";

    @UpnpStateVariable(name = "CurrentRecordQualityMode", defaultValue = "NOT_IMPLEMENTED", sendEvents = false)
    private String currentRecordQualityMode = "NOT_IMPLEMENTED";

    @UpnpStateVariable(name = "PossibleRecordQualityModes", defaultValue = "NOT_IMPLEMENTED", sendEvents = false)
    private String possibleRecordQualityModes = "NOT_IMPLEMENTED";

    @UpnpStateVariable(name = "NumberOfTracks", datatype = "ui4", defaultValue = "0", sendEvents = false)
    private UnsignedIntegerFourBytes numberOfTracks = new UnsignedIntegerFourBytes(0L);

    @UpnpStateVariable(name = "CurrentTrack", datatype = "ui4", defaultValue = "0", sendEvents = false)
    private UnsignedIntegerFourBytes currentTrack = new UnsignedIntegerFourBytes(0L);

    @UpnpStateVariable(name = "CurrentTrackDuration", defaultValue = "00:00:00", sendEvents = false)
    private String currentTrackDuration = "00:00:00";

    @UpnpStateVariable(name = "CurrentMediaDuration", defaultValue = "00:00:00", sendEvents = false)
    private String currentMediaDuration = "00:00:00";

    @UpnpStateVariable(name = "CurrentTrackMetaData", defaultValue = "NOT_IMPLEMENTED", sendEvents = false)
    private String currentTrackMetaData = "NOT_IMPLEMENTED";

    @UpnpStateVariable(name = "CurrentTrackURI", defaultValue = "", sendEvents = false)
    private String currentTrackURI = "";

    @UpnpStateVariable(name = "AVTransportURI", defaultValue = "", sendEvents = false)
    private String avTransportURI = "";

    @UpnpStateVariable(name = "AVTransportURIMetaData", defaultValue = "", sendEvents = false)
    private String avTransportURIMetaData = "";

    @UpnpStateVariable(name = "NextAVTransportURI", defaultValue = "", sendEvents = false)
    private String nextAVTransportURI = "";

    @UpnpStateVariable(name = "NextAVTransportURIMetaData", defaultValue = "", sendEvents = false)
    private String nextAVTransportURIMetaData = "";

    @UpnpStateVariable(name = "RelativeTimePosition", defaultValue = "00:00:00", sendEvents = false)
    private String relativeTimePosition = "00:00:00";

    @UpnpStateVariable(name = "AbsoluteTimePosition", defaultValue = "00:00:00", sendEvents = false)
    private String absoluteTimePosition = "00:00:00";

    @UpnpStateVariable(name = "RelativeCounterPosition", datatype = "i4", defaultValue = "0", sendEvents = false)
    private int relativeCounterPosition = 0;

    @UpnpStateVariable(name = "AbsoluteCounterPosition", datatype = "i4", defaultValue = "0", sendEvents = false)
    private int absoluteCounterPosition = 0;

    @UpnpStateVariable(name = "CurrentTransportActions", defaultValue = "", sendEvents = false)
    private String currentTransportActions = "Play,Stop,Pause,Seek";

    @UpnpStateVariable(name = "A_ARG_TYPE_SeekMode", defaultValue = "REL_TIME", sendEvents = false)
    private String aArgTypeSeekMode = "REL_TIME";

    @UpnpStateVariable(name = "A_ARG_TYPE_SeekTarget", defaultValue = "00:00:00", sendEvents = false)
    private String aArgTypeSeekTarget = "00:00:00";

    @UpnpStateVariable(name = "A_ARG_TYPE_InstanceID", datatype = "ui4", defaultValue = "0", sendEvents = false)
    private UnsignedIntegerFourBytes aArgTypeInstanceId = new UnsignedIntegerFourBytes(0L);

    /**
     * **唯一的事件化状态变量**（GENA）。
     *
     * 按 UPnP AV / DLNA 规范，`AVTransport:1` 只 evented `LastChange` —— 其值是
     * `urn:schemas-upnp-org:metadata-1-0/AVT/` 命名空间的 XML，列出「本次变化的变量」。
     * DMC 订阅后即可实时收到播放状态变化。
     */
    @UpnpStateVariable(name = "LastChange", sendEvents = true)
    private String lastChangeValue = "";

    /** GENA 事件通道：jUPnP 通过它把 `LastChange` 的变化推给所有订阅者 */
    private final PropertyChangeSupport propertyChangeSupport = new PropertyChangeSupport(this);

    private final LastChange lastChange = new LastChange(new AVTransportLastChangeParser());

    public PropertyChangeSupport getPropertyChangeSupport() {
        return propertyChangeSupport;
    }

    @Override
    public LastChange getLastChange() {
        return lastChange;
    }

    @Override
    public UnsignedIntegerFourBytes[] getCurrentInstanceIds() {
        return new UnsignedIntegerFourBytes[]{ new UnsignedIntegerFourBytes(0L) };
    }

    /** 当前状态的完整快照（订阅时的初值、重订阅时用） */
    @Override
    public void appendCurrentState(LastChange lc, UnsignedIntegerFourBytes instanceId) {
        // 用 UnsignedIntegerFourBytes 重载（setEventedValue(int, …) 不接受 Long）
        lc.setEventedValue(instanceId,
                new AVTransportVariable.TransportState(safeState()),
                new AVTransportVariable.TransportStatus(TransportStatus.OK),
                new AVTransportVariable.AVTransportURI(safeUri(avTransportURI)),
                new AVTransportVariable.CurrentTrackURI(safeUri(currentTrackURI)),
                new AVTransportVariable.CurrentTrackDuration(currentTrackDuration),
                new AVTransportVariable.CurrentTrackMetaData(currentTrackMetaData));
    }

    /**
     * 把「本次变化的变量」写入 LastChange 并**立即推送**给所有订阅者（GENA `NOTIFY`）。
     */
    private void fireLastChange(EventedValue<?>... changes) {
        if (changes.length > 0) {
            lastChange.setEventedValue(0, changes);
        }
        lastChangeValue = lastChange.toString();
        lastChange.fire(propertyChangeSupport);
    }

    private TransportState safeState() {
        try {
            return TransportState.valueOf(transportState);
        } catch (Exception e) {
            return TransportState.NO_MEDIA_PRESENT;
        }
    }

    /** `EventedValueURI` 要求合法 URI；投屏地址可能含特殊字符，这里兜底为空 URI（不抛异常）。 */
    private static URI safeUri(String s) {
        try {
            return URI.create(s == null ? "" : s.trim());
        } catch (Exception e) {
            return URI.create("");
        }
    }

    // ==================================================================== Actions

    /** **核心**：DMC 把要播放的地址交过来 —— 这里通过总线转交给应用播放器。 */
    @UpnpAction(name = "SetAVTransportURI")
    public void setAVTransportURI(
            @UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID")
            UnsignedIntegerFourBytes instanceId,
            @UpnpInputArgument(name = "CurrentURI", stateVariable = "AVTransportURI")
            String currentURI,
            @UpnpInputArgument(name = "CurrentURIMetaData", stateVariable = "AVTransportURIMetaData")
            String currentURIMetaData) {

        this.avTransportURI = currentURI == null ? "" : currentURI;
        this.avTransportURIMetaData = currentURIMetaData == null ? "" : currentURIMetaData;
        this.currentTrackURI = this.avTransportURI;
        // 只要拿到地址就进入「已装载」状态，随后 DMC 一般会立刻发 Play
        this.transportState = "STOPPED";
        this.numberOfTracks = new UnsignedIntegerFourBytes(1L);
        this.currentTrack = new UnsignedIntegerFourBytes(1L);

        String title = extractTitle(currentURIMetaData);
        Log.i(TAG, "SetAVTransportURI: " + this.avTransportURI + " title=" + title);
        CastMediaBus.onSetUri(this.avTransportURI, title);
        fireLastChange(
                new AVTransportVariable.TransportState(TransportState.STOPPED),
                new AVTransportVariable.TransportStatus(TransportStatus.OK),
                new AVTransportVariable.AVTransportURI(safeUri(this.avTransportURI)),
                new AVTransportVariable.CurrentTrackURI(safeUri(this.currentTrackURI)),
                new AVTransportVariable.CurrentTrackMetaData(this.avTransportURIMetaData));
    }

    @UpnpAction(name = "SetNextAVTransportURI")
    public void setNextAVTransportURI(
            @UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID")
            UnsignedIntegerFourBytes instanceId,
            @UpnpInputArgument(name = "NextURI", stateVariable = "NextAVTransportURI")
            String nextURI,
            @UpnpInputArgument(name = "NextURIMetaData", stateVariable = "NextAVTransportURIMetaData")
            String nextURIMetaData) {
        this.nextAVTransportURI = nextURI == null ? "" : nextURI;
        this.nextAVTransportURIMetaData = nextURIMetaData == null ? "" : nextURIMetaData;
    }

    @UpnpAction(name = "GetMediaInfo", out = {
            @UpnpOutputArgument(name = "NrTracks", stateVariable = "NumberOfTracks", getterName = "getNrTracks"),
            @UpnpOutputArgument(name = "MediaDuration", stateVariable = "CurrentMediaDuration", getterName = "getMediaDuration"),
            @UpnpOutputArgument(name = "CurrentURI", stateVariable = "AVTransportURI", getterName = "getCurrentUri"),
            @UpnpOutputArgument(name = "CurrentURIMetaData", stateVariable = "AVTransportURIMetaData", getterName = "getCurrentUriMetaData"),
            @UpnpOutputArgument(name = "NextURI", stateVariable = "NextAVTransportURI", getterName = "getNextUri"),
            @UpnpOutputArgument(name = "NextURIMetaData", stateVariable = "NextAVTransportURIMetaData", getterName = "getNextUriMetaData"),
            @UpnpOutputArgument(name = "PlayMedium", stateVariable = "PlaybackStorageMedium", getterName = "getPlayMedium"),
            @UpnpOutputArgument(name = "RecordMedium", stateVariable = "RecordStorageMedium", getterName = "getRecordMedium"),
            @UpnpOutputArgument(name = "WriteStatus", stateVariable = "RecordMediumWriteStatus", getterName = "getWriteStatus")
    })
    public MediaInfoResult getMediaInfo(
            @UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID")
            UnsignedIntegerFourBytes instanceId) {
        return new MediaInfoResult(numberOfTracks, currentMediaDuration, avTransportURI,
                avTransportURIMetaData, nextAVTransportURI, nextAVTransportURIMetaData,
                playbackStorageMedium, recordStorageMedium, recordMediumWriteStatus);
    }

    @UpnpAction(name = "GetTransportInfo", out = {
            @UpnpOutputArgument(name = "CurrentTransportState", stateVariable = "TransportState", getterName = "getCurrentTransportState"),
            @UpnpOutputArgument(name = "CurrentTransportStatus", stateVariable = "TransportStatus", getterName = "getCurrentTransportStatus"),
            @UpnpOutputArgument(name = "CurrentSpeed", stateVariable = "TransportPlaySpeed", getterName = "getCurrentSpeed")
    })
    public TransportInfoResult getTransportInfo(
            @UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID")
            UnsignedIntegerFourBytes instanceId) {
        return new TransportInfoResult(transportState, transportStatus, transportPlaySpeed);
    }

    @UpnpAction(name = "GetPositionInfo", out = {
            @UpnpOutputArgument(name = "Track", stateVariable = "CurrentTrack", getterName = "getTrack"),
            @UpnpOutputArgument(name = "TrackDuration", stateVariable = "CurrentTrackDuration", getterName = "getTrackDuration"),
            @UpnpOutputArgument(name = "TrackMetaData", stateVariable = "CurrentTrackMetaData", getterName = "getTrackMetaData"),
            @UpnpOutputArgument(name = "TrackURI", stateVariable = "CurrentTrackURI", getterName = "getTrackUri"),
            @UpnpOutputArgument(name = "RelTime", stateVariable = "RelativeTimePosition", getterName = "getRelTime"),
            @UpnpOutputArgument(name = "AbsTime", stateVariable = "AbsoluteTimePosition", getterName = "getAbsTime"),
            @UpnpOutputArgument(name = "RelCount", stateVariable = "RelativeCounterPosition", getterName = "getRelCount"),
            @UpnpOutputArgument(name = "AbsCount", stateVariable = "AbsoluteCounterPosition", getterName = "getAbsCount")
    })
    public PositionInfoResult getPositionInfo(
            @UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID")
            UnsignedIntegerFourBytes instanceId) {
        // 进度由应用播放器经总线回写（服务自己不知道播放位置）
        CastPlaybackState st = CastMediaBus.INSTANCE.getPlaybackState().getValue();
        String rel = formatDuration(st.getPositionMs());
        String dur = formatDuration(st.getDurationMs());
        this.relativeTimePosition = rel;
        this.currentTrackDuration = dur;
        return new PositionInfoResult(currentTrack, dur, currentTrackMetaData, currentTrackURI,
                rel, absoluteTimePosition, relativeCounterPosition, absoluteCounterPosition);
    }

    @UpnpAction(name = "GetDeviceCapabilities", out = {
            @UpnpOutputArgument(name = "PlayMedia", stateVariable = "PossiblePlaybackStorageMedia", getterName = "getPlayMedia"),
            @UpnpOutputArgument(name = "RecMedia", stateVariable = "PossibleRecordStorageMedia", getterName = "getRecMedia"),
            @UpnpOutputArgument(name = "RecQualityModes", stateVariable = "PossibleRecordQualityModes", getterName = "getRecQualityModes")
    })
    public DeviceCapabilitiesResult getDeviceCapabilities(
            @UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID")
            UnsignedIntegerFourBytes instanceId) {
        return new DeviceCapabilitiesResult(possiblePlaybackStorageMedia, possibleRecordStorageMedia,
                possibleRecordQualityModes);
    }

    @UpnpAction(name = "GetTransportSettings", out = {
            @UpnpOutputArgument(name = "PlayMode", stateVariable = "CurrentPlayMode", getterName = "getPlayMode"),
            @UpnpOutputArgument(name = "RecQualityMode", stateVariable = "CurrentRecordQualityMode", getterName = "getRecQualityMode")
    })
    public TransportSettingsResult getTransportSettings(
            @UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID")
            UnsignedIntegerFourBytes instanceId) {
        return new TransportSettingsResult(currentPlayMode, currentRecordQualityMode);
    }

    @UpnpAction(name = "Stop")
    public void stop(
            @UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID")
            UnsignedIntegerFourBytes instanceId) {
        transportState = "STOPPED";
        CastMediaBus.onCommand(CastCommand.Type.STOP, 0L);
        fireLastChange(
                new AVTransportVariable.TransportState(TransportState.STOPPED),
                new AVTransportVariable.TransportStatus(TransportStatus.OK));
    }

    @UpnpAction(name = "Play")
    public void play(
            @UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID")
            UnsignedIntegerFourBytes instanceId,
            @UpnpInputArgument(name = "Speed", stateVariable = "TransportPlaySpeed")
            String speed) {
        transportState = "PLAYING";
        if (speed != null && !speed.isEmpty()) {
            transportPlaySpeed = speed;
        }
        CastMediaBus.onCommand(CastCommand.Type.PLAY, 0L);
        fireLastChange(
                new AVTransportVariable.TransportState(TransportState.PLAYING),
                new AVTransportVariable.TransportStatus(TransportStatus.OK));
    }

    @UpnpAction(name = "Pause")
    public void pause(
            @UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID")
            UnsignedIntegerFourBytes instanceId) {
        transportState = "PAUSED_PLAYBACK";
        CastMediaBus.onCommand(CastCommand.Type.PAUSE, 0L);
        fireLastChange(
                new AVTransportVariable.TransportState(TransportState.PAUSED_PLAYBACK),
                new AVTransportVariable.TransportStatus(TransportStatus.OK));
    }

    @UpnpAction(name = "Record")
    public void record(
            @UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID")
            UnsignedIntegerFourBytes instanceId) {
        // 不支持录制：保持状态不变（返回成功但不做事，避免部分 DMC 因"不支持"而中断会话）
    }

    @UpnpAction(name = "Seek")
    public void seek(
            @UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID")
            UnsignedIntegerFourBytes instanceId,
            @UpnpInputArgument(name = "Unit", stateVariable = "A_ARG_TYPE_SeekMode")
            String unit,
            @UpnpInputArgument(name = "Target", stateVariable = "A_ARG_TYPE_SeekTarget")
            String target) {
        long ms = parseDuration(target);
        Log.i(TAG, "Seek unit=" + unit + " target=" + target + " -> " + ms + "ms");
        CastMediaBus.onCommand(CastCommand.Type.SEEK, ms);
    }

    @UpnpAction(name = "Next")
    public void next(
            @UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID")
            UnsignedIntegerFourBytes instanceId) {
        // 单曲模式：无下一曲
    }

    @UpnpAction(name = "Previous")
    public void previous(
            @UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID")
            UnsignedIntegerFourBytes instanceId) {
        // 单曲模式：无上一曲
    }

    @UpnpAction(name = "SetPlayMode")
    public void setPlayMode(
            @UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID")
            UnsignedIntegerFourBytes instanceId,
            @UpnpInputArgument(name = "NewPlayMode", stateVariable = "CurrentPlayMode")
            String newPlayMode) {
        if (newPlayMode != null && !newPlayMode.isEmpty()) {
            currentPlayMode = newPlayMode;
        }
    }

    @UpnpAction(name = "SetRecordQualityMode")
    public void setRecordQualityMode(
            @UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID")
            UnsignedIntegerFourBytes instanceId,
            @UpnpInputArgument(name = "NewRecordQualityMode", stateVariable = "CurrentRecordQualityMode")
            String newRecordQualityMode) {
        // 不支持录制
    }

    @UpnpAction(name = "GetCurrentTransportActions", out =
            @UpnpOutputArgument(name = "Actions", stateVariable = "CurrentTransportActions", getterName = "getActions"))
    public CurrentTransportActionsResult getCurrentTransportActions(
            @UpnpInputArgument(name = "InstanceID", stateVariable = "A_ARG_TYPE_InstanceID")
            UnsignedIntegerFourBytes instanceId) {
        return new CurrentTransportActionsResult(currentTransportActions);
    }

    // ========================================================= 输出 JavaBean（多输出）

    public static class MediaInfoResult {
        private final UnsignedIntegerFourBytes nrTracks;
        private final String mediaDuration, currentUri, currentUriMetaData, nextUri, nextUriMetaData;
        private final String playMedium, recordMedium, writeStatus;

        public MediaInfoResult(UnsignedIntegerFourBytes nrTracks, String mediaDuration, String currentUri,
                               String currentUriMetaData, String nextUri, String nextUriMetaData,
                               String playMedium, String recordMedium, String writeStatus) {
            this.nrTracks = nrTracks;
            this.mediaDuration = mediaDuration;
            this.currentUri = currentUri;
            this.currentUriMetaData = currentUriMetaData;
            this.nextUri = nextUri;
            this.nextUriMetaData = nextUriMetaData;
            this.playMedium = playMedium;
            this.recordMedium = recordMedium;
            this.writeStatus = writeStatus;
        }

        public UnsignedIntegerFourBytes getNrTracks() { return nrTracks; }
        public String getMediaDuration() { return mediaDuration; }
        public String getCurrentUri() { return currentUri; }
        public String getCurrentUriMetaData() { return currentUriMetaData; }
        public String getNextUri() { return nextUri; }
        public String getNextUriMetaData() { return nextUriMetaData; }
        public String getPlayMedium() { return playMedium; }
        public String getRecordMedium() { return recordMedium; }
        public String getWriteStatus() { return writeStatus; }
    }

    public static class TransportInfoResult {
        private final String currentTransportState, currentTransportStatus, currentSpeed;

        public TransportInfoResult(String currentTransportState, String currentTransportStatus, String currentSpeed) {
            this.currentTransportState = currentTransportState;
            this.currentTransportStatus = currentTransportStatus;
            this.currentSpeed = currentSpeed;
        }

        public String getCurrentTransportState() { return currentTransportState; }
        public String getCurrentTransportStatus() { return currentTransportStatus; }
        public String getCurrentSpeed() { return currentSpeed; }
    }

    public static class PositionInfoResult {
        private final UnsignedIntegerFourBytes track;
        private final String trackDuration, trackMetaData, trackUri, relTime, absTime;
        private final int relCount, absCount;

        public PositionInfoResult(UnsignedIntegerFourBytes track, String trackDuration, String trackMetaData,
                                  String trackUri, String relTime, String absTime, int relCount, int absCount) {
            this.track = track;
            this.trackDuration = trackDuration;
            this.trackMetaData = trackMetaData;
            this.trackUri = trackUri;
            this.relTime = relTime;
            this.absTime = absTime;
            this.relCount = relCount;
            this.absCount = absCount;
        }

        public UnsignedIntegerFourBytes getTrack() { return track; }
        public String getTrackDuration() { return trackDuration; }
        public String getTrackMetaData() { return trackMetaData; }
        public String getTrackUri() { return trackUri; }
        public String getRelTime() { return relTime; }
        public String getAbsTime() { return absTime; }
        public int getRelCount() { return relCount; }
        public int getAbsCount() { return absCount; }
    }

    public static class DeviceCapabilitiesResult {
        private final String playMedia, recMedia, recQualityModes;

        public DeviceCapabilitiesResult(String playMedia, String recMedia, String recQualityModes) {
            this.playMedia = playMedia;
            this.recMedia = recMedia;
            this.recQualityModes = recQualityModes;
        }

        public String getPlayMedia() { return playMedia; }
        public String getRecMedia() { return recMedia; }
        public String getRecQualityModes() { return recQualityModes; }
    }

    public static class TransportSettingsResult {
        private final String playMode, recQualityMode;

        public TransportSettingsResult(String playMode, String recQualityMode) {
            this.playMode = playMode;
            this.recQualityMode = recQualityMode;
        }

        public String getPlayMode() { return playMode; }
        public String getRecQualityMode() { return recQualityMode; }
    }

    public static class CurrentTransportActionsResult {
        private final String actions;

        public CurrentTransportActionsResult(String actions) {
            this.actions = actions;
        }

        public String getActions() { return actions; }
    }

    // ================================================================= 工具方法

    /** 从 DIDL-Lite 元数据里粗略抽取标题（`<dc:title>…</dc:title>`）；失败返回 null。 */
    private static String extractTitle(String didl) {
        if (didl == null || didl.isEmpty()) {
            return null;
        }
        int s = didl.indexOf("<dc:title>");
        if (s < 0) {
            return null;
        }
        s += "<dc:title>".length();
        int e = didl.indexOf("</dc:title>", s);
        if (e < 0) {
            return null;
        }
        String t = didl.substring(s, e).trim();
        return t.isEmpty() ? null : t;
    }

    /** "HH:MM:SS" / "H:MM:SS.mmm" → 毫秒；解析失败返回 0。 */
    static long parseDuration(String hms) {
        if (hms == null || hms.isEmpty()) {
            return 0L;
        }
        try {
            String s = hms.trim();
            double frac = 0d;
            int dot = s.indexOf('.');
            if (dot >= 0) {
                frac = Double.parseDouble("0" + s.substring(dot));
                s = s.substring(0, dot);
            }
            String[] parts = s.split(":");
            if (parts.length != 3) {
                return 0L;
            }
            long h = Long.parseLong(parts[0]);
            long m = Long.parseLong(parts[1]);
            long sec = Long.parseLong(parts[2]);
            return (long) (((h * 3600 + m * 60 + sec) + frac) * 1000L);
        } catch (Exception ex) {
            return 0L;
        }
    }

    /** 毫秒 → "HH:MM:SS"。 */
    static String formatDuration(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long total = ms / 1000L;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s);
    }
}
