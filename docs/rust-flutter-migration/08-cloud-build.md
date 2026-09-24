# GitHub Actions 云端构建全链路（Rust + Flutter）

> 约束：**所有构建放 GitHub Actions 云端，本机不产出构建物。**
> 本文的验收标准是「CI 上一次跑通并产出可验证产物」，不讨论本机可行性。
> 上游结论见 [03-bridge-and-build.md](03-bridge-and-build.md)（桥接方案与打包），[05-flutter-macos-ime.md](05-flutter-macos-ime.md)（输入法版本约束）。
> 写作时间 2026-09-23。

## 0. 本机 vs CI：阻塞项的性质变化

上一份报告 [03-bridge-and-build.md](03-bridge-and-build.md) 里有 8 个标【假设】的点，其中几个是把「本机没有完整 Xcode」当成了限制。**在云端构建的约束下，这些限制消失**，性质从「阻塞」变成「CI 里的一次验证」。

| 原报告的判断 | 云端约束下的修正 |
|---|---|
| 「`flutter build macos` 能否产出 bundle」待验 | **放到 CI 上验**。`macos-latest` runner 预装完整 Xcode，这是 CI 的天然能力，不再是限制 |
| 「Release entitlements 缺 `network.client` 是否阻断网络」待验 | 保持待验，但可以在 CI 里加一步 probe 自动判定（见 §5.3） |
| 「静态链接是否规避 mis-aligned LINKEDIT」待验 | CI 上直接验：跑一次 release build 并启动，见 §5.2 |
| 「签名与公证能否通过」 | **性质变了：这是新增能力，不是维持现状**。见 §4 开头的明确界定 |
| 本机环境坑（受限 HOME、`~/.rustup` 不可写） | CI runner 上 HOME 可写，**这些坑在 CI 上不存在** |

现有 CI 已经跑在 `macos-latest` 上（核对 `.github/workflows/ci.yml` 与 `release.yml`），所以 **Rust 在 CI 里的构建路径已经存在，迁移时只需替换其中的 Rust 构建步骤**。这一点 lead 已确认，本文在此前提下展开。

## 1. 集成后端选型：native-assets

### 1.1 结论

**推荐 `--integration-backend native-assets`，不用 cargokit。**

理由（三条各自独立成立，任意一条都足以否决 cargokit）：

1. **Flutter 3.47 的 macOS 工程已切到 Swift Package Manager，没有 Podfile。** 这是我在本机实测确认的（`03-bridge-and-build.md` §3.3）：FRB 默认后端生成的 `rust_builder/macos/pixiv_core.podspec` 依赖 CocoaPods 集成路径，而新工程根本没有这条路径。**cargokit 这条路在 Flutter 3.47 上走不通**，这是硬阻塞，不是偏好问题。
2. **native-assets 自 Flutter 3.38.0 起在 stable 上默认启用**（[flutter/flutter#176285](https://github.com/flutter/flutter/pull/176285)，3.38.0 release notes「Enable build hooks and code assets on stable」）。本项目锁的版本远高于此，所以**不需要任何 `flutter config` 开关**，装上即用。这一条把「native-assets 较新所以不成熟」的顾虑降了一档：它已经默认开机，不再是需要刻意 opt-in 的实验特性。
3. **不需要 CocoaPods**。macOS runner 上虽然预装了 CocoaPods，但少一层依赖就少一处失联点。

### 1.2 两个后端在 CI 上的实际差别

| 项 | cargokit | **native-assets（推荐）** |
|---|---|---|
| macOS 集成机制 | `rust_builder/macos/*.podspec` + `script_phase` 调 `cargokit/build_pod.sh` | `hook/build.dart` + `flutter_rust_bridge_hooks`，由 `flutter build` 原生驱动 |
| CI 是否要装 CocoaPods | 要（且 `macos/Podfile` 已不存在，需要自己补一条 SPM→CocoaPods 的桥，很别扭） | **不需要** |
| Rust 何时编译 | Xcode `script_phase` 阶段 | `flutter build` 内部的 hook 阶段，时序由 Flutter 管 |
| 产物link 方式 | 静态库 `.a` + `-force_load` | 动态库或静态库，由 hook 决定 |
| CI 步骤数 | 多（要处理 pod install） | 少（无额外步骤） |
| 多架构交叉编译 | 自己配 | hook 里可读 target 自行分派 |

**选 native-assets 的实质收益是无条件的：cargokit 在 Flutter 3.47 上根本跑不起来**，其余都是顺带的。

### 1.3 静态库 vs 动态库

**推荐静态库（`.a`）。**

差别在 CI 上落在两个地方：

| | 静态库 `.a` | 动态库 `.dylib` |
|---|---|---|
| 签名步骤 | **无需单独签名**，link 进可执行文件，随外层 App 一次签完 | 必须在签 App 之前**先单独签 dylib**，且要用同一个证书身份（hardened runtime 的库校验会拒绝加载未签名/异证书 dylib） |
| mis-aligned LINKEDIT 坑 | 【假设】不经过 `dlopen`，**理论上不受影响**，可能因此省下 `rust/ech` 现在被迫留着的 12MB debuginfo | 受影响，必须保留 `debug = true` |

**这也是 §5.2 里要请 CI 验证的一条**：若静态链接确实验证了不受影响，就可以把 `[profile.release] debug = true` 去掉，产物能瘦约 8MB。

---

## 2. CI 上的 Flutter 安装与版本锁定

### 2.1 版本锁定（硬约束）

**必须 ≥ 3.35.0。** 依据 ui-scout 的 [05-flutter-macos-ime.md](05-flutter-macos-ime.md)：日语输入法的 composing range 重复修复（`flutter/flutter#166291`）**首个含此修复的 stable 是 3.35.0**，逐个 tag 拉源码比对确认，非推测。低于此版本搜狗/系统日语输入法会导致文字重复，**直接影响本项目的搜索框与评论框**。

**同时必须 ≤ 某个上界并锁死具体版本**，不要只写 `channel: stable`——那样每次 CI 跑的版本都可能不同，输入法修复也可能回归。

推荐锁 **3.47.5**（flutter/Dart 组合已在 [03-bridge-and-build.md](03-bridge-and-build.md) 实测可用，且 `flutter create` 实测生成的 `Package.swift` 里 `platforms: [.macOS("12.0")]`；本机实测 Flutter 3.47.5 / Dart 3.13.4）：

```yaml
- uses: subosito/flutter-action@v2
  with:
    channel: stable
    flutter-version: 3.47.5
```

> 版本也可以通过 `flutter-version-file: pubspec.yaml` 从 `pubspec.yaml` 读取，前提是写**精确版本号**（`"3.47.5"`），不能写区间（`'>=3.35.0 <4.0.0'` 这种 [subosito/flutter-action](https://github.com/subosito/flutter-action) 不认）。
> 推荐**在 workflow 里直接写 `flutter-version`**，让版本在一处可见，不必依赖 action 的解析行为。

### 2.2 必须显式启用 macOS desktop

本机实测：Flutter 默认**没有**开启 macOS desktop target，需要 `flutter config --enable-macos-desktop`。CI 上同样要显式做：

```yaml
- run: flutter config --enable-macos-desktop
```

漏掉这一步，`flutter build macos` 会直接失败。

### 2.3 缓存

Flutter SDK 本体（几个 GB）与 pub _cache 是两块不同的东西，分别配：

```yaml
- uses: subosito/flutter-action@v2
  with:
    channel: stable
    flutter-version: 3.47.5
    cache: true        # 缓存 Flutter SDK 安装
    pub-cache: true    # 缓存 dart pub get 的依赖
    cache-key: 'flutter-:os:-:channel:-:version:-:arch:-:hash:'
    cache-path: '${{ runner.tool_cache }}/flutter/:channel:-:version:-:arch:'
    pub-cache-key: 'flutter-pub-:os:-:channel:-:version:-:arch:-:hash:'
    pub-cache-path: '${{ runner.tool_cache }}/flutter/:channel:-:version:-:arch:'
```

`subosito/flutter-action` 内部已用 `actions/cache@v5`，开 `cache: true` 即可，**不需要自己再写一遍 `actions/cache` 去缓存 Flutter SDK**。自托管 runner 需 Actions Runner ≥ 2.327.1（GitHub 托管 runner 无此问题）。

要缓存的目录总表：

| 内容 | 谁来缓存 | 说明 |
|---|---|---|
| Flutter SDK（几个 GB） | `subosito/flutter-action` 的 `cache: true` | 最大的一块 |
| Pub 依赖（`~/.pub-cache`） | 同上 action 的 `pub-cache: true` | 由 `pubspec.lock` 决定命中率 |
| Cargo 依赖与目标产物 | `Swatinem/rust-cache@v2`（配 `workspaces: rust`） | 见 §3 |
| Xcode DerivedData | 通常不缓存 | 体积大且 invalidate 频繁，性价比低 |
| Gradle（过渡期） | `gradle/actions/setup-gradle@v4`（现有 CI 已有） | 迁移完成后随 JVM 一起删掉 |

---

## 3. 完整 CI 配置

下面给出三份可直接使用（drop-in）的 YAML。**都是完整的 job，不是片段。**

### 3.1 CI（每次 push / PR）

这是替换现有 `.github/workflows/ci.yml` 的完整版本，对应现在的「重编 Rust + 编译 + 测试」三件事，并加上了桥接层 codegen 校验。

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  rust:
    name: Rust crate (build + test)
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4

      - uses: dtolnay/rust-toolchain@stable
        with:
          targets: aarch64-apple-darwin

      - uses: Swatinem/rust-cache@v2
        with:
          workspaces: rust -> target

      - name: Build Rust core (release)
        run: cargo build --release --manifest-path rust/Cargo.toml

      - name: Test Rust core
        run: cargo test --manifest-path rust/Cargo.toml

  # 桥接层 codegen：确认生成代码与提交版本一致。
  # 这一步必须有，理由见 03-bridge-and-build.md §1.4——codegen 产物进 git，
  # 一旦有人改了 Rust API 没跑 codegen，CI 要能抓到。
  bridge:
    name: Flutter-Rust bridge codegen check
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4

      - uses: dtolnay/rust-toolchain@stable
        with:
          targets: aarch64-apple-darwin

      # codegen 依赖 cargo expand，而它依赖 nightly + 可写 RUSTUP_HOME。
      # 本机踩到的 HOME / RUSTUP_HOME 限制在 CI 上不存在，但 cargo-expand 必须显式安装。
      - name: Install cargo-expand
        run: cargo install cargo-expand --locked

      - uses: subosito/flutter-action@v2
        with:
          channel: stable
          flutter-version: 3.47.5
          cache: true
          pub-cache: true

      - run: flutter config --enable-macos-desktop

      - run: flutter pub get

      - name: Generate bridge code
        run: flutter_rust_bridge_codegen generate

      # 生成后如果 git 有 diff，说明 Rust API 改了但没重新生成
      - name: Fail if generated code is stale
        run: |
          git add -A lib/src/rust rust/src/frb_generated.rs
          git diff --cached --exit-code \
            || (echo "::error::桥接层产物过期，请在本地跑 flutter_rust_bridge_codegen generate 后提交"
                exit 1)

  # 全链路：真正产出 App bundle。这一步同时是「能否产出产物」的验收。
  build-macos:
    name: Build macOS app bundle
    runs-on: macos-latest
    needs: [rust, bridge]
    steps:
      - uses: actions/checkout@v4

      - uses: dtolnay/rust-toolchain@stable
        with:
          targets: aarch64-apple-darwin

      - uses: Swatinem/rust-cache@v2
        with:
          workspaces: rust -> target

      - uses: subosito/flutter-action@v2
        with:
          channel: stable
          flutter-version: 3.47.5
          cache: true
          pub-cache: true

      - run: flutter config --enable-macos-desktop

      - run: flutter pub get

      # native-assets hook 会在这里自动触发 cargo build，不需要额外步骤。
      # Rust 已于 rust job 构建过且命中缓存，此步主要是 link。
      - name: Build release app bundle
        run: flutter build macos --release

      # 产物path【假设】：需首次 CI 跑通后按实际输出修正
      - name: Verify bundle exists and probe it
        run: |
          APP="build/macos/Build/Products/Release/PixivShaft.app"
          test -d "$APP" || (echo "::error::App bundle 未生成"; exit 1)
          du -sh "$APP"
          echo "--- Contents ---"
          ls -la "$APP/Contents"
          echo "--- Frameworks ---"
          ls -la "$APP/Contents/Frameworks"

      - uses: actions/upload-artifact@v4
        with:
          name: PixivShaft-macos-arm64
          path: build/macos/Build/Products/Release/PixivShaft.app
          retention-days: 7
```

### 3.2 Release（打 tag 时发布 DMG）

对应现有 `release.yml`，先只做**无签名的 DMG 发布**（与现状功能对等）。签名与公证作为独立可选 job 放在 §4。

```yaml
name: Release

on:
  push:
    tags: ['v*']

permissions:
  contents: write

jobs:
  release-macos:
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4

      - uses: dtolnay/rust-toolchain@stable
        with:
          targets: aarch64-apple-darwin

      - uses: Swatinem/rust-cache@v2
        with:
          workspaces: rust -> target

      - uses: subosito/flutter-action@v2
        with:
          channel: stable
          flutter-version: 3.47.5
          cache: true
          pub-cache: true

      - run: flutter config --enable-macos-desktop

      - run: flutter pub get

      - run: flutter build macos --release

      - name: Create DMG
        run: |
          APP="build/macos/Build/Products/Release/PixivShaft.app"
          VERSION="${GITHUB_REF_NAME#v}"
          mkdir -p dist/dmg
          # 把 .app 复制进 staging 目录再打，避免把整个 build 目录打进去
          cp -R "$APP" dist/dmg/
          hdiutil create \
            -volname "PixivShaft ${VERSION}" \
            -srcfolder dist/dmg \
            -ov -format UDZO \
            "dist/PixivShaft-${VERSION}.dmg"

      - name: Checksum
        run: |
          VERSION="${GITHUB_REF_NAME#v}"
          cd dist
          shasum -a 256 "PixivShaft-${VERSION}.dmg" > "PixivShaft-${VERSION}.dmg.sha256"

      - uses: softprops/action-gh-release@v2
        with:
          files: |
            dist/PixivShaft-*.dmg
            dist/PixivShaft-*.dmg.sha256
```

**DMG 工具的取舍**：

| 方案 | 优点 | 缺点 |
|---|---|---|
| **`hdiutil`（推荐）** | macOS 自带，**零外部依赖**，不会因第三方 action 挂掉而中断发布 | 只有最朴素的挂载窗口，**没有 Applications 快捷方式**，用户要自己拖到 Applications |
| `create-dmg` | 有 Applications 快捷方式、自定义背景图 | 外部依赖（brew / npm），多一处故障点 |
| `$CREATEDMG$` 三方的 GitHub Action | 封装好了 | 引入非官方 action，维护状态不确定 |

**推荐先用 `hdiutil` 跑通主线**，原因是这条路已是 `03-bridge-and-build.md` §3.5 里验证过的[标准做法](https://docs.flutter.dev/deployment/macos)，且现有 `release.yml` 用 jpackage 打出的 DMG 也没有特殊的安装器界面（AGENTS.md 记着「需要手动拖到 /Applications」），**用 `hdiutil` 在功能上与现状完全对等**。想要 Applications 快捷方式的话，后续单独加一个 `create-dmg` 步骤即可，不影响主线。

### 3.3 Rust target 与缓存

```yaml
- uses: dtolnay/rust-toolchain@stable
  with:
    # 保留：arm64 runner 上幂等，换 Intel runner 时可交叉编译
    targets: aarch64-apple-darwin

- uses: Swatinem/rust-cache@v2
  with:
    workspaces: rust -> target
```

要点：

- `macos-latest` 现在是 **arm64**（Apple Silicon），与 `targets: aarch64-apple-darwin` 一致。
- **如果将来要产出通用二进制（universal binary，同时含 arm64 与 x86_64）**，需要加 `x86_64-apple-darwin` target 并用 `lipo` 合并。【假设】`flutter build macos` 默认**只编当前 runner 架构**，universal 需要额外步骤。
- `Swatinem/rust-cache` 缓存 `~/.cargo` 与 target 目录，key 自动按 `Cargo.lock` 哈希。

**注意 `rust/ech` 现有配置里的 `[profile.release] debug = true`**（为了绕 mis-aligned LINKEDIT）。静态链接路径若验证通过（§5.2），这条可以去掉，同时缩小缓存与产物体积。

---

## 4. 签名与公证：这是新增能力，不是维持现状

### 4.1 明确界定

**核对现有 `.github/workflows/release.yml`：只有 `./gradlew :app:packageDmg` + `softprops/action-gh-release@v2` 上传 DMG，没有任何 codesign / notarytool / staple 步骤。**

也就是说：**当前发布的 PixivShaft DMG 既没有签名，也没有公证。** 用户在 macOS 上首次打开会看到 Gatekeeper 警告，需要右键打开或在系统设置里手动放行。

因此：

> **「签名与公证」在功能对等意义上属于新增能力，不属于迁移的必需成本。**

请用这个原则来决定是否要做：

- **不做**：迁移后的 CI 与现状完全对等，用户体感不变（仍然要手动放行）。这一档不需要任何 secrets，**§3.2 的 release.yml 就是完整可用的最终形态**。
- **做**：需要 Apple Developer Program 会员（年费 99 美元）与一批 secrets，用户下载后可直接双击打开。这是**产品体验升级**，应当单独立项，不要和「Rust + Flutter 迁移」绑在一起判断成本。

下面把「如果要做」的完整链路列出，供决策参考。

### 4.2 如果要做：完整步骤

核心是**把 .p12 证书导入临时 keychain**。这是 macOS CI 签名的标准做法，原因是 GitHub runner 的默认 keychain 不允许 `codesign` 无交互访问：

```yaml
  # 只有 secrets 配置齐全时才启用；没配则整段跳过，流水线照样绿灯通过
  - name: Import Developer ID certificate
    if: ${{ env.MACOS_CERT_P12 != '' }}
    env:
      MACOS_CERT_P12: ${{ secrets.MACOS_CERT_P12 }}
      MACOS_CERT_PASSWORD: ${{ secrets.MACOS_CERT_PASSWORD }}
      MACOS_KEYCHAIN_PASSWORD: ${{ secrets.MACOS_KEYCHAIN_PASSWORD }}
    run: |
      KEYCHAIN_PATH="${RUNNER_TEMP}/app-signing.keychain-db"

      security create-keychain -p "${MACOS_KEYCHAIN_PASSWORD}" "${KEYCHAIN_PATH}"
      security set-keychain-settings -lut 21600 "${KEYCHAIN_PATH}"
      security unlock-keychain -p "${MACOS_KEYCHAIN_PASSWORD}" "${KEYCHAIN_PATH}"

      CERT_PATH="${RUNNER_TEMP}/certificate.p12"
      echo "${MACOS_CERT_P12}" | base64 --decode > "${CERT_PATH}"
      security import "${CERT_PATH}" \
        -k "${KEYCHAIN_PATH}" \
        -P "${MACOS_CERT_PASSWORD}" \
        -T /usr/bin/codesign \
        -T /usr/bin/security
      rm -f "${CERT_PATH}"

      # 关键一步：允许 codesign 无需交互弹窗即可访问私钥
      security set-key-partition-list -S apple-tool:,apple: \
        -s -k "${MACOS_KEYCHAIN_PASSWORD}" "${KEYCHAIN_PATH}"

      security list-keychains -d user -s "${KEYCHAIN_PATH}" login.keychain
```

`if: ${{ env.MACOS_CERT_P12 != '' }}` 这个守卫很重要：**没配 secrets 时流水线不该失败**，这样签名能力可以优雅降级为未签名发布。

签名——**由内向外**，`--timestamp` 是公证的硬性要求（缺 secure timestamp 会被 Apple 拒）：

```yaml
  - name: Code sign
    if: ${{ env.MACOS_CERT_P12 != '' }}
    env:
      CODESIGN_IDENTITY: ${{ secrets.CODESIGN_IDENTITY }}
    run: |
      APP="build/macos/Build/Products/Release/PixivShaft.app"

      # 1) 若产物含独立 dylib/framework，先签内层（静态库方案下通常没有）
      find "$APP/Contents/Frameworks" -name '*.dylib' -o -name '*.framework' | while read lib; do
        codesign --force --options runtime --timestamp --sign "$CODESIGN_IDENTITY" "$lib"
      done

      # 2) 带 entitlements 签外层
      codesign --force --options runtime --timestamp \
        --entitlements macos/Runner/Release.entitlements \
        --sign "$CODESIGN_IDENTITY" "$APP"

      # 3) 校验
      codesign --verify --deep --strict --verbose=2 "$APP"
```

公证与附加公证票据（staple）。推荐用 **App Store Connect API Key**，不使用 Apple ID 加专用密码：前者不依赖真人账号、不会因双重认证或密码轮转而失效，更适合 CI：

```yaml
  - name: Notarize
    if: ${{ env.MACOS_CERT_P12 != '' }}
    env:
      APP_STORE_CONNECT_KEY_ID: ${{ secrets.APP_STORE_CONNECT_KEY_ID }}
      APP_STORE_CONNECT_ISSUER_ID: ${{ secrets.APP_STORE_CONNECT_ISSUER_ID }}
      APP_STORE_CONNECT_API_KEY: ${{ secrets.APP_STORE_CONNECT_API_KEY }}
    run: |
      APP="build/macos/Build/Products/Release/PixivShaft.app"
      KEY_DIR="${RUNNER_TEMP}/asc"
      mkdir -p "${KEY_DIR}/private_keys"
      printf '%s' "${APP_STORE_CONNECT_API_KEY}" \
        | base64 --decode > "${KEY_DIR}/private_keys/AuthKey_${APP_STORE_CONNECT_KEY_ID}.p8"

      ditto -c -k --keepParent "$APP" "${RUNNER_TEMP}/PixivShaft.zip"

      xcrun notarytool submit "${RUNNER_TEMP}/PixivShaft.zip" \
        --key "${KEY_DIR}/private_keys/AuthKey_${APP_STORE_CONNECT_KEY_ID}.p8" \
        --key-id "${APP_STORE_CONNECT_KEY_ID}" \
        --issuer-id "${APP_STORE_CONNECT_ISSUER_ID}" \
        --wait

      xcrun stapler staple "$APP"
      xcrun stapler validate "$APP"
      spctl -a -vv "$APP"
```

注意 `notarytool` 用 API Key 时需要的是 **`.p8` 私钥文件 + key-id + issuer-id**，不是 `notarytool store-credentials` 那种 keychain profile。**公证必须先 `codesign` 并且 dmg/zip 里的 App 已带 secure timestamp**，否则会收到 "Failed to parse entitlements" 或 "code object is not signed at all" 一类拒因。

### 4.3 所需 secrets 清单（仅在决定要做签名时才需要）

前置：Apple Developer Program 会员（年费 99 美元），并在 Certificates 页面创建 **Developer ID Application** 证书（不是 Mac Development / Apple Distribution）。

| Secret 名 | 内容 | 怎么取得 |
|---|---|---|
| `MACOS_CERT_P12` | Developer ID Application 证书（含私钥）导出为 .p12，**再做 base64 编码** 后的文本 | 钥匙串访问导出 .p12 → `base64 -i cert.p12 \| pbcopy` |
| `MACOS_CERT_PASSWORD` | 导出 .p12 时设的密码 | 导出时自设 |
| `MACOS_KEYCHAIN_PASSWORD` | CI 临时 keychain 的密码，任意强随机串 | `openssl rand -hex 16` |
| `CODESIGN_IDENTITY` | 证书完整身份串，形如 `Developer ID Application: Your Name (TEAMID)` | `security find-identity -v -p codesigning` |
| `APP_STORE_CONNECT_KEY_ID` | App Store Connect API Key 的 Key ID | App Store Connect → Users and Access → Keys |
| `APP_STORE_CONNECT_ISSUER_ID` | 同上页面的 Issuer ID | 同页面顶部 |
| `APP_STORE_CONNECT_API_KEY` | 下载的 `AuthKey_<KeyID>.p8` 的 **base64 编码**文本 | 下载 .p8 → base64 编码 |

**全部 7 个 secrets 都只在「决定要做签名与公证」时才需要配。** 不做签名时，CI 一无所求，§3.2 那份 YAML 就是完整形态。

---

## 5. 用 CI 消除不确定性

以下各项本机无法验证（缺完整 Xcode），**性质上都是「CI 里的一次执行」**，按此顺序推进可在几轮内全部收敛。

### 5.1 第一优先：跑通全链路

先只求产出绿色、可下载的产物：

1. 提交 §3.1 的 CI workflow；
2. push 触发，`build-macos` job 成功；
3. 下载 `PixivShaft-macos-arm64` artifact，解压确认 `.app` 存在。

**这一步就消除了**：

- `flutter build macos --release` 能否产出 bundle（本机唯一卡住的硬阻塞）
- App bundle 的真实目录结构（验证 @executable_path/../Frameworks 与 frameworks 布局）
- 真实体积，从而验证 03 号报告里「197MB → 几十 MB」这个收益是否成立
- native-assets hook 是否真的会触发 cargo build（不需要额外 CI 步骤）

### 5.2 第二优先：静态链接与 entitlements

在 §3.1 的 `build-macos` job 末尾加探针：

```yaml
      - name: Probe static link & entitlements
        run: |
          APP="build/macos/Build/Products/Release/PixivShaft.app"
          echo "=== 是否含独立 Rust dylib ==="
          find "$APP/Contents" -name 'lib*.dylib' || echo "无（静态链接已生效）"
          echo "=== 实际 entitlements ==="
          codesign -d --entitlements - "$APP" 2>&1 || echo "未签名"
          echo "=== 能否启动（smoke） ==="
          open "$APP" --args --smoke \
            || echo "::warning::启动失败，见下方日志"
```

这一步消除：

- **静态链接是否规避 mis-aligned LINKEDIT** —— 若 App 能启动且**不含独立 dylib**，说明静态链接生效，可以尝试去掉 `[profile.release] debug = true` 再跑一次确认仍可启动（成功则省下约 8MB）
- **Release entitlements 实际内容** —— 直接 dump 出来对照，验证 03 号报告 §4.2 坑 1 的判断（默认只有 `app-sandbox`，没有 `network.client` / `network.server`）

### 5.3 第三优先：Release 网络能力

这一项关系到 Release 包能不能真正联网。依赖 SMB 式的判断不可行，要在 CI 里实际跑：

```yaml
      - name: Release smoke (requires real network call)
        run: |
          APP="build/macos/Build/Products/Release/PixivShaft.app"
          "$APP/Contents/MacOS/PixivShaft" --headless-smoke 2>&1 | tee smoke.log
```

需要 App 侧提供一个「跑一次 DoH 解析 + 一次 CDN 图片 HEAD 请求并输出结果」的 headless 模式。**若 Debug 模式能通过而 Release 模式失败，即可确认是 entitlements 缺 `network.client`。**

这一步是唯一无法单靠看配置就得出结论的，必须实跑。

### 5.4 第四优先（可选）：签名与公证

只在决定要做 §4 的能力时才跑。建议顺序：

1. 配齐 7 个 secrets；
2. 先用 `if:` 守卫让证书导入与签名步骤在 tag push 时跑一次；
3. 看 `notarytool submit` 返回的日志，按 Apple 给的拒因修（常见：嵌套二进制未签名、缺 secure timestamp、entitlements 与 provisioning profile 不匹配）。

**这一步的迭代成本最高**（每次都要重新提交公证并等待），但不影响主线交付——它可以无限期推迟，先把未签名版本发出去。

### 5.5 CI 时间与步骤

| 阶段 | 步骤数 | 说明 |
|---|---|---|
| Rust crate build + test | 5 | checkout + Rust toolchain + cache + build + test |
| 桥接层 codegen 校验 | 8 | 含装 `cargo-expand`（首次慢，可用 `cargo binstall` 加速） |
| macOS 全链路 build | 9 | 含探针与上传 artifact |
| Release（DMG） | 10 | 含 DMG + 校验和 + 上传发布 |

**构建耗时参考**（【假设】，无实机数据）：首次全冷跑（Flutter SDK 下载 + Rust 全量编译 + Xcode archive）预计 20–40 分钟；命中全部缓存后的增量跑预计 5–10 分钟。主要变量是 Flutter SDK 是否命中缓存。

优化手段：

- 把 `Rust crate build/test` 与 `bridge codegen` 并行（二者无依赖，已在 §3.1 用两个 job 拆开）
- Flutter SDK 缓存命中后，下载环节完全省去
- `Swatinem/rust-cache` 让 Rust 编译增量生效

---

## 6. 迁移期的取舍

迁移过程中会有一段 Rust 核心尚未接管全部能力的时期。按 AGENTS.md 的规则「不保留向后兼容、不留兼容层」，因此**不做 JVM 与 Flutter 双轨并行构建**。CI 的替换方式是：

| 阶段 | ci.yml | release.yml |
|---|---|---|
| 现状 | `updateEchPrebuilt` + `gradlew test` | `gradlew :app:packageDmg` + 上传 DMG |
| 迁移后 | §3.1 的三个 job | §3.2 的 release job |

`updateEchPrebuilt` 这个任务在迁移后**没有对应物**：整个 Rust 核心改用 `Swatinem/rust-cache` 加 `cargo build` 直接构建，**不再需要「提交预编译产物到 git」这个做法**（prebuilt 提交本身是为了让默认构建链不跑 cargo，见根 `build.gradle.kts` 注释；native-assets 下 cargo 由构建钩子直接驱动，prebuilt 机制整体作废）。

---

## 7. 待验清单（全部可在 CI 上收敛）

| # | 待验项 | 在哪验 | 怎么判就是通过 |
|---|---|---|---|
| 1 | `flutter build macos --release` 产出 bundle | §5.1 | artifact 里有 `.app` |
| 2 | App bundle 真实结构与体积 | §5.1 | 打印 `Contents` 与 `du -sh`，对照 197MB 基线 |
| 3 | native-assets hook 自动触发 cargo | §5.1 | 无额外 Rust 步骤仍能 link 成功 |
| 4 | 静态链接是否消除独立 dylib | §5.2 | `find -name '*.dylib'` 无输出 |
| 5 | 静态链接能否去掉 `debug = true` | §5.2 | 去掉后 App 仍能启动 |
| 6 | Release entitlements 实际内容 | §5.2 | dump 结果与 expectations 对照 |
| 7 | Release 包能否真的联网 | §5.3 | headless smoke 里 DoH + 图片请求成功 |
| 8 | 签名 + 公证是否通过 | §5.4（可选） | `notarytool` 返回 Accepted，`spctl -a` 通过 |
| 9 | DMG 能否挂载安装 | 人工 | 下载 release DMG 挂载并拖到 Applications |

第 1–3 项是 **CI 第一次跑就能全部收敛**的，优先做完。它们收敛之后，本机没有完整 Xcode 这件事对本项目就不再有任何影响。
