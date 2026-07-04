plugins {
    id("com.google.osdetector") version "1.7.3"
    kotlin("jvm")
    application
}

group = "ceui.pixiv"
version = "0.0.1-poc"

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

// 大陆镜像：Aliyun public 聚合 central（与原 Shaft 仓 build.gradle 约定一致）
repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/central") }
    mavenCentral()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    // QUIC + HTTP/3 已于 2025-04 毕业入 netty 4.2 core（PR #14979）。
    // netty 4.2 的 netty-codec-quic POM 用 ${os.detected.*} 定 native classifier，
    // Gradle 不插值，故 exclude 掉那个解析不了的 transitive，改显式硬编码 osx-aarch_64。
    implementation("io.netty:netty-codec-http3:4.2.2.Final") {
        exclude(group = "io.netty", module = "netty-codec-native-quic")
    }
    runtimeOnly("io.netty:netty-codec-native-quic:4.2.2.Final:osx-aarch_64")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test { useJUnitPlatform() }

application {
    mainClass.set("ceui.pixiv.poc.PocMainKt")
}
