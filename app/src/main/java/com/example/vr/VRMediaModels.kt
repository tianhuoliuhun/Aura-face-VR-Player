package com.example.vr

import android.net.Uri

enum class ProjectionMode(val displayName: String, val id: Int) {
    STANDARD("标准平面", 0),
    FISHEYE("鱼眼广角", 1),
    VR_360("360°全景", 2),
    VR_180("180°穹幕", 3),
    BOX("盒子模式", 4)
}

enum class WarpMode(val displayName: String, val id: Int) {
    NONE("无", 0),
    CYLINDER_RECT("等距矩形柱面", 1),
    CYLINDER("等距圆柱", 2),
    SPHERE("立体球面", 3),
    CURVE("环幕曲面", 4),
    ANTI_SPHERE("反向球面", 5),
    ANTI_CURVE("反向曲面", 6)
}

enum class MaxResolution(val displayName: String, val width: Int, val height: Int, val id: Int) {
    UNRESTRICTED("无限制", Integer.MAX_VALUE, Integer.MAX_VALUE, 0),
    K8("8K (7680x4320)", 7680, 4320, 1),
    K4("4K (3840x2160)", 3840, 2160, 2),
    K2("2K (2560x1440)", 2560, 1440, 3),
    FHD("1080P (1920x1080)", 1920, 1080, 4),
    HD("720P (1280x720)", 1280, 720, 5)
}

enum class StereoMode(val displayName: String, val id: Int) {
    MONO("常规2D", 0),
    SBS("3D 左右立体 (Side-by-Side)", 1),
    TAB("3D 上下立体 (Top-Bottom)", 2)
}

enum class DecoderEngine(val displayName: String, val tag: String, val id: Int) {
    EXO("EXO 解码器", "Google ExoPlayer 标准高清引擎", 0),
    MPV("MPV 解码器", "MPV FFmpeg 万能解码内核", 1)
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
