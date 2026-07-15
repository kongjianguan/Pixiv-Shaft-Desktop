package ceui.pixiv.ui.screen.novel

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.Novel
import ceui.loxia.NovelSeriesDetail
import ceui.loxia.NovelSeriesResp
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.state.Pager
import ceui.pixiv.ui.state.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class NovelSeriesScreenModel(
    private val seriesId: Long,
) : ScreenModel {

    private val client = AppContainer.client
    private val pager = Pager<NovelSeriesResp, Novel>(client, NovelSeriesResp::class.java)
    private val loadingMore = AtomicBoolean(false)
    private val watchlistInFlight = AtomicBoolean(false)
    private val followInFlight = AtomicBoolean(false)
    private val bookmarkingNovels = ConcurrentHashMap.newKeySet<Long>()

    private val _seriesState = MutableStateFlow<UiState<NovelSeriesDetail>>(UiState.Loading)
    val seriesState: StateFlow<UiState<NovelSeriesDetail>> = _seriesState.asStateFlow()

    private val _novelsState = MutableStateFlow<UiState<List<Novel>>>(UiState.Loading)
    val novelsState: StateFlow<UiState<List<Novel>>> = _novelsState.asStateFlow()

    private val _latestNovel = MutableStateFlow<Novel?>(null)
    val latestNovel: StateFlow<Novel?> = _latestNovel.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        screenModelScope.launch { loadSeries(showLoading = true) }
    }

    fun refresh() {
        screenModelScope.launch {
            _isRefreshing.value = true
            loadSeries(showLoading = false)
            _isRefreshing.value = false
        }
    }

    private suspend fun loadSeries(showLoading: Boolean) {
        if (showLoading) {
            _seriesState.value = UiState.Loading
            _novelsState.value = UiState.Loading
        }
        try {
            val response = client.appApi.getNovelSeries(seriesId)
            val detail = response.novel_series_detail
                ?: throw IllegalStateException("Novel series not found")
            pager.refresh(response)
            _seriesState.value = UiState.Success(detail)
            _latestNovel.value = response.novel_series_latest_novel
            _novelsState.value = UiState.Success(visibleNovels())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_seriesState.value !is UiState.Success) {
                _seriesState.value = UiState.Error(e.message ?: "Failed to load series")
            }
            if (_novelsState.value !is UiState.Success) {
                _novelsState.value = UiState.Error(e.message ?: "Failed to load chapters")
            }
        }
    }

    fun loadMore() {
        if (!pager.hasNext.value || !loadingMore.compareAndSet(false, true)) return
        screenModelScope.launch {
            try {
                pager.loadMore()
                _novelsState.value = UiState.Success(visibleNovels())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep already-loaded chapters visible when the next page fails.
            } finally {
                loadingMore.set(false)
            }
        }
    }

    fun toggleWatchlist() {
        val detail = (_seriesState.value as? UiState.Success)?.data ?: return
        if (!watchlistInFlight.compareAndSet(false, true)) return
        val wasAdded = detail.watchlist_added == true
        updateSeries(detail.copy(watchlist_added = !wasAdded))
        screenModelScope.launch {
            try {
                if (wasAdded) {
                    client.appApi.removeNovelSeriesFromWatchlist(seriesId)
                } else {
                    client.appApi.addNovelSeriesToWatchlist(seriesId)
                }
            } catch (e: CancellationException) {
                updateSeries(detail)
                throw e
            } catch (_: Exception) {
                updateSeries(detail)
            } finally {
                watchlistInFlight.set(false)
            }
        }
    }

    fun toggleFollow() {
        val detail = (_seriesState.value as? UiState.Success)?.data ?: return
        val user = detail.user ?: return
        if (user.id <= 0L || !followInFlight.compareAndSet(false, true)) return
        val wasFollowed = user.is_followed == true
        updateSeries(detail.copy(user = user.copy(is_followed = !wasFollowed)))
        screenModelScope.launch {
            try {
                if (wasFollowed) {
                    client.appApi.postUnFollow(user.id)
                } else {
                    client.appApi.postFollow(user.id, "public")
                }
            } catch (e: CancellationException) {
                updateSeries(detail)
                throw e
            } catch (_: Exception) {
                updateSeries(detail)
            } finally {
                followInFlight.set(false)
            }
        }
    }

    fun toggleNovelBookmark(novel: Novel) {
        if (!bookmarkingNovels.add(novel.id)) return
        val wasBookmarked = novel.is_bookmarked == true
        updateNovelBookmark(novel.id, !wasBookmarked)
        screenModelScope.launch {
            try {
                if (wasBookmarked) {
                    client.appApi.removeNovelBookmark(novel.id)
                } else {
                    client.appApi.addNovelBookmark(novel.id, "public")
                }
            } catch (e: CancellationException) {
                updateNovelBookmark(novel.id, wasBookmarked)
                throw e
            } catch (_: Exception) {
                updateNovelBookmark(novel.id, wasBookmarked)
            } finally {
                bookmarkingNovels.remove(novel.id)
            }
        }
    }

    private fun visibleNovels(): List<Novel> = pager.items.value.filter { it.visible != false }

    private fun updateSeries(detail: NovelSeriesDetail) {
        _seriesState.value = UiState.Success(detail)
    }

    private fun updateNovelBookmark(novelId: Long, isBookmarked: Boolean) {
        pager.updateItems { novels ->
            novels.map { if (it.id == novelId) it.copy(is_bookmarked = isBookmarked) else it }
        }
        _novelsState.value = UiState.Success(visibleNovels())
    }
}
