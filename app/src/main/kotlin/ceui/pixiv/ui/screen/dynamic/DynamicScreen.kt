package ceui.pixiv.ui.screen.dynamic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberNavigatorScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.loxia.UserPreview
import ceui.pixiv.ui.component.FeedScaffold
import ceui.pixiv.ui.component.IllustCard
import ceui.pixiv.ui.component.NovelCard
import ceui.pixiv.ui.component.NovelGrid
import ceui.pixiv.ui.component.ScrollToTopOnEvent
import ceui.pixiv.ui.component.UserAvatar
import ceui.pixiv.ui.component.WorkFeedGrid
import ceui.pixiv.ui.screen.detail.IllustDetailScreen
import ceui.pixiv.ui.screen.novel.NovelDetailScreen
import ceui.pixiv.ui.screen.novel.NovelSeriesScreen
import ceui.pixiv.ui.screen.user.UserDetailScreen
import ceui.pixiv.ui.screen.user.UserListMode
import ceui.pixiv.ui.screen.user.UserListScreen
import ceui.pixiv.ui.state.UiState

/** 动态类型：插画 / 漫画 / 小说。 */
private enum class DynamicType(val apiValue: String, val label: String) {
    ILLUST("illust", "插画"),
    MANGA("manga", "漫画"),
    NOVEL("novel", "小说"),
}

/** restrict 筛选：全部 / 公开 / 私人。 */
private enum class DynamicRestrict(val apiValue: String, val label: String) {
    ALL("all", "全部"),
    PUBLIC("public", "公开"),
    PRIVATE("private", "私人"),
}

/** 动态页：已关注画师的最新作品（插画/漫画/小说）+ 推荐用户 + 好P友/关注用户入口。 */
class DynamicScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        var type by remember { mutableStateOf(DynamicType.ILLUST.apiValue) }
        var restrict by remember { mutableStateOf(DynamicRestrict.ALL.apiValue) }

        // 推荐用户货架独立于 type/restrict，切换类型时不重复请求
        val recommendModel = navigator.rememberNavigatorScreenModel(tag = "dynamic-recommended") {
            DynamicRecommendedModel()
        }
        val recommendedState by recommendModel.recommendedState.collectAsState()
        val recommendRefreshing by recommendModel.isRefreshing.collectAsState()

        // 作品流按 (type, restrict) 各自独立；模型随 Navigator 常驻，
        // 切换组合时旧模型保留已加载数据（有界累积，见 RankingFeed 同款说明）
        val feedModel = navigator.rememberNavigatorScreenModel(tag = "dynamic-$type-$restrict") {
            DynamicScreenModel(type, restrict)
        }
        val feedRefreshing by feedModel.isRefreshing.collectAsState()

        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部操作区（固定，不走 LazyColumn）
            DynamicControls(
                type = type,
                restrict = restrict,
                onTypeChange = { type = it },
                onRestrictChange = { restrict = it },
                onNiceFriendClick = { navigator.push(NiceFriendScreen()) },
                onFollowingClick = { navigator.push(UserListScreen("关注中", UserListMode.FOLLOWING)) },
            )

            // 下拉刷新区：推荐用户货架 + 作品列表
            PullToRefreshBox(
                isRefreshing = feedRefreshing || recommendRefreshing,
                onRefresh = {
                    recommendModel.refresh()
                    feedModel.refresh()
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    RecommendedUsersShelf(
                        state = recommendedState,
                        onUserClick = { id -> navigator.push(UserDetailScreen(id)) },
                    )
                    // 内容区：切换 type/restrict 时按 key 重建列表
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        key("$type-$restrict") {
                            DynamicFeedContent(
                                type = type,
                                model = feedModel,
                                onIllustClick = { id -> navigator.push(IllustDetailScreen(id)) },
                                onNovelClick = { id -> navigator.push(NovelDetailScreen(id)) },
                                onUserClick = { id -> navigator.push(UserDetailScreen(id)) },
                                onSeriesClick = { id -> navigator.push(NovelSeriesScreen(id)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DynamicControls(
    type: String,
    restrict: String,
    onTypeChange: (String) -> Unit,
    onRestrictChange: (String) -> Unit,
    onNiceFriendClick: () -> Unit,
    onFollowingClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DynamicType.entries.forEach { t ->
                FilterChip(
                    selected = type == t.apiValue,
                    onClick = { onTypeChange(t.apiValue) },
                    label = { Text(t.label) },
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onNiceFriendClick) { Text("好P友作品") }
            TextButton(onClick = onFollowingClick) { Text("关注用户") }
        }
        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DynamicRestrict.entries.forEach { r ->
                FilterChip(
                    selected = restrict == r.apiValue,
                    onClick = { onRestrictChange(r.apiValue) },
                    label = { Text(r.label) },
                )
            }
        }
    }
}

/** 推荐用户货架：加载失败/为空时静默隐藏，不阻塞主列表。 */
@Composable
private fun RecommendedUsersShelf(
    state: UiState<List<UserPreview>>,
    onUserClick: (Long) -> Unit,
) {
    if (state !is UiState.Success) return
    val users = state.data.mapNotNull { it.user }.filter { it.id > 0 }
    if (users.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "推荐用户",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            users.forEach { user ->
                item(key = user.id) {
                    Column(
                        modifier = Modifier
                            .width(72.dp)
                            .clickable { onUserClick(user.id) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        UserAvatar(
                            url = user.profile_image_urls?.px_50x50 ?: user.profile_image_urls?.medium,
                            size = 56,
                        )
                        Text(
                            text = user.name ?: "Unknown",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 内容区：按 type 分发到插画/漫画网格或小说网格。 */
@Composable
private fun DynamicFeedContent(
    type: String,
    model: DynamicScreenModel,
    onIllustClick: (Long) -> Unit,
    onNovelClick: (Long) -> Unit,
    onUserClick: (Long) -> Unit,
    onSeriesClick: (Long) -> Unit,
) {
    when (type) {
        DynamicType.NOVEL.apiValue -> {
            val state by model.novelState.collectAsState()
            DynamicNovelFeed(
                state = state,
                onLoadMore = model::loadMore,
                onRefresh = model::refresh,
                onNovelClick = onNovelClick,
                onUserClick = onUserClick,
                onSeriesClick = onSeriesClick,
                onToggleBookmark = model::toggleNovelBookmark,
            )
        }
        else -> {
            val state by model.illustState.collectAsState()
            DynamicIllustFeed(
                state = state,
                onLoadMore = model::loadMore,
                onRefresh = model::refresh,
                onIllustClick = onIllustClick,
            )
        }
    }
}

/** 动态流共享骨架：滚动回顶 + 分页触发与 Loading/Error/Empty 状态（复用 FeedScaffold）。 */
@Composable
internal fun <T : Any> DynamicFeedScaffold(
    state: UiState<List<T>>,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    gridState: LazyStaggeredGridState,
    emptyMessage: String,
    successContent: @Composable (List<T>) -> Unit,
) {
    ScrollToTopOnEvent(gridState = gridState, onScrolledToTop = onRefresh)
    FeedScaffold(
        state = state,
        gridState = gridState,
        onLoadMore = onLoadMore,
        onRefresh = onRefresh,
        emptyMessage = emptyMessage,
        modifier = Modifier.fillMaxSize(),
        successContent = successContent,
    )
}

@Composable
private fun DynamicIllustFeed(
    state: UiState<List<Illust>>,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onIllustClick: (Long) -> Unit,
) {
    val gridState = rememberLazyStaggeredGridState()
    DynamicFeedScaffold(
        state = state,
        onLoadMore = onLoadMore,
        onRefresh = onRefresh,
        gridState = gridState,
        emptyMessage = "No works",
    ) { items ->
        WorkFeedGrid(state = gridState) { _, _ ->
            items(items, key = { it.id }) { illust ->
                IllustCard(illust = illust, onClick = onIllustClick)
            }
        }
    }
}

@Composable
private fun DynamicNovelFeed(
    state: UiState<List<Novel>>,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onNovelClick: (Long) -> Unit,
    onUserClick: (Long) -> Unit,
    onSeriesClick: (Long) -> Unit,
    onToggleBookmark: (Novel) -> Unit,
) {
    val gridState = rememberLazyStaggeredGridState()
    DynamicFeedScaffold(
        state = state,
        onLoadMore = onLoadMore,
        onRefresh = onRefresh,
        gridState = gridState,
        emptyMessage = "No novels",
    ) { items ->
        NovelGrid(gridState = gridState, items = items) { novel ->
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
