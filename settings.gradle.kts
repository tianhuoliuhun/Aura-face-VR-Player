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
