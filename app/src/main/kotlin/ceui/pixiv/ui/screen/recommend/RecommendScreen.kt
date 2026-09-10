package ceui.pixiv.ui.screen.recommend

import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.pixiv.ui.component.EmptyView
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.FeedPager
import ceui.pixiv.ui.component.IllustCard
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.component.NovelCard
import ceui.pixiv.ui.component.NovelGrid
import ceui.pixiv.ui.component.WorkFeedGrid
import ceui.pixiv.ui.navigation.LocalScrollToTop
import ceui.pixiv.ui.screen.detail.IllustDetailScreen
import ceui.pixiv.ui.screen.novel.NovelDetailScreen
import ceui.pixiv.ui.screen.novel.NovelSeriesScreen
import ceui.pixiv.ui.screen.user.UserDetailScreen
import ceui.pixiv.ui.state.UiState

class RecommendScreen : Screen {

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { RecommendScreenModel() }
        val illustState by screenModel.illustState.collectAsState()
        val mangaState by screenModel.mangaState.collectAsState()
        val novelState by screenModel.novelState.collectAsState()
        val walkState by screenModel.walkState.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        FeedPager(pageLabels = RecommendPage.entries.map { it.label }) { page, isCurrentPage ->
            when (page) {
                0 -> IllustTabContent(
                    state = illustState,
                    isCurrentPage = isCurrentPage,
                    onRefresh = screenModel::refreshIllust,
                    onLoadMore = screenModel::loadMoreIllust,
                    onIllustClick = { id -> navigator.push(IllustDetailScreen(id)) },
                )
                1 -> IllustTabContent(
                    state = mangaState,
                    isCurrentPage = isCurrentPage,
                    onRefresh = screenModel::refreshManga,
                    onLoadMore = screenModel::loadMoreManga,
                    onIllustClick = { id -> navigator.push(IllustDetailScreen(id)) },
                )
                2 -> NovelTabContent(
                    state = novelState,
                    isCurrentPage = isCurrentPage,
                    onRefresh = screenModel::refreshNovel,
                    onLoadMore = screenModel::loadMoreNovel,
                    onNovelClick = { id -> navigator.push(NovelDetailScreen(id)) },
                    onUserClick = { id -> navigator.push(UserDetailScreen(id)) },
                    onSeriesClick = { id -> navigator.push(NovelSeriesScreen(id)) },
                    onToggleBookmark = screenModel::toggleNovelBookmark,
                )
                3 -> IllustTabContent(
                    state = walkState,
                    isCurrentPage = isCurrentPage,
                    onRefresh = screenModel::refreshWalk,
                    onLoadMore = screenModel::loadMoreWalk,
                    onIllustClick = { id -> navigator.push(IllustDetailScreen(id)) },
                )
            }
        }
    }
}

// ----- Illust Grid (shared by 推荐/漫画/最新) -----

@Composable
private fun IllustTabContent(
    state: UiState<List<Illust>>, onRefresh: () -> Unit,
    onLoadMore: () -> Unit, onIllustClick: (Long) -> Unit,
    isCurrentPage: Boolean,
) {
    val gridState = rememberLazyStaggeredGridState()
    val scrollToTopState = LocalScrollToTop.current
    val scrollToTopValue = scrollToTopState.value
    LaunchedEffect(scrollToTopValue, isCurrentPage) {
        if (scrollToTopValue > 0 && isCurrentPage) {
            gridState.scrollToItem(0)
            onRefresh()
            scrollToTopState.value = 0
        }
    }
    val shouldLoadMore by remember {
        derivedStateOf {
            val items = (state as? UiState.Success)?.data ?: return@derivedStateOf false
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= items.size - 5 && items.isNotEmpty()
        }
    }
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) onLoadMore() }

    when (state) {
        is UiState.Loading -> LoadingView()
        is UiState.Error -> ErrorView(state.message, onRefresh)
        is UiState.Success -> if (state.data.isEmpty()) EmptyView("No works")
        else WorkFeedGrid(state = gridState) { _, _ ->
            items(state.data, key = { it.id }) { illust ->
                IllustCard(illust = illust, onClick = onIllustClick)
            }
        }
    }
}

@Composable
private fun NovelTabContent(
    state: UiState<List<Novel>>, onRefresh: () -> Unit,
    onLoadMore: () -> Unit, onNovelClick: (Long) -> Unit,
    onUserClick: (Long) -> Unit, onSeriesClick: (Long) -> Unit,
    onToggleBookmark: (Novel) -> Unit, isCurrentPage: Boolean,
) {
    val gridState = rememberLazyStaggeredGridState()
    val scrollToTopState = LocalScrollToTop.current
    val scrollToTopValue = scrollToTopState.value
    LaunchedEffect(scrollToTopValue, isCurrentPage) {
        if (scrollToTopValue > 0 && isCurrentPage) {
            gridState.scrollToItem(0)
            onRefresh()
            scrollToTopState.value = 0
        }
    }
    val shouldLoadMore by remember {
        derivedStateOf {
            val items = (state as? UiState.Success)?.data ?: return@derivedStateOf false
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= items.size - 5 && items.isNotEmpty()
        }
    }
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) onLoadMore() }

    when (state) {
        is UiState.Loading -> LoadingView()
        is UiState.Error -> ErrorView(state.message, onRefresh)
        is UiState.Success -> if (state.data.isEmpty()) EmptyView("No novels")
        else NovelGrid(gridState = gridState, items = state.data) { novel ->
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
