# Firebase Analytics 接入指南（v107）

> 用户量统计组件：**Firebase Analytics**（Google 免费服务，Android/Google Play 生态首选）。

## 原理与隐私合规

- **默认不采集**：应用首次启动会弹出隐私同意对话框，用户**同意后**才初始化 Firebase 并上报启动/活跃数据；拒绝则完全不采集。
- 采集内容：匿名设备信息（型号/系统版本/语言）+ 启动与活跃次数 + 崩溃数据（后续可加 Crashlytics）。
- 不采集：任何个人身份信息（姓名/账号/联系方式/视频内容/字幕内容）。
- 用户可随时关闭：`AnalyticsManager.setConsent(context, false)`（建议后续在设置面板加开关）。

## 接入步骤（需要你的 Firebase 账号）

### 1. 创建 Firebase 项目
1. 访问 https://console.firebase.google.com/ 登录（Google 账号）
2. 新建项目（或复用已有项目）
3. 项目设置 → 添加应用 → **Android**
4. 包名填：`com.aistudio.vrplayer.vrmjpy`
5. 下载生成的 **google-services.json**

### 2. 放入配置文件
把 `google-services.json` 放到 `app/` 目录：

```
app/google-services.json   ← 放这里
```

> 该文件包含 Firebase 项目标识（非密钥类敏感信息），但**建议加入 .gitignore 不提交公开仓库**。

### 3. 构建
`google-services.json` 存在后，构建会自动启用 google-services 插件并激活统计：

```powershell
gradlew.bat assembleRelease
```

未放置 json 时：构建正常，统计自动禁用（APK 不含你的 Firebase 配置，日志提示"统计未启用"）。

### 4. 验证
- 首次启动出现「隐私与数据统计」弹窗 → 点同意
- 查看 Logcat：`Firebase Analytics ready`
- Firebase 控制台 → Analytics 面板：次日可见用户量/日活/留存

## 代码结构

| 文件 | 职责 |
|---|---|
| `AnalyticsManager.kt` | 统计入口：隐私同意、初始化、启动/事件上报、关闭 |
| `MainActivity.kt` | 首次启动隐私弹窗 + reportAppOpen |
| `AppApplication.kt` | 应用入口（预留） |

## 扩展埋点示例（可选）

```kotlin
// 播放事件
AnalyticsManager.logEvent(context, "video_play", mapOf("format" to "360", "duration" to "120"))

// LUT 使用
AnalyticsManager.logEvent(context, "lut_apply", mapOf("lut" to "Classic_Cyan_Orange"))
```

## 注意事项

- **Google Play 数据安全表单**：上架时需如实声明收集「设备或其它标识符 / 应用信息 / 崩溃数据」，用途为「分析」，且需提供退出机制（已有：隐私弹窗可拒绝）
- **国内分发**：Firebase 在国内可能无法直连上报（需要网络环境支持）；若主要面向国内商店，可考虑替换为国内统计（如友盟）或仅使用商店后台统计
- **免费额度**：Firebase Analytics 对标准用量免费，无限制门槛
