// GitHub Actions 等 CI 环境走官方仓库（阿里云镜像在海外网络下不稳定，会导致插件/依赖解析失败）

pluginManagement {
    repositories {
        if (System.getenv("CI") != "true") {
            // 国内镜像仓库优先（阿里云），仅本地构建使用以加速
            maven { setUrl("https://maven.aliyun.com/repository/public") }
            maven { setUrl("https://maven.aliyun.com/repository/google") }
            maven { setUrl("https://maven.aliyun.com/repository/gradle-plugin") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI") != "true") {
            // 国内镜像仓库优先（阿里云），仅本地构建使用以加速
            maven { setUrl("https://maven.aliyun.com/repository/public") }
            maven { setUrl("https://maven.aliyun.com/repository/google") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "ling-ting"
include(":app")
