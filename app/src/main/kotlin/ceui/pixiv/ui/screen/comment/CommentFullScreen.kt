package ceui.pixiv.ui.screen.comment

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.component.CommentComposer
import ceui.pixiv.ui.component.CommentList
import ceui.pixiv.ui.screen.user.UserDetailScreen

/**
 * 评论完整页：整页评论列表（不限高）+ 输入区，无详情页上下文，故没有「查看全部」按钮。
 * [providedController] 为详情页传入的共享 controller；未传入时（独立进入）自建一份。
 */
class CommentFullScreen(
    private val workType: String,
    private val workId: Long,
    private val providedController: CommentsController? = null,
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { CommentFullScreenModel(workType, workId) }
        val controller = providedController ?: screenModel.controller
        val navigator = LocalNavigator.currentOrThrow

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("评论") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                CommentList(
                    controller = controller,
                    onUserClick = { id -> navigator.push(UserDetailScreen(id)) },
                    modifier = Modifier.weight(1f),
                    maxHeight = null,
                )
                CommentComposer(controller = controller)
            }
        }
    }
}

class CommentFullScreenModel(
    private val workType: String,
    private val workId: Long,
) : ScreenModel {

    // 懒初始化：传入 providedController 时从不访问，避免每次「查看全部」重复发加载请求
    val controller: CommentsController by lazy {
        CommentsController(
            client = AppContainer.client,
            workType = workType,
            workId = workId,
            scope = screenModelScope,
        ).also { it.loadInitial() }
    }
}
