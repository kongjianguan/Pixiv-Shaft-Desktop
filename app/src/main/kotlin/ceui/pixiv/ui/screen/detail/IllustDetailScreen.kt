package ceui.pixiv.ui.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.pager.PagerState
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import cafe.adriel.voyager.core.screen.Screen
import ceui.loxia.Illust
import ceui.loxia.UgoiraMetaData
import ceui.pixiv.platform.TrackpadGestureBridge
import ceui.pixiv.ui.component.CaptionText
import ceui.pixiv.ui.component.EmptyView
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.UgoiraPlayer
import ceui.pixiv.ui.component.IllustCard
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.component.TagChip
import ceui.pixiv.ui.component.UserAvatar
import ceui.pixiv.ui.component.WorkFeedGrid
import ceui.pixiv.ui.component.ZoomableImage
import ceui.pixiv.ui.screen.search.SearchScreen
import ceui.pixiv.ui.screen.user.UserDetailScreen
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.util.openInBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val IMAGE_PAGER_SWITCH_THRESHOLD = 0.35f
private const val IMAGE_PAGER_FAST_SWIPE_VELOCITY = 1200f
private const val IMAGE_PAGER_FAST_SWIPE_MIN_DISTANCE = 0.12f
private const val IMAGE_PAGER_SETTLE_DURATION_MS = 160
private const val IMAGE_TRACKPAD_GESTURE_IDLE_MS = 120L
private const val IMAGE_TRACKPAD_HORIZONTAL_RATIO = 0.55f
private const val IMAGE_TRACKPAD_AXIS_DECISION_DISTANCE = 2f

private enum class ImageScrollIntent { UNDECIDED, HORIZONTAL, VERTICAL }

private fun calculateSinglePageTarget(
    startPage: Int,
    pageCount: Int,
    pageWidth: Float,
    accumulatedScroll: Float,
    releaseVelocity: Float,
): Int {
    val distanceSwitch = abs(accumulatedScroll) >= pageWidth * IMAGE_PAGER_SWITCH_THRESHOLD
    val velocitySwitch =
        abs(accumulatedScroll) >= pageWidth * IMAGE_PAGER_FAST_SWIPE_MIN_DISTANCE &&
            abs(releaseVelocity) >= IMAGE_PAGER_FAST_SWIPE_VELOCITY &&
            accumulatedScroll * releaseVelocity > 0f

    if (!distanceSwitch && !velocitySwitch) return startPage

    val direction = if (velocitySwitch) releaseVelocity else accumulatedScroll
    return if (direction > 0f) {
        (startPage + 1).coerceAtMost(pageCount - 1)
    } else {
        (startPage - 1).coerceAtLeast(0)
    }
}

private class SinglePagePagerController(
    private val pagerState: PagerState,
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private var startPage = 0
    private var accumulatedScroll = 0f
    private var appliedScroll = 0f
    private var active = false
    private var settling = false
    private var settleJob: Job? = null

    fun begin(): Boolean = synchronized(lock) {
        if (settling) return false
        if (!active) {
            startPage = pagerState.settledPage
            accumulatedScroll = 0f
            appliedScroll = 0f
            active = true
        }
        true
    }

    fun dragBy(scrollDelta: Float) {
        val rawDelta = synchronized(lock) {
            if (!active || settling) return
            val pageWidth = pagerState.layoutInfo.pageSize.toFloat().coerceAtLeast(1f)
            accumulatedScroll += scrollDelta
            val clamped = accumulatedScroll.coerceIn(-pageWidth, pageWidth)
            val delta = clamped - appliedScroll
            appliedScroll = clamped
            delta
        }
        if (rawDelta != 0f) {
            scope.launch { pagerState.dispatchRawDelta(rawDelta) }
        }
    }

    fun finish(releaseVelocity: Float = 0f) = synchronized(lock) {
        if (!active || settling) return
        val pageWidth = pagerState.layoutInfo.pageSize.toFloat().coerceAtLeast(1f)
        val targetPage = calculateSinglePageTarget(
            startPage = startPage,
            pageCount = pagerState.pageCount,
            pageWidth = pageWidth,
            accumulatedScroll = accumulatedScroll,
            releaseVelocity = releaseVelocity,
        )
        settleLocked(targetPage)
    }

    fun moveBy(pageDelta: Int) = synchronized(lock) {
        if (settling || active || pagerState.pageCount <= 1) return
        val currentPage = pagerState.settledPage
        val targetPage = (currentPage + pageDelta).coerceIn(0, pagerState.pageCount - 1)
        if (targetPage != currentPage) settleLocked(targetPage)
    }

    fun cancel() = synchronized(lock) {
        if (!active || settling) return
        settleLocked(startPage)
    }

    fun dispose() = synchronized(lock) {
        settleJob?.cancel()
        settleJob = null
        active = false
        settling = false
    }

    private fun settleLocked(targetPage: Int) {
        active = false
        accumulatedScroll = 0f
        appliedScroll = 0f
        settling = true
        settleJob?.cancel()
        settleJob = scope.launch {
            try {
                pagerState.animateScrollToPage(
                    page = targetPage,
                    animationSpec = tween(
                        durationMillis = IMAGE_PAGER_SETTLE_DURATION_MS,
                        easing = FastOutSlowInEasing,
                    ),
                )
            } finally {
                synchronized(lock) { settling = false }
            }
        }
    }
}

@Composable
private fun rememberSinglePagePagerController(pagerState: PagerState): SinglePagePagerController {
    val scope = rememberCoroutineScope()
    return remember(pagerState, scope) {
        SinglePagePagerController(pagerState, scope)
    }
}

private fun Modifier.singlePagePagerDrag(controller: SinglePagePagerController): Modifier =
    pointerInput(controller) {
        var accepted = false
        var velocityTracker = VelocityTracker()
        detectHorizontalDragGestures(
            onDragStart = {
                velocityTracker = VelocityTracker()
                accepted = controller.begin()
            },
            onHorizontalDrag = { change, dragAmount ->
                if (accepted) {
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    change.consume()
                    controller.dragBy(-dragAmount)
                }
            },
            onDragEnd = {
                if (accepted) {
                    val releaseVelocity = -velocityTracker.calculateVelocity().x
                    controller.finish(releaseVelocity)
                }
                accepted = false
            },
            onDragCancel = {
                if (accepted) controller.cancel()
                accepted = false
            },
        )
    }

@Composable
private fun InstallImagePagerTrackpadHandler(pagerState: PagerState) {
    val scope = rememberCoroutineScope()
    val handlerToken = remember { Any() }

    DisposableEffect(handlerToken, pagerState) {
        TrackpadGestureBridge.install()

        var intent = ImageScrollIntent.UNDECIDED
        var physicalGestureActive = false
        var ignoreMomentum = false
        var settling = false
        var decisionX = 0f
        var decisionY = 0f
        var accumulatedX = 0f
        var appliedX = 0f
        var lastEventTimeNanos = 0L
        var peakHorizontalVelocity = 0f
        var startPage = pagerState.settledPage
        var idleJob: Job? = null
        var settleJob: Job? = null
        val gestureLock = Any()

        fun resetPhysicalGesture() {
            intent = ImageScrollIntent.UNDECIDED
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
            val pageWidth = pagerState.layoutInfo.pageSize.toFloat().coerceAtLeast(1f)
            accumulatedX += deltaX
            val clamped = accumulatedX.coerceIn(-pageWidth, pageWidth)
            val rawDelta = clamped - appliedX
            appliedX = clamped
            if (rawDelta != 0f) {
                scope.launch { pagerState.dispatchRawDelta(rawDelta) }
            }
        }

        fun settleHorizontalGesture() {
            val pageWidth = pagerState.layoutInfo.pageSize.toFloat().coerceAtLeast(1f)
            val target = calculateSinglePageTarget(
                startPage = startPage,
                pageCount = pagerState.pageCount,
                pageWidth = pageWidth,
                accumulatedScroll = accumulatedX,
                releaseVelocity = peakHorizontalVelocity,
            )
            val currentPosition = pagerState.currentPage + pagerState.currentPageOffsetFraction
            val targetDistance = abs(target - currentPosition) * pageWidth
            if (targetDistance < 0.5f) {
                resetPhysicalGesture()
                return
            }

            settling = true
            settleJob?.cancel()
            settleJob = scope.launch {
                try {
                    pagerState.animateScrollToPage(
                        page = target,
                        animationSpec = tween(
                            durationMillis = IMAGE_PAGER_SETTLE_DURATION_MS,
                            easing = FastOutSlowInEasing,
                        ),
                    )
                } finally {
                    synchronized(gestureLock) { settling = false }
                }
            }
            resetPhysicalGesture()
        }

        val handler: (TrackpadGestureBridge.ScrollEvent) -> Boolean = handler@ { event ->
            synchronized(gestureLock) {
                val phase = TrackpadGestureBridge.Phase

                if (settling) return@handler true

                if (event.momentumPhase != phase.NONE) {
                    val consume = ignoreMomentum
                    if (phase.isFinished(event.momentumPhase)) ignoreMomentum = false
                    return@handler consume
                }

                if (!physicalGestureActive || phase.has(event.phase, phase.BEGAN)) {
                    physicalGestureActive = true
                    ignoreMomentum = false
                    intent = ImageScrollIntent.UNDECIDED
                    decisionX = 0f
                    decisionY = 0f
                    accumulatedX = 0f
                    appliedX = 0f
                    lastEventTimeNanos = 0L
                    peakHorizontalVelocity = 0f
                    startPage = pagerState.settledPage
                }

                val deltaX = -event.deltaX.toFloat()
                val deltaY = event.deltaY.toFloat()
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

                if (intent == ImageScrollIntent.UNDECIDED) {
                    decisionX += deltaX
                    decisionY += deltaY
                    if (abs(decisionX) + abs(decisionY) >= IMAGE_TRACKPAD_AXIS_DECISION_DISTANCE) {
                        intent = if (abs(decisionX) >= abs(decisionY) * IMAGE_TRACKPAD_HORIZONTAL_RATIO) {
                            ImageScrollIntent.HORIZONTAL
                        } else {
                            ImageScrollIntent.VERTICAL
                        }
                        if (intent == ImageScrollIntent.HORIZONTAL) {
                            applyHorizontalDelta(decisionX)
                        }
                    }
                } else if (intent == ImageScrollIntent.HORIZONTAL) {
                    applyHorizontalDelta(deltaX)
                }

                if (intent == ImageScrollIntent.VERTICAL) {
                    if (phase.isFinished(event.phase)) resetPhysicalGesture()
                    return@handler false
                }

                if (intent != ImageScrollIntent.HORIZONTAL) return@handler false

                idleJob?.cancel()
                if (phase.isFinished(event.phase)) {
                    ignoreMomentum = true
                    settleHorizontalGesture()
                } else if (event.phase == phase.NONE) {
                    idleJob = scope.launch {
                        delay(IMAGE_TRACKPAD_GESTURE_IDLE_MS)
                        synchronized(gestureLock) {
                            if (physicalGestureActive && intent == ImageScrollIntent.HORIZONTAL) {
                                ignoreMomentum = true
                                settleHorizontalGesture()
                            }
                        }
                    }
                }
                true
            }
        }

        TrackpadGestureBridge.setScrollHandler(handlerToken, handler)
        onDispose {
            synchronized(gestureLock) {
                idleJob?.cancel()
                settleJob?.cancel()
            }
            TrackpadGestureBridge.setScrollHandler(handlerToken, null)
        }
    }
}

class IllustDetailScreen(private val illustId: Long) : Screen {

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { IllustDetailScreenModel(illustId) }
        val illustState by screenModel.illustState.collectAsState()
        val relatedState by screenModel.relatedState.collectAsState()
        val ugoiraState by screenModel.ugoiraState.collectAsState()
        val isFollowing by screenModel.isFollowing.collectAsState()
        val isBookmarked by screenModel.isBookmarked.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val illust = (illustState as? UiState.Success)?.data

        var isFullscreen by remember { mutableStateOf(false) }
        var isMoreMenuExpanded by remember { mutableStateOf(false) }
        // Sync fullscreen state to a global flag so the tab-level EscBackNavigator
        // can prioritize exit-fullscreen over back-pop (single ESC handler, no race).
        LaunchedEffect(isFullscreen) { ceui.pixiv.fullscreenImageActive.value = isFullscreen }
        // ESC handler in MainScreen sets the global flag to false. Sync it back here
        // so the local isFullscreen follows — EscBackNavigator can't touch our state.
        LaunchedEffect(Unit) {
            androidx.compose.runtime.snapshotFlow { ceui.pixiv.fullscreenImageActive.value }
                .collect { fs -> if (!fs && isFullscreen) isFullscreen = false }
        }

        Scaffold(
            topBar = {
                if (!isFullscreen) {
                    TopAppBar(
                        title = {
                            val title = (illustState as? UiState.Success)
                                ?.data
                                ?.title
                                ?.takeIf { it.isNotBlank() }
                                ?: "Illust #$illustId"
                            Column(
                                modifier = Modifier.padding(vertical = 2.dp),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val user = illust?.user
                                if (user != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(14.dp))
                                                .clickable {
                                                    if (user.id > 0) {
                                                        navigator.push(UserDetailScreen(user.id))
                                                    }
                                                },
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        ) {
                                            UserAvatar(
                                                url = user.profile_image_urls?.px_50x50,
                                                size = 22,
                                            )
                                            Text(
                                                text = user.name.orEmpty(),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.widthIn(max = 140.dp),
                                            )
                                        }
                                        val following = isFollowing
                                        if (following != null) {
                                            Surface(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(50))
                                                    .clickable { screenModel.toggleFollow("public") },
                                                color = if (following) {
                                                    MaterialTheme.colorScheme.primaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceVariant
                                                },
                                                shape = RoundedCornerShape(50),
                                            ) {
                                                Text(
                                                    text = if (following) "Following" else "Follow",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (following) {
                                                        MaterialTheme.colorScheme.onPrimaryContainer
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                    },
                                                    modifier = Modifier.padding(
                                                        horizontal = 8.dp,
                                                        vertical = 3.dp,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { navigator.pop() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            val originalUrl = illust?.maxUrl()
                            if (isBookmarked != null) {
                                IconButton(
                                    onClick = { screenModel.toggleBookmark("public") },
                                ) {
                                    Icon(
                                        imageVector = if (isBookmarked == true) {
                                            Icons.Filled.Favorite
                                        } else {
                                            Icons.Outlined.FavoriteBorder
                                        },
                                        contentDescription = "Bookmark",
                                        tint = if (isBookmarked == true) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    )
                                }
                            }
                            if (originalUrl != null) {
                                IconButton(onClick = { openInBrowser(originalUrl) }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = "Open original in browser",
                                    )
                                }
                            }
                            Box {
                                IconButton(onClick = { isMoreMenuExpanded = true }) {
                                    Icon(
                                        Icons.Filled.MoreVert,
                                        contentDescription = "More actions",
                                    )
                                }
                                DropdownMenu(
                                    expanded = isMoreMenuExpanded,
                                    onDismissRequest = { isMoreMenuExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (illust?.isGif() == true) {
                                                    "Download ugoira GIF"
                                                } else if ((illust?.page_count ?: 1) > 1) {
                                                    "Download all pages"
                                                } else {
                                                    "Download"
                                                }
                                            )
                                        },
                                        enabled = illust != null && if (illust.isGif()) {
                                            (ugoiraState as? UiState.Success)?.data?.let { metadata ->
                                                !metadata.zip_urls?.medium.isNullOrBlank() &&
                                                    !metadata.frames.isNullOrEmpty()
                                            } == true
                                        } else {
                                            true
                                        },
                                        onClick = {
                                            isMoreMenuExpanded = false
                                            illust?.let { artwork ->
                                                if (artwork.isGif()) {
                                                    (ugoiraState as? UiState.Success)?.data?.let { metadata ->
                                                        screenModel.enqueueUgoira(artwork, metadata)
                                                    }
                                                } else {
                                                    screenModel.enqueueDownload(artwork)
                                                }
                                            }
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Save privately") },
                                        enabled = isBookmarked == false,
                                        onClick = {
                                            isMoreMenuExpanded = false
                                            screenModel.toggleBookmark("private")
                                        },
                                    )
                                    if (illust?.user != null) {
                                        DropdownMenuItem(
                                            text = { Text("Follow privately") },
                                            enabled = isFollowing == false,
                                            onClick = {
                                                isMoreMenuExpanded = false
                                                screenModel.toggleFollow("private")
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(if (isFullscreen) PaddingValues(0.dp) else padding)) {
                when (val s = illustState) {
                    is UiState.Loading -> LoadingView()
                    is UiState.Error -> ErrorView(s.message, {})
                    is UiState.Success -> IllustDetailContent(
                        illust = s.data,
                        relatedState = relatedState,
                        onIllustClick = { id -> navigator.push(IllustDetailScreen(id)) },
                        onTagClick = { tag -> navigator.push(SearchScreen(initialQuery = tag)) },
                        ugoiraState = ugoiraState,
                        isFullscreen = isFullscreen,
                        onToggleFullscreen = { isFullscreen = !isFullscreen },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IllustDetailContent(
    illust: Illust,
    relatedState: UiState<List<Illust>>,
    onIllustClick: (Long) -> Unit,
    onTagClick: (String) -> Unit,
    ugoiraState: UiState<UgoiraMetaData?> = UiState.Loading,
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
) {
    val imageUrls = buildList {
        if (illust.page_count <= 1) {
            illust.maxUrl()?.let { add(it) }
        } else {
            illust.meta_pages?.forEach { page ->
                page.image_urls?.original?.let { add(it) }
            }
        }
    }

    val imageAspectRatio = if (illust.width > 0 && illust.height > 0)
        illust.width.toFloat() / illust.height.toFloat()
    else null

    val imagePagerState = rememberPagerState(pageCount = { imageUrls.size.coerceAtLeast(1) })
    val imagePagerController = rememberSinglePagePagerController(imagePagerState)

    // Fullscreen mode: only image + overlay, no LazyColumn
    if (isFullscreen) {
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(focusRequester) {
            focusRequester.requestFocus()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> {
                            imagePagerController.moveBy(-1)
                            true
                        }
                        Key.DirectionRight -> {
                            imagePagerController.moveBy(1)
                            true
                        }
                        else -> false
                    }
                }
        ) {
            if (illust.isGif()) {
                when (val u = ugoiraState) {
                    is UiState.Success -> {
                        val meta = u.data
                        if (meta != null) {
                            val zipUrl = meta.zip_urls?.medium
                            val frames = meta.frames ?: emptyList()
                            if (zipUrl != null && frames.isNotEmpty()) {
                                UgoiraPlayer(zipUrl = zipUrl, frames = frames)
                            }
                        }
                    }
                    else -> {}
                }
            } else if (imageUrls.size > 1) {
                InstallImagePagerTrackpadHandler(imagePagerState)
                HorizontalPager(
                    state = imagePagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .singlePagePagerDrag(imagePagerController),
                    beyondViewportPageCount = 1,
                    userScrollEnabled = false,
                ) { page ->
                    ZoomableImage(
                        model = imageUrls[page],
                        contentDescription = "Page ${page + 1}",
                        modifier = Modifier.fillMaxSize(),
                        active = true,
                        onToggleFullscreen = onToggleFullscreen
                    )
                }
            } else {
                ZoomableImage(
                    model = imageUrls.firstOrNull(),
                    contentDescription = illust.title,
                    modifier = Modifier.fillMaxSize(),
                    active = true,
                    onToggleFullscreen = onToggleFullscreen
                )
            }

            // Fullscreen only keeps a standalone back button.
            Box(
                modifier = Modifier
                    .padding(8.dp)
                    .align(Alignment.TopStart)
            ) {
                IconButton(onClick = { onToggleFullscreen() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        "Exit fullscreen",
                        tint = Color.White
                    )
                }
            }

            // Bottom overlay: page indicator
            if (imageUrls.size > 1) {
                val currentPage by remember { derivedStateOf { imagePagerState.currentPage } }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(8.dp)
                        .align(Alignment.BottomCenter)
                ) {
                    Text(
                        text = "第 ${currentPage + 1} / ${imageUrls.size} P",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }
        }
        return
    }

    // Normal mode: use the same masonry layout as the recommendation feed.
    val gridState = rememberLazyStaggeredGridState()
    WorkFeedGrid(state = gridState) { viewportWidth, viewportHeight ->
        val imageAvailableWidth = (viewportWidth - 8.dp).coerceAtLeast(0.dp)
        val frameWidth = imageAspectRatio?.let { minOf(imageAvailableWidth, viewportHeight * it) }
            ?: imageAvailableWidth
        val frameHeight = imageAspectRatio?.let { frameWidth / it } ?: viewportHeight

        item(key = "detail-image", span = StaggeredGridItemSpan.FullLine) {
            if (illust.isGif()) {
                // Ugoira: animated player
                when (val u = ugoiraState) {
                    is UiState.Loading -> LoadingView()
                    is UiState.Error -> ErrorView(u.message, {})
                    is UiState.Success -> {
                        ArtworkFrame(frameWidth = frameWidth, frameHeight = frameHeight) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFFE0E0E0))
                            ) {
                                val meta = u.data
                                if (meta != null) {
                                    val zipUrl = meta.zip_urls?.medium
                                    val frames = meta.frames ?: emptyList()
                                    if (zipUrl != null && frames.isNotEmpty()) {
                                        UgoiraPlayer(
                                            zipUrl = zipUrl,
                                            frames = frames
                                        )
                                    } else {
                                        Text("Ugoira metadata incomplete")
                                    }
                                } else {
                                    Text("No ugoira metadata")
                                }
                            }
                        }
                    }
                }
            } else if (imageUrls.size > 1) {
                val currentPage by remember { derivedStateOf { imagePagerState.currentPage } }
                InstallImagePagerTrackpadHandler(imagePagerState)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ArtworkFrame(frameWidth = frameWidth, frameHeight = frameHeight) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFFE0E0E0))
                        ) {
                            HorizontalPager(
                                state = imagePagerState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .singlePagePagerDrag(imagePagerController),
                                beyondViewportPageCount = 1,
                                userScrollEnabled = false,
                            ) { page ->
                                ZoomableImage(
                                    model = imageUrls[page],
                                    contentDescription = "Page ${page + 1}",
                                    modifier = Modifier.fillMaxSize(),
                                    active = false,
                                    onToggleFullscreen = onToggleFullscreen
                                )
                            }
                        }
                    }
                    Text(
                        text = "第 ${currentPage + 1} / ${imageUrls.size} P",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                ArtworkFrame(frameWidth = frameWidth, frameHeight = frameHeight) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFFE0E0E0))
                    ) {
                        ZoomableImage(
                            model = imageUrls.firstOrNull(),
                            contentDescription = illust.title,
                            modifier = Modifier.fillMaxSize(),
                            active = false,
                            onToggleFullscreen = onToggleFullscreen
                        )
                    }
                }
            }
        }

        // Image info (resolution, pages, date)
        item(key = "image-info", span = StaggeredGridItemSpan.FullLine) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "${illust.width}×${illust.height}",
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = "| ${illust.page_count}P",
                    style = MaterialTheme.typography.labelSmall
                )
                val date = illust.create_date?.take(10)
                if (date != null) {
                    Text(
                        text = "| $date",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        // Stats
        item(key = "stats", span = StaggeredGridItemSpan.FullLine) {
            Text(
                text = "♥ ${illust.total_bookmarks ?: 0}  👁 ${illust.total_view ?: 0}",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // Caption
        if (!illust.caption.isNullOrBlank()) {
            item(key = "caption", span = StaggeredGridItemSpan.FullLine) {
                CaptionText(
                    html = illust.caption,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }

        // Tags
        item(key = "tags", span = StaggeredGridItemSpan.FullLine) {
            illust.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    lazyItems(tags) { tag ->
                        TagChip(tag = tag, onClick = onTagClick)
                    }
                }
            }
        }

        // Related works header
        item(key = "related-header", span = StaggeredGridItemSpan.FullLine) {
            Text(
                text = "Related works",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(16.dp)
            )
        }

        // Related works content uses the same card/grid parameters as the home feed.
        when (relatedState) {
            is UiState.Loading -> item(
                key = "related-loading",
                span = StaggeredGridItemSpan.FullLine,
            ) {
                LoadingView(modifier = Modifier.fillMaxWidth().height(200.dp))
            }
            is UiState.Error -> item(
                key = "related-error",
                span = StaggeredGridItemSpan.FullLine,
            ) {
                ErrorView(
                    message = (relatedState as UiState.Error).message,
                    onRetry = {},
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                )
            }
            is UiState.Success -> {
                val related = (relatedState as UiState.Success).data
                if (related.isEmpty()) {
                    item(
                        key = "related-empty",
                        span = StaggeredGridItemSpan.FullLine,
                    ) {
                        EmptyView("No related works", modifier = Modifier.fillMaxWidth().height(200.dp))
                    }
                } else {
                    items(related, key = { it.id }) { relatedIllust ->
                        IllustCard(
                            illust = relatedIllust,
                            onClick = onIllustClick,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtworkFrame(
    frameWidth: Dp,
    frameHeight: Dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(frameHeight),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(frameWidth)
                .fillMaxHeight(),
            content = content,
        )
    }
}
