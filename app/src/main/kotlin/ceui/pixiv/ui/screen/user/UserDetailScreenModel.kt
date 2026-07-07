package ceui.pixiv.ui.screen.user

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.ProfileBean
import ceui.loxia.User
import ceui.loxia.UserDetailResponse
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.state.Pager
import ceui.pixiv.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class UserDetailScreenModel(
    private val userId: Long
) : ScreenModel {

    private val client = AppContainer.client
    private val illustPager = Pager<IllustResponse, Illust>(client, IllustResponse::class.java)
    private val bookmarkPager = Pager<IllustResponse, Illust>(client, IllustResponse::class.java)

    private val _userState = MutableStateFlow<UiState<Pair<User, ProfileBean>>>(UiState.Loading)
    val userState: StateFlow<UiState<Pair<User, ProfileBean>>> = _userState.asStateFlow()

    private val _illustsState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val illustsState: StateFlow<UiState<List<Illust>>> = _illustsState.asStateFlow()

    private val _bookmarksState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val bookmarksState: StateFlow<UiState<List<Illust>>> = _bookmarksState.asStateFlow()

    private val _isFollowing = MutableStateFlow<Boolean?>(null)
    val isFollowing: StateFlow<Boolean?> = _isFollowing.asStateFlow()

    init { loadAll() }

    private fun loadAll() {
        loadUserDetail()
        loadIllusts()
        loadBookmarks()
    }

    private fun loadUserDetail() {
        screenModelScope.launch {
            _userState.value = UiState.Loading
            try {
                val detail = client.appApi.getUserDetail(userId)
                val user = detail.user ?: User()
                val profile = detail.profile ?: ProfileBean()
                _userState.value = UiState.Success(user to profile)
                _isFollowing.value = user.is_followed
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _userState.value = UiState.Error(e.message ?: "Failed to load user")
            }
        }
    }

    private fun loadIllusts() {
        screenModelScope.launch {
            _illustsState.value = UiState.Loading
            try {
                val resp = client.appApi.getUserCreatedIllusts(userId, "illust")
                illustPager.refresh(resp)
                _illustsState.value = UiState.Success(illustPager.items.value)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _illustsState.value = UiState.Error(e.message ?: "Failed to load illusts")
            }
        }
    }

    private fun loadBookmarks() {
        screenModelScope.launch {
            _bookmarksState.value = UiState.Loading
            try {
                val resp = client.appApi.getUserBookmarkedIllusts(userId, "public")
                bookmarkPager.refresh(resp)
                _bookmarksState.value = UiState.Success(bookmarkPager.items.value)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _bookmarksState.value = UiState.Error(e.message ?: "Failed to load bookmarks")
            }
        }
    }

    fun toggleFollow(restrict: String = "public") {
        val current = _isFollowing.value ?: return
        screenModelScope.launch {
            _isFollowing.value = !current
            try {
                if (current) {
                    client.appApi.postUnFollow(userId)
                } else {
                    client.appApi.postFollow(userId, restrict)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _isFollowing.value = current
            }
        }
    }
}
