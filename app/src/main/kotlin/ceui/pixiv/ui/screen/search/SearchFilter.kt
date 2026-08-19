package ceui.pixiv.ui.screen.search

enum class SearchSort(val apiValue: String, val label: String) {
    PopularPreview("popular_preview", "热度预览"),
    DateDesc("date_desc", "最新"),
    DateAsc("date_asc", "最旧"),
    PopularDesc("popular_desc", "热度"),
}

enum class SearchTarget(val apiValue: String, val label: String) {
    PartialTags("partial_match_for_tags", "标签部分匹配"),
    ExactTags("exact_match_for_tags", "标签完全匹配"),
    TitleCaption("title_and_caption", "标题和简介"),
    NovelText("text", "正文"),
    NovelKeyword("keyword", "关键词"),
    ;

    companion object {
        fun fromApiValue(value: String?): SearchTarget =
            values().firstOrNull { it.apiValue == value } ?: PartialTags
    }
}

enum class SearchAiMode(val label: String) {
    All("全部作品"),
    ExcludeAi("屏蔽 AI"),
    OnlyAi("仅 AI"),
}

enum class SearchR18Mode(val label: String) {
    All("全部"),
    SafeOnly("仅全年龄"),
    R18Only("仅 R-18"),
}

enum class SearchRatio(val apiValue: String, val label: String) {
    Landscape("landscape", "横图"),
    Portrait("portrait", "竖图"),
    Square("square", "正方形"),
}

enum class SearchContentType(val apiValue: String?, val label: String) {
    All(null, "插画、漫画、动图"),
    IllustAndUgoira("illust_and_ugoira", "插画、动图"),
    Illust("illust", "插画"),
    Ugoira("ugoira", "动图"),
    Manga("manga", "漫画"),
}

enum class SearchResolution(val label: String, val min: Int?, val max: Int?) {
    Above3000("3000px 以上", 3000, null),
    Between1000And2999("1000px - 2999px", 1000, 2999),
    Below1000("999px 以下", null, 999),
}

enum class SearchBodyLengthUnit(val label: String) {
    Characters("文字数"),
    Words("单词数"),
    ReadingMinutes("阅读分钟"),
}

data class SearchBodyLength(
    val unit: SearchBodyLengthUnit,
    val min: Int? = null,
    val max: Int? = null,
)

data class SearchFilter(
    val sort: SearchSort = SearchSort.DateDesc,
    val target: SearchTarget = SearchTarget.PartialTags,
    val bookmarkMin: Int? = null,
    val tool: String? = null,
    val genre: Int? = null,
    val language: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val ratio: SearchRatio? = null,
    val contentType: SearchContentType = SearchContentType.All,
    val resolution: SearchResolution? = null,
    val aiMode: SearchAiMode = SearchAiMode.All,
    val r18Mode: SearchR18Mode = SearchR18Mode.All,
    val isOriginalOnly: Boolean = false,
    val isReplaceableOnly: Boolean = false,
    val bodyLength: SearchBodyLength? = null,
) {
    fun activeCount(isNovel: Boolean): Int {
        var count = 0
        if (sort != SearchSort.DateDesc) count++
        if (target != SearchTarget.PartialTags) count++
        if (bookmarkMin != null) count++
        if (language != null) count++
        if (startDate != null || endDate != null) count++
        if (aiMode != SearchAiMode.All) count++
        if (r18Mode != SearchR18Mode.All) count++
        if (isNovel) {
            if (genre != null) count++
            if (isOriginalOnly) count++
            if (isReplaceableOnly) count++
            if (bodyLength != null) count++
        } else {
            if (tool != null) count++
            if (ratio != null) count++
            if (contentType != SearchContentType.All) count++
            if (resolution != null) count++
        }
        return count
    }
}

enum class SearchTab(val label: String) {
    Illust("插画"),
    Novel("小说"),
    User("用户"),
}

enum class SearchInputKind {
    Keyword,
    Url,
    Numeric,
}

data class SearchSuggestion(
    val tag: String,
    val translatedName: String? = null,
)

internal fun shouldLoadSearchMore(
    itemCount: Int,
    lastVisibleIndex: Int,
    hasMore: Boolean,
    isLoadingMore: Boolean,
): Boolean {
    if (!hasMore || isLoadingMore) return false
    return itemCount == 0 || lastVisibleIndex >= itemCount - 5
}
