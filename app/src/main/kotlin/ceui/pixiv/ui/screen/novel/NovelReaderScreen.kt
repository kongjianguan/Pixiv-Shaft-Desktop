package ceui.pixiv.ui.screen.novel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.EmptyView
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.novel.ContentParser
import ceui.pixiv.ui.novel.NovelContent
import ceui.pixiv.ui.state.UiState

class NovelReaderScreen(
    private val novelId: Long,
    private val novelTitle: String? = null,
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { NovelReaderScreenModel(novelId) }
        val state by screenModel.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        var targetSource by remember { mutableStateOf<Int?>(null) }
        var chapterMenuExpanded by remember { mutableStateOf(false) }
        val readerData = (state as? UiState.Success)?.data
        val outline = remember(readerData?.tokens) {
            readerData?.tokens?.let(ContentParser::buildChapterOutline).orEmpty()
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(novelTitle?.takeIf { it.isNotBlank() } ?: "Novel Reader") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        readerData?.webNovel?.seriesNavigation?.prevNovel?.let { previous ->
                            TextButton(onClick = { navigator.push(NovelReaderScreen(previous.id?.toLong() ?: 0L, previous.title)) }) {
                                Text("上一话")
                            }
                        }
                        readerData?.webNovel?.seriesNavigation?.nextNovel?.let { next ->
                            TextButton(onClick = { navigator.push(NovelReaderScreen(next.id?.toLong() ?: 0L, next.title)) }) {
                                Text("下一话")
                            }
                        }
                        if (outline.isNotEmpty()) {
                            Box {
                                TextButton(onClick = { chapterMenuExpanded = true }) { Text("目录") }
                                DropdownMenu(
                                    expanded = chapterMenuExpanded,
                                    onDismissRequest = { chapterMenuExpanded = false },
                                ) {
                                    outline.forEach { entry ->
                                        DropdownMenuItem(
                                            text = { Text(entry.title) },
                                            onClick = {
                                                targetSource = entry.sourceStart
                                                chapterMenuExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (val s = state) {
                    is UiState.Loading -> LoadingView()
                    is UiState.Error -> ErrorView(s.message, screenModel::reload)
                    is UiState.Success -> {
                        if (s.data.tokens.isEmpty()) {
                            EmptyView("正文为空")
                        } else {
                            NovelContent(
                                tokens = s.data.tokens,
                                webNovel = s.data.webNovel,
                                targetSource = targetSource,
                                onTargetConsumed = { targetSource = null },
                            )
                        }
                    }
                }
            }
        }
    }
}
