package ceui.pixiv.ui.screen.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import ceui.loxia.Article
import ceui.loxia.TrendingTag
import ceui.pixiv.di.AppContainer
import ceui.pixiv.ui.component.EmptyView
import ceui.pixiv.ui.component.ErrorView
import ceui.pixiv.ui.component.LoadingView
import ceui.pixiv.ui.component.RankingFeed
import ceui.pixiv.ui.component.RankingType
import ceui.pixiv.ui.navigation.LocalScrollToTop
import ceui.pixiv.ui.screen.pixivision.PixivisionScreen
import ceui.pixiv.ui.screen.pixivision.fullArticleUrl
import ceui.pixiv.ui.state.UiState
import ceui.pixiv.util.openInBrowser
import coil3.compose.AsyncImage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// 排行 mode 列表：day/week/month + 分类/R18 模式，标签保持英文短风格；
// R18 全局开关关闭时过滤掉 r18 模式（对用户不可见）
private fun discoverRankingModes(showR18: Boolean): List<Pair<String, String>> {
    val modes = listOf(
        "day" to "Daily",
        "week" to "Weekly",
        "month" to "Monthly",
        "day_male" to "Male",
        "day_female" to "Female",
        "week_original" to "Original",
        "week_rookie" to "Rookie",
        "day_r18" to "R18 Daily",
        "week_r18" to "R18 Weekly",
    )
    return if (showR18) modes else modes.filterNot { it.first.contains("r18") }
}

// 小说排行 mode：对齐原 Shaft 的 6 个模式；R18 开关关闭时过滤
private fun novelRankingModes(showR18: Boolean): List<Pair<String, String>> {
    val modes = listOf(
        "day" to "Daily",
        "week" to "Weekly",
        "day_male" to "Male",
        "day_female" to "Female",
        "week_rookie" to "Rookie",
        "day_r18" to "R18 Daily",
    )
    return if (showR18) modes else modes.filterNot { it.first.contains("r18") }
}

// 排名日期格式：补零（yyyy-MM-dd）。pixiv 官方 API spec 声明 date 参数 pattern 为 yyyy-MM-dd，
// pixivpy 注释也明确「Pixiv raises an error if the date is not in the format YYYY-MM-DD」，
// 不补零（如 2024-3-5）可能被服务器拒绝导致日期筛选静默失败。
private val RANKING_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun LocalDate.formatRankingDate(): String = RANKING_DATE_FORMAT.format(this)

private fun String.parseRankingDate(): LocalDate? = try {
    LocalDate.parse(this, RANKING_DATE_FORMAT)
} catch (_: Exception) {
    null
}

private enum class OpenPicker { NONE, ILLUST, NOVEL }

class DiscoverScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { DiscoverScreenModel() }
        val tagsState by screenModel.tagsState.collectAsState()
        val currentMode by screenModel.currentMode.collectAsState()
        val currentDate by screenModel.currentDate.collectAsState()
        val novelMode by screenModel.novelMode.collectAsState()
        val novelDate by screenModel.novelDate.collectAsState()
        val articlesState by screenModel.articlesState.collectAsState()
        val showR18 by AppContainer.settingsStore.isShowR18Flow.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        var openPicker by remember { mutableStateOf(OpenPicker.NONE) }
        val listState = rememberLazyListState()

        // R18 开关关闭时，把已选中的 r18 mode 重置回全年龄（保证界面无 R18 痕迹）
        LaunchedEffect(showR18) {
            screenModel.syncR18Visibility(showR18)
        }

        val scrollToTopState = LocalScrollToTop.current
        val scrollToTopValue = scrollToTopState.value
        LaunchedEffect(scrollToTopValue) {
            if (scrollToTopValue > 0) {
                listState.scrollToItem(0)
                screenModel.refresh()
                scrollToTopState.value = 0
            }
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
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
                    is UiState.Loading -> LoadingView(modifier = Modifier.fillMaxWidth().height(120.dp))
                    is UiState.Error -> ErrorView(s.message, { screenModel.refresh() }, Modifier.fillMaxWidth().height(120.dp))
                    is UiState.Success -> LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(s.data) { tag ->
                            TrendingTagItem(tag)
                        }
                    }
                }
            }

            // 插画排行
            item {
                Text(
                    text = "Illust Ranking",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp, 8.dp)
                )
            }
            item {
                RankingModeChips(
                    modes = discoverRankingModes(showR18),
                    currentMode = currentMode,
                    currentDate = currentDate,
                    onModeSelect = { screenModel.selectMode(it) },
                    onDateClick = { openPicker = OpenPicker.ILLUST },
                )
            }
            item {
                // 切换 mode/date 时重建，保证滚动位置和数据都切到新组合
                key("$currentMode-$currentDate") {
                    RankingFeed(
                        mode = currentMode,
                        type = RankingType.ILLUST,
                        date = currentDate,
                        modifier = Modifier.height(400.dp),
                        refreshTick = screenModel.rankingRefreshTick,
                    )
                }
            }

            // 小说排行
            item {
                Text(
                    text = "Novel Ranking",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(16.dp, 8.dp)
                )
            }
            item {
                RankingModeChips(
                    modes = novelRankingModes(showR18),
                    currentMode = novelMode,
                    currentDate = novelDate,
                    onModeSelect = { screenModel.selectNovelMode(it) },
                    onDateClick = { openPicker = OpenPicker.NOVEL },
                )
            }
            item {
                key("$novelMode-$novelDate") {
                    RankingFeed(
                        mode = novelMode,
                        type = RankingType.NOVEL,
                        date = novelDate,
                        modifier = Modifier.height(400.dp),
                        refreshTick = screenModel.rankingRefreshTick,
                    )
                }
            }

            // Pixivision 特辑预览
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp)
                ) {
                    Text(
                        text = "Pixivision 特辑",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp)
                    )
                    TextButton(onClick = { navigator.push(PixivisionScreen()) }) {
                        Text("查看全部")
                    }
                }
            }
            item {
                PixivisionPreviewRow(
                    state = articlesState,
                    onRefresh = { screenModel.refresh() },
                )
            }
            item {
                Spacer(Modifier.height(24.dp))
            }
        }

        when (openPicker) {
            OpenPicker.ILLUST -> RankingDatePickerDialog(
                initialDate = currentDate,
                onDismiss = { openPicker = OpenPicker.NONE },
                onConfirm = { screenModel.selectDate(it) },
            )
            OpenPicker.NOVEL -> RankingDatePickerDialog(
                initialDate = novelDate,
                onDismiss = { openPicker = OpenPicker.NONE },
                onConfirm = { screenModel.selectNovelDate(it) },
            )
            OpenPicker.NONE -> Unit
        }
    }
}

@Composable
private fun RankingModeChips(
    modes: List<Pair<String, String>>,
    currentMode: String,
    currentDate: String?,
    onModeSelect: (String) -> Unit,
    onDateClick: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(modes) { (mode, label) ->
            FilterChip(
                selected = (currentMode == mode),
                onClick = { onModeSelect(mode) },
                label = { Text(label) }
            )
        }
        item {
            FilterChip(
                selected = false,
                onClick = onDateClick,
                label = { Text(currentDate ?: "选择日期") }
            )
        }
    }
}

/** 排行日期选择：DatePicker 限制不能选未来日期。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RankingDatePickerDialog(
    initialDate: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val today = LocalDate.now()
    val todayMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val initialMillis = initialDate?.parseRankingDate()
        ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialMillis,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayMillis
            override fun isSelectableYear(year: Int): Boolean = year <= today.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { millis ->
                    onConfirm(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().formatRankingDate())
                }
                onDismiss()
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    ) {
        DatePicker(state = state)
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

@Composable
private fun PixivisionPreviewRow(
    state: UiState<List<Article>>,
    onRefresh: () -> Unit,
) {
    when (state) {
        is UiState.Loading -> LoadingView(modifier = Modifier.fillMaxWidth().height(160.dp))
        is UiState.Error -> ErrorView(state.message, onRefresh, Modifier.fillMaxWidth().height(160.dp))
        is UiState.Success -> if (state.data.isEmpty()) {
            EmptyView("No articles", modifier = Modifier.fillMaxWidth().height(160.dp))
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.data.take(10), key = { it.id }) { article ->
                    PixivisionCard(article)
                }
            }
        }
    }
}

@Composable
private fun PixivisionCard(article: Article) {
    Column(
        modifier = Modifier
            .width(180.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { fullArticleUrl(article)?.let { openInBrowser(it) } }
    ) {
        AsyncImage(
            model = article.thumbnail,
            contentDescription = article.title,
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp),
            contentScale = ContentScale.Crop
        )
        Text(
            text = article.title ?: "Untitled",
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}
