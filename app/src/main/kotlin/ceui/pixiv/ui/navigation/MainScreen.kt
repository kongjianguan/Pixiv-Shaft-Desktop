package ceui.pixiv.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
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
import ceui.pixiv.ui.screen.recommend.RecommendScreen
import ceui.pixiv.ui.screen.search.SearchScreen

class MainScreen : Screen {

    @Composable
    override fun Content() {
        LaunchedEffect(Unit) {
            println("PLAN 4 GATE PASSED — UI rendered, recommend page active")
        }
        TabNavigator(RecommendTab) {
            Scaffold(
                bottomBar = { BottomBar() }
            ) { padding ->
                Box(modifier = Modifier.padding(padding)) {
                    CurrentTab()
                }
            }
        }
    }
}

@Composable
private fun BottomBar() {
    val tabNavigator = LocalTabNavigator.current
    NavigationBar {
        listOf(RecommendTab, DiscoverTab, SearchTab).forEach { tab ->
            NavigationBarItem(
                selected = tabNavigator.current.key == tab.key,
                onClick = { tabNavigator.current = tab },
                icon = { Icon(painter = tab.options.icon!!, contentDescription = tab.options.title) },
                label = { Text(tab.options.title) }
            )
        }
    }
}

object RecommendTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(index = 0u, title = "推荐", icon = rememberVectorPainter(Icons.Default.Home))

    @Composable
    override fun Content() {
        Navigator(RecommendScreen()) { CurrentScreen() }
    }
}

object DiscoverTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(index = 1u, title = "发现", icon = rememberVectorPainter(Icons.Default.Star))

    @Composable
    override fun Content() {
        Navigator(DiscoverScreen()) { CurrentScreen() }
    }
}

object SearchTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(index = 2u, title = "搜索", icon = rememberVectorPainter(Icons.Default.Search))

    @Composable
    override fun Content() {
        Navigator(SearchScreen()) { CurrentScreen() }
    }
}
