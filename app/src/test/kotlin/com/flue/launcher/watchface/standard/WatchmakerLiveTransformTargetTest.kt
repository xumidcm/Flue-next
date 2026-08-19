package com.flue.launcher.watchface.standard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchmakerLiveTransformTargetTest {
    @Test
    fun layerIdMatchesEvenWhenFlattenedIndexDiffers() {
        assertTrue(
            watchmakerLiveTransformTargetsLayer(
                targetLayerId = "text-1",
                targetLayerIndex = 0,
                layerId = "text-1",
                layerIndex = 4,
            ),
        )
    }

    @Test
    fun differentIdDoesNotFallBackToIndex() {
        assertFalse(
            watchmakerLiveTransformTargetsLayer(
                targetLayerId = "text-1",
                targetLayerIndex = 0,
                layerId = "shape-1",
                layerIndex = 0,
            ),
        )
    }
}
