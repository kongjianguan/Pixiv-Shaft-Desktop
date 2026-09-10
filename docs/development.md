# Development

English | [中文](development.zh.md)

This guide covers the supported local workflow for PixivShaft Desktop. The project targets macOS and uses the Gradle wrapper as its build entry point.

## Prerequisites

Install JDK 21 and make it visible to Gradle. The local Homebrew path used by the repository is `/opt/homebrew/opt/openjdk@21`.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
```

The application bundles an Apple Silicon Rust ECH library from `rust/ech/prebuilt/libech.dylib`. Normal application builds use that checked-in library. Rebuild it only when the Rust ECH source changes.

## Daily workflow

Compile the Kotlin sources without starting the application:

```bash
./gradlew :app:compileKotlin
```

Run the unit tests for all modules:

```bash
./gradlew test
```

Start the desktop application:

```bash
./gradlew :app:run
```

The [`script/build_and_run.sh`](../script/build_and_run.sh) helper also supports `run`, `debug`, `logs`, and `verify` modes for local application checks.

## Packaging

Build a distributable macOS application and then a DMG:

```bash
./gradlew :app:createDistributable
./gradlew :app:packageDmg
```

The DMG is written to `app/build/compose/binaries/main/dmg/PixivShaft-1.0.0.dmg`. The Compose packaging configuration adds `java.sql` and `jdk.unsupported` to the runtime image because SQLDelight and the desktop runtime require them.

## ECH library

The Rust helper is under `rust/ech`. Rebuild and copy the Apple Silicon library with:

```bash
./gradlew updateEchPrebuilt
```

This command requires a Rust toolchain and the `aarch64-apple-darwin` target. CI runs it before the test task so the committed prebuilt library stays aligned with the Rust source.

## Documentation checks

The repository has no Gradle Markdown task. Run the project documentation checks directly after changing files under `docs/` or `.agents/notes/`:

```bash
python3 /Users/he/.agents/skills/project-doc-library/scripts/audit_template_policy.py --skill-root /Users/he/.agents/skills/project-doc-library
python3 /Users/he/.agents/skills/project-doc-library/scripts/verify_project_docs.py --root "$PWD"
python3 /Users/he/.agents/skills/project-doc-library/scripts/lint_prose.py --root "$PWD"
git diff --check
```

The template policy audit checks the protected protocol templates and their project-neutrality. The structural verifier covers the lifecycle tree, active Agent Note format, archive metadata, and links in noncanonical project docs. These checks do not validate the truth of architecture prose or replace human review of historical material.

## CI

The GitHub Actions CI workflow runs on macOS, installs JDK 21, rebuilds the ECH prebuilt library, and runs `./gradlew test --stacktrace`. The release workflow packages the DMG for tags matching `v*`.
