package ceui.pixiv.ui.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import ceui.loxia.WebNovel
import ceui.pixiv.util.openInBrowser
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun NovelContent(
    tokens: List<ContentToken>,
    webNovel: WebNovel? = null,
    style: NovelReaderStyle,
    targetSource: Int? = null,
    scrollCommand: Int = 0,
    onTargetConsumed: () -> Unit = {},
    onProgressChanged: (Float) -> Unit = {},
    onScrollDirectionChanged: (scrollingDown: Boolean) -> Unit = {},
    topContentPadding: androidx.compose.ui.unit.Dp = 72.dp,
    bottomContentPadding: androidx.compose.ui.unit.Dp = 92.dp,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(targetSource, tokens) {
        val source = targetSource ?: return@LaunchedEffect
        val targetIndex = tokenIndexAtOrAfter(tokens, source)
        if (tokens.isNotEmpty()) listState.animateScrollToItem(targetIndex)
        onTargetConsumed()
    }

    LaunchedEffect(scrollCommand, tokens) {
        if (scrollCommand == 0 || tokens.isEmpty()) return@LaunchedEffect
        val visibleCount = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
        val delta = (visibleCount * 0.8f).roundToInt().coerceAtLeast(1)
        val target = (listState.firstVisibleItemIndex + delta * scrollCommand)
            .coerceIn(0, tokens.lastIndex)
        listState.animateScrollToItem(target)
    }

    LaunchedEffect(listState, tokens) {
        var previous = Triple(-1, -1, 0)
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.layoutInfo.totalItemsCount,
            )
        }.distinctUntilChanged().collect { current ->
            val totalItems = current.third.coerceAtLeast(tokens.size)
            val denominator = (totalItems - 1).coerceAtLeast(1)
            onProgressChanged((current.first.toFloat() / denominator).coerceIn(0f, 1f))

            if (previous.first >= 0 && current != previous) {
                val scrollingDown = current.first > previous.first ||
                    (current.first == previous.first && current.second > previous.second)
                onScrollDirectionChanged(scrollingDown)
            }
            previous = current
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = style.maxContentWidth)
                .align(Alignment.Center),
            state = listState,
            contentPadding = PaddingValues(
                start = 32.dp,
                top = topContentPadding,
                end = 32.dp,
                bottom = bottomContentPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(style.paragraphSpacing),
        ) {
            itemsIndexed(
                items = tokens,
                key = { index, token -> "${token.sourceStart}:$index" },
            ) { _, token ->
                when (token) {
                    is ContentToken.Paragraph -> ParagraphItem(token, style)
                    is ContentToken.Chapter -> ChapterItem(token, style)
                    is ContentToken.PixivImage -> ImageItem(token, webNovel, style)
                    is ContentToken.UploadedImage -> ImageItem(token, webNovel, style)
                    is ContentToken.PageBreak -> PageBreakItem(style)
                    is ContentToken.BlankLine -> Box(modifier = Modifier.height(8.dp))
                    is ContentToken.Jump -> JumpItem(token, style) {
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
}

private fun tokenIndexAtOrAfter(tokens: List<ContentToken>, source: Int): Int {
    if (tokens.isEmpty()) return 0
    return tokens.indexOfFirst { it.sourceStart >= source }
        .takeIf { it >= 0 }
        ?: tokens.lastIndex
}

@Composable
private fun ParagraphItem(token: ContentToken.Paragraph, style: NovelReaderStyle) {
    val annotated = buildAnnotatedString {
        append(token.text)
        for (span in token.inlineSpans) {
            when (span.tag) {
                is InlineTag.Link -> {
                    addStringAnnotation("URL", span.tag.url, span.start, span.end)
                    addStyle(
                        SpanStyle(
                            color = style.accent,
                            textDecoration = TextDecoration.Underline,
                        ),
                        span.start,
                        span.end,
                    )
                }
                is InlineTag.Ruby -> {
                    addStyle(
                        SpanStyle(fontSize = (style.fontSizeSp * 0.7f).sp),
                        span.start,
                        span.end,
                    )
                }
            }
        }
    }
    ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyLarge.copy(
            color = style.text,
            fontSize = style.fontSizeSp.sp,
            lineHeight = (style.fontSizeSp * style.lineSpacing).sp,
            textIndent = TextIndent(firstLine = (style.fontSizeSp * 2).sp),
        ),
        onClick = { offset ->
            annotated.getStringAnnotations("URL", offset, offset)
                .firstOrNull()?.item?.let(::openInBrowser)
        },
    )
}

@Composable
private fun ChapterItem(token: ContentToken.Chapter, style: NovelReaderStyle) {
    Text(
        text = token.title,
        style = MaterialTheme.typography.titleLarge.copy(
            color = style.accent,
            fontSize = (style.fontSizeSp + 5).sp,
        ),
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun ImageItem(token: ContentToken, webNovel: WebNovel?, style: NovelReaderStyle) {
    val url = webNovel?.let { NovelImageResolver.urlFor(it, token) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp, max = 520.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(style.divider),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = "小说插图",
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 520.dp),
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
                color = style.secondaryText,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun PageBreakItem(style: NovelReaderStyle) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = style.divider)
        Text(
            text = "分页",
            style = MaterialTheme.typography.labelSmall,
            color = style.secondaryText,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = style.divider)
    }
}

@Composable
private fun JumpItem(token: ContentToken.Jump, style: NovelReaderStyle, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(style.divider.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = "→ 跳转到第 ${token.target} 页",
            style = MaterialTheme.typography.bodyMedium,
            color = style.accent,
            modifier = Modifier.padding(10.dp),
        )
    }
}
