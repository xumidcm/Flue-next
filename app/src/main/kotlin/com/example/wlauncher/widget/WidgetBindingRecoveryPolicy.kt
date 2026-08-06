package com.flue.launcher.widget

enum class WidgetBindingDecision {
    VALID,
    RECOVER,
    PROVIDER_UNAVAILABLE,
}

fun widgetBindingDecision(
    savedId: Int,
    expectedProviderKey: String,
    hostIds: Set<Int>,
    actualProviderKey: String?,
    providerInstalled: Boolean,
): WidgetBindingDecision {
    if (!providerInstalled) return WidgetBindingDecision.PROVIDER_UNAVAILABLE
    if (savedId <= 0) return WidgetBindingDecision.RECOVER
    if (savedId !in hostIds) return WidgetBindingDecision.RECOVER
    if (actualProviderKey != expectedProviderKey) return WidgetBindingDecision.RECOVER
    return WidgetBindingDecision.VALID
}

class WidgetRecoveryGate {
    private val started = mutableSetOf<String>()
    private val finished = mutableSetOf<String>()

    fun tryStart(key: String): Boolean = synchronized(this) {
        if (key in started || key in finished) return@synchronized false
        started += key
        true
    }

    fun finish(key: String) = synchronized(this) {
        started -= key
        finished += key
    }

    fun reset(key: String) = synchronized(this) {
        started -= key
        finished -= key
    }
}
