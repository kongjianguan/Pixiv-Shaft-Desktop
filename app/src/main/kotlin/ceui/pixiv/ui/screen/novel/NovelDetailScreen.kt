package ceui.pixiv.ui.screen.novel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.loxia.Novel
import ceui.pixiv.ui.component.CaptionText
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.component.TagChip
import ceui.pixiv.ui.component.UserAvatar
import ceui.pixiv.ui.screen.search.SearchScreen
import ceui.pixiv.ui.screen.user.UserDetailScreen
import ceui.pixiv.ui.state.UiState
import coil3.compose.AsyncImage
import java.text.NumberFormat

class NovelDetailScreen(private val novelId: Long) : Screen {

    @OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { NovelDetailScreenModel(novelId) }
        val state by screenModel.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val novel = (state as? UiState.Success)?.data

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("小说详情") },
                    navigationIcon = {
                        IconButton(onClick = navigator::pop) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        if (novel?.is_bookmarked != null) {
                            IconButton(onClick = screenModel::toggleBookmark) {
                                Icon(
                                    imageVector = if (novel.is_bookmarked == true) {
                                        Icons.Filled.Favorite
                                    } else {
                                        Icons.Outlined.FavoriteBorder
                                    },
                                    contentDescription = if (novel.is_bookmarked == true) "取消收藏" else "收藏",
                                    tint = if (novel.is_bookmarked == true) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            when (val current = state) {
                is UiState.Loading -> Box(Modifier.fillMaxSize().padding(padding)) { LoadingView() }
                is UiState.Error -> Box(Modifier.fillMaxSize().padding(padding)) {
                    ErrorView(current.message, screenModel::reload)
                }
                is UiState.Success -> NovelDetailContent(
                    novel = current.data,
                    contentPadding = padding,
                    onRead = { navigator.push(NovelReaderScreen(novelId, current.data.title)) },
                    onUserClick = { userId -> navigator.push(UserDetailScreen(userId)) },
                    onSeriesClick = { seriesId -> navigator.push(NovelSeriesScreen(seriesId)) },
                    onTagClick = { tag -> navigator.push(SearchScreen(initialQuery = tag)) },
                    onToggleFollow = screenModel::toggleFollow,
                )
            }
        }
    }
}

@Composable
@androidx.compose.foundation.layout.ExperimentalLayoutApi
private fun NovelDetailContent(
    novel: Novel,
    contentPadding: PaddingValues,
    onRead: () -> Unit,
    onUserClick: (Long) -> Unit,
    onSeriesClick: (Long) -> Unit,
    onTagClick: (String) -> Unit,
    onToggleFollow: () -> Unit,
) {
    val numberFormat = remember { NumberFormat.getIntegerInstance() }
    val detailModifier = Modifier.fillMaxWidth().widthIn(max = 1040.dp)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            NovelHero(
                novel = novel,
                modifier = detailModifier,
                numberFormat = numberFormat,
                onRead = onRead,
                onUserClick = onUserClick,
                onSeriesClick = onSeriesClick,
                onToggleFollow = onToggleFollow,
            )
        }

        novel.caption?.takeIf { it.isNotBlank() }?.let { caption ->
            item {
                NovelDetailSection(title = "简介", modifier = detailModifier) {
                    CaptionText(html = caption, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        novel.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
            item {
                NovelDetailSection(title = "标签", modifier = detailModifier) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        tags.forEach { tag ->
                            TagChip(tag = tag, onClick = onTagClick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
@androidx.compose.foundation.layout.ExperimentalLayoutApi
private fun NovelHero(
    novel: Novel,
    modifier: Modifier,
    numberFormat: NumberFormat,
    onRead: () -> Unit,
    onUserClick: (Long) -> Unit,
    onSeriesClick: (Long) -> Unit,
    onToggleFollow: () -> Unit,
) {
    Card(modifier = modifier, shape = RoundedCornerShape(12.dp)) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isWide = maxWidth >= 720.dp
            val innerPadding = if (isWide) 20.dp else 16.dp

            if (isWide) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(innerPadding),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    NovelCover(
                        novel = novel,
                        modifier = Modifier.width(220.dp).aspectRatio(240f / 338f),
                    )
                    NovelHeroInfo(
                        novel = novel,
                        numberFormat = numberFormat,
                        modifier = Modifier.weight(1f),
                        onRead = onRead,
                        onUserClick = onUserClick,
                        onSeriesClick = onSeriesClick,
                        onToggleFollow = onToggleFollow,
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(innerPadding),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    NovelCover(
                        novel = novel,
                        modifier = Modifier
                            .fillMaxWidth(0.62f)
                            .widthIn(max = 240.dp)
                            .aspectRatio(240f / 338f)
                            .align(Alignment.CenterHorizontally),
                    )
                    NovelHeroInfo(
                        novel = novel,
                        numberFormat = numberFormat,
                        modifier = Modifier.fillMaxWidth(),
                        onRead = onRead,
                        onUserClick = onUserClick,
                        onSeriesClick = onSeriesClick,
                        onToggleFollow = onToggleFollow,
                    )
                }
            }
        }
    }
}

@Composable
@androidx.compose.foundation.layout.ExperimentalLayoutApi
private fun NovelHeroInfo(
    novel: Novel,
    numberFormat: NumberFormat,
    modifier: Modifier,
    onRead: () -> Unit,
    onUserClick: (Long) -> Unit,
    onSeriesClick: (Long) -> Unit,
    onToggleFollow: () -> Unit,
) {
    val user = novel.user
    val series = novel.series
    val userId = user?.id ?: 0L
    val seriesId = series?.id ?: 0L

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = novel.title ?: "未命名小说",
            style = MaterialTheme.typography.headlineSmall,
        )

        if (user != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = userId > 0L) { onUserClick(userId) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UserAvatar(
                    url = user.profile_image_urls?.px_50x50
                        ?: user.profile_image_urls?.medium,
                    size = 44,
                )
                Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        text = user.name.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    user.account?.takeIf { it.isNotBlank() }?.let { account ->
                        Text(
                            text = "@$account",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                user.is_followed?.let {
                    OutlinedButton(onClick = onToggleFollow) {
                        Text(if (it) "已关注" else "关注")
                    }
                }
            }
        }

        if (series != null && seriesId > 0L && !series.title.isNullOrBlank()) {
            TextButton(
                onClick = { onSeriesClick(seriesId) },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.align(Alignment.Start),
            ) {
                Text(
                    text = "系列：${series.title}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (novel.novel_ai_type == 2) NovelBadge("AI", MaterialTheme.colorScheme.errorContainer)
            if (novel.is_x_restricted == true || novel.x_restrict == 1) {
                NovelBadge("R-18", MaterialTheme.colorScheme.errorContainer)
            }
            if (novel.is_mypixiv_only == true) {
                NovelBadge("仅好友可见", MaterialTheme.colorScheme.secondaryContainer)
            }
            if (novel.is_original == true) {
                NovelBadge("原创", MaterialTheme.colorScheme.primaryContainer)
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NovelStat(Icons.Filled.MenuBook, "${formatCount(numberFormat, novel.text_length)} 字")
            NovelStat(Icons.Filled.Favorite, formatCount(numberFormat, novel.total_bookmarks))
            NovelStat(Icons.Filled.Visibility, formatCount(numberFormat, novel.total_view))
            NovelStat(Icons.Filled.Comment, formatCount(numberFormat, novel.total_comments))
            novel.create_date?.take(10)?.let { date ->
                NovelStat(Icons.Filled.CalendarToday, date)
            }
        }

        Spacer(Modifier.height(2.dp))
        Button(onClick = onRead, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.MenuBook, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("开始阅读")
        }
    }
}

@Composable
private fun NovelCover(novel: Novel, modifier: Modifier) {
    val imageUrls = remember(
        novel.id,
        novel.image_urls?.medium,
        novel.image_urls?.large,
        novel.image_urls?.square_medium,
    ) {
        listOfNotNull(
            novel.image_urls?.medium,
            novel.image_urls?.large,
            novel.image_urls?.square_medium,
        ).distinct()
    }
    var imageIndex by remember(imageUrls) { mutableStateOf(0) }
    val imageUrl = imageUrls.getOrNull(imageIndex)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp),
                )
                Text(
                    text = "暂无封面",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            AsyncImage(
                model = imageUrl,
                contentDescription = novel.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = {
                    imageIndex = (imageIndex + 1).coerceAtMost(imageUrls.size)
                },
            )
        }
    }
}

@Composable
private fun NovelDetailSection(
    title: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun NovelStat(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NovelBadge(text: String, color: Color) {
    Surface(
        color = color,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

private fun formatCount(numberFormat: NumberFormat, value: Int?): String =
    numberFormat.format(value ?: 0)
