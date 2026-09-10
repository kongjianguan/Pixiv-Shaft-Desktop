# 开发

[English](development.md) | 中文

本指南说明 PixivShaft Desktop 支持的本地工作流。项目面向 macOS，并以 Gradle wrapper 作为构建入口。

## 前置条件

安装 JDK 21，并让 Gradle 能够找到它。仓库使用的本地 Homebrew 路径是 `/opt/homebrew/opt/openjdk@21`。

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
```

应用会从 `rust/ech/prebuilt/libech.dylib` 加载 Apple Silicon Rust ECH 库。普通应用构建使用仓库中已提交的库；只有 Rust ECH 源码发生变化时才需要重新构建。

## 日常工作流

编译 Kotlin 源码但不启动应用：

```bash
./gradlew :app:compileKotlin
```

运行所有模块的单元测试：

```bash
./gradlew test
```

启动桌面应用：

```bash
./gradlew :app:run
```

[`script/build_and_run.sh`](../script/build_and_run.sh) 辅助脚本还支持 `run`、`debug`、`logs` 和 `verify` 模式，用于本地应用检查。

## 打包

构建可分发的 macOS 应用，然后生成 DMG：

```bash
./gradlew :app:createDistributable
./gradlew :app:packageDmg
```

DMG 会写入 `app/build/compose/binaries/main/dmg/PixivShaft-1.0.0.dmg`。Compose 打包配置会将 `java.sql` 和 `jdk.unsupported` 添加到运行时映像，因为 SQLDelight 和桌面运行时需要这些模块。

## ECH 库

Rust 辅助库位于 `rust/ech`。使用以下命令重新构建并复制 Apple Silicon 库：

```bash
./gradlew updateEchPrebuilt
```

此命令需要 Rust 工具链和 `aarch64-apple-darwin` target。CI 会在测试任务前运行该命令，以保持已提交的预构建库与 Rust 源码一致。

## 文档检查

仓库没有 Gradle Markdown 任务。修改 `docs/` 或 `.agents/notes/` 下的文件后，直接运行项目文档检查：

```bash
python3 /Users/he/.agents/skills/project-doc-library/scripts/audit_template_policy.py --skill-root /Users/he/.agents/skills/project-doc-library
python3 /Users/he/.agents/skills/project-doc-library/scripts/verify_project_docs.py --root "$PWD"
python3 /Users/he/.agents/skills/project-doc-library/scripts/lint_prose.py --root "$PWD"
git diff --check
```

模板策略审计会检查受保护协议模板及其项目中立性。结构验证器会检查生命周期目录、活动 Agent Note 格式、归档元数据，以及非模板项目文档中的链接。这些检查不会验证架构正文是否真实，也不能替代对历史资料的人工评审。

## CI

GitHub Actions CI 工作流在 macOS 上运行，安装 JDK 21，重新构建 ECH 预构建库，然后运行 `./gradlew test --stacktrace`。发布工作流会为匹配 `v*` 的 tag 打包 DMG。
