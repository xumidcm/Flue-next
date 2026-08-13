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
) : AutoCloseable {
    private var resolveTag: (String) -> WatchTagResolution = { WatchTagResolution.Unsupported(it) }
    private var lastFrameElapsedSeconds: Double? = null
    private var lastWallSecond: Int? = null
    private var lastWallMinute: Int? = null
    private var lastWallHour: Int? = null
    private var closed = false
    private val dynamicFaceNames = LinkedHashSet(declaredFaceNames)
    private val compiledExpressions = object : LinkedHashMap<String, LuaValue>(
        MAX_CACHED_EXPRESSIONS,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LuaValue>): Boolean =
            size > MAX_CACHED_EXPRESSIONS
    }

    fun runSnippet(
        source: String,
        resolver: (String) -> WatchTagResolution = { WatchTagResolution.Unsupported(it) },
    ): Result<Unit> = runLimited {
        require(source.toByteArray(Charsets.UTF_8).size <= MAX_SNIPPET_BYTES) {
            "face snippet is too large"
        }
        resolveTag = resolver
        compile(spliceTags(source), "=watch_tap").call()
        dynamicFaceNames += declaredFaceNames(source)
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

        val wall = java.util.Calendar.getInstance().apply { timeInMillis = wallTimeMillis }
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

    /** True only when the expression references a global defined by face code. */
    fun referencesFaceState(expression: String): Boolean {
        if (closed || expression.isBlank()) return false
        return identifiersOutsideTagsAndStrings(expression).any { name ->
            name !in BUILTIN_ROOTS && (name in dynamicFaceNames || !globals.rawget(name).isnil())
        }
    }

    fun evaluateFaceNumber(
        expression: String,
        resolver: (String) -> WatchTagResolution,
    ): Double? = if (!referencesFaceState(expression)) null else {
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
        val splicedExpression = spliceTags(expression)
        return runLimited {
            resolveTag = resolver
            val chunk = synchronized(compiledExpressions) {
                compiledExpressions[splicedExpression]
                    ?: compile("return ($splicedExpression)", "=watch_attr").also {
                        compiledExpressions[splicedExpression] = it
                    }
            }
            chunk.call()
        }.getOrNull()?.takeUnless(LuaValue::isnil)
    }

    private fun compile(source: String, chunkName: String): LuaValue =
        // String input can only contain source text; unlike InputStream loads it
        // cannot select LuaJ's binary-chunk mode.
        globals.load(source, chunkName)

    private inline fun <T> runLimited(block: () -> T): Result<T> {
        if (closed) return Result.failure(IllegalStateException("face Lua runtime is closed"))
        stringAllocationBudget.reset()
        val setHook = internalDebugTable.get("sethook")
        val hook = InstructionLimitHook()
        return runCatching {
            setHook.invoke(
                LuaValue.varargsOf(
                    arrayOf(hook, LuaValue.valueOf(""), LuaValue.valueOf(INSTRUCTION_HOOK_INTERVAL)),
                ),
            )
            block()
        }.mapFailure { error ->
            if (error.message.orEmpty().contains(INSTRUCTION_LIMIT_MESSAGE)) {
                IllegalStateException(INSTRUCTION_LIMIT_MESSAGE, error)
            } else {
                error
            }
        }.also {
            runCatching { setHook.invoke(LuaValue.varargsOf(arrayOf(LuaValue.NIL))) }
            sandboxDebug.resetFrames()
        }
    }

    private inner class LiveTagLookup : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            // Called as wm_tag(name) and as the globals metatable's
            // __index(table, name); the key is the last argument in both forms.
            val name = args.arg(args.narg())
            val normalized = name.checkjstring().trim().lowercase(Locale.ROOT)
            return resolveTag(normalized).expressionValue()?.toLuaInput() ?: NIL
        }
    }

    private class InstructionLimitHook : VarArgFunction() {
        private var instructions = 0

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

        fun create(
            initialScript: String?,
            resolveTag: (String) -> WatchTagResolution = { WatchTagResolution.Unsupported(it) },
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
            )
            runtime.resolveTag = resolveTag
            globals.set("wm_tag", runtime.LiveTagLookup())
            globals.setmetatable(
                LuaValue.tableOf(
                    arrayOf(
                        LuaValue.valueOf("__index"), runtime.LiveTagLookup(),
                    ),
                ),
            )
            if (script.isNotBlank()) {
                runtime.runLimited {
                    runtime.compile(spliceTags(script), "=watch_script").call()
                    Unit
                }.getOrThrow()
            }
            return runtime
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
                val name = match.groupValues[2].trim().lowercase(Locale.ROOT)
                if (isKnownFaceTag(name)) "wm_tag(${name.quoteLua()})" else match.value
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
                            if (name != null && isKnownFaceTag(name)) {
                                output.append(quote)
                                    .append(" .. wm_tag(")
                                    .append(name.lowercase(Locale.ROOT).quoteLua())
                                    .append(") .. ")
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
                    if (name != null && isKnownFaceTag(name)) {
                        output.append("wm_tag(")
                            .append(name.lowercase(Locale.ROOT).quoteLua())
                            .append(')')
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

        private fun isKnownFaceTag(name: String): Boolean =
            name.startsWith("tweens.", ignoreCase = true) || WatchmakerTagResolver.isKnown(name)

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
