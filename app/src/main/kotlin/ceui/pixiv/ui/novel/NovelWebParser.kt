package ceui.pixiv.ui.novel

import ceui.loxia.PixivHtmlObject
import ceui.loxia.WebNovel
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/** Parses the HTML payload returned by Pixiv's `/webview/v2/novel` endpoint. */
object NovelWebParser {

    private val scriptRegex = Regex(
        "<script\\b[^>]*>(.*?)</script>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val pixivMarker = Regex(
        "Object\\.defineProperty\\(\\s*window\\s*,\\s*['\"]pixiv['\"]",
    )
    private val valueMarker = Regex("\\bvalue\\s*:")
    private val gson = Gson()

    fun parse(html: String): WebNovel? {
        val script = scriptRegex.findAll(html)
            .map { it.groupValues[1] }
            .firstOrNull { pixivMarker.containsMatchIn(it) }
            ?: return null
        val pixivEnd = pixivMarker.find(script)!!.range.last + 1
        val valueStart = valueMarker.find(script, pixivEnd)?.range?.last ?: return null
        val objectStart = script.indexOf('{', valueStart + 1)
        if (objectStart < 0) return null
        val objectEnd = findJsonObjectEnd(script, objectStart) ?: return null
        return try {
            val json = stripTrailingCommas(script.substring(objectStart, objectEnd + 1))
            gson.fromJson(json, PixivHtmlObject::class.java).novel
        } catch (_: JsonSyntaxException) {
            null
        }
    }

    /** Pixiv embeds a JavaScript object literal, which may contain trailing commas. */
    private fun stripTrailingCommas(json: String): String {
        val result = StringBuilder(json.length)
        var inString = false
        var escaped = false
        var index = 0
        while (index < json.length) {
            val c = json[index]
            if (inString) {
                result.append(c)
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                index++
                continue
            }
            if (c == '"') {
                inString = true
                result.append(c)
                index++
                continue
            }
            if (c == ',') {
                var lookahead = index + 1
                while (lookahead < json.length && json[lookahead].isWhitespace()) lookahead++
                if (lookahead < json.length && json[lookahead] in charArrayOf('}', ']')) {
                    index++
                    continue
                }
            }
            result.append(c)
            index++
        }
        return result.toString()
    }

    /** Finds the matching closing brace while ignoring braces inside strings. */
    private fun findJsonObjectEnd(source: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until source.length) {
            val c = source[index]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }
}
