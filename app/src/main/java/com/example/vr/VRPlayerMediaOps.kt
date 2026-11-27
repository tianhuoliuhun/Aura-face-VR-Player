package com.example.vr

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.example.R
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 媒体预处理（降分辨率转码 / FFmpeg 重封装 / H264 Level Patch）与 SMB 局域网浏览/播放操作，
 * 从 VRPlayerScreen.kt 拆出以精简主 UI 组件。
 */
internal object VRPlayerMediaOps {

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun startDownscalingTranscode(
        context: Context,
        scope: CoroutineScope,
        selectedMediaItem: MediaItem,
        maxResolution: MaxResolution,
        onSelectMedia: (MediaItem) -> Unit,
        onShowResolutionTipChange: (Boolean) -> Unit,
        onTranscodingChange: (Boolean) -> Unit,
        onTranscodeProgressChange: (Int) -> Unit,
        onTranscodeStatusChange: (String) -> Unit,
        onPhotoReload: () -> Unit,
        onKeepUiAlight: () -> Unit,
    ) {
    val uriStr = selectedMediaItem.uri ?: return
    if (maxResolution == MaxResolution.UNRESTRICTED) {
        Toast.makeText(context, context.getString(R.string.toast_select_resolution_first), Toast.LENGTH_SHORT).show()
        return
    }

    val targetHeight = maxResolution.height
    val targetWidth = maxResolution.width
    
    // Uniquely identify transcoded file by item id and target height to avoid collision
    val cacheFile = File(context.cacheDir, "transcoded_${selectedMediaItem.id}_${targetHeight}.mp4")

    // If cached file already exists, load and play it immediately
    if (cacheFile.exists() && cacheFile.length() > 1024) {
        Toast.makeText(context, context.getString(R.string.toast_cached_downscale, context.getString(maxResolution.labelRes)), Toast.LENGTH_SHORT).show()
        val transcodedMediaItem = selectedMediaItem.copy(
            title = selectedMediaItem.title + context.getString(R.string.media_downscale_suffix, context.getString(maxResolution.labelRes)),
            uri = cacheFile.absolutePath,
            isDemo = false
        )
        onSelectMedia(transcodedMediaItem)
        onShowResolutionTipChange(false)
        onPhotoReload()
        return
    }

    onTranscodingChange(true)
    onTranscodeProgressChange(0)
    onTranscodeStatusChange(context.getString(R.string.toast_transcode_init))

    scope.launch(Dispatchers.Main) {
        var tempOutFile: File? = null
        try {
            val inputUri = Uri.parse(uriStr)
            
            // Create Media3 effects list with presentation resizing
            val presentation = Presentation.createForHeight(targetHeight)
            val videoEffects = ImmutableList.of<Effect>(presentation)
            
            val editedMediaItem = EditedMediaItem.Builder(ExoMediaItem.fromUri(inputUri))
                .setEffects(Effects(ImmutableList.of(), videoEffects))
                .build()

            tempOutFile = File(context.cacheDir, "transcoding_${System.currentTimeMillis()}.mp4")
            withContext(Dispatchers.IO) {
                if (tempOutFile.exists()) tempOutFile.delete()
            }

            val transformer = Transformer.Builder(context)
                .build()

            var completed = false
            var errorException: Exception? = null

            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    completed = true
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exception: ExportException
                ) {
                    errorException = exception
                }
            })

            // Start the export process on the main thread
            transformer.start(editedMediaItem, tempOutFile.absolutePath)

            // Poll progress
            val progressHolder = ProgressHolder()
            while (!completed && errorException == null) {
                val progressState = transformer.getProgress(progressHolder)
                if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val pct = progressHolder.progress
                    onTranscodeProgressChange(pct)
                    onTranscodeStatusChange(context.getString(R.string.toast_transcoding_progress, context.getString(maxResolution.labelRes), pct))
                    onKeepUiAlight()
                }
                delay(500)
            }

            if (errorException != null) {
                throw errorException!!
            }

            // Copy temp file to cache file
            withContext(Dispatchers.IO) {
                if (tempOutFile.exists()) {
                    if (cacheFile.exists()) cacheFile.delete()
                    tempOutFile.renameTo(cacheFile)
                }
            }

            onTranscodingChange(false)
            Toast.makeText(context, context.getString(R.string.toast_transcode_ok, context.getString(maxResolution.labelRes)), Toast.LENGTH_LONG).show()
            val transcodedMediaItem = selectedMediaItem.copy(
                title = selectedMediaItem.title + context.getString(R.string.media_downscale_suffix, context.getString(maxResolution.labelRes)),
                uri = cacheFile.absolutePath,
                isDemo = false
            )
            onSelectMedia(transcodedMediaItem)
            onShowResolutionTipChange(false)
            onPhotoReload()
        } catch (e: Exception) {
            Log.e("VRPlayerScreen", "Transformer transcoding failed", e)
            onTranscodingChange(false)
            withContext(Dispatchers.IO) {
                tempOutFile?.let { if (it.exists()) it.delete() }
            }
            Toast.makeText(context, context.getString(R.string.toast_transcode_failed, e.localizedMessage), Toast.LENGTH_LONG).show()
        }
    }
    }

fun encodeSmb(url: String): String {
    val m = Regex("""smb://([^@/]+@)?([^/]+)(/.*)?""").find(url) ?: return url
    val creds = m.groupValues[1]
    val host = m.groupValues[2]
    val path = m.groupValues[3] ?: "/"
    val encodedPath = path.split("/").joinToString("/") { seg ->
        if (seg.isEmpty()) "" else java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
    }
    return "smb://$creds$host$encodedPath"
}

fun browseSmb(
        context: Context,
        scope: CoroutineScope,
        path: String,
        onSmbErrorChange: (String) -> Unit,
        onSmbPathChange: (String) -> Unit,
        onSmbEntriesChange: (List<jcifs.smb.SmbFile>) -> Unit,
    ) {
    onSmbErrorChange("")
    scope.launch(Dispatchers.IO) {
        try {
            val dir = jcifs.smb.SmbFile(path)
            if (!dir.exists() || !dir.isDirectory) {
                withContext(Dispatchers.Main) { onSmbErrorChange(context.getString(R.string.toast_path_not_exist, path)) }
                return@launch
            }
            val entries = dir.listFiles()?.toList() ?: emptyList()
            withContext(Dispatchers.Main) {
                onSmbPathChange(path)
                onSmbEntriesChange(entries)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onSmbErrorChange(context.getString(R.string.toast_connect_failed, (e.message ?: ""))) }
        }
    }
}

fun connectSmb(
        context: Context,
        scope: CoroutineScope,
        smbHost: String,
        smbUser: String,
        smbPass: String,
        onSmbErrorChange: (String) -> Unit,
        onSmbPathChange: (String) -> Unit,
        onSmbEntriesChange: (List<jcifs.smb.SmbFile>) -> Unit,
    ) {
    val host = smbHost.trim()
    if (host.isEmpty()) {
        onSmbErrorChange(context.getString(R.string.toast_enter_server_addr))
        return
    }
    val creds = if (smbUser.isNotBlank()) "${smbUser}:${smbPass}@" else ""
    browseSmb(context, scope, "smb://$creds$host/", onSmbErrorChange, onSmbPathChange, onSmbEntriesChange)
}

fun playSmbFile(
        context: Context,
        entry: jcifs.smb.SmbFile,
        onSelectMedia: (MediaItem) -> Unit,
        onSmbDialogOpenChange: (Boolean) -> Unit,
        onPhotoReload: () -> Unit,
    ) {
    val smbUri = encodeSmb(entry.path)
    val item = MediaItem(
        id = "smb_" + System.currentTimeMillis(),
        title = entry.name,
        uri = smbUri,
        isVideo = true
    )
    onSmbDialogOpenChange(false)
    onPhotoReload()

    // v2.1.241：SMB 上的 WMV / ASF 是**当前架构下唯一无解的组合**，提前告知而不是让它黑屏。
    //
    // 为什么无解：
    //  · WMV/ASF 必须走 IJK（EXO 不解析 ASF 容器）；
    //  · 但 IJK 的 FFmpeg **没有 smb 协议**（见 IjkPlayerBackend 的类注释第 3 条），
    //    它的数据源也拿不到本项目 jcifs 的 SmbDataSource → 只会回退 EXO；
    //  · 回退到 EXO 后依然不认 ASF → 最终失败。
    // 所以这里直接在**点开时**给提示，用户就不用等它转一圈再报错。
    // 本地文件 / HTTP 上的 WMV 不受此限（走得到 IJK）。
    val ext = MediaFormats.extensionOfName(entry.name)
    if (MediaFormats.requiresIjk(ext)) {
        Toast.makeText(
            context,
            context.getString(R.string.toast_smb_ffmpeg_only, ext.uppercase()),
            Toast.LENGTH_LONG
        ).show()
    }
}

fun startRemuxFix(
        context: Context,
        scope: CoroutineScope,
        selectedMediaItem: MediaItem,
        isRemuxing: Boolean,
        onRemuxingChange: (Boolean) -> Unit,
        onSelectMedia: (MediaItem) -> Unit,
        onSeekUnsupportedChange: (Boolean) -> Unit,
        onPhotoReload: () -> Unit,
    ) {
    val uriStr = selectedMediaItem.uri ?: return
    if (isRemuxing) return
    // v2.4.25：当前已是本机修复产物 → 不再重复修复。
    // 否则排队的 seek 校验协程会把「已修复文件」再修一次（输入=输出同路径），
    // 造成同一文件反复重封装、标题反复追加"（已修复）"、反复重载 → 卡顿。
    if (isContainerFixOutput(uriStr)) return
    onRemuxingChange(true)
    onSeekUnsupportedChange(false)
    Toast.makeText(
        context,
        context.getString(R.string.toast_fixing_container),
        Toast.LENGTH_LONG
    ).show()

    scope.launch(Dispatchers.IO) {
        // v2.4.26：容器修复改用 **FFmpeg(libavformat) 转封装**（输出 MP4），
        //   不再用 MediaExtractor + MediaMuxer。实测根因：源片含 B 帧（fMP4 的 trun
        //   带非零 sample_composition_time_offset → PTS 在解码顺序下非单调），而
        //   MediaMuxer 要求时间戳单调递增，旧实现「时间戳倒退就跳过样本」把 6302 帧
        //   砍到 464 帧（≈2fps）→ 修复后播放极卡。
        //   FFmpeg 的 av_interleaved_write_frame 会缓冲重排、写出 ctts，**不丢帧**；
        //   stbl 采样表保证 ExoPlayer 可 seek。MP4 收不下时（如裸 PCM 音频）退回 MKV。
        val base = "remuxed_${selectedMediaItem.id}"
        val mp4Out = File(context.cacheDir, "$base.mp4")
        var out = mp4Out
        var result = try {
            FfmpegRemuxer.remuxToMp4(context, Uri.parse(uriStr), mp4Out)
        } catch (t: Throwable) {
            FfmpegRemuxer.Result(false, null, -200, t.message)
        }
        if (!result.success) {
            val mkvOut = File(context.cacheDir, "$base.mkv")
            val r2 = try {
                FfmpegRemuxer.remuxToMkv(context, Uri.parse(uriStr), mkvOut)
            } catch (t: Throwable) {
                FfmpegRemuxer.Result(false, null, -200, t.message)
            }
            if (r2.success) {
                result = r2
                out = mkvOut
            }
        }
        // v2.4.28：产物必须先**验能播**（含视频轨）再切换 —— 防止「修复后反而播不了」。
        // ⚠️ 这一步放在 IO 线程（MediaExtractor 打开文件是 IO）。
        val valid = result.success && out.length() > 1024 && hasVideoTrack(out)
        withContext(Dispatchers.Main) {
            onRemuxingChange(false)
            if (valid) {
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_container_fixed),
                    Toast.LENGTH_LONG
                ).show()
                onSelectMedia(selectedMediaItem.copy(
                    uri = Uri.fromFile(out).toString(),
                    title = selectedMediaItem.title + context.getString(R.string.suffix_fixed)
                ))
                onPhotoReload()
            } else {
                onSeekUnsupportedChange(true)
                Log.w(
                    "VRPlayerScreen",
                    "容器修复未采用：success=${result.success} size=${out.length()} " +
                        "code=${result.code} ${result.message}"
                )
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_seek_unsupported),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

// Helper inside Compose to rebuild/re-bind Android ExoPlayer to GLES
// D. When downscale output is enabled, cap the surface texture buffer to
// 1920px wide (aspect preserved) to lower bandwidth/OOM during 8K hard decode. (8/3 功能)
fun effectiveOutputSize(w: Int, h: Int, downscaleOutputEnabled: Boolean): Pair<Int, Int> {
    if (!downscaleOutputEnabled || w <= 0 || h <= 0) return w to h
    val maxWidth = 1920
    if (w <= maxWidth) return w to h
    val nh = (h.toLong() * maxWidth / w).toInt().coerceAtLeast(1)
    return maxWidth to nh
}

/**
 * Rewrites the SPS (level_idc and/or width/height) so the hardware
 * decoder accepts the stream. No re-encoding. (8/2-8/3 功能)
 */
fun startLevelPatchFix(
        context: Context,
        scope: CoroutineScope,
        selectedMediaItem: MediaItem,
        isRemuxing: Boolean,
        level51Enabled: Boolean,
        spoofResolutionEnabled: Boolean,
        levelPatchEnabled: Boolean,
        onRemuxingChange: (Boolean) -> Unit,
        onSelectMedia: (MediaItem) -> Unit,
        onPhotoReload: () -> Unit,
        ) {
    val uriStr = selectedMediaItem.uri ?: return
    if (isRemuxing) return
    // v2.4.25：已是本机修复产物 → 不再重复改写 SPS（防 8K 场景反复重编码）。
    if (isContainerFixOutput(uriStr)) return
    onRemuxingChange(true)
    Toast.makeText(
        context,
        context.getString(R.string.toast_patching_header),
        Toast.LENGTH_LONG
    ).show()

    scope.launch(Dispatchers.IO) {
        val out = File(context.cacheDir, "levelpatched_${selectedMediaItem.id}.mp4")
        val levelIdc = if (level51Enabled) 0x33 else 0x3D // 5.1 or 6.1
        val result = if (spoofResolutionEnabled) {
            VideoRemuxer.remuxWithSpsSpoof(
                context,
                Uri.parse(uriStr),
                out,
                targetWidth = 3840,
                targetHeight = 2160,
                levelIdc = if (levelPatchEnabled) levelIdc else null
            )
        } else {
            VideoRemuxer.remuxWithLevelPatch(
                context,
                Uri.parse(uriStr),
                out,
                levelIdc = levelIdc
            )
        }
        withContext(Dispatchers.Main) {
            onRemuxingChange(false)
            if (result.success && out.length() > 1024) {
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_hw_patch_ok),
                    Toast.LENGTH_LONG
                ).show()
                onSelectMedia(selectedMediaItem.copy(
                    uri = Uri.fromFile(out).toString(),
                    title = selectedMediaItem.title + context.getString(R.string.suffix_hw_patched)
                ))
                onPhotoReload()
            } else {
                Toast.makeText(
                    context,
                    "修改编码头失败：${result.message ?: "未知错误"}，可尝试手动降级转码",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
}
