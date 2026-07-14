package ceui.pixiv.ui.screen.recommend

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.pixiv.ui.component.EmptyView
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.IllustCard
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.navigation.LocalScrollToTop
import ceui.pixiv.ui.screen.detail.IllustDetailScreen
import ceui.pixiv.ui.state.UiState

class RecommendScreen : Screen {

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { RecommendScreenModel() }
        val state by screenModel.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow

        Box(modifier = Modifier.fillMaxSize()) {
            when (val s = state) {
                is UiState.Loading -> LoadingView()
                is UiState.Error -> ErrorView(s.message, { screenModel.refresh() })
                is UiState.Success -> {
                    if (s.data.isEmpty()) {
                        EmptyView("No recommendations")
                    } else {
                        IllustGrid(
                            illusts = s.data,
                            onIllustClick = { id -> navigator.push(IllustDetailScreen(id)) },
                            onLoadMore = screenModel::loadMore,
                            onRefresh = screenModel::refresh
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun IllustGrid(
    illusts: List<ceui.loxia.Illust>,
    onIllustClick: (Long) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit = {}
) {
    val gridState = rememberLazyStaggeredGridState()

    // Tab re-click scroll-to-top + refresh
    val scrollToTopValue = LocalScrollToTop.current.value
    LaunchedEffect(scrollToTopValue) {
        if (scrollToTopValue > 0) {
            gridState.scrollToItem(0)
            onRefresh()
        }
    }

    // Infinite scroll: derivedStateOf avoids relaunching LaunchedEffect on every
    // resize/layout event (only fires when shouldLoadMore actually toggles).
    val shouldLoadMore by remember {
        derivedStateOf {
            val visibleItems = gridState.layoutInfo.visibleItemsInfo
            val lastVisible = visibleItems.lastOrNull()?.index ?: 0
            lastVisible >= illusts.size - 5
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        state = gridState,
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalItemSpacing = 4.dp,
        modifier = Modifier.fillMaxSize()
    ) {
        items(illusts, key = { it.id }) { illust ->
            IllustCard(illust = illust, onClick = onIllustClick)
        }
    }
}
