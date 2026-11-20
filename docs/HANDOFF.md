# 交接文档 · Aura Lens（GfaceVRplayer）

> 给接手的开发者 / AI 的第一份文档。**先读第 0、5、6 节**，其余按需查。
> 最后更新：2026-11-20（对应 v2.8.3 / versionCode 348）

---

## 0. 一句话现状

Android VR/AV 播放器，主线功能完备、每版正常发版，**当前无已知崩溃**。
遗留的是三个「非阻断但真实」的问题（见第 7 节），以及一份 9812 行巨型文件的拆分技术债。

---

## 1. 项目是什么

| 项 | 值 |
|---|---|
| 应用名 | Aura Lens / Aura-face-VR-Player |
| 包名 | `com.aistudio.vrplayer.vrmjpy` |
| 仓库 | https://github.com/tianhuoliuhun/Aura-face-VR-Player |
| 当前版本 | **v2.8.3（versionCode 348）** |
| 最低 / 目标 SDK | 26 / 36 |
| 语言 | Kotlin 2.2.10（Compose）+ C++（JNI） |
| 规模 | 125 个 `.kt`（约 56k 行）、39 个 `.cpp/.h`（约 5k 行） |

核心能力：4 个解码内核（EXO / MPV / IJK / 系统）可切、VR 双屏渲染、美颜双引擎（GLSL / GPUPixel）、
实时 ASR 字幕 + 翻译、AI 弹幕、端侧 LLM、网页直链嗅探、华为 VR Glass、液态玻璃 UI。

---

## 2. 环境与构建（照抄即可）

### 2.1 版本矩阵

| 组件 | 版本 | 备注 |
|---|---|---|
| Gradle | **9.6.1**（系统安装） | 🔴 **项目没有 `gradlew`**，必须用绝对路径调 |
| JDK | **21**（Eclipse Adoptium） | `JAVA_HOME` 必须显式指定 |
| AGP | 9.1.1 | |
| NDK | 30.0.16248370 | |
| compileSdk | 36（minor 1） | |

### 2.2 命令

```bash
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot"
GRADLE="C:/Users/lufan/.gradle/dist/gradle-9.6.1/bin/gradle.bat"

# 快速语法校验（约 1~2 分钟）
"$GRADLE" :app:compileAllDebugKotlin --console=plain

# 单元测试
"$GRADLE" :app:testAllDebugUnitTest --console=plain

# debug 包（自测用）
"$GRADLE" clean :app:assembleAllDebug --console=plain

# 🔴 release 包（发版用）—— -x lintVitalAllRelease 不可省
"$GRADLE" clean :app:assembleAllRelease -x lintVitalAllRelease --console=plain
```

耗时参考：debug 全量 clean 约 2 分钟，release（含 R8）约 **3~4 分钟**。

### 2.3 flavor

4 个：`standard` / `pico` / `quest` / `all`。**主线只发 `all`**，其余是华为 VR 专用分支的遗留。

### 2.4 产物校验（每次必做）

```bash
# release 期望：约 83 MB / 2 dex / 30 so / 仅 arm64-v8a
python -c "
import zipfile,os
p='app/build/outputs/apk/all/release/Aura-Lens-v2.8.3-all.apk'
z=zipfile.ZipFile(p); n=z.namelist()
dex=[x for x in n if x.endswith('.dex')]; so=[x for x in n if x.endswith('.so')]
sz=os.path.getsize(p)
print(f'{sz} B / {sz/1048576:.2f} MB / dex={len(dex)} / so={len(so)}')
print('ABI:', sorted({x.split('/')[1] for x in so}))
"
```

16 KB 页对齐（Android 15+ 强制，**装机会拦**）：

```bash
# 先把 so 解包到临时目录，再逐个 check；脚本只支持 ELF64，所以只校验 arm64
python scripts/elf16k_align.py --check <解包后的所有 .so>
```

---

## 3. 目录与文件地图

```
app/src/main/
  java/com/example/vr/     ← 全部业务代码（125 个 kt）
  cpp/                     ← JNI：aura_vr_session / ffremux / llama_mtmd / aura_vr_input
  res/                     ← 5 语言：values / -en / -zh-rTW / -ja / -ko
  assets/                  ← 🔴 模型权重不入库，首启下载
scripts/elf16k_align.py    ← 16 KB 对齐工具
docs/                      ← CHANGELOG、架构、专题方案
mpv-libs/ third_party/     ← 已入库
```

### 需要认识的 8 个文件

| 文件 | 行数 | 作用 | 警告 |
|---|---|---|---|
| `VRPlayerScreen.kt` | **9812** | 播放器主界面：状态、控件、菜单、几乎所有交互 | 🔴 巨型文件，改前先 grep 定位，别通读 |
| `VRGLRenderer.kt` | **5975** | GL 渲染：VR 双屏、美颜管线、字幕叠加 | 🔴 同理 |
| `SubtitleTranslator.kt` | 3198 | 翻译调度（云端 / 端侧 LLM / 缓存） | |
| `WebSnifferScreen.kt` | 687 | 网页嗅探面板、码率选择、字幕候选 | 近期改动集中地 |
| `HttpPlaybackHeaders.kt` | 163 | 网页播放的 UA / Referer / **Cookie** 单一出口 | 进程级单例，改要谨慎 |
| `FfmpegRemuxer.kt` + `cpp/ffremux_jni.cpp` | 513 | 网络流 → MKV（实时字幕用） | JNI 参数顺序，改一处两边都要改 |
| `WebSubtitleSniffer.kt` | 393 | 网页字幕抓取 + HLS 分片合并 | |
| `HlsPlaylist.kt` / `StreamProbe.kt` | 287 / 214 | 纯逻辑解析 / 手动跟 302 | **有单测**，改完必须跑 |

---

## 4. 发版流程（硬规则）

```bash
# 1. 版本号：app/build.gradle.kts 的 versionCode +1、versionName 递增
# 2. 更新 docs/CHANGELOG.md（表格里加一行，中英对照）
# 3. clean 构建 release，按 2.4 校验
# 4. 提交 + 打 tag + 推送
git add -A && git commit -F - <<'EOF'
release(vX.Y.Z): 一句话摘要

详细说明...
EOF
git tag -a vX.Y.Z -m "vX.Y.Z 摘要"
git push origin main && git push origin vX.Y.Z

# 5. 建 Release，只上传 release APK
gh release create vX.Y.Z "app/build/outputs/apk/all/release/Aura-Lens-vX.Y.Z-all.apk" \
  --title "vX.Y.Z 标题" --notes-file <写好的说明.md>
```

**四条铁律：**

1. **只上传 release 包，绝不附带 debug。**
2. 每个版本**默认发 Release，无需再问**。
3. 附件命名必须是 `Aura-Lens-v<versionName>-all.apk`。
4. Release 说明写**已知边界**，不要把「未验证」写成「已支持」。

---

## 5. 验证与装机

功能验证由**用户**做；你只负责改代码 → 编译 → 装机 → 说清要看什么。
但**定位崩溃**必须自己抓日志（这属于开发排查，不是验收）。

```bash
# MuMu 模拟器（Android 15 / API 35），adb 地址 127.0.0.1:16384
adb connect 127.0.0.1:16384
adb -s 127.0.0.1:16384 install -r app/build/outputs/apk/all/release/Aura-Lens-v2.8.3-all.apk
adb -s 127.0.0.1:16384 shell am start -n com.aistudio.vrplayer.vrmjpy/com.example.MainActivity
adb -s 127.0.0.1:16384 logcat -d | grep -E "FATAL|AndroidRuntime"
```

**判定「升级 vs 重装」的唯一硬判据是 `firstInstallTime`**：
`-r` 替换安装不改它；先卸载再装一定会变。变了一律视为数据丢失（本地 LLM 权重会被删，要重下 1.5 GB）。

---

## 6. 🔴 接手前必须知道的坑（全部踩过）

| # | 坑 | 现象 | 对策 |
|---|---|---|---|
| 1 | **增量构建产出残缺包** | 1.8 MB / 1 dex / 0 so，装上去闪退 | **一律 `clean`**；每次装机前跑 2.4 的校验 |
| 2 | 忘了 `-x lintVitalAllRelease` | release 构建因 lint 失败中断 | 命令照抄 2.2 |
| 3 | debug 与 release **共用 release 签名** | 若改回真 debug 签名，两包无法互相覆盖，卸载会删模型 | **不要改** `debug { signingConfig = release }` |
| 4 | R8 混淆裁坏 JNI | release 包一调 native 就 `UnsatisfiedLinkError` | JNI 走标准命名导出，`-keepclasseswith**membernames**` 不能删 |
| 5 | 16 KB 页对齐 | 装 release 弹「不兼容 16 KB 对齐」 | 见 2.4；脚本只支持 ELF64，只校验 arm64 |
| 6 | **构建期间 MuMu 会被系统回收** | 装机报 `cannot connect to 127.0.0.1:16384` | 装机前 `adb devices` 确认；掉了就重开模拟器 |
| 7 | Compose 状态被同帧清空 | 字幕「选中了但进播放器没有」 | 用 `pendingXxx` 暂存，由 `LaunchedEffect` 优先消费 |
| 8 | MPV 的 `http-header-fields` 带了 Range | 拖动进度条永远只拿到文件头 | MPV 只给 UA / Referer / Cookie，**绝不给 Range** |
| 9 | `path` 里没有 `gradlew` | `./gradlew` 报找不到 | 用 2.2 的绝对路径 |
| 10 | Git Bash 的 `/sdcard/...` 被转成 Windows 路径 | adb shell 行为诡异 | `export MSYS_NO_PATHCONV=1` |

---

## 7. 当前待办（按优先级，含修复思路）

### P0 · `av_log` 仍是 DEBUG 级，正式包会泄露 URL token

- 位置：`app/src/main/cpp/ffremux_jni.cpp:64` `av_log_set_level(AV_LOG_DEBUG)`
- 影响：签过名的直链 URL、请求头（含 Cookie）**会打进 logcat**，任何拿到设备日志的人都能看到
- 修法：改成 `AV_LOG_WARNING`；诊断时用临时 patch，别提交

### P1 · Cookie 只在点击播放时取一次，中途过期就断

- 位置：`WebSnifferScreen.kt` 的 `headersFor()` → 写入 `HttpPlaybackHeaders` → 之后不再更新
- 现象：HLS 长播放中途 403，**和「CDN 拒绝」在日志里无法区分**，用户只觉得「播到一半卡死」
- 修法：在 EXO `PlayerError` / MPV `end-file` 里判断是否 403/410，若是则重新 `CookieManager.getCookie()` 并重试一次

### P2 · 未实测的路径（有代码、没跑过）

| 项 | 现状 | 怎么验 |
|---|---|---|
| AES-128 加密 HLS | `protocol_whitelist` 已含 `crypto`，key 请求也会走 `io_open` 补 Cookie | 找一条加密 HLS 流跑实时字幕 |
| 带 Cookie 的 FFmpeg 重封装 | JNI 参数顺序已逐项核对，未实跑 | 登录态站点的网页流开实时字幕 |
| 多档清晰度选择器 UI | 只在代码层 | 找个多码率 HLS 站点点一条流 |

### P3 · 已知设计边界（不是 bug，不要当 bug 修）

- **直播实时字幕不支持**：`AudioTee` 走 `MediaExtractor`，HLS 多分片取不到音频 → 已有明确提示
- 多语言字幕需**手动选一条**，不支持双字幕同屏
- 单字幕文件上限 4 MB、单分片 2 MB

### P4 · 技术债

- `VRPlayerScreen.kt` 9812 行、`VRGLRenderer.kt` 5975 行 → 拆分时**先备份再动**，一次只搬一个功能域
- `docs/` 下有 20+ 份历史专题文档，部分结论已过时，以 `CHANGELOG.md` 为准

---

## 8. 工作约定（与原负责人协作的方式）

1. **回复与思考都用简体中文**（技术术语、API 名、命令保留英文）。
2. **改任何已存在文件前先备份**到 `.workbuddy/tmp/bak/`，命名 `<原名>.<yyyyMMdd-HHmmss>.bak`，并把路径告诉对方。
3. **不擅自重新设计需求**：「选 X 的 / 只留 X / 还是原来的」= 指定既有选项，改现存内容默认最小改动。
4. **同一功能若有两份实现，先 grep 全部副本，收敛成一处**再改。
5. **观感类问题不下结论**（"是不是更清楚了""颜色对不对"），交给用户看。
6. **不在仓库里留临时文件**（`git status` 保持干净）。

---

## 9. 交接资产清单（⚠️ 不在仓库里，必须单独索取）

| 资产 | 位置 | 没有会怎样 |
|---|---|---|
| `my-upload-key.jks` | 项目根（已 gitignore） | **无法发布可覆盖安装的更新**——签名变了，老用户装不上 |
| `keystore.properties` | 项目根（已 gitignore） | 同上，含 keystore 密码 |
| `local.properties` | 项目根 | 需自行写 `sdk.dir=<Android SDK 路径>` |
| 华为 VR SDK | 由 `HUAWEI_VR_SDK_DIR` 指定 | `standard`/`pico` flavor 的 native 编译不过；`all` 主线不受影响 |
| 本地 LLM 权重（约 1.5 GB） | `filesDir/llm/`，App 内下载 | **不入库属预期**；注意卸载应用会一并删除，重装要重下 |
| `.workbuddy/` | 项目根（已 gitignore） | 内含 `memory/`（按 `rules/` + `topics/` 分册的项目记忆与踩坑记录）和装机脚本；**同机接手优先读它**，异机接手以本文档为准 |

**签名身份是最高优先级资产**：现有 Release 全部由 `CN=GfaceVRplayer`
（SHA-256 `1d4bc93762ce5574690dc0747ae85fde34e340318a588f28032f0ac260be90ac`）签名，
一旦丢失，这个包名就只能换包名重新上架。

---

## 10. 快速自检清单（接手第一天）

- [ ] 能跑通 `:app:compileAllDebugKotlin`
- [ ] 能跑通 `:app:testAllDebugUnitTest`（应全绿）
- [ ] 能 clean 构建出 release 并按 2.4 校验通过
- [ ] 能装到模拟器并冷启动无 FATAL
- [ ] 已拿到签名密钥，并确认 `apksigner verify` 的指纹与第 9 节一致
