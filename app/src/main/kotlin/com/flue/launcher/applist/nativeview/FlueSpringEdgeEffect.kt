package com.flue.launcher.applist.nativeview

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.widget.EdgeEffect
import androidx.annotation.RequiresApi
import androidx.recyclerview.widget.RecyclerView
import com.flue.launcher.motion.FlueMotionProfile
import com.flue.launcher.motion.FlueMotionProfiles
import com.flue.launcher.motion.MotionEasing
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Direction-independent state machine for the native list's edge physics.
 *
 * The class deliberately has no Android dependencies.  A pointer delta is in
 * pixels in the edge's outward direction (positive means pulling away from
 * the content).  The returned value is the part consumed by the edge; callers
 * can pass the remainder to the list in the same input frame.
 */
internal class FlueEdgePhysics(
    private val density: Float = 1f,
    pullResistance: Float = 0.18f,
    absorbVelocityFactor: Float = 0.008f,
    maxKickDp: Float = 40f,
    extensionDurationMs: Long = 80L,
    reboundDurationMs: Long = 350L,
    private val triggerFraction: Float = 0.78f,
) {
    enum class Edge(val sign: Float) {
        TOP(1f),
        BOTTOM(-1f),
    }

    enum class Phase {
        IDLE,
        PULL,
        EXTENDING,
        REBOUND,
    }

    data class Snapshot(
        val offsetPx: Float,
        val phase: Phase,
        val activeEdge: Edge?,
        val maxOffsetPx: Float,
        val triggerOffsetPx: Float,
    )

    private var viewportHeightPx = 1f
    private var maxOffsetPx = 1f
    private var triggerOffsetPx = 1f
    private var enabled = true
    private var edgeStrength = 5
    private var pullResistance = pullResistance
    private var absorbVelocityFactor = absorbVelocityFactor
    private var maxKickDp = maxKickDp
    private var extensionDurationMs = extensionDurationMs.coerceAtLeast(1L)
    private var reboundDurationMs = reboundDurationMs.coerceAtLeast(1L)
    private var reboundEasing: MotionEasing = FlueMotionProfiles.Gentle.edgeBounce.easing
    private var activeEdge: Edge? = null
    private var phase = Phase.IDLE
    private var offsetPx = 0f
    private var animationElapsedMs = 0L
    private var animationStartPx = 0f
    private var animationPeakPx = 0f

    val offset: Float
        get() = offsetPx
    val currentPhase: Phase
        get() = phase
    val isAnimating: Boolean
        get() = phase == Phase.EXTENDING || phase == Phase.REBOUND
    val needsAnimationFrame: Boolean
        get() = isAnimating
    val hasActiveEdge: Boolean
        get() = activeEdge != null
    val activeEdgeOrNull: Edge?
        get() = activeEdge

    init {
        updateViewport(1)
    }

    fun updateViewport(heightPx: Int) {
        viewportHeightPx = heightPx.coerceAtLeast(1).toFloat()
        val strengthScale = (edgeStrength / 5f).coerceIn(0f, 2f)
        maxOffsetPx = if (enabled && strengthScale > 0f) {
            min(
                48f * density.coerceAtLeast(0.1f) * strengthScale,
                viewportHeightPx * 0.14f * strengthScale,
            ).coerceAtLeast(1f)
        } else {
            0f
        }
        triggerOffsetPx = (maxOffsetPx * triggerFraction.coerceIn(0.1f, 1f))
            .coerceAtMost(maxOffsetPx)
        offsetPx = offsetPx.coerceIn(-maxOffsetPx, maxOffsetPx)
        animationStartPx = animationStartPx.coerceIn(-maxOffsetPx, maxOffsetPx)
        animationPeakPx = animationPeakPx.coerceIn(-maxOffsetPx, maxOffsetPx)
        if (!enabled || maxOffsetPx <= 0f) reset()
    }

    fun updateMotionProfile(profile: FlueMotionProfile) {
        val safe = profile.nativeSafeProfile()
        edgeStrength = safe.edgeStrength.coerceIn(0, 10)
        enabled = safe.bounceEnabled && edgeStrength > 0
        val strengthScale = (edgeStrength / 5f).coerceIn(0f, 2f)
        pullResistance = 0.18f * strengthScale
        absorbVelocityFactor = 0.008f * strengthScale
        maxKickDp = 40f * strengthScale
        extensionDurationMs = safe.edgeBounce.handoffMillis.toLong().coerceAtLeast(1L)
        reboundDurationMs = safe.edgeBounce.durationMillis.toLong().coerceAtLeast(1L)
        reboundEasing = safe.edgeBounce.easing
        updateViewport(viewportHeightPx.toInt())
    }

    fun snapshot(): Snapshot = Snapshot(
        offsetPx = offsetPx,
        phase = phase,
        activeEdge = activeEdge,
        maxOffsetPx = maxOffsetPx,
        triggerOffsetPx = triggerOffsetPx,
    )

    /**
     * Applies a drag delta and returns the consumed edge-relative pixels.
     * Recovery is one-to-one; outward movement is damped to CloX's 0.18
     * baseline.  A move crossing the boundary therefore never waits for a
     * second gesture sample before producing a non-zero offset.
     */
    fun pull(edge: Edge, outwardDeltaPx: Float): Float {
        if (!enabled || !outwardDeltaPx.isFinite()) return 0f
        takeOver(edge)
        if (activeEdge != edge && abs(offsetPx) > 0.001f) {
            // A direction change cannot leave two glows fighting over one
            // translated child layer.  Preserve the currently rendered value
            // until the caller explicitly releases it.
            return 0f
        }
        activeEdge = edge
        phase = Phase.PULL
        if (outwardDeltaPx == 0f) return 0f

        val relative = offsetPx * edge.sign
        if (outwardDeltaPx < 0f) {
            // Consume the currently visible stretch before the RecyclerView
            // gets any inward scroll distance.  This is the key behavior that
            // avoids a sticky second pull after a boundary hit.
            val recoverable = max(0f, relative)
            val consumed = min(recoverable, -outwardDeltaPx)
            offsetPx += edge.sign * (-consumed)
            if (abs(offsetPx) <= 0.001f) reset()
            if (consumed > 0f) return -consumed
            return 0f
        }

        val available = (maxOffsetPx - max(0f, relative)).coerceAtLeast(0f)
        val resistance = smoothResistance(relative, maxOffsetPx)
        // Consume the complete outward sample even at the cap.  RecyclerView
        // must not interpret a capped edge as a fresh fling in the same frame.
        offsetPx += edge.sign * min(available, outwardDeltaPx * pullResistance * resistance)
        offsetPx = offsetPx.coerceIn(-maxOffsetPx, maxOffsetPx)
        return outwardDeltaPx
    }

    /**
     * Converts a vertical pointer delta into the active edge's coordinates and
     * consumes only the inward part needed to return the visible stretch.
     *
     * Android 12 exposes this handoff through EdgeEffect.onPullDistance.
     * Earlier releases do not, so NativeAppListView uses this pure contract to
     * forward only the remainder to RecyclerView.
     */
    fun consumeInwardPointerDelta(pointerDeltaPx: Float): Float {
        val edge = activeEdge ?: return 0f
        if (!pointerDeltaPx.isFinite()) return 0f
        val outwardDeltaPx = pointerDeltaPx * edge.sign
        if (outwardDeltaPx >= 0f) return 0f
        return pull(edge, outwardDeltaPx) * edge.sign
    }

    /** Starts the CloX-style immediate velocity kick at a hard boundary. */
    fun absorb(edge: Edge, velocityPxPerSecond: Float) {
        if (!enabled || !velocityPxPerSecond.isFinite()) return
        takeOver(edge)
        activeEdge = edge
        val current = max(0f, offsetPx * edge.sign)
        val kick = min(
            min(abs(velocityPxPerSecond) * absorbVelocityFactor, maxKickDp * density),
            (maxOffsetPx - current).coerceAtLeast(0f),
        )
        val immediateKick = if (kick > 0.001f) {
            min(kick, max(0.5f * density, kick * pullResistance))
        } else {
            0f
        }
        animationStartPx = edge.sign * (current + immediateKick)
        animationPeakPx = edge.sign * (current + kick)
        animationElapsedMs = 0L
        phase = if (kick > 0.001f) Phase.EXTENDING else Phase.REBOUND
        if (phase == Phase.REBOUND) animationStartPx = animationPeakPx
        // Publish a visible displacement on the absorb frame itself. Waiting
        // for the first animator callback creates a one-frame hard stop on old
        // watches and breaks the perceived fling momentum at the boundary.
        offsetPx = animationStartPx
    }

    /** Begins a physically interruptible rebound and reports a face trigger. */
    fun release(edge: Edge): Boolean {
        if (!enabled) {
            reset()
            return false
        }
        if (activeEdge != edge || abs(offsetPx) <= 0.001f) {
            if (activeEdge == edge && abs(offsetPx) <= 0.001f) reset()
            return false
        }
        val shouldTriggerFace = edge == Edge.TOP &&
            offsetPx * edge.sign >= triggerOffsetPx
        activeEdge = edge
        animationStartPx = offsetPx
        animationPeakPx = offsetPx
        animationElapsedMs = 0L
        phase = Phase.REBOUND
        return shouldTriggerFace
    }

    /** Stops an animation without snapping the currently visible transform. */
    fun takeOver(edge: Edge? = activeEdge) {
        if (edge != null && activeEdge != null && activeEdge != edge) return
        if (abs(offsetPx) <= 0.001f) {
            reset()
            return
        }
        phase = Phase.PULL
        animationElapsedMs = 0L
        animationStartPx = offsetPx
        animationPeakPx = offsetPx
    }

    /** Advances one frame; returns true while a frame should be scheduled. */
    fun advanceBy(deltaMs: Long): Boolean {
        if (!isAnimating) return false
        val delta = deltaMs.coerceIn(0L, 100L)
        animationElapsedMs += delta
        when (phase) {
            Phase.EXTENDING -> {
                val t = (animationElapsedMs.toFloat() / extensionDurationMs)
                    .coerceIn(0f, 1f)
                // Decelerate into the maximum kick, matching the original
                // CloX extension rather than making the boundary stop dead.
                val eased = 1f - (1f - t) * (1f - t)
                offsetPx = animationStartPx + (animationPeakPx - animationStartPx) * eased
                if (t >= 1f) {
                    phase = Phase.REBOUND
                    animationElapsedMs = 0L
                    animationStartPx = animationPeakPx
                }
            }

            Phase.REBOUND -> {
                val t = (animationElapsedMs.toFloat() / reboundDurationMs)
                    .coerceIn(0f, 1f)
                val eased = nativeMotionInterpolation(t, reboundEasing)
                offsetPx = animationStartPx * (1f - eased)
                if (t >= 1f) reset()
            }

            else -> Unit
        }
        return isAnimating
    }

    fun finish(edge: Edge) {
        if (activeEdge == edge) reset()
    }

    fun finishAnimation() {
        if (isAnimating) reset()
    }

    fun isActive(edge: Edge): Boolean = activeEdge == edge && phase != Phase.IDLE

    fun isBeyondTrigger(edge: Edge): Boolean =
        activeEdge == edge && offsetPx * edge.sign >= triggerOffsetPx

    fun distance(edge: Edge): Float =
        if (activeEdge == edge) {
            (abs(offsetPx) / viewportHeightPx).coerceIn(0f, 1f)
        } else {
            0f
        }

    private fun smoothResistance(relative: Float, maximum: Float): Float {
        val fraction = (relative / maximum.coerceAtLeast(1f)).coerceIn(0f, 1f)
        // Keep a visible response near the cap without the hard plateau that
        // made the old implementation appear to stop before the rubber band.
        return 1f - fraction * 0.72f
    }

    private fun reset() {
        phase = Phase.IDLE
        activeEdge = null
        offsetPx = 0f
        animationElapsedMs = 0L
        animationStartPx = 0f
        animationPeakPx = 0f
    }
}

/**
 * EdgeEffectFactory that translates the existing RecyclerView children via a
 * single callback.  It intentionally does not draw a second glow: the list's
 * backdrop and icon layer already provide the visual treatment, while this
 * object only owns consumption and physics.
 */
internal class FlueSpringEdgeEffectFactory(
    private val owner: RecyclerView,
    density: Float = owner.resources.displayMetrics.density,
    motionProfile: FlueMotionProfile = FlueMotionProfiles.Gentle,
    private val onOffsetChanged: (Float) -> Unit,
) : RecyclerView.EdgeEffectFactory() {
    private val physics = FlueEdgePhysics(density = density).apply {
        updateMotionProfile(motionProfile)
    }
    private var animator: android.animation.ValueAnimator? = null
    private var lastFrameTimeMs = 0L
    private var released = false
    private var topReleaseCandidate = false
    private var lastDiagnosticState = ""

    /**
     * 状态跳变诊断（logcat 过滤 `FlueEdge`）。offset 按 8px 桶量化，
     * 一次手势只落几行；用于定位「偶发滑不动」时状态机停在哪个格子。
     */
    private fun logDiagnostic(reason: String) {
        val state = "owns=$pointerOwnsGesture phase=${physics.currentPhase} " +
            "offBucket=${(physics.offset / 8f).toInt()} anim=${physics.isAnimating} " +
            "cand=$topReleaseCandidate"
        if (state != lastDiagnosticState) {
            lastDiagnosticState = state
            android.util.Log.d("FlueEdge", "$reason -> $state")
        }
    }

    /**
     * True strictly between the real ACTION_DOWN and its ACTION_UP/CANCEL as
     * seen by [NativeAppListView.dispatchTouchEvent] — the only funnel that is
     * guaranteed to observe every sample of every stream. The old flag was
     * opened/closed from onInterceptTouchEvent/onTouchEvent, both of which
     * have blind windows (`!active` early-return, child-owned streams), so a
     * missed terminal sample left it latched forever and every later
     * `release()` was rejected: the classic "list refuses to scroll until I
     * rub it a few times" lock-up.
     */
    private var pointerOwnsGesture = false

    internal val ownsPointerGesture: Boolean
        get() = pointerOwnsGesture

    val offsetPx: Float
        get() = physics.offset

    val isAnimating: Boolean
        get() = physics.isAnimating

    fun updateViewport(heightPx: Int) {
        physics.updateViewport(heightPx)
        publish()
    }

    fun updateMotionProfile(profile: FlueMotionProfile) {
        physics.updateMotionProfile(profile)
        if (!physics.isAnimating) {
            animator?.cancel()
            animator = null
        }
        topReleaseCandidate = false
        publish()
    }

    /**
     * Starts a new touch gesture.
     *
     * The in-flight rebound is deliberately left running. `pull()` and
     * `absorb()` already call `physics.takeOver()` themselves at the moment the
     * pointer really engages the edge, so freezing the offset here only created
     * dead travel: a touch landing during a 350ms rebound used to pin the offset
     * at up to `maxOffsetPx` (≈64–90px on a 454px viewport) that could only
     * unwind through `pull()`'s inward-consumption path — the finger had to
     * travel the whole stretch plus touch slop before the list scrolled at all.
     * That is the "have to wait a moment before it scrolls again" symptom.
     */
    fun beginGesture() {
        pointerOwnsGesture = true
        // A stale candidate from the previous gesture must not leak forward,
        // but the rebound animation itself keeps its own clock.
        topReleaseCandidate = false
        logDiagnostic("beginGesture")
    }

    /**
     * Closes the pointer window at the real terminal sample (or a forced one,
     * e.g. the scene turning inactive mid-stream) and reports whether the
     * latched pull-to-face intent should fire.
     *
     * The intent is *latched*: once the finger crosses the trigger it stays
     * armed even if an unconditional [release] rebound decays the offset below
     * the trigger before the UP arrives ("上滑回表盘时灵时不灵"). It is only
     * disarmed by the finger itself pulling back inward — [pull] re-evaluates
     * the candidate on every owned sample.
     */
    fun endGesture(terminalUp: Boolean): Boolean {
        val shouldTrigger = terminalUp && topReleaseCandidate && !released
        topReleaseCandidate = false
        pointerOwnsGesture = false
        settleOwnerlessEdge()
        logDiagnostic("endGesture(up=$terminalUp)")
        return shouldTrigger
    }

    fun finish() {
        animator?.cancel()
        animator = null
        physics.finish(FlueEdgePhysics.Edge.TOP)
        physics.finish(FlueEdgePhysics.Edge.BOTTOM)
        topReleaseCandidate = false
        // The pointer window is owned exclusively by the dispatch funnel.
        // beginDrag() calls finish() mid-gesture; clearing the window here made
        // the following terminal sample a no-op and leaked the old window into
        // the next gesture.
        publish()
    }

    override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect {
        val edge = when (direction) {
            DIRECTION_TOP -> FlueEdgePhysics.Edge.TOP
            DIRECTION_BOTTOM -> FlueEdgePhysics.Edge.BOTTOM
            else -> null
        }
        return if (edge == null) {
            EdgeEffect(view.context)
        } else {
            FlueSpringEdgeEffect(view.context, edge, this)
        }
    }

    internal fun pull(edge: FlueEdgePhysics.Edge, distance: Float): Float {
        if (released) return 0f
        stopAnimatorForInput()
        val consumed = physics.pull(edge, distance * owner.height.coerceAtLeast(1))
        topReleaseCandidate = pointerOwnsGesture &&
            edge == FlueEdgePhysics.Edge.TOP &&
            physics.isBeyondTrigger(FlueEdgePhysics.Edge.TOP)
        // RecyclerView probes glows with zero-delta pulls (stopGlowAnimations)
        // even outside any finger window; `physics.pull` parks the phase at
        // PULL in that case. An ownerless PULL with a non-zero offset would be
        // invisible to RecyclerView (`distance()` reports 0) yet keep child
        // rows translated forever — settle it into a rebound immediately.
        if (!pointerOwnsGesture) settleOwnerlessEdge()
        logDiagnostic("pull")
        publish()
        return consumed / owner.height.coerceAtLeast(1)
    }

    internal fun absorb(edge: FlueEdgePhysics.Edge, velocity: Int) {
        if (released) return
        physics.absorb(edge, velocity.toFloat())
        topReleaseCandidate = false
        logDiagnostic("absorb")
        publish()
        startAnimator()
    }

    internal fun release(edge: FlueEdgePhysics.Edge) {
        if (released) return
        // Unconditional: RecyclerView's onRelease call sites are all legitimate
        // (terminal samples, and considerReleasingGlowsOnScroll once the list
        // really scrolls away from the edge). The old finger guard rejected
        // those requests, so the offset stayed locked for the whole gesture,
        // `distance()` stayed non-zero, and every fling in that window was
        // silently absorbed by the stretch — the "pull too far at the top and
        // the list dies" symptom. Snap-under-finger cannot happen: while the
        // finger genuinely holds the stretch, RecyclerView unwinds through
        // onPullDistance and only releases once the offset is already spent
        // (physics.release ignores a zero-offset edge).
        if (physics.isAnimating) {
            ensureAnimatorMatchesPhase()
            return
        }
        physics.release(edge)
        // The latched pull-to-face candidate deliberately survives the rebound:
        // intent was expressed at the finger's deepest point, not at UP time.
        logDiagnostic("release")
        publish()
        if (physics.isAnimating) startAnimator()
    }

    internal fun finish(edge: FlueEdgePhysics.Edge) {
        physics.finish(edge)
        if (edge == FlueEdgePhysics.Edge.TOP) topReleaseCandidate = false
        if (!physics.isAnimating) animator?.cancel()
        publish()
    }

    internal fun consumeLegacyInwardPointerDelta(pointerDeltaPx: Float): Float {
        if (released) return 0f
        // Only take the animation away once this pointer actually has an edge to
        // consume. Killing it up front meant an outward MOVE, or a MOVE with no
        // active edge at all, silently stranded the state machine.
        if (!physics.hasActiveEdge) return 0f
        val consumed = physics.consumeInwardPointerDelta(pointerDeltaPx)
        if (consumed == 0f) {
            ensureAnimatorMatchesPhase()
            return 0f
        }
        stopAnimatorForInput()
        topReleaseCandidate = pointerOwnsGesture &&
            physics.isBeyondTrigger(FlueEdgePhysics.Edge.TOP)
        if (!pointerOwnsGesture) settleOwnerlessEdge()
        publish()
        return consumed
    }

    internal val hasActiveEdge: Boolean
        get() = physics.hasActiveEdge

    /**
     * Self-heal for the one forbidden state: `offset != 0` with no animation
     * and no finger owning the stream. Nothing else would ever unwind it —
     * `distance()` reports 0 for ownerless edges, so RecyclerView will not
     * route inward deltas here either. Start the rebound ourselves.
     */
    private fun settleOwnerlessEdge() {
        if (released || pointerOwnsGesture || physics.isAnimating) return
        if (!physics.hasActiveEdge) return
        physics.activeEdgeOrNull?.let(physics::release)
        logDiagnostic("settleOwnerless")
        publish()
        if (physics.isAnimating) startAnimator()
    }

    internal fun isFinished(edge: FlueEdgePhysics.Edge): Boolean =
        !physics.isActive(edge)

    /**
     * What API 31+ RecyclerView sees through `EdgeEffect.getDistance()`.
     *
     * The stretch-aware scroll path treats any non-zero distance as "there is
     * stretch to unwind first": inward MOVE deltas are routed into
     * `onPullDistance` instead of scrolling, and — worse — a fling started
     * while distance != 0 is *absorbed into the edge* rather than scrolling the
     * list. Our rebound runs on its own animator for ~350ms after every
     * boundary hit, so during that tail every fling was silently swallowed:
     * the "I stop, then the list refuses to scroll for a moment" report from
     * Android 16 hardware.
     *
     * The distance only matters to RecyclerView while a finger actively owns
     * the stretch. During the self-driven rebound — and in any ownerless state
     * — the visual is ours alone, so report zero and let RecyclerView
     * scroll/fling normally underneath it. `!pointerOwnsGesture → 0` is the
     * invariant that makes a parked ownerless PULL harmless even if one ever
     * slips past [settleOwnerlessEdge].
     */
    internal fun distance(edge: FlueEdgePhysics.Edge): Float =
        if (!pointerOwnsGesture || physics.isAnimating) 0f else physics.distance(edge)

    internal fun draw(
        @Suppress("UNUSED_PARAMETER") edge: FlueEdgePhysics.Edge,
        @Suppress("UNUSED_PARAMETER") canvas: Canvas,
    ): Boolean = physics.needsAnimationFrame

    private fun stopAnimatorForInput() {
        if (animator != null) {
            animator?.cancel()
            animator = null
        }
        // Cancelling the animator while `phase` is still EXTENDING/REBOUND
        // leaves `physics.isAnimating == true` with nothing driving it. Both
        // `release()` and `endGesture()` guard on `isAnimating`, so that
        // state froze the edge offset permanently and every later MOVE was
        // swallowed unwinding it. Handing the offset back to PULL keeps the
        // visible transform but makes the state machine consistent again.
        physics.takeOver()
    }

    /**
     * Repairs the "animating with no animator" state should any caller ever
     * recreate it. Cheap enough to run on every publish path.
     */
    private fun ensureAnimatorMatchesPhase() {
        if (!released && physics.isAnimating && animator == null) startAnimator()
    }

    private fun startAnimator() {
        if (animator != null || !physics.isAnimating || released) return
        lastFrameTimeMs = android.os.SystemClock.uptimeMillis()
        animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2_000L
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val now = android.os.SystemClock.uptimeMillis()
                val delta = (now - lastFrameTimeMs).coerceAtLeast(0L)
                lastFrameTimeMs = now
                if (!physics.advanceBy(delta)) {
                    cancel()
                    return@addUpdateListener
                }
                publish()
                owner.postInvalidateOnAnimation()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (animator !== animation) return
                    animator = null
                    if (!cancelled) physics.finishAnimation()
                    publish()
                }
            })
            start()
        }
    }

    private fun publish() {
        onOffsetChanged(physics.offset)
        owner.postInvalidateOnAnimation()
    }

    fun releaseOwner() {
        released = true
        animator?.cancel()
        animator = null
        physics.finish(FlueEdgePhysics.Edge.TOP)
        physics.finish(FlueEdgePhysics.Edge.BOTTOM)
        topReleaseCandidate = false
        pointerOwnsGesture = false
        onOffsetChanged(0f)
    }
}

/**
 * Allocation-free pointer-coordinate relay used only on API 27-30.
 *
 * RecyclerView must see a continuous synthetic pointer coordinate after the
 * edge consumes part of a MOVE. Keeping both coordinates prevents the next
 * physical sample from replaying the consumed distance.
 */
internal class NativeLegacyEdgePointerState {
    private var physicalY = Float.NaN
    private var forwardedY = Float.NaN

    val isStarted: Boolean
        get() = physicalY.isFinite() && forwardedY.isFinite()

    fun begin(pointerY: Float) {
        val safeY = pointerY.takeIf(Float::isFinite) ?: 0f
        physicalY = safeY
        forwardedY = safeY
    }

    fun rebase(pointerY: Float) {
        val safeY = pointerY.takeIf(Float::isFinite) ?: return
        if (!isStarted) {
            begin(safeY)
            return
        }
        val offset = forwardedY - physicalY
        physicalY = safeY
        forwardedY = safeY + offset
    }

    fun physicalDeltaTo(pointerY: Float): Float {
        if (!isStarted || !pointerY.isFinite()) return 0f
        return pointerY - physicalY
    }

    /** Advances the relay and returns the Y offset to apply to the MotionEvent. */
    fun advanceTo(pointerY: Float, consumedPointerDeltaPx: Float): Float {
        val safeY = pointerY.takeIf(Float::isFinite) ?: return currentOffset()
        if (!isStarted) {
            begin(safeY)
            return 0f
        }
        val physicalDelta = safeY - physicalY
        val consumed = consumedPointerDeltaPx.takeIf {
            it.isFinite() &&
                (it == 0f || physicalDelta == 0f || it * physicalDelta > 0f) &&
                abs(it) <= abs(physicalDelta)
        } ?: 0f
        forwardedY += physicalDelta - consumed
        physicalY = safeY
        return forwardedY - safeY
    }

    fun forwardedDeltaFrom(originY: Float): Float =
        if (forwardedY.isFinite() && originY.isFinite()) forwardedY - originY else 0f

    fun currentOffset(): Float =
        if (isStarted) forwardedY - physicalY else 0f

    fun reset() {
        physicalY = Float.NaN
        forwardedY = Float.NaN
    }
}

/**
 * Allocation-free terminal velocity sample for the API 27-30 edge relay.
 *
 * A stationary UP may reuse the last moving sample only while it is still
 * inside VelocityTracker's short history window. Otherwise a finger that
 * stopped before lifting would incorrectly launch a fallback fling.
 */
internal class NativeLegacyPointerVelocityState(
    maximumSampleAgeMs: Long = DEFAULT_MAXIMUM_SAMPLE_AGE_MS,
) {
    private val maximumSampleAgeMs = maximumSampleAgeMs.coerceAtLeast(0L)
    private var lastPointerY = Float.NaN
    private var lastEventTime = Long.MIN_VALUE

    var pointerVelocityY: Float = 0f
        private set

    fun begin(pointerY: Float, eventTime: Long) {
        lastPointerY = pointerY.takeIf(Float::isFinite) ?: Float.NaN
        lastEventTime = eventTime
        pointerVelocityY = 0f
    }

    fun record(
        pointerY: Float,
        eventTime: Long,
        preserveWhenStationary: Boolean,
    ) {
        if (
            !lastPointerY.isFinite() ||
            !pointerY.isFinite() ||
            lastEventTime == Long.MIN_VALUE
        ) {
            begin(pointerY, eventTime)
            return
        }
        val elapsed = eventTime - lastEventTime
        if (elapsed <= 0L) {
            begin(pointerY, eventTime)
            return
        }
        val delta = pointerY - lastPointerY
        pointerVelocityY = when {
            delta != 0f -> (delta * 1_000f / elapsed)
                .takeIf(Float::isFinite)
                ?: 0f
            preserveWhenStationary && elapsed <= maximumSampleAgeMs -> pointerVelocityY
            else -> 0f
        }
        lastPointerY = pointerY
        lastEventTime = eventTime
    }

    fun reset() {
        lastPointerY = Float.NaN
        lastEventTime = Long.MIN_VALUE
        pointerVelocityY = 0f
    }

    private companion object {
        const val DEFAULT_MAXIMUM_SAMPLE_AGE_MS = 100L
    }
}

private class FlueSpringEdgeEffect(
    context: Context,
    private val edge: FlueEdgePhysics.Edge,
    private val factory: FlueSpringEdgeEffectFactory,
) : EdgeEffect(context) {
    override fun setSize(width: Int, height: Int) {
        super.setSize(width, height)
        factory.updateViewport(height)
    }

    override fun onPull(deltaDistance: Float) {
        factory.pull(edge, deltaDistance)
    }

    override fun onPull(deltaDistance: Float, displacement: Float) {
        factory.pull(edge, deltaDistance)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun onPullDistance(deltaDistance: Float, displacement: Float): Float {
        return factory.pull(edge, deltaDistance)
    }

    override fun onAbsorb(velocity: Int) {
        factory.absorb(edge, velocity)
    }

    override fun onRelease() {
        factory.release(edge)
    }

    override fun finish() {
        factory.finish(edge)
    }

    override fun isFinished(): Boolean = factory.isFinished(edge)

    @RequiresApi(Build.VERSION_CODES.S)
    override fun getDistance(): Float = factory.distance(edge)

    override fun draw(canvas: Canvas): Boolean = factory.draw(edge, canvas)

    override fun getMaxHeight(): Int = ceil(factory.offsetPx.let { abs(it) }).toInt()
}
