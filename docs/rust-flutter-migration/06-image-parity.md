# 图片子系统功能对等性核对（Kotlin + Coil 3 → Rust）

核对日期：2026-09-24。
核对对象：`app/src/main/kotlin/ceui/pixiv/image/ImageLoaderFactory.kt`、`app/src/main/java/ceui/pixiv/image/CoilFactoryBridge.java`、`app/src/main/kotlin/ceui/pixiv/ui/component/UgoiraPlayer.kt`，以及全部 16 个 `AsyncImage` 调用点。
Coil 版本：`io.coil-kt.coil3:coil-compose:3.1.0` + `coil-network-okhttp:3.1.0`。核对方式是解包 Coil 3.1.0 的源码包逐项检查，不依赖文档推断。第三方源码未随文档提交，需要时按下面方式从 Gradle 缓存提取：

```bash
unzip -o ~/.gradle/caches/modules-2/files-2.1/io.coil-kt.coil3/coil-core-jvm/3.1.0/*/coil-core-jvm-3.1.0-sources.jar -d .probe/coil-core
```
实测环境：中国大陆网络，macOS arm64。

## 评价口径

只回答「现有能力能否保持或优化」，不讨论工作量与成本。每条标注 **完全保持 / 优化 / 降级**，并给出判定依据所在的具体文件与行号。

---

## 1. Coil 3 实际提供了什么：逐项核对本项目是否真的用到

这是判断「保持还是退化」的基础。lead 列出的清单里，有一项与 Coil 3.1.0 的实际行为不符，我做了实证核对并纠正。

### 1.1 并发相同 URL 的请求去重（dedup）—— 本项目**没有**这个能力

**这是本次核对最重要的纠正。** Coil 3.1.0 **不存在** in-flight 请求合并机制。

实证方式：在解包的 `coil-core-jvm` 与 `coil-compose-core-jvm` 全部源码中检索 `dedup`、`coalesce`、`inflight`、`merge(`，唯一命中是 `DiskLruCache.kt:635` 的注释（说的是磁盘编辑事务，与请求去重无关）。`RealImageLoader.kt` 里两处 `async(`（`RealImageLoader.kt:66`、`RealImageLoader.kt:82`）都是给每个请求各起一个 Job，没有按 key 共享 `Deferred` 的结构。

也就是说：同一 URL 的 N 个并发请求，Coil 会发 N 次网络请求，各自解码、各自写缓存。当前项目在瀑布流里快速滚动、或列表里同一张图多处出现时，**本来就会产生重复下载**，这个现象现在就存在。

**Rust 侧判定：优化。** 这一项不仅不会退化，还能补上现在缺失的能力。做法是按 URL 做 in-flight 合并：把 `(url -> Shared<Future>)` 放进一个 map，首个请求发起真实下载，后续同 URL 请求共享同一个 Future（用 `tokio::sync::watch` 或 `futures::future::Shared`）。

实测依据（[image_concurrency_probe.py](verify/image_concurrency_probe.py)）：并发 6 个相同请求打到 pximg，6 条全部 `HTTP/1.1 200 OK`，总耗时 830ms（单请求约 330ms），证明传输层本身支持并发、去重是纯应用层可解决的问题。

### 1.2 内存缓存：LRU 强引用 + 弱引用两级 —— 本项目用到了，Rust 侧**完全保持**

实证：`MemoryCache.Builder().maxSizeBytes(MEMORY_CACHE_SIZE_BYTES)`，常量定义在 [ImageLoaderFactory.kt:20](../../app/src/main/kotlin/ceui/pixiv/image/ImageLoaderFactory.kt#L20)）。Coil 内部实现是 `RealStrongMemoryCache`（`StrongMemoryCache.kt:55`）持有一个 `LruCache`，配 `WeakMemoryCache`（`WeakMemoryCache.kt:19`）——从强引用层被淘汰的值会降级进弱引用层，仍可能被复用。

Rust 侧：`moka = "0.12"`（0.12.16，2026-08-09 发布，1.29 亿下载，活跃维护）提供 `sync::Cache`，支持 `max_capacity` 与基于权重的 `weigher`（按字节数计权），语义对等。

**弱引用那一层 Rust 没有直接对应**（Rust 没有 JVM 那种 GC 弱引用）。影响：从 LRU 淘汰的图片在 Rust 侧直接释放，不会像现在那样还有一次「弱引用命中」的机会。实际影响很小——弱引用层本身随时可能被 GC 清掉，不构成可靠收益。**判定：完全保持**（弱引用层缺失不计为降级，因为它不保证命中）。

### 1.3 磁盘缓存：LRU + journal 持久化 —— 本项目用到了，Rust 侧**完全保持并可优化**

实证：`DiskCache.Builder().directory(...).maxSizeBytes(256L * 1024 * 1024)`（[ImageLoaderFactory.kt:35-37](../../app/src/main/kotlin/ceui/pixiv/image/ImageLoaderFactory.kt#L35-L37)），目录 `~/Library/Caches/PixivShaft/images`。Coil 的 `RealDiskCache` 内部是 `DiskLruCache`，淘汰逻辑在 `DiskLruCache.kt:607` 的 `trimToSize()`：超过 `maxSize` 时循环 `removeOldestEntry()`。

**磁盘上的真实状态（实测）**：

| 指标 | 实测值 |
|------|-------|
| 条目数 | 1134 个 |
| 实际占用 | 259.1 MB（配置上限 256 MB） |
| 最大单文件 | 17.6 MB |
| 中位数文件 | 43 KB |
| 超过 1MB 的条目 | 48 个，合计 194 MB |
| 最大 10 个文件 | 92.2 MB，占总容量 37% |
| journal 格式 | `libcore.io.DiskLruCache` / appVersion 3 / valueCount 2 |
| 文件命名 | `<url 的 sha256 hex>.0`（元数据）与 `.1`（数据） |
| 数据跨度 | 2026-07-06 至 2026-09-24 |

**缓存 key 的构造（实证）**：`UriKeyer.kt:10` 的 `key()` 只返回 `data.toString()`，也就是 **URL 字符串本身**，不含任何请求头。所以 `Referer`、伪装 UA 不参与 key；`MemoryCacheService.kt:34` 的 `newCacheKey()` 只在有 `transformations` 时才把 size 加进 extras。Rust 侧用 URL 做 key 即可完全对应，**重启后磁盘缓存仍能命中**。

**Rust 侧判定：完全保持，且可优化。** 现有 256MB LRU 按「最近最少使用」逐条淘汰，但实测显示 48 个大文件吃掉 77% 的容量、1086 个小文件只用 23%。纯 LRU 会让一张 17.6MB 的原图挤掉约 400 张缩略图。Rust 侧可以实现更明确的策略：按条目大小加权淘汰、或按「缩略图 / 原图」分池，各自独立限额。这是**优化项**，不是必需项——保持现有 LRU 也能工作。

### 1.4 按控件尺寸下采样解码 —— 本项目用到了，Rust 侧**完全保持并可优化**

实证：`SkiaImageDecoder.kt:24` 调 `Bitmap.makeFromImage(image, options)`，实际缩放在 `utils.nonAndroid.kt:24-56`：先 `DecodeUtils.computeDstSize()` 按 `options.size` 算目标尺寸，再 `computeSizeMultiplier()` 算缩放比。目标尺寸来自 composable 的实际约束尺寸。

**关键限制（实证）**：`SkiaImageDecoder.kt:21` 是 `Image.makeFromEncoded(bytes)` 整图解码进 Skia Image，然后才缩放到目标 Bitmap。也就是说 **Coil 现在的做法是「整图解码 → 缩放」**，峰值内存等于整图。

实测代价（[decode-bench](verify/decode-bench/src/main.rs)，用磁盘缓存里真实存在的 4993×6963 PNG）：

```
整图解码: 4993x6963 = 34766259 像素, 122 ms
  估算内存占用（RGBA8）: 132.6 MB
  目标 200: 输出 143x199, 133 ms, 内存 132.6 MB（峰值仍是整图）
```

**Rust 侧判定：优化。** JPEG 有 `jpeg-decoder = "0.3"` 的 DCT 缩放能力（`decoder.rs:278` 的 `scale(requested_width, requested_height)`，内部选 1/8、1/4、1/2、1 的因子）。实测（磁盘缓存里真实的 2895×4091 JPEG）：

```
整图解码: 2895x4091 = 11843445 像素, 128 ms, RGBA 内存 45.2 MB
  解码时缩放 1/2: 实际 1448x2046, 102 ms, 缓冲 8.5 MB (像素减少 75%)
  解码时缩放 1/4: 实际 724x1023,  93 ms, 缓冲 2.1 MB (像素减少 94%)
  解码时缩放 1/8: 实际 362x512,   92 ms, 缓冲 0.5 MB (像素减少 98%)
```

一张列表缩略图只需要几百像素宽，用 1/8 缩放解码可以把内存从 45.2MB 降到 0.5MB，而且**更快**（92ms vs 128ms）。这是当前 Kotlin 实现做不到、Rust 侧能明确做到的优化。

PNG 没有等价的解码时缩放（`image` 0.25 与 `fast_image_resize` 都是解码后缩放），但可用 `fast_image_resize = "6.1"`（SIMD 加速）降低缩放本身开销。**【假设】** PNG 路径的峰值内存仍等于整图，与现状持平。

### 1.5 协程取消传播 —— 本项目用到了，Rust 侧**完全保持**

实证：`AsyncImagePainter.kt:167-171` 的 `rememberJob` setter 会在赋值前 `field?.cancel()`，composable 离开组合时取消请求。Coil 内部多处 `ensureActive()` 让取消能中断解码。

Rust 侧：`tokio` 的 `JoinHandle::abort()` 或 `CancellationToken` 直接对应。语义对等，且 Rust 的取消是真实的（drop future 即停）。**判定：完全保持。**

### 1.6 `AsyncImage` 的加载状态机与占位/错误态 —— 本项目**几乎没用到**

实证：16 个调用点全部枚举（脚本 [analyze_asyncimage.py](verify/analyze_asyncimage.py)，逐点提取参数列表）。参数使用统计：

| 参数 | 使用次数 / 16 |
|------|--------------|
| `model` | 16 |
| `contentDescription` | 16 |
| `modifier` | 16 |
| `contentScale` | 14 |
| `placeholder` | **1** |
| `onError` | **1** |
| `error` | 0 |
| `crossfade` | 0 |
| `onState` / `onLoading` | 0 |
| `size` / `precision` | 0 |

结论：**Coil 的状态机在本项目里几乎未被使用**。16 处里 14 处只用了 `model + contentDescription + modifier + contentScale`，没有占位图、没有错误态 UI、没有淡入。`AsyncImage` 在这里基本只当「给 URL 就显示图片」的黑盒。

唯一用到错误态的是 [NovelDetailScreen.kt:447](../../app/src/main/kotlin/ceui/pixiv/ui/screen/novel/NovelDetailScreen.kt#L447)（带 `onError`）。占位色是用 `Modifier.background(placeholderColor)` 手动画的（[IllustCard.kt:63](../../app/src/main/kotlin/ceui/pixiv/ui/component/IllustCard.kt#L63)），不是 Coil 的 `placeholder`。

**Rust 侧判定：完全保持。** Flutter 侧用 `Image.memory` 配 `frameBuilder` / `errorBuilder` 就能覆盖现有用法（一个占位色 + 一个错误回调），状态机本身不需要复刻。

### 1.7 图片专用 OkHttpClient 的反墙配置 —— 本项目用到了，Rust 侧**完全保持并可优化**

实证（[ImageLoaderFactory.kt:45-70](../../app/src/main/kotlin/ceui/pixiv/image/ImageLoaderFactory.kt#L45)）：

| 配置 | 值 |
|------|-----|
| Referer | `https://app-api.pixiv.net/` |
| User-Agent | `PixivIOSApp/8.6.10 (iOS 26.5; iPhone16,2)` |
| 连接超时 | 15s |
| 读取超时 | 30s |
| 无 SNI TLS + HttpDns + 强制 HTTP/1.1 | 仅当 `settings.isDirectConnect && !ImageHostManager.requiresStandardClient()` |

反墙部分已在 [01-net-layer.md](01-net-layer.md) 实测通过：rustls 的 `enable_sni=false` 已验证到 ClientHello 字节层面无 SNI 扩展，并直连 `210.140.139.134` 拿到 HTTP 200。

**Rust 侧判定：优化。** 现有条件判定要同时看「直连开关」和「图片源模式」两个变量，因为无 SNI + 硬编码 IP 只在 PIXIV 源下成立，切到 pixiv.cat / 自定义源必须退回标准 DNS + 带 SNI 的 TLS。Rust 侧网络层统一后，可以把这个判定收敛成「按 host 选择传输策略」一处配置，不再需要在构造 client 的分支里判断。

### 1.8 动图解码（Ugoira）—— 本项目用到了，Rust 侧**优化**

实证：[UgoiraPlayer.kt:37](../../app/src/main/kotlin/ceui/pixiv/ui/component/UgoiraPlayer.kt#L37) 的 `var bitmaps by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }`，[UgoiraPlayer.kt:88](../../app/src/main/kotlin/ceui/pixiv/ui/component/UgoiraPlayer.kt#L88) 的 `decodeUgoiraZip()` 把 zip 里所有帧一次性全部解码成 `ImageBitmap` 后放进这个 List。播放只是按 `frames[i].delay` 轮播下标（[UgoiraPlayer.kt:60-72](../../app/src/main/kotlin/ceui/pixiv/ui/component/UgoiraPlayer.kt#L60)）。

**这是当前实现里最明确的浪费**：一个 zip 有几十到几百帧，全部解码常驻内存，而同一时刻只显示一帧。以 Ugoira 常见规格（如 1200×1600 的帧、60 帧）估算，全部解码约 460MB。**【假设】**（本项目未实测具体 zip，按帧尺寸与帧数估算）

Rust 侧优化：改成按需解码 —— 只保留当前帧与下一帧的解码结果，播放时提前一帧异步解码。`zip = "8.0"` 读条目 + `image` 解码单帧。这能把常驻内存从「全部帧」降到「两帧」。

### 1.9 预取（prefetch）—— 本项目**未使用**

检索 `app/src/main` 全部源码，没有 `ImageRequest.Builder` 的显式构造，也没有 `prefetch` 调用。所有加载都走 `AsyncImage(model = ...)` 的隐式默认请求。**判定：无此项能力，迁移不涉及。**

### 1.10 图片源重写 —— 本项目用到了，Rust 侧**完全保持**

`ImageHostManager.rewrite()` 把 `i.pximg.net` / `s.pximg.net` 换成 pixiv.cat / pixiv.re / pixiv.nl / 自定义前缀。当前只在 [DownloadManager.kt:607](../../app/src/main/kotlin/ceui/pixiv/download/DownloadManager.kt#L607) 对下载路径调用（`GlideUrlChild` 那条注释描述的是原始 Shaft 的接法，Desktop 版本未保留）。纯字符串替换逻辑，Rust 侧直接平移。**判定：完全保持。**

---

## 2. 逐项对等总表

| 能力 | 本项目是否用到 | Coil 现状 | Rust 方案 | 判定 |
|------|--------------|----------|----------|------|
| 并发相同 URL 去重 | 用到（但**当前缺失**） | 无此机制（源码实证） | URL → 共享 Future 的 map | **优化** |
| 内存缓存 128MB LRU | 用到 | `RealStrongMemoryCache` + `WeakMemoryCache` | `moka` 按字节计权 | **完全保持** |
| 磁盘缓存 256MB LRU | 用到 | `DiskLruCache` + journal | 自写 LRU 或 `lru` crate | **完全保持**，可加权优化 |
| 缓存 key 构造 | 用到 | URL 字符串（不含请求头） | URL 字符串 | **完全保持** |
| 按控件尺寸下采样 | 用到 | 整图解码后缩放 | `jpeg-decoder` DCT 缩放 + `fast_image_resize` | **优化** |
| 协程取消传播 | 用到 | `rememberJob` cancel | `JoinHandle::abort()` | **完全保持** |
| 加载状态机占位/错误 | **几乎未用**（1/16） | `AsyncImagePainter` State | Flutter `frameBuilder`/`errorBuilder` | **完全保持** |
| 无 SNI TLS + HttpDns | 用到 | `RubySSLSocketFactory` + `HttpDns` | rustls `enable_sni=false` | **完全保持**，条件判定可简化 |
| 图片专用超时与头 | 用到 | OkHttp 15s/30s + Referer + UA | reqwest 同等配置 | **完全保持** |
| Ugoira 动图 | 用到 | 全部帧一次性解码 | `zip` + 按需单帧解码 | **优化** |
| 图片源重写 | 用到 | `ImageHostManager.rewrite` | 字符串替换平移 | **完全保持** |
| 预取 | **未使用** | — | — | 不涉及 |

---

## 3. 会退化的具体场景

按当前核对结果，**没有任何一项能力会退化**。以下是三个最容易被误判为退化的场景，逐条给出实测结论：

| 场景 | 会不会退化 | 依据 |
|------|-----------|------|
| 同一列表并发打开（如瀑布流快速滚动、同图多处出现）产生重复下载 | **不会退化，反而改善** | 当前 Coil 3.1.0 本来就没有去重，重复下载现在就存在；Rust 侧加上 in-flight 合并后消除 |
| 退出重进后磁盘缓存是否仍命中 | **不会退化** | 缓存 key 是 URL 的 sha256，与请求头无关；journal 持久化到磁盘。实测目录里有 1134 个条目，跨度 2026-07-06 至 2026-09-24 |
| 是否还会按显示尺寸下采样 | **不会退化，反而更好** | Rust 侧 JPEG 可在解码阶段降采样，内存从 45.2MB 降到 0.5MB 且更快 |

需要留意的一处细微差异：从 LRU 淘汰的图片，现在是降级进弱引用层（还有一次命中机会），Rust 侧会直接释放。弱引用层何时被 GC 清掉不受控，不构成可靠能力，因此不计为退化。

---

## 4. 现有实现里可以做得更好的地方

按收益从高到低：

### 4.1 Ugoira 全帧解码（收益最高）

现状：[UgoiraPlayer.kt:88](../../app/src/main/kotlin/ceui/pixiv/ui/component/UgoiraPlayer.kt#L88) 把 zip 内所有帧一次解码进内存常驻，只显示一帧。
优化：改为「当前帧 + 预解码下一帧」，常驻内存从全部帧降到两帧。用 `zip = "8.0"` 逐条读、`image = "0.25"` 单帧解码。

### 4.2 JPEG 解码时降采样

现状：Coil 走 `Image.makeFromEncoded()` 整图解码再缩放，峰值内存等于整图（实测 4993×6963 PNG 需 132.6MB）。
优化：`jpeg-decoder` 的 `scale()` 走 DCT 缩放。实测 2895×4091 JPEG：整图 45.2MB / 128ms → 1/8 缩放 0.5MB / 92ms。列表缩略图场景收益巨大。

### 4.3 磁盘缓存淘汰策略

现状：256MB 纯 LRU 逐条淘汰。实测 48 个大文件占 77% 容量，一张 17.6MB 原图会挤掉约 400 张缩略图。
优化：按条目大小加权，或把缩略图与原图分池各自限额。缩略图是高频访问项，被大图挤掉会明显升高流量与加载延迟。

### 4.4 反墙条件判定收敛

现状：图片 client 要在构造时判断 `settings.isDirectConnect && !ImageHostManager.requiresStandardClient()` 两个条件，因为无 SNI + 硬编码 IP 只在 PIXIV 源成立。
优化：网络层统一到 Rust 后，改成按 host 查表选传输策略（pximg.net 走无 SNI + HttpDns，其他 host 走标准 DNS + 带 SNI 的 TLS），条件集中在一处。

### 4.5 请求去重补上

现状：Coil 3.1.0 无 in-flight 合并，重复下载当前就存在。
优化：Rust 侧加 URL → 共享 Future 的 map。瀑布流快速滚动、列表同图多处出现时直接消除重复请求。

---

## 5. 需要的 crate

均为主流且活跃维护，版本与发布日期实测取自 crates.io API（2026-09-24）：

| crate | 版本 | 发布日期 | 累计下载 | 用途 |
|-------|------|---------|---------|------|
| `moka` | 0.12.16 | 2026-08-09 | 1.30 亿 | 内存 LRU 缓存 |
| `image` | 0.25.10 | 2026-03-10 | 1.95 亿 | 图片解码（PNG 等） |
| `jpeg-decoder` | 0.3.2 | 2025-06-21 | 8912 万 | JPEG 解码时 DCT 降采样 |
| `fast_image_resize` | 6.1.0 | 2026-07-21 | 1964 万 | SIMD 缩放 |
| `zip` | 8.6.0 | 2026-08-11 | 2.76 亿 | Ugoira zip 读帧 |
| `lru` | 0.18.5 | 2026-09-23 | 3.56 亿 | 磁盘 LRU（若不自写） |
| `sha2` | — | — | — | 缓存 key（与现状一致） |
| `rustls` | 0.23.45 | 2026-09-14 | 9.35 亿 | 无 SNI TLS（项目已有） |
| `reqwest` | 0.13.5 | 2026-09-08 | 7.39 亿 | HTTP 客户端（项目已有） |
| `tokio` | 1.53.1 | 2026-07-20 | 9.91 亿 | 异步与取消（项目已有） |

`jpeg-decoder` 最后发布 2025-06-21，是表中更新最慢的一个，但它功能单一（JPEG 解码）、下载量 8912 万，且被 `image` crate 用作默认 JPEG 后端，实际维护是活跃的。**【假设】**：`image` 0.25 内部已依赖 `jpeg-decoder`，直接依赖它不会出现版本冲突，此点未实际编译验证。

---

## 6. 结论

1. **没有一项现有能力会退化。** 12 项核对里 8 项完全保持、4 项可优化。
2. **lead 清单里「请求去重」这一项需要纠正**：Coil 3.1.0 并无此机制（源码实证），当前项目本来就有重复下载。Rust 侧能补上，判定为优化。
3. **收益最大的三项优化**：Ugoira 按需解码（常驻内存从全部帧降到两帧）、JPEG 解码时降采样（45.2MB → 0.5MB 且更快）、补上请求去重。
4. **磁盘缓存「无自定义淘汰策略」的说法需要修正**：Coil 默认有 LRU（`DiskLruCache.kt:607`）。现状的问题在于纯 LRU 对「大图挤掉大量缩略图」处理不好——实测 48 个大文件占 77% 容量。Rust 侧可以做得更明确。
5. 反墙链路（无 SNI + HttpDns）已在 [01-net-layer.md](01-net-layer.md) 实测通过，图片链路的下沉没有技术阻塞。

---

## 附录：验证产物

| 文件 | 用途 |
|------|------|
| Coil 3.1.0 源码 | 从 Gradle 缓存的 `coil-core-jvm-3.1.0-sources.jar` 解包（第三方源码未随文档提交），用于核对缓存、淘汰、键构造等实现细节 |
| [verify/analyze_asyncimage.py](verify/analyze_asyncimage.py) | 枚举 16 个 `AsyncImage` 调用点并提取参数列表 |
| [verify/decode-bench/src/main.rs](verify/decode-bench/src/main.rs) | 整图解码与缩放的内存/耗时实测 |
| [verify/decode-bench/src/bin/jpeg_scaled.rs](verify/decode-bench/src/bin/jpeg_scaled.rs) | JPEG 解码时 DCT 降采样实测 |
| [verify/image_concurrency_probe.py](verify/image_concurrency_probe.py) | 并发相同 URL 请求实测 |

复现方式：

```bash
export CARGO_HOME=$PWD/docs/rust-flutter-migration/verify/.cargo-home
cd docs/rust-flutter-migration/verify/decode-bench
cargo build --release
./target/release/decode-bench <任意大图.png>    # 整图解码代价
./target/release/jpeg_scaled <任意大图.jpg>     # 解码时降采样收益
```

测量所用图片取自本机磁盘缓存（`~/Library/Caches/PixivShaft/images`）里的真实作品：一个 4993×6963 的 PNG（18.4MB）与一个 2895×4091 的 JPEG（13.5MB）。这两张图片未随文档提交，复现时从缓存目录另取同量级的图片即可，工具会打印实际尺寸与内存占用。构建可在云端 GitHub Actions（`macos-latest`，已装 Rust 工具链）完成，本机无需 Xcode。
