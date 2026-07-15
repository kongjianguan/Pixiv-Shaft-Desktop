package ceui.pixiv.ui.screen.novel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.loxia.Novel
import ceui.loxia.NovelSeriesDetail
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.component.CaptionText
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.component.NovelCard
import ceui.pixiv.ui.component.UserAvatar
import ceui.pixiv.ui.screen.user.UserDetailScreen
import ceui.pixiv.ui.state.UiState
import java.text.NumberFormat
import kotlin.math.ceil

class NovelSeriesScreen(private val seriesId: Long) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { NovelSeriesScreenModel(seriesId) }
        val seriesState by screenModel.seriesState.collectAsState()
        val novelsState by screenModel.novelsState.collectAsState()
        val latestNovel by screenModel.latestNovel.collectAsState()
        val isRefreshing by screenModel.isRefreshing.collectAsState()
        val navigator = LocalNavigator.currentOrThrow

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = (seriesState as? UiState.Success)?.data?.title ?: "小说系列",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = navigator::pop) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        val detail = (seriesState as? UiState.Success)?.data
                        if (detail != null) {
                            IconButton(onClick = screenModel::toggleWatchlist) {
                                Icon(
                                    imageVector = if (detail.watchlist_added == true) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = if (detail.watchlist_added == true) "Remove from watchlist" else "Add to watchlist",
                                    tint = if (detail.watchlist_added == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                )
            }
        ) { padding ->
            when (val state = seriesState) {
                is UiState.Loading -> Box(Modifier.fillMaxSize().padding(padding)) { LoadingView() }
                is UiState.Error -> Box(Modifier.fillMaxSize().padding(padding)) {
                    ErrorView(state.message, screenModel::refresh)
                }
                is UiState.Success -> SeriesContent(
                    detail = state.data,
                    latestNovel = latestNovel,
                    novelsState = novelsState,
                    isRefreshing = isRefreshing,
                    onRefresh = screenModel::refresh,
                    onLoadMore = screenModel::loadMore,
                    onUserClick = { navigator.push(UserDetailScreen(it)) },
                    onToggleFollow = screenModel::toggleFollow,
                    onReadLatest = { novel -> navigator.push(NovelReaderScreen(novel.id, novel.title)) },
                    onNovelClick = { novel -> navigator.push(NovelDetailScreen(novel.id)) },
                    onToggleBookmark = screenModel::toggleNovelBookmark,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeriesContent(
    detail: NovelSeriesDetail,
    latestNovel: Novel?,
    novelsState: UiState<List<Novel>>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onUserClick: (Long) -> Unit,
    onToggleFollow: () -> Unit,
    onReadLatest: (Novel) -> Unit,
    onNovelClick: (Novel) -> Unit,
    onToggleBookmark: (Novel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyStaggeredGridState()
    val maxColumnWidthDp by AppContainer.settingsStore.novelFeedMaxColumnWidthDpFlow.collectAsState()
    val maxColumns by AppContainer.settingsStore.novelFeedMaxColumnsFlow.collectAsState()
    val minColumnWidthDp by AppContainer.settingsStore.novelFeedMinColumnWidthDpFlow.collectAsState()
    val shouldLoadMore by remember(novelsState) {
        derivedStateOf {
            val chapters = (novelsState as? UiState.Success)?.data ?: return@derivedStateOf false
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= chapters.size - 4 && chapters.isNotEmpty()
        }
    }
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) onLoadMore() }

    PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val spacing = 10.dp
            val maxColumnWidth = maxColumnWidthDp.dp
            val desiredColumns = ceil(
                (maxWidth.value + spacing.value) / (maxColumnWidth.value + spacing.value)
            ).toInt()
            val columnsAllowedByMinimum = (
                (maxWidth.value + spacing.value) / (minColumnWidthDp.dp.value + spacing.value)
            ).toInt()
            val columns = minOf(desiredColumns, maxColumns, columnsAllowedByMinimum).coerceAtLeast(1)
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(columns),
                state = gridState,
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalItemSpacing = spacing,
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "series-header", span = StaggeredGridItemSpan.FullLine) {
                    SeriesHeader(
                        detail = detail,
                        latestNovel = latestNovel,
                        onUserClick = onUserClick,
                        onToggleFollow = onToggleFollow,
                        onReadLatest = onReadLatest,
                    )
                }
                item(key = "chapter-label", span = StaggeredGridItemSpan.FullLine) {
                    Text("章节", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                }
                when (novelsState) {
                    is UiState.Loading -> item(span = StaggeredGridItemSpan.FullLine) {
                        LoadingView(Modifier.fillMaxWidth().height(160.dp))
                    }
                    is UiState.Error -> item(span = StaggeredGridItemSpan.FullLine) {
                        ErrorView(novelsState.message, onRefresh, Modifier.fillMaxWidth().height(160.dp))
                    }
                    is UiState.Success -> items(novelsState.data, key = { it.id }) { novel ->
                        NovelCard(
                            novel = novel,
                            onClick = { onNovelClick(novel) },
                            onUserClick = onUserClick,
                            onToggleBookmark = onToggleBookmark,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesHeader(
    detail: NovelSeriesDetail,
    latestNovel: Novel?,
    onUserClick: (Long) -> Unit,
    onToggleFollow: () -> Unit,
    onReadLatest: (Novel) -> Unit,
) {
    val numberFormat = remember { NumberFormat.getIntegerInstance() }
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(detail.title.orEmpty(), style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val user = detail.user
                if (user != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f).clickable { onUserClick(user.id) },
                    ) {
                        UserAvatar(user.profile_image_urls?.px_50x50, size = 36)
                        Text(
                            text = user.name.orEmpty(),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    OutlinedButton(onClick = onToggleFollow) {
                        Text(if (user.is_followed == true) "已关注" else "关注")
                    }
                }
            }
            val meta = buildList {
                add("${detail.content_count} 章")
                if (detail.total_character_count > 0) add("${numberFormat.format(detail.total_character_count)} 字")
                if (detail.is_concluded == true) add("已完结")
                if (detail.novel_ai_type == 2) add("AI")
            }.joinToString("  ·  ")
            Text(meta, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CaptionText(detail.caption, Modifier.fillMaxWidth())
            latestNovel?.let { latest ->
                Button(onClick = { onReadLatest(latest) }, modifier = Modifier.fillMaxWidth()) {
                    Text("阅读最新一话（第 ${detail.content_count} 章）")
                }
            }
        }
    }
}
