pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()

    // ========================================================================
    // v2.4.14：**新增 jitpack（且把范围限死）** —— 唯一用途是取 Media3Avi
    // ------------------------------------------------------------------------
    // 起因：官方 Media3 的 `AviExtractor` **只认 `idx1` 索引**，常量表里没有
    // `INDX`/`AVIX`/`DMLH`；且**没有 idx1 时直接
    // `extractorOutput.seekMap(new SeekMap.Unseekable(durationUs))`**
    // —— 整个视频被判为不可 seek（源码实证，见 `docs/AVI_EXO_COMPAT_STUDY.md`）。
    //
    // `com.github.dburckh:Media3Avi:2.7.1`（MIT 许可、55KB、纯 Java 无 native）
    // 补上了官方缺的 OpenDML（`indx` / `ix##` / `AVIX` 多 movi / `DMLH`）与
    // 无索引兜底，从源头上免掉「一 seek 就把整个文件重封装一遍」的等待。
    //
    // ⚠️ 用 `content { includeGroupByRegex(...) }` **把源的范围限死在这一个 group** ——
    //    与下面移除 ijk 镜像时同一条原则：多一个宽泛的源，就多一处
    //    「依赖被解析到镜像副本上的不同版本」的隐患。
    //    ⚠️ 放宽这个正则前请先想清楚会不会误伤其它依赖。
    maven {
      url = uri("https://jitpack.io")
      content {
        includeGroupByRegex("com\\.github\\.dburckh.*")
      }
    }

    // ========================================================================
    // v2.1.234：**原来这里的两个 ijkplayer 镜像已删除**
    // ------------------------------------------------------------------------
    // v2.1.233 曾加过阿里云 public 与华为云镜像，用途只有一个：取
    // `tv.danmaku.ijk.media:*:0.8.8` —— 它当年只发到 jcenter（2021 关停），
    // MavenCentral / Google Maven 上从来没有这个坐标。
    //
    // 现在 ijk 改用 **本地 AAR 文件依赖**（见 app/build.gradle.kts：
    // `implementation(files("libs/ijkplayer-k0.8.9-release.aar"))`，
    // 来源 debugly/ijkplayer k0.8.9 从上游源码以 NDK r27c 重新构建），
    // 不再需要任何第三方 Maven 源 → 一并移除，保持依赖解析路径干净
    // （少一个镜像就少一处"依赖被解析到镜像副本上的不同版本"的隐患）。
    // ========================================================================
  }
}

rootProject.name = "My Application"

include(":app")
