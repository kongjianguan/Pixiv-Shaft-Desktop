package ceui.pixiv.ui.screen.user

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.loxia.User
import ceui.loxia.UserPreview
import ceui.loxia.UserPreviewResponse
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.component.EmptyView
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.component.UserAvatar
import ceui.pixiv.ui.state.Pager
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.ui.util.SelfUserIdResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/** 用户列表模式：关注中 / 粉丝 / 好P友。 */
enum class UserListMode { FOLLOWING, FOLLOWER, MYPIXIV }

/** 通用用户列表页：关注/粉丝/好P友共用，按 [mode] 拉取对应接口。 */
class UserListScreen(
    private val title: String,
    private val mode: UserListMode,
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { UserListScreenModel(mode) }
        val navigator = LocalNavigator.currentOrThrow
        val state by screenModel.state.collectAsState()
        val isRefreshing by screenModel.isRefreshing.collectAsState()
        val listState = rememberLazyListState()

        val shouldLoadMore by remember(state) {
            derivedStateOf {
                val s = state as? UiState.Success ?: return@derivedStateOf false
                val users = s.data.mapNotNull { it.user }.filter { it.id > 0 }
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= users.size - 5 && users.isNotEmpty()
            }
        }
        LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) screenModel.loadMore() }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            }
        ) { padding ->
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { screenModel.refresh() },
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                when (val s = state) {
                    is UiState.Loading -> LoadingView()
                    is UiState.Error -> ErrorView(s.message, { screenModel.refresh() })
                    is UiState.Success -> {
                        val users = s.data.mapNotNull { it.user }.filter { it.id > 0 }
                        if (users.isEmpty()) {
                            EmptyView("No users")
                        } else {
                            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                items(users, key = { it.id }) { user ->
                                    UserListRow(user = user, onClick = { navigator.push(UserDetailScreen(user.id)) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserListRow(user: User, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UserAvatar(
            url = user.profile_image_urls?.px_50x50 ?: user.profile_image_urls?.medium,
            size = 48,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = user.name ?: "Unknown", style = MaterialTheme.typography.titleSmall)
            val comment = user.comment
            if (!comment.isNullOrEmpty()) {
                Text(
                    text = comment,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 用户列表：先取当前用户 id，再按 [mode] 请求对应列表，Pager 分页 + 三段式状态。 */
class UserListScreenModel(private val mode: UserListMode) : ScreenModel {

    private val client = AppContainer.client
    private val pager = Pager<UserPreviewResponse, UserPreview>(client, UserPreviewResponse::class.java)

    private val _state = MutableStateFlow<UiState<List<UserPreview>>>(UiState.Loading)
    val state: StateFlow<UiState<List<UserPreview>>> = _state.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val loadingMore = AtomicBoolean(false)

    init {
        screenModelScope.launch { fetchInitial() }
    }

    private suspend fun fetchInitial() {
        try {
            fetchCurrent()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = UiState.Error(e.message ?: "Failed to load users")
        }
    }

    fun refresh() {
        screenModelScope.launch {
            _isRefreshing.value = true
            try {
                fetchCurrent()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 刷新失败时保留已有数据
                if (_state.value !is UiState.Success) {
                    _state.value = UiState.Error(e.message ?: "Failed to load users")
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun loadMore() {
        if (!pager.hasNext.value || !loadingMore.compareAndSet(false, true)) return
        screenModelScope.launch {
            try {
                pager.loadMore()
                _state.value = UiState.Success(pager.items.value)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 加载失败保留已有数据
            } finally {
                loadingMore.set(false)
            }
        }
    }

    /** 当前登录用户 id：进程级共享解析（详情页评论等页面共用一次 getSelfProfile 请求） */
    private suspend fun resolveSelfUserId(): Long =
        SelfUserIdResolver.resolve {
            val self = client.appApi.getSelfProfile()
            self.profile.user_id.takeIf { it > 0 } ?: self.profile.id
        }

    private suspend fun fetchCurrent() {
        val userId = resolveSelfUserId()
        val resp = when (mode) {
            UserListMode.FOLLOWING -> client.appApi.getFollowingUsers(userId, "public")
            UserListMode.FOLLOWER -> client.appApi.getUserFans(userId)
            UserListMode.MYPIXIV -> client.appApi.getUserPixivFriends(userId)
        }
        pager.refresh(resp)
        _state.value = UiState.Success(pager.items.value)
    }
}
