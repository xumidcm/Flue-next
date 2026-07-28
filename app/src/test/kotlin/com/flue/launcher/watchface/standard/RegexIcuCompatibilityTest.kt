package com.flue.launcher.watchface.standard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Android 14+ (SDK 34) 把 `java.util.regex` 的后端换成了 ICU
 * (`com.android.icu.util.regex.PatternNative`). ICU 比旧的 Harmony 引擎严格：
 *
 *  * `}` 是元字符，作为字面量时**必须**转义 —— 旧引擎容忍裸 `}`。
 *  * 字符组内的 `{` 会被当成 UnicodeSet 的多字符串起始符 (`[{abc}]`)，
 *    所以 `[^{}]` 这种写法也不安全。
 *
 * 违规的后果远比"匹配不到"严重：这些 `Regex` 大多是 `companion object` /
 * 顶层 `private val` 字段，编译失败会在类初始化时抛
 * `ExceptionInInitializerError`，**整个类无法加载**。旧版本表盘编辑器
 * 与表盘下载就是这样被打挂的：
 *
 * ```
 * java.lang.ExceptionInInitializerError
 *   at ...WatchEditorActivityKt.WatchEditorPreview$lambda$7$0$0(WatchEditorActivity.kt:1144)
 * Caused by: java.util.regex.PatternSyntaxException:
 *   Syntax error in regexp pattern near index 9
 *   \{[^{}]+}
 *   at ...WatchmakerWatchFaceView.<clinit>(WatchmakerWatchFaceView.kt:1822)
 * ```
 *
 * 桌面 JVM 用的仍是宽松引擎，`Pattern.compile("\\{[^{}]+}")` 不会报错，
 * 所以"把每个正则编译一遍"的测试**抓不到**这个回归。只能扫源码。
 */
class RegexIcuCompatibilityTest {

    @Test
    fun `no regex literal relies on lenient brace handling`() {
        val root = resolveMainSourceRoot()
        val violations = mutableListOf<String>()

        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readText().lineSequence().forEachIndexed { index, line ->
                    extractRegexPatterns(line).forEach { pattern ->
                        val problem = firstBraceProblem(pattern)
                        if (problem != null) {
                            violations += "${file.relativeToRootOrSelf(root)}:${index + 1}  $problem  →  $pattern"
                        }
                    }
                }
            }

        assertTrue(
            buildString {
                appendLine("发现 ${violations.size} 处 ICU 不兼容的正则字面量。")
                appendLine("ICU (Android 14+) 要求 '}' 必须转义为 '\\}'，字符组内的 '{' 也要写成 '\\{'。")
                appendLine("这些正则位于类初始化路径上时会抛 ExceptionInInitializerError，整个类不可用。")
                appendLine()
                violations.forEach { appendLine("  $it") }
            },
            violations.isEmpty(),
        )
    }

    @Test
    fun `detector flags the exact pattern that crashed beta1_5`() {
        // 回归哨兵：如果检测逻辑本身被削弱，这条会先失败。
        assertTrue(firstBraceProblem("""\{[^{}]+}""") != null)
        assertTrue(firstBraceProblem("""\{([^{}]+)}""") != null)
        assertTrue(firstBraceProblem("""(?:}\s*-\s*\{)""") != null)

        // 修复后的写法必须通过。
        assertTrue(firstBraceProblem("""\{[^\{\}]+\}""") == null)
        assertTrue(firstBraceProblem("""\{([^\{\}]+)\}""") == null)
        // 合法的区间量词不应被误报。
        assertTrue(firstBraceProblem("""[0-9a-fA-F]{32}""") == null)
        assertTrue(firstBraceProblem("""watch_([0-9a-f]{32}|[0-9a-f]{64})""") == null)
        assertTrue(firstBraceProblem("""\d{1,3}""") == null)
        assertTrue(firstBraceProblem("""\w{2,}""") == null)
    }

    private companion object {

        fun resolveMainSourceRoot(): File =
            sequenceOf(
                File("src/main"),
                File("app/src/main"),
                File("../app/src/main"),
            ).firstOrNull { it.isDirectory }
                ?: error("找不到 src/main —— 工作目录是 ${File(".").absolutePath}")

        fun File.relativeToRootOrSelf(root: File): String =
            runCatching { relativeTo(root.parentFile ?: root).path }.getOrDefault(path)

        /**
         * 抽出传给 `Regex(...)` 的字符串字面量，并把 Kotlin 的转义还原成正则引擎
         * 真正看到的文本（`\\{` → `\{`）。原始字符串 `"""..."""` 不做还原。
         */
        fun extractRegexPatterns(line: String): List<String> {
            val out = mutableListOf<String>()

            // Regex("""...""")  —— 原始字符串，内容即模式
            Regex("""Regex\(\s*"""" + "\"\"" + """(.*?)"""" + "\"\"").findAll(line).forEach {
                out += it.groupValues[1]
            }

            // Regex("...")  —— 需要把 Kotlin 转义还原一层
            Regex("""Regex\(\s*"((?:[^"\\]|\\.)*)"""").findAll(line).forEach {
                out += unescapeKotlin(it.groupValues[1])
            }
            return out
        }

        fun unescapeKotlin(literal: String): String {
            val sb = StringBuilder(literal.length)
            var i = 0
            while (i < literal.length) {
                val c = literal[i]
                if (c == '\\' && i + 1 < literal.length) {
                    when (val next = literal[i + 1]) {
                        '\\' -> sb.append('\\')
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        '"' -> sb.append('"')
                        else -> sb.append('\\').append(next)
                    }
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        /**
         * 返回第一处 ICU 下会报错的花括号用法，没有问题时返回 null。
         *
         * 规则：
         *  * 字符组内出现未转义的 `{` 或 `}` → 违规（UnicodeSet 会当成字符串字面量）。
         *  * 字符组外的 `{` 必须构成合法区间量词 `{n}` / `{n,}` / `{n,m}`。
         *  * 字符组外出现未被量词消费的 `}` → 违规。
         */
        fun firstBraceProblem(pattern: String): String? {
            var i = 0
            var inClass = false
            while (i < pattern.length) {
                when (val c = pattern[i]) {
                    '\\' -> {
                        i += 2      // 转义序列整体跳过
                        continue
                    }

                    '[' -> if (!inClass) inClass = true
                    ']' -> if (inClass) inClass = false

                    '{' -> {
                        if (inClass) return "字符组内未转义的 '{'（ICU 会解析成 UnicodeSet 字符串）"
                        val close = quantifierEnd(pattern, i)
                            ?: return "位置 $i 的 '{' 不构成合法区间量词，需写成 '\\{'"
                        i = close      // 跳过整个 {n,m}，其结尾的 '}' 已被消费
                    }

                    '}' -> {
                        return if (inClass) {
                            "字符组内未转义的 '}'"
                        } else {
                            "位置 $i 出现未转义的 '}'，ICU 下必须写成 '\\}'"
                        }
                    }

                    else -> Unit.also { @Suppress("UNUSED_EXPRESSION") c }
                }
                i++
            }
            return null
        }

        /** `{` 起始的合法区间量词的结束下标（指向 `}`）；不合法返回 null。 */
        fun quantifierEnd(pattern: String, start: Int): Int? {
            var i = start + 1
            var digits = 0
            while (i < pattern.length && pattern[i].isDigit()) { i++; digits++ }
            if (digits == 0) return null
            if (i < pattern.length && pattern[i] == ',') {
                i++
                while (i < pattern.length && pattern[i].isDigit()) i++
            }
            return if (i < pattern.length && pattern[i] == '}') i else null
        }
    }
}
