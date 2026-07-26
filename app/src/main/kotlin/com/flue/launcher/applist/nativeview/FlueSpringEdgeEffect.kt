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

    /**
     * True only between the real ACTION_DOWN and the real ACTION_UP/CANCEL of
     * the stream that owns this view.
     *
     * This is the single most load-bearing flag in the class. On API 31+ the
     * *only* thing RecyclerView asks about the edge is [EdgeEffect.getDistance];
     * a non-zero answer makes it (a) route inward MOVE deltas into
     * `onPullDistance` instead of scrolling and (b) zero out the velocity of
     * every fling. So a stretch that outlives its pointer does not merely look
     * wrong — it disables scrolling outright until something clears it.
     *
     * The flag must therefore be driven from a funnel that cannot miss a
     * terminal sample: [NativeAppListView.dispatchTouchEvent]. Driving it from
     * `onInterceptTouchEvent` (which bails on `!active`) or from `onTouchEvent`
     * (which only runs when this view owns the stream) leaks it to `true`, and
     * a leaked `true` blocks [release] forever.
     */
    private var pointerOwnsGesture = false

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

    fun cancelWithoutSnap() {
        animator?.cancel()
        animator = null
        physics.takeOver()
        topReleaseCandidate = false
    }

    /**
     * Starts a new touch gesture.
     *
     * The in-flight rebound is deliberately left running. `pull()` and
     * `absorb()` already call `physics.takeOver()` themselves at the moment the
     * pointer really engages the edge, so freezing the offset here only created
     * dead travel: a touch landing during a 350ms rebound used to pin the offset
     * at up to `maxOffsetPx` (≈64–127px on a 454px viewport), and that offset
     * could then only unwind through `pull()`'s inward-consumption path — the
     * finger had to travel the whole stretch plus touch slop before the list
     * scrolled at all. That is the "have to wait a moment before it scrolls
     * again" symptom.
     *
     * Opening the window here is only half the contract; it MUST be closed by
     * [endGesture] on the real terminal sample, from a dispatch site that
     * cannot be skipped.
     */
    fun beginGesture() {
        pointerOwnsGesture = true
        // A stale candidate from the previous gesture must not leak forward,
        // but the rebound animation itself keeps its own clock.
        topReleaseCandidate = false
    }

    /**
     * Closes the pointer window and *guarantees* the edge unwinds.
     *
     * Every exit path — the real UP/CANCEL, a scene going inactive, detach,
     * release — funnels through here, and none of them may leave the machine
     * parked in [FlueEdgePhysics.Phase.PULL] with a non-zero offset: that is
     * the state RecyclerView reads as "there is stretch to unwind first".
     *
     * Returns true at most once, and only for a real terminal ACTION_UP that
     * crossed the pull-to-face trigger.
     */
    fun endGesture(terminalUp: Boolean): Boolean {
        // 只看锁存的意图，不再复查 isBeyondTrigger：`release()` 已经改为无条件
        // 受理，回弹可能在 UP 之前就把 offset 拉回阈值以内，那时再复查等于把
        // 已经达成的意图判为无效 —— 表现就是「上滑到顶回表盘」时灵时不灵。
        // 意图在 pull() 越过阈值的那一刻锁存，这里只负责消费一次。
        val shouldTrigger = terminalUp && topReleaseCandidate
        topReleaseCandidate = false
        pointerOwnsGesture = false
        settleOwnerlessEdge()
        return shouldTrigger
    }

    /**
     * Hands a stretch that no pointer owns back to the rebound clock.
     *
     * Two producers create such a stretch without any gesture at all: the
     * programmatic `scrollBy` used by drag auto-scroll (RecyclerView routes its
     * unconsumed remainder through `pullGlows`), and [stopAnimatorForInput],
     * which converts a running rebound into `PULL` on every `onPullDistance` —
     * including RecyclerView's zero-delta `stopGlowAnimations` probe. Left
     * alone, either one pins `getDistance()` above zero forever.
     */
    private fun settleOwnerlessEdge() {
        if (released || pointerOwnsGesture) return
        val edge = physics.activeEdgeOrNull ?: return
        if (physics.isAnimating) {
            // "Animating with nothing driving it" is just as sticky as a parked
            // PULL, because both `release` and this method refuse to restart it.
            ensureAnimatorMatchesPhase()
            return
        }
        physics.release(edge)
        publish()
        startAnimator()
    }

    /** Public seam for hosts that end an interaction outside the touch stream. */
    fun settleIfOwnerless() {
        settleOwnerlessEdge()
    }

    fun finish() {
        animator?.cancel()
        animator = null
        physics.finish(FlueEdgePhysics.Edge.TOP)
        physics.finish(FlueEdgePhysics.Edge.BOTTOM)
        topReleaseCandidate = false
        // Deliberately does *not* clear `pointerOwnsGesture`. This is called
        // mid-stream by the drag hand-off, and claiming the finger is gone
        // there let `release()` snap the content under a held pointer while
        // simultaneously desynchronising the real UP from the window that the
        // UP is supposed to close.
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
        // 锁存而非每帧重算：一旦这一次手势里越过过阈值，意图就成立，之后
        // 手指回退一点或回弹把 offset 拉回来都不该撤销它。清零只发生在
        // beginGesture / endGesture / finish 这些真正的边界上。
        if (edge == FlueEdgePhysics.Edge.TOP &&
            physics.isBeyondTrigger(FlueEdgePhysics.Edge.TOP)
        ) {
            topReleaseCandidate = true
        }
        publish()
        // A pull with no pointer behind it (drag auto-scroll, smooth scroll,
        // RecyclerView's zero-delta DOWN probe) must not park the machine.
        settleOwnerlessEdge()
        return consumed / owner.height.coerceAtLeast(1)
    }

    internal fun absorb(edge: FlueEdgePhysics.Edge, velocity: Int) {
        if (released) return
        physics.absorb(edge, velocity.toFloat())
        topReleaseCandidate = false
        publish()
        startAnimator()
        settleOwnerlessEdge()
    }

    /**
     * Honours `EdgeEffect.onRelease()` unconditionally.
     *
     * This deliberately reverses an earlier guard that ignored the callback
     * while a finger was down. RecyclerView only asks for a release when the
     * stretch has stopped being justified, and on API 31+ there are exactly
     * three producers, all of them correct:
     *
     * * `releaseVerticalGlow`, when `canScrollVertically(dir)` is true — i.e.
     *   the list scrolled away from the boundary, so the stretch is stale.
     * * `releaseVerticalGlow`, when the distance has just reached zero.
     * * `considerReleasingGlowsOnScroll`, when a scroll ran the opposite way.
     *
     * Refusing those left `offsetPx` latched for the whole gesture. Because
     * `releaseVerticalGlow` charges the finger one pixel of dead travel per
     * pixel of latched offset the moment the list returns to the boundary, the
     * user paid for the *entire* accumulated stretch before the list moved
     * again — "the more I overscroll one way, the further I have to drag back
     * before anything happens". The stretch also stayed visible on every row
     * via `pullOffsetPx` the whole time.
     *
     * A release is always interruptible: the next `onPullDistance` runs
     * [stopAnimatorForInput], which hands the current visual value straight
     * back to `PULL`, so re-pulling mid-rebound is seamless.
     */
    internal fun release(edge: FlueEdgePhysics.Edge) {
        if (released) return
        if (physics.isAnimating) {
            ensureAnimatorMatchesPhase()
            return
        }
        physics.release(edge)
        // 刻意不清 topReleaseCandidate：视觉上的回弹和「用户想不想回表盘」
        // 是两件事。release() 现在无条件受理（RecyclerView 在拉伸失效时随时
        // 会请求它），如果顺手把意图也抹掉，手指还没抬起来上滑回表盘就失效了。
        // 意图只由真正的终结样本消费 —— 见 endGesture。
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
        // 同 pull()：锁存，不每帧重算。
        if (physics.isBeyondTrigger(FlueEdgePhysics.Edge.TOP)) topReleaseCandidate = true
        publish()
        settleOwnerlessEdge()
        return consumed
    }

    internal val hasActiveEdge: Boolean
        get() = physics.hasActiveEdge

    internal fun isFinished(edge: FlueEdgePhysics.Edge): Boolean =
        !physics.isActive(edge)

    /**
     * What API 31+ RecyclerView sees through `EdgeEffect.getDistance()`.
     *
     * This is the single gate on all scrolling, so it is written as a hard
     * invariant rather than as a description of our own state:
     *
     * * `RecyclerView.onTouchEvent` does `dy -= releaseVerticalGlow(dy, x)`
     *   whenever this is non-zero, so every inward pixel is spent unwinding the
     *   stretch before the list moves at all.
     * * `RecyclerView.fling()` **zeroes `velocityY`** whenever this is non-zero
     *   for either vertical glow. A stale non-zero distance therefore swallows
     *   every fling, silently and permanently.
     *
     * Both behaviours are only ever correct while a finger is physically down
     * and owns the stretch. Keying this on `isAnimating` (the previous attempt)
     * hid the *rebound* — which was never the problem — while leaving exactly
     * the dangerous case exposed: a `PULL` phase parked by `takeOver()` with no
     * pointer behind it. That is what produced "it just stops scrolling for no
     * reason" and "after interrupting the launch animation the list is dead".
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
        // leaves `physics.isAnimating == true` with nothing driving it, which
        // froze the offset permanently. Handing it back to PULL keeps the
        // visible transform and makes the state machine consistent again — but
        // PULL with no pointer behind it is itself a scroll-blocking state, so
        // every caller of this must follow up with `settleOwnerlessEdge()`.
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
