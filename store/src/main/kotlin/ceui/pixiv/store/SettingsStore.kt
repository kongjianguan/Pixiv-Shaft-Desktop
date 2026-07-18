package ceui.pixiv.store

import ceui.pixiv.net.abstractions.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    fun readerProgress(novelId: Long): Float =
        kv.getString("readerProgress_$novelId")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f

    fun setReaderProgress(novelId: Long, value: Float) {
        kv.putString("readerProgress_$novelId", value.coerceIn(0f, 1f).toString())
    }

    private companion object {
        val READER_THEMES = setOf("system", "paper", "night", "sage")
    }
}
