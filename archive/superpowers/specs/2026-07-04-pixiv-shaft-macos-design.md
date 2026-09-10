# Pixiv-Shaft macOS 移植 — 设计文档

> 日期：2026-07-04 | 状态：待审核

## 1. 目标与范围

将 Pixiv-Shaft（Android 版 Pixiv 第三方客户端）移植到 macOS，做 **核心浏览版**：

- 插图 / 漫画 / 小说浏览
- 推荐流（插画 + 漫画）
- 发现页（热搜标签、日/周/月排行）
- 搜索（按类型筛选、排序切换）
- 作品详情页（大图查看、标签、作者信息、相关作品）
- 用户主页（作品画廊、关注按钮）
- 个人主页（已收藏、浏览历史）
- 登录（OAuth PKCE）

**不做**：下载管理队列、ONNX 翻译、反向搜图、评论发表、屏蔽管理、多账号。

## 2. 技术路线

**方案 A — 保留核心层 + 替换 UI**：

- Fork 自 Pixiv-Shaft，不回头兼容 Android
- 保留 `Retrofit + OkHttp + RxJava` 网络层 & Repository 实现
- UI 层全部用 Compose Multiplatform (Material 3) 重写
- ViewModel 内部消费 RxJava Observable，暴露 Compose State 给 UI
- 去 Firebase、Cronet、Room、MMKV，换成 KMP 友好的替代品

**选择理由**：Pixiv API 的反爬、Token 刷新、DNS fallback、直连策略在现有网络层有 2000+ 行成熟代码，重写风险大；UI 层的替换是机械工作。

## 3. 项目结构

```
Pixiv-Shaft-Desktop/           # Fork 后的独立仓库
├── models/                    # 搬运，去 android.os.Parcelable → kotlinx.serialization
│   └── src/main/              # Gson 数据类 + Retrofit API 接口
├── annotations/               # 搬运，自定义注解（不变）
├── processor/                 # 搬运，KAPT 代码生成器（不变）
├── net/                       # 【新】网络层集中模块
│   └── src/main/
│       ├── api/               # ApiService 实例化 + Retrofit 配置
│       ├── interceptor/       # Token 刷新、UA/Cookie、DNS、日志拦截器
│       ├── dns/               # DNS fallback 策略（OkHttp Dns 接口）
│       ├── repository/        # Repository 实现（翻页合并、缓存合并、重试）
│       └── auth/              # OAuth PKCE Token 管理
├── store/                     # 【新】存储层
│   └── src/main/
│       ├── db/                # SQLDelight .sq 文件 + Driver 配置
│       └── kv/                # 轻量 KV（java.util.prefs 或 JSON 文件）
└── app/                       # 【新】Compose Desktop 应用
    └── src/desktopMain/
        ├── Main.kt            # 应用入口 + Window 配置
        ├── ui/
        │   ├── screen/        # 各页面 Composable
        │   ├── component/     # 可复用组件
        │   ├── navigation/    # Voyager Screen 栈管理
        │   ├── theme/         # Material 3 主题
        │   └── platform/      # macOS 特定交互（手势、托盘）
        ├── viewmodel/         # Compose State 容器（内部消费 RxJava）
        └── di/                # 简单 DI（手动或 Koin）
```

## 4. 架构与数据流

```
┌─────────────────────────────────────────────────┐
│  Compose UI (Screen composables)                  │
│  监听 ViewModel 的 Compose State                     │
├─────────────────────────────────────────────────┤
│  ViewModel (Compose State 容器)                      │
│  内部持有 RxJava Observable                         │
│  订阅 → 转为 StateFlow / mutableStateOf              │
│  DisposableEffect → dispose                        │
├─────────────────────────────────────────────────┤
│  Repository (RxJava Single/Completable)          │
│  翻页合并、缓存合并、错误重试                          │
├─────────────────────────────────────────────────┤
│  Retrofit ApiService + OkHttp 拦截器链               │
│  Token 刷新、DNS fallback、直连策略                   │
├─────────────────────────────────────────────────┤
│  SQLDelight (浏览历史/收藏) / 文件缓存 (图片)            │
└─────────────────────────────────────────────────┘
```

**ViewModel 桥接模式示例**：
```kotlin
class RecommendViewModel(repo: RecommendRepository) {
    var uiState by mutableStateOf<UiState>(UiState.Loading)
        private set

    private var disposable: Disposable? = null

    fun load(page: Int) {
        disposable?.dispose()
        disposable = repo.getRecommend(page)
            .subscribeOn(Schedulers.io())
            .observeOn(Schedulers.computation()) // Desktop 用 computation()
            .subscribe({ result ->
                uiState = UiState.Success(result)
            }, { error ->
                uiState = UiState.Error(error)
            })
    }

    fun dispose() {
        disposable?.dispose()
    }
}
```

## 5. 页面与导航

### 主导航

底部 NavigationBar（Material 3），四项：推荐 / 发现 / 搜索 / 个人

### 页面清单

| 页面 | 源文件 | 说明 |
|------|--------|------|
| RecommendScreen | 插画+漫画推荐流 | LazyVerticalStaggeredGrid，下拉刷新，无限滚动 |
| DiscoverScreen | 热搜标签、排行（日/周/月） | TabRow 切换，日期选择器 |
| SearchScreen | 搜索+筛选 | 搜索栏 + 类型切换 + 排序 + 结果列表 |
| IllustDetailScreen | 作品详情 | 大图、标签、作者信息、相关作品横向滚动 |
| UserScreen | 用户主页 | 头像、关注按钮、作品 StaggeredGrid |
| ProfileScreen | 个人主页 | 已收藏、浏览历史列表 |
| LoginScreen | OAuth PKCE 登录 | 系统浏览器回跳，独立 Window |

### 导航实现

使用 [Voyager](https://voyager.adriel.cafe/) (`cafe.adriel.voyager:voyager-navigator`)，理由：
- 原生 Compose Multiplatform 支持
- Screen 栈管理、传参、关闭返回
- 不与 Material 组件冲突

## 6. 网络层

### 拦截器链（全部保留）

| 拦截器 | 职责 | 来源 |
|--------|------|------|
| TokenInterceptor | 401 → 自动刷新 OAuth access token | 搬运 |
| UserAgentInterceptor | 伪装桌面浏览器 UA | 搬运 + 修改 |
| CookieInterceptor | 管理 Pixiv session cookie | 搬运 |
| DnsFallbackInterceptor | DNS 解析失败时切换备用 DNS（DoH / 自定义） | 搬运，移除 Cronet 依赖 |
| LoggingInterceptor | Debug 模式请求日志 | OkHttp 自带 |

### DNS / 反墙策略

原项目使用 Cronet 提供 QUIC/HTTP3 绕过 GFW TCP RST。Desktop 替代方案：

- **DNS 层**：OkHttp `Dns` 接口实现多 DNS 源轮询（系统 DNS → DoH → 自定义），搬运现有策略
- **传输层**：保持 OkHttp 4.12.0 + HTTP/2（稳定版）。HTTP/3 待 OkHttp 5 正式发布后升级
- **效果**：多 DNS fallback 绕过 DNS 污染，HTTP/2 解决大部分连接问题；QUIC 作为后续增强

## 7. 存储层

| 原版 | 桌面版 | 说明 |
|------|--------|------|
| Room (SQLite) | SQLDelight | 浏览历史、收藏记录、搜索历史，KMP 原生 |
| MMKV (Tencent) | `java.util.prefs.Preferences` | 轻量 KV：设置项、Token 存储、上次浏览位置 |
| 文件系统 | `java.io.File` | 图片缓存（Coil 自带磁盘缓存） |

## 8. 图片加载

### 基础：Coil

```kotlin
// Coil Compose 集成
AsyncImage(
    model = illust.imageUrls.medium,
    contentDescription = illust.title,
    imageLoader = LocalImageLoader.current
)
```

Coil 自带：内存缓存、磁盘缓存、渐进式 JPEG、GIF 播放。

### Ugoira 动图支持

Ugoira 格式 = ZIP(图片帧序列) + JSON(帧延迟 `frame_delay`)。

实现自定义 `coil.decode.Decoder`：

```
1. Coil 发请求拿 .zip 响应体
2. UgoiraDecoder 拦截（检测 Content-Type 或文件头 zip 特征）
3. 解压 zip → 按文件名排序帧
4. 解析 frame_delay → 计算每帧显示时长
5. 逐帧合成 Compose AnimatedImage（或使用 Coil Animation 路径）
6. 循环播放（loop=true）
```

Coil 的 `Decoder.Factory` 可以注册自定义解码器，在 `ImageLoader.Builder` 中配置。

## 9. 错误处理

### UI 状态模型

```kotlin
sealed class UiState<out T> {
    object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String, val retry: (() -> Unit)? = null) : UiState<Nothing>()
}
```

### 错误展示

- **全局错误**：`SnackbarHostState` 放在 Material 3 `Scaffold` 内，ViewModel 抛出的可恢复错误显示 Snackbar
- **页面级错误**：`Error` 状态渲染错误提示 + 重试按钮
- **登录失效**：全局 AuthState 监听，检测到 Token 过期弹出 LoginScreen

## 10. 登录

OAuth 2.0 PKCE 流程，Pixiv 标准认证：

```
1. 用户点击登录 → 生成 code_verifier + code_challenge
2. 打开系统浏览器 → Pixiv 授权页
3. 用户授权后回调 → http://localhost:PORT/callback?code=xxx
4. 本地 HTTP 服务器监听 PORT，拿到 code
5. POST /auth/token → 拿 access_token + refresh_token
6. 持久化 token（Preferences）
```

实现要点：
- `java.awt.Desktop.browse()` 打开系统浏览器
- 本地 HTTP 服务器（OkHttp MockWebServer 或 `com.sun.net.httpserver.HttpServer`）监听回调端口
- Token 自动刷新逻辑复用在 `TokenInterceptor` 中

## 11. macOS 特定交互

### 菜单栏状态

- 系统托盘图标（`java.awt.SystemTray`）
- 托盘菜单：最近浏览、收藏数统计、偏好设置、退出
- 关闭窗口不退出应用，托盘图标常驻（类似 macOS 微信/QQ）

### 触控板手势

Compose Desktop 通过 `Modifier.pointerInput` + 自定义手势识别实现：

| 手势 | 触发位置 | 行为 |
|------|---------|------|
| 双指捏合 | 图片详情页 | 缩放 |
| 双指滑动（两指水平） | 图片详情页 | 平移 |
| 双击 | 图片详情页 | 缩放复位 |
| 双指横向滑动 | 详情页 / 搜索结果 | 返回上一页 |
| 自然滚动 | 列表 | macOS 方向滚动 |

手势识别需要处理 `PointerEventType.Scroll`（触控板滚动带惯性）和 `PointerEventType.Move` + `PointerType.Stylus` / 多点触控检测。

## 12. 构建系统

### Gradle 配置

```kotlin
// build.gradle.kts (root)
plugins {
    kotlin("multiplatform") version "2.1.20" apply false
    kotlin("plugin.compose") version "2.1.20" apply false
    id("org.jetbrains.compose") version "1.7.3" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20" apply false
}
```

### 模块构建

- `models` / `net` / `store`：Kotlin JVM target（无 multiplatform 需要，Desktop = JVM 单 target）
- `app`：Compose Desktop target，单一 `desktopMain` source set

### 打包产物

```bash
./gradlew :app:packageDmg  # 打包 DMG
./gradlew :app:packageMsi  # 打包 PKG（可选）
```

## 13. 依赖清单

| 依赖 | 版本 | 用途 |
|------|------|------|
| Compose Multiplatform | 1.7.3 | UI 框架 |
| Kotlin | 2.1.20 | 语言 |
| Retrofit | 2.11.0 | HTTP API |
| OkHttp | 4.12.0 | HTTP 客户端，多 DNS fallback 反墙 |
| RxJava 2 | 2.2.21 | 响应式编程（搬运） |
| RxJava 3 | 3.1.3 | 新代码可用 |
| Gson | 2.11.0 | JSON 解析（搬运） |
| SQLDelight | 2.0.x | 本地数据库 |
| Coil | 2.7.x (coil-compose) / 3.x | 图片加载（优先 3.x，兼容 KMP） |
| Voyager | 1.1.x | 页面导航 |
| kotlinx.serialization | 1.7.x | 替代 Parcelable |
| jsoup | 1.18.1 | HTML 解析（搬运） |

## 14. 不搬运的依赖

| 依赖 | 原因 |
|------|------|
| Firebase Analytics / Crashlytics | 仅 Android/iOS，桌面不需要 |
| Cronet | 换 OkHttp Dns 接口多源轮询（无需额外库） |
| Glide | 换成 Coil |
| Room | 换成 SQLDelight |
| MMKV | 换成 Preferences |
| Android Navigation Component | 换成 Voyager |
| RxAndroid | `AndroidSchedulers.mainThread()` 不存在，换 `Schedulers.computation()` |
| pixiv-login (Chrome Custom Tab) | 换成系统浏览器 + localhost callback |
| ONNX Runtime (翻译) | 不做翻译功能 |
| Lottie | 如果不需要动画启动页可去掉 |

## 15. 实施顺序

| 阶段 | 内容 | 预计输出 |
|------|------|---------|
| 1. 项目骨架 | Gradle KMP 配置、模块创建、依赖引入 | 能编译空窗口 |
| 2. 网络层搬运 | models + net + annotations + processor 模块搬运 | ApiService 能调用 |
| 3. 存储层 | SQLDelight schema 定义、Preferences KV 封装 | 能读写本地数据 |
| 4. 登录 | OAuth PKCE 流程 + Token 持久化 | 能登录拿到 Token |
| 5. 推荐页 | 推荐流 UI + ViewModel + Repository | 第一个可看页面 |
| 6. 发现页 | 标签排行 + 排行榜 | |
| 7. 搜索 | 搜索栏 + 筛选 + 结果 | |
| 8. 作品详情 | 大图查看 + 信息展示 | |
| 9. 图片加载完善 | Ugoira 解码器 | |
| 10. 用户/个人主页 | 画廊 + 关注 | |
| 11. macOS 交互 | 托盘菜单 + 触控板手势 | |
| 12. 打包 | DMG 产出 | 可分发的 .dmg |
