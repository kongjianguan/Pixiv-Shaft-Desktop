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
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("io.netty:netty-codec-http3:4.2.2.Final") {
        exclude(group = "io.netty", module = "netty-codec-native-quic")
    }
    runtimeOnly("io.netty:netty-codec-native-quic:4.2.2.Final:osx-aarch_64")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}
tasks.test { useJUnitPlatform() }
