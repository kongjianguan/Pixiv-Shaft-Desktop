package ceui.pixiv.ui.screen.detail

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.GifInfoResponse
import ceui.loxia.Comment
import ceui.loxia.CommentResponse
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.UgoiraMetaData
import ceui.pixiv.di.AppContainer
import ceui.pixiv.download.DownloadManager
import ceui.pixiv.ui.history.BrowseHistoryRecorder
import ceui.pixiv.ui.state.Pager
import ceui.pixiv.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

class IllustDetailScreenModel(
    private val illustId: Long
) : ScreenModel {

    private val client = AppContainer.client
    private val downloadManager: DownloadManager = AppContainer.downloadManager
    private val relatedPager = Pager<IllustResponse, Illust>(client, IllustResponse::class.java)
    private val commentsPager = Pager<CommentResponse, Comment>(client, CommentResponse::class.java)
    private val commentsOperationMutex = Mutex()
    private val locallyAddedComments = mutableListOf<Comment>()

    private val _illustState = MutableStateFlow<UiState<Illust>>(UiState.Loading)
    val illustState: StateFlow<UiState<Illust>> = _illustState.asStateFlow()

    private val _relatedState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val relatedState: StateFlow<UiState<List<Illust>>> = _relatedState.asStateFlow()

    private val _commentsState = MutableStateFlow<UiState<List<Comment>>>(UiState.Loading)
    val commentsState: StateFlow<UiState<List<Comment>>> = _commentsState.asStateFlow()

    private val _commentsHasMore = MutableStateFlow(false)
    val commentsHasMore: StateFlow<Boolean> = _commentsHasMore.asStateFlow()

    private val _commentsLoadingMore = MutableStateFlow(false)
    val commentsLoadingMore: StateFlow<Boolean> = _commentsLoadingMore.asStateFlow()

    private val _commentDraft = MutableStateFlow("")
    val commentDraft: StateFlow<String> = _commentDraft.asStateFlow()

    private val _commentSubmitting = MutableStateFlow(false)
    val commentSubmitting: StateFlow<Boolean> = _commentSubmitting.asStateFlow()

    private val _commentError = MutableStateFlow<String?>(null)
    val commentError: StateFlow<String?> = _commentError.asStateFlow()

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
        loadComments()
    }

    private fun loadIllust() {
        screenModelScope.launch {
            _illustState.value = UiState.Loading
            try {
                val resp = client.appApi.getIllust(illustId)
                val illust = resp.illust
                if (illust == null) {
                    _illustState.value = UiState.Error("Illust not found")
                } else {
                    if (illust.isGif()) loadUgoira(illust.id)
                    _isBookmarked.value = illust.is_bookmarked
                    _isFollowing.value = illust.user?.is_followed
                    _userId = illust.user?.id ?: 0
                    BrowseHistoryRecorder.recordIllust(illust)
                    _illustState.value = UiState.Success(illust)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _illustState.value = UiState.Error(e.message ?: "Failed to load illust")
            }
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

    fun updateCommentDraft(value: String) {
        _commentDraft.value = value
    }

    fun retryComments() {
        loadComments()
    }

    fun loadMoreComments() {
        if (!_commentsHasMore.value || _commentsLoadingMore.value) return
        screenModelScope.launch {
            commentsOperationMutex.withLock {
                if (!_commentsHasMore.value || _commentsLoadingMore.value) return@withLock
                _commentsLoadingMore.value = true
                _commentError.value = null
                try {
                    commentsPager.loadMore()
                    commentsPager.updateItems { mergeComments(it) }
                    _commentsHasMore.value = commentsPager.hasNext.value
                    _commentsState.value = UiState.Success(commentsPager.items.value)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _commentError.value = e.message ?: "加载更多评论失败"
                } finally {
                    _commentsLoadingMore.value = false
                }
            }
        }
    }

    fun submitComment() {
        val draft = _commentDraft.value.trim()
        if (draft.isEmpty() || _commentSubmitting.value) return

        screenModelScope.launch {
            commentsOperationMutex.withLock {
                if (_commentsState.value is UiState.Loading || _commentSubmitting.value) return@withLock
                _commentSubmitting.value = true
                _commentError.value = null
                try {
                    val response = client.appApi.postIllustComment(illustId, draft)
                    val comment = response.comment ?: throw IllegalStateException("服务器没有返回评论")
                    locallyAddedComments.removeAll { it.id > 0L && it.id == comment.id }
                    locallyAddedComments.add(comment)
                    commentsPager.updateItems { mergeComments(it) }
                    _commentsState.value = UiState.Success(commentsPager.items.value)
                    _commentsHasMore.value = commentsPager.hasNext.value
                    if (_commentDraft.value.trim() == draft) {
                        _commentDraft.value = ""
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _commentError.value = e.message ?: "发表评论失败"
                } finally {
                    _commentSubmitting.value = false
                }
            }
        }
    }

    private fun loadComments() {
        screenModelScope.launch {
            commentsOperationMutex.withLock {
                _commentsState.value = UiState.Loading
                _commentsHasMore.value = false
                _commentError.value = null
                try {
                    val response = client.appApi.getIllustComments(illustId)
                    commentsPager.refresh(response)
                    commentsPager.updateItems { mergeComments(it) }
                    _commentsHasMore.value = commentsPager.hasNext.value
                    _commentsState.value = UiState.Success(commentsPager.items.value)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _commentsState.value = UiState.Error(e.message ?: "加载评论失败")
                }
            }
        }
    }

    private fun mergeComments(items: List<Comment>): List<Comment> {
        val seenIds = HashSet<Long>()
        val deduplicated = items.filter { comment ->
            comment.id <= 0L || seenIds.add(comment.id)
        }
        val existingIds = deduplicated.asSequence()
            .map { it.id }
            .filter { it > 0L }
            .toSet()
        val localOnly = locallyAddedComments.filter { it.id <= 0L || it.id !in existingIds }
        return localOnly + deduplicated
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
