package ceui.pixiv.ui.state

sealed class UiState<out T> {
    data object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String) : UiState<Nothing>()
}

fun <T> UiState<List<T>>.hasVisibleContent(): Boolean =
    (this as? UiState.Success)?.data?.isNotEmpty() == true

val UiState<*>.isSuccess: Boolean get() = this is UiState.Success<*>
