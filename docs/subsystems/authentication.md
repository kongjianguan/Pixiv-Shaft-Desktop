# Authentication

English | [中文](authentication.zh.md)

## Purpose

The authentication subsystem performs Pixiv OAuth authorization-code exchange with PKCE, stores the resulting credentials, and refreshes access tokens when an API request receives an authentication failure.

## Ownership and dependencies

The OAuth implementation lives in `:net/auth`. [`TokenExchange`](../../net/src/main/kotlin/ceui/pixiv/net/auth/TokenExchange.kt) exchanges an authorization code or refresh token. [`RealTokenRefresher`](../../net/src/main/kotlin/ceui/pixiv/net/auth/RealTokenRefresher.kt) implements the network token refresh contract. `:app` owns the login screen and updates `AppContainer.authState` after a successful exchange.

Credentials are stored by [`KeychainTokenStore`](../../store/src/main/kotlin/ceui/pixiv/store/KeychainTokenStore.kt), which delegates to the macOS `security` command through `KeychainKv`.

## Core concepts

`PkceUtils.generate()` creates a verifier and an S256 challenge. `LoginScreenModel` opens Pixiv's login URL, retains the verifier for the login attempt, accepts either a pasted raw code or a redirect URL, and sends the code and verifier to `TokenExchange`.

The current login UI uses Pixiv's fixed HTTPS redirect URI and manual code or redirect-URL submission. [`OAuthCallbackServer`](../../net/src/main/kotlin/ceui/pixiv/net/auth/OAuthCallbackServer.kt) exists as a localhost callback utility, but it is not part of the current `LoginScreenModel` flow.

## API and extension points

Keep PKCE generation and token endpoint details in `:net/auth`. Keep token persistence behind `TokenStore`; UI code should not invoke `security` or read Keychain entries directly. A new authentication flow must update the process `AuthState` only after the token store has accepted the credentials.

## Failure behavior

A missing or expired PKCE verifier produces a login error and requires a new login attempt. A malformed pasted URL or empty code is rejected before network access. Token exchange errors are exposed through `LoginState.Error`. Refresh failures return `null`, allowing the request interceptor to surface the original authentication failure.

Cancellation exceptions are rethrown by the login and refresh coroutines so screen disposal or request cancellation is not converted into a user-visible network failure.

## Verification

Run the PKCE, login screen model, token exchange, and token refresh tests through `./gradlew test`. Manual verification requires a Pixiv account and should confirm that a successful login survives application restart through the Keychain.
