package ceui.pixiv.ui.screen.novel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.component.CaptionText
import ceui.pixiv.ui.component.TagChip
import ceui.pixiv.ui.component.UserAvatar
import ceui.pixiv.ui.screen.user.UserDetailScreen
import ceui.pixiv.ui.state.UiState
import coil3.compose.AsyncImage

class NovelDetailScreen(private val novelId: Long) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { NovelDetailScreenModel(novelId) }
        val state by screenModel.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = (state as? UiState.Success)?.data?.title ?: "Novel",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            when (val s = state) {
                is UiState.Loading -> Box(modifier = Modifier.fillMaxSize().padding(padding)) { LoadingView() }
                is UiState.Error -> Box(modifier = Modifier.fillMaxSize().padding(padding)) { ErrorView(s.message, {}) }
                is UiState.Success -> {
                    val novel = s.data
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            novel.image_urls?.large?.let { url ->
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        model = url,
                                        contentDescription = novel.title,
                                        modifier = Modifier
                                            .fillMaxWidth(0.5f)
                                            .aspectRatio(240f / 338f),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }
                        item {
                            Text(
                                text = novel.title ?: "Untitled",
                                style = MaterialTheme.typography.headlineSmall
                            )
                        }
                        item {
                            val userId = novel.user?.id ?: 0L
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = if (userId > 0) Modifier.clickable { navigator.push(UserDetailScreen(userId)) } else Modifier,
                            ) {
                                UserAvatar(url = novel.user?.profile_image_urls?.px_50x50)
                                Text(
                                    text = novel.user?.name ?: "",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        val series = novel.series
                        series?.title?.takeIf { it.isNotBlank() }?.let { seriesTitle ->
                            item {
                                Text(
                                    text = seriesTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable { navigator.push(NovelSeriesScreen(series.id)) },
                                )
                            }
                        }
                        item {
                            Text(
                                text = "${novel.text_length ?: 0} 字  ·  ♥ ${novel.total_bookmarks ?: 0}  ·  👁 ${novel.total_view ?: 0}",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        novel.caption?.takeIf { it.isNotBlank() }?.let { caption ->
                            item {
                                CaptionText(
                                    html = caption,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                        novel.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                            item {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(tags) { tag -> TagChip(tag = tag, onClick = {}) }
                                }
                            }
                        }
                        item {
                            Button(
                                onClick = { navigator.push(NovelReaderScreen(novelId, novel.title)) },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            ) {
                                Text("开始阅读")
                            }
                        }
                    }
                }
            }
        }
    }
}
