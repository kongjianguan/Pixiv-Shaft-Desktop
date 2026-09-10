# Architecture

English | [中文](architecture.zh.md)

PixivShaft Desktop is a macOS desktop client built from four Gradle modules: `:app` owns the Compose Desktop application and user workflows, `:net` owns Pixiv HTTP clients and network transports, `:store` owns local persistence, and `:models` owns Gson model types shared by the other modules.

## Composition

`ceui.pixiv.MainKt` starts the Compose Desktop application, initializes [`AppContainer`](../app/src/main/kotlin/ceui/pixiv/di/AppContainer.kt), and selects either [`LoginScreen`](../app/src/main/kotlin/ceui/pixiv/ui/screen/login/LoginScreen.kt) or [`MainScreen`](../app/src/main/kotlin/ceui/pixiv/ui/navigation/MainScreen.kt) from the process authentication state.

`AppContainer` is the application composition root. It creates the `SettingsStore`, `KeychainTokenStore`, OAuth token exchange and refresher, the [`Client`](../net/src/main/kotlin/ceui/pixiv/net/api/Client.kt), the Coil image loader, the SQLDelight database, and the persistent [`DownloadManager`](../app/src/main/kotlin/ceui/pixiv/download/DownloadManager.kt). Screens receive these services through their screen models or the container defaults.

The dependency direction is `:app` -> `:net`, `:store`, and `:models`; `:net` and `:store` depend on the model contracts they need, while UI code does not create Retrofit clients, database drivers, or image transport clients directly.

## Runtime paths

1. The application starts in [`Main.kt`](../app/src/main/kotlin/ceui/pixiv/Main.kt), installs macOS window and menu integrations, initializes the container, and observes `AuthState`.
2. The authenticated shell uses Voyager `TabNavigator` for the five main tabs. Each tab owns a Voyager `Navigator` for detail and overlay screens.
3. Screen models call `AppContainer.client` for Pixiv API, web API, and comic API requests. Shared feed behavior uses [`Pager`](../app/src/main/kotlin/ceui/pixiv/ui/state/Pager.kt), `UiState`, and the feed scaffolds under `ui/component`.
4. Artwork images use the singleton Coil loader created by [`ImageLoaderFactory`](../app/src/main/kotlin/ceui/pixiv/image/ImageLoaderFactory.kt). Pixiv image requests add the required `Referer` and user agent; direct Pixiv image mode uses the custom TLS and DNS path.
5. The download coordinator persists task state in SQLDelight, writes data to sibling `.part` files, and moves completed output into place only after the response succeeds.
6. Settings use Java Preferences and credentials use the macOS Keychain. The SQLDelight database is stored at `~/Library/Application Support/PixivShaft/shaft.db`.

## Network boundaries

The [`Client`](../net/src/main/kotlin/ceui/pixiv/net/api/Client.kt) exposes three typed Retrofit services: `API` for the Pixiv application API, `PixivWebApi` for web endpoints, and `ComicApi` for comic endpoints. Shared interceptors add headers, attach tokens, refresh expired access tokens, and apply the configured direct-connect transport.

When direct connect is enabled, the API clients try the Rust ECH transport and fall back to the Netty QUIC interceptor. The image client is separate because it needs CDN-specific DNS, TLS, protocol, and `Referer` handling that does not belong on API requests.

## Extension points

Add a Pixiv endpoint to the owning Retrofit interface in `:net`, then expose it through the existing `Client` service used by screen models. Add a new persisted value to the owning SQLDelight schema or `SettingsStore`, not to UI state alone. Add a user workflow as a Voyager `Screen` and screen model under `:app`, reusing the shared feed, state, comment, or download components where the behavior matches an existing contract.

Platform-specific behavior belongs under `app/src/main/kotlin/ceui/pixiv/platform` or the relevant network/storage adapter. Keep Compose screens independent of AppKit/JNA, Keychain process invocation, and raw OkHttp construction.

## Current references

- [Application subsystem](subsystems/application.md)
- [Network subsystem](subsystems/network.md)
- [Authentication subsystem](subsystems/authentication.md)
- [Storage subsystem](subsystems/storage.md)
- [Downloads subsystem](subsystems/downloads.md)
- [Development guide](development.md)
- [Build and package cookbook](cookbook/build-and-package.md)
- [Archived historical documents](archived/README.md)
