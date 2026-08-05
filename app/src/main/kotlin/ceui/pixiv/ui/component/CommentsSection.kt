package ceui.pixiv.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ceui.loxia.Comment
import ceui.pixiv.ui.state.UiState

/** Shared comment list and composer for artwork and novel detail pages. */
@Composable
fun CommentsSection(
    state: UiState<List<Comment>>,
    draft: String,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    isSubmitting: Boolean,
    errorMessage: String?,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onUserClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "评论",
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)

        when (state) {
            UiState.Loading -> LoadingView(modifier = Modifier.fillMaxWidth().height(120.dp))
            is UiState.Error -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    IconButton(onClick = onRetry) {
                        Icon(Icons.Default.Refresh, contentDescription = "重试加载评论")
                    }
                }
            }
            is UiState.Success -> {
                if (state.data.isEmpty()) {
                    Text(
                        text = "还没有评论",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                    ) {
                        itemsIndexed(
                            items = state.data,
                            key = { index, comment ->
                                comment.id.takeIf { it > 0L } ?: "comment-$index"
                            },
                        ) { index, comment ->
                            CommentRow(comment = comment, onUserClick = onUserClick)
                            if (index != state.data.lastIndex) {
                                HorizontalDivider()
                            }
                        }

                        if (hasMore) {
                            item(key = "load-more-comments") {
                                TextButton(
                                    onClick = onLoadMore,
                                    enabled = !isLoadingMore,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    if (isLoadingMore) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(if (isLoadingMore) "加载中" else "加载更多评论")
                                }
                            }
                        }
                    }
                }

                if (state.data.isEmpty() && hasMore) {
                    TextButton(
                        onClick = onLoadMore,
                        enabled = !isLoadingMore,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        if (isLoadingMore) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (isLoadingMore) "加载中" else "加载更多评论")
                    }
                }
            }
        }

        errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
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
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("写下评论") },
                singleLine = false,
                maxLines = 4,
                enabled = !isSubmitting,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { if (state !is UiState.Loading) onSubmit() },
                ),
                trailingIcon = {
                    IconButton(
                        onClick = onSubmit,
                        enabled = draft.isNotBlank() &&
                            !isSubmitting &&
                            state !is UiState.Loading,
                    ) {
                        if (isSubmitting) {
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
private fun CommentRow(
    comment: Comment,
    onUserClick: (Long) -> Unit,
) {
    val user = comment.user
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (user.id > 0L) {
                    Modifier.clickable { onUserClick(user.id) }
                } else {
                    Modifier
                },
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        UserAvatar(
            url = user.profile_image_urls?.px_50x50 ?: user.profile_image_urls?.medium,
            size = 32,
        )
        Column(
            modifier = Modifier.weight(1f).padding(start = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = user.name?.takeIf { it.isNotBlank() } ?: "匿名用户",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = comment.displayCommentDate(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = comment.comment.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
