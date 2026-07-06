package ceui.pixiv.ui.screen.profile

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.ProfileBean
import ceui.loxia.SelfProfile
import ceui.loxia.UserDetailResponse
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.state.Pager
import ceui.pixiv.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class ProfileScreenModel : ScreenModel {

    private val client = AppContainer.client
    private val db = AppContainer.database
    private val bookmarkPager = Pager<IllustResponse, Illust>(client, IllustResponse::class.java)

    private val _profileState = MutableStateFlow<UiState<SelfProfile>>(UiState.Loading)
    val profileState: StateFlow<UiState<SelfProfile>> = _profileState.asStateFlow()

    private val _profileDetailState = MutableStateFlow<UiState<ProfileBean>>(UiState.Loading)
    val profileDetailState: StateFlow<UiState<ProfileBean>> = _profileDetailState.asStateFlow()

    private val _bookmarksState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val bookmarksState: StateFlow<UiState<List<Illust>>> = _bookmarksState.asStateFlow()

    private val _history = MutableStateFlow<List<Illust>>(emptyList())
    val history: StateFlow<List<Illust>> = _history.asStateFlow()

    init {
        loadProfile()
        loadHistory()
    }

    private fun loadProfile() {
        screenModelScope.launch {
            _profileState.value = UiState.Loading
            try {
                val profile = client.appApi.getSelfProfile()
                _profileState.value = UiState.Success(profile)
                val userId = profile.profile.user_id.takeIf { it > 0 } ?: profile.profile.id
                loadProfileDetail(userId)
                loadBookmarks(userId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _profileState.value = UiState.Error(e.message ?: "Failed to load profile")
            }
        }
    }

    private fun loadProfileDetail(userId: Long) {
        screenModelScope.launch {
            _profileDetailState.value = UiState.Loading
            try {
                val detail = client.appApi.getUserDetail(userId)
                _profileDetailState.value = UiState.Success(detail.profile ?: ProfileBean())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _profileDetailState.value = UiState.Error(e.message ?: "Failed to load profile detail")
            }
        }
    }

    private fun loadBookmarks(userId: Long) {
        screenModelScope.launch {
            _bookmarksState.value = UiState.Loading
            try {
                val resp = client.appApi.getUserBookmarkedIllusts(userId, "public")
                bookmarkPager.refresh(resp)
                _bookmarksState.value = UiState.Success(bookmarkPager.items.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _bookmarksState.value = UiState.Error(e.message ?: "Failed to load bookmarks")
            }
        }
    }

    private fun loadHistory() {
        screenModelScope.launch {
            try {
                val rows = db.queries.illustHistoryQueries.selectRecentIllusts(50)
                    .executeAsList()
                val illusts = rows.mapNotNull { row ->
                    try {
                        com.google.gson.Gson().fromJson(row.illustJson, Illust::class.java)
                    } catch (_: Exception) { null }
                }
                _history.value = illusts
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // History is best-effort
            }
        }
    }
}
