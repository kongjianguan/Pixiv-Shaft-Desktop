package ceui.pixiv.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ceui.loxia.Novel
import ceui.pixiv.di.AppContainer
import kotlin.math.ceil

/** 响应式小说网格：列数跟随 novelFeed 布局设置（排行/动态页共用）。 */
@Composable
fun NovelGrid(
    gridState: LazyStaggeredGridState,
    items: List<Novel>,
    card: @Composable (Novel) -> Unit,
) {
    val maxColumnWidthDp by AppContainer.settingsStore.novelFeedMaxColumnWidthDpFlow.collectAsState()
    val maxColumns by AppContainer.settingsStore.novelFeedMaxColumnsFlow.collectAsState()
    val minColumnWidthDp by AppContainer.settingsStore.novelFeedMinColumnWidthDpFlow.collectAsState()
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val spacing = 10.dp
        val desiredColumns = ceil(
            (maxWidth.value + spacing.value) / (maxColumnWidthDp + spacing.value)
        ).toInt()
        val columnsAllowedByMinimum = (
            (maxWidth.value + spacing.value) / (minColumnWidthDp + spacing.value)
        ).toInt()
        val columns = minOf(desiredColumns, maxColumns, columnsAllowedByMinimum).coerceAtLeast(1)
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(columns),
            state = gridState,
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalItemSpacing = spacing,
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.id }) { novel -> card(novel) }
        }
    }
}
