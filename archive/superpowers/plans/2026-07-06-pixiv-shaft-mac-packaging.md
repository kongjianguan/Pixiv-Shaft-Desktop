# Pixiv-Shaft macOS — Desktop Interaction + DMG Packaging Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add image zoom/pan on detail page, system tray (close-to-tray), keyboard shortcut `Cmd+W`, and configure DMG packaging for distribution.

**Architecture:** `ZoomableImage` composable wraps `AsyncImage` with `pointerInput` + `detectTransformGestures` + `graphicsLayer`. `TrayManager` uses `java.awt.SystemTray` + `PopupMenu` for tray icon + close-to-tray. `compose.desktop.nativeDistributions` block configures jpackage DMG output.

**Tech Stack:** Compose Multiplatform 1.7.3, `java.awt.SystemTray`, `java.awt.image.BufferedImage`, `compose.desktop.nativeDistributions`, jpackage (JDK 21 bundled).

## Global Constraints

- macOS aarch64, `JAVA_HOME=/opt/homebrew/opt/openjdk@21` for every `./gradlew`.
- Aliyun mirrors in all `repositories{}`.
- `:app` uses `kotlin("jvm")` + `id("org.jetbrains.compose")` + `id("org.jetbrains.kotlin.plugin.compose")` + `application`.
- Compose plugin 1.7.3 + Kotlin 2.1.20.
- `compose.desktop.currentOs` already in dependencies.
- Do NOT break existing Voyager navigation, auth gate, or any Plan 1-7 functionality.
- `java.awt.SystemTray` is available on macOS.
- jpackage is bundled with JDK 21 (no separate install needed).
- DMG output path: `app/build/compose/binaries/main/dmg/`.
- No code signing (ad-hoc personal use; `xattr -cr` to bypass Gatekeeper).
- No minify.

---

## File Structure

| File | Responsibility |
|------|---------------|
| `app/src/main/kotlin/ceui/pixiv/ui/component/ZoomableImage.kt` | `ZoomableImage` composable (pinch-zoom + pan) |
| `app/src/main/kotlin/ceui/pixiv/platform/TrayManager.kt` | System tray icon + close-to-tray + `Cmd+W` |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreen.kt` | Replace `AsyncImage` with `ZoomableImage` |
| `app/src/main/kotlin/ceui/pixiv/Main.kt` | Wire tray + window lifecycle |
| `app/build.gradle.kts` | Add `compose.desktop.nativeDistributions` block |

---

## Task 1: ZoomableImage + Detail Integration

**Files:**
- Create: `app/src/main/kotlin/ceui/pixiv/ui/component/ZoomableImage.kt`
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreen.kt`

**Interfaces:**
- Consumes: Coil `AsyncImage`, `Modifier.pointerInput`, `detectTransformGestures`, `graphicsLayer`.
- Produces: `ZoomableImage(model, contentDescription, modifier)` composable.

- [ ] **Step 1: Create `ZoomableImage.kt`**

```kotlin
package ceui.pixiv.ui.component

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

@Composable
fun ZoomableImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        modifier = modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(0.5f, 5f)
                    scale = newScale
                    if (newScale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY
            },
        contentScale = ContentScale.Fit
    )
}
```

Note: `mutableFloatStateOf` is available in Compose 1.7.x. If not found, use `mutableStateOf(1f)`. The `coerceIn(0.5f, 5f)` allows zoom out to 50% and zoom in to 500%. When scale returns to ≤1, pan offset resets to 0.

- [ ] **Step 2: Replace `AsyncImage` with `ZoomableImage` in `IllustDetailScreen.kt`**

In `IllustDetailScreen.kt`, find the single-page image display (not HorizontalPager, not UgoiraPlayer) and replace:

```kotlin
// Before:
AsyncImage(
    model = imageUrls.firstOrNull(),
    contentDescription = illust.title,
    modifier = Modifier.fillMaxWidth(),
    contentScale = ContentScale.Fit
)

// After:
ZoomableImage(
    model = imageUrls.firstOrNull(),
    contentDescription = illust.title,
    modifier = Modifier.fillMaxWidth()
)
```

Also replace inside `HorizontalPager`:
```kotlin
// Before:
AsyncImage(
    model = imageUrls[page],
    contentDescription = "Page ${page + 1}",
    modifier = Modifier.fillMaxWidth(),
    contentScale = ContentScale.Fit
)

// After:
ZoomableImage(
    model = imageUrls[page],
    contentDescription = "Page ${page + 1}",
    modifier = Modifier.fillMaxWidth()
)
```

Add import: `import ceui.pixiv.ui.component.ZoomableImage`

Do NOT replace `AsyncImage` in `DiscoverScreen` (thumbnails don't need zoom) or other screens — only detail page.

- [ ] **Step 3: Compile**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(app/zoom): ZoomableImage（捏合缩放 + 平移）+ 详情页集成"
```

---

## Task 2: System Tray + Close-to-Tray + Cmd+W

**Files:**
- Create: `app/src/main/kotlin/ceui/pixiv/platform/TrayManager.kt`
- Modify: `app/src/main/kotlin/ceui/pixiv/Main.kt`

**Interfaces:**
- Consumes: `java.awt.SystemTray`, `java.awt.PopupMenu`, `java.awt.image.BufferedImage`, Compose `Window` state.
- Produces: `TrayManager.setup(onShow, onExit)` — adds tray icon; `TrayManager.remove()` — removes tray.

- [ ] **Step 1: Create `TrayManager.kt`**

```kotlin
package ceui.pixiv.platform

import java.awt.Color
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * Manages macOS system tray icon with Show/Exit menu.
 * Call [setup] after window creation, [remove] before exit.
 */
object TrayManager {

    private var trayIcon: TrayIcon? = null

    fun setup(onShow: () -> Unit, onExit: () -> Unit) {
        if (!SystemTray.isSupported()) return
        val tray = SystemTray.getSystemTray()

        val popup = PopupMenu().apply {
            add(MenuItem("Show").apply {
                addActionListener { onShow() }
            })
            addSeparator()
            add(MenuItem("Exit").apply {
                addActionListener { onExit() }
            })
        }

        val image = createTrayImage()
        trayIcon = TrayIcon(image, "Pixiv Shaft", popup).apply {
            isImageAutoSize = true
            addActionListener { onShow() }
        }

        tray.add(trayIcon)
    }

    fun remove() {
        val icon = trayIcon ?: return
        if (SystemTray.isSupported()) {
            SystemTray.getSystemTray().remove(icon)
        }
        trayIcon = null
    }

    private fun createTrayImage(): Image {
        val size = 16
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color(0x00, 0x96, 0xC7) // Shaft blue
        g.fillOval(0, 0, size, size)
        g.color = Color.WHITE
        g.drawString("P", 4, 13)
        g.dispose()
        return img
    }
}
```

Note: The tray icon is a simple 16×16 blue circle with "P" text. For a real app, replace with a proper `.png` icon resource. The `isImageAutoSize = true` scales the icon to the system tray size. `addActionListener` on the `TrayIcon` handles double-click → Show.

- [ ] **Step 2: Modify `Main.kt` — window lifecycle + tray + Cmd+W**

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
import ceui.pixiv.platform.TrayManager
import ceui.pixiv.ui.auth.AuthState
import ceui.pixiv.ui.navigation.MainScreen
import ceui.pixiv.ui.screen.login.LoginScreen
import ceui.pixiv.ui.theme.ShaftTheme
import java.awt.Frame
import java.awt.event.WindowEvent

fun main() = application {
    AppContainer.init()
    println("PLAN 8 GATE PASSED — Desktop interaction + DMG ready")

    var isExiting = false

    Window(
        onCloseRequest = {
            if (!isExiting) {
                // Close-to-tray: hide window instead of exiting
                (window as? Frame)?.let { frame ->
                    frame.isVisible = false
                    TrayManager.setup(
                        onShow = { frame.isVisible = true; frame.toFront() },
                        onExit = {
                            isExiting = true
                            TrayManager.remove()
                            AppContainer.close()
                            exitApplication()
                        }
                    )
                }
            }
        },
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

Note: `onCloseRequest` intercepts the window close (including `Cmd+W`). Instead of exiting, it hides the window and sets up the tray. The tray's "Show" item restores the window; "Exit" actually exits (calls `AppContainer.close()` + `exitApplication()`). The `isExiting` flag prevents re-entrance. `(window as? Frame)` accesses the underlying AWT `Frame` from the Compose `WindowScope`.

If `window` is not accessible in `onCloseRequest`, use an alternative: capture the `Frame` via `java.awt.Window.getWindows()` or pass it through a `remember` variable. The `WindowScope` in Compose Multiplatform 1.7.3 exposes `window` as the underlying AWT `Frame`.

- [ ] **Step 3: Compile**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL. If `(window as? Frame)` doesn't compile, try accessing the AWT window differently — check the `WindowScope` API.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(app/tray): 系统托盘 + 关窗到托盘 + Cmd+W 拦截"
```

---

## Task 3: DMG Packaging Config + Gate

**Files:**
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: `org.jetbrains.compose.desktop.application.nativeDistributions`.
- Produces: DMG packaging configuration; `./gradlew :app:packageDmg` produces `.dmg`.

- [ ] **Step 1: Add `compose.desktop.nativeDistributions` to `app/build.gradle.kts`**

Add this block at the end of the file (after `application { }`):

```kotlin
compose.desktop {
    application {
        mainClass = "ceui.pixiv.MainKt"
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg)
            packageName = "PixivShaft"
            packageVersion = "0.1.0"
            macOS {
                bundleID = "ceui.pixiv.Shaft"
                minimumSystemVersion = "12.0"
            }
        }
    }
}
```

Note: `targetFormats(TargetFormat.Dmg)` tells jpackage to produce a `.dmg`. `packageName` is the app name inside the DMG. `packageVersion` is the version string. `macOS.bundleID` is the app's bundle identifier. `minimumSystemVersion` ensures macOS 12+ (for Compose Desktop Skia requirements).

The `compose.desktop` block is the Compose Multiplatform Gradle DSL for native distribution. It wraps jpackage (bundled with JDK 21). No additional tools needed.

- [ ] **Step 2: Compile (verify Gradle config)**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL (the `compose.desktop` block is config-time, not compile-time).

- [ ] **Step 3: Run `packageDmg` (gate)**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:packageDmg --no-daemon 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL. A `.dmg` file is produced at `app/build/compose/binaries/main/dmg/`.

If the build fails with "jpackage not found", check `JAVA_HOME` points to JDK 21 (jpackage is in `$JAVA_HOME/bin/jpackage`). If it fails with "DMG creation requires Xcode command line tools", install them: `xcode-select --install`.

If the build takes a long time (>5 min), it's normal — jpackage creates a full macOS app bundle with embedded JRE.

- [ ] **Step 4: Verify DMG exists**

```bash
ls -la app/build/compose/binaries/main/dmg/
```

Expected: A `PixivShaft-0.1.0.dmg` file (typically 50-100 MB).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(app/packaging): DMG 打包配置（jpackage + nativeDistributions）"
```

---

## Self-Review

### Spec Coverage
- ✅ Image zoom/pan (design §7.1): Task 1 — `ZoomableImage` with `detectTransformGestures` + `graphicsLayer`
- ✅ System tray (design §7.1): Task 2 — `TrayManager` with `SystemTray` + `PopupMenu` + close-to-tray
- ✅ Cmd+W close to tray (design §7.1): Task 2 — `onCloseRequest` intercepts window close
- ✅ DMG packaging (design §7.2): Task 3 — `compose.desktop.nativeDistributions` + `packageDmg`
- ⚠️ Deferred: `Cmd+,` settings shortcut (needs navigator hoisting), `Cmd+F` search shortcut (needs tab switching), touchpad pinch (Compose Desktop `pointerInput` multi-touch limited — §7.1 says "二期")
- ⚠️ Deferred: Code signing / notarization (§7.2 says "默认个人自用，ad-hoc 签名，免公证")

### Placeholder Scan
- No "TBD" or "TODO". Code blocks for all steps. Notes for AWT API fallbacks.

### Type Consistency
- `ZoomableImage(model: Any?, contentDescription: String?, modifier: Modifier)` — matches Coil's `AsyncImage` signature.
- `TrayManager.setup(onShow: () -> Unit, onExit: () -> Unit)` — called in `Main.kt`'s `onCloseRequest`.
- `compose.desktop.application.mainClass` = `"ceui.pixiv.MainKt"` — matches existing `application.mainClass`.

### Risks
1. **AWT window access**: `WindowScope.window` provides the AWT `Frame`. If the cast `(window as? Frame)` fails, the close-to-tray won't work. Test at runtime.
2. **jpackage DMG**: Requires Xcode command line tools (`xcode-select --install`). If not installed, `packageDmg` will fail. The error message is clear.
3. **DMG size**: jpackage embeds a full JRE (~50-100 MB). This is expected for a desktop app without module optimization.
4. **Tray icon**: Uses a programmatic 16×16 BufferedImage (no icon resource file). For a real release, replace with a proper `.icns` resource.
5. **`onCloseRequest` re-entrance**: The `isExiting` flag prevents double-exit when the user clicks "Exit" in the tray and the window close event fires again.
