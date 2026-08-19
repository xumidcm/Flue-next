package com.flue.launcher.watchface.standard

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 词表覆盖：Studio 认识的每一个变量，我们也必须认识。
 *
 * ## 为什么这条测试值得单独存在
 *
 * `WatchmakerTagResolver.isKnown` 返回 false 的变量，在表达式里会被代入成
 * `nil`（见 `WatchmakerRuntimeContracts` 里 `expressionValue()` 的
 * `Unsupported -> null`，以及代入点的 `?: "nil"`）。一条表达式只要含 nil 就整条
 * 求值失败，属性**落回默认值** —— opacity 落回 100、x/y 落回 0。于是「少认识一个
 * 变量」的观感不是那一个字段显示异常，而是**整块表盘看起来加载不了**：图层全亮
 * 叠在一起，或者全部堆到左上角。老师转述的用户反馈正是这个。
 *
 * 认识 ≠ 有数据。没有数据源的变量走 `Unavailable` 渲染成 `--`，表盘照样加载、
 * 其余图层照样正确 —— 这才是缺数据应有的降级。所以这条测试只管**词表完整**，
 * 不管有没有 provider。
 *
 * 词表来自上游 Studio 自己的清单（见资源文件头部的出处与 commit），是「表盘会用到
 * 哪些变量」的权威上界；不从我们自己的实现里反推，否则测试只会自我确认。
 */
class WatchmakerStudioTagVocabularyTest {

    @Test
    fun `every studio tag is known to the resolver`() {
        val tags = loadStudioTags()
        // 词表本身别悄悄变空 —— 那样这条测试会假装通过。
        assertTrue("词表没读到，检查资源是否被打进 test resources", tags.size > 400)

        val missing = tags.filterNot(WatchmakerTagResolver::isKnown)
        assertTrue(
            buildString {
                append("有 ").append(missing.size).append(" / ").append(tags.size)
                append(" 个 Studio 变量不在词表里，含它们的表达式会整条失败、属性落回默认值：\n")
                missing.chunked(8).forEach { row -> append("  ").append(row.joinToString(" ")).append('\n') }
            },
            missing.isEmpty(),
        )
    }

    private fun loadStudioTags(): List<String> {
        val stream = javaClass.getResourceAsStream(RESOURCE)
            ?: error("找不到测试资源 $RESOURCE")
        return stream.bufferedReader().useLines { lines ->
            lines.map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .distinct()
                .toList()
        }
    }

    private companion object {
        const val RESOURCE = "/watchmaker-studio-tags.txt"
    }
}
