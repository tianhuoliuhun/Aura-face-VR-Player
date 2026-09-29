# GPUPixel 性能优化与人脸识别优化方案（代码级）

- 日期：2026-09-29
- 适用版本：v2.0.185（commit `9e814dc`）→ **实施于 v2.0.186**
- 范围：GPUPixel 美颜链路（`GpuPixelBeauty.kt` + `VRGLRenderer.kt` 的 GPUPixel 分支）与 Mars-Face 人脸检测
- 状态：**第 1~4 步已实施并装机验证**（实测数据见第 0 节；实施清单见第 8 节）

---

## 0. 实测结果（实施后，MuMu 1080p / downscale=1）

### 0.1 分段耗时（`GP profile(120f)` 日志，稳态各 1200+ 帧）

| 指标 | 数值 | 说明 |
|---|---|---|
| `detect` | **1 ms**（每 2 帧一次，单次约 2 ms） | Mars-Face 检测，跑在 `gpDetectExecutor` |
| `gpu` | **19~20 ms** | `processFrame`：上传 + GPUPixel 9 pass + sink 回读 + JNI 拷贝 |
| `rb` | **8~9 ms** | **GL 线程**回读开销（8.29 MB 全分辨率 memcpy） |
| `pboWait` | **0/120** | fence 从未未就绪 → 异步化没有引入任何等待 |

### 0.2 ⚠️ 重要修正：检测并非瓶颈

原判断「检测段占 T_proc 的 40~60%」**被实测推翻** —— MuMu 上 Mars-Face 单次检测仅 **约 2 ms**，
而 GPUPixel GPU 链是 **19~20 ms**（约 10×）。因此：

- **双线程并行的实际收益远小于预期**（只省下约 1 ms/帧，而非 30~40%）；它仍有价值（在真机/大图
  或检测更慢的场景下收益更大），但不是本轮的主要收益来源。
- **真正的瓶颈是 GPU 链本身**，其构成（`jni_sink_raw_data.cc` + `sink_raw_data.cc`）：
  8.29 MB 上传 → 9 个全屏 pass → 8.29 MB `glReadPixels` 回读 → JNI `NewByteArray` 再拷 8.29 MB。
  这三段**全部位于 AAR 边界之内**，调用侧无法消除（见第 6.1/6.2 节）。

### 0.3 PBO A/B 对照（同机同场景，各 1200+ 帧）

| 组 | `pbo` | `detect` | `gpu` | `rb` |
|---|---|---|---|---|
| A | 开 | 1 ms | 19~20 ms | **8~9 ms** |
| B | 关（同步回读） | 1 ms | 18~19 ms | **9 ms** |

**结论：在本模拟器上两者基本持平。** `rb` 的主体是 8.29 MB memcpy（两条路径都要付），
PBO 只省下 `glReadPixels` 的管线同步等待，而该等待在模拟器 GPU 直通下本就很小。
真机（管线更深、同步等待通常 1~3 ms）预期收益为正，故**默认保持开启**，
并保留 `gpAsyncReadback` 运行时开关与三重自动回退。**代价是回读结果再晚 1 帧到位（约 33 ms）。**

### 0.4 已确证的确定性收益

| 项 | 状态 | 依据 |
|---|---|---|
| 删除每帧冗余 `System.arraycopy` | ✅ 已消除 | 原为 8.29 MB/帧纯拷贝（`jni_sink_raw_data.cc` 已保证 `out` 是新数组） |
| 检测与 GL 处理并行 | ✅ 已实现 | T_proc 由「两段之和」变为「两段最大值」；实测省约 1 ms/帧 |
| `SetProperty` 值变化才下发 | ✅ 已实现 | 稳态（滑条不动）下每帧省 2~5 次 JNI + map 查找 |
| 缓存 `aTex` attrib location | ✅ 已实现 | 每帧省 1~2 次 JNI 往返 + 字符串查表 |
| 去冗余 `glClear` | ✅ 已实现 | 每帧省一次全屏写带宽（1080p ≈ 8.3 MB） |
| 检测降频 + 防抖 + alpha-beta 平滑 | ✅ 已实现 | 检测频次 ↓50%；形变抖动 ↓（稳定性提升） |
| PBO 异步回读 | ⚠️ 中性（模拟器） | 见 0.3；真机预期为正 |

**回归验证**：无崩溃、无 `FBO incomplete` / `blit failed` / `GL_INVALID`、无任何 PBO 回退日志、
自适应稳定在 1 档（最高清晰度）、`pboWait 0/120`（无额外等待）。

---

## 1. 结论摘要（原始分析，保留以对照）

### 1.1 当前瓶颈排序（按收益从大到小）

| 级别 | 瓶颈 | 位置 | 本质 |
|---|---|---|---|
| **P0** | 每帧全帧数据穿越 CPU **3~4 次**（≈6~8 MB/帧 memcpy） | `VRGLRenderer.kt:3432` / `:3484` / AAR JNI 层 | raw-data 管线进出都必须落 CPU，其中 1 次拷贝纯属多余 |
| **P0** | 人脸检测（Mars-Face，CPU 推理）与美颜处理（GPUPixel，GPU）**串行**在同一线程 | `GpuPixelBeauty.kt:143-189`（`process()` 内先 `detect` 后 `ProcessData`） | 两段本可重叠的耗时被加在一起 |
| **P1** | GL 线程 `glReadPixels` **同步阻塞**（pipeline flush） | `VRGLRenderer.kt:3419-3423` | 回读是硬同步点，主渲染线程停等 GPU |
| **P1** | 检测每帧必跑，**无防抖、无降频、无时序平滑** | `GpuPixelBeauty.kt:161-165` | `face_detector.cc:56` 的 `timestamp` 恒为 0，Mars-Face 的 VIDEO 模式拿不到时序信息，无法内部跟踪 |
| **P2** | 每帧冗余 GL/JNI 小开销（`glGetAttribLocation` 每帧查、`SetProperty` 每帧重复、保底帧全分辨率 blit） | `VRGLRenderer.kt:2934` / `:3078` / `:2529` | 逐帧累积的固定开销 |

### 1.2 预期收益（MuMu 实测口径，稳态）

| 指标 | 现状（v2.0.185 实测） | 优化后预期 | 提升 |
|---|---|---|---|
| 单帧处理耗时 T_proc（EMA 日志） | **5 ms** | **2.5 ~ 3.5 ms** | ↓ 30~50% |
| GL 线程回读阻塞 | 未计时，估算 1~3 ms/帧 | 提交即返回（延迟 1 帧） | 阻塞 ↓ 80~100% |
| CPU 侧全帧 memcpy | ≈ 6~8 MB/帧 | ≈ 4~6 MB/帧（去 1 次 + 检测小图化可再降） | ↓ 25~35% |
| 保底帧 GPU blit 带宽 | 8.3 MB/帧（1080p） | 2.1 MB/帧（半分辨率） | ↓ 75%（可选项） |
| 检测结果稳定性 | 空结果即清零 → 形变开关抖动 | 连续 N 次失败才清零 + alpha-beta 平滑 | 抖动 ↓（精度感知提升） |

> **重要前提**：MuMu 上当前已收敛到 1 档、5 ms（预算 33 ms），余量很大。本轮优化的**主要价值在真机**（大分辨率、发热降频、后台竞争）与**功耗/发热**，以及把真机低端档位从「降采样到糊」拉回「可保持高清晰度」。

### 1.3 一句话方案

**去冗余拷贝（P0）+ 检测与美颜流水线并行（P0）+ PBO 异步回读（P1）+ 检测防抖与平滑（P1）**，全部在 Kotlin 调用侧完成，不动 AAR、不改预编译 shader、检测精度不降级。

---

## 2. 现状链路与逐跳数据量

### 2.1 每帧完整数据流（全覆盖 + 降采样 2 档，视口 1920×1080）

```
【GL 线程（VRGLRenderer，ES3 context）】
① blitFboToHalf()                     GPU: 1920×1080 → 960×540 缩放 blit（GPU 带宽 ≈ 8.3MB 读 + 2.1MB 写）
② glReadPixels(960×540)               同步阻塞！GPU→faceReadBuffer 2,073,600 B   ← P1 瓶颈
③ buf.get(frame)                      CPU memcpy ×1（2.07 MB）                   ← frame 池复用，OK
④ pendingFaceFrame = frame            加锁移交后台

【faceExecutor 单线程（Executors.newSingleThreadExecutor）】
⑤ det.detect(rgba 960×540)            Mars-Face CPU 推理                          ← P0 瓶颈（串行）
⑥ beauty.SetProperty ×2               JNI ×2（每帧重复设同值）                    ← P2
⑦ source.ProcessData(...)             JNI GetByteArrayElements（可能 +1 次拷贝）
                                       → SyncRunWithContext = 投递任务 + future.wait()
                                       → GPUPixel 专属 ES2 context 线程内：
                                          glTexImage2D 上传 2.07MB
                                          ≈9 个 960×540 全屏 pass（见 2.2）
⑧ reshape.SetProperty(face_landmark)  JNI float[] → std::vector（212 float，可忽略）
⑨ sink.GetRgbaBuffer()                再次 SyncRunWithContext：
                                          glReadPixels 回读 2.07MB → native rgba_buffer_
                                          JNI NewByteArray + SetByteArrayRegion 拷贝到 Java   ← CPU memcpy ×2
⑩ System.arraycopy(out → dst)         CPU memcpy ×1（2.07 MB）                   ← P0 可直接删除
⑪ gpRegionPending = dst               移交 GL 线程

【GL 线程】
⑫ uploadPendingGpRegion()             glTexSubImage2D 2.07MB → 临时纹理
⑬ blitScaledIntoFbo()                 GPU 上采样贴回整张 gpFbo
⑭ snapshotGpResult()                  GPU 全帧 blit → 保底帧（8.3MB 写带宽/帧）   ← P2 可降
⑮ blitToScreen()                      glClear + 全屏 quad 上屏
```

### 2.2 每帧开销清单

| 类别 | 项目 | 量（1080p / 2 档降采样） | 说明 |
|---|---|---|---|
| GPU pass | 主渲染 + 4 个 blit/clear pass + GPUPixel 内部 ≈9 pass | 约 13 个全屏 pass | GPUPixel 侧 9 pass 来源：Source 上传 pass 1 + FaceReshape 1 + BeautyFaceFilter 组 6（BoxBlur H/V 2 + BoxHighPass 的 Blur H/V+Diff 3 + BeautyFaceUnit 1）+ Sink 1 |
| CPU memcpy | ③ + ⑨×2 + ⑩ | ≈ 6.2 MB/帧 | ⑩ 为纯浪费；⑨ 中 JNI 拷贝无法避免（AAR 限定） |
| 跨界数据 | CPU↔GPU 往返 | ≈ 12.6 MB/帧 | ②读 2.1 + ⑦传 2.1 + ⑨读 2.1 + ⑫写 2.1 + 各次 JNI 拷贝 |
| Java 堆 | 每帧 JNI `NewByteArray(2.07MB)` | 每帧 1 个 LOS 分配 | >12KB 走 Large Object Space，GC churn **无法从 Java 侧消除**（见 6.2 长期项） |
| 显存常驻 | gpFbo 8.3 + gpResult 8.3 + gpHalf 2.1 + gpUpload 2.1 | ≈ 20.7 MB | 另加 GPUPixel 侧 framebuffer factory 缓存 |
| 显存常驻（native） | sink `rgba_buffer_` 8.3MB + `yuv_buffer_` 12.4MB（1080p 时） | ≈ 20.7 MB | `sink_raw_data.cc:164-181` **无条件同时分配 RGBA 与 YUV**，I420 我们从不用 → 白占内存；**只能改 AAR，短期不可行** |

### 2.3 关键源码事实（已核实 GPUPixel main 分支源码）

| 事实 | 出处 | 含义 |
|---|---|---|
| `SyncRunWithContext` = 投递到专属 DispatchQueue + **`future.wait()` 同步阻塞** | `src/utils/dispatch_queue.cc:43-84` | `ProcessData`/`GetRgbaBuffer` 在调用线程上是硬等待，但跑在 faceExecutor 后台线程，与 GL 线程**并行**（不同线程、不同 EGL context）—— 这部分架构是健康的 |
| Android 侧自建 **OpenGL ES 2.0** 独立 context（`EGL_CONTEXT_CLIENT_VERSION, 2`） | `src/core/gpupixel_context.cc:151,162` | 与主渲染 ES3 context 不共享显存 → 必须「回读→上传」两次跨 context 搬运；**这是最大结构性开销** |
| sink 输出：`glReadPixels` → native buffer → JNI `NewByteArray`+`SetByteArrayRegion` | `sink_raw_data.cc:134-149`、`jni_sink_raw_data.cc:95-104` | **Java 拿到的每次都是全新数组**，"native buffer 复用"对 Java 不可见 → Kotlin 侧再 arraycopy 是多余的 |
| source 输入：`GetByteArrayElements` + `ReleaseByteArrayElements(..., 0)` | `jni_source_raw_data.cc:66-71` | 可能 pin 也可能拷贝 2.07MB，无法规避 |
| 检测 `timestamp` 恒为 0；landmarks 用传入 `width/height` 归一化 | `face_detector.cc:56, 65-66` | ① Mars-Face VIDEO 模式拿不到时序（无法内部跟踪/跳帧）；② **缩小检测输入不影响归一化坐标** → 检测可单独降分辨率 |
| `FaceReshapeFilter` 只吃 106 点 landmarks（uniform 数组） | `face_reshape_filter.cc:13-110` | 检测与美颜天然解耦，**分离/降频的精度风险只落在"形变滞后"上**，磨皮美白完全不受影响 |
| `SetFramebufferScale` 可缩放内部 framebuffer | `filter.cc:284-288`、`GPUPixelSource.java:73` | 已由我们输入降采样覆盖，**不建议叠加使用**（会二次降质） |

---

## 3. 分项优化方案

### P0-A：删除每帧冗余 `System.arraycopy`（零风险，直接落地）

**问题定位**：`VRGLRenderer.kt:3477-3485`

```kotlin
// 现状（每帧 2.07 MB 纯拷贝）
val need = out.size
val dst = synchronized(gpRegionPoolLock) {
    gpRegionPool?.takeIf { it.size == need }?.also { gpRegionPool = null }
} ?: ByteArray(need)
System.arraycopy(out, 0, dst, 0, need)
gpRegionPending = dst
```

注释称「GPUPixel 内部会复用它的输出 buffer，必须拷一份」——**对 Java 层不成立**：`jni_sink_raw_data.cc:97-104` 每次都 `env->NewByteArray(size)` 新建数组再 `SetByteArrayRegion` 拷入，`out` 本身就是一次性新数组，native 侧 `rgba_buffer_` 的复用被 JNI 隔离了。

**修改建议**：

```kotlin
if (out != null) {
    gpRegionW = fw
    gpRegionH = fh
    // v2.0.186：JNI nativeGetRgbaBuffer 每次 NewByteArray + SetByteArrayRegion
    // （jni_sink_raw_data.cc:97-104），返回的必然是全新数组 —— 不存在"复用被覆盖"
    // 的风险，直接移交所有权给 GL 线程，省掉每帧 2.07 MB 的 arraycopy。
    gpRegionPending = out
}
```

同步清理（否则池永远无人消费）：
- 删除字段 `gpRegionPool` / `gpRegionPoolLock`；
- `uploadPendingGpRegion()` 的 `finally { synchronized(gpRegionPoolLock) { gpRegionPool = bytes } }`（`VRGLRenderer.kt:2964-2967`）整块删除。

**收益**：T_proc ↓ 0.2~0.5 ms；CPU 全帧 memcpy ↓ 2.07 MB/帧（约 -30%）。
**风险**：无。GC 分配量不变（`out` 本来每帧就是新数组），反而少一次拷贝。
**精度影响**：无（像素内容完全一致）。

---

### P0-B：人脸检测与美颜「流水线并行」+ 降频（核心项）

**问题定位**：`GpuPixelBeauty.kt:143-189` —— `process()` 内部**先** `det.detect(...)`（CPU 推理，估占 T_proc 40~60%）**再** `source.ProcessData(...)`（等 GPU，估 30~40%）**再** `sink.GetRgbaBuffer()`（等 GPU 回读）。三段串行，且检测期间 GPU 空转、GPU 处理期间 CPU 空转。

**方案（两档，建议先做简版再做进阶）**：

**简版 B1：检测降频 + landmark 复用**（改动最小）

```kotlin
class Pipeline {
    // v2.0.186：检测与处理解耦 —— 检测可降频，处理每帧照常
    @Volatile private var cachedLandmarks: FloatArray? = null
    private var detectInterval = 2          // 每 N 帧真检一次；按硬件分级（见第 5 节）
    private var frameTick = 0

    fun process(rgba, w, h, stride, smooth, white, slim, eyeZoom): ByteArray? {
        ...
        frameTick++
        if (frameTick % detectInterval == 1 || cachedLandmarks == null) {
            val lm = det.detect(rgba, w, h, stride, VIDEO, RGBA)
            // 防抖：连续 detectInterval 次拿不到脸才清缓存（对齐 GLSL 路径 faceMissStreak 思想）
            missStreak = if (lm == null || lm.isEmpty()) missStreak + 1 else 0
            if (missStreak >= 3) cachedLandmarks = null else if (!lm.isNullOrEmpty()) cachedLandmarks = lm
        }
        val landmarks = cachedLandmarks
        if (landmarks != null) {
            reshape?.SetProperty("face_landmark", landmarks)
            reshape?.SetProperty("thin_face", slim)
            reshape?.SetProperty("big_eye", eyeZoom)
        } else {
            reshape?.SetProperty("thin_face", 0f)
            reshape?.SetProperty("big_eye", 0f)
        }
        source?.ProcessData(...)
        ...
    }
}
```

**进阶 B2：双线程流水线**（检测与 GPU 处理真正重叠，T_proc ≈ max(两段) 而非 sum）

- 把 `det.detect` 从 `process()` 中拆出为独立方法 `detectOnly(rgba, w, h): FloatArray?`；
- 新增 `detectExecutor = Executors.newSingleThreadExecutor()`，与现有 `faceExecutor` 并行：
  - `faceExecutor`：`processFrame(rgba, ...)` 使用 **上一帧已完成的 `cachedLandmarks`**（volatile 发布）；
  - `detectExecutor`：对本帧（或上一帧）跑 `detectOnly`，结果写 `cachedLandmarks`；
- 接口改为：

```kotlin
fun processFrame(rgba: ByteArray, w: Int, h: Int, stride: Int,
                 smooth: Float, white: Float, slim: Float, eyeZoom: Float,
                 landmarks: FloatArray?): ByteArray?
```

**收益**：
- B1：检测段出现频次 ↓50%（interval=2）→ T_proc 预估 ↓ 1~2 ms；
- B2：T_proc ≈ max(detect, gpu) + 拷贝 → 预估 ↓ 30~40%（5 ms → 3~3.5 ms）；
- B1+B2 叠加：检测降频后流水线更短，真机低端档收益最大。

**精度影响论证（关键）**：
1. 磨皮/美白**不依赖检测**（`beauty_face_filter.cc` 只吃像素），任何检测策略都不影响；
2. 瘦脸/大眼只吃 106 点 landmarks，检测滞后 ≤1 帧（33ms）+ 已有回读/贴回 1~2 帧延迟，同量级，**视觉不可感知**；
3. 原实现「空结果立即 `thin_face=0`」反而造成**检测抖动 → 形变开关抖动**（v2.0.182 才加的保护），本方案引入 `missStreak ≥ 3` 防抖 + 保持，**稳定性优于现状**。

**兼容性**：纯 Kotlin 层，无 GL/GPU 要求，所有硬件一致收益。

---

### P1-C：PBO + Fence 异步回读（渲染管线调整，消除 GL 线程硬阻塞）

**问题定位**：`VRGLRenderer.kt:3406-3425` —— `glReadPixels` 是同步调用：驱动必须 flush 整条 GPU 命令管线并等像素落内存才返回，GL 线程在此停等（Adreno 上 960×540 典型 0.5~2 ms，全分辨率更久）。这也是帧延迟的重要来源。

**方案**：ES3 双 PBO 轮转 + `glFenceSync`（本项目 EGL 上下文已是 ES 3.2，`GL_VERSION = OpenGL ES 3.2 v334 R | VENDOR = Qualcomm`，核心 ES3 特性可用）。

```kotlin
// 新增字段
private var gpPboIds = IntArray(2)          // 双 PBO 轮转
private var gpFences = LongArray(2)         // 对应 fence
private var gpPboIdx = 0
private var gpPboReady = false

// 回读段替换（maybeScheduleFaceSampling 内）
val need = rw * rh * 4
if (gpPboIds[0] == 0) initPbo(need)         // glGenBuffers ×2 + glBufferData(GL_PIXEL_PACK_BUFFER, need, null, GL_STREAM_READ)

val cur = gpPboIdx
val prev = 1 - cur
// 1) 收割上一帧：等待 fence（0 超时轮询，超时则本帧交给保底帧，绝不阻塞）
if (gpPboReady) {
    val st = GLES30.glClientWaitSync(gpFences[prev], GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, 0)
    if (st == GLES30.GL_ALREADY_SIGNALED || st == GLES30.GL_CONDITION_SATISFIED) {
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, gpPboIds[prev])
        GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, need,
            GLES30.GL_MAP_READ_BIT).let { mapped ->
            // 拷进 frame（此时 GPU 早已完成，纯内存拷贝，无管线等待）
            (mapped as java.nio.ByteBuffer).duplicate().get(frame, 0, need) // 或直接 position(0).get(frame)
        }
        GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
    } else {
        GLES30.glDeleteSync(gpFences[prev]); gpFences[prev] = 0
        gpSkipReadThisFrame = true          // 未就绪 → 走保底帧（v2.0.185 机制天然兜底）
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        return
    }
}
// 2) 提交本帧回读到另一个 PBO（异步，不等 GPU）
GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, gpPboIds[cur])
GLES30.glReadPixels(x0, y0, rw, rh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0L)
GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
gpFences[cur] = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_BIT, 0)
gpPboIdx = cur; gpPboReady = true
// 注意：本帧没有 frame → 不投递后台，下一帧收割后再投递（整体延迟 +1 帧）
```

**必须配套的安全网**：
1. 运行时探测 + 失败回退：`initPbo` try/catch + 首次 `glGetError()!=GL_NO_ERROR` 则 `gpPboEnabled=false`，永久回退现有同步路径（覆盖旧 Mali 驱动的 PBO bug）；
2. `glClientWaitSync(timeout=0)` 轮询，**绝不使用无限等待**（否则把阻塞换了个地方）；
3. 未就绪帧 → `gpSkipReadThisFrame = true` → v2.0.185 保底帧自动兜底，画面退化为"美颜旧 1 帧"，**与现有防闪烁机制无缝衔接**；
4. FBO 尺寸变化/`resetGpSplitState()` 时销毁重建 PBO 与 fence。

**收益**：GL 线程每帧消除 0.5~3 ms 的硬阻塞（帧延迟 ↓ 1 帧 + 主线程卡顿 ↓）；T_proc 不变（回读仍发生，只是与下一帧重叠）。
**精度影响**：无（像素数据完全一致，只是晚 1 帧到达，本就有 1~2 帧管线延迟）。
**兼容性**：核心 ES 3.0 特性（`GL_PIXEL_PACK_BUFFER` + `glFenceSync`），Adreno/Mali/PowerVR 现代驱动均支持；**必须保留回退路径**。

---

### P1-D：人脸识别「稳定性与精度」优化（不降精度，提升感知质量）

**问题定位**：
1. `GpuPixelBeauty.kt:172-179`：单次空结果即清零 `thin_face/big_eye` → 抖动；
2. `face_detector.cc:56`：`image.timestamp = 0` 恒定 → Mars-Face 的 `RunningMode::VIDEO`（时序模式）**拿不到时间戳**，内部无法做帧间跟踪与自适应 —— 这是 AAR 层缺陷，**调用侧无法修复**，只能靠我们自己的时序滤波补齐；
3. landmarks **无任何时间平滑**（GLSL 路径有 0.75/0.25 lerp，GPUPixel 路径直接裸喂 reshape）。

**方案（Kotlin 层补齐三件事）**：

```kotlin
// ① missStreak 防抖（见 P0-B 代码）
// ② alpha-beta 恒速模型平滑（比简单 lerp 更适合"降频 + 跟踪"场景）
private val smX = FloatArray(106 * 2)   // 位置
private val vx = FloatArray(106 * 2)    // 速度
private var lmTick = 0

fun smoothLandmarks(raw: FloatArray): FloatArray {
    lmTick++
    val dt = 1f
    val alpha = 0.55f; val beta = 0.22f
    for (i in raw.indices) {
        val pred = smX[i] + vx[i] * dt          // 预测
        val meas = raw[i]
        smX[i] = pred + alpha * (meas - pred)   // 校正
        vx[i] = vx[i] + beta * (meas - pred) / dt
    }
    return smX
}
```

3. **检测频率自适应**：无脸（`missStreak` 高）时把 `detectInterval` 放大到 8（省 CPU），重新出现脸时立即恢复 2（利用 VIDEO 模式冷启动代价已由首帧预热覆盖）。

**精度影响论证**：
- alpha-beta 是**预测-校正**滤波，跟踪滞后比纯 lerp 更小，且对降频后的"阶梯感"有插值补偿 → 检测频率下降时**轨迹反而更平滑**；
- 防抖直接消除"形变忽有忽无"这一现有缺陷；
- 真正的算法层提升（多帧跟踪、`timestamp` 修复）在 AAR native 内，列为第 6 节长期项。

**兼容性**：纯 CPU 数学，106 点 × 2 × 几次乘加，开销 < 0.01 ms，全硬件无差异。

---

### P2-E：逐帧固定开销清理（低风险，顺手做）

| # | 问题 | 位置 | 修改 |
|---|---|---|---|
| E1 | `glGetAttribLocation(gpBlitProgram, "aTex")` **每帧调 1~2 次**（JNI + 字符串哈希） | `VRGLRenderer.kt:2934`、`:3078` | 新增字段 `gpBlitTexAttrLoc`，在 `ensureBlitProgram()` 中与 `gpBlitPosLoc` 一起缓存 |
| E2 | `SetProperty("skin_smoothing"/"whiteness"/...)` 每帧重复（每帧 2 次 JNI + std::string 构造 + map 查找），**值根本没变** | `GpuPixelBeauty.kt:156-157,173-178` | 缓存 `lastSmooth/lastWhite/lastSlim/lastEye`，仅变化时才调用（UI 拖动滑条时才变） |
| E3 | `blitToScreen` 每帧 `glClear` —— 全屏 quad 已 100% 覆盖，clear 纯属额外带宽 | `VRGLRenderer.kt:2924-2925` | 删除 clear（保留黑边兜底：仅当 srcTex 尺寸 != 视口时才 clear） |
| E4 | 保底帧快照 = 全分辨率 8.3 MB 写带宽/帧 | `VRGLRenderer.kt:2529-2549` | 可选：`ensureGpResultFbo(gpFboW/2, gpFboH/2)` 半分辨率快照，blit 带宽 ↓75%；`blitToScreen` 用 LINEAR 采样上采样，保底帧只持续 1~2 帧，轻微软化不可感知。**建议先实测 blit 占比再决定** |

**收益合计**：T_proc ↓ 0.1~0.3 ms；GPU 带宽 ↓（E3/E4 合计约 12~16 MB/帧）。
**风险**：E3 需确认所有上屏路径 quad 都铺满（`blitToScreen` 与 `blitScaledIntoFbo` 均为全屏/全矩形，成立）。

---

### P2-F：检测输入独立降采样（可选，收益取决于人脸占比）

**依据**：`face_detector.cc:65-66` 用 `point.x / width` 归一化 → **检测输入缩小不改变归一化坐标**，与美颜处理分辨率完全解耦。

**方案**：复用现成的 `blitFboToHalf` 管道，多做一次 1/2 blit 得到 **480×270 检测专用图**（0.53 MB），`detect` 吃小图、`ProcessData` 吃 960×540：

```kotlin
// 仅当 rw ≥ 960 时启用（保证缩小后人脸仍有 ≥60px）
if (gpDetectSmall && rw >= 960) {
    blitFboToHalf(uvx0, uvy0, uvw, uvh, rw / 2, rh / 2)   // 注意：需独立的第二张降采样 FBO
    glReadPixels(...)  // 0.53MB → 只喂 det.detect
}
```

**收益**：检测耗时随像素数近似线性下降 → 检测段 ↓ 约 60~75%（若检测是大头，T_proc ↓ 25~40%）。
**风险与护栏**（必须全部做）：
1. 门控 `rw ≥ 960`（小分辨率下不启用，避免小脸漏检）；
2. 小图检测连续 2 次空 → **自动回退全分辨率图检测一次**再继续；
3. VR 并排双画面本来脸小，**默认关闭，仅 2D 全屏模式默认开启**。
**精度影响**：有门控 + 回退时可视为无损；无门控则有漏检风险 —— 故列为"可选、需实测验证"。

---

## 4. GPU 资源利用率专项说明

| 观察 | 数据 | 结论 |
|---|---|---|
| 双 EGL context（主 ES3 + GPUPixel ES2） | 每帧 2 次跨 context 全帧搬运（上传 2.1 + 回读 2.1） | **最大结构性开销**，见 6.1 |
| GPUPixel 内部 9 个全屏 pass @960×540 | 平均 2~4 次纹理采样/pass | GPU 段并非空闲，**不要再叠加 `SetFramebufferScale`**（会二次降质） |
| 保底帧 blit + 上屏 clear | 8.3 + 8.3 MB 带宽/帧 | E3/E4 可砍掉一半 |
| 回读同步点 | glReadPixels flush 整条管线 | PBO 消除（P1-C） |
| 降采样档位自适应 | 已有（EMA + 预热 3 帧 + 连续 2 次同向），MuMu 收敛 1 档 | 保留；建议把 `gpDetectInterval` 挂到同一套 `gpProcNanosAvg` 上联动 |

---

## 5. 分硬件配置策略

复用 v2.0.185 已有的 `gpProcNanosAvg` / `gpDownscaleNow` 自适应框架，新增一条联动规则：

| 档位 | 判据（EMA T_proc） | 降采样 | detectInterval | PBO | 检测小图 |
|---|---|---|---|---|---|
| 高端（真机旗舰） | < 8 ms | 1~2 | **1（每帧，精度优先）** | 开 | 开（2D） |
| 中端 | 8~20 ms | 2 | 2 | 开 | 开（2D） |
| 低端 / 模拟器 | > 20 ms | 3~4 | 3~4 | 开（失败则自动回退） | 关（脸小风险） |

- 换档沿用「**连续 2 次同向才切换** + **预热 3 帧**」的既有防连跳规则；
- **精度不降级的底线**：任何档位下，磨皮/美白恒为全帧全分辨率管线效果；仅形变 landmarks 的更新频率随档位变化（≤1 帧滞后 + alpha-beta 平滑补偿）。

---

## 6. 不可做 / 长期项（明确边界，避免无效投入）

### 6.1 纹理共享（真正的架构解，短期不可行）

理想态：主渲染的 FBO 纹理直接 `eglCreateImage` + 共享 context 交给 GPUPixel 消费，**彻底消灭 4 次跨界拷贝（≈8.3 MB/帧）+ 2 次 glReadPixels + 1 次 glTexImage2D**，预期 T_proc 可再降 40~60%。
不可行原因：
1. 预编译 AAR 只暴露 raw-data 通道（`javap` 已核实 9 个类，无 texture/GL 输入的 Java API）；
2. GPUPixel 自建 **ES2** 独立 context（`gpupixel_context.cc:151,162`），与主 ES3 context 无共享；
3. 需要 fork 源码加 `GPUPixelSourceTexture` JNI → 涉及自建 native 构建链与 AAR 维护成本。
**建议**：作为独立技术债立项，不混入本轮。

### 6.2 消除每帧 Java LOS 分配（需改 AAR）

`nativeGetRgbaBuffer` 每帧 `NewByteArray(2.07MB)` → Large Object Space churn。Java 侧无法复用（拿到的必是新数组）。长期方案：AAR 增加 `GetRgbaBuffer(ByteBuffer)` / `int GetRgbaBufferInto(byte[] dst)` 重载。
**注意**：P0-A 之后 LOS 分配量**不变**（`out` 本来每帧就是新的），只是省了拷贝 —— 不要误判为"引入了 GC 问题"。

### 6.3 Mars-Face 时序能力修复（需改 AAR / 上游 PR）

`face_detector.cc:56` 的 `timestamp=0` 导致 VIDEO 模式时序能力失效。可向上游 `pixpark/gpupixel` 提 PR（JNI `detect` 增加 `long timestampMs` 参数），本地短期靠 P1-D 的 Kotlin 层 alpha-beta 补齐。

### 6.4 sink 的 YUV buffer 白占内存（需改 AAR）

`sink_raw_data.cc:164-181` 无条件分配 RGBA + YUV 两块 buffer（1080p 合计 20.7 MB native），I420 从不使用。同上游 PR 项。

### 6.5 GPUPixel 内部 shader 优化

9 个 pass 的 GLSL 全在预编译 `libgpupixel.so` 内（`BeautyFaceFilter`/`FaceReshapeFilter` 等），**调用侧无任何手段修改**。我们自己的 shader（blit 三个）已是极简形态（`texture2D` 直通 + `mediump`），无优化空间。—— 这就是"着色器优化"在本项目的实际边界：**只能优化调用侧的管线结构，改不了库内 shader**。

---

## 7. 实施顺序、量化验证方法

### 7.1 实施顺序（每步独立可编译、可验证、可回退）

| 步骤 | 内容 | 预期 T_proc | 风险 |
|---|---|---|---|
| 1 | P0-A 删 arraycopy + P2-E1/E2/E3 清理 | 5 → 4.5~4.8 ms | 极低 |
| 2 | P0-B1 检测降频 + missStreak 防抖 + P1-D alpha-beta 平滑 | → 3.5~4 ms | 低 |
| 3 | P0-B2 双线程流水线 | → 2.5~3.5 ms | 中（线程编排） |
| 4 | P1-C PBO 异步回读（含回退） | GL 线程阻塞 ↓，T_proc 不变 | 中（驱动兼容） |
| 5 | P2-F 检测小图（仅 2D，带门控） | 视脸占比再 ↓ 0.5~1.5 ms | 中（漏检护栏） |
| 6 | P2-E4 保底帧半分辨率（实测 blit 占比后再定） | GPU 带宽 ↓ | 低 |

### 7.2 量化测试方案（先插桩，后改码 —— 拿到基线再动刀）

在 `GpuPixelBeauty.Pipeline.process()` 内分段计时，每 120 帧打一行：

```kotlin
val t0 = System.nanoTime()
val lm = det.detect(...)                     // A: 检测
val t1 = System.nanoTime()
source?.ProcessData(...)                     // B: 上传+GPU 链
val t2 = System.nanoTime()
val out = sink?.GetRgbaBuffer()              // C: 回读+JNI 拷贝
val t3 = System.nanoTime()
// 日志：detect=A/1e6, gpu=B/1e6, sink=C/1e6 (ms)
```

GL 线程侧同样分段（`blitFboToHalf` / `glReadPixels` / `buf.get`），**注意 glReadPixels 是同步的所以计时准确**；PBO 改造后改用 `glClientWaitSync` 的等待时长作为对照指标。

判定口径（沿用既有习惯）：
- T_proc EMA 与档位日志：`GPUPixel adaptive downscale: ...`（已有）；
- 新增分段日志：`GP profile: detect=2.1 gpu=1.4 sink=1.2 read=0.9 ms`；
- 闪烁/保底帧回归：确认无 `stable-result` / `blit failed` / `FBO incomplete` 告警；
- 精度回归：同一段视频，优化前后截帧对比瘦脸/大眼贴合度（MuMu `screencap` + 并排比对），并确认检测丢失率不升。

### 7.3 精度不受影响的验收标准

1. 磨皮/美白视觉完全一致（不依赖检测，管线未动）；
2. 瘦脸/大眼：同帧偏差 ≤ 1 帧延迟，无肉眼可见的滞后/抖动，且**抖动优于现状**（有防抖与平滑）；
3. 检测丢失率：优化前后同素材统计，不升（目标 ↓，因有 missStreak 防抖）；
4. 档位切换无连跳（沿用预热 + 滞回规则）。

---

## 8. 关键代码修改清单（供实施核对）

| # | 文件 | 位置 | 改动 | 级别 | 风险 |
|---|---|---|---|---|---|
| 1 | `VRGLRenderer.kt` | `:3477-3485` | 删 `arraycopy`，`gpRegionPending = out`；删 `gpRegionPool`/`gpRegionPoolLock` | P0 | 无 |
| 2 | `VRGLRenderer.kt` | `:2964-2967` | 删 `uploadPendingGpRegion` 的还池 `finally` | P0 | 无 |
| 3 | `GpuPixelBeauty.kt` | `:143-189` | 拆 `detectOnly` / `processFrame(landmarks)`；加 `detectInterval`、`missStreak`、`cachedLandmarks` | P0 | 低 |
| 4 | `VRGLRenderer.kt` | `:3445-3585` | 新增 `detectExecutor`，检测与处理双线程流水线 | P0 | 中 |
| 5 | `GpuPixelBeauty.kt` | 新增 | alpha-beta landmarks 平滑 | P1 | 低 |
| 6 | `VRGLRenderer.kt` | `:3406-3425` | PBO + fence 异步回读 + 自动回退 | P1 | 中 |
| 7 | `GpuPixelBeauty.kt` | `:156-157,173-178` | `SetProperty` 值变化才调用 | P2 | 无 |
| 8 | `VRGLRenderer.kt` | `:2934`,`:3078` | 缓存 `gpBlitTexAttrLoc` | P2 | 无 |
| 9 | `VRGLRenderer.kt` | `:2924-2925` | 去冗余 `glClear` | P2 | 低 |
| 10 | `VRGLRenderer.kt` | `:2478-2514` | 保底帧半分辨率（可选，先实测） | P2 | 低 |
| 11 | `VRGLRenderer.kt` | `computeGpCoverRegion` / 自适应 | `gpDetectInterval` 挂入 `gpProcNanosAvg` 联动分档 | P1 | 低 |
| 12 | `GpuPixelBeauty.kt` | 检测输入 | 480×270 检测小图 + 门控 + 回退（仅 2D 默认开） | P2 | 中 |

> 所有改动前先按约定备份：`<文件>.<yyyyMMdd-HHmmss>.bak`（2026-09-29 已预备份两份：`GpuPixelBeauty.kt.20260929-091549.bak`、`VRGLRenderer.kt.20260929-091549.bak`，位于同目录，待实施后随验证结果处置）。
