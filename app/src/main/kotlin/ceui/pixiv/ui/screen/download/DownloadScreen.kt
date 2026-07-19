package ceui.pixiv.ui.screen.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.pixiv.di.AppContainer
import ceui.pixiv.download.DownloadStatus
import ceui.pixiv.download.DownloadTask
import java.awt.Desktop
import java.nio.file.Path

class DownloadScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val tasks by AppContainer.downloadManager.tasks.collectAsState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("下载管理") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        TextButton(onClick = AppContainer.downloadManager::clearCompleted) {
                            Text("清除已完成")
                        }
                    },
                )
            },
        ) { padding ->
            if (tasks.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("还没有下载任务", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "在作品详情页的更多菜单中选择下载",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(tasks, key = { it.id }) { task ->
                        DownloadTaskCard(task)
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadTaskCard(task: DownloadTask) {
    val manager = AppContainer.downloadManager
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
            Text(
                text = "${task.authorName} · ${task.displayPage}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (task.kind == ceui.pixiv.download.DownloadTaskKind.UGOIRA) {
                Text(
                    text = "输出：GIF 动图",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))

            if (task.progress != null) {
                val progress = task.progress!!
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(5.dp)),
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "${formatBytes(task.bytesDownloaded)} / ${formatBytes(task.totalBytes)} · ${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (task.status == DownloadStatus.DOWNLOADING) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(5.dp)),
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "正在下载 · ${formatBytes(task.bytesDownloaded)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = statusText(task),
                style = MaterialTheme.typography.labelMedium,
                color = statusColor(task),
                modifier = Modifier.padding(top = 6.dp),
            )
            task.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (task.status) {
                    DownloadStatus.QUEUED,
                    DownloadStatus.DOWNLOADING -> TextButton(onClick = { manager.pause(task.id) }) {
                        Text("暂停")
                    }
                    DownloadStatus.PAUSED -> TextButton(onClick = { manager.resume(task.id) }) {
                        Text("继续")
                    }
                    DownloadStatus.FAILED,
                    DownloadStatus.CANCELED -> TextButton(onClick = { manager.retry(task.id) }) {
                        Text("重试")
                    }
                    DownloadStatus.COMPLETED -> TextButton(onClick = { revealInFinder(task) }) {
                        Text("在 Finder 中显示")
                    }
                }
                TextButton(onClick = { manager.delete(task.id) }) { Text("删除") }
            }
        }
    }
}

private fun statusText(task: DownloadTask): String = when (task.status) {
    DownloadStatus.QUEUED -> "等待中"
    DownloadStatus.DOWNLOADING -> "下载中"
    DownloadStatus.PAUSED -> "已暂停"
    DownloadStatus.COMPLETED -> "已完成"
    DownloadStatus.FAILED -> "失败"
    DownloadStatus.CANCELED -> "已取消"
}

@Composable
private fun statusColor(task: DownloadTask) = when (task.status) {
    DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
    DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (index == 0) "${bytes} ${units[index]}" else "%.1f %s".format(value, units[index])
}

private fun revealInFinder(task: DownloadTask) {
    val file = Path.of(task.outputPath).toFile()
    val parent = file.parentFile ?: return
    if (Desktop.isDesktopSupported()) {
        runCatching { Desktop.getDesktop().open(parent) }
    }
}
