package com.example.vr

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import com.example.R
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 字幕导出/重生成/历史加载 + 音视频轨选择 + 视频信息收集（从 VRPlayerScreen.kt 拆出）。
 */
internal object VRPlayerTrackAndAsrOps {

    fun exportSubtitleSrt(
        context: Context,
        isRealtimeAsrActive: Boolean,
        realtimeCues: List<SubtitleCue>,
        loadedSubtitleCues: List<SubtitleCue>,
        subtitleTranslator: SubtitleTranslator,
        selectedMediaItem: MediaItem,
        isStripSubtitlePunctuation: Boolean,
    ) {
        val cues = if (isRealtimeAsrActive) realtimeCues else loadedSubtitleCues
        if (cues.isEmpty()) {
            Toast.makeText(context, context.getString(R.string.toast_no_subtitle_export), Toast.LENGTH_SHORT).show()
            return
        }
        val untranslated =
            if (subtitleTranslator.config.isEnabled) {
                cues.count { subtitleTranslator.cachedTranslationOf(it.text) == null }
            } else 0
        val f = SubtitleExporter.exportSrt(
            context,
            selectedMediaItem.title,
            cues,
            SubtitleExporter.langSuffix(subtitleTranslator),
            isStripSubtitlePunctuation,
            { subtitleTranslator.exportTextFor(it) }
        )
        Toast.makeText(
            context,
            when {
                f == null -> context.getString(R.string.toast_subtitle_export_failed)
                untranslated > 0 -> context.getString(
                    R.string.toast_subtitle_exported_untranslated,
                    cues.size,
                    untranslated,
                    f.absolutePath
                )
                else -> context.getString(R.string.toast_subtitle_exported, cues.size, f.absolutePath)
            },
            Toast.LENGTH_LONG
        ).show()
    }

    fun regenerateRealtimeSubtitle(
        context: Context,
        isRealtimeAsrActive: Boolean,
        selectedMediaItem: MediaItem,
        realtimeSubtitleEngine: RealtimeSubtitleEngine,
        onResetRealtime: () -> Unit,
    ) {
        if (!isRealtimeAsrActive) {
            Toast.makeText(context, context.getString(R.string.toast_enable_realtime_first), Toast.LENGTH_SHORT).show()
            return
        }
        if (!selectedMediaItem.isVideo) {
            Toast.makeText(context, context.getString(R.string.toast_image_no_audio), Toast.LENGTH_SHORT).show()
            return
        }
        onResetRealtime()
        realtimeSubtitleEngine.restart()
        Toast.makeText(context, context.getString(R.string.toast_subtitle_restarted), Toast.LENGTH_SHORT).show()
    }

    fun loadSavedSubtitleFile(
        context: Context,
        prefs: SharedPreferences,
        scope: CoroutineScope,
        f: File,
        isMemoryModeEnabled: Boolean,
        onLoaded: (List<SubtitleCue>, String) -> Unit,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val content = SubtitleParser.decodeBytes(f.readBytes())
                val cues = SubtitleParser.parse(content)
                withContext(Dispatchers.Main) {
                    if (cues.isEmpty()) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_subtitle_empty_format, f.name),
                            Toast.LENGTH_LONG
                        ).show()
                        return@withContext
                    }
                    onLoaded(cues, f.name)
                    if (isMemoryModeEnabled) {
                        prefs.edit().putBoolean("realtime_subtitle_enabled", false).apply()
                    }
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_subtitle_autoloaded, f.name, cues.size),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.w("VRPlayerScreen", "load saved subtitle failed: ${e.message}")
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun selectMediaTrack(
        playerInstance: VrPlayerBackend?,
        groupType: Int,
        trackIndex: Int,
        onVideoTrackSelected: (Int) -> Unit,
        onAudioTrackSelected: (Int) -> Unit,
        onTextTrackSelected: (Int) -> Unit,
    ) {
        playerInstance?.exo?.let { p ->
            try {
                val groups = p.currentTracks?.groups ?: return@let
                val group = groups.firstOrNull { it.type == groupType } ?: return@let
                val params = p.trackSelectionParameters.buildUpon()
                if (trackIndex >= 0 && trackIndex < group.mediaTrackGroup.length) {
                    params.setTrackTypeDisabled(groupType, false)
                    params.addOverride(
                        TrackSelectionOverride(
                            group.mediaTrackGroup,
                            ImmutableList.of(trackIndex)
                        )
                    )
                } else if (groupType == C.TRACK_TYPE_VIDEO) {
                    params.clearOverridesOfType(groupType)
                    params.setTrackTypeDisabled(groupType, false)
                } else {
                    params.clearOverridesOfType(groupType)
                    params.setTrackTypeDisabled(groupType, true)
                }
                p.trackSelectionParameters = params.build()
                when (groupType) {
                    C.TRACK_TYPE_VIDEO -> onVideoTrackSelected(trackIndex)
                    C.TRACK_TYPE_AUDIO -> onAudioTrackSelected(trackIndex)
                    C.TRACK_TYPE_TEXT -> onTextTrackSelected(trackIndex)
                }
            } catch (e: Exception) {
                Log.e("VRPlayerScreen", "selectMediaTrack failed", e)
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun showVideoInfo(
        context: Context,
        scope: CoroutineScope,
        selectedMediaItem: MediaItem,
        displayName: String,
        playerInstance: VrPlayerBackend?,
        isSoftwareDecoding: Boolean,
        onInfoReady: (String) -> Unit,
    ) {
        val uriStr = selectedMediaItem.uri ?: return
        val title = selectedMediaItem.title
        val sb = StringBuilder()
        sb.append(context.getString(R.string.info_file, displayName))
        if (title != null && title != displayName) {
            sb.append(context.getString(R.string.info_title, title))
        }
        var gotAny = false
        playerInstance?.exo?.let { p ->
            try {
                val dur = p.duration
                if (dur > 0) {
                    gotAny = true
                    sb.append(context.getString(R.string.info_duration, dur / 1000 / 60, (dur / 1000) % 60))
                }
                val groups = p.currentTracks?.groups
                if (groups != null && groups.isNotEmpty()) {
                    gotAny = true
                    for (g in groups) {
                        val f = g.mediaTrackGroup.getFormat(0)
                        val mime = f.sampleMimeType ?: context.getString(R.string.unknown)
                        if (mime.startsWith("video/")) {
                            if (f.width > 0 && f.height > 0) {
                                sb.append(context.getString(R.string.info_resolution, f.width, f.height))
                            }
                            sb.append(context.getString(R.string.info_video_codec, mime))
                            if (f.frameRate > 0f) sb.append(context.getString(R.string.info_fps, f.frameRate))
                            if (f.bitrate > 0) sb.append(context.getString(R.string.info_bitrate, f.bitrate / 1000))
                        } else if (mime.startsWith("audio/")) {
                            sb.append("音轨: $mime ${f.language ?: ""}\n")
                        } else {
                            sb.append("轨道: $mime ${f.language ?: ""}\n")
                        }
                    }
                }
                sb.append("解码: ${if (isSoftwareDecoding) "软件" else context.getString(R.string.info_hw)}\n")
            } catch (e: Exception) {
                Log.e("VRPlayerScreen", "video info player read failed", e)
            }
        }

        scope.launch(Dispatchers.IO) {
            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                val configured = try {
                    if (uriStr.startsWith("content://") || uriStr.startsWith("file://")) {
                        retriever.setDataSource(context, Uri.parse(uriStr))
                    } else {
                        retriever.setDataSource(uriStr, java.util.HashMap<String, String>())
                    }
                    true
                } catch (_: Exception) {
                    try {
                        val pfd = context.contentResolver.openFileDescriptor(Uri.parse(uriStr), "r")
                        pfd?.use { retriever.setDataSource(it.fileDescriptor) }
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                if (configured) {
                    gotAny = true
                    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    if (w != null && h != null && !sb.contains(context.getString(R.string.label_resolution))) {
                        sb.append(context.getString(R.string.info_resolution2, w, h))
                    }
                    val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    if (rotation != null && rotation != "0") sb.append(context.getString(R.string.info_rotation, rotation))
                    val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    if (fps != null && fps != "-1" && !sb.contains(context.getString(R.string.label_fps))) sb.append(context.getString(R.string.info_fps2, fps))
                    val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    if (bitrate != null && !sb.contains(context.getString(R.string.label_bitrate))) sb.append(context.getString(R.string.info_bitrate2, bitrate.toLong() / 1000))
                    val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    if (mime != null && !sb.contains(context.getString(R.string.label_codec))) sb.append(context.getString(R.string.info_codec, mime))
                }
            } catch (e: Exception) {
                Log.e("VRPlayerScreen", "video info retriever failed", e)
            } finally {
                try { retriever?.release() } catch (_: Exception) {}
            }

            val info = if (gotAny) sb.toString() else context.getString(R.string.info_unavailable)
            withContext(Dispatchers.Main) {
                onInfoReady(info)
            }
        }
    }
}
