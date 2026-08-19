package ceui.pixiv.ui.auth

sealed class AuthState {
    data object LoggedOut : AuthState()
    data object LoggedIn : AuthState()
}
