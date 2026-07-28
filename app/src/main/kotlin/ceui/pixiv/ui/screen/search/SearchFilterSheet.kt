package ceui.pixiv.ui.screen.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ceui.pixiv.ui.search.v3.SearchOptionsResponse

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchFilterSheet(
    isNovel: Boolean,
    initialFilter: SearchFilter,
    options: SearchOptionsResponse?,
    onDismiss: () -> Unit,
    onApply: (SearchFilter) -> Unit,
) {
    var draft by remember(initialFilter) { mutableStateOf(initialFilter) }
    val scrollState = rememberScrollState()
    val targetOptions = if (isNovel) {
        listOf(SearchTarget.PartialTags, SearchTarget.ExactTags, SearchTarget.NovelText, SearchTarget.NovelKeyword)
    } else {
        listOf(SearchTarget.PartialTags, SearchTarget.ExactTags, SearchTarget.TitleCaption)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("搜索条件", style = androidx.compose.material3.MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { draft = SearchFilter() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "重置筛选")
                }
            }

            FilterSection("排序")
            ChoiceRow(
                options = SearchSort.values().map { it.name to it.label },
                selected = draft.sort.name,
                onSelected = { value -> draft = draft.copy(sort = SearchSort.valueOf(value)) },
            )

            FilterSection("检索范围")
            ChoiceRow(
                options = targetOptions.map { it.name to it.label },
                selected = draft.target.name,
                onSelected = { value -> draft = draft.copy(target = SearchTarget.valueOf(value)) },
            )

            FilterSection("收藏数")
            val bookmarkOptions = listOf(null to "不限", 100 to "100+", 500 to "500+", 1000 to "1000+", 5000 to "5000+", 10000 to "10000+")
            ChoiceRow(
                options = bookmarkOptions.map { (value, label) -> (value?.toString() ?: "none") to label },
                selected = draft.bookmarkMin?.toString() ?: "none",
                onSelected = { value -> draft = draft.copy(bookmarkMin = value.takeUnless { it == "none" }?.toIntOrNull()) },
            )

            FilterSection("投稿期间")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = draft.startDate.orEmpty(),
                    onValueChange = { draft = draft.copy(startDate = it.trim().ifBlank { null }) },
                    label = { Text("起始日期") },
                    placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = draft.endDate.orEmpty(),
                    onValueChange = { draft = draft.copy(endDate = it.trim().ifBlank { null }) },
                    label = { Text("截止日期") },
                    placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            FilterSection("语言")
            val languages = options?.let { scopeFor(isNovel, it)?.lang?.options.orEmpty() } ?: emptyList()
            if (languages.isEmpty()) {
                Text("搜索后可加载语言选项", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            } else {
                ChoiceRow(
                    options = listOf("none" to "所有语种") + languages.map { it.code to it.name },
                    selected = draft.language ?: "none",
                    onSelected = { value -> draft = draft.copy(language = value.takeUnless { it == "none" }) },
                )
            }

            FilterSection("AI 与 R-18")
            ChoiceRow(
                options = SearchAiMode.values().map { it.name to it.label },
                selected = draft.aiMode.name,
                onSelected = { value -> draft = draft.copy(aiMode = SearchAiMode.valueOf(value)) },
            )
            ChoiceRow(
                options = SearchR18Mode.values().map { it.name to it.label },
                selected = draft.r18Mode.name,
                onSelected = { value -> draft = draft.copy(r18Mode = SearchR18Mode.valueOf(value)) },
            )

            if (isNovel) {
                FilterSection("小说类型")
                val genres = options?.novel?.genre?.options.orEmpty()
                if (genres.isEmpty()) {
                    Text("搜索后可加载小说类型", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                } else {
                    ChoiceRow(
                        options = listOf("none" to "全部类型") + genres.map { it.id.toString() to it.label },
                        selected = draft.genre?.toString() ?: "none",
                        onSelected = { value -> draft = draft.copy(genre = value.takeUnless { it == "none" }?.toIntOrNull()) },
                    )
                }
                FilterSection("小说条件")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draft.isOriginalOnly,
                        onClick = { draft = draft.copy(isOriginalOnly = !draft.isOriginalOnly) },
                        label = { Text("仅原创") },
                    )
                    FilterChip(
                        selected = draft.isReplaceableOnly,
                        onClick = { draft = draft.copy(isReplaceableOnly = !draft.isReplaceableOnly) },
                        label = { Text("支持单词置换") },
                    )
                }
                BodyLengthSection(draft) { draft = draft.copy(bodyLength = it) }
            } else {
                FilterSection("作品类别")
                ChoiceRow(
                    options = SearchContentType.values().map { it.name to it.label },
                    selected = draft.contentType.name,
                    onSelected = { value -> draft = draft.copy(contentType = SearchContentType.valueOf(value)) },
                )
                FilterSection("长宽比")
                ChoiceRow(
                    options = listOf("none" to "所有纵横比") + SearchRatio.values().map { it.name to it.label },
                    selected = draft.ratio?.name ?: "none",
                    onSelected = { value -> draft = draft.copy(ratio = value.takeUnless { it == "none" }?.let(SearchRatio::valueOf)) },
                )
                FilterSection("分辨率")
                ChoiceRow(
                    options = listOf("none" to "全部清晰度") + SearchResolution.values().map { it.name to it.label },
                    selected = draft.resolution?.name ?: "none",
                    onSelected = { value -> draft = draft.copy(resolution = value.takeUnless { it == "none" }?.let(SearchResolution::valueOf)) },
                )
                FilterSection("制图工具")
                val tools = options?.illust?.tool?.options.orEmpty()
                if (tools.isEmpty()) {
                    Text("搜索后可加载制图工具", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                } else {
                    ChoiceRow(
                        options = listOf("none" to "所有工具") + tools.map { it to it },
                        selected = draft.tool ?: "none",
                        onSelected = { value -> draft = draft.copy(tool = value.takeUnless { it == "none" }) },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
            Button(
                onClick = {
                    onApply(draft)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            ) {
                Text("应用筛选")
            }
        }
    }
}

@Composable
private fun FilterSection(title: String) {
    Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun ChoiceRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelected(value) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun BodyLengthSection(
    filter: SearchFilter,
    onChange: (SearchBodyLength?) -> Unit,
) {
    FilterSection("正文长度")
    var minText by remember(filter.bodyLength) { mutableStateOf(filter.bodyLength?.min?.toString().orEmpty()) }
    var maxText by remember(filter.bodyLength) { mutableStateOf(filter.bodyLength?.max?.toString().orEmpty()) }
    val unit = filter.bodyLength?.unit ?: SearchBodyLengthUnit.Characters
    ChoiceRow(
        options = SearchBodyLengthUnit.values().map { it.name to it.label },
        selected = unit.name,
        onSelected = { value ->
            val nextUnit = SearchBodyLengthUnit.valueOf(value)
            onChange(SearchBodyLength(nextUnit, minText.toIntOrNull(), maxText.toIntOrNull()))
        },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = minText,
            onValueChange = {
                minText = it.filter(Char::isDigit)
                onChange(SearchBodyLength(unit, minText.toIntOrNull(), maxText.toIntOrNull()))
            },
            label = { Text("下限") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = maxText,
            onValueChange = {
                maxText = it.filter(Char::isDigit)
                onChange(SearchBodyLength(unit, minText.toIntOrNull(), maxText.toIntOrNull()))
            },
            label = { Text("上限") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun scopeFor(isNovel: Boolean, options: SearchOptionsResponse) = if (isNovel) options.novel else options.illust
