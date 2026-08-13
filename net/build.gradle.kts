plugins { kotlin("jvm") }
group = "ceui.pixiv"; version = "0.0.1"
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/central") }
    mavenCentral()
}
dependencies {
    implementation(project(":models"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    // API 接口暴露 retrofit2.Call（getNovelTextCall），需 api 才能让 :app 编译
    api("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("io.netty:netty-codec-http3:4.2.2.Final") {
        exclude(group = "io.netty", module = "netty-codec-native-quic")
    }
    runtimeOnly("io.netty:netty-codec-native-quic:4.2.2.Final:osx-aarch_64")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
tasks.test { useJUnitPlatform() }

// ECH 冒烟测试需要原生库路径（dylib 不存在时 EchClient.available=false，测试自动跳过）
tasks.test {
    systemProperty(
        "ech.library.path",
        rootProject.file("rust/ech/prebuilt/libech.dylib").absolutePath,
    )
}
