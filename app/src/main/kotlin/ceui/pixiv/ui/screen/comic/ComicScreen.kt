package ceui.pixiv.ui.screen.comic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.loxia.ComicBanner
import ceui.loxia.ComicTopData
import ceui.loxia.ComicWork
import ceui.pixiv.di.AppContainer
import ceui.pixiv.net.api.Client
import ceui.pixiv.ui.component.EmptyView
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.util.openInBrowser
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class ComicScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { ComicScreenModel() }
        val state by screenModel.state.collectAsState()
        val refreshing by screenModel.refreshing.collectAsState()
        val navigator = LocalNavigator.currentOrThrow

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Pixiv Comic") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            },
        ) { padding ->
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = screenModel::refresh,
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                when (val current = state) {
                    is UiState.Loading -> LoadingView()
                    is UiState.Error -> ErrorView(current.message, screenModel::refresh)
                    is UiState.Success -> ComicTopContent(current.data)
                }
            }
        }
    }
}

@Composable
private fun ComicTopContent(data: ComicTopData) {
    val banners = data.banners.orEmpty()
    val works = data.recent_updated_official_works.orEmpty()

    if (banners.isEmpty() && works.isEmpty()) {
        EmptyView("暂时没有 Comic 内容")
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (banners.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(banners, key = { it.id }) { banner ->
                        ComicBannerCard(banner)
                    }
                }
            }
        }
        item {
            Text(
                text = "最近更新",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        items(works, key = { it.id }) { work ->
            ComicWorkRow(work)
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun ComicBannerCard(banner: ComicBanner) {
    val url = banner.url ?: comicWorkUrl(banner.id)
    Card(
        modifier = Modifier
            .size(width = 280.dp, height = 150.dp)
            .clickable { openInBrowser(url) },
        shape = RoundedCornerShape(10.dp),
    ) {
        AsyncImage(
            model = banner.image_url,
            contentDescription = "Pixiv Comic banner",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun ComicWorkRow(work: ComicWork) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { openInBrowser(comicWorkUrl(work.id)) },
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = work.thumbnail_image_url ?: work.main_image_url,
                contentDescription = work.title,
                modifier = Modifier
                    .size(92.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = work.title ?: "未命名作品",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                )
                Text(
                    text = work.author.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    text = "${work.stories_count} 话  ·  ♥ ${work.like_count}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private fun comicWorkUrl(id: Long): String = "https://comic.pixiv.net/works/$id"

class ComicScreenModel(
    private val client: Client = AppContainer.client,
) : ScreenModel {

    private val _state = MutableStateFlow<UiState<ComicTopData>>(UiState.Loading)
    val state: StateFlow<UiState<ComicTopData>> = _state.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        screenModelScope.launch { load() }
    }

    fun refresh() {
        screenModelScope.launch {
            _refreshing.value = true
            try {
                load()
            } finally {
                _refreshing.value = false
            }
        }
    }

    private suspend fun load() {
        if (_state.value !is UiState.Success) _state.value = UiState.Loading
        try {
            _state.value = UiState.Success(client.comicApi.getComicTop().data ?: ComicTopData())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_state.value !is UiState.Success) {
                _state.value = UiState.Error(e.message ?: "Comic 加载失败")
            }
        }
    }
}
