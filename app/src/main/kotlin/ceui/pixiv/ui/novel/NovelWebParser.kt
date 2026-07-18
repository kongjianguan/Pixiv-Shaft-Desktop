package ceui.pixiv.ui.novel

import ceui.loxia.NovelImages
import ceui.loxia.SeriesNavigation
import ceui.loxia.WebIllustHolder
import ceui.loxia.WebNovel
import ceui.lisa.models.NovelDetail.NovelMarkerBean
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import java.io.StringReader

/** Parses the HTML payload returned by Pixiv's `/webview/v2/novel` endpoint. */
object NovelWebParser {

    private val scriptRegex = Regex(
        "<script\\b[^>]*>(.*?)</script\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val pixivMarker = Regex(
        "Object\\.defineProperty\\(\\s*(?:window|globalThis|self)\\s*,\\s*(['\"])pixiv\\1\\s*,?",
        RegexOption.IGNORE_CASE,
    )
    private val valueMarker = Regex("\\bvalue\\s*:")
    private val pixivAssignmentMarker = Regex(
        "(?:window|globalThis|self)\\s*\\.\\s*pixiv\\s*=",
        RegexOption.IGNORE_CASE,
    )
    private val htmlEntityRegex = Regex(
        "&(quot|apos|amp|lt|gt|#39|#x27|#34);",
        RegexOption.IGNORE_CASE,
    )
    private val gson = Gson()

    fun parse(html: String): WebNovel? {
        // Normally the payload is inside a <script> tag. Falling back to the
        // whole response also handles cached/proxy responses that stripped the
        // surrounding HTML tags.
        val scripts = scriptRegex.findAll(html)
            .map { it.groupValues[1] }
            .toList()
            .ifEmpty { listOf(html) }

        for (script in scripts) {
            parseScript(script)?.let { return it }
            // Some proxies HTML-escape the inline script. Try the decoded
            // variant only after the raw script so normal novel text keeps
            // its original characters.
            decodeHtmlEntities(script)
                .takeIf { it != script }
                ?.let { parseScript(it) }
                ?.let { return it }
        }
        return null
    }

    private fun decodeHtmlEntities(source: String): String =
        htmlEntityRegex.replace(source) { match ->
            when (match.groupValues[1].lowercase()) {
                "quot", "#34" -> "\""
                "apos", "#39", "#x27" -> "'"
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                else -> match.value
            }
        }

    private fun parseScript(script: String): WebNovel? {
        pixivMarker.find(script)?.let { marker ->
            val value = valueMarker.find(script, marker.range.last + 1)
                ?: return@let null
            parseValueExpression(script, skipWhitespace(script, value.range.last + 1))
                ?.let { return it }
        }

        // A few webview/proxy variants use a direct assignment instead of a
        // property descriptor.
        pixivAssignmentMarker.find(script)?.let { marker ->
            parseValueExpression(script, skipWhitespace(script, marker.range.last + 1))
                ?.let { return it }
        }

        // Some cached responses contain only the serialized pixiv object in a
        // JSON script block.
        val objectStart = skipWhitespace(script, 0)
        if (objectStart < script.length && script[objectStart] == '{') {
            parseObject(script, objectStart)?.let { return it }
        }
        return null
    }

    private fun parseValueExpression(source: String, start: Int): WebNovel? {
        if (start >= source.length) return null
        if (source[start] == '{') return parseObject(source, start)

        if (source.regionMatches(start, "JSON.parse", 0, "JSON.parse".length, ignoreCase = true)) {
            val openParen = source.indexOf('(', start + "JSON.parse".length)
            if (openParen < 0) return null
            val quoteStart = skipWhitespace(source, openParen + 1)
            if (quoteStart >= source.length || source[quoteStart] !in charArrayOf('\'', '"')) {
                return null
            }
            val quoteEnd = findStringEnd(source, quoteStart) ?: return null
            val literal = source.substring(quoteStart, quoteEnd + 1)
            val json = decodeJavaScriptString(literal) ?: return null
            val jsonStart = skipWhitespace(json, 0)
            return if (jsonStart < json.length && json[jsonStart] == '{') {
                parseObject(json, jsonStart)
            } else {
                null
            }
        }
        return null
    }

    private fun parseObject(source: String, start: Int): WebNovel? {
        val objectEnd = findJsonObjectEnd(source, start) ?: return null
        val json = normalizeJavaScriptJson(source.substring(start, objectEnd + 1))
        val root = parseJsonTree(json) ?: return null
        val novel = findNovelElement(root) ?: return null

        // Prefer the normal model conversion. If a new/optional Pixiv field
        // changes type, fall back to field-by-field conversion so `text` can
        // still be displayed instead of failing the entire novel.
        runCatching { gson.fromJson(novel, WebNovel::class.java) }
            .getOrNull()
            ?.let { return it }
        return parseWebNovelTolerantly(novel)
    }

    private fun parseJsonTree(json: String): JsonElement? = runCatching {
        // Pixiv's serialized object is JSON in current responses, but the
        // webview has historically emitted JavaScript-compatible details such
        // as comments, single-quoted strings, and trailing commas.
        val reader = JsonReader(StringReader(json))
        reader.setStrictness(Strictness.LENIENT)
        try {
            JsonParser.parseReader(reader)
        } finally {
            reader.close()
        }
    }.getOrNull()

    private fun findNovelElement(root: JsonElement): JsonElement? {
        if (!root.isJsonObject) return null
        val rootObject = root.asJsonObject
        rootObject["novel"]?.let { return it }
        rootObject["pixiv"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("novel")?.let { return it }
        return if (rootObject.has("id") || rootObject.has("text")) root else null
    }

    private fun parseWebNovelTolerantly(element: JsonElement): WebNovel? {
        if (!element.isJsonObject) return null
        val novelObject = element.asJsonObject
        return WebNovel(
            aiType = novelObject.readInt("aiType"),
            caption = novelObject.readString("caption"),
            coverUrl = novelObject.readString("coverUrl"),
            glossaryItems = novelObject.readAnyList("glossaryItems"),
            id = novelObject.readString("id"),
            text = novelObject.readString("text"),
            isOriginal = novelObject.readBoolean("isOriginal"),
            marker = novelObject.readObject("marker", NovelMarkerBean::class.java),
            illusts = novelObject.readMap("illusts", WebIllustHolder::class.java),
            images = novelObject.readMap("images", NovelImages::class.java),
            replaceableItemIds = novelObject.readAnyList("replaceableItemIds"),
            seriesId = novelObject.readString("seriesId"),
            seriesIsWatched = novelObject.readBoolean("seriesIsWatched"),
            seriesNavigation = novelObject.readObject("seriesNavigation", SeriesNavigation::class.java),
            seriesTitle = novelObject.readString("seriesTitle"),
            tags = novelObject.readStringList("tags"),
            title = novelObject.readString("title"),
            userId = novelObject.readString("userId"),
        )
    }

    private fun JsonElement.asPrimitiveString(): String? {
        if (!isJsonPrimitive) return null
        val primitive = asJsonPrimitive
        return runCatching { primitive.asString }.getOrNull()
    }

    private fun JsonElement.asPrimitiveBoolean(): Boolean? {
        if (!isJsonPrimitive || !asJsonPrimitive.isBoolean) return null
        return runCatching { asBoolean }.getOrNull()
    }

    private fun JsonElement.asPrimitiveInt(): Int? {
        if (!isJsonPrimitive || !asJsonPrimitive.isNumber) return null
        return runCatching { asInt }.getOrNull()
    }

    private fun JsonElement.asAnyValue(): Any? =
        runCatching { gson.fromJson(this, Any::class.java) }.getOrNull()

    private fun JsonObject.readString(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.asPrimitiveString()

    private fun JsonObject.readBoolean(name: String): Boolean? =
        get(name)?.takeUnless { it.isJsonNull }?.asPrimitiveBoolean()

    private fun JsonObject.readInt(name: String): Int? =
        get(name)?.takeUnless { it.isJsonNull }?.asPrimitiveInt()

    private fun JsonObject.readAnyList(name: String): List<Any?>? {
        val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
        if (!value.isJsonArray) return null
        return value.asJsonArray.map { it.asAnyValue() }
    }

    private fun JsonObject.readStringList(name: String): List<String?>? {
        val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
        if (!value.isJsonArray) return null
        return value.asJsonArray.map { it.takeUnless { item -> item.isJsonNull }?.asPrimitiveString() }
    }

    private fun <T> JsonObject.readObject(name: String, type: Class<T>): T? {
        val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
        if (!value.isJsonObject) return null
        return runCatching { gson.fromJson(value, type) }.getOrNull()
    }

    private fun <T> JsonObject.readMap(name: String, type: Class<T>): Map<String, T>? {
        val value = get(name)?.takeUnless { it.isJsonNull } ?: return null
        if (!value.isJsonObject) return null
        return value.asJsonObject.entrySet().mapNotNull { (key, item) ->
            runCatching { gson.fromJson(item, type) }
                .getOrNull()
                ?.let { key to it }
        }.toMap()
    }

    /** Removes JavaScript-only syntax while preserving text inside strings. */
    private fun normalizeJavaScriptJson(json: String): String {
        val result = StringBuilder(json.length)
        var stringQuote: Char? = null
        var escaped = false
        var index = 0
        while (index < json.length) {
            val c = json[index]
            if (stringQuote != null) {
                result.append(c)
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == stringQuote) stringQuote = null
                index++
                continue
            }
            if (c == '"' || c == '\'') {
                stringQuote = c
                result.append(c)
                index++
                continue
            }
            if (c == '/' && index + 1 < json.length && json[index + 1] == '/') {
                index += 2
                while (index < json.length && json[index] != '\n' && json[index] != '\r') index++
                continue
            }
            if (c == '/' && index + 1 < json.length && json[index + 1] == '*') {
                index += 2
                while (index + 1 < json.length && !(json[index] == '*' && json[index + 1] == '/')) index++
                index = (index + 2).coerceAtMost(json.length)
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

            if (json.startsWith("undefined", index) && isTokenBoundary(json, index - 1) &&
                isTokenBoundary(json, index + "undefined".length)
            ) {
                result.append("null")
                index += "undefined".length
                continue
            }
            result.append(c)
            index++
        }
        return result.toString()
    }

    private fun decodeJavaScriptString(literal: String): String? {
        if (literal.length < 2) return null
        val quote = literal.first()
        if (literal.last() != quote) return null
        if (quote == '"') {
            return runCatching { gson.fromJson(literal, String::class.java) }.getOrNull()
        }

        val result = StringBuilder(literal.length - 2)
        var index = 1
        while (index < literal.length - 1) {
            val c = literal[index]
            if (c != '\\') {
                result.append(c)
                index++
                continue
            }
            if (index + 1 >= literal.length - 1) return null
            when (val escaped = literal[index + 1]) {
                'b' -> result.append('\b')
                'f' -> result.append('\u000C')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    if (index + 5 >= literal.length) return null
                    val code = literal.substring(index + 2, index + 6).toIntOrNull(16) ?: return null
                    result.append(code.toChar())
                    index += 4
                }
                '\\', '\'', '"' -> result.append(escaped)
                '\n' -> Unit
                '\r' -> if (index + 2 < literal.length && literal[index + 2] == '\n') index++
                else -> result.append(escaped)
            }
            index += 2
        }
        return result.toString()
    }

    private fun findStringEnd(source: String, start: Int): Int? {
        val quote = source[start]
        var escaped = false
        for (index in start + 1 until source.length) {
            val c = source[index]
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == quote) {
                return index
            }
        }
        return null
    }

    private fun skipWhitespace(source: String, start: Int): Int {
        var index = start.coerceAtLeast(0)
        while (index < source.length && source[index].isWhitespace()) index++
        return index
    }

    private fun isTokenBoundary(source: String, index: Int): Boolean =
        index !in source.indices || !source[index].let { it.isLetterOrDigit() || it == '_' || it == '$' }

    /** Finds the matching closing brace while ignoring braces inside strings. */
    private fun findJsonObjectEnd(source: String, start: Int): Int? {
        var depth = 0
        var stringQuote: Char? = null
        var escaped = false
        for (index in start until source.length) {
            val c = source[index]
            if (stringQuote != null) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == stringQuote) stringQuote = null
                continue
            }
            when (c) {
                '"', '\'' -> stringQuote = c
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
