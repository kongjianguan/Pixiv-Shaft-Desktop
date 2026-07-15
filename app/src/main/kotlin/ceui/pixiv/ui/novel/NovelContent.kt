package ceui.pixiv.ui.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import ceui.loxia.WebNovel
import ceui.pixiv.util.openInBrowser
import kotlinx.coroutines.launch

@Composable
fun NovelContent(
    tokens: List<ContentToken>,
    webNovel: WebNovel? = null,
    targetSource: Int? = null,
    onTargetConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(targetSource, tokens) {
        val source = targetSource ?: return@LaunchedEffect
        val targetIndex = tokenIndexAtOrAfter(tokens, source)
        if (tokens.isNotEmpty()) listState.animateScrollToItem(targetIndex)
        onTargetConsumed()
    }
    LazyColumn(
        modifier = modifier,
        state = listState,
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
                is ContentToken.PixivImage -> ImageItem(token, webNovel)
                is ContentToken.UploadedImage -> ImageItem(token, webNovel)
                is ContentToken.PageBreak -> PageBreakItem()
                is ContentToken.BlankLine -> Box(modifier = Modifier.height(8.dp))
                is ContentToken.Jump -> JumpItem(token) {
                    ContentParser.resolveJumpTarget(tokens, token.target)?.let { source ->
                        scope.launch {
                            val targetIndex = tokenIndexAtOrAfter(tokens, source)
                            if (tokens.isNotEmpty()) listState.animateScrollToItem(targetIndex)
                        }
                    }
                }
            }
        }
    }
}

private fun tokenIndexAtOrAfter(tokens: List<ContentToken>, source: Int): Int {
    if (tokens.isEmpty()) return 0
    return tokens.indexOfFirst { it.sourceStart >= source }
        .takeIf { it >= 0 }
        ?: tokens.lastIndex
}

@Composable
private fun ParagraphItem(token: ContentToken.Paragraph) {
    val annotated = buildAnnotatedString {
        append(token.text)
        for (span in token.inlineSpans) {
            when (span.tag) {
                is InlineTag.Link -> {
                    addStringAnnotation("URL", span.tag.url, span.start, span.end)
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
    ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge.copy(
            lineHeight = 28.sp
        ),
        onClick = { offset ->
            annotated.getStringAnnotations("URL", offset, offset)
                .firstOrNull()?.item?.let(::openInBrowser)
        },
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
private fun ImageItem(token: ContentToken, webNovel: WebNovel?) {
    val url = webNovel?.let { NovelImageResolver.urlFor(it, token) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = "Novel image",
                modifier = Modifier.fillMaxWidth().height(320.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        } else {
            Text(
                text = when (token) {
                    is ContentToken.PixivImage -> "[pixivimage:${token.illustId}-${token.pageIndex}]"
                    is ContentToken.UploadedImage -> "[uploadedimage:${token.imageId}]"
                    else -> "[image]"
                },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(8.dp)
            )
        }
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
private fun JumpItem(token: ContentToken.Jump, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable(onClick = onClick)
    ) {
        Text(
            text = "→ Jump to page ${token.target}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(8.dp)
        )
    }
}
