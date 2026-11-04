# mpv-libs

MPV 解码内核的 10 个 `.so`（每个 ABI 一份），**只在兜底路径下用到**。

## 这是什么

| 文件 | ABI | 内容 |
|---|---|---|
| `mpv-libs-arm64-v8a.zip` | arm64-v8a | `libc++_shared` / `libavutil` / `libswresample` / `libswscale` / `libavcodec` / `libavformat` / `libavdevice` / `libavfilter` / `libmpv` / `libplayer` |
| `mpv-libs-armeabi-v7a.zip` | armeabi-v7a | 同上 |

## 谁在用

`MpvLibLoader`（`app/src/main/java/com/example/vr/MpvLibLoader.kt`）的**下载兜底**：

1. **首选路径**：MPV 的 so 已经打在 APK 里（`jniLibs`），运行时 `System.loadLibrary(短名)`
   从 `nativeLibraryDir` 加载 —— 正常情况下**根本不会用到这里的 zip**。
2. **兜底路径**：只有当某个 ABI 的包因为 packaging 配置变动漏了这些 so 时，
   设置页「MPV 解码库 → 下载并安装」才会从本目录拉 zip，解到 `filesDir/mpv-libs/` 再按依赖序加载。

## 为什么放在仓库里而不是 Release

原先挂在 tag 为 `mpv-libs` 的 GitHub Release 上（30.9 MB 附件）。用户要求不占 Release，
改为随源码进仓库。App 侧下载地址相应改为 raw 直链：

```
https://raw.githubusercontent.com/tianhuoliuhun/Aura-face-VR-Player/main/mpv-libs/<文件名>
```

⚠️ 走 `main` 分支 → **更新这两个 zip 必须推 `main` 才会生效**；raw 单文件上限 100 MB，当前 15~17 MB。

## 怎么重新打包

```bash
python .workbuddy/tmp/pack_mpv_libs.py     # 见 MpvLibLoader 注释里的 LOAD_ORDER
```

改动后必须核对依赖拓扑序（`llvm-readelf -d`），`libplayer` 依赖 `libmpv`，顺序错了兜底会加载失败。
