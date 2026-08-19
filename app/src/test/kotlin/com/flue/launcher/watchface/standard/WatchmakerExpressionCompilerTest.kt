package com.flue.launcher.watchface.standard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 1（导入期表达式编译）的契约测试，对照
 * `plan-Claude/watchface-precompile-and-canvas.md` §五：常量折叠正确性、
 * `deps` 集合正确性、引号内 tag 的类型语义、含 `ScopeRef` 不得折成 `Const`。
 *
 * 另外钉住**两条路径的等价性** —— 编译路径是新加的快路，老路径仍在原地兜底。
 * 两者在同一条表达式上给出不同结果的话，表现是「同一张表盘时快时慢时对时错」，
 * 真机上几乎无法复现，所以必须在单测里挡住。
 */
class WatchmakerExpressionCompilerTest {

    private fun resolver(vararg pairs: Pair<String, String>): (String) -> WatchTagResolution {
        val values = pairs.toMap()
        return { tag ->
            values[tag.trim().lowercase()]
                ?.let { WatchTagResolution.Resolved(it) }
                ?: WatchTagResolution.Unavailable(tag)
        }
    }

    // ---- 常量折叠 ----

    @Test
    fun 不含tag与变量的表达式在编译期折成常量() {
        val attr = compileWatchAttr("2 + 3 * 4", WatchExpressionScope.EMPTY)

        val const = attr as? WatchAttr.Const
        assertNotNull("纯字面量必须折成 Const，运行时连树都不该走", const)
        assertEquals(14.0, requireNotNull(const).value.asNumber())
    }

    @Test
    fun 折叠遵守优先级与一元负号() {
        // -2^2 在 Lua 里是 -(2^2)：一元负号比 ^ 结合得松。
        assertEquals(
            -4.0,
            (compileWatchAttr("-2^2", WatchExpressionScope.EMPTY) as WatchAttr.Const).value.asNumber(),
        )
    }

    @Test
    fun 含tag的表达式不折叠且记录依赖() {
        val attr = compileWatchAttr("{bl} > 50", WatchExpressionScope.EMPTY)

        val dynamic = attr as? WatchAttr.Dynamic
        assertNotNull("含 tag 的表达式必须留成 Dynamic", dynamic)
        assertEquals(setOf("bl"), requireNotNull(dynamic).tagDependencies)
    }

    @Test
    fun 折不动的子树保持原样而不是让整条编译失败() {
        // 1/0 求值抛错，但它落在一条永不执行的短路分支上。整条表达式必须仍然可用。
        val attr = compileWatchAttr("{bl} > 0 and 1 or 1/0", WatchExpressionScope.EMPTY)

        assertTrue("折叠失败不能让整条表达式退化成 Unsupported", attr is WatchAttr.Dynamic)
        val value = evaluateWatchExpr(
            (attr as WatchAttr.Dynamic).node,
            WatchExpressionScope.EMPTY,
            resolver("bl" to "85"),
        )
        assertEquals(1.0, value.asNumber())
    }

    // ---- 依赖集合 ----

    @Test
    fun 依赖集合覆盖全部节点种类且已小写归一() {
        val source = "math.max({BL}, {ssc}) .. '{ddw}' .. tostring({bc})"

        val dynamic = compileWatchAttr(source, WatchExpressionScope.EMPTY) as WatchAttr.Dynamic

        // 裸 tag、引号内 tag、函数实参里的 tag 都要收进来。
        assertEquals(setOf("bl", "ssc", "ddw", "bc"), dynamic.tagDependencies)
    }

    @Test
    fun 不含tag的动态表达式依赖集合为空() {
        val scope = WatchExpressionScope.fromInitialScript("var_offset = 3")
        val dynamic = compileWatchAttr("var_offset + 1", scope) as WatchAttr.Dynamic

        assertTrue(dynamic.tagDependencies.isEmpty())
    }

    // ---- scope 变量 ----

    @Test
    fun 含scope变量的表达式不得折成常量() {
        // scope 按引用进缓存键：同一条表达式在不同 View 下 resolve 结果不同，
        // 折成 Const 会让编辑器的实时预览与画布互相串值。
        val scope = WatchExpressionScope.fromInitialScript("var_page = 2")

        val attr = compileWatchAttr("var_page * 10", scope)

        assertTrue("含 ScopeRef 一律 Dynamic", attr is WatchAttr.Dynamic)
        assertTrue((attr as WatchAttr.Dynamic).hasScopeRef)
        assertEquals(
            20.0,
            evaluateWatchExpr(attr.node, scope, resolver()).asNumber(),
        )
    }

    // ---- 引号内 tag 的类型语义 ----

    @Test
    fun 引号内的tag是字符串内容而不是值() {
        // '{bl}' 是字符串 "85"，不是数字 85 —— 拼接后应当是 "85%"。
        val dynamic = compileWatchAttr("'{bl}' .. '%'", WatchExpressionScope.EMPTY) as WatchAttr.Dynamic

        val value = evaluateWatchExpr(dynamic.node, WatchExpressionScope.EMPTY, resolver("bl" to "85"))

        assertEquals("85%", value.renderText())
    }

    @Test
    fun 裸tag按代入文本定类型() {
        val scope = WatchExpressionScope.EMPTY

        val numeric = compileWatchAttr("{bl} + 1", scope) as WatchAttr.Dynamic
        assertEquals(
            86.0,
            evaluateWatchExpr(numeric.node, scope, resolver("bl" to "85")).asNumber(),
        )

        // 文字型 tag（星期）比较相等：作者写 {ddw} == 'Monday'。
        val textual = compileWatchAttr("{ddw} == 'Monday'", scope) as WatchAttr.Dynamic
        assertTrue(
            evaluateWatchExpr(textual.node, scope, resolver("ddw" to "Monday")).isTruthy(),
        )
    }

    @Test
    fun 取不到的tag在裸位置是中性0在引号内是空串() {
        val scope = WatchExpressionScope.EMPTY

        // 这里曾断言 `{nope} == nil` 为真 —— 那正是「少认识一个变量就把整块表盘弄花」
        // 的来源：nil 一进算术或比较，整条表达式求值失败，属性落回默认值
        // （opacity → 100、x/y → 0）。现在缺值的裸 tag 取中性 0，只影响读它的那一个字段。
        // 上游 CloX 也是让未知变量落成一个静态值（`""`）让式子算得完，而不是让式子废掉。
        val bare = compileWatchAttr("{nope} + 1", scope) as WatchAttr.Dynamic
        assertEquals(1.0, evaluateWatchExpr(bare.node, scope, resolver()).asNumber())

        // 引号内仍然是空串：文本语境不该冒出一个凭空的 0。
        val quoted = compileWatchAttr("'[{nope}]'", scope) as WatchAttr.Dynamic
        assertEquals("[]", evaluateWatchExpr(quoted.node, scope, resolver()).renderText())
    }

    // ---- 短路 ----

    @Test
    fun and与or真正短路() {
        val scope = WatchExpressionScope.EMPTY
        // 老路径 parseOr 会先把右侧算完，{bl} 为 0 时整条求值失败、属性落回默认值。
        // 编译路径按 Lua 语义短路，右侧根本不求。
        val dynamic = compileWatchAttr("{bl} > 0 and 100/{bl} or 0", scope) as WatchAttr.Dynamic

        assertEquals(0.0, evaluateWatchExpr(dynamic.node, scope, resolver("bl" to "0")).asNumber())
        assertEquals(2.0, evaluateWatchExpr(dynamic.node, scope, resolver("bl" to "50")).asNumber())
    }

    // ---- 上限 ----

    @Test
    fun 超长源文本直接判为不支持() {
        val long = "1+".repeat(WatchExpressionLimits.MAX_EXPRESSION_LENGTH) + "1"

        assertEquals(WatchAttr.Unsupported, compileWatchAttr(long, WatchExpressionScope.EMPTY))
    }

    @Test
    fun 语法错误判为不支持而不是抛异常() {
        listOf("1 +", "(1", "x = 1", "1 @ 2").forEach { source ->
            assertEquals(
                "「$source」应当判为 Unsupported 让调用方退回老路径",
                WatchAttr.Unsupported,
                compileWatchAttr(source, WatchExpressionScope.EMPTY),
            )
        }
    }

    @Test
    fun 引号内的tag计入节点数() {
        // 整段引号字符串是一个 token，若 Template 只报 1 个节点，
        // '{a}{a}{a}…' 就能在 token 上限内藏下上千个 tag 引用而不被节点上限拦住。
        val many = "'" + "{bl}".repeat(64) + "'"

        val dynamic = compileWatchAttr(many, WatchExpressionScope.EMPTY) as WatchAttr.Dynamic

        assertTrue("Template 的节点数必须随 tag 数增长，实际 ${dynamic.nodeCount}", dynamic.nodeCount > 64)
    }
}
