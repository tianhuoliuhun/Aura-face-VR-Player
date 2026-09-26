plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  // v107：Firebase Analytics（google-services 插件；无 google-services.json 时不启用，见下方条件 apply）
  id("com.google.gms.google-services") version "4.4.2" apply false
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.vrplayer.vrmjpy"
    minSdk = 24
    targetSdk = 36
    versionCode = 174
    versionName = "2.0.174"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // v127：QNN（高通 NPU）引擎及其 136MB 运行库已移除，原先"必须让 .so 落盘"的
  // 理由不再成立。但这里**保留** useLegacyPackaging = true：它会让 native 库以
  // 压缩形式打包，APK 明显更小（实测移除后反而涨了约 24MB）。
  packaging {
    jniLibs {
      useLegacyPackaging = true
    }
  }

  // v2.0.127：SenseVoice 模型内置到 assets，必须禁止压缩。
  // 若被压缩，sherpa-onnx 从 AssetManager 读取时需先解压到内存（239MB 峰值），
  // 既慢又容易 OOM；noCompress 后可走文件描述符直接读。
  androidResources {
    noCompress += listOf("onnx", "bin", "txt")
  }

  // ==========================================================================
  // 华为 VR Glass（VR Engine / OpenXR）native 构建 —— 默认**关闭**
  // --------------------------------------------------------------------------
  // 为什么默认关闭：native 构建需要 NDK + CMake + 华为 SDK 三件套，
  // 在它们到位之前必须保证「普通构建照常可用」（不能因为缺 NDK 就编不过）。
  //
  // 启用方式：在项目根 local.properties 里加
  //     huawei.vr.enable=true
  //     huawei.vr.sdk.dir=D:/HuaweiVRSDK          ← SDK 解压根目录
  //     ndk.dir=C:/Users/<你>/AppData/Local/Android/Sdk/ndk/<版本号>
  //
  // ⚠️ 设计要点：`useLegacyPackaging = true`（上方 packaging 块）会让 .so
  //    以压缩形式打包，**保留不动** —— 移除它会让 APK 涨约 24MB。
  //
  // ⚠️ ABI 只保留 arm64-v8a：华为 VR Glass 仅支持真机 arm64；
  //    x86_64（模拟器）没有华为 Runtime，编了也用不上，白增体积。
  //
  // ⚠️ CMake 版本必须与本机 SDK 里实际安装的版本一致。
  //    本机（2026-09-26）装的是 4.1.2；若你的环境不同，改这里或删掉 version
  //    让 Gradle 自行协商（需 sdkmanager 已装多个版本）。
  // ==========================================================================
  val huaweiCmakeVersion = "4.1.2"
  val huaweiVrEnabled: Boolean = run {
    val f = project.rootProject.file("local.properties")
    if (!f.exists()) false
    else f.readLines().any { it.trim() == "huawei.vr.enable=true" }
  }
  val huaweiVrSdkDir: String = run {
    val f = project.rootProject.file("local.properties")
    if (!f.exists()) ""
    else f.readLines()
      .firstOrNull { it.trim().startsWith("huawei.vr.sdk.dir=") }
      ?.substringAfter("=")?.trim() ?: ""
  }

  if (huaweiVrEnabled) {
    logger.lifecycle("[HuaweiVR] native 构建已启用，SDK 路径: $huaweiVrSdkDir")
    // ⚠️ NDK 版本必须与 local.properties 的 ndk.dir 一致：
    //    AGP 默认要 28.2.13676358，本机装的是 30.0.16248370，不一致会报
    //    「CXX1104: NDK from ndk.dir had version ... which disagrees with android.ndkVersion」。
    //    这里显式指定本机实际版本；若你的环境不同，改这个字符串即可。
    ndkVersion = "30.0.16248370"
    defaultConfig {
      ndk {
        abiFilters += listOf("arm64-v8a")
      }
    }
    externalNativeBuild {
      cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = huaweiCmakeVersion
      }
    }
    defaultConfig {
      externalNativeBuild {
        cmake {
          // 把 SDK 路径与「已接入」宏传进 CMake
          arguments += listOf(
            "-DHUAWEI_VR_SDK_DIR=$huaweiVrSdkDir",
            "-DAURA_HAVE_OPENXR=1"
          )
          cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
        }
      }
    }
    // 让 CMake 里复制出来的 libxr_loader.so 参与打包
    sourceSets["main"].jniLibs.directories.add("src/main/cpp/libs")
  } else {
    logger.lifecycle("[HuaweiVR] native 构建未启用（local.properties 无 huawei.vr.enable=true），跳过")
  }

  signingConfigs {
    // v106：release 签名。密码来源优先级：
    //   1. 环境变量 STORE_PASSWORD / KEY_PASSWORD（CI 场景）
    //   2. 项目根 keystore.properties（本地开发，已 gitignore，勿提交）
    // 密钥库文件默认取项目根 my-upload-key.jks（已 gitignore），也可用 KEYSTORE_PATH 指定。
    fun prop(name: String): String? {
      val f = project.rootProject.file("keystore.properties")
      if (!f.exists()) return null
      return f.readLines().firstOrNull { it.startsWith("$name=") }?.substringAfter("=")?.trim()
    }
    val releaseStorePass: String = System.getenv("STORE_PASSWORD") ?: prop("storePassword") ?: ""
    val releaseKeyPass: String = System.getenv("KEY_PASSWORD") ?: prop("keyPassword") ?: ""
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = releaseStorePass
      keyAlias = "upload"
      keyPassword = releaseKeyPass
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }

  // v111：ABI 分包（当前注释掉 = 构建全架构 universal 包，约 391MB）
  // 若要出 arm64 专用包，取消注释下面几行：
  // splits {
  //   abi {
  //     isEnable = true
  //     reset()
  //     include(*listOf("arm64-v8a").toTypedArray())
  //   }
  // }
}

// v107：Firebase Analytics 条件启用。
// 在 Firebase 控制台创建应用（包名 com.aistudio.vrplayer.vrmjpy）并下载
// google-services.json 放入 app/ 目录后自动生效；未配置时跳过，不影响构建。
// （firebase-analytics 依赖无条件引入：无 json 时 FirebaseApp 无默认实例，统计自动禁用）
if (file("google-services.json").exists()) {
  apply(plugin = "com.google.gms.google-services")
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

// ===== 依赖许可证收集任务（开源合规）=====
// 用法：gradlew :app:dumpDependencies
// 输出：app/build/deps.txt（每行一个 group:name:version 坐标，供 scripts 生成 licenses.json）
// 说明：debugCompileClasspath 即最终打进 APK 的依赖集合（含传递依赖）。
tasks.register("dumpDependencies") {
  // 该任务读取 Android 变体配置（debugCompileClasspath），配置缓存下不可用，标记跳过缓存
  notCompatibleWithConfigurationCache("读取 Android 变体配置，配置缓存下不可用")
  doLast {
    // 注意：配置缓存模式下禁止在任务执行时访问 project；本任务已标记不兼容缓存，故此处可安全访问
    val out = File(layout.buildDirectory.get().asFile, "deps.txt")
    out.parentFile.mkdirs()
    val sb = StringBuilder()
    configurations.named("debugCompileClasspath").get()
      .incoming.resolutionResult.allComponents
      .mapNotNull { it.moduleVersion }
      .distinctBy { "${it.group}:${it.name}:${it.version}" }
      .sortedBy { "${it.group}:${it.name}" }
      .forEach { sb.appendLine("${it.group}:${it.name}:${it.version}") }
    out.writeText(sb.toString())
    println("依赖坐标已写入: ${out.absolutePath} (${sb.lines().count()} 个)")
  }
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  // v102 曾移除 GPUPixel；v2.0.160 按双引擎规划重新引入为「可选方案」（默认仍是 GLSL）。
  // AAR 取自官方 Release v1.3.1：自带 arm64-v8a / armeabi-v7a 两个 ABI 的 .so，
  // 以及 Mars-Face 模型（face_det / face_align .mars_model）与妆容素材（AAR assets 自动合并）。
  // 注意：官方预编译包**不含 x86_64** —— 模拟器上会初始化失败，运行时自动回退 GLSL 引擎。
  implementation(files("libs/gpupixel-release.aar"))
  // v2.0.174：华为 VR Engine 官方 Java 桥（hvrbridge.jar）。
  // 提供 com.huawei.hvr.LibUpdateClient（确保设备已装/已更新华为 VR Runtime）
  // 与 com.huawei.vrlab.HVRActivity（官方 2D→VR 通道）。
  // ⚠️ 这是华为 SDK 的一部分，**不入仓**（已在 .gitignore 忽略）；
  //    本地构建前需从 SDK 的 sdkDemo/openXRsdk/libs/ 复制到 app/libs/。
  //    文件缺失时不报错（普通构建不受影响），仅华为 VR 功能不可用。
  val hvrBridgeJar = file("libs/hvrbridge.jar")
  if (hvrBridgeJar.exists()) {
    implementation(files("libs/hvrbridge.jar"))
  } else {
    logger.lifecycle("[HuaweiVR] app/libs/hvrbridge.jar 不存在，跳过（官方 Java 桥不可用）")
  }
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  implementation(libs.mediapipe.tasks.vision)
  // Liquid Glass effect (Android 12+ RenderEffect backdrop; uses androidx compose 1.10.3)
  implementation(libs.backdrop)
  implementation("androidx.media3:media3-exoplayer:1.4.1")
  implementation("androidx.media3:media3-common:1.4.1")
  implementation("androidx.media3:media3-transformer:1.4.1")
  implementation("androidx.media3:media3-effect:1.4.1")
  // v2.0.142：SimpleCache 需要 media3-database 提供 StandaloneDatabaseProvider
  // （CacheDataSource / SimpleCache / LeastRecentlyUsedCacheEvictor 本身在 media3-datasource，
  //  已由 media3-exoplayer 传递引入，无需再显式声明）
  implementation("androidx.media3:media3-database:1.4.1")
  // Real Vosk offline speech recognition (Kaldi based, on-device ASR)
  // v117：纯 Java MPEG 音频软件解码兜底（JLayer，LGPL-2.1）
  // 背景：MPEG-1 Audio Layer II（Android 里的 MIME 是 audio/mpeg-L2）在 Android 上属可选格式，
  // 部分机型（实测骁龙8 Elite / SM8850）没有可用解码器，导致字幕生成的音轨解码失败；
  // JLayer 支持 MPEG-1/2/2.5 的 Layer I/II/III 解码，作为 MediaCodec 失败后的兜底。
  implementation("com.googlecode.soundlibs:jlayer:1.0.1.4")
  // SMB client for LAN playback
  implementation("eu.agno3.jcifs:jcifs-ng:2.1.8")
  // v107：用户统计（隐私合规：用户同意后才采集，见 AnalyticsManager）
  // Firebase Analytics（免费）：google-services.json 未配置时自动禁用，不影响构建运行
  implementation("com.google.firebase:firebase-analytics")

  // v110：sherpa-onnx 离线 ASR（Qwen3-ASR 等，29 语言 + 20 种中文方言）
  implementation(files("libs/sherpa-onnx-1.13.6.aar"))
  // tar.bz2 模型解压支持（sherpa-onnx 模型打包格式）
  implementation("org.apache.commons:commons-compress:1.27.1")
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

// v2.0.127：APK 产物命名规范化。
// 默认的 app-release.apk / app-debug.apk 看不出版本，归档与分发时极易混淆。
// AGP 9 已移除旧的 applicationVariants 改名 API，这里改为在 assemble 完成后重命名文件，
// 与 AGP 版本无关（src 不存在时自动跳过，可重复执行）。
// 产物：Aura-face-VR-Player-v<versionName>.apk / -debug.apk
// 注意两点，都是 AGP 9 + Gradle 9 的坑：
// 1) assemble 任务注册较晚：必须用 afterEvaluate + matching（live 集合），
//    直接用 tasks.named 会在配置期抛 "Task with name 'assembleRelease' not found"。
// 2) 配置缓存（configuration cache）默认开启：doLast 闭包里不能再引用 project / android /
//    layout，否则会报 cannot serialize DefaultProject。因此路径与版本号必须在配置期
//    先算成纯 String，闭包内只用这些字符串。
afterEvaluate {
  val buildDirPath = layout.buildDirectory.asFile.get().absolutePath
  val ver = android.defaultConfig.versionName ?: "unknown"
  tasks.matching { it.name == "assembleRelease" || it.name == "assembleDebug" }.configureEach {
    val type = name.removePrefix("assemble").lowercase()
    val suffix = if (type == "release") "" else "-debug"
    val dirPath = "$buildDirPath/outputs/apk/$type"
    val srcName = "app-$type.apk"
    val dstName = "Aura-face-VR-Player-v$ver$suffix.apk"
    doLast {
      val src = File(dirPath, srcName)
      val dst = File(dirPath, dstName)
      if (src.exists()) {
        dst.delete()
        if (src.renameTo(dst)) {
          logger.lifecycle("[apk-name] APK -> $dstName")
        } else {
          logger.warn("[apk-name] 重命名失败（仍为 $srcName）")
        }
      }
    }
  }
}
