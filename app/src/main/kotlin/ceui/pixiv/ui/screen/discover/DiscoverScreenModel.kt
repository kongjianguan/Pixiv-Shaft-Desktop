package ceui.pixiv.ui.screen.discover

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.TrendingTag
import ceui.loxia.TrendingTagsResponse
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class DiscoverScreenModel : ScreenModel {

    private val client = AppContainer.client

    private val _tagsState = MutableStateFlow<UiState<List<TrendingTag>>>(UiState.Loading)
    val tagsState: StateFlow<UiState<List<TrendingTag>>> = _tagsState.asStateFlow()

    private val _rankingState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val rankingState: StateFlow<UiState<List<Illust>>> = _rankingState.asStateFlow()

    private var _currentMode = "day"
    private val _currentModeFlow = MutableStateFlow("day")
    val currentMode: StateFlow<String> = _currentModeFlow.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init { loadInitial() }

    private fun loadInitial() {
        screenModelScope.launch {
            _tagsState.value = UiState.Loading
            _rankingState.value = UiState.Loading
            fetchTags()
            fetchRanking(_currentMode)
        }
    }

    fun refresh() {
        screenModelScope.launch {
            _isRefreshing.value = true
            fetchTags()
            fetchRanking(_currentMode)
            _isRefreshing.value = false
        }
    }

    private suspend fun fetchTags() {
        try {
            val resp = client.appApi.trendingTags("illust")
            _tagsState.value = UiState.Success(resp.displayList)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _tagsState.value = UiState.Error(e.message ?: "Failed to load trending tags")
        }
    }

    fun loadRanking(mode: String) {
        _currentMode = mode
        _currentModeFlow.value = mode
        screenModelScope.launch {
            _rankingState.value = UiState.Loading
            fetchRanking(mode)
        }
    }

    private suspend fun fetchRanking(mode: String) {
        try {
            val resp = client.appApi.getRankingIllusts(mode)
            if (_currentMode == mode) {
                _rankingState.value = UiState.Success(resp.illusts)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_currentMode == mode) {
                _rankingState.value = UiState.Error(e.message ?: "Failed to load ranking")
            }
        }
    }
}
