# Pixiv-Shaft macOS 移植 — 设计文档 v2

> 日期：2026-07-04 | 状态：待用户复审 | 前序：本文件替代 v1（`2026-07-04-pixiv-shaft-macos-design.md`），修正了 v1 对代码库的多处误读，最致命的是反墙策略。源仓库：`~/workspace/git_repo/Pixiv-Shaft`（实际路径 `/Users/he/workspace/git_repo/Pixiv-Shaft`）。

## 0. 已确认的约束与范围（brainstorming 结论）

**约束**：
- **连通性**：app 必须**自带反墙、零配置可用**（用户选择）。→ 必须移植 QUIC 路径。
- **代码复用**：**硬 fork 为 mac 专用 JVM 仓**，不维持与 Android 同仓编译。
- **目标平台**：macOS（单 JVM target，非全 KMP 多 target）。

**范围**：
- 核心：插图/漫画浏览、推荐流、发现（热搜/排行）、搜索（筛选/排序）、作品详情、用户主页、个人主页（本地历史/收藏）、登录。
- 小说：列表 + 详情 + **正文阅读器**（分页/ruby/翻页/批注/书签/自定义字体主题）。
- 下载管理：队列 + aria2 RPC 分发 + 路径模板引擎 + ugoira→GIF/ZIP。
- Ugoira 动图播放。

**不做**：
- 云同步后端（moonAPI 设置同步 / pixshaft 浏览历史云同步 / WebApi cookie-CSRF 抓取）→ 浏览历史/设置改纯本地；**依赖网页 cookie 接口的功能不可用**（待核实哪些功能受影响，已知 app-api 覆盖推荐/搜索/详情/排行/收藏，影响面应可控）。
- ONNX 翻译、反向搜图（Iqdb/SauceNao/TinEye）、评论发表、漫画阅读器、聊天、屏蔽管理 UI（`MuteEntity` 表保留但暂不出 UI）、Firebase、Cronet（Android 原生）、Glide、Room、MMKV、RxAndroid、RxJava、Android Navigation、pixiv-login 库、Lottie、AgentWeb。

**方案**：A — Compose Desktop + 搬运 loxia 协程栈 + Netty-QUIC QuicInterceptor（与原 CronetInterceptor 同构）。

---

## 1. 整体架构与模块结构

**仓库**：`Pixiv-Shaft-Desktop`（fork），Gradle Kotlin DSL、JDK 21、单 JVM target。

| 模块 | 处置 | 说明 |
|---|---|---|
| `models` | 搬运 | 54 个 Gson Java bean 原样（**无 Parcelable 可去**——v1"去 Parcelable→kotlinx.serialization"系误判；Gson 在 JVM 正常，不引入第二套序列化） |
| `net` | 新建（聚合搬运） | `ceui.loxia` 协程栈：`Client`/`API`/`HeaderInterceptor`/`TokenFetcherInterceptor`/`HttpDns`/`CloudFlareDns`/`ImageHostManager` + 新 `NettyQuicInterceptor`（替 Cronet）+ `auth`（OAuth） |
| `store` | 新建 | SQLDelight + KV（Keychain/Preferences） |
| `app` | 新建 | Compose Desktop：`screen`/`component`/navigation`(Voyager)/`theme`/`viewmodel`/`platform` |
| `annotations` + `processor` | **丢弃** | 生成 RecyclerView ViewHolder 绑定（`ItemHolder`→`binding`/`viewHolder`），Compose 用不上 |
| `progressmanager` | 丢弃 | Glide 下载进度专用，Coil 自带 |
| `flowlayout-lib` | 丢弃 | Compose 有 `FlowRow` |
| `3rdparty/ncnn` | 丢弃 | ONNX 翻译库，不在范围 |
| `ceui.lisa.http.Retro` 旧 RxJava2 栈 | **丢弃** | 迁移其残留调用方（`ArtworkV3ViewModel`/`NovelReader`/批量下载器/widget）到 loxia 协程栈后清掉 RxJava2/3 |

**数据流分层**（端到端协程，**纠正 v1 的 RxJava 桥接错误**）：

```
Compose UI (Composable, 监听 StateFlow)  ← collectAsState
ViewModel (viewModelScope.launch → StateFlow<UiState>)  ← suspend
Repository (suspend 分页/合并/重试, 搬运 loxia Repository 模式)  ← suspend
Retrofit API (loxia API.kt, suspend fun, Gson 转换)
OkHttp + 拦截器链 (HeaderInterceptor → TokenFetcherInterceptor → NettyQuicInterceptor[条件] → Logging)
[QUIC 路径: NettyQuicInterceptor 钉 CF IP]  或  [TCP 路径: HttpDns + 无SNI-TLS(仅pximg)]
+ SQLDelight (历史/收藏/队列) | Coil 磁盘缓存 (图片)
```

**相对 v1 的纠偏**：① 不引入 RxJava 桥接，协程贯穿；② 不"去 Parcelable"（本就不存在）；③ processor 丢弃而非"不变搬运"；④ 单 JVM target 而非名义 KMP；⑤ token 刷新在 HTTP **400** 非 401；⑥ UA 伪装 **iOS** app 非"桌面浏览器"。

---

## 2. 反墙与网络层（核心，重写 v1 致命错区）

GFW 对 pixiv 三层封锁，需三招对应，**不可互相替代**：

| GFW 手段 | 影响 | 绕过机制 | 载体 |
|---|---|---|---|
| DNS 污染 | 域名解析假 IP | DoH + 硬编码 IP fallback | `HttpDns`（搬运） |
| **SNI-based TCP RST** | app-api/oauth 的 TCP 被 RST | **QUIC over UDP**（RST 是 TCP 专属） | `NettyQuicInterceptor`（新，替 Cronet） |
| pximg 图片封锁 | i.pximg.net 不可达 | 无 SNI TLS + IP 钉死 / 镜像切换 | `HttpDns` + `RubySSLSocketFactory` 移植 + `ImageHostManager` |

**关键事实**：反墙**不能**靠"HTTP/2 + DNS 轮询"（v1 错）——HTTP/2 over TCP 照样被 SNI RST。pximg 是旧 Pixiv 基础设施（`210.140.139.x`）**不要求 SNI**，无-SNI TLS 有效；app-api/oauth 在 Cloudflare 后**要求 SNI**，无-SNI 无效，只能 QUIC。

### 2.1 NettyQuicInterceptor（CronetInterceptor 的 macOS 同构移植）

与原 `CronetInterceptor.java` 同构：OkHttp 应用拦截器，劫持请求→经 QUIC 发送→合成 OkHttp `Response`。
- 底层：`netty-incubator-codec-quic`（JNI 包 Cloudflare quiche，含 `netty_quiche_osx_aarch_64` 原生库）+ `netty-codec-http3`。
- 钉 IP：QUIC 连 `104.18.42.239:443`（CF 主）/`172.64.145.17`（备），SNI=`app-api.pixiv.net`（QUIC 的 TLS SNI GFW 不 RST，与原 app 行为一致）。
- 仅作用于 `app-api.pixiv.net`/`oauth.secure.pixiv.net`；其余域名走普通 OkHttp TCP。
- 条件挂载：`isDirectConnect` 设置开时（与现 `Client.kt:88-92` `applyDirectConnect` 同语义）。

### 2.2 拦截器链（搬运 loxia）

| 拦截器 | 职责 | 纠偏 |
|---|---|---|
| `HeaderInterceptor` | 注 `app-os:ios`、`PixivIOSApp/8.6.10` UA、`x-client-time`+`x-client-hash`、Bearer token | v1"伪装桌面浏览器 UA"**错**——app-api 必须伪装 iOS app |
| `TokenFetcherInterceptor` | **HTTP 400**（非 401）含 `Error occurred at OAuth`/`Invalid refresh token` 时刷 token | v1"401"**错**——Pixiv 用 400 |
| `NettyQuicInterceptor` | QUIC 反墙（条件挂载） | 新 |
| `HttpLoggingInterceptor` | 日志 | OkHttp 自带 |

**`x-client-hash` 必须移植**：`MD5(xClientTime + "<persona secret>")`，不带被拒。loxia 用 iOS 人设，搬运 `RequestNonce`（含 iOS secret）原样，**secret 必须与人设匹配**，不能用 Android secret 配 iOS UA。

### 2.3 DNS 层（搬运 HttpDns，非拦截器）

v1 把 DNS 写成"DnsFallbackInterceptor"是错的——实际是 OkHttp `Dns` 接口实现。搬运 `HttpDns.java`：
- DoH（Cloudflare + DNS.SB）预热 `app-api`/`oauth`，缓存解析；
- fallback：API 域名→CF IP `104.18.42.239`/`172.64.145.17`；**pximg 域名→旧 Pixiv IP `210.140.139.134/133/131`**（与 API 不同 IP 集，v1 漏了图片域名）；
- `isUseSecureDns` 开关：关时先系统 DNS，失败再 fallback。

### 2.4 图片反墙客户端（独立 OkHttpClient）

Coil 3 `ImageLoader` 配独立 OkHttpClient（搬 `Shaft.java:327-350` 逻辑）：
- PIXIV 模式 + 直连：装 `RubySSLSocketFactory`（无 SNI TLS）+ `TrustAllCertManager` + `HttpDns`（pximg 走 `210.140.139.x`）+ HTTP/1.1；
- 迁移 `ImageHostManager`：模式枚举 `PIXIV/PIXIV_CAT/PIXIV_RE/PIXIV_NL/CUSTOM`，URL 重写 `i.pximg.net→i.pixiv.cat/re/nl/自定义`，无 QUIC 时的图片兜底；
- 非 PIXIV 模式回退系统 DNS + 标准 TLS（`requiresStandardClient()`，避免硬编码 IP 破坏代理）。

### 2.5 Token 刷新（搬运 + 修坏味道）

- 搬 `SessionManager`：MMKV→Keychain（见 §3.2）；`refreshAccessToken` 调 OAuth token POST（经 QUIC 路径，因 `oauth.secure.pixiv.net` 也被 RST）。
- **修既有坏味道**：现 `SessionManager.kt:172` 在 OkHttp 拦截器里 `runBlocking` 刷 token 阻塞 IO 线程。移植改为同步 OkHttp call 刷新，不嵌套 `runBlocking(Dispatchers.IO)`；refresh_token 失效→清 session→全局 AuthState 弹登录页（不 `Common.restart()`，桌面无重启语义）。

### 2.6 POC 闸门（第一阶段硬门槛）— ✅ 已通过 (2026-07-05)

POC 已实施并**通过**：从用户真实网络经 CF IP `104.18.42.239:443` QUIC 连 `app-api.pixiv.net`，HTTP 200 + 112 illusts。代码在 `~/workspace/git_repo/Pixiv-Shaft-Desktop`（6 commits）。

**关键实现要点（Plan 2 必须沿用）**：
1. **SNI 必须显式设**：netty 4.2 `QuicClientCodecBuilder` 的 `.sslContext(ctx)` 内部调 `newEngine(alloc)`（无 peerHost → **不发 SNI** → CF 丢弃握手 → 静默超时）。必须改用 `.sslEngineProvider { q -> ctx.newEngine(q.alloc(), host, port) }`（带 peerHost 重载 → SNI=目标域名）。
2. **SNI/连接 IP 解耦**：`InetAddress.getByAddress(host, cfIpBytes)` + `InetSocketAddress(inetAddr, 443)` → UDP 连 CF IP，SNI=host。
3. **trust-all**：`InsecureTrustManagerFactory.INSTANCE`（CF anycast IP 不做主机名校验，与源 app `TrustAllCertManager` 一致）。
4. GFW 不封 pixiv QUIC（`docs/direct-connect.md` 确认 pixiv.net 未入 QUIC 黑名单），自研反墙成立，无需退系统代理。

原退路（curl-`--http3`/系统代理）未启用。

---

## 3. 数据存储层

**总原则**：结构化/列表→SQLDelight；标量设置→KV；敏感凭证→macOS Keychain。**不做云同步**，浏览历史/收藏/设置纯本地（相对现 app 的 pixshaft 云同步是功能回退，已知取舍）。

### 3.1 SQLDelight（替 Room）

现 `AppDatabase.java` ~25 实体，按范围筛分：

| 表（搬实体→.sq） | 用途 | 范围 |
|---|---|---|
| `IllustHistoryEntity` | 浏览历史 | 核心 ✓ |
| `IllustRecmdEntity` | 推荐流缓存 | 核心 ✓ |
| `SearchEntity` | 搜索历史 | 核心 ✓ |
| `GeneralEntity`/`DiscoveryEntity` | 发现页缓存 | 核心 ✓ |
| `UserEntity` | 用户信息缓存 | 核心 ✓ |
| `ImageEntity` | 图片元数据 | 核心 ✓ |
| `RemoteKey` | 分页 remote key | 核心 ✓ |
| `DownloadEntity`/`DownloadingEntity`/`DownloadQueueEntity` | 下载记录/进行中队列 | 下载 ✓ |
| `NovelBookmarkEntity` | 小说收藏 | 小说 ✓ |
| `NovelReadingStatsEntity` | 阅读进度 | 小说 ✓ |
| `NovelAnnotationEntity` | 小说批注 | 小说 ✓ |
| `NovelCustomThemeEntity`/`NovelCustomFontEntity` | 阅读器主题/字体 | 小说 ✓ |
| `MuteEntity` | 屏蔽（表保留，UI 暂不出） | 可选 ✓ |
| `ChatMessageEntity`/`ChatDatabase` | 聊天 | **丢** |
| `SynonymTag/Target` | 同义词（翻译相关） | **丢** |
| `ComicReadingStats/Bookmark/TbBookChapter` | 漫画阅读器 | **丢** |

SQLDelight driver：JVM `JdbcSqliteDriver`，DB 文件 `~/Library/Application Support/PixivShaft/shaft.db`。每实体写 `.sq`，DAO 的 `@Query` 逐条翻译为 SQL。

### 3.2 KV（替 MMKV）——按敏感度分流

| 数据 | 存储 | 理由 |
|---|---|---|
| OAuth `access_token`/`refresh_token`/`User` JSON | **macOS Keychain** | 长期凭证敏感，`java.util.prefs` 落 plist 明文不安全（相对 MMKV 明文是**安全改进**） |
| 其余设置（直连开关/图片host/语言/阅读器偏好/下载配置/上次位置） | `java.util.prefs.Preferences` | 标量/小字符串，JDK 自带，落 `~/Library/Preferences/...plist` |

Keychain 访问：JNA 调 `Security.framework`，或轻量库 `keychain4j`。封装 `KeychainKv`/`PreferencesKv` 实现 `KvStore` 接口。

### 3.3 图片缓存

Coil 3 自带磁盘缓存，`~/Library/Caches/PixivShaft/images/`，默认 256MB。`ImageEntity` 表只存元数据（尺寸/类型），不存 blob。

### 3.4 分页

丢 AndroidX Paging 3。loxia Repository 已是 `dataFetcher: suspend (page)->Response` 简单分页，UI 用 `LazyStaggeredGrid` + 手动 `loadMore` 累积 `SnapshotStateList`。`RemoteKey` 表保留（存 `next_url`/偏移）。

---

## 4. UI 层与小说阅读器

### 4.1 Compose Desktop 结构

- `app/src/desktopMain/`：`Main.kt`（入口 + `application{}` Window）、`ui/screen`、`ui/component`、`ui/navigation`、`ui/theme`、`ui/platform`、`viewmodel`、`di`。
- **导航**：Voyager（`cafe.adriel.voyager:voyager-navigator`），替 Android Navigation + safe-args。
- **主题**：Material 3（主界面）+ 搬 `ReaderTheme`（小说阅读器独立主题，自定义背景/文字色/字体）。
- **状态**：ViewModel `viewModelScope.launch{}`→`StateFlow<UiState>`，UI `collectAsState`（Desktop 无 lifecycle）。无 RxJava。

### 4.2 页面清单

| 页面 | 说明 | 数据源 |
|---|---|---|
| RecommendScreen | 插画+漫画瀑布流，下拉刷新+无限滚动 | `RecmdIllustMangaDataSource` |
| DiscoverScreen | 热搜标签 + 日/周/月排行 | `trendingTags`/`rankingIllusts` |
| SearchScreen | 搜索栏 + 类型/排序筛选 + 结果 | `searchIllustManga` |
| IllustDetailScreen | 大图缩放 + 标签 + 作者 + 相关 | `getIllust`/`getRelatedIllusts` |
| NovelDetailScreen | 小说信息 + 章节列表 | `getNovel`/`getNovelSeries` |
| **NovelReaderScreen** | 分页阅读器（§4.3） | `getNovelText`(ResponseBody) |
| UserScreen | 头像 + 关注 + 作品画廊 | `getUserProfile`/`getUserCreatedIllusts` |
| ProfileScreen | 已收藏 + 浏览历史 | 本地 SQLDelight + `getUserBookmarkedIllusts` |
| DownloadScreen | 下载队列/完成/失败 | 本地 `DownloadQueueEntity` |
| LoginScreen | OAuth PKCE（独立窗口） | `auth` |
| SettingsScreen | 直连/图片host/语言/阅读器配置 | KV |

### 4.3 小说阅读器（Compose 原生渲染）

现有阅读器是电子书级：分页、翻页动画(None/Slide/Cover/Simulation)、ruby 注音、搜索、批注、书签、章节/系列、自定义字体/主题、导出。
- **解析层**（`paginate/ContentParser.kt`+`InlineMarkup.kt`→`ContentToken`/`PageElement`）：**平台无关 Kotlin，原样搬运**。
- **渲染层**（`PageView`/`ReaderTextBlockView`/`FlipAnimator`）：Android View，Compose 重写。
- **存储**（`NovelReadingStats`/`NovelAnnotation`/`NovelCustomTheme`/`NovelCustomFont`）：§3.1 已纳入。

渲染方式：**Compose 原生**。搬运解析器→`PageElement` 列表→Compose `Text`/`AnnotatedString` 渲染段落；ruby 用自定义 `Layout`（小字悬浮于汉字上方）；`[newpage]` 用 `HorizontalPager`；四种翻页动画用 Compose `AnimatedContent`/`graphicsLayer` 重写。理由：解析器可复用是决定性因素；与主题/字体/选择工具栏原生集成；ruby 与分页是有限工作。（备选 B=内嵌 WebView 因依赖重/JCEF~150MB/主题集成差而否决。）

---

## 5. 图片加载、Ugoira、下载管理

### 5.1 图片加载（Coil 3）

- `ImageLoader` 单例，`OkHttpClient` 用 §2.4 图片反墙客户端。
- `ImageHostManager.rewrite()` 注册到 Coil `ComponentRegistry`（mapper 或 interceptor 层），与原 `GlideUrlChild` choke point 等价。
- 磁盘缓存 `~/Library/Caches/PixivShaft/images/`，256MB。
- `AsyncImage`/`SubcomposeAsyncImage` 用于列表与详情。

### 5.2 Ugoira 动图

**显示**：Coil 3 自定义 `Decoder`：拿 `.zip`（经图片反墙客户端）→`ZipInputStream` 解压按文件名排序帧→解析 `frame_delay` JSON→产出帧序列+时长→Compose `AnimatedImage` 循环播放（搬 `ModelDownloadManager.kt` 帧/延迟逻辑）。

**下载保存**：搬 `AnimatedGifEncoder.java`（纯 Java）→帧合成 GIF；也支持原样存 ZIP。无 webm/mp4 转码（现 app 也不用 MediaCodec/FFmpeg）。

### 5.3 下载管理（完整移植）

- **核心**：队列 SQLDelight `DownloadQueueEntity`/`DownloadingEntity`；协程 `OkHttpFetcher`（重写去 RxJava2），**经 QUIC 路径**取 pximg；`FsSanitizer`（搬运，纯逻辑）；bucket 配置 `BucketConfig`/`BucketDefaults`/`OverwritePolicy`/`StorageChoice`（搬运，纯数据+JSON），默认 `~/Downloads/PixivShaft/...`；ugoira→GIF/ZIP、插画原图、小说 TXT；`DownloadScreen` 显示队列/进度/完成/失败重试。
- **aria2 分发**：搬 `download/aria2/Aria2Dispatcher.kt`+`Aria2Client.kt`（纯 OkHttp 客户端调本地 aria2 RPC），依赖用户自装 aria2。
- **路径模板引擎**：搬 `download/template/`（`Template`/`TemplateContext`/`Condition`/`TemplateValidator`/`TemplateNode`，纯 Kotlin 逻辑）+ 配置 UI。
- 下载引擎 `ceui.lisa.core`（`Manager`/`OkHttpFetcher`，RxJava2）→重写为协程，并入 `net` 模块 QUIC 客户端。

---

## 6. 登录鉴权与错误处理

### 6.1 OAuth PKCE（桌面重写，丢 pixiv-login 库）

1. 生成 `code_verifier`+`code_challenge`(S256)；
2. `java.awt.Desktop.browse()` 打开系统浏览器到 Pixiv 授权页；
3. 本地 `com.sun.net.httpserver.HttpServer` 监听 `localhost:PORT/callback` 拿 `code`；
4. POST `/auth/token`（`code`+`verifier`）换 token——**此 POST 经 QUIC 路径**（`oauth.secure.pixiv.net` 被 RST）；
5. token 存 Keychain。

- **client_id/secret**：从 `pixiv-login` 库 `PixivOAuthConfig.PIXIV_ANDROID` 提取（公开已知凭证），与现 app 一致。API 人设（iOS UA + x-client-hash）与 OAuth client_id 独立，混用无碍。
- PKCE `verifier` 流程期内暂存 Keychain/KV，回调后销毁。

### 6.2 鉴权状态

- `AuthState`：全局 `StateFlow`（LoggedIn/LoggedOut/Expired），监听 `SessionManager`。
- Token 失效（刷新失败 / refresh_token 吊销）→`AuthState=Expired`→全局导航 `LoginScreen`。

### 6.3 错误处理

- `UiState<T>` sealed：`Loading`/`Success`/`Error(message, retry?)`。
- **网络错误分流**：QUIC 路径 `IOException`→"连接失败，检查直连设置/网络"；API 业务错误（非 2xx）→服务端 message；token 错误（400+OAuth 错误串）→走 §2.5 刷新。
- 全局 `SnackbarHostState`（Scaffold 内）；页面级 Error 占位+重试；图片 `SubcomposeAsyncImage` error 槽。

---

## 7. macOS 交互、构建打包、实施顺序

### 7.1 macOS 特定交互（务实）

- **托盘**：`java.awt.SystemTray`+`PopupMenu`（最近浏览/收藏数/偏好/退出）；关窗→最小化到托盘常驻。
- **图片缩放/平移**：`Modifier.pointerInput`+`detectTransformGestures`（详情页大图）。
- **自然滚动**：Compose Desktop 默认跟随系统。
- **触控板捏合/双指返回**：`pointerInput` 对多点触控支持有限→**二期**，一期仅缩放/平移。
- **快捷键**：`Cmd+W` 关窗到托盘、`Cmd+,` 设置、`Cmd+F` 搜索、`Esc` 返回。

### 7.2 构建与打包

- Gradle Kotlin DSL、JDK 21、`org.jetbrains.compose` 1.7.x、`kotlin("jvm")` 2.1.20。
- `models`/`net`/`store` 为 JVM library；`app` 为 Compose Desktop application（`desktopMain`）。
- 产物：`./gradlew :app:packageDmg`→`.dmg`。
- **签名/公证**：默认个人自用（ad-hoc 签名，免公证，`xattr -cr` 解隔离门）；公开发布需 Developer ID + notarize，另加。
- 不 minify（与现 app 一致）。

### 7.3 实施顺序（POC 闸门在前）

| 阶段 | 内容 | 门槛 |
|---|---|---|
| **0. QUIC POC** | 最小 `NettyQuicInterceptor`，经 CF IP QUIC 连 app-api 拿一条推荐流 | **硬闸门**：不通则退 curl-`--http3` 子进程 / 系统代理兜底 |
| 1. 骨架 | Gradle 项目/模块/依赖/空窗口 | 能编出空窗口 |
| 2. 网络搬运 | models + loxia 栈 + HttpDns + ImageHostManager + QuicInterceptor + Header/Token + RequestNonce | API 能调通（命令行验证） |
| 3. 存储 | SQLDelight schema + KV + Keychain | 能读写 |
| 4. 登录 | OAuth PKCE + Keychain + AuthState | 能登录拿 token |
| 5. 图片 | Coil 3 + 反墙客户端 + ImageHostManager | 图片能显示 |
| 6. 推荐页 | UI+VM+Repository+分页 | 第一个可看页面 |
| 7. 发现+搜索 | 标签/排行 + 搜索筛选 | |
| 8. 作品详情 | 大图缩放 + 标签 + 相关 | |
| 9. Ugoira | Coil 解码器 + GIF 编码 | 动图能播/能存 |
| 10. 小说 | 详情 + 阅读器（解析器搬运 + Compose 渲染 + ruby + 翻页） | 能阅读 |
| 11. 用户/个人 | 画廊 + 关注 + 本地历史/收藏 | |
| 12. 下载 | 核心 + aria2 + 模板 + ugoira→GIF | 能下载 |
| 13. mac 交互 | 托盘 + 快捷键 + 缩放 | |
| 14. 打包 | DMG | 可分发 .dmg |

---

## 8. 待核实风险

1. **QUIC POC**（§2.6）：最高风险，决定方案 A 是否成立。
2. **WebApi 丢弃的功能影响**（§0）：需核实哪些 in-scope 功能依赖网页 cookie 接口（已知 app-api 覆盖推荐/搜索/详情/排行/收藏，预期影响可控，但需在阶段 2 移植时逐接口确认）。
3. **iOS x-client-hash secret**（§2.2）：`RequestNonce` 中的 iOS secret 需确认与现 loxia 栈一致，且 Cloudflare 仍接受。
4. **Coil 3 vs 2 API 破坏性变更**（§5.1）：定 Coil 3，注意 `Decoder`/`ComponentRegistry` API 与 v2 差异。
5. **netty-incubator-codec-quic 成熟度**（§2.1）：incubator 项目，mac arm64 原生库可用但需 POC 验证稳定性。
