package com.flue.launcher.aod

data class AodConfig(
    val enabled: Boolean = false,
    val timeoutSeconds: Int = 30,
    val brightnessPercent: Int = 10,
    val lockGestures: Boolean = true,
    val lockDelaySeconds: Int = 5,
    val reawaken: Boolean = true,
    val powerButtonExit: Boolean = true,
    val shallowSleep: Boolean = false,
    val alwaysWhenCharging: Boolean = false,
) {
    fun active(charging: Boolean): Boolean = enabled || (charging && alwaysWhenCharging)
}

enum class AodScreenOffAction { NONE, ENTER_AMBIENT, EXIT_AMBIENT, KEEP_AMBIENT }

fun aodScreenOffAction(config: AodConfig, charging: Boolean, locked: Boolean, canForeground: Boolean): AodScreenOffAction = when {
    !config.active(charging) || !config.reawaken || !canForeground -> AodScreenOffAction.NONE
    locked && config.powerButtonExit -> AodScreenOffAction.EXIT_AMBIENT
    locked -> AodScreenOffAction.KEEP_AMBIENT
    else -> AodScreenOffAction.ENTER_AMBIENT
}

/** Shared by capture tests and the controller: stale work cannot outlive a settings/lifecycle change. */
internal class AodGeneration {
    var value = 0L
        private set
    fun invalidate() { value++ }
    fun accepts(token: Long, active: Boolean): Boolean = active && token == value
}
