# Plan 11: 页码指示器

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 多页作品详情页底部显示"第 N/M P"页码指示器。

**Architecture:** 在 `IllustDetailContent` 的 HorizontalPager **外部**（Column 兄弟节点）加页码行。`pagerState.currentPage` 变化时数字更新，但不跟随 Pager 横向滑动。

**Tech Stack:** Compose Multiplatform 1.7.3, Material 3, `HorizontalPager` + `rememberPagerState`.

## Global Constraints

- macOS aarch64, `JAVA_HOME=/opt/homebrew/opt/openjdk@21`
- No tests for UI composables (visual verification only)
- `CancellationException` must be rethrown

---

## File Structure

| File | Responsibility |
|------|---------------|
| `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreen.kt` | Add page indicator below HorizontalPager |

---

## Task 1: Add page indicator to IllustDetailScreen

**Files:**
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreen.kt`

- [ ] **Step 1: Wrap HorizontalPager in Column + add page indicator**

Find the multi-page branch (line ~135-143) in `IllustDetailContent`:

```kotlin
} else if (imageUrls.size > 1) {
    val pagerState = rememberPagerState(pageCount = { imageUrls.size })
    HorizontalPager(state = pagerState) { page ->
        ZoomableImage(
            model = imageUrls[page],
            contentDescription = "Page ${page + 1}",
            modifier = Modifier.fillMaxWidth()
        )
    }
}
```

Replace with:

```kotlin
} else if (imageUrls.size > 1) {
    val pagerState = rememberPagerState(pageCount = { imageUrls.size })
    val currentPage by remember { derivedStateOf { pagerState.currentPage } }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalPager(state = pagerState) { page ->
            ZoomableImage(
                model = imageUrls[page],
                contentDescription = "Page ${page + 1}",
                modifier = Modifier.fillMaxWidth()
            )
        }
        Text(
            text = "第 ${currentPage + 1} / ${imageUrls.size} P",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
}
```

**Key:** The `Text` is OUTSIDE the `HorizontalPager` content lambda — it's a sibling in the `Column`. This prevents it from scrolling horizontally with each page.

- [ ] **Step 2: Add missing imports**

```kotlin
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
```

(Some may already be imported.)

- [ ] **Step 3: Compile**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Manual verification**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:run --no-daemon`
Navigate to a multi-page illust → verify "第 N/M P" shows below the pager and updates when swiping. Single-page illusts should NOT show the indicator.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: 多页作品详情页加页码指示器"
```

---

## GATE

Verify: multi-page illust shows "第 N/M P" below image, updates on swipe, stays fixed (doesn't scroll with pager).
