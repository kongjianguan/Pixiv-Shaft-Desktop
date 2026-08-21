package ceui.pixiv.ui.screen.pixivision

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import ceui.loxia.Article
import ceui.loxia.ArticlesResponse
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.component.EmptyView
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.state.PagedFeed
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.util.openInBrowser
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

// Pixivision 分类：插画 / 漫画
private val PIXIVISION_CATEGORIES = listOf(
    "插画" to "illust",
    "漫画" to "manga",
)

class PixivisionScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { PixivisionScreenModel() }
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val pagerState = rememberPagerState(pageCount = { PIXIVISION_CATEGORIES.size })

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Pixivision") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                TabRow(selectedTabIndex = pagerState.currentPage) {
                    PIXIVISION_CATEGORIES.forEachIndexed { index, (label, _) ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = { Text(label) },
                        )
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val category = PIXIVISION_CATEGORIES[page].second
                    // key 保证每个 tab 按各自 category 独立加载和分页
                    key(category) {
                        PixivisionCategoryFeed(category = category, screenModel = screenModel)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PixivisionCategoryFeed(category: String, screenModel: PixivisionScreenModel) {
    val state by screenModel.stateOf(category).collectAsState()
    val isRefreshing by screenModel.isRefreshingOf(category).collectAsState()
    val listState = rememberLazyListState()

    val shouldLoadMore by remember(state) {
        derivedStateOf {
            val items = (state as? UiState.Success)?.data ?: return@derivedStateOf false
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= items.size - 5 && items.isNotEmpty()
        }
    }
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) screenModel.loadMore(category) }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { screenModel.refresh(category) },
        modifier = Modifier.fillMaxSize()
    ) {
        when (val s = state) {
            is UiState.Loading -> LoadingView()
            is UiState.Error -> ErrorView(s.message, { screenModel.refresh(category) })
            is UiState.Success -> if (s.data.isEmpty()) {
                EmptyView("No articles")
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(s.data, key = { it.id }) { article ->
                        PixivisionArticleRow(article) { fullArticleUrl(article)?.let { openInBrowser(it) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun PixivisionArticleRow(article: Article, onClick: () -> Unit) {
    Card(onClick = onClick, shape = RoundedCornerShape(10.dp)) {
        Row(modifier = Modifier.padding(10.dp)) {
            AsyncImage(
                model = article.thumbnail,
                contentDescription = article.title,
                modifier = Modifier
                    .width(120.dp)
                    .height(90.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = article.title ?: "Untitled",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                article.subcategory_label?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                article.publish_date?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

/** article_url 可能为相对路径，补全为完整 URL。 */
internal fun fullArticleUrl(article: Article): String? {
    val url = article.article_url ?: return null
    return if (url.startsWith("http", ignoreCase = true)) url else "https://www.pixivision.net$url"
}

class PixivisionScreenModel : ScreenModel {

    private val client = AppContainer.client

    private class CategoryFeed(
        val category: String,
        val feed: PagedFeed<ArticlesResponse, Article>,
        val isRefreshing: MutableStateFlow<Boolean>,
    )

    private val feeds = PIXIVISION_CATEGORIES.map { it.second }.associateWith { category ->
        CategoryFeed(
            category = category,
            feed = PagedFeed(client, ArticlesResponse::class.java),
            isRefreshing = MutableStateFlow(false),
        )
    }

    fun stateOf(category: String): StateFlow<UiState<List<Article>>> = feeds.getValue(category).feed.state

    fun isRefreshingOf(category: String): StateFlow<Boolean> = feeds.getValue(category).isRefreshing

    init {
        feeds.values.forEach { feed ->
            screenModelScope.launch {
                try {
                    fetchFeed(feed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    feed.feed.setError(e.message ?: "Failed to load pixivision")
                }
            }
        }
    }

    fun refresh(category: String) {
        val feed = feeds.getValue(category)
        screenModelScope.launch {
            feed.isRefreshing.value = true
            try {
                fetchFeed(feed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 刷新失败时保留已有数据，只有尚无数据时才切到 Error
                if (!feed.feed.isSuccess()) {
                    feed.feed.setError(e.message ?: "Failed to load pixivision")
                }
            } finally {
                feed.isRefreshing.value = false
            }
        }
    }

    fun loadMore(category: String) {
        val feed = feeds.getValue(category)
        if (!feed.feed.tryBeginLoadMore()) return
        screenModelScope.launch {
            try {
                feed.feed.loadMoreAndPublishOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 加载失败保留已有数据
            } finally {
                feed.feed.endLoadMore()
            }
        }
    }

    private suspend fun fetchFeed(feed: CategoryFeed) {
        val resp = client.appApi.pixivsionArticles(feed.category)
        feed.feed.refresh(resp)
    }
}
