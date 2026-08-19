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
import ceui.pixiv.platform.AppMenu
import ceui.pixiv.platform.TrayManager
import ceui.pixiv.platform.WindowBackgroundBridge
import ceui.pixiv.ui.auth.AuthState
import ceui.pixiv.ui.navigation.MainScreen
import ceui.pixiv.ui.screen.login.LoginScreen
import ceui.pixiv.ui.screen.download.DownloadScreen
import ceui.pixiv.ui.history.BrowseHistoryScreen
import ceui.pixiv.ui.screen.r18.R18Screen
import ceui.pixiv.ui.screen.settings.SettingsScreen
import ceui.pixiv.ui.screen.pixivision.PixivisionScreen
import ceui.pixiv.ui.screen.comic.ComicScreen
import ceui.pixiv.ui.theme.ShaftTheme
import ceui.pixiv.util.openInBrowser

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
    DYNAMIC,
}

internal val mainNavigationRequest = mutableStateOf<MainNavigationTarget?>(null)
internal val mainRefreshRequest = mutableStateOf(0)
internal val mainSettingsRequest = mutableStateOf(0)
internal val mainDownloadsRequest = mutableStateOf(0)
internal val mainHistoryRequest = mutableStateOf(0)
internal val mainR18Request = mutableStateOf(0)
internal val mainPixivisionRequest = mutableStateOf(0)
internal val mainComicRequest = mutableStateOf(0)

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

            // 登出时清空所有悬挂请求：AppMenu 的「我的」「设置」菜单项在登出后仍留在
            // 系统菜单栏（不随 Compose 组合销毁），点击会留下请求；不清空则下次登录时
            // MainScreen 重新组合会意外消费（自动跳个人页/弹出覆盖页）
            LaunchedEffect(authState) {
                if (authState is AuthState.LoggedOut) {
                    mainNavigationRequest.value = null
                    mainSettingsRequest.value = 0
                    mainDownloadsRequest.value = 0
                    mainHistoryRequest.value = 0
                    mainR18Request.value = 0
                    mainPixivisionRequest.value = 0
                    mainComicRequest.value = 0
                }
            }

            if (authState is AuthState.LoggedIn) {
                // 「我的」「设置」已移入系统应用菜单（AppMenu），此处只负责安装。
                // 首次安装可能赶上应用菜单尚未就绪（mainMenu 为 nil），失败后每秒重试，最多 10 次
                LaunchedEffect(Unit) {
                    repeat(10) {
                        AppMenu.install(
                            // AppMenu 在登出后仍保留在系统菜单栏；回调读取实时登录态，避免
                            // 登出期间的点击成为下次登录后才被消费的悬挂导航请求。
                            onProfile = {
                                if (AppContainer.authState.value is AuthState.LoggedIn) {
                                    mainNavigationRequest.value = MainNavigationTarget.PROFILE
                                }
                            },
                            onSettings = {
                                if (AppContainer.authState.value is AuthState.LoggedIn) {
                                    mainSettingsRequest.value++
                                }
                            },
                        )
                        if (AppMenu.isInstalled) return@LaunchedEffect
                        delay(1_000L)
                    }
                }
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
                            "动态",
                            onClick = { mainNavigationRequest.value = MainNavigationTarget.DYNAMIC },
                            shortcut = KeyShortcut(Key.Five, meta = true),
                        )
                        Item(
                            "浏览记录",
                            onClick = { mainHistoryRequest.value++ },
                            shortcut = KeyShortcut(Key.Six, meta = true),
                        )
                        val showR18 by AppContainer.settingsStore.isShowR18Flow.collectAsState()
                        if (showR18) {
                            Item(
                                "R18 排行",
                                onClick = { mainR18Request.value++ },
                            )
                        }
                        Item(
                            "Pixivision",
                            onClick = { mainPixivisionRequest.value++ },
                        )
                        Item(
                            "Pixiv Comic",
                            onClick = { mainComicRequest.value++ },
                        )
                        Item(
                            "FANBOX（浏览器）",
                            onClick = { openInBrowser("https://www.fanbox.cc/") },
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
                            "下载管理",
                            onClick = { mainDownloadsRequest.value++ },
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
                            val downloadsRequest = mainDownloadsRequest.value
                            LaunchedEffect(downloadsRequest) {
                                if (downloadsRequest > 0) {
                                    if (rootNavigator.lastItem !is DownloadScreen) {
                                        rootNavigator.push(DownloadScreen())
                                    }
                                    mainDownloadsRequest.value = 0
                                }
                            }
                            val historyRequest = mainHistoryRequest.value
                            LaunchedEffect(historyRequest) {
                                if (historyRequest > 0) {
                                    if (rootNavigator.lastItem !is BrowseHistoryScreen) {
                                        rootNavigator.push(BrowseHistoryScreen())
                                    }
                                    mainHistoryRequest.value = 0
                                }
                            }
                            val r18Request = mainR18Request.value
                            LaunchedEffect(r18Request) {
                                if (r18Request > 0) {
                                    if (rootNavigator.lastItem !is R18Screen) {
                                        rootNavigator.push(R18Screen())
                                    }
                                    mainR18Request.value = 0
                                }
                            }
                            val pixivisionRequest = mainPixivisionRequest.value
                            LaunchedEffect(pixivisionRequest) {
                                if (pixivisionRequest > 0) {
                                    if (rootNavigator.lastItem !is PixivisionScreen) {
                                        rootNavigator.push(PixivisionScreen())
                                    }
                                    mainPixivisionRequest.value = 0
                                }
                            }
                            val comicRequest = mainComicRequest.value
                            LaunchedEffect(comicRequest) {
                                if (comicRequest > 0) {
                                    if (rootNavigator.lastItem !is ComicScreen) {
                                        rootNavigator.push(ComicScreen())
                                    }
                                    mainComicRequest.value = 0
                                }
                            }
                            CurrentScreen()
                            // Tab 导航请求（推荐/发现/搜索/我的/动态）由 MainScreen 内部的
                            // LaunchedEffect 消费；但 MainScreen 不在栈顶时（如设置页、详情页
                            // 覆盖其上）该组合不活跃，请求会一直挂着，直到用户手动返回。
                            // 这里先弹回 MainScreen，让其重新组合后消费该请求。
                            // 有意权衡：这会丢弃当前覆盖页（详情页/设置页）的返回栈，按快捷键
                            // 切 Tab 无法用 ESC 回到原页面；换取的是请求永不悬挂。
                            val navRequest = mainNavigationRequest.value
                            LaunchedEffect(navRequest) {
                                if (navRequest != null && rootNavigator.canPop) {
                                    rootNavigator.popUntilRoot()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
