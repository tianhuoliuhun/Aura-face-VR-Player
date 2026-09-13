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
    versionCode = 121
    versionName = "1.0.121"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // v118：QNN（高通 NPU）运行库必须以真实文件形式落在 nativeLibraryDir。
  // targetSdk>=30 时 AGP 默认 extractNativeLibs=false：.so 以未压缩形式留在 APK 内、
  // 不展开到 lib/<abi>/（实测安装后 /data/app/.../lib/arm64/ 为空）。
  // 而 QnnConfig.backendLib/systemLib 要的是可 dlopen 的文件路径，且 ADSP_LIBRARY_PATH
  // 也要指向能找到 libQnnHtpV*Skel.so 的真实目录 —— 故改回 legacy 打包让 .so 落盘。
  packaging {
    jniLibs {
      useLegacyPackaging = true
    }
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
  // v102：GPUPixel 原生美颜引擎已移除（删除 aar），改用内置 GLSL shader 美颜方案
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
  // Real Vosk offline speech recognition (Kaldi based, on-device ASR)
  implementation("com.alphacephei:vosk-android:0.3.75")
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
