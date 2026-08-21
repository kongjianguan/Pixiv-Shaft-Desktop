package ceui.pixiv.store

import ceui.pixiv.net.abstractions.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path

class SettingsStore(
    private val kv: KvStore = PreferencesKv.forApp(),
) : Settings {
    private val workFeedMaxColumnWidthDpSetting = IntSetting(kv, "workFeedMaxColumnWidthDp", 360, 220..720)
    private val workFeedMaxColumnsSetting = IntSetting(kv, "workFeedMaxColumns", 4, 1..8)
    private val workFeedMinColumnWidthDpSetting = IntSetting(kv, "workFeedMinColumnWidthDp", 280, 180..560)
    private val workTitleMaxLinesSetting = IntSetting(kv, "workTitleMaxLines", 1, 1..5)
    private val novelFeedMaxColumnWidthDpSetting = IntSetting(kv, "novelFeedMaxColumnWidthDp", 360, 260..720)
    private val novelFeedMaxColumnsSetting = IntSetting(kv, "novelFeedMaxColumns", 4, 1..8)
    private val novelFeedMinColumnWidthDpSetting = IntSetting(kv, "novelFeedMinColumnWidthDp", 280, 220..560)
    private val novelTitleMaxLinesSetting = IntSetting(kv, "novelTitleMaxLines", 2, 1..5)
    private val readerFontSizeSpSetting = IntSetting(kv, "readerFontSizeSp", 18, 14..30)
    private val _readerLineSpacing = MutableStateFlow(
        (kv.getString("readerLineSpacing")?.toFloatOrNull() ?: 1.8f).coerceIn(1.2f, 2.6f)
    )
    private val readerParagraphSpacingDpSetting = IntSetting(kv, "readerParagraphSpacingDp", 12, 4..28)
    private val _readerTheme = MutableStateFlow(
        kv.getString("readerTheme")?.takeIf { it in READER_THEMES } ?: "paper"
    )
    private val themeColorIndexSetting = IntSetting(kv, "themeColorIndex", 0, 0..9)
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
    val workFeedMaxColumnWidthDp: Int get() = workFeedMaxColumnWidthDpSetting.value
    val workFeedMaxColumnWidthDpFlow: StateFlow<Int> = workFeedMaxColumnWidthDpSetting.flow
    val workFeedMaxColumns: Int get() = workFeedMaxColumnsSetting.value
    val workFeedMaxColumnsFlow: StateFlow<Int> = workFeedMaxColumnsSetting.flow
    val workFeedMinColumnWidthDp: Int get() = workFeedMinColumnWidthDpSetting.value
    val workFeedMinColumnWidthDpFlow: StateFlow<Int> = workFeedMinColumnWidthDpSetting.flow
    val workTitleMaxLines: Int get() = workTitleMaxLinesSetting.value
    val workTitleMaxLinesFlow: StateFlow<Int> = workTitleMaxLinesSetting.flow
    /** Upper bound for each responsive novel-feed column, in dp. */
    val novelFeedMaxColumnWidthDp: Int
        get() = novelFeedMaxColumnWidthDpSetting.value
    val novelFeedMaxColumnWidthDpFlow: StateFlow<Int> = novelFeedMaxColumnWidthDpSetting.flow
    val novelFeedMaxColumns: Int get() = novelFeedMaxColumnsSetting.value
    val novelFeedMaxColumnsFlow: StateFlow<Int> = novelFeedMaxColumnsSetting.flow
    val novelFeedMinColumnWidthDp: Int get() = novelFeedMinColumnWidthDpSetting.value
    val novelFeedMinColumnWidthDpFlow: StateFlow<Int> = novelFeedMinColumnWidthDpSetting.flow
    val novelTitleMaxLines: Int get() = novelTitleMaxLinesSetting.value
    val novelTitleMaxLinesFlow: StateFlow<Int> = novelTitleMaxLinesSetting.flow
    val readerFontSizeSp: Int get() = readerFontSizeSpSetting.value
    val readerFontSizeSpFlow: StateFlow<Int> = readerFontSizeSpSetting.flow
    val readerLineSpacing: Float get() = _readerLineSpacing.value
    val readerLineSpacingFlow: StateFlow<Float> = _readerLineSpacing.asStateFlow()
    val readerParagraphSpacingDp: Int get() = readerParagraphSpacingDpSetting.value
    val readerParagraphSpacingDpFlow: StateFlow<Int> = readerParagraphSpacingDpSetting.flow
    val readerTheme: String get() = _readerTheme.value
    val readerThemeFlow: StateFlow<String> = _readerTheme.asStateFlow()
    val themeColorIndex: Int get() = themeColorIndexSetting.value
    val themeColorIndexFlow: StateFlow<Int> = themeColorIndexSetting.flow
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
        workFeedMaxColumnWidthDpSetting.set(value)
    }
    fun setWorkFeedMaxColumns(value: Int) {
        workFeedMaxColumnsSetting.set(value)
    }
    fun setWorkFeedMinColumnWidthDp(value: Int) {
        workFeedMinColumnWidthDpSetting.set(value)
    }
    fun setWorkTitleMaxLines(value: Int) {
        workTitleMaxLinesSetting.set(value)
    }
    fun setNovelFeedMaxColumnWidthDp(value: Int) {
        novelFeedMaxColumnWidthDpSetting.set(value)
    }
    fun setNovelFeedMaxColumns(value: Int) {
        novelFeedMaxColumnsSetting.set(value)
    }
    fun setNovelFeedMinColumnWidthDp(value: Int) {
        novelFeedMinColumnWidthDpSetting.set(value)
    }
    fun setNovelTitleMaxLines(value: Int) {
        novelTitleMaxLinesSetting.set(value)
    }

    fun setReaderFontSizeSp(value: Int) {
        readerFontSizeSpSetting.set(value)
    }

    fun setReaderLineSpacing(value: Float) {
        val clamped = value.coerceIn(1.2f, 2.6f)
        kv.putString("readerLineSpacing", clamped.toString())
        _readerLineSpacing.value = clamped
    }

    fun setReaderParagraphSpacingDp(value: Int) {
        readerParagraphSpacingDpSetting.set(value)
    }

    fun setReaderTheme(value: String) {
        val theme = value.takeIf { it in READER_THEMES } ?: "paper"
        kv.putString("readerTheme", theme)
        _readerTheme.value = theme
    }

    fun setThemeColorIndex(value: Int) {
        themeColorIndexSetting.set(value)
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

    private class IntSetting(
        private val kv: KvStore,
        private val key: String,
        default: Int,
        private val range: IntRange,
    ) {
        private val state = MutableStateFlow(kv.getInt(key, default).coerceIn(range.first, range.last))
        val value: Int get() = state.value
        val flow: StateFlow<Int> = state.asStateFlow()

        fun set(value: Int) {
            val clamped = value.coerceIn(range.first, range.last)
            kv.putInt(key, clamped)
            state.value = clamped
        }
    }
}
