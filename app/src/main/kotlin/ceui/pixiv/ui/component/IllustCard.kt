package ceui.pixiv.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import ceui.loxia.Illust
import ceui.pixiv.di.AppContainer

private val placeholderColor = Color(0xFFE0E0E0)

@Composable
fun IllustCard(
    illust: Illust,
    onClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val titleMaxLines by AppContainer.settingsStore.workTitleMaxLinesFlow.collectAsState()
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick(illust.id) },
        shape = RoundedCornerShape(8.dp)
    ) {
        Column {
            val imageUrl = illust.image_urls?.medium ?: illust.image_urls?.large
            val aspectRatio = if (illust.width > 0 && illust.height > 0) {
                illust.width.toFloat() / illust.height.toFloat()
            } else {
                1f
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = illust.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(aspectRatio)
                        .clip(RoundedCornerShape(8.dp))
                        .background(placeholderColor, RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                val pageCount = maxOf(illust.page_count, illust.meta_pages?.size ?: 0)
                if (pageCount > 1) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "${pageCount}P",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = illust.title ?: "Untitled",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = illust.user?.name ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "\u2665 ${illust.total_bookmarks ?: 0}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}
