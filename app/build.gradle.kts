plugins {
    id("com.google.osdetector") version "1.7.3"
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    application
}

group = "ceui.pixiv"
version = "0.0.1"

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

// 大陆镜像：Aliyun public 聚合 central；google 镜像聚合 Google Maven（androidx.* 依赖所需，
// Compose Desktop 运行时的 transitive androidx.collection/annotation/lifecycle 等只在 Google Maven）。
repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/central") }
    maven { url = uri("https://maven.aliyun.com/repository/google") }
    mavenCentral()
}

dependencies {
    implementation(project(":models"))
    implementation(project(":net"))
    implementation(project(":store"))
    // Compose Desktop 运行时（Compose 编译器插件要求 classpath 上有 Compose Runtime；
    // brief Step 6 应用了 compose 插件但未显式加运行时，此处补齐「空 Compose 窗口可编译」）。
    implementation(compose.desktop.currentOs)
    // PocMain.kt 直接用 okhttp3.* / gson JsonParser；:net 的这些依赖是 implementation
    // 作用域不透传给 :app 编译类路径，故 :app 显式声明（与 netty native 同理「为稳」）。
    // Task 12 改写 PocMain 为 Compose AppMain 后可移除。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    // Coil 3 图片加载（Compose Desktop）
    implementation("io.coil-kt.coil3:coil-compose:3.1.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.1.0")
    runtimeOnly("io.netty:netty-codec-native-quic:4.2.2.Final:osx-aarch_64")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test { useJUnitPlatform() }

application {
    mainClass.set("ceui.pixiv.poc.PocMainKt")  // Task 12 改为 AppMain
}
