package ceui.pixiv.ui.screen.collection

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.KListShow
import ceui.loxia.Novel
import ceui.loxia.NovelResponse
import ceui.pixiv.di.AppContainer
import ceui.pixiv.net.api.Client
import ceui.pixiv.store.SettingsStore
import ceui.pixiv.ui.component.FeedScaffold
import ceui.pixiv.ui.component.IllustCard
import ceui.pixiv.ui.component.NovelCard
import ceui.pixiv.ui.component.NovelGrid
import ceui.pixiv.ui.component.WorkFeedGrid
import ceui.pixiv.ui.screen.detail.IllustDetailScreen
import ceui.pixiv.ui.screen.novel.NovelDetailScreen
import ceui.pixiv.ui.screen.novel.NovelSeriesScreen
import ceui.pixiv.ui.screen.user.UserDetailScreen
import ceui.pixiv.ui.state.PagedFeed
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.ui.util.observeR18Toggle
import ceui.pixiv.ui.util.visibleItems
import ceui.pixiv.ui.util.visibleNovels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** 按标签筛选收藏列表页：type="illust"/"novel"，tag 为空时即全部收藏。 */
class BookmarkedListScreen(
    private val userId: Long,
    private val type: String,
    private val tag: String?,
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { BookmarkedListScreenModel(userId, type, tag) }
        val navigator = LocalNavigator.currentOrThrow
        val isRefreshing by screenModel.isRefreshing.collectAsState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(tag ?: "按标签筛选") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            }
        ) { padding ->
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { screenModel.refresh() },
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                when (type) {
                    "illust" -> IllustTaggedList(screenModel)
                    else -> NovelTaggedList(screenModel)
                }
            }
        }
    }
}

@Composable
private fun IllustTaggedList(screenModel: BookmarkedListScreenModel) {
    val navigator = LocalNavigator.currentOrThrow
    val state by screenModel.illustState.collectAsState()
    val gridState = rememberLazyStaggeredGridState()
    TaggedGridScaffold(state, gridState, screenModel, "No works") { items ->
        WorkFeedGrid(state = gridState) { _, _ ->
            items(items, key = { it.id }) { illust ->
                IllustCard(
                    illust = illust,
                    onClick = { id -> navigator.push(IllustDetailScreen(id)) },
                )
            }
        }
    }
}

@Composable
private fun NovelTaggedList(screenModel: BookmarkedListScreenModel) {
    val navigator = LocalNavigator.currentOrThrow
    val state by screenModel.novelState.collectAsState()
    val gridState = rememberLazyStaggeredGridState()
    TaggedGridScaffold(state, gridState, screenModel, "No works") { items ->
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

/** 网格列表公共骨架：分页触发 + Loading/Error/Empty 三段式（复用 FeedScaffold）。 */
@Composable
private fun <T : Any> TaggedGridScaffold(
    state: UiState<List<T>>,
    gridState: LazyStaggeredGridState,
    screenModel: BookmarkedListScreenModel,
    emptyMessage: String,
    successContent: @Composable (List<T>) -> Unit,
) {
    FeedScaffold(
        state = state,
        gridState = gridState,
        onLoadMore = { screenModel.loadMore() },
        onRefresh = { screenModel.refresh() },
        // R18 开关关闭且数据被过滤时提示隐藏而非「没有数据」
        emptyMessage = if (screenModel.hasHiddenR18()) "R18 内容已隐藏（可在设置中开启）" else emptyMessage,
        successContent = successContent,
    )
}

/** 按标签筛选收藏模型：type 决定走插画还是小说 feed。 */
class BookmarkedListScreenModel(
    private val userId: Long,
    private val type: String,
    private val tag: String?,
    private val client: Client = AppContainer.client,
    private val settingsStore: SettingsStore = AppContainer.settingsStore,
) : ScreenModel {

    private val illustFeed = PagedFeed<IllustResponse, Illust>(client, IllustResponse::class.java) {
        visibleItems(it, settingsStore.isShowR18)
    }
    private val novelFeed = PagedFeed<NovelResponse, Novel>(client, NovelResponse::class.java) {
        visibleNovels(it, settingsStore.isShowR18)
    }

    val illustState: StateFlow<UiState<List<Illust>>> = illustFeed.state

    val novelState: StateFlow<UiState<List<Novel>>> = novelFeed.state

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

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
            setError(e.message ?: "Failed to load bookmarks")
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
                // 刷新失败时保留已有数据
                if (currentState() !is UiState.Success) {
                    setError(e.message ?: "Failed to load bookmarks")
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun loadMore() {
        if (type == "illust") loadMore(illustFeed) else loadMore(novelFeed)
    }

    private fun <Response : KListShow<Item>, Item : Any> loadMore(
        feed: PagedFeed<Response, Item>,
    ) {
        if (!feed.tryBeginLoadMore()) return
        screenModelScope.launch {
            try {
                feed.loadMoreAndPublish()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 加载失败保留已有数据
            } finally {
                feed.endLoadMore()
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

    private fun updateNovelBookmark(novelId: Long, isBookmarked: Boolean) {
        novelFeed.pager.updateItems { items ->
            items.map { item ->
                if (item.id == novelId) item.copy(is_bookmarked = isBookmarked) else item
            }
        }
        novelFeed.publish()
    }

    private suspend fun fetchCurrent() {
        if (type == "illust") {
            val resp = client.appApi.getUserBookmarkedIllusts(userId, "public", tag)
            illustFeed.refreshUntilVisible(resp)
        } else {
            val resp = client.appApi.getUserBookmarkedNovels(userId, "public", tag)
            novelFeed.refreshUntilVisible(resp)
        }
    }

    private fun currentState(): UiState<*> =
        if (type == "illust") illustFeed.state.value else novelFeed.state.value

    /** 当前开关状态下，已加载数据中是否有被 R18 过滤隐藏的作品（空态时区分「真没有」与「被隐藏」） */
    fun hasHiddenR18(): Boolean {
        val raw = if (type == "illust") illustFeed.pager.items.value else novelFeed.pager.items.value
        return ceui.pixiv.ui.util.hasHiddenR18(raw, settingsStore.isShowR18)
    }

    /** R18 开关变化时重新过滤已加载内容（feed 保留完整数据） */
    private fun republishIfLoaded() {
        if (type == "illust") illustFeed.republishIfLoaded() else novelFeed.republishIfLoaded()
    }

    private fun setError(message: String) {
        if (type == "illust") illustFeed.setError(message) else novelFeed.setError(message)
    }
}
