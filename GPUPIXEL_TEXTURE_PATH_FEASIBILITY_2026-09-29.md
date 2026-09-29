# fork GPUPixel 加 texture 输入通道 —— 可行性评估

- 日期：2026-09-29
- 起因：v2.0.186 实测发现瓶颈是 GPUPixel 的 GPU 链（19~20 ms），且**全部开销位于 AAR 边界内（CPU 回读/上传/JNI 拷贝）**，调用侧已无优化空间
- 结论：**技术可行，且改动量比预期小**；但**强烈建议先做 PoC，且必须真机验证**
- 性质：评估报告（本轮未改动任何代码）

---

## 1. 结论速览

| 维度         | 判定                                                                                     |
| ---------- | -------------------------------------------------------------------------------------- |
| 技术可行性      | ✅ **可行** —— 三个前置条件全部满足，且上游已有同方向先例                                                      |
| native 改动量 | 🟢 **小** —— 约新增 300 行（2 个新类 + JNI + context 注入点），**不需要重构核心**                           |
| 构建链        | 🟢 **就绪** —— 本机 NDK 30.0.16248370 + CMake 4.1.2 已装；依赖齐全                                |
| 收益         | 🟢 **极大** —— 每帧 CPU 开销约 28 ms → 约 2~4 ms（**↓ 85%**），是 v2.0.186 全部优化之和的数倍               |
| 主要风险       | 🔴 **跨 context 同步正确性** 与 **驱动兼容性** —— 需真机验证，且必须保留回退路径                                  |
| **PoC 状态** | ✅ **已在 MuMu 通过**（2026-09-29）：5194 次跨 context 往返、像素级零失配、零 GL 错误、零弃帧 —— 见 **§2.2**；真机待验证 |
| 建议         | PoC 已过 → 可进入**阶段 1（输出侧 texture）**；华为 VR 通路与真机兼容验证另行安排                                  |

---

## 2. 前置条件核查（全部通过）

| # | 条件              | 核查结果                                                                                                                                                                                                        |
| - | --------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1 | 本机具备 native 构建链 | ✅ NDK **30.0.16248370**、CMake **4.1.2**；**已实测通过** —— 用项目自身的 `:app:externalNativeBuildDebug --rerun-tasks` 强制重跑，`configureCMakeDebug[arm64-v8a]` + `buildCMakeDebug[arm64-v8a]` 均成功（BUILD SUCCESSFUL in 35s） |
| 2 | 依赖能独立构建         | ✅ `third_party/libyuv` **含完整源码**（56 个 .cc/.c）；`stb`/`ghc` 为 header-only；`mars-face-kit` 为**预编译 .so**（arm64-v8a / armeabi-v7a，与现有 AAR 内同源）+ models 齐全                                                        |
| 3 | 上游有无同方向先例       | ✅ **有** —— `SinkSurface`（Android 专用）注释明确写着设计目标：「Avoids copying data from C layer to Java layer / Avoids rendering again in Java layer / **Unified EGL context, better performance**」                        |
| 4 | context 是否有注入点  | ✅ `GPUPixelContext` 已暴露 `GetEglContext()/GetEglDisplay()/GetEglSurface()/GetEglConfig()`（Android 分支）                                                                                                        |
| 5 | 链接库是否支持 ES3     | ✅ Android 侧 `target_link_libraries` 已是 **GLESv3**（ES3 是 ES2 超集，`CreateContext` 里 `EGL_CONTEXT_CLIENT_VERSION` 从 2 改 3 只是改一个数字）                                                                              |

> **重要**：条件 3 意味着这个方向是**上游认可的路线**，因此可以把改造以 **PR 形式提交上游**（而非永久 fork），显著降低维护成本 —— 见第 7 节。

### 2.1 ⚠️ 已验证的构建路径 + 一个必须避开的坑

**将来集成 GPUPixel 源码就走项目已有的这条路径**（`app/build.gradle.kts:88-105`）：

```kotlin
externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
ndkVersion = "30.0.16248370"
ndk { abiFilters += listOf("arm64-v8a") }
```

即：把 GPUPixel 源码作为 CMake 子目录加入现有 `src/main/cpp/CMakeLists.txt`，  
即可与华为 VR 的 `libauravr.so` 一起产出 —— **无需自建独立构建脚本**，也无需手工替换 AAR 内的 `.so`。

> ⚠️ **环境坑（本轮实测踩到，务必记住）**：  
> **不要从 Git Bash 直接调用 NDK 的 `clang.exe`**（也不要从 Git Bash 调 `cmake` 做编译器探测）——  
> 会崩溃在 `Exception Code: 0x00000005`，崩溃栈全是网络组件  
> （`RPCRT4.dll` / `wkscli.dll` / `ntlanman.dll` / `MPR.dll`），**连 hello world 都编译不了**。  
> 根因是 Git Bash 的 POSIX 环境使 clang 去解析 UNC 路径（环境里有 `LOGONSERVER=\\DESKTOP-UGD6TD4`）。  
> **沙箱外同样复现**，故与沙箱无关。  
> ✅ 正确做法：走 **gradle 的 `externalNativeBuild`**（AGP 用原生 Windows 环境 spawn clang），  
> 或从 cmd/PowerShell 调用。已用 `--rerun-tasks` 强制重跑验证通过。

### 2.2 ✅ PoC 实测结果：共享 context + fence 同步 **已验证可行**（2026-09-29）

在 MuMu（x86_64 + houdini 转译）上跑通 PoC（`com.example.vr.poc.PocSharedContextActivity`：  
2 个共享 EGLContext + 512×512 双 texture + fence 往返 + **像素级校验**）：

```
contexts created: root=EGLContextImpl@... render=EGLContextImpl@... (share_context=root)
secondary EGL current: ver=OpenGL ES 3.2 v334 R
shared visibility (from secondary ctx): isTexture(texA)=true isTexture(texB)=true
                                        isFramebuffer(fboA)=true isFramebuffer(fboB)=true
secondary re-attach fboB: status=0x8cd5 COMPLETE
STAT 主帧=5198  往返=5194  第二ctx帧=5195  绘制成功=5195  失配=0  GL错误=0  超时=0  放弃=0
```

**判定通过**：5194 次跨 context 往返、**像素级零失配**、零 GL 错误、零弃帧。

#### 校验方法（可量化，不靠肉眼）

主 context 把 `texA` 清成灰度 `((帧号 × 8) & 0xFF) / 255` → 第二 context 等 fence 后**直通复制**到  
`texB` → 主 context 等 fence 后**读回 texB 中心像素**与期望值比对。  
帧号步长取 8 ⇒ 任何同步错误（读到旧帧 / 未写完的 texture）都会产生 **8 的整数倍偏差**，  
与浮点精度误差（±1）清晰可分 —— 所以「失配=0」是**真实的正确性证据**，不是"看着没花屏"。

#### ⚠️ PoC 挖出的三个关键实现细节（直接影响生产实施，务必带上）

1. **🔴 必须在「使用方 context」里重新 attach FBO**。  
   首轮 PoC **每次都报** `GL_INVALID_FRAMEBUFFER_OPERATION (0x506)`（共 4053 次），texB 读回恒为 0。  
   原因：虽然 **FBO 确实是共享对象**（`glIsFramebuffer` 返回 true），但**附件与完整性状态必须在  
   使用它的 context 里重新建立** —— 在第二 context 里再执行一次  
   `glFramebufferTexture2D(GL_COLOR_ATTACHMENT0, texB)` 之后，`glCheckFramebufferStatus` 变为  
   `COMPLETE`，错误**全部消失**。**这一条若事先不知道，实施时必然长时间卡住。**
2. **跨 context 等待不要带 `GL_SYNC_FLUSH_COMMANDS_BIT`**。该位的语义是「等待前 flush 本 context  
   的命令队列」，而这里等的是**另一个 context 创建的 sync**，flush 自己毫无帮助。
3. **`WAIT_FAILED` 会偶发（约 1%），但重试即恢复**。实测 56 次 WAIT_FAILED，加上「最多重试 3 次」  
   后 **放弃=0**（零丢帧）。生产实现**必须**带重试；即便偶尔丢帧，也可由 v2.0.185 的保底帧机制兜底。

#### 仍然待验证的项（不要在 PoC 通过后跳过）

- **真机**：MuMu 通过 ≠ 真机通过，尤其**华为 VR 通路的 native EGL**（`aura_vr_session.cpp` 自建 context）。
- **性能**：PoC 是**串行同步往返**（主线程阻塞等第二线程，平均 16 ms），**不代表生产性能**。  
  生产用双/三缓冲 + 非阻塞，PoC 只回答「正确性」这一个问题。
- PoC 代码（`app/src/main/java/com/example/vr/poc/` + Manifest 里的 Activity 条目）是**临时验证资产**，  
  结论落定后可整体删除。

---

## 3. 技术路径（含关键源码依据）

### 3.1 为什么必须共享 EGLContext

GPUPixel 在 Android 上**自建 ES2 独立 context**（`gpupixel_context.cc:164`，`eglCreateContext(..., EGL_NO_CONTEXT, ...)`），与主渲染的 ES3 context **不共享命名空间** → 纹理 id 互不可见 → 每帧必须「回读成字节 → 再上传」跨 context 搬运两次。

> ⚠️ **已排除的替代方案：让主渲染直接用 GPUPixel 的 context（单一 context）**。  
> 看似最省（同一 context 内操作天然有序，无需任何同步），但实际上**不可行**：  
> EGL 规定一个 context 同一时刻只能在一个线程 current。主渲染在 GL 线程、GPUPixel 在它的  
> DispatchQueue 线程，两线程交替 makeCurrent 同一 context 会产生抢占/失败，且 GL 对象非线程安全。  
> 唯一的规避方式是把 GPUPixel 处理也挪到 GL 线程 —— 那就回到同步阻塞、且彻底失去并行。  
> **所以必须「两个 context + share_context」。**

### 3.2 共享模型：应用层建「共享根 context」

```
        ┌─────────────────────────────┐
        │  root context（应用层创建）   │  ← eglCreateContext(display, config, EGL_NO_CONTEXT)
        └──────────┬──────────────────┘
                   │ 作为 share_context 传入
        ┌──────────┴──────────┐
        ▼                     ▼
  主渲染 context          GPUPixel context
  (GLSurfaceView)        (native 自建 pbuffer)
        └──── 互相可见对方创建的 texture ────┘
```

**为什么用「根 context」而不是让一方共享另一方**：避免初始化顺序耦合（GLSurfaceView 创建 context 时 GPUPixel 可能尚未初始化）。

### 3.3 改动面（关键：改动量集中在「新增」而非「修改」）

| # | 层面                             | 具体改动                                                                                                                                                                                                                                                                                    | 量级          | 难度                          |
| - | ------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------- | --------------------------- |
| 1 | `SourceTexture`（新增类）           | 吃外部 `texId`，用自己的直通 program 画进内部 framebuffer。**依据**：`GPUPixelFramebuffer` 构造只有 `(width, height)`（`gpupixel_framebuffer.h:27`），内部 `GenerateTexture()` 自建 → **无法包装已有 texture**，故需新类；但可直接照搬 `SourceRawData::GenerateTextureWithPixels`（`source_raw_data.cc:97-147`）的绘制部分，只把「上传像素」换成「绑定外部纹理」 | ~80 行       | 🟢 低                        |
| 2 | `SinkTexture`（新增类）             | 结果保留在自有 framebuffer，暴露 texture id。**依据**：`SinkRawData::Render()`（`sink_raw_data.cc:69-119`）**本来就已经把结果渲染进自己的 `framebuffer_`**，`RenderToOutput()`（`:134-143`）才是多做的一次 `glReadPixels` → **只需删掉读回、加一个 `GetTextureId()`**                                                                     | ~70 行       | 🟢 低                        |
| 3 | `GPUPixelContext` 注入共享 context | `CreateContext()` 的 `eglCreateContext` 第 3 个参数由 `EGL_NO_CONTEXT` 改为外部传入；新增 setter                                                                                                                                                                                                       | ~15 行       | 🟢 低                        |
| 4 | JNI                            | `jni_source_texture.cc` + `jni_sink_texture.cc`（照 `jni_source_raw_data.cc` / `jni_sink_raw_data.cc` 裁剪，后者可删掉 `nativeGetRgbaBuffer` 的大段拷贝逻辑）                                                                                                                                             | ~80 行       | 🟢 低                        |
| 5 | Java 包装                        | `GPUPixelSourceTexture` / `GPUPixelSinkTexture`                                                                                                                                                                                                                                         | ~100 行      | 🟢 低                        |
| 6 | 构建链                            | `externalNativeBuild`（CMake）+ 把 `libgpupixel.so` 由「引用 AAR」改为「本工程编译产物」；ABI 保持 arm64-v8a + armeabi-v7a                                                                                                                                                                                    | 配置为主        | 🟡 中                        |
| 7 | **应用侧：4 处 context 接入共享根**      | `VRGLSurfaceView.kt:43`（`setEGLContextClientVersion(3)` 处）、`HuaweiVrActivity.kt:158`、`aura_vr_session.cpp:613`（native OpenXR，`EGL_NO_CONTEXT`）、GPUPixel 自身                                                                                                                              | 每处约 20~40 行 | 🔴 **高**（华为通路是 native，回归面大） |
| 8 | **应用侧：gpFbo 多缓冲 + fence 同步**   | 见 3.4                                                                                                                                                                                                                                                                                   | 约 150 行     | 🔴 **高**                    |
| 9 | 保留 raw-data 回退                 | `gpUseTexturePath` 开关，两条通路并存                                                                                                                                                                                                                                                            | 已有架构可复用     | 🟡 中                        |

**精确落点**（照 `src/CMakeLists.txt` 的现有组织方式登记即可）：

```
src/source/source_texture.cc          + include/gpupixel/source/source_texture.h   （新）
src/sink/sink_texture.cc              + include/gpupixel/sink/sink_texture.h       （新）
src/android/jni/jni_source_texture.cc   （新）
src/android/jni/jni_sink_texture.cc     （新）
src/android/java/gpupixel/src/main/java/com/pixpark/gpupixel/GPUPixelSourceTexture.java  （新）
src/android/java/gpupixel/src/main/java/com/pixpark/gpupixel/GPUPixelSinkTexture.java    （新）

src/CMakeLists.txt
  · common_source_files  += source/source_texture.cc, sink/sink_texture.cc
  · jni_source_files     += android/jni/jni_source_texture.cc, jni_sink_texture.cc

src/core/gpupixel_context.h/.cc        （改：CreateContext 支持注入共享 context）
src/android/jni/jni_gpupixel.cc        （改：新增 nativeSetSharedContext）
```

> 附带发现的一处上游小瑕疵（**不影响我们，但改的时候顺手可以修**）：  
> `src/CMakeLists.txt:30-32` 用 `#if defined(GPUPIXEL_ANDROID)` / `#endif` 包住 `sink/sink_surface.cc`  
> —— CMake **不做 C 预处理**，这两行是无效的，该文件其实**无条件**被加入编译列表。  
> 之所以至今没出问题，是因为 `sink_surface.cc` 内部有  
> `#if defined(__ANDROID__) || defined(GPUPIXEL_ANDROID)` 保护，桌面构建时该文件编为空单元。  
> 我们新增的 `source_texture.cc` / `sink_texture.cc` 是跨平台通用实现，**不存在这个问题**。

### 3.4 ⚠️ 真正的难点：跨 context 同步（不是「加个类」那么简单）

即使纹理互通，**两个 context 的读写必须有序**，否则出现撕裂 / 花屏 / 随机错帧（且难复现）：

**问题 1：主渲染正在重绘 gpFbo，GPUPixel 同时在读它。**  
当前 `gpFbo` 是**单张**、每帧清空重绘（`onDrawFrame` → `ensureGpFbo(displayWidth, displayHeight)`）。共享后必须改为 **2~3 张轮转（三缓冲最稳）**，并配 fence：

```
帧 N:   主渲染写入 gpFbo[A] → 插入 fence → GPUPixel 等 fence 后读取
帧 N+1: 主渲染写入 gpFbo[B]（A 仍可能被 GPUPixel 读）
```

**问题 2：GPUPixel 输出 texture 正被主渲染采样，下一轮 GPUPixel 又要覆盖它。**  
同理需要 GPUPixel 侧输出 **2~3 张轮转**，主渲染采样完插入 fence 通知 GPUPixel 可复用。

**问题 3：fence 的对象（GLsync）本身跨 context 可用**（`EGL_KHR_fence_sync` / ES3 核心），但必须在**产生它的 context** 里创建、在**使用方 context** 里等待 —— 这是正确但容易写错的部分。

> 这部分是本项目**从未有过的新架构**（现有代码里所有 GL 操作都在单一 context 内，`glClientWaitSync` 只用于同 context 的 PBO）。必须当作独立技术点验证。

---

## 4. 收益量化（基于 v2.0.186 实测外推）

当前实测（MuMu 1080p，downscale=1，各 1200+ 帧）：`gpu=19~20 ms`、`rb=8~9 ms`。

| 开销段                                               | 现状            | texture 通路后                       | 省                       |
| ------------------------------------------------- | ------------- | --------------------------------- | ----------------------- |
| 输入上传 8.29 MB（`glTexImage2D` + Source pass）        | ~~2~~4 ms     | ~0（一次 texture→texture 直通，<0.5 ms） | ~~2~~4 ms               |
| GPUPixel 内部 9 个全屏 pass                            | ~~6~~10 ms    | 不变（GPU 侧本来就该做）                    | 0                       |
| sink 回读 8.29 MB（`glReadPixels`）                   | ~~3~~5 ms     | **0**                             | ~~3~~5 ms               |
| JNI `NewByteArray` + `SetByteArrayRegion` 8.29 MB | ~~2~~3 ms     | **0**                             | ~~2~~3 ms               |
| 输入 `GetByteArrayElements` 8.29 MB                 | ~~1~~2 ms     | **0**                             | ~~1~~2 ms               |
| GL 线程 `rb`（`glMapBufferRange` + memcpy 8.29 MB）   | **8~9 ms**    | **0**（直接采样共享 texture）             | 8~9 ms                  |
| 结果贴回（`glTexSubImage2D` + 缩放 blit）                 | ~~1~~2 ms     | **0**（主渲染直接 blit 共享 texture）      | ~~1~~2 ms               |
| **合计**                                            | **≈ 28 ms/帧** | **≈ 2~4 ms**（仅提交命令 + fence 等待）    | **≈ 24~26 ms（↓ 约 85%）** |

**附带收益**：

- 内存流量从约 **5 × 8.29 MB/帧 → ≈ 0**（对真机发热/带宽收益显著）
- 不再需要靠降采样换性能 → 真机可稳定跑全分辨率 1:1，**画面更清晰**（当前自适应降档本质是用清晰度换流畅度）
- 消除每帧 8.29 MB 的 Java 大对象分配（LOS churn）

---

## 5. 风险分级

| 级别   | 风险                    | 说明与缓解                                                                                                                 |
| ---- | --------------------- | --------------------------------------------------------------------------------------------------------------------- |
| 🔴 高 | **跨 context 同步正确性**   | 三缓冲 + 双端 fence。搞错 = 撕裂/花屏/偶发错帧且难复现。**建议独立 PoC 验证后再动生产代码**                                                             |
| 🔴 高 | **驱动兼容性**（共享 context） | ES2/ES3 跨版本共享在部分老驱动上有坑 → 建&#x8BAE;**&#x20;GPUPixel context 一并升到 ES3**（链接库已是 GLESv3）；**必须保留 raw-data 回退路径**，运行时探测失败即降级 |
| 🔴 高 | **模拟器无法验证**           | MuMu 是 x86_64 跑 houdini 转译 arm64；共享 context + fence 在转译层行为未知。**必须真机验证** → 需要用户配合多轮真机测试（功能验证由用户做的既有分工）                 |
| 🟡 中 | **华为 VR 通路**          | `aura_vr_session.cpp:613` 是 native OpenXR 自建 context，接共享根要动 C++，且该通路的回归成本高（只在真机 VR Glass 上能测）                         |
| 🟡 中 | **fork 维护成本**         | 上游更新需 rebase。**缓解**：改造尽量「只新增文件 + 极少 if 分支」，并考虑向上游提 PR（见第 7 节）                                                         |
| 🟢 低 | 构建链                   | **已实测通过**：NDK/CMake 可用（`:app:externalNativeBuildDebug --rerun-tasks` 编译成功）；依赖齐全；AAR 里的 `libmars-face-kit.so` 可直接复用    |
| 🟢 低 | CMake 版本兼容性           | **已核查**：顶层要求 3.16、libyuv 要求 3.10，均 ≥ 3.5（CMake 4.x 的下限）；`glfw`(3.0) 只在桌面平台被 `add_subdirectory`，Android 不会拉入           |
| 🟢 低 | native 改动量            | 约 300 行新增，核心类（Filter/Framebuffer/Context 渲染逻辑）**不需要重构**                                                               |
| 🟢 低 | 精度影响                  | texture 通路是**像素等价**的（只是不经 CPU），磨皮/美白/瘦脸/大眼效果完全一致                                                                      |


## 7. 减少维护成本的建议：向上游提 PR

因为上游**已有 `SinkSurface` 这个同方向先例**（Android 零拷贝输出），`SourceTexture` / `SinkTexture` 与「共享 context 注入」都属于**通用能力**，而非本项目特有需求。因此：

1. 以**独立 PR** 形式向上游提交（`pixpark/gpupixel`），争取让 `SourceTexture`/`SinkTexture`/context 注入进入主线
2. 若被接受 → 本项目回到「消费官方 AAR」的形态，**彻底消除 fork 维护成本**（这是最理想结局）
3. 若被拒 → 维护本地 patch（建议保留为 `.patch` 文件 + 构建脚本自动套用，而非改名成 `gpupixel-fork` 目录，方便后续跟随上游）

---

## 8. 明确边界（不做 / 暂不做）

| 项                                                     | 判定     | 理由                                                                                                                                                 |
| ----------------------------------------------------- | ------ | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| 单一 context（主渲染直接用 GPUPixel 的 context）                 | ❌ 不可行  | EGL 同一 context 不能跨线程 current，会失去并行                                                                                                                 |
| 用 `EGLImage` + `GL_TEXTURE_EXTERNAL_OES` 替代共享 context | ⚠️ 不推荐 | 需要 `EGL_KHR_gl_texture_2d_image`（非核心扩展），且 GPUPixel 的 shader 用 `sampler2D`，需额外适配；复杂度不低于共享 context 而收益相同                                             |
| 库内 shader 优化                                          | ❌ 不可行  | 9 个 pass 的 GLSL 在预编译 .so 内且属上游资产；LP 也不该改                                                                                                           |
| 仅靠「降采样档位更激进」换取性能                                      | ⚠️ 已评估 | 零风险，收益约 60%（downscale 2 → 数据量 1/4，`gpu` 20→约 7 ms、`rb` 9→约 3 ms），**代价是画面变软**；当前 MuMu 实测稳定在 1 档（不降档），仅在真机发热/低端机场景才有意义 —— **可作为不做 texture 通路时的临时手段** |
| 在未做 PoC 前直接改生产代码                                      | ❌ 不建议  | 风险集中在「跨 context 同步 + 驱动兼容」，未知项必须先用最小代价证伪                                                                                                           |

---

## 9. 一句话总结

**能省掉约 85% 的每帧 CPU 开销（28 ms → 2~4 ms）；native 改动只有约 300 行新增，构建链已实测通过（NDK 30 + 项目现有的 `externalNativeBuild` 路径，无需另建构建脚本），上游还有同方向的 `SinkSurface` 先例；真正的难点不在「加两个类」，而在「跨 context 的三缓冲 + fence 同步」和「驱动兼容性」—— 这两点无法在模拟器上确认，必须先做真机 PoC。**

### 待确认事项（需你决定）

1. ~~是否先做 PoC~~ → ✅ **PoC 已做且通过**（见 §2.2）。当前待决：**是否推进到阶段 1（输出侧 `SinkTexture`）**
2. **PoC 代码是否保留** —— `com.example.vr.poc` 包 + Manifest 条目属临时验证资产，也可删除
3. **是否尝试向上游提 PR** —— 若上游接受，本项目可回到「消费官方 AAR」形态，彻底消除 fork 维护成本
4. **真机测试的配合** —— 按既有分工「功能验证由你自己做」，本改造**必须**在真机上多轮验证（模拟器无法确认共享 context 稳定性）
5. **华为 VR 通路是否本期纳入** —— 建议前两阶段在普通通路验证充分后再做（该通路只能在真机 VR Glass 上测，回归成本高）

---

## 11. 阶段 1 实测结果：输出侧 texture 通道（v2.0.187，已完成）

### 实施内容（全部在 fork 中开发，未向上游提交任何 PR）

**fork 侧**（`tianhuoliuhun/gpupixel`，commit `bc65380`）：
- `GPUPixelContext::SetSharedEglContext(...)` + `CaptureCurrentEglContextAsShare()`
  （后者在**有 current context 的线程**直接捕获，Android 侧无需反射读私有字段）
- `CreateContext()` 优先共享、失败自动回退私有 context；共享路径用 ES3
- `ReleaseContext()` 共享模式下**跳过 `eglTerminate`**（display 归外部所有）
- **`SinkTexture`**：结果留在 GPU、暴露 texture id；**双缓冲**轮转 + 可选**消费端 fence**
- JNI（`jni_sink_texture.cc`）+ `GPUPixelSinkTexture.java`

**项目侧**：
- fork 作为 **git submodule**（`third_party/gpupixel`），源码级集成（`add_subdirectory`）；
  `libmars-face-kit.so` 由 jniLibs 源目录挂载
- **移除 `libs/gpupixel-release.aar`** 依赖，Java 类改由 submodule 源码提供（AAR 文件保留在磁盘以便一键回退）
- GL 线程 `onSurfaceCreated` 调 `GPUPixel.captureSharedEglContext()`
- **双通道**：texture 优先 + raw-data 自动回退 —— GL 线程用 `glIsTexture` 做**权威校验**
  （不可见即说明共享未生效，自动切回 CPU 回读，功能不受影响）

### 实测（MuMu 1080p，各 1200+ 帧）

| 指标 | v2.0.186（raw-data） | v2.0.187（阶段 1） | 变化 |
|---|---|---|---|
| `gpu`（faceExecutor 处理段） | 19~20 ms | **4~5 ms** | **↓ 约 75%** |
| `rb`（GL 线程回读） | 8~9 ms | 8~9 ms | 不变（属**输入侧**） |
| **合计 CPU/帧** | ≈ 28 ms | **≈ 13 ms** | **↓ 约 55%** |
| `detect` | 1 ms | 0~1 ms | 不变 |

关键日志（证据链）：
```
GPUPixel shared EGL context registered: true
pipeline -> SinkTexture (GPU-side output, zero-copy)
GPUPixel texture path: glIsTexture(19)=true (main context visibility)
GP profile(120f): detect=0 ms, gpu=4 ms, rb=8 ms, pboWait=0/120, downscale=1, pbo=on
```

回归：无崩溃、无 `GL_INVALID`、无回退日志、自适应稳定 1 档；截图确认画面正常
（非黑屏/花屏，中心区域色彩丰富）。

**对照预测**：原预测阶段 1 省 7~10 ms → **实测省约 15 ms**，优于预期 ——
因为输出侧同时消除了「sink `glReadPixels` + JNI 拷贝 + 结果贴回」三段开销。

### 阶段 1 **未**消除的部分（都属输入侧，即阶段 2 的范围）

| 未消除项 | 量 | 位置 |
|---|---|---|
| 主渲染 `gpFbo` → CPU 回读 | `rb` 8~9 ms（8.29 MB） | GL 线程 |
| `ProcessData` 像素上传 | ~2~4 ms（8.29 MB） | `gpu` 段内 |
| 检测线程的输入数组 | 8.29 MB 一次分配 | 同回读 |

→ 阶段 2（`SourceTexture`）若完成，预计 `rb` 归零、`gpu` 再降至 2~3 ms，**合计约 4~5 ms/帧**。

### 未验证项（本阶段明确不做）

- **真机**：MuMu 通过 ≠ 真机通过（尤其华为 VR 通路的 native EGL）
- **功能观感**：通道与画面连通性已确认，但**磨皮/美白/美型的视觉效果需人工确认**

