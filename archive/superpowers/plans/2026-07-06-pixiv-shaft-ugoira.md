# Pixiv-Shaft macOS — Ugoira Animation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Display ugoira (animated illustrations) in the detail page — fetch metadata, download zip via anti-GFW client, unzip frames, animate with Compose.

**Architecture:** Add `/v1/ugoira/metadata` API endpoint. `UgoiraPlayer` composable downloads zip via anti-GFW `OkHttpClient`, extracts frames via `ZipInputStream`, decodes via Skia `Image.makeFromEncoded`, animates via `LaunchedEffect` + `delay`. `IllustDetailScreenModel` fetches ugoira metadata when `illust.isGif()`. `IllustDetailScreen` shows `UgoiraPlayer` instead of static `AsyncImage` for ugoira type.

**Tech Stack:** Compose Multiplatform 1.7.3, Skia (`org.jetbrains.skia.Image`), `java.util.zip.ZipInputStream`, OkHttp, Voyager, existing `ImageLoaderFactory` anti-GFW client.

## Global Constraints

- macOS aarch64, `JAVA_HOME=/opt/homebrew/opt/openjdk@21` for every `./gradlew`.
- Aliyun mirrors in all `repositories{}` blocks.
- `:app` uses `kotlin("jvm")` — source dir is `src/main/kotlin/`.
- Voyager 1.0.1: `Screen.Content()` (capital C), `rememberScreenModel`, `screenModelScope`.
- `Illust.isGif()` returns `true` when `type == "ugoira"`.
- `Illust.maxUrl()` returns original image URL (for static illusts).
- `GifInfoResponse` model already exists in `:models` — has `ugoira_metadata: UgoiraMetaData?` with `zip_urls: ZipUrl?` (has `medium: String?`) and `frames: List<GifFrame>?` (each has `file: String?` and `delay: Int?`).
- `ImageLoaderFactory.buildImageClient(settings)` is currently `private` — make it public or add a public wrapper.
- Anti-GFW image client: `RubySSLSocketFactory` + `TrustAllCertManager` + `HttpDns` + HTTP/1.1 (when `isDirectConnect && !requiresStandardClient()`).
- CancellationException must be rethrown.
- `org.jetbrains.skia.Image.makeFromEncoded(bytes: ByteArray)` decodes image from bytes on Desktop JVM. `toComposeImageBitmap()` converts to Compose `ImageBitmap`.
- `java.util.zip.ZipInputStream` for unzip.
- Frame files in the zip are named like `000000.jpg`, `000001.jpg`, etc. Sort by filename to get correct order.
- Frame delays are in milliseconds (`GifFrame.delay: Int?`).
- No tests for UI composables (visual verification only).

---

## File Structure

| File | Responsibility |
|------|---------------|
| `net/src/main/kotlin/ceui/pixiv/net/api/API.kt` | Add `getUgoiraMetadata(illust_id)` endpoint |
| `app/src/main/kotlin/ceui/pixiv/image/ImageLoaderFactory.kt` | Expose `createImageClient(settings)` public method |
| `app/src/main/kotlin/ceui/pixiv/di/AppContainer.kt` | Add `imageClient: OkHttpClient` field |
| `app/src/main/kotlin/ceui/pixiv/ui/component/UgoiraPlayer.kt` | Download zip + unzip + frame animation composable |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreenModel.kt` | Fetch ugoira metadata when `isGif()` |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreen.kt` | Show `UgoiraPlayer` for ugoira type |

---

## Task 1: Ugoira API + Expose Image Client

**Files:**
- Modify: `net/src/main/kotlin/ceui/pixiv/net/api/API.kt`
- Modify: `app/src/main/kotlin/ceui/pixiv/image/ImageLoaderFactory.kt`
- Modify: `app/src/main/kotlin/ceui/pixiv/di/AppContainer.kt`

**Interfaces:**
- Consumes: `GifInfoResponse` model, `Settings`, `ImageLoaderFactory.buildImageClient`.
- Produces: `API.getUgoiraMetadata(illust_id: Long): GifInfoResponse`; `ImageLoaderFactory.createImageClient(settings): OkHttpClient` (public); `AppContainer.imageClient: OkHttpClient`.

- [ ] **Step 1: Add ugoira metadata API endpoint to `API.kt`**

Add this method to the `API` interface in `net/src/main/kotlin/ceui/pixiv/net/api/API.kt`:

```kotlin
    @GET("/v1/ugoira/metadata")
    suspend fun getUgoiraMetadata(@Query("illust_id") illust_id: Long): ceui.loxia.GifInfoResponse
```

- [ ] **Step 2: Expose `createImageClient` in `ImageLoaderFactory.kt`**

In `app/src/main/kotlin/ceui/pixiv/image/ImageLoaderFactory.kt`, add a public method:

```kotlin
    fun createImageClient(settings: Settings): OkHttpClient = buildImageClient(settings)
```

This exposes the existing `buildImageClient` (which configures anti-GFW SSL/DNS/protocol) as a public method. The existing `buildImageClient` remains private — `createImageClient` is a thin public wrapper.

- [ ] **Step 3: Add `imageClient` to `AppContainer.kt`**

In `app/src/main/kotlin/ceui/pixiv/di/AppContainer.kt`, add:

```kotlin
    lateinit var imageClient: okhttp3.OkHttpClient
        private set
```

In `init()`, after `imageLoader = ImageLoaderFactory.create(settingsStore)`, add:

```kotlin
        imageClient = ImageLoaderFactory.createImageClient(settingsStore)
```

- [ ] **Step 4: Compile**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(net+app): ugoira metadata API + 暴露反墙图片 OkHttpClient"
```

---

## Task 2: UgoiraPlayer Composable

**Files:**
- Create: `app/src/main/kotlin/ceui/pixiv/ui/component/UgoiraPlayer.kt`

**Interfaces:**
- Consumes: `AppContainer.imageClient` (anti-GFW OkHttpClient), `org.jetbrains.skia.Image`, `java.util.zip.ZipInputStream`, Compose `ImageBitmap` + `LaunchedEffect`.
- Produces: `UgoiraPlayer(zipUrl: String, frames: List<GifFrame>, modifier: Modifier)` composable.

- [ ] **Step 1: Create `UgoiraPlayer.kt`**

```kotlin
package ceui.pixiv.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import ceui.loxia.GifFrame
import ceui.pixiv.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jetbrains.skia.Image as SkiaImage
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Displays an animated ugoira illustration by downloading the zip,
 * extracting frames, and cycling through them at the specified delays.
 */
@Composable
fun UgoiraPlayer(
    zipUrl: String,
    frames: List<GifFrame>,
    modifier: Modifier = Modifier
) {
    var bitmaps by remember(zipUrl) { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var currentIndex by remember { mutableStateOf(0) }

    // Download + unzip + decode
    LaunchedEffect(zipUrl) {
        try {
            val decoded = withContext(Dispatchers.IO) {
                val client = AppContainer.imageClient
                val request = Request.Builder().url(zipUrl).build()
                client.newCall(request).execute().use { resp ->
                    val bytes = resp.body?.bytes() ?: return@withContext emptyList()
                    decodeUgoiraZip(bytes, frames)
                }
            }
            bitmaps = decoded
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Download/decode failed — bitmaps stays empty, shows nothing
        }
    }

    // Animate
    val currentBitmap = bitmaps.getOrNull(currentIndex)
    if (currentBitmap != null && bitmaps.isNotEmpty()) {
        val delayMs = frames.getOrNull(currentIndex)?.delay ?: 100
        LaunchedEffect(bitmaps, currentIndex) {
            delay(delayMs.toLong())
            currentIndex = (currentIndex + 1) % bitmaps.size
        }
        Image(
            bitmap = currentBitmap,
            contentDescription = "Ugoira animation",
            modifier = modifier.fillMaxWidth(),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(modifier = modifier.fillMaxWidth())
    }
}

/**
 * Downloads and decodes a ugoira zip into a list of ImageBitmap frames.
 * Frames are sorted by filename (000000.jpg, 000001.jpg, ...).
 */
private fun decodeUgoiraZip(
    zipBytes: ByteArray,
    frameMetadata: List<GifFrame>
): List<ImageBitmap> {
    val sortedFrames = frameMetadata.sortedBy { it.file ?: "" }
    val frameMap = sortedFrames.associate { it.file to it.delay }

    val entries = mutableListOf<Pair<String, ByteArray>>()
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name.endsWith(".jpg") || entry.name.endsWith(".png")) {
                val data = zis.readBytes()
                entries.add(entry.name to data)
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
    }

    // Sort by filename to match frame order
    val sortedEntries = entries.sortedBy { it.first }
    return sortedEntries.map { (_, data) ->
        SkiaImage.makeFromEncoded(data).toComposeImageBitmap()
    }
}
```

Note: `org.jetbrains.skia.Image.makeFromEncoded(bytes)` decodes JPEG/PNG from bytes. `toComposeImageBitmap()` converts the Skia image to a Compose `ImageBitmap`. The `ZipInputStream` reads the zip entries. Frames are sorted by filename (`000000.jpg`, `000001.jpg`, ...) to ensure correct order. The `LaunchedEffect` cycle uses `delay(frameDelay)` to advance frames.

If `org.jetbrains.skia.Image` is not available, check if `androidx.compose.ui.graphics.ImageBitmap` can be created from bytes via `androidx.compose.ui.graphics.decodeByteArray` (this is an Android-only API). On Desktop JVM, Skia is the only path. If Skia import fails, try `org.jetbrains.skia.Image` (the full qualified name).

The `client.newCall(request).execute()` is a blocking call — it's wrapped in `withContext(Dispatchers.IO)` to avoid blocking the UI thread.

- [ ] **Step 2: Compile**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL. If Skia import fails, check `compose.desktop.currentOs` includes Skia natives (it should).

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(app/ugoira): UgoiraPlayer composable（下载 zip + 解压 + 帧动画）"
```

---

## Task 3: Integrate Ugoira in IllustDetailScreen

**Files:**
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreenModel.kt`
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreen.kt`

**Interfaces:**
- Consumes: `API.getUgoiraMetadata(illustId)`, `UgoiraPlayer`, `Illust.isGif()`, `GifInfoResponse`.
- Produces: `IllustDetailScreenModel` with ugoira metadata state; `IllustDetailScreen` shows `UgoiraPlayer` for ugoira.

- [ ] **Step 1: Add ugoira metadata to `IllustDetailScreenModel.kt`**

Add these imports and fields:

```kotlin
import ceui.loxia.GifInfoResponse
import ceui.loxia.UgoiraMetaData
```

Add state fields:

```kotlin
    private val _ugoiraState = MutableStateFlow<UiState<UgoiraMetaData?>>(UiState.Loading)
    val ugoiraState: StateFlow<UiState<UgoiraMetaData?>> = _ugoiraState.asStateFlow()
```

Add a `loadUgoira` method:

```kotlin
    private fun loadUgoira(illustId: Long) {
        screenModelScope.launch {
            _ugoiraState.value = UiState.Loading
            try {
                val resp = client.appApi.getUgoiraMetadata(illustId)
                _ugoiraState.value = UiState.Success(resp.ugoira_metadata)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ugoiraState.value = UiState.Error(e.message ?: "Failed to load ugoira metadata")
            }
        }
    }
```

In `loadIllust()`, after `UiState.Success(it)`, add:

```kotlin
                _illustState.value = resp.illust?.let { illust ->
                    if (illust.isGif()) loadUgoira(illust.id)
                    UiState.Success(illust)
                } ?: UiState.Error("Illust not found")
```

- [ ] **Step 2: Show UgoiraPlayer in `IllustDetailScreen.kt` for ugoira type**

Add import:

```kotlin
import ceui.pixiv.ui.component.UgoiraPlayer
```

In `IllustDetailContent`, modify the image gallery section:

```kotlin
        // Image gallery
        item {
            if (illust.isGif()) {
                // Ugoira: animated player
                val ugoiraState by screenModel.ugoiraState.collectAsState()
                // Note: screenModel is not available here — pass ugoiraState as parameter
            }
        }
```

Wait — `IllustDetailContent` is a private composable that doesn't have access to `screenModel`. I need to pass the ugoira state. Update `IllustDetailContent` signature:

```kotlin
@Composable
private fun IllustDetailContent(
    illust: Illust,
    relatedState: UiState<List<Illust>>,
    onIllustClick: (Long) -> Unit,
    onTagClick: (String) -> Unit,
    ugoiraState: UiState<ceui.loxia.UgoiraMetaData?> = UiState.Loading
) {
```

In `Content()`, pass `ugoiraState`:

```kotlin
                    is UiState.Success -> IllustDetailContent(
                        illust = s.data,
                        relatedState = relatedState,
                        onIllustClick = { id -> navigator.push(IllustDetailScreen(id)) },
                        onTagClick = { tag -> navigator.push(SearchScreen(initialQuery = tag)) },
                        ugoiraState = ugoiraState
                    )
```

And in `Content()`, collect `ugoiraState`:

```kotlin
        val ugoiraState by screenModel.ugoiraState.collectAsState()
```

In `IllustDetailContent`, replace the image gallery `item`:

```kotlin
        // Image gallery
        item {
            if (illust.isGif()) {
                // Ugoira: animated player
                when (val u = ugoiraState) {
                    is UiState.Loading -> LoadingView()
                    is UiState.Error -> ErrorView(u.message, {})
                    is UiState.Success -> {
                        val meta = u.data
                        if (meta != null) {
                            val zipUrl = meta.zip_urls?.medium
                            val frames = meta.frames ?: emptyList()
                            if (zipUrl != null && frames.isNotEmpty()) {
                                UgoiraPlayer(
                                    zipUrl = zipUrl,
                                    frames = frames
                                )
                            } else {
                                Text("Ugoira metadata incomplete")
                            }
                        } else {
                            Text("No ugoira metadata")
                        }
                    }
                }
            } else if (imageUrls.size > 1) {
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
```

- [ ] **Step 3: Compile**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(app/detail): IllustDetailScreen ugoira 动图播放集成"
```

---

## Task 4: Gate

**Files:**
- Modify: `app/src/main/kotlin/ceui/pixiv/Main.kt`

- [ ] **Step 1: Update gate message**

```kotlin
println("PLAN 6 GATE PASSED — Ugoira animation integrated")
```

- [ ] **Step 2: Compile + run gate**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:run --no-daemon &
RUN_PID=$!
sleep 20
kill $RUN_PID 2>/dev/null
wait $RUN_PID 2>/dev/null
```

Expected: `PLAN 6 GATE PASSED — Ugoira animation integrated` printed, no crashes.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(app): Plan 6 闸门 — Ugoira animation"
```

---

## Self-Review

### Spec Coverage
- ✅ Ugoira display (design §5.2): Task 2-3 — download zip, unzip, frame animation, Skia decode
- ✅ Anti-GFW for ugoira zip (design §5.2): Task 1 — expose anti-GFW image client
- ⚠️ Deferred: GIF export (AnimatedGifEncoder port, 1291 lines Java) — future plan
- ⚠️ Deferred: Coil custom Decoder integration (design says Coil Decoder, but manual UgoiraPlayer is simpler for first version)

### Placeholder Scan
- No "TBD" or "TODO". Code blocks for all steps. Notes for Skia import fallback.

### Type Consistency
- `API.getUgoiraMetadata(illust_id: Long): GifInfoResponse` — matches existing model.
- `GifInfoResponse.ugoira_metadata: UgoiraMetaData?` — has `zip_urls: ZipUrl?` + `frames: List<GifFrame>?`.
- `UgoiraPlayer(zipUrl: String, frames: List<GifFrame>)` — takes zip URL + frame metadata.
- `IllustDetailContent` gains `ugoiraState: UiState<UgoiraMetaData?>` param.
- `Illust.isGif()` — existing method, `type == "ugoira"`.

### Risks
1. **Skia availability**: `org.jetbrains.skia.Image.makeFromEncoded` should be available via `compose.desktop.currentOs`. If not, try `org.jetbrains.skia.Image` import.
2. **Zip download via anti-GFW client**: The zip URL is `https://i.pximg.net/...`. The anti-GFW image client (no-SNI TLS + HttpDns) should handle it. If the download fails, the UgoiraPlayer shows nothing (graceful degradation).
3. **Frame animation smoothness**: Manual `LaunchedEffect` + `delay` is not as smooth as a native animated image decoder. For the first version, this is acceptable.
4. **Memory**: All frames are decoded into `ImageBitmap` and held in memory. For large ugoira (100+ frames), this could use significant memory. For the first version, this is acceptable — future optimization: use a frame cache with limited size.
