package ceui.pixiv.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ceui.loxia.Comment
import ceui.loxia.Stamp
import ceui.pixiv.ui.screen.comment.CommentsController
import ceui.pixiv.ui.state.UiState
import coil3.compose.AsyncImage

// pixiv 官方 38 个 emoji code（数据源：原 Shaft 参考仓库 ceui/lisa/utils/Emoji.java 的 getEmojis()），
// 形如 (normal)/(heart)，发表后 pixiv 端渲染成表情图片，比纯文字颜文字更贴近官方体验。
private val PIXIV_EMOJI_CODES = listOf(
    "(normal)", "(surprise)", "(serious)", "(heaven)", "(happy)", "(excited)",
    "(sing)", "(cry)", "(normal2)", "(shame2)", "(love2)", "(interesting2)",
    "(blush2)", "(fire2)", "(angry2)", "(shine2)", "(panic2)", "(normal3)",
    "(satisfaction3)", "(surprise3)", "(smile3)", "(shock3)", "(gaze3)", "(wink3)",
    "(happy3)", "(excited3)", "(love3)", "(normal4)", "(surprise4)", "(serious4)",
    "(love4)", "(shine4)", "(sweat4)", "(shame4)", "(sleep4)", "(heart)",
    "(teardrop)", "(star)",
)

/** 评论区块：标题 + 评论列表 + 输入区，插画详情页与小说详情页共用。 */
@Composable
fun CommentsSection(
    controller: CommentsController,
    onUserClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "评论",
    onOpenFullScreen: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (onOpenFullScreen != null) {
                TextButton(onClick = onOpenFullScreen) { Text("查看全部") }
            }
        }
        CommentList(
            controller = controller,
            onUserClick = onUserClick,
            maxHeight = 420.dp,
        )
        CommentComposer(controller = controller)
    }
}

/** 评论列表：分页加载 + 回复展开 + 删除确认，详情页（限高）与全屏页（不限高）共用。 */
@Composable
fun CommentList(
    controller: CommentsController,
    onUserClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp? = null,
) {
    val commentsState by controller.commentsState.collectAsState()
    val hasMore by controller.hasMore.collectAsState()
    val loadingMore by controller.loadingMore.collectAsState()
    val replies by controller.replies.collectAsState()
    val expandedReplies by controller.expandedReplies.collectAsState()
    val hasMoreReplies by controller.hasMoreReplies.collectAsState()
    val loadingMoreReplies by controller.loadingMoreReplies.collectAsState()
    val selfUserId by controller.selfUserId.collectAsState()
    var pendingDelete by remember { mutableStateOf<Comment?>(null) }

    // selfUserId 首次解析失败后（删除按钮会消失），列表渲染期间定时补重试。
    // LaunchedEffect 以值作 key，selfUserId 仍为 null 时不会自动重跑，需在此循环；
    // 有界重试后仍失败则放弃（页面重进会重新开始）。
    LaunchedEffect(selfUserId) {
        if (selfUserId == null) {
            repeat(SELF_ID_RETRY_COUNT) {
                delay(SELF_ID_RETRY_INTERVAL_MS)
                controller.retrySelfUserId()
                if (controller.selfUserId.value != null) return@LaunchedEffect
            }
        }
    }

    when (val cs = commentsState) {
        UiState.Loading -> LoadingView(modifier = Modifier.fillMaxWidth().height(120.dp))
        is UiState.Error -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = cs.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                IconButton(onClick = controller::retry) {
                    Icon(Icons.Default.Refresh, contentDescription = "重试加载评论")
                }
            }
        }
        is UiState.Success -> {
            if (cs.data.isEmpty()) {
                Text(
                    text = "还没有评论",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = if (maxHeight != null) {
                        modifier.fillMaxWidth().heightIn(max = maxHeight)
                    } else {
                        modifier.fillMaxWidth()
                    },
                ) {
                    itemsIndexed(
                        items = cs.data,
                        key = { index, comment ->
                            comment.id.takeIf { it > 0L } ?: "comment-$index"
                        },
                    ) { index, comment ->
                        Column {
                            CommentRow(
                                comment = comment,
                                isExpanded = comment.id in expandedReplies,
                                childComments = replies[comment.id].orEmpty(),
                                hasMoreReplies = comment.id in hasMoreReplies,
                                loadingMoreReplies = loadingMoreReplies == comment.id,
                                selfUserId = selfUserId,
                                onUserClick = onUserClick,
                                onExpand = { controller.loadReplies(comment.id) },
                                onCollapse = { controller.collapseReplies(comment.id) },
                                onLoadMoreReplies = { controller.loadMoreReplies(comment.id) },
                                onReply = { controller.startReply(comment, comment.id) },
                                onReplyChild = { child -> controller.startReply(child, comment.id) },
                                onRequestDelete = { pendingDelete = it },
                            )
                            if (index != cs.data.lastIndex) {
                                HorizontalDivider()
                            }
                        }
                    }

                    if (hasMore) {
                        item(key = "load-more-comments") {
                            LoadMoreButton(
                                loading = loadingMore,
                                onClick = controller::loadMore,
                                modifier = Modifier.fillMaxWidth(),
                                text = "加载更多评论",
                            )
                        }
                    }
                }
            }

            if (cs.data.isEmpty() && hasMore) {
                LoadMoreButton(
                    loading = loadingMore,
                    onClick = controller::loadMore,
                    modifier = Modifier.fillMaxWidth(),
                    text = "加载更多评论",
                )
            }
        }
    }

    // 删除二次确认
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除评论") },
            text = { Text("确定要删除这条评论吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.deleteComment(target)
                        pendingDelete = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

/** 输入区 + 回复横幅 + 表情面板，详情页与全屏页共用。 */
@Composable
fun CommentComposer(
    controller: CommentsController,
    modifier: Modifier = Modifier,
) {
    val draft by controller.draft.collectAsState()
    val submitting by controller.submitting.collectAsState()
    val replyTarget by controller.replyingTo.collectAsState()
    val error by controller.error.collectAsState()
    val commentsState by controller.commentsState.collectAsState()
    val replyName = replyTarget?.user?.name.orEmpty()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 回复 @name 横幅
        replyTarget?.let { target ->
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "回复 @${target.user.name.orEmpty()}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = controller::cancelReply,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "取消回复",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        error?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = controller::updateDraft,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(if (replyTarget != null) "回复 $replyName" else "写下评论")
                },
                singleLine = false,
                maxLines = 4,
                enabled = !submitting,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { if (commentsState !is UiState.Loading) controller.submit() },
                ),
                leadingIcon = {
                    EmojiPickerButton(
                        controller = controller,
                        enabled = commentsState !is UiState.Loading,
                    )
                },
                trailingIcon = {
                    IconButton(
                        onClick = controller::submit,
                        enabled = draft.isNotBlank() &&
                            !submitting &&
                            commentsState !is UiState.Loading,
                    ) {
                        if (submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发表评论")
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun EmojiPickerButton(controller: CommentsController, enabled: Boolean) {
    var panelOpen by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val draft by controller.draft.collectAsState()

    Box {
        IconButton(
            onClick = {
                selectedTab = 0
                panelOpen = true
            },
            enabled = enabled,
        ) {
            Icon(Icons.Default.EmojiEmotions, contentDescription = "表情")
        }
        DropdownMenu(
            expanded = panelOpen,
            onDismissRequest = { panelOpen = false },
        ) {
            Column(
                modifier = Modifier
                    .width(320.dp)
                    .heightIn(max = 280.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("颜文字") },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = {
                            selectedTab = 1
                            controller.loadStamps()
                        },
                        text = { Text("贴纸") },
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (selectedTab == 0) {
                    KaomojiGrid(onPick = { code -> controller.updateDraft(draft + code) })
                } else {
                    StampGrid(controller = controller)
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun KaomojiGrid(onPick: (String) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PIXIV_EMOJI_CODES.forEach { code ->
            Surface(
                onClick = { onPick(code) },
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StampGrid(controller: CommentsController) {
    val stampsState by controller.stamps.collectAsState()
    val submitting by controller.submitting.collectAsState()
    val commentsState by controller.commentsState.collectAsState()

    when (val s = stampsState) {
        UiState.Loading -> Box(
            modifier = Modifier.fillMaxWidth().height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
        is UiState.Error -> Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = s.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = controller::loadStamps) { Text("重试") }
        }
        is UiState.Success -> {
            if (s.data.isEmpty()) {
                Text(
                    text = "暂无贴纸",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    s.data.forEach { stamp ->
                        StampCell(
                            stamp = stamp,
                            enabled = !submitting && commentsState !is UiState.Loading,
                            onSend = { controller.sendStamp(stamp) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StampCell(stamp: Stamp, enabled: Boolean, onSend: () -> Unit) {
    Surface(
        onClick = onSend,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        AsyncImage(
            model = stamp.stamp_url,
            contentDescription = "发送贴纸",
            modifier = Modifier.size(48.dp).padding(4.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun CommentRow(
    comment: Comment,
    isExpanded: Boolean,
    childComments: List<Comment>,
    hasMoreReplies: Boolean,
    loadingMoreReplies: Boolean,
    selfUserId: Long?,
    onUserClick: (Long) -> Unit,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onLoadMoreReplies: () -> Unit,
    onReply: () -> Unit,
    onReplyChild: (Comment) -> Unit,
    onRequestDelete: (Comment) -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CommentAuthorContent(
            comment = comment,
            avatarSize = 32,
            contentStartPadding = 10.dp,
            contentSpacing = 3.dp,
            nameStyle = MaterialTheme.typography.labelLarge,
            onUserClick = onUserClick,
        ) {
            // 操作行：展开回复常显；回复/删除桌面端 hover 显示
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                    // 显隐由本地展开态 + 实际回复数据驱动：服务端 has_replies 可能滞后
                    // （本地刚发表第一条回复时仍是 false），只信服务端会导致线程无法收起
                    if ((comment.has_replies || childComments.isNotEmpty()) && !isExpanded) {
                        TextButton(
                            onClick = onExpand,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        ) { Text("展开回复") }
                    } else if ((comment.has_replies || childComments.isNotEmpty()) && isExpanded) {
                        TextButton(
                            onClick = onCollapse,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        ) { Text("收起回复") }
                    }
                    Spacer(Modifier.weight(1f))
                    if (hovered) {
                        TextButton(
                            onClick = onReply,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        ) { Text("回复") }
                        if (comment.user.id == selfUserId) {
                            TextButton(
                                onClick = { onRequestDelete(comment) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            ) { Text("删除") }
                        }
                    }
            }
        }

        // 已展开的回复线程：缩进 + 底色区分
        if (isExpanded && childComments.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                childComments.forEach { child ->
                    ChildCommentRow(
                        comment = child,
                        selfUserId = selfUserId,
                        onUserClick = onUserClick,
                        onReply = { onReplyChild(child) },
                        onDelete = { onRequestDelete(child) },
                    )
                }
                if (hasMoreReplies) {
                    LoadMoreButton(
                        loading = loadingMoreReplies,
                        onClick = onLoadMoreReplies,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        indicatorSize = 14.dp,
                        spacerWidth = 6.dp,
                        text = "加载更多回复",
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadMoreButton(
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues? = null,
    indicatorSize: Dp = 16.dp,
    spacerWidth: Dp = 8.dp,
    text: String,
) {
    val content: @Composable () -> Unit = {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(indicatorSize),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(spacerWidth))
        }
        Text(if (loading) "加载中" else text)
    }
    if (contentPadding != null) {
        TextButton(
            onClick = onClick,
            enabled = !loading,
            modifier = modifier,
            contentPadding = contentPadding,
            content = { content() },
        )
    } else {
        TextButton(
            onClick = onClick,
            enabled = !loading,
            modifier = modifier,
            content = { content() },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun ChildCommentRow(
    comment: Comment,
    selfUserId: Long?,
    onUserClick: (Long) -> Unit,
    onReply: () -> Unit,
    onDelete: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }

    CommentAuthorContent(
        modifier = Modifier
            .fillMaxWidth()
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(vertical = 4.dp),
        comment = comment,
        avatarSize = 24,
        contentStartPadding = 8.dp,
        contentSpacing = 2.dp,
        nameStyle = MaterialTheme.typography.labelSmall,
        onUserClick = onUserClick,
    ) {
        if (hovered) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onReply,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                ) { Text("回复") }
                if (comment.user.id == selfUserId) {
                    TextButton(
                        onClick = onDelete,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) { Text("删除") }
                }
            }
        }
    }
}

@Composable
private fun CommentAuthorContent(
    modifier: Modifier = Modifier,
    comment: Comment,
    avatarSize: Int,
    contentStartPadding: Dp,
    contentSpacing: Dp,
    nameStyle: TextStyle,
    onUserClick: (Long) -> Unit,
    actions: @Composable () -> Unit,
) {
    val user = comment.user

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
    ) {
        UserAvatar(
            url = user.profile_image_urls?.px_50x50 ?: user.profile_image_urls?.medium,
            size = avatarSize,
        )
        Column(
            modifier = Modifier.weight(1f).padding(start = contentStartPadding),
            verticalArrangement = Arrangement.spacedBy(contentSpacing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = user.name?.takeIf { it.isNotBlank() } ?: "匿名用户",
                    style = nameStyle,
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (user.id > 0L) {
                                Modifier.clickable { onUserClick(user.id) }
                            } else {
                                Modifier
                            },
                        ),
                )
                Text(
                    text = comment.displayCommentDate(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CommentContent(comment = comment)
            actions()
        }
    }
}

/** 评论正文：贴纸评论显示图片（约 48dp），普通评论显示文本。 */
@Composable
private fun CommentContent(comment: Comment) {
    val stamp = comment.stamp
    if (stamp != null) {
        AsyncImage(
            model = stamp.stamp_url,
            contentDescription = "评论贴纸",
            modifier = Modifier.size(48.dp),
        )
    } else {
        Text(
            text = comment.comment.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** selfUserId 解析失败后的自动重试参数（有界，页面重进会重新开始） */
private const val SELF_ID_RETRY_COUNT = 5
private const val SELF_ID_RETRY_INTERVAL_MS = 3_000L
