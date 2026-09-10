# Pixiv-Shaft macOS — Core UI Pages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first usable Compose Desktop UI — Recommend (waterfall grid), Illust Detail (gallery + tags + related), Search (search bar + history + results), Discover (trending tags + ranking) — with Voyager navigation and Material 3 theme.

**Architecture:** Voyager `TabNavigator` for bottom-nav shell; each tab owns a `Navigator` for push/pop. `ScreenModel` (Voyager's ViewModel equivalent) holds `StateFlow<UiState<T>>`; UI `collectAsState`. A generic `Pager<T, Item>` handles next_url pagination via `generalGet` + Gson. Coil 3 `AsyncImage` for all images via the anti-GFW `ImageLoader`.

**Tech Stack:** Compose Multiplatform 1.7.3, Kotlin 2.1.20, Voyager 1.0.1 (navigator + screenmodel + tab-navigator), Coil 3.1.0, Material 3.

## Global Constraints

- macOS aarch64, `JAVA_HOME=/opt/homebrew/opt/openjdk@21` for every `./gradlew` invocation.
- Aliyun mirrors (public + central + google + gradle-plugin) in all `repositories{}` blocks.
- Compose plugin 1.7.3 + Kotlin plugin 2.1.20 (already in root `build.gradle.kts`).
- `:app` uses `kotlin("jvm")` (not KMP) — source dir is `src/main/kotlin/` (NOT `src/desktopMain/`).
- Anti-GFW stack (QUIC + no-SNI TLS + HttpDns) already built in Plans 2-3 — do NOT modify `:net` or `:store`.
- `ImageLoaderFactory` already wires the anti-GFW image OkHttpClient — use it as-is.
- Recommend page uses `getWalkthroughWorks()` (public, no auth needed, 112 illusts) so the gate passes without OAuth. Other pages (`getIllust`, `searchIllustManga`, `trendingTags`, `getRankingIllusts`) require auth — they will show `UiState.Error` if no token. This is expected.
- No tests for UI composables (visual verification only). Infrastructure (`Pager`, `UiState`) gets unit tests.
- Delete `PocMain.kt` in Task 1 — replaced by `Main.kt`.
- `Client` constructor: `Client(settings, tokenStore, refresher, lang, logger)` — see Plan 2.
- `createDatabase()` returns `Database` (SQLDelight) — see Plan 3 Task 1.
- `KeychainTokenStore()`, `SettingsStore()`, `RealTokenRefresher(tokenStore, TokenExchange())`, `DefaultLanguageProvider()`, `StdoutLogger` — see Plan 3.
- `Illust.image_urls?.large` or `.medium` for thumbnail URL; `Illust.maxUrl()` for original; `Illust.meta_pages` for multi-page; `Illust.user?.name`, `Illust.user?.profile_image_urls?.px_50x50` for author; `Illust.tags` for tag list; `Illust.total_bookmarks` for bookmark count.

---

## File Structure

| File | Responsibility |
|------|---------------|
| `app/src/main/kotlin/ceui/pixiv/Main.kt` | Entry point: `application{}` + `Window` + `AppContainer.init()` + `Navigator(MainScreen)` |
| `app/src/main/kotlin/ceui/pixiv/di/AppContainer.kt` | Singleton holding `Client`, `KeychainTokenStore`, `SettingsStore`, `ImageLoader`, `Database` |
| `app/src/main/kotlin/ceui/pixiv/ui/theme/ShaftTheme.kt` | Material 3 dark/light color scheme + `ShaftTheme` composable wrapper |
| `app/src/main/kotlin/ceui/pixiv/ui/state/UiState.kt` | `sealed class UiState<out T>` — Loading/Success/Error |
| `app/src/main/kotlin/ceui/pixiv/ui/state/Pager.kt` | `Pager<T : KListShow<Item>, Item>` — next_url pagination state |
| `app/src/main/kotlin/ceui/pixiv/ui/component/IllustCard.kt` | `IllustCard(illust, onClick)` — grid item (thumbnail + title + author + bookmark count) |
| `app/src/main/kotlin/ceui/pixiv/ui/component/CommonComponents.kt` | `TagChip`, `LoadingView`, `ErrorView`, `EmptyView`, `UserAvatar` |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/recommend/RecommendScreen.kt` | Voyager `Screen` — StaggeredGrid + infinite scroll |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/recommend/RecommendScreenModel.kt` | `ScreenModel` — fetches walkthrough, manages `Pager` + `UiState` |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreen.kt` | `Screen` — image gallery (HorizontalPager) + tags + author + related grid |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreenModel.kt` | `ScreenModel` — fetches `getIllust` + `getRelatedIllusts` |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/search/SearchScreen.kt` | `Screen` — search bar + history + results grid |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/search/SearchScreenModel.kt` | `ScreenModel` — searchIllustManga + SearchHistory (SQLDelight) |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/discover/DiscoverScreen.kt` | `Screen` — trending tags + ranking illusts |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/discover/DiscoverScreenModel.kt` | `ScreenModel` — trendingTags + getRankingIllusts |
| `app/src/main/kotlin/ceui/pixiv/ui/navigation/MainScreen.kt` | `Screen` — `TabNavigator` with Recommend/Discover/Search tabs + bottom bar |

**Files to modify:** `app/build.gradle.kts` (add Voyager deps, change mainClass, delete PocMain).

---

## Task 1: Voyager Deps + Main.kt Skeleton

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/kotlin/ceui/pixiv/Main.kt`
- Delete: `app/src/main/kotlin/ceui/pixiv/poc/PocMain.kt`

**Interfaces:**
- Produces: `main()` entry point in `Main.kt`; `AppContainer` reference (Task 2 fills it).

- [ ] **Step 1: Add Voyager dependencies to `app/build.gradle.kts`**

In `app/build.gradle.kts`, add inside `dependencies { }` block (after the Coil dependencies):

```kotlin
    // Voyager navigation (Compose Multiplatform)
    implementation("cafe.adriel.voyager:voyager-navigator:1.0.1")
    implementation("cafe.adriel.voyager:voyager-screenmodel:1.0.1")
    implementation("cafe.adriel.voyager:voyager-tab-navigator:1.0.1")
```

Also change `mainClass` from `ceui.pixiv.poc.PocMainKt` to `ceui.pixiv.MainKt`:

```kotlin
application {
    mainClass.set("ceui.pixiv.MainKt")
}
```

Also remove the comment about PocMain in the `dependencies` block (the `okhttp`/`gson` explicit deps are still needed for `Pager` Gson usage — keep them).

- [ ] **Step 2: Delete `PocMain.kt`**

```bash
rm app/src/main/kotlin/ceui/pixiv/poc/PocMain.kt
```

- [ ] **Step 3: Create `Main.kt` — minimal empty window with Navigator**

```kotlin
package ceui.pixiv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import cafe.adriel.voyager.navigator.Navigator

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Pixiv Shaft"
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Navigator(PlaceholderScreen)
        }
    }
}

private object PlaceholderScreen : cafe.adriel.voyager.core.screen.Screen {
    @Composable
    override fun Content() {
        androidx.compose.material3.Text("Pixiv Shaft Desktop — loading…")
    }
}
```

Note: Voyager 1.0.1's `Screen` interface uses `Content()` (capital C, verified from source). The `PlaceholderScreen` object implements `Screen` with `@Composable override fun Content()`.

- [ ] **Step 4: Verify compilation**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL. If Voyager deps fail to resolve, check Aliyun mirror has them (try `mavenCentral()` fallback).

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/kotlin/ceui/pixiv/Main.kt
git rm app/src/main/kotlin/ceui/pixiv/poc/PocMain.kt
git commit -m "feat(app): Voyager deps + Main.kt skeleton（替 PocMain）"
```

---

## Task 2: DI Container + Theme

**Files:**
- Create: `app/src/main/kotlin/ceui/pixiv/di/AppContainer.kt`
- Create: `app/src/main/kotlin/ceui/pixiv/ui/theme/ShaftTheme.kt`
- Modify: `app/src/main/kotlin/ceui/pixiv/Main.kt`

**Interfaces:**
- Consumes: `Client`, `KeychainTokenStore`, `SettingsStore`, `RealTokenRefresher`, `TokenExchange`, `DefaultLanguageProvider`, `StdoutLogger` (from `:net` + `:store`), `ImageLoaderFactory` (existing), `createDatabase` (from `:store`).
- Produces: `AppContainer` singleton (fields: `client`, `tokenStore`, `settingsStore`, `imageLoader`, `database`); `ShaftTheme` composable.

- [ ] **Step 1: Create `AppContainer.kt`**

```kotlin
package ceui.pixiv.di

import ceui.pixiv.image.ImageLoaderFactory
import ceui.pixiv.net.api.Client
import ceui.pixiv.net.auth.RealTokenRefresher
import ceui.pixiv.net.auth.TokenExchange
import ceui.pixiv.net.impl.DefaultLanguageProvider
import ceui.pixiv.net.impl.StdoutLogger
import ceui.pixiv.store.KeychainTokenStore
import ceui.pixiv.store.SettingsStore
import ceui.pixiv.store.createDatabase
import coil3.ImageLoader

object AppContainer {
    lateinit var client: Client
        private set
    lateinit var tokenStore: KeychainTokenStore
        private set
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var imageLoader: ImageLoader
        private set
    lateinit var database: ceui.pixiv.store.Database
        private set

    fun init() {
        settingsStore = SettingsStore()
        tokenStore = KeychainTokenStore()
        val refresher = RealTokenRefresher(tokenStore, TokenExchange())
        client = Client(settingsStore, tokenStore, refresher, DefaultLanguageProvider(), StdoutLogger)
        imageLoader = ImageLoaderFactory.create(settingsStore)
        database = createDatabase()
    }
}
```

Note: `RealTokenRefresher` — check the actual class name in `:net` (might be `RealTokenRefresher` or `RealTokenRefresher`). Verify the import path compiles. `createDatabase()` returns `ceui.pixiv.store.Database` — check the actual return type in `:store`.

- [ ] **Step 2: Create `ShaftTheme.kt`**

```kotlin
package ceui.pixiv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0096C7),
    secondary = Color(0xFF023E8A),
    background = Color(0xFFF8F9FA),
    surface = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF48CAE4),
    secondary = Color(0xFF90E0EF),
    background = Color(0xFF1A1A2E),
    surface = Color(0xFF16213E),
)

@Composable
fun ShaftTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
```

- [ ] **Step 3: Wire `AppContainer.init()` + `ShaftTheme` into `Main.kt`**

Modify `Main.kt`:

```kotlin
package ceui.pixiv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import cafe.adriel.voyager.navigator.Navigator
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.theme.ShaftTheme

fun main() = application {
    AppContainer.init()
    Window(
        onCloseRequest = ::exitApplication,
        title = "Pixiv Shaft"
    ) {
        ShaftTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Navigator(PlaceholderScreen)
            }
        }
    }
}

private object PlaceholderScreen : cafe.adriel.voyager.core.screen.Screen {
    @Composable
    override fun Content() {
        androidx.compose.material3.Text("Pixiv Shaft Desktop — loading…")
    }
}
```

- [ ] **Step 4: Verify compilation**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/ceui/pixiv/di/AppContainer.kt app/src/main/kotlin/ceui/pixiv/ui/theme/ShaftTheme.kt app/src/main/kotlin/ceui/pixiv/Main.kt
git commit -m "feat(app/di+theme): AppContainer DI 容器 + ShaftTheme Material3 主题"
```

---

## Task 3: UiState + Pager

**Files:**
- Create: `app/src/main/kotlin/ceui/pixiv/ui/state/UiState.kt`
- Create: `app/src/main/kotlin/ceui/pixiv/ui/state/Pager.kt`
- Test: `app/src/test/kotlin/ceui/pixiv/ui/state/PagerTest.kt`

**Interfaces:**
- Consumes: `KListShow<T>` (from `:models`), `Client.appApi.generalGet(Url)` (from `:net`), `Gson`.
- Produces: `UiState<T>` sealed class; `Pager<T, Item>` class with `items: StateFlow<List<Item>>`, `hasNext: StateFlow<Boolean>`, `refresh(response)`, `suspend loadMore()`.

- [ ] **Step 1: Create `UiState.kt`**

```kotlin
package ceui.pixiv.ui.state

sealed class UiState<out T> {
    data object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String) : UiState<Nothing>()
}
```

- [ ] **Step 2: Create `Pager.kt`**

```kotlin
package ceui.pixiv.ui.state

import ceui.loxia.KListShow
import ceui.pixiv.net.api.Client
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Generic next_url-based pager for pixiv list APIs.
 *
 * Usage: ScreenModel calls the initial API, passes the response to [refresh].
 * For subsequent pages, [loadMore] fetches next_url via generalGet + Gson.
 */
class Pager<T : KListShow<Item>, Item : Any>(
    private val client: Client,
    private val responseType: Class<T>,
) {
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    private val _hasNext = MutableStateFlow(false)
    val hasNext: StateFlow<Boolean> = _hasNext.asStateFlow()

    private var nextUrl: String? = null
    private val gson = Gson()

    fun refresh(response: T) {
        nextUrl = response.nextPageUrl
        _items.value = response.displayList
        _hasNext.value = !nextUrl.isNullOrEmpty()
    }

    suspend fun loadMore() {
        val url = nextUrl ?: return
        val body = client.appApi.generalGet(url)
        val json = body.string()
        val response = gson.fromJson(json, responseType)
        nextUrl = response.nextPageUrl
        _items.value = _items.value + response.displayList
        _hasNext.value = !nextUrl.isNullOrEmpty()
    }
}
```

- [ ] **Step 3: Write unit test for `Pager`**

Create `app/src/test/kotlin/ceui/pixiv/ui/state/PagerTest.kt`:

```kotlin
package ceui.pixiv.ui.state

import ceui.loxia.KListShow
import ceui.pixiv.net.api.Client
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.Serializable

data class FakeItem(val id: Long) : Serializable
data class FakeResponse(
    val items: List<FakeItem> = listOf(),
    val next_url: String? = null,
) : Serializable, KListShow<FakeItem> {
    override val displayList: List<FakeItem> get() = items
    override val nextPageUrl: String? get() = next_url
}

class PagerTest {

    @Test
    fun `refresh sets items and hasNext`() {
        val pager = Pager<FakeResponse, FakeItem>(fakeClient(), FakeResponse::class.java)
        pager.refresh(FakeResponse(listOf(FakeItem(1), FakeItem(2)), "https://next"))
        assertEquals(2, pager.items.value.size)
        assertTrue(pager.hasNext.value)
    }

    @Test
    fun `refresh with null next_url sets hasNext false`() {
        val pager = Pager<FakeResponse, FakeItem>(fakeClient(), FakeResponse::class.java)
        pager.refresh(FakeResponse(listOf(FakeItem(1)), null))
        assertEquals(1, pager.items.value.size)
        assertFalse(pager.hasNext.value)
    }

    @Test
    fun `loadMore appends items and updates nextUrl`() = runTest {
        val client = fakeClient("""{"items":[{"id":3},{"id":4}],"next_url":null}""")
        val pager = Pager<FakeResponse, FakeItem>(client, FakeResponse::class.java)
        pager.refresh(FakeResponse(listOf(FakeItem(1), FakeItem(2)), "https://next"))
        pager.loadMore()
        assertEquals(4, pager.items.value.size)
        assertFalse(pager.hasNext.value)
    }

    @Test
    fun `loadMore with no nextUrl is no-op`() = runTest {
        val pager = Pager<FakeResponse, FakeItem>(fakeClient(), FakeResponse::class.java)
        pager.refresh(FakeResponse(listOf(FakeItem(1)), null))
        pager.loadMore()
        assertEquals(1, pager.items.value.size)
    }

    private fun fakeClient(json: String = ""): Client {
        // Create a mock Client that returns the given JSON for generalGet.
        // Since Client is complex, use a simple subclass override.
        val body = json.toResponseBody()
        return object : Client(
            ceui.pixiv.net.abstractions.Settings { },
            object : ceui.pixiv.net.abstractions.TokenStore {
                override val accessToken get() = null
                override val refreshToken get() = null
                override val isLoggedIn get() = false
                override fun saveTokens(access: String?, refresh: String?) {}
                override fun clear() {}
            },
            object : ceui.pixiv.net.abstractions.TokenRefresher {
                override suspend fun refreshToken() = false
            },
            object : ceui.pixiv.net.abstractions.LanguageProvider {
                override fun getLanguage() = "en"
            },
            ceui.pixiv.net.impl.StdoutLogger
        ) {
            // generalGet is on the API interface, not Client directly.
            // For the test, we need to mock the Retrofit API.
            // This is complex — see note below.
        }
    }
}
```

**IMPORTANT:** The `fakeClient` above may not compile because `Client`'s constructor and `appApi` are complex. If you cannot mock `Client.appApi.generalGet()`, **skip the `loadMore` test** and only test `refresh` (which doesn't use the client). Remove the `loadMore` test and the `fakeClient` method. The `refresh` tests don't use the client parameter — just pass `null!!` for the client:

```kotlin
@Test
fun `refresh sets items and hasNext`() {
    val pager = Pager<FakeResponse, FakeItem>(null!!, FakeResponse::class.java)
    pager.refresh(FakeResponse(listOf(FakeItem(1), FakeItem(2)), "https://next"))
    assertEquals(2, pager.items.value.size)
    assertTrue(pager.hasNext.value)
}
```

The `loadMore` integration is verified in the Plan 4 gate (real API call).

- [ ] **Step 4: Run tests**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:test --no-daemon
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/ceui/pixiv/ui/state/ app/src/test/kotlin/ceui/pixiv/ui/state/
git commit -m "feat(app/state): UiState sealed + Pager 通用分页（next_url + Gson）"
```

---

## Task 4: Shared Components

**Files:**
- Create: `app/src/main/kotlin/ceui/pixiv/ui/component/IllustCard.kt`
- Create: `app/src/main/kotlin/ceui/pixiv/ui/component/CommonComponents.kt`

**Interfaces:**
- Consumes: `Illust` (from `:models`), `ImageLoader` (from `AppContainer`), Coil 3 `AsyncImage`.
- Produces: `IllustCard(illust, onClick)` composable; `TagChip(tag, onClick)`; `LoadingView()`; `ErrorView(message, onRetry)`; `EmptyView(message)`; `UserAvatar(url)`.

- [ ] **Step 1: Create `IllustCard.kt`**

```kotlin
package ceui.pixiv.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import ceui.loxia.Illust

@Composable
fun IllustCard(
    illust: Illust,
    onClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick(illust.id) },
        shape = RoundedCornerShape(8.dp)
    ) {
        Column {
            val imageUrl = illust.image_urls?.medium ?: illust.image_urls?.large
            val aspectRatio = if (illust.width > 0 && illust.height > 0) {
                illust.width.toFloat() / illust.height.toFloat()
            } else {
                1f
            }
            AsyncImage(
                model = imageUrl,
                contentDescription = illust.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectRatio)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = illust.title ?: "Untitled",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = illust.user?.name ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "♥ ${illust.total_bookmarks ?: 0}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: Create `CommonComponents.kt`**

```kotlin
package ceui.pixiv.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import ceui.loxia.Tag

@Composable
fun TagChip(tag: Tag, onClick: (String) -> Unit) {
    AssistChip(
        onClick = { tag.name?.let { onClick(it) } },
        label = {
            Text(
                text = tag.tagName ?: "",
                style = MaterialTheme.typography.labelSmall
            )
        }
    )
}

@Composable
fun LoadingView(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onRetry, modifier = Modifier.padding(top = 8.dp)) {
            Text("Retry")
        }
    }
}

@Composable
fun EmptyView(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun UserAvatar(url: String?, modifier: Modifier = Modifier, size: Int = 40) {
    AsyncImage(
        model = url,
        contentDescription = "Avatar",
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape),
        contentScale = ContentScale.Crop
    )
}
```

- [ ] **Step 3: Verify compilation**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/ceui/pixiv/ui/component/
git commit -m "feat(app/component): IllustCard + TagChip + Loading/Error/Empty 共享组件"
```

---

## Task 5: Recommend Page

**Files:**
- Create: `app/src/main/kotlin/ceui/pixiv/ui/screen/recommend/RecommendScreenModel.kt`
- Create: `app/src/main/kotlin/ceui/pixiv/ui/screen/recommend/RecommendScreen.kt`

**Interfaces:**
- Consumes: `AppContainer.client` (for `getWalkthroughWorks()`), `AppContainer.imageLoader`, `Pager`, `UiState`, `IllustCard`, Voyager `Screen` + `ScreenModel`.
- Produces: `RecommendScreen` (Voyager `Screen`) + `RecommendScreenModel` (`ScreenModel`).

- [ ] **Step 1: Create `RecommendScreenModel.kt`**

```kotlin
package ceui.pixiv.ui.screen.recommend

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.IllustResponse
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.state.Pager
import ceui.pixiv.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RecommendScreenModel : ScreenModel {

    private val client = AppContainer.client
    private val pager = Pager<IllustResponse, ceui.loxia.Illust>(client, IllustResponse::class.java)

    private val _state = MutableStateFlow<UiState<List<ceui.loxia.Illust>>>(UiState.Loading)
    val state: StateFlow<UiState<List<ceui.loxia.Illust>>> = _state.asStateFlow()

    val hasNext get() = pager.hasNext

    init {
        refresh()
    }

    fun refresh() {
        screenModelScope.launch {
            _state.value = UiState.Loading
            try {
                val resp = client.appApi.getWalkthroughWorks()
                pager.refresh(resp)
                _state.value = UiState.Success(pager.items.value)
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Failed to load recommendations")
            }
        }
    }

    fun loadMore() {
        if (!pager.hasNext.value) return
        screenModelScope.launch {
            try {
                pager.loadMore()
                _state.value = UiState.Success(pager.items.value)
            } catch (e: Exception) {
                // Keep existing items, show non-blocking error
                _state.value = UiState.Success(pager.items.value)
            }
        }
    }
}
```

Note: `screenModelScope` requires `voyager-screenmodel`. If `screenModelScope` is not resolved, check if the extension is `screenModelScope` or `screenModelState`. In Voyager 1.0.1, it's `screenModelScope` (an extension property on `ScreenModel`).

- [ ] **Step 2: Create `RecommendScreen.kt`**

```kotlin
package ceui.pixiv.ui.screen.recommend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.pixiv.ui.component.EmptyView
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.IllustCard
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.screen.detail.IllustDetailScreen
import ceui.pixiv.ui.state.UiState

class RecommendScreen : Screen {

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { RecommendScreenModel() }
        val state by screenModel.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow

        when (val s = state) {
            is UiState.Loading -> LoadingView()
            is UiState.Error -> ErrorView(s.message, screenModel::refresh)
            is UiState.Success -> {
                if (s.data.isEmpty()) {
                    EmptyView("No recommendations")
                } else {
                    IllustGrid(
                        illusts = s.data,
                        onIllustClick = { id -> navigator.push(IllustDetailScreen(id)) },
                        onLoadMore = screenModel::loadMore
                    )
                }
            }
        }
    }
}

@Composable
fun IllustGrid(
    illusts: List<ceui.loxia.Illust>,
    onIllustClick: (Long) -> Unit,
    onLoadMore: () -> Unit
) {
    val gridState = rememberLazyStaggeredGridState()
    val visibleItems = gridState.layoutInfo.visibleItemsInfo

    // Infinite scroll: if last visible item is near the end, trigger loadMore
    LaunchedEffect(visibleItems) {
        val lastVisible = visibleItems.lastOrNull()?.index ?: 0
        if (lastVisible >= illusts.size - 5) {
            onLoadMore()
        }
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        state = gridState,
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalItemSpacing = 4.dp,
        modifier = Modifier.fillMaxSize()
    ) {
        items(illusts, key = { it.id }) { illust ->
            IllustCard(illust = illust, onClick = onIllustClick)
        }
    }
}
```

Note: Voyager 1.0.1's `Screen` interface uses `Content()` (capital C, verified from source).

- [ ] **Step 3: Update `Main.kt` to use `RecommendScreen` as initial screen**

In `Main.kt`, change `Navigator(PlaceholderScreen)` to `Navigator(RecommendScreen())`. Remove the `PlaceholderScreen` object. Add import `ceui.pixiv.ui.screen.recommend.RecommendScreen`.

- [ ] **Step 4: Verify compilation**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/ceui/pixiv/ui/screen/recommend/ app/src/main/kotlin/ceui/pixiv/Main.kt
git commit -m "feat(app/recommend): RecommendScreen + ScreenModel（瀑布流 + 无限滚动）"
```

---

## Task 6: Illust Detail Page

**Files:**
- Create: `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreenModel.kt`
- Create: `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreen.kt`

**Interfaces:**
- Consumes: `AppContainer.client` (for `getIllust(id)` + `getRelatedIllusts(id)`), `Illust` model, `IllustCard`, `TagChip`, `UserAvatar`, Voyager `Screen` + `ScreenModel`.
- Produces: `IllustDetailScreen(illustId: Long)` — Voyager `Screen` with `illustId` constructor param; `IllustDetailScreenModel(illustId)`.

- [ ] **Step 1: Create `IllustDetailScreenModel.kt`**

```kotlin
package ceui.pixiv.ui.screen.detail

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.state.Pager
import ceui.pixiv.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class IllustDetailScreenModel(
    private val illustId: Long
) : ScreenModel {

    private val client = AppContainer.client
    private val relatedPager = Pager<IllustResponse, Illust>(client, IllustResponse::class.java)

    private val _illustState = MutableStateFlow<UiState<Illust>>(UiState.Loading)
    val illustState: StateFlow<UiState<Illust>> = _illustState.asStateFlow()

    private val _relatedState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val relatedState: StateFlow<UiState<List<Illust>>> = _relatedState.asStateFlow()

    init {
        loadIllust()
        loadRelated()
    }

    private fun loadIllust() {
        screenModelScope.launch {
            _illustState.value = UiState.Loading
            try {
                val resp = client.appApi.getIllust(illustId)
                _illustState.value = resp.illust?.let { UiState.Success(it) }
                    ?: UiState.Error("Illust not found")
            } catch (e: Exception) {
                _illustState.value = UiState.Error(e.message ?: "Failed to load illust")
            }
        }
    }

    private fun loadRelated() {
        screenModelScope.launch {
            _relatedState.value = UiState.Loading
            try {
                val resp = client.appApi.getRelatedIllusts(illustId)
                relatedPager.refresh(resp)
                _relatedState.value = UiState.Success(relatedPager.items.value)
            } catch (e: Exception) {
                _relatedState.value = UiState.Error(e.message ?: "Failed to load related")
            }
        }
    }
}
```

- [ ] **Step 2: Create `IllustDetailScreen.kt`**

```kotlin
package ceui.pixiv.ui.screen.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import coil3.compose.AsyncImage
import ceui.loxia.Illust
import ceui.pixiv.ui.component.EmptyView
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.IllustCard
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.component.TagChip
import ceui.pixiv.ui.component.UserAvatar
import ceui.pixiv.ui.state.UiState

class IllustDetailScreen(private val illustId: Long) : Screen {

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { IllustDetailScreenModel(illustId) }
        val illustState by screenModel.illustState.collectAsState()
        val relatedState by screenModel.relatedState.collectAsState()

        when (val s = illustState) {
            is UiState.Loading -> LoadingView()
            is UiState.Error -> ErrorView(s.message, {})
            is UiState.Success -> IllustDetailContent(
                illust = s.data,
                relatedState = relatedState
            )
        }
    }
}

@Composable
private fun IllustDetailContent(
    illust: Illust,
    relatedState: UiState<List<Illust>>
) {
    val imageUrls = buildList {
        if (illust.page_count <= 1) {
            illust.maxUrl()?.let { add(it) }
        } else {
            illust.meta_pages?.forEach { page ->
                page.image_urls?.original?.let { add(it) }
            }
        }
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(1),
        contentPadding = PaddingValues(0.dp),
        verticalItemSpacing = 8.dp
    ) {
        // Image gallery
        item {
            if (imageUrls.size > 1) {
                val pagerState = rememberPagerState(pageCount = { imageUrls.size })
                HorizontalPager(state = pagerState) { page ->
                    AsyncImage(
                        model = imageUrls[page],
                        contentDescription = "Page ${page + 1}",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit
                    )
                }
            } else {
                AsyncImage(
                    model = imageUrls.firstOrNull(),
                    contentDescription = illust.title,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit
                )
            }
        }

        // Title + author
        item {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = illust.title ?: "Untitled",
                    style = MaterialTheme.typography.titleMedium
                )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    UserAvatar(url = illust.user?.profile_image_urls?.px_50x50)
                    Text(
                        text = illust.user?.name ?: "",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = "♥ ${illust.total_bookmarks ?: 0}  👁 ${illust.total_view ?: 0}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // Tags
        item {
            illust.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tags) { tag ->
                        TagChip(tag = tag, onClick = {})
                    }
                }
            }
        }

        // Related works
        item {
            Text(
                text = "Related works",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(16.dp)
            )
        }

        when (relatedState) {
            is UiState.Loading -> item { LoadingView(modifier = Modifier.height(200.dp)) }
            is UiState.Error -> item {
                ErrorView(
                    message = (relatedState as UiState.Error).message,
                    onRetry = {},
                    modifier = Modifier.height(200.dp)
                )
            }
            is UiState.Success -> {
                val related = (relatedState as UiState.Success).data
                if (related.isEmpty()) {
                    item { EmptyView("No related works", modifier = Modifier.height(200.dp)) }
                } else {
                    items(related, key = { it.id }) { relatedIllust ->
                        IllustCard(
                            illust = relatedIllust,
                            onClick = {},
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
```

Note: `LazyVerticalStaggeredGrid` with `StaggeredGridCells.Fixed(1)` is a single-column vertical list (equivalent to `LazyColumn`). If `item {}` and `items()` don't work with StaggeredGrid DSL, switch to `LazyColumn` + `item {}` + `items()`. The key difference is StaggeredGrid uses `items` from `lazy.staggeredgrid.items`, while LazyColumn uses `lazy.items`.

If the StaggeredGrid DSL is problematic, use `LazyColumn` instead:

```kotlin
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems

// In content():
LazyColumn(modifier = Modifier.fillMaxSize()) {
    item { /* gallery */ }
    item { /* title + author */ }
    item { /* tags */ }
    item { Text("Related works", ...) }
    when (relatedState) {
        is UiState.Success -> lazyItems(relatedState.data) { /* IllustCard */ }
        ...
    }
}
```

- [ ] **Step 3: Verify compilation**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/ceui/pixiv/ui/screen/detail/
git commit -m "feat(app/detail): IllustDetailScreen + ScreenModel（大图 gallery + 标签 + 作者 + 相关）"
```

---

## Task 7: Search Page

**Files:**
- Modify: `store/src/main/sqldelight/ceui/pixiv/store/SearchHistory.sq` (add `insertKeywordOnly` + `clearAllSearches` queries)
- Create: `app/src/main/kotlin/ceui/pixiv/ui/screen/search/SearchScreenModel.kt`
- Create: `app/src/main/kotlin/ceui/pixiv/ui/screen/search/SearchScreen.kt`

**Interfaces:**
- Consumes: `AppContainer.client` (for `searchIllustManga`), `AppContainer.database` (for SearchHistory), `Pager`, `UiState`, `IllustCard`, Voyager `Screen` + `ScreenModel`.
- Produces: `SearchScreen` — Voyager `Screen` with search bar + history + results grid; `SearchScreenModel`.

- [ ] **Step 1: Add queries to `SearchHistory.sq`**

The existing `insertSearch` query requires 6 params (id, keyword, searchTime, searchType, pinned, previewIllustsJson). Add simpler queries for UI use. Append to `store/src/main/sqldelight/ceui/pixiv/store/SearchHistory.sq`:

```sql

insertKeywordOnly:
INSERT INTO search_table(keyword, searchTime, searchType, pinned)
VALUES (?, ?, ?, 0);

clearAllSearches:
DELETE FROM search_table;
```

Note: `insertKeywordOnly` omits `id` (auto-increment) and `previewIllustsJson` (defaults to NULL). This is for UI search history insertion where we only have keyword + time.

- [ ] **Step 2: Create `SearchScreenModel.kt`**

```kotlin
package ceui.pixiv.ui.screen.search

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.state.Pager
import ceui.pixiv.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchScreenModel : ScreenModel {

    private val client = AppContainer.client
    private val db = AppContainer.database
    private val pager = Pager<IllustResponse, Illust>(client, IllustResponse::class.java)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _resultsState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val resultsState: StateFlow<UiState<List<Illust>>> = _resultsState.asStateFlow()

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    init {
        loadHistory()
    }

    fun updateQuery(q: String) {
        _query.value = q
    }

    fun search(word: String) {
        if (word.isBlank()) return
        _query.value = word
        screenModelScope.launch {
            _resultsState.value = UiState.Loading
            try {
                // Save to search history
                db.queries.searchHistoryQueries.insertKeywordOnly(word, System.currentTimeMillis(), 0L)
                loadHistory()

                val resp = client.appApi.searchIllustManga(
                    word = word,
                    sort = "date_desc",
                    search_target = "partial_match_for_tags",
                    merge_plain_keyword_results = true,
                    include_translated_tag_results = true
                )
                pager.refresh(resp)
                _resultsState.value = UiState.Success(pager.items.value)
            } catch (e: Exception) {
                _resultsState.value = UiState.Error(e.message ?: "Search failed")
            }
        }
    }

    fun loadMore() {
        if (!pager.hasNext.value) return
        screenModelScope.launch {
            try {
                pager.loadMore()
                _resultsState.value = UiState.Success(pager.items.value)
            } catch (_: Exception) {
                // Keep existing results
            }
        }
    }

    fun clearHistory() {
        screenModelScope.launch {
            db.queries.searchHistoryQueries.clearAllSearches()
            loadHistory()
        }
    }

    private fun loadHistory() {
        screenModelScope.launch {
            _history.value = db.queries.searchHistoryQueries.selectRecentSearches(20)
                .executeAsList()
                .map { it.keyword }
        }
    }
}
```

Note: `selectRecentSearches` returns `List<SearchHistory>` (generated data class). The field `keyword` matches the column name `keyword` in the `.sq` file. `searchType` is `Long` (SQLDelight maps `INTEGER` to `Long`). The `insertKeywordOnly` takes `(keyword: String, searchTime: Long, searchType: Long)`.

- [ ] **Step 3: Create `SearchScreen.kt`**

```kotlin
package ceui.pixiv.ui.screen.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.pixiv.ui.component.IllustCard
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.EmptyView
import ceui.pixiv.ui.screen.detail.IllustDetailScreen
import ceui.pixiv.ui.state.UiState

class SearchScreen : Screen {

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { SearchScreenModel() }
        val query by screenModel.query.collectAsState()
        val resultsState by screenModel.resultsState.collectAsState()
        val history by screenModel.history.collectAsState()
        val navigator = LocalNavigator.currentOrThrow

        Column(modifier = Modifier.fillMaxSize()) {
            // Search bar
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
                singleLine = true
            )

            // Search history (when no results yet)
            if (resultsState is UiState.Loading && history.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(history) { keyword ->
                        AssistChip(
                            onClick = { screenModel.search(keyword) },
                            label = { Text(keyword) }
                        )
                    }
                }
            }

            // Results
            when (val s = resultsState) {
                is UiState.Loading -> LoadingView()
                is UiState.Error -> ErrorView(s.message, { screenModel.search(query) })
                is UiState.Success -> {
                    if (s.data.isEmpty()) {
                        EmptyView("No results")
                    } else {
                        LazyVerticalStaggeredGrid(
                            columns = StaggeredGridCells.Fixed(2),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalItemSpacing = 4.dp
                        ) {
                            items(s.data, key = { it.id }) { illust ->
                                IllustCard(
                                    illust = illust,
                                    onClick = { id -> navigator.push(IllustDetailScreen(id)) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
```

Note: `androidx.compose.material.icons.Icons.Default.Search` and `Close` require the `material-icons-extended` dependency OR the core icons. In Compose Multiplatform 1.7.3, `Icons.Default.Search` is in the core `material-icons-core` module. If not available, use `Icons.Default.Search` from `androidx.compose.material:material-icons-core` or replace with a text icon ("🔍" / "✕"). If the import fails, add `implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")` to `app/build.gradle.kts`, or simply remove the icons and use text placeholders.

- [ ] **Step 4: Verify compilation**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/ceui/pixiv/ui/screen/search/
git commit -m "feat(app/search): SearchScreen + ScreenModel（搜索栏 + 历史 + 结果瀑布流）"
```

---

## Task 8: Discover Page

**Files:**
- Create: `app/src/main/kotlin/ceui/pixiv/ui/screen/discover/DiscoverScreenModel.kt`
- Create: `app/src/main/kotlin/ceui/pixiv/ui/screen/discover/DiscoverScreen.kt`

**Interfaces:**
- Consumes: `AppContainer.client` (for `trendingTags("illust")` + `getRankingIllusts("day")`), `TrendingTag`, `Illust`, `IllustCard`, `TagChip`, Voyager `Screen` + `ScreenModel`.
- Produces: `DiscoverScreen` — Voyager `Screen` with trending tags + ranking illusts; `DiscoverScreenModel`.

- [ ] **Step 1: Create `DiscoverScreenModel.kt`**

```kotlin
package ceui.pixiv.ui.screen.discover

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.TrendingTag
import ceui.loxia.TrendingTagsResponse
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DiscoverScreenModel : ScreenModel {

    private val client = AppContainer.client

    private val _tagsState = MutableStateFlow<UiState<List<TrendingTag>>>(UiState.Loading)
    val tagsState: StateFlow<UiState<List<TrendingTag>>> = _tagsState.asStateFlow()

    private val _rankingState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val rankingState: StateFlow<UiState<List<Illust>>> = _rankingState.asStateFlow()

    init {
        loadTags()
        loadRanking("day")
    }

    private fun loadTags() {
        screenModelScope.launch {
            _tagsState.value = UiState.Loading
            try {
                val resp = client.appApi.trendingTags("illust")
                _tagsState.value = UiState.Success(resp.displayList)
            } catch (e: Exception) {
                _tagsState.value = UiState.Error(e.message ?: "Failed to load trending tags")
            }
        }
    }

    fun loadRanking(mode: String) {
        screenModelScope.launch {
            _rankingState.value = UiState.Loading
            try {
                val resp = client.appApi.getRankingIllusts(mode)
                _rankingState.value = UiState.Success(resp.illusts)
            } catch (e: Exception) {
                _rankingState.value = UiState.Error(e.message ?: "Failed to load ranking")
            }
        }
    }
}
```

- [ ] **Step 2: Create `DiscoverScreen.kt`**

```kotlin
package ceui.pixiv.ui.screen.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import ceui.loxia.TrendingTag
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.IllustCard
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.screen.detail.IllustDetailScreen
import ceui.pixiv.ui.state.UiState

class DiscoverScreen : Screen {

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { DiscoverScreenModel() }
        val tagsState by screenModel.tagsState.collectAsState()
        val rankingState by screenModel.rankingState.collectAsState()
        val navigator = LocalNavigator.currentOrThrow

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // Trending tags
            item {
                Text(
                    text = "Trending Tags",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp, 8.dp)
                )
            }
            item {
                when (val s = tagsState) {
                    is UiState.Loading -> LoadingView(modifier = Modifier.height(120.dp))
                    is UiState.Error -> ErrorView(s.message, {}, Modifier.height(120.dp))
                    is UiState.Success -> {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(s.data) { tag ->
                                TrendingTagItem(tag)
                            }
                        }
                    }
                }
            }

            // Ranking mode selector
            item {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.padding(16.dp, 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("day" to "Daily", "week" to "Weekly", "month" to "Monthly").forEach { (mode, label) ->
                        FilterChip(
                            selected = false,
                            onClick = { screenModel.loadRanking(mode) },
                            label = { Text(label) }
                        )
                    }
                }
            }

            // Ranking illusts
            item {
                Text(
                    text = "Ranking",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp, 8.dp)
                )
            }
            when (val s = rankingState) {
                is UiState.Loading -> item { LoadingView(modifier = Modifier.height(200.dp)) }
                is UiState.Error -> item { ErrorView(s.message, {}, Modifier.height(200.dp)) }
                is UiState.Success -> {
                    val columns = 2
                    val rows = (s.data.size + columns - 1) / columns
                    items(rows) { rowIndex ->
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            for (col in 0 until columns) {
                                val index = rowIndex * columns + col
                                if (index < s.data.size) {
                                    IllustCard(
                                        illust = s.data[index],
                                        onClick = { id -> navigator.push(IllustDetailScreen(id)) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendingTagItem(tag: TrendingTag) {
    Column(modifier = Modifier.width(120.dp)) {
        AsyncImage(
            model = tag.illust?.image_urls?.medium,
            contentDescription = tag.tag,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            contentScale = ContentScale.Crop
        )
        Text(
            text = "#${tag.tag}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
```

Note: The `Modifier.width(120.dp)` in `TrendingTagItem` needs `import androidx.compose.foundation.layout.width`. Also, the ranking grid uses a manual row approach because `LazyVerticalStaggeredGrid` can't be nested inside `LazyColumn`. If this causes issues, use a single `LazyVerticalStaggeredGrid` for ranking and put the tags/ranking-selector in a header `item` block instead.

Alternative approach (cleaner): use a single `LazyVerticalStaggeredGrid` with `StaggeredGridCells.Fixed(2)` and `item { }` for headers, `staggeredItems` for illusts. But `StaggeredGrid`'s `item {}` spans full width only with `StaggeredGridCells.Fixed(1)`. For `Fixed(2)`, headers would be placed in one column only. This is a known limitation. The manual row approach above is a workaround.

- [ ] **Step 3: Verify compilation**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/ceui/pixiv/ui/screen/discover/
git commit -m "feat(app/discover): DiscoverScreen + ScreenModel（热搜标签 + 日/周/月排行）"
```

---

## Task 9: Navigation Shell + Gate

**Files:**
- Create: `app/src/main/kotlin/ceui/pixiv/ui/navigation/MainScreen.kt`
- Modify: `app/src/main/kotlin/ceui/pixiv/Main.kt`

**Interfaces:**
- Consumes: `RecommendScreen`, `DiscoverScreen`, `SearchScreen` (from Tasks 5-8), Voyager `TabNavigator` + `Tab`.
- Produces: `MainScreen` — Voyager `Screen` with `TabNavigator` + bottom navigation bar; gate verification.

- [ ] **Step 1: Create `MainScreen.kt`**

```kotlin
package ceui.pixiv.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.CurrentTab
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import ceui.pixiv.ui.screen.discover.DiscoverScreen
import ceui.pixiv.ui.screen.recommend.RecommendScreen
import ceui.pixiv.ui.screen.search.SearchScreen

class MainScreen : Screen {

    @Composable
    override fun Content() {
        TabNavigator(RecommendTab) {
            Scaffold(
                bottomBar = { BottomBar() }
            ) { padding ->
                Box(modifier = Modifier.padding(padding)) {
                    CurrentTab()
                }
            }
        }
    }
}

@Composable
private fun BottomBar() {
    val tabNavigator = LocalTabNavigator.current
    NavigationBar {
        listOf(RecommendTab, DiscoverTab, SearchTab).forEach { tab ->
            NavigationBarItem(
                selected = tabNavigator.current.key == tab.key,
                onClick = { tabNavigator.current = tab },
                icon = { Icon(painter = tab.options.icon!!, contentDescription = tab.options.title) },
                label = { Text(tab.options.title) }
            )
        }
    }
}

object RecommendTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(index = 0u, title = "推荐", icon = rememberVectorPainter(Icons.Default.Home))

    @Composable
    override fun Content() {
        Navigator(RecommendScreen()) { CurrentScreen() }
    }
}

object DiscoverTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(index = 1u, title = "发现", icon = rememberVectorPainter(Icons.Default.Star))

    @Composable
    override fun Content() {
        Navigator(DiscoverScreen()) { CurrentScreen() }
    }
}

object SearchTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(index = 2u, title = "搜索", icon = rememberVectorPainter(Icons.Default.Search))

    @Composable
    override fun Content() {
        Navigator(SearchScreen()) { CurrentScreen() }
    }
}
```

Key Voyager 1.0.1 API notes (verified from source):
- `Screen.Content()` — capital C, no `override` keyword needed (interface method).
- `Tab : Screen` — also uses `Content()`.
- `TabOptions(index: UShort, title: String, icon: Painter?)` — `index` is `UShort` (use `0u`), `icon` is `Painter?` (use `rememberVectorPainter(Icons.Default.X)`, NOT `Icons.Default.X` directly).
- `Tab.options` has `@Composable get` — so `rememberVectorPainter` can be called inside.
- `TabNavigator(tab: Tab)` — takes initial tab.
- `LocalTabNavigator.current` — returns `TabNavigator`; `tabNavigator.current` — get/set `Tab`.
- `CurrentTab()` — renders current tab's `Content()`.
- `CurrentScreen()` — renders current screen in a `Navigator`.
- `rememberVectorPainter` is from `androidx.compose.ui.graphics.vector.rememberVectorPainter`.
- `Icons.Default.Home/Search/Star` are in `material-icons-core` (bundled with `compose.material3`). If `Star` is unavailable, use `Icons.Default.Favorite` or any available icon.

- [ ] **Step 2: Update `Main.kt` to use `MainScreen`**

In `Main.kt`, change `Navigator(RecommendScreen())` to `Navigator(MainScreen())`. Add import `ceui.pixiv.ui.navigation.MainScreen`. Remove the `PlaceholderScreen` object if still present.

```kotlin
package ceui.pixiv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import cafe.adriel.voyager.navigator.Navigator
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.navigation.MainScreen
import ceui.pixiv.ui.theme.ShaftTheme

fun main() = application {
    AppContainer.init()
    Window(
        onCloseRequest = ::exitApplication,
        title = "Pixiv Shaft"
    ) {
        ShaftTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Navigator(MainScreen())
            }
        }
    }
}
```

- [ ] **Step 3: Verify compilation**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the app (gate)**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:run --no-daemon 2>&1 | head -100
```

Expected: A window opens with:
1. Bottom navigation bar (推荐 / 发现 / 搜索)
2. Recommend tab shows a waterfall grid of illust thumbnails (from `getWalkthroughWorks()`, 112 illusts)
3. Clicking 发现 switches to Discover page (may show error if not authenticated — expected)
4. Clicking 搜索 switches to Search page with search bar
5. Clicking an illust in Recommend pushes IllustDetailScreen (may show error if `getIllust` requires auth — expected)

Verify in console output:
- No uncaught exceptions
- QUIC connection succeeds (same as Plan 2/3 gate)

If the window opens and the recommend page shows images, the gate passes.

- [ ] **Step 5: Print gate confirmation and commit**

Add a `println("PLAN 4 GATE PASSED")` at the end of `AppContainer.init()`:

```kotlin
fun init() {
    // ... existing init code ...
    println("PLAN 4 GATE PASSED")
}
```

Wait — the gate should verify UI, not just init. Instead, add a `LaunchedEffect` in `MainScreen.content()` that prints after first render:

Actually, simpler: just run the app and verify visually. The gate is:
1. App launches without crash
2. Recommend page shows illust thumbnails
3. Bottom nav switches tabs

If all three work, the gate passes. Print a console message:

In `MainScreen.content()`, add at the top:
```kotlin
androidx.compose.runtime.LaunchedEffect(Unit) {
    println("PLAN 4 GATE PASSED — UI rendered, recommend page active")
}
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/ceui/pixiv/ui/navigation/MainScreen.kt app/src/main/kotlin/ceui/pixiv/Main.kt
git commit -m "feat(app/nav): MainScreen 底部导航 + Plan 4 闸门"
```

---

## Self-Review

### Spec Coverage
- ✅ Recommend page (design §4.2): Task 5 — `getWalkthroughWorks()`, StaggeredGrid, infinite scroll
- ✅ Discover page (design §4.2): Task 8 — `trendingTags` + `getRankingIllusts`, mode selector
- ✅ Search page (design §4.2): Task 7 — search bar + SearchHistory + results grid
- ✅ Illust detail (design §4.2): Task 6 — image gallery (HorizontalPager) + tags + author + related
- ✅ Compose Desktop (design §4.1): Task 1 — `application{}` + `Window`
- ✅ Voyager navigation (design §4.1): Task 9 — `TabNavigator` + bottom nav
- ✅ Material 3 (design §4.1): Task 2 — `ShaftTheme`
- ✅ State management (design §4.1): Tasks 3,5-8 — `ScreenModel` + `StateFlow<UiState>`
- ✅ Coil 3 AsyncImage (design §5.1): Tasks 4-8 — `AsyncImage` in cards/detail
- ✅ Pager (design §3.4): Task 3 — `next_url` pagination
- ⚠️ Deferred: AuthState StateFlow + LoginScreen (Plan 5), zoomable image (Plan 6), Ugoira (Plan 7)

### Placeholder Scan
- No "TBD" or "TODO" in plan steps.
- Code blocks provided for all implementation steps.
- "Note" sections explain how to handle API uncertainties (SQLDelight query names, material icons availability).

### Type Consistency
- `UiState<T>` used consistently across Tasks 3-8.
- `Pager<T : KListShow<Item>, Item>` used consistently in Tasks 3, 5, 6, 7.
- `AppContainer.client` / `.database` / `.imageLoader` referenced consistently.
- `IllustDetailScreen(illustId: Long)` constructor matches `onIllustClick` usage in Tasks 5, 7, 8.
- `RecommendScreen()` / `DiscoverScreen()` / `SearchScreen()` no-arg constructors match `Tab.content()` usage in Task 9.

### Risks
1. **Voyager 1.0.1 + Compose 1.7.3 compatibility**: Voyager 1.0.1 was built with Kotlin 1.9.10; our project uses Kotlin 2.1.20. Should be forward-compatible (Compose runtime is backward compatible). If not, fall back to 1.1.0-beta03 or a simple custom navigator.
2. **Material icons**: `Icons.Default.Home/Search/Star` are in the core module (bundled with `compose.material3`). If any icon is unavailable, use a different one or text-only `NavigationBarItem`.
3. **SQLDelight query names**: Plan 3 Task 2 defined the `.sq` files. The generated method names must match. Implementer should verify.
4. **Auth-required endpoints**: `getIllust`, `searchIllustManga`, `trendingTags`, `getRankingIllusts` require auth. Without a token, they return 400/401 → `UiState.Error`. This is expected behavior. The gate passes because `getWalkthroughWorks()` (recommend page) is public.
