package ceui.pixiv.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ceui.loxia.Novel
import ceui.pixiv.di.AppContainer
import coil3.compose.AsyncImage
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** Shaft-style, single-column novel card shared by recommendation and series pages. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun NovelCard(
    novel: Novel,
    onClick: (Long) -> Unit,
    onUserClick: (Long) -> Unit,
    onSeriesClick: ((Long) -> Unit)? = null,
    onToggleBookmark: (Novel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val titleMaxLines by AppContainer.settingsStore.novelTitleMaxLinesFlow.collectAsState()
    Card(modifier = modifier.fillMaxWidth().clickable { onClick(novel.id) }, shape = RoundedCornerShape(10.dp)) {
        Row(modifier = Modifier.padding(10.dp)) {
            AsyncImage(
                model = novel.image_urls?.medium ?: novel.image_urls?.large,
                contentDescription = novel.title,
                modifier = Modifier.width(108.dp).aspectRatio(240f / 338f).clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = novel.title ?: "Untitled",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = titleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (novel.novel_ai_type == 2) {
                        Text(
                            text = "AI",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
                val series = novel.series
                series?.title?.takeIf { it.isNotBlank() }?.let { title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 5.dp).then(
                            if (onSeriesClick != null) Modifier.clickable { onSeriesClick(series.id) } else Modifier
                        )
                    )
                }
                novel.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 7.dp),
                    ) {
                        tags.take(6).forEach { tag ->
                            Text(
                                text = "# ${tag.tagName.orEmpty()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    val userId = novel.user?.id ?: 0L
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f).then(
                            if (userId > 0) Modifier.clickable { onUserClick(userId) } else Modifier
                        )
                    ) {
                        UserAvatar(
                            url = novel.user?.profile_image_urls?.medium
                                ?: novel.user?.profile_image_urls?.px_170x170
                                ?: novel.user?.profile_image_urls?.px_50x50,
                            size = 30,
                        )
                        Text(
                            text = novel.user?.name.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                    IconButton(onClick = { onToggleBookmark(novel) }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = if (novel.is_bookmarked == true) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = "Bookmark novel",
                            tint = if (novel.is_bookmarked == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = "${novel.create_date.toRelativeNovelTime()}  ·  ${novel.text_length ?: 0} 字  ·  ♥ ${novel.total_bookmarks ?: 0}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

private fun String?.toRelativeNovelTime(): String {
    val publishedAt = try { this?.let(OffsetDateTime::parse)?.toInstant() } catch (_: Exception) { null }
        ?: return this?.substringBefore('T').orEmpty()
    val elapsed = Duration.between(publishedAt, Instant.now()).coerceAtLeast(Duration.ZERO)
    return when {
        elapsed.toMinutes() < 1 -> "刚刚"
        elapsed.toHours() < 1 -> "${elapsed.toMinutes()} 分钟前"
        elapsed.toDays() < 1 -> "${elapsed.toDays()} 天前"
        else -> this?.substringBefore('T').orEmpty()
    }
}
