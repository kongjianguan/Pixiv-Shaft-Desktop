package ceui.pixiv.ui.screen.collection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.loxia.BookmarkTag
import ceui.loxia.BookmarkTagsResponse
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.component.EmptyView
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.state.PagedFeed
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.ui.util.resolveSelfUserId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** 收藏标签页：插画/小说两个 tab，点击标签跳转按标签筛选的收藏列表。 */
class BookmarkTagsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { BookmarkTagsScreenModel() }
        val navigator = LocalNavigator.currentOrThrow
        val illustState by screenModel.illustState.collectAsState()
        val novelState by screenModel.novelState.collectAsState()
        val isRefreshing by screenModel.isRefreshing.collectAsState()
        var selectedTab by remember { mutableStateOf(0) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("收藏标签") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("插画") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("小说") }
                    )
                }
                val isIllust = selectedTab == 0
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { screenModel.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val state = if (isIllust) illustState else novelState
                    // 每个 tab 独立滚动位置，切换 tab 时列表回到顶部
                    val listState = remember(selectedTab) { LazyListState() }
                    val shouldLoadMore by remember(state, isIllust) {
                        derivedStateOf {
                            val items = (state as? UiState.Success)?.data ?: return@derivedStateOf false
                            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                            lastVisible >= items.size - 5 && items.isNotEmpty()
                        }
                    }
                    LaunchedEffect(shouldLoadMore, isIllust) {
                        if (shouldLoadMore) screenModel.loadMore(isIllust)
                    }
                    when (state) {
                        is UiState.Loading -> LoadingView()
                        is UiState.Error -> ErrorView(state.message, { screenModel.refresh() })
                        is UiState.Success -> {
                            val tags = state.data
                            if (tags.isEmpty()) {
                                EmptyView("No tags")
                            } else {
                                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                    items(tags, key = { it.name }) { tag ->
                                        BookmarkTagRow(tag = tag, onClick = {
                                            navigator.push(
                                                BookmarkedListScreen(screenModel.userId, if (isIllust) "illust" else "novel", tag.name)
                                            )
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
}

/** 收藏标签行卡：标签名 + 译名（灰）+ 数量徽标。 */
@Composable
private fun BookmarkTagRow(tag: BookmarkTag, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tag.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val translated = tag.translated_name
                if (!translated.isNullOrEmpty()) {
                    Text(
                        text = translated,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = "${tag.count}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** 收藏标签模型：插画/小说两个独立 Pager，懒加载 selfUserId。 */
class BookmarkTagsScreenModel : ScreenModel {

    private val client = AppContainer.client
    private val illustFeed = PagedFeed<BookmarkTagsResponse, BookmarkTag>(client, BookmarkTagsResponse::class.java)
    private val novelFeed = PagedFeed<BookmarkTagsResponse, BookmarkTag>(client, BookmarkTagsResponse::class.java)

    val illustState: StateFlow<UiState<List<BookmarkTag>>> = illustFeed.state

    val novelState: StateFlow<UiState<List<BookmarkTag>>> = novelFeed.state

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    var userId: Long = 0L
        private set

    init {
        screenModelScope.launch { fetchInitial() }
    }

    private suspend fun fetchInitial() {
        try {
            userId = client.resolveSelfUserId()
            fetchBoth()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "Failed to load tags"
            illustFeed.setError(message)
            novelFeed.setError(message)
        }
    }

    fun refresh() {
        screenModelScope.launch {
            _isRefreshing.value = true
            try {
                // 解析器进程级缓存，重复调用不再发请求
                userId = client.resolveSelfUserId()
                fetchBoth()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 刷新失败时保留已有数据，只有尚无数据时才切到 Error
                val message = e.message ?: "Failed to load tags"
                if (!illustFeed.isSuccess()) illustFeed.setError(message)
                if (!novelFeed.isSuccess()) novelFeed.setError(message)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun loadMore(isIllust: Boolean) {
        if (isIllust) loadMore(illustFeed) else loadMore(novelFeed)
    }

    private fun <Response : ceui.loxia.KListShow<Item>, Item : Any> loadMore(
        feed: PagedFeed<Response, Item>,
    ) {
        if (!feed.tryBeginLoadMore()) return
        screenModelScope.launch {
            try {
                feed.pager.loadMore()
                feed.publish()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 加载失败保留已有数据
            } finally {
                feed.endLoadMore()
            }
        }
    }

    private suspend fun fetchBoth() {
        coroutineScope {
            launch { fetchIllustTags() }
            launch { fetchNovelTags() }
        }
    }

    private suspend fun fetchIllustTags() {
        try {
            val resp = client.appApi.getIllustBookmarkTags(userId, "public")
            illustFeed.refresh(resp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 刷新失败时保留已有数据，只有尚无数据时才切到 Error
            if (!illustFeed.isSuccess()) {
                illustFeed.setError(e.message ?: "Failed to load tags")
            }
        }
    }

    private suspend fun fetchNovelTags() {
        try {
            val resp = client.appApi.getNovelBookmarkTags(userId, "public")
            novelFeed.refresh(resp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 刷新失败时保留已有数据，只有尚无数据时才切到 Error
            if (!novelFeed.isSuccess()) {
                novelFeed.setError(e.message ?: "Failed to load tags")
            }
        }
    }
}
