package ceui.pixiv.ui.component

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberNavigatorScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.navigation.LocalScrollToTop
import ceui.pixiv.ui.screen.detail.IllustDetailScreen
import ceui.pixiv.ui.state.Pager
import ceui.pixiv.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * 通用排行流组件：按 [mode] 拉取 /v1/illust/ranking 并分页加载。
 *
 * 说明：Voyager 1.0.1 的 Screen.rememberScreenModel 以 (screen, class) 为 key，
 * 同一屏幕内只能有一个同类型模型，无法支持「同屏多个 mode 各自独立」。因此这里用
 * Navigator 作用域的 rememberNavigatorScreenModel + mode 作为 tag，每个 mode 一个独立
 * ScreenModel，切换 mode（Discover）或多页面（R18）时各 Feed 互不干扰。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RankingFeed(mode: String, modifier: Modifier = Modifier) {
    val navigator = LocalNavigator.currentOrThrow
    val screenModel = navigator.rememberNavigatorScreenModel(tag = mode) { RankingFeedScreenModel(mode) }
    val state by screenModel.state.collectAsState()
    val isRefreshing by screenModel.isRefreshing.collectAsState()

    val gridState = rememberLazyStaggeredGridState()

    val scrollToTopState = LocalScrollToTop.current
    val scrollToTopValue = scrollToTopState.value
    LaunchedEffect(scrollToTopValue) {
        if (scrollToTopValue > 0) {
            gridState.scrollToItem(0)
            screenModel.refresh()
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
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) screenModel.loadMore() }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { screenModel.refresh() },
        modifier = modifier.fillMaxSize()
    ) {
        when (val currentState = state) {
            is UiState.Loading -> LoadingView()
            is UiState.Error -> ErrorView(currentState.message, { screenModel.refresh() })
            is UiState.Success -> if (currentState.data.isEmpty()) {
                EmptyView("No works")
            } else {
                WorkFeedGrid(state = gridState) { _, _ ->
                    items(currentState.data, key = { it.id }) { illust ->
                        IllustCard(
                            illust = illust,
                            onClick = { id -> navigator.push(IllustDetailScreen(id)) }
                        )
                    }
                }
            }
        }
    }
}

class RankingFeedScreenModel(private val mode: String) : ScreenModel {

    private val client = AppContainer.client
    private val pager = Pager<IllustResponse, Illust>(client, IllustResponse::class.java)

    private val _state = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Illust>>> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val loadingMore = AtomicBoolean(false)

    init {
        screenModelScope.launch { fetchInitial() }
    }

    private suspend fun fetchInitial() {
        try {
            val resp = client.appApi.getRankingIllusts(mode)
            pager.refresh(resp)
            _state.value = UiState.Success(pager.items.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = UiState.Error(e.message ?: "Failed to load ranking")
        }
    }

    fun refresh() {
        screenModelScope.launch {
            _isRefreshing.value = true
            try {
                val resp = client.appApi.getRankingIllusts(mode)
                pager.refresh(resp)
                _state.value = UiState.Success(pager.items.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 刷新失败时保留已有数据，只有尚无数据时才切到 Error
                if (_state.value !is UiState.Success) {
                    _state.value = UiState.Error(e.message ?: "Failed to load ranking")
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun loadMore() {
        if (!pager.hasNext.value || !loadingMore.compareAndSet(false, true)) return
        screenModelScope.launch {
            try {
                pager.loadMore()
                _state.value = UiState.Success(pager.items.value)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 加载失败保留已有数据
            } finally {
                loadingMore.set(false)
            }
        }
    }
}
