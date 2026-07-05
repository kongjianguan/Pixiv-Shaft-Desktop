package ceui.pixiv.ui.screen.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.pixiv.ui.component.IllustCard
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.EmptyView
import ceui.pixiv.ui.screen.detail.IllustDetailScreen
import ceui.pixiv.ui.state.UiState

class SearchScreen : Screen {

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { SearchScreenModel() }
        val query by screenModel.query.collectAsState()
        val resultsState by screenModel.resultsState.collectAsState()
        val history by screenModel.history.collectAsState()
        val navigator = LocalNavigator.currentOrThrow

        Column(modifier = Modifier.fillMaxSize()) {
            // Search bar
            OutlinedTextField(
                value = query,
                onValueChange = screenModel::updateQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                placeholder = { Text("Search illusts\u2026") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { screenModel.updateQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true
            )

            // Search history (when no results yet)
            if (resultsState is UiState.Loading && history.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(history) { keyword ->
                        AssistChip(
                            onClick = { screenModel.search(keyword) },
                            label = { Text(keyword) }
                        )
                    }
                }
            }

            // Results
            when (val s = resultsState) {
                is UiState.Loading -> LoadingView()
                is UiState.Error -> ErrorView(s.message, { screenModel.search(query) })
                is UiState.Success -> {
                    if (s.data.isEmpty()) {
                        EmptyView("No results")
                    } else {
                        LazyVerticalStaggeredGrid(
                            columns = StaggeredGridCells.Fixed(2),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalItemSpacing = 4.dp
                        ) {
                            items(s.data, key = { it.id }) { illust ->
                                IllustCard(
                                    illust = illust,
                                    onClick = { id -> navigator.push(IllustDetailScreen(id)) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
