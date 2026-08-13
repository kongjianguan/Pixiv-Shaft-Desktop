package ceui.pixiv.download

/**
 * 下载文件名模板渲染。
 *
 * 支持变量：{title} {id} {author} {author_id} {page} {ext} {series} {series_order} {chapters}。
 * - {page} 多页时渲染 " pN"（前导空格），单页渲染空串
 * - {ext} 渲染带点的扩展名 ".jpg" / ".txt" / ".gif"
 * - {series} {series_order} {chapters} 单篇下载渲染 空 / 1 / 1；系列章节任务由
 *   metadataJson（SeriesChapterMeta）提供真实值
 *
 * 渲染规则：先逐段替换变量，再按 / 分段、每段单独过 [sanitizeSegment] 后拼接，
 * 防止路径穿越（. / ..）与非法字符。模板相对下载根目录解析，不要以 / 开头。
 */
data class DownloadTemplateValues(
    val title: String,
    val id: Long,
    val author: String,
    val authorId: Long,
    val page: String,
    val ext: String,
    val series: String,
    val seriesOrder: String,
    val chapters: String,
) {
    val variables: Map<String, String> get() = mapOf(
        "title" to title,
        "id" to id.toString(),
        "author" to author,
        "author_id" to authorId.toString(),
        "page" to page,
        "ext" to ext,
        "series" to series,
        "series_order" to seriesOrder,
        "chapters" to chapters,
    )
}

object DownloadTemplate {

    /**
     * 渲染模板，得到相对下载根目录的路径（不含自动页码与扩展名追加）。
     * [pageSuffix] 非空时，{ext} 变量渲染为 pageSuffix + ext（" p2.jpg"），
     * 自动页码锚定在扩展名真正出现的位置。
     */
    fun render(template: String, values: DownloadTemplateValues, pageSuffix: String = ""): String {
        var result = template
        // 系列为空（单篇下载）时，{series}/ 目录段整体去掉，避免渲染出 untitled 空目录；
        // 非空时保留原样交给变量替换
        if (values.series.isBlank()) {
            result = result.replace("{series}/", "")
            result = result.replace("{series}", "")
        }
        // 单遍替换：只扫描模板原文中的 {token}，替换进去的值不再被二次扫描，
        // 避免标题/作者名里恰好含 {id} 这类字面量被误替换
        result = TOKEN_REGEX.replace(result) { match ->
            val token = match.groupValues[1]
            if (token == "ext" && pageSuffix.isNotEmpty()) {
                // 自动页码必须落在扩展名之前（"Title p2.jpg" 而不是 "Title.jpg p2"）。
                // 在 {ext} 变量位置直接拼后缀，避免标题/作者名里恰好含 ".jpg" 字样时，
                // 用 lastIndexOf 搜索会插进名字里（"photo p2.jpg.jpg"）
                pageSuffix + values.ext
            } else {
                val value = values.variables[token]
                // 变量值先按单段清洗：值里的 / 等非法字符换成 _，避免作者名/标题里的斜杠
                // 在替换后被误当作目录分隔符；空白值（如单页时的 {page}）原样保留
                if (value != null) sanitizeValue(value) else match.value
            }
        }
        // 逐段清洗后丢弃空段：{series}/ 被整体去掉后可能留下尾部空段（如
        // "Novels/{series}/"），不丢弃会渲染成 untitled 目录；变量值里不可能
        // 产生空段（值中的 / 已被 sanitizeValue 换成 _）
        return result.split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { segment -> sanitizeSegment(segment) }
            .ifBlank { "untitled" }
    }

    /**
     * 完整渲染：模板 + 可选自动页码后缀 + 扩展名。
     * 页码与扩展名由下载器决定；模板内嵌 {page} / {ext} 时各自替换生效，不再追加。
     * 模板不含 {page} 且多页时，追加 [autoPageSuffix]（如 " p2"）。
     */
    fun renderPath(
        template: String,
        values: DownloadTemplateValues,
        autoPageSuffix: String = "",
        ext: String = "",
    ): String {
        val inlineExt = template.contains("{ext}")
        val needsSuffix = autoPageSuffix.isNotEmpty() && !template.contains("{page}")
        var result = render(
            template,
            values,
            // 模板内嵌 {ext} 时把自动页码直接拼进 {ext} 的渲染值
            pageSuffix = if (needsSuffix && inlineExt && values.ext.isNotEmpty()) autoPageSuffix else "",
        )
        if (needsSuffix && !inlineExt) result += autoPageSuffix
        // 模板内嵌 {ext} 时已替换生效，再追加会得到 ".jpg.jpg"
        if (ext.isNotEmpty() && !inlineExt) result += ext
        return result
    }

    /**
     * 变量值级清洗：非法字符换下划线、折叠空白；保留首尾空白（{page} 渲染为
     * " pN" 需要前导空格），最终分段时再由 [sanitizeSegment] 统一 trim 与截断。
     */
    private fun sanitizeValue(value: String): String = value
        .replace(ILLEGAL_CHAR_REGEX, "_")
        .replace(WHITESPACE_REGEX, " ")

    fun sanitizeSegment(value: String): String {
        val cleaned = value
            .replace(ILLEGAL_CHAR_REGEX, "_")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
        // 防路径穿越：目录分段中的 "." / ".." 不允许原样保留
        if (cleaned == "." || cleaned == "..") return "_"
        return cleaned.ifBlank { "untitled" }.takeUtf8Bytes(MAX_SEGMENT_BYTES)
    }

    /**
     * 按 UTF-8 字节安全截断：macOS 文件名限制是 255 字节（NAME_MAX）而不是字符数，
     * 中日韩字符一个码点占 3 字节，按码点数截断仍可能超限导致「File name too long」。
     * 以码点为单位累加字节数，超限即停，emoji（代理对）不会在边界处被切碎。
     */
    private fun String.takeUtf8Bytes(maxBytes: Int): String {
        val builder = StringBuilder()
        var used = 0
        var offset = 0
        while (offset < length) {
            val codePoint = codePointAt(offset)
            val byteCount = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (used + byteCount > maxBytes) break
            builder.append(Character.toChars(codePoint))
            used += byteCount
            offset += Character.charCount(codePoint)
        }
        return builder.toString()
    }

    private val ILLEGAL_CHAR_REGEX = Regex("[\\\\/:*?\"<>|]")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val TOKEN_REGEX = Regex("\\{([a-z_]+)\\}")

    /** 单段上限 240 字节：NAME_MAX 255 减去自动页码（" p2"）与扩展名（".jpg"）的追加空间 */
    private const val MAX_SEGMENT_BYTES = 240
}
