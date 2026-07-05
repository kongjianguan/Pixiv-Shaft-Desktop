package ceui.pixiv.ui.screen.recommend

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.IllustResponse
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.state.Pager
import ceui.pixiv.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RecommendScreenModel : ScreenModel {

    private val client = AppContainer.client
    private val pager = Pager<IllustResponse, ceui.loxia.Illust>(client, IllustResponse::class.java)

    private val _isLoading = MutableStateFlow(false)
    private val _state = MutableStateFlow<UiState<List<ceui.loxia.Illust>>>(UiState.Loading)
    val state: StateFlow<UiState<List<ceui.loxia.Illust>>> = _state.asStateFlow()

    val hasNext get() = pager.hasNext

    init {
        refresh()
    }

    fun refresh() {
        screenModelScope.launch {
            _state.value = UiState.Loading
            try {
                val resp = client.appApi.getWalkthroughWorks()
                pager.refresh(resp)
                _state.value = UiState.Success(pager.items.value)
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Failed to load recommendations")
            }
        }
    }

    fun loadMore() {
        if (!pager.hasNext.value) return
        if (_isLoading.value) return
        screenModelScope.launch {
            _isLoading.value = true
            try {
                pager.loadMore()
                _state.value = UiState.Success(pager.items.value)
            } catch (e: Exception) {
                // Keep existing items, show non-blocking error
                _state.value = UiState.Success(pager.items.value)
            } finally {
                _isLoading.value = false
            }
        }
    }
}
