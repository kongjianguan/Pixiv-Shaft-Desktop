package ceui.pixiv.ui.screen.search

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

class SearchScreenModel(
    initialQuery: String? = null
) : ScreenModel {

    private val client = AppContainer.client
    private val db = AppContainer.database
    private val pager = Pager<IllustResponse, Illust>(client, IllustResponse::class.java)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _resultsState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val resultsState: StateFlow<UiState<List<Illust>>> = _resultsState.asStateFlow()

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    private val _isLoading = MutableStateFlow(false)

    init {
        loadHistory()
        if (initialQuery != null && initialQuery.isNotBlank()) {
            _query.value = initialQuery
            search(initialQuery)
        }
    }

    fun updateQuery(q: String) {
        _query.value = q
    }

    fun search(word: String) {
        if (word.isBlank()) return
        _query.value = word
        screenModelScope.launch {
            _resultsState.value = UiState.Loading
            try {
                // Save to search history
                db.queries.searchHistoryQueries.insertKeywordOnly(word, System.currentTimeMillis(), 0L)
                loadHistory()

                val resp = client.appApi.searchIllustManga(
                    word = word,
                    sort = "date_desc",
                    search_target = "partial_match_for_tags",
                    merge_plain_keyword_results = true,
                    include_translated_tag_results = true
                )
                pager.refresh(resp)
                _resultsState.value = UiState.Success(pager.items.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _resultsState.value = UiState.Error(e.message ?: "Search failed")
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
                _resultsState.value = UiState.Success(pager.items.value)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep existing results
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearHistory() {
        screenModelScope.launch {
            db.queries.searchHistoryQueries.clearAllSearches()
            loadHistory()
        }
    }

    private fun loadHistory() {
        screenModelScope.launch {
            _history.value = db.queries.searchHistoryQueries.selectRecentSearches(20)
                .executeAsList()
                .map { it.keyword }
        }
    }
}
