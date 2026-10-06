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
    versionCode = 251
    versionName = "2.3.1"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // v127：QNN（高通 NPU）引擎及其 136MB 运行库已移除，原先"必须让 .so 落盘"的
  // 理由不再成立。但这里**保留** useLegacyPackaging = true：它会让 native 库以
  // 压缩形式打包，APK 明显更小（实测移除后反而涨了约 24MB）。
  packaging {
    jniLibs {
      useLegacyPackaging = true

      // ======================================================================
      // v2.1.243：**MPV 的 native 库改回"内置模式"**（不再后下载）
      // ----------------------------------------------------------------------
      // 历史：v2.1.235 曾把 MPV 的 so 从 APK 移除、改成用户首次选 MPV 时下载
      //   （理由：占体积 arm64 36.5MB / v7a 31.8MB，不划算）。
      // v2.1.243 改回内置，原因：
      //   1. 查实 WMV/RM 这类格式**只有 MPV 能解**（IJK 裁剪版无对应解码器、
      //      EXO 无 ASF/RealMedia 解析器）→ MPV 从"可选内核"变成"兜底必需"，
      //      再让用户先下载 36MB 才能播一个 WMV，体验上不可接受；
      //   2. 后下载模式依赖固定 tag `mpv-libs` 的 Release 可达性与下载成功率，
      //      多一条失败路径；内置后 `dlopen` 直接走 APK 的 nativeLibraryDir
      //      （Android 白名单路径），最稳。
      //
      // 代价：APK 体积增大（两个 ABI 的 MPV so 展开约 36MB / 31MB）。
      // 这是明确的取舍：**用体积换"任何格式开箱即播"**。
      //
      // ⚠️ 现在的依赖来源是 `io.github.marlboro-advance:mpv-android:1.0.0`
      //    （见下方 dependencies），它的 so 由 AAR 的 jniLibs 自动进包，
      //    **不需要也不应**再加 excludes。
      //    `MpvLibLoader` 已相应改成"优先 System.loadLibrary"（见该类注释）。
      // ======================================================================
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
    // ==========================================================================
  // v2.1.234：ABI 从「仅 arm64-v8a」扩到 **arm64-v8a + armeabi-v7a**
  // --------------------------------------------------------------------------
  // ⚠️ 为什么**不能**加 x86 / x86_64（这两个 ABI 是物理上做不到，不是没做）：
  //
  //   1. **libmars-face-kit.so（旷视 Megvii 闭源预编译二进制）只有两个 ABI**：
  //      third_party/gpupixel/third_party/mars-face-kit/libs/android/
  //        ├── arm64-v8a/libmars-face-kit.so
  //        └── armeabi-v7a/libmars-face-kit.so
  //      没有源码 → 无法自行编译 x86 版本。
  //      而 app/src/main/cpp/CMakeLists.txt 里有一条**硬校验**：
  //        if(NOT EXISTS "<mars-face-kit>/libs/android/${ANDROID_ABI}/libmars-face-kit.so")
  //            message(FATAL_ERROR ...)
  //      → 一旦把 x86 加进 abiFilters，**CMake 直接报错终止构建**（不是警告）。
  //
  //   2. **libmediapipe_tasks_vision_jni.so**（Maven: tasks-vision:0.10.14）
  //      只有 arm64-v8a / armeabi-v7a / x86 —— **没有 x86_64**，
  //      该库是 Google 发布的闭源 AAR，同样无法自行编译。
  //
  //   3. 华为 SDK 的 libxr_loader.so 也只有 arm64-v8a / armeabi-v7a
  //      （D:/HuaweiVrSdk/sdkDemo/openXRsdk/jni/），CMake 对每个 ABI 都会硬校验。
  //
  //   结论：**arm64-v8a + armeabi-v7a 是唯一能做到「每个 ABI 的 native 库都完整」
  //   的组合**。硬塞 x86/x86_64 的后果不是"多支持两个架构"，而是
  //   「构建直接失败」，或者绕过校验后「装上能开、一点美颜就 UnsatisfiedLinkError 崩」。
  //
  //   ⚠️ 另外提醒：**不要**为了让模拟器用 x86 原生库而加 x86_64 ——
  //      一旦 APK 里存在 x86_64 目录，x86_64 设备会优先选它（原生 ABI 优先于转译），
  //      于是从"能用 arm64 转译正常跑"退化成"缺 gpupixel/mars/mediapipe 直接崩"。
  //
  //   abiFilters 的两个 ABI 各来源覆盖（已逐个核对）：
  //     libauravr.so        —— 自建 CMake（按 abiFilters 自动出两份）
  //     libgpupixel.so      —— 源码集成 third_party/gpupixel（同上）
  //     libmars-face-kit.so —— 预编译，两个 ABI 都有
  //     libijkplayer.so     —— libs/ijkplayer-k0.8.9-release.aar，四个 ABI 齐
  //     libsherpa-onnx-*.so / libonnxruntime.so —— sherpa aar，四个 ABI 齐
  //     libmediapipe_*.so   —— tasks-vision aar，含这两个 ABI
  //     libopenxr_loader*.so—— openxr_loader aar + 华为 SDK，两个 ABI 齐
  //     libxr_loader.so     —— 华为 SDK，两个 ABI 齐
  // ==========================================================================
    defaultConfig {
      ndk {
        abiFilters += listOf("arm64-v8a", "armeabi-v7a")
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
            "-DAURA_HAVE_OPENXR=1",
            // v2.0.187：本地 fork 的 GPUPixel（submodule，源码级集成）。
            // 由 CMake 侧 add_subdirectory 编译出 libgpupixel.so，并复用 fork 内
            // 预编译的 libmars-face-kit.so —— 取代原先的 libs/gpupixel-release.aar。
            // 目的：接入 fork 的 texture 通道（共享 EGLContext + SinkTexture），
            // 免去每帧「回读→上传」的跨界搬运。详见 GPUPIXEL_TEXTURE_PATH_FEASIBILITY_2026-09-29.md
            "-DGPUPIXEL_FORK_DIR=" + rootProject.file("third_party/gpupixel").absolutePath
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

  // v2.0.187：GPUPixel 的 Java 类改由 submodule 源码提供（不再来自 AAR）。
  // 路径对应 fork 的 Android 库模块源码目录；classpath 上不再有 gpupixel-release.aar。
  sourceSets["main"].java.directories.add(
    rootProject.file("third_party/gpupixel/src/android/java/gpupixel/src/main/java").absolutePath
  )

  // v2.0.187：GPUPixel 自带**预编译**的 libmars-face-kit.so（Mars-Face 关键点模型运行时），
  // 必须随包分发。该目录结构 `libs/android/<abi>/*.so` 正好符合 jniLibs 的 ABI 约定，
  // 直接挂为源目录即可 —— 比在 CMake 里做 POST_BUILD 拷贝更干净
  // （且 CMake 的 add_custom_command(TARGET ...) 也无法作用于子目录创建的 target）。
  sourceSets["main"].jniLibs.directories.add(
    rootProject.file("third_party/gpupixel/third_party/mars-face-kit/libs/android").absolutePath
  )

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

  // v111：ABI 分包（当前注释掉）
  // ⚠️ v2.1.233 更正：原先这里写着「当前注释掉 = 构建全架构 universal 包，约 391MB」——
  //    但 defaultConfig.ndk.abiFilters 已经锁死 arm64-v8a，**不可能**再产出 universal 包。
  //    所以这个 splits 块现在**即使取消注释也没有额外效果**（包含集被 abiFilters 先一步裁掉）。
  //    真要出多 ABI 包，得先放开上面的 abiFilters，再启用这里。
  //    保留这段是为了记住历史上的分包做法，不是"打开就能用"的开关。
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
  // v2.0.187：GPUPixel 改为**源码级集成**（submodule: third_party/gpupixel，本地 fork）。
  // 原因：需要 fork 的 texture 通道（共享 EGLContext + SinkTexture）来消除每帧
  // 「回读→上传」的跨界搬运。AAR 只能提供原始 raw-data 通道，无法接入。
  //
  // ⚠️ 回退方式：把下面这行注释换成 `implementation(files("libs/gpupixel-release.aar"))`
  //    即可回到 AAR 形态（.so 版本覆盖的 Java 类同样来自 AAR，无需改其他代码）；
  //    但注意此时 **不能**再加 add_subdirectory(GPUPixel)，否则 libgpupixel.so 会重复。
  // implementation(files("libs/gpupixel-release.aar"))
  //
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

  // ==========================================================================
  // v2.1.237：ExoPlayer **流媒体协议扩展**（第一批）
  // --------------------------------------------------------------------------
  // 用户要求"支持 exo 扩展库"。这些是 **纯 Java** 模块，加依赖即可生效 ——
  // `DefaultMediaSourceFactory` 会**自动检测 classpath 上可用的模块**并按 URI
  // scheme 选择对应的 MediaSource（hls:// / .m3u8 → HLS，.mpd → DASH，
  // rtsp:// → RTSP，.ism → SmoothStreaming），**项目代码一行都不用改**。
  // （Media3 各模块的 AAR 自带 consumer proguard 规则，反射查找的类不会被 R8 裁掉。）
  //
  // ⚠️ 解码器扩展（decoder-ffmpeg / av1 / vp9）**不在这里** —— 它们含 native 库，
  //    不在 Google Maven 上，见下方 "解码扩展" 段落。
  //
  // ⚠️ rtmp 与其他几个不同：它提供的是 **DataSource**（不是 MediaSource），
  //    要真正播 rtmp:// 需要在 DataSource.Factory 里显式挂 RtmpDataSource.Factory。
  //    这里先引入（体积很小），实际启用见 `VRPlayerScreen` 的 DataSource 链。
  // ==========================================================================
  implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
  implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
  implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")
  implementation("androidx.media3:media3-exoplayer-smoothstreaming:1.4.1")
  implementation("androidx.media3:media3-datasource-rtmp:1.4.1")
  implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
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

  // ==========================================================================
  // v2.1.234：ijkplayer —— 换用**新源码构建**的 AAR（debugly/ijkplayer k0.8.9）
  // --------------------------------------------------------------------------
  // 为什么不再用 `tv.danmaku.ijk.media:*:0.8.8`：
  //   0.8.8 是 2016 年官方发布的最后一个版本，用 **NDK r10e** 编译 ——
  //   在 Android 15 上已有明确的兼容问题，且它当年只发到 **jcenter**（2021 关停），
  //   MavenCentral / Google Maven 上从来没有这个包，必须挂第三方镜像才能取到。
  //
  // 现在改用 debugly/ijkplayer 的 **k0.8.9**（从上游源码重新构建）：
  //   · NDK r27c 编译 → 可在 Android 15 正常运行
  //   · FFmpeg / OpenSSL(1.1.1w) / soundtouch / yuv 全部升级并静态链接
  //   · **四个 ABI 齐全**：arm64-v8a / armeabi-v7a / x86 / x86_64
  //   · 合并成**单个 libijkplayer.so**（不再是 ijkffmpeg+ijkplayer+ijksdl 三个）
  //   · 用 cmake 重新组织工程，取代原 ndk-build
  //   · **Java API 100% 向后兼容**：包名仍是 `tv.danmaku.ijk.media.player`，
  //     loadLibrariesOnce / native_profileBegin / setSpeed / setOption(int,String,long)
  //     / setSurface / setDataSource / prepareAsync 与各 OPT_CATEGORY_* 常量全部一致
  //     → IjkPlayerBackend.kt 一行都不用改
  //     （已用 javap 逐项核对，并确认 loadLibrariesOnce 内部加载的是 "ijkplayer" 单库）
  //
  // 来源：https://github.com/debugly/ijkplayer/releases
  //       → k0.8.9-beta-260526101841/ijkplayer-cmake-release.aar（13.13 MB）
  //
  // ⚠️ 换成 AAR 文件依赖后，**settings.gradle.kts 里那两个 ijk 专用镜像可以删掉**
  //    （它们只为取 0.8.8 而加，且用 content{includeGroup} 限定了 group）。
  // ==========================================================================
  implementation(files("libs/ijkplayer-k0.8.9-release.aar"))

  // ==========================================================================
  // v2.1.234：MPV（libmpv）解码内核
  // --------------------------------------------------------------------------
  // `io.github.marlboro-advance:mpv-android`（MavenCentral，MIT）—— **libmpv 的纯 JNI
  // 绑定，不带自己的 View**。本项目需要的是"把解码结果吐到我给的 Surface"（视频帧要
  // 交给 VRGLSurfaceView 做投影与美颜），而不是现成的播放器控件，所以这个包正合适。
  //
  // ⚠️ 为什么不用同样常见的 `dev.jdtech.mpv:libmpv`：它 **minSdk = 26**，
  //    而本项目 minSdk = 24 → 清单合并直接失败：
  //      uses-sdk:minSdkVersion 24 cannot be smaller than version 26 declared
  //      in library [dev.jdtech.mpv:libmpv:1.0.0]
  //    提高 minSdk 会砍掉全部 Android 7.x 设备（破坏性变更），故换用本库
  //    —— 它 minSdk = 24，与本项目一致，清单不用动。
  //
  // 已核对（javap + 二进制字符串）：
  //   · `is.xyz.mpv.MPVLib` 提供 create/init/attachSurface/detachSurface/command(vararg)/
  //     setOptionString/getProperty* /observeProperty/addObserver —— 够实现 VrPlayerBackend
  //   · libmpv.so 内含 `mediacodec_embed`（Android 专有 vo，画面直出 Surface）
  //     与 `mediacodec` 硬解 → 可以像 Exo/ijk 一样只交出 Surface
  //   · 含 `video-params` / `audio-params` / `file-format` 等属性 → 「视频信息」面板的数据源
  //   · 四个 ABI 齐全（arm64-v8a / armeabi-v7a / x86 / x86_64）
  //
  // ⚠️ 它是**全局单例**（Kotlin object）：同一时刻只能有一个 mpv 播放器。
  //    本项目同时只播一个片，够用；但要清楚这个限制（见 MpvPlayerBackend.kt 注释）。
  //
  // ⚠️ 体积：AAR 65.4 MB；实际进包的是 abiFilters 允许的两个 ABI
  //    （libmpv.so：arm64 ≈ 13.9 MB + armv7a ≈ 12 MB，另带 libass 等依赖）。
  // ==========================================================================
  implementation("io.github.marlboro-advance:mpv-android:1.0.0")

  // ===== Khronos 标准 OpenXR loader（Android AAR）=====
  // 用途：给 PICO / Meta Quest 提供 OpenXR loader。它们与华为同为 Android OpenXR，
  // 差异只在 loader 来源：
  //   华为：libxr_loader.so（华为 SDK 定制，已接入）
  //   PICO / Quest：libopenxr_loader.so（**Khronos 标准 loader**）
  // ⚠️ Meta 设备的 OpenXR 运行时由**系统自带**；PICO（4 Ultra / 新固件）亦兼容标准
  //    loader。若需支持 PICO Neo3 / 老固件（ALVR 实测需 1.0.34 legacy），
  //    改用 1.0.34 或引入 PICO 官方 SDK 的 loader（同名 .so，只能二选一）。
  implementation("org.khronos.openxr:openxr_loader_for_android:1.1.63")
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
