plugins {
    id("com.google.osdetector") version "1.7.3"
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
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
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // JNA — native libobjc bridge for macOS trackpad pinch (NSEvent.magnify)
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    implementation("com.google.code.gson:gson:2.11.0")
    // Coil 3 图片加载（Compose Desktop）
    implementation("io.coil-kt.coil3:coil-compose:3.1.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.1.0")
    // Voyager navigation (Compose Multiplatform)
    implementation("cafe.adriel.voyager:voyager-navigator:1.0.1")
    implementation("cafe.adriel.voyager:voyager-screenmodel:1.0.1")
    implementation("cafe.adriel.voyager:voyager-tab-navigator:1.0.1")
    // Provides Dispatchers.Main on desktop (AWT EDT) — required by Voyager ScreenModel
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    runtimeOnly("io.netty:netty-codec-native-quic:4.2.2.Final:osx-aarch_64")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("app.cash.sqldelight:sqlite-driver:2.0.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test { useJUnitPlatform() }

compose.desktop {
    application {
        mainClass = "ceui.pixiv.MainKt"
        jvmArgs += listOf(
            "--add-opens", "java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens", "java.desktop/java.awt.peer=ALL-UNNAMED",
            "--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
            // ECH 原生库（开发模式直接指向 cargo 产物；打包后走 Contents/Resources 探测）
            "-Declibrary.path=${rootProject.file("rust/ech/target/release/libech.dylib")}",
        )
        nativeDistributions {
            modules("java.sql", "jdk.unsupported")
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg)
            packageName = "PixivShaft"
            packageVersion = "1.0.0"
            macOS {
                bundleID = "ceui.pixiv.Shaft"
                minimumSystemVersion = "12.0"
                iconFile.set(project.file("src/main/resources/icons/PixivShaft.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSRequiresAquaSystemAppearance</key>
                        <false/>
                    """.trimIndent()
                }
            }
        }
    }
}

// ---- ECH 原生库接入（rust/ech）----

/** 构建 Rust ECH dylib 并复制到 appResourcesRootDir（进 Contents/Resources）。 */
val copyEchLib = tasks.register<Copy>("copyEchLib") {
    dependsOn(rootProject.tasks.named("buildEchLib"))
    from(rootProject.file("rust/ech/target/release/libech.dylib"))
    into(project.file("build/app-resources"))
}

tasks.matching { it.name == "run" }.configureEach {
    dependsOn(rootProject.tasks.named("buildEchLib"))
}

// Compose 插件不保证 appResourcesRootDir 落进 DMG，直接往 app image 里复制：
// createDistributable 生成 PixivShaft.app 后，把 dylib 放进 Contents/Resources，
// packageDmg 再用这个 app image 打 DMG（EchClient 启动时按 java.home 探测该路径）。
tasks.matching { it.name == "createDistributable" }.configureEach {
    dependsOn(copyEchLib)
    doLast {
        val appResources = project.file("build/compose/binaries/main/app/PixivShaft.app/Contents/Resources")
        copy {
            from(project.file("build/app-resources/libech.dylib"))
            into(appResources)
        }
    }
}

// 测试不需要原生库：EchClient.available=false 时拦截器自动回退 QUIC

