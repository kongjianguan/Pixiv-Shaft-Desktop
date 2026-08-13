package ceui.pixiv.ui.screen.novel

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.Novel
import ceui.loxia.NovelSeriesDetail
import ceui.loxia.NovelSeriesResp
import ceui.pixiv.di.AppContainer
import ceui.pixiv.download.NovelMergeFormat
import ceui.pixiv.net.api.Client
import ceui.pixiv.store.SettingsStore
import ceui.pixiv.ui.state.Pager
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.ui.util.observeR18Toggle
import ceui.pixiv.ui.util.visibleNovels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class NovelSeriesScreenModel(
    private val seriesId: Long,
    private val client: Client = AppContainer.client,
    private val settingsStore: SettingsStore = AppContainer.settingsStore,
) : ScreenModel {

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

    /** 章节多选模式（顶栏「下载 → 章节多选」进入） */
    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    /** 「全选」按钮状态：区分「已全部选中」与「还有分页未加载」，避免文案与行为不一致 */
    private val _allSelected = MutableStateFlow(false)
    val allSelected: StateFlow<Boolean> = _allSelected.asStateFlow()

    /** 「全部逐篇」拉取章节列表期间的加载态 */
    private val _resolvingChapters = MutableStateFlow(false)
    val resolvingChapters: StateFlow<Boolean> = _resolvingChapters.asStateFlow()

    /** 全量章节缓存：全选/下载已拉取过的完整列表，避免反复分页拉取（500ms/页 + 429 风险） */
    private var allChaptersCache: List<Novel>? = null

    /** 在途的全量章节拉取任务：退出多选时取消，防止完成后把选中态写回已退出的界面 */
    private var chaptersFetchJob: kotlinx.coroutines.Job? = null

    /** 保留接口原始最新章节，R18 开关切换时可重新决定是否展示入口。 */
    private var rawLatestNovel: Novel? = null

    init {
        screenModelScope.launch { loadSeries(showLoading = true) }
        observeR18Toggle(::republishIfLoaded, settingsStore)
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
        allChaptersCache = null
        try {
            val response = client.appApi.getNovelSeries(seriesId)
            val detail = response.novel_series_detail
                ?: throw IllegalStateException("Novel series not found")
            pager.refresh(response)
            _seriesState.value = UiState.Success(detail)
            rawLatestNovel = response.novel_series_latest_novel
            publishChapters()
            // 首页可能都是 R18 或 visible=false；自动跳过直到出现可展示章节或没有下一页。
            pager.loadMoreUntil(::hasVisibleChapters, ::publishChapters)
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
                publishChapters()
                pager.loadMoreUntil(::hasVisibleChapters, ::publishChapters)
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
        ceui.pixiv.ui.util.toggleNovelBookmark(
            scope = screenModelScope,
            client = client,
            novel = novel,
            inFlight = bookmarkingNovels,
            updateLocal = ::updateNovelBookmark,
        )
    }

    fun enterSelectionMode() {
        _selectionMode.value = true
    }

    fun exitSelectionMode() {
        // 先取消在途拉取：否则拉取完成后仍会把全部章节写回 _selectedIds（协程不受
        // _selectionMode 影响），下次进入多选时所有章节被预选。
        chaptersFetchJob?.cancel()
        chaptersFetchJob = null
        _selectionMode.value = false
        _selectedIds.value = emptySet()
        _allSelected.value = false
    }

    fun toggleSelect(novelId: Long) {
        _selectedIds.value = if (novelId in _selectedIds.value) {
            _selectedIds.value - novelId
        } else {
            _selectedIds.value + novelId
        }
        _allSelected.value = false
    }

    /** 全选 / 取消全选：还有分页未加载时先拉全量再全选，避免「全选」只选中已加载章节 */
    fun selectAllToggle() {
        if (_allSelected.value) {
            // 已全选时点按是「取消全选」：直接清空，不再进入拉全量分支（重新拉取会重新选中全部章节）
            _selectedIds.value = emptySet()
            _allSelected.value = false
            return
        }
        val all = visibleChapters().map { it.id }.toSet()
        if (all.isEmpty()) return
        if (pager.hasNext.value) {
            // 拉取中再点「全选」视为重新开始：取消旧拉取，避免并发重复分页
            chaptersFetchJob?.cancel()
            _resolvingChapters.value = true
            chaptersFetchJob = screenModelScope.launch {
                try {
                    val detail = (_seriesState.value as? UiState.Success)?.data
                    val seriesTotal = detail?.content_count?.takeIf { it > 0 } ?: 0
                    // 复用已拉取过的全量缓存，避免每次「全选」都重新分页拉取（500ms/页 + 429 风险）
                    val allChapters = chaptersForDownload(seriesTotal) ?: loadAllChapters()
                    allChaptersCache = allChapters
                    if (!_selectionMode.value) return@launch
                    _selectedIds.value = filterVisibleChapters(allChapters).map { it.id }.toSet()
                    _allSelected.value = true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (!_selectionMode.value) return@launch
                    // 拉全量失败：只选中已加载章节，不能声称「全部选中」，
                    // 否则按钮显示「取消全选」但未加载章节实际未被选中
                    _selectedIds.value = all
                    _allSelected.value = false
                } finally {
                    // 只有仍是当前拉取任务时才复位加载态（被新任务取代时由新任务管理）
                    if (chaptersFetchJob === kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]) {
                        _resolvingChapters.value = false
                    }
                }
            }
        } else {
            _allSelected.value = true
            _selectedIds.value = all
        }
    }

    /** 下载勾选章节：先拉全系列章节列表，保证刷新后勾选章节不丢、序号不串 */
    fun downloadSelected() {
        val detail = (_seriesState.value as? UiState.Success)?.data
        val seriesTitle = detail?.title ?: "series_$seriesId"
        val seriesTotal = detail?.content_count?.takeIf { it > 0 } ?: visibleChapters().size
        if (_selectedIds.value.isEmpty()) {
            exitSelectionMode()
            return
        }
        _resolvingChapters.value = true
        // 取消在途的全选拉取，避免与本次下载并发重复分页拉取
        chaptersFetchJob?.cancel()
        chaptersFetchJob = screenModelScope.launch {
            try {
                val fullList = chaptersForDownload(seriesTotal) ?: loadAllChapters()
                if (!_selectionMode.value) return@launch
                allChaptersCache = fullList
                val selected = fullList.filter { it.id in _selectedIds.value }
                if (selected.isEmpty()) {
                    exitSelectionMode()
                    return@launch
                }
                val orderOf = fullList.withIndex()
                    .associate { (index, chapter) -> chapter.id to index + 1 }
                withContext(Dispatchers.IO) {
                    selected.forEach { chapter ->
                        AppContainer.downloadManager.enqueueNovelSeriesChapter(
                            novel = chapter,
                            seriesId = seriesId,
                            seriesTitle = seriesTitle,
                            seriesOrder = orderOf[chapter.id] ?: 1,
                            seriesTotal = seriesTotal,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 拉全量失败时退回已加载章节（只有已加载页中的选中章节能入队）；
                // 序号基于已加载列表推导，可能与系列绝对序号不一致（仅影响文件名/头部元数据）
                val selected = visibleChapters().filter { it.id in _selectedIds.value }
                withContext(Dispatchers.IO) {
                    selected.forEach { chapter ->
                        AppContainer.downloadManager.enqueueNovelSeriesChapter(
                            novel = chapter,
                            seriesId = seriesId,
                            seriesTitle = seriesTitle,
                            seriesOrder = chapterSeriesOrder(chapter.id),
                            seriesTotal = seriesTotal,
                        )
                    }
                }
            } finally {
                _resolvingChapters.value = false
                exitSelectionMode()
            }
        }
    }

    /** 全部逐篇下载：先拉全系列章节列表，再逐章入队 */
    fun downloadAllSeparate() {
        if (!_resolvingChapters.compareAndSet(false, true)) return
        screenModelScope.launch {
            try {
                val all = loadAllChapters()
                if (all.isNotEmpty()) {
                    val detail = (_seriesState.value as? UiState.Success)?.data
                    val seriesTitle = detail?.title ?: "series_$seriesId"
                    val seriesTotal = detail?.content_count?.takeIf { it > 0 } ?: all.size
                    withContext(Dispatchers.IO) {
                        all.forEachIndexed { index, chapter ->
                            AppContainer.downloadManager.enqueueNovelSeriesChapter(
                                novel = chapter,
                                seriesId = seriesId,
                                seriesTitle = seriesTitle,
                                seriesOrder = index + 1,
                                seriesTotal = seriesTotal,
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 拉章节列表失败时保持已入队章节不受影响
            } finally {
                _resolvingChapters.value = false
            }
        }
    }

    /** 合并导出：直接入队一个系列合并任务 */
    fun downloadMerge(format: NovelMergeFormat) {
        val detail = (_seriesState.value as? UiState.Success)?.data
        val title = detail?.title ?: "series_$seriesId"
        AppContainer.downloadManager.enqueueNovelMerge(seriesId, title, format)
    }

    /** 分页拉全系列章节列表；实现见 [fetchSeriesChapters]（与下载器共用） */
    suspend fun loadAllChapters(): List<Novel> =
        ceui.pixiv.download.fetchSeriesChapters(
            appApi = client.appApi,
            seriesId = seriesId,
            pageDelayMs = SERIES_PAGE_DELAY_MS,
        ).chapters

    /**
     * 下载用全量章节列表：优先复用已拉取过的缓存（全选/上次下载），
     * 其次 Pager 已翻到底（hasNext=false）时直接用已加载列表；
     * 都没有（或缓存不完整）才重新分页拉取。
     */
    private suspend fun chaptersForDownload(expectedTotal: Int): List<Novel>? {
        val cached = allChaptersCache
        if (cached != null && cached.isNotEmpty() && (expectedTotal <= 0 || cached.size >= expectedTotal)) {
            return cached
        }
        val loaded = pager.items.value
        if (loaded.isNotEmpty() && !pager.hasNext.value) {
            return loaded
        }
        return null
    }

    /**
     * 章节在系列中的 1-based 序号。基于 Pager 已加载的完整列表（不过滤 visible=false，
     * 避免隐藏章节导致序号偏移）；仅覆盖已加载章节，未加载的返回 1 兜底。
     */
    private fun chapterSeriesOrder(novelId: Long): Int =
        pager.items.value.indexOfFirst { it.id == novelId }.let { if (it >= 0) it + 1 else 1 }

    private fun visibleChapters(): List<Novel> = filterVisibleChapters(pager.items.value)

    private fun filterVisibleChapters(chapters: List<Novel>): List<Novel> =
        visibleNovels(chapters, settingsStore.isShowR18)

    private fun hasVisibleChapters(): Boolean =
        (_novelsState.value as? UiState.Success)?.data?.isNotEmpty() == true

    private fun publishChapters() {
        _novelsState.value = UiState.Success(visibleChapters())
        _latestNovel.value = rawLatestNovel?.let { filterVisibleChapters(listOf(it)).firstOrNull() }
    }

    /** R18 开关变化时只重新发布已加载章节，Pager 与下载序号仍保留原始完整列表。 */
    private fun republishIfLoaded() {
        if (_novelsState.value is UiState.Success) {
            publishChapters()
        }
    }

    private fun updateSeries(detail: NovelSeriesDetail) {
        _seriesState.value = UiState.Success(detail)
    }

    private fun updateNovelBookmark(novelId: Long, isBookmarked: Boolean) {
        pager.updateItems { novels ->
            novels.map { if (it.id == novelId) it.copy(is_bookmarked = isBookmarked) else it }
        }
        publishChapters()
    }

    private companion object {
        const val SERIES_PAGE_DELAY_MS = 500L
    }
}
