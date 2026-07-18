package ceui.pixiv

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.CurrentScreen
import ceui.pixiv.di.AppContainer
import ceui.pixiv.platform.TrayManager
import ceui.pixiv.platform.WindowBackgroundBridge
import ceui.pixiv.ui.auth.AuthState
import ceui.pixiv.ui.navigation.MainScreen
import ceui.pixiv.ui.screen.login.LoginScreen
import ceui.pixiv.ui.screen.settings.SettingsScreen
import ceui.pixiv.ui.theme.ShaftTheme

// Global ESC signal — incremented by an AWT KeyEventDispatcher. Compose UI observes
// this and decides what to do (pop navigator / exit fullscreen). Bypasses Compose's
// focus-based key dispatch which doesn't fire on non-focusable containers.
internal val globalEscCounter = mutableStateOf(0)
internal val fullscreenImageActive = mutableStateOf(false)

internal enum class MainNavigationTarget {
    RECOMMEND,
    DISCOVER,
    SEARCH,
    PROFILE,
}

internal val mainNavigationRequest = mutableStateOf<MainNavigationTarget?>(null)
internal val mainRefreshRequest = mutableStateOf(0)
internal val mainSettingsRequest = mutableStateOf(0)

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // 让 macOS 托盘图标按 template image（模板图像）渲染，自动随菜单栏反色。
    // 这个 property（属性）必须在 AWT 加载 CTrayIcon 类之前设置。
    System.setProperty("apple.awt.enableTemplateImages", "true")

    // 让 AWT 窗口标题栏跟随 macOS 系统外观（浅色/深色模式）。
    // 默认 AWT 在 macOS 上会强制使用浅色标题栏，不加这一行深色模式下也会是白标题栏。
    System.setProperty("apple.awt.application.appearance", "system")

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
            val authState by AppContainer.authState.collectAsState()

            if (authState is AuthState.LoggedIn) {
                // MenuBar（菜单栏）必须位于 Window 的窗口作用域中，才能注册到 macOS 菜单栏。
                MenuBar {
                    Menu("前往", mnemonic = 'G') {
                        Item(
                            "推荐",
                            onClick = { mainNavigationRequest.value = MainNavigationTarget.RECOMMEND },
                            shortcut = KeyShortcut(Key.One, meta = true),
                        )
                        Item(
                            "发现",
                            onClick = { mainNavigationRequest.value = MainNavigationTarget.DISCOVER },
                            shortcut = KeyShortcut(Key.Two, meta = true),
                        )
                        Item(
                            "搜索",
                            onClick = { mainNavigationRequest.value = MainNavigationTarget.SEARCH },
                            shortcut = KeyShortcut(Key.Three, meta = true),
                        )
                        Item(
                            "我的",
                            onClick = { mainNavigationRequest.value = MainNavigationTarget.PROFILE },
                            shortcut = KeyShortcut(Key.Four, meta = true),
                        )
                    }

                    Menu("操作", mnemonic = 'A') {
                        Item(
                            "刷新当前页面",
                            onClick = { mainRefreshRequest.value++ },
                            shortcut = KeyShortcut(Key.R, meta = true),
                        )
                        Separator()
                        Item(
                            "设置",
                            onClick = { mainSettingsRequest.value++ },
                        )
                    }
                }
            }

            // Prevent AWT from repainting the window background on its own schedule.
            // During a live resize AWT's background paint and Compose's Skia render
            // are not synchronised, producing vertical pixel jitter. With ignoreRepaint
            // the SkiaLayer is the sole renderer and the window surface stays coherent.
            LaunchedEffect(Unit) {
                // The AWT Frame may not be in getWindows() yet when this effect
                // fires — retry until we find it.
                var frame: java.awt.Frame? = null
                while (frame == null) {
                    frame = java.awt.Window.getWindows()
                        .filterIsInstance<java.awt.Frame>()
                        .firstOrNull()
                    if (frame == null) delay(50)
                }
                @Suppress("DEPRECATION")
                frame.ignoreRepaint = true
                frame.background = java.awt.Color(30, 30, 30)
                WindowBackgroundBridge.setDark(frame)
            }

            ShaftTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    key(authState) {
                        Navigator(
                            if (authState is AuthState.LoggedIn) MainScreen()
                            else LoginScreen()
                        ) { rootNavigator ->
                            // SettingsScreen（设置页）是普通 Screen，必须交给根 Navigator，
                            // 不能交给 MainScreen 内部的 TabNavigator。
                            val settingsRequest = mainSettingsRequest.value
                            LaunchedEffect(settingsRequest) {
                                if (settingsRequest > 0) {
                                    if (rootNavigator.lastItem !is SettingsScreen) {
                                        rootNavigator.push(SettingsScreen())
                                    }
                                    mainSettingsRequest.value = 0
                                }
                            }
                            CurrentScreen()
                        }
                    }
                }
            }
        }
    }
}
