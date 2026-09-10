package ceui.pixiv.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerMoveFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import ceui.pixiv.platform.TrackpadGestureBridge
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val TRACKPAD_SCROLL_MULTIPLIER = 1f
private const val TRACKPAD_GESTURE_IDLE_MS = 120L
private const val TRACKPAD_HORIZONTAL_RATIO = 0.55f
private const val TRACKPAD_AXIS_DECISION_DISTANCE = 2f
private const val FAST_SWIPE_VELOCITY = 1200f
private const val FAST_SWIPE_MIN_DISTANCE = 0.06f

private enum class ScrollIntent { UNDECIDED, HORIZONTAL, VERTICAL }

/**
 * 推荐页和「我的」共用的作品流分页容器：横向 Pager（分页容器）、触控板切换和
 * 鼠标靠近顶部后显示的悬浮 tab 栏都在这里维护，页面只负责提供每一页的内容。
 * [content] 的第二个参数表示当前页，回顶事件应只由当前页消费。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FeedPager(
    pageLabels: List<String>,
    modifier: Modifier = Modifier.fillMaxSize(),
    content: @Composable (page: Int, isCurrentPage: Boolean) -> Unit,
) {
    require(pageLabels.isNotEmpty()) { "FeedPager requires at least one page" }

    val pagerState = rememberPagerState(pageCount = { pageLabels.size })
    val scope = rememberCoroutineScope()
    var pagerWidth by remember { mutableFloatStateOf(0f) }
    val scrollHandlerToken = remember { Any() }
    // Desktop users need to see the content modes without discovering a hidden hover zone.
    var isTabBarVisible by remember { mutableStateOf(true) }
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
            if (!isPointerInsideTabBar) isTabBarVisible = false
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
            if (rawDelta != 0f) scope.launch { pagerState.dispatchRawDelta(rawDelta) }
        }

        fun settleHorizontalGesture() {
            val threshold = pagerWidth * 0.12f
            val distanceSwitch = abs(accumulatedX) >= threshold
            val velocitySwitch =
                abs(accumulatedX) >= pagerWidth * FAST_SWIPE_MIN_DISTANCE &&
                    abs(peakHorizontalVelocity) >= FAST_SWIPE_VELOCITY &&
                    accumulatedX * peakHorizontalVelocity > 0f
            val direction = when {
                velocitySwitch -> peakHorizontalVelocity
                distanceSwitch -> accumulatedX
                else -> 0f
            }
            val lastPage = pageLabels.lastIndex
            val target = when {
                direction > 0f -> (startPage + 1).coerceAtMost(lastPage)
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
                        if (intent == ScrollIntent.HORIZONTAL) applyHorizontalDelta(decisionX)
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

    Box(modifier = modifier) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = true,
            modifier = Modifier.fillMaxSize().onSizeChanged { pagerWidth = it.width.toFloat() },
        ) { page ->
            content(page, pagerState.currentPage == page)
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
                        if (!isPointerInsideTabBar) scheduleTabBarHide()
                        false
                    },
                ),
        )

        AnimatedVisibility(
            visible = isTabBarVisible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
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
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    pageLabels.forEachIndexed { index, label ->
                        val selected = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .clickable {
                                    showTabBar()
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                }
                                .background(
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    shape = RoundedCornerShape(14.dp),
                                )
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = label,
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
