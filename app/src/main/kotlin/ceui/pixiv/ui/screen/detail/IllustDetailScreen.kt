package ceui.pixiv.ui.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.drop
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import coil3.compose.AsyncImage
import ceui.loxia.Illust
import ceui.loxia.UgoiraMetaData
import ceui.pixiv.ui.component.CaptionText
import ceui.pixiv.ui.component.EmptyView
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.UgoiraPlayer
import ceui.pixiv.ui.component.IllustCard
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.component.TagChip
import ceui.pixiv.ui.component.UserAvatar
import ceui.pixiv.ui.component.ZoomableImage
import ceui.pixiv.ui.screen.search.SearchScreen
import ceui.pixiv.ui.screen.user.UserDetailScreen
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.util.openInBrowser

class IllustDetailScreen(private val illustId: Long) : Screen {

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { IllustDetailScreenModel(illustId) }
        val illustState by screenModel.illustState.collectAsState()
        val relatedState by screenModel.relatedState.collectAsState()
        val ugoiraState by screenModel.ugoiraState.collectAsState()
        val isFollowing by screenModel.isFollowing.collectAsState()
        val navigator = LocalNavigator.currentOrThrow

        var isFullscreen by remember { mutableStateOf(false) }
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
                            Text(title)
                        },
                        navigationIcon = {
                            IconButton(onClick = { navigator.pop() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            val isBookmarked by screenModel.isBookmarked.collectAsState()
                            val illust = (illustState as? UiState.Success)?.data
                            val originalUrl = illust?.maxUrl()
                            if (originalUrl != null) {
                                IconButton(onClick = { openInBrowser(originalUrl) }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = "Open original in browser"
                                    )
                                }
                            }
                            val bookmarked = isBookmarked
                            if (bookmarked != null) {
                                IconButton(
                                    onClick = { screenModel.toggleBookmark("public") },
                                    modifier = Modifier.combinedClickable(
                                        onClick = { screenModel.toggleBookmark("public") },
                                        onLongClick = { screenModel.toggleBookmark("private") }
                                    )
                                ) {
                                    Icon(
                                        imageVector = if (bookmarked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                        contentDescription = "Bookmark",
                                        tint = if (bookmarked) Color.Red else MaterialTheme.colorScheme.onSurface
                                    )
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
                        isFollowing = isFollowing,
                        onToggleFollow = { screenModel.toggleFollow(it) },
                        onUserClick = { userId -> navigator.push(UserDetailScreen(userId)) }
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
    isFollowing: Boolean? = null,
    onToggleFollow: (String) -> Unit = {},
    onUserClick: (Long) -> Unit = {}
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

    // Pre-size image containers so the layout never collapses before images load.
    val imageAspectRatio = if (illust.width > 0 && illust.height > 0)
        illust.width.toFloat() / illust.height.toFloat()
    else null
    val imageSizeMod = if (imageAspectRatio != null)
        Modifier.fillMaxWidth().aspectRatio(imageAspectRatio)
    else
        Modifier.fillMaxWidth()

    // Fullscreen mode: only image + overlay, no LazyColumn
    if (isFullscreen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
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
                val pagerState = rememberPagerState(pageCount = { imageUrls.size })
                HorizontalPager(state = pagerState) { page ->
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

            // Top overlay: back button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
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
                val pagerState = rememberPagerState(pageCount = { imageUrls.size })
                val currentPage by remember { derivedStateOf { pagerState.currentPage } }
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

    // Normal mode: full LazyColumn
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // Image gallery
        item {
            if (illust.isGif()) {
                // Ugoira: animated player
                when (val u = ugoiraState) {
                    is UiState.Loading -> LoadingView()
                    is UiState.Error -> ErrorView(u.message, {})
                    is UiState.Success -> {
                        Box(
                            modifier = imageSizeMod
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
            } else if (imageUrls.size > 1) {
                val pagerState = rememberPagerState(pageCount = { imageUrls.size })
                val currentPage by remember { derivedStateOf { pagerState.currentPage } }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = imageSizeMod
                            .background(Color(0xFFE0E0E0))
                    ) {
                        HorizontalPager(state = pagerState) { page ->
                            ZoomableImage(
                                model = imageUrls[page],
                                contentDescription = "Page ${page + 1}",
                                modifier = Modifier.fillMaxSize(),
                                active = false,
                                onToggleFullscreen = onToggleFullscreen
                            )
                        }
                        if (isFullscreen) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.5f))
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
                        }
                    }
                    if (isFullscreen && imageUrls.size > 1) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "第 ${currentPage + 1} / ${imageUrls.size} P  ← tap to exit",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    } else {
                        Text(
                            text = "第 ${currentPage + 1} / ${imageUrls.size} P",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                }
            } else {
                Box(
                    modifier = imageSizeMod
                        .background(Color(0xFFE0E0E0))
                ) {
                    ZoomableImage(
                        model = imageUrls.firstOrNull(),
                        contentDescription = illust.title,
                        modifier = Modifier.fillMaxSize(),
                        active = false,
                        onToggleFullscreen = onToggleFullscreen
                    )
                    if (isFullscreen) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.5f))
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
                    }
                }
            }
        }

        // Image info (resolution, pages, date)
        item {
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

        // Title + author
        item {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = illust.title ?: "Untitled",
                    style = MaterialTheme.typography.titleMedium
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { illust.user?.id?.let { onUserClick(it) } },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        UserAvatar(url = illust.user?.profile_image_urls?.px_50x50)
                        Text(
                            text = illust.user?.name ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    val following = isFollowing
                    if (following != null) {
                        if (following) {
                            Button(
                                onClick = {},
                                modifier = Modifier.combinedClickable(
                                    onClick = { onToggleFollow("public") },
                                    onLongClick = { onToggleFollow("private") }
                                ).clip(RoundedCornerShape(16.dp))
                            ) {
                                Text("Following", style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            OutlinedButton(
                                onClick = {},
                                modifier = Modifier.combinedClickable(
                                    onClick = { onToggleFollow("public") },
                                    onLongClick = { onToggleFollow("private") }
                                )
                            ) {
                                Text("Follow", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Text(
                    text = "♥ ${illust.total_bookmarks ?: 0}  👁 ${illust.total_view ?: 0}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // Caption
        if (!illust.caption.isNullOrBlank()) {
            item {
                CaptionText(
                    html = illust.caption,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }

        // Tags
        item {
            illust.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tags) { tag ->
                        TagChip(tag = tag, onClick = onTagClick)
                    }
                }
            }
        }

        // Related works header
        item {
            Text(
                text = "Related works",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(16.dp)
            )
        }

        // Related works content
        when (relatedState) {
            is UiState.Loading -> item { LoadingView(modifier = Modifier.height(200.dp)) }
            is UiState.Error -> item {
                ErrorView(
                    message = (relatedState as UiState.Error).message,
                    onRetry = {},
                    modifier = Modifier.height(200.dp)
                )
            }
            is UiState.Success -> {
                val related = (relatedState as UiState.Success).data
                if (related.isEmpty()) {
                    item { EmptyView("No related works", modifier = Modifier.height(200.dp)) }
                } else {
                    items(related, key = { it.id }) { relatedIllust ->
                        IllustCard(
                            illust = relatedIllust,
                            onClick = onIllustClick,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
