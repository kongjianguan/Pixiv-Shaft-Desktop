package ceui.pixiv.ui.screen.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import coil3.compose.AsyncImage
import ceui.loxia.Illust
import ceui.loxia.UgoiraMetaData
import ceui.pixiv.ui.component.EmptyView
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.UgoiraPlayer
import ceui.pixiv.ui.component.IllustCard
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.component.TagChip
import ceui.pixiv.ui.component.UserAvatar
import ceui.pixiv.ui.component.ZoomableImage
import ceui.pixiv.ui.screen.search.SearchScreen
import ceui.pixiv.ui.state.UiState

class IllustDetailScreen(private val illustId: Long) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { IllustDetailScreenModel(illustId) }
        val illustState by screenModel.illustState.collectAsState()
        val relatedState by screenModel.relatedState.collectAsState()
        val ugoiraState by screenModel.ugoiraState.collectAsState()
        val navigator = LocalNavigator.currentOrThrow

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Illust #$illustId") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                when (val s = illustState) {
                    is UiState.Loading -> LoadingView()
                    is UiState.Error -> ErrorView(s.message, {})
                    is UiState.Success -> IllustDetailContent(
                        illust = s.data,
                        relatedState = relatedState,
                        onIllustClick = { id -> navigator.push(IllustDetailScreen(id)) },
                        onTagClick = { tag -> navigator.push(SearchScreen(initialQuery = tag)) },
                        ugoiraState = ugoiraState
                    )
                }
            }
        }
    }
}

@Composable
private fun IllustDetailContent(
    illust: Illust,
    relatedState: UiState<List<Illust>>,
    onIllustClick: (Long) -> Unit,
    onTagClick: (String) -> Unit,
    ugoiraState: UiState<UgoiraMetaData?> = UiState.Loading
) {
    val imageUrls = buildList {
        if (illust.page_count <= 1) {
            illust.maxUrl()?.let { add(it) }
        } else {
            illust.meta_pages?.forEach { page ->
                page.image_urls?.original?.let { add(it) }
            }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // Image gallery
        item {
            if (illust.isGif()) {
                // Ugoira: animated player
                when (val u = ugoiraState) {
                    is UiState.Loading -> LoadingView()
                    is UiState.Error -> ErrorView(u.message, {})
                    is UiState.Success -> {
                        val meta = u.data
                        if (meta != null) {
                            val zipUrl = meta.zip_urls?.medium
                            val frames = meta.frames ?: emptyList()
                            if (zipUrl != null && frames.isNotEmpty()) {
                                UgoiraPlayer(
                                    zipUrl = zipUrl,
                                    frames = frames
                                )
                            } else {
                                Text("Ugoira metadata incomplete")
                            }
                        } else {
                            Text("No ugoira metadata")
                        }
                    }
                }
            } else if (imageUrls.size > 1) {
                val pagerState = rememberPagerState(pageCount = { imageUrls.size })
                val currentPage by remember { derivedStateOf { pagerState.currentPage } }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    HorizontalPager(state = pagerState) { page ->
                        ZoomableImage(
                            model = imageUrls[page],
                            contentDescription = "Page ${page + 1}",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Text(
                        text = "第 ${currentPage + 1} / ${imageUrls.size} P",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                ZoomableImage(
                    model = imageUrls.firstOrNull(),
                    contentDescription = illust.title,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Title + author
        item {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = illust.title ?: "Untitled",
                    style = MaterialTheme.typography.titleMedium
                )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    UserAvatar(url = illust.user?.profile_image_urls?.px_50x50)
                    Text(
                        text = illust.user?.name ?: "",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = "♥ ${illust.total_bookmarks ?: 0}  👁 ${illust.total_view ?: 0}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // Tags
        item {
            illust.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tags) { tag ->
                        TagChip(tag = tag, onClick = onTagClick)
                    }
                }
            }
        }

        // Related works header
        item {
            Text(
                text = "Related works",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(16.dp)
            )
        }

        // Related works content
        when (relatedState) {
            is UiState.Loading -> item { LoadingView(modifier = Modifier.height(200.dp)) }
            is UiState.Error -> item {
                ErrorView(
                    message = (relatedState as UiState.Error).message,
                    onRetry = {},
                    modifier = Modifier.height(200.dp)
                )
            }
            is UiState.Success -> {
                val related = (relatedState as UiState.Success).data
                if (related.isEmpty()) {
                    item { EmptyView("No related works", modifier = Modifier.height(200.dp)) }
                } else {
                    items(related, key = { it.id }) { relatedIllust ->
                        IllustCard(
                            illust = relatedIllust,
                            onClick = onIllustClick,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
