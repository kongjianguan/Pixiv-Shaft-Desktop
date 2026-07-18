package ceui.pixiv.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow

class SettingsScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        if (navigator.canPop) {
                            IconButton(onClick = { navigator.pop() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        }
                    },
                    title = { Text("设置") },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("设置", style = MaterialTheme.typography.headlineSmall)
                Text("按功能分类管理应用选项", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SettingsCategory.entries.forEach { category ->
                    SettingsCategoryItem(category) { navigator.push(SettingsCategoryScreen(category)) }
                }
            }
        }
    }
}

private enum class SettingsCategory(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
) {
    NETWORK("网络与连接", "QUIC（快速网络协议）、安全 DNS 和连接方式", Icons.Default.NetworkCheck),
    IMAGES("图片", "图片源与自定义图片代理", Icons.Default.Image),
    FEEDS("信息流布局", "作品流与小说流的列宽、列数和标题显示", Icons.Default.MenuBook),
    ACCOUNT("账号", "退出当前 Pixiv 账号", Icons.Default.Person),
}

@Composable
private fun SettingsCategoryItem(category: SettingsCategory, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(category.icon, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
                Text(category.title, style = MaterialTheme.typography.titleMedium)
                Text(category.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private class SettingsCategoryScreen(private val category: SettingsCategory) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { SettingsScreenModel() }
        val navigator = LocalNavigator.currentOrThrow
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(category.title) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (category) {
                    SettingsCategory.NETWORK -> NetworkSettings(screenModel)
                    SettingsCategory.IMAGES -> ImageSettings(screenModel)
                    SettingsCategory.FEEDS -> FeedLayoutSettings(screenModel)
                    SettingsCategory.ACCOUNT -> AccountSettings(screenModel)
                }
            }
        }
    }
}

@Composable
private fun NetworkSettings(screenModel: SettingsScreenModel) {
    val restartRequired by screenModel.restartRequired.collectAsState()
    if (restartRequired) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "网络设置已改变，重启应用后完全生效",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
    SettingSwitch(
        title = "直连（QUIC）",
        subtitle = "通过 QUIC 和无 SNI TLS（不发送服务器名称）连接 Pixiv",
        checked = screenModel.isDirectConnect,
        onCheckedChange = screenModel::setDirectConnect,
    )
    SettingSwitch(
        title = "安全 DNS（DoH）",
        subtitle = "使用 DNS-over-HTTPS（加密 DNS）解析图片地址",
        checked = screenModel.isUseSecureDns,
        onCheckedChange = screenModel::setUseSecureDns,
    )
}

@Composable
private fun ImageSettings(screenModel: SettingsScreenModel) {
    var customHost by remember { mutableStateOf(screenModel.customImageHost) }
    var currentHostMode by remember { mutableStateOf(screenModel.imageHostMode) }
    Text("图片源", style = MaterialTheme.typography.titleMedium)
    val hostModes = listOf("Pixiv" to 0, "pixiv.cat" to 1, "pixiv.re" to 2, "pixiv.nl" to 3, "自定义" to 4)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(hostModes) { (label, mode) ->
            FilterChip(
                selected = currentHostMode == mode,
                onClick = {
                    currentHostMode = mode
                    screenModel.setImageHostMode(mode)
                },
                label = { Text(label) },
            )
        }
    }
    if (currentHostMode == 4) {
        OutlinedTextField(
            value = customHost,
            onValueChange = {
                customHost = it
                screenModel.setCustomImageHost(it)
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://your.proxy.com") },
            singleLine = true,
        )
    }
}

@Composable
private fun FeedLayoutSettings(screenModel: SettingsScreenModel) {
    Text("作品流", style = MaterialTheme.typography.titleMedium)
    Text("推荐、漫画、最新三页共用这一组参数", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FeedLayoutOptions(
        initialMaxWidth = screenModel.workFeedMaxColumnWidthDp,
        initialMaxColumns = screenModel.workFeedMaxColumns,
        initialMinWidth = screenModel.workFeedMinColumnWidthDp,
        initialTitleLines = screenModel.workTitleMaxLines,
        onMaxWidthChange = screenModel::setWorkFeedMaxColumnWidthDp,
        onMaxColumnsChange = screenModel::setWorkFeedMaxColumns,
        onMinWidthChange = screenModel::setWorkFeedMinColumnWidthDp,
        onTitleLinesChange = screenModel::setWorkTitleMaxLines,
    )

    Text("小说流", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    Text("小说推荐与系列章节页使用这一组独立参数", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FeedLayoutOptions(
        initialMaxWidth = screenModel.novelFeedMaxColumnWidthDp,
        initialMaxColumns = screenModel.novelFeedMaxColumns,
        initialMinWidth = screenModel.novelFeedMinColumnWidthDp,
        initialTitleLines = screenModel.novelTitleMaxLines,
        onMaxWidthChange = screenModel::setNovelFeedMaxColumnWidthDp,
        onMaxColumnsChange = screenModel::setNovelFeedMaxColumns,
        onMinWidthChange = screenModel::setNovelFeedMinColumnWidthDp,
        onTitleLinesChange = screenModel::setNovelTitleMaxLines,
    )
}

@Composable
private fun FeedLayoutOptions(
    initialMaxWidth: Int,
    initialMaxColumns: Int,
    initialMinWidth: Int,
    initialTitleLines: Int,
    onMaxWidthChange: (Int) -> Unit,
    onMaxColumnsChange: (Int) -> Unit,
    onMinWidthChange: (Int) -> Unit,
    onTitleLinesChange: (Int) -> Unit,
) {
    var maxWidth by remember { mutableStateOf(initialMaxWidth) }
    var maxColumns by remember { mutableStateOf(initialMaxColumns) }
    var minWidth by remember { mutableStateOf(initialMinWidth) }
    var titleLines by remember { mutableStateOf(initialTitleLines) }

    SettingChips("单列最大宽度", listOf(280, 320, 360, 420, 480, 560), maxWidth, "dp") {
        maxWidth = it; onMaxWidthChange(it)
    }
    SettingChips("最大列数", (1..6).toList(), maxColumns, "列") {
        maxColumns = it; onMaxColumnsChange(it)
    }
    SettingChips("单列最小宽度", listOf(240, 280, 320, 360, 420), minWidth, "dp") {
        minWidth = it; onMinWidthChange(it)
    }
    SettingChips("标题最大显示行数", (1..4).toList(), titleLines, "行") {
        titleLines = it; onTitleLinesChange(it)
    }
}

@Composable
private fun SettingChips(
    title: String,
    options: List<Int>,
    selected: Int,
    suffix: String,
    onSelect: (Int) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.bodyMedium)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(options) { value ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text("$value $suffix") },
            )
        }
    }
}

@Composable
private fun AccountSettings(screenModel: SettingsScreenModel) {
    Text("账号", style = MaterialTheme.typography.titleMedium)
    Text("退出后需要重新通过 Pixiv OAuth（授权登录）登录。", style = MaterialTheme.typography.bodyMedium)
    Button(
        onClick = screenModel::logout,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
    ) {
        Text("退出登录")
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
