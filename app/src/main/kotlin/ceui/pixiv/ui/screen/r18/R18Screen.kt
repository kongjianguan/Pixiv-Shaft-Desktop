package ceui.pixiv.ui.screen.r18

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.component.RankingFeed
import kotlinx.coroutines.launch

/** R18 排行各 mode 定义。 */
enum class R18RankingMode(val apiMode: String, val label: String) {
    DAY_R18("day_r18", "今日R18"),
    WEEK_R18("week_r18", "本周R18"),
    DAY_MALE_R18("day_male_r18", "男性向"),
    DAY_FEMALE_R18("day_female_r18", "女性向"),
    DAY_R18_AI("day_r18_ai", "AI"),
}

class R18Screen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val modes = R18RankingMode.entries
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val pagerState = rememberPagerState(pageCount = { modes.size })

        // R18 全局开关关闭时本页必须立刻退出：开关未开启前用户不应看到任何 R18 相关界面。
        // 场景：在本页时经 AppMenu「设置」/快捷键进入设置页关闭开关，返回后本页仍在栈中。
        val showR18 by AppContainer.settingsStore.isShowR18Flow.collectAsState()
        LaunchedEffect(showR18) {
            if (!showR18) navigator.pop()
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("R18 排行") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                TabRow(selectedTabIndex = pagerState.currentPage) {
                    modes.forEachIndexed { index, mode ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = { Text(mode.label) },
                        )
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val mode = modes[page].apiMode
                    // key 保证每页按各自 mode 独立加载和翻页
                    key(mode) {
                        RankingFeed(mode = mode)
                    }
                }
            }
        }
    }
}
