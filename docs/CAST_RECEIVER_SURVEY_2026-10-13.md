# 「投屏接收」方案调研报告

- 项目：GfaceVRplayer（Android VR 播放器，包名 `com.aistudio.vrplayer.vrmjpy`）
- 需求：在应用设置页新增「投屏」开关；开启后应用可作为**接收端**，接收其他软件投送来的视频流并播放
- 报告日期：2026-10-13
- 说明：本报告为纯文字分析，不含图片

---

## 一、结论摘要（先看这段）

1. **需求的技术本质是「接收端（Receiver / Sink）」，而不是「发送端」。** 市面上的「投屏」在协议层面分为两大类：
   - **URL 推送式**（DLNA/UPnP-AV、部分私有协议）：手机 App 把视频地址（URL）交给接收端，接收端**自己去 HTTP 拉流播放**。接收端只负责「被控 + 播放」，实现成本低。
   - **屏幕镜像式**（Miracast、AirPlay 镜像、私有镜像协议）：把发送端整屏画面编码成实时音视频流推给接收端，接收端解码渲染。协议更重、系统权限与合规门槛更高。
2. **在 Android 上，第三方普通应用（无系统签名）能真正落地的只有「URL 推送式接收」，即 DLNA/UPnP-AV 的 MediaRenderer（DMR）。** 这是唯一同时满足「纯软件、开源、可商用、活跃维护」的路线。
3. **Miracast 接收端：不可行。** Android 8.0 起，Wi-Fi Display Sink 所需的 `CONFIGURE_WIFI_DISPLAY` 被收紧为系统签名权限，第三方 App 无法注册成为系统级 Sink；自研「应用级 Sink」在真实发送端（手机/Windows）上兼容性极差。
4. **Google Cast 接收端：不可行。** Cast 的接收端形态是 Chromecast 硬件 + 认证的网页 Receiver，没有面向第三方 Android App 的接收端 API。
5. **AirPlay 接收：技术上已有人做成（UxPlay 系），但对本项目不推荐。** 现有 Android 实现（`jqssun/android-airplay-server`、`bertanbakir/airplay-screen-receiver`）均为 **GPLv3**（对闭源分发有传染性），且**不支持 DRM 内容**——主流流媒体 App 的受保护内容无法接收，实际可用面被大幅压缩。
6. **推荐方案：用 jUPnP 3.x 实现 DLNA/UPnP-AV MediaRenderer 接收端（DMR）**，配合前台服务与设置开关；拿到 URL 后直接复用本项目已有的多后端播放管线。这是投入产出比最高、与本项目架构契合度最高的路线。

---

## 二、投屏协议全景（5 类）

### 2.1 DLNA / UPnP-AV（推荐路线的协议基础）
- **定位**：局域网内「媒体推送 + 遥控播放」，不做屏幕镜像。
- **三类角色**：
  - **DMS**（Media Server）：媒体提供方（如 NAS、PC 媒体库）；
  - **DMR**（Media Renderer）：**接收并播放方——本项目要扮演的角色**；
  - **DMC**（Media Controller）：控制方（手机 App 上的「投屏」按钮）。
- **交互流程（关键）**：DMC 发现 DMR 后，通过 `AVTransport` 服务的 `SetAVTransportURI(CurrentURI=视频URL)` + `Play()` 把 URL 交给 DMR；**DMR 自己用 HTTP 去拉这个 URL 播放**。所以接收端拿到的是一串地址（可能是 MP4、HLS、TS、甚至加密流），而不是音视频码流本身。
- **四层协议**：
  - 发现：**SSDP**（UDP 组播 `239.255.255.250:1900`，`M-SEARCH` 查询 + `NOTIFY` 广播心跳）；
  - 描述：HTTP 拉取设备描述 XML 与 SCPD（服务能力描述）；
  - 控制：**SOAP** over HTTP（动作调用）；
  - 事件：**GENA**（`SUBSCRIBE` / `NOTIFY`，把播放状态回推给 DMC）。
- **核心服务**：`AVTransport:1`（播放/暂停/停止/Seek/进度查询）、`RenderingControl:1`（音量/静音）、`ConnectionManager:1`（协议能力协商）。

### 2.2 Apple AirPlay / RAOP
- **定位**：苹果生态的投屏与音频推送，二合一（RAOP 音频 + AirPlay 镜像/视频）。
- **传输特征**：基于 Bonjour/mDNS 发现（`_airplay._tcp`、`_raop._tcp`）、RTSP 协商、FairPlay 加密握手、AAC-ELD/ALAC 音频、H.264/H.265 视频镜像、HLS 视频回放。
- **特点**：协议私有且逆向实现为主；**不支持 DRM 保护内容**。

### 2.3 Miracast（Wi-Fi Display, WFD）
- **定位**：系统级屏幕镜像标准，基于 Wi-Fi Direct（P2P）+ RTSP 协商 + RTP 承载 H.264 音视频。
- **Android 现状**：系统自带「无线显示」是**发送端**；**接收端（Sink）需要 `CONFIGURE_WIFI_DISPLAY` 权限（signature|privileged）**，Android 8.0 之后第三方应用无法获取。

### 2.4 Google Cast
- **定位**：Google 的投屏体系，接收端是 Chromecast 设备上的认证网页 Receiver。
- **现状**：接收端不对第三方 Android App 开放；发送端也需 Cast SDK 与设备注册。对「把 Android 应用做成接收端」这一需求**不适用**。

### 2.5 私有协议（国内生态主流）
- **乐播（Lelink / 乐联）**：国内事实标准。官方口径为「兼容 DLNA、AirPlay，并自研 Lelink 私有协议」，宣称内置 8000+ 音视频 App、覆盖 95% 电视品牌，支持跨网投屏与镜像。**SDK 闭源、商业授权**。
- **其他**：当贝、各家视频 App 自研 Cast 通道等，多为「私有协议 + DLNA 兜底」。
- **含义**：国内用户点「投屏」按钮时，**相当比例走的是私有协议而非标准 DLNA**。只做 DLNA 接收，覆盖的是「支持 DLNA 的发送端」；要覆盖最广，只能选商业 SDK（或同时做多条协议）。

---

## 三、候选开源项目逐一分析

### 3.1 jUPnP（org.jupnp）——DLNA/UPnP 接收端首选
- **核心原理与实现**：Cling 的社区 fork，完整的 UPnP Device Architecture 实现（SSDP / SOAP / GENA / HTTP 描述），提供 `UpnpService`、`Router`、`Registry`、`ControlPoint` 等抽象；既可作为控制点，也可**作为设备（Device）被注册进网络**。
- **接收端落地方式**：注册一个 `LocalDevice`，设备类型为 `urn:schemas-upnp-org:device:MediaRenderer:1`，并绑定若干 Service 实现——`AVTransport:1`、`ConnectionManager:1`、`RenderingControl:1`。当远端 DMC 调用 `SetAVTransportURI` 时，在 action 回调里取出 `CurrentURI`，再交给本机播放器。
- **支持协议**：UPnP 1.0/1.1 + DLNA 媒体能力（`org.jupnp.support` 提供媒体相关扩展）。
- **版本与维护**：`org.jupnp` / `org.jupnp.android` / `org.jupnp.support`，最新 `3.0.4`（2025-12），**活跃**。
- **Android 支持**：官方文档《jUPnP on Android》明确支持「在 Android 上编写 UPnP 服务端应用」；提供 `org.jupnp.android.AndroidUpnpService(Impl)` 便于用 Service 承载；需依赖 Jetty 9（`jetty-server/servlet/client`）与 `slf4j`；Manifest 需 `INTERNET`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_MULTICAST_STATE`、`ACCESS_NETWORK_STATE`、`WAKE_LOCK`。
- **许可证**：**CDDL-1.0**（文件级弱 copyleft），闭源商用可行（修改 CDDL 源文件的部分需回馈）。
- **稳定性与兼容性**：维护活跃、有测试；自动处理网卡上下线；「作为服务端被注册」是其设计内的用法。已知限制：Android 模拟器**收不到组播**，发现类测试必须真机。

### 3.2 Cling 2.1.2——经典但已停更
- **核心原理与实现**：与 jUPnP 同源（jUPnP 即其 fork），API 语义清晰，示例丰富。
- **状态**：官方仓库已标注 **EOL「no longer actively maintained」**；官网关闭。
- **许可证**：LGPL-2.1 或 CDDL 双许可。
- **稳定性与兼容性**：核心协议多年稳定，但**对新 Android 的适配要自己修**：明文 HTTP 策略、后台服务限制、打包冲突（`META-INF` 重复文件）等。适合「求稳、资料多、不想引入 Jetty 系依赖」的老项目；新项目不建议。

### 3.3 CyberLink for Java / DroidDLNA——历史方案
- **原理**：Intel 开源的 UPnP 栈（CyberLink）+ 社区 Android 封装（DroidDLNA）。
- **已知问题**：体量大、线程模型老、对移动端弱网不友好、若干已知 bug；实现 MediaRenderer 时常因**未申请 MulticastLock 而「设备发现不了」**（SSDP 组播被系统过滤）。
- **结论**：仅作技术参考，不推荐新项目采用。

### 3.4 Platinum（C++）+ ShowDlnaMediaRenderer——性能路线
- **原理**：跨平台 C++ UPnP/DLNA 栈（Platinum SDK），可构建控制点与设备（含 MediaRenderer），通过 JNI 暴露给 Android。
- **代表工程**：`alienwolf-npu/ShowDlnaMediaRenderer`（2019）——基于 Platinum 的安卓投屏 SDK（接收端），提供 `startMediaRender` / `responseGenaEvent` 等 JNI 接口，**播放器与 GENA 事件需接入方自己实现**。
- **优点**：运行时开销小、协议栈成熟（很多商业设备在用）、无 JVM 依赖。
- **缺点**：需 NDK 交叉编译（工具链要求老，构建脚本基于 scons）、JNI 桥接与生命周期管理复杂、社区工程多年未更新。
- **许可证**：Platinum 为 GPL 系（部分组件另议），**需逐一核实**。

### 3.5 UPnPCast（yinnho/UPnPCast）——发送端，参考价值
- **定位**：现代 Android **DLNA 发送端**库（把 URL/本地文件投给别的渲染器），coroutine-first Kotlin API，Android 7+，JitPack 分发，自带 MulticastLock、本地文件 HTTP 服务、DIDL-Lite 元数据、92 个测试。
- **对本项目的价值**：不是接收端方案，但可作为**离线自测工具**（本机把视频投给另一台设备），并可用其「内置假渲染器做协议级测试」的思路验证我们对 DMR 协议的理解。

### 3.6 GSYVideoPlayer 的投屏能力规划——最贴近本项目的工程蓝图
- **公开文档**：`GSYVideoPlayer/doc/CAST_FEATURE_PLAN.md`（2026-07）记录了向 **jUPnP 3.0.3** 的架构重定向：
  - 抽象出 `CastCapability / CastProvider / CastSession` 三件 SPI，DLNA 作为缺省实现；
  - `DlnaCastProvider` 用 jUPnP `Registry` + `RegistryListener` 做 SSDP 发现；`DlnaCastSession` 封装 `AVTransport`（SetAVTransportURI / Play / Pause / Stop / Seek / GetPositionInfo）；
  - **自机自收验收工具**：前台 Service（`foregroundServiceType=mediaPlayback`）内启动 jUPnP `UpnpService`，注册 `LocalDevice`（`MediaRenderer:1`）+ 自定义 `AvTransportService`，**action 回调直接驱动本机播放器**；
  - 预留 Chromecast / 乐播等第三方实现挂接同一 SPI。
- **借鉴意义**：它验证了「jUPnP + 前台服务 + 自实现 AVTransport 回调 → 本机播放器」这条链路在 Android 上可落地，且给出了「预留扩展点」的架构分层。

### 3.7 UxPlay 系（AirPlay 接收，Android 移植）——能力强但合规/内容受限
- **核心原理**：UxPlay 是 C 实现的 AirPlay/RAOP 接收端（RAOP + AirPlay2 镜像 + FairPlay 握手 + HLS），跨平台（Linux/macOS 为主）。
- **Android 移植现状**：
  - `jqssun/android-airplay-server`：**首个开源 Android AirPlay 2 接收端**。JNI 桥接 UxPlay；镜像视频走 `MediaCodec`（H.264/H.265），音频走 `MediaCodec`/FFmpeg（ALAC/AAC-ELD），HLS 经本地 playlist 代理交给 ExoPlayer；mDNS（JmDNS）内置；支持 PIN、PiP、Android TV。Android 7+。
  - `bertanbakir/airplay-screen-receiver`：Android TV 壳（CMake/JmDNS/MediaCodec/AudioTrack），API 26+。
  - `shairport-sync`：仅音频（RAOP），不满足视频需求。
- **主要障碍**：
  1. **许可证 GPLv3**：与整个 App 链接后，分发二进制需以 GPLv3 提供完整对应源码——对本项目（闭源分发）是硬约束；
  2. **不支持 DRM**：受保护内容（如 Apple TV App、部分流媒体）无法接收，**这是产品级硬伤**；
  3. 协议为逆向实现，苹果协议演进可能导致兼容回归；
  4. 集成成本高（NDK + 子模块 + FFmpeg 等原生依赖 + mDNS）。

### 3.8 其他被提及但另有定位的项目
- **LibStreaming / Spydroid**：Android RTSP 相关老库，停止维护、对新系统适配差；仅适合「已知源为 RTSP」的场景。
- **MiracleCast**：Linux 上的 Miracast Sink 实现，可作协议参考，**Android 上不可用**。
- **Kodi / VLC（桌面端）**：可作为**对端测试工具**（VLC 能收 RTSP/UPnP 流），不是集成目标。

---

## 四、稳定性与兼容性横向分析

### 4.1 网络层（DLNA 落地的主要坑）
- **组播可用性**：SSDP 依赖 UDP 组播，部分 AP 开启「AP 隔离/客户端隔离」会直接阻断；企业网、访客网络常被禁。
- **MulticastLock 必开**：Android 默认过滤组播，不申请 `CHANGE_WIFI_MULTICAST_STATE` 并持有 `MulticastLock`，会出现「接收端发了 NOTIFY 但没人发现」或「收不到 M-SEARCH」（社区大量案例为此外因）。
- **多网卡选择**：设备同时存在 Wi-Fi / 热点 / 蜂窝 / VPN 时，UPnP 栈可能绑定到错误网卡；jUPnP 支持配置网卡选择，但需要在网络切换时正确处理。
- **网卡上下线**：Wi-Fi 断开重连会导致 SSDP 心跳中断，需要区分「设备离线」与「网络切换」，并重新广播。

### 4.2 平台层（Android 版本与后台限制）
- **前台服务**：接收端必须在**前台服务**中运行（用户可感知的常驻通知），并在 Android 14+ 正确声明 `foregroundServiceType`（`mediaPlayback` 或 `dataSync`），否则后台会被杀、SSDP 心跳中断。
- **网络权限**：`INTERNET`（普通）+ `ACCESS_NETWORK_STATE` + `ACCESS_WIFI_STATE`（普通）+ `CHANGE_WIFI_MULTICAST_STATE`（普通，但须实际 acquire）。Wi-Fi 相关均**不是**运行时危险权限，接入成本低。
- **后台启动限制**（Android 12+）：不得在后台自行启动前台服务；但「用户打开设置开关」是明确的前台交互，可在此时启动，合规。
- **模拟器限制**：Android 模拟器不支持接收组播（jUPnP 官方明确），发现类功能**必须真机验证**；模拟器内还可能受网络桥接方式影响（MuMu 等需实测）。

### 4.3 生态与内容层
- **发送端兼容差异**：国内主流视频 App 的「投屏」按钮多数走**私有协议（乐播等）**或自研通道，**同时**保留 DLNA 兜底。只做标准 DLNA 时，能否被发现、被投送，取决于发送端是否保留 DLNA 通道（腾讯视频、爱奇艺、优酷、B 站等通常保留）。
- **DLNA 实现碎片化**：各家 DMC 对设备描述、`ConnectionManager` 能力、DIDL-Lite 元数据的依赖程度不一；作为**接收端（被动方）**压力较小，但仍需对空/异常元数据容错。
- **投过来的 URL 形态多样**：MP4、HLS（m3u8）、TS、以及带防盗链/时效签名的地址；**受 DRM 保护的内容（Widevine/私有加密）在接收端若无对应 DRM 能力则无法播放**。
- **镜像需求无法通过 DLNA 满足**：游戏、桌面演示等「屏幕镜像」场景，DLNA 完全无能为力，只有 Miracast / AirPlay / 私有镜像协议可行——而前两者在普通 Android App 上不可行。

### 4.4 播放侧兼容性（本项目的相对优势）
- 本项目已有**四套解码后端**（ExoPlayer/Media3、IJK、系统 MediaPlayer、MPV）与网络源播放能力（含 SMB），**对「投过来的 URL」的容器/编码兼容度明显高于普通播放器**，这是集成 DLNA 后的一项天然优势（HLS/TS/冷门封装有 MPV/IJK 兜底）。

---

## 五、集成到本项目的大致思路

### 5.1 目标界定（建议先明确边界）
- 本期只做 **「URL 推送式接收」（DLNA/UPnP-AV MediaRenderer）**，即：接收其他 App 推送的视频地址并播放。
- **不做屏幕镜像**（受系统权限与合规限制，普通 App 无法实现）。
- 明确「投屏」开关的语义：开启后本机在局域网内**可见为一个可投屏设备**（出现在手机 App 的投屏列表里）。

### 5.2 技术架构（分四块）
1. **设置开关（UI + 持久化）**
   - 在设置页新增一行开关（复用现有 `AuraSwitch` 行组件），状态 `castReceiverEnabled`。
   - 持久化遵循本项目约定：写入 `vr_player_prefs`，**同时改三处**（集中写回 `LaunchedEffect` 的 key 列表、`put*` 写回块、`else` 分支的 `remove(...)`），受 `is_memory_mode_enabled` 记忆模式门控；参考现有布尔项（如 `huawei_vr_enabled`）写法。
   - 建议在开关行下方给一行说明文字（如「开启后可在同一 Wi-Fi 下被手机投屏」），避免用户误以为能接收屏幕镜像。

2. **前台服务承载 UPnP 栈**
   - 新增 `CastReceiverService`（`startForeground`，常驻通知，Android 14+ 声明 `foregroundServiceType="mediaPlayback"`）。
   - 服务内启动 jUPnP `UpnpService`（`AndroidUpnpService` 或自建 Service 配置），注册 `LocalDevice`：类型 `urn:schemas-upnp-org:device:MediaRenderer:1`，`UDN` 固定持久化（避免每次重装换 UUID 导致发送端记忆失效），`friendlyName` 用应用名/设备名。
   - 持有 `MulticastLock`（随服务生命周期），并监听网络变化，Wi-Fi 重连后重建 UPnP 栈。
   - 服务与 UI 的解绑：用进程内单例 + `SharedFlow`/`StateFlow` 通信（避免广播），开关关闭即释放并停服。

3. **DMR 服务实现（协议侧）**
   - `AVTransport:1`：`SetAVTransportURI`（取 URL → 交给播放层）、`Play/Pause/Stop/Seek`、`GetTransportInfo/GetPositionInfo`（回读进度与状态，供 DMC 显示）。
   - `ConnectionManager:1`：声明支持的协议信息（`http-get:*:video/*` 等）。
   - `RenderingControl:1`：音量/静音映射到本机播放器音量。
   - 通过 `GENA` 把播放状态变化回推给 DMC（部分发送端依赖该事件更新 UI）。

4. **播放层对接（复用现有管线——关键收益点）**
   - 定义「外部媒体请求」通道（如 `CastMediaBus`：`SharedFlow<CastRequest>`，含 `uri`、`title`、可选 metadata）。
   - 在 `VRPlayerScreen` 中收集该流 → 直接走**现有播放入口**：设置 `videoUriStr` → 触发现有 prepare/后端选择流程（`ExoBackend`/IJK/系统/MPV 自动选择，投影判定、字幕、美颜、VR 渲染**全部自动继承**）。
   - 处理两类中断策略：当前正在播放时收到投屏请求 → 暂停/停止当前并切换；无播放时直接起播。建议给出「即将播放：xxx」的轻提示。
   - 音频焦点：投屏起播时申请 AudioFocus；收到 `Stop` 指令时释放。

### 5.3 VR 场景的特别考量
- **投影模式**：投过来的多为普通 2D 视频，建议默认走 2D 平面（保留现有 `smart_projection` / `force_video_type` 判定），避免误判为 360/VR 造成画面异常。
- **交互映射**：DMC 的播放/暂停/Seek 指令需与现有控制栏、悬浮球、进度条状态**双向同步**（避免「手机点了暂停、大屏进度条还在走」）。
- **后台与息屏**：VR 使用场景下屏幕常亮，但仍需正确处理 Activity 前后台（服务独立于 Activity 存活）。

### 5.4 依赖与构建注意点
- 依赖：`org.jupnp:org.jupnp` + `org.jupnp.android` + `org.jupnp.support`（3.0.4）+ Jetty 9（`jetty-server/servlet/client`）+ `slf4j`。**纯 Java，不影响现有 arm64-v8a ABI 策略**。
- 打包：Jetty/jUPnP 会带入 `META-INF` 许可与 `services` 文件，需在 `packaging.resources.excludes` 中排除常见冲突项；如遇 Java 8+ API（`java.time` 等）需确认 desugaring 配置。
- 体积：预计增加数 MB（Jetty 为主要部分）；可评估用更轻的 `AndroidUpnpServiceConfiguration`/替换 HTTP 传输以减重。
- 保活与功耗：UPnP 心跳（SSDP `NOTIFY`）会周期性发组播，需注意息屏/后台的功耗；建议仅在开关开启且 Wi-Fi 连接时运行。

### 5.5 测试与验收
- **模拟器/模拟网络不可靠**（组播），必须**真机 + 真实路由器**验证；MuMu 的网络桥接是否转发组播需实测。
- 推荐验收矩阵：发送端 × 内容类型 —— 腾讯视频 / 爱奇艺 / 优酷 / B 站 / 本地 DLNA 应用（如 BubbleUPnP） ×（普通 MP4、HLS、HTTPS 防盗链地址）。
- 辅助工具：用 `UPnPCast` 或系统投屏做**自投自收**；抓 SSDP 日志（`M-SEARCH`/`NOTIFY`）确认发现链路；用 VLC 作为对照接收端排除「发送端本身不发 DLNA」的可能。

### 5.6 分阶段落地建议
- **P1（MVP）**：jUPnP DMR + 设置开关 + 前台服务 + 播放复用；跑通「手机 App 发现本机 → 点投屏 → 本机播放」。
- **P2（体验）**：进度/状态回读（GENA）、Seek/音量、断线重连、网络切换、多发送端兼容性打磨、播放失败兜底提示（URL 失效/DRM 不支持）。
- **P3（可选）**：AirPlay 接收（UxPlay/JNI）——**前置条件**是先解决 GPLv3 合规与 DRM 限制；若产品接受开源或能隔离该模块，再评估。
- **P4（可选）**：接入商业 SDK（乐播等）以覆盖私有协议；属于商务决策，与「开源」诉求冲突。

---

## 六、优缺点对比与推荐结论

### 6.1 方案对比（按可行性）

| 方案 | 协议能力 | 可行性 | 许可证/成本 | 主要风险 | 评级 |
|---|---|---|---|---|---|
| **jUPnP 3.x 自建 DMR** | DLNA/UPnP-AV（URL 推送） | 高（纯 Java，文档与参考工程齐全） | CDDL-1.0，可闭源商用，免费 | 组播/网络环境、发送端是否走 DLNA | **首选** |
| Cling 2.1.2 自建 DMR | 同上 | 中（EOL，需自行适配新版 Android） | LGPL-2.1/CDDL | 无维护、兼容坑自担 | 备选 |
| Platinum + JNI 自建 DMR | 同上 | 中低（NDK/scons/JNI 成本） | GPL 系，需核实 | 构建链陈旧、桥接复杂 | 备选 |
| UxPlay 系 AirPlay 接收 | AirPlay 镜像/音频 | 中（有现成 Android 工程） | **GPLv3 传染**、原生依赖重 | **不支持 DRM**、合规冲突 | 不建议（除非接受开源） |
| Miracast 接收 | 屏幕镜像 | **不可行**（系统签名权限） | — | 无 API 可用 | 排除 |
| Google Cast 接收 | Cast | **不可行**（无接收端 API） | — | — | 排除 |
| 乐播等商业 SDK | DLNA+AirPlay+私有 | 高（成熟） | 商业授权费、闭源 | 依赖第三方、成本 | 兜底/商务选项 |
| 自研私有协议 | 自定义 | 低（需发送端配合） | 自研成本 | 无生态、无人可投 | 排除 |

### 6.2 推荐结论
1. **首选：jUPnP 3.x 实现 DLNA/UPnP-AV MediaRenderer（DMR）接收端。** 唯一同时满足「纯软件实现、开源、可闭源商用、活跃维护、与现有播放管线无缝衔接」的路线。核心工作量在「注册 DMR 设备 + 实现 AVTransport 回调 + 前台服务保活 + 开关持久化」，播放、解码、投影、字幕等全部复用现有能力。
2. 若追求「更小体积/更低运行时开销」且团队具备 NDK 能力，可将 **Platinum** 作为 P2 起点的性能备选。
3. **不建议**投入 AirPlay 接收（GPLv3 与 DRM 双重硬约束）、Miracast（权限不可行）、Google Cast（无 API）。
4. 若产品目标要求「覆盖国内绝大多数 App 的投屏按钮」（含私有协议）或「支持屏幕镜像」，则**只能在商业 SDK 与自研之间做取舍**——这已超出「开源集成」的范畴，属于产品与商务决策。
5. **务必在需求侧明确预期**：本方案接收的是**「视频地址推送」**，不是**「手机屏幕镜像」**；后者在普通 Android 应用上不可达。建议在设置开关旁以文案说明，避免用户误解。

---

## 七、附：许可证与合规提示

- **jUPnP（CDDL-1.0）**：文件级弱 copyleft，可商用闭源；如需修改 CDDL 源文件，须公开被修改文件的源码。
- **Cling（LGPL-2.1 / CDDL）**：LGPL 为库级 copyleft，动态链接方式使用通常可闭源，但需提供 LGPL 声明与替换库的可行性说明。
- **UxPlay / android-airplay-server / airplay-screen-receiver（GPLv3）**：**强 copyleft**。与 App 组合分发时，整个应用需以 GPLv3 发布并提供完整对应源码——对闭源产品不可接受。
- **Platinum**：GPL 系（需按具体版本核实），同样存在 copyleft 风险。
- **乐播等商业 SDK**：闭源商业授权，需评估费用、数据合规与依赖风险。

---

## 八、参考来源（调研依据）

- jUPnP 官方文档《jUPnP on Android》（`jupnp.org/docs/android`）；`org.jupnp:org.jupnp.android` 3.0.4 发布信息（libraries.io）
- Cling 官方仓库与 EOL 说明（GitHub `4thline/cling`）
- 《Android 实现 DLNA 三端（DMR/DMC/DMS）开发实践与踩坑指南》《Android DLNA 投屏完整实现：基于 Cling 库…》（CSDN，含库选型现状对比）
- CyberLink for Java 实现 MediaRenderer「发现不了」与 `MulticastLock`（CSDN）
- `alienwolf-npu/ShowDlnaMediaRenderer`（基于 Platinum 的 Android DMR SDK）；《基于 Platinum 库的 DMR 实现（android）》
- `yinnho/UPnPCast`（现代 DLNA 发送端库，README）
- `GSYVideoPlayer/doc/CAST_FEATURE_PLAN.md`（jUPnP 3.0.3 接收端架构与自机自收方案）
- `jqssun/android-airplay-server`（XDA 帖与项目 README）、`bertanbakir/airplay-screen-receiver`（GitHub）
- Miracast 权限分析：《Miracast 技术详解（五）：Permission 问题处理》（`CONFIGURE_WIFI_DISPLAY` 仅系统应用）、StackOverflow 相关讨论
- 乐播投屏官方资料（接收端 SDK、Lelink 协议、生态覆盖口径）与第三方实测文章
