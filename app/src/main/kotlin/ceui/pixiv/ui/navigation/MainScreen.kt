package ceui.pixiv.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.CurrentTab
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import ceui.pixiv.ui.screen.discover.DiscoverScreen
import ceui.pixiv.ui.screen.profile.ProfileScreen
import ceui.pixiv.ui.screen.recommend.RecommendScreen
import ceui.pixiv.ui.screen.search.SearchScreen

val LocalScrollToTop = compositionLocalOf { mutableStateOf(0) }
val LocalEscapeOverlayHandler = compositionLocalOf<MutableState<(() -> Boolean)?>> {
    mutableStateOf(null)
}

@Composable
private fun EscBackNavigator(
    navigator: cafe.adriel.voyager.navigator.Navigator,
    content: @Composable () -> Unit
) {
    // Observe the global ESC counter (set by AWT KeyEventDispatcher in Main.kt) and pop
    // when there's something to go back to. This bypasses Compose's focus-based key
    // dispatch, which doesn't fire on non-focusable containers like Box/Scaffold.
    val escapeOverlayHandler = remember { mutableStateOf<(() -> Boolean)?>(null) }
    CompositionLocalProvider(LocalEscapeOverlayHandler provides escapeOverlayHandler) {
        LaunchedEffect(Unit) {
            androidx.compose.runtime.snapshotFlow { ceui.pixiv.globalEscCounter.value }
                .drop(1)
                .collect {
                    // Single decision point — no race. Fullscreen takes priority.
                    if (ceui.pixiv.fullscreenImageActive.value) {
                        ceui.pixiv.fullscreenImageActive.value = false
                    } else if (escapeOverlayHandler.value?.invoke() != true && navigator.canPop) {
                        navigator.pop()
                    }
                }
        }
        content()
    }
}

class MainScreen : Screen {

    @Composable
    override fun Content() {
        val scrollToTopState = remember { mutableStateOf(0) }
        CompositionLocalProvider(LocalScrollToTop provides scrollToTopState) {
            TabNavigator(RecommendTab) {
                val tabNavigator = LocalTabNavigator.current

                fun selectTab(tab: Tab) {
                    if (tabNavigator.current.key == tab.key) {
                        scrollToTopState.value++
                    } else {
                        scrollToTopState.value = 0
                        tabNavigator.current = tab
                    }
                }

                val navigationRequest = ceui.pixiv.mainNavigationRequest.value
                LaunchedEffect(navigationRequest) {
                    when (navigationRequest) {
                        ceui.pixiv.MainNavigationTarget.RECOMMEND -> selectTab(RecommendTab)
                        ceui.pixiv.MainNavigationTarget.DISCOVER -> selectTab(DiscoverTab)
                        ceui.pixiv.MainNavigationTarget.SEARCH -> selectTab(SearchTab)
                        ceui.pixiv.MainNavigationTarget.PROFILE -> selectTab(ProfileTab)
                        ceui.pixiv.MainNavigationTarget.HISTORY -> Unit
                        null -> Unit
                    }
                    if (navigationRequest != null) {
                        ceui.pixiv.mainNavigationRequest.value = null
                    }
                }

                val refreshRequest = ceui.pixiv.mainRefreshRequest.value
                LaunchedEffect(refreshRequest) {
                    if (refreshRequest > 0) {
                        scrollToTopState.value++
                    }
                }

                CurrentTab()
            }
        }
    }
}

object RecommendTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(index = 0u, title = "推荐", icon = rememberVectorPainter(Icons.Default.Home))

    @Composable
    override fun Content() {
        Navigator(RecommendScreen()) { nav -> EscBackNavigator(nav) { CurrentScreen() } }
    }
}

object DiscoverTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(index = 1u, title = "发现", icon = rememberVectorPainter(Icons.Default.Star))

    @Composable
    override fun Content() {
        Navigator(DiscoverScreen()) { nav -> EscBackNavigator(nav) { CurrentScreen() } }
    }
}

object SearchTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(index = 2u, title = "搜索", icon = rememberVectorPainter(Icons.Default.Search))

    @Composable
    override fun Content() {
        Navigator(SearchScreen()) { nav -> EscBackNavigator(nav) { CurrentScreen() } }
    }
}

object ProfileTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(index = 3u, title = "我的", icon = rememberVectorPainter(Icons.Default.Person))

    @Composable
    override fun Content() {
        Navigator(ProfileScreen()) { nav -> EscBackNavigator(nav) { CurrentScreen() } }
    }
}
