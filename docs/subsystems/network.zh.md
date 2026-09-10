# 网络

[English](network.md) | 中文

## 职责

网络子系统提供类型化 Pixiv API 客户端，以及在普通直连请求可能失败的 macOS 网络环境中访问 Pixiv API 和图片 CDN 所需的传输行为。

## 所有权与依赖

[`Client`](../../net/src/main/kotlin/ceui/pixiv/net/api/Client.kt) 负责 Pixiv 应用 API、Web API 和漫画 API 的 Retrofit 服务。`:app` 通过 `AppContainer.client` 调用这些服务。

API 请求使用 `HeaderInterceptor`、`TokenFetcherInterceptor` 以及配置好的 ECH 和 QUIC 传输。图片请求使用由 [`ImageLoaderFactory`](../../app/src/main/kotlin/ceui/pixiv/image/ImageLoaderFactory.kt) 创建的独立客户端，因为图片主机需要不同的 DNS、TLS、协议和请求头行为。

## 核心概念

`Settings.isDirectConnect` 控制是否安装直连传输拦截器。API 客户端优先使用 Rust ECH 路径，并保留 Netty QUIC 拦截器作为回退传输。图片客户端使用 `Referer: https://app-api.pixiv.net/` 和 Pixiv iOS user agent。

Pixiv 图片模式使用 [`RubySSLSocketFactory`](../../net/src/main/kotlin/ceui/pixiv/net/image/RubySSLSocketFactory.kt)、[`TrustAllCertManager`](../../net/src/main/kotlin/ceui/pixiv/net/image/TrustAllCertManager.kt) 和 [`HttpDns`](../../net/src/main/kotlin/ceui/pixiv/net/dns/HttpDns.kt)，并使用 HTTP/1.1。其他图片主机由 `ImageHostManager` 选择标准客户端路径。

## API 与扩展点

向 `net/src/main/kotlin/ceui/pixiv/net/api` 中合适的 Retrofit 接口添加类型化端点。API 专用请求头应保留在现有拦截器中。不要把图片 CDN workaround 添加到 API 客户端，也不要让 API 请求经过 Coil 图片客户端。

ECH native library 由 [`EchClient`](../../net/src/main/kotlin/ceui/pixiv/net/ech/EchClient.kt) 从开发资源路径或打包应用资源中加载。Gradle 的 `copyEchLib` 任务为应用运行和打包提供该资源。

## 失败行为

传输失败通过 Retrofit 调用传递到 screen model，后者发布 `UiState.Error` 或工作流专属错误状态。ECH warm-up 异步运行，不会阻塞应用启动；warm-up 失败会在第一条请求路径中重试。

QUIC 拦截器负责连接生命周期，并从 `Client.close()` 关闭。直连失败可以通过配置的回退路径重试；更换图片主机模式后，图片客户端会在下一次应用初始化时重建。

## 验证

使用 `./gradlew test` 运行网络和拦截器测试。手动检查时加载一个 API feed 和一个图片详情页，然后检查应用日志中的传输失败。图片请求必须包含 Pixiv `Referer`，并通过配置的图片主机正常渲染。
