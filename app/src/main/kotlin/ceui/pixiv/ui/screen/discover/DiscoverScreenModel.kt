package ceui.pixiv.ui.screen.discover

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.TrendingTag
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

    // 当前选择的排行 mode；RankingFeed 内部按 mode 各自加载并维护状态
    private val _currentMode = MutableStateFlow("day")
    val currentMode: StateFlow<String> = _currentMode.asStateFlow()

    init {
        screenModelScope.launch { fetchTags() }
    }

    fun selectMode(mode: String) {
        _currentMode.value = mode
    }

    fun refresh() {
        screenModelScope.launch { fetchTags() }
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
}
