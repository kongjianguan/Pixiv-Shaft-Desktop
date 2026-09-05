package ceui.pixiv.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DynamicFeed
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import ceui.pixiv.ui.screen.dynamic.DynamicScreen
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
        TabNavigator(RecommendTab) {
            val tabNavigator = LocalTabNavigator.current
            // Keep refresh/reselection events local to the active top-level tab.
            // A shared counter would be replayed when another tab is composed.
            val scrollToTopState = remember(tabNavigator.current.key) { mutableStateOf(0) }

            CompositionLocalProvider(LocalScrollToTop provides scrollToTopState) {

                fun selectTab(tab: Tab) {
                    if (tabNavigator.current.key == tab.key) {
                        scrollToTopState.value++
                    } else {
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
                        ceui.pixiv.MainNavigationTarget.DYNAMIC -> selectTab(DynamicTab)
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
                        ceui.pixiv.mainRefreshRequest.value = 0
                    }
                }

                Row(modifier = Modifier.fillMaxSize()) {
                    MainSidebar(selected = tabNavigator.current, onSelect = ::selectTab)
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        CurrentTab()
                    }
                }
            }
        }
    }
}

@Composable
private fun MainSidebar(selected: Tab, onSelect: (Tab) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxHeight().width(224.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(modifier = Modifier.size(34.dp), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primary) {}
                Column(modifier = Modifier.padding(start = 10.dp)) {
                    Text("Pixiv Shaft", style = MaterialTheme.typography.titleMedium)
                    Text("发现你的下一幅作品", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("浏览", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SidebarItem(RecommendTab, "推荐", Icons.Default.Home, selected, onSelect)
            SidebarItem(DiscoverTab, "发现", Icons.Default.Star, selected, onSelect)
            SidebarItem(SearchTab, "搜索", Icons.Default.Search, selected, onSelect)
            SidebarItem(DynamicTab, "动态", Icons.Default.DynamicFeed, selected, onSelect)
            SidebarItem(ProfileTab, "我的", Icons.Default.Person, selected, onSelect)
            Spacer(Modifier.weight(1f))
            HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
            Text("⌘ 1–5 切换页面 · 重复点击回到顶部", modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SidebarItem(tab: Tab, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Tab, onSelect: (Tab) -> Unit) {
    NavigationRailItem(selected = selected.key == tab.key, onClick = { onSelect(tab) }, icon = { Icon(icon, contentDescription = label) }, label = { Text(label) }, alwaysShowLabel = true, modifier = Modifier.fillMaxWidth().height(48.dp))
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

object DynamicTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(index = 4u, title = "动态", icon = rememberVectorPainter(Icons.Default.DynamicFeed))

    @Composable
    override fun Content() {
        Navigator(DynamicScreen()) { nav -> EscBackNavigator(nav) { CurrentScreen() } }
    }
}
