package com.flue.launcher.watchface.agent.tools

import com.flue.launcher.watchface.standard.WatchFaceLuaRuntime

/** Compiles the persisted WatchMaker global script with the same sandbox used at render time. */
internal fun validateGlobalScript(source: String): WatchAgentDiagnostic? {
    if (source.isBlank()) return null
    return runCatching {
        val runtime = WatchFaceLuaRuntime.create(initialScript = source)
        try {
            // Compilation alone misses callback failures (for example a
            // missing function referenced by on_second). Execute the same
            // bounded first clock tick used by the renderer so validate_face
            // reports failures before preview or a physical watch does.
            runtime.stepClock(
                elapsedSeconds = 0.0,
                wallTimeMillis = System.currentTimeMillis(),
            ).getOrThrow()
        } finally {
            runtime.close()
        }
    }
        .exceptionOrNull()
        ?.let { error ->
            WatchAgentDiagnostic(
                severity = WatchAgentDiagnosticSeverity.ERROR,
                code = "invalid_global_script",
                message = "全局脚本无法编译或运行：${error.displayableScriptError()}",
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
