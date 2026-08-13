package ceui.pixiv.ui.screen.settings

import cafe.adriel.voyager.core.model.ScreenModel
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.screen.comment.CommentsController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsScreenModel : ScreenModel {

    private val settingsStore = AppContainer.settingsStore
    private val tokenStore = AppContainer.tokenStore

    val isDirectConnect: Boolean get() = settingsStore.isDirectConnect
    val isUseSecureDns: Boolean get() = settingsStore.isUseSecureDns
    val imageHostMode: Int get() = settingsStore.imageHostMode
    val customImageHost: String get() = settingsStore.customImageHost
    val workFeedMaxColumnWidthDp: Int get() = settingsStore.workFeedMaxColumnWidthDp
    val workFeedMaxColumns: Int get() = settingsStore.workFeedMaxColumns
    val workFeedMinColumnWidthDp: Int get() = settingsStore.workFeedMinColumnWidthDp
    val workTitleMaxLines: Int get() = settingsStore.workTitleMaxLines
    val novelFeedMaxColumnWidthDp: Int get() = settingsStore.novelFeedMaxColumnWidthDp
    val novelFeedMaxColumns: Int get() = settingsStore.novelFeedMaxColumns
    val novelFeedMinColumnWidthDp: Int get() = settingsStore.novelFeedMinColumnWidthDp
    val novelTitleMaxLines: Int get() = settingsStore.novelTitleMaxLines
    val themeColorIndex: Int get() = settingsStore.themeColorIndex
    val themeColorIndexFlow = settingsStore.themeColorIndexFlow
    val themeMode: String get() = settingsStore.themeMode
    val themeModeFlow = settingsStore.themeModeFlow
    val saveBrowseHistory: Boolean get() = settingsStore.saveBrowseHistory
    val saveBrowseHistoryFlow = settingsStore.saveBrowseHistoryFlow
    val isShowR18: Boolean get() = settingsStore.isShowR18
    val isShowR18Flow = settingsStore.isShowR18Flow
    val downloadRootPath: String get() = settingsStore.downloadRootPath
    val downloadRootPathFlow = settingsStore.downloadRootPathFlow
    val illustFileNameTemplate: String get() = settingsStore.illustFileNameTemplate
    val illustFileNameTemplateFlow = settingsStore.illustFileNameTemplateFlow
    val ugoiraFileNameTemplate: String get() = settingsStore.ugoiraFileNameTemplate
    val ugoiraFileNameTemplateFlow = settingsStore.ugoiraFileNameTemplateFlow
    val novelFileNameTemplate: String get() = settingsStore.novelFileNameTemplate
    val novelFileNameTemplateFlow = settingsStore.novelFileNameTemplateFlow

    private val _restartRequired = MutableStateFlow(false)
    val restartRequired: StateFlow<Boolean> = _restartRequired.asStateFlow()

    fun setDirectConnect(value: Boolean) {
        settingsStore.setDirectConnect(value)
        _restartRequired.value = true
    }

    fun setUseSecureDns(value: Boolean) {
        settingsStore.setUseSecureDns(value)
        _restartRequired.value = true
    }

    fun setImageHostMode(mode: Int) {
        settingsStore.setImageHostMode(mode)
        _restartRequired.value = true
    }

    fun setCustomImageHost(host: String) {
        settingsStore.setCustomImageHost(host)
        _restartRequired.value = true
    }

    fun setWorkFeedMaxColumnWidthDp(value: Int) {
        settingsStore.setWorkFeedMaxColumnWidthDp(value)
    }
    fun setWorkFeedMaxColumns(value: Int) {
        settingsStore.setWorkFeedMaxColumns(value)
    }
    fun setWorkFeedMinColumnWidthDp(value: Int) {
        settingsStore.setWorkFeedMinColumnWidthDp(value)
    }
    fun setWorkTitleMaxLines(value: Int) {
        settingsStore.setWorkTitleMaxLines(value)
    }

    fun setNovelFeedMaxColumnWidthDp(value: Int) {
        settingsStore.setNovelFeedMaxColumnWidthDp(value)
    }
    fun setNovelFeedMaxColumns(value: Int) {
        settingsStore.setNovelFeedMaxColumns(value)
    }
    fun setNovelFeedMinColumnWidthDp(value: Int) {
        settingsStore.setNovelFeedMinColumnWidthDp(value)
    }
    fun setNovelTitleMaxLines(value: Int) {
        settingsStore.setNovelTitleMaxLines(value)
    }

    fun setThemeColorIndex(value: Int) {
        settingsStore.setThemeColorIndex(value)
    }

    fun setThemeMode(value: String) {
        settingsStore.setThemeMode(value)
    }

    fun setSaveBrowseHistory(value: Boolean) {
        settingsStore.setSaveBrowseHistory(value)
    }

    fun setIsShowR18(value: Boolean) {
        settingsStore.setIsShowR18(value)
    }

    fun setDownloadRootPath(value: String) {
        settingsStore.setDownloadRootPath(value)
    }

    fun setIllustFileNameTemplate(value: String) {
        settingsStore.setIllustFileNameTemplate(value)
    }

    fun setUgoiraFileNameTemplate(value: String) {
        settingsStore.setUgoiraFileNameTemplate(value)
    }

    fun setNovelFileNameTemplate(value: String) {
        settingsStore.setNovelFileNameTemplate(value)
    }

    fun logout() {
        tokenStore.clear()
        CommentsController.clearCaches()
        AppContainer.updateAuthState()
    }
}
