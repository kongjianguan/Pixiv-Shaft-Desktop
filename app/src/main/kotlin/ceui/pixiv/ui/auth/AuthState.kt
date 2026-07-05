package ceui.pixiv.ui.auth

sealed class AuthState {
    data object LoggedOut : AuthState()
    data class LoggedIn(val userId: Long? = null) : AuthState()
}
