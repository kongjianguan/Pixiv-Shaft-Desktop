package ceui.pixiv.ui.screen.novel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Toc
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.lisa.models.NovelBean
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.component.EmptyView
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.novel.ContentParser
import ceui.pixiv.ui.novel.NovelContent
import ceui.pixiv.ui.novel.rememberNovelReaderStyle
import ceui.pixiv.ui.navigation.LocalEscapeOverlayHandler
import ceui.pixiv.ui.state.UiState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class NovelReaderScreen(
    private val novelId: Long,
    private val novelTitle: String? = null,
) : Screen {

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { NovelReaderScreenModel(novelId) }
        val state by screenModel.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val settings = AppContainer.settingsStore
        val fontSizeSp by settings.readerFontSizeSpFlow.collectAsState()
        val lineSpacing by settings.readerLineSpacingFlow.collectAsState()
        val paragraphSpacingDp by settings.readerParagraphSpacingDpFlow.collectAsState()
        val themeId by settings.readerThemeFlow.collectAsState()

        val style = rememberNovelReaderStyle(
            themeId = themeId,
            fontSizeSp = fontSizeSp,
            lineSpacing = lineSpacing,
            paragraphSpacingDp = paragraphSpacingDp,
        )
        var targetSource by remember { mutableStateOf<Int?>(null) }
        var chapterMenuExpanded by remember { mutableStateOf(false) }
        var settingsVisible by remember { mutableStateOf(false) }
        var chromeVisible by remember { mutableStateOf(true) }
        var progress by remember { mutableFloatStateOf(0f) }
        var pendingSeek by remember { mutableFloatStateOf(-1f) }
        var scrollCommand by remember { mutableIntStateOf(0) }
        var restoredProgress by remember { mutableStateOf(false) }
        val focusRequester = remember { FocusRequester() }
        val escapeOverlayHandler = LocalEscapeOverlayHandler.current

        DisposableEffect(settingsVisible, chapterMenuExpanded) {
            val handler: (() -> Boolean)? = if (settingsVisible || chapterMenuExpanded) {
                {
                    settingsVisible = false
                    chapterMenuExpanded = false
                    true
                }
            } else {
                null
            }
            escapeOverlayHandler.value = handler
            onDispose {
                if (escapeOverlayHandler.value === handler) {
                    escapeOverlayHandler.value = null
                }
            }
        }

        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }

        val readerData = (state as? UiState.Success)?.data
        val outline = remember(readerData?.tokens) {
            readerData?.tokens?.let(ContentParser::buildChapterOutline).orEmpty()
        }

        LaunchedEffect(readerData?.tokens) {
            val data = readerData ?: return@LaunchedEffect
            if (!restoredProgress) {
                val saved = settings.readerProgress(novelId)
                progress = saved
                if (saved > 0f && data.tokens.isNotEmpty()) {
                    val index = (saved * data.tokens.lastIndex).roundToInt()
                    targetSource = data.tokens[index.coerceIn(0, data.tokens.lastIndex)].sourceStart
                }
                restoredProgress = true
            }
        }

        LaunchedEffect(progress, restoredProgress) {
            if (!restoredProgress) return@LaunchedEffect
            delay(500)
            settings.setReaderProgress(novelId, progress)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(style.background)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || settingsVisible) {
                        false
                    } else {
                        when {
                            event.key == Key.PageDown || event.key == Key.Spacebar || event.key == Key.DirectionDown -> {
                                scrollCommand += 1
                                true
                            }
                            event.key == Key.PageUp || event.key == Key.DirectionUp -> {
                                scrollCommand -= 1
                                true
                            }
                            event.isMetaPressed && event.key == Key.Equals -> {
                                settings.setReaderFontSizeSp(fontSizeSp + 1)
                                true
                            }
                            event.isMetaPressed && event.key == Key.Minus -> {
                                settings.setReaderFontSizeSp(fontSizeSp - 1)
                                true
                            }
                            else -> false
                        }
                    }
                },
        ) {
            when (val current = state) {
                is UiState.Loading -> Box(Modifier.fillMaxSize()) { LoadingView() }
                is UiState.Error -> Box(Modifier.fillMaxSize()) { ErrorView(current.message, screenModel::reload) }
                is UiState.Success -> {
                    if (current.data.tokens.isEmpty()) {
                        EmptyView("正文为空")
                    } else {
                        NovelContent(
                            tokens = current.data.tokens,
                            webNovel = current.data.webNovel,
                            style = style,
                            targetSource = targetSource,
                            scrollCommand = scrollCommand,
                            onTargetConsumed = { targetSource = null },
                            onProgressChanged = { if (pendingSeek < 0f) progress = it },
                            onScrollDirectionChanged = { scrollingDown ->
                                chromeVisible = !scrollingDown
                            },
                            topContentPadding = if (chromeVisible) 78.dp else 20.dp,
                            bottomContentPadding = if (chromeVisible) 116.dp else 24.dp,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = chromeVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            ) {
                ReaderTopBar(
                    title = novelTitle?.takeIf { it.isNotBlank() } ?: "小说阅读器",
                    styleBackground = style.background,
                    styleText = style.text,
                    previous = readerData?.webNovel?.seriesNavigation?.prevNovel,
                    next = readerData?.webNovel?.seriesNavigation?.nextNovel,
                    outline = outline,
                    chapterMenuExpanded = chapterMenuExpanded,
                    onBack = navigator::pop,
                    onPrevious = { readerData?.webNovel?.seriesNavigation?.prevNovel?.let { navigator.pushNovel(it) } },
                    onNext = { readerData?.webNovel?.seriesNavigation?.nextNovel?.let { navigator.pushNovel(it) } },
                    onOpenSettings = { settingsVisible = true },
                    onOpenChapters = { chapterMenuExpanded = true },
                    onDismissChapters = { chapterMenuExpanded = false },
                    onChapterSelected = { source ->
                        targetSource = source
                        chapterMenuExpanded = false
                    },
                )
            }

            if (readerData != null && readerData.tokens.isNotEmpty()) {
                AnimatedVisibility(
                    visible = chromeVisible,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
                ) {
                    ReaderBottomBar(
                        progress = progress,
                        sliderValue = pendingSeek.takeIf { it >= 0f } ?: progress,
                        styleBackground = style.background,
                        styleText = style.text,
                        styleSecondaryText = style.secondaryText,
                        onSliderChange = { pendingSeek = it },
                        onSliderFinished = {
                            val value = pendingSeek.takeIf { it >= 0f } ?: progress
                            val index = (value * (readerData.tokens.lastIndex.coerceAtLeast(0))).roundToInt()
                            targetSource = readerData.tokens.getOrNull(index)?.sourceStart
                            pendingSeek = -1f
                        },
                        onOpenSettings = { settingsVisible = true },
                    )
                }
            }

            if (settingsVisible) {
                NovelReaderSettingsPanel(
                    style = style,
                    themeId = themeId,
                    onThemeChange = settings::setReaderTheme,
                    onFontSizeChange = settings::setReaderFontSizeSp,
                    onLineSpacingChange = settings::setReaderLineSpacing,
                    onParagraphSpacingChange = settings::setReaderParagraphSpacingDp,
                    onDismiss = { settingsVisible = false },
                )
            }
        }
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    styleBackground: Color,
    styleText: Color,
    previous: NovelBean?,
    next: NovelBean?,
    outline: List<ceui.pixiv.ui.novel.ChapterOutlineEntry>,
    chapterMenuExpanded: Boolean,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChapters: () -> Unit,
    onDismissChapters: () -> Unit,
    onChapterSelected: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = styleBackground.copy(alpha = 0.96f),
        tonalElevation = 5.dp,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = styleText)
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    color = styleText,
                )
                Text(
                    text = "连续阅读",
                    style = MaterialTheme.typography.labelSmall,
                    color = styleText.copy(alpha = 0.62f),
                )
            }
            if (previous != null) {
                IconButton(onClick = onPrevious) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "上一话", tint = styleText)
                }
            }
            if (next != null) {
                IconButton(onClick = onNext) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "下一话", tint = styleText)
                }
            }
            if (outline.isNotEmpty()) {
                Box {
                    IconButton(onClick = onOpenChapters) {
                        Icon(Icons.Default.Toc, contentDescription = "目录", tint = styleText)
                    }
                    DropdownMenu(
                        expanded = chapterMenuExpanded,
                        onDismissRequest = onDismissChapters,
                    ) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 360.dp)
                                .heightIn(max = 480.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            outline.forEach { entry ->
                                DropdownMenuItem(
                                    text = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = { onChapterSelected(entry.sourceStart) },
                                )
                            }
                        }
                    }
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "阅读设置", tint = styleText)
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    progress: Float,
    sliderValue: Float,
    styleBackground: Color,
    styleText: Color,
    styleSecondaryText: Color,
    onSliderChange: (Float) -> Unit,
    onSliderFinished: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
        shape = RoundedCornerShape(14.dp),
        color = styleBackground.copy(alpha = 0.96f),
        tonalElevation = 5.dp,
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "已读 ${(progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = styleSecondaryText,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onOpenSettings) { Text("设置") }
            }
            Slider(
                value = sliderValue,
                onValueChange = onSliderChange,
                onValueChangeFinished = onSliderFinished,
                modifier = Modifier.fillMaxWidth(),
            )
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(2.dp)),
                color = styleText.copy(alpha = 0.72f),
                trackColor = styleText.copy(alpha = 0.12f),
            )
        }
    }
}

private fun cafe.adriel.voyager.navigator.Navigator.pushNovel(novel: NovelBean) {
    val id = novel.id?.toLong() ?: return
    push(NovelReaderScreen(id, novel.title))
}
