package ceui.pixiv.ui.screen.novel

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.NovelText
import ceui.loxia.WebNovel
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.novel.ContentParser
import ceui.pixiv.ui.novel.ContentToken
import ceui.pixiv.ui.novel.NovelWebParser
import ceui.pixiv.ui.state.UiState
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

class NovelReaderScreenModel(
    private val novelId: Long
) : ScreenModel {

    private val client = AppContainer.client

    private val _state = MutableStateFlow<UiState<NovelReaderData>>(UiState.Loading)
    val state: StateFlow<UiState<NovelReaderData>> = _state.asStateFlow()

    fun reload() {
        screenModelScope.launch {
            _state.value = UiState.Loading
            try {
                val readerData = withContext(Dispatchers.IO) {
                    val body = client.appApi.getNovelText(novelId)
                    val raw = body.string()
                    // Pixiv currently returns HTML with a serialized window.pixiv
                    // object. Keep JSON as a fallback for cached/proxy responses.
                    val webNovel = NovelWebParser.parse(raw)
                        ?: parseJsonPayload(raw)
                        ?: error("Unable to parse novel response")
                    NovelReaderData(webNovel, ContentParser.tokenize(webNovel.text.orEmpty()))
                }
                _state.value = UiState.Success(readerData)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Failed to load novel text")
            }
        }
    }

    init {
        reload()
    }
}

private val novelGson = Gson()

private fun parseJsonPayload(raw: String): WebNovel? {
    runCatching { novelGson.fromJson(raw, WebNovel::class.java) }
        .getOrNull()
        ?.let { return it }

    val legacy = runCatching { novelGson.fromJson(raw, NovelText::class.java) }.getOrNull()
        ?: return null
    return WebNovel(
        coverUrl = legacy.coverUrl,
        glossaryItems = legacy.glossaryItems?.map { it },
        id = legacy.id,
        text = legacy.text,
        replaceableItemIds = legacy.replaceableItemIds?.map { it },
        seriesId = legacy.seriesId,
        seriesNavigation = legacy.seriesNavigation,
        userId = legacy.userId,
    )
}

data class NovelReaderData(
    val webNovel: WebNovel,
    val tokens: List<ContentToken>,
)
