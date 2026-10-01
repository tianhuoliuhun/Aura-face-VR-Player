# 构建与发布 / Build & Release

> 📖 本文是 [README](../README.md) 的拆分文档之一。返回主文档请点上面的链接。


环境要求、内置 ASR 模型拉取、构建命令、签名与分发纪律。

---

## 🔧 构建 / Build

## 环境要求 / Requirements
- JDK 17+（本机实测 JDK 21）
- Android SDK（compileSdk 36, minSdk 24, targetSdk 36）
- Gradle 9.6.1

> ⚠️ **本仓库不包含 Gradle Wrapper**（没有 `gradlew` / `gradlew.bat`）。
> 请用本机安装的 Gradle 直接调用，并设置 `JAVA_HOME`：
>
> ```powershell
> $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"   # 按本机路径调整
> & "C:\Users\<你>\.gradle\dist\gradle-9.6.1\bin\gradle.bat" -p . assembleRelease
> ```
>
> This repo has **no Gradle Wrapper** — invoke your local Gradle installation instead of `gradlew`.

## 第一步：拉取内置 ASR 模型（首次 clone 后必做）/ Fetch bundled ASR model

`model.int8.onnx`（约 228MB）超过 GitHub 单文件 100MB 限制，**不纳入 git**，
需先跑脚本拉到 `app/src/main/assets/sense-voice/`，否则 APK 不会内置模型
（仍能编译，但离线字幕会退回运行时下载模式）。

```powershell
python scripts/fetch_asr_model.py          # 缺失才下载，支持断点续传
python scripts/fetch_asr_model.py --check  # 只检查是否就绪
```

> 镜像源为 `hf-mirror.com`；不可达时脚本会提示手动下载地址（HuggingFace 官方仓库）。
> 多语言（17 语）模型中只有 SenseVoice 需要随包：**其余 11 种语言由 App 运行时按需下载**。
>
> Mirror: `hf-mirror.com`; the script prints a manual download URL when unreachable.
> Of the 17 languages, **only SenseVoice ships inside the APK** — the other 11 are downloaded on demand at runtime.

## 构建命令 / Commands

```powershell
# Debug 包（开发测试）
gradle.bat assembleDebug

# Release 包（正式分发，必须！见 RELEASE_SIGNING.md）
# 产物：app\build\outputs\apk\release\Aura-face-VR-Player-v<版本>.apk（单包全架构，含内置模型）
gradle.bat assembleRelease

# 依赖许可证清单导出
gradle.bat :app:dumpDependencies
python scripts/gen_licenses.py
```

> ⚠️ **正式分发只允许 Release 包**：Release 使用项目私有签名（`my-upload-key.jks`），
> Debug 包使用公开的 Android debug key（密码 `android`），外发 Debug 包可被任何人重签伪造更新。
>
> ⚠️ **Only release APKs for distribution**: debug keys use the publicly known password `android`.

---
