package com.example.vr

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 系统视频/音频编解码能力探测 —— 供「实验性功能 → 系统编解码能力检测」使用。
 *
 * ## 它和 [DecoderCapabilities] 的区别（别合并）
 * [DecoderCapabilities] 只回答一个窄问题：**「这个 MIME 的硬件解码最大分辨率是多少」**，
 * 调用方是 8K 自动降采样那套流程，要求极快、可缓存、只覆盖 hevc/avc。
 *
 * 本对象回答的是给**人看**的问题：这台机器到底有哪些编解码器、各自的上限、
 * 能开哪些封装 —— 所以：全量枚举（含编码器与音频）、带实测档位校验、在主线程外跑。
 * 两者的判定口径必须一致（硬件/软件的判定都走 [DecoderCapabilities.isHardwareAccelerated]），
 * 但**不要**把这里的重活塞进播放路径。
 *
 * ## 几个必须踩准的坑（都有代价）
 * 1. **`isHardwareAccelerated()` 是 API 29** → 统一走 [DecoderCapabilities.isHardwareAccelerated]。
 * 2. **`isAlias()` 是 API 29** → 低版本拿不到别名信息，直接当 false（会多列几条重复项，
 *    但不会崩；高版本过滤掉别名后列表才干净）。
 * 3. **`getSupportedPerformancePoints()` 是 API 29 且可能返回 null** → 全部包在 SDK 判断里。
 * 4. **`getCapabilitiesForType()` 会抛 `IllegalArgumentException`**（已知某些 ROM 上报了
 *    MIME 但查能力直接抛）→ 每个 codec×mime 单独 try，**不能**让一个坏 codec 毁掉整份报告。
 * 5. **`isSizeSupported()` 才是真话** → 「标称上限」与「实测档位」分开存，
 *    UI 只把后者放进摘要（见 [CodecCapabilityModel.bestHardwareDecoder]）。
 */
object CodecCapabilityProbe {

    private const val TAG = "CodecCapabilityProbe"

    /**
     * 枚举全量编解码器并整理成报告。
     *
     * ⚠️ 一定要在后台线程调用：`ALL_CODECS` 在部分 ROM 上要创建 codec 实例去查能力，
     * 一个设备 30+ 个 codec 时能卡几百毫秒，放主线程会掉帧。
     */
    suspend fun probe(
        context: Context,
        currentEngine: DecoderEngine
    ): CodecCapabilityModel.CodecReport = withContext(Dispatchers.IO) {
        val entries = mutableListOf<CodecCapabilityModel.CodecEntry>()
        // ALL_CODECS 而非 REGULAR_CODECS：后者会隐藏「不可用于常规选择」的实现，
        // 而用户想看到的正是「本机到底有没有 AV1 解码器」这种问题。
        val list = try {
            MediaCodecList(MediaCodecList.ALL_CODECS)
        } catch (e: Exception) {
            Log.w(TAG, "MediaCodecList 创建失败", e)
            null
        }

        list?.codecInfos?.forEach { info ->
            val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    info.isAlias
                } catch (e: Exception) {
                    false
                }
            } else false
            // 别名是同一个底层 codec 的另一条注册项，展示出来只是重复
            if (alias) return@forEach

            val hardware = DecoderCapabilities.isHardwareAccelerated(info)
            val vendor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    info.isVendor
                } catch (e: Exception) {
                    true
                }
            } else true

            for (mime in info.supportedTypes) {
                val lower = mime.lowercase()
                if (!lower.startsWith("video/") && !lower.startsWith("audio/")) continue
                try {
                    val caps = info.getCapabilitiesForType(mime)
                    val entry = if (lower.startsWith("video/")) {
                        CodecCapabilityModel.CodecEntry(
                            name = info.name,
                            mime = lower,
                            encoder = info.isEncoder,
                            hardware = hardware,
                            vendor = vendor,
                            video = readVideoCaps(caps)
                        )
                    } else {
                        CodecCapabilityModel.CodecEntry(
                            name = info.name,
                            mime = lower,
                            encoder = info.isEncoder,
                            hardware = hardware,
                            vendor = vendor,
                            audio = readAudioCaps(caps)
                        )
                    }
                    entries += entry
                } catch (e: Exception) {
                    // 已知部分 ROM 会在这里抛 IllegalArgumentException / SecurityException
                    Log.w(TAG, "skip ${info.name} / $mime: ${e.javaClass.simpleName}")
                }
            }
        }

        // ⚠️ Boolean 不是 Comparable，排序键一律转成 Int，别写成 `{ it.encoder }`
        val sorted = entries.sortedWith(
            compareBy<CodecCapabilityModel.CodecEntry>(
                { if (it.encoder) 1 else 0 },              // 解码在前
                { if (it.isVideo) 0 else 1 },              // 视频在前
                { if (it.hardware) 0 else 1 },             // 硬件在前
                { -(it.video?.let { v -> CodecCapabilityModel.pixels(v.tierWidth, v.tierHeight) } ?: 0L) },
                { it.mime },
                { it.name }
            )
        )

        CodecCapabilityModel.CodecReport(
            device = readDeviceInfo(context, currentEngine),
            entries = sorted,
            containers = buildContainerGroups()
        )
    }

    // ===================== 设备信息 =====================

    private fun readDeviceInfo(context: Context, engine: DecoderEngine): CodecCapabilityModel.DeviceInfo {
        val soc = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE
        } catch (e: Exception) {
            Build.HARDWARE
        }
        val abis = try {
            Build.SUPPORTED_ABIS.joinToString(", ")
        } catch (e: Exception) {
            Build.CPU_ABI ?: "?"
        }
        val mpv = try {
            MpvLibLoader.isInstalled(context)
        } catch (e: Exception) {
            false
        }
        return CodecCapabilityModel.DeviceInfo(
            model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            androidRelease = Build.VERSION.RELEASE ?: "?",
            sdk = Build.VERSION.SDK_INT,
            soc = soc ?: Build.HARDWARE ?: "?",
            abi = abis,
            currentEngine = engine.displayName,
            mpvInstalled = mpv
        )
    }

    // ===================== 视频能力 =====================

    private fun readVideoCaps(caps: MediaCodecInfo.CodecCapabilities): CodecCapabilityModel.VideoCaps? {
        val vc = caps.videoCapabilities ?: return null
        val widths = vc.supportedWidths
        val heights = vc.supportedHeights
        val fps = vc.supportedFrameRates
        val bitrate = vc.bitrateRange

        // 实测档位：标称上限经常「不可组合」，必须逐档问 isSizeSupported
        var tierW = 0
        var tierH = 0
        var tierFps = 0.0
        for ((w, h) in CodecCapabilityModel.KNOWN_TIERS) {
            if (w > widths.upper || h > heights.upper) continue
            val ok = try {
                vc.isSizeSupported(w, h)
            } catch (e: Exception) {
                false
            }
            if (ok) {
                tierW = w
                tierH = h
                tierFps = try {
                    vc.getSupportedFrameRatesFor(w, h)?.upper?.toDouble() ?: 0.0
                } catch (e: Exception) {
                    0.0
                }
                break
            }
        }

        val formats = try {
            caps.colorFormats?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        // VR 全景档位：与上面的「最高档」分开逐项各测一次。
        // 为什么不能只靠上面那组：2:1 的片子宽度常先撞 supportedWidths.upper
        // （很多 8K 解码器标称宽只到 7680），而上面那组只会保留一个命中项。
        //
        // ⚠️ 不支持的档还要回答「差在哪」：实测机型上 7680×4320 能解、8192×4096 不能，
        // 像素只差 1.1%，标称宽度都是 8192 —— 卡的是 block-count。只有
        // getSupportedWidthsFor(h) / getSupportedHeightsFor(w) 这种「固定一维问另一维」的
        // 问法才能把真正的上限逼出来，所以这里顺带把它记下（见 VrSupport.limitWidth）。
        val vrTiers = CodecCapabilityModel.VR_PANORAMA_TIERS.map { (w, h) ->
            val withinRange = w <= widths.upper && h <= heights.upper
            val ok = withinRange && try {
                vc.isSizeSupported(w, h)
            } catch (e: Exception) {
                false
            }
            CodecCapabilityModel.VrSupport(
                width = w,
                height = h,
                supported = ok,
                maxFps = if (ok) {
                    try {
                        vc.getSupportedFrameRatesFor(w, h)?.upper?.toDouble() ?: 0.0
                    } catch (e: Exception) {
                        0.0
                    }
                } else {
                    0.0
                },
                // 不支持时才需要「实际能到多少」：把目标尺寸按可达值收缩，语义恒为
                // 「这一档最多能到 limitWidth×limitHeight」。
                limitWidth = if (ok) 0 else minOf(w, widthAtHeight(vc, h, widths.upper)),
                limitHeight = if (ok) 0 else minOf(h, heightAtWidth(vc, w, heights.upper))
            )
        }

        // 4096 高度下实际能到多宽 —— 这是「能不能吃 8K 全景 2:1」的直接答案。
        //
        // 为什么值得单存一份常显：`vrTiers` 只回答「哪一档能过」，用户还得自己反推；
        // 而这个数字把 block 预算的真相摊开了 —— 标称 8192×8192 的组件给 8192，
        // 标称 8192×4320 却吃不下 8192×4096 的组件只给 7680。
        // 高度本身到不了 4096 时无从谈起，保持 0（UI 不显示）。
        val widthAtPanorama = try {
            if (heights.upper >= CodecCapabilityModel.PANORAMA_HEIGHT) {
                vc.getSupportedWidthsFor(CodecCapabilityModel.PANORAMA_HEIGHT)?.upper ?: 0
            } else {
                0
            }
        } catch (e: Exception) {
            // getSupportedWidthsFor 在部分 ROM 上会对合法高度抛 IllegalArgumentException
            0
        }

        return CodecCapabilityModel.VideoCaps(
            minWidth = widths.lower,
            maxWidth = widths.upper,
            minHeight = heights.lower,
            maxHeight = heights.upper,
            widthAlign = safeAlign { vc.widthAlignment },
            heightAlign = safeAlign { vc.heightAlignment },
            minFps = fps.lower.toDouble(),
            maxFps = fps.upper.toDouble(),
            minBitrate = bitrate.lower,
            maxBitrate = bitrate.upper,
            colorFormats = formats,
            profiles = profileNames(caps),
            secure = featureSupported(caps, "feature-secure-playback"),
            adaptive = featureSupported(caps, "feature-adaptive-playback"),
            tunneled = featureSupported(caps, "feature-tunneled-playback"),
            maxInstances = caps.maxSupportedInstances,
            tierWidth = tierW,
            tierHeight = tierH,
            tierMaxFps = tierFps,
            vrTiers = vrTiers,
            widthAtPanoramaHeight = widthAtPanorama
        )
    }

    private fun safeAlign(block: () -> Int): Int = try {
        block()
    } catch (e: Exception) {
        0
    }

    /**
     * 固定高度，问「这个高度下能到多宽」。
     *
     * `getSupportedWidthsFor(h)` 是公开 API，它把 block-count / 每秒块数一起算进去了；
     * 而 `supportedWidths.upper` 只是宽度的单维上限。两者在 8K 边界上会差出关键的一档
     * （标称 8192 宽，4096 高度下只到 7680）。
     *
     * ⚠️ `h` 超出 `supportedHeights` 时该 API 会抛 `IllegalArgumentException`，此时只能用单维上限。
     */
    private fun widthAtHeight(
        vc: MediaCodecInfo.VideoCapabilities,
        height: Int,
        fallback: Int
    ): Int {
        if (height <= 0 || fallback <= 0) return fallback
        val maxHeight = try {
            vc.supportedHeights.upper
        } catch (e: Exception) {
            0
        }
        if (height > maxHeight) return fallback
        return try {
            vc.getSupportedWidthsFor(height)?.upper ?: fallback
        } catch (e: Exception) {
            fallback
        }
    }

    /** 固定宽度，问「这个宽度下能到多高」。语义与 [widthAtHeight] 对称。 */
    private fun heightAtWidth(
        vc: MediaCodecInfo.VideoCapabilities,
        width: Int,
        fallback: Int
    ): Int {
        if (width <= 0 || fallback <= 0) return fallback
        val maxWidth = try {
            vc.supportedWidths.upper
        } catch (e: Exception) {
            0
        }
        if (width > maxWidth) return fallback
        return try {
            vc.getSupportedHeightsFor(width)?.upper ?: fallback
        } catch (e: Exception) {
            fallback
        }
    }

    private fun featureSupported(caps: MediaCodecInfo.CodecCapabilities, name: String): Boolean =
        try {
            caps.isFeatureSupported(name)
        } catch (e: Exception) {
            false
        }

    // ===================== 音频能力 =====================

    private fun readAudioCaps(caps: MediaCodecInfo.CodecCapabilities): CodecCapabilityModel.AudioCaps? {
        val ac = caps.audioCapabilities ?: return null
        val rates = try {
            ac.supportedSampleRates?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        } ?: return null
        val bitrate = ac.bitrateRange
        return CodecCapabilityModel.AudioCaps(
            maxChannels = ac.maxInputChannelCount,
            minSampleRate = rates.min(),
            maxSampleRate = rates.max(),
            minBitrate = bitrate.lower,
            maxBitrate = bitrate.upper,
            tunneled = featureSupported(caps, "feature-tunneled-playback"),
            maxInstances = caps.maxSupportedInstances
        )
    }

    // ===================== profile =====================

    /**
     * 把 `profileLevels` 收敛成「去重后的 profile 名」。
     *
     * ⚠️ 故意**不展示 level**：level 是数字常量（AVC Level 4.1 = 0x2000），
     * 各编码的编号规则完全不同，映射表一旦漏项就会显示错误的值；
     * 而「最大分辨率 + 该分辨率下的帧率」已经把 level 的实际意义表达出来了。
     */
    private fun profileNames(caps: MediaCodecInfo.CodecCapabilities): List<String> {
        val levels = caps.profileLevels ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (pl in levels) {
            out += profileName(pl.profile)
        }
        return out.toList()
    }

    private fun profileName(profile: Int): String = when (profile) {
        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline -> "Baseline"
        MediaCodecInfo.CodecProfileLevel.AVCProfileMain -> "Main"
        MediaCodecInfo.CodecProfileLevel.AVCProfileExtended -> "Extended"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh -> "High"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 -> "High 10"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422 -> "High 4:2:2"
        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444 -> "High 4:4:4"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain -> "Main"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 -> "Main 10"
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMainStill -> "Main Still"
        MediaCodecInfo.CodecProfileLevel.VP9Profile0 -> "Profile 0"
        MediaCodecInfo.CodecProfileLevel.VP9Profile2 -> "Profile 2"
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8 -> "Main 8"
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 -> "Main 10"
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 -> "Main 10 HDR10"
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus -> "Main 10 HDR10+"
        MediaCodecInfo.CodecProfileLevel.MPEG4ProfileSimple -> "Simple"
        MediaCodecInfo.CodecProfileLevel.MPEG4ProfileAdvancedSimple -> "Advanced Simple"
        MediaCodecInfo.CodecProfileLevel.MPEG2ProfileSimple -> "Simple"
        MediaCodecInfo.CodecProfileLevel.MPEG2ProfileMain -> "Main"
        MediaCodecInfo.CodecProfileLevel.MPEG2ProfileHigh -> "High"
        MediaCodecInfo.CodecProfileLevel.AACObjectLC -> "LC"
        MediaCodecInfo.CodecProfileLevel.AACObjectHE -> "HE"
        MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS -> "HE-PS"
        else -> "0x" + Integer.toHexString(profile)
    }

    // ===================== 封装（容器）能力 =====================

    /**
     * 生成「扩展名 → 哪些内核能开」的映射。
     *
     * ⚠️⚠️ **扩展名一律从 [MediaFormats] 的集合取，绝不在这里另写一份白名单**。
     * 本项目踩过「同一份数据两处登记」的坑：新增容器时这里漏改，
     * 就会出现「能播放但检测页说不能播」的自相矛盾。
     *
     * 判定口径：
     * - EXO：不在 [MediaFormats.IJK_ONLY]（那批 = 仅完整 FFmpeg 能解）就能开；
     *   `iso` 走特殊魔数流程，单独按「可尝试」记 true 会在 UI 上说谎 → 判 false。
     * - IJK：不能是 [MediaFormats.EXO_ONLY]（裁剪版 FFmpeg 无 avi/ogg demuxer），
     *   也不能在 [MediaFormats.IJK_ONLY]（无 wmv/rm 解码器）。
     * - MPV：自带完整 libavcodec，全部可开；但 native 库要单独判是否已下载。
     */
    private fun buildContainerGroups(): List<CodecCapabilityModel.ContainerGroup> {
        val videoExts = LinkedHashSet<String>().apply {
            addAll(MediaFormats.COMMON)
            addAll(MediaFormats.IJK_ONLY)
            addAll(MediaFormats.AV1_RAW)
        }
        val rows = videoExts.map { ext ->
            val special = ext in MediaFormats.NEEDS_SPECIAL_HANDLING   // iso
            val exo = !special && ext !in MediaFormats.IJK_ONLY
            val ijk = !special && !MediaFormats.isExoOnly(ext) && ext !in MediaFormats.IJK_ONLY
            val mpv = !special
            ext to CodecCapabilityModel.ContainerGroup(
                label = ext,
                exts = listOf(ext),
                exo = exo,
                ijk = ijk,
                mpv = mpv
            )
        }
        return CodecCapabilityModel.groupContainers(rows)
    }
}
