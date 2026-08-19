package ceui.pixiv.ui.component

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberNavigatorScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.Novel
import ceui.loxia.NovelResponse
import ceui.pixiv.di.AppContainer
import ceui.pixiv.net.api.Client
import ceui.pixiv.store.SettingsStore
import ceui.pixiv.ui.navigation.LocalScrollToTop
import ceui.pixiv.ui.screen.detail.IllustDetailScreen
import ceui.pixiv.ui.screen.novel.NovelDetailScreen
import ceui.pixiv.ui.screen.novel.NovelSeriesScreen
import ceui.pixiv.ui.screen.user.UserDetailScreen
import ceui.pixiv.ui.state.Pager
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.ui.util.observeR18Toggle
import ceui.pixiv.ui.util.visibleNovels
import ceui.pixiv.ui.util.visibleItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** 排行类型：插画排行 / 小说排行。 */
enum class RankingType { ILLUST, NOVEL }

/**
 * 通用排行流组件：按 [mode] 拉取 /v1/illust/ranking 或 /v1/novel/ranking 并分页加载。
 *
 * 说明：Voyager 1.0.1 的 Screen.rememberScreenModel 以 (screen, class) 为 key，
 * 同一屏幕内只能有一个同类型模型，无法支持「同屏多个 mode 各自独立」。因此这里用
 * Navigator 作用域的 rememberNavigatorScreenModel + tag 作为 key，每个 mode/type/date
 * 组合一个独立 ScreenModel，切换 mode（Discover）或多页面（R18）时各 Feed 互不干扰。
 * 这些模型随 Navigator 常驻（切走不销毁，保留已加载数据）；date 选择器每选一个日期
 * 就保留一个模型，累积有界（实际使用的日期数有限），不做额外回收。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankingFeed(
    mode: String,
    type: RankingType = RankingType.ILLUST,
    date: String? = null,
    modifier: Modifier = Modifier,
    // 外部回顶/刷新计数：嵌入外层滚动列表时外层会先消费 LocalScrollToTop 并归零，
    // 排行区滚出可视区后组合销毁、重组合时读不到事件；用不归零的递增计数替代。
    // null 时（如 R18 页独占整屏）沿用 LocalScrollToTop 机制。
    refreshTick: StateFlow<Long>? = null,
) {
    val navigator = LocalNavigator.currentOrThrow
    // tag 必须包含 type/date，否则插画/小说或不同日期会共享同一个 ScreenModel
    val screenModel = navigator.rememberNavigatorScreenModel(tag = "$type-$mode-$date") {
        RankingFeedScreenModel(
            mode = mode,
            type = type,
            date = date,
            // 新模型直接以当前计数为已消费值：首次组合不再额外触发一次刷新，
            // 已加载模型在外部刷新事件时（tick 递增）仍会正常刷新
            initialRefreshTick = refreshTick?.value ?: 0L,
        )
    }
    val isRefreshing by screenModel.isRefreshing.collectAsState()

    when (type) {
        RankingType.ILLUST -> {
            val state by screenModel.illustState.collectAsState()
            IllustRankingFeed(screenModel, state, isRefreshing, modifier, refreshTick)
        }
        RankingType.NOVEL -> {
            val state by screenModel.novelState.collectAsState()
            NovelRankingFeed(screenModel, state, isRefreshing, modifier, refreshTick)
        }
    }
}

/** 排行流共享骨架：滚动回顶、下拉刷新和 Loading/Error/Empty 状态（分页触发复用 FeedScaffold）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T : Any> RankingFeedScaffold(
    state: UiState<List<T>>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    gridState: LazyStaggeredGridState,
    modifier: Modifier,
    emptyMessage: String,
    refreshTick: StateFlow<Long>?,
    appliedRefreshTick: StateFlow<Long>,
    onTickApplied: (Long) -> Unit,
    successContent: @Composable (List<T>) -> Unit,
) {
    val scrollToTopState = LocalScrollToTop.current
    val scrollToTopValue = scrollToTopState.value
    LaunchedEffect(scrollToTopValue) {
        // 外部传入 refreshTick 时，回顶/刷新由外层页面统一递增计数（下面的 tick 分支处理）；
        // 这里再消费 LocalScrollToTop 会让同一事件触发两次刷新。独立页（refreshTick = null）
        // 仍沿用 LocalScrollToTop 机制。
        if (refreshTick != null) return@LaunchedEffect
        if (scrollToTopValue > 0) {
            gridState.scrollToItem(0)
            onRefresh()
            scrollToTopState.value = 0
        }
    }

    // 外部回顶计数：不归零的递增计数。已消费计数存在 ScreenModel 里而不是组件本地，
    // 排行区滚出/滚回视口（组合销毁重建）不会因本地状态重置而重复刷新；只有 tick 确实
    // 前进过（包括排行区不可见期间错过的刷新事件）才补一次刷新。
    val tick by refreshTick?.collectAsState() ?: remember { mutableStateOf(0L) }
    val appliedTick by appliedRefreshTick.collectAsState()
    LaunchedEffect(tick) {
        if (refreshTick != null && tick != appliedTick) {
            onTickApplied(tick)
            gridState.scrollToItem(0)
            onRefresh()
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize()
    ) {
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
}

@Composable
private fun IllustRankingFeed(
    screenModel: RankingFeedScreenModel,
    state: UiState<List<Illust>>,
    isRefreshing: Boolean,
    modifier: Modifier,
    refreshTick: StateFlow<Long>?,
) {
    val navigator = LocalNavigator.currentOrThrow
    val gridState = rememberLazyStaggeredGridState()
    RankingFeedScaffold(
        state = state,
        isRefreshing = isRefreshing,
        onRefresh = { screenModel.refresh() },
        onLoadMore = { screenModel.loadMore() },
        gridState = gridState,
        modifier = modifier,
        emptyMessage = "No works",
        refreshTick = refreshTick,
        appliedRefreshTick = screenModel.appliedRefreshTick,
        onTickApplied = { screenModel.appliedRefreshTick.value = it },
    ) { items ->
        WorkFeedGrid(state = gridState) { _, _ ->
            items(items, key = { it.id }) { illust ->
                IllustCard(
                    illust = illust,
                    onClick = { id -> navigator.push(IllustDetailScreen(id)) }
                )
            }
        }
    }
}

@Composable
private fun NovelRankingFeed(
    screenModel: RankingFeedScreenModel,
    state: UiState<List<Novel>>,
    isRefreshing: Boolean,
    modifier: Modifier,
    refreshTick: StateFlow<Long>?,
) {
    val navigator = LocalNavigator.currentOrThrow
    val gridState = rememberLazyStaggeredGridState()
    RankingFeedScaffold(
        state = state,
        isRefreshing = isRefreshing,
        onRefresh = { screenModel.refresh() },
        onLoadMore = { screenModel.loadMore() },
        gridState = gridState,
        modifier = modifier,
        emptyMessage = "No novels",
        refreshTick = refreshTick,
        appliedRefreshTick = screenModel.appliedRefreshTick,
        onTickApplied = { screenModel.appliedRefreshTick.value = it },
    ) { items ->
        NovelGrid(gridState = gridState, items = items) { novel ->
            NovelCard(
                novel = novel,
                onClick = { id -> navigator.push(NovelDetailScreen(id)) },
                onUserClick = { id -> navigator.push(UserDetailScreen(id)) },
                onSeriesClick = { id -> navigator.push(NovelSeriesScreen(id)) },
                onToggleBookmark = { screenModel.toggleNovelBookmark(it) },
            )
        }
    }
}

class RankingFeedScreenModel(
    private val mode: String,
    private val type: RankingType,
    private val date: String?,
    initialRefreshTick: Long = 0L,
    private val client: Client = AppContainer.client,
    private val settingsStore: SettingsStore = AppContainer.settingsStore,
) : ScreenModel {

    private val illustPager = Pager<IllustResponse, Illust>(client, IllustResponse::class.java)
    private val novelPager = Pager<NovelResponse, Novel>(client, NovelResponse::class.java)

    private val _illustState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val illustState: StateFlow<UiState<List<Illust>>> = _illustState.asStateFlow()

    private val _novelState = MutableStateFlow<UiState<List<Novel>>>(UiState.Loading)
    val novelState: StateFlow<UiState<List<Novel>>> = _novelState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** 已消费的最新外部刷新计数；存模型而非组件，排行区滚出/滚回不会重复刷新 */
    val appliedRefreshTick = MutableStateFlow(initialRefreshTick)

    private val loadingMore = AtomicBoolean(false)
    private val novelBookmarksInFlight = ConcurrentHashMap.newKeySet<Long>()

    init {
        screenModelScope.launch { fetchInitial() }
        observeR18Toggle(::republishIfLoaded, settingsStore)
    }

    private suspend fun fetchInitial() {
        try {
            fetchCurrent()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setError(e.message ?: "Failed to load ranking")
        }
    }

    fun refresh() {
        screenModelScope.launch {
            _isRefreshing.value = true
            try {
                fetchCurrent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 刷新失败时保留已有数据，只有尚无数据时才切到 Error
                if (currentState() !is UiState.Success) {
                    setError(e.message ?: "Failed to load ranking")
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun loadMore() {
        val pager = if (type == RankingType.ILLUST) illustPager else novelPager
        if (!pager.hasNext.value || !loadingMore.compareAndSet(false, true)) return
        screenModelScope.launch {
            try {
                pager.loadMore()
                setSuccess()
                // 首页被 R18 过滤后整页为空：继续翻页直到出现可见内容或没有更多页
                pager.loadMoreUntil(::hasVisibleContent, ::setSuccess)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 加载失败保留已有数据
            } finally {
                loadingMore.set(false)
            }
        }
    }

    fun toggleNovelBookmark(novel: Novel) {
        ceui.pixiv.ui.util.toggleNovelBookmark(
            scope = screenModelScope,
            client = client,
            novel = novel,
            inFlight = novelBookmarksInFlight,
            updateLocal = ::updateNovelBookmark,
        )
    }

    private suspend fun fetchCurrent() {
        val pager = if (type == RankingType.ILLUST) illustPager else novelPager
        when (type) {
            RankingType.ILLUST -> {
                val resp = client.appApi.getRankingIllusts(mode, date)
                illustPager.refresh(resp)
            }
            RankingType.NOVEL -> {
                val resp = client.appApi.getRankingNovels(mode, date)
                novelPager.refresh(resp)
            }
        }
        setSuccess()
        // 首页被 R18 过滤后整页为空（且还有下一页）时自动翻页，避免卡在空态
        pager.loadMoreUntil(::hasVisibleContent, ::setSuccess)
    }

    private fun hasVisibleContent(): Boolean = when (type) {
        RankingType.ILLUST -> (_illustState.value as? UiState.Success)?.data?.isNotEmpty() == true
        RankingType.NOVEL -> (_novelState.value as? UiState.Success)?.data?.isNotEmpty() == true
    }

    private fun setSuccess() {
        when (type) {
            RankingType.ILLUST -> _illustState.value = UiState.Success(visibleItems(illustPager.items.value, settingsStore.isShowR18))
            RankingType.NOVEL -> _novelState.value = UiState.Success(visibleNovels(novelPager.items.value, settingsStore.isShowR18))
        }
    }

    /** 与 Recommend/Dynamic 的小说流一致：过滤 R18 与 visible=false（列表接口间歇返回，详情页会 crash） */
    private fun visibleNovels(): List<Novel> =
        visibleNovels(novelPager.items.value, settingsStore.isShowR18)

    private fun setError(message: String) {
        when (type) {
            RankingType.ILLUST -> _illustState.value = UiState.Error(message)
            RankingType.NOVEL -> _novelState.value = UiState.Error(message)
        }
    }

    private fun currentState(): UiState<*> = when (type) {
        RankingType.ILLUST -> _illustState.value
        RankingType.NOVEL -> _novelState.value
    }

    private fun updateNovelBookmark(novelId: Long, isBookmarked: Boolean) {
        novelPager.updateItems { items ->
            items.map { item ->
                if (item.id == novelId) item.copy(is_bookmarked = isBookmarked) else item
            }
        }
        // 与 setSuccess 一致，重新过一遍 R18 过滤，避免收藏操作把未过滤的 pager 数据发布出去
        _novelState.value = UiState.Success(visibleNovels())
    }

    /** R18 开关变化时重新过滤已加载内容（Pager 保留完整数据） */
    private fun republishIfLoaded() {
        if (_illustState.value is UiState.Success) {
            _illustState.value = UiState.Success(visibleItems(illustPager.items.value, settingsStore.isShowR18))
        }
        if (_novelState.value is UiState.Success) {
            _novelState.value = UiState.Success(visibleNovels(novelPager.items.value, settingsStore.isShowR18))
        }
    }
}
