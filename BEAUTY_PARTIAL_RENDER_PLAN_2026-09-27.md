# 美颜「只渲染变化区域」性能优化方案 + 开源方案调研

> 日期：2026-09-27
> 状态：**调研与方案，未改任何代码**
> 目标：降低美颜链路的 GPU 开销，把算力从「全屏无条件」收缩到「真正有变化的区域」

---

## 一、结论速览

| 优先级 | 优化项 | 当前代价 | 预期收益 | 改动量 | 风险 |
|---|---|---|---|---|---|
| **P0** | 磨皮在**非皮肤像素跳过 8 次采样** | 8 次 `texture2D` / 像素 × 全屏 | 片元着色器纹理采样量 **↓ 55~70%** | 小（纯 shader 重排） | 极低 |
| **P0** | GPUPixel 回读范围从**全屏**收窄到**人脸 ROI** | 每帧回读 1920×1080×4 ≈ **8 MB** + 同步阻塞 | CPU 回读量 **↓ 75~90%**，消除 8MB/帧 GC 压力 | 中（复用已有 ROI 换算） | 中（需处理 ROI 边界与闪烁） |
| **P1** | 磨皮改**半分辨率 pass**（降采样 → 磨皮 → 上采样） | 全分辨率 9 次采样 | 磨皮 pass 片元压力 **↓ 75%** | 大（需加 FBO 与第二 program） | 中（低分辨率引入块状感，需调参） |
| **P1** | 分离式高斯（水平 + 垂直两 pass）替代 8 邻域 | 8 次采样 / 像素 | 相同模糊半径下采样数 8 → 6，且半径可大幅放大 | 中 | 低（观感需重新调参） |
| **P2** | 人脸检测**降频 + 坐标预测** | 已 8 帧一次 | 再省 10~15% CPU | 中 | 中（预测错误会拖影） |
| **P2** | 妆容/腮红/口红 gate 到人脸包围盒 | 全屏跑 `ellipseMask` | 非人脸区省 ~20 次算术 | 小 | 低 |

**一句话**：不用等任何开源库，P0 两项就能把最贵的两处开销砍掉一大半，且都建立在项目**已有基础设施**上。

---

## 二、当前开销实测剖析

### 2.1 GLSL 路径（`VRGLRenderer.kt` 内联 shader，ESSL 100）

主 shader 的 fragment 每像素采样次数（最坏情况）：

| 来源 | 采样次数 | 是否无条件执行 | 代码位置 |
|---|---|---|---|
| 基础色（视频/图片） | 1 | 是 | 790 / 792 |
| **磨皮 8 邻域** | **8** | **是**（仅门控 `uBeautyStrength > 0.01`） | 808–815 |
| 美白 LUT | 2 | 门控 `uWhiten > 0.01` | — |
| 妆容/腮红等 | 0（纯算术） | 门控 `uFaceDetected == 1` | 851+ |
| **合计最坏** | **11** | | |

**核心问题**：磨皮的 8 次采样在 **797 行**就开始跑，而「这个像素是不是皮肤」的判定直到 **839 行**才做：

```glsl
if (uBeautyStrength > 0.01) {
    vec2 stepF = vec2(uTexelSize.x * 6.0, uTexelSize.y * 6.0);
    vec4 n1 = ... texture2D(...);   // ← 8 次采样，无条件下发
    ... n8
    vec4 low = (color + n1*w1 + ...) / (1.0 + w1 + ...);
    vec3 detail = color.rgb - low.rgb;
    vec3 smoothed = low.rgb + detail * uTextureDetail;

    if (isSkin(color.rgb)) {                      // ← 到这才判皮肤
        color.rgb = mix(color.rgb, smoothed, uBeautyStrength);
    } else {
        color.rgb = mix(color.rgb, smoothed, uBeautyStrength * 0.25);  // ← 结果只乘 0.25
    }
}
```

非皮肤区（背景、头发、衣物、黑边）通常占画面 **60~80%**，这些像素**白付了 8 次纹理采样**，最后只换来一个乘 0.25 的轻微降噪 —— 而这 0.25 的降噪在视频播放场景下**肉眼几乎不可见**。

> 移动 GPU 上纹理采样是 fragment shader 的主要瓶颈（带宽 + TEX 单元吞吐）。在 1080p × 60fps 下，8 次采样/像素意味着每秒 **~10 亿次**采样请求，其中约 6.5 亿次是纯浪费。

### 2.2 GPUPixel 路径（全帧回读）

`maybeScheduleFaceSampling()`（2018 行起）在 GPUPixel 激活时：

```kotlin
val rw = if (gpFullFrame) w else minOf(faceReadSize, w)   // ← 全屏宽
val rh = if (gpFullFrame) h else minOf(faceReadSize, h)   // ← 全屏高
...
GLES20.glReadPixels(x0, y0, rw, rh, GL_RGBA, GL_UNSIGNED_BYTE, buf)
```

- 1920×1080×4 = **8.29 MB / 帧**，`glReadPixels` 是**同步阻塞**调用 —— GL 线程在此处停下等 GPU 回传
- 随后 `buf.get(frame, 0, need)` 再拷一份 → 每帧产生 **~16 MB 内存流量** + 8MB 堆分配（虽有池化，`faceFramePool` 只保留 1 块，后台慢时仍会新分配）
- 回读结果**整幅**交给 GPUPixel，其内部 Mars-Face 检测再自己找人脸

**核心问题**：Kotlin 侧**已经有** `faceCropScaleX/Y` + `mapCropXToViewport` / `mapCropYToViewport` 这套「裁剪图坐标 ↔ 视口坐标」换算基础设施（1873/1881/2049/2050 行），原本就是为「只处理人脸小图」设计的；v2.0.172 为满足「全屏生效」把它扩到了全屏，代价就是 8MB/帧。

---

## 三、可落地方案详述

### 方案 A（P0）：磨皮「先判皮肤，后采样」

**做法**：把 `isSkin` 判定**提到采样之前**，非皮肤像素走一条几乎零成本的路径。

```glsl
if (uBeautyStrength > 0.01) {
    bool skin = isSkin(color.rgb);          // ← 纯标量比较，无采样
    if (skin) {
        // 只在皮肤像素上做 8 邻域磨皮（原逻辑原封不动）
        vec2 stepF = vec2(uTexelSize.x * 6.0, uTexelSize.y * 6.0);
        vec4 n1 = ...; ... vec4 n8 = ...;
        float deltaThres = 0.30;
        float w1 = ...; ... float w8 = ...;
        vec4 low = (color + n1*w1 + ... + n8*w8) / (1.0 + w1 + ... + w8);
        vec3 detail = color.rgb - low.rgb;
        vec3 smoothed = low.rgb + detail * uTextureDetail;
        color.rgb = mix(color.rgb, smoothed, uBeautyStrength);
    }
    // 非皮肤：完全不做（原实现只乘 0.25，视觉差异不可感知）
}
```

**收益**：
- 纹理采样：非皮肤区从 **9 次 → 1 次**
- 全屏加权后总采样量降幅 ≈ 非皮肤占比 × 8/11 ≈ **55~70%**
- 移动 GPU 上 fragment 是瓶颈时，**帧率提升可期 15~30%**（视视频分辨率与设备而定）

**权衡**：
- 非皮肤区失去那 0.25 强度的轻微降噪。若想保留，可保留一个**极轻量的 2 次采样**版本（例如只用 `n5`/`n6` 垂直两邻域），代价仅 +2 次采样但换回背景噪点抑制。**建议先做纯跳过版，实测观感再决定是否补。**
- 皮肤/非皮肤**边界像素**可能出现轻微过渡痕迹。缓解：对判定做一点软化（保留原 `mix` 但强度按 `isSkin` 输出 1.0/0.0 连续化）—— 代价是重新引入采样。实践中由于 `isSkin` 是阈值判定、且磨皮变化幅度本身温和，**边界差异通常不可见**。
- `dFdx/dFdy` 分歧：`if` 分支在 GPU 上以 2×2 quad 为单位执行，quad 内若同时含皮肤与非皮肤像素，**两条分支都会执行**（无收益但无害）。由于 `isSkin` 在局部区域高度一致，quad 分歧概率低，收益基本保留。

**注意**：`isSkin` 目前是「RGB 阈值」快速判定（543~548 行），对偏黄偏红的背景（木地板、暖光墙、肤色家具）可能误判为皮肤 —— 这时会退化到全采样，**只损失收益、不会出错**，是可接受的失败模式。

### 方案 B（P0）：GPUPixel 回读收窄到人脸 ROI

**做法**：利用已有的人脸坐标（`uFaceCenter` / `uEyeDistance` 或 MediaPipe 关键点）算出一个**带余量的包围盒**，只回读该区域。

```
人脸中心 (cx, cy) + 尺度 fUnit（视口 UV 空间）
   ↓ 扩边（发丝、下巴、脖子要包住）
ROI 边长 ≈ fUnit * 视口宽 * 3.0（经验值：2.5~3.5 倍眼距）
   ↓ clamp 到视口边界，并向上取 16 像素对齐（GPU 回读对齐友好）
回读区域 → 交给 GPUPixel → glTexSubImage2D 贴回同一区域
```

项目**已有的基础设施**（可直接复用，无需新写坐标数学）：

| 设施 | 位置 | 用途 |
|---|---|---|
| `faceCropScaleX/Y` | 2049 / 2050 | 记录「裁剪区 / 视口」比例 |
| `lastSampleX0/Y0` | 2052 / 2053 | 记录回读区左上角（贴回时用同值） |
| `mapCropXToViewport()` | 1873 | 裁剪图坐标 → 视口坐标 |
| `mapCropYToViewport()` | 1881 | 同上（Y 轴翻转） |
| `uploadPendingGpRegion()` | 2006 | 已支持「只贴回某矩形」 |

即：**贴回侧的非居中局部区域支持已经就绪**（`uploadPendingGpRegion` 用 `lastSampleX0/Y0` + `gpRegionW/H` 做 `glTexSubImage2D`），只需要把「回读区的计算」从写死的「居中 512×512 / 全屏」改成「跟人脸走」。

**收益**：
- 人脸 ROI 典型 640×640 → 1.6 MB（vs 8.29 MB），**↓ 80%**
- 极窄场景（远景小脸）可降到 256×256 → 0.26 MB，**↓ 97%**
- 直接消除 8MB/帧的同步阻塞停顿与内存流量

**必须处理的坑**：

1. **闪烁**（v2.0.173 已踩过）：这是 `interval` 必须为 1 的根因 —— 若 ROI 覆盖范围内「部分帧是美颜帧、部分帧是原始帧」，ROI 边缘会出现矩形闪烁。ROI 方案下同样成立，**interval 仍须保持 1**。
2. **ROI 抖动**：人脸轻微移动会导致 ROI 每帧变化、贴回矩形边缘出现「美颜/未美颜」分界跳动。解法：① ROI 尺寸**量化到固定档位**（如 512/640/768），避免尺寸连续变化；② ROI 中心做**低通跟随**（跟随系数 0.2~0.3），只在大幅移动时跳变；③ ROI **向外对齐到 16 像素**。
3. **人脸丢失**：检测失败时（`uFaceDetected == 0`）应**保持上一次 ROI**（沿用防抖逻辑的 `faceMissStreak`），不要立刻切回全屏或停止回读，否则出现画面跳变。
4. **瘦脸/大眼**：GPUPixel 内部会做几何形变，**形变结果可能超出 ROI**（脸被拉瘦时边缘像素来自 ROI 外）。余量必须给足（≥ 1.3 倍），否则脸颊边缘会有硬切割线。
5. **VR 分屏**：左右眼各有一张脸、各自 ROI —— 当前 `isSplitScreenVR` 时 `vpW = displayWidth / 2`，ROI 需对每只眼独立计算并分别回读（可两次回读，或回读覆盖双眼的联合矩形）。

### 方案 C（P1）：半分辨率磨皮 pass

**做法**：把磨皮从主 shader 里拆出来，变成独立的离屏 pass：

```
主渲染 → FBO_A（全分辨率）
   ↓ 降采样 1/2（bilinear）
FBO_B（半分辨率）
   ↓ 磨皮（8 邻域，半径按半分辨率 texel 调整）
FBO_B'（半分辨率，磨皮后）
   ↓ 上采样 + 与 FBO_A 按 uBeautyStrength 混合
屏幕
```

**为什么可行**：磨皮处理的是**低频视觉信息**（色块、光影过渡、痘印），半分辨率下的双线性放大**几乎不可辨**；而高频细节（毛孔、发丝）本来就要靠 `detail * uTextureDetail` 从全分辨率原图叠回去 —— 拆分后这个「叠回」在混合 pass 里做，**纹理保留度不受影响**。

**收益**：磨皮 pass 的片元数降到 **1/4** → 该 pass 开销 ↓ 75%。若磨皮占全帧着色 60%，总开销约 ↓ 45%。

**代价**：需要新增一组 FBO + 一个磨皮 program + 一个混合 program，代码量明显大于方案 A；且 `uTexelSize`、`stepF` 半径都要按降采样后的纹理重算，观感需重新调参。

### 方案 D（P2）：妆容/腮红/口红 gate 到人脸包围盒

**做法**：在 851 行的 `if (uProjectionMode == 0 && uFaceDetected == 1)` 内层再加一层：

```glsl
vec2 fCenter = uFaceCenter;
float fUnit = clamp(uEyeDistance, 0.02, 0.25);
// 只在人脸包围盒（3 倍眼距）内做妆容计算
if (abs(tc.x - fCenter.x) < fUnit * 1.5 && abs(tc.y - fCenter.y) < fUnit * 1.8) {
    ... 原有的 ellipseMask / softLight 妆容逻辑 ...
}
```

**收益**：非人脸区省掉约 20+ 次算术（`ellipseMask` 内含 `cos/sin/length/smoothstep`）。**注意这是纯算术优化，不影响纹理采样**，所以收益远小于方案 A。仅在人脸占画面很小时有效。

### 方案 E（P2）：检测降频 + 坐标预测

当前 GLSL 路径已是 `faceSampleInterval = 8`（8 帧一次检测）。可进一步：

- **线性预测**：用最近两次检测结果外推当前帧人脸位置（`p = p1 + (p1 - p0)`），在 8 帧间隔内逐帧更新 `uFaceCenter`，让妆容跟随更顺滑 —— 这是**感知质量优化**而非性能优化。
- **降频到 12/16 帧**：省下的光流/检测开销有限（检测本身在后台线程），**收益不明显**，不建议。

---

## 四、开源方案调研

### 4.1 项目已集成的：GPUPixel

| 项 | 内容 |
|---|---|
| 仓库 | `github.com/pixpark/gpupixel` |
| 语言 | C++11 + OpenGL ES 2.0/3.0 |
| 许可 | MIT |
| Star | ~2.4k |
| 能力 | 双边磨皮、美白、口红、腮红、瘦脸、大眼、脸型调整；内置 **Mars-Face** 人脸检测 |
| 性能（官方） | Xiaomi 10 / Huawei Mate30 上 **5~6 ms / 帧** |
| 集成状态 | **本项目已集成预编译 AAR**：`app/libs/gpupixel-release.aar` |

**与本项目的关系**：GPUPixel 的 shader 已经是**高度优化过的版本**（`gpupixel/src/...` 下的 `beauty_face_*.cc` 之类），它的双边磨皮同样是全屏皮肤判定 —— 所以方案 A 的优化思路**对 GPUPixel 内部同样适用**，但那是第三方预编译代码，改不了。因此：

- **本项目自己的 GLSL 路径**（`VRGLRenderer.kt` 内联 shader）→ 可直接落地方案 A/B/C
- **GPUPixel 路径** → 只能在**调用侧**优化（即方案 B 的 ROI 回读），内部改不了

### 4.2 其他候选对照

| 项目 | Star | 许可 | 平台/语言 | 适用性评估 |
|---|---|---|---|---|
| `pixpark/gpupixel` | 2.4k | MIT | C++ / GLES2+ | **✅ 已集成**，最成熟 |
| `wysaid/android-gpuimage-plus` (CGE) | 3.2k | 混合（商用需授权） | C++ / GLES | 滤镜/模糊/畸变强，**但无人脸关键点**，做美颜要自己接检测 |
| `cats-oss/android-gpuimage` | — | Apache-2.0 | Java / GLES2 | 50+ 滤镜，**已停止维护**，无磨皮/人脸能力 |
| `uu-code007/PixelFreeEffects` | — | MIT | iOS/Android/Win/HarmonyOS | 美颜+美型+美妆+滤镜+绿幕，功能全；**社区规模小、文档少**，作为参考 |
| `zhanghao5683934/Meihu-Beautyface-sdk` | — | — | iOS 为主 | Android 侧不完善，**不建议** |
| `Guikunzun/BeautifyFaceDemo` | — | — | ObjC / GPUImage | **教学示例**，非生产库，仅作算法参考 |

### 4.3 行业通用优化手法（与上面的方案对照）

| 手法 | 说明 | 本项目对应方案 |
|---|---|---|
| **降采样处理** | 1080p → 540p 做磨皮再放大；磨皮是低频信息，肉眼不可辨 | 方案 C |
| **分级处理** | T0 眉眼唇高精度 / T1 脸颊额头中等 / T2 背景简单高斯 | 方案 A + 方案 D |
| **区域性门控** | mask 纹理按 UV 选择处理强度 | 方案 A（用 `isSkin` 代替 mask） |
| **分离卷积** | 高斯改「水平 + 垂直」两 pass | 方案 C 的替代实现 |
| **检测降频** | 连续帧检测降到 2~3 帧一次 | 已是 8 帧一次，**无需改** |
| **ROI 处理** | 只在人脸包围盒内处理 | 方案 B |

> 注：网上流传的「GPU 脏矩形 / partial redraw」在这类**全屏视频播放**场景里**不适用** —— 视频每帧整幅都在变，「变化区域」就是全屏。所以「只渲染变化部分」在视频美颜语境下的正确解读是「**只在人脸/皮肤区域做重计算**」，而不是「跳过未变化的屏幕区域」。这也正是上面方案的立足点。

---

## 五、推荐实施顺序

```
第 1 步（P0，纯 shader 重排，风险最低）
  方案 A：磨皮先判 isSkin 再采样
  → 编译 → 装机 → 用户看观感（重点看：非皮肤区背景是否变脏、人脸上边缘有无分界）

第 2 步（P0，调用侧改动）
  方案 B：GPUPixel 回读收窄到人脸 ROI
  → 需先解决 ROI 抖动（尺寸量化 + 中心低通 + 16 像素对齐）
  → 重点验证：ROI 边缘有无矩形闪烁、瘦脸时脸颊边缘有无切割线

第 3 步（P1，结构改动，视第 1 步收益决定是否做）
  方案 C：磨皮半分辨率 pass
  → 若第 1 步已让美颜开销降到不重要，可跳过

第 4 步（P2，收益小，可选）
  方案 D：妆容 gate 到人脸包围盒
```

**建议**：先做第 1 步。它是**纯 shader 内部重排**、不涉及任何跨帧状态、不引入新 FBO，出问题的概率最低而收益已经很大。做完实测帧率与观感，再决定是否继续第 2 步。

---

## 六、验证方法

1. **帧率对比**：`adb shell dumpsys gfxinfo com.aistudio.vrplayer.vrmjpy`（关注 Janky frames / 90th percentile）；或直接看 `adb logcat` 里的 FPS 日志
2. **GPU 负载**：Adreno Profiler / Snapdragon Profiler（若可用）；退而求其次用一个「美颜全开 vs 全关」的 A/B 帧率对比
3. **观感验证（用户手动）**：
   - 非皮肤区背景（暗部、彩色物体）有无变得比原来「脏」或「噪」
   - 人脸边界（下巴/发际）有无可见的强度分界
   - 快速转头时有无矩形闪动（方案 B 的关键风险）

---

## 七、改动点索引

| 位置 | 行号 | 涉及方案 |
|---|---|---|
| 磨皮 8 邻域采样 | `VRGLRenderer.kt:808-815` | A / C |
| 磨皮中心采样 | `VRGLRenderer.kt:789-793` | A |
| `isSkin` 判定（含 `color`） | `VRGLRenderer.kt:839-844` | A |
| `isSkin` 定义 | `VRGLRenderer.kt:543-548` | A（可选增强） |
| 磨皮半径 `stepF` | `VRGLRenderer.kt:805` | C |
| `uTexelSize` 赋值 | `VRGLRenderer.kt:1305`（内置）/ `1481`（华为） | C |
| GPUPixel 回读区域计算 | `VRGLRenderer.kt:2037-2053` | B |
| `glReadPixels` 调用 | `VRGLRenderer.kt:2062` | B |
| `uploadPendingGpRegion` | `VRGLRenderer.kt:2006-2016` | B（已支持局部贴回） |
| `faceCropScaleX/Y` | `VRGLRenderer.kt:2049-2050` | B（复用） |
| `mapCropXToViewport` | `VRGLRenderer.kt:1873` | B（复用） |
| `mapCropYToViewport` | `VRGLRenderer.kt:1881` | B（复用） |
| 妆容 gate | `VRGLRenderer.kt:851` | D |
| `faceSampleInterval` / `interval` | `VRGLRenderer.kt:2028` | E（可不改） |
| 磨皮离屏 pass 需新增 | 新代码 | C |

---

## 八、风险与陷阱

1. **⚠️ `isSkin` 误判是「失败即退化」而非「失败即出错」** —— 背景被判成皮肤只是白付采样，不会画错。这是方案 A 风险极低的根本原因。
2. **⚠️ 绝不能动 GPUPixel 的 `interval = 1`** —— v2.0.173 的血泪教训：间隔 > 1 帧时全屏会出现「美颜帧 / 原始帧」交替的肉眼可见闪烁。ROI 方案下同样成立。
3. **⚠️ 瘦脸几何形变会超出 ROI** —— 余量必须 ≥ 1.3 倍，否则脸颊边缘硬切割。
4. **⚠️ VR 分屏需对每只眼独立算 ROI** —— 左右眼各一张脸，位置不同。
5. **⚠️ 方案 C 的 `uTexelSize` 不能直接复用** —— 半分辨率 pass 里 texel 尺寸要按半分辨率纹理算，否则磨皮半径会翻倍。
6. **⚠️ 修改前必须备份** —— 按用户长期约定，改 `VRGLRenderer.kt` 前先 `.bak`，并保留到编译验证通过。

---

## 九、结论

- **「只渲染变化区域」在视频美颜场景下的正确形态**是「只在皮肤/人脸区域做重计算」，不是屏幕级的脏矩形（视频每帧全幅都在变）。
- **最值得做且风险最低的两项**：磨皮先判皮肤后采样（纯 shader 重排）+ GPUPixel 回读收窄到人脸 ROI（复用已有基础设施）。
- **开源方案方面**：项目已集成 GPUPixel（最成熟的 C++ 开源美颜库），其内部不可改；其余候选（android-gpuimage-plus / PixelFreeEffects / BeautifyFaceDemo）无一项能直接替换现有实现，**没有「换个库就白赚性能」的选项**。
- **建议先做方案 A，实测收益后再决策后续**。
