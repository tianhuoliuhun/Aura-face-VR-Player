package com.example.vr

import androidx.annotation.StringRes
import com.example.R

import android.net.Uri

enum class ProjectionMode(val displayName: String, @StringRes val labelRes: Int, val id: Int) {
    STANDARD("标准平面", R.string.proj_flat, 0),
    FISHEYE("鱼眼广角", R.string.proj_fisheye, 1),
    VR_360("360°全景", R.string.proj_360, 2),
    VR_180("180°穹幕", R.string.proj_180, 3),
    BOX("盒子模式", R.string.proj_box, 4),

    /**
     * **EAC（Equi-Angular Cubemap，等角立方体贴图）**
     *
     * YouTube / Google 的 360° 片源格式：源码流是 3×2 排布的 6 个立方体面，
     * 且每个面内做了 `tan(π/4·(2s−1))` 的**等角重映射**（相比普通立方体贴图 CMP，
     * 球面上采样间距更均匀，面边界不再过采样）。
     *
     * 与 [VR_360] 的关系：**顶点几何完全相同**（都是球面），只是 UV 换成
     * EAC atlas 映射 —— 见 [GeometryHelper.generateEacSphere]。
     * 用 VR_360 播放 EAC 片源时，画面在 6 个面接缝处会明显错位/拉伸。
     */
    EAC("EAC 立方体贴图", R.string.proj_eac, 5),

    /**
     * **Dome Master（球幕 / 天文馆穹顶）** —— v2.1.212
     *
     * 上半球片源（天文馆、球幕影院发行）。画面本身是**一个圆 + 四角黑**，
     * 播放器只需正确映射上半球，圆外自然显示为黑，无需额外做遮罩。
     *
     * 与 [VR_180] 的区别：[VR_180] 是**经度**方向的半球（等距柱状 180 度），
     * 而 Dome 是**纬度**方向的上半球（天顶到赤道）—— 两者网格正交，不能复用。
     */
    DOME("球幕（半球）", R.string.proj_dome, 6)
}

enum class WarpMode(val displayName: String, @StringRes val labelRes: Int, val id: Int) {
    NONE("无", R.string.warp_none_opt, 0),
    CYLINDER_RECT("等距矩形柱面", R.string.warp_cylinder_rect, 1),
    CYLINDER("等距圆柱", R.string.warp_cylinder2, 2),
    SPHERE("立体球面", R.string.warp_sphere2, 3),
    CURVE("环幕曲面", R.string.warp_curve2, 4),
    ANTI_SPHERE("反向球面", R.string.warp_anti_sphere, 5),
    ANTI_CURVE("反向曲面", R.string.warp_anti_curve, 6)
}

enum class MaxResolution(val displayName: String, @StringRes val labelRes: Int, val width: Int, val height: Int, val id: Int) {
    UNRESTRICTED("无限制", R.string.res_unrestricted, Integer.MAX_VALUE, Integer.MAX_VALUE, 0),
    K8("8K (7680x4320)", R.string.res_8k, 7680, 4320, 1),
    K4("4K (3840x2160)", R.string.res_4k, 3840, 2160, 2),
    K2("2K (2560x1440)", R.string.res_2k, 2560, 1440, 3),
    FHD("1080P (1920x1080)", R.string.res_1080p, 1920, 1080, 4),
    HD("720P (1280x720)", R.string.res_720p, 1280, 720, 5)
}

enum class StereoMode(val displayName: String, @StringRes val labelRes: Int, val id: Int) {
    MONO("常规2D", R.string.stereo_mono, 0),
    SBS("3D 左右立体 (Side-by-Side)", R.string.stereo_sbs_full, 1),
    TAB("3D 上下立体 (Top-Bottom)", R.string.stereo_tab_full, 2)
}

/**
 * 解码器内核。
 *
 * ⚠️ v2.1.234 现状：**EXO / IJK / MPV 三个都已接通**，没有预留位了。
 *
 * 选择逻辑见 `VRPlayerScreen`（未实现的内核会被置灰，且读取存档时回落为 EXO）。
 */
enum class DecoderEngine(val displayName: String, @StringRes val labelRes: Int, val tag: String, val id: Int) {
    EXO("EXO 解码器", R.string.decoder_exo, "Google ExoPlayer 标准高清引擎", 0),
    IJK("IJK 解码器", R.string.decoder_ijk, "Bilibili ijkplayer（基于 FFmpeg）", 2),
    MPV("MPV 解码器", R.string.decoder_mpv, "MPV FFmpeg 万能解码内核", 1),

    /**
     * 系统解码 —— 走 **Android Framework 自带的 `MediaPlayer`**，而不是第三方内核。
     *
     * ## 它的价值在哪
     * Exo/IJK/MPV 都是「自带一套解码栈」。而系统 MediaPlayer 用的是**厂商 ROM 自己的
     * 解码管线** —— 国产 ROM（华为/小米/OPPO…）常在其中集成**私有增强解码器**
     * （自研格式支持、更好的功耗控制、某些 DRM/超分能力）。同一台设备上，
     * 系统解码能放的面源类型与硬解路径，未必和 Exo 走 MediaCodec 时一致。
     * 所以它是一条**独立的兜底路径**：当某个片源在 Exo/IJK 下都不正常时，值得一试。
     *
     * ⚠️ 但它的 API 很老（`MediaPlayer` 从 API 1 就在），**各 ROM 行为差异较大** ——
     * 所以它是**可选**项、并且失败时必定回退 EXO（见 `SystemPlayerBackend`）。
     */
    SYSTEM("系统解码器", R.string.decoder_system, "调用系统原生解码能力（MediaPlayer）", 3);

    /**
     * 该内核是否已经接通播放链路。
     *
     * 写在这里而不是散落在 UI 里：UI 的置灰判断、prefs 读取时的回落校正
     * 都从这里取值 —— **新增内核时只需改这一处**。
     *
     * v2.1.234：三个内核全部接通（`IjkPlayerBackend.kt` / `MpvPlayerBackend.kt`）。
     * 注意"已接通"只意味着**代码链路齐备**；native 库是否装上、片源协议与编码
     * 该内核吃不吃得下，都在 `VRPlayerScreen.setupVideoPlayer` 里做**运行时判定**，
     * 判定不过会自动回退 EXO（绝不黑屏）。
     *
     * v2.1.236：MPV 由 [MPV_ENABLED] 总开关控制 —— 关掉后它会从选择列表里消失，
     * 且存档里遗留的 MPV 选择会在读取时**自动回落 EXO**（见 `VRPlayerScreen` 的
     * prefs 读取处：那里的 `find { it.id == id && it.isImplemented }` 正是靠本属性过滤）。
     * **注意本属性同时承担"UI 是否展示"的职责**，所以停用一个内核不用改 UI 代码。
     *
 * v2.1.240：SYSTEM（系统解码）无需 native 库、也不依赖任何可选组件，
 * 所以**恒为可用** —— 它只在运行时（MediaPlayer 创建/prepare 失败）才回退 EXO。
 *
 * v2.1.243：MPV **重新启用**（[MPV_ENABLED] = true）。原因是查实了 IJK 的裁剪版
 * FFmpeg 解不了 WMV/RM（软解器被裁、硬解全关、MediaCodec 白名单无 WMV），
 * 而 EXO 也没有 ASF/RealMedia 解析器 —— **MPV 是这些格式唯一的出路**。
 */
    val isImplemented: Boolean
        get() = this != MPV || MPV_ENABLED
}

/**
 * **MPV 内核总开关** —— v2.1.236 首次引入，v2.1.243 重新打开。
 *
 * ## 为什么 v2.1.236 曾关闭
 * 用户当时反馈"暂时不需要 MPV 了"，于是从界面上停用。这里刻意**用开关而不是把代码
 * 注释掉**，原因有三：
 *  1. 注释掉大段代码极易引发语法错（本项目的 Kotlin 块注释**可嵌套**，注释里出现
 *     斜杠紧跟星号就会吃掉后面整个文件），且 IDE 无法对注释代码做引用检查；
 *  2. 关闭后此处的分支都**不可达**，运行时效果与"注释掉"完全一致 —— UI 里没有 MPV、
 *     存档回落到 EXO、下载面板也永远不会显示；
 *  3. 将来要恢复只需把这里改回 `true`，**一处生效**（`isImplemented` 已经承担了
 *     "UI 是否展示 / 是否允许选中"的全部判断）。
 *
 * ## 为什么 v2.1.243 重新打开（当前）
 * 实测日志证实：`wmv/asf/rm/rmvb` 这几个容器在本项目的 IJK 构建上**无解**
 * （`No codec could be found with id 18` → `Error (-10000,0)`），EXO 也无解析器。
 * 而 MPV 自带**完整** libavcodec，是唯一能播这些格式的内核。
 * 详见 `MediaFormats.IJK_ONLY` 的注释与 `docs/CHANGELOG.md` v2.1.243 条目。
 *
 * 关闭时**仍然保留**（不删、不失效）的东西：
 *  - `MpvPlayerBackend.kt` / `MpvLibLoader.kt` / `MpvLibPanel.kt` / `MpvOptionsPanel.kt`
 *    全部代码与 `packaging.jniLibs.excludes` 里的排除项；
 *  - 已下载到 `filesDir/mpv-libs/` 的库文件（不会自动删；将来恢复就不用重新下）。
 *    ⚠️ 已下载过 MPV 库的用户在开关关闭期间**看不到删除入口**（那段 UI 在 MPV 分支内，
 *       不可达）。那部分空间（约 36MB）只能靠"清除应用数据"释放 —— 可接受，
 *       因为恢复开关后入口就回来了。
 */
const val MPV_ENABLED = true

data class MediaItem(
    val id: String,
    val title: String,
    val uri: String?, // String description of uri or empty for embedded images
    val isVideo: Boolean,
    val isDemo: Boolean = false,
    val demoAssetPath: String? = null,
    val description: String = ""
)
