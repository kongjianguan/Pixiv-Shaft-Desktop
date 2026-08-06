package ceui.pixiv.ui.screen.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import coil3.compose.AsyncImage
import cafe.adriel.voyager.core.screen.Screen
import ceui.loxia.TrendingTag
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.component.RankingFeed
import ceui.pixiv.ui.navigation.LocalScrollToTop
import ceui.pixiv.ui.state.UiState

// 排行 mode 列表：day/week/month + 分类/R18 模式，标签保持英文短风格
private val DISCOVER_RANKING_MODES = listOf(
    "day" to "Daily",
    "week" to "Weekly",
    "month" to "Monthly",
    "day_male" to "Male",
    "day_female" to "Female",
    "week_original" to "Original",
    "week_rookie" to "Rookie",
    "day_r18" to "R18 Daily",
    "week_r18" to "R18 Weekly",
)

class DiscoverScreen : Screen {

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { DiscoverScreenModel() }
        val tagsState by screenModel.tagsState.collectAsState()
        val currentMode by screenModel.currentMode.collectAsState()

        val scrollToTopState = LocalScrollToTop.current
        val scrollToTopValue = scrollToTopState.value
        LaunchedEffect(scrollToTopValue) {
            if (scrollToTopValue > 0) {
                screenModel.refresh()
                scrollToTopState.value = 0
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Trending tags
            Text(
                text = "Trending Tags",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(16.dp, 8.dp)
            )
            when (val s = tagsState) {
                is UiState.Loading -> LoadingView(modifier = Modifier.fillMaxWidth().height(120.dp))
                is UiState.Error -> ErrorView(s.message, { screenModel.refresh() }, Modifier.fillMaxWidth().height(120.dp))
                is UiState.Success -> LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(s.data) { tag ->
                        TrendingTagItem(tag)
                    }
                }
            }

            // Ranking mode selector
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(DISCOVER_RANKING_MODES) { (mode, label) ->
                    FilterChip(
                        selected = (currentMode == mode),
                        onClick = { screenModel.selectMode(mode) },
                        label = { Text(label) }
                    )
                }
            }

            // Ranking feed —— 切换 mode 时用 key 重建，保证滚动位置和数据都切到新 mode
            key(currentMode) {
                RankingFeed(mode = currentMode, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TrendingTagItem(tag: TrendingTag) {
    Column(modifier = Modifier.width(120.dp)) {
        AsyncImage(
            model = tag.illust?.image_urls?.medium,
            contentDescription = tag.tag,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            contentScale = ContentScale.Crop
        )
        Text(
            text = "#${tag.tag}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
