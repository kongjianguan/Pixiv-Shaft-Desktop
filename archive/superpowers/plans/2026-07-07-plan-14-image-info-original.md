# Plan 14: 图片信息 + 原图链接

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 详情页显示图片分辨率/页数/日期信息行，TopAppBar 加"在浏览器打开原图"按钮。

**Architecture:** IllustDetailContent 加图片信息行（item 2 in layout）。新建 `DesktopUtils.kt` 提供 `openInBrowser()`。TopAppBar actions 加 `OpenInNew` 图标按钮。

**Tech Stack:** Compose Multiplatform 1.7.3, Material 3, `java.awt.Desktop`.

## Global Constraints

- macOS aarch64, `JAVA_HOME=/opt/homebrew/opt/openjdk@21`
- No tests for UI composables (visual verification only)
- IllustDetailScreen 已在加载原图（`meta_single_page.original_image_url`）

---

## File Structure

| File | Responsibility |
|------|---------------|
| `app/src/main/kotlin/ceui/pixiv/util/DesktopUtils.kt` | `openInBrowser(url)` utility |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreen.kt` | Image info row + TopAppBar action |

---

## Task 1: Create DesktopUtils + image info row + original URL button

**Files:**
- Create: `app/src/main/kotlin/ceui/pixiv/util/DesktopUtils.kt`
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreen.kt`

- [ ] **Step 1: Create DesktopUtils.kt**

```kotlin
package ceui.pixiv.util

import java.awt.Desktop
import java.net.URI

fun openInBrowser(url: String) {
    try {
        val desktop = Desktop.getDesktop()
        if (desktop.isSupported(Desktop.Action.BROWSE)) {
            desktop.browse(URI(url))
        }
    } catch (e: Exception) {
        println("Failed to open browser: ${e.message}")
    }
}
```

- [ ] **Step 2: Add image info row to IllustDetailContent**

In `IllustDetailContent`'s `LazyColumn`, after the image gallery item, before the title+author item (item 2 in layout), add:

```kotlin
// Image info (item 2 in layout)
item {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "${illust.width}×${illust.height}",
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            text = "| ${illust.page_count}P",
            style = MaterialTheme.typography.labelSmall
        )
        val date = illust.create_date?.take(10)
        if (date != null) {
            Text(
                text = "| $date",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
```

Add import:
```kotlin
import androidx.compose.foundation.layout.Arrangement
```
(may already be imported)

- [ ] **Step 3: Add OpenInNew button to TopAppBar actions**

In `IllustDetailScreen.Content()`, update the `Scaffold`'s `topBar`:

```kotlin
topBar = {
    if (!isFullscreen) {
        TopAppBar(
            title = { Text("Illust #$illustId") },
            navigationIcon = {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                val illust = (illustState as? UiState.Success)?.data
                val originalUrl = illust?.maxUrl()
                if (originalUrl != null) {
                    IconButton(onClick = { openInBrowser(originalUrl) }) {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = "Open original in browser"
                        )
                    }
                }
            }
        )
    }
}
```

Add imports:
```kotlin
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import ceui.pixiv.util.openInBrowser
```

- [ ] **Step 4: Compile**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Manual verification**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:run --no-daemon`
Open an illust → verify "1500×980 | 1P | 2014-03-10" shows below image. Click OpenInNew icon → browser opens original image URL.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: 详情页图片信息行 + 原图浏览器打开按钮"
```

---

## GATE

Verify: resolution/page count/date show below image. OpenInNew button opens browser with original URL.
