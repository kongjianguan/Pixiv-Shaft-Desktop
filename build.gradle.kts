plugins {
    kotlin("jvm") version "2.1.20" apply false
    id("org.jetbrains.compose") version "1.9.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}

// Rust ECH 原生库（rust/ech，抄自 PixEz rhttp）。Cargo.lock 已提交保证可复现构建；
// GitHub Actions 上先 setup-rust-toolchain 再跑 Gradle 即可（crates.io 直连）。
tasks.register<Exec>("buildEchLib") {
    group = "build"
    description = "Build the Rust ECH native library (rust/ech)"
    workingDir = rootProject.file("rust/ech")
    commandLine("cargo", "build", "--release")
    inputs.files(
        fileTree("rust/ech/src"),
        file("rust/ech/Cargo.toml"),
        file("rust/ech/Cargo.lock"),
    )
    outputs.file("rust/ech/target/release/libech.dylib")
}
