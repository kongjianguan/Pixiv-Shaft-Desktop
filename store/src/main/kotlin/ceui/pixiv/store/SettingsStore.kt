package ceui.pixiv.store

import ceui.pixiv.net.abstractions.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path

class SettingsStore(
    private val kv: KvStore = PreferencesKv.forApp(),
) : Settings {
    private val _workFeedMaxColumnWidthDp = MutableStateFlow(
        kv.getInt("workFeedMaxColumnWidthDp", 360).coerceIn(220, 720)
    )
    private val _workFeedMaxColumns = MutableStateFlow(
        kv.getInt("workFeedMaxColumns", 4).coerceIn(1, 8)
    )
    private val _workFeedMinColumnWidthDp = MutableStateFlow(
        kv.getInt("workFeedMinColumnWidthDp", 280).coerceIn(180, 560)
    )
    private val _workTitleMaxLines = MutableStateFlow(
        kv.getInt("workTitleMaxLines", 1).coerceIn(1, 5)
    )
    private val _novelFeedMaxColumnWidthDp = MutableStateFlow(
        kv.getInt("novelFeedMaxColumnWidthDp", 360).coerceIn(260, 720)
    )
    private val _novelFeedMaxColumns = MutableStateFlow(
        kv.getInt("novelFeedMaxColumns", 4).coerceIn(1, 8)
    )
    private val _novelFeedMinColumnWidthDp = MutableStateFlow(
        kv.getInt("novelFeedMinColumnWidthDp", 280).coerceIn(220, 560)
    )
    private val _novelTitleMaxLines = MutableStateFlow(
        kv.getInt("novelTitleMaxLines", 2).coerceIn(1, 5)
    )
    private val _readerFontSizeSp = MutableStateFlow(
        kv.getInt("readerFontSizeSp", 18).coerceIn(14, 30)
    )
    private val _readerLineSpacing = MutableStateFlow(
        (kv.getString("readerLineSpacing")?.toFloatOrNull() ?: 1.8f).coerceIn(1.2f, 2.6f)
    )
    private val _readerParagraphSpacingDp = MutableStateFlow(
        kv.getInt("readerParagraphSpacingDp", 12).coerceIn(4, 28)
    )
    private val _readerTheme = MutableStateFlow(
        kv.getString("readerTheme")?.takeIf { it in READER_THEMES } ?: "paper"
    )
    private val _themeColorIndex = MutableStateFlow(
        kv.getInt("themeColorIndex", 0).coerceIn(0, 9)
    )
    private val _themeMode = MutableStateFlow(
        kv.getString("themeMode")?.takeIf { it in THEME_MODES } ?: "system"
    )
    private val _saveBrowseHistory = MutableStateFlow(
        kv.getBoolean("saveBrowseHistory", true)
    )
    private val _isShowR18 = MutableStateFlow(
        kv.getBoolean("isShowR18", false)
    )
    private val _downloadRootPath = MutableStateFlow(
        kv.getString("downloadRootPath")?.takeIf { it.isNotBlank() } ?: defaultDownloadRootPath()
    )
    private val _illustFileNameTemplate = MutableStateFlow(
        kv.getString("illustFileNameTemplate")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_ILLUST_FILE_NAME_TEMPLATE
    )
    private val _ugoiraFileNameTemplate = MutableStateFlow(
        kv.getString("ugoiraFileNameTemplate")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_UGOIRA_FILE_NAME_TEMPLATE
    )
    private val _novelFileNameTemplate = MutableStateFlow(
        kv.getString("novelFileNameTemplate")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_NOVEL_FILE_NAME_TEMPLATE
    )

    override val isDirectConnect: Boolean get() = kv.getBoolean("isDirectConnect", true)
    override val isUseSecureDns: Boolean get() = kv.getBoolean("isUseSecureDns", false)
    override val imageHostMode: Int get() = kv.getInt("imageHostMode", 0)
    override val customImageHost: String get() = kv.getString("customImageHost") ?: ""
    val workFeedMaxColumnWidthDp: Int get() = _workFeedMaxColumnWidthDp.value
    val workFeedMaxColumnWidthDpFlow: StateFlow<Int> = _workFeedMaxColumnWidthDp.asStateFlow()
    val workFeedMaxColumns: Int get() = _workFeedMaxColumns.value
    val workFeedMaxColumnsFlow: StateFlow<Int> = _workFeedMaxColumns.asStateFlow()
    val workFeedMinColumnWidthDp: Int get() = _workFeedMinColumnWidthDp.value
    val workFeedMinColumnWidthDpFlow: StateFlow<Int> = _workFeedMinColumnWidthDp.asStateFlow()
    val workTitleMaxLines: Int get() = _workTitleMaxLines.value
    val workTitleMaxLinesFlow: StateFlow<Int> = _workTitleMaxLines.asStateFlow()
    /** Upper bound for each responsive novel-feed column, in dp. */
    val novelFeedMaxColumnWidthDp: Int
        get() = _novelFeedMaxColumnWidthDp.value
    val novelFeedMaxColumnWidthDpFlow: StateFlow<Int> = _novelFeedMaxColumnWidthDp.asStateFlow()
    val novelFeedMaxColumns: Int get() = _novelFeedMaxColumns.value
    val novelFeedMaxColumnsFlow: StateFlow<Int> = _novelFeedMaxColumns.asStateFlow()
    val novelFeedMinColumnWidthDp: Int get() = _novelFeedMinColumnWidthDp.value
    val novelFeedMinColumnWidthDpFlow: StateFlow<Int> = _novelFeedMinColumnWidthDp.asStateFlow()
    val novelTitleMaxLines: Int get() = _novelTitleMaxLines.value
    val novelTitleMaxLinesFlow: StateFlow<Int> = _novelTitleMaxLines.asStateFlow()
    val readerFontSizeSp: Int get() = _readerFontSizeSp.value
    val readerFontSizeSpFlow: StateFlow<Int> = _readerFontSizeSp.asStateFlow()
    val readerLineSpacing: Float get() = _readerLineSpacing.value
    val readerLineSpacingFlow: StateFlow<Float> = _readerLineSpacing.asStateFlow()
    val readerParagraphSpacingDp: Int get() = _readerParagraphSpacingDp.value
    val readerParagraphSpacingDpFlow: StateFlow<Int> = _readerParagraphSpacingDp.asStateFlow()
    val readerTheme: String get() = _readerTheme.value
    val readerThemeFlow: StateFlow<String> = _readerTheme.asStateFlow()
    val themeColorIndex: Int get() = _themeColorIndex.value
    val themeColorIndexFlow: StateFlow<Int> = _themeColorIndex.asStateFlow()
    val themeMode: String get() = _themeMode.value
    val themeModeFlow: StateFlow<String> = _themeMode.asStateFlow()
    val saveBrowseHistory: Boolean get() = _saveBrowseHistory.value
    val saveBrowseHistoryFlow: StateFlow<Boolean> = _saveBrowseHistory.asStateFlow()
    val isShowR18: Boolean get() = _isShowR18.value
    val isShowR18Flow: StateFlow<Boolean> = _isShowR18.asStateFlow()
    val downloadRootPath: String get() = _downloadRootPath.value
    val downloadRootPathFlow: StateFlow<String> = _downloadRootPath.asStateFlow()
    val illustFileNameTemplate: String get() = _illustFileNameTemplate.value
    val illustFileNameTemplateFlow: StateFlow<String> = _illustFileNameTemplate.asStateFlow()
    val ugoiraFileNameTemplate: String get() = _ugoiraFileNameTemplate.value
    val ugoiraFileNameTemplateFlow: StateFlow<String> = _ugoiraFileNameTemplate.asStateFlow()
    val novelFileNameTemplate: String get() = _novelFileNameTemplate.value
    val novelFileNameTemplateFlow: StateFlow<String> = _novelFileNameTemplate.asStateFlow()
    val searchIllustTarget: String
        get() = validSearchTarget(kv.getString(SEARCH_ILLUST_TARGET_KEY))
    val searchNovelTarget: String
        get() = validSearchTarget(kv.getString(SEARCH_NOVEL_TARGET_KEY))

    fun setDirectConnect(value: Boolean) {
        kv.putBoolean("isDirectConnect", value)
    }
    fun setUseSecureDns(value: Boolean) {
        kv.putBoolean("isUseSecureDns", value)
    }
    fun setImageHostMode(value: Int) {
        kv.putInt("imageHostMode", value)
    }
    fun setCustomImageHost(value: String) {
        kv.putString("customImageHost", value)
    }
    fun setWorkFeedMaxColumnWidthDp(value: Int) {
        val clamped = value.coerceIn(220, 720)
        kv.putInt("workFeedMaxColumnWidthDp", clamped)
        _workFeedMaxColumnWidthDp.value = clamped
    }
    fun setWorkFeedMaxColumns(value: Int) {
        val clamped = value.coerceIn(1, 8)
        kv.putInt("workFeedMaxColumns", clamped)
        _workFeedMaxColumns.value = clamped
    }
    fun setWorkFeedMinColumnWidthDp(value: Int) {
        val clamped = value.coerceIn(180, 560)
        kv.putInt("workFeedMinColumnWidthDp", clamped)
        _workFeedMinColumnWidthDp.value = clamped
    }
    fun setWorkTitleMaxLines(value: Int) {
        val clamped = value.coerceIn(1, 5)
        kv.putInt("workTitleMaxLines", clamped)
        _workTitleMaxLines.value = clamped
    }
    fun setNovelFeedMaxColumnWidthDp(value: Int) {
        val clamped = value.coerceIn(260, 720)
        kv.putInt("novelFeedMaxColumnWidthDp", clamped)
        _novelFeedMaxColumnWidthDp.value = clamped
    }
    fun setNovelFeedMaxColumns(value: Int) {
        val clamped = value.coerceIn(1, 8)
        kv.putInt("novelFeedMaxColumns", clamped)
        _novelFeedMaxColumns.value = clamped
    }
    fun setNovelFeedMinColumnWidthDp(value: Int) {
        val clamped = value.coerceIn(220, 560)
        kv.putInt("novelFeedMinColumnWidthDp", clamped)
        _novelFeedMinColumnWidthDp.value = clamped
    }
    fun setNovelTitleMaxLines(value: Int) {
        val clamped = value.coerceIn(1, 5)
        kv.putInt("novelTitleMaxLines", clamped)
        _novelTitleMaxLines.value = clamped
    }

    fun setReaderFontSizeSp(value: Int) {
        val clamped = value.coerceIn(14, 30)
        kv.putInt("readerFontSizeSp", clamped)
        _readerFontSizeSp.value = clamped
    }

    fun setReaderLineSpacing(value: Float) {
        val clamped = value.coerceIn(1.2f, 2.6f)
        kv.putString("readerLineSpacing", clamped.toString())
        _readerLineSpacing.value = clamped
    }

    fun setReaderParagraphSpacingDp(value: Int) {
        val clamped = value.coerceIn(4, 28)
        kv.putInt("readerParagraphSpacingDp", clamped)
        _readerParagraphSpacingDp.value = clamped
    }

    fun setReaderTheme(value: String) {
        val theme = value.takeIf { it in READER_THEMES } ?: "paper"
        kv.putString("readerTheme", theme)
        _readerTheme.value = theme
    }

    fun setThemeColorIndex(value: Int) {
        val index = value.coerceIn(0, 9)
        kv.putInt("themeColorIndex", index)
        _themeColorIndex.value = index
    }

    fun setThemeMode(value: String) {
        val mode = value.takeIf { it in THEME_MODES } ?: "system"
        kv.putString("themeMode", mode)
        _themeMode.value = mode
    }

    fun setSaveBrowseHistory(value: Boolean) {
        kv.putBoolean("saveBrowseHistory", value)
        _saveBrowseHistory.value = value
    }

    fun setIsShowR18(value: Boolean) {
        kv.putBoolean("isShowR18", value)
        _isShowR18.value = value
    }

    fun setDownloadRootPath(value: String) {
        val cleaned = value.trim().ifBlank { defaultDownloadRootPath() }
        kv.putString("downloadRootPath", cleaned)
        _downloadRootPath.value = cleaned
    }

    fun setIllustFileNameTemplate(value: String) {
        val cleaned = value.trim().ifBlank { DEFAULT_ILLUST_FILE_NAME_TEMPLATE }
        kv.putString("illustFileNameTemplate", cleaned)
        _illustFileNameTemplate.value = cleaned
    }

    fun setUgoiraFileNameTemplate(value: String) {
        val cleaned = value.trim().ifBlank { DEFAULT_UGOIRA_FILE_NAME_TEMPLATE }
        kv.putString("ugoiraFileNameTemplate", cleaned)
        _ugoiraFileNameTemplate.value = cleaned
    }

    fun setNovelFileNameTemplate(value: String) {
        val cleaned = value.trim().ifBlank { DEFAULT_NOVEL_FILE_NAME_TEMPLATE }
        kv.putString("novelFileNameTemplate", cleaned)
        _novelFileNameTemplate.value = cleaned
    }

    fun setSearchIllustTarget(value: String) {
        kv.putString(SEARCH_ILLUST_TARGET_KEY, validSearchTarget(value))
    }

    fun setSearchNovelTarget(value: String) {
        kv.putString(SEARCH_NOVEL_TARGET_KEY, validSearchTarget(value))
    }

    fun readerProgress(novelId: Long): Float =
        kv.getString("readerProgress_$novelId")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f

    fun setReaderProgress(novelId: Long, value: Float) {
        kv.putString("readerProgress_$novelId", value.coerceIn(0f, 1f).toString())
    }

    private companion object {
        val READER_THEMES = setOf("system", "paper", "night", "sage")
        val THEME_MODES = setOf("system", "light", "dark")
        val SEARCH_TARGETS = setOf(
            "partial_match_for_tags",
            "exact_match_for_tags",
            "title_and_caption",
            "text",
            "keyword",
        )
        const val SEARCH_ILLUST_TARGET_KEY = "searchIllustTarget"
        const val SEARCH_NOVEL_TARGET_KEY = "searchNovelTarget"
        const val DEFAULT_ILLUST_FILE_NAME_TEMPLATE = "Illusts/{author}/{title} {id}{page}"
        const val DEFAULT_UGOIRA_FILE_NAME_TEMPLATE = "Ugoira/{author}/{title} {id}"
        const val DEFAULT_NOVEL_FILE_NAME_TEMPLATE = "Novels/{series}/{series_order}_{title}_{id}"

        fun defaultDownloadRootPath(): String =
            Path.of(System.getProperty("user.home"), "Pictures", "PixivShaft").toString()

        fun validSearchTarget(value: String?): String =
            value?.takeIf { it in SEARCH_TARGETS } ?: "partial_match_for_tags"
    }
}
