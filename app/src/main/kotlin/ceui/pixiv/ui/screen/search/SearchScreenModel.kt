package ceui.pixiv.ui.screen.search

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.Novel
import ceui.loxia.NovelResponse
import ceui.loxia.TrendingTag
import ceui.loxia.UserPreview
import ceui.loxia.UserPreviewResponse
import ceui.pixiv.di.AppContainer
import ceui.pixiv.store.Search_table
import ceui.pixiv.ui.search.v3.SearchOptionsResponse
import ceui.pixiv.ui.state.Pager
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.ui.util.observeR18Toggle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

class SearchScreenModel(
    initialQuery: String? = null,
) : ScreenModel {

    private val client = AppContainer.client
    private val db = AppContainer.database
    private val settings = AppContainer.settingsStore

    private val illustPager = Pager<IllustResponse, Illust>(client, IllustResponse::class.java)
    private val novelPager = Pager<NovelResponse, Novel>(client, NovelResponse::class.java)
    private val userPager = Pager<UserPreviewResponse, UserPreview>(client, UserPreviewResponse::class.java)

    private val _activeTab = MutableStateFlow(SearchTab.Illust)
    val activeTab: StateFlow<SearchTab> = _activeTab.asStateFlow()

    private val _tags = MutableStateFlow<List<String>>(emptyList())
    val tags: StateFlow<List<String>> = _tags.asStateFlow()

    private val _input = MutableStateFlow("")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _hasSubmitted = MutableStateFlow(!initialQuery.isNullOrBlank())
    val hasSubmitted: StateFlow<Boolean> = _hasSubmitted.asStateFlow()

    private val _illustState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val illustState: StateFlow<UiState<List<Illust>>> = _illustState.asStateFlow()

    private val _novelState = MutableStateFlow<UiState<List<Novel>>>(UiState.Loading)
    val novelState: StateFlow<UiState<List<Novel>>> = _novelState.asStateFlow()

    private val _userState = MutableStateFlow<UiState<List<UserPreview>>>(UiState.Loading)
    val userState: StateFlow<UiState<List<UserPreview>>> = _userState.asStateFlow()

    val illustHasMore: StateFlow<Boolean> = illustPager.hasNext
    val novelHasMore: StateFlow<Boolean> = novelPager.hasNext
    val userHasMore: StateFlow<Boolean> = userPager.hasNext

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _illustFilter = MutableStateFlow(
        SearchFilter(target = SearchTarget.fromApiValue(settings.searchIllustTarget))
    )
    val illustFilter: StateFlow<SearchFilter> = _illustFilter.asStateFlow()

    private val _novelFilter = MutableStateFlow(
        SearchFilter(target = SearchTarget.fromApiValue(settings.searchNovelTarget))
    )
    val novelFilter: StateFlow<SearchFilter> = _novelFilter.asStateFlow()

    private val _searchOptions = MutableStateFlow<SearchOptionsResponse?>(null)
    val searchOptions: StateFlow<SearchOptionsResponse?> = _searchOptions.asStateFlow()

    private val _suggestions = MutableStateFlow<List<SearchSuggestion>>(emptyList())
    val suggestions: StateFlow<List<SearchSuggestion>> = _suggestions.asStateFlow()

    private val _trendingTags = MutableStateFlow<UiState<List<TrendingTag>>>(UiState.Loading)
    val trendingTags: StateFlow<UiState<List<TrendingTag>>> = _trendingTags.asStateFlow()

    private val _clipboardSuggestion = MutableStateFlow<String?>(null)
    val clipboardSuggestion: StateFlow<String?> = _clipboardSuggestion.asStateFlow()

    private val _pinnedHistory = MutableStateFlow<List<Search_table>>(emptyList())
    val pinnedHistory: StateFlow<List<Search_table>> = _pinnedHistory.asStateFlow()

    private val _recentHistory = MutableStateFlow<List<Search_table>>(emptyList())
    val recentHistory: StateFlow<List<Search_table>> = _recentHistory.asStateFlow()

    private var suggestionJob: Job? = null
    private var searchOptionsJob: Job? = null
    private var searchOptionsGeneration = 0L
    private val novelBookmarksInFlight = ConcurrentHashMap.newKeySet<Long>()

    init {
        if (!initialQuery.isNullOrBlank()) {
            setQueryParts(initialQuery)
        }
        screenModelScope.launch {
            loadHistory()
            loadTrendingTags()
        }
        if (!initialQuery.isNullOrBlank()) {
            search(initialQuery)
        } else {
            readClipboardSuggestion()
        }
        observeR18Toggle(::republishIfLoaded)
    }

    fun selectTab(tab: SearchTab) {
        if (_activeTab.value == tab) return
        _activeTab.value = tab
        if (_query.value.isNotBlank()) refresh()
    }

    fun updateInput(value: String) {
        val normalized = value.replace('\n', ' ')
        val hasWhitespace = normalized.any(Char::isWhitespace)
        if (!hasWhitespace) {
            _input.value = normalized
        } else {
            val parts = normalized.trim().split(Regex("\\s+")).filter(String::isNotBlank)
            val keepLast = normalized.lastOrNull()?.isWhitespace() != true
            val committed = if (keepLast) parts.dropLast(1) else parts
            addTags(committed)
            _input.value = if (keepLast) parts.lastOrNull().orEmpty() else ""
        }
        updateQueryValue()
        requestSuggestions(_input.value)
    }

    fun acceptSuggestion(suggestion: SearchSuggestion) {
        addTags(listOf(suggestion.tag))
        _input.value = ""
        updateQueryValue()
        clearSuggestions()
        search(_query.value)
    }

    fun removeTagAndSearch(tag: String) {
        _tags.value = _tags.value.filterNot { it == tag }
        updateQueryValue()
        if (_query.value.isBlank()) clearQuery() else search(_query.value)
    }

    fun clearQuery() {
        _tags.value = emptyList()
        _input.value = ""
        _query.value = ""
        _hasSubmitted.value = false
        clearSuggestions()
        searchOptionsGeneration++
        searchOptionsJob?.cancel()
        searchOptionsJob = null
        _searchOptions.value = null
        setState(SearchTab.Illust, UiState.Loading)
        setState(SearchTab.Novel, UiState.Loading)
        setState(SearchTab.User, UiState.Loading)
        screenModelScope.launch { loadTrendingTags() }
    }

    fun search(word: String) {
        val normalized = word.trim().split(Regex("\\s+")).filter(String::isNotBlank).joinToString(" ")
        if (normalized.isBlank()) return
        _hasSubmitted.value = true
        setQueryParts(normalized)
        clearSuggestions()
        val tab = _activeTab.value
        val filter = filterFor(tab)
        val optionsGeneration = ++searchOptionsGeneration
        searchOptionsJob?.cancel()
        searchOptionsJob = screenModelScope.launch {
            loadSearchOptions(normalized, filter, optionsGeneration)
        }
        screenModelScope.launch {
            setState(tab, UiState.Loading)
            try {
                saveSearchHistory(normalized)
                fetch(tab, normalized, filter)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setError(tab, e.message ?: "搜索失败")
            }
        }
    }

    fun refresh() {
        val word = _query.value
        if (word.isBlank()) {
            screenModelScope.launch {
                _isRefreshing.value = true
                try {
                    loadHistory()
                    loadTrendingTags()
                } finally {
                    _isRefreshing.value = false
                }
            }
            return
        }

        val tab = _activeTab.value
        val filter = filterFor(tab)
        screenModelScope.launch {
            _isRefreshing.value = true
            try {
                fetch(tab, word, filter)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setErrorIfNeeded(tab, e.message ?: "刷新失败")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun updateActiveFilter(filter: SearchFilter) {
        when (_activeTab.value) {
            SearchTab.Illust -> {
                _illustFilter.value = filter
                settings.setSearchIllustTarget(filter.target.apiValue)
            }
            SearchTab.Novel -> {
                _novelFilter.value = filter
                settings.setSearchNovelTarget(filter.target.apiValue)
            }
            SearchTab.User -> Unit
        }
    }

    fun loadMore() {
        if (_isLoadingMore.value) return
        val tab = _activeTab.value
        val hasMore = when (tab) {
            SearchTab.Illust -> illustPager.hasNext.value
            SearchTab.Novel -> novelPager.hasNext.value
            SearchTab.User -> userPager.hasNext.value
        }
        if (!hasMore) return

        screenModelScope.launch {
            _isLoadingMore.value = true
            try {
                when (tab) {
                    SearchTab.Illust -> {
                        do {
                            val filter = _illustFilter.value
                            val previousVisibleCount = visibleIllusts(illustPager.items.value, filter).size
                            illustPager.loadMore()
                            val visible = visibleIllusts(illustPager.items.value, filter)
                            setState(tab, UiState.Success(visible))
                        } while (visible.size <= previousVisibleCount && illustPager.hasNext.value)
                    }
                    SearchTab.Novel -> {
                        do {
                            val filter = _novelFilter.value
                            val previousVisibleCount = visibleNovels(novelPager.items.value, filter).size
                            novelPager.loadMore()
                            val visible = visibleNovels(novelPager.items.value, filter)
                            setState(tab, UiState.Success(visible))
                        } while (visible.size <= previousVisibleCount && novelPager.hasNext.value)
                    }
                    SearchTab.User -> {
                        userPager.loadMore()
                        setState(tab, UiState.Success(userPager.items.value))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the already loaded page when an additional page fails.
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun toggleNovelBookmark(novel: Novel) {
        ceui.pixiv.ui.util.toggleNovelBookmark(
            scope = screenModelScope,
            client = client,
            novel = novel,
            inFlight = novelBookmarksInFlight,
            updateLocal = ::updateNovelBookmark,
        )
    }

    fun dismissClipboardSuggestion() {
        _clipboardSuggestion.value = null
    }

    fun classifyInput(value: String): SearchInputKind {
        val trimmed = value.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> SearchInputKind.Url
            trimmed.toLongOrNull() != null -> SearchInputKind.Numeric
            else -> SearchInputKind.Keyword
        }
    }

    fun clearHistory() {
        screenModelScope.launch {
            db.queries.searchHistoryQueries.clearAllSearches()
            loadHistory()
        }
    }

    fun deleteHistory(id: Long) {
        screenModelScope.launch {
            db.queries.searchHistoryQueries.deleteSearch(id)
            loadHistory()
        }
    }

    fun togglePinned(entry: Search_table) {
        screenModelScope.launch {
            db.queries.searchHistoryQueries.setSearchPinned(
                pinned = if (entry.pinned == 1L) 0L else 1L,
                id = entry.id,
            )
            loadHistory()
        }
    }

    fun clearRecentHistory() {
        screenModelScope.launch {
            db.queries.searchHistoryQueries.clearUnpinnedSearches()
            loadHistory()
        }
    }

    fun clearPinnedHistory() {
        screenModelScope.launch {
            db.queries.searchHistoryQueries.clearPinnedSearches()
            loadHistory()
        }
    }

    private suspend fun fetch(tab: SearchTab, word: String, filter: SearchFilter) {
        when (tab) {
            SearchTab.Illust -> {
                val response = if (filter.sort == SearchSort.PopularPreview) {
                    client.appApi.popularPreview(
                        word = word,
                        sort = filter.sort.apiValue,
                        search_target = filter.target.apiValue,
                        merge_plain_keyword_results = true,
                        include_translated_tag_results = true,
                        search_ai_type = filter.aiMode.serverValue(),
                        bookmark_num_min = filter.bookmarkMin,
                        tool = filter.tool,
                        lang = filter.language,
                        start_date = filter.startDate,
                        end_date = filter.endDate,
                        ratio_pattern = filter.ratio?.apiValue,
                        content_type = filter.contentType.apiValue,
                        width_min = filter.resolution?.min,
                        width_max = filter.resolution?.max,
                        height_min = filter.resolution?.min,
                        height_max = filter.resolution?.max,
                    )
                } else {
                    client.appApi.searchIllustManga(
                        word = word,
                        sort = filter.sort.apiValue,
                        search_target = filter.target.apiValue,
                        merge_plain_keyword_results = true,
                        include_translated_tag_results = true,
                        search_ai_type = filter.aiMode.serverValue(),
                        bookmark_num_min = filter.bookmarkMin,
                        tool = filter.tool,
                        lang = filter.language,
                        start_date = filter.startDate,
                        end_date = filter.endDate,
                        ratio_pattern = filter.ratio?.apiValue,
                        content_type = filter.contentType.apiValue,
                        width_min = filter.resolution?.min,
                        width_max = filter.resolution?.max,
                        height_min = filter.resolution?.min,
                        height_max = filter.resolution?.max,
                    )
                }
                illustPager.refresh(response)
                setState(tab, UiState.Success(visibleIllusts(illustPager.items.value, filter)))
            }
            SearchTab.Novel -> {
                val body = filter.bodyLength
                val response = if (filter.sort == SearchSort.PopularPreview) {
                    client.appApi.popularPreviewNovel(
                        word = word,
                        sort = filter.sort.apiValue,
                        search_target = filter.target.apiValue,
                        merge_plain_keyword_results = true,
                        include_translated_tag_results = true,
                        search_ai_type = filter.aiMode.serverValue(),
                        bookmark_num_min = filter.bookmarkMin,
                        genre = filter.genre,
                        lang = filter.language,
                        start_date = filter.startDate,
                        end_date = filter.endDate,
                        is_original_only = filter.isOriginalOnly.takeIf { it },
                        is_replaceable_only = filter.isReplaceableOnly.takeIf { it },
                        text_length_min = body?.valueFor(SearchBodyLengthUnit.Characters, true),
                        text_length_max = body?.valueFor(SearchBodyLengthUnit.Characters, false),
                        word_count_min = body?.valueFor(SearchBodyLengthUnit.Words, true),
                        word_count_max = body?.valueFor(SearchBodyLengthUnit.Words, false),
                        reading_time_min = body?.valueFor(SearchBodyLengthUnit.ReadingMinutes, true),
                        reading_time_max = body?.valueFor(SearchBodyLengthUnit.ReadingMinutes, false),
                    )
                } else {
                    client.appApi.searchNovel(
                        word = word,
                        sort = filter.sort.apiValue,
                        search_target = filter.target.apiValue,
                        merge_plain_keyword_results = true,
                        include_translated_tag_results = true,
                        search_ai_type = filter.aiMode.serverValue(),
                        bookmark_num_min = filter.bookmarkMin,
                        genre = filter.genre,
                        lang = filter.language,
                        start_date = filter.startDate,
                        end_date = filter.endDate,
                        is_original_only = filter.isOriginalOnly.takeIf { it },
                        is_replaceable_only = filter.isReplaceableOnly.takeIf { it },
                        text_length_min = body?.valueFor(SearchBodyLengthUnit.Characters, true),
                        text_length_max = body?.valueFor(SearchBodyLengthUnit.Characters, false),
                        word_count_min = body?.valueFor(SearchBodyLengthUnit.Words, true),
                        word_count_max = body?.valueFor(SearchBodyLengthUnit.Words, false),
                        reading_time_min = body?.valueFor(SearchBodyLengthUnit.ReadingMinutes, true),
                        reading_time_max = body?.valueFor(SearchBodyLengthUnit.ReadingMinutes, false),
                    )
                }
                novelPager.refresh(response)
                setState(tab, UiState.Success(visibleNovels(novelPager.items.value, filter)))
            }
            SearchTab.User -> {
                val response = client.appApi.searchUser(word)
                userPager.refresh(response)
                setState(tab, UiState.Success(userPager.items.value))
            }
        }
    }

    private fun visibleIllusts(items: List<Illust>, filter: SearchFilter): List<Illust> = items.filter { illust ->
        val r18Mode = effectiveR18Mode(filter)
        when (r18Mode) {
            SearchR18Mode.All -> true
            SearchR18Mode.SafeOnly -> (illust.x_restrict ?: 0) <= 0
            SearchR18Mode.R18Only -> (illust.x_restrict ?: 0) > 0
        } && when (filter.aiMode) {
            SearchAiMode.All -> true
            SearchAiMode.ExcludeAi -> illust.illust_ai_type != 2
            SearchAiMode.OnlyAi -> illust.illust_ai_type == 2
        }
    }

    private fun visibleNovels(items: List<Novel>, filter: SearchFilter): List<Novel> = items.filter { novel ->
        (novel.visible != false) && when (effectiveR18Mode(filter)) {
            SearchR18Mode.All -> true
            SearchR18Mode.SafeOnly -> (novel.x_restrict ?: 0) <= 0
            SearchR18Mode.R18Only -> (novel.x_restrict ?: 0) > 0
        } && when (filter.aiMode) {
            SearchAiMode.All -> true
            SearchAiMode.ExcludeAi -> novel.novel_ai_type != 2
            SearchAiMode.OnlyAi -> novel.novel_ai_type == 2
        }
    }

    /** R18 全局开关关闭时强制按全年龄过滤（用户在界面上看不到也选不到 R18） */
    private fun effectiveR18Mode(filter: SearchFilter): SearchR18Mode =
        if (AppContainer.settingsStore.isShowR18) filter.r18Mode else SearchR18Mode.SafeOnly

    private suspend fun loadSearchOptions(
        word: String,
        filter: SearchFilter,
        generation: Long,
    ) {
        try {
            val response = client.appApi.searchOptions(
                word = word,
                search_target = filter.target.apiValue,
            )
            if (generation == searchOptionsGeneration) {
                _searchOptions.value = response
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Dynamic options are optional; the static filter choices remain usable.
        }
    }

    private suspend fun loadTrendingTags() {
        _trendingTags.value = UiState.Loading
        try {
            _trendingTags.value = UiState.Success(client.appApi.trendingTags("illust").displayList.take(15))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _trendingTags.value = UiState.Error(e.message ?: "热门标签加载失败")
        }
    }

    private fun requestSuggestions(value: String) {
        suggestionJob?.cancel()
        if (value.isBlank()) {
            _suggestions.value = emptyList()
            return
        }
        val job = screenModelScope.launch {
            delay(300)
            try {
                val response = client.appApi.searchAutocomplete(value)
                val suggestions = response.getList().orEmpty().mapNotNull { item ->
                    item.getTag().takeIf { it.isNotBlank() }?.let { tag ->
                        SearchSuggestion(tag, item.getTranslated_name())
                    }
                }
                val currentJob = coroutineContext[Job]
                if (suggestionJob === currentJob && currentJob?.isActive == true) {
                    _suggestions.value = suggestions
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                val currentJob = coroutineContext[Job]
                if (suggestionJob === currentJob && currentJob?.isActive == true) {
                    _suggestions.value = emptyList()
                }
            }
        }
        suggestionJob = job
    }

    private fun clearSuggestions() {
        suggestionJob?.cancel()
        _suggestions.value = emptyList()
    }

    private fun readClipboardSuggestion() {
        screenModelScope.launch {
            val value = withContext(Dispatchers.IO) {
                try {
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    val contents = clipboard.getContents(null)
                    if (contents?.isDataFlavorSupported(DataFlavor.stringFlavor) == true) {
                        contents.getTransferData(DataFlavor.stringFlavor).toString().trim()
                    } else {
                        null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
            if (!value.isNullOrBlank() && classifyInput(value) != SearchInputKind.Keyword) {
                _clipboardSuggestion.value = value
            }
        }
    }

    private fun setQueryParts(word: String) {
        _tags.value = word.trim().split(Regex("\\s+")).filter(String::isNotBlank).distinct()
        _input.value = ""
        updateQueryValue()
    }

    private fun addTags(newTags: List<String>) {
        if (newTags.isEmpty()) return
        _tags.value = (_tags.value + newTags.map(String::trim).filter(String::isNotBlank)).distinct()
    }

    private fun updateQueryValue() {
        _query.value = (_tags.value + _input.value.trim())
            .filter(String::isNotBlank)
            .joinToString(" ")
    }

    private fun filterFor(tab: SearchTab): SearchFilter = when (tab) {
        SearchTab.Illust -> _illustFilter.value
        SearchTab.Novel -> _novelFilter.value
        SearchTab.User -> SearchFilter()
    }

    private fun setState(tab: SearchTab, state: UiState<*>) {
        when (tab) {
            SearchTab.Illust -> setIllustState(state)
            SearchTab.Novel -> setNovelState(state)
            SearchTab.User -> setUserState(state)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setIllustState(state: UiState<*>) {
        _illustState.value = state as UiState<List<Illust>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun setNovelState(state: UiState<*>) {
        _novelState.value = state as UiState<List<Novel>>
    }

    @Suppress("UNCHECKED_CAST")
    private fun setUserState(state: UiState<*>) {
        _userState.value = state as UiState<List<UserPreview>>
    }

    private fun setError(tab: SearchTab, message: String) {
        when (tab) {
            SearchTab.Illust -> _illustState.value = UiState.Error(message)
            SearchTab.Novel -> _novelState.value = UiState.Error(message)
            SearchTab.User -> _userState.value = UiState.Error(message)
        }
    }

    private fun setErrorIfNeeded(tab: SearchTab, message: String) {
        val state = when (tab) {
            SearchTab.Illust -> _illustState.value
            SearchTab.Novel -> _novelState.value
            SearchTab.User -> _userState.value
        }
        if (state !is UiState.Success) setError(tab, message)
    }

    private fun updateNovelBookmark(novelId: Long, isBookmarked: Boolean) {
        novelPager.updateItems { items ->
            items.map { item ->
                if (item.id == novelId) item.copy(is_bookmarked = isBookmarked) else item
            }
        }
        setState(SearchTab.Novel, UiState.Success(visibleNovels(novelPager.items.value, _novelFilter.value)))
    }

    /** R18 开关变化时重新过滤已加载的搜索结果；effectiveR18Mode 会按当前开关重算 */
    private fun republishIfLoaded() {
        if (_illustState.value is UiState.Success) {
            setState(SearchTab.Illust, UiState.Success(visibleIllusts(illustPager.items.value, _illustFilter.value)))
        }
        if (_novelState.value is UiState.Success) {
            setState(SearchTab.Novel, UiState.Success(visibleNovels(novelPager.items.value, _novelFilter.value)))
        }
    }

    private suspend fun saveSearchHistory(word: String) {
        val existing = db.queries.searchHistoryQueries.selectSearchByKeyword(word).executeAsOneOrNull()
        if (existing?.pinned == 1L) {
            db.queries.searchHistoryQueries.insertSearch(
                id = existing.id,
                keyword = word,
                searchTime = System.currentTimeMillis(),
                searchType = existing.searchType,
                pinned = 1L,
                previewIllustsJson = existing.previewIllustsJson,
            )
        } else {
            db.queries.searchHistoryQueries.deleteSearchByKeyword(word)
            db.queries.searchHistoryQueries.insertKeywordOnly(word, System.currentTimeMillis(), 0L)
        }
        loadHistory()
    }

    private suspend fun loadHistory() {
        _pinnedHistory.value = db.queries.searchHistoryQueries.selectPinnedSearches().executeAsList()
        _recentHistory.value = db.queries.searchHistoryQueries.selectRecentUnpinnedSearches(50L).executeAsList()
    }
}

internal fun SearchAiMode.serverValue(): Int = if (this == SearchAiMode.ExcludeAi) 0 else 1

private fun SearchBodyLength.valueFor(unit: SearchBodyLengthUnit, min: Boolean): Int? {
    if (this.unit != unit) return null
    return if (min) this.min else this.max
}
