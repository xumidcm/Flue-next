package com.flue.launcher.watchface.standard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WatchExpressionCompileReport.tagDependenciesOf] —— 阶段 3 可烘判定的安全底线。
 *
 * 它要回答的问题只有一个：**这条属性到底依不依赖 tag**。答不上来时必须说「不知道」
 * 而不是「不依赖」，因为调用方会把「不依赖」当成可以烘：
 *
 * - 判成「不依赖」而实际依赖 `{dss}` → 图层被烘死，永久冻在某一帧（用户可见的 bug）
 * - 判成「不知道」而实际不依赖 → 少烘一段，只损失一点性能
 *
 * 两者代价不对等，所以全部往严的一侧倒。
 */
class WatchmakerCompileReportDependencyTest {

    private fun reportOf(vararg entries: Pair<String, Set<String>>) =
        WatchExpressionCompileReport(tagDependencies = entries.toMap())

    @Test
    fun `命中的表达式返回它的依赖`() {
        val report = reportOf("{dm}" to setOf("dm"))
        assertEquals(setOf("dm"), report.tagDependenciesOf("{dm}"))
    }

    @Test
    fun `查表要按 trim 之后的形式`() {
        // precompile 存的键是 trim() 之后的原文。属性值带前后空格时直接拿原文查
        // 会 miss —— 而 miss 若退化成空集，这层就被当成常量烘死了。
        val report = reportOf("{dss}" to setOf("dss"))
        assertEquals(setOf("dss"), report.tagDependenciesOf("  {dss}  "))
    }

    @Test
    fun `实体解码后的形式也能命中`() {
        // precompile 对每条源文本同时热了原文与 &quot;/&apos; 解码后的形式。
        val report = reportOf("""x == "a"""" to setOf("dm"))
        assertEquals(setOf("dm"), report.tagDependenciesOf("x == &quot;a&quot;"))
    }

    @Test
    fun `不含 tag 标记的属性是货真价实的空集`() {
        // 常量属性（WatchAttr.Const）不进表，但它确实没有依赖 —— 可以烘。
        val report = reportOf()
        assertEquals(emptySet<String>(), report.tagDependenciesOf("120"))
        assertEquals(emptySet<String>(), report.tagDependenciesOf("center"))
    }

    @Test
    fun `空属性是空集`() {
        val report = reportOf()
        assertEquals(emptySet<String>(), report.tagDependenciesOf(""))
        assertEquals(emptySet<String>(), report.tagDependenciesOf("   "))
    }

    @Test
    fun `含 tag 标记却查不到就是依赖不明`() {
        // 编译失败（WatchAttr.Unsupported）只进计数不进表。它的 {tag} 走老的代入
        // 路径，这里根本看不见 —— 必须返回 null 让调用方当作不可烘。
        val report = reportOf()
        assertNull(report.tagDependenciesOf("{dss} + 不支持的写法"))
    }

    @Test
    fun `依赖不明与无依赖必须能区分开`() {
        // 这一条是整个方法存在的理由：两种情况若都返回空集，调用方无从分辨，
        // 只能一律当可烘 —— 于是依赖 {dss} 的图层被烘死冻住。
        val report = reportOf()
        assertNull(report.tagDependenciesOf("{dss}"))
        assertTrue(report.tagDependenciesOf("100")!!.isEmpty())
    }

    @Test
    fun `referencedTags 汇总全部条目`() {
        val report = reportOf(
            "{dm}" to setOf("dm"),
            "{bl} + {dm}" to setOf("bl", "dm"),
        )
        assertEquals(setOf("dm", "bl"), report.referencedTags)
    }
}
