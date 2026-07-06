package ceui.pixiv.ui.screen.novel

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.NovelText
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.novel.ContentParser
import ceui.pixiv.ui.novel.ContentToken
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

    private val _state = MutableStateFlow<UiState<List<ContentToken>>>(UiState.Loading)
    val state: StateFlow<UiState<List<ContentToken>>> = _state.asStateFlow()

    init {
        loadNovelText()
    }

    private fun loadNovelText() {
        screenModelScope.launch {
            _state.value = UiState.Loading
            try {
                val tokens = withContext(Dispatchers.IO) {
                    val body = client.appApi.getNovelText(novelId)
                    val json = body.string()
                    val novelText = Gson().fromJson(json, NovelText::class.java)
                    ContentParser.tokenize(novelText.text.orEmpty())
                }
                _state.value = UiState.Success(tokens)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Failed to load novel text")
            }
        }
    }
}
