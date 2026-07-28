package ceui.pixiv.ui.screen.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.loxia.TrendingTag
import ceui.loxia.UserPreview
import ceui.pixiv.store.Search_table
import ceui.pixiv.ui.component.EmptyView
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.IllustCard
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.component.NovelCard
import ceui.pixiv.ui.navigation.LocalScrollToTop
import ceui.pixiv.ui.screen.detail.IllustDetailScreen
import ceui.pixiv.ui.screen.novel.NovelDetailScreen
import ceui.pixiv.ui.screen.user.UserDetailScreen
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.util.openInBrowser
import coil3.compose.AsyncImage
import java.net.URI

class SearchScreen(
    private val initialQuery: String? = null,
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { SearchScreenModel(initialQuery) }
        val activeTab by screenModel.activeTab.collectAsState()
        val tags by screenModel.tags.collectAsState()
        val input by screenModel.input.collectAsState()
        val query by screenModel.query.collectAsState()
        val hasSubmitted by screenModel.hasSubmitted.collectAsState()
        val illustState by screenModel.illustState.collectAsState()
        val novelState by screenModel.novelState.collectAsState()
        val userState by screenModel.userState.collectAsState()
        val illustFilter by screenModel.illustFilter.collectAsState()
        val novelFilter by screenModel.novelFilter.collectAsState()
        val options by screenModel.searchOptions.collectAsState()
        val suggestions by screenModel.suggestions.collectAsState()
        val trendingTags by screenModel.trendingTags.collectAsState()
        val clipboardSuggestion by screenModel.clipboardSuggestion.collectAsState()
        val pinnedHistory by screenModel.pinnedHistory.collectAsState()
        val recentHistory by screenModel.recentHistory.collectAsState()
        val isRefreshing by screenModel.isRefreshing.collectAsState()
        val isLoadingMore by screenModel.isLoadingMore.collectAsState()
        val hasMoreIllust by screenModel.illustHasMore.collectAsState()
        val hasMoreNovel by screenModel.novelHasMore.collectAsState()
        val hasMoreUser by screenModel.userHasMore.collectAsState()
        val navigator = LocalNavigator.currentOrThrow

        var showFilter by remember { mutableStateOf(false) }
        var numericInput by remember { mutableStateOf<String?>(null) }

        val illustGridState = rememberLazyStaggeredGridState()
        val scrollToTopState = LocalScrollToTop.current
        val scrollToTopValue = scrollToTopState.value
        LaunchedEffect(scrollToTopValue) {
            if (scrollToTopValue > 0) {
                illustGridState.scrollToItem(0)
                screenModel.refresh()
                scrollToTopState.value = 0
            }
        }

        fun submit(value: String = query) {
            val trimmed = value.trim()
            when (screenModel.classifyInput(trimmed)) {
                SearchInputKind.Keyword -> screenModel.search(trimmed)
                SearchInputKind.Url -> openSmartUrl(trimmed, navigator)
                SearchInputKind.Numeric -> numericInput = trimmed
            }
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { screenModel.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (tags.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    ) {
                        items(tags, key = { it }) { tag ->
                            AssistChip(
                                onClick = { screenModel.removeTagAndSearch(tag) },
                                label = { Text(tag, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "移除标签") },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = screenModel::updateInput,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("搜索标签、标题或作者") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (input.isNotEmpty()) {
                                IconButton(onClick = { screenModel.updateInput("") }) {
                                    Icon(Icons.Default.Close, contentDescription = "清除输入")
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { submit() }),
                    )
                    if (activeTab != SearchTab.User) {
                        IconButton(onClick = { showFilter = true }) {
                            Icon(Icons.Default.FilterAlt, contentDescription = "筛选")
                        }
                    }
                    if (query.isNotBlank() && input.isBlank()) {
                        IconButton(onClick = screenModel::clearQuery) {
                            Icon(Icons.Default.Refresh, contentDescription = "清除搜索")
                        }
                    }
                }

                if (suggestions.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                    ) {
                        items(suggestions, key = { it.tag }) { suggestion ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { screenModel.acceptSuggestion(suggestion) }
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                                Column(modifier = Modifier.padding(start = 10.dp)) {
                                    Text(suggestion.tag)
                                    suggestion.translatedName?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }

                TabRow(selectedTabIndex = activeTab.ordinal) {
                    SearchTab.values().forEach { tab ->
                        Tab(
                            selected = activeTab == tab,
                            onClick = { screenModel.selectTab(tab) },
                            text = { Text(tab.label) },
                        )
                    }
                }

                if (activeTab != SearchTab.User) {
                    val filter = if (activeTab == SearchTab.Illust) illustFilter else novelFilter
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SearchSort.values().forEach { sort ->
                            FilterChip(
                                selected = filter.sort == sort,
                                onClick = {
                                    screenModel.updateActiveFilter(filter.copy(sort = sort))
                                    screenModel.refresh()
                                },
                                label = { Text(sort.label) },
                            )
                        }
                        val count = filter.activeCount(isNovel = activeTab == SearchTab.Novel)
                        if (count > 0) {
                            Text("已筛选 $count 项", style = MaterialTheme.typography.labelMedium, modifier = Modifier.align(Alignment.CenterVertically))
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    if (!hasSubmitted) {
                        SearchLanding(
                            clipboardSuggestion = clipboardSuggestion,
                            trendingTags = trendingTags,
                            pinnedHistory = pinnedHistory,
                            recentHistory = recentHistory,
                            onClipboardClick = { submit(it) },
                            onDismissClipboard = screenModel::dismissClipboardSuggestion,
                            onTagClick = { screenModel.search(it) },
                            onHistoryClick = screenModel::search,
                            onTogglePinned = screenModel::togglePinned,
                            onDelete = screenModel::deleteHistory,
                            onClearPinned = screenModel::clearPinnedHistory,
                            onClearRecent = screenModel::clearRecentHistory,
                        )
                    } else {
                        when (activeTab) {
                            SearchTab.Illust -> IllustResults(
                                state = illustState,
                                gridState = illustGridState,
                                hasMore = hasMoreIllust,
                                isLoadingMore = isLoadingMore,
                                onLoadMore = screenModel::loadMore,
                                onRefresh = { screenModel.refresh() },
                                onIllustClick = { navigator.push(IllustDetailScreen(it)) },
                            )
                            SearchTab.Novel -> NovelResults(
                                state = novelState,
                                hasMore = hasMoreNovel,
                                isLoadingMore = isLoadingMore,
                                onLoadMore = screenModel::loadMore,
                                onRefresh = { screenModel.refresh() },
                                onNovelClick = { navigator.push(NovelDetailScreen(it)) },
                                onUserClick = { navigator.push(UserDetailScreen(it)) },
                                onSeriesClick = { navigator.push(ceui.pixiv.ui.screen.novel.NovelSeriesScreen(it)) },
                                onToggleBookmark = screenModel::toggleNovelBookmark,
                            )
                            SearchTab.User -> UserResults(
                                state = userState,
                                hasMore = hasMoreUser,
                                isLoadingMore = isLoadingMore,
                                onLoadMore = screenModel::loadMore,
                                onRefresh = { screenModel.refresh() },
                                onUserClick = { navigator.push(UserDetailScreen(it)) },
                            )
                        }
                    }
                }
            }
        }

        if (showFilter && activeTab != SearchTab.User) {
            val filter = if (activeTab == SearchTab.Illust) illustFilter else novelFilter
            SearchFilterSheet(
                isNovel = activeTab == SearchTab.Novel,
                initialFilter = filter,
                options = options,
                onDismiss = { showFilter = false },
                onApply = {
                    screenModel.updateActiveFilter(it)
                    screenModel.refresh()
                },
            )
        }

        numericInput?.let { value ->
            AlertDialog(
                onDismissRequest = { numericInput = null },
                title = { Text("识别到数字 ID") },
                text = { Text("请选择要打开的对象类型，或继续按关键词搜索。") },
                confirmButton = {
                    Button(onClick = { numericInput = null; navigator.push(IllustDetailScreen(value.toLong())) }) {
                        Text("作品")
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { numericInput = null; navigator.push(UserDetailScreen(value.toLong())) }) { Text("用户") }
                        TextButton(onClick = { numericInput = null; navigator.push(NovelDetailScreen(value.toLong())) }) { Text("小说") }
                        TextButton(onClick = { numericInput = null; screenModel.search(value) }) { Text("关键词") }
                    }
                },
            )
        }
    }
}

@Composable
private fun SearchLanding(
    clipboardSuggestion: String?,
    trendingTags: UiState<List<TrendingTag>>,
    pinnedHistory: List<Search_table>,
    recentHistory: List<Search_table>,
    onClipboardClick: (String) -> Unit,
    onDismissClipboard: () -> Unit,
    onTagClick: (String) -> Unit,
    onHistoryClick: (String) -> Unit,
    onTogglePinned: (Search_table) -> Unit,
    onDelete: (Long) -> Unit,
    onClearPinned: () -> Unit,
    onClearRecent: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        clipboardSuggestion?.let { value ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("剪贴板内容：${value.take(42)}", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { onClipboardClick(value) }) { Text("使用") }
                IconButton(onClick = onDismissClipboard) { Icon(Icons.Default.Close, contentDescription = "关闭") }
            }
        }

        Text("热门标签", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        when (trendingTags) {
            is UiState.Loading -> Text("加载中…", modifier = Modifier.padding(horizontal = 12.dp))
            is UiState.Error -> Text("暂时无法加载热门标签", modifier = Modifier.padding(horizontal = 12.dp))
            is UiState.Success -> LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(trendingTags.data, key = { it.tag.orEmpty() }) { tag ->
                    AssistChip(
                        onClick = { tag.tag?.let(onTagClick) },
                        label = { Text(tag.translated_name ?: tag.tag.orEmpty()) },
                    )
                }
            }
        }

        if (pinnedHistory.isNotEmpty()) {
            SearchHistoryRow("置顶搜索", pinnedHistory, onHistoryClick, onTogglePinned, onDelete, onClearPinned)
        }
        if (recentHistory.isNotEmpty()) {
            SearchHistoryRow("最近搜索", recentHistory, onHistoryClick, onTogglePinned, onDelete, onClearRecent)
        }
        if (pinnedHistory.isEmpty() && recentHistory.isEmpty()) {
            EmptyView("输入关键词开始搜索", Modifier.height(180.dp))
        }
    }
}

@Composable
private fun IllustResults(
    state: UiState<List<Illust>>,
    gridState: androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onIllustClick: (Long) -> Unit,
) {
    val shouldLoadMore by remember(state, gridState, hasMore, isLoadingMore) {
        derivedStateOf {
            val items = (state as? UiState.Success)?.data ?: return@derivedStateOf false
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            shouldLoadSearchMore(items.size, lastVisible, hasMore, isLoadingMore)
        }
    }
    LaunchedEffect(shouldLoadMore, hasMore, isLoadingMore) {
        if (shouldLoadMore) onLoadMore()
    }

    when (state) {
        is UiState.Loading -> LoadingView(Modifier.fillMaxSize())
        is UiState.Error -> ErrorView(state.message, onRefresh, Modifier.fillMaxSize())
        is UiState.Success -> if (state.data.isEmpty()) {
            EmptyView("没有找到插画", Modifier.fillMaxSize())
        } else {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                state = gridState,
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalItemSpacing = 4.dp,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.data, key = { it.id }) { illust ->
                    IllustCard(illust = illust, onClick = onIllustClick)
                }
                if (isLoadingMore) {
                    item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                }
            }
        }
    }
}

@Composable
private fun NovelResults(
    state: UiState<List<Novel>>,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onNovelClick: (Long) -> Unit,
    onUserClick: (Long) -> Unit,
    onSeriesClick: (Long) -> Unit,
    onToggleBookmark: (Novel) -> Unit,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val shouldLoadMore by remember(state, listState, hasMore, isLoadingMore) {
        derivedStateOf {
            val items = (state as? UiState.Success)?.data ?: return@derivedStateOf false
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            shouldLoadSearchMore(items.size, lastVisible, hasMore, isLoadingMore)
        }
    }
    LaunchedEffect(shouldLoadMore, hasMore, isLoadingMore) {
        if (shouldLoadMore) onLoadMore()
    }

    when (state) {
        is UiState.Loading -> LoadingView(Modifier.fillMaxSize())
        is UiState.Error -> ErrorView(state.message, onRefresh, Modifier.fillMaxSize())
        is UiState.Success -> if (state.data.isEmpty()) {
            EmptyView("没有找到小说", Modifier.fillMaxSize())
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.data, key = { it.id }) { novel ->
                    NovelCard(
                        novel = novel,
                        onClick = onNovelClick,
                        onUserClick = onUserClick,
                        onSeriesClick = onSeriesClick,
                        onToggleBookmark = onToggleBookmark,
                    )
                }
                if (isLoadingMore) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun UserResults(
    state: UiState<List<UserPreview>>,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onUserClick: (Long) -> Unit,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val shouldLoadMore by remember(state, listState, hasMore, isLoadingMore) {
        derivedStateOf {
            val items = (state as? UiState.Success)?.data ?: return@derivedStateOf false
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            shouldLoadSearchMore(items.size, lastVisible, hasMore, isLoadingMore)
        }
    }
    LaunchedEffect(shouldLoadMore, hasMore, isLoadingMore) {
        if (shouldLoadMore) onLoadMore()
    }

    when (state) {
        is UiState.Loading -> LoadingView(Modifier.fillMaxSize())
        is UiState.Error -> ErrorView(state.message, onRefresh, Modifier.fillMaxSize())
        is UiState.Success -> if (state.data.isEmpty()) {
            EmptyView("没有找到用户", Modifier.fillMaxSize())
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(state.data, key = { index, item -> item.user?.id ?: index.toLong() }) { _, preview ->
                    val user = preview.user
                    if (user != null) {
                        UserResultCard(user = user, preview = preview, onClick = { onUserClick(user.id) })
                    }
                }
                if (isLoadingMore) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun UserResultCard(
    user: ceui.loxia.User,
    preview: UserPreview,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = user.profile_image_urls?.medium,
                contentDescription = user.name,
                modifier = Modifier.size(64.dp),
                contentScale = ContentScale.Crop,
            )
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(user.name ?: "未知用户", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("@${user.account ?: user.id}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (preview.illusts.isNotEmpty()) {
                    Text("作品预览 ${preview.illusts.size} 件", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SearchHistoryRow(
    title: String,
    entries: List<Search_table>,
    onSearch: (String) -> Unit,
    onTogglePinned: (Search_table) -> Unit,
    onDelete: (Long) -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onClear) { Text("清空") }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(entries, key = { it.id }) { entry ->
                AssistChip(
                    onClick = { onSearch(entry.keyword) },
                    label = { Text(entry.keyword, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = {
                        if (entry.pinned == 1L) Icon(Icons.Default.PushPin, contentDescription = "已置顶")
                    },
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { onTogglePinned(entry) }) {
                                Icon(Icons.Default.PushPin, contentDescription = "切换置顶")
                            }
                            IconButton(onClick = { onDelete(entry.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除")
                            }
                        }
                    },
                )
            }
        }
    }
}

private fun openSmartUrl(value: String, navigator: cafe.adriel.voyager.navigator.Navigator) {
    val artworkId = Regex("/artworks/(\\d+)").find(value)?.groupValues?.getOrNull(1)
    val userId = Regex("/users/(\\d+)").find(value)?.groupValues?.getOrNull(1)
    val novelId = Regex("/novel/(?:show.php\\?id=|show/)?(\\d+)").find(value)?.groupValues?.getOrNull(1)
    when {
        artworkId != null -> navigator.push(IllustDetailScreen(artworkId.toLong()))
        userId != null -> navigator.push(UserDetailScreen(userId.toLong()))
        novelId != null -> navigator.push(NovelDetailScreen(novelId.toLong()))
        else -> runCatching { URI(value) }.getOrNull()?.let { openInBrowser(it.toString()) }
    }
}
