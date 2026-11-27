package com.example.vr

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.example.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.abs

@Stable
internal class DanmuOrchestratorState(
    val visionClient: DanmuVisionClient,
    val engine: DanmuEngine,
    val importStore: DanmuImportStore,
) {
    var generatedCount by mutableIntStateOf(0)
    var lastError by mutableStateOf("")
    var importStatus by mutableStateOf<DanmuImportStore.LoadResult?>(null)
    var importCount by mutableIntStateOf(0)
    var importName by mutableStateOf("")
    var lastPosMs by mutableLongStateOf(0L)
    internal var launchImportPicker: () -> Unit = {}
}

/**
 * AI 弹幕与导入弹幕编排器（从 VRPlayerScreen.kt 拆出）。
 */
@Composable
internal fun rememberDanmuOrchestratorEffects(
    context: Context,
    prefs: SharedPreferences,
    isMemoryModeEnabled: Boolean,
    selectedMediaItem: MediaItem,
    danmuConfig: DanmuConfig,
    onDanmuConfigChange: (DanmuConfig) -> Unit,
    subtitleTranslator: SubtitleTranslator,
    getCurrentPositionMs: () -> Long,
    getIsVideoPlaying: () -> Boolean,
    getCurrentGlSurfaceView: () -> VRGLSurfaceView?,
    getIsRealtimeAsrActive: () -> Boolean,
    getRealtimeCues: () -> List<SubtitleCue>,
    getLoadedSubtitleCues: () -> List<SubtitleCue>,
    onKeepUiAlight: () -> Unit,
): DanmuOrchestratorState {
    val state = remember {
        DanmuImporter.enableAndroidLog()
        DanmuImportStore.enableAndroidLog()
        DanmuOrchestratorState(
            visionClient = DanmuVisionClient(),
            engine = DanmuEngine(),
            importStore = DanmuImportStore(),
        )
    }

    val danmuImportPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onKeepUiAlight()
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val name = state.importStore.displayNameOf(context.contentResolver, uri)
            onDanmuConfigChange(danmuConfig.copy(importedUri = uri.toString(), importedName = name))
            if (isMemoryModeEnabled) {
                prefs.edit().putString("danmu_import_for_video", selectedMediaItem.uri.orEmpty()).apply()
            }
        }
    }
    state.launchImportPicker = { danmuImportPickerLauncher.launch(arrayOf("*/*")) }

    LaunchedEffect(selectedMediaItem.uri, danmuConfig.importedUri, danmuConfig.isEnabled) {
        if (!danmuConfig.isEnabled || danmuConfig.importedUri.isBlank()) return@LaunchedEffect
        val bound = prefs.getString("danmu_import_for_video", "").orEmpty()
        val cur = selectedMediaItem.uri.orEmpty()
        if (bound.isNotBlank() && cur.isNotBlank() && bound != cur) {
            state.lastError = context.getString(R.string.danmu_import_bound_other_video)
        }
    }

    LaunchedEffect(
        danmuConfig.speedPxPerSec,
        danmuConfig.maxTracks,
        danmuConfig.autoTracks,
        danmuConfig.dedupThresholdPercent
    ) {
        state.engine.speedPxPerSec = danmuConfig.speedPxPerSec
        state.engine.maxTracks = danmuConfig.maxTracks
        state.engine.autoTracks = danmuConfig.autoTracks
        state.engine.dedupThresholdPercent = danmuConfig.dedupThresholdPercent
    }

    LaunchedEffect(danmuConfig.importedUri) {
        val u = danmuConfig.importedUri
        if (u.isBlank()) {
            state.importStore.clear()
            state.importStatus = null
            state.importCount = 0
            state.importName = ""
            return@LaunchedEffect
        }
        if (state.importStore.uri == u && state.importStore.hasContent) {
            state.importCount = state.importStore.items.size
            state.importName = state.importStore.fileName
            return@LaunchedEffect
        }
        state.importStatus = null
        val uri = Uri.parse(u)
        val name = danmuConfig.importedName.ifBlank { uri.lastPathSegment.orEmpty() }
        val result = state.importStore.load(context.contentResolver, uri, name)
        state.importStatus = result
        state.importCount = state.importStore.items.size
        state.importName = state.importStore.fileName
        state.lastError = when (result) {
            is DanmuImportStore.LoadResult.Failure -> when (result.error) {
                DanmuImportStore.Error.UNREADABLE -> context.getString(R.string.danmu_import_fail_unreadable)
                DanmuImportStore.Error.BAD_FORMAT -> context.getString(R.string.danmu_import_fail_format)
                DanmuImportStore.Error.EMPTY -> context.getString(R.string.danmu_import_fail_empty)
            }
            is DanmuImportStore.LoadResult.Success -> ""
        }
    }

    LaunchedEffect(danmuConfig.isEnabled) {
        if (!danmuConfig.isEnabled) {
            state.engine.reset()
            state.generatedCount = 0
            state.lastError = ""
        }
    }

    LaunchedEffect(danmuConfig.isEnabled, danmuConfig.intervalSec, danmuConfig.sourceTypeId) {
        if (!danmuConfig.isEnabled) return@LaunchedEffect
        if (danmuConfig.isImportMode) return@LaunchedEffect
        if (!danmuConfig.isReadyToRequest()) {
            state.lastError = context.getString(R.string.danmu_err_not_configured)
            return@LaunchedEffect
        }
        val urlErr = when (danmuConfig.baseUrlProblem()) {
            DanmuConfig.BaseUrlProblem.EMPTY -> context.getString(R.string.danmu_err_url_empty)
            DanmuConfig.BaseUrlProblem.MISSING_SCHEME -> context.getString(R.string.danmu_err_url_no_scheme)
            DanmuConfig.BaseUrlProblem.BAD_SCHEME -> context.getString(R.string.danmu_err_url_bad_scheme)
            DanmuConfig.BaseUrlProblem.MISSING_HOST -> context.getString(R.string.danmu_err_url_no_host)
            null -> ""
        }
        if (urlErr.isNotEmpty()) {
            state.lastError = urlErr
            return@LaunchedEffect
        }
        val periodMs = danmuConfig.intervalSec.coerceIn(
            DanmuConfig.MIN_INTERVAL_SEC, DanmuConfig.MAX_INTERVAL_SEC
        ) * 1000L
        var danmuFailStreak = 0
        delay(FIRST_CAPTURE_DELAY_MS)
        while (true) {
            if (!getIsVideoPlaying()) {
                delay(periodMs)
                continue
            }
            try {
                var frame: Bitmap? = null
                val capturePosMs = getCurrentPositionMs()
                val needFrame = danmuConfig.needsImage ||
                    (danmuConfig.useLocalLlm && LocalLlmManager.visionAvailable)
                if (needFrame) {
                    val renderer = getCurrentGlSurfaceView()?.renderer
                    if (renderer == null) {
                        state.lastError = context.getString(R.string.danmu_err_no_renderer)
                        delay(periodMs)
                        continue
                    }
                    val requested = renderer.requestDanmuFrame(danmuConfig.imageMaxLongSide)
                    if (!requested) {
                        delay(periodMs)
                        continue
                    }
                    var tries = 0
                    while (tries < FRAME_POLL_MAX_TRIES && frame == null) {
                        delay(FRAME_POLL_INTERVAL_MS)
                        val taken = renderer.takeDanmuFrame()
                        if (taken != null) {
                            val (px, w, h) = taken
                            frame = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
                        }
                        tries++
                    }
                    if (frame == null) {
                        state.lastError = context.getString(R.string.danmu_err_capture_timeout)
                        delay(periodMs)
                        continue
                    }
                }

                val subtitleText = if (danmuConfig.needsSubtitle) {
                    val cues = if (getIsRealtimeAsrActive()) getRealtimeCues() else getLoadedSubtitleCues()
                    DanmuSubtitleSnippet.extract(
                        cues = cues,
                        positionMs = getCurrentPositionMs(),
                        maxChars = DanmuConfig.SUBTITLE_TEXT_MAX_CHARS * 2,
                        translationOf = { src -> subtitleTranslator.peekTranslation(src) }
                    )
                } else {
                    ""
                }
                if (danmuConfig.needsSubtitle && subtitleText.isBlank() && !needFrame) {
                    state.lastError = context.getString(R.string.danmu_err_no_subtitle)
                    delay(periodMs)
                    continue
                }

                val fetched = if (danmuConfig.useLocalLlm) {
                    val lines = LocalDanmuGenerator.generate(
                        context = context,
                        subtitleText = subtitleText,
                        personaPrompt = danmuConfig.personaPrompt,
                        batchSize = danmuConfig.batchSize,
                        frame = frame
                    )
                    DanmuVisionClient.DanmuFetchResult(
                        lines = lines,
                        kind = if (lines.isEmpty()) {
                            DanmuVisionClient.ChatErrorKind.NO_CONTENT
                        } else {
                            DanmuVisionClient.ChatErrorKind.NONE
                        },
                        detail = if (lines.isEmpty()) "local-llm" else ""
                    )
                } else {
                    state.visionClient.fetchDanmu(frame, danmuConfig, subtitleText)
                }
                if (frame != null && !frame.isRecycled) frame.recycle()

                if (!fetched.isSuccess) {
                    danmuFailStreak++
                    val retryInMs = DanmuRetryPolicy.nextDelayMs(
                        periodMs, danmuFailStreak, fetched.kind
                    )
                    state.lastError = danmuRetryHint(
                        context, danmuErrorText(context, fetched.kind), retryInMs, danmuFailStreak
                    )
                    delay(retryInMs)
                    continue
                }

                danmuFailStreak = 0
                state.lastError = ""
                val now = SystemClock.elapsedRealtime()
                val driftMs = abs(getCurrentPositionMs() - capturePosMs)
                if (driftMs > DANMU_LATE_DROP_MS) {
                    Log.i(
                        "VRPlayerScreen",
                        "弹幕批丢弃：响应与截图位置相差 ${driftMs}ms（>${DANMU_LATE_DROP_MS}ms）"
                    )
                } else {
                    val spawnBaseMs = now
                    val added = state.engine.enqueue(fetched.lines, spawnBaseMs)
                    state.generatedCount += added
                    state.engine.prune(now)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("VRPlayerScreen", "弹幕编排循环异常: ${e.message}", e)
                state.lastError = context.getString(R.string.danmu_err_unexpected)
            }
            delay(periodMs)
        }
    }

    LaunchedEffect(danmuConfig.isEnabled, danmuConfig.sourceTypeId, danmuConfig.importedUri) {
        if (!danmuConfig.isEnabled) return@LaunchedEffect
        if (!danmuConfig.isImportMode) return@LaunchedEffect
        while (true) {
            if (state.importStore.hasContent) break
            if (danmuConfig.importedUri.isBlank()) {
                state.lastError = context.getString(R.string.danmu_err_import_no_file)
                return@LaunchedEffect
            }
            delay(DANMU_IMPORT_TICK_MS)
        }
        state.importStore.resetCursorTo(getCurrentPositionMs())
        state.lastError = ""
        val pending = mutableListOf<Triple<String, Long, Color?>>()
        while (true) {
            if (getIsVideoPlaying()) {
                val pos = getCurrentPositionMs()
                val due = state.importStore.drainUntil(pos)
                if (due.isNotEmpty() || pending.isNotEmpty()) {
                    val now = SystemClock.elapsedRealtime()
                    val fresh = due.map { d ->
                        val lateMs = (pos - d.timeMs).coerceAtLeast(0L)
                        Triple(d.text, now - lateMs, d.color)
                    }
                    pending.addAll(fresh)
                    val batch = pending.toList()
                    pending.clear()
                    val outcome = state.engine.enqueueTimed(batch, dedupe = false)
                    state.generatedCount += outcome.placed
                    val deadline = now - DANMU_IMPORT_RETRY_WINDOW_MS
                    pending.addAll(outcome.unplaced.filter { it.second >= deadline })
                    state.engine.prune(now)
                }
            }
            delay(DANMU_IMPORT_TICK_MS)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            state.engine.reset()
        }
    }

    LaunchedEffect(danmuConfig.isEnabled) {
        if (!danmuConfig.isEnabled) return@LaunchedEffect
        while (true) {
            val pos = getCurrentPositionMs()
            val jumped = abs(pos - state.lastPosMs) > 2_000L
            state.lastPosMs = pos
            if (jumped) {
                state.engine.clear()
                if (danmuConfig.isImportMode) {
                    state.importStore.resetCursorTo(pos)
                }
            }
            delay(DANMU_SEEK_POLL_MS)
        }
    }

    return state
}
