package ceui.pixiv.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ceui.pixiv.di.AppContainer
import kotlin.math.ceil

@Composable
fun WorkFeedGrid(
    state: LazyStaggeredGridState,
    modifier: Modifier = Modifier.fillMaxSize(),
    contentPadding: PaddingValues = PaddingValues(4.dp),
    spacing: Dp = 4.dp,
    content: LazyStaggeredGridScope.(viewportWidth: Dp, viewportHeight: Dp) -> Unit,
) {
    val maxColumnWidthDp by AppContainer.settingsStore.workFeedMaxColumnWidthDpFlow.collectAsState()
    val maxColumns by AppContainer.settingsStore.workFeedMaxColumnsFlow.collectAsState()
    val minColumnWidthDp by AppContainer.settingsStore.workFeedMinColumnWidthDpFlow.collectAsState()

    BoxWithConstraints(modifier = modifier) {
        val columns = calculateWorkFeedColumns(
            viewportWidth = maxWidth,
            maxColumnWidthDp = maxColumnWidthDp,
            maxColumns = maxColumns,
            minColumnWidthDp = minColumnWidthDp,
            spacing = spacing,
        )
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(columns),
            state = state,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalItemSpacing = spacing,
            modifier = Modifier.fillMaxSize(),
        ) {
            content(maxWidth, maxHeight)
        }
    }
}

internal fun calculateWorkFeedColumns(
    viewportWidth: Dp,
    maxColumnWidthDp: Int,
    maxColumns: Int,
    minColumnWidthDp: Int,
    spacing: Dp,
): Int {
    val desiredColumns = ceil(
        (viewportWidth.value + spacing.value) / (maxColumnWidthDp + spacing.value)
    ).toInt()
    val columnsAllowedByMinimum = (
        (viewportWidth.value + spacing.value) / (minColumnWidthDp + spacing.value)
    ).toInt()
    return minOf(desiredColumns, maxColumns, columnsAllowedByMinimum).coerceAtLeast(1)
}
