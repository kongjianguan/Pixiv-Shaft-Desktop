pluginManagement {
    repositories {
        // 大陆镜像：Aliyun 的 gradle-plugin 聚合 Gradle Plugin Portal（含 Kotlin 插件）
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        gradlePluginPortal()
    }
}

rootProject.name = "Pixiv-Shaft-Desktop"
include(":models", ":net", ":store", ":app")
