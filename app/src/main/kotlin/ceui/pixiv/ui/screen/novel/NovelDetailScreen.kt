package ceui.pixiv.ui.screen.novel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.component.TagChip
import ceui.pixiv.ui.component.UserAvatar
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
                    title = { Text("Novel") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (val s = state) {
                    is UiState.Loading -> LoadingView()
                    is UiState.Error -> ErrorView(s.message, {})
                    is UiState.Success -> {
                        val novel = s.data
                        // Cover image
                        novel.image_urls?.large?.let { url ->
                            AsyncImage(
                                model = url,
                                contentDescription = novel.title,
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.Fit
                            )
                        }
                        // Title
                        Text(
                            text = novel.title ?: "Untitled",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        // Author
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            UserAvatar(url = novel.user?.profile_image_urls?.px_50x50)
                            Text(
                                text = novel.user?.name ?: "",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        // Stats
                        Text(
                            text = "${novel.text_length ?: 0} chars  ♥ ${novel.total_bookmarks ?: 0}  👁 ${novel.total_view ?: 0}",
                            style = MaterialTheme.typography.labelMedium
                        )
                        // Caption
                        novel.caption?.let { caption ->
                            Text(
                                text = caption,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        // Tags
                        novel.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(tags) { tag ->
                                    TagChip(tag = tag, onClick = {})
                                }
                            }
                        }
                        // Read button
                        Button(
                            onClick = { navigator.push(NovelReaderScreen(novelId)) },
                            modifier = Modifier.padding(top = 16.dp)
                        ) {
                            Text("Read")
                        }
                    }
                }
            }
        }
    }
}
