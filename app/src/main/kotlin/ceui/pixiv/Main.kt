package ceui.pixiv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.window.application
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import cafe.adriel.voyager.navigator.Navigator
import ceui.pixiv.di.AppContainer
import ceui.pixiv.platform.TrayManager
import ceui.pixiv.ui.auth.AuthState
import ceui.pixiv.ui.navigation.MainScreen
import ceui.pixiv.ui.screen.login.LoginScreen
import ceui.pixiv.ui.theme.ShaftTheme

// Global ESC signal — incremented by an AWT KeyEventDispatcher. Compose UI observes
// this and decides what to do (pop navigator / exit fullscreen). Bypasses Compose's
// focus-based key dispatch which doesn't fire on non-focusable containers.
internal val globalEscCounter = mutableStateOf(0)
internal val fullscreenImageActive = mutableStateOf(false)

fun main() {
    // 让 macOS 托盘图标按 template image（模板图像）渲染，自动随菜单栏反色。
    // 这个 property（属性）必须在 AWT 加载 CTrayIcon 类之前设置。
    System.setProperty("apple.awt.enableTemplateImages", "true")

    return application {
        AppContainer.init()
        // Install a global key dispatcher: fires regardless of focus.
        val escDispatcher = KeyEventDispatcher { e ->
            if (e.id == KeyEvent.KEY_RELEASED && e.keyCode == KeyEvent.VK_ESCAPE) {
                globalEscCounter.value = globalEscCounter.value + 1
                true // consume
            } else {
                false
            }
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(escDispatcher)
        var isExiting = false

        Window(
            onCloseRequest = {
                if (!isExiting) {
                    // Close-to-tray: hide window instead of exiting
                    val frame = java.awt.Window.getWindows()
                        .filterIsInstance<java.awt.Frame>()
                        .firstOrNull()
                    if (frame != null) {
                        frame.isVisible = false
                        TrayManager.setup(
                            onShow = { frame.isVisible = true; frame.toFront() },
                            onExit = {
                                isExiting = true
                                TrayManager.remove()
                                AppContainer.close()
                                exitApplication()
                            }
                        )
                    }
                }
            },
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
}
