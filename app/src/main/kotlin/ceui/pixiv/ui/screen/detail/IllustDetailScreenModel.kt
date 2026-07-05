package ceui.pixiv.ui.screen.detail

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.state.Pager
import ceui.pixiv.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class IllustDetailScreenModel(
    private val illustId: Long
) : ScreenModel {

    private val client = AppContainer.client
    private val relatedPager = Pager<IllustResponse, Illust>(client, IllustResponse::class.java)

    private val _illustState = MutableStateFlow<UiState<Illust>>(UiState.Loading)
    val illustState: StateFlow<UiState<Illust>> = _illustState.asStateFlow()

    private val _relatedState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val relatedState: StateFlow<UiState<List<Illust>>> = _relatedState.asStateFlow()

    init {
        loadIllust()
        loadRelated()
    }

    private fun loadIllust() {
        screenModelScope.launch {
            _illustState.value = UiState.Loading
            try {
                val resp = client.appApi.getIllust(illustId)
                _illustState.value = resp.illust?.let { UiState.Success(it) }
                    ?: UiState.Error("Illust not found")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _illustState.value = UiState.Error(e.message ?: "Failed to load illust")
            }
        }
    }

    private fun loadRelated() {
        screenModelScope.launch {
            _relatedState.value = UiState.Loading
            try {
                val resp = client.appApi.getRelatedIllusts(illustId)
                relatedPager.refresh(resp)
                _relatedState.value = UiState.Success(relatedPager.items.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _relatedState.value = UiState.Error(e.message ?: "Failed to load related")
            }
        }
    }
}
