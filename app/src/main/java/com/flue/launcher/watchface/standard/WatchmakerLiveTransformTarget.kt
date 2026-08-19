package com.flue.launcher.watchface.standard

internal fun watchmakerLiveTransformTargetsLayer(
    targetLayerId: String?,
    targetLayerIndex: Int,
    layerId: String?,
    layerIndex: Int,
): Boolean {
    val stableTargetId = targetLayerId?.takeIf(String::isNotBlank)
    return if (stableTargetId != null) {
        stableTargetId == layerId
    } else {
        targetLayerIndex >= 0 && targetLayerIndex == layerIndex
    }
}
