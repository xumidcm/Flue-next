package com.flue.launcher.watchface.agent.tools

import com.flue.launcher.watchface.standard.WatchFaceLuaRuntime

/** Compiles the persisted WatchMaker global script with the same sandbox used at render time. */
internal fun validateGlobalScript(source: String): WatchAgentDiagnostic? {
    if (source.isBlank()) return null
    return runCatching { WatchFaceLuaRuntime.create(initialScript = source) }
        .exceptionOrNull()
        ?.let { error ->
            WatchAgentDiagnostic(
                severity = WatchAgentDiagnosticSeverity.ERROR,
                code = "invalid_global_script",
                message = "全局脚本无法编译或执行：${error.displayableScriptError()}",
            )
        }
}

private fun Throwable.displayableScriptError(): String =
    generateSequence(this) { it.cause }
        .lastOrNull()
        ?.message
        ?.takeIf(String::isNotBlank)
        ?.replace(Regex("\\s+"), " ")
        ?.take(240)
        ?: javaClass.simpleName
