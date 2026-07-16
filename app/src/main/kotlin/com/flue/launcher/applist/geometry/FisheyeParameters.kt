package com.flue.launcher.applist.geometry

enum class BlurBucket(
    val radiusDp: Int,
) {
    NONE(0),
    DP_1(1),
    DP_2(2),
    DP_3(3),
    DP_4(4),
    DP_6(6),
    DP_8(8),
}

sealed interface FisheyeParameters {
    val minScale: Float
    val minAlpha: Float
    val maximumSpacingCompression: Float
    val maximumBlur: BlurBucket

    data class Classic(
        override val minScale: Float = 0.55f,
        override val minAlpha: Float = 0.65f,
        val effectRadiusFraction: Float = 1f,
        override val maximumSpacingCompression: Float = 0f,
        override val maximumBlur: BlurBucket = BlurBucket.NONE,
    ) : FisheyeParameters {
        init {
            validateFisheyeParameters(
                minScale = minScale,
                minAlpha = minAlpha,
                maximumSpacingCompression = maximumSpacingCompression,
            )
            require(effectRadiusFraction.isFinite() && effectRadiusFraction in 0.01f..1f) {
                "Classic effect radius fraction must be in 0.01..1"
            }
        }
    }

    data class Next(
        override val minScale: Float = 0.45f,
        override val minAlpha: Float = 0.55f,
        val stableRadiusFraction: Float = 0.60f,
        override val maximumSpacingCompression: Float = 0.30f,
        override val maximumBlur: BlurBucket = BlurBucket.DP_8,
    ) : FisheyeParameters {
        init {
            validateFisheyeParameters(
                minScale = minScale,
                minAlpha = minAlpha,
                maximumSpacingCompression = maximumSpacingCompression,
            )
            require(
                stableRadiusFraction.isFinite() &&
                    stableRadiusFraction >= 0f &&
                    stableRadiusFraction < 1f,
            ) {
                "Next stable radius fraction must be in 0..<1"
            }
        }
    }
}

private fun validateFisheyeParameters(
    minScale: Float,
    minAlpha: Float,
    maximumSpacingCompression: Float,
) {
    require(minScale.isFinite() && minScale in 0f..1f) {
        "Minimum scale must be in 0..1"
    }
    require(minAlpha.isFinite() && minAlpha in 0f..1f) {
        "Minimum alpha must be in 0..1"
    }
    require(
        maximumSpacingCompression.isFinite() &&
            maximumSpacingCompression in 0f..MAXIMUM_SPACING_COMPRESSION,
    ) {
        "Maximum spacing compression must be in 0..$MAXIMUM_SPACING_COMPRESSION"
    }
}

private const val MAXIMUM_SPACING_COMPRESSION = 0.30f
