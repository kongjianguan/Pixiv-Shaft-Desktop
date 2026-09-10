# Network

English | [中文](network.zh.md)

## Purpose

The network subsystem provides typed Pixiv API clients and the transport behavior required for Pixiv API and image CDN access on macOS networks where ordinary direct requests may fail.

## Ownership and dependencies

[`Client`](../../net/src/main/kotlin/ceui/pixiv/net/api/Client.kt) owns the Retrofit services for the Pixiv application API, web API, and comic API. `:app` calls those services through `AppContainer.client`.

API requests use `HeaderInterceptor`, `TokenFetcherInterceptor`, and the configured ECH and QUIC transport. Image requests use the separate client created by [`ImageLoaderFactory`](../../app/src/main/kotlin/ceui/pixiv/image/ImageLoaderFactory.kt), because image hosts need different DNS, TLS, protocol, and header behavior.

## Core concepts

`Settings.isDirectConnect` controls whether the direct transport interceptors are installed. The API clients prefer the Rust ECH path and retain the Netty QUIC interceptor as the fallback transport. The image client uses `Referer: https://app-api.pixiv.net/` and the Pixiv iOS user agent.

Pixiv image mode uses [`RubySSLSocketFactory`](../../net/src/main/kotlin/ceui/pixiv/net/image/RubySSLSocketFactory.kt), [`TrustAllCertManager`](../../net/src/main/kotlin/ceui/pixiv/net/image/TrustAllCertManager.kt), and [`HttpDns`](../../net/src/main/kotlin/ceui/pixiv/net/dns/HttpDns.kt) with HTTP/1.1. Alternative image hosts use the standard client path selected by `ImageHostManager`.

## API and extension points

Add typed endpoints to the appropriate Retrofit interface in `net/src/main/kotlin/ceui/pixiv/net/api`. Keep API-specific headers in the existing interceptors. Do not add an image CDN workaround to the API client or route API calls through the Coil image client.

The ECH native library is loaded by [`EchClient`](../../net/src/main/kotlin/ceui/pixiv/net/ech/EchClient.kt) from the development resource path or the packaged application resources. The Gradle `copyEchLib` task supplies that resource for application runs and packaging.

## Failure behavior

Transport failures surface through the Retrofit call to the screen model, which publishes `UiState.Error` or the workflow-specific error state. ECH warm-up runs asynchronously and does not block application startup; a failed warm-up is retried by the first request path.

The QUIC interceptor owns its connection lifecycle and is closed from `Client.close()`. A direct-connect failure can be retried through the configured fallback path; changing image host mode rebuilds the image client on the next application initialization.

## Verification

Run `./gradlew test` for network and interceptor tests. For a manual check, load an API feed and an image detail, then inspect the application log for transport failures. Image requests must include the Pixiv `Referer` and render through the configured image host.
