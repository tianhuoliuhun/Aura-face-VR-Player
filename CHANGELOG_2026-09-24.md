# 2026-09-24 变更总结（v2.0.164 → v2.0.170）

> 今天共 **7 个版本迭代**，两个主题：**GPUPixel 引擎落地收尾** + **旋转 180° 与悬浮球**。
> 其中 **4 个已发布 Release**：v2.0.165 / v2.0.166 / v2.0.169 / v2.0.170
> （v2.0.164 按当时要求只装本地；v2.0.167 / v2.0.168 是中间尝试，未提交未发布）
>
> ⚠️ **18:30 更新**：其中的「旋转 180°」功能（v2.0.165~170）因三种实现方案均未通过验证，
> 已按用户要求于 **v2.0.171 全部撤回**（按钮、状态、投影矩阵旋转、陀螺仪倒置变换、触摸反向映射、
> 以及 5 语字符串均已移除）；**快进 / 后退悬浮球保留**。

---

## 一、GPUPixel 引擎：从「不生效」到「能用」

### 🎯 核心问题：美颜完全不生效（v2.0.164）

翻 GPUPixel 源码 `RegisterProperty` 才找到根因 —— **我传的 property key 全错**：

| 功能 | 之前传的（错） | 正确 key | 对应 C++ setter |
|---|---|---|---|
| 磨皮 | `blur_alpha` | **`skin_smoothing`** | `BeautyFaceFilter::SetBlurAlpha` |
| 美白 | `white` | **`whiteness`** | `BeautyFaceFilter::SetWhite` |
| 瘦脸 | `thin_face_delta` | **`thin_face`** | `FaceReshapeFilter::SetFaceSlimLevel` |
| 大眼 | `big_eye_delta` | **`big_eye`** | `FaceReshapeFilter::SetEyeZoomLevel` |
| 锐化 | `sharpen` | **未注册 → 删除该滑块** | — |

⚠️ **`SetProperty` 传未注册的 key 不报错、不抛异常，只是什么都不做** ——
所以「美颜完全没效果」应当**优先怀疑 key 名**，而不是怀疑渲染链路。

另外**滤镜链顺序也接反了**：官方文档是 `source → reshape → beauty → sink`，我们接成了 `beauty → reshape`。

### 崩溃修复（v2.0.163）

真机 + 模拟器同栈 SIGSEGV（都在 `MarsFaceDetector::Detect` 内部）：
1. `face_landmarks`（复数）→ **`face_landmark`**（单数，对齐官方 demo）
2. `detect()` 返回空时**不再喂** native（空 `FloatArray` 有风险）
3. 参数顺序摆正（`format(MODE_FMT)` 与 `frameType(FRAME_TYPE)` 曾传反，侥幸两个常量都是 0）

### 取消 ABI 门槛（v2.0.163）

按用户决策：不再按 ABI 拦截，任何设备都可尝试 GPUPixel；初始化失败 → Toast 提示 + 保持 GLSL。

### 多人脸检测（v2.0.164）

- MediaPipe `setNumFaces(1)` → **3**；兜底 `FaceDetector` maxFaces 1 → **3**
- 多张脸里选**两眼距离最大**的作主脸（`FaceResult` 新增 `faceCount` 字段）
- GPUPixel 侧 landmarks **全量透传**（Mars 的 `Detect` 本身返回多个人脸框）

---

## 二、快进 / 后退悬浮球（v2.0.165）

参照加速球样式，**两个独立开关**（默认关）：

- **单击** → seek ±当前步长（**延迟 280ms 执行**，用于与双击区分）
- **双击** → 步长 5 → 10 → 15 → 30 → 5 循环（不执行 seek）
- **长按**（500ms）→ 进入拖动模式，**抬手不触发单击**
- 拖动边界与加速球一致；三球初始位置错开（快进 0.28 / 加速 0.5 / 后退 0.68）
- **开关入口**：设置 →「悬浮球控速与播放倍速」区块，加速球开关正下方
- **层级**：与加速球同在最外层 Box → **恒在播控组件之上**
- 修正玻璃主题底色（补 `isLiquidGlass` 半透明白底分支）

---

## 三、今日踩坑清单（值得留存）

1. **Compose `Modifier.graphicsLayer` 的变换不参与命中测试** —— **参数版与 lambda 版皆然**。
   做「整体旋转/镜像」应使用 **View 层 `View.rotation`**（View 系统保证触摸逆矩阵映射）
2. **SurfaceView 有独立合成层**：Compose graphicsLayer 与 View rotation **都不带动画面**，
   且**它的触摸要单独映射**（本次触摸冲突的根因）
3. **画面软件旋转后，陀螺仪需「设备倒置」等价变换**（`gyro·Rz(180)` + yaw/pitch 取负）
4. **GPUPixel `SetProperty` 传错 key 静默忽略** → 「完全没效果」先查 key 名
5. **GPUPixel 官方文档在 `docs/docs/zh/call/*.md`** —— 接它先看 docs 再看源码
6. **`uiautomator dump` 取 `content-desc` + `bounds`** 是验证 UI 状态/坐标的可靠手段
   （⚠️ dump 慢，须在 UI 显示的 2.5 秒窗口内完成，要与点击压在**同一条设备端命令**里）
7. `adb pull /sdcard/x.png` 在 Git Bash 下路径会被转换 → 用 `exec-out cat //sdcard/x.png`
8. **需求沟通**：本日的两次返工都源于理解偏差 ——
   · "上下翻转" → 我做成镜像，用户要的是旋转 180°（**方向类需求先问一句**）
   · "撤销这个改动" → 我删了功能，用户要的是**修副作用**（**先分清撤功能还是修副作用**）

---

## 四、发布清单

| 版本 | 内容 | 状态 |
|---|---|---|
| v2.0.164 | GPUPixel key 修复 + 多人脸 | 本地（按当时要求未上传） |
| v2.0.165 | 上下反转按钮 + 快进/后退悬浮球 | ✅ Release |
| v2.0.166 | 改为旋转 180° | ✅ Release |
| v2.0.167 / 168 | 触摸方案尝试 | 未发布 |
| v2.0.169 | View 层 rotation | ✅ Release |
| **v2.0.170** | **触摸冲突 + 陀螺仪修复** | ✅ Release |
| **v2.0.171** | **撤除「旋转 180°」功能**（三种方案均未通过验证）；快进/后退悬浮球保留 | ✅ Release |

Release 页：https://github.com/tianhuoliuhun/Aura-face-VR-Player/releases

---

## 五、待验证（真机）

- **v2.0.170**：旋转 180° 状态下 —— 画面拖动方向是否跟手、双击重置视角是否正常、
  陀螺仪方向是否与头部动作一致
- **GPUPixel**：磨皮/瘦脸是否真的生效（key 已按源码修正，但 native 端行为需真机确认）；
  Mars-Face 对上下颠倒回读帧的检测质量；VR 模式（`gpuPixelVrFaceBeauty`）帧率
