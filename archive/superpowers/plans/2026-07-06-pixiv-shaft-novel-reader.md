# Pixiv-Shaft macOS — Novel Reader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the Pixiv novel markup parser (ContentParser + InlineMarkup) to Desktop, render novel text as Compose `LazyColumn` with paragraphs/chapters/images/jumps, and create NovelDetail + NovelReader screens.

**Architecture:** Port `ContentParser.kt` + `InlineMarkup.kt` + `ContentToken.kt` (pure Kotlin, drop `WebNovel` dependency, take `String` param). `NovelContent` composable renders `List<ContentToken>` as `LazyColumn` items: paragraphs as `Text`/`AnnotatedString`, chapters as headings, images as `AsyncImage`, jumps as clickable buttons, page breaks as dividers. `NovelReaderScreen` fetches `getNovelText(id)` → Gson deserialize → `ContentParser.tokenize()` → `NovelContent`. `NovelDetailScreen` shows novel info + "Read" button.

**Tech Stack:** Compose Multiplatform 1.7.3, Voyager 1.0.1, Coil 3, Gson, existing `API.getNovelText()` + `API.getNovel()`.

## Global Constraints

- macOS aarch64, `JAVA_HOME=/opt/homebrew/opt/openjdk@21` for every `./gradlew`.
- Aliyun mirrors in all `repositories{}`.
- `:app` uses `kotlin("jvm")` — source dir is `src/main/kotlin/`.
- Voyager 1.0.1: `Screen.Content()` (capital C), `rememberScreenModel`, `screenModelScope`.
- `API.getNovelText(id: Long): ResponseBody` — returns JSON body; deserialize with `Gson().fromJson(body.string(), NovelText::class.java)`.
- `API.getNovel(novel_id: Long): SingleNovelResponse` — returns `Novel` with title, user, caption, tags.
- `NovelText.text: String?` — the raw novel markup (contains `[newpage]`, `[chapter:]`, `[[rb:]]`, `[[jumpuri:]]`, `[jump:N]`, `[pixivimage:]` tags).
- `Novel` model at `ceui.loxia.Novel` — has `title`, `user`, `caption`, `tags`, `text_length`, `total_bookmarks`, `total_view`.
- Source parser files are at `/Users/he/workspace/git_repo/Pixiv-Shaft/app/src/main/java/ceui/pixiv/ui/novel/reader/` — port from there.
- `ContentParser.kt` has a `tokenize(text: String)` method (no `WebNovel` dependency needed) — port this method and drop the `WebNovel` overload.
- `InlineMarkup.kt` is 100% pure Kotlin, no Android deps — port verbatim.
- `ContentToken.kt` is pure Kotlin — port verbatim.
- Skip `PageElement.kt`, `Page.kt`, `Paginator.kt` (Android-dependent text measurement, not needed for scroll-based rendering).
- Ruby (`[[rb:base>ruby]]`) rendering: the parser strips `[[rb:]]` and records the span. For v1, render base text only (ruby text is in `InlineSpan.tag.rubyText` but not displayed). True ruby (small text above characters) deferred to future custom `Layout`.
- Link (`[[jumpuri:display>url]]`) rendering: `AnnotatedString` with underline + clickable.
- CancellationException must be rethrown.
- `getNovelText` and `getNovel` require auth — will show error if not logged in. Expected.

---

## File Structure

| File | Responsibility |
|------|---------------|
| `app/src/main/kotlin/ceui/pixiv/ui/novel/ContentToken.kt` | Ported sealed class (paragraph/chapter/image/pagebreak/jump) |
| `app/src/main/kotlin/ceui/pixiv/ui/novel/InlineMarkup.kt` | Ported inline tag parser (Link, Ruby, InlineSpan) |
| `app/src/main/kotlin/ceui/pixiv/ui/novel/ContentParser.kt` | Ported tokenizer (text → List<ContentToken>) |
| `app/src/main/kotlin/ceui/pixiv/ui/novel/NovelContent.kt` | Composable rendering List<ContentToken> as LazyColumn |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/novel/NovelDetailScreen.kt` | Novel info + "Read" button |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/novel/NovelDetailScreenModel.kt` | Fetch getNovel() |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/novel/NovelReaderScreen.kt` | Full-screen reader |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/novel/NovelReaderScreenModel.kt` | Fetch getNovelText() + parse |

---

## Task 1: Port Parser (ContentParser + InlineMarkup + ContentToken)

**Files:**
- Create: `app/src/main/kotlin/ceui/pixiv/ui/novel/ContentToken.kt`
- Create: `app/src/main/kotlin/ceui/pixiv/ui/novel/InlineMarkup.kt`
- Create: `app/src/main/kotlin/ceui/pixiv/ui/novel/ContentParser.kt`

**Interfaces:**
- Consumes: source files at `/Users/he/workspace/git_repo/Pixiv-Shaft/app/src/main/java/ceui/pixiv/ui/novel/reader/`.
- Produces: `ContentToken` sealed class, `InlineMarkupProcessor.process(raw: String): Result`, `ContentParser.tokenize(text: String): List<ContentToken>`.

- [ ] **Step 1: Port `ContentToken.kt`**

Copy from `/Users/he/workspace/git_repo/Pixiv-Shaft/app/src/main/java/ceui/pixiv/ui/novel/reader/model/ContentToken.kt`.

Change the package to `ceui.pixiv.ui.novel` (drop `reader.model`). Fix the `InlineSpan` import to `ceui.pixiv.ui.novel.InlineSpan` (will be in `InlineMarkup.kt`).

The file is 58 lines, pure Kotlin. Port verbatim except:
- Package: `package ceui.pixiv.ui.novel`
- The `inlineSpans` field in `Paragraph` references `ceui.pixiv.ui.novel.reader.paginate.InlineSpan` — change to `ceui.pixiv.ui.novel.InlineSpan`.

- [ ] **Step 2: Port `InlineMarkup.kt`**

Copy from `/Users/he/workspace/git_repo/Pixiv-Shaft/app/src/main/java/ceui/pixiv/ui/novel/reader/paginate/InlineMarkup.kt`.

Change the package to `ceui.pixiv.ui.novel`. The file is 106 lines, 100% pure Kotlin, no Android deps. Port verbatim except the package line.

- [ ] **Step 3: Port `ContentParser.kt`**

Copy from `/Users/he/workspace/git_repo/Pixiv-Shaft/app/src/main/java/ceui/pixiv/ui/novel/reader/paginate/ContentParser.kt`.

Changes:
- Package: `package ceui.pixiv.ui.novel`
- Drop the `import ceui.loxia.WebNovel` line.
- Drop the `fun tokenize(webNovel: WebNovel): List<ContentToken>` method (keep only `fun tokenize(text: String): List<ContentToken>`).
- Fix `ContentToken` and `InlineMarkupProcessor` references (same package now, no import needed).
- The `ChapterOutlineEntry` data class at the bottom — port it too (same package).
- The file is 300 lines. Port everything else verbatim (all regex, tokenization, coalesce, chapter outline, jump resolution).

- [ ] **Step 4: Compile**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(app/novel): 搬运 ContentParser + InlineMarkup + ContentToken（纯 Kotlin 解析器）"
```

---

## Task 2: NovelContent Composable

**Files:**
- Create: `app/src/main/kotlin/ceui/pixiv/ui/novel/NovelContent.kt`

**Interfaces:**
- Consumes: `ContentToken` list, `InlineSpan`/`InlineTag` for link styling, Coil `AsyncImage`, Voyager `LocalNavigator`.
- Produces: `NovelContent(tokens: List<ContentToken>, modifier: Modifier)` composable.

- [ ] **Step 1: Create `NovelContent.kt`**

```kotlin
package ceui.pixiv.ui.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import ceui.pixiv.ui.state.UiState

@Composable
fun NovelContent(
    tokens: List<ContentToken>,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 24.dp,
            vertical = 32.dp
        ),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
    ) {
        items(tokens) { token ->
            when (token) {
                is ContentToken.Paragraph -> ParagraphItem(token)
                is ContentToken.Chapter -> ChapterItem(token)
                is ContentToken.PixivImage -> PixivImageItem(token)
                is ContentToken.UploadedImage -> UploadedImageItem(token)
                is ContentToken.PageBreak -> PageBreakItem()
                is ContentToken.BlankLine -> Box(modifier = Modifier.height(8.dp))
                is ContentToken.Jump -> JumpItem(token)
            }
        }
    }
}

@Composable
private fun ParagraphItem(token: ContentToken.Paragraph) {
    val annotated = buildAnnotatedString {
        append(token.text)
        for (span in token.inlineSpans) {
            when (span.tag) {
                is InlineTag.Link -> {
                    addStyle(
                        SpanStyle(
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline
                        ),
                        span.start,
                        span.end
                    )
                }
                is InlineTag.Ruby -> {
                    // Ruby text not rendered vertically (v1) — just mark with subtle style
                    addStyle(
                        SpanStyle(fontSize = 11.sp),
                        span.start,
                        span.end
                    )
                }
            }
        }
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge.copy(
            lineHeight = 28.sp
        )
    )
}

@Composable
private fun ChapterItem(token: ContentToken.Chapter) {
    Text(
        text = token.title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(vertical = 16.dp)
    )
}

@Composable
private fun PixivImageItem(token: ContentToken.PixivImage) {
    // Pixiv image placeholder — would need illust lookup to get URL
    // For v1, show a placeholder text
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            text = "[pixivimage:${token.illustId}-${token.pageIndex}]",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(8.dp)
        )
    }
}

@Composable
private fun UploadedImageItem(token: ContentToken.UploadedImage) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            text = "[uploadedimage:${token.imageId}]",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(8.dp)
        )
    }
}

@Composable
private fun PageBreakItem() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 24.dp),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun JumpItem(token: ContentToken.Jump) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable { /* TODO: jump to target page */ }
    ) {
        Text(
            text = "→ Jump to page ${token.target}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(8.dp)
        )
    }
}
```

Note: `ParagraphItem` builds an `AnnotatedString` from the paragraph text + inline spans. Links get underline + primary color. Ruby spans get smaller font (v1 simplification — true vertical ruby deferred). `PixivImageItem` and `UploadedImageItem` show placeholders (resolving image URLs requires illust lookup, deferred). `PageBreakItem` shows a divider. `JumpItem` shows a clickable button (jump navigation deferred).

- [ ] **Step 2: Compile**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(app/novel): NovelContent composable（LazyColumn 渲染段落/章节/图片/跳转）"
```

---

## Task 3: NovelDetailScreen + NovelReaderScreen

**Files:**
- Create: `app/src/main/kotlin/ceui/pixiv/ui/screen/novel/NovelDetailScreenModel.kt`
- Create: `app/src/main/kotlin/ceui/pixiv/ui/screen/novel/NovelDetailScreen.kt`
- Create: `app/src/main/kotlin/ceui/pixiv/ui/screen/novel/NovelReaderScreenModel.kt`
- Create: `app/src/main/kotlin/ceui/pixiv/ui/screen/novel/NovelReaderScreen.kt`

**Interfaces:**
- Consumes: `API.getNovel(novel_id)`, `API.getNovelText(id)`, `ContentParser.tokenize()`, `NovelContent`, Voyager `Screen` + `ScreenModel`, Gson.
- Produces: `NovelDetailScreen(novelId: Long)` — shows novel info + "Read" button; `NovelReaderScreen(novelId: Long)` — full-screen reader.

- [ ] **Step 1: Create `NovelDetailScreenModel.kt`**

```kotlin
package ceui.pixiv.ui.screen.novel

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.Novel
import ceui.loxia.SingleNovelResponse
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class NovelDetailScreenModel(
    private val novelId: Long
) : ScreenModel {

    private val client = AppContainer.client

    private val _state = MutableStateFlow<UiState<Novel>>(UiState.Loading)
    val state: StateFlow<UiState<Novel>> = _state.asStateFlow()

    init {
        loadNovel()
    }

    private fun loadNovel() {
        screenModelScope.launch {
            _state.value = UiState.Loading
            try {
                val resp = client.appApi.getNovel(novelId)
                _state.value = resp.novel?.let { UiState.Success(it) }
                    ?: UiState.Error("Novel not found")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Failed to load novel")
            }
        }
    }
}
```

- [ ] **Step 2: Create `NovelDetailScreen.kt`**

```kotlin
package ceui.pixiv.ui.screen.novel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.component.TagChip
import ceui.pixiv.ui.component.UserAvatar
import ceui.pixiv.ui.state.UiState
import coil3.compose.AsyncImage

class NovelDetailScreen(private val novelId: Long) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { NovelDetailScreenModel(novelId) }
        val state by screenModel.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Novel") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (val s = state) {
                    is UiState.Loading -> LoadingView()
                    is UiState.Error -> ErrorView(s.message, {})
                    is UiState.Success -> {
                        val novel = s.data
                        // Cover image
                        novel.image_urls?.large?.let { url ->
                            AsyncImage(
                                model = url,
                                contentDescription = novel.title,
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.Fit
                            )
                        }
                        // Title
                        Text(
                            text = novel.title ?: "Untitled",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        // Author
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            UserAvatar(url = novel.user?.profile_image_urls?.px_50x50)
                            Text(
                                text = novel.user?.name ?: "",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        // Stats
                        Text(
                            text = "${novel.text_length ?: 0} chars  ♥ ${novel.total_bookmarks ?: 0}  👁 ${novel.total_view ?: 0}",
                            style = MaterialTheme.typography.labelMedium
                        )
                        // Caption
                        novel.caption?.let { caption ->
                            Text(
                                text = caption,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        // Tags
                        novel.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(tags) { tag ->
                                    TagChip(tag = tag, onClick = {})
                                }
                            }
                        }
                        // Read button
                        Button(
                            onClick = { navigator.push(NovelReaderScreen(novelId)) },
                            modifier = Modifier.padding(top = 16.dp)
                        ) {
                            Text("Read")
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Create `NovelReaderScreenModel.kt`**

```kotlin
package ceui.pixiv.ui.screen.novel

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.NovelText
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.novel.ContentParser
import ceui.pixiv.ui.novel.ContentToken
import ceui.pixiv.ui.state.UiState
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

class NovelReaderScreenModel(
    private val novelId: Long
) : ScreenModel {

    private val client = AppContainer.client

    private val _state = MutableStateFlow<UiState<List<ContentToken>>>(UiState.Loading)
    val state: StateFlow<UiState<List<ContentToken>>> = _state.asStateFlow()

    init {
        loadNovelText()
    }

    private fun loadNovelText() {
        screenModelScope.launch {
            _state.value = UiState.Loading
            try {
                val tokens = withContext(Dispatchers.IO) {
                    val body = client.appApi.getNovelText(novelId)
                    val json = body.string()
                    val novelText = Gson().fromJson(json, NovelText::class.java)
                    ContentParser.tokenize(novelText.text.orEmpty())
                }
                _state.value = UiState.Success(tokens)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Failed to load novel text")
            }
        }
    }
}
```

- [ ] **Step 4: Create `NovelReaderScreen.kt`**

```kotlin
package ceui.pixiv.ui.screen.novel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.novel.NovelContent
import ceui.pixiv.ui.state.UiState

class NovelReaderScreen(private val novelId: Long) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { NovelReaderScreenModel(novelId) }
        val state by screenModel.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Novel Reader") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (val s = state) {
                    is UiState.Loading -> LoadingView()
                    is UiState.Error -> ErrorView(s.message, {})
                    is UiState.Success -> {
                        NovelContent(tokens = s.data)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 5: Compile**

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(app/novel): NovelDetailScreen + NovelReaderScreen（小说详情 + 阅读器）"
```

---

## Task 4: Gate

**Files:**
- Modify: `app/src/main/kotlin/ceui/pixiv/Main.kt`

- [ ] **Step 1: Update gate message**

```kotlin
println("PLAN 7 GATE PASSED — Novel reader integrated")
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

Expected: `PLAN 7 GATE PASSED — Novel reader integrated` printed, no crashes.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(app): Plan 7 闸门 — Novel reader"
```

---

## Self-Review

### Spec Coverage
- ✅ Parser porting (design §4.3): Task 1 — ContentParser + InlineMarkup + ContentToken, pure Kotlin
- ✅ Compose rendering (design §4.3): Task 2 — NovelContent composable, LazyColumn rendering
- ✅ Novel detail + reader (design §4.2): Task 3 — NovelDetailScreen + NovelReaderScreen
- ⚠️ Deferred: True ruby rendering (vertical small text above characters) — v1 uses inline smaller font
- ⚠️ Deferred: Pagination (HorizontalPager with [newpage] splitting) — v1 uses scroll
- ⚠️ Deferred: Flip animations (None/Slide/Cover/Simulation) — v1 scroll only
- ⚠️ Deferred: Bookmarks/annotations/custom themes/custom fonts — future plans
- ⚠️ Deferred: Image resolution in novels (pixivimage/uploadedimage) — v1 shows placeholder
- ⚠️ Deferred: Jump navigation ([jump:N]) — v1 shows button but no navigation

### Placeholder Scan
- No "TBD" or "TODO" in implementation code (the JumpItem has a `// TODO` comment for navigation — this is a deferred feature, not a plan placeholder).
- Code blocks provided for all steps.

### Type Consistency
- `ContentParser.tokenize(text: String): List<ContentToken>` — matches `NovelReaderScreenModel` usage.
- `NovelContent(tokens: List<ContentToken>, modifier: Modifier)` — matches `NovelReaderScreen` usage.
- `NovelDetailScreen(novelId: Long)` and `NovelReaderScreen(novelId: Long)` — constructor params match.
- `NovelText.text: String?` — passed to `ContentParser.tokenize()` via `.orEmpty()`.
- `InlineTag.Link` / `InlineTag.Ruby` — used in `ParagraphItem` for `AnnotatedString` styling.
- CancellationException rethrown in both ScreenModels.

### Risks
1. **Ruby rendering**: v1 renders ruby base text with smaller font (not vertical). True ruby requires custom `Layout` composable — deferred.
2. **Novel text API**: `getNovelText(id)` returns `ResponseBody` — deserialized as `NovelText` via Gson. If the API response structure differs from `NovelText` model, deserialization may fail. The source app uses `WebNovel` (different field names) — `NovelText` uses `text` field which should match.
3. **Auth required**: `getNovelText` and `getNovel` require auth. Without a token, `UiState.Error` is shown. Expected.
4. **Large novel text**: Very long novels (100k+ chars) may cause `LazyColumn` performance issues. For v1, this is acceptable — all tokens are in memory.
5. **Image resolution**: `[pixivimage:12345]` in novel text requires looking up the illust to get the image URL. v1 shows a placeholder. Future: fetch illust metadata and resolve URLs.
