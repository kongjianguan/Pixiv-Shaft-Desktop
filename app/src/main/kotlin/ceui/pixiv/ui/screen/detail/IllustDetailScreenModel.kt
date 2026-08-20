package ceui.pixiv.ui.screen.detail

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.Illust
import ceui.loxia.ObjectType
import ceui.loxia.UgoiraMetaData
import ceui.loxia.toIllust
import ceui.pixiv.di.AppContainer
import ceui.pixiv.download.DownloadManager
import ceui.pixiv.ui.history.BrowseHistoryRecorder
import ceui.pixiv.ui.screen.comment.CommentsController
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.ui.util.observeR18Toggle
import ceui.pixiv.ui.util.visibleItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class IllustDetailScreenModel(
    private val illustId: Long
) : ScreenModel {

    private val client = AppContainer.client
    private val downloadManager: DownloadManager = AppContainer.downloadManager
    private var rawRelated: List<Illust> = emptyList()

    /** 评论逻辑全部收口在 CommentsController（插画/小说/全屏页三处复用） */
    val commentsController = CommentsController(
        client = client,
        workType = ObjectType.ILLUST,
        workId = illustId,
        scope = screenModelScope,
    )

    private val _illustState = MutableStateFlow<UiState<Illust>>(UiState.Loading)
    val illustState: StateFlow<UiState<Illust>> = _illustState.asStateFlow()

    private val _relatedState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val relatedState: StateFlow<UiState<List<Illust>>> = _relatedState.asStateFlow()

    private val _ugoiraState = MutableStateFlow<UiState<UgoiraMetaData?>>(UiState.Loading)
    val ugoiraState: StateFlow<UiState<UgoiraMetaData?>> = _ugoiraState.asStateFlow()

    private val _isBookmarked = MutableStateFlow<Boolean?>(null)
    val isBookmarked: StateFlow<Boolean?> = _isBookmarked.asStateFlow()

    private val _isFollowing = MutableStateFlow<Boolean?>(null)
    val isFollowing: StateFlow<Boolean?> = _isFollowing.asStateFlow()
    private var _userId: Long = 0

    init {
        loadIllust()
        loadRelated()
        commentsController.loadInitial()
        observeR18Toggle(::republishIfLoaded)
    }

    private fun loadIllust() {
        screenModelScope.launch {
            _illustState.value = UiState.Loading
            var appError: Throwable? = null
            // Pixiv may return a visible=false placeholder instead of an error.
            // The web Ajax endpoint is a deliberate second source, not a general
            // retry: it is only used when the app response is absent or hidden.
            val appIllust = try {
                client.appApi.getIllust(illustId).illust
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                appError = e
                null
            }
            val illust = when {
                appIllust == null -> fetchWebIllustFallback()
                appIllust.visible == false -> fetchWebIllustFallback()
                else -> appIllust
            }

            if (illust == null) {
                _illustState.value = UiState.Error(
                    appError?.message ?: "Illust not found"
                )
            } else {
                if (illust.isGif()) loadUgoira(illust.id)
                _isBookmarked.value = illust.is_bookmarked
                _isFollowing.value = illust.user?.is_followed
                _userId = illust.user?.id ?: 0
                BrowseHistoryRecorder.recordIllust(illust)
                _illustState.value = UiState.Success(illust)
            }
        }
    }

    private suspend fun fetchWebIllustFallback(): Illust? {
        return try {
            val response = client.webApi.getWebIllust(illustId)
            val body = response.body
            if (response.error == true || body == null || body.urls?.original.isNullOrBlank()) {
                null
            } else {
                val pages = if (body.pageCount > 1) {
                    try {
                        client.webApi.getIllustPages(illustId).body
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
                body.toIllust(illustId, pages)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun loadUgoira(illustId: Long) {
        screenModelScope.launch {
            _ugoiraState.value = UiState.Loading
            try {
                val resp = client.appApi.getUgoiraMetadata(illustId)
                _ugoiraState.value = UiState.Success(resp.ugoira_metadata)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ugoiraState.value = UiState.Error(e.message ?: "Failed to load ugoira metadata")
            }
        }
    }

    fun toggleBookmark(restrict: String = "public") {
        val current = _isBookmarked.value ?: return
        val id = illustId
        screenModelScope.launch {
            _isBookmarked.value = !current
            try {
                if (current) {
                    client.appApi.removeBookmark(id)
                } else {
                    client.appApi.postBookmark(id, restrict)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _isBookmarked.value = current
            }
        }
    }

    fun toggleFollow(restrict: String = "public") {
        val current = _isFollowing.value ?: return
        val userId = _userId
        if (userId == 0L) return
        screenModelScope.launch {
            _isFollowing.value = !current
            try {
                if (current) {
                    client.appApi.postUnFollow(userId)
                } else {
                    client.appApi.postFollow(userId, restrict)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _isFollowing.value = current
            }
        }
    }

    fun enqueueDownload(illust: Illust): Int = downloadManager.enqueueIllust(illust)

    fun enqueueUgoira(illust: Illust, metadata: UgoiraMetaData): Int =
        downloadManager.enqueueUgoira(illust, metadata)

    private fun loadRelated() {
        screenModelScope.launch {
            _relatedState.value = UiState.Loading
            try {
                val resp = client.appApi.getRelatedIllusts(illustId)
                rawRelated = resp.displayList
                _relatedState.value = UiState.Success(visibleItems(rawRelated))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _relatedState.value = UiState.Error(e.message ?: "Failed to load related")
            }
        }
    }

    /** R18 开关变化时重新过滤「相关作品」（保留完整数据） */
    private fun republishIfLoaded() {
        if (_relatedState.value is UiState.Success) {
            _relatedState.value = UiState.Success(visibleItems(rawRelated))
        }
    }
}
