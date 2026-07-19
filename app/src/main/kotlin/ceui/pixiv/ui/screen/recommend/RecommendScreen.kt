package ceui.pixiv.ui.screen.recommend

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.Surface
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerMoveFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.pixiv.di.AppContainer
import ceui.pixiv.platform.TrackpadGestureBridge
import ceui.pixiv.ui.component.EmptyView
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.IllustCard
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.component.NovelCard
import ceui.pixiv.ui.component.WorkFeedGrid
import ceui.pixiv.ui.navigation.LocalScrollToTop
import ceui.pixiv.ui.screen.detail.IllustDetailScreen
import ceui.pixiv.ui.screen.novel.NovelDetailScreen
import ceui.pixiv.ui.screen.novel.NovelSeriesScreen
import ceui.pixiv.ui.screen.user.UserDetailScreen
import ceui.pixiv.ui.state.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil

private const val TRACKPAD_SCROLL_MULTIPLIER = 1f
private const val TRACKPAD_GESTURE_IDLE_MS = 120L
private const val TRACKPAD_HORIZONTAL_RATIO = 0.55f
private const val TRACKPAD_AXIS_DECISION_DISTANCE = 2f
private const val RECOMMEND_FAST_SWIPE_VELOCITY = 1200f
private const val RECOMMEND_FAST_SWIPE_MIN_DISTANCE = 0.06f

private enum class ScrollIntent { UNDECIDED, HORIZONTAL, VERTICAL }

class RecommendScreen : Screen {

    @OptIn(ExperimentalComposeUiApi::class, ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { RecommendScreenModel() }
        val pagerState = rememberPagerState(pageCount = { 4 })
        val scope = rememberCoroutineScope()
        var pagerWidth by remember { mutableFloatStateOf(0f) }

        val illustState by screenModel.illustState.collectAsState()
        val mangaState by screenModel.mangaState.collectAsState()
        val novelState by screenModel.novelState.collectAsState()
        val walkState by screenModel.walkState.collectAsState()

        val illustRefreshing by screenModel.illustRefreshing.collectAsState()
        val mangaRefreshing by screenModel.mangaRefreshing.collectAsState()
        val novelRefreshing by screenModel.novelRefreshing.collectAsState()
        val walkRefreshing by screenModel.walkRefreshing.collectAsState()

        val navigator = LocalNavigator.currentOrThrow
        val scrollHandlerToken = remember { Any() }
        var isTabBarVisible by remember { mutableStateOf(false) }
        var isPointerInsideTabBar by remember { mutableStateOf(false) }
        var tabBarHideJob by remember { mutableStateOf<Job?>(null) }

        fun showTabBar() {
            tabBarHideJob?.cancel()
            isTabBarVisible = true
        }

        fun scheduleTabBarHide() {
            tabBarHideJob?.cancel()
            tabBarHideJob = scope.launch {
                delay(100L)
                if (!isPointerInsideTabBar) {
                    isTabBarVisible = false
                }
            }
        }

        DisposableEffect(Unit) {
            onDispose { tabBarHideJob?.cancel() }
        }

        DisposableEffect(scrollHandlerToken, pagerState, pagerWidth) {
            TrackpadGestureBridge.install()

            var intent = ScrollIntent.UNDECIDED
            var physicalGestureActive = false
            var ignoreMomentum = false
            var decisionX = 0f
            var decisionY = 0f
            var accumulatedX = 0f
            var appliedX = 0f
            var lastEventTimeNanos = 0L
            var peakHorizontalVelocity = 0f
            var startPage = pagerState.settledPage
            var idleJob: Job? = null
            val gestureLock = Any()

            fun resetPhysicalGesture() {
                intent = ScrollIntent.UNDECIDED
                physicalGestureActive = false
                decisionX = 0f
                decisionY = 0f
                accumulatedX = 0f
                appliedX = 0f
                lastEventTimeNanos = 0L
                peakHorizontalVelocity = 0f
                idleJob?.cancel()
                idleJob = null
            }

            fun applyHorizontalDelta(deltaX: Float) {
                accumulatedX += deltaX
                val clamped = accumulatedX.coerceIn(-pagerWidth, pagerWidth)
                val rawDelta = clamped - appliedX
                appliedX = clamped
                if (rawDelta != 0f) {
                    scope.launch { pagerState.dispatchRawDelta(rawDelta) }
                }
            }

            fun settleHorizontalGesture() {
                val threshold = pagerWidth * 0.12f
                val distanceSwitch = abs(accumulatedX) >= threshold
                val velocitySwitch =
                    abs(accumulatedX) >= pagerWidth * RECOMMEND_FAST_SWIPE_MIN_DISTANCE &&
                        abs(peakHorizontalVelocity) >= RECOMMEND_FAST_SWIPE_VELOCITY &&
                        accumulatedX * peakHorizontalVelocity > 0f
                val direction = when {
                    velocitySwitch -> peakHorizontalVelocity
                    distanceSwitch -> accumulatedX
                    else -> 0f
                }
                val target = when {
                    direction > 0f -> (startPage + 1).coerceAtMost(3)
                    direction < 0f -> (startPage - 1).coerceAtLeast(0)
                    else -> startPage
                }
                scope.launch { pagerState.animateScrollToPage(target) }
                resetPhysicalGesture()
            }

            val handler: (TrackpadGestureBridge.ScrollEvent) -> Boolean = handler@ { event ->
                synchronized(gestureLock) {
                    val phase = TrackpadGestureBridge.Phase

                    if (event.momentumPhase != phase.NONE) {
                        val consume = ignoreMomentum
                        if (phase.isFinished(event.momentumPhase)) ignoreMomentum = false
                        return@handler consume
                    }

                    if (!physicalGestureActive || phase.has(event.phase, phase.BEGAN)) {
                        physicalGestureActive = true
                        ignoreMomentum = false
                        intent = ScrollIntent.UNDECIDED
                        decisionX = 0f
                        decisionY = 0f
                        accumulatedX = 0f
                        appliedX = 0f
                        lastEventTimeNanos = 0L
                        peakHorizontalVelocity = 0f
                        startPage = pagerState.settledPage
                    }

                    // NSEvent reports content-scroll direction, opposite to the finger motion.
                    val deltaX = -event.deltaX.toFloat() * TRACKPAD_SCROLL_MULTIPLIER
                    val deltaY = event.deltaY.toFloat() * TRACKPAD_SCROLL_MULTIPLIER
                    val nowNanos = System.nanoTime()
                    if (lastEventTimeNanos != 0L) {
                        val elapsedSeconds = (nowNanos - lastEventTimeNanos)
                            .coerceAtLeast(1L) / 1_000_000_000f
                        val instantaneousVelocity = deltaX / elapsedSeconds
                        if (abs(instantaneousVelocity) > abs(peakHorizontalVelocity)) {
                            peakHorizontalVelocity = instantaneousVelocity
                        }
                    }
                    lastEventTimeNanos = nowNanos

                    if (intent == ScrollIntent.UNDECIDED) {
                        decisionX += deltaX
                        decisionY += deltaY
                        if (abs(decisionX) + abs(decisionY) >= TRACKPAD_AXIS_DECISION_DISTANCE) {
                            intent = if (abs(decisionX) >= abs(decisionY) * TRACKPAD_HORIZONTAL_RATIO) {
                                ScrollIntent.HORIZONTAL
                            } else {
                                ScrollIntent.VERTICAL
                            }
                            if (intent == ScrollIntent.HORIZONTAL) {
                                applyHorizontalDelta(decisionX)
                            }
                        }
                    } else if (intent == ScrollIntent.HORIZONTAL) {
                        applyHorizontalDelta(deltaX)
                    }

                    if (intent == ScrollIntent.VERTICAL) {
                        if (phase.isFinished(event.phase)) resetPhysicalGesture()
                        return@handler false
                    }

                    if (intent != ScrollIntent.HORIZONTAL) return@handler false

                    idleJob?.cancel()
                    if (phase.isFinished(event.phase)) {
                        ignoreMomentum = true
                        settleHorizontalGesture()
                    } else if (event.phase == phase.NONE) {
                        idleJob = scope.launch {
                            delay(TRACKPAD_GESTURE_IDLE_MS)
                            synchronized(gestureLock) {
                                ignoreMomentum = true
                                settleHorizontalGesture()
                            }
                        }
                    }
                    true
                }
            }

            TrackpadGestureBridge.setScrollHandler(scrollHandlerToken, handler)
            onDispose {
                synchronized(gestureLock) { idleJob?.cancel() }
                TrackpadGestureBridge.setScrollHandler(scrollHandlerToken, null)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = true,
                modifier = Modifier.fillMaxSize().onSizeChanged { pagerWidth = it.width.toFloat() }
            ) { page ->
                    when (page) {
                        0 -> IllustTabContent(
                            state = illustState, isRefreshing = illustRefreshing,
                            onRefresh = screenModel::refreshIllust,
                            onLoadMore = screenModel::loadMoreIllust,
                            onIllustClick = { id -> navigator.push(IllustDetailScreen(id)) }
                        )
                        1 -> IllustTabContent(
                            state = mangaState, isRefreshing = mangaRefreshing,
                            onRefresh = screenModel::refreshManga,
                            onLoadMore = screenModel::loadMoreManga,
                            onIllustClick = { id -> navigator.push(IllustDetailScreen(id)) }
                        )
                        2 -> NovelTabContent(
                            state = novelState, isRefreshing = novelRefreshing,
                            onRefresh = screenModel::refreshNovel,
                            onLoadMore = screenModel::loadMoreNovel,
                            onNovelClick = { id -> navigator.push(NovelDetailScreen(id)) },
                            onUserClick = { id -> navigator.push(UserDetailScreen(id)) },
                            onSeriesClick = { id -> navigator.push(NovelSeriesScreen(id)) },
                            onToggleBookmark = screenModel::toggleNovelBookmark
                        )
                        3 -> IllustTabContent(
                            state = walkState, isRefreshing = walkRefreshing,
                            onRefresh = screenModel::refreshWalk,
                            onLoadMore = screenModel::loadMoreWalk,
                            onIllustClick = { id -> navigator.push(IllustDetailScreen(id)) }
                        )
                    }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(20.dp)
                    .pointerMoveFilter(
                        onEnter = {
                            showTabBar()
                            false
                        },
                        onMove = {
                            showTabBar()
                            false
                        },
                        onExit = {
                            if (!isPointerInsideTabBar) {
                                scheduleTabBarHide()
                            }
                            false
                        },
                    ),
            )

            AnimatedVisibility(
                visible = isTabBarVisible,
                enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
            ) {
                Surface(
                    modifier = Modifier.pointerMoveFilter(
                        onEnter = {
                            isPointerInsideTabBar = true
                            showTabBar()
                            false
                        },
                        onMove = {
                            showTabBar()
                            false
                        },
                        onExit = {
                            isPointerInsideTabBar = false
                            scheduleTabBarHide()
                            false
                        },
                    ),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    tonalElevation = 4.dp,
                    shadowElevation = 8.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        RecommendPage.entries.forEachIndexed { index, page ->
                            val selected = pagerState.currentPage == index
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else Color.Transparent,
                                    )
                                    .clickable {
                                        showTabBar()
                                        scope.launch { pagerState.animateScrollToPage(index) }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = page.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ----- Illust Grid (shared by 推荐/漫画/最新) -----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IllustTabContent(
    state: UiState<List<Illust>>, isRefreshing: Boolean, onRefresh: () -> Unit,
    onLoadMore: () -> Unit, onIllustClick: (Long) -> Unit
) {
    val gridState = rememberLazyStaggeredGridState()
    val scrollToTopValue = LocalScrollToTop.current.value
    LaunchedEffect(scrollToTopValue) {
        if (scrollToTopValue > 0) { gridState.scrollToItem(0); onRefresh() }
    }
    val shouldLoadMore by remember {
        derivedStateOf {
            val items = (state as? UiState.Success)?.data ?: return@derivedStateOf false
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= items.size - 5 && items.isNotEmpty()
        }
    }
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) onLoadMore() }
    PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        when (state) {
            is UiState.Loading -> LoadingView()
            is UiState.Error -> ErrorView(state.message, onRefresh)
            is UiState.Success -> if (state.data.isEmpty()) EmptyView("No works")
            else WorkFeedGrid(state = gridState) { _, _ ->
                items(state.data, key = { it.id }) { illust ->
                    IllustCard(illust = illust, onClick = onIllustClick)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NovelTabContent(
    state: UiState<List<Novel>>, isRefreshing: Boolean, onRefresh: () -> Unit,
    onLoadMore: () -> Unit, onNovelClick: (Long) -> Unit,
    onUserClick: (Long) -> Unit, onSeriesClick: (Long) -> Unit,
    onToggleBookmark: (Novel) -> Unit,
) {
    val gridState = rememberLazyStaggeredGridState()
    val maxColumnWidthDp by AppContainer.settingsStore.novelFeedMaxColumnWidthDpFlow.collectAsState()
    val maxColumns by AppContainer.settingsStore.novelFeedMaxColumnsFlow.collectAsState()
    val minColumnWidthDp by AppContainer.settingsStore.novelFeedMinColumnWidthDpFlow.collectAsState()
    val scrollToTopValue = LocalScrollToTop.current.value
    LaunchedEffect(scrollToTopValue) {
        if (scrollToTopValue > 0) { gridState.scrollToItem(0); onRefresh() }
    }
    val shouldLoadMore by remember {
        derivedStateOf {
            val items = (state as? UiState.Success)?.data ?: return@derivedStateOf false
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= items.size - 5 && items.isNotEmpty()
        }
    }
    LaunchedEffect(shouldLoadMore) { if (shouldLoadMore) onLoadMore() }
    PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        when (state) {
            is UiState.Loading -> LoadingView()
            is UiState.Error -> ErrorView(state.message, onRefresh)
            is UiState.Success -> if (state.data.isEmpty()) EmptyView("No novels")
            else BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val spacing = 10.dp
                val maxColumnWidth = maxColumnWidthDp.dp
                val desiredColumns = ceil(
                    (maxWidth.value + spacing.value) / (maxColumnWidth.value + spacing.value)
                ).toInt()
                val columnsAllowedByMinimum = (
                    (maxWidth.value + spacing.value) / (minColumnWidthDp.dp.value + spacing.value)
                ).toInt()
                val columns = minOf(desiredColumns, maxColumns, columnsAllowedByMinimum).coerceAtLeast(1)
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(columns),
                    state = gridState,
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                    verticalItemSpacing = spacing,
                    modifier = Modifier.fillMaxSize(),
                ) { items(state.data, key = { it.id }) { novel ->
                    NovelCard(
                        novel = novel,
                        onClick = onNovelClick,
                        onUserClick = onUserClick,
                        onSeriesClick = onSeriesClick,
                        onToggleBookmark = onToggleBookmark,
                    )
                }}
            }
        }
    }
}
