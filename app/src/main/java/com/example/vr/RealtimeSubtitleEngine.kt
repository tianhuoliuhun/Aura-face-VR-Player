package com.example.vr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

import com.example.R
import android.media.MediaDataSource
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile

/**
 * v126：实时 AI 字幕引擎（方案文档「AI 字幕集成播放器」P0+P1）。
 *
 * ## 与原有「整片转写」的区别
 * 原流程是「整片跑完再写 `_asr.srt`」（v127 已移除该链路）；文档明确指出这条路
 * 「每次都要等全片跑完，且拖动进度条要重新解析文件」。本引擎改为**边播边生成**：
 * 后台按游标前方 10–15 秒滚动预读，识别结果直接进内存缓存，播放头取用。
 *
 * ## 三个关键设计（对应文档要求）
 * 1. **独立音频解码（AudioTee）**：另开一套 MediaExtractor + MediaCodec 解码同一文件，
 *    与播放器解耦，互不干扰，且可以超前解码。不走 AudioSink 旁路（文档评价：
 *    与播放耦合，拖动时易出错）。
 * 2. **时间戳来自音频 PTS，不是 VAD 累计时长**。独立解码流的起始 PTS 通常不为 0
 *    （尤其 seek 后从关键帧开始解），因此每次窗口都用**首个输出样本的 PTS** 做基准，
 *    再叠加段内偏移，避免整片字幕错位。
 * 3. **稀疏缓存 + 每帧 O(log n) 查询**：见 [SubtitleCache]。
 *
 * ## 线程模型（文档 8.2）
 * - 预读线程：本类的 `scope`（`Dispatchers.Default`）负责解码→VAD→ASR，是 CPU 大户；
 * - 渲染/主线程：只做 `cache.find()` 查询，极轻量；
 * - ASR 既不跑主线程也不跑播放线程。
 *
 * ## 内存
 * 文档实测纯 ASR 约 260–290MB（SenseVoice int8 约 234MB 占大头），无需 largeHeap。
 * 模型与 native 资源在 [stop] 时释放。
 */
class RealtimeSubtitleEngine(private val context: Context) {

    companion object {
        private const val TAG = "RealtimeSubtitle"

        private const val SAMPLE_RATE = 16000

        // VAD（能量法）：与 v122 离线引擎保持一致的判据
        private const val VAD_RMS_THRESHOLD = 0.008f
        private const val SPLIT_SILENCE_MS = 700L
        private const val MIN_SEGMENT_MS = 300L
        private const val MAX_SEGMENT_MS = 25_000L

        /**
         * 单次解码窗口（v2.0.135：20s → 60s）。
         * decodeWindow 每次都要 seekTo 最近关键帧重解，关键帧间隔常见 5–10s，
         * 窗口越小平摊的冗余解码越多（20s 窗口浪费可达 1/3）。60s 窗口把
         * seek/flush/VAD-reset 固定开销摊薄 3 倍，实测显著提升生成吞吐；
         * 且当前点附近（priority 1，cursor-5s~cursor+lookahead）仍按实际缺口
         * 长度处理，不受窗口变大影响。
         */
        private const val DECODE_WINDOW_MS = 60_000L

        /** 预读窗口（文档推荐 10–15 秒）：太短会被偶发卡顿打断，太长拖动后做无用功 */
        private const val LOOKAHEAD_DEFAULT_MS = 12_000L
        private const val LOOKAHEAD_NARROW_MS = 5_000L   // 热节流时收窄
        private const val LOOKAHEAD_RELAXED_MS = 20_000L // 充电中放宽

        /**
         * v127e：播放点**前方**的回补余量。
         * 用户从任意位置开始播、或向前小跳时，前 5 秒往往没有字幕（那个位置之前
         * 还没生成过），所以每次游标移动都把 [cursor-5s, cursor] 也纳入高优先区间。
         */
        private const val BACKFILL_BEFORE_MS = 5_000L

        // 长句切分（对齐项目 SRT 14 字/行口径：2 行约 28 字，这里留紧一些到 20）
        // v127b：原 40 字 / 8 秒太宽松，实际字幕常占满两行且停留过久，用户体验偏"太长"。
        private const val MAX_CUE_CHARS = 20
        private const val MAX_CUE_MS = 5000L
        private const val MIN_SPLIT_CHARS = 8
        private const val MIN_PIECE_MS = 800L

        /** v127：Silero VAD 模型（内置 assets，629KB，MIT 许可） */
        private const val VAD_MODEL_ASSET = "silero_vad.onnx"
        private const val VAD_MODEL_NAME = "silero_vad.onnx"
    }

    /** 回调到 UI（主线程语义由调用方保证，这里只做数据投递） */
    interface Listener {
        /** 缓存有更新，[cues] 是完整有序快照，可直接喂给字幕渲染 */
        fun onCuesUpdated(cues: List<SubtitleCue>)
        /** 状态提示（加载中 / 无音轨 / 未检测到语音 / 已暂停预读…） */
        fun onStatus(message: String)
    }

    /** 段级识别器抽象：屏蔽 sherpa-onnx（SenseVoice/Qwen3）与 Vosk 的接口差异 */
    interface SegmentRecognizer {
        /** 识别一段 16kHz 单声道 PCM，返回文本（可能为空） */
        fun recognize(samples: FloatArray): String
        fun release()
    }

    /** 由 UI 层实现：负责准备模型并构造识别器（模型管理不属于本引擎职责） */
    fun interface RecognizerFactory {
        /** 在 IO 线程调用；返回 null 表示模型不可用 */
        suspend fun create(): SegmentRecognizer?
    }

    val cache = SubtitleCache()

    @Volatile var isRunning = false
        private set

    /** 播放头位置（毫秒），由 UI 在播放位置推进时更新 */
    @Volatile private var cursorMs = 0L

    /** 已生成到的位置（毫秒）——预读调度据此决定下一步解哪里 */
    @Volatile private var generatedUpToMs = 0L

    /** 是否正在拖动进度条（拖动期间暂停预读，避免做无用功） */
    @Volatile private var isScrubbing = false

    @Volatile private var lookaheadMs = LOOKAHEAD_DEFAULT_MS

    /** 是否有音轨（无音轨时直接禁用，文档第十章） */
    @Volatile var hasAudioTrack = true
        private set

    /** 是否识别到过任何语音（用于"纯音乐"提示，避免静默失败） */
    @Volatile private var producedAnyCue = false

    /** v127b：媒体总时长（毫秒，0=未知），供 UI 计算实时字幕生成进度 */
    @Volatile var durationMs: Long = 0L
        private set

    /** v127b：实时字幕已生成到的位置（毫秒），供 UI 显示进度 */
    @Volatile var generatedMs: Long = 0L
        private set

    /** v127b：全片是否已生成完（到达媒体末尾） */
    @Volatile var isFullyGenerated = false
        private set

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var listener: Listener? = null

    /**
     * v2.0.136：引擎代际号。每次 [start]/[stop] 递增；协程启动时捕获自己的代际，
     * 循环每轮与窗口处理完成后校验，代际不等（说明已被新一代取代）立即退出且
     * **不再写任何共享状态**。解决实测问题：stop() 是协作式取消，旧协程卡在
     * 4~6.5s 的 60s 长窗口里退不出来，而 start() 已启动新协程——两个预读循环
     * 并发读写同一份 scannedRanges，导致同一窗口重复识别 2~3 遍、进度倒跳、
     * 调度状态损坏（logcat 15:37:54 实证）。
     */
    @Volatile private var generation = 0L

    /** v2.0.136：scannedRanges 的访问锁（多代协程可能短暂并发，ArrayList 非线程安全） */
    private val scanLock = Any()

    /**
     * v127：native 资源（识别器 / VAD / 解码器）**只在启动协程内部持有与释放**。
     *
     * 曾经把它们放在字段里、由 [stop] 直接 release，结果实测 native abort：
     *     Abort message: 'FORTIFY: pthread_mutex_lock called on a destroyed mutex'
     * 原因是 stop() 在主线程释放时，预读线程（DefaultDispatcher）可能正在识别——
     * 对象被销毁后仍被使用。播放中切换媒体/引擎/开关都会走 stop → start，很容易撞上。
     *
     * 因此这里不做任何跨线程释放：stop() 只负责取消，释放交给持有资源的协程在
     * finally 中执行（取消是协作式的，会等当前 native 调用返回后才进入 finally）。
     */

    /** 启动实时字幕。[mediaUri] 支持 content://（相册）与 file:// */
    fun start(mediaUri: Uri, factory: RecognizerFactory, listener: Listener) {
        if (isRunning) stop()
        generation++                      // v2.0.136：让尚未退出的旧代协程尽快自灭
        val myGen = generation
        this.listener = listener
        currentUri = mediaUri
        currentFactory = factory
        cache.clear()
        clearScanned()
        producedAnyCue = false
        generatedUpToMs = 0L
        cursorMs = 0L
        hasAudioTrack = true
        durationMs = 0L
        generatedMs = 0L
        isFullyGenerated = false

        val s = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope = s
        isRunning = true
        listener.onStatus(context.getString(R.string.rt_preparing))

        job = s.launch {
            // native 资源全部在本协程内创建与释放（见上方字段处的说明）：
            // 若改由 stop() 跨线程释放，会与正在执行的识别撞车，触发
            // "pthread_mutex_lock called on a destroyed mutex" 的 native abort。
            var rec: SegmentRecognizer? = null
            var vad: com.k2fsa.sherpa.onnx.Vad? = null
            var teeLocal: AudioTee? = null
            try {
                // 1) 准备识别模型（可能触发下载/加载，耗时较长）
                rec = try {
                    factory.create()
                } catch (e: Exception) {
                    Log.e(TAG, "recognizer create failed", e)
                    null
                }
                if (rec == null) {
                    listener.onStatus(context.getString(R.string.rt_unavailable))
                    isRunning = false
                    return@launch
                }

                // 2) 准备 VAD（失败只降级，不阻断）
                vad = tryCreateSileroVad()

                // 3) 打开独立音频解码器（AudioTee）
                val t = AudioTee(context, mediaUri)
                if (!t.open()) {
                    hasAudioTrack = false
                    listener.onStatus(context.getString(R.string.rt_no_audio))
                    isRunning = false
                    return@launch
                }
            teeLocal = t
            durationMs = t.durationMs
            listener.onStatus(context.getString(R.string.rt_started))

                // 4) 预读主循环
                prefetchLoop(t, rec, vad, myGen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "prefetch loop ended: ${e.message}")
            } finally {
                // 取消后仍要释放 native 资源，因此用 NonCancellable 包裹；
                // 此时预读已停止（取消是协作式的，会等当前 native 调用返回），不会并发。
                withContext(NonCancellable) {
                    try { teeLocal?.close() } catch (_: Exception) {}
                    try { vad?.release() } catch (_: Exception) {}
                    try { rec?.release() } catch (_: Exception) {}
                }
                isRunning = false
            }
        }
    }

    /**
     * v127e：重新生成字幕（字幕悬浮窗的「重新生成」按钮）。
     *
     * 清空缓存与已扫描记录后按当前播放点重新走一遍优先级调度：
     * 先补当前点前 5 秒与之后的部分，再补齐其余，因此点一下很快就能看到
     * 当前位置的字幕，而不用等全片跑完。
     */
    fun restart() {
        val uri = currentUri ?: return
        val factory = currentFactory ?: return
        val l = listener ?: return
        Log.i(TAG, "重新生成字幕：从 ${cursorMs}ms 开始")
        start(uri, factory, l)
    }

    /** 播放位置推进（UI 每 200ms 左右调用一次即可） */
    fun updateCursor(positionMs: Long) {
        cursorMs = positionMs
    }

    /** 拖动进度条开始/结束：拖动期间暂停预读 */
    fun setScrubbing(scrubbing: Boolean) {
        isScrubbing = scrubbing
    }

    /**
     * Seek 处理（v2.0.135 重写）：
     * **只更新播放头，不清任何缓存/扫描记录。**
     *
     * v2.0.134 及之前会 invalidateAfter(target+lookahead) 清掉前沿之后的字幕，
     * 实测（MuMu logcat）生成速度仅 ≈1x 实时：清掉后播放头 12 秒内必然追上
     * 生成前沿，此后一直处于"未生成区间"——这就是"拖动/切场景后字幕放一会儿
     * 就消失"的真正根因。而 seek 后前方已生成的字幕依然正确（音频没变），
     * 清掉再按 1x 速度补回纯属浪费。故：往回拖，旧字幕仍在（瞬时命中）；
     * 往前拖，前沿字幕直接可用。真正需要全量清空的只有换媒体（start 已做）。
     */
    fun onSeek(targetMs: Long) {
        cursorMs = targetMs
        Log.i(TAG, "seek → ${targetMs}ms, 缓存保留 ${cache.size()} 条，已覆盖 ${scannedMs()}ms")
    }

    fun stop() {
        isRunning = false
        generation++   // v2.0.136：旧代协程在当前窗口处理完后立即自灭，不再写共享状态
        // 只取消，不在这里释放 native 资源：释放由持有它们的协程在 finally 中完成，
        // 否则会与正在执行的识别并发，触发 native abort（见字段处说明）。
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        listener = null
    }

    /** 当前应显示的字幕（渲染路径每帧调用，O(log n)） */
    fun currentCue(positionMs: Long): SubtitleCue? = cache.find(positionMs)

    // ==================== 预读循环 ====================

    /**
     * v127e：预读调度 —— **自动全局生成，按优先级推进**。
     *
     * 优先级（用户要求 + 体验考量）：
     *  1. 当前播放点附近：[cursor-5s, cursor+lookahead]。
     *     前 5 秒是刻意的「回补」——从任意位置起播、或向前小跳时，那个位置此前
     *     没生成过字幕，不补的话跳过去就是一片空白。
     *  2. 当前点之后：从 [cursor+lookahead] 顺序推进到片尾。
     *  3. 其他：回头把片头到 [cursor-5s] 的缺口补齐。
     * 三段都没有缺口时说明全片已覆盖 → 置 [isFullyGenerated] 并停止空转。
     *
     * 已扫描区间用 [scannedRanges] 记录（合并相邻），因此 seek 后不会重复解码
     * 已生成的段落，只补真正的缺口。
     */
    private suspend fun prefetchLoop(
        t: AudioTee,
        r: SegmentRecognizer,
        vad: com.k2fsa.sherpa.onnx.Vad?,
        myGen: Long
    ) {
        while (currentCoroutineContext().isActive) {
            // v2.0.136：已被新一代引擎取代 → 立即退出，不再读写任何共享状态
            if (myGen != generation) return
            if (isScrubbing) {
                delay(300)
                continue
            }

            val total = durationMs
            // 时长未知时给一个足够大的推进上限（解码到流末尾会自然结束）
            val endMs = if (total > 0L) total else cursorMs + DECODE_WINDOW_MS * 30

            val gap = nextUnscannedRange(endMs)
            if (gap == null) {
                // 全片已覆盖：只报一次完成，然后低频轮询（用户 seek 后可能出现新缺口）
                if (!isFullyGenerated) {
                    isFullyGenerated = true
                    listener?.onStatus(context.getString(R.string.rt_all_done, cache.size()))
                }
                delay(500)
                continue
            }

            val t0 = System.currentTimeMillis()
            val winFrom = gap.first
            val winTo = gap.last + 1   // 半开区间
            // v2.0.134：单窗口解码/识别异常不应中断整条生成链路——
            // 否则最后一个窗口抛错会让进度卡在 99% 且引擎停摆。
            // 标记已扫描后继续，保证进度能走到 100% 并完成。
            try {
                processWindow(t, r, vad, winFrom, winTo)
            } catch (e: Exception) {
                Log.w(TAG, "窗口 ${winFrom}~${winTo}ms 处理失败（已跳过）: ${e.message}")
            }
            // v2.0.136：窗口处理（可达数秒）期间若引擎已被重启，本代已过期——
            // 直接退出，不能把本代窗口标记为已扫描，否则会污染新一代的调度。
            if (myGen != generation) return
            markScanned(winFrom, winTo)
            Log.i(
                TAG,
                "窗口 ${winFrom}~${winTo}ms 完成，耗时 ${System.currentTimeMillis() - t0}ms，" +
                    "缓存 ${cache.size()} 条，已覆盖 ${scannedMs()}/${if (total > 0) total else -1}ms"
            )

            generatedMs = scannedMs()
            if (total > 0L && generatedMs < total) isFullyGenerated = false

            // 状态文案
            if (total > 0L) {
                val pct = (generatedMs * 100 / total).coerceIn(0L, 100L)
                // 播了 20 秒还没有任何语音：给明确反馈而不是静默失败（文档第十章）
                listener?.onStatus(
                    if (!producedAnyCue && scannedMs() > 20_000L && cache.size() == 0)
                        context.getString(R.string.rt_no_speech)
                    else context.getString(R.string.rt_generating_pct, pct, fmt(generatedMs), fmt(total))
                )
            } else {
                listener?.onStatus(context.getString(R.string.rt_generating_to, fmt(generatedMs)))
            }
        }
    }

    // ==================== 已扫描区间管理 ====================

    /**
     * 选出下一个待处理区间（长度约一个解码窗口），按上述优先级。
     * 返回 null 表示三段都没有缺口。
     */
    private fun nextUnscannedRange(endMs: Long): LongRange? = synchronized(scanLock) {
        val cur = cursorMs
        val nearStart = (cur - BACKFILL_BEFORE_MS).coerceAtLeast(0L)
        val nearEnd = (cur + lookaheadMs).coerceAtMost(endMs)

        // 1) 当前点附近（含前方 5 秒回补）
        firstGapIn(nearStart, nearEnd)?.let { return@synchronized it }
        // 2) 当前点之后
        firstGapIn(nearEnd, endMs)?.let { return@synchronized it }
        // 3) 其他：片头 → 当前点前
        firstGapIn(0L, nearStart)?.let { return@synchronized it }
        return@synchronized null
    }

    /** 在 [fromMs, toMs) 内找第一个未被扫描的子区间（长度 ≤ 一个解码窗口） */
    private fun firstGapIn(fromMs: Long, toMs: Long): LongRange? = synchronized(scanLock) {
        if (fromMs >= toMs) return@synchronized null
        var pos = fromMs
        var guard = 0
        while (pos < toMs && guard++ < 10_000) {
            // v2.0.135 修 1ms 滑移：scannedRanges 存的是 [first, last]（last=末毫秒，闭区间），
            // 旧代码匹配条件用 pos < it.last 且跳转用 pos = hit.last，导致每个已扫描区间的
            // 最后一毫秒永远被当成新 gap 返回——每个窗口都重复解码上窗末尾 1ms（logcat 实证：
            // 窗口首尾 16122~18081 / 18080~19906 重叠）。正确：命中判定含 last，跳到 last+1。
            val hit = scannedRanges.firstOrNull { pos >= it.first && pos <= it.last }
            if (hit == null) {
                val len = minOf(DECODE_WINDOW_MS, toMs - pos)
                if (len <= 0L) return@synchronized null
                return@synchronized (pos until (pos + len))
            }
            pos = hit.last + 1
        }
        return@synchronized null
    }

    /** 标记区间已扫描（合并相邻/重叠区间，保持按起点有序） */
    private fun markScanned(fromMs: Long, toMs: Long) = synchronized(scanLock) {
        if (toMs <= fromMs) return@synchronized
        val merged = ArrayList<LongRange>(scannedRanges.size + 1)
        var newStart = fromMs
        var newEnd = toMs
        var inserted = false
        for (r in scannedRanges) {
            when {
                r.last < newStart -> merged.add(r)                    // 完全在前
                r.first > newEnd -> {                                 // 完全在后
                    if (!inserted) { merged.add(newStart until newEnd); inserted = true }
                    merged.add(r)
                }
                else -> {                                             // 有重叠 → 合并
                    newStart = minOf(newStart, r.first)
                    newEnd = maxOf(newEnd, r.last)
                }
            }
        }
        if (!inserted) merged.add(newStart until newEnd)
        scannedRanges.clear()
        scannedRanges.addAll(merged)
    }

    /** 已覆盖总时长（毫秒） */
    private fun scannedMs(): Long = synchronized(scanLock) {
        var sum = 0L
        for (r in scannedRanges) sum += (r.last - r.first)
        sum
    }

    /** 清空扫描记录（重新生成时用） */
    private fun clearScanned() = synchronized(scanLock) {
        scannedRanges.clear()
    }

    /** v127e：已扫描（解码+识别过）的音频区间，合并相邻；仅预读线程访问 */
    private val scannedRanges = ArrayList<LongRange>()

    /** 启动参数留存，供 [restart] 重新生成时复用 */
    private var currentUri: Uri? = null
    private var currentFactory: RecognizerFactory? = null

    private fun fmt(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0L)
        return "%02d:%02d".format(s / 60, s % 60)
    }

    /**
     * 处理一个解码窗口：解码 → VAD 切分 → 逐段识别 → 写入缓存。
     *
     * 时间戳一律以窗口内**首个音频样本的 PTS** 为基准（文档第四章），
     * 而不是用 VAD 的累计时长——否则 seek 到关键帧后起始 PTS 不为 0 会导致整体错位。
     */
    private suspend fun processWindow(
        t: AudioTee,
        r: SegmentRecognizer,
        vad: com.k2fsa.sherpa.onnx.Vad?,
        fromMs: Long,
        toMs: Long
    ) {
        val decoded = withContext(Dispatchers.Default) {
            try {
                t.decodeWindow(fromMs, toMs)
            } catch (e: Exception) {
                Log.w(TAG, "decode window failed: ${e.message}")
                null
            }
        } ?: return

        val samples = decoded.samples
        if (samples.isEmpty()) return

        // samples 已被重采样为 16k 单声道；ptsBaseMs 是首样本的绝对时间
        val cues = withContext(Dispatchers.Default) {
            recognizeSegments(samples, decoded.ptsBaseMs, r, vad)
        }
        if (cues.isEmpty()) return

        producedAnyCue = true
        cache.putAll(cues)
        listener?.onCuesUpdated(cache.snapshot())
    }

    // ==================== VAD 分段 + 识别 ====================

    private fun recognizeSegments(
        samples: FloatArray,
        ptsBaseMs: Long,
        r: SegmentRecognizer,
        vad: com.k2fsa.sherpa.onnx.Vad?
    ): List<SubtitleCue> {
        // v127：优先用 Silero VAD（方案文档指定，629KB 模型已内置 assets）。
        // 它比能量阈值更抗音乐/噪声干扰，且能自动处理静音切分。
        // VAD 实例由引擎创建并复用（native 对象，不宜每窗口重建）；
        // 模型缺失或初始化失败时回退能量 VAD，保证功能不因单个组件失效而整体不可用。
        return if (vad != null) {
            try {
                vad.reset()   // 每个解码窗口是独立片段（seek 后时基不同），必须清空状态
                recognizeSegmentsWithSilero(samples, ptsBaseMs, r, vad)
            } catch (e: Throwable) {
                Log.w(TAG, "Silero VAD 分段失败（${e.message}），本窗口回退能量 VAD")
                recognizeSegmentsByEnergy(samples, ptsBaseMs, r)
            }
        } else {
            recognizeSegmentsByEnergy(samples, ptsBaseMs, r)
        }
    }

    /** 构造 Silero VAD（模型内置在 assets，首次使用时复制到 filesDir 供 native 读取） */
    private fun tryCreateSileroVad(): com.k2fsa.sherpa.onnx.Vad? = try {
        val modelFile = File(context.filesDir, VAD_MODEL_NAME)
        if (!modelFile.exists() || modelFile.length() < 100_000L) {
            context.assets.open(VAD_MODEL_ASSET).use { input ->
                modelFile.outputStream().use { out -> input.copyTo(out) }
            }
        }
        val cfg = com.k2fsa.sherpa.onnx.VadModelConfig(
            sileroVadModelConfig = com.k2fsa.sherpa.onnx.SileroVadModelConfig(
                model = modelFile.absolutePath,
                threshold = 0.5f,
                // v127b：静音阈值 0.7→0.5 秒，断句更勤，避免把多句并成一条长字幕
                minSilenceDuration = 0.5f,
                minSpeechDuration = 0.25f,
                windowSize = 512,
                // v127b：单段上限 15→8 秒。原值会让一条字幕覆盖十几秒，
                // 表现为"字幕太长"；8 秒更接近正常语速下一句话的长度。
                maxSpeechDuration = 8.0f,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        )
        // v127 修复闪退：这里必须传 null 而不是 context.assets。
        // sherpa-onnx 在「给了绝对路径 + assetManager 非空」时会判定冲突并
        // `F` 级致命日志（Load xxx failed）直接终止进程——实测就是这个原因闪退。
        // 绝对路径走文件系统读取，AssetManager 必须为空。
        com.k2fsa.sherpa.onnx.Vad(null, cfg)
            .also { Log.i(TAG, "Silero VAD 就绪（$VAD_MODEL_ASSET）") }
    } catch (e: Throwable) {
        Log.w(TAG, "Silero VAD 不可用（${e.message}），回退能量 VAD")
        null
    }

    /** 用 Silero VAD 切分并逐段识别 */
    private fun recognizeSegmentsWithSilero(
        samples: FloatArray,
        ptsBaseMs: Long,
        r: SegmentRecognizer,
        vad: com.k2fsa.sherpa.onnx.Vad
    ): List<SubtitleCue> {
        val out = ArrayList<SubtitleCue>()
        // 按 512 样本窗口喂入（Silero 要求固定窗口）
        var i = 0
        while (i + 512 <= samples.size) {
            val win = FloatArray(512)
            System.arraycopy(samples, i, win, 0, 512)
            vad.acceptWaveform(win)
            i += 512
            // 取走已完成（尾部静音已达阈值）的语音段
            while (!vad.empty()) {
                val seg = vad.front()
                val text = try {
                    r.recognize(seg.samples)
                } catch (e: Exception) {
                    Log.w(TAG, "segment recognize failed: ${e.message}")
                    ""
                }
                if (text.isNotBlank()) {
                    // seg.start 是该段首样本在本次输入中的索引 → 换算成绝对时间
                    val startMs = ptsBaseMs + seg.start * 1000L / SAMPLE_RATE
                    val endMs = startMs + seg.samples.size * 1000L / SAMPLE_RATE
                    for (p in splitLongSentence(text, startMs.coerceAtLeast(0L), endMs)) {
                        out.add(SubtitleCue(out.size + 1, p.startMs, p.endMs, p.text))
                    }
                }
                vad.pop()
            }
        }
        // 收尾：把尾部未闭合的语音段也处理掉
        try {
            vad.flush()
            while (!vad.empty()) {
                val seg = vad.front()
                val text = try { r.recognize(seg.samples) } catch (_: Exception) { "" }
                if (text.isNotBlank()) {
                    val startMs = ptsBaseMs + seg.start * 1000L / SAMPLE_RATE
                    val endMs = startMs + seg.samples.size * 1000L / SAMPLE_RATE
                    for (p in splitLongSentence(text, startMs.coerceAtLeast(0L), endMs)) {
                        out.add(SubtitleCue(out.size + 1, p.startMs, p.endMs, p.text))
                    }
                }
                vad.pop()
            }
        } catch (_: Exception) {}
        return out
    }

    /** 回退方案：能量阈值 VAD（与 v122 整片转写同口径） */
    private fun recognizeSegmentsByEnergy(
        samples: FloatArray,
        ptsBaseMs: Long,
        r: SegmentRecognizer
    ): List<SubtitleCue> {
        val out = ArrayList<SubtitleCue>()
        val pending = ArrayList<Float>(SAMPLE_RATE * 30)
        var segStartMs = ptsBaseMs
        var segEndMs = ptsBaseMs
        var silenceRunMs = 0L
        val chunkLen = SAMPLE_RATE / 10   // 100ms

        fun flush(endMs: Long) {
            if (pending.isEmpty()) return
            val seg = pending.toFloatArray()
            pending.clear()
            val segMs = seg.size * 1000L / SAMPLE_RATE
            if (segMs < MIN_SEGMENT_MS) return
            val text = try {
                r.recognize(seg)
            } catch (e: Exception) {
                Log.w(TAG, "segment recognize failed: ${e.message}")
                ""
            }
            if (text.isNotBlank()) {
                for (p in splitLongSentence(text, segStartMs, endMs)) {
                    out.add(SubtitleCue(out.size + 1, p.startMs, p.endMs, p.text))
                }
            }
        }

        var i = 0
        while (i + chunkLen <= samples.size) {
            var rms = 0f
            for (k in 0 until chunkLen) {
                val v = samples[i + k]
                rms += v * v
            }
            rms = kotlin.math.sqrt(rms / chunkLen)
            val chunkMs = chunkLen * 1000L / SAMPLE_RATE
            val chunkStart = ptsBaseMs + i * 1000L / SAMPLE_RATE
            i += chunkLen

            if (rms >= VAD_RMS_THRESHOLD) {
                if (pending.isEmpty()) segStartMs = chunkStart
                for (k in 0 until chunkLen) pending.add(samples[i - chunkLen + k])
                silenceRunMs = 0L
                segEndMs = chunkStart + chunkMs
                if (pending.size >= SAMPLE_RATE * MAX_SEGMENT_MS / 1000) {
                    flush(segEndMs)
                }
            } else if (pending.isNotEmpty()) {
                silenceRunMs += chunkMs
                if (silenceRunMs >= SPLIT_SILENCE_MS) {
                    flush(chunkStart)
                    silenceRunMs = 0L
                } else {
                    // 短静音留在段内，避免从词中间切断
                    for (k in 0 until chunkLen) pending.add(samples[i - chunkLen + k])
                    segEndMs = chunkStart + chunkMs
                }
            }
        }
        flush(segEndMs)
        return out
    }

    // ==================== 长句切分（与整片转写同口径）====================

    private data class TextPiece(val text: String, val startMs: Long, val endMs: Long)

    private fun splitLongSentence(text: String, startMs: Long, endMs: Long): List<TextPiece> {
        val total = endMs - startMs
        if (text.length <= MAX_CUE_CHARS && total <= MAX_CUE_MS) {
            return listOf(TextPiece(text, startMs, endMs))
        }
        val parts = mutableListOf<String>()
        var buf = StringBuilder()
        for (ch in text) {
            buf.append(ch)
            if (ch in "。！？；!?;" || (ch in "，,、" && buf.length >= MIN_SPLIT_CHARS)) {
                parts.add(buf.toString()); buf = StringBuilder()
            }
        }
        if (buf.isNotBlank()) parts.add(buf.toString())
        var usable = parts.filter { it.isNotBlank() }
        if (usable.isEmpty()) return listOf(TextPiece(text, startMs, endMs))

        // 无标点长句：按字数强制等分
        if (usable.size == 1 && usable[0].length > MAX_CUE_CHARS) {
            val whole = usable[0]
            val chunks = mutableListOf<String>()
            var i = 0
            while (i < whole.length) {
                chunks.add(whole.substring(i, minOf(i + MAX_CUE_CHARS, whole.length)))
                i += MAX_CUE_CHARS
            }
            usable = chunks
        }

        val totalChars = usable.sumOf { it.length }.coerceAtLeast(1)
        val out = mutableListOf<TextPiece>()
        var cursor = startMs
        usable.forEachIndexed { idx, part ->
            val isLast = idx == usable.lastIndex
            val share = if (isLast) endMs - cursor
            else (total * part.length / totalChars.toFloat()).toLong()
            val segEnd = if (isLast) endMs
            else (cursor + share).coerceAtLeast(cursor + MIN_PIECE_MS).coerceAtMost(endMs)
            out.add(TextPiece(part.trim(), cursor, segEnd))
            cursor = segEnd
        }
        return out
    }

    // ==================== 预读窗口自适应（文档第六章）====================

    /** 根据热节流与充电状态调整预读窗口 */
    fun refreshLookahead() {
        lookaheadMs = try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            // 充电状态：用 ACTION_BATTERY_CHANGED 粘性广播读取，避免 API 差异
            val charging = try {
                val it = context.registerReceiver(
                    null,
                    android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                )
                val st = it?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                st == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    st == android.os.BatteryManager.BATTERY_STATUS_FULL
            } catch (_: Exception) {
                false
            }
            val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                pm?.currentThermalStatus ?: 0
            } else 0
            when {
                thermal >= PowerManager.THERMAL_STATUS_SEVERE -> LOOKAHEAD_NARROW_MS
                thermal >= PowerManager.THERMAL_STATUS_MODERATE -> 8_000L
                charging -> LOOKAHEAD_RELAXED_MS
                else -> LOOKAHEAD_DEFAULT_MS
            }
        } catch (_: Exception) {
            LOOKAHEAD_DEFAULT_MS
        }
    }

    // ==================== AudioTee：独立音频解码 ====================

    private class DecodedAudio(val samples: FloatArray, val ptsBaseMs: Long)

    /**
     * 独立解码同文件的音频轨（文档 8.1）。
     *
     * 与整片转写（v127 已移除）的差别：那个是「整片一次性解码」，本类需要
     * **按范围反复解码**（预读窗口滚动 + seek），因此保持 extractor/codec 常驻，
     * 复用 codec 实例（创建开销约 50–100ms，不能每段重建）。
     */
    /**
     * v2.0.142：把 smb:// 暴露成 framework 的 [MediaDataSource]，让 [MediaExtractor]
     * 能真随机访问（per-window seek）。底层用 jcifs [SmbRandomAccessFile]，与
     * [SmbDataSource] 的 seek 实现一致（真随机访问，非 skip 顺序读）。
     */
    private class SmbMediaDataSource(private val smbUri: String) : MediaDataSource() {
        private val raf = SmbRandomAccessFile(SmbFile(smbUri), "r")

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= raf.length()) return -1
            raf.seek(position)
            val n = raf.read(buffer, offset, size)
            return if (n < 0) -1 else n
        }

        override fun getSize(): Long = raf.length()

        override fun close() {
            try {
                raf.close()
            } catch (_: Exception) {
            }
        }
    }

    private class AudioTee(private val context: Context, private val uri: Uri) {

        private var extractor: MediaExtractor? = null
        private var codec: MediaCodec? = null
        private var outRate = SAMPLE_RATE
        private var outChannels = 1

        /** v127b：媒体时长（毫秒，0 表示未知），用于 UI 显示实时字幕生成进度 */
        var durationMs: Long = 0L
            private set

        /**
         * 打开音频轨。返回 false 表示没有可用音轨。
         * 用文件描述符打开：`setDataSource(context, uri, null)` 对相册（photo picker）
         * 的临时 URI 会抛 "Failed to instantiate extractor"（v123 实测）。
         */
        fun open(): Boolean {
            val scheme = uri.scheme?.lowercase()
            if (scheme == "smb") {
                // v2.0.142：smb://（应用内 SMB 浏览器）走 jcifs 真随机访问，
                // openFileDescriptor 对 smb:// 必然抛异常（与 http 同源的「无音轨」误报）。
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(SmbMediaDataSource(uri.toString()))
                    extractor = ex
                    return openCodec(ex)
                } catch (e: Exception) {
                    Log.w(TAG, "AudioTee smb 直连失败（${e.message}），回退整文件下载")
                    try { ex.release() } catch (_: Exception) {}
                    val tmp = downloadSmbToTemp(uri) ?: return false
                    val ex2 = MediaExtractor()
                    try {
                        ex2.setDataSource(tmp.absolutePath)
                    } catch (e2: Exception) {
                        Log.w(TAG, "AudioTee smb 临时文件打开失败：${e2.message}")
                        try { ex2.release() } catch (_: Exception) {}
                        return false
                    }
                    extractor = ex2
                    return openCodec(ex2)
                }
            }
            if (scheme == "http" || scheme == "https") {
                // v2.0.140：http(s)（如 MT 管理器的本地回环代理 http://127.0.0.1:port/...）
                // 走框架自带 HTTP 栈（支持 Range seek）；openFileDescriptor 对 http URI
                // 必然抛异常（v2.0.139 实测误报“该视频没有可用的音轨”）。
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(context, uri, null)
                    extractor = ex
                    return openCodec(ex)
                } catch (e: Exception) {
                    Log.w(TAG, "AudioTee http 直连失败（${e.message}），回退整文件临时下载")
                    try { ex.release() } catch (_: Exception) {}
                    val tmp = downloadToTemp(uri) ?: return false
                    val ex2 = MediaExtractor()
                    try {
                        ex2.setDataSource(tmp.absolutePath)
                    } catch (e2: Exception) {
                        Log.w(TAG, "AudioTee 临时文件打开失败：${e2.message}")
                        try { ex2.release() } catch (_: Exception) {}
                        return false
                    }
                    extractor = ex2
                    return openCodec(ex2)
                }
            }
            val ex = MediaExtractor()
            try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    try {
                        ex.setDataSource(pfd.fileDescriptor)
                    } catch (e: Exception) {
                        // fd 不可 seek 时回退到临时文件（与 v123 同策略）
                        Log.w(TAG, "AudioTee fd 方式失败（${e.message}），回退临时文件")
                        pfd.close()
                        val tmp = copyToTemp(uri) ?: return false
                        val ex2 = MediaExtractor()
                        ex2.setDataSource(tmp.absolutePath)
                        extractor = ex2
                        return openCodec(ex2)
                    }
                    pfd.close()
                } else {
                    return false
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioTee open failed: ${e.message}")
                return false
            }
            extractor = ex
            return openCodec(ex)
        }

        private fun openCodec(ex: MediaExtractor): Boolean {
            var audioTrack = -1
            var mime = ""
            var durationUs = 0L
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) {
                    audioTrack = i; mime = m
                    durationUs = if (f.containsKey(MediaFormat.KEY_DURATION)) {
                        f.getLong(MediaFormat.KEY_DURATION)
                    } else 0L
                    break
                }
            }
            if (audioTrack < 0) return false
            durationMs = durationUs / 1000L
            ex.selectTrack(audioTrack)
            return try {
                val c = MediaCodec.createDecoderByType(mime)
                c.configure(ex.getTrackFormat(audioTrack), null, null, 0)
                c.start()
                codec = c
                true
            } catch (e: Exception) {
                Log.w(TAG, "AudioTee codec 创建失败：${e.message}（暂不支持该音频格式）")
                false
            }
        }

        private fun copyToTemp(uri: Uri): File? = try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val tmp = File(context.cacheDir, "rt_asr_src.tmp")
            pfd.use { p ->
                java.io.FileInputStream(p.fileDescriptor).use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
                }
            }
            tmp
        } catch (e: Exception) {
            null
        }

        /** v2.0.140：http(s) 直连失败时的兜底——经回环代理整文件下载到缓存再打开 */
        private fun downloadToTemp(uri: Uri): File? = try {
            val tmp = File(context.cacheDir, "rt_asr_src.tmp")
            val conn = (java.net.URL(uri.toString()).openConnection()
                    as java.net.HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
            tmp
        } catch (e: Exception) {
            Log.w(TAG, "AudioTee http 临时下载失败：${e.message}")
            null
        }

        /** v2.0.142：smb:// 直连失败时的兜底——经 jcifs 整文件下载到缓存再打开
         * （copyToTemp/downloadToTemp 都依赖 contentResolver.openFileDescriptor，对 smb:// 无效） */
        private fun downloadSmbToTemp(uri: Uri): File? = try {
            val smb = SmbFile(uri.toString())
            val tmp = File(context.cacheDir, "rt_asr_src_smb.tmp")
            smb.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
            tmp
        } catch (e: Exception) {
            Log.w(TAG, "AudioTee smb 临时下载失败：${e.message}")
            null
        }

        /** 解码 [fromMs, toMs) 区间，返回 16k 单声道样本与首样本 PTS（毫秒） */
        fun decodeWindow(fromMs: Long, toMs: Long): DecodedAudio? {
            val ex = extractor ?: return null
            val c = codec ?: return null

            // seek 到最近关键帧（可能早于 fromMs，因此必须用首样本 PTS 校正）
            ex.seekTo(fromMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            try { c.flush() } catch (_: Exception) {}

            val info = MediaCodec.BufferInfo()
            val acc = ArrayList<Float>(SAMPLE_RATE * 32)
            var ptsBaseUs = -1L
            var inputDone = false
            var outputDone = false
            val toUs = toMs * 1000L
            var mono = FloatArray(0)

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(5_000)
                    if (inIdx >= 0) {
                        val inBuf = c.getInputBuffer(inIdx)
                        val sz = if (inBuf != null) ex.readSampleData(inBuf, 0) else -1
                        if (sz < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, 5_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = c.outputFormat
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) outRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) outChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    continue
                }
                if (outIdx < 0) {
                    if (inputDone && outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // 输入已结束仍拿不到输出：避免死循环
                        if (info.size == 0 && info.presentationTimeUs == 0L) {
                            outputDone = true
                        }
                    }
                    continue
                }

                val buf = c.getOutputBuffer(outIdx)
                if (buf != null && info.size > 0) {
                    if (ptsBaseUs < 0) ptsBaseUs = info.presentationTimeUs
                    val bytes = ByteArray(info.size)
                    buf.get(bytes)
                    val ch = outChannels.coerceAtLeast(1)
                    val frames = bytes.size / (2 * ch)
                    val part = FloatArray(frames)
                    for (f in 0 until frames) {
                        var sum = 0
                        for (k in 0 until ch) {
                            val off = (f * ch + k) * 2
                            sum += ((bytes[off].toInt() and 0xff) or (bytes[off + 1].toInt() shl 8)).toShort().toInt()
                        }
                        part[f] = sum / ch.toFloat() / 32768f
                    }
                    mono = appendFloat(mono, part)
                }
                val ptsUs = info.presentationTimeUs
                c.releaseOutputBuffer(outIdx, false)

                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                if (ptsUs >= toUs) outputDone = true

                // 边解边降采样重采样，避免长窗口累积过多内存
                if (mono.size >= outRate) {
                    val resampled = resampleLinear(mono, outRate, SAMPLE_RATE)
                    for (v in resampled) acc.add(v)
                    mono = FloatArray(0)
                }
            }
            if (mono.isNotEmpty()) {
                for (v in resampleLinear(mono, outRate, SAMPLE_RATE)) acc.add(v)
            }
            if (acc.isEmpty()) return null

            val baseMs = if (ptsBaseUs >= 0) ptsBaseUs / 1000L else fromMs
            val samples = FloatArray(acc.size)
            for (i in acc.indices) samples[i] = acc[i]
            return DecodedAudio(samples, baseMs)
        }

        fun close() {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
            codec = null
            extractor = null
        }

        private fun appendFloat(a: FloatArray, b: FloatArray): FloatArray {
            if (a.isEmpty()) return b
            val out = FloatArray(a.size + b.size)
            System.arraycopy(a, 0, out, 0, a.size)
            System.arraycopy(b, 0, out, a.size, b.size)
            return out
        }

        private fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
            if (srcRate == dstRate || input.isEmpty()) return input
            val ratio = srcRate.toDouble() / dstRate.toDouble()
            val outSize = (input.size / ratio).toInt().coerceAtLeast(0)
            val out = FloatArray(outSize)
            for (i in 0 until outSize) {
                val pos = i * ratio
                val i0 = pos.toInt().coerceAtMost(input.size - 1)
                val frac = (pos - i0).toFloat()
                out[i] = input[i0] + (input[(i0 + 1).coerceAtMost(input.size - 1)] - input[i0]) * frac
            }
            return out
        }
    }
}

/**
 * sherpa-onnx（SenseVoice / Qwen3）的段级识别器适配。
 * 这两个都是离线模型，整段送入即可（与 v122 的整段识别口径一致）。
 */
class SherpaSegmentRecognizer(
    private val recognizer: com.k2fsa.sherpa.onnx.OfflineRecognizer
) : RealtimeSubtitleEngine.SegmentRecognizer {

    override fun recognize(samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    override fun release() {
        try { recognizer.release() } catch (_: Exception) {}
    }
}
