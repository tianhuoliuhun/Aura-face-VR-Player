# ============================================================================
# v2.6.11：**首次启用 R8**（release 从 v2.6.10 及以前一直是 isMinifyEnabled = false）
# ----------------------------------------------------------------------------
# 本文件此前只是 AGP 生成的空模板（21 行全注释）。开启 R8 + shrinkResources 后，
# 下面的规则决定「哪些名字必须原样保留」。每条都对应本项目里一个**真实的反射 /
# JNI 依赖**，不是抄模板 —— 改动前请先读对应注释。
#
# 排查方法（构建能过但运行崩）：
#   1. 看 `app/build/outputs/mapping/allRelease/mapping.txt`（反混淆栈）
#   2. `Missing class` 类报错 → 补 `-dontwarn`；`NoSuchMethodError` → 补 keep
#   3. 临时把 build.gradle.kts 的 isMinifyEnabled 改回 false 对照验证
# ============================================================================

# ---------------------------------------------------------------------------
# 1. 通用属性
# ---------------------------------------------------------------------------
# Annotation / Signature 是反射与泛型反序列化的基础
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*,AnnotationDefault
# LineNumberTable 保留 → tombstone / 崩溃栈仍有行号（配合 mapping.txt 可还原）
-keepattributes SourceFile,LineNumberTable
# 把源文件名统一成 "SourceFile"，省体积；行号仍保留
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# 2. 枚举
# ---------------------------------------------------------------------------
# values() / valueOf(String) 依赖常量名。本项目枚举很多（TranslationEngine、
# DecoderEngine、SubtitleFont、AsrLanguage…），且设置项持久化走 `.id`（Int），
# 不依赖 name；但 Debug 导出 / 日志里有按 name 输出的地方，故全量保留成员。
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------------------------------------------------------------------------
# 3. Parcelable / Serializable
# ---------------------------------------------------------------------------
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ---------------------------------------------------------------------------
# 4. JNI —— **最关键的一条**
# ---------------------------------------------------------------------------
# 本项目没有用 RegisterNatives（全仓 grep 为空），JNI 走的是**标准命名导出**：
#   Java_com_example_vr_LlamaMtmd_nativeInit  ← 符号名由「包名+类名+方法名」拼成
# 因此**类名与方法名一个字都不能改**，否则 dlopen 后找不到符号 → UnsatisfiedLinkError。
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# 承载 native 方法的 Kotlin 类：除符号名外，native 侧还会回调这些类里的
# 方法（进度/状态回调、Surface/帧回调），所以连成员一起保留。
-keep class com.example.vr.LlamaMtmd { *; }
-keep class com.example.vr.FfmpegRemuxer { *; }
-keep class com.example.vr.huawei.HuaweiVrNative { *; }

# ---------------------------------------------------------------------------
# 5. 第三方 native 库的 Java 层
# ---------------------------------------------------------------------------
# 共同点：这些 .so 会**反向调用** Java 方法（回调 / listener / 观察者），而调用
# 目标靠方法名字符串查找 → 混淆必崩。它们的 Java 层合计只有几百 KB，
# 全 keep 的代价远小于逐个试错。
#
# 5.1 sherpa-onnx：C++ 侧回调 OnlineRecognizer/OfflineRecognizer 的结果
-keep class com.k2fsa.sherpa.onnx.** { *; }

# 5.2 ijkplayer：IJKMediaPlayer 的 native 回调 + IjkMediaPlayer 的 opengl 回调
-keep class tv.danmaku.ijk.media.** { *; }

# 5.3 MediaPipe Tasks Vision：native 侧回调 result listener
-keep class com.google.mediapipe.** { *; }

# 5.4 onnxruntime：JNI 侧回调 OnnxValue 的 Java 实现类
-keep class ai.onnxruntime.** { *; }

# 5.5 mpv-android：MPVLib 的 EventObserver / 属性回调全是 native 反调
#     ⚠️ 见 memory:topics/mpv-backend.md —— `EventObserver.event(id, data)` 必须原样
-keep class is.xyz.mpv.** { *; }

# 5.6 GPUPixel（submodule 源码级集成，包名 com.pixpark.gpupixel）
#     闭源 libmars-face-kit.so 通过 JNI 回调 FaceDetector / GPUPixelSink
-keep class com.pixpark.gpupixel.** { *; }

# 5.7 华为 VR Engine 官方 Java 桥（hvrbridge.jar）
#     ⚠️ HuaweiVrActivity 里用 Class.forName("com.huawei.hvr.LibUpdateClient")
#        + getMethod("runUpdate") 反射调用 → 类名与方法名必须原样
#     ⚠️ 该 jar 里还打进了 `com.huawei.vrlab.UnityPlayerActivity`（Unity 集成样例），
#        它引用 `com.unity3d.player.UnityPlayer`，而本项目不含 Unity 运行时。
#        keep 到 com.huawei.** 会连带保留它 → R8 报 Missing class，见下方 -dontwarn。
-keep class com.huawei.hvr.** { *; }
-keep class com.huawei.** { *; }

# ---------------------------------------------------------------------------
# 6. DLNA 投屏接收（Jetty 9 + jUPnP）
# ---------------------------------------------------------------------------
# 这是全项目**反射最重**的一块：Jetty 内部用 ServiceLoader 装配 Handler /
# Servlet，jUPnP 用反射实例化协议处理器，且大量 `Class.forName` 读类名字符串。
# 全 keep 会少省 1~2MB，但 DLNA 接收是「要么能用要么完全不能用」的功能，
# 不值得为这点体积去赌。
-keep class org.eclipse.jetty.** { *; }
-keep class org.jupnp.** { *; }
-keep class javax.servlet.** { *; }
-keep class org.slf4j.** { *; }

# ServiceLoader 的 META-INF/services 注册项
-keepnames class * implements java.util.ServiceLoader$Provider
-keep class * implements org.jupnp.UpnpServiceConfiguration { *; }

# ---------------------------------------------------------------------------
# 7. 按**类名字符串**做分支的代码
# ---------------------------------------------------------------------------
# DanmuVisionClient.classifyException 用 `e.javaClass.simpleName.contains("StreamReset")`
# 判断 HTTP/2 流重置（okhttp 内部类，import 会触发 K2 Unresolved）。
# 混淆后 simpleName 变成 "a" → 该分支静默退化成 NETWORK，错误提示失准。
-keepnames class okhttp3.internal.http2.StreamResetException

# ---------------------------------------------------------------------------
# 8. Kotlin / 协程 / Compose
# ---------------------------------------------------------------------------
# 官方库自带 consumer 规则，这里只补 Kotlin metadata（Compose 编译器产物需要）。
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# ---------------------------------------------------------------------------
# 9. 可选依赖缺失 → 只静音警告，不影响正确性
# ---------------------------------------------------------------------------
# Jetty / jUPnP 会把一堆运行时可选模块（JNDI、JASPI、ALPN、log4j…）写成硬引用，
# 本项目不带这些 jar，R8 会报 Missing class。逐个 -dontwarn 即可。
-dontwarn org.eclipse.jetty.**
-dontwarn org.slf4j.**
-dontwarn javax.**
-dontwarn jakarta.**
-dontwarn org.jupnp.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.apache.**
-dontwarn com.google.errorprone.annotations.**

# 首次开启 R8 时实测报出的两个**真实缺失类**（见 allRelease/missing_rules.txt）：
#   · com.google.mediapipe.proto.*  —— GraphProfiler / 图模板序列化的可选 proto，
#     本项目只用 Tasks Vision 的人脸检测，不碰 profiler
#   · com.unity3d.player.UnityPlayer —— 来自 hvrbridge.jar 里的 Unity 样例 Activity
-dontwarn com.google.mediapipe.proto.**
-dontwarn com.unity3d.player.**

# v2.6.18：mediapipe **0.10.14 → 0.10.29** 带来的新缺失类（release 构建首爆）。
#   `com.google.mediapipe.framework.image.MPImageProperties.hashCode()` 引用了
#   `com.google.auto.value.extension.memoized.Memoized` —— 那是 auto-value 的
#   **编译期注解处理器**产物，不会随 AAR 发布，运行时也永远不会走到。
#   不抑制的话 `minifyAllReleaseWithR8` 直接 FAILED（"Compilation failed to complete"），
#   而 debug 构建不走 R8 → 只有发版时才发现（本次即如此）。
-dontwarn com.google.auto.value.extension.memoized.Memoized
