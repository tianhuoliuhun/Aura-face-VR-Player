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
    // v2.1.233：ijkplayer 的归档源
    // ------------------------------------------------------------------------
    // 背景：ijkplayer 官方当年只发到 **jcenter**（bintray），而 jcenter 已于
    // 2021 年关停。MavenCentral 与 Google Maven 上**从来没有**这个包，直接加
    // `tv.danmaku.ijk.media:*` 坐标会报 Could not find。
    //
    // 阿里云 public 仓库是 MavenCentral + jcenter 的聚合镜像，**完整缓存了
    // jcenter 关停前的归档**，因此能取到 0.8.8 的全部 5 个构件
    // （ijkplayer-java / arm64 / armv7a / x86 / x86_64，均为 .aar）。
    // 华为云镜像同样缓存了（repo.huaweicloud.com/repository/maven/），作为备用。
    //
    // ⚠️ 为什么放在**最后**并且限定 content：
    //    Gradle 按声明顺序查找，google/mavenCentral 优先 —— 这样镜像只用于补漏，
    //    不会把既有依赖解析到镜像上的不同副本（避免版本漂移与构建不确定性）。
    //    `content { includeGroup(...) }` 进一步把它限制成"只认 ijk 这一个 group"，
    //    其他依赖连查都不会查，也不拖慢构建。
    // ========================================================================
    maven {
      url = uri("https://maven.aliyun.com/repository/public/")
      content { includeGroup("tv.danmaku.ijk.media") }
    }
    maven {
      url = uri("https://repo.huaweicloud.com/repository/maven/")
      content { includeGroup("tv.danmaku.ijk.media") }
    }
  }
}

rootProject.name = "My Application"

include(":app")
