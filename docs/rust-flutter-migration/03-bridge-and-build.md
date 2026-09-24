# 桥接层与构建打包（Rust ↔ Flutter）

> 评估对象：把 PixivShaft Desktop 从 Kotlin + Compose Multiplatform 迁移到 Rust + Flutter。
> 本文只覆盖两件事：**Rust 与 Flutter 之间怎么通信**，以及 **macOS 上怎么把产物打成可分发安装包**。
> 写作时间 2026-09-23。

## 0. 本文的验证状态

本次评估**在本机实际装了 Flutter SDK 并跑了真实的代码生成与基准测试**，因此大部分结论带实测数据，不是纯粹的文档推测。

### 0.1 本机环境（实测）

| 项目 | 实测值 |
|---|---|
| macOS | 27.0（Build 26A428），arm64（Apple Silicon） |
| Flutter | **3.47.5**（channel stable，framework revision `6a19cca564`） |
| Dart | **3.13.4** |
| Rust | **rustc / cargo 1.96.0**（toolchain `stable-aarch64-apple-darwin`） |
| rustup 已装 target | 仅 `aarch64-apple-darwin`（**x86_64-apple-darwin 未安装**） |
| Xcode | **未安装**，只有 Command Line Tools（`xcode-select -p` = `/Library/Developer/CommandLineTools`） |
| CocoaPods | **未安装**（`which pod` 无输出） |
| JDK / jpackage | 21（`/opt/homebrew/opt/openjdk@21/bin/jpackage`） |

### 0.2 实际完成的验证

| 验证项 | 方式 | 结果 |
|---|---|---|
| Flutter 3.47.5 + Dart 3.13.4 可运行 | 下载 SDK 并校验 SHA256，`flutter --version` | 通过 |
| macOS desktop target 可启用 | `flutter config --enable-macos-desktop` | 通过 |
| 建立 Flutter macOS 工程 | `flutter create --platforms=macos` | 通过，`flutter devices` 认出 `macOS (desktop) • darwin-arm64` |
| 手写 `dart:ffi` + tokio 全链路 | 自建 Rust dylib（795KB）+ Dart 端 `NativeCallable.listener` 回调 | 通过，实测数据见 §2 |
| `flutter_rust_bridge` 2.13.0 codegen | `flutter_rust_bridge_codegen create` + `generate` | **通过**，按本项目真实 API 形态生成了桥接代码，见 §1.3 |
| 桥接层字节拷贝开销 | 64MB 分级基准测试 | 通过，数据见 §2.4 |
| `flutter build macos` 出 App bundle | `flutter build macos --release` | **未能完成**，阻塞原因见下 |

实测代码留在 `docs/rust-flutter-migration/verify/bridge-probe/`，可复跑：

```
rust/              自建的 Rust dylib（tokio，用于 §2 全部测量）
dart/probe.dart    异步、Stream、UI 响应基准
dart/bytes.dart    零拷贝 vs 拷贝基准
frb-api-pixiv.rs   喂给 FRB codegen 的 API 样本（§1.3）
```

### 0.3 未能完成的一项，及其重要性

`flutter build macos` 报：

```
Xcode not installed; this is necessary for iOS and macOS development.
```

本机只有 Command Line Tools，没有完整 Xcode，**因此无法产出真实的 Flutter macOS App bundle**。连带地，以下三项只能标为【假设】：

- App bundle 的最终目录结构与体积；
- 代码签名（codesign）与公证（notarization）的实际通过情况；
- 嵌入 Rust dylib 后能否通过 hardened runtime 的库校验。

**这不影响桥接方案选型**（§1 与 §2 的结论全部来自实测或源码分析），但**影响打包章节的可信度**。§3 的步骤来自官方 Flutter 文档与 Apple 文档，属文档级结论；§6 列出了立项前必须补验的清单。

另外记录两个在本机踩到、未来必然复现的环境坑：

1. **Flutter 拒绝在受限 HOME 下启动**：必须能创建 `~/.config/flutter` 与 `~/.dart-tool`。我们是靠把 `HOME` 重定向到工作区绕过的。CI 上要给这两个路径写权限。
2. **`flutter_rust_bridge_codegen generate` 依赖 `cargo expand`**，而 `cargo expand` 需要 nightly 工具链 + 可写 `~/.rustup`。这条依赖不是可选的，见 §1.4。

---

## 1. 桥接方案选型

### 1.1 结论

**推荐：`flutter_rust_bridge`（FRB）v2，版本锁 2.13.0。**

理由压缩成一条：**本项目有 4 类持续数据流（下载队列进度、列表分页、图片字节流、评论分页）需要双向从 Rust 推向 Dart，而 FRB 恰好把这四类都变成了一等公民——`StreamSink<T>` 直接映射成 Dart `Stream<T>`，代码生成一次性覆盖全部序列化样板；其余三条路线要么把这批样板变成人工负担，要么引入额外的 IPC 与编解码开销。**

推荐它的理由在于它解决的本项目具体痛点最多，与流行程度无关：

- 图片链路必须下沉 Rust（ui-scout 结论：Dart 无法禁用 SNI，dart-lang/sdk#44122 仍 open），所以**图片字节必须持续穿过桥接层**，这是本项目最重的负载。FRB 对此有专门支持，见 §2.4。
- 下载队列要「边下边推进度」，评论分页要「多条 next_url 游标并发」，这两件事在手写下会退化成自己设计一套消息协议，而 FRB 直接给了 `StreamSink`。

### 1.2 四条路线对比

| 维度 | **flutter_rust_bridge v2（推荐）** | 手写 `dart:ffi` + C ABI | Rust 独立进程 + stdio/JSON-RPC | protobuf over native port |
|---|---|---|---|---|
| 代码生成与维护成本 | Rust 侧写一个普通 `fn`，双方代码全自动生成，**零手写样板** | 每个函数手写 C 签名 + Dart typedef + 两侧类型转换，N 个 API = 3N 处同步维护 | 无代码生成，但要自己维护协议定义与进程生命周期 | 需要 `.proto` + `protoc` + 两侧代码生成，还要自己写 native port 投递层 |
| 异步流（Stream） | **`StreamSink<T>` → Dart `Stream<T>`，一等公民** | 无内建概念，要自己用 `NativeCallable.listener` + `ReceivePort` 搭 | 有（进程天然异步），但每条流要一套 JSON 帧协议 | 有，但要自己定义 streaming 消息 |
| 跨平台构建集成 | cargokit（默认）/ native-assets（新）；**macOS 路径实测有坑，见 §3.3** | 完全自控，但要自己写所有平台的构建钩子 | 最简单：子进程 path 随便放 | 中等，要为每个平台编译 protobuf 运行时 |
| 调试难度 | Rust panic 被 `catch_unwind` 捕获转成 Dart 异常并可带 **backtrace**；Rust 侧可附加 lldb | 同样可附加 lldb，但类型转换出错就是纯粹的内存不安全崩溃 | **最好**：两个进程可分别 attach，stdio 可直接人肉观察 | 中等，二进制帧不可读，要专门的解码工具 |
| 社区活跃度 | 活跃，crates.io 累计下载 736 万，2.13.0 发布于 2026-08-23 | 无「社区」，能力等于 Dart SDK 自带 | 无「社区」，全是自制 | protobuf 本身活跃，但「over native port」这个组合无成型库 |
| 本项目适配度 | 高：图片字节流有专用零拷贝路径 | 中：能做到，但样板量与 Gson 模型数量成正比 | **低**：本项目每条 API 都要跨进程序列化，且 stdio 管道会成为图片字节流的瓶颈 | 中低：帧越薄越好，图片字节流是它最不擅长的一档 |

**明确不推荐的两条：**

- **Rust 独立进程 + JSON-RPC**：本项目要在 UI 里同时滚动几十张图并推送下载进度，图片字节走 stdio 管道意味着每个字节都要经历「序列化 → 管道写 → 管道读 → 反序列化」，这是把最重的负载放在了最慢的通道上。**这条路线在「调试方便」上有真实优势，但用不起。**
- **protobuf over native port**：protobuf 的价值在于跨语言 schema 演进，本项目两侧都是自己写、同步发布，schema 演进不构成问题，白付一层编解码成本。

**手写 `dart:ffi` 的定位**：技术上完全可行（本文 §2 的实测数据就是用这条路线跑出来的），而且它是 FRB 的底层实现基础（FRB 生成的 `.dart` 里就是 `dart:ffi` 调用）。但把它当主方案意味着要为几十个 Gson 模型手写一一对应的别名（alias）转换。这个工作量在项目规模上是可观的，且每加一个 API 都要重复一遍。**它的正确用途是逃生舱**：当某个 API 的 FRB 代码生成失败或性能不达标时，对单个热点函数手写绕过，不适合全局采用。

### 1.3 flutter_rust_bridge 实测结果

用项目真实形态的 API（`rust/src/api/pixiv.rs`）跑了 codegen，映射关系如下：

| Rust 侧写法 | 生成的 Dart API |
|---|---|
| `pub async fn fetch_illust_page(next_url: Option<String>) -> Result<PageResult>` | `Future<PageResult> fetchIllustPage({String? nextUrl})` |
| `pub fn watch_download_queue(sink: StreamSink<DownloadProgress>)` | `Stream<DownloadProgress> watchDownloadQueue()` |
| `pub fn stream_ranking(stream: StreamSink<Vec<String>>)` | `Stream<List<String>> streamRanking()` |
| `pub fn stream_image_chunks(sink: StreamSink<Vec<u8>>, chunks: u32, chunk_size: usize)` | `Stream<Uint8List> streamImageChunks({required int chunks, required BigInt chunkSize})` |
| `pub fn load_image_bytes(size: usize) -> Vec<u8>` | `Future<Uint8List> loadImageBytes({required BigInt size})` |

生成产物规模：

```
rust/src/frb_generated.rs       784 行
lib/src/rust/frb_generated.dart 784 行
lib/src/rust/frb_generated.io.dart   219 行
lib/src/rust/frb_generated.web.dart  219 行
```

**关键确认**：本项目需要的四类数据流形态（`Future` 单次返回、对象 `Stream`、列表 `Stream`、字节 `Stream`）**全部被正确生成**，无需任何手写补丁。`struct DownloadProgress` 自动生成了带 `hashCode` / `==` 的 Dart class，`Option<String>` 正确映射为 `String?`。

需要注意的类型映射细节：

- Rust `usize` / `u64` → Dart **`BigInt`**（不是 `int`）。本项目的分页 `offset`、字节计数都会变 `BigInt`，Dart 侧写法要改，这是一处真实的迁移成本。
- Rust 的 `anyhow::Result` → Dart 抛出 `AnyhowException`，自动捕获，**不需要手写错误码**。

### 1.4 FRB 的隐藏依赖：`cargo expand`

这是本次评估发现的、任何公开文档都没强调的前提条件：

**`flutter_rust_bridge_codegen generate` 依赖 `cargo expand`，而 `cargo expand` 依赖 nightly 工具链。**

实测过程：在没有 `cargo-expand` 的情况下，`generate` 会**静默卡死**（无报错、无超时，日志停在 `Running cargo expand`）。装上 `cargo-expand` 后，它又会去 `${rustup}/tmp` 下载一个版本固定的 toolchain（模板里 `rust-toolchain.toml` 固定的是 `1.93.1`），要求 `RUSTUP_HOME` 可写。

也就是说 **CI 上必须保证：`cargo-expand` 已安装 + 存在 pinned toolchain + `RUSTUP_HOME` 可写**。这与当前 jpackage 链路「只要有 JDK 和 Gradle 缓存就行」相比是净增加的环境表面积。

---

## 2. 异步数据流能否承载

这一节的结论全部基于**本机实测**，数据来自自建的 Rust dylib（tokio full features）与 Dart 端 `NativeCallable.listener` 回调的组合，即手写 `dart:ffi` 的最低层实现。FRB 搭在这同一套原语之上，因此这些数字是 FRB 性能的**下界**（FRB 会多一些序列化开销，但同数量级）。

### 2.1 tokio runtime 与 Flutter UI isolate 的隔离

**结论：完全隔离，前提是不要把耗时函数标成 `#[frb(sync)]`。**

FRB 的线程模型（从 `flutter_rust_bridge-2.13.0` 源码确认）：

- 默认执行器是 `SimpleExecutor`，它内部持有一个**自建的线程池**和一个**自建的 tokio runtime**（`SimpleAsyncRuntime(tokio::runtime::Runtime)`）。
- `thread-pool` 与 `rust-async` 都是 **default feature**，无需额外开启。
- 普通 `async fn` → `FLUTTER_RUST_BRIDGE_HANDLER.wrap_async`，跑在 FRB 自己的 tokio runtime 上，**与 Dart 的任何 isolate 无关**。

实测确认了这一点，并量化了「不隔离」的代价：

```
== 同步调用会占用调用线程 ==
block_cpu_sync(300ms) 阻塞调用线程 300ms
（等价于在 UI isolate 上跑 300ms，掉帧 18 帧）

== 异步卸载到 tokio ==
async_cpu 调用返回耗时 319us（未被阻塞）
async_cpu 结果 … 总耗时 304ms
```

这里的对照非常清楚：同一个 300ms 的 CPU 任务，同步调用会把调用方线程**整整占住 300ms**；走 `tokio::spawn` 后调用方 **319 微秒**就返回了。

对应到本项目的硬性纪律：

| 场景 | 应该怎么做 | 为什么 |
|---|---|---|
| pixiv API 请求 | `async fn` + reqwest/tokio | FRB 自动丢到它的 tokio runtime |
| 下载队列 | `StreamSink<DownloadProgress>` 推送 | Rust 侧永远不在 UI 线程 |
| 图片解码 / Ugoira 转 GIF | `async fn` 内用 `tokio::task::spawn_blocking` | CPU 密集任务会占满 tokio 的异步工作线程，必须显式走阻塞池 |
| **本地 DAO / 小状态查询** | 可以 `#[frb(sync)]` | 同步调用没有线程池切换开销，实测往返在百微秒级以下 |

**唯一会卡 UI 的情况**是误用了 `#[frb(sync)]`：它会在 UI isolate 线程上同步执行。

### 2.2 持续数据流的写法

FRB 里就一个 `StreamSink<T>` 参数，Dart 侧直接拿到 `Stream<T>`，可以照常 `listen` / `await for` / 喂进 `StreamBuilder`：

```rust
pub fn watch_download_queue(sink: StreamSink<DownloadProgress>) -> Result<()> {
    for i in 0..100 {
        sink.add(DownloadProgress { /* ... */ })?;
        std::thread::sleep(Duration::from_millis(10));
    }
    Ok(())
}
```

```dart
RustLib.instance.api.crateApiPixivWatchDownloadQueue()
    .listen((p) => setState(() => progress = p.sentBytes / p.totalBytes));
```

底层传输是 **Dart native port**（`serializeNativePort(sendPort.nativePort)`）+ **SSE 序列化编解码器**（`default_stream_sink_codec = SseCodec`）。这一点很重要：**FRB 的 Stream 走 native port 消息推送，没有定时轮询开销。**

### 2.3 吞吐量与 UI 响应实测

| 场景 | 实测结果 |
|---|---|
| 下载进度（小包，无节流洪峰） | **14.3 万帧/秒**，单帧 7.0us |
| 节流推送（2k 帧 / 500us 间隔） | 精确按节流执行，单帧 1770us |
| 图片块（200 帧 × 256KB） | **11765 帧/秒**，单帧 85us → **约 3.07 GB/s 有效载荷** |
| 洪峰（5 万帧 × 4KB） | **23 万帧/秒，900 MB/s** |

**UI 是否被数据流压垮**（这一项对本项目最关键，因为图片列表滚动时进度推送和图片字节同时来）：

```
洪峰 50000 帧 x 4KB 期间：共 13 次 UI tick,
间隔中位数 16023us, 最大 17053us (→ UI 未卡死)
```

在 **900 MB/s 的持续洪峰**冲击下，UI isolate 的 16ms 周期 timer 抖动保持在 **16.0ms 中位数 / 17.1ms 最大值**，也就是**几乎零抖动**。原因很直接：回调走 tokio 线程投递 native port 消息，UI isolate 只按自己的节奏去取消息队列。

**结论：本项目的异步数据流需求（下载队列、分页加载、图片字节流、评论分页全部并发）在桥接层不构成瓶颈。** 瓶颈在网络 IO 与图片解码，不在 Rust↔Dart 通道。

但这里有一个必须知道的前提：**FRB 的 `StreamSink` 没有背压（backpressure）**。上面「14.3 万帧/秒」的数字说明生产端可以远超消费端。本项目的下载队列必须在 Dart 侧用 `StreamController` 或 `listen` 的 `cancelOnError` + 暂停/恢复做显式节流，否则：

- 进度更新过快 → `setState` 风暴 → 虽非 native 层卡顿，但 Dart widget 重建会吃掉帧预算；
- 图片字节块堆积 → Dart 堆内存暴涨。

正确做法是**生产端节流**：下载进度按 100ms 粒度或按字节百分比（每 1%）推一次，避免每个网络包都推一次。上面的「节流 500us」实测显示，加了间隔后 UI 侧完全按节奏工作。

### 2.4 图片字节流的拷贝开销（本项目最重的负载）

这条单独拿出来，因为它是唯一有真实数量级差异的一档。

64MB 单次传递，5 次取中位数：

| 路径 | 耗时 | 说明 |
|---|---|---|
| **A 零拷贝**（`asTypedList` 直接映射） | **1 us** | Rust `Vec<u8>` 泄漏所有权给 Dart，Dart 建一个不拥有内存的 view，**0 次 memcpy** |
| **B 拷贝到 Dart 堆**（`Uint8List.fromList`） | **5296 us**（5.30ms） | 桥接层序列化默认走的这条路 |
| C 纯 Dart 堆内 `setRange`（对照组） | 1258 us | 与 FFI 无关的上限参考 |

换算到本项目真实尺度：

```
每 MB 拷贝成本 ≈ 82.8us  → 一张 8MB 图 ≈ 0.66ms
B/C 比 = 4.21x（跨 FFI 边界的拷贝比纯堆内拷贝贵 4.2 倍）
```

**结论：拷贝一次的绝对开销不大（8MB 图 0.66ms），在 60fps 的一帧预算（16.7ms）里只占 4%。**

所以这里的判断要诚实：**不必为了省掉这 0.66ms 而上零拷贝**。真正值得使用零拷贝的场景是：

1. **批量很大时**：首页一次加载几十张图 × 每张 8MB = 数百毫秒，这时零拷贝有意义；
2. **GC 压力**：每次拷贝都在 Dart 堆上产生一个 8MB 对象并等待回收，这在长时间滚动列表时会造成明显的 GC 抖动，而零拷贝不产生 Dart 堆对象。

FRB 对此的支持：FRB 生成的 Dart 代码里 distro 有 `RustVecU8` 类型，它内部就是 `ptr.asTypedList(length)`——**零拷贝路径在 FRB 里是现成的**，代价是必须手动 `dispose()`，否则内存泄漏：

```dart
// 源码注释原文："Must call `dispose` manually, otherwise the memory will be leaked."
final bytes = await api.loadImageBytes(size: BigInt.from(n));
try { /* 使用 bytes */ } finally { bytes.dispose(); }
```

**给图片链路的具体建议**：图片字节走 `StreamSink<Vec<u8>>` 分块（如 256KB/块）流向 Dart，由 Dart 喂给 `Image.memory`。实测 256KB 分块可达 **3.07 GB/s**，远超网络带宽，不构成瓶颈。是否需要零拷贝取决于「是否观察到 GC 抖动」，单次 0.66ms 不构成理由。**先把正常的拷贝路径跑通，测得卡顿再上零拷贝**——过早引入 `dispose()` 生命周期管理会带来真实的泄漏风险（漏掉一次 `dispose` 就永久泄漏）。

---

## 3. macOS 二进制集成与打包

### 3.1 Flutter macOS 产物结构

从实测生成的 Xcode 工程确认（`macos/` 目录与 `project.pbxproj`）：

```
Runner.xcodeproj      ← Xcode 工程，flutter build 时由 xcodebuild 驱动
Runner.xcworkspace
Flutter/ephemeral/    ← 每次 build 重新生成，含依赖与环境变量
Flutter/GeneratedPluginRegistrant.swift
Runner/
  AppDelegate.swift
  DebugProfile.entitlements
  Release.entitlements
  Info.plist
  MainFlutterWindow.swift
```

关键构建设置（实测取自 pbxproj）：

```
MACOSX_DEPLOYMENT_TARGET = 12.0
LD_RUNPATH_SEARCH_PATHS = @executable_path/../Frameworks
CODE_SIGN_ENTITLEMENTS = Runner/DebugProfile.entitlements | Runner/Release.entitlements
CODE_SIGN_STYLE = Automatic
```

产物 App bundle 结构（**【假设】**，因本机无 Xcode 未能实机产出；依据 Flutter 官方文档与 `LD_RUNPATH_SEARCH_PATHS` 推断）：

```
YourApp.app/
  Contents/MacOS/YourApp                    ← 可执行文件
  Contents/Frameworks/
    App.framework/                          ← 编译后的 Dart 代码
    FlutterMacOS.framework/                 ← Flutter engine
  Contents/Resources/
  Contents/Info.plist
```

`@executable_path/../Frameworks` 的 runtime search path 说明：**放在 `Contents/Frameworks/` 下的 dylib 会被自动找到**，这是 Rust dylib 的落点。

### 3.2 Rust 库的四种集成方式

| 方式 | 机制 | 评价 |
|---|---|---|
| **a. CocoaPods podspec** | `rust_builder/macos/*.podspec` + `script_phase` 调 `cargokit/build_pod.sh`，`OTHER_LDFLAGS` 里 `-force_load ${BUILT_PRODUCTS_DIR}/lib*.a` | **FRB 默认方案，但在 Flutter 3.47 上已过期，见 §3.3** |
| **b. Xcode Run Script 构建阶段** | 在 `Runner` target 里加一个 `PBXShellScriptBuildPhase` 跑 `cargo build --release`，产物拷进 `Contents/Frameworks` | 可控性最好，不依赖 CocoaPods。**推荐，见 §3.4** |
| **c. xcframework** | `xcodebuild -create-xcframework` 把多个架构的 Rust 静态库封成 `.xcframework` 加进 Xcode 工程 | 适合要多平台分发、要上架 App Store 的场景。单机自用偏重，且每次 Rust 改动都要重建 xcframework |
| **d. CMake / 手动拷贝 + install_name_tool** | Cargo 产出 dylib 后手动拷进 bundle，用 `install_name_tool` 修 `@rpath` | 最底层，`flutter build` 之外还要自己串一条 CMake 或 shell 流水线 |

注意四种方式都能落成 **静态库（`.a`，`-force_load` 链接）** 或 **动态库（`.dylib`，拷进 Frameworks）** 两种形态。FRB 的 cargokit 默认走**静态库**，这对本项目其实是好事：**静态链接进可执行文件，就绕开了「dylib 也要单独签名 + hardened runtime 拒加载未签名 dylib」这一整类麻烦**（见 §4.2）。

### 3.3 重要实测发现：Flutter 3.47 已弃用 CocoaPods，但 FRB 默认方案还依赖它

这是本次评估最有价值的一个发现，直接影响方案实施：

**用 FRB 默认后端创建的工程里，`macos/` 目录下没有 Podfile，但 `rust_builder/macos/` 下有一个 podspec。**

实测：

```
frb_probe/macos/Podfile          → 不存在（ls: No such file or directory）
frb_probe/macos/Podfile.lock     → 不存在
frb_probe/rust_builder/macos/pixiv_core.podspec  → 存在
本机 which pod                    → 无输出（CocoaPods 未安装）
```

原因是 **Flutter 3.47 的 macOS 端已经切换到 Swift Package Manager**：

```
macos/Flutter/ephemeral/Packages/FlutterGeneratedPluginSwiftPackage/
    Package.swift            ← platforms: [.macOS("12.0")]
    Sources/FlutterGeneratedPluginSwiftPackage/*.swift
```

**旧教程里「Flutter macOS 项目一定有 macos/Podfile」这件事已经不成立了。** CocoaPods 时代的那个 podspec 钩子（`script_phase` + `-force_load`）依赖 Xcode 的 CocoaPods 集成路径，而新工程根本没有这条路径。

FRB 提供了第二个后端来回避这个问题：

```
--integration-backend <VALUE>
  cargokit:      (default) Use Cargokit and generated platform scaffold
  native-assets: Use Dart/Flutter Native Assets build hooks
```

实测 `native-assets` 后端生成的工程：

- **不生成 `rust_builder/` 与任何 podspec**；
- 改为根目录 `hook/build.dart` + 依赖 `flutter_rust_bridge_hooks: 2.13.0`：

```dart
// hook/build.dart（实测生成内容原文）
import 'package:flutter_rust_bridge_hooks/flutter_rust_bridge_hooks.dart';

void main(List<String> args) async {
  await build(args, (input, output) async {
    await const FlutterRustBridgeNativeAssetsBuilder(cratePath: 'rust')
        .run(input: input, output: output);
  });
}
```

**打包选型结论：本项目应当用 `native-assets` 后端**，因为它贴合 Flutter 3.47 的 SPM 现状，不引入 CocoaPods 这个已在本机缺失、且长期看处于退场路径上的依赖。§1.4 提到的 codegen 必须在 GOPATH 之外另说，`native-assets` 后端的工程布局（`rust/` 在根目录、`hook/` 独立）也更干净。

注意：**native-assets 是较新的路径**（`flutter_rust_bridge_hooks` 与 `flutter_rust_bridge` 同版本 2.13.0 同步发布），成熟度不如 cargokit。选它意味着要接受「踩在较新的东西上」，但 cargokit 那条路的 podspec 在 Flutter 3.47 上已经是死路。**这是本项目必须承担的一个选型风险，写在 §5 的风险清单里。**

### 3.4 DMG / 签名 / 公证完整流程

以下为**文档级结论**（依据 Flutter 官方文档与 Apple 文档整理），本机因无 Xcode **未能实机跑通**，标【假设】。

**第一步：产出 Release App bundle**

```bash
flutter build macos --release
# 产物位置【假设】：build/macos/Build/Products/Release/PixivShaft.app
```

构建阶段要先让 Rust 侧产物就位（这一步是新增的，对应 §3.2 方式 b）：

```bash
cargo build --release --manifest-path rust/Cargo.toml
cp rust/target/release/libpixiv_core.a <App>/Contents/Frameworks/   # 静态库随链接
# 若是 dylib，则还要加一步 install_name_tool 修 @rpath
```

**第二步：代码签名（codesign）**

Release 需要 hardened runtime，且**签名要由内向外**做（先签内层 dylib / framework，再签外层 App），不要图省事用 `--deep`（Apple 官方明确不推荐，`--deep` 只对直接依赖有效且会漏掉嵌套资源）：

```bash
IDENTITY="Developer ID Application: Your Name (TEAMID)"

# 1) 内层：先签每个嵌入的 framework / dylib
codesign --force --options runtime --timestamp \
  --sign "$IDENTITY" \
  "PixivShaft.app/Contents/Frameworks/libpixiv_core.dylib"

codesign --force --options runtime --timestamp \
  --sign "$IDENTITY" \
  "PixivShaft.app/Contents/Frameworks/FlutterMacOS.framework"

# 2) 外层：带 entitlements 签整个 App
codesign --force --options runtime --timestamp \
  --entitlements macos/Runner/Release.entitlements \
  --sign "$IDENTITY" \
  "PixivShaft.app"

# 3) 校验
codesign --verify --deep --strict --verbose=2 PixivShaft.app
```

`--timestamp` 是公证的硬性要求（缺 secure timestamp 会被拒）。

**第三步：公证（notarization）**

先把待签物打包成 zip（DMG 也可直接提交），再用 `notarytool`：

```bash
# 一次性：把凭据存进 keychain
xcrun notarytool store-credentials "pixivshaft-profile" \
  --apple-id "you@example.com" \
  --team-id "TEAMID" \
  --password "app-specific-password"

# 提交 + 等待
ditto -c -k --keepParent PixivShaft.app PixivShaft.zip
xcrun notarytool submit PixivShaft.zip \
  --keychain-profile "pixivshaft-profile" --wait

# 成功后钉合（staple）
xcrun stapler staple PixivShaft.app

# 本地校验 Gatekeeper 是否放行
spctl -a -vv PixivShaft.app
```

**第四步：打 DMG**

```bash
hdiutil create -volname "PixivShaft" -srcfolder PixivShaft.app \
  -ov -format UDZO PixivShaft.dmg
```

社区常用 `create-dmg` 来做带 Applications 快捷方式、自定义背景图的漂亮 DMG；此处也可以只用 `hdiutil`（无额外依赖，代价是没有背景排版）。

### 3.5 换掉 jpackage 之后的工作量

当前链路（实测自 `app/build.gradle.kts`）：

```
./gradlew :app:packageDmg
  → Compose Gradle 插件 → jpackage → DMG
  + copyEchLib 任务把 libech.dylib 复制到 build/app-resources
  + createDistributable 的 doLast 里手动把 dylib 拷进 Contents/Resources
  + nativeDistributions { modules("java.sql","jdk.unsupported") }
  + jvmArgs 里 4 个 --add-opens
```

**消失的部分**：

- jpackage 的 `modules("java.sql","jdk.unsupported")` 补丁
- 4 个 `--add-opens java.desktop/...` 启动参数
- 72MB 的 JVM runtime 打包
- jpackage 本身的 DMG 生成逻辑

**新增的部分**：

| 新增项 | 说明 | 预估规模 |
|---|---|---|
| Xcode Run Script 或 native-assets hook | 每版 build 先调 `cargo build` | 一个 hook/build.dart 或一段 Xcode 脚本（约 10–20 行） |
| `hook/build.dart`（若走 native-assets） | FRB 已生成，通常不需改 | 0（DRB 生成） |
| entitlements 调整 | 必须补 `com.apple.security.network.client`（见 §4.1） | 改一个 plist 的两三个 key |
| 签名脚本 | 由内向外 codesign 内层库再签 App | 约 20–30 行 shell |
| 公证 + 钉合脚本 | `notarytool` + `stapler` | 约 10 行 shell |
| DMG 脚本 | `hdiutil` 或 `create-dmg` | 约 5 行 shell |
| **`cargo expand` + pinned nightly toolchain**（§1.4） | codegen 的隐藏前提 | CI 环境配置一次 |
| CI 上装 Flutter SDK | 几个 GB，且 `~/.config` `~/.dart-tool` 要可写 | CI 配置一次，每次构建要重新缓存 |

**净判断**：jpackage 那套是「一个 Compose 插件搞定一切、代价是几个手工补丁」；Flutter 这套是「每一步都显式、没有黑盒魔法，代价是脚本得上全套」。

脚本的**绝对量不大**（合计约 50–70 行 shell + 一个 hook 文件），但**它们从「0 行」变成了「必须有人写并长期维护」**。这部分是可控的一次性投入，主要成本在于**首次调通签名与公证**（这一步通常需要反复提交公证、看 Apple 返回的二进制拒因，经验上是几个来回），以及 CI 环境要额外装 Flutter。

---

## 4. 现在的坑会不会消失

**总体回答：会换坑，但新坑的数量更少、性质更轻。诚实地说，消失 4 个明确坑，新增 4 个新坑，另有 1 个 Rust 侧既有的坑会被保留。**

### 4.1 Compose 侧的坑：4 个全部消失

| 现有坑 | 迁移后 |
|---|---|
| `java.sql` 模块缺失导致 DMG 启动崩溃 | **彻底消失**。没有 Java 模块系统了 |
| `jdk.unsupported` 模块缺失 | **彻底消失**。同上 |
| 4 个 `--add-opens java.desktop/...` | **彻底消失**。没有任何 JVM 内部 API 反射需求 |
| JNI 加载 dylib + 手动复制 `libech.dylib` + 传 `-Declibrary.path=` | **彻底消失**。不再有 JVM 这一层，Rust 产物由 Xcode 构建阶段/native-assets 直接 link 或 bundle |

这一栏是真实收益，没有夸大：**这四个坑的根源都是「JVM 被打成一个精简 runtime image 后缺东西」，换掉 JVM 就从根上没有了。**

附带一个体积收益，用 lead 提供的实测基线对比：现有 App **197MB**（JVM runtime 72MB + app 目录 117MB + Resources 7.7MB），其中 material-icons-extended 单包 36MB、sqlite-jdbc 13MB、skiko 8.7MB。Flutter + Rust 方案下 JVM runtime 的 72MB 和这批胖 jar **都不会存在**。【假设】最终体积落在几十 MB 量级，但具体数字要等实机能出 bundle 才能给。

### 4.2 Flutter 侧的新坑：4 个，逐个说明

**坑 1：entitlements 与 App 沙箱（新坑，有实际破坏力）**

实测 Flutter 3.47 模板生成的 entitlements：

`DebugProfile.entitlements`：
```xml
<key>com.apple.security.app-sandbox</key><true/>
<key>com.apple.security.cs.allow-jit</key><true/>
<key>com.apple.security.network.server</key><true/>
```

`Release.entitlements`：
```xml
<key>com.apple.security.app-sandbox</key><true/>
```

**Release 版的 entitlements 只有沙箱，既没有 `network.client` 也没有 `network.server`。** 本项目是一个要发 HTTPS 请求、还要开本地 HTTP server 收 OAuth 回调、还要跑 DoH（加密 DNS）的应用，这几件事在 Release 的沙箱配置下**都会失败**。而 Debug 版恰好有 `network.server`，所以**开发调试时一切正常，打出 Release 包才发现网络全废**——这是一个典型的「只在 Release 爆」的坑。

必须补（这一条要在立项前就写进 checklist）：

```xml
<key>com.apple.security.network.client</key><true/>   <!-- API / 图片 / DoH 都要 -->
<key>com.apple.security.network.server</key><true/>   <!-- OAuth 回调本地 HTTP server 要 -->
<key>com.apple.security.files.user-selected.read-write</key><true/>  <!-- 下载选目录 -->
<key>com.apple.security.files.downloads.read-write</key><true/>      <!-- 下载到「下载」文件夹 -->
```

对应 AGENTS.md 里记录的 macOS 菜单栏那条踩坑经验：结论同样适用于这里——**主菜单只允许在主线程修改**，这条约束来自 AppKit，不会因为换 Flutter 而消失。

**坑 2：hardened runtime 的库校验（新坑，但可规避）**

Release 签名要求 `--options runtime`（hardened runtime），它会启用**库校验（library validation）**：系统会拒绝加载没有用同一证书签名的 dylib。

也就是说：**如果 Rust 产物是 dylib 且没被单独签名，Release 包启动即崩溃**——症状和现在的「DMG 启动崩溃」一模一样，只是病因完全不同。

规避办法有两个，推荐第一个：

- **产物用静态库**（`.a`，`-force_load` link 进可执行文件）。静态库不是动态加载的，绕开整个库校验问题。**FRB 的 cargokit 默认就是静态库**，这条路已经是默认选项。
- 若要 dylib：按 §3.4 第二步的规则，**先用同一身份签它再签外层 App**。

**坑 3：`flutter build macos` 强依赖完整 Xcode（新坑，本机已实证）**

Compose Desktop 只需要 JDK + Gradle，**完全不需要 Xcode**。Flutter macOS 必须有完整 Xcode：

```
Xcode not installed; this is necessary for iOS and macOS development.
```

本机因为这个原因**没能产出 App bundle**。这是硬依赖，绕不开：任何要做 macOS 打包的机器（含 CI）都得装 Xcode（几十 GB 下载）。相比现在「brew 装个 openjdk@21 就能打包」，这是明确的门槛上升。

**坑 4：DMG/签名/公证链路要自己补齐（新坑，一次性）**

jpackage 把「打包 App → 签名 → 打 DMG」串成了一条 Gradle 命令。Flutter 只负责产出 App bundle，**签名、公证、DMG 三件事全部要自己补**（见 §3.5）。这是一次性投入，但它是真实新增的。

### 4.3 Rust 侧：1 个既有的坑会被完整继承

`rust/ech/Cargo.toml` 里那条注释记录得很清楚：

```toml
[profile.release]
opt-level = 3
# 必须保留 debuginfo：Rust 1.96 + macOS 新版 ld 在不带 debuginfo 的 release dylib 上
# 会产生 "mis-aligned LINKEDIT string pool"（dlopen 失败）
debug = true
# 代价是 dylib 体积增大（约 3.9MB -> 约 12MB），换取可加载
```

**这个坑不会消失，因为它属于 Rust + macOS 链接器，不属于 JVM。** 迁移后 `rust/ech` 会被吸收进更大的 Rust 核心 crate，这个 `[profile.release]` 配置要原样搬过去。

唯一的好消息：如果采用 **§4.2 坑 2 的静态库方案**，静态链接不经过 `dlopen`，**理论上不受这个 mis-aligned LINKEDIT 问题影响**（该问题只在动态加载时暴露）。这一条标【假设】——需要实机打个 release 静态 link 的 App 才能确认。**若验证成立，这 12MB 的 debuginfo 代价是可以省掉的。**

### 4.4 诚实的总账

| | Compose Desktop（现状） | Rust + Flutter（迁移后） |
|---|---|---|
| 明确踩过的坑 | 4 个（Java 模块 ×2、`--add-opens`、JNI dylib） | — |
| 继承的坑 | — | 1 个（Rust debuginfo / LINKEDIT，静态链接可能规避） |
| 新增的坑 | — | 4 个（entitlements、hardened runtime、强依赖 Xcode、打包链路自建） |
| 坑的性质 | 黑盒，病因在第三方插件内部，靠试错发现 | 白盒，每一步都显式可控，病因在 Apple 规范里可查 |

**直说：坑的总数没有减少，4 个换 4 个加 1 个继承。** 迁移的价值不在于「坑变少」，而在于：

1. 现有坑全部集中在**一个第三方 Gradle 黑盒**里，只能靠 AGENTS.md 记流水账；新坑每一步都是显式的、有 Apple 官方文档可查的。
2. 体积从 197MB 降到几十 MB 量级（JVM runtime 72MB + 胖 jar 全部消失）【假设】。
3. 网络层从 JNI + libech.dylib 这种「两层 runtime 拼接」变成单一 Rust 原生实现。

反过来，如果评估 Rust + Flutter 是为了「消灭现在的坑」，那结论会让期望落空。**这次迁移能拿到的收益是「原生二进制、更小的体积、统一的网络层」，打包本身并不会变简单。**

---

## 5. 风险清单

| 风险 | 等级 | 说明 |
|---|---|---|
| Flutter macOS GPU 渲染性能与 SwiftUI/AppKit 观感差异 | 中 | Flutter macOS 桌面端的文字渲染、滚动惯性、触控板手势与原生仍有差距。本项目大量依赖触控板 pinch 缩放、滚动翻页（见 AGENTS.md 触控板手势条目），这块需要专项验证 |
| macOS 原生菜单栏 | 中 | AGENTS.md 记录现在用 JNA 直调 AppKit（`class_addMethod` + `performSelectorOnMainThread`）。Flutter 下要做原生菜单需走 Platform Channel 写 Swift，工作量换了个地方但没消失 |
| `native-assets` 后端较新 | 中 | 它是 Flutter 3.47 上唯一走得通的路（§3.3），但不如 cargokit 成熟 |
| `cargo expand` + pinned nightly 依赖 | 中 | codegen 的隐藏前提（§1.4），CI 必须配好 |
| Dart 无法禁用 SNI | 已确认 | ui-scout 结论，图片链路必须下沉 Rust，桥接层必须承载图片字节流 |
| 本机无 Xcode | — | 只影响本次评估的打包章节可信度，不影响选型结论 |

---

## 6. 立项前必须补验的清单

以下各项因本机缺少完整 Xcode 未能实机验证（**均已在正文标【假设】**），建议立项前用一个装了 Xcode 的环境集中跑一遍：

| # | 待验项 | 为什么重要 | 怎么验 |
|---|---|---|---|
| 1 | `flutter build macos --release` 能否产出可用 bundle | 整个打包链路的前提 | 装 Xcode 后直接跑 |
| 2 | 去聚合 App bundle 结构与真实体积 | 验证「197MB → 几十 MB」这个收益是否成立 | 解开 bundle，`du` 各目录 |
| 3 | Rust 静态链接是否规避 mis-aligned LINKEDIT | 决定是否省下 12MB debuginfo（§4.3） | 静态 link 打一个 release App 并启动 |
| 4 | Release entitlements 缺 `network.client` 是否真的阻断网络 | 关系到 Release 包能不能用（§4.2 坑 1） | Release 模式下打一次登录 + 拉一个 feed |
| 5 | hardened runtime 下静态库方案是否可以免去单独的库签名 | 决定签名步骤的复杂度（§4.2 坑 2） | `--options runtime` 签名后启动 |
| 6 | 完整跑通一次「签名 → 公证 → 钉合 → DMG」 | 唯一无法靠文档推断到底通不通的一环 | 需 Apple Developer 账号，按 §3.4 实操 |
| 7 | 触控板 pinch 缩放 / 滚动翻页在 Flutter 下的手感 | 本项目核心交互（见 AGENTS.md） | 写一个最小 Flutter macOS 页面实机试 |
| 8 | 首页并发几十张图的滚动帧率 | 桥接层 + GC 的综合表现（§2.4） | 真机 instrument 测帧率与内存 |

第 1、4、6 项是**阻塞性**的——不过这三项，无法承诺「能做成产品」。

---

## 附录：本文实测数据速查

| 指标 | 实测值 |
|---|---|
| Flutter / Dart | 3.47.5 / 3.13.4 |
| Rust | 1.96.0 |
| FRB 版本 | 2.13.0（2026-08-23 发布） |
| FRB codegen 产物 | Rust 784 行 + Dart 784 行 + io/web 各 219 行 |
| 同步 vs 异步 300ms CPU 任务 | 阻塞 300ms vs 调用方 319us 返回 |
| 数据流吞吐（小包） | 14.3 万帧/秒 |
| 数据流吞吐（256KB 分块） | 11765 帧/秒 ≈ 3.07 GB/s |
| 900 MB/s 洪峰下 UI tick 抖动 | 中位数 16.0ms / 最大 17.1ms（几乎零抖动） |
| 64MB 零拷贝 | 1 us |
| 64MB 拷到 Dart 堆 | 5296 us（每张 8MB 图约 0.66ms） |
| 每 MB 拷贝成本 | 82.8 us |
