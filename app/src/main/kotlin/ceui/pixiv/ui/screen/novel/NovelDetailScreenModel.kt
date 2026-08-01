package ceui.pixiv.ui.screen.novel

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import ceui.loxia.Novel
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.history.BrowseHistoryRecorder
import ceui.pixiv.ui.state.UiState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class NovelDetailScreenModel(
    private val novelId: Long
) : ScreenModel {

    private val client = AppContainer.client

    private val _state = MutableStateFlow<UiState<Novel>>(UiState.Loading)
    val state: StateFlow<UiState<Novel>> = _state.asStateFlow()
    private val bookmarkInFlight = AtomicBoolean(false)
    private val followInFlight = AtomicBoolean(false)

    init {
        loadNovel()
    }

    fun reload() {
        loadNovel()
    }

    fun toggleBookmark(restrict: String = "public") {
        val novel = (_state.value as? UiState.Success)?.data ?: return
        val current = novel.is_bookmarked ?: return
        if (!bookmarkInFlight.compareAndSet(false, true)) return

        val updated = novel.copy(is_bookmarked = !current)
        _state.value = UiState.Success(updated)
        screenModelScope.launch {
            try {
                if (current) {
                    client.appApi.removeNovelBookmark(novel.id)
                } else {
                    client.appApi.addNovelBookmark(novel.id, restrict)
                }
            } catch (e: CancellationException) {
                _state.value = UiState.Success(novel)
                throw e
            } catch (_: Exception) {
                _state.value = UiState.Success(novel)
            } finally {
                bookmarkInFlight.set(false)
            }
        }
    }

    fun toggleFollow(restrict: String = "public") {
        val novel = (_state.value as? UiState.Success)?.data ?: return
        val user = novel.user ?: return
        val current = user.is_followed ?: return
        if (user.id <= 0L || !followInFlight.compareAndSet(false, true)) return

        val updated = novel.copy(user = user.copy(is_followed = !current))
        _state.value = UiState.Success(updated)
        screenModelScope.launch {
            try {
                if (current) {
                    client.appApi.postUnFollow(user.id)
                } else {
                    client.appApi.postFollow(user.id, restrict)
                }
            } catch (e: CancellationException) {
                _state.value = UiState.Success(novel)
                throw e
            } catch (_: Exception) {
                _state.value = UiState.Success(novel)
            } finally {
                followInFlight.set(false)
            }
        }
    }

    private fun loadNovel() {
        screenModelScope.launch {
            _state.value = UiState.Loading
            try {
                val resp = client.appApi.getNovel(novelId)
                val novel = resp.novel
                if (novel == null) {
                    _state.value = UiState.Error("Novel not found")
                } else {
                    BrowseHistoryRecorder.recordNovel(novel)
                    _state.value = UiState.Success(novel)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "Failed to load novel")
            }
        }
    }
}
