package ceui.pixiv.ui.screen.detail

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.GifInfoResponse
import ceui.loxia.Illust
import ceui.loxia.IllustResponse
import ceui.loxia.UgoiraMetaData
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.state.Pager
import ceui.pixiv.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class IllustDetailScreenModel(
    private val illustId: Long
) : ScreenModel {

    private val client = AppContainer.client
    private val relatedPager = Pager<IllustResponse, Illust>(client, IllustResponse::class.java)

    private val _illustState = MutableStateFlow<UiState<Illust>>(UiState.Loading)
    val illustState: StateFlow<UiState<Illust>> = _illustState.asStateFlow()

    private val _relatedState = MutableStateFlow<UiState<List<Illust>>>(UiState.Loading)
    val relatedState: StateFlow<UiState<List<Illust>>> = _relatedState.asStateFlow()

    private val _ugoiraState = MutableStateFlow<UiState<UgoiraMetaData?>>(UiState.Loading)
    val ugoiraState: StateFlow<UiState<UgoiraMetaData?>> = _ugoiraState.asStateFlow()

    private val _isBookmarked = MutableStateFlow<Boolean?>(null)
    val isBookmarked: StateFlow<Boolean?> = _isBookmarked.asStateFlow()

    private val _isFollowing = MutableStateFlow<Boolean?>(null)
    val isFollowing: StateFlow<Boolean?> = _isFollowing.asStateFlow()
    private var _userId: Long = 0

    init {
        loadIllust()
        loadRelated()
    }

    private fun loadIllust() {
        screenModelScope.launch {
            _illustState.value = UiState.Loading
            try {
                val resp = client.appApi.getIllust(illustId)
                _illustState.value = resp.illust?.let { illust ->
                    if (illust.isGif()) loadUgoira(illust.id)
                    _isBookmarked.value = illust.is_bookmarked
                    _isFollowing.value = illust.user?.is_followed
                    _userId = illust.user?.id ?: 0
                    UiState.Success(illust)
                } ?: UiState.Error("Illust not found")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _illustState.value = UiState.Error(e.message ?: "Failed to load illust")
            }
        }
    }

    private fun loadUgoira(illustId: Long) {
        screenModelScope.launch {
            _ugoiraState.value = UiState.Loading
            try {
                val resp = client.appApi.getUgoiraMetadata(illustId)
                _ugoiraState.value = UiState.Success(resp.ugoira_metadata)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ugoiraState.value = UiState.Error(e.message ?: "Failed to load ugoira metadata")
            }
        }
    }

    fun toggleBookmark(restrict: String = "public") {
        val current = _isBookmarked.value ?: return
        val id = illustId
        screenModelScope.launch {
            _isBookmarked.value = !current
            try {
                if (current) {
                    client.appApi.removeBookmark(id)
                } else {
                    client.appApi.postBookmark(id, restrict)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _isBookmarked.value = current
            }
        }
    }

    fun toggleFollow(restrict: String = "public") {
        val current = _isFollowing.value ?: return
        val userId = _userId
        if (userId == 0L) return
        screenModelScope.launch {
            _isFollowing.value = !current
            try {
                if (current) {
                    client.appApi.postUnFollow(userId)
                } else {
                    client.appApi.postFollow(userId, restrict)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _isFollowing.value = current
            }
        }
    }

    private fun loadRelated() {
        screenModelScope.launch {
            _relatedState.value = UiState.Loading
            try {
                val resp = client.appApi.getRelatedIllusts(illustId)
                relatedPager.refresh(resp)
                _relatedState.value = UiState.Success(relatedPager.items.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _relatedState.value = UiState.Error(e.message ?: "Failed to load related")
            }
        }
    }
}
