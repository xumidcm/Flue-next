package com.flue.launcher

import kotlin.random.Random

internal data class CrashMessageSelection(
    val titleIndex: Int,
    val subtitleIndex: Int,
)

internal fun selectCrashMessageIndices(
    titleCount: Int,
    subtitleCount: Int,
    random: Random = Random.Default,
): CrashMessageSelection {
    require(titleCount > 0) { "At least one crash title is required" }
    require(subtitleCount > 0) { "At least one crash subtitle is required" }
    val titleIndex = random.nextInt(titleCount)
    val subtitleIndex = if (subtitleCount > 1 && titleCount > 1 && titleIndex < subtitleCount) {
        random.nextInt(subtitleCount - 1).let { candidate ->
            if (candidate >= titleIndex) candidate + 1 else candidate
        }
    } else {
        random.nextInt(subtitleCount)
    }
    return CrashMessageSelection(titleIndex, subtitleIndex)
}
