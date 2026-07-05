plugins {
    kotlin("jvm")
    id("app.cash.sqldelight") version "2.0.2"
}
group = "ceui.pixiv"; version = "0.0.1"
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/central") }
    maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
    maven { url = uri("https://maven.aliyun.com/repository/google") }
    mavenCentral(); gradlePluginPortal()
}
dependencies {
    implementation(project(":models"))
    implementation(project(":net"))
    implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
tasks.test { useJUnitPlatform() }
sqldelight {
    databases { create("ShaftDatabase") { packageName.set("ceui.pixiv.store") } }
}
