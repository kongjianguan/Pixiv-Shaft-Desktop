package ceui.pixiv.ui.screen.collection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import ceui.lisa.models.WatchlistMangaItem
import ceui.lisa.models.WatchlistNovelItem
import ceui.loxia.KListShow
import ceui.loxia.WatchlistMangaResponse
import ceui.loxia.WatchlistNovelResponse
import ceui.pixiv.di.AppContainer
import ceui.pixiv.net.api.Client
import ceui.pixiv.store.SettingsStore
import ceui.pixiv.ui.component.EmptyView
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.screen.novel.NovelSeriesScreen
import ceui.pixiv.ui.state.PagedFeed
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.ui.util.observeR18Toggle
import ceui.pixiv.ui.util.visibleItems
import ceui.pixiv.util.openInBrowser
import coil3.compose.AsyncImage
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** 追更列表页：漫画/小说两个 tab。小说进系列页，漫画无独立详情页则用浏览器打开网页版系列。 */
class WatchlistScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { WatchlistScreenModel() }
        val navigator = LocalNavigator.currentOrThrow
        val mangaState by screenModel.mangaState.collectAsState()
        val novelState by screenModel.novelState.collectAsState()
        val isRefreshing by screenModel.isRefreshing.collectAsState()
        var selectedTab by remember { mutableStateOf(0) } // 0=漫画 1=小说

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("追更") },
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
                        text = { Text("漫画") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("小说") }
                    )
                }
                val isManga = selectedTab == 0
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { screenModel.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // 每个 tab 独立滚动位置，切换 tab 时列表回到顶部
                    val listState = remember(selectedTab) { LazyListState() }
                    val shouldLoadMore by remember(isManga, mangaState, novelState) {
                        derivedStateOf {
                            val size = when {
                                isManga -> (mangaState as? UiState.Success)?.data?.size
                                else -> (novelState as? UiState.Success)?.data?.size
                            } ?: 0
                            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                            size > 0 && lastVisible >= size - 5
                        }
                    }
                    LaunchedEffect(shouldLoadMore, isManga) {
                        if (shouldLoadMore) screenModel.loadMore(isManga)
                    }
                    if (isManga) {
                        when (val s = mangaState) {
                            is UiState.Loading -> LoadingView()
                            is UiState.Error -> ErrorView(s.message, { screenModel.refresh() })
                            is UiState.Success -> {
                                if (s.data.isEmpty()) {
                                    // R18 开关关闭且数据被过滤时提示隐藏而非「没有数据」
                                    EmptyView(
                                        if (screenModel.hasHiddenR18(isManga)) "R18 内容已隐藏（可在设置中开启）"
                                        else "No watchlist"
                                    )
                                } else {
                                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                        items(s.data, key = { it.id }) { item ->
                                            WatchlistMangaRow(item = item, onClick = {
                                                // 屏蔽态不可点；无漫画详情页，用浏览器打开网页版系列
                                                if (item.mask_text.isNullOrEmpty()) {
                                                    openInBrowser("https://www.pixiv.net/manga/series/${item.id}")
                                                }
                                            })
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        when (val s = novelState) {
                            is UiState.Loading -> LoadingView()
                            is UiState.Error -> ErrorView(s.message, { screenModel.refresh() })
                            is UiState.Success -> {
                                if (s.data.isEmpty()) {
                                    // R18 开关关闭且数据被过滤时提示隐藏而非「没有数据」
                                    EmptyView(
                                        if (screenModel.hasHiddenR18(isManga)) "R18 内容已隐藏（可在设置中开启）"
                                        else "No watchlist"
                                    )
                                } else {
                                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                        items(s.data, key = { it.id }) { item ->
                                            WatchlistNovelRow(item = item, onClick = {
                                                if (item.mask_text.isNullOrEmpty()) {
                                                    navigator.push(NovelSeriesScreen(item.id.toLong()))
                                                }
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
}

/** 追更行卡：封面 + 标题 + 已发布话数 + 最近更新时间。 */
@Composable
private fun WatchlistSeriesRow(
    title: String,
    coverUrl: String?,
    authorName: String?,
    contentCount: Int,
    lastDate: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(modifier = Modifier.padding(10.dp)) {
            AsyncImage(
                model = coverUrl,
                contentDescription = title,
                modifier = Modifier
                    .width(72.dp)
                    .aspectRatio(3f / 4f)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!authorName.isNullOrEmpty()) {
                    Text(
                        text = authorName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "已发布 $contentCount 话",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (lastDate.isNotEmpty()) {
                        Text(
                            text = "更新于 $lastDate",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// last_published_content_datetime 的 getter 会 substring(0, 10)：
// MangaItem 对 null 安全（?.），NovelItem 用 !! 在字段缺失时直接在取属性处抛 NPE，
// 所以 runCatching 必须在调用处包住属性访问，进函数后再包就拦不到了

@Composable
private fun WatchlistMangaRow(item: WatchlistMangaItem, onClick: () -> Unit) {
    WatchlistSeriesRow(
        title = item.title,
        coverUrl = item.url,
        authorName = item.user?.name,
        contentCount = item.published_content_count,
        lastDate = runCatching { item.last_published_content_datetime }.getOrNull().orEmpty(),
        onClick = onClick,
    )
}

@Composable
private fun WatchlistNovelRow(item: WatchlistNovelItem, onClick: () -> Unit) {
    WatchlistSeriesRow(
        title = item.title,
        coverUrl = item.url,
        authorName = item.user?.name,
        contentCount = item.published_content_count,
        lastDate = runCatching { item.last_published_content_datetime }.getOrNull().orEmpty(),
        onClick = onClick,
    )
}

/** 追更模型：漫画/小说两个独立的类型安全 feed，无需 user_id。 */
class WatchlistScreenModel(
    private val client: Client = AppContainer.client,
    private val settingsStore: SettingsStore = AppContainer.settingsStore,
) : ScreenModel {

    private val mangaFeed = PagedFeed<WatchlistMangaResponse, WatchlistMangaItem>(
        client,
        WatchlistMangaResponse::class.java,
    ) { visibleItems(it, settingsStore.isShowR18) }
    private val novelFeed = PagedFeed<WatchlistNovelResponse, WatchlistNovelItem>(
        client,
        WatchlistNovelResponse::class.java,
    ) { visibleItems(it, settingsStore.isShowR18) }

    val mangaState: StateFlow<UiState<List<WatchlistMangaItem>>> = mangaFeed.state

    val novelState: StateFlow<UiState<List<WatchlistNovelItem>>> = novelFeed.state

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        screenModelScope.launch { fetchInitial() }
        observeR18Toggle(::republishIfLoaded, settingsStore)
    }

    private suspend fun fetchInitial() {
        try {
            fetchBoth()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "Failed to load watchlist"
            mangaFeed.setError(message)
            novelFeed.setError(message)
        }
    }

    fun refresh() {
        screenModelScope.launch {
            _isRefreshing.value = true
            try {
                fetchBoth()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 刷新失败时保留已有数据
                val message = e.message ?: "Failed to load watchlist"
                if (!mangaFeed.isSuccess()) mangaFeed.setError(message)
                if (!novelFeed.isSuccess()) novelFeed.setError(message)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun loadMore(isManga: Boolean) {
        if (isManga) loadMore(mangaFeed) else loadMore(novelFeed)
    }

    private fun <Response : KListShow<Item>, Item : Any> loadMore(feed: PagedFeed<Response, Item>) {
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

    /** 当前开关状态下，已加载数据中是否有被 R18 过滤隐藏的作品（空态时区分「真没有」与「被隐藏」） */
    fun hasHiddenR18(isManga: Boolean): Boolean {
        val raw = if (isManga) mangaFeed.pager.items.value else novelFeed.pager.items.value
        return ceui.pixiv.ui.util.hasHiddenR18(raw, settingsStore.isShowR18)
    }

    private suspend fun fetchBoth() {
        coroutineScope {
            launch { fetchManga() }
            launch { fetchNovel() }
        }
    }

    private suspend fun fetchManga() {
        try {
            val resp = client.appApi.getWatchlistMangas()
            mangaFeed.refreshUntilVisible(resp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 刷新失败时保留已有数据，只有尚无数据时才切到 Error
            if (!mangaFeed.isSuccess()) {
                mangaFeed.setError(e.message ?: "Failed to load watchlist")
            }
        }
    }

    private suspend fun fetchNovel() {
        try {
            val resp = client.appApi.getWatchlistNovels()
            novelFeed.refreshUntilVisible(resp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 刷新失败时保留已有数据，只有尚无数据时才切到 Error
            if (!novelFeed.isSuccess()) {
                novelFeed.setError(e.message ?: "Failed to load watchlist")
            }
        }
    }

    /** R18 开关变化时重新过滤已加载内容（feed 保留完整数据） */
    private fun republishIfLoaded() {
        mangaFeed.republishIfLoaded()
        novelFeed.republishIfLoaded()
    }
}
