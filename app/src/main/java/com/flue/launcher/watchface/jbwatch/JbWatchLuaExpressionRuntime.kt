package com.flue.launcher.watchface.jbwatch

import com.flue.launcher.watchface.standard.WatchFaceLuaRuntime
import com.flue.launcher.watchface.standard.WatchTagResolution
import java.util.Locale

/**
 * Lua expression adapter for JBWatch layer attributes.
 *
 * JBWatch exports Lua expressions inside XML attributes rather than as a
 * separate face script.  Keep ordinary labels on the legacy path and only
 * claim expressions that contain an unmistakable Lua operation.
 */
internal class JbWatchLuaExpressionRuntime private constructor(
    private val runtime: WatchFaceLuaRuntime,
) : AutoCloseable {
    private var closed = false

    fun evaluateText(
        expression: String,
        resolver: (String) -> WatchTagResolution,
    ): String? = evaluateIfCandidate(expression) {
        runtime.evaluateText(spliceJbAliases(expression, resolver), resolver)
    }

    fun evaluateNumber(
        expression: String,
        resolver: (String) -> WatchTagResolution,
    ): Double? = evaluateIfCandidate(expression) {
        runtime.evaluateNumber(spliceJbAliases(expression, resolver), resolver).takeIf(Double::isFinite)
    }

    fun evaluateBoolean(
        expression: String,
        resolver: (String) -> WatchTagResolution,
    ): Boolean? = evaluateIfCandidate(expression) {
        runtime.evaluateText(spliceJbAliases(expression, resolver), resolver)?.let { rendered ->
            when {
                rendered.equals("true", ignoreCase = true) -> true
                rendered.equals("false", ignoreCase = true) -> false
                rendered.toDoubleOrNull() != null -> rendered.toDouble() != 0.0
                else -> null
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runtime.close()
    }

    private fun <T> evaluateIfCandidate(expression: String, block: () -> T?): T? {
        if (closed) return null
        if (!looksLikeLuaExpression(expression)) return null
        return runCatching { block() }.getOrNull()
    }

    /** Add JB-only date aliases without rewriting Lua table constructors. */
    private fun spliceJbAliases(
        source: String,
        resolver: (String) -> WatchTagResolution,
    ): String {
        if ('{' !in source) return source
        val output = StringBuilder(source.length + 16)
        var index = 0
        var quote: Char? = null
        while (index < source.length) {
            val char = source[index]
            if (quote != null) {
                when {
                    char == '\\' && index + 1 < source.length -> {
                        output.append(char).append(source[index + 1])
                        index += 2
                    }
                    char == '{' -> {
                        val end = source.indexOf('}', index + 1)
                        val name = source.substringOrNull(index + 1, end)?.trim()?.lowercase()
                        if (name in JB_ALIAS_TAGS) {
                            val value = resolver(name.orEmpty()).renderedValue.quoteLua()
                            output.append(quote)
                                .append(" .. ")
                                .append(value)
                                .append(" .. ")
                                .append(quote)
                            index = end + 1
                        } else {
                            output.append(char)
                            index++
                        }
                    }
                    else -> {
                        output.append(char)
                        if (char == quote) quote = null
                        index++
                    }
                }
            } else if (char == '\'' || char == '"') {
                quote = char
                output.append(char)
                index++
            } else if (char == '{') {
                val end = source.indexOf('}', index + 1)
                val name = source.substringOrNull(index + 1, end)?.trim()?.lowercase()
                if (name in JB_ALIAS_TAGS) {
                    output.append(resolver(name.orEmpty()).renderedValue.quoteLua())
                    index = end + 1
                } else {
                    output.append(char)
                    index++
                }
            } else {
                output.append(char)
                index++
            }
        }
        return output.toString()
    }

    companion object {
        private val LUA_KEYWORD_PATTERN = Regex("\\b(?:and|or|not|true|false|nil)\\b")
        private val LUA_LIBRARY_PATTERN = Regex("\\b(?:math|string|table)\\s*\\.")
        private val LUA_STRUCTURE_PATTERN = Regex("[\\{\\}+*/%<>=~'\"0-9-]")
        private val JB_ALIAS_TAGS = setOf("time", "ddm", "dmm")

        fun create(): JbWatchLuaExpressionRuntime = JbWatchLuaExpressionRuntime(
            WatchFaceLuaRuntime.create(initialScript = null),
        )

        internal fun looksLikeLuaExpression(raw: String): Boolean {
            val expression = raw.trim()
            if (expression.isBlank()) return false
            if (expression.matches(Regex("\\{[^\\{\\}]+\\}"))) return false
            if (expression.contains("..")) return true
            if (LUA_LIBRARY_PATTERN.containsMatchIn(expression)) return true
            if ('{' in expression && expression.length >= 2 &&
                expression.first() == expression.last() && expression.first() in charArrayOf('\'', '"')
            ) return true
            if ('{' in expression && Regex("(?:[+*/%<>=~()-])").containsMatchIn(expression)) return true
            if (LUA_KEYWORD_PATTERN.containsMatchIn(expression) &&
                LUA_STRUCTURE_PATTERN.containsMatchIn(expression)
            ) return true
            return false
        }

        private fun String.quoteLua(): String = buildString(length + 2) {
            append('\'')
            for (char in this@quoteLua) {
                when (char) {
                    '\\' -> append("\\\\")
                    '\'' -> append("\\'")
                    else -> append(char)
                }
            }
            append('\'')
        }

        private fun String.substringOrNull(startIndex: Int, endIndex: Int): String? =
            if (endIndex >= startIndex) substring(startIndex, endIndex) else null
    }
}
