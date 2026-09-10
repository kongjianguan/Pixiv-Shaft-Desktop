# 架构

[English](architecture.md) | 中文

PixivShaft Desktop 是一个 macOS 桌面客户端，由 4 个 Gradle 模块组成：`:app` 负责 Compose Desktop 应用和用户流程，`:net` 负责 Pixiv HTTP 客户端与网络传输，`:store` 负责本地持久化，`:models` 负责供其他模块共享的 Gson 模型类型。

## 组合

[`ceui.pixiv.MainKt`](../app/src/main/kotlin/ceui/pixiv/Main.kt) 启动 Compose Desktop 应用，初始化 [`AppContainer`](../app/src/main/kotlin/ceui/pixiv/di/AppContainer.kt)，并根据进程认证状态在 [`LoginScreen`](../app/src/main/kotlin/ceui/pixiv/ui/screen/login/LoginScreen.kt) 和 [`MainScreen`](../app/src/main/kotlin/ceui/pixiv/ui/navigation/MainScreen.kt) 之间选择。

`AppContainer` 是应用的组合根。它创建 `SettingsStore`、`KeychainTokenStore`、OAuth token exchange 和 refresher、[`Client`](../net/src/main/kotlin/ceui/pixiv/net/api/Client.kt)、Coil 图片加载器、SQLDelight 数据库，以及持久化的 [`DownloadManager`](../app/src/main/kotlin/ceui/pixiv/download/DownloadManager.kt)。Screen model 通过自身依赖或容器默认值获取这些服务。

依赖方向是 `:app` -> `:net`、`:store` 和 `:models`；`:net` 与 `:store` 依赖它们所需的模型契约，UI 代码不会直接创建 Retrofit 客户端、数据库驱动或图片传输客户端。

## 运行时路径

1. 应用从 [`Main.kt`](../app/src/main/kotlin/ceui/pixiv/Main.kt) 启动，安装 macOS 窗口和菜单集成，初始化容器，并观察 `AuthState`。
2. 认证后的外壳使用 Voyager `TabNavigator` 管理 5 个主标签页。每个标签页为详情页和覆盖层页面拥有自己的 Voyager `Navigator`。
3. Screen model 通过 `AppContainer.client` 调用 Pixiv API、Web API 和漫画 API。共享的 feed 行为使用 [`Pager`](../app/src/main/kotlin/ceui/pixiv/ui/state/Pager.kt)、`UiState` 以及 `ui/component` 下的 feed scaffold。
4. 作品图片使用由 [`ImageLoaderFactory`](../app/src/main/kotlin/ceui/pixiv/image/ImageLoaderFactory.kt) 创建的单例 Coil 加载器。Pixiv 图片请求会加入必需的 `Referer` 和 user agent；直接 Pixiv 图片模式使用自定义 TLS 和 DNS 路径。
5. 下载协调器把任务状态持久化到 SQLDelight，先把数据写入同级 `.part` 文件，只有响应成功后才把已完成文件移动到目标位置。
6. 设置使用 Java Preferences，凭据使用 macOS Keychain。SQLDelight 数据库存放在 `~/Library/Application Support/PixivShaft/shaft.db`。

## 网络边界

[`Client`](../net/src/main/kotlin/ceui/pixiv/net/api/Client.kt) 暴露 3 个类型化 Retrofit 服务：用于 Pixiv 应用 API 的 `API`、用于 Web 端点的 `PixivWebApi`，以及用于漫画端点的 `ComicApi`。共享拦截器负责添加请求头、附加 token、刷新过期 access token，并应用配置的直连传输。

启用直连时，API 客户端先尝试 Rust ECH 传输，并回退到 Netty QUIC 拦截器。图片客户端独立存在，因为它需要 CDN 专用的 DNS、TLS、协议和 `Referer` 处理，这些不属于 API 请求。

## 扩展点

向 Pixiv 增加端点时，把它添加到 `:net` 中对应的 Retrofit 接口，再通过 screen model 使用的现有 `Client` 服务暴露。新增持久化值时，写入所属 SQLDelight schema 或 `SettingsStore`，不要只放在 UI state 中。新增用户流程时，在 `:app` 下创建 Voyager `Screen` 和 screen model，并在行为符合现有契约时复用共享的 feed、state、comment 或 download 组件。

平台专属行为应放在 `app/src/main/kotlin/ceui/pixiv/platform` 或对应的网络、存储适配器下。Compose screen 不应直接依赖 AppKit/JNA、Keychain 进程调用或底层 OkHttp 构造。

## 当前参考资料

- [应用子系统](subsystems/application.zh.md)
- [网络子系统](subsystems/network.zh.md)
- [认证子系统](subsystems/authentication.zh.md)
- [存储子系统](subsystems/storage.zh.md)
- [下载子系统](subsystems/downloads.zh.md)
- [开发指南](development.zh.md)
- [构建与打包手册](cookbook/build-and-package.zh.md)
- [历史文档](archived/README.zh.md)
