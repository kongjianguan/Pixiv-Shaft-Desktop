package ceui.pixiv.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.rememberCoroutineScope
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.pixiv.ui.component.EmptyView
import ceui.pixiv.ui.component.IllustCard
import ceui.pixiv.ui.component.NovelCard
import ceui.pixiv.ui.component.UserAvatar
import ceui.pixiv.ui.screen.detail.IllustDetailScreen
import ceui.pixiv.ui.screen.novel.NovelDetailScreen
import ceui.pixiv.ui.screen.user.UserDetailScreen
import kotlinx.coroutines.flow.collectLatest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class BrowseHistoryScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { BrowseHistoryScreenModel() }
        val tab by screenModel.tab.collectAsState()
        val query by screenModel.query.collectAsState()
        val items by screenModel.items.collectAsState()
        val selectedKeys by screenModel.selectedKeys.collectAsState()
        val selectionMode by screenModel.selectionMode.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        var showClearDialog by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf<String?>(null) }

        val gridState = rememberLazyStaggeredGridState()
        val listState = rememberLazyListState()
        val itemCount = items.size
        LaunchedEffect(tab, query, itemCount) {
            snapshotFlow {
                when (tab) {
                    BrowseHistoryTab.ILLUST -> gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    else -> listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                }
            }.collectLatest { lastIndex ->
                if (lastIndex >= itemCount - 5 && itemCount > 0) screenModel.loadMore()
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    title = {
                        Text(if (selectionMode) "已选 ${selectedKeys.size} 项" else "浏览记录")
                    },
                    actions = {
                        if (selectionMode) {
                            IconButton(onClick = screenModel::toggleSelectAll) {
                                Icon(Icons.Default.SelectAll, contentDescription = "全选")
                            }
                            IconButton(
                                onClick = screenModel::deleteSelected,
                                enabled = selectedKeys.isNotEmpty(),
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "删除选中")
                            }
                            TextButton(onClick = screenModel::exitSelectionMode) { Text("完成") }
                        } else {
                            IconButton(onClick = screenModel::enterSelectionMode) {
                                Icon(Icons.Default.Checklist, contentDescription = "多选")
                            }
                            IconButton(onClick = { showClearDialog = true }) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = "清空")
                            }
                            IconButton(onClick = { notice = screenModel.exportToFile() }) {
                                Icon(Icons.Default.FileDownload, contentDescription = "导出")
                            }
                            IconButton(onClick = { notice = screenModel.importFromFile() }) {
                                Icon(Icons.Default.FileUpload, contentDescription = "导入")
                            }
                        }
                    },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                TabRow(selectedTabIndex = tab.ordinal) {
                    BrowseHistoryTab.entries.forEach { itemTab ->
                        Tab(
                            selected = tab == itemTab,
                            onClick = { screenModel.selectTab(itemTab) },
                            text = { Text(itemTab.title) },
                        )
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = screenModel::updateQuery,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    singleLine = true,
                    placeholder = { Text("搜索标题、作者或标签") },
                )
                notice?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
                if (isLoading && items.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (items.isEmpty()) {
                    EmptyView(if (query.isBlank()) "暂无浏览记录" else "没有匹配的浏览记录")
                } else {
                    when (tab) {
                        BrowseHistoryTab.ILLUST -> IllustHistoryList(
                            items = items,
                            selectedKeys = selectedKeys,
                            selectionMode = selectionMode,
                            state = gridState,
                            onClick = { item ->
                                if (selectionMode) screenModel.toggleSelection(item.key)
                                else item.illust?.let { navigator.push(IllustDetailScreen(it.id)) }
                            },
                            onDelete = screenModel::deleteItem,
                        )
                        BrowseHistoryTab.NOVEL -> NovelHistoryList(
                            items = items,
                            selectedKeys = selectedKeys,
                            selectionMode = selectionMode,
                            state = listState,
                            onClick = { item ->
                                if (selectionMode) screenModel.toggleSelection(item.key)
                                else item.novel?.let { navigator.push(NovelDetailScreen(it.id)) }
                            },
                            onDelete = screenModel::deleteItem,
                        )
                        BrowseHistoryTab.USER -> UserHistoryList(
                            items = items,
                            selectedKeys = selectedKeys,
                            selectionMode = selectionMode,
                            state = listState,
                            onClick = { item ->
                                if (selectionMode) screenModel.toggleSelection(item.key)
                                else item.user?.let { navigator.push(UserDetailScreen(it.id)) }
                            },
                            onDelete = screenModel::deleteItem,
                        )
                    }
                }
            }
        }

        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                title = { Text("清空浏览记录") },
                text = { Text("将清空插画 / 漫画、小说、用户三个标签下的全部本地浏览记录。") },
                confirmButton = {
                    TextButton(onClick = {
                        showClearDialog = false
                        screenModel.clearAll()
                        notice = "已清空浏览记录"
                    }) { Text("清空") }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) { Text("取消") }
                },
            )
        }
    }
}

@Composable
private fun IllustHistoryList(
    items: List<BrowseHistoryDisplay>,
    selectedKeys: Set<String>,
    selectionMode: Boolean,
    state: androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState,
    onClick: (BrowseHistoryDisplay) -> Unit,
    onDelete: (BrowseHistoryDisplay) -> Unit,
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(220.dp),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalItemSpacing = 8.dp,
    ) {
        items(items, key = { it.key }) { item ->
            val illust = item.illust ?: return@items
            Box {
                IllustCard(illust = illust, onClick = { onClick(item) })
                HistoryOverlay(
                    selected = item.key in selectedKeys,
                    selectionMode = selectionMode,
                    onToggle = { onClick(item) },
                    onDelete = { onDelete(item) },
                )
            }
        }
    }
}

@Composable
private fun NovelHistoryList(
    items: List<BrowseHistoryDisplay>,
    selectedKeys: Set<String>,
    selectionMode: Boolean,
    state: androidx.compose.foundation.lazy.LazyListState,
    onClick: (BrowseHistoryDisplay) -> Unit,
    onDelete: (BrowseHistoryDisplay) -> Unit,
) {
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.key }) { item ->
            val novel = item.novel ?: return@items
            Box {
                NovelCard(
                    novel = novel,
                    onClick = { onClick(item) },
                    onUserClick = {},
                    onToggleBookmark = {},
                    modifier = Modifier.fillMaxWidth(),
                )
                HistoryOverlay(
                    selected = item.key in selectedKeys,
                    selectionMode = selectionMode,
                    onToggle = { onClick(item) },
                    onDelete = { onDelete(item) },
                )
            }
        }
    }
}

@Composable
private fun UserHistoryList(
    items: List<BrowseHistoryDisplay>,
    selectedKeys: Set<String>,
    selectionMode: Boolean,
    state: androidx.compose.foundation.lazy.LazyListState,
    onClick: (BrowseHistoryDisplay) -> Unit,
    onDelete: (BrowseHistoryDisplay) -> Unit,
) {
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.key }) { item ->
            val user = item.user ?: return@items
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UserAvatar(
                        url = user.profile_image_urls?.medium ?: user.profile_image_urls?.px_50x50,
                        size = 48,
                    )
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(user.name ?: "未知用户", style = MaterialTheme.typography.titleMedium)
                        Text("@${user.account ?: user.id}", style = MaterialTheme.typography.labelSmall)
                        Text(formatViewedAt(item.item.viewedAt), style = MaterialTheme.typography.labelSmall)
                    }
                    if (selectionMode) {
                        Checkbox(
                            checked = item.key in selectedKeys,
                            onCheckedChange = { onClick(item) },
                        )
                    } else {
                        IconButton(onClick = { onDelete(item) }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除")
                        }
                    }
                }
                if (!selectionMode) {
                    HorizontalDivider()
                    TextButton(onClick = { onClick(item) }, modifier = Modifier.fillMaxWidth()) {
                        Text("打开用户主页")
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryOverlay(
    selected: Boolean,
    selectionMode: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        if (selectionMode) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            ) {
                Checkbox(checked = selected, onCheckedChange = { onToggle() })
            }
        } else {
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            ) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除")
                }
            }
        }
    }
}

private val historyTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatViewedAt(timestamp: Long): String = historyTimeFormatter.format(
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
)
