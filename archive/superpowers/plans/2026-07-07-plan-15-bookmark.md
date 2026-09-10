# Plan 15: 收藏/取消收藏

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 详情页 TopAppBar 加心形收藏按钮，点击公开收藏/取消，长按非公开收藏。

**Architecture:** IllustDetailScreenModel 加 `isBookmarked` state + `toggleBookmark(restrict)` 方法（乐观更新+失败回滚）。TopAppBar actions 加心形 IconButton。API 端点已存在：`postBookmark(illust_id, restrict)` + `removeBookmark(illust_id)`。

**Tech Stack:** Compose Multiplatform 1.7.3, Material 3, `combinedClickable`, existing API.

## Global Constraints

- macOS aarch64, `JAVA_HOME=/opt/homebrew/opt/openjdk@21`
- No tests for UI composables (visual verification only)
- `CancellationException` must be rethrown
- API already exists: `postBookmark(illust_id, restrict)` / `removeBookmark(illust_id)`
- `Illust.is_bookmarked: Boolean?` already exists in model
- `Params.TYPE_PUBLIC = "public"`, `Params.TYPE_PRIVATE = "private"`

---

## File Structure

| File | Responsibility |
|------|---------------|
| `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreenModel.kt` | Add isBookmarked state + toggleBookmark |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreen.kt` | Add bookmark IconButton in TopAppBar actions |

---

## Task 1: ScreenModel — isBookmarked state + toggleBookmark

**Files:**
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreenModel.kt`

- [ ] **Step 1: Read current ScreenModel**

Read the file to understand existing structure:
```
app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreenModel.kt
```

- [ ] **Step 2: Add isBookmarked state + sync on load + toggleBookmark**

Add to `IllustDetailScreenModel`:

```kotlin
private val _isBookmarked = MutableStateFlow<Boolean?>(null)
val isBookmarked: StateFlow<Boolean?> = _isBookmarked.asStateFlow()
```

In the illust loading method (after `_illustState.value = UiState.Success(illust)`):
```kotlin
_isBookmarked.value = illust.is_bookmarked
```

Add toggle method:
```kotlin
fun toggleBookmark(restrict: String = "public") {
    val current = _isBookmarked.value ?: return
    val illustId = illustId // the constructor parameter
    screenModelScope.launch {
        // 乐观更新
        _isBookmarked.value = !current
        try {
            if (current) {
                client.appApi.removeBookmark(illustId)
            } else {
                client.appApi.postBookmark(illustId, restrict)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 回滚
            _isBookmarked.value = current
        }
    }
}
```

- [ ] **Step 3: Compile**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: IllustDetailScreenModel 收藏状态管理 + toggleBookmark"
```

---

## Task 2: UI — bookmark button in TopAppBar

**Files:**
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreen.kt`

- [ ] **Step 1: Add bookmark IconButton to TopAppBar actions**

In `IllustDetailScreen.Content()`, update TopAppBar `actions`:

```kotlin
actions = {
    val isBookmarked by screenModel.isBookmarked.collectAsState()
    val originalUrl = (illustState as? UiState.Success)?.data?.maxUrl()
    if (originalUrl != null) {
        IconButton(onClick = { openInBrowser(originalUrl) }) {
            Icon(Icons.Default.OpenInNew, contentDescription = "Open original in browser")
        }
    }
    val bookmarked = isBookmarked
    if (bookmarked != null) {
        IconButton(
            onClick = { screenModel.toggleBookmark("public") },
            modifier = Modifier.combinedClickable(
                onClick = { screenModel.toggleBookmark("public") },
                onLongClick = { screenModel.toggleBookmark("private") }
            )
        ) {
            Icon(
                imageVector = if (bookmarked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = "Bookmark",
                tint = if (bookmarked) Color.Red else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
```

Add imports:
```kotlin
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.ui.graphics.Color
```

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Manual verification**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:run --no-daemon`
Open an illust → heart icon shows outline (not bookmarked) → click → heart fills red (bookmarked). Click again → outline (unbookmarked). Long-press → private bookmark (same visual but API uses restrict=private). Verify on Pixiv website that bookmark actually saved.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: 详情页收藏按钮 + 长按非公开收藏"
```

---

## GATE

Verify:
1. Heart icon reflects `is_bookmarked` from API
2. Click toggles public bookmark (icon updates immediately = optimistic update)
3. Long-press toggles private bookmark
4. API failure → icon reverts (rollback)
5. Bookmark actually saved on Pixiv (check website)
