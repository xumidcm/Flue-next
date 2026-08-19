package com.flue.launcher.applist.nativeview

import kotlin.math.abs
import kotlin.math.min

internal data class NativeDragPolicy(
    val startThresholdPx: Float,
    val autoScrollEdgePx: Float,
    val maxAutoScrollPxPerFrame: Float,
    /** 合成所需停留的**上限**：指针还在接近速度门槛地漂移时用这一档。 */
    val folderHoverMillis: Long = 500L,
    /**
     * 合成所需停留的**下限**：指针已经真正停稳时用这一档。
     *
     * 老师报「合成的时间可以改得更自然，不需要硬死在 500ms」。硬死一个值的问题是它
     * 同时要服务两种意图完全不同的动作：手指已经停在图标正中不动了（明摆着要合成，
     * 却还得干等半秒），和手指正慢慢划过去（这时立刻合成就是误触）。改成按
     * [NativeFolderHoverGate] 里记的「本次悬停最慢采样」在两档之间插值。
     */
    val folderHoverSettledMillis: Long = 240L,
    val folderHoverMaxSpeedPxPerMs: Float,
    val longPressMillis: Long = 620L,
)

internal class NativeDragPolicyProfiles(
    density: Float,
) {
    private val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
    private val linear = NativeDragPolicy(
        startThresholdPx = 20f * safeDensity,
        autoScrollEdgePx = 84f * safeDensity,
        maxAutoScrollPxPerFrame = 30f,
        folderHoverMillis = 500L,
        folderHoverSettledMillis = 240L,
        folderHoverMaxSpeedPxPerMs = 0.55f,
        longPressMillis = 620L,
    )
    private val spatial = NativeDragPolicy(
        startThresholdPx = 20f * safeDensity,
        autoScrollEdgePx = 96f * safeDensity,
        maxAutoScrollPxPerFrame = 26f,
        folderHoverMillis = 700L,
        // 空间布局的图标彼此更近，误触代价更高，下限比线性档留宽一点。
        folderHoverSettledMillis = 300L,
        folderHoverMaxSpeedPxPerMs = 0.55f,
        longPressMillis = 620L,
    )

    fun forPresentation(presentation: NativeAppListPresentation): NativeDragPolicy =
        when (presentation) {
            NativeAppListPresentation.LINEAR -> linear
            NativeAppListPresentation.HONEYCOMB,
            NativeAppListPresentation.DENSE_GRID,
            NativeAppListPresentation.FLOW,
            NativeAppListPresentation.RING,
            -> spatial
        }
}

internal fun nativeAutoScrollDelta(
    pointerY: Float,
    viewportHeight: Float,
    policy: NativeDragPolicy,
): Float {
    if (viewportHeight <= 0f || policy.autoScrollEdgePx <= 0f) return 0f
    // The raw 84/96dp bands were sized for a phone. On a watch viewport
    // (454px at density 2, or worse on 320px hardware) they covered most or
    // all of the screen — on the smallest watches the two bands overlapped and
    // the top branch won everywhere, so *any* drag position auto-scrolled and
    // the reorder loop ran at frame rate. Clamping keeps a neutral middle of
    // at least ~68% of the viewport on every device.
    val edge = policy.autoScrollEdgePx.coerceAtMost(viewportHeight * 0.16f)
    // Decide by proximity first so a (theoretical) overlap can never let the
    // top branch swallow the bottom half of the screen.
    val nearTop = pointerY < viewportHeight - pointerY
    return when {
        nearTop && pointerY < edge ->
            -policy.maxAutoScrollPxPerFrame *
                ((edge - pointerY) / edge).coerceIn(0f, 1f)
        !nearTop && pointerY > viewportHeight - edge ->
            policy.maxAutoScrollPxPerFrame *
                ((pointerY - (viewportHeight - edge)) / edge).coerceIn(0f, 1f)
        else -> 0f
    }
}

internal class NativeFolderHoverGate(
    private val policy: NativeDragPolicy,
) {
    private var targetKey: String? = null
    private var startedAtMillis = 0L

    /**
     * 本次悬停里见过的**最慢**采样。
     *
     * 用最慢而不是最新的那一下，有两个好处：
     *
     *  * **语义对**。「手指在这个图标上停稳过」才是合成意图的证据；用最新采样的话，
     *    停稳半秒之后一次轻微抖动就能把门槛重新抬高。
     *  * **不会抖**。这个值只减不增，所以要求的停留时长单调下降 —— 一旦满足就不会
     *    再被推翻，不需要担心 FolderHoverChanged 在 null 与目标之间来回闪。
     */
    private var slowestSpeedPxPerMs = Float.MAX_VALUE
    private var armed = false

    fun update(candidateKey: String?, speedPxPerMs: Float, nowMillis: Long): String? {
        val validCandidate = candidateKey?.takeIf {
            speedPxPerMs.isFinite() && abs(speedPxPerMs) <= policy.folderHoverMaxSpeedPxPerMs
        }
        if (validCandidate == null) {
            reset()
            return null
        }
        if (targetKey != validCandidate) {
            targetKey = validCandidate
            startedAtMillis = nowMillis
            slowestSpeedPxPerMs = abs(speedPxPerMs)
            armed = false
            return null
        }
        slowestSpeedPxPerMs = min(slowestSpeedPxPerMs, abs(speedPxPerMs))
        if (armed) return validCandidate
        if (nowMillis - startedAtMillis >= requiredDwellMillis()) {
            armed = true
            return validCandidate
        }
        return null
    }

    /**
     * 按「停得多稳」在下限与上限之间插值出这一次需要的停留时长。
     *
     * 完全停住（最慢采样 = 0）取 [NativeDragPolicy.folderHoverSettledMillis]；
     * 一直贴着速度门槛漂移取 [NativeDragPolicy.folderHoverMillis]。
     */
    private fun requiredDwellMillis(): Long {
        val ceiling = policy.folderHoverMillis
        val floor = policy.folderHoverSettledMillis.coerceIn(0L, ceiling)
        val limit = policy.folderHoverMaxSpeedPxPerMs
        if (limit <= 0f || !limit.isFinite()) return ceiling
        val settled = slowestSpeedPxPerMs.takeIf { it.isFinite() } ?: limit
        val normalized = (settled / limit).coerceIn(0f, 1f)
        return floor + ((ceiling - floor) * normalized).toLong()
    }

    fun reset() {
        targetKey = null
        startedAtMillis = 0L
        slowestSpeedPxPerMs = Float.MAX_VALUE
        armed = false
    }
}

internal fun nativeFolderTargetAllowed(
    source: NativeAppListBoundRow<*>,
    target: NativeAppListBoundRow<*>,
): Boolean =
    source.stableKey != target.stableKey &&
        target.entry.type != com.flue.launcher.applist.model.AppItemType.SHORTCUT
