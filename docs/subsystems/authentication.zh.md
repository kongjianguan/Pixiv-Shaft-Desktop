# 认证

[English](authentication.md) | 中文

## 职责

认证子系统使用 PKCE 执行 Pixiv OAuth authorization-code exchange，保存产生的凭据，并在 API 请求收到认证失败时刷新 access token。

## 所有权与依赖

OAuth 实现位于 `:net/auth`。[`TokenExchange`](../../net/src/main/kotlin/ceui/pixiv/net/auth/TokenExchange.kt) 负责交换 authorization code 或 refresh token。[`RealTokenRefresher`](../../net/src/main/kotlin/ceui/pixiv/net/auth/RealTokenRefresher.kt) 实现网络 token refresh 契约。`:app` 负责登录页，并在交换成功后更新 `AppContainer.authState`。

凭据由 [`KeychainTokenStore`](../../store/src/main/kotlin/ceui/pixiv/store/KeychainTokenStore.kt) 保存；它通过 `KeychainKv` 调用 macOS `security` 命令。

## 核心概念

`PkceUtils.generate()` 创建 verifier 和 S256 challenge。`LoginScreenModel` 打开 Pixiv 登录 URL，为本次登录尝试保存 verifier，接受用户粘贴的原始 code 或 redirect URL，然后把 code 和 verifier 发送给 `TokenExchange`。

当前登录 UI 使用 Pixiv 固定的 HTTPS redirect URI，并手动提交 code 或 redirect URL。[`OAuthCallbackServer`](../../net/src/main/kotlin/ceui/pixiv/net/auth/OAuthCallbackServer.kt) 是现存的 localhost 回调工具，但不属于当前 `LoginScreenModel` 流程。

## API 与扩展点

将 PKCE 生成和 token endpoint 细节保留在 `:net/auth`。通过 `TokenStore` 隔离 token 持久化；UI 代码不应直接调用 `security` 或读取 Keychain 条目。新的认证流程只有在 token store 接受凭据后，才能更新进程 `AuthState`。

## 失败行为

缺失或过期的 PKCE verifier 会产生登录错误，并要求重新开始登录。格式错误的粘贴 URL 或空 code 会在访问网络前被拒绝。Token exchange 错误通过 `LoginState.Error` 暴露。刷新失败返回 `null`，允许请求拦截器暴露原始认证失败。

登录和刷新协程会重新抛出 cancellation exception，因此 screen 销毁或请求取消不会被转换成面向用户的网络失败。

## 验证

通过 `./gradlew test` 运行 PKCE、登录 screen model、token exchange 和 token refresh 测试。手动验证需要 Pixiv 账号，并应确认成功登录后应用重启仍能通过 Keychain 保持登录状态。
