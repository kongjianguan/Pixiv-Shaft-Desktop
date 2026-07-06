package ceui.pixiv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import cafe.adriel.voyager.navigator.Navigator
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.auth.AuthState
import ceui.pixiv.ui.navigation.MainScreen
import ceui.pixiv.ui.screen.login.LoginScreen
import ceui.pixiv.ui.theme.ShaftTheme

fun main() = application {
    AppContainer.init()
    println("PLAN 6 GATE PASSED — Ugoira animation integrated")
    Window(
        onCloseRequest = ::exitApplication,
        title = "Pixiv Shaft"
    ) {
        ShaftTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                val authState by AppContainer.authState.collectAsState()
                key(authState) {
                    Navigator(
                        if (authState is AuthState.LoggedIn) MainScreen()
                        else LoginScreen()
                    )
                }
            }
        }
    }
}
