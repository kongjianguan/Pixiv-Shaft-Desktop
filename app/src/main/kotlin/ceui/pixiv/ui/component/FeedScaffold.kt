package ceui.pixiv.ui.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import ceui.pixiv.ui.navigation.LocalScrollToTop
import ceui.pixiv.ui.state.UiState
import kotlinx.coroutines.flow.collectLatest

/**
 * 作品流共享骨架：滚动到底自动加载更多 + Loading/Error/Empty 三段式。
 * 回顶与下拉刷新由各页面自行组合（发现/排行用 PullToRefreshBox，动态页在
 * 外层货架下统一刷新），骨架只负责分页触发与状态展示，避免多份近重复实现漂移。
 */
@Composable
fun <T : Any> FeedScaffold(
    state: UiState<List<T>>,
    gridState: LazyStaggeredGridState,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    emptyMessage: String,
    modifier: Modifier = Modifier,
    successContent: @Composable (List<T>) -> Unit,
) {
    StaggeredGridLoadMoreTrigger(gridState = gridState, onLoadMore = onLoadMore)
    when (state) {
        is UiState.Loading -> LoadingView(modifier)
        is UiState.Error -> ErrorView(state.message, onRefresh, modifier)
        is UiState.Success -> if (state.data.isEmpty()) {
            EmptyView(emptyMessage, modifier)
        } else {
            successContent(state.data)
        }
    }
}

/**
 * 滚动到底自动加载：用 snapshotFlow 持续监听滚动位置与总项数。追加数据后即使
 * 视口内 item 索引不变（超大窗口/紧凑卡片），totalItemsCount 变化也会触发重新
 * 评估，不会卡住不翻页（derivedStateOf 版本在 Profile 上踩过这个坑）。
 */
@Composable
fun StaggeredGridLoadMoreTrigger(
    gridState: LazyStaggeredGridState,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(gridState, onLoadMore) {
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }.collectLatest { (lastIndex, total) ->
            if (lastIndex >= total - 5 && total > 0) onLoadMore()
        }
    }
}

/** [StaggeredGridLoadMoreTrigger] 的 LazyColumn 版本（我的页面等列表式页面用）。 */
@Composable
fun FeedLoadMoreTrigger(
    listState: LazyListState,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(listState, onLoadMore) {
        snapshotFlow {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }.collectLatest { (lastIndex, total) ->
            if (lastIndex >= total - 5 && total > 0) onLoadMore()
        }
    }
}

/**
 * 回顶事件（重复点击当前 tab）响应：滚回顶部并触发一次刷新，然后消费事件。
 * 排行区嵌在外层滚动列表里时外层已消费事件，由 RankingFeedScaffold 改用
 * refreshTick 计数，不经过这里。
 */
@Composable
fun ScrollToTopOnEvent(
    gridState: LazyStaggeredGridState,
    onScrolledToTop: () -> Unit,
) {
    val scrollToTopState = LocalScrollToTop.current
    val scrollToTopValue = scrollToTopState.value
    LaunchedEffect(scrollToTopValue) {
        if (scrollToTopValue > 0) {
            gridState.scrollToItem(0)
            onScrolledToTop()
            scrollToTopState.value = 0
        }
    }
}
