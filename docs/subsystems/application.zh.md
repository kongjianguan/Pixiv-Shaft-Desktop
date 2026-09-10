# 应用

[English](application.md) | 中文

## 职责

应用子系统负责 Compose Desktop 进程、认证门、Voyager 导航、共享 UI 状态，以及 macOS 专属的窗口、菜单、托盘和触控板集成。

## 所有权与依赖

入口是 [`Main.kt`](../../app/src/main/kotlin/ceui/pixiv/Main.kt)。[`AppContainer`](../../app/src/main/kotlin/ceui/pixiv/di/AppContainer.kt) 在根导航器渲染页面前，负责组合 `:net`、`:store` 和图片服务。

已认证外壳是 [`MainScreen`](../../app/src/main/kotlin/ceui/pixiv/ui/navigation/MainScreen.kt)。它的标签页拥有嵌套导航器；设置、下载、历史、Pixivision、漫画和 R18 页面可以由根导航器推入。

## 核心概念

`AuthState` 有 `LoggedOut` 和 `LoggedIn` 两种状态。`Main.kt` 以该状态作为根导航器的 key，因此登录成功会用主外壳替换登录页，退出登录会返回登录页。

Screen model 负责异步加载，并向 Compose 暴露 `StateFlow`。通用 `UiState` 契约包含 `Loading`、`Success` 和 `Error`。Feed 页面使用 `Pager` 处理 `next_url` 分页，使用 `FeedScaffold` 或相关组件处理继续加载、空状态和错误状态。

## API 与扩展点

当一个页面拥有完整路由时，在 `app/src/main/kotlin/ceui/pixiv/ui/screen` 下添加 screen。当可复用的渲染或交互行为需要独立存在时，将其放在 `ui/component` 下。当行为需要 AppKit、AWT 或原生事件访问时，在 `platform` 下添加平台桥接。

网络和持久化对象必须在 `AppContainer` 和 screen model 中构造。Composable 应渲染状态并发出用户操作，不应构造 OkHttp 客户端或 SQLDelight driver。

## 失败行为

根应用观察认证状态，并在登录或退出登录后替换导航树。`Main.kt` 中的全局 Escape dispatcher 允许导航层关闭全屏图片模式，或在 Compose focus dispatch 不足时弹出当前嵌套导航器。

macOS 应用菜单通过 [`AppMenu`](../../app/src/main/kotlin/ceui/pixiv/platform/AppMenu.kt) 安装。菜单回调会检查当前认证状态，因此用户在退出登录时点击菜单，不会在之后重新登录时变成过期的导航请求。

## 验证

使用 `./gradlew :app:compileKotlin` 进行编译。使用已打包或正在运行的应用，验证登录状态、根导航、嵌套详情导航、Escape 处理、关闭到托盘，以及系统应用菜单。
