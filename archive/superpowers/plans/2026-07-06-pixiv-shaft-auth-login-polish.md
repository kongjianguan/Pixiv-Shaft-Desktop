# Pixiv-Shaft macOS — Auth + Login + UX Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add OAuth login flow (PKCE + manual code paste), wire AuthState to global navigation, and fix Plan 4 deferred UX issues (back navigation, clickable related/tags, search submit, CancellationException, FilterChip state).

**Architecture:** `AuthState` StateFlow in `AppContainer` monitors `KeychainTokenStore.isLoggedIn`. `LoginScreen` generates PKCE, opens browser, accepts pasted redirect URL, extracts code, exchanges via QUIC-enabled `TokenExchange`. `Main.kt` gates on `AuthState`: LoggedOut → `LoginScreen`, LoggedIn → `MainScreen`. UX polish: `TopAppBar` + `BackHandler` on detail, navigator passed to related IllustCard + TagChip, `keyboardActions` on search, `CancellationException` rethrow in all ScreenModels.

**Tech Stack:** Compose Multiplatform 1.7.3, Kotlin 2.1.20, Voyager 1.0.1, Coil 3.1.0, Material 3, existing `TokenExchange`/`PkceUtils`/`KeychainTokenStore` from Plan 3.

## Global Constraints

- macOS aarch64, `JAVA_HOME=/opt/homebrew/opt/openjdk@21` for every `./gradlew` invocation.
- Aliyun mirrors in all `repositories{}` blocks.
- `:app` uses `kotlin("jvm")` — source dir is `src/main/kotlin/`.
- Voyager 1.0.1: `Screen.Content()` (capital C), `rememberScreenModel { }`, `screenModelScope`, `LocalNavigator.currentOrThrow`, `navigator.push()`, `navigator.replaceAll()`.
- Anti-GFW stack (QUIC + no-SNI TLS + HttpDns) in `:net`/`:store` — do NOT break existing functionality.
- `TokenExchange` accepts `OkHttpClient` constructor param — pass QUIC-enabled client for OAuth.
- `PixivOAuthConfig.REDIRECT_URI` = `"https://app-api.pixiv.net/web/v1/users/auth/pixiv/callback"` (registered, cannot be changed).
- `PkceUtils.generate()` returns `PkcePair(verifier, challenge)`.
- `PkceUtils.buildAuthUrl(challenge, redirectUri)` builds the auth URL.
- `TokenExchange.exchangeCode(code, verifier)` returns `OAuthTokenResponse(accessToken, refreshToken, user)`.
- `KeychainTokenStore.saveTokens(accessToken, refreshToken, userJson)` stores tokens.
- `KeychainTokenStore.isLoggedIn` checks if `access_token` is in Keychain.
- `KeychainTokenStore.clear()` removes all tokens.
- `NettyQuicInterceptor` from `ceui.pixiv.net` — QUIC interceptor for `settings.isDirectConnect`.
- No tests for UI composables (visual verification only). AuthState gets a unit test.
- CancellationException must be rethrown, never swallowed: `catch (e: CancellationException) { throw e }` before generic `catch (e: Exception)`.

---

## File Structure

| File | Responsibility |
|------|---------------|
| `app/src/main/kotlin/ceui/pixiv/di/AppContainer.kt` | Add `authState: StateFlow<AuthState>`, QUIC-enabled `TokenExchange`, `close()` |
| `app/src/main/kotlin/ceui/pixiv/ui/auth/AuthState.kt` | `sealed class AuthState` (LoggedOut/LoggedIn) |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/login/LoginScreen.kt` | OAuth PKCE login UI (browser + code paste + exchange) |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/login/LoginScreenModel.kt` | PKCE generation, token exchange, state management |
| `app/src/main/kotlin/ceui/pixiv/Main.kt` | Auth gate: LoggedOut → LoginScreen, LoggedIn → MainScreen |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreen.kt` | Add TopAppBar + BackHandler + pass navigator to related/tags |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/search/SearchScreen.kt` | Add `initialQuery` param + `keyboardActions(onSearch)` |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/search/SearchScreenModel.kt` | Accept `initialQuery`, auto-search if provided |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/recommend/RecommendScreenModel.kt` | CancellationException rethrow |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreenModel.kt` | CancellationException rethrow |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/search/SearchScreenModel.kt` | CancellationException rethrow |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/discover/DiscoverScreen.kt` | FilterChip selected state |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/discover/DiscoverScreenModel.kt` | `loadRanking` guard + CancellationException rethrow |

---

## Task 1: AuthState + QUIC TokenExchange

**Files:**
- Create: `app/src/main/kotlin/ceui/pixiv/ui/auth/AuthState.kt`
- Modify: `app/src/main/kotlin/ceui/pixiv/di/AppContainer.kt`
- Test: `app/src/test/kotlin/ceui/pixiv/ui/auth/AuthStateTest.kt`

**Interfaces:**
- Consumes: `KeychainTokenStore`, `NettyQuicInterceptor`, `TokenExchange`, `Settings.isDirectConnect`.
- Produces: `AuthState` sealed class; `AppContainer.authState: StateFlow<AuthState>`; `AppContainer.tokenExchange: TokenExchange` (QUIC-enabled); `AppContainer.close()`.

- [ ] **Step 1: Create `AuthState.kt`**

```kotlin
package ceui.pixiv.ui.auth

sealed class AuthState {
    data object LoggedOut : AuthState()
    data class LoggedIn(val userId: Long? = null) : AuthState()
}
```

- [ ] **Step 2: Modify `AppContainer.kt` — add AuthState + QUIC TokenExchange + close()**

Add these imports and fields to `AppContainer`:

```kotlin
import ceui.pixiv.net.NettyQuicInterceptor
import ceui.pixiv.net.auth.TokenExchange
import ceui.pixiv.ui.auth.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
```

Add these fields after the existing `lateinit` fields:

```kotlin
    lateinit var tokenExchange: TokenExchange
        private set
    private var oauthQuicInterceptor: NettyQuicInterceptor? = null

    private val _authState = MutableStateFlow<AuthState>(AuthState.LoggedOut)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    fun updateAuthState() {
        _authState.value = if (tokenStore.isLoggedIn) AuthState.LoggedIn()
                           else AuthState.LoggedOut
    }
```

In `init()`, replace the existing `refresher` + `client` creation with:

```kotlin
        // QUIC-enabled OkHttpClient for OAuth (no HeaderInterceptor, no TokenFetcherInterceptor)
        val oauthClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .apply {
                if (settingsStore.isDirectConnect) {
                    oauthQuicInterceptor = NettyQuicInterceptor()
                    addInterceptor(oauthQuicInterceptor!!)
                }
            }
            .build()
        tokenExchange = TokenExchange(oauthClient)
        val refresher = RealTokenRefresher(tokenStore, tokenExchange)
        client = Client(settingsStore, tokenStore, refresher, DefaultLanguageProvider(), StdoutLogger)
```

At the end of `init()`, add:

```kotlin
        updateAuthState()
```

Add a `close()` method:

```kotlin
    fun close() {
        client.close()
        oauthQuicInterceptor?.close()
    }
```

- [ ] **Step 3: Write unit test for AuthState**

Create `app/src/test/kotlin/ceui/pixiv/ui/auth/AuthStateTest.kt`:

```kotlin
package ceui.pixiv.ui.auth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AuthStateTest {

    @Test
    fun `LoggedOut is object`() {
        assertInstanceOf(AuthState.LoggedOut::class.java, AuthState.LoggedOut)
    }

    @Test
    fun `LoggedIn holds userId`() {
        val state = AuthState.LoggedIn(userId = 12345L)
        assertEquals(12345L, state.userId)
    }

    @Test
    fun `LoggedIn with null userId`() {
        val state = AuthState.LoggedIn()
        assertNull(state.userId)
    }
}
```

- [ ] **Step 4: Compile and run tests**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:test --no-daemon
```

Expected: 3 tests PASS (plus the 2 Pager tests from Plan 4 Task 3 = 5 total).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/ceui/pixiv/ui/auth/ app/src/main/kotlin/ceui/pixiv/di/AppContainer.kt app/src/test/kotlin/ceui/pixiv/ui/auth/
git commit -m "feat(app/auth): AuthState StateFlow + QUIC-enabled TokenExchange"
```

---

## Task 2: LoginScreen + LoginScreenModel

**Files:**
- Create: `app/src/main/kotlin/ceui/pixiv/ui/screen/login/LoginScreenModel.kt`
- Create: `app/src/main/kotlin/ceui/pixiv/ui/screen/login/LoginScreen.kt`

**Interfaces:**
- Consumes: `AppContainer.tokenExchange` (QUIC-enabled), `AppContainer.tokenStore` (KeychainTokenStore), `PkceUtils.generate()`, `PkceUtils.buildAuthUrl()`, `PixivOAuthConfig.REDIRECT_URI`, Voyager `Screen` + `ScreenModel`, `java.awt.Desktop.browse()`.
- Produces: `LoginScreen` — Voyager `Screen` with OAuth PKCE flow; `LoginScreenModel` — manages PKCE state, token exchange, auth state update.

- [ ] **Step 1: Create `LoginScreenModel.kt`**

```kotlin
package ceui.pixiv.ui.screen.login

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.pixiv.di.AppContainer
import ceui.pixiv.net.auth.PkceUtils
import ceui.pixiv.ui.auth.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import java.net.URI

class LoginScreenModel : ScreenModel {

    private val tokenExchange = AppContainer.tokenExchange
    private val tokenStore = AppContainer.tokenStore

    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state.asStateFlow()

    private var pkceVerifier: String? = null

    fun startLogin() {
        val (verifier, challenge) = PkceUtils.generate()
        pkceVerifier = verifier
        val authUrl = PkceUtils.buildAuthUrl(challenge)
        _state.value = LoginState.AwaitingCode(authUrl)

        // Open browser
        try {
            java.awt.Desktop.getDesktop().browse(URI(authUrl))
        } catch (e: Exception) {
            // Browser open failed — user can copy URL manually
        }
    }

    fun submitCode(pastedUrlOrCode: String) {
        val verifier = pkceVerifier ?: run {
            _state.value = LoginState.Error("Login session expired. Click 'Login' again.")
            return
        }

        // Extract code from pasted URL or raw code
        val code = extractCode(pastedUrlOrCode)
        if (code.isNullOrBlank()) {
            _state.value = LoginState.Error("Could not extract authorization code. Paste the full redirect URL or the code value.")
            return
        }

        screenModelScope.launch {
            _state.value = LoginState.Exchanging
            try {
                val resp = tokenExchange.exchangeCode(code, verifier)
                if (resp.accessToken != null) {
                    tokenStore.saveTokens(resp.accessToken, resp.refreshToken, null)
                    AppContainer.updateAuthState()
                    _state.value = LoginState.Success
                } else {
                    _state.value = LoginState.Error("Token exchange returned no access_token.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = LoginState.Error(e.message ?: "Token exchange failed.")
            }
        }
    }

    private fun extractCode(input: String): String? {
        val trimmed = input.trim()
        // If it's a URL, extract the "code" query parameter
        val codeParam = "code="
        val codeIdx = trimmed.indexOf(codeParam, ignoreCase = true)
        if (codeIdx >= 0) {
            val start = codeIdx + codeParam.length
            val end = trimmed.indexOf('&', start).let { if (it < 0) trimmed.length else it }
            return trimmed.substring(start, end)
        }
        // Otherwise, treat the whole input as the code
        return trimmed.ifBlank { null }
    }
}

sealed class LoginState {
    data object Idle : LoginState()
    data class AwaitingCode(val authUrl: String) : LoginState()
    data object Exchanging : LoginState()
    data object Success : LoginState()
    data class Error(val message: String) : LoginState()
}
```

- [ ] **Step 2: Create `LoginScreen.kt`**

```kotlin
package ceui.pixiv.ui.screen.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.pixiv.ui.navigation.MainScreen

class LoginScreen : Screen {

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { LoginScreenModel() }
        val state by screenModel.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        var codeInput by remember { mutableStateOf("") }

        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Pixiv Shaft",
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "Login to access all features",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            when (val s = state) {
                is LoginState.Idle -> {
                    Button(onClick = screenModel::startLogin) {
                        Text("Login with Pixiv")
                    }
                }
                is LoginState.AwaitingCode -> {
                    Text(
                        text = "Browser opened. Authorize, then paste the redirect URL or code here.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    OutlinedButton(onClick = {
                        try {
                            java.awt.Desktop.getDesktop().browse(java.net.URI(s.authUrl))
                        } catch (_: Exception) {}
                    }) {
                        Text("Reopen browser")
                    }
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { codeInput = it },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        placeholder = { Text("Paste redirect URL or code") },
                        singleLine = true
                    )
                    Button(
                        onClick = { screenModel.submitCode(codeInput) },
                        enabled = codeInput.isNotBlank()
                    ) {
                        Text("Submit")
                    }
                }
                is LoginState.Exchanging -> {
                    CircularProgressIndicator()
                    Text("Exchanging code for token…", modifier = Modifier.padding(top = 8.dp))
                }
                is LoginState.Success -> {
                    Text("Login successful!", color = MaterialTheme.colorScheme.primary)
                    // Navigator will switch to MainScreen via auth gate in Main.kt
                }
                is LoginState.Error -> {
                    Text(
                        text = s.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    Button(onClick = screenModel::startLogin) {
                        Text("Try again")
                    }
                }
            }
        }
    }
}
```

Note: The `LoginState.Success` state doesn't directly navigate. The auth gate in `Main.kt` (Task 3) observes `AppContainer.authState` and switches to `MainScreen` when it becomes `LoggedIn`. The `navigator.replaceAll(MainScreen())` call is NOT needed here — the `Main.kt` recomposition handles it.

However, if the auth gate in `Main.kt` uses `key(authState)` to recreate the `Navigator`, the `LoginScreen` will be disposed and `MainScreen` will be created. This is the correct flow.

- [ ] **Step 3: Compile**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/ceui/pixiv/ui/screen/login/
git commit -m "feat(app/login): LoginScreen + ScreenModel（OAuth PKCE 手动粘贴 code）"
```

---

## Task 3: Auth Gate in Main.kt + IllustDetailScreen Back Navigation

**Files:**
- Modify: `app/src/main/kotlin/ceui/pixiv/Main.kt`
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreen.kt`

**Interfaces:**
- Consumes: `AppContainer.authState`, `LoginScreen` (Task 2), `MainScreen` (Plan 4), Voyager `BackHandler`.
- Produces: `Main.kt` with auth gate; `IllustDetailScreen` with TopAppBar + back button.

- [ ] **Step 1: Modify `Main.kt` — auth gate**

Replace the `Navigator(MainScreen())` line with an auth-aware gate:

```kotlin
package ceui.pixiv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import cafe.adriel.voyager.navigator.Navigator
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.auth.AuthState
import ceui.pixiv.ui.navigation.MainScreen
import ceui.pixiv.ui.screen.login.LoginScreen
import ceui.pixiv.ui.theme.ShaftTheme

fun main() = application {
    AppContainer.init()
    Window(
        onCloseRequest = ::exitApplication,
        title = "Pixiv Shaft"
    ) {
        ShaftTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                val authState by AppContainer.authState.collectAsState()
                key(authState) {
                    Navigator(
                        if (authState is AuthState.LoggedIn) MainScreen()
                        else LoginScreen()
                    )
                }
            }
        }
    }
}
```

The `key(authState)` forces the `Navigator` to be recreated when the auth state changes. When login succeeds, `AppContainer.updateAuthState()` sets `authState = LoggedIn`, triggering recomposition, which recreates `Navigator` with `MainScreen()`. When logout happens (future), `authState = LoggedOut` recreates `Navigator` with `LoginScreen()`.

- [ ] **Step 2: Modify `IllustDetailScreen.kt` — add TopAppBar + BackHandler**

In `IllustDetailScreen.kt`, wrap the `IllustDetailContent` in a `Scaffold` with a `TopAppBar`:

Add these imports:
```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
```

Change the `Content()` composable to:

```kotlin
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { IllustDetailScreenModel(illustId) }
        val illustState by screenModel.illustState.collectAsState()
        val relatedState by screenModel.relatedState.collectAsState()
        val navigator = LocalNavigator.currentOrThrow

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Illust #$illustId") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (val s = illustState) {
                    is UiState.Loading -> LoadingView()
                    is UiState.Error -> ErrorView(s.message, {})
                    is UiState.Success -> IllustDetailContent(
                        illust = s.data,
                        relatedState = relatedState,
                        onIllustClick = { id -> navigator.push(IllustDetailScreen(id)) },
                        onTagClick = { tag -> navigator.push(SearchScreen(initialQuery = tag)) }
                    )
                }
            }
        }
    }
```

Update `IllustDetailContent` to accept `onIllustClick` and `onTagClick`:

```kotlin
@Composable
private fun IllustDetailContent(
    illust: Illust,
    relatedState: UiState<List<Illust>>,
    onIllustClick: (Long) -> Unit,
    onTagClick: (String) -> Unit
) {
    // ... existing code ...

    // In the tags section, change TagChip onClick:
    TagChip(tag = tag, onClick = onTagClick)

    // In the related works section, change IllustCard onClick:
    IllustCard(
        illust = relatedIllust,
        onClick = onIllustClick,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}
```

Add the import for `SearchScreen`:
```kotlin
import ceui.pixiv.ui.screen.search.SearchScreen
```

Note: `SearchScreen(initialQuery = tag)` requires Task 4's modification to accept `initialQuery`. For now, if `SearchScreen` doesn't have the `initialQuery` parameter yet, use `SearchScreen()` instead and note that tag-to-search will be wired in Task 4.

If `Icons.AutoMirrored.Filled.ArrowBack` is not available, use `Icons.Default.Close` or `Icons.Default.Home` as a fallback. `AutoMirrored` is available in Compose Multiplatform 1.7.3.

- [ ] **Step 3: Compile**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL. If `SearchScreen(initialQuery = tag)` doesn't compile (Task 4 not yet done), change to `SearchScreen()` for now.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/ceui/pixiv/Main.kt app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreen.kt
git commit -m "feat(app): auth gate in Main.kt + IllustDetailScreen TopAppBar 返回导航"
```

---

## Task 4: SearchScreen initialQuery + Keyboard Submit

**Files:**
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/search/SearchScreen.kt`
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/search/SearchScreenModel.kt`

**Interfaces:**
- Consumes: Voyager `Screen`, `SearchScreenModel`.
- Produces: `SearchScreen(initialQuery: String? = null)` — accepts pre-filled query for tag-to-search; `OutlinedTextField` with `keyboardActions(onSearch)`.

- [ ] **Step 1: Modify `SearchScreenModel.kt` — accept `initialQuery`**

Change the class to accept an `initialQuery` parameter and auto-search if provided:

```kotlin
class SearchScreenModel(
    initialQuery: String? = null
) : ScreenModel {

    // ... existing fields ...

    init {
        loadHistory()
        if (initialQuery != null && initialQuery.isNotBlank()) {
            _query.value = initialQuery
            search(initialQuery)
        }
    }

    // ... rest unchanged ...
}
```

- [ ] **Step 2: Modify `SearchScreen.kt` — add `initialQuery` constructor + keyboard submit**

Change the class signature:

```kotlin
class SearchScreen(
    private val initialQuery: String? = null
) : Screen {

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { SearchScreenModel(initialQuery) }
        // ... rest unchanged ...
```

Add `keyboardActions` and `imeAction` to the `OutlinedTextField`:

```kotlin
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction

// In the OutlinedTextField:
OutlinedTextField(
    value = query,
    onValueChange = screenModel::updateQuery,
    modifier = Modifier
        .fillMaxWidth()
        .padding(8.dp),
    placeholder = { Text("Search illusts…") },
    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
    trailingIcon = {
        if (query.isNotEmpty()) {
            IconButton(onClick = { screenModel.updateQuery("") }) {
                Icon(Icons.Default.Close, contentDescription = "Clear")
            }
        }
    },
    singleLine = true,
    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    keyboardActions = KeyboardActions(onSearch = { screenModel.search(query) })
)
```

- [ ] **Step 3: Compile**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/ceui/pixiv/ui/screen/search/
git commit -m "feat(app/search): initialQuery + 键盘搜索提交 + TagChip→搜索导航"
```

---

## Task 5: Code Quality Polish (CancellationException + loadRanking guard + FilterChip state)

**Files:**
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/recommend/RecommendScreenModel.kt`
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreenModel.kt`
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/search/SearchScreenModel.kt`
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/discover/DiscoverScreenModel.kt`
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/discover/DiscoverScreen.kt`

**Interfaces:**
- Consumes: existing ScreenModels.
- Produces: CancellationException rethrown in all catch blocks; `loadRanking` guard; FilterChip `selected` state.

- [ ] **Step 1: Add CancellationException rethrow to all ScreenModels**

In each of the 4 ScreenModel files, add this import:
```kotlin
import kotlin.coroutines.cancellation.CancellationException
```

In each `catch (e: Exception)` block, add a `catch (e: CancellationException) { throw e }` BEFORE the generic catch. The pattern for each try-catch:

```kotlin
try {
    // ... existing code ...
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    // ... existing error handling ...
}
```

Files to update:
1. `RecommendScreenModel.kt` — `refresh()` and `loadMore()` (2 catch blocks)
2. `IllustDetailScreenModel.kt` — `loadIllust()` and `loadRelated()` (2 catch blocks)
3. `SearchScreenModel.kt` — `search()` and `loadMore()` (2 catch blocks; `loadMore` already has `catch (_: Exception)` — add CancellationException before it)
4. `DiscoverScreenModel.kt` — `loadTags()` and `loadRanking()` (2 catch blocks)

- [ ] **Step 2: Add `_isLoading` guard to `DiscoverScreenModel.loadRanking()`**

In `DiscoverScreenModel.kt`, add a `_isLoading` field and guard:

```kotlin
    private val _isLoading = MutableStateFlow(false)
    private var _currentMode = "day"

    fun loadRanking(mode: String) {
        _currentMode = mode
        if (_isLoading.value) return
        screenModelScope.launch {
            _isLoading.value = true
            _rankingState.value = UiState.Loading
            try {
                val resp = client.appApi.getRankingIllusts(mode)
                // Only update if this is still the current mode
                if (_currentMode == mode) {
                    _rankingState.value = UiState.Success(resp.illusts)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_currentMode == mode) {
                    _rankingState.value = UiState.Error(e.message ?: "Failed to load ranking")
                }
            } finally {
                _isLoading.value = false
            }
        }
    }
```

Also add a `currentMode: StateFlow<String>` for the FilterChip selected state:

```kotlin
    private val _currentModeFlow = MutableStateFlow("day")
    val currentMode: StateFlow<String> = _currentModeFlow.asStateFlow()
```

Update `loadRanking` to set `_currentModeFlow.value = mode` at the start.

- [ ] **Step 3: Wire FilterChip `selected` state in `DiscoverScreen.kt`**

In `DiscoverScreen.kt`, collect `currentMode` and use it for FilterChip `selected`:

```kotlin
val currentMode by screenModel.currentMode.collectAsState()

// In the FilterChip:
FilterChip(
    selected = (currentMode == mode),
    onClick = { screenModel.loadRanking(mode) },
    label = { Text(label) }
)
```

- [ ] **Step 4: Compile**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "fix(app): CancellationException rethrow + loadRanking guard + FilterChip selected 状态"
```

---

## Task 6: Gate

**Files:**
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/navigation/MainScreen.kt` (update gate message)

**Interfaces:**
- Consumes: all previous tasks.

- [ ] **Step 1: Update gate message in `MainScreen.kt`**

Change the `LaunchedEffect` in `MainScreen.Content()`:

```kotlin
LaunchedEffect(Unit) {
    println("PLAN 5 GATE PASSED — Auth gate + Login + UX polish active")
}
```

- [ ] **Step 2: Compile**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the app (gate)**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:run --no-daemon &
RUN_PID=$!
sleep 20
kill $RUN_PID 2>/dev/null
wait $RUN_PID 2>/dev/null
```

Expected:
1. App launches
2. If not logged in: `LoginScreen` shows "Login with Pixiv" button
3. If logged in (token from previous OAuth): `MainScreen` shows with bottom nav
4. `PLAN 5 GATE PASSED — Auth gate + Login + UX polish active` printed in console
5. No crashes

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(app): Plan 5 闸门 — auth gate + login + UX polish"
```

---

## Self-Review

### Spec Coverage
- ✅ AuthState StateFlow (design §6.2): Task 1 — `sealed class AuthState` + `AppContainer.authState`
- ✅ LoginScreen (design §4.2): Task 2 — OAuth PKCE manual code paste
- ✅ Auth gate (design §6.2): Task 3 — `Main.kt` gates on `authState`
- ✅ TopAppBar + back navigation (Plan 4 M2): Task 3 — `IllustDetailScreen` TopAppBar + `navigator.pop()`
- ✅ Related IllustCard navigation (Plan 4 M4): Task 3 — `onIllustClick` passed to `IllustDetailContent`
- ✅ TagChip → search (Plan 4 M5): Task 3 — `onTagClick` → `SearchScreen(initialQuery = tag)`
- ✅ Search keyboard submit (Plan 4 M3): Task 4 — `keyboardActions(onSearch)` + `imeAction = ImeAction.Search`
- ✅ CancellationException rethrow (Plan 4 M1): Task 5 — all 8 catch blocks in 4 ScreenModels
- ✅ loadRanking guard (Plan 4 M11): Task 5 — `_isLoading` + `_currentMode` check
- ✅ FilterChip selected state (Plan 4 M7): Task 5 — `currentMode` StateFlow + `selected = (currentMode == mode)`
- ⚠️ Deferred: OAuth desktop redirect_uri design gap (Pixiv validates registered URL; manual code paste works but is not elegant — future WebView or custom scheme)
- ⚠️ Deferred: ErrorView retry callbacks (Plan 4 M6) — not addressed (low impact)
- ⚠️ Deferred: Duplicate search keywords (Plan 4 M9) — not addressed
- ⚠️ Deferred: SQLDelight blocking on Main (Plan 4 M10) — not addressed
- ⚠️ Deferred: Tab switches dispose ScreenModels (Plan 4 M12) — Voyager default, not fixable without custom tab navigator

### Placeholder Scan
- No "TBD" or "TODO" in plan steps.
- Code blocks provided for all implementation steps.
- Notes explain how to handle API uncertainties (Icons.AutoMirrored, SearchScreen initialQuery ordering).

### Type Consistency
- `AuthState` used consistently in Tasks 1, 2, 3.
- `LoginState` sealed class used in Task 2.
- `SearchScreen(initialQuery: String? = null)` used in Tasks 3, 4.
- `SearchScreenModel(initialQuery: String? = null)` used in Task 4.
- `onIllustClick: (Long) -> Unit` and `onTagClick: (String) -> Unit` passed from `Content()` to `IllustDetailContent` in Task 3.
- `CancellationException` import is `kotlin.coroutines.cancellation.CancellationException` — consistent across all 4 ScreenModels.

### Risks
1. **OAuth manual code paste UX**: not elegant but functional. The user opens browser, authorizes, copies the redirect URL, pastes into the app. Future improvement: embedded WebView or OS-level URL intercept.
2. **QUIC for OAuth**: `TokenExchange` uses QUIC-enabled OkHttpClient. If `oauth.secure.pixiv.net` is not behind Cloudflare or CF rejects the SNI, the token exchange will fail. Fallback: user uses VPN for initial login.
3. **`key(authState)` recreates Navigator**: loses back stack on auth state change. Acceptable for login/logout transitions (rare events).
4. **Task 3 depends on Task 4**: `SearchScreen(initialQuery = tag)` in Task 3 requires Task 4's `initialQuery` parameter. The plan notes: if Task 4 is not yet done, use `SearchScreen()` temporarily. The implementer should do Task 4 before Task 3, or use the fallback.
5. **Icons.AutoMirrored.Filled.ArrowBack**: available in Compose Multiplatform 1.7.3. If not, fallback to `Icons.Default.Close`.
