plugins {
    kotlin("jvm") version "2.1.20" apply false
    id("org.jetbrains.compose") version "1.9.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}

// Rust ECH 原生库（rust/ech，抄自 PixEz rhttp）。
//
// dylib 已预编译提交在 rust/ech/prebuilt/，默认构建链不跑 cargo（cargo 慢且占硬盘）。
// 仅当 Rust 代码改动时才需要手动：
//   ./gradlew updateEchPrebuilt   # cargo 重编 + 复制到 prebuilt/，再提交
// CI 无需安装 Rust 工具链。
tasks.register<Exec>("buildEchLib") {
    group = "build"
    description = "Build the Rust ECH native library (rust/ech) — manual only"
    workingDir = rootProject.file("rust/ech")
    // 只支持 Apple Silicon：显式 arm64 target，Intel 机器上交叉编译产物一致
    commandLine("cargo", "build", "--release", "--target", "aarch64-apple-darwin")
    inputs.files(
        fileTree("rust/ech/src"),
        file("rust/ech/Cargo.toml"),
        file("rust/ech/Cargo.lock"),
    )
    outputs.file("rust/ech/target/aarch64-apple-darwin/release/libech.dylib")
}

tasks.register<Copy>("updateEchPrebuilt") {
    group = "build"
    description = "Rebuild the Rust ECH lib and refresh rust/ech/prebuilt (manual)"
    dependsOn("buildEchLib")
    from("rust/ech/target/aarch64-apple-darwin/release/libech.dylib")
    into("rust/ech/prebuilt")
}
