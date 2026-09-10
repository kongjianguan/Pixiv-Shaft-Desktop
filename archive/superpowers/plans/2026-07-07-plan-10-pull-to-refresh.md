# Plan 10: 下拉刷新 + Tab 回顶

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给所有列表页加下拉刷新 + 再次点击 Tab 回顶刷新。

**Architecture:** 各 ScreenModel 重构 init 逻辑为 `loadInitial()` + `refresh()` + `fetchData()`，加 `isRefreshing` state。列表页用 Material3 `PullToRefreshBox` 包裹。MainScreen 记录上次 Tab，再次点击触发 `scrollToItem(0)` + `refresh()`。

**Tech Stack:** Compose Multiplatform 1.7.3, Material 3 `PullToRefreshBox`, Voyager 1.0.1.

## Global Constraints

- macOS aarch64, `JAVA_HOME=/opt/homebrew/opt/openjdk@21`
- No tests for UI composables (visual verification only)
- `CancellationException` must be rethrown
- `./gradlew :app:run` for verification

---

## File Structure

| File | Responsibility |
|------|---------------|
| `app/.../screen/recommend/RecommendScreenModel.kt` | Refactor init + add refresh/isRefreshing |
| `app/.../screen/discover/DiscoverScreenModel.kt` | Refactor init + add refresh/isRefreshing |
| `app/.../screen/search/SearchScreenModel.kt` | Refactor init + add refresh/isRefreshing |
| `app/.../screen/profile/ProfileScreenModel.kt` | Refactor init + add refresh/isRefreshing |
| `app/.../screen/recommend/RecommendScreen.kt` | Wrap grid in PullToRefreshBox + scroll-to-top |
| `app/.../screen/discover/DiscoverScreen.kt` | Wrap list in PullToRefreshBox + scroll-to-top |
| `app/.../screen/search/SearchScreen.kt` | Wrap results in PullToRefreshBox |
| `app/.../screen/profile/ProfileScreen.kt` | Wrap list in PullToRefreshBox |
| `app/.../navigation/MainScreen.kt` | Tab re-click detection + scroll-to-top event |

---

## Task 1: Refactor ScreenModels — add refresh + isRefreshing

**Files:**
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/recommend/RecommendScreenModel.kt`
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/discover/DiscoverScreenModel.kt`
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/search/SearchScreenModel.kt`
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/profile/ProfileScreenModel.kt`

- [ ] **Step 1: Refactor RecommendScreenModel**

Replace the existing `init`/`refresh()` with the three-method pattern. Add `isRefreshing`:

```kotlin
private val _isRefreshing = MutableStateFlow(false)
val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

init { loadInitial() }

private fun loadInitial() {
    screenModelScope.launch {
        _state.value = UiState.Loading
        fetchData()
    }
}

fun refresh() {
    screenModelScope.launch {
        _isRefreshing.value = true
        fetchData()
        _isRefreshing.value = false
    }
}

private suspend fun fetchData() {
    try {
        val resp = client.appApi.getWalkthroughWorks()
        pager.refresh(resp)
        _state.value = UiState.Success(pager.items.value)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (_state.value !is UiState.Success) {
            _state.value = UiState.Error(e.message ?: "Failed to load recommendations")
        }
    }
}
```

- [ ] **Step 2: Refactor DiscoverScreenModel**

Add `isRefreshing`. Split `loadTags()` and `loadRanking()` into initial vs refresh:

```kotlin
private val _isRefreshing = MutableStateFlow(false)
val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

init { loadInitial() }

private fun loadInitial() {
    _tagsState.value = UiState.Loading
    _rankingState.value = UiState.Loading
    fetchTags()
    fetchRanking(_currentMode)
}

fun refresh() {
    screenModelScope.launch {
        _isRefreshing.value = true
        fetchTags()
        fetchRanking(_currentMode)
        _isRefreshing.value = false
    }
}
```

Rename existing `loadTags()` → `fetchTags()` (set Loading only in `loadInitial`, not in `fetchTags`). Same for `loadRanking()` → `fetchRanking(mode)` (the public `loadRanking(mode)` stays as-is for FilterChip click, but internally calls `fetchRanking`).

- [ ] **Step 3: Refactor SearchScreenModel**

Add `isRefreshing` and `refresh()`:

```kotlin
private val _isRefreshing = MutableStateFlow(false)
val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

fun refresh() {
    val q = _query.value
    if (q.isBlank()) {
        screenModelScope.launch {
            _isRefreshing.value = true
            // just reload history, no search
            loadHistory()
            _isRefreshing.value = false
        }
    } else {
        screenModelScope.launch {
            _isRefreshing.value = true
            // re-run search without clearing results
            try {
                val resp = client.appApi.searchIllustManga(
                    word = q, sort = "date_desc",
                    search_target = "partial_match_for_tags",
                    merge_plain_keyword_results = true,
                    include_translated_tag_results = true
                )
                pager.refresh(resp)
                _resultsState.value = UiState.Success(pager.items.value)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (_resultsState.value !is UiState.Success)
                    _resultsState.value = UiState.Error(e.message ?: "Search failed")
            } finally { _isRefreshing.value = false }
        }
    }
}
```

- [ ] **Step 4: Refactor ProfileScreenModel**

Add `isRefreshing` and `refresh()`:

```kotlin
private val _isRefreshing = MutableStateFlow(false)
val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

fun refresh() {
    screenModelScope.launch {
        _isRefreshing.value = true
        loadProfile()
        loadHistory()
        _isRefreshing.value = false
    }
}
```

- [ ] **Step 5: Compile**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "refactor: ScreenModels 加 refresh/isRefreshing 三段式 init"
```

---

## Task 2: PullToRefreshBox on list screens

**Files:**
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/recommend/RecommendScreen.kt`
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/discover/DiscoverScreen.kt`
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/search/SearchScreen.kt`
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/profile/ProfileScreen.kt`

- [ ] **Step 1: Add PullToRefreshBox to RecommendScreen**

Import:
```kotlin
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
```

Wrap the grid:
```kotlin
val isRefreshing by screenModel.isRefreshing.collectAsState()
val gridState = rememberLazyStaggeredGridState()

PullToRefreshBox(
    isRefreshing = isRefreshing,
    onRefresh = { screenModel.refresh() },
    modifier = Modifier.fillMaxSize()
) {
    when (val s = state) {
        is UiState.Loading -> LoadingView()
        is UiState.Error -> ErrorView(s.message, { screenModel.refresh() })
        is UiState.Success -> {
            LazyVerticalStaggeredGrid(
                state = gridState,
                // ... existing params
            ) { ... }
        }
    }
}
```

- [ ] **Step 2: Add PullToRefreshBox to DiscoverScreen, SearchScreen, ProfileScreen**

Same pattern — wrap the LazyColumn in `PullToRefreshBox`, pass `isRefreshing` and `onRefresh`.

- [ ] **Step 3: Compile and run**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Manual verification**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:run --no-daemon`
Verify: pull down on each list → refresh spinner appears → data reloads.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: 列表页加 PullToRefreshBox 下拉刷新"
```

---

## Task 3: Tab re-click scroll-to-top + refresh

**Files:**
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/navigation/MainScreen.kt`
- Modify: each Screen — accept scroll-to-top event

- [ ] **Step 1: Add Tab re-click detection in MainScreen**

Use a `CompositionLocal` to pass a "scroll to top" trigger:

```kotlin
val LocalScrollToTop = compositionLocalOf { mutableStateOf(0) }
```

In `BottomBar()`, track last clicked tab:
```kotlin
@Composable
private fun BottomBar() {
    val tabNavigator = LocalTabNavigator.current
    var lastTab by remember { mutableStateOf<String?>(null) }
    val scrollToTopState = LocalScrollToTop.current

    listOf(RecommendTab, DiscoverTab, SearchTab, ProfileTab).forEach { tab ->
        NavigationBarItem(
            selected = tabNavigator.current.key == tab.key,
            onClick = {
                if (tabNavigator.current.key == tab.key) {
                    // Same tab re-clicked → scroll to top + refresh
                    scrollToTopState.intValue++
                } else {
                    tabNavigator.current = tab
                }
                lastTab = tab.key
            },
            icon = { Icon(painter = tab.options.icon!!, contentDescription = tab.options.title) },
            label = { Text(tab.options.title) }
        )
    }
}
```

Provide `LocalScrollToTop` in `MainScreen.Content()`:
```kotlin
@Composable
override fun Content() {
    val scrollToTopState = remember { mutableStateOf(0) }
    CompositionLocalProvider(LocalScrollToTop provides scrollToTopState) {
        TabNavigator(RecommendTab) {
            Scaffold(bottomBar = { BottomBar() }) { padding ->
                Box(modifier = Modifier.padding(padding)) { CurrentTab() }
            }
        }
    }
}
```

- [ ] **Step 2: Observe scroll-to-top in each Screen**

In each Screen's `Content()`, observe the counter:
```kotlin
val scrollToTopTrigger = LocalScrollToTop.current.intValue
LaunchedEffect(scrollToTopTrigger) {
    if (scrollToTopTrigger > 0) {
        gridState.scrollToItem(0)
        screenModel.refresh()
    }
}
```

- [ ] **Step 3: Compile and run**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Manual verification**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:run --no-daemon`
Verify: click 推荐 tab → scroll down → click 推荐 again → list scrolls to top + refresh spinner.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: Tab 再次点击回顶 + 刷新"
```

---

## GATE

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:run --no-daemon`

Verify:
1. All 4 tabs support pull-to-refresh
2. Re-clicking current tab scrolls to top and refreshes
3. Refresh failure keeps existing data
