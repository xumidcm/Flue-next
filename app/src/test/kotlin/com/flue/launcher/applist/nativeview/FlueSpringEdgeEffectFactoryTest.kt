package com.flue.launcher.applist.nativeview

import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.random.Random

/**
 * State-machine invariants for the edge factory.
 *
 * The one forbidden state is the "ownerless parked stretch":
 * `offset != 0` with no animation running and no finger owning the stream.
 * Nothing can ever unwind it — `distance()` reports 0 for ownerless edges, so
 * RecyclerView will not route inward deltas into it either — and its visible
 * child translation plus swallowed flings were the "list randomly refuses to
 * scroll" family of bugs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FlueSpringEdgeEffectFactoryTest {

    private fun factory(): FlueSpringEdgeEffectFactory {
        val owner = RecyclerView(RuntimeEnvironment.getApplication())
        owner.layout(0, 0, 400, 400)
        return FlueSpringEdgeEffectFactory(owner = owner, onOffsetChanged = {})
            .apply { updateViewport(400) }
    }

    private fun assertNoOwnerlessParkedStretch(effects: FlueSpringEdgeEffectFactory, step: String) {
        val parked = abs(effects.offsetPx) > 0.001f &&
            !effects.isAnimating &&
            !effects.ownsPointerGesture
        assertFalse("ownerless parked stretch after: $step", parked)
        if (effects.distance(FlueEdgePhysics.Edge.TOP) != 0f ||
            effects.distance(FlueEdgePhysics.Edge.BOTTOM) != 0f
        ) {
            assertTrue("non-zero distance requires pointer ownership ($step)", effects.ownsPointerGesture)
        }
    }

    @Test
    fun `fuzz - random input sequences never park an ownerless stretch`() {
        val effects = factory()
        val random = Random(42)
        val edges = arrayOf(FlueEdgePhysics.Edge.TOP, FlueEdgePhysics.Edge.BOTTOM)
        var lastOp = "init"
        repeat(4_000) { step ->
            val edge = edges[random.nextInt(edges.size)]
            lastOp = when (random.nextInt(8)) {
                0 -> "beginGesture".also { effects.beginGesture() }
                1 -> "pull(+)".also { effects.pull(edge, random.nextFloat() * 0.4f) }
                2 -> "pull(-)".also { effects.pull(edge, -random.nextFloat() * 0.4f) }
                3 -> "pull(0)".also { effects.pull(edge, 0f) }
                4 -> "absorb".also { effects.absorb(edge, random.nextInt(20_000) - 10_000) }
                5 -> "release".also { effects.release(edge) }
                6 -> "endGesture(up)".also { effects.endGesture(terminalUp = true) }
                else -> "endGesture(forced)".also { effects.endGesture(terminalUp = false) }
            }
            assertNoOwnerlessParkedStretch(effects, "step $step: $lastOp")
        }
    }

    @Test
    fun `ownerless zero delta probe during rebound self heals into a rebound`() {
        val effects = factory()
        effects.beginGesture()
        effects.pull(FlueEdgePhysics.Edge.TOP, 0.5f)
        effects.endGesture(terminalUp = false)
        assertTrue(effects.isAnimating)

        // RecyclerView probes glows with a zero-delta pull (stopGlowAnimations)
        // before our dispatch funnel opens a window — e.g. a DOWN landing while
        // the scene is inactive. The probe converts REBOUND into a parked PULL;
        // the factory must immediately settle it back into a rebound.
        effects.pull(FlueEdgePhysics.Edge.TOP, 0f)

        assertTrue("probe must not strand the rebound", effects.isAnimating)
        assertEquals(0f, effects.distance(FlueEdgePhysics.Edge.TOP), 0f)
    }

    @Test
    fun `release during an owned gesture rebounds immediately and hides distance`() {
        val effects = factory()
        effects.beginGesture()
        effects.pull(FlueEdgePhysics.Edge.TOP, 0.5f)
        assertTrue(effects.distance(FlueEdgePhysics.Edge.TOP) > 0f)

        effects.release(FlueEdgePhysics.Edge.TOP)

        assertTrue(effects.isAnimating)
        assertEquals(0f, effects.distance(FlueEdgePhysics.Edge.TOP), 0f)
    }

    @Test
    fun `pull to face intent stays latched across a rebound decay`() {
        val effects = factory()
        effects.beginGesture()
        // Pull far past the 0.78 trigger fraction.
        repeat(40) { effects.pull(FlueEdgePhysics.Edge.TOP, 0.5f) }
        // A legitimate mid-gesture release starts the rebound; the offset may
        // fall below the trigger before the UP arrives. The intent was
        // expressed at the finger's deepest point and must survive.
        effects.release(FlueEdgePhysics.Edge.TOP)

        assertTrue(
            "UP after a rebound decay must still trigger pull-to-face",
            effects.endGesture(terminalUp = true),
        )
    }

    @Test
    fun `pull to face intent is disarmed by the finger returning inward`() {
        val effects = factory()
        effects.beginGesture()
        repeat(40) { effects.pull(FlueEdgePhysics.Edge.TOP, 0.5f) }
        // The finger changes its mind and pushes the whole stretch back in.
        repeat(60) { effects.pull(FlueEdgePhysics.Edge.TOP, -0.5f) }

        assertFalse(
            "an intent revoked by the finger must not navigate on UP",
            effects.endGesture(terminalUp = true),
        )
    }

    @Test
    fun `forced terminal never triggers pull to face`() {
        val effects = factory()
        effects.beginGesture()
        repeat(40) { effects.pull(FlueEdgePhysics.Edge.TOP, 0.5f) }

        assertFalse(effects.endGesture(terminalUp = false))
    }

    @Test
    fun `distance is zero for any ownerless state`() {
        val effects = factory()
        // Pull without a window at all (defensive path).
        effects.pull(FlueEdgePhysics.Edge.TOP, 0.5f)
        assertEquals(0f, effects.distance(FlueEdgePhysics.Edge.TOP), 0f)
        assertNoOwnerlessParkedStretch(effects, "windowless pull")
    }
}
