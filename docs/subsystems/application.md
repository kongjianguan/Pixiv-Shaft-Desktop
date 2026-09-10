# Application

English | [中文](application.zh.md)

## Purpose

The application subsystem owns the Compose Desktop process, authentication gate, Voyager navigation, shared UI state, and macOS-specific window, menu, tray, and trackpad integrations.

## Ownership and dependencies

The entry point is [`Main.kt`](../../app/src/main/kotlin/ceui/pixiv/Main.kt). [`AppContainer`](../../app/src/main/kotlin/ceui/pixiv/di/AppContainer.kt) composes the `:net`, `:store`, and image services before the root navigator renders a screen.

The authenticated shell is [`MainScreen`](../../app/src/main/kotlin/ceui/pixiv/ui/navigation/MainScreen.kt). Its tabs own nested navigators, while settings, downloads, history, Pixivision, comic, and R18 screens can be pushed by the root navigator.

## Core concepts

`AuthState` has `LoggedOut` and `LoggedIn` states. `Main.kt` keys the root navigator by that state, so a successful login replaces the login screen with the main shell and logout returns to the login screen.

Screen models own asynchronous loading and expose `StateFlow` values to Compose. The common `UiState` contract is `Loading`, `Success`, or `Error`. Feed screens use `Pager` for `next_url` pagination and `FeedScaffold` or related components for load-more and empty/error states.

## API and extension points

Add a screen under `app/src/main/kotlin/ceui/pixiv/ui/screen` when it owns a complete route. Add reusable rendering or interaction behavior under `ui/component`. Add platform bridges under `platform` when the behavior needs AppKit, AWT, or native event access.

Keep network and persistence construction in `AppContainer` and screen models. A composable should render state and emit user actions; it should not construct an OkHttp client or SQLDelight driver.

## Failure behavior

The root application observes authentication state and replaces the navigation tree after login or logout. The global Escape dispatcher in `Main.kt` lets the navigation layer close full-screen image mode or pop the active nested navigator when Compose focus dispatch is insufficient.

The macOS application menu is installed through [`AppMenu`](../../app/src/main/kotlin/ceui/pixiv/platform/AppMenu.kt). Menu callbacks guard the current authentication state so a click made while logged out does not become a stale navigation request after a later login.

## Verification

Use `./gradlew :app:compileKotlin` for compilation. Exercise login state, root navigation, nested detail navigation, Escape handling, close-to-tray, and the system application menu with the packaged or running application.
