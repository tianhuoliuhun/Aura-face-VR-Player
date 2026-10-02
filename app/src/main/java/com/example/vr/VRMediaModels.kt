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
    EAC("EAC 立方体贴图", R.string.proj_eac, 5)
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

enum class DecoderEngine(val displayName: String, @StringRes val labelRes: Int, val tag: String, val id: Int) {
    EXO("EXO 解码器", R.string.decoder_exo, "Google ExoPlayer 标准高清引擎", 0),
    MPV("MPV 解码器", R.string.decoder_mpv, "MPV FFmpeg 万能解码内核", 1)
}

data class MediaItem(
    val id: String,
    val title: String,
    val uri: String?, // String description of uri or empty for embedded images
    val isVideo: Boolean,
    val isDemo: Boolean = false,
    val demoAssetPath: String? = null,
    val description: String = ""
)
