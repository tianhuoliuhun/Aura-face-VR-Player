package com.example.vr.cast;

import org.jupnp.binding.annotations.UpnpAction;
import org.jupnp.binding.annotations.UpnpInputArgument;
import org.jupnp.binding.annotations.UpnpOutputArgument;
import org.jupnp.binding.annotations.UpnpService;
import org.jupnp.binding.annotations.UpnpServiceId;
import org.jupnp.binding.annotations.UpnpServiceType;
import org.jupnp.binding.annotations.UpnpStateVariable;
import org.jupnp.model.types.UnsignedIntegerFourBytes;

/**
 * v2.4.58：DLNA / UPnP-AV 的 `ConnectionManager:1` 服务。
 *
 * DMC 在投屏前常先调用 `GetProtocolInfo` 询问「接收端支持哪些协议/格式」，
 * 由 `Sink` 字段决定它的「投屏」按钮是否可用 —— 所以这里必须把常见视频格式列全，
 * 否则会出现「搜到了设备但点投屏没反应」。
 *
 * `Sink` 里的格式尽量宽：本项目有 Exo/IJK/系统/MPV 四套解码后端，
 * 常规封装（MP4/MKV/WebM/TS/HLS 等）基本都能播。
 */
@UpnpService(
        serviceId = @UpnpServiceId("ConnectionManager"),
        serviceType = @UpnpServiceType(value = "ConnectionManager", version = 1)
)
public class ConnectionManagerService {

    /** 接收端（Sink）可播放的协议信息 —— 决定 DMC「投屏」按钮是否可用 */
    private static final String SINK_PROTOCOL_INFO =
            "http-get:*:video/mp4:*,"
                    + "http-get:*:video/x-matroska:*,"
                    + "http-get:*:video/webm:*,"
                    + "http-get:*:video/mpeg:*,"
                    + "http-get:*:video/x-msvideo:*,"
                    + "http-get:*:video/x-ms-wmv:*,"
                    + "http-get:*:video/quicktime:*,"
                    + "http-get:*:video/3gpp:*,"
                    + "http-get:*:video/x-flv:*,"
                    + "http-get:*:video/MP2T:*,"
                    + "http-get:*:application/vnd.apple.mpegurl:*,"
                    + "http-get:*:application/x-mpegURL:*,"
                    + "http-get:*:audio/mpeg:*,"
                    + "http-get:*:audio/mp4:*,"
                    + "http-get:*:audio/x-ms-wma:*,"
                    + "http-get:*:video/*:*,"
                    + "http-get:*:audio/*:*";

    @UpnpStateVariable(name = "SourceProtocolInfo", defaultValue = "", sendEvents = false)
    private String sourceProtocolInfo = "";

    @UpnpStateVariable(name = "SinkProtocolInfo", defaultValue = "", sendEvents = false)
    private String sinkProtocolInfo = SINK_PROTOCOL_INFO;

    @UpnpStateVariable(name = "CurrentConnectionIDs", defaultValue = "0", sendEvents = false)
    private String currentConnectionIDs = "0";

    @UpnpStateVariable(name = "A_ARG_TYPE_ConnectionStatus", defaultValue = "OK", sendEvents = false)
    private String argConnectionStatus = "OK";

    @UpnpStateVariable(name = "A_ARG_TYPE_ConnectionManager", defaultValue = "", sendEvents = false)
    private String argConnectionManager = "";

    @UpnpStateVariable(name = "A_ARG_TYPE_Direction", defaultValue = "Input", sendEvents = false)
    private String argDirection = "Input";

    @UpnpStateVariable(name = "A_ARG_TYPE_ProtocolInfo", defaultValue = "", sendEvents = false)
    private String argProtocolInfo = "";

    @UpnpStateVariable(name = "A_ARG_TYPE_ConnectionID", datatype = "i4", defaultValue = "0", sendEvents = false)
    private int argConnectionId = 0;

    @UpnpStateVariable(name = "A_ARG_TYPE_AVTransportID", datatype = "i4", defaultValue = "0", sendEvents = false)
    private int argAvTransportId = 0;

    @UpnpStateVariable(name = "A_ARG_TYPE_RcsID", datatype = "i4", defaultValue = "0", sendEvents = false)
    private int argRcsId = 0;

    @UpnpAction(name = "GetProtocolInfo", out = {
            @UpnpOutputArgument(name = "Source", stateVariable = "SourceProtocolInfo", getterName = "getSource"),
            @UpnpOutputArgument(name = "Sink", stateVariable = "SinkProtocolInfo", getterName = "getSink")
    })
    public ProtocolInfoResult getProtocolInfo() {
        return new ProtocolInfoResult(sourceProtocolInfo, sinkProtocolInfo);
    }

    @UpnpAction(name = "GetCurrentConnectionIDs", out =
            @UpnpOutputArgument(name = "ConnectionIDs", stateVariable = "CurrentConnectionIDs", getterName = "getConnectionIDs"))
    public ConnectionIdsResult getCurrentConnectionIDs() {
        return new ConnectionIdsResult(currentConnectionIDs);
    }

    @UpnpAction(name = "GetCurrentConnectionInfo", out = {
            @UpnpOutputArgument(name = "RcsID", stateVariable = "A_ARG_TYPE_RcsID", getterName = "getRcsID"),
            @UpnpOutputArgument(name = "AVTransportID", stateVariable = "A_ARG_TYPE_AVTransportID", getterName = "getAvTransportID"),
            @UpnpOutputArgument(name = "ProtocolInfo", stateVariable = "A_ARG_TYPE_ProtocolInfo", getterName = "getProtocolInfo"),
            @UpnpOutputArgument(name = "PeerConnectionManager", stateVariable = "A_ARG_TYPE_ConnectionManager", getterName = "getPeerConnectionManager"),
            @UpnpOutputArgument(name = "PeerConnectionID", stateVariable = "A_ARG_TYPE_ConnectionID", getterName = "getPeerConnectionID"),
            @UpnpOutputArgument(name = "Direction", stateVariable = "A_ARG_TYPE_Direction", getterName = "getDirection"),
            @UpnpOutputArgument(name = "Status", stateVariable = "A_ARG_TYPE_ConnectionStatus", getterName = "getStatus")
    })
    public ConnectionInfoResult getCurrentConnectionInfo(
            @UpnpInputArgument(name = "ConnectionID", stateVariable = "A_ARG_TYPE_ConnectionID")
            int connectionId) {
        return new ConnectionInfoResult(0, 0, "", "", 0, "Input", "OK");
    }

    // ============================================================== 输出 JavaBean

    public static class ProtocolInfoResult {
        private final String source, sink;

        public ProtocolInfoResult(String source, String sink) {
            this.source = source;
            this.sink = sink;
        }

        public String getSource() { return source; }
        public String getSink() { return sink; }
    }

    public static class ConnectionIdsResult {
        private final String connectionIDs;

        public ConnectionIdsResult(String connectionIDs) {
            this.connectionIDs = connectionIDs;
        }

        public String getConnectionIDs() { return connectionIDs; }
    }

    public static class ConnectionInfoResult {
        private final int rcsID, avTransportID, peerConnectionID;
        private final String protocolInfo, peerConnectionManager, direction, status;

        public ConnectionInfoResult(int rcsID, int avTransportID, String protocolInfo,
                                    String peerConnectionManager, int peerConnectionID,
                                    String direction, String status) {
            this.rcsID = rcsID;
            this.avTransportID = avTransportID;
            this.protocolInfo = protocolInfo;
            this.peerConnectionManager = peerConnectionManager;
            this.peerConnectionID = peerConnectionID;
            this.direction = direction;
            this.status = status;
        }

        public int getRcsID() { return rcsID; }
        public int getAvTransportID() { return avTransportID; }
        public String getProtocolInfo() { return protocolInfo; }
        public String getPeerConnectionManager() { return peerConnectionManager; }
        public int getPeerConnectionID() { return peerConnectionID; }
        public String getDirection() { return direction; }
        public String getStatus() { return status; }
    }
}
