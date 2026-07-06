package ceui.pixiv.ui.screen.novel

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.Novel
import ceui.loxia.SingleNovelResponse
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class NovelDetailScreenModel(
    private val novelId: Long
) : ScreenModel {

    private val client = AppContainer.client

    private val _state = MutableStateFlow<UiState<Novel>>(UiState.Loading)
    val state: StateFlow<UiState<Novel>> = _state.asStateFlow()

    init {
        loadNovel()
    }

    private fun loadNovel() {
        screenModelScope.launch {
            _state.value = UiState.Loading
            try {
                val resp = client.appApi.getNovel(novelId)
                _state.value = resp.novel?.let { UiState.Success(it) }
                    ?: UiState.Error("Novel not found")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Failed to load novel")
            }
        }
    }
}
