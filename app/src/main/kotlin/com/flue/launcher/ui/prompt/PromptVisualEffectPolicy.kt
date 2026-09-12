package com.flue.launcher.ui.prompt

enum class PromptBackgroundEffect {
    SYSTEM_RENDER_EFFECT,
    PIXEL_COPY,
    DIM_ONLY,
}

enum class PromptTextEffect {
    BLUR_RISE,
    FADE,
}

data class PromptVisualEffectPolicy(
    val background: PromptBackgroundEffect,
    val text: PromptTextEffect,
    val enterDurationMillis: Int,
    val exitDurationMillis: Int,
)

fun promptVisualEffectPolicy(
    sdkInt: Int,
    advancedMaterial: Boolean,
    reduceMotion: Boolean,
): PromptVisualEffectPolicy {
    val background = when {
        sdkInt >= 31 -> PromptBackgroundEffect.SYSTEM_RENDER_EFFECT
        sdkInt >= 27 -> PromptBackgroundEffect.PIXEL_COPY
        else -> PromptBackgroundEffect.DIM_ONLY
    }
    return PromptVisualEffectPolicy(
        background = background,
        text = if (sdkInt >= 31 && advancedMaterial && !reduceMotion) {
            PromptTextEffect.BLUR_RISE
        } else {
            PromptTextEffect.FADE
        },
        enterDurationMillis = if (reduceMotion) 100 else 360,
        exitDurationMillis = if (reduceMotion) 100 else 240,
    )
}
