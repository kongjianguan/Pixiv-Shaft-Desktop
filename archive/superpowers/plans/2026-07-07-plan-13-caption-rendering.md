# Plan 13: 作品描述渲染

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 IllustDetailScreen 渲染 Pixiv 作品的 HTML caption（描述文本）。

**Architecture:** 新建 `CaptionText` 组件，内含 `parseHtml(String): AnnotatedString` 轻量解析器。支持 `<br>`/`<a>`/`<b>`/`<i>`/`<s>`/`<u>` + HTML 实体。不引入 Jsoup 依赖。

**Tech Stack:** Compose Multiplatform 1.7.3, `AnnotatedString`, `LinkAnnotation`.

## Global Constraints

- macOS aarch64, `JAVA_HOME=/opt/homebrew/opt/openjdk@21`
- No tests for UI composables (visual verification only)
- 不引入 Jsoup 或其他 HTML 解析库依赖

---

## File Structure

| File | Responsibility |
|------|---------------|
| `app/src/main/kotlin/ceui/pixiv/ui/component/CaptionText.kt` | HTML→AnnotatedString 解析 + CaptionText composable |
| `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreen.kt` | 在 item 4 位置插入 caption |

---

## Task 1: Create CaptionText component

**Files:**
- Create: `app/src/main/kotlin/ceui/pixiv/ui/component/CaptionText.kt`

- [ ] **Step 1: Create CaptionText.kt with parseHtml + composable**

```kotlin
package ceui.pixiv.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withAnnotation

@Composable
fun CaptionText(
    html: String?,
    modifier: Modifier = Modifier
) {
    if (html.isNullOrBlank()) return
    val annotated = remember(html) { parseHtml(html) }
    Text(
        text = annotated,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall
    )
}

fun parseHtml(html: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val tagStack = ArrayDeque<SpanStyle>()

    while (i < html.length) {
        when {
            html.startsWith("<br", i, ignoreCase = true) -> {
                append('\n')
                i = html.indexOf('>', i) + 1
                if (i == 0) break
            }
            html.startsWith("<b>", i, ignoreCase = true) || html.startsWith("<strong>", i, ignoreCase = true) -> {
                tagStack.addLast(SpanStyle(fontWeight = FontWeight.Bold))
                i = html.indexOf('>', i) + 1
                if (i == 0) break
                pushStyle(tagStack.last())
            }
            html.startsWith("<i>", i, ignoreCase = true) || html.startsWith("<em>", i, ignoreCase = true) -> {
                tagStack.addLast(SpanStyle(fontStyle = FontStyle.Italic))
                i = html.indexOf('>', i) + 1
                if (i == 0) break
                pushStyle(tagStack.last())
            }
            html.startsWith("<s>", i, ignoreCase = true) || html.startsWith("<del>", i, ignoreCase = true) -> {
                tagStack.addLast(SpanStyle(textDecoration = TextDecoration.LineThrough))
                i = html.indexOf('>', i) + 1
                if (i == 0) break
                pushStyle(tagStack.last())
            }
            html.startsWith("<u>", i, ignoreCase = true) -> {
                tagStack.addLast(SpanStyle(textDecoration = TextDecoration.Underline))
                i = html.indexOf('>', i) + 1
                if (i == 0) break
                pushStyle(tagStack.last())
            }
            html.startsWith("</b>", i, ignoreCase = true) || html.startsWith("</strong>", i, ignoreCase = true) ||
            html.startsWith("</i>", i, ignoreCase = true) || html.startsWith("</em>", i, ignoreCase = true) ||
            html.startsWith("</s>", i, ignoreCase = true) || html.startsWith("</del>", i, ignoreCase = true) ||
            html.startsWith("</u>", i, ignoreCase = true) -> {
                if (tagStack.isNotEmpty()) {
                    tagStack.removeLast()
                    pop()
                }
                i = html.indexOf('>', i) + 1
                if (i == 0) break
            }
            html.startsWith("<a ", i, ignoreCase = true) -> {
                // Extract href
                val hrefStart = html.indexOf("href=\"", i)
                val hrefEnd = html.indexOf("\"", hrefStart + 6)
                if (hrefStart >= 0 && hrefEnd > hrefStart) {
                    val href = html.substring(hrefStart + 6, hrefEnd)
                    val closeTag = html.indexOf('>', i) + 1
                    if (closeTag == 0) break
                    i = closeTag
                    // push link style + annotation
                    withAnnotation(LinkAnnotation.Url(href)) {
                        withStyle(SpanStyle(color = Color(0xFF2196F3), textDecoration = TextDecoration.Underline)) {
                            // append text until </a>
                            val endA = html.indexOf("</a>", i, ignoreCase = true)
                            if (endA > i) {
                                append(decodeEntities(html.substring(i, endA)))
                                i = endA + 4
                            } else {
                                // no closing tag, just append rest
                                break
                            }
                        }
                    }
                } else {
                    // no href, skip tag
                    i = html.indexOf('>', i) + 1
                    if (i == 0) break
                }
            }
            html.startsWith("&", i) -> {
                val semi = html.indexOf(';', i)
                if (semi > i && semi - i < 10) {
                    val entity = html.substring(i, semi + 1)
                    append(decodeEntity(entity))
                    i = semi + 1
                } else {
                    append(html[i])
                    i++
                }
            }
            html[i] == '<' -> {
                // Unknown tag, skip to >
                val close = html.indexOf('>', i)
                if (close >= 0) {
                    i = close + 1
                } else {
                    append(html[i])
                    i++
                }
            }
            else -> {
                append(html[i])
                i++
            }
        }
    }
}

private fun decodeEntity(entity: String): Char = when (entity.lowercase()) {
    "&amp;" -> '&'
    "&lt;" -> '<'
    "&gt;" -> '>'
    "&quot;" -> '"'
    "&#39;" -> '\''
    "&nbsp;" -> ' '
    else -> ' '
}

private fun decodeEntities(text: String): String {
    var result = text
    for (e in listOf("&amp;", "&lt;", "&gt;", "&quot;", "&#39;", "&nbsp;")) {
        result = result.replace(e, decodeEntity(e).toString(), ignoreCase = true)
    }
    return result
}
```

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "feat: CaptionText 组件 — HTML caption 解析渲染"
```

---

## Task 2: Integrate caption into IllustDetailScreen

**Files:**
- Modify: `app/src/main/kotlin/ceui/pixiv/ui/screen/detail/IllustDetailScreen.kt`

- [ ] **Step 1: Add caption item between title+author and tags**

In `IllustDetailContent`'s `LazyColumn`, after the title+author item (item 3 in layout), add:

```kotlin
// Caption (item 4 in layout)
item {
    CaptionText(
        html = illust.caption,
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}
```

Add import:
```kotlin
import ceui.pixiv.ui.component.CaptionText
```

- [ ] **Step 2: Compile**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:compileKotlin --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Manual verification**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :app:run --no-daemon`
Open an illust with a description → verify caption renders below title, above tags. HTML tags (bold, links, line breaks) render correctly. Empty caption shows nothing.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: 详情页加作品描述渲染"
```

---

## GATE

Verify: illust with `<br>`, `<a>`, `<b>` in caption renders correctly. Empty caption = no extra space.
