package com.flue.launcher.applist.nativeview

import com.flue.launcher.applist.model.AppItemKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAppListContractsTest {
    @Test
    fun stableIdIsDeterministicForTheSameKey() {
        val key = AppItemKey("component:com.example/.MainActivity")

        assertEquals(stableIdFor(key), stableIdFor(key))
    }

    @Test
    fun stableIdDiffersForDifferentKeys() {
        val first = stableIdFor(AppItemKey("component:com.example/.MainActivity"))
        val second = stableIdFor(AppItemKey("component:com.example/.SettingsActivity"))

        assertNotEquals(first, second)
    }

    @Test
    fun stableIdRegistryProbesWhenTwoKeysHaveTheSameSeed() {
        val registry = StableIdRegistry(seedFor = { 42L })
        val firstKey = AppItemKey("first")
        val secondKey = AppItemKey("second")

        val firstId = registry.idFor(firstKey)
        val secondId = registry.idFor(secondKey)

        assertEquals(firstId, registry.idFor(firstKey))
        assertEquals(secondId, registry.idFor(secondKey))
        assertNotEquals(firstId, secondId)
    }

    @Test
    fun stableIdRegistryNeverReturnsRecyclerViewNoId() {
        val registry = StableIdRegistry(seedFor = { -1L })

        assertNotEquals(-1L, registry.idFor(AppItemKey("first")))
        assertNotEquals(-1L, registry.idFor(AppItemKey("second")))
    }

    @Test
    fun semanticLayoutStrategyKeyOnlyReplacesDifferentStrategies() {
        assertFalse(shouldReplaceLayoutStrategy("linear", "linear"))
        assertTrue(shouldReplaceLayoutStrategy("linear", "honeycomb"))
    }

    @Test
    fun normalizedOriginUsesTheChildCenter() {
        val origin = normalizedChildCenter(
            childLeft = 25f,
            childTop = 10f,
            childWidth = 50,
            childHeight = 20,
            viewportWidth = 100,
            viewportHeight = 100,
        )

        assertEquals(0.5f, origin.x, 0f)
        assertEquals(0.2f, origin.y, 0f)
    }

    @Test
    fun normalizedOriginClampsAChildOutsideTheViewport() {
        val origin = normalizedChildCenter(
            childLeft = -80f,
            childTop = 150f,
            childWidth = 20,
            childHeight = 20,
            viewportWidth = 100,
            viewportHeight = 100,
        )

        assertEquals(0f, origin.x, 0f)
        assertEquals(1f, origin.y, 0f)
    }

    @Test
    fun normalizedOriginUsesTheIconCenterRelativeToAnOffsetViewport() {
        val origin = normalizedDescendantCenterInWindow(
            descendantWindowLeft = 156f,
            descendantWindowTop = 294f,
            descendantWidth = 48,
            descendantHeight = 48,
            viewportWindowLeft = 100f,
            viewportWindowTop = 200f,
            viewportWidth = 240,
            viewportHeight = 240,
        )

        assertEquals(80f / 240f, origin.x, 0f)
        assertEquals(118f / 240f, origin.y, 0f)
    }

    @Test
    fun normalizedOriginUsesTheTransformedIconCenterInsteadOfTheRowCenter() {
        val origin = normalizedIconCenterAfterChildTransform(
            iconBounds = NativeAppListIconBounds(
                left = 16f,
                top = 12f,
                right = 56f,
                bottom = 52f,
            ),
            childLeft = 20f,
            childTop = 40f,
            childTranslationX = 10f,
            childTranslationY = -6f,
            childScaleX = 0.5f,
            childScaleY = 1.5f,
            childPivotX = 100f,
            childPivotY = 32f,
            viewportWidth = 400,
            viewportHeight = 300,
        )

        assertEquals(0.245f, origin.x, 0.0001f)
        assertEquals(0.22f, origin.y, 0.0001f)
    }

    @Test
    fun normalizedOriginFallsBackToTheViewportCenterWhenGeometryIsUnavailable() {
        val origin = normalizedDescendantCenterInWindow(
            descendantWindowLeft = Float.NaN,
            descendantWindowTop = Float.NaN,
            descendantWidth = 0,
            descendantHeight = 0,
            viewportWindowLeft = 0f,
            viewportWindowTop = 0f,
            viewportWidth = 0,
            viewportHeight = 0,
        )

        assertEquals(0.5f, origin.x, 0f)
        assertEquals(0.5f, origin.y, 0f)
    }

    @Test
    fun generationGateRejectsAnOlderOrDuplicateSubmission() {
        val gate = SnapshotGenerationGate()

        assertTrue(gate.tryAccept(7L))
        assertFalse(gate.tryAccept(6L))
        assertFalse(gate.tryAccept(7L))
        assertEquals(7L, gate.latestGeneration)
    }

    @Test
    fun generationGateAcceptsANewerSubmissionAfterRejectingAnOldOne() {
        val gate = SnapshotGenerationGate()

        assertTrue(gate.tryAccept(4L))
        assertFalse(gate.tryAccept(3L))
        assertTrue(gate.tryAccept(5L))
        assertEquals(5L, gate.latestGeneration)
    }
}
