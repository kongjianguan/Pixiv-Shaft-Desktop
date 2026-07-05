package ceui.pixiv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.Navigator

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Pixiv Shaft"
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Navigator(PlaceholderScreen)
        }
    }
}

private object PlaceholderScreen : cafe.adriel.voyager.core.screen.Screen {
    @Composable
    override fun Content() {
        androidx.compose.material3.Text("Pixiv Shaft Desktop — loading…")
    }
}
