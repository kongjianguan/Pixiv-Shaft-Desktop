package ceui.pixiv.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Palette
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
import ceui.pixiv.download.DownloadTemplate
import ceui.pixiv.download.DownloadTemplateValues
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.UserAvatar
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.ui.theme.ShaftThemeMode
import ceui.pixiv.ui.theme.ShaftThemePreset
import javax.swing.JFileChooser

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
    HISTORY("历史记录", "控制本地浏览历史的保存方式", Icons.Default.History),
    DOWNLOAD("下载", "下载路径与文件名模板", Icons.Default.FileDownload),
    APPEARANCE("外观", "主题色与浅色、深色模式", Icons.Default.Palette),
    ACCOUNT("账号", "查看账号资料、R18 显示和退出登录", Icons.Default.Person),
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
                    SettingsCategory.HISTORY -> HistorySettings(screenModel)
                    SettingsCategory.DOWNLOAD -> DownloadSettings(screenModel)
                    SettingsCategory.APPEARANCE -> AppearanceSettings(screenModel)
                    SettingsCategory.ACCOUNT -> {
                        val accountScreenModel = rememberScreenModel { AccountSettingsScreenModel() }
                        AccountSettings(accountScreenModel, screenModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistorySettings(screenModel: SettingsScreenModel) {
    val saveBrowseHistory by screenModel.saveBrowseHistoryFlow.collectAsState()
    Text("浏览历史", style = MaterialTheme.typography.titleMedium)
    Text(
        "关闭后不会再记录新的插画、小说和用户浏览记录，已有记录不会被删除。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SettingSwitch(
        title = "保存浏览历史",
        subtitle = "只保存在本机，不上传云端",
        checked = saveBrowseHistory,
        onCheckedChange = screenModel::setSaveBrowseHistory,
    )
}

@Composable
private fun AppearanceSettings(screenModel: SettingsScreenModel) {
    val themeColorIndex by screenModel.themeColorIndexFlow.collectAsState()
    val themeModeValue by screenModel.themeModeFlow.collectAsState()
    val themeMode = ShaftThemeMode.fromStorage(themeModeValue)

    Text("主题色彩", style = MaterialTheme.typography.titleMedium)
    Text(
        "选择原版 Shaft 的主题色，修改后立即生效",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ShaftThemePreset.entries.toList()) { preset ->
            FilterChip(
                selected = themeColorIndex == preset.index,
                onClick = { screenModel.setThemeColorIndex(preset.index) },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(preset.lightPrimary, CircleShape),
                    )
                },
                label = { Text(preset.label) },
            )
        }
    }

    Text("主题模式", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(ShaftThemeMode.entries.toList()) { mode ->
            FilterChip(
                selected = themeMode == mode,
                onClick = { screenModel.setThemeMode(mode.storageValue) },
                label = { Text(mode.label) },
            )
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
private fun DownloadSettings(screenModel: SettingsScreenModel) {
    val downloadRootPath by screenModel.downloadRootPathFlow.collectAsState()
    val illustTemplate by screenModel.illustFileNameTemplateFlow.collectAsState()
    val ugoiraTemplate by screenModel.ugoiraFileNameTemplateFlow.collectAsState()
    val novelTemplate by screenModel.novelFileNameTemplateFlow.collectAsState()

    Text("下载路径", style = MaterialTheme.typography.titleMedium)
    Text(
        "所有下载文件默认保存在该目录下，模板中的路径相对此目录",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = downloadRootPath,
            onValueChange = screenModel::setDownloadRootPath,
            modifier = Modifier.weight(1f),
            singleLine = true,
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            chooseDownloadDirectory(downloadRootPath, screenModel::setDownloadRootPath)
        }) {
            Text("选择…")
        }
    }

    TemplateSetting(
        title = "插画文件名模板",
        template = illustTemplate,
        onTemplateChange = screenModel::setIllustFileNameTemplate,
        preview = renderTemplatePreview(illustTemplate, page = " p2", ext = ".jpg"),
    )
    TemplateSetting(
        title = "动图文件名模板",
        template = ugoiraTemplate,
        onTemplateChange = screenModel::setUgoiraFileNameTemplate,
        preview = renderTemplatePreview(ugoiraTemplate, page = "", ext = ".gif"),
    )
    TemplateSetting(
        title = "小说文件名模板",
        template = novelTemplate,
        onTemplateChange = screenModel::setNovelFileNameTemplate,
        preview = renderTemplatePreview(novelTemplate, page = "", ext = ".txt"),
    )
}

@Composable
private fun TemplateSetting(
    title: String,
    template: String,
    onTemplateChange: (String) -> Unit,
    preview: String,
) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    Text(
        "相对下载根目录，不要以 / 开头",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = template,
        onValueChange = onTemplateChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    Text(
        "可用变量：{title} {id} {author} {author_id} {page} {ext} {series} {series_order} {chapters}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "预览：$preview",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** 用示例值渲染模板，供设置页实时预览文件名 */
private fun renderTemplatePreview(template: String, page: String, ext: String): String {
    val values = DownloadTemplateValues(
        title = "示例标题",
        id = 12345678L,
        author = "示例作者",
        authorId = 1000L,
        page = page,
        ext = ext,
        series = "示例系列",
        seriesOrder = "3",
        chapters = "12",
    )
    return DownloadTemplate.renderPath(
        template = template,
        values = values,
        autoPageSuffix = page,
        ext = ext,
    )
}

private fun chooseDownloadDirectory(initialPath: String, onChosen: (String) -> Unit) {
    val chooser = JFileChooser(initialPath).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "选择下载目录"
        isAcceptAllFileFilterUsed = false
    }
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.absolutePath?.let(onChosen)
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
private fun AccountSettings(
    accountScreenModel: AccountSettingsScreenModel,
    settingsScreenModel: SettingsScreenModel,
) {
    val profileState by accountScreenModel.profileState.collectAsState()
    val profileDetailState by accountScreenModel.profileDetailState.collectAsState()
    val isShowR18 by settingsScreenModel.isShowR18Flow.collectAsState()
    when (val state = profileState) {
        UiState.Loading -> AccountProfileLoading()
        is UiState.Error -> ErrorView(state.message, accountScreenModel::refresh)
        is UiState.Success -> AccountProfile(
            self = state.data,
            detailState = profileDetailState,
            onRetryDetail = accountScreenModel::refresh,
        )
    }

    Text("账号操作", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Text("退出后需要重新通过 Pixiv OAuth（授权登录）登录。", style = MaterialTheme.typography.bodyMedium)
    Button(
        onClick = settingsScreenModel::logout,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
    ) {
        Text("退出登录")
    }
    SettingSwitch(
        title = "显示 R18 内容",
        subtitle = "",
        checked = isShowR18,
        onCheckedChange = settingsScreenModel::setIsShowR18,
    )
}

@Composable
private fun AccountProfileLoading() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UserAvatar(url = null, size = 64)
        Text("正在加载账号资料…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AccountProfile(
    self: ceui.loxia.SelfProfile,
    detailState: UiState<ceui.loxia.ProfileBean>,
    onRetryDetail: () -> Unit,
) {
    val user = self.profile
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            UserAvatar(
                url = user.profile_image_urls?.px_50x50 ?: user.profile_image_urls?.medium,
                size = 64,
            )
            Column {
                Text(user.name ?: "Unknown", style = MaterialTheme.typography.titleLarge)
                Text(
                    "@${user.pixiv_id ?: user.account ?: user.user_id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (user.is_premium == true) {
                    Text("Premium", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        when (detailState) {
            UiState.Loading -> Text("正在加载详细资料…", style = MaterialTheme.typography.bodySmall)
            is UiState.Error -> ErrorView(detailState.message, onRetryDetail)
            is UiState.Success -> {
                val profile = detailState.data
                Text(
                    "作品：插画 ${profile.total_illusts} · 漫画 ${profile.total_manga} · 小说 ${profile.total_novels}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "社交：关注 ${profile.total_follow_users} · 好P友 ${profile.total_mypixiv_users} · 收藏 ${profile.total_illust_bookmarks_public}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!profile.job.isNullOrEmpty()) Text("职业：${profile.job}", style = MaterialTheme.typography.bodySmall)
                if (!profile.region.isNullOrEmpty()) Text("地区：${profile.region}", style = MaterialTheme.typography.bodySmall)
                if (!profile.twitter_account.isNullOrEmpty()) {
                    Text("Twitter：@${profile.twitter_account}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
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
