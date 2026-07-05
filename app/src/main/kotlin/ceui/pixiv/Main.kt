package ceui.pixiv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import cafe.adriel.voyager.navigator.Navigator
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.navigation.MainScreen
import ceui.pixiv.ui.theme.ShaftTheme

fun main() = application {
    AppContainer.init()
    Window(
        onCloseRequest = ::exitApplication,
        title = "Pixiv Shaft"
    ) {
        ShaftTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Navigator(MainScreen())
            }
        }
    }
}
