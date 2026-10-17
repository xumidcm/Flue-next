package com.flue.launcher.watchface.standard

import org.luaj.vm2.Globals
import org.luaj.vm2.Lua
import org.luaj.vm2.LuaClosure
import org.luaj.vm2.LuaFunction
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.BaseLib
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JseMathLib
import org.luaj.vm2.lib.jse.JseOsLib
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.Locale

/**
 * One CloX-compatible Lua sandbox per loaded WatchMaker face.
 *
 * LuaJ supplies only the VM/compiler.  Flue creates the globals explicitly and
 * removes every filesystem, package-loader, Java bridge and debug entry before
 * face code runs.  The internal [DebugLib] remains attached solely so the host
 * can enforce a per-entry instruction budget; the face never receives it.
 */
class WatchFaceLuaRuntime private constructor(
    private val globals: Globals,
    private val sandboxDebug: SandboxDebugLib,
    private val stringAllocationBudget: StringAllocationBudget,
    private val internalDebugTable: LuaValue,
    private val declaredFaceNames: Set<String>,
    private val externalDataVersion: () -> Long,
) : AutoCloseable {
    private var resolveTag: (String) -> WatchTagResolution = { WatchTagResolution.Unsupported(it) }
    private var lastFrameElapsedSeconds: Double? = null
    private var lastWallSecond: Int? = null
    private var lastWallMinute: Int? = null
    private var lastWallHour: Int? = null
    private var closed = false
    private val dynamicFaceNames = LinkedHashSet(declaredFaceNames)

    // runLimited 的沙箱钩子与参数包：每次属性求值都要装/拆一遍钩子，复用同一
    // 组实例（InstructionLimitHook 带 reset）。Lua 求值是单线程串行的。
    private val instructionLimitHook = InstructionLimitHook()
    private val hookArgs: Varargs = LuaValue.varargsOf(
        arrayOf(
            instructionLimitHook,
            LuaValue.valueOf(""),
            LuaValue.valueOf(INSTRUCTION_HOOK_INTERVAL),
        ),
    )
    private val hookOffArgs: Varargs = LuaValue.varargsOf(arrayOf(LuaValue.NIL))
    // stepClock 每拍一次的 Calendar；getInstance 是重量级分配，复用同一实例。
    private val wallCalendar = java.util.Calendar.getInstance()

    private val compiledExpressions = object : LinkedHashMap<String, LuaValue>(
        MAX_CACHED_EXPRESSIONS,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LuaValue>): Boolean =
            size > MAX_CACHED_EXPRESSIONS
    }

    /**
     * 属性表达式的**求值结果**缓存。
     *
     * ## 为什么需要它
     *
     * 编译结果早就缓存了（[compiledExpressions]），但每一帧、每个图层的每个动态属性仍然要走一遍
     * 「装指令钩子 → 调 chunk → 拆钩子」。老师报「这个表盘用起来很卡」的那张 M3 Expressive
     * 表盘就是极端例子：89 个图层里有 87 个 `color` 写成 `m_on_tertiary_cont('{ucolor}')`
     * 这类**脚本函数调用**（脚本里是 hex→HSL→hex 的一整套色调映射 + string.format）。
     * 60fps 下就是每秒五千多次 Lua 调用，全都在算同一批颜色。
     *
     * ## 什么时候可以复用上一次的结果
     *
     * 四个条件同时成立：
     *
     * 1. **代号相同**。face 侧 Lua 状态一变（跑过 tap 脚本、跑过 `on_millisecond` /
     *    `on_second` 这些回调）就 [faceStateGeneration] + 1，跨代的条目全部作废 ——
     *    带逐帧回调的表盘因此只能吃到「同一帧内多个图层共用同一表达式」这一层收益，
     *    这正是应该的。
     * 2. **它读过的每个标签值都没变**。标签是走 `wm_tag(...)` 进来的，求值时顺手记下
     *    「读了哪些标签、当时是什么值」，命中判定时用**当前**解析器重新取一遍比对。
     *    重取是纯 Kotlin 的快照查表，比进一次 Lua 便宜得多；而 `{swss}` / tween 这类
     *    每帧都变的标签会自然而然地判不中，不会被冻住。
     * 3. **外部数据版本相同**。`WF.thirdParty.slots` 由广播写入时递增版本号；即使表盘
     *    本身没有脚本回调，版本变化也会让缓存立即失效。
     * 4. **没过期**。再兜一层 [MAX_CACHE_AGE_NANOS] 上限，防住没有版本供应器的其它动态输入。
     *
     * 另外第一次见到某个表达式时会**连算两次探纯度**，两次结果不同就永久不缓存（见
     * [evaluateValue]）—— 靠副作用驱动动画的表达式因此不会被冻住。
     */
    private class EvaluatedAttribute(
        val value: LuaValue?,
        val generation: Long,
        val externalDataVersion: Long,
        val evaluatedAtNanos: Long,
        val tagNames: Array<String>,
        val tagValues: Array<String?>,
        val impure: Boolean,
    )

    private val evaluatedAttributes = object : LinkedHashMap<String, EvaluatedAttribute>(
        MAX_CACHED_EXPRESSIONS,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, EvaluatedAttribute>,
        ): Boolean = size > MAX_CACHED_EXPRESSIONS
    }

    /** face 侧 Lua 状态的代号。跑过任何 face 代码就 +1。 */
    private var faceStateGeneration = 0L

    /** 正在录制的「本次求值读过哪些标签」。非空表示当前处在一次可缓存的求值里。 */
    private var recordedTagNames: MutableList<String>? = null
    private var recordedTagValues: MutableList<String?>? = null

    /** `expression -> (代号, 是否引用了 face 全局)`。见 [referencesFaceState]。 */
    private val faceStateReferences = object : LinkedHashMap<String, Pair<Long, Boolean>>(
        MAX_CACHED_EXPRESSIONS,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Pair<Long, Boolean>>,
        ): Boolean = size > MAX_CACHED_EXPRESSIONS
    }

    fun runSnippet(
        source: String,
        resolver: (String) -> WatchTagResolution = { WatchTagResolution.Unsupported(it) },
    ): Result<Unit> = runLimited {
        require(source.toByteArray(Charsets.UTF_8).size <= MAX_SNIPPET_BYTES) {
            "face snippet is too large"
        }
        resolveTag = resolver
        val sanitized = sanitizeWatchmakerScript(source)
        compile(spliceTags(sanitized), "=watch_tap").call()
        dynamicFaceNames += declaredFaceNames(sanitized)
        // 脚本可以改任何全局，缓存里的结果一律不能再信。
        invalidateEvaluatedAttributes()
        Unit
    }

    fun stepMilliseconds(
        elapsedSeconds: Double,
        resolver: (String) -> WatchTagResolution = { WatchTagResolution.Unsupported(it) },
    ): Result<Unit> = runLimited {
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0.0) { "invalid frame time" }
        resolveTag = resolver
        val callback = globals.get("on_millisecond")
        if (!callback.isfunction()) {
            lastFrameElapsedSeconds = elapsedSeconds
            return@runLimited Unit
        }
        val previous = lastFrameElapsedSeconds
        lastFrameElapsedSeconds = elapsedSeconds
        callback.call(LuaValue.valueOf(if (previous == null) 0.0 else (elapsedSeconds - previous).coerceAtLeast(0.0)))
        invalidateEvaluatedAttributes()
        Unit
    }

    /**
     * CloX clock contract: run the millisecond callback and fire the boundary
     * callbacks using one wall-clock snapshot. The first tick fires all
     * boundaries, matching CloX's initial `-1` sentinels.
     */
    fun stepClock(
        elapsedSeconds: Double,
        wallTimeMillis: Long,
        resolver: (String) -> WatchTagResolution = { WatchTagResolution.Unsupported(it) },
    ): Result<Boolean> = runLimited {
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0.0) { "invalid frame time" }
        require(wallTimeMillis >= 0L) { "invalid wall clock" }
        resolveTag = resolver
        val previous = lastFrameElapsedSeconds
        lastFrameElapsedSeconds = elapsedSeconds
        var callbackRan = false
        globals.get("on_millisecond").takeIf(LuaValue::isfunction)?.let { callback ->
            callback.call(
                LuaValue.valueOf(
                    if (previous == null) 0.0 else (elapsedSeconds - previous).coerceAtLeast(0.0),
                ),
            )
            callbackRan = true
        }

        val wall = wallCalendar.apply { timeInMillis = wallTimeMillis }
        val second = wall.get(java.util.Calendar.SECOND)
        val minute = wall.get(java.util.Calendar.MINUTE)
        val hour = wall.get(java.util.Calendar.HOUR_OF_DAY)
        val firstTick = lastWallSecond == null
        if (firstTick || lastWallSecond != second) {
            globals.get("on_second").takeIf(LuaValue::isfunction)?.let { callback ->
                callback.call(LuaValue.valueOf(minute), LuaValue.valueOf(second))
                callbackRan = true
            }
        }
        if (firstTick || lastWallMinute != minute) {
            globals.get("on_minute").takeIf(LuaValue::isfunction)?.let { callback ->
                callback.call()
                callbackRan = true
            }
        }
        if (firstTick || lastWallHour != hour) {
            globals.get("on_hour").takeIf(LuaValue::isfunction)?.let { callback ->
                callback.call()
                callbackRan = true
            }
        }
        lastWallSecond = second
        lastWallMinute = minute
        lastWallHour = hour
        // 回调跑过就说明 face 侧的全局可能变了：属性结果缓存整批作废。没跑回调时
        // 什么都不做 —— 那种表盘（比如老师那张 M3，只定义颜色函数、没有逐帧回调）
        // 才能真正吃到跨帧复用。
        if (callbackRan) invalidateEvaluatedAttributes()
        callbackRan
    }

    fun needsFrame(): Boolean = !closed && globals.get("on_millisecond").isfunction()

    fun evaluateNumber(
        expression: String,
        resolveTag: (String) -> WatchTagResolution = { WatchTagResolution.Unsupported(it) },
    ): Double = evaluateValue(expression, resolveTag)?.let { value ->
        when {
            value.isnumber() -> value.todouble()
            value.isstring() -> value.tojstring().toDoubleOrNull()
            else -> null
        }
    } ?: Double.NaN

    fun evaluateText(
        expression: String,
        resolveTag: (String) -> WatchTagResolution = { WatchTagResolution.Unsupported(it) },
    ): String? = evaluateValue(expression, resolveTag)?.let(::renderLuaValue)

    fun globalType(name: String): String? {
        if (closed) return null
        val value = globals.get(name)
        return if (value.isnil()) null else value.typename()
    }

    internal val cachedExpressionCount: Int
        get() = synchronized(compiledExpressions) { compiledExpressions.size }

    /** True when the expression references face state or a reserved `var_*` value. */
    fun referencesFaceState(expression: String): Boolean {
        if (closed || expression.isBlank()) return false
        // 这一步要扫一遍表达式挑出标识符（协程序列 + 字符串切片），而它在**每个动态属性、
        // 每一帧**都会被调用一次 —— 老师那张 89 图层的表盘一秒就是五千多次。判定结果只取决于
        // 表达式文本 + 当前 face 全局，所以按代号缓存：代号变了（跑过 face 代码）才重扫。
        synchronized(faceStateReferences) {
            faceStateReferences[expression]
        }?.let { cached ->
            if (cached.first == faceStateGeneration) return cached.second
        }
        // `{var_*}` is a value lookup, not a device tag.  The generic scanner
        // deliberately skips every braced token so `{dh24}` cannot turn into
        // face state, therefore keep the reserved script-variable prefix as a
        // small separate signal.  This also lets callers use evaluateFace*()
        // directly without depending on the editor's Lua-source classifier.
        val result = BRACED_SCRIPT_VARIABLE_PATTERN.containsMatchIn(expression) ||
            identifiersOutsideTagsAndStrings(expression).any { name ->
                name !in BUILTIN_ROOTS && (name in dynamicFaceNames || !globals.rawget(name).isnil())
            }
        synchronized(faceStateReferences) {
            faceStateReferences[expression] = faceStateGeneration to result
        }
        return result
    }

    fun evaluateFaceNumber(
        expression: String,
        resolver: (String) -> WatchTagResolution,
    ): Double? = if (!referencesFaceState(expression)) null else {
        val trimmed = expression.trim()
        val bareVar = if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            trimmed.substring(1, trimmed.length - 1).trim()
        } else trimmed
        if (bareVar.startsWith("var_") && bareVar.all { it.isLetterOrDigit() || it == '_' }) {
            val fastVal = globals.rawget(bareVar)
            if (!fastVal.isnil()) {
                val num = if (fastVal.isnumber()) fastVal.todouble() else fastVal.tojstring().toDoubleOrNull()
                if (num != null && num.isFinite()) return num
            }
        }
        evaluateValue(expression, resolver)?.let { value ->
            when {
                value.isnumber() -> value.todouble()
                value.isstring() -> value.tojstring().toDoubleOrNull()
                else -> null
            }
        }?.takeIf(Double::isFinite)
    }

    fun evaluateFaceText(
        expression: String,
        resolver: (String) -> WatchTagResolution,
    ): String? = if (!referencesFaceState(expression)) null else {
        val trimmed = expression.trim()
        val bareVar = if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            trimmed.substring(1, trimmed.length - 1).trim()
        } else trimmed
        if (bareVar.startsWith("var_") && bareVar.all { it.isLetterOrDigit() || it == '_' }) {
            val fastVal = globals.rawget(bareVar)
            if (!fastVal.isnil()) {
                return renderLuaValue(fastVal)
            }
        }
        // Once a layer references a face-owned name, a Lua nil is a valid
        // rendered value (blank), not a signal to fall back to the raw WM
        // template. Compile/runtime failures also stay blank so `var_s_t4 +`
        // can never leak source code onto the face.
        evaluateValue(expression, resolver)?.let(::renderLuaValue).orEmpty()
    }

    fun evaluateFaceBoolean(
        expression: String,
        resolver: (String) -> WatchTagResolution,
    ): Boolean? = if (!referencesFaceState(expression)) null else {
        evaluateValue(expression, resolver)?.toboolean()
    }

    /**
     * Evaluate an inline Lua condition even when it only contains locals/tags.
     * Face-state detection controls cache invalidation, not whether Lua is a
     * valid backend for the attribute.
     */
    fun evaluateBoolean(
        expression: String,
        resolver: (String) -> WatchTagResolution = { WatchTagResolution.Unsupported(it) },
    ): Boolean? = evaluateValue(expression, resolver)?.toboolean()

    fun globalRenderedValue(name: String): String? {
        if (closed) return null
        val value = globals.rawget(name)
        return when {
            value.isboolean() || value.isnumber() || value.isstring() -> renderLuaValue(value)
            else -> null
        }
    }

    private fun evaluateValue(
        expression: String,
        resolver: (String) -> WatchTagResolution,
    ): LuaValue? {
        if (expression.isBlank() || expression.length > MAX_EXPRESSION_CHARS) return null
        // 缓存命中路径也在跑 spliceTags（逐字符扫描 + 字符串重建）。它是纯文本
        // 变换、与运行时状态无关，按原文缓存后命中路径只剩一次 map 查找。
        val splicedExpression = spliceTagsCached(expression)
        val cached = synchronized(evaluatedAttributes) { evaluatedAttributes[splicedExpression] }
        if (cached != null && !cached.impure && isReusable(cached, resolver)) return cached.value

        val names = ArrayList<String>(TAG_RECORD_INITIAL_CAPACITY)
        val values = ArrayList<String?>(TAG_RECORD_INITIAL_CAPACITY)
        val first = evaluateRecording(splicedExpression, resolver, names, values)
        // 已知不纯：算一次就走，什么都不记。
        if (cached != null && cached.impure) return first
        if (cached != null) {
            rememberValue(splicedExpression, first, names, values, impure = false)
            return first
        }
        // **第一次见这个表达式 → 紧接着再算一次探纯度。**
        //
        // 缓存的前提是「输入没变 ⇒ 结果不变」。绝大多数属性表达式是纯的（`m_accent('{ucolor}')`
        // 这种色调映射就是典型），但表盘理论上可以写一个每次调用都自增的函数来驱动动画，
        // 或者直接读 `os.date()` 这类标签之外的活数据 —— 那种表达式一旦被复用就会卡住。
        // 同一瞬间连算两次：结果不同就永久标记为不可复用，代价只有加载时多算一次。
        val probeNames = ArrayList<String>(TAG_RECORD_INITIAL_CAPACITY)
        val probeValues = ArrayList<String?>(TAG_RECORD_INITIAL_CAPACITY)
        val second = evaluateRecording(splicedExpression, resolver, probeNames, probeValues)
        rememberValue(
            splicedExpression = splicedExpression,
            value = second,
            tagNames = probeNames,
            tagValues = probeValues,
            impure = identityKey(first) != identityKey(second),
        )
        return second
    }

    /** 求值一次，并把这次读到的标签记进 [tagNames] / [tagValues]。 */
    private fun evaluateRecording(
        splicedExpression: String,
        resolver: (String) -> WatchTagResolution,
        tagNames: MutableList<String>,
        tagValues: MutableList<String?>,
    ): LuaValue? {
        // 嵌套求值目前不存在（Lua 侧没有回调进这里的桥），但还是按栈式保存/恢复，
        // 免得以后加桥时静悄悄把外层的录制清掉。
        val outerNames = recordedTagNames
        val outerValues = recordedTagValues
        recordedTagNames = tagNames
        recordedTagValues = tagValues
        return try {
            runLimited {
                resolveTag = resolver
                val chunk = synchronized(compiledExpressions) {
                    compiledExpressions[splicedExpression]
                        ?: compile("return ($splicedExpression)", "=watch_attr").also {
                            compiledExpressions[splicedExpression] = it
                        }
                }
                chunk.call()
            }.getOrNull()?.takeUnless(LuaValue::isnil)
        } finally {
            recordedTagNames = outerNames
            recordedTagValues = outerValues
        }
    }

    /** 命中判定见 [EvaluatedAttribute] 的注释。四个条件全满足才算能复用。 */
    private fun isReusable(
        cached: EvaluatedAttribute,
        resolver: (String) -> WatchTagResolution,
    ): Boolean {
        if (cached.generation != faceStateGeneration) return false
        if (cached.externalDataVersion != externalDataVersion()) return false
        if (System.nanoTime() - cached.evaluatedAtNanos > MAX_CACHE_AGE_NANOS) return false
        for (index in cached.tagNames.indices) {
            if (resolver(cached.tagNames[index]).expressionValue() != cached.tagValues[index]) {
                return false
            }
        }
        return true
    }

    private fun rememberValue(
        splicedExpression: String,
        value: LuaValue?,
        tagNames: List<String>,
        tagValues: List<String?>,
        impure: Boolean,
    ) {
        // 表 / 函数一律按不纯记：它们是可变对象，缓存等于把同一个实例反复交出去，face 代码
        // 改一下就污染后续所有帧。**但条目仍然要写进去** —— 那是「探过了」的记录，
        // 否则每一帧都会为它重探一次（两次求值），比不缓存还慢。
        val cacheable = value == null || value.isboolean() || value.isnumber() || value.isstring()
        val entry = EvaluatedAttribute(
            value = if (cacheable) value else null,
            generation = faceStateGeneration,
            externalDataVersion = externalDataVersion(),
            evaluatedAtNanos = System.nanoTime(),
            tagNames = tagNames.toTypedArray(),
            tagValues = tagValues.toTypedArray(),
            impure = impure || !cacheable,
        )
        synchronized(evaluatedAttributes) { evaluatedAttributes[splicedExpression] = entry }
    }

    /** 纯度探测用的比较键。表 / 函数走 LuaJ 的身份字符串，天然每次都不同 → 判为不纯。 */
    private fun identityKey(value: LuaValue?): String? = value?.tojstring()

    private fun invalidateEvaluatedAttributes() {
        faceStateGeneration++
    }

    // spliceTags 结果按原文缓存：一张表盘的属性表达式集合是稳定的，几十到几百条。
    private val splicedExpressions = HashMap<String, String>()

    private fun spliceTagsCached(expression: String): String =
        synchronized(splicedExpressions) {
            splicedExpressions[expression] ?: spliceTags(expression).also { spliced ->
                if (splicedExpressions.size >= SPLICE_CACHE_LIMIT) splicedExpressions.clear()
                splicedExpressions[expression] = spliced
            }
        }

    private fun compile(source: String, chunkName: String): LuaValue =
        // String input can only contain source text; unlike InputStream loads it
        // cannot select LuaJ's binary-chunk mode.
        globals.load(source, chunkName)

    private inline fun <T> runLimited(block: () -> T): Result<T> {
        if (closed) return Result.failure(IllegalStateException("face Lua runtime is closed"))
        stringAllocationBudget.reset()
        val setHook = internalDebugTable.get("sethook")
        // hook 计数对象与参数包每次求值都要用；InstructionLimitHook 加了 reset，
        // 复用同一实例，省掉每属性两次 varargs 装配和钩子对象分配。
        val hook = instructionLimitHook
        hook.reset()
        return runCatching {
            setHook.invoke(hookArgs)
            block()
        }.mapFailure { error ->
            if (error.message.orEmpty().contains(INSTRUCTION_LIMIT_MESSAGE)) {
                IllegalStateException(INSTRUCTION_LIMIT_MESSAGE, error)
            } else {
                error
            }
        }.also {
            runCatching { setHook.invoke(hookOffArgs) }
            sandboxDebug.resetFrames()
        }
    }

    private inner class LiveTagLookup : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            // Called as wm_tag(name) and as the globals metatable's
            // __index(table, name); the key is the last argument in both forms.
            val name = args.arg(args.narg())
            val normalized = name.checkjstring().trim().lowercase(Locale.ROOT)
            val resolved = resolveTag(normalized).expressionValue()
            // 记下「这次求值依赖了哪个标签、当时是什么值」，供结果缓存的命中判定。
            // 同一个标签被读多次就记多次：多比几个字符串远比进一次 Lua 便宜。
            recordedTagNames?.let { names ->
                names += normalized
                recordedTagValues?.add(resolved)
            }
            return resolved?.toLuaInput() ?: NIL
        }
    }

    /** Reads a braced `var_*` script value without changing ordinary Lua globals. */
    private inner class ScriptVariableLookup : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            val name = args.arg(args.narg()).checkjstring().trim()
            if (!SCRIPT_VARIABLE_PATTERN.matches(name)) return LuaValue.NIL
            val global = globals.rawget(name)
            return when {
                global.isboolean() || global.isnumber() || global.isstring() -> global
                name in dynamicFaceNames -> LuaValue.NIL
                else -> LuaValue.ZERO
            }
        }
    }

    private class InstructionLimitHook : VarArgFunction() {
        private var instructions = 0

        fun reset() {
            instructions = 0
        }

        override fun invoke(args: Varargs): Varargs {
            instructions += INSTRUCTION_HOOK_INTERVAL
            if (instructions > MAX_INSTRUCTIONS_PER_ENTRY) throw InstructionLimitError()
            return NONE
        }
    }

    private abstract class BoundedLibraryFunction(
        private val delegate: LuaValue,
        protected val allocationBudget: StringAllocationBudget,
    ) : VarArgFunction() {
        protected fun invokeDelegate(args: Varargs): Varargs = delegate.invoke(args)

        protected fun rejectOversized(resultBytes: Long) {
            if (resultBytes > MAX_STRING_RESULT_BYTES) throw StringResultLimitError()
        }

        protected fun reserve(resultBytes: Long) {
            rejectOversized(resultBytes)
            allocationBudget.reserve(resultBytes)
        }
    }

    private class BoundedStringRep(
        delegate: LuaValue,
        allocationBudget: StringAllocationBudget,
    ) : BoundedLibraryFunction(delegate, allocationBudget) {
        override fun invoke(args: Varargs): Varargs {
            val unitBytes = args.checkstring(1).rawlen().toLong()
            val repetitions = args.checkint(2)
            if (repetitions >= 0) reserve(unitBytes * repetitions)
            return invokeDelegate(args)
        }
    }

    private class BoundedStringFormat(
        delegate: LuaValue,
        allocationBudget: StringAllocationBudget,
    ) : BoundedLibraryFunction(delegate, allocationBudget) {
        override fun invoke(args: Varargs): Varargs {
            val format = args.checkstring(1)
            var upperBound = format.rawlen().toLong() * 2L
            var argumentIndex = 2
            var index = 0
            while (index < format.rawlen()) {
                if (format.luaByte(index) != '%'.code) {
                    index++
                    continue
                }
                index++
                if (index >= format.rawlen()) break
                if (format.luaByte(index) == '%'.code) {
                    index++
                    continue
                }
                while (index < format.rawlen() && format.luaByte(index).toChar() in FORMAT_FLAGS) index++
                var width = 0
                while (index < format.rawlen() && format.luaByte(index).toChar().isDigit()) {
                    width = (width * 10 + format.luaByte(index) - '0'.code).coerceAtMost(MAX_STRING_RESULT_BYTES)
                    index++
                }
                if (index < format.rawlen() && format.luaByte(index) == '.'.code) {
                    index++
                    while (index < format.rawlen() && format.luaByte(index).toChar().isDigit()) index++
                }
                if (index >= format.rawlen()) break
                val conversion = format.luaByte(index).toChar()
                index++
                val value = args.arg(argumentIndex++)
                val convertedBytes = when (conversion) {
                    's' -> value.checkstring().rawlen().toLong()
                    'q' -> value.checkstring().rawlen().toLong() * 4L + 2L
                    'c' -> 1L
                    else -> 128L
                }
                upperBound += maxOf(width.toLong(), convertedBytes)
                rejectOversized(upperBound)
            }
            val formatted = formatLuaString(args)
            reserve(formatted.toByteArray(Charsets.UTF_8).size.toLong())
            return LuaValue.valueOf(formatted)
        }
    }

    private class BoundedTableConcat(
        delegate: LuaValue,
        allocationBudget: StringAllocationBudget,
    ) : BoundedLibraryFunction(delegate, allocationBudget) {
        override fun invoke(args: Varargs): Varargs {
            val table = args.checktable(1)
            val separatorBytes = args.optstring(2, LuaValue.EMPTYSTRING).rawlen().toLong()
            val first = args.optint(3, 1)
            val last = args.optint(4, table.length())
            val count = (last.toLong() - first.toLong() + 1L).coerceAtLeast(0L)
            if (count > MAX_TABLE_CONCAT_ITEMS) throw StringResultLimitError()
            var resultBytes = separatorBytes * (count - 1L).coerceAtLeast(0L)
            var index = first
            while (index.toLong() <= last.toLong()) {
                resultBytes += table.get(index).checkstring().rawlen().toLong()
                rejectOversized(resultBytes)
                if (index == Int.MAX_VALUE) break
                index++
            }
            reserve(resultBytes)
            return invokeDelegate(args)
        }
    }

    private class BoundedStringGsub(
        delegate: LuaValue,
        allocationBudget: StringAllocationBudget,
    ) : BoundedLibraryFunction(delegate, allocationBudget) {
        override fun invoke(args: Varargs): Varargs {
            val sourceBytes = args.checkstring(1).rawlen().toLong()
            val maxMatches = args.optint(4, (sourceBytes + 1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                .coerceAtLeast(0)
                .toLong()
                .coerceAtMost(sourceBytes + 1L)
            val replacement = args.arg(3)
            val forwarded = Array(args.narg()) { args.arg(it + 1) }
            when {
                replacement.isstring() -> {
                    val template = replacement.checkstring()
                    var literalBytes = 0L
                    var captureReferences = 0L
                    var index = 0
                    while (index < template.rawlen()) {
                        if (template.luaByte(index) == '%'.code && index + 1 < template.rawlen()) {
                            index++
                            if (template.luaByte(index).toChar().isDigit()) captureReferences++ else literalBytes++
                        } else {
                            literalBytes++
                        }
                        index++
                    }
                    rejectOversized(
                        sourceBytes + captureReferences * sourceBytes + literalBytes * maxMatches,
                    )
                }
                replacement.isfunction() -> {
                    allocationBudget.reserve(sourceBytes)
                    forwarded[2] = BoundedGsubReplacement(
                        delegate = replacement,
                        allocationBudget = allocationBudget,
                        sourceBytes = sourceBytes,
                    )
                }
                replacement.istable() -> {
                    val table = replacement.checktable()
                    if (table.keyCount() > MAX_GSUB_REPLACEMENT_TABLE_ITEMS) {
                        throw StringResultLimitError()
                    }
                    var maximumValueBytes = 0L
                    var key = LuaValue.NIL
                    while (true) {
                        val next = table.next(key)
                        key = next.arg1()
                        if (key.isnil()) break
                        val value = next.arg(2)
                        if (value.isstring()) {
                            maximumValueBytes = maxOf(maximumValueBytes, value.checkstring().rawlen().toLong())
                        }
                    }
                    reserve(sourceBytes + maximumValueBytes * maxMatches)
                }
            }
            if (replacement.isstring()) {
                reserve(sourceBytes + replacement.checkstring().rawlen().toLong() * maxMatches)
            }
            return invokeDelegate(LuaValue.varargsOf(forwarded))
        }
    }

    private class BoundedGsubReplacement(
        private val delegate: LuaValue,
        private val allocationBudget: StringAllocationBudget,
        sourceBytes: Long,
    ) : VarArgFunction() {
        private var remainingBytes = (MAX_STRING_RESULT_BYTES.toLong() - sourceBytes).coerceAtLeast(0L)

        override fun invoke(args: Varargs): Varargs {
            val result = delegate.invoke(args)
            val value = result.arg1()
            if (value.isstring()) {
                val resultBytes = value.checkstring().rawlen().toLong()
                remainingBytes -= resultBytes
                if (remainingBytes < 0L) throw StringResultLimitError()
                allocationBudget.reserve(resultBytes)
            }
            return result
        }
    }

    private class SandboxDebugLib(
        private val allocationBudget: StringAllocationBudget,
    ) : DebugLib() {
        private data class Frame(
            val closure: LuaClosure?,
            val stack: Array<LuaValue>?,
        )

        private val frames = ArrayDeque<Frame>()

        override fun onCall(function: LuaFunction) {
            frames.addLast(Frame(null, null))
            super.onCall(function)
        }

        override fun onCall(closure: LuaClosure, varargs: Varargs, stack: Array<LuaValue>) {
            frames.addLast(Frame(closure, stack))
            super.onCall(closure, varargs, stack)
        }

        override fun onInstruction(pc: Int, varargs: Varargs, top: Int) {
            val frame = frames.peekLast()
            val instruction = frame?.closure?.p?.code?.getOrNull(pc)
            if (instruction != null && Lua.GET_OPCODE(instruction) == Lua.OP_CONCAT) {
                val first = Lua.GETARG_B(instruction)
                val last = Lua.GETARG_C(instruction)
                var resultBytes = 0L
                for (index in first..last) {
                    val value = frame.stack?.getOrNull(index) ?: continue
                    if (!value.isstring()) continue
                    resultBytes += value.checkstring().rawlen().toLong()
                    if (resultBytes > MAX_STRING_RESULT_BYTES) throw StringResultLimitError()
                }
                allocationBudget.reserve(resultBytes)
            }
            super.onInstruction(pc, varargs, top)
        }

        override fun onReturn() {
            try {
                super.onReturn()
            } finally {
                if (frames.isNotEmpty()) frames.removeLast()
            }
        }

        fun resetFrames() {
            frames.clear()
        }
    }

    /** Error (not Exception) so a face's Lua pcall/xpcall cannot swallow the host budget. */
    private class InstructionLimitError : Error(INSTRUCTION_LIMIT_MESSAGE)

    /** Host allocation guard; face pcall/xpcall must not be able to bypass it. */
    private class StringResultLimitError : Error(STRING_RESULT_LIMIT_MESSAGE)

    private class StringAllocationBudget {
        private var remainingBytes = MAX_STRING_ALLOCATION_BYTES_PER_ENTRY.toLong()

        fun reset() {
            remainingBytes = MAX_STRING_ALLOCATION_BYTES_PER_ENTRY.toLong()
        }

        fun reserve(bytes: Long) {
            if (bytes < 0L || bytes > remainingBytes) throw StringResultLimitError()
            remainingBytes -= bytes
        }
    }

    override fun close() {
        closed = true
        resolveTag = { WatchTagResolution.Unsupported(it) }
        lastFrameElapsedSeconds = null
        lastWallSecond = null
        lastWallMinute = null
        lastWallHour = null
        synchronized(compiledExpressions) { compiledExpressions.clear() }
    }

    companion object {
        private const val MAX_SCRIPT_BYTES = 256 * 1024
        private const val MAX_SNIPPET_BYTES = 32 * 1024
        private const val MAX_EXPRESSION_CHARS = 32 * 1024
        private const val INSTRUCTION_HOOK_INTERVAL = 1_000
        private const val MAX_INSTRUCTIONS_PER_ENTRY = 2_000_000
        private const val MAX_CACHED_EXPRESSIONS = 256

        /**
         * 属性结果缓存的兜底寿命：200ms。
         *
         * 标签变化、face 状态变化、副作用型表达式都有各自的精确判据（见 [EvaluatedAttribute]），
         * 这一条只管「万一还有别的隐藏输入」，把最坏陈旧时间钉在 200ms —— 颜色/文字上看不出来，
         * 而 60fps 下真求值仍然从「每秒每表达式 60 次」降到 5 次。
         */
        private const val MAX_CACHE_AGE_NANOS = 200_000_000L

        /** spliceTags 结果缓存的条数上限；一张表盘的表达式集合远到不了这个量。 */
        private const val SPLICE_CACHE_LIMIT = 1024

        /** 单个表达式读到的标签数量通常是 1~2 个，列表初值给小的。 */
        private const val TAG_RECORD_INITIAL_CAPACITY = 4
        private const val MAX_STRING_RESULT_BYTES = 1024 * 1024
        private const val MAX_STRING_ALLOCATION_BYTES_PER_ENTRY = 8 * 1024 * 1024
        private const val MAX_TABLE_CONCAT_ITEMS = 65_536L
        private const val MAX_GSUB_REPLACEMENT_TABLE_ITEMS = 65_536
        internal const val MAX_CACHED_EXPRESSIONS_FOR_TEST = MAX_CACHED_EXPRESSIONS
        private const val STRING_RESULT_LIMIT_MESSAGE =
            "string result limit exceeded in face script"
        private const val FORMAT_FLAGS = "-+ #0"
        private const val INSTRUCTION_LIMIT_MESSAGE =
            "instruction limit exceeded (possible infinite loop in face script)"

        /**
         * 脚本全局变量的形态。WatchMaker 约定所有脚本写入的全局都以 `var_` 开头
         * （CloX `wf_expr.lua:205` 原文：「ONLY script variables (var_* — always
         * that prefix, even when the script hasn't WRITTEN them yet)」），所以这个
         * 形态本身就是「这是一个脚本变量」的判据，不需要先跑脚本。
         *
         * 与 CloX `identDeps` 的 `var_[%w_]*` 同形；大小写敏感 —— Lua 全局本来就是。
        */
        private val SCRIPT_VARIABLE_PATTERN = Regex("var_[A-Za-z0-9_]*")
        private val BRACED_SCRIPT_VARIABLE_PATTERN = Regex(
            """\{\s*var_[A-Za-z0-9_]*\s*\}""",
        )

        private val DECLARED_FACE_NAME = Regex(
            "(?:\\bfunction\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(|\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=)",
        )
        private val BRACED_WM_TAG = Regex(
            "wm_tag\\s*\\(\\s*(['\"])\\{([^\\{\\}]+)\\}\\1\\s*\\)",
        )
        private val BUILTIN_ROOTS = setOf(
            "and", "break", "do", "else", "elseif", "end", "false", "for", "function",
            "if", "in", "local", "nil", "not", "or", "repeat", "return", "then", "true",
            "until", "while", "math", "string", "table", "os", "tonumber", "tostring", "type",
        )

        /**
         * @param readExternalSlot clox External Data API 的槽位读取器（B5）。缺省返回空槽位 ——
         *   离线测试与不需要外部数据的调用点都不必接线。传进来的值**只当字符串**用：
         *   见 [WatchExternalData] 里那段安全基线。
         */
        fun create(
            initialScript: String?,
            resolveTag: (String) -> WatchTagResolution = { WatchTagResolution.Unsupported(it) },
            readExternalSlot: (Int) -> WatchExternalDataSlot = { WatchExternalData.EMPTY_SLOT },
            externalDataVersion: () -> Long = { 0L },
        ): WatchFaceLuaRuntime {
            val script = initialScript.orEmpty()
            require(script.toByteArray(Charsets.UTF_8).size <= MAX_SCRIPT_BYTES) {
                "face script is too large"
            }
            val globals = Globals()
            globals.load(BaseLib())
            // LuaJ's standard-library initialisers register themselves in
            // package.loaded.  Give them a private bootstrap table, then erase
            // the whole package surface before any face source is compiled.
            globals.set(
                "package",
                LuaValue.tableOf(
                    arrayOf(LuaValue.valueOf("loaded"), LuaValue.tableOf()),
                ),
            )
            globals.load(TableLib())
            globals.load(StringLib())
            val stringAllocationBudget = StringAllocationBudget()
            val stringLibrary = globals.get("string")
            stringLibrary.set(
                "rep",
                BoundedStringRep(stringLibrary.get("rep"), stringAllocationBudget),
            )
            stringLibrary.set(
                "format",
                BoundedStringFormat(stringLibrary.get("format"), stringAllocationBudget),
            )
            stringLibrary.set(
                "gsub",
                BoundedStringGsub(stringLibrary.get("gsub"), stringAllocationBudget),
            )
            val tableLibrary = globals.get("table")
            tableLibrary.set(
                "concat",
                BoundedTableConcat(tableLibrary.get("concat"), stringAllocationBudget),
            )
            globals.load(JseMathLib())
            globals.load(JseOsLib())
            val loadedOs = globals.get("os")
            val safeOs = LuaValue.tableOf()
            for (name in listOf("time", "date", "clock", "difftime")) {
                loadedOs.get(name).takeUnless(LuaValue::isnil)?.let { safeOs.set(name, it) }
            }
            val debug = SandboxDebugLib(stringAllocationBudget)
            globals.load(debug)
            val internalDebugTable = globals.get("debug")
            LuaC.install(globals)

            // CloX whitelist: basics + private math/string/table and safe os
            // time functions.  LuaJ has no useful Android os implementation, so
            // expose an empty private table rather than its desktop JSE library.
            val allowedBasics = setOf(
                "_G", "_VERSION", "assert", "error", "pcall", "xpcall", "print",
                "tonumber", "tostring", "type", "pairs", "ipairs", "next", "select",
                "unpack", "math", "string", "table", "os",
            )
            val keys = mutableListOf<LuaValue>()
            var key = LuaValue.NIL
            while (true) {
                val next = globals.next(key)
                key = next.arg1()
                if (key.isnil()) break
                keys += key
            }
            keys.forEach { current ->
                if (current.tojstring() !in allowedBasics) globals.set(current, LuaValue.NIL)
            }
            globals.set("os", safeOs)
            globals.set("unpack", globals.get("table").get("unpack"))
            // Self-reference stays inside this per-face box.
            globals.set("_G", globals)

            val runtime = WatchFaceLuaRuntime(
                globals = globals,
                sandboxDebug = debug,
                stringAllocationBudget = stringAllocationBudget,
                internalDebugTable = internalDebugTable,
                declaredFaceNames = declaredFaceNames(script),
                externalDataVersion = externalDataVersion,
            )
            runtime.resolveTag = resolveTag
            globals.set("wm_tag", runtime.LiveTagLookup())
            // Braced WatchMaker script variables use a separate bridge so an
            // undeclared `{var_*}` can be neutral numeric zero without changing
            // ordinary Lua reads of a missing global (which must stay nil).
            globals.set("wm_var", runtime.ScriptVariableLookup())
            // WF.thirdParty.slots：clox External Data API 的读取面。
            //
            // 注意**必须装在白名单裁剪之后** —— 上面那一轮 `set(current, LuaValue.NIL)`
            // 会把不在名单里的全局统统抹掉，先装就白装了。
            //
            // 用惰性查表（`__index` 函数）而不是「广播来了推一次」：推送要跨线程碰
            // Lua 状态机，而表盘每帧都在读，惰性查的代价只是一次内存数组索引，
            // 却天然不会读到半个写入。
            globals.set("WF", externalDataTable(readExternalSlot))
            globals.setmetatable(
                LuaValue.tableOf(
                    arrayOf(
                        LuaValue.valueOf("__index"), runtime.LiveTagLookup(),
                    ),
                ),
            )
            if (script.isNotBlank()) {
                val sanitizedScript = sanitizeWatchmakerScript(script)
                runtime.runLimited {
                    runtime.compile(spliceTags(sanitizedScript), "=watch_script").call()
                    Unit
                }.getOrThrow()
            }
            return runtime
        }

        /**
         * 兼容转换大型表盘脚本：LuaJ 单函数局部变量上限为 200（LUAI_MAXVARS = 200）。
         * 当表盘脚本单函数内声明超过 200 个局部变量时（如 TetoLive2D 的 step 函数内有 266 个 local），
         * LuaJ 编译期直接抛出 NullPointerException / LuaError。
         * 在表盘沙箱内将普通的局部变量声明安全规范化为变量赋值，保证脚本顺利编译执行。
         */
        internal fun sanitizeWatchmakerScript(script: String): String {
            if (!script.contains("local ")) return script
            return script.lineSequence().map { line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("local ") && !trimmed.startsWith("local function ")) {
                    val leading = line.substring(0, line.indexOf("local "))
                    val rest = line.substring(line.indexOf("local ") + 6).trim()
                    if (rest.contains("=")) {
                        leading + rest
                    } else {
                        val count = rest.count { it == ',' } + 1
                        val nils = (1..count).joinToString(", ") { "nil" }
                        leading + rest + " = " + nils
                    }
                } else {
                    line
                }
            }.joinToString("\n")
        }

        /**
         * `WF = { thirdParty = { slots = <惰性 1..10> } }`。
         *
         * 槽位表本身是空表 + `__index`：没写过的槽位也返回 `{data="",key="",timestamp=0}`
         * 而不是 nil，表盘脚本才能无条件 `slots[n].data`（上游文档保证的就是这个）。
         * 越界索引返回 nil —— 那是脚本写错了，给个空槽位反而会把错误藏起来。
         */
        private fun externalDataTable(
            readExternalSlot: (Int) -> WatchExternalDataSlot,
        ): LuaValue {
            val slots = LuaValue.tableOf()
            slots.setmetatable(
                LuaValue.tableOf(
                    arrayOf(
                        LuaValue.valueOf("__index"),
                        object : VarArgFunction() {
                            override fun invoke(args: Varargs): Varargs {
                                // __index(table, key)：键是最后一个参数。
                                val index = args.arg(args.narg()).toint()
                                if (
                                    index < WatchExternalData.MIN_SLOT ||
                                    index > WatchExternalData.MAX_SLOT
                                ) {
                                    return NIL
                                }
                                val slot = readExternalSlot(index)
                                return LuaValue.tableOf(
                                    arrayOf(
                                        LuaValue.valueOf("data"), LuaValue.valueOf(slot.data),
                                        LuaValue.valueOf("key"), LuaValue.valueOf(slot.key),
                                        LuaValue.valueOf("timestamp"),
                                        LuaValue.valueOf(slot.timestampMs.toDouble()),
                                    ),
                                )
                            }
                        },
                    ),
                ),
            )
            val thirdParty = LuaValue.tableOf(
                arrayOf(LuaValue.valueOf("slots"), slots),
            )
            return LuaValue.tableOf(
                arrayOf(LuaValue.valueOf("thirdParty"), thirdParty),
            )
        }

        private fun declaredFaceNames(source: String): Set<String> =
            DECLARED_FACE_NAME.findAll(source)
                .mapTo(LinkedHashSet()) { match ->
                    match.groupValues[1].ifBlank { match.groupValues[2] }
                }

        /**
         * LuaJ's JSE formatter does not consistently honour floating-point
         * precision (for example `%.1f` may render `1.44`).  WatchMaker and
         * CloX expect ordinary printf semantics, so format the bounded subset
         * explicitly while keeping the surrounding allocation guard.
         */
        private fun formatLuaString(args: Varargs): String {
            val format = args.checkjstring(1)
            val output = StringBuilder(format.length + 16)
            var index = 0
            var argumentIndex = 2
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
                val flagsStart = index
                while (index < format.length && format[index] in FORMAT_FLAGS) index++
                val flags = format.substring(flagsStart, index)
                val widthStart = index
                while (index < format.length && format[index].isDigit()) index++
                val width = format.substring(widthStart, index).toIntOrNull() ?: 0
                require(width <= MAX_STRING_RESULT_BYTES) { STRING_RESULT_LIMIT_MESSAGE }
                var precision: Int? = null
                if (index < format.length && format[index] == '.') {
                    index++
                    val precisionStart = index
                    while (index < format.length && format[index].isDigit()) index++
                    precision = format.substring(precisionStart, index).toIntOrNull() ?: 0
                    require(precision <= MAX_STRING_RESULT_BYTES) { STRING_RESULT_LIMIT_MESSAGE }
                }
                require(index < format.length) { "incomplete format specifier" }
                val conversion = format[index++]
                val value = args.arg(argumentIndex++)
                val spec = format.substring(specStart, index)
                val rendered = when (conversion) {
                    'd', 'i' -> formatLuaInteger(
                        value = value.checklong(),
                        flags = flags,
                        width = width,
                        precision = precision,
                        unsigned = false,
                    )
                    'o', 'x', 'X' -> String.format(Locale.ROOT, spec, value.checklong())
                    'u' -> formatLuaInteger(
                        value = value.checklong(),
                        flags = flags,
                        width = width,
                        precision = precision,
                        unsigned = true,
                    )
                    'f', 'e', 'E', 'g', 'G' ->
                        String.format(Locale.ROOT, spec, value.checkdouble())
                    'c' -> applyLuaFormatWidth(
                        value.checkint().toChar().toString(),
                        flags,
                        width,
                        precision = null,
                    )
                    's' -> applyLuaFormatWidth(
                        value.tojstring(),
                        flags,
                        width,
                        precision,
                    )
                    'q' -> applyLuaFormatWidth(
                        quoteLuaString(value.tojstring()),
                        flags,
                        width,
                        precision = null,
                    )
                    else -> error("unsupported format type: $conversion")
                }
                output.append(rendered)
                require(output.toString().toByteArray(Charsets.UTF_8).size <= MAX_STRING_RESULT_BYTES) {
                    STRING_RESULT_LIMIT_MESSAGE
                }
            }
            return output.toString()
        }

        private fun applyLuaFormatWidth(
            raw: String,
            flags: String,
            width: Int,
            precision: Int?,
        ): String {
            val value = precision?.let(raw::take) ?: raw
            if (value.length >= width) return value
            val padding = width - value.length
            if ('-' in flags) return value + " ".repeat(padding)
            val pad = if ('0' in flags) '0' else ' '
            return pad.toString().repeat(padding) + value
        }

        private fun formatLuaInteger(
            value: Long,
            flags: String,
            width: Int,
            precision: Int?,
            unsigned: Boolean,
        ): String {
            val negative = !unsigned && value < 0
            val sign = when {
                negative -> "-"
                '+' in flags -> "+"
                ' ' in flags -> " "
                else -> ""
            }
            val digits = if (unsigned) {
                value.toULong().toString()
            } else {
                value.toString().removePrefix("-")
            }
            val minimumDigits = precision ?: 0
            val paddedDigits = digits.padStart(minimumDigits, '0')
            val combined = sign + paddedDigits
            if (combined.length >= width) return combined
            val padding = width - combined.length
            if ('-' in flags) return combined + " ".repeat(padding)
            if ('0' in flags && precision == null) return sign + "0".repeat(padding) + paddedDigits
            return " ".repeat(padding) + combined
        }

        private fun quoteLuaString(raw: String): String = buildString(raw.length + 2) {
            append('"')
            raw.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    in '\u0000'..'\u001f', '\u007f' -> {
                        append('\\')
                        append(char.code.toString().padStart(3, '0'))
                    }
                    else -> append(char)
                }
            }
            append('"')
        }

        /** Layer expressions may contain WatchMaker tags and quoted labels that are not Lua globals. */
        private fun identifiersOutsideTagsAndStrings(source: String): Sequence<String> = sequence {
            var index = 0
            var quote: Char? = null
            while (index < source.length) {
                val char = source[index]
                if (quote != null) {
                    when {
                        char == '\\' && index + 1 < source.length -> index += 2
                        char == quote -> {
                            quote = null
                            index++
                        }
                        else -> index++
                    }
                    continue
                }
                when {
                    char == '\'' || char == '"' -> {
                        quote = char
                        index++
                    }
                    char == '{' -> {
                        val closing = source.indexOf('}', index + 1)
                        index = if (closing >= 0) closing + 1 else source.length
                    }
                    char.isLetter() || char == '_' -> {
                        val start = index++
                        while (index < source.length &&
                            (source[index].isLetterOrDigit() || source[index] == '_')
                        ) {
                            index++
                        }
                        yield(source.substring(start, index))
                    }
                    else -> index++
                }
            }
        }

        /** CloX-compatible source splicing without rewriting Lua table constructors. */
        private fun spliceTags(source: String): String {
            if ('{' !in source) return source
            // wm_tag accepts both "bl" and "{bl}". Normalize its explicit
            // braced form before the general in-string splice so the resolved
            // value is not accidentally fed back as a second tag name.
            val normalizedSource = BRACED_WM_TAG.replace(source) { match ->
                val rawName = match.groupValues[2].trim()
                val name = rawName.lowercase(Locale.ROOT)
                when {
                    SCRIPT_VARIABLE_PATTERN.matches(rawName) -> "wm_var(${rawName.quoteLua()})"
                    isKnownFaceTag(name) -> "wm_tag(${name.quoteLua()})"
                    else -> match.value
                }
            }
            if ('{' !in normalizedSource) return normalizedSource
            val output = StringBuilder(normalizedSource.length + 32)
            var index = 0
            var quote: Char? = null
            while (index < normalizedSource.length) {
                val char = normalizedSource[index]
                if (quote != null) {
                    when {
                        char == '\\' && index + 1 < normalizedSource.length -> {
                            output.append(char).append(normalizedSource[index + 1])
                            index += 2
                        }
                        char == '{' -> {
                            val end = normalizedSource.indexOf('}', index + 1)
                            val name = normalizedSource.substringOrNull(index + 1, end)?.trim()
                            val spliced = name?.let(::spliceBracedNameInQuote)
                            if (spliced != null) {
                                output.append(quote)
                                    .append(" .. ")
                                    .append(spliced)
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
                } else if (char == '-' && normalizedSource.getOrNull(index + 1) == '-') {
                    val end = normalizedSource.indexOf('\n', index)
                        .let { if (it < 0) normalizedSource.length else it }
                    output.append(normalizedSource, index, end)
                    index = end
                } else if (char == '\'' || char == '"') {
                    quote = char
                    output.append(char)
                    index++
                } else if (char == '{') {
                    val end = normalizedSource.indexOf('}', index + 1)
                    val name = normalizedSource.substringOrNull(index + 1, end)?.trim()
                    val spliced = name?.let(::spliceBracedName)
                    if (spliced != null) {
                        output.append(spliced)
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

        /**
         * 花括号名字 → Lua **取值**源码（引号外）。返回 null 表示原样保留。
         *
         * ## 为什么脚本变量要走 `wm_var('var_x')`
         *
         * 三条理由，缺一都会继续错：
         *
         * 1. **`wm_tag` 只查设备标签。** Lua 路径的 resolver 不回读 face 全局，
         *    所以把 `var_cyc` 当成设备标签会得到 NIL。
         * 2. **普通裸变量不能承担“缺失即 0”。** 表盘脚本里有大量 `var_x = var_x or 1`
         *    和数组/表初始化；改全局 metatable 会把缺失变量变成数字，直接触发
         *    `attempt to index a number`，也会让未声明变量的文字从空白变成 `0`。
         * 3. **专用桥等价于 CloX 的值表。** `wm_var` 直接读沙箱里的脚本全局：已声明值
         *    原样返回，已声明但为 nil 保持 nil，尚未声明才返回属性表达式所需的中性 0。
         *
         * 真实收益（三个 `bm:`/TTF 表盘的实测根因）：
         *
         * - `opacity="{var_cyc} == 0 and 100 or 0"`：修前 `{0} == 0` → false → **0**
         *   → 整层消失；修后 `0 == 0` → **100**。
         * - `text="{var_s_t1}"`：修前整串被当表构造器，渲染出 Lua 表地址
         *   （`table: 4decbe5`）；修后直接得到变量本身的文本。
         */
        private fun spliceBracedName(name: String): String? {
            scriptValueSplice(name)?.let { return it }
            return if (isKnownFaceTag(name)) "wm_tag(${name.lowercase(Locale.ROOT).quoteLua()})" else null
        }

        /** 引号内：字符串拼接，用 tostring 兜住 nil，避免整条表达式报错。 */
        private fun spliceBracedNameInQuote(name: String): String? {
            scriptValueSplice(name)?.let { return "tostring($it or '')" }
            return if (isKnownFaceTag(name)) "wm_tag(${name.lowercase(Locale.ROOT).quoteLua()})" else null
        }

        /**
         * 脚本变量的取值源码。只接受合法的 `var_*` 名字，避免把任意文本拼进 Lua。
         *
         * `tweens.*` 不在这里 —— 它是设备/动画标签，继续走 `wm_tag`。
         */
        private fun scriptValueSplice(name: String): String? =
            if (SCRIPT_VARIABLE_PATTERN.matches(name)) "wm_var(${name.quoteLua()})" else null

        /**
         * 花括号里哪些名字属于**目录标签**，该走 `wm_tag`。
         *
         * 脚本变量（`var_*`）在调用本函数前已由 [scriptValueSplice] 拦截，
         * 不得把 Lua 表构造器或任意 `var_...` 文本误判成标签。
         */
        private fun isKnownFaceTag(name: String): Boolean =
            name.startsWith("tweens.", ignoreCase = true) ||
                WatchmakerTagResolver.isKnown(name)

        private fun String.substringOrNull(startIndex: Int, endIndex: Int): String? =
            if (endIndex >= startIndex) substring(startIndex, endIndex) else null

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

        private fun renderLuaValue(value: LuaValue): String = when {
            value.isboolean() -> value.toboolean().toString()
            // LuaJ's isnumber() also returns true for numeric-looking strings.
            // Color text such as "0066e6" must keep its exact string bytes.
            value.type() == LuaValue.TNUMBER -> ExpressionValue.Numeric(value.todouble()).renderText()
            value.type() == LuaValue.TSTRING -> value.tojstring()
            else -> value.tojstring()
        }

        private fun String.toLuaInput(): LuaValue = when {
            equals("true", true) -> LuaValue.TRUE
            equals("false", true) -> LuaValue.FALSE
            equals("nil", true) -> LuaValue.NIL
            toDoubleOrNull()?.isFinite() == true -> LuaValue.valueOf(toDouble())
            else -> LuaValue.valueOf(this)
        }

        private inline fun <T> Result<T>.mapFailure(transform: (Throwable) -> Throwable): Result<T> =
            fold(onSuccess = { Result.success(it) }, onFailure = { Result.failure(transform(it)) })
    }
}
