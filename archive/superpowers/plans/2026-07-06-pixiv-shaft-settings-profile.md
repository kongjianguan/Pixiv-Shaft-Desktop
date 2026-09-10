# Pixiv-Shaft macOS — Settings + Profile Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Settings page (anti-GFW config: direct connect, DNS, image host, logout) and Profile page (self profile + bookmarks + browse history), wire as 4th bottom-nav tab.

**Architecture:** `SettingsStore` gains setter methods (persist to `PreferencesKv`). `SettingsScreen` uses Material 3 `Switch`/`FilterChip`/`OutlinedTextField`. `ProfileScreen` calls `getSelfProfile()` + `getUserBookmarkedIllusts()` + SQLDelight `selectRecentIllusts()`. Both are Voyager `Screen` + `ScreenModel`. Profile becomes 4th tab in `MainScreen`.

**Tech Stack:** Compose Multiplatform 1.7.3, Voyager 1.0.1, Material 3, SQLDelight, existing `SettingsStore`/`KeychainTokenStore`/`ImageHostManager`.

## Global Constraints

- macOS aarch64, `JAVA_HOME=/opt/homebrew/opt/openjdk@21` for every `./gradlew`.
- Aliyun mirrors in all `repositories{}` blocks.
- `:app` uses `kotlin("jvm")` — source dir is `src/main/kotlin/`.
- Voyager 1.0.1: `Screen.Content()` (capital C), `rememberScreenModel { }`, `screenModelScope`, `Tab`/`TabOptions(index: UShort, icon: Painter?)`.
- `SettingsStore` currently read-only (getters only) — add setters that persist to `KvStore`.
- `ImageHostManager.setModeOrdinal(int)` / `setCustomHost(string)` already exist — call after persisting.
- Network settings changes (directConnect, DNS, imageHost) require app restart — the `Client`/`ImageLoader` OkHttpClients are built once at startup. Show "Restart required" UI.
- `Settings.isDirectConnect` gates QUIC interceptor in `Client` + `ImageLoaderFactory`.
- `Settings.imageHostMode` is an int ordinal: 0=PIXIV, 1=PIXIV_CAT, 2=PIXIV_RE, 3=PIXIV_NL, 4=CUSTOM.
- `ImageHostManager.requiresStandardClient()` = `mode != Mode.PIXIV` — non-PIXIV modes use standard TLS.
- `KeychainTokenStore.clear()` removes all tokens → `AppContainer.updateAuthState()` → auth gate switches to `LoginScreen`.
- CancellationException must be rethrown.
- `getSelfProfile()` returns `SelfProfile(profile: User, user_state: KUserState)`.
- `getUserBookmarkedIllusts(user_id, restrict)` returns `IllustResponse`.
- `getUserCreatedIllusts(user_id, type)` returns `IllustResponse`.
- `db.illustHistoryQueries.selectRecentIllusts(limit)` returns `List<IllustHistory>` (illustID, illustJson, time, type).
- No tests for UI composables (visual verification only).

---

## File Structure

| File | Responsibility |
|------|---------------|
| `store/src/main/kotlin/ceui/pixiv/store/SettingsStore.kt` | Add setter methods (persist to KvStore) |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/settings/SettingsScreen.kt` | Settings UI (toggles, selectors, logout) |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/settings/SettingsScreenModel.kt` | Settings state + apply changes |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/profile/ProfileScreen.kt` | Profile UI (avatar + stats + bookmarks/history tabs) |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/profile/ProfileScreenModel.kt` | Fetch selfProfile + bookmarks + history |
| `app/src/main/kotlin/ceui/pixiv/ui/navigation/MainScreen.kt` | Add ProfileTab (4th tab) |

---

## Task 1: SettingsStore Setters + SettingsScreen

**Files:**
- Modify: `store/src/main/kotlin/ceui/pixiv/store/SettingsStore.kt`
- Create: `app/src/main/kotlin/ceui/pixiv/ui/screen/settings/SettingsScreenModel.kt`
- Create: `app/src/main/kotlin/ceui/pixiv/ui/screen/settings/SettingsScreen.kt`

**Interfaces:**
- Consumes: `SettingsStore`, `ImageHostManager`, `AppContainer.tokenStore`, `AppContainer.settingsStore`, Voyager `Screen` + `ScreenModel`.
- Produces: `SettingsStore` with setters; `SettingsScreen` — Voyager `Screen`; `SettingsScreenModel`.

- [ ] **Step 1: Add setters to `SettingsStore.kt`**

Add these methods to `SettingsStore`:

```kotlin
    fun setDirectConnect(value: Boolean) {
        kv.putBoolean("isDirectConnect", value)
    }
    fun setUseSecureDns(value: Boolean) {
        kv.putBoolean("isUseSecureDns", value)
    }
    fun setImageHostMode(value: Int) {
        kv.putInt("imageHostMode", value)
    }
    fun setCustomImageHost(value: String) {
        kv.putString("customImageHost", value)
    }
```

- [ ] **Step 2: Create `SettingsScreenModel.kt`**

```kotlin
package ceui.pixiv.ui.screen.settings

import cafe.adriel.voyager.core.model.ScreenModel
import ceui.pixiv.di.AppContainer
import ceui.pixiv.net.imagehost.ImageHostManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsScreenModel : ScreenModel {

    private val settingsStore = AppContainer.settingsStore
    private val tokenStore = AppContainer.tokenStore

    val isDirectConnect: Boolean get() = settingsStore.isDirectConnect
    val isUseSecureDns: Boolean get() = settingsStore.isUseSecureDns
    val imageHostMode: Int get() = settingsStore.imageHostMode
    val customImageHost: String get() = settingsStore.customImageHost

    private val _restartRequired = MutableStateFlow(false)
    val restartRequired: StateFlow<Boolean> = _restartRequired.asStateFlow()

    fun setDirectConnect(value: Boolean) {
        settingsStore.setDirectConnect(value)
        _restartRequired.value = true
    }

    fun setUseSecureDns(value: Boolean) {
        settingsStore.setUseSecureDns(value)
        _restartRequired.value = true
    }

    fun setImageHostMode(mode: Int) {
        settingsStore.setImageHostMode(mode)
        ImageHostManager.setModeOrdinal(mode)
        ImageHostManager.setCustomHost(settingsStore.customImageHost)
        _restartRequired.value = true
    }

    fun setCustomImageHost(host: String) {
        settingsStore.setCustomImageHost(host)
        ImageHostManager.setCustomHost(host)
        _restartRequired.value = true
    }

    fun logout() {
        tokenStore.clear()
        AppContainer.updateAuthState()
    }
}
```

- [ ] **Step 3: Create `SettingsScreen.kt`**

```kotlin
package ceui.pixiv.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import ceui.pixiv.net.imagehost.ImageHostManager

class SettingsScreen : Screen {

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { SettingsScreenModel() }
        val restartRequired by screenModel.restartRequired.collectAsState()
        var customHost by remember { mutableStateOf(screenModel.customImageHost) }
        var currentHostMode by remember { mutableStateOf(screenModel.imageHostMode) }

        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)

            if (restartRequired) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Restart required — network settings changed",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Direct connect
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Direct Connect (QUIC)", style = MaterialTheme.typography.bodyLarge)
                    Text("Bypass GFW via QUIC + no-SNI TLS", style = MaterialTheme.typography.labelSmall)
                }
                Switch(
                    checked = screenModel.isDirectConnect,
                    onCheckedChange = screenModel::setDirectConnect
                )
            }

            // Secure DNS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Secure DNS (DoH)", style = MaterialTheme.typography.bodyLarge)
                    Text("DNS-over-HTTPS for pximg resolution", style = MaterialTheme.typography.labelSmall)
                }
                Switch(
                    checked = screenModel.isUseSecureDns,
                    onCheckedChange = screenModel::setUseSecureDns
                )
            }

            // Image host
            Text("Image Host", style = MaterialTheme.typography.bodyLarge)
            val hostModes = listOf("Pixiv" to 0, "pixiv.cat" to 1, "pixiv.re" to 2, "pixiv.nl" to 3, "Custom" to 4)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(hostModes) { (label, mode) ->
                    FilterChip(
                        selected = currentHostMode == mode,
                        onClick = {
                            currentHostMode = mode
                            screenModel.setImageHostMode(mode)
                        },
                        label = { Text(label) }
                    )
                }
            }

            if (currentHostMode == 4) {
                OutlinedTextField(
                    value = customHost,
                    onValueChange = {
                        customHost = it
                        screenModel.setCustomImageHost(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://your.proxy.com") },
                    singleLine = true
                )
            }

            // Logout
            Button(
                onClick = screenModel::logout,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Logout")
            }
        }
    }
}
```

Note: `LazyRow` with `items` needs `import androidx.compose.foundation.lazy.items`. The `FilterChip` `selected` state uses local `currentHostMode` state for immediate UI feedback.

- [ ] **Step 4: Compile**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(app/settings): SettingsStore setters + SettingsScreen（反墙配置 + 图片host + 登出）"
```

---

## Task 2: ProfileScreen + ProfileScreenModel

**Files:**
- Create: `app/src/main/kotlin/ceui/pixiv/ui/screen/profile/ProfileScreenModel.kt`
- Create: `app/src/main/kotlin/ceui/pixiv/ui/screen/profile/ProfileScreen.kt`

**Interfaces:**
- Consumes: `AppContainer.client` (for `getSelfProfile()` + `getUserBookmarkedIllusts()`), `AppContainer.database` (for `illustHistoryQueries`), `Pager`, `UiState`, `IllustCard`, Voyager `Screen` + `ScreenModel`.
- Produces: `ProfileScreen` — Voyager `Screen` with avatar + stats + bookmarks/history; `ProfileScreenModel`.

- [ ] **Step 1: Create `ProfileScreenModel.kt`**

```kotlin
package ceui.pixiv.ui.screen.profile

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.SelfProfile
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.state.Pager
import ceui.pixiv.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class ProfileScreenModel : ScreenModel {

    private val client = AppContainer.client
    private val db = AppContainer.database
    private val bookmarkPager = Pager<IllustResponse, Illust>(client, IllustResponse::class.java)

    private val _profileState = MutableStateFlow<UiState<SelfProfile>>(UiState.Loading)
    val profileState: StateFlow<UiState<SelfProfile>> = _profileState.asStateFlow()

    private val _bookmarksState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val bookmarksState: StateFlow<UiState<List<Illust>>> = _bookmarksState.asStateFlow()

    private val _history = MutableStateFlow<List<Illust>>(emptyList())
    val history: StateFlow<List<Illust>> = _history.asStateFlow()

    init {
        loadProfile()
        loadHistory()
    }

    private fun loadProfile() {
        screenModelScope.launch {
            _profileState.value = UiState.Loading
            try {
                val profile = client.appApi.getSelfProfile()
                _profileState.value = UiState.Success(profile)
                loadBookmarks(profile.profile.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _profileState.value = UiState.Error(e.message ?: "Failed to load profile")
            }
        }
    }

    private fun loadBookmarks(userId: Long) {
        screenModelScope.launch {
            _bookmarksState.value = UiState.Loading
            try {
                val resp = client.appApi.getUserBookmarkedIllusts(userId, "public")
                bookmarkPager.refresh(resp)
                _bookmarksState.value = UiState.Success(bookmarkPager.items.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _bookmarksState.value = UiState.Error(e.message ?: "Failed to load bookmarks")
            }
        }
    }

    private fun loadHistory() {
        screenModelScope.launch {
            try {
                val rows = db.queries.illustHistoryQueries.selectRecentIllusts(50)
                    .executeAsList()
                val illusts = rows.mapNotNull { row ->
                    try {
                        com.google.gson.Gson().fromJson(row.illustJson, Illust::class.java)
                    } catch (_: Exception) { null }
                }
                _history.value = illusts
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // History is best-effort
            }
        }
    }
}
```

Note: `SelfProfile.profile` is a `User` with `id: Long`. The `getSelfProfile()` requires auth. If not logged in, `profileState` will be `Error`. The `loadBookmarks` is called after profile loads, using the user ID. `loadHistory` reads from SQLDelight `illust_table` and deserializes JSON to `Illust` via Gson.

- [ ] **Step 2: Create `ProfileScreen.kt`**

```kotlin
package ceui.pixiv.ui.screen.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.IllustCard
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.component.UserAvatar
import ceui.pixiv.ui.screen.detail.IllustDetailScreen
import ceui.pixiv.ui.screen.settings.SettingsScreen
import ceui.pixiv.ui.state.UiState

class ProfileScreen : Screen {

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { ProfileScreenModel() }
        val profileState by screenModel.profileState.collectAsState()
        val bookmarksState by screenModel.bookmarksState.collectAsState()
        val history by screenModel.history.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        var selectedTab by remember { mutableStateOf(0) }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // Header: avatar + name + stats + settings button
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (val s = profileState) {
                        is UiState.Loading -> {
                            UserAvatar(url = null, size = 64)
                        }
                        is UiState.Error -> {
                            UserAvatar(url = null, size = 64)
                        }
                        is UiState.Success -> {
                            val user = s.data.profile
                            UserAvatar(
                                url = user.profile_image_urls?.px_50x50,
                                size = 64
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = user.name ?: "Unknown",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "${user.id}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                    IconButton(onClick = { navigator.push(SettingsScreen()) }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            }

            // Stats row
            item {
                profileState.let { state ->
                    if (state is UiState.Success) {
                        val profile = state.data.profile
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text("Illusts: ${profile.total_illusts}", style = MaterialTheme.typography.labelMedium)
                            Text("Bookmarks: ${profile.total_illust_bookmarks_public}", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // Tab row: Bookmarks / History
            item {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Bookmarks") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("History") }
                    )
                }
            }

            // Tab content
            when (selectedTab) {
                0 -> {
                    when (val s = bookmarksState) {
                        is UiState.Loading -> item { LoadingView(modifier = Modifier.height(200.dp)) }
                        is UiState.Error -> item {
                            ErrorView(s.message, {}, Modifier.height(200.dp))
                        }
                        is UiState.Success -> {
                            if (s.data.isEmpty()) {
                                item { Text("No bookmarks", modifier = Modifier.padding(16.dp)) }
                            } else {
                                items(s.data, key = { it.id }) { illust ->
                                    IllustCard(
                                        illust = illust,
                                        onClick = { id -> navigator.push(IllustDetailScreen(id)) },
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                1 -> {
                    if (history.isEmpty()) {
                        item { Text("No browse history", modifier = Modifier.padding(16.dp)) }
                    } else {
                        items(history, key = { it.id }) { illust ->
                            IllustCard(
                                illust = illust,
                                onClick = { id -> navigator.push(IllustDetailScreen(id)) },
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
```

Note: The `ProfileScreen` uses `LazyColumn` with `item { }` and `items()`. The bookmarks and history are displayed as single-column IllustCard lists. The settings button is in the header row. `TabRow` with 2 tabs (Bookmarks/History) switches content. The `profileState` success state shows avatar + name + stats.

If `Icons.Default.Settings` is not available, use `Icons.Default.Star` or any available icon. Check material-icons-core availability.

- [ ] **Step 3: Compile**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(app/profile): ProfileScreen + ScreenModel（头像 + 统计 + 收藏 + 浏览历史）"
```

---

## Task 3: Wire ProfileTab + Settings Navigation + Gate

**Files:**
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/navigation/MainScreen.kt`
- Modify: `app/src/main/kotlin/ceui/pixiv/Main.kt` (gate message)

**Interfaces:**
- Consumes: `ProfileScreen` (Task 2), `SettingsScreen` (Task 1), Voyager `Tab`/`TabNavigator`.
- Produces: `MainScreen` with 4 tabs (Recommend/Discover/Search/Profile); updated gate message.

- [ ] **Step 1: Add ProfileTab to `MainScreen.kt`**

Add import:
```kotlin
import ceui.pixiv.ui.screen.profile.ProfileScreen
import androidx.compose.material.icons.filled.Person
```

Add `ProfileTab` object after `SearchTab`:

```kotlin
object ProfileTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(index = 3u, title = "我的", icon = rememberVectorPainter(Icons.Default.Person))

    @Composable
    override fun Content() {
        Navigator(ProfileScreen()) { CurrentScreen() }
    }
}
```

Update `BottomBar()` to include `ProfileTab`:

```kotlin
listOf(RecommendTab, DiscoverTab, SearchTab, ProfileTab).forEach { tab ->
```

If `Icons.Default.Person` is not available, use `Icons.Default.Home` or `Icons.Default.Star` as fallback.

- [ ] **Step 2: Update gate message in `Main.kt`**

Change the `println` in `main()`:

```kotlin
println("PLAN 6 GATE PASSED — Settings + Profile + 4th tab")
```

- [ ] **Step 3: Compile**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run gate**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:run --no-daemon &
RUN_PID=$!
sleep 20
kill $RUN_PID 2>/dev/null
wait $RUN_PID 2>/dev/null
```

Expected:
1. `PLAN 6 GATE PASSED — Settings + Profile + 4th tab` printed
2. Bottom nav shows 4 tabs (推荐/发现/搜索/我的)
3. No crashes

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(app/nav): ProfileTab 第4个底部导航 + Plan 6 闸门"
```

---

## Self-Review

### Spec Coverage
- ✅ Settings page (design §4.2): Task 1 — directConnect, DNS, imageHost, customHost, logout
- ✅ Profile page (design §4.2): Task 2 — self profile + bookmarks + browse history
- ✅ 4th bottom nav tab (design §4.2): Task 3 — ProfileTab
- ✅ SettingsStore setters: Task 1 — persist to KvStore
- ⚠️ Deferred: UserScreen for viewing other users (Plan 7 or later)
- ⚠️ Deferred: Illust history insertion (the history is read but not written — need to insert illust JSON when viewing detail; defer to polish)

### Placeholder Scan
- No "TBD" or "TODO". Code blocks for all steps. Notes for icon fallbacks.

### Type Consistency
- `SettingsStore.setDirectConnect(Boolean)` / `setUseSecureDns(Boolean)` / `setImageHostMode(Int)` / `setCustomImageHost(String)` — consistent with `Settings` interface getters.
- `ImageHostManager.setModeOrdinal(Int)` / `setCustomHost(String)` — existing API, called in `SettingsScreenModel`.
- `ProfileScreenModel` uses `Pager<IllustResponse, Illust>` — same as Plan 4.
- `SelfProfile.profile: User` → `User.id: Long` → passed to `getUserBookmarkedIllusts(userId, "public")`.
- `TabOptions(index = 3u, ...)` — UShort, consistent with Plan 4.

### Risks
1. **Settings changes require restart**: `Client` and `ImageLoader` OkHttpClients are built once at startup. Settings page shows "Restart required" banner. This is documented behavior.
2. **Profile requires auth**: `getSelfProfile()` and `getUserBookmarkedIllusts()` require auth. If not logged in, `profileState = Error`. Expected.
3. **History not populated**: `illustHistoryQueries.selectRecentIllusts()` reads from `illust_table`, but nothing inserts into it during normal browsing (the POC inserted a test row). To populate history, need to insert illust JSON when viewing detail — deferred to polish.
4. **`Icons.Default.Person/Settings`**: may not be in material-icons-core. Fallback: `Icons.Default.Star` or `Icons.Default.Home`.
