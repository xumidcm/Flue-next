package com.flue.launcher.ui.prompt

enum class PromptRevealClock {
    SHARED_FOREGROUND,
    LOCAL,
}

data class PromptForegroundMotion(
    val titleClock: PromptRevealClock,
    val bodyClock: PromptRevealClock,
    val actionClock: PromptRevealClock,
    val blurWholeForeground: Boolean,
    val pressedScale: Float,
    val releaseDampingRatio: Float,
    val edgeRubberBand: Boolean,
)

fun promptForegroundMotion(reduceMotion: Boolean): PromptForegroundMotion {
    val clock = PromptRevealClock.SHARED_FOREGROUND
    return PromptForegroundMotion(
        titleClock = clock,
        bodyClock = clock,
        actionClock = clock,
        blurWholeForeground = !reduceMotion,
        pressedScale = if (reduceMotion) 1f else 0.96f,
        releaseDampingRatio = if (reduceMotion) 1f else 0.68f,
        edgeRubberBand = !reduceMotion,
    )
}
