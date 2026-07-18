package ceui.pixiv.ui.screen.settings

import cafe.adriel.voyager.core.model.ScreenModel
import ceui.pixiv.di.AppContainer
import ceui.pixiv.net.imagehost.ImageHostManager
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
        ImageHostManager.setModeOrdinal(mode)
        ImageHostManager.setCustomHost(settingsStore.customImageHost)
        _restartRequired.value = true
    }

    fun setCustomImageHost(host: String) {
        settingsStore.setCustomImageHost(host)
        ImageHostManager.setCustomHost(host)
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

    fun logout() {
        tokenStore.clear()
        AppContainer.updateAuthState()
    }
}
