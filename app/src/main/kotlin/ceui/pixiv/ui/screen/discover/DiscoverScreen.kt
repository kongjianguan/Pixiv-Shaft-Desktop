package ceui.pixiv.ui.screen.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import ceui.loxia.TrendingTag
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.IllustCard
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.screen.detail.IllustDetailScreen
import ceui.pixiv.ui.state.UiState

class DiscoverScreen : Screen {

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { DiscoverScreenModel() }
        val tagsState by screenModel.tagsState.collectAsState()
        val rankingState by screenModel.rankingState.collectAsState()
        val currentMode by screenModel.currentMode.collectAsState()
        val navigator = LocalNavigator.currentOrThrow

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // Trending tags
            item {
                Text(
                    text = "Trending Tags",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp, 8.dp)
                )
            }
            item {
                when (val s = tagsState) {
                    is UiState.Loading -> LoadingView(modifier = Modifier.height(120.dp))
                    is UiState.Error -> ErrorView(s.message, {}, Modifier.height(120.dp))
                    is UiState.Success -> {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(s.data) { tag ->
                                TrendingTagItem(tag)
                            }
                        }
                    }
                }
            }

            // Ranking mode selector
            item {
                Row(
                    modifier = Modifier.padding(16.dp, 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("day" to "Daily", "week" to "Weekly", "month" to "Monthly").forEach { (mode, label) ->
                        FilterChip(
                            selected = (currentMode == mode),
                            onClick = { screenModel.loadRanking(mode) },
                            label = { Text(label) }
                        )
                    }
                }
            }

            // Ranking illusts
            item {
                Text(
                    text = "Ranking",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp, 8.dp)
                )
            }
            when (val s = rankingState) {
                is UiState.Loading -> item { LoadingView(modifier = Modifier.height(200.dp)) }
                is UiState.Error -> item { ErrorView(s.message, {}, Modifier.height(200.dp)) }
                is UiState.Success -> {
                    val columns = 2
                    val rows = (s.data.size + columns - 1) / columns
                    items(rows) { rowIndex ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            for (col in 0 until columns) {
                                val index = rowIndex * columns + col
                                if (index < s.data.size) {
                                    IllustCard(
                                        illust = s.data[index],
                                        onClick = { id -> navigator.push(IllustDetailScreen(id)) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
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
