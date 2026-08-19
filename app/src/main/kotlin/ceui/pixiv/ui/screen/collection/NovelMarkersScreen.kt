package ceui.pixiv.ui.screen.collection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.lisa.models.MarkedNovelItem
import ceui.loxia.NovelMarkersResponse
import ceui.pixiv.di.AppContainer
import ceui.pixiv.net.api.Client
import ceui.pixiv.store.SettingsStore
import ceui.pixiv.ui.component.EmptyView
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.screen.novel.NovelDetailScreen
import ceui.pixiv.ui.state.Pager
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.ui.state.hasVisibleContent
import ceui.pixiv.ui.util.observeR18Toggle
import ceui.pixiv.ui.util.visibleMarkedNovels
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** 小说标记（书签）列表页：/v2/novel/markers，点击整卡进入小说详情。 */
class NovelMarkersScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { NovelMarkersScreenModel() }
        val navigator = LocalNavigator.currentOrThrow
        val state by screenModel.state.collectAsState()
        val isRefreshing by screenModel.isRefreshing.collectAsState()
        val listState = rememberLazyListState()

        val shouldLoadMore by remember(state) {
            derivedStateOf {
                val items = (state as? UiState.Success)?.data ?: return@derivedStateOf false
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= items.size - 5 && items.isNotEmpty()
            }
        }
        LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) screenModel.loadMore() }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("小说标记") },
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
                when (val s = state) {
                    is UiState.Loading -> LoadingView()
                    is UiState.Error -> ErrorView(s.message, { screenModel.refresh() })
                    is UiState.Success -> {
                        // lateinit 字段未初始化时跳过该条目（理论不会发生，防御而已）
                        val items = s.data
                        if (items.isEmpty()) {
                            // R18 开关关闭且数据被过滤时提示隐藏而非「没有数据」
                            EmptyView(
                                if (screenModel.hasHiddenR18()) "R18 内容已隐藏（可在设置中开启）"
                                else "No markers"
                            )
                        } else {
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                items(items, key = { it.novel.id }) { item ->
                                    NovelMarkerRow(item = item, onClick = {
                                        navigator.push(NovelDetailScreen(item.novel.id.toLong()))
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 小说标记行卡：封面 + 标题 + 作者 + 「读到第 N 页」。 */
@Composable
private fun NovelMarkerRow(item: MarkedNovelItem, onClick: () -> Unit) {
    val novel = item.novel
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(modifier = Modifier.padding(10.dp)) {
            AsyncImage(
                model = novel.image_urls?.medium ?: novel.image_urls?.large,
                contentDescription = novel.title,
                modifier = Modifier
                    .width(72.dp)
                    .aspectRatio(240f / 338f)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = novel.title ?: "Untitled",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!novel.user?.name.isNullOrEmpty()) {
                    Text(
                        text = novel.user?.name.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    text = "读到第 ${item.novel_marker.page} 页",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** 小说标记模型：Pager 分页 + 三段式。接口无需 user_id，直接请求自己。 */
class NovelMarkersScreenModel(
    private val client: Client = AppContainer.client,
    private val settingsStore: SettingsStore = AppContainer.settingsStore,
) : ScreenModel {

    private val pager = Pager<NovelMarkersResponse, MarkedNovelItem>(client, NovelMarkersResponse::class.java)

    private val _state = MutableStateFlow<UiState<List<MarkedNovelItem>>>(UiState.Loading)
    val state: StateFlow<UiState<List<MarkedNovelItem>>> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val loadingMore = AtomicBoolean(false)

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
            _state.value = UiState.Error(e.message ?: "Failed to load markers")
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
                if (_state.value !is UiState.Success) {
                    _state.value = UiState.Error(e.message ?: "Failed to load markers")
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
                publishItems()
                // R18 或 visible=false 可能让整页都不可展示；继续翻页避免空列表无法触发加载。
                pager.loadMoreUntil(::hasVisibleContent, ::publishItems)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 加载失败保留已有数据
            } finally {
                loadingMore.set(false)
            }
        }
    }

    /** 当前开关状态下，已加载数据中是否有被 R18 过滤隐藏的作品（空态时区分「真没有」与「被隐藏」） */
    fun hasHiddenR18(): Boolean = ceui.pixiv.ui.util.hasHiddenR18(pager.items.value, settingsStore.isShowR18)

    private suspend fun fetchCurrent() {
        val resp = client.appApi.getNovelMarkers()
        pager.refresh(resp)
        publishItems()
        pager.loadMoreUntil(::hasVisibleContent, ::publishItems)
    }

    /** R18 开关变化时重新过滤已加载内容（Pager 保留完整数据） */
    private fun republishIfLoaded() {
        if (_state.value is UiState.Success) {
            publishItems()
        }
    }

    private fun hasVisibleContent(): Boolean =
        _state.value.hasVisibleContent()

    private fun publishItems() {
        _state.value = UiState.Success(visibleMarkedNovels(pager.items.value, settingsStore.isShowR18))
    }
}
