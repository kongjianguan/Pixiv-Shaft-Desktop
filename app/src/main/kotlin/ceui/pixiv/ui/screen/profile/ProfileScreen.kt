package ceui.pixiv.ui.screen.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.pixiv.ui.component.FeedPager
import ceui.pixiv.ui.component.FeedScaffold
import ceui.pixiv.ui.component.IllustCard
import ceui.pixiv.ui.component.NovelCard
import ceui.pixiv.ui.component.NovelGrid
import ceui.pixiv.ui.component.WorkFeedGrid
import ceui.pixiv.ui.history.BrowseHistoryScreen
import ceui.pixiv.ui.navigation.LocalScrollToTop
import ceui.pixiv.ui.screen.collection.BookmarkTagsScreen
import ceui.pixiv.ui.screen.collection.NovelMarkersScreen
import ceui.pixiv.ui.screen.collection.WatchlistScreen
import ceui.pixiv.ui.screen.detail.IllustDetailScreen
import ceui.pixiv.ui.screen.novel.NovelDetailScreen
import ceui.pixiv.ui.screen.novel.NovelSeriesScreen
import ceui.pixiv.ui.screen.settings.SettingsScreen
import ceui.pixiv.ui.screen.user.UserDetailScreen
import ceui.pixiv.ui.screen.user.UserListMode
import ceui.pixiv.ui.screen.user.UserListScreen
import ceui.pixiv.ui.state.UiState

private enum class ProfileTab(val label: String) {
    ILLUST_BOOKMARKS("插画收藏"),
    NOVEL_BOOKMARKS("小说收藏"),
    CREATED_WORKS("我的作品"),
    HISTORY("历史"),
}

class ProfileScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { ProfileScreenModel() }
        val profileState by screenModel.profileState.collectAsState()
        val profileDetailState by screenModel.profileDetailState.collectAsState()
        val bookmarksState by screenModel.bookmarksState.collectAsState()
        val novelBookmarksState by screenModel.novelBookmarksState.collectAsState()
        val createdIllustsState by screenModel.createdIllustsState.collectAsState()
        val createdNovelsState by screenModel.createdNovelsState.collectAsState()
        val history by screenModel.history.collectAsState()
        val isRefreshing by screenModel.isRefreshing.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        var worksType by remember { mutableStateOf(0) } // 我的作品: 0=插画 1=小说

        Column(modifier = Modifier.fillMaxSize()) {
            ProfileHeader(
                profileState = profileState,
                profileDetailState = profileDetailState,
                onOpenSettings = { navigator.push(SettingsScreen()) },
            )
            ProfileEntryRow(navigator = navigator)

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = screenModel::refresh,
                modifier = Modifier.weight(1f),
            ) {
                FeedPager(pageLabels = ProfileTab.entries.map { it.label }) { page, isCurrentPage ->
                    when (ProfileTab.entries[page]) {
                        ProfileTab.ILLUST_BOOKMARKS -> ProfileIllustFeedPage(
                            state = bookmarksState,
                            isCurrentPage = isCurrentPage,
                            onRefresh = screenModel::refresh,
                            onLoadMore = screenModel::loadMoreBookmarks,
                            emptyMessage = if (screenModel.hasHiddenBookmarksR18()) {
                                "R18 内容已隐藏（可在设置中开启）"
                            } else {
                                "暂无插画收藏"
                            },
                            onIllustClick = { id -> navigator.push(IllustDetailScreen(id)) },
                        )

                        ProfileTab.NOVEL_BOOKMARKS -> ProfileNovelFeedPage(
                            state = novelBookmarksState,
                            isCurrentPage = isCurrentPage,
                            onRefresh = screenModel::refresh,
                            onLoadMore = screenModel::loadMoreNovelBookmarks,
                            emptyMessage = if (screenModel.hasHiddenNovelBookmarksR18()) {
                                "R18 内容已隐藏（可在设置中开启）"
                            } else {
                                "暂无小说收藏"
                            },
                            onNovelClick = { id -> navigator.push(NovelDetailScreen(id)) },
                            onUserClick = { id -> navigator.push(UserDetailScreen(id)) },
                            onSeriesClick = { id -> navigator.push(NovelSeriesScreen(id)) },
                            onToggleBookmark = screenModel::toggleNovelBookmark,
                        )

                        ProfileTab.CREATED_WORKS -> ProfileWorksPage(
                            worksType = worksType,
                            onWorksTypeChange = { worksType = it },
                            isCurrentPage = isCurrentPage,
                            illustState = createdIllustsState,
                            novelState = createdNovelsState,
                            onRefresh = screenModel::refresh,
                            onLoadMoreIllusts = screenModel::loadMoreCreatedIllusts,
                            onLoadMoreNovels = screenModel::loadMoreCreatedNovels,
                            onIllustClick = { id -> navigator.push(IllustDetailScreen(id)) },
                            onNovelClick = { id -> navigator.push(NovelDetailScreen(id)) },
                            onUserClick = { id -> navigator.push(UserDetailScreen(id)) },
                            onSeriesClick = { id -> navigator.push(NovelSeriesScreen(id)) },
                            onToggleBookmark = screenModel::toggleNovelBookmark,
                        )

                        ProfileTab.HISTORY -> ProfileHistoryPage(
                            history = history,
                            isCurrentPage = isCurrentPage,
                            onRefresh = screenModel::refresh,
                            onOpenHistory = { navigator.push(BrowseHistoryScreen()) },
                            onIllustClick = { id -> navigator.push(IllustDetailScreen(id)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(
    profileState: UiState<ceui.loxia.SelfProfile>,
    profileDetailState: UiState<ceui.loxia.ProfileBean>,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val state = profileState) {
                UiState.Loading, is UiState.Error -> {
                    ceui.pixiv.ui.component.UserAvatar(url = null, size = 64)
                }

                is UiState.Success -> {
                    val user = state.data.profile
                    ceui.pixiv.ui.component.UserAvatar(
                        url = user.profile_image_urls?.px_50x50 ?: user.profile_image_urls?.medium,
                        size = 64,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(user.name ?: "Unknown", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "@${user.pixiv_id ?: user.account ?: user.user_id}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (user.is_premium == true) {
                            Text(
                                "Premium",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "设置")
            }
        }

        if (profileDetailState is UiState.Success) {
            val profile = profileDetailState.data
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("插画：${profile.total_illusts}", style = MaterialTheme.typography.labelMedium)
                Text("收藏：${profile.total_illust_bookmarks_public}", style = MaterialTheme.typography.labelMedium)
            }
            if (!profile.job.isNullOrEmpty() || !profile.region.isNullOrEmpty() || !profile.twitter_account.isNullOrEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!profile.job.isNullOrEmpty()) Text("职业：${profile.job}", style = MaterialTheme.typography.labelSmall)
                    if (!profile.region.isNullOrEmpty()) Text("地区：${profile.region}", style = MaterialTheme.typography.labelSmall)
                    if (!profile.twitter_account.isNullOrEmpty()) Text("Twitter：@${profile.twitter_account}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ProfileIllustFeedPage(
    state: UiState<List<Illust>>,
    isCurrentPage: Boolean,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    emptyMessage: String,
    onIllustClick: (Long) -> Unit,
) {
    val gridState = rememberLazyStaggeredGridState()
    ConsumeProfileScrollToTop(gridState, isCurrentPage, onRefresh)
    FeedScaffold(
        state = state,
        gridState = gridState,
        onLoadMore = onLoadMore,
        onRefresh = onRefresh,
        emptyMessage = emptyMessage,
    ) { works ->
        WorkFeedGrid(state = gridState) { _, _ ->
            items(works, key = { it.id }) { illust ->
                IllustCard(illust = illust, onClick = onIllustClick)
            }
        }
    }
}

@Composable
private fun ProfileNovelFeedPage(
    state: UiState<List<Novel>>,
    isCurrentPage: Boolean,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    emptyMessage: String,
    onNovelClick: (Long) -> Unit,
    onUserClick: (Long) -> Unit,
    onSeriesClick: (Long) -> Unit,
    onToggleBookmark: (Novel) -> Unit,
) {
    val gridState = rememberLazyStaggeredGridState()
    ConsumeProfileScrollToTop(gridState, isCurrentPage, onRefresh)
    FeedScaffold(
        state = state,
        gridState = gridState,
        onLoadMore = onLoadMore,
        onRefresh = onRefresh,
        emptyMessage = emptyMessage,
    ) { novels ->
        NovelGrid(gridState = gridState, items = novels) { novel ->
            NovelCard(
                novel = novel,
                onClick = onNovelClick,
                onUserClick = onUserClick,
                onSeriesClick = onSeriesClick,
                onToggleBookmark = onToggleBookmark,
            )
        }
    }
}

@Composable
private fun ProfileWorksPage(
    worksType: Int,
    onWorksTypeChange: (Int) -> Unit,
    isCurrentPage: Boolean,
    illustState: UiState<List<Illust>>,
    novelState: UiState<List<Novel>>,
    onRefresh: () -> Unit,
    onLoadMoreIllusts: () -> Unit,
    onLoadMoreNovels: () -> Unit,
    onIllustClick: (Long) -> Unit,
    onNovelClick: (Long) -> Unit,
    onUserClick: (Long) -> Unit,
    onSeriesClick: (Long) -> Unit,
    onToggleBookmark: (Novel) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = worksType == 0,
                onClick = { onWorksTypeChange(0) },
                label = { Text("插画") },
            )
            FilterChip(
                selected = worksType == 1,
                onClick = { onWorksTypeChange(1) },
                label = { Text("小说") },
            )
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (worksType == 0) {
                ProfileIllustFeedPage(
                    state = illustState,
                    isCurrentPage = isCurrentPage,
                    onRefresh = onRefresh,
                    onLoadMore = onLoadMoreIllusts,
                    emptyMessage = "暂无已发布插画",
                    onIllustClick = onIllustClick,
                )
            } else {
                ProfileNovelFeedPage(
                    state = novelState,
                    isCurrentPage = isCurrentPage,
                    onRefresh = onRefresh,
                    onLoadMore = onLoadMoreNovels,
                    emptyMessage = "暂无已发布小说",
                    onNovelClick = onNovelClick,
                    onUserClick = onUserClick,
                    onSeriesClick = onSeriesClick,
                    onToggleBookmark = onToggleBookmark,
                )
            }
        }
    }
}

@Composable
private fun ProfileHistoryPage(
    history: List<Illust>,
    isCurrentPage: Boolean,
    onRefresh: () -> Unit,
    onOpenHistory: () -> Unit,
    onIllustClick: (Long) -> Unit,
) {
    val gridState = rememberLazyStaggeredGridState()
    ConsumeProfileScrollToTop(gridState, isCurrentPage, onRefresh)

    if (history.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("暂无浏览记录")
            Button(onClick = onOpenHistory, modifier = Modifier.padding(top = 12.dp)) {
                Text("打开完整浏览记录")
            }
        }
    } else {
        WorkFeedGrid(state = gridState) { _, _ ->
            item(key = "history-open", span = StaggeredGridItemSpan.FullLine) {
                Button(
                    onClick = onOpenHistory,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                ) {
                    Text("打开完整浏览记录")
                }
            }
            items(history, key = { it.id }) { illust ->
                IllustCard(illust = illust, onClick = onIllustClick)
            }
        }
    }
}

@Composable
private fun ConsumeProfileScrollToTop(
    gridState: LazyStaggeredGridState,
    isCurrentPage: Boolean,
    onRefresh: () -> Unit,
) {
    val scrollToTopState = LocalScrollToTop.current
    val scrollToTopValue = scrollToTopState.value
    LaunchedEffect(scrollToTopValue, isCurrentPage) {
        if (scrollToTopValue > 0 && isCurrentPage) {
            gridState.scrollToItem(0)
            onRefresh()
            scrollToTopState.value = 0
        }
    }
}

/** 作品流上方的快捷入口：收藏标签、小说标记、追更和用户关系列表。 */
@Composable
private fun ProfileEntryRow(navigator: Navigator) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { AssistChip(onClick = { navigator.push(BookmarkTagsScreen()) }, label = { Text("收藏标签") }) }
        item { AssistChip(onClick = { navigator.push(NovelMarkersScreen()) }, label = { Text("小说标记") }) }
        item { AssistChip(onClick = { navigator.push(WatchlistScreen()) }, label = { Text("追更") }) }
        item { AssistChip(onClick = { navigator.push(UserListScreen("关注中", UserListMode.FOLLOWING)) }, label = { Text("关注中") }) }
        item { AssistChip(onClick = { navigator.push(UserListScreen("粉丝", UserListMode.FOLLOWER)) }, label = { Text("粉丝") }) }
        item { AssistChip(onClick = { navigator.push(UserListScreen("好P友", UserListMode.MYPIXIV)) }, label = { Text("好P友") }) }
    }
}
