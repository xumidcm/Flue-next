package com.flue.launcher.ui.home

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchFaceRenderGateTest {
    @Test
    fun `ready face stays visible through recomposition and same face refresh`() = withGate {
        assertFalse(gate.ready)
        gate.onRenderReady(0)
        settle()
        assertTrue(gate.ready)
        // Rebinding unchanged content on return must preserve readiness.
        settle()
        assertTrue(gate.ready)
        repeat(3) {
            token.value += 1
            settle()
            assertTrue("Retained content must not be covered during refresh", gate.ready)
            gate.onRenderReady(token.value)
            settle()
        }
    }

    @Test
    fun `switching faces waits and ignores callbacks from the previous face`() = withGate {
        val previousReady = gate.onRenderReady
        gate.onRenderReady(0)
        settle()
        face.value = "watch:second"
        settle()
        assertFalse(gate.ready)
        previousReady(0)
        settle()
        assertFalse(gate.ready)
        gate.onRenderReady(0)
        settle()
        assertTrue(gate.ready)
        previousReady(0)
        settle()
        assertTrue("Late callbacks must not hide an already rendered face", gate.ready)
        face.value = "watch:first"
        settle()
        assertFalse("Returning to a disposed face needs a new ready signal", gate.ready)
    }

    @Test
    fun `unrendered face rejects a stale refresh completion`() = withGate {
        val previousReady = gate.onRenderReady
        token.value = 1
        settle()
        previousReady(0)
        settle()
        assertFalse(gate.ready)
        gate.onRenderReady(0)
        settle()
        assertFalse("Callback token must match the requested content", gate.ready)
        gate.onRenderReady(1)
        settle()
        assertTrue(gate.ready)
    }

    @Test
    fun `cold load still has a timeout and a new face waits again`() = withGate {
        assertFalse(gate.ready)
        delay(6_100L)
        settle()
        assertTrue(gate.ready)
        face.value = "watch:second"
        settle()
        assertFalse(gate.ready)
    }

    @Test
    fun `built in face never waits for a native renderer`() = withGate {
        native.value = false
        settle()
        assertTrue(gate.ready)
    }

    private class Harness(val frameClock: BroadcastFrameClock) {
        val face = mutableStateOf("watch:first")
        val token = mutableStateOf(0)
        val native = mutableStateOf(true)
        lateinit var gate: WatchFaceRenderGate

        suspend fun settle() {
            repeat(3) {
                Snapshot.sendApplyNotifications()
                yield()
                frameClock.sendFrame(System.nanoTime())
                yield()
            }
        }
    }

    private fun withGate(block: suspend Harness.() -> Unit) = runBlocking {
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext)
        val runner = launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        val harness = Harness(frameClock)
        try {
            composition.setContent {
                harness.gate = rememberWatchFaceRenderGate(
                    harness.face.value,
                    harness.token.value,
                    harness.native.value,
                )
            }
            harness.settle()
            harness.block()
        } finally {
            composition.dispose()
            recomposer.cancel()
            runner.join()
        }
    }
}
