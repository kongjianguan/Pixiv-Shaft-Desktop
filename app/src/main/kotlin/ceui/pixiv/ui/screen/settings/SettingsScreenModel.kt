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

    fun logout() {
        tokenStore.clear()
        AppContainer.updateAuthState()
    }
}
