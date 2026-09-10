# Plan 17: 查看他人主页

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新建 UserDetailScreen，显示他人主页（头像/名称/统计/Follow按钮 + Illusts/Bookmarks 两个 Tab），从详情页作者点击进入。

**Architecture:** 新建 `UserDetailScreen` + `UserDetailScreenModel`。调用 `getUserDetail(userId)` 获取用户详情 + `ProfileBean` 统计，`getUserCreatedIllusts(userId, "illust")` 获取作品，`getUserBookmarkedIllusts(userId, "public")` 获取收藏。复用 `IllustCard`、`Pager`、`toggleFollow` 逻辑。IllustDetailScreen 作者行加 clickable 跳转。

**Tech Stack:** Compose Multiplatform 1.7.3, Material 3, Voyager, existing API + Pager.

## Global Constraints

- macOS aarch64, `JAVA_HOME=/opt/homebrew/opt/openjdk@21`
- No tests for UI composables (visual verification only)
- `CancellationException` must be rethrown
- API already exists: `getUserDetail(user_id)`, `getUserCreatedIllusts(user_id, type)`, `getUserBookmarkedIllusts(user_id, restrict)`, `postFollow(user_id, restrict)`, `postUnFollow(user_id)`
- `UserDetailResponse(user: User?, profile: ProfileBean?)` already defined
- `ProfileBean` has `total_illusts`, `total_illust_bookmarks_public`, `total_follow_users`, `total_mypixiv_users`, `job`, `region`, `twitter_account`
- `User` has `is_followed`, `profile_image_urls`, `name`, `account`, `id`, `is_premium`

---

## File Structure

| File | Responsibility |
|------|---------------|
| `app/src/main/kotlin/ceui/pixiv/ui/screen/user/UserDetailScreenModel.kt` | Load user detail + illusts + bookmarks + follow toggle |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/user/UserDetailScreen.kt` | User profile UI (header + stats + Follow + tabs + lists) |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreen.kt` | Author row clickable → UserDetailScreen |

---

## Task 1: Create UserDetailScreenModel

**Files:**
- Create: `app/src/main/kotlin/ceui/pixiv/ui/screen/user/UserDetailScreenModel.kt`

- [ ] **Step 1: Create UserDetailScreenModel.kt**

```kotlin
package ceui.pixiv.ui.screen.user

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.ProfileBean
import ceui.loxia.User
import ceui.loxia.UserDetailResponse
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.state.Pager
import ceui.pixiv.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class UserDetailScreenModel(
    private val userId: Long
) : ScreenModel {

    private val client = AppContainer.client
    private val illustPager = Pager<IllustResponse, Illust>(client, IllustResponse::class.java)
    private val bookmarkPager = Pager<IllustResponse, Illust>(client, IllustResponse::class.java)

    private val _userState = MutableStateFlow<UiState<Pair<User, ProfileBean>>>(UiState.Loading)
    val userState: StateFlow<UiState<Pair<User, ProfileBean>>> = _userState.asStateFlow()

    private val _illustsState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val illustsState: StateFlow<UiState<List<Illust>>> = _illustsState.asStateFlow()

    private val _bookmarksState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val bookmarksState: StateFlow<UiState<List<Illust>>> = _bookmarksState.asStateFlow()

    private val _isFollowing = MutableStateFlow<Boolean?>(null)
    val isFollowing: StateFlow<Boolean?> = _isFollowing.asStateFlow()

    init { loadAll() }

    private fun loadAll() {
        loadUserDetail()
        loadIllusts()
        loadBookmarks()
    }

    private fun loadUserDetail() {
        screenModelScope.launch {
            _userState.value = UiState.Loading
            try {
                val detail = client.appApi.getUserDetail(userId)
                val user = detail.user ?: User()
                val profile = detail.profile ?: ProfileBean()
                _userState.value = UiState.Success(user to profile)
                _isFollowing.value = user.is_followed
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _userState.value = UiState.Error(e.message ?: "Failed to load user")
            }
        }
    }

    private fun loadIllusts() {
        screenModelScope.launch {
            _illustsState.value = UiState.Loading
            try {
                val resp = client.appApi.getUserCreatedIllusts(userId, "illust")
                illustPager.refresh(resp)
                _illustsState.value = UiState.Success(illustPager.items.value)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _illustsState.value = UiState.Error(e.message ?: "Failed to load illusts")
            }
        }
    }

    private fun loadBookmarks() {
        screenModelScope.launch {
            _bookmarksState.value = UiState.Loading
            try {
                val resp = client.appApi.getUserBookmarkedIllusts(userId, "public")
                bookmarkPager.refresh(resp)
                _bookmarksState.value = UiState.Success(bookmarkPager.items.value)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _bookmarksState.value = UiState.Error(e.message ?: "Failed to load bookmarks")
            }
        }
    }

    fun toggleFollow(restrict: String = "public") {
        val current = _isFollowing.value ?: return
        screenModelScope.launch {
            _isFollowing.value = !current
            try {
                if (current) {
                    client.appApi.postUnFollow(userId)
                } else {
                    client.appApi.postFollow(userId, restrict)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _isFollowing.value = current
            }
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "feat: UserDetailScreenModel — 加载用户详情/作品/收藏/关注"
```

---

## Task 2: Create UserDetailScreen

**Files:**
- Create: `app/src/main/kotlin/ceui/pixiv/ui/screen/user/UserDetailScreen.kt`

- [ ] **Step 1: Create UserDetailScreen.kt**

```kotlin
package ceui.pixiv.ui.screen.user

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import ceui.loxia.ProfileBean
import ceui.loxia.User
import ceui.pixiv.ui.component.EmptyView
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.IllustCard
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.component.UserAvatar
import ceui.pixiv.ui.screen.detail.IllustDetailScreen
import ceui.pixiv.ui.state.UiState

class UserDetailScreen(private val userId: Long) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { UserDetailScreenModel(userId) }
        val userState by screenModel.userState.collectAsState()
        val illustsState by screenModel.illustsState.collectAsState()
        val bookmarksState by screenModel.bookmarksState.collectAsState()
        val isFollowing by screenModel.isFollowing.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        var selectedTab by remember { mutableStateOf(0) }

        val userName = (userState as? UiState.Success)?.data?.first?.name ?: "User #$userId"

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(userName) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                // Header
                item {
                    when (val s = userState) {
                        is UiState.Loading -> LoadingView(modifier = Modifier.height(120.dp))
                        is UiState.Error -> ErrorView(s.message, {}, Modifier.height(120.dp))
                        is UiState.Success -> {
                            val (user, profile) = s.data
                            UserHeader(
                                user = user,
                                profile = profile,
                                isFollowing = isFollowing,
                                onToggleFollow = { screenModel.toggleFollow(it) }
                            )
                        }
                    }
                }

                // Tabs
                item {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Illusts") }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Bookmarks") }
                        )
                    }
                }

                // Tab content
                when (selectedTab) {
                    0 -> when (val s = illustsState) {
                        is UiState.Loading -> item { LoadingView(modifier = Modifier.height(200.dp)) }
                        is UiState.Error -> item { ErrorView(s.message, {}, Modifier.height(200.dp)) }
                        is UiState.Success -> {
                            if (s.data.isEmpty()) {
                                item { EmptyView("No illusts", Modifier.height(200.dp)) }
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
                    1 -> when (val s = bookmarksState) {
                        is UiState.Loading -> item { LoadingView(modifier = Modifier.height(200.dp)) }
                        is UiState.Error -> item { ErrorView(s.message, {}, Modifier.height(200.dp)) }
                        is UiState.Success -> {
                            if (s.data.isEmpty()) {
                                item { EmptyView("No bookmarks", Modifier.height(200.dp)) }
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
            }
        }
    }
}

@Composable
private fun UserHeader(
    user: User,
    profile: ProfileBean,
    isFollowing: Boolean?,
    onToggleFollow: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            UserAvatar(url = user.profile_image_urls?.px_50x50 ?: user.profile_image_urls?.medium, size = 64)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = user.name ?: "Unknown", style = MaterialTheme.typography.titleMedium)
                Text(text = "@${user.account ?: user.id}", style = MaterialTheme.typography.labelSmall)
                if (user.is_premium == true) {
                    Text("Premium", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (isFollowing != null) {
                if (isFollowing) {
                    Button(onClick = { onToggleFollow("public") }) { Text("Following") }
                } else {
                    OutlinedButton(onClick = { onToggleFollow("public") }) { Text("Follow") }
                }
            }
        }

        // Stats
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Illusts: ${profile.total_illusts}", style = MaterialTheme.typography.labelMedium)
            Text("Bookmarks: ${profile.total_illust_bookmarks_public}", style = MaterialTheme.typography.labelMedium)
            Text("Following: ${profile.total_follow_users}", style = MaterialTheme.typography.labelMedium)
        }

        // Optional info
        if (!profile.job.isNullOrEmpty() || !profile.region.isNullOrEmpty() || !profile.twitter_account.isNullOrEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!profile.job.isNullOrEmpty()) Text("Job: ${profile.job}", style = MaterialTheme.typography.labelSmall)
                if (!profile.region.isNullOrEmpty()) Text("Region: ${profile.region}", style = MaterialTheme.typography.labelSmall)
                if (!profile.twitter_account.isNullOrEmpty()) Text("Twitter: @${profile.twitter_account}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "feat: UserDetailScreen — 他人主页（头像/统计/Follow/Illusts+Bookmarks tabs）"
```

---

## Task 3: Wire navigation from IllustDetailScreen

**Files:**
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreen.kt`

- [ ] **Step 1: Make author row clickable**

In `IllustDetailContent`, update the author row to be clickable:

```kotlin
Row(
    modifier = Modifier
        .fillMaxWidth()
        .padding(top = 8.dp)
        .clickable {
            illust.user?.id?.let { onUserClick(it) }
        },
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp)
) {
    UserAvatar(url = illust.user?.profile_image_urls?.px_50x50)
    Text(text = illust.user?.name ?: "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    // ... Follow button (from Plan 16)
}
```

Add `onUserClick: (Long) -> Unit` parameter to `IllustDetailContent`.

- [ ] **Step 2: Pass navigation callback from IllustDetailScreen.Content()**

```kotlin
is UiState.Success -> IllustDetailContent(
    illust = s.data,
    relatedState = relatedState,
    onIllustClick = { id -> navigator.push(IllustDetailScreen(id)) },
    onTagClick = { tag -> navigator.push(SearchScreen(initialQuery = tag)) },
    ugoiraState = ugoiraState,
    isFullscreen = isFullscreen,
    onToggleFullscreen = { isFullscreen = !isFullscreen },
    isBookmarked = isBookmarked,
    onToggleBookmark = { screenModel.toggleBookmark(it) },
    isFollowing = isFollowing,
    onToggleFollow = { screenModel.toggleFollow(it) },
    onUserClick = { userId -> navigator.push(UserDetailScreen(userId)) }
)
```

Add import:
```kotlin
import ceui.pixiv.ui.screen.user.UserDetailScreen
```

- [ ] **Step 3: Compile**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Manual verification**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:run --no-daemon`
Open an illust → click author name/avatar → UserDetailScreen opens → shows user header (avatar, name, stats, Follow button) + Illusts/Bookmarks tabs. Click an illust in the list → opens IllustDetailScreen. Back button returns to UserDetailScreen.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: 详情页作者点击跳转 UserDetailScreen"
```

---

## GATE

Verify:
1. Click author name/avatar in IllustDetailScreen → navigates to UserDetailScreen
2. UserDetailScreen shows avatar, name, account, premium, stats (illusts/bookmarks/following)
3. Follow/Following button works (same as Plan 16)
4. Illusts tab shows user's uploaded works
5. Bookmarks tab shows user's public bookmarks
6. Clicking an illust in either tab opens IllustDetailScreen
7. Back button returns to previous screen
