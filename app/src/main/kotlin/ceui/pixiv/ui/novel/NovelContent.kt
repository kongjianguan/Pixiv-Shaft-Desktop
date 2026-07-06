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
import androidx.compose.ui.text.style.TextDecoration
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
