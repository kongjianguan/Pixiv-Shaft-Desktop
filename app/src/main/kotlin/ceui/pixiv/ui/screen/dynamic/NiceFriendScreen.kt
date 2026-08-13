package ceui.pixiv.ui.screen.dynamic

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.component.IllustCard
import ceui.pixiv.ui.component.WorkFeedGrid
import ceui.pixiv.ui.screen.detail.IllustDetailScreen
import ceui.pixiv.ui.state.Pager
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.ui.util.observeR18Toggle
import ceui.pixiv.ui.util.visibleItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** 好P友作品：mypixiv(互关好友)的插画/漫画作品流，独立页面。 */
class NiceFriendScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { NiceFriendScreenModel() }
        val navigator = LocalNavigator.currentOrThrow
        val state by screenModel.illustState.collectAsState()
        val isRefreshing by screenModel.isRefreshing.collectAsState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("好P友作品") },
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
                val gridState = rememberLazyStaggeredGridState()
                DynamicFeedScaffold(
                    state = state,
                    onLoadMore = screenModel::loadMore,
                    onRefresh = screenModel::refresh,
                    gridState = gridState,
                    emptyMessage = "No works",
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
        }
    }
}

/** 好P友作品流：/v2/illust/mypixiv + Pager 分页 + 三段式状态。 */
class NiceFriendScreenModel : ScreenModel {

    private val client = AppContainer.client
    private val pager = Pager<IllustResponse, Illust>(client, IllustResponse::class.java)

    private val _illustState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val illustState: StateFlow<UiState<List<Illust>>> = _illustState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val loadingMore = AtomicBoolean(false)

    init {
        screenModelScope.launch { fetchInitial() }
        observeR18Toggle(::republishIfLoaded)
    }

    private suspend fun fetchInitial() {
        try {
            fetchCurrent()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _illustState.value = UiState.Error(e.message ?: "Failed to load mypixiv feed")
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
                if (_illustState.value !is UiState.Success) {
                    _illustState.value = UiState.Error(e.message ?: "Failed to load mypixiv feed")
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
                _illustState.value = UiState.Success(visibleItems(pager.items.value))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 加载失败保留已有数据
            } finally {
                loadingMore.set(false)
            }
        }
    }

    private suspend fun fetchCurrent() {
        val resp = client.appApi.getNiceFriendIllust()
        pager.refresh(resp)
        _illustState.value = UiState.Success(visibleItems(pager.items.value))
    }

    /** R18 开关变化时重新过滤已加载内容（Pager 保留完整数据） */
    private fun republishIfLoaded() {
        if (_illustState.value is UiState.Success) {
            _illustState.value = UiState.Success(visibleItems(pager.items.value))
        }
    }
}
