package com.flue.launcher.watchface.standard

import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 属性表达式的**导入期编译**路径。
 *
 * 对照参考实现 clox 的 `wf_expr.lua:11`：
 *
 * > THIS FILE IS THE ONLY PLACE ATTRIBUTE STRINGS ARE PATTERN-MATCHED. The
 * > functions it produces do zero gsub/find/match at runtime. Import is allowed
 * > to be slow; the running face is not.
 *
 * 老路径（`WatchmakerExpressionEvaluator.Parser`）每帧要做三件事：把 tag 代入
 * 文本（建 StringBuilder、逐字符抄一遍）、词法、递归下降**边解析边求值**。一张
 * 377 图层的表盘每帧求值 276 条表达式，30fps 下就是每秒近万次完整解析。
 *
 * 这里换成：导入时编译成 [WatchExprNode] 树并记录它依赖了哪些 tag，运行时只
 * 走一遍树。副产物是 [WatchAttr.Dynamic.tagDependencies] —— 阶段 2 的 tag 分桶
 * 推模型要靠它才知道「哪条表达式该在哪个时机重算」。
 *
 * **老路径一行都不删**：它既是编译失败时的兜底，也是整套改动的回滚开关。
 */

/**
 * 表达式引擎的共用上限。
 *
 * 单独抽出来是因为现在有**两条**求值路径。两条路径的限额必须逐个相等，否则会
 * 出现「编译路径拒了、老路径收了」这种同一条表达式在两次渲染之间行为不同的
 * 情况 —— 那种 bug 在真机上几乎无法复现。各项数值的来历见
 * `WatchmakerExpressionEvaluator` 里同名常量的 KDoc。
 */
internal object WatchExpressionLimits {
    const val MAX_EXPRESSION_LENGTH = 32 * 1024
    const val MAX_EXPRESSION_TOKENS = 8 * 1024
    const val MAX_FUNCTION_ARGUMENTS = 16
    const val MAX_RESULT_TEXT_LENGTH = 4 * 1024
    const val MAX_PARSE_DEPTH = 256
}

/**
 * 表达式的运行时值。
 *
 * 从 `WatchmakerExpressionEvaluator` 内部提到顶层（`private` → `internal`），
 * 原因是下面的 Lua 标准库与 AST 求值器都要用它，而两者不在同一个对象里。
 * 语义逐字未改。
 */
internal sealed interface ExpressionValue {
    fun asNumber(): Double?
    fun isTruthy(): Boolean
    fun renderText(): String

    data class Numeric(val value: Double) : ExpressionValue {
        override fun asNumber(): Double = value
        override fun isTruthy(): Boolean = true
        override fun renderText(): String = when {
            value.isFinite() && value == floor(value) && abs(value) <= 9_007_199_254_740_992.0 ->
                value.toLong().toString()
            value.isFinite() -> String.format(Locale.ROOT, "%.6g", value)
            else -> ""
        }
    }

    data class Text(val value: String) : ExpressionValue {
        override fun asNumber(): Double? = value.toDoubleOrNull()
        override fun isTruthy(): Boolean = true
        override fun renderText(): String = value
    }

    data class Logical(val value: Boolean) : ExpressionValue {
        override fun asNumber(): Double = if (value) 1.0 else 0.0
        override fun isTruthy(): Boolean = value
        override fun renderText(): String = value.toString()
    }

    data object Nil : ExpressionValue {
        override fun asNumber(): Double? = null
        override fun isTruthy(): Boolean = false
        override fun renderText(): String = ""
    }
}

/**
 * WatchMaker 表达式可用的 Lua 内建子集。
 *
 * **两条求值路径共用这一份实现**，这是刻意的：`string.format` 的手写格式串
 * 解析、`string.sub` 的 Lua 负索引语义这些东西各写一遍，迟早会在某个边角上
 * 不一致，而那种差异表现为「同一张表盘在编译成功与失败时显示不同的文字」，
 * 极难定位。所以老 `Parser` 也改成调用这里，函数体是从它那儿**逐字搬过来**的。
 *
 * 引擎是纯函数：没有 random、没有时间函数，全部外部输入都经 tag 进来。求值
 * 缓存能成立就靠这条性质，别往这里加带状态的内建。
 */
internal object WatchLuaStdlib {

    fun numericValue(value: Double): ExpressionValue.Numeric {
        require(value.isFinite()) { "Expression result is not finite" }
        return ExpressionValue.Numeric(value)
    }

    /** scope 变量的字面量按文本推断类型，与 tag 代入后的推断规则一致。 */
    fun toScopedExpressionValue(raw: String): ExpressionValue = when {
        raw.equals("true", true) -> ExpressionValue.Logical(true)
        raw.equals("false", true) -> ExpressionValue.Logical(false)
        raw == "nil" -> ExpressionValue.Nil
        raw.toDoubleOrNull()?.isFinite() == true -> ExpressionValue.Numeric(raw.toDouble())
        else -> ExpressionValue.Text(raw)
    }

    fun callFunction(name: String, arguments: List<ExpressionValue>): ExpressionValue {
        fun arg(index: Int, fallback: ExpressionValue = ExpressionValue.Numeric(0.0)): ExpressionValue =
            arguments.getOrElse(index) { fallback }
        fun number(index: Int, fallback: Double = 0.0): Double =
            arguments.getOrNull(index)?.asNumber() ?: fallback
        fun text(index: Int): String = arguments.getOrNull(index)?.renderText().orEmpty()

        return when (name) {
            "math.floor" -> numericValue(floor(number(0)))
            "math.ceil" -> numericValue(ceil(number(0)))
            "math.abs" -> numericValue(abs(number(0)))
            "math.sqrt" -> numericValue(sqrt(number(0)))
            "math.max" -> numericValue(arguments.maxOfOrNull { it.asNumber() ?: 0.0 } ?: 0.0)
            "math.min" -> numericValue(arguments.minOfOrNull { it.asNumber() ?: 0.0 } ?: 0.0)
            "math.mod", "math.fmod" -> {
                val divisor = number(1, 1.0)
                require(abs(divisor) > Double.MIN_VALUE) { "Division by zero" }
                numericValue(number(0) % divisor)
            }
            "math.pow" -> numericValue(number(0, 1.0).pow(number(1, 1.0)))
            "math.sin" -> numericValue(sin(number(0)))
            "math.cos" -> numericValue(cos(number(0)))
            "math.tan" -> numericValue(tan(number(0)))
            "math.asin" -> numericValue(asin(number(0)))
            "math.acos" -> numericValue(acos(number(0)))
            "math.atan" -> if (arguments.size >= 2) {
                numericValue(atan2(number(0), number(1)))
            } else {
                numericValue(atan(number(0)))
            }
            "math.atan2" -> numericValue(atan2(number(0), number(1, 1.0)))
            "math.rad" -> numericValue(Math.toRadians(number(0)))
            "math.deg" -> numericValue(Math.toDegrees(number(0)))
            "math.exp" -> numericValue(exp(number(0)))
            "math.log" -> {
                val value = number(0)
                require(value > 0.0) { "Logarithm input must be positive" }
                val base = arguments.getOrNull(1)?.asNumber()
                numericValue(if (base == null) ln(value) else ln(value) / ln(base))
            }
            "string.len" -> numericValue(text(0).length.toDouble())
            "string.upper" -> ExpressionValue.Text(text(0).uppercase(Locale.ROOT))
            "string.lower" -> ExpressionValue.Text(text(0).lowercase(Locale.ROOT))
            "string.sub" -> ExpressionValue.Text(
                luaSubstring(text(0), number(1, 1.0), arguments.getOrNull(2)?.asNumber()),
            )
            "string.rep" -> {
                val count = number(1).toInt()
                    .coerceIn(0, WatchExpressionLimits.MAX_RESULT_TEXT_LENGTH)
                val unit = text(0)
                require(unit.length.toLong() * count <= WatchExpressionLimits.MAX_RESULT_TEXT_LENGTH) {
                    "Repeated string is too large"
                }
                ExpressionValue.Text(unit.repeat(count))
            }
            "string.find" -> {
                val start = luaStringIndex(number(2, 1.0).toInt(), text(0).length)
                val found = text(0).indexOf(text(1), startIndex = start.coerceAtMost(text(0).length))
                if (found < 0) ExpressionValue.Nil else numericValue((found + 1).toDouble())
            }
            "string.format" -> ExpressionValue.Text(formatLuaString(text(0), arguments.drop(1)))
            "tonumber" -> arg(0).asNumber()?.let(::numericValue) ?: ExpressionValue.Nil
            "tostring" -> ExpressionValue.Text(arg(0, ExpressionValue.Nil).renderText())
            "type" -> ExpressionValue.Text(
                when (arg(0, ExpressionValue.Nil)) {
                    is ExpressionValue.Numeric -> "number"
                    is ExpressionValue.Text -> "string"
                    is ExpressionValue.Logical -> "boolean"
                    ExpressionValue.Nil -> "nil"
                },
            )
            else -> error("Unsupported function: $name")
        }
    }

    private fun luaSubstring(value: String, fromRaw: Double, toRaw: Double?): String {
        val length = value.length
        val from = luaStringIndex(fromRaw.toInt(), length)
        val inclusiveTo = toRaw?.toInt()?.let { index ->
            if (index < 0) length + index else index - 1
        } ?: (length - 1)
        val endExclusive = (inclusiveTo + 1).coerceIn(0, length)
        return if (from < endExclusive) value.substring(from, endExclusive) else ""
    }

    private fun luaStringIndex(raw: Int, length: Int): Int =
        (if (raw < 0) length + raw else raw - 1).coerceIn(0, length)

    /**
     * 比较运算。两条路径共用：老 `Parser` 把它的 `TokenType` 映射成
     * [WatchCompareOp] 再调这里。
     *
     * `==` / `~=` 的类型规则不是 Lua 原味（Lua 里 `1 == "1"` 为假），而是
     * WatchMaker 的：**能当数字比就按数字比**。表盘作者大量写
     * `{wci} == 1` 而 `{wci}` 代入后是字符串，按 Lua 原味会全判假。
     */
    fun compare(left: ExpressionValue, right: ExpressionValue, operator: WatchCompareOp): Boolean {
        if (operator == WatchCompareOp.EQUAL || operator == WatchCompareOp.NOT_EQUAL) {
            val equal = when {
                left === ExpressionValue.Nil || right === ExpressionValue.Nil -> left === right
                left.asNumber() != null && right.asNumber() != null -> left.asNumber() == right.asNumber()
                left is ExpressionValue.Text && right is ExpressionValue.Text -> left.value == right.value
                left is ExpressionValue.Logical && right is ExpressionValue.Logical -> left.value == right.value
                else -> false
            }
            return if (operator == WatchCompareOp.EQUAL) equal else !equal
        }
        val leftNumber = left.asNumber() ?: error("Expected number")
        val rightNumber = right.asNumber() ?: error("Expected number")
        return when (operator) {
            WatchCompareOp.GREATER -> leftNumber > rightNumber
            WatchCompareOp.GREATER_EQUAL -> leftNumber >= rightNumber
            WatchCompareOp.LESS -> leftNumber < rightNumber
            WatchCompareOp.LESS_EQUAL -> leftNumber <= rightNumber
            else -> error("Unexpected comparison")
        }
    }

    private fun formatLuaString(
        format: String,
        arguments: List<ExpressionValue>,
    ): String {
        require(format.length <= WatchExpressionLimits.MAX_RESULT_TEXT_LENGTH) {
            "Format string is too large"
        }
        val output = StringBuilder(format.length + 16)
        var index = 0
        var argumentIndex = 0
        while (index < format.length) {
            if (format[index] != '%') {
                output.append(format[index++])
                continue
            }
            if (index + 1 < format.length && format[index + 1] == '%') {
                output.append('%')
                index += 2
                continue
            }
            val specStart = index++
            while (index < format.length && format[index] in "-+ #0") index++
            val widthStart = index
            while (index < format.length && format[index].isDigit()) index++
            val width = format.substring(widthStart, index).toIntOrNull() ?: 0
            require(width <= 128) { "Format width is too large" }
            if (index < format.length && format[index] == '.') {
                index++
                val precisionStart = index
                while (index < format.length && format[index].isDigit()) index++
                val precision = format.substring(precisionStart, index).toIntOrNull() ?: 0
                require(precision <= 32) { "Format precision is too large" }
            }
            require(index < format.length) { "Incomplete format specifier" }
            val type = format[index++]
            require(type in "diufFeEgGsq") { "Unsupported format type: $type" }
            val argument = arguments.getOrNull(argumentIndex++) ?: ExpressionValue.Numeric(0.0)
            val spec = format.substring(specStart, index)
            val rendered = when (type) {
                'd', 'i' -> String.format(
                    Locale.ROOT,
                    spec.dropLast(1) + "d",
                    (argument.asNumber() ?: 0.0).toLong(),
                )
                'u' -> (argument.asNumber() ?: 0.0).toLong().toULong().toString()
                'f', 'F', 'e', 'E', 'g', 'G' ->
                    String.format(Locale.ROOT, spec, argument.asNumber() ?: 0.0)
                's' -> String.format(Locale.ROOT, spec, argument.renderText())
                'q' -> "\"${argument.renderText().replace("\\", "\\\\").replace("\"", "\\\"")}\""
                else -> error("Unsupported format type")
            }
            output.append(rendered)
            require(output.length <= WatchExpressionLimits.MAX_RESULT_TEXT_LENGTH) {
                "Formatted text is too large"
            }
        }
        return output.toString()
    }
}

internal enum class WatchCompareOp { EQUAL, NOT_EQUAL, GREATER, GREATER_EQUAL, LESS, LESS_EQUAL }

internal enum class WatchArithOp { ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO }

/**
 * 一条属性表达式的编译产物。
 *
 * 对照 clox `wf_expr.lua:289` 的 `WFExpr.compile`，它返回
 * `{const=true, value}` / `{const=false, fn, deps}` —— 同一套三分法。
 */
internal sealed interface WatchAttr {

    /**
     * 不含 tag、不含 scope 变量，导入时就算死了。
     *
     * 这是**收益最大的一档**：`x="256"`、`opacity="100"` 这类字面量在表盘里占
     * 绝大多数，命中这里之后运行时连树都不用走。
     */
    data class Const(val value: ExpressionValue) : WatchAttr

    /**
     * 每次求值都要走树。
     *
     * @param node AST 根。
     * @param tagDependencies 这条表达式引用到的 tag（已 trim + 小写归一）。
     *   **阶段 2 的分桶推模型就靠它**：没有这个集合就只能每帧全量重算。
     * @param hasScopeRef 是否引用了 scope 变量。
     *   ⚠️ 含 scope 变量的表达式**一律不得折成 [Const]**：`scope` 是按引用进
     *   缓存键的（编辑器里画布、实时预览、图层缩略图各持一份
     *   [WatchExpressionScope]），同一条表达式在不同 View 下 `resolve` 结果不同。
     *   阶段 3 判定静态图层可否烘焙时，也要拿这个标志决定「变量写入是否触发重烘」。
     * @param nodeCount 树的节点数，用于全局内存记账。
     */
    data class Dynamic(
        val node: WatchExprNode,
        val tagDependencies: Set<String>,
        val hasScopeRef: Boolean,
        val nodeCount: Int,
    ) : WatchAttr

    /**
     * 编译不过（语法不支持、超限、或常量折叠时就确定会抛）。
     *
     * 运行时退回老路径 —— 老路径同样会失败，于是走它自己的 fallback（文字图层
     * 回退成 tag 插值、数值图层回退成默认值），行为与改动前逐位相同。
     */
    data object Unsupported : WatchAttr
}

/**
 * AST 节点。
 *
 * 与老 `Parser` 的差别只有一处是刻意的：**这里是真的短路求值**。老路径
 * `parseOr` 写成 `value = if (value.isTruthy()) value else right`，右侧其实
 * **已经求过值了**（`parseAnd()` 在赋值前就调用完了），所以
 * `{bl} > 0 and 100/{bl} or 0` 在 `{bl}` 为 0 时会因除零而整条失败。
 * Lua 是短路的，WatchMaker 跑的是真 Lua，clox 也编译成 Lua —— 这里跟 Lua 对齐，
 * 老路径那个行为是 bug。相应地表达式**只会比以前更少失败**，不会更多。
 */
internal sealed interface WatchExprNode {
    /** 子树节点数，构造时算一次，用于内存记账与超限判定。 */
    val nodeCount: Int

    /** 子树是否不含 tag 与 scope 变量 —— 常量折叠的前提。 */
    val isFoldable: Boolean

    data class Literal(val value: ExpressionValue) : WatchExprNode {
        override val nodeCount: Int = 1
        override val isFoldable: Boolean = true
    }

    /**
     * 裸 tag：`{bl}`。
     *
     * 运行时类型由**代入文本**决定（数值 / `true`/`false`/`nil` 关键字 / 字符串），
     * 与老路径的 `toExpressionLiteral` 同规则。
     *
     * @param tag 原样保留（不 trim）：`resolveTag` 自己会归一化，改在这里 trim
     *   会让「解析器看到的 tag 名」与老路径不同。
     */
    data class TagValue(val tag: String) : WatchExprNode {
        override val nodeCount: Int = 1
        override val isFoldable: Boolean = false
    }

    /**
     * 带插值的字符串字面量：`'今天是{ddw}'`。
     *
     * 引号内的 tag 是字符串**内容**的一部分，走的不是 [TagValue] 那条类型推断
     * 路径（老路径对应 `escapeForQuote`）。
     *
     * @param parts 交替的字面片段与 tag 引用，按源顺序。
     */
    data class Template(val parts: List<Part>) : WatchExprNode {
        // 片段数计进节点数：整段模板只是一个 token，节点数不算上它的话，
        // `'{a}{a}{a}…'` 这种能在 token 上限之内堆出上万个 tag 引用。
        override val nodeCount: Int = 1 + parts.size
        override val isFoldable: Boolean = parts.none { it is Part.Tag }

        sealed interface Part {
            /** 已解转义的字面文本。 */
            data class Chunk(val text: String) : Part
            data class Tag(val tag: String) : Part
        }
    }

    /** scope 变量。编译期已确认 `scope.resolve(name) != null`。 */
    data class ScopeRef(val name: String) : WatchExprNode {
        override val nodeCount: Int = 1
        override val isFoldable: Boolean = false
    }

    data class Arith(
        val op: WatchArithOp,
        val left: WatchExprNode,
        val right: WatchExprNode,
    ) : WatchExprNode {
        override val nodeCount: Int = 1 + left.nodeCount + right.nodeCount
        override val isFoldable: Boolean = left.isFoldable && right.isFoldable
    }

    /**
     * 幂运算。
     *
     * ⚠️ [compensateNegativeBase] 是为了与老路径的**文本代入**语义对齐：
     * `{x}^2` 在 `{x}` 为 −5 时，老路径的源文本变成 `-5^2`，而 Lua 里一元负号
     * 比 `^` 结合得松，于是算的是 `-(5^2) = -25`；AST 路径把 tag 当**值**看，
     * 直接算 `(-5)^2 = 25`。两者差一个正负号，会让表针指到反方向。
     * 只有底数是 tag 时需要补偿 —— scope 变量在老路径也是按值取的（走
     * `toScopedExpressionValue`，不经文本代入），字符串模板做底数没有实际用法。
     */
    data class Power(
        val left: WatchExprNode,
        val right: WatchExprNode,
        val compensateNegativeBase: Boolean,
    ) : WatchExprNode {
        override val nodeCount: Int = 1 + left.nodeCount + right.nodeCount
        override val isFoldable: Boolean = left.isFoldable && right.isFoldable
    }

    /** 一元负号。一元正号在编译期直接消掉，不留节点。 */
    data class Negate(val value: WatchExprNode) : WatchExprNode {
        override val nodeCount: Int = 1 + value.nodeCount
        override val isFoldable: Boolean = value.isFoldable
    }

    data class Compare(
        val op: WatchCompareOp,
        val left: WatchExprNode,
        val right: WatchExprNode,
    ) : WatchExprNode {
        override val nodeCount: Int = 1 + left.nodeCount + right.nodeCount
        override val isFoldable: Boolean = left.isFoldable && right.isFoldable
    }

    data class Concat(val left: WatchExprNode, val right: WatchExprNode) : WatchExprNode {
        override val nodeCount: Int = 1 + left.nodeCount + right.nodeCount
        override val isFoldable: Boolean = left.isFoldable && right.isFoldable
    }

    data class And(val left: WatchExprNode, val right: WatchExprNode) : WatchExprNode {
        override val nodeCount: Int = 1 + left.nodeCount + right.nodeCount
        override val isFoldable: Boolean = left.isFoldable && right.isFoldable
    }

    data class Or(val left: WatchExprNode, val right: WatchExprNode) : WatchExprNode {
        override val nodeCount: Int = 1 + left.nodeCount + right.nodeCount
        override val isFoldable: Boolean = left.isFoldable && right.isFoldable
    }

    data class Not(val value: WatchExprNode) : WatchExprNode {
        override val nodeCount: Int = 1 + value.nodeCount
        override val isFoldable: Boolean = value.isFoldable
    }

    data class Call(val name: String, val arguments: List<WatchExprNode>) : WatchExprNode {
        override val nodeCount: Int = 1 + arguments.sumOf(WatchExprNode::nodeCount)
        override val isFoldable: Boolean = arguments.all(WatchExprNode::isFoldable)
    }
}

private enum class WatchTokenType {
    NUMBER,
    /** 引号字符串。可能带内嵌 tag，故负载是片段序列而不是单个字符串。 */
    TEMPLATE,
    /** 裸 tag `{...}`。老词法器看不到这种 token —— tag 在它之前就被代入了。 */
    TAG,
    TRUE, FALSE, NIL, IDENTIFIER,
    AND, OR, NOT,
    PLUS, MINUS, STAR, SLASH, PERCENT, POWER, CONCAT,
    DOT, COMMA,
    EQUAL, NOT_EQUAL, GREATER, GREATER_EQUAL, LESS, LESS_EQUAL,
    LEFT_PAREN, RIGHT_PAREN,
    END,
}

private class WatchToken(
    val type: WatchTokenType,
    val text: String,
    /** 仅 [WatchTokenType.TEMPLATE] 使用。 */
    val parts: List<WatchExprNode.Template.Part> = emptyList(),
)

/**
 * 认得 `{tag}` 的词法器。
 *
 * 与老 `Lexer` 的关键差别：老的跑在**已代入**的文本上，这里跑在**原始**文本上。
 * 于是这里必须自己承担原来 `substituteTags` 那部分职责 ——
 *
 * - `{...}` 成为 token，而不是被换成字面量；
 * - 引号外的不换行空格（` `）当普通空白（`Char.isWhitespace()` 对它返回
 *   false，所以必须显式列出，否则会报「不支持的字符」）；
 * - 引号内的 `{...}` 是字符串内容，产出 [WatchExprNode.Template.Part.Tag]；
 * - 引号内**不成对**的 `{`（如 `'a{b'`）按字面字符处理，与
 *   `substituteTags` 同规则（它只在找到配对 `}` 且中间无 `{` 时才当 tag）。
 */
private class WatchExprLexer(private val source: String) {
    private var index = 0

    fun tokens(): List<WatchToken> = buildList {
        while (true) {
            require(size < WatchExpressionLimits.MAX_EXPRESSION_TOKENS) {
                "Expression has too many tokens"
            }
            val token = nextToken()
            add(token)
            if (token.type == WatchTokenType.END) break
        }
    }

    private fun nextToken(): WatchToken {
        while (index < source.length && isSkippableSpace(source[index])) index++
        if (index >= source.length) return WatchToken(WatchTokenType.END, "")
        val start = index
        val char = source[index++]
        return when (char) {
            '+' -> WatchToken(WatchTokenType.PLUS, "+")
            '-' -> WatchToken(WatchTokenType.MINUS, "-")
            '*' -> WatchToken(WatchTokenType.STAR, "*")
            '/' -> WatchToken(WatchTokenType.SLASH, "/")
            '%' -> WatchToken(WatchTokenType.PERCENT, "%")
            '^' -> WatchToken(WatchTokenType.POWER, "^")
            ',' -> WatchToken(WatchTokenType.COMMA, ",")
            '(' -> WatchToken(WatchTokenType.LEFT_PAREN, "(")
            ')' -> WatchToken(WatchTokenType.RIGHT_PAREN, ")")
            '{' -> readTag(start)
            '=' -> {
                require(match('=')) { "Assignment is not supported" }
                WatchToken(WatchTokenType.EQUAL, "==")
            }
            '!', '~' -> {
                require(match('=')) { "Unsupported operator at $start" }
                WatchToken(WatchTokenType.NOT_EQUAL, source.substring(start, index))
            }
            '>' -> if (match('=')) {
                WatchToken(WatchTokenType.GREATER_EQUAL, ">=")
            } else {
                WatchToken(WatchTokenType.GREATER, ">")
            }
            '<' -> if (match('=')) {
                WatchToken(WatchTokenType.LESS_EQUAL, "<=")
            } else {
                WatchToken(WatchTokenType.LESS, "<")
            }
            '\'', '"' -> readTemplate(char)
            '.' -> when {
                match('.') -> WatchToken(WatchTokenType.CONCAT, "..")
                index < source.length && source[index].isDigit() -> readNumber(start)
                else -> WatchToken(WatchTokenType.DOT, ".")
            }
            else -> when {
                char.isDigit() -> readNumber(start)
                char.isLetter() || char == '_' -> readIdentifier(start)
                else -> error("Unsupported expression character: $char")
            }
        }
    }

    /** ` ` 见类注释：老路径在代入阶段把它归一成空格，这里当空白跳过。 */
    private fun isSkippableSpace(char: Char): Boolean =
        char.isWhitespace() || char.code == 0xa0

    private fun readTag(start: Int): WatchToken {
        val closing = source.indexOf('}', startIndex = index)
        require(closing > index) { "Empty or unterminated tag at $start" }
        val tag = source.substring(index, closing)
        require(tag.none { it == '{' }) { "Nested '{' in tag at $start" }
        index = closing + 1
        return WatchToken(WatchTokenType.TAG, tag)
    }

    private fun readNumber(start: Int): WatchToken {
        var hasDecimalPoint = source[start] == '.'
        while (index < source.length) {
            val char = source[index]
            when {
                char.isDigit() -> index++
                char == '.' && !hasDecimalPoint &&
                    (index + 1 >= source.length || source[index + 1] != '.') -> {
                    hasDecimalPoint = true
                    index++
                }
                else -> break
            }
        }
        if (index < source.length && (source[index] == 'e' || source[index] == 'E')) {
            index++
            if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
            while (index < source.length && source[index].isDigit()) index++
        }
        val text = source.substring(start, index)
        require(text.toDoubleOrNull()?.isFinite() == true) { "Invalid number: $text" }
        return WatchToken(WatchTokenType.NUMBER, text)
    }

    private fun readIdentifier(start: Int): WatchToken {
        while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_')) {
            index++
        }
        val text = source.substring(start, index)
        val type = when (text.lowercase(Locale.ROOT)) {
            "true" -> WatchTokenType.TRUE
            "false" -> WatchTokenType.FALSE
            "nil" -> WatchTokenType.NIL
            "and" -> WatchTokenType.AND
            "or" -> WatchTokenType.OR
            "not" -> WatchTokenType.NOT
            else -> WatchTokenType.IDENTIFIER
        }
        return WatchToken(type, text)
    }

    /**
     * 读一段引号字符串，产出「字面片段 + tag 引用」交替的序列。
     *
     * 转义解释与老 `readString` 一致：`\n`/`\r`/`\t` 转换，其余转义字符原样保留
     * （包含 `\\`、`\'`、`\"`）。tag 值**不**参与转义解释 —— 老路径靠
     * `escapeForQuote` 先转义再由 `readString` 解回来，往返后就是原值，这里直接
     * 用原值，结果相同且省掉两趟字符串扫描。
     */
    private fun readTemplate(quote: Char): WatchToken {
        val parts = ArrayList<WatchExprNode.Template.Part>(4)
        val chunk = StringBuilder()
        fun flushChunk() {
            if (chunk.isNotEmpty()) {
                parts += WatchExprNode.Template.Part.Chunk(chunk.toString())
                chunk.setLength(0)
            }
        }
        while (index < source.length) {
            val char = source[index++]
            if (char == quote) {
                flushChunk()
                return WatchToken(WatchTokenType.TEMPLATE, "", parts)
            }
            if (char == '\\') {
                require(index < source.length) { "Unterminated escape" }
                chunk.append(
                    when (val escaped = source[index++]) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        else -> escaped
                    },
                )
                continue
            }
            if (char == '{') {
                val closing = source.indexOf('}', startIndex = index)
                // 与 substituteTags 同条件：配对 '}' 存在、非空、中间无 '{'。
                // 不满足时 '{' 是普通字面字符。
                if (closing > index && source.substring(index, closing).none { it == '{' }) {
                    flushChunk()
                    parts += WatchExprNode.Template.Part.Tag(source.substring(index, closing))
                    index = closing + 1
                    continue
                }
            }
            chunk.append(char)
        }
        error("Unterminated string")
    }

    private fun match(expected: Char): Boolean {
        if (index >= source.length || source[index] != expected) return false
        index++
        return true
    }
}

/**
 * 递归下降编译器。文法与老 `Parser` **逐层对应**，只是产出节点而不是就地求值。
 *
 * 优先级链（自低到高）：`or` → `and` → `not` → 比较 → `..` → `+ -` → `* / %`
 * → 一元 `+ -` → `^` → 基本项。
 *
 * 顺带做**常量折叠**：子树不含 tag 也不含 scope 变量时，编译期直接算成字面量。
 * 这是收益最大的一档 —— 表盘里绝大多数属性是纯字面量，折完之后运行时连树都
 * 不用走。
 */
private class WatchExprCompiler(
    source: String,
    private val scope: WatchExpressionScope,
) {
    private val tokens = WatchExprLexer(source).tokens()
    private var index = 0
    private var depth = 0

    fun compile(): WatchExprNode {
        val node = parseOr()
        require(peek().type == WatchTokenType.END) { "Unexpected token: ${peek().text}" }
        require(node.nodeCount <= MAX_NODES_PER_EXPRESSION) { "Expression AST is too large" }
        return node
    }

    private inline fun <T> nested(block: () -> T): T {
        require(depth < WatchExpressionLimits.MAX_PARSE_DEPTH) { "Expression nesting is too deep" }
        depth++
        try {
            return block()
        } finally {
            depth--
        }
    }

    private fun parseOr(): WatchExprNode {
        var node = parseAnd()
        while (match(WatchTokenType.OR)) {
            node = fold(WatchExprNode.Or(node, parseAnd()))
        }
        return node
    }

    private fun parseAnd(): WatchExprNode {
        var node = parseNot()
        while (match(WatchTokenType.AND)) {
            node = fold(WatchExprNode.And(node, parseNot()))
        }
        return node
    }

    /** WatchMaker/WFTools treats `not` as applying to the following comparison. */
    private fun parseNot(): WatchExprNode =
        if (match(WatchTokenType.NOT)) fold(WatchExprNode.Not(parseNot())) else parseComparison()

    private fun parseComparison(): WatchExprNode {
        var node = parseConcat()
        while (true) {
            val op = when (peek().type) {
                WatchTokenType.EQUAL -> WatchCompareOp.EQUAL
                WatchTokenType.NOT_EQUAL -> WatchCompareOp.NOT_EQUAL
                WatchTokenType.GREATER -> WatchCompareOp.GREATER
                WatchTokenType.GREATER_EQUAL -> WatchCompareOp.GREATER_EQUAL
                WatchTokenType.LESS -> WatchCompareOp.LESS
                WatchTokenType.LESS_EQUAL -> WatchCompareOp.LESS_EQUAL
                else -> return node
            }
            advance()
            node = fold(WatchExprNode.Compare(op, node, parseConcat()))
        }
    }

    /** Lua concatenation is right-associative and lower precedence than arithmetic. */
    private fun parseConcat(): WatchExprNode {
        val left = parseAdditive()
        if (!match(WatchTokenType.CONCAT)) return left
        return fold(WatchExprNode.Concat(left, parseConcat()))
    }

    private fun parseAdditive(): WatchExprNode {
        var node = parseMultiplicative()
        while (true) {
            val op = when (peek().type) {
                WatchTokenType.PLUS -> WatchArithOp.ADD
                WatchTokenType.MINUS -> WatchArithOp.SUBTRACT
                else -> return node
            }
            advance()
            node = fold(WatchExprNode.Arith(op, node, parseMultiplicative()))
        }
    }

    private fun parseMultiplicative(): WatchExprNode {
        var node = parseUnary()
        while (true) {
            val op = when (peek().type) {
                WatchTokenType.STAR -> WatchArithOp.MULTIPLY
                WatchTokenType.SLASH -> WatchArithOp.DIVIDE
                WatchTokenType.PERCENT -> WatchArithOp.MODULO
                else -> return node
            }
            advance()
            node = fold(WatchExprNode.Arith(op, node, parseUnary()))
        }
    }

    private fun parseUnary(): WatchExprNode = when {
        match(WatchTokenType.MINUS) -> fold(WatchExprNode.Negate(parseUnary()))
        // 一元正号在老路径里是 `numericValue(x.asNumber())`：只做「必须是数字」
        // 的校验，不改变值。这里保留同样的校验，但用 Arith(ADD, 0, x) 表达，
        // 免得为它单开一种节点。
        match(WatchTokenType.PLUS) -> fold(
            WatchExprNode.Arith(
                WatchArithOp.ADD,
                WatchExprNode.Literal(ExpressionValue.Numeric(0.0)),
                parseUnary(),
            ),
        )
        else -> parsePower()
    }

    private fun parsePower(): WatchExprNode {
        val left = parsePrimary()
        if (!match(WatchTokenType.POWER)) return left
        return fold(
            WatchExprNode.Power(
                left = left,
                right = parseUnary(),
                // 见 WatchExprNode.Power 的 KDoc：只有底数是裸 tag 时才需要
                // 补偿老路径的文本代入语义。
                compensateNegativeBase = left is WatchExprNode.TagValue,
            ),
        )
    }

    private fun parsePrimary(): WatchExprNode {
        val token = advance()
        return when (token.type) {
            WatchTokenType.NUMBER ->
                WatchExprNode.Literal(ExpressionValue.Numeric(token.text.toDouble()))
            WatchTokenType.TEMPLATE -> fold(WatchExprNode.Template(token.parts))
            WatchTokenType.TAG -> WatchExprNode.TagValue(token.tag())
            WatchTokenType.TRUE -> WatchExprNode.Literal(ExpressionValue.Logical(true))
            WatchTokenType.FALSE -> WatchExprNode.Literal(ExpressionValue.Logical(false))
            WatchTokenType.NIL -> WatchExprNode.Literal(ExpressionValue.Nil)
            WatchTokenType.LEFT_PAREN -> nested { parseOr() }.also {
                require(match(WatchTokenType.RIGHT_PAREN)) { "Missing ')'" }
            }
            WatchTokenType.IDENTIFIER -> parseIdentifier(token.text)
            else -> error("Expected value, found ${token.text}")
        }
    }

    private fun parseIdentifier(first: String): WatchExprNode {
        val qualified = StringBuilder(first)
        while (match(WatchTokenType.DOT)) {
            val segment = advance()
            require(segment.type == WatchTokenType.IDENTIFIER) { "Expected identifier after '.'" }
            qualified.append('.').append(segment.text)
        }
        val name = qualified.toString().lowercase(Locale.ROOT)
        if (!match(WatchTokenType.LEFT_PAREN)) {
            return when (name) {
                "math.pi" -> WatchExprNode.Literal(ExpressionValue.Numeric(Math.PI))
                "math.huge" -> WatchExprNode.Literal(ExpressionValue.Numeric(Double.MAX_VALUE))
                // 编译期就确认变量存在，与老路径「解析时 resolve 不到就抛」同时机。
                // 但**不把它折成字面量**：见 WatchAttr.Dynamic.hasScopeRef 的 KDoc。
                else -> if (scope.resolve(name) != null) {
                    WatchExprNode.ScopeRef(name)
                } else {
                    error("Bare identifiers and script variables are not supported: $name")
                }
            }
        }

        val arguments = ArrayList<WatchExprNode>()
        if (!match(WatchTokenType.RIGHT_PAREN)) {
            nested {
                do {
                    require(arguments.size < WatchExpressionLimits.MAX_FUNCTION_ARGUMENTS) {
                        "Too many function arguments"
                    }
                    arguments += parseOr()
                } while (match(WatchTokenType.COMMA))
            }
            require(match(WatchTokenType.RIGHT_PAREN)) { "Missing ')' after function arguments" }
        }
        return fold(WatchExprNode.Call(name, arguments))
    }

    /**
     * 常量折叠。
     *
     * ⚠️ **折不动就原样返回，绝不把失败上抛**：不含 tag 的子树可能落在一条永远
     * 不会被执行的短路分支里（`{bl} > 0 and 100/0 or 1` 的除零就在这种位置）。
     * 编译期抛出去会让整条表达式退回老路径 —— 而老路径是**急求值**的，它同样
     * 会失败。所以折叠只做「能算就算」，算不了留给运行时按短路语义跳过。
     */
    private fun fold(node: WatchExprNode): WatchExprNode {
        if (!node.isFoldable) return node
        if (node is WatchExprNode.Literal) return node
        val value = runCatching {
            evaluateWatchExpr(node, WatchExpressionScope.EMPTY, CONSTANT_FOLD_RESOLVER)
        }.getOrNull() ?: return node
        return WatchExprNode.Literal(value)
    }

    private fun peek(): WatchToken = tokens[index]

    private fun advance(): WatchToken = tokens[index++]

    private fun match(type: WatchTokenType): Boolean {
        if (peek().type != type) return false
        index++
        return true
    }

    private fun WatchToken.tag(): String = text

    companion object {
        /**
         * 单条表达式的 AST 节点上限。
         *
         * token 上限（8K）本身就把节点数压在同量级，这里是第二道闸：实测最长的
         * 那批 WFF 天气链约 2500 token / 1200 节点，8K 留了足够余量。
         */
        private const val MAX_NODES_PER_EXPRESSION = 8 * 1024

        /**
         * 常量折叠时的 tag 解析器。
         *
         * 折叠只在 `isFoldable`（子树不含 tag 也不含 scope 变量）时进行，所以这个
         * 解析器**永远不该被调用到**。写成抛异常而不是返回 `nil`，是为了让哪天
         * `isFoldable` 算错时立刻暴露成折叠失败（留在树里、运行时正常求值），
         * 而不是静默把一个 tag 折成常量、图层从此冻住。
         */
        private val CONSTANT_FOLD_RESOLVER: (String) -> WatchTagResolution = { tag ->
            error("Constant folding must not resolve tags: $tag")
        }
    }
}

/**
 * 走一遍 AST 求值。
 *
 * 抛异常表示这次求值失败，由调用方退回老路径 —— 与老 `Parser` 的失败约定一致
 * （它同样靠 `runCatching` 兜住）。
 */
internal fun evaluateWatchExpr(
    node: WatchExprNode,
    scope: WatchExpressionScope,
    resolveTag: (String) -> WatchTagResolution,
): ExpressionValue = when (node) {
    is WatchExprNode.Literal -> node.value

    is WatchExprNode.TagValue -> resolveTag(node.tag).toBareTagValue()

    is WatchExprNode.Template -> {
        val output = StringBuilder()
        node.parts.forEach { part ->
            when (part) {
                is WatchExprNode.Template.Part.Chunk -> output.append(part.text)
                is WatchExprNode.Template.Part.Tag -> output.append(
                    (resolveTag(part.tag) as? WatchTagResolution.Resolved)?.renderedValue.orEmpty(),
                )
            }
            require(output.length <= WatchExpressionLimits.MAX_EXPRESSION_LENGTH) {
                "Expression text result is too large"
            }
        }
        ExpressionValue.Text(output.toString())
    }

    is WatchExprNode.ScopeRef -> WatchLuaStdlib.toScopedExpressionValue(
        scope.resolve(node.name) ?: error("Script variable disappeared: ${node.name}"),
    )

    is WatchExprNode.Arith -> {
        val left = evaluateWatchExpr(node.left, scope, resolveTag).asNumber()
            ?: error("Expected number")
        val right = evaluateWatchExpr(node.right, scope, resolveTag).asNumber()
            ?: error("Expected number")
        WatchLuaStdlib.numericValue(
            when (node.op) {
                WatchArithOp.ADD -> left + right
                WatchArithOp.SUBTRACT -> left - right
                WatchArithOp.MULTIPLY -> left * right
                WatchArithOp.DIVIDE -> {
                    require(abs(right) > Double.MIN_VALUE) { "Division by zero" }
                    left / right
                }
                WatchArithOp.MODULO -> {
                    require(abs(right) > Double.MIN_VALUE) { "Division by zero" }
                    left % right
                }
            },
        )
    }

    is WatchExprNode.Power -> {
        val base = evaluateWatchExpr(node.left, scope, resolveTag).asNumber()
            ?: error("Expected number")
        val exponent = evaluateWatchExpr(node.right, scope, resolveTag).asNumber()
            ?: error("Expected number")
        WatchLuaStdlib.numericValue(
            // 见 WatchExprNode.Power 的 KDoc：底数是 tag 且为负时，老路径实际算的
            // 是 -(|base|^exp)，因为代入后的 '-' 在 Lua 里比 '^' 结合得松。
            if (node.compensateNegativeBase && base < 0.0) {
                -((-base).pow(exponent))
            } else {
                base.pow(exponent)
            },
        )
    }

    is WatchExprNode.Negate -> WatchLuaStdlib.numericValue(
        -(evaluateWatchExpr(node.value, scope, resolveTag).asNumber() ?: error("Expected number")),
    )

    is WatchExprNode.Compare -> ExpressionValue.Logical(
        WatchLuaStdlib.compare(
            evaluateWatchExpr(node.left, scope, resolveTag),
            evaluateWatchExpr(node.right, scope, resolveTag),
            node.op,
        ),
    )

    is WatchExprNode.Concat -> {
        val text = evaluateWatchExpr(node.left, scope, resolveTag).renderText() +
            evaluateWatchExpr(node.right, scope, resolveTag).renderText()
        require(text.length <= WatchExpressionLimits.MAX_RESULT_TEXT_LENGTH) {
            "Expression text result is too large"
        }
        ExpressionValue.Text(text)
    }

    // 真短路：右侧只在需要时求值。语义与 Lua 的 `and`/`or` 一致
    // （返回的是操作数本身，不是布尔），三元惯用法 `c and a or b` 照常工作。
    is WatchExprNode.And -> {
        val left = evaluateWatchExpr(node.left, scope, resolveTag)
        if (left.isTruthy()) evaluateWatchExpr(node.right, scope, resolveTag) else left
    }

    is WatchExprNode.Or -> {
        val left = evaluateWatchExpr(node.left, scope, resolveTag)
        if (left.isTruthy()) left else evaluateWatchExpr(node.right, scope, resolveTag)
    }

    is WatchExprNode.Not -> ExpressionValue.Logical(
        !evaluateWatchExpr(node.value, scope, resolveTag).isTruthy(),
    )

    is WatchExprNode.Call -> WatchLuaStdlib.callFunction(
        node.name,
        node.arguments.map { argument -> evaluateWatchExpr(argument, scope, resolveTag) },
    )
}

/**
 * 裸 tag 的运行时取值。
 *
 * 与老路径的 `toExpressionLiteral` **同规则**：数字文本 → 数值，
 * `true`/`false`/`nil` 关键字（大小写不敏感）→ 对应字面量，其余 → 字符串。
 * 差别只在老路径是「拼成字面量文本再由词法器读回来」，这里直接建值 —— 少两趟
 * 字符串扫描，结果相同。
 *
 * 未解析 / 不支持的 tag 取 `nil`，与老路径代入 `"nil"` 等价。
 */
private fun WatchTagResolution.toBareTagValue(): ExpressionValue {
    val value = expressionValue()?.trim() ?: return ExpressionValue.Nil
    value.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { return ExpressionValue.Numeric(it) }
    if (value.equals("true", true)) return ExpressionValue.Logical(true)
    if (value.equals("false", true)) return ExpressionValue.Logical(false)
    if (value == "nil") return ExpressionValue.Nil
    return ExpressionValue.Text(value)
}

/** 收集 AST 里所有 tag 依赖（已 trim + 小写归一）与 scope 变量引用。 */
private fun WatchExprNode.collectDependencies(
    tags: MutableSet<String>,
    scopeRefs: MutableSet<String>,
) {
    when (this) {
        is WatchExprNode.Literal -> Unit
        is WatchExprNode.TagValue -> tags += tag.trim().lowercase(Locale.ROOT)
        is WatchExprNode.Template -> parts.forEach { part ->
            if (part is WatchExprNode.Template.Part.Tag) {
                tags += part.tag.trim().lowercase(Locale.ROOT)
            }
        }
        is WatchExprNode.ScopeRef -> scopeRefs += name
        is WatchExprNode.Arith -> {
            left.collectDependencies(tags, scopeRefs)
            right.collectDependencies(tags, scopeRefs)
        }
        is WatchExprNode.Power -> {
            left.collectDependencies(tags, scopeRefs)
            right.collectDependencies(tags, scopeRefs)
        }
        is WatchExprNode.Compare -> {
            left.collectDependencies(tags, scopeRefs)
            right.collectDependencies(tags, scopeRefs)
        }
        is WatchExprNode.Concat -> {
            left.collectDependencies(tags, scopeRefs)
            right.collectDependencies(tags, scopeRefs)
        }
        is WatchExprNode.And -> {
            left.collectDependencies(tags, scopeRefs)
            right.collectDependencies(tags, scopeRefs)
        }
        is WatchExprNode.Or -> {
            left.collectDependencies(tags, scopeRefs)
            right.collectDependencies(tags, scopeRefs)
        }
        is WatchExprNode.Negate -> value.collectDependencies(tags, scopeRefs)
        is WatchExprNode.Not -> value.collectDependencies(tags, scopeRefs)
        is WatchExprNode.Call -> arguments.forEach { it.collectDependencies(tags, scopeRefs) }
    }
}

/**
 * 把一条属性表达式编译成 [WatchAttr]。**编译失败不抛**，返回
 * [WatchAttr.Unsupported] 让调用方退回老路径。
 */
internal fun compileWatchAttr(source: String, scope: WatchExpressionScope): WatchAttr {
    if (source.isBlank() || source.length > WatchExpressionLimits.MAX_EXPRESSION_LENGTH) {
        return WatchAttr.Unsupported
    }
    val node = runCatching { WatchExprCompiler(source, scope).compile() }.getOrNull()
        ?: return WatchAttr.Unsupported
    if (node is WatchExprNode.Literal) return WatchAttr.Const(node.value)
    val tags = LinkedHashSet<String>()
    val scopeRefs = LinkedHashSet<String>()
    node.collectDependencies(tags, scopeRefs)
    return WatchAttr.Dynamic(
        node = node,
        tagDependencies = tags,
        hasScopeRef = scopeRefs.isNotEmpty(),
        nodeCount = node.nodeCount,
    )
}

/**
 * 导入期预编译的产物摘要。
 *
 * 它有两个用途，缺一不可：
 * 1. **诊断**：三个计数一眼看出这张表盘有多少属性真的进了编译路径。若
 *    [unsupported] 异常地高，说明词法/语法有没覆盖到的写法，而不是「性能没提升」
 *    这种查不动的现象。
 * 2. **阶段 2 的种子数据**：[tagDependencies] 就是 tag 分桶要的
 *    「哪条表达式依赖哪些 tag」。分桶不需要重新扫一遍属性，直接读这里。
 */
data class WatchExpressionCompileReport(
    val total: Int = 0,
    val constant: Int = 0,
    val dynamic: Int = 0,
    val unsupported: Int = 0,
    /** 表达式原文 → 它依赖的 tag（已小写归一）。仅收录 [WatchAttr.Dynamic]。 */
    val tagDependencies: Map<String, Set<String>> = emptyMap(),
) {
    /** 这张表盘里出现过的全部 tag —— 阶段 2 建桶时的全集。 */
    val referencedTags: Set<String>
        get() = tagDependencies.values.flatMapTo(LinkedHashSet()) { it }

    /**
     * 查一条属性原文的 tag 依赖。返回 `null` 表示**依赖不明**，不是「没有依赖」。
     *
     * ⚠️ 这个区分是阶段 3 可烘判定的安全底线。直接读 [tagDependencies] 的
     * `[raw].orEmpty()` 会把两种情况混成同一个空集：
     *
     * 1. **真的没 tag**（[WatchAttr.Const]）—— 可以烘。
     * 2. **编译不过**（[WatchAttr.Unsupported]，只进计数不进表），或者**键对不上**
     *    （表里的键是 `trim()` + 实体解码后的文本，而调用方拿的是属性原文，带前后
     *    空格就 miss）—— 这两种情况下属性完全可能引用 `{dss}`，当成「没有依赖」
     *    去烘就把逐帧变化的图层烘死冻住。
     *
     * 所以查不到时不猜：原文含 `{` 就返回 `null`（依赖不明，调用方从严当不可烘），
     * 不含 `{` 才是货真价实的无 tag 常量。
     */
    fun tagDependenciesOf(raw: String): Set<String>? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptySet()
        tagDependencies[trimmed]?.let { return it }
        // precompile 对每条源文本同时热了原文与实体解码后的形式，两个键都试。
        val decoded = trimmed.replace("&quot;", "\"").replace("&apos;", "'").trim()
        tagDependencies[decoded]?.let { return it }
        // 没有 `{` 就不可能有 tag：Const 走这条，是唯一可以放心当空集的情况。
        return if (trimmed.indexOf('{') < 0 && decoded.indexOf('{') < 0) emptySet() else null
    }

    companion object {
        val EMPTY = WatchExpressionCompileReport()
    }
}
