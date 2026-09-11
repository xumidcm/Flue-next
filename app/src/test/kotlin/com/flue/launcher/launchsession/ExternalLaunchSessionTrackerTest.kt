package com.flue.launcher.launchsession

import com.flue.launcher.applist.model.NormalizedPoint
import com.flue.launcher.ui.navigation.ScreenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalLaunchSessionTrackerTest {

    @Test
    fun `ambient foreground discards app return state and ignores its late callbacks`() {
        val id = tracker.begin(request())
        tracker.onDispatchResult(id, success = true)
        tracker.onPaused()
        tracker.onStopped()
        tracker.finishWithoutReturnAnimation()
        tracker.onResumed()
        tracker.onDispatchResult(id, success = true)
        tracker.onBackgroundConfirmationTimedOut(id)
        assertSame(ExternalLaunchState.Idle, tracker.state)
        assertFalse(tracker.state.retainsLaunchIcon)
    }

    private var now = 1_000L
    private val tracker = ExternalLaunchSessionTracker { now }

    @Test
    fun `begin creates a unique mask entering session that retains launch visuals`() {
        val firstId = tracker.begin(request("calendar"))
        val first = tracker.state as ExternalLaunchState.MaskEntering

        now = 1_010L
        val secondId = tracker.begin(request("music"))
        val second = tracker.state as ExternalLaunchState.MaskEntering

        assertTrue(secondId > firstId)
        assertEquals(secondId, second.session.id)
        assertEquals("music", second.session.targetKey)
        assertEquals(1_010L, second.session.startedAtElapsedMs)
        assertTrue(second.usesLaunchOrigin)
        assertTrue(second.retainsLaunchIcon)
    }

    @Test
    fun `successful dispatch records dispatch time and duplicate result is idempotent`() {
        val id = tracker.begin(request())
        now = 1_025L

        tracker.onDispatchResult(id, success = true)
        val dispatched = tracker.state as ExternalLaunchState.LaunchDispatched
        tracker.onDispatchResult(id, success = true)

        assertEquals(1_025L, dispatched.session.dispatchedAtElapsedMs)
        assertEquals(dispatched, tracker.state)
    }

    @Test
    fun `failed dispatch becomes terminal and drops launch visuals`() {
        val id = tracker.begin(request())

        tracker.onDispatchResult(id, success = false)

        val failed = tracker.state as ExternalLaunchState.Failed
        assertSame(FailureReason.DispatchFailed, failed.reason)
        assertFalse(failed.usesLaunchOrigin)
        assertFalse(failed.retainsLaunchIcon)
    }

    @Test
    fun `stale callbacks cannot mutate a newer session`() {
        val staleId = tracker.begin(request("calendar"))
        val currentId = tracker.begin(request("music"))
        val before = tracker.state

        tracker.onDispatchResult(staleId, success = false)
        tracker.cancel(staleId, CancelReason.UserBack)
        tracker.acknowledge(staleId)

        assertEquals(before, tracker.state)
        assertEquals(currentId, (tracker.state as ExternalLaunchState.MaskEntering).session.id)
    }

    @Test
    fun `evidence observed before dispatch is ignored`() {
        val id = tracker.begin(request())
        now = 1_100L
        tracker.onDispatchResult(id, success = true)

        tracker.recordEvidence(
            ForegroundPackageEvidence(
                packageName = "com.example.calendar",
                observedAtElapsedMs = 1_099L,
                sequence = 1L,
            ),
        )

        val state = tracker.state as ExternalLaunchState.LaunchDispatched
        assertEquals(null, state.session.lastExternalEvidence)
    }

    @Test
    fun `evidence with the same timestamp as dispatch is conservatively ignored`() {
        val id = tracker.begin(request())
        tracker.onDispatchResult(id, success = true)

        tracker.recordEvidence(
            ForegroundPackageEvidence(
                packageName = "com.example.calendar",
                observedAtElapsedMs = 1_000L,
                sequence = 1L,
            ),
        )

        val state = tracker.state as ExternalLaunchState.LaunchDispatched
        assertEquals(null, state.session.lastExternalEvidence)
    }

    @Test
    fun `newest post-dispatch evidence wins while stale sequence is ignored`() {
        val id = tracker.begin(request())
        tracker.onDispatchResult(id, success = true)
        tracker.recordEvidence(evidence("com.example.calendar", time = 1_010L, sequence = 2L))
        tracker.recordEvidence(evidence("com.example.other", time = 1_020L, sequence = 1L))

        val state = tracker.state as ExternalLaunchState.LaunchDispatched
        assertEquals("com.example.calendar", state.session.lastExternalEvidence?.packageName)
        assertEquals(2L, state.session.lastExternalEvidence?.sequence)
    }

    @Test
    fun `confirmed background returns to the expected target with source origin`() {
        val id = tracker.begin(request())
        tracker.onDispatchResult(id, success = true)
        tracker.onPaused()
        tracker.recordEvidence(evidence("com.example.calendar", time = 1_010L, sequence = 1L))
        tracker.onStopped()

        tracker.onResumed()

        val returning = tracker.state as ExternalLaunchState.Returning
        assertSame(ReturnKind.TargetReturn, returning.kind)
        assertTrue(returning.usesLaunchOrigin)
        assertTrue(returning.retainsLaunchIcon)
    }

    @Test
    fun `confirmed background classifies another package as foreign return`() {
        backgroundWithEvidence(expectedPackage = "com.example.calendar", actualPackage = "com.example.music")

        tracker.onResumed()

        val returning = tracker.state as ExternalLaunchState.Returning
        assertSame(ReturnKind.ForeignReturn, returning.kind)
        assertFalse(returning.usesLaunchOrigin)
        assertFalse(returning.retainsLaunchIcon)
    }

    @Test
    fun `missing expected package is always unverified even with evidence`() {
        backgroundWithEvidence(expectedPackage = null, actualPackage = "com.example.calendar")

        tracker.onResumed()

        assertSame(
            ReturnKind.UnverifiedReturn,
            (tracker.state as ExternalLaunchState.Returning).kind,
        )
    }

    @Test
    fun `missing foreground evidence is an unverified return`() {
        val id = tracker.begin(request())
        tracker.onDispatchResult(id, success = true)
        tracker.onPaused()
        tracker.onStopped()

        tracker.onResumed()

        assertSame(
            ReturnKind.UnverifiedReturn,
            (tracker.state as ExternalLaunchState.Returning).kind,
        )
    }

    @Test
    fun `home intent has priority over matching target evidence`() {
        val id = tracker.begin(request())
        tracker.onDispatchResult(id, success = true)
        tracker.onPaused()
        tracker.recordEvidence(evidence("com.example.calendar", time = 1_010L, sequence = 1L))
        tracker.onHomeIntent()
        tracker.onStopped()

        tracker.onResumed()

        assertSame(
            ReturnKind.HomeReentry,
            (tracker.state as ExternalLaunchState.Returning).kind,
        )
    }

    @Test
    fun `home reentry is trusted even when stop was not delivered`() {
        val id = tracker.begin(request())
        tracker.onDispatchResult(id, success = true)
        tracker.onPaused()
        tracker.onHomeIntent()

        tracker.onResumed()

        val returning = tracker.state as ExternalLaunchState.Returning
        assertSame(ReturnKind.HomeReentry, returning.kind)
        assertFalse(returning.retainsLaunchIcon)
    }

    @Test
    fun `home intent replaces a terminal session with neutral home reentry`() {
        val failedId = tracker.begin(request(key = "failed"))
        tracker.onDispatchResult(failedId, success = false)
        tracker.onHomeIntent()
        assertSame(
            ReturnKind.HomeReentry,
            (tracker.state as ExternalLaunchState.Returning).kind,
        )

        tracker.acknowledge(failedId)
        val cancelledId = tracker.begin(request(key = "cancelled"))
        tracker.cancel(cancelledId, CancelReason.UserBack)
        tracker.onHomeIntent()
        assertSame(
            ReturnKind.HomeReentry,
            (tracker.state as ExternalLaunchState.Returning).kind,
        )

        tracker.acknowledge(cancelledId)
        val invalidatedId = tracker.begin(request(key = "invalidated"))
        tracker.onDispatchResult(invalidatedId, success = true)
        tracker.onBackgroundConfirmationTimedOut(invalidatedId)
        tracker.onHomeIntent()
        assertSame(
            ReturnKind.HomeReentry,
            (tracker.state as ExternalLaunchState.Returning).kind,
        )
    }

    @Test
    fun `pause and resume without stop is invalid because background was not confirmed`() {
        val id = tracker.begin(request())
        tracker.onDispatchResult(id, success = true)
        tracker.onPaused()

        tracker.onResumed()

        val invalidated = tracker.state as ExternalLaunchState.Invalidated
        assertSame(InvalidReason.NoConfirmedBackground, invalidated.reason)
        assertFalse(invalidated.retainsLaunchIcon)
    }

    @Test
    fun `dispatch timeout invalidates only the matching session still awaiting background`() {
        val id = tracker.begin(request())
        tracker.onDispatchResult(id, success = true)

        tracker.onBackgroundConfirmationTimedOut(id + 1L)
        assertTrue(tracker.state is ExternalLaunchState.LaunchDispatched)

        tracker.onBackgroundConfirmationTimedOut(id)

        val invalidated = tracker.state as ExternalLaunchState.Invalidated
        assertSame(InvalidReason.NoConfirmedBackground, invalidated.reason)
        assertEquals(id, invalidated.session?.id)
    }

    @Test
    fun `duplicate pause stop and resume events preserve the first terminal result`() {
        val id = tracker.begin(request())
        tracker.onDispatchResult(id, success = true)
        tracker.onPaused()
        tracker.onPaused()
        tracker.onStopped()
        tracker.onStopped()
        tracker.recordEvidence(evidence("com.example.calendar", time = 1_010L, sequence = 1L))

        tracker.onResumed()
        val returning = tracker.state
        tracker.onResumed()
        tracker.onStopped()

        assertEquals(returning, tracker.state)
        assertSame(
            ReturnKind.TargetReturn,
            (tracker.state as ExternalLaunchState.Returning).kind,
        )
    }

    @Test
    fun `home before dispatch cancels the session`() {
        tracker.begin(request())

        tracker.onHomeIntent()

        assertSame(
            CancelReason.HomeBeforeDispatch,
            (tracker.state as ExternalLaunchState.Cancelled).reason,
        )
    }

    @Test
    fun `activity leaving before dispatch cancels the session`() {
        tracker.begin(request())

        tracker.onPaused()

        assertSame(
            CancelReason.ActivityLeftBeforeDispatch,
            (tracker.state as ExternalLaunchState.Cancelled).reason,
        )
    }

    @Test
    fun `only matching terminal acknowledgement returns to idle`() {
        val id = tracker.begin(request())
        tracker.onDispatchResult(id, success = false)

        tracker.acknowledge(id + 1L)
        assertTrue(tracker.state is ExternalLaunchState.Failed)

        tracker.acknowledge(id)
        assertSame(ExternalLaunchState.Idle, tracker.state)
    }

    @Test
    fun `process restore invalidates active session and idle tracker`() {
        val id = tracker.begin(request())

        tracker.invalidateProcessRestore()

        val activeInvalidation = tracker.state as ExternalLaunchState.Invalidated
        assertEquals(id, activeInvalidation.session?.id)
        assertSame(InvalidReason.ProcessRestore, activeInvalidation.reason)

        activeInvalidation.let { tracker.acknowledge(id) }
        tracker.invalidateProcessRestore()

        val idleInvalidation = tracker.state as ExternalLaunchState.Invalidated
        assertEquals(null, idleInvalidation.session)
        assertSame(InvalidReason.ProcessRestore, idleInvalidation.reason)
    }

    @Test
    fun `explicit cancellation validates token and is idempotent`() {
        val id = tracker.begin(request())

        tracker.cancel(id, CancelReason.UserBack)
        val cancelled = tracker.state
        tracker.cancel(id, CancelReason.HomeBeforeDispatch)

        assertEquals(cancelled, tracker.state)
        assertSame(
            CancelReason.UserBack,
            (tracker.state as ExternalLaunchState.Cancelled).reason,
        )
    }

    private fun backgroundWithEvidence(expectedPackage: String?, actualPackage: String) {
        val id = tracker.begin(request(expectedPackage = expectedPackage))
        tracker.onDispatchResult(id, success = true)
        tracker.onPaused()
        tracker.recordEvidence(evidence(actualPackage, time = 1_010L, sequence = 1L))
        tracker.onStopped()
        assertTrue(tracker.state is ExternalLaunchState.Backgrounded)
    }

    private fun request(
        key: String = "calendar",
        expectedPackage: String? = "com.example.calendar",
    ) = ExternalLaunchRequest(
        targetKey = key,
        expectedPackage = expectedPackage,
        sourceState = ScreenState.Apps,
        origin = NormalizedPoint(0.25f, 0.75f),
    )

    private fun evidence(
        packageName: String,
        time: Long,
        sequence: Long,
    ) = ForegroundPackageEvidence(
        packageName = packageName,
        observedAtElapsedMs = time,
        sequence = sequence,
    )
}
