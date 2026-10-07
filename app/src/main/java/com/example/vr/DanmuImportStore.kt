package com.example.vr

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 导入弹幕的**内容缓存**（v2.4.7）
 *
 * ## 为什么要有这个对象
 * 弹幕文件可达数 MB（几万条），两个约束：
 * 1. **不能存进 prefs** —— prefs 是全量加载的 XML，塞几 MB 进去会让每次读写都变慢；
 *    因此 prefs 里只存 URI 字符串（见 `DanmuConfig.importedUri`）。
 * 2. **不能每次入队都读文件** —— 播放过程中每几秒就要投递一批，
 *    每次都去 `contentResolver.openInputStream` 会持续产生 IO 抖动。
 *
 * 于是：**启动/切换文件时读一次**，把解析结果留在内存的 [items]，
 * 播放期间只做「按时间游标取用」这种纯内存操作。
 *
 * ## 为什么不是单例
 * 本对象由 `remember` 在 Composable 作用域持有 —— 与 `DanmuEngine` 同生命周期。
 * 单例会跨页面残留上一部片子的弹幕，且难以在退出时释放。
 */
class DanmuImportStore {

    /**
     * 已加载的导入弹幕（**按时间升序**，由 [DanmuImporter] 保证）。
     *
     * 用 `@Volatile` 是因为写入发生在 IO 协程、读取发生在编排协程 —— 让可见性有保证。
     */
    @Volatile
    var items: List<DanmuImporter.ImportedDanmu> = emptyList()
        private set

    /** 当前文件显示名（用于 UI） */
    @Volatile
    var fileName: String = ""
        private set

    /** 当前文件 URI（用于 UI 与去重判断） */
    @Volatile
    var uri: String = ""
        private set

    /** 是否已有可用内容 */
    val hasContent: Boolean get() = items.isNotEmpty()

    /**
     * 调度游标（**下标**，指向下一条待投递的弹幕）。
     *
     * ⚠️ 用下标而非时间做游标，是为了 O(1) 推进；配合 [resetCursorTo] 处理 seek。
     */
    @Volatile
    private var cursor: Int = 0

    /**
     * 从 URI 读取并解析弹幕文件。
     *
     * ⚠️ 必须在 IO 线程调用（内部已 `withContext(Dispatchers.IO)`）。
     *
     * @return 解析结果；失败时返回 [LoadResult.Failure]（**不抛异常**）
     */
    suspend fun load(
        resolver: ContentResolver,
        fileUri: Uri,
        displayName: String
    ): LoadResult = withContext(Dispatchers.IO) {
        try {
            val text = resolver.openInputStream(fileUri)?.use { input ->
                // 弹幕文件基本都是 UTF-8。用 `String(bytes, UTF_8)` —— 它对非法字节序列
                // **不抛异常**，而是替换成 U+FFFD，因此单个脏字节不会让整份文件读不出来
                // （真实弹幕文件里偶有编码不干净的字节，这一点很实用）。
                val bytes = input.readBytes()
                String(bytes, Charsets.UTF_8)
            }
            if (text.isNullOrEmpty()) {
                return@withContext LoadResult.Failure(Error.UNREADABLE)
            }
            val parsed = DanmuImporter.parse(text)
            if (parsed.format == DanmuImporter.Format.UNKNOWN) {
                return@withContext LoadResult.Failure(Error.BAD_FORMAT)
            }
            if (parsed.items.isEmpty()) {
                return@withContext LoadResult.Failure(Error.EMPTY)
            }
            items = parsed.items
            fileName = displayName
            uri = fileUri.toString()
            cursor = 0
            logger("导入弹幕：${parsed.items.size} 条（跳过 ${parsed.skipped}）来自 $displayName")
            LoadResult.Success(count = parsed.items.size, skipped = parsed.skipped)
        } catch (e: Exception) {
            logger("导入弹幕失败: ${e.message}")
            LoadResult.Failure(Error.UNREADABLE)
        }
    }

    /** 清空（用户点「清除导入」或关闭功能时） */
    fun clear() {
        items = emptyList()
        fileName = ""
        uri = ""
        cursor = 0
    }

    /**
     * 取出**从上一游标到 [positionMs] 为止**所有应当投递的弹幕。
     *
     * ## 为什么按 `<= positionMs` 而不是「一个窗口」
     * 导入的弹幕自带精确时间戳，语义是「播放到这一刻就出现这一条」。
     * 因此只要播放位置越过了它的时间点，就该投递 —— 用游标保证**每条只投一次**。
     *
     * ## ⚠️ 为什么单次有限量 [MAX_PER_TICK]
     * 若播放位置一次跳跃很大（如 seek 到很后面、或某帧卡顿很久），
     * 一次性返回几千条会把引擎瞬间打满、也会造成明显的卡顿尖峰。
     * 这里限制单次上限；**超出的部分不丢**（游标只推进到实际取走的条数），
     * 下一次调用会继续取 —— 表现为"快速补放"而不是"丢掉中间的弹幕"。
     *
     * @param positionMs 当前播放位置（ms）
     * @return 本批要投递的弹幕（按时间升序）；没有则返回空列表
     */
    fun drainUntil(positionMs: Long): List<DanmuImporter.ImportedDanmu> {
        val list = items
        if (list.isEmpty()) return emptyList()
        var i = cursor
        if (i >= list.size) return emptyList()
        val out = ArrayList<DanmuImporter.ImportedDanmu>()
        while (i < list.size && list[i].timeMs <= positionMs && out.size < MAX_PER_TICK) {
            out.add(list[i])
            i++
        }
        cursor = i
        return out
    }

    /**
     * 把游标重置到**第一条时间 >= [positionMs] 的位置**。
     *
     * ## 为什么 seek 必须调它
     * 用户把进度条拖到 30 分钟处，若不重置游标，`drainUntil` 会认为
     * 「0~30 分钟的所有弹幕都该补投」→ 瞬间几千条涌出（既有性能问题，也完全不是用户想要的）。
     * 重置后只投「当前位置之后」的弹幕，与真实播放器行为一致。
     *
     * **向后 seek（回退）同样要重置** —— 否则回退后所有弹幕都被判为「已投过」，再也不出现。
     */
    fun resetCursorTo(positionMs: Long) {
        val list = items
        if (list.isEmpty()) { cursor = 0; return }
        // 二分找第一个 timeMs >= positionMs 的下标
        var lo = 0
        var hi = list.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (list[mid].timeMs < positionMs) lo = mid + 1 else hi = mid
        }
        cursor = lo
    }

    /** 已投递条数（UI 状态卡展示进度用） */
    fun deliveredCount(): Int = cursor

    /**
     * 从 URI 解析出「给用户看的文件名」。
     *
     * 直接 `uri.lastPathSegment` 对 SAF 的 `content://` 往往拿到的是一串数字 id
     * （如 `content://com.android.providers.downloads.documents/document/1234` → `1234`），
     * 用户完全认不出选的是哪个文件。这里优先查 `OpenableColumns.DISPLAY_NAME`。
     *
     * 任何一步失败都回落到 `lastPathSegment`，**不抛异常** —— 这只是显示名，
     * 拿不到不该阻断导入主流程。
     */
    fun displayNameOf(resolver: ContentResolver, fileUri: Uri): String {
        val fallback = fileUri.lastPathSegment.orEmpty()
        return try {
            resolver.query(fileUri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) c.getString(idx) ?: fallback else fallback
                } ?: fallback
        } catch (e: Exception) {
            fallback
        }
    }

    /** 加载结果 */
    sealed interface LoadResult {
        data class Success(val count: Int, val skipped: Int) : LoadResult
        data class Failure(val error: Error) : LoadResult
    }

    /** 加载失败原因（供 UI 映射本地化文案） */
    enum class Error {
        /** 打不开 / 读不出（权限丢失、文件被删） */
        UNREADABLE,
        /** 能读但认不出格式 */
        BAD_FORMAT,
        /** 格式对但没有可用的滚动弹幕 */
        EMPTY
    }

    companion object {
        private const val TAG = "DanmuImportStore"

        /**
         * 日志钩子（默认不做事）。
         *
         * 与 [DanmuImporter.logger] 同款理由：不让纯 JVM 单测因为
         * `android.util.Log` 未 mock 而崩溃。生产环境由 `enableAndroidLog()` 接上。
         */
        var logger: (String) -> Unit = {}

        /** 生产环境启用日志（与 [DanmuImporter.enableAndroidLog] 一起调用即可） */
        fun enableAndroidLog() {
            logger = { msg -> android.util.Log.i(TAG, msg) }
        }

        /**
         * 单次最多投递条数。
         *
         * 取 10：与 AI 模式的 `batchSize`（默认 8）同量级，
         * 保证「补放」时的瞬时压力与正常模式相当。
         */
        const val MAX_PER_TICK = 10
    }
}
