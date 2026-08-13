package ceui.pixiv.ui.screen.comment

import ceui.loxia.Comment
import ceui.loxia.CommentResponse
import ceui.loxia.ObjectType
import ceui.loxia.Stamp
import ceui.pixiv.net.api.Client
import ceui.pixiv.ui.state.Pager
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.ui.util.SelfUserIdResolver
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * 评论「加载/分页/发表/回复/删除/贴纸」的共享逻辑，插画详情、小说详情、评论全屏页三处复用。
 * 状态全部放在公开只读的 StateFlow 里，UI 层 collectAsState 即可。生命周期跟随注入的 [scope]
 * （调用方传 ScreenModel 的 screenModelScope）。
 */
class CommentsController(
    private val client: Client,
    private val workType: String, // "illust" / "novel"
    private val workId: Long,
    private val scope: CoroutineScope,
    /** 顶层评论发表成功后的回调（小说详情页用它更新 total_comments 计数；回复/贴纸回复不计入） */
    private val onCommentPosted: (() -> Unit)? = null,
) {

    private val commentsPager = Pager<CommentResponse, Comment>(client, CommentResponse::class.java)
    private val operationMutex = Mutex()
    private val gson = Gson()
    private val locallyAddedComments = mutableListOf<Comment>()
    /** 首屏是否成功加载过；首次加载失败后发表评论会先重拉首屏，避免列表被截断 */
    private var pagerInitialized = false

    private val _commentsState = MutableStateFlow<UiState<List<Comment>>>(UiState.Loading)
    val commentsState: StateFlow<UiState<List<Comment>>> = _commentsState.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _replyingTo = MutableStateFlow<Comment?>(null)
    val replyingTo: StateFlow<Comment?> = _replyingTo.asStateFlow()

    /** 回复目标所属的顶层评论 id（发给 API 的 parent_comment_id）；回复子评论时是那条子评论所在的主评论 id */
    private var replyParentId: Long? = null

    private val _replies = MutableStateFlow<Map<Long, List<Comment>>>(emptyMap())
    val replies: StateFlow<Map<Long, List<Comment>>> = _replies.asStateFlow()

    private val _expandedReplies = MutableStateFlow<Set<Long>>(emptySet())
    val expandedReplies: StateFlow<Set<Long>> = _expandedReplies.asStateFlow()

    /** 每个主评论的回复分页游标（next_url）；key 不存在=未加载，null=已到最后一页 */
    private val replyNextUrls = mutableMapOf<Long, String?>()

    /** 还有下一页回复未加载的主评论 id 集合（供「加载更多回复」按钮显隐） */
    private val _hasMoreReplies = MutableStateFlow<Set<Long>>(emptySet())
    val hasMoreReplies: StateFlow<Set<Long>> = _hasMoreReplies.asStateFlow()

    /** 正在加载回复下一页的主评论 id（串行化在 operationMutex 上，同一时刻最多一个） */
    private val _loadingMoreReplies = MutableStateFlow<Long?>(null)
    val loadingMoreReplies: StateFlow<Long?> = _loadingMoreReplies.asStateFlow()

    private val _stamps = MutableStateFlow<UiState<List<Stamp>>>(UiState.Loading)
    val stamps: StateFlow<UiState<List<Stamp>>> = _stamps.asStateFlow()

    private val _selfUserId = MutableStateFlow<Long?>(null)
    val selfUserId: StateFlow<Long?> = _selfUserId.asStateFlow()

    init {
        // 后台尽早拉一次当前用户 id，让「删除」按钮能正确显隐
        scope.launch {
            try {
                resolveSelfUserId()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 失败静默，deleteComment 时还会再解析
            }
        }
    }

    fun loadInitial() {
        loadComments()
    }

    fun retry() {
        retrySelfUserId()
        loadComments()
    }

    fun updateDraft(value: String) {
        _draft.value = value
    }

    /** 设置回复目标：[parentCommentId] 是回复所属的顶层评论 id（顶层评论自身回复时为它自己的 id） */
    fun startReply(comment: Comment, parentCommentId: Long) {
        _replyingTo.value = comment
        replyParentId = parentCommentId
    }

    fun cancelReply() {
        _replyingTo.value = null
        replyParentId = null
    }

    fun loadMore() {
        if (!_hasMore.value || _loadingMore.value) return
        scope.launch {
            operationMutex.withLock {
                if (!_hasMore.value || _loadingMore.value) return@withLock
                _loadingMore.value = true
                _error.value = null
                try {
                    commentsPager.loadMore()
                    commentsPager.updateItems { mergeComments(it) }
                    _hasMore.value = commentsPager.hasNext.value
                    _commentsState.value = UiState.Success(commentsPager.items.value)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _error.value = e.message ?: "加载更多评论失败"
                } finally {
                    _loadingMore.value = false
                }
            }
        }
    }

    fun submit() {
        val draftText = _draft.value.trim()
        if (draftText.isEmpty() || _submitting.value) return

        scope.launch {
            operationMutex.withLock {
                if (_commentsState.value is UiState.Loading || _submitting.value) return@withLock
                _submitting.value = true
                _error.value = null
                try {
                    val parentId = replyParentId
                    val response = postComment(draftText, parentId)
                    val comment = response.comment ?: throw IllegalStateException("服务器没有返回评论")
                    applyPostedComment(comment, parentId)
                    if (_draft.value.trim() == draftText) {
                        _draft.value = ""
                    }
                    cancelReply()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _error.value = e.message ?: "发表评论失败"
                } finally {
                    _submitting.value = false
                }
            }
        }
    }

    /** 展开某条主评论的回复（失败静默，保留 has_replies 标志，可再次点击重试） */
    fun loadReplies(commentId: Long) {
        if (commentId <= 0L) return
        scope.launch {
            operationMutex.withLock {
                // 已从服务端加载过回复的线程直接展开：重拉第 1 页会重置分页游标，
                // 翻过页的线程将重复拉取已看过的页（仅去重不丢数据，但浪费请求）。
                // 用 replyNextUrls 判断而不是 _replies：本地刚发表的回复也会预置
                // _replies key，但不能因此挡住服务端回复的首次加载。
                if (replyNextUrls.containsKey(commentId)) {
                    _expandedReplies.value = _expandedReplies.value + commentId
                    return@withLock
                }
                try {
                    val response = client.appApi.getIllustReplyComments(workType, commentId)
                    // 顶层评论已被删除时丢弃过期响应
                    if (commentId !in commentsPager.items.value.asSequence().map { it.id }.toSet()) {
                        return@withLock
                    }
                    mergeRepliesPage(commentId, response)
                    _expandedReplies.value = _expandedReplies.value + commentId
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 静默失败
                }
            }
        }
    }

    /** 加载某条主评论的更多回复（next_url 分页；失败静默，按钮可再次点击重试） */
    fun loadMoreReplies(commentId: Long) {
        if (commentId <= 0L) return
        scope.launch {
            operationMutex.withLock {
                _loadingMoreReplies.value = commentId
                try {
                    val nextUrl = replyNextUrls[commentId] ?: return@withLock
                    val body = client.appApi.generalGet(nextUrl)
                    val response = gson.fromJson(body.string(), CommentResponse::class.java)
                    // 顶层评论已被删除时丢弃过期响应
                    if (commentId in commentsPager.items.value.asSequence().map { it.id }.toSet()) {
                        mergeRepliesPage(commentId, response)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 静默失败
                } finally {
                    _loadingMoreReplies.value = null
                }
            }
        }
    }

    /** 收起某条主评论的回复线程（缓存保留，重新展开不再请求） */
    fun collapseReplies(commentId: Long) {
        if (commentId <= 0L) return
        _expandedReplies.value = _expandedReplies.value - commentId
    }

    fun deleteComment(comment: Comment) {
        if (comment.id <= 0L) return
        scope.launch {
            // 与 loadMore/submit/sendStamp 一样串行化：Pager.updateItems 是非原子读-改-写，
            // 不加锁时并发 loadMore + deleteComment 会互相丢更新（删掉的评论可能复活）
            operationMutex.withLock {
                // 提交进行中不可删除：给明确反馈而不是在锁外静默丢弃（对话框已关闭，用户会以为删掉了）
                if (_commentsState.value is UiState.Loading || _submitting.value) {
                    _error.value = "正在发送评论，请稍后再试"
                    return@withLock
                }
                try {
                    val selfId = resolveSelfUserId()
                    if (selfId <= 0L) {
                        _error.value = "无法确认当前登录用户，删除失败"
                        return@withLock
                    }
                    if (comment.user.id != selfId) return@withLock
                    client.appApi.deleteComment(workType, comment.id)
                    // 本会话刚发布的评论也存在于 locallyAddedComments；不删的话下次
                    // mergeComments（loadMore/submit 触发）会把已删除的评论当本地新评论重新插回列表
                    locallyAddedComments.removeAll { it.id == comment.id }
                    val topLevelIds = commentsPager.items.value.asSequence().map { it.id }.toSet()
                    if (comment.id in topLevelIds) {
                        commentsPager.updateItems { list -> list.filterNot { it.id == comment.id } }
                        _commentsState.value = UiState.Success(commentsPager.items.value)
                    } else {
                        _replies.value = _replies.value.mapValues { (_, list) ->
                            list.filterNot { it.id == comment.id }
                        }
                    }
                    // 清理被删评论的回复线程状态，避免孤儿条目残留
                    _replies.value = _replies.value - comment.id
                    replyNextUrls.remove(comment.id)
                    _hasMoreReplies.value = _hasMoreReplies.value - comment.id
                    _expandedReplies.value = _expandedReplies.value - comment.id
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _error.value = e.message ?: "删除评论失败"
                }
            }
        }
    }

    /** 表情贴纸选中即发：comment 留空 + 只带 stamp_id，插入逻辑同 submit */
    fun sendStamp(stamp: Stamp) {
        if (_submitting.value) return
        scope.launch {
            operationMutex.withLock {
                if (_commentsState.value is UiState.Loading || _submitting.value) return@withLock
                _submitting.value = true
                _error.value = null
                try {
                    val parentId = replyParentId
                    val response = postStamp(stamp.stamp_id, parentId)
                    val comment = response.comment ?: throw IllegalStateException("服务器没有返回评论")
                    applyPostedComment(comment, parentId)
                    cancelReply()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _error.value = e.message ?: "发表评论失败"
                } finally {
                    _submitting.value = false
                }
            }
        }
    }

    /** 表情面板打开时拉贴纸目录；结果进程内缓存（companion object），避免每个页面重复请求 */
    fun loadStamps() {
        if (_stamps.value is UiState.Success) return
        scope.launch {
            try {
                val stamps = fetchStampsFromCache()
                _stamps.value = UiState.Success(stamps)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _stamps.value = UiState.Error(e.message ?: "加载贴纸失败")
            }
        }
    }

    /** 删除按钮显隐依赖 selfUserId；首次解析失败后由 UI 在渲染评论行时触发重试 */
    fun retrySelfUserId() {
        if (_selfUserId.value != null) return
        scope.launch {
            try {
                resolveSelfUserId()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 仍失败则下次渲染评论行时再试
            }
        }
    }

    // ---------- 内部实现 ----------

    private fun loadComments() {
        scope.launch {
            operationMutex.withLock {
                _commentsState.value = UiState.Loading
                _hasMore.value = false
                _error.value = null
                try {
                    val response = when (workType) {
                        ObjectType.ILLUST -> client.appApi.getIllustComments(workId)
                        else -> client.appApi.getNovelComments(workId)
                    }
                    commentsPager.refresh(response)
                    commentsPager.updateItems { mergeComments(it) }
                    _hasMore.value = commentsPager.hasNext.value
                    pagerInitialized = true
                    _commentsState.value = UiState.Success(commentsPager.items.value)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _commentsState.value = UiState.Error(e.message ?: "加载评论失败")
                }
            }
        }
    }

    private suspend fun postComment(
        draftText: String,
        parentId: Long?,
    ) = if (parentId != null && parentId > 0L) {
        when (workType) {
            ObjectType.ILLUST -> client.appApi.postIllustComment(workId, draftText, parentId)
            else -> client.appApi.postNovelComment(workId, draftText, parentId)
        }
    } else {
        when (workType) {
            ObjectType.ILLUST -> client.appApi.postIllustComment(workId, draftText)
            else -> client.appApi.postNovelComment(workId, draftText)
        }
    }

    private suspend fun postStamp(
        stampId: Long,
        parentId: Long?,
    ) = if (parentId != null && parentId > 0L) {
        when (workType) {
            ObjectType.ILLUST -> client.appApi.postIllustComment(workId, "", parentId, stampId)
            else -> client.appApi.postNovelComment(workId, "", parentId, stampId)
        }
    } else {
        when (workType) {
            ObjectType.ILLUST -> client.appApi.postIllustComment(workId, "", null, stampId)
            else -> client.appApi.postNovelComment(workId, "", null, stampId)
        }
    }

    /** 发评论/贴纸成功后的列表编辑：顶层插头部，回复挂进对应主评论的回复线程 */
    private suspend fun applyPostedComment(comment: Comment, parentId: Long?) {
        if (parentId != null && parentId > 0L) {
            val threadLoadedBefore = replyNextUrls.containsKey(parentId)
            _replies.value = _replies.value.toMutableMap().apply {
                put(parentId, (get(parentId) ?: emptyList()) + comment)
            }
            _expandedReplies.value = _expandedReplies.value + parentId
            // 用户可能直接在从未展开过的顶层评论上回复：若线程从未从服务端加载过，
            // 本地预置的 key 会让 loadReplies 误判为已加载，导致服务端回复永远不出现。
            // 等锁释放后补拉一次首屏（loadReplies 内部自带去重，不会丢本地新回复）。
            if (!threadLoadedBefore) {
                loadReplies(parentId)
            }
        } else {
            // 首次加载失败过（Error 态）时先重拉首屏，否则列表会被截断成只有新评论。
            // 补偿重拉失败不能算发表失败：评论已发布成功（POST 已返回），本地插入的评论
            // 仍会显示，仅列表暂时不完整；否则用户看到「发表失败」后重试会产生重复评论。
            if (!pagerInitialized) {
                try {
                    ensurePagerLoaded()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 保持 pagerInitialized=false，用户刷新/重试加载可恢复完整列表
                }
            }
            locallyAddedComments.removeAll { it.id > 0L && it.id == comment.id }
            locallyAddedComments.add(comment)
            commentsPager.updateItems { mergeComments(it) }
            _commentsState.value = UiState.Success(commentsPager.items.value)
            _hasMore.value = commentsPager.hasNext.value
            // total_comments 只统计顶层评论，回复/贴纸回复不触发回调
            onCommentPosted?.invoke()
        }
    }

    /** 首屏从未成功加载时重拉一次第一页（发布成功后的补偿路径） */
    private suspend fun ensurePagerLoaded() {
        if (pagerInitialized) return
        val response = when (workType) {
            ObjectType.ILLUST -> client.appApi.getIllustComments(workId)
            else -> client.appApi.getNovelComments(workId)
        }
        commentsPager.refresh(response)
        commentsPager.updateItems { mergeComments(it) }
        _hasMore.value = commentsPager.hasNext.value
        pagerInitialized = true
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

    /** 合并一页回复进本地列表（按 id 去重），并更新该线程的分页游标 */
    private fun mergeRepliesPage(commentId: Long, response: CommentResponse) {
        val existing = _replies.value[commentId].orEmpty()
        val merged = (existing + response.comments).distinctBy { it.id }
        _replies.value = _replies.value + (commentId to merged)
        replyNextUrls[commentId] = response.next_url
        if (response.next_url != null) {
            _hasMoreReplies.value = _hasMoreReplies.value + commentId
        } else {
            _hasMoreReplies.value = _hasMoreReplies.value - commentId
        }
    }

    private suspend fun resolveSelfUserId(): Long {
        _selfUserId.value?.let { return it }
        // 进程级共享解析：并发调用等同一结果；无效 id（0）不缓存，下次可重试
        val id = SelfUserIdResolver.resolve {
            val self = client.appApi.getSelfProfile()
            self.profile.user_id.takeIf { it > 0 } ?: self.profile.id
        }
        if (id > 0L) _selfUserId.value = id
        return id
    }

    private suspend fun fetchStampsFromCache(): List<Stamp> {
        cachedStamps?.let { return it }
        return stampsMutex.withLock {
            cachedStamps?.let { return@withLock it }
            val response = client.appApi.getStamps()
            if (response.stamps.isNotEmpty()) {
                cachedStamps = response.stamps
            }
            response.stamps
        }
    }

    companion object {
        /** 进程级贴纸目录缓存：多个页面共用一次 getStamps 请求 */
        @Volatile
        private var cachedStamps: List<Stamp>? = null
        private val stampsMutex = Mutex()

        /** 退出登录 / 切换账号时调用，避免缓存串到下一个账号 */
        fun clearCaches() {
            SelfUserIdResolver.clear()
            cachedStamps = null
        }
    }
}
