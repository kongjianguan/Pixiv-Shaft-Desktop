package ceui.pixiv.ui.screen.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import ceui.pixiv.ui.component.FeedLoadMoreTrigger
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.IllustCard
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.component.NovelCard
import ceui.pixiv.ui.component.UserAvatar
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
        var selectedTab by remember { mutableStateOf(0) }
        var worksType by remember { mutableStateOf(0) } // 我的作品: 0=插画 1=小说

        // 每个 tab（含我的作品下的插画/小说）独立滚动位置，切换时列表回到顶部
        val listState = remember(selectedTab, worksType) { LazyListState() }
        val scrollToTopState = LocalScrollToTop.current
        val scrollToTopValue = scrollToTopState.value
        LaunchedEffect(scrollToTopValue, listState) {
            if (scrollToTopValue > 0) {
                listState.scrollToItem(0)
                screenModel.refresh()
                scrollToTopState.value = 0
            }
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { screenModel.refresh() },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                // Header: avatar + name + stats + settings button
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when (val s = profileState) {
                            is UiState.Loading -> {
                                UserAvatar(url = null, size = 64)
                            }
                            is UiState.Error -> {
                                UserAvatar(url = null, size = 64)
                            }
                            is UiState.Success -> {
                                val user = s.data.profile
                                UserAvatar(
                                    url = user.profile_image_urls?.px_50x50 ?: user.profile_image_urls?.medium,
                                    size = 64
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = user.name ?: "Unknown",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = "@${user.pixiv_id ?: user.account ?: user.user_id}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    if (user.is_premium == true) {
                                        Text(
                                            text = "Premium",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                        IconButton(onClick = { navigator.push(SettingsScreen()) }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                }

                // Stats row
                item {
                    profileDetailState.let { state ->
                        if (state is UiState.Success) {
                            val profile = state.data
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Text("Illusts: ${profile.total_illusts}", style = MaterialTheme.typography.labelMedium)
                                Text("Bookmarks: ${profile.total_illust_bookmarks_public}", style = MaterialTheme.typography.labelMedium)
                            }
                            if (!profile.job.isNullOrEmpty() || !profile.region.isNullOrEmpty() || !profile.twitter_account.isNullOrEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    if (!profile.job.isNullOrEmpty()) {
                                        Text("Job: ${profile.job}", style = MaterialTheme.typography.labelSmall)
                                    }
                                    if (!profile.region.isNullOrEmpty()) {
                                        Text("Region: ${profile.region}", style = MaterialTheme.typography.labelSmall)
                                    }
                                    if (!profile.twitter_account.isNullOrEmpty()) {
                                        Text("Twitter: @${profile.twitter_account}", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }

                // Entry row: 收藏标签 / 小说标记 / 追更 / 关注中 / 粉丝 / 好P友
                item {
                    ProfileEntryRow(navigator = navigator)
                }

                // Tab row: 插画收藏 / 小说收藏 / 我的作品 / 历史
                item {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("插画收藏") }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("小说收藏") }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("我的作品") }
                        )
                        Tab(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            text = { Text("历史") }
                        )
                    }
                }

                // Tab content
                when (selectedTab) {
                    0 -> {
                        when (val s = bookmarksState) {
                            is UiState.Loading -> item { LoadingView(modifier = Modifier.height(200.dp)) }
                            is UiState.Error -> item {
                                ErrorView(s.message, { screenModel.refresh() }, Modifier.height(200.dp))
                            }
                            is UiState.Success -> {
                                if (s.data.isEmpty()) {
                                    item { Text("No bookmarks", modifier = Modifier.padding(16.dp)) }
                                } else {
                                    items(s.data, key = { it.id }) { illust ->
                                        IllustCard(
                                            illust = illust,
                                            onClick = { id -> navigator.push(IllustDetailScreen(id)) },
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )
                                    }
                                    item(key = "bookmarks-load-more") {
                                        FeedLoadMoreTrigger(listState) { screenModel.loadMoreBookmarks() }
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        when (val s = novelBookmarksState) {
                            is UiState.Loading -> item { LoadingView(modifier = Modifier.height(200.dp)) }
                            is UiState.Error -> item {
                                ErrorView(s.message, { screenModel.refresh() }, Modifier.height(200.dp))
                            }
                            is UiState.Success -> {
                                if (s.data.isEmpty()) {
                                    item { Text("No novel bookmarks", modifier = Modifier.padding(16.dp)) }
                                } else {
                                    items(s.data, key = { it.id }) { novel ->
                                        NovelCard(
                                            novel = novel,
                                            onClick = { id -> navigator.push(NovelDetailScreen(id)) },
                                            onUserClick = { id -> navigator.push(UserDetailScreen(id)) },
                                            onSeriesClick = { id -> navigator.push(NovelSeriesScreen(id)) },
                                            onToggleBookmark = { screenModel.toggleNovelBookmark(it) },
                                            modifier = Modifier.padding(horizontal = 16.dp)
                                        )
                                    }
                                    item(key = "novel-bookmarks-load-more") {
                                        FeedLoadMoreTrigger(listState) { screenModel.loadMoreNovelBookmarks() }
                                    }
                                }
                            }
                        }
                    }
                    2 -> {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = worksType == 0,
                                    onClick = { worksType = 0 },
                                    label = { Text("插画") }
                                )
                                FilterChip(
                                    selected = worksType == 1,
                                    onClick = { worksType = 1 },
                                    label = { Text("小说") }
                                )
                            }
                        }
                        if (worksType == 0) {
                            when (val s = createdIllustsState) {
                                is UiState.Loading -> item { LoadingView(modifier = Modifier.height(200.dp)) }
                                is UiState.Error -> item {
                                    ErrorView(s.message, { screenModel.refresh() }, Modifier.height(200.dp))
                                }
                                is UiState.Success -> {
                                    if (s.data.isEmpty()) {
                                        item { Text("No works", modifier = Modifier.padding(16.dp)) }
                                    } else {
                                        items(s.data, key = { it.id }) { illust ->
                                            IllustCard(
                                                illust = illust,
                                                onClick = { id -> navigator.push(IllustDetailScreen(id)) },
                                                modifier = Modifier.padding(horizontal = 4.dp)
                                            )
                                        }
                                        item(key = "created-illusts-load-more") {
                                            FeedLoadMoreTrigger(listState) { screenModel.loadMoreCreatedIllusts() }
                                        }
                                    }
                                }
                            }
                        } else {
                            when (val s = createdNovelsState) {
                                is UiState.Loading -> item { LoadingView(modifier = Modifier.height(200.dp)) }
                                is UiState.Error -> item {
                                    ErrorView(s.message, { screenModel.refresh() }, Modifier.height(200.dp))
                                }
                                is UiState.Success -> {
                                    if (s.data.isEmpty()) {
                                        item { Text("No works", modifier = Modifier.padding(16.dp)) }
                                    } else {
                                        items(s.data, key = { it.id }) { novel ->
                                            NovelCard(
                                                novel = novel,
                                                onClick = { id -> navigator.push(NovelDetailScreen(id)) },
                                                onUserClick = { id -> navigator.push(UserDetailScreen(id)) },
                                                onSeriesClick = { id -> navigator.push(NovelSeriesScreen(id)) },
                                                onToggleBookmark = { screenModel.toggleNovelBookmark(it) },
                                                modifier = Modifier.padding(horizontal = 16.dp)
                                            )
                                        }
                                        item(key = "created-novels-load-more") {
                                            FeedLoadMoreTrigger(listState) { screenModel.loadMoreCreatedNovels() }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    3 -> {
                        item {
                            Button(
                                onClick = { navigator.push(BrowseHistoryScreen()) },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            ) {
                                Text("打开完整浏览记录")
                            }
                        }
                        if (history.isEmpty()) {
                            item { Text("No browse history", modifier = Modifier.padding(16.dp)) }
                        } else {
                            items(history, key = { it.id }) { illust ->
                                IllustCard(
                                    illust = illust,
                                    onClick = { id -> navigator.push(IllustDetailScreen(id)) },
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 头部入口行：收藏标签 / 小说标记 / 追更 / 关注中 / 粉丝 / 好P友。 */
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
