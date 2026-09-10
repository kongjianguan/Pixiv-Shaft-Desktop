package ceui.pixiv.ui.screen.settings

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.ProfileBean
import ceui.loxia.SelfProfile
import ceui.pixiv.di.AppContainer
import ceui.pixiv.net.api.Client
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.ui.util.resolveSelfProfile
import ceui.pixiv.ui.util.resolvedUserId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** 账号设置页专用的远程资料状态，不混入本地设置项和作品流分页状态。 */
class AccountSettingsScreenModel(
    private val client: Client = AppContainer.client,
) : ScreenModel {

    private val _profileState = MutableStateFlow<UiState<SelfProfile>>(UiState.Loading)
    val profileState: StateFlow<UiState<SelfProfile>> = _profileState.asStateFlow()

    private val _profileDetailState = MutableStateFlow<UiState<ProfileBean>>(UiState.Loading)
    val profileDetailState: StateFlow<UiState<ProfileBean>> = _profileDetailState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        loadInitial()
    }

    private fun loadInitial() {
        screenModelScope.launch { loadAccount() }
    }

    fun refresh() {
        screenModelScope.launch {
            _isRefreshing.value = true
            try {
                loadAccount(forceRefresh = true)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun loadAccount(forceRefresh: Boolean = false) {
        if (_profileState.value !is UiState.Success) {
            _profileState.value = UiState.Loading
        }
        try {
            val self = client.resolveSelfProfile(forceRefresh)
            _profileState.value = UiState.Success(self)

            loadProfileDetail(self.resolvedUserId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _profileState.value = UiState.Error(e.message ?: "加载账号资料失败")
        }
    }

    private suspend fun loadProfileDetail(userId: Long) {
        _profileDetailState.value = UiState.Loading
        try {
            val detail = client.appApi.getUserDetail(userId)
            _profileDetailState.value = UiState.Success(detail.profile ?: ProfileBean())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _profileDetailState.value = UiState.Error(e.message ?: "加载账号详细资料失败")
        }
    }
}
