package ceui.pixiv.ui.history

import com.google.gson.Gson
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.loxia.User
import ceui.pixiv.di.AppContainer
import ceui.pixiv.store.BrowseHistoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Window
import java.io.File

enum class BrowseHistoryTab(
    val contentType: String,
    val title: String,
) {
    ILLUST("illust", "插画 / 漫画"),
    NOVEL("novel", "小说"),
    USER("user", "用户"),
}

data class BrowseHistoryDisplay(
    val item: BrowseHistoryItem,
    val illust: Illust? = null,
    val novel: Novel? = null,
    val user: User? = null,
) {
    val key: String get() = "${item.contentType}:${item.targetId}"
}

data class BrowseHistoryBackup(
    val version: Int = 1,
    val entries: List<BrowseHistoryBackupEntry> = emptyList(),
)

data class BrowseHistoryBackupEntry(
    val contentType: String,
    val targetId: Long,
    val payloadJson: String,
    val viewedAt: Long,
)

class BrowseHistoryScreenModel : ScreenModel {

    private val database = AppContainer.database
    private val store = database.browseHistory
    private val gson = Gson()

    private val _tab = MutableStateFlow(BrowseHistoryTab.ILLUST)
    val tab: StateFlow<BrowseHistoryTab> = _tab.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _items = MutableStateFlow<List<BrowseHistoryDisplay>>(emptyList())
    val items: StateFlow<List<BrowseHistoryDisplay>> = _items.asStateFlow()

    private val _selectedKeys = MutableStateFlow<Set<String>>(emptySet())
    val selectedKeys: StateFlow<Set<String>> = _selectedKeys.asStateFlow()

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var offset = 0L
    private var loadGeneration = 0

    init {
        loadFirst()
    }

    fun selectTab(tab: BrowseHistoryTab) {
        if (_tab.value == tab) return
        _tab.value = tab
        exitSelectionMode()
        loadFirst()
    }

    fun updateQuery(value: String) {
        if (_query.value == value) return
        _query.value = value
        loadFirst()
    }

    fun loadFirst() {
        val generation = ++loadGeneration
        screenModelScope.launch {
            _isLoading.value = true
            val loaded = withContext(Dispatchers.IO) {
                store.list(
                    contentType = _tab.value.contentType,
                    query = _query.value,
                    limit = PAGE_SIZE,
                    offset = 0L,
                )
            }
            if (generation != loadGeneration) return@launch
            offset = loaded.size.toLong()
            _hasMore.value = loaded.size == PAGE_SIZE.toInt()
            _items.value = loaded.mapNotNull(::toDisplay)
            _selectedKeys.value = emptySet()
            _isLoading.value = false
        }
    }

    fun loadMore() {
        if (_isLoading.value || !_hasMore.value) return
        val generation = loadGeneration
        screenModelScope.launch {
            _isLoading.value = true
            val loaded = withContext(Dispatchers.IO) {
                store.list(
                    contentType = _tab.value.contentType,
                    query = _query.value,
                    limit = PAGE_SIZE,
                    offset = offset,
                )
            }
            if (generation != loadGeneration) return@launch
            offset += loaded.size
            _hasMore.value = loaded.size == PAGE_SIZE.toInt()
            _items.value = _items.value + loaded.mapNotNull(::toDisplay)
            _isLoading.value = false
        }
    }

    fun enterSelectionMode() {
        if (_items.value.isNotEmpty()) _selectionMode.value = true
    }

    fun exitSelectionMode() {
        _selectionMode.value = false
        _selectedKeys.value = emptySet()
    }

    fun toggleSelection(key: String) {
        if (!_selectionMode.value) return
        _selectedKeys.value = _selectedKeys.value.toMutableSet().apply {
            if (!add(key)) remove(key)
        }
    }

    fun toggleSelectAll() {
        if (!_selectionMode.value) return
        val allKeys = _items.value.map { it.key }.toSet()
        _selectedKeys.value = if (_selectedKeys.value == allKeys) emptySet() else allKeys
    }

    fun deleteItem(item: BrowseHistoryDisplay) {
        screenModelScope.launch {
            withContext(Dispatchers.IO) {
                store.delete(item.item.contentType, item.item.targetId)
            }
            loadFirst()
        }
    }

    fun deleteSelected() {
        val keys = _selectedKeys.value
        if (keys.isEmpty()) return
        val targets = _items.value.filter { it.key in keys }
        screenModelScope.launch {
            withContext(Dispatchers.IO) {
                targets.forEach { item ->
                    store.delete(item.item.contentType, item.item.targetId)
                }
            }
            exitSelectionMode()
            loadFirst()
        }
    }

    fun clearCurrentTab() {
        screenModelScope.launch {
            withContext(Dispatchers.IO) {
                store.deleteType(_tab.value.contentType)
            }
            loadFirst()
        }
    }

    fun clearAll() {
        screenModelScope.launch {
            withContext(Dispatchers.IO) { store.clear() }
            exitSelectionMode()
            loadFirst()
        }
    }

    fun exportToFile(): String {
        val entries = store.all().map { item ->
            BrowseHistoryBackupEntry(
                contentType = item.contentType,
                targetId = item.targetId,
                payloadJson = item.payloadJson,
                viewedAt = item.viewedAt,
            )
        }
        if (entries.isEmpty()) return "没有可导出的浏览记录"
        val file = chooseFile(FileDialog.SAVE, "PixivShaft-BrowseHistory.json") ?: return "已取消导出"
        file.writeText(gson.toJson(BrowseHistoryBackup(entries = entries)))
        return "已导出 ${entries.size} 条浏览记录"
    }

    fun importFromFile(): String {
        val file = chooseFile(FileDialog.LOAD, null) ?: return "已取消导入"
        val backup = runCatching {
            gson.fromJson(file.readText(), BrowseHistoryBackup::class.java)
        }.getOrNull() ?: return "导入失败：文件格式不正确"

        val validTypes = BrowseHistoryTab.entries.map { it.contentType }.toSet()
        val entries = backup.entries.filter {
            it.contentType in validTypes && it.targetId > 0L && it.payloadJson.isNotBlank()
        }
        if (entries.isEmpty()) return "导入失败：没有有效的浏览记录"

        entries.forEach { entry ->
            store.upsert(entry.contentType, entry.targetId, entry.payloadJson, entry.viewedAt)
        }
        loadFirst()
        return "已导入 ${entries.size} 条浏览记录"
    }

    private fun toDisplay(item: BrowseHistoryItem): BrowseHistoryDisplay? = when (item.contentType) {
        BrowseHistoryTab.ILLUST.contentType -> runCatching {
            BrowseHistoryDisplay(item = item, illust = gson.fromJson(item.payloadJson, Illust::class.java))
        }.getOrNull()
        BrowseHistoryTab.NOVEL.contentType -> runCatching {
            BrowseHistoryDisplay(item = item, novel = gson.fromJson(item.payloadJson, Novel::class.java))
        }.getOrNull()
        BrowseHistoryTab.USER.contentType -> runCatching {
            BrowseHistoryDisplay(item = item, user = gson.fromJson(item.payloadJson, User::class.java))
        }.getOrNull()
        else -> null
    }

    private fun chooseFile(mode: Int, defaultName: String?): File? {
        val parent = Window.getWindows()
            .filterIsInstance<Frame>()
            .firstOrNull { it.isActive }
            ?: Window.getWindows().filterIsInstance<Frame>().firstOrNull()
        val dialog = FileDialog(parent, if (mode == FileDialog.SAVE) "导出浏览记录" else "导入浏览记录", mode)
        dialog.directory = File(System.getProperty("user.home"), "Downloads").path
        dialog.file = defaultName
        dialog.isVisible = true
        val fileName = dialog.file ?: return null
        return File(dialog.directory, fileName)
    }

    companion object {
        private const val PAGE_SIZE = 30L
    }
}
