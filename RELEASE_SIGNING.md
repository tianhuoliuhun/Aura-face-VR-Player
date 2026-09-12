# Release 签名与分发流程

> **为什么必须用 release 签名分发？**
> Debug 包使用 Android SDK 公开的 `debug.keystore`（密码 `android`，人人可查）。
> 任何人拿到你的 debug 包，都能用同一个公开密钥重新签名并伪装更新，用户手机上已信任的
> 签名会被静默替换 —— 这是严重安全隐患。**正式分发只允许 release 签名包。**

## 一、密钥文件（本地生成，绝不提交）

| 文件 | 位置 | 说明 |
|---|---|---|
| `my-upload-key.jks` | 项目根目录（本地） | 签名密钥库，**已 gitignore，勿提交/勿外传** |
| `keystore.properties` | 项目根目录（本地） | 密钥库密码，**已 gitignore，勿提交** |

- 密钥别名：`upload`
- 算法：RSA 2048，建议有效期 30 年
- ⚠️ **密钥与密码必须离线/私密云盘多处备份**（切勿上传公开仓库或公开网盘）。
  丢失密钥 = 永远无法更新已发布的 App（除非换包名重新上架）。
- 重新生成密钥库（会覆盖！）：

  ```powershell
  keytool -genkeypair -v `
    -keystore my-upload-key.jks -alias upload -keyalg RSA -keysize 2048 -validity 10950 `
    -storepass <你的密码> -keypass <你的密码> -dname "CN=<应用名>, OU=Personal, O=Personal, L=Unknown, ST=Unknown, C=CN"
  ```

## 二、构建 release 包

```powershell
# 本地构建（密码自动从 keystore.properties 读取）
gradlew.bat assembleRelease

# 产物：app\build\outputs\apk\release\app-release.apk
```

CI / 服务器场景（不落地 keystore.properties）：
```powershell
$env:KEYSTORE_PATH   = "C:\secure\my-upload-key.jks"
$env:STORE_PASSWORD  = "<store 密码>"
$env:KEY_PASSWORD    = "<key 密码>"
```

## 三、验证签名

```powershell
# 检查 APK 签名（apksigner，Android SDK build-tools）
apksigner.bat verify --print-certs app\build\outputs\apk\release\app-release.apk
# 期望 CN 与生成密钥时填写的 dname 一致，且包含 v1+v2 签名方案（建议 v3）
```

## 四、发布与分发

1. 构建 `assembleRelease`（不是 `assembleDebug`）
2. 验证签名证书为你的 upload 别名
3. 创建 GitHub Release 并上传 APK：
   ```powershell
   gh release create v<版本> --title "<版本标题>" --notes "<更新说明>"
   gh release upload v<版本> app\build\outputs\apk\release\app-release.apk
   ```
4. 上架应用商店（如 Google Play）：使用 Play App Signing（上传密钥 + Play 签名密钥），
   用 PEPK 工具导出上传密钥并妥善保管。

## 五、分发前 Checklist

- [ ] `assembleRelease` 构建（不是 `assembleDebug`）
- [ ] 确认 APK 签名证书为你的 upload 别名
- [ ] 密钥库与密码已私密备份（离线/私密云盘）
- [ ] `keystore.properties` 未被 git 跟踪（`git check-ignore keystore.properties`）
- [ ] 版本号 `versionCode` / `versionName` 已递增
- [ ] 源码中无任何本地路径、用户名、密钥、token（`git grep -n "C:\\Users\\\|D:\\\|sk-\|ghp_\|AKIA"`）
