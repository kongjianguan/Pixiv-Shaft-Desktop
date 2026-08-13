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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import ceui.pixiv.download.NovelMergeFormat
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
        val selectionMode by screenModel.selectionMode.collectAsState()
        val selectedIds by screenModel.selectedIds.collectAsState()
        val allSelected by screenModel.allSelected.collectAsState()
        val resolvingChapters by screenModel.resolvingChapters.collectAsState()
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
                        if (selectionMode) {
                            TextButton(onClick = screenModel::exitSelectionMode) {
                                Text("取消")
                            }
                        } else {
                            val detail = (seriesState as? UiState.Success)?.data
                            SeriesDownloadMenu(
                                resolvingChapters = resolvingChapters,
                                onPickChapters = screenModel::enterSelectionMode,
                                onDownloadAll = screenModel::downloadAllSeparate,
                                onMergeTxt = { screenModel.downloadMerge(NovelMergeFormat.TXT) },
                                onMergeMd = { screenModel.downloadMerge(NovelMergeFormat.MD) },
                            )
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
                    }
                )
            },
            bottomBar = {
                if (selectionMode) {
                    Surface(tonalElevation = 3.dp) {
                        Column {
                            if (resolvingChapters) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(
                                    text = "正在获取章节列表…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(
                                    enabled = !resolvingChapters,
                                    onClick = screenModel::selectAllToggle,
                                ) {
                                    Text(if (allSelected) "取消全选" else "全选")
                                }
                                Box(Modifier.weight(1f))
                                Button(
                                    // 拉取章节列表期间禁点，避免与全选拉取并发重复分页
                                    enabled = selectedIds.isNotEmpty() && !resolvingChapters,
                                    onClick = screenModel::downloadSelected,
                                ) {
                                    Text("下载选中 ${selectedIds.size} 篇")
                                }
                            }
                        }
                    }
                }
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
                    selectionMode = selectionMode,
                    selectedIds = selectedIds,
                    onToggleSelect = screenModel::toggleSelect,
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

/** 系列页顶栏「下载」菜单：章节多选 / 全部逐篇 / 合并导出 TXT·MD */
@Composable
private fun SeriesDownloadMenu(
    resolvingChapters: Boolean,
    onPickChapters: () -> Unit,
    onDownloadAll: () -> Unit,
    onMergeTxt: () -> Unit,
    onMergeMd: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Download, contentDescription = "下载")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("章节多选") },
                onClick = {
                    expanded = false
                    onPickChapters()
                },
            )
            DropdownMenuItem(
                text = { Text(if (resolvingChapters) "正在获取章节列表…" else "全部逐篇下载") },
                enabled = !resolvingChapters,
                onClick = {
                    expanded = false
                    onDownloadAll()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("合并导出为 TXT") },
                onClick = {
                    expanded = false
                    onMergeTxt()
                },
            )
            DropdownMenuItem(
                text = { Text("合并导出为 MD") },
                onClick = {
                    expanded = false
                    onMergeMd()
                },
            )
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
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    onToggleSelect: (Long) -> Unit,
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
                        if (selectionMode) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Checkbox(
                                    checked = novel.id in selectedIds,
                                    onCheckedChange = { onToggleSelect(novel.id) },
                                )
                                Box(Modifier.weight(1f)) {
                                    // 选择模式下隐藏用户跳转与收藏按钮，避免误触
                                    NovelCard(
                                        novel = novel,
                                        onClick = { onToggleSelect(novel.id) },
                                    )
                                }
                            }
                        } else {
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
