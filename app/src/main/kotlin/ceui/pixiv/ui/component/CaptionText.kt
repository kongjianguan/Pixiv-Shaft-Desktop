package ceui.pixiv.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.UrlAnnotation
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

@OptIn(ExperimentalTextApi::class)
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
                    var aborted = false
                    // push link style + annotation
                    withAnnotation(UrlAnnotation(href)) {
                        withStyle(SpanStyle(color = Color(0xFF2196F3), textDecoration = TextDecoration.Underline)) {
                            // append text until </a>
                            val endA = html.indexOf("</a>", i, ignoreCase = true)
                            if (endA > i) {
                                append(decodeEntities(html.substring(i, endA)))
                                i = endA + 4
                            } else {
                                // no closing tag, signal abort
                                aborted = true
                            }
                        }
                    }
                    if (aborted) break
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
