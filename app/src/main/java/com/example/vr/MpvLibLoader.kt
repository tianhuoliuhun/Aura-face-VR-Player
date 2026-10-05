package com.example.vr

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * MPV native 库的**加载 / 管理** —— v2.1.235 引入，v2.1.243 改为「内置优先」。
 *
 * ## ⚠️ v2.1.243：库已**内置 APK**，本类的角色变成"加载器 + 可选下载兜底"
 *
 * ### 历史
 * v2.1.235 曾把 MPV 的 native 库（10 个 so，arm64 展开 36.5 MB / v7a 31.8 MB）
 * **移出 APK**，改成用户第一次选 MPV 内核时下载。理由：MPV 只是三个内核里的一个，
 * 为它让所有人的安装包都大 36MB 不划算。
 *
 * ### 为什么 v2.1.243 又改回内置
 * 查实 `wmv/asf/rm/rmvb` 这类格式**只有 MPV 能解**（IJK 是裁剪版 FFmpeg，
 * 没有 wmv2/wmav2/rv 系解码器；EXO 没有 ASF/RealMedia 解析器）。
 * 于是 MPV 从"可选内核"变成"兜底必需" —— 再要求用户先下载 36MB 才能播一个 WMV，
 * 体验上不可接受；且下载模式多了一条"Release 可达性/下载成功率"的失败路径。
 * 内置后由 linker 从 APK 的 `nativeLibraryDir`（Android 白名单路径）解析，最稳。
 *
 * ### 现在的加载顺序（[ensureLoaded]）
 * 1. **首选**：`System.loadLibrary(短名)` —— 从 APK 内置目录加载。
 *    此时 [LOAD_ORDER] 的**顺序不再关键**（linker 自行按 DT_NEEDED 解析），
 *    但仍逐个加载，目的是让"某个库缺失"在这里就暴露，而不是等 mpv 初始化时崩。
 * 2. **兜底**：若 APK 因故没带上这些 so，而 `filesDir/mpv-libs/` 下有下载副本，
 *    则退回"按 [LOAD_ORDER] 用绝对路径 `System.load`"的方式 —— 这条路径
 *    **顺序仍然关键**（原因见下）。
 *
 * ## ⚠️ 为什么下载兜底那条路必须按固定顺序加载
 * `System.load` 走 dlopen，会按 so 的 `DT_NEEDED` 找依赖：先在**已加载库**里按
 * SONAME 匹配，找不到才会去文件系统找（而后者会撞上 namespace 路径限制）。
 * 所以下载路径按**依赖拓扑序**逐个加载（`LOAD_ORDER`，被依赖的在前）：
 * 轮到 libmpv.so 时，它依赖的 avcodec/avformat/… 全都已在已加载表里，
 * linker 直接从内存里匹配，**根本不会去碰文件系统**。
 * （依赖拓扑已用 `DT_NEEDED` 实测核对：libplayer → libmpv → av* → c++_shared。）
 *
 * ⚠️ 写这个文件的注释时注意：**不要在块注释里写出"斜杠紧跟星号"**。
 *    Kotlin 的块注释是**可嵌套**的，注释里出现那个两字符序列会再开一层注释，
 *    结果整个文件后半段都变成注释，编译器只在文件末尾报一句
 *    "Unclosed comment"，极难定位。
 */
object MpvLibLoader {

    private const val TAG = "MpvLibLoader"

    /**
     * 下载源：**独立 tag 的 Release**（tag 固定为 `mpv-libs`，不随版本号变）。
     *
     * ⚠️ 刻意不写成 `<最新版本 tag>`：否则每次发版都要改代码里的 URL，
     *    一旦忘记改就会 404。放在一个固定 tag 下，资源地址永久有效。
     */
    private const val BASE_URL =
        "https://github.com/tianhuoliuhun/Aura-face-VR-Player/releases/download/mpv-libs/"

    /** 下载文件名：mpv-libs-<abi>.zip */
    private fun zipNameFor(abi: String) = "mpv-libs-$abi.zip"

    /**
     * **依赖拓扑序**（自底向上，被依赖的在前）。
     *
     * ⚠️ 顺序**不能凭直觉猜** —— v2.1.235 第一次就是靠猜的，把 `libplayer.so`
     *    排在 `libmpv.so` 前面，结果实测直接失败：
     *      `dlopen failed: library "libmpv.so" not found:
     *       needed by .../files/mpv-libs/libplayer.so`
     *    原因：**`libplayer.so` 依赖 `libmpv.so`**（不是反过来），轮到它加载时
     *    libmpv 还没进已加载表，linker 就去文件系统找 —— 而它只查 namespace
     *    白名单路径，**不会在私有目录里找同目录的邻居**，于是报 not found。
     *
     * 当前顺序由 `llvm-readelf -d` 读出的真实 `DT_NEEDED` 拓扑排序得到（本包内依赖）：
     *   libc++_shared → （无）
     *   libavutil     → （无）
     *   libswresample → avutil
     *   libswscale    → avutil
     *   libavcodec    → swresample, avutil
     *   libavformat   → avcodec, avutil
     *   libavdevice   → avformat, avutil
     *   libavfilter   → swscale, avformat, avcodec, swresample, avutil
     *   libmpv        → avcodec, avfilter, avformat, avutil, c++_shared, swresample,
     *                   swscale, avdevice   ←（另有 vulkan/OpenSLES/EGL 等系统库，自会解析）
     *   libplayer     → swscale, avcodec, avformat, avutil, **mpv**, c++_shared
     *
     * ⚠️ 与 `.workbuddy/tmp/pack_mpv_libs.py` 的 `LOAD_ORDER` 对应关系：
     *    那边只用它决定"哪些文件进 zip"（顺序无关，zip 内部是平铺的），
     *    这里用它决定"按什么顺序 System.load"（**顺序是关键**）。
     *    长度也是**完整性校验依据**：解压后文件数不等于它就没装成功。
     * ⚠️ 将来若换 mpv 版本：**重新跑一次 `llvm-readelf -d` 核对顺序**，不要沿用。
     */
    val LOAD_ORDER: List<String> = listOf(
        "libc++_shared.so",   // C++ 运行时（APK 里没有它，MPV 的 so 动态依赖）
        "libavutil.so",
        "libswresample.so",
        "libswscale.so",
        "libavcodec.so",
        "libavformat.so",
        "libavdevice.so",
        "libavfilter.so",
        "libmpv.so",          // 依赖上面全部 ffmpeg 组件
        "libplayer.so"        // ⚠️ 它依赖 libmpv.so，所以必须排在 libmpv 之后
    )

    // ===================== 供 UI 观察的状态 =====================
    var isInstalling by mutableStateOf(false)
        private set
    /** 0f..1f */
    var progress by mutableFloatStateOf(0f)
        private set
    /** 面向用户的一句话状态（下载中 / 解压中 / 失败原因） */
    var status by mutableStateOf("")
        private set

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var installJob: Job? = null

    /** 已成功加载过（进程内只加载一次）。 */
    @Volatile private var loaded = false

    /** 这些 so 装在 `filesDir/mpv-libs/` 下。 */
    fun libDir(context: Context): File = File(context.filesDir, "mpv-libs")

    /** 当前设备的 ABI 目录名（与 APK 的 nativeLibraryDir 一致）。 */
    private fun deviceAbi(): String = android.os.Build.SUPPORTED_ABIS.firstOrNull { abi ->
        abi == "arm64-v8a" || abi == "armeabi-v7a"
    } ?: "arm64-v8a"

    /**
     * **下载目录**里是否已装齐（10 个文件都在，且大小不像半截文件）。
     *
     * ⚠️ v2.1.243：这**只反映"下载兜底"路径**的状态，不再等于"库是否可用"。
     *    内置模式下下载目录是空的，但库完全可用 → 判"能不能用"请用 [isReady]。
     *    本方法只用于 UI 决定"显示下载入口还是删除按钮"。
     */
    fun isInstalled(context: Context): Boolean {
        val dir = libDir(context)
        return LOAD_ORDER.all { name ->
            val f = File(dir, name)
            f.isFile && f.length() > 1024L
        }
    }

    /**
     * 按 [LOAD_ORDER] 逐个加载。幂等；成功返回 true。
     *
     * ## ⚠️ v2.1.243：两条加载路径，**优先 APK 内置**
     * MPV 的 so 已改回**内置 APK**（见 `app/build.gradle.kts` 的 packaging 注释），
     * 所以正常情况走的是 `System.loadLibrary(短名)` —— 它由 linker 从 APK 的
     * `nativeLibraryDir`（Android 白名单路径）解析，**最稳**。此时
     * [LOAD_ORDER] 里的 **加载顺序不再重要**（`loadLibrary` 会自行按 DT_NEEDED
     * 解析依赖，且不撞 namespace 路径限制）—— 但仍逐个加载一次，目的是让
     * "某个库缺失"这件事在**这里就暴露**，而不是等 mpv 初始化时崩在 native。
     *
     * 旧的"后下载"路径**保留**为兜底：若 APK 因某种原因没带上这些 so
     * （例如自定义 ABI 过滤、或不慎又加了 excludes），而 `filesDir/mpv-libs/`
     * 下有已下载的副本，则退回到"按 LOAD_ORDER 用绝对路径 System.load"的方式。
     * 这条路径的顺序**仍然关键**（见上方 LOAD_ORDER 的说明）。
     *
     * ⚠️ 任何一步失败都返回 false（不抛）—— 调用方据此回退 EXO。
     *    失败时会把已部分加载的状态保持原样（**不做卸载**：dlopen 的库无法安全卸载，
     *    强行 dlclose 会让其他仍在使用的库崩）。
     */
    @android.annotation.SuppressLint("UnsafeDynamicallyLoadedCode")
    fun ensureLoaded(context: Context): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true

            // ---- 路径 1（首选）：APK 内置，用短名让 linker 自己解析 ----
            val builtIn = try {
                for (name in LOAD_ORDER) {
                    // LOAD_ORDER 里是 "libxxx.so"，loadLibrary 要的是去掉 "lib" 前缀与 ".so" 的短名
                    val short = name.removePrefix("lib").removeSuffix(".so")
                    System.loadLibrary(short)
                }
                true
            } catch (t: Throwable) {
                Log.w(TAG, "APK 内置的 MPV 库加载失败（转试下载目录）: ${t.javaClass.simpleName}: ${t.message}")
                false
            }
            if (builtIn) {
                loaded = true
                Log.i(TAG, "MPV native 库（APK 内置）全部加载成功（${LOAD_ORDER.size} 个）")
                return true
            }

            // ---- 路径 2（兜底）：下载目录，绝对路径 + 严格依赖顺序 ----
            if (!isInstalled(context)) return false
            val dir = libDir(context)
            return try {
                for (name in LOAD_ORDER) {
                    System.load(File(dir, name).absolutePath)
                }
                loaded = true
                Log.i(TAG, "MPV native 库（下载目录）全部加载成功（${LOAD_ORDER.size} 个）")
                true
            } catch (t: Throwable) {
                // UnsatisfiedLinkError 是 Error 不是 Exception；路径限制报的
                // "not accessible for the namespace" 也从这里出来 —— 都要能捕获。
                Log.e(TAG, "MPV native 库加载失败: ${t.javaClass.simpleName}: ${t.message}")
                false
            }
        }
    }

    /**
     * 库是否可用（= 能成功加载 native 库）。
     *
     * ⚠️ **v2.1.243 改法**：不再要求 `isInstalled()`（那是"下载目录 10 个文件都在"）。
     * 因为 so 已改回**内置 APK**，此时下载目录是空的，但库完全可用 ——
     * 若仍要求 `isInstalled`，`isReady` 会永远返回 false，MPV 就白内置了。
     *
     * 现在的判据是直接试加载：[ensureLoaded] 内部**先试 APK 内置（System.loadLibrary）**，
     * 失败才回退下载目录；两条都不行才 false。
     *
     * 幂等且带缓存（`loaded`），所以 UI 反复调用无额外代价。
     */
    fun isReady(context: Context): Boolean = ensureLoaded(context)

    /** 是否正在安装（下载/解压中）。 */
    fun isBusy(): Boolean = installJob?.isActive == true

    /**
     * 下载 + 解压 MPV 库。重复调用会被忽略（已有任务在跑）。
     *
     * @param onDone 成功与否的回调（**主线程**）
     */
    fun install(
        context: Context,
        scope: CoroutineScope,
        onDone: (Boolean) -> Unit
    ) {
        if (isBusy()) return
        isInstalling = true
        progress = 0f
        status = ""
        val abi = deviceAbi()
        val url = BASE_URL + zipNameFor(abi)
        val dir = libDir(context)

        installJob = scope.launch {
            var ok = false
            try {
                withContext(Dispatchers.IO) {
                    dir.mkdirs()
                    val tmp = File(context.cacheDir, zipNameFor(abi))

                    // ---- 1. 下载（流式写盘，边下边报进度）----
                    status = "downloading"
                    val req = Request.Builder().url(url)
                        .header("User-Agent", "AuraFaceVRPlayer").build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) error("HTTP ${resp.code}")
                        val body = resp.body ?: error("empty body")
                        val total = body.contentLength()
                        body.byteStream().use { input ->
                            tmp.outputStream().use { output ->
                                val buf = ByteArray(64 * 1024)
                                var done = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    output.write(buf, 0, n)
                                    done += n
                                    progress =
                                        if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                                }
                            }
                        }
                    }

                    // ---- 2. 解压（zip 内部是平铺的 10 个 so）----
                    status = "extracting"
                    progress = 1f
                    ZipInputStream(tmp.inputStream().buffered()).use { zin ->
                        var entry = zin.nextEntry
                        while (entry != null) {
                            // ⚠️ 只取文件名（去掉可能的目录前缀）并**只接受白名单里的名字**：
                            //    防止 zip 里塞 `../` 路径穿越写到目录外面去。
                            val name = entry.name.substringAfterLast('/')
                            if (name in LOAD_ORDER) {
                                File(dir, name).outputStream().use { zin.copyTo(it) }
                            }
                            zin.closeEntry()
                            entry = zin.nextEntry
                        }
                    }
                    tmp.delete()

                    // ---- 3. 校验完整性 ----
                    status = "verifying"
                    if (!isInstalled(context)) error("incomplete")

                    // ---- 4. 立刻试加载一次，尽早发现问题（而不是等用户点播放才发现）----
                    if (!ensureLoaded(context)) error("link")
                    ok = true
                }
            } catch (t: Throwable) {
                Log.e(TAG, "MPV 库安装失败: ${t.message}", t)
                status = t.message ?: t.javaClass.simpleName
                ok = false
            } finally {
                isInstalling = false
                withContext(Dispatchers.Main) { onDone(ok) }
            }
        }
    }

    /** 删除已安装的库（设置里提供"删除"以便释放空间；下次用时会重新下载）。 */
    fun uninstall(context: Context): Boolean {
        // ⚠️ 进程内已加载的 so 无法卸载，所以只能删文件；
        //    本次运行仍然可用（内存里已有），重启后失效 → 这是可接受的语义。
        return try {
            libDir(context).deleteRecursively()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "删除 MPV 库失败: ${t.message}")
            false
        }
    }

    /** 已安装库占用的字节数（设置里显示）。 */
    fun installedBytes(context: Context): Long {
        val dir = libDir(context)
        if (!dir.isDirectory) return 0L
        return dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
    }
}
