package ceui.pixiv.ui.screen.novel

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.Comment
import ceui.loxia.CommentResponse
import ceui.loxia.Novel
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.history.BrowseHistoryRecorder
import ceui.pixiv.ui.state.Pager
import ceui.pixiv.ui.state.UiState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

class NovelDetailScreenModel(
    private val novelId: Long
) : ScreenModel {

    private val client = AppContainer.client
    private val commentsPager = Pager<CommentResponse, Comment>(client, CommentResponse::class.java)
    private val commentsOperationMutex = Mutex()
    private val locallyAddedComments = mutableListOf<Comment>()

    private val _state = MutableStateFlow<UiState<Novel>>(UiState.Loading)
    val state: StateFlow<UiState<Novel>> = _state.asStateFlow()
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
    private val bookmarkInFlight = AtomicBoolean(false)
    private val followInFlight = AtomicBoolean(false)

    init {
        loadNovel()
        loadComments()
    }

    fun reload() {
        loadNovel()
    }

    fun toggleBookmark(restrict: String = "public") {
        val novel = (_state.value as? UiState.Success)?.data ?: return
        val current = novel.is_bookmarked ?: return
        if (!bookmarkInFlight.compareAndSet(false, true)) return

        val updated = novel.copy(is_bookmarked = !current)
        _state.value = UiState.Success(updated)
        screenModelScope.launch {
            try {
                if (current) {
                    client.appApi.removeNovelBookmark(novel.id)
                } else {
                    client.appApi.addNovelBookmark(novel.id, restrict)
                }
            } catch (e: CancellationException) {
                _state.value = UiState.Success(novel)
                throw e
            } catch (_: Exception) {
                _state.value = UiState.Success(novel)
            } finally {
                bookmarkInFlight.set(false)
            }
        }
    }

    fun toggleFollow(restrict: String = "public") {
        val novel = (_state.value as? UiState.Success)?.data ?: return
        val user = novel.user ?: return
        val current = user.is_followed ?: return
        if (user.id <= 0L || !followInFlight.compareAndSet(false, true)) return

        val updated = novel.copy(user = user.copy(is_followed = !current))
        _state.value = UiState.Success(updated)
        screenModelScope.launch {
            try {
                if (current) {
                    client.appApi.postUnFollow(user.id)
                } else {
                    client.appApi.postFollow(user.id, restrict)
                }
            } catch (e: CancellationException) {
                _state.value = UiState.Success(novel)
                throw e
            } catch (_: Exception) {
                _state.value = UiState.Success(novel)
            } finally {
                followInFlight.set(false)
            }
        }
    }

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
                    val response = client.appApi.postNovelComment(novelId, draft)
                    val comment = response.comment ?: throw IllegalStateException("服务器没有返回评论")
                    locallyAddedComments.removeAll { it.id > 0L && it.id == comment.id }
                    locallyAddedComments.add(comment)
                    commentsPager.updateItems { mergeComments(it) }
                    _commentsState.value = UiState.Success(commentsPager.items.value)
                    _commentsHasMore.value = commentsPager.hasNext.value
                    (_state.value as? UiState.Success)?.data?.let { novel ->
                        val total = novel.total_comments ?: 0
                        _state.value = UiState.Success(novel.copy(total_comments = total + 1))
                    }
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
                    val response = client.appApi.getNovelComments(novelId)
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

    private fun loadNovel() {
        screenModelScope.launch {
            _state.value = UiState.Loading
            try {
                val resp = client.appApi.getNovel(novelId)
                val novel = resp.novel
                if (novel == null) {
                    _state.value = UiState.Error("Novel not found")
                } else {
                    BrowseHistoryRecorder.recordNovel(novel)
                    _state.value = UiState.Success(novel)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Failed to load novel")
            }
        }
    }
}
