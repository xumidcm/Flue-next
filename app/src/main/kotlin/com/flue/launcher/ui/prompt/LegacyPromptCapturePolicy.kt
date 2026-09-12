package com.flue.launcher.ui.prompt

import kotlin.math.roundToInt

data class LegacyPromptCaptureSize(
    val width: Int,
    val height: Int,
) {
    val longEdge: Int get() = maxOf(width, height)
    val shortEdge: Int get() = minOf(width, height)
    val byteCount: Int get() = width * height * 4
}

fun legacyPromptCaptureSize(
    width: Int,
    height: Int,
    maxLongEdge: Int = 96,
    minShortEdge: Int = 40,
): LegacyPromptCaptureSize {
    if (width <= 0 || height <= 0) return LegacyPromptCaptureSize(1, 1)
    val longEdge = maxOf(width, height).toFloat()
    val initialScale = maxLongEdge / longEdge
    var targetWidth = (width * initialScale).roundToInt().coerceAtLeast(1)
    var targetHeight = (height * initialScale).roundToInt().coerceAtLeast(1)
    val targetShort = minOf(targetWidth, targetHeight)
    if (targetShort < minShortEdge) {
        val correctiveScale = minShortEdge.toFloat() / targetShort
        val correctedWidth = (targetWidth * correctiveScale).roundToInt()
        val correctedHeight = (targetHeight * correctiveScale).roundToInt()
        if (maxOf(correctedWidth, correctedHeight) <= maxLongEdge) {
            targetWidth = correctedWidth
            targetHeight = correctedHeight
        }
    }
    return LegacyPromptCaptureSize(targetWidth, targetHeight)
}

class LegacyPromptCaptureGate {
    private var activeToken = 0L
    private var capturedToken: Long? = null

    fun beginChain(): Long {
        activeToken += 1L
        capturedToken = null
        return activeToken
    }

    fun shouldCapture(token: Long): Boolean =
        token == activeToken && capturedToken != token

    fun shouldAccept(token: Long, foregroundStarted: Boolean): Boolean =
        shouldCapture(token) && !foregroundStarted

    fun markCaptured(token: Long) {
        if (token == activeToken) capturedToken = token
    }

    fun invalidate() {
        activeToken += 1L
        capturedToken = null
    }
}
