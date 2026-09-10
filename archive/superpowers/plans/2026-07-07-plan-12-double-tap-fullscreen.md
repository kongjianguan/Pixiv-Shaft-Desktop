# Plan 12: 双击缩放 + 全屏切换

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ZoomableImage 支持双击切换缩放（1x↔2x），IllustDetailScreen 支持单击切换全屏（隐藏 TopAppBar）。

**Architecture:** ZoomableImage 加 `onToggleFullscreen` 回调，手势改为 `detectTapGestures(onTap, onDoubleTap)` + 保留 `detectTransformGestures`。IllustDetailScreen 维护 `isFullscreen` state，全屏时隐藏 TopAppBar，底部加半透明浮层（返回+页码）。

**Tech Stack:** Compose Multiplatform 1.7.3, `detectTapGestures`, `Animatable`, `AnimatedVisibility`.

## Global Constraints

- macOS aarch64, `JAVA_HOME=/opt/homebrew/opt/openjdk@21`
- No tests for UI composables (visual verification only)
- `CancellationException` must be rethrown

---

## File Structure

| File | Responsibility |
|------|---------------|
| `app/src/main/kotlin/ceui/pixiv/ui/component/ZoomableImage.kt` | Add double-tap zoom + onToggleFullscreen callback |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreen.kt` | Add isFullscreen state + hide TopAppBar + overlay |

---

## Task 1: ZoomableImage — double-tap zoom + fullscreen callback

**Files:**
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/component/ZoomableImage.kt`

- [ ] **Step 1: Read current ZoomableImage**

Read the file to understand the current gesture handling:
```
app/src/main/kotlin/ceui/pixiv/ui/component/ZoomableImage.kt
```

- [ ] **Step 2: Add onToggleFullscreen parameter + double-tap gesture**

Add parameter:
```kotlin
@Composable
fun ZoomableImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    onToggleFullscreen: (() -> Unit)? = null
) {
```

Replace the gesture handling. The current code likely uses `Modifier.transformable()` or `Modifier.pointerInput { detectTransformGestures }`. Change to:

```kotlin
val scale = remember { Animatable(1f) }
val offsetX = remember { Animatable(0f) }
val offsetY = remember { Animatable(0f) }

Box(
    modifier = modifier
        .clipToBounds()
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = {
                    onToggleFullscreen?.invoke()
                },
                onDoubleTap = {
                    val target = if (scale.value < 1.5f) 2f else 1f
                    scope.launch {
                        scale.animateTo(target)
                        if (target == 1f) {
                            offsetX.animateTo(0f)
                            offsetY.animateTo(0f)
                        }
                    }
                }
            )
        }
        .pointerInput(Unit) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                scope.launch {
                    scale.snapTo((scale.value * zoom).coerceIn(0.5f, 5f))
                    if (scale.value > 1f) {
                        offsetX.snapTo(offsetX.value + pan.x)
                        offsetY.snapTo(offsetY.value + pan.y)
                    } else {
                        offsetX.snapTo(0f)
                        offsetY.snapTo(0f)
                    }
                }
            }
        }
) {
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                translationX = offsetX.value
                translationY = offsetY.value
            },
        contentScale = ContentScale.Fit
    )
}
```

Add imports:
```kotlin
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateTo
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
```

- [ ] **Step 3: Compile**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: ZoomableImage 双击缩放 + 全屏回调"
```

---

## Task 2: IllustDetailScreen — fullscreen state + overlay

**Files:**
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreen.kt`

- [ ] **Step 1: Add isFullscreen state**

In `IllustDetailScreen.Content()`:
```kotlin
var isFullscreen by remember { mutableStateOf(false) }
```

- [ ] **Step 2: Conditionally hide TopAppBar in fullscreen**

```kotlin
Scaffold(
    topBar = {
        if (!isFullscreen) {
            TopAppBar(
                title = { Text("Illust #$illustId") },
                navigationIcon = {
                    IconButton(onClick = { navigator.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    }
) { padding ->
    Box(modifier = Modifier.padding(if (isFullscreen) PaddingValues(0.dp) else padding)) {
        when (val s = illustState) {
            is UiState.Loading -> LoadingView()
            is UiState.Error -> ErrorView(s.message, {})
            is UiState.Success -> IllustDetailContent(
                illust = s.data,
                relatedState = relatedState,
                onIllustClick = { id -> navigator.push(IllustDetailScreen(id)) },
                onTagClick = { tag -> navigator.push(SearchScreen(initialQuery = tag)) },
                ugoiraState = ugoiraState,
                isFullscreen = isFullscreen,
                onToggleFullscreen = { isFullscreen = !isFullscreen }
            )
        }
    }
}
```

- [ ] **Step 3: Pass isFullscreen + onToggleFullscreen to IllustDetailContent**

Update `IllustDetailContent` signature:
```kotlin
@Composable
private fun IllustDetailContent(
    illust: Illust,
    relatedState: UiState<List<Illust>>,
    onIllustClick: (Long) -> Unit,
    onTagClick: (String) -> Unit,
    ugoiraState: UiState<UgoiraMetaData?> = UiState.Loading,
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {}
) {
```

Pass `onToggleFullscreen` to each `ZoomableImage` call:
```kotlin
ZoomableImage(
    model = imageUrls[page],
    contentDescription = "Page ${page + 1}",
    modifier = Modifier.fillMaxWidth(),
    onToggleFullscreen = onToggleFullscreen
)
```

- [ ] **Step 4: Add fullscreen overlay (back button + page indicator)**

When `isFullscreen`, show a semi-transparent overlay at the bottom:
```kotlin
// At the end of the image gallery item, inside the Column:
if (isFullscreen && imageUrls.size > 1) {
    val pagerState = rememberPagerState(pageCount = { imageUrls.size })
    // ... (pager already declared above)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "第 ${pagerState.currentPage + 1} / ${imageUrls.size} P  ← tap to exit",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium
        )
    }
}
```

Also add a back button overlay at the top-left:
```kotlin
if (isFullscreen) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(8.dp)
    ) {
        IconButton(onClick = { isFullscreen = false }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Exit fullscreen", tint = Color.White)
        }
    }
}
```

- [ ] **Step 5: Compile**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Manual verification**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:run --no-daemon`
Open an illust → single-tap image → TopAppBar hides (fullscreen) → single-tap again → TopAppBar returns. Double-tap → zooms to 2x → double-tap → back to 1x.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat: 详情页全屏切换 + 双击缩放 + 全屏浮层"
```

---

## GATE

Verify:
1. Single-tap toggles fullscreen (TopAppBar hides/shows)
2. Double-tap toggles zoom (1x ↔ 2x with smooth animation)
3. Pinch-zoom still works
4. Fullscreen overlay shows back button + page indicator
